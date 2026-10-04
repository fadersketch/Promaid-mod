package com.maidsmart.patrol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.8【巡逻航迹】数据模型 + NBT 编解码（1.21.1 树；1.20.1 树本批不动）。
 *
 * <p>── 这是什么 ──
 * 一条航迹 = 一串**标记**（世界坐标，玩家骑扫帚飞着打点）+ 一个「已连接（闭环）」标志
 * + 净空半径 + 所属维度。玩家在编辑器里点「连接」把首尾接上，才成为可巡逻的闭环。
 *
 * <p>── 为什么必须闭环才算数（玩家原话）──
 * 「加一个连接按钮，玩家可以直接在那里面点击连接，将首尾的两个连在一起，这样子刚好可以
 * 达成闭环。（当然了，也必须要保证没有障碍物。否则就会提示连接失败。）」
 * 所以 {@link #closed()} 为假时这条航迹**不是**一条有效巡逻路径——运行时 {@code PatrolFlight}
 * 直接放行、回落到原来那套 home 守家盘旋（一个字节不变）；编辑器与日志都会提示"还没连成闭环"。
 *
 * <p>── 存哪儿 ──
 * 两种载体共用这一份 NBT 编解码（口径只留一处，见 {@link PatrolChartData}）：
 * 物品（{@code PatrolChartItem} 的 {@code CUSTOM_DATA}）与女仆（{@code persistentData}）。
 * 绑定 = 把物品那一份**深拷**进女仆，之后两者各走各的（把航图锁进箱子她照样巡逻）。
 */
public final class PatrolRoute {

    /** NBT 根标签名（物品 {@code CUSTOM_DATA} 与女仆 {@code persistentData} 都用它） */
    public static final String TAG_ROOT = "MaidSmartPatrol";
    /** 至少几个标记才算一条航迹（3 个点才能围出一个环） */
    public static final int MIN_POINTS = 3;
    /** 最多几个标记（防玩家打出几百个点把编辑器/校验拖垮） */
    public static final int MAX_POINTS = 64;
    /** 默认净空半径（格）：采样点周围这个范围内不许有阻挡方块 */
    public static final double DEFAULT_CLEARANCE = 1.5;
    /** 净空半径的可用范围（格） */
    public static final double MIN_CLEARANCE = 0.5;
    public static final double MAX_CLEARANCE = 6.0;

    private final List<Vec3> points = new ArrayList<>();
    private boolean closed = false;
    private double clearance = DEFAULT_CLEARANCE;
    private String dimension = "";
    /**
     * 【v1.3.9.3「连接时把线抬过方块」】每个标记的**抬升量**（格），与 {@link #points} 等长；
     * 空数组 = 没烘过（或烘完发现本来就没挡路的）。
     *
     * <p>玩家原话：「在最终玩家点击连接连接的时候让那些线稍微整改一下，尽可能越过方块。」
     * ⇒ 点「连接」那一刻在服务端跑一遍 {@link PatrolAdapt#refine}，把每个标记要抬多高算出来
     * 存进这里。**玩家打的标记一个都不动**（{@link #points} 原样保留、预览里的菱形还在原地），
     * 抬的只是"她实际飞的那条线"——见 {@link #flightPoints()}。
     */
    private double[] lift = new double[0];
    /** 轨道名（界面里显示；一本航图可能装好几条，靠它区分） */
    private String name = "";
    /**
     * 稳定标识（界面选中/改名后仍然认得同一条）。
     * 用 {@code name} 当键不行——玩家会改名、也可能重名；所以创建时生成一个短随机串。
     */
    private String id = "";

    public PatrolRoute() {
    }

    /* ==================== 只读访问 ==================== */

    public List<Vec3> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public Vec3 point(int i) {
        return i >= 0 && i < points.size() ? points.get(i) : null;
    }

    /** 已连接 = 首尾相接的闭环。只有它才让这条航迹成为"可巡逻"的路径 */
    public boolean closed() {
        return closed;
    }

    public void setClosed(boolean c) {
        this.closed = c;
    }

    public double clearance() {
        return clearance;
    }

    public void setClearance(double c) {
        this.clearance = Math.max(MIN_CLEARANCE, Math.min(MAX_CLEARANCE, c));
    }

    /** 轨道名（空 = 还没起名，界面按"未命名"显示） */
    public String name() {
        return name == null ? "" : name;
    }

    public void setName(String n) {
        this.name = n == null ? "" : n.trim();
    }

    /** 界面显示用名（空名字给个占位） */
    public String displayName() {
        return name().isEmpty() ? "未命名航迹" : name();
    }

    /* ==================== 界面用的两个速算（列表页每行要显示，别让界面自己算样条） ==================== */

    /**
     * 一圈弧长（格）。**只在闭环且点数够时才算**；否则 0。
     *
     * <p>列表页一行一次、每屏最多十来行，而点数是几十——采样量很小，可以随手算，不必缓存
     * （真正每 tick 跑的是 {@code PatrolCurve.Path} 那份缓存）。
     */
    public double lengthSafe() {
        try {
            if (!closed || size() < MIN_POINTS) {
                return 0.0;
            }
            List<net.minecraft.world.phys.Vec3> poly = PatrolCurve.polyline(points, true, 1.0);
            double[] cum = PatrolCurve.cumulative(poly);
            return cum.length == 0 ? 0.0 : cum[cum.length - 1];
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 一圈大概多少秒（按扫帚全速；闭环不算则 0） */
    public double secondsSafe() {
        try {
            return PatrolGeometry.secondsFor(lengthSafe());
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 稳定标识（创建时生成；老存档没有就补一个） */
    public String id() {
        if (id == null || id.isEmpty()) {
            id = newId();
        }
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    /** 生成一个短随机标识（够一本航图内部唯一即可） */
    public static String newId() {
        return Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
    }

    /** 绑定时的维度 id（空 = 不限）：她跨维度后不再巡逻 */
    public String dimension() {
        return dimension;
    }

    public void setDimension(String d) {
        this.dimension = d == null ? "" : d;
    }

    /* ==================== 编辑 ==================== */

    /** 追加一个标记。返回 false = 满了/非法。**加新点会让原来的闭环失效**（首尾不再相接） */
    public boolean add(Vec3 p) {
        if (p == null || points.size() >= MAX_POINTS) {
            return false;
        }
        points.add(p);
        closed = false;
        lift = new double[0]; // 点集变了，上次烘的抬升线作废（连接时会重烘）
        return true;
    }

    public boolean removeAt(int i) {
        if (i < 0 || i >= points.size()) {
            return false;
        }
        points.remove(i);
        closed = false;
        lift = new double[0];
        return true;
    }

    public void clear() {
        points.clear();
        closed = false;
        lift = new double[0];
    }

    /** 够不够围出一个环（≥ {@link #MIN_POINTS} 个点） */
    public boolean viable() {
        return points.size() >= MIN_POINTS;
    }

    /* ==================== v1.3.9.3：连接时烘的"避让线" ==================== */

    /**
     * 存下"每个标记该往哪挪"（点「连接」时算一次，见 {@link PatrolAdapt#refine}）。
     *
     * <p>数组按 **3 个一组**排：{@code [dx0,dy0,dz0, dx1,dy1,dz1, …]}——长度必须正好是
     * {@code 3 × 点数}，否则一律按"没烘"处理（数据坏了宁可当作没挪过，也不让索引越界）。
     *
     * <p>玩家原话：「不只是竖直高度方面的问题吧。如果上面被全部封死了，那也可以尝试往左往右
     * 或者往下的方式来进行连接的。」⇒ 从 v1.3.9.2 的"只抬高度"扩成三维挪移。
     */
    public void setLift(double[] offsets) {
        if (offsets == null || offsets.length != points.size() * 3) {
            this.lift = new double[0];
            return;
        }
        double max = 0.0;
        for (double v : offsets) {
            max = Math.max(max, Math.abs(v));
        }
        // 本来就没有一处挡路 → 不留数组（signature 也就不会因为"烘了一次空"而变化）
        this.lift = max < 0.03 ? new double[0] : offsets.clone();
    }

    /** 烘过的避让位移（长度 = 3×点数；空 = 没挪过） */
    public double[] lift() {
        return lift;
    }

    /** 这条轨道**实际会飞的**那串点 = 标记 + 烘好的避让位移（没烘过就是原样） */
    public List<Vec3> flightPoints() {
        if (lift.length != points.size() * 3) {
            return points;
        }
        List<Vec3> out = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            double dx = lift[i * 3];
            double dy = lift[i * 3 + 1];
            double dz = lift[i * 3 + 2];
            if (Math.abs(dx) < 0.03 && Math.abs(dy) < 0.03 && Math.abs(dz) < 0.03) {
                out.add(p);
            } else {
                out.add(new Vec3(p.x + dx, p.y + dy, p.z + dz));
            }
        }
        return out;
    }

    /** 挪得最多的一处挪了几格（0 = 没挪；给界面/日志报一句用） */
    public double liftMax() {
        double m = 0.0;
        for (double v : lift) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    /**
     * 内容签名：供运行时的"路径缓存"判"这条航迹换过了没有"。
     * 只用点列表 + 闭环标志 + 净空——维度不进签名（她跨维度时缓存重建一次也无妨）。
     */
    public String signature() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(closed ? 'C' : 'O').append('|').append(clearance).append('|');
        for (Vec3 p : points) {
            sb.append(fmt(p.x)).append(',').append(fmt(p.y)).append(',').append(fmt(p.z)).append(';');
        }
        // 【v1.3.9.3】抬升线也算内容：重连后烘出来的抬升不同 → 运行时路径缓存必须重建
        if (lift.length > 0) {
            sb.append("|L");
            for (double v : lift) {
                sb.append(fmt(v)).append(',');
            }
        }
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    /* ==================== 深拷 ==================== */

    public PatrolRoute copy() {
        PatrolRoute r = new PatrolRoute();
        r.points.addAll(this.points);
        r.closed = this.closed;
        r.clearance = this.clearance;
        r.dimension = this.dimension;
        r.name = this.name;
        r.id = this.id;
        r.lift = this.lift.clone();
        return r;
    }

    /* ==================== NBT 编解码（物品与女仆共用） ==================== */

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putString("name", name());
        root.putString("id", id());
        root.putBoolean("closed", closed);
        root.putDouble("clearance", clearance);
        root.putString("dimension", dimension == null ? "" : dimension);
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", p.x);
            t.putDouble("y", p.y);
            t.putDouble("z", p.z);
            list.add(t);
        }
        root.put("points", list);
        // 【v1.3.9.3】连接时烘的避让线（3 个一组；空数组不写——老存档读回来就是"没挪"）
        if (lift.length > 0) {
            ListTag ls = new ListTag();
            for (double v : lift) {
                ls.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            }
            root.put("lift", ls);
        }
        return root;
    }

    /** 从 NBT 读（坏数据一律丢，绝不抛） */
    public static PatrolRoute load(CompoundTag root) {
        PatrolRoute r = new PatrolRoute();
        if (root == null) {
            return r;
        }
        try {
            r.name = root.getString("name");
            r.id = root.getString("id");
            r.closed = root.getBoolean("closed");
            if (root.contains("clearance", Tag.TAG_DOUBLE)) {
                r.setClearance(root.getDouble("clearance"));
            }
            r.dimension = root.getString("dimension");
            Tag raw = root.get("points");
            if (raw instanceof ListTag list) {
                for (Tag t : list) {
                    if (!(t instanceof CompoundTag ct) || r.points.size() >= MAX_POINTS) {
                        continue;
                    }
                    r.points.add(new Vec3(ct.getDouble("x"), ct.getDouble("y"), ct.getDouble("z")));
                }
            }
            // 【v1.3.9.3】避让线：长度对不上就丢掉（宁可当作"没挪"，也不让索引越界）
            Tag rawLift = root.get("lift");
            if (rawLift instanceof ListTag ll) {
                double[] h = new double[ll.size()];
                for (int i = 0; i < ll.size(); i++) {
                    h[i] = ll.getDouble(i);
                }
                if (h.length == r.points.size() * 3) {
                    r.lift = h;
                }
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 一行摘要（日志/提示用） */
    public String describe() {
        return displayName() + "（标记 " + points.size() + " 个 / " + (closed ? "已连接（闭环）" : "未连接")
                + " / 净空 " + fmt(clearance) + " 格）";
    }
}
