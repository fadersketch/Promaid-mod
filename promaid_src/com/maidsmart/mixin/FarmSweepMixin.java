package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFarmPlantTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.130：农场专项——连收连种（一次到田里处理一整片）。
 *
 * 根因：MaidFarmPlantTask.start 一次只处理 TARGET_POS 指定的 1 个目标格
 * （收割该格作物→清目标→下一轮再找下一个目标→走过去→再收割），大块农田
 * 来回跑，效率低。
 *
 * 修复：start 收割完目标格后，把目标格周围 3x3（水平）内已成熟的作物一并
 * 收割并顺手补种——女仆到田里一次处理一片，来回跑次数降到约 1/8。
 * 与原逻辑同机制（canHarvest 检查 + 真实消耗种子），无作弊。
 * 总开关：misc.produceTaskEnhance。
 */
@Mixin(MaidFarmPlantTask.class)
public abstract class FarmSweepMixin {
    @Shadow
    private IFarmTask task;

    /** HEAD 时记下的目标格（start 原逻辑末尾会清 TARGET_POS，TAIL 时已读不到） */
    private BlockPos maidsmart$basePos = null;

    @Inject(method = "start", at = @At("HEAD"))
    private void maidsmart$captureBasePos(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        java.util.Optional<net.minecraft.world.entity.ai.behavior.PositionTracker> tracker =
                maid.m_6274_().m_21952_(
                        (net.minecraft.world.entity.ai.memory.MemoryModuleType) InitEntities.TARGET_POS.get());
        this.maidsmart$basePos = tracker.map(pos -> pos.m_6675_()).orElse(null);
    }

