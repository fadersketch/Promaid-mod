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
     *  v1.1.0 实测二百九十一：20 tick / 1 秒——用户："锄地检测的频率不行，
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
     *  v1.1.0 实测三百零五（用户："玩家在进入游戏之后进行耕地。然后把根蒂踩掉，
     *  但是女仆不为所动"）：getFinalState 恒为 null——原版锄地（HoeItem.m_6225_）
     *  走 fallback 逻辑直接 setBlock，不 setFinalState，旧版 finalState==null 直接
     *  return → 玩家锄地永远打不上标记。改用 getState()（锄地前的原方块：泥土/
     *  草方块/草径）判定——锄地动作 + 原方块是锄头可锄目标 = 锄成耕地，打标。 */
    public static void onToolModification(net.minecraftforge.event.level.BlockEvent.BlockToolModificationEvent event) {
        try {
            if (event.isSimulated()) {
                return;
            }
            if (!net.minecraftforge.common.ToolActions.HOE_TILL.equals(event.getToolAction())) {
                return;
            }
            net.minecraft.world.level.block.state.BlockState orig = event.getState();
            if (orig == null) {
                return;
            }
            net.minecraft.world.level.block.Block b = orig.m_60734_();
            // 锄头可锄目标（HoeItem.f_41332_ 表：dirt/grass_block/dirt_path → farmland）。
            // v1.1.0 实测三百三十五：f_50092_ 是 CropBlock（马铃薯）不是 dirt_path——
            // javap 实证 DirtPathBlock 构造 → putstatic f_152481_。旧版草径判定字段错，
            // 草径被锄成耕地时不打标 → 草径踩坏后女仆不认（"可锄地块判定有问题"）。
            if (b != net.minecraft.world.level.block.Blocks.f_50493_
                    && b != net.minecraft.world.level.block.Blocks.f_50440_
                    && b != net.minecraft.world.level.block.Blocks.f_152481_) {
                return;
            }
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) {
                return;
            }
            FarmlandMarkStore.get((net.minecraft.server.level.ServerLevel) event.getLevel())
                    .mark(event.getPos());
        } catch (Throwable ignored) {
        }
    }

    /** v1.1.0 实测三百零三（用户："有些结构会自然生成耕地，那那些耕地也要打上
     *  标记"）：区块加载扫描——结构生成（村庄农田等）的耕地是直接放置方块，不触发
     *  锄地事件，需要扫描兜底。区块加载时遍历各 section 的耕地方块打标记（只扫
     *  非空 section，跳过 hasOnlyAir）。 */
    public static void onChunkLoad(net.minecraftforge.event.level.ChunkEvent.Load event) {
        try {
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) {
                return;
            }
            net.minecraft.world.level.chunk.ChunkAccess chunk = event.getChunk();
            if (chunk == null) {
                return;
            }
            net.minecraft.world.level.ChunkPos cp = chunk.m_7697_();
            int minX = cp.m_151390_();
            int minZ = cp.m_151393_();
            int minY = chunk.m_141937_();
            int maxY = chunk.m_141928_();
            java.util.List<net.minecraft.core.BlockPos> found = new java.util.ArrayList<>();
            for (net.minecraft.world.level.chunk.LevelChunkSection section : chunk.m_7103_()) {
                if (section == null || section.m_188008_()) {
                    continue; // 空 section 跳过
                }
                int secY = minY + section.m_63020_() * 16;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            int wy = secY + y;
                            if (wy < minY || wy > maxY) {
                                continue;
                            }
                            if (section.m_62982_(x, y, z).m_60734_()
                                    == net.minecraft.world.level.block.Blocks.f_50093_) {
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
        } catch (Throwable ignored) {
        }
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
     *
     * v1.1.0 实测二百九十一：判定扩展——dirt 之外认 grass_block（f_50440_，
     * javap 实证：GrassBlock 构造 → f_50440_）。用户："当地块从泥土变为草方块
     * 之后，女仆就会彻底失去判定"——草方块蔓延到泥土上后旧版只认 dirt → 不再
     * 锄。原版锄头（HoeItem.f_41332_ 表）本来就能锄 dirt/grass_block/dirt_path
     * → farmland，判定与锄地动作对齐。
     *
     * v1.1.0 实测三百零二（用户："对曾经已经是耕地的地块打上一个标记……在 5×5
     * 范围内检索到以后发现不是耕地就动用锄头将其锄成耕地"）：判定改为【标记制】——
     * 旧版"3×3 内有耕地"启发式会连锁扩散（锄一块后周围 3×3 就有耕地 → 超平坦
     * 地形 5×5 全被锄成耕地）。现在只锄【有标记（曾经是耕地）且当前不是耕地】的
     * 地块——标记由锄地事件自动打（玩家/女仆锄地时），SavedData 持久化。
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
        net.minecraft.world.level.block.Block b = st.m_60734_();
        // 只锄泥土/草方块（原版锄头可锄目标；草方块蔓延后也能恢复耕地）
        if (b != net.minecraft.world.level.block.Blocks.f_50493_
                && b != net.minecraft.world.level.block.Blocks.f_50440_) {
            return false;
        }
        if (!world.m_8055_(pos.m_7494_()).m_60795_()) {
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
