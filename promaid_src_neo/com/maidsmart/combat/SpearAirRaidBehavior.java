package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 【R1080】鞘翅长矛：像重锤猛击一样单开的近战空袭链路。
 *
 * <p>接敌前与通用近战空袭完全一致（平地走路、索敌半径、目标判定都不变）。接敌后
 * 先借用通用空袭的起飞方式（烟花 / 羽扇 / 激流 / 自推鞘翅，消耗照旧），起飞 0.5 秒
 * 后整体切换成幻翼的攻击方式与状态机：环绕目标上方 → 俯冲 → 平A 换成长矛冲锋。
 *
 * <p>与幻翼唯一的区别：幻翼一次攻击后就飞离，女仆命中后解除幻翼态，改为普通水平
 * 鞘翅飞行并顺着惯性继续冲锋 1 秒（撞到敌人仍按长矛结算）；1 秒后解除飞行，若敌人
 * 还在就回到链路开头重新起飞。进入幻翼态的那一拍把速度写成"起飞后 0.5 秒的实际
 * 速度"，之后每 tick 衰减 0.1%、最多衰减 50%。
 *
 * <p>壮丽鞘翅 / 孔雀羽扇这类可飞行物只作为"能不能起飞"的启用条件，不改变链路；
 * 冲锋前的额外加速只有烟花火箭才会触发。鞘翅耐久完全交给原版滑翔扣耐久。
 */
public final class SpearAirRaidBehavior extends Behavior<EntityMaid> {

    private enum Phase {
        /** 已观察到通用链路点火，等待"起飞后 0.5 秒" */
        WAIT_TAKEOFF,
        /** 幻翼态·环绕 */
        CIRCLE,
        /** 幻翼态·俯冲 */
        SWOOP,
        /** 命中后的水平惯性冲锋 */
        POST
    }

    private static final class State {
        Phase phase = Phase.WAIT_TAKEOFF;
        int age;
        int ticks;
        /** 已经真正接管（通用空袭让位）；WAIT_TAKEOFF 期间为 false */
        boolean active;
        boolean done;
        boolean handoff;
        /** 本轮俯冲是否已经吃过"冲锋前烟花加速" */
        boolean boosted;
        /** 进入幻翼态时写入的速度（格/tick） */
        double speed;
        /** 速度下限 = 进入时的速度 × (1 - 最大衰减) */
        double speedFloor;
        int lastHurtStamp;
        Vec3 anchor = Vec3.ZERO;
        Vec3 moveTarget = Vec3.ZERO;
        double circleAngle;
        double circleDistance;
        double circleHeight;
        float circleClockwise = 1.0f;
        int nextSweepTicks;
        int swoopTicks;
        double prevX;
        double prevY;
        double prevZ;
        boolean hasPrev;
        final Set<UUID> pathHits = new HashSet<>();
        String reason = "";
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Set<UUID> ACTIVE = new HashSet<>();

    /** 等待离地的兜底（tick）：起飞窗通常 30 tick，留足余量 */
    private static final int TAKEOFF_WAIT_MAX = 60;
    /** 幻翼环绕：锚点高于目标 16~32 格（R1083 压低：俯冲距离短一点、轮次更快） */
    private static final int CIRCLE_ANCHOR_MIN = 16;
    private static final int CIRCLE_ANCHOR_SPAN = 16;
    /** 幻翼环绕：半径 4~12 格、高度 -4~+5 格 */
    private static final float CIRCLE_RADIUS_MIN = 4.0f;
    private static final float CIRCLE_RADIUS_SPAN = 8.0f;
    private static final float CIRCLE_HEIGHT_BASE = -4.0f;
    private static final float CIRCLE_HEIGHT_SPAN = 9.0f;
    /** 环绕参数随机变化间隔（tick，与幻翼同值） */
    private static final int CIRCLE_HEIGHT_TICKS = 350;
    private static final int CIRCLE_RADIUS_TICKS = 250;
    private static final int CIRCLE_ANGLE_TICKS = 450;
    /** 环绕多久后转入下一次俯冲（tick）：幻翼 = 10；R1083 收紧到 6 = 提高冲撞频率 */
    private static final int FIRST_SWEEP_TICKS = 6;
    /** 俯冲前至少要比目标高这么多格（格）；R1083 8 → 6（够扎下去、少爬一点） */
    private static final double MIN_SWEEP_CLIMB = 6.0;
    /** 爬高兜底（tick）：R1083 5 秒 → 4 秒，别无限绕圈 */
    private static final int CLIMB_TIMEOUT_TICKS = 80;
    /** 俯冲撞上目标的判定外扩（格）：幻翼 = 0.2 */
    private static final double SWEEP_INFLATE = 0.2;
    /** 路径碰撞补判外扩（格）：机体路径及周边 3×3×3 */
    private static final double PATH_HIT_INFLATE = 1.0;
    /** 目标保持半径（格）：与通用近战空袭同口径 */
    private static final double TARGET_RANGE = 128.0;

