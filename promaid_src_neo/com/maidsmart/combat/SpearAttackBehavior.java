package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 攻击模式下的长矛冲锋：把 TLM 每 20 tick 的那一记平A换成持续蓄力；伤害/疲劳由
 * spearcore 的 {@code onUseTick} 自己结算，本行为只管举矛、走向与拉开。
 *
 * <p>【先打后撤】发现目标 → 举矛冲锋 → **造成伤害后**按原方向再冲 1 秒 → 套用既有
 * "边打边退"后撤段跑出范围 → 再压回去冲锋。全程保持 {@code startUsingItem}（长矛自己
 * 到时限收手也立刻续上，含后撤段）；仅目标丢失/死亡、离开 24 格脱战半径、切任务或
 * 关开关时才在 {@link #stop} 里收矛。
 */
public final class SpearAttackBehavior extends Behavior<EntityMaid> {

    /** 接敌/起蓄距离（格）——高版本持矛僵尸的同一口径 */
    private static final double ENGAGE_RANGE = 10.0;
    private static final double ENGAGE_SQR = ENGAGE_RANGE * ENGAGE_RANGE;
    /** 追击/后撤的脱战半径（格）——所有相位统一用它，避免 12 格边界把链路反复 stop/start */
    private static final double REENGAGE_RANGE = 24.0;
    private static final double REENGAGE_SQR = REENGAGE_RANGE * REENGAGE_RANGE;
    /** 追击基础速度（乘在导航速度上） */
    private static final double CHASE_SPEED = 1.0;
    /** 骑乘时的速度倍数 */
    private static final double MOUNTED_SPEED = 2.0;
    /** 命中后按原方向继续冲锋的时长（4 tick = 0.2 秒）：只让这一枪的矢量走完，
     *  随即后撤——旧版 1 秒会让玩家觉得"打中了还赖在脸上不走" */
    private static final int HIT_HOLD_TICKS = 4;
    /** 后撤至少走这么久，避免原地"后撤—再冲"闪一下 */
    private static final int RETREAT_MIN_TICKS = 10;
    /** 后撤硬上限（tick） */
    private static final int RETREAT_MAX_TICKS = 60;
    /** 后撤水平距离（格） */
    private static final double RETREAT_DISTANCE = 10.0;

    private enum Phase { OPENING, APPROACH, CHARGING, AFTER_HIT, RETREAT }

    private static final class State {
        Phase phase = Phase.APPROACH;
        int holdTicks;
        int retreatTicks;
        Vec3 chargeDir;
        Vec3 awayPos;
        int lastHurtStamp;
        boolean reengaging;
    }

    /** 行为实例由 TLM 的 core 行为表共享，每个女仆的状态按 UUID 分表 */
    private static final Map<UUID, State> STATES = new HashMap<>();

    public SpearAttackBehavior() {
        // 与其它常驻 core 行为同款：min/max 都取 MAX_VALUE，避免 tryStart 里
        // max-min+1 溢出成负数（0/MAX_VALUE 会抛 Bound must be positive）。
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return canStart(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        if (!isEnabled(maid)) {
            return false;
        }
        LivingEntity target = currentTarget(maid);
        if (target == null || !target.isAlive() || target.level() != level) {
            return false;
        }
        if (FriendlyFireGuard.isFriendly(maid, target)) {
            return false;
        }
        // 【实测一千〇二十三】所有相位统一 24 格脱战：旧版 CHARGING 只给 12 格，
        // 后撤/目标被击退到 12 格外就 stop，下一 tick 又重新 start 进 OPENING，
        // 实机表现就是"冲锋—自己解除—再拉开"的死循环。
        return maid.distanceToSqr(target) <= REENGAGE_SQR;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidFlightCombatBehavior.suppressVanillaMelee(maid);
        // TLM 的攻击任务在 start() 里把 DATA_ARM_RISE 置 true（MaidAttackStrafingTask），
        // 模型据此全程播放"举臂/挥臂"姿态——这不是 swing()，光拦 swing 拦不住。
        // 长矛蓄力期间每 tick 压回 false；真正命中那一下的摆臂由 spearcore 的二参 swing 放行。
        maid.setSwingingArms(false);
        State state = STATES.computeIfAbsent(maid.getUUID(), k -> new State());
        state.phase = Phase.APPROACH;
        state.holdTicks = 0;
        state.retreatTicks = 0;
        state.chargeDir = null;
        state.awayPos = null;
        state.reengaging = false;
        LivingEntity target = currentTarget(maid);
        if (target != null) {
            state.lastHurtStamp = target.getLastHurtByMobTimestamp();
            // 开局就贴脸（<10 格）：先举矛拉开，给长矛冲锋留出加速距离
            if (maid.distanceToSqr(target) < ENGAGE_SQR) {
                state.phase = Phase.OPENING;
                state.awayPos = retreatPos(maid, target);
                log(maid, "贴脸先拉开（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target))) + " 格）");
            } else {
                log(maid, "举矛起手（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target))) + " 格）");
            }
            SpearCoreCompat.claimCharge(maid);
            SpearCoreCompat.holdMaxCharge(maid);
        }
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        // 冲锋期间长矛是唯一攻击：TLM 的近战行为整条按住（否则它会 swing + doHurtTarget，
        // 既有挥臂动画又会把 spearcore 的冲锋伤害顶成普通平A）。
        MaidFlightCombatBehavior.suppressVanillaMelee(maid);
        // TLM 的攻击任务在 start() 里把 DATA_ARM_RISE 置 true（MaidAttackStrafingTask），
        // 模型据此全程播放"举臂/挥臂"姿态——这不是 swing()，光拦 swing 拦不住。
        // 长矛蓄力期间每 tick 压回 false；真正命中那一下的摆臂由 spearcore 的二参 swing 放行。
        maid.setSwingingArms(false);
        // 玩家把手槽里的矛拿走 / 换成另一把：先让原版 useItem 与主手对齐
        SpearCoreCompat.guardStaleUse(maid);
        LivingEntity target = currentTarget(maid);
        State state = STATES.computeIfAbsent(maid.getUUID(), k -> new State());
        if (target == null || !target.isAlive()) {
            SpearCoreCompat.stopCharge(maid);
            return;
        }
        // 吃喝优先：她正在用食物/药水时，本行为这一拍连朝向和寻路都不抢，让原版使用动作
        // 完整走完；结束后的下一 tick 再续矛。这就是"冲锋是并行链路，不是抢占"。
        if (maid.isUsingItem() && !SpearCoreCompat.isCharging(maid)) {
            return;
        }
        // 满冲锋：spearcore 到 damageEndTick 会自己收手；每 tick 把使用进度钉在伤害窗口末尾
        SpearCoreCompat.holdMaxCharge(maid);
        // 与长矛自己的结算同向：它只看视线与当前速度。
        // 【实测一千〇二十二】开局拉开 **不能**看目标：她一边朝目标看、一边往背离目标的
        // 方向寻路，移动控制每 tick 按身体朝向算速度 → 原地打转好几圈才冲。
        // 命中后撤段改为"倒退走"：身体/头保持面朝目标，位移用 strafeTo 倒着退，
        // 不再让导航按撤退方向掰身体（见 backpedal）。
        if (state.phase == Phase.OPENING || state.phase == Phase.RETREAT) {
            if (state.awayPos == null) {
                state.awayPos = retreatPos(maid, target);
            }
            if (state.phase == Phase.RETREAT && !maid.isPassenger()) {
                faceTarget(maid, target);
            } else {
                faceToward(maid, state.awayPos);
            }
        } else {
            maid.lookAt(target, 30.0F, 30.0F);
            maid.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        double speed = CHASE_SPEED * (maid.isPassenger() ? MOUNTED_SPEED : 1.0);

        // 只在"已经举矛冲锋"的段吃伤害事件：APPROACH 还没起手，别人打掉的血不算她命中；
        // 后撤/保持段里再被蹭到也不延长，否则会在原地反复折返
        if (state.phase == Phase.CHARGING) {
            if (tookDamage(maid, target, state)) {
                state.phase = Phase.AFTER_HIT;
                state.holdTicks = HIT_HOLD_TICKS;
                state.chargeDir = chargeDirection(maid, target);
                state.reengaging = false;
                log(maid, "长矛命中（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target)))
                        + " 格）→ 同向保持 1 秒后后撤");
            }
        }

        switch (state.phase) {
            case OPENING -> {
                // 开局贴脸：一边举矛一边退到 10 格外，再回头进入正常冲锋
                ensureCharging(maid);
                state.retreatTicks++;
                if (state.retreatTicks > RETREAT_MAX_TICKS) {
                    state.phase = Phase.APPROACH;
                    state.awayPos = null;
                    state.retreatTicks = 0;
                    state.reengaging = true;
                    return;
                }
                // 【实测一千〇二十二】后撤落点不可达（墙角 / 背后是墙）时，寻路会立刻 isDone，
                // 旧版要空转到 RETREAT_MAX_TICKS（3 秒）——玩家看到的就是"原地转好几圈"。
                // 1 秒还拉不开就直接掉头冲锋，别为了固定距离在原地耗。
                if (state.retreatTicks >= 20 && maid.getNavigation().isDone()
                        && maid.distanceToSqr(target) < ENGAGE_SQR) {
                    state.phase = Phase.APPROACH;
                    state.awayPos = null;
                    state.retreatTicks = 0;
                    state.reengaging = true;
                    return;
                }
                if (state.retreatTicks >= RETREAT_MIN_TICKS
                        && maid.distanceToSqr(target) >= ENGAGE_SQR) {
                    state.phase = Phase.APPROACH;
                    state.awayPos = null;
                    state.retreatTicks = 0;
                    state.reengaging = true;
                    return;
                }
                if (state.awayPos == null) {
                    state.awayPos = retreatPos(maid, target);
                }
                Vec3 away = state.awayPos;
                moveTo(maid, away, speed);
            }
            case APPROACH -> {
                // 后撤段刻意没收矛：回到 APPROACH 时她可能**还在蓄力**，此时直接算进入冲锋，
                // 不能等长矛自己到时限收手才切相位（否则要空等十几秒才重新统计命中）
                if (SpearCoreCompat.isCharging(maid) || SpearCoreCompat.claimCharge(maid)) {
                    state.phase = Phase.CHARGING;
                    state.reengaging = false;
                    // 从这里开始才算"本轮冲锋"：重置伤害基线，避免把起冲前挨的血算成她打中的
                    state.lastHurtStamp = target.getLastHurtByMobTimestamp();
                    SpearCoreCompat.holdMaxCharge(maid);
                    log(maid, "进入冲锋（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target))) + " 格）");
                }
                moveTo(maid, target.position(), speed);
            }
            case CHARGING -> {
                // 断开了就续：长矛自己到时限收手也立刻再举，全程巅峰冲锋
                ensureCharging(maid);
                moveTo(maid, target.position(), speed);
            }
            case AFTER_HIT -> {
                ensureCharging(maid);
                // 按命中那一刻的方向继续冲锋 1 秒（不是继续追着目标转向）
                Vec3 dir = state.chargeDir;
                if (dir == null) {
                    dir = chargeDirection(maid, target);
                }
                Vec3 anchor = maid.position().add(dir.scale(5.0));
                moveTo(maid, new Vec3(anchor.x, maid.getY(), anchor.z), speed);
                if (--state.holdTicks <= 0) {
                    beginRetreat(maid, target, state);
                }
            }
            case RETREAT -> {
                // 【关键】后撤不收矛：保持蓄力、继续朝目标看，跑出去的那一段也可能再擦中
                ensureCharging(maid);
                state.retreatTicks++;
                boolean escaped = maid.distanceToSqr(target) >= ENGAGE_SQR;
                // 落点被墙/地形截断、还没退到 10 格就走完了：换一个更远的落点继续退，
                // 不要停在原地等超时（那正是"打中了还赖在脸上"的另一半来源）。
                // 步行用"离落点已很近"代替 isDone（后撤段改走 STRAFE）；骑乘保持原导航口径。
                boolean nearAway = state.awayPos != null
                        && maid.position().distanceToSqr(state.awayPos) < 4.0;
                if (!escaped && state.retreatTicks >= RETREAT_MIN_TICKS
                        && (maid.isPassenger() ? maid.getNavigation().isDone() : nearAway)
                        && state.retreatTicks <= RETREAT_MAX_TICKS - 10) {
                    state.awayPos = retreatPos(maid, target);
                }
                boolean arrival = maid.isPassenger() ? maid.getNavigation().isDone() : nearAway;
                if (state.awayPos == null
                        || (state.retreatTicks >= RETREAT_MIN_TICKS && escaped && arrival)
                        || state.retreatTicks > RETREAT_MAX_TICKS) {
                    state.phase = Phase.APPROACH;
                    state.awayPos = null;
                    state.retreatTicks = 0;
                    state.reengaging = true;
                    return;
                }
                // 骑乘不变：坐骑位移由 RideBindManager 读导航终点，STRAFE 传不过去。
                if (maid.isPassenger()) {
                    moveTo(maid, state.awayPos, speed);
                } else {
                    backpedal(maid, target, state.awayPos);
                }
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        // 目标丢失 / 切任务 / 开关关闭：这才收矛
        SpearCoreCompat.stopCharge(maid);
        STATES.remove(maid.getUUID());
        maid.getNavigation().stop();
        // 骑乘时位移由坐骑执行：她自己的导航停了，坐骑那边也要收油，
        // 否则会出现"她已脱战、马还在按上一拍的目标跑"。
        try {
            if (maid.isPassenger() && maid.getVehicle() != null) {
                MaidRideKit.stopNavigation(maid.getVehicle());
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAttackBehavior", ignored);
        }
    }

    /**
     * 走位：她自己走路；骑在坐骑上时把同一个点同时喂给坐骑的寻路。
     *
     * <p>只写她自己的导航不够：坐骑的位移依赖 RideBindManager 每拍读她导航的**终点**再转达。
     * 一旦她这一拍的路被判 done / 终点为空（乘客身份下寻路重算失败、或两段走位之间的空隙），
     * 坐骑就收油原地站着——实机表现就是"打一下之后坐在马上不动"。
     * 这里直接喂坐骑，绕开那次转达的空窗。
     */
    private static void moveTo(EntityMaid maid, Vec3 point, double speed) {
        try {
            maid.getNavigation().moveTo(point.x, point.y, point.z, speed);
            if (maid.isPassenger() && maid.getVehicle() != null) {
                net.minecraft.world.entity.Entity mount = maid.getVehicle();
                MaidRideKit.feedNavigation(mount, point,
                        MaidRideKit.speedModifierFor(mount, maid), maid);
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAttackBehavior", ignored);
        }
    }

    /** 主手长矛 + 攻击模式 + 开关打开：任务/装备切换后本条链路立即让位。 */
    public static boolean isEnabled(EntityMaid maid) {
        try {
            if (maid == null || !MaidSmartConfig.COMBAT_SPEAR_CHARGE.get()) {
                return false;
            }
            if (!SpearCoreCompat.isSpear(maid.getMainHandItem())) {
                return false;
            }
            return maid.getTask() != null
                    && "touhou_little_maid:attack".equals(maid.getTask().getUid().toString());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAttackBehavior", ignored);
            return false;
        }
    }

    private static boolean canStart(EntityMaid maid) {
        if (!isEnabled(maid)) {
            return false;
        }
        LivingEntity target = currentTarget(maid);
        return target != null && target.isAlive()
                && !FriendlyFireGuard.isFriendly(maid, target)
                && maid.distanceToSqr(target) <= ENGAGE_SQR;
    }

    /**
     * 目标读取：先看 TLM 的 {@code getTarget()}，为空时回退到 brain 的 ATTACK_TARGET。
     * TLM 的索敌每 tick 会重写/擦除记忆，单看一个口会在两拍之间闪空——行为一旦 stop
     * 再 start 就会重进 OPENING，实机表现同样是"反复冲锋—拉开"。
     */
    private static LivingEntity currentTarget(EntityMaid maid) {
        try {
            LivingEntity target = maid.getTarget();
            if (target == null) {
                target = maid.getBrain()
                        .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
            }
            return target;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAttackBehavior", ignored);
            return maid.getTarget();
        }
    }

    /**
     * 本行为是否正在独占移动：源头取消 TLM 的走位目标，并让战术走位让位。
     * 只要状态表里还有她且任务/装备仍成立就算——后撤段同样不能被 TLM 拽回。
     */
    public static boolean takesOverMovement(EntityMaid maid) {
        return maid != null && STATES.containsKey(maid.getUUID()) && isEnabled(maid);
    }

    /** 持矛时那一记平A整记作废（挥砍动画与伤害都不走），伤害只由长矛冲锋结算。 */
    public static boolean suppressesMelee(EntityMaid maid) {
        // 目标读取必须和 tick 同口径：TLM 每 tick 会重写/擦除 getTarget，
        // 只读 getTarget 会漏掉"记忆里还有目标"的那几拍，挥臂就从缝里漏出来。
        LivingEntity target = maid == null ? null : currentTarget(maid);
        return isEnabled(maid) && target != null
                && target.isAlive() && !FriendlyFireGuard.isFriendly(maid, target);
    }

    /**
     * 续矛：主手空闲时立刻举矛；她正在吃喝则本 tick 跳过（claimCharge 不抢 useItem），
     * 原版使用动作结束后的下一 tick 自动续满。冲锋与吃喝因此是并行链路，不互相打断。
     */
    private static void ensureCharging(EntityMaid maid) {
        if (!SpearCoreCompat.isCharging(maid)) {
            SpearCoreCompat.claimCharge(maid);
        }
        SpearCoreCompat.holdMaxCharge(maid);
    }

    /**
     * 这一 tick 目标是否**被她的长矛**打中：只看原版受伤归因（spearcore 的矛伤伤害源就是她）。
     * 不再用"血量下降"兜底——别的怪/队友打掉的血也会降，旧版因此在她还没冲到时就误判命中、
     * 立刻转 AFTER_HIT 后撤。归因不是她时保持冲锋，下一 tick 继续判定。
     */
    private static boolean tookDamage(EntityMaid maid, LivingEntity target, State state) {
        int stamp = target.getLastHurtByMobTimestamp();
        boolean hit = stamp != state.lastHurtStamp && target.getLastHurtByMob() == maid;
        state.lastHurtStamp = stamp;
        return hit;
    }

    /** 命中那一刻的冲锋方向：优先用她当前实际位移，静止时才退回"她→目标"的水平方向。 */
    private static Vec3 chargeDirection(EntityMaid maid, LivingEntity target) {
        Vec3 velocity = maid.getDeltaMovement();
        Vec3 dir = new Vec3(velocity.x, 0.0, velocity.z);
        if (dir.lengthSqr() > 1.0E-4) {
            return dir.normalize();
        }
        dir = target.position().subtract(maid.position());
        dir = new Vec3(dir.x, 0.0, dir.z);
        if (dir.lengthSqr() < 1.0E-4) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        return dir.normalize();
    }

    /** 既有"边打边退"的后撤段：找一个背离目标、可站立的落点。 **不收矛**。 */
    private static void beginRetreat(EntityMaid maid, LivingEntity target, State state) {
        state.phase = Phase.RETREAT;
        state.retreatTicks = 0;
        state.chargeDir = null;
        state.awayPos = retreatPos(maid, target);
        log(maid, "后撤拉开（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target))) + " 格）");
    }

    /** 面朝本次移动的落点（不是目标）——开局拉开用它，避免"看目标 + 往反方向走"原地打转。 */
    private static void faceToward(EntityMaid maid, Vec3 point) {
        double dx = point.x - maid.getX();
        double dz = point.z - maid.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        maid.setYRot(yaw);
        maid.setYHeadRot(yaw);
        maid.yRotO = yaw;
        maid.getLookControl().setLookAt(point.x, maid.getEyeY(), point.z, 360.0f, 360.0f);
    }

    /** 命中后撤：身体与头都锁在敌人身上（倒退走由 {@link #backpedal} 负责）。 */
    private static void faceTarget(EntityMaid maid, LivingEntity target) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        maid.setYRot(yaw);
        maid.setYBodyRot(yaw);
        maid.setYHeadRot(yaw);
        maid.yRotO = yaw;
        maid.getLookControl().setLookAt(target, 360.0f, 360.0f);
    }

    /**
     * 倒着退向 away：不再让导航按撤退方向掰身体（那会把刚锁到敌人身上的朝向拧走），
     * 改用 MoveControl 的 STRAFE——身体朝敌人、输入反向，走出来就是"面朝敌人后撤"。
     */
    private static void backpedal(EntityMaid maid, LivingEntity target, Vec3 away) {
        Vec3 dir = new Vec3(away.x - maid.getX(), 0.0, away.z - maid.getZ());
        if (dir.lengthSqr() < 1.0E-4) {
            maid.getNavigation().stop();
            return;
        }
        dir = dir.normalize();
        Vec3 fwd = new Vec3(target.getX() - maid.getX(), 0.0, target.getZ() - maid.getZ());
        if (fwd.lengthSqr() < 1.0E-4) {
            fwd = new Vec3(0.0, 0.0, 1.0);
        } else {
            fwd = fwd.normalize();
        }
        // 世界方向 → 身体坐标系：forward 朝敌人为正（撤退时是负值），strafe 右侧为正。
        double forward = dir.x * fwd.x + dir.z * fwd.z;
        double strafe = dir.x * (-fwd.z) + dir.z * fwd.x;
        maid.getNavigation().stop(); // 清掉旧路径，免得 PathNavigation 再喂 MOVE_TO 把朝向掰走
        maid.getMoveControl().strafe((float) forward, (float) strafe);
    }

    /** 背离目标的落点；找不到可行落点时退回"背离方向 10 格"。 */
    private static Vec3 retreatPos(EntityMaid maid, LivingEntity target) {
        int bonus = maid.isPassenger() ? 2 : 0;
        Vec3 away = LandRandomPos.getPosAway(maid, 10 + bonus, 7, target.position());
        if (away != null) {
            return away;
        }
        Vec3 dir = maid.position().subtract(target.position());
        if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(1.0, 0.0, 0.0);
        }
        return maid.position().add(dir.normalize().scale(RETREAT_DISTANCE + bonus));
    }

    /** 任务切换/传送/卸下时的状态清理（蓄力本身由调用方先 stopCharge）。 */
    public static void forget(UUID maidId) {
        if (maidId != null) {
            STATES.remove(maidId);
        }
    }

    private static void log(EntityMaid maid, String message) {
        try {
            com.maidsmart.tool.PromaidLog.log("长矛冲锋",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + message);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAttackBehavior", ignored);
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    public static void clearAll() {
        STATES.clear();
    }
}
