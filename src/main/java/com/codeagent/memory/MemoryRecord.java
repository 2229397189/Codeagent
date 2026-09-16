package com.codeagent.memory;

import com.codeagent.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条长期记忆记录。对应 Stage 5 记忆系统的「跨会话事实」：
 * 用户偏好 / 项目背景 / 话题知识 / 关键事件。
 * 只持久化「用户明确提供、跨会话仍有价值」的信息（见 RESUME 红线）。
 */
public class MemoryRecord {
    public enum Type { USER, PROJECT, TOPIC, PREFERENCE, EPISODIC, FACT }

    public String id;
    public Type type = Type.FACT;
    public String content;
    public String source;            // 来源（用户输入 / 工具结果 / 系统）
    public long createdAt;           // epoch millis
    public long lastAccessed;        // epoch millis
    public int accessCount = 0;
    public double importance = 0.5;  // 0..1，越高越优先保留/召回

    public MemoryRecord() {}

    public MemoryRecord(String id, String content, Type type, String source, double importance) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.source = source;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastAccessed = now;
        this.importance = importance;
    }

    public void touch() {
        lastAccessed = System.currentTimeMillis();
        accessCount++;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type.name());
        m.put("content", content);
        m.put("source", source);
        m.put("createdAt", createdAt);
        m.put("lastAccessed", lastAccessed);
        m.put("accessCount", accessCount);
        m.put("importance", importance);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static MemoryRecord fromMap(Map<String, Object> m) {
        MemoryRecord r = new MemoryRecord();
        r.id = String.valueOf(m.get("id"));
        r.content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
        r.type = Type.valueOf(String.valueOf(m.get("type")));
        r.source = m.get("source") == null ? "" : String.valueOf(m.get("source"));
        r.createdAt = asLong(m.get("createdAt"));
        r.lastAccessed = asLong(m.get("lastAccessed"));
        r.accessCount = (int) asLong(m.get("accessCount"));
        r.importance = m.get("importance") instanceof Number ? ((Number) m.get("importance")).doubleValue() : 0.5;
        return r;
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    public String toJson() { return Json.write(toMap()); }
}
