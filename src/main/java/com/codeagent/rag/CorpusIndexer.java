package com.codeagent.rag;

import com.codeagent.core.Json;
import com.codeagent.retrieval.Bm25;
import com.codeagent.retrieval.InvertedIndex;
import com.codeagent.retrieval.Tokenizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语料索引器：把目录下的 .md/.txt 切成 chunk 并建立倒排索引（lexical，无向量库依赖）。
 * 持久化只存 chunk 列表；索引在加载时由 chunk 重新构建，避免两份状态不一致。
 *
 * 说明：这是「基础版」RAG（BM25 lexical 召回）。要上 dense vector 召回需外部 embedding
 * 服务与向量库——属 Phase 3，不在此项目的零依赖范围内，但检索接口与上层接入方式已就绪。
 */
public class CorpusIndexer {
    private final List<Chunk> chunks = new ArrayList<>();
    private final InvertedIndex index = new InvertedIndex();
    private boolean indexed = false;

    public int chunkCount() { return chunks.size(); }

    /** 对目录下所有 .md/.txt 建索引；返回索引的 chunk 数 */
    public int indexDirectory(Path dir) {
        chunks.clear();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.walk(dir)) {
                for (Path p : stream
                        .filter(Files::isRegularFile)
                        .filter(f -> {
                            String n = f.toString().toLowerCase();
                            return n.endsWith(".md") || n.endsWith(".txt");
                        }).toList()) {
                    try {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        Document doc = new Document(p.toString(), text);
                        for (Chunk c : Chunker.chunk(doc, 500, 50)) chunks.add(c);
                    } catch (IOException ignore) { /* 跳过不可读文件 */ }
                }
            } catch (IOException ignore) { /* 目录遍历失败不致命 */ }
        }
        buildIndex();
        return chunks.size();
    }

    public void buildIndex() {
        index.rawIndex().clear();
        index.rawDocLengths().clear();
        for (Chunk c : chunks) index.addDoc(c.id, Tokenizer.tokenize(c.text));
        indexed = true;
    }

    public List<Chunk> retrieve(String query, int k) {
        if (!indexed) buildIndex();
        List<String> q = Tokenizer.tokenize(query);
        if (q.isEmpty()) return new ArrayList<>();
        Map<String, Double> scores = Bm25.score(q, index);
        List<String> top = Bm25.topK(scores, k);
        List<Chunk> out = new ArrayList<>();
        for (String id : top) {
            for (Chunk c : chunks) if (c.id.equals(id)) { out.add(c); break; }
        }
        return out;
    }

    /** 持久化（仅 chunk 列表；索引重建于 load） */
    public void save(Path file) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> arr = new ArrayList<>();
        for (Chunk c : chunks) arr.add(c.toMap());
        root.put("chunks", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("rag index save failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public void load(Path file) {
        chunks.clear();
        if (!Files.exists(file)) return;
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                Object cs = ((Map<String, Object>) parsed).get("chunks");
                if (cs instanceof List) {
                    for (Object o : (List<Object>) cs) {
                        if (o instanceof Map) chunks.add(Chunk.fromMap((Map<String, Object>) o));
                    }
                }
            }
        } catch (IOException | RuntimeException ignore) { /* 损坏则从头建 */ }
        buildIndex();
    }
}
