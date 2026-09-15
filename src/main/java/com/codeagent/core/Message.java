package com.codeagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 从会话日志（JSONL）的一行重建消息，用于 resume */
    @SuppressWarnings("unchecked")
    public static Message fromJsonLine(String line) {
        Object o = Json.parse(line);
        Map<String, Object> m = (Map<String, Object>) o;
        Role r = Role.valueOf(String.valueOf(m.get("role")));
        Object c = m.get("content");
        Message msg = new Message(r, c == null ? null : String.valueOf(c));
        if (m.get("tool_call_id") != null) msg.toolCallId = String.valueOf(m.get("tool_call_id"));
        if (m.get("name") != null) msg.name = String.valueOf(m.get("name"));
        if (m.get("tool_calls") instanceof List) {
            for (Object t : (List<Object>) m.get("tool_calls")) {
                Map<String, Object> tm = (Map<String, Object>) t;
                ToolCall tc = new ToolCall();
                tc.id = tm.get("id") == null ? null : String.valueOf(tm.get("id"));
                tc.name = tm.get("name") == null ? null : String.valueOf(tm.get("name"));
                if (tm.get("arguments") instanceof Map) tc.arguments = (Map<String, Object>) tm.get("arguments");
                msg.toolCalls.add(tc);
            }
        }
        return msg;
    }
}
