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

    /** 实测二百二十四：分片接收缓冲（key -> 片数组；全部到齐才组装成点云） */
    private static final java.util.Map<String, String[]> PROJ_PARTS = new java.util.HashMap<>();
    private static final java.util.Map<String, String> PROJ_PARTS_SIZE = new java.util.HashMap<>();

    // ================= 实测二百二十四：幽灵线框 VBO 缓存 =================
    // 点云免抽稀后（上限 500000）全区块棱线每帧即时重建/上传不可行（45.6 万盒 ×
    // 24 顶点 = 1000 万级顶点/帧）。改为：后台线程把某区块的线框几何一次性建成
    // RenderedBuffer → 渲染线程上传成 VertexBuffer（GPU 显存缓存）→ 每帧一次
    // drawWithShader 画出全区块（一次 submit 的钱画百万级顶点）→ 近处再叠加
    // 32 格内最近的 2400 盒满体积填充。金色预览（锚点随玩家移动）不缓存，走
    // 近场即时回退（仅 64 格内最近 6000 盒）。key = "blueprintId#q@ox,oy,oz"。
    private static final java.util.Map<String, com.mojang.blaze3d.vertex.VertexBuffer> GHOST_VBOS =
            new java.util.LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, com.mojang.blaze3d.vertex.VertexBuffer> eldest) {
                    if (size() > 3) { // 大蓝图单块线框可达百 MB 级（345K 盒 ≈ 133MB），上限 3 块
                        try {
                            eldest.getValue().close();
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    return false;
                }
            };
    /** 待构建队列/参数（后台线程只读，不可变快照） */
    private static final java.util.concurrent.LinkedBlockingQueue<String> VBO_BUILD_QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private static final java.util.Map<String, Object[]> VBO_BUILD_PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, double[]> VBO_BUILD_META =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 后台构建完成，等渲染线程上传 */
    private static final java.util.concurrent.ConcurrentHashMap<String, com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer> VBO_READY =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean vboWorkerStarted = false;

    private static void startGhostVboWorker() {
        if (vboWorkerStarted) {
            return;
        }
        vboWorkerStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String key = VBO_BUILD_QUEUE.take();
                    Object[] pts = VBO_BUILD_PENDING.remove(key);
                    double[] meta = VBO_BUILD_META.remove(key);
                    if (pts == null || meta == null || pts.length < 4) {
                        continue;
                    }
                    buildGhostVbo(key, pts, meta[0], meta[1], meta[2], (float) meta[3], (float) meta[4], (float) meta[5]);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    // 单块构建失败跳过（下帧发现不在 PENDING 会重新排入；失败场景极少）
                }
            }
        }, "promaid-ghost-vbo");
        t.setDaemon(true);
        t.start();
    }

    /** 后台线程：把全区块 12 棱线按【世界坐标】写入 BufferBuilder（无 camera 偏移——
     *  绘制时配 RenderSystem 的模型视图矩阵即世界可视；颜色与即时棱线同款饱和放大） */
    private static void buildGhostVbo(String key, Object[] pts, double ox, double oy, double oz,
                                      float r, float g, float b) {
        long boxes = pts.length / 4L;
        int init = (int) Math.min(Integer.MAX_VALUE - 8L, 4096L + boxes * 384L);
        com.mojang.blaze3d.vertex.BufferBuilder bb = new com.mojang.blaze3d.vertex.BufferBuilder(init);
        com.mojang.blaze3d.vertex.PoseStack stack = new com.mojang.blaze3d.vertex.PoseStack();
        float rc = Math.min(1.0f, r * 1.5f);
        float gc = Math.min(1.0f, g * 1.5f);
        float bc = Math.min(1.0f, b * 1.4f);
        for (int i = 0; i + 3 < pts.length; i += 4) {
            double wx = ox + (int) pts[i];
            double wy = oy + (int) pts[i + 1];
            double wz = oz + (int) pts[i + 2];
            double x1 = wx + 1.0, y1 = wy + 1.0, z1 = wz + 1.0;
            net.minecraft.world.phys.Vec3 c0 = new net.minecraft.world.phys.Vec3(wx, wy, wz);
            net.minecraft.world.phys.Vec3 c1 = new net.minecraft.world.phys.Vec3(x1, wy, wz);
            net.minecraft.world.phys.Vec3 c2 = new net.minecraft.world.phys.Vec3(x1, wy, z1);
            net.minecraft.world.phys.Vec3 c3 = new net.minecraft.world.phys.Vec3(wx, wy, z1);
            net.minecraft.world.phys.Vec3 t0 = new net.minecraft.world.phys.Vec3(wx, y1, wz);
            net.minecraft.world.phys.Vec3 t1 = new net.minecraft.world.phys.Vec3(x1, y1, wz);
            net.minecraft.world.phys.Vec3 t2 = new net.minecraft.world.phys.Vec3(x1, y1, z1);
            net.minecraft.world.phys.Vec3 t3 = new net.minecraft.world.phys.Vec3(wx, y1, z1);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c0, c1, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c1, c2, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c2, c3, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c3, c0, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, t0, t1, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, t1, t2, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, t2, t3, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, t3, t0, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c0, t0, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c1, t1, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c2, t2, rc, gc, bc);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(stack, bb, c3, t3, rc, gc, bc);
        }
        VBO_READY.put(key, bb.m_231168_());
    }

    /** 渲染线程：每帧最多上传一个完成的后台构建（上传是 GL 操作，不能在工作线程做） */
    private static void pumpGhostVboUploads() {
        for (java.util.Map.Entry<String, com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer> e : VBO_READY.entrySet()) {
            try {
                com.mojang.blaze3d.vertex.VertexBuffer vb =
                        new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
                vb.m_231221_(e.getValue());
                com.mojang.blaze3d.vertex.VertexBuffer old = GHOST_VBOS.put(e.getKey(), vb);
                if (old != null) {
                    try {
                        old.close();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            VBO_READY.remove(e.getKey());
            break; // 每帧一个
        }
    }

    /** 蓝图数据更新/清除时废弃对应旧 VBO（避免新旧点云错位显示） */
    private static void invalidateGhostVbo(String blueprintId) {
        String prefix = blueprintId + "#";
        for (java.util.Iterator<java.util.Map.Entry<String, com.mojang.blaze3d.vertex.VertexBuffer>> it =
             GHOST_VBOS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, com.mojang.blaze3d.vertex.VertexBuffer> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                try {
                    e.getValue().close();
                } catch (Throwable ignored) {
                }
                it.remove();
            }
        }
        for (java.util.Iterator<String> it = VBO_BUILD_PENDING.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith(prefix)) {
                VBO_BUILD_META.remove(k);
                it.remove();
            }
        }
        VBO_READY.keySet().removeIf(k -> k.startsWith(prefix));
    }

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
        // v1.1.0 实测一百三十二（用户："建筑投影的大致建筑轮廓又没有了"）：轮廓链路
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
            return;
        }
        PROJECTIONS.put(key, pts);
        invalidateGhostVbo(id);
        LOGGER.info("projection: key={} received {} blocks", key, pts.length / 4);
    }

    /**
     * 实测二百二十四：点云分片接收（S2C 多包）——等服务端把整云发完（seq 0..total-1）
     * 再拼装应用；大蓝图（上限 500000 点 ≈ 6MB）一包装不下，拆成 ≤16000 字符的切片。
     * 再收到重复 seq 覆盖即可（旧数据被新请求替代时自动回退到新 parts 数组）。
     */
    public static void onProjectionChunk(String id, int quarters, String size,
                                         int seq, int total, String chunk) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String key = projKey(id, quarters);
        if (PROJECTIONS.containsKey(key)) {
            return; // 已就绪（旧数据），忽略迟到的分片
        }
        String[] parts = PROJ_PARTS.get(key);
        if (parts == null || parts.length != total) {
            parts = new String[Math.max(1, total)];
            PROJ_PARTS.put(key, parts);
            PROJ_PARTS_SIZE.put(key, size == null ? "0,0,0" : size);
        }
        if (seq < 0 || seq >= parts.length) {
            return;
        }
        parts[seq] = chunk == null ? "" : chunk;
        for (String p : parts) {
            if (p == null) {
                return; // 未完，继续等
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
        }
        PROJ_PARTS.remove(key);
        String sz = PROJ_PARTS_SIZE.remove(key);
        setProjection(id, quarters, sz, sb.toString());
    }

    /**
     * 解析点云文本 → 平铺 Object[]{x,y,z,占位, x,y,z,占位, …}。
     * 格式 "x,y,z;x,y,z;…"（实测二百二十三：旧版 "x,y,z,id|state" 的状态段——
     * 实测一百四十七起渲染走 DebugRenderer 填充盒，BlockState 不再参与绘制，
     * 客户端却仍逐点做 SNBT 解析；已移除，状态槽留 null 占位）。
     */
    private static Object[] parseCloud(String cloud) {
        if (cloud == null || cloud.isEmpty()) {
            return new Object[0];
        }
        String[] segs = cloud.split(";");
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
                int z = Integer.parseInt(s.substring(c2 + 1));
                out[n++] = x;
                out[n++] = y;
                out[n++] = z;
                out[n++] = null;
            } catch (Exception ignored) {
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
        // v1.1.0 实测一百三十二（轮廓消失所）——渲染层空档记录日志：
        // REGION_BOXES = 红色框集合（有计划就有）、active = 金色预览开启状态。
        // 门不开=every~秒一次性记"有心但不可见"的精确原因（哪一种没开）。
        if (!registered || (!active && REGION_BOXES.isEmpty())) {
            return;
        }
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
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
        // 实测二百二十四：每帧最多上传一个后台构建完的幽灵线框 VBO（GL 调用必须渲染线程）
        pumpGhostVboUploads();
        net.minecraft.world.phys.Vec3 camera = event.getCamera().m_90583_().m_82548_();
        com.mojang.blaze3d.vertex.PoseStack pose = event.getPoseStack();
        pose.m_85836_(); // pushPose
        for (int i = 0; i < REGION_BOXES.size(); i++) {
            double[] b = REGION_BOXES.get(i);
            // 实测二百一十二（用户："从外部往内部看会发现是一个红色的正方体/长方体，
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
                // 实测二百二十四：红色区块（锚点固定）启用 VBO 线框缓存（全量棱线）
                drawGhost(pose, mc, camera, key, org[0], org[1], org[2],
                        1.0f, 0.55f, 0.25f, 0.55f, true);
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
            // 实测二百一十二：金色预览同样去掉大填充面（0.3 半透明大盒会从外面挡住
            // 青色幽灵——与红色区块框同病）；金色框棱线 + 标签已足够标识范围。
            com.mojang.blaze3d.vertex.VertexConsumer buf =
                    mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            drawBoxEdges(pose, buf, camera, x0, y0, z0, x1, y1, z1, 1.0f, 0.85f, 0.2f);
            if (previewId != null) {
                // 实测二百零一/二百零四：金预览青色幽灵 0.40 → 0.55（近景也清晰）
                // 实测二百二十四：金色预览锚点随玩家移动 → 不缓存 VBO，走近场回退
                drawGhost(pose, mc, camera, projKey(previewId, previewQuarters),
                        p.m_123341_(), p.m_123342_(), p.m_123343_(),
                        0.30f, 0.95f, 1.0f, 0.55f, false);
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
                                  float r, float g, float b, float a,
                                  boolean useVbo) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_PROJECTION.get()) {
            return;
        }
        Object[] pts = PROJECTIONS.get(id);
        if (pts == null || pts.length < 4) {
            return;
        }
        var bufferSource = mc.m_91269_().m_110104_();
        int total = pts.length / 4;
        // 实测二百二十四【全区块线框 = 后台构建的 VBO 缓存】：点云免抽稀后（上限
        // 500000）45.6 万盒 × 12 棱 = 千万级顶点/帧，即时重建不可能。固定坐标的
        // 红色区块用 VBO 缓存（构建在后台线程、渲染线程上传、每帧一次 drawWithShader
        // ——一次 submit 花百万级顶点的钱）；金色预览（锚点随玩家移动，不缓存）
        // 与 VBO 就绪前的回退 = 64 格内最近 6000 盒即时棱线。近处（32 格内最近
        // 2400 盒）满体积填充一律即时绘制。
        boolean vboDrawn = false;
        if (useVbo && total >= 32) {
            String vkey = id + "@" + ox + "," + oy + "," + oz;
            com.mojang.blaze3d.vertex.VertexBuffer vbo = GHOST_VBOS.get(vkey);
            if (vbo != null) {
                net.minecraft.client.renderer.RenderType lt =
                        net.minecraft.client.renderer.RenderType.f_110371_;
                try {
                    lt.m_110185_(); // setupRenderState（内部切到 lines 着色器）
                    vbo.m_253207_(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(),
                            com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(),
                            com.mojang.blaze3d.systems.RenderSystem.getShader());
                    vboDrawn = true;
                } catch (Throwable t) {
                    vboDrawn = false; // 驱动异常 → 回退即时棱线（不崩）
                } finally {
                    lt.m_110188_(); // clearRenderState
                }
            } else if (!VBO_BUILD_PENDING.containsKey(vkey) && !VBO_READY.containsKey(vkey)) {
                startGhostVboWorker();
                VBO_BUILD_PENDING.put(vkey, pts);
                VBO_BUILD_META.put(vkey, new double[]{ox, oy, oz, r, g, b});
                VBO_BUILD_QUEUE.offer(vkey);
            }
        }
        // 近处填充（≤32 格 3D 距离，最近 2400 盒）
        boolean[] fillSel = null;
        GhostBufferSource ghostSource = null;
        // 回退棱线（VBO 未就绪/金色预览）：≤64 格、最近 6000 盒
        int[][] edgeSel = null;
        double[] edgeDist = null;
        if (total > 0) {
            java.util.ArrayList<int[]> nearFill = new java.util.ArrayList<>();
            java.util.ArrayList<int[]> nearEdge = !vboDrawn ? new java.util.ArrayList<>() : null;
            for (int i = 0; i < total; i++) {
                double dx = ox + (int) pts[i * 4] + 0.5 - camera.f_82479_;
                double dy = oy + (int) pts[i * 4 + 1] + 0.5 - camera.f_82480_;
                double dz = oz + (int) pts[i * 4 + 2] + 0.5 - camera.f_82481_;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq <= 1024.0) {
                    nearFill.add(new int[]{i, (int) dSq});
                }
                if (nearEdge != null && dSq <= 4096.0) {
                    nearEdge.add(new int[]{i, (int) dSq});
                }
            }
            if (!nearFill.isEmpty()) {
                nearFill.sort(java.util.Comparator.comparingInt(o -> o[1]));
                int cap = Math.min(2400, nearFill.size());
                fillSel = new boolean[total];
                for (int k = 0; k < cap; k++) {
                    fillSel[nearFill.get(k)[0]] = true;
                }
                ghostSource = new GhostBufferSource();
            }
            if (nearEdge != null && !nearEdge.isEmpty()) {
                nearEdge.sort(java.util.Comparator.comparingInt(o -> o[1]));
                int cap = Math.min(6000, nearEdge.size());
                edgeSel = new int[cap][];
                for (int k = 0; k < cap; k++) {
                    edgeSel[k] = nearEdge.get(k);
                }
            }
        }
        int drawn = 0;
        int fillCount = 0;
        // 回退棱线（VBO 画过的场景这里跳过——全区块线框已由 VBO 提供）
        com.mojang.blaze3d.vertex.VertexConsumer edgeBuf = null;
        if (edgeSel != null) {
            edgeBuf = bufferSource.m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
        }
        for (int i = 0; i + 3 < pts.length; i += 4) {
            int bx = (int) pts[i];
            int by = (int) pts[i + 1];
            int bz = (int) pts[i + 2];
            double wx = ox + bx;
            double wy = oy + by;
            double wz = oz + bz;
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
        // 回退棱线第二遍绘制（选了最近 6000 盒才画——避免整点多遍线性扫）
        if (edgeBuf != null && edgeSel != null) {
            for (int[] sel : edgeSel) {
                int i = sel[0];
                double wx = ox + (int) pts[i];
                double wy = oy + (int) pts[i + 1];
                double wz = oz + (int) pts[i + 2];
                drawBoxEdges(pose, edgeBuf, camera, wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0,
                        Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
            }
        }
        // 实测二百零四：每帧一次落的诊断（数量变化/恢复才记，不刷屏）——"还是没显示"
        // 时日志直接区分：没进 drawGhost（0 盒/没到渲染层） vs 进了但客户端看不出
        if (drawn != lastDrawnCount) {
            lastDrawnCount = drawn;
            com.maidsmart.tool.PromaidLog.log("投影", "drawGhost(" + id + ") 盒数="
                    + drawn + " 填充=" + fillCount + " vbo=" + vboDrawn);
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
