package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(MaidAimGlowClient.class);
        }
    }

    /** Mixin 每帧问一次：这只实体是不是当前准星目标 */
    public static boolean isAimed(Entity entity) {
        return entity != null && entity == aimed && entity.m_6084_() && !entity.m_213877_();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc.f_91074_ == null || mc.f_91073_ == null) {
                clear();
                return;
            }
            boolean holding = holdingGlowItem(mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.MAIN_HAND))
                    || holdingGlowItem(mc.f_91074_.m_21120_(net.minecraft.world.InteractionHand.OFF_HAND));
            if (!holding) {
                clear();
                return;
            }
            Entity cam = mc.m_91288_() != null ? mc.m_91288_() : mc.f_91074_;
            float pt = mc.m_91296_();
            // 【R1118】旧版用的是 {@code Entity.pick}——它只做**方块**裁剪，永远不可能返回
            // {@link EntityHitResult}，于是 {@code found} 恒为 null、描边一次都没亮过。
            // 改用与远距离选女仆同一个实体射线（{@code ProjectileUtil.m_37304_}）。
            Vec3 eye = cam.m_20299_(pt);
            Vec3 end = eye.m_82549_(cam.m_20252_(pt).m_82490_(
                    com.maidsmart.tool.MaidFarPick.PICK_RANGE));
            AABB box = new AABB(eye, end).m_82400_(1.0);
            EntityHitResult ehr = net.minecraft.world.entity.projectile.ProjectileUtil.m_37304_(
                    mc.f_91073_, mc.f_91074_, eye, end, box,
                    e -> e instanceof EntityMaid && e.m_6084_());
            Entity found = ehr != null && ehr.m_82443_() instanceof EntityMaid
                    ? ehr.m_82443_() : null;
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
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        if (com.maidsmart.build.IndexStoneService.isEnabled()
                && com.maidsmart.build.IndexStoneInteractHandler.isIndexStone(stack)) {
            return true;
        }
        return com.maidsmart.combat.GunnerTetherManager.isEnabled()
                && stack.m_41720_() instanceof com.maidsmart.combat.CombatLeashItem;
    }

    private static void clear() {
        aimed = null;
        hold = 0;
    }
}
