package com.maidsmart.box;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 「盒子里的东西也是女仆背包的一部分」（v1.2.2 实测六百一十六）。
 *
 * 做法：把 {@code maid.getMaidInv()} 的返回值换成一个**子类**（本类），它在她的
 * 36 格之后**追加**背包里每个压缩盒的 5 格。换的方法在
 * {@code MaidCompressionBoxMixin}（往 TLM 的 {@code getMaidInv} 头部注入）。
 * 之所以挑这一个方法下手：javap 实证全 TLM 只有 4 个类引用它，而**本模组自己
 * 找东西/吃东西/数东西的代码几乎全部走它**——一处注入，几十处调用点全部自动
 * 看得见盒子里的东西，不用去改 40 个文件。
 *
 * ── 两条硬规矩（都是 1.20.1 原版的 byte 截断逼出来的，见 CompressionBoxData） ──
 * <ul>
 *   <li><b>取：视野封顶 64</b>。{@link #getStackInSlot} 对盒子格返回的是
 *       {@code min(真实数量, 64)} 的**副本**，{@link #extractItem} 也最多给 64。
 *       于是「把这一格整个拿走」的代码最多只拿到 64——不会把 114514 个塞进她的
 *       手/背包格（那一步会在她存档时被截断，等于蒸发）；</li>
 *   <li><b>存：并到真上限</b>。{@link #insertItem} 认的是**真实**余量，能把东西
 *       并进一格已有的 114514 个里，这才是「一格堆满」的用处。</li>
 * </ul>
 * 还有个副产物：她捡到的东西（{@code insertItemStacked} 那条路）也会优先并进
 * 盒子——「背包的延伸」就该是双向的。
 */
public final class CompressionBoxMaidInv extends ItemStackHandler {

    /** 女仆侧一格盒子最多能「看见」多少（原版堆叠口径） */
    private static final int VIEW_CAP = CompressionBoxData.MAID_VIEW_CAP;

    private final EntityMaid maid;
    private final ItemStackHandler backing;

    public CompressionBoxMaidInv(EntityMaid maid, ItemStackHandler backing) {
        this.maid = maid;
        this.backing = backing;
    }

    /** 背包里某个盒子：她在背包里的第几格 + 盒子里的第几格 */
    private record Cell(int maidSlot, int slotInBox) {
    }

    private int maidSlots() {
        return this.backing.getSlots();
    }

    /** 她自己的背包有多少格（不含后面追加的盒子格）——自检/诊断用来认出「从第几格起是盒子」 */
    public int baseSlots() {
        return maidSlots();
    }

    /** 背包里有几个压缩盒 */
    public int boxCount() {
        return boxSlots().size();
    }

    /** 背包里所有压缩盒（按她背包里的顺序；一个都没有就是空表） */
    private List<Integer> boxSlots() {
        List<Integer> out = new ArrayList<>(2);
        int n = maidSlots();
        for (int i = 0; i < n; i++) {
            if (CompressionBoxData.isBox(this.backing.getStackInSlot(i))) {
                out.add(i);
            }
        }
        return out;
    }

    /** 追加段（slot >= maidSlots()）的地址翻译；越界/盒子已经不在 → null */
    private Cell cellAt(int slot) {
        int n = maidSlots();
        int rel = slot - n;
        if (rel < 0) {
            return null;
        }
        List<Integer> boxes = boxSlots();
        int idx = rel / CompressionBoxData.SLOTS;
        if (idx < 0 || idx >= boxes.size()) {
            return null;
        }
        return new Cell(boxes.get(idx), rel % CompressionBoxData.SLOTS);
    }

    private List<ItemStack> readBox(Cell cell) {
        return CompressionBoxData.read(this.backing.getStackInSlot(cell.maidSlot));
    }

    /**
     * 只含「盒子那几格」的一段视图（v1.2.2 实测六百一十八）。
     *
     * 【为什么需要它】TLM 有几条路走的是 {@code getAvailableInv} /
     * {@code getAvailableBackpackInv}（它们读的是 {@code maidInv} **字段**，不是
     * {@code getMaidInv()}，六百一十六那批只注入了后者）——**女仆自己吃食物**
     * （{@code MaidHealSelfTask} 就是拿 {@code getAvailableBackpackInv()} 逐格找吃的）
     * 正好是其中一条。于是「盒子里放食物她不吃、放弹药/TNT 她认」（弹药是走
     * {@code getMaidInv()} 的我们自己的代码）——这就是用户报的那条。
     * 修法在 {@code MaidCompressionBoxMixin}：把那两个方法的返回值**再套一层**，
     * 把这一段接在后面；这里只负责把这 5×N 格切出来。
     */
    public net.minecraftforge.items.IItemHandlerModifiable boxRange() {
        int base = maidSlots();
        int n = CompressionBoxData.SLOTS * boxSlots().size();
        if (n <= 0) {
            return null; // 背包里没有盒子：不给调用方多套一层
        }
        return new net.minecraftforge.items.wrapper.RangedWrapper(this, base, base + n);
    }

    /** 把改过的内容写回她那格盒子（写回会触发背包的脏标记/同步） */
    private void writeBox(Cell cell, List<ItemStack> items) {
        ItemStack cur = this.backing.getStackInSlot(cell.maidSlot);
        if (!CompressionBoxData.isBox(cur)) {
            return;
        }
        CompressionBoxData.write(cur, items);
        this.backing.setStackInSlot(cell.maidSlot, cur);
    }

    /* ==================== IItemHandler ==================== */

    @Override
    public int getSlots() {
        return maidSlots() + CompressionBoxData.SLOTS * boxSlots().size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int n = maidSlots();
        if (slot < n) {
            return this.backing.getStackInSlot(slot);
        }
        Cell cell = cellAt(slot);
        if (cell == null) {
            return ItemStack.f_41583_;
        }
        ItemStack s = readBox(cell).get(cell.slotInBox);
        if (s.m_41619_()) {
            return ItemStack.f_41583_;
        }
        // 视野封顶：大堆只暴露一点点，而且给的是副本（外部改不到盒子里去）。
        // 【六百一十八修】上限不能一律按 64 算——见 CompressionBoxData.viewCap：
        // 不可堆叠的物品（附魔书/工具/甲）上限是 1，给原版一个「2 个的附魔书」这种
        // 非法堆，TLM 那边「插进背包」的调用点大多不看返回值，多出来的那个就没了。
        //
        // 【必须 min(真实数量, 上限)】viewCap 是**上限**不是数量——第一版写成
        // `copyWithCount(viewCap(s))`，于是"盒子里 5 个石头"会变成"她看见 64 个"
        // （凭空多出来，实测六百一十八自检的石头对照当场打红）。原版口径本来就是
        // 「不超过 min(64, 物品上限)」，真实数量更小时以真实数量为准。
        return s.m_255036_(Math.min(s.m_41613_(), CompressionBoxData.viewCap(s)));
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int n = maidSlots();
        if (slot < n) {
            this.backing.setStackInSlot(slot, stack);
            return;
        }
        Cell cell = cellAt(slot);
        if (cell == null) {
            return;
        }
        List<ItemStack> items = readBox(cell);
        if (stack.m_41619_()) {
            items.set(cell.slotInBox, ItemStack.f_41583_);
        } else {
            if (CompressionBoxData.isBox(stack)) {
                return; // 盒子不装盒子（与 isItemValid / mergeInto 同一个口径）
            }
            ItemStack copy = stack.m_41777_();
            // copyWithCount（javap 实证 m_255036_）：这里是"设数量"，不能用 m_41769_（那是 grow，
            // 会让数量整体 +1——实测六百一十七在 CompressionBoxData.fromTag 上就踩过这一脚）
            items.set(cell.slotInBox, copy.m_255036_(
                    Math.min(copy.m_41613_(), CompressionBoxData.maxStack())));
        }
        writeBox(cell, items);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        int n = maidSlots();
        if (slot < n) {
            return this.backing.insertItem(slot, stack, simulate);
        }
        if (stack.m_41619_()) {
            return ItemStack.f_41583_;
        }
        Cell cell = cellAt(slot);
        if (cell == null) {
            return stack;
        }
        List<ItemStack> items = readBox(cell);
        ItemStack left = CompressionBoxData.mergeInto(items, cell.slotInBox, stack);
        if (!simulate && left.m_41613_() != stack.m_41613_()) {
            writeBox(cell, items);
        }
        return left;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int n = maidSlots();
        if (slot < n) {
            return this.backing.extractItem(slot, amount, simulate);
        }
        Cell cell = cellAt(slot);
        if (cell == null || amount <= 0) {
            return ItemStack.f_41583_;
        }
        List<ItemStack> items = readBox(cell);
        // 大堆不许整格漏出去；不可堆叠的东西一次也只给 1（viewCap 见 CompressionBoxData）
        int take = Math.min(amount, CompressionBoxData.viewCap(items.get(cell.slotInBox)));
        ItemStack out = CompressionBoxData.take(items, cell.slotInBox, take);
        if (!simulate && !out.m_41619_()) {
            writeBox(cell, items);
        }
        return out;
    }

    @Override
    public int getSlotLimit(int slot) {
        int n = maidSlots();
        return slot < n ? this.backing.getSlotLimit(slot) : CompressionBoxData.maxStack();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int n = maidSlots();
        if (slot < n) {
            return this.backing.isItemValid(slot, stack);
        }
        // 盒子里不套盒子（套进去她也只看得见外层那一层，白占一格）
        return !CompressionBoxData.isBox(stack);
    }

    /** 序列化仍走她真正的背包（本类只是加了一层「读得到盒子」的视图） */
    @Override
    public CompoundTag serializeNBT() {
        return this.backing.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.backing.deserializeNBT(nbt);
    }

    /**
     * v1.2.2 实测六百一十六【诊断入口】——把"她背包视图里盒子那几格"打成几行字。
     *
     * 用户排查"她为什么不用我盒子里那十万个石头"时，这几行一眼就能看出三件事：
     * 盒子在不在她背包里、她的代码一共能看见多少格、每一格她**看得见多少**（= 压到 64 的视图）
     * 与盒子**实际存了多少**（真实 int 数量）。走 {@code /maid_smart box <女仆>} 打印。
     */
    public java.util.List<net.minecraft.network.chat.Component> describe() {
        java.util.List<net.minecraft.network.chat.Component> out = new java.util.ArrayList<>();
        try {
            int base = maidSlots();
            java.util.List<Integer> boxes = boxSlots();
            out.add(net.minecraft.network.chat.Component.m_237113_("\u00a7e"
                    + this.maid.m_7755_().getString() + "\u00a77 的背包视图：\u00a7f"
                    + getSlots() + " \u00a77格（" + base + " 格背包 + 压缩盒 \u00a7f"
                    + boxes.size() + "\u00a77 个 × " + CompressionBoxData.SLOTS + " 格）"));
            if (boxes.isEmpty()) {
                out.add(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a77背包里没有压缩盒——视图就等于原背包（" + base + " 格）。"));
                return out;
            }
            for (int k = 0; k < boxes.size(); k++) {
                int maidSlot = boxes.get(k);
                java.util.List<ItemStack> real =
                        CompressionBoxData.read(this.backing.getStackInSlot(maidSlot));
                for (int j = 0; j < CompressionBoxData.SLOTS; j++) {
                    int viewSlot = base + k * CompressionBoxData.SLOTS + j;
                    ItemStack seen = getStackInSlot(viewSlot);
                    ItemStack stored = j < real.size() ? real.get(j) : ItemStack.f_41583_;
                    out.add(net.minecraft.network.chat.Component.m_237113_("\u00a78  第 "
                            + viewSlot + " 格（背包第 " + maidSlot + " 格的盒子，第 " + (j + 1)
                            + " 格）：看得见 \u00a7f" + name(seen) + "\u00a78，实际 \u00a7f"
                            + name(stored)));
                }
            }
        } catch (Throwable t) {
            out.add(net.minecraft.network.chat.Component.m_237113_("\u00a7c诊断失败：" + t));
        }
        return out;
    }

    /** 诊断用的物品显示名（优先注册名，取不到就退回物品自己的字符串） */
    private static String name(ItemStack stack) {
        if (stack.m_41619_()) {
            return "空";
        }
        try {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return (id == null ? String.valueOf(stack.m_41720_()) : id.toString())
                    + " ×" + stack.m_41613_();
        } catch (Throwable ignored) {
            return String.valueOf(stack.m_41720_()) + " ×" + stack.m_41613_();
        }
    }
}
