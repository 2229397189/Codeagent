package com.codeagent.memory;

import com.codeagent.retrieval.Bm25;
import com.codeagent.retrieval.InvertedIndex;
import com.codeagent.retrieval.Tokenizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆：跨会话持久化的事实库。
 * 召回采用 lexical BM25 + 重要性 + 时效性 综合打分（无外部向量库依赖）。
 * 只存「用户明确提供、跨会话仍有价值」的信息，不做模型推断的脏记忆。
 */
public class LongTermMemory {
    private final MemoryStore store;
    private final InvertedIndex index = new InvertedIndex();
    private boolean dirty = true;

    public LongTermMemory(Path file) {
        this.store = new MemoryStore(file);
    }

    public String remember(String content, MemoryRecord.Type type, String source) {
        return remember(content, type, source, 0.5);
    }

    public String remember(String content, MemoryRecord.Type type, String source, double importance) {
        String id = "mem-" + UUID.randomUUID().toString().substring(0, 8);
        MemoryRecord r = new MemoryRecord(id, content, type, source, importance);
        store.add(r);
        store.save();
        dirty = true;
        return id;
    }

    public MemoryRecord get(String id) { return store.get(id); }

    public boolean forget(String id) {
        boolean removed = store.remove(id);
        if (removed) { store.save(); dirty = true; }
        return removed;
    }

    public int size() { return store.size(); }

    private void rebuildIfNeeded() {
        if (!dirty) return;
        index.rawIndex().clear();
        index.rawDocLengths().clear();
        for (MemoryRecord r : store.all()) {
            index.addDoc(r.id, Tokenizer.tokenize(r.content));
        }
        dirty = false;
    }

    /**
     * 召回与 query 最相关的 top-k 条记忆。
     * 综合分 = BM25 * (0.5 + importance) * (1 + 0.3 * recencyBoost)
     * recencyBoost = 1/(1 + 距上次访问小时数/24)，最近访问的记忆轻微优先。
     */
    public List<MemoryRecord> recall(String query, int k) {
        rebuildIfNeeded();
        List<String> q = Tokenizer.tokenize(query);
        if (q.isEmpty()) return new ArrayList<>();
        Map<String, Double> scores = Bm25.score(q, index);
        List<Scored> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            MemoryRecord r = store.get(e.getKey());
            if (r == null) continue;
            r.touch();
            double recency = 1.0 / (1.0 + (System.currentTimeMillis() - r.lastAccessed) / 3_600_000.0 / 24.0);
            double finalScore = e.getValue() * (0.5 + r.importance) * (1 + 0.3 * recency);
            list.add(new Scored(r, finalScore));
        }
        list.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        List<MemoryRecord> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, list.size()); i++) out.add(list.get(i).record);
        if (!list.isEmpty()) store.save(); // 持久化 accessCount/lastAccessed
        return out;
    }

    /** 把召回结果渲染为可注入 system prompt 的段落 */
    public static String render(List<MemoryRecord> records) {
        if (records.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Relevant long-term memory (recalled)\n");
        for (MemoryRecord r : records) {
            sb.append("- [").append(r.type.name().toLowerCase()).append("] ").append(r.content).append("\n");
        }
        return sb.toString().trim();
    }

    private static final class Scored {
        final MemoryRecord record;
        final double score;
        Scored(MemoryRecord r, double s) { record = r; score = s; }
    }
}
