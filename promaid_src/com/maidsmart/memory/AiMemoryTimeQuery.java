package com.maidsmart.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 游戏日时间查询（移植自 maidsoulcore v3 {@code MinecraftTimeQuery} + 游戏日距离加权）。
 *
 * <p>玩家问"昨天我们在哪里找到铁矿？"时，检索要能把「昨天」解析成一个确定的游戏日窗口，再按
 * 事件发生日与窗口的距离排序——而不是把它当成普通关键词去和记忆正文做词面匹配（"昨天"这两
 * 个字根本不会出现在记忆里）。本类是纯解析器：给当前游戏日和一句查询，吐出闭区间
 * {@code [startDay, endDay]}。
 *
 * <p>游戏日定义与 {@code AiMemoryArchiver} / {@code AiMemoryManager.dailyConsolidate} 一致：
 * {@code gameDay = gameTick / 24000}（原版一天 24000 tick，清晨为日界）。
 *
 * <p>纯工具类、无状态。两树逐字相同。
 */
public final class AiMemoryTimeQuery {

    /** 一段游戏日闭区间。 */
    public record Window(long startDay, long endDay) {
        public long span() {
            return this.endDay - this.startDay + 1;
        }
    }

    /** 相对时间线索：出现这些字样说明查询在指向某段时间（用于决定是否启用时间召回路）。 */
    private static final String[] CUES = {
            "今天", "今日", "昨天", "昨日", "前天", "大前天", "明天",
            "上周", "上星期", "这周", "本周", "这个星期", "上个月", "上月", "这个月",
            "前几天", "这几天", "最近", "那天", "那天", "当时", "曾经", "以前", "之前",
            "小时候", "刚刚", "刚才"
    };

    /** "N天前 / N天以前" 捕获（N 可为阿拉伯数字或中文数字）。 */
    private static final Pattern N_DAYS_AGO = Pattern.compile("([0-9一二两三四五六七八九十]+)\\s*天(?:以)?前");

    private AiMemoryTimeQuery() {
    }

    /** 游戏 tick → 游戏日。 */
    public static long gameDayOf(long gameTime) {
        return gameTime < 0 ? 0 : gameTime / 24000L;
    }

    /** 查询是否含相对时间线索。 */
    public static boolean hasCue(String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        for (String c : CUES) {
            if (query.contains(c)) {
                return true;
            }
        }
        return N_DAYS_AGO.matcher(query).find();
    }

    /**
     * 把查询里的相对时间表达解析成游戏日窗口；解析不出（如"那天""曾经"这种无锚点的说法）
     * 返回 null——调用方应回退到普通关键词检索。
     */
    public static Window resolve(String query, long nowGameDay) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        long d = nowGameDay;
        if (query.contains("大前天")) {
            return new Window(d - 3, d - 3);
        }
        if (query.contains("前天")) {
            return new Window(d - 2, d - 2);
        }
        if (query.contains("昨天") || query.contains("昨日")) {
            return new Window(d - 1, d - 1);
        }
        if (query.contains("今天") || query.contains("今日") || query.contains("刚才") || query.contains("刚刚")) {
            return new Window(d, d);
        }
        if (query.contains("上周") || query.contains("上星期")) {
            return new Window(d - 7, d - 1);
        }
        if (query.contains("这周") || query.contains("本周") || query.contains("这个星期")) {
            return new Window(Math.max(0, d - 6), d);
        }
        if (query.contains("上个月") || query.contains("上月")) {
            return new Window(Math.max(0, d - 30), Math.max(0, d - 1));
        }
        if (query.contains("这个月")) {
            return new Window(Math.max(0, d - 29), d);
        }
        if (query.contains("这几天") || query.contains("前几天") || query.contains("最近")) {
            return new Window(Math.max(0, d - 3), d);
        }
        Matcher m = N_DAYS_AGO.matcher(query);
        if (m.find()) {
            int n = parseNum(m.group(1));
            if (n > 0) {
                return new Window(Math.max(0, d - n), Math.max(0, d - n));
            }
        }
        return null; // "那天/当时/曾经/以前" 之类无锚点 → 交给调用方决定
    }

    /** 事件游戏 tick 是否落在窗口内。 */
    public static boolean inWindow(long eventGameTime, Window w) {
        if (w == null) {
            return true;
        }
        long gd = gameDayOf(eventGameTime);
        return gd >= w.startDay() && gd <= w.endDay();
    }

    /**
     * 时间路打分：离窗口越近分越高（窗口内 = 1.0；窗口外按天距离线性衰减到 0）。
     * 用于把"昨天的记忆"抬到"很久以前的同词记忆"之上。
     */
    public static double proximity(long eventGameTime, Window w, long nowGameDay) {
        if (w == null) {
            return 0.0;
        }
        long gd = gameDayOf(eventGameTime);
        if (gd >= w.startDay() && gd <= w.endDay()) {
            return 1.0;
        }
        long dist = gd < w.startDay() ? w.startDay() - gd : gd - w.endDay();
        // 窗口外 14 天内线性衰减
        return Math.max(0.0, 1.0 - dist / 14.0);
    }

    /** 中文/阿拉伯数字 1..99（够用：天数量级）。 */
    static int parseNum(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int total = 0;
        int section = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = digitOf(c);
            if (v < 0) {
                if (c == '十') {
                    section = (section == 0 ? 1 : section) * 10;
                }
                continue;
            }
            section += v;
        }
        total += section;
        return total <= 0 ? -1 : total;
    }

    private static int digitOf(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }
}
