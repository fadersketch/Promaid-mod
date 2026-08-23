package com.maidsmart.schedule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 排班表界面（v1.1.0）——纸+墨囊合成的「排班表」物品右键打开。
 *
 * v1.1.0 实测五十一【UI 重做】（用户："不再要求玩家去填时间……实际的时间分配
 * 实际上就是将女仆的工作时间分成 6 份"）：
 * - 旧版「日程设置」= 任意行数 + 手填 "H:MM~H:MM" 时间段——弃用（实测三十三的
 *   时间输入框/添加分段/删除行全套移除）。
 * - 新流程：选女仆 → 选班次（早班/晚班/全天）→ 6 个任务按钮排一天。班次工作
 *   窗口均分 6 份（全天每份 4 小时、早/晚班每份 2 小时），点任务按钮循环切换
 *   （与快捷设置同款循环交互），可重复（前 3 个种植后 3 个挖矿 = 一天两档）。
 * - 底层沿用 Segment 存储（相邻同任务自动合并），跨时间点照旧自动切换；
 *   休息时间不排段——由 TLM 作息让她睡觉（早班夜休/晚班昼休）。
 * - 底部"保存日程"一次性提交（避免每点一下任务就 rebuild brain）。
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
    private String selUuid;
    private String selName;
    /** 日程请求中（等 SchedDataPacket） */
    private boolean waiting = false;
    private boolean loadedOn = false;
    /** 详情页页签（实测五十九）：0=第 1 页快捷设置（立即生效） 1=第 2 页排班（班次+6 槽） */
    private int detailPage = 0;
    /** 班次（0=早班 1=晚班 2=全天）——排班页第一步选这个 */
    private int shift = 2;
    /** 6 个任务槽（uid；空 = 空闲/idle）——第二步点按钮循环切换 */
    private final String[] slots = new String[6];
    private static ScheduleBookScreen instance;

    private static final String[] MODE_NAMES = {"早班", "晚班", "全天"};

    /* ==================== 布局常量（实测三十三：全界面唯一定义处） ==================== */
    private static final int TOP_TITLE_Y = 8;      // 标题行
    private static final int TAB_Y = 28;           // tab 行（详情页）
    private static final int CONTENT_TOP = 52;     // 内容区顶
    private static final int CONTENT_BOTTOM_PAD = 56; // 底部按钮区高度预留
    private static final int FOOT_Y = -52;         // 底部区 y（相对 h，负数=从底往上）
    private static final int LIST_ROW_H = 22;      // 列表行高
    /** 槽位按钮：宽 150，一列 6 行（行高 24；窄屏自动缩窄） */
    private static final int SLOT_W = 150;
    /** 实测五十六：时段标签与任务按钮的设计间距——标签区 72px 容纳 "22:00~24:00"
     *  （实测 58px）后仍留 14px 净空；init（detailButtons）与渲染（m_88315_）共用 */
    private static final int LABEL_GAP = 72;

    public static void open(List<String[]> maids, List<String> taskUids) {
        instance = new ScheduleBookScreen(null, maids, taskUids);
        Minecraft.m_91087_().m_91152_(instance);
    }

    /** SchedDataPacket 到达：已存日程 → 班次 + 6 槽任务（旧版手填段也能读回来） */
    public static void showSchedule(String uuid, boolean on, List<ScheduleData.Segment> segments) {
        ScheduleBookScreen cur = instance;
        if (cur == null || !uuid.equals(cur.selUuid)) {
            return;
        }
        cur.waiting = false;
        cur.loadedOn = on;
        cur.shift = ScheduleData.inferShift(segments);
        // 默认任务 = 她当前任务（空表新用户的起点）
        String curTask = "";
        for (String[] m : cur.maids) {
            if (m[0].equals(uuid)) {
                curTask = m[2];
                break;
            }
        }
        String[] tasks = ScheduleData.slotTasks(segments, cur.shift, curTask);
        for (int i = 0; i < 6; i++) {
            cur.slots[i] = tasks[i] == null ? "" : tasks[i];
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
                        this.view = VIEW_DETAIL;
                        // 实测五十九：进详情默认第 1 页（快捷设置，无需日程数据）；
                        // 排班数据改在切到第 2 页时按需拉取（每次进都拉最新）
                        this.detailPage = 0;
                        this.m_7856_();
                    })
                    .m_252987_(cx - bw / 2, y, bw, 20).m_253136_());
            y += LIST_ROW_H;
        }
        // 翻页（◀ 页码 ▶ 居中一行，位于底区）
        // 实测五十六：◀ cx-70 / ▶ cx+40——旧位置（cx-40 / cx+20）与 ~60px 宽的
        // 页码文字（cx±30）两端各重叠 10px，按钮后渲染盖住"第 x/y 页"两端
        if (this.page > 0) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"), b -> {
                        this.page--;
                        this.m_7856_();
                    })
                    .m_252987_(cx - 70, h - 46, 20, 18).m_253136_());
        }
        if (this.page < totalPages - 1) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"), b -> {
                        this.page++;
                        this.m_7856_();
                    })
                    .m_252987_(cx + 40, h - 46, 20, 18).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c关闭"), b -> this.m_7379_())
                .m_252987_(cx - 50, h - 24, 100, 20).m_253136_());
    }

    /* ==================== 详情页（实测五十九：第 1 页快捷设置 / 第 2 页排班） ==================== */

    private void detailButtons(int w, int h) {
        int cx = w / 2;
        // 返回列表（左下角，两页共用）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆列表"), b -> {
                    this.view = VIEW_LIST;
                    this.selUuid = null;
                    this.m_7856_();
                })
                .m_252987_(12, h - 24, 100, 20).m_253136_());
        // ---- 页签（第 1 页快捷设置 / 第 2 页排班）——实测五十六同款自由区居中，避开右上开关 ----
        boolean on = this.loadedOn;
        int toggleLeft = Math.max(4, w - 120);
        int freeL = 8;
        int freeR = toggleLeft - 8;
        String[] tabs = {"快捷设置", "排班"};
        int tabW = Math.min(120, Math.max(60, (freeR - freeL - 10) / 2));
        int tabX0 = freeL + Math.max(0, (freeR - freeL - (tabW * 2 + 10)) / 2);
        for (int i = 0; i < 2; i++) {
            final int ti = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.detailPage == ti ? "\u00a76\u25cf " : "\u00a77") + tabs[i]),
                            b -> {
                                if (this.detailPage == ti) {
                                    return;
                                }
                                this.detailPage = ti;
                                if (ti == 1) {
                                    // 切到排班页：无条件重新拉最新日程（旧数据不残留）
                                    this.waiting = true;
                                    ScheduleNetworking.CHANNEL.sendToServer(
                                            new ScheduleNetworking.SchedLoadRequestPacket(this.selUuid));
                                }
                                this.m_7856_();
                            })
                    .m_252987_(tabX0 + i * (tabW + 10), TAB_Y, tabW, 18).m_253136_());
        }
        // 排班开关（右上角，两页共用；同步列表行状态防关界面后列表显示过期）
        String[] sel = this.findSel();
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班：开" : "\u00a77排班：关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            this.loadedOn = !on;
                            if (sel != null) {
                                sel[4] = this.loadedOn ? "1" : "0";
                            }
                            this.m_7856_();
                        })
                .m_252987_(toggleLeft, TAB_Y, 90, 18).m_253136_());
        if (this.waiting) {
            return; // 渲染层显示"请求中…"，数据到了 showSchedule 会重建
        }
        if (this.detailPage == 0) {
            this.quickPage(w, h, cx, sel);
        } else {
            this.schedPage(w, h, cx);
        }
    }

    /**
     * 第 1 页快捷设置（实测五十九恢复）：工作模式 / 任务循环，点击立即生效——
     * 遥控她"现在"干什么（排班页管的是"一天怎么过"，这页管"此刻"）。
     * 排班开关在右上角（两页共用），本页不再重复放。
     */
    private void quickPage(int w, int h, int cx, String[] sel) {
        String curTask = sel != null ? sel[2] : "";
        int qx = Math.max(8, cx - 150);
        int qw = Math.min(300, w - qx - 8);
        int y = CONTENT_TOP + 6;
        // 工作模式（早班/晚班/全天 → TLM DAY/NIGHT/ALL，立即生效）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("工作模式：\u00a7e"
                                + MODE_NAMES[Math.max(0, Math.min(2, safeInt(sel != null ? sel[3] : null, 2)))]
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
        // 任务循环（点一下换下一个；到头回绕；立即生效）
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
     * 第 2 页排班（实测五十一的班次 + 6 任务槽）。实测五十九压缩纵向：
     * 页签行(28..46)下方班次行(50..68)、6 槽行距 24→22（按钮 20→18，槽区
     * 72..200），提示(h-38)与底行(h-24)不变——240 高最小窗口下全部放得下且零重叠。
     */
    private void schedPage(int w, int h, int cx) {
        // ---- 班次选择：自由区内居中，窄屏自动缩窄（实测五十六口径） ----
        int toggleLeft = Math.max(4, w - 120);
        int freeL = 8;
        int freeR = toggleLeft - 8;
        int shiftW = Math.min(84, Math.max(40, (freeR - freeL - 12) / 3));
        int shiftX0 = freeL + Math.max(0, (freeR - freeL - (shiftW * 3 + 12)) / 2);
        for (int i = 0; i < 3; i++) {
            final int si = i;
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_((this.shift == si ? "\u00a76\u25cf " : "\u00a77") + MODE_NAMES[i]),
                            b -> {
                                if (this.shift == si) {
                                    return;
                                }
                                // 换班次：旧槽任务按新窗口重新取样（已存段自动映射）
                                List<ScheduleData.Segment> cur = ScheduleData.segmentsFromSlots(
                                        this.shift, java.util.Arrays.asList(this.slots));
                                this.shift = si;
                                String[] tasks = ScheduleData.slotTasks(cur, si, this.defTask());
                                for (int k = 0; k < 6; k++) {
                                    this.slots[k] = tasks[k] == null ? "" : tasks[k];
                                }
                                this.m_7856_();
                            })
                    .m_252987_(shiftX0 + i * (shiftW + 6), TAB_Y + 22, shiftW, 18).m_253136_());
        }
        // ---- 6 个任务槽按钮（行距 22、按钮高 18——给页签行让出纵向空间） ----
        int bw = Math.min(SLOT_W, w - 180);
        int x = cx - (bw + LABEL_GAP) / 2 + LABEL_GAP;
        int y = CONTENT_TOP + 20;
        int[] win = ScheduleData.shiftWindow(this.shift);
        int len = (win[1] - win[0]) / 6;
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            String uid = this.slots[idx];
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(fitTask(uid)),
                            b -> {
                                // 快捷设置同款：点一下换下一个（含"空闲"档 → 循环不依赖 uid 匹配）
                                int cur = this.taskUids.indexOf(String.valueOf(this.slots[idx]));
                                int next = (cur + 1) % (this.taskUids.size() + 1);
                                this.slots[idx] = next == this.taskUids.size() ? "" : this.taskUids.get(next);
                                b.m_93666_(Component.m_237113_(fitTask(this.slots[idx])));
                            })
                    .m_252987_(x, y, bw, 18).m_253136_());
            y += 22;
        }
        // ---- 保存（底区右侧，与"← 女仆列表"同一行；攒一次提交防频繁 rebuild brain） ----
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a保存日程"), b -> this.saveSchedule())
                .m_252987_(w - 112, h - 24, 100, 20).m_253136_());
    }

    /** 槽位默认任务：她当前任务（打开包数据；找不到 = 空 = 空闲） */
    private String defTask() {
        String[] sel = this.findSel();
        return sel != null ? sel[2] : "";
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
            // 实测五十九：副标题同步两页结构（第 1 页快捷设置 / 第 2 页排班）
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进入：第 1 页快捷设置（立即生效），第 2 页排班表"), cx, TOP_TITLE_Y + 12, 0xAAAAAA);
            int rowsPerPage = Math.max(1, Math.min(8, (h - 56 - 44) / LIST_ROW_H));
            int tp = Math.max(1, (this.maids.size() + rowsPerPage - 1) / rowsPerPage);
            if (tp > 1) {
                // 页码在 ◀ ▶ 中间（实测五十六：按钮外移到 cx±40~60——旧位置 ◀ 右沿
                // cx-20 / ▶ 左沿 cx+20 会压住 ~60px 宽页码文字的两端各 10px）
                g.m_280653_(this.f_96547_,
                        Component.m_237113_("\u00a77第 " + (this.page + 1) + "/" + tp + " 页"),
                        cx, h - 41, 0xAAAAAA);
            }
        } else {
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a7d\u00a7o" + this.selName + "\u00a7r\u00a7c 的排班"), cx, TOP_TITLE_Y, 0xFFFFFF);
            if (this.waiting) {
                g.m_280653_(this.f_96547_, Component.m_237113_("\u00a77请求中…"), cx, CONTENT_TOP + 6, 0xAAAAAA);
            } else if (this.detailPage == 1) {
                // 第 2 页排班：每槽时段标签（左侧，与任务按钮同行；实测五十一）
                // 实测五十六：lx 用与 schedPage 相同的 LABEL_GAP 公式（旧 58 间距下
                // "22:00~24:00"（58px）右沿与按钮左沿 0px 贴边）
                // 实测五十九：纵向随 schedPage 压缩（行距 22、按钮高 18 → 标签 y+5 居中）
                int[] win = ScheduleData.shiftWindow(this.shift);
                int len = (win[1] - win[0]) / 6;
                int bw = Math.min(SLOT_W, w - 180);
                int lx = cx - (bw + LABEL_GAP) / 2;
                int y = CONTENT_TOP + 20;
                for (int i = 0; i < 6; i++) {
                    String label = ScheduleData.fmt(win[0] + len * i) + "~" + ScheduleData.fmt(win[0] + len * (i + 1));
                    g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + label),
                            lx, y + 5, 0xFFE5A0A0, false);
                    y += 22;
                }
                // 提示在槽区（下沿 200）与底行（h-24）之间的空档居中（实测五十六口径）
                g.m_280653_(this.f_96547_, Component.m_237113_(
                                "\u00a77选班次 → 每段点一个任务（可重复）→ 保存；休息按作息睡觉"),
                        cx, h - 38, 0xFFE5A0A0);
            } else {
                // 第 1 页快捷设置：提示放同一条空档线（h-38），与两行按钮零重叠
                g.m_280653_(this.f_96547_, Component.m_237113_(
                                "\u00a77点击立即生效：遥控她现在的作息与任务；排一天班去第 2 页"),
                        cx, h - 38, 0xFFE5A0A0);
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

    /**
     * 任务 UID → 中文名（翻译键 task.<ns>.<path>；无翻译回退 path 段）。
     * v1.1.0 实测五十三（用户："任务显示的是英文，很影响阅读"）：旧版用
     * Component.m_237113_（= literal 字面量组件，javap 核实 LiteralContents）去
     * "查翻译"——getString() 永远返回键本身 → 恒等比对永远成立 → 永远走英文兜底，
     * TLM/本模组语言文件里的中文（task.touhou_little_maid.farm=农场、
     * task.maid_smart.mine=挖矿…）从来没被用过。改 m_237115_ = translatable，
     * getString() 走客户端合并语言表解析；键缺失时 TranslatableContents 原样返回
     * 键 → 恒等比对依旧能正确判"无翻译"。
     */
    private static String taskCn(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "空闲";
        }
        int idx = uid.indexOf(':');
        if (idx < 0) {
            return uid;
        }
        String key = "task." + uid.substring(0, idx) + "." + uid.substring(idx + 1);
        String cn = Component.m_237115_(key).getString();
        if (!cn.equals(key)) {
            return cn;
        }
        return uid.substring(idx + 1);
    }

    /** 任务名截断到按钮宽度内（SLOT_W ≈ 150px ≈ 15 个中文字符） */
    private static String fitTask(String uid) {
        String cn = taskCn(uid);
        return cn.length() > 10 ? cn.substring(0, 9) + "…" : cn;
    }

    private void chat(String msg) {
        if (this.f_96541_.f_91074_ != null) {
            this.f_96541_.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
        }
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