    public SpearAirRaidBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** 本链路是否正在接管这只女仆（通用空袭 / 战术 / 举盾 / 牵引绳据此让位）。 */
    public static boolean isActive(EntityMaid maid) {
        return maid != null && ACTIVE.contains(maid.getUUID());
    }

    /** 配置、任务、主手长矛都成立（不要求当前有目标 / 已经起飞）。 */
    public static boolean isConfigured(EntityMaid maid) {
        try {
            return maid != null
                    && MaidSmartConfig.COMBAT_SPEAR_AIR_RAID.get()
                    && MaidSmartConfig.COMBAT_FLIGHT_MODE.get()
                    && isMeleeFlightTask(maid)
                    && SpearCoreCompat.isSpear(maid.getMainHandItem());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return false;
        }
    }

    private static boolean isMeleeFlightTask(EntityMaid maid) {
        try {
            return maid.getTask() != null && MaidFlightKit.UID.equals(maid.getTask().getUid());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return false;
        }
    }

    /* ---------------- 行为骨架 ---------------- */

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (!isConfigured(maid) || maid.isSleeping() || maid.isPassenger()
                || ACTIVE.contains(maid.getUUID()) || STATES.containsKey(maid.getUUID())) {
            return false;
        }
        // 只认通用空袭刚刚点过火：没有点火记录（或已经是上一轮）就不抢
        long age = MaidFlightCombatBehavior.launchAge(maid, level.getGameTime());
        if (age < 0 || age > takeoffDelay() + 20L) {
            return false;
        }
        if (!MaidFlightCombatBehavior.isLaunchActive(maid) && !maid.isFallFlying()) {
            return false;
        }
        MaidFlightKit.equip(maid);
        if (!SpearCoreCompat.isSpear(maid.getMainHandItem()) || !MaidFlightKit.isModeActive(maid)) {
            return false;
        }
        return resolveTarget(maid) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        State state = STATES.get(maid.getUUID());
        if (!isConfigured(maid) || maid.isSleeping() || maid.isPassenger() || state == null || state.done) {
            markReason(maid, "配置/任务/装备失效");
            return false;
        }
        if (state.phase != Phase.WAIT_TAKEOFF && !MaidFlightKit.isModeActive(maid)) {
            markReason(maid, "长矛/飞行三件套失效");
            return false;
        }
        if (state.phase != Phase.WAIT_TAKEOFF && state.phase != Phase.POST && maid.onGround()) {
            markReason(maid, "幻翼态触地");
            return false;
        }
        if (resolveTarget(maid) == null) {
            markReason(maid, "目标丢失或超出 " + (int) TARGET_RANGE + " 格");
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        State state = new State();
        state.phase = Phase.WAIT_TAKEOFF;
        STATES.put(maid.getUUID(), state);
        MaidFlightKit.equip(maid);
        log(maid, "通用空袭已点火，等待 " + takeoffDelay() + " tick（"
                + fmt(takeoffDelay() / 20.0) + " 秒）后切换幻翼态");
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        State state = STATES.get(id);
        if (state == null) {
            return;
        }
        state.age++;
        if (!state.active) {
            tickWaitTakeoff(level, maid, state);
            return;
        }
        if (MaidHeatEscape.tick(maid)) {
            return;
        }
        LivingEntity target = resolveTarget(maid);
        if (target == null) {
            fail(state, "目标丢失或超出 " + (int) TARGET_RANGE + " 格");
            return;
        }
        // 长矛是唯一攻击：TLM 的平A整条按住，蓄力/伤害由 spearcore 的 onUseTick 结算
        MaidFlightCombatBehavior.suppressVanillaMelee(maid);
        maid.setSwingingArms(false);
        MaidFlightKit.equip(maid);
        SpearCoreCompat.guardStaleUse(maid);
        SpearCoreCompat.claimCharge(maid);
        SpearCoreCompat.holdMaxCharge(maid);
        MaidFlightKit.setGliding(maid, true);
        // 冲锋前补的那一枚烟花仍然按原版挂载烟花的递推施加；没有窗口时只是一次查表
        MaidVirtualFirework.tick(maid);

        double x = maid.getX();
        double y = maid.getY();
        double z = maid.getZ();
        switch (state.phase) {
            case CIRCLE -> tickCircle(level, maid, target, state);
            case SWOOP -> tickSwoop(level, maid, target, state, gameTime);
            case POST -> tickPost(level, maid, target, state);
            default -> {
            }
        }
        state.prevX = x;
        state.prevY = y;
        state.prevZ = z;
        state.hasPrev = true;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        State state = STATES.remove(id);
        ACTIVE.remove(id);
        SpearCoreCompat.stopCharge(maid);
        if (maid.onGround()) {
            MaidFlightKit.setGliding(maid, false);
            MaidRiptideBoost.clear(maid);
            MaidSelfPropelledWings.clear(maid);
            // 交还通用链路重新起飞时不能清 WAIT_LAUNCH，否则循环断在这里
            if (state != null && !state.handoff) {
                MaidFlightCombatBehavior.pauseRound(id);
            }
        }
        String reason = state != null && !state.reason.isEmpty() ? state.reason : "收尾";
        log(maid, "退出鞘翅长矛链路（" + reason + "）");
    }

