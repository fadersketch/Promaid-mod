package com.maidsmart.schedule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 排班表界面（v1.1.0）——纸+墨囊合成的「排班表」物品右键打开。
 *
 * v1.1.0 实测三十三【整体重写】（用户："排班表的 UI bug 还是一大堆，需要重做"）：
 * 旧版结构在多轮补丁后状态失控（tab 与排班开关重叠、日程行数字框焦点链脆弱、
 * 排版参数多处复制粘贴口径不一）。本次按以下原则重写：
 * - **单一布局函数**：所有控件的 x/y 只在一个方法里计算，渲染层的静态文字
 *   （冒号/波浪线/说明）复用同一组常量——彻底消灭"两处各算各的、改一处漏一处"。
 * - **固定栏式布局**：顶部标题(8..24) / tab(28..46) / 内容区(52..h-56) /
 *   底部按钮区(h-52..h-8)。任何分辨率下各区不重叠；内容行数按内容区高度收敛。
 * - **行内编辑改单框**：时间段一行只用【一个】输入框（"H:MM~H:MM" 自由输入，
 *   保存时校验），替代旧版 6 个单字符数字框——数字框焦点/切换/重建在多行场景
 *   极易失焦丢字，单框是 Minecraft 原版配置界面的通用做法，稳定得多。
 * - 任务/模式仍是循环按钮（点一下换下一个）。
 * 数据结构与网络协议不变（rows 槽位 0=时间文本 1=模式 2=任务UID；3..8 弃用）。
 */
public class ScheduleBookScreen extends Screen {
    private static final int VIEW_LIST = 0;
    private static final int VIEW_DETAIL = 1;

    /** 打开包数据：{uuid, 名字, 任务UID, 工作模式, 排班开, 段数} */
    private final List<String[]> maids;
    /** 可选任务清单（uid） */
    private final List<String> taskUids;
    private final Screen parent;

    private int view = VIEW_LIST;
    private int page = 0;
    /** 详情页 tab：0=快捷设置 1=日程设置 */
    private int tab = 0;
    private String selUuid;
    private String selName;
    /** 日程行编辑态（客户端）：{时间文本 "H:MM~H:MM", 工作模式(int), 任务UID} */
    private final List<Object[]> rows = new ArrayList<>();
    /** 日程请求中（等 SchedDataPacket） */
    private boolean waiting = false;
    private boolean loadedOn = false;
    private static ScheduleBookScreen instance;

    /** 键盘输入转发的激活输入框（时间编辑框） */
    private EditBox activeBox;

    private static final String[] MODE_NAMES = {"早班", "晚班", "全天"};

    /* ==================== 布局常量（实测三十三：全界面唯一定义处） ==================== */
    private static final int TOP_TITLE_Y = 8;      // 标题行
    private static final int TAB_Y = 28;           // tab 行（详情页）
    private static final int CONTENT_TOP = 52;     // 内容区顶
    private static final int CONTENT_BOTTOM_PAD = 56; // 底部按钮区高度预留
    private static final int FOOT_Y = -52;         // 底部区 y（相对 h，负数=从底往上）
    private static final int ROW_H = 24;           // 内容行高
    private static final int LIST_ROW_H = 22;      // 列表行高
    /** 日程行控件宽度：时间框 92 + 模式 60 + 任务 130 + 删 20 + 间隔 4×3 */
    private static final int TIME_W = 92;
    private static final int MODE_W = 60;
    private static final int TASK_W = 130;
    private static final int DEL_W = 20;
    private static final int SCHED_ROW_W = TIME_W + MODE_W + TASK_W + DEL_W + 12;

    public static void open(List<String[]> maids, List<String> taskUids) {
        instance = new ScheduleBookScreen(null, maids, taskUids);
        Minecraft.m_91087_().m_91152_(instance);
    }

