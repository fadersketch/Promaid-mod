package com.maidsmart.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Promaid 手册界面（v1.5.71 布局重构版）：
 * - 大目录页：两个大入口【建造】【女仆管理】+ 顶部实时进度行
 * - 建造面板：总目录（全部建筑仅显示名称，翻页）→ 点名称 → 材料详情页
 *   材料详情：2 列 × 8 行 = 16 种/页（规则，小字体），放不下自动翻页
 * - 女仆管理面板：128 格内所有建造女仆（上限 30 只，翻页），行内点击暂停/继续 + 设工头，
 *   右上角全部暂停/继续
 * - 实时进度：客户端每 2 秒轮询 SHOW_PROGRESS，服务端回发 ProgressUpdatePacket，
 *   进度/速度/女仆状态自动刷新（无需按键、无需退出手册）
 * 854×480 窗口 + GUI 自动缩放（虚拟分辨率 427×240）时列表压出屏幕、与底部控制区互相重叠。
 * 新版改为【自适应】：内容区 = 屏幕高 - 顶部标题区 - 底部控制区，每页行数按可用高度动态
 * 计算（大窗口自动变多、小窗口自动变少，永不越界）；材料 2 列规则保留，列宽自适应。
 * 纯客户端 Screen（无 Menu 容器），数据由 OpenBlueprintBookPacket 下发。
 */
