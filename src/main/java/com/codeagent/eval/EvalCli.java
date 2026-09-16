package com.codeagent.eval;

import com.codeagent.core.*;

import java.util.List;

/**
 * 评测命令行入口（端到端跑任务集并输出报告）：
 *   java -cp out com.codeagent.eval.EvalCli --tasks evalset/basic.jsonl --model glm-4.6v
 *   java -cp out com.codeagent.eval.EvalCli --tasks evalset/basic.jsonl --mock   # 离线，无需 key
 *
 * 默认读 CODEAGENT_API_KEY 环境变量；--mock 用离线模型。复用 AgentConfig 解析其它参数。
 */
public class EvalCli {
    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.load(args);
        String tasksPath = "evalset/basic.jsonl";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--tasks") && i + 1 < args.length) tasksPath = args[++i];
        }

        ChatModel model;
        if (cfg.useMock || cfg.apiKey == null || cfg.apiKey.isBlank()) {
            model = new OfflineModel();
        } else {
            model = new OpenAiCompatibleChatModel(cfg.baseUrl, cfg.apiKey, cfg.model);
        }

        List<EvalTask> tasks = EvalHarness.loadTasks(tasksPath);
        if (tasks.isEmpty()) {
            System.out.println("no tasks loaded from " + tasksPath);
            return;
        }
        String ws = System.getProperty("java.io.tmpdir") + "/codeagent-eval";
        EvalHarness harness = new EvalHarness(model, ws, cfg.acceptEdits);
        EvalReport report = harness.runAll(tasks);

        System.out.println(report.summary());
        for (EvalResult r : report.results) {
            System.out.println("  [" + (r.passed ? "PASS" : "FAIL") + "] " + r.id
                    + " (" + r.status + ", steps=" + r.steps + ") " + r.reason);
        }
        System.out.println(report.toJson());
    }

    /** 离线模型：仅用于 --mock 跑通评测管线，不调用真实 LLM */
    static class OfflineModel implements ChatModel {
        @Override
        public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
            ChatResponse r = new ChatResponse();
            r.content = "[offline eval] no LLM configured";
            r.usage = new Usage(1, 1);
            return r;
        }
    }
}
