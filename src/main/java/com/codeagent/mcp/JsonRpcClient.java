package com.codeagent.mcp;

import com.codeagent.core.Json;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 零依赖 JSON-RPC 2.0 客户端（over 任意 InputStream / OutputStream，典型为子进程的 stdio）。
 * 设计对齐 MCP（Model Context Protocol）的传输层约定：
 *  - 每行一个 JSON 对象（newline-delimited）；
 *  - 用自增 id 匹配「请求 -> 响应」；
 *  - 无 id 的消息为通知（notification），由服务端单向推送，客户端忽略。
 */
public class JsonRpcClient {
    private final BufferedReader in;
    private final PrintStream out;
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Thread reader;
    private int nextId = 1;
    private volatile boolean closed = false;

    public JsonRpcClient(InputStream fromServer, OutputStream toServer) {
        this.in = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8));
        this.out = new PrintStream(toServer, true, StandardCharsets.UTF_8);
        this.reader = new Thread(this::readLoop, "mcp-jsonrpc-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> msg;
                try {
                    msg = (Map<String, Object>) Json.parse(line);
                } catch (RuntimeException e) {
                    continue; // 解析失败跳过，不致命
                }
                Object idObj = msg.get("id");
                if (idObj instanceof Number) {
                    int id = ((Number) idObj).intValue();
                    CompletableFuture<Map<String, Object>> f = pending.remove(id);
                    if (f != null) f.complete(msg);
                }
                // 无 id = notification，忽略
            }
        } catch (java.io.IOException e) {
            for (CompletableFuture<Map<String, Object>> f : pending.values()) {
                f.completeExceptionally(e);
            }
            pending.clear();
        }
    }

    /** 发送请求并阻塞等待匹配 id 的响应；返回完整响应对象（含 result 或 error）。 */
    public synchronized Map<String, Object> request(String method, Object params, long timeoutMs) throws Exception {
        int id = nextId++;
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        out.print(Json.write(req));
        out.print("\n");
        out.flush();
        try {
            Map<String, Object> resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (resp.containsKey("error")) {
                Map<String, Object> err = (Map<String, Object>) resp.get("error");
                throw new RuntimeException("MCP error: " + err);
            }
            return resp;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RuntimeException("MCP request timeout: " + method);
        }
    }

    /** 发送单向通知（不期望响应，如 notifications/initialized） */
    public synchronized void notify(String method, Object params) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) req.put("params", params);
        out.print(Json.write(req));
        out.print("\n");
        out.flush();
    }

    public void close() {
        closed = true;
        try { out.close(); } catch (Exception ignored) {}
        try { reader.interrupt(); } catch (Exception ignored) {}
    }
}
