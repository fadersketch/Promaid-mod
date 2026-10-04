package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidBroomKit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.8【巡逻航迹】运行时：生效判据 + 路径缓存 + 每拍目标点（纯追踪）。
 *
 * <p>── 玩家定下的口径（逐字）──
 * 「既然是巡逻，那它相当于替代了原来 home 模式下。所以这个功能也只对 home 模式下的扫帚模式下
 * 女仆进行操作。」⇒ {@link #effective} 要求三件同时成立：**任务=扫帚模式** + **home 模式开着**
 * + **绑定了一条已连接（闭环）的航迹**。缺任一条都返回 null，调用方原样走旧链路（home 沿工作圈
 * 盘旋 / 跟随主人 / 悬停），一个字节都不改。
 *
 * <p>── 接敌怎么办 ──
 * 什么都不用做。{@code MaidBroomBehavior.tick} 的相位顺序里"接敌档"排在"平时档"**前面**，
 * 所以敌人一出现就自动抢占（爬升 → 绕敌盘旋/轰炸），敌人消失后自然回到平时档、也就是回到这条
 * 航迹上。这正是玩家要的"发现敌人就绕着敌人转圈并攻击"，白捡的。
 *
 * <p>── 路径缓存 ──
 * 密采样一条航迹要几百次样条求值，绝不能每拍重算。缓存键 = 女仆 UUID，值 = (内容签名, Path)。
 * 签名取自 {@link PatrolRoute#signature()}——玩家改了航迹、或重新绑定，签名就变，下一拍自动重建。
 */
public final class PatrolFlight {

    /** 前视距离基准（格）——纯追踪瞄"投影点往前这么远" */
    private static final double LOOKAHEAD_BASE = 2.5;
    /** 前视距离随速度放大的系数：速度越快看得越远（格/（格/拍）） */
    private static final double LOOKAHEAD_PER_SPEED = 6.0;
    /** 前视距离上限（格） */
    private static final double LOOKAHEAD_MAX = 8.0;
    /** 她偏离航迹超过这么多格 → 认为"得先回航迹"，把前视距离收到最小（防止绕大圈切过去） */
    private static final double FAR_FROM_PATH = 6.0;

    /** 女仆 UUID → 缓存 */
    private static final Map<UUID, Cache> CACHE = new HashMap<>();

    private static final class Cache {
        String sig;
        PatrolCurve.Path path;
    }

    private PatrolFlight() {
    }

    /* ==================== 生效判据 ==================== */

    /**
     * 这只女仆此刻该不该按航迹巡逻。返回航迹本身（已过闭环/点数判据），否则 null。
     *
     * <p>三件同时成立：
     * <ol>
     *   <li><b>任务 = 扫帚模式</b>——与 {@code MaidBroomKit.isBroomTask} 同一判据（不另写一份）；</li>
     *   <li><b>home 模式开着</b>——玩家原话"只对 home 模式下的扫帚模式下女仆进行操作"。
     *       判据直接复用 {@code WorkAreaClamp.homeAnchor}（= home + 圈心有效，连"排班锚点兜底"
     *       那一层一起），所以"她确实在守家"这件事的判定与本模组别处完全同口径；</li>
     *   <li><b>绑了一条闭环航迹</b>——{@link PatrolChartData#readMaid} 非 null 且
     *       {@link PatrolRoute#closed()} 且点数够。</li>
     * </ol>
     */
    public static PatrolRoute effective(EntityMaid maid) {
        try {
            if (maid == null || maid.level().isClientSide()) {
                return null;
            }
            // 总开关（默认开）：关掉 = 绑了的航迹不起作用，扫帚模式回到原本的守家盘旋
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_PATROL_ENABLE.get()) {
                return null;
            }
            if (!MaidBroomKit.isBroomTask(maid)) {
                return null;
            }
            if (com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid) == null) {
                return null; // 没开 home（或不守家）：这条航迹不生效，走旧链路
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
                    now = maid.level().dimension().location().toString();
                } catch (Throwable ignored) {
                    now = dim;
                }
                if (!dim.equals(now)) {
                    return null;
                }
            }
            return route;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只是"绑了没"（给提示用：没闭环时要说清是没连上，而不是当作没绑） */
    public static PatrolRoute boundRoute(EntityMaid maid) {
        try {
            return maid == null || maid.level().isClientSide() ? null : PatrolChartData.readMaid(maid);
        } catch (Throwable ignored) {
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
            UUID id = maid.getUUID();
            Cache c = CACHE.get(id);
            if (c == null) {
                return null;
            }
            Vec3 near = path.nearestPoint(broomPos);
            if (near == null) {
                return null;
            }
            double deviation = broomPos.distanceTo(near);
            double speed = speedOf(maid);
            double look = Math.min(LOOKAHEAD_MAX,
                    LOOKAHEAD_BASE + speed * LOOKAHEAD_PER_SPEED);
            if (deviation > FAR_FROM_PATH) {
                look = Math.max(0.5, look * 0.4); // 偏太远：先把前视收短，别切大圈
            }
            int[] hint = new int[1];
            Vec3 aim = path.aim(broomPos, look, hint);
            if (aim == null) {
                return null;
            }
            // 高度：航迹点的 y 就是玩家打点时的高度。真被地形挡住时 steerTo 的脱困与危险绕行会兜住。
            // 【v1.3.9.2】再加一道更轻的"自己让一让"：目标点若被方块占着就往空处挪几格
            // （见 PatrolAdapt.nudge；避让不改存档，只改这一拍往哪儿飞）。
            // 【v1.3.9.3】从"只往上抬"扩成三维（上/左右/下）——头顶整片封死时也能绕。
            return PatrolAdapt.nudge(maid.level(), aim, PatrolAdapt.MAX_LIFT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取（必要时重建）这只女仆的航迹运行态 */
    private static PatrolCurve.Path pathFor(EntityMaid maid, PatrolRoute route) {
        UUID id = maid.getUUID();
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
        if (CACHE.size() > 256) {
            CACHE.clear(); // 极端情况（大量女仆反复换任务）宁可全忘，也不让表长起来
        }
        return built;
    }

    /* ==================== 清理 ==================== */

    /** 换任务 / 下扫帚 / 解绑时清掉缓存（下次重新绑定时重建） */
    public static void forget(EntityMaid maid) {
        try {
            if (maid == null) {
                return;
            }
            CACHE.remove(maid.getUUID());
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 小工具 ==================== */

    /** 扫帚当前速度（格/拍）；拿不到就按 0 算（前视取最小） */
    private static double speedOf(EntityMaid maid) {
        try {
            net.minecraft.world.entity.Entity broom = maid.getVehicle();
            if (broom == null) {
                return 0.0;
            }
            Vec3 v = broom.getDeltaMovement();
            return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        } catch (Throwable ignored) {
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
            sb.append(" / home=").append(com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid) != null);
            sb.append(" / 任务=扫帚").append(MaidBroomKit.isBroomTask(maid));
            sb.append(" / 生效=").append(eff != null);
            Cache c = CACHE.get(maid.getUUID());
            if (c != null && c.path != null) {
                sb.append(String.format(" / 一圈 %.0f 格 约 %.0f 秒", c.path.total(),
                        PatrolGeometry.secondsFor(c.path.total())));
            }
            BlockPos h = com.maidsmart.follow.WorkAreaClamp.homeAnchor(maid);
            if (h != null) {
                sb.append(" / 家=").append(h.getX()).append(',').append(h.getY()).append(',').append(h.getZ());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取异常：" + t;
        }
    }
}
