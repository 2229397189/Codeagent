package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * list_files：列出工作区内某目录的直接子项（目录名带尾斜杠），供模型"按需探索"代码库，
 * 避免一次性把整个仓库塞进 prompt（对齐 Aider repo-map / MiniCode 的按需探索）。
 */
public class ListFilesTool implements Tool {
    private final String workspace;

    public ListFilesTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", "directory to list (default workspace root)");
        props.put("path", p);
        params.put("properties", props);
        return new ToolSpec("list_files", "List immediate children of a directory", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String pathArg = call.argStr("path") == null ? "." : call.argStr("path");
        Path root;
        try {
            root = ToolSupport.resolve(workspace, pathArg);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isDirectory(root)) return ToolResult.error("not a directory: " + pathArg);
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path child : ds) {
                String name = root.relativize(child).toString().replace('\\', '/');
                sb.append(name);
                if (Files.isDirectory(child)) sb.append("/");
                sb.append("\n");
            }
        } catch (IOException e) {
            return ToolResult.error("list failed: " + e.getMessage());
        }
        return ToolResult.ok(sb.toString());
    }
}
