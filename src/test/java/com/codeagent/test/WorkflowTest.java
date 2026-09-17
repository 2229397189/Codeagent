package com.codeagent.test;

import com.codeagent.core.ChatModel;
import com.codeagent.core.ChatResponse;
import com.codeagent.core.Message;
import com.codeagent.core.ToolSpec;
import com.codeagent.core.Usage;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.ToolRegistry;
import com.codeagent.workflow.Coordinator;
import com.codeagent.workflow.Plan;

import java.util.List;

/**
 * 多 Agent 工作流测试：断言「返回正确的值」，而非仅不报错。
 * 用脚本化模型（按用户消息内容区分角色）跑通 Plan-and-Execute 编排：
 * planner 产出计划 -> executor 逐步执行 -> synthesizer 综合，并验证 maxPlanSteps 截断生效。
 */
public class WorkflowTest {

    public static int run() {
        int fails = 0;
        System.out.println("[WorkflowTest]");

        // ---- Plan.parse：对象数组 ----
        {
            List<Plan.Step> steps = Plan.parse("[{\"description\":\"do x\"},{\"description\":\"do y\"}]");
            fails += check("plan parses two steps", steps.size() == 2);
            fails += check("plan indexes steps sequentially",
                    steps.get(0).index == 1 && steps.get(1).index == 2);
            fails += check("plan captures descriptions", "do x".equals(steps.get(0).description));
        }

        // ---- Plan.parse：字符串数组与 {steps:[...]} 包装 ----
        {
            fails += check("plan parses a plain string array", Plan.parse("[\"a\",\"b\",\"c\"]").size() == 3);
            fails += check("plan parses a wrapped steps object",
                    Plan.parse("{\"steps\":[{\"description\":\"only\"}]}").size() == 1);
            fails += check("plan tolerates garbage input", Plan.parse("not json at all").isEmpty());
            fails += check("plan tolerates null input", Plan.parse(null).isEmpty());
            fails += check("plan strips a ```json fence",
                    Plan.parse("```json\n[{\"description\":\"x\"}]\n```").size() == 1);
            fails += check("plan ignores surrounding prose",
                    Plan.parse("Here is my plan:\n[{\"description\":\"x\"}]\nHope it helps!").size() == 1);
        }

        // ---- Coordinator：完整编排（计划 3 步，不截断）----
        {
            ScriptedModel model = new ScriptedModel();
            Coordinator c = new Coordinator(model, new ToolRegistry(), PermissionManager.ALLOW_ALL);
            String out = c.execute("build a feature");

            fails += check("coordinator reaches the synthesizer", out != null && out.contains("FINAL_SUMMARY"));
            fails += check("coordinator executes every planned step", model.stepCalls == 3);
        }

        // ---- Coordinator：maxPlanSteps 截断 ----
        {
            ScriptedModel model = new ScriptedModel();
            Coordinator c = new Coordinator(model, new ToolRegistry(), PermissionManager.ALLOW_ALL, 2, 12);
            c.execute("build a feature");
            fails += check("coordinator truncates the plan to maxPlanSteps", model.stepCalls == 2);
        }

        // ---- Coordinator：单步失败 → 重试 1 次（B2，零回归之外的增强）----
        {
            ProgrammableModel m = new ProgrammableModel();
            m.step2AlwaysFails = false; // step2 首次失败、重试成功
            Coordinator c = new Coordinator(m, new ToolRegistry(), PermissionManager.ALLOW_ALL);
            String out = c.execute("build a feature");
            fails += check("retry: planner called once when a step recovers", m.planCalls == 1);
            fails += check("retry: step 2 executed twice (1 fail + 1 retry)", m.stepCalls == 4); // 1+2+1
            fails += check("retry: reaches synthesizer", out != null && out.contains("FINAL_SUMMARY"));
        }

        // ---- Coordinator：持续失败 → 触发一次重规划（B3/B4/B5）----
        {
            ProgrammableModel m = new ProgrammableModel();
            m.step2AlwaysFails = true;  // step2 永远失败
            m.echoSynth = true;         // synthesizer 回显 context，便于断言 [FAILED] 标记
            Coordinator c = new Coordinator(m, new ToolRegistry(), PermissionManager.ALLOW_ALL);
            String out = c.execute("build a feature");
            fails += check("replan: planner called exactly twice (initial + 1 replan)", m.planCalls == 2);
            fails += check("replan: still reaches synthesizer without hang",
                    out != null && m.synthCalls == 1);
            fails += check("replan: failed step marked [FAILED] in context",
                    out.contains("[FAILED]"));
        }

        // ---- Coordinator：maxReplans=0 时 planner 只调用一次（B4 上限分支）----
        {
            ProgrammableModel m = new ProgrammableModel();
            m.step2AlwaysFails = true;
            Coordinator c = new Coordinator(m, new ToolRegistry(), PermissionManager.ALLOW_ALL, 8, 12, 0);
            c.execute("build a feature");
            fails += check("maxReplans=0: planner called once only", m.planCalls == 1);
        }

        return fails;
    }

