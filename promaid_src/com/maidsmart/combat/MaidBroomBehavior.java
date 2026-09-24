package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.3.0「扫帚模式」的行为（两树镜像）——骑扫帚、悬停、凋灵式盘旋、照搬远程空袭开火。
 *
 * ── 一件必须说清的事：她**不会**退化成地面近战 ──
 * 空袭在缺件时会退回"和普通攻击模式一致"的地面近战（那是空袭的既有设计）。扫帚模式
 * **不注册 TLM 的 {@code MaidMeleeAttack}**，所以缺件时她是"原地待命 + 气泡报告缺什么"，
 * 不会自己冲上去挥拳头。这是刻意的第一批取舍：把"未激活时怎么打"留到下一批做
 * （要么接一条地面近战回退、要么干脆让她切回普通攻击任务），免得在这一批里把
 * "骑乘 + 盘旋 + 开火"这条主链路和另一条战斗链路的交互一起搅进来。
 * 因此缺件气泡里**不会**写"先按普通战斗来"——那句话在这里会是假的。
 *
 * ── 为什么行为本身不移动她 ──
 * 她骑在扫帚上是**乘客**：TLM {@code EntityMaid.canBrainMoving()} 在 {@code isPassenger()}
 * 时为 false，她自己那套寻路/巡逻/范围约束整条失效，动她的是载具。所以这里只做三件事：
 * 决定"去哪"（{@link MaidBroomDrive}）、决定"打谁"、以及"骑上/下来"。
 * 真正每 tick 调 {@code move()} 的是 {@link com.maidsmart.mixin.EntityBroomMaidTravelMixin}。
 *
 * ── 开火 ──
 * 走 {@link MaidFlightCombatBehavior#fireRanged}——**与远程空袭同一个方法**（射程/视线/
 * 冷却/枪械换弹瞄准全部同款），不是抄一份。用户原话："手上武器的运作直接照搬远程空袭模式"。
 */
public class MaidBroomBehavior extends Behavior<EntityMaid> {

    public MaidBroomBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 缺件播报（形状照搬空袭的 notifyNotReady） ==================== */

    /** 第一次见到这具女仆实体的 gameTime（入世界宽限用） */
    private static final Map<EntityMaid, Long> FIRST_SEEN =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 入世界后的静默窗口（tick）：0.5 秒——刚放出来那一拍她的背包/饰品数据还在就绪中 */
    private static final long GRACE_TICKS = 10;
    /** 缺件必须**连续**成立这么久才播报、齐备也必须连续成立这么久才清冷却（防抖动刷屏） */
    private static final long STABLE_TICKS = 40;
    /** 同一只女仆两条播报之间的最短间隔（tick）= 15 秒 */
    private static final int NOTIFY_COOLDOWN = 300;
    private static final Map<EntityMaid, Long> MISSING_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EntityMaid, Long> COMPLETE_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<EntityMaid, Long> NOTIFY_READY =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ==================== 行为生命周期 ==================== */

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        return MaidBroomKit.isBroomTask(maid);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidBroomKit.isBroomTask(maid) && maid.m_6084_();
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 换任务 / 行为结束 / 她死了：一定要下来并把这件扫帚还回去，绝不能留一把孤儿扫帚漂在天上
        MaidBroomDrive.dismount(maid);
        MaidBroomDrive.forgetMaid(maid.m_20148_());
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.m_6084_()) {
            return;
        }
        // ① 未激活（缺扫帚 / 缺远程武器 / 缺弹药 / 总开关关了）→ 下来、报缺件
        if (!MaidBroomKit.isModeActive(maid)) {
            MaidBroomDrive.dismount(maid);
            notifyNotReady(maid, gameTime);
            return;
        }
        // ② 骑上（身上有扫帚物品就取出来放一把；已经骑着就原样返回）
        EntityBroom broom = MaidBroomDrive.ensureMounted(level, maid);
        if (broom == null) {
            notifyNotReady(maid, gameTime);
            return;
        }
        clearNotReady(maid, gameTime);

        // ②.5 起飞相位：先垂直抬起 1 格（玩家原话"女仆会立刻用扫帚飞起来 1 格"），
        // 抬到位再开始"去哪"。这一段不转向、不开火——就是那一下"腾空"的动作。
        Double riseY = MaidBroomDrive.riseTarget(broom, maid.m_20186_());
        if (riseY != null) {
            MaidBroomDrive.steerTo(maid, new Vec3(maid.m_20185_(), riseY, maid.m_20189_()));
            return;
        }

        // ③ 有目标 → 凋灵式盘旋 + 照搬远程空袭开火
        LivingEntity target = currentTarget(maid);
        if (target != null && target.m_6084_() && target.m_9236_() == level) {
            faceTarget(maid, target);
            MaidBroomDrive.steerTo(maid, MaidBroomDrive.combatPoint(maid, target));
            // 朝向改成"看着目标"而不是"朝着速度方向"：她在绕着目标侧移，脸得对着它才像在射击
            MaidBroomDrive.faceYaw(broom, yawTo(maid, target));
            MaidFlightCombatBehavior.fireRanged(maid, target, maid.m_20148_(), gameTime);
            return;
        }

        // ④ 没目标 → 平时：悬停在主人身边（配置可关；关掉就原地悬停待命）
        LivingEntity owner = ownerOf(maid);
        if (followEnabled() && owner != null && owner.m_6084_() && owner.m_9236_() == level) {
            faceTarget(maid, owner);
            MaidBroomDrive.steerTo(maid, MaidBroomDrive.followPoint(maid, owner));
            MaidBroomDrive.faceYaw(broom, yawTo(maid, owner));
        } else {
            // 原地悬停：目标点就是当前位置 → steerTo 走到"到点阻尼"那一支，速度很快收干
            MaidBroomDrive.steerTo(maid, new Vec3(maid.m_20185_(), maid.m_20186_(), maid.m_20189_()));
        }
    }

    /* ==================== 目标 / 朝向 ==================== */

    /** 当前攻击目标：优先脑里的 ATTACK_TARGET（TLM 攻击任务与我们的行为都写这一条），其次实体层 target */
    private static LivingEntity currentTarget(EntityMaid maid) {
        try {
            var brain = maid.m_6274_();
            if (brain != null) {
                LivingEntity t = brain
                        .m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_)
                        .orElse(null);
                if (t != null && t.m_6084_()) {
                    return t;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.m_5448_();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LivingEntity ownerOf(EntityMaid maid) {
        try {
            // 先落到 Entity 再判：TLM 的 getOwner() 在两树的返回类型不同（一版是 Player 语义、
            // 一版直接是 LivingEntity），用中间变量后 `instanceof` 在两棵树里都是**有条件**模式
            // ——否则 forge 树的 --release 17 会以"无条件模式"直接编译失败。
            net.minecraft.world.entity.Entity o = maid.m_269323_();
            return o instanceof LivingEntity le ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * MC 的 yaw 约定：朝向 (dx, dz) 的偏航角 = {@code -atan2(dx, dz)} 换算成度——
     * 与凋灵那行 {@code setYRot(-(float)Mth.atan2(d.x, d.z) * 57.295776F)} 同一个公式。
     */
    private static float yawTo(EntityMaid maid, LivingEntity target) {
        return (float) (-Math.atan2(target.m_20185_() - maid.m_20185_(), target.m_20189_() - maid.m_20189_()) * 57.295776F);
    }

    /**
     * 让她**看着**目标（纯表现：子弹方向一直是按坐标算的，不看她的朝哪）。
     *
     * 【为什么两只都设】她骑在扫帚上，而 {@code EntityBroom} 本身是 {@code LivingEntity}——
     * MC 对"载具是 LivingEntity 的乘客"有一套"身体朝向跟着载具"的逻辑，所以光设她的 yRot
     * 可能每 tick 被覆写回去。**载具的朝向**由 {@link MaidBroomDrive#faceYaw} 设（那一份是稳的），
     * 这里再补她的偏航与俯仰：俯仰（抬头/低头看目标）只有她自己的 XRot 能表达。
     */
    private static void faceTarget(EntityMaid maid, LivingEntity target) {
        try {
            double dx = target.m_20185_() - maid.m_20185_();
            double dz = target.m_20189_() - maid.m_20189_();
            double dy = (target.m_20186_() + 1.0) - (maid.m_20186_() + 1.0);
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (-Math.atan2(dx, dz) * 57.295776F);
            float pitch = (float) (-Math.atan2(dy, Math.max(horiz, 1.0E-4)) * 57.295776F);
            maid.m_146922_(yaw);
            maid.m_146926_(pitch);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 缺件播报 ==================== */

    /**
     * 缺件时给她头顶一条气泡——**形状与空袭的 {@code MaidFlightCombatBehavior.notifyNotReady}
     * 完全一致**（入世界宽限 0.5 秒 → 缺件要连续站稳 2 秒才报 → 15 秒冷却 → 齐备也要站稳 2 秒
     * 才把冷却清零）。理由与空袭那边一字不差：我们自己的动作表现会把副手那件借走十几 tick，
     * 若"看到一拍就报/就清冷却"，气泡会每 10 秒一条、永远不停（空袭那边吃过的亏）。
     */
    private static void notifyNotReady(EntityMaid maid, long gameTime) {
        try {
            String missing = MaidBroomKit.missingParts(maid);
            if (missing == null) {
                // 判定竞态：其实是齐的 → 不误报（并让"齐备"重新计时）
                MISSING_SINCE.remove(maid);
                if (COMPLETE_SINCE.putIfAbsent(maid, gameTime) == null) {
                    return;
                }
                Long cSince = COMPLETE_SINCE.get(maid);
                if (cSince != null && gameTime - cSince >= STABLE_TICKS) {
                    COMPLETE_SINCE.remove(maid);
                    NOTIFY_READY.remove(maid);
                }
                return;
            }
            COMPLETE_SINCE.remove(maid);
            Long firstSeen = FIRST_SEEN.get(maid);
            if (firstSeen == null) {
                FIRST_SEEN.put(maid, gameTime);
                return;
            }
            if (gameTime - firstSeen < GRACE_TICKS) {
                return;
            }
            if (MISSING_SINCE.putIfAbsent(maid, gameTime) == null) {
                return;
            }
            Long mSince = MISSING_SINCE.get(maid);
            if (mSince != null && gameTime - mSince < STABLE_TICKS) {
                return;
            }
            Long ready = NOTIFY_READY.get(maid);
            if (ready != null && gameTime < ready) {
                return;
            }
            NOTIFY_READY.put(maid, gameTime + NOTIFY_COOLDOWN);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 扫帚模式缺件：" + missing + "（原地待命中）");
            maid.getChatBubbleManager().addTextChatBubble("扫帚模式还差" + missing + "，先待着不动");
        } catch (Throwable ignored) {
        }
    }

    /** 齐备了：清缺件计时（冷却的清零走 notifyNotReady 里"齐备也要站稳"那条） */
    private static void clearNotReady(EntityMaid maid, long gameTime) {
        MISSING_SINCE.remove(maid);
    }

    private static boolean followEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_FOLLOW.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