    /** SchedDataPacket 到达：更新当前打开界面的日程行 */
    public static void showSchedule(String uuid, boolean on, List<ScheduleData.Segment> segments) {
        ScheduleBookScreen cur = instance;
        if (cur == null || !uuid.equals(cur.selUuid)) {
            return;
        }
        cur.waiting = false;
        cur.loadedOn = on;
        cur.rows.clear();
        if (segments.isEmpty()) {
            // 默认模板：一整段全天当前任务（用户在此基础上切分）
            String curTask = "";
            for (String[] m : cur.maids) {
                if (m[0].equals(uuid)) {
                    curTask = m[2];
                    break;
                }
            }
            cur.rows.add(new Object[]{"0:00~24:00", 2, curTask});
        } else {
            for (ScheduleData.Segment s : segments) {
                cur.rows.add(new Object[]{ScheduleData.fmt(s.startMin()) + "~" + ScheduleData.fmt(s.endMin()),
                        s.mode(), s.taskUid()});
            }
        }
        cur.m_7856_();
    }

    private ScheduleBookScreen(Screen parent, List<String[]> maids, List<String> taskUids) {
        super(Component.m_237113_("排班表"));
        this.parent = parent;
        this.maids = new ArrayList<>(maids);
        this.taskUids = new ArrayList<>(taskUids);
    }

    @Override
    public void m_7856_() {
        this.m_169413_(); // clearWidgets
        this.activeBox = null;
        int w = this.f_96543_;
        int h = this.f_96544_;
        if (this.view == VIEW_LIST) {
            this.listButtons(w, h);
        } else {
            this.detailButtons(w, h);
        }
    }

    /* ==================== 女仆列表页 ==================== */

