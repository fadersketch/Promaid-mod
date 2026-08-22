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
            String label = "\u00a7e" + m[1] + "\u00a7r  " + taskCn(m[2]) + "  " + sched;
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
        // 翻页
        if (this.page > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("< 上一页"), b -> {
                        this.page--;
                        this.m_7856_();
                    })
                    .m_252987_(cx - 90, h - 60, 80, 18).m_253136_());
        }
        if (this.page < totalPages - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("下一页 >"), b -> {
                        this.page++;
                        this.m_7856_();
                    })
                    .m_252987_(cx + 10, h - 60, 80, 18).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c关闭"), b -> this.m_7379_())
                .m_252987_(cx - 50, h - 34, 100, 20).m_253136_());
    }

    /* ==================== 详情页 ==================== */

    private void detailButtons(int w, int h, int cx) {
        // tab 切换
        String[] tabs = {"\u00a7e快捷设置", "\u00a7e日程设置"};
        for (int i = 0; i < 2; i++) {
            final int ti = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.tab == ti ? "\u00a76\u25cf " : "\u00a77") + tabs[i]),
                            b -> {
                                this.tab = ti;
                                if (ti == 1 && this.waiting) {
                                    ScheduleNetworking.CHANNEL.sendToServer(
                                            new ScheduleNetworking.SchedLoadRequestPacket(this.selUuid));
                                }
                                this.m_7856_();
                            })
                    .m_252987_(cx - 170, 24, 160, 18).m_253136_());
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
                .m_252987_(cx - 170, 52, 160, 20).m_253136_());
        // 工作模式（早班/晚班/全天 → TLM DAY/NIGHT/ALL）
        this.m_142416_(CycleButton.<String>m_168894_(
                        v -> Component.m_237113_(v))
                .m_168961_(MODE_NAMES)
                .m_168948_(MODE_NAMES[Math.max(0, Math.min(2, curMode))])
                .m_168936_(cx - 170, 78, 160, 20, Component.m_237113_("工作模式"),
                        (b, v) -> {
                            int mode = java.util.Arrays.asList(MODE_NAMES).indexOf(v);
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, mode, "", -1));
                            if (sel != null) {
                                sel[3] = String.valueOf(mode);
                            }
                        }));
        // 任务循环（点一下换下一个；到头回绕）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("任务：" + taskCn(curTask) + " \u00a78(点击切换)"),
                        b -> {
                            int next = (this.taskUids.indexOf(curTask) + 1) % Math.max(1, this.taskUids.size());
                            String uid = this.taskUids.get(next);
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, uid, -1));
                            if (sel != null) {
                                sel[2] = uid;
                            }
                            this.m_7856_();
                        })
                .m_252987_(cx - 170, 104, 200, 20).m_253136_());
    }

    /** 日程设置：分段行编辑 + 保存 */
    private void schedTab(int w, int h, int cx) {
        int left = Math.max(8, cx - 210);
        if (this.waiting) {
            return; // 渲染层显示"请求中…"
        }
        // 排班开关（与快捷页同款）
        String[] sel = this.findSel();
        boolean on = this.loadedOn;
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班:开" : "\u00a77排班:关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            this.loadedOn = !on;
                            this.m_7856_();
                        })
                .m_252987_(cx + 20, 24, 120, 18).m_253136_());
        // 分段行：时间输入 + 工作模式 + 任务 + 删除
        int y = 52;
        int maxRows = Math.min(this.rows.size(), 8);
        for (int i = 0; i < maxRows; i++) {
            final int idx = i;
            Object[] row = this.rows.get(i);
            EditBox box = new EditBox(this.f_96547_, left, y, 96, 18,
                    Component.m_237113_("时间"));
            box.m_94199_(24);
            box.m_94144_(String.valueOf(row[0]));
            box.m_94151_(s -> this.rows.get(idx)[0] = s);
            this.m_142416_(box);
            // 工作模式循环
            int mode = (int) row[1];
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(MODE_NAMES[Math.max(0, Math.min(2, mode))]),
                            b -> {
                                this.rows.get(idx)[1] = (((int) this.rows.get(idx)[1]) + 1) % 3;
                                b.m_93666_(Component.m_237113_(MODE_NAMES[(int) this.rows.get(idx)[1]]));
                            })
                    .m_252987_(left + 100, y, 62, 18).m_253136_());
            // 任务循环
            String uid = String.valueOf(row[2]);
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(taskCn(uid)),
                            b -> {
                                int next = (this.taskUids.indexOf(String.valueOf(this.rows.get(idx)[2])) + 1)
                                        % Math.max(1, this.taskUids.size());
                                this.rows.get(idx)[2] = this.taskUids.get(next);
                                b.m_93666_(Component.m_237113_(taskCn(this.taskUids.get(next))));
                            })
                    .m_252987_(left + 166, y, 130, 18).m_253136_());
            // 删除
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c×"), b -> {
                        this.rows.remove(idx);
                        this.m_7856_();
                    })
                    .m_252987_(left + 300, y, 20, 18).m_253136_());
            y += 22;
        }
        // 添加分段（新段自动衔接上一段结束；无行则 0:00 起）
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
        // 保存
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存日程"), b -> this.saveSchedule())
                .m_252987_(left + 130, y, 100, 18).m_253136_());
    }

    /** 保存日程：解析全部行 → 归一化 → 发包 */
    private void saveSchedule() {
        List<ScheduleData.Segment> segs = new ArrayList<>();
        for (Object[] row : this.rows) {
            int[] se = parseRange(String.valueOf(row[0]));
            if (se == null) {
                this.chat("\u00a7c时间格式不对：「" + row[0] + "」应为 H:MM~H:MM（如 0:00~8:00）");
                return;
            }
            int mode = Math.max(0, Math.min(2, (int) row[1]));
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
        int cx = w / 2;
        g.m_280509_(Math.max(8, cx - 290), 8, Math.min(w - 8, cx + 290),
                this.f_96544_ - 8, 0xC0101010);
        if (this.view == VIEW_LIST) {
            g.m_280653_(this.f_96547_, Component.m_237113_("\u00a7e排班表——选择女仆"), cx, 16, 0xFFFFFF);
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进入设置：快捷切换工作模式/任务，或按游戏内时间排一天班"), cx, 28, 0x888888);
        } else {
            g.m_280653_(this.f_96547_, Component.m_237113_("\u00a7e" + this.selName + "\u00a7r 的排班"), cx, 8, 0xFFFFFF);
            if (this.tab == 1) {
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77每行 H:MM~H:MM + 工作模式 + 该时段任务；保存时自动补满 24:00（游戏内时间，一天 20 分钟）"),
                        Math.max(8, cx - 210), 40, 0xFF7FB2E5, false);
                if (this.waiting) {
                    g.m_280653_(this.f_96547_, Component.m_237113_("\u00a77请求中…"), cx, 60, 0x888888);
                }
            } else {
                g.m_280614_(this.f_96547_, Component.m_237113_(
                                "\u00a77点击立即生效；早班=白天工作、晚班=夜晚工作、全天=一直工作（TLM 日程）"),
                        Math.max(8, cx - 210), 130, 0xFF7FB2E5, false);
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

    /** 任务 UID → 中文名（走任务翻译键 task.<ns>.<path>，TLM/promaid lang 已覆盖） */
    private static String taskCn(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "空闲";
        }
        int idx = uid.indexOf(':');
        if (idx < 0) {
            return uid;
        }
        return Component.m_237113_("task." + uid.substring(0, idx) + "." + uid.substring(idx + 1))
                .getString();
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
