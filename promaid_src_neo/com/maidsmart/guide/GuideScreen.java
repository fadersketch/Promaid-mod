package com.maidsmart.guide;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

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

    /** 行内图标尺寸（与字号 9 的物品图标一致）。 */
    private static final int ICON = 10;
    /** 图标与前后文字的间距。 */
    private static final int ICON_GAP = 2;

    private final Screen parent;
    private int view = VIEW_CHAPTERS;
    /** 章节目录页码 */
    private int chapterPage = 0;
    /** 当前阅读章节下标 + 阅读页码 */
    private int reading = -1;
    private int readPage = 0;

    /**
     * 当前章节的**渲染元素**列表（换行后）。
     * <p>元素只有两种：{@link String}（一行正文，高 {@link #LINE_H}）与
     * {@link Recipe}（一整块合成表，高 {@link Recipe#height()}）。
     * 实测八百一十三之前这里只有 {@code List<String>}、分页按「每页几行」算；
     * 加入合成表这种**比一行高**的元素后，分页必须按**累计像素高**算（见
     * {@link #pageStart}）——否则合成表会被页边界劈开、或把最后一行的位置算错。
     */
    private List<Object> elements = new ArrayList<>();
    /** 实测四百二十四：手册内可点击的配置链接——[[@大类:小类:行标签|显示文字]]。 */
    private static final String LINK_OPEN = "[[@";
    /** 实测八百一十三：行内物品图标——[[i:物品id|说明文字]]（说明文字可省）。 */
    private static final String ICON_OPEN = "[[i:";
    /** 实测八百一十三：整块合成表——[[#shaped;行;行;行;K=物品id;>产物id;@说明]]。 */
    private static final String RECIPE_OPEN = "[[#";
    private static final String LINK_CLOSE = "]]";
    private final java.util.List<int[]> linkBoxes = new java.util.ArrayList<>();
    private final java.util.List<String> linkTargets = new java.util.ArrayList<>();

    /** 取链接目标（| 前面） */
    private static String linkTarget(String token) {
        int bar = token.indexOf('|');
        int end = token.length() - LINK_CLOSE.length();
        String inner = bar > 0 ? token.substring(LINK_OPEN.length(), bar)
                : token.substring(LINK_OPEN.length(), end);
        return inner.trim();
    }

    /** 取链接显示文字（| 后面，无 | 时 = 目标） */
    private static String linkLabel(String token) {
        int bar = token.indexOf('|');
        int end = token.length() - LINK_CLOSE.length();
        return bar > 0 ? token.substring(bar + 1, end) : linkTarget(token);
    }

    // ================= 行内物品图标 / 合成表 元素 =================

    /**
     * 行内元素：一段文字、或一枚物品图标。
     *
     * <p>实测八百一十三：原文是整行 {@link String}，图标要插在行中间，所以把"一行"拆成
     * 若干段（文字与图标混排），再整行居中画出来。
     */
    private static final class Seg {
        final String text;      // 文字段（text != null）
        final String iconId;    // 图标段（iconId != null）
        final String tip;       // 图标鼠标悬停说明（可 null）

        private Seg(String text, String iconId, String tip) {
            this.text = text;
            this.iconId = iconId;
            this.tip = tip;
        }

        static Seg ofText(String t) {
            return new Seg(t, null, null);
        }

        static Seg ofIcon(String id, String tip) {
            return new Seg(null, id, tip);
        }
    }

    /** 渲染行：居中，由若干 {@link Seg} 组成；height = 行高（图标行也占一行）。 */
    private static final class RLine {
        final boolean centered;
        final List<Seg> segs;

        RLine(boolean centered, List<Seg> segs) {
            this.centered = centered;
            this.segs = segs;
        }

        int height() {
            return LINE_H;
        }
    }

    /** 合成表格子：null = 空格子。 */
    private static final class Cell {
        final String id;

        Cell(String id) {
            this.id = id;
        }
    }

    /**
     * 整块合成表（实测八百一十三）。
     *
     * <p>【支持的形状】
     * <ul>
     *   <li>{@code shaped}：{@code "[[#shaped;IDI;RMR;IDI;I=铁块;D=钻石;R=红石块;M=矿车;>da_yun;@说明]]"}</li>
     *   <li>{@code shapeless}：{@code "[[#shapeless;书;纸;>blueprint_book;@说明]]"}</li>
     * </ul>
     * 物品在格子里的写法是**别名**（{@code I}）或**直接写 id**（{@code minecraft:iron_block}）；
     * 别名在 {@code 别名=物品id} 段里定义。
     */
    private static final class Recipe {
        final boolean shaped;
        final Cell[][] grid;            // shaped: 3×3 / shapeless: 1×N
        final int rows;
        final int cols;
        final String resultId;
        final String note;

        Recipe(boolean shaped, Cell[][] grid, String resultId, String note) {
            this.shaped = shaped;
            this.grid = grid;
            this.rows = grid.length;
            this.cols = grid.length == 0 ? 0 : grid[0].length;
            this.resultId = resultId;
            this.note = note;
        }

        int height() {
            // 上留白 3 + 格子 + 产物那一行 + 说明行 + 下留白 3
            return 6 + rows * (CELL + 2) + 12;
        }
    }

    /** 合成表格子像素尺寸（与物品图标一致）。 */
    private static final int CELL = 18;

    /**
     * 把一个 {@code [[#…]]} token 解析成 {@link Recipe}；解析失败返回 null（跳过，绝不崩界面）。
     * <p>格式（{@code ;} 分段，顺序固定：形状在前、{@code 别名=id} 与 {@code >产物}、
     * {@code @说明}在后、其余段是网格行）：
     * <pre>
     * [[#shaped;IDI;RMR;IDI;I=minecraft:iron_block;D=minecraft:diamond_block;R=minecraft:redstone_block;M=minecraft:minecart;>maid_smart:da_yun;@铁块×4 + 钻石块×2 + 红石块×2 + 矿车×1]]
     * [[#shapeless;minecraft:book;minecraft:paper;>maid_smart:blueprint_book;@书 + 纸]] </pre>
     */
    private static Recipe parseRecipe(String token) {
        try {
            int end = token.length() - LINK_CLOSE.length();
            String body = token.substring(3, Math.max(3, end)); // 去掉 "[[#" 与 "]]"
            String[] parts = body.split(";");
            if (parts.length < 2) {
                return null;
            }
            boolean shaped = "shaped".equalsIgnoreCase(parts[0].trim());
            boolean shapeless = "shapeless".equalsIgnoreCase(parts[0].trim());
            if (!shaped && !shapeless) {
                return null;
            }
            java.util.Map<String, String> alias = new java.util.HashMap<>();
            String result = null;
            String note = null;
            List<String> gridRows = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p == null || p.isEmpty()) {
                    continue;
                }
                char c0 = p.charAt(0);
                if (c0 == '>') {
                    result = p.substring(1).trim();
                } else if (c0 == '@') {
                    note = p.substring(1);
                } else {
                    int eq = p.indexOf('=');
                    if (eq > 0 && eq == 1) {
                        alias.put(p.substring(0, 1), p.substring(2).trim());
                    } else {
                        gridRows.add(p);
                    }
                }
            }
            if (result == null || gridRows.isEmpty()) {
                return null;
            }
            // 解析一个格子：先查别名表，再当 id 用
            java.util.function.Function<String, Cell> cellOf = s -> {
                if (s == null || s.isEmpty() || " ".equals(s) || "_".equals(s)) {
                    return null;
                }
                String id = alias.get(s);
                return new Cell(id != null ? id : s);
            };
            if (shaped) {
                int r = gridRows.size();
                int c = 0;
                for (String row : gridRows) {
                    c = Math.max(c, row.length());
                }
                if (r == 0 || c == 0) {
                    return null;
                }
                Cell[][] grid = new Cell[r][c];
                for (int i = 0; i < r; i++) {
                    String row = gridRows.get(i);
                    for (int j = 0; j < c; j++) {
                        grid[i][j] = j < row.length() ? cellOf.apply(row.substring(j, j + 1)) : null;
                    }
                }
                return new Recipe(true, grid, result, note);
            }
            // shapeless：每个网格行段里按 , 或 | 再拆
            List<Cell> flat = new ArrayList<>();
            for (String row : gridRows) {
                for (String s : row.split("[,|]")) {
                    Cell c = cellOf.apply(s.trim());
                    if (c != null) {
                        flat.add(c);
                    }
                }
            }
            if (flat.isEmpty()) {
                return null;
            }
            Cell[][] grid = new Cell[1][flat.size()];
            for (int i = 0; i < flat.size(); i++) {
                grid[0][i] = flat.get(i);
            }
            return new Recipe(false, grid, result, note);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GuideScreen", ignored);
            return null;
        }
    }

    /** 点击链接：打开模组详细配置并定位到对应大类/小类/参数行。 */
    private void openConfigLink(String target) {
        String[] parts = target.split(":", 3);
        com.maidsmart.config.PromaidConfigScreen.openAt(this,
                parts.length > 0 ? parts[0] : "",
                parts.length > 1 ? parts[1] : "",
                parts.length > 2 ? parts[2] : null);
    }

    /** 自跟踪焦点（同配置面板：键盘输入直接转发给点击过的输入框） */

    public GuideScreen(Screen parent) {
        super(Component.literal("Promaid 详细介绍"));
        this.parent = parent;
    }

    /** 从手册大目录打开 */
    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new GuideScreen(parent));
    }

    // ---------- init ----------

    @Override
    protected void init() {
        this.clearWidgets(); // clearWidgets
        int w = this.width;
        int h = this.height;
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
            this.addRenderableWidget(Button.builder(Component.literal(
                            "\u00a7e" + num + "  " + this.fitText(
                                    com.maidsmart.i18n.PromaidI18n.tr(chs[i].title), btnW - 44)),
                            b -> {
                                this.reading = idx;
                                this.readPage = 0;
                                this.elements = this.buildElements(idx);
                                this.view = VIEW_READ;
                                this.init();
                            })
                    .bounds(x, y, btnW, bh).build());
            y += rowH;
        }
        this.pageButtons(totalPages, this.chapterPage, cx, h, false);
        // 左上角：返回手册大目录
        this.addRenderableWidget(Button.builder(Component.literal("← 返回"),
                        b -> this.onClose())
                .bounds(8, 8, 70, 16).build());
        // 实测四百二十三：手册内的开关页（自动复活设置 / 日语语音包设置）已移除——
        // 要求「所有开关统一扔进模组详细配置界面，不得出现在其它地方」。
        // 对应参数现在只在 模组详细配置 里调整：
        //   自动复活 / 回魂符 → 生存与复活 · 死亡与复活
        //   内置日语语音包     → 语音与显示 · 语音与 TTS
    }


    // ================= 章节阅读 =================

    private void readButtons(int w, int h, int cx) {
        com.maidsmart.guide.GuideContent.Chapter[] chs = com.maidsmart.guide.GuideContent.chapters();
        if (this.reading < 0 || this.reading >= chs.length) {
            this.reading = -1;
            this.view = VIEW_CHAPTERS;
            this.init();
            return;
        }
        int contentBottom = h - 56;
        // 实测八百一十三：分页由「每页几行」改为「每页几像素高」——合成表比一行高，
        // 必须按元素自己的高度累加（见 pageRanges），否则会被页边界劈成两半。
        java.util.List<int[]> pages = this.pageRanges(contentBottom);
        int totalPages = pages.size();
        this.readPage = Math.min(this.readPage, totalPages - 1);
        this.pageButtons(totalPages, this.readPage, cx, h, true);
        // 左上角：返回章节目录
        this.addRenderableWidget(Button.builder(Component.literal("← 章节目录"),
                        b -> {
                            this.view = VIEW_CHAPTERS;
                            this.init();
                        })
                .bounds(8, 8, 90, 16).build());
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
            this.addRenderableWidget(Button.builder(Component.literal("\u00a77◀"),
                            b -> {
                                if (isRead) {
                                    this.readPage--;
                                } else {
                                    this.chapterPage--;
                                }
                                this.init();
                            })
                    .bounds(cx - 52, py, 20, 18).build());
        }
        if (page < totalPages - 1) {
            this.addRenderableWidget(Button.builder(Component.literal("\u00a77▶"),
                            b -> {
                                if (isRead) {
                                    this.readPage++;
                                } else {
                                    this.chapterPage++;
                                }
                                this.init();
                            })
                    .bounds(cx + 32, py, 20, 18).build());
        }
    }

    // ================= 内容行构建 =================

    /** 空行元素（段间空行） */
    private static RLine blankLine() {
        return new RLine(false, new ArrayList<>());
    }

    /** 章节 → 渲染元素列表（普通章节：段落换行 + 段间空行；更新日志：逐条完整换行显示） */
    private List<Object> buildElements(int idx) {
        List<Object> out = new ArrayList<>();
        com.maidsmart.guide.GuideContent.Chapter ch = com.maidsmart.guide.GuideContent.chapters()[idx];
        int maxWidth = this.width - 24;
        // 【v1.5.395 实测八百零七：手册改英文后正文仍中文的根因】
        // 旧流程是「先按像素折行、再交给绘制出口翻译」——而翻译表里存的是**整段原文**
        // （段落命中率 99.2%），折行后的每一片都不等于任何一个整段键，于是整篇一个词都换不掉。
        // 现在改成「先整段翻译、再折行」：译文按英文宽度折行，宽度也才对得上。
        // 链接 token [[@目标|文字]] 的「目标」全是 ASCII（本方法把中文标签照译，目标原样保留），
        // 所以点击定位不受影响。
        for (String p : ch.paras) {
            if (p == null) {
                continue;
            }
            this.appendParagraph(out, translateParagraph(p), maxWidth);
            out.add(blankLine()); // 段间空行
        }
        // 实测四百二十四：每章正文末尾自动附一行可点击的「配置入口」链接
        if (ch.paras.length > 0) {
            String cfgLink = com.maidsmart.guide.GuideContent.configLinkFor(ch.title);
            if (cfgLink != null) {
                out.add(blankLine());
                List<RLine> w = this.wrapLine("\u00a77\u2699 \u914d\u7f6e\u5165\u53e3\uff1a[[@" + cfgLink
                        + "|\u70b9\u6b64\u6253\u5f00\u5bf9\u5e94\u914d\u7f6e]]"
                        + "\uff08\u8df3\u8f6c\u540e\u76ee\u6807\u53c2\u6570\u884c\u4f1a\u9ad8\u4eae\uff09", maxWidth);
                out.addAll(w);
            }
        }
        // v1.5.252h：去掉末尾空行——段落后追加的空行若落在分页边界，
        // 最后一页只剩空白行（反馈"贴身辅助第 2 页完全空白"）
        while (!out.isEmpty() && out.get(out.size() - 1) instanceof RLine
                && ((RLine) out.get(out.size() - 1)).segs.isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 把一个段落追加进元素表：段落里可能夹着整块合成表 token（{@code [[#…]]}），
     * 所以先按它切开——文字段走 {@link #wrapLine} 折行，合成表段解析成 {@link Recipe}
     * 元素（前后各垫一个空行，让它从正文里"跳出来"）。
     */
    private void appendParagraph(List<Object> out, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            int open = text.indexOf(RECIPE_OPEN, i);
            if (open < 0) {
                out.addAll(this.wrapLine(text.substring(i), maxWidth));
                return;
            }
            if (open > i) {
                out.addAll(this.wrapLine(text.substring(i, open), maxWidth));
            }
            int close = text.indexOf(LINK_CLOSE, open);
            if (close < 0) {
                out.addAll(this.wrapLine(text.substring(open), maxWidth));
                return;
            }
            int end = close + LINK_CLOSE.length();
            String token = text.substring(open, end);
            Recipe r = parseRecipe(token);
            if (r != null) {
                out.add(blankLine());
                out.add(r);
                out.add(blankLine());
            } else {
                // 解析失败（写错了）：当普通文字显示出来，让作者看得见、玩家也不会崩
                out.addAll(this.wrapLine(token, maxWidth));
            }
            i = end;
        }
    }

    /**
     * <p>先按 {@code [[@目标|文字]]} 把链接**拆出来**，只翻译目标之外的普通文字，再按原样
     * 拼回去——这样 {@code @WORK:BUILD:指标石} 这种含中文小类名的定位目标不会被译坏
     * （译了它 {@code PromaidConfigScreen} 就找不到对应页了），而链接两侧的中文照译。
     * <p>实测八百一十三：{@code [[i:物品id|说明]]} 与 {@code [[#…合成表…]]} 两种 token
     * **整块原样保留**（绝不送进译表）——合成表里含中文的 {@code @说明} 段若被逐段
     * 贪心匹配，token 结构会被译坏；说明文字由 {@code drawRecipe} 单独翻译。
     */
    private static String translateParagraph(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            if (com.maidsmart.i18n.PromaidI18n.isChinese()) {
                return raw;
            }
            if (raw.indexOf("[[") < 0) {
                return com.maidsmart.i18n.PromaidI18n.tr(raw);
            }
            StringBuilder sb = new StringBuilder(raw.length() + 32);
            int i = 0;
            int n = raw.length();
            while (i < n) {
                int open = raw.indexOf("[[", i);
                if (open < 0) {
                    sb.append(com.maidsmart.i18n.PromaidI18n.tr(raw.substring(i)));
                    break;
                }
                int close = raw.indexOf(LINK_CLOSE, open);
                if (close < 0) {
                    sb.append(com.maidsmart.i18n.PromaidI18n.tr(raw.substring(i)));
                    break;
                }
                if (open > i) {
                    sb.append(com.maidsmart.i18n.PromaidI18n.tr(raw.substring(i, open)));
                }
                int end = close + LINK_CLOSE.length();
                String token = raw.substring(open, end);
                if (token.startsWith(LINK_OPEN)) {
                    // 配置链接：只译标签（| 之后），目标原样保留
                    int bar = token.indexOf('|');
                    if (bar > 0) {
                        String target = token.substring(0, bar);          // "[[@目标"
                        String label = token.substring(bar + 1, token.length() - LINK_CLOSE.length());
                        sb.append(target).append('|')
                                .append(com.maidsmart.i18n.PromaidI18n.tr(label))
                                .append(LINK_CLOSE);
                    } else {
                        sb.append(token);
                    }
                } else {
                    // 图标 / 合成表：整块原样
                    sb.append(token);
                }
                i = end;
            }
            return sb.toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GuideScreen", ignored);
            return raw;
        }
    }

    /**
     * 按像素宽度逐字符换行（中英文混排），换行时延续 § 颜色样式——
     * 旧式折行会在换行处丢掉颜色码，强调文字后半段变白。
     * <p>实测四百二十四：{@code [[@目标|文字]]} 当【原子 token】——按标签宽度计量、整块不拆行。
     * <p>实测八百一十三：再支持行内图标 {@code [[i:物品id|说明]]}——同样原子，
     * 按 {@link #ICON} + {@link #ICON_GAP} 计宽，不拆行。所以本方法产出的是
     * {@link RLine}（一行 = 若干段），不再是纯字符串。
     */
    private List<RLine> wrapLine(String text, int maxWidth) {
        List<RLine> out = new ArrayList<>();
        List<Seg> cur = new ArrayList<>();
        String active = "";
        int curW = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                out.add(new RLine(false, cur));
                cur = new ArrayList<>();
                curW = 0;
                i++;
                continue;
            }
            if (ch == '[' && (text.startsWith(ICON_OPEN, i) || text.startsWith(LINK_OPEN, i))) {
                int close = text.indexOf(LINK_CLOSE, i);
                if (close > 0) {
                    String token = text.substring(i, close + LINK_CLOSE.length());
                    boolean icon = text.startsWith(ICON_OPEN, i);
                    int tw = icon ? ICON + ICON_GAP : this.font.width(linkLabel(token));
                    if (curW + tw > maxWidth && !cur.isEmpty()) {
                        out.add(new RLine(false, cur));
                        cur = new ArrayList<>();
                        curW = 0;
                        if (!active.isEmpty()) {
                            cur.add(Seg.ofText(active));
                            curW = this.font.width(active);
                        }
                    }
                    if (icon) {
                        String inner = token.substring(ICON_OPEN.length(), token.length() - LINK_CLOSE.length());
                        int bar = inner.indexOf('|');
                        String id = (bar > 0 ? inner.substring(0, bar) : inner).trim();
                        String tip = bar > 0 ? inner.substring(bar + 1) : null;
                        cur.add(Seg.ofIcon(id, tip));
                    } else {
                        cur.add(Seg.ofText(token));
                    }
                    curW += tw;
                    i = close + LINK_CLOSE.length();
                    continue;
                }
            }
            if (ch == '\u00a7' && i + 1 < n) {
                active = "\u00a7" + text.charAt(i + 1);
                cur.add(Seg.ofText(active));
                i += 2;
                continue;
            }
            int cw = this.font.width(String.valueOf(ch));
            if (curW + cw > maxWidth && !cur.isEmpty()) {
                out.add(new RLine(false, cur));
                cur = new ArrayList<>();
                curW = 0;
                if (!active.isEmpty()) {
                    cur.add(Seg.ofText(active));
                    curW = this.font.width(active);
                }
            }
            cur.add(Seg.ofText(String.valueOf(ch)));
            curW += cw;
            i++;
        }
        if (!cur.isEmpty()) {
            out.add(new RLine(false, cur));
        }
        return out;
    }

    /** 一行（若干段）的像素宽（图标按 {@link #ICON} + {@link #ICON_GAP} 计）。 */
    private int lineWidth(RLine line) {
        int w = 0;
        for (Seg s : line.segs) {
            w += s.iconId != null ? ICON + ICON_GAP : this.font.width(s.text);
        }
        return w;
    }

    /** 截断到屏幕宽度（长标题/长版本行防突出屏幕） */
    private String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String t = text;
        while (!t.isEmpty() && this.font.width(t + "…") > maxWidth) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    // ================= 渲染 =================

    /** 居中标题（圆心 = 屏幕中心，fitText 防突出屏幕——手册 drawCentered 同款） */
    private void drawCentered(net.minecraft.client.gui.GuiGraphics graphics, String text, int y, int color) {
        String t = this.fitText(text, this.width - 20);
        graphics.drawCenteredString(this.font, Component.literal(t), this.width / 2, y, color);
    }

    /** 左对齐标签（设置页行标签，右缘不超屏） */
    private void drawLabel(net.minecraft.client.gui.GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(this.font, Component.literal(this.fitText(text, this.width - x - 8)),
                x, y, 0xFFFFFF, false);
    }

    /** 居中说明文字（设置页底部注解，居中 + fitText 防出屏） */
    private void drawNote(net.minecraft.client.gui.GuiGraphics graphics, String text, int y) {
        String t = this.fitText(text, this.width - 20);
        graphics.drawCenteredString(this.font, Component.literal(t), this.width / 2, y, 0xBBBBBB);
    }

    
        /**
     * 【1.21.1 图层修复】1.21.1 的 Screen.render() 开头会自动调 renderBackground
     * （游戏内=全屏模糊+菜单底纹），把 render() 先画好的自定义背景与文字再盖一层
     * （实机截图实证：说明文字/按钮文字发暗、渐变色带错乱）。重写为空 →
     * super.render() 内部的回调变 no-op，背景只由本类 render() 开头显式画一次
     * （1.20.1 语义：游戏内半透明黑渐变 / 主菜单全景）。
     */
    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 【1.21.1 图层修复】1.20.1 语义：游戏内只画半透明黑渐变；主菜单画全景。
        // 不能用 1.21.1 默认 renderBackground（模糊+菜单底纹会盖住后画内容）
        if (this.minecraft != null && this.minecraft.level != null) {
            this.renderTransparentBackground(graphics);
        } else {
            super.renderBackground(graphics, 0, 0, 0);
        }
        // v1.1.0 实测三百一十七（反馈："UI 美化仅更改了手册第一主界面，其他子界面
        // 一点都没变"）：Promaid 详细介绍（手册子界面）补上蓝金品牌渐变——与手册
        // 主界面同款（半透明色带叠加 = 渐变，fill 走 ARGB）
        int w = this.width;
        int h = this.height;
        int bandL = Math.max(4, w / 2 - 300);
        int bandR = Math.min(w - 4, w / 2 + 300);
        graphics.fill(bandL, 4, bandR, h - 4, 0x55122A4E);   // 底层：深海军蓝
        graphics.fill(bandL, 4, bandR, h - 4, 0x220F3A8C);   // 中层：宝蓝
        graphics.fill(bandL, 4, bandR, h - 4, 0x1A1B4E8C);   // 高光：亮蓝
        graphics.fill(bandL, 4, bandR, 14, 0xFF2C5F9E);      // 顶部饰条：靛蓝
        graphics.fill(bandL, 4 + 10, bandR, 14 + 1, 0x80D4A017); // 金线
        int h2 = this.height;
        if (this.view == VIEW_READ) {
            com.maidsmart.guide.GuideContent.Chapter[] chs = com.maidsmart.guide.GuideContent.chapters();
            if (this.reading >= 0 && this.reading < chs.length) {
                this.drawCentered(graphics, "\u00a7e" + com.maidsmart.i18n.PromaidI18n.tr(
                        chs[this.reading].title), 36, 0xFFFFFF);
                this.renderLines(graphics);
            }
        } else {
            this.drawCentered(graphics, "\u00a7ePromaid 详细介绍 · 章节目录", 36, 0xFFFFFF);
            // v1.5.252h：目录页不再显示章节总数说明（要求"不要再有字"）
        }
        // 页码（v1.1.0 实测二十五：画在两箭头中间 h-26 行——箭头 20px 在两侧，
        // 页码居中，任意文本长度不与按钮重叠）；设置页无分页，不画
        int totalPages = this.currentPages();
        if (totalPages > 1) {
            int page = this.view == VIEW_READ ? this.readPage : this.chapterPage;
            // 页码是拼出来的（"第 " + 数字 + " 页"），整串交 compose 逐段换，空格才对
            this.drawCentered(graphics, com.maidsmart.i18n.PromaidI18n.compose(
                            "\u00a77第 " + (page + 1) + "/" + totalPages + " 页"),
                    h - 26, 0xAAAAAA);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 实测八百一十三：按**累计像素高**切页——返回每页的元素区间 {@code {from, to}}。
     *
     * <p>为什么不再按"每页几行"：合成表元素高 {@code Recipe.height()}（约 80px），
     * 比一行 10px 高一个数量级。仍按行数切，一张合成表就会被页边界劈开、下半截跑到下一页。
     * 这里的规则是：本页放不下这个元素就从**下一页**开始放（元素本身不拆）——
     * 只有当一个元素自身就高于整页时才允许它独占一页溢出（不会死循环）。
     */
    private java.util.List<int[]> pageRanges(int contentBottom) {
        java.util.List<int[]> pages = new ArrayList<>();
        int avail = Math.max(LINE_H, contentBottom - CONTENT_TOP);
        int i = 0;
        int n = this.elements.size();
        if (n == 0) {
            pages.add(new int[]{0, 0});
            return pages;
        }
        while (i < n) {
            int start = i;
            int used = 0;
            while (i < n) {
                int hh = elementHeight(this.elements.get(i));
                if (used + hh > avail && i > start) {
                    break;
                }
                used += hh;
                i++;
                if (used >= avail) {
                    break;
                }
            }
            pages.add(new int[]{start, i});
        }
        return pages;
    }

    /** 元素像素高：字符串行 = {@link #LINE_H}；合成表 = 它自己算的高度。 */
    private static int elementHeight(Object el) {
        if (el instanceof Recipe r) {
            return r.height();
        }
        return LINE_H;
    }

    /** 当前视图总页数 */
    private int currentPages() {
        int h = this.height;
        if (this.view == VIEW_READ) {
            return this.pageRanges(h - 56).size();
        }
        int rowH = h < 238 ? (h < 214 ? (h < 188 ? 16 : 18) : 21) : 24;
        int perPage = Math.max(3, (h - 56 - CONTENT_TOP) / rowH);
        int n = com.maidsmart.guide.GuideContent.chapters().length;
        return Math.max(1, (n + perPage - 1) / perPage);
    }

    /** 正文渲染（行居中；链接单独着色 + 记命中区；合成表整块画）。 */
    private void renderLines(net.minecraft.client.gui.GuiGraphics graphics) {
        java.util.List<int[]> pages = this.pageRanges(this.height - 56);
        int pi = Math.min(this.readPage, pages.size() - 1);
        int[] range = pages.get(pi);
        this.linkBoxes.clear();
        this.linkTargets.clear();
        int y = CONTENT_TOP;
        for (int i = range[0]; i < range[1]; i++) {
            Object el = this.elements.get(i);
            if (el instanceof Recipe r) {
                this.drawRecipe(graphics, r, y);
                y += r.height();
            } else {
                RLine line = (RLine) el;
                if (!line.segs.isEmpty()) {
                    this.drawRLine(graphics, line, y);
                }
                y += LINE_H;
            }
        }
    }

    /**
     * 渲染一行（实测八百一十三：文字与图标混排；整行**居中**——图标行单靠左对齐会显得很偏）。
     */
    private void drawRLine(net.minecraft.client.gui.GuiGraphics graphics, RLine line, int y) {
        int total = lineWidth(line);
        int x = Math.max(12, (this.width - total) / 2);
        for (Seg s : line.segs) {
            if (s.iconId != null) {
                ItemStack st = GuideIcons.stack(s.iconId);
                if (st.isEmpty()) {
                    // 认不出的 id：画一个占位方框，作者一眼能看出写错了
                    graphics.fill(x, y, x + ICON, y + ICON, 0x40FF5555);
                } else {
                    graphics.renderItem(st, x, y - 1);
                }
                x += ICON + ICON_GAP;
            } else {
                // 文字片段里的 [[@目标|文字]] 链接：单独着色 + 记命中区
                this.drawTextWithLinks(graphics, s.text, x, y);
                x += this.font.width(stripLinksForWidth(s.text));
            }
        }
    }

    /** 一行文字的像素宽：链接按**标签**宽度算，不是 token 原文宽度。 */
    private String stripLinksForWidth(String text) {
        if (text == null || text.indexOf(LINK_OPEN) < 0) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf(LINK_OPEN, i);
            if (open < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, open);
            int close = text.indexOf(LINK_CLOSE, open);
            if (close < 0) {
                sb.append(text, open, text.length());
                break;
            }
            sb.append(linkLabel(text.substring(open, close + LINK_CLOSE.length())));
            i = close + LINK_CLOSE.length();
        }
        return sb.toString();
    }

    /** 渲染一段文字，其中的 {@code [[@…]]} 链接用青色下划线并记录命中区。 */
    private void drawTextWithLinks(net.minecraft.client.gui.GuiGraphics graphics, String text, int x, int y) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            int open = text.indexOf(LINK_OPEN, i);
            if (open < 0) {
                graphics.drawString(this.font, Component.literal(text.substring(i)), x, y, 0xDDDDDD, false);
                break;
            }
            if (open > i) {
                String plain = text.substring(i, open);
                graphics.drawString(this.font, Component.literal(plain), x, y, 0xDDDDDD, false);
                x += this.font.width(plain);
            }
            int close = text.indexOf(LINK_CLOSE, open);
            if (close < 0) {
                graphics.drawString(this.font, Component.literal(text.substring(open)), x, y, 0xDDDDDD, false);
                break;
            }
            String token = text.substring(open, close + LINK_CLOSE.length());
            String label = linkLabel(token);
            int lw = this.font.width(label);
            graphics.drawString(this.font, Component.literal("\u00a7b\u00a7n" + label), x, y, 0x55FFFF, false);
            this.linkBoxes.add(new int[]{x, y, x + lw, y + LINE_H});
            this.linkTargets.add(linkTarget(token));
            x += lw;
            i = close + LINK_CLOSE.length();
        }
    }

    /**
     * 整块合成表（实测八百一十三）。
     *
     * <p>画法：深色底 + 每个格子一个凹槽，物品图标按格子画；右侧接一个箭头和产物图标；
     * 最下面一行是说明文字（材料清单 / 备注）。整块**居中**。
     */
    private void drawRecipe(net.minecraft.client.gui.GuiGraphics graphics, Recipe r, int y) {
        int gridW = r.cols * (CELL + 2) + 2;
        int gridH = r.rows * (CELL + 2) + 2;
        int arrowW = 16;
        int resultW = CELL + 4;
        int textW = 0;
        if (r.note != null && !r.note.isEmpty()) {
            textW = this.font.width(r.note);
        }
        int blockW = Math.max(gridW + arrowW + resultW, textW);
        int x0 = Math.max(8, (this.width - blockW) / 2);
        if (x0 + blockW > this.width - 8) {
            x0 = Math.max(8, this.width - 8 - blockW);
        }
        int gy = y + 3;
        // 底
        graphics.fill(x0 - 4, gy - 3, x0 + blockW + 4, gy + gridH + 15, 0x66101018);
        // 格子
        for (int row = 0; row < r.rows; row++) {
            for (int col = 0; col < r.cols; col++) {
                int cx = x0 + col * (CELL + 2);
                int cy = gy + row * (CELL + 2);
                graphics.fill(cx, cy, cx + CELL, cy + CELL, 0x40FFFFFF);
                graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0x80000000);
                Cell c = r.grid[row] != null && col < r.grid[row].length ? r.grid[row][col] : null;
                if (c != null && c.id != null) {
                    ItemStack st = GuideIcons.stack(c.id);
                    if (!st.isEmpty()) {
                        graphics.renderItem(st, cx + 1, cy + 1);
                    }
                }
            }
        }
        // 箭头 + 产物
        int ax = x0 + gridW + 2;
        String arrow = r.shaped ? "\u00a77=>" : "\u00a77->";
        graphics.drawString(this.font, Component.literal(arrow), ax, gy + gridH / 2 - 4, 0xAAAAAA, false);
        int rx = ax + arrowW;
        graphics.fill(rx, gy + (gridH - CELL) / 2, rx + CELL, gy + (gridH - CELL) / 2 + CELL, 0x40FFFFFF);
        ItemStack out = GuideIcons.stack(r.resultId);
        if (!out.isEmpty()) {
            graphics.renderItem(out, rx + 1, gy + (gridH - CELL) / 2 + 1);
            graphics.drawString(this.font, Component.literal("\u00a7f" + GuideIcons.name(r.resultId)),
                    rx + CELL + 4, gy + (gridH - CELL) / 2 + 5, 0xFFFFFF, false);
        } else {
            graphics.drawString(this.font, Component.literal("\u00a7c?" + r.resultId),
                    rx + CELL + 4, gy + (gridH - CELL) / 2 + 5, 0xFF5555, false);
        }
        // 说明行
        if (r.note != null && !r.note.isEmpty()) {
            String note = com.maidsmart.i18n.PromaidI18n.tr(r.note);
            graphics.drawString(this.font, Component.literal("\u00a77" + note),
                    x0, gy + gridH + 3, 0x999999, false);
        }
    }

        @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 实测四百二十四：先判链接命中（最近一次渲染记下的框）
        if (button == 0 && this.view == VIEW_READ) {
            for (int i = 0; i < this.linkBoxes.size(); i++) {
                int[] bx = this.linkBoxes.get(i);
                if (mouseX >= bx[0] && mouseX <= bx[2] && mouseY >= bx[1] && mouseY <= bx[3]) {
                    this.openConfigLink(this.linkTargets.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        super.onClose();
        Minecraft.getInstance().setScreen(this.parent);
    }
}
