package com.codeagent.mcp;

import com.codeagent.core.Json;
import com.codeagent.core.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端：完成 initialize -> initialized -> tools/list 握手，并提供 tools/call。
 * 零依赖：仅复用项目内自研 JSON 解析/序列化（com.codeagent.core.Json）。
 * 基础版：覆盖 stdio transport 的核心握手与工具调用，不实现 resources / prompts / sampling。
 */
public class McpClient {
    private final JsonRpcClient rpc;
    private final String serverName;
    private final List<Map<String, Object>> toolDefs = new ArrayList<>();

    public McpClient(JsonRpcClient rpc, String serverName) {
        this.rpc = rpc;
        this.serverName = serverName;
    }

    /** 握手：初始化会话 -> 通知 initialized -> 拉取工具清单 */
    public void start(long timeoutMs) throws Exception {
        Map<String, Object> initParams = new LinkedHashMap<>();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.put("capabilities", new LinkedHashMap<>());
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "CodeAgent");
        clientInfo.put("version", "0.1");
        initParams.put("clientInfo", clientInfo);

        rpc.request("initialize", initParams, timeoutMs);

        Map<String, Object> ready = new LinkedHashMap<>();
        rpc.notify("notifications/initialized", ready);

        Map<String, Object> listResp = rpc.request("tools/list", new LinkedHashMap<>(), timeoutMs);
        Object result = listResp.get("result");
        if (result instanceof Map) {
            Object tools = ((Map<String, Object>) result).get("tools");
            if (tools instanceof List) {
                for (Object t : (List<Object>) tools) {
                    if (t instanceof Map) toolDefs.add((Map<String, Object>) t);
                }
            }
        }
    }

    public List<Map<String, Object>> toolDefs() { return toolDefs; }

    /** 调用一个 MCP 工具，返回其文本内容（兼容 {content:[{type:text,text}]} 与兜底序列化） */
    public String callTool(String name, Map<String, Object> args, long timeoutMs) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args == null ? new LinkedHashMap<>() : args);
        Map<String, Object> resp = rpc.request("tools/call", params, timeoutMs);
        Object result = resp.get("result");
        if (result instanceof Map) {
            Object content = ((Map<String, Object>) result).get("content");
            if (content instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object c : (List<Object>) content) {
                    if (c instanceof Map) {
                        Object text = ((Map<String, Object>) c).get("text");
                        if (text != null) sb.append(text);
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
            Object isErr = ((Map<String, Object>) result).get("isError");
            if (Boolean.TRUE.equals(isErr)) {
                return "[mcp tool error] " + Json.write(result);
            }
            return Json.write(result);
        }
        return Json.write(resp);
    }

    public void close() { rpc.close(); }
}
