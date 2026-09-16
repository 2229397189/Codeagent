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
}
