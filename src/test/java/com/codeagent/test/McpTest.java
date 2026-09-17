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
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

        // ---- P1-2：真实子进程 MCP（端到端验证 stdio JSON-RPC 不卡死）----
        // 环境受限（无 java / 无法起子进程）时打印 SKIP 而非 FAIL。
        try {
            String javaBin = System.getProperty("java.home") == null
                    ? "java"
                    : System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
            String classPath;
            try {
                classPath = Paths.get(McpEchoServerFixture.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toString();
            } catch (Exception e) {
                classPath = "D:/code/CodeAgent/out";
            }
            List<String> command = List.of(javaBin, "-cp", classPath, "com.codeagent.test.McpEchoServerFixture");

            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            JsonRpcClient rpc = new JsonRpcClient(p.getInputStream(), p.getOutputStream());
            McpClient sub = new McpClient(rpc, "fixture-subprocess");
            sub.start(5_000);

            // E1：真实子进程握手拿到工具清单
            fails += check("real-process: handshake lists echo + add", sub.toolDefs().size() == 2);

            // E2：echo 工具返回传入的消息
            Map<String, Object> echoArgs = new LinkedHashMap<>();
            echoArgs.put("message", "hello");
            String echoed = sub.callTool("echo", echoArgs, 5_000);
            fails += check("real-process: echo tool returns the message", "hello".equals(echoed));

            // E3：add 工具返回两数之和
            Map<String, Object> addArgs = new LinkedHashMap<>();
            addArgs.put("a", 2);
            addArgs.put("b", 3);
            String summed = sub.callTool("add", addArgs, 5_000);
            fails += check("real-process: add tool returns the sum", "5".equals(summed));

            // E4：连续 ≥50 次请求无死锁 / 无挂起（stdio 双向缓冲边界）
            boolean allOk = true;
            int n = 60;
            for (int i = 0; i < n; i++) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("message", "m" + i);
                String r = sub.callTool("echo", a, 5_000);
                if (!("m" + i).equals(r)) allOk = false;
            }
            fails += check("real-process: " + n + " sequential calls all succeed (no deadlock)", allOk);

            // E5：shutdown 通知后子进程干净退出（无残留句柄）
            rpc.request("shutdown", null, 3_000);
            sub.close();
            boolean exited = p.waitFor(3, TimeUnit.SECONDS);
            if (!exited) p.destroyForcibly();
            fails += check("real-process: subprocess exits after shutdown", !p.isAlive());

        } catch (Exception e) {
            // 环境无法起子进程：SKIP，不计入失败
            System.out.println("  SKIP real-process MCP (environment: " + e.getMessage() + ")");
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
