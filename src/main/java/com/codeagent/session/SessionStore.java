package com.codeagent.session;

import com.codeagent.core.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话事件溯源（M5）：每条消息以一行 JSONL 追加到会话文件，append-only。
 * 设计对齐 Claude Code / OpenHands 的透明可恢复会话：任何时刻都能从日志重建完整上下文。
 */
public class SessionStore {

    private final Path file;

    public SessionStore(Path file) {
        this.file = file;
    }

    /** 追加一条消息（一行 JSON），返回追加后的总行号（1-based） */
    public long append(Message m) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("{\"role\":").append(json(m.role == null ? "" : m.role.name()));
            sb.append(",\"content\":").append(json(m.content == null ? "" : m.content));
            if (m.toolCallId != null) sb.append(",\"tool_call_id\":").append(json(m.toolCallId));
            if (m.name != null) sb.append(",\"name\":").append(json(m.name));
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                StringBuilder tc = new StringBuilder("[");
                for (int i = 0; i < m.toolCalls.size(); i++) {
                    if (i > 0) tc.append(",");
                    tc.append(m.toolCalls.get(i).toJson());
                }
                tc.append("]");
                sb.append(",\"tool_calls\":").append(tc);
            }
            sb.append("}\n");
            // append-only：不存在则创建，存在则追加（事件溯源不做原地修改）
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Files.readAllLines(file, StandardCharsets.UTF_8).size();
        } catch (Exception e) {
            throw new RuntimeException("session append failed: " + e.getMessage(), e);
        }
    }

    /** 从 JSONL 重建消息列表（恢复会话） */
    public List<Message> replay() {
        List<Message> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                out.add(Message.fromJsonLine(line));
            }
        } catch (Exception e) {
            throw new RuntimeException("session replay failed: " + e.getMessage(), e);
        }
        return out;
    }

    public long size() {
        if (!Files.exists(file)) return 0;
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).size();
        } catch (Exception e) {
            return 0;
        }
    }

    public Path file() { return file; }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);
            }
        }
        b.append("\"");
        return b.toString();
    }
}
