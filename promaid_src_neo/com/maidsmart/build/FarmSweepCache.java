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

    /** v1.1.0 实测二百七十六：锄地冷却（40 tick / 2 秒）——见 FarmSweepMixin.tillAround。
     *  v1.1.0 实测二百九十一：20 tick / 1 秒——反馈："锄地检测的频率不行，
     *  导致没有办法及时锄地"（旧版 2 秒冷却 + 依赖收割/种植触发，锄地滞后）。 */
    public static final java.util.Map<String, Long> TILL_CD =
            new ConcurrentHashMap<>();

    /** 审计：女仆卸载/移除时清理农场冷却表 */
    public static void forgetMaid(UUID maidUuid) {
        String key = maidUuid.toString();
        HARVEST_CD.remove(key);
        PLANT_CD.remove(key);
        TILL_CD.remove(key);
    }

    /** v1.1.0 实测三百零二：锄地事件监听——玩家/女仆用锄头把泥土/草方块锄成耕地时
     *  自动打标记（"曾经是耕地"）。女仆只锄有标记的地块，所以标记必须覆盖所有
     *  锄地来源：玩家手锄、女仆手锄（FarmTillDriver）、女仆原版农场任务锄地。
     *  v1.1.0 实测三百零五（反馈："玩家在进入游戏之后进行耕地。然后把根蒂踩掉，
     *  但是女仆不为所动"）：getFinalState 恒为 null——原版锄地（HoeItem.m_6225_）
     *  走 fallback 逻辑直接 setBlock，不 setFinalState，旧版 finalState==null 直接
     *  return → 玩家锄地永远打不上标记。改用 getState()（锄地前的原方块：泥土/
     *  草方块/草径）判定——锄地动作 + 原方块是锄头可锄目标 = 锄成耕地，打标。 */
    public static void onToolModification(net.neoforged.neoforge.event.level.BlockEvent.BlockToolModificationEvent event) {
        try {
            if (event.isSimulated()) {
                return;
            }
            if (!net.neoforged.neoforge.common.ItemAbilities.HOE_TILL.equals(event.getItemAbility())) {
                return;
            }
            net.minecraft.world.level.block.state.BlockState orig = event.getState();
            if (orig == null) {
                return;
            }
            net.minecraft.world.level.block.Block b = orig.getBlock();
            // 锄头可锄目标（HoeItem.TILLABLES 表：dirt/grass_block/dirt_path → farmland）。
            // v1.1.0 实测三百三十五：WHEAT 是 CropBlock（马铃薯）不是 dirt_path——
            // javap 实证 DirtPathBlock 构造 → putstatic DIRT_PATH。旧版草径判定字段错，
            // 草径被锄成耕地时不打标 → 草径踩坏后女仆不认（"可锄地块判定有问题"）。
            if (b != net.minecraft.world.level.block.Blocks.DIRT
                    && b != net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                    && b != net.minecraft.world.level.block.Blocks.DIRT_PATH) {
                return;
            }
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) {
                return;
            }
            FarmlandMarkStore.get((net.minecraft.server.level.ServerLevel) event.getLevel())
                    .mark(event.getPos());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FarmSweepCache", ignored);
        }
    }

    /** v1.1.0 实测三百零三（反馈："有些结构会自然生成耕地，那那些耕地也要打上
     *  标记"）：区块加载扫描——结构生成（村庄农田等）的耕地是直接放置方块，不触发
     *  锄地事件，需要扫描兜底。区块加载时遍历各 section 的耕地方块打标记（只扫
     *  非空 section，跳过 hasOnlyAir）。 */
    public static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        try {
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) {
                return;
            }
            net.minecraft.world.level.chunk.ChunkAccess chunk = event.getChunk();
            if (chunk == null) {
                return;
            }
            net.minecraft.world.level.ChunkPos cp = chunk.getPos();
            int minX = cp.getMiddleBlockX();
            int minZ = cp.getMiddleBlockZ();
            int minY = chunk.getMinBuildHeight();
            int maxY = chunk.getHeight();
            java.util.List<net.minecraft.core.BlockPos> found = new java.util.ArrayList<>();
            for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.getSections()) {
                if (section == null || section.hasOnlyAir()) {
                    continue; // 空 section 跳过
                }
                int secY = minY + section.getSerializedSize() * 16;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            int wy = secY + y;
                            if (wy < minY || wy > maxY) {
                                continue;
                            }
                            if (section.getBlockState(x, y, z).getBlock()
                                    == net.minecraft.world.level.block.Blocks.FARMLAND) {
                                found.add(new net.minecraft.core.BlockPos(minX + x, wy, minZ + z));
                            }
                        }
                    }
                }
            }
            if (!found.isEmpty()) {
                FarmlandMarkStore.get((net.minecraft.server.level.ServerLevel) event.getLevel())
                        .markAll(found);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FarmSweepCache", ignored);
        }
    }

    /**
     * 锄地目标判定（FarmMoveTillMixin 与 FarmSweepMixin 共用）。根因：{@code MaidFarmMoveTask
     * .shouldMoveTo} 只认"可收割/可种植"，踩坏的 dirt 不在其中 → 找不到目标 → 后续锄地逻辑永不运行。
     * 修复：注入第三个目标判定——"需要锄的地块"（dirt 或 grass_block，上方空气，且背包/主手有锄头；
     * 原版锄头本来就能锄 dirt/grass_block/dirt_path）。
     * 判定改【标记制】：只锄【有标记（曾经是耕地，由锄地事件自动打标、SavedData 持久化）且当前不是
     * 耕地】的地块——旧版"3×3 内有耕地"启发式会连锁扩散（超平坦 5×5 全被锄）。
     */
    public static boolean isTillable(net.minecraft.server.level.ServerLevel world,
                                     com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid,
                                     net.minecraft.core.BlockPos pos) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
            return false;
        }
        if (!world.isLoaded(pos)) {
            return false;
        }
        if (!maid.canDestroyBlock(pos)) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState st = world.getBlockState(pos);
        net.minecraft.world.level.block.Block b = st.getBlock();
        // 只锄泥土/草方块（原版锄头可锄目标；草方块蔓延后也能恢复耕地）
        if (b != net.minecraft.world.level.block.Blocks.DIRT
                && b != net.minecraft.world.level.block.Blocks.GRASS_BLOCK) {
            return false;
        }
        if (!world.getBlockState(pos.above()).isAir()) {
            return false; // 上方不是空气（有作物/方块）不锄
        }
        // v1.1.0 实测三百零二：标记制——没有"曾经是耕地"标记的地块不锄
        // （超平坦地形从未耕过的泥土/草方块没有标记 → 不锄）
        if (!FarmlandMarkStore.get(world).isMarked(pos)) {
            return false;
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
                net.minecraft.core.BlockPos nb = pos.offset(dx, 0, dz);
                if (world.isLoaded(nb)
                        && world.getBlockState(nb).getBlock() == net.minecraft.world.level.block.Blocks.FARMLAND) {
                    return true;
                }
            }
        }
        return false;
    }
}
