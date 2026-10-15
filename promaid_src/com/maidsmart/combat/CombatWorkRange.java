package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * ── 需求原文 ──
 * "还有近战战斗时由于超出工作范围而被传送回来，然后就这么来回循环。感觉可以改成
 *  战斗状态临时扩圈把范围改大些，回到常态再用正常设置的工作范围。"
 * ── 根因（TLM 1.5.2/1.5.3 两树反编译实证：{@code SchedulePos.tick} 每 40 tick 一次）──
 * <pre>
 *   restrictTo(maid);                                  // 按当前活动刷圈心/半径
 *   if (maid.isWithinRestriction()) return;            // 圈内 → 什么都不做
 *   if (!maid.canBrainMoving()) return;
 *   double distanceSqr = getRestrictCenter().distSqr(maid.blockPosition());
 *   int minTeleportDistance = (int) maid.getRestrictRadius() + 4;   // ← 就是这一条
 *   if (distanceSqr &gt; minTeleportDistance² &amp;&amp; !sameWithRestrictCenter(maid)) {
 *       teleport(maid);                                // 传送回圈心（TeleportHelper.teleportToRestrictCenter）
 *   } else { BehaviorUtils.setWalkAndLookTarget(maid, center, 0.7f, 3); // 走回去 } </pre> home 模式（不跟随 = 排班/在家干活）下，圈的半径来自 TLM 的 MAID_WORK_RANGE / MAID_IDLE_RANGE / MAID_SLEEP_RANGE（本模组另有 {@code scheduleActivityRange} 抬高下限，见 ScheduleRangeMixin）。女仆接了战后追怪， 一跑出"半径 + 4"就被**直接传送回工位**——于是"追出去 → 传送回来 → 再追出去"， 玩家看到的就是"来回循环"，仗永远打不完（追的是同一个怪，进度每次清零）。 ── 修法：只改"读数"，不动任何存储 ── {@link com.maidsmart.mixin.CombatWorkRangeMixin} 挂在 {@code EntityMaid.getRestrictRadius}（SRG {@code m_21535_}）的出口上：**正在接战时** 返回 {@code max(常态半径 × 2, 配置的 combatWorkRange)}，其余时候原样返回。因为 TLM 那一整套判据（{@code isWithinRestriction} 的圈内判定、寻路节点评估 MaidNodeEvaluator、上面那条传送阈值、各任务"离家多远该回去"）**全都读这一个值**， 一处改完就全线一致： <ul> <li>接战中：圈变大 → 不传送、不"走回去"、寻路也允许她追出去；<b>home 模式（有 SchedulePos 限制）除外</b>——那道圈是死规矩，接战也不扩（实测九百一十四）；</li> <li>战斗结束（威胁消失 / 目标清掉 / 挨打窗口过去）：读数自动落回常态半径， 该回家回家、该被传送传送——"回到常态再用正常设置的工作范围"，不需要任何收尾代码。</li> </ul> ── 为什么取 max 而不是直接改大 ── 玩家把 {@code scheduleActivityRange} 调到比本项更大时（想让她大范围干活）， 本项不该把圈**缩小**；只有常态半径比它小时才扩。 ── 为什么不碰客户端的圈 ── 客户端的圈靠同步的圈心/半径画（MaidAreaRenderEvent），而"在不在接战"依赖 Brain 记忆与攻击时间戳——客户端拿不到（记忆不同步、target 也不同步）。硬在客户端猜会画出 一个和服务端不一致的圈，所以客户端恒返回常态值（画出来的还是玩家设置的工作范围）。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return 0;
        }
    }

    /**
     * 圈半径的最终读数（mixin 的出口值）：接战中且配置开启 → 取大者，否则原样。
     * 实测九百一十三【接战翻倍】：接战时**传送判定半径直接翻倍**（{@code 常态 × 2}）——
     * 旧口径 {@code max(常态, 15)} 在常态半径 ≥15 时形同没扩，追出去照样被传送回来。
     * **home 模式（{@code hasRestriction}）恒原样（实测九百一十四）**：那道圈是死规矩，接战也不扩。
     * 「接管走位」关掉时战斗口径停用：只有本模组自有驱动（自保/空袭/扫帚/自由飞/喂目标）在开她时扩圈。
     * 任何异常 → 原样返回（这个 getter 被 TLM 各处高频调用，绝不能抛）。
     */
    public static float radius(EntityMaid maid, float normal) {
        try {
            if (maid == null || expandRadius() <= 0) {
                return normal;
            }
            if (maid.m_9236_() == null || maid.m_9236_().m_5776_()) {
                return normal; // 客户端：画出来的圈保持玩家设置（见类文档）
            }
            if (maid.m_21536_()) {
                return normal; // home：圈是最大活动范围，接战也不扩
            }
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TAKE_OVER_MOVEMENT.get()) {
                // 实测九百九十三【接管走位·关】：战斗口径的扩圈停用——只有本模组自有驱动
                //（自保/空袭/扫帚/自由飞/喂目标）在开她时才扩，其余全按常态半径
                return chainDriverActive(maid) ? Math.max(normal * 2f, expandRadius()) : normal;
            }
            return inCombat(maid) ? Math.max(normal * 2f, expandRadius()) : normal;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return normal;
        }
    }

    /**
     * 她"正在接战"吗——决定要不要临时扩圈（任一命中即算）：
     * <ol>
     *   <li><b>脑里有活动目标</b>：{@code ATTACK_TARGET} 记忆在、且目标还活着
     *       （TLM 攻击任务与我们的战术/空袭全走这条记忆，是"正在打"最准的信号）；</li>
     *   <li><b>实体层目标</b>：{@code Mob.getTarget()} 非空且活着（TLM/原版任务写的那份，
     *       与上面那条互为兜底——两边任一没写全都不影响判定）；</li> <li><b>自保中</b>：自保会话标记在（自保本身就是"在危险里"，此时更不该被圈拽回去）；</li> <li><b>刚交过手</b>：最近 {@value #RECENT_COMBAT_TICKS} tick 内她打过东西或挨过打 （覆盖"目标刚被打死/刚丢掉，人还在圈外"的那几秒——否则最后那一下必然触发传送）。</li> </ol> 全部只读，异常一律按"没在接战"处理（宁可少扩一次圈，也不要凭异常把圈放大）。
     */
    public static boolean inCombat(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_()) {
                    return true; // ATTACK_TARGET
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.m_5448_();
            if (mt != null && mt.m_6084_()) {
                return true; // Mob.getTarget
            }
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return true; // 自保会话中
            }
            int now = maid.f_19797_; // tickCount
            // 【初生女仆不算"刚交过手"】两个时间戳字段初值是 0，`now - 0 < 100` 在她出生的
            // 头 100 tick 里恒成立——不挡住的话，一只刚召出来（还带着工作圈）的女仆会被
            // 白扩 5 秒圈。加上"时间戳本身要非 0"这一条即可。
            if (now < RECENT_COMBAT_TICKS) {
                return false;
            }
            int lastHurt = maid.m_21213_();   // 最近挨打
            int lastAttack = maid.m_21215_(); // 最近打人
            return (lastHurt > 0 && now - lastHurt < RECENT_COMBAT_TICKS)
                    || (lastAttack > 0 && now - lastAttack < RECENT_COMBAT_TICKS);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return false;
        }
    }

    /** "刚交过手"的窗口（tick）：5 秒——目标丢掉/被打死后还有 5 秒不算"脱离战斗"，
     *  足够她走回圈内（走不回去也该被传送，那是常态半径的职责） */
    private static final int RECENT_COMBAT_TICKS = 100;

    /**
     * 实测九百九十一【接敌口径】：她自己**有攻击欲望**吗——脑里的 {@code ATTACK_TARGET}
     * 记忆或实体层 {@code getTarget()}，活着、且不是主人/友军（TLM 打雪仗会把主人写进
     * ATTACK_TARGET，那种"目标"不算接敌）。**不含**"最近 5 秒挨打/打人"这类事后窗口与
     * 自保标记——那些是 {@link #inCombat}（扩圈用）的口径，本方法只回答"她此刻在打谁"。
     */
    public static boolean hasAttackDesire(EntityMaid maid) {
        return attackDesireTarget(maid) != null;
    }

    /** 同 {@link #hasAttackDesire}，但把她此刻认的那个敌人一并给出（没有就 null）——
     *  敌袭语音（「有敌人靠近了！离我 X 格」）要用它报距离。 */
    public static net.minecraft.world.entity.LivingEntity attackDesireTarget(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_() && !FriendlyFireGuard.isFriendly(maid, t)) {
                    return t;
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.m_5448_();
            if (mt != null && mt.m_6084_() && !FriendlyFireGuard.isFriendly(maid, mt)) {
                return mt;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
        }
        return null;
    }

    /**
     * 实测九百九十一【接敌才接管】：本模组的**战斗驱动器**此刻在不在开她（= 走位独占是否正当）：
     * 战术在跑 / 自保在跑 / 喂的目标还在脑里 / 扫帚或空袭在空中 / 仿创造飞行在控。都是「真的在
     * 驱动」的瞬时状态、不含事后窗口——敌人一清、驱动器一停，判据当 tick 变 false，走位立即
     * 交还 TLM 原版。「接管走位」关掉时战斗腿（战术）直接不成立（见 MaidWorkTags），本判据
     * 只剩自有内容那几条腿——战斗扩圈也只在它们成立时保留（见 radius）。
     */
    public static boolean chainDriverActive(EntityMaid maid) {
        try {
            return MaidCombatTacticsBehavior.isActive(maid)
                    || maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)
                    || NeutralThreatDriver.drivingFedTarget(maid)
                    || MaidBroomKit.isBroomAirborne(maid)
                    || MaidFlightKit.isFlightAirborne(maid)
                    || com.maidsmart.flight.MaidFreeFlightController.isControlling(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return false;
        }
    }

    /**
     * 她"算在接战"的**第一条命中的依据**（诊断用：自检/排查"圈怎么变大了"时把这条打进日志）。
     * 返回：{@code ATTACK_TARGET} / {@code getTarget} / {@code 自保中} / {@code 最近挨打} /
     * {@code 最近打人} / {@code 无}。
     */
    public static String combatReason(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_()) {
                    return "ATTACK_TARGET";
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.m_5448_();
            if (mt != null && mt.m_6084_()) {
                return "getTarget";
            }
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return "自保中";
            }
            int now = maid.f_19797_;
            if (now < RECENT_COMBAT_TICKS) {
                return "无";
            }
            int lastHurt = maid.m_21213_();
            if (lastHurt > 0 && now - lastHurt < RECENT_COMBAT_TICKS) {
                return "最近挨打";
            }
            int lastAttack = maid.m_21215_();
            if (lastAttack > 0 && now - lastAttack < RECENT_COMBAT_TICKS) {
                return "最近打人";
            }
            return "无";
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return "?";
        }
    }

    /**
     * 与 {@link #combatReason} 同口径，但把"是谁"一并写出来（滞留探针用）：
     * {@code ATTACK_TARGET:Zombie} / {@code 最近挨打(1.2秒前):Wolf} / {@code 无}。
     * 只报实体类名——显示名要额外过一遍 Component 链，诊断里不需要。
     */
    public static String combatProbeDesc(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                net.minecraft.world.entity.LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_()) {
                    return "ATTACK_TARGET:" + t.getClass().getSimpleName();
                }
            }
            net.minecraft.world.entity.LivingEntity mt = maid.m_5448_();
            if (mt != null && mt.m_6084_()) {
                return "getTarget:" + mt.getClass().getSimpleName();
            }
            if (maid.getPersistentData().m_128471_(
                    com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return "自保中";
            }
            int now = maid.f_19797_;
            if (now < RECENT_COMBAT_TICKS) {
                return "无";
            }
            int lastHurt = maid.m_21213_();
            if (lastHurt > 0 && now - lastHurt < RECENT_COMBAT_TICKS) {
                return "最近挨打(" + secs(now - lastHurt) + "秒前):" + nameOf(maid.m_21188_());
            }
            int lastAttack = maid.m_21215_();
            if (lastAttack > 0 && now - lastAttack < RECENT_COMBAT_TICKS) {
                return "最近打人(" + secs(now - lastAttack) + "秒前):" + nameOf(maid.m_21214_());
            }
            return "无";
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("CombatWorkRange", ignored);
            return "?";
        }
    }

    /** 剩余窗口的秒数（一位小数）——诊断串用 */
    private static String secs(int ticks) {
        return String.valueOf(Math.round(ticks / 2.0) / 10.0);
    }

    /** 实体的类名（诊断串用，null 记 "null"；不碰显示名/类型注册表） */
    private static String nameOf(net.minecraft.world.entity.Entity e) {
        return e == null ? "null" : e.getClass().getSimpleName();
    }
}
