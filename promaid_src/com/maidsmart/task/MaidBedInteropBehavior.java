package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.Comparator;
import java.util.Optional;

/**
 * 床铺互通·方向一（v1.1.0 实测四百一十八，用户："让女仆床和玩家床的代码互通。
 * 女仆和玩家可以互相使用对方的床"）。
 *
 * TLM 原版睡觉只认自己的女仆床：MaidBedTask（REST 活动里唯一的睡觉行为）用 POI
 * 只查 InitPoi.MAID_BED，start 里还硬判 state.is(InitBlocks.MAID_BED.get())——
 * 原版 16 色床既不进这个 POI 类型，也不满足这个 is()，所以原版床对女仆完全不可用。
 *
 * 本行为挂在 REST 活动（通过 ProMaidExtension 的 getRestBehaviors 注册），
 * 优先级 6（TLM 自带 MaidBedTask=5 之后、随机散步=20 之前）：
 * - 用原版 PoiTypes.HOME 找最近的原版床（原版把 16 色床的 HEAD 都注册进 HOME）；
 * - 校验 PART==HEAD、!OCCUPIED、blockState.is(BlockTags.BEDS)（防别的模组往 HOME
 *   POI 里塞非床方块），有 home 限制（restrictTo）时还要求床在活动范围内；
 * - 够远就用 WalkTarget 走过去，到位后调 maid.startSleeping(head) + setPos，
 *   与 TLM 自己的 MaidBedTask.start 同款（占床/回血/好感度都走 TLM 既有逻辑）。
 *
 * 不改 TLM 源码（全部走 addon 扩展点）。开关 MISC_BED_INTEROP 关掉后本行为不启动。
 */
public class MaidBedInteropBehavior extends Behavior<EntityMaid> {
    /** 到位距离（格）：与 TLM MaidBedTask 的 closeEnoughDist=2 保持一致 */
    private static final int CLOSE_ENOUGH = 2;
    /** 睡觉走路速度：与 TLM MaidBedTask(0.6f, 2) 同款 */
    private static final float SPEED = 0.6f;

    public MaidBedInteropBehavior() {
        // 与 TLM 自带 MaidBedTask 同款门禁：WALK_TARGET 必须缺席——否则两边会
        // 同 tick 各推一个行走目标（女仆床 vs 原版床）来回抢，表现为原地抽搐。
        // TLM MaidBedTask 优先级 5 先跑：有女仆床时它先设 WALK_TARGET 把我们挡住
        // （= 女仆床优先）；没有女仆床它返回 false 不设记忆，我们才接手找原版床。
        super(maidsmart$gate(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static java.util.Map<MemoryModuleType<?>, MemoryStatus> maidsmart$gate() {
        java.util.Map<MemoryModuleType<?>, MemoryStatus> m = new java.util.HashMap<>();
        m.put(MemoryModuleType.f_26370_, MemoryStatus.VALUE_ABSENT);
        return m;
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!MaidSmartConfig.MISC_BED_INTEROP.get()) {
            return false;
        }
        if (!maid.canBrainMoving()) {
            return false;
        }
        // 自保/参战/坐姿骑乘中不找床（与 TLM MaidBedTask 只在 REST 活动跑的口径对齐）
        try {
            if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        if (maid.isMaidInSittingPose() || maid.m_20202_() != null) {
            return false;
        }
        BlockPos bed = this.findBed(level, maid);
        if (bed == null) {
            return false;
        }
        if (this.isCloseEnough(maid, bed)) {
            return true; // 到位——start 里直接睡
        }
        // 太远：走过去（下一 tick 会被重新评估）
        BehaviorUtils.m_22617_(maid, bed, SPEED, 1);
        return false;
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos bed = this.findBed(level, maid);
        if (bed == null) {
            return;
        }
        BlockState st = level.m_8055_(bed);
        if (st.m_60734_() instanceof BedBlock
                && st.m_61143_(BedBlock.f_49440_) == BedPart.HEAD
                && !st.m_61143_(BedBlock.f_49441_)) {
            maid.m_5802_(bed);
            maid.m_6034_(bed.m_123341_() + 0.5, bed.m_123342_() + 0.5625, bed.m_123343_() + 0.5);
        }
        // 与 TLM MaidBedTask 一致：睡下后清掉行走/观察记忆，避免躺着还在走
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        maid.m_6274_().m_21936_(MemoryModuleType.f_26371_);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return false; // 一次性：走近由 checkExtraStartConditions 每 tick 推 WalkTarget
    }

    /** 是否已到床边（水平 + 垂直都在 2 格内，与 TLM 的 closeEnoughDist 口径一致） */
    private boolean isCloseEnough(EntityMaid maid, BlockPos bed) {
        double dx = maid.m_20185_() - (bed.m_123341_() + 0.5);
        double dy = maid.m_20186_() - (bed.m_123342_() + 0.5);
        double dz = maid.m_20189_() - (bed.m_123343_() + 0.5);
        return dx * dx + dy * dy + dz * dz < (double) (CLOSE_ENOUGH * CLOSE_ENOUGH);
    }

    /**
     * 找最近可用的原版床 HEAD：原版 PoiTypes.HOME → 校验是床 HEAD 且未被占用，
     * 有限制（restrictTo / home 模式）时必须在活动范围内。
     */
    private BlockPos findBed(ServerLevel level, EntityMaid maid) {
        try {
            BlockPos searchPos = maid.getBrainSearchPos();
            int range = Math.max(8, Math.round(maid.m_21535_())); // getRestrictRadius
            PoiManager poi = level.m_8904_();
            Optional<BlockPos> found = poi.m_27181_(
                            holder -> holder.m_203565_(PoiTypes.f_218060_), // PoiTypes.HOME
                            searchPos, range, PoiManager.Occupancy.ANY)
                    .map(rec -> rec.m_27257_())                    // PoiRecord.getPos
                    .filter(pos -> this.isUsableBed(level, maid, pos))
                    .min(Comparator.comparingDouble(pos -> pos.m_123331_(maid.m_20183_())));
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 指定坐标是否为可睡的床 HEAD（未被占用 + 在活动范围内） */
    private boolean isUsableBed(ServerLevel level, EntityMaid maid, BlockPos pos) {
        BlockState st = level.m_8055_(pos);
        if (!st.m_204336_(BlockTags.f_13038_)) { // BlockTags.BEDS
            return false;
        }
        if (st.m_61143_(BedBlock.f_49440_) != BedPart.HEAD) {
            return false;
        }
        if (st.m_61143_(BedBlock.f_49441_)) {
            return false; // 已被占用
        }
        return maid.m_21536_() ? maid.m_21534_().m_123331_(pos) < (double) (maid.m_21535_() * maid.m_21535_()) : true;
    }
}
