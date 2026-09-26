package com.maidsmart.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测六百九十三【锁敌后的「随机环绕路径」】——把"绕着敌人转圈"从
 * **固定半径 + 固定旋向** 改成 **每只女仆各自随机**，并给一条"离敌最远距离"的硬上界。
 *
 * <p>── 反馈原文 ──
 * 「可以把环绕型攻击方式更改一下，这样环绕的话会增加被击中的概率，会导致消耗量增加，
 *  敌人如果攻击的是第一只的话，会攻击到后面的，锁敌之后攻击敌人的飞行路径改成随机吧，
 *  然后设一个锁敌之后离敌的最远距离，狐狐被击中的概率或许就降低不少。」
 *
 * <p>── 为什么"环绕"会被串击（不是玄学，是几何）──
 * 锁敌之后她飞的是一个**圆**：半径固定（扫帚 {@code combat.broom.range} 默认 8 / 远程空袭
 * {@code airRaid.orbitRadius} 默认 10）、旋向固定（两条链路算出来的切向永远朝同一个方向）。
 * 多只女仆同时接敌时，她们落在**同一个圆、同一个高度**上——敌人朝最近那只射一箭，射线穿过
 * 这个圆还会打到对面那只；近战的横扫同理。换言之："被击中的概率"里有一份不是来自"她贴得近"，
 * 而是来自"她和同伴在同一条线上"。
 *
 * <p>── 这个类做的事 ──
 * 给每只女仆一份**稳定但会缓慢游走**的环绕参数：
 * <ul>
 *   <li><b>旋向</b>（{@link #direction}）：由 UUID 派生，一半逆时针、一半顺时针。这一条自己
 *       就能把"排队"拆开——两只女仆哪怕半径一样，也是在圆上**对穿**而不是首尾相接。</li>
 *   <li><b>半径</b>（{@link #radius}）：不再固定取上界，而是在 {@code [lo, hi]} 区间里取一个
 *       由 UUID 起手、每 {@link #REROLL_TICKS} 重掷一次、按 {@link #DRIFT_STEP} 缓动的比例。
 *       多只女仆的半径天然不同（不在一个圆上），同一只女仆的半径还会随时间飘（不是死圈）。</li>
 *   <li><b>上界</b>：{@code hi} 就是配置里那条"锁敌之后离敌的最远距离"，它同时是随机区间的
 *       **顶点**（调用方还会拿它做硬牵引），所以"随机"绝不会变成"越飞越远"。</li>
 * </ul>
 *
 * <p>── 为什么"缓慢游走"而不是"每 tick 随机" ──
 * 每 tick 掷一次骰子 = 目标点每 tick 跳一个位置，她的朝向会被这份噪声抖散（枪口永远摆不稳），
 * 看起来像故障而不是"机动"。这里取"每 4 秒重掷 + 每 tick 只挪 0.4% 量程"：路径是**飘**的，
 * 一个来回要十几秒，肉眼是"她在换位置"，不是"她在抽搐"。
 *
 * <p>── 为什么参数从 UUID 派生 ──
 * 与 {@code MaidBroomDrive.phaseOf} 同一个理由：随机会在每次重进世界时换值（她当着你的面跳一下），
 * 而 UUID 是稳定的——同一只女仆的起始半径与旋向永远一样。重掷那一半用 UUID + 递增计数派生，
 * 所以**同一份存档重放出来的轨迹也一样**（可复现，日志对得上）。
 *
 * <p>── 边界（如实写）──
 * 本类**只管"绕多远、往哪边绕"**，不碰高度：扫帚那条的盘旋高度由
 * {@code MaidBroomDrive.COMBAT_ALT}（爬升相位定下来的那一场遭遇的高度）全权决定，空袭那条由
 * {@code rangedHoldHeight} + 补推决定——把高度也随机化会与那两套"只升不降 / 掉高才补"的口径
 * 打架。想连高度一起错开，那是另一件事。
 */
public final class CombatOrbit {

    /** 重掷间隔（tick，默认 80 = 4 秒）：每这么久给这只女仆换一个新的目标半径比例 */
    private static final int REROLL_TICKS = 80;
    /** 每 tick 向目标比例滑动的步长（0.004 ⇒ 走满量程 1.0 要 250 tick ≈ 12.5 秒） */
    private static final double DRIFT_STEP = 0.004;
    /** 一次重掷相对当前比例的最大跳变（0.35）：保证是"飘"，不是"瞬移" */
    private static final double REROLL_SPAN = 0.35;

    private static final Map<UUID, State> STATE = new HashMap<>();

    private CombatOrbit() {
    }

    /** 一只女仆的环绕参数（当前比例 / 目标比例 / 已走拍数 / 这一轮是否刚起手） */
    private static final class State {
        double frac = Double.NaN;   // 当前半径比例（0~1），NaN = 还没起手
        double targetFrac;          // 目标比例
        int ticks;                  // 本状态走过的拍数（重掷节拍与随机盐都用它，不依赖 gameTime）
        boolean fresh;              // 本次 radius() 是否为"这一轮第一次"（供调用方写一行日志）
    }

    /**
     * 这次环绕的**旋向**：{@code +1.0} = 逆时针、{@code -1.0} = 顺时针（俯视图），由 UUID 派生、
     * 同一只女仆恒定。
     *
     * <p>【为什么必须一半一半】半径随机只解决了"不在同一个圆上"，但如果旋向仍然一致，"半径接近
     * 的两只"依然会一前一后跟着走。旋向对半拆开之后，两只女仆在圆上的相对运动是**对穿**的——
     * 敌人那条射线想穿过两只，得同时穿过两个不同半径、不同旋向的点，几何上不成立。
     */
    public static double direction(UUID id) {
        if (id == null) {
            return 1.0;
        }
        return ((id.hashCode() >>> 16) & 1) == 0 ? 1.0 : -1.0;
    }

    /**
     * 推进并返回这一拍该用的**环绕半径**（格）。
     *
     * @param id 女仆 UUID（null 时退化为"区间中点"，不记账）
     * @param lo 最近半径（格）：调用方按"基础盘旋距离 × 0.75"算
     * @param hi 最远半径（格）= 配置里那条"锁敌之后离敌的最远距离"，也是随机区间的顶点
     */
    public static double radius(UUID id, double lo, double hi) {
        if (hi < lo) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        if (id == null) {
            return (lo + hi) * 0.5;
        }
        if (hi - lo < 0.01) {
            State degenerate = STATE.computeIfAbsent(id, k -> new State());
            degenerate.fresh = false;
            // 区间退化（玩家把上界调到基础半径以下）：直接用上界，别再随机
            return hi;
        }
        State s = STATE.computeIfAbsent(id, k -> new State());
        if (Double.isNaN(s.frac)) {
            // 起手：比例由 UUID 定，稳定可复现
            s.frac = rand01(id, 0L);
            s.targetFrac = s.frac;
            s.fresh = true;
        } else {
            s.fresh = false;
        }
        s.ticks++;
        if (s.ticks % REROLL_TICKS == 0) {
            // 重掷：以当前比例为基准小幅游走（而不是全区间重抽——那会让她横跨整个区间瞬移）
            double delta = (rand01(id, s.ticks) * 2.0 - 1.0) * REROLL_SPAN;
            s.targetFrac = clamp01(s.frac + delta);
        }
        if (s.frac < s.targetFrac) {
            s.frac = Math.min(s.targetFrac, s.frac + DRIFT_STEP);
        } else if (s.frac > s.targetFrac) {
            s.frac = Math.max(s.targetFrac, s.frac - DRIFT_STEP);
        }
        return lo + (hi - lo) * s.frac;
    }

    /**
     * 本次 {@link #radius} 是否为"这一轮第一次"——调用方据此写一行「随机环绕」日志，
     * 且**只在起手那一拍写**（每轮一只女仆一行，不刷屏）。
     */
    public static boolean entering(UUID id) {
        if (id == null) {
            return false;
        }
        State s = STATE.get(id);
        return s != null && s.fresh;
    }

    /** 这只女仆下线/卸载（或这一轮打完）：丢掉她的环绕参数，下次接敌重新起手 */
    public static void forget(UUID id) {
        if (id == null) {
            return;
        }
        STATE.remove(id);
    }

    /** 服务端停止 / 重载：整表清空 */
    public static void clearAll() {
        STATE.clear();
    }

    /* ==================== 内部：稳定随机 ==================== */

    /** 0~1 的稳定随机：由 UUID 与"盐"（起手用 0、重掷用拍数）派生，同一输入永远同一输出 */
    private static double rand01(UUID id, long salt) {
        long h = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 17)
                ^ (salt * 0x9E3779B97F4A7C15L)
                ^ 0x2545F4914F6CDD1DL;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return (h >>> 11) / (double) (1L << 53);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
