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
 * 指标石客户端渲染 + 输入（v1.2.0，1.21.1 NeoForge 版）。
 *
 * 渲染：
 * 1. 绿色追踪框：手持指标石且未锁定时，每帧用【512 格长射线】取视线指向的方块，
 *    画绿色线框 + 半透明填充（跟随指针实时移动）；
 * 2. 红色锁定框：已锁定方块（服务端 S2C 下发），固定红色线框；
 * 3. 橙色幽灵方块：已绑定女仆时，从女仆所在方块到锁定方块之间的空气格
 *    （S2C 下发的同一份格集合，保证"看到什么就填什么"逐格一致）。
 *
 * 输入：拦截 use 键（InteractionKeyMappingTriggered）——已锁定 → 只有右击【那个锁定方块】
 * 才发解锁请求（右击别的方块=换锁定点、右击空气=保持锁定）；
 * 未锁定且命中超出原版触及距离的方块 → 长射线锁定（实现"几乎无视距离"）。
 */
@net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
public final class IndexStonePreviewClient {

    public static final double PICK_RANGE = IndexStoneService.LOCK_RANGE;
    /** 超出这个距离才拦截原版交互（近处交给原版 useOn） */
    private static final double NEAR_REACH = 5.0;

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
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(IndexStonePreviewClient.class);
        }
    }

    public static void set(boolean lockedIn, int x, int y, int z, String maid, List<int[]> cellsIn) {
        locked = lockedIn;
        lx = x;
        ly = y;
        lz = z;
        maidId = maid == null ? "" : maid;
        cells = cellsIn == null ? java.util.Collections.emptyList() : cellsIn;
    }

    public static boolean isLockedLocally() {
        return locked;
    }

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

    @net.neoforged.bus.api.SubscribeEvent
    public static void onInteraction(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || !IndexStoneService.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ItemStack main = mc.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack off = mc.player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
        if (!IndexStoneInteractHandler.isIndexStone(main) && !IndexStoneInteractHandler.isIndexStone(off)) {
            return;
        }
        if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult) {
            return; // 指向实体 → 交给原版（右键女仆=绑定，绝不能被吞）
        }
        if (locked) {
            // v1.2.0 实测四百八十五【锁定期间不可改选】：锁定的语义 = 期间只能操作这一格。
            // 旧版（实测四百八十四）虽已做到"右击别处不解锁"，但仍会把右键当作【换锁定点】，
            // 等于锁定期间还能改选别的方块 —— 与用户要求不符。现在改为：
            //  ① 右击的就是那个锁定方块 → 解除锁定；
            //  ② 右击任何【别的方块 / 空气】→ 一律无效（锁定保持不变），只提示怎么解锁。
            BlockPos aim = pickAnyBlock(mc);
            if (aim != null && aim.getX() == lx && aim.getY() == ly && aim.getZ() == lz) {
                // 右击的就是那个锁定方块 → 解除锁定
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new IndexStoneNetworking.LockRequestPacket(true, 0, 0, 0));
                event.setCanceled(true);
                return;
            }
            // 其它一律无效：吞掉这次右键 + 提示（先解锁才能选新的）
            hintLocked();
            event.setCanceled(true);
            return;
        }
        BlockPos far = farPick(mc);
        if (far != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new IndexStoneNetworking.LockRequestPacket(false, far.getX(), far.getY(), far.getZ()));
            event.setCanceled(true);
            return;
        }
        if (clientPickDistance(mc) > NEAR_REACH) {
            hintUsage(); // 指向空气 → 提示用法
        }
    }

    /** 长射线命中的方块；命中距离 ≤ 原版触及距离 或 未命中/空气 → null */
    private static BlockPos farPick(Minecraft mc) {
        try {
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            HitResult hit = cam.pick(PICK_RANGE, mc.getTimer().getGameTimeDeltaPartialTick(false), false);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.getBlockPos();
                if (mc.level.getBlockState(p).isAir()) {
                    return null; // 不可锁空气
                }
                double d = cam.position().distanceTo(bhr.getBlockPos().getCenter());
                return d > NEAR_REACH ? p : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
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
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            HitResult hit = cam.pick(PICK_RANGE, mc.getTimer().getGameTimeDeltaPartialTick(false), false);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.getBlockPos();
                if (!mc.level.getBlockState(p).isAir()) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static double clientPickDistance(Minecraft mc) {
        try {
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            HitResult hit = cam.pick(PICK_RANGE, mc.getTimer().getGameTimeDeltaPartialTick(false), false);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                return cam.position().distanceTo(bhr.getBlockPos().getCenter());
            }
        } catch (Throwable ignored) {
        }
        return Double.MAX_VALUE;
    }

    // ==================== 渲染 ====================

    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (hintCooldown > 0) {
            hintCooldown--;
        }
        if (hintedLocked) {
            hintedLocked = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(IndexStoneItem.lockedHint()), false);
            }
            return;
        }
        if (!hinted) {
            return;
        }
        hinted = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(IndexStoneItem.usageHint()), false);
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRender(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage()
                != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ItemStack main = mc.player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack off = mc.player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
        boolean holding = IndexStoneInteractHandler.isIndexStone(main)
                || IndexStoneInteractHandler.isIndexStone(off);
        if ((!holding && !locked && cells.isEmpty()) || !IndexStoneService.isEnabled()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition().reverse();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            if (holding && !locked) {
                BlockPos p = greenPick(mc);
                if (p != null) {
                    drawBox(pose, mc, camera, p.getX(), p.getY(), p.getZ(), 0.25f, 1.0f, 0.35f, 0.30f);
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
            pose.popPose();
        }
    }

    private static BlockPos greenPick(Minecraft mc) {
        try {
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            HitResult hit = cam.pick(PICK_RANGE, mc.getTimer().getGameTimeDeltaPartialTick(false), false);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos p = bhr.getBlockPos();
                if (mc.level.getBlockState(p).isAir()) {
                    return null;
                }
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void drawBox(PoseStack pose, Minecraft mc, Vec3 camera,
                                int bx, int by, int bz, float r, float g, float b, float a) {
        VertexConsumer edge = mc.renderBuffers().bufferSource()
                .getBuffer(net.minecraft.client.renderer.RenderType.LINES);
        drawBoxEdges(pose, edge, camera, bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
        GhostBufferSource src = new GhostBufferSource();
        AABB box = new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0).move(camera);
        net.minecraft.client.renderer.debug.DebugRenderer.renderFilledBox(pose, src, box, r, g, b, a);
        src.endBatch(net.minecraft.client.renderer.RenderType.debugFilledBox());
    }

    private static void drawGhost(PoseStack pose, Minecraft mc, Vec3 camera, List<int[]> list) {
        VertexConsumer edge = mc.renderBuffers().bufferSource()
                .getBuffer(net.minecraft.client.renderer.RenderType.LINES);
        float r = 1.0f, g = 0.55f, b = 0.25f, a = 0.55f;
        GhostBufferSource src = new GhostBufferSource();
        int filled = 0;
        for (int[] c : list) {
            int bx = c[0], by = c[1], bz = c[2];
            drawBoxEdges(pose, edge, camera, bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0,
                    Math.min(1.0f, r * 1.5f), Math.min(1.0f, g * 1.5f), Math.min(1.0f, b * 1.4f));
            double dx = bx + 0.5 - camera.x;
            double dy = by + 0.5 - camera.y;
            double dz = bz + 0.5 - camera.z;
            if (dx * dx + dy * dy + dz * dz <= 1024.0 && filled < 2400) {
                AABB box = new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0).move(camera);
                net.minecraft.client.renderer.debug.DebugRenderer.renderFilledBox(pose, src, box, r, g, b, a);
                filled++;
            }
        }
        src.endBatch(net.minecraft.client.renderer.RenderType.debugFilledBox());
    }

    private static void drawLineToMaid(PoseStack pose, Minecraft mc, Vec3 camera) {
        if (maidId == null || maidId.isEmpty() || mc.level == null) {
            return;
        }
        try {
            java.util.UUID id = java.util.UUID.fromString(maidId);
            Entity found = null;
            for (Entity e : mc.level.getEntitiesOfClass(Entity.class,
                    new AABB(-131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0))) {
                if (e.getUUID().equals(id)) {
                    found = e;
                    break;
                }
            }
            if (found == null) {
                return;
            }
            VertexConsumer edge = mc.renderBuffers().bufferSource()
                    .getBuffer(net.minecraft.client.renderer.RenderType.LINES);
            Vec3 from = camera.add(found.getX(), found.getY() + 1.0, found.getZ());
            Vec3 to = camera.add(lx + 0.5, ly + 0.5, lz + 0.5);
            com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, edge,
                    from, to, 1.0f, 0.65f, 0.25f);
        } catch (Throwable ignored) {
        }
    }

    private static void drawBoxEdges(PoseStack pose, VertexConsumer buf, Vec3 camera,
                                     double x0, double y0, double z0,
                                     double x1, double y1, double z1,
                                     float r, float g, float b) {
        Vec3 c0 = camera.add(x0, y0, z0);
        Vec3 c1 = camera.add(x1, y0, z0);
        Vec3 c2 = camera.add(x1, y0, z1);
        Vec3 c3 = camera.add(x0, y0, z1);
        Vec3 c4 = camera.add(x0, y1, z0);
        Vec3 c5 = camera.add(x1, y1, z0);
        Vec3 c6 = camera.add(x1, y1, z1);
        Vec3 c7 = camera.add(x0, y1, z1);
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
            super(new com.mojang.blaze3d.vertex.ByteBufferBuilder(4096), new java.util.LinkedHashMap<>());
        }
    }
}
