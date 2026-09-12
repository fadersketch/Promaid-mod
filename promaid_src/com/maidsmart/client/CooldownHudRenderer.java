package com.maidsmart.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 实测四百二十一【冷却可视化】——用户："我希望女仆复活的CD及自己回魂符的CD在
 * 玩家屏幕上可视化。"
 *
 * 实测四百三十三【照搬 HeartPact 的分娩倒计时显示机制】：
 * 带阴影、按剩余秒升序、彩色、时间 M:SS；收到快照才显示，3 秒无快照自动清空。
 *
 * 实测四百四十四【1.20.1 恒不渲染修复】：旧版（四百三十三）在 1.20.1 侧改成
 * {@code @Mod.EventBusSubscriber} 注解 + {@code RenderGuiOverlayEvent.Post}（只认
 * HOTBAR 层）。实测证据：1.20.1 客户端 latest.log 有
 * "cooldown hud: first snapshot received"，但【没有】"first draw" —— 数据到了、
 * 渲染回调却从未进入（注解自动注册在 1.20.1 未生效 / 或 overlay 事件没走到本类）。
 * 1.21.1（NeoForge 的 RenderGuiLayerEvent）同款写法则是好的，所以只改 1.20.1：
 * - 换用 {@code RenderGuiEvent.Post}：ForgeGui.render 末尾【每帧必然发一次】
 *   （反编译 ForgeGui 实证），不再依赖"每层 overlay 都发一次事件 + 比对 HOTBAR id"，
 *   少一个失败点；
 * - 换用【显式注册】（与同目录 BuildHudRenderer 同款 ensureRegistered 懒注册）：
 *   首个快照到达时 register 本类，不依赖注解扫描，注册行为确定可查。
 *
 * 显示（左上角，建造 HUD 之下）：
 *   ⏳ 冷却
 *   复活 · 小玉    0:42
 *   回魂符 · 小玉  0:18 / 1:00
 */
public final class CooldownHudRenderer {
    /** {kind, 显示名, 剩余秒, 总秒}，插入序 = 服务端打包序（复活在前、回魂符在后） */
    private static final java.util.List<String[]> ENTRIES = new java.util.ArrayList<>();
    private static long lastPacketMs = 0L;
    private static final int MAX_ENTRIES = 8;
    private static final int LINE_H = 10;
    /** 服务端广播间隔 1 秒；超过该毫秒数未收到新快照即视为结束并清空 */
    private static final long STALE_MS = 3000L;
    /** 诊断：快照 / 首次进入渲染回调 / 首次绘制各打一条 latest.log */
    private static boolean loggedSnapshot = false;
    private static boolean loggedEvent = false;
    private static boolean loggedDraw = false;
    /** 实测四百四十四：显式注册闸（不依赖注解自动注册） */
    private static boolean registered = false;

    private CooldownHudRenderer() {
    }

    /** 客户端网络包回调：更新快照（主线程）——顺带把自己挂到 Forge 事件总线 */
    public static void onSnapshot(java.util.List<String[]> entries) {
        ensureRegistered();
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

    /** 实测四百四十四：显式注册（客户端 Mod 构造期由 PromaidClientSetup 调用；
     *  onSnapshot 里再兜一次——registered 闸保证只注册一次） */
    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(CooldownHudRenderer.class);
        }
    }

    /** 实测四百四十六：诊断——首次"有数据却跳过绘制"时按原因各打一行，定位到底是哪个条件拦的 */
    private static final java.util.Set<String> LOGGED_SKIP = new java.util.HashSet<>();

    private static void noteSkip(String reason, int entries, long staleMs,
                                 boolean screenOpen, boolean f3, boolean fontOk, int y, int h) {
        try {
            if (!LOGGED_SKIP.add(reason) || LOGGED_SKIP.size() > 5) {
                return;
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "cooldown hud: SKIP[{}] entries={} staleMs={} screen={} f3={} font={} y={} h={}",
                    reason, entries, staleMs, screenOpen, f3, fontOk, y, h);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 实测四百四十四：整帧绘制一次（1.20.1 ForgeGui.render 末尾必然触发的
     * RenderGuiEvent.Post）。旧版用 RenderGuiOverlayEvent.Post + HOTBAR 层过滤，
     * 在 1.20.1 上回调未进入 → HUD 恒不显示。
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!loggedEvent) {
            loggedEvent = true;
            com.mojang.logging.LogUtils.getLogger().info("cooldown hud: first render event");
        }
        if (ENTRIES.isEmpty()) {
            return; // 没数据：正常，不记诊断
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.f_91066_ == null) {
            return;
        }
        boolean screenOpen = mc.f_91080_ != null;
        boolean f3 = mc.f_91066_.f_92063_;
        boolean fontOk = mc.f_91062_ != null;
        long staleMs = System.currentTimeMillis() - lastPacketMs;
        if (staleMs > STALE_MS) {
            int n = ENTRIES.size();
            ENTRIES.clear(); // 服务端已不再下发（复活/冷却结束）——清空，不残留
            noteSkip("stale", n, staleMs, screenOpen, f3, fontOk, -1, -1);
            return;
        }
        if (screenOpen || f3) {
            noteSkip(screenOpen ? "screen" : "f3", ENTRIES.size(), staleMs, screenOpen, f3, fontOk, -1, -1);
            return; // 打开界面 / F3 调试屏不显示
        }
        Font font = mc.f_91062_;
        if (font == null) {
            noteSkip("fontNull", ENTRIES.size(), staleMs, false, false, false, -1, -1);
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        // 实测四百四十六【1.20.1 恒不渲染的真正根因】：Window 的 SRG 名是
        // m_85445_=getGuiScaledWidth / m_85446_=getGuiScaledHeight / m_85447_=getX！
        // 旧版（含 433/444）误把 m_85447_ 当高度 → h 拿到的是【窗口 X 位置】，
        // 窗口贴左（x=0，最大化/全屏）时 h-12 = -12，y=4 永远大于它 → 每帧提前
        // return，HUD 恒不显示；窗口恰好在 x≥16 的位置时才偶然能画出来
        //（所以"换个摆法/我自己拉窗口测试就正常、你那边一直没有"）。
        int h = event.getWindow().m_85446_();
        int x = 4;
        int y = Math.max(4, com.maidsmart.build.BuildHudRenderer.bottomY());
        if (h > 32 && y > h - LINE_H - 2) {
            noteSkip("noRoom", ENTRIES.size(), staleMs, false, false, true, y, h);
            return; // h 明显异常（<=32）时不拦——宁可画出来也不要因为取值错误整块消失
        }
        gg.m_280056_(font, "\u00a7e\u23f3 \u51b7\u5374", x, y, 0xFFFFFF, true);
        if (!loggedDraw) {
            loggedDraw = true;
            com.mojang.logging.LogUtils.getLogger().info("cooldown hud: first draw at y={}", y);
        }
        y += LINE_H;
        for (String[] e : sorted()) {
            if (y > h - LINE_H - 2) {
                return; // 超出可用高度：剩余行不画，绝不顶出屏幕
            }
            gg.m_280056_(font, format(e), x, y, 0xFFFFFF, true);
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
