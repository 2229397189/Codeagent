package com.codeagent.context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 大结果离屏：完整结果写入磁盘，上下文仅留短预览 + 路径。
 * 原则（Codex / MiniCode 一致）：系统仍可访问完整数据 ≠ 完整数据塞进 prompt。
 */
public class ToolResultStorage {
    private final Path base;

    public ToolResultStorage(Path base) {
        this.base = base;
    }

    public String store(String content) {
        try {
            Files.createDirectories(base);
            String id = "r" + Math.abs(System.nanoTime() + content.hashCode());
            Path f = base.resolve(id + ".txt");
            Files.writeString(f, content, StandardCharsets.UTF_8);
            int previewLen = Math.min(content.length(), 200);
            return "[off-screen, full at " + f.toString() + "]\n" + content.substring(0, previewLen);
        } catch (Exception e) {
            return content; // 落盘失败则降级为原文，保证可用
        }
    }
}
