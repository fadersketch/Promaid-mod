package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * v1.3.7 实测六百六十七：武装拴绳的绳子（纯客户端渲染）。
 *
 * 【画什么】"她的二号位枪手"从女仆腰部（脚底 +0.75）到玩家手部（脚底 + 身高×0.85）之间的一根绳。
 * 状态来自服务端 S2C（{@link com.maidsmart.combat.GunnerTetherNetworking} → {@link #onSync}），
 * 客户端自己不判断"这是不是拴绳挂的"。
 *
 * ── 【实测六百七十五：线 → 原版拴绳同款丝带（配色改白）】──
 * 玩家原话："当前的武装拴绳连接视觉效果做的根本就不像个绳子，你用的是一根线。应该套用原版的
 * 拴绳模型的。也就改个配色为白。"
 *
 * <p>旧版走 {@code RenderHelper.renderLine}（原版 {@code RenderType.LINES}，1px 宽的 GL 线、
 * 不带明暗），所以永远是一根没有体积的直线。现在整段几何**逐字照抄原版拴绳**：
 * <ul>
 *   <li><b>宿主类</b>：1.20.1 的原版拴绳渲染在 {@code MobRenderer.m_115461_} +
 *       {@code m_174307_}（不是 EntityRenderer——javap 实证 1.20.1 的 EntityRenderer 里
 *       根本没有 renderLeash，绳子是 MobRenderer 自己画的）；1.21.1 搬进了
 *       {@code EntityRenderer.renderLeash} + {@code addVertexPair}。两边算法一模一样。</li>
 *   <li><b>管线</b>：原版自己的拴绳渲染类型（1.20.1 {@code RenderType.m_110475_()} /
 *       1.21.1 {@code RenderType.leash()}，{@code POSITION_COLOR_LIGHTMAP} + 自己的 shader），
 *       不再是 LINES。</li>
 *   <li><b>几何</b>：沿绳子分 24 段（原版 {@code MobRenderer.f_174302_} / 1.21.1
 *       {@code LEASH_RENDER_STEPS}），每段写一对顶点：两趟 {@code TRIANGLE_STRIP} 围成一条
 *       0.025 格宽、沿长度**扭着**的丝带——这就是原版拴绳看着像绳子的原因（不是它更粗，
 *       而是那层明暗交错的编织感）。竖直方向还有原版那条二次曲线（{@code dy>0 ? dy*f² :
 *       dy - dy*(1-f)²}），所以两端之间是"绷着的绳"而不是直线。</li>
 *   <li><b>配色</b>：原版把颜色写死在顶点里：{@code (0.5, 0.4, 0.3) × (隔一段 0.7 / 1.0)}（棕绳）。
 *       按玩家要求**只改配色**：三个通道都换成 1.0（白）——那层 0.7/1.0 的编织明暗原样保留，
 *       否则绳子又会变回一根没有质感的直线。</li>
 *   <li><b>光照</b>：每段顶点按两端各自的方块光/天空光插值（同原版
 *       {@code LightTexture.pack(block, sky)}），所以在阴影里是暗的、在太阳下是亮的。</li>
 * </ul>
 * 锚点仍是"她的腰 → 他的手"（原版拴绳的两端是"实体拴点 → 拴绳持有者"），因为这里拴的不是
 * 原版拴绳、而是我们自己的挂载关系。
 *
 * 【实测六百七十三 / 六百七十四 两处修正】
 * <ul>
 *   <li>牵绳档（空袭的女仆还没起飞、玩家在地面牵着她走）里玩家**不是**她的乘客，
 *       而那正是最需要看到绳子的时候——旧版要求"必须是她乘客"，于是这一档什么都看不到；
 *       现在由服务端相位（{@link com.maidsmart.combat.GunnerTetherManager#ST_LEASH}）说了算。</li>
 *   <li>相位每变一次服务端重发一次包（挂载 / 起飞翻档 / 落地回牵绳 / 解除），
 *       所以绳子不会留在过期状态上。</li>
 * </ul>
 *
 * 【生命周期】退出世界清空（{@link #clear()}）；对端实体不在 / 悬挂档已下鞍（原版乘客同步已断）
 * 则跳过绘制，等下一次相位包修正。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class GunnerTetherClient {

    private static boolean registered = false;
    /** 跳过阈值：两端离得比这还远 = 状态已过期（等解除包），不再硬画 */
    private static final double MAX_ROPE_DISTANCE_SQR = 64.0;

    /* ---------------- 原版拴绳的几何常数（见类注释） ---------------- */

    /** 整根绳分多少段：原版 {@code MobRenderer.f_174302_} = 24 */
    private static final int ROPE_STEPS = 24;
    /** 丝带宽度（格）：原版两版都是 0.025 */
    private static final float ROPE_WIDTH = 0.025f;
    /** 绳色（实测六百七十五：白）。原版写死的是 (0.5, 0.4, 0.3) */
    private static final float ROPE_R = 1.0f;
    private static final float ROPE_G = 1.0f;
    private static final float ROPE_B = 1.0f;
    /** 原版那层"隔一段暗一档"的编织明暗：暗档 */
    private static final float ROPE_SHADE_DARK = 0.7f;

    private GunnerTetherClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(GunnerTetherClient.class);
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
        try {
            MultiBufferSource buffers = mc.m_91269_().m_110104_();
            for (Map.Entry<Integer, Integer> e : com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.entrySet()) {
                Entity maid = mc.f_91073_.m_6815_(e.getKey());
                Entity rider = mc.f_91073_.m_6815_(e.getValue());
                if (!(maid instanceof EntityMaid) || !(rider instanceof Player)) {
                    continue;
                }
                // 【实测六百七十三 / 六百七十四】牵绳档玩家不是乘客（那正是最该看到绳子的时候）；
                //  悬挂档必须是乘客（不是 = 原版乘客同步已断，等下一次相位包修正）。
                //  判据来自服务端相位，不猜；解绑/翻档都会重发包，所以不会留残影，
                //  下面那道 8 格距离判据本身也把过期状态挡在外面。
                boolean leash = com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.contains(e.getKey());
                if (!leash && !maid.m_20363_(rider)) {
                    continue;
                }
                if (maid.m_20238_(rider.m_20182_()) > MAX_ROPE_DISTANCE_SQR) {
                    continue;
                }
                drawRope(pose, buffers, camera, maid, rider);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 绳子几何（照原版拴绳：24 段 × 两趟三角带） ---------------- */

    /**
     * 她的腰 → 他的手，画一根原版拴绳同款的丝带（几何与配色见类注释）。
     *
     * <p>坐标系：这个阶段（AFTER_TRANSLUCENT_BLOCKS）的 PoseStack 原点是**相机**，所以先把
     * 原点平移到绳子的起点（相对相机），顶点坐标就写成相对起点的差值——与旧版把两端都减掉
     * 相机位置是同一件事，只是这样能做原版那套"以起点为原点"的几何。
     */
    private static void drawRope(PoseStack pose, MultiBufferSource buffers, Vec3 camera, Entity maid, Entity rider) {
        Vec3 a = new Vec3(maid.m_20185_(), maid.m_20186_() + 0.75, maid.m_20189_());
        Vec3 b = new Vec3(rider.m_20185_(), rider.m_20186_() + rider.m_20192_() * 0.85, rider.m_20189_());
        float dx = (float) (b.f_82479_ - a.f_82479_);
        float dy = (float) (b.f_82480_ - a.f_82480_);
        float dz = (float) (b.f_82481_ - a.f_82481_);
        pose.m_85836_();
        try {
            pose.m_85837_(a.f_82479_ - camera.f_82479_, a.f_82480_ - camera.f_82480_,
                    a.f_82481_ - camera.f_82481_);
            VertexConsumer vc = buffers.m_6299_(RenderType.m_110475_());
            Matrix4f mat = pose.m_85850_().m_252922_();
            // 原版：把丝带的宽度摊到"水平垂直方向"上（invSqrt(水平长度) × 宽/2）
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            float w = len < 1.0E-4f ? 0.0f : (1.0f / len) * ROPE_WIDTH / 2.0f;
            float ox = dz * w;
            float oz = dx * w;
            int blockA = brightness(maid, a, true);
            int blockB = brightness(rider, b, true);
            int skyA = brightness(maid, a, false);
            int skyB = brightness(rider, b, false);
            for (int i = 0; i <= ROPE_STEPS; i++) {
                addVertexPair(vc, mat, dx, dy, dz, blockA, blockB, skyA, skyB,
                        ROPE_WIDTH, ROPE_WIDTH, ox, oz, i, false);
            }
            for (int i = ROPE_STEPS; i >= 0; i--) {
                addVertexPair(vc, mat, dx, dy, dz, blockA, blockB, skyA, skyB,
                        ROPE_WIDTH, 0.0f, ox, oz, i, true);
            }
        } catch (Throwable ignored) {
        } finally {
            pose.m_85849_();
        }
    }

    /**
     * 原版 {@code MobRenderer.m_174307_} 的逐字搬运（只改了颜色与光照来源）：
     * 第 {@code step} 段的一对顶点。
     *
     * @param h1 第一趟 = 丝带厚（0.025），第二趟 = 0
     * @param h2 第一趟 = 丝带厚（0.025），第二趟 = 0.025（两趟错开半格厚 → 扭着的那条丝带）
     */
    private static void addVertexPair(VertexConsumer vc, Matrix4f mat, float dx, float dy, float dz,
                                      int blockA, int blockB, int skyA, int skyB,
                                      float h1, float h2, float ox, float oz, int step, boolean second) {
        float f = (float) step / (float) ROPE_STEPS;
        int light = LightTexture.m_109885_(lerp(f, blockA, blockB), lerp(f, skyA, skyB));
        float shade = step % 2 == (second ? 1 : 0) ? ROPE_SHADE_DARK : 1.0f;
        float r = ROPE_R * shade;
        float g = ROPE_G * shade;
        float bl = ROPE_B * shade;
        float x = dx * f;
        // 原版那条"绷着的绳"：两端之间不是直线，往上够是 f²、往下垂是 1-(1-f)²
        float y = dy > 0.0f ? dy * f * f : dy - dy * (1.0f - f) * (1.0f - f);
        float z = dz * f;
        vc.m_252986_(mat, x - ox, y + h1, z + oz).m_85950_(r, g, bl, 1.0f).m_85969_(light).m_5752_();
        vc.m_252986_(mat, x + ox, y + h2 - h1, z - oz).m_85950_(r, g, bl, 1.0f).m_85969_(light).m_5752_();
    }

    /** 某个世界坐标处的光照（block=true 取方块光、false 取天空光）——同原版拴绳两端各取一份 */
    private static int brightness(Entity entity, Vec3 p, boolean block) {
        try {
            net.minecraft.world.level.Level lvl = entity.m_9236_();
            BlockPos pos = new BlockPos((int) Math.floor(p.f_82479_), (int) Math.floor(p.f_82480_),
                    (int) Math.floor(p.f_82481_));
            return lvl.m_45517_(block ? LightLayer.BLOCK : LightLayer.SKY, pos);
        } catch (Throwable t) {
            return 15;
        }
    }

    /** 原版 {@code Mth.lerp(f, a, b)} 的整数版（光照插值用） */
    private static int lerp(float f, int a, int b) {
        return (int) (a + (b - a) * f);
    }
}
