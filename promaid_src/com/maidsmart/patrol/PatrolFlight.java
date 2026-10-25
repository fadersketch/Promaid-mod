package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidBroomKit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>── 玩家定下的口径（逐字）──
 * 「既然是巡逻，那它相当于替代了原来 home 模式下。所以这个功能也只对 home 模式下的扫帚模式下
 * 女仆进行操作。」⇒ {@link #effective} 要求三件同时成立：**任务=扫帚模式** + **home 模式开着**
 * + **绑定了一条已连接（闭环）的航迹**。缺任一条都返回 null，调用方原样走旧链路（home 沿工作圈
 * 盘旋 / 跟随主人 / 悬停），一个字节都不改。
 * <p>── 接敌怎么办 ──
 * 什么都不用做。{@code MaidBroomBehavior.tick} 的相位顺序里"接敌档"排在"平时档"**前面**，
 * 所以敌人一出现就自动抢占（爬升 → 绕敌盘旋/轰炸），敌人消失后自然回到平时档、也就是回到这条
 * 航迹上。这正是玩家要的"发现敌人就绕着敌人转圈并攻击"，白捡的。
 * <p>── 路径缓存 ──
 * 密采样一条航迹要几百次样条求值，绝不能每拍重算。缓存键 = 女仆 UUID，值 = (内容签名, Path)。
 * 签名取自 {@link PatrolRoute#signature()}——玩家改了航迹、或重新绑定，签名就变，下一拍自动重建。
 */
public final class PatrolFlight {

    /** 前视距离基准（格）——纯追踪瞄"投影点往前这么远" */
    private static final double LOOKAHEAD_BASE = 2.5;
    /** 前视距离随速度放大的系数：速度越快看得越远（格/（格/拍）） */
    private static final double LOOKAHEAD_PER_SPEED = 10.0;
    /** 前视距离上限（格） */
    private static final double LOOKAHEAD_MAX = 12.0;
    /**
     * 【R1121】驾驶飞行载具时的前视下限：必须大于 {@code MaidMountCompat.FLIGHT_ARRIVE = 3.0}，
     * 否则"到位就松油"的死区会把纯追踪的胡萝卜吃掉，她停在航迹点上不走。
     * 【R1127】6 → 8：高速切弯时前视太短会在航线两侧来回摆，略放大后转弯更平顺。
     */
    private static final double VEHICLE_MIN_LOOKAHEAD = 8.0;
    /** 她偏离航迹超过这么多格 → 认为"得先回航迹"，把前视距离收到最小（防止绕大圈切过去） */
    private static final double FAR_FROM_PATH = 6.0;
    /**
     * 【R1128】飞行载具偏离航迹时的前视下限（格）。偏离越多前视越短，切回航线的夹角越大，
     * 修正不用拖十几秒；下限仍要大于 {@code MaidMountCompat.FLIGHT_ARRIVE = 3.0}。
     */
    private static final double VEHICLE_NEAR_LOOKAHEAD = 5.0;
    /** 【R1128】横向阻尼：按"横向误差变化率"把胡萝卜沿偏离方向回推的拍数。 */
    private static final double CROSS_DAMP_TICKS = 6.0;
    /** 【R1128】只在偏离航迹这么近（格）时才加阻尼：太远时本来就要全力切回去。 */
    private static final double CROSS_DAMP_RANGE = 4.0;

    /* ==================== R1127：减速点 ==================== */

    /** 减速点的影响半径（格）：玩家口径是 10×10×10，所以三轴各 ±5。 */
    public static final double SLOW_HALF = 5.0;
    /** 减速点内的速度倍率（"大幅减速"）：0.1 = 巡航速度的十分之一。 */
    public static final double SLOW_SCALE = 0.1;

    /** 女仆 UUID → 缓存 */
    private static final Map<UUID, Cache> CACHE = new HashMap<>();

    private static final class Cache {
        String sig;
        PatrolCurve.Path path;
        /** 【R1128】上一拍的投影点下标：投影只在这附近找，路线两段挨得近时不会跨段跳。 */
        int hint = -1;
    }

    private PatrolFlight() {
    }

    /* ==================== 生效判据 ==================== */

    /**
     * R1114：她是不是"正在驾驶一台飞行载具"（直升机等）——航迹飞行对扫帚与这类载具同样生效。
     * 只认 0 号驾驶位：玩家自己开的时候方向盘在玩家手里，{@code RideBindManager} 整段让位。
     */
    public static boolean onFlyingVehicle(EntityMaid maid) {
        try {
            net.minecraft.world.entity.Entity mount = maid == null ? null : maid.m_20202_();
            return mount != null
                    && com.maidsmart.combat.MaidMountCompat.isFlyingVehicle(mount)
                    && !com.maidsmart.combat.MaidMountCompat.isAircraft(mount)
                    && mount.m_146895_() == maid;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return false;
        }
    }

    public static PatrolRoute effective(EntityMaid maid) {
        try {
            if (maid == null || maid.m_9236_().m_5776_()) {
                return null;
            }
            // 总开关（默认开）：关掉 = 绑了的航迹不起作用，扫帚模式回到原本的守家盘旋
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_PATROL_ENABLE.get()) {
                return null;
            }
            // R1114：扫帚任务与"她在驾驶飞行载具"同权——两种都能按航迹飞
            if (!MaidBroomKit.isBroomTask(maid) && !onFlyingVehicle(maid)) {
                return null;
            }
            // R1116：home 模式就够——不再要求限制圈心有效。玩家在 GUI 里打开 home 时 TLM 只翻标志位、
            // 不写圈心（{@code MaidConfigManager#setHomeModeEnable} 字节码实证），旧判据会让"只开 home
            // 没画圈"的直升机永远不生效、被 home 档按住悬停。航迹自己带目的地，不需要圈心。
            // 【R1121】航迹只对 home 女仆生效，跟随模式完全不用这条轨。
            // R1118 曾对驾驶飞行载具放行（"绑定本身就是显式命令"），实测后果是关掉 home 后
            // 直升机仍被航迹/悬停接管、不跟随主人——现在扫帚与飞行载具统一要求 home。
            if (!maid.isHomeModeEnable()) {
                return null; // 没开 home：这条航迹不生效，走旧链路（跟随主人）
            }
            PatrolRoute route = PatrolChartData.readMaid(maid);
            if (route == null || !route.viable() || !route.closed()) {
                return null;
            }
            // 跨维度不巡逻（绑定那一刻记下了维度）
            String dim = route.dimension();
            if (dim != null && !dim.isEmpty()) {
                String now;
                try {
                    now = maid.m_9236_().m_46472_().m_135782_().toString();
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
                    now = dim;
                }
                if (!dim.equals(now)) {
                    return null;
                }
            }
            return route;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return null;
        }
    }

    /**
     * 【R1118】诊断用：按 {@link #effective} 的**同一组判据**说明它为什么没生效。
     * 只给「航迹飞行」日志读，不改任何行为。
     */
    public static String explain(EntityMaid maid) {
        try {
            if (maid == null) {
                return "没有女仆";
            }
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_PATROL_ENABLE.get()) {
                return "巡逻总开关关";
            }
            if (!MaidBroomKit.isBroomTask(maid) && !onFlyingVehicle(maid)) {
                return "任务不是扫帚、也没在驾驶飞行载具";
            }
            if (!maid.isHomeModeEnable()) {
                return "没开 home";
            }
            PatrolRoute r = PatrolChartData.readMaid(maid);
            if (r == null) {
                return "未绑定航迹";
            }
            if (!r.viable()) {
                return "航迹点数不足";
            }
            if (!r.closed()) {
                return "航迹未闭环";
            }
            String dim = r.dimension();
            if (dim != null && !dim.isEmpty() && !dim.equals(
                    maid.m_9236_().m_46472_().m_135782_().toString())) {
                return "航迹在其他维度";
            }
            return "生效";
        } catch (Throwable t) {
            com.maidsmart.diag.Diag.swallow("PatrolFlight", t);
            return "读取异常：" + t;
        }
    }

    /** 只是"绑了没"（给提示用：没闭环时要说清是没连上，而不是当作没绑） */
    public static PatrolRoute boundRoute(EntityMaid maid) {
        try {
            return maid == null || maid.m_9236_().m_5776_() ? null : PatrolChartData.readMaid(maid);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return null;
        }
    }

    /* ==================== 每拍：给一个目标点 ==================== */

    /**
     * 算这一个 tick 她该往哪儿飞。返回 null = 没在巡逻（调用方走旧链路）。
     *
     * <p>纯追踪：把扫帚当前位置投影到航迹折线上，瞄"投影点往前 {@code lookahead} 格"那个点。
     * 她被吹偏、被打退之后，下一拍投影回来就自动切回正轨——不累积漂移。
     */
    public static Vec3 nextPoint(EntityMaid maid, Vec3 broomPos) {
        try {
            PatrolRoute route = effective(maid);
            if (route == null || broomPos == null) {
                return null;
            }
            PatrolCurve.Path path = pathFor(maid, route);
            if (path == null || path.size() < 2) {
                return null;
            }
            UUID id = maid.m_20148_();
            Cache c = CACHE.get(id);
            if (c == null) {
                return null;
            }
            // 【R1128】投影点只从上一拍附近找（见 PatrolCurve.Path#nearestNear）：
            // 航线两段靠得近时，全局最近点会在两段之间来回跳，胡萝卜一跳她就摆。
            int lastIdx = c.hint;
            int nearIdx = path.nearestNear(broomPos, lastIdx);
            if (nearIdx < 0) {
                return null;
            }
            Vec3 near = path.pointAt(nearIdx);
            if (near == null) {
                return null;
            }
            double deviation = broomPos.m_82554_(near);
            double devH = Math.sqrt((broomPos.f_82479_ - near.f_82479_) * (broomPos.f_82479_ - near.f_82479_)
                    + (broomPos.f_82481_ - near.f_82481_) * (broomPos.f_82481_ - near.f_82481_));
            double speed = speedOf(maid);
            double look = Math.min(LOOKAHEAD_MAX,
                    LOOKAHEAD_BASE + speed * LOOKAHEAD_PER_SPEED);
            if (deviation > FAR_FROM_PATH) {
                look = Math.max(0.5, look * 0.4); // 偏太远：先把前视收短，别切大圈
            }
            // 【R1121】飞行载具的"胡萝卜"必须大于驾驶层的到位死区：`FLIGHT_ARRIVE = 3.0`。
            // 静止时前视只有 2.5 格，目标落进死区 → 驾驶层判"到了"、不给油——直升机
            // 就会停在第一个航迹点上不动（玩家实测）。6 格既出了死区，又不会切太大弯。
            // 【R1128】偏离航迹时前视随偏离量收短（8 → 5）：夹角更大、切回更快，
            // 不再"慢慢蹭回航线"；贴线时仍用 8 格，巡航不被拉成锯齿。
            if (onFlyingVehicle(maid)) {
                if (devH > 2.0) {
                    look = clamp(VEHICLE_MIN_LOOKAHEAD - (devH - 2.0) * 0.75,
                            VEHICLE_NEAR_LOOKAHEAD, VEHICLE_MIN_LOOKAHEAD);
                } else {
                    look = Math.max(look, VEHICLE_MIN_LOOKAHEAD);
                }
            }
            Vec3 aim = path.aimFrom(nearIdx, look);
            if (aim == null) {
                return null;
            }
            // 【R1128】横向阻尼（纯追踪的 D 项）：机身斜穿航线时仍带着横向动量，
            // 越线以后只靠"再瞄一次"要等一个同样大的角度才拉回来，玩家看到的就是
            // 在航线两侧来回摆。按横向误差变化率把胡萝卜沿偏离方向回推一点——
            // 正在回线（rate<0）时少打舵，正在离开（rate>0）时多打舵。
            if (onFlyingVehicle(maid) && devH > 0.4 && devH <= CROSS_DAMP_RANGE) {
                Vec3 vel = velocityOf(maid);
                if (vel != null) {
                    double ux = (broomPos.f_82479_ - near.f_82479_) / devH;
                    double uz = (broomPos.f_82481_ - near.f_82481_) / devH;
                    double rate = vel.f_82479_ * ux + vel.f_82481_ * uz;
                    double shift = clamp(-rate * CROSS_DAMP_TICKS, -look * 0.4, look * 0.4);
                    if (Math.abs(shift) > 0.02) {
                        aim = aim.m_82520_(ux * shift, 0.0, uz * shift);
                    }
                }
            }
            c.hint = nearIdx;
            // 高度：航迹点的 y 就是玩家打点时的高度。真被地形挡住时 steerTo 的脱困与危险绕行会兜住。
            // 【v1.3.9.2】再加一道更轻的"自己让一让"：目标点若被方块占着就往空处挪几格
            // （见 PatrolAdapt.nudge；避让不改存档，只改这一拍往哪儿飞）。
            // 【v1.3.9.3】从"只往上抬"扩成三维（上/左右/下）——头顶整片封死时也能绕。
            return PatrolAdapt.nudge(maid.m_9236_(), aim, PatrolAdapt.MAX_LIFT);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return null;
        }
    }

    /** 取（必要时重建）这只女仆的航迹运行态 */
    private static PatrolCurve.Path pathFor(EntityMaid maid, PatrolRoute route) {
        UUID id = maid.m_20148_();
        String sig = route.signature();
        Cache c = CACHE.get(id);
        if (c != null && c.path != null && sig.equals(c.sig)) {
            return c.path;
        }
        PatrolCurve.Path built = new PatrolCurve.Path(route);
        if (c == null) {
            c = new Cache();
            CACHE.put(id, c);
        }
        c.sig = sig;
        c.path = built;
        c.hint = -1; // 新路径的下标与旧路径无关
        if (CACHE.size() > 256) {
            CACHE.clear(); // 极端情况（大量女仆反复换任务）宁可全忘，也不让表长起来
        }
        return built;
    }

    /* ==================== R1127：减速点 ==================== */

    /**
     * 她此刻在不在某个减速点的 8×8×8 里。判据与 {@link #effective} 同源：只有**绑定这条航迹 +
     * 正在巡逻**的女仆才算，跟随/战斗/换任务后立即失效。扫帚与飞行载具共用这一个入口。
     * @param pos 真正在移动的那个实体的坐标（骑扫帚/载具时传载具，不是女仆座位）
     * @return 在减速点内返回 {@link #SLOW_SCALE}，否则 1.0
     */
    public static double speedScale(EntityMaid maid, Vec3 pos) {
        try {
            if (maid == null || pos == null) {
                return 1.0;
            }
            PatrolRoute route = effective(maid);
            if (route == null) {
                return 1.0;
            }
            for (int idx : route.slowIndexes()) {
                Vec3 p = route.point(idx);
                if (p == null) {
                    continue;
                }
                if (Math.abs(pos.f_82479_ - p.f_82479_) <= SLOW_HALF
                        && Math.abs(pos.f_82480_ - p.f_82480_) <= SLOW_HALF
                        && Math.abs(pos.f_82481_ - p.f_82481_) <= SLOW_HALF) {
                    return SLOW_SCALE;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
        }
        return 1.0;
    }

    /* ==================== 清理 ==================== */
    /** 换任务 / 下扫帚 / 解绑时清掉缓存（下次重新绑定时重建） */
    public static void forget(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            CACHE.remove(maid.m_20148_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
        }
    }

    /* ==================== 小工具 ==================== */

    /** 她骑着的那个实体的速度（格/拍）；没骑 / 拿不到 → null */
    private static Vec3 velocityOf(EntityMaid maid) {
        try {
            net.minecraft.world.entity.Entity mount = maid == null ? null : maid.m_20202_();
            return mount == null ? null : mount.m_20184_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return null;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 扫帚当前速度（格/拍）；拿不到就按 0 算（前视取最小） */
    private static double speedOf(EntityMaid maid) {
        try {
            net.minecraft.world.entity.Entity broom = maid.m_20202_();
            if (broom == null) {
                return 0.0;
            }
            Vec3 v = broom.m_20184_();
            return Math.sqrt(v.f_82479_ * v.f_82479_ + v.f_82480_ * v.f_82480_ + v.f_82481_ * v.f_82481_);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolFlight", ignored);
            return 0.0;
        }
    }

    /** 一行诊断（日志搜「扫帚巡逻」） */
    public static String status(EntityMaid maid) {
        try {
            PatrolRoute r = boundRoute(maid);
            if (r == null) {
                return "未绑定航迹";
            }
            PatrolRoute eff = effective(maid);
            StringBuilder sb = new StringBuilder();
            sb.append(r.describe());
            sb.append(" / home=").append(maid.isHomeModeEnable());
            sb.append(" / 任务=").append(MaidBroomKit.isBroomTask(maid) ? "扫帚"
                    : (onFlyingVehicle(maid) ? "飞行载具" : "无"));
            sb.append(" / 生效=").append(eff != null);
            Cache c = CACHE.get(maid.m_20148_());
            if (c != null && c.path != null) {
                sb.append(String.format(" / 一圈 %.0f 格 约 %.0f 秒", c.path.total(),
                        PatrolGeometry.secondsFor(c.path.total())));
            }
            BlockPos h = com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid);
            if (h != null) {
                sb.append(" / 家=").append(h.m_123341_()).append(',').append(h.m_123342_()).append(',').append(h.m_123343_());
            }
            return sb.toString();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("PatrolFlight", t); return "读取异常：" + t;
        }
    }
}
