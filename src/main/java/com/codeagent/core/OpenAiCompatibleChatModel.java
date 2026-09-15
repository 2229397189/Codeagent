package com.codeagent.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实模型实现：OpenAI 兼容 Chat Completions（GLM / DeepSeek / 通义 / Ollama 通用）。
 * 通过 java.net.http 直连，零第三方依赖。
 * API Key 仅从构造参数 / 环境变量读取，绝不写死在代码或仓库里。
 */
public class OpenAiCompatibleChatModel implements ChatModel {
    public String baseUrl;
    public String apiKey;
    public String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", toWire(messages));
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> ts = new ArrayList<>();
            for (ToolSpec t : tools) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", t.name);
                f.put("description", t.description);
                f.put("parameters", t.parameters);
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("type", "function");
                wrap.put("function", f);
                ts.add(wrap);
            }
            body.put("tools", ts);
            body.put("tool_choice", "auto");
        }
        String json = Json.write(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return parseResponse(resp.body());
    }

    @SuppressWarnings("unchecked")
    private ChatResponse parseResponse(String body) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(body);
        ChatResponse r = new ChatResponse();
        Object usageO = root.get("usage");
        if (usageO instanceof Map) {
            Map<String, Object> u = (Map<String, Object>) usageO;
            int p = toInt(u.get("prompt_tokens"));
            int c = toInt(u.get("completion_tokens"));
            r.usage = new Usage(p, c);
        }
        Object choicesO = root.get("choices");
        if (choicesO instanceof List) {
            List<Object> choices = (List<Object>) choicesO;
            if (!choices.isEmpty()) {
                Map<String, Object> ch = (Map<String, Object>) choices.get(0);
                Map<String, Object> msg = (Map<String, Object>) ch.get("message");
                Object content = msg.get("content");
                r.content = content == null ? null : String.valueOf(content);
                Object tcs = msg.get("tool_calls");
                if (tcs instanceof List) {
                    for (Object tco : (List<Object>) tcs) {
                        Map<String, Object> tc = (Map<String, Object>) tco;
                        ToolCall call = new ToolCall();
                        call.id = str(tc.get("id"));
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        call.name = str(fn.get("name"));
                        Object argsO = fn.get("arguments");
                        String argStr = argsO == null ? "{}" : String.valueOf(argsO);
                        try {
                            call.arguments = (Map<String, Object>) Json.parse(argStr);
                        } catch (Exception e) {
                            call.arguments = new LinkedHashMap<>();
                        }
                        r.toolCalls.add(call);
                    }
                }
            }
        }
        return r;
    }

    private List<Object> toWire(List<Message> messages) {
        List<Object> out = new ArrayList<>();
        for (Message m : messages) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("role", m.role.name());
            if (m.role == Message.Role.tool) {
                o.put("tool_call_id", m.toolCallId);
                o.put("content", m.content == null ? "" : m.content);
            } else if (m.role == Message.Role.assistant && m.toolCalls != null && !m.toolCalls.isEmpty()) {
                o.put("content", m.content);
                List<Object> tcs = new ArrayList<>();
                for (ToolCall tc : m.toolCalls) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", tc.name);
                    f.put("arguments", Json.write(tc.arguments == null ? new LinkedHashMap<>() : tc.arguments));
                    Map<String, Object> wrap = new LinkedHashMap<>();
                    wrap.put("id", tc.id);
                    wrap.put("type", "function");
                    wrap.put("function", f);
                    tcs.add(wrap);
                }
                o.put("tool_calls", tcs);
            } else {
                o.put("content", m.content == null ? "" : m.content);
            }
            out.add(o);
        }
        return out;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
