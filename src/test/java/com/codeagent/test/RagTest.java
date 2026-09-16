package com.codeagent.test;

import com.codeagent.rag.Chunk;
import com.codeagent.rag.Chunker;
import com.codeagent.rag.Document;
import com.codeagent.rag.RagProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 基础版 RAG 测试：断言「返回正确的值」，而非仅不报错。
 * 覆盖 切块（空行分段 + 超长滑动切分）、语料索引、BM25 检索与上下文渲染。
 * 说明：本 RAG 为 lexical（BM25）实现，dense vector 检索未实现（属后续阶段）。
 */
public class RagTest {

    public static int run() {
        int fails = 0;
        System.out.println("[RagTest]");

        // ---- Chunker：按空行分段 ----
        {
            Document doc = new Document("d1",
                    "First paragraph about Java.\n\nSecond paragraph about concurrency and threads.\n\nThird short one.");
            List<Chunk> chunks = Chunker.chunk(doc, 500, 50);
            fails += check("chunker splits on blank lines", chunks.size() == 3);

            boolean allSameDoc = true;
            for (Chunk c : chunks) if (!"d1".equals(c.docId)) allSameDoc = false;
            fails += check("chunk keeps its source docId", allSameDoc);
        }

        // ---- Chunker：超长段落按 maxChars 滑动切分（保留 overlap）----
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append("sentence-").append(i).append(" ");
            Document doc = new Document("d2", sb.toString());
            List<Chunk> chunks = Chunker.chunk(doc, 60, 10);
            fails += check("chunker splits long paragraph into multiple chunks", chunks.size() > 1);
        }

        // ---- RagProvider：建索引 + 检索 + 渲染 ----
        try {
            Path dir = Files.createTempDirectory("codeagent-rag");
            Files.writeString(dir.resolve("kb.md"),
                    "# Redis\nRedis is an in-memory key-value store used for caching.\n\n"
                            + "# Kafka\nKafka is a distributed event streaming platform.\n",
                    StandardCharsets.UTF_8);

            RagProvider rag = new RagProvider();
            int indexed = rag.index(dir);
            fails += check("rag indexes corpus into chunks", indexed >= 1);
            fails += check("rag reports a positive chunk count", rag.chunkCount() >= 1);

            String ctx = rag.retrieve("redis caching", 3);
            fails += check("rag retrieve returns the relevant chunk", ctx.contains("Redis"));
            fails += check("rag renders a RAG section header",
                    ctx.contains("## Retrieved context (RAG)"));
        } catch (Exception e) {
            fails += check("rag ran without exception", false);
        }

        return fails;
    }

    static int check(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS " + name);
            return 0;
        }
        System.out.println("  FAIL " + name);
        return 1;
    }
}
