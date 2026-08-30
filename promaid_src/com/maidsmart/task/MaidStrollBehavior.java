package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 空闲散步行为（v1.1.0 实测一百八十三，用户："增加女仆散步的频率和速度"）。
 *
 * TLM 原生散步 = RandomStroll.stroll(0.3F, 5, 3)（MaidBrain 字节码实证）：
 * 0.3 倍速、5 格半径、触发概率 0.0010×0.09²≈8e-6/tick——平均一两小时才随机走
 * 一次，表现为"女仆站桩不动、活动范围只有几格"。
 *
 * 本行为（core 优先级 50——低于 TLM core 最高 99，只在一切更优先行为都不跑时
 * 生效）按配置间隔给空闲女仆设一个散步目标：
 * - 门禁：战斗/自保/站桩任务占用（复用 isTaskOccupied——挖矿锁定/烹饪酿造/
 *   建造等）/已有移动目标（任务在走/在追人）/坐姿骑乘 → 一律不打扰
 * - 选点：散步半径内随机；排班/在家模式（restrictTo 生效）下以限制中心为圆心、
 *   钳制在限制半径内——配合 ScheduleRangeMixin 放大的「排班活动半径」，
 *   女仆散步范围跟着扩大
 * - 落点：本格可通过 + 头顶空 + 下方实心（水中/悬崖边不选）
 * - 一次性：设完目标即结束，导航接管走过去；下次冷却由 stop 侧登记
 */
public class MaidStrollBehavior extends Behavior<EntityMaid> {
    /** 下次散步时间（gameTime tick，按女仆）——start/stop 反复进出也不刷屏 */
    private static final Map<UUID, Long> NEXT_STROLL = new HashMap<>();
    /** 选点随机源（自有实例——不依赖实体 getRandom 的 SRG 名，与 AutoCombatSwitch 同款） */
    private static final java.util.Random RNG = new java.util.Random();

    public MaidStrollBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!MaidSmartConfig.MISC_STROLL_ENABLED.get()) {
            return false;
        }
        // 自保中不散步
        if (maid.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return false;
        }
        // 本系统参战状态下不散步
        if (com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid)) {
            return false;
        }
        // 间隔节流
        long now = level.m_46467_();
        Long next = NEXT_STROLL.get(maid.m_20148_());
        if (next != null && now < next) {
            return false;
        }
        // 已有移动目标（任务在走/其他系统在驱动）不打扰
        try {
            if (maid.m_6274_().m_21952_(MemoryModuleType.f_26370_).isPresent()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        // 正在接战不打扰
        try {
            var atk = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
            if (atk.isPresent() && atk.get().m_6084_()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        // 坐姿/乘骑不散步（getVehicle 实测为 m_20202_——MaidRunOne 字节码实证）
        if (maid.isMaidInSittingPose() || maid.m_20202_() != null) {
            return false;
        }
        // 任务实质占用（挖矿/伐木锁定目标、烹饪/酿造站桩、建造未暂停、战斗任务）不散步
        if (BridgeUpBehavior.isTaskOccupied(maid)) {
            return false;
        }
        return true;
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        int r = Math.max(4, MaidSmartConfig.MISC_STROLL_RADIUS.get());
        float restrictR = maid.m_21535_();     // getRestrictRadius（未限制时 -1）
        BlockPos center = maid.m_20183_();
        boolean restricted = restrictR >= 0;
        if (restricted) {
            center = maid.m_21534_();          // getRestrictCenter——限制区中心
        }
        BlockPos pick = null;
        for (int i = 0; i < 14; i++) {
            int dx = RNG.nextInt(r * 2 + 1) - r;
            int dz = RNG.nextInt(r * 2 + 1) - r;
            int dy = RNG.nextInt(7) - 3;
            if (dx * dx + dz * dz < 9) {
                continue; // 太近（<3 格）没意义
            }
            BlockPos p = center.m_7918_(dx, dy, dz);
            if (restricted) {
                double dSq = p.m_123331_(center);
                if (dSq > (double) (restrictR * restrictR)) {
                    continue; // 超出活动半径——原地出圈会被拉回，不选
                }
            }
            if (!isStandable(level, p)) {
                continue;
            }
            pick = p;
            break;
        }
        if (pick == null) {
            return; // 找不到落点——本轮放弃，stop 侧照常登记冷却顺延
        }
        float speed = (float) (double) MaidSmartConfig.MISC_STROLL_SPEED.get();
        BlockPosTracker tracker = new BlockPosTracker(pick);
        maid.m_6274_().m_21879_(MemoryModuleType.f_26371_, tracker);
        maid.m_6274_().m_21879_(MemoryModuleType.f_26370_, new WalkTarget(tracker, speed, 1));
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return false; // 一次性——目标已设，导航接管走过去
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        NEXT_STROLL.put(maid.m_20148_(),
                level.m_46467_() + Math.max(20, MaidSmartConfig.MISC_STROLL_INTERVAL.get()));
    }

    /** 落点可站立：本格空气 + 头顶空气 + 下方实心（水中/悬崖不选——下方不实心即排除） */
    private static boolean isStandable(ServerLevel level, BlockPos p) {
        BlockState st = level.m_8055_(p);
        if (!st.m_60795_()) {
            return false;
        }
        if (!level.m_8055_(p.m_7918_(0, 1, 0)).m_60795_()) {
            return false;
        }
        BlockPos below = p.m_7918_(0, -1, 0);
        return level.m_8055_(below).m_60796_(level, below);
    }
}
