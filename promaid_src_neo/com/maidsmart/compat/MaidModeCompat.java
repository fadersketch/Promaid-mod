package com.maidsmart.compat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;

/**
 * v1.2.2 实测五百九十【第三方「玩法模式」黑名单】。
 *
 * 反馈原文：「这个功能的模组位于龙之冒险、新征程这个存档里面。如果检测到有傀儡模式，那么会
 * 自动将他列入黑名单。自主切换和主动作战不会切换到这个模式。如果玩家手动切换到这个模式，
 * 那么原本这个 mod 安排的所有战术全都暂时无效，仅保留那个模组原汁原味的傀儡装配玩法。」
 *
 * 对象 =《傀儡装配》Modular Golems（modulargolems；龙之冒险：新征程 v2.4a 里的
 * `[傀儡装配] modulargolems-2.7.3.jar`）给女仆注册的任务「傀儡师」。javap 实证：
 * `dev.xkmc.modulargolems.compat.maid.MaidSummonerTask implements IRangedAttackTask`，
 * UID = `modulargolems:summon_golems`（`ModularGolems.loc("summon_golems")`），
 * 进入条件 = 主手持「万能手杖」（lang 键 `task.modulargolems.summon_golems.condition.has_golem_wand`），
 * 玩法 = 女仆召唤傀儡替她打。
 *
 * 【为什么要拉黑】它是 `IRangedAttackTask`——本模组所有「战斗任务」口径（自主参战的任务池、
 * 投弹链路、单兵战术）都会把它当成战斗模式。可它的玩法是「女仆站在后面指挥傀儡」，本模组那套
 * 跳劈/贴脸绕圈/投弹会把她从指挥官变成突击兵，等于把那个模组的玩法拆了。
 *
 * 两条口径（对应反馈里的两句话）：
 * ① 自动系统（自主参战选任务 / 战中换战术 / LLM 自主切换的候选与可用列表）**永不切进去**
 *    ——{@link #isBlacklisted}；
 * ② 玩家手动切进去之后，本模组的**战术全体让位**——{@link #isPuppetMode}：单兵作战战术
 *    （走位/跳劈/举盾/威胁驱动）、自动换装、投弹与轰炸、自主参战与还原、排班换段全部停手。
 *    **保命动作不在让位之列**（自保、落地水、防火、宠物免疫）——那是「别死」的保险，不是战术。
 *    玩家把她切回别的任务，一切自动恢复（全程没动过任何存档数据）。
 *
 * 检测口径：UID 硬编码比对——**没装那个模组时这就是一句恒假的判断**（整合包里没有这个任务，
 * 黑名单等于空），所以对不装该模组的存档零影响。总开关：`compat.puppetBlacklist`（面板可关）。
 */
public final class MaidModeCompat {
    /** 黑名单（UID → 人类可读名）：目前只有傀儡师；将来有同类「玩法模式」往这里加 */
    private static final Map<String, String> BLACKLIST = Map.of(
            "modulargolems:summon_golems", "傀儡师（傀儡装配 Modular Golems）");

    private MaidModeCompat() {
    }

    /** 总开关（兼容段；默认开） */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMPAT_PUPPET_BLACKLIST.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 任务 UID 是否在黑名单里 */
    public static boolean isBlacklistedUid(String uid) {
        return uid != null && BLACKLIST.containsKey(uid);
    }

    /** 任务是否在黑名单里（自主切换/候选池过滤用；null 安全） */
    public static boolean isBlacklisted(IMaidTask task) {
        if (!enabled() || task == null) {
            return false;
        }
        try {
            return isBlacklistedUid(String.valueOf(task.getUid()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 黑名单条目的人类可读名（给 LLM 工具回话时说清楚理由） */
    public static String descriptionOf(IMaidTask task) {
        try {
            String name = task == null ? null : BLACKLIST.get(String.valueOf(task.getUid()));
            return name == null ? "第三方玩法模式" : name;
        } catch (Throwable ignored) {
            return "第三方玩法模式";
        }
    }

    /** 黑名单 UID 的人类可读名（LLM 工具回话用；非黑名单返回兜底文案） */
    public static String descriptionOfUid(String uid) {
        String name = BLACKLIST.get(uid);
        return name == null ? "第三方玩法模式" : name;
    }

    /**
     * 她当前是不是正处在这个第三方玩法模式里。是的话本模组的战术全体让位，
     * 只保留那个模组自己的玩法（见类注释的口径 ②）。
     */
    public static boolean isPuppetMode(EntityMaid maid) {
        if (!enabled() || maid == null) {
            return false;
        }
        try {
            IMaidTask t = maid.getTask();
            return t != null && isBlacklistedUid(String.valueOf(t.getUid()));
        } catch (Throwable ignored) {
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
