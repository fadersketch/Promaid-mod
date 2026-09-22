package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 实测六百一十九【战斗时临时扩圈】（用户反馈）。
 *
 * ── 需求原文 ──
 * "还有近战战斗时由于超出工作范围而被传送回来，然后就这么来回循环。感觉可以改成
 *  战斗状态临时扩圈把范围改大些，回到常态再用正常设置的工作范围。"
 *
 * ── 根因（TLM 1.5.2/1.5.3 两树反编译实证：{@code SchedulePos.tick} 每 40 tick 一次）──
 * <pre>
 *   restrictTo(maid);                                  // 按当前活动刷圈心/半径
 *   if (maid.isWithinRestriction()) return;            // 圈内 → 什么都不做
 *   if (!maid.canBrainMoving()) return;
 *   double distanceSqr = getRestrictCenter().distSqr(maid.blockPosition());
 *   int minTeleportDistance = (int) maid.getRestrictRadius() + 4;   // ← 就是这一条
 *   if (distanceSqr &gt; minTeleportDistance² &amp;&amp; !sameWithRestrictCenter(maid)) {
 *       teleport(maid);                                // 传送回圈心（TeleportHelper.teleportToRestrictCenter）
 *   } else {
 *       BehaviorUtils.setWalkAndLookTarget(maid, center, 0.7f, 3); // 走回去
 *   }
 * </pre>
 * home 模式（不跟随 = 排班/在家干活）下，圈的半径来自 TLM 的
 * MAID_WORK_RANGE / MAID_IDLE_RANGE / MAID_SLEEP_RANGE（本模组另有
 * {@code scheduleActivityRange} 抬高下限，见 ScheduleRangeMixin）。女仆接了战后追怪，
 * 一跑出"半径 + 4"就被**直接传送回工位**——于是"追出去 → 传送回来 → 再追出去"，
 * 玩家看到的就是"来回循环"，仗永远打不完（追的是同一个怪，进度每次清零）。
 *
 * ── 修法：只改"读数"，不动任何存储 ──
 * {@link com.maidsmart.mixin.CombatWorkRangeMixin} 挂在
 * {@code EntityMaid.getRestrictRadius} 的出口上：**正在接战时**返回
 * {@code max(常态半径, 配置的 combatWorkRange)}，其余时候原样返回。因为
 * TLM 那一整套判据（{@code isWithinRestriction} 的圈内判定、寻路节点评估
 * MaidNodeEvaluator、上面那条传送阈值、各任务"离家多远该回去"）**全都读这一个值**，
 * 一处改完就全线一致：
 * <ul>
 *   <li>接战中：圈变大 → 不传送、不"走回去"、寻路也允许她追出去；</li>
 *   <li>战斗结束（威胁消失 / 目标清掉 / 挨打窗口过去）：读数自动落回常态半径，
 *       该回家回家、该被传送传送——"回到常态再用正常设置的工作范围"，不需要任何收尾代码。</li>
 * </ul>
 *
 * ── 为什么取 max 而不是直接改大 ──
 * 玩家把 {@code scheduleActivityRange} 调到比本项更大时（想让她大范围干活），
 * 本项不该把圈**缩小**；只有常态半径比它小时才扩。
 *
 * ── 为什么不碰客户端的圈 ──
 * 客户端的圈靠同步的圈心/半径画（MaidAreaRenderEvent），而"在不在接战"依赖 Brain
 * 记忆与攻击时间戳——客户端拿不到（记忆不同步、target 也不同步）。硬在客户端猜会画出
 * 一个和服务端不一致的圈，所以客户端恒返回常态值（画出来的还是玩家设置的工作范围）。
 */
public final class CombatWorkRange {

    private CombatWorkRange() {
    }

    /**
     * 临时扩圈的目标值（格）。0 = 关闭本功能；默认 15——比默认工作范围（12 格）略大一圈，
     * 够她追出工位接战，又不会被拽进几十格外的乱战（实测六百二十三 由 32 调小）。
     */
    public static int expandRadius() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_WORK_RANGE.get();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 圈半径的最终读数（mixin 的出口值）：接战中且配置开启 → 取大者，否则原样。
     * 任何异常 → 原样返回（这个 getter 被 TLM 各处高频调用，绝不能抛）。
     */
    public static float radius(EntityMaid maid, float normal) {
        try {
            if (maid == null || expandRadius() <= 0) {
                return normal;
            }
            if (maid.level() == null || maid.level().isClientSide()) {
                return normal; // 客户端：画出来的圈保持玩家设置（见类文档）
            }
            if (!maid.hasRestriction()) { // 非 home 模式本来没有圈（TLM 重写为 isHomeModeEnable）
                return normal;
            }
            return inCombat(maid) ? Math.max(normal, expandRadius()) : normal;
        } catch (Throwable ignored) {
            return normal;
        }
    }