    @Inject(method = "start", at = @At("TAIL"))
    private void maidsmart$sweepSurrounding(ServerLevel world, EntityMaid maid, long gameTime, CallbackInfo ci) {
        BlockPos base = this.maidsmart$basePos;
        this.maidsmart$basePos = null;
        if (base == null) {
            return;
        }
        boolean enhance = com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get();
        boolean chain = com.maidsmart.config.MaidSmartConfig.MISC_CHAIN_HARVEST.get();
        // v1.5.236：批量种植独立开关（默认开启）——每次处理农田时，把相连的
        // 空耕地一次种一片（种子真实消耗），女仆不再一格跑一趟；与连锁收割
        // 同机制（canPlant 检查）、同设置格式（misc.batchPlant / batchPlantLimit）
        this.batchPlantAround(world, maid, base);
        if (!enhance && !chain) {
            return;
        }
        // v1.5.161：收获物自动收集——原逻辑收割的目标格产物（作物/种子）也直接进背包
        if (com.maidsmart.config.MaidSmartConfig.MISC_AUTO_COLLECT.get()) {
            this.collectDrops(maid, world, base.m_7494_());
        }
        if (chain) {
            // v1.5.161：农场连锁收获——BFS 蔓延连锁收割相连农田里的成熟作物
            this.sweepChain(world, maid, base);
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 目标格已由原逻辑处理
                }
                BlockPos b = base.m_7918_(dx, 0, dz);
                // v1.5.214：区块加载检查——start 时农田可能位于正在加载的区块
                // 边缘，Server thread 上 getBlockState 未加载区块会触发区块加载，
                // 而加载任务要 Server thread 自己处理 → 自锁卡死（线程 dump 实证：
                // sweepChain → Level.m_8055_ → BlockableEventLoop.parkNanos 永久等待）
                if (!world.m_46749_(b)) {
                    continue;
                }
                BlockPos crop = b.m_7494_();
                BlockState st = world.m_8055_(crop);
                if (!maid.canDestroyBlock(crop)) {
                    continue;
                }
                if (this.task.canHarvest(maid, crop, st)) {
                    this.task.harvest(maid, crop, st);
                    maid.m_6674_(InteractionHand.MAIN_HAND);
                    if (com.maidsmart.config.MaidSmartConfig.MISC_AUTO_COLLECT.get()) {
                        this.collectDrops(maid, world, crop);
                    }
                    plantBack(maid, b, world);
                }
            }
        }
    }

    /**
     * v1.5.161：农场连锁收获（misc.chainHarvest，默认关闭）——以目标格为中心 BFS
     * 蔓延（水平 4 方向），连锁收割相连农田里所有成熟作物并顺手补种；一次上限从
     * 配置面板读取（misc.chainHarvestLimit，默认 24 格，大农田多轮任务自然清完），
     * BFS 展开上限 96 防大农场卡顿。
     * 与原逻辑同机制（canHarvest 检查 + 真实消耗种子），无作弊。
     */
    // v1.5.386：冷却表与 forgetMaid 已抽到 com.maidsmart.build.FarmSweepCache。
    // Mixin 类不能被业务代码当作普通类引用，否则触发 NoClassDefFoundError。
    // FarmSweepMixin 的静态字段通过 FarmSweepCache.HARVEST_CD / PLANT_CD 访问。

    private void sweepChain(ServerLevel world, EntityMaid maid, BlockPos base) {
        long now = world.m_46467_();
        Long last = com.maidsmart.build.FarmSweepCache.HARVEST_CD.get(maid.m_20148_().toString());
        if (last != null && now - last < 10) {
            return;
        }
        com.maidsmart.build.FarmSweepCache.HARVEST_CD.put(maid.m_20148_().toString(), now);
        // v1.5.248：一次拿背包，循环内补种复用
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        BlockPos baseImm = base.m_7949_();
        queue.add(baseImm);
        visited.add(baseImm);
        int limit = com.maidsmart.config.MaidSmartConfig.MISC_CHAIN_HARVEST_LIMIT.get();
        int harvested = 0;
        while (!queue.isEmpty() && harvested < limit) {
            BlockPos b = queue.poll();
            // v1.5.214：区块加载检查（同 sweepSurrounding）——BFS 蔓延可能跨出
            // 已加载区块，未加载直接跳过不收割也不继续蔓延
            if (!world.m_46749_(b)) {
                continue;
            }
            if (!b.equals(baseImm)) {
                BlockPos crop = b.m_7494_();
                BlockState st = world.m_8055_(crop);
                if (maid.canDestroyBlock(crop) && this.task.canHarvest(maid, crop, st)) {
                    this.task.harvest(maid, crop, st);
                    maid.m_6674_(InteractionHand.MAIN_HAND);
                    if (com.maidsmart.config.MaidSmartConfig.MISC_AUTO_COLLECT.get()) {
                        this.collectDrops(maid, world, crop);
                    }
                    this.plantWith(inv, maid, b, world);
                    harvested++;
                }
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                net.minecraft.core.Vec3i step = d.m_122436_();
                BlockPos nb = b.m_7918_(step.m_123341_(), 0, step.m_123343_());
                if (visited.add(nb.m_7949_()) && queue.size() < 96 && world.m_46749_(nb)) {
                    queue.add(nb.m_7949_());
                }
            }
        }
    }

    /** v1.5.161：收获物自动收集——收割点附近的掉落物直接进女仆背包（不落地） */
    private void collectDrops(EntityMaid maid, ServerLevel world, BlockPos crop) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(crop).m_82400_(1.5);
        for (net.minecraft.world.entity.item.ItemEntity e :
                world.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (e.m_6084_()) {
                maid.pickupItem(e, false);
            }
        }
    }


    private void batchPlantAround(ServerLevel world, EntityMaid maid, BlockPos base) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_BATCH_PLANT.get()) {
            return;
        }
        long now = world.m_46467_();
        Long last = com.maidsmart.build.FarmSweepCache.PLANT_CD.get(maid.m_20148_().toString());
        if (last != null && now - last < 40) {
            return; // v1.5.248：2 秒冷却，防每次 start 全量 BFS 卡顿
        }
        com.maidsmart.build.FarmSweepCache.PLANT_CD.put(maid.m_20148_().toString(), now);
        int limit = com.maidsmart.config.MaidSmartConfig.MISC_BATCH_PLANT_LIMIT.get();
        // v1.5.248：一次拿背包，循环内复用（不再每格 plantBack 重扫背包找种子）
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        BlockPos baseImm = base.m_7949_();
        queue.add(baseImm);
        visited.add(baseImm);
        int planted = 0;
        while (!queue.isEmpty() && planted < limit) {
            BlockPos b = queue.poll();
            // v1.5.214：区块加载检查（同 sweepChain）——未加载区块不种也不蔓延
            if (!world.m_46749_(b)) {
                continue;
            }
            if (!b.equals(baseImm)) {
                BlockPos crop = b.m_7494_();
                BlockState cropSt = world.m_8055_(crop);
                // 只有空耕地（上方是空气）才批量种；已有作物（未成熟/成熟）跳过
                if (cropSt.m_60795_() && this.plantWith(inv, maid, b, world)) {
                    planted++;
                }
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                net.minecraft.core.Vec3i step = d.m_122436_();
                BlockPos nb = b.m_7918_(step.m_123341_(), 0, step.m_123343_());
                if (visited.add(nb.m_7949_()) && queue.size() < 96 && world.m_46749_(nb)) {
                    queue.add(nb.m_7949_());
                }
            }
        }
    }

    /** v1.5.248：用给定背包找种子种植（一次 getAvailableInv 复用，不再每格重扫） */
    private boolean plantWith(CombinedInvWrapper inv, EntityMaid maid, BlockPos basePos, ServerLevel world) {
        try {
            BlockState baseState = world.m_8055_(basePos);
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack seed = inv.getStackInSlot(i);
                if (seed.m_41619_() || !this.task.isSeed(seed)) {
                    continue;
                }
                if (!this.task.canPlant(maid, basePos, baseState, seed)) {
                    continue;
                }
                ItemStack remain = this.task.plant(maid, basePos, baseState, seed);
                inv.setStackInSlot(i, remain);
                maid.m_6674_(InteractionHand.MAIN_HAND);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 收割后顺手补种（与原 start 的找种子/种植逻辑同款，真实消耗种子）。
     *  v1.5.236：返回是否种成功（批量种植统计用） */
    private boolean plantBack(EntityMaid maid, BlockPos basePos, ServerLevel world) {
        try {
            return this.plantWith(maid.getAvailableInv(true), maid, basePos, world);
        } catch (Exception ignored) {
            return false;
        }
    }
}
