package com.maidsmart.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 女仆放置方块的金色描边（纯客户端渲染，实测一千〇六）。
 * 服务端 {@code PlacedMarkNetworking} 发"哪些格要描边、预期是哪块方块"（进服全量 + 逐批增删），
 * 这里维护位置表并按帧画金色线框；退出世界清空（静态表不能跨世界残留）。
 * <p>生命周期与方块硬绑定：每帧比对"当前方块 id"与"登记时 id"。方块没了（空气）或被换成别的
 * 方块 → 这一帧立刻不画；方块还在 → 照画。这里【只隐藏、不删条目】——客户端看到的一帧可能是
 * 预测/回滚，真正删条目由服务端撤框包决定，避免"方块还在、框却永久没了"。
 * 渲染管线与 {@code IndexStonePreviewClient} 同款（AFTER_TRANSLUCENT_BLOCKS + TLM
 * RenderHelper.renderLine + 相机相对坐标）。只画棱线不画填充（口径就是"描边"）；
 * 96 格内、单帧最多 {@code MAX_DRAW} 格，帧开销有界。
 */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class PlacedMarkClient {

    /** 描边格（打包坐标 → 服务端登记时的方块注册名；空串 = 旧协议/未知） */
    private static final Map<Long, String> POS = new HashMap<>();
    /** 单帧最多画的格数（防极端存量把帧率拖死） */
    private static final int MAX_DRAW = 4096;
    /** 只画这个距离内的（格²；96 格） */
    private static final double DRAW_DIST_SQ = 96.0 * 96.0;

    private static boolean registered = false;
    /** 实测一千〇六·续三：链路诊断——首批增量到没到（进世界/换维度时复位） */
    private static boolean loggedFirstIncr = false;
    /** 实测一千〇六·续三：链路诊断——首帧 + 每 30 秒一条"在画"心跳（进世界/换维度时复位） */
    private static boolean loggedFirstDraw = false;
    private static long lastDrawLogTick = 0L;
    /** 客户端跳过"已消失/已替换"描边的日志节流 */
    private static long lastSkipLogTick = 0L;

    private PlacedMarkClient() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(PlacedMarkClient.class);
        }
    }

    /** 坐标打包（自用格式，NEO 镜像同款；避免触碰 BlockPos 的映射名） */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long v) {
        return (int) (v >> 38);
    }

    private static int unpackY(long v) {
        // 实测一千〇六·续四：12 位【有符号】还原（-2048..2047）。旧版按无符号取——
        // 负 Y 解成 4055+，距离闸把整张表跳过，金框永远画不出来（实机日志实证）。
        int y = (int) ((v >> 26) & 0xFFF);
        return y >= 0x800 ? y - 0x1000 : y;
    }

    private static int unpackZ(long v) {
        // 实测一千〇六·续四：26 位【有符号】还原。旧版按无符号取——负 Z 解成 67108823+（同上）。
        int z = (int) (v & 0x3FFFFFF);
        return z >= 0x2000000 ? z - 0x4000000 : z;
    }

    /* ---------------- 服务端消息入口（由 PlacedMarkNetworking 调用） ---------------- */

    public static void apply(boolean full, List<int[]> add, List<String> addIds, List<int[]> remove) {
        ensureRegistered();
        if (full) {
            POS.clear();
            loggedFirstIncr = false;
            com.maidsmart.tool.PromaidLog.log("描边", "客户端收到全量：清底后 "
                    + (add == null ? 0 : add.size()) + " 格");
        } else if (add != null && !add.isEmpty() && !loggedFirstIncr) {
            loggedFirstIncr = true;
            int[] c = add.get(0);
            com.maidsmart.tool.PromaidLog.log("描边", "客户端收到首批增量：+" + add.size()
                    + " 格 @(" + c[0] + "," + c[1] + "," + c[2] + ")");
        }
        if (add != null) {
            for (int i = 0; i < add.size(); i++) {
                int[] c = add.get(i);
                String id = addIds != null && i < addIds.size() ? addIds.get(i) : "";
                POS.put(pack(c[0], c[1], c[2]), id == null ? "" : id);
            }
        }
        if (remove != null) {
            for (int[] c : remove) {
                POS.remove(pack(c[0], c[1], c[2]));
            }
        }
    }

    public static void clear() {
        POS.clear();
        loggedFirstIncr = false;
        loggedFirstDraw = false;
        lastDrawLogTick = 0L;
        lastSkipLogTick = 0L;
    }

    /* ---------------- 事件 ---------------- */

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRender(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (POS.isEmpty()) {
            return;
        }
        if (event.getStage()
                != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!com.maidsmart.config.MaidSmartConfig.MISC_PLACED_GOLD_MARK.get()) {
            return;
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91074_ == null || mc.f_91073_ == null) {
            return;
        }
        // camera = -camPos（同指标石预览口径：camera.add(世界坐标) = 相机相对坐标）
        Vec3 camera = event.getCamera().m_90583_().m_82548_();
        PoseStack pose = event.getPoseStack();
        pose.m_85836_();
        try {
            VertexConsumer buf = mc.m_91269_().m_110104_()
                    .m_6299_(net.minecraft.client.renderer.RenderType.f_110371_);
            int drawn = 0;
            int skipped = 0;
            for (Map.Entry<Long, String> entry : POS.entrySet()) {
                if (drawn >= MAX_DRAW) {
                    break;
                }
                long v = entry.getKey();
                int x = unpackX(v);
                int y = unpackY(v);
                int z = unpackZ(v);
                double dx = x + 0.5 - mc.f_91074_.m_20185_();
                double dy = y + 0.5 - mc.f_91074_.m_20186_();
                double dz = z + 0.5 - mc.f_91074_.m_20189_();
                if (dx * dx + dy * dy + dz * dz > DRAW_DIST_SQ) {
                    continue;
                }
                // 同生死：当前方块必须还在，且还是登记时那块。没了/换了 → 本帧不画。
                // 只隐藏不删条目：服务端撤框包才是唯一删除来源，避免预测帧把框永久抹掉。
                BlockPos bp = new BlockPos(x, y, z);
                if (mc.f_91073_.m_46805_(bp)) {
                    BlockState state = mc.f_91073_.m_8055_(bp);
                    if (state.m_60795_()) {
                        skipped++;
                        continue;
                    }
                    String expected = entry.getValue();
                    if (expected != null && !expected.isEmpty()) {
                        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
                        if (key == null || !expected.equals(key.toString())) {
                            skipped++;
                            continue;
                        }
                    }
                }
                drawGoldBox(pose, buf, camera, x, y, z);
                drawn++;
            }
            if (skipped > 0) {
                long now = mc.f_91073_.m_46467_();
                if (now - lastSkipLogTick >= 40L) {
                    lastSkipLogTick = now;
                    com.maidsmart.tool.PromaidLog.log("描边", "客户端跳过已消失/已替换的描边 "
                            + skipped + " 格（等待服务端撤框）");
                }
            }
            if (drawn > 0) {
                long now = mc.f_91073_.m_46467_();
                if (!loggedFirstDraw || now - lastDrawLogTick >= 600L) {
                    loggedFirstDraw = true;
                    lastDrawLogTick = now;
                    com.maidsmart.tool.PromaidLog.log("描边", "客户端在画描边：表内 " + POS.size()
                            + " 格，视野内 " + drawn + " 格");
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkClient", ignored);
        } finally {
            pose.m_85849_();
        }
    }

    /** 金色（描边专用；比粉标更"实"——这是可交互提示） */
    private static final float GOLD_R = 1.0f;
    private static final float GOLD_G = 0.80f;
    private static final float GOLD_B = 0.20f;

    /** 一个方块的金色线框（12 条棱；相机相对坐标同指标石预览） */
    private static void drawGoldBox(PoseStack pose, VertexConsumer buf, Vec3 camera,
                                    int bx, int by, int bz) {
        double x0 = bx, y0 = by, z0 = bz;
        double x1 = bx + 1.0, y1 = by + 1.0, z1 = bz + 1.0;
        Vec3 c0 = camera.m_82520_(x0, y0, z0);
        Vec3 c1 = camera.m_82520_(x1, y0, z0);
        Vec3 c2 = camera.m_82520_(x1, y0, z1);
        Vec3 c3 = camera.m_82520_(x0, y0, z1);
        Vec3 t0 = camera.m_82520_(x0, y1, z0);
        Vec3 t1 = camera.m_82520_(x1, y1, z0);
        Vec3 t2 = camera.m_82520_(x1, y1, z1);
        Vec3 t3 = camera.m_82520_(x0, y1, z1);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, c1, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, c2, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, c3, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, c0, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t0, t1, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t1, t2, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t2, t3, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, t3, t0, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c0, t0, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c1, t1, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c2, t2, GOLD_R, GOLD_G, GOLD_B);
        com.github.tartaricacid.touhoulittlemaid.util.RenderHelper.renderLine(pose, buf, c3, t3, GOLD_R, GOLD_G, GOLD_B);
    }
}
