package com.codeagent.eval;

import com.codeagent.context.ContextBudget;
import com.codeagent.context.DefaultContextGovernance;
import com.codeagent.context.MicroCompact;
import com.codeagent.context.ToolResultStorage;
import com.codeagent.core.*;
import com.codeagent.observability.TraceRecorder;
import com.codeagent.permission.DefaultPermissionManager;
import com.codeagent.permission.InjectionGuard;
import com.codeagent.session.Session;
import com.codeagent.tools.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线/在线评测执行器：把一条 {@link EvalTask} 喂给真实主循环（AgentLoop），
 * 复用与 CLI 完全相同的工具协议 / 权限 / 上下文治理 / 注入防护，
 * 因此评测的是"端到端行为"而非孤立函数 —— 这正是简历里"评估与可观测"的实锤。
 *
 * 评测模式默认 accept-edits=true（写操作自动批准），保证无人值守跑批；
 * 但仍受路径沙箱与危险命令拦截约束（DENY 不会被放开）。
 */
public class EvalHarness {
    private final ChatModel model;
    private final String workspace;
    private final boolean acceptEdits;

    public EvalHarness(ChatModel model, String workspace, boolean acceptEdits) {
        this.model = model;
        this.workspace = Path.of(workspace).toAbsolutePath().normalize().toString();
        this.acceptEdits = acceptEdits;
    }

    public EvalResult runTask(EvalTask task) {
        Path ws = Path.of(workspace);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(workspace));
        registry.register(new GrepTool(workspace));
        registry.register(new ListFilesTool(workspace));
        registry.register(new EditFileTool(workspace));
        registry.register(new PatchTool(workspace));
        registry.register(new RunCommandTool(workspace));

        DefaultPermissionManager perms = new DefaultPermissionManager(ws, ws.resolve(".codeagent/permissions.json"));
        perms.acceptEdits = acceptEdits;
        Session session = Session.open(ws.resolve(".codeagent/sessions"), "eval-" + safe(task.id));
        TraceRecorder trace = new TraceRecorder(ws.resolve(".codeagent/traces/trace-eval-" + safe(task.id) + ".jsonl"));
        InjectionGuard guard = new InjectionGuard(false);

        ContextBudget budget = new ContextBudget();
        budget.limit = 128_000;
        DefaultContextGovernance gov = new DefaultContextGovernance(budget,
                new MicroCompact(6, Set.of("read_file")),
                DefaultContextGovernance.extractiveSummarizer());

        AgentLoop loop = new AgentLoop();
        loop.maxSteps = task.maxSteps > 0 ? task.maxSteps : 25;
        loop.permissionManager = perms;
        loop.context = gov;
        loop.injectionGuard = guard;
        loop.storage = new ToolResultStorage(ws.resolve(".codeagent/offscreen"));
        loop.largeResultBytes = 32 * 1024;
        loop.model.chatModel = model;

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(SystemPrompt.defaultPrompt(workspace)));
        messages.add(Message.user(task.prompt));

        AgentLoop.AgentTurnResult res;
        int totalSteps = 0;
        while (true) {
            res = loop.runTurn(messages, registry);
            totalSteps += res.steps;
            if (res.status != AgentLoop.TurnStatus.AWAITING_USER || res.pendingCall == null) break;
            // 评测无人值守：自动批准写操作（仍受沙箱 / 危险命令约束）
            perms.approve(res.pendingCall);
            ToolResult applied = registry.execute(res.pendingCall, perms);
            replaceLastToolMessage(messages, applied.output == null ? "" : applied.output);
        }
        res.steps = totalSteps;

        boolean toolCalled = false;
        if (task.expectTool != null) {
            for (Message m : messages) {
                if (m.role == Message.Role.assistant && m.toolCalls != null) {
                    for (ToolCall tc : m.toolCalls) {
                        if (task.expectTool.equals(tc.name)) {
                            toolCalled = true;
                            break;
                        }
                    }
                }
                if (toolCalled) break;
            }
        }
        String finalText = lastAssistantText(messages);

        boolean baseOk = res.status == AgentLoop.TurnStatus.FINAL;
        boolean passed = baseOk;
        String reason = "reached " + res.status.name();
        if (task.expectTool != null) {
            if (!toolCalled) {
                passed = false;
                reason = "expected tool " + task.expectTool + " not called";
            } else if (passed) {
                reason = "final + tool " + task.expectTool + " called";
            }
        }
        if (task.expectContains != null) {
            boolean contains = finalText != null
                    && finalText.toLowerCase().contains(task.expectContains.toLowerCase());
            if (!contains) {
                passed = false;
                reason = "final text missing expected substring: " + task.expectContains;
            } else if (passed) {
                reason = reason + " + contains '" + task.expectContains + "'";
            }
        }
        if (!baseOk && passed) {
            passed = false;
            reason = "did not reach FINAL (" + res.status.name() + ")";
        }

        EvalResult r = new EvalResult();
        r.id = task.id;
        r.passed = passed;
        r.status = res.status.name();
        r.toolChecked = task.expectTool != null;
        r.expectedToolCalled = toolCalled;
        r.steps = totalSteps;
        r.promptTokens = loop.model.lastUsage.promptTokens;
        r.completionTokens = loop.model.lastUsage.completionTokens;
        r.finalText = finalText;
        r.reason = reason;
        return r;
    }

    public EvalReport runAll(List<EvalTask> tasks) {
        List<EvalResult> rs = new ArrayList<>();
        for (EvalTask t : tasks) rs.add(runTask(t));
        return EvalReport.aggregate(rs);
    }

    /** 从 JSONL 文件加载任务（每行一个 JSON 对象，空行与注释行忽略） */
    public static List<EvalTask> loadTasks(String path) throws Exception {
        List<EvalTask> tasks = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            String t = line.trim();
            if (!t.isEmpty() && t.startsWith("{")) tasks.add(EvalTask.fromJsonLine(t));
        }
        return tasks;
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

    private static void replaceLastToolMessage(List<Message> msgs, String output) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).role == Message.Role.tool) {
                msgs.get(i).content = output;
                return;
            }
        }
    }

    private static String safe(String id) {
        return id == null ? "x" : id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
