package com.codeagent.workflow;

import com.codeagent.core.AgentLoop;
import com.codeagent.core.ChatModel;
import com.codeagent.core.Message;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Execute 多 Agent 编排（基础版，对齐 AutoGen / LangGraph 的 planner-executor 思路）：
 *   1) planner 子 Agent 把目标拆成有序步骤（JSON）；
 *   2) executor 子 Agent 逐步执行，每步结果回灌给下一步的上下文；
 *   3) synthesizer 子 Agent 综合所有步骤结果产出最终答复。
 * 每个子 Agent 都是一次独立的 AgentLoop 回合（复用统一工具协议与权限边界），
 * 因此多 Agent 协作仍受同一套权限 / 上下文治理约束，不是「另开一个不受控的模型」。
 *
 * M13 修复：原实现只取子 Agent 最后文本，若子 Agent 未达 FINAL（FAILED / MAX_STEPS / 空输出）
 * 会把残缺结果照写进 context，失败被静默吞掉。现改为：
 *   - chatOnce 返回完整 AgentTurnResult（含 status），Coordinator 能判断成败；
 *   - 单步失败 → 重试 1 次（该步最多 2 次尝试）；
 *   - 仍失败 → 该步标记 [FAILED]，并触发一次重规划（maxReplans 硬截断，默认 1，
 *     即 planner 总调用数上限 = 2），重规划后只执行剩余步骤。
 */
public class Coordinator {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final PermissionManager perms;
    private final int maxPlanSteps;
    private final int maxExecSteps;
    private final int maxReplans;

    public Coordinator(ChatModel model, ToolRegistry tools, PermissionManager perms) {
        this(model, tools, perms, 8, 12, 1);
    }

    public Coordinator(ChatModel model, ToolRegistry tools, PermissionManager perms,
                       int maxPlanSteps, int maxExecSteps) {
        this(model, tools, perms, maxPlanSteps, maxExecSteps, 1);
    }

    public Coordinator(ChatModel model, ToolRegistry tools, PermissionManager perms,
                       int maxPlanSteps, int maxExecSteps, int maxReplans) {
        this.model = model;
        this.tools = tools;
        this.perms = perms;
        this.maxPlanSteps = Math.max(1, maxPlanSteps);
        this.maxExecSteps = Math.max(1, maxExecSteps);
        this.maxReplans = Math.max(0, maxReplans);
    }

    public String execute(String goal) {
        int planAttempts = 0;
        String planGoal = goal;
        StringBuilder context = new StringBuilder();
        context.append("Goal: ").append(goal).append("\n");
        while (true) {
            String planJson = plan(planGoal);
            List<Plan.Step> steps = Plan.parse(planJson);
            if (steps.size() > maxPlanSteps) {
                steps = new ArrayList<>(steps.subList(0, maxPlanSteps));
            }
            if (steps.isEmpty()) {
                return synthesize(planGoal, context.toString(), 0);
            }
            boolean allOk = true;
            for (Plan.Step step : steps) {
                StepResult sr = executeStep(step, context.toString());
                context.append("Step ").append(step.index).append(": ").append(step.description).append("\n");
                // 失败步带 [FAILED] 标记，让 synthesizer（和人）能看清哪一步出了问题
                context.append("Result: ").append(sr.ok ? sr.text : "[FAILED] " + sr.text).append("\n");
                if (!sr.ok) allOk = false;
            }
            if (allOk || planAttempts >= maxReplans) {
                return synthesize(planGoal, context.toString(), steps.size());
            }
            // 触发一次重规划：把失败信号带回 planner，让它产出覆盖剩余工作的新计划
            planAttempts++;
            planGoal = goal + " (NOTE: a previous plan had one or more steps that failed to complete; "
                    + "produce a revised plan focusing on the remaining work)";
        }
    }

    /** 执行单步：失败时重试 1 次（最多 2 次尝试）。返回该步最终文本与是否成功。 */
    private StepResult executeStep(Plan.Step step, String context) {
        String text = "";
        boolean ok = false;
        for (int attempt = 0; attempt <= 1 && !ok; attempt++) {
            AgentLoop.AgentTurnResult r = chatOnce(buildStepMessages(step, context), maxExecSteps);
            text = lastText(r.messages);
            // 失败判定：未达 FINAL，或虽 FINAL 但输出为空（无实质结果）
            boolean failed = (r.status != AgentLoop.TurnStatus.FINAL)
                    || (text == null || text.isEmpty());
            if (!failed) ok = true;
        }
        return new StepResult(ok, text == null ? "" : text);
    }

    private static final class StepResult {
        final boolean ok;
        final String text;
        StepResult(boolean ok, String text) { this.ok = ok; this.text = text; }
    }

    private String plan(String goal) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the planner sub-agent of CodeAgent. Break the goal into an ordered list of concrete steps. Respond ONLY with a JSON array of objects {\"description\": \"...\"}."));
        msgs.add(Message.user("TASK: " + goal + "\nProduce a plan as a JSON array of steps."));
        return chatOnceText(msgs, 1);
    }

    private List<Message> buildStepMessages(Plan.Step step, String context) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the executor sub-agent of CodeAgent. Use tools when needed; when the step is done, reply with a short result."));
        msgs.add(Message.user("STEP " + step.index + ": " + step.description + "\nCONTEXT:\n" + context));
        return msgs;
    }

    private String synthesize(String goal, String context, int stepCount) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the synthesizer sub-agent of CodeAgent. Combine the step results into a final answer for the user."));
        msgs.add(Message.user("SYNTHESIZE the results below into a final answer for the goal: " + goal
                + " (" + stepCount + " steps executed)\n\n" + context));
        return chatOnceText(msgs, 1);
    }

    private AgentLoop.AgentTurnResult chatOnce(List<Message> msgs, int maxSteps) {
        AgentLoop loop = new AgentLoop();
        loop.model.chatModel = model;
        loop.permissionManager = perms;
        loop.maxSteps = maxSteps;
        return loop.runTurn(msgs, tools);
    }

    private String chatOnceText(List<Message> msgs, int maxSteps) {
        return lastText(chatOnce(msgs, maxSteps).messages);
    }

    private static String lastText(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.role == Message.Role.assistant && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                return m.content;
            }
        }
        return "";
    }
}
