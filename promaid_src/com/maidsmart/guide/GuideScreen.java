package com.maidsmart.guide;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Promaid 详细介绍界面（v1.5.252h 收官功能）。
 *
 * 结构与 Promaid 手册的"建筑页面"一致：章节目录（按钮列表，翻页）→
 * 点章节 → 正文分页阅读（< 上一页 / 下一页 > + 页码）。
 *
 * 布局（照手册样式，全部自适应防重叠/出屏）：
 * - 标题：y=36 居中（drawCentered，fitText 截断防突出屏幕）
 * - 内容区：y=52 起，底 = h-56（页码 y=h-48 之上，翻页按钮 y=h-30 之上）
 * - 目录页：章节按钮（行高按窗口高自适应压缩，永不超过内容区底）
 * - 阅读页：正文自动换行分页（段间空行；换行时延续 § 颜色样式）
 * - 左上角："← 返回"（目录页回手册）/ "← 章节目录"（阅读页回目录）
 * 纯客户端 Screen，无网络。
 */
public class GuideScreen extends Screen {
    private static final int VIEW_CHAPTERS = 0;
    private static final int VIEW_READ = 1;

    private static final int CONTENT_TOP = 52;
    /** 行高（正文逐行渲染） */
    private static final int LINE_H = 10;

    private final Screen parent;
    private int view = VIEW_CHAPTERS;
    /** 章节目录页码 */
    private int chapterPage = 0;
    /** 当前阅读章节下标 + 阅读页码 */
    private int reading = -1;
    private int readPage = 0;
    /** 当前章节的渲染行列表（换行后）——changelog 按行、普通章节段落换行+空行 */
    private List<String> lines = new ArrayList<>();
    /** 更新日志行（资源文件，一次加载） */
    private static String[] changelogLines = null;
    /** 更新日志章节下标（chapters() 里动态构造，防静态顺序漂移） */
    private static int changelogIndex = -1;

    public GuideScreen(Screen parent) {
        super(Component.m_237113_("Promaid 详细介绍"));
        this.parent = parent;
    }

    /** 从手册大目录打开 */
    public static void open(Screen parent) {
        Minecraft.m_91087_().m_91152_(new GuideScreen(parent));
    }

    // ---------- init ----------

    @Override
    protected void m_7856_() {
        this.m_169413_(); // clearWidgets
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        if (this.view == VIEW_READ) {
            this.readButtons(w, h, cx);
        } else {
            this.chaptersButtons(w, h, cx);
        }
    }

    // ================= 章节目录 =================

