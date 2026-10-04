package com.maidsmart.patrol;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航迹】曲线引擎：向心（centripetal）Catmull-Rom 样条 + 弧长重采样 + 纯追踪取点。
 *
 * <p>── 为什么是 Catmull-Rom（玩家原话）──
 * 「Replay mod 里可以在某个位置放置摄像头标记，让视频的摄像头随着标记之间连接的曲线
 * 平滑移动。我觉得可以套用一下相关机制。」——ReplayMod 那套相机的本质就是一条**穿过所有
 * 标记**的插值样条：Catmull-Rom 恰好保证曲线**精确经过**每个控制点（不像 B 样条会被拉偏），
 * 所以"标记就是她一定会经过的地方"这句话成立。
 *
 * <p>── 为什么必须是**向心**参数化（这条错了整条航迹就废）──
 * 均匀（uniform）Catmull-Rom 在控制点间距悬殊时会**过冲**甚至打出自交的小圈；而向心
 * 参数化（α = 0.5）在数学上保证曲线不打结、不出尖点——这正是"相机路径"的标准选择。
 * 你后面要用「连接」按钮保证首尾相接，曲线自己先打结的话那个保证就是空的。
 *
 * <p>── 为什么还要按弧长重采样 ──
 * 样条在控制点附近走得慢、段中间走得快。直接把样条参数喂给 {@code steerTo}，她的速度会一耸
 * 一耸。所以这里离线密采样（{@link #STEP} 格一点）、累计弧长，运行时按"沿航迹飞了多远"取点，
 * 速度就均匀了。
 *
 * <p>── 首尾相接是**构造出来**的，不是检测出来的 ──
 * 标记列表本来就是循环的：第 n 个点的下一个是第 0 个。用这套循环索引去算 Catmull-Rom，
 * 接缝那一段的控制点是 {@code P[n-2], P[n-1], P[0], P[1]}——曲线在接缝处**位置连续、
 * 切线连续**是数学上直接保证的，不需要"检测它们接上了没有"。玩家打点时也不必手动封口。
 */
public final class PatrolCurve {

    /** 密采样步长（格）：0.35 ≈ 她半 tick 的位移，够密 */
    private static final double STEP = 0.35;
    /** 向心参数化的 α（0 = 均匀、0.5 = 向心、1 = 弦长） */
    private static final double ALPHA = 0.5;
    /** 重复点（间距为 0）时的节点增量兜底，防除零 */
    private static final double KNOT_EPS = 1.0E-4;

    private PatrolCurve() {
    }

    /* ==================== 密采样 ==================== */

    /**
     * 把标记列表插值成密采样折线。
     *
     * @param closed true = 循环（末点显式等于首点，便于弧长表回绕）；false = 开放
     */
    public static List<Vec3> polyline(List<Vec3> pts, boolean closed, double step) {
        List<Vec3> out = new ArrayList<>();
        int n = pts == null ? 0 : pts.size();
        if (n == 0) {
            return out;
        }
        if (n == 1) {
            out.add(pts.get(0));
            return out;
        }
        double h = (step <= 0.05 || !Double.isFinite(step)) ? STEP : step;
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            Vec3 p1 = pts.get(i);
            Vec3 p2 = pts.get((i + 1) % n);
            Vec3 p0 = closed ? pts.get((i - 1 + n) % n) : pts.get(Math.max(0, i - 1));
            Vec3 p3 = closed ? pts.get((i + 2) % n) : pts.get(Math.min(n - 1, i + 2));
            double len = p1.m_82554_(p2);
            int steps = Math.max(1, (int) Math.ceil(len / h));
            for (int k = 0; k < steps; k++) {
                double u = (double) k / (double) steps;
                out.add(cr(p0, p1, p2, p3, u));
            }
        }
        if (closed) {
            out.add(out.get(0)); // 显式闭合（回绕用）
        } else {
            out.add(pts.get(n - 1));
        }
        return out;
    }

    /**
     * 向心 Catmull-Rom 单段插值（Barry–Goldman 金字塔），{@code u ∈ [0,1]} 映射到节点区间 [t1,t2]。
     * 端点性质：u=0 → p1、u=1 → p2（所以曲线精确经过标记）。
     */
    private static Vec3 cr(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        double t0 = 0.0;
        double t1 = t0 + knot(p0, p1);
        double t2 = t1 + knot(p1, p2);
        double t3 = t2 + knot(p2, p3);
        double t = t1 + (t2 - t1) * Math.max(0.0, Math.min(1.0, u));
        // 三段线性插值（金字塔）
        double a1 = (t1 - t) / (t1 - t0);
        double a2 = (t - t0) / (t1 - t0);
        double b1 = (t2 - t) / (t2 - t1);
        double b2 = (t - t1) / (t2 - t1);
        double c1 = (t3 - t) / (t3 - t2);
        double c2 = (t - t2) / (t3 - t2);
        double d1 = (t2 - t) / (t2 - t0);
        double d2 = (t - t0) / (t2 - t0);
        double e1 = (t3 - t) / (t3 - t1);
        double e2 = (t - t1) / (t3 - t1);
        Vec3 A1 = lerp(p0, p1, a1, a2);
        Vec3 A2 = lerp(p1, p2, b1, b2);
        Vec3 A3 = lerp(p2, p3, c1, c2);
        Vec3 B1 = lerp(A1, A2, d1, d2);
        Vec3 B2 = lerp(A2, A3, e1, e2);
        double f1 = (t2 - t) / (t2 - t1);
        double f2 = (t - t1) / (t2 - t1);
        return lerp(B1, B2, f1, f2);
    }

    /** 向心节点增量：|b−a|^α（α=0.5） */
    private static double knot(Vec3 a, Vec3 b) {
        double d = a.m_82554_(b);
        if (d < KNOT_EPS) {
            return KNOT_EPS;
        }
        return Math.pow(d, ALPHA);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double wa, double wb) {
        return new Vec3(a.f_82479_ * wa + b.f_82479_ * wb, a.f_82480_ * wa + b.f_82480_ * wb, a.f_82481_ * wa + b.f_82481_ * wb);
    }

    /** 折线的累计弧长表（长度 = 点数，末项 = 总长） */
    public static double[] cumulative(List<Vec3> poly) {
        int n = poly.size();
        double[] cum = new double[Math.max(1, n)];
        double acc = 0.0;
        for (int i = 1; i < n; i++) {
            acc += poly.get(i - 1).m_82554_(poly.get(i));
            cum[i] = acc;
        }
        return cum;
    }

    /* ==================== 运行态：预采样 + 纯追踪 ==================== */

    /**
     * 一条航迹的**运行态**（密采样折线 + 弧长表）。构造一次、多拍复用；
     * 航迹内容变了（{@link PatrolRoute#signature()}）由调用方重建。
     */
    public static final class Path {

        private final List<Vec3> poly;
        private final double[] cum;
        private final double total;
        private final boolean loop;

        public Path(PatrolRoute route) {
            this(route, STEP);
        }

        public Path(PatrolRoute route, double step) {
            this.loop = route != null && route.closed();
            // 【限定 PatrolCurve.】本类有个同名的访问器 polyline()（返回密采样折线），
            // 不加前缀时 `polyline(...)` 会解析到那个 0 参方法上（编译期实错，不是风格问题）。
            // 【v1.3.9.3】取 flightPoints（= 标记 + 连接时烘好的抬升线）：运行时飞的就是它
            this.poly = PatrolCurve.polyline(
                    route == null ? List.of() : route.flightPoints(), loop, step);
            this.cum = cumulative(poly);
            this.total = cum.length == 0 ? 0.0 : cum[cum.length - 1];
        }

        public List<Vec3> polyline() {
            return poly;
        }

        /** 一圈弧长（格） */
        public double total() {
            return total;
        }

        public int size() {
            return poly.size();
        }

        /**
         * 折线上离 {@code p} 最近的点下标。全扫（几百点，每 tick 每只女仆一次，可忽略）；
         * 开放航迹时首尾不回绕。
         */
        public int nearest(Vec3 p) {
            int n = poly.size();
            if (n == 0) {
                return -1;
            }
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double d = poly.get(i).m_82557_(p);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }

        /** 离投影点最近的那条折线上的实际点（= 把她拉回航迹的落点） */
        public Vec3 nearestPoint(Vec3 p) {
            int i = nearest(p);
            return i < 0 ? null : poly.get(i);
        }

        /** 按弧长取点（闭环回绕；开放航迹夹在两端） */
        public Vec3 at(double s) {
            int n = poly.size();
            if (n == 0) {
                return null;
            }
            if (n == 1) {
                return poly.get(0);
            }
            if (total <= 1.0E-6) {
                return poly.get(0);
            }
            if (loop) {
                s = ((s % total) + total) % total;
            } else {
                s = Math.max(0.0, Math.min(total, s));
            }
            int lo = 0;
            int hi = n - 1;
            while (lo < hi - 1) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] <= s) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double segLen = cum[hi] - cum[lo];
            if (segLen <= 1.0E-9) {
                return poly.get(lo);
            }
            double f = (s - cum[lo]) / segLen;
            return poly.get(lo).m_165921_(poly.get(hi), f);
        }

        /**
         * 纯追踪：把她当前位置投影到航迹上，瞄准"投影点往前 {@code lookahead} 格"的那个点。
         *
         * <p>【为什么用纯追踪而不是"走到点就换下一个"】她被风推偏、被打退、绕建筑偏出去之后，
         * 下一拍投影回航迹就自动切回正轨——**不累积漂移**，也不需要任何"到了没有"的判据。
         *
         * @param hint 调用方传入的 1 元素数组，回填投影点下标（诊断用；可为 null）
         */
        public Vec3 aim(Vec3 from, double lookahead, int[] hint) {
            int i = nearest(from);
            if (i < 0) {
                return null;
            }
            if (hint != null && hint.length > 0) {
                hint[0] = i;
            }
            return at(cum[i] + Math.max(0.5, lookahead));
        }
    }
}
