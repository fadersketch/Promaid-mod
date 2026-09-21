package com.maidsmart.client;

import com.maidsmart.box.CompressionBoxData;
import com.maidsmart.box.CompressionBoxNetworking;
import com.maidsmart.box.CompressionBoxService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import java.util.List;

/**
 * 压缩盒界面（v1.2.2 实测六百一十六）——**自制界面，不是原版容器界面**。
 *
 * ── 为什么不用原版 Menu/Slot ──
 * 这个盒子一格能装 114514 个，而 1.20.1 的原版整套槽位协议是按「一格最多 64」写死的，
 * 且数量在网络与 NBT 里都是**1 字节**（javap 实证 {@code FriendlyByteBuf.writeItem} 里
 * 是 {@code writeByte(count)}）：114514 走到客户端会变成 82，点击结算也会按错的数量来。
 * 所以这里自己做：服务端算账（{@link CompressionBoxService}）、客户端只画和发点击，
 * 大堆的数量走我们自己的 int。
 *
 * ── 交互（没有「光标上那一叠」，所以不走拖拽）──
 * <ul>
 *   <li>左键点盒子格 = 取 64 个进背包；右键 = 取 1 个；Shift+左键 = 整格取（装不下就停）；</li>
 *   <li>Shift+左键点背包格 = 把那一叠存进盒子；Shift+右键 = 只存 1 个；</li>
 *   <li>背包格本身不搬动（这不是背包整理界面），背包满时多出来的部分掉在脚边。</li>
 * </ul>
 * 布局全部是纯色块（{@code m_280509_}）+ 物品图标，与本模组其它几个界面同款——
 * 不额外塞 GUI 贴图。数量画在格子下方（6 位数字也放得下，不会被截成「114.5k」）。
 */
public class CompressionBoxScreen extends Screen {

    private static final int PANEL_W = 220;
    private static final int CELL = 40;              // 盒子格子的横向间距（够放 6 位数字）
    private static final int TITLE_Y = 8;
    private static final int HINT_Y = 21;
    private static final int INFO_Y = 32;            // 「已装 N 个 / 占 x/5 格」
    private static final int BOX_Y = 48;             // 盒子格图标 y
    private static final int COUNT_Y = BOX_Y + 20;   // 数量文字 y
    private static final int INV_Y = COUNT_Y + 14;   // 玩家背包第一行图标 y
    private static final int HOTBAR_Y = INV_Y + 3 * 18 + 4;
    private static final int PANEL_H = HOTBAR_Y + 18 + 10;

    private static final int C_TEXT = 0xFFE8E8E8;
    private static final int C_DIM = 0xFF9A9A9A;
    private static final int C_BIG = 0xFFFFD24A;     // 超过 64 的数量用金色点出来
    private static final int C_FRAME = 0xFF2A2A33;
    private static final int C_WELL = 0xFF14141A;
    private static final int C_HOVER = 0xFFFFFFFF;

    private final int hand;
    private List<ItemStack> items;
    private int left;
    private int top;

    public CompressionBoxScreen(int hand, List<ItemStack> items) {
        super(Component.m_237113_("\u538b\u7f29\u76d2"));
        this.hand = hand;
        this.items = items;
    }

