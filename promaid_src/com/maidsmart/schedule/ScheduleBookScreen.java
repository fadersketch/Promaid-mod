package com.maidsmart.schedule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 排班表界面（v1.1.0）——纸+墨囊合成的「排班表」物品右键打开。
 * UI 仿 Promaid 手册（BlueprintBookScreen）：列表页 → 详情页两 tab。
 *
 * - 女仆列表：名字 + 当前任务 + 排班开关状态 + 段数，分页
 * - 快捷设置：工作模式（早班/晚班/全天 = TLM DAY/NIGHT/ALL）+ 任务切换 + 排班开关，
 *   点击立即生效（QuickApplyPacket）
 * - 日程设置：00:00～24:00 分段编辑（游戏内时间，一天 20 分钟；00:00=游戏黎明）。
 *   每行 "H:MM～H:MM" + 工作模式 + 切换任务；保存时自动去缝隙/补满 24:00；
 *   调度器每秒检查，跨段时间点自动 setSchedule + setTask。
 */
public class ScheduleBookScreen extends Screen {
    private static final int VIEW_LIST = 0;
    private static final int VIEW_DETAIL = 1;
    private static final int ROWS_PER_PAGE = 6;

    /** 打开包数据：{uuid, 名字, 任务UID, 工作模式, 排班开, 段数} */
    private final List<String[]> maids;
    /** 可选任务清单（uid，渲染走 task.<ns>.<path> 翻译键） */
    private final List<String> taskUids;
    private final Screen parent;

    private int view = VIEW_LIST;
    private int page = 0;
    /** 详情页 tab：0=快捷设置 1=日程设置 */
    private int tab = 0;
    private String selUuid;
    private String selName;
    /** 日程行编辑态（客户端）：{时间文本 "H:MM~H:MM", 工作模式, 任务UID} */
    private final List<Object[]> rows = new ArrayList<>();
    /** 日程请求中（等 SchedDataPacket） */
    private boolean waiting = false;
    private boolean loadedOn = false;
    private static ScheduleBookScreen instance;

    /** 键盘输入转发的激活输入框（照 PromaidConfigScreen.activeBox 模式） */
    private EditBox activeBox;

