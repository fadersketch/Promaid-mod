package com.maidsmart.build;

/**
 * v1.5.159：建造范围预览（"区块显示"）——手册建筑详情页点击后：
 * - 退出手册，世界内以【玩家为中心】显示该建筑占地大小的金色框
 *   （半透明填充 + 线框 + 顶部悬浮文字），随玩家位置移动而移动
 *   （方便选位置）；颜色用金色，区别于原版 F3+G 的白色区块边界
 * - 再次打开手册即关闭预览（BlueprintBookScreen.open 调用 clear）
 * - v1.5.164：建造区域【定下来】后自动显示【红色固定框】——一旦有进行中/暂停中的
 *   建造计划（服务端下发区块标记 regionX/Y/Z + 宽高深），世界内以【实际建造区域】
 *   显示红色框（不随玩家移动，固定在原地），直到计划取消/完成才消失；
 *   金色预览框（玩家选择位置用）与红色区域框互不影响、可同时显示。
 * - 渲染完全照抄 TLM MaidAreaRenderEvent 的坐标约定（camera + 世界坐标）与
 *   TLM RenderHelper（renderLine / renderFloatingText）+ 原版
 *   DebugRenderer.renderFilledBox（m_269311_）——均已验证可运行。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class BlueprintAreaPreview {
    private static boolean active = false;
    private static int sizeX = 1;
    private static int sizeY = 1;
    private static int sizeZ = 1;
    private static boolean registered = false;
    /** v1.5.188b：玩家是否已看过本次区块预览（建造确认流程第 1 步——看过才放行
     *  第 2 步确认弹窗；区块显示按钮主动打开也算）。
     *  v1.5.204：重开手册【不再重置】——旧版 clear() 把标记清掉，第 1 步提示
     *  "再次打开手册点击确认建造"，但重开后点击又走第 1 步 → 永远进不了第 2 步
     *  （"卡在第一步一直循环"的根因）。标记只在区块真正创建成功后 resetSeen() 重置。 */
    private static boolean previewSeen = false;

    /** v1.5.180：实际建造区块的红色固定框（多区块共存——每个区块一框）
     *  框 = {x0,y0,z0,x1,y1,z1}；名称与框一一对应（顶部悬浮文字） */
    private static final java.util.List<double[]> REGION_BOXES = new java.util.ArrayList<>();
    private static final java.util.List<String> REGION_NAMES = new java.util.ArrayList<>();
    /** v1.5.290：每个区块的创建坐标文本（"x, y, z"——玩家创建区块时的原点；
     *  渲染在名字下方第二行。v1.5.279 起服务端下发 r[11..13]，v1.5.290 encode
     *  修 14 字段后真正到达客户端） */
    private static final java.util.List<String> REGION_ORIGINS = new java.util.ArrayList<>();

    private BlueprintAreaPreview() {
    }

    /** 开启预览：以玩家为中心的 W×H×D 金色框（打开手册即关闭） */
    public static void show(int sx, int sy, int sz) {
        sizeX = Math.max(1, sx);
        sizeY = Math.max(1, sy);
        sizeZ = Math.max(1, sz);
        active = true;
        previewSeen = true; // v1.5.188b：看过预览 → 建造确认流程放行第 2 步
        ensureRegistered();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7e【建造范围预览】" + sizeX + "\u00d7" + sizeY + "\u00d7" + sizeZ
                            + " 格——金色框以你为中心，移动可见范围；再次打开手册关闭预览"));
        }
    }

    /** v1.5.188b：是否已看过区块预览（建造确认流程第 1 步放行判断） */
    public static boolean wasShown() {
        return previewSeen;
    }

    /** v1.5.204：建造确认流程标记重置——区块【真正创建成功后】调用（下一轮建造
     *  仍需先看范围，防误操作）；与 clear() 分离：clear 只关金色框渲染 */
    public static void resetSeen() {
        previewSeen = false;
    }

    /** v1.5.188b：区块内控制一体化——自动重新打开红色区块框（不重置"看过预览"标记，
     *  不改变金色框状态；只要服务端推送过区块范围就保持显示） */
    public static void ensureShown() {
        if (!REGION_BOXES.isEmpty()) {
            ensureRegistered();
        }
    }

    /**
     * v1.5.180：设置实际建造区块（红色固定框，多框）——服务端每次推送进度状态时同步；
     * 行格式 {planId, 显示名, 维度名, 状态, x, y, z, W, H, D, blueprintId}；
     * 无区块时清空（取消/完成建造后自动消失）。
     */
    public static void setRegions(java.util.List<String[]> regions) {
        REGION_BOXES.clear();
        REGION_NAMES.clear();
        REGION_ORIGINS.clear();
        if (regions == null) {
            return;
        }
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
            } catch (NumberFormatException ignored) {
            }
        }
        if (!REGION_BOXES.isEmpty()) {
            ensureRegistered();
        }
    }

    public static void clear() {
        active = false;
        // v1.5.204：不再重置 previewSeen——重开手册只关金色框渲染；"看过预览"
        // 标记跨手册会话保留（第 1 步提示"再次打开手册点击确认"后能真正走到
        // 第 2 步确认弹窗），由 resetSeen()（区块创建成功后）显式重置
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
        if (!REGION_BOXES.isEmpty()) {
            // v1.5.164：红色固定框——实际建造区块（v1.5.180：多区块各画一框）
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
                // 旧版 NORMAL 深度测试：区块外/隔方块看会被遮挡，实际只有站在区块内部
                // 才看得到（用户："只能在内部才能看到"）；现在任何角度、隔方块都可见
                com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                        "\u00a7c「" + label + "」（建造中）",
                        (b[0] + b[3]) / 2.0, b[4] + 0.8, (b[2] + b[5]) / 2.0,
                        0xFF5544, 0.15f, true, -5.0f, true);
                // v1.5.290：创建坐标第二行（用户："坐标显示在苔藓神庙三建造中的下面"——
                // 世界内区块标记下方显示玩家创建该区块时的坐标）
                // v1.5.297：第二行下移——旧版与名字行锚点仅差 0.35 格 < 行高（0.15 字
                // 高约 1.3 格）→ 两行叠在一起（截图实证「苔藓神庙-214，-60 建造中」）；
                // 现在锚点差 1.4 格，净距约 0.45 格不再重叠
                String origin = i < REGION_ORIGINS.size() ? REGION_ORIGINS.get(i) : "";
                if (!origin.isEmpty()) {
                    com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                            "\u00a78创建于 " + origin,
                            (b[0] + b[3]) / 2.0, b[4] - 0.6, (b[2] + b[5]) / 2.0,
                            0x888888, 0.12f, true, -5.0f, true);
                }
            }
        }
        if (active) {
            // 金色预览框：以玩家所在格为中心（每帧取玩家位置 → 框随玩家移动）
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
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderFloatingText(pose,
                    "建造范围 " + sizeX + "\u00d7" + sizeY + "\u00d7" + sizeZ + "（打开手册关闭）",
                    x0 + sizeX / 2.0, y1 + 0.6, z0 + sizeZ / 2.0, 0xFFDD55, 0.15f, true, -5.0f, false);
        }
        pose.m_85849_(); // popPose
    }

    /** 画一个方框的 12 条棱（TLM RenderHelper.renderLine） */
    private static void drawBoxEdges(com.mojang.blaze3d.vertex.PoseStack pose,
                                     com.mojang.blaze3d.vertex.VertexConsumer buf,
                                     net.minecraft.world.phys.Vec3 camera,
                                     double x0, double y0, double z0, double x1, double y1, double z1,
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
