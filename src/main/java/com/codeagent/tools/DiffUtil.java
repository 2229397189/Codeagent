package com.codeagent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 极简 unified diff 生成与应用（对齐 MiniCode buildUnifiedDiff / review-before-write）。
 * 仅支持整行级别，足够编码 Agent 的 edit/patch 场景；不追求完整 patch 语义。
 */
public final class DiffUtil {

    private DiffUtil() {}

    public static String unifiedDiff(String name, String before, String after) {
        String[] b = before.split("\n", -1);
        String[] a = after.split("\n", -1);
        int start = 0;
        while (start < b.length && start < a.length && b[start].equals(a[start])) start++;
        int endB = b.length, endA = a.length;
        while (endB > start && endA > start && b[endB - 1].equals(a[endA - 1])) {
            endB--; endA--;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(name).append('\n').append("+++ b/").append(name).append('\n');
        sb.append("@@ -").append(start + 1).append(',').append(endB - start)
                .append(" +").append(start + 1).append(',').append(endA - start).append(" @@\n");
        for (int i = start; i < endB; i++) sb.append('-').append(b[i]).append('\n');
        for (int i = start; i < endA; i++) sb.append('+').append(a[i]).append('\n');
        return sb.toString();
    }

    public static String applyUnifiedDiff(String content, String diff) {
        String[] lines = diff.split("\n", -1);
        List<String> result = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("@@")) i++;
        while (i < lines.length && lines[i].startsWith("@@")) {
            i++; // skip hunk header
            List<String> hunkOld = new ArrayList<>();
            List<String> hunkNew = new ArrayList<>();
            while (i < lines.length && !lines[i].startsWith("@@")
                    && !lines[i].startsWith("---") && !lines[i].startsWith("+++")) {
                String l = lines[i++];
                if (l.startsWith("-")) hunkOld.add(l.substring(1));
                else if (l.startsWith("+")) hunkNew.add(l.substring(1));
                else if (l.startsWith(" ")) {
                    hunkOld.add(l.substring(1));
                    hunkNew.add(l.substring(1));
                }
            }
            int pos = indexOfSublist(result, hunkOld);
            if (pos < 0) throw new IllegalStateException("patch does not apply at expected location");
            List<String> replaced = new ArrayList<>(result.subList(0, pos));
            replaced.addAll(hunkNew);
            replaced.addAll(result.subList(pos + hunkOld.size(), result.size()));
            result = replaced;
        }
        return String.join("\n", result);
    }

    private static int indexOfSublist(List<String> src, List<String> sub) {
        if (sub.isEmpty()) return 0;
        for (int i = 0; i + sub.size() <= src.size(); i++) {
            boolean match = true;
            for (int j = 0; j < sub.size(); j++) {
                if (!src.get(i + j).equals(sub.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
