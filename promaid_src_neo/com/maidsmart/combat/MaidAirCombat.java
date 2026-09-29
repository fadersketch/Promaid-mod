package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百二十七·点1 + 实测七百二十八【骑飞行载具：跟随离地悬停、接敌升到敌上俯射】。
 *
 * <h2>玩家原话（两次）</h2>
 * <p>第一次（727）：「女仆似乎不会让直升机悬停。而且在打精英敌人进行绕圈的时候，总是范围绕的特别大，
 *  而且高度很低。导致实际的命中率非常堪忧。最好是采用跟扫帚一样的机制，骑上直升机之后就进入悬停状态，
 *  离地三格左右。随后的盘旋也是悬停在同一高度盘旋。」
 * <p>第二次（728，**补正**）：「我是说跟随的时候保持离地三格的……但是遇到敌人还是要升高到比敌人高
 *  15 格的位置的呀，同时缩小绕圈的半径。」
 *
 * <h2>七百二十七 那一版把两档混成了一档</h2>
 * <p>727 把"离地三格"套到了**所有**情形上（连接敌也一起改成离地三格），于是：
 * <ul>
 *   <li><b>跟随时</b>离地三格 —— 这是玩家要的，对；</li>
 *   <li><b>接敌时</b>也离地三格 —— 这是玩家 728 要修回来的：他要接敌时**升到敌人上方 15 格**高位俯射。</li>
 * </ul>
 * 本版把两档拆开：**跟随 = 离地 {@link #followAltCfg}（默认 3）**、**接敌 = 敌上 {@link #fightAltCfg}
 * （默认 15）**，半径再收（默认 4）。两档互不影响。
 *
 * <h2>七百二十六 那一版又错在哪（实机日志 + 反编译双实证）</h2>
 * <ol>
 *   <li><b>"爬到敌人上方 15 格"被写进了俯仰</b>：直升机的**高度只由总距与悬停开关控制**
 *       （反编译 {@code VehicleEngineUtils.helicopterEngine} 实证——没有竖直轴输入），而俯仰
 *       决定她往哪飞。726 把"比敌人高 15 格"翻译成"机头朝目标方向"，一旦敌人比她低很多
 *       （实机日志 `高差=-24`），俯仰就把机头压向地面 → 越飞越低、贴地乱窜。</li>
 *   <li><b>半径借了扫帚那套 → 圈特别大</b>：{@code CombatOrbit.radius} 的区间是
 *       {@code range(8) × 0.75 ~ orbitMax(10)}，再乘 {@code CombatManeuver.radiusScale}
 *       （蛇形/脱离再进还会放大）→ 实际能绕到 10 格以上。</li>
 * </ol>
 *
 * <h2>本版口径（逐条对应玩家两次的话）</h2>
 * <ul>
 *   <li><b>"跟随的时候保持离地三格"</b>：没有敌人时，目标高度 = 她**脚下的地面** +
 *       {@code airAlt}（默认 3）。由 {@link #followTarget} / {@link #hoverAt} 给出。</li>
 *   <li><b>"遇到敌人还是要升高到比敌人高 15 格"</b>：有敌人时，目标高度 = **敌人所在位置** +
 *       {@code fightAlt}（默认 15），盘旋点也在这个高度上——由 {@link #combatTarget} 给出。
 *       <b>关键区别</b>：这个高度进的是**目标点**（总距升到位），不是俯仰——所以不管敌人比她
 *       高还是低，机头都不会被压向地面，她能真正稳在敌上方（这是 726 栽的那个坑）。</li>
 *   <li><b>"缩小绕圈的半径"</b>：半径改成独立的 {@code combat.ride.orbitRadius}（默认 4），
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

    /** **跟随**时的悬停高度（离地格数，默认 3）——玩家原话「跟随的时候保持离地三格」。 */
    public static double followAltCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AIR_ALT.get();
        } catch (Throwable ignored) {
            return 3.0;
        }
    }

    /**
     * **接敌**时的爬升高度（比敌人高多少格，默认 15）——玩家原话「遇到敌人还是要升高到比敌人
     * 高 15 格的位置的呀」。
     */
    public static double fightAltCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FIGHT_ALT.get();
        } catch (Throwable ignored) {
            return 15.0;
        }
    }

    /** 盘旋半径（格，默认 4）——玩家两次都在说圈太大 / 要缩小。 */
    public static double orbitRadiusCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_ORBIT_RADIUS.get();
        } catch (Throwable ignored) {
            return 4.0;
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

    /**
     * 她此刻**在接敌吗**（有活着的、同维度的敌人）——{@code MaidMountCompat.driveFlight} 用它
     * 决定"该用跟随档（离地）还是接敌档（敌上）"。
     *
     * <p>【为什么飞行档自己也要问这一句】727 的悬停档是"只要开着就常驻"，高度也一直按离地算，
     * 于是接敌时也压在三格高。728 起高度分两档，飞行档必须知道此刻是哪一档才写得对总距。
     */
    public static boolean inCombat(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            LivingEntity foe = targetOf(maid);
            return foe != null && foe.isAlive() && foe.level() == maid.level();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她当前的活目标（与 {@code RideBindManager.targetOf} 同口径的轻量版，避免反向依赖）。 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            return maid.getTarget();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 悬停点（"骑上就停在这"） ==================== */

    /**
     * 她此刻**该悬停在哪**（**跟随档**）——水平就是她现在的位置，竖直是「她脚下的地面 +
     * {@link #followAltCfg}」。
     *
     * <p>玩家原话「骑上直升机之后就进入悬停状态，离地三格左右」+ 728 补正「我是说跟随的时候
     * 保持离地三格的」。所以这一条**只看地面、不看敌人**：有敌人时改走 {@link #combatTarget}
     * （那时高度按敌上算，见 {@link #fightAltCfg}）。
     *
     * @return 悬停点；世界/异常拿不到 → {@code null}（调用方照旧按跟随链路处理）
     */
    public static Vec3 followTarget(EntityMaid maid) {
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
     * 悬停在指定水平坐标的上方（高度仍是「该处地面 + {@link #followAltCfg}」）。
     *
     * <p>{@code RideBindManager} 在没有敌人时用它做**低空跟随**：主人走远了就把目标点放在
     * 主人正上方、高度仍锁在离地 N 格——既保留"跟着主人"，又满足"离地三格左右、不再贴地乱窜"。
     *
     * @return 目标点；拿不到世界 → {@code null}
     */
    public static Vec3 hoverAt(EntityMaid maid, double x, double z) {
        try {
            if (maid == null) {
                return null;
            }
            double y = groundY(maid, x, z) + followAltCfg();
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
     * 这一拍该飞去哪个点——**水平**绕着敌人转圈，**竖直**在敌人上方 {@link #fightAltCfg} 格。
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
     * 绕着敌人转圈打（**高度 = 敌上 {@link #fightAltCfg} 格**，用总距升到位、不用俯仰）。
     *
     * <p>【实测七百二十八·玩家补正】「遇到敌人还是要升高到比敌人高 15 格的位置的呀」——
     * 所以这一档高度**以敌人为基准**，与跟随档（以地面为基准）分开。
     *
     * <p>【与 726 的关键区别】726 把"比敌人高 15 格"翻成"机头朝目标"，敌人一旦在她脚下很多，
     * 俯仰就把机头压向地面 → 越飞越低（实机日志 `高差=-24`）。本版把它放进**目标点 Y**，
     * 由 {@code driveFlight} 的总距逻辑升降，姿态不参与高度控制，所以她能真正稳在敌上方。
     *
     * <h2>【实测七百二十八·为什么旧版的圈"特别大"】——"胡萝卜"必须挂在她**当前方位的前方**</h2>
     * 旧版返回的是"她上一次那个方位角再往前 0.14 格"的点。可 {@code driveFlight} 的距离死区
     * {@code FLIGHT_ARRIVE = 3.0}：目标点只领先 0.14 格时它**根本不给俯仰**（{@code flatDist = 0}）
     * → 她原地不动；目标点自己往前跑，等拉开超过 3 格才重新给油。于是**稳态滞后 ≈ 3~6 格**，
     * 实际半径 = 配置半径 + 滞后 ≈ 10 格以上——这正是玩家两次都在说的"范围绕的特别大"。
     *
     * <p>本版改成**追逐式胡萝卜**：胡萝卜永远挂在她**当前实际方位角**再往前 {@code LEAD} 格
     * （{@code LEAD = 基础值 × 快慢倍率}，恒 &gt; 死区），于是
     * <ul>
     *   <li>稳态滞后被 LEAD 吸收、不再叠加到半径上 → 她的半径**就是** {@link #orbitRadiusCfg}；</li>
     *   <li>她若飞到圈外/圈内，胡萝卜在半径 r 的圆上，会把她径向拉回来（自纠正）；</li>
     *   <li>旋向（{@link CombatOrbit#direction} × {@link CombatOrbit#flipSign}，8 秒对半换向）与
     *       快慢（{@link CombatOrbit#speedScale}，3 秒重掷）照旧生效——只是快慢改的是 LEAD，
     *       不再改"绕多大"。"跟扫帚一样的机制"体现在随机性同源。</li>
     * </ul>
     */
    private static Vec3 orbitPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        double r = Math.max(1.5, orbitRadiusCfg());
        double dir = CombatOrbit.direction(id) * CombatOrbit.flipSign(id);
        double spd = CombatOrbit.speedScale(id);
        // LEAD：胡萝卜领先她当前方位的弧长。两个约束同时满足：
        //   ① 必须**大于 driveFlight 的距离死区**（FLIGHT_ARRIVE = 3.0），否则"贴到点了就不
        //      给油"→ 又退回旧版的稳态滞后（圈变大）。所以下限 {#MIN_LEAD} = 4.0。
        //   ② 角偏移 δ = LEAD / r 不能太大——driveFlight 在偏航误差 ≥ 60° 时会"只转向、不给
        //      俯仰"（turning 档）。弦方向与切向的夹角 = δ/2，所以取 LEAD ≈ 0.85r 让 δ/2 稳在
        //      60° 以内（最极端 r=2 时 δ/2 ≈ 57°）。乘 spd 让"绕得快"体现为胡萝卜走得更快。
        double lead = Math.max(MIN_LEAD, r * 0.85 * Math.max(0.5, spd));
        // 她**当前**相对敌人的方位角（不是上一次的目标角）——胡萝卜就挂在这条射线上往前。
        double angCur = Math.atan2(maid.getZ() - target.getZ(), maid.getX() - target.getX());
        double ang = angCur + dir * (lead / r);     // 沿她当前的切线方向再往前 lead 格
        // 高度：**敌人所在位置之上** fightAlt 格（玩家 728 补正原话），不再用离地高度。
        // 用敌人"脚底"而不是"眼睛"：她比敌人高 N 格时是整体高出，俯射角自然成立。
        double y = target.getY() + fightAltCfg();
        // 这一场遭遇起手留一行（与扫帚的「接敌机动」同款：每场一行、不走节流）；
        // 排查时搜「空战」看的就是它。
        if (firstOrbit(id)) {
            log(maid, "接敌 → 爬到敌上 " + fmt(fightAltCfg()) + " 格、绕着敌人盘旋（半径 " + fmt(r)
                    + " 格，旋向 " + (dir > 0 ? "逆时针" : "顺时针") + "）");
        }
        return new Vec3(target.getX() + Math.cos(ang) * r, y, target.getZ() + Math.sin(ang) * r);
    }

    /** 胡萝卜领先的下限弧长（格）——见 {@link #orbitPoint}。必须大于 {@code FLIGHT_ARRIVE}。 */
    private static final double MIN_LEAD = 4.0;

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
        ORBIT_FIRST.remove(id);
        // 与扫帚同口径：这一场遭遇结束 = 随机环绕的抽签也重掷（下一场看得见换旋向/换快慢）
        CombatOrbit.forget(id);
        CombatManeuver.forget(id);
    }

    /* ==================== 小工具 ==================== */

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
