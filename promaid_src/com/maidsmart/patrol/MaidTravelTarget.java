package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "飞到这个目标点"——巡逻航图里点「飞过去」后，给**正载着玩家的那只女仆**下的一条临时指令。
 *
 * <p>为什么单独一张表：四种情形（同乘扫帚 / 直升机副驾 / 空袭拴绳 / 扫帚拴绳）的位移链路
 * 各走各的（{@code MaidBroomDrive} / {@code RideBindManager} / {@code MaidFlightFollowBehavior}），
 * 但它们都只需要同一个答案：**这一拍把主人换成哪个坐标**。所以目标只存一次，三条链路各自
 * 在自己的"跟随主人"入口处问一句，其余逻辑一个字节不动。
 *
 * <p>生命周期：下单后一直有效，直到 ① 到达（{@link #ARRIVE_HALF} 包围盒，或 8 格内停稳 3 秒）、
 * ② 女仆换维度、③ 超时（{@link #TTL_TICKS}，防止离线/卡住后永久生效）、④ 手动清除。
 * 只存坐标不存目标点 id：玩家删掉目标点后，已经起飞的这一趟仍然会飞完（符合"点了就飞"）。
 */
public final class MaidTravelTarget {

    private static final Map<UUID, Req> MAP = new HashMap<>();
    /** 一条指令最多活 30 分钟（36000 tick） */
    private static final long TTL_TICKS = 36000L;
    /** 到点判定（格）——与巡逻航迹的"到点"同量级 */
    public static final double ARRIVE = 4.0;
    /**
     * 【R1120】到达包围盒半边长：±5 格（10×10×10）。R1118 的 ±2.5（5×5×5）实测太紧——
     * 飞行跟随的"收手刹车圈"是 5 格，她会被刹在盒外永远进不来。R1120 同时拆掉那道刹车，
     * 并把盒放宽到 ±5，保证"减速了就一定算到"。
     */
    public static final double ARRIVE_HALF = 5.0;
    /** 【R1120】兜底到点：已经进到 8 格内并停稳 3 秒（60 tick），也算到——防止绕着盒子打转 */
    private static final double SETTLE_RADIUS = 8.0;
    private static final long SETTLE_TICKS = 60L;
    /** 【R1120】"刚到达"标记的有效期（tick）：飞行跟随据此收手，而不是回落成追主人 */
    private static final long ARRIVED_TTL = 200L;
    /** 【R1123】下单后的起飞加速窗口（tick）：这段时间内不踩到点减速，避免"刚起飞就被刹住" */
    private static final long TAKEOFF_GRACE = 20L;
    private static final Map<UUID, Long> ARRIVED = new HashMap<>();
    private static final Map<UUID, Long> CLOSE_SINCE = new HashMap<>();

    private record Req(double x, double y, double z, String dimension, long until,
                       long setAt, boolean home, String task) {
    }

    private MaidTravelTarget() {
    }

    /** 给这只女仆下单（维度取她当前所在维度；跨维度的合法性由调用方先判） */
    public static void set(EntityMaid maid, Vec3 pos) {
        try {
            if (maid == null || pos == null) {
                return;
            }
            UUID id = maid.m_20148_();
            long now = maid.m_9236_().m_46467_();
            MAP.put(id, new Req(pos.f_82479_, pos.f_82480_, pos.f_82481_,
                    dimensionOf(maid), now + TTL_TICKS, now,
                    maid.isHomeModeEnable(), taskIdOf(maid)));
            ARRIVED.remove(id);
            CLOSE_SINCE.remove(id);
            // 【R1118】下单留痕：点「飞过去」却不起飞时，先看这一行有没有落盘
            com.maidsmart.tool.PromaidLog.log("飞过去", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 接受目标 (" + String.format("%.1f, %.1f, %.1f",
                    pos.f_82479_, pos.f_82480_, pos.f_82481_)
                    + ") 维度=" + dimensionOf(maid));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
        }
    }

    public static void clear(EntityMaid maid) {
        try {
            if (maid != null) {
                UUID id = maid.m_20148_();
                MAP.remove(id);
                ARRIVED.remove(id);
                CLOSE_SINCE.remove(id);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
        }
    }

    public static boolean active(EntityMaid maid) {
        return aim(maid) != null;
    }

    /** 这一拍还有没有有效目标（过期/换维度/已到达会顺手清掉） */
    public static Vec3 aim(EntityMaid maid) {
        return aim(maid, null);
    }

    /**
     * @param current 当前载具/本人位置；传 null 时自己取（骑着扫帚/直升机时取载具位置）
     */
    public static Vec3 aim(EntityMaid maid, Vec3 current) {
        try {
            if (maid == null || !maid.m_6084_()) {
                return null;
            }
            UUID id = maid.m_20148_();
            Req r = MAP.get(id);
            if (r == null) {
                return null;
            }
            long now = maid.m_9236_().m_46467_();
            // 【R1121】玩家口径：换工作模式、或在「跟随 / home」之间切换，都要结束这一趟。
            // 放在这里而不是各个切换入口：aim 每拍都会被三条飞行链路调用，天然覆盖命令、
            // 面板、工具、排班、自动战斗等所有改法。
            if (r.home != maid.isHomeModeEnable()) {
                cancel(maid, r.home ? "切到跟随模式" : "切到 home 模式");
                return null;
            }
            String taskNow = taskIdOf(maid);
            if (r.task != null && !r.task.isEmpty() && !taskNow.isEmpty()
                    && !r.task.equals(taskNow)) {
                cancel(maid, "切换工作模式（" + r.task + " → " + taskNow + "）");
                return null;
            }
            if (now > r.until || !r.dimension.equals(dimensionOf(maid))) {
                MAP.remove(id);
                CLOSE_SINCE.remove(id);
                return null;
            }
            Vec3 p = current != null ? current : positionOf(maid);
            if (p == null) {
                return new Vec3(r.x, r.y, r.z);
            }
            // 【R1120】到点判据：目标点 ±5 格（10×10×10）三轴都要进。放宽的原因见 ARRIVE_HALF。
            if (Math.abs(p.f_82479_ - r.x) <= ARRIVE_HALF
                    && Math.abs(p.f_82480_ - r.y) <= ARRIVE_HALF
                    && Math.abs(p.f_82481_ - r.z) <= ARRIVE_HALF) {
                finish(maid, id, r, p, now, "±5 格包围盒");
                return null;
            }
            // 【R1120】兜底：已在 8 格内停稳 3 秒也算到——她绕着目标转/悬停时不会永远差几格。
            if (p.m_82557_(new Vec3(r.x, r.y, r.z))
                    <= SETTLE_RADIUS * SETTLE_RADIUS) {
                long since = CLOSE_SINCE.computeIfAbsent(id, k -> now);
                if (now - since >= SETTLE_TICKS) {
                    finish(maid, id, r, p, now, "8 格内停稳 3 秒");
                    return null;
                }
            } else {
                CLOSE_SINCE.remove(id);
            }
            return new Vec3(r.x, r.y, r.z);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return null;
        }
    }

    /**
     * 【R1123】这一趟是不是刚下单（{@link #TAKEOFF_GRACE} 内）：飞行跟随据此推迟到点减速。
     * 从目标 1 改飞目标 2 时，新目标可能就在附近，但起飞本身仍需要一小段加速。
     */
    public static boolean justOrdered(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Req r = MAP.get(maid.m_20148_());
            return r != null && maid.m_9236_().m_46467_() - r.setAt < TAKEOFF_GRACE;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return false;
        }
    }

    /**
     * 【R1120】刚到达标记（只读，不消费）：飞行跟随在 tick 里看到它就先别动，
     * 下一拍由 {@link #consumeArrival} 翻成"结束（已到达目标点）"。
     */
    public static boolean justArrived(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Long at = ARRIVED.get(maid.m_20148_());
            return at != null && maid.m_9236_().m_46467_() - at <= ARRIVED_TTL;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return false;
        }
    }

    /** 【R1120】取走"刚到达"标记（一次性）：给飞行行为收手用。 */
    public static boolean consumeArrival(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Long at = ARRIVED.remove(maid.m_20148_());
            return at != null && maid.m_9236_().m_46467_() - at <= ARRIVED_TTL;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return false;
        }
    }

    /**
     * 【R1121】主动取消（魂符收回 / home 切换 / 换工作模式）：清目标并留一行日志。
     * 魂符收回与死亡由 {@code ProMaidExtension.onMaidLeaveLevel} 调；其余由 {@link #aim} 自检。
     */
    public static void cancel(EntityMaid maid, String why) {
        try {
            if (maid == null) {
                return;
            }
            UUID id = maid.m_20148_();
            boolean had = MAP.remove(id) != null;
            ARRIVED.remove(id);
            CLOSE_SINCE.remove(id);
            if (had) {
                com.maidsmart.tool.PromaidLog.log("飞过去", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 本次飞行取消（" + why + "）");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
        }
    }

    private static String taskIdOf(EntityMaid maid) {
        try {
            return maid.getTask() == null ? "" : maid.getTask().getUid().toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return "";
        }
    }

    private static void finish(EntityMaid maid, UUID id, Req r, Vec3 at, long now, String why) {
        MAP.remove(id);
        CLOSE_SINCE.remove(id);
        ARRIVED.put(id, now);
        announceArrived(maid, r, at, why);
    }

    /** 到点：日志 + 给主人一句"结束"（玩家口径：到了就宣布本次飞行结束） */
    private static void announceArrived(EntityMaid maid, Req r, Vec3 at, String why) {
        try {
            String name = com.maidsmart.tool.PromaidLog.nameOf(maid);
            com.maidsmart.tool.PromaidLog.log("飞过去", name + " 已到达目标点附近（" + why + "）"
                    + String.format("，落点 (%.1f, %.1f, %.1f) 目标 (%.1f, %.1f, %.1f)",
                     at.f_82479_, at.f_82480_, at.f_82481_, r.x, r.y, r.z));
            if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player player) {
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a✦ " + name + " 已到达目标点附近，本次飞行结束"));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
        }
    }

    /** 她当前"真正在飞的东西"的位置：骑着扫帚/直升机时取载具，否则取她自己 */
    public static Vec3 positionOf(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            net.minecraft.world.entity.Entity mount = maid.m_20202_();
            if (mount != null) {
                return new Vec3(mount.m_20185_(), mount.m_20186_(), mount.m_20189_());
            }
            return new Vec3(maid.m_20185_(), maid.m_20186_(), maid.m_20189_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return null;
        }
    }

    private static String dimensionOf(EntityMaid maid) {
        try {
            return maid.m_9236_().m_46472_().m_135782_().toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidTravelTarget", ignored);
            return "";
        }
    }
}
