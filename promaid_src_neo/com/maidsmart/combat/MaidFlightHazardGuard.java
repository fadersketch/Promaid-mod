package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.DangerBlocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测六百九十六【飞行危险环境避让】。
 *
 * <p>【玩家原话】「女仆在飞行的时候应该要尝试避开危险的环境，比如岩浆这些，将这些视为不可靠近的地区。」
 *
 * <p>── 为什么飞行一侧本来是"没人管"的 ──
 * 地面那一侧早就有一整套危险方块处理（{@code misc.dangerBlocks} 表 + 寻路层
 * {@code MaidDangerPathMixin} + 险境脱离 {@code DangerEscapeHandler} + 自保的
 * 「附近岩浆感知」），但它们**全部挂在"走路/寻路"上**：
 * {@code SelfPreservationBehavior} 里那句注释写得很直白——「放在岩浆扫描之前：
 * <b>空中不需要岩浆避让</b>，顺带省掉方块扫描」。于是她一旦离地（扫帚模式自己驾扫帚、
 * 空袭/飞行跟随在滑翔），危险方块表就整个失效：她会贴着岩浆湖面盘旋、一头扎进去。
 *
 * <p>── 本类做的两件事（都只加"别进去"，不加"还能干什么"）──
 * <ol>
 *   <li><b>{@link #detour}：把"想去哪"改成"能去哪"</b>。给定运动起点与目的地，若这
 *       一段（最多 {@link #REACH} 格）会穿进危险格，就在水平面上按固定几个侧向候选
 *       （±35°/±70°/±110°，带<b>粘滞</b>：选过哪一侧 3 秒内优先还选那一侧，防左右横跳）
 *       找一个不穿危险格的方向；候选全被堵死就**向上让开**（升 {@link #CLEAR} 格），
 *       宁可爬过去也绝不进危险格。已经身处危险格里时原样返回——那一档交给自保的
 *       卡墙/岩浆逃生链路，这里不跟它抢。</li>
 *   <li><b>{@link #antiSink}：不往危险格里沉</b>。滑翔是"重力慢慢把她往下拽"，
 *       方向对了照样会沉进去。每 tick 问一次"脚下/正前方那格是不是危险方块"，
 *       是就把**竖直分量**抬平（水平分量同时按 0.7 收一下，别在危险区上方横冲），
 *       于是她掉到岩浆面上方就停住、往外飘走。只管竖直，不改朝向、不改攻击动作。</li>
 * </ol>
 *
 * <p>【危险格的口径】完全复用 {@link DangerBlocks#cellDangerous}——站立格本体 /
 * 脚下一格 / 头顶灼烧型（火·灵魂火·岩浆）三选一，与地面那套**同一份表和同一个判据**，
 * 所以玩家在 {@code misc.dangerBlocks} 里加一项，飞行这一侧立刻跟着生效。
 * 【开关】独立于 {@code misc.dangerAvoid}（那是地面寻路的）：本类看
 * {@code combat.flightDangerAvoid}（默认开），关掉 = 飞行完全不看危险方块（旧行为）。
 *
 * <p>【为什么只扫这么近】{@link #REACH} 格 ≈ 她 1.5 秒的位移：飞行避让要的是
 * "别撞上去"，不是"绕远路重新规划"——远处的绕行交给玩家自己的圈心/盘旋半径设置。
 * 一段最多 6 次方块采样，每 tick 每只女仆的开销可以忽略。
 *
 * <p>【明确的边界】**攻击动作不做避让**：近战空袭的收翅俯冲、远程空袭的俯冲助推
 * 是"朝目标去"的设计动作，目标站在岩浆边也照冲（那是玩家要的攻击）。这里只保
 * 巡航/盘旋/跟随/掉高那段"她自己随便飞"的路程。
 */
public final class MaidFlightHazardGuard {

    private MaidFlightHazardGuard() {
    }

    /** 沿途采样步长（格）：0.75 ≈ 她半 tick 的位移，够密了 */
    private static final double STEP = 0.75;
    /** 沿途最多试探多远（格）——越过这个距离的危险当"还早"，下一 tick 再说 */
    private static final double REACH = 4.5;
    /** 绕行候选的侧偏角（度），由小到大试 */
    private static final double[] SIDES = {35.0, 70.0, 110.0};
    /** 绕行目标距起点的水平距离（格）——短程修正，不是重新规划 */
    private static final double DETOUR_STEP = 3.0;
    /** "让开"时向上抬多少格 */
    private static final double CLEAR = 2.0;
    /** 抗沉抬升给的竖直速度（格/tick）：正数就是"不许再往下" */
    private static final double LIFT = 0.12;
    /** 粘滞有效期（tick）= 3 秒 */
    private static final long STICKY_TICKS = 60L;

    /** 女仆 UUID → (选边, 到期游戏刻)：防左右横跳 */
    private static final Map<UUID, long[]> STICKY = new HashMap<>();

    public static boolean enabled() {
        try {
            return MaidSmartConfig.COMBAT_FLIGHT_DANGER_AVOID.get();
        } catch (Throwable t) {
            return false; // 配置没加载等异常：按关闭处理（绝不因为避让坏了飞行）
        }
    }

    /** 该坐标所在的格（连同脚下一格、头顶灼烧格）是不是危险格——口径同地面那套 */
    public static boolean dangerousAt(Level level, double x, double y, double z) {
        return dangerousAt(level, x, y, z, 0);
    }

    /**
     * 【实测七百〇四：带上她的碰撞箱宽度】同 {@link #dangerousAt(Level, double, double, double)}，
     * 但把**她整个身位**（碰撞箱覆盖的水平格）都算进来——见
     * {@link DangerBlocks#cellDangerousBoxed}。
     *
     * <p>玩家反馈「对于岩浆这种危险环境的判定，可能需要把女仆自身的碰撞伤害算进去」：
     * 旧判据只探中轴那一条线/那一格，而她宽 0.6 格、横跨两列——"中轴安全、隔壁是岩浆"时
     * 判安全，于是她擦着岩浆边缘飞/走，碰撞箱自己把她推进去。
     *
     * @param radius 水平扩展半径（格）：0 = 旧行为（只判中轴那一格）。
     *               调用方按 {@link DangerBlocks#boxRadius} 从她的碰撞箱算。
     */
    public static boolean dangerousAt(Level level, double x, double y, double z, int radius) {
        if (level == null) {
            return false;
        }
        try {
            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            if (radius <= 0) {
                return DangerBlocks.cellDangerous(level, bx, by, bz);
            }
            return DangerBlocks.cellDangerousBoxed(level, bx, by, bz, radius);
        } catch (Throwable t) {
            return false; // 判不出来就当她能过（宁可漏一次，不误杀飞行）
        }
    }

    /** 这只女仆碰撞箱对应的危险判定半径（0.6 宽 → 1）——见 {@link DangerBlocks#boxRadius} */
    private static int boxRadius(EntityMaid maid) {
        try {
            return DangerBlocks.boxRadius(maid.getBbWidth());
        } catch (Throwable t) {
            return 1; // 读不到宽度就按女仆的常规宽度保守取 1（多判一格，不会误放行）
        }
    }

    /** from → to 这一段（最多 {@link #REACH} 格）会不会穿进危险格（起点本身不算） */
    private static boolean pathBlocked(Level level, Vec3 from, Vec3 to, int radius) {
        try {
            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-4) {
                return false;
            }
            double reach = Math.min(len, REACH);
            int steps = (int) Math.ceil(reach / STEP);
            for (int i = 1; i <= steps; i++) {
                double t = Math.min(1.0, (i * STEP) / len);
                if (dangerousAt(level,
                        from.x + dx * t, from.y + dy * t, from.z + dz * t, radius)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把"想去哪"改成"能去哪"。
     *
     * @param from    运动起点（扫帚模式传扫帚坐标，滑翔传她自己；避让以"真正移动的那个"为准）
     * @param desired 原本的目的地（已过各链路自己的夹取/脱困）
     * @return 调整后的目的地；任何异常/开关关闭都原样返回 {@code desired}
     */
    public static Vec3 detour(EntityMaid maid, Vec3 from, Vec3 desired) {
        try {
            if (maid == null || from == null || desired == null || !enabled()) {
                return desired;
            }
            Level level = maid.level();
            if (level == null) {
                return desired;
            }
            // 已经身处危险格里：不在这一档抢活（自保的岩浆逃生/卡墙顶出在管）
            int rad = boxRadius(maid);
            if (dangerousAt(level, from.x, from.y, from.z, rad)) {
                return desired;
            }
            if (!pathBlocked(level, from, desired, rad)) {
                stickyClear(maid);
                return desired; // 快路：这一段干净，一个数都不改
            }
            double dx = desired.x - from.x;
            double dz = desired.z - from.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz < 0.25) {
                // 目的地几乎在正上/正下方：没有可偏的侧向，只能竖直处理
                return up(level, from, desired, rad);
            }
            double baseYaw = Math.atan2(dz, dx);
            int stick = stickySide(maid);
            for (double mag : SIDES) {
                for (int k = 0; k < 2; k++) {
                    // 粘滞侧先试（stick==0 时先试左）
                    int sign = (k == 0 ? 1 : -1) * (stick < 0 ? -1 : 1);
                    double yaw = baseYaw + sign * Math.toRadians(mag);
                    Vec3 cand = new Vec3(
                            from.x + Math.cos(yaw) * DETOUR_STEP,
                            Math.max(desired.y, from.y), // 不往低处钻
                            from.z + Math.sin(yaw) * DETOUR_STEP);
                    if (!dangerousAt(level, cand.x, cand.y, cand.z, rad)
                            && !pathBlocked(level, from, cand, rad)) {
                        stickySet(maid, sign);
                        return cand;
                    }
                }
            }
            // 两侧全被堵死：爬过去（这是"不可靠近"的最后一道）
            stickyClear(maid);
            return up(level, from, desired, rad);
        } catch (Throwable t) {
            return desired;
        }
    }

    /** 目的地几乎不可侧偏、或两侧都堵死时的"向上让开"（抬不动就退回原目标） */
    private static Vec3 up(Level level, Vec3 from, Vec3 desired, int radius) {
        Vec3 cand = new Vec3(from.x, from.y + CLEAR, from.z);
        if (dangerousAt(level, cand.x, cand.y, cand.z, radius)) {
            return desired;
        }
        return cand;
    }

    /**
     * 不往危险格里沉：她此刻若正在下沉、而脚下或正前方那格是危险方块，就把竖直分量抬平。
     * 由 {@code MaidFlightCombatBehavior.tickRangedAir} 每 tick 在**最后**调一次
     * （姿态/速度都摆完之后，所以这一句有最终话语权）。
     *
     * <p>【实测七百〇四】脚下那一格与"正前方"都按她的碰撞箱宽度判（见 {@link DangerBlocks#cellDangerousBoxed}）
     * ——旧版只探中轴一格，她擦着岩浆湖边缘下沉时判不出来。
     */
    public static void antiSink(EntityMaid maid) {
        try {
            if (maid == null || !enabled()) {
                return;
            }
            Level level = maid.level();
            if (level == null) {
                return;
            }
            Vec3 v = maid.getDeltaMovement();
            if (v.y >= -0.01) {
                return; // 没在往下掉
            }
            Vec3 p = maid.position();
            int rad = boxRadius(maid);
            boolean below = dangerousAt(level, p.x, p.y - 1.0, p.z, rad);
            if (!below) {
                double len = Math.sqrt(v.x * v.x + v.y * v.y
                        + v.z * v.z);
                if (len > 1.0E-4) {
                    double k = 1.5 / len;
                    below = dangerousAt(level, p.x + v.x * k,
                            p.y + v.y * k, p.z + v.z * k, rad);
                }
            }
            if (!below) {
                return;
            }
            // 只抬竖直、水平收一下；朝向交给原本的盘旋/弹开逻辑（本方法不碰姿态）
            maid.setDeltaMovement(new Vec3(v.x * 0.7, LIFT, v.z * 0.7));
        } catch (Throwable ignored) {
        }
    }

    // ================= 选边粘滞（防左右横跳） =================

    private static int stickySide(EntityMaid maid) {
        try {
            long[] st = STICKY.get(maid.getUUID());
            if (st == null || st[1] < maid.level().getGameTime()) {
                return 0;
            }
            return (int) st[0];
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void stickySet(EntityMaid maid, int side) {
        try {
            STICKY.put(maid.getUUID(),
                    new long[]{side, maid.level().getGameTime() + STICKY_TICKS});
            if (STICKY.size() > 256) {
                STICKY.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void stickyClear(EntityMaid maid) {
        try {
            STICKY.remove(maid.getUUID());
        } catch (Throwable ignored) {
        }
    }
}
