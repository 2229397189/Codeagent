package com.codeagent.test;

import com.codeagent.context.*;
import com.codeagent.core.Message;
import com.codeagent.core.Usage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 上下文治理测试（M3）：断言“返回正确的值”，而非仅不报错。
 * 覆盖 ContextBudget 水位记账、MicroCompact 确定性裁剪、ToolResultStorage 大结果离屏、AutoCompact 有损压缩。
 */
public class ContextTest {

    public static int run() {
        int fails = 0;
        System.out.println("[ContextTest]");

        // ---- ContextBudget：水位记账 ----
        {
            ContextBudget b = new ContextBudget();
            b.limit = 128_000;
            b.observe(new Usage(80_000, 1_000)); // 81k / 128k = 63.3%
            fails += check("budget below 70% -> OK", b.phase() == ContextBudget.Phase.OK);

            b.observe(new Usage(90_000, 1_000)); // 91k / 128k = 71.1%
            fails += check("budget at ~71% -> WARN", b.phase() == ContextBudget.Phase.WARN);

            b.observe(new Usage(115_200, 0)); // 115200 / 128000 = 90% 整
            fails += check("budget at 90% -> AUTO", b.phase() == ContextBudget.Phase.AUTO);

            b.observe(new Usage(130_000, 0)); // >=100%
            fails += check("budget >=100% -> HARD", b.phase() == ContextBudget.Phase.HARD);

            // observe(null) 不更新、不抛
            b.observe(null);
            fails += check("observe(null) keeps HARD", b.phase() == ContextBudget.Phase.HARD);

            // 自定义阈值
            ContextBudget b2 = new ContextBudget();
            b2.limit = 1000; b2.warnPct = 50; b2.autoPct = 80;
            b2.observe(new Usage(500, 0)); // 50% -> 恰在 warn 边界
            fails += check("custom 50% threshold -> WARN", b2.phase() == ContextBudget.Phase.WARN);
        }

        // ---- MicroCompact：确定性裁剪 ----
        {
            // keepRounds=1 => keepFrom = n - 2；保留 read_file 结果，替换早期非保留工具结果
            List<Message> msgs = new ArrayList<>();
            msgs.add(Message.user("u0"));
            msgs.add(Message.assistant("a1"));
            msgs.add(Message.tool("t1", "grep", "grep-result-2"));      // distant, not kept
            msgs.add(Message.tool("t2", "read_file", "REF-MATERIAL-3")); // distant, kept
            msgs.add(Message.user("u4"));
            msgs.add(Message.assistant("a5"));
            msgs.add(Message.tool("t3", "grep", "grep-result-6"));      // recent (>= keepFrom)

            MicroCompact mc = new MicroCompact(1, Set.of("read_file"));
            List<Message> out = mc.apply(msgs);

            fails += check("micro replaces distant grep result",
                    "[used tool grep]".equals(out.get(2).content));
            fails += check("micro keeps distant read_file result",
                    "REF-MATERIAL-3".equals(out.get(3).content));
            fails += check("micro keeps recent grep result",
                    "grep-result-6".equals(out.get(6).content));
            fails += check("micro keeps user/assistant messages",
                    "u0".equals(out.get(0).content) && "a5".equals(out.get(5).content));

            // 幂等：已替换过的不会二次处理
            List<Message> out2 = mc.apply(out);
            fails += check("micro is idempotent on [used tool]",
                    "[used tool grep]".equals(out2.get(2).content));
        }

        // ---- ToolResultStorage：大结果离屏 ----
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 400; i++) sb.append("x"); // 400 chars > 200 preview cap
            String big = sb.toString();

            try {
                Path base = Files.createTempDirectory("codeagent-offscreen");
                ToolResultStorage storage = new ToolResultStorage(base);
                String stored = storage.store(big);

                fails += check("off-screen marker present", stored.startsWith("[off-screen, full at "));
                // 预览截断到 200 字符
                String preview = stored.substring(stored.indexOf("]\n") + 2);
                fails += check("off-screen preview capped at 200", preview.length() == 200);
                fails += check("off-screen preview matches head", big.substring(0, 200).equals(preview));

                // 完整内容确实落盘，可后续取回
                int start = stored.indexOf("full at ") + "full at ".length();
                int end = stored.indexOf("]", start);
                Path fullPath = Path.of(stored.substring(start, end));
                String full = Files.readString(fullPath, StandardCharsets.UTF_8);
                fails += check("off-screen full content persisted", big.equals(full));
            } catch (Exception ex) {
                fails += check("off-screen storage ran without IOException", false);
            }
        }

        // ---- AutoCompact：有损压缩保留最近用户意图 ----
        {
            List<Message> msgs = new ArrayList<>();
            msgs.add(Message.user("old task A"));
            msgs.add(Message.assistant("did A"));
            msgs.add(Message.user("latest task B"));

            AutoCompact ac = new AutoCompact();
            List<Message> out = ac.apply(msgs, m -> "SUMMARY_OF_HISTORY");

            fails += check("auto-compact emits [summary] head",
                    out.get(0).content.equals("[summary] SUMMARY_OF_HISTORY"));
            fails += check("auto-compact keeps latest user intent",
                    out.size() == 2 && out.get(1).role == Message.Role.user
                            && "latest task B".equals(out.get(1).content));
        }

        // ---- DefaultContextGovernance：管道编排（micro 每轮 / auto 达水位才触发）----
        {
            ContextBudget b = new ContextBudget();
            b.limit = 1000; b.warnPct = 70; b.autoPct = 90;
            DefaultContextGovernance g = new DefaultContextGovernance(b,
                    new MicroCompact(1, Set.of("read_file")),
                    DefaultContextGovernance.extractiveSummarizer());

            List<Message> msgs = new ArrayList<>();
            msgs.add(Message.user("u0"));
            msgs.add(Message.tool("t1", "grep", "g1"));
            msgs.add(Message.tool("t2", "read_file", "r2"));
            msgs.add(Message.user("u3"));
            msgs.add(Message.assistant("a4"));

            // OK 水位：只做 micro（早期 grep 结果被裁剪，read_file 保留）
            b.observe(new Usage(100, 0)); // 10% -> OK
            List<Message> out = g.apply(msgs);
            fails += check("governance OK applies micro-compact only",
                    "[used tool grep]".equals(out.get(1).content) && "r2".equals(out.get(2).content));

            // AUTO 水位：触发 auto-compact（压缩为摘要 + 最近用户意图）
            b.observe(new Usage(950, 0)); // 95% -> AUTO
            List<Message> out2 = g.apply(msgs);
            fails += check("governance AUTO triggers auto-compact",
                    out2.get(0).content.startsWith("[summary]"));
            fails += check("governance AUTO keeps latest user intent",
                    out2.size() == 2 && out2.get(1).role == Message.Role.user
                            && "u3".equals(out2.get(1).content));
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
