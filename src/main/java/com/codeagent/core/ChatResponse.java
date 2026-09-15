package com.codeagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次模型调用的响应。
 */
public class ChatResponse {
    /** assistant 文本（若有工具调用，文本可能为 null） */
    public String content;
    /** 模型请求调用的工具（空 = 终态） */
    public List<ToolCall> toolCalls = new ArrayList<>();
    /** token 用量，用于上下文预算记账 */
    public Usage usage = new Usage();

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
