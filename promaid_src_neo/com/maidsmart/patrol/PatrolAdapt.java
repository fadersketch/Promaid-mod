package com.maidsmart.patrol;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.9.2【巡逻航图 · 航线自适应避让】——"连线不再卡方块"的落点。
 *
 * <p>── 玩家原话（分两次提的需求）──
 * 「对某些东西的判定似乎有些过严了，我刚刚在运行游戏的时候，它就一直在显示有方块阻挡。这样子吧。
 * 我们在创建的时候，就不要把方块阻挡做一个门槛了。交给女仆自己的寻路。如果可以的话，可以让在
 * 连线的时候让画出来的运动曲线稍微有一些变化来适配要求。」
 * 「在最终玩家点击连接连接的时候让那些线稍微整改一下，尽可能越过方块。」
 * 「不只是竖直高度方面的问题吧。如果上面被全部封死了，那也可以尝试往左往右或者往下的方式来进行
 * 连接的。」
 *
 * <p>⇒ 三件事：
 * <ol>
 *   <li><b>方块不再是门槛</b>：连接 / 绑定一律照做，挡路方块只念一句 ⚠（见 {@code PatrolNetworking}）。</li>
 *   <li><b>连接时把整条线烘一遍</b>（{@link #refine}）：每个标记算一个避让位移，存进
 *       {@link PatrolRoute#setLift}——从此"她飞的那条线"就是挪过的这条。</li>
 *   <li><b>避让是三维的</b>（{@link #nudge}）：先往上抬；**上面被封死就往四边挪**；
 *       四面也堵着才往下沉。旧版只会抬高度，遇到"头顶一整片天花板"就完全没辙。</li>
 * </ol>
 *
 * <p>── 为什么必须有上限 ──
 * 与 {@code MaidBroomDrive} 的 {@code ESCAPE_UP_MAX} 同一条道理：躲障碍只是**暂时**改位置。
 * 无上限地"往外找空位"，一遇到连续地形就会把她一路垫到天上（或推出几百格），
 * 攻击与链路全飞没。{@link #MAX_LIFT} = 4 格：翻得过一道坎、绕过一堵矮墙，
 * 但绝不会改变"这条轨道大概在哪儿飞"这件事。
 *
 * <p>── 避让不改存档 ──
 * 玩家打的标记**原样不动**（世界里那几颗记号还在你打的位置）。挪的只是"她实际会飞的那条线"——
 * 所以把航图锁进箱子、换个地形再飞，同一条轨道依旧只依赖标记本身。这是"曲线适配"而不是
 * "偷偷改你的轨道"。
 */
public final class PatrolAdapt {

    /** 避让时最多往外挪几格，上下左右前后共用这一个上限（见类注释的"为什么必须有上限"） */
    public static final int MAX_LIFT = 4;
    /** 平滑窗口半径（采样点）：避让量在障碍前后各这么多点内过渡，曲线不会折成台阶 */
    private static final int SMOOTH_R = 3;
    /** 烘"避让线"时的平滑窗口半径（**按标记数**，不是采样点数——标记最多 64 个） */
    private static final int REFINE_R = 2;

    /**
     * 水平搜索顺序：先四正（左右前后），再四角。
     * 玩家原话要的是"往左往右"，所以四正排前面——挪一格就够时不会斜着走。
     */
    private static final int[][] HORIZ = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private PatrolAdapt() {
    }

    /* ==================== 运行时：单个目标点 ==================== */

    /**
     * 目标点被方块占着就找一个能过身位（她 0.6×1.5，这里查脚下 + 头顶两格）的位置。
     *
     * <p>【顺序】① 先往上抬（最常见：脚下/头顶压着，抬一抬就过）；
     * ② **上面抬不动就往四边挪**（玩家原话：「如果上面被全部封死了，那也可以尝试往左往右」）；
     * ③ 四面也堵着才往下沉。找一个就收，找不到（或压根没挡）→ 原样返回。
     *
     * <p>【v1.3.9.3 修一个真 bug】旧版的循环从 {@code dy=1} 起查，于是"本来就能过"的点
     * （空中绝大多数点）也会返回 1——整条线恒定飘高 1 格。以前只影响预览与飞行高度 1 格、
     * 看不出来；现在这套避让量要**烘进存档**（见 {@link #refine}），再带着这个错就是永久偏 1 格。
     * 所以先判"原位置就能过 → 不挪"。
     */
    public static Vec3 nudge(Level level, Vec3 p, int maxD) {
        if (level == null || p == null || maxD <= 0) {
            return p;
        }
        try {
            if (passable(level, p)) {
                return p;
            }
            for (int d = 1; d <= maxD; d++) {   // ① 往上
                Vec3 q = new Vec3(p.x, p.y + d, p.z);
                if (passable(level, q)) {
                    return q;
                }
            }
            for (int d = 1; d <= maxD; d++) {   // ② 四边（左右前后 + 四角）
                for (int[] dir : HORIZ) {
                    Vec3 q = new Vec3(p.x + dir[0] * d, p.y, p.z + dir[1] * d);
                    if (passable(level, q)) {
                        return q;
                    }
                }
            }
            for (int d = 1; d <= maxD; d++) {   // ③ 往下
                Vec3 q = new Vec3(p.x, p.y - d, p.z);
                if (passable(level, q)) {
                    return q;
                }
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    /* ==================== 预览：整条折线 ==================== */

    /**
     * 把一条密采样折线整体挪成"能飞的那条线"——预览画的就是它，所以玩家看到的曲线
     * 与实际飞行一致。
     *
     * <p>三步：① 每个点算最小避让位移（{@link #rawOffset}）；② 窗口取"绝对值最大"的那个分量
     * （障碍前后各 {@link #SMOOTH_R} 个点跟着一起挪，避免曲线在障碍边缘只挪一个点、
     * 看着像被戳了一下）；③ 两遍 1-2-1 平滑，把台阶磨成缓坡。
     */
    public static List<Vec3> liftAll(Level level, List<Vec3> poly, int maxD) {
        int n = poly == null ? 0 : poly.size();
        if (n == 0 || level == null || maxD <= 0) {
            return poly;
        }
        double[] dx = new double[n];
        double[] dy = new double[n];
        double[] dz = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 off = rawOffset(level, poly.get(i), maxD);
            dx[i] = off.x;
            dy[i] = off.y;
            dz[i] = off.z;
        }
        smoothWindow(dx, SMOOTH_R);
        smoothWindow(dy, SMOOTH_R);
        smoothWindow(dz, SMOOTH_R);
        List<Vec3> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Vec3 p = poly.get(i);
            out.add(tiny(dx[i], dy[i], dz[i]) ? p : new Vec3(p.x + dx[i], p.y + dy[i], p.z + dz[i]));
        }
        return out;
    }

    /**
     * 【v1.3.9.3「连接时让线越过方块」】在点「连接」那一刻，把**每个标记**该往哪挪算出来，
     * 存进 {@link PatrolRoute#setLift}（见 {@link PatrolRoute#flightPoints()}）。
     *
     * <p>── 与"运行时挪移"的分工 ──
     * <ul>
     *   <li><b>本方法（烘一次）</b>：按**标记**逐点算避让位移，算出一条固定的避让线存进存档。
     *       它是"这条轨道以后就这么飞"的一部分——换个存档重进、换个维度都一样。</li>
     *   <li>{@link #nudge}（每拍）：飞的时候个别还顶着的点再补一下。</li>
     * </ul>
     * 两者叠用：避让线先让整条线绕过去，残余的再由每拍那一下兜掉。
     *
     * <p><b>不改编出来的航迹数据</b>：{@link PatrolRoute#points()} 一个数都不动，
     * 位移存在 {@code lift} 数组里，二者相加才是"她飞的那条线"。
     *
     * @return 挪得最多的一处挪了几格（0 = 全线本来就能过，没烘）
     */
    public static double refine(Level level, PatrolRoute route, int maxD) {
        if (route == null || level == null || maxD <= 0 || route.size() < 2) {
            return 0.0;
        }
        try {
            int n = route.size();
            List<Vec3> pts = route.points();
            double[] dx = new double[n];
            double[] dy = new double[n];
            double[] dz = new double[n];
            for (int i = 0; i < n; i++) {
                Vec3 off = rawOffset(level, pts.get(i), maxD);
                dx[i] = off.x;
                dy[i] = off.y;
                dz[i] = off.z;
            }
            // 每个点各自"该挪多少"留一份，平滑过头的地方用它兜底
            double[] rx = dx.clone();
            double[] ry = dy.clone();
            double[] rz = dz.clone();
            smoothWindow(dx, REFINE_R);
            smoothWindow(dy, REFINE_R);
            smoothWindow(dz, REFINE_R);
            // 平滑完还是过不去的点，退回它自己那份"确实找得到的"位移
            for (int i = 0; i < n; i++) {
                if (tiny(dx[i], dy[i], dz[i])) {
                    continue;
                }
                Vec3 p = pts.get(i);
                Vec3 q = new Vec3(p.x + dx[i], p.y + dy[i], p.z + dz[i]);
                if (!passable(level, q)) {
                    dx[i] = rx[i];
                    dy[i] = ry[i];
                    dz[i] = rz[i];
                }
            }
            double[] triples = new double[n * 3];
            for (int i = 0; i < n; i++) {
                triples[i * 3] = dx[i];
                triples[i * 3 + 1] = dy[i];
                triples[i * 3 + 2] = dz[i];
            }
            route.setLift(triples);
            return route.liftMax();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /* ==================== 内部 ==================== */

    /**
     * 这个点最少要往哪挪、挪几格才能过身位（零向量 = 本来就能过）。
     * 【v1.3.9.3】与 {@link #nudge} 同一处 off-by-one 修复：先判原地能不能过。
     */
    private static Vec3 rawOffset(Level level, Vec3 p, int maxD) {
        Vec3 q = nudge(level, p, maxD);
        return q.subtract(p);
    }

    /** 对一条分量序列做"窗口取绝对值最大 + 两遍 1-2-1 平滑"（就地改） */
    private static void smoothWindow(double[] v, int r) {
        int n = v.length;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            double best = 0.0;
            int lo = Math.max(0, i - r);
            int hi = Math.min(n - 1, i + r);
            for (int j = lo; j <= hi; j++) {
                if (Math.abs(v[j]) > Math.abs(best)) {
                    best = v[j];
                }
            }
            w[i] = best;
        }
        for (int pass = 0; pass < 2; pass++) {
            double[] s = new double[n];
            for (int i = 0; i < n; i++) {
                double a = w[Math.max(0, i - 1)];
                double b = w[i];
                double c = w[Math.min(n - 1, i + 1)];
                s[i] = (a + 2.0 * b + c) / 4.0;
            }
            w = s;
        }
        System.arraycopy(w, 0, v, 0, n);
    }

    /** 小到可以当"没挪"（避免给浮点噪声留一堆 0.0001） */
    private static boolean tiny(double dx, double dy, double dz) {
        return Math.abs(dx) < 0.03 && Math.abs(dy) < 0.03 && Math.abs(dz) < 0.03;
    }

    /** 这个高度上她能不能过（脚下 + 头顶两格都空） */
    private static boolean passable(Level level, Vec3 p) {
        try {
            BlockPos feet = BlockPos.containing(p.x, p.y, p.z);
            return clear(level, feet) && clear(level, feet.above());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 这一格能不能过（未加载的区块按"能过"处理——她飞到时区块票会把它加载出来） */
    private static boolean clear(Level level, BlockPos pos) {
        try {
            if (!level.isLoaded(pos)) {
                return true;
            }
            return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        } catch (Throwable ignored) {
            return true; // 判不出来就放行：宁可她自己脱困，也别把航线挪歪
        }
    }
}
