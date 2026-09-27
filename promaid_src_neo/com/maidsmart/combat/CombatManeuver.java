package com.maidsmart.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0(beta) 实测七百〇三【接敌后的「多种飞行方式」】——把"绕着敌人转圈"这一种打法
 * 扩成一套**按现代空战战术命名的机动库**，每只女仆**每场遭遇各抽一种**执行。
 *
 * <p>── 玩家原话 ──
 * 「在女仆扫帚模式接敌的情况下随机性能不能稍微高一点？我是说现在全都是保持盘旋状态的，
 *  战斗方式有些过于单一了。首先先研究多种飞行方式（要求适配远程武器，可以参考一下现代
 *  空军战术里面都有哪些飞行方式），然后女仆接敌之后，会从这多种飞行方式中选择一个进行执行。
 *  而不全是统一绕圈。即使真的选到了绕圈，那么每次的盘旋方向、速度等，各方面都要有一定的
 *  随机性，当然不要太大。而且基础比敌人高多少格这一点还是要的。」
 *
 * <p>── 现状（为什么"全都一样"）──
 * 实测六百九十三 / 七百〇一 已经把**同一张圆**拆开了：半径各抽各的（{@link CombatOrbit#radius}）、
 * 旋向对半（{@link CombatOrbit#direction}）、绕圈快慢也在飘（{@link CombatOrbit#speedScale}）。
 * 但"打法"本身仍然只有一种——**绕圈**。所以多只女仆观感上还是"一群人在转"，只是圆的半径不同。
 *
 * <p>── 这一层加什么 ──
 * 在"圆"这个骨架上叠一层**波形调制**（半径倍率 / 角速度倍率 / 高度增量 / 旋向翻转），五种打法：
 * <ul>
 *   <li><b>环绕 ORBIT</b>：基线。半径/旋向/快慢照旧由 {@link CombatOrbit} 随机——玩家说的
 *       "即使真的选到了绕圈，那么每次的盘旋方向、速度等都要有一定的随机性"就落在这一档上。</li>
 *   <li><b>蛇形 WEAVE</b>（空战里的 defensive weave）：一边绕一边**径向进出**，她的距离在
 *       {@code ±15%} 里持续滑动——敌人算不准"她下一拍在几格外"。远程对射最实用：始终留在
 *       有效射程带里，却不是一个可预测的固定半径。</li>
 *   <li><b>高悠悠 YOYO</b>（空战里的 yo-yo，教科书动作）：绕圈半径不变，**高度做慢波**
 *       （在基准高度**之上** {@code 0~amp} 格），并且**高处转得慢、低处转得快**——真实 yo-yo
 *       就是拿速度换高度，这一条把那个物理关系原样搬了过来。</li>
 *   <li><b>脱离再进 EXTEND</b>（空战里的 extend / disengage）：**周期性地拉到最外圈、再切回来**，
 *       "贴着火线输出"与"退出去喘一口"交替，是最省血的一种打法。它的半径调制**先向上顶、
 *       再由调用方夹进 [近端, 最远距离]**，所以"最远距离"这条硬上界一个字节不让。</li>
 *   <li><b>8 字横切 FIGURE8</b>（空战里的 cross-turn / 剪刀机动）：**每隔几秒把旋向翻过来**，
 *       她会在敌人正面来回横穿，而不是匀速绕单侧的圈。</li>
 * </ul>
 *
 * <p>── 三条硬边界（为什么这样叠是安全的）──
 * <ol>
 *   <li><b>不改几何骨架</b>：本类只输出**乘数/加数**（半径倍率、角速度倍率、高度增量、旋向翻转），
 *       圆的径向修正、{@code minStandoff} 近端、{@code orbitMax} 硬牵引全部留在原调用方——
 *       所以"随机"仍然不会变成"越飞越远"或"贴脸"（六百九十三 立的规矩原样成立）。</li>
 *   <li><b>高度只向上、不向下</b>：{@code heightAdd >= 0}，玩家要的那条「基础比敌人高多少格」
 *       **永远成立**（只会更高，不会掉到敌人脚下）。</li>
 *   <li><b>幅度都小</b>：半径倍率 {@code [0.82, 1.15]}、角速度倍率 {@code [0.75, 1.15]}——
 *       观感是"她在换位置"，不是"她在抽搐"，也不会像换了一架飞行器。</li>
 * </ol>
 *
 * <p>── 为什么"每场遭遇抽一种"而不是"每 tick 抽" ──
 * 每 tick 换打法 = 轨迹没有一段是完整的，看着像故障。这里按**遭遇**（锁敌 → 丢敌/打完为一轮）
 * 抽一次，一只女仆在一场仗里从头到尾打同一种机动；换了敌人 / 打完再打才重抽。抽签由
 * UUID + 「第几次遭遇」派生——同一份存档重放出来的选择一样（可复现，日志对得上），而**同场
 * 多只女仆各抽各的**（这正是玩家要的"不要全都是同一种"）。
 *
 * <p>── 与 {@link CombatOrbit} 的分工（口径只有一处）──
 * {@code CombatOrbit} 管"圆多大、往哪边绕、绕多快"；本类管"在这一圈上怎么个走法"。
 * 两者都不碰高度基准与硬边界——那些在各自的调用方（扫帚 {@code MaidBroomDrive.combatPoint}、
 * 空袭 {@code MaidFlightCombatBehavior.faceOrbit}）。
 */
public final class CombatManeuver {

    /** 五种接敌机动（顺序 = 抽签阈值顺序，改动会改变随机序列，别随便挪） */
    public enum Kind {
        ORBIT("环绕"),
        WEAVE("蛇形"),
        YOYO("高悠悠"),
        EXTEND("脱离再进"),
        FIGURE8("8字横切");

        /** 中文名（日志 / 面板用；本类不依赖任何 MC 类型，纯字符串） */
        public final String cn;

        Kind(String cn) {
            this.cn = cn;
        }
    }

    /* ==================== 抽签权重 ==================== */

    /**
     * 环绕的权重（最高）——它是基线打法，也是玩家点名"真选到绕圈也要有随机性"的那一档；
     * 给最高权重保证"多数情况下看起来还是那只熟悉的绕圈女仆"，其余四种是点缀而不是换人。
     */
    private static final double W_ORBIT = 0.34;
    /** 蛇形（远程最实用的一种：距离持续滑动，却始终在射程带里） */
    private static final double W_WEAVE = 0.22;
    /** 高悠悠（高度慢波 + 高处转得慢） */
    private static final double W_YOYO = 0.18;
    /** 脱离再进（周期性拉远再切回，最省血） */
    private static final double W_EXTEND = 0.14;
    // 8 字横切 = 剩下的 0.12（最后兜底，不单独写常量，免得四个权重加起来不是 1 时出空洞）

    /* ==================== 波形参数 ==================== */

    /** 蛇形径向波周期（tick，默认 100 = 5 秒）——比半径重掷的 4 秒略慢，才叠得出"飘"的观感 */
    private static final int WEAVE_PERIOD = 100;
    /** 蛇形径向幅度（半径的 ±15%） */
    private static final double WEAVE_RADIUS_AMP = 0.15;

    /**
     * 高悠悠的高度波周期（tick，默认 160 = 8 秒）。
     *
     * <p>刻意取**最长**的一个周期：高度变化最显眼，换快了像"她在上下点头"；8 秒一个来回
     * 才是"她升上去压一轮、再下来一轮"的节奏（一场遭遇通常能走完一到两个完整波）。
     */
    private static final int YOYO_PERIOD = 160;
    /**
     * 高悠悠的角速度深度（0.25）：{@code angleScale = 1 − 0.25 × 高度归一值}，
     * 也就是**高的时候转到 0.75 倍、低的时候回到 1.0 倍**。
     *
     * <p>这就是真实 yo-yo 的那条物理关系——爬升时动能换势能、速度掉下来，俯冲时再把高度
     * 换回速度。少了这一条，"高度波动"只是上下平移，不像机动。
     */
    private static final double YOYO_ANGLE_DEPTH = 0.25;

    /** 脱离再进的周期（tick，默认 200 = 10 秒）：一次完整的"拉出去 → 切回来" */
    private static final int EXTEND_PERIOD = 200;
    /** 脱离再进的半径倍率区间：{@code [0.82, 1.12]}（1.12 会被调用方夹回「最远距离」以内） */
    private static final double EXTEND_RADIUS_LO = 0.82;
    private static final double EXTEND_RADIUS_SPAN = 0.30;
    /** 脱离再进的角速度：拉出去时收小（少绕圈、多直飞出去），切回来时加大（抢角度再进） */
    private static final double EXTEND_ANGLE_LO = 0.85;
    private static final double EXTEND_ANGLE_SPAN = 0.30;

    /** 8 字横切：每这么多 tick（默认 80 = 4 秒）把旋向翻一次，一圈 8 字 = 两条腿 */
    private static final int FIGURE8_LEG_TICKS = 80;

    /** 抽签与波形用的随机盐（与 {@link CombatOrbit} 的盐刻意不同，两条链条的随机互不相关） */
    private static final long PICK_SALT = 0x6D4E5556L;

    /* ==================== 状态 ==================== */

    /** 女仆 UUID → 本场遭遇的机动状态 */
    private static final Map<UUID, State> STATE = new HashMap<>();
    /** 女仆 UUID → 她打过几场遭遇（每 begin 一次 +1）——用它让"同一只女仆下一场换一种打法" */
    private static final Map<UUID, Integer> SERIAL = new HashMap<>();

    /** 一只女仆这一场遭遇的机动（种类 / 已走拍数 / 这一拍的波形值） */
    private static final class State {
        Kind kind = Kind.ORBIT;
        int ticks;                  // 本场遭遇走了多少 tick（波形的自变量）
        // 每 tick 刷新的波形值（tick() 里算一次，四个 accessor 直接读，避免重复计算/重复推进）
        double radiusScale = 1.0;
        double angleScale = 1.0;
        double heightNorm = 0.0;    // 高度归一值 0~1（只有 YOYO 非零）
        double reversal = 1.0;      // 旋向翻转 ±1（只有 FIGURE8 会变）
    }

    private CombatManeuver() {
    }

    /* ==================== 生命周期 ==================== */

    /**
     * 开一场遭遇（锁敌那一刻调）：没有状态就抽一种机动；已经有就把现状原样返回（幂等）。
     *
     * <p>【为什么幂等】调用方每 tick 都会走到"要不要开一场"这一句（扫帚在爬升收尾、空袭在
     * 进盘旋），幂等之后调用方不必自己记"开过没开过"——重复调用不会把她的打法中途换掉
     * （那正是"每 tick 抽一次"的坏处，见类注释）。
     */
    public static Kind begin(UUID id) {
        if (id == null) {
            return Kind.ORBIT;
        }
        State s = STATE.get(id);
        if (s != null) {
            return s.kind;
        }
        int serial = SERIAL.getOrDefault(id, 0);
        SERIAL.put(id, serial + 1);
        s = new State();
        s.kind = pick(id, serial);
        STATE.put(id, s);
        return s.kind;
    }

    /** 这只女仆这一场打的哪种机动（没开遭遇时为 null） */
    public static Kind kind(UUID id) {
        if (id == null) {
            return null;
        }
        State s = STATE.get(id);
        return s == null ? null : s.kind;
    }

    /** 这一场遭遇开起来了吗（调用方据此只写一次日志） */
    public static boolean active(UUID id) {
        return id != null && STATE.containsKey(id);
    }

    /** 这一场遭遇的拍数（供调用方做"开场的两三秒"之类的判据；没开遭遇返回 0） */
    public static int ticks(UUID id) {
        if (id == null) {
            return 0;
        }
        State s = STATE.get(id);
        return s == null ? 0 : s.ticks;
    }

    /** 这一场遭遇结束（丢敌 / 打完 / 下了鞍）：丢掉状态，下次接敌重抽（序列继续往后走） */
    public static void forget(UUID id) {
        if (id == null) {
            return;
        }
        STATE.remove(id);
        // SERIAL 刻意**不清**：下一场遭遇要抽到不一样的打法，序列必须继续往下走
        // （清了就等于"每场都抽第一签"，那又变回"每只女仆一辈子同一种机动"）。
    }

    /** 服务端停止 / 重载：整表清空 */
    public static void clearAll() {
        STATE.clear();
        SERIAL.clear();
    }

    /**
     * 推进这一拍（调用方每 tick 在**取波形值之前**调一次）。
     *
     * <p>【为什么是"推进 + 读数"两段式，而不是一个返回值】调用方要同时拿半径倍率、角速度倍率、
     * 高度增量、旋向翻转四个数。做成每 tick 一个对象就要每只女仆每 tick 分配一个小对象；
     * 而这个模组在「空闲与流畅」那一节里对每 tick 的分配很敏感。两段式全程零分配。
     */
    public static void tick(UUID id) {
        if (id == null) {
            return;
        }
        State s = STATE.get(id);
        if (s == null) {
            return;
        }
        s.ticks++;
        int t = s.ticks;
        // 先全部归位（ORBIT 与"这一档没用到的量"都保持中性）
        s.radiusScale = 1.0;
        s.angleScale = 1.0;
        s.heightNorm = 0.0;
        s.reversal = 1.0;
        switch (s.kind) {
            case ORBIT -> {
                // 基线：一个字节都不加，半径/旋向/快慢全交给 CombatOrbit 的随机
            }
            case WEAVE -> {
                double w = Math.sin(2.0 * Math.PI * t / WEAVE_PERIOD);
                s.radiusScale = 1.0 + WEAVE_RADIUS_AMP * w;
            }
            case YOYO -> {
                double y = Math.sin(2.0 * Math.PI * t / YOYO_PERIOD);
                double h = 0.5 + 0.5 * y;                 // 0~1，1 = 波峰（最高）
                s.heightNorm = h;
                s.angleScale = 1.0 - YOYO_ANGLE_DEPTH * h; // 高处转得慢（速度换高度）
            }
            case EXTEND -> {
                double c = Math.cos(2.0 * Math.PI * t / EXTEND_PERIOD);
                double e = 0.5 + 0.5 * c;                 // 0~1，1 = 拉到最外圈
                s.radiusScale = EXTEND_RADIUS_LO + EXTEND_RADIUS_SPAN * e;
                s.angleScale = EXTEND_ANGLE_LO + EXTEND_ANGLE_SPAN * (1.0 - e);
            }
            case FIGURE8 -> {
                s.reversal = ((t / FIGURE8_LEG_TICKS) % 2 == 0) ? 1.0 : -1.0;
            }
            default -> {
            }
        }
    }

    /* ==================== 读数（tick() 之后读） ==================== */

    /** 半径倍率（乘在 {@link CombatOrbit#radius} 抽到的那一圈上；调用方仍须夹进 [近端, 最远距离]） */
    public static double radiusScale(UUID id) {
        State s = STATE.get(id);
        return s == null ? 1.0 : s.radiusScale;
    }

    /** 角速度倍率（乘在"固定线速度 / 半径"换算出来的那一步上） */
    public static double angleScale(UUID id) {
        State s = STATE.get(id);
        return s == null ? 1.0 : s.angleScale;
    }

    /**
     * 高度增量（格，**只加不减**）：{@code amp} 由调用方传（配置项），本类不碰配置。
     *
     * <p>只有 YOYO 会返回非零值，且 {@code 0 <= 返回值 <= amp}——这就是"基础比敌人高多少格
     * 这一点还是要的"的硬保证：任何机动都不会把她压到基准高度以下。
     */
    public static double heightAdd(UUID id, double amp) {
        State s = STATE.get(id);
        if (s == null || amp <= 0.0) {
            return 0.0;
        }
        return amp * s.heightNorm;
    }

    /** 旋向翻转（{@code ±1}）：乘在 {@link CombatOrbit#direction} 上，只有 8 字横切会翻 */
    public static double reversal(UUID id) {
        State s = STATE.get(id);
        return s == null ? 1.0 : s.reversal;
    }

    /**
     * 这只女仆的**起飞方位偏置**（度，稳定值 {@code [-maxDeg, +maxDeg]}）——近战空袭的
     * "别叠罗汉"用。
     *
     * <p>【为什么近战空袭需要它】近战空袭没有"盘旋"这一段，它是"背离敌人抬头爬升（30 tick）
     * → 压低机头俯冲 → 收翅猛击"的一轮循环。多只女仆同时接同一个敌人时，**爬升那 1.5 秒
     * 她们飞的是同一条直线**（都朝"敌人反方向 + 向上"），那就是玩家说的"叠罗汉"在近战这边的
     * 形态。给每只女仆一个只跟 UUID 有关的方位偏置之后，她们的爬升线天然叉开。
     *
     * <p>【为什么偏置只用在爬升、不用在俯冲】俯冲的朝向就是瞄准方向，偏一点点都会掉命中率
     * （这个模组在 实测四百七十三 / 四百八十三 上专门修过命中）。爬升朝向本来就是"背离敌人"
     * 这种不需要精确的方向，偏十几度没有任何代价。
     */
    public static double azimuthSpread(UUID id, double maxDeg) {
        if (id == null || maxDeg <= 0.0) {
            return 0.0;
        }
        long h = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 29);
        double u = ((h >>> 8) & 0xFFFFL) / 65535.0; // 0~1
        return (u * 2.0 - 1.0) * maxDeg;
    }

    /* ==================== 内部：抽签 ==================== */

    /**
     * 按权重抽一种机动：由 UUID + 第几次遭遇派生，同一输入永远同一输出。
     *
     * <p>【为什么不是真随机】真随机会在每次重进世界时换（她当着你的面换打法），而"UUID + 遭遇序号"
     * 是稳定的：同一份存档、同一场仗，重放出来还是同一种机动。日志与实测才能对上号。
     */
    private static Kind pick(UUID id, int serial) {
        double r = rand01(id, serial);
        if (r < W_ORBIT) {
            return Kind.ORBIT;
        }
        r -= W_ORBIT;
        if (r < W_WEAVE) {
            return Kind.WEAVE;
        }
        r -= W_WEAVE;
        if (r < W_YOYO) {
            return Kind.YOYO;
        }
        r -= W_YOYO;
        if (r < W_EXTEND) {
            return Kind.EXTEND;
        }
        return Kind.FIGURE8;
    }

    /** 0~1 的稳定随机：由 UUID 与"第几次遭遇"派生（同 {@link CombatOrbit} 的混洗形状） */
    private static double rand01(UUID id, int serial) {
        long h = id.getMostSignificantBits()
                ^ Long.rotateLeft(id.getLeastSignificantBits(), 17)
                ^ ((long) serial * 0x9E3779B97F4A7C15L)
                ^ PICK_SALT;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return (h >>> 11) / (double) (1L << 53);
    }
}
