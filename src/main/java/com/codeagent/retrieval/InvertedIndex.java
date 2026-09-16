package com.codeagent.retrieval;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 倒排索引：term -> { docId -> 词频 tf }。供 BM25 打分使用。
 * 纯内存结构；持久化由上层（memory/rag）负责序列化。
 */
public class InvertedIndex {
    private final Map<String, Map<String, Integer>> index = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private int totalDocs = 0;

    /** 加入一篇文档的分词结果，更新词频与文档长度 */
    public void addDoc(String docId, java.util.List<String> tokens) {
        if (!docLengths.containsKey(docId)) totalDocs++;
        docLengths.put(docId, tokens.size());
        Map<String, Integer> seen = new HashMap<>();
        for (String t : tokens) {
            index.computeIfAbsent(t, k -> new HashMap<>());
            Map<String, Integer> postings = index.get(t);
            postings.put(docId, postings.getOrDefault(docId, 0) + 1);
            seen.put(t, seen.getOrDefault(t, 0) + 1);
        }
        // 用去重后的词数作为文档长度更利于 BM25（避免重复词膨胀）
        docLengths.put(docId, seen.size() == 0 ? 1 : seen.size());
    }

    public boolean contains(String term) {
        return index.containsKey(term);
    }

    /** 某文档中某词的频率 */
    public int tf(String docId, String term) {
        Map<String, Integer> p = index.get(term);
        return p == null ? 0 : p.getOrDefault(docId, 0);
    }

    /** 包含某词的文档数（idf 分母） */
    public int docFreq(String term) {
        Map<String, Integer> p = index.get(term);
        return p == null ? 0 : p.size();
    }

    public int docLength(String docId) {
        return docLengths.getOrDefault(docId, 0);
    }

    public double avgDocLength() {
        if (docLengths.isEmpty()) return 0;
        long sum = 0;
        for (int l : docLengths.values()) sum += l;
        return (double) sum / docLengths.size();
    }

    public int totalDocs() { return totalDocs; }

    public Set<String> docIds() { return new HashSet<>(docLengths.keySet()); }

    /** 序列化辅助：暴露内部索引（只读副本由调用方负责） */
    public Map<String, Map<String, Integer>> rawIndex() { return index; }
    public Map<String, Integer> rawDocLengths() { return docLengths; }
}
