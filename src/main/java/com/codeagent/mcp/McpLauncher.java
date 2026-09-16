package com.codeagent.mcp;

import java.util.List;

/**
 * 启动外部 MCP server 子进程并与之建立 stdio 连接（MCP 标准 transport）。
 * 例：launch(List.of("node", "my-server.js"), 5000)
 */
public class McpLauncher {
    public static McpClient launch(List<String> command, long timeoutMs) throws Exception {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("empty MCP command");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        JsonRpcClient rpc = new JsonRpcClient(p.getInputStream(), p.getOutputStream());
        McpClient client = new McpClient(rpc, String.join(" ", command));
        client.start(timeoutMs);
        return client;
    }
}
