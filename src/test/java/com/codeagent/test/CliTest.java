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

            // ---- Phase-2 能力：默认关闭时给出明确提示，不静默失败 ----
            String memOff = cli.handle("/memory this project uses zero middleware");
            fails += check("/memory is disabled by default", memOff != null && memOff.contains("memory disabled"));
            String st3 = cli.handle("/status");
            fails += check("/status reports no features by default", st3.contains("features:   none"));

            // ---- 开启记忆后的 /memory 与 /forget ----
            Path ws2 = Files.createTempDirectory("codeagent-cli-mem");
            AgentConfig cfg2 = new AgentConfig();
            cfg2.workspace = ws2.toString();
            cfg2.useMock = true;
            cfg2.enableMemory = true;
            CodeAgentCli cli2 = new CodeAgentCli(cfg2);
            String st4 = cli2.handle("/status");
            fails += check("/status reports the memory feature", st4.contains("features:   memory"));

            String mem1 = cli2.handle("/memory 用户偏好中文回复");
            fails += check("/memory stores a fact", mem1 != null && mem1.startsWith("remembered:"));
            fails += check("/memory reports a total of 1", mem1 != null && mem1.contains("total=1"));
            String mem2 = cli2.handle("/memory 该项目刻意不使用中间件");
            fails += check("/memory reports a total of 2", mem2 != null && mem2.contains("total=2"));

            String usage = cli2.handle("/memory");
            fails += check("/memory requires an argument", "usage: /memory <text>".equals(usage));

            // 记忆要能跨实例持久化（换一个 CLI 实例读同一工作区）
            AgentConfig cfg3 = new AgentConfig();
            cfg3.workspace = ws2.toString();
            cfg3.useMock = true;
            cfg3.enableMemory = true;
            CodeAgentCli cli3 = new CodeAgentCli(cfg3);
            String st5 = cli3.handle("/status");
            fails += check("memory survives a CLI restart", st5.contains("features:   memory"));

            String id = mem1.substring("remembered: ".length(), mem1.indexOf(" (total="));
            String forgot = cli3.handle("/forget " + id);
            fails += check("/forget removes the record", forgot != null && forgot.contains("total=1"));

            // ---- --rag / --skills 的目录参数是可选的（不该吞掉后续开关）----
            AgentConfig parsed = AgentConfig.load(new String[]{
                    "--memory", "--skills", "--rag", "docs", "--workflow"});
            fails += check("--skills without a dir still enables skills", parsed.enableSkills);
            fails += check("--skills defaults to .codeagent/skills",
                    ".codeagent/skills".equals(parsed.skillsDir));
            fails += check("--rag still parses after a bare --skills", parsed.enableRag);
            fails += check("--rag keeps its explicit dir", "docs".equals(parsed.ragDir));
            fails += check("--workflow is enabled", parsed.enableWorkflow);
            AgentConfig bare = AgentConfig.load(new String[]{"--rag"});
            fails += check("bare --rag falls back to the default dir", "docs".equals(bare.ragDir));

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
