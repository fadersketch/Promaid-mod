package com.maidsmart.accessory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * v1.5.394 实测八百零五【穿墙锁敌】：女仆佩戴「大运」后，能不能隔着**可破坏的**墙锁定敌人。
 *
 * <p>【需求原文】"玩家可以配置女仆是否可以在佩戴大运饰品后穿墙锁敌（无法破坏的不穿墙锁敌）"。
 *
 * <p>【判据】要穿过一整条视线，路径上**每一个**挡路的方块都必须是
 * {@link DaYunBreaker#canPhaseThrough} 认的"撞得开"——也就是「大运」飞行时真的会清掉的那些：
 * 硬度在 {@code [0, 上限)} 之间、且（开着「跳过容器」时）不带方块实体。
 * 只要中间夹了一个撞不开的（基岩、屏障、黑曜石、自家箱子），这条视线就**仍然不算数**。
 *
 * <p>【为什么不是"整条线全查一遍硬度"】那样会把"薄墙正好落在采样点之间"漏掉，或者反过来
 * 把墙后的方块也算进来。这里走**迭代 raycast**：每次取第一个撞到的方块，能穿就从中穿过去
 * 继续找下一个，直到命中目标（MISS）。命中即"全通了"，撞到穿不过的即"不通"——与游戏里
 * 光线实际怎么走一模一样。
 *
 * <p>【谁会被影响】本类只从 {@code SelfPreservationBehavior.hasSight} 的**实体版**进来
 * （那里是全局唯一的视线收口），所以索敌（50 格空袭扫描 / 威胁登记 / 自保反击）统一放宽，
 * 而飞行跟随的**坐标版**视线（{@code hasSight(from, Vec3)}）不受影响——那道门禁是防
 * "贴着山壁顶"的，与锁敌无关，不该跟着放宽。
 */
public final class DaYunWallPhase {

    /** 视线起点/终点在实体身上的抬升（与 {@code SelfPreservationBehavior.hasSight} 逐字一致）。 */
    private static final double EYE_LIFT = 1.2D;

    /** 离开一个格子之后再多走这么远，避免下一步又贴着同一面墙起头。 */
    private static final double PASS_EPSILON = 0.06D;

    /** 迭代上限：正常视线顶多穿过十来个方块，给到 64 足够，也防着病态情况下的死循环。 */
    private static final int MAX_PASSES = 64;

    private DaYunWallPhase() {
    }

    /**
     * 她此刻"可以隔墙看"吗——配置开着，且她戴着「大运」并且在飞（空袭或真滑翔）。
     *
     * <p>为什么要求"在飞"：「大运」的清块只在滑翔时发生（{@link DaYunBreaker#sweep} 自己
     * 也判 {@code m_21255_}）。她要是不在飞还隔墙锁人，就会"锁得到却过不去、也铲不开"，
     * 那不叫穿墙锁敌，那叫卡住。所以口径与破坏一致：戴着 + 在飞才算数。
     */
    public static boolean active(LivingEntity from) {
        try {
            if (!(from instanceof EntityMaid maid) || !maid.m_6084_()) {
                return false;
            }
            if (!com.maidsmart.config.MaidSmartConfig.DAYUN_WALL_PHASE.get()) {
                return false;
            }
            if (!maid.m_21255_() && !DaYunBauble.isAirRaidTask(maid)) {
                return false;
            }
            return wearsDaYun(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunWallPhase", ignored);
            return false;
        }
    }

    /**
     * 隔着可破坏的墙也算"看得见"吗。
     *
     * <p>调用点保证此时"直线视线已经被挡"（{@code hasSight} 返回了 false），所以这里只做一件事：
     * 沿视线逐个撞过去，确认每一个挡路的方块都能被「大运」撞开。
     *
     * @param from 看得人（女仆）
     * @param to   目标中心点
     * @return true = 路上全是撞得开的方块（可以隔墙锁）；false = 有撞不开的挡着
     */
    public static boolean canSeeThrough(LivingEntity from, Vec3 to) {
        try {
            Level level = from.m_9236_();
            if (level == null) {
                return false;
            }
            float maxHardness = com.maidsmart.config.MaidSmartConfig.DAYUN_MAX_HARDNESS.get().floatValue();
            boolean skipContainers = com.maidsmart.config.MaidSmartConfig.DAYUN_SKIP_CONTAINERS.get();

            Vec3 eye = from.m_20182_().m_82520_(0.0, EYE_LIFT, 0.0);
            Vec3 end = to.m_82520_(0.0, EYE_LIFT, 0.0);
            Vec3 cursor = eye;
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                BlockHitResult hit = level.m_45547_(new ClipContext(
                        cursor, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from));
                if (hit.m_6662_() == HitResult.Type.MISS) {
                    return true; // 打到底都没再撞到东西 → 中间全是能穿的
                }
                BlockPos pos = hit.m_82425_();
                if (!DaYunBreaker.canPhaseThrough(level, pos, maxHardness, skipContainers)) {
                    return false; // 这一格她撞不开（基岩/黑曜石/容器…）
                }
                // 从这一格的**另一侧**继续找下一个挡路的方块。
                // 【为什么要算出口而不是"往前挪一点点"】只挪一个固定小步的话，遇到薄板/台阶这种
                // 碰撞箱很薄、而光线几乎与其平行的情况，下一步会又撞回同一格，一路空转到迭代上限
                // 被当成"不通"——功能会在这种角落里悄悄失效。这里按 1×1×1 的整格算光线真正离开
                // 那一格的位置（取三轴里最小的出口参数），再往后多走一点，保证彻底脱离该格。
                Vec3 direction = end.m_82546_(cursor);
                double length = direction.m_82553_();
                if (length <= 1.0E-6) {
                    return true; // 起点就在终点上，等于没挡
                }
                double tExit = exitParam(cursor, direction, pos);
                cursor = cursor.m_82549_(direction.m_82490_((tExit + PASS_EPSILON) / length));
            }
            return false; // 迭代到顶（不该发生）：保守判不通
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunWallPhase", ignored);
            return false;
        }
    }

    /**
     * 光线从 {@code pos} 那一格（按整格 1×1×1 算）出去时的参数 {@code t}（{@code 1.0} = 终点）。
     *
     * <p>逐轴取"朝那面墙"的交点参数，三轴取最小——就是光线离开这个格子的位置。
     * 某轴方向分量为 0 时那一轴永远不离开（返回 {@code +∞}），所以不会误选它。
     */
    private static double exitParam(Vec3 from, Vec3 direction, BlockPos pos) {
        double t = Double.MAX_VALUE;
        t = Math.min(t, axisExit(from.m_7096_(), direction.m_7096_(), pos.m_123341_()));
        t = Math.min(t, axisExit(from.m_7098_(), direction.m_7098_(), pos.m_123342_()));
        t = Math.min(t, axisExit(from.m_7094_(), direction.m_7094_(), pos.m_123343_()));
        return t == Double.MAX_VALUE ? 0.0D : t;
    }

    /** 单轴：从 {@code origin} 以 {@code delta} 前进，离开 {@code [min, min+1)} 的参数。 */
    private static double axisExit(double origin, double delta, int min) {
        if (delta > 1.0E-9) {
            return (min + 1.0D - origin) / delta;
        }
        if (delta < -1.0E-9) {
            return (min - origin) / delta;
        }
        return Double.MAX_VALUE;
    }

    /** 她饰品栏里有没有「大运」（TLM 饰品栏；与自保/飞行那些 hasBaubleItem 同一套扫法）。 */
    public static boolean wearsDaYun(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler bauble =
                    maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                ItemStack stack = bauble.getStackInSlot(i);
                if (DaYunPlayerHook.isDaYun(stack)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunWallPhase", ignored);
        }
        return false;
    }
}
