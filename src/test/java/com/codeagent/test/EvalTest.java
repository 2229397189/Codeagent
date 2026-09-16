package com.codeagent.test;

import com.codeagent.core.*;
import com.codeagent.eval.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测模块测试：用确定性脚本模型跑端到端任务，断言"聚合数学正确"而非仅不报错。
 * 复用项目零依赖测试入口（AllTests）的 PASS 打印约定。
 */
public class EvalTest {
    static int failed = 0;

    static void check(String name, boolean cond) {
        System.out.println("  " + (cond ? "PASS " : "FAIL ") + name);
        if (!cond) failed++;
    }

    public static int run() {
        failed = 0;
        System.out.println("[EvalTest]");

        // 确定性脚本模型：根据最后一条 user 指令决定调哪个工具；工具结果回来后给终态
        ChatModel scripted = new ChatModel() {
            @Override
            public ChatResponse chat(List<Message> msgs, List<ToolSpec> tools) {
                boolean hasTool = false;
                for (Message m : msgs) if (m.role == Message.Role.tool) { hasTool = true; break; }
                ChatResponse r = new ChatResponse();
                r.usage = new Usage(10, 5);
                if (hasTool) {
                    r.content = "DONE";
                    return r;
                }
                String user = lastUser(msgs).toLowerCase();
                if (user.contains("edit")) {
                    r.toolCalls.add(new ToolCall("c1", "edit_file", args("path", "notes.txt", "old", "x", "new", "DONE")));
                } else if (user.contains("echo")) {
                    r.toolCalls.add(new ToolCall("c1", "run_command", args("command", "echo hello")));
                } else if (user.contains("list")) {
                    r.toolCalls.add(new ToolCall("c1", "list_files", args("path", ".")));
                } else {
                    r.content = "Java GC (Garbage Collection) reclaims memory.";
                }
                return r;
            }
        };

        try {
            Path ws = Files.createTempDirectory("codeagent-eval-test");
            Files.writeString(ws.resolve("notes.txt"), "x"); // 让 edit_file 能真正应用
            EvalHarness h = new EvalHarness(scripted, ws.toString(), true);

            List<EvalTask> tasks = new ArrayList<>();
            tasks.add(task("t1", "please echo hello", "run_command", null));
            tasks.add(task("t2", "explain Garbage Collection", null, "GC"));
            tasks.add(task("t3", "edit notes.txt to DONE", "edit_file", null));
            tasks.add(task("t4", "list the files here", "list_files", null));
            tasks.add(task("t5", "edit the file please", "run_command", null)); // 期望 run_command，但模型调了 edit_file -> 应判 FAIL

            EvalReport rep = h.runAll(tasks);

            check("total == 5", rep.total == 5);
            check("passed == 4", rep.passed == 4);
            check("successRate == 0.8", Math.abs(rep.successRate - 0.8) < 1e-9);
            check("toolAccuracy == 0.75 (3/4)", Math.abs(rep.toolAccuracy - 0.75) < 1e-9);
            check("avgSteps == 1.8 (9/5)", Math.abs(rep.avgSteps - 1.8) < 1e-9);
            check("avgPromptTokens == 10", Math.abs(rep.avgPromptTokens - 10) < 1e-9);
            check("avgCompletionTokens == 5", Math.abs(rep.avgCompletionTokens - 5) < 1e-9);

            check("t1 passed (run_command called)", find(rep, "t1").passed);
            check("t1 expectedToolCalled", find(rep, "t1").expectedToolCalled);
            check("t1 status == FINAL", "FINAL".equals(find(rep, "t1").status));
            check("t2 passed (contains GC)", find(rep, "t2").passed);
            check("t3 passed (edit_file called)", find(rep, "t3").passed);
            check("t4 passed (list_files called)", find(rep, "t4").passed);
            check("t5 failed (wrong tool)", !find(rep, "t5").passed);
            check("t5 reason mentions not called", find(rep, "t5").reason.contains("not called"));

            // 空任务集聚合不崩
            EvalReport empty = EvalReport.aggregate(new ArrayList<>());
            check("empty total == 0", empty.total == 0);
            check("empty successRate == 0", empty.successRate == 0.0);
            check("empty toolAccuracy default 1.0", empty.toolAccuracy == 1.0);

            // 任务解析
            EvalTask parsed = EvalTask.fromJsonLine("{\"id\":\"p1\",\"prompt\":\"hi\",\"expectTool\":\"run_command\",\"expectContains\":\"OK\",\"maxSteps\":7}");
            check("parse id", "p1".equals(parsed.id));
            check("parse expectTool", "run_command".equals(parsed.expectTool));
            check("parse expectContains", "OK".equals(parsed.expectContains));
            check("parse maxSteps", parsed.maxSteps == 7);

        } catch (Exception e) {
            check("no exception: " + e.getMessage(), false);
        }
        return failed;
    }

    private static EvalTask task(String id, String prompt, String expectTool, String expectContains) {
        EvalTask t = new EvalTask();
        t.id = id;
        t.prompt = prompt;
        t.expectTool = expectTool;
        t.expectContains = expectContains;
        return t;
    }

    private static EvalResult find(EvalReport rep, String id) {
        for (EvalResult r : rep.results) if (id.equals(r.id)) return r;
        return null;
    }

    private static String lastUser(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role == Message.Role.user) return msgs.get(i).content == null ? "" : msgs.get(i).content;
        }
        return "";
    }

    private static Map<String, Object> args(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
