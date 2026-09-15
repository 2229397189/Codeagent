package com.codeagent.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具公共能力：把工具入参路径解析到工作区内绝对路径，拒绝逃逸工作区（基础沙箱）。
 * 完整的权限/危险命令拦截在 M4 的 PermissionManager 中实现。
 */
public final class ToolSupport {

    private ToolSupport() {}

    public static Path resolve(String workspace, String p) {
        Path ws = Paths.get(workspace).toAbsolutePath().normalize();
        Path target = ws.resolve(p == null ? "" : p).normalize();
        if (!target.startsWith(ws)) {
            throw new IllegalArgumentException("path escapes workspace: " + p);
        }
        return target;
    }
}
