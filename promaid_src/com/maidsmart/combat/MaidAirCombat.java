package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
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
 *   <li><b>"跟随的时候保持离地三格"→ 七百三十三 更正为"比主人高三格"</b>：没有敌人时，目标高度 =
 *       **主人的 Y** + {@code airAlt}（默认 3）。由 {@link #hoverAt} 给出。
 *       基准**只有主人这一个来源**——七百三十四 修掉了"拿她自己高度当基准"导致的棘轮。</li> *   <li><b>"遇到敌人还是要升高到比敌人高 15 格"</b>：有敌人时，目标高度 = **敌人所在位置** +
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

    /**
     * v1.3.0(beta) 实测七百四十七【投弹安全高度】——她骑着飞行载具**要往下投弹**时，
     * 必须先把机身升到**比目标高这么多格**（默认 20）。
     *
     * <h2>玩家原话</h2>
     * 「女仆在乘坐基洛夫空艇时，如果要进行投放炸药，那么要先自己向上飞 20 格，防止被炸到。」
     *
     * <h2>为什么单独一个旋钮、而不是直接调「接敌高度」</h2>
     * 接敌高度（默认 15）管的是**所有**接敌站位——抬到 20 会让坦克/直升机这些不投弹的载具
     * 也一起飞得更高，没必要。只有"这一下有投弹意图"（见 {@link #hasBombIntent}）时才把
     * 基准抬到本项。所以两档的关系是 {@code 实际高度 = max(接敌高度, 本项)}，
     * 而本项默认更大 → 投弹时以本项为准。见 {@link #requiredAbove}。
     *
     * <p>0 = 关掉这道闸（照旧想投就投）。
     */
    public static double bombStandoffCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_BOMB_STANDOFF.get();
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    /**
     * 这一拍她**是不是要往下投弹**（决定要不要按 {@link #bombStandoffCfg} 抬高度）。
     *
     * <p>两条路任一成立即算：
     * <ol>
     *   <li><b>车上那一门是炸弹</b>：基洛夫空艇的唯一武器就叫 {@code Bomb}（SWB 数据实证），
     *       它往下丢的那颗航空炸弹爆炸半径极大——贴地丢等于把自己也圈进爆心。</li>
     *   <li><b>她自己的轰炸链路有料</b>：她背包里带着 TNT / 末地水晶 / 重生锚 / 床
     *       （{@code BombItems} 那几条判据），也就是"接下来几拍她可能就地放一发"。</li>
     * </ol>
     *
     * <p>【为什么不是"永远按 20 飞"】不投弹的时候（比如她只是开直升机护航、用车上的机炮打）
     * 抬到 20 会白白丢掉命中率——所以这一档只在**真有投弹意图**时生效。
     *
     * <p>【节流】第二条要扫她的背包（4 次全槽扫描），而本方法被高度那几处**每拍**问到，
     * 所以结果按女仆缓存 {@link #BOMB_INTENT_TICKS} 拍。材料几拍内不会凭空出现/消失，
     * 这个粒度足够，代价从"每拍 4 次扫包"降到"半秒 4 次"。
     */
    private static boolean hasBombIntent(EntityMaid maid) {
        try {
            UUID id = maid.m_20148_();
            long now = maid.m_9236_().m_46467_();
            long[] c = BOMB_INTENT_CACHE.get(id);
            if (c != null && now - c[1] < BOMB_INTENT_TICKS) {
                return c[0] != 0L;
            }
            boolean hit = vehicleGunIsBomb(maid) || carriesBombPayload(maid);
            if (BOMB_INTENT_CACHE.size() > 512) {
                BOMB_INTENT_CACHE.clear();
            }
            BOMB_INTENT_CACHE.put(id, new long[]{hit ? 1L : 0L, now});
            return hit;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "要不要投弹"的缓存（女仆 UUID → {0/1, 上次算的 gameTime}）。 */
    private static final Map<UUID, long[]> BOMB_INTENT_CACHE = new HashMap<>();

    /** 上面那个缓存的存活拍数（10 拍 = 0.5 秒）。 */
    private static final long BOMB_INTENT_TICKS = 10L;

    /** 车上**当前选中的那门炮**是不是炸弹（基洛夫唯一武器 = Bomb）。 */
    private static boolean vehicleGunIsBomb(EntityMaid maid) {
        try {
            Entity mount = maid.m_20202_();
            if (mount == null || !MaidMountCompat.isVehicle(mount)) {
                return false;
            }
            String gun = MaidMountCompat.gunNameFor(mount, maid);
            return gun != null && gun.toLowerCase(java.util.Locale.ROOT).contains("bomb");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她背包里是不是带着能放的炸料（TNT / 末地水晶 / 重生锚 / 床）。 */
    private static boolean carriesBombPayload(EntityMaid maid) {
        try {
            return BombItems.hasTnt(maid)
                    || BombItems.has(maid, BombItems.ID_END_CRYSTAL)
                    || BombItems.has(maid, BombItems.ID_RESPAWN_ANCHOR)
                    || BombItems.hasBed(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这一拍"该比目标高多少格" = {@code max(接敌高度, 投弹安全高度)}，
     * 其中投弹安全高度只在 {@link #hasBombIntent} 成立时并进来。
     *
     * <p>没有投弹意图时它**恒等于** {@link #fightAltCfg}——所以七百二十八 那套接敌口径
     * 一个字节都没变，这一项只是给它加了一个"投弹时更高的下限"。
     */
    private static double requiredAbove(EntityMaid maid) {
        double base = fightAltCfg();
        double stand = bombStandoffCfg();
        if (stand > base && hasBombIntent(maid)) {
            return stand;
        }
        return base;
    }

    /**
     * v1.3.0(beta) 实测七百四十七【投弹安全高度闸：没爬到位就不许投】。
     *
     * <p>给"投弹的那几路"（车上的炸弹武器 {@code MaidMountCompat.tickAttack}、她自己的
     * 轰炸链路 {@code BombTntTick} / {@code MaidBombing.tryStartMelee}）共用的一问：
     * 这一拍该不该**按住这一发**。
     *
     * <p>为 true 时调用方直接跳过本次投弹（不消耗材料、不占冷却），而高度那一边由
     * {@link #requiredAbove} 把她往 {@code 目标Y + bombStandoffCfg} 抬——两边配合起来
     * 就是玩家要的"先自己向上飞 20 格，再投"。
     *
     * <p>不受本闸管的四种情形（任一成立即返回 false = 照常投）：
     * <ul>
     *   <li>没骑飞行载具 / 没装卓越前线（闸只对能飞的载具生效）；</li>
     *   <li>「骑飞行载具·悬停与盘旋」总开关关着（那就没人负责把她抬上去，按住投弹等于
     *       永久不投——宁可照旧投，也不能让她僵住）；</li>
     *   <li>安全高度填 0（玩家自己关掉了这道闸）；</li>
     *   <li>没有可投的目标，或她已经爬到位。</li>
     * </ul>
     */
    public static boolean holdDropForStandoff(EntityMaid maid, LivingEntity target) {
        try {
            if (maid == null || !enabled()) {
                return false;
            }
            double stand = bombStandoffCfg();
            if (stand <= 0.0) {
                return false; // 玩家关了这道闸
            }
            Entity mount = maid.m_20202_();
            if (mount == null || !MaidMountCompat.isFlyingVehicle(mount)) {
                return false; // 只对飞行载具生效（地面车没有"爬升"这回事）
            }
            if (target == null || !target.m_6084_()) {
                return false; // 没目标：交给调用方自己的"没目标就跳过"
            }
            double want = target.m_20186_() + stand;
            double have = maid.m_20186_();
            if (have >= want - STANDOFF_TOL) {
                return false; // 已到位
            }
            if (firstHold(maid.m_20148_())) {
                log(maid, "投弹安全高度未到（现在 " + fmt(have) + "，目标上 " + fmt(stand)
                        + " 格 = " + fmt(want) + "）→ 先爬升、这一发按住");
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 到位容差（格）：离"目标上 N 格"进这个带就算到位、可以投了。 */
    private static final double STANDOFF_TOL = 1.5;

    /** 这一场遭遇里"她是不是第一次因为安全高度被按住"（每场只写一行，免得刷屏）。 */
    private static boolean firstHold(UUID id) {
        try {
            return HOLD_FIRST.add(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本场遭遇是否已经写过"安全高度未到"那一行（{@link #clear} 时清）。 */
    private static final java.util.Set<UUID> HOLD_FIRST = new java.util.HashSet<>();

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
            return foe != null && foe.m_6084_() && foe.m_9236_() == maid.m_9236_();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她当前的活目标（与 {@code RideBindManager.targetOf} 同口径的轻量版，避免反向依赖）。 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            return maid.m_5448_();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 悬停点（"骑上就停在这"） ==================== */

    /**
     * 悬停在指定水平坐标的上方，高度 = {@code baseY + {@link #followAltCfg}}。
     *
     * <p>{@code RideBindManager} 在没有敌人时用它做**跟随**：主人走远了就把目标点放在
     * 主人正上方（{@code x/z = 主人}），主人就在旁边就守住她当前水平位置（{@code x/z = 她}）。
     * <b>两种情况 {@code baseY} 都传主人的 Y</b>——高度基准只有这一个来源。
     *
     * <p>【实测七百三十四·棘轮修正】曾经有过一个"原地悬停"的重载，把基准传成**她自己当前的 Y**：
     * 于是目标 = 她现在的高度 + 3，她每贴到一次就被抬高 3 格、目标跟着水涨船高，形成**每拍 +3 的
     * 棘轮**，几秒就窜到主人上方十几二十格（玩家实机反馈）。所以本版**只保留这一个入口**，
     * 收的 {@code baseY} 语义就是"参照物的高度"，调用方一律传主人的 Y。
     *
     * @param baseY 高度参照（**主人**的 Y；绝不传她自己的 Y）
     * @return 目标点；拿不到世界 → {@code null}
     */
    public static Vec3 hoverAt(EntityMaid maid, double x, double z, double baseY) {
        try {
            if (maid == null) {
                return null;
            }
            return new Vec3(x, baseY + followAltCfg(), z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 盘旋点（水平绕圈，高度不变） ==================== */

    /**
     * 这一拍该飞去哪个点——**水平**绕着敌人转圈，**竖直**在敌人上方 {@link #fightAltCfg} 格。
     *
     * @return 目标点；{@code null} = 本档不管（调用方照旧按"跟随主人"处理）
     */
    public static Vec3 combatTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !target.m_6084_()) {
            return null;
        }
        try {
            // 【实测七百三十一·点2】"先上升、再盘旋"——玩家原话「遇到敌人之后，先上升，然后再盘旋」。
            // 实机日志实证（promaid.log 05:51~05:53）：接敌档里 {@code 高差} 每 5 秒就在
            // {@code 11 → 0 → -11} 之间横跳，因为她**一边爬升一边绕圈**——绕到另一侧时目标点
            // 的 Y 没变但她自己在动，高度环与水平环互相抢输入，永远爬不到位。
            //
            // 本版加**硬高度闸**：她现在离"敌上 fightAlt 格"还差得远（且不是已经飞过头）时，
            // 水平目标点直接取**她自己当前位置的上方**——先原地爬到高度，再交给盘旋。
            // 这样高度环独占输入、几步就位；到位后自动切回绕圈。
            if (!altitudeReady(maid, target)) {
                return climbPoint(maid, target);
            }
            return orbitPoint(maid, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 进入盘旋前必须爬到的高度容差（格）：离"敌上 N 格"进这个带就认为高度到位、开始绕圈。 */
    private static final double CLIMB_TOL = 2.5;

    /** 她此刻是否已经爬到"敌上 {@link #fightAltCfg} 格"附近（可以开始盘旋了）。 */
    private static boolean altitudeReady(EntityMaid maid, LivingEntity target) {
        try {
            double want = target.m_20186_() + requiredAbove(maid);
            return Math.abs(maid.m_20186_() - want) <= CLIMB_TOL;
        } catch (Throwable ignored) {
            return true; // 拿不到就当作已就位，退回旧行为
        }
    }

    /**
     * 【实测七百三十一·点2】爬升点：水平**原地**、竖直指向"敌上 {@link #fightAltCfg} 格"。
     * 每场遭遇只写一行「先爬升」留痕（搜「空战」），与盘旋那一行区分。
     *
     * <p>【实测七百四十七】高度改用 {@link #requiredAbove}——她在**要投弹**时（基洛夫等）
     * 基准抬到"投弹安全高度"（默认 20），所以这里写出来的目标高度会更高，她会先爬到位再盘旋。
     */
    private static Vec3 climbPoint(EntityMaid maid, LivingEntity target) {
        double y = target.m_20186_() + requiredAbove(maid);
        if (firstClimb(maid.m_20148_())) {
            log(maid, "接敌 → 先爬到敌上 " + fmt(requiredAbove(maid)) + " 格（到位后再开始盘旋）");
        }
        return new Vec3(maid.m_20185_(), y, maid.m_20189_());
    }

    /** 这一场遭遇里"她是不是第一次进爬升"（每场只写一行）。 */
    private static boolean firstClimb(UUID id) {
        try {
            return CLIMB_FIRST.add(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本场遭遇是否已经写过"先爬升"那一行（{@link #clear} 时清）。 */
    private static final java.util.Set<UUID> CLIMB_FIRST = new java.util.HashSet<>();

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
        UUID id = maid.m_20148_();
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
        double angCur = Math.atan2(maid.m_20189_() - target.m_20189_(), maid.m_20185_() - target.m_20185_());
        double ang = angCur + dir * (lead / r);     // 沿她当前的切线方向再往前 lead 格
        // 高度：**敌人所在位置之上** fightAlt 格（玩家 728 补正原话），不再用离地高度。
        // 用敌人"脚底"而不是"眼睛"：她比敌人高 N 格时是整体高出，俯射角自然成立。
        // 【实测七百四十七】要投弹时（基洛夫等）基准抬到"投弹安全高度"（见 requiredAbove）。
        double above = requiredAbove(maid);
        double y = target.m_20186_() + above;
        // 这一场遭遇起手留一行（与扫帚的「接敌机动」同款：每场一行、不走节流）；
        // 排查时搜「空战」看的就是它。
        if (firstOrbit(id)) {
            log(maid, "接敌 → 爬到敌上 " + fmt(above) + " 格、绕着敌人盘旋（半径 " + fmt(r)
                    + " 格，旋向 " + (dir > 0 ? "逆时针" : "顺时针") + "）");
        }
        return new Vec3(target.m_20185_() + Math.cos(ang) * r, y, target.m_20189_() + Math.sin(ang) * r);
    }

    /** 胡萝卜领先的下限弧长（格）——见 {@link #orbitPoint}。必须大于 {@code FLIGHT_ARRIVE}。 */
    private static final double MIN_LEAD = 4.0;

    /* ==================== 实测七百三十八：地面载具接敌（贴着地绕敌人转） ==================== */

    /**
     * v1.3.0(beta) 实测七百三十八【女仆开陆地载具时接敌一动不动】。
     *
     * <h2>玩家原话</h2>
     * 「女仆在骑乘陆地载具的时候，走位的方向仍然是朝着主人方向。如果主人坐上了车，那么女仆的
     * 车子就会一点都不动了。……主要是在面对敌人的时候，主人坐上车以后，车还是一动不动，就很难绷了。
     * 能不能在接敌后也采用直升机/扫帚那种绕圈的方式呢？撞墙以后自动反方向。」
     *
     * <h2>根因</h2>
     * 接敌机动那一整套（{@link #combatTarget}）**只挂在"飞行载具"这一档**上：地面载具（车/坦克）
     * 在 {@code RideBindManager.drive} 里根本不进那一支，永远只走"跟着主人走"的兜底——所以主人
     * 一上车，她与主人的水平距离≈0，{@code stopBand} 立刻判"到了"→ 停车。敌人再近她也不动。
     *
     * <h2>本方法</h2>
     * 与 {@link #orbitPoint} **同一套机制**（{@link CombatOrbit} 的旋向/快慢/半径、同一个"追逐式
     * 胡萝卜"抗滞后），只把高度从「敌上 {@code fightAlt} 格」换成**敌人脚下的地面高度**——
     * 地面载具本来就不该飞。旋向再乘一个 {@code MaidMountCompat.groundReverse}：撞墙/被卡住连拍
     * 没位移时它会翻号（玩家原话「撞墙以后自动反方向」）。
     *
     * @param mount  她正开的载具（算方位用；她的位置≈载具座位）
     * @return 目标点；{@code null} = 本档不管
     */
    public static Vec3 groundOrbitTarget(EntityMaid maid, Entity mount, LivingEntity target) {
        if (maid == null || mount == null || target == null || !target.m_6084_()) {
            return null;
        }
        try {
            UUID id = maid.m_20148_();
            // 【实测七百三十九·点3】半径改成**按车速/车体自适应**（见 groundOrbitRadius 的注释：
            // 固定半径给小车是"拐不过来 → 直接撞进敌人怀里"，给大车是"原地蹭"）。
            double r = MaidMountCompat.groundOrbitRadius(mount, orbitRadiusCfg());
            // 旋向 = 基准（UUID）× 每 8 秒随机掉头 × **撞墙反向**（最后一项目的是玩家点名要的）
            double dir = CombatOrbit.direction(id) * CombatOrbit.flipSign(id)
                    * MaidMountCompat.groundReverse(mount);
            double spd = CombatOrbit.speedScale(id);
            double lead = Math.max(MIN_LEAD, r * 0.85 * Math.max(0.5, spd));
            double angCur = Math.atan2(mount.m_20189_() - target.m_20189_(),
                    mount.m_20185_() - target.m_20185_());
            double ang = angCur + dir * (lead / r);
            double y = target.m_20186_(); // 地面档：贴着敌人的高度绕，不进"敌上 N 格"
            if (firstOrbit(id)) {
                log(maid, "接敌 → 开着地面载具绕着敌人转圈（半径 " + fmt(r) + " 格，旋向 "
                        + (dir > 0 ? "逆时针" : "顺时针") + "）");
            }
            return new Vec3(target.m_20185_() + Math.cos(ang) * r, y,
                    target.m_20189_() + Math.sin(ang) * r);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 地面载具绕圈的绝对半径下限（格）——由 {@code MaidMountCompat.groundOrbitRadius} 统一裁决
     * （那里还叠车速与车体尺寸）。这里只保留一个"配置值兜底"。
     */
    private static final double GROUND_ORBIT_MIN = 6.0;

    /**
     * v1.3.0(beta) 实测七百三十八【玩家坐上副驾后直升机一直往上飞】。
     *
     * <h2>玩家原话</h2>
     * 「如果女仆乘坐的是直升机且玩家坐副驾驶，那结果就更糟糕了，因为会跟之前的代码冲突
     * （女仆开的直升机必须要在玩家的上面），导致女仆必须要一直往上飞。建议改为玩家乘坐以后就
     * 悬停。锁敌的时候正常。」
     *
     * <h2>根因</h2>
     * 跟随档的高度基准是**主人的 Y**（{@code hoverAt(…, owner.getY())}，七百三十三 定的口径）。
     * 玩家一旦坐上这架直升机，{@code owner.getY()} 就等于**机身自己的 Y**——于是目标高度 =
     * 她自己的高度 + {@code followAlt}，她每贴一次就被抬高，形成**每拍 +N 的棘轮**，永远往上飞
     * （七百三十四 修的是"拿她自己的 Y 当基准"，没覆盖"主人与她在同一台机器上"这一档）。
     *
     * <h2>修法</h2>
     * 玩家在机上时**不追任何高度基准**，直接悬停在**载具现在的位置**（水平 + 竖直都保持）。
     * 接敌那一档完全不受影响（照旧爬升到敌上、绕圈）——正是玩家要的"锁敌的时候正常"。
     *
     * <h2>【实测七百四十一·点2a】为什么基准必须是**载具**的位置</h2>
     * 玩家原话：「目前和女仆同时乘坐直升机的时候，悬浮状态下还是会有缓慢的上升。」
     * 根因：738 这一档返回的是**她自己**的位置，而她是**乘客**——她的 Y 是座位在载具内的
     * 局部偏移（Mi-28 驾驶座 {@code y ≈ 机身 + 1.47}）。{@code driveFlight} 收到
     * {@code dy = 目标Y − mount.getY()} 恒为 **+1.4 格左右**，于是总距（升力）那条 PD 每拍都在
     * "还没到目标高度"上补一点 → **永久缓慢爬升**。飞行档唯一能控制的是**载具**，所以基准必须是
     * 载具自己的位置：{@code dy = 0}、{@code horiz = 0}，才是真正的"原地悬停"。
     *
     * @param mount 她正开的载具（唯一的高度/水平基准）
     * @return 载具当前的位置（悬停点）
     */
    public static Vec3 holdHere(EntityMaid maid, Entity mount) {
        try {
            if (mount == null) {
                return null;
            }
            return new Vec3(mount.m_20185_(), mount.m_20186_(), mount.m_20189_());
        } catch (Throwable ignored) {
            return null;
        }
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
        UUID id = maid.m_20148_();
        ORBIT_FIRST.remove(id);
        CLIMB_FIRST.remove(id);
        HOLD_FIRST.remove(id);
        BOMB_INTENT_CACHE.remove(id);
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
            Long last = LOG_AT.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            if (LOG_AT.size() > 256) {
                LOG_AT.clear();
            }
            LOG_AT.put(maid.m_20148_(), now);
            com.maidsmart.tool.PromaidLog.log("空战", com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) {
        }
    }
}