    private void chaptersButtons(int w, int h, int cx) {
        com.maidsmart.guide.GuideContent.Chapter[] chs = com.maidsmart.guide.GuideContent.chapters();
        // 行高按窗口高自适应（照 PromaidConfigScreen 目录页的压缩模式，防止与
        // 底部页码/翻页按钮重叠；最小 17px 行高，任意常见窗口不越界）
        int rowH = 24;
        if (h < 238) {
            rowH = 21;
        }
        if (h < 214) {
            rowH = 18;
        }
        if (h < 188) {
            rowH = 16;
        }
        int bh = Math.max(13, rowH - 3);
        int gap = rowH - bh;
        int contentBottom = h - 56; // 页码（h-48）之上，留 8px
        int perPage = Math.max(3, (contentBottom - CONTENT_TOP) / rowH);
        int totalPages = Math.max(1, (chs.length + perPage - 1) / perPage);
        this.chapterPage = Math.min(this.chapterPage, totalPages - 1);
        int start = this.chapterPage * perPage;
        int end = Math.min(chs.length, start + perPage);
        int btnW = Math.min(340, w - 40);
        int x = cx - btnW / 2;
        int y = CONTENT_TOP + 2;
        for (int i = start; i < end; i++) {
            final int idx = i;
            String num = (i + 1) < 10 ? "0" + (i + 1) : String.valueOf(i + 1);
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            "\u00a7e" + num + "  " + this.fitText(chs[i].title, btnW - 44)),
                            b -> {
                                this.reading = idx;
                                this.readPage = 0;
                                this.lines = this.buildLines(idx);
                                this.view = VIEW_READ;
                                this.m_7856_();
                            })
                    .m_252987_(x, y, btnW, bh).m_253136_());
            y += rowH;
        }
        this.pageButtons(totalPages, this.chapterPage, cx, h, false);
        // 左上角：返回手册大目录
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回"),
                        b -> this.m_7379_())
                .m_252987_(8, 8, 70, 16).m_253136_());
    }

    // ================= 章节阅读 =================

    private void readButtons(int w, int h, int cx) {
        com.maidsmart.guide.GuideContent.Chapter[] chs = com.maidsmart.guide.GuideContent.chapters();
        if (this.reading < 0 || this.reading >= chs.length) {
            this.reading = -1;
            this.view = VIEW_CHAPTERS;
            this.m_7856_();
            return;
        }
        int contentBottom = h - 56;
        int perPage = Math.max(5, (contentBottom - CONTENT_TOP) / LINE_H);
        int totalPages = Math.max(1, (this.lines.size() + perPage - 1) / perPage);
        this.readPage = Math.min(this.readPage, totalPages - 1);
        this.pageButtons(totalPages, this.readPage, cx, h, true);
        // 左上角：返回章节目录
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 章节目录"),
                        b -> {
                            this.view = VIEW_CHAPTERS;
                            this.m_7856_();
                        })
                .m_252987_(8, 8, 90, 16).m_253136_());
    }

    /** 底部翻页按钮（v1.1.0 实测二十五：80 宽"上一页/下一页"会盖住正文末行——
     *  改 20 宽纯箭头，页码画在两箭头之间不重叠。
     *  v1.1.0 实测一百七十八：箭头外移 12px（◀ cx-52 / ▶ cx+32）——页码
     *  "第 10/10 页"约 56px 宽，旧版两箭头内净宽仅 40px（cx±20），多页数时
     *  页码两端压进箭头；外移后内净宽 64px 任意页码都不接触 */
    private void pageButtons(int totalPages, int page, int cx, int h, boolean isRead) {
        if (totalPages <= 1) {
            return;
        }
        int py = h - 30;
        if (page > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                            b -> {
                                if (isRead) {
                                    this.readPage--;
                                } else {
                                    this.chapterPage--;
                                }
                                this.m_7856_();
                            })
                    .m_252987_(cx - 52, py, 20, 18).m_253136_());
        }
        if (page < totalPages - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                            b -> {
                                if (isRead) {
                                    this.readPage++;
                                } else {
                                    this.chapterPage++;
                                }
                                this.m_7856_();
                            })
                    .m_252987_(cx + 32, py, 20, 18).m_253136_());
        }
    }

    // ================= 内容行构建 =================

    /** 章节 → 渲染行列表（普通章节：段落换行 + 段间空行；更新日志：逐条完整换行显示） */
    private List<String> buildLines(int idx) {
        List<String> out = new ArrayList<>();
        com.maidsmart.guide.GuideContent.Chapter ch = com.maidsmart.guide.GuideContent.chapters()[idx];
        int maxWidth = this.f_96543_ - 24;
        if (ch.changelog) {
            // v1.5.252h：更新日志不再 fitText 截断——用户反馈省略号太多（"至少要把
            // 这一句话显示完整"）；改为与正文一致的自动换行，每条完整可见
            String[] rows = loadChangelog();
            for (String r : rows) {
                List<String> wrapped = this.wrapText(r, maxWidth);
                if (wrapped.isEmpty()) {
                    wrapped.add("");
                }
                out.addAll(wrapped);
            }
            return out;
        }
        for (String p : ch.paras) {
            if (p == null) {
                continue;
            }
            List<String> wrapped = this.wrapText(p, maxWidth);
            if (wrapped.isEmpty()) {
                wrapped.add("");
            }
            out.addAll(wrapped);
            out.add(""); // 段间空行
        }
        // v1.5.252h：去掉末尾空行——段落后追加的空行若落在分页边界，
        // 最后一页只剩空白行（用户反馈"贴身辅助第 2 页完全空白"）
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 按像素宽度逐字符换行（中英文混排），换行时延续 § 颜色样式——
     * 旧式折行会在换行处丢掉颜色码，强调文字后半段变白。
     */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String active = ""; // 当前生效的颜色/样式码（§x）
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            if (ch == '\u00a7' && i + 1 < text.length()) {
                active = "\u00a7" + text.charAt(i + 1);
            }
            String test = cur.toString() + ch;
            if (this.f_96547_.m_92895_(test) > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(active); // 换行延续当前颜色
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 截断到屏幕宽度（长标题/长版本行防突出屏幕） */
    private String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (this.f_96547_.m_92895_(text) <= maxWidth) {
            return text;
        }
        String t = text;
        while (!t.isEmpty() && this.f_96547_.m_92895_(t + "…") > maxWidth) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    /** 更新日志行（资源文件一次加载；缺失/异常给提示行） */
    private static String[] loadChangelog() {
        if (changelogLines != null) {
            return changelogLines;
        }
        try (java.io.InputStream in = GuideScreen.class.getResourceAsStream(
                "/assets/promaid/guide/changelog.txt")) {
            if (in == null) {
                changelogLines = new String[]{"\u00a7c更新日志资源缺失（assets/promaid/guide/changelog.txt）"};
                return changelogLines;
            }
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            List<String> rows = new ArrayList<>();
            for (String ln : text.split("\r?\n")) {
                if (!ln.trim().isEmpty()) {
                    rows.add(ln.trim());
                }
            }
            changelogLines = rows.toArray(new String[0]);
        } catch (Exception e) {
            changelogLines = new String[]{"\u00a7c更新日志加载失败: " + e.getMessage()};
        }
        return changelogLines;
    }

    // ================= 渲染 =================

    /** 居中标题（圆心 = 屏幕中心，fitText 防突出屏幕——手册 drawCentered 同款） */
    private void drawCentered(net.minecraft.client.gui.GuiGraphics graphics, String text, int y, int color) {
        String t = this.fitText(text, this.f_96543_ - 20);
        graphics.m_280653_(this.f_96547_, Component.m_237113_(t), this.f_96543_ / 2, y, color);
    }

    @Override
    public void m_88315_(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(graphics); // renderBackground
        int h = this.f_96544_;
        if (this.view == VIEW_READ) {
            com.maidsmart.guide.GuideContent.Chapter[] chs = com.maidsmart.guide.GuideContent.chapters();
            if (this.reading >= 0 && this.reading < chs.length) {
                this.drawCentered(graphics, "\u00a7e" + chs[this.reading].title, 36, 0xFFFFFF);
                this.renderLines(graphics);
            }
        } else {
            this.drawCentered(graphics, "\u00a7ePromaid 详细介绍 · 章节目录", 36, 0xFFFFFF);
            // v1.5.252h：目录页不再显示章节总数说明（用户要求"不要再有字"）
        }
        // 页码（v1.1.0 实测二十五：画在两箭头中间 h-26 行——箭头 20px 在两侧，
        // 页码居中，任意文本长度不与按钮重叠）
        int totalPages = this.currentPages();
        if (totalPages > 1) {
            int page = this.view == VIEW_READ ? this.readPage : this.chapterPage;
            this.drawCentered(graphics, "\u00a77第 " + (page + 1) + "/" + totalPages + " 页",
                    h - 26, 0xAAAAAA);
        }
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    /** 当前视图总页数 */
    private int currentPages() {
        int h = this.f_96544_;
        if (this.view == VIEW_READ) {
            int perPage = Math.max(5, (h - 56 - CONTENT_TOP) / LINE_H);
            return Math.max(1, (this.lines.size() + perPage - 1) / perPage);
        }
        int rowH = h < 238 ? (h < 214 ? (h < 188 ? 16 : 18) : 21) : 24;
        int perPage = Math.max(3, (h - 56 - CONTENT_TOP) / rowH);
        int n = com.maidsmart.guide.GuideContent.chapters().length;
        return Math.max(1, (n + perPage - 1) / perPage);
    }

    /** 正文行渲染（左对齐 12px，右缘不超屏） */
    private void renderLines(net.minecraft.client.gui.GuiGraphics graphics) {
        int h = this.f_96544_;
        int perPage = Math.max(5, (h - 56 - CONTENT_TOP) / LINE_H);
        int start = this.readPage * perPage;
        int end = Math.min(this.lines.size(), start + perPage);
        int y = CONTENT_TOP;
        for (int i = start; i < end; i++) {
            String line = this.lines.get(i);
            if (!line.isEmpty()) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_(line), 12, y, 0xDDDDDD, false);
            }
            y += LINE_H;
        }
    }

    @Override
    public void m_7379_() {
        super.m_7379_();
        Minecraft.m_91087_().m_91152_(this.parent);
    }
}
