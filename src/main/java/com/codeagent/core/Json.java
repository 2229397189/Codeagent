package com.codeagent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖的最小 JSON 解析/序列化。仅覆盖本项目所需子集：
 * object / array / string / number / true / false / null。
 * 用于 OpenAI 兼容请求体构造与响应解析、会话 JSONL 持久化。
 */
public final class Json {

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        return p.val();
    }

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        w(b, o);
        return b.toString();
    }

    // ---------- parser ----------
    static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object val() {
            if (i >= s.length()) throw new RuntimeException("JSON: unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': case 'f': return bool();
                case 'n': return nul();
                default: return num();
            }
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (s.charAt(i) != '"') throw new RuntimeException("JSON: expected key at " + i);
                String k = str();
                ws();
                if (s.charAt(i) != ':') throw new RuntimeException("JSON: expected : at " + i);
                i++;
                ws();
                m.put(k, val());
                ws();
                if (i >= s.length()) throw new RuntimeException("JSON: unterminated object");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw new RuntimeException("JSON: expected , or } at " + i);
            }
            return m;
        }

        List<Object> arr() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(val());
                ws();
                if (i >= s.length()) throw new RuntimeException("JSON: unterminated array");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw new RuntimeException("JSON: expected , or ] at " + i);
            }
            return a;
        }

        String str() {
            StringBuilder b = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            b.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
            throw new RuntimeException("JSON: unterminated string");
        }

        Object num() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String n = s.substring(start, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        Object bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new RuntimeException("JSON: invalid literal at " + i);
        }

        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new RuntimeException("JSON: invalid literal at " + i);
        }
    }

    // ---------- writer ----------
    @SuppressWarnings("unchecked")
    static void w(StringBuilder b, Object o) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { sq(b, (String) o); return; }
        if (o instanceof Boolean) { b.append(o.toString()); return; }
        if (o instanceof Number) { b.append(o.toString()); return; }
        if (o instanceof Map) {
            b.append('{');
            boolean f = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!f) b.append(',');
                f = false;
                sq(b, e.getKey());
                b.append(':');
                w(b, e.getValue());
            }
            b.append('}');
            return;
        }
        if (o instanceof List) {
            b.append('[');
            boolean f = true;
            for (Object x : (List<Object>) o) {
                if (!f) b.append(',');
                f = false;
                w(b, x);
            }
            b.append(']');
            return;
        }
        sq(b, o.toString());
    }

    static void sq(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default: b.append(c);
            }
        }
        b.append('"');
    }
}
