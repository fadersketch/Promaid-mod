package com.maidsmart.box;

import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩盒的存档格式与内容搬运（v1.2.2 实测六百一十六）。
 *
 * ── 为什么不用原版那一套 ──
 * 用户要的是「一格能堆 114514 个」，而 1.20.1 的原版 ItemStack 在**序列化**上根本
 * 装不下这个数：javap 实证 {@code FriendlyByteBuf.writeItem} 把数量写成
 * {@code writeByte(count)}、NBT 里的 {@code Count} 也是 byte——超过 127 就被截断
 * （114514 会变成 82）。所以盒子自己存一份整数数量的表（{@link #TAG_COUNT} 是
 * int），**大堆只在盒子的内存态与这套自定义 NBT 里存在**，任何一次交给原版
 * （手上的堆、女仆的背包格、网络的 ItemStack 编解码）之前都会被压回 64：
 * <ul>
 *   <li>界面里玩家一次最多拿走 64（{@link #TAKE_PER_CLICK}）；</li>
 *   <li>女仆那一侧更严——{@link #MAID_VIEW_CAP}：她对盒子格子的**视野**永远只有
 *       64 个，所以「把整格拿走」这种代码最多也只拿到 64，不会把 114514 个塞进
 *       她的手/背包格（那会在她存档时被 byte 截断，等于凭空蒸发）；</li>
 *   <li>要塞进盒子则相反：向盒子写入的堆会**并**进已有的那一格，一格最多
 *       {@link #maxStack()} 个。</li>
 * </ul>
 *
 * ── 存档格式 ──
 * 物品标签 {@code CompressionBox} 下的 {@code Items} 列表：
 * <pre>
 * Items: [ { Slot: 0, Count: 114514, Item: {id: "minecraft:cobblestone", Count: 1} } ]
 * </pre>
 * {@code Item} 里固定是 **1 个**的原版 ItemStack（保留附魔/耐久/自定义名等全部信息，
 * 但数量恒为 1），真正的数量在 {@code Count}——这样原版的 byte 截断就碰不到它，
 * 同时 {@code /give ... {tag:{CompressionBox:{Items:[{Slot:0,Count:114514,Item:{...}}]}}}}
 * 这种手写命令也能直接喂进来（实测用例就是这么造数据的）。
 */
public final class CompressionBoxData {

    /** 格子数（用户口径：堆叠上限很高，但只有 5 格） */
    public static final int SLOTS = 5;
    /** 默认每格上限（用户指定的 114514） */
    public static final int DEFAULT_MAX_STACK = 114514;
    /** 女仆从盒子里「看见」的上限——原版堆叠口径，防止大堆漏进她的存档 */
    public static final int MAID_VIEW_CAP = 64;
    /** 界面上一次点击最多拿走多少（原版堆叠口径） */
    public static final int TAKE_PER_CLICK = 64;

    static final String TAG_ROOT = "CompressionBox";
    static final String TAG_ITEMS = "Items";
    static final String TAG_SLOT = "Slot";
    static final String TAG_COUNT = "Count";
    static final String TAG_ITEM = "Item";

    private CompressionBoxData() {
    }

    /** 每格上限（配置可调；读不到配置时用用户给的那个数） */
    public static int maxStack() {
        try {
            return Math.max(1, MaidSmartConfig.COMPRESSION_BOX_MAX_STACK.get());
        } catch (Throwable ignored) {
            return DEFAULT_MAX_STACK;
        }
    }

    /** 一个空盒子的 5 格 */
    public static List<ItemStack> empty() {
        List<ItemStack> list = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            list.add(ItemStack.f_41583_);
        }
        return list;
    }

    /** 是不是压缩盒（子类都算） */
    public static boolean isBox(ItemStack stack) {
        return !stack.m_41619_() && stack.m_41720_() instanceof CompressionBoxItem;
    }

    /* ==================== 内容 ↔ NBT ==================== */

    /** 把 5 格写成 Items 列表（空格子不写；数量写进 int 的 Count） */
    public static ListTag toTag(List<ItemStack> items) {
        ListTag list = new ListTag();
        for (int i = 0; i < items.size() && i < SLOTS; i++) {
            ItemStack s = items.get(i);
            if (s.m_41619_()) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.m_128405_(TAG_SLOT, i);
            e.m_128405_(TAG_COUNT, s.m_41613_());
            e.m_128365_(TAG_ITEM, s.m_255036_(1).m_41739_(new CompoundTag()));
            list.add(e);
        }
        return list;
    }

    /** 读 Items 列表——「Item 里只有 1 个、数量在 Count」这一条格式的回读（越界/坏数据一律丢） */
    public static List<ItemStack> fromTag(ListTag list) {
        List<ItemStack> out = empty();
        if (list == null) {
            return out;
        }
        int cap = maxStack();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.m_128728_(i);
            int slot = e.m_128451_(TAG_SLOT);
            if (slot < 0 || slot >= SLOTS) {
                continue;
            }
            ItemStack item = ItemStack.m_41712_(e.m_128469_(TAG_ITEM));
            if (item.m_41619_()) {
                continue;
            }
            // Count 缺失（或手写成 0）时退回 Item 自带的数量——老命令/手写 NBT 也能读
            int count = e.m_128451_(TAG_COUNT);
            if (count <= 0) {
                count = item.m_41613_();
            }
            item.m_41769_(Math.min(count, cap));
            out.set(slot, item);
        }
        return out;
    }

    /* ==================== 物品标签（CompressionBox 子标签） ==================== */

    /** 读物品形态的内容（不是压缩盒 / 没标签 → 空盒） */
    public static List<ItemStack> read(ItemStack box) {
        List<ItemStack> out = empty();
        if (!isBox(box)) {
            return out;
        }
        CompoundTag tag = box.m_41783_();
        if (tag == null || !tag.m_128425_(TAG_ROOT, 10)) {
            return out;
        }
        return fromTag(tag.m_128469_(TAG_ROOT).m_128437_(TAG_ITEMS, 10));
    }

    /** 写物品形态的内容（全空时把标签整个摘掉，保持 NBT 干净） */
    public static void write(ItemStack box, List<ItemStack> items) {
        if (!isBox(box)) {
            return;
        }
        ListTag list = toTag(items);
        if (list.isEmpty()) {
            CompoundTag tag = box.m_41783_();
            if (tag != null) {
                tag.m_128473_(TAG_ROOT);
            }
            return;
        }
        box.m_41698_(TAG_ROOT).m_128365_(TAG_ITEMS, list);
    }

    /* ==================== 内容搬运 ==================== */

    /** 一共装了多少件（物品描述用；long 防溢出） */
    public static long totalCount(List<ItemStack> items) {
        long n = 0L;
        for (ItemStack s : items) {
            if (!s.m_41619_()) {
                n += s.m_41613_();
            }
        }
        return n;
    }

    /** 用了几格 */
    public static int usedSlots(List<ItemStack> items) {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.m_41619_()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 把 {@code in} 尽量并进盒子的**指定那一格**（并到上限为止），返回没塞进去的部分。
     * 只动这一格——女仆背包侧的 insertItem(盒格子) 就是它。
     */
    public static ItemStack mergeInto(List<ItemStack> items, int slot, ItemStack in) {
        if (slot < 0 || slot >= items.size() || in.m_41619_()) {
            return in;
        }
        ItemStack cur = items.get(slot);
        if (cur.m_41619_()) {
            int put = Math.min(in.m_41613_(), maxStack());
            ItemStack copy = in.m_255036_(put);
            items.set(slot, copy);
            return in.m_41613_() > put ? in.m_255036_(in.m_41613_() - put) : ItemStack.f_41583_;
        }
        if (!ItemStack.m_150942_(cur, in)) {
            return in; // 不同物品：这一格塞不进（调用方自己找别的格子）
        }
        int room = maxStack() - cur.m_41613_();
        if (room <= 0) {
            return in;
        }
        int put = Math.min(room, in.m_41613_());
        cur.m_41774_(put);
        return in.m_41613_() > put ? in.m_255036_(in.m_41613_() - put) : ItemStack.f_41583_;
    }

    /** 把 {@code in} 塞进盒子（先并同名、再占空格），返回没塞进去的部分 */
    public static ItemStack merge(List<ItemStack> items, ItemStack in) {
        ItemStack left = in;
        for (int i = 0; i < items.size() && !left.m_41619_(); i++) {
            ItemStack cur = items.get(i);
            if (!cur.m_41619_() && ItemStack.m_150942_(cur, left) && cur.m_41613_() < maxStack()) {
                left = mergeInto(items, i, left);
            }
        }
        for (int i = 0; i < items.size() && !left.m_41619_(); i++) {
            if (items.get(i).m_41619_()) {
                left = mergeInto(items, i, left);
            }
        }
        return left;
    }

    /** 从第 slot 格取最多 amount 件（真取；返回取到的堆，空 = 没取到） */
    public static ItemStack take(List<ItemStack> items, int slot, int amount) {
        if (slot < 0 || slot >= items.size() || amount <= 0) {
            return ItemStack.f_41583_;
        }
        ItemStack cur = items.get(slot);
        if (cur.m_41619_()) {
            return ItemStack.f_41583_;
        }
        int take = Math.min(amount, cur.m_41613_());
        ItemStack out = cur.m_41620_(take); // split：本格减 take，返回那份
        if (cur.m_41619_()) {
            items.set(slot, ItemStack.f_41583_);
        }
        return out;
    }

}
