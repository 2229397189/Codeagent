package com.codeagent.core;

import java.util.Map;

/**
 * 工具 schema，喂给模型做 function calling。
 * parameters 采用 JSON-Schema 风格的 Map（type/properties/required 等），
 * 既用于构造请求，也用于本地入参校验。
 */
public class ToolSpec {
    public String name;
    public String description;
    public Map<String, Object> parameters;

    public ToolSpec() {}

    public ToolSpec(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }
}
