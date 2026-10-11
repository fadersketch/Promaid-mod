package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆检索分词器（移植自 maidsoulcore v3 {@code MemoryV3SparseIndex.tokenize/flushHan/flushWord}）。
 *
 * <p>BM25 需要一个稳定的词项集合：只用「单字 + 相邻二元」的 n-gram 硬匹配时，拉丁词（"diamond_sword"、
 * "iron"）会被切成单字母、噪声大。这里做 CJK 感知切分：
 * <ul>
 *   <li><b>汉字</b>：切成单字（{@code h:红}）+ 相邻汉字二元（{@code h:红茶}）——中文没有空格，二元能在
 *       不引第三方分词器的前提下把词义带出来；单字兜底召回短查询。</li>
 *   <li><b>拉丁/数字</b>：连续字母数字合成一个词（{@code w:iron}），下划线视为词内字符
 *       （{@code w:diamond_sword} 整体保留），大小写无关。</li>
 *   <li>其余字符（标点/空白）只作分隔。</li>
 * </ul>
 *
 * <p>纯工具类、无状态、可被 BM25 / 关系图 / 时间查询共用。两树逐字相同。
 */
public final class AiMemoryTokens {

    private AiMemoryTokens() {
    }

    /** CJK 感知分词：返回小写 token 列表（含重复，供词频统计用）。 */
    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        String norm = AiMemoryModels.normalize(s); // trim + 折叠空白 + 小写
        StringBuilder word = new StringBuilder();
        char prevHan = 0;
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (isHan(c)) {
                flushWord(out, word);
                out.add("h:" + c);
                if (prevHan != 0) {
                    out.add("h:" + prevHan + c); // 汉字二元
                }
                prevHan = c;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
                prevHan = 0;
            } else {
                flushWord(out, word);
                prevHan = 0;
            }
        }
        flushWord(out, word);
        return out;
    }

    /** 查询词项：去重后的 token（BM25 的 query term 只按有无加权，重复无意义）。 */
    public static Set<String> queryTokens(String query) {
        return new LinkedHashSet<>(tokenize(query));
    }

    /** 是否 CJK 汉字（含扩展 A 区）。 */
    static boolean isHan(char c) {
        return (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf);
    }

    private static void flushWord(List<String> out, StringBuilder word) {
        if (word.length() > 0) {
            out.add("w:" + word);
            word.setLength(0);
        }
    }
}
