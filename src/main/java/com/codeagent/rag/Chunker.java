package com.codeagent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切块：先按空行分段，超长段再按长度切分并保留 overlap，
 * 保证检索粒度适中、且不切断关键信息。
 */
public final class Chunker {
    private Chunker() {}

    public static List<Chunk> chunk(Document doc, int maxChars, int overlap) {
        List<Chunk> out = new ArrayList<>();
        String[] paras = doc.text.split("\n\\s*\n");
        int ci = 0;
        for (String para : paras) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (p.length() <= maxChars) {
                out.add(new Chunk(doc.id + "#" + ci++, doc.id, p));
            } else {
                int start = 0;
                while (start < p.length()) {
                    int end = Math.min(p.length(), start + maxChars);
                    out.add(new Chunk(doc.id + "#" + ci++, doc.id, p.substring(start, end)));
                    if (end == p.length()) break;
                    start = end - overlap;
                    if (start < 0) start = 0;
                }
            }
        }
        if (out.isEmpty()) out.add(new Chunk(doc.id + "#0", doc.id, doc.text));
        return out;
    }
}
