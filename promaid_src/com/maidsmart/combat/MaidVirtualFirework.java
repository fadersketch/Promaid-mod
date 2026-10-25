package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 隐形烟花推力（R1042 / R1083，1.20.1 镜像）。
 *
 * <p>把挂载烟花的推力从实体上解耦：原版对滑翔骑手每 tick 递推
 * {@code v ← v×0.5 + 视线×0.85}。本类直接施加同一套递推，不再生成
 * {@code FireworkRocketEntity}——火箭与爆炸粒子不再出现，只留一条尾迹。
 *
 * <p>R1083：增加"原地空推"检测——窗口开着却连续多拍几乎不动（被墙/地形吃掉推力）时收窗并冷却，
 * 避免反复烧烟花却飞不走。
 *
 * <p>粒子照原版挂载烟花的发射口径：客户端每 tick <b>1 粒</b>、点火不爆发（原版
 * {@code FireworkRocketEntity.m_8119_} 也没有点火爆发），所以一次释放就是玩家放一枚烟花的那条细流。
 * 尾迹由 {@link FireworkTrailNetworking} 通知客户端在她**本帧渲染坐标**上发——服务端坐标比她
 * 画出来的位置领先一拍，那正是"粒子先飞、人还在后面"的来源（见 {@code FireworkTrailClient}）。
 */
public final class MaidVirtualFirework {

    private MaidVirtualFirework() {
    }

    /** 原版挂载烟花的推力递推系数 */
    private static final double GAIN = 0.5;
    private static final double ADD = 0.85;
    /** 窗口时长（tick）：原版挂载烟花约 2 秒 */
    private static final int TICKS = 40;

    private static final class Window {
        int left;
        final double scale;
        double lastX;
        double lastY;
        double lastZ;
        int stillTicks;
        boolean hasLast;

        Window(int left, double scale) {
            this.left = left;
            this.scale = scale;
        }
    }

    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    /** "点了火却没动"的冷却表：窗口开着、人却原地不动超过 STALL_TICKS 拍 → 收窗并拒绝点火。 */
    private static final Map<UUID, Long> STALL_UNTIL = new HashMap<>();
    private static final double STALL_MOVE = 0.05;
    private static final int STALL_TICKS = 10;
    private static final int STALL_COOLDOWN = 60;

    /**
     * 点一枚"隐形烟花"。
     *
     * @param scale 1.0 = 原版力度；俯冲段那一枚可传 {@code airRaid.diveBoostFireworkScale}
     */
    public static boolean ignite(EntityMaid maid, double scale) {
        try {
            if (maid == null) {
                return false;
            }
            UUID id = maid.m_20148_();
            Long until = STALL_UNTIL.get(id);
            if (until != null) {
                if (maid.m_9236_().m_46467_() < until) {
                    return false; // 刚因为原地不动被收过窗：这段冷却里别再烧
                }
                STALL_UNTIL.remove(id);
            }
            WINDOWS.put(id, new Window(TICKS, Math.max(0.1, scale)));
            // 点火本身不发粒子：原版烟花火箭起飞时也没有爆发，只有飞行途中每 tick 一粒。
            // 尾迹整体交给客户端（在她本帧的渲染坐标上发）——服务端坐标会领先客户端画出来的她一拍。
            if (!maid.m_9236_().m_5776_()) {
                FireworkTrailNetworking.start(maid, TICKS);
            }
            return true;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    /** 是否刚被判定为"原地空推"（调用方据此别消耗烟花）。 */
    public static boolean isStalled(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            UUID id = maid.m_20148_();
            Long until = STALL_UNTIL.get(id);
            if (until == null) {
                return false;
            }
            if (maid.m_9236_().m_46467_() < until) {
                return true;
            }
            STALL_UNTIL.remove(id);
            return false;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    /** 每 tick 推一下；由空袭 / 长矛空袭 / 飞行跟随三条链路在相位派发之前各调一次。 */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.m_20148_();
            Window w = WINDOWS.get(id);
            if (w == null) {
                return;
            }
            if (w.left <= 0) {
                WINDOWS.remove(id);
                return;
            }
            if (w.hasLast) {
                double dx = maid.m_20185_() - w.lastX;
                double dy = maid.m_20186_() - w.lastY;
                double dz = maid.m_20189_() - w.lastZ;
                if (dx * dx + dy * dy + dz * dz < STALL_MOVE * STALL_MOVE) {
                    if (++w.stillTicks >= STALL_TICKS) {
                        WINDOWS.remove(id);
                        STALL_UNTIL.put(id, maid.m_9236_().m_46467_() + STALL_COOLDOWN);
                        FireworkTrailNetworking.start(maid, 0);
                        com.maidsmart.tool.PromaidLog.log("飞行推进",
                                com.maidsmart.tool.PromaidLog.nameOf(maid)
                                        + " 原地空推 " + STALL_TICKS + " 拍 → 收掉推力窗，"
                                        + (STALL_COOLDOWN / 20) + " 秒内不再点火");
                        return;
                    }
                } else {
                    w.stillTicks = 0;
                }
            }
            w.lastX = maid.m_20185_();
            w.lastY = maid.m_20186_();
            w.lastZ = maid.m_20189_();
            w.hasLast = true;
            w.left--;
            if (!maid.m_21255_()) {
                return;
            }
            Vec3 look = maid.m_20154_();
            Vec3 v = maid.m_20184_();
            double add = ADD * w.scale;
            maid.m_20256_(new Vec3(
                    v.f_82479_ * GAIN + look.f_82479_ * add,
                    v.f_82480_ * GAIN + look.f_82480_ * add,
                    v.f_82481_ * GAIN + look.f_82481_ * add));
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
        }
    }

    /** 撤掉窗口（返回 true = 本来还开着）。已给的速度不动；客户端尾迹同步收掉。 */
    public static boolean clear(EntityMaid maid) {
        try {
            if (maid == null || WINDOWS.remove(maid.m_20148_()) == null) {
                return false;
            }
            if (!maid.m_9236_().m_5776_()) {
                FireworkTrailNetworking.start(maid, 0);
            }
            return true;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    public static boolean isThrusting(EntityMaid maid) {
        try {
            return maid != null && WINDOWS.containsKey(maid.m_20148_());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    public static void forget(UUID maidId) {
        if (maidId != null) {
            WINDOWS.remove(maidId);
            STALL_UNTIL.remove(maidId);
        }
    }

    public static void clearAll() {
        WINDOWS.clear();
        STALL_UNTIL.clear();
    }
}
