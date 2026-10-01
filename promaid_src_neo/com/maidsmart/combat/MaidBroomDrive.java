package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v1.3.0「扫帚模式」的骑乘 / 悬停 / 战斗盘旋驱动（两树镜像）。
 *
 * ── 为什么必须由我们驱动（TLM 的扫帚 API 不够用）──
 * TLM 的扫帚是**真载具**（{@code EntityBroom extends AbstractEntityFromItem extends LivingEntity}），
 * 也确实留了 {@code IBroomControl} 扩展点。但字节码实证：
 * <pre>
 *   EntityBroom.getControllingPassenger()  →  只有【第一乘客是 Player】时才返回非空
 *   EntityBroom.travel(Vec3)               →  只有"控制乘客是 Player 且第二乘客是 EntityMaid"
 *                                             才遍历 broomControls 调 IBroomControl；
 *                                             否则走重力下坠分支
 * </pre>
 * 也就是说 {@code IBroomControl} 的**每一个回调都要求一个 Player 参数**——它是给
 * "玩家驾驶、女仆搭乘"设计的。**女仆单骑**这条路上没有任何回调点：她一个人骑上去，
 * {@code getControllingPassenger()} 返回 null，扫帚直接自由落体。
 * 所以"女仆独立骑扫帚"只能由我们在 {@code EntityBroom.travel} 上接管
 * （见 {@link com.maidsmart.mixin.EntityBroomMaidTravelMixin}）：
 * **没有玩家驾驶时**，用本类写下的推进意图替它飞；**有玩家驾驶时一个字都不改**。
 *
 * ── 本类与行为的分工 ──
 * <ul>
 *   <li>{@link MaidBroomBehavior} 是"大脑"：决定**去哪**（打谁、跟谁、守在哪）、以及
 *       什么时候进入"起飞 / 战斗爬升"这两个相位；</li>
 *   <li>本类是"手"：把"去哪"翻译成**这一 tick 的速度矢量与朝向**，写在 {@link #THRUST} /
 *       {@link #YAW} 里，由 mixin 读取并消费。</li>
 * </ul>
 * 写在表里而不是直接 {@code move()}，是因为**行为 tick 与实体 tick 的先后顺序不保证**
 * （服务端按实体列表顺序），中间隔一 tick 的延迟完全无感，但"谁在什么时候动"这件事
 * 必须只有一个执行者——真正调 {@code move()} 的永远只有 mixin 那一处。
 *
 * ── 为什么"骑扫帚时反复被拉回"（实测六百五十六）——三段因果，全在 vanilla ──
 * <pre>
 *   ServerLevel.tickNonPassenger(载具) → 载具先 tick（扫帚移动 + positionRider 把她摆上鞍位）
 *   ServerLevel.tickPassenger(载具, 乘客) → 乘客 rideTick():
 *        Entity.rideTick():  setDeltaMovement(ZERO)
 *                            this.tick()            ← 【她照样跑自己的整条 aiStep】
 *                            getVehicle().positionRider(this)  ← 【再被按回鞍位】
 * </pre>
 * 也就是说：**每个 tick 都先让乘客按自己的逻辑走一步、再把她拽回鞍位**。对一个没有移动
 * 输入的普通生物这无感；可本模组有一整套"地面走位"（战斗战术的 navigateAway/Orbit、
 * 自保的垫高/逃跑/setPos、威胁驱动的直连导航），它们会在这半拍里把她推走，紧接着被扫帚
 * 拉回来——每 tick 重演一次，玩家看到的就是「女仆在乘坐扫帚的时候会反复被拉回」。
 * 修法不在本类，而在那一整套走位：**她骑扫帚期间全体让位**
 * （见 {@code MaidCombatTacticsBehavior.isTacticsEnabled}、{@code SelfPreservationBehavior.tick}
 * 与 {@code NeutralThreatDriver.navigateTo} 里 {@code MaidBroomKit.isBroomAirborne} 那三处闸）。
 *
 * ── 移动速度：照搬原版，一点不加 ──
 * 玩家原话「扫帚的移动速度肯定是只能照搬原版，不能有所加速的」。所以物理参数不是我们
 * 拍的，全部来自 TLM 给玩家驾驶写的那份 {@code PlayerBroomControl.travel}（字节码实证）：
 * <pre>
 *   float forward  = zza * 0.375f;            // W 全按
 *   float vertical = 0.25f;                   // 跳跃键（潜行是 -0.2）
 *   double speed   = max(playerFlySpeed - 0.1, 0) * 2.5 + 0.1;   // 玩家默认 = 0.1
 *   Vec3 target    = new Vec3(strafe, vertical, forward).scale(speed * 20.0).yRot(-yawRad);
 *   broom.setDeltaMovement(currentMotion.lerp(target, 0.25));    // 有输入
 *   broom.setDeltaMovement(currentMotion.scale(0.75));           // 无输入
 * </pre>
 * 换算成"速度上限"就是：水平 {@code 0.375 × 0.1 × 20 = 0.75} 格/tick、竖直（跳跃键那一档）
 * {@code 0.5}。本类**照这个包络做比例导引**（{@link #MAX_H_SPEED} / {@link #MAX_V_SPEED}），
 * 并照搬它的两个阻尼系数（{@link #BLEND} 有输入、{@link #IDLE_DECAY} 无输入），
 * 所以"手感"与原版一致、**速度一分不加**；不同的是目标速度由"距离"算出来而不是由键盘
 * 开关决定——因为 AI 没有键盘，而"按满 W 直到冲过目标点"在自动飞行里只会变成绕圈。
 *
 * ── 两个相位（实测六百五十六，玩家原话）──
 * <ol>
 *   <li><b>起飞</b>：「如果拿到了扫帚，原地往上飞 1 格悬停（头顶如果被顶住了那就悬停在此处）」
 *       ——见 {@link #takeoffTarget}；</li>
 *   <li><b>接敌爬升</b>：「遇到敌人之后，先向上飞 8 格（头顶如果被顶住了，那就悬停在此处），
 *       然后绕着敌人盘旋」——见 {@link #combatClimbTarget}。</li>
 * </ol>
 * 两条都走同一套"爬升相位"（{@link #CLIMB}）：**升不动就算到底**——靠"连续几 tick 没长高"
 * 判断头顶被顶住（原版 {@code move} 撞到方块自然就升不上去），不需要另外查一遍方块。
 */
public final class MaidBroomDrive {

    private MaidBroomDrive() {
    }

    /* ==================== 状态表（按 UUID，异常一律吞掉） ==================== */

    /** 扫帚 UUID → 本 tick 的推进矢量（行为写、mixin 读后**移除**） */
    private static final Map<UUID, Vec3> THRUST = new HashMap<>();
    /** 扫帚 UUID → 本 tick 的朝向（度） */
    private static final Map<UUID, Float> YAW = new HashMap<>();
    /**
     * 我们放出去的那些扫帚：扫帚 UUID → **当初从她身上抽走的那一件物品**。
     * 收工时按这一份精确归还（连自定义名字一起），而不是让原版 {@code killEntity()} 掉在地上
     * ——那样东西会散在离她几格远的地方，玩家得自己去捡。
     */
    private static final Map<UUID, ItemStack> DEBT = new HashMap<>();
    /** 女仆 UUID → 战斗盘旋的方位角（弧度），每 tick 缓慢增长 → "绕着她打" */
    private static final Map<UUID, Double> ORBIT = new HashMap<>();
    /**
     * 女仆 UUID → **工作范围盘旋**的方位角（弧度）：守家（home）时她沿工作范围那个圈转的相位。
     * <p>
     * 与 {@link #ORBIT}（绕着**敌人**转）分开存：接敌用敌人那一份、平时用这一份。共用同一个
     * 角度变量会让两边轮流加角度——表现为"刚打完忽然跳半个圈"。
     */
    private static final Map<UUID, Double> HOME_ORBIT = new HashMap<>();
    /** 女仆 UUID → 当前爬升相位（起飞 / 接敌） */
    private static final Map<UUID, Climb> CLIMB = new HashMap<>();
    /**
     * 女仆 UUID → **这一场遭遇的盘旋高度**（相对敌人脚底的格数）。
     *
     * <p>【为什么需要它（v1.3.3 实测六百五十八，玩家原话："遇到敌人的时候会先上升 8 个，
     * 但是随后又慢慢掉下来了，那这样子的意义在哪里？"）】旧版 {@link #combatPoint} 的高度
     * 写死成 {@code 目标脚底 + combat.broomHover}（默认 2），而接敌爬升是"在她自己脚下 +8"——
     * 两处对高度的口径不一样，于是"先爬 8 格、再滑回敌上 2 格"，爬升白爬。
     * 现在把两处接起来：**爬升相位的唯一职责就是决定这一场遭遇的盘旋高度**
     * （{@link #combatClimbTarget} 完成时把它记在这里），盘旋照这个高度飞，掉不下来。
     */
    private static final Map<UUID, Double> COMBAT_ALT = new HashMap<>();
    /**
     * 【实测六百九十二】女仆 UUID → **她这场守家巡逻的高度**（绝对 Y + 当时那个圈心）。
     *
     * <p>玩家原话：「女仆在处于 home 模式的状态下最好还是要尝试让自己的高度尽可能保持启动盘旋的
     * 高度（躲建筑只是暂时调整高度）。防止女仆在躲避其他建筑物的时候越飞越高。如果女仆飞得太高了，
     * 那么虽然会在那个位置上盘旋，但是之后的攻击、链路等方面就都不会触发了。」
     *
     * <p>它是 {@link #homeOrbitPoint} 在"**脚下找不到地**"时的唯一参照：{@link #groundYOrNaN} 找不到
     * 地面（虚空 / 她比地形高出 {@link #GROUND_SCAN} 格以上）时，目标高度取
     * {@code min(她当前高度, 这份记忆)} —— **只降不升**。旧版那一档是 {@code fromY + homeAlt}
     * （"没找到地就当她悬在虚空上原地不动" + 离地偏移），于是在地形之上每拍都 +8 格 = 无上限爬升，
     * 正是玩家说的"躲避建筑物的时候越飞越高"。
     *
     * <p>只在"能算出地面高度"的时候刷新（并记下当时的圈心：换了一个工作区就不认旧记忆）；
     * 圈心对不上 / 还没巡逻过 → 记忆为空，那一档退化成"停在现在的高度"（不升也不降）。
     */
    private static final Map<UUID, PatrolAlt> HOME_ALT = new HashMap<>();

    /** {@link #HOME_ALT} 的一项：那一场巡逻的高度 + 它属于哪个圈心 */
    private static final class PatrolAlt {
        final double cx;
        final double cz;
        final double y;

        PatrolAlt(double cx, double cz, double y) {
            this.cx = cx;
            this.cz = cz;
            this.y = y;
        }
    }
    /** 女仆 UUID → 这一轮"找扫帚"的起算毫秒（去找世界里放着的那把 / 地上掉的那件） */
    private static final Map<UUID, Long> HUNTING = new HashMap<>();
    /** 女仆 UUID → 放弃找扫帚后的冷却到期毫秒（卡墙/够不着时别每 tick 重试） */
    private static final Map<UUID, Long> HUNT_COOLDOWN = new HashMap<>();

    /* ==================== 诊断留痕（只写日志，不参与任何判定） ==================== */

    /**
     * 已经记过"她本来就骑着一把扫帚（不是我们放的）"的女仆——每只一次。
     * <p>
     * 【为什么要有这条】实测里最容易误判的一件事：日志里没有「取出扫帚骑上」时，到底是
     * ①"没扫帚没骑上"还是②"她已经在扫帚上了"？旧版这两种情况的日志**长得一模一样**
     * （都是什么都不打，因为缺件气泡那边看到"扫帚物品在"也不会报）。现在②会留痕，
     * 配合「扫帚接管」（mixin 那侧）能一眼看出驱动链路通没通。
     */
    private static final Set<UUID> ADOPTED_LOGGED = new HashSet<>();
    /** 已经记过"想飞却没骑上"的女仆 → 上次记的时间（毫秒），只用于日志限频 */
    private static final Map<UUID, Long> NO_DRIVE_LOGGED = new HashMap<>();
    /** 诊断日志的最小间隔（毫秒）：同一只女仆 5 秒最多一条（纯观感，与游戏逻辑无关） */
    private static final long LOG_GAP_MS = 5000L;

    /* ==================== 物理常量（全部照搬 PlayerBroomControl，见类注释） ==================== */

    /**
     * 水平速度上限（格/tick）= 原版 W 全按那一档：{@code 0.375 × ((0.05-0.1→0)×2.5+0.1) × 20}
     * { 其中 0.05 是玩家默认 {@code FLYING_SPEED}，算下来 {@code speed = 0.1} }。
     * <b>这是"原版全速"，不加速</b>——玩家点名要求。
     */
    private static final double MAX_H_SPEED = 0.75;
    /**
     * 竖直速度上限（格/tick）。原版跳跃键那一档是 {@code 0.25 × 0.1 × 20 = 0.5}；
     * 这里刻意取它的一半多一点：原地起飞与接敌爬升都是"整体位移"而不是"冲刺"，
     * 0.5 格/tick ≈ 10 格/秒会把"抬 1 格"演成一跳。**只会比原版慢，不会比原版快。**
     */
    private static final double MAX_V_SPEED = 0.30;
    /** 原版有输入时的阻尼：{@code currentMotion.lerp(targetMotion, 0.25)} */
    private static final double BLEND = 0.25;
    /** 原版无输入时的衰减：{@code currentMotion.scale(0.75)} → 几 tick 内收干悬停 */
    private static final double IDLE_DECAY = 0.75;

    /** 到达判定（格）：进入这个距离视为"已到位"，速度走原版"无输入"那一支自然收干 */
    private static final double ARRIVE = 0.35;
    /**
     * 到点减速带（格）：距离小于此值开始线性降速（目标速度 ∝ 距离），到点自然收干不冲过头。
     * <p>
     * 这一条**取代**了"按满 W"那套二进制输入——原因见类注释：AI 没有键盘，而"按满 W 直到
     * 冲过目标点"在自动飞行里只会变成绕圈。阻尼本身还是原版的 {@link #BLEND}。
     */
    private static final double ARRIVE_RAMP = 1.5;

    /** 起飞相位要抬的高度（格）——玩家要求"女仆会立刻用扫帚飞起来 1 格" */
    private static final double RISE_BLOCKS = 1.0;
    // 【实测六百七十八】接敌爬升的高度**不再写死 8 格**：改成配置项 {@code combat.broom.climb}
    //  （实测六百八十六 起默认 12，2~32，面板「移动与行为 → 扫帚模式 → 接敌爬升高度」可调），见下面的 climbCfg()。
    //  玩家原话："考虑到现在加入了这个模式，那么女仆需要飞的再高一点，默认应该是10格的高度。
    //  （之前的扫帚模式盘旋是8格）"——"这个模式"指武装拴绳二号位：你吊在她下方 2.6~2.9 格，
    //  她飞高一点，你脚下才有余量、不会一路蹭着树冠/地面。旧存档里写死的 8 不会自动变。
    /**
     * 爬升相位的目标 Y 写在"想抬的高度 + 这个余量"处（{@link #ARRIVE} 格）。
     * <p>
     * 为什么要这个余量：她到位的方式是 {@link #steerTo} 的比例导引，而那一支在距目标
     * {@link #ARRIVE} 格时就判定"到点、收干悬停"——所以想让她**真的抬满 1 / 10 格**，
     * 目标就得写在"到点线"之上 {@link #ARRIVE} 格处。两边共用同一个常量，改一处两边一起动。
     */
    private static final double CLIMB_LEAD = ARRIVE;
    /** 爬升相位的触发起飞标识（与"目标 UUID"这种 key 并列，只有一个语义：这一次爬升是谁要的） */
    private static final Object TAKEOFF = new Object();
    /** 弧度→度（凋灵那行 `* 57.295776F`，也是 MC 的 yaw 约定） */
    private static final float DEG = 57.295776F;
    /**
     * 爬升相位的"升不动"判定：连续这么多 tick 没有长高就算头顶被顶住（见 {@link Climb#stalled}）。
     *
     * <p>【实测六百八十四】4 → 10。4 tick（0.2 秒）比"起手加速"还短：{@link #BLEND} 那一支从
     * 零起步的第一拍只涨 0.075 格，只要有一次方块角/叶子的擦碰把这一拍抹平，本场遭遇的盘旋高度
     * 就被钉在地板上（实测日志里 2.00 / 2.93 / 3.66 格就是这么来的）。
     *
     * <p>【实测六百八十六】10 → 20（1 秒），并且**配合下面那条"整段净涨 &lt; 0.5 格"一起判**
     * （{@link #STALL_MIN_PROGRESS}）：`stall` 数的是"这一拍涨了没有"，而她在被 boss 撞一下、
     * 被风弹吹一下、客户端/服务端位置同步抖一下的时候都可能连着几拍不涨——只看这个计数，10 拍
     * （0.5 秒）仍然太短，一抖就被判成"顶头"，本场盘旋高度当场被钉到"敌上 2 格"（玩家原话
     * 「只是在比敌人稍微高一点点的地方盘旋，就是不升高自己的高度」）。正常爬满 12 格要走 2.4 秒，
     * 20 拍该涨 6 格，所以"20 拍里净涨不到半格"才是那个不会误判的判据。
     */
    private static final int STALL_TICKS = 20;
    /**
     * 【实测六百八十六】"真顶住了"的**第二个**条件：从相位开始到现在，整段一共抬起来还不到这么多格。
     *
     * <p>为什么光看"连续几拍没涨"不够：正常爬升是 {@link #MAX_V_SPEED} 那条 0.30 格/tick 的包络，
     * 20 拍该涨 6 格；而抖动/擦碰只会抹掉一两拍。反过来，真被天花板压着时她的**净位移**是 0
     * （甚至因为到点那一支的收干而微降）。所以"连着 20 拍几乎没涨 **且** 整段净涨不到 0.5 格"
     * 才是那个不会误判的判据——它同时挡住了"起手那一两拍被抹平，整场高度就被钉死"这一类误判。
     */
    private static final double STALL_MIN_PROGRESS = 0.5;
    /**
     * 【实测六百八十四】头顶被顶住之后，最多隔这么久再试一次爬升（tick）。
     *
     * <p>旧版是"一场遭遇只爬一次"，于是她只要在低矮房间里挨过第一次爬升，**整场**都按那几格飞，
     * 走进开阔地也升不回去（实测日志：16:46:15 钉在 3.66 格 →「以后一直保持这个高度」）。
     * 重试还要过一个"头顶现在是不是空气"的探针（见 {@link Climb#roomNow}）：房间里照样是顶的
     * 时候一次都不试，她不会为了够一个够不到的高度在原地一抽一抽。
     *
     * <p>【实测六百八十六】100 → 40（2 秒）：等待从"5 秒起、翻倍到 60 秒"缩到"2 秒起、翻倍到
     * 30 秒"。旧门槛下，"被 boss 撞得几拍没涨"这种一次性的误判要 5 秒以上才自愈，玩家看到的
     * 就是"一整场都在敌人上方两格盘旋、怎么都不升高"（他的原话）。②那条头顶探针本来就把
     * "真被天花板压着"那一档挡住了（房间里的头顶一直是方块 → 一次都不试），所以把等待压短是安全的。
     */
    private static final int CLIMB_RETRY_TICKS = 40;
    /**
     * 【实测六百八十四】连续重试失败时的退避上限（tick）：
     * 每失败一次等待翻倍（40 → 80 → …），封顶在这里。理由：真被天花板压着的房间里，
     * 反复试、每次都失败、每次都写两行日志——既没用又刷屏。退避到半分钟一次之后，
     * 她在同一个房间里打十分钟也只有十来行。
     *
     * <p>【实测六百八十六】1200 → 600（60 秒 → 30 秒）：自愈的速度比"少刷几行日志"值钱。
     */
    private static final int CLIMB_RETRY_MAX_TICKS = 600;
    /**
     * 战斗盘旋的**线速度**（格/tick）：0.14 ≈ 2.8 格/秒。
     * <p>
     * 取"线速度恒定"而不是"角速度恒定"，是因为盘旋半径可配（默认 8 格）：角速度固定时
     * 半径越大、目标点的圆周线速度越大——她永远追不上那个点，表现成"绕不动、只在原地抖"。
     * 按线速度换算（{@code step = ORBIT_SPEED / 半径}）后，任何半径下她都能稳稳跟上。
     */
    private static final double ORBIT_SPEED = 0.14;
    /**
     * 【实测七百零一】接敌后**离敌的最小距离**（格）的**兜底默认值**（配置
     * {@code combat.broom.minStandoff} 读不到时用它，见 {@link #minStandoffCfg}）。
     *
     * <p>玩家原话：「应该要保证至少与怪物拉开多少距离」。旧版的环绕近端由"基础盘旋距离 × 0.75"
     * 现算（默认 8 × 0.75 = 6 格），**没有独立的下限**——玩家把「离敌最远距离」调小到 4，
     * 近端就塌到 3 格，她正好飘进近战怪的攻击范围。现在近端取
     * {@code max(区间近端, 本值)}：随机只发生在这条线之外，"拉开距离"这件事不再随另一个旋钮塌陷。
     */
    private static final double MIN_STANDOFF = 6.0;
    /** 平时跟随的水平距离（格） */
    private static final double FOLLOW_DIST = 3.5;
    /** 平时跟随的高度（格，相对主人脚下） */
    private static final double FOLLOW_HOVER = 2.0;

    /* ==================== "找扫帚"（扫帚模式的第一优先级） ==================== */

    /**
     * 找扫帚时的搜索半径（格）：她身上没有扫帚时会看这么远内的**扫帚**。
     * <p>
     * 【"找"的两档（v1.3.4 实测六百五十九，玩家原话："让她去骑世界里已经放着的那把扫帚实体"）】
     * <pre>
     *   ① 世界里**已经放着**的那把扫帚实体（{@code EntityBroom}）——走过去骑上（首选）；
     *   ② 地上掉着的那把扫帚**物品**——走过去收进背包（次选：收进来之后由
     *      {@link #ensureMounted} 照 v1.3.0 的老规矩放出来骑）。
     * </pre>
     * 两档都排在"跟随主人"之前——没扫帚时她不会先去跟主人（见 {@link #seekBroom}）。
     * <p>
     * 【哪些扫帚不碰】背包/精妙背包里躺着的那不叫"找"叫"取"——归 {@link #ensureMounted}
     * 直接抽出来放骑，这里不重复；**已经有乘客的扫帚不抢**（玩家正骑着的那把，或别的女仆
     * 已经骑上的那把）——只认空着的那种。
     */
    private static final double HUNT_RADIUS = 16.0;
    /**
     * 骑上判定（格）：走到这么近就直接上鞍。
     * <p>
     * 比 {@link #PICKUP_RANGE} 稍微放宽一点：扫帚实体的碰撞箱只有一格多，而"走到底"这件事
     * 是 TLM 的导航在做的（{@code MoveToTargetSink} 的到点精度本身就有余量）；2.5 格既够
     * 判"她已经走到它跟前了"，又不会让她站在两格外就凭空瞬移上去。
     */
    private static final double MOUNT_RANGE = 2.5;
    /** 捡起判定（格）：走到这么近就直接收进背包（不必等原版拾取） */
    private static final double PICKUP_RANGE = 2.0;
    /** 找扫帚的耐心（毫秒）：这么久还没走到（卡墙/够不着）就放弃这一轮，改报缺件 */
    private static final long HUNT_GIVE_UP_MS = 20000L;
    /** 找扫帚时的移动速度倍率（TLM 默认走路 1.0；给个正常的步行速度，不冲刺） */
    private static final float HUNT_SPEED = 1.0F;

    /* ==================== 骑上 / 下来 ==================== */

    /**
     * 确保她骑在一把扫帚上（已经骑着就原样返回）。
     *
     * 【扫帚从哪来】用她背包里的——主手 → 副手 → 背包 → 精妙背包/旅行者背包这类额外容器，
     * 抽走 1 件，在**她脚下**放出一把 {@code EntityBroom}（与玩家手持扫帚右键放置同款：
     * {@code setOwnerUUID(主人)} + 入世界 + 消耗那件物品），然后骑上去。收工时把**这一件**
     * 精确还回她背包（见 {@link #dismount}），所以扫帚不会损耗、也不会掉在地上。
     *
     * 【为什么认"主人"而不是"她"】{@code EntityBroom.canMaidRide} 要求女仆与扫帚的
     * ownerUUID 相等（字节码实证）；写成女仆自己会让别的女仆被 {@code pushEntities}
     * 顺带拽上来。挂主人头上与玩家自己放置完全一致。
     *
     * 【骑上即起一个"起飞相位"】玩家要求"原地往上飞 1 格悬停"。相位登记在这里，
     * 由 {@link MaidBroomBehavior} 每 tick 消费（见 {@link #takeoffTarget}）。
     *
     * @return 骑上的那把扫帚；null = 没扫帚 / 放不出来（调用方按"缺扫帚"处理）
     */
    public static EntityBroom ensureMounted(ServerLevel level, EntityMaid maid) {
        if (level == null || maid == null) {
            return null;
        }
        EntityBroom riding = MaidBroomKit.ridingBroom(maid);
        if (riding != null) {
            clearBroomless(maid); // 【实测六百七十二】骑着就说明"有扫帚"，把没扫帚的计时清掉
            noteAdopted(maid);
            return riding;
        }
        ItemStack taken = takeBroomItem(maid);
        if (taken.isEmpty()) {
            return null;
        }
        net.minecraft.world.entity.Entity prev = null;
        try {
            prev = maid.getVehicle(); // force 骑乘会把她从这上面叫下来，先记着是谁（日志用）
        } catch (Throwable ignored) {
        }
        EntityBroom broom = null;
        try {
            broom = new EntityBroom(level);
            broom.moveTo(maid.getX(), maid.getY(), maid.getZ(), maid.getYRot(), 0.0f);
            try {
                broom.setOwnerUUID(maid.getOwnerUUID());
            } catch (Throwable ignored) {
            }
            if (!level.addFreshEntity(broom)) {
                giveBack(maid, taken);
                return null;
            }
            // 【force = true】实测六百五十七：不 force 的 startRiding 要求她**此刻不是任何载具的
            // 乘客**（原版 `canRide` = `!isPassenger() && …`），而她会坐在椅子（我们自己的钓鱼椅
            // 就是靠 `startRiding(chair, true)` 让她坐上去的）或别的载具上——那样这里恒返回 false，
            // 于是**每 tick 取出一把扫帚、骑不上、再还回去**：面板上看不到任何报错（缺件判据看到
            // "扫帚物品在"就不报），她却永远上不去。与本模组其它上座点（FishingChairService）同款：
            // 要她上去就 force。
            if (!maid.startRiding(broom, true)) {
                // 连 force 都骑不上：把扫帚收掉、物品还她，不留孤儿实体（并且一定要留痕）
                com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 骑不上扫帚：startRiding(force) 返回 false（当前载具=" + entityName(maid) + "）→ 扫帚收回背包");
                broom.kill();
                giveBack(maid, taken);
                return null;
            }
            if (prev != null && prev != broom) {
                com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 她本来在 " + entityName(prev) + " 上 → 强制换到扫帚（那把载具本身不动）");
            }
        } catch (Throwable t) {
            if (broom != null) {
                try {
                    broom.kill();
                } catch (Throwable ignored) {
                }
            }
            giveBack(maid, taken);
            return null;
        }
        DEBT.put(broom.getUUID(), taken);
        ORBIT.remove(maid.getUUID());
        clearBroomless(maid); // 【实测六百七十二】取出来骑上了 → 没扫帚的计时清零
        // 玩家要求："女仆会立刻用扫帚飞起来 1 格"——登记起飞相位（她脚下 +1 格，另加
        // 到点判定的余量，见 CLIMB_LEAD），由行为先垂直抬起来、抬到位再开始"去哪"的正常逻辑。
        // 【实测六百七十二】走 startTakeoff：5 秒内已经起过一次就**不再重复抬**
        //（反复上/下扫帚时，每一轮都抬一格就是那个"不断攀升"的棘轮）。
        boolean lifted = startTakeoff(maid);
        mountLog(maid, "取出扫帚骑上（消耗 " + taken.getCount() + "x "
                + taken.getHoverName().getString() + "，收工时原物归还）→ "
                + (lifted ? "原地抬起 " + RISE_BLOCKS + " 格" : "5 秒内刚起过一次，不重复抬高度"));
        return broom;
    }

    /* ==================== 两个爬升相位（起飞 / 接敌） ==================== */

    /**
     * 起飞相位的目标 Y：还在抬就返回那个 Y（调用方应直线上升、先别管去哪）；
     * 抬到位（或头顶被顶住）就返回 null 并结束相位。
     */
    public static Double takeoffTarget(EntityMaid maid) {
        return climbTarget(maid, TAKEOFF);
    }

    /**
     * 接敌爬升相位的目标 Y：「遇到敌人之后，先向上飞 N 格，然后绕着敌人盘旋」——
     * N = 配置 {@code combat.broom.climb}（**实测六百八十六 起默认 12**，面板可调 2~32；
     * 六百八十二 起 15、六百七十八 起 10，再往前是写死的 8）。
     *
     * <p>**以目标（敌人）为单位起相位**：换了一个敌人就重新爬一次（同一场遭遇里
     * 只爬一次——所以相位做完是**留在表里打 done 标记**而不是删掉，删掉的话下一 tick
     * 又会当"新相位"从头爬，变成无限上蹿）。相位结束（到位 / 顶头）后返回 null，
     * 调用方转去 {@link #combatPoint} 盘旋。丢目标时由 {@link #clearClimb} 清掉，
     * 于是下一次接敌会重新爬。
     *
     * <p>── v1.3.3【"先升 8 格又慢慢掉下来"的修正】──
     * 旧版这里的目标 Y 是 {@code 她自己脚下 + N}，而盘旋高度是 {@code 目标脚底 + broomHover}：
     * 两处口径不同 → 爬完 N 格立刻按另一套高度往回落，玩家看到的正是"升上去又掉下来"，
     * 爬升毫无意义。现在爬升的 Y 改成 **{@code 目标脚底 + N}**（相对敌人，与盘旋同一个参照系），
     * 并在相位收尾（到位 / 顶头）那一刻把**这一场遭遇的盘旋高度**记进 {@link #COMBAT_ALT}
     * （顶头了就记实际抬到的高度，但至少 {@code broomHover}）——于是"爬 N 格"不是在演一段
     * 动画，而是在**决定盘旋高度**：升到敌上 N 格，就一直在敌上 N 格打。
     *
     * <p>── 实测六百八十四【"只比敌人高三格"的两个来源，都在这一个方法里】──
     * 玩家原话："扫帚模式绕着敌人盘旋射击似乎默认高出的高度仍然不够，实际测试下来差不多只比
     * 敌人高了三格左右……可能是某些配置没有生效。" 配置没有失效（日志实证：`combat.broom.climb`
     * 读出来就是 15.0，开阔处「爬升到位（y -58.81 → -44.93）→ 本场盘旋高度 = 敌人上方 15.00 格」
     * 一模一样地兑现）。问题在**这个相位把降下来的高度钉死了一整场遭遇**，加上换目标就重爬：
     * <pre>
     *   ① 目标来回换 → 重爬 → 谁在被顶住的高度低，就按谁的高度钉住（15 ↔ 3.66 来回跳）；
     *   ② 钉住之后不再重试 → 走进开阔地也升不回去（她只能一直"敌人上方 3 格"）。
     * </pre>
     * 现在改成：本场遭遇只爬一次、换目标只改写 key（①）；被顶住的高度照旧先按它飞，但每
     * {@link #CLIMB_RETRY_TICKS} 一次、且**头顶探针说现在有空间**时才重试爬升（②）。
     *
     * <p>── 实测六百八十六【"就是不升高自己的高度"的第三处根因】──
     * 玩家原话：「把扫帚盘旋的默认配置高度改为12格，而且我当时测试的环境是平地，但是女仆依然只是
     * 在比敌人稍微高一点点的地方盘旋，就是不升高自己的高度。」前两处（①②）修完之后还剩一处：
     * **"顶头"的判据太敏感、而且一顶就把高度钉死**——连 10 拍（0.5 秒）没涨就算顶头（boss 撞一下、
     * 风弹吹一下、位置同步抖一下都够），顶头那一刻就把 {@link #COMBAT_ALT} 写成"此刻相对敌人的
     * 高度"（日志里 2.00 / 2.93 / 3.66 格就是这么来的），此后整场都按那个数飞，要等重试把它抬回来。
     * 现在两处一起改：判据加一条"整段净涨不到 {@link #STALL_MIN_PROGRESS}"（见 {@link Climb#stalled}），
     * 且 {@link #COMBAT_ALT} **只升不降**（一次顶头抬不高，但绝不会把已经飞到的高度拽下来）。
     *
     * @param target 当前敌人（取它的 UUID 当相位的 key、取它的脚底当高度参照）
     */
    public static Double combatClimbTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null) {
            return null;
        }
        double climb = climbCfg(); // 【实测六百七十八】配置项：默认 10（旧版写死 8）
        Object key = target.getUUID();
        java.util.UUID id = maid.getUUID();
        Climb c = CLIMB.get(id);
        if (c == null) {
            // 【相对敌人】目标脚底 + climb（另加 ARRIVE 余量，见 CLIMB_LEAD）
            double to = target.getY() + climb + CLIMB_LEAD;
            startClimb(maid, key, to);
            COMBAT_ALT.remove(id); // 新遭遇 → 高度重新由这一次爬升决定
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 接敌 → 先爬到它上方 " + fmt(climb) + " 格（高度从这以后一直保持）");
            return to;
        }
        if (c.done) {
            // 【实测六百八十四：这一场遭遇的高度已经定下来了】
            //  ① 换目标**不重爬**。旧版把"相位 key = 敌人 UUID"同时当成"换敌人就重爬一次"，
            //     而 TLM 每 tick 重挑 ATTACK_TARGET —— 两个敌人轮流被选中时，她就在
            //     "爬到 15 格" 与 "爬到 3 格" 之间来回切（实测日志 16:46:15 与 16:46:18
            //     各一次，只隔 3 秒）。她现在的高度由 {@link #COMBAT_ALT} 全权决定，
            //     换目标只需把相位记的 key 改写成新敌人，一个字节的高度都不动。
            //  ② 被顶住过 → 过一会儿（或头顶开了）再试一次，试成了就爬满。
            if (!c.key.equals(key)) {
                c.key = key;
            }
            if (c.retryDue(maid)) {
                double to = target.getY() + climb + CLIMB_LEAD;
                int tries = c.retries + 1;
                startClimb(maid, key, to);
                Climb fresh = CLIMB.get(id);
                if (fresh != null) {
                    // 退避计数跟着走：startClimb 建的是新相位，不带上它就等于每次都"第一次"重试
                    fresh.retries = tries;
                }
                com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 重试爬升（第 " + tries + " 次）：头顶现在有空间了（上次被顶住后隔了 "
                        + c.sinceBlocked + " tick、挪了 " + fmt(c.movedSince(maid))
                        + " 格）→ 再爬一次 " + fmt(climb) + " 格");
                return to;
            }
            return null; // 这一场遭遇已经爬过了，直接盘旋
        }
        Double y = climbTarget(maid, key);
        if (y == null) {
            // 相位刚刚收尾（到位 / 头顶被顶住）→ 把这一场遭遇的盘旋高度定下来：
            // 取"她此刻相对敌人的高度"，夹进 [broomHover, climb]——
            // 顶头只抬到 3 格就按 3 格飞（不会为了够那个够不到的高度一直往上顶），
            // 但也绝不比原来的悬停高度更低。
            double alt = maid.getY() - target.getY();
            double fixed = Math.max(hoverCfg(), Math.min(climb, alt));
            // 【实测六百八十六】本场遭遇的盘旋高度**只升不降**：这一拍顶住了（或者只是被撞得
            //   几拍没涨），不该把之前已经飞到的那个高度一笔勾销——旧版正是这么把 15 格一路拽到
            //   2.00 格的（玩家原话「只是在比敌人稍微高一点点的地方盘旋，就是不升高自己的高度」）。
            //   下面那句重试只会把它抬回 climb，所以这里取"两者更高的那个"，上限仍是 climb。
            Double prevAlt = COMBAT_ALT.get(id);
            if (prevAlt != null && prevAlt > fixed) {
                fixed = Math.min(climb, prevAlt);
            }
            COMBAT_ALT.put(id, fixed);
            Climb now = CLIMB.get(id);
            boolean blocked = now != null && now.blocked;
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 本场盘旋高度 = 敌人上方 " + fmt(fixed) + " 格（想 " + fmt(climb)
                    + "，实际 " + fmt(alt) + " 格，本场只升不降）"
                    + (blocked
                            ? "→ 头顶被顶住，先按这个高度飞；头顶一开阔就再爬一次（每 "
                                    + (CLIMB_RETRY_TICKS / 20) + " 秒最多试一次）"
                            : "→ 以后一直保持这个高度"));
        }
        return y;
    }

    /**
     * 结束爬升相位（没目标了 / 收了工）——顺带作废这一场遭遇的盘旋高度。
     *
     * <p>【实测七百〇四：顺手把随机环绕与接敌机动也丢干净】玩家反馈「打了那么多场都一直在用环绕」，
     * 根因就在这一格：{@link CombatOrbit} 与 {@link CombatManeuver} 的状态**只在换任务/下线时**
     * （{@link #forgetMaid}）才清，而"一场遭遇结束"（丢目标、下鞍、玩家接管）走的是本方法——
     * 于是 {@code CombatManeuver.begin} 的幂等语义变成了"一次抽签用一整天"：她第一场抽到环绕，
     * 之后每一场都还是环绕。本方法现在是**这一场遭遇的收尾点**，两套随机都从这里重掷，
     * 「每场遭遇抽一种」才真的成立（下一场一定看得见换打法）。
     */
    public static void clearClimb(EntityMaid maid) {
        if (maid != null) {
            CLIMB.remove(maid.getUUID());
            COMBAT_ALT.remove(maid.getUUID());
            // 【实测七百〇四】这一场遭遇结束 = 两套随机都作废，下一场重抽（含"随机环绕"那一行日志）
            CombatOrbit.forget(maid.getUUID());
            CombatManeuver.forget(maid.getUUID());
        }
    }

    private static void startClimb(EntityMaid maid, Object key, double targetY) {
        CLIMB.put(maid.getUUID(), new Climb(key, targetY, maid.getY()));
    }

    /**
     * 爬升相位的统一推进：返回这一 tick 该爬到的 Y，null = 相位已结束（或不该由本相位管）。
     *
     * 【"头顶被顶住"怎么判】不查方块，看**她自己有没有长高**：原版 {@code Entity.move}
     * 撞到方块就动不了，所以"想往上、却没上去"这件事本身就是"被顶住了"的证据。
     * 这比查碰撞箱更稳——半个砖、楼梯、火把、水都各自有各自的碰撞形状，而"没长高"是事实。
     */
    private static Double climbTarget(EntityMaid maid, Object key) {
        Climb c = CLIMB.get(maid.getUUID());
        if (c == null || !c.key.equals(key)) {
            return null;
        }
        if (c.done) {
            return null;
        }
        double y = maid.getY();
        if (y >= c.targetY - ARRIVE) {
            // 与 steerTo 的"到点"用同一个阈值：它在距目标 ARRIVE 格时收干悬停，所以这里
            // 也用 ARRIVE 判定完成——阈值写小了会出现"她已经停住、相位却永远不结束"。
            c.done = true;
            c.blocked = false; // 【实测六百八十四】这一档是"爬到位"，没有重试可言
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 爬升到位（y " + fmt(c.startY) + " → " + fmt(y) + "，想抬 " + fmt(c.lift)
                    + " 格）");
            return null;
        }
        c.observe(y);
        if (c.stalled()) {
            c.done = true;
            // 【实测六百八十四】记成"被顶住"（而不是"这个高度就是本场的定论"）：她换到开阔处
            //  之后会重试爬升，见 combatClimbTarget 的 ② 与 Climb.retryDue。
            c.blocked = true;
            c.noteBlocked(maid);
            // 【日志要能分辨两种"顶头"】真被方块顶住 vs 驱动根本没生效。两者在旧日志里都是
            // 一句"头顶被顶住"，但处置完全不同（前者正常、后者是 bug）。所以这里把起止高度、
            // 实际抬了多少格、**头顶那三格到底是什么**一起写出来：
            // 抬了 0 格 + 头顶全是空气 = 驱动没生效（该查驱动）；头顶是方块 = 真撞到东西了。
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 头顶被顶住 → 就地悬停（y " + fmt(c.startY) + " → " + fmt(y) + "，"
                    + STALL_TICKS + " tick 没长高、整段只抬了 " + fmt(y - c.startY) + " 格）"
                    + " 头顶：" + c.headBlocks(maid));
            return null;
        }
        return c.targetY;
    }

    /** 一个爬升相位：key = 谁要的（起飞哨兵 / 敌人 UUID），targetY = 想爬到的 Y */
    private static final class Climb {
        /** 【实测六百八十四】不再 final：换目标只改写它（不重开相位，见 combatClimbTarget） */
        Object key;
        final double targetY;
        /** 相位开始时的 y（日志用：起止一对比就知道是真顶头还是驱动没生效） */
        final double startY;
        /** 想抬的格数（= targetY - startY，含 {@link #CLIMB_LEAD} 余量；日志用） */
        final double lift;
        /** 相位是否已经做完——**留在表里**，否则下一 tick 会被当成新相位重新爬 */
        boolean done;
        /**
         * 【实测六百八十四】这一次收尾是"真的被方块顶住"（true）还是"爬到位"（false）。
         * 顶住的那一档才有重试可言（见 {@link #retryDue}）。
         */
        boolean blocked;
        /** 顶住之后过了多少 tick（由 gameTime 差值现算，见 retryDue） */
        int sinceBlocked;
        /** 【实测六百八十四】这一场遭遇里已经重试过几次（退避翻倍用，见 retryDue） */
        int retries;
        /** 被顶住时她在哪儿 + 那是哪一 tick（重试判据与日志都用它） */
        private double bx;
        private double bz;
        private long blockedAt;
        private double lastY;
        private int stall;

        Climb(Object key, double targetY, double startY) {
            this.key = key;
            this.targetY = targetY;
            this.startY = startY;
            this.lift = targetY - startY;
            this.lastY = startY;
        }

        /** 记下这一 tick 的高度：几乎没长高就累计，长高了就清零 */
        void observe(double y) {
            if (y - this.lastY < 0.01) {
                this.stall++;
            } else {
                this.stall = 0;
            }
            this.lastY = y;
        }

        boolean stalled() {
            // 【实测六百八十六】两个条件一起看：连着这么多拍几乎没涨（stall）**且**整段净涨
            // 不到 STALL_MIN_PROGRESS——后者才是"真顶住了"，前者只是不让起手那一两拍误触。
            return this.stall >= STALL_TICKS && (this.lastY - this.startY) < STALL_MIN_PROGRESS;
        }

        /** 【实测六百八十四】记下"在哪儿、哪一 tick 被顶住的"（重试的判据与日志都用它） */
        void noteBlocked(EntityMaid maid) {
            try {
                this.bx = maid.getX();
                this.bz = maid.getZ();
                this.blockedAt = maid.level().getGameTime();
            } catch (Throwable ignored) {
                this.blockedAt = 0L;
            }
            this.sinceBlocked = 0;
        }

        /** 从被顶住那一点挪开了多远（格，只看水平） */
        double movedSince(EntityMaid maid) {
            try {
                double dx = maid.getX() - this.bx;
                double dz = maid.getZ() - this.bz;
                return Math.sqrt(dx * dx + dz * dz);
            } catch (Throwable ignored) {
                return 0.0;
            }
        }

        /**
         * 【实测六百八十四】这一拍该不该重试爬升——两个前提都要满足：
         * <pre>
         *   ① 隔够了这一次的等待时间（{@link #CLIMB_RETRY_TICKS} 起、每失败一次翻倍、
         *      {@link #CLIMB_RETRY_MAX_TICKS} 封顶）；
         *   ② {@link #roomNow}：她头顶现在是空气（真有地方可以上去）。
         * </pre>
         * ②是防抖动的那一半：低矮房间里头顶一直是方块，条件永不成立 → 一次都不重试，
         * 她照常绕着敌人盘旋（不做"每 5 秒原地一抽"）。等她自己飞进高一点的地方，②立刻成立，
         * 到点就重爬。读不到方块时按"可以试"处理（最坏 = 多试几次，绝不会把高度钉死）。
         */
        boolean retryDue(EntityMaid maid) {
            if (!this.blocked) {
                return false;
            }
            // 【为什么用 gameTime 差值而不是自己 +1】不知道调用方每 tick 调一次还是每 2 tick 调一次
            //  （空袭那边的同类表就是 2 tick 一次），自己数就会有 2 倍误差——直接读游戏时间，
            //  常量 CLIMB_RETRY_TICKS = 100 就真的是"5 秒"。
            long now;
            try {
                now = maid.level().getGameTime();
            } catch (Throwable ignored) {
                now = this.blockedAt;
            }
            this.sinceBlocked = (int) Math.max(0L, now - this.blockedAt);
            long wait = (long) CLIMB_RETRY_TICKS << Math.min(this.retries, 8);
            if (wait > CLIMB_RETRY_MAX_TICKS) {
                wait = CLIMB_RETRY_MAX_TICKS;
            }
            return this.sinceBlocked >= wait && roomNow(maid);
        }

        /** 她脚底往上 1~3 格是不是空气（纯读，只服务重试判据） */
        private boolean roomNow(EntityMaid maid) {
            try {
                net.minecraft.world.level.Level level = maid.level();
                net.minecraft.core.BlockPos base = maid.blockPosition();
                for (int dy = 1; dy <= 3; dy++) {
                    if (!level.getBlockState(base.offset(0, dy, 0)).isAir()) {
                        return false;
                    }
                }
                return true;
            } catch (Throwable ignored) {
                return true;
            }
        }

        /** 【实测六百八十四】诊断用：头顶那几格是什么方块（分清"真被顶住"和"驱动没生效"） */
        String headBlocks(EntityMaid maid) {
            try {
                net.minecraft.world.level.Level level = maid.level();
                net.minecraft.core.BlockPos base = maid.blockPosition();
                StringBuilder sb = new StringBuilder();
                for (int dy = 1; dy <= 3; dy++) {
                    if (dy > 1) {
                        sb.append(" / ");
                    }
                    net.minecraft.world.level.block.state.BlockState st = level.getBlockState(base.offset(0, dy, 0));
                    sb.append("+").append(dy).append("=").append(st.isAir() ? "空气" : blockName(st));
                }
                return sb.toString();
            } catch (Throwable ignored) {
                return "（读不到）";
            }
        }

        private static String blockName(net.minecraft.world.level.block.state.BlockState st) {
            try {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                return rl == null ? String.valueOf(st.getBlock()) : rl.toString();
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }

    /** 收工：下扫帚 + 把当初那件扫帚**精确**还回她背包（背包塞不下就落脚下，走 MaidGiveBack） */
    public static void dismount(EntityMaid maid) {
        dismount(maid, "收工");
    }

    /**
     * 收工（{@code why} 只进日志）：下扫帚 + 把当初那件扫帚**精确**还回她背包。
     *
     * <p>【实测六百七十二：为什么要带理由】上扫帚每次都留痕，下扫帚却只在"要还扫帚物品"那一支
     * 写日志——世界扫帚那一支下鞍后直接 {@code return}，一个字节都不打。于是实测里"她什么时候、
     * 为什么下的扫帚"完全查不到，而排查"上一秒骑上、下一秒又下"那个高频循环恰恰只要这一个答案。
     * 现在四个调用点（总开关关 / 手上没扫帚 / 缺件 / 行为结束换任务）各自报出理由，5 秒一条上限。
     */
    public static void dismount(EntityMaid maid, String why) {
        if (maid == null) {
            return;
        }
        clearClimb(maid);
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        if (broom == null) {
            return;
        }
        ItemStack debt = DEBT.remove(broom.getUUID());
        try {
            maid.stopRiding();
        } catch (Throwable ignored) {
        }
        noteDismount(maid, why, debt != null);
        if (debt == null) {
            // 不是我们放出去的扫帚（玩家自己放的 / 别的模组）：一个字都不动，留给玩家
            return;
        }
        try {
            if (broom.isAlive()) {
                // kill() 只移除实体、**不**按原版掉落（原版掉落走 killEntity），物品由我们精确归还
                broom.kill();
            }
        } catch (Throwable ignored) {
        }
        forget(broom.getUUID());
        giveBack(maid, debt);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 收工下扫帚，扫帚已放回背包");
    }

    /** 下扫帚留痕（{@code hadDebt} = 这把是不是我们放出去的）：同一只女仆 5 秒一条上限 */
    private static void noteDismount(EntityMaid maid, String why, boolean hadDebt) {
        try {
            long now = System.currentTimeMillis();
            Long last = DISMOUNT_LOGGED.get(maid.getUUID());
            if (last != null && now - last < MOUNT_LOG_GAP_MS) {
                return;
            }
            DISMOUNT_LOGGED.put(maid.getUUID(), Long.valueOf(now));
            com.maidsmart.tool.StateTables.cap("扫帚.DISMOUNT_LOGGED", DISMOUNT_LOGGED);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 下扫帚（理由=" + why + "，这把是"
                    + (hadDebt ? "本模组放的，扫帚收进背包" : "世界里那把，原地留着") + "）");
        } catch (Throwable ignored) {
        }
    }

    private static final Map<UUID, Long> DISMOUNT_LOGGED = new HashMap<>();

    /* ==================== 意图：写 / 读 ==================== */

    public static void setThrust(EntityBroom broom, Vec3 vel, float yaw) {
        if (broom == null || vel == null) {
            return;
        }
        THRUST.put(broom.getUUID(), vel);
        YAW.put(broom.getUUID(), yaw);
    }

    /** mixin 取走本 tick 的推进矢量（取走即清：没有新意图的 tick 就悬停原地，绝不自由落体） */
    public static Vec3 takeThrust(EntityBroom broom) {
        if (broom == null) {
            return null;
        }
        return THRUST.remove(broom.getUUID());
    }

    /**
     * 丢掉这把扫帚**这一 tick 的推进意图**（传送落地后必调，v1.3.6 实测六百六十一）。
     *
     * <p>不碰 {@link #DEBT}：那把扫帚还「欠着」她当初那一件物品，收工时照还不误——这里若图省事
     * 走 {@link #forget}，「传送回来的下一秒收工」就会把她的扫帚吞掉（forget 连 DEBT 一起清）。
     */
    public static void clearIntent(EntityBroom broom) {
        if (broom == null) {
            return;
        }
        THRUST.remove(broom.getUUID());
        YAW.remove(broom.getUUID());
    }

    /** mixin 取朝向（保留最后写入的那个，悬停时朝向不抖） */
    public static float yaw(EntityBroom broom) {
        if (broom == null) {
            return 0.0f;
        }
        Float f = YAW.get(broom.getUUID());
        return f == null ? broom.getYRot() : f;
    }

    /**
     * 覆盖本 tick 的朝向——战斗时用"看着目标"而不是"朝着速度方向"：她一边绕圈一边射击，
     * 脸得对着目标（弓弦/枪口朝哪是表现，弹道一直按坐标算，但观感全靠这一条）。
     * 必须**在 {@link #steerTo} 之后**调用，否则会被 steerTo 写的速度方向盖掉。
     */
    public static void faceYaw(EntityBroom broom, float yaw) {
        if (broom == null) {
            return;
        }
        YAW.put(broom.getUUID(), yaw);
    }

    /* ==================== 去哪：战斗盘旋 / 平时跟随 ==================== */

    /**
     * 战斗时的目标点：保持在目标的水平外圈、缓慢绕着她转、高度压在她上方一点。
     *
     * 【为什么要绕而不是直线怼过去】远程女仆必须**持续把距离拉开**才有输出窗口
     * （贴脸会被近战反打），所以这里把"目标点"取成目标周围的**圆周上一点**，
     * 方位角每 tick 转 {@link #ORBIT_SPEED} 换算出来的那一步——表现就是"绕着她转圈打"。
     *
     * 【高度 = 这一场遭遇爬上来的那个高度（v1.3.3）】由 {@link #combatClimbTarget} 在爬升
     * 收尾时写进 {@link #COMBAT_ALT}（想抬配置那个数、顶头按实际、下限 {@link #hoverCfg}，
     * 而且**本场只升不降**，见 combatClimbTarget 里 实测六百八十六 那一段）。
     * 没有记录（理论上只在爬升还没做完时）才退回配置的悬停高度——**绝不出现"爬到 8 格
     * 又被另一套高度拽回来"**（那正是玩家反馈的"升上去又慢慢掉下来"）。
     *
     * <p>【实测六百八十四 补充】"顶头按实际"是**暂时的**：被顶住那一次按实际高度飞，
     * 但只要她头顶一开阔（她自己飞到高处 / 房间变高），{@link #combatClimbTarget} 会重试
     * 爬升并把这个数改回 {@code climb}。所以这一格不会像旧版那样把一整场遭遇钉在地板上。
     *
     * <p>【实测六百九十三：半径与旋向都改成随机】玩家原话：「可以把环绕型攻击方式更改一下，
     * 这样环绕的话会增加被击中的概率……敌人如果攻击的是第一只的话，会攻击到后面的，锁敌之后
     * 攻击敌人的飞行路径改成随机吧，然后设一个锁敌之后离敌的最远距离。」
     * 旧版这一格是 {@code Math.max(1.0, rangeCfg())}：**所有**女仆永远同一个半径（8 格），
     * 切向又是同一个固定方向（{@link #ORBIT_SPEED} / 半径，恒 +）——691 的相位错开只让她们
     * **起点**不同，跑起来仍然在同一个圆上同向转，敌人一条射线就能串到对面那只。
     * 现在半径交给 {@link CombatOrbit#radius}（每只女仆各自一个、每 4 秒缓动的比例，区间
     * {@code [基础距离 × 0.75, orbitMax]}）、旋向交给 {@link CombatOrbit#direction}（UUID 派生，
     * 一半逆时针一半顺时针）——两只女仆是在圆上**对穿**，不再首尾相接。
     * {@code orbitMax}（配置 {@code combat.broom.orbitMax}）就是玩家要的那条「离敌最远距离」，
     * 它同时是随机区间的顶点，所以"随机"不会变成"越飞越远"。
     *
     * @return 期望位置（已过 {@link MaidBroomKit#clampToHome} 夹取，见 {@link #steerTo}）
     */
    public static Vec3 combatPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        // 【实测六百九十三】随机环绕的区间：顶点 = 配置的「离敌最远距离」（硬上界），
        // 近端 = 基础盘旋距离的 75%（但绝不越过顶点——玩家把上限调得比基础距离还小时，
        // 就该听那个更小的数，所以她实际是"贴着上限飞"）。
        double base = Math.max(1.0, rangeCfg());
        double hi = Math.max(1.0, orbitMaxCfg());
        double lo = Math.min(hi, Math.max(1.0, base * 0.75));
        // 【实测七百零一】近端再兜一条**硬下限**：无论如何不许贴到比这个距离更近。
        //  玩家原话：「应该要保证至少与怪物拉开多少距离」。旧版的近端 = base × 0.75
        //  （默认 8 × 0.75 = 6 格），而 orbitMax 被调小时近端还会跟着塌下去——玩家把
        //  「离敌最远距离」调到 4，她就可能绕到 3 格，正好进近战怪的攻击范围。
        //  现在近端取 max(区间近端, 本值)：随机只发生在这条线之外。
        double standoff = minStandoffCfg();
        lo = Math.max(lo, standoff);
        // 区间退化（硬下限已越过顶点）时以顶点为准并夹住——绝不能出现 lo > hi 的倒挂
        // （那会让 CombatOrbit.radius 的插值把半径算到区间外，正是"随机变成乱飞"的来源）。
        if (lo > hi) {
            lo = hi;
        }
        double r = Math.max(0.5, CombatOrbit.radius(id, lo, hi));
        double dir = CombatOrbit.direction(id);
        // 【实测七百〇五】旋向不再是"一辈子恒定"：每 8 秒对半概率决定"继续原方向 / 翻过来"
        //  （见 CombatOrbit.flipSign）。乘在基准 dir 上，所以一半女仆以逆时针起手、一半以顺时针，
        //  但每个都会随机掉头——玩家原话「不要一直顺时针或者逆时针……差不多 8 秒钟一个周期」。
        //  掉头只改角速度符号、位置连续，不需要插值（见 CombatOrbit.DIR_FLIP_TICKS 的注释）。
        double dflip = CombatOrbit.flipSign(id);
        dir *= dflip;
        if (CombatOrbit.flipped(id)) {
            // 掉头那一刻记一行（每 8 秒判定一次、真翻才写，不刷屏）——方便实测核对"是不是真在掉头"。
            // 直写日志（不走 mountLog）：它和「随机环绕」同属"这一轮的关键事件"，不该被 5 秒节流吞掉。
            com.maidsmart.tool.PromaidLog.log("扫帚模式",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 反向环绕：旋向翻转为 "
                            + (dir > 0 ? "逆时针" : "顺时针")
                            + "（每 " + (CombatOrbit.flipTicks() / 20) + " 秒对半概率决定是否掉头）");
        }
        // 【实测七百零一】绕圈的**快慢**也在飘（见 CombatOrbit.speedScale）：半径决定圆多大、
        // 速度决定她转多快，两者节拍错开（4 秒 / 3 秒），合成出来的轨迹才不可预测。
        double spd = CombatOrbit.speedScale(id);
        // 【实测七百〇三】再叠一层**接敌机动**（见 CombatManeuver）：六百九十三/七百〇一 只把
        //  "同一张圆"拆开了（半径/旋向/快慢各自随机），但"打法"仍然只有绕圈一种——多只女仆观感
        //  上还是"一群人在转"。机动层给这一段加**波形调制**：半径倍率（蛇形 / 脱离再进）、
        //  角速度倍率（脱离再进 / 高悠悠）、高度增量（高悠悠，只加不减）、旋向翻转（8 字横切）。
        boolean maneuverOn = maneuverEnabled();
        if (maneuverOn) {
            CombatManeuver.begin(id);
            CombatManeuver.tick(id);
        } else {
            CombatManeuver.forget(id);
        }
        if (maneuverOn) {
            r = Math.max(lo, Math.min(hi, r * CombatManeuver.radiusScale(id)));
        }
        if (CombatOrbit.entering(id)) {
            mountLog(maid, "随机环绕：半径 " + fmt(r) + " 格（区间 " + fmt(lo) + "~" + fmt(hi)
                    + "，上界=combat.broom.orbitMax " + fmt(orbitMaxCfg()) + "，下限=combat.broom.minStandoff "
                    + fmt(standoff) + "），旋向 " + (dir > 0 ? "逆时针" : "顺时针")
                    + "，速度 " + fmt(spd) + "×（区间 " + fmt(CombatOrbit.speedLo()) + "~"
                    + fmt(CombatOrbit.speedHi()) + "×，每 3 秒重掷）");
        }
        // 【实测七百〇三】开场只写一行「接敌机动」（每场遭遇一只女仆一行，不刷屏）
        // 【实测七百〇四：**不能走 mountLog**】它和上面那行「随机环绕」在同一 tick 起手，
        //  而 mountLog 是"同一只女仆 5 秒最多一条"的节流——先进去的「随机环绕」占掉名额，
        //  这一行被静默丢掉。玩家反馈「打了那么多场都一直在用环绕」，一半原因就是它压根没打出来
        //  （另一半是状态没随遭遇重置，见 clearClimb）。这里改成直写日志：它本来就是每场遭遇一行，
        //  频次与「爬升到位」同档，不需要那 5 秒闸。
        if (maneuverOn && CombatManeuver.entering(id)) {
            CombatManeuver.Kind mk = CombatManeuver.kind(id);
            int seg = CombatManeuver.segment(id);
            com.maidsmart.tool.PromaidLog.log("扫帚模式",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 接敌机动："
                            + (mk == null ? "环绕" : mk.cn)
                            + (seg == 0
                                    ? "（本场遭遇开场；每 " + (CombatManeuver.segmentTicks() / 20)
                                            + " 秒换一种，高悠悠幅度 combat.maneuver.yoyoAmp "
                                            + fmt(yoyoAmpCfg()) + " 格，只加不减）"
                                    : "（本场遭遇第 " + (seg + 1) + " 段·换打法；每 "
                                            + (CombatManeuver.segmentTicks() / 20) + " 秒换一种）"));
        }
        // 角速度由固定线速度换算（见 ORBIT_SPEED 的注释）：任何半径下她都能跟上这个点
        // 【实测六百九十一】起点不是 0 而是"她自己那个相位"（见 phaseOf）：旧版所有女仆都从 0 起，
        // 绕着同一个敌人、同一个半径、同一个角速度 → 目标点逐 tick 完全重合，正是玩家说的"叠罗汉"。
        // 【实测六百九十三】步长再乘上她自己那个旋向 dir：一半女仆顺时针、一半逆时针。
        // 【实测七百零一】再乘上速度倍率 spd：绕圈速度本身也随时间游走。
        // 【实测七百〇三】再乘上机动给的两个倍率：角速度倍率（脱离再进 / 高悠悠）× 旋向翻转（8 字横切）。
        double mAngle = maneuverOn ? CombatManeuver.angleScale(id) : 1.0;
        double mRev = maneuverOn ? CombatManeuver.reversal(id) : 1.0;
        double ang = ORBIT.getOrDefault(id, phaseOf(maid)) + dir * mRev * ORBIT_SPEED * spd * mAngle / r;
        ORBIT.put(id, ang);
        double alt = COMBAT_ALT.getOrDefault(id, hoverCfg());
        // 【实测七百〇三】高悠悠的高度增量：**只加**在玩家设的那条基准高度上（0~amp 的慢波），
        //  所以"基础比敌人高多少格"在任何机动下都成立（绝不会被压到基准以下）。
        if (maneuverOn) {
            alt += CombatManeuver.heightAdd(id, yoyoAmpCfg());
        }
        return new Vec3(target.getX() + Math.cos(ang) * r,
                target.getY() + alt,
                target.getZ() + Math.sin(ang) * r);
    }

    /**
     * 平时（没有敌人）的目标点：**悬停在主人身边**——保持主人到她当前所在的这个方位、
     * 水平 {@link #FOLLOW_DIST} 格、高 {@link #FOLLOW_HOVER} 格。
     * 用"她当前方位"而不是固定方位，是为了她不会为了换边而横穿主人的脸。
     */
    public static Vec3 followPoint(EntityMaid maid, LivingEntity owner) {
        double dx = maid.getX() - owner.getX();
        double dz = maid.getZ() - owner.getZ();
        double ang = (Math.abs(dx) + Math.abs(dz) < 0.05) ? 0.0 : Math.atan2(dz, dx);
        double x = owner.getX() + Math.cos(ang) * FOLLOW_DIST;
        double z = owner.getZ() + Math.sin(ang) * FOLLOW_DIST;
        // 【实测六百九十一】高度过 safeY：主人头顶有天花板时，不再把"主人上方 2 格"那个
        // 在方块里的点硬塞给她（她会一路顶上去、人嵌进方块扣窒息伤害——与脱困那一档同一个病根）。
        double y = safeY(maid, x, z, owner.getY() + FOLLOW_HOVER, maid.getY());
        return new Vec3(x, y, z);
    }

    /**
     * 守家（工作范围）时的目标点：**沿着工作范围那个圈的边缘慢慢盘旋**（v1.3.6 实测六百六十一）。
     *
     * <p>【玩家原话】「如果我在扫把模式下开启鸿蒙，那个女仆正常就会在工作范围内对着工作范围
     * 那个圈进行盘旋，直到接敌。」旧版 home 对扫帚模式只剩「把目标点夹进圈里」这一条，
     * 于是开着 home 她也只是跟着主人悬停——看不出「守家」。现在平时（没有敌人）改成绕圈巡逻。
     *
     * <p>【高度：离地 patrolAlt 格，实测六百九十一改；**找不到地时只降不升**，实测六百九十二】
     * 旧版这一条**不碰高度**（y 取扫帚当前高度），理由是"工作范围是个水平圆、没有纵向语义"。
     * 但起飞相位只抬 {@link #RISE_BLOCKS} 格，于是她整场守家巡逻都贴着地面飞——玩家原话：
     * 「女仆很喜欢贴地飞行。这个观感太差了。」现在高度改成"**她脚下那块地之上
     * {@code combat.broom.homeAlt} 格**（默认 8）"，再由 {@link #safeY} 兜住天花板：{@code want}
     * 太高（头顶有顶）就取放得下的最高一格，一格都放不下就留在现在的高度。参照物仍是水平的那个圈，
     * 只把"多高"这一项补齐。
     *
     * <p>【实测六百九十二："躲建筑不许越躲越高"】玩家原话：「尽可能保持启动盘旋的高度（躲建筑只是
     * 暂时调整高度）。防止女仆在躲避其他建筑物的时候越飞越高。」所以 {@link #groundYOrNaN} 找不到
     * 地面时**不再加那个离地偏移**（旧版等于每拍 +8 格 = 无上限爬升），而是取
     * {@code min(她当前高度, 这场巡逻的高度记忆)}——只准降回那个高度，绝不再往上加。
     *
     * <p>【半径取「半径 − 余量」】圈内判定是「离圈心 ≤ 半径」，而她的座位在朝向后方半格
     * （见 {@link #hoverInPlace} 那段因果）——贴着边缘飞容易在边界上反复进出；退 1.5 格留余量，
     * 视觉上仍然是「贴着圈在转」。
     *
     * <p>【转速】与战斗盘旋共用 {@link #ORBIT_SPEED}（恒定**线**速度）：圈大圈小都是同样的
     * 格/秒，不会出现「圈一大就看起来钉在原地」。
     *
     * @return 期望位置；没有工作范围（圈心无效）时返回 null，调用方退回跟主人 / 原地悬停
     */
    public static Vec3 homeOrbitPoint(EntityMaid maid) {
        try {
            net.minecraft.core.BlockPos c = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (c == null) {
                return null;
            }
            UUID id = maid.getUUID();
            double r = Math.max(2.0, maid.getRestrictRadius() - 1.5);
            // 【实测六百九十一】起点换成"她自己那个相位"：旧版从 0 起，多只女仆绕同一个圈
            // 就永远停在同一格上（"叠罗汉"最稳定的一种）。
            double ang = HOME_ORBIT.getOrDefault(id, phaseOf(maid)) + ORBIT_SPEED / r;
            HOME_ORBIT.put(id, ang);
            Vec3 p = broomPos(maid);
            double x = c.getX() + 0.5 + Math.cos(ang) * r;
            double z = c.getZ() + 0.5 + Math.sin(ang) * r;
            // 【实测六百九十一】高度：她脚下那块地 + 配置的"离地格数"，再压到放得下的高度
            // （旧版这一格是 p.y = 扫帚当前高度 → 贴地飞）。
            // 【实测六百九十二：找不到地时**只降不升**】玩家原话「防止女仆在躲避其他建筑物的时候
            //  越飞越高……尽可能保持启动盘旋的高度（躲建筑只是暂时调整高度）」。
            //  旧版这一句是 `groundY(...) + homeAltCfg()`，而 groundY 找不到地面时返回的正是
            //  **她当前的高度** → 每拍在她现在的高度上再加 8 格 = 无上限爬升（地形在她脚下 24 格
            //  以外、或她悬在虚空上时必然发生）。现在：找得到地 → 地 + 离地格数（并记进
            //  HOME_ALT 当"这场巡逻的高度"）；找不到地 → 取 min(她当前高度, 那份记忆)，
            //  也就是**只准往那个高度降回去，绝不再往上加**。记忆为空（没巡逻过 / 换了圈心）
            //  就停在现在的高度。
            double cx = c.getX() + 0.5;
            double cz = c.getZ() + 0.5;
            double g = groundYOrNaN(maid, x, z, p.y);
            double wantY;
            if (Double.isNaN(g)) {
                PatrolAlt memo = HOME_ALT.get(id);
                wantY = (memo != null && memo.cx == cx && memo.cz == cz)
                        ? Math.min(p.y, memo.y)
                        : p.y;
            } else {
                wantY = g + homeAltCfg();
                HOME_ALT.put(id, new PatrolAlt(cx, cz, wantY));
            }
            return new Vec3(x, safeY(maid, x, z, wantY, p.y), z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 「别叠罗汉」：相位错开 + 邻近互斥（实测六百九十一） ==================== */

    /**
     * 每只女仆一个**稳定的盘旋相位**（弧度，0~2π）：从她的 UUID 派生，同一只女仆永远同一个值。
     *
     * <p>【为什么必须有它（玩家原话：「多个女仆乘着扫帚飞行的时候，很容易出现叠罗汉的情况」）】
     * 旧版两个盘旋相位表（{@link #ORBIT} 绕敌人 / {@link #HOME_ORBIT} 绕工作范围）**起点都是
     * 0.0**，而角速度又是同一个 {@link #ORBIT_SPEED} / 半径——于是同一时刻进场、半径相同的两只
     * 女仆目标点**逐 tick 完全重合**：绕着同一个圆心、永远是同一个点，看起来就是叠在一起飞。
     * 现在起点换成"她自己那个相位"，同一批女仆天然错开一整圈；再叠一层 {@link #separate} 兜住
     * "从两处飞过来撞到一起"的情况。
     *
     * <p>【为什么从 UUID 派生、而不是随机】随机会在每次重进世界时换相位（她会在你眼前跳一下），
     * 而 UUID 是稳定的：同一只女仆在同一个圆上的位置永远一样。
     */
    private static double phaseOf(EntityMaid maid) {
        try {
            // 【实测七百四十九·点3】算式搬到 MaidRideKit.ridePhase（坐骑那边用同一个）——
            // 这里只是把"她"包一层。
            return MaidRideKit.ridePhase(maid.getUUID());
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 两只"载着女仆的扫帚"之间的最小水平间距（格）：近到这个数以内就互相让位 */
    private static final double SEP_R = MaidRideKit.SEP_R;
    /**
     * 【实测七百零一】**接敌期间**的最小水平间距（格）——比平时的 {@link #SEP_R} 大一档。
     *
     * <p>玩家原话：「同时与其他的女仆拉开距离（这些机制仅在扫帚模式接敌以后才启用）」。
     * 平时跟主人/守家盘旋时，2 格是"别撞在一起"的观感下限；而**接敌**时两只女仆靠得近
     * 就是活靶子：敌人一箭穿过前面那只还会打到后面那只（正是 实测六百九十三 玩家说的"串"）。
     * 所以接敌档把间距放宽到 4 格——半径随机（6~10）+ 旋向对半（{@link CombatOrbit#direction}）
     * + 这条间距，三层一起保证"她与同伴不在同一条射线上"。
     *
     * <p>【只推水平、只在接敌】与 {@link #SEP_R} 同一套做法（见 {@link #separate}）。
     *
     * <p>【实测七百四十九·点3】取值改为直接引用 {@link MaidRideKit} 的公共常量——坐骑那边
     * 用的是**同一个** {@link MaidRideKit#SEP_R_COMBAT}，这才叫"统一的标准"。
     */
    private static final double SEP_R_COMBAT = MaidRideKit.SEP_R_COMBAT;
    /** 一次让位最多挪出去多少格（封顶：互斥只做修正，绝不把"去哪"整条盖掉） */
    private static final double SEP_MAX = MaidRideKit.SEP_MAX;
    /**
     * 【实测七百零一】接敌让位的**更大封顶**（格）：间距要求放宽到 {@link #SEP_R_COMBAT} 之后，
     * 原来 1.5 格的封顶会让"推出去"永远追不上要求（差值 4−d 经常大于 1.5），互斥等于白设。
     * 放大到 2.5 格——仍只是修正（真正"去哪"由 {@link #combatPoint} 决定），但够把两只分开。
     *
     * <p>【实测七百四十九·点3】同上，改为引用 {@link MaidRideKit#SEP_MAX_COMBAT}。
     */
    private static final double SEP_MAX_COMBAT = MaidRideKit.SEP_MAX_COMBAT;

    /**
     * **邻近互斥**：目标点附近有别的"载着女仆的扫帚"时，把目标点朝远离她的方向推出去一点。
     *
     * <p>【为什么写在推进这一层】"别叠罗汉"是横跨跟随 / 战斗盘旋 / 守家盘旋 / 原地悬停四种目标点
     * 的**同一条口径**，写在 {@link #steerTo} 里只此一处；相位错开（{@link #phaseOf}）只解决
     * "同一起点"，互斥负责"追同一个位置 / 半路撞上"。
     *
     * <p>【只避让"载着女仆的扫帚"】空着的扫帚是她的**目标**（她正要过去骑），玩家自己骑的那把
     * 也不是她要叠的东西；只把"别的女仆正骑着的扫帚"当障碍，正好就是玩家说的"叠罗汉"。
     *
     * <p>【只推水平】"叠罗汉"要的是水平方向错开；竖直那一份交给起飞/接敌两个爬升相位，
     * 互斥插一脚只会让爬升抖动。
     *
     * <p>【顺序】互斥作用在"原始目标点"上、**在 {@link MaidBroomKit#clampToHome} 之前**：
     * 夹取仍有最终话语权（不会被推出工作范围），互斥那点偏置在圈边被夹掉也只是"这一边推不动"。
     *
     * <p>【实测七百零一：接敌档间距放宽到 {@link #SEP_R_COMBAT}】玩家原话「同时与其他的女仆
     * 拉开距离（这些机制仅在扫帚模式接敌以后才启用）」——所以间距与封顶都由调用方按"这一拍
     * 是不是在接敌"传进来（口径只有一处：{@code combat} 为真时用那一对更大的数）。
     */
    private static Vec3 separate(EntityMaid maid, EntityBroom broom, Vec3 aim, boolean combat) {
        if (aim == null || broom == null) {
            return aim;
        }
        // 【实测七百四十九·点3：口径只有一处】算式与四个参数（SEP_R / SEP_R_COMBAT /
        // SEP_MAX / SEP_MAX_COMBAT）已整体搬到 {@link MaidRideKit#separateAim}——坐骑那边
        // （RideBindManager.drive）用的是**同一个方法、同一组数字**，这样"扫帚与坐骑是一个
        // 统一的标准"是结构上的事实，而不是两份抄来抄去的常量。本方法只剩"凑齐同伴位置"
        // 这一步（扫帚的同伴 = 别的**女仆正骑着的扫帚**）。
        final double sepR = combat ? SEP_R_COMBAT : SEP_R;
        try {
            if (!(broom.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return aim;
            }
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    aim.x - sepR, aim.y - sepR, aim.z - sepR,
                    aim.x + sepR, aim.y + sepR, aim.z + sepR);
            java.util.List<Vec3> peers = new java.util.ArrayList<>();
            for (EntityBroom other : level.getEntitiesOfClass(EntityBroom.class, box,
                    b -> b != broom && b.isAlive() && carriesMaid(b))) {
                peers.add(other.position());
            }
            return MaidRideKit.separateAim(aim, phaseOf(maid), peers, combat);
        } catch (Throwable ignored) {
            return aim;
        }
    }

    /** 这把扫帚上坐着女仆吗（{@link #separate} 只把"女仆骑着的扫帚"当障碍） */
    private static boolean carriesMaid(EntityBroom broom) {
        try {
            for (net.minecraft.world.entity.Entity p : broom.getPassengers()) {
                if (p instanceof EntityMaid) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ==================== 「别把目标点设在方块里」：高度兜底（实测六百九十一） ==================== */

    /**
     * 【实测六百九十一】把"想去的高度"压到**她真的放得下**的高度：顶头就低一点，绝不硬塞。
     *
     * <p>【为什么需要】玩家反馈「home 模式下女仆会进行飞行盘旋巡逻……但是女仆很喜欢贴地飞行。
     * 这个观感太差了」——旧版守家盘旋的高度是**扫帚当前高度**（{@code p.y}），而起飞相位
     * 只抬 {@link #RISE_BLOCKS} 格，于是她整场巡逻都贴着地面飞。现在改成"地面之上
     * {@code combat.broom.homeAlt} 格"（见 {@link #homeOrbitPoint}），但**不能直接给一个绝对
     * 高度**：头顶有天花板时那个点在方块里，她会一路顶上去——而她是被原版 {@code positionRider}
     * 直接摆到座位点的**乘客**，会嵌在方块里扣窒息伤害（与脱困那一档同一个病根）。
     * 所以这里从"想要的高度"往下找第一个她放得下的高度。
     *
     * <p>【判据】她连扫帚要占的 {@code y} 与 {@code y+1} 两格都是空气（她的碰撞箱宽 0.6 / 高 1.5，
     * 见 {@link #MAID_HALF_W} 那一组常量）。一格都放不下（她头顶就是一格厚的地板）→ 返回
     * {@code fromY}（她现在的高度 = 原地悬停），不硬抬。
     */
    private static double safeY(EntityMaid maid, double x, double z, double wantY, double fromY) {
        try {
            if (!(wantY > fromY)) {
                return wantY; // 往下走不存在"顶头"
            }
            net.minecraft.world.level.Level level = maid.level();
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int bottom = (int) Math.floor(fromY) + 1;
            for (int y = (int) Math.floor(wantY); y >= bottom; y--) {
                if (isAirAt(level, new net.minecraft.core.BlockPos(bx, y, bz))
                        && isAirAt(level, new net.minecraft.core.BlockPos(bx, y + 1, bz))) {
                    return y + 0.5;
                }
            }
        } catch (Throwable ignored) {
            // 读不到方块（异常）→ 按想要的高度走（与旧行为一致，不新增坑）
        }
        return wantY;
    }

    /**
     * 【实测六百九十一】她脚下（沿着 x/z 往下最多 {@link #GROUND_SCAN} 格）第一块"非空气"方块
     * **之上那一格**的高度 = "离地"的基准；**找不到就返回 {@code NaN}**。
     *
     * <p>【为什么不用原版高度图】{@code Level.getHeight} 在 1.20.1 与 1.21.1 的名字不一样
     * （SRG / Mojmap），而"从她当前位置往下找第一块地"既不需要多一个两树要映射的名字、
     * 又天然覆盖"她此刻在一个洞里 / 屋里 / 树冠上"这些情况。
     *
     * <p>【实测六百九十二：找不到地必须是"找不到"，不能是"她当前高度"】旧版返回 {@code fromY}
     * 兜底，而调用方（{@link #homeOrbitPoint}）紧接着就 {@code + homeAltCfg()} —— 于是"她比地形
     * 高出 24 格以上 / 悬在虚空上"这条路上，每 tick 的目标高度 = **她自己 + 8 格**：无上限爬升
     * （玩家原话「躲避其他建筑物的时候越飞越高」）。现在改成 {@code NaN} 明确表达"没找到"，
     * 由调用方决定怎么办（守家盘旋那一档：只降不升，见 {@link #HOME_ALT}）。
     */
    private static double groundYOrNaN(EntityMaid maid, double x, double z, double fromY) {
        try {
            net.minecraft.world.level.Level level = maid.level();
            net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(
                    (int) Math.floor(x), (int) Math.floor(fromY), (int) Math.floor(z));
            for (int i = 0; i <= GROUND_SCAN; i++) {
                if (!isAirAt(level, p)) {
                    return p.getY() + 1; // 这一格是地 → 上面那一格就是"地面"
                }
                p = p.below();
            }
        } catch (Throwable ignored) {
        }
        return Double.NaN;
    }

    /* ==================== 原地悬停 / 垂直爬升：一律用**扫帚自己的坐标** ==================== */

    /** 这把扫帚（载具）现在在哪；没骑着就退回她自己的位置（调用方只在骑着时用） */
    private static Vec3 broomPos(EntityMaid maid) {
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        return broom == null
                ? new Vec3(maid.getX(), maid.getY(), maid.getZ())
                : broom.position();
    }

    /**
     * 原地悬停：目标点 = **扫帚自己现在的位置**。
     *
     * <p>【为什么不能用她的坐标（v1.3.3 实测六百五十八，玩家原话："现在女仆在骑着扫帚的
     * 时候，如果主人就在旁边呢，它会在空中不停的旋转"）】她是**乘客**：原版
     * {@code EntityBroom.m_19956_}（positionRider）把她的座位摆在她朝向的**后方 0.5 格**
     * （再叠一个骑乘高度差）——也就是说"扫帚的位置"与"她的位置"永远差着一段固定偏移。
     * 旧版拿**她的**坐标当"原地不动"的目标点，而 {@link #steerTo} 里那句"到点了吗"比的是
     * **扫帚**到目标点的距离：恒差那 0.5 格，于是永远到不了点、每 tick 被给一个速度，
     * 方向又正好是背对朝向那一侧。与此同时 {@code faceYaw} 写进去的"朝向主人"是用
     * **她的**位置算的——她的位置随朝向动，朝向又随她的位置动。两处互相引用，就成了
     * 每 tick 翻 180° 的回路；客户端对 yRot 做插值，看起来就是"在空中不停地打转"。
     * 换成扫帚自己的坐标：这一支真的"到点 → 速度乘 0.75 收干"，静止悬停，回路断掉。
     */
    public static void hoverInPlace(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        steerTo(maid, broomPos(maid));
    }

    /** 实测六百九十八：待命落地的悬停高度——离地一格半（扫帚自己还有厚度，贴地会穿模） */
    private static final double PARK_ABOVE_GROUND = 1.6;
    /** 她高于目标这么多格才值得往下走（再低就就地悬停，免得贴着地面上下抖） */
    private static final double PARK_MIN_LIFT = 2.5;
    /** 待命落地日志限频（女仆 UUID → 上次毫秒），20 秒一条 */
    private static final java.util.Map<java.util.UUID, Long> PARK_LOGGED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PARK_LOG_MS = 20_000L;

    /**
     * 实测六百九十八【待命不许挂在天上】——玩家原话：「还会出现女仆骑上扫帚，结果在空中
     * 悬空的状态」。
     *
     * <p>扫帚模式"没目标、也没在跟主人这一趟"那一档（{@link MaidBroomBehavior} 的 ⑥.③）
     * 原先一律 {@link #hoverInPlace}：**目标点就是她现在的坐标**，速度收干 → 她挂在半空
     * 一动不动。她为什么会挂在半空？起飞相位本来就要"原地往上抬 1 格"（实测六百七十七 的
     * 玩家要求），打完一仗也停在高处，主人在别的维度/离得远时更没人管她——于是"骑上扫帚
     * 就浮在那儿"成了常态。
     *
     * <p>现在改成**降回地面待命**：顺着她脚下探地（{@link #groundYOrNaN}，最多 24 格），
     * 找到就降到"地面之上 {@link #PARK_ABOVE_GROUND} 格"；探不到地（悬在虚空 / 比地形高出
     * 24 格以上）就照旧原地悬停——**绝不往下扎**。已经贴地了也照旧悬停（不再上下抖）。
     * 开关 = 配置 {@code combat.broom.idleLand}（默认开）。
     */
    public static void parkIdle(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        if (!idleLandEnabled()) {
            hoverInPlace(maid); // 关掉开关 = 旧行为（原地悬停）
            return;
        }
        try {
            Vec3 p = broomPos(maid);
            double ground = groundYOrNaN(maid, p.x, p.z, p.y);
            if (Double.isNaN(ground)) {
                hoverInPlace(maid); // 脚下没地：原地悬停，绝不往下扎
                return;
            }
            // 【实测七百〇四：脚下那块地是岩浆/火就不落过去】玩家反馈「女仆有的时候还是会飞进
            //  岩浆里面（在空袭和扫帚里面都有这种问题）」。{@link #groundYOrNaN} 只问"这一格是不是
            //  空气"，而岩浆**不是空气**——于是"岩浆湖面"在她眼里与"地面"一模一样，待命降落会
            //  直直落到岩浆面上。这一档是她**主动下降**（唯一一条自己往低处走的路径），所以按
            //  {@link com.maidsmart.tool.DangerBlocks#cellDangerousBoxed} 判一下"落点是不是危险格"
            //  （含她的碰撞箱宽度），是就照旧原地悬停——宁可挂在半空，也不落进岩浆。
            int gy = (int) Math.floor(ground);
            if (com.maidsmart.tool.DangerBlocks.cellDangerousBoxed(maid.level(),
                    (int) Math.floor(p.x), gy, (int) Math.floor(p.z),
                    com.maidsmart.tool.DangerBlocks.boxRadius(maid.getBbWidth()))) {
                hoverInPlace(maid); // 落点是危险格（岩浆/火/岩浆块…）→ 不落，原地悬停
                return;
            }
            double target = ground + PARK_ABOVE_GROUND;
            if (p.y - target < PARK_MIN_LIFT) {
                hoverInPlace(maid); // 已经贴地：就地悬停
                return;
            }
            logPark(maid, p.y, target);
            steerVerticalTo(maid, target);
        } catch (Throwable ignored) {
            hoverInPlace(maid);
        }
    }

    private static void logPark(EntityMaid maid, double fromY, double toY) {
        try {
            long now = System.currentTimeMillis();
            Long last = PARK_LOGGED.get(maid.getUUID());
            if (last != null && now - last < PARK_LOG_MS) {
                return;
            }
            PARK_LOGGED.put(maid.getUUID(), now);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 待命 → 降回地面（y " + fmt(fromY) + " → " + fmt(toY) + "）");
        } catch (Throwable ignored) {
        }
    }

    private static boolean idleLandEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_IDLE_LAND.get();
        } catch (Throwable ignored) {
            return true; // 读不到配置（早期加载）→ 按默认开
        }
    }

    /**
     * 只爬高、水平不动：目标点 = **扫帚当前的 x/z** + 指定的 y。
     * <p>
     * 起飞相位（原地抬 1 格）与接敌爬升走这一支。旧版用她的 x/z，同样会被座位偏移拖成
     * 一条"边升边漂"的小斜线（见 {@link #hoverInPlace} 的说明）。
     */
    public static void steerVerticalTo(EntityMaid maid, double y) {
        if (maid == null) {
            return;
        }
        Vec3 p = broomPos(maid);
        steerTo(maid, new Vec3(p.x, y, p.z));
    }

    /**
     * 让**扫帚**朝着某个目标（纯表现：弹道一直按坐标算，不看朝向）。
     *
     * <p>与 {@link #faceYaw} 只差一处、但很关键：这里的偏航是**从扫帚自己的位置**算出来的，
     * 不是从她的位置算。她是乘客、座位在她朝向的后方 0.5 格；用她的位置算"朝向主人"会
     * 让"她动 → 朝向动 → 她再动"自引用成环（见 {@link #hoverInPlace} 里那段因果）。
     * 从扫帚出发就没有这一环。
     *
     * <p>调用约定同 {@link #faceYaw}：**必须在 {@link #steerTo} 之后调**，
     * 否则会被 steerTo 写进去的"速度方向"盖掉。
     */
    public static void faceYawTo(EntityBroom broom, net.minecraft.world.entity.Entity target) {
        if (broom == null || target == null) {
            return;
        }
        try {
            faceYaw(broom, (float) (-Math.atan2(target.getX() - broom.getX(),
                    target.getZ() - broom.getZ()) * DEG));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 找扫帚（扫帚模式里排在"跟随主人"之前的第一优先级） ==================== */

    /**
     * 她身上没有扫帚时：**去找一把**——玩家原话"扫帚模式应该优先找扫帚，而不是优先跟随主人"。
     *
     * <p>【"找"指什么（v1.3.4 实测六百五十九，玩家原话："让她去骑世界里已经放着的那把扫帚实体"）】
     * 按玩家点名的那一档来：<b>世界里已经放着的那把扫帚实体</b>（{@code EntityBroom}）——
     * 她会走过去骑上它，见 {@link #rideWorldBroom}。实在没有实体可骑，才退一步找
     * **地上掉的扫帚物品**（v1.3.3 的老规矩：收进背包之后由 {@link #ensureMounted} 放出来骑）。
     * 背包/精妙背包里躺着的那不叫"找"叫"取"——归 {@link #ensureMounted} 直接抽出来放骑。
     *
     * <p>【怎么找】近了直接上鞍 / 直接收进背包；远了就把原版寻路目标指向它、每 tick 重写一次
     * （与其它行为同款：真正执行的是 TLM 的 {@code MoveToTargetSink}）。超过
     * {@link #HUNT_GIVE_UP_MS} 还没到（卡墙、在水里、够不着）就放弃这一轮并进
     * {@link #HUNT_COOLDOWN} 冷却——免得她对着一个拿不到的扫帚走到天荒地老。
     *
     * @return true = 这一 tick 正在为"找扫帚"做事（调用方这 tick 先别管跟随/战斗）
     */
    public static boolean seekBroom(ServerLevel level, EntityMaid maid) {
        if (level == null || maid == null) {
            return false;
        }
        UUID id = maid.getUUID();
        if (MaidBroomKit.hasBroomItem(maid) || MaidBroomKit.ridingBroom(maid) != null) {
            HUNTING.remove(id); // 已经有了：交给 ensureMounted 去"取出来骑上"
            return false;
        }
        // ① 世界里放着的那把（首选）→ ② 地上掉的那件（次选）。每 tick 重选一次最近的，
        //    所以走着走着她旁边又出现一把更近的，她会自然改道（与旧版同一个口径）。
        EntityBroom world = nearestWorldBroom(level, maid);
        net.minecraft.world.entity.item.ItemEntity drop =
                world == null ? nearestDroppedBroom(level, maid) : null;
        if (world == null && drop == null) {
            HUNTING.remove(id);
            return false;
        }
        long now = System.currentTimeMillis();
        Long cool = HUNT_COOLDOWN.get(id);
        if (cool != null) {
            if (cool > now) {
                return false; // 冷却中（同一把够不着的扫帚，别每 tick 重试）
            }
            HUNT_COOLDOWN.remove(id);
        }
        net.minecraft.world.entity.Entity goal = world != null ? world : drop;
        double d = maid.distanceTo(goal);
        if (world != null) {
            if (d <= MOUNT_RANGE) {
                HUNTING.remove(id);
                return rideWorldBroom(maid, world);
            }
        } else if (d <= PICKUP_RANGE) {
            HUNTING.remove(id);
            return pickUpBroom(maid, drop);
        }
        Long since = HUNTING.get(id);
        if (since == null) {
            HUNTING.put(id, now);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 身上没有扫帚 → 发现" + (world != null
                            ? "世界里放着的那把扫帚（" + fmt(Math.sqrt(maid.distanceToSqr(world))) + " 格外），过去骑"
                            : "掉在地上的扫帚（" + fmt(Math.sqrt(maid.distanceToSqr(drop))) + " 格外），过去捡")
                    + "——这就是「扫帚优先」那一档");
        } else if (now - since > HUNT_GIVE_UP_MS) {
            HUNTING.remove(id);
            HUNT_COOLDOWN.put(id, now + HUNT_GIVE_UP_MS);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 那把扫帚走了 " + (HUNT_GIVE_UP_MS / 1000) + " 秒也没够到 → 先放下"
                    + "（缺件待命，过一会儿再试）");
            return false;
        }
        try {
            // 每 tick 重写寻路目标（与其它行为同款；真正的移动由 TLM 的导航执行）
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.setWalkAndLookTargetMemories(maid, goal.blockPosition(),
                    HUNT_SPEED, 1);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 她附近**世界里放着的**扫帚实体（{@code EntityBroom}，按距离取最近的一把；没有则 null）。
     *
     * <p>【为什么"空着的"才算】TLM 的扫帚最多两个乘客：玩家驾驶时玩家在第一乘客位，TLM 还会
     * 把主人的女仆拽上第二乘客位；别的女仆也可能是自己骑上去的。这两种都不该被抢——她是去找
     * "没人用的那把"。判据就用原版自己的 {@code getPassengers().isEmpty()}，不另立一套。
     */
    private static EntityBroom nearestWorldBroom(ServerLevel level, EntityMaid maid) {
        try {
            Vec3 p = maid.position();
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    p.x - HUNT_RADIUS, p.y - HUNT_RADIUS, p.z - HUNT_RADIUS,
                    p.x + HUNT_RADIUS, p.y + HUNT_RADIUS, p.z + HUNT_RADIUS);
            EntityBroom best = null;
            double bestD = Double.MAX_VALUE;
            for (EntityBroom b : level.getEntitiesOfClass(EntityBroom.class, box,
                    x -> x.isAlive() && x.getPassengers().isEmpty())) {
                double d = maid.distanceToSqr(b);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 骑上世界里放着的那把扫帚。
     * <p>
     * 【与 {@link #ensureMounted} 的区别】那把**不是我们放出去的**：所以不进 {@link #DEBT}
     * ——收工时只下鞍、扫帚原样留在世界里（见 {@link #dismount} 里 {@code debt == null} 那一支），
     * 也就不会把它收进背包、更不会删掉它。
     * <p>
     * 【骑上即起"起飞相位"】与"取出自己那把"同款：玩家要求"拿到扫帚就原地往上飞 1 格"
     * （见 {@link #takeoffTarget}）——不管扫帚是买的还是捡的，拿到手都该腾空。
     */
    private static boolean rideWorldBroom(EntityMaid maid, EntityBroom broom) {
        try {
            if (broom == null || maid == null || !broom.isAlive()) {
                return false;
            }
            if (!broom.getPassengers().isEmpty()) {
                return false; // 这一 tick 刚有人骑上去：让给它，下一 tick 再看别的/再看它
            }
            if (broom.level() != maid.level()) {
                return false;
            }
            // force = true：与 ensureMounted 同款（原版不带 force 的 startRiding 要求她此刻
            // 不是任何载具的乘客，坐椅子/坐别的载具时恒返回 false，那就永远上不去）
            if (!maid.startRiding(broom, true)) {
                return false;
            }
            ORBIT.remove(maid.getUUID());
            clearBroomless(maid); // 【实测六百七十二】骑上了 → 没扫帚的计时清零
            // 【实测六百七十二】起飞相位去抖 + 日志节流：这一句旧版是**无节流、无去抖**的，
            //  而它恰好是"反复上下扫帚"那个循环里被刷的那一行（实测 5~11 次/秒），
            //  每刷一次就重开一次起飞相位 = 每轮 +1 格。
            boolean lifted = startTakeoff(maid);
            mountLog(maid, "骑上了世界里放着的那把扫帚（不是我们放的：收工只下鞍、扫帚留在原地）"
                    + " → " + (lifted ? "原地抬起 " + RISE_BLOCKS + " 格"
                                      : "5 秒内刚起过一次，不重复抬高度"));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她附近掉在地上的扫帚物品（按距离取最近的一把；没有则 null） */
    private static net.minecraft.world.entity.item.ItemEntity nearestDroppedBroom(ServerLevel level,
                                                                                 EntityMaid maid) {
        try {
            Vec3 p = maid.position();
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    p.x - HUNT_RADIUS, p.y - HUNT_RADIUS, p.z - HUNT_RADIUS,
                    p.x + HUNT_RADIUS, p.y + HUNT_RADIUS, p.z + HUNT_RADIUS);
            net.minecraft.world.entity.item.ItemEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (net.minecraft.world.entity.item.ItemEntity e
                    : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box,
                            x -> MaidBroomKit.isBroomItem(x.getItem()))) {
                double d = maid.distanceToSqr(e);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把地上那把扫帚收进她的背包。
     * <p>
     * 【塞不下就不动】返回 false 时物品**原样留在地上**——绝不能"捡不起来还把实体删了"，
     * 那是把玩家的东西弄没了。收成之后 {@code kill()} 掉实体（不走原版掉落：物品已经进了
     * 背包，再走掉落会掉两份）。
     */
    private static boolean pickUpBroom(EntityMaid maid, net.minecraft.world.entity.item.ItemEntity drop) {
        try {
            ItemStack stack = drop.getItem();
            if (stack.isEmpty()) {
                return false;
            }
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            ItemStack rest = stack.copy();
            for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
                rest = inv.insertItem(i, rest, false);
            }
            if (!rest.isEmpty()) {
                return false; // 背包满：留在地上
            }
            drop.kill();
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 把地上的扫帚捡进背包了（" + stack.getCount() + "x "
                    + stack.getHoverName().getString() + "）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把"想去哪"变成这一 tick 的速度矢量写进意图表。
     *
     * <p>目标点先过 {@link MaidBroomKit#clampToHome}：这是"移动逻辑与 home 模式"那条账的落点
     * ——她骑上扫帚后 TLM 自身的范围约束整条失效，圈内这件事只剩这里把关。
     *
     * <p>── 公式（与 {@code PlayerBroomControl.travel} 同一套包络与阻尼）──
     * <pre>
     *   目标速度 = 单位方向 × (水平 MAX_H_SPEED / 竖直 MAX_V_SPEED) × 到点减速带
     *   本 tick 速度 = 上一 tick 速度.lerp(目标速度, 0.25)     ← 原版有输入那一支的阻尼
     *   到点（&lt; ARRIVE）= 上一 tick 速度 × 0.75               ← 原版无输入那一支 = 悬停
     * </pre>
     * "速度 → 位移"是纯一阶（没有累加、没有加速度），所以数学上不可能振荡；而阻尼/衰减
     * 系数与原版逐字相同，观感也就是原版那把扫帚。**速度上限只到原版全速，一分不加**。
     */
    public static void steerTo(EntityMaid maid, Vec3 desired) {
        steerTo(maid, desired, false);
    }

    /**
     * 【实测七百零一】带 {@code combat} 标记的推进入口：接敌那一档传 {@code true}，
     * 让 {@link #separate} 用更大的同伴间距（{@link #SEP_R_COMBAT}）。
     *
     * <p>玩家原话：「同时与其他的女仆拉开距离（这些机制仅在扫帚模式接敌以后才启用）」——
     * 所以"要不要拉开"由调用方（{@link MaidBroomBehavior} 的接敌分支）传进来，
     * 平时跟随/守家仍走原来那 2 格（观感上的"别撞在一起"就够）。
     */
    public static void steerTo(EntityMaid maid, Vec3 desired, boolean combat) {
        if (maid == null || desired == null) {
            return;
        }
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        if (broom == null) {
            // 【绝不能静默】旧版这里直接 return：只要"没骑上"，她整段飞行都是"什么都不发生"，
            // 而缺件气泡那边看到"扫帚物品还在"也不会报——玩家只能看到"女仆站在原地不动"。
            // 实测六百五十七就吃过这个亏（日志里只有"顶头"、没有原因）。现在留痕（5 秒一条上限）。
            noteNoDrive(maid);
            return;
        }
        // 【实测六百九十一】先把她推离"别的女仆正骑着的扫帚"（别叠罗汉），再夹进工作范围
        // （夹取有最终话语权，见 separate 的注释），最后才是卡墙脱困。
        // 【实测七百零一】接敌档（combat=true）用更大的间距，见 separate 的注释。
        Vec3 aim = MaidBroomKit.clampToHome(maid, separate(maid, broom, desired, combat));
        if (aim == null) {
            return;
        }
        aim = unstick(maid, broom, aim); // v1.3.0(beta) 实测六百六十四：卡墙脱困
        // 【v1.3.0(beta) 实测六百九十六：飞行危险环境避让】"想去哪"里若那一段会穿进
        // 危险方块（岩浆/火/岩浆块…），侧向绕开或抬升爬过去——飞行一侧本来完全没有这道
        // （地面的危险方块避让全挂在寻路上，空中那份被显式跳过）。放在最后：脱困（顶出
        // 方块）优先于绕行；夹取仍在本方法下游（clampToHome 已经先跑过一遍，这里的修正
        // 是"别进危险格"的局部小位移，不改圈心规矩）。
        aim = MaidFlightHazardGuard.detour(maid, broom.position(), aim);
        Vec3 cur = broom.getDeltaMovement();
        double dx = aim.x - broom.getX();
        double dy = aim.y - broom.getY();
        double dz = aim.z - broom.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (dist < ARRIVE) {
            // 到点：走原版"没有输入"那一支（速度乘 0.75 收干）→ 原地悬停，绝不自由落体
            setThrust(broom, cur.scale(IDLE_DECAY), yaw(broom));
            return;
        }
        // 到点减速带：远处全速、近了按距离线性收干（分子分母同量纲，dist→0 时速度→0）
        double ramp = Math.min(1.0, dist / ARRIVE_RAMP);
        double inv = 1.0 / dist;
        Vec3 tgt = new Vec3(dx * inv * MAX_H_SPEED * ramp,
                dy * inv * MAX_V_SPEED * ramp,
                dz * inv * MAX_H_SPEED * ramp);
        Vec3 nv = cur.lerp(tgt, BLEND);
        // 朝向 = 速度方向（纯垂直位移时不改朝向）
        float yaw = horiz > 0.15 ? (float) (-Math.atan2(dx, dz) * DEG) : yaw(broom);
        setThrust(broom, nv, yaw);
    }

    /* ==================== 卡墙脱困（v1.3.0(beta) 实测六百六十四） ==================== */

    /** 女仆 UUID → 卡墙检测状态（只读她自己，逐 tick 更新，异常一律吞掉） */
    private static final Map<UUID, Stuck> STUCK = new HashMap<>();
    /** 连续这么多 tick 位移 < {@link #STILL_EPS} 且还没到点 → 判"被方块顶住了"（12 tick = 0.6 秒） */
    private static final int STUCK_TICKS = 12;
    /** "没动"的位移阈值（格）：低于它就算这一 tick 没走成 */
    private static final double STILL_EPS = 0.06;
    /** 一次脱困最多走这么多 tick（2 秒）；到点或超时都回到原链路 */
    private static final int ESCAPE_TICKS = 40;
    /** 脱困取点的水平扫描半径（格）——玩家原话是"最近的空气方块"，所以扫小一点、取最近的 */
    private static final int ESCAPE_R = 3;
    /**
     * 【实测六百九十一】她（作为乘客）相对扫帚的座位偏移与体型——**原版字节码实证**：
     * <pre>
     *   水平：{@code -0.5} 格，沿扫帚朝向旋转 →【朝向的后方半格】
     *         （1.20.1 {@code EntityBroom.m_19956_} / 1.21.1 {@code EntityBroom.getPassengerAttachmentPoint}
     *          两份字节码都是这个数：女仆乘客 -0.5，其它乘客 0，两人时改 ±0.35）
     *   竖直：1.21.1 是 {@code -0.3125}；1.20.1 取 {@code m_6048_() + 乘客.m_6049_()}，
     *         而 {@code EntityBroom.m_6048_()} 反汇编就是 {@code return 0.0}、女仆也没覆写 m_6049_
     *         → 0.0。**两个版本都落在同一格**（差 0.31 格不会跨格），所以这里取 -0.3125 这一侧。
     *   体型：女仆的 {@code EntityType.sized(0.6f, 1.5f)}（宽 0.6 / 高 1.5）
     * </pre>
     * 这三个数只服务于一条判据：**"她身体压着的那几格是不是空气"**（见 {@link #bodyFits}）。
     */
    private static final double SEAT_BACK = 0.5;
    private static final double SEAT_DOWN = 0.3125;
    private static final double MAID_HALF_W = 0.3;
    private static final double MAID_HEIGHT = 1.5;
    /**
     * 【实测六百九十一】"刚去过"的记忆时长（tick）= 5 秒——玩家点名的时间：
     * 「刚刚寻路并且到达过的空气方块在5秒之内就不应该再次登进去了」。
     */
    private static final long ESCAPE_MEMORY_TICKS = 100L;
    /** 【实测六百九十一】"往下找地面"最多扫这么多格（找不到就当她悬在虚空上，原地不动） */
    private static final int GROUND_SCAN = 24;
    /**
     * 【实测六百九十二】脱困取格时允许**高出目标点多远**（格）——躲建筑只是暂时调整高度。
     *
     * <p>玩家原话：「女仆在处于 home 模式的状态下最好还是要尝试让自己的高度尽可能保持启动盘旋的
     * 高度（躲建筑只是暂时调整高度）。防止女仆在躲避其他建筑物的时候越飞越高。」
     *
     * <p>【为什么必须有这个上限】脱困的取格判据里"离目标更近"用的是 3D 距离，而 {@code goal} 的
     * Y 取的是**她当前那一格**（见 {@link #nearestEscapeCell}）——所以"往上挪"与"往旁边挪"在
     * 距离上等价，楼里横向全被堵死时就只剩"往上"可选。一次脱困最多 +2 格，但**每次卡住都会再来
     * 一次**：实测日志里她在地形之上被一路从 y=15 垫到 40 多（01:32:26 → 01:32:58 那一串
     * 「被方块顶住 → 先飘到最近的空气格」，Y 一格一格往上走）。现在候选格必须落在
     * {@code 目标高度 + 本上限} 以下：翻一道坎够用，但**永远爬不过目标高度**——
     * 一旦越过去，"越飞越高"就会把攻击与链路一起飞没（玩家原话：飞太高之后"攻击、链路等方面
     * 就都不会触发了"）。
     */
    private static final int ESCAPE_UP_MAX = 3;
    /** 同一只女仆的「卡墙」日志最短间隔（毫秒）= 5 秒，防刷屏 */
    private static final long UNSTICK_LOG_GAP_MS = 5000L;

    private static final class Stuck {
        double px = Double.NaN;
        double py;
        double pz;
        /** 连续"没动"的 tick 数 */
        int still;
        /** 剩余脱困 tick（>0 = 正在脱困） */
        int escape;
        double ex;
        double ey;
        double ez;
        long lastLog;
        /**
         * 【实测六百九十一】最近"寻路进去过"的空气格 → 到期 gameTime（tick）。
         * <p>玩家原话：「刚刚寻路并且到达过的空气方块在5秒之内就不应该再次登进去了。防止女仆在
         * 两个空气格子之间来回反复横跳。」过期时刻用 {@link #ESCAPE_MEMORY_TICKS} 现算，
         * 不另起一套计时；只在这一只女仆身上，她下扫帚/换任务时随 {@link #STUCK} 一起清。
         */
        private final java.util.Map<Long, Long> visited = new java.util.HashMap<>();

        /** 这一格 5 秒内去过没有（只查表，不做清理——清理在 {@link #remember} 里顺手做） */
        boolean recentlyVisited(net.minecraft.core.BlockPos p, long now) {
            Long until = this.visited.get(Long.valueOf(keyOf(p)));
            return until != null && until.longValue() > now;
        }

        /**
         * 记下"这一格刚去过"。
         * <p>到达（{@code toEsc <= 1.0}）与超时（够不着）**都记**：前者是玩家点名的"到达过的
         * 空气方块 5 秒内不再登入"，后者是"这一格她压根到不了"——两种都不该在下一拍原样再撞一次
         * （否则就是玩家说的"卡死 / 反复横跳"）。
         */
        void remember(net.minecraft.core.BlockPos p, long now) {
            if (this.visited.size() > 32) {
                long t = now;
                this.visited.values().removeIf(v -> v.longValue() <= t);
            }
            if (this.visited.size() > 64) {
                this.visited.clear(); // 极端情况下宁可全忘（最坏 = 旧行为），也不让这张表长起来
            }
            this.visited.put(Long.valueOf(keyOf(p)), Long.valueOf(now + ESCAPE_MEMORY_TICKS));
        }
    }

    /** 方块坐标 → long 键（脱困"刚去过"名单用；不用 {@code BlockPos.asLong} 免得再多一个要映射的名字） */
    private static long keyOf(net.minecraft.core.BlockPos p) {
        return ((long) p.getX() & 0x3FFFFFFL) << 38
                | ((long) p.getY() & 0xFFFL) << 26
                | ((long) p.getZ() & 0x3FFFFFFL);
    }

    /**
     * 卡墙脱困：朝目标直飞、**被方块顶住原地不动**时，先飘到最近的空气格，再续原来的链路。
     *
     * <p>【玩家原话】「女仆在 home 模式进行盘旋飞行的时候，显得很不聪明。如果他被方块挡住了，
     * 那他就会一直盯着那个方块飞，也不知道换路线，我觉得如果他在飞行中撞到了阻挡的方块，
     * 那么应该先尝试往最近的空气方块进行移动。然后再继续执行原有的扫帚链路」。
     *
     * <p>【为什么必然发生】她的位移是"目标速度包络 + 阻尼"算出来的一阶矢量（见 {@link #steerTo}），
     * 撞上方块时 {@code Entity.move(MoverType.SELF, …)} 只是在碰撞面上滑一下，**速度意图一个字
     * 都不变**——下一 tick 照旧朝那个点推。表现就是"盯着那块方块飞"。
     *
     * <p>【判据】连 {@link #STUCK_TICKS} tick（0.6 秒）位移小于 {@link #STILL_EPS} 且离目标还远
     * （&gt; {@link #ARRIVE}）→ 判卡住；悬停/到点/玩家驾驶都不会误判（到点那一支 {@code dist < ARRIVE}）。
     * 取点：她身边 ±{@link #ESCAPE_R} 格里的**空气格**，要求**上面一格也是空气**（她连扫帚差不多
     * 两格高）且**离目标比她现在更近**（否则就是原地打转），取最近的那个。走到它（1 格内）或
     * {@link #ESCAPE_TICKS} 用完就清除脱困状态、回到原链路——**脱困只是绕一步，不改变去哪**。
     *
     * <p>异常一律当"没卡"（这个函数在每 tick 的驱动路径上，绝不能抛）。配置
     * {@code combat.broom.unstick} 可整条关掉（默认开）。
     */
    private static Vec3 unstick(EntityMaid maid, EntityBroom broom, Vec3 aim) {
        if (!unstickCfg()) {
            STUCK.remove(maid.getUUID());
            return aim;
        }
        try {
            UUID id = maid.getUUID();
            Stuck st = STUCK.get(id);
            if (st == null) {
                st = new Stuck();
                STUCK.put(id, st);
            }
            double bx = broom.getX();
            double by = broom.getY();
            double bz = broom.getZ();
            double toAim = Math.sqrt((aim.x - bx) * (aim.x - bx)
                    + (aim.y - by) * (aim.y - by)
                    + (aim.z - bz) * (aim.z - bz));
            if (Double.isNaN(st.px)) {
                st.px = bx;
                st.py = by;
                st.pz = bz;
            }
            double moved = Math.sqrt((bx - st.px) * (bx - st.px) + (by - st.py) * (by - st.py)
                    + (bz - st.pz) * (bz - st.pz));
            st.px = bx;
            st.py = by;
            st.pz = bz;
            // ① 正在脱困：继续朝那个空气格走；到了 / 超时就收工回原链路
            if (st.escape > 0) {
                st.escape--;
                double toEsc = Math.sqrt((st.ex - bx) * (st.ex - bx) + (st.ey - by) * (st.ey - by)
                        + (st.ez - bz) * (st.ez - bz));
                if (toEsc <= 1.0 || st.escape <= 0) {
                    // 【实测六百九十一】这一趟脱困的收尾：把目标格记进"刚去过"名单（5 秒内不再选它）。
                    // 到了也算、超时没到也算（理由见 Stuck.remember）——这就是玩家点名的
                    // "刚刚寻路并且到达过的空气方块在5秒之内不应该再次登入"，用来掐掉两个格子之间的反复横跳。
                    st.remember(new net.minecraft.core.BlockPos(
                            (int) Math.floor(st.ex), (int) Math.floor(st.ey), (int) Math.floor(st.ez)),
                            gameTimeOf(maid));
                    st.escape = 0;
                    st.still = 0;
                    return aim;
                }
                return new Vec3(st.ex, st.ey, st.ez);
            }
            // ①.5【实测七百〇九】她**此刻正被方块闷住** → 立刻脱困，**不等那 0.6 秒**。
            //
            // 【为什么"卡住"那条判据救不了这一档】卡住看的是**位移**（连续 {@link #STUCK_TICKS}
            // tick 移动小于 {@link #STILL_EPS}），而扫帚贴着墙"滑"的时候每一 tick 都在动——
            // 位移不为零，`still` 永远攒不满，脱困相位一次都进不去；而**她**（乘客）已经被
            // 按在墙的边角里，每 tick 扣一点 in_wall 伤害。玩家原话：「仍然会导致女仆窒息。
            // 被方块挡住的只能是扫帚。而女仆在扫帚上似乎又没有碰撞箱，导致会陷进去窒息。」
            // ——"被挡住的只能是扫帚"正是这段因果：扫帚有自己的碰撞箱，靠 {@code move()} 停在
            // 墙外；她的座位在扫帚**朝向后方半格**（见 {@link #SEAT_BACK}），而原版
            // {@code positionRider} 是**直接摆位、不做碰撞解算**的，没人替她挡那半格。
            //
            // 【判据就用原版那一个】{@link #maidSuffocating} = {@code isInWall()}，它**恰好**
            // 是"这一 tick 原版要不要扣她 in_wall 伤害"，不比几何判定宽。
            //
            // 【选格规则同时放松】① 允许"不比现在更靠近目标"——从方块里退出来本身就是正确方向，
            // 而旧规则会把唯一可行的"退出去"那一格判成"更远"、全部否掉，于是返回 null、
            // 她原地继续闷着；② 第一趟仍尊重"5 秒内刚去过"（防两个格子之间横跳），
            // 一趟选不出来就**放开这条防抖再来一趟**——她的命比"别来回横跳"重要。
            if (maidSuffocating(maid)) {
                net.minecraft.core.BlockPos air = nearestEscapeCell(broom, maid, aim, st, true, false);
                if (air == null) {
                    air = nearestEscapeCell(broom, maid, aim, st, true, true);
                }
                if (air != null) {
                    enterEscape(maid, st, air, true);
                    return new Vec3(st.ex, st.ey, st.ez);
                }
                // 附近真的一格能容下她的空气都没有（她整个人嵌在一整片实心里）→ 保持原链路；
                // 下面"卡住"那一档还会再试一次（那一档带"更靠近目标"的约束，可能挑到别的格）。
            }
            // ② 还没到点却一动不动 → 记一笔；动起来了（或被推/被撞飞）→ 清零
            if (toAim > ARRIVE && moved < STILL_EPS) {
                st.still++;
            } else if (moved >= STILL_EPS) {
                st.still = 0;
            }
            // ③ 判出卡住 → 找最近的空气格，进脱困相位
            if (st.still >= STUCK_TICKS) {
                st.still = 0;
                net.minecraft.core.BlockPos air = nearestEscapeCell(broom, maid, aim, st, false, false);
                if (air != null) {
                    enterEscape(maid, st, air, false);
                    return new Vec3(st.ex, st.ey, st.ez);
                }
                // 【实测六百九十一】一个合格的格子都没有（附近全是"一格死洞"，或者都在 5 秒
                // 名单里）→ **原地不动**，绝不再往死洞里按她（那正是玩家看到的"钻进去然后窒息"）。
                long now2 = System.currentTimeMillis();
                if (now2 - st.lastLog >= UNSTICK_LOG_GAP_MS) {
                    st.lastLog = now2;
                    com.maidsmart.tool.PromaidLog.log("扫帚卡墙",
                            com.maidsmart.tool.PromaidLog.nameOf(maid)
                                    + " 被方块顶住，但附近没有能容下她的空气格（一格死洞 / 5 秒内刚去过）→ 原地不动等下一轮");
                }
            }
        } catch (Throwable ignored) {
        }
        return aim;
    }

    /**
     * 她身边最近的、**能容下她整个人**的空气格：水平 ±{@link #ESCAPE_R}、上下 ±2。
     *
     * <p>【实测六百九十一：玩家原话「女仆的扫帚模式对于空气的寻路不行，很多时候只往一格里面钻，
     * 导致女仆窒息。应该要加一些额外条件的，而不是单纯的拿一格来进行判定，就算要拿一格判定，
     * 也是拿那个窒息的那个格子来判定」】旧版只判**两件事**：候选格是空气、它**上面一格**也是空气。
     * 而那两格是"**扫帚**那一格"的上下——**窒息的是她、不是扫帚**：她是乘客，原版
     * {@code positionRider} 把她的座位摆在扫帚**朝向的后方 0.5 格**（{@link #SEAT_BACK} 那段
     * 字节码实证），她身体压的格子与扫帚那一格**差半格**；而乘客的位置是直接摆上去的、
     * 不走碰撞——扫帚能停在那格、她却被按进邻格/上格的方块里扣窒息伤害，就是这么来的。
     *
     * <p>【现在的条件（缺一不可）】
     * <ol>
     *   <li>候选格与它上面一格是空气（扫帚那一格：扫帚的碰撞箱 {@code 1.375 × 0.5625}，扁但宽）；</li>
     *   <li><b>她身体的每一格都是空气</b>（{@link #bodyFits}）：按座位偏移把她的碰撞箱
     *       （宽 0.6 / 高 1.5）铺进方块网格逐格判——**这就是"拿那个窒息的那个格子来判定"**。
     *       这一条同时挡住了"钻一格"：她离格边界正好半格，身体会**同时压住候选格与来路那一格**，
     *       所以一格宽的墙洞（来路那格是墙）直接不合格——那正是玩家看到的"往一格里面钻、然后窒息"；</li>
     *   <li>**不是死洞**（{@link #isPocket}）：候选格两层、东南西北四邻全是方块 → 进去就出不来，不去；</li>
     *   <li>**5 秒内没去过**（{@link Stuck#recentlyVisited}）：刚寻路到过的空气格不再选，
     *       免得她在两个格子之间反复横跳（玩家原话「防止女仆在两个空气格子之间来回反复横跳」）；</li>
     *   <li>【实测六百九十二】**不比目标点高过 {@link #ESCAPE_UP_MAX} 格**：躲建筑只是暂时调整
     *       高度，越躲越高会把攻击与链路一起飞没（那一段因果见 {@link #ESCAPE_UP_MAX}）。</li>
     * </ol>
     * 剩下的照旧：**离目标比现在更近**（否则脱困变成原地打转）、取最近的那个。
     * 一条都不合格 → 返回 null，**宁可原地不动也不把她按进方块里**（调用方会留一行日志）。
     *
     * <p>【实测七百〇九：上面这两条（"更靠近目标"、"不许超过目标高度"）在**保命那一档**必须让位】
     * 玩家原话：「仍然会导致女仆窒息。被方块挡住的只能是扫帚。而女仆在扫帚上似乎又没有碰撞箱，
     * 导致会陷进去窒息。」场景是"扫帚贴着墙、她被按进墙的边角"——那种局面下**唯一能容下她**的格
     * 往往在墙角外侧（= 离目标更远）或者要抬过目标高度，旧规则会把它们**全部否掉**、返回 null，
     * 于是她原地继续挨窒息伤害。所以 {@code suffocating} 为真时：① 不再要求"更靠近目标"
     * （从方块里退出来本身就是正确方向，方向由"哪一格放得下她"决定）；② 不再封 {@link #ESCAPE_UP_MAX}
     * 那个高度（但仍**取最近的合格格**，所以并不会变成往上爬）；{@code desperate} 为真时
     * 进一步放开"5 秒内刚去过"与"死洞"这两条防抖——**一趟选不出来就再来一趟**，
     * 她的命比"别来回横跳"重要。
     */
    private static net.minecraft.core.BlockPos nearestEscapeCell(EntityBroom broom, EntityMaid maid,
                                                                Vec3 aim, Stuck st,
                                                                boolean suffocating, boolean desperate) {
        try {
            net.minecraft.world.level.Level level = broom.level();
            net.minecraft.core.BlockPos base = broom.blockPosition();
            net.minecraft.core.BlockPos goal = new net.minecraft.core.BlockPos(
                    (int) Math.floor(aim.x), base.getY(), (int) Math.floor(aim.z));
            double here = base.distSqr(goal);
            long now = gameTimeOf(maid);
            // 【实测六百九十二】躲建筑不许爬过目标高度（见 ESCAPE_UP_MAX 那段因果）；
            // 【实测七百〇九】保命那一档不封这条（墙角外侧那一格常常比目标点高）。
            double upCap = suffocating ? Double.MAX_VALUE : aim.y + ESCAPE_UP_MAX;
            double best = Double.MAX_VALUE;
            net.minecraft.core.BlockPos bestPos = null;
            for (int dx = -ESCAPE_R; dx <= ESCAPE_R; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -ESCAPE_R; dz <= ESCAPE_R; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        net.minecraft.core.BlockPos p = base.offset(dx, dy, dz);
                        if (p.getY() > upCap) {
                            continue; // ⑤ 比目标高度还高 → 不选（"越躲越高"就是这么来的）
                        }
                        if (!isAirAt(level, p) || !isAirAt(level, p.above())) {
                            continue; // ① 扫帚那一格（扁但是宽，照旧要两格）
                        }
                        // 【实测七百〇九】保命那一档不看"是否更靠近目标"——见方法注释末段的因果
                        if (!suffocating && p.distSqr(goal) >= here) {
                            continue; // 不比现在更靠近目标 → 不选（防原地打转）
                        }
                        if (!desperate && st.recentlyVisited(p, now)) {
                            continue; // ④ 5 秒内到过 → 不再进（防两个空气格之间反复横跳）
                        }
                        if (!bodyFits(level, broom, p)) {
                            continue; // ② 她身体那几格（"窒息的那个格子"）
                        }
                        if (!desperate && isPocket(level, p)) {
                            continue; // ③ 一格死洞：进去了也出不来
                        }
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < best) {
                            best = d;
                            bestPos = p;
                        }
                    }
                }
            }
            return bestPos;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 【实测七百〇九】她**此刻正被方块闷住**吗——判据就是原版那一个：{@code isInWall()}。
     *
     * <p>【为什么用原版这个而不是自己判几何】{@code LivingEntity.isInWall()} 正是"这一 tick
     * 原版要不要扣她 {@code in_wall} 伤害"的那道判据（{@code LivingEntity.m_6075_} 字节码：
     * {@code if (isInWall()) hurt(damageSources().inWall(), 1.0F)}），它按**眼位 + 0.8×宽度**
     * 的小盒去撞方块的 {@code isSuffocating} 形状。用它 = "她真在挨窒息伤害"，不会比实伤更宽
     * （自己另写一套几何判据容易出现"看着嵌进去了、其实没扣血"的误触发）。
     *
     * <p>【为什么不能靠"卡住"那条判据兜住】见 {@link #unstick} 里 ①.5 那一段：扫帚贴墙滑行时
     * **位移一直不为零**，"连续 0.6 秒没动"永远攒不满，脱困相位一次都进不去。
     */
    private static boolean maidSuffocating(EntityMaid maid) {
        try {
            return maid.isInWall();
        } catch (Throwable t) {
            return false; // 判不出来当"没事"：绝不能因为读不到判据把她往别处搬
        }
    }

    /**
     * 【实测七百〇九】进脱困相位（把"这一趟去哪"记进 {@link Stuck}），并按来源写一行日志。
     *
     * @param suffocating 这一趟是"正被闷住"触发的（日志口径不同：那是保命，不是绕路）
     */
    private static void enterEscape(EntityMaid maid, Stuck st, net.minecraft.core.BlockPos air,
                                    boolean suffocating) {
        st.ex = air.getX() + 0.5;
        st.ey = air.getY() + 0.5;
        st.ez = air.getZ() + 0.5;
        st.escape = ESCAPE_TICKS;
        long now = System.currentTimeMillis();
        if (now - st.lastLog < UNSTICK_LOG_GAP_MS) {
            return;
        }
        st.lastLog = now;
        com.maidsmart.tool.PromaidLog.log("扫帚卡墙",
                com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + (suffocating
                                ? " 人正卡在方块里（isInWall，正在挨窒息伤害）→ 立刻退到能容下她的空气格 ("
                                : " 被方块顶住不动了 → 先飘到最近的空气格 (")
                        + air.getX() + ", " + air.getY() + ", " + air.getZ()
                        + ")，到了再续原链路");
    }

    /** 这一格是不是空气（纯读；读不到一律当"不是"，宁可少选一格也不把她按进方块里） */
    private static boolean isAirAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos p) {
        try {
            return level.getBlockState(p).isAir();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她（**作为乘客**）在"扫帚停在 {@code p} 那一格"时，身体压着的每一格是不是都是空气。
     *
     * <p>座位偏移与体型见 {@link #SEAT_BACK} 那一组常量（原版字节码实证）。这里把它落成一条算式：
     * <pre>
     *   她身体中心 = 候选格中心 − 0.5 × 单位方向(从扫帚现在的位置 → 候选格)   ← 水平（朝向的后方）
     *   她的脚     = 候选格中心 − 0.3125                                   ← 竖直
     *   碰撞箱     = 宽 0.6 / 高 1.5 → 铺进方块网格逐格查空气
     * </pre>
     * 方向取"从她现在的位置到候选格"，是因为 {@link #steerTo} 把朝向写成速度方向（飞过去就是那个朝向）。
     */
    private static boolean bodyFits(net.minecraft.world.level.Level level, EntityBroom broom,
                                    net.minecraft.core.BlockPos p) {
        double ux = (p.getX() + 0.5) - broom.getX();
        double uz = (p.getZ() + 0.5) - broom.getZ();
        double h = Math.sqrt(ux * ux + uz * uz);
        if (h < 1.0E-4) {
            ux = 0.0;
            uz = 0.0;
        } else {
            ux /= h;
            uz /= h;
        }
        double cx = p.getX() + 0.5 - SEAT_BACK * ux;
        double cz = p.getZ() + 0.5 - SEAT_BACK * uz;
        double feet = p.getY() + 0.5 - SEAT_DOWN;
        return cellsAir(level, cx - MAID_HALF_W, cz - MAID_HALF_W, feet,
                cx + MAID_HALF_W, cz + MAID_HALF_W, feet + MAID_HEIGHT);
    }

    /** 一个碰撞箱（水平 center ± half、竖直 feet→top）覆盖到的每一格是不是都是空气 */
    private static boolean cellsAir(net.minecraft.world.level.Level level,
                                    double x0, double z0, double feet,
                                    double x1, double z1, double top) {
        int bx0 = (int) Math.floor(Math.min(x0, x1));
        int bx1 = (int) Math.floor(Math.max(x0, x1));
        int bz0 = (int) Math.floor(Math.min(z0, z1));
        int bz1 = (int) Math.floor(Math.max(z0, z1));
        int by0 = (int) Math.floor(feet + 0.02);
        int by1 = (int) Math.floor(top - 0.02);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                for (int bz = bz0; bz <= bz1; bz++) {
                    if (!isAirAt(level, new net.minecraft.core.BlockPos(bx, by, bz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 是不是"一格死洞"：候选格与她身体那两层、东南西北四邻**全是方块** → 进去了就出不来的
     * 单格空腔。这种格子再近也不去（玩家原话「不要只往一格里面钻」＋「防止女仆卡死」）。
     * 一格宽的**通道**不算死洞（两侧有空气），照旧能走。
     */
    private static boolean isPocket(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos p) {
        for (int dy = 0; dy <= 1; dy++) {
            net.minecraft.core.BlockPos q = p.offset(0, dy, 0);
            if (isAirAt(level, q.offset(1, 0, 0)) || isAirAt(level, q.offset(-1, 0, 0))
                    || isAirAt(level, q.offset(0, 0, 1)) || isAirAt(level, q.offset(0, 0, -1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean unstickCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_UNSTICK.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 清理 ==================== */

    /** 这具扫帚实体没了：丢掉它的意图与"欠账"（物品已由原版掉落兜底，不能再还一次） */
    public static void forget(UUID broomId) {
        if (broomId == null) {
            return;
        }
        THRUST.remove(broomId);
        YAW.remove(broomId);
        DEBT.remove(broomId);
    }

    /** 这只女仆下线/卸载：清掉她的盘旋相位、爬升相位、本场盘旋高度、守家巡逻高度、找扫帚状态与跟随迟滞 */
    public static void forgetMaid(UUID maidId) {
        if (maidId == null) {
            return;
        }
        ORBIT.remove(maidId);
        HOME_ORBIT.remove(maidId);
        CLIMB.remove(maidId);
        COMBAT_ALT.remove(maidId);
        HOME_ALT.remove(maidId); // 【实测六百九十二】守家巡逻高度记忆
        HUNTING.remove(maidId);
        HUNT_COOLDOWN.remove(maidId);
        STUCK.remove(maidId);
        // 【实测六百九十三】随机环绕参数（半径比例 / 旋向节奏）也归她所有，下线一并丢
        CombatOrbit.forget(maidId);
        // 【实测七百〇三】接敌机动（这一场抽到的那一种 + 波形相位）同样归她所有；
        //  SERIAL（她打过几场）刻意保留在 CombatManeuver 内部——下一场要抽到不一样的
        CombatManeuver.forget(maidId);
    }

    /** 服务端停止：整表清空 */
    public static void clearAll() {
        THRUST.clear();
        YAW.clear();
        DEBT.clear();
        ORBIT.clear();
        HOME_ORBIT.clear();
        CLIMB.clear();
        COMBAT_ALT.clear();
        HOME_ALT.clear(); // 【实测六百九十二】守家巡逻高度记忆
        HUNTING.clear();
        HUNT_COOLDOWN.clear();
        STUCK.clear();
        // 【实测六百九十三】随机环绕参数
        CombatOrbit.clearAll();
        // 【实测七百〇三】接敌机动（含"这是她第几场遭遇"的序列）
        CombatManeuver.clearAll();
    }

    /* ==================== 内部工具 ==================== */

    /** 从她身上抽 1 件扫帚物品（主手 → 副手 → 背包 → 额外容器）；没有则空栈 */
    private static ItemStack takeBroomItem(EntityMaid maid) {
        try {
            net.neoforged.neoforge.items.IItemHandlerModifiable h =
                    (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            for (int slot = 0; slot <= 1; slot++) {
                if (MaidBroomKit.isBroomItem(h.getStackInSlot(slot))) {
                    ItemStack one = h.extractItem(slot, 1, false);
                    if (!one.isEmpty()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 额外容器（精妙背包 / 旅行者背包）里有的先请 TLM 搬进来一个（与 MaidFlightKit 同款）
            com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidBroomKit::isBroomItem, 1);
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (MaidBroomKit.isBroomItem(inv.getStackInSlot(i))) {
                    ItemStack one = inv.extractItem(i, 1, false);
                    if (!one.isEmpty()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static void giveBack(EntityMaid maid, ItemStack stack) {
        com.maidsmart.tool.MaidGiveBack.give(maid, stack, "扫帚模式");
    }

    /**
     * 这把扫帚上有没有**玩家在驾驶**。
     * <p>
     * TLM 的驾驶权判据就是 `getControllingPassenger()`（只有第一乘客是 Player 时才非空，
     * 字节码实证），而 mixin 那边同样"有玩家在驾驶就一个字不改"。行为侧问这一条只是为了让
     * **日志和相位干净**：玩家开着的时候我们既不该开爬升相位（会每 tick 判"顶头"刷日志），
     * 也不该写推进意图（写了也没人用）——只负责开火。
     */
    public static boolean drivenByPlayer(EntityBroom broom) {
        try {
            return broom != null
                    && broom.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 上/下扫帚的去抖与留痕（实测六百七十二） ==================== */

    /**
     * 每只女仆上一次"起飞相位"开始的 gameTime。
     *
     * <p>【为什么必须去抖】起飞相位（原地抬 1 格）的目标是**相对她当前高度**算的
     * （{@link #startClimb} 收 {@code 她当前 y + RISE_BLOCKS}），所以"上一秒骑上、下一秒又下"
     * 这种循环里，每一轮都会再抬一格——实测日志里她就是这样从 y −60.00 一路升到 +3.67 的
     * （「爬升到位」记的 y 依次是 −58.94 → −9.14 → −53.60 → +3.67，中间还夹着 5~11 次/秒的
     * 「骑上了世界里放着的那把扫帚」）。玩家原话："扫帚模式下反复横跳这个问题还是没有解决，
     * 依然会不断的攀升。" 5 秒内已经起过一次就不再起，棘轮就断了。
     */
    private static final Map<UUID, Long> TAKEOFF_AT = new HashMap<>();
    /** 起飞相位去抖窗口（tick）= 5 秒 */
    private static final long TAKEOFF_DEBOUNCE = 100L;

    /**
     * 登记"起飞相位"（原地抬 1 格）——同一只女仆 {@link #TAKEOFF_DEBOUNCE} 内只登一次。
     *
     * @return true = 这次真的登记了（调用方可以报"原地抬起 1 格"）；false = 5 秒内刚起过，跳过
     */
    private static boolean startTakeoff(EntityMaid maid) {
        try {
            long now = maid.level().getGameTime();
            Long last = TAKEOFF_AT.get(maid.getUUID());
            if (last != null && now - last < TAKEOFF_DEBOUNCE) {
                return false;
            }
            TAKEOFF_AT.put(maid.getUUID(), Long.valueOf(now));
            com.maidsmart.tool.StateTables.cap("扫帚.TAKEOFF_AT", TAKEOFF_AT);
            startClimb(maid, TAKEOFF, maid.getY() + RISE_BLOCKS + CLIMB_LEAD);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---- 「刚才已经上过一次扫帚」这类日志的节流：5 秒一条，防高频上下扫帚刷屏 ---- */

    private static final Map<UUID, Long> MOUNT_LOGGED = new HashMap<>();
    private static final long MOUNT_LOG_GAP_MS = 5000L;

    /** 上扫帚/起飞这类**可能高频**的日志走这里：同一只女仆 5 秒最多一条 */
    private static void mountLog(EntityMaid maid, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = MOUNT_LOGGED.get(maid.getUUID());
            if (last != null && now - last < MOUNT_LOG_GAP_MS) {
                return;
            }
            MOUNT_LOGGED.put(maid.getUUID(), Long.valueOf(now));
            com.maidsmart.tool.StateTables.cap("扫帚.MOUNT_LOGGED", MOUNT_LOGGED);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    /* ---- "她没扫帚"这件事要连续成立多久才真去找（去抖，见 MaidBroomBehavior ②） ---- */

    private static final Map<UUID, Long> BROOMLESS_SINCE = new HashMap<>();
    /** "没扫帚"要连续这么久（tick）= 1 秒 */
    private static final long BROOMLESS_DEBOUNCE = 20L;

    /** 她已经连续"没扫帚"够久了吗（第一次调用只记时间、返回 false） */
    public static boolean broomlessLongEnough(EntityMaid maid, long gameTime) {
        try {
            UUID id = maid.getUUID();
            Long since = BROOMLESS_SINCE.get(id);
            if (since == null) {
                BROOMLESS_SINCE.put(id, Long.valueOf(gameTime));
                return false;
            }
            return gameTime - since.longValue() >= BROOMLESS_DEBOUNCE;
        } catch (Throwable t) {
            return true; // 判不了就按旧行为（立刻去找）
        }
    }

    /** 她又有扫帚了（骑上/取出来）→ 清掉"没扫帚"的计时，下一次缺扫帚重新计 1 秒 */
    private static void clearBroomless(EntityMaid maid) {
        try {
            BROOMLESS_SINCE.remove(maid.getUUID());
        } catch (Throwable ignored) {
        }
    }

    /** 她已经在扫帚上了（不是我们放的）——每只女仆记一次，让"没取出扫帚"不再等于"没骑上" */
    private static void noteAdopted(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            synchronized (ADOPTED_LOGGED) {
                if (!ADOPTED_LOGGED.add(id)) {
                    return;
                }
                if (ADOPTED_LOGGED.size() > 256) {
                    ADOPTED_LOGGED.clear();
                    ADOPTED_LOGGED.add(id);
                }
            }
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 她已经在一把扫帚上了（不是本模组放出去的）→ 直接接管驱动，收工时不会回收它");
        } catch (Throwable ignored) {
        }
    }

    /** "想飞却没骑上"——5 秒一条上限（这是异常路径，出现就说明有东西挡着） */
    private static void noteNoDrive(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            long now = System.currentTimeMillis();
            synchronized (NO_DRIVE_LOGGED) {
                Long last = NO_DRIVE_LOGGED.get(id);
                if (last != null && now - last < LOG_GAP_MS) {
                    return;
                }
                if (NO_DRIVE_LOGGED.size() > 256) {
                    NO_DRIVE_LOGGED.clear();
                }
                NO_DRIVE_LOGGED.put(id, now);
            }
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 想飞却没骑上扫帚（ridingBroom=null；当前载具=" + entityName(maid) + "）→ 这一拍不动");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 日志用的实体名 = 实体类型（`EntityType.toString()` 就是注册名，如 `minecraft:boat` /
     * `touhou_little_maid:chair`，原版实现就是查注册表键）。
     * <p>
     * 刻意**不用** `BuiltInRegistries.ENTITY_TYPE.getKey(...)`：那是注册表字段，
     * 两树的写法不同，写它等于给镜像多一处映射风险；`toString()` 两树一字不差。
     */
    private static String entityName(net.minecraft.world.entity.Entity e) {
        if (e == null) {
            return "无";
        }
        try {
            return String.valueOf(e.getType());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 女仆当前载具的注册名（没有载具 = "无"） */
    private static String entityName(EntityMaid maid) {
        try {
            return entityName(maid.getVehicle());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 日志用的两位小数（固定 Locale：避免某些语言把小数点写成逗号） */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 战斗盘旋距离（格） */
    private static double rangeCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_RANGE.get();
        } catch (Throwable ignored) {
            return 8.0;
        }
    }

    /**
     * 【实测六百九十三】锁敌之后离敌的**最远距离**（格）：配置 {@code combat.broom.orbitMax}，
     * 默认 10（= 基础盘旋距离 8 的 1.25 倍，均值仍落在 8 上）。它同时是随机环绕区间的顶点
     * （见 {@link #combatPoint}），所以调小它 = 把她整体拉近 + 收紧随机范围，调大 = 允许她在
     * 更宽的一圈里飘。配置没挂上时退回 10——与配置里的默认值对齐（本项目的老规矩：兜底值必须
     * 跟着默认值走）。
     */
    private static double orbitMaxCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_ORBIT_MAX.get();
        } catch (Throwable ignored) {
            return 10.0;
        }
    }

    /**
     * 【实测七百零一】接敌后离敌的**最小距离**（格）：配置 {@code combat.broom.minStandoff}，
     * 默认 6（= 基础盘旋距离 8 × 0.75，与实测六百九十三 那套区间的近端同值——加这一项是把它
     * 从"跟着另一个旋钮现算"变成"独立的一条线"）。配置没挂上时退回 6——与配置里的默认值对齐
     * （本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double minStandoffCfg() {
        try {
            return Math.max(1.0, com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_MIN_STANDOFF.get());
        } catch (Throwable ignored) {
            return MIN_STANDOFF;
        }
    }

    /** 悬停高度（格，相对目标脚底） */
    private static double hoverCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_HOVER.get();
        } catch (Throwable ignored) {
            return 2.0;
        }
    }

    /**
     * 【实测七百〇三】接敌机动总开关：配置 {@code combat.maneuver.enable}，默认开。
     * 配置没挂上时退回 **true**——与配置里的默认值对齐（本项目的老规矩：兜底值跟着默认值走）。
     * 关掉 = 退回旧行为（永远环绕；半径 / 旋向 / 快慢的随机照旧保留）。
     */
    private static boolean maneuverEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_MANEUVER_ENABLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 【实测七百〇三】高悠悠的高度波幅度（格）：配置 {@code combat.maneuver.yoyoAmp}，默认 4。
     * 配置没挂上时退回 4——同一条老规矩。
     */
    private static double yoyoAmpCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_MANEUVER_YOYO_AMP.get();
        } catch (Throwable ignored) {
            return 4.0;
        }
    }

    /**
     * 【实测六百九十一】守家盘旋高度（格，**离地**）：配置 {@code combat.broom.homeAlt}，默认 8。
     *
     * <p>玩家原话：「home 模式下女仆会进行飞行盘旋巡逻……但是女仆很喜欢贴地飞行。这个观感太差了。」
     * 旧版守家盘旋的目标高度是"扫帚当前高度"（起飞只抬 {@link #RISE_BLOCKS} 格 → 整场贴地飞），
     * 现在改成"她脚下那块地之上这么多格"，再由 {@link #safeY} 兜住天花板。
     * 这一项只影响**守家巡逻**这一档，接敌照旧走 {@code combat.broom.climb}。
     *
     * <p>【为什么不复用「接敌爬升高度」】那个数是"相对敌人"（没有敌人时根本没有那个参照物），
     * 这个数是"离地"，两个参照系不同；合成一个数只会让其中一边永远不对。
     *
     * <p>配置没挂上时退回 8——与配置里的默认值对齐（本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double homeAltCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_HOME_ALT.get();
        } catch (Throwable ignored) {
            return 8.0;
        }
    }

    /** 世界游戏刻（拿不到就 0）——脱困"刚去过"名单的过期判据（与 {@code Climb.retryDue} 同一个口径） */
    private static long gameTimeOf(EntityMaid maid) {
        try {
            return maid.level().getGameTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 【实测六百七十八】接敌爬升高度（格，相对敌人脚底）：配置 {@code combat.broom.climb}，
     * 默认 12（【实测六百八十六】15 → 12；六百八十二 是 10 → 15；六百七十八 之前写死 8，
     * 六百七十八 起的 10），面板「移动与行为 → 扫帚模式 → 接敌爬升高度」可调 2~32。
     * 配置没挂上时退回 12——与配置里的默认值对齐（本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double climbCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_CLIMB.get();
        } catch (Throwable ignored) {
            return 12.0;
        }
    }
}
