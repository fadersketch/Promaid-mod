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
    /** v1.1.0 实测六十三（自查修复）：排班页有未保存编辑——切页不再重拉数据覆盖 */
    private boolean schedDirty = false;
    /** 实测六十（借鉴 Maid_Roster）：列表搜索词 / 批量模式选择 / 批量任务选择 /
     *  键盘焦点输入框 / 上次过滤后行数（空态提示用） */
    private String searchQuery = "";
    private int batchMode = 2;
    private String batchTask = "";
    private EditBox activeBox;
    private int lastFiltered = 0;
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
        this.activeBox = null; // 实测六十：重建后由各页第一个输入框认领焦点
        int w = this.f_96543_;
        int h = this.f_96544_;
        if (this.view == VIEW_LIST) {
            this.listButtons(w, h);
        } else {
            this.detailButtons(w, h);
        }
    }

    /* ==================== 女仆列表页 ==================== */

    /**
     * v1.1.0 实测六十（借鉴 Maid_Roster 军队管理，不学"绑定点名册"——我们直接
     * 列出全部女仆且跨维度）：搜索框按名字过滤 / 行内血量+维度状态 / 批量应用
     * 工作模式与任务 / 一键集合（跨维度传送回身边）。
     * 纵向：搜索框 32..50，行区 52 起（rowsPerPage = (h-124)/22），批量行 h-68，
     * 翻页 h-46，集合+关闭 h-24——240 高最小窗口零重叠（实测五十六口径）。
     */
    private void listButtons(int w, int h) {
        int cx = w / 2;
        int bw = Math.min(300, w - 16);
        // 搜索框（按名字过滤，输入即刷）
        EditBox search = new EditBox(this.f_96547_, cx - bw / 2, 32, bw, 18,
                Component.m_237113_("搜索名字…"));
        search.m_94199_(20);
        search.m_94144_(this.searchQuery);
        // 实测六十二（自查修复）：setValue 只把【旧框】的光标夹到新文本长度，而击键
        // 重建出来的【新框】光标默认在 0——不手动移到末尾的话，输入会倒序乱掉
        search.m_94208_(this.searchQuery.length());
        search.m_94151_(s -> {
            this.searchQuery = s;
            this.page = 0;
            this.m_7856_();
        });
        search.m_94202_(0xFFFFFF);
        this.m_142416_(search);
        this.activeBox = search; // 默认聚焦：打开即打字过滤
        search.m_93692_(true);
        // 过滤（名字包含匹配，大小写不敏感）
        String q = this.searchQuery == null ? "" : this.searchQuery.trim().toLowerCase();
        java.util.List<String[]> shown = new ArrayList<>();
        for (String[] m : this.maids) {
            if (!q.isEmpty() && !m[1].toLowerCase().contains(q)) {
                continue;
            }
            shown.add(m);
        }
        this.lastFiltered = shown.size();
        int avail = h - 72 - 52;
        int rowsPerPage = Math.max(1, Math.min(8, avail / LIST_ROW_H));
        int totalPages = Math.max(1, (shown.size() + rowsPerPage - 1) / rowsPerPage);
        this.page = Math.min(this.page, totalPages - 1);
        int start = this.page * rowsPerPage;
        int end = Math.min(shown.size(), start + rowsPerPage);
        int y = 52;
        for (int i = start; i < end; i++) {
            String[] m = shown.get(i);
            // 行标签：名字 + 血量% + 维度标签（跨维度才显示）+ 排班状态（实测六十）
            String sched = "1".equals(m[4])
                    ? "\u00a7a排班开\u00a77（" + m[5] + " 段）" : "\u00a77排班关";
            String label = "\u00a7e" + fitName(m[1]) + " \u00a7f" + m[6] + "% "
                    + (m[7].isEmpty() ? "" : "\u00a79" + m[7] + " ") + sched;
            final String uuid = m[0];
            final String name = m[1];
            this.m_142416_(Button.m_253074_(Component.m_237113_(label), b -> {
                        this.selUuid = uuid;
                        this.selName = name;
                        this.view = VIEW_DETAIL;
                        // 实测五十九：进详情默认第 1 页（快捷设置，无需日程数据）；
                        // 排班数据改在切到第 2 页时按需拉取（每次进都拉最新）
                        this.detailPage = 0;
                        // v1.1.0 实测六十三（自查修复）：开关状态从列表行初始化——旧逻辑
                        // loadedOn 残留上一只女仆的值，详情页右上角开关显示错误、切换时
                        // 还会把错误的开/关写给新女仆
                        this.loadedOn = "1".equals(m[4]);
                        // v1.1.0 实测六十四（二次复查修复）：换女仆必须清排班页脏标记——
                        // 残留 true 会让新女仆的排班页不拉数据、显示上一只的槽位
                        this.schedDirty = false;
                        this.m_7856_();
                    })
                    .m_252987_(cx - bw / 2, y, bw, 20).m_253136_());
            y += LIST_ROW_H;
        }
        // 批量行（h-68）：全员模式（点选即应用）/ 任务选择（只选不应用）/ 应用任务
        int bx0 = cx - 146;
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7e全员模式：" + MODE_NAMES[Math.max(0, Math.min(2, this.batchMode))]),
                        b -> {
                            this.batchMode = (this.batchMode + 1) % 3;
                            ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.BatchApplyPacket(
                                    this.batchMode, ""));
                            this.m_7856_();
                        })
                .m_252987_(bx0, h - 68, 100, 18).m_253136_());
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("任务：\u00a7e" + fitTask(this.batchTask) + " \u00a77\u25b8"),
                        b -> {
                            if (this.taskUids.isEmpty()) {
                                return;
                            }
                            int next = (this.taskUids.indexOf(this.batchTask) + 1) % this.taskUids.size();
                            this.batchTask = this.taskUids.get(next);
                            this.m_7856_();
                        })
                .m_252987_(bx0 + 106, h - 68, 100, 18).m_253136_());
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a\u2713应用任务"), b -> {
                    if (!this.batchTask.isEmpty()) {
                        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.BatchApplyPacket(
                                -1, this.batchTask));
                    }
                })
                .m_252987_(bx0 + 212, h - 68, 80, 18).m_253136_());
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
        // 一键集合（跨维度传送全部在场女仆到身边；实测六十）+ 关闭
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7d\u2691 一键集合"), b ->
                        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.SummonPacket()))
                .m_252987_(Math.max(8, cx - 145), h - 24, 90, 20).m_253136_());
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
                                // v1.1.0 实测六十三（自查修复）：有未保存编辑时不再重拉——
                                // 旧逻辑每次进排班页都拉最新数据，翻个页未保存的班次/任务
                                // 就被覆盖丢失；脏标记在保存/换女仆时清除
                                if (ti == 1 && !this.schedDirty) {
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
        // v1.1.0 实测七十（用户反馈：日程表与主人主动切任务冲突）：排班中的女仆
        // 【锁定任务】——按钮文案提示，点击不再发包（服务端同样拦截兜底）
        if (!this.taskUids.isEmpty()) {
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_("任务：\u00a7e" + taskCn(curTask)
                                    + (this.loadedOn ? " \u00a7c(排班中·锁定)" : " \u00a78(点击切换)")),
                            b -> {
                                if (this.loadedOn) {
                                    return; // 硬性锁定：先关右上角的排班才能切任务
                                }
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
        // 改名行（实测六十，借鉴 Maid_Roster 的重命名——直接改女仆自定义名，等同命名牌）
        y += 26;
        EditBox nameBox = new EditBox(this.f_96547_, qx, y + 1, qw - 96, 18,
                Component.m_237113_("新名字"));
        nameBox.m_94199_(30);
        nameBox.m_94144_(sel != null ? sel[1] : "");
        nameBox.m_94202_(0xFFFFFF);
        this.m_142416_(nameBox);
        this.m_142416_(Button.m_253074_(Component.m_237113_("改名"), b ->
                        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.RenameMaidPacket(
                                this.selUuid, nameBox.m_94155_())))
                .m_252987_(qx + qw - 92, y, 92, 20).m_253136_());
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
                                this.schedDirty = true; // 实测六十三：未保存编辑标记
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
                                this.schedDirty = true; // 实测六十三：未保存编辑标记
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

    /* ==================== 保存 ==================== */

    /**
     * 保存日程：班次 + 6 槽 → 段列表（相邻同任务自动合并）→ 发包。
     * 服务端只做越界防御（不再 normalize 补满 24:00——休息时间不排段）。
     * v1.1.0 实测六十三：保存后清脏标记（之后切页可安全重拉最新数据）。
     * 【实测六十三事故记录】本方法在实测五十一重写 UI 时被整体遗漏，导致
     * ScheduleBookScreen.java 从五十一起每次 javac 都失败、而构建管线打包的是
     * out 目录里实测五十时代的旧 class（verify 只对比 out↔jar 不对比 src）——
     * 五十一~六十三的全部界面改动一直没真正进过 jar。已补回本方法并加固
     * 构建脚本（javac 退出码真实检查 + 源文件比 class 新则拒打包）。
     */
    private void saveSchedule() {
        List<String> slotList = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            slotList.add(this.slots[i] == null ? "" : this.slots[i]);
        }
        List<ScheduleData.Segment> segs = ScheduleData.segmentsFromSlots(this.shift, slotList);
        ScheduleNetworking.CHANNEL.sendToServer(new ScheduleNetworking.SchedSavePacket(
                this.selUuid, this.loadedOn, segs));
        this.schedDirty = false;
        this.chat("\u00a7a日程已保存：工作时间均分 6 份，跨时间段自动切换；休息时间由作息睡觉");
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
            // 实测五十九/六十：副标题同步两页结构 + 列表页新能力（搜索/批量/集合）
            g.m_280653_(this.f_96547_, Component.m_237113_(
                            "\u00a77点女仆进详情（快捷/排班）；下方搜索·批量·集合"), cx, TOP_TITLE_Y + 12, 0xAAAAAA);
            // 实测六十：空列表/无匹配提示（画在行区首行位置，不与任何控件重叠）
            if (this.lastFiltered == 0) {
                String q = this.searchQuery == null ? "" : this.searchQuery.trim();
                g.m_280653_(this.f_96547_, Component.m_237113_(q.isEmpty()
                                ? "\u00a77没有找到女仆（打开排班表时须有女仆在场）"
                                : "\u00a77没有匹配「" + q + "」的女仆"),
                        cx, 66, 0xAAAAAA);
            }
            int rowsPerPage = Math.max(1, Math.min(8, (h - 124) / LIST_ROW_H));
            int tp = Math.max(1, (this.lastFiltered + rowsPerPage - 1) / rowsPerPage);
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
                // v1.1.0 实测七十：排班中的女仆任务锁定——硬性提示替代普通说明
                g.m_280653_(this.f_96547_, Component.m_237113_(this.loadedOn
                                ? "\u00a7c⚠ 她有排班：任务由日程表自动管理——先在右上角关闭排班，才能在这里切任务"
                                : "\u00a77点击立即生效：遥控她现在的作息与任务；排一天班去第 2 页"),
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

    /** 实测六十：女仆名截断（行内还要放血量/维度/排班状态，8 字符封顶） */
    private static String fitName(String s) {
        return s != null && s.length() > 8 ? s.substring(0, 7) + "…" : s;
    }

    /* ==================== 键盘转发（实测六十：搜索框/改名框） ==================== */

    /** 点击输入框时记录 activeBox（键盘字符/按键直接转发，不依赖焦点链）；
     *  点空白处失焦——按钮点击不受输入框焦点影响 */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.activeBox = null;
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
