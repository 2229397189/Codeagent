package com.codeagent.rag;

import com.codeagent.retrieval.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lexical 重排器（基础版，零依赖、无向量、无 cross-encoder）。
 *
 * 在 BM25 召回的候选集之上，用「可解释的 lexical 特征」重新排序，纠正 BM25 纯词频偏置
 * （例如高频词堆砌的片段靠词频胜出，但标题命中 / 查询词全覆盖的片段其实更相关）：
 *   - 查询词覆盖度：命中的不同查询词数 / 查询词总数（核心信号，0..1）
 *   - 标题命中：chunk 首行（# 标题）含任意查询词，强信号
 *   - 精确短语命中：查询词按序相邻拼接后作为子串出现
 *   - 输入候选序作为稳定 tiebreak（保证确定性、不丢候选）
 *
 * 诚实边界：这是工业 RAG「BM25 + 向量混合召回 + cross-encoder rerank」中 rerank 环节的
 * lexical 平替。它能做确定性、可回归、可解释的粗排修正，但无语义理解能力——
 * 语义级 rerank 需 embedding + 双塔 / cross-encoder，属后续阶段（不在零依赖范围内）。
 */
public class Reranker {

    /** 在 BM25 候选集内重排，返回全新列表（不修改入参）。k 由调用方在外层截断。 */
    public List<Chunk> rerank(String query, List<Chunk> candidates) {
        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();
        List<String> q = Tokenizer.tokenize(query);
        if (q.isEmpty()) return new ArrayList<>(candidates);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new Scored(candidates.get(i), score(q, candidates.get(i).text), i));
        }
        // 分数降序；同分保留 BM25 输入序（稳定排序，确定性）
        scored.sort(Comparator.<Scored>comparingDouble(s -> s.score).reversed()
                .thenComparingInt(s -> s.inputRank));
        List<Chunk> out = new ArrayList<>();
        for (Scored s : scored) out.add(s.chunk);
        return out;
    }

    /** 对单条候选打分（越大越相关）。纯 lexical，无模型。对外暴露便于单测。 */
    public double score(List<String> queryTerms, String text) {
        if (text == null || text.isEmpty()) return 0.0;
        String low = text.toLowerCase();
        // 查询词覆盖度：命中的不同查询词数量（核心信号）
        int hit = 0;
        for (String t : queryTerms) if (low.contains(t)) hit++;
        double coverage = queryTerms.isEmpty() ? 0.0 : (double) hit / queryTerms.size();
        // 标题命中：首行是 markdown 标题（以 # 开头）且含任意查询词
        boolean titleHit = false;
        int nl = low.indexOf('\n');
        String firstLine = (nl < 0 ? low : low.substring(0, nl)).trim();
        if (firstLine.startsWith("#")) {
            for (String t : queryTerms) if (firstLine.contains(t)) { titleHit = true; break; }
        }
        // 精确短语命中：查询词按序相邻拼接后作为子串出现
        StringBuilder phrase = new StringBuilder();
        for (String t : queryTerms) phrase.append(t).append(' ');
        String phraseStr = phrase.toString().trim();
        boolean exactPhrase = !phraseStr.isEmpty() && low.contains(phraseStr);
        return coverage * 100 + (titleHit ? 50 : 0) + (exactPhrase ? 30 : 0);
    }

    private static final class Scored {
        final Chunk chunk;
        final double score;
        final int inputRank;
        Scored(Chunk c, double s, int rank) { chunk = c; score = s; inputRank = rank; }
    }
}
