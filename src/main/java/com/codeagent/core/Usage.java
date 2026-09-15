package com.codeagent.core;

/**
 * Token 用量记账。事实来源 = provider 返回的 usage.prompt_tokens（最接近当前上下文真相）。
 * stale：auto-compact 之后旧的 usage 不再代表当前窗口，避免误用。
 */
public class Usage {
    public int promptTokens;
    public int completionTokens;
    /** auto-compact 后标记旧用量失效 */
    public boolean stale = false;
    /** 来源：usage（纯 provider）或 usage+est（最新追加消息为本地估算） */
    public String source = "usage";

    public Usage() {}

    public Usage(int prompt, int completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
    }

    public int total() {
        return promptTokens + completionTokens;
    }
}
