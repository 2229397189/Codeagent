package com.codeagent.memory;

import com.codeagent.core.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆持久化：JSONL 追加/整体重写。每条记录一行 JSON。
 * 文件不存在时自动创建；corrupt 行跳过不致命。
 */
public class MemoryStore {
    private final Path file;
    private final List<MemoryRecord> records = new ArrayList<>();

    public MemoryStore(Path file) {
        this.file = file;
        load();
    }

    public void load() {
        records.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || !t.startsWith("{")) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) Json.parse(t);
                    records.add(MemoryRecord.fromMap(m));
                } catch (RuntimeException ignore) {
                    // 跳过损坏行，保证其余记录可用
                }
            }
        } catch (IOException e) {
            // 加载失败不致命：从空记忆开始
        }
    }

    public void add(MemoryRecord r) { records.add(r); }

    public List<MemoryRecord> all() { return new ArrayList<>(records); }

    public MemoryRecord get(String id) {
        for (MemoryRecord r : records) if (r.id.equals(id)) return r;
        return null;
    }

    public boolean remove(String id) {
        return records.removeIf(r -> r.id.equals(id));
    }

    public int size() { return records.size(); }

    /** 整体重写落盘（记忆规模小，整体写比逐行追加更易保持一致性） */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (MemoryRecord r : records) sb.append(r.toJson()).append("\n");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("memory save failed: " + e.getMessage(), e);
        }
    }
}
