package com.maidsmart.i18n;

import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Promaid 文本本地化：让本模组全部自制文本跟随**玩家自己的语言设置**切换——
 * 玩家语言不是中文时，所有本模组文本显示英文。
 * <h3>机制</h3>
 * 英文词条放在 jar 内 {@code assets/promaid/i18n/en_us.json}（{@code {"中文原文": "English"}}），
 * classloader 读取，不落盘、不依赖原版 lang 加载器。在**显示出口**（GUI 绘制、聊天栏）把
 * 整串中文换成英文：
 * <ul>
 *   <li>只做**整串精确**匹配（匹配前剥掉 {@code §x} 颜色码与首尾空白）——不碰玩家名、
 *       物品名、坐标、数字等动态内容；</li>
 *   <li>当前语言是中文（zh*）→ 直接返回原文，中文玩家体验一个字节不变；</li>
 *   <li>用纯 map 查找，**绝不触发** {@code TranslatableContents} 的格式串解析——所以原文里
 *       带 {@code %s}/{@code %d}/裸 {@code %} 也不会抛异常（比走 lang + translatable 安全）；</li>
 *   <li>表里没命中 → 返回原文。所以未收录的长尾保持中文，不会变成键名或空白。</li> </ul> <h3>为什么安全</h3> 在英文/俄文等非中文客户端上，原版与其它模组的文本**早已是**该语言（原版 {@code LanguageManager} 以 {@code en_us} 兜底），屏幕上剩下的中文基本只有本模组自己的文本——键几乎不可能误撞。 且本类是**纯显示层**：不改服务端任何状态（NBT / 日志 / 女仆记忆 / LLM 提示词一字不动）， 服务端照旧发中文，各客户端各自按自己的语言显示。 <p>女仆气泡无需经过本类：TLM 自己用 {@code Component.translatable(文本)} 建气泡 （字节码实证），语言表里有键即自动按各玩家语言解析。
 */
public final class PromaidI18n {

    /** 英文词条表（jar 内） */
    private static final String RES_EN = "/assets/promaid/i18n/en_us.json";

    /** §x 颜色/格式码 */
    private static final Pattern FORMATTING = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    /** 含 CJK 的快速预筛（纯 ASCII 串直接跳过，省查找） */
    private static final Pattern CJK = Pattern.compile("[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]");

    /**
     * 纯汉字（不含全角标点）——{@link #compose} 那道"还剩中文就放弃"的守卫专用。
     *
     * <p>为什么不能用 {@link #CJK}：译文里**保留全角标点是正常的**（{@code "："}、{@code 「」}、
     * {@code 【】} 在本模组的英文译文里到处都是，例如 {@code "§e【Example】…："}）。若把全角标点
     * 也算作"没翻干净"，这一类句子会被守卫整串退回中文——等于白翻。守卫要问的是
     * "还有没有一个汉字没被覆盖"，所以这里只认汉字。
     */
    private static final Pattern HAN = Pattern.compile("[\u4e00-\u9fff\u3400-\u4dbf]");

    private static volatile Map<String, String> TABLE = null;
    private static volatile boolean LOADED = false;

    /** 首字符 → 以它开头的词条（长的在前）。给"拼接串"匹配用（见 {@link #compose}）。 */
    private static volatile Map<Character, String[]> INDEX = null;

