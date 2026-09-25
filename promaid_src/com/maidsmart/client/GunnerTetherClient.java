package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
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
 *       1.21.1 {@code RenderType.leash()}，{@code POSITION_COLOR_LIGHTMAP} + 自己的 shader +
 *       {@code NO_CULL}——正因如此原版那两趟反向绕序的带子才都能看见），不再是 LINES。</li>
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
 * ── 【实测六百七十六：绳子"隔远就消失"、"还是没有拉扯感" 三条根因】──
 * 玩家原话："拴绳隔得太远，渲染模型就消失了，而且它没有拴绳特有的物理拉扯效果。"
 * 三条都对着反编译源码逐字核过（1.21.1 {@code EntityRenderer.renderLeash} / 1.20.1
 * {@code MobRenderer.m_115461_}，两版逐字相同）：
 * <ol>
 *   <li><b>"隔远就消失" = 我们自己那道 8 格闸门</b>。旧版 {@code MAX_ROPE_DISTANCE_SQR = 64.0}
 *       （= 8 格）本意是"状态过期了别硬画"，但它把**牵绳档**一起挡掉了——那一档她（空袭模式还没
 *       起飞）跟在主人身后走，跟随距离 6 格，加上高度差、加上她绕过障碍时的一顿，很容易就过 8 格，
 *       而那正是最该看到绳子的时候。现在放宽到 {@link #STALE_STATE_SQR}（64 格），闸门只管
 *       "这对挂载是不是已经过期"，不再参与"多近才画"。</li>
 *   <li><b>两端各跳各的 = 没插值</b>。原版两端都走 {@code Mth.lerp(partialTick, xo, getX())}
 *       ——按帧插值；旧版直接读 {@code getX()}，于是绳子只有 20 Hz 的台阶，而她本人是按帧插值
 *       渲染的：绳子会被"钉"在她上一 tick 的位置上一格格地抽，看着就是根硬棍。现在两端都按帧插值。</li>
 *   <li><b>"直上直下"时原版公式是 0/0</b>——这条才是"仍然不像绳子"的真正原因。原版那层丝带的
 *       <b>宽度方向</b>是 {@code (dz, dx)} 除以**水平投影长度**（{@code Mth.invSqrt(dx²+dz²)}），
 *       而那个除法与"单位化"正好抵消，所以原版丝带的宽度恒为 0.025、方向恒为"绳子水平投影的垂线"。
 *       可我们的绳子是**玩家正吊在她正下方**：两端 X/Z 逐字相等（{@code seatOffset} 悬挂档水平偏移
 *       为 0）→ 水平投影 = 0 → 方向算不出来、宽度也摊不出去 → 24 段全叠成一根竖线。
 *       原版拴绳永远不会遇到这一档（被拴的生物总在你旁边晃），所以我们补上原版没有的那一档：
 *       近垂直（水平投影 &lt; {@link #ROPE_MIN_HORIZONTAL}）时改用<b>三片 0°/60°/120° 围着绳轴
 *       一圈</b>的带子——任何水平角度看都至少有一片是侧对着你的，看上去就是一条圆绳。</li>
 * </ol>
 * <p><b>"拉扯感"这一条必须额外加一层</b>：原版那条垂坠（{@code dy>0 ? dy*f² : dy - dy*(1-f)²}）
 * 只在 <b>Y</b> 上弯，而"直上直下"的绳子里 Y 就是绳自己的方向——弯了也看不出来；再加上两端是
 * **刚性连接**（她在飞、玩家跟着她，相对位置每 tick 被服务端钉死），这根绳子本身确实不会有任何
 * 形变。所以这里额外加一层<b>拖曳弯曲</b>：按她本帧的水平位移把绳子中段朝反方向拽出去
 * （{@link #ROPE_DRAG_K}，两端仍严格钉在两个锚点上，用 {@code 4f(1-f)} 包络让峰值落在中点，
 * 再做帧间平滑与限幅）。效果：她加速/转向时绳子被拖在后面，悬停时自己收直。
 * 这不是原版的东西、是为了"像绳子"刻意加的，独立一层，一眼能认出来。
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
    /** 跳过阈值：两端离得比这还远 = 状态已过期（等解除包），不再硬画。
     *  【实测六百七十六】旧值 64.0 = 8 格，把"牵绳档她跟在后面走"也一起挡了（见类注释）→ 64 格。 */
    private static final double STALE_STATE_SQR = 4096.0;

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
    /** 【实测六百七十六】水平投影短于这个（格）就按"直上直下"处理：改用三片围绳轴的带子 */
    private static final float ROPE_MIN_HORIZONTAL = 0.15f;
    /** 【实测六百七十六】拖曳弯曲：她的水平位移 × 这个系数 = 中段被拽出去的格数 */
    private static final float ROPE_DRAG_K = 1.0f;
    /** 拖曳弯曲上限（格）：再快也不让它比绳长还夸张 */
    private static final float ROPE_DRAG_MAX = 0.8f;
    /** 拖曳弯曲的帧间平滑（0~1，越小越"软"） */
    private static final float ROPE_DRAG_SMOOTH = 0.25f;

    /** 每根绳子的拖曳偏移（女仆实体 id → {x,z}）；她不在挂载表里就清掉 */
    private static final Map<Integer, float[]> DRAG = new HashMap<>();

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
        DRAG.clear();
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
        float pt = event.getPartialTick();
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
                //  下面那道过期判据本身也把过期状态挡在外面。
                boolean leash = com.maidsmart.combat.GunnerTetherManager.SYNCED_LEASH.contains(e.getKey());
                if (!leash && !maid.m_20363_(rider)) {
                    continue;
                }
                if (maid.m_20238_(rider.m_20182_()) > STALE_STATE_SQR) {
                    continue;
                }
                drawRope(pose, buffers, camera, maid, rider, pt);
            }
            // 已经不在挂载表里的拖曳状态一并清掉（别按实体 id 一直囤）
            if (DRAG.size() > com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.size()) {
                DRAG.keySet().removeIf(id -> !com.maidsmart.combat.GunnerTetherManager.SYNCED_PAIRS.containsKey(id));
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
     *
     * <p>【实测六百七十六】两端都按帧插值（{@code Mth.lerp(partialTick, xo, getX())}，同原版），
     * 宽度方向与拖曳弯曲见类注释那三条根因。
     */
    private static void drawRope(PoseStack pose, MultiBufferSource buffers, Vec3 camera,
                                Entity maid, Entity rider, float pt) {
        Vec3 a = new Vec3(lerp(maid.f_19854_, maid.m_20185_(), pt),
                lerp(maid.f_19855_, maid.m_20186_(), pt) + 0.75,
                lerp(maid.f_19856_, maid.m_20189_(), pt));
        Vec3 b = new Vec3(lerp(rider.f_19854_, rider.m_20185_(), pt),
                lerp(rider.f_19855_, rider.m_20186_(), pt) + rider.m_20192_() * 0.85,
                lerp(rider.f_19856_, rider.m_20189_(), pt));
        float dx = (float) (b.f_82479_ - a.f_82479_);
        float dy = (float) (b.f_82480_ - a.f_82480_);
        float dz = (float) (b.f_82481_ - a.f_82481_);
        float[] drag = dragFor(maid);
        pose.m_85836_();
        try {
            pose.m_85837_(a.f_82479_ - camera.f_82479_, a.f_82480_ - camera.f_82480_,
                    a.f_82481_ - camera.f_82481_);
            VertexConsumer vc = buffers.m_6299_(RenderType.m_110475_());
            Matrix4f mat = pose.m_85850_().m_252922_();
            int blockA = brightness(maid, a, true);
            int blockB = brightness(rider, b, true);
            int skyA = brightness(maid, a, false);
            int skyB = brightness(rider, b, false);
            // 宽度方向：水平投影够长 → 原版口径（其垂线，单位向量）；近 vertical → 三片围绳轴一圈
            double hl = Math.sqrt((double) dx * dx + (double) dz * dz);
            boolean upright = hl < (double) ROPE_MIN_HORIZONTAL;
            int planes = upright ? 3 : 1;
            float baseX = upright ? 1.0f : (float) (dz / hl);
            float baseZ = upright ? 0.0f : (float) (dx / hl);
            float half = ROPE_WIDTH / 2.0f;
            for (int p = 0; p < planes; p++) {
                double ang = (Math.PI / 3.0) * p;
                float cs = (float) Math.cos(ang);
                float sn = (float) Math.sin(ang);
                float ox = (baseX * cs - baseZ * sn) * half;
                float oz = (baseX * sn + baseZ * cs) * half;
                for (int i = 0; i <= ROPE_STEPS; i++) {
                    addVertexPair(vc, mat, dx, dy, dz, blockA, blockB, skyA, skyB,
                            ROPE_WIDTH, ROPE_WIDTH, ox, oz, drag, i, false);
                }
                for (int i = ROPE_STEPS; i >= 0; i--) {
                    addVertexPair(vc, mat, dx, dy, dz, blockA, blockB, skyA, skyB,
                            ROPE_WIDTH, 0.0f, ox, oz, drag, i, true);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            pose.m_85849_();
        }
    }

    /**
     * 原版 {@code MobRenderer.m_174307_} 的逐字搬运（只改了颜色、加了拖曳弯曲）：
     * 第 {@code step} 段的一对顶点。
     *
     * @param h1 第一趟 = 丝带厚（0.025），第二趟 = 0
     * @param h2 第一趟 = 丝带厚（0.025），第二趟 = 0.025（两趟错开半格厚 → 扭着的那条丝带）
     * @param drag 拖曳偏移 {x,z}：按 {@code 4f(1-f)} 包络叠在中段上，两端严格为 0
     */
    private static void addVertexPair(VertexConsumer vc, Matrix4f mat, float dx, float dy, float dz,
                                      int blockA, int blockB, int skyA, int skyB,
                                      float h1, float h2, float ox, float oz, float[] drag,
                                      int step, boolean second) {
        float f = (float) step / (float) ROPE_STEPS;
        int light = LightTexture.m_109885_(lerpInt(f, blockA, blockB), lerpInt(f, skyA, skyB));
        float shade = step % 2 == (second ? 1 : 0) ? ROPE_SHADE_DARK : 1.0f;
        float r = ROPE_R * shade;
        float g = ROPE_G * shade;
        float bl = ROPE_B * shade;
        float bow = 4.0f * f * (1.0f - f);
        float x = dx * f + drag[0] * bow;
        // 原版那条"绷着的绳"：两端之间不是直线，往上够是 f²、往下垂是 1-(1-f)²
        float y = dy > 0.0f ? dy * f * f : dy - dy * (1.0f - f) * (1.0f - f);
        float z = dz * f + drag[1] * bow;
        // 原版两个顶点的竖直错开顺序：v1 = y + h2、v2 = y + h1 - h2（实测六百七十六 对着反编译源码核过）
        vc.m_252986_(mat, x - ox, y + h2, z + oz).m_85950_(r, g, bl, 1.0f).m_85969_(light).m_5752_();
        vc.m_252986_(mat, x + ox, y + h1 - h2, z - oz).m_85950_(r, g, bl, 1.0f).m_85969_(light).m_5752_();
    }

    /**
     * 【实测六百七十六】她这一帧的水平速度 → 绳子中段的拖曳偏移，帧间平滑 + 限幅。
     *
     * <p>目标值 = {@code (xo - getX(), zo - getZ()) × ROPE_DRAG_K}：她朝哪边走，绳子中段就被拖到
     * 反方向去（同"绳被拽着走"）。她悬停时目标归零，绳子靠平滑自己收直。
     */
    private static float[] dragFor(Entity maid) {
        int id = maid.m_19879_();
        float[] cur = DRAG.get(id);
        if (cur == null) {
            cur = new float[] {0.0f, 0.0f};
            DRAG.put(id, cur);
        }
        float tx = clamp((float) (maid.f_19854_ - maid.m_20185_()) * ROPE_DRAG_K, ROPE_DRAG_MAX);
        float tz = clamp((float) (maid.f_19856_ - maid.m_20189_()) * ROPE_DRAG_K, ROPE_DRAG_MAX);
        cur[0] += (tx - cur[0]) * ROPE_DRAG_SMOOTH;
        cur[1] += (tz - cur[1]) * ROPE_DRAG_SMOOTH;
        return cur;
    }

    private static float clamp(float v, float lim) {
        return v > lim ? lim : (v < -lim ? -lim : v);
    }

    /** 原版 {@code Mth.lerp(partialTick, o, now)}（按帧插值：绳子的两端要跟渲染位置对得上） */
    private static double lerp(double from, double to, float pt) {
        return Mth.m_14139_((double) pt, from, to);
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
    private static int lerpInt(float f, int a, int b) {
        return (int) (a + (b - a) * f);
    }
}
