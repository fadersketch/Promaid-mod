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
    /** v1.1.0 实测九十七：金色预览当前朝向（0~3 × 90° 顺时针）——按转向键 +1；
     *  换蓝图时归零，同一蓝图取消后重新选位保留上次选择 */
    private static int previewQuarters = 0;

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
        ensureRegistered();
        ensureProjection(blueprintId, previewQuarters);
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

    /** v1.1.0 实测九十七：当前选定的朝向（0~3 × 90° 顺时针）——确认建造时随包下发 */
    public static int previewQuarters() {
        return previewQuarters;
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
     * v1.1.0 实测一百零九：cloud 格式为 "x,y,z,id|state;…"（含方块注册名+状态 SNBT），
     * 客户端据此用 renderSingleBlock 渲染真实方块模型。空串 = 无投影 → 清缓存。
     */
    public static void setProjection(String id, int quarters, String size, String cloud) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String key = projKey(id, quarters);
        Object[] pts = parseCloudWithState(cloud);
        if (pts.length == 0) {
            LOGGER.info("projection: key={} empty cloud (unavailable)", key);
            PROJECTIONS.remove(key);
            return;
        }
        PROJECTIONS.put(key, pts);
        LOGGER.info("projection: key={} received {} blocks", key, pts.length / 4);
    }

    /**
     * 解析含方块状态的点云文本 → 平铺 Object[]{x,y,z,BlockState, x,y,z,BlockState, …}。
     * 格式 "x,y,z,id|state;x,y,z,id|state;…"。state SNBT 为空或解析失败时用方块默认态。
     */
    private static Object[] parseCloudWithState(String cloud) {
        if (cloud == null || cloud.isEmpty()) {
            return new Object[0];
        }
        // 客户端 HolderGetter（BlockState 重建必需）
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder = null;
        if (mc.f_91073_ != null) {
            try {
                holder = mc.f_91073_.m_246945_(net.minecraft.core.registries.Registries.f_256747_);
            } catch (Exception ignored) {
            }
        }
        net.minecraft.world.level.block.Block fallbackBlock =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        new net.minecraft.resources.ResourceLocation("minecraft", "gray_wool"));
        net.minecraft.world.level.block.state.BlockState fallbackState =
                fallbackBlock != null ? fallbackBlock.m_49966_() : null;

        String[] segs = cloud.split(";");
        Object[] out = new Object[segs.length * 4];
        int n = 0;
        for (String s : segs) {
            try {
                int c1 = s.indexOf(',');
                int c2 = s.indexOf(',', c1 + 1);
                int c3 = s.indexOf(',', c2 + 1);
                if (c1 < 0 || c2 < 0) {
                    continue;
                }
                int x = Integer.parseInt(s.substring(0, c1));
                int y = Integer.parseInt(s.substring(c1 + 1, c2));
                int z;
                String blockId;
                String stateSnbt = "";
                if (c3 > 0) {
                    z = Integer.parseInt(s.substring(c2 + 1, c3));
                    String idPart = s.substring(c3 + 1);
                    int pipe = idPart.indexOf('|');
                    if (pipe >= 0) {
                        blockId = idPart.substring(0, pipe);
                        stateSnbt = idPart.substring(pipe + 1);
                    } else {
                        blockId = idPart;
                    }
                } else {
                    z = Integer.parseInt(s.substring(c2 + 1));
                    blockId = "minecraft:gray_wool";
                }
                net.minecraft.world.level.block.state.BlockState state =
                        resolveBlockState(blockId, stateSnbt, holder, fallbackState);
                out[n++] = x;
                out[n++] = y;
                out[n++] = z;
                out[n++] = state;
            } catch (Exception ignored) {
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** 客户端 BlockState 重建：blockId + stateSnbt → BlockState（失败返回 fallback） */
    private static net.minecraft.world.level.block.state.BlockState resolveBlockState(
            String blockId, String stateSnbt,
            net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder,
            net.minecraft.world.level.block.state.BlockState fallback) {
        try {
            net.minecraft.world.level.block.Block block =
                    net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                            new net.minecraft.resources.ResourceLocation(blockId));
            if (block == null) {
                return fallback;
            }
            if (stateSnbt == null || stateSnbt.isEmpty()) {
                return block.m_49966_();
            }
            net.minecraft.nbt.CompoundTag stateTag = net.minecraft.nbt.NbtUtils.m_178024_(stateSnbt);
            if (stateTag == null) {
                return block.m_49966_();
            }
            // 补全 Name 键（蓝图 stateSnbt 只含属性不含 Name）
            if (!stateTag.m_128425_("Name", 8)) {
                net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
                for (String key : stateTag.m_128431_()) {
                    props.m_128359_(key, stateTag.m_128423_(key).m_7916_());
                }
                net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
                wrapper.m_128359_("Name", blockId);
                wrapper.m_128365_("Properties", props);
                stateTag = wrapper;
            }
            if (holder != null) {
                net.minecraft.world.level.block.state.BlockState resolved =
                        net.minecraft.nbt.NbtUtils.m_247651_(holder, stateTag);
                if (resolved != null) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
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
            // 建造中/暂停中的区块都能直接看到建筑最终形态与朝向。
            // v1.1.0 实测九十七：键含朝向（id#q），与计划实际旋转一致
            String key = i < REGION_PROJ_KEYS.size() ? REGION_PROJ_KEYS.get(i) : "";
            int[] org = i < REGION_ORIGINS_POS.size() ? REGION_ORIGINS_POS.get(i) : null;
            if (!key.isEmpty() && org != null) {
                drawGhost(pose, mc, camera, key, org[0], org[1], org[2],
                        1.0f, 0.55f, 0.25f, 0.20f);
            }
        }
        if (active) {
            // 金色预览：以玩家所在格为中心（每帧取玩家位置 → 框随玩家移动）；
            // v1.1.0 实测九十六：青色幽灵方块同步显示——未确认阶段即可看形态朝向；
            // v1.1.0 实测九十七：奇数朝向（90°/270°）占地 W/D 互换，金色框整体换向
            boolean swapped = (previewQuarters & 1) != 0;
            int effX = swapped ? sizeZ : sizeX;
            int effZ = swapped ? sizeX : sizeZ;
            net.minecraft.core.BlockPos p = mc.f_91074_.m_20183_();
            double x0 = p.m_123341_() - effX / 2.0;
            double z0 = p.m_123343_() - effZ / 2.0;
            double y0 = p.m_123342_();
            double x1 = x0 + effX;
            double z1 = z0 + effZ;
            double y1 = y0 + sizeY;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1, y1, z1)
                    .m_82383_(camera);
            net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(
                    pose, mc.m_91269_().m_110104_(), box, 1.0f, 0.85f, 0.2f, 0.3f);
            com.mojang.blaze3d.vertex.VertexConsumer buf =
                    mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            drawBoxEdges(pose, buf, camera, x0, y0, z0, x1, y1, z1, 1.0f, 0.85f, 0.2f);
            if (previewId != null) {
                drawGhost(pose, mc, camera, projKey(previewId, previewQuarters),
                        p.m_123341_(), p.m_123342_(), p.m_123343_(),
                        0.30f, 0.95f, 1.0f, 0.22f);
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
    }

    /**
     * v1.1.0 实测八十二：画蓝图投影——点云每个点在格内画一个 0.4 格的半透明小方块。
     * 锚点 = (ox, oy, oz) = 计划原点，点坐标为居中后的相对值 → 与
     * BlueprintBuildExecutor 实际放置位置逐块重合。
     * 距离剔除：锚点距相机 >96 格不画（远处区块只留框和文字，省性能）。
     */
    /**
     * v1.1.0 实测一百零九【Litematica 风格】：用 renderSingleBlock 渲染真实方块模型。
     * 每个投影点是 Object[]{x, y, z, BlockState}，通过 BlockRenderDispatcher 渲染带
     * 纹理的实际模型。半透明：TransparentVertexConsumer 包装真实 VertexConsumer，
     * 拦截 m_6122_（RGBA 颜色写入）缩减 alpha——关键修复：所有 builder 方法返回 this
     * 而非 delegate，保证链式调用 (vertex→color→uv→…) 始终走包装器，alpha 缩减生效。
     * renderType 用 RenderType.m_110466_()（= translucent，字节码实证 f_110375_）——
     * translucent 管线开启 alpha 混合。
     * 距离剔除：锚点距相机 >96 格不画。
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
        net.minecraft.world.phys.Vec3 anchor = new net.minecraft.world.phys.Vec3(ox, oy, oz);
        if (mc.f_91074_.m_20238_(anchor) > 9216.0) {
            return;
        }
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = mc.m_91289_();
        var realSource = mc.m_91269_().m_110104_();
        GhostBufferSource ghostSource = new GhostBufferSource(realSource, a);
        int fullBright = 0xF000F0;
        for (int i = 0; i + 3 < pts.length; i += 4) {
            int bx = (int) pts[i];
            int by = (int) pts[i + 1];
            int bz = (int) pts[i + 2];
            net.minecraft.world.level.block.state.BlockState state =
                    (net.minecraft.world.level.block.state.BlockState) pts[i + 3];
            if (state == null) {
                continue;
            }
            double wx = ox + bx;
            double wy = oy + by;
            double wz = oz + bz;
            pose.m_85836_(); // pushPose
            pose.m_85837_(wx - camera.f_82479_, wy - camera.f_82480_, wz - camera.f_82481_);
            try {
                blockRenderer.renderSingleBlock(state, pose, ghostSource,
                        fullBright, net.minecraft.client.renderer.texture.OverlayTexture.f_118083_,
                        net.minecraftforge.client.model.data.ModelData.EMPTY,
                        net.minecraft.client.renderer.RenderType.m_110466_()); // translucent
            } catch (Exception ignored) {
            }
            pose.m_85849_(); // popPose
        }
    }

    /**
     * v1.1.0 实测一百零九：半透明 MultiBufferSource 包装器——委托给真实 BufferSource，
     * 但每个 VertexConsumer 被 GhostVertexConsumer 包装，缩减顶点颜色 alpha 值。
     */
    private static final class GhostBufferSource implements net.minecraft.client.renderer.MultiBufferSource {
        private final net.minecraft.client.renderer.MultiBufferSource.BufferSource delegate;
        private final int alphaMul; // 0~255

        GhostBufferSource(net.minecraft.client.renderer.MultiBufferSource.BufferSource delegate, float alpha) {
            this.delegate = delegate;
            this.alphaMul = Math.max(0, Math.min(255, (int) (alpha * 255)));
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_6299_(net.minecraft.client.renderer.RenderType renderType) {
            return new GhostVertexConsumer(delegate.m_6299_(renderType), alphaMul);
        }
    }

    /**
     * v1.1.0 实测一百零九：顶点颜色 alpha 缩减包装器——拦截 m_6122_（RGBA 颜色写入）
     * 将 alpha 分量乘以缩放因子后转发。关键修复：所有 builder 方法【返回 this】而非
     * delegate——旧版返回 delegate.m_5483_(...) 导致后续 .color()/.uv() 链式调用直接
     * 在底层 consumer 上执行，绕过 alpha 缩减且渲染链中断（方块完全不可见）。
     */
    private static final class GhostVertexConsumer implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final com.mojang.blaze3d.vertex.VertexConsumer delegate;
        private final int alphaMul;

        GhostVertexConsumer(com.mojang.blaze3d.vertex.VertexConsumer delegate, int alphaMul) {
            this.delegate = delegate;
            this.alphaMul = alphaMul;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_5483_(double x, double y, double z) {
            delegate.m_5483_(x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer m_6122_(int red, int green, int blue, int alpha) {
            delegate.m_6122_(red, green, blue, (alpha * alphaMul) >> 8);
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
        public void m_7404_(int red, int green, int blue, int alpha) {
            delegate.m_7404_(red, green, blue, (alpha * alphaMul) >> 8);
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
