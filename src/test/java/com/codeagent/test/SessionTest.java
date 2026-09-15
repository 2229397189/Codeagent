package com.codeagent.test;

import com.codeagent.core.Message;
import com.codeagent.core.ToolCall;
import com.codeagent.session.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 会话测试（M5）：断言“返回正确的值”，而非仅不报错。
 * 覆盖：JSONL 追加、resume 重放顺序与内容、tool_calls 往返、rename 保留历史、fork 隔离。
 */
public class SessionTest {

    public static int run() {
        int fails = 0;
        System.out.println("[SessionTest]");

        try {
            Path dir = Files.createTempDirectory("codeagent-session");

            Session s = Session.open(dir, "main");
            s.append(Message.user("hello"));
            Message a = Message.assistant("");
            a.toolCalls.add(new ToolCall("t1", "echo", Map.of("text", "hi")));
            s.append(a);
            s.append(Message.tool("t1", "read_file", "data"));

            // append 计数
            fails += check("session size == 3", s.size() == 3);

            // resume：顺序与内容正确
            List<Message> msgs = s.messages();
            fails += check("resume returns 3 messages", msgs.size() == 3);
            fails += check("resume preserves user message order/content",
                    msgs.get(0).role == Message.Role.user && "hello".equals(msgs.get(0).content));
            fails += check("resume preserves tool result content",
                    msgs.get(2).role == Message.Role.tool && "data".equals(msgs.get(2).content)
                            && "t1".equals(msgs.get(2).toolCallId) && "read_file".equals(msgs.get(2).name));

            // tool_calls 往返（JSON 序列化/反序列化）
            Message ra = msgs.get(1);
            boolean tcOk = ra.role == Message.Role.assistant && ra.toolCalls != null
                    && ra.toolCalls.size() == 1
                    && "echo".equals(ra.toolCalls.get(0).name)
                    && "hi".equals(ra.toolCalls.get(0).argStr("text"));
            fails += check("tool_calls survive JSONL round-trip", tcOk);

            // rename：历史保留 + 文件搬迁
            s.rename("renamed");
            fails += check("rename updates session name", "renamed".equals(s.name()));
            fails += check("rename creates new log file", Files.exists(dir.resolve("renamed.jsonl")));
            fails += check("rename removes old log file", !Files.exists(dir.resolve("main.jsonl")));
            fails += check("rename preserves history", s.messages().size() == 3);

            // fork：复制历史 + 此后隔离
            Session f = s.fork("branch");
            fails += check("fork copies history", f.messages().size() == 3);
            fails += check("fork has its own name", "branch".equals(f.name()));

            f.append(Message.user("diverge"));
            fails += check("fork grows independently", f.size() == 4);
            fails += check("parent is isolated from fork", s.size() == 3);

        } catch (Exception ex) {
            fails++;
            System.out.println("  FAIL exception: " + ex);
            ex.printStackTrace();
        }
        return fails;
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
