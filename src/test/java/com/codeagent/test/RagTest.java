package com.codeagent.test;

import com.codeagent.rag.Chunk;
import com.codeagent.rag.Chunker;
import com.codeagent.rag.CorpusIndexer;
import com.codeagent.rag.Document;
import com.codeagent.rag.RagProvider;
import com.codeagent.rag.Reranker;

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

        // ---- P1-1：Lexical 重排（默认关闭 → 零回归；开启后纠正 BM25 词频偏置）----
        try {
            Path dir = Files.createTempDirectory("codeagent-rag-rerank");
            // fx：高频堆砌、无标题（BM25 纯词频会胜出）
            Files.writeString(dir.resolve("fx.md"), "redis cache redis cache redis cache", StandardCharsets.UTF_8);
            // fy：标题命中 + 查询词全覆盖（应被 rerank 提升）
            Files.writeString(dir.resolve("fy.md"), "# Redis\nRedis is a cache store.", StandardCharsets.UTF_8);
            // fz：全覆盖但无标题（应排在 fy 之后）
            Files.writeString(dir.resolve("fz.md"), "Redis and cache are useful concepts.", StandardCharsets.UTF_8);

            CorpusIndexer idx = new CorpusIndexer();
            idx.indexDirectory(dir);

            // D1：关闭重排时 top1 是词频堆砌的 fx；开启后 top1 变成标题命中的 fy
            List<Chunk> bm25 = idx.retrieve("redis cache", 3);
            fails += check("rerank off: BM25 top1 is the frequency-stuffed chunk",
                    !bm25.isEmpty() && bm25.get(0).text.startsWith("redis cache"));
            idx.setReranker(new Reranker());
            List<Chunk> re = idx.retrieve("redis cache", 3);
            fails += check("rerank on: top1 is the title-hit chunk",
                    !re.isEmpty() && re.get(0).text.startsWith("# Redis"));

            // D2：重排确定性（同输入同序）
            List<Chunk> reAgain = idx.retrieve("redis cache", 3);
            boolean det = re.size() == reAgain.size();
            for (int i = 0; det && i < re.size(); i++) det = re.get(i).text.equals(reAgain.get(i).text);
            fails += check("rerank is deterministic", det);

            // D3：标题命中优先于无标题的全覆盖片段（fy 排在 fz 前）
            int iy = -1, iz = -1;
            for (int i = 0; i < re.size(); i++) {
                if (iy < 0 && re.get(i).text.startsWith("# Redis")) iy = i;
                if (iz < 0 && re.get(i).text.contains("Redis and cache")) iz = i;
            }
            fails += check("rerank ranks title-hit above title-less full-coverage", iy >= 0 && iz >= 0 && iy < iz);

            // D4：关闭重排恢复纯 BM25 顺序（零回归）
            idx.setReranker(null);
            List<Chunk> restored = idx.retrieve("redis cache", 3);
            fails += check("rerank off restores BM25 order (top1 back to frequency chunk)",
                    !restored.isEmpty() && restored.get(0).text.startsWith("redis cache"));

            // D5：重排不丢候选（k 足够大时集合与 BM25 完全一致）
            idx.setReranker(new Reranker());
            List<Chunk> reAll = idx.retrieve("redis cache", 10);
            idx.setReranker(null);
            List<Chunk> bmAll = idx.retrieve("redis cache", 10);
            fails += check("rerank keeps all candidates (none dropped)", reAll.size() == 3);
            fails += check("rerank candidate set == BM25 candidate set",
                    idsSet(reAll).equals(idsSet(bmAll)));

            // D6：RagProvider 开关端到端影响渲染顺序
            RagProvider rag = new RagProvider();
            rag.index(dir);
            String before = rag.retrieve("redis cache", 3);
            fails += check("provider rerank off: first shown is frequency chunk",
                    before.indexOf("redis cache redis cache") >= 0
                            && before.indexOf("redis cache redis cache") < before.indexOf("# Redis"));
            rag.enableRerank(true);
            String after = rag.retrieve("redis cache", 3);
            fails += check("provider rerank on: first shown is title chunk",
                    after.indexOf("# Redis") >= 0
                            && after.indexOf("# Redis") < after.indexOf("redis cache redis cache"));
        } catch (Exception e) {
            fails += check("rerank ran without exception: " + e.getMessage(), false);
        }

        return fails;
    }

    private static java.util.Set<String> idsSet(List<Chunk> chunks) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Chunk c : chunks) s.add(c.id);
        return s;
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
