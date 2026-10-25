package com.maidsmart.task;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 潜行右键 = 把女仆搭的方块立刻强制回收成掉落物（服务端，实测一千〇六）。
 * 只认 {@code PlacedBlockTracker} 登记过的方块（蓝图建造与指标石不走追踪表 → 天然不接管）。
 * 【最低优先级】（实测一千〇六·续）：手里拿着方块、主手物品自带右键动作、或方块自己有交互
 * （方块实体 / 门·按钮·工作台等原版交互家族）→ 一律让位、原样放行；只有"这一下本来什么
 * 都不做"才回收。
 * 方块已被玩家换过（登记名 != 当前名）→ 只清登记，不动玩家的方块。
 */
public class PlacedMarkInteractHandler {

    /** 实测一千〇六·续三：链路诊断——"空手潜行右键了但该格没登记"的留痕节流（20 tick 一条） */
    private long lastMissLog = -100000L;

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_PLACED_GOLD_MARK.get()) {
                return; // 开关关 = 完全回原版
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (event.getHand() != InteractionHand.MAIN_HAND) {
                return; // 两手的右键各有一发事件：只按主手这发处理，去重
            }
            if (!player.m_6144_()) {
                return; // 必须潜行
            }
            if (!(player.m_9236_() instanceof ServerLevel sl)) {
                return;
            }
            BlockPos pos = event.getPos();
            String tracked = PlacedBlockTracker.trackedBlockId(sl, pos);
            if (tracked == null) {
                // 实测一千〇六·续三：空手潜行右键 = 本功能的测试手势（拿物品时的右键是常态、不记）
                if (player.m_21120_(InteractionHand.MAIN_HAND).m_41619_()
                        && player.m_21120_(InteractionHand.OFF_HAND).m_41619_()
                        && sl.m_46467_() - this.lastMissLog >= 20L) {
                    this.lastMissLog = sl.m_46467_();
                    com.maidsmart.tool.PromaidLog.log("描边", "潜行右键：该格无描边登记（描边表 "
                            + PlacedBlockTracker.snapshot(sl).size() + " 格）——非女仆搭的 / 已回收 / 旧会话遗留");
                }
                return; // 不是女仆搭的方块 → 交还原版
            }
            BlockState st = sl.m_8055_(pos);
            String yield = someoneElseActs(player, st);
            if (yield != null) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键让位：" + yield + "（" + tracked + "）");
                return; // 实测一千〇六·续【最低优先级】：别的交互赢，原样放行
            }
            event.setCanceled(true);
            player.m_6674_(InteractionHand.MAIN_HAND); // 挥手
            PlacedBlockTracker.untrackAny(sl, pos); // 登记先清（含描边镜像的移除通知）
            if (st.m_60795_()) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键清登记：该格已是空气（" + tracked + "）");
                return; // 已经是空气：只清登记
            }
            ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(st.m_60734_());
            if (!tracked.isEmpty() && cur != null && !tracked.equals(cur.toString())) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键清登记：方块已被换成 " + cur);
                return; // 玩家换过的方块：尊重改动，只清登记
            }
            java.util.List<ItemStack> drops = PlacedBlockTracker.reclaimDrops(st, sl, pos);
            sl.m_46796_(2001, pos, Block.m_49956_(st)); // 破坏粒子 + 音效（同到期回收）
            sl.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
            int n = 0;
            for (ItemStack d : drops) {
                if (!d.m_41619_()) {
                    Block.m_49840_(sl, pos, d); // 掉在原地，谁都能捡
                    n++;
                }
            }
            com.maidsmart.tool.PromaidLog.log("描边", "潜行右键强制回收 " + tracked + " → 掉落 " + n + " 件");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkInteractHandler", ignored);
        }
    }

    /**
     * 实测一千〇六·续【最低优先级】：这一下"本来就有别的事可做"就整条让位（返回让位原因；null = 让位给回收）。
     * 可探测集按**超集方向**取（宁可多让位、绝不抢）：未知模组的物品/方块本类不猜。
     */
    private static String someoneElseActs(ServerPlayer player, BlockState state) {
        ItemStack main = player.m_21120_(InteractionHand.MAIN_HAND);
        ItemStack off = player.m_21120_(InteractionHand.OFF_HAND);
        if (isBlockLike(main) || isBlockLike(off)) {
            return "手里拿着方块"; // 多半在搭建/放置，放置优先
        }
        if (hasRightClickAction(main)) {
            return "主手物品自带右键动作"; // 食物 / 蓄力使用 / 方块工具 / 常见放置物
        }
        if (blockHasOwnAction(state)) {
            return "方块自己可交互"; // 方块实体 / 原版交互家族
        }
        return null; // 这一下本来什么都不做 → 才轮到回收
    }

    private static boolean isBlockLike(ItemStack s) {
        return s.m_41720_() instanceof net.minecraft.world.item.BlockItem;
    }

    /** 主手物品有没有右键动作（食物 / 蓄力使用 / 常见即时作用与放置物家族） */
    private static boolean hasRightClickAction(ItemStack s) {
        if (s.m_41619_()) {
            return false;
        }
        net.minecraft.world.item.Item i = s.m_41720_();
        if (s.m_41614_() || i.m_8105_(s) > 0) {
            return true; // 食物 / 蓄力使用（弓·盾·弩·三叉戟·望远镜·药水…）
        }
        return i instanceof net.minecraft.world.item.BucketItem
                || i instanceof net.minecraft.world.item.FlintAndSteelItem
                || i instanceof net.minecraft.world.item.HoeItem
                || i instanceof net.minecraft.world.item.ShovelItem
                || i instanceof net.minecraft.world.item.AxeItem
                || i instanceof net.minecraft.world.item.ShearsItem
                || i instanceof net.minecraft.world.item.FishingRodItem
                || i instanceof net.minecraft.world.item.BoatItem
                || i instanceof net.minecraft.world.item.MinecartItem
                || i instanceof net.minecraft.world.item.SpawnEggItem
                || i instanceof net.minecraft.world.item.EnderpearlItem
                || i instanceof net.minecraft.world.item.SnowballItem
                || i instanceof net.minecraft.world.item.EggItem
                || i instanceof net.minecraft.world.item.FireChargeItem
                || i instanceof net.minecraft.world.item.EndCrystalItem;
    }

    /** 方块自己有事可做（一切方块实体 + 原版无方块实体的交互家族）——它赢 */
    private static boolean blockHasOwnAction(BlockState state) {
        Block b = state.m_60734_();
        return b instanceof net.minecraft.world.level.block.EntityBlock
                || b instanceof net.minecraft.world.level.block.ButtonBlock
                || b instanceof net.minecraft.world.level.block.LeverBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.NoteBlock
                || b instanceof net.minecraft.world.level.block.ComposterBlock
                || b instanceof net.minecraft.world.level.block.CakeBlock
                || b instanceof net.minecraft.world.level.block.RespawnAnchorBlock
                || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.CartographyTableBlock
                || b instanceof net.minecraft.world.level.block.StonecutterBlock
                || b instanceof net.minecraft.world.level.block.LoomBlock
                || b instanceof net.minecraft.world.level.block.GrindstoneBlock
                || b instanceof net.minecraft.world.level.block.AnvilBlock;
    }
}
