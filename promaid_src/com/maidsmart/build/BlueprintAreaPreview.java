package com.maidsmart.build;

/**
 * 建造区块标记 + 蓝图投影（客户端渲染）。
 *
 * 历史：v1.5.159 区块显示（金色玩家中心预览）→ v1.5.164 红色固定框 →
 * v1.1.0 实测八十二 幽灵方块投影（金/红双路径）→ 实测九十五 删「区块显示」
 * 独立按钮 + 新增每秒区块同步 → 实测九十六 金色预览回归。
 *
 * v1.1.0 实测九十五【根因修复】：此前携带区块行的包只在打开手册/创建计划时
 * 一次性下发，真正开始建造后玩家关掉手册走到工地，客户端没有任何区块数据 →
 * 橙色幽灵方块从不显示。现在服务端每秒广播 RegionSyncPacket
 *（BlueprintBookNetworking.broadcastRegionSync）驱动：
 * - 红色固定框：进行中/暂停中的建造计划（多区块各一框，顶部悬浮文字+创建坐标）
 * - 橙色幽灵方块：按计划原点落地（与实际搭建同一锚点），玩家走近即见建筑最终
 *   形态与朝向，随玩家移动自由观察（>96 格距离剔除省性能）
 * - 计划取消/完成后空列表推送自动清除所有框与投影
 *
 * v1.1.0 实测九十六【金色预览回归】：「建造此图纸」未确认阶段的玩家中心金色框
 * （随移动）+ 青色幽灵方块——show()/wasShown()/resetSeen()/clear() 只服务该
 * 流程；独立的「区块显示」按钮保持删除。点云由 ProjectionRequest/ProjectionData
 * 包按蓝图 id 请求缓存，金/红两态共用。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class BlueprintAreaPreview {
    private static boolean registered = false;

    /** v1.1.0 实测九十六：金色预览回归（反馈："未确认时金色区域随着玩家移动这个
     *  功能很重要"）——「建造此图纸」未确认阶段的玩家中心预览，叠加青色幽灵方块；
     *  独立的「区块显示」按钮保持删除，金色预览只服务建造确认流程 */
    private static boolean active = false;
    private static int sizeX = 1;
    private static int sizeY = 1;
    private static int sizeZ = 1;
    private static String previewId = null;
    /** 是否已看过本次金色预览（建造确认流程第 1 步放行第 2 步）；重开手册不重置
     *  ——clear 只关金色框渲染（v1.5.204"卡第一步死循环"教训），仅 resetSeen 显式重置 */
    private static boolean previewSeen = false;
    /** v1.1.0 实测九十七：金色预览当前朝向（0~3 × 90° 顺时针）——按转向键 +1；
     *  换蓝图时归零，同一蓝图取消后重新选位保留上次选择 */
    private static int previewQuarters = 0;

    /**
     * 实测五百五十三②：预览落点——**打开预览那一刻**的玩家脚下格。此后不再随玩家移动
     * （旧版 onRender 每帧取玩家位置 = 框跟着人走，落点不可控），改由微调界面
     * （BuildPlacementScreen 的 X±1/Y±1/Z±1）与「回到脚下」按钮控制；
     * 确认建造时这个坐标随 SelectBlueprintPacket 下发，服务端按它落地。
     */
    private static net.minecraft.core.BlockPos previewOrigin = null;

    /** 实测五百五十三①：投影调色板（key → 每项 "i~blockId~stateSnbt"）+ 解析好的
     *  BlockState 数组缓存（调色板换了就失效）——真方块渲染要用它把每个点还原成
     *  原版模型（石砖就是石砖、楼梯朝向也对） */
    private static final java.util.Map<String, String[]> PALETTES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Object[]> PARSED_STATES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** v1.5.180：实际建造区块的红色固定框（多区块共存——每个区块一框）
     *  框 = {x0,y0,z0,x1,y1,z1}；名称与框一一对应（顶部悬浮文字） */
    private static final java.util.List<double[]> REGION_BOXES = new java.util.ArrayList<>();
    private static final java.util.List<String> REGION_NAMES = new java.util.ArrayList<>();
    /** v1.5.290：每个区块的创建坐标文本（"x, y, z"——玩家创建区块时的原点；
     *  渲染在名字下方第二行。v1.5.279 起服务端下发 r[11..13]，v1.5.290 encode
     *  修 14 字段后真正到达客户端） */
    private static final java.util.List<String> REGION_ORIGINS = new java.util.ArrayList<>();
    /** v1.1.0 实测八十二：每个区块的蓝图 id（r[10]）+ 原点整数坐标（r[11..13]）——
     *  幽灵方块投影按【计划原点】落地（与实际搭建同一锚点，位置零偏差） */
    private static final java.util.List<String> REGION_BPS = new java.util.ArrayList<>();
    private static final java.util.List<int[]> REGION_ORIGINS_POS = new java.util.ArrayList<>();

    /** v1.1.0 实测八十二：投影点云缓存（key = "blueprintId#quarters"，值为 Object[]
     *  平铺 [x,y,z,BlockState, x,y,z,BlockState, …]；REQUESTED 防重复请求。
     *  v1.1.0 实测一百零九：改存 BlockState——渲染真实方块模型（Litematica 风格） */
    private static final java.util.Map<String, Object[]> PROJECTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> REQUESTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 投影缓存/请求的复合键（实测九十七：同蓝图不同朝向各自一份点云） */
    private static String projKey(String blueprintId, int quarters) {
        return blueprintId + "#" + Math.floorMod(quarters, 4);
    }

    /** v1.5.290：每个区块的橙影投影键（"bp#q"，r[10] 蓝图 id + r[14] 朝向） */
    private static final java.util.List<String> REGION_PROJ_KEYS = new java.util.ArrayList<>();
    /** v1.1.0 实测二百零四：最近一次实际提交的幽灵盒数（变化才落日志） */
    private static int lastDrawnCount = -1;
    /** v1.1.0 实测八十三b：投影链路诊断日志（latest.log 搜 "projection"） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private BlueprintAreaPreview() {
    }

    /** v1.5.188b：区块内控制一体化——自动重新打开红色区块框（只要服务端推送过
     *  区块范围就保持显示） */
    public static void ensureShown() {
        if (!REGION_BOXES.isEmpty()) {
            ensureRegistered();
        }
    }

    /**
     * 开启金色预览：以玩家为中心的 W×H×D 金色框，随玩家移动（每帧取玩家位置），
     * 同时叠加青色幽灵方块投影（实测八十二/九十六：金色状态也能看形态朝向）。
     * 由「建造此图纸」流程调用；再次打开手册即关闭（open → clear）。
     */
    public static void show(String blueprintId, int sx, int sy, int sz) {
        sizeX = Math.max(1, sx);
        sizeY = Math.max(1, sy);
        sizeZ = Math.max(1, sz);
        // v1.1.0 实测九十七：换蓝图归零朝向；同一蓝图重新选位保留上次旋转选择
        if (!blueprintId.equals(previewId)) {
            previewQuarters = 0;
        }
        previewId = blueprintId;
        active = true;
        previewSeen = true; // 看过预览 → 建造确认流程放行第 2 步
        // 实测五百五十三②：落点 = 这一刻的玩家脚下格（之后不再跟着人走）
        resetOriginToPlayer();
        ensureRegistered();
        ensureProjection(blueprintId, previewQuarters);
    }

    /** 实测五百五十三②：当前落点（null = 还没开过预览） */
    public static net.minecraft.core.BlockPos origin() {
        return previewOrigin;
    }

    /** 实测五百五十三②：直接设置落点（微调界面用） */
    public static void setOrigin(net.minecraft.core.BlockPos pos) {
        if (pos != null) {
            previewOrigin = pos;
        }
    }

    /** 实测五百五十三②：落点平移（微调按钮 X±1/Y±1/Z±1 用） */
    public static void shiftOrigin(int dx, int dy, int dz) {
        if (previewOrigin != null) {
            previewOrigin = previewOrigin.m_7918_(dx, dy, dz);
        }
    }

    /** 实测五百五十三②：落点拉回玩家脚下（「回到脚下」按钮） */
    public static void resetOriginToPlayer() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            previewOrigin = mc.f_91074_.m_20183_();
        }
    }

    /** 实测五百五十三②：占地尺寸（金色框与微调界面显示用，不含朝向互换） */
    public static int[] previewSize() {
        boolean swapped = (previewQuarters & 1) != 0;
        return new int[]{swapped ? sizeZ : sizeX, sizeY, swapped ? sizeX : sizeZ};
    }

    /**
     * v1.1.0 实测九十七：按转向键顺时针转 90°（整个建筑整体：占地 W/D 互换 +
     * 方块状态转向 + 青色幽灵投影刷新）。仅金色预览态响应；确认建造时该朝向
     * 随 SelectBlueprintPacket 落地。
     */
    public static void rotateClockwise() {
        if (!active || previewId == null) {
            return;
        }
        previewQuarters = (previewQuarters + 1) & 3;
        ensureProjection(previewId, previewQuarters);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7b【建造转向】建筑已顺时针旋转至 " + (previewQuarters * 90)
                            + "\u00a7b°——青色幽灵为当前朝向投影，确认建造后以此落地"));
        }
    }

    /** 是否已看过金色预览（建造确认流程第 1 步放行判断） */
    public static boolean wasShown() {
        return previewSeen;
    }

    /** 实测五百五十三②：逆时针 90°（微调界面的「左转」按钮；Z 键仍是顺时针） */
    public static void rotateCounterClockwise() {
        if (!active || previewId == null) {
            return;
        }
        previewQuarters = (previewQuarters + 3) & 3;
        ensureProjection(previewId, previewQuarters);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7b【建造转向】建筑已逆时针旋转至 " + (previewQuarters * 90)
                            + "\u00a7b°——确认建造后以此落地"));
        }
    }

    /** v1.1.0 实测九十七：当前选定的朝向（0~3 × 90° 顺时针）——确认建造时随包下发 */
    public static int previewQuarters() {
        return previewQuarters;
    }

    /** 实测五百五十三②：当前预览的蓝图 id（微调界面的「确认建造」要用） */
    public static String previewBlueprintId() {
        return previewId;
    }

    /** 建造确认成功后重置——下一轮建造仍先看范围（防误操作） */
    public static void resetSeen() {
        previewSeen = false;
    }

    /** 关闭金色预览渲染（打开手册时调用；不重置 previewSeen——v1.5.204 教训）
     *  v1.1.0 实测一百四十七【金色框去不掉根治】：实测一百三十二加诊断日志时把
     *  `active = false` 误删（日志替换了状态复位）——金色预览开启后打开手册/确认
     *  建造都关不掉，框永远跟着玩家移动。恢复复位；调用点 BlueprintBookScreen.open
     *  = "再次打开手册 = 关预览"（见 render 标签"（打开手册关闭）"） */
    public static void clear() {
        if (active) {
            // v1.1.0 实测一百三十二：金色轮廓消失链路——正常关闭记录日志排查用
            //（"又"字眼：玩家感受到反复消失/重建——实测一百二十九同样的问题）
            com.maidsmart.tool.PromaidLog.log("投影", "clear（金色预览关闭）");
        }
        active = false;
    }

    /**
     * v1.5.180：设置实际建造区块（红色固定框，多框）——v1.1.0 实测九十五起由
     * 服务端每秒 RegionSyncPacket 驱动；行格式
     * {planId, 显示名, 维度名, 状态, x, y, z, W, H, D, blueprintId, 创建X, 创建Y, 创建Z}；
     * 空列表 = 无进行中计划（取消/完成）→ 清空所有框与投影。
     */
    public static void setRegions(java.util.List<String[]> regions) {
        // v1.1.0 实测一百三十二（反馈："建筑投影的大致建筑轮廓又没有了"）：轮廓链路
        // 的关键路径日志——帮助排查红色框/橙色幽灵何时被清。
        // 仅记录状态变化（非心跳静默——每 1 秒 RegionSyncPacket 到来，日志不会刷屏）
        int before = REGION_BOXES.size();
        int after = regions == null ? 0 : regions.size();
        if (before != after) {
            com.maidsmart.tool.PromaidLog.log("投影", "setRegions "
                    + before + " -> " + after
                    + " 行（null=" + (regions == null) + "）"
                    + (after == 0 ? " ——全部框/投影清空（计划取消/完成）"
                            : " ——" + after + " 个区块"));
        }
        REGION_BOXES.clear();
        REGION_NAMES.clear();
        REGION_ORIGINS.clear();
        REGION_BPS.clear();
        REGION_PROJ_KEYS.clear();
        REGION_ORIGINS_POS.clear();
        if (regions == null) {
            return;
        }
        java.util.Set<String> needProj = new java.util.LinkedHashSet<>();
        for (String[] r : regions) {
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
                // v1.5.188：x/y/z 已是区块 box 的 min 角（服务端 planRegion 下发），
                // 直接作框的起点——旧版误当中心减半 → 红色区块框偏移 ≈1 格，
                // "实际搭建超出区块"（反馈）
                double x0 = x;
                double z0 = z;
                REGION_BOXES.add(new double[]{x0, y, z0, x0 + w, y + h, z0 + d});
                REGION_NAMES.add(r[1]);
                // v1.5.290：创建坐标（r[11..13]，encode 14 字段后到达）
                REGION_ORIGINS.add(r.length > 13
                        ? r[11] + ", " + r[12] + ", " + r[13] : "");
                // v1.1.0 实测八十二：蓝图 id（r[10]）+ 原点整数坐标——投影落地锚点
                String bp = r.length > 10 ? r[10] : "";
                REGION_BPS.add(bp);
                int[] org = null;
                if (r.length > 13) {
                    try {
                        org = new int[]{Integer.parseInt(r[11]),
                                Integer.parseInt(r[12]), Integer.parseInt(r[13])};
                    } catch (NumberFormatException ignored) {
                    }
                }
                REGION_ORIGINS_POS.add(org);
                // v1.1.0 实测九十七：r[14] = 计划朝向——橙影按 id#quarters 取旋转版点云
                if (!bp.isEmpty() && org != null) {
                    int rq = 0;
                    if (r.length > 14) {
                        try {
                            rq = Integer.parseInt(r[14]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String key = projKey(bp, rq);
                    REGION_PROJ_KEYS.add(key);
                    needProj.add(key);
                } else {
                    REGION_PROJ_KEYS.add("");
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (String key : needProj) {
            ensureProjectionForKey(key);
        }
        if (!REGION_BOXES.isEmpty()) {
            ensureRegistered();
        }
    }

    /** 从复合键拆出 id/quarters 并请求（红色区块路径用） */
    private static void ensureProjectionForKey(String key) {
        int idx = key.lastIndexOf('#');
        if (idx <= 0) {
            return;
        }
        try {
            ensureProjection(key.substring(0, idx), Integer.parseInt(key.substring(idx + 1)));
        } catch (NumberFormatException ignored) {
        }
    }

    /** v1.1.0 实测八十二：确保某蓝图的投影点云已在手（无缓存则向服务端请求一次）。
     *  v1.1.0 实测九十七：按 id#quarters 复合键缓存/请求（不同朝向各自一份点云） */
    private static void ensureProjection(String id, int quarters) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String key = projKey(id, quarters);
        if (PROJECTIONS.containsKey(key)) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            return;
        }
        if (!REQUESTED.add(key)) {
            return; // 已有在途请求
        }
        try {
            LOGGER.info("projection: request {} q={}", id, Math.floorMod(quarters, 4));
            BlueprintBookNetworking.CHANNEL.sendToServer(
                    new BlueprintBookNetworking.ProjectionRequestPacket(id, quarters));
        } catch (Exception e) {
            REQUESTED.remove(key);
        }
    }

    /**
     * v1.1.0 实测八十二：收到服务端点云（S2C ProjectionDataPacket）。
     * v1.1.0 实测一百零九：cloud 格式为 "x,y,z,id|state;…"（含方块注册名+状态 SNBT，
     * 状态解析仅作格式校验/备用——实测一百四十七起渲染走 DebugRenderer 填充盒，
     * 不再用 renderSingleBlock，BlockState 不参与绘制）。空串 = 无投影 → 清缓存。
     */
    public static void setProjection(String id, int quarters, String size, String cloud) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String key = projKey(id, quarters);
        Object[] pts = parseCloud(cloud);
        if (pts.length == 0) {
            LOGGER.info("projection: key={} empty cloud (unavailable)", key);
            PROJECTIONS.remove(key);
            PALETTES.remove(key);
            PARSED_STATES.remove(key);
            return;
        }
        // 实测五百五十三①：调色板（真方块渲染用）单独存——点里只带调色板下标
        String[] pal = parsePalette(cloud);
        if (pal != null && pal.length > 0) {
            String[] old = PALETTES.put(key, pal);
            if (old != pal) {
                PARSED_STATES.remove(key); // 调色板换了 → 解析缓存失效
            }
        }
        PROJECTIONS.put(key, pts);
        LOGGER.info("projection: key={} received {} blocks, palette={}", key, pts.length / 4,
                pal == null ? 0 : pal.length);
    }

    /**
     * 解析点云文本 → 平铺 Object[]{x,y,z,调色板下标(Integer，旧格式为 null), …}。
     *
     * 实测五百五十三①起格式为 `<点>|<调色板>`：
     * - 点：`x,y,z,i`（i = 调色板下标的 base36）；
     * - 调色板：`i~blockId~stateSnbt` 以 ';' 分隔。
     * 没有 '|' 的老格式（纯 "x,y,z"）继续兼容——那走旧的彩色填充盒渲染。
     */
    private static Object[] parseCloud(String cloud) {
        if (cloud == null || cloud.isEmpty()) {
            return new Object[0];
        }
        int bar = cloud.indexOf('|');
        String pointSection = bar >= 0 ? cloud.substring(0, bar) : cloud;
        if (pointSection.isEmpty()) {
            return new Object[0];
        }
        String[] segs = pointSection.split(";");
        Object[] out = new Object[segs.length * 4];
        int n = 0;
        for (String s : segs) {
            try {
                int c1 = s.indexOf(',');
                int c2 = s.indexOf(',', c1 + 1);
                if (c1 <= 0 || c2 <= c1 + 1 || c2 >= s.length() - 1) {
                    continue;
                }
                int x = Integer.parseInt(s.substring(0, c1));
                int y = Integer.parseInt(s.substring(c1 + 1, c2));
                int c3 = s.indexOf(',', c2 + 1);
                int z;
                Integer idx = null;
                if (c3 > 0) {
                    z = Integer.parseInt(s.substring(c2 + 1, c3));
                    String is = s.substring(c3 + 1).trim();
                    if (!is.isEmpty()) {
                        idx = Integer.parseInt(is, 36);
                    }
                } else {
                    z = Integer.parseInt(s.substring(c2 + 1));
                }
                out[n++] = x;
                out[n++] = y;
                out[n++] = z;
                out[n++] = idx;
            } catch (Exception ignored) {
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** 实测五百五十三①：解析调色板段 `i~blockId~stateSnbt;i~…`（无该段返回 null） */
    private static String[] parsePalette(String cloud) {
        if (cloud == null || cloud.isEmpty()) {
            return null;
        }
        int bar = cloud.indexOf('|');
        if (bar < 0 || bar >= cloud.length() - 1) {
            return null;
        }
        String[] parts = cloud.substring(bar + 1).split(";");
        java.util.List<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            out.add(s);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    private static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(BlueprintAreaPreview.class);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        // v1.1.0 实测一百三十二（轮廓消失所）——渲染层空档记录日志：
        // REGION_BOXES = 红色框集合（有计划就有）、active = 金色预览开启状态。
        // 门不开=every~秒一次性记"有心但不可见"的精确原因（哪一种没开）。
        if (!registered || (!active && REGION_BOXES.isEmpty())) {
            return;
        }
        // 实测五百五十三①：真方块幽灵走半透明方块图集 → 必须在【半透明层之后】绘制
        //（否则会被世界的半透明面盖住/混色不对）；旧的彩色填充盒仍在方块实体之后画。
        net.minecraftforge.client.event.RenderLevelStageEvent.Stage wantStage =
                com.maidsmart.config.MaidSmartConfig.BUILD_REAL_GHOST_BLOCKS.get()
                        ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                        : net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (event.getStage() != wantStage) {
            return;
        }
        // 画质：遥控到此时的值——红框渲染不检查这个开关，只橙影/金影检查
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            com.maidsmart.tool.PromaidLog.log("投影",
                    "render: 画面里 已注册=" + registered
                    + " 红框数=" + REGION_BOXES.size()
                    + " 金色预览=" + active
                    + " BUILD_PROJECTION=false——点选投影总开关，框与幽灵都不渲染");
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ == null || mc.f_91073_ == null) {
            return;
        }
        net.minecraft.world.phys.Vec3 camera = event.getCamera().m_90583_().m_82548_();
        com.mojang.blaze3d.vertex.PoseStack pose = event.getPoseStack();
        pose.m_85836_(); // pushPose
        for (int i = 0; i < REGION_BOXES.size(); i++) {
            double[] b = REGION_BOXES.get(i);
            // 实测二百一十二（反馈："从外部往内部看会发现是一个红色的正方体/长方体，
            // 六个面都被红色覆盖——透过那一层红色看不到里面的幽灵方块；从内部往外看
            // 才正常"）：删除区块框的【大红填充面】——0.28 半透明大盒的 6 个面在从外
            // 向内看时正好挡在幽灵方块前面，后画的红面覆盖/混合掉幽灵（内部看在相机
            // 身后被裁剪所以"正常"）。区域标识由红框【棱线】+ 悬浮标签承担（从来就是
            // 这样显示的），填充面本就是冗余且有害；幽灵线框+近处填充从此穿透可见。
            com.mojang.blaze3d.vertex.VertexConsumer buf =
                    mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            drawBoxEdges(pose, buf, camera, b[0], b[1], b[2], b[3], b[4], b[5], 1.0f, 0.25f, 0.2f);
            String label = i < REGION_NAMES.size() ? REGION_NAMES.get(i) : "建造区域";
            // v1.5.297：标签改 SEE_THROUGH 透显（末参 false→true，TLM 名字牌同款）——
            // 任何角度、隔方块都可见
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                    "\u00a7c「" + label + "」（建造中）",
                    (b[0] + b[3]) / 2.0, b[4] + 0.8, (b[2] + b[5]) / 2.0,
                    0xFF5544, 0.15f, true, -5.0f, true);
            // v1.5.290：创建坐标第二行（锚点差 1.4 格，净距约 0.45 格不再重叠）
            String origin = i < REGION_ORIGINS.size() ? REGION_ORIGINS.get(i) : "";
            if (!origin.isEmpty()) {
                com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                        "\u00a78创建于 " + origin,
                        (b[0] + b[3]) / 2.0, b[4] - 0.6, (b[2] + b[5]) / 2.0,
                        0x888888, 0.12f, true, -5.0f, true);
            }
            // v1.1.0 实测八十二：橙色幽灵方块投影——按计划原点落地，与实际搭建
            // 同一坐标系（锚点 = PlanState.origin + 居中步骤相对坐标），位置零偏差；
            // 建造中/暂停中的区块都能直接看到建筑最终形态与朝向。
            // v1.1.0 实测九十七：键含朝向（id#q），与计划实际旋转一致
            String key = i < REGION_PROJ_KEYS.size() ? REGION_PROJ_KEYS.get(i) : "";
            int[] org = i < REGION_ORIGINS_POS.size() ? REGION_ORIGINS_POS.get(i) : null;
            if (!key.isEmpty() && org != null) {
                // 实测二百零一/二百零四：幽灵面 alpha 0.45 → 0.55（区块外可见性）
                drawGhost(pose, mc, camera, key, org[0], org[1], org[2],
                        1.0f, 0.55f, 0.25f, 0.55f);
            }
        }
        if (active) {
            // 实测五百五十三②：金色预览改用**固定落点**（打开预览那一刻的玩家脚下格，
            // 微调界面/回到脚下按钮可改）——不再每帧取玩家位置；
            // v1.1.0 实测九十七：奇数朝向（90°/270°）占地 W/D 互换，金色框整体换向
            boolean swapped = (previewQuarters & 1) != 0;
            int effX = swapped ? sizeZ : sizeX;
            int effZ = swapped ? sizeX : sizeZ;
            net.minecraft.core.BlockPos p = previewOrigin != null ? previewOrigin : mc.f_91074_.m_20183_();
            double x0 = p.m_123341_() - effX / 2.0;
            double z0 = p.m_123343_() - effZ / 2.0;
            double y0 = p.m_123342_();
            double x1 = x0 + effX;
            double z1 = z0 + effZ;
            double y1 = y0 + sizeY;
            // 实测二百一十二：金色预览同样去掉大填充面（0.3 半透明大盒会从外面挡住
            // 青色幽灵——与红色区块框同病）；金色框棱线 + 标签已足够标识范围。
            com.mojang.blaze3d.vertex.VertexConsumer buf =
                    mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            drawBoxEdges(pose, buf, camera, x0, y0, z0, x1, y1, z1, 1.0f, 0.85f, 0.2f);
            if (previewId != null) {
                // 实测二百零一/二百零四：金预览青色幽灵 0.40 → 0.55（近景也清晰）
                drawGhost(pose, mc, camera, projKey(previewId, previewQuarters),
                        p.m_123341_(), p.m_123342_(), p.m_123343_(),
                        0.30f, 0.95f, 1.0f, 0.55f);
            }
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                    "建造范围 " + effX + "\u00d7" + sizeY + "\u00d7" + effZ
                            + "·朝向 " + (previewQuarters * 90) + "°（打开手册关闭）",
                    x0 + effX / 2.0, y1 + 0.6, z0 + effZ / 2.0, 0xFFDD55, 0.15f, true, -5.0f, false);
        }
        pose.m_85849_(); // popPose
    }

    /**
     * v1.1.0 实测九十七：金色预览态轮询转向键（默认 P，原版按键设置可改）——
     * 每次点击顺时针转 90°。
     * v1.1.0 实测九十七复查：计数器【无条件清空】——consumeClick 的内部计数
     * 只减不增地被本处消费，若非预览态提前 return 不排空，玩家平时误按的 P 会
     * 攒在计数里，下次开启预览瞬间一次性爆转；现在始终排空、仅预览态生效。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        while (com.maidsmart.build.BuildKeysClient.ROTATE_BLUEPRINT != null
                && com.maidsmart.build.BuildKeysClient.ROTATE_BLUEPRINT.m_90859_()) {
            if (active) {
                rotateClockwise();
            }
        }
        // 实测五百五十三②：G 键打开落点微调界面（仅金色预览激活时；同样无条件排空计数）
        while (com.maidsmart.build.BuildKeysClient.PLACEMENT_SCREEN != null
                && com.maidsmart.build.BuildKeysClient.PLACEMENT_SCREEN.m_90859_()) {
            if (active && previewId != null
                    && net.minecraft.client.Minecraft.m_91087_().f_91080_ == null) {
                net.minecraft.client.Minecraft.m_91087_().m_91152_(
                        new com.maidsmart.build.BuildPlacementScreen());
            }
        }
    }

    /**
     * v1.1.0 实测二百一十【崩溃二次修复】：二百零九直接把每盒 flush 放在了共享的主
     * bufferSource 上——同一帧里主源的描边（lines）与填充盒（debugFilledBox）共用一个
     * 即时建造器（1.20.1 BufferSource 对未入 map 的渲染类型统一取 f_109904_），中途
     * flush 后本地描边指针指向的建造器已不在"开始"状态 → "BufferBuilder not started"
     * 二次崩溃（实测二百零四的手写顶点崩溃是同一根因的另一面貌）。现在幽灵填充用
     * 【每帧独立的专用 BufferSource】（GhostBufferSource）——每盒 flush 只影响它自己，
     * 主 bufferSource 完全不受干扰：描边/红框/本模组其他渲染/其他模组的渲染全隔离。
     * 渲染本体 = DebugRenderer.renderFilledBox（m_269311_，vanilla debug 管线，长期
     * 使用零崩溃）；每盒立即 flush 断掉 TRIANGLE_STRIP 跨盒连接三角形——每盒精确
     * 1×1×1 满体积、无跨盒面（填充适用距离分档实测二百二十三：近处实心、外圈线框）。
     */
    private static void drawGhost(com.mojang.blaze3d.vertex.PoseStack pose,
                                  net.minecraft.client.Minecraft mc,
                                  net.minecraft.world.phys.Vec3 camera,
                                  String id, double ox, double oy, double oz,
                                  float r, float g, float b, float a) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            return;
        }
        Object[] pts = PROJECTIONS.get(id);
        if (pts == null || pts.length < 4) {
            return;
        }
        // 实测五百五十三①【真方块半透明渲染】：点里带调色板下标（新格式）且开关开着时，
        // 直接按每点的方块用原版烘焙模型画——效果与参照的 TLM-Builder 一致（看得出
        // 石砖/木板/楼梯朝向），比彩色填充盒信息量大得多。开关关掉或老格式 → 走下面的
        // 填充盒老路径（行为完全不变）。
        if (com.maidsmart.config.MaidSmartConfig.BUILD_REAL_GHOST_BLOCKS.get()) {
            net.minecraft.world.level.block.state.BlockState[] states = parsedStates(mc, id);
            if (states != null) {
                drawRealGhost(pose, mc, camera, pts, states, ox, oy, oz, a);
                return;
            }
        }
        // v1.1.0 实测一百八十九（反馈："建造模式方块——玩家走出框选的区块以后，
        // 蓝色方块和橙色方块在区块外是看不见的"）：移除 96 格点剔除——旧版以
        // 【计划原点】为锚点算玩家距离（>96 格整片不画），与红框渲染策略不一致
        //（红框永远渲染）：玩家走出框选区块投影全没；大蓝图时玩家站在框内对角
        //（离原点 >96 格）也会整片消失（一百四十七"框能显示幽灵必能显示"被这条
        // 独立剔除戳穿）。现在与红框同策略：不剔距离，只受 BUILD_PROJECTION
        // 总开关控制（帧率敏感玩家关总开关即可）。
        // v1.1.0 实测二百零一：面 alpha 0.20→0.45（2600 个格子从区块外看只有朝玩家的
        // 外皮几面可见，0.20 极淡基本看不出）。
        // v1.1.0 实测二百二十三【逐盒距离分档，覆盖全部方块】：旧版填充闸是一道
        // "距【区块原点】>24 格整片只描边"的全局开关——大蓝图（金字塔 251×124×251，
        // 玩家 08:43 实测 drawGhost 盒数=3000 远距描边=true）玩家站框内任意位置都算
        // "远"，整片只剩线框；且数据被 3000 点封顶、按扫描序抽稀成竖条纹 = "零星复刻
        // 大概形状"。现在：① 数据层上限 3000→12000，点云只发坐标（同带宽 4 倍覆盖、
        // 采样改确定性洗牌+等距，无竖条纹——见 BlueprintProjectionSampler）；
        // ② 渲染层每盒按【与相机的 3D 距离】各自判定——≤32 格内的盒全部参与填充
        // （实心体积跟随玩家），超过 2400 盒时取最近的 2400 个（帧内开销封顶）；
        // ③ 全部盒无条件画 12 条棱线（lines 单缓冲成批、隔地形透显，与红框同管线）
        // ——近处满体积、远处线框剪影，任意位置都能看到从近到远的完整形态。
        var bufferSource = mc.m_91269_().m_110104_();
        int total = pts.length / 4;
        boolean[] fillSel = null;
        GhostBufferSource ghostSource = null;
        if (total > 0) {
            java.util.ArrayList<int[]> near = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                double dx = ox + (int) pts[i * 4] + 0.5 - camera.f_82479_;
                double dy = oy + (int) pts[i * 4 + 1] + 0.5 - camera.f_82480_;
                double dz = oz + (int) pts[i * 4 + 2] + 0.5 - camera.f_82481_;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq <= 1024.0) {
                    near.add(new int[]{i, (int) dSq});
                }
            }
            if (!near.isEmpty()) {
                near.sort(java.util.Comparator.comparingInt(o -> o[1]));
                int cap = Math.min(2400, near.size());
                fillSel = new boolean[total];
                for (int k = 0; k < cap; k++) {
                    fillSel[near.get(k)[0]] = true;
                }
                ghostSource = new GhostBufferSource();
            }
        }
        com.mojang.blaze3d.vertex.VertexConsumer edgeBuf =
                bufferSource.m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
        int drawn = 0;
        int fillCount = 0;
        for (int i = 0; i + 3 < pts.length; i += 4) {
            int bx = (int) pts[i];
            int by = (int) pts[i + 1];
            int bz = (int) pts[i + 2];
            double wx = ox + bx;
            double wy = oy + by;
            double wz = oz + bz;
            // 棱线（恒定，穿透地形可见——与红框同管线；亮色便于远处辨认）
            drawBoxEdges(pose, edgeBuf, camera, wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0,
                    Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
            if (fillSel != null && fillSel[i / 4]) {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0).m_82383_(camera);
                net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(
                        pose, ghostSource, box, r, g, b, a);
                ghostSource.m_109912_(net.minecraft.client.renderer.RenderType.m_269313_());
                fillCount++;
            }
            drawn++;
        }
        // 实测二百零四：每帧一次落的诊断（数量变化/恢复才记，不刷屏）——"还是没显示"
        // 时日志直接区分：没进 drawGhost（0 盒/没到渲染层） vs 进了但客户端看不出
        if (drawn != lastDrawnCount) {
            lastDrawnCount = drawn;
            com.maidsmart.tool.PromaidLog.log("投影", "drawGhost(" + id + ") 盒数="
                    + drawn + " 填充=" + fillCount);
        }
    }

    /** 实测二百一十：幽灵方块专用 BufferSource——每帧新建、只写 debugFilledBox、
     *  每盒 flush 只影响自己；主 bufferSource（描边/红框/其他模组渲染）完全隔离。
     *  构造器 protected，子类化即接（Builder 初始 6KB，按需自动增长）。 */
    private static final class GhostBufferSource
            extends net.minecraft.client.renderer.MultiBufferSource.BufferSource {
        GhostBufferSource() {
            super(new com.mojang.blaze3d.vertex.BufferBuilder(4096), new java.util.HashMap<>());
        }
    }

    /**
     * 实测五百五十三①：调色板 → BlockState 数组（带缓存；调色板数组换了就重解析）。
     * 解析用原版的 {@code BlockStateParser.parseForBlock}，所以楼梯朝向/半砖类型/
     * 含水状态这些都能还原——预览里看到的就是建好以后的样子。
     */
    private static net.minecraft.world.level.block.state.BlockState[] parsedStates(
            net.minecraft.client.Minecraft mc, String key) {
        String[] pal = PALETTES.get(key);
        if (pal == null || pal.length == 0 || mc.f_91073_ == null) {
            return null;
        }
        Object[] cached = PARSED_STATES.get(key);
        if (cached != null && cached.length == 3 && cached[0] == pal) {
            @SuppressWarnings("unchecked")
            net.minecraft.world.level.block.state.BlockState[] hit =
                    (net.minecraft.world.level.block.state.BlockState[]) cached[1];
            return hit;
        }
        net.minecraft.core.HolderLookup<net.minecraft.world.level.block.Block> lookup =
                mc.f_91073_.m_9598_()
                        .m_254861_(net.minecraft.core.registries.Registries.f_256747_)
                        .orElse(null);
        if (lookup == null) {
            return null;
        }
        net.minecraft.world.level.block.state.BlockState[] states =
                new net.minecraft.world.level.block.state.BlockState[pal.length];
        int ok = 0;
        for (int i = 0; i < pal.length; i++) {
            String entry = pal[i];
            try {
                int t = entry.indexOf('~');
                if (t <= 0) {
                    continue;
                }
                int t2 = entry.indexOf('~', t + 1);
                String id = t2 > 0 ? entry.substring(t + 1, t2) : entry.substring(t + 1);
                String state = t2 > 0 ? entry.substring(t2 + 1) : "";
                String text = state == null || state.isEmpty() ? id : id + state;
                net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult res =
                        net.minecraft.commands.arguments.blocks.BlockStateParser.m_245437_(lookup, text, false);
                states[i] = res.f_234748_();
                ok++;
            } catch (Throwable ignored) {
                states[i] = null; // 单个方块解析失败（模组方块被卸？）→ 该点跳过，不影响其它
            }
        }
        if (ok == 0) {
            return null; // 一个都解析不了 → 退回填充盒渲染，至少还能看见范围
        }
        PARSED_STATES.put(key, new Object[]{pal, states});
        return states;
    }

    /**
     * 实测五百五十三①：真方块幽灵——按每点的方块用**原版烘焙模型**画进半透明方块图集，
     * 顶点颜色 alpha×0.6、RGB 略压暗（参照 TLM-Builder 的 GhostVertexConsumer 做法），
     * 看上去就是"一栋半透明的真建筑"。已经建好（世界里已是同种方块）的点不再画。
     * 绘制上限走 {@code build.ghostBlockCap}（真模型比填充盒重），仍是最近优先。
     */
    private static void drawRealGhost(com.mojang.blaze3d.vertex.PoseStack pose,
                                      net.minecraft.client.Minecraft mc,
                                      net.minecraft.world.phys.Vec3 camera,
                                      Object[] pts, net.minecraft.world.level.block.state.BlockState[] states,
                                      double ox, double oy, double oz, float alpha) {
        net.minecraft.client.multiplayer.ClientLevel level = mc.f_91073_;
        if (level == null) {
            return;
        }
        int total = pts.length / 4;
        int cap = com.maidsmart.config.MaidSmartConfig.BUILD_GHOST_BLOCK_CAP.get();
        java.util.ArrayList<int[]> near = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            double dx = ox + (int) pts[i * 4] + 0.5 - camera.f_82479_;
            double dy = oy + (int) pts[i * 4 + 1] + 0.5 - camera.f_82480_;
            double dz = oz + (int) pts[i * 4 + 2] + 0.5 - camera.f_82481_;
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq <= 4096.0) { // 64 格内
                near.add(new int[]{i, (int) dSq});
            }
        }
        if (near.isEmpty()) {
            return;
        }
        near.sort(java.util.Comparator.comparingInt(o -> o[1]));
        int limit = Math.min(cap, near.size());
        net.minecraft.client.renderer.block.BlockRenderDispatcher brd = mc.m_91289_();
        net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = mc.m_91269_().m_110104_();
        com.mojang.blaze3d.vertex.VertexConsumer vc = new GhostBlockVertexConsumer(
                buffers.m_6299_(net.minecraft.client.renderer.Sheets.m_110792_()));
        int drawn = 0;
        int oxi = (int) Math.floor(ox);
        int oyi = (int) Math.floor(oy);
        int ozi = (int) Math.floor(oz);
        for (int k = 0; k < limit; k++) {
            int i = near.get(k)[0];
            Object idxObj = pts[i * 4 + 3];
            if (!(idxObj instanceof Integer idx) || idx < 0 || idx >= states.length) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState st = states[idx];
            if (st == null || st.m_60795_()) {
                continue;
            }
            net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(
                    oxi + (int) pts[i * 4], oyi + (int) pts[i * 4 + 1], ozi + (int) pts[i * 4 + 2]);
            // 世界里已经是同一种方块 → 建好了，不再画（与参照一致）
            if (level.m_8055_(bp).m_60713_(st.m_60734_())) {
                continue;
            }
            // 本帧的 PoseStack 没有做 -camera 平移（描边/填充盒都是手算相机相对坐标），
            // 所以这里自己平移到"相机相对位置"，BlockPos 仍用世界坐标算光照/种子。
            pose.m_85836_();
            pose.m_252880_((float) (bp.m_123341_() - camera.f_82479_),
                    (float) (bp.m_123342_() - camera.f_82480_),
                    (float) (bp.m_123343_() - camera.f_82481_));
            brd.m_110937_().tesselateBlock(level, brd.m_110910_(st), st, bp, pose, vc, false,
                    level.f_46441_, st.m_60726_(bp),
                    net.minecraft.client.renderer.texture.OverlayTexture.f_118083_,
                    net.minecraftforge.client.model.data.ModelData.EMPTY, null);
            pose.m_85849_();
            drawn++;
        }
        buffers.m_109912_(net.minecraft.client.renderer.Sheets.m_110792_());
        if (drawn != lastDrawnCount) {
            lastDrawnCount = drawn;
            com.maidsmart.tool.PromaidLog.log("投影", "drawRealGhost 方块数=" + drawn
                    + "（调色板 " + states.length + " 种）");
        }
    }

    /**
     * 实测五百五十三①：真方块幽灵的顶点包装——把原版方块图集的顶点颜色
     * alpha 压到 0.6、RGB 略压暗，得到"半透明真方块"的观感。
     * （1.20.1 的 VertexConsumer 是 9 个抽象方法的 SRG 版，javap 实证）
     */
    private static final class GhostBlockVertexConsumer
            implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final com.mojang.blaze3d.vertex.VertexConsumer delegate;

        GhostBlockVertexConsumer(com.mojang.blaze3d.vertex.VertexConsumer delegate) {
            this.delegate = delegate;
        }

        private static int dim(int c) {
            return Math.max(0, Math.min(255, (int) (c * 0.88f)));
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_5483_(double x, double y, double z) {
            delegate.m_5483_(x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_6122_(int r, int g, int b, int a) {
            delegate.m_6122_(dim(r), dim(g), dim(b), (int) (a * 0.6f));
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_7421_(float u, float v) {
            delegate.m_7421_(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_7122_(int u, int v) {
            delegate.m_7122_(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_7120_(int u, int v) {
            delegate.m_7120_(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_5601_(float x, float y, float z) {
            delegate.m_5601_(x, y, z);
            return this;
        }

        @Override
        public void m_5752_() {
            delegate.m_5752_();
        }

        @Override
        public void m_7404_(int r, int g, int b, int a) {
            delegate.m_7404_(dim(r), dim(g), dim(b), (int) (a * 0.6f));
        }

        @Override
        public void m_141991_() {
            delegate.m_141991_();
        }
    }

    /** 画一个方框的 12 条棱（TLM RenderHelper.renderLine） */
    private static void drawBoxEdges(com.mojang.blaze3d.vertex.PoseStack pose,
                                     com.mojang.blaze3d.vertex.VertexConsumer buf,
                                     net.minecraft.world.phys.Vec3 camera,
                                     double x0, double y0, double z0,
                                     double x1, double y1, double z1,
                                     float r, float g, float b) {
        net.minecraft.world.phys.Vec3 c0 = camera.m_82520_(x0, y0, z0);
        net.minecraft.world.phys.Vec3 c1 = camera.m_82520_(x1, y0, z0);
        net.minecraft.world.phys.Vec3 c2 = camera.m_82520_(x1, y0, z1);
        net.minecraft.world.phys.Vec3 c3 = camera.m_82520_(x0, y0, z1);
        net.minecraft.world.phys.Vec3 t0 = camera.m_82520_(x0, y1, z0);
        net.minecraft.world.phys.Vec3 t1 = camera.m_82520_(x1, y1, z0);
        net.minecraft.world.phys.Vec3 t2 = camera.m_82520_(x1, y1, z1);
        net.minecraft.world.phys.Vec3 t3 = camera.m_82520_(x0, y1, z1);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, c1, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, c2, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, c3, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, c0, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t0, t1, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t1, t2, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t2, t3, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t3, t0, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, t0, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, t1, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, t2, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, t3, r, g, b);
    }
}