    /* ---------------- 起飞后 0.5 秒 ---------------- */

    private void tickWaitTakeoff(ServerLevel level, EntityMaid maid, State state) {
        if (state.age < takeoffDelay()) {
            return;
        }
        if (maid.onGround()) {
            if (state.age > TAKEOFF_WAIT_MAX) {
                fail(state, "起飞后仍未离地");
            }
            return;
        }
        LivingEntity target = resolveTarget(maid);
        if (target == null) {
            fail(state, "目标丢失");
            return;
        }
        beginPhantom(level, maid, target, state);
    }

    private void beginPhantom(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        state.active = true;
        ACTIVE.add(maid.getUUID());
        state.phase = Phase.CIRCLE;
        state.ticks = 0;
        state.swoopTicks = 0;
        state.boosted = false;
        state.pathHits.clear();
        // 【R1080】进入幻翼态这一拍：写入"起飞后 0.5 秒的实际速度"，之后只减不增
        state.speed = Math.max(0.12, maid.getDeltaMovement().length());
        state.speedFloor = state.speed * (1.0 - maxDecay());
        state.lastHurtStamp = target.getLastHurtByMobTimestamp();
        // 通用链路的起飞推力窗/旧相位（含 SMASH）到此为止：幻翼态速度由本链路维持
        MaidFlightCombatBehavior.pauseRound(maid.getUUID());
        MaidVirtualFirework.clear(maid);
        MaidRiptideBoost.clear(maid);
        MaidSelfPropelledWings.clear(maid);
        MaidFlightKit.setGliding(maid, true);
        SpearCoreCompat.claimCharge(maid);
        SpearCoreCompat.holdMaxCharge(maid);
        setupCircle(level, maid, target, state);
        state.nextSweepTicks = FIRST_SWEEP_TICKS;
        log(maid, "起飞 " + fmt(takeoffDelay() / 20.0) + " 秒 → 幻翼态（速度 "
                + fmt2(state.speed) + " 格/tick，R1083 起全程不衰减）");
    }

    /* ---------------- 幻翼态：环绕 ---------------- */

