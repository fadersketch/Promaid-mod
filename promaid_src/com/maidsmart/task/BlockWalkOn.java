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

    /** 登记"走上去"目标并给首次推力（后续 tick 由 tick() 持续推送） */
    public static void start(EntityMaid maid, double tx, double ty, double tz) {
        ACTIVE.put(maid.m_20148_(), new State(tx, ty, tz, 12));
    }

    public static boolean isActive(EntityMaid maid) {
        return ACTIVE.containsKey(maid.m_20148_());
    }

    /**
     * 每 tick 调用（行为 tick 开头）。返回 true = 本 tick 已消费（调用方 return）。
     * 到达判定：水平已进入目标格 且 脚位达到目标高度（斜上台阶目标 y+1 不能按
     * |dy|<1.01 判——她还站在下面时差值恰为 1，会误判提前收力）。
     */
    public static boolean tick(EntityMaid maid) {
        State s = ACTIVE.get(maid.m_20148_());
        if (s == null) {
            return false;
        }
        boolean arrived = Math.floor(maid.m_20185_()) == Math.floor(s.x())
                && Math.floor(maid.m_20189_()) == Math.floor(s.z())
                && maid.m_20186_() >= s.y() - 0.01;
        if (arrived || s.ticks() <= 1) {
            ACTIVE.remove(maid.m_20148_());
            return false;
        }
        ACTIVE.put(maid.m_20148_(), new State(s.x(), s.y(), s.z(), s.ticks() - 1));
        double dx = s.x() - maid.m_20185_();
        double dz = s.z() - maid.m_20189_();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 1e-3) {
            // 斜上台阶目标（y 高于当前脚位）带起跳；平桥保持原垂直速度（下落自然）
            double vy = s.y() > maid.m_20186_() + 0.5
                    ? Math.max(maid.m_20184_().f_82480_, 0.42)
                    : maid.m_20184_().f_82480_;
            maid.m_20256_(new net.minecraft.world.phys.Vec3(dx / d * 0.22, vy, dz / d * 0.22));
        }
        return true;
    }
}
