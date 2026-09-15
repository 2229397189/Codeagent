package com.codeagent.permission;

import com.codeagent.core.ToolCall;

/**
 * 权限边界接口（M4 实现：路径沙箱 + 危险命令拦截 + review-before-write + 审批持久化）。
 * 此处先以接口 + 默认 ALLOW_ALL 解耦主循环，M4 替换为真实实现。
 */
public interface PermissionManager {
    enum Decision { ALLOW, DENY, ASK }

    Decision decide(ToolCall call);

    PermissionManager ALLOW_ALL = call -> Decision.ALLOW;
}
