package com.codeagent.rag;

import java.nio.file.Path;
import java.util.List;

/**
 * RAG 提供方：对上层（AgentLoop / CLI）暴露「检索并组装上下文」的能力。
 * 设计对齐 Cursor 的「把相关代码/文档检索进上下文」思路：检索是确定性的（BM25），
 * 不依赖模型，可回归、可解释。
 */
public class RagProvider {
    private final CorpusIndexer indexer = new CorpusIndexer();

    /** 对语料目录建索引 */
    public int index(Path corpusDir) { return indexer.indexDirectory(corpusDir); }

    public void saveIndex(Path file) { indexer.save(file); }
    public void loadIndex(Path file) { indexer.load(file); }
    public int chunkCount() { return indexer.chunkCount(); }

    /**
     * 检索 top-k 相关片段并渲染为可注入 system/user 上下文的段落。
     * 返回空串表示无相关片段（调用方据此跳过注入）。
     */
    public String retrieve(String query, int k) {
        List<Chunk> hits = indexer.retrieve(query, k);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Retrieved context (RAG)\n");
        int i = 1;
        for (Chunk c : hits) {
            sb.append("[").append(i++).append("] ").append(c.text).append("\n\n");
        }
        return sb.toString().trim();
    }
}
