package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;

/**
 * v1.5.189：被动插火把（玩家贴身辅助）——core 行为，非工作状态。
 *
 * 主人周围 3×3 的黑暗格（方块亮度 < 阈值，默认 7，天光/方块光都算）自动插火把：
 * 背包有 torch（SRG f_42000_ = StandingAndWallBlockItem(torch)）且脚下有实心支撑
 * 时放置（消耗 1 个火把）。冷却 1.5 秒（防连插刷屏）。
 * 总开关：combat.torchPlacerEnable（默认开）+ 阈值可调。
 */
public class MaidTorchPlacerBehavior extends Behavior<EntityMaid> {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private int torchCooldown = 0;
    /** v1.5.227：canUse 首调诊断标记（只打第一条） */
    private boolean canUseLogged = false;

    public MaidTorchPlacerBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        // v1.5.227 诊断：行为构造 = 类被加载 + 实例被创建
        LOGGER.info("torch-placer constructed");
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.227 诊断：canUse 被 Brain 调用过一次后不再刷（只打第一条）
        if (!this.canUseLogged) {
            this.canUseLogged = true;
            LOGGER.info("torch-placer canUse first-call: enabled={}",
                    com.maidsmart.config.MaidSmartConfig.TORCH_PLACER_ENABLE.get());
        }
        return com.maidsmart.config.MaidSmartConfig.TORCH_PLACER_ENABLE.get();
    }

    /**
     * v1.5.228【重大修复】：canStillUse 必须重写为 true——原版 1.20.1 Behavior 的
     * canStillUse 默认返回【false】！行为 tryStart 后下一 tick 立即被 tickOrStop
     * 停掉，tick() 永远不执行。插火把行为从 v1.5.189 诞生起就没 tick 过。
     */
    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return true;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.torchCooldown > 0) {
            this.torchCooldown--;
            return;
        }
        if (!(maid.m_269323_() instanceof ServerPlayer owner)) {
            return;
        }
        if (!owner.m_6084_()) {
            return;
        }
        if (maid.m_20238_(owner.m_20182_()) > 8.0) {
            return; // 主人不在身边（> 8 格）不管
        }
        int threshold = com.maidsmart.config.MaidSmartConfig.TORCH_DARK_THRESHOLD.get();
        net.minecraft.core.BlockPos base = owner.m_20183_();
        // 主人脚下 3×3 找最暗格（方块亮度 = LightLayer.BLOCK 的 m_45517_，0-15）
        net.minecraft.core.BlockPos target = null;
        int darkest = threshold;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                net.minecraft.core.BlockPos p = base.m_7918_(dx, 0, dz);
                int light = level.m_45517_(net.minecraft.world.level.LightLayer.BLOCK, p);
                if (light < darkest) {
                    darkest = light;
                    target = p;
                }
            }
        }
        if (target == null) {
            return; // 周围够亮
        }
        // 目标格可放（空气/可替换）且脚下有支撑
        if (!level.m_8055_(target).m_60795_()) {
            return;
        }
        if (level.m_8055_(target.m_7918_(0, -1, 0)).m_60795_()) {
            return; // 悬空
        }
        // 找背包火把并放置
        int slot = findTorch(maid);
        if (slot < 0) {
            return; // 没有火把
        }
        net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
        ItemStack torch = inv.getStackInSlot(slot);
        level.m_7731_(target, net.minecraft.world.level.block.Blocks.f_50081_.m_49966_(), 3);
        torch.m_41774_(1); // 消耗一个火把
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        this.torchCooldown = 30; // 1.5 秒
    }

    /** 背包里找火把（SRG f_42000_ = torch 物品）槽位；无则 -1 */
    private static int findTorch(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.m_41619_() && stack.m_150930_(net.minecraft.world.item.Items.f_42000_)) {
                    return i;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** v1.5.215：诊断——主人脚下 3×3 里最暗的方块亮度（LightLayer.BLOCK，0-15） */
    private static int darkestAround(ServerLevel level, net.minecraft.core.BlockPos base) {
        int darkest = 15;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int light = level.m_45517_(net.minecraft.world.level.LightLayer.BLOCK,
                        base.m_7918_(dx, 0, dz));
                if (light < darkest) {
                    darkest = light;
                }
            }
        }
        return darkest;
    }
}
