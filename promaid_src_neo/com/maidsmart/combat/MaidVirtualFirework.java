package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 【R1042】隐形烟花推力：把"挂载烟花"的推力从实体上解耦出来。
 *
 * <p>原版挂载烟花对滑翔中的骑手每 tick 递推 {@code v ← v×0.5 + 视线×0.85}。旧版这里
 * {@code addFreshEntity(FireworkRocketEntity)} 会顺带画出火箭与烟花粒子；本轮改为由本类
 * 直接施加同一套递推，<b>力度与时长都照原版</b>，但不再生成实体——粒子自然消失。
 *
 * <p>粒子照原版挂载烟花的发射口径：客户端每 tick <b>1 粒</b> {@code FIREWORK}
 * （速度 {@code (gauss×0.05, -dy×0.5, gauss×0.05)}），没有"点火一束"。本类只做推力、不喷爆发；
 * 尾迹由 {@link FireworkTrailNetworking} 通知客户端在她**本帧渲染坐标**上发（服务端坐标比她画出来的
 * 位置领先一拍，见 {@code FireworkTrailClient}）。穿神秘遗物+的壮丽 / 混沌鞘翅时同样每 tick 1 粒
 * {@code DRAGON_BREATH}。
 *
 * <p>窗口 {@link #TICKS} 取原版挂载烟花的寿命量级（2 秒）；只在她滑翔时推，收翅不推但窗口照走，与原版"火箭挂在她身上直到到期"一致。
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

    /**
     * 【R1083】"点了火却没动"的冷却表：窗口开着、人却原地不动超过
     * {@link #STALL_TICKS} 拍 → 收掉窗口并在这段时间里拒绝再点火。玩家实测
     * "一直在原地放烟花但飞不走"就是推进窗被墙/地形吃掉后，调用方按冷却反复补点。
     */
    private static final Map<UUID, Long> STALL_UNTIL = new HashMap<>();
    /** 判定"原地"的位移阈值（格/tick）与连续拍数 */
    private static final double STALL_MOVE = 0.05;
    private static final int STALL_TICKS = 10;
    /** 停滞后的拒绝点火时长（tick）：3 秒 */
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
            UUID id = maid.getUUID();
            Long until = STALL_UNTIL.get(id);
            if (until != null) {
                if (maid.level().getGameTime() < until) {
                    return false; // 【R1083】刚因为原地不动被收过窗：这段冷却里别再烧
                }
                STALL_UNTIL.remove(id);
            }
            WINDOWS.put(id, new Window(TICKS, Math.max(0.1, scale)));
            // 点火本身**不发任何粒子**：原版的烟花火箭起飞时也没有爆发，只有飞行途中每 tick 一粒。
            // 尾迹整体交给客户端（在她本帧的渲染坐标上发）——服务端坐标会领先客户端画出来的她一拍。
            if (!maid.level().isClientSide()) {
                FireworkTrailNetworking.start(maid, TICKS);
            }
            return true;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    /** 【R1083】是否刚被判定为"原地空推"（调用方据此别消耗烟花）。 */
    public static boolean isStalled(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Long until = STALL_UNTIL.get(maid.getUUID());
            if (until == null) {
                return false;
            }
            if (maid.level().getGameTime() < until) {
                return true;
            }
            STALL_UNTIL.remove(maid.getUUID());
            return false;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
            return false;
        }
    }

    /**
     * 每 tick 推一下。由空袭 / 长矛空袭 / 飞行跟随三条链路在相位派发之前各调一次；
     * 没有窗口时只是一次 Map 查询。
     */
    public static void tick(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.getUUID();
            Window w = WINDOWS.get(id);
            if (w == null) {
                return;
            }
            if (w.left <= 0) {
                WINDOWS.remove(id);
                return;
            }
            // 【R1083】原地空推检测：窗口开着但位置连续多拍几乎不动 = 推力被墙/地形吃掉。
            // 收掉窗口 + 冷却，避免调用方每轮冷却都再烧一枚烟花却始终飞不走。
            if (w.hasLast) {
                double dx = maid.getX() - w.lastX;
                double dy = maid.getY() - w.lastY;
                double dz = maid.getZ() - w.lastZ;
                if (dx * dx + dy * dy + dz * dz < STALL_MOVE * STALL_MOVE) {
                    if (++w.stillTicks >= STALL_TICKS) {
                        WINDOWS.remove(id);
                        STALL_UNTIL.put(id, maid.level().getGameTime() + STALL_COOLDOWN);
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
            w.lastX = maid.getX();
            w.lastY = maid.getY();
            w.lastZ = maid.getZ();
            w.hasLast = true;
            w.left--;
            // 与原版同一个门：只对正在滑翔的骑手给推力
            if (!maid.isFallFlying()) {
                return;
            }
            Vec3 look = maid.getLookAngle();
            Vec3 v = maid.getDeltaMovement();
            double add = ADD * w.scale;
            maid.setDeltaMovement(
                    v.x * GAIN + look.x * add,
                    v.y * GAIN + look.y * add,
                    v.z * GAIN + look.z * add);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidVirtualFirework", ignored);
        }
    }


    /** 撤掉窗口（返回 true = 本来还开着）。已给的速度不动；客户端尾迹同步收掉。 */
    public static boolean clear(EntityMaid maid) {
        try {
            if (maid == null || WINDOWS.remove(maid.getUUID()) == null) {
                return false;
            }
            if (!maid.level().isClientSide()) {
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
            return maid != null && WINDOWS.containsKey(maid.getUUID());
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
