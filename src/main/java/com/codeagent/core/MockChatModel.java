package com.codeagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性 Mock 模型，用于离线测试主循环与工具协议（无需网络 / API key）。
 * 按脚本顺序返回预设响应；脚本用尽后循环返回最后一条，避免测试无限循环。
 */
public class MockChatModel implements ChatModel {
    private final List<ChatResponse> script;
    private int cursor = 0;

    public MockChatModel(List<ChatResponse> script) {
        this.script = new ArrayList<>(script);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
        if (script.isEmpty()) {
            ChatResponse r = new ChatResponse();
            r.content = "";
            return r;
        }
        ChatResponse r = script.get(Math.min(cursor, script.size() - 1));
        cursor++;
        return r;
    }
}
