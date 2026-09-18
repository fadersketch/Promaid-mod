package com.maidsmart.build;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 实测五百五十三②：建造落点微调界面（客户端）。
 *
 * 由来：以前金色预览框**每帧跟着玩家走**，落点 = "你确认那一刻站的地方"，
 * 想挪半格只能自己走位；参照 [车万重工]TLM-Builder 的 PlacementModeScreen 改成
 * "打开预览时定住落点 + 按钮微调"：
 *
 * - 打开方式：金色预览激活期间按 {@code G}（键位可在原版按键设置里改）；
 * - 布局：底部两排按钮——X/Y/Z 各 ±1（按住 Shift 走 ±8）、左转/右转（接 Z 键那套
 *   朝向，每次 90°）、回到脚下、确认建造、取消；
 * - 「确认建造」直接发 SelectBlueprintPacket（带当前落点与朝向），与在手册里
 *   点两次确认等价；服务端会校验距离/区块加载/重叠。
 *
 * 界面自绘（深色渐变，与手册/排班表同风格），不暂停游戏——挪落点时还能看着世界里的
 * 金色框与真方块投影实时变化。
 */
public class BuildPlacementScreen extends Screen {
    private static final int PANEL_BG = 0xC0102030;
    private static final int PANEL_EDGE = 0x6080C0FF;

    private String message = null;
    private int messageColor = 0xFFFFFF;

    public BuildPlacementScreen() {
        super(Component.m_237113_("\u5efa\u9020\u843d\u70b9\u5fae\u8c03"));
    }

    @Override
    protected void m_7856_() { // init
        this.m_169413_(); // clearWidgets
        int w = this.f_96543_;
        int h = this.f_96544_;
        // 第一排：XYZ 微调（6 个）
        int stepTotal = 6 * 44 - 4;
        int sx = w / 2 - stepTotal / 2;
        int y1 = h - 78;
        this.m_142416_(Button.m_253074_(Component.m_237113_("X-1"), b -> this.shift(-1, 0, 0))
                .m_252987_(sx, y1, 40, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("X+1"), b -> this.shift(1, 0, 0))
                .m_252987_(sx + 44, y1, 40, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("Y-1"), b -> this.shift(0, -1, 0))
                .m_252987_(sx + 88, y1, 40, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("Y+1"), b -> this.shift(0, 1, 0))
                .m_252987_(sx + 132, y1, 40, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("Z-1"), b -> this.shift(0, 0, -1))
                .m_252987_(sx + 176, y1, 40, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("Z+1"), b -> this.shift(0, 0, 1))
                .m_252987_(sx + 220, y1, 40, 20).m_253136_());
        // 第二排：转向 / 归位 / 确认 / 取消
        int row2 = 5 * 62 - 8;
        int r2x = w / 2 - row2 / 2;
        int y2 = h - 52;
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u5de6\u8f6c"), b -> {
            com.maidsmart.build.BlueprintAreaPreview.rotateCounterClockwise();
        }).m_252987_(r2x, y2, 58, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u53f3\u8f6c"), b -> {
            com.maidsmart.build.BlueprintAreaPreview.rotateClockwise();
        }).m_252987_(r2x + 62, y2, 58, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u56de\u5230\u811a\u4e0b"), b -> {
            com.maidsmart.build.BlueprintAreaPreview.resetOriginToPlayer();
        }).m_252987_(r2x + 124, y2, 62, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a\u786e\u8ba4\u5efa\u9020"), b -> this.confirm())
                .m_252987_(r2x + 190, y2, 62, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u53d6\u6d88"), b -> this.m_7379_())
                .m_252987_(r2x + 256, y2, 52, 20).m_253136_());
    }

    private void shift(int dx, int dy, int dz) {
        int k = Screen.m_96637_() ? 8 : 1; // hasShiftDown → 大步长
        com.maidsmart.build.BlueprintAreaPreview.shiftOrigin(dx * k, dy * k, dz * k);
    }

    private void confirm() {
        String id = com.maidsmart.build.BlueprintAreaPreview.previewBlueprintId();
        BlockPos o = com.maidsmart.build.BlueprintAreaPreview.origin();
        if (id == null || o == null) {
            this.message = "\u00a7c没有正在预览的图纸——先回手册点「建造此图纸」";
            this.messageColor = 0xFF5555;
            return;
        }
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookNetworking.SelectBlueprintPacket(id,
                        com.maidsmart.build.BlueprintAreaPreview.previewQuarters(),
                        o.m_123341_(), o.m_123342_(), o.m_123343_(), true));
        com.maidsmart.build.BlueprintAreaPreview.clear();
        com.maidsmart.build.BlueprintAreaPreview.resetSeen();
        this.m_7379_();
    }

    @Override
    public void m_88315_(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(g); // renderBackground
        int w = this.f_96543_;
        int h = this.f_96544_;
        int top = h - 116;
        g.m_280509_(0, top, w, h, PANEL_BG);
        g.m_280509_(0, top, w, top + 1, PANEL_EDGE);

        String title = "\u00a7b\u3010\u5efa\u9020\u843d\u70b9\u5fae\u8c03\u3011\u00a7r \u62ff G \u5173\u6389\u8fd9\u4e2a\u754c\u9762\u56de\u5230\u4e16\u754c\u91cc\u770b\u6548\u679c";
        g.m_280653_(this.f_96547_, net.minecraft.network.chat.Component.m_237113_(title), w / 2 - this.f_96547_.m_92895_(title) / 2, top + 8, 0xFFFFFF);

        BlockPos o = com.maidsmart.build.BlueprintAreaPreview.origin();
        int[] sz = com.maidsmart.build.BlueprintAreaPreview.previewSize();
        String posText = o == null ? "\u00a77\u843d\u70b9\uff1a\uff08\u65e0\uff09"
                : "\u00a7e\u843d\u70b9\uff1a\u00a7f" + o.m_123341_() + ", " + o.m_123342_() + ", " + o.m_123343_();
        String sizeText = "\u00a7e\u5360\u5730\uff1a\u00a7f" + sz[0] + "\u00d7" + sz[1] + "\u00d7" + sz[2]
                + "\u00a7e\u671d\u5411\uff1a\u00a7f"
                + (com.maidsmart.build.BlueprintAreaPreview.previewQuarters() * 90) + "\u00b0";
        String hint = "\u00a77\u6309\u4f4f Shift \u6b65\u8fdb 8 \u683c\uff1bZ \u952e\u540c\u6837\u80fd\u8f6c\u5411\uff1b"
                + "\u786e\u8ba4\u540e\u5973\u4ec6\u5c31\u6309\u8fd9\u4e2a\u4f4d\u7f6e\u5efa";
        g.m_280653_(this.f_96547_, net.minecraft.network.chat.Component.m_237113_(posText), w / 2 - this.f_96547_.m_92895_(posText) / 2, top + 24, 0xFFFFFF);
        g.m_280653_(this.f_96547_, net.minecraft.network.chat.Component.m_237113_(sizeText), w / 2 - this.f_96547_.m_92895_(sizeText) / 2, top + 38, 0xFFFFFF);
        g.m_280653_(this.f_96547_, net.minecraft.network.chat.Component.m_237113_(hint), w / 2 - this.f_96547_.m_92895_(hint) / 2, top + 52, 0xAAAAAA);
        if (this.message != null) {
            g.m_280653_(this.f_96547_, net.minecraft.network.chat.Component.m_237113_(this.message),
                    w / 2 - this.f_96547_.m_92895_(this.message) / 2, top + 66, this.messageColor);
        }
        super.m_88315_(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean m_7043_() { // isPauseScreen
        return false;
    }
}
