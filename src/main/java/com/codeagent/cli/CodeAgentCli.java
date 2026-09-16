package com.codeagent.cli;

import com.codeagent.context.ContextBudget;
import com.codeagent.context.DefaultContextGovernance;
import com.codeagent.context.MicroCompact;
import com.codeagent.context.ToolResultStorage;
import com.codeagent.core.*;
import com.codeagent.mcp.McpClient;
import com.codeagent.mcp.McpLauncher;
import com.codeagent.mcp.McpTool;
import com.codeagent.memory.LongTermMemory;
import com.codeagent.memory.MemoryRecord;
import com.codeagent.memory.ShortTermMemory;
import com.codeagent.observability.TraceRecorder;
import com.codeagent.permission.DefaultPermissionManager;
import com.codeagent.permission.InjectionGuard;
import com.codeagent.rag.RagProvider;
import com.codeagent.session.Session;
import com.codeagent.skills.Skill;
import com.codeagent.skills.SkillRegistry;
import com.codeagent.tools.*;
import com.codeagent.workflow.Coordinator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TraceRecorder trace;
    private final InjectionGuard guard;

    /** REPL 与审批提示共用的标准输入读取器 */
    private final BufferedReader console;
    /** 审批策略（review-before-write）：默认从终端读 y/N；测试可注入确定性策略 */
    public ApprovePolicy approvePolicy;

    // ---- Phase-2 可选能力（默认全关，由 --memory/--rag/--skills/--mcp/--workflow 启用）----
    private LongTermMemory longTerm;
    private ShortTermMemory shortTerm;
    private RagProvider rag;
    private SkillRegistry skills;
    private final List<McpClient> mcpClients = new ArrayList<>();
    /** 当前回合的用户输入，供记忆/RAG/技能做本回合的上下文召回 */
    private String lastUserQuery = "";

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
        this.perms.acceptEdits = cfg.acceptEdits;
        this.session = Session.open(ws.resolve(".codeagent/sessions"), "default");
        this.trace = new TraceRecorder(ws.resolve(".codeagent/traces/trace.jsonl"));
        this.guard = new InjectionGuard(false);
        this.console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.approvePolicy = new ConsoleApprovePolicy(console);

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
        this.loop.injectionGuard = guard;
        this.loop.storage = new ToolResultStorage(ws.resolve(".codeagent/offscreen"));
        this.loop.largeResultBytes = cfg.largeResultKb * 1024;
        this.loop.model.chatModel = buildModel();

        initOptionalFeatures();
    }

    /**
     * 按需装配 Phase-2 能力。全部走「开关 + 惰性初始化 + 失败不致命」：
     * 任一能力初始化失败（如 MCP server 起不来）只告警，不影响 Agent 正常运行。
     */
    private void initOptionalFeatures() {
        if (cfg.enableMemory) {
            this.longTerm = new LongTermMemory(Path.of(cfg.workspace).resolve(".codeagent/memory.jsonl"));
            this.shortTerm = new ShortTermMemory();
        }
        if (cfg.enableRag && cfg.ragDir != null) {
            this.rag = new RagProvider();
            try {
                this.rag.index(Path.of(cfg.ragDir));
            } catch (Exception e) {
                System.err.println("[rag] indexing failed: " + e.getMessage());
            }
        }
        if (cfg.enableSkills) {
            this.skills = new SkillRegistry();
            if (cfg.skillsDir != null) this.skills.loadFromDir(Path.of(cfg.skillsDir));
        }
        for (String cmd : cfg.mcpCommands) {
            try {
                McpClient mc = McpLauncher.launch(splitCommand(cmd), 5_000);
                mcpClients.add(mc);
                for (Map<String, Object> td : mc.toolDefs()) {
                    Object schema = td.get("inputSchema");
                    registry.register(new McpTool(mc,
                            td.get("name") == null ? "mcp" : String.valueOf(td.get("name")),
                            td.get("description") == null ? "" : String.valueOf(td.get("description")),
                            schema instanceof Map ? (Map<String, Object>) schema : new LinkedHashMap<>()));
                }
            } catch (Exception e) {
                System.err.println("[mcp] failed to launch: " + cmd + " (" + e.getMessage() + ")");
            }
        }
    }

    private static List<String> splitCommand(String cmd) {
        List<String> parts = new ArrayList<>();
        for (String p : cmd.trim().split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        return parts;
    }

    /**
     * 组装 system prompt：基础规则 + 本回合动态召回的上下文（长期记忆 / 短期黑板 / RAG / 激活技能）。
     * 召回是确定性的（BM25 / 触发词），不依赖模型，可回归、可解释。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(SystemPrompt.defaultPrompt(cfg.workspace));
        if (cfg.enableMemory && longTerm != null) {
            List<MemoryRecord> recalled = longTerm.recall(lastUserQuery, 5);
            if (!recalled.isEmpty()) sb.append("\n\n").append(LongTermMemory.render(recalled));
        }
        if (cfg.enableMemory && shortTerm != null && shortTerm.size() > 0) {
            sb.append("\n\n").append(ShortTermMemory.render(shortTerm.recent()));
        }
        if (cfg.enableRag && rag != null) {
            String ctx = rag.retrieve(lastUserQuery, 4);
            if (!ctx.isEmpty()) sb.append("\n\n").append(ctx);
        }
        if (cfg.enableSkills && skills != null) {
            Skill active = skills.route(lastUserQuery);
            sb.append("\n\n").append(skills.renderForPrompt(active));
        }
        return sb.toString();
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

        // 多 Agent 工作流模式：Plan-and-Execute（planner -> executor(s) -> synthesizer）
        if (cfg.enableWorkflow) {
            try {
                Coordinator coordinator = new Coordinator(loop.model.chatModel, registry, perms);
                String result = coordinator.execute(s);
                trace.turn("WORKFLOW", 0, gov.budget().promptTokens, gov.budget().completionTokens, result);
                return result;
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        lastUserQuery = s; // 供记忆 / RAG / 技能做本回合的确定性召回
        List<Message> messages = new ArrayList<>(session.messages());
        // 首个回合注入 system prompt 并落盘，保证 replay 出来的就是模型当初真正看到的上下文
        if (messages.isEmpty()) {
            Message sys = Message.system(buildSystemPrompt());
            session.append(sys);
            messages.add(sys);
        } else {
            // 后续回合刷新动态上下文（记忆 / RAG / 技能随输入变化）；system 恒在 index 0，不会重复落盘
            messages.set(0, Message.system(buildSystemPrompt()));
        }
        int persisted = messages.size(); // 已在日志中的条数，之后的新消息才需要 append
        messages.add(Message.user(s));

        AgentLoop.AgentTurnResult res;
        try {
            res = runUntilDone(messages, registry); // 含 review-before-write 的审批循环
        } catch (Exception e) {
            // 模型/网络故障不应终结 REPL：单回合失败要可恢复
            trace.turn("ERROR", 0, 0, 0, e.getMessage());
            return "error: " + e.getMessage();
        }
        gov.observe(loop.model.lastUsage); // 用 provider usage 更新预算

        for (int i = persisted; i < res.messages.size(); i++) session.append(res.messages.get(i));

        String content = lastAssistantText(res.messages);
        String body = content == null ? "" : content;
        // 审计：记录状态/步数/token 用量（成本归因与排障的事实来源）
        trace.turn(res.status.name(), res.steps,
                gov.budget().promptTokens, gov.budget().completionTokens, body);

        if (res.status != AgentLoop.TurnStatus.FINAL) {
            return "[" + res.status.name() + "] " + body;
        }
        return body;
    }

    /**
     * 驱动主循环直到终态；中途遇到 review-before-write 暂停（AWAITING_USER）时，
     * 先把 diff 展示给人，等人批准/拒绝后再继续：批准则落盘并续跑，拒绝则把结果反馈给模型。
     */
    private AgentLoop.AgentTurnResult runUntilDone(List<Message> messages, ToolRegistry registry) {
        AgentLoop.AgentTurnResult res;
        int totalSteps = 0;
        int reviews = 0;
        while (true) {
            res = loop.runTurn(messages, registry);
            totalSteps += res.steps;
            if (res.status != AgentLoop.TurnStatus.AWAITING_USER || res.pendingCall == null) break;

            // review-before-write：把待审批的 diff 展示给人，等批准/拒绝
            if (++reviews > cfg.maxSteps) {
                replaceLastToolMessage(messages, "[too many pending approvals; stopped for safety]");
                return new AgentLoop.AgentTurnResult(AgentLoop.TurnStatus.CONTROLLED_STOP, messages);
            }
            ToolCall pending = res.pendingCall;
            String diff = lastToolOutput(messages);
            boolean ok = approvePolicy.approve(pending.name, diff == null ? "" : diff);
            if (ok) {
                perms.approve(pending); // 持久化，后续同目标写不再询问
                ToolResult applied = registry.execute(pending, perms); // 现在 ALLOW -> 真正落盘
                replaceLastToolMessage(messages, applied.output == null ? "" : applied.output);
                trace.event("approve", pending.name + " " + pending.argStr("path"));
            } else {
                replaceLastToolMessage(messages, "[rejected by user]");
                trace.event("reject", pending.name + " " + pending.argStr("path"));
            }
        }
        res.steps = totalSteps; // 累计步数，供审计与成本归因
        return res;
    }

    /** 取最近一条 tool 消息的输出（即待审批的 diff 预览） */
    private static String lastToolOutput(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role == Message.Role.tool) return msgs.get(i).content;
        }
        return null;
    }

    /** 用审批/拒绝后的真实结果替换最近一条 tool 消息（替换掉占位用的 awaitUser 预览） */
    private static void replaceLastToolMessage(List<Message> msgs, String output) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role == Message.Role.tool) {
                msgs.get(i).content = output;
                return;
            }
        }
    }

    /** /status 里的能力开关摘要：让人一眼看清当前启用了哪些 Phase-2 能力 */
    private String featuresSummary() {
        List<String> on = new ArrayList<>();
        if (cfg.enableMemory) on.add("memory");
        if (cfg.enableRag) on.add("rag");
        if (cfg.enableSkills) on.add("skills");
        if (!cfg.mcpCommands.isEmpty()) on.add("mcp(" + mcpClients.size() + ")");
        if (cfg.enableWorkflow) on.add("workflow");
        if (on.isEmpty()) return "none (enable with --memory/--rag/--skills/--mcp/--workflow)";
        return String.join(", ", on);
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
                        "/memory <text>       记住一条长期事实（需 --memory）",
                        "/forget <id>         删除一条长期记忆",
                        "/session              current session info",
                        "flags: --memory --rag [dir] --skills [dir] --workflow --mcp <cmd>",
                        "/trace                audit trace file and event count",
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
                        "max steps:  " + cfg.maxSteps,
                        "trace:      " + trace.size() + " events",
                        "features:   " + featuresSummary(),
                        "security:   injection-guard=on accept-edits=" + perms.acceptEdits);
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
            case "/memory": {
                if (longTerm == null) return "memory disabled (start with --memory)";
                if (parts.length < 2) return "usage: /memory <text>";
                String id = longTerm.remember(parts[1], MemoryRecord.Type.USER, "user", 0.8);
                if (shortTerm != null) shortTerm.note(parts[1], 0.8);
                return "remembered: " + id + " (total=" + longTerm.size() + ")";
            }
            case "/forget": {
                if (longTerm == null) return "memory disabled (start with --memory)";
                if (parts.length < 2) return "usage: /forget <id>";
                longTerm.forget(parts[1]);
                return "forgot: " + parts[1] + " (total=" + longTerm.size() + ")";
            }
            case "/trace":
                return "trace: " + trace.file() + " events=" + trace.size();
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

    /** 审批策略：review-before-write 时由它决定要不要批准落盘 */
    public interface ApprovePolicy {
        /** 返回 true 批准并落盘，false 拒绝 */
        boolean approve(String toolName, String diff);
    }

    /** 默认实现：把 diff 打到终端，从标准输入读 y/N */
    public static class ConsoleApprovePolicy implements ApprovePolicy {
        private final BufferedReader in;

        public ConsoleApprovePolicy(BufferedReader in) {
            this.in = in;
        }

        @Override
        public boolean approve(String toolName, String diff) {
            System.out.println("=== review-before-write: " + toolName + " ===");
            System.out.println(diff);
            System.out.print("Approve this change? [y/N] ");
            System.out.flush();
            try {
                String line = in.readLine();
                return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
            } catch (IOException e) {
                return false;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.load(args);
        CodeAgentCli cli = new CodeAgentCli(cfg);
        System.out.println(cli.banner());
        while (true) {
            System.out.print("codeagent> ");
            String line = cli.console.readLine();
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
