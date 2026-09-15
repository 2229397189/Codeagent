package com.codeagent.permission;

import com.codeagent.core.Json;
import com.codeagent.core.ToolCall;
import com.codeagent.tools.ToolSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 真实权限边界（M4）：路径沙箱 + 危险命令拦截 + 写前审批(review-before-write) + 审批持久化。
 * 对齐 OpenHands 的权限分级、Cline 的 read-only / write 分类、Claude Code 的 approval 持久化。
 *
 * 决策矩阵（decide）：
 *  - run_command        ：命中危险模式 -> DENY；否则 ALLOW
 *  - 只读路径工具        ：逃逸工作区 -> DENY；否则 ALLOW
 *  - 写路径工具          ：逃逸 -> DENY；未审批 -> ASK；已审批 -> ALLOW
 *  - 其它工具            ：ALLOW（已知风险类别已单独处理）
 */
public class DefaultPermissionManager implements PermissionManager {

    private final Path workspace;
    private final Path approvalFile; // 可为 null（仅内存，不持久化）
    private final Set<String> approvals = new LinkedHashSet<>();

    /** 显式开启后跳过写前审批（对应 --accept-edits）；默认 false，改文件必须审批 */
    public boolean acceptEdits = false;

    private static final Set<String> PATH_TOOLS = Set.of("read_file", "list_files", "edit_file", "patch");
    private static final Set<String> WRITE_TOOLS = Set.of("edit_file", "patch");

    // 危险命令正则（忽略大小写）。fork bomb 单独做字面量匹配，避免正则元字符歧义。
    private static final String[] DANGEROUS_REGEX = {
            "\\brm\\s+(-rf|-fr|--recursive|--force)\\b",
            "\\brm\\s+-r\\b",
            "\\bgit\\s+reset\\s+--hard\\b",
            "\\bgit\\s+clean\\s+-[a-z]*f\\b",
            "\\bsudo\\b",
            "\\bmkfs\\b",
            "\\bdd\\b.*\\bif=",
            "\\b(shutdown|halt|reboot|poweroff)\\b"
    };

    public DefaultPermissionManager(String workspace) {
        this(Path.of(workspace), null);
    }

    public DefaultPermissionManager(Path workspace, Path approvalFile) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.approvalFile = approvalFile;
        loadApprovals();
    }

    private void loadApprovals() {
        if (approvalFile == null || !Files.exists(approvalFile)) return;
        try {
            String txt = Files.readString(approvalFile, StandardCharsets.UTF_8);
            Object o = Json.parse(txt);
            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) approvals.add(String.valueOf(e.getKey()));
                }
            }
        } catch (Exception ignore) {
            // 损坏的审批文件不影响启动，视为无审批
        }
    }

    private void persist() {
        if (approvalFile == null) return;
        try {
            if (approvalFile.getParent() != null) Files.createDirectories(approvalFile.getParent());
            Map<String, Boolean> m = new LinkedHashMap<>();
            for (String a : approvals) m.put(a, true);
            Files.writeString(approvalFile, Json.write(m), StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 持久化失败降级为内存态，不阻断 Agent 运行
        }
    }

    /** 危险命令分类（静态，便于单测与 CLI 复用） */
    public static boolean isDangerous(String command) {
        if (command == null) return false;
        String c = command.toLowerCase();
        if (c.contains(":(){:|:&};:")) return true;
        for (String p : DANGEROUS_REGEX) {
            if (Pattern.matches(".*" + p + ".*", c)) return true;
        }
        return false;
    }

    /** 审批键 = 工具名 + 解析后的绝对路径（同工具同目标只审一次） */
    private String keyFor(String toolName, String path) {
        String resolved;
        try {
            resolved = ToolSupport.resolve(workspace.toString(), path == null ? "" : path).toString();
        } catch (IllegalArgumentException e) {
            resolved = (path == null ? "" : path);
        }
        return toolName + ":" + resolved;
    }

    private String approvalKey(ToolCall call) {
        return keyFor(call.name, call.argStr("path"));
    }

    @Override
    public Decision decide(ToolCall call) {
        if (call == null || call.name == null) return Decision.DENY;

        // 1) 命令工具：危险分类拦截
        if ("run_command".equals(call.name)) {
            return isDangerous(call.argStr("command")) ? Decision.DENY : Decision.ALLOW;
        }

        // 2) 路径工具：沙箱校验
        if (PATH_TOOLS.contains(call.name)) {
            String p = call.argStr("path");
            try {
                ToolSupport.resolve(workspace.toString(), p == null ? "" : p);
            } catch (IllegalArgumentException e) {
                return Decision.DENY; // 逃逸工作区
            }
            if (WRITE_TOOLS.contains(call.name)) {
                if (acceptEdits) return Decision.ALLOW; // --accept-edits：跳过逐次审批
                return approvals.contains(approvalKey(call)) ? Decision.ALLOW : Decision.ASK;
            }
            return Decision.ALLOW; // 只读路径工具
        }

        // 3) 其它工具：默认放行（已知风险类别已单独处理）
        return Decision.ALLOW;
    }

    /** 记录对某工具+目标的审批，并持久化 */
    public void approve(ToolCall call) {
        approvals.add(approvalKey(call));
        persist();
    }

    /** 按工具名 + 目标路径审批（供 CLI /approve 使用），并持久化 */
    public void approve(String toolName, String path) {
        approvals.add(keyFor(toolName, path));
        persist();
    }

    public boolean isApproved(ToolCall call) {
        return approvals.contains(approvalKey(call));
    }
}
