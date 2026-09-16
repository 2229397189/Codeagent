package com.codeagent.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 被索引的文档（RAG 语料单元）。
 */
public class Document {
    public String id;
    public String text;
    public Map<String, String> metadata = new LinkedHashMap<>();

    public Document() {}
    public Document(String id, String text) { this.id = id; this.text = text; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("text", text);
        m.put("metadata", metadata);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Document fromMap(Map<String, Object> m) {
        Document d = new Document();
        d.id = String.valueOf(m.get("id"));
        d.text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
        if (m.get("metadata") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("metadata")).entrySet()) {
                d.metadata.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return d;
    }
}