public class BlueprintBookScreen extends Screen {
    /** 视图：大目录 / 建造 / 女仆管理 */
    /** v1.5.88：读配置面板（build.maxMaids） */
    private static int maxMaids() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_MAIDS.get();
    }


    private static final int VIEW_HOME = 0;
    private static final int VIEW_BUILD = 1;
    private static final int VIEW_MAIDS = 2;
    /** v1.5.95：女仆记忆查看 */
    private static final int VIEW_MEMORY = 3;
    /** v1.5.182：女仆详情页（绑定/解绑指定区块） */
    private static final int VIEW_MAID_DETAIL = 4;
    /** v1.5.182：区块绑定女仆名单（设置工头） */
    private static final int VIEW_REGION_MAIDS = 5;
    /** v1.5.396 实测八百三十五：DIY / 默认模式页（DIY ↔ 默认一键切换） */
    private static final int VIEW_PRESET = 6;
    /** 大目录入口数（详细介绍 / 建造 / 女仆管理 / 模组详细配置 / 女仆记忆 / DIY·默认模式） */
    private static final int HOME_ENTRIES = 6;
    /** 预设页的"DIY 会改动 N 项"：进页时算一次（planDiy 要读 500+ 个配置项，不能每帧算） */
    private int presetPending = -1;

    /** 女仆管理上限 */
    /** 材料详情：2 列 × 8 行 = 16 种/页（规则），小行高 */
    private static final int MAT_COLS = 2;
    private static final int MAT_ROWS_PER_PAGE = 8;
    private static final int MAT_LINE_H = 9;
    /** 建造目录条目按钮高 */
    private static final int NAME_BUTTON_H = 18;
    /** 女仆行高 */
    private static final int MAID_ROW_H = 16;

    // ===== v1.5.71 布局常量（全部相对屏幕尺寸，小窗口自适应） =====
    /** 顶部角按钮（返回大目录 / 全部暂停） */
    private static final int TOP_BTN_Y = 8;
    /** v1.5.227：记忆开关防连点时间戳（600ms 内重复点击忽略） */
    private long lastMemoryToggleClick = 0;
    /** v1.0.3：LLM 开关防连点时间戳（与记忆开关同 600ms 防连点） */
    private long lastLlmToggleClick = 0;
    private static final int TOP_BTN_H = 16;
    /** 面板标题行（渲染用，36 = 标题） */
    private static final int PANEL_TITLE_Y = 36;
    /** 内容区起点（标题/进度之下） */
    private static final int CONTENT_TOP = 52;
    /** 底部控制区占用高度（行2 = h-52，行1 = h-30，各 20px 高） */
    private static final int BOTTOM_ZONE = 76;
    /** v1.5.102b：进度显示底部安全间距（文本行距底 40px，进度条在其下 22px——
     *  旧版 home 用 h-28（条画在 h-6）、maids 无翻页用 h-34：百分比文字字号 8px
     *  会画出屏幕底边被裁掉（"进度条/文本突出屏幕"）。统一提到安全距离，条/文字完整可见） */
    /** v1.5.252u：大目录页进度区离底边距离（3 行字段 + 进度条 + 标签） */
    private static final int PROGRESS_BOTTOM_GAP = 60;

    private List<BlueprintBookBuildPackets.Entry> entries;
    /** v1.5.43：周围建造女仆状态列表 {uuid, 名字, 状态[, 工头标记]} */
    private List<String[]> maids = new ArrayList<>();
    /** v1.5.100b：全部女仆（记忆页用，不限距离）：{uuid, 名字, 记忆开关 "1"/"0"} */
    private List<String[]> allMaids = new ArrayList<>();
    /** v1.5.178：有效建造区块列表 {planId, 显示名, 维度名, 状态, x,y,z, W,H,D, blueprintId}
     *  （女仆管理页区块显示 + 客户端区块内判定） */
    private List<String[]> buildRegions = new ArrayList<>();
    /** v1.5.180：玩家所在区块 planId（轮询刷新；控制按钮目标；区块外 = null） */
    private String currentPlanId = null;
    /** v1.5.180：女仆管理页选中的区块 planId（绑定目标；默认第一个） */
    private String selectedPlanId = null;
    /** v1.5.182：女仆详情页——当前查看的女仆 UUID */
    private String detailMaidUuid = null;
    /** v1.5.48：全局暂停状态 + 速度档位 */
    private boolean paused = false;
    private String speed = "×1";
    /** v1.5.62：建造进度文本（服务端下发，实时刷新） */
    private String progressText = "";
    /** v1.5.65：进度百分比（-1 = 无计划）——进度条绘制 */
    private int progressPct = -1;
    /** v1.5.252s：进度条旁显示——预计完成秒（-1=未知）+ 实时速度（块/秒） */
    private int etaSec = -1;
    private String speedBps = "";
    /**
     * v1.5.162：进行中计划的区块标记（中心点 + 尺寸）——客户端判定"玩家是否处于
     * 建造区块内"（regionX = Integer.MIN_VALUE 表示无计划）；续建/暂停等控制按钮
     * 只在玩家站在区块内时显示（2 秒轮询刷新）
     */
    private int regionX = Integer.MIN_VALUE;
    private int regionY = 0;
    private int regionZ = 0;
    private int regionW = 0;
    private int regionH = 0;
    private int regionD = 0;
    /** v1.5.63：当前视图 */
    private int view = VIEW_HOME;
    /** 建造面板：当前查看材料详情的蓝图（null = 建造目录） */
    private BlueprintBookBuildPackets.Entry viewingEntry = null;
    /** 材料详情页页码 */
    private int matPage = 0;
    /** 建造目录页 */
    private int buildPage = 0;
    /** v1.5.374：建造目录搜索框 + 查询串（8000+ 建筑按名/ID 过滤，网格分页） */
    private net.minecraft.client.gui.components.EditBox searchBox;
    private String searchQuery = "";
    private boolean searchFocusPending = false;
    /** 女仆列表页 */
    private int maidPage = 0;
    /** v1.5.63：实时轮询节流（每 40 tick ≈ 2 秒请求一次状态） */
    private int tickCount = 0;
    /** v1.5.95：女仆记忆查看——选中的女仆 uuid + 返回的记忆行 */
    private String memoryMaidUuid = null;
    private String memoryMaidName = null;
    private List<String> memoryLines = null;
    /** 实测九百七十一：记忆 / 状态区折行后的行高（原 12 偏挤，折行后按 11 走） */
    private static final int TEXT_LINE_H = 11;
    /** 实测九百七十一：链路调试"可调试对象"行按钮高度——对齐原版按钮贴图 20px
     *  （旧版 14px 会把 20px 贴图压出横缝，看上去像每行白占一行） */
    private static final int DEBUG_ROW_H = 16;

    private int memoryPage = 0;
    /** v1.5.190：女仆详情页区块列表页码 / 区块名单页女仆列表页码（无上限行修复） */
    private int detailRegionPage = 0;
    private int regionMaidPage = 0;

    // ===== v1.5.73：跨打开界面记忆（帕秋莉式——ESC 关闭后再次右击打开，回到上次所在界面） =====
    private static int lastView = VIEW_HOME;
    private static String lastViewingId = null;
    private static int lastBuildPage = 0;
    private static int lastMatPage = 0;
    private static int lastMaidPage = 0;

    protected BlueprintBookScreen(List<BlueprintBookBuildPackets.Entry> entries, List<String[]> maids,
                                  List<String[]> allMaids, boolean paused, String speed, String progressText,
                                  int progressPct, int regionX, int regionY, int regionZ,
                                  int regionW, int regionH, int regionD,
                                  boolean inPlanRegion, String currentPlanId,
                                  List<String[]> regions, int etaSec, String speedBps) {
        super(Component.m_237113_("Promaid 手册"));
        this.entries = entries;
        this.maids = maids == null ? new ArrayList<>() : maids;
        this.allMaids = allMaids == null ? new ArrayList<>() : allMaids;
        this.buildRegions = regions == null ? new ArrayList<>() : regions;
        this.paused = paused;
        if (speed != null) {
            this.speed = speed;
        }
        if (progressText != null) {
            this.progressText = progressText;
        }
        this.progressPct = progressPct;
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
        this.regionW = regionW;
        this.regionH = regionH;
        this.regionD = regionD;
        // v1.5.252z：打开手册立即显示速度/ETA（不等 2 秒轮询）
        this.etaSec = etaSec;
        this.speedBps = speedBps == null ? "" : speedBps;
        // v2.0：区块内右击手册 = 玩家明确意图 → 直接进入当前计划的建造详情页
        //（覆盖上次视图恢复）；区块外右击 = 正常目录
        boolean jumped = false;
        if (inPlanRegion && currentPlanId != null && !currentPlanId.isEmpty() && this.entries != null) {
            for (BlueprintBookBuildPackets.Entry e : this.entries) {
                if (e.id().equals(currentPlanId)) {
                    this.view = VIEW_BUILD;
                    this.viewingEntry = e;
                    jumped = true;
                    break;
                }
            }
        }
        // v1.5.73：恢复上次关闭时的视图/页面（材料详情按蓝图 id 在新目录里重新定位，
        // 找不到（蓝图被删）则回建造目录）
        if (!jumped) {
            this.view = lastView;
            this.viewingEntry = null;
            if (lastViewingId != null && this.entries != null) {
                for (BlueprintBookBuildPackets.Entry e : this.entries) {
                    if (e.id().equals(lastViewingId)) {
                        this.viewingEntry = e;
                        break;
                    }
                }
            }
        }
        this.buildPage = lastBuildPage;
        this.matPage = lastMatPage;
        this.maidPage = lastMaidPage;
    }

    /** 收到目录包后打开界面（v1.5.159：同时关闭金色预览——再次打开手册 = 关预览；
     *  v1.5.204：clear 不重置"看过预览"标记，第 2 步确认可达；
     *  红色区块框与幽灵投影由服务端每秒同步驱动，不受开关手册影响）
     *  v1.5.275：initialView 0=大目录 1=女仆管理（配置面板"跳转女仆管理"） */
    public static void open(List<BlueprintBookBuildPackets.Entry> entries, List<String[]> maids,
                            List<String[]> allMaids, boolean paused, String speed, String progressText,
                            int progressPct, int regionX, int regionY, int regionZ,
                            int regionW, int regionH, int regionD,
                            boolean inPlanRegion, String currentPlanId, List<String[]> regions,
                            int etaSec, String speedBps, int initialView) {
        com.maidsmart.build.BlueprintAreaPreview.clear();
        BlueprintBookScreen screen = new BlueprintBookScreen(entries, maids, allMaids, paused, speed,
                progressText, progressPct, regionX, regionY, regionZ, regionW, regionH, regionD,
                inPlanRegion, currentPlanId, regions, etaSec, speedBps);
        if (initialView == 2) {
            screen.view = VIEW_MAIDS; // 直接进女仆管理页
        }
        Minecraft.m_91087_().m_91152_(screen);
    }

    /** v1.5.62：服务端状态刷新（进度/速度/暂停/女仆状态即时更新，不重开面板） */
    public void updateStatus(String progressText, List<String[]> maids, boolean paused, String speed, int progressPct,
                             int regionX, int regionY, int regionZ, int regionW, int regionH, int regionD,
                             List<String[]> allMaids, List<String[]> regions, String planId,
                             int etaSec, String speedBps) {
        if (progressText != null) {
            this.progressText = progressText;
        }
        this.maids = maids == null ? new ArrayList<>() : maids;
        this.allMaids = allMaids == null ? new ArrayList<>() : allMaids;
        this.buildRegions = regions == null ? new ArrayList<>() : regions;
        this.currentPlanId = planId; // v1.5.180：玩家所在区块 planId（控制按钮目标）
        this.paused = paused;
        if (speed != null) {
            this.speed = speed;
        }
        this.progressPct = progressPct;
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionZ = regionZ;
        this.regionW = regionW;
        this.regionH = regionH;
        this.regionD = regionD;
        // v1.5.252s：进度条旁显示 块/秒 + 预计完成时间
        this.etaSec = etaSec;
        this.speedBps = speedBps == null ? "" : speedBps;
        this.rebuildButtons();
    }

    /**
     * v1.5.180：玩家所在区块 planId（多区块共存——用 regions 列表带尺寸判定，
     * 公式与服务端一致：[ox-W/2, ox-W/2+W) × [oy, oy+H) × [oz-D/2, oz-D/2+D)）。
     * 不在任何区块内返回 null。
     *
     * <p>实测九百八十五：判定放宽到「**接触到**区块」——紧贴边界外一格也算（见下）。
     */
    private String playerPlanId() {
        net.minecraft.client.player.LocalPlayer player = Minecraft.m_91087_().f_91074_;
        if (player == null) {
            return null;
        }
        net.minecraft.core.BlockPos p = player.m_20183_();
        for (String[] r : this.buildRegions) {
            if (r == null || r.length < 10) {
                continue;
            }
            try {
                int x = Integer.parseInt(r[4]);
                int y = Integer.parseInt(r[5]);
                int z = Integer.parseInt(r[6]);
                int w = Integer.parseInt(r[7]);
                int h = Integer.parseInt(r[8]);
                int d = Integer.parseInt(r[9]);
                // v1.5.188：x/y/z 为区块 box 的 min 角（服务端 planRegion 下发），
                // 不再减半——旧版"中心"语义导致玩家站在实际建造区内却不被判定在
                // 区块内 → 区块内右击跳转不到对应区块详情页（反馈）
                // 实测九百八十五：三轴各向外放一格（接触面也算）。只有一格高的建筑
                // （地毯/水渠/平台）玩家永远进不去体积内，旧判据下连"站在上面"都不算
                // 在区块里 → 控制按钮永远不出现。
                if (p.m_123341_() >= x - 1 && p.m_123341_() <= x + w
                        && p.m_123342_() >= y - 1 && p.m_123342_() <= y + h
                        && p.m_123343_() >= z - 1 && p.m_123343_() <= z + d) {
                    return r[0]; // planId
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * v1.5.162：玩家是否处于建造区块内（v1.5.180：多区块——任一区块命中即 true）。
     * 控制按钮（续建/暂停/取消等）只在区块内显示——玩家在手册里看到区块范围，
     * 进到里面才有控制权。
     *
     * <p>实测九百八十五：口径改为「站在区块内 **或贴着它**（边界外一格）」——见
     * {@link #playerPlanId()}。
     */
    private boolean inPlanRegion() {
        return playerPlanId() != null;
    }

    /** v1.5.63：进度实时轮询——每 40 tick（2 秒）向服务端请求一次状态快照 */
    @Override
    public void m_86600_() {
        super.m_86600_();
        if (++this.tickCount % 40 == 0) {
            BlueprintBookNetworking.CHANNEL.sendToServer(
                    new BlueprintBookBuildPackets.BuildControlPacket(
                            BlueprintBookBuildPackets.BuildControlPacket.SHOW_PROGRESS, null));
        }
    }

    @Override
    protected void m_7856_() {
        super.m_7856_();
        this.rebuildButtons();
    }

    /** 物品 id → 本地化中文名 */
    private static String itemName(String itemId) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
            if (item != null) {
                String name = new ItemStack(item).m_41786_().getString();
                if (name != null && !name.isEmpty() && !name.startsWith("item.")) {
                    return name;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintBookScreen", ignored);
        }
        return BlueprintLib.cnName(itemId);
    }

    /** 持有量显示——极大值显示 ∞ */
    private static String haveText(int have) {
        return have >= 1_000_000 ? "\u221e" : String.valueOf(have);
    }

    /** v1.5.319：液体工具/周转行角标——水桶=工具（不消耗）、岩浆桶=周转（放置后
     *  返还空桶）；v1.5.320：矿车=启动消耗（完工自动放置，需备齐）。灰色小字与
     *  消耗材料区分（材料表里这几行不是普通消耗品）。 */
    private static String materialRowTag(String itemId) {
        if ("minecraft:water_bucket".equals(itemId)) {
            return "\u00a77（工具）";
        }
        if ("minecraft:lava_bucket".equals(itemId)) {
            return "\u00a77（返还）";
        }
        if ("minecraft:minecart".equals(itemId)) {
            return "\u00a77（工具·消耗）";
        }
        return "";
    }

    /** 材料文本（缺口黄色、充足绿色） */
    private List<String> materialItems(BlueprintBookBuildPackets.Entry entry) {
        List<String> items = new ArrayList<>();
        if (entry.materials() == null || entry.materials().isEmpty()) {
            items.add("\u00a7a材料充足");
        } else {
            for (String[] m : entry.materials()) {
                int have = Integer.parseInt(m[1]);
                int need = Integer.parseInt(m[2]);
                // v1.5.252y：剩余需求 ≤ 0（已建完）→ 不显示——不再出现"0/0"诡异数据
                if (need <= 0) {
                    continue;
                }
                String color = have >= need ? "\u00a7a" : "\u00a7e";
                items.add(color + itemName(m[0]) + materialRowTag(m[0])
                        + " " + haveText(have) + "/" + need);
            }
            if (items.isEmpty()) {
                items.add("\u00a7a材料充足");
            }
        }
        return items;
    }

    /** 材料页数（16 种/页 = 2 列 × 8 行） */
    private int materialPages(BlueprintBookBuildPackets.Entry entry) {
        int n = this.materialItems(entry).size();
        return Math.max(1, (n + MAT_COLS * MAT_ROWS_PER_PAGE - 1) / (MAT_COLS * MAT_ROWS_PER_PAGE));
    }

    /** 文本截断到屏幕宽度（进度行防左移/溢出） */
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

    /**
     * v1.5.111：标题类文本统一模板——drawCenteredString（m_280653_）以 x 为【圆心】
     * 居中：文本从 x-宽/2 画到 x+宽/2。旧版到处写 (W-文本宽)/2 再喂给 drawCenteredString
     * → 圆心在 (W-w)/2，整行又左移 w/2 → 长标题/页码偏出屏幕左缘（"所有标题都偏左"根因）。
     * 本方法先 fitText 截断到 W-20，再以屏幕中心 W/2 为圆心绘制——任何窗口宽度下完整居中可见。
     * 所有面板标题/提示/页码统一走这里，禁止再手写 (W-w)/2 圆心。
     */
    private void drawCentered(net.minecraft.client.gui.GuiGraphics graphics, String text, int y, int color) {
        String t = this.fitText(text, this.f_96543_ - 20);
        graphics.m_280653_(this.f_96547_, Component.m_237113_(t), this.f_96543_ / 2, y, color);
    }

    /** v1.5.279：区块列表占用的行数（标题 1 行 + 每区块 2 行(名字/状态+创建坐标)
     *  + 溢出省略行；上限 6 区块）——女仆管理页按钮据此下移，与 renderMaids 同步 */
    private int regionListRows() {
        if (this.buildRegions.isEmpty()) {
            return 0;
        }
        int n = Math.min(this.buildRegions.size(), 6);
        int rows = 1 + n * 2;
        if (this.buildRegions.size() > n) {
            rows++;
        }
        return rows;
    }

    // ================= v1.5.71 自适应布局计算 =================

    /** 内容区可用高度（标题之下 ~ 底部控制区之上） */
    private int contentH() {
        return Math.max(64, this.f_96544_ - CONTENT_TOP - BOTTOM_ZONE - 6);
    }

    /** 建造目录每页行数（按可用高度自适应，至少 3 行） */
    private int buildRowsPerPage() {
        return Math.max(3, this.contentH() / (NAME_BUTTON_H + 2));
    }

    /** v1.5.374：按搜索串过滤目录条目（名称 / 蓝图 id 包含匹配，大小写不敏感） */
    private java.util.List<BlueprintBookBuildPackets.Entry> filteredEntries() {
        String q = this.searchQuery == null ? "" : this.searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return this.entries;
        }
        java.util.List<BlueprintBookBuildPackets.Entry> out = new java.util.ArrayList<>();
        for (BlueprintBookBuildPackets.Entry e : this.entries) {
            if (e.name() != null && e.name().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                out.add(e);
            } else if (e.id() != null && e.id().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    /** v1.5.374：建造目录网格列数（宽窗口 3 列，窄窗口 2 列） */
    private int buildGridCols() {
        return this.f_96543_ >= 520 ? 3 : 2;
    }

    /** v1.5.374：建造目录网格每页行数（顶部搜索框占 24px，其余给条目按钮） */
    private int buildGridRowsPerPage() {
        int avail = this.f_96544_ - 30 - 72; // 底行按钮之上 - 搜索框之下
        return Math.max(3, avail / (NAME_BUTTON_H + 2));
    }

    /** 女仆面板每页行数（按可用高度自适应，至少 3 行） */
    private int maidRowsPerPage() {
        return Math.max(3, this.contentH() / MAID_ROW_H);
    }

    /** v1.5.82：MC 经验条纹理（textures/gui/bars.png：v=64 亮绿进度、v=69 暗底） */
    private static final net.minecraft.resources.ResourceLocation BARS =
            new net.minecraft.resources.ResourceLocation("minecraft", "textures/gui/bars.png");

    /**
     * v1.5.83：进度显示——MC 标准经验条纹理（182×5 居中）+ 文本（自动换行居中）。
     * v1.5.84：进度可超过 100%（真实值）——超过时文本与百分比染红警示"超料"。
     * 位置：大目录贴底；其他视图内容区底部（翻页/控制按钮之上）。
     */
    private void renderProgress(net.minecraft.client.gui.GuiGraphics graphics, int textY) {
        boolean hasText = !this.progressText.isEmpty();
        boolean hasBar = this.progressPct >= 0;
        if (!hasText && !hasBar) {
            return;
        }
        // v1.5.84：超过 100% = 超料 → 文本/百分比红色警示
        boolean over = this.progressPct > 100;
        int usedLines = 0;
        if (hasText) {
            // v1.5.102c：进度文字整体右对齐（要求"向右移一大步"——结尾的
            // 【全局暂停中】/速度 等字样从中间跳到最右侧）；进度条仍居中
            // v1.5.252u：分行显示（\n 分隔），返回实际行数，进度条随之自适应下移
            usedLines = this.drawWrapped(graphics, (over ? "\u00a7c" : "\u00a7b") + this.progressText,
                    this.f_96543_ - 10, textY, this.f_96543_ - 20,
                    over ? 0xFF5555 : 0xAAAAAA, 4, false, true);
        }
        if (hasBar) {
            int barY = textY + Math.max(usedLines, 1) * 10 + 10;
            int barW = 182;
            int barX = (this.f_96543_ - barW) / 2;
            // MC 经验条纹理：暗底 + 亮绿进度（v=69 底 / v=64 进度，182×5）
            graphics.m_280218_(BARS, barX, barY, 0, 69, barW, 5);
            int fillW = Math.max(0, Math.min(barW, this.progressPct * barW / 100));
            if (fillW > 0) {
                graphics.m_280218_(BARS, barX, barY, 0, 64, fillW, 5);
            }
            // v1.5.252s：进度条右侧 = 百分比 · 速度(块/秒) · 预计完成时间——
            // 超宽时左移钳制（绝不顶出屏幕右缘）
            String label = (over ? "\u00a7c" : "\u00a7a") + this.progressPct + "%";
            double bps = 0;
            try {
                bps = this.speedBps.isEmpty() ? 0 : Double.parseDouble(this.speedBps);
            } catch (NumberFormatException ignored) {
            }
            // v1.5.278：速度/预计【无条件显示】——旧版 bps≤0.01 整个不显示
            // （缺料停滞时 ema→0 → 进度条旁只剩"56%"，反馈："搭建速度和剩余
            // 时间看不到"，截图实证 speed=0.0 eta=-1）。停滞时如实显示
            // "0.0块/秒 · 预计--"（-- = 无法估计：缺料/刚启动统计窗口内），
            // 有速度时正常显示实测值
            if (bps > 0.01) {
                label += " \u00a77\u00b7 " + fmtBps(bps) + " \u00b7 \u9884\u8ba1" + fmtEta(this.etaSec);
            } else {
                label += " \u00a77\u00b7 0.0\u5757/\u79d2 \u00b7 \u9884\u8ba1" + fmtEta(this.etaSec);
            }
            net.minecraft.client.gui.Font font = this.f_96547_;
            int labelW = font.m_92895_(label);
            int lx = Math.min(barX + barW + 4, this.f_96543_ - labelW - 4);
            // v1.5.252y：label 上移到进度条上方 9px（旧版 barY-1 起点 → 文字底部
            // 压进度条 6px，截图实证"字样跟进度条重叠"）
            graphics.m_280137_(font, label, lx, barY - 9, over ? 0xFF5555 : 0xFFFFFF);
        }
    }

    /** v1.5.252s：块/秒（慢速显示一位小数） */
    private static String fmtBps(double bps) {
        return bps < 10 ? String.format("%.1f", bps) + "\u5757/\u79d2"
                : String.format("%.0f", bps) + "\u5757/\u79d2";
    }

    /** v1.5.252s：预计完成时间（秒 → 小时/分/秒；-1 = 未知） */
    private static String fmtEta(int eta) {
        if (eta < 0) {
            return "--";
        }
        if (eta >= 3600) {
            return (eta / 3600) + "\u5c0f\u65f6" + ((eta % 3600) / 60) + "\u5206";
        }
        if (eta >= 60) {
            return (eta / 60) + "\u5206" + (eta % 60) + "\u79d2";
        }
        return eta + "\u79d2";
    }

    /** v1.5.82：按宽度自动换行的文本绘制（最多 maxLines 行；center=true 每行居中，
     *  right=true 每行右对齐到 x（优先于居中））。v1.5.102c：进度文字走右对齐。
     *  v1.5.252t：右对齐改为 drawString 左锚点（旧版以 x-lw 为圆心 → 右缘在 x-lw/2，
     *  长文本偏左悬空）；换行截断处补"…"（不再无声丢内容）。
     *  v1.5.252u：支持 \n 分段（每段独立右对齐/换行，分行显示）；返回实际绘制行数 */
    private int drawWrapped(net.minecraft.client.gui.GuiGraphics graphics, String text,
                            int x, int y, int maxWidth, int color, int maxLines,
                            boolean center, boolean right) {
        net.minecraft.client.gui.Font font = this.f_96547_;
        int line = 0;
        for (String seg : text.split("\n", -1)) {
            if (seg.isEmpty()) {
                continue;
            }
            String remaining = seg;
            while (!remaining.isEmpty() && line < maxLines) {
                if (font.m_92895_(remaining) <= maxWidth) {
                    int lw = font.m_92895_(remaining);
                    if (right) {
                        graphics.m_280137_(font, remaining, Math.max(2, x - lw),
                                y + line * 10, color); // 左锚点：右缘恰好在 x
                    } else {
                        int lineX = center ? this.f_96543_ / 2 : x;
                        graphics.m_280653_(font, Component.m_237113_(remaining), lineX,
                                y + line * 10, color);
                    }
                    line++;
                    break;
                }
                int ell = font.m_92895_("\u2026");
                int cut = remaining.length();
                while (cut > 0 && font.m_92895_(remaining.substring(0, cut)) > maxWidth - ell) {
                    cut--;
                }
                if (cut <= 0) {
                    break;
                }
                String lineText = remaining.substring(0, cut) + "\u2026"; // 截断补省略号
                int lw = font.m_92895_(lineText);
                if (right) {
                    graphics.m_280137_(font, lineText, Math.max(2, x - lw), y + line * 10, color);
                } else {
                    int lineX = center ? this.f_96543_ / 2 : x;
                    graphics.m_280653_(font, Component.m_237113_(lineText), lineX,
                            y + line * 10, color);
                }
                remaining = remaining.substring(cut).trim();
                line++;
            }
            if (line >= maxLines) {
                break;
            }
        }
        return line;
    }

    /** 实测九百七十一：把逻辑行按像素宽折成"视觉行"——长记忆 / 记忆日记 / 工作笔记不再被一行截断。
     *  渲染与分页共用同一份结果（页码因此与实际可见行严格一致）；折行时保留行首颜色码，续行不丢色。 */
    private java.util.List<String> wrapLines(java.util.List<String> logical, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (logical == null || maxWidth < 8) {
            return out;
        }
        net.minecraft.client.gui.Font font = this.f_96547_;
        for (String raw : logical) {
            if (raw == null || raw.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder codes = new StringBuilder();
            for (int i = 0; i + 1 < raw.length() && raw.charAt(i) == '\u00a7'; i += 2) {
                codes.append(raw, i, i + 2);
            }
            String remaining = raw.trim();
            while (!remaining.isEmpty()) {
                if (font.m_92895_(remaining) <= maxWidth) {
                    out.add(remaining);
                    break;
                }
                int cut = remaining.length();
                while (cut > 0 && font.m_92895_(remaining.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                if (cut <= 0) {
                    out.add(remaining);
                    break;
                }
                String chunk = remaining.substring(0, cut);
                if (chunk.endsWith("\u00a7")) { // 别把颜色码劈成两半
                    chunk = chunk.substring(0, chunk.length() - 1);
                    cut--;
                }
                if (chunk.isEmpty()) {
                    out.add(remaining);
                    break;
                }
                out.add(chunk);
                remaining = codes + remaining.substring(cut).trim();
            }
        }
        return out;
    }

    /** 实测九百七十一：链路调试状态区的**唯一**内容源（状态行 + 选中某行时追加的全文段）。
     *  渲染与按钮分页都走它，页码才不会和实际可见行打架。 */
    private java.util.List<String> debugStatusSource() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (this.debugStatusLines != null) {
            out.addAll(this.debugStatusLines);
        }
        if (this.debugStatusOpen) {
            String[] r = this.debugSelRow();
            if (r != null) {
                String st = this.debugSelState();
                String mark = "deleted".equals(st) ? "\uff08\u5df2\u4f5c\u5e9f\uff09" : ("inactive".equals(st) ? "\uff08\u5df2\u505c\u7528\uff09" : "");
                out.add("\u00a7e\u25b7 \u9009\u4e2d\u7684\u8fd9\u6761\u00a77\uff08" + r[2] + "\uff09" + mark + "\u00a77\uff1a");
                out.add("§f" + r[3]);
            }
        }
        return out;
    }

    /** 实测九百七十六：第二行——按选中项写明"这一条能做什么"（旧版只罗列按钮名，
     *  「停用关系 / 恢复关系」到底干嘛没人看得懂） */
    private String debugContextHint() {
        String[] r = this.debugSelRow();
        int relCount = this.debugRelCount();
        if (r == null) {
            int n = this.debugRows == null ? 0 : this.debugRows.size();
            return "\u00a77\u70b9\u4e00\u884c\u9009\u4e2d \u2192 \u7528\u4e0b\u9762\u7684\u6309\u94ae\u6539\u8fd9\u4e00\u6761\uff08\u5171 " + n + " \u6761 \u00b7 \u5173\u7cfb " + relCount + " \u6761 \u00b7 \u72b6\u6001\u5f00\u5173\u5728\u5de6\u4e0b \u00b7 \u23ee\u23ed \u8df3\u9996\u9875\u672b\u9875\uff09";
        }
        if ("rel".equals(r[0])) {
            return "inactive".equals(this.debugSelState())
                    ? "\u00a77\u8fd9\u6bb5\u5173\u7cfb\u5df2\u505c\u7528\uff1a\u70b9\u300c\u542f\u7528\u5173\u7cfb\u300d\u5979\u5c31\u91cd\u65b0\u60f3\u8d77\u5b83"
                    : "\u00a77\u8fd9\u6bb5\u5173\u7cfb\u6709\u6548\uff1a\u505c\u7528\u53ea\u662f\u8ba9\u5979\u4e0d\u518d\u60f3\u8d77\u5b83\uff0c\u8bb0\u5f55\u4fdd\u7559\u3001\u968f\u65f6\u53ef\u542f\u7528";
        }
        if ("deleted".equals(this.debugSelState())) {
            return "\u00a77\u8fd9\u6761\u8bb0\u5fc6\u5df2\u4f5c\u5e9f\uff1a\u70b9\u300c\u6062\u590d\u300d\u5979\u5c31\u53c8\u80fd\u60f3\u8d77\u5b83";
        }
        // 实测九百七十七：记忆行被选中时讲清「关系操作」为什么是灰的、什么时候才亮
        String why = relCount > 0
                ? "\u00a77 \u00b7 \u4e0b\u9762\u90a3\u9897\u300c\u8df3\u5230\u5173\u7cfb\u300d\u53ef\u76f4\u8fbe"
                : "\u00a77 \u00b7 \u6682\u65f6\u8fd8\u6ca1\u6709\u5173\u7cfb\u6761\u76ee\uff0c\u591a\u548c\u5979\u76f8\u5904\uff08\u63d0\u5347\u597d\u611f\u7b49\u7ea7\u3001\u89e6\u53d1\u5173\u7cfb\u4e8b\u4ef6\uff09\u540e\u4f1a\u51fa\u73b0";
        return "\u00a77\u8fd9\u6761\u8bb0\u5fc6\uff1a\u2191\u2193\u6539\u91cd\u8981\u5ea6\u3001\u5f3a\u5316\u524a\u5f31\u8c03\u6743\u91cd\uff1b\u4f5c\u5e9f\u540e\u4e0d\u518d\u88ab\u60f3\u8d77\uff08\u53ef\u6062\u590d\uff09"
                + "\u00a77\u3002\u300c\u5173\u7cfb\u64cd\u4f5c\u300d\u53ea\u5bf9\u5e26\u7d2b\u6807\u8bb0\u7684\u5173\u7cfb\u884c\u751f\u6548\uff08\u672c\u9875 " + relCount + " \u6761\uff09" + why;
    }

    /** 实测九百七十六：第三行——上一步回执优先；否则显示选中那条的全文（单行截断）；
     *  都没有时显示刷新时刻。旧版这行画在 y=54，与状态首行（y=56）叠在一起。 */
    private String debugTopLine() {
        if (this.debugNote != null) {
            return "\u00a7a\u4e0a\u4e00\u6b65\uff1a\u00a7f" + this.debugNote;
        }
        String[] r = this.debugSelRow();
        if (r != null) {
            return "\u00a7e\u25b7 \u9009\u4e2d\u7684\u8fd9\u6761\uff1a\u00a77" + this.fitText(r[3], this.f_96543_ - 40);
        }
        return this.debugStamp != null ? "\u00a77\u6570\u636e " + this.debugStamp + " \u5237\u65b0" : "";
    }

    /** 实测九百七十四：选中项在列表里的下标（按稳定键查；没选中或那条已消失 → -1） */
    private int debugSelIndex() {
        if (this.debugSelKey == null || this.debugRows == null) {
            return -1;
        }
        for (int i = 0; i < this.debugRows.size(); i++) {
            if (this.debugSelKey.equals(this.debugRows.get(i)[1])) {
                return i;
            }
        }
        return -1;
    }

    /** 选中行（没选中返回 null） */
    private String[] debugSelRow() {
        int i = this.debugSelIndex();
        return i < 0 ? null : this.debugRows.get(i);
    }

    /** 选中行状态码（空 / deleted / inactive） */
    private String debugSelState() {
        String[] r = this.debugSelRow();
        return r == null || r.length < 5 || r[4] == null ? "" : r[4];
    }

    private void rebuildButtons() {
        this.m_169413_(); // clearWidgets
        // v1.5.252ad：每次重建按钮清空空状态提示——旧版 graphicsHint 只设置不清空，
        // 女仆加入/条件满足后残留提示仍显示（截图实证：名单页有女仆仍显示
        // "该区块没有女仆"）；各视图按钮构建时按条件重新设置
        this.maidEmptyText = null;
        // v1.5.390 修复：没有蓝图不再"挡死整本手册"——旧版 entries 为空时直接渲染
        // 一个"没有可用蓝图"按钮并 return，导致首页五个入口（详细介绍/建造/女仆管理/
        // 模组详细配置/女仆记忆）全部不可达，干净安装的玩家根本进不了手册其他功能。
        // 现在无论有无蓝图都正常进各视图；空蓝图改由建造目录页内部提示。
        switch (this.view) {
            case VIEW_BUILD -> this.buildViewButtons();
            case VIEW_MAIDS -> this.maidsViewButtons();
            case VIEW_MAID_DETAIL -> this.maidDetailViewButtons();
            case VIEW_REGION_MAIDS -> this.regionMaidsViewButtons();
            case VIEW_MEMORY -> this.memoryViewButtons();
            case VIEW_PRESET -> this.presetViewButtons();
            default -> this.homeViewButtons();
        }
    }

    // ================= v1.5.374：搜索框输入转发 + 目录网格坐标点击 =================

    /** 字符输入直接转发给搜索框（不依赖 getFocused() 焦点链，同 PromaidConfigScreen） */
    @Override
    public boolean m_5534_(char codePoint, int modifiers) {
        if (this.view == VIEW_BUILD && this.viewingEntry == null
                && this.searchBox != null && this.searchBox.m_5534_(codePoint, modifiers)) {
            return true;
        }
        return super.m_5534_(codePoint, modifiers);
    }

    /** 按键转发给搜索框（退格/方向/回车等） */
    @Override
    public boolean m_7933_(int key, int scanCode, int modifiers) {
        if (this.view == VIEW_BUILD && this.viewingEntry == null
                && this.searchBox != null && this.searchBox.m_7933_(key, scanCode, modifiers)) {
            return true;
        }
        return super.m_7933_(key, scanCode, modifiers);
    }

    /** 目录页点击：搜索框聚焦兜底 + 条目（打开详情）/删除（）坐标命中 */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0 && this.view == VIEW_BUILD && this.viewingEntry == null && this.searchBox != null) {
            if (this.searchBox.m_5953_(mouseX, mouseY)) {
                this.searchBox.m_93692_(true);
            } else if (mouseY >= 72 && mouseY <= this.f_96544_ - 30) {
                java.util.List<BlueprintBookBuildPackets.Entry> list = this.filteredEntries();
                int cols = this.buildGridCols();
                int rows = this.buildGridRowsPerPage();
                int perPage = cols * rows;
                int start = this.buildPage * perPage;
                int margin = 10;
                int gap = 4;
                int cellW = (this.f_96543_ - margin * 2 - (cols - 1) * gap) / cols;
                int colIdx = (int) ((mouseX - margin) / (cellW + gap));
                int rowIdx = (int) ((mouseY - 72) / (NAME_BUTTON_H + 2));
                if (colIdx >= 0 && colIdx < cols && rowIdx >= 0 && rowIdx < rows) {
                    int idx = start + rowIdx * cols + colIdx;
                    if (idx >= 0 && idx < list.size()) {
                        BlueprintBookBuildPackets.Entry entry = list.get(idx);
                        boolean ext = entry.id().startsWith("maid_smart_ext:");
                        if (ext) {
                            int delW = 14;
                            double dx = mouseX - (margin + colIdx * (cellW + gap) + cellW - delW);
                            if (dx >= 0 && dx < delW) {
                                final String deleteId = entry.id();
                                this.confirmAction("删除蓝图？",
                                        "\u00a7e\u300c" + entry.name() + "\u300d将从手册中删除（文件也会被移除，无法恢复）",
                                        "\u00a7c确认删除",
                                        () -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                                new BlueprintBookEntityPackets.DeleteBlueprintPacket(deleteId)));
                                return true;
                            }
                        }
                        this.viewingEntry = entry;
                        this.matPage = 0;
                        this.rebuildButtons();
                        return true;
                    }
                }
            }
        }
        return super.m_6375_(mouseX, mouseY, button);
    }

    // ================= 大目录页 =================
    private void homeViewButtons() {
        int cx = this.f_96543_ / 2;
        int h = this.f_96544_;
        int buildCount = this.entries == null ? 0 : this.entries.size();
        int maidCount = this.maids == null ? 0 : this.maids.size();
        // v1.5.252h：入口数 + 底部进度条防重叠——旧版 4 按钮（64/100/136/172
        // 高 28 间距 36）加第 5 个会压住底部进度条（renderHome 文字 h-40）。
        // 行高按窗口高自适应压缩（照 PromaidConfigScreen 目录页模式），
        // 起始 y 上浮保证按钮组底 ≤ h-44（进度文字顶之上 4px）。
        // v1.5.396 实测八百三十五：第 6 个入口（DIY / 默认模式）——行高从 28 起逐格压，
        // 压到"从 y0=52 起 6 行仍不越过 h-44"为止（小窗口不会再压住底部）。
        int bw = 170;
        int rowH = 28;
        while (rowH > 9 && HOME_ENTRIES * rowH - 3 + 52 > h - 44) {
            rowH--;
        }
        int bh = Math.max(12, rowH - 3);
        int gap = rowH - bh;
        int y0 = Math.max(52, Math.min(64, h - rowH * HOME_ENTRIES - 44));
        // v1.5.252h：详细介绍放目录【最上面】（反馈"放最下面怪怪的"）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a76\u25c6 详细介绍"),
                        b -> com.maidsmart.guide.GuideScreen.open(this))
                .m_252987_(cx - bw / 2, y0, bw, bh).m_253136_());
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7e\u26f0 建造（" + buildCount + " 个建筑）"),
                        b -> {
                            this.view = VIEW_BUILD;
                            this.rebuildButtons();
                        })
                .m_252987_(cx - bw / 2, y0 + rowH, bw, bh).m_253136_());
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7b\u2640 女仆管理（" + maidCount + " 只）"),
                        b -> {
                            this.view = VIEW_MAIDS;
                            this.maidPage = 0;
                            this.rebuildButtons();
                        })
                .m_252987_(cx - bw / 2, y0 + rowH * 2, bw, bh).m_253136_());
        // v1.5.88：模组详细配置（打开 Promaid 配置面板，返回时回到手册）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7d\u2699 模组详细配置"),
                        b -> Minecraft.m_91087_().m_91152_(
                                new com.maidsmart.config.PromaidConfigScreen(this)))
                .m_252987_(cx - bw / 2, y0 + rowH * 3, bw, bh).m_253136_());
        // v1.5.95：女仆记忆查看（看女仆记得什么）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7a\u2661 女仆记忆"),
                        b -> {
                            this.view = VIEW_MEMORY;
                            this.memoryLines = null;
                            this.rebuildButtons();
                        })
                .m_252987_(cx - bw / 2, y0 + rowH * 4, bw, bh).m_253136_());
        // v1.5.396 实测八百三十五：DIY / 默认模式（DIY ↔ 默认；改的是全模组配置）
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7c\u2691 DIY / 默认模式"),
                        b -> {
                            this.view = VIEW_PRESET;
                            this.rebuildButtons();
                        })
                .m_252987_(cx - bw / 2, y0 + rowH * 5, bw, bh).m_253136_());
    }

    // ================= v1.5.396 实测八百三十五：DIY / 默认模式页 =================

    /**
     * 预设模式页：两个按钮 = 两套配置。
     *
     * <p>DIY = 关掉全部"介入女仆行为"的开关（像 TLM 原版），保留纯附加子系统与护栏；
     * 默认 = 逐项恢复出厂推荐配置（AI 接口密钥与手工数据表保留）。两者都带二次确认，
     * 因为一次会改动上百项配置。
     */
    private void presetViewButtons() {
        int cx = this.f_96543_ / 2;
        int h = this.f_96544_;
        int bw = Math.min(300, Math.max(170, this.f_96543_ - 40));
        int y0 = Math.max(92, h / 2 - 6);
        this.presetPending = com.maidsmart.config.MaidConfigPreset.planDiy().size();
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7c\u2691 DIY 模式（关闭全部介入，像 TLM 原版）"),
                        b -> this.confirmAction(
                                "切到 DIY 模式？",
                                "\u00a7e只关闭本模组对「TLM 女仆原有行为」的改动：战斗自保与单兵战术、主动参战与援护、"
                                        + "自动换装、飞行跟随、搭路、拆刷怪笼、巡逻、排班接管、跟随收紧、"
                                        + "移速上限与压档、拾取、传送与拉回、女仆复活、危险方块绕行等。\n\n"
                                        + "\u00a7a新任务与新物品**不受 DIY 影响**（与默认模式逐项相同）："
                                        + "挖矿 / 伐木 / 建造 / 扫帚 / 宰杀 / 空袭（含空中接敌机动），"
                                        + "以及压缩盒、武装拴绳、骑乘指挥棒、饰品「大运」。\n\n"
                                        + "\u00a77保留：AI 记忆与对话、感知与情绪、语音、手册、指标石与投影、冷却 HUD，"
                                        + "以及几处安全护栏（不拆箱子、不搭在主人身上、卡住自愈、传送后重建）。\n\n"
                                        + "\u00a77效果：不碰新任务与新物品时，体验与 TLM 原版一致；想用就照常用。"
                                        + "立即生效、随时可切回「默认模式」。之后到"
                                        + "「模组详细配置」里单独打开。\n\n"
                                        + "\u00a77大模式都有自己的「总开关」，就在它自己板块的第一条——"
                                        + "扫帚＝「移动与行为 → 扫帚模式」，空袭＝「战斗与自保 → 空袭数值」"
                                        + "（叫「空袭·总开关（飞行作战）」）。",
                                "\u00a7c确认切到 DIY",
                                () -> this.presetApply(true)))
                .m_252987_(cx - bw / 2, y0, bw, 20).m_253136_());
        this.m_142416_(Button.m_253074_(
                        Component.m_237113_("\u00a7a\u2714 默认模式（恢复出厂推荐配置）"),
                        b -> this.confirmAction(
                                "恢复默认模式？",
                                "\u00a7e会把全部配置一次性恢复为出厂默认值（含你手动改过的开关与数值），"
                                        + "包括挖矿伐木的速度、各功能半径、战斗参数等。\n\n"
                                        + "\u00a77保留：AI 接口密钥与端点（你的钥匙不会被清掉），"
                                        + "以及你自己维护的数据表（矿表、木材表、危险方块表、白名单等）。\n\n"
                                        + "\u00a77立即生效。",
                                "\u00a7a确认恢复默认",
                                () -> this.presetApply(false)))
                .m_252987_(cx - bw / 2, y0 + 26, bw, 20).m_253136_());
        // 返回大目录（左上角小按钮，与其它子页一致）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 大目录"),
                        b -> {
                            this.view = VIEW_HOME;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
    }

    /** 执行预设切换（客户端改配置；返回后刷新本页与聊天回执） */
    private void presetApply(boolean diy) {
        int changed = diy
                ? com.maidsmart.config.MaidConfigPreset.applyDiy()
                : com.maidsmart.config.MaidConfigPreset.applyFactoryDefault();
        this.rebuildButtons();
        Minecraft mc = Minecraft.m_91087_();
        if (mc != null && mc.f_91074_ != null) {
            if (diy) {
                mc.f_91074_.m_213846_(Component.m_237113_(
                        "\u00a7a[Promaid] 已应用 DIY 模式（改动 " + changed + " 项）：本模组不再介入女仆行为——"
                                + "要哪项加强，到「模组详细配置」对应板块里单独打开"));
            } else {
                mc.f_91074_.m_213846_(Component.m_237113_(
                        "\u00a7a[Promaid] 已恢复默认模式（改动 " + changed + " 项）：按出厂推荐配置运行"));
            }
        }
    }

    /** 预设模式页渲染 */
    private void renderPreset(net.minecraft.client.gui.GuiGraphics graphics) {
        boolean diy = com.maidsmart.config.MaidConfigPreset.isDiy();
        int pending = Math.max(0, this.presetPending);
        this.drawCentered(graphics, "\u00a7e\u2691 DIY / 默认模式", 20, 0xFFFFFF);
        this.drawCentered(graphics, "\u00a77给本模组换一套设定：DIY = 只关掉「介入女仆原有行为」的改动"
                + "（新任务 / 新物品照常）；默认 = 出厂推荐配置", 36, 0x888888);
        this.drawCentered(graphics, diy ? "\u00a7b当前模式：DIY 模式" : "\u00a7b当前模式：默认模式",
                52, 0x55FFFF);
        this.drawCentered(graphics, diy
                        ? "\u00a77已应用 DIY：不再改动女仆原有行为；新任务与新物品照常可用"
                        : "\u00a77当前为默认模式：本模组按出厂推荐配置运行（全部功能可用）",
                64, 0x999999);
        this.drawCentered(graphics, "\u00a7cDIY 模式会改动 " + pending + " 项配置", 78, 0xFF8888);
    }

    // ================= 建造面板 =================
    private void buildViewButtons() {
        // 建筑详情页（v1.5.159：点目录里的建筑进入——原来所有建造相关 UI 都在这里）
        if (this.viewingEntry != null) {
            int h = this.f_96544_;
            int w = this.f_96543_;
            int cx = this.f_96543_ / 2;
            final String vid = this.viewingEntry.id();
            // v1.5.160：是否有进行中的建造计划（progressPct >= 0）——控制按钮动态显示：
            // 无计划时底部只有 返回/建造此图纸/区块显示
            // v1.5.162：续建/暂停等控制按钮还要求【玩家处于建造区块内】——区块范围由
            // 服务端下发（regionX/Y/Z + W/H/D，2 秒轮询刷新）；玩家进到区块内才有控制权
            // v1.5.171：取消区块内限制——反馈"继续建造按钮消失、走到区块内也看不到"
            //（计划在远处/地下矿洞时玩家根本找不到区块 → 无法取消/继续 = 死锁）；
            // 改为【有计划就显示控制按钮】，红色区块框仍由服务端推送指示计划位置
            boolean hasPlan = this.progressPct >= 0;
            boolean inRegion = hasPlan && this.inPlanRegion();
            // v1.5.188b：区块控制一体化——【调区块 = 先显示区块】固定流程：
            // 站在区块内时，全员加入/名单/暂停/速度/取消等按钮全部先关掉手册、打开
            // 区块显示（红色固定框 + 提示文字）；重开手册后按钮恢复。保证玩家每一步
            // 都能看到区块实际范围（防误操作），也是"建造确认流程"的前置。
            if (inRegion) {
                com.maidsmart.build.BlueprintAreaPreview.ensureShown();
            }
            // v1.5.190 修复：按钮按可用宽度自适应均分（最小 54px），任意窗口宽度
            // 都完整在屏内。
            // v1.1.0 实测九十五：「区块显示」按钮删除（金色预览功能整体下线）——
            // 建造投影改由红色区块框 + 幽灵方块在建造期间持续显示（服务端每秒同步）。
            // v1.1.0 实测九十七：底部按钮 = 返回 + [全员加入 + 名单] + 建造此图纸
            // + 转向键位（后三者中名单仅区块内）
            int btnCount = inRegion ? 5 : 3;
            int avail = w - 16;
            int gap2 = 4;
            int[] bw = new int[btnCount];
            int need = (btnCount - 1) * gap2;
            for (int i = 0; i < btnCount; i++) {
                bw[i] = Math.max(54, (avail - need) / btnCount);
            }
            int total5 = need + bw[0] * btnCount;
            int start5 = Math.max(8, cx - total5 / 2);
            if (start5 + total5 > w - 8) {
                start5 = w - 8 - total5; // 兜底：右缘对齐，绝不超出屏幕
            }
            int x = start5;
            // 返回目录（总是显示）
            this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回目录"),
                            b -> {
                                this.viewingEntry = null;
                                this.rebuildButtons();
                            })
                    .m_252987_(x, h - 30, bw[0], 20).m_253136_());
            x += bw[0] + gap2;
            // 全员加入 + 名单（v1.5.178 区块内限制；v1.5.188b：区块内自动先显示区块框）
            if (inRegion) {
                final String cid = this.currentPlanId;
                this.m_142416_(Button.m_253074_(Component.m_237113_("全员加入"),
                                b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookBuildPackets.BuildControlPacket(
                                                BlueprintBookBuildPackets.BuildControlPacket.JOIN_ALL, null, cid)))
                        .m_252987_(x, h - 30, bw[1], 20).m_253136_());
                x += bw[1] + gap2;
                // v1.5.182：名单——区块内右击手册时额外多出的按钮：查看绑定该区块的
                // 女仆名单 + 设置工头（一区块一工头）
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e名单"),
                                b -> {
                                    this.view = VIEW_REGION_MAIDS;
                                    this.regionMaidPage = 0;
                                    this.rebuildButtons();
                                })
                        .m_252987_(x, h - 30, bw[2], 20).m_253136_());
                x += bw[2] + gap2;
            }
            // 建造此图纸（v1.1.0 实测九十五：两步确认流程简化——直接弹确认框；
            // 明确告知会摧毁周边障碍物、以玩家脚下为中心，确认后才真正创建区块。
            // 创建后红色区块框 + 蓝图投影在工地持续显示，位置不满意可取消重摆）
            this.m_142416_(Button.m_253074_(Component.m_237113_("建造此图纸"),
                            b -> this.startBuildFlow(vid))
                    .m_252987_(x, h - 30, bw[inRegion ? 3 : 1], 20).m_253136_());
            x += bw[inRegion ? 3 : 1] + gap2;
            // v1.1.0 实测九十七：转向键位——跳转【原版按键设置】界面（选项→按键绑定
            // 的同一套 UI），玩家可把"建造预览·旋转朝向"（默认 P）改成任意按键，
            // 操作方式与调整原版按键完全一致；完成后点"完成"返回本页
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a79\u8f6c\u5411\u952e\u4f4d"),
                            b -> {
                                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                                mc.m_91152_(new net.minecraft.client.gui.screens.controls.KeyBindsScreen(
                                        this, mc.f_91066_));
                            })
                    .m_252987_(x, h - 30, bw[inRegion ? 4 : 2], 20).m_253136_());
            // 材料翻页（16 种/页；固定在材料区下方，不与底部控制区冲突）
            // v1.1.0 实测二十五：80 宽按钮盖材料行——改 20 宽纯箭头 ◀/▶
            int pages = this.materialPages(this.viewingEntry);
            this.matPage = Math.min(this.matPage, pages - 1);
            if (pages > 1) {
                int matY = CONTENT_TOP + MAT_ROWS_PER_PAGE * MAT_LINE_H + 8;
                if (this.matPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                    b -> {
                                        this.matPage--;
                                        this.rebuildButtons();
                                    })
                            .m_252987_(cx - 40, matY, 20, 16).m_253136_());
                }
                if (this.matPage < pages - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                    b -> {
                                        this.matPage++;
                                        this.rebuildButtons();
                                    })
                            .m_252987_(cx + 20, matY, 20, 16).m_253136_());
                }
            }
            // v1.5.160：行2 控制按钮——仅进行中的计划存在时显示；开始建造后自动出现
            // v1.5.178：重新引入区块内限制——暂停/继续/取消/速度只在玩家站在建造
            // 区块内时显示（区块外无法对该区块做任何控制；红色区块框指引位置）
            if (inRegion) {
                this.addControlButtons();
            }
            return;
        }
        // v1.5.159：建造总目录（点击进入建筑详情页）
        // v1.5.374：加搜索框 + 网格多列显示——条目改为【渲染 + 坐标点击】（不再每键
        // 重建按钮，避免中文输入法被打断 / 焦点丢失），搜索框只建一次
        java.util.List<BlueprintBookBuildPackets.Entry> list = this.filteredEntries();
        if (list.isEmpty()) {
            this.maidEmptyText = "没有可用蓝图——把 .nbt/.litematic/.schem 图纸放进 config/maid_smart/blueprints/ 或存档 schematics/";
        }
        int cols = this.buildGridCols();
        int rows = this.buildGridRowsPerPage();
        int perPage = cols * rows;
        int totalPages = Math.max(1, (list.size() + perPage - 1) / perPage);
        this.buildPage = Math.min(this.buildPage, totalPages - 1);
        this.searchBox = new net.minecraft.client.gui.components.EditBox(this.f_96547_,
                this.f_96543_ / 2 - 150, 50, Math.min(300, this.f_96543_ - 20), 16,
                Component.m_237113_("搜索建筑名称…"));
        this.searchBox.m_94199_(64);
        // 实测九百八十五：改回**有边框**（v1.5.376 的无边框只解决了"深色字看不清"，
        // 代价是整框看起来不存在——空值时只有一个提示串飘在标题下面，玩家找不到搜索框）。
        // 边框 + 白字：原版边框自带浅灰描边与深底，任何面板底色上都看得出是一个输入框。
        this.searchBox.m_94182_(true);
        this.searchBox.m_94202_(0xFFFFFF);
        this.searchBox.m_257771_(Component.m_237113_("搜索建筑名称…"));
        this.searchBox.m_94144_(this.searchQuery);
        this.searchBox.m_94151_(s -> {
            this.searchQuery = s;
            this.buildPage = 0;
        });
        this.m_142416_(this.searchBox);
        // 翻页（底行）——v1.5.159：目录页只有 返回/上一页/下一页/退出
        // v1.1.0 实测二十五：100 宽按钮盖底部建筑名——改 20 宽纯箭头，页码画中间
        if (totalPages > 1) {
            if (this.buildPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.buildPage--;
                                    this.rebuildButtons();
                                }).m_252987_(this.f_96543_ / 2 - 40, this.f_96544_ - 30, 20, 20).m_253136_());
            }
            if (this.buildPage < totalPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.buildPage++;
                                    this.rebuildButtons();
                                }).m_252987_(this.f_96543_ / 2 + 20, this.f_96544_ - 30, 20, 20).m_253136_());
            }
        }
        // 返回大目录（左上角小按钮）
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 大目录"),
                        b -> {
                            this.view = VIEW_HOME;
                            this.viewingEntry = null;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        // v1.5.290：建造名单页直达女仆管理（反馈："名单内部仍然没有跳转按键"）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7b\u2640 女仆管理"),
                        b -> {
                            this.view = VIEW_MAIDS;
                            this.maidPage = 0;
                            this.rebuildButtons();
                        })
                .m_252987_(94, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
        // v1.5.220：导入建筑（右上角小按钮）——版本警告确认 → 文件选择器 → 服务端导入
        // v1.5.224：右上角并排两个导入按钮（导入建筑 / 导入世界地图）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7b\u2b06 导入建筑"),
                        b -> this.confirmAction("导入前请确认版本",
                                "\u00a7e请先确认：你要导入的蓝图文件与当前 MC 版本（1.20.1）一致。\n"
                                        + "版本不符的文件（如其他版本导出的结构/存档）导入后可能出现建筑损毁"
                                        + "（方块错位、状态丢失、无法解析）。\n\n"
                                        + "确认来源版本一致后再继续导入。",
                                "\u00a7a继续导入",
                                this::pickAndImportBuild))
                .m_252987_(this.f_96543_ - 184, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
        // v1.5.224：导入世界地图——.zip 世界存档/建筑包，自动提取建筑（锚点 ±256、排除地形）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e\u2b06 导入地图"),
                        b -> this.confirmAction("导入世界地图",
                                "\u00a7e选择包含建筑的世界存档压缩包（.zip，内含 level.dat 与 "
                                        + "region/*.mca）或打包的建筑文件 zip。\n"
                                        + "世界存档会自动以玩家最后位置为中心提取建筑"
                                        + "（排除地形、锚点 ±256 格，旧版本 1.8-1.13+ 均可）。\n\n"
                                        + "提取完成后在建造目录选择该蓝图即可建造。",
                                "\u00a7a选择文件",
                                this::pickAndImportWorld))
                .m_252987_(this.f_96543_ - 92, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
    }

    /** v1.5.224：选择世界地图 zip → 发送导入请求（结果回聊天框）。
     *  实测九百六十六：实测九百六十五 的「系统文件选择框优先」在开发者环境弹不出来
     *  （AWT 不可用）→ 回到游戏内浏览器（同轮把它的布局修好：翻页行与条目重叠、条目出屏）。 */
    private void pickAndImportWorld() {
        com.maidsmart.gui.FilePickScreen.open(this,
                "选择世界存档压缩包（.zip）",
                new String[]{"zip"},
                path -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookEntityPackets.WorldImportPacket(path));
                    this.chatHint("\u00a77已发送世界地图导入请求，结果请看聊天框");
                });
    }

    /** v1.5.220：打开文件选择器选蓝图文件 → 发送导入请求（单机场景客户端与服务端
     *  同机，绝对路径服务端可直接读取；结果回聊天框）。
     *  实测九百六十六：同导入地图——系统框弹不出来，回到游戏内浏览器（布局已修）。 */
    private void pickAndImportBuild() {
        com.maidsmart.gui.FilePickScreen.open(this,
                "选择建筑蓝图文件（.schem/.litematic/.nbt/.snbt/.schematic/.json/.zip）",
                new String[]{"schem", "litematic", "nbt", "snbt", "schematic", "json", "zip"},
                path -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookEntityPackets.BuildImportPacket(path));
                    this.chatHint("\u00a77已发送导入请求，结果请看聊天框");
                });
    }

    // ================= 女仆管理面板 =================
    private void maidsViewButtons() {
        int h = this.f_96544_;
        int w = this.f_96543_;
        int cx = w / 2;
        // v1.5.182：女仆管理页简化——只显示【有效区块】（render 文字）+【建造状态
        // 女仆名单】；点击女仆行 → 女仆详情页（绑定/解绑指定区块）；解绑/设工头
        // 按钮移出本页（解绑在女仆详情页、工头在区块名单页）
        boolean inRegion = this.inPlanRegion();
        String herePlanId = this.playerPlanId();
        // 建造状态女仆（建筑任务）
        java.util.List<String[]> builders = new java.util.ArrayList<>();
        for (String[] m : this.allMaids) {
            if (m.length > 4 && "build".equals(m[4])) {
                builders.add(m);
            }
        }
        int rows = this.maidRowsPerPage();
        int total = Math.max(1, (builders.size() + rows - 1) / rows);
        this.maidPage = Math.min(this.maidPage, total - 1);
        int start = this.maidPage * rows;
        int end = Math.min(builders.size(), start + rows);
        int btnW = Math.max(180, w - 40);
        // v1.5.183：下移 10px 给上方区块列表文字行留空间（旧版 45 行文字与按钮重叠）
        // v1.5.279：区块列表竖排（每区块 2 行含创建坐标）→ 按钮按区块行数继续下移
        int y = CONTENT_TOP + 10 + Math.max(0, this.regionListRows() - 1) * 10;
        for (int i = start; i < end; i++) {
            final String[] m = builders.get(i);
            final String uuid = m[0];
            boolean isFm = m.length > 7 && "1".equals(m[7]);
            String bindName = m.length > 5 ? m[5] : "";
            String bState = m.length > 6 ? m[6] : "";
            String mainText = "\u00a7e\u2605 " + this.fitText(m[1], 90)
                    + (bindName.isEmpty() ? "" : " \u00a7b「" + this.fitText(bindName, 40) + "」")
                    + " \u00a7e" + bState
                    + (isFm ? " \u00a7e（工头）" : " \u00a77（点击查看/绑定）");
            // 点击 → 女仆详情页（绑定/解绑）
            this.m_142416_(Button.m_253074_(Component.m_237113_(mainText),
                            b -> {
                                this.detailMaidUuid = uuid;
                                this.selectedPlanId = m.length > 8 && !m[8].isEmpty()
                                        ? m[8] : (this.buildRegions.isEmpty() ? null : this.buildRegions.get(0)[0]);
                                this.view = VIEW_MAID_DETAIL;
                                this.rebuildButtons();
                            })
                    .m_252987_(cx - btnW / 2, y, btnW, MAID_ROW_H).m_253136_());
            y += MAID_ROW_H + 1;
        }
        if (builders.isEmpty()) {
            this.graphicsHint("当前维度没有建造状态的女仆——给女仆切换'建筑'任务后她会出现在这里。");
        }
        // 右上角：全部暂停/继续（v1.5.178：区块内才显示——区块控制）；左上角：返回大目录
        if (inRegion) {
            final String pid = herePlanId;
            this.m_142416_(Button.m_253074_(Component.m_237113_(this.paused ? "全部继续" : "全部暂停"),
                            b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new BlueprintBookBuildPackets.BuildControlPacket(
                                            BlueprintBookBuildPackets.BuildControlPacket.TOGGLE_PAUSE, null, pid)))
                    .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 大目录"),
                        b -> {
                            this.view = VIEW_HOME;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        // 女仆翻页（底行，独占一行不与其他按钮冲突；v1.1.0 实测二十五：改 20 宽箭头）
        if (total > 1) {
            if (this.maidPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.maidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 40, h - 26, 20, 20).m_253136_());
            }
            if (this.maidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.maidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 20, h - 26, 20, 20).m_253136_());
            }
        }
    }

    // ================= v1.5.182 女仆详情页（绑定/解绑指定区块） =================
    private void maidDetailViewButtons() {
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        // 找女仆行
        String[] m = null;
        if (this.detailMaidUuid != null) {
            for (String[] x : this.allMaids) {
                if (x[0].equals(this.detailMaidUuid)) {
                    m = x;
                    break;
                }
            }
        }
        if (m == null) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆管理"),
                            b -> {
                                this.view = VIEW_MAIDS;
                                this.rebuildButtons();
                            })
                    .m_252987_(8, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
            return;
        }
        final String uuid = m[0];
        boolean isBuild = m.length > 4 && "build".equals(m[4]);
        // v1.5.183：已绑定 = 绑定 planId 非空（建筑女仆可能已绑定/未绑定）
        boolean isBound = m.length > 8 && !m[8].isEmpty();
        // 区块列表（点选绑定目标；默认女仆当前绑定的区块或第一个）
        // v1.5.190 修复：旧版按区块数无上限逐行 +15 排下去，区块一多绑定/解绑按钮
        // 被推出屏幕外（找不到按钮=死锁）。改为分页（每页按可用高度算行数）+ 底部翻页。
        int regionRow = 0;
        if (!this.buildRegions.isEmpty()) {
            if (this.selectedPlanId == null) {
                this.selectedPlanId = this.buildRegions.get(0)[0];
            }
            int maxRows = Math.max(3, (h - 30 - CONTENT_TOP - 40) / 15);
            int rTotal = Math.max(1, (this.buildRegions.size() + maxRows - 1) / maxRows);
            this.detailRegionPage = Math.min(this.detailRegionPage, rTotal - 1);
            int rStart = this.detailRegionPage * maxRows;
            int rEnd = Math.min(this.buildRegions.size(), rStart + maxRows);
            int ry = CONTENT_TOP + 4;
            for (int i = rStart; i < rEnd; i++) {
                String[] r = this.buildRegions.get(i);
                if (r == null || r.length < 3) {
                    continue;
                }
                boolean selected = r[0].equals(this.selectedPlanId);
                final String rid = r[0];
                this.m_142416_(Button.m_253074_(
                                Component.m_237113_((selected ? "\u00a7e\u25c9 " : "\u00a77\u25c7 ")
                                        + this.fitText(r[1], 60)
                                        + " \u00a77(" + r[2] + "·" + r[3] + ")"),
                                b -> {
                                    this.selectedPlanId = rid;
                                    this.rebuildButtons();
                                })
                        .m_252987_(cx - 200, ry, 400, 14).m_253136_());
                ry += 15;
                regionRow++;
            }
            // 区块翻页（页脚行，独占一行不与绑定按钮冲突；v1.1.0 实测二十五：改 20 宽箭头）
            if (rTotal > 1) {
                int py = h - 26;
                if (this.detailRegionPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                    b -> {
                                        this.detailRegionPage--;
                                        this.rebuildButtons();
                                    }).m_252987_(cx - 40, py, 20, 20).m_253136_());
                }
                if (this.detailRegionPage < rTotal - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                    b -> {
                                        this.detailRegionPage++;
                                        this.rebuildButtons();
                                    }).m_252987_(cx + 20, py, 20, 20).m_253136_());
                }
            }
        }
        int y = CONTENT_TOP + regionRow * 15 + 8;
        // v1.5.183：绑定/解绑逻辑修正——【未绑定】一律显示绑定按钮（无论建筑/非建筑
        // 任务，绑定自动切任务）；【已绑定】才显示解绑
        if (isBound) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e解绑（离开当前区块）"),
                            b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new BlueprintBookBuildPackets.BuildControlPacket(
                                            BlueprintBookBuildPackets.BuildControlPacket.UNBIND_MAID, uuid, null)))
                    .m_252987_(cx - 140, y, 280, 20).m_253136_());
        } else if (this.selectedPlanId != null) {
            final String sid = this.selectedPlanId;
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a绑定到选中区块"),
                            b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new BlueprintBookBuildPackets.BuildControlPacket(
                                            BlueprintBookBuildPackets.BuildControlPacket.BIND_MAID, uuid, sid)))
                    .m_252987_(cx - 140, y, 280, 20).m_253136_());
        }
        // v1.5.305：删除「女仆配置」按钮（反馈："有 bug 不想修，直接删了；
        // 不走这个路径了"——打开 TLM 女仆配置请直接右键女仆）
        // 提示行：未绑定区块时的引导（render 绘制，避免与按钮重叠）
        if (!isBound && this.buildRegions.isEmpty()) {
            this.graphicsHint("当前没有可绑定的区块——先到建造目录创建区块。");
        }
        // 返回女仆管理
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆管理"),
                        b -> {
                            this.view = VIEW_MAIDS;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
    }

    // ================= v1.5.182 区块绑定女仆名单（设置工头） =================
    private void regionMaidsViewButtons() {
        int w = this.f_96543_;
        int h = this.f_96544_;
        int cx = w / 2;
        final String pid = this.currentPlanId;
        java.util.List<String[]> bound = new java.util.ArrayList<>();
        for (String[] m : this.allMaids) {
            if (pid != null && m.length > 8 && pid.equals(m[8])) {
                bound.add(m);
            }
        }
        int btnW = Math.max(180, w - 40);
        // v1.5.190 修复：旧版按绑定女仆数无上限排下去，列表一长"设为工头"和
        // 翻页按钮被推出屏幕（找不回 = 死锁）。改为分页（每页按可用高度算行数）。
        // v1.5.308：行数公式【预留进度条空间】——旧版只给翻页按钮留 32px，绑定
        // 女仆多时（6 只 + 小窗口）底部进度条叠在最后几行女仆按钮上
        //（反馈："又一次出现了进度条重合在了一起"）；48px = 4 行状态文本 + 进度条
        int maxRows = Math.max(3, (h - 30 - CONTENT_TOP - 32 - 48) / (MAID_ROW_H + 1));
        int total = Math.max(1, (bound.size() + maxRows - 1) / maxRows);
        this.regionMaidPage = Math.min(this.regionMaidPage, total - 1);
        int start = this.regionMaidPage * maxRows;
        int end = Math.min(bound.size(), start + maxRows);
        int y = CONTENT_TOP;
        for (int i = start; i < end; i++) {
            String[] m = bound.get(i);
            final String uuid = m[0];
            boolean isFm = m.length > 7 && "1".equals(m[7]);
            String bState = m.length > 6 ? m[6] : "";
            String mainText = "\u00a7" + (isFm ? "e" : "7") + (isFm ? "\u2605" : "\u2666")
                    + " " + this.fitText(m[1], 100)
                    + (bState.isEmpty() ? "" : " \u00a77" + bState)
                    + (isFm ? " \u00a7e（工头）" : "");
            this.m_142416_(Button.m_253074_(Component.m_237113_(mainText), b -> {
                        // v1.5.290：区块详情页名单点击 → 跳转女仆详情页（查看/绑定/解绑）
                        //（旧版是空按钮，反馈："右击区块详细页里面的名单，要有跳转功能"）
                        this.detailMaidUuid = uuid;
                        this.selectedPlanId = pid;
                        this.view = VIEW_MAID_DETAIL;
                        this.rebuildButtons();
                    })
                    .m_252987_(cx - btnW / 2, y, Math.max(120, btnW - 90), MAID_ROW_H).m_253136_());
            // 设为工头（一区块一工头；当前工头行不显示）
            if (!isFm) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("设为工头"),
                                b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookBuildPackets.BuildControlPacket(
                                                BlueprintBookBuildPackets.BuildControlPacket.SET_FOREMAN, uuid, pid)))
                        .m_252987_(cx + btnW / 2 - 88, y, 88, MAID_ROW_H).m_253136_());
            }
            y += MAID_ROW_H + 1;
        }
        if (bound.isEmpty()) {
            this.graphicsHint("该区块还没有绑定女仆——点右上「♀ 女仆管理」进女仆管理页，点女仆行进详情页绑定。");
        }
        // v1.5.298：本页跳转女仆管理（反馈："在此页面要的跳转界面仍然没有出现"——
        // 旧版此页只有「← 返回详情」，提示却指向女仆管理——空名单页是死胡同；
        // 加右上角直达按钮）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7b\u2640 女仆管理"),
                        b -> {
                            this.view = VIEW_MAIDS;
                            this.maidPage = 0;
                            this.rebuildButtons();
                        })
                .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        // v1.5.190：绑定女仆翻页（页脚行；v1.1.0 实测二十五：改 20 宽箭头）
        if (total > 1) {
            int py = h - 26;
            if (this.regionMaidPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.regionMaidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 40, py, 20, 20).m_253136_());
            }
            if (this.regionMaidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.regionMaidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 20, py, 20, 20).m_253136_());
            }
        }
        // 返回详情页
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 返回详情"),
                        b -> {
                            this.view = VIEW_BUILD;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
    }

    /** 女仆面板空状态提示（按钮区无空间时的文字提示，放在 render 里画） */
    private String maidEmptyText = null;

    private void graphicsHint(String text) {
        this.maidEmptyText = text;
    }

    /** 未选中动作目标时的聊天提示（不打断界面；f_91065_=Gui → m_93076_=getChat → m_93785_=addMessage） */
    private void chatHint(String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91065_ != null) {
            mc.f_91065_.m_93076_().m_93785_(net.minecraft.network.chat.Component.m_237113_(text));
        }
    }

    // ================= v1.5.95 女仆记忆查看面板 =================
    /** v1.5.192：已选女仆页 tab——0=记忆列表 / 1=链路调试（工作链路 + 手动调试） */
    private int memTab = 0;
    /** v1.5.192：链路调试——状态行（工作链路/状态/情绪/记忆统计/主动对话阶段/笔记） */
    private List<String> debugStatusLines = null;
    /** v1.5.192 / 实测九百七十四：可调试对象行 {type, key, info, text, state}
     *  （段落 para / 关系 rel；state 空或 deleted 或 inactive） */
    private List<String[]> debugRows = null;
    /** 实测九百七十四：选中项记【稳定键】而不是下标——动作后服务端按重要度重排，
     *  旧版下标会指到隔壁那条（改上面一条、下面那条也跟着变就是这个）。null = 未选中 */
    private String debugSelKey = null;
    /** v1.5.192：调试对象行分页 */
    private int debugPage = 0;

    /** 实测九百七十七：当前列表每页行数（用于「跳到关系」把目标翻到可见页） */
    private int debugMaxRows = 10;
    /** v1.2.1：链路调试状态区分页（小窗口状态行超 maxStatus 时 ◀/▶ 翻页） */
    private int debugStatusPage = 0;
    /** 实测九百七十四：上一步动作的回执（服务端下发，画在面板里，不刷聊天栏）+ 刷新时刻 */
    private String debugNote = null;
    private String debugStamp = null;
    /** 实测九百七十六：状态区默认收起——旧版一直占着上半屏，列表一页只剩两行 */
    private boolean debugStatusOpen = false;

    // ===== 实测九百七十二：记忆图谱页（把记忆画出来：重要度分布 / 时间轴 / 关系网络）=====
    /** 图谱模式：0=重要度分布 / 1=记忆时间轴 / 2=关系网络 */
    private int graphMode = 0;
    /** 关系网翻页（一页 GRAPH_NODES_PER_PAGE 个实体；另两张图整张一屏，不翻页） */
    private int graphPage = 0;
    /** 图谱原始数据行（服务端结构化下发；行格式见 BlueprintBookNetworking#collectMemoryGraph） */
    private List<String> graphLines = null;
    /** 重要度直方图：下标 = 重要度 0~10，值 = 该档段落条数 */
    private int[] graphHist = null;
    /** 统计口径 {段落, 有效关系, 日记篇数, 永久段落, 长期段落, 日记最小日, 日记最大日} */
    private long[] graphStat = null;
    /** 时间轴条目 {起日, 止日, 级别码(0日/1三日/2周/3月), 字数} */
    private List<int[]> graphTime = null;
    /** 关系边 {主体, 谓词, 客体, 置信度文本} */
    private List<String[]> graphEdge = null;
    /** 实体节点 {名字, 度数}（按度数降序） */
    private List<String[]> graphNode = null;
    /** 关系网一页显示的实体数（圆环布局，再多会挤成一团） */
    private static final int GRAPH_NODES_PER_PAGE = 12;

    // ===== 实测九百七十五：日记本页（多级记忆索引 = 目录 + 逐日正文）=====
    /** 日记原始行（服务端结构化下发；行格式见 BlueprintBookNetworking#collectDiary） */
    private List<String> diaryLines = null;
    /** 日记目录 {级别, 起日, 止日, 字数, 正文}（按时间倒序，最新在前） */
    private List<String[]> diaryRec = null;
    /** 目录翻页 / 选中的日记下标（-1 = 未选中）/ 正文翻页 */
    private int diaryTocPage = 0;
    private int diarySel = -1;
    private int diaryTextPage = 0;

    /** 服务端下发记忆行后更新显示 */
    public void showMemoryLines(String maidUuid, List<String> lines) {
        if (this.memoryMaidUuid != null && this.memoryMaidUuid.equals(maidUuid)) {
            this.memoryLines = lines == null ? new ArrayList<>() : lines;
            this.memoryPage = 0;
            this.rebuildButtons();
        }
    }

    /** v1.5.192 / 实测九百七十四：服务端下发调试快照后更新显示（note = 上一步动作回执） */
    public void showDebugSnapshot(String maidUuid, List<String> statusLines, List<String[]> rows, String note) {
        if (this.memoryMaidUuid != null && this.memoryMaidUuid.equals(maidUuid)) {
            this.debugStatusLines = statusLines == null ? new ArrayList<>() : statusLines;
            this.debugRows = rows == null ? new ArrayList<>() : rows;
            // 选中项按稳定键重新定位；那条已不在列表里才清空选择
            if (this.debugSelIndex() < 0) {
                this.debugSelKey = null;
            }
            this.debugPage = 0;
            this.debugStatusPage = 0;
            this.debugNote = note;
            this.debugStamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date());
            this.rebuildButtons();
        }
    }

    /** 实测九百七十二：服务端下发图谱数据后解析并刷新（行格式：KIND|字段|…，坏行直接跳过） */
    public void showMemoryGraph(String maidUuid, List<String> lines) {
        if (this.memoryMaidUuid == null || !this.memoryMaidUuid.equals(maidUuid)) {
            return;
        }
        List<String> src = lines == null ? new ArrayList<>() : lines;
        this.graphLines = src;
        this.graphHist = new int[11];
        this.graphStat = new long[7];
        this.graphTime = new ArrayList<>();
        this.graphEdge = new ArrayList<>();
        this.graphNode = new ArrayList<>();
        for (String raw : src) {
            if (raw == null) {
                continue;
            }
            String[] f = raw.split("\\|", -1);
            try {
                switch (f[0]) {
                    case "STAT" -> {
                        for (int i = 0; i < 7 && i + 1 < f.length; i++) {
                            this.graphStat[i] = Long.parseLong(f[i + 1]);
                        }
                    }
                    case "HIST" -> {
                        int b = Integer.parseInt(f[1]);
                        if (b >= 0 && b <= 10) {
                            this.graphHist[b] = Integer.parseInt(f[2]);
                        }
                    }
                    case "TIME" -> this.graphTime.add(new int[]{Integer.parseInt(f[1]),
                            Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])});
                    case "EDGE" -> this.graphEdge.add(new String[]{f[1], f[2], f[3], f[4]});
                    case "NODE" -> this.graphNode.add(new String[]{f[1], f[2]});
                    default -> {
                    }
                }
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintBookScreen", ignored);
            }
        }
        this.graphPage = 0;
        this.rebuildButtons();
    }

    /** 实测九百七十五：服务端下发日记行后解析并刷新（行格式：KIND|字段…，坏行直接跳过） */
    public void showDiaryLines(String maidUuid, List<String> lines) {
        if (this.memoryMaidUuid == null || !this.memoryMaidUuid.equals(maidUuid)) {
            return;
        }
        this.diaryLines = lines == null ? new ArrayList<>() : lines;
        this.diaryRec = new ArrayList<>();
        for (String raw : this.diaryLines) {
            if (raw == null) {
                continue;
            }
            String[] f = raw.split("\\|", -1);
            if (f.length < 6 || !"DIARY".equals(f[0])) {
                continue;
            }
            this.diaryRec.add(new String[]{f[1], f[2], f[3], f[4], f[5]});
        }
        this.diaryTocPage = 0;
        this.diaryTextPage = 0;
        // 服务端已按时间倒序，默认就停在新的一篇上
        this.diarySel = this.diaryRec.isEmpty() ? -1 : 0;
        this.rebuildButtons();
    }

    /** 实测九百七十五：清掉日记缓存（换女仆或退出该女仆时调用——旧日记不能串到新人身上） */
    private void clearDiary() {
        this.diaryLines = null;
        this.diaryRec = null;
        this.diaryTocPage = 0;
        this.diarySel = -1;
        this.diaryTextPage = 0;
    }

    /** 实测九百七十五：请求日记数据（进日记页时调用） */
    private void requestDiary() {
        if (this.memoryMaidUuid == null) {
            return;
        }
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookEntityPackets.MaidDiaryRequestPacket(this.memoryMaidUuid));
    }

    /** 实测九百七十五：日记本页左右两列的几何（渲染与按钮共用同一口径，页码才和按钮对得上） */
    private int[] diaryLayout(int w, int h) {
        int tocW = Math.max(96, w * 38 / 100);
        int conX = 12 + tocW + 10;
        int conW = Math.max(60, w - conX - 12);
        return new int[]{12, tocW, conX, conW, h - 26};
    }

    /** 实测九百七十五：选中那篇的正文（当折行输入用；没选中返回空表） */
    private java.util.List<String> diaryBody() {
        java.util.List<String> one = new java.util.ArrayList<>();
        if (this.diaryRec != null && this.diarySel >= 0 && this.diarySel < this.diaryRec.size()) {
            one.add(this.diaryRec.get(this.diarySel)[4]);
        }
        return one;
    }

    /** 实测九百七十五：正文页数（按窗口宽度折行后算） */
    private int diaryTextPages(int w, int h) {
        int[] g = this.diaryLayout(w, h);
        int top = CONTENT_TOP + 14;
        int maxLines = Math.max(1, (g[4] - 4 - top) / TEXT_LINE_H);
        int total = this.wrapLines(this.diaryBody(), g[3] - 4).size();
        return Math.max(1, (total + maxLines - 1) / maxLines);
    }

    /** 实测九百七十五：左对齐小字（日记页标题/页码共用） */
    private void diaryText(net.minecraft.client.gui.GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.m_280614_(this.f_96547_, Component.m_237113_(text), x, y, color, false);
    }

    /** 实测九百七十五：日记本页按钮——左列目录（分页，点一条跳转）+ 两列页脚翻页 */
    private void diaryViewButtons(int w, int h, int cx) {
        int[] g = this.diaryLayout(w, h);
        int top = CONTENT_TOP + 14;
        if (this.diaryRec == null) {
            this.graphicsHint("\u6b63\u5728\u7ffb\u5979\u7684\u65e5\u8bb0\u2026");
            return;
        }
        if (this.diaryRec.isEmpty()) {
            this.graphicsHint("\u5979\u8fd8\u6ca1\u6709\u65e5\u8bb0\u2014\u2014\u7761\u4e00\u89c9\u6216\u8de8\u5929\u540e\uff0c\u5979\u4f1a\u628a\u8fd9\u6bb5\u65f6\u95f4\u7684\u4e8b\u6574\u7406\u6210\u65e5\u8bb0");
            return;
        }
        int maxToc = Math.max(1, (g[4] - 4 - top) / 19);
        int tocPages = Math.max(1, (this.diaryRec.size() + maxToc - 1) / maxToc);
        this.diaryTocPage = Math.min(this.diaryTocPage, tocPages - 1);
        int start = this.diaryTocPage * maxToc;
        int end = Math.min(this.diaryRec.size(), start + maxToc);
        for (int i = start; i < end; i++) {
            final int idx = i;
            String[] r = this.diaryRec.get(i);
            String label = (idx == this.diarySel ? "\u00a7e\u25b6 " : "\u00a77  ")
                    + this.fitText("\u7b2c" + r[1] + "~" + r[2] + "\u5929 " + r[0] + "\u8bb0", g[1] - 14);
            this.m_142416_(Button.m_253074_(Component.m_237113_(label), b -> {
                        this.diarySel = idx;
                        this.diaryTextPage = 0;
                        this.rebuildButtons();
                    }).m_252987_(g[0], top + (i - start) * 19, g[1], 18).m_253136_());
        }
        if (tocPages > 1) {
            if (this.diaryTocPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                            this.diaryTocPage--;
                            this.rebuildButtons();
                        }).m_252987_(g[0], g[4], 20, 18).m_253136_());
            }
            if (this.diaryTocPage < tocPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                            this.diaryTocPage++;
                            this.rebuildButtons();
                        }).m_252987_(g[0] + g[1] - 20, g[4], 20, 18).m_253136_());
            }
        }
        int tp = this.diaryTextPages(w, h);
        this.diaryTextPage = Math.min(this.diaryTextPage, tp - 1);
        if (tp > 1) {
            if (this.diaryTextPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                            this.diaryTextPage--;
                            this.rebuildButtons();
                        }).m_252987_(g[2], g[4], 20, 18).m_253136_());
            }
            if (this.diaryTextPage < tp - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                            this.diaryTextPage++;
                            this.rebuildButtons();
                        }).m_252987_(g[2] + g[3] - 20, g[4], 20, 18).m_253136_());
            }
        }
    }

    /** 实测九百七十五：日记本页渲染——左目录 + 右正文（都按窗口宽度折行分页） */
    private void renderDiaryView(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e\u5973\u4ec6\u65e5\u8bb0 \u00b7 "
                + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                + (this.diaryRec == null ? "\uff08\u8bf7\u6c42\u4e2d\u2026\uff09" : "\uff08\u5171 " + this.diaryRec.size() + " \u7bc7\uff09"),
                PANEL_TITLE_Y, 0xFFFFFF);
        this.drawCentered(graphics, "\u00a77\u5de6\u8fb9\u662f\u76ee\u5f55\uff0c\u70b9\u4e00\u5929\u770b\u90a3\u5929\u5979\u5199\u4e0b\u7684\u4e8b\uff1b\u53f3\u8fb9\u662f\u6b63\u6587",
                PANEL_TITLE_Y + 8, 0x888888);
        if (this.diaryRec == null || this.diaryRec.isEmpty()) {
            if (this.maidEmptyText != null) {
                this.diaryText(graphics, this.maidEmptyText, 12, CONTENT_TOP + 16, 0x888888);
            }
            return;
        }
        int[] g = this.diaryLayout(this.f_96543_, this.f_96544_);
        int top = CONTENT_TOP + 14;
        this.diaryText(graphics, "\u00a78\u76ee\u5f55\uff08\u6700\u65b0\u5728\u524d\uff09", g[0], CONTENT_TOP, 0x888888);
        int maxToc = Math.max(1, (g[4] - 4 - top) / 19);
        int tocPages = Math.max(1, (this.diaryRec.size() + maxToc - 1) / maxToc);
        if (tocPages > 1) {
            this.diaryText(graphics, "\u00a77\u7b2c" + (this.diaryTocPage + 1) + "/" + tocPages + " \u9875",
                    g[0] + 24, g[4] + 5, 0xAAAAAA);
        }
        if (this.diarySel < 0 || this.diarySel >= this.diaryRec.size()) {
            return;
        }
        String[] r = this.diaryRec.get(this.diarySel);
        this.diaryText(graphics, "\u00a7e\u7b2c" + r[1] + "~" + r[2] + "\u5929 \u00b7 " + r[0] + "\u8bb0 \u00b7 " + r[3] + " \u5b57",
                g[2], CONTENT_TOP, 0xFFFFFF);
        java.util.List<String> body = this.wrapLines(this.diaryBody(), g[3] - 4);
        int maxLines = Math.max(1, (g[4] - 4 - top) / TEXT_LINE_H);
        int tp = Math.max(1, (body.size() + maxLines - 1) / maxLines);
        int from = Math.min(this.diaryTextPage, tp - 1) * maxLines;
        int y = top;
        for (int i = from; i < Math.min(body.size(), from + maxLines); i++) {
            this.diaryText(graphics, body.get(i), g[2], y, 0xDDDDDD);
            y += TEXT_LINE_H;
        }
        if (tp > 1) {
            this.diaryText(graphics, "\u00a77\u7b2c" + (this.diaryTextPage + 1) + "/" + tp + " \u9875",
                    g[2] + 24, g[4] + 5, 0xAAAAAA);
        }
    }

    /** 实测九百七十二：清掉图谱缓存（换女仆或退出该女仆时调用——旧数据不能串到新人身上） */
    private void clearGraph() {
        this.graphLines = null;
        this.graphHist = null;
        this.graphStat = null;
        this.graphTime = null;
        this.graphEdge = null;
        this.graphNode = null;
        this.graphPage = 0;
    }

    /** 实测九百七十二：请求图谱数据（进图谱页/刷新时调用） */
    private void requestMemoryGraph() {
        if (this.memoryMaidUuid == null) {
            return;
        }
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookEntityPackets.MemoryGraphRequestPacket(this.memoryMaidUuid));
    }

    /** v1.5.192 / 实测九百七十四：发送调试快照请求（进入链路调试页或点刷新时调用）——
     *  先给一句可见回执，否则点了刷新像没反应 */
    private void requestDebugSnapshot() {
        if (this.memoryMaidUuid == null) {
            return;
        }
        this.debugNote = "\u6b63\u5728\u5237\u65b0\u2026";
        this.rebuildButtons();
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookEntityPackets.MaidDebugRequestPacket(this.memoryMaidUuid));
    }

    /** v1.5.103：清空女仆记忆确认框（MC 删除世界样式）——确认后发 ClearMemoryPacket，
     *  服务端清空并回发空列表 */
    private void confirmClearMemory() {
        if (this.memoryMaidUuid == null) {
            return;
        }
        final String uuid = this.memoryMaidUuid;
        final String name = this.memoryMaidName == null ? "" : this.memoryMaidName;
        this.confirmAction("清空记忆？",
                "\u00a7e\u300c" + name + "\u300d的全部记忆（段落/关系/画像/工作笔记）将被清除，无法恢复",
                "\u00a7c确认清空",
                () -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookEntityPackets.ClearMemoryPacket(uuid));
                    this.memoryLines = null;
                });
    }

    private void memoryViewButtons() {
        int h = this.f_96544_;
        int w = this.f_96543_;
        int cx = w / 2;
        // 已选中女仆 → 显示记忆/链路调试（分页）
        if (this.memoryMaidUuid != null) {
            // 记忆行渲染在 render 里；这里只放返回按钮
            this.m_142416_(Button.m_253074_(Component.m_237113_("← 女仆列表"),
                            b -> {
                                this.memoryMaidUuid = null;
                                this.memoryLines = null;
                                this.debugStatusLines = null;
                                this.debugRows = null;
                                this.debugSelKey = null;
                                this.memTab = 0;
                                this.clearGraph();
                                this.clearDiary();
                                this.rebuildButtons();
                            })
                    .m_252987_(8, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
            // v1.5.192：页内 tab（记忆 / 链路调试）
            // 实测九百七十五：四个 tab 一组居中，总宽 46+50+46+50 + 三道 4px 缝 = 204
            //（与三个 tab 时同外框，不与左侧「← 女仆列表」、右侧「清空记忆」抢位）
            int tabW1 = 46;
            int tabW2 = 50;
            int tabW3 = 46;
            int tabW4 = 50;
            int tabGap = 4;
            int tabX = cx - (tabW1 + tabW2 + tabW3 + tabW4 + tabGap * 3) / 2;
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 0 ? "\u00a7e\u25cf \u8bb0\u5fc6" : "\u00a77\u8bb0\u5fc6"),
                            b -> {
                                this.memTab = 0;
                                this.rebuildButtons();
                            })
                    .m_252987_(tabX, TOP_BTN_Y, tabW1, TOP_BTN_H).m_253136_());
            tabX += tabW1 + tabGap;
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 1 ? "\u00a7e\u25cf \u8c03\u8bd5" : "\u00a77\u8c03\u8bd5"),
                            b -> {
                                this.memTab = 1;
                                this.debugSelKey = null;
                                this.debugStatusLines = null;
                                this.debugRows = null;
                                this.rebuildButtons();
                                this.requestDebugSnapshot();
                            })
                    .m_252987_(tabX, TOP_BTN_Y, tabW2, TOP_BTN_H).m_253136_());
            tabX += tabW2 + tabGap;
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 2 ? "\u00a7e\u25cf \u56fe\u8c31" : "\u00a77\u56fe\u8c31"),
                            b -> {
                                this.memTab = 2;
                                this.graphMode = 0;
                                this.clearGraph();
                                this.clearDiary();
                                this.rebuildButtons();
                                this.requestMemoryGraph();
                            })
                    .m_252987_(tabX, TOP_BTN_Y, tabW3, TOP_BTN_H).m_253136_());
            tabX += tabW3 + tabGap;
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 3 ? "\u00a7e\u25cf \u65e5\u8bb0" : "\u00a77\u65e5\u8bb0"),
                            b -> {
                                this.memTab = 3;
                                this.clearDiary();
                                this.rebuildButtons();
                                this.requestDiary();
                            })
                    .m_252987_(tabX, TOP_BTN_Y, tabW4, TOP_BTN_H).m_253136_());
            // v1.5.103：清空该女仆全部记忆（段落/关系/画像）——确认框 + 服务端清空 + 刷新
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c清空记忆"),
                            b -> this.confirmClearMemory())
                    .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
            if (this.memTab == 1) {
                // ---- 链路调试页 ----
                this.debugViewButtons(w, h, cx);
                return;
            }
            if (this.memTab == 2) {
                // ---- 记忆图谱页 ----
                this.graphViewButtons(w, h, cx);
                return;
            }
            if (this.memTab == 3) {
                // ---- 实测九百七十五：日记本页 ----
                this.diaryViewButtons(w, h, cx);
                return;
            }
        // v1.5.190：记忆行分页 + 高度自适应——旧版从 y=52 起无限画下去，
        // 行数一多就盖住底部按钮/翻页键；改为每页最多 8 行、底部翻页。
        // 实测九百七十一：翻页按**折行后的视觉行数**算，行高与渲染侧同口径（旧版这里 /10、
        // 渲染侧 /12 —— 页数与实际可见行本就对不上，末页会被切掉）
        int memVis = this.memoryLines == null ? 0 : this.wrapLines(this.memoryLines, w - 40).size();
        int maxLines = Math.max(4, (h - 30 - CONTENT_TOP - 20) / TEXT_LINE_H);
        if (this.memoryLines != null && memVis > maxLines) {
            int pages = (memVis + maxLines - 1) / maxLines;
            this.memoryPage = Math.min(this.memoryPage, pages - 1);
            if (this.memoryPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.memoryPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 40, h - 26, 20, 20).m_253136_());
            }
            if (this.memoryPage < pages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.memoryPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 20, h - 26, 20, 20).m_253136_());
            }
        }
        return;
        }
        // 女仆列表（v1.5.100b：全部女仆，不限 128 格/不限任务；每行主按钮 + 记忆开关）
        // v1.0.3：每行再加「LLM:开/关」开关——缩小并排按钮挤进去，行总宽恒 ≤ btnW ≤ w-40，
        // 小窗口/高 guiScale 不再超出屏幕
        int rows = this.maidRowsPerPage();
        int total = Math.max(1, (this.allMaids.size() + rows - 1) / rows);
        this.maidPage = Math.min(this.maidPage, total - 1);
        int start = this.maidPage * rows;
        int end = Math.min(this.allMaids.size(), start + rows);
        int btnW = Math.max(180, w - 40);
        final int toggleW = 52;                       // 开关按钮宽（"记忆:开"/"LLM:开" 4 字）
        final int mainW = Math.max(68, btnW - 112);   // 主按钮 = 行宽 - 两个开关 - 间隙(8)
        int y = CONTENT_TOP;
        for (int i = start; i < end; i++) {
            final String[] m = this.allMaids.get(i);
            final String uuid = m[0];
            final boolean memOn = m.length > 2 && "1".equals(m[2]);
            final boolean llmOn = m.length > 9 && "1".equals(m[9]);
            // v1.5.167：第 4 字段 = 段落数（记忆调试可视化——每只女仆的记忆量）
            final String paraCount = m.length > 3 ? m[3] : "?";
            // 主按钮：名字 + 记忆开关状态 + 段落数（点击查看记忆）
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_("\u00a7a\u2661 " + this.fitText(m[1], 100)
                                    + "  \u00a77（" + (memOn ? "\u00a7a记忆开" : "\u00a77记忆关")
                                    + "\u00a77 · 段落" + paraCount + "\u00a77）"),
                            b -> {
                                this.memoryMaidUuid = uuid;
                                this.memoryMaidName = m[1];
                                this.memoryLines = null;
                                this.memTab = 0;
                                this.debugStatusLines = null;
                                this.debugRows = null;
                                this.debugSelKey = null;
                                this.clearGraph();
                                this.clearDiary();
                                this.rebuildButtons();
                                // 请求记忆
                                BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookEntityPackets.MemoryViewRequestPacket(uuid));
                            })
                    .m_252987_(cx - btnW / 2, y, mainW, MAID_ROW_H).m_253136_());
            // 记忆开关（点击切换 per-maid 记忆，服务端写 persistentData + 磁盘备份）
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(memOn ? "\u00a7a记忆:开" : "\u00a77记忆:关"),
                            b -> {
                                // v1.5.227：防连点——600ms 内重复点击忽略（双击 =
                                // 关→开 空转，实测日志里成对出现"关闭/启用"）
                                long now = System.currentTimeMillis();
                                if (now - this.lastMemoryToggleClick < 600) {
                                    return;
                                }
                                this.lastMemoryToggleClick = now;
                                BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookEntityPackets.AiMemoryTogglePacket(uuid, !memOn));
                                // 本地立即翻转（服务端确认消息另发聊天框）
                                m[2] = memOn ? "0" : "1";
                                this.rebuildButtons();
                            })
                    .m_252987_(cx - btnW / 2 + mainW + 4, y, toggleW, MAID_ROW_H).m_253136_());
            // v1.0.3：LLM 开关（点击切换 per-maid 大语言模型，操作方式同记忆开关）
            this.m_142416_(Button.m_253074_(
                            Component.m_237113_(llmOn ? "\u00a7aLLM:开" : "\u00a77LLM:关"),
                            b -> {
                                long now = System.currentTimeMillis();
                                if (now - this.lastLlmToggleClick < 600) {
                                    return;
                                }
                                this.lastLlmToggleClick = now;
                                BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookEntityPackets.AiLlmTogglePacket(uuid, !llmOn));
                                // 本地立即翻转（服务端确认消息另发聊天框）
                                m[9] = llmOn ? "0" : "1";
                                this.rebuildButtons();
                            })
                    .m_252987_(cx - btnW / 2 + mainW + 4 + toggleW + 4, y, toggleW, MAID_ROW_H).m_253136_());
            y += MAID_ROW_H + 1;
        }
        if (this.allMaids.isEmpty()) {
            this.graphicsHint("没有女仆——先召唤一只女仆再来看她的记忆");
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 大目录"),
                        b -> {
                            this.view = VIEW_HOME;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        if (total > 1) {
            if (this.maidPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.maidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 40, h - 26, 20, 20).m_253136_());
            }
            if (this.maidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.maidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 20, h - 26, 20, 20).m_253136_());
            }
        }
    }

    /** 女仆记忆渲染 */
    private void renderMemory(net.minecraft.client.gui.GuiGraphics graphics) {
        if (this.memoryMaidUuid != null) {
            if (this.memTab == 1) {
                // v1.5.192：链路调试页（工作链路 + 各项状况 + 手动调试记忆）
                this.renderDebugView(graphics);
                return;
            }
            if (this.memTab == 2) {
                // 实测九百七十二：记忆图谱页（重要度分布 / 时间轴 / 关系网络）
                this.renderGraphView(graphics);
                return;
            }
            if (this.memTab == 3) {
                // 实测九百七十五：日记本页（左目录 + 右逐日正文）
                this.renderDiaryView(graphics);
                return;
            }
            this.drawCentered(graphics, "\u00a7e女仆记忆 · "
                    + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                    + (this.memoryLines == null ? "（请求中…）" : ""), PANEL_TITLE_Y, 0xFFFFFF);
            // v1.5.100b：居中提示（旧版塞进记忆行导致左对齐不居中）
            this.drawCentered(graphics, "\u00a77（也可在女仆配置界面切换记忆）", PANEL_TITLE_Y + 8, 0x888888);
            if (this.memoryLines == null) {
                return;
            }
            // 每页行数按可用高度自适应（v1.5.190：旧版固定 8 行，小窗口盖底）
            // 实测九百七十一：长记忆 / 记忆日记先按宽度折行再分页——不再"一行截断看不全"
            java.util.List<String> vis = this.wrapLines(this.memoryLines, this.f_96543_ - 40);
            int maxLines = Math.max(4, (this.f_96544_ - 30 - CONTENT_TOP - 20) / TEXT_LINE_H);
            int y = CONTENT_TOP + 12;
            int start = this.memoryPage * maxLines;
            int end = Math.min(vis.size(), start + maxLines);
            for (int i = start; i < end; i++) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_(vis.get(i)),
                        20, y, 0xFFFFFF, false);
                y += TEXT_LINE_H;
            }
            if (vis.size() > maxLines) {
                // v1.1.0 实测二十五：页码画在两箭头中间行（h-22）
                this.drawCentered(graphics, "\u00a77第" + (this.memoryPage + 1) + "/"
                        + ((vis.size() + maxLines - 1) / maxLines) + " 页",
                        this.f_96544_ - 22, 0xAAAAAA);
            }
            return;
        }
        this.drawCentered(graphics, "\u00a7e女仆记忆（全部女仆 · 点击查看她记得什么，右侧按钮切换记忆开关）",
                PANEL_TITLE_Y, 0xFFFFFF);
        // v1.5.167：记忆系统调试统计（可视化）——女仆数 / 记忆开启数 / 段落总数
        int onCount = 0;
        long totalPara = 0;
        for (String[] m : this.allMaids) {
            if (m.length > 2 && "1".equals(m[2])) {
                onCount++;
            }
            if (m.length > 3) {
                try {
                    totalPara += Long.parseLong(m[3]);
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintBookScreen", ignored);
                }
            }
        }
        this.drawCentered(graphics, "\u00a78[调试]\u00a77 女仆 " + this.allMaids.size()
                        + " · 记忆开启 " + onCount + " · 段落共 " + totalPara
                        + "（详细调试见各女仆页：存储目录/统计/来源分布/重要度分级）",
                PANEL_TITLE_Y + 8, 0x888888);
        if (this.maidEmptyText != null) {
            // v1.5.110：旧版 m_280653_ 以 x=10 为圆心 → 文本几乎全裁出屏幕；改左对齐
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    10, CONTENT_TOP, 0x888888, false);
        }
    }

    // ================= v1.5.192 链路调试页（工作链路 + 手动调试记忆） =================

    /** 链路调试页按钮：状态区（默认收起）+ 记忆列表（紧凑整页）+ 两行动作按钮 + 翻页/跳转 */
    private void debugViewButtons(int w, int h, int cx) {
        int statusTop = CONTENT_TOP + 8;
        int maxStatus = Math.max(3, (h - 100 - statusTop) / TEXT_LINE_H);
        java.util.List<String> statusSrc = this.debugStatusSource();
        int statusPages = 1;
        int statusShown = 0;
        if (this.debugStatusOpen) {
            java.util.List<String> vis = this.wrapLines(statusSrc, w - 130);
            statusPages = Math.max(1, (vis.size() + maxStatus - 1) / maxStatus);
            this.debugStatusPage = Math.min(this.debugStatusPage, statusPages - 1);
            statusShown = Math.min(maxStatus, Math.max(0, vis.size() - this.debugStatusPage * maxStatus));
            if (statusPages > 1) {
                if (this.debugStatusPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                                this.debugStatusPage--;
                                this.rebuildButtons();
                            }).m_252987_(w - 58, statusTop - 2, 26, 14).m_253136_());
                }
                if (this.debugStatusPage < statusPages - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                                this.debugStatusPage++;
                                this.rebuildButtons();
                            }).m_252987_(w - 28, statusTop - 2, 26, 14).m_253136_());
                }
            }
        }
        // 状态开关（左下角；展开时按钮上直接写第几页）
        String toggle = this.debugStatusOpen
                ? (statusPages > 1 ? "\u00a7e\u72b6\u6001 " + (this.debugStatusPage + 1) + "/" + statusPages + " \u25b4"
                                   : "\u00a7e\u72b6\u6001 \u25b4")
                : "\u00a77\u72b6\u6001 \u25be";
        this.m_142416_(Button.m_253074_(Component.m_237113_(toggle), b -> {
                    this.debugStatusOpen = !this.debugStatusOpen;
                    this.rebuildButtons();
                }).m_252987_(8, h - 24, 64, 16).m_253136_());
        if (this.debugStatusLines == null) {
            this.graphicsHint("\u6b63\u5728\u5411\u5979\u8981\u4e00\u4efd\u6700\u65b0\u72b6\u6001\u2026");
            return;
        }
        int pages = 1;
        if (this.debugRows == null || this.debugRows.isEmpty()) {
            if (this.debugRows != null) {
                this.graphicsHint("\u5979\u8fd8\u6ca1\u6709\u80fd\u8c03\u8bd5\u7684\u8bb0\u5fc6\u2014\u2014\u591a\u548c\u5979\u804a\u804a\u5929\u3001\u4e00\u8d77\u5e72\u6d3b\uff0c\u8bb0\u5fc6\u4f1a\u6162\u6162\u957f\u51fa\u6765");
            }
        } else {
            int rowsTop = statusTop + (this.debugStatusOpen ? statusShown * TEXT_LINE_H + 8 : 0);
            int rowsBottom = h - 68;
            int pitch = DEBUG_ROW_H + 1;
            int maxRows = Math.max(1, (rowsBottom - rowsTop) / pitch);
            this.debugMaxRows = maxRows;
            pages = (this.debugRows.size() + maxRows - 1) / maxRows;
            final int lastPage = pages - 1;
            this.debugPage = Math.min(this.debugPage, pages - 1);
            int start = this.debugPage * maxRows;
            int end = Math.min(this.debugRows.size(), start + maxRows);
            int y = rowsTop;
            int btnW = Math.max(120, w - 40);
            for (int i = start; i < end; i++) {
                final String[] r = this.debugRows.get(i);
                final String key = r[1];
                boolean sel = key.equals(this.debugSelKey);
                this.m_142416_(Button.m_253074_(Component.m_237113_(
                                (sel ? "\u00a7e\u25b6 " : "\u00a77  ") + this.fitText(this.debugRowDigest(r), btnW - 30)),
                                b -> {
                                    this.debugSelKey = key.equals(this.debugSelKey) ? null : key;
                                    this.rebuildButtons();
                                }).m_252987_(cx - btnW / 2, y, btnW, DEBUG_ROW_H).m_253136_());
                y += pitch;
            }
            if (pages > 1) {
                if (this.debugPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u23ee"), b -> {
                                this.debugPage = 0;
                                this.rebuildButtons();
                            }).m_252987_(cx - 84, h - 24, 22, 16).m_253136_());
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25c0"), b -> {
                                this.debugPage--;
                                this.rebuildButtons();
                            }).m_252987_(cx - 58, h - 24, 22, 16).m_253136_());
                }
                if (this.debugPage < pages - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u25b6"), b -> {
                                this.debugPage++;
                                this.rebuildButtons();
                            }).m_252987_(cx + 36, h - 24, 22, 16).m_253136_());
                    this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77\u23ed"), b -> {
                                this.debugPage = lastPage;
                                this.rebuildButtons();
                            }).m_252987_(cx + 62, h - 24, 22, 16).m_253136_());
                }
            }
        }
        // 动作按钮：两行四列；只对选中的这条真用得上的那些点亮
        String[] r = this.debugSelRow();
        String st = this.debugSelState();
        boolean isPara = r != null && "para".equals(r[0]);
        boolean isRel = r != null && "rel".equals(r[0]);
        boolean live = isPara && !"deleted".equals(st);
        boolean relOn = isRel && !"inactive".equals(st);
        // 实测九百七十七【启用关系按钮点不动】：旧版没选中关系行时按钮照样写着「启用关系」却是灰的，
        // 玩家不知道该先选中什么。现在分三态——选中关系行 = 停用/启用；本页有关系但没选中 = 跳过去；
        // 一条关系都没有 = 保持灰，并把原因写进上方提示行。
        int relCount = this.debugRelCount();
        String relLabel;
        boolean relUsable;
        String relAction;
        if (isRel) {
            relLabel = relOn ? "\u505c\u7528\u5173\u7cfb" : "\u542f\u7528\u5173\u7cfb";
            relUsable = true;
            relAction = relOn ? "deactivate_rel" : "activate_rel";
        } else if (relCount > 0) {
            relLabel = "\u8df3\u5230\u5173\u7cfb \u25b8";
            relUsable = true;
            relAction = "goto_rel";
        } else {
            relLabel = "\u5173\u7cfb\u64cd\u4f5c";
            relUsable = false;
            relAction = "activate_rel";
        }
        int gap = 4;
        int bw = Math.max(56, Math.min(96, (w - 24 - 3 * gap) / 4));
        int ax = Math.max(8, cx - (4 * bw + 3 * gap) / 2);
        int ay1 = h - 64;
        int ay2 = h - 44;
        this.debugActionButton("\u2191 \u91cd\u8981\u5ea6", "salience_up", live, ax, ay1, bw, gap, 0);
        this.debugActionButton("\u2193 \u91cd\u8981\u5ea6", "salience_down", live, ax, ay1, bw, gap, 1);
        this.debugActionButton("\u5f3a\u5316", "reinforce", live, ax, ay1, bw, gap, 2);
        this.debugActionButton("\u524a\u5f31", "weaken", live, ax, ay1, bw, gap, 3);
        this.debugActionButton("\u6807\u4e3a\u4f5c\u5e9f", "delete", live, ax, ay2, bw, gap, 0);
        this.debugActionButton("\u6062\u590d", "restore", isPara && "deleted".equals(st), ax, ay2, bw, gap, 1);
        this.debugActionButton(relLabel, relAction, relUsable, ax, ay2, bw, gap, 2);
        this.debugActionButton("\u21bb \u5237\u65b0", "refresh", true, ax, ay2, bw, gap, 3);
    }

    /** 实测九百七十四：动作按钮统一样式——用不上的置灰禁用而不是藏起来，
     *  一眼看出选中的这一条不支持该操作 */
    private void debugActionButton(String label, String action, boolean usable,
                                   int ax, int y, int bw, int gap, int col) {
        Button b = Button.m_253074_(Component.m_237113_(label), btn -> {
                    if ("refresh".equals(action)) {
                        this.requestDebugSnapshot();
                    } else if ("goto_rel".equals(action)) {
                        this.selectFirstRelation();
                    } else {
                        this.sendDebugAction(action);
                    }
                }).m_252987_(ax + col * (bw + gap), y, bw, 18).m_253136_();
        b.f_93623_ = usable;
        this.m_142416_(b);
    }

    /** 实测九百七十七：本页关系条目数（调试列表里 kind=="rel" 的行） */
    private int debugRelCount() {
        if (this.debugRows == null) {
            return 0;
        }
        int n = 0;
        for (String[] r : this.debugRows) {
            if ("rel".equals(r[0])) {
                n++;
            }
        }
        return n;
    }

    /** 实测九百七十七：把第一条关系选中并翻到它所在的页——「启用关系」点不动时的直达入口 */
    private void selectFirstRelation() {
        if (this.debugRows == null) {
            return;
        }
        for (int i = 0; i < this.debugRows.size(); i++) {
            if ("rel".equals(this.debugRows.get(i)[0])) {
                this.debugSelKey = this.debugRows.get(i)[1];
                this.debugNote = "已跳到第一条关系——点下面那颗按钮即可停用 / 启用它";
                this.debugPage = i / Math.max(1, this.debugMaxRows);
                this.rebuildButtons();
                return;
            }
        }
    }

    /** 实测九百七十四：列表里一行的中文摘要（内部字段与英文长文不再突脸；全文看上方选中的这条） */
    private String debugRowDigest(String[] r) {
        String st = r.length > 4 && r[4] != null ? r[4] : "";
        StringBuilder sb = new StringBuilder();
        if ("deleted".equals(st)) {
            sb.append("\u00a7c\u3010\u5df2\u4f5c\u5e9f\u3011");
        } else if ("inactive".equals(st)) {
            sb.append("\u00a75\u3010\u5df2\u505c\u7528\u3011");
        }
        sb.append("rel".equals(r[0]) ? "\u00a7d\u5173\u7cfb \u00a77" : "\u00a7f\u8bb0\u5fc6 \u00a77").append(r[2]).append(" \u00a78| \u00a77");
        String t = r[3] == null ? "" : r[3];
        if (t.length() > 40) {
            int cut = 40;
            if (Character.isHighSurrogate(t.charAt(cut - 1))) {
                cut--;
            }
            t = t.substring(0, cut) + "\u2026";
        }
        return sb.append(t).toString();
    }

    /** 发送调试动作（没选中时在面板内提示，不刷聊天栏） */
    private void sendDebugAction(String action) {
        String[] r = this.debugSelRow();
        if (r == null) {
            this.debugNote = "\u5148\u70b9\u4e0a\u9762\u4e00\u884c\u9009\u4e2d\uff0c\u518d\u7528\u4e0b\u9762\u7684\u6309\u94ae";
            this.rebuildButtons();
            return;
        }
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookEntityPackets.MaidDebugActionPacket(
                        this.memoryMaidUuid, action, r[1]));
        this.debugNote = "\u5df2\u53d1\u9001\u2026";
    }

    /** 链路调试页渲染 */
    private void renderDebugView(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e\u94fe\u8def\u8c03\u8bd5 \u00b7 "
                + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                + (this.debugStatusLines == null ? "\uff08\u8bf7\u6c42\u4e2d\u2026\uff09" : ""), PANEL_TITLE_Y, 0xFFFFFF);
        this.drawCentered(graphics, this.debugContextHint(), PANEL_TITLE_Y + 8, 0x888888);
        String top = this.debugTopLine();
        if (!top.isEmpty()) {
            this.drawCentered(graphics, top, PANEL_TITLE_Y + 18, 0x888888);
        }
        if (this.debugStatusLines == null) {
            return;
        }
        int statusTop = CONTENT_TOP + 8;
        int statusShown = 0;
        if (this.debugStatusOpen) {
            int maxStatus = Math.max(3, (this.f_96544_ - 100 - statusTop) / TEXT_LINE_H);
            java.util.List<String> vis = this.wrapLines(this.debugStatusSource(), this.f_96543_ - 130);
            int sPages = Math.max(1, (vis.size() + maxStatus - 1) / maxStatus);
            this.debugStatusPage = Math.min(this.debugStatusPage, sPages - 1);
            int sStart = this.debugStatusPage * maxStatus;
            int n = Math.min(vis.size() - sStart, maxStatus);
            int y = statusTop;
            for (int i = 0; i < n; i++) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_(vis.get(sStart + i)),
                        12, y, 0xDDDDDD, false);
                y += TEXT_LINE_H;
            }
            statusShown = n;
        }
        if (this.debugRows != null && !this.debugRows.isEmpty()) {
            int rowsTop = statusTop + (this.debugStatusOpen ? statusShown * TEXT_LINE_H + 8 : 0);
            int maxRows = Math.max(1, (this.f_96544_ - 68 - rowsTop) / (DEBUG_ROW_H + 1));
            int pages = (this.debugRows.size() + maxRows - 1) / maxRows;
            if (pages > 1) {
                this.drawCentered(graphics, "\u00a77\u7b2c" + (this.debugPage + 1) + "/" + pages + " \u9875",
                        this.f_96544_ - 20, 0xAAAAAA);
            }
        }
        if (this.maidEmptyText != null) {
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    12, CONTENT_TOP + 30, 0x888888, false);
        }
    }

    // ============ 实测九百七十二：记忆图谱页（三张图共用一块内容区） ============

    /** 图谱页按钮：三张图切换 + 关系网翻页（另两张整张一屏） */
    private void graphViewButtons(int w, int h, int cx) {
        String[] labels = {"重要度分布", "记忆时间轴", "关系网络"};
        int bw = 84;
        int gap = 4;
        int total = labels.length * bw + (labels.length - 1) * gap;
        int x = cx - total / 2;
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            boolean on = this.graphMode == i;
            this.m_142416_(Button.m_253074_(Component.m_237113_(on ? "\u00a7e\u25cf " + labels[i] : "\u00a77" + labels[i]),
                            b -> {
                                this.graphMode = mode;
                                this.graphPage = 0;
                                this.rebuildButtons();
                            })
                    .m_252987_(x, h - 46, bw, 16).m_253136_());
            x += bw + gap;
        }
        int nodeCount = this.graphNode == null ? 0 : this.graphNode.size();
        int pages = Math.max(1, (nodeCount + GRAPH_NODES_PER_PAGE - 1) / GRAPH_NODES_PER_PAGE);
        if (this.graphMode == 2 && pages > 1) {
            this.graphPage = Math.min(this.graphPage, pages - 1);
            if (this.graphPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.graphPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 40, h - 26, 20, 20).m_253136_());
            }
            if (this.graphPage < pages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.graphPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 20, h - 26, 20, 20).m_253136_());
            }
        }
    }

    /** 图谱页渲染：标题 → 统计 → 当前那张图 */
    private void renderGraphView(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e记忆图谱 · "
                + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                + (this.graphLines == null ? "（请求中…）" : ""), PANEL_TITLE_Y, 0xFFFFFF);
        if (this.graphLines == null) {
            return;
        }
        long[] st = this.graphStat == null ? new long[7] : this.graphStat;
        this.drawCentered(graphics, "\u00a77段落 " + st[0] + " · 关系 " + st[1] + " · 日记 " + st[2]
                        + " · 永久 " + st[3] + " · 长期 " + st[4]
                        + (st[2] > 0 ? " · 覆盖第" + st[5] + "~" + st[6] + "天" : ""),
                PANEL_TITLE_Y + 8, 0x888888);
        int x0 = 16;
        int x1 = this.f_96543_ - 16;
        int yTop = CONTENT_TOP + 4;
        int yBot = this.f_96544_ - 52;
        if (x1 - x0 < 80 || yBot - yTop < 44) {
            return; // 窗口太矮：宁可不画，也不画出屏幕
        }
        switch (this.graphMode) {
            case 1 -> this.drawTimeline(graphics, x0, x1, yTop, yBot);
            case 2 -> this.drawRelationNet(graphics, x0, x1, yTop, yBot);
            default -> this.drawSalienceHist(graphics, x0, x1, yTop, yBot);
        }
    }

    /** 图 1·重要度分布：横轴 0~10 档，柱高 = 该档段落条数（柱顶标条数、柱底标档位） */
    private void drawSalienceHist(net.minecraft.client.gui.GuiGraphics graphics,
                                  int x0, int x1, int yTop, int yBot) {
        int[] hist = this.graphHist == null ? new int[11] : this.graphHist;
        int max = 1;
        int total = 0;
        for (int c : hist) {
            max = Math.max(max, c);
            total += c;
        }
        if (total == 0) {
            this.drawCentered(graphics, "\u00a77还没有记忆段落——多和她对话、一起做事后再回来看看",
                    (yTop + yBot) / 2, 0x888888);
            return;
        }
        graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a77横轴＝重要度档位（越高越容易被想起），柱高＝该档段落条数"),
                x0, yTop - 2, 0x888888, false);
        int baseY = yBot - 12;
        int topY = yTop + 10;
        int areaH = Math.max(10, baseY - topY);
        int slot = (x1 - x0) / 11;
        int barW = Math.max(4, slot - 6);
        for (int i = 0; i <= 10; i++) {
            int bx = x0 + slot * i + slot / 2;
            int hgt = hist[i] == 0 ? 0 : Math.max(2, (int) ((long) areaH * hist[i] / max));
            int left = bx - barW / 2;
            int right = left + barW;
            int color = i >= 8 ? 0xE0FFC83D : (i >= 5 ? 0xC055DD55 : 0xC0AAAAAA);
            if (hgt > 0) {
                graphics.m_280509_(left, baseY - hgt, right, baseY, color);
            } else {
                graphics.m_280509_(left, baseY - 1, right, baseY, 0x33AAAAAA);
            }
            String n = String.valueOf(hist[i]);
            graphics.m_280614_(this.f_96547_, Component.m_237113_(n), bx - this.f_96547_.m_92895_(n) / 2, baseY - hgt - 10,
                    hist[i] > 0 ? 0xCCCCCC : 0x666666, false);
            String b = String.valueOf(i);
            graphics.m_280614_(this.f_96547_, Component.m_237113_(b), bx - this.f_96547_.m_92895_(b) / 2, baseY + 2,
                    i >= 8 ? 0xFFC83D : 0x999999, false);
        }
    }

    /** 图 2·记忆时间轴：横轴 = 游戏日，四条泳道 = 日/3日/周/月四级日记，色条 = 一篇日记的覆盖区间 */
    private void drawTimeline(net.minecraft.client.gui.GuiGraphics graphics,
                              int x0, int x1, int yTop, int yBot) {
        if (this.graphTime == null || this.graphTime.isEmpty()) {
            this.drawCentered(graphics, "\u00a77还没有日记——睡一觉或跨天后她才会整理出记忆日记",
                    (yTop + yBot) / 2, 0x888888);
            return;
        }
        int minDay = Integer.MAX_VALUE;
        int maxDay = Integer.MIN_VALUE;
        for (int[] t : this.graphTime) {
            minDay = Math.min(minDay, t[0]);
            maxDay = Math.max(maxDay, t[1]);
        }
        maxDay = Math.max(maxDay, minDay);
        int span = Math.max(1, maxDay - minDay + 1);
        String[] laneName = {"日", "3日", "周", "月"};
        int[] laneColor = {0xC05AA9E8, 0xC055DD55, 0xC0FFAA33, 0xC0BB66DD};
        int gutter = 30;
        int gx0 = x0 + gutter;
        int axisY = yBot - 10;
        int laneH = Math.max(11, (axisY - yTop) / 4);
        int barH = Math.max(2, Math.min(6, laneH / 3));
        graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a77横轴＝游戏日（D 数字）；四条泳道＝日/3日/周/月日记，色条越长＝覆盖天数越多"),
                x0, yTop - 2, 0x888888, false);
        for (int l = 0; l < 4; l++) {
            int ly = yTop + l * laneH;
            graphics.m_280509_(x0, ly + laneH - 1, x1, ly + laneH, 0x22FFFFFF);
            graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a77" + laneName[l]), x0 + 2, ly + 2, 0x999999, false);
        }
        int tickStep = Math.max(1, span / 8);
        for (int d = minDay; d <= maxDay; d += tickStep) {
            int tx = gx0 + (int) ((long) (x1 - gx0) * (d - minDay) / span);
            graphics.m_280509_(tx, yTop, tx + 1, axisY, 0x15FFFFFF);
            graphics.m_280614_(this.f_96547_, Component.m_237113_("D" + d), tx, axisY + 1, 0x888888, false);
        }
        int[] laneSub = new int[4];
        int perLane = Math.max(1, (laneH - 4) / (barH + 1));
        for (int[] t : this.graphTime) {
            int l = Math.max(0, Math.min(3, t[2]));
            int bx = gx0 + (int) ((long) (x1 - gx0) * (t[0] - minDay) / span);
            int bx2 = gx0 + (int) ((long) (x1 - gx0) * (t[1] - minDay + 1) / span);
            if (bx2 <= bx) {
                bx2 = bx + 2;
            }
            int sub = laneSub[l]++;
            int by = yTop + l * laneH + 2 + (sub % perLane) * (barH + 1);
            graphics.m_280509_(bx, by, Math.min(bx2, x1), by + barH, laneColor[l]);
        }
    }

    /** 图 3·关系网络：实体排在圆上、连线 = 关系（越亮越可信），一页 GRAPH_NODES_PER_PAGE 个实体 */
    private void drawRelationNet(net.minecraft.client.gui.GuiGraphics graphics,
                                 int x0, int x1, int yTop, int yBot) {
        List<String[]> nodes = this.graphNode == null ? new ArrayList<>() : this.graphNode;
        if (nodes.isEmpty()) {
            this.drawCentered(graphics, "\u00a77还没有关系三元组——和她多聊些人和事，关系网会自己长出来",
                    (yTop + yBot) / 2, 0x888888);
            return;
        }
        int pages = Math.max(1, (nodes.size() + GRAPH_NODES_PER_PAGE - 1) / GRAPH_NODES_PER_PAGE);
        int page = Math.min(this.graphPage, pages - 1);
        int from = page * GRAPH_NODES_PER_PAGE;
        int to = Math.min(nodes.size(), from + GRAPH_NODES_PER_PAGE);
        List<String[]> vis = new ArrayList<>(nodes.subList(from, to));
        int n = vis.size();
        int cx = (x0 + x1) / 2;
        int cy = (yTop + yBot) / 2;
        int radius = Math.max(22, Math.min((x1 - x0) / 2 - 58, (yBot - yTop) / 2 - 22));
        int[] px = new int[n];
        int[] py = new int[n];
        for (int i = 0; i < n; i++) {
            double ang = -Math.PI / 2 + i * 2 * Math.PI / n;
            px[i] = cx + (int) Math.round(Math.cos(ang) * radius);
            py[i] = cy + (int) Math.round(Math.sin(ang) * radius);
        }
        java.util.Map<String, Integer> posOf = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            posOf.put(vis.get(i)[0], i);
        }
        int drawn = 0;
        if (this.graphEdge != null) {
            for (String[] e : this.graphEdge) {
                if (drawn >= 40) {
                    break;
                }
                Integer a = posOf.get(e[0]);
                Integer b = posOf.get(e[2]);
                if (a == null || b == null) {
                    continue;
                }
                drawn++;
                double conf = 0.5;
                try {
                    conf = Double.parseDouble(e[3]);
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintBookScreen", ignored);
                }
                int alpha = 0x30 + (int) (0x9F * Math.max(0.0, Math.min(1.0, conf)));
                this.drawThinLine(graphics, px[a], py[a], px[b], py[b], (alpha << 24) | 0x7FB0FF);
            }
        }
        for (int i = 0; i < n; i++) {
            graphics.m_280509_(px[i] - 3, py[i] - 3, px[i] + 3, py[i] + 3, 0xC0FFD700);
            graphics.m_280509_(px[i] - 1, py[i] - 1, px[i] + 1, py[i] + 1, 0xFFFF6A00);
            String name = this.fitText(vis.get(i)[0], 76);
            int lw = this.f_96547_.m_92895_(name);
            int lx = px[i] >= cx ? px[i] + 5 : px[i] - lw - 5;
            lx = Math.max(2, Math.min(this.f_96543_ - lw - 2, lx));
            int ly = Math.max(2, Math.min(this.f_96544_ - 22, py[i] - 4));
            graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a7f" + name), lx, ly, 0xFFFFFF, false);
            graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a78×" + vis.get(i)[1]), lx, ly + 9, 0x888888, false);
        }
        graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a77圆点＝实体，连线＝关系（越亮越可信）　第" + (page + 1) + "/" + pages + " 页"),
                x0, yTop - 2, 0x888888, false);
    }

    /** 实测九百七十二：两点间画一条细线——GuiGraphics 没有画线接口，按 2px 步长用 1px 方块拼出来 */
    private void drawThinLine(net.minecraft.client.gui.GuiGraphics graphics,
                              int ax, int ay, int bx, int by, int color) {
        int dx = bx - ax;
        int dy = by - ay;
        int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 2;
        if (steps <= 0) {
            graphics.m_280509_(ax, ay, ax + 1, ay + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = ax + dx * i / steps;
            int y = ay + dy * i / steps;
            graphics.m_280509_(x, y, x + 1, y + 1, color);
        }
    }

    /** v1.5.43：控制面板——v1.5.159 起只含行2（暂停/继续、速度、取消）；"全员加入"
     *  移到详情页行1（与返回/建造/区块显示并列）。
     *  v1.5.162：强制续建/强制建造已删除（建造默认强制执行，重复下达被拒绝）；
     *  本面板只在玩家处于建造区块内时显示。
     *  v1.5.180：操作目标 = 玩家所在区块（currentPlanId）——仅针对本区块 */
    private void addControlButtons() {
        // v1.5.252ae：控制按钮只在玩家处于建造区块内时显示——注释承诺但旧版未实现，
        // 区块外也显示按钮 → 点击后 currentPlanId 为空 → 服务端误报"区块不存在"
        // （实测：区块明明存在却提示不存在）
        if (this.currentPlanId == null || this.currentPlanId.isEmpty()) {
            return;
        }
        int cx = this.f_96543_ / 2;
        int h = this.f_96544_;
        final String cid = this.currentPlanId;
        String[] labels = {
                this.paused ? "继续建造" : "暂停建造",
                "速度" + this.speed,
                "取消建造"
        };
        int[] actions = {
                BlueprintBookBuildPackets.BuildControlPacket.TOGGLE_PAUSE,
                BlueprintBookBuildPackets.BuildControlPacket.CYCLE_SPEED,
                BlueprintBookBuildPackets.BuildControlPacket.CANCEL
        };
        // v1.5.162：3 个按钮（cx-70 起，64 间距）居中排布
        for (int i = 0; i < labels.length; i++) {
            final int action = actions[i];
            this.m_142416_(Button.m_253074_(Component.m_237113_(labels[i]),
                            b -> {
                                // v1.5.103：取消建造是破坏性操作（清计划/释放强制加载）→ 确认弹窗
                                if (action == BlueprintBookBuildPackets.BuildControlPacket.CANCEL) {
                                    this.confirmAction("取消建造？",
                                            "\u00a7e当前建造区块将被清除（区块标记删除），已建的部分将不再被识别为半成品、会被当成障碍物处理",
                                            "\u00a7c确认取消",
                                            () -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                                    new BlueprintBookBuildPackets.BuildControlPacket(action, null, cid)));
                                } else {
                                    BlueprintBookNetworking.CHANNEL.sendToServer(
                                            new BlueprintBookBuildPackets.BuildControlPacket(action, null, cid));
                                }
                            })
                    .m_252987_(cx - 70 + i * 64, h - 52, 56, 20).m_253136_());
        }
    }

    @Override
    public void m_88315_(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(graphics); // renderBackground
        // v1.1.0 实测三百一十三（反馈："模组内新添加物品的 ui 背景都是用的原版 MC
        // 格式，过于单调。在背景加上更多的颜色，ui 颜色也变一下"）：Promaid 手册
        // 品牌渐变背景——蓝金主题（与手册建造功能呼应）。半透明色带叠加 = 渐变
        //（m_280509_ 走 ARGB，Alpha 叠加）
        int w = this.f_96543_;
        int h = this.f_96544_;
        int bandL = Math.max(4, w / 2 - 300);
        int bandR = Math.min(w - 4, w / 2 + 300);
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x55122A4E);   // 底层：深海军蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x220F3A8C);   // 中层：宝蓝
        graphics.m_280509_(bandL, 4, bandR, h - 4, 0x1A1B4E8C);   // 高光：亮蓝
        graphics.m_280509_(bandL, 4, bandR, 14, 0xFF2C5F9E);      // 顶部饰条：靛蓝
        graphics.m_280509_(bandL, 4 + 10, bandR, 14 + 1, 0x80D4A017); // 金线
        switch (this.view) {
            case VIEW_BUILD -> this.renderBuild(graphics);
            case VIEW_MAIDS -> this.renderMaids(graphics);
            case VIEW_MAID_DETAIL -> this.renderMaidDetail(graphics);
            case VIEW_REGION_MAIDS -> this.renderRegionMaids(graphics);
            case VIEW_MEMORY -> this.renderMemory(graphics);
            case VIEW_PRESET -> this.renderPreset(graphics);
            default -> this.renderHome(graphics);
        }
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    /** 大目录渲染 */
    private void renderHome(net.minecraft.client.gui.GuiGraphics graphics) {
        // v1.5.64：固定布局（按钮在 70/130，文字不重叠）
        // v1.5.100b：改名（Promaid 手册 → Promaid 功能手册）；v1.5.192：统一为 Promaid 手册
        this.drawCentered(graphics, "\u00a7ePromaid 手册", 20, 0xFFFFFF);
        // v1.5.252w：大目录不再显示建造进度/进度条（要求——目录页保持干净，
        // 进度显示保留在建造面板/女仆管理等具体页面）
        this.drawCentered(graphics, "\u00a77点击下方入口进入对应面板", 46, 0x888888);
    }

    /** 建造面板渲染 */
    private void renderBuild(net.minecraft.client.gui.GuiGraphics graphics) {
        if (this.viewingEntry != null) {
            // 材料详情页
            BlueprintBookBuildPackets.Entry entry = this.viewingEntry;
            List<String> items = this.materialItems(entry);
            String matTitle = "\u00a7e\u300c" + entry.name() + "\u300d所需材料（"
                    + items.size() + " 种 · 第 " + (this.matPage + 1) + "/"
                    + this.materialPages(entry) + " 页）";
            this.drawCentered(graphics, matTitle, 10, 0xFFFFFF);
            String desc = entry.desc();
            int mi = desc == null ? -1 : desc.indexOf("材料：");
            if (mi > 0) {
                desc = desc.substring(0, mi);
            }
            // v1.5.84：描述行（含"共多少块"）居中
            this.drawCentered(graphics, "\u00a77" + (desc == null ? "" : desc), 24, 0xAAAAAA);
            // v1.5.82：进度显示放在内容区底部（翻页/底部控制按钮之上）
            this.renderProgress(graphics, this.f_96544_ - BOTTOM_ZONE - 18);
            // 材料区：2 列 × 8 行，小字体（行高 9）；v1.5.71 列宽自适应不溢出
            // v1.5.84：网格整体居中 + 每行文本在格内居中（不再偏左）
            int cellW = Math.max(120, (this.f_96543_ - 80) / MAT_COLS);
            int gridX = (this.f_96543_ - cellW * MAT_COLS) / 2;
            int y = CONTENT_TOP;
            int start = this.matPage * MAT_COLS * MAT_ROWS_PER_PAGE;
            int end = Math.min(items.size(), start + MAT_COLS * MAT_ROWS_PER_PAGE);
            for (int i = start; i < end; i++) {
                int col = (i - start) % MAT_COLS;
                int row = (i - start) / MAT_COLS;
                String text = this.fitText(items.get(i), cellW - 8);
                // v1.5.111：格内居中 = 以格中心为圆心（drawCenteredString 圆心），
                // 旧版 (cellW-textW)/2 圆心 → 每格文本又左移 textW/2 → 整列偏左
                graphics.m_280653_(this.f_96547_, Component.m_237113_(text),
                        gridX + col * cellW + cellW / 2,
                        y + row * MAT_LINE_H, 0xFFFFFF);
            }
            return;
        }
        // 建造总目录（v1.5.374：网格多列渲染 + 搜索）
        java.util.List<BlueprintBookBuildPackets.Entry> list = this.filteredEntries();
        int cols = this.buildGridCols();
        int rows = this.buildGridRowsPerPage();
        int perPage = cols * rows;
        int totalPages = Math.max(1, (list.size() + perPage - 1) / perPage);
        int start = Math.min(list.size(), this.buildPage * perPage);
        int end = Math.min(list.size(), start + perPage);
        int margin = 10;
        int gap = 4;
        int cellW = (this.f_96543_ - margin * 2 - (cols - 1) * gap) / cols;
        String q = this.searchQuery == null ? "" : this.searchQuery.trim();
        String title = q.isEmpty()
                ? "\u00a7e建造 · 总目录（" + list.size() + " 个建筑 · 点击名称查看材料）"
                : "\u00a7e搜索「" + q + "」· 共 " + list.size() + " 个结果 · 点击名称查看材料";
        this.drawCentered(graphics, title, PANEL_TITLE_Y, 0xFFFFFF);
        for (int i = start; i < end; i++) {
            BlueprintBookBuildPackets.Entry entry = list.get(i);
            int idx = i - start;
            int col = idx % cols;
            int row = idx / cols;
            int x = margin + col * (cellW + gap);
            int cy = 72 + row * (NAME_BUTTON_H + 2);
            boolean ext = entry.id().startsWith("maid_smart_ext:");
            int delW = ext ? 14 : 0;
            int nameW = cellW - delW - 4;
            String text = this.fitText(entry.name(), nameW - 2);
            graphics.m_280614_(this.f_96547_, Component.m_237113_(text),
                    x + 4, cy + (NAME_BUTTON_H - 8) / 2, 0xFFFFFF, false);
            if (ext) {
                graphics.m_280614_(this.f_96547_, Component.m_237113_("\u00a7c\u2716"),
                        x + cellW - delW + 3, cy + (NAME_BUTTON_H - 8) / 2, 0xFF5555, false);
            }
        }
        if (this.maidEmptyText != null) {
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    10, CONTENT_TOP + 40, 0x888888, false);
        }
        if (totalPages > 1) {
            // v1.1.0 实测二十五：页码画在两箭头中间（箭头 cx±(20..40) 仅 20px 宽）
            this.drawCentered(graphics, "\u00a77第 " + (this.buildPage + 1) + " / " + totalPages + " 页",
                    this.f_96544_ - 24, 0xAAAAAA);
        }
        this.renderProgress(graphics, this.f_96544_ - BOTTOM_ZONE - 18);
    }

    /** 女仆管理面板渲染 */
    private void renderMaids(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e女仆管理 · 建造状态女仆名单（点击女仆查看/绑定/解绑）",
                PANEL_TITLE_Y, 0xFFFFFF);
        // v1.5.182：有效区块列表（信息显示；v1.5.183：fitText 像素级截断防突出屏幕）
        // v1.5.279：区块【打标签】竖排——每区块两行：名字/状态行 + 创建坐标行
        //（反馈："区块上面只会显示某某建筑建造中，再隔一行显示玩家在哪个坐标创建的"）
        if (this.buildRegions.isEmpty()) {
            this.drawCentered(graphics, "\u00a78有效建造区块：0 —— 先到建造目录创建区块", PANEL_TITLE_Y + 9, 0x888888);
        } else {
            int ry = PANEL_TITLE_Y + 9;
            int shown = Math.min(this.buildRegions.size(), 6); // 上限 6 个，防撑爆女仆列表
            this.drawCentered(graphics, "\u00a7e有效建造区块 " + this.buildRegions.size() + "：", ry, 0xAAAAAA);
            ry += 10;
            for (int ri = 0; ri < shown; ri++) {
                String[] r = this.buildRegions.get(ri);
                this.drawCentered(graphics, this.fitText("\u00a7b「" + r[1] + "」\u00a77("
                                + r[2] + "\u00b7" + r[3] + ")", this.f_96543_ - 40), ry, 0xFFFFFF);
                ry += 10;
                // v1.5.279：创建坐标（r[11..13] = 玩家创建区块时的原点）
                String ox = r.length > 11 ? r[11] : "?";
                String oy = r.length > 12 ? r[12] : "?";
                String oz = r.length > 13 ? r[13] : "?";
                this.drawCentered(graphics, "\u00a78创建于 " + ox + ", " + oy + ", " + oz, ry, 0x888888);
                ry += 10;
            }
            if (this.buildRegions.size() > shown) {
                this.drawCentered(graphics, "\u00a78\u2026 其余 " + (this.buildRegions.size() - shown)
                        + " 个", ry, 0x888888);
            }
        }
        if (this.maidEmptyText != null) {
            // v1.5.110：旧版 m_280653_ 以 x=10 为圆心 → 文本几乎全裁出屏幕；改左对齐
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    10, CONTENT_TOP, 0x888888, false);
        }
        // v1.5.302：女仆管理页【不再画进度条】——旧版底部进度条叠在女仆名单最后几行
        // 上（反馈："此页面字段重叠，进度条不应该在这个地方显示"）；进度条移到
        // 区块详细页（renderRegionMaids），"只在对应区块的详细界面那边显示"
    }

    /** v1.5.182：女仆详情页渲染（绑定/解绑指定区块） */
    private void renderMaidDetail(net.minecraft.client.gui.GuiGraphics graphics) {
        String name = "";
        String task = "";
        String bind = "";
        if (this.detailMaidUuid != null) {
            for (String[] m : this.allMaids) {
                if (m[0].equals(this.detailMaidUuid)) {
                    name = m[1];
                    task = BlueprintBookNetworking.taskNameCn(m.length > 4 ? m[4] : "");
                    bind = m.length > 5 ? m[5] : "";
                    break;
                }
            }
        }
        // v1.5.183：fitText 截断防突出屏幕（长名字/长区块名）
        this.drawCentered(graphics, this.fitText("\u00a7e女仆详情 · " + name + "（" + task
                + (bind.isEmpty() ? "" : " · 已绑定「" + bind + "」") + "）",
                this.f_96543_ - 40), PANEL_TITLE_Y, 0xFFFFFF);
        this.drawCentered(graphics, "\u00a78点选上方区块 → 绑定到该区块；解绑 = 离开当前区块",
                PANEL_TITLE_Y + 9, 0x888888);
        if (this.maidEmptyText != null) {
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    10, CONTENT_TOP, 0x888888, false);
        }
    }

    /** v1.5.182：区块绑定女仆名单渲染（设置工头） */
    private void renderRegionMaids(net.minecraft.client.gui.GuiGraphics graphics) {
        String regionName = "";
        for (String[] r : this.buildRegions) {
            if (r[0].equals(this.currentPlanId)) {
                regionName = r[1];
                break;
            }
        }
        this.drawCentered(graphics, this.fitText("\u00a7e区块「" + regionName + "」· 绑定女仆名单"
                        + "（一区块一工头 · 右侧设为工头）", this.f_96543_ - 40),
                PANEL_TITLE_Y, 0xFFFFFF);
        if (this.maidEmptyText != null) {
            graphics.m_280614_(this.f_96547_, Component.m_237113_(this.maidEmptyText),
                    10, CONTENT_TOP, 0x888888, false);
        }
        // v1.5.302：区块详细页显示进度条（反馈："进度条只在对应区块的详细界面那边
        // 显示出来就可以了"——女仆管理页的进度条已移除，这里补上本区块的进度；
        // 有翻页按钮时上移避开底行，无翻页贴底）
        // v1.5.308：maxRows 公式与按钮区一致（预留进度条 48px，防行数口径不一致
        // 导致进度条位置与翻页按钮错位）
        int maxRows = Math.max(3, (this.f_96544_ - 30 - CONTENT_TOP - 32 - 48) / (MAID_ROW_H + 1));
        int boundCount = 0;
        for (String[] m : this.allMaids) {
            if (this.currentPlanId != null && m.length > 8 && this.currentPlanId.equals(m[8])) {
                boundCount++;
            }
        }
        int total = Math.max(1, (boundCount + maxRows - 1) / maxRows);
        this.renderProgress(graphics, total > 1 ? this.f_96544_ - BOTTOM_ZONE - 18
                : this.f_96544_ - PROGRESS_BOTTOM_GAP);
    }

    @Override
    public void m_7379_() {
        this.maidEmptyText = null;
        // v1.5.190：进入详情/名单页时重置各自的页码（防跨页残留越界索引）
        this.detailRegionPage = 0;
        this.regionMaidPage = 0;
        // v1.5.73：关闭时快照当前界面状态（ESC 退出/点击建造图纸都会走这里），
        // 下次打开由构造函数恢复——与帕秋莉手册一致的"回到上次页面"体验
        lastView = this.view;
        lastViewingId = this.viewingEntry == null ? null : this.viewingEntry.id();
        lastBuildPage = this.buildPage;
        lastMatPage = this.matPage;
        lastMaidPage = this.maidPage;
        super.m_7379_();
    }

    /**
     * v1.5.103：通用确认弹窗——用 MC 原版 ConfirmScreen（删除世界的那个界面样式）：
     * 标题 + 正文 + 确认/取消；确认后执行 onYes，取消或确认后都回到当前手册页面。
     */
    private void confirmAction(String title, String message, String yesLabel, Runnable onYes) {
        BlueprintBookScreen self = this;
        net.minecraft.client.gui.screens.ConfirmScreen[] holder = new net.minecraft.client.gui.screens.ConfirmScreen[1];
        holder[0] = new net.minecraft.client.gui.screens.ConfirmScreen(
                ok -> {
                    if (ok) {
                        onYes.run();
                    }
                    // v1.0.4：onYes 可能已打开新界面（如游戏内文件选择器）——此时不再强制
                    // 切回手册，否则刚打开的界面会被立刻顶掉（表现为"点击继续导入后没反应"）
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
                    if (mc.f_91080_ == holder[0]) {
                        mc.m_91152_(self);
                    }
                },
                Component.m_237113_("\u00a7c" + title),
                Component.m_237113_(message),
                Component.m_237113_(yesLabel),
                Component.m_237113_("\u00a77\u53d6\u6d88"));
        net.minecraft.client.Minecraft.m_91087_().m_91152_(holder[0]);
    }

    /**
     * 建造此图纸确认流程（v1.1.0 实测九十六：两步流程回归+金色态投影）——
     * 1. 第一次点击：开启【金色预览】（以玩家为中心、随移动移动，叠加青色幽灵
     *    方块投影）+ 系统提示 → 退出手册在世界里走位选点；
     * 2. 再次点击（重开手册）：先把金色预览刷新到当前脚下（取消弹窗重新选位后
     *    再点也一样），再弹确认框——明确告知会摧毁周边障碍物；确认才创建区块
     *   （SelectBlueprintPacket），金色框关闭、红色区块框+橙色幽灵投影由每秒 同步接管，并重置"看过预览"标记（下一轮建造仍先看范围）。
     */
    private void startBuildFlow(String vid) {
        boolean previewed = com.maidsmart.build.BlueprintAreaPreview.wasShown();
        // 金色预览每次点击都刷新到当前脚下（青色幽灵方块随 show 内 ensureProjection 显示）
        com.maidsmart.build.BlueprintAreaPreview.show(
                vid,
                this.viewingEntry.sizeX(), this.viewingEntry.sizeY(),
                this.viewingEntry.sizeZ());
        // v1.1.0 实测一百九十（反馈："按 Z 键可以旋转区块的方向。但是这个操作没有
        // 提示。我觉得最好在玩家点击建造此建筑之后，在系统提示里弹出这样的系统提示"）：
        // Z 键提示从【仅首次预览发送】改为【每次点击建造此图纸都发送】——旧版只有
        // 首次点击发（那次恰好刷走了就再看不到），重开手册直接进确认框的第二次点击
        // 零提示，而金色预览激活期间 Z 键始终可用（标注"确认后不可改"）。
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7e【请确认建造范围】金色框以你为中心显示占地范围（青色幽灵方块为建筑投影）。"
                            + "\u00a7b按 Z 键顺时针旋转建筑朝向（每次 90°）\u00a7e，选好位置与朝向后再次打开手册点击"
                            + "「建造此图纸」确认建造。女仆搭建会直接摧毁区块内的障碍物。"));
        }
        if (!previewed) {
            // 第 1 步：系统提示选位 → 退出手册看金色框+幽灵投影
            this.m_7379_();
            return;
        }
        // 第 2 步：确认弹窗 → 确认才创建区块
        this.confirmAction("确定建造在这里？",
                "\u00a7e区块范围 = 你脚下为中心（金色框当前位置，青色幽灵方块即建筑形态）。\n"
                        + "\u00a7b仍可按 Z 键旋转建筑朝向（每次 90°，确认后不可改）。\n"
                        + "\u00a7c注意：女仆搭建会直接摧毁区块内的树、建筑等障碍物。\n"
                        + "\u00a77确认后创建区块，工地会显示红色区域框与橙色蓝图投影，到女仆管理绑定女仆开始建造。",
                "\u00a7c确定，开始建造",
                () -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookBuildPackets.SelectBlueprintPacket(vid,
                                    com.maidsmart.build.BlueprintAreaPreview.previewQuarters()));
                    // 金色框关闭；红色区块框+橙色幽灵投影由服务端每秒同步接管
                    com.maidsmart.build.BlueprintAreaPreview.clear();
                    // 本轮确认完成 → 重置标记，下一轮建造重新先看范围
                    com.maidsmart.build.BlueprintAreaPreview.resetSeen();
                });
    }
}
