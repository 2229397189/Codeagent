package com.codeagent.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档切块后的最小检索单元。索引与召回都以 chunk 为单位。
 */
public class Chunk {
    public String id;
    public String docId;
    public String text;

    public Chunk() {}
    public Chunk(String id, String docId, String text) {
        this.id = id; this.docId = docId; this.text = text;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("docId", docId);
        m.put("text", text);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Chunk fromMap(Map<String, Object> m) {
        Chunk c = new Chunk();
        c.id = String.valueOf(m.get("id"));
        c.docId = String.valueOf(m.get("docId"));
        c.text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
        return c;
    }
}
