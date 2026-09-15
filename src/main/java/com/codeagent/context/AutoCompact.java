package com.codeagent.context;

import com.codeagent.core.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * auto-compact：当预算达到 90% 水位时触发（有损）。调用 Summarizer 把历史压缩为单条摘要，
 * 保留最近用户消息，避免丢失"当前任务意图"。真实运行接线到 ChatModel；测试可注入桩。
 */
public class AutoCompact {

    public interface Summarizer {
        String summarize(List<Message> messages);
    }

    public List<Message> apply(List<Message> messages, Summarizer summarizer) {
        String summary = summarizer.summarize(messages);
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role == Message.Role.user) lastUser = i;
        }
        List<Message> out = new ArrayList<>();
        out.add(Message.assistant("[summary] " + summary));
        if (lastUser >= 0) out.add(messages.get(lastUser));
        return out;
    }
}
