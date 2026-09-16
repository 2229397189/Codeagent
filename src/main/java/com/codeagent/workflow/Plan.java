package com.codeagent.workflow;

import com.codeagent.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 中的「计划」数据结构：有序步骤列表。
 * 解析模型产出的 JSON 计划（数组或 {steps:[...]} 包装），容错丢弃无法识别的条目。
 */
public final class Plan {
    private Plan() {}

    public static final class Step {
        public final int index;
        public final String description;
        /** 可选：指定由哪个子 Agent（planner / executor 角色）负责，默认 executor */
        public final String assignee;

        public Step(int index, String description) {
            this(index, description, null);
        }

        public Step(int index, String description, String assignee) {
            this.index = index;
            this.description = description;
            this.assignee = assignee;
        }
    }

    /**
     * 解析 JSON 计划为步骤列表；支持 [{"description":"..."}]、["a","b"] 与 {steps:[...]} 三种形态。
     * 模型输出常带 ```json 围栏或前后废话，因此先做文本提取再解析；任何无法解析的输入都退化为空计划，
     * 由 Coordinator 走单步直答，而不是把异常抛给主循环。
     */
    @SuppressWarnings("unchecked")
    public static List<Step> parse(String json) {
        List<Step> steps = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return steps;
        String candidate = extractJson(json);
        if (candidate == null) return steps;
        Object o;
        try {
            o = Json.parse(candidate);
        } catch (RuntimeException e) {
            return steps;
        }
        List<Object> arr;
        if (o instanceof List) {
            arr = (List<Object>) o;
        } else if (o instanceof Map) {
            Object s = ((Map<String, Object>) o).get("steps");
            arr = (s instanceof List) ? (List<Object>) s : new ArrayList<>();
        } else {
            return steps;
        }
        int i = 1;
        for (Object e : arr) {
            if (e instanceof String) {
                steps.add(new Step(i++, (String) e));
            } else if (e instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) e;
                String d = m.get("description") == null ? "" : String.valueOf(m.get("description"));
                Object a = m.get("assignee");
                steps.add(new Step(i++, d, a == null ? null : String.valueOf(a)));
            }
        }
        return steps;
    }

    /** 从任意模型输出中抠出第一段 JSON（先剥 ``` 围栏，再取最外层 [..] / {..}） */
    static String extractJson(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = (nl < 0) ? "" : t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
            t = t.trim();
        }
        int start = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '[' || c == '{') { start = i; break; }
        }
        if (start < 0) return null;
        char close = t.charAt(start) == '[' ? ']' : '}';
        int end = -1;
        for (int i = t.length() - 1; i > start; i--) {
            if (t.charAt(i) == close) { end = i; break; }
        }
        return end < 0 ? null : t.substring(start, end + 1);
    }
}
