package com.codeagent.test;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具集测试：断言“返回正确的值”。
 * 覆盖 read/grep/list/edit/patch/run_command 的正确行为与路径沙箱拒绝逃逸。
 */
public class ToolsTest {

    public static int run() {
        int fails = 0;
        System.out.println("[ToolsTest]");
        try {
            Path ws = Files.createTempDirectory("codeagent-test");
            String WS = ws.toString();
            ToolRegistry reg = new ToolRegistry();
            reg.register(new ReadFileTool(WS));
            reg.register(new GrepTool(WS));
            reg.register(new ListFilesTool(WS));
            reg.register(new EditFileTool(WS));
            reg.register(new PatchTool(WS));
            reg.register(new RunCommandTool(WS));

            // read_file：返回精确字节对应的文本
            Path f1 = ws.resolve("a.txt");
            Files.writeString(f1, "hello\nworld\n", StandardCharsets.UTF_8);
            ToolResult r = reg.execute(new ToolCall("c1", "read_file", Map.of("path", "a.txt")), PermissionManager.ALLOW_ALL);
            fails += check("read_file returns exact content", "hello\nworld\n".equals(r.output));

            // grep：返回正确文件与行号
            Path f2 = ws.resolve("b.txt");
            Files.writeString(f2, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);
            ToolResult g = reg.execute(new ToolCall("c2", "grep", Map.of("pattern", "bet")), PermissionManager.ALLOW_ALL);
            fails += check("grep finds 'beta' at line 2", g.output.contains("b.txt:2:beta"));

            // list_files：列出直接子项（目录带尾斜杠）
            Files.createDirectory(ws.resolve("sub"));
            ToolResult l = reg.execute(new ToolCall("c3", "list_files", new LinkedHashMap<>()), PermissionManager.ALLOW_ALL);
            fails += check("list_files shows a.txt and sub/", l.output.contains("a.txt") && l.output.contains("sub/"));

            // edit_file：精确替换并产出 unified diff
            Path f3 = ws.resolve("c.txt");
            Files.writeString(f3, "line1\nOLD\nline3\n", StandardCharsets.UTF_8);
            ToolResult e = reg.execute(new ToolCall("c4", "edit_file",
                    Map.of("path", "c.txt", "old_string", "OLD", "new_string", "NEW")), PermissionManager.ALLOW_ALL);
            String edited = Files.readString(f3, StandardCharsets.UTF_8);
            fails += check("edit_file changes OLD -> NEW", edited.equals("line1\nNEW\nline3\n"));
            fails += check("edit_file output is a unified diff", e.output.contains("@@") && e.output.contains("-OLD") && e.output.contains("+NEW"));

            // patch：用上面生成的 diff 打到另一个副本，结果一致
            Path f4 = ws.resolve("d.txt");
            Files.writeString(f4, "line1\nOLD\nline3\n", StandardCharsets.UTF_8);
            ToolResult p = reg.execute(new ToolCall("c5", "patch",
                    Map.of("path", "d.txt", "diff", e.output)), PermissionManager.ALLOW_ALL);
            String patched = Files.readString(f4, StandardCharsets.UTF_8);
            fails += check("patch reproduces edit result", patched.equals("line1\nNEW\nline3\n"));

            // run_command：执行并返回正确输出
            ToolResult rc = reg.execute(new ToolCall("c6", "run_command", Map.of("command", "echo hello")), PermissionManager.ALLOW_ALL);
            fails += check("run_command echoes 'hello'", "hello".equals(rc.output.trim()));

            // 路径沙箱：拒绝逃逸工作区
            ToolResult esc = reg.execute(new ToolCall("c7", "read_file", Map.of("path", "../escape.txt")), PermissionManager.ALLOW_ALL);
            fails += check("path escape is rejected", esc.isError);

        } catch (Exception ex) {
            fails++;
            System.out.println("  FAIL exception: " + ex);
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
