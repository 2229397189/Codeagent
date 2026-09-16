package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

/**
 * 统一工具协议。所有工具（读/搜/列/改/跑）实现该接口：
 *  - spec() 声明给模型的 schema（名称/描述/入参 JSON-Schema）
 *  - execute() 在权限校验后执行，返回结构化 ToolResult { output, isError, awaitUser, stop, fatal }
 */
public interface Tool {
    ToolSpec spec();

    ToolResult execute(ToolCall call, PermissionManager perms);

    /**
     * 可选：写前预览（dry-run）。返回将产生的 diff / 结果预览，但**不落盘**。
     * 默认不支持（返回 null）；由写类工具（edit_file / patch）覆写。
     * review-before-write 依赖它：权限为 ASK 时先预览给人看，批准后才真正 execute。
     */
    default ToolResult preview(ToolCall call, PermissionManager perms) {
        return null;
    }
}
