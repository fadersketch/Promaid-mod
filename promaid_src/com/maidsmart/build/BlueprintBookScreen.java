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
 *   材料详情：2 列 × 8 行 = 16 种/页（用户规则，小字体），放不下自动翻页
 * - 女仆管理面板：128 格内所有建造女仆（上限 30 只，翻页），行内点击暂停/继续 + 设工头，
 *   右上角全部暂停/继续
 * - 实时进度：客户端每 2 秒轮询 SHOW_PROGRESS，服务端回发 ProgressUpdatePacket，
 *   进度/速度/女仆状态自动刷新（无需按键、无需退出手册）
 *
 * v1.5.71 布局重构（修复小窗口"完全混乱"）：
 * 旧版所有行数/坐标按大窗口固定（目录 10 行/页、女仆 10 行/页、材料列宽下限 200px），
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
    /** 女仆管理上限 */
    /** 材料详情：2 列 × 8 行 = 16 种/页（用户规则），小行高 */
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

    private List<BlueprintBookNetworking.Entry> entries;
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
    private BlueprintBookNetworking.Entry viewingEntry = null;
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

    protected BlueprintBookScreen(List<BlueprintBookNetworking.Entry> entries, List<String[]> maids,
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
            for (BlueprintBookNetworking.Entry e : this.entries) {
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
                for (BlueprintBookNetworking.Entry e : this.entries) {
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

    /** 收到目录包后打开界面（v1.5.159：同时关闭建造范围预览——再次打开手册 = 关闭预览）
     *  v1.5.275：initialView 0=大目录 1=女仆管理（配置面板"跳转女仆管理"） */
    public static void open(List<BlueprintBookNetworking.Entry> entries, List<String[]> maids,
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
                // 区块内 → 区块内右击跳转不到对应区块详情页（用户反馈）
                if (p.m_123341_() >= x && p.m_123341_() < x + w
                        && p.m_123342_() >= y && p.m_123342_() < y + h
                        && p.m_123343_() >= z && p.m_123343_() < z + d) {
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
                    new BlueprintBookNetworking.BuildControlPacket(
                            BlueprintBookNetworking.BuildControlPacket.SHOW_PROGRESS, null));
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
        } catch (Exception ignored) {
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
    private List<String> materialItems(BlueprintBookNetworking.Entry entry) {
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
    private int materialPages(BlueprintBookNetworking.Entry entry) {
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
    private java.util.List<BlueprintBookNetworking.Entry> filteredEntries() {
        String q = this.searchQuery == null ? "" : this.searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return this.entries;
        }
        java.util.List<BlueprintBookNetworking.Entry> out = new java.util.ArrayList<>();
        for (BlueprintBookNetworking.Entry e : this.entries) {
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
            // v1.5.102c：进度文字整体右对齐（用户要求"向右移一大步"——结尾的
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
            // （缺料停滞时 ema→0 → 进度条旁只剩"56%"，用户："搭建速度和剩余
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

    /** 目录页点击：搜索框聚焦兜底 + 条目（打开详情）/删除（✖）坐标命中 */
    @Override
    public boolean m_6375_(double mouseX, double mouseY, int button) {
        if (button == 0 && this.view == VIEW_BUILD && this.viewingEntry == null && this.searchBox != null) {
            if (this.searchBox.m_5953_(mouseX, mouseY)) {
                this.searchBox.m_93692_(true);
            } else if (mouseY >= 72 && mouseY <= this.f_96544_ - 30) {
                java.util.List<BlueprintBookNetworking.Entry> list = this.filteredEntries();
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
                        BlueprintBookNetworking.Entry entry = list.get(idx);
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
                                                new BlueprintBookNetworking.DeleteBlueprintPacket(deleteId)));
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
        // v1.5.252h：5 个入口 + 底部进度条防重叠——旧版 4 按钮（64/100/136/172
        // 高 28 间距 36）加第 5 个会压住底部进度条（renderHome 文字 h-40）。
        // 行高按窗口高自适应压缩（照 PromaidConfigScreen 目录页模式），
        // 起始 y 上浮保证按钮组底 ≤ h-44（进度文字顶之上 4px）。
        int bw = 170;
        int rowH = 28;
        if (h < 238) {
            rowH = 24;
        }
        if (h < 214) {
            rowH = 20;
        }
        if (h < 188) {
            rowH = 17;
        }
        if (h < 180) {
            rowH = 15;
        }
        int bh = Math.max(12, rowH - 3);
        int gap = rowH - bh;
        int y0 = Math.max(52, Math.min(64, h - rowH * 5 - 44));
        // v1.5.252h：详细介绍放目录【最上面】（用户反馈"放最下面怪怪的"）
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
            // v1.5.171：取消区块内限制——用户反馈"继续建造按钮消失、走到区块内也看不到"
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
            // v1.5.190 修复：旧版 inRegion 时 x 从 cx-190 起、按钮总宽拉到 cx+260
            //（w=427 时"区块显示"右缘 473 > 427 出屏 46px，右半边点不到）。
            // 改为按可用宽度自适应：5 个按钮均分（最小 54px），总宽不超过 w-16，
            // 任意窗口宽度都完整在屏内。
            int avail = w - 16;
            int gap2 = 4;
            int[] bw5 = new int[5];
            int need = (5 - 1) * gap2;
            for (int i = 0; i < 5; i++) {
                bw5[i] = Math.max(54, (avail - need) / 5);
            }
            int total5 = need + bw5[0] * 5;
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
                    .m_252987_(x, h - 30, bw5[0], 20).m_253136_());
            x += bw5[0] + gap2;
            // 全员加入 + 名单（v1.5.178 区块内限制；v1.5.188b：区块内自动先显示区块框）
            if (inRegion) {
                final String cid = this.currentPlanId;
                this.m_142416_(Button.m_253074_(Component.m_237113_("全员加入"),
                                b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookNetworking.BuildControlPacket(
                                                BlueprintBookNetworking.BuildControlPacket.JOIN_ALL, null, cid)))
                        .m_252987_(x, h - 30, bw5[1], 20).m_253136_());
                x += bw5[1] + gap2;
                // v1.5.182：名单——区块内右击手册时额外多出的按钮：查看绑定该区块的
                // 女仆名单 + 设置工头（一区块一工头）
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e名单"),
                                b -> {
                                    this.view = VIEW_REGION_MAIDS;
                                    this.regionMaidPage = 0;
                                    this.rebuildButtons();
                                })
                        .m_252987_(x, h - 30, bw5[2], 20).m_253136_());
                x += bw5[2] + gap2;
            }
            // 建造此图纸（v1.5.188b：首次点击强制打开区块预览 + 系统提示确认范围；
            // 再次点击弹确认框——明确告知会摧毁周边障碍物，确认后才真正创建区块）
            this.m_142416_(Button.m_253074_(Component.m_237113_("建造此图纸"),
                            b -> this.startBuildFlow(vid))
                    .m_252987_(x, h - 30, bw5[3], 20).m_253136_());
            x += bw5[3] + gap2;
            // 区块显示（总是显示——预览占地范围；v1.5.188b：点击先关闭手册，保证
            // 玩家在世界里看到金色框再决定位置）
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e区块显示"),
                            b -> {
                                com.maidsmart.build.BlueprintAreaPreview.show(
                                        this.viewingEntry.sizeX(), this.viewingEntry.sizeY(),
                                        this.viewingEntry.sizeZ());
                                this.m_7379_();
                            })
                    .m_252987_(x, h - 30, bw5[4], 20).m_253136_());
            // 材料翻页（16 种/页；固定在材料区下方，不与底部控制区冲突）
            int pages = this.materialPages(this.viewingEntry);
            this.matPage = Math.min(this.matPage, pages - 1);
            if (pages > 1) {
                int matY = CONTENT_TOP + MAT_ROWS_PER_PAGE * MAT_LINE_H + 8;
                if (this.matPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("< 上一页"),
                                    b -> {
                                        this.matPage--;
                                        this.rebuildButtons();
                                    })
                            .m_252987_(cx - 90, matY, 80, 16).m_253136_());
                }
                if (this.matPage < pages - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("下一页 >"),
                                    b -> {
                                        this.matPage++;
                                        this.rebuildButtons();
                                    })
                            .m_252987_(cx + 10, matY, 80, 16).m_253136_());
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
        java.util.List<BlueprintBookNetworking.Entry> list = this.filteredEntries();
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
        // v1.5.376：套用原版物品搜索栏样式——无边框 + 白字（默认深色字在深色面板上
        // 几乎看不清，输入看起来"没反应/延迟"；字节码实证 CreativeModeInventoryScreen
        // 搜索框 = m_94182_(setBordered false) + m_94202_(setTextColor 白)）
        this.searchBox.m_94182_(false);
        this.searchBox.m_94202_(0xFFFFFF);
        this.searchBox.m_94144_(this.searchQuery);
        this.searchBox.m_94151_(s -> {
            this.searchQuery = s;
            this.buildPage = 0;
        });
        this.m_142416_(this.searchBox);
        // 翻页（底行）——v1.5.159：目录页只有 返回/上一页/下一页/退出
        if (totalPages > 1) {
            if (this.buildPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                b -> {
                                    this.buildPage--;
                                    this.rebuildButtons();
                                }).m_252987_(this.f_96543_ / 2 - 160, this.f_96544_ - 30, 100, 20).m_253136_());
            }
            if (this.buildPage < totalPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                b -> {
                                    this.buildPage++;
                                    this.rebuildButtons();
                                }).m_252987_(this.f_96543_ / 2 + 60, this.f_96544_ - 30, 100, 20).m_253136_());
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
        // v1.5.290：建造名单页直达女仆管理（用户："名单内部仍然没有跳转按键"）
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
     *  v1.0.4：改用游戏内文件浏览器（FilePickScreen）——旧版 java.awt.FileDialog 在
     *  以 -Djava.awt.headless=true 运行的 JVM 里抛 HeadlessException，选择器打不开。 */
    private void pickAndImportWorld() {
        com.maidsmart.gui.FilePickScreen.open(this,
                "选择世界存档压缩包（.zip）",
                new String[]{"zip"},
                path -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookNetworking.WorldImportPacket(path));
                    this.chatHint("\u00a77已发送世界地图导入请求，结果请看聊天框");
                });
    }

    /** v1.5.220：打开文件选择器选蓝图文件 → 发送导入请求（单机场景客户端与服务端
     *  同机，绝对路径服务端可直接读取；结果回聊天框）。
     *  v1.0.4：改用游戏内文件浏览器（FilePickScreen）——java.awt.FileDialog 在
     *  headless JVM 下抛 HeadlessException 打不开。 */
    private void pickAndImportBuild() {
        com.maidsmart.gui.FilePickScreen.open(this,
                "选择建筑蓝图文件（.schem/.litematic/.nbt/.snbt/.schematic/.json/.zip）",
                new String[]{"schem", "litematic", "nbt", "snbt", "schematic", "json", "zip"},
                path -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookNetworking.BuildImportPacket(path));
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
                                    new BlueprintBookNetworking.BuildControlPacket(
                                            BlueprintBookNetworking.BuildControlPacket.TOGGLE_PAUSE, null, pid)))
                    .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        }
        this.m_142416_(Button.m_253074_(Component.m_237113_("← 大目录"),
                        b -> {
                            this.view = VIEW_HOME;
                            this.rebuildButtons();
                        })
                .m_252987_(8, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        // 女仆翻页（底行，独占一行不与其他按钮冲突）
        if (total > 1) {
            if (this.maidPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                b -> {
                                    this.maidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 160, h - 26, 100, 20).m_253136_());
            }
            if (this.maidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                b -> {
                                    this.maidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 60, h - 26, 100, 20).m_253136_());
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
            // 区块翻页（页脚行，独占一行不与绑定按钮冲突）
            if (rTotal > 1) {
                int py = h - 26;
                if (this.detailRegionPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                    b -> {
                                        this.detailRegionPage--;
                                        this.rebuildButtons();
                                    }).m_252987_(cx - 160, py, 100, 20).m_253136_());
                }
                if (this.detailRegionPage < rTotal - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                    b -> {
                                        this.detailRegionPage++;
                                        this.rebuildButtons();
                                    }).m_252987_(cx + 60, py, 100, 20).m_253136_());
                }
            }
        }
        int y = CONTENT_TOP + regionRow * 15 + 8;
        // v1.5.183：绑定/解绑逻辑修正——【未绑定】一律显示绑定按钮（无论建筑/非建筑
        // 任务，绑定自动切任务）；【已绑定】才显示解绑
        if (isBound) {
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7e解绑（离开当前区块）"),
                            b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new BlueprintBookNetworking.BuildControlPacket(
                                            BlueprintBookNetworking.BuildControlPacket.UNBIND_MAID, uuid, null)))
                    .m_252987_(cx - 140, y, 280, 20).m_253136_());
        } else if (this.selectedPlanId != null) {
            final String sid = this.selectedPlanId;
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7a绑定到选中区块"),
                            b -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                    new BlueprintBookNetworking.BuildControlPacket(
                                            BlueprintBookNetworking.BuildControlPacket.BIND_MAID, uuid, sid)))
                    .m_252987_(cx - 140, y, 280, 20).m_253136_());
        }
        // v1.5.305：删除「⚙ 女仆配置」按钮（用户："有 bug 不想修，直接删了；
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
        //（用户："又一次出现了进度条重合在了一起"）；48px = 4 行状态文本 + 进度条
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
                        //（旧版是空按钮，用户："右击区块详细页里面的名单，要有跳转功能"）
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
                                        new BlueprintBookNetworking.BuildControlPacket(
                                                BlueprintBookNetworking.BuildControlPacket.SET_FOREMAN, uuid, pid)))
                        .m_252987_(cx + btnW / 2 - 88, y, 88, MAID_ROW_H).m_253136_());
            }
            y += MAID_ROW_H + 1;
        }
        if (bound.isEmpty()) {
            this.graphicsHint("该区块还没有绑定女仆——点右上「♀ 女仆管理」进女仆管理页，点女仆行进详情页绑定。");
        }
        // v1.5.298：本页跳转女仆管理（用户："在此页面要的跳转界面仍然没有出现"——
        // 旧版此页只有「← 返回详情」，提示却指向女仆管理——空名单页是死胡同；
        // 加右上角直达按钮）
        this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7b\u2640 女仆管理"),
                        b -> {
                            this.view = VIEW_MAIDS;
                            this.maidPage = 0;
                            this.rebuildButtons();
                        })
                .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
        // v1.5.190：绑定女仆翻页（页脚行）
        if (total > 1) {
            int py = h - 26;
            if (this.regionMaidPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                b -> {
                                    this.regionMaidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 160, py, 100, 20).m_253136_());
            }
            if (this.regionMaidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                b -> {
                                    this.regionMaidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 60, py, 100, 20).m_253136_());
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
    /** v1.5.192：可调试对象行 {type, key, info, text}（段落 para / 关系 rel） */
    private List<String[]> debugRows = null;
    /** v1.5.192：选中的调试对象（下标，-1 = 未选中） */
    private int debugSelected = -1;
    /** v1.5.192：调试对象行分页 */
    private int debugPage = 0;
    /** v1.2.1：链路调试状态区分页（小窗口状态行超 maxStatus 时 ◀/▶ 翻页） */
    private int debugStatusPage = 0;

    /** 服务端下发记忆行后更新显示 */
    public void showMemoryLines(String maidUuid, List<String> lines) {
        if (this.memoryMaidUuid != null && this.memoryMaidUuid.equals(maidUuid)) {
            this.memoryLines = lines == null ? new ArrayList<>() : lines;
            this.memoryPage = 0;
            this.rebuildButtons();
        }
    }

    /** v1.5.192：服务端下发调试快照后更新显示 */
    public void showDebugSnapshot(String maidUuid, List<String> statusLines, List<String[]> rows) {
        if (this.memoryMaidUuid != null && this.memoryMaidUuid.equals(maidUuid)) {
            this.debugStatusLines = statusLines == null ? new ArrayList<>() : statusLines;
            this.debugRows = rows == null ? new ArrayList<>() : rows;
            if (this.debugSelected >= (this.debugRows == null ? 0 : this.debugRows.size())) {
                this.debugSelected = -1;
            }
            this.debugPage = 0;
            this.debugStatusPage = 0;
            this.rebuildButtons();
        }
    }

    /** v1.5.192：发送调试快照请求（进入链路调试页/刷新时调用） */
    private void requestDebugSnapshot() {
        if (this.memoryMaidUuid == null) {
            return;
        }
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookNetworking.MaidDebugRequestPacket(this.memoryMaidUuid));
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
                            new BlueprintBookNetworking.ClearMemoryPacket(uuid));
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
                                this.debugSelected = -1;
                                this.memTab = 0;
                                this.rebuildButtons();
                            })
                    .m_252987_(8, TOP_BTN_Y, 90, TOP_BTN_H).m_253136_());
            // v1.5.192：页内 tab（记忆 / 链路调试）
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 0 ? "\u00a7e\u25cf 记忆" : "\u00a77记忆"),
                            b -> {
                                this.memTab = 0;
                                this.rebuildButtons();
                            })
                    .m_252987_(cx - 130, TOP_BTN_Y, 100, TOP_BTN_H).m_253136_());
            this.m_142416_(Button.m_253074_(Component.m_237113_(
                            this.memTab == 1 ? "\u00a7e\u25cf 链路调试" : "\u00a77链路调试"),
                            b -> {
                                this.memTab = 1;
                                this.debugSelected = -1;
                                this.debugStatusLines = null;
                                this.debugRows = null;
                                this.rebuildButtons();
                                this.requestDebugSnapshot();
                            })
                    .m_252987_(cx - 24, TOP_BTN_Y, 110, TOP_BTN_H).m_253136_());
            // v1.5.103：清空该女仆全部记忆（段落/关系/画像）——确认框 + 服务端清空 + 刷新
            this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a7c清空记忆"),
                            b -> this.confirmClearMemory())
                    .m_252987_(w - 88, TOP_BTN_Y, 80, TOP_BTN_H).m_253136_());
            if (this.memTab == 1) {
                // ---- 链路调试页 ----
                this.debugViewButtons(w, h, cx);
                return;
            }
        // v1.5.190：记忆行分页 + 高度自适应——旧版从 y=52 起无限画下去，
        // 行数一多就盖住底部按钮/翻页键；改为每页最多 8 行、底部翻页。
        int maxLines = Math.max(4, (h - 30 - CONTENT_TOP - 20) / 10);
        if (this.memoryLines != null && this.memoryLines.size() > maxLines) {
            int pages = (this.memoryLines.size() + maxLines - 1) / maxLines;
            this.memoryPage = Math.min(this.memoryPage, pages - 1);
            if (this.memoryPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                b -> {
                                    this.memoryPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 160, h - 26, 100, 20).m_253136_());
            }
            if (this.memoryPage < pages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                b -> {
                                    this.memoryPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 60, h - 26, 100, 20).m_253136_());
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
                                this.debugSelected = -1;
                                this.rebuildButtons();
                                // 请求记忆
                                BlueprintBookNetworking.CHANNEL.sendToServer(
                                        new BlueprintBookNetworking.MemoryViewRequestPacket(uuid));
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
                                        new BlueprintBookNetworking.AiMemoryTogglePacket(uuid, !memOn));
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
                                        new BlueprintBookNetworking.AiLlmTogglePacket(uuid, !llmOn));
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
                this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                b -> {
                                    this.maidPage--;
                                    this.rebuildButtons();
                                }).m_252987_(cx - 160, h - 26, 100, 20).m_253136_());
            }
            if (this.maidPage < total - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                b -> {
                                    this.maidPage++;
                                    this.rebuildButtons();
                                }).m_252987_(cx + 60, h - 26, 100, 20).m_253136_());
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
            this.drawCentered(graphics, "\u00a7e女仆记忆 · "
                    + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                    + (this.memoryLines == null ? "（请求中…）" : ""), PANEL_TITLE_Y, 0xFFFFFF);
            // v1.5.100b：居中提示（旧版塞进记忆行导致左对齐不居中）
            this.drawCentered(graphics, "\u00a77（也可在女仆配置界面切换记忆）", PANEL_TITLE_Y + 8, 0x888888);
            if (this.memoryLines == null) {
                return;
            }
            // 每页行数按可用高度自适应（v1.5.190：旧版固定 8 行，小窗口盖底）
            int maxLines = Math.max(4, (this.f_96544_ - 30 - CONTENT_TOP - 20) / 12);
            int y = CONTENT_TOP + 12;
            int start = this.memoryPage * maxLines;
            int end = Math.min(this.memoryLines.size(), start + maxLines);
            for (int i = start; i < end; i++) {
                String line = this.fitText(this.memoryLines.get(i), this.f_96543_ - 40);
                graphics.m_280614_(this.f_96547_, Component.m_237113_(line),
                        20, y, 0xFFFFFF, false);
                y += 12;
            }
            if (this.memoryLines.size() > maxLines) {
                this.drawCentered(graphics, "\u00a77第" + (this.memoryPage + 1) + "/"
                        + ((this.memoryLines.size() + maxLines - 1) / maxLines) + " 页",
                        this.f_96544_ - 44, 0x888888);
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
                } catch (Exception ignored) {
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

    /** 链路调试页按钮：状态行区（顶部）+ 可调试对象行（分页，点击选中）+ 动作按钮组 */
    private void debugViewButtons(int w, int h, int cx) {
        int maxStatus = Math.max(4, (h - 30 - CONTENT_TOP - 120) / 12);
        // v1.2.1：状态区分页 ◀/▶（右上角，仅在状态行超 maxStatus 时出现——小窗口不丢行）
        if (this.debugStatusLines != null && this.debugStatusLines.size() > maxStatus) {
            int sPages = (this.debugStatusLines.size() + maxStatus - 1) / maxStatus;
            this.debugStatusPage = Math.min(this.debugStatusPage, sPages - 1);
            if (this.debugStatusPage > 0) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77◀"),
                                b -> {
                                    this.debugStatusPage--;
                                    this.rebuildButtons();
                                })
                        .m_252987_(w - 58, CONTENT_TOP + 2, 26, 14).m_253136_());
            }
            if (this.debugStatusPage < sPages - 1) {
                this.m_142416_(Button.m_253074_(Component.m_237113_("\u00a77▶"),
                                b -> {
                                    this.debugStatusPage++;
                                    this.rebuildButtons();
                                })
                        .m_252987_(w - 28, CONTENT_TOP + 2, 26, 14).m_253136_());
            }
        }
        // 状态行分页（statusLines 可能比一行高——单独页码）
        // 状态区 + 对象区共用 debugPage（对象区为主，状态区贴顶固定显示前几行）
        if (this.debugStatusLines == null) {
            this.graphicsHint("调试数据请求中…");
        }
        if (this.debugRows == null || this.debugRows.isEmpty()) {
            if (this.debugRows != null) {
                this.graphicsHint("没有可调试的记忆对象（多对话积累后出现）");
            }
        } else {
            int maxRows = Math.max(3, (h - 30 - CONTENT_TOP - 150) / 15);
            int pages = (this.debugRows.size() + maxRows - 1) / maxRows;
            this.debugPage = Math.min(this.debugPage, pages - 1);
            int start = this.debugPage * maxRows;
            int end = Math.min(this.debugRows.size(), start + maxRows);
            int y = CONTENT_TOP + 12 + maxStatus * 12 + 10;
            int btnW = Math.max(120, w - 40);
            for (int i = start; i < end; i++) {
                final int idx = i;
                final String[] r = this.debugRows.get(i);
                String line = (r[0].equals("rel") ? "\u00a7d[关系 " : "\u00a7e[")
                        + r[2] + "] " + r[3];
                boolean sel = idx == this.debugSelected;
                this.m_142416_(Button.m_253074_(Component.m_237113_(
                                (sel ? "\u00a7e\u25b6 " : "\u00a77  ") + this.fitText(line, 200)),
                                b -> {
                                    this.debugSelected = (this.debugSelected == idx) ? -1 : idx;
                                    this.rebuildButtons();
                                })
                        .m_252987_(cx - btnW / 2, y, btnW, 14).m_253136_());
                y += 15;
            }
            // 翻页
            if (pages > 1) {
                if (this.debugPage > 0) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("上一页"),
                                    b -> {
                                        this.debugPage--;
                                        this.rebuildButtons();
                                    }).m_252987_(cx - 160, h - 26, 100, 18).m_253136_());
                }
                if (this.debugPage < pages - 1) {
                    this.m_142416_(Button.m_253074_(Component.m_237113_("下一页"),
                                    b -> {
                                        this.debugPage++;
                                        this.rebuildButtons();
                                    }).m_252987_(cx + 60, h - 26, 100, 18).m_253136_());
                }
            }
        }
        // 动作按钮组（作用于选中行；未选中提示）
        // v1.5.219：刷新并入动作组统一布局——旧版刷新固定 x=8 且与动作组同
        // y=h-48，窗口窄时动作组占满整行（ax 钳到 8）→ 刷新按钮直接压住"↑重要度"
        int ay = h - 48;
        String[][] actions = {
                {"\u00a7e↑重要度", "salience_up"},
                {"\u00a7e↓重要度", "salience_down"},
                {"\u00a7c标记删除", "delete"},
                {"\u00a7a恢复", "restore"},
                {"\u00a7b强化", "reinforce"},
                {"\u00a7d停用关系", "deactivate_rel"},
                {"\u00a7f↻ 刷新", "refresh"},
        };
        int bw = 76;
        int gap = 4;
        int totalW = actions.length * bw + (actions.length - 1) * gap;
        int ax = cx - totalW / 2;
        if (ax < 8) {
            bw = Math.max(56, (w - 16 - (actions.length - 1) * gap) / actions.length);
            ax = cx - (actions.length * bw + (actions.length - 1) * gap) / 2;
        }
        for (int i = 0; i < actions.length; i++) {
            final String act = actions[i][1];
            this.m_142416_(Button.m_253074_(Component.m_237113_(actions[i][0]),
                            b -> {
                                if ("refresh".equals(act)) {
                                    this.requestDebugSnapshot();
                                } else {
                                    this.sendDebugAction(act);
                                }
                            })
                    .m_252987_(ax + i * (bw + gap), ay, bw, 18).m_253136_());
        }
    }

    /** 发送调试动作（选中行未选中时聊天提示） */
    private void sendDebugAction(String action) {
        if (this.debugSelected < 0 || this.debugRows == null
                || this.debugSelected >= this.debugRows.size()) {
            this.chatHint("\u00a77先点击选中一行记忆再执行动作");
            return;
        }
        String[] r = this.debugRows.get(this.debugSelected);
        BlueprintBookNetworking.CHANNEL.sendToServer(
                new BlueprintBookNetworking.MaidDebugActionPacket(
                        this.memoryMaidUuid, action, r[1]));
    }

    /** 链路调试页渲染 */
    private void renderDebugView(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e链路调试 · "
                + (this.memoryMaidName == null ? "" : this.memoryMaidName)
                + (this.debugStatusLines == null ? "（请求中…）" : ""), PANEL_TITLE_Y, 0xFFFFFF);
        this.drawCentered(graphics, "\u00a77点击下方记忆行选中，再用底部按钮调试（↑↓重要度/删除/恢复/强化/停用关系）",
                PANEL_TITLE_Y + 8, 0x888888);
        if (this.debugStatusLines == null) {
            return;
        }
        // 状态区（分页——小窗口状态行超 maxStatus 时 ◀/▶ 翻页；v1.2.1）
        int maxStatus = Math.max(4, (this.f_96544_ - 30 - CONTENT_TOP - 120) / 12);
        int sPages = (this.debugStatusLines.size() + maxStatus - 1) / maxStatus;
        if (sPages > 1) {
            this.debugStatusPage = Math.min(this.debugStatusPage, sPages - 1);
        }
        int y = CONTENT_TOP + 4;
        int sStart = this.debugStatusPage * maxStatus;
        int n = Math.min(this.debugStatusLines.size() - sStart, maxStatus);
        // 状态翻页按钮在右侧时给文本留出右缘
        int lineW = sPages > 1 ? this.f_96543_ - 130 : this.f_96543_ - 40;
        for (int i = 0; i < n; i++) {
            String line = this.fitText(this.debugStatusLines.get(sStart + i), lineW);
            graphics.m_280614_(this.f_96547_, Component.m_237113_(line),
                    12, y, 0xDDDDDD, false);
            y += 12;
        }
        // 对象行（分页，选中高亮由按钮文本承载；这里画行下标提示）
        if (this.debugRows != null && !this.debugRows.isEmpty()) {
            int maxRows = Math.max(3, (this.f_96544_ - 30 - CONTENT_TOP - 150) / 15);
            int pages = (this.debugRows.size() + maxRows - 1) / maxRows;
            int start = this.debugPage * maxRows;
            int end = Math.min(this.debugRows.size(), start + maxRows);
            if (pages > 1) {
                // v1.5.219：页码移到翻页按钮行上方（y=h-28）——旧版画在 h-44
                // 与底部动作按钮组（h-48~h-30）重叠（"第X页"压在标记删除/恢复上）
                this.drawCentered(graphics, "\u00a77第" + (this.debugPage + 1) + "/" + pages + " 页",
                        this.f_96544_ - 28, 0x888888);
            }
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
        // （用户实测：区块明明存在却提示不存在）
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
                BlueprintBookNetworking.BuildControlPacket.TOGGLE_PAUSE,
                BlueprintBookNetworking.BuildControlPacket.CYCLE_SPEED,
                BlueprintBookNetworking.BuildControlPacket.CANCEL
        };
        // v1.5.162：3 个按钮（cx-70 起，64 间距）居中排布
        for (int i = 0; i < labels.length; i++) {
            final int action = actions[i];
            this.m_142416_(Button.m_253074_(Component.m_237113_(labels[i]),
                            b -> {
                                // v1.5.103：取消建造是破坏性操作（清计划/释放强制加载）→ 确认弹窗
                                if (action == BlueprintBookNetworking.BuildControlPacket.CANCEL) {
                                    this.confirmAction("取消建造？",
                                            "\u00a7e当前建造区块将被清除（区块标记删除），已建的部分将不再被识别为半成品、会被当成障碍物处理",
                                            "\u00a7c确认取消",
                                            () -> BlueprintBookNetworking.CHANNEL.sendToServer(
                                                    new BlueprintBookNetworking.BuildControlPacket(action, null, cid)));
                                } else {
                                    BlueprintBookNetworking.CHANNEL.sendToServer(
                                            new BlueprintBookNetworking.BuildControlPacket(action, null, cid));
                                }
                            })
                    .m_252987_(cx - 70 + i * 64, h - 52, 56, 20).m_253136_());
        }
    }

    @Override
    public void m_88315_(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.m_280039_(graphics); // renderBackground
        switch (this.view) {
            case VIEW_BUILD -> this.renderBuild(graphics);
            case VIEW_MAIDS -> this.renderMaids(graphics);
            case VIEW_MAID_DETAIL -> this.renderMaidDetail(graphics);
            case VIEW_REGION_MAIDS -> this.renderRegionMaids(graphics);
            case VIEW_MEMORY -> this.renderMemory(graphics);
            default -> this.renderHome(graphics);
        }
        super.m_88315_(graphics, mouseX, mouseY, partialTick);
    }

    /** 大目录渲染 */
    private void renderHome(net.minecraft.client.gui.GuiGraphics graphics) {
        // v1.5.64：固定布局（按钮在 70/130，文字不重叠）
        // v1.5.100b：改名（Promaid 手册 → Promaid 功能手册）；v1.5.192：统一为 Promaid 手册
        this.drawCentered(graphics, "\u00a7ePromaid 手册", 20, 0xFFFFFF);
        // v1.5.252w：大目录不再显示建造进度/进度条（用户要求——目录页保持干净，
        // 进度显示保留在建造面板/女仆管理等具体页面）
        this.drawCentered(graphics, "\u00a77点击下方入口进入对应面板", 46, 0x888888);
    }

    /** 建造面板渲染 */
    private void renderBuild(net.minecraft.client.gui.GuiGraphics graphics) {
        if (this.viewingEntry != null) {
            // 材料详情页
            BlueprintBookNetworking.Entry entry = this.viewingEntry;
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
        java.util.List<BlueprintBookNetworking.Entry> list = this.filteredEntries();
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
            BlueprintBookNetworking.Entry entry = list.get(i);
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
            this.drawCentered(graphics, "\u00a77第 " + (this.buildPage + 1) + " / " + totalPages + " 页",
                    this.f_96544_ - 24, 0x888888);
        }
        this.renderProgress(graphics, this.f_96544_ - BOTTOM_ZONE - 18);
    }

    /** 女仆管理面板渲染 */
    private void renderMaids(net.minecraft.client.gui.GuiGraphics graphics) {
        this.drawCentered(graphics, "\u00a7e女仆管理 · 建造状态女仆名单（点击女仆查看/绑定/解绑）",
                PANEL_TITLE_Y, 0xFFFFFF);
        // v1.5.182：有效区块列表（信息显示；v1.5.183：fitText 像素级截断防突出屏幕）
        // v1.5.279：区块【打标签】竖排——每区块两行：名字/状态行 + 创建坐标行
        //（用户："区块上面只会显示某某建筑建造中，再隔一行显示玩家在哪个坐标创建的"）
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
        // 上（用户："此页面字段重叠，进度条不应该在这个地方显示"）；进度条移到
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
        // v1.5.302：区块详细页显示进度条（用户："进度条只在对应区块的详细界面那边
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
        net.minecraft.client.Minecraft.m_91087_().m_91152_(new net.minecraft.client.gui.screens.ConfirmScreen(
                ok -> {
                    if (ok) {
                        onYes.run();
                    }
                    net.minecraft.client.Minecraft.m_91087_().m_91152_(self);
                },
                Component.m_237113_("\u00a7c" + title),
                Component.m_237113_(message),
                Component.m_237113_(yesLabel),
                Component.m_237113_("\u00a77\u53d6\u6d88")));
    }

    /**
     * v1.5.188b：建造此图纸固定确认流程——
     * 1. 第一次点击：强制打开区块显示（金色框以玩家为中心预览占地范围）+ 系统提示
     *    告知"确定范围后再次打开手册点击建造"→ 直接退出手册（玩家在世界里看框选位）；
     * 2. 再次打开手册再次点击：弹确认框【建造在这里？】——明确告知女仆搭建会直接
     *    摧毁区块内的树/建筑等障碍物；点确认才真正创建区块（SelectBlueprintPacket），
     *    并提示玩家去绑定女仆。
     * v1.5.204：修复"卡在第一步死循环"——旧版重开手册（open → clear）会重置
     *  "看过预览"标记，第 1 步提示"再次打开手册点击确认"后重开点击仍走第 1 步。
     *  现在标记跨手册会话保留（clear 只关金色框），只有【确认创建成功后】
     *  resetSeen() 重置 → 下一轮建造仍需先看范围（防误操作保留）。
     */
    private void startBuildFlow(String vid) {
        boolean previewed = com.maidsmart.build.BlueprintAreaPreview.wasShown();
        if (!previewed) {
            // 第 1 步：强制预览 + 系统提示，退出手册看框选位
            com.maidsmart.build.BlueprintAreaPreview.show(
                    this.viewingEntry.sizeX(), this.viewingEntry.sizeY(),
                    this.viewingEntry.sizeZ());
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
            if (mc.f_91074_ != null) {
                mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7e【请确认建造范围】金色框以你为中心显示占地范围，移动选好位置后"
                                + " 再次打开手册点击「建造此图纸」确认建造。女仆搭建会直接摧毁区块内的障碍物。"));
            }
            this.m_7379_();
            return;
        }
        // 第 2 步：确认弹窗（再次打开手册点击才会走到这里）→ 确认才创建区块
        this.confirmAction("确定建造在这里？",
                "\u00a7e区块范围 = 你脚下为中心（金色框显示过的那片区域）。\n"
                        + "\u00a7c注意：女仆搭建会直接摧毁区块内的树、建筑等障碍物。\n"
                        + "\u00a77确认后创建区块，之后到女仆管理里绑定女仆开始建造。",
                "\u00a7c确定，开始建造",
                () -> {
                    BlueprintBookNetworking.CHANNEL.sendToServer(
                            new BlueprintBookNetworking.SelectBlueprintPacket(vid));
                    com.maidsmart.build.BlueprintAreaPreview.clear();
                    // v1.5.204：本轮确认完成 → 重置预览标记，下一轮建造重新先看范围
                    com.maidsmart.build.BlueprintAreaPreview.resetSeen();
                });
    }
}