    private static final String[] MODE_NAMES = {"早班", "晚班", "全天"};

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
        int cx = w / 2;
        if (this.view == VIEW_LIST) {
            this.listButtons(w, h, cx);
        } else {
            this.detailButtons(w, h, cx);
        }
    }

    /* ==================== 女仆列表页 ==================== */

    private void listButtons(int w, int h, int cx) {
        int bw = 260;
        int totalPages = Math.max(1, (this.maids.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        this.page = Math.min(this.page, totalPages - 1);
        int start = this.page * ROWS_PER_PAGE;
        int end = Math.min(this.maids.size(), start + ROWS_PER_PAGE);
        int y = 44;
        for (int i = start; i < end; i++) {
            String[] m = this.maids.get(i);
            String sched = "1".equals(m[4]) ? "\u00a7a排班:开\u00a7r（" + m[5] + " 段）" : "\u00a77排班:关";
            // v1.1.0 终审二（用户：只显示名字）——不再拼任务中文名（太挤且信息重复：
            // 点进去第一眼就是当前任务）
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
            y += 23;
        }
        // 翻页（v1.1.0 实测二十五：80 宽"上一页/下一页"按钮盖住女仆行文字——
        // 改 20 宽纯箭头 ◀/▶，仅贴行区下缘，按钮区间距留给页码文字）
        if (this.page > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"), b -> {
                        this.page--;
                        this.m_7856_();
                    })
                    .m_252987_(cx - 110, h - 60, 20, 18).m_253136_());
        }
        if (this.page < totalPages - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"), b -> {
                        this.page++;
                        this.m_7856_();
                    })
                    .m_252987_(cx + 90, h - 60, 20, 18).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c关闭"), b -> this.m_7379_())
                .m_252987_(cx - 50, h - 34, 100, 20).m_253136_());
    }

    /* ==================== 详情页 ==================== */

    private void detailButtons(int w, int h, int cx) {
        // tab 切换（v1.1.0 终审：两个 tab 旧版建在同一坐标完全重叠——看得见的是后画的
        // 「日程设置」、点中的却是先注册的「快捷设置」；现左右并排 + 窄屏收窄防出屏）
        String[] tabs = {"\u00a7e快捷设置", "\u00a7e日程设置"};
        int tabW = w >= 344 ? 160 : 120;
        for (int i = 0; i < 2; i++) {
            final int ti = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.tab == ti ? "\u00a76\u25cf " : "\u00a77") + tabs[i]),
                            b -> {
                                this.tab = ti;
                                // v1.1.0 实测十二（用户："快捷设置打不开"）：切到日程 tab
                                // 时【无条件】请求日程数据——旧版只在 waiting 时补发，
                                // 而 waiting 早已被数据包清掉：切回快捷再切日程时 rows
                                // 是旧数据不会刷新；切到日程后 waiting 已是 false，
                                // schedTab 直接画旧 rows，丢"请求中"状态。现在每次进
                                // 日程 tab 都重新拉最新数据。
                                if (ti == 1) {
                                    this.waiting = true;
                                    this.rows.clear();
                                    ScheduleNetworking.CHANNEL.sendToServer(
                                            new ScheduleNetworking.SchedLoadRequestPacket(this.selUuid));
                                }
                                this.m_7856_();
                            })
                    .m_252987_(cx - tabW - 10 + ti * (tabW + 20), 24, tabW, 18).m_253136_());
        }
        // 返回列表
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆列表"), b -> {
                    this.view = VIEW_LIST;
                    this.selUuid = null;
                    this.m_7856_();
                })
                .m_252987_(12, h - 34, 100, 20).m_253136_());
        if (this.tab == 0) {
            this.quickTab(w, h, cx);
        } else {
            this.schedTab(w, h, cx);
        }
    }

    /** 快捷设置：工作模式 + 任务 + 排班开关（点击立即生效） */
    private void quickTab(int w, int h, int cx) {
        int qx = Math.max(4, cx - 170); // v1.1.0 终审：窄窗口左缘钳制，防按钮出屏
        String[] sel = this.findSel();
        String curTask = sel != null ? sel[2] : "";
        int curMode = sel != null ? Integer.parseInt(sel[3]) : 2;
        boolean on = sel != null && "1".equals(sel[4]);
        // 排班开关
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班:开" : "\u00a77排班:关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            if (sel != null) {
                                sel[4] = on ? "0" : "1";
                            }
                            this.m_7856_();
                        })
                .m_252987_(qx, 52, 160, 20).m_253136_());
        // 工作模式（早班/晚班/全天 → TLM DAY/NIGHT/ALL）
        this.m_142416_(CycleButton.<String>m_168894_(
                        v -> Component.m_237113_(v))
                .m_168961_(MODE_NAMES)
                .m_168948_(MODE_NAMES[Math.max(0, Math.min(2, curMode))])
                .m_168936_(qx, 78, 160, 20, Component.m_237113_("工作模式"),
                        (b, v) -> {
                            int mode = java.util.Arrays.asList(MODE_NAMES).indexOf(v);
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, mode, "", -1));
                            if (sel != null) {
                                sel[3] = String.valueOf(mode);
                            }
                        }));
        // 任务循环（点一下换下一个；到头回绕）
        // v1.1.0 实测十六（审查 P2-5）：空任务列表守卫——openFor 的 getNotHiddenTaskList
        // 整段被 try/catch 吞掉时 taskUids 可能为空，旧版 get(next) 直接 IOOBE 崩客户端
        if (!this.taskUids.isEmpty()) {
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_("任务：" + taskCn(curTask) + " \u00a78(点击切换)"),
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
                    .m_252987_(qx, 104, Math.min(200, w - qx - 4), 20).m_253136_());
        }
    }

    /**
     * 日程设置：分段行编辑 + 保存。
     * v1.1.0 终审重排（像素审计）：排班开关旧版在 (cx+20,24) 与右移后的「日程设置」
     * tab 同行重叠 → 挪到 tab 下一行；分段行宽度窄屏收窄、行数按窗口高度收敛——
     * 任何分辨率下不重叠、不出屏、不与「← 女仆列表」按钮打架。
     */
    private void schedTab(int w, int h, int cx) {
        if (this.waiting) {
            return; // 渲染层显示"请求中…"
        }
        // v1.1.0 实测十二（崩溃修复）：数据未到/行被删空时直接不建行——旧版
        // maxRows = Math.max(1, ...) 强制至少跑一轮循环对空 rows 调 get(0) →
        // IndexOutOfBoundsException 崩游戏（删除唯一一行时触发）
        if (this.rows.isEmpty()) {
            // 空表也要能"添加分段"（否则删光了卡死在空页）
            int emptyLeft = Math.max(8, Math.min(cx - 210, w - 200));
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a+ 添加分段"), b -> {
                        this.rows.add(new Object[]{"0:00~24:00", 2,
                                this.taskUids.isEmpty() ? "" : this.taskUids.get(0)});
                        this.m_7856_();
                    })
                    .m_252987_(emptyLeft, 64, 120, 18).m_253136_());
            return;
        }
        // 排班开关（与快捷页同款）
        boolean on = this.loadedOn;
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班:开" : "\u00a77排班:关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            this.loadedOn = !on;
                            this.m_7856_();
                        })
                .m_252987_(Math.max(4, cx - 170), 44, 160, 18).m_253136_());
        // 分段行布局：宽屏原尺寸，窄屏（GUI 缩放大/小窗口）收窄一档
        // v1.1.0 终审二（用户："□□：□□～□□：□□每一个方框都应该是仅让玩家填一个数字"）——
        // 时间改为 6 个单字符数字输入框（时/时:分/分 ~ 时/时:分/分），默认空；
        // 输入框 setMaxLength(1) + 数字过滤，只收一个数字。
        boolean narrow = w < 348;
        int digitW = 16;
        int gap = 2;
        int timeW = digitW * 6 + gap * 7 + 4; // 6 框 + 间隔 + 冒号/波浪线占位
        int modeW = narrow ? 56 : 62;
        int taskX = timeW + 8;
        int taskW = narrow ? 116 : 130;
        int delX = taskX + taskW + 4;
        int delW = narrow ? 18 : 20;
        int left = Math.max(8, Math.min(cx - 210, w - delX - delW - 8));
        int y = 64;
        // 行数按高度收敛：行 + 添加/保存行必须完整落在「← 女仆列表」按钮（h-34）之上
        // v1.1.0 实测十二：rows 非空保证在前（空表早 return）——不再 maxRows 强制 ≥1
        int maxRows = Math.max(1, Math.min(8, Math.min(this.rows.size(), (h - 118) / 22)));
        for (int i = 0; i < maxRows; i++) {
            final int idx = i;
            Object[] row = this.rows.get(i);
            int[] se = parseRange(String.valueOf(row[0]));
            // 行数据先扩展到 9 槽（3..8 存数字框值——扩展要在数字框回调注册前完成）
            while (this.rows.get(idx).length < 9) {
                Object[] old = this.rows.get(idx);
                Object[] ext = new Object[9];
                System.arraycopy(old, 0, ext, 0, old.length);
                this.rows.set(idx, ext);
            }
            // v1.1.0 实测十二（用户："□□：□□～□□：□□ 方框内的内容可以填数字，
            // 其他内容不要改变，形状都是这个样子"）：固定 6 框【全部常驻显示】——
            // 旧版个位数小时只显示一个框（9:00 → □9:00），形状随数值变形；
            // 现在十位框空着也显示（_9:00 的形态），每个框只填一个数字（0-9），
            // 组合规则不变（空十位 = 个位直接做数值）。
            String[] digits = new String[6];
            if (se != null) {
                int sh = se[0] / 60;
                int sm = se[0] % 60;
                int eh = se[1] / 60;
                int em = se[1] % 60;
                digits[0] = sh >= 10 ? String.valueOf(sh / 10) : "";
                digits[1] = String.valueOf(sh % 10);
                digits[2] = String.valueOf(sm / 10);
                digits[3] = String.valueOf(sm % 10);
                digits[4] = eh >= 10 ? String.valueOf(eh / 10) : "";
                digits[5] = String.valueOf(eh % 10);
            }
            for (int d = 0; d < 6; d++) {
                final int di = d;
                EditBox box = new EditBox(this.f_96547_, left + d * (digitW + gap), y, digitW, 18,
                        Component.m_237113_("时间"));
                box.m_94199_(1); // 单字符
                box.m_94144_(digits[d] == null ? "" : digits[d]);
                box.m_94151_(s -> {
                    // 只收数字（过滤非数字输入）
                    String v = s.replaceAll("[^0-9]", "");
                    this.rows.get(idx)[3 + di] = v;
                });
                // 初始值也写进行槽位（digitsToRange 从槽位读——不依赖 EditBox 焦点历史）
                this.rows.get(idx)[3 + d] = digits[d] == null ? "" : digits[d];
                this.m_142416_(box);
            }
            // 行数据扩展槽位已在上文完成（9 槽）
            // 工作模式循环
            int mode = (int) row[1];
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(MODE_NAMES[Math.max(0, Math.min(2, mode))]),
                            b -> {
                                this.rows.get(idx)[1] = (((int) this.rows.get(idx)[1]) + 1) % 3;
                                b.m_93666_(Component.m_237113_(MODE_NAMES[(int) this.rows.get(idx)[1]]));
                            })
                    .m_252987_(left + timeW + 4, y, modeW, 18).m_253136_());
            // 任务循环
            // v1.1.0 实测十六（审查 P2-5）：空任务列表守卫（同快捷页——空列表时
            // 循环按钮 get(next) 会 IOOBE 崩客户端，这里干脆不建按钮）
            String uid = String.valueOf(row[2]);
            if (!this.taskUids.isEmpty()) {
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_(taskCn(uid)),
                                b -> {
                                    int next = (this.taskUids.indexOf(String.valueOf(this.rows.get(idx)[2])) + 1)
                                            % this.taskUids.size();
                                    this.rows.get(idx)[2] = this.taskUids.get(next);
                                    b.m_93666_(Component.m_237113_(taskCn(this.taskUids.get(next))));
                                })
                        .m_252987_(left + taskX, y, taskW, 18).m_253136_());
            }
            // 删除
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c×"), b -> {
                        this.rows.remove(idx);
                        this.m_7856_();
                    })
                    .m_252987_(left + delX, y, delW, 18).m_253136_());
            y += 22;
        }
        // 添加分段（新段自动衔接上一段结束；无行则 0:00 起）
        // v1.1.0 终审二（用户：到达 24 小时之后就不应该再允许添加分段了）——
        // 末段已到 24:00 时按钮隐藏
        boolean full = !this.rows.isEmpty() && this.lastRowEndMin() >= 1440;
        if (!full) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a+ 添加分段"), b -> {
                        int startMin = 0;
                        if (!this.rows.isEmpty()) {
                            Object[] last = this.rows.get(this.rows.size() - 1);
                            int[] se = parseRange(String.valueOf(last[0]));
                            startMin = se != null ? se[1] : 0;
                        }
                        this.rows.add(new Object[]{ScheduleData.fmt(startMin) + "~24:00", 2,
                                this.taskUids.isEmpty() ? "" : this.taskUids.get(0)});
                        this.m_7856_();
                    })
                    .m_252987_(left, y, 120, 18).m_253136_());
        }
        // 保存
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存日程"), b -> this.saveSchedule())
                .m_252987_(left + 130, y, 100, 18).m_253136_());
    }

    /**
     * v1.1.0 终审二：解析当前各行 6 个数字框拼出的时间段。
     * 数字框值在行对象槽位 3..8（时/时:分/分 ~ 时/时:分/分）；全空行返回 null；
     * 拼出的时间非法（≥24 时 / ≥60 分 / 结束 ≤ 开始）返回 {-1,-1}。
     */
    private static int[] digitsToRange(Object[] row) {
        int h1 = digitAt(row, 3);
        int h2 = digitAt(row, 4);
        int m1 = digitAt(row, 5);
        int m2 = digitAt(row, 6);
        int H1 = digitAt(row, 7);
        int H2 = digitAt(row, 8);
        boolean startEmpty = h1 < 0 && h2 < 0 && m1 < 0 && m2 < 0;
        boolean endEmpty = H1 < 0 && H2 < 0 && m1 < 0 && m2 < 0;
        if (startEmpty && endEmpty) {
            return null; // 整行没填——跳过该行（不报错）
        }
        if (startEmpty || endEmpty) {
            return new int[]{-1, -1}; // 只填了一半——非法
        }
        int sh = h1 < 0 ? h2 : h2 < 0 ? h1 : h1 * 10 + h2;
        int sm = m1 < 0 ? m2 : m2 < 0 ? m1 : m1 * 10 + m2;
        int eh = H1 < 0 ? H2 : H2 < 0 ? H1 : H1 * 10 + H2;
        int em = m1 < 0 ? m2 : m2 < 0 ? m1 : m1 * 10 + m2;
        if (sh > 23 || sm > 59 || eh > 24 || em > 59) {
            return new int[]{-1, -1}; // 非法——调用方报格式错误
        }
        int s = sh * 60 + sm;
        int e = eh * 60 + em;
        if (e <= s) {
            return new int[]{-1, -1};
        }
        return new int[]{s, e};
    }

    private static int digitAt(Object[] row, int slot) {
        if (row == null || row.length <= slot || row[slot] == null) {
            return -1;
        }
        String v = String.valueOf(row[slot]).trim();
        if (v.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 末行结束分钟数（数字框优先，解析失败回退 parseRange；无行返回 0） */
    private int lastRowEndMin() {
        if (this.rows.isEmpty()) {
            return 0;
        }
        Object[] last = this.rows.get(this.rows.size() - 1);
        int[] d = digitsToRange(last);
        if (d != null && d[0] >= 0) {
            return d[1];
        }
        int[] se = parseRange(String.valueOf(last[0]));
        return se != null ? se[1] : 0;
    }

    /** 保存日程：数字框拼时间段 → 归一化 → 发包（v1.1.0 终审二：改走 6 数字框） */
    private void saveSchedule() {
        List<ScheduleData.Segment> segs = new ArrayList<>();
        for (Object[] row : this.rows) {
            int[] se = digitsToRange(row);
            if (se == null) {
                continue; // 整行没填——跳过（不报错；至少一段的校验在下面）
            }
            if (se[0] < 0) {
                this.chat("\u00a7c时间段不对：小时 0~23（结束可到 24）、分钟 0~59，且结束要晚于开始");
                return;
            }
            int mode = Math.max(0, Math.min(2, (int) row[1]));
            segs.add(new ScheduleData.Segment(se[0], se[1], mode, String.valueOf(row[2])));
        }
        if (segs.isEmpty()) {
            this.chat("\u00a7c至少要有一段（点「+ 添加分段」并填上时间）");
            return;
        }
        List<ScheduleData.Segment> norm = ScheduleData.normalize(segs);
        this.rows.clear();
        for (ScheduleData.Segment s : norm) {
            // 时间字符串仅作行内展示缓存；数字框重建时从 Segment 解析
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
        int cx = w / 2;
        // v1.1.0 终审二（用户："要有附魔书那种特效，色调改为红色"）——
        // 背景改暗红、标题/高亮改附魔书紫红（§d 附魔紫 + §o 斜体，Minecraft 附魔书名风格）
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                this.f_96544_ - 8, 0xC0200808);
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                12, 0xFF8B1A1A); // 顶部红色饰条
        if (this.view == VIEW_LIST) {
            g.m_280653_(this.f_96547_, Component.m_237113_("\u00a7d\u00a7o排班表\u00a7r\u00a7c——选择女仆"), cx, 20, 0xFFFFFF);
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进入设置：快捷切换工作模式/任务，或按游戏内时间排一天班"), cx, 32, 0xAAAAAA);
            // v1.1.0 实测二十五：页码画在翻页箭头中间（◀ x 页 ▶）——箭头只有
            // 20px 宽，中间留 ~80px 恰好放"第n/m页"，任何情况下不与按钮重叠
            int tp = Math.max(1, (this.maids.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
            if (tp > 1) {
                g.m_280653_(this.f_96547_,
                        Component.m_237113_("\u00a77第 " + (this.page + 1) + "/" + tp + " 页"),
                        cx, this.f_96544_ - 55, 0xAAAAAA);
            }
        } else {
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a7d\u00a7o" + this.selName + "\u00a7r\u00a7c 的排班"), cx, 8, 0xFFFFFF);
            if (this.tab == 1) {
                // v1.1.0 终审：说明文字挪到页底——顶部那一行现在被 tab(24..42) 和排班开关(44..62) 占满
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77每行填数字（时时:分分～时时:分分）+ 工作模式 + 该时段任务；保存时自动补满 24:00（游戏内时间，一天 20 分钟）"),
                        8, this.f_96544_ - 10, 0xFFE5A0A0, false);
                // 时间框之间的冒号和波浪线（数字框本身不画）
                if (!this.waiting) {
                    int digitW = 16;
                    int gap = 2;
                    int timeW = digitW * 6 + gap * 7 + 4;
                    boolean narrowB = w < 348;
                    int taskX = timeW + 8;
                    int taskW = narrowB ? 116 : 130;
                    int delX = taskX + taskW + 4;
                    int left = Math.max(8, Math.min(cx - 210, w - delX - (narrowB ? 18 : 20) - 8));
                    int y = 64;
                    // v1.1.0 实测十二：冒号/波浪线渲染与 schedTab 同口径——空 rows 不画
                    //（空表只有"+ 添加分段"按钮），不再 maxRows 强制 ≥1
                    int maxRows = Math.min(8, Math.min(this.rows.size(), (this.f_96544_ - 118) / 22));
                    for (int i = 0; i < maxRows; i++) {
                        g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c:"), left + 2 * (digitW + gap) - 3, y + 5, 0xFFFFFFFF, false);
                        g.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c~"), left + 4 * (digitW + gap) - 4, y + 5, 0xFFFFFFFF, false);
                        y += 22;
                    }
                }
                if (this.waiting) {
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u00a77请求中…"), cx, 60, 0xAAAAAA);
                }
            } else {
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77点击立即生效；早班=白天工作、晚班=夜晚工作、全天=一直工作（TLM 日程）"),
                        Math.max(8, cx - 210), 130, 0xFFE5A0A0, false);
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

    /** 任务 UID → 中文名（走任务翻译键 task.<ns>.<path>，TLM/promaid lang 已覆盖；
     *  v1.1.0 终审二：翻译键缺失时旧版把 task.touhou_little_maid.xxx 原样显示——
     *  回退到 UID 末段（fishing）而不是整串翻译键名） */
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
            return cn; // 找到翻译
        }
        return uid.substring(idx + 1); // 无翻译：显示 path 段（如 fishing），不显示整串键名
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

    private void chat(String msg) {
        if (this.f_96541_.f_91074_ != null) {
            this.f_96541_.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
        }
    }

    /* ==================== 键盘转发（照 PromaidConfigScreen.activeBox） ==================== */

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
