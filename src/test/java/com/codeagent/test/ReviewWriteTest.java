package com.codeagent.test;

import com.codeagent.cli.CodeAgentCli;
import com.codeagent.core.*;
import com.codeagent.permission.DefaultPermissionManager;
import com.codeagent.tools.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * review-before-write 测试：断言“返回正确的值”，而非仅不报错。
 * 覆盖：未审批写先预览 diff 且不落盘；审批后才真正写入；审批持久化后同目标不再暂停；
 *       patch 预览校验可应用且不落盘；CLI 自动批准/拒绝流程。
 */
public class ReviewWriteTest {

    public static int run() {
        int fails = 0;
        System.out.println("[ReviewWriteTest]");

        try {
            Path ws = Files.createTempDirectory("codeagent-rbw");

            // ---- 直接测 ToolRegistry 的 ASK -> preview(awaitUser) + 不落盘 ----
            ToolRegistry reg = new ToolRegistry();
            reg.register(new EditFileTool(ws.toString()));
            DefaultPermissionManager pm = new DefaultPermissionManager(ws, null);

            Path ef = ws.resolve("e.txt");
            Files.writeString(ef, "x\n", StandardCharsets.UTF_8);
            ToolCall ec = new ToolCall("e1", "edit_file",
                    new LinkedHashMap<>(Map.of("path", "e.txt", "old_string", "x", "new_string", "y")));
            ToolResult er = reg.execute(ec, pm);
            fails += check("edit preview pauses with awaitUser", er.awaitUser);
            fails += check("edit preview shows unified diff",
                    er.output.contains("@@") && er.output.contains("-x") && er.output.contains("+y"));
            fails += check("edit preview does NOT write disk",
                    "x\n".equals(Files.readString(ef, StandardCharsets.UTF_8)));

            // 审批后同一调用应真正落盘
            pm.approve(ec);
            ToolResult er2 = reg.execute(ec, pm);
            fails += check("after approve, edit writes disk",
                    "y\n".equals(Files.readString(ef, StandardCharsets.UTF_8)));
            fails += check("after approve, edit returns applied diff",
                    er2.output.contains("+y") && !er2.awaitUser);

            // ---- patch 预览：校验可应用且不落盘 ----
            ToolRegistry preg = new ToolRegistry();
            preg.register(new PatchTool(ws.toString()));
            DefaultPermissionManager ppm = new DefaultPermissionManager(ws, null);
            Path pf = ws.resolve("p.txt");
            Files.writeString(pf, "A\nB\nC\n", StandardCharsets.UTF_8);
            String diffStr = DiffUtil.unifiedDiff("p.txt", "A\nB\nC\n", "A\nX\nC\n");
            ToolCall pcall = new ToolCall("p1", "patch",
                    new LinkedHashMap<>(Map.of("path", "p.txt", "diff", diffStr)));
            ToolResult pv = preg.execute(pcall, ppm); // ASK -> preview
            fails += check("patch preview pauses with awaitUser", pv.awaitUser);
            fails += check("patch preview reports target", pv.output.contains("will apply to p.txt"));
            fails += check("patch preview does NOT modify disk",
                    "A\nB\nC\n".equals(Files.readString(pf, StandardCharsets.UTF_8)));

            // ---- CLI 端到端：自动批准，文件被写入，返回最终文本 ----
            Path wsCli = Files.createTempDirectory("codeagent-rbw-cli");
            AgentConfig cfg = new AgentConfig();
            cfg.workspace = wsCli.toString();
            cfg.useMock = false; // apiKey 为 null -> buildModel 走 OfflineModel，随后被 setModel 覆盖
            CodeAgentCli cli = new CodeAgentCli(cfg);
            cli.setModel(new MockChatModel(editThenFinal("f.txt", "OLD", "NEW", "done")));
            cli.approvePolicy = (name, diff) -> true; // 自动批准

            Path f = wsCli.resolve("f.txt");
            Files.writeString(f, "OLD\n", StandardCharsets.UTF_8);
            String out = cli.handle("change it");
            fails += check("CLI auto-approve writes the file",
                    "NEW\n".equals(Files.readString(f, StandardCharsets.UTF_8)));
            fails += check("CLI auto-approve returns final text", "done".equals(out));

            // 已审批目标：第二次写应直接 ALLOW 落盘，不再暂停
            cli.setModel(new MockChatModel(editThenFinal("f.txt", "NEW", "ZED", "done2")));
            String out2 = cli.handle("change again");
            fails += check("already-approved target writes without pausing",
                    "ZED\n".equals(Files.readString(f, StandardCharsets.UTF_8)));
            fails += check("already-approved target returns final text", "done2".equals(out2));

            // ---- CLI 端到端：自动拒绝，文件不被写入，模型收到拒绝反馈 ----
            Path wsRej = Files.createTempDirectory("codeagent-rbw-rej");
            AgentConfig cfgR = new AgentConfig();
            cfgR.workspace = wsRej.toString();
            cfgR.useMock = false;
            CodeAgentCli cliR = new CodeAgentCli(cfgR);
            cliR.setModel(new MockChatModel(editThenFinal("f.txt", "OLD", "NEW", "done")));
            cliR.approvePolicy = (name, diff) -> false; // 自动拒绝

            Path fr = wsRej.resolve("f.txt");
            Files.writeString(fr, "OLD\n", StandardCharsets.UTF_8);
            String outR = cliR.handle("change it");
            fails += check("CLI reject does NOT write the file",
                    "OLD\n".equals(Files.readString(fr, StandardCharsets.UTF_8)));
            fails += check("CLI reject still returns a turn result", outR != null);

        } catch (Exception ex) {
            fails++;
            System.out.println("  FAIL exception: " + ex);
            ex.printStackTrace();
        }
        return fails;
    }

    /** 构造脚本：第 1 次返回对 path 的 edit_file(old->nw)，第 2 次返回最终文本 finalText */
    private static List<ChatResponse> editThenFinal(String path, String oldStr, String newStr, String finalText) {
        ChatResponse r1 = new ChatResponse();
        r1.content = null;
        r1.toolCalls.add(new ToolCall("c1", "edit_file",
                new LinkedHashMap<>(Map.of("path", path, "old_string", oldStr, "new_string", newStr))));
        r1.usage = new Usage(5, 5);
        ChatResponse r2 = new ChatResponse();
        r2.content = finalText;
        r2.usage = new Usage(3, 2);
        return List.of(r1, r2);
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
