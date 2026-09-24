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
 * v1.3.0「扫帚模式」的行为（两树镜像）——骑扫帚、起飞悬停、接敌爬升、盘旋、照搬远程空袭开火。
 *
 * ── 每 tick 的相位（实测六百五十六，按玩家原话定的顺序）──
 * <pre>
 *   ① 缺件        → 下扫帚 + 气泡报缺什么（原地待命，不退化成地面近战，见下）
 *   ② 骑上        → 从她背包取一把扫帚放出来骑上（已骑着就跳过；她坐在椅子上也能换过来，
 *                    走 force 骑乘——实测六百五十七）
 *   ②.4 玩家在开  → 让位：只开火，不写推进意图、不开爬升相位（驾驶权在玩家，见 drivenByPlayer）
 *   ②.5 起飞相位  → 原地往上抬 1 格（头顶顶住就悬停在此处）
 *   ③ 接敌        → 先向上爬 8 格（顶住就悬停在此处）→ 再绕着她盘旋 + 开火
 *   ④ 平时        → 按"飞行跟随"同款的起手/收手距离跟主人（默认开；扫帚没耐久）
 * </pre>
 * 两个爬升相位与"怎么飞"全部在 {@link MaidBroomDrive}（速度公式照搬 TLM 给玩家驾驶写的
 * {@code PlayerBroomControl}，一分不加）。
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
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidBroomKit.isBroomTask(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidBroomKit.isBroomTask(maid) && maid.isAlive();
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        // 换任务 / 行为结束 / 她死了：一定要下来并把这件扫帚还回去，绝不能留一把孤儿扫帚漂在天上
        MaidBroomDrive.dismount(maid);
        MaidBroomDrive.forgetMaid(maid.getUUID());
        FOLLOWING.remove(maid);
        PLAYER_DRIVE_LOGGED.remove(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid == null || !maid.isAlive()) {
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

        // ②.4 玩家在开这把扫帚 → 我们只开火，不碰飞行（见 drivenByPlayer 的注释：
        // mixin 那边"有玩家驾驶就一个字不改"，这里跟着让位，否则每 tick 都会写一份没人用的
        // 推进意图、还会把爬升相位开起来每 tick 判"顶头"刷日志）
        if (MaidBroomDrive.drivenByPlayer(broom)) {
            MaidBroomDrive.clearClimb(maid);
            LivingEntity driven = currentTarget(maid);
            if (driven != null && driven.isAlive() && driven.level() == level) {
                MaidBroomDrive.faceYaw(broom, yawTo(maid, driven));
                MaidFlightCombatBehavior.fireRanged(maid, driven, maid.getUUID(), gameTime);
            }
            notePlayerDriving(maid, gameTime);
            return;
        }

        // ②.5 起飞相位：原地往上抬 1 格再到别的地方去（玩家原话"如果拿到了扫帚，原地往上飞 1 格
        // 悬停（头顶如果被顶住了那就悬停在此处）"）。这一段不转向、不开火——就是那一下"腾空"。
        Double riseY = MaidBroomDrive.takeoffTarget(maid);
        if (riseY != null) {
            MaidBroomDrive.steerTo(maid, new Vec3(maid.getX(), riseY, maid.getZ()));
            return;
        }

        // ③ 有目标 → 先向上爬 8 格（顶头即就地悬停）→ 再绕着敌人盘旋 + 照搬远程空袭开火
        LivingEntity target = currentTarget(maid);
        if (target != null && target.isAlive() && target.level() == level) {
            faceTarget(maid, target);
            // 朝向改成"看着目标"而不是"朝着速度方向"：她在绕着目标侧移，脸得对着它才像在射击
            MaidBroomDrive.faceYaw(broom, yawTo(maid, target));
            Double climbY = MaidBroomDrive.combatClimbTarget(maid, target);
            if (climbY != null) {
                MaidBroomDrive.steerTo(maid, new Vec3(maid.getX(), climbY, maid.getZ()));
                MaidFlightCombatBehavior.fireRanged(maid, target, maid.getUUID(), gameTime);
                return;
            }
            MaidBroomDrive.steerTo(maid, MaidBroomDrive.combatPoint(maid, target));
            MaidBroomDrive.faceYaw(broom, yawTo(maid, target));
            MaidFlightCombatBehavior.fireRanged(maid, target, maid.getUUID(), gameTime);
            return;
        }

        // ④ 没目标 → 平时：悬停在主人身边（配置可关；关掉就原地悬停待命）
        MaidBroomDrive.clearClimb(maid); // 打完/丢目标 → 爬升相位作废，下次接敌重新爬
        LivingEntity owner = ownerOf(maid);
        if (followEnabled() && owner != null && owner.isAlive() && owner.level() == level) {
            faceTarget(maid, owner);
            MaidBroomDrive.faceYaw(broom, yawTo(maid, owner));
            if (shouldFollow(maid, owner)) {
                MaidBroomDrive.steerTo(maid, MaidBroomDrive.followPoint(maid, owner));
            } else {
                // 迟滞带内：留在原地悬停（不必为了贴住主人一直微调位置）
                MaidBroomDrive.steerTo(maid, new Vec3(maid.getX(), maid.getY(), maid.getZ()));
            }
        } else {
            // 原地悬停：目标点就是当前位置 → steerTo 走到"到点"那一支，速度收干
            MaidBroomDrive.steerTo(maid, new Vec3(maid.getX(), maid.getY(), maid.getZ()));
        }
    }

    /* ==================== 平时跟随：与"飞行跟随"同款的起手/收手迟滞 ==================== */

    /** 这只女仆当前是否处于"跟随主人这一趟"里（迟滞带内不再起飞） */
    private static final Map<EntityMaid, Boolean> FOLLOWING =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 平时要不要飞过去跟主人——**照飞行跟随的同款机制**（玩家原话："引用飞行跟随的逻辑以及
     * 同款机制来决定是否启动跟随（这个是默认开启的，因为扫帚没有耐久）"）。
     *
     * <p>同款机制 = 那一对**起手 / 收手距离**（{@code flightFollow.start / end}，默认 25 / 5）：
     * 主人远过起手距离才开始跟，进到收手距离内就停下悬停。起手与收手是两个不同的球，
     * 中间那段就是迟滞带——没有它她会在阈值上"动一下停一下"地抖。
     * 这里**直接读同一对配置**，不另开一套数字：口径只有一处（本模组反复强调的红线）。
     *
     * <p>与飞行跟随的区别只剩"谁来飞"：那边要鞘翅 + 烟花且默认关（会烧料、磨耐久），
     * 这边是扫帚、没有耐久，所以开关是 {@code combat.broomFollow}（默认开）。
     */
    private static boolean shouldFollow(EntityMaid maid, LivingEntity owner) {
        double d = maid.distanceTo(owner);
        double start = followDistCfg();
        double end = Math.min(followEndDistCfg(), Math.max(1.0, start - 1.0));
        boolean following = Boolean.TRUE.equals(FOLLOWING.get(maid));
        if (!following && d > start) {
            following = true;
        } else if (following && d <= end) {
            following = false;
        }
        FOLLOWING.put(maid, following);
        return following;
    }

    private static double followDistCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.FLIGHT_FOLLOW_DIST.get();
        } catch (Throwable ignored) {
            return 25.0;
        }
    }

    private static double followEndDistCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.FLIGHT_FOLLOW_END_DIST.get();
        } catch (Throwable ignored) {
            return 5.0;
        }
    }

    /* ==================== 目标 / 朝向 ==================== */

    /** 当前攻击目标：优先脑里的 ATTACK_TARGET（TLM 攻击任务与我们的行为都写这一条），其次实体层 target */
    private static LivingEntity currentTarget(EntityMaid maid) {
        try {
            var brain = maid.getBrain();
            if (brain != null) {
                LivingEntity t = brain
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
                if (t != null && t.isAlive()) {
                    return t;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.getTarget();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LivingEntity ownerOf(EntityMaid maid) {
        try {
            // 先落到 Entity 再判：TLM 的 getOwner() 在两树的返回类型不同（一版是 Player 语义、
            // 一版直接是 LivingEntity），用中间变量后 `instanceof` 在两棵树里都是**有条件**模式
            // ——否则 forge 树的 --release 17 会以"无条件模式"直接编译失败。
            net.minecraft.world.entity.Entity o = maid.getOwner();
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
        return (float) (-Math.atan2(target.getX() - maid.getX(), target.getZ() - maid.getZ()) * 57.295776F);
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
            double dx = target.getX() - maid.getX();
            double dz = target.getZ() - maid.getZ();
            double dy = (target.getY() + 1.0) - (maid.getY() + 1.0);
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (-Math.atan2(dx, dz) * 57.295776F);
            float pitch = (float) (-Math.atan2(dy, Math.max(horiz, 1.0E-4)) * 57.295776F);
            maid.setYRot(yaw);
            maid.setXRot(pitch);
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

    /**
     * 玩家在开这把扫帚 → 15 秒最多一条日志（同 {@link #NOTIFY_COOLDOWN} 的节奏）。
     * <p>
     * 这是**必须能看见**的一种状态：此时她会正常开火，但**永远不会自己飞**（驾驶权在玩家），
     * 玩家看到的却是"女仆骑在扫帚上却不跟着我飞/不升空"。旧版这种情况一个字都不打，
     * 只能靠猜。现在留痕：日志搜「玩家在驾驶」。
     */
    private static void notePlayerDriving(EntityMaid maid, long gameTime) {
        try {
            Long ready = PLAYER_DRIVE_LOGGED.get(maid);
            if (ready != null && gameTime < ready) {
                return;
            }
            PLAYER_DRIVE_LOGGED.put(maid, gameTime + NOTIFY_COOLDOWN);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 玩家在驾驶这把扫帚 → 扫帚模式只负责开火、不接管飞行（想让她自己飞，请让玩家下扫帚）");
        } catch (Throwable ignored) {
        }
    }

    /** 女仆 → 「玩家在驾驶」这条日志的下次可打时间（tick） */
    private static final Map<EntityMaid, Long> PLAYER_DRIVE_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean followEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_FOLLOW.get();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
