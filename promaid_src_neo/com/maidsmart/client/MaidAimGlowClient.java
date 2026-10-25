package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 准星描边（R1108）：手持武装拴绳/指标石时，每 tick 做一次 512 格视线射线，命中的女仆在
 * 客户端被当作"发光"——渲染走原版发光描边同一条管线（与光灵箭一致），指针移开 / 收手 /
 * 换物品后自动解除，零网络流量、零服务端状态。
 *
 * <p>口径照傀儡装配（Modular Golems）的命令手杖：客户端每 tick 刷新目标 + Mixin 覆写
 * {@code Entity.isCurrentlyGlowing}；这里多一个 10 tick 去抖，视线抖动时不闪烁。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidAimGlowClient {

    /** 目标丢失后继续亮多少 tick（去抖；10 tick = 0.5 秒） */
    private static final int HOLD_TICKS = 10;

    private static Entity aimed;
    private static int hold;
    private static boolean registered;

    private MaidAimGlowClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(MaidAimGlowClient.class);
        }
    }

    /** Mixin 每帧问一次：这只实体是不是当前准星目标 */
    public static boolean isAimed(Entity entity) {
        return entity != null && entity == aimed && entity.isAlive() && !entity.isRemoved();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                clear();
                return;
            }
            boolean holding = holdingGlowItem(mc.player.getMainHandItem())
                    || holdingGlowItem(mc.player.getOffhandItem());
            if (!holding) {
                clear();
                return;
            }
            Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
            float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
            // 【R1118】旧版 {@code Entity.pick} 只做方块裁剪、永远不返回实体命中 → 描边从未亮过。
            Vec3 eye = cam.getEyePosition(pt);
            Vec3 end = eye.add(cam.getViewVector(pt).scale(
                    com.maidsmart.tool.MaidFarPick.PICK_RANGE));
            AABB box = new AABB(eye, end).inflate(1.0);
            EntityHitResult ehr = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                    mc.level, mc.player, eye, end, box,
                    e -> e instanceof EntityMaid && e.isAlive());
            Entity found = ehr != null && ehr.getEntity() instanceof EntityMaid
                    ? ehr.getEntity() : null;
            if (found != null) {
                aimed = found;
                hold = HOLD_TICKS;
                return;
            }
            if (hold > 0 && --hold == 0) {
                aimed = null;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidAimGlowClient", ignored);
        }
    }

    /** 两件物品各自守自己的开关：功能关掉时不描边 */
    private static boolean holdingGlowItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (com.maidsmart.build.IndexStoneService.isEnabled()
                && com.maidsmart.build.IndexStoneInteractHandler.isIndexStone(stack)) {
            return true;
        }
        return com.maidsmart.combat.GunnerTetherManager.isEnabled()
                && stack.getItem() instanceof com.maidsmart.combat.CombatLeashItem;
    }

    private static void clear() {
        aimed = null;
        hold = 0;
    }
}
