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
}
