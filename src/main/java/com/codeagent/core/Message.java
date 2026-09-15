package com.codeagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话消息。对齐 OpenAI chat 消息结构，但用强类型承载工具调用。
 * role 决定语义：system/user/assistant/tool。
 */
public class Message {
    public enum Role { system, user, assistant, tool }

    public Role role;
    /** 文本内容；tool 角色时表示工具输出文本 */
    public String content;
    /** assistant 消息可能携带的工具调用 */
    public List<ToolCall> toolCalls = new ArrayList<>();
    /** tool 角色时，指向对应的 ToolCall.id */
    public String toolCallId;
    /** tool 角色时，对应工具名 */
    public String name;

    public Message() {}

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public static Message system(String c) { return new Message(Role.system, c); }
    public static Message user(String c) { return new Message(Role.user, c); }
    public static Message assistant(String c) { return new Message(Role.assistant, c); }

    /** 工具结果消息：role=tool，绑定 toolCallId 与工具名 */
    public static Message tool(String callId, String toolName, String output) {
        Message m = new Message(Role.tool, output);
        m.toolCallId = callId;
        m.name = toolName;
        return m;
    }
}
