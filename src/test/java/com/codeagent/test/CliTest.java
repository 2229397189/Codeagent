package com.codeagent.test;

import com.codeagent.cli.CodeAgentCli;
import com.codeagent.core.AgentConfig;
import com.codeagent.core.ChatModel;
import com.codeagent.core.ChatResponse;
import com.codeagent.core.Message;
import com.codeagent.core.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI 测试（M6）：断言“返回正确的值”，而非仅不报错。
 * 覆盖：/tools 列出全部工具、/status 预算相位、离线回合文本、会话持久化、/compact、/approve、/exit。
 */
public class CliTest {

    public static int run() {
        int fails = 0;
        System.out.println("[CliTest]");

        try {
            Path ws = Files.createTempDirectory("codeagent-cli");
            AgentConfig cfg = new AgentConfig();
            cfg.workspace = ws.toString();
            cfg.useMock = true; // 离线，避免依赖网络与 API key

            CodeAgentCli cli = new CodeAgentCli(cfg);

            // /tools：列出 6 个工具
            String tools = cli.handle("/tools");
            fails += check("/tools lists exactly 6 tools", tools != null && tools.split("\n").length == 6);
            for (String t : new String[]{"read_file", "grep", "list_files", "edit_file", "patch", "run_command"}) {
                fails += check("/tools includes " + t, tools != null && tools.contains(t));
            }

            // /status：初始预算相位为 OK
            String st = cli.handle("/status");
            fails += check("/status shows workspace", st.contains("workspace:"));
            fails += check("/status shows context phase OK at start", st.contains("(OK)"));

            // 离线回合：返回模型文本，且把 user+assistant 写进会话
            String turn = cli.handle("hello");
            fails += check("offline turn echoes user input", turn != null && turn.contains("you said: hello"));

            String sess = cli.handle("/session");
            fails += check("first turn persists system+user+assistant (events=3)", sess.contains("events=3"));

            // /status 暴露安全与追踪状态
            String st2 = cli.handle("/status");
            fails += check("/status shows injection guard enabled", st2.contains("injection-guard=on"));
            fails += check("/status shows trace events", st2.contains("trace:") && st2.contains("events"));

            // /trace：审计追踪已记录本回合
            String tr = cli.handle("/trace");
            fails += check("/trace reports file and 1 event",
                    tr != null && tr.startsWith("trace:") && tr.contains("events=1"));

            // /compact：给出压缩前后规模（不改动 append-only 日志）
            String cp = cli.handle("/compact");
            fails += check("/compact reports before -> after", cp != null && cp.startsWith("compacted 3 -> "));
            fails += check("log untouched by /compact", cli.handle("/session").contains("events=3"));

            // /approve：记录审批
            String ap = cli.handle("/approve edit_file a.txt");
            fails += check("/approve confirms target", "approved: edit_file a.txt".equals(ap));

            // 模型故障隔离：单回合失败不应终结 REPL
            cli.setModel(new ChatModel() {
                @Override
                public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
                    throw new RuntimeException("boom");
                }
            });
            String err = cli.handle("trigger failure");
            fails += check("model failure is contained (no crash)",
                    err != null && err.startsWith("error:") && err.contains("boom"));
            fails += check("REPL still responsive after model failure", cli.handle("/tools") != null);

            // 未知命令与退出
            String unk = cli.handle("/nope");
            fails += check("unknown command handled", unk != null && unk.contains("unknown command"));
            fails += check("/exit returns null (quit signal)", cli.handle("/exit") == null);

        } catch (Exception ex) {
            fails++;
            System.out.println("  FAIL exception: " + ex);
            ex.printStackTrace();
        }
        return fails;
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
