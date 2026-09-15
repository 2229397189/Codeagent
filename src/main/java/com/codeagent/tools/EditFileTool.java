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
 * edit_file：对文件做精确字符串替换，生成 unified diff（review-before-write 的预览）。
 * 真实落盘前的审批门在 M4 的 PermissionManager 中接入；此处先直接应用并返回 diff 供可观测。
 */
public class EditFileTool implements Tool {
    private final String workspace;

    public EditFileTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (String f : new String[]{"path", "old_string", "new_string"}) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "string");
            props.put(f, p);
        }
        params.put("properties", props);
        params.put("required", List.of("path", "old_string", "new_string"));
        return new ToolSpec("edit_file", "Replace the first occurrence of old_string with new_string in a file", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String p = call.argStr("path");
        String old = call.argStr("old_string");
        String nw = call.argStr("new_string");
        if (p == null || old == null || nw == null) return ToolResult.error("missing path/old_string/new_string");
        Path file;
        try {
            file = ToolSupport.resolve(workspace, p);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(file)) return ToolResult.error("not a regular file: " + p);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int idx = content.indexOf(old);
            if (idx < 0) return ToolResult.error("old_string not found in " + p);
            String updated = content.substring(0, idx) + nw + content.substring(idx + old.length());
            String diff = DiffUtil.unifiedDiff(p, content, updated);
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return ToolResult.ok(diff);
        } catch (Exception e) {
            return ToolResult.error("edit failed: " + e.getMessage());
        }
    }
}
