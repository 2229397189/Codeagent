package com.codeagent.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 打分（零依赖实现）。用于 memory / rag 的 lexical 召回。
 * 默认 k1=1.5, b=0.75。查询词重复时按出现次数加权（查询词频）。
 */
public final class Bm25 {
    private Bm25() {}

    public static final double K1 = 1.5;
    public static final double B = 0.75;

    /** 对一篇查询计算所有文档的 BM25 分；返回 docId -> score（未排序） */
    public static Map<String, Double> score(List<String> queryTokens, InvertedIndex idx) {
        Map<String, Double> out = new HashMap<>();
        if (idx.totalDocs() == 0) return out;
        double avgdl = idx.avgDocLength();
        int N = idx.totalDocs();

        // 查询词频（同一词重复出现加权）
        Map<String, Integer> qtf = new HashMap<>();
        for (String t : queryTokens) qtf.put(t, qtf.getOrDefault(t, 0) + 1);

        for (Map.Entry<String, Integer> e : qtf.entrySet()) {
            String term = e.getKey();
            int n = idx.docFreq(term);
            if (n == 0) continue;
            double idf = Math.log(1.0 + (N - n + 0.5) / (n + 0.5));
            int qf = e.getValue();
            for (String docId : idx.docIds()) {
                int f = idx.tf(docId, term);
                if (f == 0) continue;
                double denom = f + K1 * (1 - B + B * idx.docLength(docId) / avgdl);
                double s = idf * (f * (K1 + 1)) / denom;
                // 查询词频轻微加权（避免 1 词查询时丢失信号）
                out.put(docId, out.getOrDefault(docId, 0.0) + s * (1 + 0.1 * (qf - 1)));
            }
        }
        return out;
    }

    /** 取打分最高的 k 个 docId（降序），不足 k 则全部返回 */
    public static List<String> topK(Map<String, Double> scores, int k) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(scores.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> r = new ArrayList<>();
        for (int i = 0; i < Math.min(k, list.size()); i++) r.add(list.get(i).getKey());
        return r;
    }
}
