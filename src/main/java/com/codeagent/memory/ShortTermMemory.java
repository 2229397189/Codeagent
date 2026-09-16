package com.codeagent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆：单次会话内的工作黑板（scratchpad）。
 * 与上下文治理（micro/auto-compact）互补——compact 负责裁剪历史消息，
 * 短期记忆负责主动保留「当前任务的关键中间结论」，避免被裁剪后丢失。
 * 容量上限 + 重要性/时效性淘汰。
 */
public class ShortTermMemory {
    public static final class Note {
        public final String content;
        public final long createdAt = System.currentTimeMillis();
        public double importance;
        public Note(String content, double importance) {
            this.content = content;
            this.importance = importance;
        }
    }

    private final List<Note> notes = new ArrayList<>();
    private final int capacity;

    public ShortTermMemory() { this(20); }
    public ShortTermMemory(int capacity) { this.capacity = Math.max(1, capacity); }

    public void note(String content) { note(content, 0.5); }

    public void note(String content, double importance) {
        notes.add(new Note(content, importance));
        evictIfNeeded();
    }

    /** 主动提升某条记忆的重要性（被再次引用时调用） */
    public void bump(int index, double importance) {
        if (index >= 0 && index < notes.size()) {
            notes.get(index).importance = Math.max(notes.get(index).importance, importance);
        }
    }

    public List<Note> recent() { return new ArrayList<>(notes); }

    public int size() { return notes.size(); }

    private void evictIfNeeded() {
        while (notes.size() > capacity) {
            int victim = 0;
            double lowest = Double.MAX_VALUE;
            long now = System.currentTimeMillis();
            for (int i = 0; i < notes.size(); i++) {
                Note n = notes.get(i);
                double recency = 1.0 / (1.0 + (now - n.createdAt) / 60_000.0);
                double value = n.importance * recency;
                if (value < lowest) { lowest = value; victim = i; }
            }
            notes.remove(victim);
        }
    }

    public static String render(List<Note> notes) {
        if (notes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Working notes (current session)\n");
        for (Note n : notes) sb.append("- ").append(n.content).append("\n");
        return sb.toString().trim();
    }
}
