package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 垫块后"走上新方块"持久推送（v1.1.0 实测一百九十二，镜像搭路行为的 walkOn）。
 *
 * 背景：挖矿/伐木垫台阶/桥块后旧版只设 WALK_TARGET 靠寻路走到目标格——跨沟/断崖
 * 或路径差一格时导航半路折断（或原地微移），女仆永远踩不上刚垫的方块，变成
 * "在某几个方块上死循环"（用户："运动的幅度真的太小了"）。
 *
 * 用法：
 *  - 垫块成功 → {@link #start(EntityMaid, double, double, double)}（目标格中心）；
 *  - 调用方每 tick【先】调 {@link #tick(EntityMaid)}——返回 true = 本 tick 已被
 *    推送消费（调用方直接 return，不导航/不垫块/不钳制）；
 *  - 踏入目标格（水平进入 + 脚位达标）即停；12 tick 超时放弃（防卡死）。
 */
public final class BlockWalkOn {
    private record State(double x, double y, double z, int ticks) {
    }

    private static final java.util.Map<java.util.UUID, State> ACTIVE = new java.util.HashMap<>();

    private BlockWalkOn() {
    }

    /** 登记"走上去"目标并给首次推力（后续 tick 由 tick() 持续推送）。
     *  v1.1.0：目标 Y 低于当前脚位时自动走"下行台阶"到达判定（见 tick）。 */
    public static void start(EntityMaid maid, double tx, double ty, double tz) {
        ACTIVE.put(maid.getUUID(), new State(tx, ty, tz, 12));
    }

    public static boolean isActive(EntityMaid maid) {
        return ACTIVE.containsKey(maid.getUUID());
    }

    /**
     * 每 tick 调用（行为 tick 开头）。返回 true = 本 tick 已消费（调用方 return）。
     * 到达判定分两档（v1.1.0 下行台阶）：
     *  - 上行/平移（s.y >= 当前脚位）：水平进入目标格 且 脚位达到目标高度
     *    （斜上台阶目标 y+1 不能按 |dy|<1.01 判——她还站在下面时差值恰为 1，会误判提前收力）；
     *  - 下行（s.y < 当前脚位 - 0.01）：水平进入目标格 且 已落到目标高度附近
     *    （脚位 ≤ targetY+0.01）——旧口径"脚位 >= targetY"在下行时恒真，会当 tick 就误判到达。
     */
    public static boolean tick(EntityMaid maid) {
        State s = ACTIVE.get(maid.getUUID());
        if (s == null) {
            return false;
        }
        boolean inCell = Math.floor(maid.getX()) == Math.floor(s.x())
                && Math.floor(maid.getZ()) == Math.floor(s.z());
        boolean downward = s.y() < maid.getY() - 0.01;
        boolean arrived = inCell && (downward
                ? maid.getY() <= s.y() + 0.01
                : maid.getY() >= s.y() - 0.01);
        if (arrived || s.ticks() <= 1) {
            ACTIVE.remove(maid.getUUID());
            if (arrived) {
                // v1.1.0 实测二百一十五【防摔落·潜行式收力】：到位立即清水平速度——
                // 0.22 的速度带着惯性会在到位后继续滑行约 1.7~2 块（0.22 × 0.91/(1-0.91)），
                // 1 格宽的桥块根本停不住，直接冲出边缘失足（速度越快越明显）。
                // 垂直速度保留（正在下落就继续落，落到块面上）。
                maid.setDeltaMovement(new net.minecraft.world.phys.Vec3(
                        0, maid.getDeltaMovement().y, 0));
            }
            return false;
        }
        ACTIVE.put(maid.getUUID(), new State(s.x(), s.y(), s.z(), s.ticks() - 1));
        double dx = s.x() - maid.getX();
        double dz = s.z() - maid.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 1e-3) {
            // v1.1.0 实测二百一十五【防摔落·潜行式边缘守卫】：目标格下方已经没有
            // 支撑（方块被回收/没放上/被替换）→ 不推不冲，水平收力原地站住——
            // 绝不在断崖边上主动把自己推下去（宁可她停住等下一步重铺）。
            if (!supportedAt(maid, s)) {
                maid.setDeltaMovement(new net.minecraft.world.phys.Vec3(
                        0, maid.getDeltaMovement().y, 0));
                ACTIVE.remove(maid.getUUID());
                return false;
            }
            // 斜上台阶目标（y 高于当前脚位）带起跳；平桥保持原垂直速度（下落自然）
            double vy = s.y() > maid.getY() + 0.5
                    ? Math.max(maid.getDeltaMovement().y, 0.42)
                    : maid.getDeltaMovement().y;
            maid.setDeltaMovement(new net.minecraft.world.phys.Vec3(dx / d * 0.22, vy, dz / d * 0.22));
        }
        return true;
    }

    /** 实测二百一十五：目标格是否可落脚——目标格自身无碰撞、脚下一格有碰撞面
     *  （她自己搭的方块/地形都算；异常放行不拦截——宁可移动也不卡死） */
    private static boolean supportedAt(EntityMaid maid, State s) {
        try {
            net.minecraft.world.level.Level level = maid.level();
            net.minecraft.core.BlockPos cell = new net.minecraft.core.BlockPos(
                    (int) Math.floor(s.x()), (int) Math.floor(s.y()), (int) Math.floor(s.z()));
            if (!level.getBlockState(cell).getCollisionShape(level, cell,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty()) {
                return false;
            }
            net.minecraft.core.BlockPos below = cell.below();
            return !level.getBlockState(below).getCollisionShape(level, below,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty();
        } catch (Exception e) {
            return true;
        }
    }
}
