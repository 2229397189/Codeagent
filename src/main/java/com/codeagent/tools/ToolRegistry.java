package com.codeagent.tools;

import com.codeagent.core.ToolCall;
import com.codeagent.core.ToolResult;
import com.codeagent.core.ToolSpec;
import com.codeagent.permission.PermissionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一工具注册与执行入口（对齐 MiniCode ToolRegistry / Spring AI ToolCallback）。
 * 模型只看到 spec() 列表；执行统一走 execute()，由具体工具在内部做权限校验。
 */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool t) {
        tools.put(t.spec().name, t);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public List<ToolSpec> specs() {
        List<ToolSpec> s = new ArrayList<>();
        for (Tool t : tools.values()) s.add(t.spec());
        return s;
    }

    public ToolResult execute(ToolCall call, PermissionManager perms) {
        Tool t = tools.get(call.name);
        if (t == null) return ToolResult.error("unknown tool: " + call.name);
        // 权限门：DENY 直接拦截；ASK 需要审批（未审批前不允许落盘）
        PermissionManager.Decision d = perms.decide(call);
        if (d == PermissionManager.Decision.DENY) {
            return ToolResult.error("permission denied: " + call.name);
        }
        if (d == PermissionManager.Decision.ASK) {
            return ToolResult.error("approval required: " + call.name + " (approve first)");
        }
        return t.execute(call, perms);
    }
}
