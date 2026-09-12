package com.maidsmart.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 实测四百二十一【冷却可视化】——用户："我希望女仆复活的CD及自己回魂符的CD在
 * 玩家屏幕上可视化。"
 *
 * 实测四百三十三【照搬 HeartPact 的分娩倒计时显示机制】：
 * - **用 {@code @Mod.EventBusSubscriber} 注解常驻注册**（不再"收到第一个快照才
 *   register"——旧的懒注册一旦没触发，HUD 恒不显示）；
 * - 在 {@code RenderGuiLayerEvent.Post} 里【只认 HOTBAR 那一层】绘制（原版每帧
 *   热键栏画完时正好一次），带阴影、按剩余秒升序、彩色。
 *
 * 显示（左上角，建造 HUD 之下）：
 *   ⏳ 冷却
 *   复活 · 小玉    0:42
 *   回魂符 · 小玉  0:18 / 1:00
 */
@EventBusSubscriber(modid = "promaid", value = Dist.CLIENT)
public final class CooldownHudRenderer {
    /** {kind, 显示名, 剩余秒, 总秒}，插入序 = 服务端打包序（复活在前、回魂符在后） */
    private static final java.util.List<String[]> ENTRIES = new java.util.ArrayList<>();
    private static long lastPacketMs = 0L;
    private static final int MAX_ENTRIES = 8;
    private static final int LINE_H = 10;
    /** 服务端广播间隔 1 秒；超过该毫秒数未收到新快照即视为结束并清空 */
    private static final long STALE_MS = 3000L;
    /** 诊断：首次快照 / 首次绘制各打一条 latest.log（便于排查"没数据"还是"没渲染"） */
    private static boolean loggedSnapshot = false;
    private static boolean loggedDraw = false;

    private CooldownHudRenderer() {
    }

    /** 客户端网络包回调：更新快照（主线程） */
    public static void onSnapshot(java.util.List<String[]> entries) {
        ENTRIES.clear();
        lastPacketMs = System.currentTimeMillis();
        int shown = 0;
        if (entries != null) {
            for (String[] e : entries) {
                if (shown >= MAX_ENTRIES) {
                    break;
                }
                ENTRIES.add(e);
                shown++;
            }
        }
        if (!loggedSnapshot && !ENTRIES.isEmpty()) {
            loggedSnapshot = true;
            com.mojang.logging.LogUtils.getLogger().info(
                    "cooldown hud: first snapshot received ({} entries)", ENTRIES.size());
        }
    }

    // 实测四百三十三：常驻注册（注解）+ 只认 HOTBAR 层——与 HeartPact 分娩倒计时同款。
    @SubscribeEvent
    public static void onRenderLayer(RenderGuiLayerEvent.Post event) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        if (!VanillaGuiLayers.HOTBAR.equals(event.getName())) {
            return; // 每帧只在热键栏画完时画一次（避免每层都画）
        }
        if (System.currentTimeMillis() - lastPacketMs > STALE_MS) {
            ENTRIES.clear(); // 服务端已不再下发（复活/冷却结束）——清空，不残留
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getDebugOverlay().showDebugScreen()) {
            return; // 打开界面 / F3 调试屏不显示
        }
        Font font = mc.font;
        if (font == null) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        int h = mc.getWindow().getGuiScaledHeight();
        int x = 4;
        int y = Math.max(4, com.maidsmart.build.BuildHudRenderer.bottomY());
        if (y > h - LINE_H - 2) {
            return;
        }
        gg.drawString(font, "\u00a7e\u23f3 \u51b7\u5374", x, y, 0xFFFFFF, true);
        if (!loggedDraw) {
            loggedDraw = true;
            com.mojang.logging.LogUtils.getLogger().info("cooldown hud: first draw at y={}", y);
        }
        y += LINE_H;
        for (String[] e : sorted()) {
            if (y > h - LINE_H - 2) {
                return; // 超出可用高度：剩余行不画，绝不顶出屏幕
            }
            gg.drawString(font, format(e), x, y, 0xFFFFFF, true);
            y += LINE_H;
        }
    }

    /** 按剩余秒升序（参考 HeartPact 分娩倒计时：最紧急的排最上） */
    private static java.util.List<String[]> sorted() {
        java.util.List<String[]> list = new java.util.ArrayList<>(ENTRIES);
        list.sort(java.util.Comparator.comparingLong(CooldownHudRenderer::remainOf));
        return list;
    }

    private static long remainOf(String[] e) {
        return e != null && e.length > 2 ? parse(e[2]) : Long.MAX_VALUE;
    }

    /** 一行：{kind, name, remain, total} → 彩色文案（名字截断防超宽） */
    private static String format(String[] e) {
        if (e == null || e.length < 4) {
            return "";
        }
        boolean revive = "revive".equals(e[0]);
        String name = trim(e[1]);
        long remain = parse(e[2]);
        long total = parse(e[3]);
        long safeRemain = Math.max(0L, Math.min(remain, total > 0 ? total : remain));
        if (revive) {
            return "\u00a7e\u590d\u6d3b \u00a7f" + name + " \u00a7e" + fmt(safeRemain);
        }
        return "\u00a7d\u56de\u9b42\u7b26 \u00a7f" + name + " \u00a7d" + fmt(safeRemain)
                + (total > 0 ? " \u00a77/ " + fmt(total) : "");
    }

    private static String trim(String s) {
        if (s == null) {
            return "\u5973\u4ec6";
        }
        if (s.length() <= 16) {
            return s;
        }
        return s.substring(0, 15) + "\u2026";
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** 秒 → "M:SS"（参考 HeartPact 分娩倒计时的时间格式） */
    private static String fmt(long sec) {
        long s = Math.max(0L, sec);
        return (s / 60L) + ":" + String.format("%02d", s % 60L);
    }
}
