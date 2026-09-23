package com.maidsmart.box;

import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩盒的存档格式与内容搬运（v1.2.2 实测六百一十六）。
 *
 * ── 为什么不用原版那一套 ──
 * 用户要的是「一格能堆 114514 个」，而原版 ItemStack 的**序列化**装不下这个数：
 * 1.20.1 侧 javap 实证 {@code FriendlyByteBuf.writeItem} 把数量写成
 * {@code writeByte(count)}、NBT 里的 {@code Count} 也是 byte（超过 127 就截断，
 * 114514 会变成 82）；1.21.1 侧虽然网络上放开了，但 {@code ItemStack.CODEC} 的
 * count 字段照样有自己的取值范围。所以盒子自己存一份整数数量的表
 * （{@link #TAG_COUNT} 是 int），**大堆只在盒子的内存态与这套自定义 NBT 里存在**，
 * 任何一次交给原版（手上的堆、女仆的背包格、网络的 ItemStack 编解码）之前都会
 * 被压回 64：
 * <ul>
 *   <li>界面里玩家一次最多拿走 64（{@link #TAKE_PER_CLICK}）；</li>
 *   <li>女仆那一侧更严——{@link #MAID_VIEW_CAP}：她对盒子格子的**视野**永远只有
 *       64 个，所以「把整格拿走」这种代码最多也只拿到 64，不会把 114514 个塞进
 *       她的手/背包格（那会在她存档时被截断，等于凭空蒸发）；</li>
 *   <li>要塞进盒子则相反：向盒子写入的堆会**并**进已有的那一格，一格最多
 *       {@link #maxStack()} 个。</li>
 * </ul>
 *
 * ── 存档格式 ──
 * 物品标签 {@code CompressionBox} 下的 {@code Items} 列表：
 * <pre>
 * Items: [ { Slot: 0, Count: 114514, Item: {id: "minecraft:cobblestone", count: 1} } ]
 * </pre>
 * {@code Item} 里固定是 **1 个**的物品（用 {@code ItemStack.SINGLE_ITEM_CODEC} 编，
 * 保留附魔/耐久/自定义名等全部组件，但数量恒为 1），真正的数量在 {@code Count}
 * ——这样原版那套数量口径就碰不到它，同时手写命令
 * {@code /give ... [custom_data={CompressionBox:{Items:[{Slot:0,Count:114514,Item:{id:"..."}}]}}]}
 * 也能直接喂进来。
 *
 * 【1.21.1 与 1.20.1 的唯一差异】物品本体的编解码：这里用
 * {@code ItemStack.SINGLE_ITEM_CODEC} + NbtOps；1.20.1 侧是
 * {@code ItemStack.copyWithCount(1).save(tag)} / {@code ItemStack.of(tag)}。
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

    /**
     * 这一个堆最多能「看见」多少（v1.2.2 实测六百一十八）。
     *
     * ── 为什么不能一律 64 ──
     * 盒子一格能堆 114514 个，而**不可堆叠的物品**（附魔书、附魔工具/武器/盔甲、药水、
     * 船……原版 {@code getMaxStackSize()} 就是 1）一格也能存好几个：两个同名同附魔的
     * 附魔书存进同一格，那一格的堆就是 {@code ×2}。六百一十六起这条路的视野封顶只按
     * 64 算，于是**把一个「2 个的附魔书」这种非法堆交给了原版**——原版任何一处
     * 「插进背包/手里」的代码都会按 {@code getMaxStackSize()=1} 只收下 1 个并把剩下的
     * **作为返回值退回**，而 TLM 那几十处调用点大多不看返回值（它们的入参在正常世界里
     * 永远不可能装不下）——那 1 个就这样凭空消失。用户报的「附魔类物品存进去会消失」
     * 与这条完全吻合（普通物品的视野本来就是 64 = 合法堆，所以只有附魔这一类出问题）。
     *
     * 所以口径改成 {@code min(64, 物品自己的堆叠上限)}：**交给原版的每一个堆都必须是
     * 合法堆**，大数量只活在盒子的数据组件与界面里的那行数字上。
     */
    public static int viewCap(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(1, Math.min(MAID_VIEW_CAP, stack.getMaxStackSize()));
        } catch (Throwable ignored) {
            return MAID_VIEW_CAP;
        }
    }

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
            list.add(ItemStack.EMPTY);
        }
        return list;
    }

    /** 是不是压缩盒（子类都算） */
    public static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CompressionBoxItem;
    }

    /* ==================== 物品本体（1 个）的编解码 ==================== */

    private static CompoundTag saveOne(ItemStack stack) {
        try {
            return (CompoundTag) ItemStack.SINGLE_ITEM_CODEC
                    .encodeStart(NbtOps.INSTANCE, stack.copyWithCount(1))
                    .getOrThrow();
        } catch (Throwable ignored) {
            return new CompoundTag();
        }
    }

    private static ItemStack loadOne(CompoundTag tag) {
        try {
            return ItemStack.SINGLE_ITEM_CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(ItemStack.EMPTY);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /* ==================== 内容 ↔ NBT ==================== */

    /** 把 5 格写成 Items 列表（空格子不写；数量写进 int 的 Count） */
    public static ListTag toTag(List<ItemStack> items) {
        ListTag list = new ListTag();
        for (int i = 0; i < items.size() && i < SLOTS; i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putInt(TAG_SLOT, i);
            e.putInt(TAG_COUNT, s.getCount());
            e.put(TAG_ITEM, saveOne(s));
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
            CompoundTag e = list.getCompound(i);
            int slot = e.getInt(TAG_SLOT);
            if (slot < 0 || slot >= SLOTS) {
                continue;
            }
            ItemStack item = loadOne(e.getCompound(TAG_ITEM));
            if (item.isEmpty()) {
                continue;
            }
            // Count 缺失（或手写成 0）时退回 Item 自带的数量——老命令/手写 NBT 也能读
            int count = e.getInt(TAG_COUNT);
            if (count <= 0) {
                count = item.getCount();
            }
            item.setCount(Math.min(count, cap));
            out.set(slot, item);
        }
        return out;
    }

    /* ==================== 物品形态（CompressionBox 子标签） ==================== */

    /** 读物品形态的内容（不是压缩盒 / 没标签 → 空盒） */
    public static List<ItemStack> read(ItemStack box) {
        List<ItemStack> out = empty();
        if (!isBox(box)) {
            return out;
        }
        // 1.21.1：自定义 NBT 一律走数据组件（与 MaidSoulSpellGuard / MaidToolAutoEquip 同款）
        CompoundTag tag = box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(TAG_ROOT, 10)) {
            return out;
        }
        return fromTag(tag.getCompound(TAG_ROOT).getList(TAG_ITEMS, 10));
    }

    /** 写物品形态的内容（全空时把这一段摘掉，其余自定义数据保留） */
    public static void write(ItemStack box, List<ItemStack> items) {
        if (!isBox(box)) {
            return;
        }
        ListTag list = toTag(items);
        CompoundTag tag = box.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (list.isEmpty()) {
            tag.remove(TAG_ROOT);
        } else {
            CompoundTag root = tag.getCompound(TAG_ROOT);
            root.put(TAG_ITEMS, list);
            tag.put(TAG_ROOT, root);
        }
        if (tag.isEmpty()) {
            box.remove(DataComponents.CUSTOM_DATA);
        } else {
            box.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    /* ==================== 内容搬运 ==================== */

    /**
     * 深拷一份内容（v1.2.2 实测六百一十八）。
     *
     * 【为什么必须有这一步】六百一十八把界面改成了箱子式鼠标取放，一次点击可能**同时**
     * 改「盒子里那一格」和「玩家背包那一格」。这两处只要有一处先动了、另一处抛异常，
     * 东西就没了（用户报的「存进去会消失」正是在这类路径上最疼）。所以现在的口径是：
     * **先在副本上把整件事算完，再动真东西**——副本里改的是 {@code copy()} 出来的堆，
     * 原列表一个引用都不碰（不深拷就会在 {@code grow}/{@code set} 时改到真列表里的对象）。
     */
    public static List<ItemStack> duplicate(List<ItemStack> items) {
        List<ItemStack> out = empty();
        for (int i = 0; i < out.size() && i < items.size(); i++) {
            ItemStack s = items.get(i);
            out.set(i, s.isEmpty() ? ItemStack.EMPTY : s.copy());
        }
        return out;
    }

    /** 一共装了多少件（物品描述用；long 防溢出） */
    public static long totalCount(List<ItemStack> items) {
        long n = 0L;
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 用了几格 */
    public static int usedSlots(List<ItemStack> items) {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 把 {@code in} 尽量并进盒子的**指定那一格**（并到上限为止），返回没塞进去的部分。
     * 只动这一格——女仆背包侧的 insertItem(盒格子) 就是它。
     *
     * <b>压缩盒不许装压缩盒</b>（v1.2.2 实测六百一十七修的那个 bug）：把盒子塞进盒子，
     * 玩家既看不见里面那一层（女仆那一侧的视野也是只读外层），又能把自己装进去——
     * 界面里 Shift+点自己那一格，服务端会先把它从背包取出来再写标签，于是**整个盒子
     * （连里面的东西）**就这样从世界里消失了。拒绝点放在这里（所有入口都走这个方法：
     * 玩家界面存入、女仆背包插入、溢出回退），比在每个调用点各写一遍可靠。
     *
     * <b>六百二十起这一道交给 {@link CompressionBoxFilter}</b>：压缩盒本身与带附魔的
     * 物品（附魔书/附魔武器等）都在这里被原样退回（v1.2.4 实测六百四十五 起这两条写死，
     * 不再有配置开关与自定义清单）。判据还是只有一处——全库任何一条「往盒子里放东西」
     * 的路都得先过它。
     */
    public static ItemStack mergeInto(List<ItemStack> items, int slot, ItemStack in) {
        if (slot < 0 || slot >= items.size() || in.isEmpty()
                || !CompressionBoxFilter.canStore(in)) {
            return in;
        }
        ItemStack cur = items.get(slot);
        if (cur.isEmpty()) {
            int put = Math.min(in.getCount(), maxStack());
            items.set(slot, in.copyWithCount(put));
            return in.getCount() > put ? in.copyWithCount(in.getCount() - put) : ItemStack.EMPTY;
        }
        if (!ItemStack.isSameItemSameComponents(cur, in)) {
            return in; // 不同物品：这一格塞不进（调用方自己找别的格子）
        }
        int room = maxStack() - cur.getCount();
        if (room <= 0) {
            return in;
        }
        int put = Math.min(room, in.getCount());
        cur.grow(put);
        return in.getCount() > put ? in.copyWithCount(in.getCount() - put) : ItemStack.EMPTY;
    }

    /** 把 {@code in} 塞进盒子（先并同名、再占空格），返回没塞进去的部分（压缩盒一律原样退回） */
    public static ItemStack merge(List<ItemStack> items, ItemStack in) {
        ItemStack left = in;
        for (int i = 0; i < items.size() && !left.isEmpty(); i++) {
            ItemStack cur = items.get(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, left)
                    && cur.getCount() < maxStack()) {
                left = mergeInto(items, i, left);
            }
        }
        for (int i = 0; i < items.size() && !left.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                left = mergeInto(items, i, left);
            }
        }
        return left;
    }

    /** 从第 slot 格取最多 amount 件（真取；返回取到的堆，空 = 没取到） */
    public static ItemStack take(List<ItemStack> items, int slot, int amount) {
        if (slot < 0 || slot >= items.size() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack cur = items.get(slot);
        if (cur.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int take = Math.min(amount, cur.getCount());
        ItemStack out = cur.split(take); // split：本格减 take，返回那一份
        if (cur.isEmpty()) {
            items.set(slot, ItemStack.EMPTY);
        }
        return out;
    }

}
