package com.maidsmart.build;

/**
 * v1.5.252j：建造 HUD（客户端）——左上角实时显示进行中区块：
 *
 *   ⛏ 建造进度
 *   olymp-final 12,345 / 493,527 (2%) [暂停]
 *   跳过 162 · 速度 1,234 块/秒 · 预计还需 6分32秒
 *
 * 防错位/防出屏（逐项约束）：
 * - 行高固定 10px，行与行严格步进，绝不重叠；
 * - 每行按字体宽度截断到屏幕右缘内（§ 颜色码完整保留，不出现乱码半截色）；
 * - 超过屏幕可用高度（h-12）的行直接不画；
 * - 打开任何界面（含手册）时不渲染——手册详情页已有进度显示，避免双份；
 * - F3 调试屏打开时不渲染（不与 F3 文字重叠）；
 * - 最多显示 3 个区块，超出折叠为"…还有 N 个区块"一行。
 */
public final class BuildHudRenderer {
    private static boolean registered = false;
    /** planId → {planId,name,placed,total,skipped,speed,eta,paused}，插入序 = 服务端广播序 */
    private static final java.util.LinkedHashMap<String, String[]> SNAPSHOT = new java.util.LinkedHashMap<>();
    /** 超出 HUD 显示上限的剩余区块数 */
    private static int hiddenCount = 0;
    private static final int MAX_REGIONS = 3;
    private static final int LINE_H = 10;

    private BuildHudRenderer() {
    }

    /** 客户端网络包回调：更新快照（主线程） */
    public static void onSnapshot(java.util.List<String[]> entries) {
        SNAPSHOT.clear();
        hiddenCount = 0;
        if (entries != null) {
            for (String[] e : entries) {
                if (SNAPSHOT.size() < MAX_REGIONS) {
                    SNAPSHOT.put(e[0], e);
                } else {
                    hiddenCount++;
                }
            }
        }
        if (!SNAPSHOT.isEmpty()) {
            ensureRegistered();
        }
    }

    private static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(BuildHudRenderer.class);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onGui(net.minecraftforge.client.event.RenderGuiOverlayEvent.Post event) {
        if (SNAPSHOT.isEmpty()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91080_ != null || mc.f_91066_.f_92028_) {
            return; // 打开界面 / F3 调试屏不显示（避免与菜单/调试重叠）
        }
        net.minecraft.client.gui.Font font = mc.f_91062_;
        if (font == null) {
            return;
        }
        int w = event.getWindow().m_85446_();
        int h = event.getWindow().m_85447_();
        net.minecraft.client.gui.GuiGraphics gg = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        y = line(gg, font, "\u00a7e\u26cf 建造进度", x, y, w, h);
        if (y < 0) {
            return;
        }
        for (String[] e : SNAPSHOT.values()) {
            y = entry(gg, font, e, x, y, w, h);
            if (y < 0) {
                return;
            }
        }
        if (hiddenCount > 0) {
            line(gg, font, "\u00a77\u2026还有 " + hiddenCount + " 个区块", x, y, w, h);
        }
    }

    /** 一个区块两行：名称+进度 / 跳过+速度+预计时间 */
    private static int entry(net.minecraft.client.gui.GuiGraphics gg, net.minecraft.client.gui.Font font,
                             String[] e, int x, int y, int w, int h) {
        try {
            String name = e[1];
            int placed = Integer.parseInt(e[2]);
            int total = Integer.parseInt(e[3]);
            int skipped = Integer.parseInt(e[4]);
            double speed = Double.parseDouble(e[5]);
            int eta = Integer.parseInt(e[6]);
            boolean paused = Boolean.parseBoolean(e[7]);
            int pct = total > 0 ? Math.min(100, placed * 100 / total) : 0;
            String line1 = "\u00a7f" + name + " \u00a77" + fmtNum(placed) + " / " + fmtNum(total)
                    + " (" + pct + "%)" + (paused ? " \u00a7c[\u6682\u505c]" : "");
            String line2 = "\u00a77跳过 " + fmtNum(skipped) + " \u00b7 速度 " + fmtSpeed(speed)
                    + " \u00b7 预计还需 " + fmtEta(eta);
            y = line(gg, font, line1, x, y, w, h);
            if (y < 0) {
                return -1;
            }
            return line(gg, font, line2, x, y, w, h);
        } catch (Exception ignored) {
            return y;
        }
    }

    /** 画一行：宽度超限截断（§ 颜色码完整保留），高度超限返回 -1 */
    private static int line(net.minecraft.client.gui.GuiGraphics gg, net.minecraft.client.gui.Font font,
                            String text, int x, int y, int w, int h) {
        if (y > h - LINE_H - 2) {
            return -1;
        }
        String t = fit(font, text, w - x - 4);
        gg.m_280137_(font, t, x, y, 0xFFFFFF); // drawString(字体, 文本, x, y, 颜色)
        return y + LINE_H;
    }

    /** 按字体宽度从尾部截断；若截到 § 颜色码则连 § 一起删（保留前面的颜色状态） */
    private static String fit(net.minecraft.client.gui.Font font, String s, int maxW) {
        if (font.m_92895_(s) <= maxW) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 0 && font.m_92895_(sb.toString()) > maxW) {
            int len = sb.length();
            if (len >= 2 && sb.charAt(len - 1) == '\u00a7') {
                sb.setLength(len - 2); // 删掉 §x 颜色码
            } else {
                sb.setLength(len - 1);
            }
        }
        return sb.toString();
    }

    private static String fmtNum(int n) {
        return String.format("%,d", n);
    }

    private static String fmtSpeed(double s) {
        if (s <= 0) {
            return "--";
        }
        return String.format("%.0f", s) + " 块/秒";
    }

    private static String fmtEta(int eta) {
        if (eta < 0) {
            return "--";
        }
        if (eta >= 3600) {
            return (eta / 3600) + "小时" + ((eta % 3600) / 60) + "分";
        }
        if (eta >= 60) {
            return (eta / 60) + "分" + (eta % 60) + "秒";
        }
        return eta + "秒";
    }
}
