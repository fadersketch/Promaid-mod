package com.maidsmart.storage;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.List;

/**
 * v1.1.0 实测三百零八：精妙储存终端绑定/解绑交互 + 女仆从终端取物工具。
 *
 * 两个新物品：
 * - storage_bind_card（终端绑定卡，9 皮革合成）：手持右击【自己的女仆】→ 进入
 *   绑定模式（记住目标女仆）；再右击【精妙储存控制器】（sophisticatedstorage:
 *   controller）→ 该女仆绑定此终端（替换旧绑定），卡不消耗（可反复使用），
 *   气泡/系统消息确认。
 * - storage_unbind_card（终端解绑卡，9 甘蔗合成）：右击女仆 → 清空她的绑定，
 *   卡不消耗。
 *
 * 取物设计：控制器方块实体（ControllerBlockEntity）实现了 Forge IItemHandler——
 * 女仆把它当背包直接抽取物品（extractItem）。绑定关系按【维度】存储（SavedData，
 * 每维度一份）——主世界的绑定只作用于主世界终端。
 *
 * 多女仆一终端：终端坐标 → 列表，每只女仆各自存一份坐标，天然支持多只女仆
 * 绑同一终端。
 */
public final class BoundStorageInteractHandler {
    public static final String BIND_MODE_TMP = "maid_smart_bind_target"; // 玩家 pendingData 暂存目标女仆 UUID

    /** 无实例状态——事件由 ProMaidMod 注册（new BoundStorageInteractHandler()） */
    public BoundStorageInteractHandler() {
    }

    /** 是否为绑定卡（按注册名判定） */
    public static boolean isBindCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:storage_bind_card".equals(key.toString());
    }

    /** 是否为解绑卡 */
    public static boolean isUnbindCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:storage_unbind_card".equals(key.toString());
    }

    /** 是否精妙储存控制器方块（注册名 sophisticatedstorage:controller） */
    public static boolean isControllerBlock(ServerLevel level, BlockPos pos) {
        try {
            Block b = level.getBlockState(pos).getBlock();
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            return key != null && "sophisticatedstorage:controller".equals(key.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SubscribeEvent
    public void onInteractBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        try {
            if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            InteractionHand hand = event.getHand();
            ItemStack stack = player.getItemInHand(hand);
            if (!isBindCard(stack)) {
                return;
            }
            // 绑定卡右击控制器 → 完成绑定
            if (!isControllerBlock((ServerLevel) player.level(), event.getPos())) {
                return;
            }
            String tmpUuid = ((net.neoforged.neoforge.common.extensions.IEntityExtension) player).getPersistentData().getString(BIND_MODE_TMP);
            if (tmpUuid == null || tmpUuid.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[精妙终端]\u00a7f 请先用绑定卡右击你想要绑定的女仆"));
                return;
            }
            // 找到女仆（可能在其他维度——跨维度查找）
            EntityMaid target = null;
            for (ServerLevel lvl : player.level().getServer().getAllLevels()) {
                net.minecraft.world.entity.Entity e = lvl.getEntity(java.util.UUID.fromString(tmpUuid));
                if (e instanceof EntityMaid m && m.isAlive() && m.isOwnedBy(player)) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[精妙终端]\u00a7f 选定的女仆不在线或不在已加载区块，请重新选定"));
                return;
            }
            // 绑定写入【玩家当前维度】的表（= 终端所在维度）——女仆取物时按
            // 自己所在维度查表 + 解析坐标，跨维度取物本就不支持（终端不在该维度）
            StorageBindingStore.get((ServerLevel) player.level())
                    .bind(StorageBindingStore.maidKey(target.getUUID()),
                            StorageBindingStore.posToArr(event.getPos()));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7e[精妙终端]\u00a7f 女仆 " + com.maidsmart.tool.PromaidLog.nameOf(target)
                            + " 已绑定精妙储存终端 @" + event.getPos().getX() + ","
                            + event.getPos().getY() + "," + event.getPos().getZ()
                            + "（可同时绑定多只女仆到同一终端）"));
            event.setCanceled(true);
            player.swing(hand);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 女仆取物工具（建造/酿药调用） ====================

    /**
     * 取女仆绑定终端的可操作背包（第一个存活的控制器方块实体）。
     * 控制器方块实体实现了 Forge IItemHandler——可直接当背包抽物品。
     * 终端被拆除/区块未加载 → 返回 null（调用方回退原逻辑）。
     * v1.1.0 实测三百一十六：取消 32 格距离限制（超大型建筑时女仆离终端
     * 可能很远，取物不应失效；同维度内任意距离可取）。
     */
    public static IItemHandler boundHandlerOf(ServerLevel level, EntityMaid maid) {
        try {
            List<long[]> terms = StorageBindingStore.get(level)
                    .terminalsOf(StorageBindingStore.maidKey(maid.getUUID()));
            for (long[] arr : terms) {
                BlockPos pos = StorageBindingStore.arrToPos(arr);
                if (!level.isLoaded(pos) || !isControllerBlock(level, pos)) {
                    continue; // 区块未加载/终端没了 → 跳过
                }
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof IItemHandler handler) {
                    return handler;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从绑定终端提取 1 个指定物品（主手/背包/终端 顺序由调用方控制）——成功返回
     *  取出的物品，没有返回空。 */
    public static ItemStack extractFromBoundStorage(ServerLevel level, EntityMaid maid,
                                                    net.minecraft.world.item.Item item) {
        try {
            IItemHandler handler = boundHandlerOf(level, maid);
            if (handler == null) {
                return ItemStack.EMPTY;
            }
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack s = handler.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() == item) {
                    ItemStack taken = handler.extractItem(i, 1, false);
                    if (!taken.isEmpty()) {
                        return taken;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        try {
            if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                    || !(event.getTarget() instanceof EntityMaid maid)
                    || !maid.isOwnedBy(player)) {
                return;
            }
            InteractionHand hand = event.getHand();
            ItemStack stack = player.getItemInHand(hand);
            if (isBindCard(stack)) {
                event.setCanceled(true);
                // 绑定卡右击女仆 = 进入"绑定模式"（暂存目标女仆 UUID 到玩家
                // persistentData——pendingData 在切换维度时会丢）
                ((net.neoforged.neoforge.common.extensions.IEntityExtension) player).getPersistentData().putString(BIND_MODE_TMP, maid.getUUID().toString());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[精妙终端]\u00a7f 已选定女仆 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + "，现在右击精妙储存控制器完成绑定"));
                return;
            }
            if (isUnbindCard(stack)) {
                event.setCanceled(true);
                String key = StorageBindingStore.maidKey(maid.getUUID());
                StorageBindingStore.get((ServerLevel) player.level()).unbind(key);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[精妙终端]\u00a7f 已解除女仆 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 与终端的绑定"));
                return;
            }
        } catch (Throwable ignored) {
        }
    }
}
