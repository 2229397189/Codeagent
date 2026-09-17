package com.codeagent.test;

import com.codeagent.core.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 测试桩（真实子进程用）：通过 stdio 走 newline-delimited JSON-RPC 2.0，
 * 完整响应 initialize / tools/list / tools/call，可被子进程模式下的 McpClient 拉起。
 * 仅用于测试（不进主代码），证明「零依赖 JSON-RPC over stdio」在真实进程边界下不卡死。
 */
public class McpEchoServerFixture {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (RuntimeException e) {
                continue; // 坏行跳过
            }
            if (!(parsed instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) parsed;
            Object idObj = msg.get("id");
            String method = String.valueOf(msg.get("method"));

            if ("shutdown".equals(method) || "exit".equals(method)) {
                if (idObj instanceof Number) {
                    Map<String, Object> empty = new LinkedHashMap<String, Object>();
                    Map<String, Object> shutdownResp = resp((Number) idObj, empty);
                    out.print(Json.write(shutdownResp));
                    out.print("\n");
                    out.flush();
                }
                break;
            }
            if (!(idObj instanceof Number)) continue; // notification，忽略

            Map<String, Object> result;
            if ("initialize".equals(method)) {
                result = initResult();
            } else if ("tools/list".equals(method)) {
                result = toolsList();
            } else if ("tools/call".equals(method)) {
                result = toolCall(msg);
            } else {
                result = new LinkedHashMap<String, Object>();
            }
            Map<String, Object> response = resp((Number) idObj, result);
            out.print(Json.write(response));
            out.print("\n");
            out.flush();
        }
    }

    private static Map<String, Object> resp(Number id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> initResult() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("protocolVersion", "2024-11-05");
        r.put("capabilities", new LinkedHashMap<String, Object>());
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("name", "fixture");
        info.put("version", "1.0");
        r.put("serverInfo", info);
        return r;
    }

    private static Map<String, Object> toolsList() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        List<Object> tools = new ArrayList<Object>();
        Map<String, Object> echoProps = new LinkedHashMap<String, Object>();
        echoProps.put("message", props("string"));
        tools.add(toolDef("echo", "echo back the message", echoProps));
        Map<String, Object> addProps = new LinkedHashMap<String, Object>();
        addProps.put("a", props("number"));
        addProps.put("b", props("number"));
        tools.add(toolDef("add", "add two numbers", addProps));
        r.put("tools", tools);
        return r;
    }

    private static Map<String, Object> props(String type) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("type", type);
        return p;
    }

    private static Map<String, Object> toolDef(String name, String desc, Map<String, Object> props) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", name);
        t.put("description", desc);
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", props);
        t.put("inputSchema", schema);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolCall(Map<String, Object> msg) {
        Map<String, Object> params = msg.get("params") instanceof Map
                ? (Map<String, Object>) msg.get("params") : new LinkedHashMap<String, Object>();
        String name = String.valueOf(params.get("name"));
        Map<String, Object> args = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : new LinkedHashMap<String, Object>();
        String text;
        if ("add".equals(name)) {
            double a = asNumber(args.get("a"));
            double b = asNumber(args.get("b"));
            text = String.valueOf((int) (a + b));
        } else {
            text = String.valueOf(args.get("message"));
        }
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Object> arr = new ArrayList<Object>();
        arr.add(content);
        result.put("content", arr);
        return result;
    }

    private static double asNumber(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (RuntimeException ignore) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
