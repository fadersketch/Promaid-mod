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
        super(Component.literal("\u5efa\u9020\u843d\u70b9\u5fae\u8c03"));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int w = this.width;
        int h = this.height;
        // 第一排：XYZ 微调（6 个）
        int stepTotal = 6 * 44 - 4;
        int sx = w / 2 - stepTotal / 2;
        int y1 = h - 78;
        this.addRenderableWidget(Button.builder(Component.literal("X-1"), b -> this.shift(-1, 0, 0))
                .bounds(sx, y1, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("X+1"), b -> this.shift(1, 0, 0))
                .bounds(sx + 44, y1, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Y-1"), b -> this.shift(0, -1, 0))
                .bounds(sx + 88, y1, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Y+1"), b -> this.shift(0, 1, 0))
                .bounds(sx + 132, y1, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Z-1"), b -> this.shift(0, 0, -1))
                .bounds(sx + 176, y1, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Z+1"), b -> this.shift(0, 0, 1))
                .bounds(sx + 220, y1, 40, 20).build());
        // 第二排：转向 / 归位 / 确认 / 取消
        int row2 = 5 * 62 - 8;
        int r2x = w / 2 - row2 / 2;
        int y2 = h - 52;
        this.addRenderableWidget(Button.builder(Component.literal("\u5de6\u8f6c"),
                b -> com.maidsmart.build.BlueprintAreaPreview.rotateCounterClockwise())
                .bounds(r2x, y2, 58, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u53f3\u8f6c"),
                b -> com.maidsmart.build.BlueprintAreaPreview.rotateClockwise())
                .bounds(r2x + 62, y2, 58, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u56de\u5230\u811a\u4e0b"),
                b -> com.maidsmart.build.BlueprintAreaPreview.resetOriginToPlayer())
                .bounds(r2x + 124, y2, 62, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u00a7a\u786e\u8ba4\u5efa\u9020"),
                b -> this.confirm()).bounds(r2x + 190, y2, 62, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u00a77\u53d6\u6d88"),
                b -> this.onClose()).bounds(r2x + 256, y2, 52, 20).build());
    }

    private void shift(int dx, int dy, int dz) {
        int k = Screen.hasShiftDown() ? 8 : 1; // Shift → 大步长
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
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new BlueprintBookNetworking.SelectBlueprintPacket(id,
                        com.maidsmart.build.BlueprintAreaPreview.previewQuarters(),
                        o.getX(), o.getY(), o.getZ(), true));
        com.maidsmart.build.BlueprintAreaPreview.clear();
        com.maidsmart.build.BlueprintAreaPreview.resetSeen();
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        int w = this.width;
        int h = this.height;
        int top = h - 116;
        g.fill(0, top, w, h, PANEL_BG);
        g.fill(0, top, w, top + 1, PANEL_EDGE);

        String title = "\u00a7b\u3010\u5efa\u9020\u843d\u70b9\u5fae\u8c03\u3011\u00a7r \u62ff G \u5173\u6389\u8fd9\u4e2a\u754c\u9762\u56de\u5230\u4e16\u754c\u91cc\u770b\u6548\u679c";
        g.drawString(this.font, Component.literal(title), w / 2 - this.font.width(title) / 2, top + 8, 0xFFFFFF);

        BlockPos o = com.maidsmart.build.BlueprintAreaPreview.origin();
        int[] sz = com.maidsmart.build.BlueprintAreaPreview.previewSize();
        String posText = o == null ? "\u00a77\u843d\u70b9\uff1a\uff08\u65e0\uff09"
                : "\u00a7e\u843d\u70b9\uff1a\u00a7f" + o.getX() + ", " + o.getY() + ", " + o.getZ();
        String sizeText = "\u00a7e\u5360\u5730\uff1a\u00a7f" + sz[0] + "\u00d7" + sz[1] + "\u00d7" + sz[2]
                + "\u00a7e\u671d\u5411\uff1a\u00a7f"
                + (com.maidsmart.build.BlueprintAreaPreview.previewQuarters() * 90) + "\u00b0";
        String hint = "\u00a77\u6309\u4f4f Shift \u6b65\u8fdb 8 \u683c\uff1bZ \u952e\u540c\u6837\u80fd\u8f6c\u5411\uff1b"
                + "\u786e\u8ba4\u540e\u5973\u4ec6\u5c31\u6309\u8fd9\u4e2a\u4f4d\u7f6e\u5efa";
        g.drawString(this.font, Component.literal(posText), w / 2 - this.font.width(posText) / 2, top + 24, 0xFFFFFF);
        g.drawString(this.font, Component.literal(sizeText), w / 2 - this.font.width(sizeText) / 2, top + 38, 0xFFFFFF);
        g.drawString(this.font, Component.literal(hint), w / 2 - this.font.width(hint) / 2, top + 52, 0xAAAAAA);
        if (this.message != null) {
            g.drawString(this.font, Component.literal(this.message),
                    w / 2 - this.font.width(this.message) / 2, top + 66, this.messageColor);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
