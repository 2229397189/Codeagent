package com.codeagent.core;

import com.codeagent.context.ContextGovernance;
import com.codeagent.context.ToolResultStorage;
import com.codeagent.permission.InjectionGuard;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.ToolRegistry;

import java.util.List;

/**
 * 主循环（model -> tool -> model，直到模型不再调工具）。
 * 对齐 learn-claude-code s01 + MiniCode agent-loop：
 *  - 循环不变；progress 与 final 分离（不以自然语言判断结束）
 *  - 工具先占位 tool_result 再回填，保证对话结构完整
 *  - 终止条件用显式枚举（FINAL / FAILED / AWAITING_USER / CONTROLLED_STOP / MAX_STEPS）
 *
 * context / permission 为可插拔依赖（M3 / M4 实现），此处先以接口解耦，未注入时退化为直连。
 */
public class AgentLoop {

    public enum TurnStatus { FINAL, FAILED, AWAITING_USER, CONTROLLED_STOP, ABORTED, MAX_STEPS }

    public static class AgentTurnResult {
        public TurnStatus status;
        public List<Message> messages;
        /** 本回合实际执行的步数（用于审计与成本归因） */
        public int steps;

        public AgentTurnResult(TurnStatus s, List<Message> m) {
            this.status = s;
            this.messages = m;
        }
    }

    public ChatModelAdapter model = new ChatModelAdapter();
    public PermissionManager permissionManager = PermissionManager.ALLOW_ALL;
    public ContextGovernance context; // M3 注入
    public int maxSteps = 25;

    /** 可选：注入防护，对进入上下文的工具输出做净化 */
    public InjectionGuard injectionGuard;
    /** 可选：大结果离屏存储，配合 largeResultBytes 使用 */
    public ToolResultStorage storage;
    /** 工具结果超过该字节数则离屏（0 = 关闭） */
    public int largeResultBytes = 0;

    public AgentTurnResult runTurn(List<Message> messages, ToolRegistry tools) {
        int step = 0;
        for (step = 0; step < maxSteps; step++) {
            List<Message> modelMessages = (context != null) ? context.apply(messages) : messages;
            ChatResponse resp = model.chat(modelMessages, tools.specs());

            Message assistant = Message.assistant(resp.content != null ? resp.content : "");
            if (resp.hasToolCalls()) assistant.toolCalls = resp.toolCalls;
            messages.add(assistant);

            if (!resp.hasToolCalls()) {
                return result(TurnStatus.FINAL, messages, step + 1);
            }
            for (ToolCall call : resp.toolCalls) {
                ToolResult r = tools.execute(call, permissionManager);
                // 进入上下文前：先做注入净化，再按阈值离屏（顺序不能反，否则大结果的警示语会丢失）
                String out = r.output == null ? "" : r.output;
                if (injectionGuard != null) out = injectionGuard.sanitize(out);
                if (storage != null && largeResultBytes > 0 && out.length() > largeResultBytes) {
                    out = storage.store(out);
                }
                messages.add(Message.tool(call.id, call.name, out));
                if (r.fatal) return result(TurnStatus.FAILED, messages, step + 1);
                if (r.awaitUser) return result(TurnStatus.AWAITING_USER, messages, step + 1);
                if (r.stop) return result(TurnStatus.CONTROLLED_STOP, messages, step + 1);
            }
        }
        return result(TurnStatus.MAX_STEPS, messages, maxSteps);
    }

    private static AgentTurnResult result(TurnStatus s, List<Message> m, int steps) {
        AgentTurnResult r = new AgentTurnResult(s, m);
        r.steps = steps;
        return r;
    }
}
