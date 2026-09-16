package com.codeagent.test;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.mcp.EmbeddedMcpServer;
import com.codeagent.mcp.JsonRpcClient;
import com.codeagent.mcp.McpClient;
import com.codeagent.mcp.McpTool;
import com.codeagent.permission.PermissionManager;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP（Model Context Protocol）端到端测试：断言「返回正确的值」，而非仅不报错。
 * 用一对内存管道把 JsonRpcClient 与进程内 MCP server 对连，
 * 完整跑通 initialize -> initialized -> tools/list -> tools/call，不需要真实子进程。
 */
public class McpTest {

    public static int run() {
        int fails = 0;
        System.out.println("[McpTest]");

        McpClient client = null;
        EmbeddedMcpServer server = null;
        try {
            // client -> server 与 server -> client 两条管道，读写各在不同线程，避免 PipedStream 自锁
            PipedInputStream clientToServerIn = new PipedInputStream();
            PipedOutputStream clientToServerOut = new PipedOutputStream(clientToServerIn);
            PipedInputStream serverToClientIn = new PipedInputStream();
            PipedOutputStream serverToClientOut = new PipedOutputStream(serverToClientIn);

            JsonRpcClient rpc = new JsonRpcClient(serverToClientIn, clientToServerOut);
            server = new EmbeddedMcpServer(clientToServerIn, serverToClientOut);
            server.addTool("echo", "pong");
            server.addTool("add", "sum");
            server.start();

            client = new McpClient(rpc, "embedded");
            client.start(3_000);

            // ---- 握手后应拿到 server 的工具清单 ----
            fails += check("mcp handshake lists both tools", client.toolDefs().size() == 2);

            // ---- tools/call 应返回工具的文本输出 ----
            String echo = client.callTool("echo", new LinkedHashMap<>(), 3_000);
            fails += check("mcp callTool returns the tool output", "pong".equals(echo));

            String add = client.callTool("add", new LinkedHashMap<>(), 3_000);
            fails += check("mcp callTool returns the second tool output", "sum".equals(add));

            // ---- 适配成 CodeAgent 统一 Tool 协议后仍可正常执行 ----
            Map<String, Object> def = client.toolDefs().get(0);
            McpTool tool = new McpTool(client,
                    String.valueOf(def.get("name")),
                    String.valueOf(def.get("description")),
                    (Map<String, Object>) def.get("inputSchema"));

            fails += check("mcp tool exposes its name via spec", "echo".equals(tool.spec().name));

            ToolResult res = tool.execute(new ToolCall("c1", "echo", new LinkedHashMap<>()),
                    PermissionManager.ALLOW_ALL);
            fails += check("mcp tool executes without error", !res.isError);
            fails += check("mcp tool returns the server payload", "pong".equals(res.output));

        } catch (Exception e) {
            fails += check("mcp end-to-end ran without exception", false);
            System.out.println("    cause: " + e);
        } finally {
            if (server != null) server.close();
            if (client != null) client.close();
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
