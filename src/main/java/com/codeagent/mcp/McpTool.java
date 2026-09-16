package com.codeagent.mcp;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 MCP server 暴露的一个工具适配成 CodeAgent 的统一 Tool 协议，
 * 使外部 MCP 工具对主循环完全透明：模型只看到统一的 spec，执行时走 MCP 协议转发。
 */
public class McpTool implements Tool {
    private final McpClient client;
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpTool(McpClient client, String name, String description, Map<String, Object> inputSchema) {
        this.client = client;
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null ? new LinkedHashMap<>() : inputSchema;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name, description, inputSchema);
    }

    @Override
    public ToolResult execute(ToolCall call, PermissionManager perms) {
        try {
            String out = client.callTool(name, call.arguments, 30_000);
            return ToolResult.ok(out);
        } catch (Exception e) {
            return ToolResult.error("mcp tool failed: " + name + " (" + e.getMessage() + ")");
        }
    }
}
