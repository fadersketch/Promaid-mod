package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百二十六·点3【骑飞行载具的空战：套用扫帚模式的"先爬到敌上、再绕圈打"】。
 *
 * <h2>玩家原话</h2>
 * 「女仆在驾驶武装直升机的时候总算可以跟着主人进行飞行了，但是在战斗的时候显得过于笨逼，
 *  发挥不出武装直升机的优势，应该要套用扫帚模式运动代码和逻辑，体现出空战的优势。至少离敌人
 *  要高出15格左右吧。现在基本上就是绕着敌人盘旋，但是总是在地上和高空5个左右跳动，敌人很
 *  容易就能打到。」
 *
 * <h2>为什么旧版"贴地乱窜"（反编译实证）</h2>
 * 飞行载具（直升机/固定翼）的航向/俯仰走**鼠标通道**、高度靠**总距**（{@code helicopterEngine}），
 * 而 {@code MaidMountCompat.driveFlight} 收的"目标点"来自 {@code RideBindManager.drive}——
 * 那一格算的是"她的走位记忆 / 主人跟随点"。**一有敌人它还在追主人**，于是低空乱飞；再加上
 * 到达判据一进带就切悬停/松总距，就出现玩家说的"在地上和高空5格左右跳动"。
 *
 * <h2>本档做什么（"套用扫帚模式运动代码和逻辑"）</h2>
 * 与 {@link MaidBroomDrive} 的接敌段**同一套形状**：
 * <ol>
 *   <li><b>接敌爬升</b>：遇到敌人先爬到它上方 {@code airAlt} 格（默认 15），并把"这一场遭遇的
 *       盘旋高度"定下来（顶头按实际、本场只升不降）——见 {@link #climbTarget}；</li>
 *   <li><b>战斗盘旋</b>：绕着敌人转圈打，半径/旋向/快慢直接复用扫帚那三套状态机
 *       （{@link CombatOrbit} + {@link CombatManeuver}）——所以"同款"是**真的同一段代码**，
 *       不是另写一份近似物。</li>
 * </ol>
 *
 * <p>与扫帚模式的**唯一**差别是"离敌高度"读的是另一个旋钮（{@code combat.ride.airAlt}，默认 15；
 * 扫帚那个是 {@code combat.broom.climb}，默认 12）——玩家给直升机点名要的是 15。
 *
 * <p>本类**只算"去哪"**（返回一个目标点），不碰任何载具 API：真正把点翻译成鼠标通道/总距的是
 * {@link MaidMountCompat#driveFlight}（它已经写好并实机验证过"能跟着主人飞"）。所以这一档的风险
 * 面只在"目标点从哪儿来"。
 */
public final class MaidAirCombat {

    private MaidAirCombat() {
    }

    /* ==================== 状态表（按女仆 UUID，异常一律吞掉） ==================== */

    /** 女仆 UUID → 这一场遭遇的盘旋高度（相对敌人脚底的格数，本场只升不降） */
    private static final Map<UUID, Double> ALT = new HashMap<>();
    /** 女仆 UUID → 接敌爬升相位：想爬到的绝对 Y */
    private static final Map<UUID, Double> CLIMB_TO = new HashMap<>();
    /** 女仆 UUID → 这一次爬升的起始 Y（"有没有真的在长高"的判据 + 日志） */
    private static final Map<UUID, Double> CLIMB_FROM = new HashMap<>();
    /** 女仆 UUID → 连续多少拍没长高（头顶被方块顶住的判据，与扫帚同口径） */
    private static final Map<UUID, Integer> CLIMB_STALL = new HashMap<>();
    /** 女仆 UUID → 上一拍的高度（"这一拍涨了没有"的比较基准） */
    private static final Map<UUID, Double> CLIMB_LAST_Y = new HashMap<>();
    /** 女仆 UUID → 爬升相位的目标（敌人 UUID）：换敌人只改写它，不重爬（与扫帚 684 同口径） */
    private static final Map<UUID, UUID> CLIMB_KEY = new HashMap<>();
    /** 女仆 UUID → 战斗盘旋的方位角（弧度） */
    private static final Map<UUID, Double> ORBIT = new HashMap<>();

    /** 爬升到达判定（格）与"顶头"判据（与 MaidBroomDrive 同一组口径） */
    private static final double ARRIVE = 0.35;
    private static final int STALL_TICKS = 20;
    private static final double STALL_MIN_PROGRESS = 0.5;
    /** 盘旋线速度（格/tick）：照搬扫帚的 ORBIT_SPEED（0.14 ≈ 2.8 格/秒），任何半径下都能跟上 */
    private static final double ORBIT_SPEED = 0.14;

    /* ==================== 对外入口 ==================== */

    /** 空战开关（配置 combat.ride.airCombat，默认开）。 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AIR_COMBAT.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 离敌高度（格，默认 15）。玩家原话「至少离敌人要高出15格左右吧」。 */
    public static double airAltCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AIR_ALT.get();
        } catch (Throwable ignored) {
            return 15.0;
        }
    }

    /**
     * 这一拍该飞去哪个点——**接敌爬升**与**战斗盘旋**的合一入口。
     *
     * @return 目标点；{@code null} = 本档不管（调用方照旧按"跟随主人"处理）
     */
    public static Vec3 combatTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !target.isAlive()) {
            return null;
        }
        try {
            // ① 爬升相位未完成 → 返回"爬升点"（水平仍在敌人上方，竖直是爬升目标 Y）
            Double climb = climbTarget(maid, target);
            if (climb != null) {
                double dx = maid.getX() - target.getX();
                double dz = maid.getZ() - target.getZ();
                double horiz = Math.sqrt(dx * dx + dz * dz);
                // 水平保持在敌人外圈一点（别垂直贴脸爬，否则机头对着敌人、总距又抬着，
                // 表现会像"原地拔高"）；距离取"她当前水平距离"与盘旋半径的折中。
                double r = Math.max(6.0, horiz);
                double ang = ORBIT.getOrDefault(maid.getUUID(), phaseOf(maid));
                return new Vec3(target.getX() + Math.cos(ang) * r, climb, target.getZ() + Math.sin(ang) * r);
            }
            // ② 爬升已完成 → 绕着敌人盘旋
            return orbitPoint(maid, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这一场遭遇结束（丢目标 / 下鞍 / 玩家接管）→ 清掉本档所有状态，下一场重新爬。 */
    public static void clear(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        UUID id = maid.getUUID();
        ALT.remove(id);
        CLIMB_TO.remove(id);
        CLIMB_FROM.remove(id);
        CLIMB_STALL.remove(id);
        CLIMB_LAST_Y.remove(id);
        CLIMB_KEY.remove(id);
        ORBIT.remove(id);
        // 与扫帚同口径：这一场遭遇结束 = 随机环绕/接敌机动也重掷（下一场看得见换打法）
        CombatOrbit.forget(id);
        CombatManeuver.forget(id);
    }

    /* ==================== 接敌爬升（形状照搬 MaidBroomDrive.combatClimbTarget） ==================== */

    /**
     * 爬升相位推进：返回这一拍该爬到的 Y；{@code null} = 相位已结束（到位 / 顶头）。
     *
     * <p>与扫帚那边的差别只有**高度来源**：这里 {@code airAlt} 默认 15（玩家点名），
     * 那个是 {@code broom.climb} 默认 12。其余口径（相对敌人、顶头按实际、本场只升不降、
     * 换目标不重爬）逐条一致。
     */
    private static Double climbTarget(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        String key = target.getUUID().toString();
        String cur = CLIMB_KEY.get(id) == null ? null : CLIMB_KEY.get(id).toString();
        if (CLIMB_TO.containsKey(id) && key.equals(cur)) {
            // 相位进行中：看有没有爬到 / 有没有被顶住
            double y = maid.getY();
            Double to = CLIMB_TO.get(id);
            Double from = CLIMB_FROM.get(id);
            if (to == null || from == null) {
                CLIMB_TO.remove(id);
                return null;
            }
            if (y >= to - ARRIVE) {
                // 到位：把这一场遭遇的盘旋高度定下来，并结束相位
                finishClimb(maid, target, from, y, "爬升到位");
                return null;
            }
            // "顶头"判据：连着 STALL_TICKS 拍几乎没长高 + 整段净涨不到 STALL_MIN_PROGRESS
            double last = CLIMB_LAST_Y.getOrDefault(id, y);
            int stall = (y - last < 0.01) ? CLIMB_STALL.getOrDefault(id, 0) + 1 : 0;
            CLIMB_STALL.put(id, stall);
            CLIMB_LAST_Y.put(id, y);
            if (stall >= STALL_TICKS && (y - from) < STALL_MIN_PROGRESS) {
                finishClimb(maid, target, from, y, "头顶被顶住");
                return null;
            }
            return to;
        }
        // 开场 / 换敌人：新遭遇 → 重起相位（高度重新由这一次爬升决定）
        if (cur == null || !key.equals(cur)) {
            double to = target.getY() + airAltCfg() + ARRIVE;
            CLIMB_KEY.put(id, target.getUUID());
            CLIMB_TO.put(id, to);
            CLIMB_FROM.put(id, maid.getY());
            CLIMB_STALL.put(id, 0);
            CLIMB_LAST_Y.put(id, maid.getY());
            ALT.remove(id);
            log(maid, "接敌 → 先爬到它上方 " + fmt(airAltCfg()) + " 格（高度从这以后一直保持）");
            return to;
        }
        return null;
    }

    /** 爬升相位收尾：记下"这一场遭遇的盘旋高度"（本场只升不降），清掉相位状态，留一行日志。 */
    private static void finishClimb(EntityMaid maid, LivingEntity target,
                                    double from, double y, String why) {
        UUID id = maid.getUUID();
        double fixed = Math.max(2.0, Math.min(airAltCfg(), y - target.getY()));
        Double prev = ALT.get(id);
        ALT.put(id, prev != null && prev > fixed ? Math.min(airAltCfg(), prev) : fixed);
        CLIMB_TO.remove(id);
        CLIMB_FROM.remove(id);
        CLIMB_STALL.remove(id);
        CLIMB_LAST_Y.remove(id);
        log(maid, why + "（y " + fmt(from) + " → " + fmt(y) + "，敌上 " + fmt(y - target.getY())
                + " 格）→ 本场盘旋高度 = 敌上 " + fmt(ALT.get(id)) + " 格（本场只升不降）");
    }

    /* ==================== 战斗盘旋（复用扫帚的三套状态机） ==================== */

    /**
     * 绕着敌人转圈打——**半径/旋向/快慢直接复用扫帚模式那三套状态机**（{@link CombatOrbit} 的
     * {@code radius}/{@code direction}/{@code speedScale}/{@code flipSign} + {@link CombatManeuver}），
     * 所以"套用扫帚模式逻辑"是字面意义的同一段抽签与缓动。
     *
     * <p>高度取 {@link #ALT}（本场遭遇爬到的那个高度）；没有记录时退回配置的 {@code airAlt}。
     */
    private static Vec3 orbitPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        double base = Math.max(6.0, rangeCfg());
        double hi = Math.max(base, orbitMaxCfg());
        double lo = Math.max(minStandoffCfg(), Math.min(hi, base * 0.75));
        if (lo > hi) {
            lo = hi;
        }
        double r = Math.max(0.5, CombatOrbit.radius(id, lo, hi));
        double dir = CombatOrbit.direction(id) * CombatOrbit.flipSign(id);
        double spd = CombatOrbit.speedScale(id);
        boolean maneuverOn = maneuverEnabled();
        if (maneuverOn) {
            CombatManeuver.begin(id);
            CombatManeuver.tick(id);
        } else {
            CombatManeuver.forget(id);
        }
        if (maneuverOn) {
            r = Math.max(lo, Math.min(hi, r * CombatManeuver.radiusScale(id)));
        }
        double mAngle = maneuverOn ? CombatManeuver.angleScale(id) : 1.0;
        double mRev = maneuverOn ? CombatManeuver.reversal(id) : 1.0;
        double ang = ORBIT.getOrDefault(id, phaseOf(maid)) + dir * mRev * ORBIT_SPEED * spd * mAngle / r;
        ORBIT.put(id, ang);
        double alt = ALT.getOrDefault(id, airAltCfg());
        if (maneuverOn) {
            alt += CombatManeuver.heightAdd(id, yoyoAmpCfg());
        }
        return new Vec3(target.getX() + Math.cos(ang) * r,
                target.getY() + alt,
                target.getZ() + Math.sin(ang) * r);
    }

    /* ==================== 配置读取（异常一律给安全默认） ==================== */

    private static double rangeCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_RANGE.get();
        } catch (Throwable ignored) {
            return 8.0;
        }
    }

    private static double orbitMaxCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_ORBIT_MAX.get();
        } catch (Throwable ignored) {
            return 10.0;
        }
    }

    private static double minStandoffCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_MIN_STANDOFF.get();
        } catch (Throwable ignored) {
            return 6.0;
        }
    }

    private static boolean maneuverEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_MANEUVER_ENABLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static double yoyoAmpCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_MANEUVER_YOYO_AMP.get();
        } catch (Throwable ignored) {
            return 3.0;
        }
    }

    /* ==================== 小工具 ==================== */

    /** 每只女仆一个稳定的盘旋相位（与扫帚同源：UUID 派生，同一只永远同一个值）。 */
    private static double phaseOf(EntityMaid maid) {
        try {
            return ((maid.getUUID().hashCode() & 0xFFFF) / 65536.0) * (Math.PI * 2.0);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 低频留痕（同坐骑 5 秒一条上限，与全工程一致）——日志搜「空战」。 */
    private static final Map<UUID, Long> LOG_AT = new HashMap<>();

    private static void log(EntityMaid maid, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = LOG_AT.get(maid.getUUID());
            if (last != null && now - last < 5000L) {
                return;
            }
            if (LOG_AT.size() > 256) {
                LOG_AT.clear();
            }
            LOG_AT.put(maid.getUUID(), now);
            com.maidsmart.tool.PromaidLog.log("空战", com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) {
        }
    }
}
