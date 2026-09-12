package com.maidsmart.guide;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    // 实测四百二十三【已停用】：手册内的两个开关页入口已移除——用户要求
    // 「所有开关统一扔进模组详细配置界面，不得出现在其它地方」。下面两个常量与
    // 对应的 settingsButtons/voiceButtons/commit* 方法已不可达（保留仅为回滚方便，
    // 不再有任何按钮把 view 设为它们）。参数现在只在配置面板：
    //   自动复活/回魂符 → 模组详细配置 · 生存与复活 · 死亡与复活
    //   内置日语语音包   → 模组详细配置 · 语音与显示 · 语音与 TTS
    @Deprecated
    private static final int VIEW_SETTINGS = 2;
    @Deprecated
    private static final int VIEW_VOICE = 3;

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
    /** 实测四百二十四：手册内可点击的配置链接——[[@大类:小类:行标签|显示文字]]。 */
    private static final String LINK_OPEN = "[[@";
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

    /** 点击链接：打开模组详细配置并定位到对应大类/小类/参数行。 */
    private void openConfigLink(String target) {
        String[] parts = target.split(":", 3);
        com.maidsmart.config.PromaidConfigScreen.openAt(this,
                parts.length > 0 ? parts[0] : "",
                parts.length > 1 ? parts[1] : "",
                parts.length > 2 ? parts[2] : null);
    }
    /** 更新日志行（资源文件，一次加载） */
    private static String[] changelogLines = null;
    /** 更新日志章节下标（chapters() 里动态构造，防静态顺序漂移） */
    private static int changelogIndex = -1;

    /**
     * 设置页输入状态（延迟提交，照 PromaidConfigScreen 的做法：输入时只记文本、
     * 不写配置——旧版实测每按键调 ForgeConfigSpec.set() 会让输入卡住）；离开
     * 设置页/关闭界面时统一写入 + SPEC.save()。
     * key: "delay"=复活延迟秒, "ratio"=复活血量比
     */
    private final java.util.Map<String, String> settingsPending = new java.util.HashMap<>();
    /** 自跟踪焦点（同配置面板：1.20.1 键盘链走 m_5534_/m_7933_ 直接转发） */
    private EditBox activeBox = null;

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
        this.activeBox = null; // 控件重建后旧焦点作废（同配置面板）
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        if (this.view == VIEW_SETTINGS) {
            this.settingsButtons(w, h, cx);
        } else if (this.view == VIEW_VOICE) {
            this.voiceButtons(w, h, cx);
        } else if (this.view == VIEW_READ) {
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
        // 实测四百二十三：手册内的开关页（⚙ 自动复活设置 / ♪ 日语语音包设置）已移除——
        // 用户要求「所有开关统一扔进模组详细配置界面，不得出现在其它地方」。
        // 对应参数现在只在 模组详细配置 里调整：
        //   自动复活 / 回魂符 → 生存与复活 · 死亡与复活
        //   内置日语语音包     → 语音与显示 · 语音与 TTS
    }

    // ================= 自动复活设置 =================

    /**
     * 设置页布局（控件与渲染共用同一套坐标，防两处算法漂移）：
     * 返回 {开关y, 延迟y, 血量y, 保存按钮y, 说明文字y}。
     * 行高按窗口高自适应压缩，任意常见窗口不越界（照配置面板目录页模式）。
     */
    private int[] settingsLayout() {
        int h = this.f_96544_;
        int rowH = h < 200 ? 26 : 32;
        int top = Math.max(56, Math.min(88, h - rowH * 3 - 70));
        int switchY = top;
        int delayY = top + rowH;
        int ratioY = top + rowH * 2;
        int saveY = top + rowH * 3 + 8;
        int noteY = saveY + 30;
        return new int[]{switchY, delayY, ratioY, saveY, noteY};
    }

    /**
     * 自动复活设置页（用户："自动复活功能应该在手册里面也能够调整 CD 和开关"）。
     * 手册里直接改，不用跳到配置面板：总开关即时写入（同配置面板 BoolRow），
     * 两个数字框延迟到离开本页时统一写（照配置面板：输入路径零配置写入，
     * 旧版每按键 set() 会卡输入），写完 SPEC.save() 落盘。
     */
    private void settingsButtons(int w, int h, int cx) {
        int labelW = 130;
        int fieldW = 120;
        int left = cx - (labelW + 8 + fieldW) / 2;
        int fieldX = left + labelW + 8;
        int[] lay = this.settingsLayout();

        // ① 总开关（标签画在左侧，按钮本身只显示"开/关"）
        final boolean enabled = com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.get();
        this.m_142416_(Button.m_253074_(Component.m_237113_(enabled ? "\u00a7a开" : "\u00a77关"),
                        b -> {
                            com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_ENABLE.set(!enabled);
                            this.m_7856_(); // 重建以刷新按钮文字
                        })
                .m_252987_(fieldX, lay[0], fieldW, 20).m_253136_());

        // ② 复活延迟（秒）
        this.m_142416_(this.settingsBox("delay", fieldX, lay[1], fieldW,
                String.valueOf(com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.get())));

        // ③ 复活血量比
        this.m_142416_(this.settingsBox("ratio", fieldX, lay[2], fieldW,
                String.valueOf(com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.get())));

        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存并返回目录"),
                        b -> {
                            this.commitSettings();
                            this.view = VIEW_CHAPTERS;
                            this.m_7856_();
                        })
                .m_252987_(cx - 70, lay[3], 140, 20).m_253136_());
        // 左上角返回（同样提交）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回"),
                        b -> {
                            this.commitSettings();
                            this.view = VIEW_CHAPTERS;
                            this.m_7856_();
                        })
                .m_252987_(8, 8, 70, 16).m_253136_());
    }

    private EditBox settingsBox(String key, int x, int y, int w, String initial) {
        EditBox box = new EditBox(this.f_96547_, x, y, w, 20, Component.m_237113_(key));
        box.m_94199_(16);
        String pending = this.settingsPending.get(key);
        box.m_94144_(pending != null ? pending : initial);
        box.m_94151_(s -> {
            this.settingsPending.put(key, s);
            box.m_94202_(isNumberText(s) ? 0xFFFFFF : 0xFFFF5555);
        });
        return box;
    }

    // ================= 内置日语语音包设置 =================

    /**
     * v1.1.0 实测四百二十：内置日语语音包设置页（用户："可以在手册里调整开关和音量大小
     * 以及最小间隔"）。开关即时写；音量/最小间隔延迟到离开本页统一写（同复活设置页）。
     * 布局与复活设置页共用 settingsLayout，行数相同（开关 + 2 数字 + 保存）。
     */
    private void voiceButtons(int w, int h, int cx) {
        int labelW = 150;
        int fieldW = 120;
        int left = cx - (labelW + 8 + fieldW) / 2;
        int fieldX = left + labelW + 8;
        int[] lay = this.settingsLayout();

        // ① 总开关（即时写入）
        final boolean enabled = com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_ENABLED.get();
        this.m_142416_(Button.m_253074_(Component.m_237113_(enabled ? "\u00a7a开" : "\u00a77关"),
                        b -> {
                            com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_ENABLED.set(!enabled);
                            this.m_7856_();
                        })
                .m_252987_(fieldX, lay[0], fieldW, 20).m_253136_());

        // ② 音量倍率
        this.m_142416_(this.settingsBox("voiceVol", fieldX, lay[1], fieldW,
                String.valueOf(com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_VOLUME.get())));

        // ③ 最小间隔（秒）
        this.m_142416_(this.settingsBox("voiceGap", fieldX, lay[2], fieldW,
                String.valueOf(com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_MIN_INTERVAL_S.get())));

        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存并返回目录"),
                        b -> {
                            this.commitVoiceSettings();
                            this.view = VIEW_CHAPTERS;
                            this.m_7856_();
                        })
                .m_252987_(cx - 70, lay[3], 140, 20).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回"),
                        b -> {
                            this.commitVoiceSettings();
                            this.view = VIEW_CHAPTERS;
                            this.m_7856_();
                        })
                .m_252987_(8, 8, 70, 16).m_253136_());
    }

    /** 语音设置页的待提交文本写入配置并落盘（越界钳制；非法/空跳过） */
    private void commitVoiceSettings() {
        String v = this.settingsPending.get("voiceVol");
        if (isNumberText(v)) {
            try {
                double d = Double.parseDouble(v.trim());
                d = Math.max(0.1, Math.min(5.0, d));
                com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_VOLUME.set(d);
            } catch (Exception ignored) {
            }
        }
        String g = this.settingsPending.get("voiceGap");
        if (isNumberText(g)) {
            try {
                long n = Math.round(Double.parseDouble(g.trim()));
                n = Math.max(0, Math.min(60, n));
                com.maidsmart.config.MaidSmartConfig.TTS_JAR_PACK_MIN_INTERVAL_S.set((int) n);
            } catch (Exception ignored) {
            }
        }
        try {
            com.maidsmart.config.MaidSmartConfig.SPEC.save();
        } catch (Exception ignored) {
        }
    }

    private static boolean isNumberText(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 待提交文本写入配置并落盘（非法/空文本跳过保留原值；越界钳制到声明范围） */
    private void commitSettings() {
        String d = this.settingsPending.get("delay");
        if (isNumberText(d)) {
            try {
                long v = Math.round(Double.parseDouble(d.trim()));
                v = Math.max(1, Math.min(86400, v));
                com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_DELAY_SECONDS.set((int) v);
            } catch (Exception ignored) {
            }
        }
        String r = this.settingsPending.get("ratio");
        if (isNumberText(r)) {
            try {
                double v = Double.parseDouble(r.trim());
                v = Math.max(0.05, Math.min(1.0, v));
                com.maidsmart.config.MaidSmartConfig.AUTO_RESURRECT_HEALTH_RATIO.set(v);
            } catch (Exception ignored) {
            }
        }
        try {
            com.maidsmart.config.MaidSmartConfig.SPEC.save();
        } catch (Exception ignored) {
        }
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
        // 实测四百二十四：每章正文末尾自动附一行可点击的「⚙ 配置入口」链接
        if (ch.paras.length > 0) {
            String cfgLink = com.maidsmart.guide.GuideContent.configLinkFor(ch.title);
            if (cfgLink != null) {
                out.add("");
                out.add("\u00a77\u2699 \u914d\u7f6e\u5165\u53e3\uff1a[[@" + cfgLink
                        + "|\u70b9\u6b64\u6253\u5f00\u5bf9\u5e94\u914d\u7f6e]]"
                        + "\uff08\u8df3\u8f6c\u540e\u76ee\u6807\u53c2\u6570\u884c\u4f1a\u9ad8\u4eae\uff09");
            }
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
    /**
     * 实测四百二十四：按像素宽度逐字符换行，同时把
     * [[@目标|文字]] 当【原子 token】——按标签宽度计量、整块不拆行；
     * 换行时延续 § 颜色样式。
     */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String active = "";
        int curW = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
                curW = 0;
                i++;
                continue;
            }
            if (ch == '[' && text.startsWith(LINK_OPEN, i)) {
                int close = text.indexOf(LINK_CLOSE, i);
                if (close > 0) {
                    String token = text.substring(i, close + LINK_CLOSE.length());
                    int tw = this.f_96547_.m_92895_(linkLabel(token));
                    if (curW + tw > maxWidth && cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                        cur.append(active);
                        curW = active.isEmpty() ? 0 : this.f_96547_.m_92895_(active);
                    }
                    cur.append(token);
                    curW += tw;
                    i = close + LINK_CLOSE.length();
                    continue;
                }
            }
            if (ch == '\u00a7' && i + 1 < n) {
                active = "\u00a7" + text.charAt(i + 1);
                cur.append(ch).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            int cw = this.f_96547_.m_92895_(String.valueOf(ch));
            if (curW + cw > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(active);
                curW = active.isEmpty() ? 0 : this.f_96547_.m_92895_(active);
            }
            cur.append(ch);
            curW += cw;
            i++;
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

    /** 左对齐标签（设置页行标签，右缘不超屏） */
    private void drawLabel(net.minecraft.client.gui.GuiGraphics graphics, String text, int x, int y) {
        graphics.m_280614_(this.f_96547_, Component.m_237113_(this.fitText(text, this.f_96543_ - x - 8)),
                x, y, 0xFFFFFF, false);
    }

    /** 居中说明文字（设置页底部注解，居中 + fitText 防出屏） */
    private void drawNote(net.minecraft.client.gui.GuiGraphics graphics, String text, int y) {
        String t = this.fitText(text, this.f_96543_ - 20);
        graphics.m_280653_(this.f_96547_, Component.m_237113_(t), this.f_96543_ / 2, y, 0xBBBBBB);
    }

    @Override
    public void m_88315_(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(graphics); // renderBackground
        // v1.1.0 实测三百一十七（用户："UI 美化仅更改了手册第一主界面，其他子界面
        // 一点都没变"）：Promaid 详细介绍（手册子界面）补上蓝金品牌渐变——与手册
        // 主界面同款（半透明色带叠加 = 渐变，m_280509_ 走 ARGB）
        int w = this.f_96543_;
        int h = this.f_96544_;
        int bandL = Math.max(4, w / 2 - 300);
        int bandR = Math.min(w - 4, w / 2 + 300);
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x55122A4E);   // 底层：深海军蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x220F3A8C);   // 中层：宝蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x1A1B4E8C);   // 高光：亮蓝
        graphics.m_280509_(bandL, 4, bandR, 14, 0xFF2C5F9E);      // 顶部饰条：靛蓝
        graphics.m_280509_(bandL, 4 + 10, bandR, 14 + 1, 0x80D4A017); // 金线
        int h2 = this.f_96544_;
        if (this.view == VIEW_SETTINGS) {
            this.drawCentered(graphics, "\u00a7e女仆自动复活 · 设置", 36, 0xFFFFFF);
            int labelW = 130;
            int fieldW = 120;
            int left = this.f_96543_ / 2 - (labelW + 8 + fieldW) / 2;
            int[] lay = this.settingsLayout();
            this.drawLabel(graphics, "\u00a7e女仆自动复活", left, lay[0] + 6);
            this.drawLabel(graphics, "\u00a7e复活延迟（秒）", left, lay[1] + 6);
            this.drawLabel(graphics, "\u00a7e复活血量比", left, lay[2] + 6);
            // 说明文字：窗口太矮时按可用高度逐行取舍（防压出屏/压住保存按钮）
            int ny = lay[4];
            String[] notes = {
                    "死后墓碑到期自动消失，女仆在主人重生点复活（关掉 = TLM 原版死亡流程）",
                    "复活延迟：墓碑存在多久才自动消失并复活，默认 60（范围 1~86400）",
                    "复活血量比：复活时恢复的血量比例，1.0 = 满血，0.35 = 35%（范围 0.05~1.0）",
                    "\u00a77填完按「保存并返回目录」；非法/空文本跳过并保留原值。",
                    "\u00a77同一组参数也在「模组详细配置 → 战斗自保 → 女仆自动复活」。",
            };
            int room = (this.f_96544_ - 6 - ny) / 12;
            for (int i = 0; i < notes.length; i++) {
                if (i >= room) {
                    break;
                }
                // 第 4/5 条是补充说明，放不下时优先丢它们
                if (i >= 3 && room < 5) {
                    break;
                }
                this.drawNote(graphics, notes[i], ny + i * 12);
            }
        } else if (this.view == VIEW_VOICE) {
            this.drawCentered(graphics, "\u00a7e内置日语语音包 · 设置", 36, 0xFFFFFF);
            int labelW = 150;
            int fieldW = 120;
            int left = this.f_96543_ / 2 - (labelW + 8 + fieldW) / 2;
            int[] lay = this.settingsLayout();
            this.drawLabel(graphics, "\u00a7e启用内置语音包", left, lay[0] + 6);
            this.drawLabel(graphics, "\u00a7e音量倍率", left, lay[1] + 6);
            this.drawLabel(graphics, "\u00a7e最小间隔（秒）", left, lay[2] + 6);
            int ny = lay[4];
            String[] notes = {
                    "触发系统消息时自动播放日语语音（115 条）；优先级高于 TLM 原生语音包",
                    "音量倍率：1.0 = 原始音量（范围 0.1~5.0），与 TTS 音量倍率相乘",
                    "最小间隔：同一女仆两次播放的最小间隔（秒，范围 0~60，默认 8）",
                    "\u00a77填完按「保存并返回目录」；非法/空文本跳过并保留原值。",
                    "\u00a77播放期间会暂压 TLM 原生语音包，播放完自动解除（可在配置面板关）。",
            };
            int room = (this.f_96544_ - 6 - ny) / 12;
            for (int i = 0; i < notes.length; i++) {
                if (i >= room) {
                    break;
                }
                if (i >= 3 && room < 5) {
                    break;
                }
                this.drawNote(graphics, notes[i], ny + i * 12);
            }
        } else if (this.view == VIEW_READ) {
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
        // 页码居中，任意文本长度不与按钮重叠）；设置页无分页，不画
        int totalPages = this.currentPages();
        if (totalPages > 1 && this.view != VIEW_SETTINGS && this.view != VIEW_VOICE) {
            int page = this.view == VIEW_READ ? this.readPage : this.chapterPage;
            this.drawCentered(graphics, "\u00a77第 " + (page + 1) + "/" + totalPages + " 页",
                    h - 26, 0xAAAAAA);
        }
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    /** 当前视图总页数 */
    private int currentPages() {
        int h = this.f_96544_;
        if (this.view == VIEW_SETTINGS || this.view == VIEW_VOICE) {
            return 1; // 设置页无分页
        }
        if (this.view == VIEW_READ) {
            int perPage = Math.max(5, (h - 56 - CONTENT_TOP) / LINE_H);
            return Math.max(1, (this.lines.size() + perPage - 1) / perPage);
        }
        int rowH = h < 238 ? (h < 214 ? (h < 188 ? 16 : 18) : 21) : 24;
        int perPage = Math.max(3, (h - 56 - CONTENT_TOP) / rowH);
        int n = com.maidsmart.guide.GuideContent.chapters().length;
        return Math.max(1, (n + perPage - 1) / perPage);
    }

    /** 正文行渲染（左对齐 12px，右缘不超屏；链接单独着色 + 记命中区） */
    private void renderLines(net.minecraft.client.gui.GuiGraphics graphics) {
        int h = this.f_96544_;
        int perPage = Math.max(5, (h - 56 - CONTENT_TOP) / LINE_H);
        int start = this.readPage * perPage;
        int end = Math.min(this.lines.size(), start + perPage);
        this.linkBoxes.clear();
        this.linkTargets.clear();
        int y = CONTENT_TOP;
        for (int i = start; i < end; i++) {
            String line = this.lines.get(i);
            if (!line.isEmpty()) {
                this.drawLine(graphics, line, y);
            }
            y += LINE_H;
        }
    }

    /** 实测四百二十四：渲染一行，链接段用青色下划线并记录命中区 */
    private void drawLine(net.minecraft.client.gui.GuiGraphics graphics, String line, int y) {
        int x = 12;
        int i = 0;
        int n = line.length();
        while (i < n) {
            int open = line.indexOf(LINK_OPEN, i);
            if (open < 0) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_(line.substring(i)), x, y, 0xDDDDDD, false);
                break;
            }
            if (open > i) {
                String plain = line.substring(i, open);
                graphics.m_280614_(this.f_96547_, Component.m_237113_(plain), x, y, 0xDDDDDD, false);
                x += this.f_96547_.m_92895_(plain);
            }
            int close = line.indexOf(LINK_CLOSE, open);
            if (close < 0) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_(line.substring(open)), x, y, 0xDDDDDD, false);
                break;
            }
            String token = line.substring(open, close + LINK_CLOSE.length());
            String label = linkLabel(token);
            int lw = this.f_96547_.m_92895_(label);
            graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a7b\u00a7n" + label), x, y, 0x55FFFF, false);
            this.linkBoxes.add(new int[]{x, y, x + lw, y + LINE_H});
            this.linkTargets.add(linkTarget(token));
            x += lw;
            i = close + LINK_CLOSE.length();
        }
    }

    /**
     * 点击输入框立即聚焦（照 PromaidConfigScreen：容器事件顺序/命中区域差异会
     * 导致"点不进输入框"，这里显式 setFocused + 记录 activeBox，键盘输入再直接
     * 转发给它）。
     */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
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
        if (button == 0 && (this.view == VIEW_SETTINGS || this.view == VIEW_VOICE)) {
            for (net.minecraft.client.gui.components.events.GuiEventListener c : this.m_6702_()) {
                if (c instanceof EditBox eb && eb.m_5953_(mouseX, mouseY)) {
                    eb.m_93692_(true);
                    this.activeBox = eb;
                }
            }
        }
        return super.m_6375_(mouseX, mouseY, button);
    }

    @Override
    public boolean m_5534_(char codePoint, int modifiers) {
        if (this.activeBox != null && this.activeBox.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_5534_(codePoint, modifiers);
    }

    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) {
        if (this.activeBox != null && this.activeBox.m_7933_(key, scanCode, modifiers)) {
            return true;
        }
        return super.m_7933_(key, scanCode, modifiers);
    }

    @Override
    public void m_7379_() {
        // 关闭界面（含 ESC / 返回）时提交未保存的设置，避免编辑丢失
        this.commitSettings();
        this.commitVoiceSettings();
        super.m_7379_();
        Minecraft.m_91087_().m_91152_(this.parent);
    }
}
