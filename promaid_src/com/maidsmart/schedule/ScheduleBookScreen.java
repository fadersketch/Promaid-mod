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
    /** 班次（0=早班 1=晚班 2=全天）——详情页第一步选这个 */
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
                        this.waiting = true;
                        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.SchedLoadRequestPacket(uuid));
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

    /* ==================== 详情页（实测五十一：班次 + 6 任务槽） ==================== */

    private void detailButtons(int w, int h) {
        int cx = w / 2;
        // 返回列表（左下角）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆列表"), b -> {
                    this.view = VIEW_LIST;
                    this.selUuid = null;
                    this.m_7856_();
                })
                .m_252987_(12, h - 24, 100, 20).m_253136_());
        if (this.waiting) {
            return; // 渲染层显示"请求中…"，数据到了 showSchedule 会重建
        }
        // ---- 1. 班次选择（早班/晚班/全天 三连排；选中高亮金色） ----
        // v1.1.0 实测五十六【像素级核查修复】：旧版 shiftX0 = cx-132 与右上角排班
        // 开关（w-120 起）在默认 GUI 宽 427 下重叠 38px（第 3 个按钮右沿 cx+132=345
        // > 开关左沿 307）、480 宽下仍重叠 12px。改为在【左边距 8 ~ 开关左沿-8】
        // 的自由区内居中，窄屏自动缩窄按钮宽度——任何分辨率下零重叠
        boolean on = this.loadedOn;
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
                    .m_252987_(shiftX0 + i * (shiftW + 6), TAB_Y, shiftW, 18).m_253136_());
        }
        // 排班开关（右上角，y 与班次行同行 28..46——x 区间已隔离）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_(on ? "\u00a7a排班：开" : "\u00a77排班：关"),
                        b -> {
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.QuickApplyPacket(
                                    this.selUuid, -1, "", on ? 0 : 1));
                            this.loadedOn = !on;
                            this.m_7856_();
                        })
                .m_252987_(toggleLeft, TAB_Y, 90, 18).m_253136_());
        // ---- 2. 6 个任务槽按钮（一列 6 行，每行 = 时段标签 + 任务按钮） ----
        // 实测五十六：标签/按钮间距 58→72——"22:00~24:00"实测 58px，旧间距下标签
        // 右沿与按钮左沿 0px 贴边（字体渲染差异即重叠）；72 间距留 14px 净空
        int bw = Math.min(SLOT_W, w - 180);
        int x = cx - (bw + LABEL_GAP) / 2 + LABEL_GAP;
        int y = CONTENT_TOP + 2;
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
                    .m_252987_(x, y, bw, 20).m_253136_());
            y += 24;
        }
        // ---- 3. 保存（底区右侧，与"← 女仆列表"同一行） ----
        // 实测五十六：旧位置 (cx+100, h-52) 与第 6 槽按钮（右沿 cx+104、下沿 194）
        // 在所有宽度下角部重叠 4×6px（h-52=188 < 194）；h-24 行槽区早已结束（194
        // < 216），且与左下返回按钮分列两端（中缝 ≥ 96px），零重叠
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
            // 实测五十六：副标题同步实测五十一的新 UI（旧文案还在描述已移除的快捷设置 tab）
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进入排班：选班次，6 个时段各安排一个任务"), cx, TOP_TITLE_Y + 12, 0xAAAAAA);
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
            } else {
                // 每槽时段标签（左侧，与任务按钮同行；实测五十一）
                // 实测五十六：lx 用与 detailButtons 相同的 LABEL_GAP 公式（旧 58 间距下
                // "22:00~24:00"（58px）右沿与按钮左沿 0px 贴边）
                int[] win = ScheduleData.shiftWindow(this.shift);
                int len = (win[1] - win[0]) / 6;
                int bw = Math.min(SLOT_W, w - 180);
                int lx = cx - (bw + LABEL_GAP) / 2;
                int y = CONTENT_TOP + 2;
                for (int i = 0; i < 6; i++) {
                    String label = ScheduleData.fmt(win[0] + len * i) + "~" + ScheduleData.fmt(win[0] + len * (i + 1));
                    g.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + label),
                            lx, y + 6, 0xFFE5A0A0, false);
                    y += 24;
                }
                // 实测五十六：提示从 (8, h-8) 移到槽区与底行按钮之间的空档居中——旧位置
                // 与"← 女仆列表"（y h-24..h-4，x 12..112）重叠，按钮后渲染把提示前
                // 17 个字盖住（"字幕被遮挡"）。h-38 行：槽区下沿 194 之下、按钮行上沿
                // h-24 之上，240 高最小窗口下两侧净空 8/5px；文案缩短到 ~237px，
                // 320 最小宽度下左右仍各余 41px
                g.m_280653_(this.f_96547_, Component.m_237113_(
                                "\u00a77选班次 → 每段点一个任务（可重复）→ 保存；休息按作息睡觉"),
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
