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
 * v1.3.7 实测六百六十七：武装拴绳的绳子（纯客户端渲染，1.21.1 NeoForge 版）。
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
 * 【实测六百七十三 / 六百七十四 两处修正】
 * <ul>
 *   <li>牵绳档（空袭的女仆还没起飞、玩家在地面牵着她走）里玩家**不是**她的乘客，
 *       而那正是最需要看到绳子的时候——旧版要求"必须是她乘客"，于是这一档什么都看不到
 *       （forge 树六百七十三 已改，1.21.1 树漏了，本批补齐）；现在由服务端相位
 *       （{@link com.maidsmart.combat.GunnerTetherManager#ST_LEASH}）说了算。</li>
 *   <li>相位每变一次服务端重发一次包（挂载 / 起飞翻档 / 落地回牵绳 / 解除），
 *       所以绳子不会留在过期状态上。</li>
 * </ul>
 *
 * 【生命周期】退出世界清空（{@link #clear()}）；对端实体不在 / 悬挂档已下鞍（原版乘客同步已断）
 * 则跳过绘制，等下一次相位包修正。
 */
@net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
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
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(GunnerTetherClient.class);
        }
    }

    /* ---------------- 服务端状态入口（由 GunnerTetherNetworking 调用） ---------------- */

    /** 相位同步（{@code state} 见 {@code GunnerTetherManager.ST_*}；riderId &lt; 0 = 没挂） */
    public static void onSync(int maidId, int riderId, int state) {
        ensureRegistered();
        if (riderId < 0) {
            com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.remove(maidId);
            com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.remove(maidId);
        } else {
            com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.put(maidId, riderId);
            if (state == com.maidsmart.combat.GunnerTetherManager.ST_LEASH) {
                com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.add(maidId);
            } else {
                com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.remove(maidId);
            }
        }
    }

    public static void clear() {
        com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.clear();
        com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.clear();
    }

    /* ---------------- 事件 ---------------- */

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRender(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.isEmpty()) {
            return;
        }
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TETHER_ENABLE.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(
                net.minecraft.client.renderer.RenderType.LINES);
        pose.pushPose();
        try {
            for (Map.Entry<Integer, Integer> e : com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.entrySet()) {
                Entity maid = mc.level.getEntity(e.getKey());
                Entity rider = mc.level.getEntity(e.getValue());
                if (!(maid instanceof EntityMaid) || !(rider instanceof Player)) {
                    continue;
                }
                // 【实测六百七十三 / 六百七十四】牵绳档玩家不是乘客（那正是最该看到绳子的时候）；
                //  悬挂档必须是乘客（不是 = 原版乘客同步已断，等下一次相位包修正）。
                //  判据来自服务端相位，不猜；解绑/翻档都会重发包，所以不会留残影，
                //  下面那道 8 格距离判据本身也把过期状态挡在外面。
                boolean leash = com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.contains(e.getKey());
                if (!leash && !maid.hasPassenger(rider)) {
                    continue;
                }
                if (maid.distanceToSqr(rider.position()) > MAX_ROPE_DISTANCE_SQR) {
                    continue;
                }
                drawRope(pose, buf, camera, maid, rider);
            }
        } catch (Throwable ignored) {
        } finally {
            pose.popPose();
        }
    }

    /** 女仆腰 → 玩家手，两段 + 中点下垂 */
    private static void drawRope(PoseStack pose, VertexConsumer buf, Vec3 camera, Entity maid, Entity rider) {
        double maidAnchorY = maid.getY() + 0.75;
        double handY = rider.getY() + rider.getBbHeight() * 0.85;
        Vec3 a = new Vec3(maid.getX(), maidAnchorY, maid.getZ()).subtract(camera);
        Vec3 b = new Vec3(rider.getX(), handY, rider.getZ()).subtract(camera);
        Vec3 mid = new Vec3((a.x + b.x) * 0.5,
                (a.y + b.y) * 0.5 - 0.12,
                (a.z + b.z) * 0.5);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, a, mid, R, G, B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, mid, b, R, G, B);
    }
}
