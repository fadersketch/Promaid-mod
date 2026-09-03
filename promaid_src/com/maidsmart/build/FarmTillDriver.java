package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.1.0 实测二百九十七：锄地独立驱动（用户："女仆耕地的积极性真的很差，
 * 基本上就是只是放下去一下，会锄一下地，但是之后就再也不锄地了"）。
 *
 * 根因：锄地只挂在 MaidFarmPlantTask.start 的 TAIL——而 start 只在 TARGET_POS
 * 存在时触发（MaidFarmMoveTask.searchForDestination 设置，只认可收割/可种植/
 * 可锄目标）。锄完一块泥土变成耕地后，周围没有成熟作物/空耕地时扫描空转 →
 * TARGET_POS 不设置 → start 不触发 → 锄地再也不跑。
 *
 * v1.1.0 实测二百九十八（用户："耕地改为一个顺带逻辑。先将整个农场模式运作的
 * 逻辑改回原版。但是如果在自己 5×5 范围内发现到曾经是耕地的地块，然后执行目前
 * 的换工具逻辑，并播放一下动画，并将地块变为耕地"）：锄地降级为【顺带逻辑】——
 * 农场模式运作完全回原版（FarmMoveTillMixin 注入作废，锄地目标不再占用移动
 * 扫描），本驱动每 1 秒扫描女仆周围 5×5（水平）的可锄泥土并顺带锄掉。
 * 冷却表复用 FarmSweepCache.TILL_CD。
 */
public final class FarmTillDriver {
    private static boolean registered = false;

    private FarmTillDriver() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new FarmTillDriver());
        }
    }

    /** 扫描节流（每 20 tick = 1 秒一次） */
    private int throttle = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 20) {
            return;
        }
        this.throttle = 0;
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            for (ServerLevel level : server.m_129785_()) {
                for (EntityMaid maid : level.m_45976_(EntityMaid.class,
                        new net.minecraft.world.phys.AABB(-131072.0, -4096.0, -131072.0,
                                131072.0, 4096.0, 131072.0))) {
                    if (!maid.m_6084_() || !isFarmTask(maid)) {
                        continue;
                    }
                    this.tillNearby(level, maid);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFarmTask(EntityMaid maid) {
        try {
            return maid.getTask() != null
                    && "touhou_little_maid:farm".equals(maid.getTask().getUid().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 扫描女仆周围 5×5（水平）的可锄泥土并锄掉（1 秒冷却/女仆）——
     *  v1.1.0 实测二百九十八：3×3 → 5×5（用户指定范围） */
    private void tillNearby(ServerLevel world, EntityMaid maid) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_PRODUCE_TASK_ENHANCE.get()) {
                return;
            }
            long now = world.m_46467_();
            Long last = FarmSweepCache.TILL_CD.get(maid.m_20148_().toString());
            if (last != null && now - last < 20) {
                return; // 1 秒冷却（与 FarmSweepMixin.tillAround 同节奏）
            }
            // 先确认背包/主手有锄头（没有就不锄，也不写冷却——补锄头后立即生效）
            if (!com.maidsmart.task.MaidToolAutoEquip.ensureHoeForFarm(maid)) {
                return;
            }
            FarmSweepCache.TILL_CD.put(maid.m_20148_().toString(), now);
            BlockPos base = maid.m_20183_();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos b = base.m_7918_(dx, 0, dz);
                    if (!world.m_46749_(b)) {
                        continue; // 区块未加载跳过
                    }
                    if (!FarmSweepCache.isTillable(world, maid, b)) {
                        continue;
                    }
                    // 锄成耕地（与 HoeItem 静态表同目标：dirt/grass_block → farmland）
                    world.m_7731_(b, net.minecraft.world.level.block.Blocks.f_50093_.m_49966_(), 3);
                    // 锄地音效（HoeItem.m_6225_ 字节码实证：SoundEvents.f_11955_）
                    world.m_5594_(null, b, net.minecraft.sounds.SoundEvents.f_11955_,
                            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
                    maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂
                    // 消耗 1 点耐久（HoeItem.m_6225_ 同款：m_41622_(1, LivingEntity, Consumer)）
                    ItemStack hoe = maid.m_21205_();
                    if (!hoe.m_41619_()) {
                        hoe.m_41622_(1, maid, e -> {
                        });
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
