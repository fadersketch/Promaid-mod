package com.maidsmart.box;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

import java.util.List;

/**
 * 压缩盒的取放服务（v1.2.2 实测六百一十六）——界面上每一次点击真正干活的地方。
 *
 * 玩家打开界面时手上那件压缩盒就是「打开的这一个」：每次操作都**重新确认那只手
 * 里还是压缩盒**（{@link #handle}），换了东西/丢掉了就直接拒绝——不需要追踪
 * 会话，也不存在「界面开着、物品已经不在」的错位。
 *
 * 为什么是「服务端说了算」：这个界面**不是原版容器界面**（没有 Menu、没有光标上
 * 那一叠），客户端只发「我想从第几格拿 / 想存入背包第几格」，搬多少、搬到哪、
 * 剩不剩，全在这里算。客户端伪造包最多只能让自己多拿几次，拿不出合同以外的
 * 东西（每次操作都要真实存在的东西才动，且最多 64 个/次）。
 *
 * 五条动作（与 {@link CompressionBoxNetworking} 的常量一一对应）：
 * <ul>
 *   <li>{@link #TAKE_ONE} / {@link #TAKE_STACK}：从盒子某格取 1 / 64 个 → 进玩家背包；</li>
 *   <li>{@link #TAKE_ALL}：反复取 64 直到盒子那格空**或背包满**（装不进的那份原样放回，不丢）；</li>
 *   <li>{@link #DEPOSIT_STACK} / {@link #DEPOSIT_ONE}：把玩家背包某一格的一整叠 / 1 个存进盒子。</li>
 * </ul>
 * 背包满时多出来的部分**掉在玩家脚边**（与原版拿取习惯一致），提示写在界面里。
 */
public final class CompressionBoxService {

    public static final int TAKE_ONE = 0;
    public static final int TAKE_STACK = 1;
    public static final int TAKE_ALL = 2;
    public static final int DEPOSIT_STACK = 3;
    public static final int DEPOSIT_ONE = 4;

    private CompressionBoxService() {
    }

    /** 手序号（网络里传的是 ordinal）→ 交互手 */
    public static InteractionHand handOf(int ordinal) {
        return ordinal == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /**
     * 服务端处理一次点击；返回 true = 内容有变（调用方据此回包刷新界面）。
     * 那只手里已经不是压缩盒了（换手/丢出去/被拿走）→ 一律 false，什么都不动。
     */
    public static boolean handle(ServerPlayer player, int handOrdinal, int action, int index) {
        try {
            ItemStack box = player.getItemInHand(handOf(handOrdinal));
            if (!CompressionBoxData.isBox(box)) {
                return false;
            }
            List<ItemStack> items = CompressionBoxData.read(box);
            IItemHandler inv = new PlayerMainInvWrapper(player.getInventory());
            boolean changed;
            if (action <= TAKE_ALL) {
                changed = take(player, inv, items, index, action);
            } else {
                changed = deposit(inv, items, index, action == DEPOSIT_ONE ? 1 : Integer.MAX_VALUE);
            }
            if (changed) {
                // 写回手上那件物品（就是同一个 ItemStack 对象；改完由原版的背包同步
                // 把它推给客户端，所以界面之外的物品描述也会跟着更新）
                CompressionBoxData.write(box, items);
            }
            return changed;
        } catch (Throwable ignored) {
            // 界面动作出错不该把服务端拖下水：什么都不改，下次点击重来
            return false;
        }
    }

    /** 从第 slot 格往外拿 */
    private static boolean take(ServerPlayer player, IItemHandler inv, List<ItemStack> items,
                                int slot, int action) {
        int want = action == TAKE_ONE ? 1 : CompressionBoxData.TAKE_PER_CLICK;
        if (action == TAKE_ALL) {
            boolean any = false;
            while (true) {
                ItemStack out = CompressionBoxData.take(items, slot, CompressionBoxData.TAKE_PER_CLICK);
                if (out.isEmpty()) {
                    break;
                }
                any = true;
                if (!give(player, inv, items, out)) {
                    break; // 背包满了：剩下的留在盒子里，本轮收手
                }
            }
            return any;
        }
        ItemStack out = CompressionBoxData.take(items, slot, want);
        if (out.isEmpty()) {
            return false;
        }
        give(player, inv, items, out);
        return true;
    }

    /**
     * 给玩家一堆东西：先进背包，装不下的**放回盒子**，盒子也放不回去才掉在脚边。
     * 返回 false = 一件都没进背包（调用方据此停下「整格取」的循环）。
     */
    private static boolean give(ServerPlayer player, IItemHandler inv, List<ItemStack> items,
                                ItemStack out) {
        ItemStack left = ItemHandlerHelper.insertItemStacked(inv, out, false);
        if (left.isEmpty()) {
            return true;
        }
        ItemStack back = CompressionBoxData.merge(items, left);
        if (!back.isEmpty()) {
            player.drop(back, false);
        }
        return left.getCount() < out.getCount();
    }

    /** 把玩家背包第 slot 格存进盒子 */
    private static boolean deposit(IItemHandler inv, List<ItemStack> items, int slot, int amount) {
        if (slot < 0 || slot >= inv.getSlots()) {
            return false;
        }
        ItemStack from = inv.getStackInSlot(slot);
        if (from.isEmpty()) {
            return false;
        }
        int take = Math.min(amount, from.getCount());
        ItemStack moved = inv.extractItem(slot, take, false);
        if (moved.isEmpty()) {
            return false;
        }
        ItemStack left = CompressionBoxData.merge(items, moved);
        if (!left.isEmpty()) {
            // 盒子满了：原样塞回背包（塞不回去就掉在脚边——不凭空销毁）
            left = ItemHandlerHelper.insertItemStacked(inv, left, false);
            if (!left.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
