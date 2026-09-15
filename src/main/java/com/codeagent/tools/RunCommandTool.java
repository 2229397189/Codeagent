package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * run_command：用 ProcessBuilder 执行 shell 命令，合并 stdout/stderr，带超时与退出码判定。
 * 危险命令分类拦截（rm -rf / git reset --hard / sudo 等）在 M4 的 PermissionManager 中接入。
 */
public class RunCommandTool implements Tool {
    private final String workspace;

    public RunCommandTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "string");
        cmd.put("description", "shell command to run");
        props.put("command", cmd);
        params.put("properties", props);
        params.put("required", List.of("command"));
        return new ToolSpec("run_command", "Execute a shell command and return its output", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String command = call.argStr("command");
        if (command == null || command.isBlank()) return ToolResult.error("empty command");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] parts = windows
                ? new String[]{"cmd.exe", "/c", command}
                : new String[]{"sh", "-c", command};
        try {
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.directory(new File(workspace));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ToolResult.error("command timed out");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) return ToolResult.error("exit " + p.exitValue() + ":\n" + out);
            return ToolResult.ok(out);
        } catch (Exception e) {
            return ToolResult.error("run failed: " + e.getMessage());
        }
    }
}
