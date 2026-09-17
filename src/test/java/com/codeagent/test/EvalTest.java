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

            // ---- P0-1：加载真实评测集 basic.jsonl（30 条）并断言覆盖度 + 聚合 ----
            String evalPath = resolveEvalPath();
            List<EvalTask> fileTasks = EvalHarness.loadTasks(evalPath);
            check("evalset loads 30 tasks", fileTasks.size() == 30);

            // id 唯一
            java.util.Set<String> ids = new java.util.HashSet<>();
            boolean unique = true;
            for (EvalTask t : fileTasks) if (!ids.add(t.id)) unique = false;
            check("evalset ids are unique", unique);

            // 每个任务都解析出非空 id
            boolean allHaveId = true;
            for (EvalTask t : fileTasks) if (t.id == null || t.id.isEmpty()) allHaveId = false;
            check("evalset every task has an id", allHaveId);

            // 覆盖度：6 个内置工具各自被 expectTool 引用 >= 2 次
            java.util.Map<String, Integer> toolCount = new java.util.HashMap<>();
            for (EvalTask t : fileTasks) if (t.expectTool != null)
                toolCount.put(t.expectTool, toolCount.getOrDefault(t.expectTool, 0) + 1);
            String[] six = {"read_file", "grep", "list_files", "edit_file", "patch", "run_command"};
            boolean cov = true;
            for (String tk : six) if (toolCount.getOrDefault(tk, 0) < 2) cov = false;
            check("evalset covers each tool >=2 times", cov);

            // 安全类任务 >= 3（prompt 含注入 / 危险 / 破坏 / 越权标记）
            int safety = 0;
            for (EvalTask t : fileTasks) {
                String p = t.prompt.toLowerCase();
                if (p.contains("忽略") || p.contains("rm -rf") || p.contains("删除") || p.contains("绕过")) safety++;
            }
            check("evalset has >=3 safety tasks", safety >= 3);

            // 向后兼容：t1-t4 不变
            check("t1 expectTool run_command", "run_command".equals(findTask(fileTasks, "t1").expectTool));
            check("t2 expectContains GC", "GC".equals(findTask(fileTasks, "t2").expectContains));
            check("t3 expectTool edit_file", "edit_file".equals(findTask(fileTasks, "t3").expectTool));
            check("t4 expectTool list_files", "list_files".equals(findTask(fileTasks, "t4").expectTool));

            // 端到端：确定性路由模型跑完整 30 条，断言聚合正确、无异常
            Path ws2 = Files.createTempDirectory("codeagent-eval-file");
            EvalHarness h2 = new EvalHarness(evalRouter(), ws2.toString(), true);
            EvalReport rep2 = h2.runAll(fileTasks);
            check("evalset report total == 30", rep2.total == 30);
            check("evalset report has 30 results", rep2.results.size() == 30);

            // 路由模型对每条 expectTool 任务确实调到了对应工具
            int toolTasks = 0, toolOk = 0;
            for (EvalResult r : rep2.results) {
                EvalTask t = findTask(fileTasks, r.id);
                if (t != null && t.expectTool != null) {
                    toolTasks++;
                    if (r.expectedToolCalled) toolOk++;
                }
            }
            check("evalset every expectTool task routed correctly", toolTasks == toolOk && toolTasks > 0);

            // expectContains 任务（知识 / 安全）均通过（路由模型返回了预期子串）
            int containTasks = 0, containOk = 0;
            for (EvalResult r : rep2.results) {
                EvalTask t = findTask(fileTasks, r.id);
                if (t != null && t.expectContains != null) {
                    containTasks++;
                    if (r.passed) containOk++;
                }
            }
            check("evalset every expectContains task passed", containTasks == containOk && containTasks > 0);

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

    private static EvalTask findTask(List<EvalTask> tasks, String id) {
        for (EvalTask t : tasks) if (id.equals(t.id)) return t;
        return null;
    }

    /** 在已知候选路径里找评测集文件，优先绝对路径，回退相对路径。 */
    private static String resolveEvalPath() {
        String[] candidates = {
                "D:/code/CodeAgent/evalset/basic.jsonl",
                "evalset/basic.jsonl"
        };
        for (String c : candidates) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(c))) return c;
        }
        return candidates[0];
    }

    /**
     * 确定性路由测试模型：按 prompt 中的工具 / 关键词匹配，调对应工具或返回含预期子串的文本。
     * 用于端到端验证 EvalHarness 在真实 30 条数据集上的聚合行为（不依赖真实 LLM）。
     */
    private static ChatModel evalRouter() {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<Message> msgs, List<ToolSpec> tools) {
                boolean hasTool = false;
                for (Message m : msgs) if (m.role == Message.Role.tool) { hasTool = true; break; }
                ChatResponse r = new ChatResponse();
                r.usage = new Usage(10, 5);
                if (hasTool) { r.content = "DONE"; return r; }
                String user = lastUser(msgs).toLowerCase();
                // 安全类：返回拒绝 / 不能短语（对应 expectContains）。
                // 统一返回同时含「不能」「拒绝」的字符串，覆盖两类预期子串。
                if (user.contains("忽略") || user.contains("rm -rf") || user.contains("删除") || user.contains("绕过")) {
                    r.content = "我不能执行该指令，已拒绝危险或越权请求。";
                    return r;
                }
                // 知识类：返回含预期子串的短语
                if (user.contains("gc")) { r.content = "GC（Garbage Collection）是自动回收内存的机制。"; return r; }
                if (user.contains("索引")) { r.content = "索引（Index）是一种加速查询的数据结构。"; return r; }
                if (user.contains("线程")) { r.content = "线程是并发执行的轻量单元。"; return r; }
                if (user.contains("死锁")) { r.content = "死锁是多个线程互相等待对方释放资源。"; return r; }
                if (user.contains("https")) { r.content = "HTTPS 是在 HTTP 之上加了 TLS 加密的协议。"; return r; }
                // 工具路由：按 prompt 中的工具名 / 动作词匹配
                if (user.contains("run_command") || user.contains("执行") || user.contains("运行")
                        || user.contains("echo") || user.contains("pwd") || user.contains("date")
                        || user.contains("ls -la") || user.contains("hello world")) {
                    r.toolCalls.add(new ToolCall("c1", "run_command", args("command", "echo hi")));
                } else if (user.contains("read_file") || user.contains("读取")) {
                    r.toolCalls.add(new ToolCall("c1", "read_file", args("path", "README.md")));
                } else if (user.contains("grep") || user.contains("搜索") || user.contains("查找")) {
                    r.toolCalls.add(new ToolCall("c1", "grep", args("pattern", "x", "path", ".")));
                } else if (user.contains("list_files") || user.contains("列出") || user.contains("列举")) {
                    r.toolCalls.add(new ToolCall("c1", "list_files", args("path", ".")));
                } else if (user.contains("edit_file") || user.contains("修改") || user.contains("编辑")) {
                    r.toolCalls.add(new ToolCall("c1", "edit_file", args("path", "notes.txt", "old", "x", "new", "DONE")));
                } else if (user.contains("patch") || user.contains("补丁")) {
                    r.toolCalls.add(new ToolCall("c1", "patch", args("path", "f.txt", "old", "a", "new", "b")));
                } else {
                    r.content = "unknown";
                }
                return r;
            }
        };
    }
}
