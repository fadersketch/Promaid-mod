package com.maidsmart.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 游戏内文件浏览器（v1.0.4）——替代 java.awt.FileDialog。
 *
 * 背景：部分启动器/环境以 -Djava.awt.headless=true 运行游戏，AWT 系统文件对话框
 * 直接抛 HeadlessException（getMessage()=null，聊天框显示"打开文件选择器失败: null"）。
 * 本界面用纯 MC GUI + java.io.File 浏览文件系统，不依赖 AWT，任何环境都能用。
 *
 * 用法：FilePickScreen.open(parent, title, exts, path -> {...})
 * - 首页显示磁盘分区（Windows 盘符；Linux/macOS 为 /）
 * - 目录内：上级目录 + 子目录（▶）/ 匹配扩展名的文件（●）
 * - 点击文件即回调绝对路径并返回上一界面
 * 纯客户端，无网络。
 */
public class FilePickScreen extends Screen {
    private final Screen parent;
    private final String pickTitle;
    private final List<String> exts;
    private final Consumer<String> onPick;

    /** 当前目录；null = 磁盘分区选择页 */
    private File dir = null;
    private final List<File> entries = new ArrayList<>();
    private int page = 0;

    private static final int ROWS = 12;
    private static final int ROW_H = 18;

    public FilePickScreen(Screen parent, String title, String[] exts, Consumer<String> onPick) {
        super(Component.m_237113_("选择文件"));
        this.parent = parent;
        this.pickTitle = title;
        this.exts = new ArrayList<>(Arrays.asList(exts));
        this.onPick = onPick;
        reload();
    }

    public static void open(Screen parent, String title, String[] exts, Consumer<String> onPick) {
        Minecraft.m_91087_().m_91152_(new FilePickScreen(parent, title, exts, onPick));
    }

    private boolean isMatch(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String e = n.substring(dot + 1);
        for (String x : this.exts) {
            if (x.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private void reload() {
        this.entries.clear();
        this.page = 0;
        if (this.dir == null) {
            for (File r : File.listRoots()) {
                this.entries.add(r);
            }
            return;
        }
        File[] fs = this.dir.listFiles();
        if (fs == null) {
            return;
        }
        List<File> list = new ArrayList<>();
        for (File f : fs) {
            if (f.isDirectory()) {
                list.add(f);
            } else if (isMatch(f)) {
                list.add(f);
            }
        }
        list.sort(Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        this.entries.addAll(list);
    }

    private String fit(String text, int maxWidth) {
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

    private void drawCentered(net.minecraft.client.gui.GuiGraphics graphics, String text, int y, int color) {
        String t = this.fit(text, this.f_96543_ - 20);
        graphics.m_280653_(this.f_96547_, Component.m_237113_(t), this.f_96543_ / 2, y, color);
    }

    @Override
    protected void m_7856_() {
        this.m_169413_(); // clearWidgets
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        // 左上角：返回手册
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回手册"),
                        b -> this.m_7379_())
                .m_252987_(8, 8, 80, 16).m_253136_());
        if (this.dir == null) {
            // 磁盘分区页
            int y = 70;
            for (File r : this.entries) {
                final File f = r;
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e\u25b6 进入 " + f.getPath()),
                                b -> {
                                    this.dir = f;
                                    this.reload();
                                    this.m_7856_();
                                })
                        .m_252987_(cx - 150, y, 300, 20).m_253136_());
                y += 24;
            }
            return;
        }
        // 上级目录
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77.. 上级目录"),
                        b -> {
                            this.dir = this.dir.getParentFile();
                            this.reload();
                            this.m_7856_();
                        })
                .m_252987_(cx - 150, 58, 300, 16).m_253136_());
        // 条目（分页）
        int totalPages = Math.max(1, (this.entries.size() + ROWS - 1) / ROWS);
        this.page = Math.min(this.page, totalPages - 1);
        int start = this.page * ROWS;
        int end = Math.min(this.entries.size(), start + ROWS);
        int y = 80;
        for (int i = start; i < end; i++) {
            final File f = this.entries.get(i);
            String label = f.isDirectory()
                    ? "\u00a7e\u25b6 " + f.getName() + "/"
                    : "\u00a7a\u25c9 " + f.getName();
            this.m_142416_(Button.m_253074_(Component.m_237113_(this.fit(label, w - 90)),
                            b -> {
                                if (f.isDirectory()) {
                                    this.dir = f;
                                    this.reload();
                                    this.m_7856_();
                                } else {
                                    this.onPick.accept(f.getAbsolutePath());
                                    this.m_7379_();
                                }
                            })
                    .m_252987_(cx - 150, y, 300, ROW_H).m_253136_());
            y += ROW_H + 2;
        }
        if (this.entries.isEmpty()) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77此目录没有匹配的文件"),
                            b -> {
                            })
                    .m_252987_(cx - 150, y, 300, 18).m_253136_());
        }
        // 翻页（v1.1.0 实测二十五：100 宽按钮盖底行文件名——改 20 宽纯箭头，页码画中间）
        if (totalPages > 1) {
            int py = h - 28;
            if (this.page > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.page--;
                                    this.m_7856_();
                                }).m_252987_(cx - 40, py, 20, 18).m_253136_());
            }
            if (this.page < totalPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.page++;
                                    this.m_7856_();
                                }).m_252987_(cx + 20, py, 20, 18).m_253136_());
            }
        }
    }

    @Override
    public void m_88315_(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(graphics); // renderBackground
        // v1.1.0 实测三百一十七：蓝图文件选择（手册子界面）补上蓝金品牌渐变——与手册同款
        int w = this.f_96543_;
        int h = this.f_96544_;
        int bandL = Math.max(4, w / 2 - 300);
        int bandR = Math.min(w - 4, w / 2 + 300);
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x55122A4E);   // 底层：深海军蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x220F3A8C);   // 中层：宝蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x1A1B4E8C);   // 高光：亮蓝
        graphics.m_280509_(bandL, 4, bandR, 14, 0xFF2C5F9E);      // 顶部饰条：靛蓝
        graphics.m_280509_(bandL, 4 + 10, bandR, 14 + 1, 0x80D4A017); // 金线
        this.drawCentered(graphics, "\u00a7e" + this.pickTitle, 26, 0xFFFFFF);
        String pathText = this.dir == null ? "选择磁盘分区" : this.dir.getAbsolutePath();
        this.drawCentered(graphics, "\u00a77" + this.fit(pathText, this.f_96543_ - 20), 42, 0x888888);
        int totalPages = Math.max(1, (this.entries.size() + ROWS - 1) / ROWS);
        if (totalPages > 1) {
            // v1.1.0 实测二十五：页码画在两箭头中间（h-24 行），不与按钮/条目重叠
            this.drawCentered(graphics, "\u00a77第 " + (this.page + 1) + "/" + totalPages + " 页",
                    this.f_96544_ - 24, 0xAAAAAA);
        }
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void m_7379_() {
        super.m_7379_();
        Minecraft.m_91087_().m_91152_(this.parent);
    }
}
