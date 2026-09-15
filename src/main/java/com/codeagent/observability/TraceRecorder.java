package com.codeagent.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * 审计追踪（企业版补齐）：把每回合的状态、步数、token 用量与工具调用落成 JSONL。
 * 与 SessionStore 的区别：
 *  - SessionStore = 对话内容（给 resume 用）
 *  - TraceRecorder = 运行事实（给审计/排障/成本归因用），同样 append-only
 */
public class TraceRecorder {

    private final Path file;

    public TraceRecorder(Path file) {
        this.file = file;
    }

    /** 记录一个回合的完成事实 */
    public void turn(String status, int steps, long promptTokens, long completionTokens, String summary) {
        String detail = "status=" + status
                + " steps=" + steps
                + " prompt_tokens=" + promptTokens
                + " completion_tokens=" + completionTokens
                + " summary=" + (summary == null ? "" : summary.replace("\n", " "));
        write("turn", detail);
    }

    /** 记录一条自定义事件（工具调用、权限决策等） */
    public void event(String type, String detail) {
        write(type, detail);
    }

    private void write(String type, String detail) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String line = "{\"ts\":" + json(Instant.now().toString())
                    + ",\"type\":" + json(type)
                    + ",\"detail\":" + json(detail == null ? "" : detail)
                    + "}\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
            // 追踪失败不应影响主流程
        }
    }

    public long size() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public List<String> lines() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path file() {
        return file;
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);
            }
        }
        return b.append("\"").toString();
    }
}
