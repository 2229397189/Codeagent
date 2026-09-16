package com.codeagent.eval;

import com.codeagent.core.Json;

import java.util.Map;

/**
 * 一条评测任务：给 Agent 的指令 + 可断言的成功条件。
 * 成功条件全部可选；缺省时以"主循环抵达 FINAL 终态"作为通过（即 Agent 正常结束、未失控/未超限）。
 */
public class EvalTask {
    public String id;
    public String prompt;
    /** 期望 Agent 至少调用一次的工具名（如 run_command / edit_file / list_files） */
    public String expectTool;
    /** 期望最终回答包含的子串（大小写不敏感） */
    public String expectContains;
    public int maxSteps = 25;

    @SuppressWarnings("unchecked")
    public static EvalTask fromJsonLine(String line) {
        Object o = Json.parse(line);
        if (!(o instanceof Map)) throw new IllegalArgumentException("bad task line: " + line);
        Map<String, Object> m = (Map<String, Object>) o;
        EvalTask t = new EvalTask();
        t.id = str(m.get("id"), "task");
        t.prompt = str(m.get("prompt"), "");
        t.expectTool = str(m.get("expectTool"), null);
        t.expectContains = str(m.get("expectContains"), null);
        Object ms = m.get("maxSteps");
        if (ms instanceof Number) t.maxSteps = ((Number) ms).intValue();
        return t;
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }
}
