package com.maidsmart.build;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.5.386：农场冷却表普通工具类（从 FarmSweepMixin 抽出）。
 *
 * 根因：AiMemoryManager.onEntityLeaveLevel 直接引用了 com.maidsmart.mixin.FarmSweepMixin
 * 的静态方法 forgetMaid。Mixin 类不能被普通 classloader 当作普通类加载——
 * Mixin 系统会把它们标记为 "invalid"，触发 NoClassDefFoundError（crash-2026-
 * 08-17_15.24.41：交互女仆触发实体离开事件 → AiMemoryManager.onEntityLeaveLevel
 * → 引用 FarmSweepMixin → ModLauncherClassTracker.handlesClass 抛 NoClassDefFoundError）。
 *
 * 修复：把静态冷却表和 forgetMaid 提到这个普通工具类，FarmSweepMixin 委托到这里，
 * AiMemoryManager 也改为引用本类。Mixin 类不再被业务代码直接引用。
 */
public final class FarmSweepCache {
    private FarmSweepCache() {
    }

    /** 连锁收割冷却（10 tick）——见 FarmSweepMixin.sweepChain */
    public static final java.util.Map<String, Long> HARVEST_CD =
            new ConcurrentHashMap<>();

    /** 批量种植冷却（40 tick / 2 秒）——见 FarmSweepMixin.batchPlantAround */
    public static final java.util.Map<String, Long> PLANT_CD =
            new ConcurrentHashMap<>();

    /** v1.1.0 实测二百七十六：锄地冷却（40 tick / 2 秒）——见 FarmSweepMixin.tillAround */
    public static final java.util.Map<String, Long> TILL_CD =
            new ConcurrentHashMap<>();

    /** 审计：女仆卸载/移除时清理农场冷却表 */
    public static void forgetMaid(UUID maidUuid) {
        String key = maidUuid.toString();
        HARVEST_CD.remove(key);
        PLANT_CD.remove(key);
        TILL_CD.remove(key);
    }

    /**
     * v1.1.0 实测二百七十八：锄地目标判定（FarmMoveTillMixin 与 FarmSweepMixin 共用）。
     *
     * 根因：MaidFarmMoveTask.shouldMoveTo 只认"可收割/可种植"两种目标，踩坏的泥土
     * （dirt）不在其中 → 找不到目标 → TARGET_POS 永不设置 → MaidFarmPlantTask.start
     * 永不触发 → 挂在 start TAIL 的锄地逻辑永不运行。
     *
     * 修复：shouldMoveTo 注入第三个目标判定——"需要锄的泥土"（dirt + 上方空气 +
     * 3×3 内有耕地 + 背包/主手有锄头）也算目标，女仆走过去后 start 触发锄地。
     */
    public static boolean isTillable(net.minecraft.server.level.ServerLevel world,
                                     com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid,
                                     net.minecraft.core.BlockPos pos) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return false;
        }
        if (!world.m_46749_(pos)) {
            return false;
        }
        if (!maid.canDestroyBlock(pos)) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState st = world.m_8055_(pos);
        if (st.m_60734_() != net.minecraft.world.level.block.Blocks.f_50493_) {
            return false; // 只锄泥土（dirt）
        }
        if (!world.m_8055_(pos.m_7494_()).m_60795_()) {
            return false; // 上方不是空气（有作物/方块）不锄
        }
        if (!nearFarmland(world, pos)) {
            return false; // 3×3 内无耕地 = 不是"曾经是耕地"
        }
        return com.maidsmart.task.MaidToolAutoEquip.ensureHoeForFarm(maid);
    }

    /** 水平 3×3 内是否存在耕地（farmland）——"曾经是耕地"的农田区域信号 */
    public static boolean nearFarmland(net.minecraft.server.level.ServerLevel world, net.minecraft.core.BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                net.minecraft.core.BlockPos nb = pos.m_7918_(dx, 0, dz);
                if (world.m_46749_(nb)
                        && world.m_8055_(nb).m_60734_() == net.minecraft.world.level.block.Blocks.f_50093_) {
                    return true;
                }
            }
        }
        return false;
    }
}
