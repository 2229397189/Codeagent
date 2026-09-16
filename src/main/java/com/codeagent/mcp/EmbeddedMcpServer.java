package com.codeagent.mcp;

import com.codeagent.core.Json;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程内最小 MCP server 测试桩：用一对内存流（PipedStream）与 JsonRpcClient 对连，
 * 支持 initialize / tools/list / tools/call，便于做零依赖端到端测试（不依赖真实子进程）。
 */
public class EmbeddedMcpServer implements Runnable {
    private final BufferedReader in;
    private final PrintStream out;
    private final Map<String, String> tools = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    public EmbeddedMcpServer(InputStream fromClient, OutputStream toClient) {
        this.in = new BufferedReader(new InputStreamReader(fromClient, StandardCharsets.UTF_8));
        this.out = new PrintStream(toClient, true, StandardCharsets.UTF_8);
    }

    public void addTool(String name, String responseText) {
        tools.put(name, responseText);
    }

    public void start() { pool.submit(this); }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> msg;
                try {
                    msg = (Map<String, Object>) Json.parse(line);
                } catch (RuntimeException e) {
                    continue;
                }
                String method = String.valueOf(msg.get("method"));
                Object idObj = msg.get("id");
                switch (method) {
                    case "initialize": {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("protocolVersion", "2024-11-05");
                        result.put("capabilities", Map.of("tools", Map.of()));
                        result.put("serverInfo", Map.of("name", "embedded-test", "version", "0.0"));
                        sendResult(idObj, result);
                        break;
                    }
                    case "tools/list": {
                        List<Object> list = new ArrayList<>();
                        for (String t : tools.keySet()) {
                            Map<String, Object> td = new LinkedHashMap<>();
                            td.put("name", t);
                            td.put("description", "test tool " + t);
                            td.put("inputSchema", Map.of("type", "object",
                                    "properties", Map.of("input", Map.of("type", "string"))));
                            list.add(td);
                        }
                        sendResult(idObj, Map.of("tools", list));
                        break;
                    }
                    case "tools/call": {
                        Map<String, Object> params = (Map<String, Object>) msg.get("params");
                        String name = String.valueOf(params.get("name"));
                        String text = tools.getOrDefault(name, "no such tool: " + name);
                        sendResult(idObj, Map.of("content", List.of(Map.of("type", "text", "text", text))));
                        break;
                    }
                    default:
                        // notification 或其它方法：忽略
                        break;
                }
            }
        } catch (java.io.IOException e) {
            // 流关闭，结束
        }
    }

    private synchronized void sendResult(Object idObj, Map<String, Object> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", idObj);
        resp.put("result", result);
        out.print(Json.write(resp));
        out.print("\n");
        out.flush();
    }

    public void close() {
        try { pool.shutdownNow(); } catch (Exception ignored) {}
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }
}
