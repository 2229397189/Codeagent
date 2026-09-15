package com.codeagent.test;

import com.codeagent.core.*;
import com.codeagent.permission.PermissionManager;
import com.codeagent.tools.*;

import java.util.*;

/**
 * 主循环测试：断言“返回正确的值”，而非“不报错”。
 * 覆盖：工具驱动回填、终态判定、最终文本、工具输出值、对话结构、usage 记账、步数上限。
 */
public class AgentLoopTest {

    /** 测试用工具：回显入参，用于验证主循环的工具调用链路 */
    static class EchoTool implements Tool {
        @Override
        public ToolSpec spec() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> textProp = new LinkedHashMap<>();
            textProp.put("type", "string");
            textProp.put("description", "text to echo");
            props.put("text", textProp);
            params.put("properties", props);
            params.put("required", List.of("text"));
            return new ToolSpec("echo", "echo back the given text", params);
        }

        @Override
        public ToolResult execute(ToolCall call, PermissionManager perms) {
            String text = call.argStr("text");
            return ToolResult.ok("echo:" + text);
        }
    }

    public static int run() {
        int fails = 0;
        System.out.println("[AgentLoopTest]");

        // 构造脚本：第1次返回工具调用，第2次返回最终文本
        ChatResponse r1 = new ChatResponse();
        r1.content = null;
        r1.toolCalls.add(new ToolCall("c1", "echo", new LinkedHashMap<>(Map.of("text", "hello"))));
        r1.usage = new Usage(10, 5);

        ChatResponse r2 = new ChatResponse();
        r2.content = "done: hello";
        r2.usage = new Usage(20, 3);

        ToolRegistry reg = new ToolRegistry();
        reg.register(new EchoTool());

        AgentLoop loop = new AgentLoop();
        loop.model.chatModel = new MockChatModel(List.of(r1, r2));
        loop.maxSteps = 10;

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("please echo hello"));
        AgentLoop.AgentTurnResult res = loop.runTurn(messages, reg);

        fails += check("status == FINAL", res.status == AgentLoop.TurnStatus.FINAL);
        fails += check("final content == 'done: hello'", "done: hello".equals(lastAssistantContent(res.messages)));
        fails += check("tool output == 'echo:hello'", "echo:hello".equals(toolOutput(res.messages, "echo")));
        fails += check("message count == 4 (user/assistant(tool)/tool/assistant(final))", res.messages.size() == 4);
        fails += check("assistant carries echo toolCall (replay structure)",
                res.messages.stream().anyMatch(m -> m.role == Message.Role.assistant
                        && m.toolCalls != null && !m.toolCalls.isEmpty()
                        && "echo".equals(m.toolCalls.get(0).name)));
        fails += check("usage accounted (total > 0)", loop.model.lastUsage.total() > 0);

        // 步数上限：脚本永远返回工具调用 -> 应终止于 MAX_STEPS
        AgentLoop loop2 = new AgentLoop();
        loop2.model.chatModel = new MockChatModel(List.of(r1));
        loop2.maxSteps = 3;
        List<Message> m2 = new ArrayList<>();
        m2.add(Message.user("go"));
        AgentLoop.AgentTurnResult res2 = loop2.runTurn(m2, reg);
        fails += check("infinite tool loop -> MAX_STEPS", res2.status == AgentLoop.TurnStatus.MAX_STEPS);

        return fails;
    }

    private static String lastAssistantContent(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.role == Message.Role.assistant && (m.toolCalls == null || m.toolCalls.isEmpty())) return m.content;
        }
        return null;
    }

    private static String toolOutput(List<Message> msgs, String toolName) {
        for (Message m : msgs) {
            if (m.role == Message.Role.tool && toolName.equals(m.name)) return m.content;
        }
        return null;
    }

    static int check(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS " + name);
            return 0;
        }
        System.out.println("  FAIL " + name);
        return 1;
    }
}
