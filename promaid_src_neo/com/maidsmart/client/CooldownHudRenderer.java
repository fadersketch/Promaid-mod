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
 * 实测四百二十一【冷却可视化】——反馈："我希望女仆复活的CD及自己回魂符的CD在
 * 玩家屏幕上可视化。"
 *
 * 实测四百三十三【照搬 HeartPact 的分娩倒计时显示机制】：
 * - **用 {@code @Mod.EventBusSubscriber} 注解常驻注册**（不再"收到第一个快照才
 *   register"——旧的懒注册一旦没触发，HUD 恒不显示）；
 * - 在 {@code RenderGuiLayerEvent.Post} 里【只认 HOTBAR 那一层】绘制（原版每帧
 *   热键栏画完时正好一次），带阴影、按剩余秒升序、彩色。
 *
 * 显示（左上角，建造 HUD 之下）：
 * 冷却
 *   复活 · 小玉    0:42
 *   回魂符 · 小玉  0:18 / 1:00
 *
 * ── 【实测六百七十八：这一块还顺带显示武装拴绳的"绑定中"】──
 * 玩家原话："在进入绑定状态下，最好是在左上角用蓝色字体显示一下玩家现在处于绑定状态。
 * （渲染机制同冷却计时）"——就是**复用本类**：服务端在同一个快照包里多塞一条
 * {@code kind = "bound"}（见 {@code CooldownHudTracker.broadcast}），于是位置、字体、行高、
 * "三秒没数据自动清空"、"打开界面 / F3 时整体隐藏"全套行为**一行都不用新写**。
 * 区别只有两处：① 那一段的字是**蓝色**（{@code §9}）；② 它没有倒计时，永远排在**最上**一行。
 * 它**不受"冷却 HUD"开关影响**（那是两件事；见服务端 {@code soulScanWanted()}）。
 *
 * <p>【实测六百八十二：它下面再多一行操作提示】玩家原话："目前在那个处于绑定状态下的HUD再加
 * 一句提示，提醒一下，玩家可以通过右击战术拴绳的方式切换为主动骑乘。"——见 {@link #HINT}：
 * 只在这条"绑定中"存在时画，灰字、缩进 4 像素，仍然受"放不下就不画"的边界保护。
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
            // 【实测六百八十二】"绑定中"那条下面再补一行操作提示。
            // 玩家原话："目前在那个处于绑定状态下的HUD再加一句提示，提醒一下，玩家可以通过右击
            // 战术拴绳的方式切换为主动骑乘。"——文案与右击的真实行为对齐（见
            // GunnerTetherManager.toggle / seatBackOnBroom）：她骑着扫帚时那一下右击 = 你坐回
            // 扫帚驾驶位（主动骑乘）；她没骑扫帚（空袭）时那一下右击 = 纯解除，所以这行两句都写。
            if (isBound(e)) {
                if (y > h - LINE_H - 2) {
                    return; // 提示行放不下就只留主行，绝不顶出屏幕
                }
                // 实测六百八十七：拆成两行，第一行强调"必须手持武装拴绳"（旧版没写，玩家会误解）
                gg.drawString(font, HINT1, x + 4, y, 0xFFFFFF, true);
                y += LINE_H;
                if (y > h - LINE_H - 2) {
                    return; // 第二行放不下就只留第一行，绝不顶出屏幕
                }
                gg.drawString(font, HINT2, x + 4, y, 0xFFFFFF, true);
                y += LINE_H;
            }
        }
    }

    /**
     * 【实测六百八十七】「绑定中」下面那两行操作提示（灰字）。
     *
     * <p>需求方原话："扫帚模式玩家绑定时HUD写详细一点。现在的就仅仅告诉你，右击之后可以进行
     * 切换，但是没有强调，是拿了武装拴绳右击之后才行。这个会让玩家产生误解。"
     * 旧版只有一句"右击她：解除；她骑扫帚时改为主动骑乘"——没写**手里得拿着那根武装拴绳**，
     * 玩家空手右击发现没反应（或以为随便什么物品都行）。现在拆两行：第一行把"手持武装拴绳"
     * 摆在开头，第二行再写那一下右击在两种情形下各自是什么。
     */
    private static final String HINT1 = "§7手持【武装拴绳】右击女仆 = 绑定 / 解除（空手右击无效）";
    private static final String HINT2 = "§8她骑扫帚时那一击 = 坐回驾驶位（主动骑乘）；空袭二号位时 = 解除";


    /** 按剩余秒升序（参考 HeartPact 分娩倒计时：最紧急的排最上） */
    private static java.util.List<String[]> sorted() {
        java.util.List<String[]> list = new java.util.ArrayList<>(ENTRIES);
        list.sort(java.util.Comparator.comparingLong(CooldownHudRenderer::remainOf));
        return list;
    }

    private static long remainOf(String[] e) {
        // 【实测六百七十八】"绑定中"那条没有倒计时（它不是冷却，是个状态指示）→ 永远排最上
        if (isBound(e)) {
            return Long.MIN_VALUE;
        }
        return e != null && e.length > 2 ? parse(e[2]) : Long.MAX_VALUE;
    }

    /** 这一条是不是"武装拴绳·绑定中"（服务端 kind = "bound"） */
    private static boolean isBound(String[] e) {
        return e != null && e.length > 0 && "bound".equals(e[0]);
    }

    /**
     * 一行 → 彩色文案（名字截断防超宽）。
     *
     * <p>【实测六百七十八 新增一档】{@code kind = "bound"}：武装拴绳的"绑定中"指示。
     * 玩家原话："在进入绑定状态下，最好是在左上角用蓝色字体显示一下玩家现在处于绑定状态。
     * （渲染机制同冷却计时）"——渲染机制**就是这一套**（同一个包、同一个左上角竖直串、
     * 同一条带阴影的文字、同样三秒没数据自动清空），只是这一段的字是**蓝色**（{@code §9}）。
     * 第二格是女仆名，第三格是相位（{@code leash} = 牵绳档，其它 = 二号位悬挂），
     * 两档在文案上直接写清楚——你一眼能看出"我现在是牵着还是在下面挂着"。
     */
    private static String format(String[] e) {
        if (e == null || e.length < 4) {
            return "";
        }
        if (isBound(e)) {
            return "\u00a79\u25c6 \u7ed1\u5b9a\u4e2d \u00a7b" + trim(e[1])
                    + " \u00a79" + ("leash".equals(e[2]) ? "\u7275\u7ef3" : "\u4e8c\u53f7\u4f4d");
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
