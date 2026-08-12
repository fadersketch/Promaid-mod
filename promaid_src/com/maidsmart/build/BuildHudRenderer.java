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
 * - 每行按字体宽度折行成多行（§ 颜色码完整保留，不出现乱码半截色），
 *   超出屏幕可用宽度自动换行、超出可用高度的行直接不画——永不顶出屏幕；
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

    /** 画一段文本：按宽度折行成多行逐行绘制（不截断不省略），高度超限返回 -1 */
    private static int line(net.minecraft.client.gui.GuiGraphics gg, net.minecraft.client.gui.Font font,
                            String text, int x, int y, int w, int h) {
        if (y > h - LINE_H - 2) {
            return -1;
        }
        int maxW = Math.min(w - x - 4, (int) (w * 0.7)); // 右缘留 4px + 单行最长占屏 70%
        for (String seg : wrap(font, text, maxW)) {
            if (y > h - LINE_H - 2) {
                return -1; // 折行后超出屏幕高度 → 停画（不顶出屏幕）
            }
            gg.m_280137_(font, seg, x, y, 0xFFFFFF); // drawString(字体, 文本, x, y, 颜色)
            y += LINE_H;
        }
        return y;
    }

    /** 按字体宽度折行（多行显示，不截断）：§ 颜色码完整保留（占 2 字符、宽度 0）；
     *  优先在最近空格处断行（英文单词不拆），无空格则按字符硬断 */
    private static java.util.List<String> wrap(net.minecraft.client.gui.Font font, String s, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (font.m_92895_(s) <= maxW) {
            out.add(s);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < n) {
                cur.append(c).append(s.charAt(i + 1)); // 颜色码整体带走
                i += 2;
                continue;
            }
            if (font.m_92895_(cur.toString() + c) > maxW && cur.length() > 0) {
                int sp = cur.lastIndexOf(" ");
                if (sp > 0) {
                    String tail = cur.substring(sp + 1);
                    cur.setLength(sp);
                    out.add(cur.toString().trim());
                    cur = new StringBuilder(tail);
                } else {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                }
                continue; // 不吞掉当前字符，下一轮处理
            }
            cur.append(c);
            i++;
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String fmtNum(int n) {
        return String.format("%,d", n);
    }

    private static String fmtSpeed(double s) {
        if (s <= 0) {
            return "--";
        }
        if (s < 10) {
            return String.format("%.1f", s) + " 块/秒"; // 慢速建造显示小数，不再吞成 0/1
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
