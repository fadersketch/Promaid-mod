package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;

/**
 * 工作站桩标记（v1.5.24）。
 *
 * 背景：1.20.1 Brain 同一 activity 内多个行为并发启动（无 runningPriority 互斥），
 * MoveToTargetSink 每 tick 读取 WALK_TARGET 并驱动寻路。工作行为（建筑/烹饪/酿造/整理）
 * 虽然在 m_6725_ 里每 tick 清 WALK_TARGET + 停导航，但若 MoveToTargetSink 在行为执行
 * 之前跑，会读到（其他行为/上 tick 遗留的）移动目标重新寻路 → 站桩"时灵时不灵"。
 * 于是把"完全站桩"状态打在 persistentData 上，由 MaidMoveSuppressMixin 从源头取消
 * MoveToTargetSink（比事后清 memory 更可靠）。
 */
public final class MaidWorkTags {
    /** persistentData 键：女仆处于工作站桩（建筑/烹饪/酿造/整理，站立不动） */
    public static final String WORK_STILL_TAG = "maid_smart_work_still";

    private MaidWorkTags() {
    }

    /** 是否处于完全站桩工作状态（标记 + 当前任务确为工作任务，双保险防残留冻结） */
    public static boolean isStill(EntityMaid maid) {
        if (!maid.getPersistentData().m_128471_(WORK_STILL_TAG)) {
            return false;
        }
        // 防残留：标记在但任务已切走（行为被移除没走到清标记路径）→ 不冻结女仆
        return isWorkTask(maid);
    }

    /** 是否为"站桩工作"任务之一（建筑/烹饪/酿造；v1.5.134 整理任务已删除） */
    public static boolean isWorkTask(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        ResourceLocation uid = maid.getTask().getUid();
        return uid.equals(ResourceLocation.parse("maid_smart:build"))
                || uid.equals(ResourceLocation.parse("maid_smart:cook"))
                || uid.equals(ResourceLocation.parse("maid_smart:brew"));
    }

    /** v1.5.129：战斗任务 UID（干活不被打断的豁免集合——战斗女仆保留吃饭/恐慌） */
    private static final java.util.Set<String> COMBAT_UIDS = java.util.Set.of(
            "attack", "ranged_attack", "crossbow_attack", "trident_attack", "danmaku_attack");

    /**
     * v1.5.129：是否"非战斗干活中"——任务 UID 不属于战斗集合即视为干活
     * （Promaid 挖矿/建筑/烹饪/酿造 + TLM 原生 farm/挤奶/钓鱼/剪毛/蜂蜜等
     * 全部命中；idle/跟随/战斗类不命中）。用于"干活不被打断"系列闸门
     * （吃饭/偷吃/恐慌/切班拉回），战斗女仆不受影响。
     *
     * v1.5.134 修复：m_135827_ 是 getNamespace（返回 touhou_little_maid），
     * 与 COMBAT_UIDS（path 名）永远不匹配 → isNonCombatWork 恒 true →
     * 战斗女仆也吃"干活不打断"闸门（战斗时不吃饭/不恐慌）。改为 m_135815_
     * （= getPath，返回 attack/ranged_attack 等）——MaidToolAutoEquip 注释实证。
     */
    public static boolean isNonCombatWork(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        return !COMBAT_UIDS.contains(maid.getTask().getUid().m_135815_());
    }

    /** v1.5.142：是否处于战斗任务（攻击/弓/弩/三叉戟/弹幕）——副手盾牌自动装备判定用 */
    public static boolean isCombatTask(EntityMaid maid) {
        if (maid.getTask() == null) {
            return false;
        }
        return COMBAT_UIDS.contains(maid.getTask().getUid().m_135815_());
    }

    public static void setStill(EntityMaid maid, boolean still) {
        maid.getPersistentData().m_128379_(WORK_STILL_TAG, still);
    }
}
