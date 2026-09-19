package com.maidsmart.build;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 指标石客户端渲染 + 输入（v1.2.0）。
 *
 * 渲染三样东西：
 * 1. 绿色追踪框：手持指标石且未锁定时，每帧用【512 格长射线】取视线指向的方块，
 *    画绿色线框 + 半透明填充（跟随指针实时移动）；
 * 2. 红色锁定框：已锁定的方块（服务端 S2C 下发的坐标），固定的红色线框；
 * 3. 橙色幽灵方块：已绑定女仆时，从女仆所在方块到锁定方块之间的空气格
 *    （S2C 下发的同一份格集合，保证"看到什么就填什么"逐格一致）。
 *
 * 输入：拦截 use 键（InteractionKeyMappingTriggered）——已锁定 → 只有右击【那个锁定方块】
 * 才发解锁请求（右击别的方块=换锁定点、右击空气=保持锁定）；
 * 未锁定 → 长射线锁定远处方块（超出原版触及距离才有必要拦截，近处交给原版 useOn）。
 * 这样"几乎无视距离"的锁定由客户端射线实现，服务端只做校验。
 *
 * 全部为客户端；注册方式与 BlueprintAreaPreview 同款（客户端总线）。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class IndexStonePreviewClient {

    /** 客户端长射线距离（几乎无视距离） */
    public static final double PICK_RANGE = IndexStoneService.LOCK_RANGE;
    /** 超出这个距离才拦截原版交互（近处交给原版 useOn，观感与其它物品一致） */
    private static final double NEAR_REACH = 5.0;

    /** 服务端下发的会话镜像 */
    private static volatile boolean locked = false;
    private static volatile int lx, ly, lz;
    private static volatile String maidId = "";
    private static volatile List<int[]> cells = java.util.Collections.emptyList();

    private static boolean registered = false;
    private static boolean hinted = false;
    /** v1.2.0 实测四百八十五：锁定期间的提示标记（与 hinted 互斥，共用 hintCooldown 限频） */
    private static boolean hintedLocked = false;
    private static int hintCooldown = 0;

    private IndexStonePreviewClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(IndexStonePreviewClient.class);
        }
    }

    /** S2C 包收到 → 更新本地镜像 */
    public static void set(boolean lockedIn, int x, int y, int z, String maid, List<int[]> cellsIn) {
        locked = lockedIn;
        lx = x;
        ly = y;
        lz = z;
        maidId = maid == null ? "" : maid;
        cells = cellsIn == null ? java.util.Collections.emptyList() : cellsIn;
    }

    /** v1.2.2 实测五百八十四（issue #13）：把客户端镜像清回空态。
     *  这份状态是 static（单人档里"退出世界"不会重载客户端类），而它只在收到 S2C
     *  时才更新——旧版"退出世界再进"仍是锁定中，右键被无限拦截、也锁不上新方块，
     *  玩家体感就是"指标石永久死锁、退出世界都救不回来"（服务端那边其实早清了）。 */
    public static void reset() {
        locked = false;
        lx = 0;
        ly = 0;
        lz = 0;
        maidId = "";
        cells = java.util.Collections.emptyList();
        hinted = false;
        hintedLocked = false;
        hintCooldown = 0;
    }

    /** v1.2.2 实测五百八十四（issue #13）：断线/重连时清一次本地镜像（双保险——
     *  服务端另有登录/换维度补推，两条独立通道任意一条生效即可自愈）。 */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
    }

    public static boolean isLockedLocally() {
        return locked;
    }

    /** 对着空气右键但没锁到方块 → 提示（限频 2 秒，由 tick 发到聊天栏） */
    public static void hintUsage() {
        if (hintCooldown <= 0) {
            hinted = true;
            hintCooldown = 40;
        }
    }

    /**
     * v1.2.0 实测四百八十五：锁定期间的专属提示——右击别的方块无效，要先解锁。
     * 与 {@link #hintUsage} 共用限频（同一 tick 不会刷两条）。
     */
    public static void hintLocked() {
        if (hintCooldown <= 0) {
            hintedLocked = true;
            hintCooldown = 40;
        }
    }

    // ==================== 输入：长射线锁定远处方块 ====================

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onInteraction(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || !IndexStoneService.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91074_ == null || mc.f_91073_ == null) {
            return;
        }
        ItemStack main = mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack off = mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.OFF_HAND);
        if (!IndexStoneInteractHandler.isIndexStone(main) && !IndexStoneInteractHandler.isIndexStone(off)) {
            return;
        }
        // 【关键】视线指向实体 → 绝不拦截：右键女仆=绑定/解绑，必须交给原版
        // EntityInteract 链路（若这里 cancel，原版 handleKeybinds 会整段跳过
        // startUseItem → 绑定流程永远收不到包 = 绑不上女仆）。
        // 判定用 mc.f_91077_（原版每帧 pick 的结果）——与 startUseItem 判 ENTITY
        // 分支用的是同一个 hitResult，逐帧同步、无需自己射线。
        if (mc.f_91077_ instanceof net.minecraft.world.phys.EntityHitResult) {
            return;
        }
        if (locked) {
            // v1.2.0 实测四百八十五【锁定期间不可改选】：锁定的语义 = 期间只能操作这一格。
            // 旧版（实测四百八十四）虽已做到"右击别处不解锁"，但仍会把右键当作【换锁定点】，
            // 等于锁定期间还能改选别的方块 —— 与用户要求不符。现在改为：
            //  ① 右击的就是那个锁定方块 → 解除锁定；
            //  ② 右击任何【别的方块 / 空气】→ 一律无效（锁定保持不变），只提示怎么解锁；
            //  ③【潜行 + 右键】= 无条件解锁出口（v1.2.2 实测五百八十四，issue #13）。
            // 解锁原本只有"重新右击那一格"一条路，而锁定格完全可能已经瞄不到（水/地形
            // 遮挡、走远超出 512 格长射线）→ 永久死锁。潜行右键在这里不占任何既有操作，
            // 是安全的逃生通道；服务端仍按原规则校验（女仆正在搭建时会婉拒）。
            if (mc.f_91074_.m_6040_()) {
                IndexStoneNetworking.CHANNEL.sendToServer(new IndexStoneNetworking.LockRequestPacket(true, 0, 0, 0));
                event.setCanceled(true);
                return;
            }
            BlockPos aim = pickAnyBlock(mc);
            if (aim != null && aim.m_123341_() == lx && aim.m_123342_() == ly
                    && aim.m_123343_() == lz) {
                // 右击的就是那个锁定方块 → 解除锁定
                IndexStoneNetworking.CHANNEL.sendToServer(
                        new IndexStoneNetworking.LockRequestPacket(true, 0, 0, 0));
                event.setCanceled(true);
                return;
            }
            // 其它一律无效：吞掉这次右键 + 提示（先解锁才能选新的）
            hintLocked();
            event.setCanceled(true);
            return;
        }
        // 未锁定 → 长射线；只有命中【超出原版触及距离】的方块才由我们接管
        BlockPos far = farPick(mc);
        if (far != null) {
            IndexStoneNetworking.CHANNEL.sendToServer(
                    new IndexStoneNetworking.LockRequestPacket(false,
                            far.m_123341_(), far.m_123342_(), far.m_123343_()));
            event.setCanceled(true);
            return;
        }
        // 近处方块 / 没命中：不拦截，交给原版 useOn（近处锁定）/ 原版空挥
        if (clientPickDistance(mc) > NEAR_REACH) {
            hintUsage(); // 指向空气（没命中任何方块）→ 提示用法
        }
    }

    /**
     * v1.2.0 实测四百八十四：长射线命中的方块，**不做距离过滤**。
     *
     * 与 {@link #farPick} 的区别：已锁定时要判断"我右击的是不是就是那个锁定方块"，
     * 而这个判断对**近处方块同样成立**（锁定的方块常常就在眼前）。
     * `farPick` 会把 ≤ 触及距离的命中过滤成 null，拿它判断会漏掉近处的锁定格。
     *
     * @return 命中的非空气方块；未命中/空气 → null
     */
    private static BlockPos pickAnyBlock(Minecraft mc) {
        try {
            Entity cam = mc.m_91288_() != null ? mc.m_91288_() : mc.f_91074_;
            HitResult hit = cam.m_19907_(PICK_RANGE, mc.m_91296_(), false);
            if (hit instanceof BlockHitResult bhr && bhr.m_6662_() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.m_82425_();
                if (!mc.f_91073_.m_8055_(p).m_60795_()) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 长射线命中的方块位置；命中距离 ≤ 原版触及距离 或 未命中/空气 → null */
    private static BlockPos farPick(Minecraft mc) {
        try {
            Entity cam = mc.m_91288_() != null ? mc.m_91288_() : mc.f_91074_;
            HitResult hit = cam.m_19907_(PICK_RANGE, mc.m_91296_(), false);
            if (hit instanceof BlockHitResult bhr && bhr.m_6662_() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.m_82425_();
                if (mc.f_91073_.m_8055_(p).m_60795_()) {
                    return null; // 不可锁空气
                }
                double d = cam.m_20182_().m_82554_(bhr.m_82425_().m_252807_());
                return d > NEAR_REACH ? p : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 长射线到底打了多远（未命中返回 Double.MAX_VALUE） */
    private static double clientPickDistance(Minecraft mc) {
        try {
            Entity cam = mc.m_91288_() != null ? mc.m_91288_() : mc.f_91074_;
            HitResult hit = cam.m_19907_(PICK_RANGE, mc.m_91296_(), false);
            if (hit instanceof BlockHitResult bhr && bhr.m_6662_() == HitResult.Type.BLOCK) {
                return cam.m_20182_().m_82554_(bhr.m_82425_().m_252807_());
            }
        } catch (Throwable ignored) {
        }
        return Double.MAX_VALUE;
    }

    // ==================== 渲染 ====================

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (hintCooldown > 0) {
            hintCooldown--;
        }
        if (hintedLocked) {
            hintedLocked = false;
            Minecraft mc = Minecraft.m_91087_();
            if (mc.f_91074_ != null) {
                mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(IndexStoneItem.lockedHint()));
            }
            return;
        }
        if (!hinted) {
            return;
        }
        hinted = false;
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91074_ != null) {
            mc.f_91074_.m_213846_(net.minecraft.network.chat.Component.m_237113_(IndexStoneItem.usageHint()));
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage()
                != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91074_ == null || mc.f_91073_ == null) {
            return;
        }
        ItemStack main = mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack off = mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.OFF_HAND);
        boolean holding = IndexStoneInteractHandler.isIndexStone(main)
                || IndexStoneInteractHandler.isIndexStone(off);
        // 不手持且没有需要渲染的锁/绑状态 → 空态短路（常驻零开销）
        if ((!holding && !locked && cells.isEmpty()) || !IndexStoneService.isEnabled()) {
            return;
        }
        Vec3 camera = event.getCamera().m_90583_().m_82548_();
        PoseStack pose = event.getPoseStack();
        pose.m_85836_();
        try {
            if (holding && !locked) {
                BlockPos p = greenPick(mc);
                if (p != null) {
                    drawBox(pose, mc, camera, p.m_123341_(), p.m_123342_(), p.m_123343_(),
                            0.25f, 1.0f, 0.35f, 0.30f);
                }
            }
            if (locked) {
                drawBox(pose, mc, camera, lx, ly, lz, 1.0f, 0.2f, 0.15f, 0.30f);
                if (!cells.isEmpty()) {
                    drawGhost(pose, mc, camera, cells);
                }
                drawLineToMaid(pose, mc, camera);
            }
        } finally {
            pose.m_85849_();
        }
    }

    /** 绿色追踪框的命中块（长射线；空气返回 null） */
    private static BlockPos greenPick(Minecraft mc) {
        try {
            Entity cam = mc.m_91288_() != null ? mc.m_91288_() : mc.f_91074_;
            HitResult hit = cam.m_19907_(PICK_RANGE, mc.m_91296_(), false);
            if (hit instanceof BlockHitResult bhr && bhr.m_6662_() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.m_82425_();
                if (mc.f_91073_.m_8055_(p).m_60795_()) {
                    return null;
                }
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 一个方块的线框 + 半透明填充 */
    private static void drawBox(PoseStack pose, Minecraft mc, Vec3 camera,
                                int bx, int by, int bz, float r, float g, float b, float a) {
        VertexConsumer edge = mc.m_91269_().m_110104_()
                .m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
        drawBoxEdges(pose, edge, camera, bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
        GhostBufferSource src = new GhostBufferSource();
        AABB box = new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0).m_82383_(camera);
        net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(pose, src, box, r, g, b, a);
        src.m_109912_(net.minecraft.client.renderer.RenderType.m_269313_());
    }

    /** 橙色幽灵格集合（与建造投影同一渲染口径：线框 + 近处填充，限 2400 盒） */
    private static void drawGhost(PoseStack pose, Minecraft mc, Vec3 camera, List<int[]> list) {
        VertexConsumer edge = mc.m_91269_().m_110104_()
                .m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
        float r = 1.0f, g = 0.55f, b = 0.25f, a = 0.55f;
        GhostBufferSource src = new GhostBufferSource();
        int filled = 0;
        for (int[] c : list) {
            int bx = c[0], by = c[1], bz = c[2];
            drawBoxEdges(pose, edge, camera, bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                    Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
            double dx = bx + 0.5 - camera.f_82479_;
            double dy = by + 0.5 - camera.f_82480_;
            double dz = bz + 0.5 - camera.f_82481_;
            if (dx * dx + dy * dy + dz * dz <= 1024.0 && filled < 2400) { // 32 格内填充
                AABB box = new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0).m_82383_(camera);
                net.minecraft.client.renderer.debug.DebugRenderer.m_269311_(pose, src, box, r, g, b, a);
                filled++;
            }
        }
        src.m_109912_(net.minecraft.client.renderer.RenderType.m_269313_());
    }

    /** 从绑定女仆到锁定格画一条橙色引导线（客户端实体位置可用，无需额外包） */
    private static void drawLineToMaid(PoseStack pose, Minecraft mc, Vec3 camera) {
        if (maidId == null || maidId.isEmpty() || mc.f_91073_ == null) {
            return;
        }
        try {
            java.util.UUID id = java.util.UUID.fromString(maidId);
            Entity found = null;
            for (Entity e : mc.f_91073_.m_104735_()) {
                if (e.m_20148_().equals(id)) {
                    found = e;
                    break;
                }
            }
            if (found == null) {
                return;
            }
            VertexConsumer edge = mc.m_91269_().m_110104_()
                    .m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            // camera 已是 -camPos：camera.add(世界坐标) = 相机相对坐标（与 drawBoxEdges 同口径）
            Vec3 from = camera.m_82520_(found.m_20185_(), found.m_20186_() + 1.0, found.m_20189_());
            Vec3 to = camera.m_82520_(lx + 0.5, ly + 0.5, lz + 0.5);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, edge,
                    from, to, 1.0f, 0.65f, 0.25f);
        } catch (Throwable ignored) {
        }
    }

    /** 画一个方框的 12 条棱（TLM RenderHelper.renderLine） */
    private static void drawBoxEdges(PoseStack pose, VertexConsumer buf, Vec3 camera,
                                     double x0, double y0, double z0,
                                     double x1, double y1, double z1,
                                     float r, float g, float b) {
        Vec3 c0 = camera.m_82520_(x0, y0, z0);
        Vec3 c1 = camera.m_82520_(x1, y0, z0);
        Vec3 c2 = camera.m_82520_(x1, y0, z1);
        Vec3 c3 = camera.m_82520_(x0, y0, z1);
        Vec3 c4 = camera.m_82520_(x0, y1, z0);
        Vec3 c5 = camera.m_82520_(x1, y1, z0);
        Vec3 c6 = camera.m_82520_(x1, y1, z1);
        Vec3 c7 = camera.m_82520_(x0, y1, z1);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, c1, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, c2, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, c3, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, c0, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c4, c5, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c5, c6, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c6, c7, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c7, c4, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, c4, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, c5, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, c6, r, g, b);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, c7, r, g, b);
    }

    /** 幽灵方块专用 BufferSource（每帧新建、只写 debugFilledBox，与主 bufferSource 隔离） */
    private static final class GhostBufferSource
            extends net.minecraft.client.renderer.MultiBufferSource.BufferSource {
        GhostBufferSource() {
            super(new com.mojang.blaze3d.vertex.BufferBuilder(4096), new java.util.HashMap<>());
        }
    }
}
