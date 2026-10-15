package com.maidsmart.compat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;

/**
 * 第三方「玩法模式」名单——对象 = 自带一整套战斗玩法的模组任务：目前三条——《傀儡装配》的「傀儡师」
 * （{@code modulargolems:summon_golems}）、《车万女仆：真正的力量》的「拔刀剑模式」
 * （{@code true_power_of_maid:slashblade_attack}）、《史诗战斗联动》的「战斗模式」（{@code ef_tlm:fight_mode_task}）。
 * 【为什么拉黑】它们对 TLM 而言都是攻击类任务，本模组的「战斗任务」口径（投弹与轰炸、单兵战术、
 * 走位独占、威胁驱动）会把她们变成突击兵——等于拆了那个模组自己的玩法。
 * 两条口径：① 处在名单里的她，本模组**战术与行动全体让位**（{@link #isPuppetMode}）：单兵战术、
 * 投弹与轰炸、威胁驱动、走位独占、自动换装、排班换段全停手，**保命动作不在让位之列**（自保、
 * 落地水、防火、宠物免疫）；自动化链路只保留「切进去」与「敌人清除后还原回战斗前任务」两件事。
 * ② 其中只有「傀儡师」**自主切换永不切入**（{@link #isBlacklisted}）；拔刀剑模式 / 战斗模式
 * **允许**被自主参战与 LLM 自主切换切进去——切进去之后同样全体让位。
 * 检测口径：UID 硬编码比对——没装对应模组时就是一句恒假判断（名单等于空），对不装它们的存档零影响。
 * 总开关 {@code compat.puppetBlacklist}（面板可关）。
 */
public final class MaidModeCompat {
    /** 让位名单（UID → 人类可读名）：目前三条；她处在其中任一条里时本模组战术与行动全体让位 */
    private static final Map<String, String> BLACKLIST = Map.of(
            "modulargolems:summon_golems", "傀儡师（傀儡装配 Modular Golems）",
            "true_power_of_maid:slashblade_attack", "拔刀剑模式（车万女仆：真正的力量）",
            "ef_tlm:fight_mode_task", "战斗模式（史诗战斗联动）");

    /** 「自主切换永不切入」的 UID（傀儡师——它不在战斗候选的语义里）；拔刀剑模式 / 战斗模式
     *  允许被自主参战与 LLM 自主切换切进去，切进去之后同样全体让位 */
    private static final java.util.Set<String> NO_AUTO = java.util.Set.of("modulargolems:summon_golems");

    private MaidModeCompat() {
    }

    /** 总开关（兼容段；默认开） */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMPAT_PUPPET_BLACKLIST.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidModeCompat", ignored);
            return true;
        }
    }

    /** 该 UID 是否禁止自主切入（名单里只有傀儡师；LLM 工具闸与候选池过滤用） */
    public static boolean isBlacklistedUid(String uid) {
        return uid != null && NO_AUTO.contains(uid);
    }

    /** 该任务是否禁止自主切入（只有傀儡师；自主切换/候选池过滤用；null 安全） */
    public static boolean isBlacklisted(IMaidTask task) {
        if (!enabled() || task == null) {
            return false;
        }
        try {
            return isBlacklistedUid(String.valueOf(task.getUid()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidModeCompat", ignored);
            return false;
        }
    }

    /** 黑名单条目的人类可读名（给 LLM 工具回话时说清楚理由） */
    public static String descriptionOf(IMaidTask task) {
        try {
            String name = task == null ? null : BLACKLIST.get(String.valueOf(task.getUid()));
            return name == null ? "第三方玩法模式" : name;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidModeCompat", ignored);
            return "第三方玩法模式";
        }
    }

    /** 黑名单 UID 的人类可读名（LLM 工具回话用；非黑名单返回兜底文案） */
    public static String descriptionOfUid(String uid) {
        String name = BLACKLIST.get(uid);
        return name == null ? "第三方玩法模式" : name;
    }

    /**
     * 她当前是不是正处在让位名单的模式里（傀儡师 / 拔刀剑模式 / 战斗模式）。是的话本模组的
     * 战术与行动全体让位，只保留那个模组自己的玩法（不管谁切进去的，见类注释口径 ①）。
     */
    public static boolean isPuppetMode(EntityMaid maid) {
        if (!enabled() || maid == null) {
            return false;
        }
        try {
            IMaidTask t = maid.getTask();
            return t != null && BLACKLIST.containsKey(String.valueOf(t.getUid()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidModeCompat", ignored);
            return false;
        }
    }

    /** 战术闸的语义化别名（与 {@link #isPuppetMode} 同义） */
    public static boolean isSuspended(EntityMaid maid) {
        return isPuppetMode(maid);
    }

    /** 黑名单条目数（诊断/文档用） */
    public static int size() {
        return BLACKLIST.size();
    }
}
