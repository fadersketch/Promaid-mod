package com.maidsmart.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v1.2.2 实测五百八十七：**女仆放下的炸弹的淡粉色标记**（纯客户端渲染）。
 *
 * 需求原文："女仆放置的这些物品默认会加一层很淡的粉色渲染，当然，这个效果也可以在手册内关闭。"
 *
 * 【口径】"女仆放下的东西"= 服务端用 {@code BombMarkNetworking} 发过来的标记，两种：
 * - 方块标记（黑曜石 / 重生锚 / 床）：按坐标画一个很淡的粉色半透明盒；
 * - 实体标记（末地水晶 / 扔出去的 TNT）：**按实体实时位置画**，所以 TNT 飞出去时那层粉框
 *   跟着它走，落地爆炸时一起消失。
 * 颜色刻意压得很淡（alpha 0.16 / 0.20，棱线稍亮），不开光影的玩家也能一眼认出"这是女仆刚
 * 放的"，又不会盖住方块本身的材质。开关：配置面板「战斗与自保 → 空袭数值 → 空袭轰炸」。
 *
 * 【渲染管线】与 {@code BlueprintAreaPreview} / {@code IndexStonePreviewClient} 同款：
 * 在 {@link RenderLevelStageEvent} 的 {@code AFTER_TRANSLUCENT_BLOCKS} 阶段，
 * 用原版 {@code DebugRenderer.renderFilledBox} 画填充盒（每盒独立 BufferSource + 立即
 * flush），棱线走 LINES 管线——三处代码风格一致，长期使用零崩溃。
 *
 * 【生命周期】标记自带 ttl（tick，服务端给的），客户端每 tick 减一并到期清理；
 * 退出世界一律清空（{@link #clear()}），避免"上个世界的粉框"飘到新世界。
 */
@net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
public final class BombMarkClient {

    private static boolean registered = false;
    /** 方块标记：{x, y, z, 过期 tick} */
    private static final List<int[]> BLOCKS = new ArrayList<>();
    /** 实体标记：entityId → 过期 tick */
    private static final Map<Integer, Integer> ENTITIES = new HashMap<>();
    private static int tick = 0;

    private BombMarkClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(BombMarkClient.class);
        }
    }

    /* ---------------- 服务端标记入口（由 BombMarkNetworking 调用） ---------------- */

    public static void markEntity(int entityId, int ttl) {
        ensureRegistered();
        ENTITIES.put(entityId, tick + ttl);
    }

    public static void markBlock(int x, int y, int z, int ttl) {
        ensureRegistered();
        int expire = tick + ttl;
        for (int[] b : BLOCKS) {
            if (b[0] == x && b[1] == y && b[2] == z) {
                b[3] = expire; // 同一格重复标记只需刷新时间
                return;
            }
        }
        BLOCKS.add(new int[]{x, y, z, expire});
    }

    public static void clear() {
        BLOCKS.clear();
        ENTITIES.clear();
    }

    /* ---------------- 事件 ---------------- */

    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        // Post：与服务端 tick 同步的收尾阶段
        tick++;
        for (Iterator<int[]> it = BLOCKS.iterator(); it.hasNext(); ) {
            if (it.next()[3] <= tick) {
                it.remove();
            }
        }
        Iterator<Map.Entry<Integer, Integer>> ei = ENTITIES.entrySet().iterator();
        while (ei.hasNext()) {
            if (ei.next().getValue() <= tick) {
                ei.remove();
            }
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRender(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (BLOCKS.isEmpty() && ENTITIES.isEmpty()) {
            return;
        }
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_BOMBING_PINK_MARK.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition().reverse();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            for (int[] b : BLOCKS) {
                drawBox(pose, mc, camera, b[0], b[1], b[2], 0.16f);
            }
            for (Integer id : ENTITIES.keySet()) {
                Entity e = mc.level.getEntity(id);
                if (e == null) {
                    continue;
                }
                drawEntityBox(pose, mc, camera, e);
            }
        } finally {
            pose.popPose();
        }
    }

    /* ---------------- 画 ---------------- */

    private static void drawEntityBox(PoseStack pose, Minecraft mc, Vec3 camera, Entity e) {
        try {
            AABB box = e.getBoundingBox().move(camera);
            MarkBufferSource src = new MarkBufferSource();
            net.minecraft.client.renderer.debug.DebugRenderer.renderFilledBox(pose, src, box, 1.0f, 0.62f, 0.80f, 0.20f);
            src.endBatch(net.minecraft.client.renderer.RenderType.debugFilledBox());
        } catch (Throwable ignored) {
        }
    }

    private static void drawBox(PoseStack pose, Minecraft mc, Vec3 camera,
                                int bx, int by, int bz, float a) {
        MarkBufferSource src = new MarkBufferSource();
        AABB box = new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0).move(camera);
        net.minecraft.client.renderer.debug.DebugRenderer.renderFilledBox(pose, src, box, 1.0f, 0.62f, 0.80f, a);
        src.endBatch(net.minecraft.client.renderer.RenderType.debugFilledBox());
    }

    /** 与 {@code BlueprintAreaPreview.GhostBufferSource} 同款：每盒独立、立即 flush */
    private static final class MarkBufferSource
            extends net.minecraft.client.renderer.MultiBufferSource.BufferSource {
        MarkBufferSource() {
            super(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1024), new java.util.LinkedHashMap<>());
        }
    }
}