    /**
     * 脚本化模型：按用户消息里的角色标记返回确定性结果，
     * 从而在不依赖真实 LLM 的前提下断言编排逻辑（计划 -> 执行 -> 综合）。
     */
    static class ScriptedModel implements ChatModel {
        int stepCalls = 0;

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
            String lastUser = "";
            for (Message m : messages) {
                if (m.role == Message.Role.user) lastUser = m.content == null ? "" : m.content;
            }
            ChatResponse r = new ChatResponse();
            r.usage = new Usage(1, 1);

            if (lastUser.contains("Produce a plan")) {
                r.content = "[{\"description\":\"step one\"},{\"description\":\"step two\"},{\"description\":\"step three\"}]";
            } else if (lastUser.contains("STEP ")) {
                stepCalls++;
                r.content = "done";
            } else if (lastUser.contains("SYNTHESIZE")) {
                r.content = "FINAL_SUMMARY";
            } else {
                r.content = "unknown";
            }
            return r;
        }
    }

    static int check(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS " + name);
            return 0;
        }
        System.out.println("  FAIL " + name);
        return 1;
    }

    /**
     * 可编程脚本模型：支持「单步失败可重试」「持续失败触发重规划」「synthesizer 回显」等场景，
     * 用于断言 Coordinator 的失败重试与重规划逻辑（返回正确值而非仅不报错）。
     */
    static class ProgrammableModel implements ChatModel {
        int stepCalls = 0;
        int planCalls = 0;
        int synthCalls = 0;
        int step2Attempts = 0;
        boolean step2AlwaysFails = false;
        boolean echoSynth = false;

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
            String lastUser = "";
            for (Message m : messages) {
                if (m.role == Message.Role.user) lastUser = m.content == null ? "" : m.content;
            }
            ChatResponse r = new ChatResponse();
            r.usage = new Usage(1, 1);
            String lu = lastUser.toLowerCase();
            // 角色判定必须按 Coordinator 实际发出的消息前缀，不能用 contains("step ")，
            // 否则综合阶段回灌的 context 里 "Step 1:" / "(3 steps executed)" 会让模型把
            // synthesize 请求误判成 executor 步骤（测试模型缺陷，非 Coordinator 缺陷）。
            if (lu.contains("produce a plan")) {
                planCalls++;
                r.content = "[{\"description\":\"step one\"},{\"description\":\"step two\"},{\"description\":\"step three\"}]";
            } else if (lu.startsWith("step ")) {
                stepCalls++;
                if (lu.startsWith("step 2:")) {
                    step2Attempts++;
                    // 首次尝试失败（空 FINAL）→ 触发重试；alwaysFails 则每次都失败
                    if (step2AlwaysFails || step2Attempts == 1) {
                        r.content = "";
                    } else {
                        r.content = "done";
                    }
                } else {
                    r.content = "done";
                }
            } else if (lu.startsWith("synthesize")) {
                synthCalls++;
                r.content = echoSynth ? lastUser : "FINAL_SUMMARY";
            } else {
                r.content = "unknown";
            }
            return r;
        }
    }
}
