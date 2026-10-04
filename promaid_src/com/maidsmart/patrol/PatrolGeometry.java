package com.maidsmart.patrol;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航迹】几何校验：自交 / 自邻近 / 坡度 / 一圈时长。
 *
 * <p>── 这里为什么**不**校验"首尾接上了没有" ──
 * 首尾相接是 {@link PatrolCurve} **构造出来**的（标记列表用循环索引，接缝处位置与切线
 * 连续是数学保证）。所以剩下要验的不是"接没接上"，而是"这个环**质量合格**吗"——本类只做
 * 后者，且是纯函数（不看世界、只算几何），因此编辑器里点一次就能出结果。
 *
 * <p>── 三项检查 + 两个数字 ──
 * <ol>
 *   <li><b>自交</b>：闭环密采样后，任意两条**非相邻**线段在 XZ 平面上相交 → 报交点。
 *       "这条线自己绕自己了"——她会顺着一个叉口反复切自己的航线。</li>
 *   <li><b>自邻近</b>：任意两个非相邻采样点贴得比 {@code 2×净空半径} 还近 → 报警。
 *       即使不相交，两条挨太近的航线也会让她"贴着隔壁那条飞"。</li>
 *   <li><b>坡度</b>：扫帚的水平上限 0.75、竖直上限 0.30（{@code MaidBroomDrive} 的
 *       {@code MAX_H_SPEED} / {@code MAX_V_SPEED}，与 {@code PlayerBroomControl} 同口径），
 *       所以极限爬升角 = atan(0.30/0.75) ≈ 21.8°。相邻标记比这更陡她**跟不上**。</li>
 *   <li><b>一圈时长</b> = 总弧长 ÷ 水平速度上限（格/拍）÷ 20 = 秒。给玩家一个直观的数。</li>
 * </ol>
 */
public final class PatrolGeometry {

    /** 扫帚水平速度上限（格/拍）——**与 {@code MaidBroomDrive.MAX_H_SPEED} 同值**（原版 PlayerBroomControl 包络） */
    public static final double MAX_H = 0.75;
    /** 扫帚竖直速度上限（格/拍）——**与 {@code MaidBroomDrive.MAX_V_SPEED} 同值** */
    public static final double MAX_V = 0.30;
    /** 极限爬升角（度）= atan(MAX_V / MAX_H) ≈ 21.8° */
    public static final double MAX_SLOPE_DEG = Math.toDegrees(Math.atan2(MAX_V, MAX_H));
    /** 超出极限坡度多少度才报（留一点容差，免得擦线的被反复念） */
    private static final double SLOPE_TOLERANCE = 3.0;
    /** 自邻近的采样间距（格）：比这更近才算"两条航线贴住了" */
    private static final double NEAR_EPS = 0.6;

    private PatrolGeometry() {
    }

    /** 一次校验的全量结论（编辑器与日志共用） */
    public record Report(boolean geometryOk, boolean clearanceOk,
                         List<String> problems, List<String> notes,
                         double length, double seconds, double maxSlopeDeg,
                         double radius, Vec3 center) {
    }

    /* ==================== 单项检查 ==================== */

    /**
     * 自交：返回交点列表（XZ 平面上、非相邻线段之间的交点）。空 = 没自交。
     *
     * @param closed 闭环时还要把"末段 vs 首段"当成相邻的一对排除掉（它们是接缝，本来就接着）
     */
    public static List<Vec3> selfIntersections(List<Vec3> poly, boolean closed) {
        List<Vec3> hits = new ArrayList<>();
        int n = poly.size();
        if (n < 4) {
            return hits;
        }
        int segs = n - 1;
        for (int i = 0; i < segs; i++) {
            Vec3 a1 = poly.get(i);
            Vec3 a2 = poly.get(i + 1);
            for (int j = i + 1; j < segs; j++) {
                if (j == i + 1) {
                    continue; // 相邻段共用端点
                }
                if (closed && i == 0 && j == segs - 1) {
                    continue; // 接缝那一对：相邻
                }
                Vec3 b1 = poly.get(j);
                Vec3 b2 = poly.get(j + 1);
                Vec3 x = segXZ(a1, a2, b1, b2);
                if (x != null) {
                    hits.add(x);
                    if (hits.size() >= 8) {
                        return hits; // 报几个就够，别刷屏
                    }
                }
            }
        }
        return hits;
    }

