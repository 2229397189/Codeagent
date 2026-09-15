package com.codeagent.core;

import java.util.List;

/**
 * 主循环与具体模型的适配层。
 * 职责：调用底层 ChatModel，记录最近一次 usage 供上下文预算记账（对齐 Codex 的 provider usage 真相来源）。
 */
public class ChatModelAdapter {
    public ChatModel chatModel;
    public Usage lastUsage = new Usage();

    public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
        if (chatModel == null) throw new IllegalStateException("chatModel not set");
        ChatResponse r = chatModel.chat(messages, tools);
        if (r.usage != null) lastUsage = r.usage;
        return r;
    }
}