    /** S2C 到达：开屏 / 刷新（只认同一只手，防止把另一只手的盒子内容画进来） */
    public static void accept(int hand, List<ItemStack> items, boolean open) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc == null) {
                return;
            }
            if (open) {
                mc.m_91152_(new CompressionBoxScreen(hand, items));
                return;
            }
            Screen cur = mc.f_91080_;
            if (cur instanceof CompressionBoxScreen box && box.hand == hand) {
                box.items = items;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void m_7856_() { // init
        this.left = (this.f_96543_ - PANEL_W) / 2;
        this.top = Math.max(4, (this.f_96544_ - PANEL_H) / 2);
    }

    @Override
    public boolean m_7043_() { // isPauseScreen
        return false; // 世界继续跑（与排班表/手册同款）
    }

    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) { // keyPressed
        if (key == 256) { // ESC
            this.m_7379_(); // onClose
            return true;
        }
        return true; // 界面开着时吞掉其它按键（别顺手把背包/快捷栏也开了）
    }

    /* ==================== 布局 ==================== */

    private int boxCellX(int i) {
        return this.left + (PANEL_W - CompressionBoxData.SLOTS * CELL) / 2 + i * CELL;
    }

    private int boxIconX(int i) {
        return this.boxCellX(i) + CELL / 2 - 8;
    }

    private int invIconX(int col) {
        int total = 9 * 18;
        return this.left + (PANEL_W - total) / 2 + col * 18;
    }

    private int invIconY(int row) {
        return row < 3 ? this.top + INV_Y + row * 18 : this.top + HOTBAR_Y;
    }

    /** 鼠标下的盒子格（-1 = 没有） */
    private int boxSlotAt(double mx, double my) {
        for (int i = 0; i < CompressionBoxData.SLOTS; i++) {
            int x = this.boxCellX(i) + CELL / 2 - 9;
            int y = this.top + BOX_Y - 1;
            if (mx >= x && mx < x + 18 && my >= y && my < y + 18) {
                return i;
            }
        }
        return -1;
    }

    /** 鼠标下的背包格（-1 = 没有；返回值 = 玩家背包的槽位号） */
    private int invSlotAt(double mx, double my) {
        for (int row = 0; row < 4; row++) {
            int y = invIconY(row) - 1;
            if (my < y || my >= y + 18) {
                continue;
            }
            for (int col = 0; col < 9; col++) {
                int x = invIconX(col) - 1;
                if (mx >= x && mx < x + 18) {
                    return row < 3 ? 9 + row * 9 + col : col;
                }
            }
        }
        return -1;
    }

    /** 玩家背包（客户端这份是实时的；槽位号与 PlayerMainInvWrapper 一致） */
    private IItemHandler playerInv() {
        try {
            Minecraft mc = Minecraft.m_91087_();
            return mc == null || mc.f_91074_ == null
                    ? null : new PlayerMainInvWrapper(mc.f_91074_.m_150109_());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 点击 ==================== */

    @Override
    public boolean m_6375_(double mx, double my, int button) { // mouseClicked
        try {
            int boxSlot = boxSlotAt(mx, my);
            if (boxSlot >= 0) {
                int action = -1;
                if (button == 1) {
                    action = CompressionBoxService.TAKE_ONE;
                } else if (button == 0 && m_96638_()) { // hasShiftDown
                    action = CompressionBoxService.TAKE_ALL;
                } else if (button == 0) {
                    action = CompressionBoxService.TAKE_STACK;
                }
                if (action >= 0) {
                    CompressionBoxNetworking.BoxActionPacket.send(this.hand, action, boxSlot);
                }
                return true;
            }
            int invSlot = invSlotAt(mx, my);
            if (invSlot >= 0 && m_96638_()) { // Shift+点背包格 = 存入
                int action = button == 1 ? CompressionBoxService.DEPOSIT_ONE
                        : CompressionBoxService.DEPOSIT_STACK;
                CompressionBoxNetworking.BoxActionPacket.send(this.hand, action, invSlot);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.m_6375_(mx, my, button);
    }

    /* ==================== 绘制 ==================== */

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float partialTick) { // render
        this.m_280039_(g); // renderBackground（变暗，与其它界面同款）
        int x0 = this.left;
        int y0 = this.top;
        int x1 = x0 + PANEL_W;
        int y1 = y0 + PANEL_H;

        // 面板 + 边框（纯色块，不引 GUI 贴图）
        g.m_280509_(x0, y0, x1, y1, 0xE0101018);
        g.m_280509_(x0, y0, x1, y0 + 1, 0xFF6A6A7A);
        g.m_280509_(x0, y1 - 1, x1, y1, 0xFF6A6A7A);
        g.m_280509_(x0, y0, x0 + 1, y1, 0xFF6A6A7A);
        g.m_280509_(x1 - 1, y0, x1, y1, 0xFF6A6A7A);

        int cx = (x0 + x1) / 2;
        g.m_280653_(this.f_96547_, Component.m_237113_("\u538b\u7f29\u76d2"), cx, y0 + TITLE_Y, C_BIG);
        g.m_280653_(this.f_96547_, Component.m_237113_(
                        "\u5de6\u952e\u53d6 64 \u00b7 \u53f3\u952e\u53d6 1 \u00b7 Shift+\u5de6\u952e\u6574\u683c\u53d6"
                                + " \u00b7 Shift+\u70b9\u80cc\u5305\u5b58\u5165"),
                cx, y0 + HINT_Y, C_DIM);
        g.m_280653_(this.f_96547_, Component.m_237113_(infoLine()), cx, y0 + INFO_Y, C_TEXT);

        int hoverBox = boxSlotAt(mx, my);
        int hoverInv = invSlotAt(mx, my);

        // 盒子 5 格
        for (int i = 0; i < CompressionBoxData.SLOTS; i++) {
            int fx = boxCellX(i) + CELL / 2 - 9;
            int fy = y0 + BOX_Y - 1;
            g.m_280509_(fx, fy, fx + 18, fy + 18, i == hoverBox ? C_HOVER : C_FRAME);
            g.m_280509_(fx + 1, fy + 1, fx + 17, fy + 17, C_WELL);
            ItemStack s = slotOf(this.items, i);
            if (s.m_41619_()) {
                continue;
            }
            g.m_280480_(s.m_255036_(1), boxIconX(i), y0 + BOX_Y);
            String n = String.valueOf(s.m_41613_());
            g.m_280653_(this.f_96547_, Component.m_237113_(n),
                    boxCellX(i) + CELL / 2, y0 + COUNT_Y, s.m_41613_() > 64 ? C_BIG : C_TEXT);
        }

        // 玩家背包（实时读客户端那份）
        IItemHandler inv = playerInv();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row < 3 ? 9 + row * 9 + col : col;
                int fx = invIconX(col) - 1;
                int fy = invIconY(row) - 1;
                g.m_280509_(fx, fy, fx + 18, fy + 18, slot == hoverInv ? C_HOVER : C_FRAME);
                g.m_280509_(fx + 1, fy + 1, fx + 17, fy + 17, C_WELL);
                if (inv == null) {
                    continue;
                }
                ItemStack s = inv.getStackInSlot(slot);
                if (s.m_41619_()) {
                    continue;
                }
                g.m_280480_(s, invIconX(col), invIconY(row));
            }
        }

        // 悬停说明（自己画：名称 + 数量 + 每格上限）
        ItemStack hover = hoverBox >= 0 ? slotOf(this.items, hoverBox) : ItemStack.f_41583_;
        if (!hover.m_41619_()) {
            String line1 = hover.m_41786_().getString();
            String line2 = "\u00d7" + hover.m_41613_() + "  \u00a77(\u6bcf\u683c\u4e0a\u9650 "
                    + CompressionBoxData.maxStack() + ")";
            int w = Math.max(this.f_96547_.m_92895_(line1), this.f_96547_.m_92895_(line2)) + 8;
            int hx = Math.min(mx + 8, this.f_96543_ - w - 4);
            int hy = Math.max(4, my - 20);
            g.m_280509_(hx - 3, hy - 3, hx + w, hy + 20, 0xF0101010);
            g.m_280056_(this.f_96547_, line1, hx, hy, 0xFFFFFFFF, true);
            g.m_280056_(this.f_96547_, line2, hx, hy + 10, 0xFFE0E0E0, true);
        } else if (hoverBox >= 0) {
            g.m_280056_(this.f_96547_, "\u7a7a\u683c\u5b50", mx + 8, my - 6, C_DIM, true);
        } else if (hoverInv >= 0 && m_96638_()) {
            g.m_280056_(this.f_96547_, "\u5b58\u5165\u538b\u7f29\u76d2", mx + 8, my - 6, C_TEXT, true);
        }
    }

    /** 「已装 N 个 · 占 x/5 格 · 每格上限 M」 */
    private String infoLine() {
        long total = CompressionBoxData.totalCount(this.items);
        int used = CompressionBoxData.usedSlots(this.items);
        return "\u5df2\u88c5 " + total + " \u4e2a \u00b7 \u5360 " + used + "/"
                + CompressionBoxData.SLOTS + " \u683c \u00b7 \u6bcf\u683c\u4e0a\u9650 "
                + CompressionBoxData.maxStack();
    }

    private static ItemStack slotOf(List<ItemStack> list, int i) {
        return list != null && i >= 0 && i < list.size() ? list.get(i) : ItemStack.f_41583_;
    }
}
