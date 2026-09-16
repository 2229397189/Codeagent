package com.codeagent.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 零依赖分词：混合中日英文本。
 *  - ASCII 字母/数字连续成词（小写化）；
 *  - CJK 字符（含汉字/假名/谚文）拆成单字 unigram，并对连续 CJK 串生成 bigram；
 *  - 其他符号作为分隔。
 * 不引入任何 NLP 依赖，足以支撑 lexical 检索（BM25）的召回。
 */
public final class Tokenizer {
    private Tokenizer() {}

    public static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)      // 平假名/片假名
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // 基本汉字
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF); // 兼容汉字
    }

    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        StringBuilder word = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                if (word.length() > 0) { out.add(word.toString().toLowerCase(Locale.ROOT)); word.setLength(0); }
                String ch = new String(Character.toChars(cp));
                out.add(ch);
                cjkRun.append(ch);
            } else if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else {
                if (word.length() > 0) { out.add(word.toString().toLowerCase(Locale.ROOT)); word.setLength(0); }
                if (cjkRun.length() >= 2) addBigrams(out, cjkRun.toString());
                cjkRun.setLength(0);
            }
            i += Character.charCount(cp);
        }
        if (word.length() > 0) out.add(word.toString().toLowerCase(Locale.ROOT));
        if (cjkRun.length() >= 2) addBigrams(out, cjkRun.toString());
        return out;
    }

    private static void addBigrams(List<String> out, String run) {
        for (int k = 0; k + 1 < run.length(); k++) {
            out.add(run.substring(k, k + 2));
        }
    }
}
