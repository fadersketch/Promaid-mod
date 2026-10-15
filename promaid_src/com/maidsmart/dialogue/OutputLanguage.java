package com.maidsmart.dialogue;

import com.maidsmart.config.MaidSmartConfig;

/**
 * 对话输出语言（配置 {@code dialogue.outputLanguage}）的公共判定与措辞。
 *
 * <p>两处共用同一份口径，避免"提示词说英文、检测按中文"这种两头对不上：
 * <ul>
 *   <li>{@link #directive()}：追加到 system 提示词的硬指令（{@code PromaidPromptMixin} 注入）；</li>
 *   <li>{@link #matches(String)}：LLM 回复落地时的语言检测（{@code MaidChatLanguageMixin}），
 *       不符就内嵌翻译——**这条对任何目标语言都生效**（旧版只查中文目标）。</li>
 * </ul>
 *
 * <p>为什么要有硬指令：光靠 TLM 的 {@code ${chat_language}} 提示词变量，模型在
 * "人设/记忆/上下文全是中文"时仍会整段飘回中文。所以提示词层加一段明确要求，
 * 输出层再做一次检测兜底。
 */
public final class OutputLanguage {

    /** 提示词段落标识（幂等：已注入不重复追加） */
    public static final String MARKER = "## Reply Language (Promaid)";

    private OutputLanguage() {
    }

    /** 当前设定的输出语言代码；未设置 = 强制中文（与强制逻辑一致）。 */
    public static String current() {
        try {
            String v = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
            if (v != null && !v.isBlank()) {
                return v.trim().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("OutputLanguage", ignored);
        }
        return "zh_cn";
    }

    /** 语言代码 → 英文名（写进提示词，模型对英文语言名最不容易误解）。 */
    public static String displayName(String code) {
        String c = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("zh")) {
            return "Simplified Chinese";
        }
        if (c.startsWith("en")) {
            return "English";
        }
        if (c.startsWith("ja")) {
            return "Japanese";
        }
        if (c.startsWith("ko")) {
            return "Korean";
        }
        if (c.startsWith("ru")) {
            return "Russian";
        }
        return c;
    }

    /** 语言代码 → 该语言自己的名字（写进提示词括号里，模型更不易跑偏）。 */
    public static String nativeName(String code) {
        String c = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("zh")) {
            return "简体中文";
        }
        if (c.startsWith("ja")) {
            return "日本語";
        }
        if (c.startsWith("ko")) {
            return "한국어";
        }
        if (c.startsWith("ru")) {
            return "Русский";
        }
        return ""; // en 及未知：英文名已经就是本语言写法
    }

    /** system 提示词里那段"只用设定语言回复"的硬指令。 */
    public static String directive() {
        String code = current();
        String name = displayName(code);
        String native_ = nativeName(code);
        StringBuilder sb = new StringBuilder(256);
        sb.append('\n').append(MARKER).append('\n');
        sb.append("Write every chat reply to the master in ").append(name);
        if (!native_.isEmpty()) {
            sb.append(" (").append(native_).append(')');
        }
        sb.append(" only.\n");
        sb.append("Never switch to another language mid-conversation — not because the master writes in a "
                + "different language, not because the persona, memory or context notes are written in "
                + "another language, not for emphasis or quotes.\n");
        sb.append("This applies to your spoken chat text; structured output (for example a JSON blueprint) "
                + "still follows its own task instructions.\n");
        return sb.toString();
    }

    /** 这段文字符合设定的输出语言吗？不符 → 调用方去内嵌翻译。 */
    public static boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return matches(text, current());
    }

    /** 语言判定（字符族占比，不引外部库）：目标语言不同，判据不同。 */
    public static boolean matches(String text, String code) {
        String c = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        int han = 0;
        int kana = 0;
        int hangul = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                han++;
                letters++;
            } else if (ch >= '\u3040' && ch <= '\u30ff') {
                kana++;
                letters++;
            } else if (ch >= '\uac00' && ch <= '\ud7a3') {
                hangul++;
                letters++;
            } else if (Character.isLetter(ch)) {
                letters++;
            }
        }
        if (letters == 0) {
            return true; // 纯符号 / 数字 / 表情：不判定
        }
        if (c.startsWith("zh")) {
            return han >= letters * 0.4 && kana < letters * 0.1;
        }
        if (c.startsWith("ja")) {
            return kana > 0 || han >= letters * 0.3; // 日文允许汉字，但不能整段英文
        }
        if (c.startsWith("ko")) {
            return hangul > 0 || han >= letters * 0.3;
        }
        return han == 0 && kana == 0; // 英文 / 俄文等：出现 CJK 就算不符
    }
}
