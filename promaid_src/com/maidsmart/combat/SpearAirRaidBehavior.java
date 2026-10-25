package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 【R1080】鞘翅长矛：像重锤猛击一样单开的近战空袭链路（1.20.1 镜像）。
 *
 * <p>接敌前与通用近战空袭完全一致（平地走路、索敌半径、目标判定都不变）。接敌后
 * 先借用通用空袭的起飞方式（烟花 / 羽扇 / 激流 / 自推鞘翅，消耗照旧），起飞 0.5 秒
 * 后整体切换成幻翼的攻击方式与状态机：环绕目标上方 → 俯冲 → 平A 换成长矛冲锋。
 *
 * <p>与幻翼唯一的区别：幻翼一次攻击后就飞离，女仆命中后解除幻翼态，改为普通水平
 * 鞘翅飞行并顺着惯性继续冲锋 1 秒（撞到敌人仍按长矛结算）；1 秒后解除飞行，若敌人
 * 还在就回到链路开头重新起飞。R1083 起速度全程不衰减。
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
        int lastHurtStamp;
        Vec3 anchor = Vec3.f_82478_;
        Vec3 moveTarget = Vec3.f_82478_;
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
        return maid != null && ACTIVE.contains(maid.m_20148_());
    }

    /** 配置、任务、主手长矛都成立（不要求当前有目标 / 已经起飞）。 */
    public static boolean isConfigured(EntityMaid maid) {
        try {
            return maid != null
                    && MaidSmartConfig.COMBAT_SPEAR_AIR_RAID.get()
                    && MaidSmartConfig.COMBAT_FLIGHT_MODE.get()
                    && isMeleeFlightTask(maid)
                    && SpearCoreCompat.isSpear(maid.m_21205_());
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
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!isConfigured(maid) || maid.m_5803_() || maid.m_20159_()
                || ACTIVE.contains(maid.m_20148_()) || STATES.containsKey(maid.m_20148_())) {
            return false;
        }
        // 只认通用空袭刚刚点过火：没有点火记录（或已经是上一轮）就不抢
        long age = MaidFlightCombatBehavior.launchAge(maid, level.m_46467_());
        if (age < 0 || age > takeoffDelay() + 20L) {
            return false;
        }
        if (!MaidFlightCombatBehavior.isLaunchActive(maid) && !maid.m_21255_()) {
            return false;
        }
        MaidFlightKit.equip(maid);
        if (!SpearCoreCompat.isSpear(maid.m_21205_()) || !MaidFlightKit.isModeActive(maid)) {
            return false;
        }
        return resolveTarget(maid) != null;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        State state = STATES.get(maid.m_20148_());
        if (!isConfigured(maid) || maid.m_5803_() || maid.m_20159_()
                || state == null || state.done) {
            markReason(maid, "配置/任务/装备失效");
            return false;
        }
        if (state.phase != Phase.WAIT_TAKEOFF && !MaidFlightKit.isModeActive(maid)) {
            markReason(maid, "长矛/飞行三件套失效");
            return false;
        }
        if (state.phase != Phase.WAIT_TAKEOFF && state.phase != Phase.POST && maid.m_20096_()) {
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
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        State state = new State();
        state.phase = Phase.WAIT_TAKEOFF;
        STATES.put(maid.m_20148_(), state);
        MaidFlightKit.equip(maid);
        log(maid, "通用空袭已点火，等待 " + takeoffDelay() + " tick（"
                + fmt(takeoffDelay() / 20.0) + " 秒）后切换幻翼态");
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
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
        maid.m_6862_(false);
        MaidFlightKit.equip(maid);
        SpearCoreCompat.guardStaleUse(maid);
        SpearCoreCompat.claimCharge(maid);
        SpearCoreCompat.holdMaxCharge(maid);
        MaidFlightKit.setGliding(maid, true);
        // 冲锋前补的那一枚烟花仍然按原版挂载烟花的递推施加；没有窗口时只是一次查表
        MaidVirtualFirework.tick(maid);

        double x = maid.m_20185_();
        double y = maid.m_20186_();
        double z = maid.m_20189_();
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
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
        State state = STATES.remove(id);
        ACTIVE.remove(id);
        SpearCoreCompat.stopCharge(maid);
        if (maid.m_20096_()) {
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
        if (maid.m_20096_()) {
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
        ACTIVE.add(maid.m_20148_());
        state.phase = Phase.CIRCLE;
        state.ticks = 0;
        state.swoopTicks = 0;
        state.boosted = false;
        state.pathHits.clear();
        // 【R1080】进入幻翼态这一拍：写入"起飞后 0.5 秒的实际速度"，R1083 起全程保持
        state.speed = Math.max(0.12, maid.m_20184_().m_82556_());
        state.lastHurtStamp = target.m_21213_();
        // 通用链路的起飞推力窗/旧相位到此为止：幻翼态速度由本链路维持
        MaidFlightCombatBehavior.pauseRound(maid.m_20148_());
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
        var random = maid.m_217043_();
        if (random.m_188503_(CIRCLE_HEIGHT_TICKS) == 0) {
            state.circleHeight = CIRCLE_HEIGHT_BASE + random.m_188501_() * CIRCLE_HEIGHT_SPAN;
        }
        if (random.m_188503_(CIRCLE_RADIUS_TICKS) == 0) {
            state.circleDistance++;
            if (state.circleDistance > CIRCLE_RADIUS_MIN + CIRCLE_RADIUS_SPAN) {
                state.circleDistance = CIRCLE_RADIUS_MIN;
                state.circleClockwise = -state.circleClockwise;
            }
        }
        if (random.m_188503_(CIRCLE_ANGLE_TICKS) == 0) {
            state.circleAngle = random.m_188501_() * 2.0f * (float) Math.PI;
            selectNextCirclePoint(state);
        }
        if (touchingMoveTarget(maid, state)) {
            selectNextCirclePoint(state);
        }
        if (state.moveTarget.f_82480_ < maid.m_20186_()
                && !level.m_46859_(maid.m_20183_().m_6630_(1))) {
            state.circleHeight = Math.max(1.0f, state.circleHeight);
            selectNextCirclePoint(state);
        }
        if (state.moveTarget.f_82480_ > maid.m_20186_()
                && !level.m_46859_(maid.m_20183_().m_6625_(1))) {
            state.circleHeight = Math.min(-1.0f, state.circleHeight);
            selectNextCirclePoint(state);
        }
        phantomMove(maid, state.moveTarget, nextSpeed(state));
        // 从地面起飞时，0.5 秒后她还在低空；先爬过目标头顶再倒数，避免第一轮直接扎进地形
        boolean highEnough = maid.m_20186_() >= target.m_20186_() + MIN_SWEEP_CLIMB;
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
                + fmt(Math.sqrt(maid.m_20280_(target))) + " 格）");
    }

    private void setupCircle(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        var random = maid.m_217043_();
        state.circleDistance = CIRCLE_RADIUS_MIN + random.m_188501_() * CIRCLE_RADIUS_SPAN;
        state.circleHeight = CIRCLE_HEIGHT_BASE + random.m_188501_() * CIRCLE_HEIGHT_SPAN;
        state.circleClockwise = random.m_188499_() ? 1.0f : -1.0f;
        state.circleAngle = random.m_188501_() * 2.0f * (float) Math.PI;
        setAnchorAboveTarget(level, target, state);
        selectNextCirclePoint(state);
    }

    /** 与幻翼 {@code setAnchorAboveTarget} 同口径：目标上方 16~32 格，且不低于海平面。 */
    private static void setAnchorAboveTarget(ServerLevel level, LivingEntity target, State state) {
        BlockPos pos = target.m_20183_()
                .m_6625_(CIRCLE_ANCHOR_MIN + level.m_213780_().m_188503_(CIRCLE_ANCHOR_SPAN));
        int minY = level.m_5736_() + 1;
        state.anchor = new Vec3(pos.m_123341_() + 0.5, Math.max(minY, pos.m_123342_()),
                pos.m_123343_() + 0.5);
    }

    /** 与幻翼 {@code selectNext} 同口径：角度步进 15°，绕锚点取下一个点。 */
    private static void selectNextCirclePoint(State state) {
        state.circleAngle += state.circleClockwise * Math.toRadians(15.0);
        state.moveTarget = state.anchor.m_82520_(
                state.circleDistance * Math.cos(state.circleAngle),
                CIRCLE_HEIGHT_BASE + state.circleHeight,
                state.circleDistance * Math.sin(state.circleAngle));
    }

    private static boolean touchingMoveTarget(EntityMaid maid, State state) {
        return state.moveTarget.m_82531_(maid.m_20185_(), maid.m_20186_(), maid.m_20189_()) < 4.0;
    }

    /* ---------------- 幻翼态：俯冲 + 长矛冲锋 ---------------- */

    private void tickSwoop(ServerLevel level, EntityMaid maid, LivingEntity target,
                           State state, long gameTime) {
        state.ticks++;
        state.swoopTicks++;
        // 【R1083】每一拍都把瞄准点刷成目标**当前**位置（带高度中心），
        // 俯冲全程盯住，不再沿用环绕阶段留下的旧点。
        state.moveTarget = new Vec3(target.m_20185_(), target.m_20227_(0.5), target.m_20189_());
        double dist = Math.sqrt(maid.m_20280_(target));
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
        if (maid.m_20096_()) {
            finish(maid, target, state, "俯冲触地（未命中）");
            return;
        }
        // 【R1083】撞墙/受击不再"直接开惯性"：惯性只在命中后开放；这里退回环绕重新找角度
        if (maid.f_19862_ || maid.f_20916_ > 0) {
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
        double dx = point.f_82479_ - maid.m_20185_();
        double dy = point.f_82480_ - (maid.m_20186_() + maid.m_20206_() * 0.5);
        double dz = point.f_82481_ - maid.m_20189_();
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
        maid.f_20883_ = yaw;
        try {
            maid.m_21563_().m_24950_(point.f_82479_, point.f_82480_, point.f_82481_, 360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        double vx = dx / dist * speed;
        double vy = dy / dist * speed;
        double vz = dz / dist * speed;
        Vec3 cur = maid.m_20184_();
        double k = 0.5;
        maid.m_20256_(new Vec3(
                cur.f_82479_ + (vx - cur.f_82479_) * k,
                cur.f_82480_ + (vy - cur.f_82480_) * k,
                cur.f_82481_ + (vz - cur.f_82481_) * k));
    }

    /** 俯冲没命中（擦墙/受击/超时）：不开惯性，退回环绕重新找角度再扎。 */
    private void abortDive(ServerLevel level, EntityMaid maid, LivingEntity target,
                           State state, String reason) {
        log(maid, reason + "（距敌 " + fmt(Math.sqrt(maid.m_20280_(target)))
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
        if (fw.m_41619_()) {
            return; // 只有烟花能补这一口；羽扇/自推鞘翅只作启用条件
        }
        try {
            MaidVirtualFirework.ignite(maid, MaidFlightCombatBehavior.diveBoostFireworkScale());
            SoundEvent launch = ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation("minecraft", "entity.firework_rocket.launch"));
            if (launch != null) {
                level.m_5594_(null, maid.m_20183_(), launch, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
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
        state.lastHurtStamp = target.m_21213_();
        levelOut(maid);
        log(maid, reason + "（距敌 " + fmt(Math.sqrt(maid.m_20280_(target)))
                + " 格）→ 解除幻翼态，水平惯性冲锋 " + fmt(postTicks() / 20.0) + " 秒");
    }

    /* ---------------- 命中后的水平惯性冲锋 ---------------- */

    private void tickPost(ServerLevel level, EntityMaid maid, LivingEntity target, State state) {
        state.ticks++;
        levelOut(maid);
        detectPathHit(level, maid, target, state);
        if (sweepHits(maid, target)) {
            // 惯性段撞到敌人：伤害与冲锋一致，但不延长这 1 秒的窗口
            if (!(target.m_21214_() == maid && target.f_20916_ > 0)) {
                MaidFlightCombatBehavior.spearPathHit(level, maid, target);
            }
        }
        if (maid.m_20096_() || state.ticks >= postTicks()) {
            finish(maid, target, state, maid.m_20096_() ? "惯性段触地" : "惯性冲锋 1 秒结束");
        }
    }

    private static void finish(EntityMaid maid, LivingEntity target, State state, String reason) {
        state.done = true;
        state.reason = reason;
        if (target != null && resolveTarget(maid) != null && MaidFlightKit.isModeActive(maid)) {
            // 还有敌人：交回通用链路重新点火，0.5 秒后本链路会再次接管
            state.handoff = true;
            MaidFlightCombatBehavior.requestRelaunch(maid.m_20148_());
            log(maid, reason + " → 交回通用空袭重新起飞（链路循环）");
        } else {
            log(maid, reason + " → 结束本轮");
        }
    }

    /* ---------------- 幻翼移动（照搬 PhantomMoveControl） ---------------- */

    /**
     * 幻翼移动控制。速度参数由 {@link #nextSpeed} 每 tick 给出；方向、朝向、速度合成
     * 全部照搬原版 {@code Phantom.PhantomMoveControl.tick}。
     *
     * <p>原版用 {@code f4 = -atan2(-dy, d3)} 作为模型俯仰；女仆的长矛伤害读的是
     * {@code getLookAngle()}，所以这里取反，让视线与飞行方向一致。
     */
    private static void phantomMove(EntityMaid maid, Vec3 targetPoint, double speed) {
        if (targetPoint == null) {
            return;
        }
        double dx = targetPoint.f_82479_ - maid.m_20185_();
        double dy = targetPoint.f_82480_ - maid.m_20186_();
        double dz = targetPoint.f_82481_ - maid.m_20189_();
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
        float oldYaw = maid.m_146908_();
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx));
        float curYaw = Mth.m_14177_(oldYaw + 90.0f);
        float delta = Mth.m_14177_(Mth.m_14177_(targetYaw) - curYaw);
        if (delta > 4.0f) {
            delta = 4.0f;
        }
        if (delta < -4.0f) {
            delta = -4.0f;
        }
        float newYaw = curYaw + delta - 90.0f;
        // 与幻翼同一条俯仰线，但取反给长矛的视线用（见方法注释）
        float phantomPitch = (float) -Math.toDegrees(Math.atan2(-dy, distH));
        float lookPitch = -phantomPitch;
        MaidFlightCombatBehavior.applyRotation(maid, newYaw, lookPitch);
        maid.f_20883_ = newYaw;
        try {
            maid.m_21563_().m_24950_(targetPoint.f_82479_, targetPoint.f_82480_,
                    targetPoint.f_82481_, 360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
        double yawRad = Math.toRadians(newYaw + 90.0f);
        double sinPitch = Math.sin(Math.toRadians(phantomPitch));
        double vx = speed * Math.cos(yawRad) * Math.abs(dx / dist3);
        double vz = speed * Math.sin(yawRad) * Math.abs(dz / dist3);
        double vy = speed * sinPitch * Math.abs(dy / dist3);
        Vec3 cur = maid.m_20184_();
        maid.m_20256_(new Vec3(
                cur.f_82479_ + (vx - cur.f_82479_) * 0.2,
                cur.f_82480_ + (vy - cur.f_82480_) * 0.2,
                cur.f_82481_ + (vz - cur.f_82481_) * 0.2));
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
        Vec3 dm = maid.m_20184_();
        double hSpeed = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
        float yaw = hSpeed > 0.03
                ? (float) Math.toDegrees(Math.atan2(dm.f_82481_, dm.f_82479_)) - 90.0f
                : maid.m_146908_();
        MaidFlightCombatBehavior.applyRotation(maid, yaw, 0.0f);
        double rad = Math.toRadians(yaw + 90.0f);
        try {
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + Math.cos(rad) * 8.0,
                    maid.m_20188_(),
                    maid.m_20189_() + Math.sin(rad) * 8.0,
                    360.0f, 360.0f);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
        }
    }

    /* ---------------- 命中判定 ---------------- */

    /** 幻翼"撞上目标"：本 tick 的碰撞箱（含速度前伸）与目标相交。 */
    private static boolean sweepHits(EntityMaid maid, LivingEntity target) {
        try {
            Vec3 dm = maid.m_20184_();
            AABB box = maid.m_20191_()
                    .m_82310_(dm.f_82479_, dm.f_82480_, dm.f_82481_)
                    .m_82400_(SWEEP_INFLATE + 0.3);
            return box.m_82381_(target.m_20191_());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return false;
        }
    }

    /** 撞上目标但 spearcore 这一拍没结算（高速掠过/矛线差半格）时补一记长矛武器伤害。 */
    private static void ensureContactDamage(ServerLevel level, EntityMaid maid, LivingEntity target) {
        if (target.m_21214_() == maid && target.f_20916_ > 0) {
            return;
        }
        MaidFlightCombatBehavior.spearPathHit(level, maid, target);
    }

    /** 这一 tick 目标是否又吃了她的长矛（spearcore 的伤害源归因给她）。 */
    private static boolean tookSpearHit(EntityMaid maid, LivingEntity target, State state) {
        int stamp = target.m_21213_();
        boolean hit = stamp != state.lastHurtStamp && target.m_21214_() == maid;
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
            AABB box = maid.m_20191_();
            double halfW = (box.f_82291_ - box.f_82288_) * 0.5;
            double height = box.f_82292_ - box.f_82289_;
            AABB swept = new AABB(
                    Math.min(state.prevX - halfW, box.f_82288_),
                    Math.min(state.prevY, box.f_82289_),
                    Math.min(state.prevZ - halfW, box.f_82290_),
                    Math.max(state.prevX + halfW, box.f_82291_),
                    Math.max(state.prevY + height, box.f_82292_),
                    Math.max(state.prevZ + halfW, box.f_82293_)).m_82400_(PATH_HIT_INFLATE);
            if (!(maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask attackTask)) {
                return false;
            }
            for (LivingEntity other : level.m_45976_(LivingEntity.class, swept)) {
                if (other == maid || other instanceof EntityMaid || !other.m_6084_()
                        || other.m_9236_() != level) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, other) || !attackTask.canAttack(maid, other)) {
                    continue;
                }
                if (other.m_21214_() == maid && other.f_20916_ > 0) {
                    continue; // 这一拍 spearcore 已经打到了
                }
                if (!state.pathHits.add(other.m_20148_())) {
                    continue;
                }
                if (MaidFlightCombatBehavior.spearPathHit(level, maid, other)) {
                    log(maid, "长矛碰撞命中（路径）距敌 "
                            + fmt(Math.sqrt(maid.m_20280_(other))) + " 格");
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
        if (target == null || !target.m_6084_() || target.m_9236_() != maid.m_9236_()) {
            return null;
        }
        if (target instanceof net.minecraft.world.entity.player.Player p
                && (p.m_5833_() || p.m_150110_().f_35937_)) {
            return null;
        }
        if (FriendlyFireGuard.isFriendly(maid, target)) {
            return null;
        }
        return maid.m_20280_(target) <= TARGET_RANGE * TARGET_RANGE ? target : null;
    }

    private static LivingEntity brainTarget(EntityMaid maid) {
        try {
            LivingEntity target = maid.m_5448_();
            if (target == null) {
                target = maid.m_6274_()
                        .m_21952_(MemoryModuleType.f_26372_)
                        .orElse(null);
            }
            return target;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearAirRaidBehavior", ignored);
            return null;
        }
    }

    private static void markReason(EntityMaid maid, String reason) {
        State state = STATES.get(maid.m_20148_());
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