    private void listButtons(int w, int h) {
        int cx = w / 2;
        int bw = Math.min(300, w - 16);
        // 行数按内容区高度收敛（顶部 44 起排，底部 FOOT 区之上）
        int avail = h - 56 - 44;
        int rowsPerPage = Math.max(1, Math.min(8, avail / LIST_ROW_H));
        int totalPages = Math.max(1, (this.maids.size() + rowsPerPage - 1) / rowsPerPage);
        this.page = Math.min(this.page, totalPages - 1);
        int start = this.page * rowsPerPage;
        int end = Math.min(this.maids.size(), start + rowsPerPage);
        int y = 44;
        for (int i = start; i < end; i++) {
            String[] m = this.maids.get(i);
            String sched = "1".equals(m[4])
                    ? "\u00a7a排班开\u00a77（" + m[5] + " 段）" : "\u00a77排班关";
            String label = "\u00a7e" + m[1] + "\u00a7r  " + sched;
            final String uuid = m[0];
            final String name = m[1];
            this.m_142416_(Button.m_253074_(Component.m_237113_(label), b -> {
                        this.selUuid = uuid;
                        this.selName = name;
                        this.tab = 0;
                        this.view = VIEW_DETAIL;
                        this.waiting = true;
                        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.SchedLoadRequestPacket(uuid));
                        this.m_7856_();
                    })
                    .m_252987_(cx - bw / 2, y, bw, 20).m_253136_());
            y += LIST_ROW_H;
        }
        // 翻页（◀ 页码 ▶ 居中一行，位于底区）
        if (this.page > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"), b -> {
                        this.page--;
                        this.m_7856_();
                    })
                    .m_252987_(cx - 40, h - 46, 20, 18).m_253136_());
        }
        if (this.page < totalPages - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"), b -> {
                        this.page++;
                        this.m_7856_();
                    })
                    .m_252987_(cx + 20, h - 46, 20, 18).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c关闭"), b -> this.m_7379_())
                .m_252987_(cx - 50, h - 24, 100, 20).m_253136_());
    }

    /* ==================== 详情页 ==================== */

    private void detailButtons(int w, int h) {
        int cx = w / 2;
        // tab：左右并排居中（互不相交；窄屏收窄）
        String[] tabs = {"\u00a7e快捷设置", "\u00a7e日程设置"};
        int tabW = w >= 380 ? 150 : Math.max(80, (w - 40) / 2);
        int tabX0 = cx - tabW - 5;
        for (int i = 0; i < 2; i++) {
            final int ti = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.tab == ti ? "\u00a76\u25cf " : "\u00a77") + tabs[i]),
                            b -> {
                                this.tab = ti;
                                // 切到日程 tab 无条件重新拉最新数据（旧数据不残留）
                                if (ti == 1) {
                                    this.waiting = true;
                                    this.rows.clear();
                                    ScheduleNetworking.CHANNEL.sendToServer(
                                            new ScheduleNetworking.SchedLoadRequestPacket(this.selUuid));
                                }
                                this.m_7856_();
                            })
                    .m_252987_(tabX0 + i * (tabW + 10), TAB_Y, tabW, 18).m_253136_());
        }
        // 返回列表（左下角）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆列表"), b -> {
                    this.view = VIEW_LIST;
                    this.selUuid = null;
                    this.m_7856_();
                })
                .m_252987_(12, h - 24, 100, 20).m_253136_());
        if (this.tab == 0) {
            this.quickTab(w, h, cx);
        } else {
            this.schedTab(w, h, cx);
        }
    }

    /** 快捷设置：排班开关 / 工作模式 / 任务循环（点击立即生效）。
     *  实测三十三：三项竖排左对齐（x=cx-170 起，宽 260），行距 26——永不重叠。 */
    private void quickTab(int w, int h, int cx) {
        String[] sel = this.findSel();
        String curTask = sel != null ? sel[2] : "";
        int curMode = sel != null ? safeInt(sel[3], 2) : 2;
        boolean on = sel != null && "1".equals(sel[4]);
        int qx = Math.max(4, cx - 170);
        int qw = Math.min(300, w - qx - 4);
        int y = CONTENT_TOP + 6;
        // 排班开关
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班：开" : "\u00a77排班：关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            if (sel != null) {
                                sel[4] = on ? "0" : "1";
                            }
                            this.m_7856_();
                        })
                .m_252987_(qx, y, qw, 20).m_253136_());
        y += 26;
        // 工作模式（早班/晚班/全天 → TLM DAY/NIGHT/ALL）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("工作模式：\u00a7e" + MODE_NAMES[Math.max(0, Math.min(2, curMode))]
                                + " \u00a78(点击切换)"),
                        b -> {
                            int mode = (safeInt(sel != null ? sel[3] : null, 2) + 1) % 3;
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, mode, "", -1));
                            if (sel != null) {
                                sel[3] = String.valueOf(mode);
                            }
                            this.m_7856_();
                        })
                .m_252987_(qx, y, qw, 20).m_253136_());
        y += 26;
        // 任务循环（点一下换下一个；到头回绕）
        if (!this.taskUids.isEmpty()) {
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_("任务：\u00a7e" + taskCn(curTask) + " \u00a78(点击切换)"),
                            b -> {
                                int next = (this.taskUids.indexOf(curTask) + 1) % this.taskUids.size();
                                String uid = this.taskUids.get(next);
                                ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                        this.selUuid, -1, uid, -1));
                                if (sel != null) {
                                    sel[2] = uid;
                                }
                                this.m_7856_();
                            })
                    .m_252987_(qx, y, qw, 20).m_253136_());
        }
    }

    /**
     * 日程设置：分段行编辑 + 添加 + 保存。
     * 实测三十三重写：时间段单输入框（"H:MM~H:MM"），整行 [时间框|模式|任务|×]
     * 左对齐 x=left；行数按内容区高度收敛；添加/保存固定底区一行。
     */
    private void schedTab(int w, int h, int cx) {
        if (this.waiting) {
            return; // 渲染层显示"请求中…"
        }
        int left = Math.max(8, Math.min(cx - 160, w - SCHED_ROW_W - 8));
        // 排班开关（右上角，避开 tab 行）
        boolean on = this.loadedOn;
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班：开" : "\u00a77排班：关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            this.loadedOn = !on;
                            this.m_7856_();
                        })
                .m_252987_(Math.max(4, w - 120), TAB_Y, 90, 18).m_253136_());
        if (this.rows.isEmpty()) {
            // 空表也要能"添加分段"（否则删光了卡死在空页）
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a+ 添加分段"), b -> {
                        this.rows.add(new Object[]{"0:00~24:00", 2,
                                this.taskUids.isEmpty() ? "" : this.taskUids.get(0)});
                        this.m_7856_();
                    })
                    .m_252987_(left, CONTENT_TOP + 6, 120, 18).m_253136_());
            return;
        }
        // 行数收敛：内容区 52..h-56 内排 ROW_H 行
        int maxRows = Math.max(1, Math.min(10, Math.min(this.rows.size(), (h - CONTENT_BOTTOM_PAD - CONTENT_TOP) / ROW_H)));
        int y = CONTENT_TOP + 6;
        for (int i = 0; i < maxRows; i++) {
            final int idx = i;
            Object[] row = this.rows.get(i);
            int x = left;
            // 时间段单框（"H:MM~H:MM"）
            EditBox timeBox = new EditBox(this.f_96547_, x, y, TIME_W, 18,
                    Component.m_237113_("时间段"));
            timeBox.m_94199_(12);
            String timeText = String.valueOf(row[0]);
            timeBox.m_94144_(timeText);
            timeBox.m_94151_(s -> this.rows.get(idx)[0] = s);
            timeBox.m_94202_(0xFFFFFF);
            this.m_142416_(timeBox);
            if (this.activeBox == null) {
                this.activeBox = timeBox; // 默认聚焦第一行（键盘直接可输入）
                timeBox.m_93692_(true);
            }
            x += TIME_W + 4;
            // 工作模式循环
            int mode = safeIntObj(row[1], 2);
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(MODE_NAMES[Math.max(0, Math.min(2, mode))]),
                            b -> {
                                int m2 = (safeIntObj(this.rows.get(idx)[1], 2) + 1) % 3;
                                this.rows.get(idx)[1] = m2;
                                b.m_93666_(Component.m_237113_(MODE_NAMES[m2]));
                            })
                    .m_252987_(x, y, MODE_W, 18).m_253136_());
            x += MODE_W + 4;
            // 任务循环
            String uid = String.valueOf(row[2]);
            if (!this.taskUids.isEmpty()) {
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_(fitTask(uid)),
                                b -> {
                                    int next = (this.taskUids.indexOf(String.valueOf(this.rows.get(idx)[2])) + 1)
                                            % this.taskUids.size();
                                    this.rows.get(idx)[2] = this.taskUids.get(next);
                                    b.m_93666_(Component.m_237113_(fitTask(this.taskUids.get(next))));
                                })
                        .m_252987_(x, y, TASK_W, 18).m_253136_());
            }
            x += TASK_W + 4;
            // 删除
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c×"), b -> {
                        this.rows.remove(idx);
                        this.activeBox = null; // 行没了焦点框也清掉
                        this.m_7856_();
                    })
                    .m_252987_(x, y, DEL_W, 18).m_253136_());
            y += ROW_H;
        }
        // 添加分段（新段自动衔接上一段结束；无行则 0:00 起；到 24:00 隐藏）
        boolean full = this.lastRowEndMin() >= 1440;
        int footY = h - 52;
        if (!full) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a+ 添加分段"), b -> {
                        int startMin = 0;
                        Object[] last = this.rows.get(this.rows.size() - 1);
                        int[] se = parseRange(String.valueOf(last[0]));
                        startMin = se != null ? se[1] : 0;
                        this.rows.add(new Object[]{ScheduleData.fmt(startMin) + "~24:00", 2,
                                this.taskUids.isEmpty() ? "" : this.taskUids.get(0)});
                        this.m_7856_();
                    })
                    .m_252987_(left, footY, 120, 18).m_253136_());
        }
        // 保存（固定右下，与添加同排）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存日程"), b -> this.saveSchedule())
                .m_252987_(Math.min(left + 130, w - 104), footY, 100, 18).m_253136_());
        // 超出可视行数的提示（渲染层画）
    }

    /* ==================== 保存 ==================== */

    /** 保存日程：各行时间框解析 → 归一化 → 发包 */
    private void saveSchedule() {
        List<ScheduleData.Segment> segs = new ArrayList<>();
        for (Object[] row : this.rows) {
            int[] se = parseRange(String.valueOf(row[0]));
            if (se == null) {
                this.chat("\u00a7c时间段格式不对：每行形如 9:00~17:00（结束要晚于开始）");
                return;
            }
            if (se[0] >= 1440 || se[1] > 1440) {
                this.chat("\u00a7c时间超出一天：小时 0~23（结束可到 24）");
                return;
            }
            int mode = Math.max(0, Math.min(2, safeIntObj(row[1], 2)));
            segs.add(new ScheduleData.Segment(se[0], se[1], mode, String.valueOf(row[2])));
        }
        if (segs.isEmpty()) {
            this.chat("\u00a7c至少要有一段（点「+ 添加分段」）");
            return;
        }
        List<ScheduleData.Segment> norm = ScheduleData.normalize(segs);
        this.rows.clear();
        for (ScheduleData.Segment s : norm) {
            this.rows.add(new Object[]{ScheduleData.fmt(s.startMin()) + "~" + ScheduleData.fmt(s.endMin()),
                    s.mode(), s.taskUid()});
        }
        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.SchedSavePacket(
                this.selUuid, this.loadedOn, norm));
        this.chat("\u00a7a日程已保存（自动补满 24:00），跨段时间点会自动切换");
        this.m_7856_();
    }

    /* ==================== 渲染 ==================== */

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float pt) {
        this.m_280039_(g);
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        // 暗红背景 + 顶部饰条（附魔书紫红标题风格）
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                h - 8, 0xC0200808);
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                12, 0xFF8B1A1A);
        if (this.view == VIEW_LIST) {
            g.m_280653_(this.f_96547_, Component.m_237113_("\u00a7d\u00a7o排班表\u00a7r\u00a7c——选择女仆"), cx, TOP_TITLE_Y, 0xFFFFFF);
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进入设置：快捷切换工作模式/任务，或按游戏内时间排一天班"), cx, TOP_TITLE_Y + 12, 0xAAAAAA);
            int rowsPerPage = Math.max(1, Math.min(8, (h - 56 - 44) / LIST_ROW_H));
            int tp = Math.max(1, (this.maids.size() + rowsPerPage - 1) / rowsPerPage);
            if (tp > 1) {
                // 页码在 ◀ ▶ 中间（同实测二十五口径）
                g.m_280653_(this.f_96547_,
                        Component.m_237113_("\u00a77第 " + (this.page + 1) + "/" + tp + " 页"),
                        cx, h - 41, 0xAAAAAA);
            }
        } else {
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a7d\u00a7o" + this.selName + "\u00a7r\u00a7c 的排班"), cx, TOP_TITLE_Y, 0xFFFFFF);
            if (this.tab == 1) {
                // 日程页说明（顶部标题与 tab 之间的夹缝放不下——放内容区首行上方不行，
                // 放底区「← 女仆列表」同一行的右侧空档）
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77时间格式 H:MM~H:MM，保存自动补满 24:00"),
                        8, h - 8, 0xFFE5A0A0, false);
                if (this.waiting) {
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u00a77请求中…"), cx, CONTENT_TOP + 6, 0xAAAAAA);
                } else {
                    // 列头（浅色小字，在首行上方 8px——CONTENT_TOP 与首行 y=CONTENT_TOP+6 之间
                    // 不够，画在内容行区最左上角向右偏移的地方会与首行重叠；改为不画列头，
                    // 时间框自带 placeholder 已经足够）
                    // 超行提示：行数超可视区时提示还有几行没显示
                    int maxRows = Math.max(1, Math.min(10,
                            Math.min(this.rows.size(), (h - CONTENT_BOTTOM_PAD - CONTENT_TOP) / ROW_H)));
                    if (this.rows.size() > maxRows) {
                        g.m_280653_(this.f_96547_, Component.m_237113_(
                                        "\u00a77还有 " + (this.rows.size() - maxRows) + " 段未显示（调大窗口或删几段）"),
                                cx, h - 30, 0xFFE5A0A0);
                    }
                }
            } else {
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77点击立即生效；早班=白天工作、晚班=夜晚工作、全天=一直工作（TLM 日程）"),
                        Math.max(8, cx - 210), CONTENT_TOP + 6 + 26 * 3 + 6, 0xFFE5A0A0, false);
            }
        }
        super.m_88315_(g, mx, my, pt);
    }

    /* ==================== 工具 ==================== */

    private String[] findSel() {
        for (String[] m : this.maids) {
            if (m[0].equals(this.selUuid)) {
                return m;
            }
        }
        return null;
    }

    private static int safeInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int safeIntObj(Object o, int def) {
        if (o instanceof Integer i) {
            return i;
        }
        return safeInt(String.valueOf(o), def);
    }

    /** 任务 UID → 中文名（翻译键 task.<ns>.<path>；无翻译回退 path 段） */
    private static String taskCn(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "空闲";
        }
        int idx = uid.indexOf(':');
        if (idx < 0) {
            return uid;
        }
        String key = "task." + uid.substring(0, idx) + "." + uid.substring(idx + 1);
        String cn = Component.m_237113_(key).getString();
        if (!cn.equals(key)) {
            return cn;
        }
        return uid.substring(idx + 1);
    }

    /** 任务名截断到按钮宽度内（TASK_W ≈ 130px ≈ 13 个中文字符） */
    private static String fitTask(String uid) {
        String cn = taskCn(uid);
        return cn.length() > 10 ? cn.substring(0, 9) + "…" : cn;
    }

    /** "H:MM~H:MM" → {startMin, endMin}；非法返回 null（容忍 ～/—/-/空格） */
    private static int[] parseRange(String text) {
        String t = text.trim().replace("～", "~").replace("—", "~").replace("-", "~")
                .replace("－", "~").replace(" ", "");
        int idx = t.indexOf('~');
        if (idx < 0) {
            return null;
        }
        int s = ScheduleData.parseTime(t.substring(0, idx));
        int e = ScheduleData.parseTime(t.substring(idx + 1));
        if (s < 0 || e < 0 || e <= s) {
            return null;
        }
        return new int[]{s, e};
    }

    /** 末行结束分钟数（无行返回 0） */
    private int lastRowEndMin() {
        if (this.rows.isEmpty()) {
            return 0;
        }
        Object[] last = this.rows.get(this.rows.size() - 1);
        int[] se = parseRange(String.valueOf(last[0]));
        return se != null ? se[1] : 0;
    }

    private void chat(String msg) {
        if (this.f_96541_.f_91074_ != null) {
            this.f_96541_.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
        }
    }

    /* ==================== 键盘转发 ==================== */

    /** 点击输入框时记录 activeBox（键盘字符/按键直接转发，不依赖焦点链） */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (net.minecraft.client.gui.components.events.GuiEventListener c : this.m_6702_()) {
                if (c instanceof EditBox eb && eb.m_5953_(mouseX, mouseY)) {
                    eb.m_93692_(true); // setFocus
                    this.activeBox = eb;
                    return eb.m_6375_(mouseX, mouseY, 0);
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
        instance = null;
        if (this.parent != null) {
            Minecraft.m_91087_().m_91152_(this.parent);
        } else {
            super.m_7379_();
        }
    }
}