    /** 任意两个非相邻采样点之间的最小水平距离（格）——判"两条航线贴太近" */
    public static double minNonAdjacentDistance(List<Vec3> poly, boolean closed) {
        int n = poly.size();
        if (n < 4) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int gap = j - i;
                if (gap <= 2 || (closed && n - gap <= 2)) {
                    continue; // 沿线相邻的点不比
                }
                double dx = poly.get(i).f_82479_ - poly.get(j).f_82479_;
                double dz = poly.get(i).f_82481_ - poly.get(j).f_82481_;
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                }
            }
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
    }

    /** 密采样折线上的最大爬升角（度） */
    public static double maxSlopeDeg(List<Vec3> poly) {
        double worst = 0.0;
        for (int i = 1; i < poly.size(); i++) {
            Vec3 a = poly.get(i - 1);
            Vec3 b = poly.get(i);
            double horiz = Math.sqrt((b.f_82479_ - a.f_82479_) * (b.f_82479_ - a.f_82479_) + (b.f_82481_ - a.f_82481_) * (b.f_82481_ - a.f_82481_));
            double dy = Math.abs(b.f_82480_ - a.f_82480_);
            if (horiz < 1.0E-4) {
                continue; // 纯垂直的一小段（几乎不会出现）：不参与坡度
            }
            worst = Math.max(worst, Math.toDegrees(Math.atan2(dy, horiz)));
        }
        return worst;
    }

    /** 一圈时长（秒）= 总弧长 ÷ 水平速度上限 ÷ 20 */
    public static double secondsFor(double length) {
        return length / MAX_H / 20.0;
    }

    /** 航迹的水平包围盒半径（格）与中心——供"牵引绳参照点/绑定上限"用 */
    public static double[] radiusAndCenter(List<Vec3> pts) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Vec3 p : pts) {
            minX = Math.min(minX, p.f_82479_);
            maxX = Math.max(maxX, p.f_82479_);
            minZ = Math.min(minZ, p.f_82481_);
            maxZ = Math.max(maxZ, p.f_82481_);
        }
        double cx = (minX + maxX) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double r = 0.0;
        for (Vec3 p : pts) {
            double dx = p.f_82479_ - cx;
            double dz = p.f_82481_ - cz;
            r = Math.max(r, Math.sqrt(dx * dx + dz * dz));
        }
        return new double[]{r, cx, cz};
    }

    /* ==================== 汇总（几何部分） ==================== */

    /**
     * 只做几何校验（不看方块）。{@code clearanceOk} 一律先置 true，由
     * {@link PatrolClearance} 再填。
     */
    public static Report geometryReport(PatrolRoute route) {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (route == null || route.size() < PatrolRoute.MIN_POINTS) {
            problems.add("标记不够（至少 " + PatrolRoute.MIN_POINTS + " 个才能围出一个环）");
            return new Report(false, true, problems, notes, 0.0, 0.0, 0.0, 0.0, null);
        }
        boolean closed = route.closed();
        List<Vec3> poly = PatrolCurve.polyline(route.points(), closed, 0.35);
        double[] cum = PatrolCurve.cumulative(poly);
        double length = cum.length == 0 ? 0.0 : cum[cum.length - 1];
        double slope = maxSlopeDeg(poly);
        double[] rc = radiusAndCenter(route.points());
        double radius = rc == null ? 0.0 : rc[0];
        Vec3 center = rc == null ? null : new Vec3(rc[1], 0.0, rc[2]);

        if (!closed) {
            problems.add("还没连成闭环——点界面里的「连接」把首尾接上");
        }
        if (slope > MAX_SLOPE_DEG + SLOPE_TOLERANCE) {
            problems.add(String.format(
                    "有一段太陡（最大 %.0f° > 扫帚极限 %.0f°）——她会跟不上，把相邻标记拉平一点",
                    slope, MAX_SLOPE_DEG));
        }
        List<Vec3> xs = selfIntersections(poly, closed);
        if (!xs.isEmpty()) {
            Vec3 h = xs.get(0);
            problems.add(String.format("航迹自己交叉了（共 %d 处，第一处约 %.0f, %.0f, %.0f）——"
                            + "她会顺着叉口反复切自己的航线，请把那一处的点挪开",
                    xs.size(), h.f_82479_, h.f_82480_, h.f_82481_));
        }
        double near = minNonAdjacentDistance(poly, closed);
        double need = route.clearance() * 2.0;
        if (near != Double.MAX_VALUE && near < need + NEAR_EPS) {
            notes.add(String.format("航迹有两段挨得很近（最小间距 %.1f 格 < %.1f 格）——"
                    + "她会贴着隔壁那条航线飞，建议拉开", near, need));
        }
        if (closed && length > 1.0E-3) {
            notes.add(String.format("一圈 %.0f 格，按扫帚全速约 %.0f 秒", length, secondsFor(length)));
        }
        return new Report(problems.isEmpty(), true, problems, notes, length,
                secondsFor(length), slope, radius, center);
    }

    /* ==================== 二维线段相交（XZ 平面） ==================== */

    /**
     * 两条线段在 XZ 平面上的交点（无交点/共线/退化 → null）。
     * Y 取交点处两条线段的线性插值平均，只是为了在界面上标出一个能看懂的位置。
     */
    private static Vec3 segXZ(Vec3 a1, Vec3 a2, Vec3 b1, Vec3 b2) {
        double d1x = a2.f_82479_ - a1.f_82479_;
        double d1z = a2.f_82481_ - a1.f_82481_;
        double d2x = b2.f_82479_ - b1.f_82479_;
        double d2z = b2.f_82481_ - b1.f_82481_;
        double den = d1x * d2z - d1z * d2x;
        if (Math.abs(den) < 1.0E-9) {
            return null; // 平行或共线：不报（共线重叠交给"自邻近"那一项）
        }
        double ex = b1.f_82479_ - a1.f_82479_;
        double ez = b1.f_82481_ - a1.f_82481_;
        double t = (ex * d2z - ez * d2x) / den;
        double u = (ex * d1z - ez * d1x) / den;
        // 端点相交不算（相邻段共享端点已排除，这里再挡一次浮点擦边）
        double m = 1.0E-6;
        if (t < m || t > 1.0 - m || u < m || u > 1.0 - m) {
            return null;
        }
        double x = a1.f_82479_ + d1x * t;
        double z = a1.f_82481_ + d1z * t;
        double ya = a1.f_82480_ + (a2.f_82480_ - a1.f_82480_) * t;
        double yb = b1.f_82480_ + (b2.f_82480_ - b1.f_82480_) * u;
        return new Vec3(x, (ya + yb) * 0.5, z);
    }
}
