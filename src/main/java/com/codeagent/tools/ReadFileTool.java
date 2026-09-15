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
 * read_file：读取工作区内文件内容，返回精确字节对应的文本。
 * 大文件离屏（> largeResultKb）由 M3 的 ToolResultStorage 接管，这里先返回全文。
 */
public class ReadFileTool implements Tool {
    private final String workspace;

    public ReadFileTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", "relative path to the file within workspace");
        props.put("path", p);
        params.put("properties", props);
        params.put("required", List.of("path"));
        return new ToolSpec("read_file", "Read a file's content as UTF-8 text", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String path = call.argStr("path");
        if (path == null) return ToolResult.error("missing path");
        Path file;
        try {
            file = ToolSupport.resolve(workspace, path);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isRegularFile(file)) return ToolResult.error("not a regular file: " + path);
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return ToolResult.ok(content);
        } catch (Exception e) {
            return ToolResult.error("read failed: " + e.getMessage());
        }
    }
}
