package com.codeagent.core;

import com.codeagent.context.ContextGovernance;
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

        public AgentTurnResult(TurnStatus s, List<Message> m) {
            this.status = s;
            this.messages = m;
        }
    }

    public ChatModelAdapter model = new ChatModelAdapter();
    public PermissionManager permissionManager = PermissionManager.ALLOW_ALL;
    public ContextGovernance context; // M3 注入
    public int maxSteps = 25;

    public AgentTurnResult runTurn(List<Message> messages, ToolRegistry tools) {
        for (int step = 0; step < maxSteps; step++) {
            List<Message> modelMessages = (context != null) ? context.apply(messages) : messages;
            ChatResponse resp = model.chat(modelMessages, tools.specs());

            Message assistant = Message.assistant(resp.content != null ? resp.content : "");
            if (resp.hasToolCalls()) assistant.toolCalls = resp.toolCalls;
            messages.add(assistant);

            if (!resp.hasToolCalls()) {
                return new AgentTurnResult(TurnStatus.FINAL, messages);
            }
            for (ToolCall call : resp.toolCalls) {
                ToolResult r = tools.execute(call, permissionManager);
                messages.add(Message.tool(call.id, call.name, r.output));
                if (r.fatal) return new AgentTurnResult(TurnStatus.FAILED, messages);
                if (r.awaitUser) return new AgentTurnResult(TurnStatus.AWAITING_USER, messages);
                if (r.stop) return new AgentTurnResult(TurnStatus.CONTROLLED_STOP, messages);
            }
        }
        return new AgentTurnResult(TurnStatus.MAX_STEPS, messages);
    }
}
