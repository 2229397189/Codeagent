package com.codeagent.eval;

import com.codeagent.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告：把若干 {@link EvalResult} 聚合成可量化指标。
 * 指标含义（对齐评估平台的"成功率 / 工具准确率 / 平均步数 / 平均 token"）：
 *  - successRate   ：通过任务数 / 总任务数
 *  - toolAccuracy  ：设了 expectTool 的任务中，工具真正被调用的比例
 *  - avgSteps      ：平均执行步数（成本/延迟代理指标）
 *  - avgPrompt/CompletionTokens：平均 token 用量（成本归因）
 */
public class EvalReport {
    public int total;
    public int passed;
    public double successRate;
    public double toolAccuracy;
    public double avgSteps;
    public double avgPromptTokens;
    public double avgCompletionTokens;
    public List<EvalResult> results = new ArrayList<>();

    public static EvalReport aggregate(List<EvalResult> rs) {
        EvalReport rep = new EvalReport();
        rep.results = rs;
        rep.total = rs.size();
        int p = 0, toolTasks = 0, toolHit = 0;
        long s = 0, pt = 0, ct = 0;
        for (EvalResult r : rs) {
            if (r.passed) p++;
            s += r.steps;
            pt += r.promptTokens;
            ct += r.completionTokens;
            if (r.toolChecked) {
                toolTasks++;
                if (r.expectedToolCalled) toolHit++;
            }
        }
        rep.passed = p;
        rep.successRate = rep.total == 0 ? 0.0 : (double) p / rep.total;
        rep.toolAccuracy = toolTasks == 0 ? 1.0 : (double) toolHit / toolTasks;
        rep.avgSteps = rep.total == 0 ? 0.0 : (double) s / rep.total;
        rep.avgPromptTokens = rep.total == 0 ? 0.0 : (double) pt / rep.total;
        rep.avgCompletionTokens = rep.total == 0 ? 0.0 : (double) ct / rep.total;
        return rep;
    }

    public String summary() {
        return String.format(
                "eval: %d/%d passed (%.0f%%) | tool-accuracy %.0f%% | avg steps %.1f | avg tokens p/c %.0f/%.0f",
                passed, total, successRate * 100, toolAccuracy * 100,
                avgSteps, avgPromptTokens, avgCompletionTokens);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("passed", passed);
        m.put("successRate", successRate);
        m.put("toolAccuracy", toolAccuracy);
        m.put("avgSteps", avgSteps);
        m.put("avgPromptTokens", avgPromptTokens);
        m.put("avgCompletionTokens", avgCompletionTokens);
        List<Object> rs = new ArrayList<>();
        for (EvalResult r : results) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id);
            rm.put("passed", r.passed);
            rm.put("status", r.status);
            rm.put("expectedToolCalled", r.expectedToolCalled);
            rm.put("toolChecked", r.toolChecked);
            rm.put("steps", r.steps);
            rm.put("promptTokens", r.promptTokens);
            rm.put("completionTokens", r.completionTokens);
            rm.put("reason", r.reason);
            rs.add(rm);
        }
        m.put("results", rs);
        return Json.write(m);
    }
}
