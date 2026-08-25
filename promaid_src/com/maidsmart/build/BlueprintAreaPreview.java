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

    /** v1.1.0 实测九十六：金色预览回归（用户："未确认时金色区域随着玩家移动这个
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

    /** v1.1.0 实测八十二：投影点云缓存（blueprintId → x,y,z 三元组平铺，相对居中
     *  坐标）；REQUESTED 防重复请求 */
    private static final java.util.Map<String, int[]> PROJECTIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> REQUESTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        previewId = blueprintId;
        active = true;
        previewSeen = true; // 看过预览 → 建造确认流程放行第 2 步
        ensureRegistered();
        ensureProjection(blueprintId);
    }

    /** 是否已看过金色预览（建造确认流程第 1 步放行判断） */
    public static boolean wasShown() {
        return previewSeen;
    }

    /** 建造确认成功后重置——下一轮建造仍先看范围（防误操作） */
    public static void resetSeen() {
        previewSeen = false;
    }

    /** 关闭金色预览渲染（打开手册时调用；不重置 previewSeen——v1.5.204 教训） */
    public static void clear() {
        active = false;
    }

    /**
     * v1.5.180：设置实际建造区块（红色固定框，多框）——v1.1.0 实测九十五起由
     * 服务端每秒 RegionSyncPacket 驱动；行格式
     * {planId, 显示名, 维度名, 状态, x, y, z, W, H, D, blueprintId, 创建X, 创建Y, 创建Z}；
     * 空列表 = 无进行中计划（取消/完成）→ 清空所有框与投影。
     */
    public static void setRegions(java.util.List<String[]> regions) {
        REGION_BOXES.clear();
        REGION_NAMES.clear();
        REGION_ORIGINS.clear();
        REGION_BPS.clear();
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
                // "实际搭建超出区块"（用户反馈）
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
                if (!bp.isEmpty() && org != null) {
                    needProj.add(bp);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (String bp : needProj) {
            ensureProjection(bp);
        }
        if (!REGION_BOXES.isEmpty()) {
            ensureRegistered();
        }
    }

    /** v1.1.0 实测八十二：确保某蓝图的投影点云已在手（无缓存则向服务端请求一次） */
    private static void ensureProjection(String id) {
        if (id == null || id.isEmpty() || PROJECTIONS.containsKey(id)) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            return;
        }
        if (!REQUESTED.add(id)) {
            return; // 已有在途请求
        }
        try {
            LOGGER.info("projection: request {}", id);
            BlueprintBookNetworking.CHANNEL.sendToServer(
                    new BlueprintBookNetworking.ProjectionRequestPacket(id));
        } catch (Exception e) {
            REQUESTED.remove(id);
        }
    }

    /**
     * v1.1.0 实测八十二：收到服务端点云（S2C ProjectionDataPacket）。
     * cloud = "x,y,z;x,y,z;…"；空串 = 该蓝图无可投影内容（已删除/解析失败）→ 清缓存。
     */
    public static void setProjection(String id, String size, String cloud) {
        if (id == null || id.isEmpty()) {
            return;
        }
        int[] pts = parseCloud(cloud);
        if (pts.length == 0) {
            LOGGER.info("projection: id={} empty cloud (unavailable)", id);
            PROJECTIONS.remove(id);
            return;
        }
        PROJECTIONS.put(id, pts);
        LOGGER.info("projection: id={} received {} points", id, pts.length / 3);
    }

    /** 解析点云文本 → 平铺 int[]；格式异常的段跳过（半包容错） */
    private static int[] parseCloud(String cloud) {
        if (cloud == null || cloud.isEmpty()) {
            return new int[0];
        }
        String[] segs = cloud.split(";");
        int[] out = new int[segs.length * 3];
        int n = 0;
        for (String s : segs) {
            try {
                int c1 = s.indexOf(',');
                int c2 = s.indexOf(',', c1 + 1);
                if (c1 < 0 || c2 < 0) {
                    continue;
                }
                out[n++] = Integer.parseInt(s.substring(0, c1));
                out[n++] = Integer.parseInt(s.substring(c1 + 1, c2));
                out[n++] = Integer.parseInt(s.substring(c2 + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(BlueprintAreaPreview.class);
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!active && REGION_BOXES.isEmpty()) {
            return;
        }
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
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
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    b[0], b[1], b[2], b[3], b[4], b[5])
                    .m_82383_(camera);
            net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(
                    pose, mc.m_91269_().m_110104_(), box, 1.0f, 0.25f, 0.2f, 0.28f);
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
            // 建造中/暂停中的区块都能直接看到建筑最终形态与朝向
            String bp = i < REGION_BPS.size() ? REGION_BPS.get(i) : "";
            int[] org = i < REGION_ORIGINS_POS.size() ? REGION_ORIGINS_POS.get(i) : null;
                    if (!bp.isEmpty() && org != null) {
                drawGhost(pose, mc, camera, bp, org[0], org[1], org[2],
                        1.0f, 0.55f, 0.25f, 0.20f);
            }
        }
        if (active) {
            // 金色预览：以玩家所在格为中心（每帧取玩家位置 → 框随玩家移动）；
            // v1.1.0 实测九十六：青色幽灵方块同步显示——未确认阶段即可看形态朝向
            net.minecraft.core.BlockPos p = mc.f_91074_.m_20183_();
            double x0 = p.m_123341_() - sizeX / 2.0;
            double z0 = p.m_123343_() - sizeZ / 2.0;
            double y0 = p.m_123342_();
            double x1 = x0 + sizeX;
            double z1 = z0 + sizeZ;
            double y1 = y0 + sizeY;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1, y1, z1)
                    .m_82383_(camera);
            net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(
                    pose, mc.m_91269_().m_110104_(), box, 1.0f, 0.85f, 0.2f, 0.3f);
            com.mojang.blaze3d.vertex.VertexConsumer buf =
                    mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            drawBoxEdges(pose, buf, camera, x0, y0, z0, x1, y1, z1, 1.0f, 0.85f, 0.2f);
            if (previewId != null) {
                drawGhost(pose, mc, camera, previewId,
                        p.m_123341_(), p.m_123342_(), p.m_123343_(),
                        0.30f, 0.95f, 1.0f, 0.22f);
            }
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                    "建造范围 " + sizeX + "\u00d7" + sizeY + "\u00d7" + sizeZ + "（打开手册关闭）",
                    x0 + sizeX / 2.0, y1 + 0.6, z0 + sizeZ / 2.0, 0xFFDD55, 0.15f, true, -5.0f, false);
        }
        pose.m_85849_(); // popPose
    }

    /**
     * v1.1.0 实测八十二：画蓝图投影——点云每个点在格内画一个 0.4 格的半透明小方块。
     * 锚点 = (ox, oy, oz) = 计划原点，点坐标为居中后的相对值 → 与
     * BlueprintBuildExecutor 实际放置位置逐块重合。
     * 距离剔除：锚点距相机 >96 格不画（远处区块只留框和文字，省性能）。
     */
    private static void drawGhost(com.mojang.blaze3d.vertex.PoseStack pose,
                                  net.minecraft.client.Minecraft mc,
                                  net.minecraft.world.phys.Vec3 camera,
                                  String id, double ox, double oy, double oz,
                                  float r, float g, float b, float a) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            return;
        }
        int[] pts = PROJECTIONS.get(id);
        if (pts == null || pts.length < 3) {
            return; // 未到达/空云（请求在途或该蓝图无可渲染块）
        }
        // 距离剔除：玩家到锚点平方距离 >96² 不画（v1.1.0 实测八十三b：改用已验证
        // 的 Entity.m_20238_ 组合，不再直接读 Vec3 字段）
        net.minecraft.world.phys.Vec3 anchor = new net.minecraft.world.phys.Vec3(ox, oy, oz);
        if (mc.f_91074_.m_20238_(anchor) > 9216.0) {
            return;
        }
        var source = mc.m_91269_().m_110104_();
        for (int i = 0; i + 2 < pts.length; i += 3) {
            double wx = ox + pts[i];
            double wy = oy + pts[i + 1];
            double wz = oz + pts[i + 2];
            net.minecraft.world.phys.AABB cube = new net.minecraft.world.phys.AABB(
                    wx + 0.30, wy + 0.30, wz + 0.30, wx + 0.70, wy + 0.70, wz + 0.70)
                    .m_82383_(camera);
            net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(pose, source, cube, r, g, b, a);
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