    private void tickCircle(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        state.ticks++;
        var random = maid.getRandom();
        if (random.nextInt(CIRCLE_HEIGHT_TICKS) == 0) {
            state.circleHeight = CIRCLE_HEIGHT_BASE + random.nextFloat() * CIRCLE_HEIGHT_SPAN;
        }
        if (random.nextInt(CIRCLE_RADIUS_TICKS) == 0) {
            state.circleDistance++;
            if (state.circleDistance > CIRCLE_RADIUS_MIN + CIRCLE_RADIUS_SPAN) {
                state.circleDistance = CIRCLE_RADIUS_MIN;
                state.circleClockwise = -state.circleClockwise;
            }
        }
        if (random.nextInt(CIRCLE_ANGLE_TICKS) == 0) {
            state.circleAngle = random.nextFloat() * 2.0f * (float) Math.PI;
            selectNextCirclePoint(state);
        }
        if (touchingMoveTarget(maid, state)) {
            selectNextCirclePoint(state);
        }
        if (state.moveTarget.y < maid.getY()
                && !level.isEmptyBlock(maid.blockPosition().below(1))) {
            state.circleHeight = Math.max(1.0f, state.circleHeight);
            selectNextCirclePoint(state);
        }
        if (state.moveTarget.y > maid.getY()
                && !level.isEmptyBlock(maid.blockPosition().above(1))) {
            state.circleHeight = Math.min(-1.0f, state.circleHeight);
            selectNextCirclePoint(state);
        }
        phantomMove(maid, state.moveTarget, nextSpeed(state));
        // 从地面起飞时，0.5 秒后她还在低空；先爬过目标头顶再倒数，避免第一轮直接扎进地形
        boolean highEnough = maid.getY() >= target.getY() + MIN_SWEEP_CLIMB;
        if (!highEnough && state.ticks < CLIMB_TIMEOUT_TICKS) {
            return;
        }
        if (--state.nextSweepTicks > 0) {
            return;
        }
        enterSwoop(level, maid, target, state);
    }

    private void enterSwoop(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        state.phase = Phase.SWOOP;
        state.ticks = 0;
        state.swoopTicks = 0;
        state.boosted = false;
        state.pathHits.clear();
        setAnchorAboveTarget(level, target, state);
        log(maid, "幻翼态：环绕结束 → 俯冲（距敌 "
                + fmt(Math.sqrt(maid.distanceToSqr(target))) + " 格）");
    }

    private void setupCircle(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        var random = maid.getRandom();
        state.circleDistance = CIRCLE_RADIUS_MIN + random.nextFloat() * CIRCLE_RADIUS_SPAN;
        state.circleHeight = CIRCLE_HEIGHT_BASE + random.nextFloat() * CIRCLE_HEIGHT_SPAN;
        state.circleClockwise = random.nextBoolean() ? 1.0f : -1.0f;
        state.circleAngle = random.nextFloat() * 2.0f * (float) Math.PI;
        setAnchorAboveTarget(level, target, state);
        selectNextCirclePoint(state);
    }

    /** 与幻翼 {@code setAnchorAboveTarget} 同口径：目标上方 20~40 格，且不低于海平面。 */
    private static void setAnchorAboveTarget(ServerLevel level, LivingEntity target, State state) {
        BlockPos pos = target.blockPosition()
                .above(CIRCLE_ANCHOR_MIN + level.getRandom().nextInt(CIRCLE_ANCHOR_SPAN));
        int minY = level.getSeaLevel() + 1;
        state.anchor = new Vec3(pos.getX() + 0.5, Math.max(minY, pos.getY()), pos.getZ() + 0.5);
    }

    /** 与幻翼 {@code selectNext} 同口径：角度步进 15°，绕锚点取下一个点。 */
    private static void selectNextCirclePoint(State state) {
        state.circleAngle += state.circleClockwise * Math.toRadians(15.0);
        state.moveTarget = state.anchor.add(
                state.circleDistance * Math.cos(state.circleAngle),
                CIRCLE_HEIGHT_BASE + state.circleHeight,
                state.circleDistance * Math.sin(state.circleAngle));
    }

    private static boolean touchingMoveTarget(EntityMaid maid, State state) {
        return state.moveTarget.distanceToSqr(maid.getX(), maid.getY(), maid.getZ()) < 4.0;
    }

    /* ---------------- 幻翼态：俯冲 + 长矛冲锋 ---------------- */