    /**
     * 她"正在接战"吗——决定要不要临时扩圈（任一命中即算）：
     * <ol>
     *   <li><b>脑里有活动目标</b>：{@code ATTACK_TARGET} 记忆在、且目标还活着
     *       （TLM 攻击任务与我们的战术/空袭全走这条记忆，是"正在打"最准的信号）；</li>
     *   <li><b>实体层目标</b>：{@code Mob.getTarget()} 非空且活着（TLM/原版任务写的那份，
     *       与上面那条互为兜底——两边任一没写全都不影响判定）；</li>
     *   <li><b>自保中</b>：自保会话标记在（自保本身就是"在危险里"，此时更不该被圈拽回去）；</li>
     *   <li><b>刚交过手</b>：最近 {@value #RECENT_COMBAT_TICKS} tick 内她打过东西或挨过打
     *       （覆盖"目标刚被打死/刚丢掉，人还在圈外"的那几秒——否则最后那一下必然触发传送）。</li>
     * </ol>
     * 全部只读，异常一律按"没在接战"处理（宁可少扩一次圈，也不要凭异常把圈放大）。
     */
    public static boolean inCombat(EntityMaid maid) {
        try {
            var brain = maid.getBrain();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
                if (t != null && t.isAlive()) {
                    return true; // ATTACK_TARGET
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.getTarget();
            if (mt != null && mt.isAlive()) {
                return true; // Mob.getTarget
            }
            if (maid.getPersistentData().getBoolean(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return true; // 自保会话中
            }
            int now = maid.tickCount;
            // 【初生女仆不算"刚交过手"】两个时间戳字段初值是 0，`now - 0 < 100` 在她出生的
            // 头 100 tick 里恒成立——不挡住的话，一只刚召出来（还带着工作圈）的女仆会被
            // 白扩 5 秒圈。加上"时间戳本身要非 0"这一条即可。
            if (now < RECENT_COMBAT_TICKS) {
                return false;
            }
            int lastHurt = maid.getLastHurtByMobTimestamp();   // 最近挨打
            int lastAttack = maid.getLastHurtMobTimestamp();   // 最近打人
            return (lastHurt > 0 && now - lastHurt < RECENT_COMBAT_TICKS)
                    || (lastAttack > 0 && now - lastAttack < RECENT_COMBAT_TICKS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "刚交过手"的窗口（tick）：5 秒——目标丢掉/被打死后还有 5 秒不算"脱离战斗"，
     *  足够她走回圈内（走不回去也该被传送，那是常态半径的职责） */
    private static final int RECENT_COMBAT_TICKS = 100;

    /**
     * 她"算在接战"的**第一条命中的依据**（诊断用：自检/排查"圈怎么变大了"时把这条打进日志）。
     * 返回：{@code ATTACK_TARGET} / {@code getTarget} / {@code 自保中} / {@code 最近挨打} /
     * {@code 最近打人} / {@code 无}。
     */
    public static String combatReason(EntityMaid maid) {
        try {
            var brain = maid.getBrain();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
                if (t != null && t.isAlive()) {
                    return "ATTACK_TARGET";
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.getTarget();
            if (mt != null && mt.isAlive()) {
                return "getTarget";
            }
            if (maid.getPersistentData().getBoolean(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return "自保中";
            }
            int now = maid.tickCount;
            if (now < RECENT_COMBAT_TICKS) {
                return "无";
            }
            int lastHurt = maid.getLastHurtByMobTimestamp();
            if (lastHurt > 0 && now - lastHurt < RECENT_COMBAT_TICKS) {
                return "最近挨打";
            }
            int lastAttack = maid.getLastHurtMobTimestamp();
            if (lastAttack > 0 && now - lastAttack < RECENT_COMBAT_TICKS) {
                return "最近打人";
            }
            return "无";
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
