package com.codeagent.test;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.permission.DefaultPermissionManager;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限测试（M4）：断言“返回正确的决策与副作用”，而非仅不报错。
 * 覆盖：路径沙箱拒绝逃逸、危险命令拦截、写前审批 ASK/ALLOW、审批持久化、权限门在 ToolRegistry 的拦截。
 */
public class PermissionTest {

    public static int run() {
        int fails = 0;
        System.out.println("[PermissionTest]");

        try {
            Path ws = Files.createTempDirectory("codeagent-perm");
            String WS = ws.toString();

            // ---- decide 决策矩阵 ----
            DefaultPermissionManager pm = new DefaultPermissionManager(ws, ws.resolve(".codeagent/permissions.json"));

            // 路径逃逸 -> DENY
            ToolCall escape = new ToolCall("c1", "read_file", Map.of("path", "../escape.txt"));
            fails += check("path escape -> DENY", pm.decide(escape) == PermissionManager.Decision.DENY);

            // 危险命令 -> DENY
            ToolCall rm = new ToolCall("c2", "run_command", Map.of("command", "rm -rf /"));
            fails += check("rm -rf / -> DENY", pm.decide(rm) == PermissionManager.Decision.DENY);
            ToolCall gitReset = new ToolCall("c3", "run_command", Map.of("command", "git reset --hard"));
            fails += check("git reset --hard -> DENY", pm.decide(gitReset) == PermissionManager.Decision.DENY);

            // 安全命令 -> ALLOW
            ToolCall echo = new ToolCall("c4", "run_command", Map.of("command", "echo hello"));
            fails += check("echo hello -> ALLOW", pm.decide(echo) == PermissionManager.Decision.ALLOW);

            // 只读路径工具（工作区内）-> ALLOW
            ToolCall read = new ToolCall("c5", "read_file", Map.of("path", "a.txt"));
            fails += check("in-workspace read_file -> ALLOW", pm.decide(read) == PermissionManager.Decision.ALLOW);

            // 写工具未审批 -> ASK
            ToolCall edit = new ToolCall("c6", "edit_file", Map.of("path", "a.txt", "old_string", "x", "new_string", "y"));
            fails += check("unapproved edit_file -> ASK", pm.decide(edit) == PermissionManager.Decision.ASK);

            // 审批后 -> ALLOW
            pm.approve(edit);
            fails += check("approved edit_file -> ALLOW", pm.decide(edit) == PermissionManager.Decision.ALLOW);

            // 审批持久化：新建一个 manager 从同一文件加载，审批应仍在
            DefaultPermissionManager pm2 = new DefaultPermissionManager(ws, ws.resolve(".codeagent/permissions.json"));
            fails += check("approval persisted across reload", pm2.decide(edit) == PermissionManager.Decision.ALLOW);

            // ---- 权限门在 ToolRegistry 的真实拦截 ----
            ToolRegistry reg = new ToolRegistry();
            reg.register(new ReadFileTool(WS));
            reg.register(new EditFileTool(WS));
            reg.register(new RunCommandTool(WS));

            // 危险命令被门拦截
            ToolResult rDanger = reg.execute(new ToolCall("d1", "run_command", Map.of("command", "sudo rm -rf /")), pm2);
            fails += check("registry blocks dangerous command", rDanger.isError && rDanger.output.contains("permission denied"));

            // 未审批写：进入 review-before-write（先预览 diff、不落盘），由人批准后才真正写入
            Path g = ws.resolve("g.txt");
            Files.writeString(g, "x\n", StandardCharsets.UTF_8);
            ToolResult rAsk = reg.execute(new ToolCall("g1", "edit_file",
                    Map.of("path", "g.txt", "old_string", "x", "new_string", "y")), pm2);
            fails += check("unapproved write pauses for review (awaitUser + diff preview)",
                    rAsk.awaitUser && rAsk.output.contains("@@") && rAsk.output.contains("-x") && rAsk.output.contains("+y"));
            fails += check("unapproved write does NOT modify disk",
                    "x\n".equals(Files.readString(g, StandardCharsets.UTF_8)));

            // 审批后写可落盘，并返回 unified diff
            Path f = ws.resolve("b.txt");
            Files.writeString(f, "line1\nX\nline3\n", StandardCharsets.UTF_8);
            ToolCall editB = new ToolCall("d3", "edit_file",
                    Map.of("path", "b.txt", "old_string", "X", "new_string", "Y"));
            pm2.approve(editB);
            ToolResult rEdit = reg.execute(editB, pm2);
            String after = Files.readString(f, StandardCharsets.UTF_8);
            fails += check("approved write applies change", after.equals("line1\nY\nline3\n"));
            fails += check("approved write returns unified diff", rEdit.output.contains("@@") && rEdit.output.contains("-X") && rEdit.output.contains("+Y"));

            // 只读工具在门内正常执行
            Path rf = ws.resolve("c.txt");
            Files.writeString(rf, "hello\n", StandardCharsets.UTF_8);
            ToolResult rRead = reg.execute(new ToolCall("d4", "read_file", Map.of("path", "c.txt")), pm2);
            fails += check("in-workspace read passes gate with exact content", "hello\n".equals(rRead.output));

        } catch (Exception ex) {
            fails++;
            System.out.println("  FAIL exception: " + ex);
            ex.printStackTrace();
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