    private void tickSwoop(ServerLevel level, EntityMaid maid, LivingEntity target,
                           State state, long gameTime) {
        state.ticks++;
        state.swoopTicks++;
        // 【R1083】每一拍都把瞄准点刷成目标**当前**位置（带高度中心），
        // 俯冲全程盯住，不再沿用环绕阶段留下的旧点。
        state.moveTarget = new Vec3(target.getX(), target.getY(0.5), target.getZ());
        double dist = Math.sqrt(maid.distanceToSqr(target));
        // 幻翼的平A位置：冲锋前若还有烟花，消耗 1 枚再补一次加速
        if (!state.boosted && dist <= boostRange()) {
            state.boosted = true;
            boostBeforeCharge(level, maid, state, gameTime);
        }
        // 撞上目标 = 幻翼的 doHurtTarget；伤害主体由 spearcore 的矛线结算
        if (tookSpearHit(maid, target, state) || sweepHits(maid, target)) {
            ensureContactDamage(level, maid, target);
            enterPost(level, maid, target, state, "长矛命中");
            return;
        }
        if (maid.onGround()) {
            finish(maid, target, state, "俯冲触地（未命中）");
            return;
        }
        // 【R1083】撞墙/受击不再"直接开惯性"：惯性只在命中后开放；这里退回环绕重新找角度
        if (maid.horizontalCollision || maid.hurtTime > 0) {
            abortDive(level, maid, target, state, "俯冲擦墙/受击");
            return;
        }
        // 【R1083】俯冲用直瞄转向（不再套幻翼的 shrink/4°/0.2 缓动）：
        // 目标每 tick 都在动，旧的缓动在近距离会明显滞后，对单命中率就是这么掉的。
        steerSwoop(maid, state.moveTarget, state.speed);
        if (tookSpearHit(maid, target, state) || sweepHits(maid, target)) {
            ensureContactDamage(level, maid, target);
            enterPost(level, maid, target, state, "长矛命中");
            return;
        }
        if (detectPathHit(level, maid, target, state)) {
            enterPost(level, maid, target, state, "长矛碰撞命中");
            return;
        }
        if (state.swoopTicks > passMaxTicks()) {
            abortDive(level, maid, target, state, "俯冲超时");
        }
    }

