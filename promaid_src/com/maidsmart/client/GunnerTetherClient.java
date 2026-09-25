package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * v1.3.7 实测六百六十七：武装拴绳的绳子（纯客户端渲染）。
 *
 * 【画什么】"她的二号位枪手"从女仆腰部（脚底 +0.75）到玩家手部（脚底 + 身高×0.85）之间的
 * 一根带一点下垂的棕绳——原版拴绳观感。状态来自服务端 S2C
 * （{@link com.maidsmart.combat.GunnerTetherNetworking} → {@link #onSync}），
 * 客户端自己不判断"这是不是拴绳挂的"。
 *
 * 【渲染管线】与 {@code BombMarkClient} 完全同款：{@link RenderLevelStageEvent} 的
 * AFTER_TRANSLUCENT_BLOCKS 阶段 + 原版 LINES 管线 + TLM
 * {@code RenderHelper.renderLine}（相机位移、push/pop、缓冲获取全部照抄——三处同源
 * 代码长期使用零崩溃）。分两段 + 中点下垂 0.12 格，静止时贴得近看不出、她机动时绳子
 * 有"绷住"的感觉。
 *
 * 【生命周期】退出世界清空（{@link #clear()}）；对端实体不在/已下鞍（原版乘客同步已断）
 * 则跳过绘制，等下一次解除/挂载包修正。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class GunnerTetherClient {

    private static boolean registered = false;
    /** 绳色：麻绳棕 */
    private static final float R = 0.55f;
    private static final float G = 0.36f;
    private static final float B = 0.22f;
    /** 跳过阈值：两端离得比这还远 = 状态已过期（等解除包），不再硬画 */
    private static final double MAX_ROPE_DISTANCE_SQR = 64.0;

    private GunnerTetherClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(GunnerTetherClient.class);
        }
    }

    /* ---------------- 服务端状态入口（由 GunnerTetherNetworking 调用） ---------------- */

    public static void onSync(int maidId, int riderId) {
        ensureRegistered();
        if (riderId < 0) {
            com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.remove(maidId);
        } else {
            com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.put(maidId, riderId);
        }
    }

    public static void clear() {
        com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.clear();
    }

    /* ---------------- 事件 ---------------- */

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.isEmpty()) {
            return;
        }
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_ENABLE.get()) {
            return;
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91073_ == null) {
            return;
        }
        Vec3 camera = event.getCamera().m_90583_();
        PoseStack pose = event.getPoseStack();
        VertexConsumer buf = mc.m_91269_().m_110104_().m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
        pose.m_85836_();
        try {
            for (Map.Entry<Integer, Integer> e : com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.entrySet()) {
                Entity maid = mc.f_91073_.m_6815_(e.getKey());
                Entity rider = mc.f_91073_.m_6815_(e.getValue());
                if (!(maid instanceof EntityMaid) || !(rider instanceof Player)) {
                    continue;
                }
                // 原版乘客同步已断（她落地自动放人/解除包还没到）→ 不画
                if (!maid.m_20363_(rider)) {
                    continue;
                }
                if (maid.m_20238_(rider.m_20182_()) > MAX_ROPE_DISTANCE_SQR) {
                    continue;
                }
                drawRope(pose, buf, camera, maid, rider);
            }
        } catch (Throwable ignored) {
        } finally {
            pose.m_85849_();
        }
    }

    /** 女仆腰 → 玩家手，两段 + 中点下垂 */
    private static void drawRope(PoseStack pose, VertexConsumer buf, Vec3 camera, Entity maid, Entity rider) {
        double maidAnchorY = maid.m_20186_() + 0.75;
        double handY = rider.m_20186_() + rider.m_20192_() * 0.85;
        Vec3 a = new Vec3(maid.m_20185_(), maidAnchorY, maid.m_20189_()).m_82549_(camera);
        Vec3 b = new Vec3(rider.m_20185_(), handY, rider.m_20189_()).m_82549_(camera);
        Vec3 mid = new Vec3((a.f_82479_ + b.f_82479_) * 0.5,
                (a.f_82480_ + b.f_82480_) * 0.5 - 0.12,
                (a.f_82481_ + b.f_82481_) * 0.5);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, a, mid, R, G, B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, mid, b, R, G, B);
    }
}
