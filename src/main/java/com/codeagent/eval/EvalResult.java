package com.codeagent.eval;

/** 单条任务的评测结果 */
public class EvalResult {
    public String id;
    public boolean passed;
    /** AgentLoop.TurnStatus 名称（FINAL / FAILED / MAX_STEPS / ...） */
    public String status;
    /** 该任务是否设了 expectTool（仅此时 expectedToolCalled 才有意义） */
    public boolean toolChecked;
    /** 是否调用了期望的工具 */
    public boolean expectedToolCalled;
    public int steps;
    public int promptTokens;
    public int completionTokens;
    public String finalText;
    public String reason;

    public static EvalResult fail(String id, String reason) {
        EvalResult r = new EvalResult();
        r.id = id;
        r.passed = false;
        r.reason = reason;
        return r;
    }
}