    /**
     * 俯冲直瞄：把机头/视线钉在目标的**当前**位置，速度以较大权重拉过去。
     *
     * <p>原版幻翼那套 {@code shrink = 1 - |dy·0.7| / distH} 在"目标几乎在正下方"时
     * 会变成负数，水平方向整个翻转（俯冲段最常见的几何），再叠加 4°/tick 转向与 0.2
     * 速度缓动，近距离目标就会从准星里滑出去。这里在俯冲相位换成无 shrink 的直瞄。
     */
    private static void steerSwoop(EntityMaid maid, Vec3 point, double speed) {
        double dx = point.x - maid.getX();
        double dy = point.y - (maid.getY() + maid.getBbHeight() * 0.5);
        double dz = point.z - maid.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 1.0E-4) {
            return;
        }
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = horiz <= 1.0E-4
                ? (dy > 0.0 ? -90.0f : 90.0f)
                : (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        MaidFlightCombatBehavior.applyRotation(maid, yaw, pitch);
        maid.yBodyRot = yaw;
        try {
            maid.getLookControl().setLookAt(point.x, point.y, point.z, 360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        double vx = dx / dist * speed;
        double vy = dy / dist * speed;
        double vz = dz / dist * speed;
        Vec3 cur = maid.getDeltaMovement();
        double k = 0.5;
        maid.setDeltaMovement(
                cur.x + (vx - cur.x) * k,
                cur.y + (vy - cur.y) * k,
                cur.z + (vz - cur.z) * k);
    }

    /** 俯冲没命中（擦墙/受击/超时）：不开惯性，退回环绕重新找角度再扎。 */
    private void abortDive(ServerLevel level, EntityMaid maid, LivingEntity target,
                           State state, String reason) {
        log(maid, reason + "（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target)))
                + " 格）→ 未命中，回到环绕重新俯冲");
        state.phase = Phase.CIRCLE;
        state.ticks = 0;
        state.boosted = false;
        state.pathHits.clear();
        state.nextSweepTicks = FIRST_SWEEP_TICKS;
        setupCircle(level, maid, target, state);
    }

    private static void boostBeforeCharge(ServerLevel level, EntityMaid maid, State state, long gameTime) {
        if (MaidVirtualFirework.isStalled(maid)) {
            return; // 【R1083】原地空推的冷却期：别白烧一枚
        }
        ItemStack fw = MaidFlightKit.takeFirework(maid);
        if (fw.isEmpty()) {
            return; // 只有烟花能补这一口；羽扇/自推鞘翅只作启用条件
        }
        try {
            MaidVirtualFirework.ignite(maid, MaidFlightCombatBehavior.diveBoostFireworkScale());
            level.playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                    SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return;
        }
        FlightFireworkPose.show(maid, fw);
        MaidFlightCombatBehavior.markFlightFireworkUsed(maid, gameTime);
        log(maid, "冲锋前补一记烟花加速（消耗 1 枚）");
    }

    private void enterPost(ServerLevel level, EntityMaid maid, LivingEntity target,
                           State state, String reason) {
        state.phase = Phase.POST;
        state.ticks = 0;
        state.pathHits.clear();
        state.lastHurtStamp = target.getLastHurtByMobTimestamp();
        levelOut(maid);
        log(maid, reason + "（距敌 " + fmt(Math.sqrt(maid.distanceToSqr(target)))
                + " 格）→ 解除幻翼态，水平惯性冲锋 " + fmt(postTicks() / 20.0) + " 秒");
    }

    /* ---------------- 命中后的水平惯性冲锋 ---------------- */

    private void tickPost(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        state.ticks++;
        levelOut(maid);
        detectPathHit(level, maid, target, state);
        if (sweepHits(maid, target)) {
            // 惯性段撞到敌人：伤害与冲锋一致，但不延长这 1 秒的窗口
            if (!(target.getLastHurtByMob() == maid && target.hurtTime > 0)) {
                MaidFlightCombatBehavior.spearPathHit(level, maid, target);
            }
        }
        if (maid.onGround() || state.ticks >= postTicks()) {
            finish(maid, target, state, maid.onGround() ? "惯性段触地" : "惯性冲锋 1 秒结束");
        }
    }

    private static void finish(EntityMaid maid, LivingEntity target, State state, String reason) {
        state.done = true;
        state.reason = reason;
        if (target != null && resolveTarget(maid) != null && MaidFlightKit.isModeActive(maid)) {
            // 还有敌人：交回通用链路重新点火，0.5 秒后本链路会再次接管
            state.handoff = true;
            MaidFlightCombatBehavior.requestRelaunch(maid.getUUID());
            log(maid, reason + " → 交回通用空袭重新起飞（链路循环）");
        } else {
            log(maid, reason + " → 结束本轮");
        }
    }

    /* ---------------- 幻翼移动（照搬 PhantomMoveControl） ---------------- */

    /**
     * 幻翼移动控制。速度参数由 {@link #nextSpeed} 每 tick 衰减；方向、朝向、速度合成
     * 全部照搬原版 {@code Phantom.PhantomMoveControl.tick}。
     *
     * <p>原版用 {@code f4 = -atan2(-dy, d3)} 作为模型俯仰；女仆的长矛伤害读的是
     * {@code getLookAngle()}，所以这里取反，让视线与飞行方向一致。
     */
    private static void phantomMove(EntityMaid maid, Vec3 targetPoint, double speed) {
        if (targetPoint == null) {
            return;
        }
        double dx = targetPoint.x - maid.getX();
        double dy = targetPoint.y - maid.getY();
        double dz = targetPoint.z - maid.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(distH) <= 1.0E-5) {
            return;
        }
        // 竖直差越大，水平目标越收窄（幻翼原式）
        double shrink = 1.0 - Math.abs(dy * 0.7) / distH;
        dx *= shrink;
        dz *= shrink;
        distH = Math.sqrt(dx * dx + dz * dz);
        double dist3 = Math.sqrt(dx * dx + dz * dz + dy * dy);
        if (dist3 <= 1.0E-5) {
            return;
        }
        float oldYaw = maid.getYRot();
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
        float curYaw = Mth.wrapDegrees(oldYaw + 90.0f);
        float newYaw = Mth.approachDegrees(curYaw, Mth.wrapDegrees(targetYaw), 4.0f) - 90.0f;
        // 与幻翼同一条俯仰线，但取反给长矛的视线用（见方法注释）
        float phantomPitch = (float) -Math.toDegrees(Math.atan2(-dy, distH));
        float lookPitch = -phantomPitch;
        MaidFlightCombatBehavior.applyRotation(maid, newYaw, lookPitch);
        maid.yBodyRot = newYaw;
        try {
            maid.getLookControl().setLookAt(targetPoint.x, targetPoint.y, targetPoint.z, 360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        double yawRad = Math.toRadians(newYaw + 90.0f);
        double sinPitch = Math.sin(Math.toRadians(phantomPitch));
        double vx = speed * Math.cos(yawRad) * Math.abs(dx / dist3);
        double vz = speed * Math.sin(yawRad) * Math.abs(dz / dist3);
        double vy = speed * sinPitch * Math.abs(dy / dist3);
        Vec3 cur = maid.getDeltaMovement();
        maid.setDeltaMovement(
                cur.x + (vx - cur.x) * 0.2,
                cur.y + (vy - cur.y) * 0.2,
                cur.z + (vz - cur.z) * 0.2);
    }

    /**
     * 幻翼态速度。【R1083】取消衰减：整轮保持进入幻翼态那一拍的速度。
     * 旧版每 tick 乘 {@code (1 - spearDecayPerTick)}、最低 50%，玩家实测"越冲越慢"，
     * 长距离俯冲后段追不上移动目标。两个衰减配置键保留只为旧配置文件不报错，当前不再读取。
     */
    private static double nextSpeed(State state) {
        return state.speed;
    }

    /** 水平平飞：把机头摆平，并把视线放到机头正前方，交给原版鞘翅物理解算。 */
    private static void levelOut(EntityMaid maid) {
        Vec3 dm = maid.getDeltaMovement();
        double hSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
        float yaw = hSpeed > 0.03
                ? (float) Math.toDegrees(Math.atan2(dm.z, dm.x)) - 90.0f
                : maid.getYRot();
        MaidFlightCombatBehavior.applyRotation(maid, yaw, 0.0f);
        double rad = Math.toRadians(yaw + 90.0f);
        try {
            maid.getLookControl().setLookAt(
                    maid.getX() + Math.cos(rad) * 8.0,
                    maid.getEyeY(),
                    maid.getZ() + Math.sin(rad) * 8.0,
                    360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
    }

    /* ---------------- 命中判定 ---------------- */

    /** 幻翼"撞上目标"：本 tick 的碰撞箱（含速度前伸）与目标相交。 */
    private static boolean sweepHits(EntityMaid maid, LivingEntity target) {
        try {
            AABB box = maid.getBoundingBox()
                    .expandTowards(maid.getDeltaMovement())
                    .inflate(SWEEP_INFLATE + 0.3);
            return box.intersects(target.getBoundingBox());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return false;
        }
    }

    /** 撞上目标但 spearcore 这一拍没结算（高速掠过/矛线差半格）时补一记长矛武器伤害。 */
    private static void ensureContactDamage(ServerLevel level, EntityMaid maid, LivingEntity target) {
        if (target.getLastHurtByMob() == maid && target.hurtTime > 0) {
            return;
        }
        MaidFlightCombatBehavior.spearPathHit(level, maid, target);
    }

    /** 这一 tick 目标是否又吃了她的长矛（spearcore 的伤害源归因给她）。 */
    private static boolean tookSpearHit(EntityMaid maid, LivingEntity target, State state) {
        int stamp = target.getLastHurtByMobTimestamp();
        boolean hit = stamp != state.lastHurtStamp && target.getLastHurtByMob() == maid;
        state.lastHurtStamp = stamp;
        return hit;
    }

    /**
     * 路径碰撞补判：上一 tick 与本 tick 的碰撞箱组成扫掠盒，外扩 1 格内的合法敌对目标
     * 各补一次长矛武器结算（与 spearcore 同口径的速度增伤）。每趟每目标最多一次。
     */
    private static boolean detectPathHit(ServerLevel level, EntityMaid maid,
                                         LivingEntity target, State state) {
        if (!state.hasPrev) {
            return false;
        }
        try {
            AABB box = maid.getBoundingBox();
            double halfW = (box.maxX - box.minX) * 0.5;
            double height = box.maxY - box.minY;
            AABB swept = new AABB(
                    Math.min(state.prevX - halfW, box.minX),
                    Math.min(state.prevY, box.minY),
                    Math.min(state.prevZ - halfW, box.minZ),
                    Math.max(state.prevX + halfW, box.maxX),
                    Math.max(state.prevY + height, box.maxY),
                    Math.max(state.prevZ + halfW, box.maxZ)).inflate(PATH_HIT_INFLATE);
            if (!(maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask attackTask)) {
                return false;
            }
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, swept)) {
                if (other == maid || other instanceof EntityMaid || !other.isAlive()
                        || other.level() != level) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, other) || !attackTask.canAttack(maid, other)) {
                    continue;
                }
                if (other.getLastHurtByMob() == maid && other.hurtTime > 0) {
                    continue; // 这一拍 spearcore 已经打到了
                }
                if (!state.pathHits.add(other.getUUID())) {
                    continue;
                }
                if (MaidFlightCombatBehavior.spearPathHit(level, maid, other)) {
                    log(maid, "长矛碰撞命中（路径）距敌 "
                            + fmt(Math.sqrt(maid.distanceToSqr(other))) + " 格");
                    if (other == target) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        return false;
    }

    /* ---------------- 目标 ---------------- */

    /**
     * 目标解析：先用共用空袭索敌器；它因活动范围/排班等原因暂时返回 null 时，
     * 回退到 TLM 的 brain 目标（与通用近战空袭同口径）。
     */
    private static LivingEntity resolveTarget(EntityMaid maid) {
        LivingEntity target = null;
        try {
            target = FlightTargeting.resolve(maid);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        if (target == null) {
            target = brainTarget(maid);
        }
        if (target == null || !target.isAlive() || target.level() != maid.level()) {
            return null;
        }
        if (target instanceof net.minecraft.world.entity.player.Player p
                && (p.isSpectator() || p.getAbilities().instabuild)) {
            return null;
        }
        if (FriendlyFireGuard.isFriendly(maid, target)) {
            return null;
        }
        return maid.distanceToSqr(target) <= TARGET_RANGE * TARGET_RANGE ? target : null;
    }

    private static LivingEntity brainTarget(EntityMaid maid) {
        try {
            LivingEntity target = maid.getTarget();
            if (target == null) {
                target = maid.getBrain()
                        .getMemory(MemoryModuleType.ATTACK_TARGET)
                        .orElse(null);
            }
            return target;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return null;
        }
    }

    private static void markReason(EntityMaid maid, String reason) {
        State state = STATES.get(maid.getUUID());
        if (state != null) {
            state.reason = reason;
        }
    }

    private static void fail(State state, String reason) {
        state.done = true;
        state.reason = reason;
    }

    /* ---------------- 配置 ---------------- */

    private static int takeoffDelay() {
        return Math.max(0, MaidSmartConfig.AIR_RAID_SPEAR_RECOVER_TICKS.get());
    }

    private static int passMaxTicks() {
        return Math.max(20, MaidSmartConfig.AIR_RAID_SPEAR_MAX_TICKS.get());
    }

    private static double boostRange() {
        return MaidSmartConfig.AIR_RAID_SPEAR_PASS_RANGE.get();
    }

    private static int postTicks() {
        return Math.max(1, MaidSmartConfig.AIR_RAID_SPEAR_BOOST_INTERVAL.get());
    }

    private static double decayPerTick() {
        return Mth.clamp(MaidSmartConfig.AIR_RAID_SPEAR_DECAY_PER_TICK.get(), 0.0, 0.1);
    }

    private static double maxDecay() {
        return Mth.clamp(MaidSmartConfig.AIR_RAID_SPEAR_MAX_DECAY.get(), 0.0, 0.9);
    }

    private static void log(EntityMaid maid, String message) {
        try {
            com.maidsmart.tool.PromaidLog.log("空袭·长矛",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + message);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 清理某只女仆的静态状态（切任务/卸下/服务端停止兜底）。 */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        STATES.remove(maidId);
        ACTIVE.remove(maidId);
    }

    public static void clearAll() {
        STATES.clear();
        ACTIVE.clear();
    }
}
