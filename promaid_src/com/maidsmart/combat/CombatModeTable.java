package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗模式分类表——"哪些模式算近战、哪些算远程、哪些不参与自主切换"，从写死的推断改成玩家可点名的表。
 *
 * 【为什么原逻辑"笼统"】入战选任务与战中换战术共用一份候选池，池子规则是**写死的三件事**：① 谁能进池
 * = 只认"任务自己认的武器在背包里"+ 几条硬门；② 进池算近战还是远程 = 靠 UID 关键词与命名空间猜（未知
 * 模组任务一律当近战兜底）；③ 池里有模组专属任务时原版通用五件套整体让位（模组优先）。三件都合理，但
 * **没一件玩家能改**——装了冷门模组的人只能看着任务被猜错分类、或被"模组优先"整体挤掉。
 * 【这张表能改什么】一行一条 {@code 任务UID=近战/远程/不参与}：{@code 近战}/{@code 远程} = 分类以表为
 * 准，且**点名写过的任务不再被"模组任务优先"整体让位挤掉**；{@code 不参与} = 入战选任务与战中换战术
 * 两条路上都永不入选。表里没写的任务 = 一字不差走旧版（内置推断 + 既有参与门）；空表 = 与旧版相同。
 * 【它不改什么】①②③以外的门全部照旧：写"参与"也要她手里有那个任务认的武器、枪械有弹药、法术任务有
 * 法术装备；写"不参与"只影响**自动切换**，玩家在 TLM 面板手动切过去完全不受影响。第三方玩法黑名单
 * （{@link MaidModeCompat#isBlacklisted}）优先于本表。UID 从 {@code /maid_smart combat modes} 抄。
 */
public final class CombatModeTable {
    /** 运行日志分类（{@code /maid_smart combat modes} 的输出也进 promaid.log） */
    public static final String CAT = "战斗分类表";
    /** 表里的三个取值（配置文件里存这几个词，中文写法在 {@link #normalizeMode} 里归一） */
    public static final String MELEE = "melee";
    public static final String RANGED = "ranged";
    public static final String OFF = "off";

    private CombatModeTable() {
    }

    /**
     * 表 → uid→取值。
     *
     * 【为什么要缓存】这张表在**选任务的循环里**被逐任务问一次（buildPools 对每个
     * 攻击类任务都要问"这个算近战还是远程"），而每秒每个战斗中的女仆都要重建一次
     * 候选池——现读现解析等于每秒几十次 map 分配。缓存键就是配置表本身的内容
     * （{@code equals} 比内容），面板/toml 一改内容就自动重建，不需要失效逻辑。
     */
    private static volatile List<? extends String> cachedRaw = null;
    private static volatile Map<String, String> cachedTable = Map.of();

    public static Map<String, String> parse() {
        List<? extends String> raw;
        try {
            raw = com.maidsmart.config.MaidSmartConfig.COMBAT_TASK_MODES.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatModeTable", ignored);
            return Map.of(); // 配置还没建好（极早期调用）：按空表处理
        }
        if (raw != null && raw.equals(cachedRaw)) {
            return cachedTable;
        }
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String line : raw) {
                if (line == null) {
                    continue;
                }
                // 配置是"一行一条"，但面板输入框与 toml 手写都可能是逗号分隔——两种都吃
                for (String part : line.split("[,，、\\n]")) {
                    String entry = part.trim();
                    if (entry.isEmpty()) {
                        continue;
                    }
                    int eq = entry.indexOf('=');
                    if (eq <= 0 || eq == entry.length() - 1) {
                        continue; // 没有 = 或两边缺一边：忽略这条（面板侧已挡住，手改 toml 兜底）
                    }
                    String uid = entry.substring(0, eq).trim();
                    String mode = normalizeMode(entry.substring(eq + 1));
                    if (!uid.isEmpty() && mode != null) {
                        out.put(uid, mode);
                    }
                }
            }
        }
        cachedRaw = raw;
        cachedTable = out;
        return out;
    }

    /** 这个任务在表里写了什么；没写（或表读不出来）返回 null */
    public static String modeOf(String uid) {
        if (uid == null || uid.isEmpty()) {
            return null;
        }
        return parse().get(uid);
    }

    /** 表里点名写了"不参与"→ true（入战选任务与战中换战术都不选它） */
    public static boolean blocksAutoSwitch(String uid) {
        return OFF.equals(modeOf(uid));
    }

    /** 表里点名写了近战/远程 → true=远程、false=近战；没写返回 null（照旧用内置推断） */
    public static Boolean rangedOverride(String uid) {
        String mode = modeOf(uid);
        if (RANGED.equals(mode)) {
            return Boolean.TRUE;
        }
        if (MELEE.equals(mode)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 「模组任务优先让位」（池里有模组专属任务时原版通用任务整体让位）是否生效 */
    public static boolean vanillaYieldToMod() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_VANILLA_YIELD_TO_MOD.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatModeTable", ignored);
            return true;
        }
    }

    /** 取值归一化：认英文（melee/ranged/off，大小写与首尾空格不计）与中文（近战/远程/不参与…）；
     *  认不出来返回 null（面板据此拒收，不让非法值落进配置） */
    public static String normalizeMode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "melee":
            case "近战":
            case "近":
            case "1":
                return MELEE;
            case "ranged":
            case "range":
            case "远程":
            case "远":
            case "2":
                return RANGED;
            case "off":
            case "none":
            case "no":
            case "不参与":
            case "不参与切换":
            case "不切换":
            case "关":
            case "0":
                return OFF;
            default:
                return null;
        }
    }

    /** 取值 → 面板/命令里的中文（认不出来按"没写"显示） */
    public static String label(String mode) {
        if (MELEE.equals(mode)) {
            return "近战";
        }
        if (RANGED.equals(mode)) {
            return "远程";
        }
        if (OFF.equals(mode)) {
            return "不参与";
        }
        return "没写";
    }

    /**
     * 一行输入 → 规范表项 {@code uid=melee}；非法返回 null（面板据此拒绝提交并保留旧值）。
     * 要求：UID 带命名空间（{@code a:b}）——不带的话压根对不上任何任务，写进去只是噪音。
     */
    public static String normalizeEntry(String raw) {
        if (raw == null) {
            return null;
        }
        String entry = raw.trim();
        if (entry.isEmpty()) {
            return null;
        }
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq == entry.length() - 1) {
            return null;
        }
        String uid = entry.substring(0, eq).trim();
        String mode = normalizeMode(entry.substring(eq + 1));
        if (uid.isEmpty() || mode == null || !uid.contains(":")) {
            return null;
        }
        return uid + "=" + mode;
    }

    /** 面板输入框的一整串（逗号/换行分隔）→ 规范表项列表；任一行非法返回 null（整串拒收） */
    public static List<String> normalizeAll(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>(); // 留空 = 清空表（回到"全部按内置规则参与"）
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,，、\\n]")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            String norm = normalizeEntry(piece);
            if (norm == null) {
                return null;
            }
            if (!out.contains(norm)) {
                out.add(norm);
            }
        }
        return out;
    }

    /** 面板显示用的一行（表项 → "uid=近战" 这种给人看的写法） */
    public static String prettyAll() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : parse().entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(label(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * {@code /maid_smart combat modes} 用：当前**所有攻击类任务**在两种口径下的分类与
     * 参与情况——"内置"= 表没写时 isRangedTask 的推断结果，"现在"= 表生效后的结果。
     *
     * 注意这是**全局**视角（一台服务器上装了哪些附属）：一只女仆实际会不会被切到某个
     * 任务，还要看她背包里有没有那个任务认的武器/弹药/法术装备（既有参与门），
     * 命令里那句"另需武器/弹药等既有门"就是提醒这件事。
     */
    public static List<String> report() {
        List<String> out = new ArrayList<>();
        try {
            for (IMaidTask task : TaskManager.getTaskIndex()) {
                if (!(task instanceof IAttackTask)) {
                    continue;
                }
                String uid = task.getUid() == null ? "(无 UID)" : task.getUid().toString();
                String mode = modeOf(uid);
                boolean defRanged = AutoCombatSwitch.defaultRangedByUid(task);
                boolean nowRanged = AutoCombatSwitch.isRangedTask(task);
                out.add(uid
                        + " ｜ 内置" + (defRanged ? "远程" : "近战")
                        + " ｜ 现在" + (nowRanged ? "远程" : "近战")
                        + (mode == null ? "（表里没写）" : "（表里点名 " + label(mode) + "）")
                        + (OFF.equals(mode) ? " ｜ 不参与自主切换" : " ｜ 参与自主切换"));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatModeTable", ignored);
        }
        if (out.isEmpty()) {
            out.add("（一个攻击类任务都没扫到——附属模组没加载？）");
        }
        return out;
    }
}