    /** 拼接串的翻译缓存（每帧都要画，不能每帧重扫一遍）。 */
    private static final Map<String, String> COMPOSED =
            Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 4096;
                }
            });

    /** 拼接串匹配的输入长度上限：超过这个长度的串只做整串查表，不做切分（防病态耗时）。 */
    private static final int COMPOSE_MAX_LEN = 4000;

    private PromaidI18n() {
    }

    private static Map<String, String> table() {
        if (LOADED) {
            return TABLE;
        }
        synchronized (PromaidI18n.class) {
            if (LOADED) {
                return TABLE;
            }
            Map<String, String> m = Collections.emptyMap();
            try (InputStream in = PromaidI18n.class.getResourceAsStream(RES_EN)) {
                if (in != null) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser
                            .parseString(json).getAsJsonObject();
                    Map<String, String> tmp = new LinkedHashMap<>(obj.size() * 2);
                    for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                        if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                            tmp.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                    m = Collections.unmodifiableMap(tmp);
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidI18n", ignored);
                // 读不到 → 空表 → 全部回退中文原文（最安全的失败模式）
            }
            TABLE = m;
            LOADED = true;
            return TABLE;
        }
    }

    /**
     * 取一条词条的译文，并修好**中文与英文混排时该有的空格**。
     * <p>两处修补，都是为了拼接串读起来像正常英文：
     * <ol>
     *   <li><b>补回被剥掉的首尾空格</b>：表里有 818 条键保留了前导空格（如 {@code " 个建筑）"}），
     *       译文却把它剥掉了（{@code "structures)"}）。整串精确显示时无所谓，但拼接时那个空格
     *       正是把中文词条与动态内容隔开的唯一分隔符——不补回来就会出现 {@code "建造（81个建筑）"} 这种挤在一起的串。</li> <li><b>全角标点后补空格</b>：键以 {@code ：（「} 这类全角标点开头（共 179 条）时，译文是 {@code "need to restock"} 这样的英文词，直接接在上一段英文后面会粘成一个词 （{@code "×3Warning"}）。同理键以全角标点结尾、译文是英文词时补一个尾空格。</li> </ol>
     */
    private static String render(String key) {
        String v = TABLE.get(key);
        if (v == null) {
            return key;
        }
        if (!key.isEmpty() && key.charAt(0) == ' ' && !v.isEmpty() && v.charAt(0) != ' ') {
            v = " " + v;
        } else if (key.length() > 1 && isFullWidthPunct(key.charAt(0))
                && !v.isEmpty() && needsLeadingSpace(v.charAt(0))) {
            v = " " + v;
        }
        int kn = key.length();
        if (kn > 1 && key.charAt(kn - 1) == ' ' && !v.isEmpty() && v.charAt(v.length() - 1) != ' ') {
            v = v + " ";
        } else if (kn > 1 && isFullWidthPunct(key.charAt(kn - 1))
                && !v.isEmpty() && Character.isLetterOrDigit(v.charAt(v.length() - 1))) {
            v = v + " ";
        }
        return v;
    }

    /** 全角标点（中文侧）——拼接时它后面/前面需要按英文习惯补空格。 */
    private static boolean isFullWidthPunct(char c) {
        return c == '\uff1a' || c == '\uff08' || c == '\uff09' || c == '\uff0c' || c == '\u3002'
                || c == '\u3001' || c == '\uff1b' || c == '\uff01' || c == '\uff1f'
                || c == '\u300c' || c == '\u300d' || c == '\u3010' || c == '\u3011'
                || c == '\u00b7' || c == '\u2026' || c == '\u2014' || c == '\uff5e';
    }

    /** 译文的这个字符是不是"前面得隔开"的（英文词/数字/左括号）。 */
    private static boolean needsLeadingSpace(char c) {
        return Character.isLetterOrDigit(c) || c == '(' || c == '[' || c == '"' || c == '\'';
    }

    /**
     * 按首字符建索引：{@code 首字符 → 以它开头的词条（长的在前）}。
     * <p>拼接串匹配（{@link #compose}）每一步都要问"从这一位起能不能匹配上某条"，逐条试全表
     * 是 O(词条数)；建索引后只试同首字符的那几条，再靠"长的在前"保证最长匹配优先。
     */
    /**
     * 拼接串匹配允许的**最短词条长度**。 <p>为什么不许单字词条参与拼接：表里有「白→White / 雪→Snow / 红→Red / 火→Fire」这样的单字 词条，而**玩家可以给女仆起名「白雪」「红石」「水火」**——这些两字名字会正好被两个单字词条 拼成「WhiteSnow」这种胡话，守卫也拦不住（每个字都被覆盖了）。 要求词条 ≥ 2 字之后，这类名字必然留下没被覆盖的汉字 → 整串放弃 → 名字原样保留。 单字词条本身仍可用于**整串精确**显示（{@code tr} 先在整表里查，走的是 {@link #table()}）。
     */
    private static final int MIN_SEGMENT = 2;

    private static Map<Character, String[]> index() {
        Map<Character, String[]> local = INDEX;
        if (local != null) {
            return local;
        }
        synchronized (PromaidI18n.class) {
            if (INDEX != null) {
                return INDEX;
            }
            Map<Character, java.util.List<String>> byFirst = new java.util.HashMap<>();
            for (String key : table().keySet()) {
                if (key.length() < MIN_SEGMENT || !CJK.matcher(key).find()) {
                    continue;
                }
                byFirst.computeIfAbsent(key.charAt(0), k -> new java.util.ArrayList<>()).add(key);
            }
            Map<Character, String[]> built = new java.util.HashMap<>(byFirst.size() * 2);
            for (Map.Entry<Character, java.util.List<String>> e : byFirst.entrySet()) {
                java.util.List<String> list = e.getValue();
                // 长的在前：同一位置优先匹配更长的词条（"建造（" 不会先被 "建造" 吃走）
                list.sort((a, b) -> Integer.compare(b.length(), a.length()));
                built.put(e.getKey(), list.toArray(new String[0]));
            }
            INDEX = built;
            return INDEX;
        }
    }

    /**
     * 拼接串翻译：把一段"中文词条 + 动态内容（数字 / 玩家名 / 物品名）+ 中文词条"混排的字符串
     * 逐段换成英文。
     * <h3>为什么需要它</h3>
     * 本模组大量文案是**拼出来**的，例如手册大目录那两个按钮：
     * {@code "§e⛰ 建造（" + buildCount + " 个建筑）"}。这种串永远不会等于表里任何一个键，
     * 于是整句原样留在中文——这正是"改了语言手册还是中文"的第二个来源。 <h3>怎么保证不出错</h3> 切分是**贪心最长匹配**，但带一道硬守卫：<b>输入里任何一个汉字都必须被某条词条覆盖</b>。 只要剩下一个没被覆盖的汉字（那是玩家名「小狐」、图纸名「别墅」这类动态内容，表里 本就不该有），就整串放弃、原样返回中文。动态内容因此永远不会被误译或吞掉。 <p>结果进 {@link #COMPOSED} 缓存（GUI 每帧都画，不能每帧重扫）。
     */
    public static String compose(String zh) {
        if (zh == null || zh.isEmpty() || zh.length() > COMPOSE_MAX_LEN || isChinese()) {
            return zh;
        }
        if (TABLE == null && !LOADED) {
            table();
        }
        if (TABLE.isEmpty() || !CJK.matcher(zh).find()) {
            return zh;
        }
        String cached = COMPOSED.get(zh);
        if (cached != null) {
            return cached;
        }
        Map<Character, String[]> idx = index();
        StringBuilder sb = new StringBuilder(zh.length() + 16);
        int i = 0;
        int n = zh.length();
        while (i < n) {
            String[] cands = idx.get(zh.charAt(i));
            String hit = null;
            if (cands != null) {
                for (String key : cands) {
                    if (zh.startsWith(key, i)) {
                        hit = key;
                        break;
                    }
                }
            }
            if (hit != null) {
                sb.append(render(hit));
                i += hit.length();
            } else {
                sb.append(zh.charAt(i));
                i++;
            }
        }
        String out = sb.toString();
        // 硬守卫：还剩**汉字** = 有动态内容没被词条覆盖 → 整串放弃（见方法注释）。
        // 只看汉字不看全角标点：译文里保留「」【】：是正常的。
        if (HAN.matcher(out).find()) {
            COMPOSED.put(zh, zh);
            return zh;
        }
        COMPOSED.put(zh, out);
        return out;
    }

    /** 语言表加载是否成功（诊断用）。 */
    public static int size() {
        return table().size();
    }

    /** 当前语言是不是中文（zh_cn / zh_tw / zh_hk 等任何 zh*）。读不到 → 按中文处理。 */
    public static boolean isChinese() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) {
                return true;
            }
            net.minecraft.client.resources.language.LanguageManager lm = mc.getLanguageManager();
            if (lm == null) {
                return true;
            }
            String sel = lm.getSelected();
            return sel == null || sel.isEmpty()
                    || sel.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidI18n", ignored);
            return true;
        }
    }

    /**
     * 中文原文 → 当前语言下的显示文本。**永远非 null**：
     * 中文玩家、空串、无译文 → 原样返回。
     */
    public static String tr(String zh) {
        if (zh == null || zh.isEmpty() || isChinese()) {
            return zh;
        }
        if (TABLE == null && !LOADED) {
            table();
        }
        Map<String, String> t = TABLE;
        if (t.isEmpty() || !CJK.matcher(zh).find()) {
            return zh;
        }
        String hit = t.get(zh);
        if (hit != null) {
            return hit;
        }
        // 带颜色码 / 首尾空白：剥掉后再试一次
        String stripped = FORMATTING.matcher(zh).replaceAll("").trim();
        if (!stripped.equals(zh) && !stripped.isEmpty()) {
            String h2 = t.get(stripped);
            if (h2 != null) {
                return h2;
            }
        }
        // 整串没命中（典型：中文词条 + 数字/名字 拼出来的串）→ 逐段拼装
        return compose(zh);
    }

    /**
     * 替换一个 {@code Component}：其整串命中词条则返回译文组件（**保留原样式**，即颜色/加粗
     * 等仍在），否则原样返回。
     *
     * <p>仅在字面量组件上生效（{@code getString()} 拿到的就是原文）；不做递归重排，
     * 不改变可翻译组件的参数。
     */
    public static Component tr(Component c) {
        if (c == null || isChinese()) {
            return c;
        }
        try {
            // v1.3.2 R948：多段消息逐段翻译。典型是主人聊天栏那行
            // 「§d[名字] §d台词」——整串翻时名字里的汉字会把整句交给硬守卫放弃
            // （名字是玩家自己起的，不在词条表里），台词那半边的英文也就没了。
            // 判据：根本没有正文（getString 全等于各兄弟串起来）、只有兄弟段 → 逐段翻。
            java.util.List<Component> sibs = c.getSiblings();
            if (!sibs.isEmpty() && ownTextEmpty(c, sibs)) {
                net.minecraft.network.chat.MutableComponent out =
                        Component.empty().withStyle(c.getStyle());
                for (Component s : sibs) {
                    out.append(tr(s));
                }
                return out;
            }
            String raw = c.getString();
            if (raw == null || raw.isEmpty()) {
                return c;
            }
            String out = tr(raw);
            if (out.equals(raw)) {
                return c;
            }
            return Component.literal(out).withStyle(c.getStyle());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidI18n", ignored);
            return c;
        }
    }

    /** 这个组件自己没有正文吗？（只有兄弟段 = 拼接容器） */
    private static boolean ownTextEmpty(Component c, java.util.List<Component> sibs) {
        StringBuilder kids = new StringBuilder();
        for (Component s : sibs) {
            kids.append(s.getString());
        }
        return c.getString().equals(kids.toString());
    }

    /**
     * 替换一个 {@link net.minecraft.network.chat.FormattedText}（可翻译组件/纯文本）。
     * 命中词条则返回等价的字面量文本，否则原样返回。
     */
    public static net.minecraft.network.chat.FormattedText tr(net.minecraft.network.chat.FormattedText t) {
        if (t == null || isChinese()) {
            return t;
        }
        try {
            if (t instanceof Component c) {
                return tr(c);
            }
            String raw = t.getString();
            String out = tr(raw);
            if (out.equals(raw)) {
                return t;
            }
            return net.minecraft.network.chat.FormattedText.of(out);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidI18n", ignored);
            return t;
        }
    }

    /** 剥掉 §x 颜色/格式码。 */
    public static String stripFormatting(String s) {
        return s == null ? null : FORMATTING.matcher(s).replaceAll("");
    }
}
