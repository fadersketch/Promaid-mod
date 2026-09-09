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

import java.util.List;

/**
 * v1.1.0 实测三百零九：超越维度（Beyond Dimensions）网络接口绑定/解绑交互 + 取物。
 *
 * 新物品（配方自定义）：
 * - beyond_bind_card（维度终端绑定卡，9 末影珍珠合成）：右击自己的女仆选定目标，
 *   再右击【超越维度网络接口】（beyonddimensions:net_interface）完成绑定——
 *   网络接口代理了它所连接存储网络的物品能力，女仆可直接从中取物。卡不消耗。
 * - beyond_unbind_card（维度终端解绑卡，9 黏液球合成）：右击女仆清空绑定。卡不消耗。
 *
 * 取物能力：NetInterfaceBlockEntity 实现 Forge capability 代理——
 * getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, null) 返回网络对面容器的
 * IItemHandler，经标准 capability 接口引用读取，零编译期依赖。
 *
 * 与精妙储存绑定独立存储（BeyondBindingStore），各自解绑卡只解各自的表。
 */
public final class BeyondBindingInteractHandler {
    /** 玩家 persistentData 暂存目标女仆 UUID（独立 key，防与精妙绑定卡串用） */
    public static final String BIND_MODE_TMP = "maid_smart_beyond_bind_target";

    public BeyondBindingInteractHandler() {
    }

