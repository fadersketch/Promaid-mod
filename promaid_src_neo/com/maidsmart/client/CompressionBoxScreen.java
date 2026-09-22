package com.maidsmart.client;

import com.maidsmart.box.CompressionBoxData;
import com.maidsmart.box.CompressionBoxFilter;
import com.maidsmart.box.CompressionBoxNetworking;
import com.maidsmart.box.CompressionBoxService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

import java.util.List;

/**
 * 压缩盒界面（v1.2.2 实测六百一十六；六百一十八改成箱子式鼠标取放）。
 *
 * ── 为什么不用原版 Menu/Slot ──
 * 这个盒子一格能装 114514 个，而原版整套槽位协议按「一格最多 64」写死，数量字段
 * 还有自己的取值范围（1.20.1 侧网络上与 NBT 里都是**1 字节**，javap 实证
 * {@code FriendlyByteBuf.writeItem} 里是 {@code writeByte(count)}：114514 到客户端
 * 会变成 82）。所以这里自己做：服务端算账（{@link CompressionBoxService}）、
 * 客户端只画和发点击，大堆的数量走我们自己的 int。
 *
 * ── 交互（六百一十八：与「往箱子里存东西」对齐）──
 * 用户的原话是「可以像往箱子存东西一样，玩家可以通过用鼠标的方式将物品拖进去」。
 * 六百一十六那版是「Shift+点背包格 = 存入」，玩家得先知道这条规矩才行——所以这一版
 * 换成原版那套**鼠标上挂着一叠**的手法：
 * <ul>
 *   <li>左键点背包格 = **拿起**整叠（挂在鼠标上）；再左键点盒子格 = **放下**
 *       （能塞多少塞多少，塞不下的还挂在手上）；</li>
 *   <li>右键 = 拿 1 个 / 放 1 个（背包格上按原版拿一半）；</li>
 *   <li>Shift+左键 = **快速移动**：背包格 → 盒子（整叠存进去）、盒子格 → 背包（最多 64）；</li>
 *   <li>手上有东西时点背包格 = 放下/合并，那一格是别的物品则**交换**（原版同款）；</li>
 *   <li>点界面空白处 = 手上的东西**还回背包**（原版是丢出去；这里保守一点，
 *       免得玩家手一滑把东西丢在地上）。</li>
 * </ul>
 * 那一叠的真身在**服务端**（{@code CompressionBoxService.CARRIES}），客户端这份只是
 * 服务端告诉它的样子——客户端自己记账等于送物品。关界面（ESC / 被别的界面顶掉 /
 * 掉线）时服务端会把它放回背包，装不下才掉在脚边（见
 * {@link CompressionBoxService#returnCarry}）。
 *
 * 布局全部是纯色块（{@code fill}）+ 物品图标，与本模组其它几个界面同款——
 * 不额外塞 GUI 贴图。数量画在格子下方（6 位数字也放得下，不会被截成「114.5k」）。
 *
 * ── 背包那 36 格按**原版口径**画（v1.2.2 实测六百一十七）──
 * 数量与耐久条都调原版那套装饰绘制（{@code renderItemDecorations}），所以这一片看起来
 * 和生存模式按 E 打开的物品栏一模一样。盒子那 5 格只借用它的**耐久条**（数量文字传空串），
 * 大堆的数量仍画在格子下方；悬停说明也不再只认盒子格（见 {@link #buildTip}）。
 *
 * ── 禁入清单（v1.2.2 实测六百二十）──
 * 用户要的「界面内无法放入附魔书/附魔武器和压缩盒，压缩盒在这个界面内无法被鼠标
 * 选中，并提示不能把压缩盒放进去」：判据统一在 {@link CompressionBoxFilter}，
 * 界面这一层只负责**把理由画出来**（红字提示 + 悬停说明里的一行），真正裁决仍在服务端。
 * 可以放进盒子里的东西一点没变（对照见自检 {@code CompressionBoxCheck}）。
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
    private static final int HINT2_Y = HOTBAR_Y + 18 + 7; // 「放不下」这类即时提示
    private static final int PANEL_H = HINT2_Y + 10;

    private static final int C_TEXT = 0xFFE8E8E8;
    private static final int C_DIM = 0xFF9A9A9A;
    private static final int C_BIG = 0xFFFFD24A;     // 超过 64 的数量用金色点出来
    private static final int C_FRAME = 0xFF2A2A33;
    private static final int C_WELL = 0xFF14141A;
    private static final int C_HOVER = 0xFFFFFFFF;
    private static final int C_WARN = 0xFFFF6A6A;    // 耐久见底 / 不能这么干

    private final int hand;
    private List<ItemStack> items;
    /** 鼠标上挂着的那一叠（服务端告诉我们的；空 = 没挂着） */
    private ItemStack carry = ItemStack.EMPTY;
    private int left;
    private int top;
    /** 「这一次点不了」的即时提示（本地算的，纯提示；真正裁决在服务端）+ 到期时刻 */
    private String hint;
    private long hintUntil;
    /** 这一屏是被「再开一次」顶掉的还是玩家关的（见 {@link #removed}） */
    private boolean replacing;

    public CompressionBoxScreen(int hand, List<ItemStack> items, ItemStack carry) {
        super(Component.literal("\u538b\u7f29\u76d2"));
        this.hand = hand;
        this.items = items;
        this.carry = carry == null ? ItemStack.EMPTY : carry;
    }

    /** S2C 到达：开屏 / 刷新（只认同一只手，防止把另一只手的盒子内容画进来） */
    public static void accept(int hand, List<ItemStack> items, ItemStack carry, String notice,
                              boolean open) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            if (open) {
                Screen cur = mc.screen;
                if (cur instanceof CompressionBoxScreen box && box.hand == hand) {
                    // 同一只手的界面已经开着：只刷新内容，别再 setScreen 一次
                    // （重开会走一遍 removed()，白白多发一次「手上东西还回去」）
                    box.items = items;
                    box.carry = carry == null ? ItemStack.EMPTY : carry;
                    box.acceptNotice(notice);
                    return;
                }
                if (cur instanceof CompressionBoxScreen old) {
                    old.replacing = true;
                }
                CompressionBoxScreen fresh = new CompressionBoxScreen(hand, items, carry);
                fresh.acceptNotice(notice);
                mc.setScreen(fresh);
                return;
            }
            Screen cur = mc.screen;
            if (cur instanceof CompressionBoxScreen box && box.hand == hand) {
                box.items = items;
                box.carry = carry == null ? ItemStack.EMPTY : carry;
                box.acceptNotice(notice);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 服务端说「这一次为什么没成」（v1.2.2 实测六百二十）。
     *
     * 【为什么要专门接这一条】界面上那几行红字是**本地算的**——只能覆盖写死的那两条
     * 判据（压缩盒 / 附魔物品）。配置里的禁入清单只有服务端读得到自己那份配置，
     * 被它拒的时候客户端算不出原因，看着就是「点了没反应」。所以服务端随内容同步
     * 把这句话带过来，这里照原样画成同一行红字。
     */
    private void acceptNotice(String notice) {
        if (notice != null && !notice.isEmpty()) {
            hint(notice);
        }
    }

    @Override
    public void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = Math.max(4, (this.height - PANEL_H) / 2);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 世界继续跑（与排班表/手册同款）
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == 256) { // ESC
            this.onClose();
            return true;
        }
        return true; // 界面开着时吞掉其它按键（别顺手把背包/快捷栏也开了）
    }

    /**
     * 吞掉滚轮（v1.2.2 实测六百一十八）。
     *
     * 【为什么必须自己接】原版 {@code Screen} 不接滚轮，于是事件会漏到
     * {@code MouseHandler} 的「滚轮切快捷栏」分支上：界面开着，玩家滚一下鼠标，
     * **手上那件就不是打开着的那个盒子了**（换成了隔壁格的东西）——之后每一次点击
     * 都会被服务端的「那只手里还是不是压缩盒」判据挡掉，看着像界面坏了。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        return true;
    }

    @Override
    public void onClose() {
        sendCarryDrop();
        super.onClose();
    }

    @Override
    public void removed() {
        if (!this.replacing) {
            sendCarryDrop(); // 被别的界面顶掉 / 退出世界时也要把手上的东西还回去
        }
        super.removed();
    }

    /** 告诉服务端「手上的东西还回去」——服务端自己决定进背包还是掉脚边 */
    private void sendCarryDrop() {
        CompressionBoxNetworking.BoxActionPacket.send(
                this.hand, CompressionBoxService.CARRY_DROP, 0, 0, false);
    }

    /** 自带底纹：不调 super（1.21.1 默认那层模糊+菜单底纹会盖住后画的内容） */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x66101018);
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
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.player == null
                    ? null : new PlayerMainInvWrapper(mc.player.getInventory());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 点击 ==================== */

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        try {
            boolean shift = hasShiftDown();
            int boxSlot = boxSlotAt(mx, my);
            if (boxSlot >= 0) {
                String why = refusal(true, boxSlot, shift);
                if (why != null) {
                    hint(why);
                } else {
                    CompressionBoxNetworking.BoxActionPacket.send(
                            this.hand, CompressionBoxService.SLOT_CLICK, boxSlot, button, shift);
                }
                return true;
            }
            int invSlot = invSlotAt(mx, my);
            if (invSlot >= 0) {
                String why = refusal(false, invSlot, shift);
                if (why != null) {
                    hint(why);
                } else {
                    CompressionBoxNetworking.BoxActionPacket.send(this.hand,
                            CompressionBoxService.SLOT_CLICK,
                            CompressionBoxData.SLOTS + invSlot, button, shift);
                }
                return true;
            }
            if (!this.carry.isEmpty()) {
                // 点空白处：手上的东西还回背包（原版是丢出去——这里保守一点）
                sendCarryDrop();
                hint("\u624b\u4e0a\u7684\u4e1c\u897f\u5df2\u8fd8\u56de\u80cc\u5305");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.mouseClicked(mx, my, button);
    }

    /**
     * 本地先算一遍「这一次点得下去吗」，点不下去就给一行红字提示（服务端那道判据
     * **一分都不会少**——它照样会拒，并且把它自己的理由回包过来，见
     * {@link #acceptNotice}）。
     *
     * 【为什么要先说一声】服务端拒了就什么都不发生，玩家只看得到「点了没反应」——
     * 那正是用户抱怨的「跟玩家的认知不太一样」。这里把服务端那几条判据在界面上先讲一遍。
     *
     * 六百二十起判据统一走 {@link CompressionBoxFilter}（与数据层/女仆那一侧同一个
     * 方法），并且在原来两条（盒子不装盒子、那一格是别的物品）之外补了三条：
     * <ul>
     *   <li>往盒子格里放**禁入清单**上的东西（带附魔的物品 / 配置清单）；</li>
     *   <li>Shift+左键（= 整叠存进盒子）那一格装的是禁入清单上的东西；</li>
     *   <li><b>鼠标不许选中压缩盒</b>（用户要的那条）：背包格上放着压缩盒时，
     *       点击既不拿起、也不和手上的东西交换，只回一句「压缩盒不能装进压缩盒」。
     *       服务端那条路上同样挡着（{@code CompressionBoxService.clickInv}）。</li>
     * </ul>
     *
     * @return 非 null = 已知点不下去（不用发包了，发了也是白跑），值就是给玩家的那句话
     */
    private String refusal(boolean inBox, int slot, boolean shift) {
        if (inBox) {
            if (this.carry.isEmpty() || shift) {
                return null; // 手上空着 = 拿起；shift = 快速移动到背包（都是往外拿）
            }
            String why = CompressionBoxFilter.reason(this.carry);
            if (why != null) {
                return why; // 压缩盒 / 带附魔的物品 / 配置禁入清单
            }
            ItemStack cur = slotOf(this.items, slot);
            if (!cur.isEmpty() && !ItemStack.isSameItemSameComponents(cur, this.carry)) {
                return "\u8fd9\u4e00\u683c\u5df2\u7ecf\u662f\u522b\u7684\u7269\u54c1";
            }
            return null;
        }
        // 背包格：只有「会把东西搬进盒子」的动作才看禁入清单（shift+左键 = 整叠存进去）；
        // 另外，压缩盒本身在这个界面里鼠标一律选不中（用户要的那条）
        ItemStack cur = playerStack(slot);
        if (shift) {
            return CompressionBoxFilter.reason(cur);
        }
        if (CompressionBoxData.isBox(cur)) {
            return CompressionBoxFilter.MSG_BOX;
        }
        return null;
    }

    /** 背包那一格的东西（客户端这份是实时的） */
    private ItemStack playerStack(int slot) {
        IItemHandler inv = playerInv();
        return inv == null ? ItemStack.EMPTY : inv.getStackInSlot(slot);
    }

    private void hint(String text) {
        this.hint = text;
        this.hintUntil = System.currentTimeMillis() + 1600L;
    }

    /* ==================== 绘制 ==================== */

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick); // 会连带调用 renderBackground（见上）
        int x0 = this.left;
        int y0 = this.top;
        int x1 = x0 + PANEL_W;
        int y1 = y0 + PANEL_H;

        // 面板 + 边框（纯色块，不引 GUI 贴图）
        g.fill(x0, y0, x1, y1, 0xE0101018);
        g.fill(x0, y0, x1, y0 + 1, 0xFF6A6A7A);
        g.fill(x0, y1 - 1, x1, y1, 0xFF6A6A7A);
        g.fill(x0, y0, x0 + 1, y1, 0xFF6A6A7A);
        g.fill(x1 - 1, y0, x1, y1, 0xFF6A6A7A);

        int cx = (x0 + x1) / 2;
        g.drawCenteredString(this.font, Component.literal("\u538b\u7f29\u76d2"), cx, y0 + TITLE_Y, C_BIG);
        g.drawCenteredString(this.font, Component.literal(
                        "\u5de6\u952e\u62ff\u8d77/\u653e\u4e0b \u00b7 \u53f3\u952e\u62ff 1/\u653e 1"
                                + " \u00b7 Shift+\u5de6\u952e\u5feb\u901f\u79fb\u52a8"),
                cx, y0 + HINT_Y, C_DIM);
        g.drawCenteredString(this.font, Component.literal(infoLine()), cx, y0 + INFO_Y, C_TEXT);

        int hoverBox = boxSlotAt(mx, my);
        int hoverInv = invSlotAt(mx, my);

        // 盒子 5 格
        for (int i = 0; i < CompressionBoxData.SLOTS; i++) {
            int fx = boxCellX(i) + CELL / 2 - 9;
            int fy = y0 + BOX_Y - 1;
            g.fill(fx, fy, fx + 18, fy + 18, i == hoverBox ? C_HOVER : C_FRAME);
            g.fill(fx + 1, fy + 1, fx + 17, fy + 17, C_WELL);
            ItemStack s = slotOf(this.items, i);
            if (s.isEmpty()) {
                continue;
            }
            g.renderItem(s.copyWithCount(1), boxIconX(i), y0 + BOX_Y);
            // 耐久条走原版那套装饰；数量文字传空串（原版只在「数量≠1 或文字非空」时画字，
            // 空串什么都不会画，但底部的耐久条照画）——6 位数下面另有一行
            g.renderItemDecorations(this.font, s, boxIconX(i), y0 + BOX_Y, "");
            String n = String.valueOf(s.getCount());
            g.drawCenteredString(this.font, Component.literal(n),
                    boxCellX(i) + CELL / 2, y0 + COUNT_Y, s.getCount() > 64 ? C_BIG : C_TEXT);
        }

        // 玩家背包（实时读客户端那份）
        IItemHandler inv = playerInv();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = row < 3 ? 9 + row * 9 + col : col;
                int fx = invIconX(col) - 1;
                int fy = invIconY(row) - 1;
                g.fill(fx, fy, fx + 18, fy + 18, slot == hoverInv ? C_HOVER : C_FRAME);
                g.fill(fx + 1, fy + 1, fx + 17, fy + 17, C_WELL);
                if (inv == null) {
                    continue;
                }
                ItemStack s = inv.getStackInSlot(slot);
                if (s.isEmpty()) {
                    continue;
                }
                g.renderItem(s, invIconX(col), invIconY(row));
                // 原版同款装饰：右下角数量（1 个不写）+ 底部耐久条 —— 与生存物品栏一致
                g.renderItemDecorations(this.font, s, invIconX(col), invIconY(row));
            }
        }

        // 即时提示（本地算的「这一下点不了」，1.6 秒）
        if (this.hint != null && System.currentTimeMillis() < this.hintUntil) {
            g.drawCenteredString(this.font, Component.literal(this.hint), cx, y0 + HINT2_Y, C_WARN);
        } else if (!this.carry.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(
                            "\u9f20\u6807\u4e0a\uff1a" + this.carry.getHoverName().getString()
                                    + " \u00d7" + this.carry.getCount()),
                    cx, y0 + HINT2_Y, C_TEXT);
        }

        // 悬停说明：名称 + 数量 + 耐久（背包格也画，跟原版一样）
        ItemStack hover = hovered(hoverBox, hoverInv, inv);
        if (!hover.isEmpty()) {
            Tip tip = buildTip(hover, hoverBox >= 0, hasShiftDown());
            int w = 0;
            for (String line : tip.lines) {
                w = Math.max(w, this.font.width(line));
            }
            w += 8;
            int hx = Math.min(mx + 8, this.width - w - 4);
            int hy = Math.max(4, my - 20);
            g.fill(hx - 3, hy - 3, hx + w, hy + tip.size() * 10 + 3, 0xF0101010);
            for (int i = 0; i < tip.size(); i++) {
                g.drawString(this.font, tip.lines.get(i), hx, hy + i * 10,
                        tip.colors.get(i), true);
            }
        } else if (hoverBox >= 0) {
            g.drawString(this.font, "\u7a7a\u683c\u5b50", mx + 8, my - 6, C_DIM, true);
        }

        // 鼠标上那一叠画在**最后**（要盖住所有格子）。数量/耐久交给原版装饰，
        // 所以手上那一叠看起来和原版箱子上挂着的那一叠一模一样。
        if (!this.carry.isEmpty()) {
            int ix = mx - 8;
            int iy = my - 8;
            g.renderItem(this.carry, ix, iy);
            g.renderItemDecorations(this.font, this.carry, ix, iy);
        }
    }

    /** 鼠标底下那一格的东西（盒子格优先；都没有 → 空堆） */
    private ItemStack hovered(int hoverBox, int hoverInv, IItemHandler inv) {
        if (hoverBox >= 0) {
            return slotOf(this.items, hoverBox);
        }
        if (hoverInv >= 0 && inv != null) {
            return inv.getStackInSlot(hoverInv);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 悬停说明的几行：名称 / 数量 / 耐久 /（按着 Shift 时的）那一行 / 禁入说明。
     * 用户要的「有多少个、耐久还剩多少」就在这里——数量 1 的原版不写，这里跟着不写（盒子格除外，
     * 盒子里 1 个也可能是关键的一格）；耐久只有能坏的物品有，掉到三分之一以下改成红字。
     *
     * 六百二十起：背包格上凡是**放不进盒子**的东西（压缩盒 / 带附魔的物品 / 配置禁入清单）
     * 都直接把理由写在说明里——不用先点一下才知道不行。
     */
    private Tip buildTip(ItemStack s, boolean inBox, boolean shift) {
        Tip tip = new Tip();
        tip.add(s.getHoverName().getString(), C_TEXT);
        if (inBox) {
            tip.add("\u00d7" + s.getCount() + "  \u6bcf\u683c\u4e0a\u9650 "
                    + CompressionBoxData.maxStack(), s.getCount() > 64 ? C_BIG : C_DIM);
        } else if (s.getCount() > 1) {
            tip.add("\u00d7" + s.getCount(), C_DIM);
        }
        int max = s.getMaxDamage();
        if (max > 0) {
            int left = max - s.getDamageValue();
            tip.add("\u8010\u4e45 " + left + " / " + max, left * 3 <= max ? C_WARN : C_DIM);
        }
        if (!inBox) {
            String why = CompressionBoxFilter.reason(s);
            if (why != null) {
                tip.add(why, C_WARN);
                if (CompressionBoxData.isBox(s)) {
                    tip.add("\u8fd9\u4e2a\u754c\u9762\u91cc\u9f20\u6807\u9009\u4e0d\u4e2d\u5b83", C_DIM);
                }
            } else if (shift) {
                tip.add("Shift+\u5de6\u952e\uff1a\u6574\u53e0\u5b58\u8fdb\u538b\u7f29\u76d2", C_DIM);
            }
        } else if (shift) {
            tip.add("Shift+\u5de6\u952e\uff1a\u5feb\u901f\u79fb\u52a8\u5230\u80cc\u5305", C_DIM);
        }
        return tip;
    }

    /** 「已装 N 个 · 占 x/5 格 · 每格上限 M」 */
    private String infoLine() {
        long total = CompressionBoxData.totalCount(this.items);
        int used = CompressionBoxData.usedSlots(this.items);
        return "\u5df2\u88c5 " + total + " \u4e2a \u00b7 \u5360 " + used + "/"
                + CompressionBoxData.SLOTS + " \u683c \u00b7 \u6bcf\u683c\u4e0a\u9650 "
                + CompressionBoxData.maxStack();
    }

    /** 悬停说明的一小块：几行字 + 每行自己的颜色（行由 {@link #buildTip} 拼） */
    private static final class Tip {
        private final java.util.List<String> lines = new java.util.ArrayList<>(4);
        private final java.util.List<Integer> colors = new java.util.ArrayList<>(4);

        void add(String text, int color) {
            this.lines.add(text);
            this.colors.add(color);
        }

        int size() {
            return this.lines.size();
        }
    }

    private static ItemStack slotOf(List<ItemStack> list, int i) {
        return list != null && i >= 0 && i < list.size() ? list.get(i) : ItemStack.EMPTY;
    }
}
