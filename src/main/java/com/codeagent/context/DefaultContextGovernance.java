package com.codeagent.context;

import com.codeagent.core.Message;
import com.codeagent.core.Usage;

import java.util.List;

/**
 * 默认上下文治理（M6 接线）：把 M3 的四层策略编排成一条 apply() 管道。
 *  - 每轮：micro-compact（确定性、无损裁剪）
 *  - 预算达到 auto 水位：auto-compact（有损压缩为摘要 + 保留最近用户意图）
 * 事实来源仍是 provider usage（ContextBudget），不做本地字符估算。
 */
public class DefaultContextGovernance implements ContextGovernance {

    private final ContextBudget budget;
    private final MicroCompact micro;
    private final AutoCompact auto;
    private final AutoCompact.Summarizer summarizer;

    public DefaultContextGovernance(ContextBudget budget, MicroCompact micro, AutoCompact.Summarizer summarizer) {
        this.budget = budget;
        this.micro = micro;
        this.auto = new AutoCompact();
        this.summarizer = summarizer;
    }

    public void observe(Usage u) {
        budget.observe(u);
    }

    public ContextBudget budget() {
        return budget;
    }

    @Override
    public List<Message> apply(List<Message> messages) {
        List<Message> out = micro.apply(messages);
        ContextBudget.Phase p = budget.phase();
        if (p == ContextBudget.Phase.AUTO || p == ContextBudget.Phase.HARD) {
            out = auto.apply(out, summarizer);
        }
        return out;
    }

    /** 供 /compact 使用：强制产出压缩后的视图（不改动 append-only 日志） */
    public List<Message> forceCompact(List<Message> messages) {
        return auto.apply(messages, summarizer);
    }

    /**
     * 抽取式摘要：不额外调用模型，避免“治理本身”产生成本与延迟（对齐 Codex 的 compact 不该烧 token）。
     */
    public static AutoCompact.Summarizer extractiveSummarizer() {
        return msgs -> {
            int users = 0, tools = 0;
            String lastUser = null;
            for (Message m : msgs) {
                if (m.role == Message.Role.user) {
                    users++;
                    lastUser = m.content;
                } else if (m.role == Message.Role.tool) {
                    tools++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(msgs.size()).append(" messages (").append(users)
                    .append(" user, ").append(tools).append(" tool results)");
            if (lastUser != null && !lastUser.isBlank()) sb.append("; latest task: ").append(lastUser);
            return sb.toString();
        };
    }
}
