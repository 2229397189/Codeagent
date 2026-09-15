package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * grep：在工作区内递归搜索正则匹配，返回 file:line:content。
 * 跳过 .git / out 等目录，避免噪声与超大扫描。
 */
public class GrepTool implements Tool {
    private final String workspace;

    public GrepTool(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "regex pattern to search");
        props.put("pattern", pattern);
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "directory to search (default workspace root)");
        props.put("path", path);
        params.put("properties", props);
        params.put("required", List.of("pattern"));
        return new ToolSpec("grep", "Recursively search files for a regex pattern", params);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        String pattern = call.argStr("pattern");
        if (pattern == null) return ToolResult.error("missing pattern");
        String pathArg = call.argStr("path") == null ? "." : call.argStr("path");
        Path root;
        try {
            root = ToolSupport.resolve(workspace, pathArg);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!Files.isDirectory(root)) return ToolResult.error("not a directory: " + pathArg);
        Path ws = java.nio.file.Paths.get(workspace).toAbsolutePath().normalize();
        Pattern pat;
        try {
            pat = Pattern.compile(pattern);
        } catch (Exception e) {
            return ToolResult.error("invalid regex: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .forEach(p -> {
                        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                            String line;
                            int n = 0;
                            while ((line = r.readLine()) != null) {
                                n++;
                                if (pat.matcher(line).find()) {
                                    sb.append(ws.relativize(p).toString().replace('\\', '/'))
                                            .append(':').append(n).append(':').append(line).append('\n');
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            return ToolResult.error("grep failed: " + e.getMessage());
        }
        return ToolResult.ok(sb.length() == 0 ? "(no matches)" : sb.toString());
    }

    private boolean isSkipped(Path p) {
        for (Path part : p) {
            String s = part.toString();
            if (".git".equals(s) || "out".equals(s) || "node_modules".equals(s)) return true;
        }
        return false;
    }
}
