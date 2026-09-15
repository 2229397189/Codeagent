package com.codeagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型决定调用的一次工具。arguments 为模型给出的入参（已解析为 Map）。
 */
public class ToolCall {
    public String id;
    public String name;
    public Map<String, Object> arguments = new LinkedHashMap<>();

    public ToolCall() {}

    public ToolCall(String id, String name, Map<String, Object> args) {
        this.id = id;
        this.name = name;
        this.arguments = args != null ? args : new LinkedHashMap<>();
    }

    /** 取字符串参数，缺失返回 null */
    public String argStr(String key) {
        Object v = arguments.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
