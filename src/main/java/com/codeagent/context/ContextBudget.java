package com.codeagent.context;

import com.codeagent.core.Usage;

/**
 * 上下文预算记账。事实来源 = provider 返回的 usage.prompt_tokens（最接近当前窗口真相）。
 * 阈值对齐 Codex：warn 70% / auto 90% / hard 100%。auto-compact 后旧 usage 标记 stale。
 */
public class ContextBudget {
    public int limit = 128_000;
    public int warnPct = 70;
    public int autoPct = 90;

    public int promptTokens = 0;
    public int completionTokens = 0;
    public boolean stale = false;

    public void observe(Usage u) {
        if (u != null) {
            promptTokens = u.promptTokens;
            completionTokens = u.completionTokens;
            stale = false;
        }
    }

    public int total() {
        return promptTokens + completionTokens;
    }

    public double ratio() {
        return limit <= 0 ? 0 : total() * 100.0 / limit;
    }

    public enum Phase { OK, WARN, AUTO, HARD }

    public Phase phase() {
        double r = ratio();
        if (r >= 100) return Phase.HARD;
        if (r >= autoPct) return Phase.AUTO;
        if (r >= warnPct) return Phase.WARN;
        return Phase.OK;
    }
}
