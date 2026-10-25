package com.maidsmart.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 潜行右键 = 把女仆搭的方块立刻强制回收成掉落物（服务端，实测一千〇六，1.21.1 NeoForge 版）。
 * 只认 {@code PlacedBlockTracker} 登记过的方块（蓝图建造与指标石不走追踪表 → 天然不接管）。
 * 【最低优先级】（实测一千〇六·续）：手里拿着方块、主手物品自带右键动作、或方块自己有交互
 * （方块实体 / 门·按钮·工作台等原版交互家族）→ 一律让位、原样放行；只有"这一下本来什么
 * 都不做"才回收。
 * 方块已被玩家换过（登记名 != 当前名）→ 只清登记，不动玩家的方块。
 * 【取消口径】NeoForge 的 setCanceled(true) 只把 useBlock/useItem 置 FALSE，
 * cancellationResult 仍留默认 PASS——显式补一条 FAIL（与 Forge 版取消语义对齐，
 * 服务端把这一下判为"已处理但不通过"，客户端预测的回滚同 Forge）。
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
            if (!player.isShiftKeyDown()) {
                return; // 必须潜行
            }
            if (!(player.level() instanceof ServerLevel sl)) {
                return;
            }
            BlockPos pos = event.getPos();
            String tracked = PlacedBlockTracker.trackedBlockId(sl, pos);
            if (tracked == null) {
                // 实测一千〇六·续三：空手潜行右键 = 本功能的测试手势（拿物品时的右键是常态、不记）
                if (player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
                        && player.getItemInHand(InteractionHand.OFF_HAND).isEmpty()
                        && sl.getGameTime() - this.lastMissLog >= 20L) {
                    this.lastMissLog = sl.getGameTime();
                    com.maidsmart.tool.PromaidLog.log("描边", "潜行右键：该格无描边登记（描边表 "
                            + PlacedBlockTracker.snapshot(sl).size() + " 格）——非女仆搭的 / 已回收 / 旧会话遗留");
                }
                return; // 不是女仆搭的方块 → 交还原版
            }
            BlockState st = sl.getBlockState(pos);
            String yield = someoneElseActs(player, st);
            if (yield != null) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键让位：" + yield + "（" + tracked + "）");
                return; // 实测一千〇六·续【最低优先级】：别的交互赢，原样放行
            }
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            player.swing(InteractionHand.MAIN_HAND); // 挥手
            PlacedBlockTracker.untrackAny(sl, pos); // 登记先清（含描边镜像的移除通知）
            if (st.isAir()) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键清登记：该格已是空气（" + tracked + "）");
                return; // 已经是空气：只清登记
            }
            ResourceLocation cur = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            if (!tracked.isEmpty() && cur != null && !tracked.equals(cur.toString())) {
                com.maidsmart.tool.PromaidLog.log("描边", "潜行右键清登记：方块已被换成 " + cur);
                return; // 玩家换过的方块：尊重改动，只清登记
            }
            java.util.List<ItemStack> drops = PlacedBlockTracker.reclaimDrops(st, sl, pos);
            sl.levelEvent(2001, pos, Block.getId(st)); // 破坏粒子 + 音效（同到期回收）
            sl.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            int n = 0;
            for (ItemStack d : drops) {
                if (!d.isEmpty()) {
                    Block.popResource(sl, pos, d); // 掉在原地，谁都能捡
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
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (isBlockLike(main) || isBlockLike(off)) {
            return "手里拿着方块"; // 多半在搭建/放置，放置优先
        }
        if (hasRightClickAction(main, player)) {
            return "主手物品自带右键动作"; // 食物 / 蓄力使用 / 方块工具 / 常见放置物
        }
        if (blockHasOwnAction(state)) {
            return "方块自己可交互"; // 方块实体 / 原版交互家族
        }
        return null; // 这一下本来什么都不做 → 才轮到回收
    }

    private static boolean isBlockLike(ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.BlockItem;
    }

    /** 主手物品有没有右键动作（食物 / 蓄力使用 / 常见即时作用与放置物家族） */
    private static boolean hasRightClickAction(ItemStack s, ServerPlayer player) {
        if (s.isEmpty()) {
            return false;
        }
        net.minecraft.world.item.Item i = s.getItem();
        if (s.has(net.minecraft.core.component.DataComponents.FOOD) || s.getUseDuration(player) > 0) {
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
        net.minecraft.world.level.block.Block b = state.getBlock();
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