    /** 是否为维度绑定卡（按注册名判定） */
    public static boolean isBindCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:beyond_bind_card".equals(key.toString());
    }

    /** 是否为维度解绑卡 */
    public static boolean isUnbindCard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:beyond_unbind_card".equals(key.toString());
    }

    /** 是否超越维度网络接口方块（注册名 beyonddimensions:net_interface） */
    public static boolean isNetInterfaceBlock(ServerLevel level, BlockPos pos) {
        try {
            Block b = level.getBlockState(pos).getBlock();
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            return key != null && "beyonddimensions:net_interface".equals(key.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** v1.1.0 实测三百二十一（用户："精妙储存是有方块的，但是超越维度没有"）：
     *  是否超越维度【网络终端】方块（注册名 beyonddimensions:net_terminal_block）——
     *  用户右击的是终端不是接口，旧版只认 net_interface → 绑定卡右击无反应。
     *  终端本身不暴露 ITEM_HANDLER（javap 实证只实现 MenuProvider），取物走
     *  网络 UnifiedStorage（见 boundHandlerOf 的终端分支）。 */
    public static boolean isNetTerminalBlock(ServerLevel level, BlockPos pos) {
        try {
            Block b = level.getBlockState(pos).getBlock();
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            return key != null && "beyonddimensions:net_terminal_block".equals(key.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否超越维度可绑定方块（网络接口 或 网络终端） */
    public static boolean isBindableBlock(ServerLevel level, BlockPos pos) {
        return isNetInterfaceBlock(level, pos) || isNetTerminalBlock(level, pos);
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
            if (!isBindableBlock((ServerLevel) player.level(), event.getPos())) {
                return;
            }
            String tmpUuid = ((net.neoforged.neoforge.common.extensions.IEntityExtension) player).getPersistentData().getString(BIND_MODE_TMP);
            if (tmpUuid == null || tmpUuid.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[维度终端]\u00a7f 请先用维度终端绑定卡右击你想要绑定的女仆"));
                return;
            }
            EntityMaid target = null;
            try {
                java.util.UUID uid = java.util.UUID.fromString(tmpUuid);
                for (ServerLevel lvl : player.level().getServer().getAllLevels()) {
                    net.minecraft.world.entity.Entity e = lvl.getEntity(uid);
                    if (e instanceof EntityMaid m && m.isAlive() && m.isOwnedBy(player)) {
                        target = m;
                        break;
                    }
                }
            } catch (IllegalArgumentException ignoreInvalid) {
            }
            if (target == null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[维度终端]\u00a7f 选定的女仆不在线或不在已加载区块，请重新选定"));
                return;
            }
            // 绑定写入【玩家当前维度】的表（= 网络接口所在维度）——女仆取物时按
            // 自己所在维度查表 + 解析坐标，跨维度取物本就不支持（接口不在该维度）
            BeyondBindingStore.get((ServerLevel) player.level())
                    .bind(BeyondBindingStore.maidKey(target.getUUID()),
                            BeyondBindingStore.posToArr(event.getPos()));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a7e[维度终端]\u00a7f 女仆 " + com.maidsmart.tool.PromaidLog.nameOf(target)
                            + " 已绑定网络接口 @" + event.getPos().getX() + ","
                            + event.getPos().getY() + "," + event.getPos().getZ()));
            event.setCanceled(true);
            player.swing(hand);
        } catch (Throwable ignored) {
        }
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
                ((net.neoforged.neoforge.common.extensions.IEntityExtension) player).getPersistentData().putString(BIND_MODE_TMP, maid.getUUID().toString());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[维度终端]\u00a7f 已选定女仆 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + "，现在右击超越维度网络接口完成绑定"));
                return;
            }
            if (isUnbindCard(stack)) {
                event.setCanceled(true);
                BeyondBindingStore.get((ServerLevel) player.level())
                        .unbind(BeyondBindingStore.maidKey(maid.getUUID()));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e[维度终端]\u00a7f 已解除女仆 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 与网络接口的绑定"));
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 女仆取物工具（建造/酿药调用） ====================

    /**
     * 取女仆绑定的超越维度方块的可操作背包（第一个存活的）。
     * - 网络接口：getCapability(ITEM_HANDLER) 暴露接口本地缓冲（StackHandler）——
     *   取物前先反射调用 transferFromNet() 把网络物品拉进缓冲（v1.1.0 实测
     *   三百一十七修正：缓冲默认空，直接取永远取不到）。
     * - 网络终端（v1.1.0 实测三百二十一）：不暴露 ITEM_HANDLER（javap 实证只
     *   实现 MenuProvider）——走网络 UnifiedStorage 反射适配器（UnifiedStorage
     *   ItemHandler，getNet → getUnifiedStorage → getStorage/extract）。
     * capability 为空（容器离线/未连网络）时 resolve 空 → null。
     */
    public static IItemHandler boundHandlerOf(ServerLevel level, EntityMaid maid) {
        try {
            List<long[]> ifaces = BeyondBindingStore.get(level)
                    .interfacesOf(BeyondBindingStore.maidKey(maid.getUUID()));
            for (long[] arr : ifaces) {
                BlockPos pos = BeyondBindingStore.arrToPos(arr);
                if (!level.isLoaded(pos)) {
                    continue; // 区块未加载 → 跳过
                }
                if (isNetInterfaceBlock(level, pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) {
                        continue;
                    }
                    // v1.1.0 实测三百一十七：先拉取网络物品到接口缓冲（反射调用
                    // transferFromNet——public 无参方法，mod 不混淆；失败静默）
                    try {
                        java.lang.reflect.Method m = be.getClass().getMethod("transferFromNet");
                        m.invoke(be);
                    } catch (Throwable ignored) {
                    }
                    IItemHandler h = net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK.getCapability(
                            level, pos, level.getBlockState(pos), be, null);
                    if (h != null) {
                        return h;
                    }
                } else if (isNetTerminalBlock(level, pos)) {
                    // v1.1.0 实测三百二十一：网络终端——走 UnifiedStorage 适配器
                    //（getNet → getUnifiedStorage，反射链路见 UnifiedStorageItemHandler）
                    if (!UnifiedStorageItemHandler.isUsable()) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) {
                        continue;
                    }
                    try {
                        java.lang.reflect.Method getNet = be.getClass().getMethod("getNet");
                        Object net = getNet.invoke(be);
                        if (net == null) {
                            continue; // 终端未连网络
                        }
                        java.lang.reflect.Method getUs = net.getClass().getMethod("getUnifiedStorage");
                        Object us = getUs.invoke(net);
                        if (us == null) {
                            continue;
                        }
                        return new UnifiedStorageItemHandler(us);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从绑定网络接口提取 1 个指定物品——成功返回取出的物品，没有返回空。 */
    public static ItemStack extractFromBoundInterface(ServerLevel level, EntityMaid maid,
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
}
