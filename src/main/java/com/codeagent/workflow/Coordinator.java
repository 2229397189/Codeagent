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
 */
public class Coordinator {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final PermissionManager perms;
    private final int maxPlanSteps;
    private final int maxExecSteps;

    public Coordinator(ChatModel model, ToolRegistry tools, PermissionManager perms) {
        this(model, tools, perms, 8, 12);
    }

    public Coordinator(ChatModel model, ToolRegistry tools, PermissionManager perms,
                       int maxPlanSteps, int maxExecSteps) {
        this.model = model;
        this.tools = tools;
        this.perms = perms;
        this.maxPlanSteps = Math.max(1, maxPlanSteps);
        this.maxExecSteps = Math.max(1, maxExecSteps);
    }

    public String execute(String goal) {
        String planJson = plan(goal);
        List<Plan.Step> steps = Plan.parse(planJson);
        if (steps.size() > maxPlanSteps) {
            steps = new ArrayList<>(steps.subList(0, maxPlanSteps));
        }

        StringBuilder context = new StringBuilder();
        context.append("Goal: ").append(goal).append("\n");
        for (Plan.Step step : steps) {
            String result = executeStep(step, context.toString());
            context.append("Step ").append(step.index).append(": ").append(step.description).append("\n");
            context.append("Result: ").append(result).append("\n");
        }
        return synthesize(goal, context.toString(), steps.size());
    }

    private String plan(String goal) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the planner sub-agent of CodeAgent. Break the goal into an ordered list of concrete steps. Respond ONLY with a JSON array of objects {\"description\": \"...\"}."));
        msgs.add(Message.user("TASK: " + goal + "\nProduce a plan as a JSON array of steps."));
        return chatOnce(msgs, 1);
    }

    private String executeStep(Plan.Step step, String context) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the executor sub-agent of CodeAgent. Use tools when needed; when the step is done, reply with a short result."));
        msgs.add(Message.user("STEP " + step.index + ": " + step.description + "\nCONTEXT:\n" + context));
        return chatOnce(msgs, maxExecSteps);
    }

    private String synthesize(String goal, String context, int stepCount) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("You are the synthesizer sub-agent of CodeAgent. Combine the step results into a final answer for the user."));
        msgs.add(Message.user("SYNTHESIZE the results below into a final answer for the goal: " + goal
                + " (" + stepCount + " steps executed)\n\n" + context));
        return chatOnce(msgs, 1);
    }

    private String chatOnce(List<Message> msgs, int maxSteps) {
        AgentLoop loop = new AgentLoop();
        loop.model.chatModel = model;
        loop.permissionManager = perms;
        loop.maxSteps = maxSteps;
        loop.runTurn(msgs, tools);
        return lastText(msgs);
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
