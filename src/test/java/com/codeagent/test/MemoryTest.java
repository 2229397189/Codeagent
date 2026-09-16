package com.codeagent.test;

import com.codeagent.memory.LongTermMemory;
import com.codeagent.memory.MemoryRecord;
import com.codeagent.memory.ShortTermMemory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 记忆系统测试：断言「返回正确的值」，而非仅不报错。
 * 覆盖 短期记忆（容量上限 + 重要性/时效性淘汰）与 长期记忆（记住/召回/遗忘 + 跨实例持久化）。
 */
public class MemoryTest {

    public static int run() {
        int fails = 0;
        System.out.println("[MemoryTest]");

        // ---- ShortTermMemory：容量上限 + 按「重要性 × 时效性」淘汰 ----
        {
            ShortTermMemory stm = new ShortTermMemory(3);
            stm.note("a", 0.1);
            stm.note("b", 0.9);
            stm.note("c", 0.2);
            stm.note("d", 0.3); // 超出容量 -> 淘汰价值最低的一条
            fails += check("short-term respects capacity", stm.size() == 3);

            boolean hasB = false, hasA = false;
            for (ShortTermMemory.Note n : stm.recent()) {
                if ("b".equals(n.content)) hasB = true;
                if ("a".equals(n.content)) hasA = true;
            }
            fails += check("short-term keeps the high-importance note", hasB);
            fails += check("short-term evicts the lowest-value note", !hasA);

            String rendered = ShortTermMemory.render(stm.recent());
            fails += check("short-term renders a prompt section", rendered.contains("Working notes"));
        }

        // ---- LongTermMemory：remember / recall / forget + 跨实例持久化 ----
        try {
            Path dir = Files.createTempDirectory("codeagent-mem");
            Path file = dir.resolve("memory.jsonl");

            LongTermMemory ltm = new LongTermMemory(file);
            String id1 = ltm.remember("Java uses JVM for memory management", MemoryRecord.Type.FACT, "user");
            String id2 = ltm.remember("Prefer BM25 for lexical retrieval", MemoryRecord.Type.TOPIC, "doc");

            fails += check("remember returns a non-null id", id1 != null && id2 != null);
            fails += check("size reflects both stored records", ltm.size() == 2);

            List<MemoryRecord> hits = ltm.recall("JVM memory", 5);
            fails += check("recall returns the relevant record first",
                    !hits.isEmpty() && hits.get(0).content.contains("JVM"));

            fails += check("forget removes a record", ltm.forget(id1) && ltm.size() == 1);
            fails += check("forget on unknown id is a no-op", !ltm.forget("nope") && ltm.size() == 1);

            // 重新打开同一个文件：验证确实落盘并可再次召回
            LongTermMemory reloaded = new LongTermMemory(file);
            fails += check("memory survives reload from disk", reloaded.size() == 1);
            fails += check("reloaded memory is still searchable",
                    !reloaded.recall("BM25 retrieval", 5).isEmpty());
        } catch (Exception e) {
            fails += check("long-term memory ran without exception", false);
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
