package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百二十七·点1【骑飞行载具：上机即悬停、盘旋保持同一高度】。
 *
 * <h2>玩家原话</h2>
 * 「女仆似乎不会让直升机悬停。而且在打精英敌人进行绕圈的时候，总是范围绕的特别大，而且高度很低。
 *  导致实际的命中率非常堪忧。最好是采用跟扫帚一样的机制，骑上直升机之后就进入悬停状态，离地三格
 *  左右。随后的盘旋也是悬停在同一高度盘旋。」
 *
 * <h2>七百二十六 那一版错在哪（实机日志 + 反编译双实证）</h2>
 * <ol>
 *   <li><b>"爬到敌人上方 15 格"会把她压到地面</b>：直升机的**高度只由总距与悬停开关控制**
 *       （反编译 {@code VehicleEngineUtils.helicopterEngine} 实证——没有竖直轴输入），而俯仰
 *       决定她往哪飞。七百二十六 写的是"爬到敌人**上方** 15 格"，一旦敌人比她低很多
 *       （实机日志 `高差=-24`），俯仰就把机头压向地面 → 越飞越低、贴地乱窜。</li>
 *   <li><b>半径借了扫帚那套 → 圈特别大</b>：{@code CombatOrbit.radius} 的区间是
 *       {@code range(8) × 0.75 ~ orbitMax(10)}，再乘 {@code CombatManeuver.radiusScale}
 *       （蛇形/脱离再进还会放大）→ 实际能绕到 10 格以上。玩家要的是"直升机悬停着打"，
 *       圈要小、要稳。</li>
 * </ol>
 *
 * <h2>本版口径（逐条对应玩家的话）</h2>
 * <ul>
 *   <li><b>"骑上就进入悬停状态，离地三格左右"</b>：不在"接敌"时才管，而是**只要她骑着飞行
 *       载具就锁定高度**——目标高度 = 她**脚下的地面** + {@code airAlt}（默认 3）。这件事由
 *       {@link MaidMountCompat#driveFlight} 每拍用总距做（它是唯一能写总距/悬停开关的地方），
 *       本类只提供 {@link #hoverTarget} 这个"该停在哪"的点。</li>
 *   <li><b>"随后的盘旋也是悬停在同一高度盘旋"</b>：盘旋点的高度 = 同一个 {@link #hoverTarget}
 *       给出的绝对 Y，**不再随敌人上下浮动**（旧版是"敌人脚底 + N"。敌人一跑高跑低，她就跟着
 *       上下扎——正是玩家说的"在地上和高空5个左右跳动"）。</li>
 *   <li><b>"范围绕的特别大"</b>：半径改成独立的 {@code combat.ride.orbitRadius}（默认 6），
 *       不再乘 {@code CombatManeuver.radiusScale}；旋向/快慢仍复用 {@link CombatOrbit}
 *       （"跟扫帚一样的机制"——同一段状态机，只是半径口径独立且不放大）。</li>
 * </ul>
 *
 * <p><b>关于 {@code CombatManeuver}</b>：本版**不接**它（那是扫帚的"蛇形/脱离再进/8字横切"，
 * 每一种都要求机身有真实的加速度换向能力；直升机是悬停+侧移，套上去只会让圈忽大忽小、
 * 高度忽高忽低——正是玩家抱怨的那两件事）。{@link CombatOrbit} 的半径/旋向/快慢三件仍然用，
 * 所以"跟扫帚一样"体现在**机制同源**，不是照抄它那套机动。
 */
public final class MaidAirCombat {

    private MaidAirCombat() {
    }

    /* ==================== 状态表（按女仆 UUID，异常一律吞掉） ==================== */

    /** 女仆 UUID → 战斗盘旋的方位角（弧度） */
    private static final Map<UUID, Double> ORBIT = new HashMap<>();

    /** 盘旋线速度（格/tick）：绕圈时每拍沿切向走多远（换算成角速度见 {@link #orbitPoint}）。 */
    private static final double ORBIT_SPEED = 0.14;

    /** 悬停高度（离地格数，默认 3）——玩家原话「离地三格左右」。 */
    public static double hoverAltCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AIR_ALT.get();
        } catch (Throwable ignored) {
            return 3.0;
        }
    }

    /** 盘旋半径（格，默认 6）——玩家反馈旧版「范围绕的特别大」，所以独立一个旋钮。 */
    public static double orbitRadiusCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_ORBIT_RADIUS.get();
        } catch (Throwable ignored) {
            return 6.0;
        }
    }

    /** 悬停/盘旋总开关（配置 combat.ride.airCombat，默认开）。 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AIR_COMBAT.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 悬停点（"骑上就停在这"） ==================== */

    /**
     * 她此刻**该悬停在哪**——水平就是她现在的位置，竖直是「她脚下的地面 + {@link #hoverAltCfg}」。
     *
     * <p>玩家原话「骑上直升机之后就进入悬停状态，离地三格左右」。所以这一条**不看敌人**：
     * 没有目标时她停在自己头顶、有目标时（见 {@link #combatTarget}）也只是水平去绕圈，
     * 高度仍是这一个数——这就是玩家要的"盘旋也是悬停在同一高度盘旋"。
     *
     * @return 悬停点；世界/异常拿不到 → {@code null}（调用方照旧按跟随链路处理）
     */
    public static Vec3 hoverTarget(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            return hoverAt(maid, maid.getX(), maid.getZ());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 悬停在指定水平坐标的上方（高度仍是「该处地面 + {@link #hoverAltCfg}」）。
     *
     * <p>{@code RideBindManager} 在没有敌人时用它做**低空跟随**：主人走远了就把目标点放在
     * 主人正上方、高度仍锁在离地 N 格——既保留"跟着主人"（玩家 719 起就有的行为），
     * 又满足"离地三格左右、不再贴地乱窜"。
     *
     * @return 目标点；拿不到世界 → {@code null}
     */
    public static Vec3 hoverAt(EntityMaid maid, double x, double z) {
        try {
            if (maid == null) {
                return null;
            }
            double y = groundY(maid, x, z) + hoverAltCfg();
            return new Vec3(x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 指定水平坐标**脚下**的地面高度（格）——从她当前 Y 往下探到第一个"站得住"的格子。
     *
     * <p>为什么不是 {@code level.getHeight}：她可能正悬在一座桥/树冠/山坡上方，也可能在山洞里
     * （那时 {@code getHeight} 给她的是**洞顶外面**的地表高度，会把她往天花板里推）。所以这里
     * 逐格往下扫，取第一个**碰撞形状非空**的方块顶面——她在洞里就按洞底算，在桥上也按桥面算。
     *
     * <p>扫描上限 {@link #GROUND_SCAN} 格；一路扫不到（悬在虚空上）就退回她当前 Y
     * （宁可原地悬停，也不往虚空里扎）。
     */
    private static double groundY(EntityMaid maid, double x, double z) {
        try {
            net.minecraft.world.level.Level level = maid.level();
            if (level == null) {
                return maid.getY();
            }
            net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
            int bx = net.minecraft.util.Mth.floor(x);
            int bz = net.minecraft.util.Mth.floor(z);
            int from = net.minecraft.util.Mth.floor(maid.getY());
            for (int dy = 0; dy <= GROUND_SCAN; dy++) {
                int y = from - dy;
                if (level.isOutsideBuildHeight(y)) {
                    break;
                }
                p.set(bx, y, bz);
                net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
                if (st.isAir()) {
                    continue;
                }
                // 该格"她站得住"= 碰撞形状非空（草丛/火把/雪这类无碰撞方块不算地面）
                if (!st.getCollisionShape(level, p,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty()) {
                    return y + 1.0; // 方块顶面
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.getY();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 往下探地形的最大格数（再深就不像"脚下"了，按原地悬停处理）。 */
    private static final int GROUND_SCAN = 24;

    /* ==================== 盘旋点（水平绕圈，高度不变） ==================== */

    /**
     * 这一拍该飞去哪个点——**水平**绕着敌人转圈，**竖直**保持悬停高度。
     *
     * @return 目标点；{@code null} = 本档不管（调用方照旧按"跟随主人"处理）
     */
    public static Vec3 combatTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !target.isAlive()) {
            return null;
        }
        try {
            return orbitPoint(maid, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 绕着敌人转圈打（**高度锁定**，不随敌人上下浮动）。
     *
     * <p>半径 = {@link #orbitRadiusCfg}（配置值本身带随机化的只有旋向与角速度，见下）；
     * 旋向与快慢复用 {@link CombatOrbit} 的三套状态机（"跟扫帚一样的机制"）。
     * <b>不乘 {@code CombatManeuver.radiusScale}</b>——那一层是扫帚的接敌机动，会把半径放大，
     * 正是玩家说的"范围绕的特别大"。
     */
    private static Vec3 orbitPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        double r = Math.max(1.5, orbitRadiusCfg());
        // 旋向（一半逆时针一半顺时针、每 8 秒对半概率掉头）与快慢（3 秒重掷）照旧借扫帚那套——
        // 这两项只改"怎么绕"，不改"绕多大"，所以不会把圈放大。
        double dir = CombatOrbit.direction(id) * CombatOrbit.flipSign(id);
        double spd = CombatOrbit.speedScale(id);
        // 角速度由固定线速度换算：任何半径下她都能跟上这个点（同扫帚口径）
        double ang = ORBIT.getOrDefault(id, phaseOf(maid)) + dir * ORBIT_SPEED * spd / r;
        ORBIT.put(id, ang);
        // 高度：**同一高度**，与敌人无关（玩家原话「盘旋也是悬停在同一高度盘旋」）
        double y = groundY(maid, maid.getX(), maid.getZ()) + hoverAltCfg();
        // 这一场遭遇起手留一行（与扫帚的「接敌机动」同款：每场一行、不走节流）；
        // 排查时搜「空战」看的就是它。
        if (firstOrbit(id)) {
            log(maid, "接敌 → 绕着敌人盘旋（半径 " + fmt(r) + " 格、锁定离地 "
                    + fmt(hoverAltCfg()) + " 格，旋向 " + (dir > 0 ? "逆时针" : "顺时针") + "）");
        }
        return new Vec3(target.getX() + Math.cos(ang) * r, y, target.getZ() + Math.sin(ang) * r);
    }

    /** 这一场遭遇里"她是不是第一次绕到这个圈上"（用于每场只写一行「接敌」）。 */
    private static boolean firstOrbit(UUID id) {
        try {
            return ORBIT_FIRST.add(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本场遭遇是否已经写过"接敌"那一行（{@link #clear} 时连同别的状态一起清）。 */
    private static final java.util.Set<UUID> ORBIT_FIRST = new java.util.HashSet<>();

    /** 这一场遭遇结束（丢目标 / 下鞍 / 玩家接管）→ 清掉本档状态。 */
    public static void clear(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        UUID id = maid.getUUID();
        ORBIT.remove(id);
        ORBIT_FIRST.remove(id);
        // 与扫帚同口径：这一场遭遇结束 = 随机环绕的抽签也重掷（下一场看得见换旋向/换快慢）
        CombatOrbit.forget(id);
        CombatManeuver.forget(id);
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

    public static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 低频留痕（同坐骑 5 秒一条上限，与全工程一致）——日志搜「空战」。 */
    private static final Map<UUID, Long> LOG_AT = new HashMap<>();

    static void log(EntityMaid maid, String msg) {
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
