package com.codeagent.cli;

import com.codeagent.context.ContextBudget;
import com.codeagent.context.DefaultContextGovernance;
import com.codeagent.context.MicroCompact;
import com.codeagent.core.*;
import com.codeagent.permission.DefaultPermissionManager;
import com.codeagent.session.Session;
import com.codeagent.tools.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CLI 入口（M6）：把主循环 / 工具协议 / 上下文治理 / 权限 / 会话五层接线到一起。
 * 设计原则（对齐 Codex / Claude Code）：CLI-first、可观测（/status）、可治理（/compact）、可审批（/approve）。
 */
public class CodeAgentCli {

    private final AgentConfig cfg;
    private final ToolRegistry registry;
    private final DefaultPermissionManager perms;
    private final Session session;
    private final AgentLoop loop;
    private final DefaultContextGovernance gov;

    public CodeAgentCli(AgentConfig cfg) {
        this.cfg = cfg;
        Path ws = Path.of(cfg.workspace).toAbsolutePath().normalize();
        String workspace = ws.toString();

        this.registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace));
        registry.register(new GrepTool(workspace));
        registry.register(new ListFilesTool(workspace));
        registry.register(new EditFileTool(workspace));
        registry.register(new PatchTool(workspace));
        registry.register(new RunCommandTool(workspace));

        this.perms = new DefaultPermissionManager(ws, ws.resolve(".codeagent/permissions.json"));
        this.session = Session.open(ws.resolve(".codeagent/sessions"), "default");

        ContextBudget budget = new ContextBudget();
        budget.limit = 128_000;
        budget.warnPct = cfg.contextWarnPct;
        budget.autoPct = cfg.contextAutoPct;
        this.gov = new DefaultContextGovernance(budget,
                new MicroCompact(cfg.microCompactRounds, Set.of("read_file")),
                DefaultContextGovernance.extractiveSummarizer());

        this.loop = new AgentLoop();
        this.loop.maxSteps = cfg.maxSteps;
        this.loop.permissionManager = perms;
        this.loop.context = gov;
        this.loop.model.chatModel = buildModel();
    }

    private ChatModel buildModel() {
        if (cfg.useMock || cfg.apiKey == null || cfg.apiKey.isBlank()) {
            return new OfflineModel();
        }
        return new OpenAiCompatibleChatModel(cfg.baseUrl, cfg.apiKey, cfg.model);
    }

    /** 替换底层模型（测试注入 / 运行时切换 provider） */
    public void setModel(ChatModel m) {
        this.loop.model.chatModel = m;
    }

    /** 处理一行输入；返回 null 表示退出 */
    public String handle(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("/")) return command(s);

        List<Message> messages = new ArrayList<>(session.messages());
        int persisted = messages.size(); // 已在日志中的条数，之后的新消息才需要 append
        messages.add(Message.user(s));

        AgentLoop.AgentTurnResult res;
        try {
            res = loop.runTurn(messages, registry);
        } catch (Exception e) {
            // 模型/网络故障不应终结 REPL：单回合失败要可恢复
            return "error: " + e.getMessage();
        }
        gov.observe(loop.model.lastUsage); // 用 provider usage 更新预算

        for (int i = persisted; i < res.messages.size(); i++) session.append(res.messages.get(i));

        String content = lastAssistantText(res.messages);
        String body = content == null ? "" : content;
        if (res.status != AgentLoop.TurnStatus.FINAL) {
            return "[" + res.status.name() + "] " + body;
        }
        return body;
    }

    private String command(String s) {
        String[] parts = s.split("\\s+", 3);
        String cmd = parts[0];
        switch (cmd) {
            case "/help":
                return String.join("\n",
                        "/tools                list available tools",
                        "/status               model, workspace, session and context budget",
                        "/compact              preview auto-compaction (does not mutate the append-only log)",
                        "/approve <tool> <path> approve a write target (persisted)",
                        "/session              current session info",
                        "/exit | /quit         leave");
            case "/tools": {
                StringBuilder sb = new StringBuilder();
                for (ToolSpec t : registry.specs()) {
                    sb.append(t.name).append(" - ").append(t.description).append("\n");
                }
                return sb.toString().trim();
            }
            case "/status": {
                ContextBudget b = gov.budget();
                return String.join("\n",
                        "model:      " + cfg.model,
                        "workspace:  " + cfg.workspace,
                        "session:    " + session.name() + " (" + session.size() + " events)",
                        "context:    " + String.format("%.1f", b.ratio()) + "% (" + b.phase() + ")"
                                + " prompt=" + b.promptTokens + " completion=" + b.completionTokens
                                + " limit=" + b.limit,
                        "tools:      " + registry.specs().size(),
                        "max steps:  " + cfg.maxSteps);
            }
            case "/compact": {
                List<Message> msgs = session.messages();
                List<Message> compacted = gov.forceCompact(msgs);
                String head = compacted.isEmpty() ? "" : String.valueOf(compacted.get(0).content);
                return "compacted " + msgs.size() + " -> " + compacted.size() + "\n" + head;
            }
            case "/approve": {
                if (parts.length < 3) return "usage: /approve <tool> <path>";
                perms.approve(parts[1], parts[2]);
                return "approved: " + parts[1] + " " + parts[2];
            }
            case "/session":
                return "session: " + session.name() + " file=" + session.file()
                        + " events=" + session.size();
            case "/exit":
            case "/quit":
                return null;
            default:
                return "unknown command: " + cmd + " (try /help)";
        }
    }

    public String banner() {
        return String.join("\n",
                "CodeAgent - a minimal, observable coding agent (zero dependency, Java 17)",
                "workspace: " + cfg.workspace,
                "model:     " + cfg.model + (cfg.useMock ? " (offline mock)" : ""),
                "type /help for commands, /exit to leave");
    }

    private static String lastAssistantText(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.role == Message.Role.assistant && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                return m.content;
            }
        }
        return null;
    }

    /** 无 API key 时的离线模型：让 CLI 可离线冒烟，不静默假装成真模型 */
    static class OfflineModel implements ChatModel {
        @Override
        public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
            String lastUser = null;
            for (Message m : messages) {
                if (m.role == Message.Role.user) lastUser = m.content;
            }
            ChatResponse r = new ChatResponse();
            r.content = "[offline mode: no LLM configured] you said: " + lastUser;
            r.usage = new Usage(1, 1);
            return r;
        }
    }

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.load(args);
        CodeAgentCli cli = new CodeAgentCli(cfg);
        System.out.println(cli.banner());
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("codeagent> ");
            String line = br.readLine();
            if (line == null) break;
            String out = cli.handle(line);
            if (out == null) {
                System.out.println("bye");
                break;
            }
            if (!out.isEmpty()) System.out.println(out);
        }
    }
}
