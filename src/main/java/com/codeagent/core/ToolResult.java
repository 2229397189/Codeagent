package com.codeagent.core;

/**
 * 工具执行结果。对齐 MiniCode ToolResult 的 { output, isError, awaitUser, stop, fatal }。
 * 这些标志驱动主循环的终止分支。
 */
public class ToolResult {
    /** 工具输出（文本/预览）。大结果应只放预览，完整数据离屏 */
    public String output = "";
    /** 是否执行出错 */
    public boolean isError = false;
    /** 需要用户介入 -> finish(AWAITING_USER) */
    public boolean awaitUser = false;
    /** 受控停止 -> finish(CONTROLLED_STOP) */
    public boolean stop = false;
    /** 致命错误 -> finish(FAILED) */
    public boolean fatal = false;

    public ToolResult() {}

    public static ToolResult ok(String out) {
        ToolResult r = new ToolResult();
        r.output = out == null ? "" : out;
        return r;
    }

    public static ToolResult error(String e) {
        ToolResult r = new ToolResult();
        r.output = e == null ? "" : e;
        r.isError = true;
        return r;
    }
}
