package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * patch：把一个 unified diff 应用到文件（模型可直接产出补丁）。
 * 复用 DiffUtil.applyUnifiedDiff。
 */
public class PatchTool implements Tool {
    private final String workspace;

    public PatchTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        props.put("path", path);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("type", "string");
        diff.put("description", "unified diff to apply");
        props.put("diff", diff);
        params.put("properties", props);
        params.put("required", List.of("path", "diff"));
        return new ToolSpec("patch", "Apply a unified diff to a file", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String p = call.argStr("path");
        String diff = call.argStr("diff");
        if (p == null || diff == null) return ToolResult.error("missing path/diff");
        Path file;
        try {
            file = ToolSupport.resolve(workspace, p);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(file)) return ToolResult.error("not a regular file: " + p);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String updated = DiffUtil.applyUnifiedDiff(content, diff);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return ToolResult.ok("patched " + p);
        } catch (Exception e) {
            return ToolResult.error("patch failed: " + e.getMessage());
        }
    }

    /** 写前预览：仅校验 diff 可应用并返回预览，不落盘 */
    @Override
    public ToolResult preview(ToolCall call, PermissionManager perms) {
        String p = call.argStr("path");
        String diff = call.argStr("diff");
        if (p == null || diff == null) return ToolResult.error("missing path/diff");
        Path file;
        try {
            file = ToolSupport.resolve(workspace, p);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(file)) return ToolResult.error("not a regular file: " + p);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            DiffUtil.applyUnifiedDiff(content, diff); // 仅校验可应用，不写盘
            return ToolResult.ok("will apply to " + p + ":\n" + diff);
        } catch (Exception e) {
            return ToolResult.error("patch does not apply: " + e.getMessage());
        }
    }
}
