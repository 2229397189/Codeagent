package com.codeagent.test;

import com.codeagent.retrieval.Bm25;
import com.codeagent.retrieval.InvertedIndex;
import com.codeagent.retrieval.Tokenizer;

import java.util.List;
import java.util.Map;

/**
 * 检索基础件测试：断言「返回正确的值」，而非仅不报错。
 * 覆盖 分词（中日英混合）/ 倒排索引（tf·df·docLength）/ BM25（排序与查询词频加权）。
 */
public class RetrievalTest {

    public static int run() {
        int fails = 0;
        System.out.println("[RetrievalTest]");

        // ---- Tokenizer：ASCII 小写化 + CJK unigram + CJK bigram ----
        {
            List<String> t = Tokenizer.tokenize("HelloWorld 测试 ABC");
            fails += check("tokenizer lowercases ascii words",
                    t.contains("helloworld") && t.contains("abc"));
            fails += check("tokenizer emits CJK unigrams",
                    t.contains("测") && t.contains("试"));
            fails += check("tokenizer emits CJK bigram for runs", t.contains("测试"));

            List<String> empty = Tokenizer.tokenize("");
            fails += check("tokenizer handles empty input", empty.isEmpty());
        }

        // ---- InvertedIndex：词频 / 文档频率 / 文档长度 ----
        {
            InvertedIndex idx = new InvertedIndex();
            idx.addDoc("d1", List.of("alpha", "beta", "alpha"));
            idx.addDoc("d2", List.of("beta", "gamma"));

            fails += check("tf counts repeated term in a doc", idx.tf("d1", "alpha") == 2);
            fails += check("tf returns 0 for absent term", idx.tf("d1", "gamma") == 0);
            fails += check("docFreq counts docs containing term", idx.docFreq("beta") == 2);
            fails += check("totalDocs counts distinct docs", idx.totalDocs() == 2);
            // 文档长度用「去重后的词数」，避免重复词膨胀（BM25 常规做法）
            fails += check("docLength uses distinct term count", idx.docLength("d1") == 2);
        }

        // ---- BM25：相关文档得分更高，topK 按分降序 ----
        {
            InvertedIndex idx = new InvertedIndex();
            idx.addDoc("d1", List.of("java", "thread", "concurrency"));
            idx.addDoc("d2", List.of("python", "data", "science"));
            idx.addDoc("d3", List.of("java", "jvm", "memory"));

            Map<String, Double> scores = Bm25.score(List.of("java"), idx);
            fails += check("bm25 scores docs containing the term",
                    scores.containsKey("d1") && scores.containsKey("d3"));
            fails += check("bm25 excludes docs without the term", !scores.containsKey("d2"));

            List<String> top = Bm25.topK(scores, 2);
            fails += check("bm25 topK returns min(k, matches)", top.size() == 2);
            fails += check("bm25 topK returns the matching docs",
                    top.contains("d1") && top.contains("d3"));
        }

        // ---- BM25：查询词重复出现应轻微加权 ----
        {
            InvertedIndex idx = new InvertedIndex();
            idx.addDoc("d1", List.of("redis", "cache"));

            double once = Bm25.score(List.of("redis"), idx).get("d1");
            double twice = Bm25.score(List.of("redis", "redis"), idx).get("d1");
            fails += check("bm25 query-term-frequency boosts score", twice > once);
        }

        // ---- 空索引不应崩溃 ----
        {
            InvertedIndex idx = new InvertedIndex();
            fails += check("bm25 on empty index returns empty map",
                    Bm25.score(List.of("x"), idx).isEmpty());
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
