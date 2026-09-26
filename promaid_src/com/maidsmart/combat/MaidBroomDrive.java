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
    //  （实测六百八十二 起默认 15，2~32，面板「移动与行为 → 扫帚模式 → 接敌爬升高度」可调），见下面的 climbCfg()。
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

    /** 弧度→度（MC 的 yaw 约定：朝向 (dx,dz) 的偏航角 = {@code -atan2(dx, dz)} 换成度） */
    private static final float DEG = 57.295776F;
    /**
     * 爬升相位的"升不动"判定：连续这么多 tick 没有长高就算头顶被顶住（见 {@link Climb#stalled}）。
     *
     * <p>【实测六百八十四】4 → 10。4 tick（0.2 秒）比"起手加速"还短：{@link #BLEND} 那一支从
     * 零起步的第一拍只涨 0.075 格，只要有一次方块角/叶子的擦碰把这一拍抹平，本场遭遇的盘旋高度
     * 就被钉在地板上（实测日志里 2.00 / 2.93 / 3.66 格就是这么来的）。0.5 秒的确认对"她是不是
     * 真升不上去"零损失——正常爬满 15 格要走 3 秒。
     */
    private static final int STALL_TICKS = 10;
    /**
     * 【实测六百八十四】头顶被顶住之后，最多隔这么久再试一次爬升（tick，100 = 5 秒）。
     *
     * <p>旧版是"一场遭遇只爬一次"，于是她只要在低矮房间里挨过第一次爬升，**整场**都按那几格飞，
     * 走进开阔地也升不回去（实测日志：16:46:15 钉在 3.66 格 →「以后一直保持这个高度」）。
     * 重试还要过一个"头顶现在是不是空气"的探针（见 {@link Climb#roomNow}）：房间里照样是顶的
     * 时候一次都不试，她不会为了够一个够不到的高度在原地一抽一抽。
     */
    private static final int CLIMB_RETRY_TICKS = 100;
    /**
     * 【实测六百八十四】连续重试失败时的退避上限（tick，1200 = 60 秒）：
     * 每失败一次等待翻倍（100 → 200 → …），封顶在这里。理由：真被天花板压着的房间里，
     * 每 5 秒试一次、每次都失败、每次都写两行日志——既没用又刷屏。退避到一分钟一次之后，
     * 她在同一个房间里打十分钟也只有十来行。
     */
    private static final int CLIMB_RETRY_MAX_TICKS = 1200;
    /**
     * 战斗盘旋的**线速度**（格/tick）：0.14 ≈ 2.8 格/秒。
     * <p>
     * 取"线速度恒定"而不是"角速度恒定"，是因为盘旋半径可配（默认 8 格）：角速度固定时
     * 半径越大、目标点的圆周线速度越大——她永远追不上那个点，表现成"绕不动、只在原地抖"。
     * 按线速度换算（{@code step = ORBIT_SPEED / 半径}）后，任何半径下她都能稳稳跟上。
     */
    private static final double ORBIT_SPEED = 0.14;
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
        if (taken.m_41619_()) {
            return null;
        }
        net.minecraft.world.entity.Entity prev = null;
        try {
            prev = maid.m_20202_(); // force 骑乘会把她从这上面叫下来，先记着是谁（日志用）
        } catch (Throwable ignored) {
        }
        EntityBroom broom = null;
        try {
            broom = new EntityBroom(level);
            broom.m_7678_(maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), maid.m_146908_(), 0.0f);
            try {
                broom.setOwnerUUID(maid.m_21805_());
            } catch (Throwable ignored) {
            }
            if (!level.m_7967_(broom)) {
                giveBack(maid, taken);
                return null;
            }
            // 【force = true】实测六百五十七：不 force 的 startRiding 要求她**此刻不是任何载具的
            // 乘客**（原版 `canRide` = `!isPassenger() && …`），而她会坐在椅子（我们自己的钓鱼椅
            // 就是靠 `startRiding(chair, true)` 让她坐上去的）或别的载具上——那样这里恒返回 false，
            // 于是**每 tick 取出一把扫帚、骑不上、再还回去**：面板上看不到任何报错（缺件判据看到
            // "扫帚物品在"就不报），她却永远上不去。与本模组其它上座点（FishingChairService）同款：
            // 要她上去就 force。
            if (!maid.m_7998_(broom, true)) {
                // 连 force 都骑不上：把扫帚收掉、物品还她，不留孤儿实体（并且一定要留痕）
                com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 骑不上扫帚：startRiding(force) 返回 false（当前载具=" + entityName(maid) + "）→ 扫帚收回背包");
                broom.m_6075_();
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
                    broom.m_6075_();
                } catch (Throwable ignored) {
                }
            }
            giveBack(maid, taken);
            return null;
        }
        DEBT.put(broom.m_20148_(), taken);
        ORBIT.remove(maid.m_20148_());
        clearBroomless(maid); // 【实测六百七十二】取出来骑上了 → 没扫帚的计时清零
        // 玩家要求："女仆会立刻用扫帚飞起来 1 格"——登记起飞相位（她脚下 +1 格，另加
        // 到点判定的余量，见 CLIMB_LEAD），由行为先垂直抬起来、抬到位再开始"去哪"的正常逻辑。
        // 【实测六百七十二】走 startTakeoff：5 秒内已经起过一次就**不再重复抬**
        //（反复上/下扫帚时，每一轮都抬一格就是那个"不断攀升"的棘轮）。
        boolean lifted = startTakeoff(maid);
        mountLog(maid, "取出扫帚骑上（消耗 " + taken.m_41613_() + "x "
                + taken.m_41786_().getString() + "，收工时原物归还）→ "
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
     * N = 配置 {@code combat.broom.climb}（**实测六百七十八 起默认 10**，面板可调 2~32；
     * 六百七十八 之前是写死的 8）。
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
     * @param target 当前敌人（取它的 UUID 当相位的 key、取它的脚底当高度参照）
     */
    public static Double combatClimbTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null) {
            return null;
        }
        double climb = climbCfg(); // 【实测六百七十八】配置项：默认 10（旧版写死 8）
        Object key = target.m_20148_();
        java.util.UUID id = maid.m_20148_();
        Climb c = CLIMB.get(id);
        if (c == null) {
            // 【相对敌人】目标脚底 + climb（另加 ARRIVE 余量，见 CLIMB_LEAD）
            double to = target.m_20186_() + climb + CLIMB_LEAD;
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
                double to = target.m_20186_() + climb + CLIMB_LEAD;
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
            double alt = maid.m_20186_() - target.m_20186_();
            double fixed = Math.max(hoverCfg(), Math.min(climb, alt));
            COMBAT_ALT.put(id, fixed);
            Climb now = CLIMB.get(id);
            boolean blocked = now != null && now.blocked;
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 本场盘旋高度 = 敌人上方 " + fmt(fixed) + " 格（想 " + fmt(climb)
                    + "，实际 " + fmt(alt) + " 格）"
                    + (blocked
                            ? "→ 头顶被顶住，先按这个高度飞；头顶一开阔就再爬一次（每 "
                                    + (CLIMB_RETRY_TICKS / 20) + " 秒最多试一次）"
                            : "→ 以后一直保持这个高度"));
        }
        return y;
    }

    /** 结束爬升相位（没目标了 / 收了工）——顺带作废这一场遭遇的盘旋高度 */
    public static void clearClimb(EntityMaid maid) {
        if (maid != null) {
            CLIMB.remove(maid.m_20148_());
            COMBAT_ALT.remove(maid.m_20148_());
        }
    }

    private static void startClimb(EntityMaid maid, Object key, double targetY) {
        CLIMB.put(maid.m_20148_(), new Climb(key, targetY, maid.m_20186_()));
    }

    /**
     * 爬升相位的统一推进：返回这一 tick 该爬到的 Y，null = 相位已结束（或不该由本相位管）。
     *
     * 【"头顶被顶住"怎么判】不查方块，看**她自己有没有长高**：原版 {@code Entity.move}
     * 撞到方块就动不了，所以"想往上、却没上去"这件事本身就是"被顶住了"的证据。
     * 这比查碰撞箱更稳——半个砖、楼梯、火把、水都各自有各自的碰撞形状，而"没长高"是事实。
     */
    private static Double climbTarget(EntityMaid maid, Object key) {
        Climb c = CLIMB.get(maid.m_20148_());
        if (c == null || !c.key.equals(key)) {
            return null;
        }
        if (c.done) {
            return null;
        }
        double y = maid.m_20186_();
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
        /** 顶住之后过了多少 tick（只在 done 且 blocked 的分支里累加） */
        int sinceBlocked;
        /** 【实测六百八十四】这一场遭遇里已经重试过几次（退避翻倍用，见 retryDue） */
        int retries;
        /** 被顶住时她在哪儿（重试判据之一：挪开了就立刻再试一次） */
        private double bx;
        private double bz;
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
            return this.stall >= STALL_TICKS;
        }

        /** 【实测六百八十四】记下"在哪儿被顶住的"（重试的两个判据都用它） */
        void noteBlocked(EntityMaid maid) {
            try {
                this.bx = maid.m_20185_();
                this.bz = maid.m_20189_();
            } catch (Throwable ignored) {
            }
            this.sinceBlocked = 0;
        }

        /** 从被顶住那一点挪开了多远（格，只看水平） */
        double movedSince(EntityMaid maid) {
            try {
                double dx = maid.m_20185_() - this.bx;
                double dz = maid.m_20189_() - this.bz;
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
            this.sinceBlocked += 2; // 本表由每 2 tick 的行为调一次
            long wait = (long) CLIMB_RETRY_TICKS << Math.min(this.retries, 8);
            if (wait > CLIMB_RETRY_MAX_TICKS) {
                wait = CLIMB_RETRY_MAX_TICKS;
            }
            return this.sinceBlocked >= wait && roomNow(maid);
        }

        /** 她脚底往上 1~3 格是不是空气（纯读，只服务重试判据） */
        private boolean roomNow(EntityMaid maid) {
            try {
                net.minecraft.world.level.Level level = maid.m_9236_();
                net.minecraft.core.BlockPos base = maid.m_20183_();
                for (int dy = 1; dy <= 3; dy++) {
                    if (!level.m_8055_(base.m_7918_(0, dy, 0)).m_60795_()) {
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
                net.minecraft.world.level.Level level = maid.m_9236_();
                net.minecraft.core.BlockPos base = maid.m_20183_();
                StringBuilder sb = new StringBuilder();
                for (int dy = 1; dy <= 3; dy++) {
                    if (dy > 1) {
                        sb.append(" / ");
                    }
                    net.minecraft.world.level.block.state.BlockState st = level.m_8055_(base.m_7918_(0, dy, 0));
                    sb.append("+").append(dy).append("=").append(st.m_60795_() ? "空气" : blockName(st));
                }
                return sb.toString();
            } catch (Throwable ignored) {
                return "（读不到）";
            }
        }

        private static String blockName(net.minecraft.world.level.block.state.BlockState st) {
            try {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.m_60734_());
                return rl == null ? String.valueOf(st.m_60734_()) : rl.toString();
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
        ItemStack debt = DEBT.remove(broom.m_20148_());
        try {
            maid.m_8127_();
        } catch (Throwable ignored) {
        }
        noteDismount(maid, why, debt != null);
        if (debt == null) {
            // 不是我们放出去的扫帚（玩家自己放的 / 别的模组）：一个字都不动，留给玩家
            return;
        }
        try {
            if (broom.m_6084_()) {
                // kill() 只移除实体、**不**按原版掉落（原版掉落走 killEntity），物品由我们精确归还
                broom.m_6075_();
            }
        } catch (Throwable ignored) {
        }
        forget(broom.m_20148_());
        giveBack(maid, debt);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 收工下扫帚，扫帚已放回背包");
    }

    /** 下扫帚留痕（{@code hadDebt} = 这把是不是我们放出去的）：同一只女仆 5 秒一条上限 */
    private static void noteDismount(EntityMaid maid, String why, boolean hadDebt) {
        try {
            long now = System.currentTimeMillis();
            Long last = DISMOUNT_LOGGED.get(maid.m_20148_());
            if (last != null && now - last < MOUNT_LOG_GAP_MS) {
                return;
            }
            DISMOUNT_LOGGED.put(maid.m_20148_(), Long.valueOf(now));
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
        THRUST.put(broom.m_20148_(), vel);
        YAW.put(broom.m_20148_(), yaw);
    }

    /** mixin 取走本 tick 的推进矢量（取走即清：没有新意图的 tick 就悬停原地，绝不自由落体） */
    public static Vec3 takeThrust(EntityBroom broom) {
        if (broom == null) {
            return null;
        }
        return THRUST.remove(broom.m_20148_());
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
        THRUST.remove(broom.m_20148_());
        YAW.remove(broom.m_20148_());
    }

    /** mixin 取朝向（保留最后写入的那个，悬停时朝向不抖） */
    public static float yaw(EntityBroom broom) {
        if (broom == null) {
            return 0.0f;
        }
        Float f = YAW.get(broom.m_20148_());
        return f == null ? broom.m_146908_() : f;
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
        YAW.put(broom.m_20148_(), yaw);
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
     * 收尾时写进 {@link #COMBAT_ALT}（默认想抬 8 格、顶头按实际、下限 {@link #hoverCfg}）。
     * 没有记录（理论上只在爬升还没做完时）才退回配置的悬停高度——**绝不出现"爬到 8 格
     * 又被另一套高度拽回来"**（那正是玩家反馈的"升上去又慢慢掉下来"）。
     *
     * <p>【实测六百八十四 补充】"顶头按实际"是**暂时的**：被顶住那一次按实际高度飞，
     * 但只要她头顶一开阔（她自己飞到高处 / 房间变高），{@link #combatClimbTarget} 会重试
     * 爬升并把这个数改回 {@code climb}。所以这一格不会像旧版那样把一整场遭遇钉在地板上。
     *
     * @return 期望位置（已过 {@link MaidBroomKit#clampToHome} 夹取，见 {@link #steerTo}）
     */
    public static Vec3 combatPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.m_20148_();
        double r = Math.max(1.0, rangeCfg());
        // 角速度由固定线速度换算（见 ORBIT_SPEED 的注释）：任何半径下她都能跟上这个点
        double ang = ORBIT.getOrDefault(id, 0.0) + ORBIT_SPEED / r;
        ORBIT.put(id, ang);
        double alt = COMBAT_ALT.getOrDefault(id, hoverCfg());
        return new Vec3(target.m_20185_() + Math.cos(ang) * r,
                target.m_20186_() + alt,
                target.m_20189_() + Math.sin(ang) * r);
    }

    /**
     * 平时（没有敌人）的目标点：**悬停在主人身边**——保持主人到她当前所在的这个方位、
     * 水平 {@link #FOLLOW_DIST} 格、高 {@link #FOLLOW_HOVER} 格。
     * 用"她当前方位"而不是固定方位，是为了她不会为了换边而横穿主人的脸。
     */
    public static Vec3 followPoint(EntityMaid maid, LivingEntity owner) {
        double dx = maid.m_20185_() - owner.m_20185_();
        double dz = maid.m_20189_() - owner.m_20189_();
        double ang = (Math.abs(dx) + Math.abs(dz) < 0.05) ? 0.0 : Math.atan2(dz, dx);
        return new Vec3(owner.m_20185_() + Math.cos(ang) * FOLLOW_DIST,
                owner.m_20186_() + FOLLOW_HOVER,
                owner.m_20189_() + Math.sin(ang) * FOLLOW_DIST);
    }

    /**
     * 守家（工作范围）时的目标点：**沿着工作范围那个圈的边缘慢慢盘旋**（v1.3.6 实测六百六十一）。
     *
     * <p>【玩家原话】「如果我在扫把模式下开启鸿蒙，那个女仆正常就会在工作范围内对着工作范围
     * 那个圈进行盘旋，直到接敌。」旧版 home 对扫帚模式只剩「把目标点夹进圈里」这一条，
     * 于是开着 home 她也只是跟着主人悬停——看不出「守家」。现在平时（没有敌人）改成绕圈巡逻。
     *
     * <p>【高度不动】只改水平（x/z 沿圆周走，y 保持扫帚当前高度）。理由：工作范围在 TLM 里
     * 本来就是个**水平**圆（{@code getRestrictCenter()} 是 BlockPos、半径是个 float），
     * 没有任何纵向语义；而扫帚模式的高度由起飞/接敌两段决定，这里再插一脚只会变成
     * "贴着地面爬"或"顶在树上"。她原来多高就多高，接敌时照旧走爬升相位。
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
            UUID id = maid.m_20148_();
            double r = Math.max(2.0, maid.m_21535_() - 1.5);
            double ang = HOME_ORBIT.getOrDefault(id, 0.0) + ORBIT_SPEED / r;
            HOME_ORBIT.put(id, ang);
            Vec3 p = broomPos(maid);
            return new Vec3(c.m_123341_() + 0.5 + Math.cos(ang) * r,
                    p.f_82480_,
                    c.m_123343_() + 0.5 + Math.sin(ang) * r);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 原地悬停 / 垂直爬升：一律用**扫帚自己的坐标** ==================== */

    /** 这把扫帚（载具）现在在哪；没骑着就退回她自己的位置（调用方只在骑着时用） */
    private static Vec3 broomPos(EntityMaid maid) {
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        return broom == null
                ? new Vec3(maid.m_20185_(), maid.m_20186_(), maid.m_20189_())
                : broom.m_20182_();
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
        steerTo(maid, new Vec3(p.f_82479_, y, p.f_82481_));
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
            faceYaw(broom, (float) (-Math.atan2(target.m_20185_() - broom.m_20185_(),
                    target.m_20189_() - broom.m_20189_()) * DEG));
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
        UUID id = maid.m_20148_();
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
        double d = maid.m_20270_(goal);
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
                            ? "世界里放着的那把扫帚（" + fmt(Math.sqrt(maid.m_20280_(world))) + " 格外），过去骑"
                            : "掉在地上的扫帚（" + fmt(Math.sqrt(maid.m_20280_(drop))) + " 格外），过去捡")
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
            net.minecraft.world.entity.ai.behavior.BehaviorUtils.m_22617_(maid, goal.m_20183_(),
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
            Vec3 p = maid.m_20182_();
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    p.f_82479_ - HUNT_RADIUS, p.f_82480_ - HUNT_RADIUS, p.f_82481_ - HUNT_RADIUS,
                    p.f_82479_ + HUNT_RADIUS, p.f_82480_ + HUNT_RADIUS, p.f_82481_ + HUNT_RADIUS);
            EntityBroom best = null;
            double bestD = Double.MAX_VALUE;
            for (EntityBroom b : level.m_6443_(EntityBroom.class, box,
                    x -> x.m_6084_() && x.m_20197_().isEmpty())) {
                double d = maid.m_20280_(b);
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
            if (broom == null || maid == null || !broom.m_6084_()) {
                return false;
            }
            if (!broom.m_20197_().isEmpty()) {
                return false; // 这一 tick 刚有人骑上去：让给它，下一 tick 再看别的/再看它
            }
            if (broom.m_9236_() != maid.m_9236_()) {
                return false;
            }
            // force = true：与 ensureMounted 同款（原版不带 force 的 startRiding 要求她此刻
            // 不是任何载具的乘客，坐椅子/坐别的载具时恒返回 false，那就永远上不去）
            if (!maid.m_7998_(broom, true)) {
                return false;
            }
            ORBIT.remove(maid.m_20148_());
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
            Vec3 p = maid.m_20182_();
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    p.f_82479_ - HUNT_RADIUS, p.f_82480_ - HUNT_RADIUS, p.f_82481_ - HUNT_RADIUS,
                    p.f_82479_ + HUNT_RADIUS, p.f_82480_ + HUNT_RADIUS, p.f_82481_ + HUNT_RADIUS);
            net.minecraft.world.entity.item.ItemEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (net.minecraft.world.entity.item.ItemEntity e
                    : level.m_6443_(net.minecraft.world.entity.item.ItemEntity.class, box,
                            x -> MaidBroomKit.isBroomItem(x.m_32055_()))) {
                double d = maid.m_20280_(e);
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
            ItemStack stack = drop.m_32055_();
            if (stack.m_41619_()) {
                return false;
            }
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            ItemStack rest = stack.m_41777_();
            for (int i = 0; i < inv.getSlots() && !rest.m_41619_(); i++) {
                rest = inv.insertItem(i, rest, false);
            }
            if (!rest.m_41619_()) {
                return false; // 背包满：留在地上
            }
            drop.m_6075_();
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 把地上的扫帚捡进背包了（" + stack.m_41613_() + "x "
                    + stack.m_41786_().getString() + "）");
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
        Vec3 aim = MaidBroomKit.clampToHome(maid, desired);
        if (aim == null) {
            return;
        }
        aim = unstick(maid, broom, aim); // v1.3.0(beta) 实测六百六十四：卡墙脱困
        Vec3 cur = broom.m_20184_();
        double dx = aim.f_82479_ - broom.m_20185_();
        double dy = aim.f_82480_ - broom.m_20186_();
        double dz = aim.f_82481_ - broom.m_20189_();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (dist < ARRIVE) {
            // 到点：走原版"没有输入"那一支（速度乘 0.75 收干）→ 原地悬停，绝不自由落体
            setThrust(broom, cur.m_82490_(IDLE_DECAY), yaw(broom));
            return;
        }
        // 到点减速带：远处全速、近了按距离线性收干（分子分母同量纲，dist→0 时速度→0）
        double ramp = Math.min(1.0, dist / ARRIVE_RAMP);
        double inv = 1.0 / dist;
        Vec3 tgt = new Vec3(dx * inv * MAX_H_SPEED * ramp,
                dy * inv * MAX_V_SPEED * ramp,
                dz * inv * MAX_H_SPEED * ramp);
        Vec3 nv = cur.m_165921_(tgt, BLEND);
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
            STUCK.remove(maid.m_20148_());
            return aim;
        }
        try {
            UUID id = maid.m_20148_();
            Stuck st = STUCK.get(id);
            if (st == null) {
                st = new Stuck();
                STUCK.put(id, st);
            }
            double bx = broom.m_20185_();
            double by = broom.m_20186_();
            double bz = broom.m_20189_();
            double toAim = Math.sqrt((aim.f_82479_ - bx) * (aim.f_82479_ - bx)
                    + (aim.f_82480_ - by) * (aim.f_82480_ - by)
                    + (aim.f_82481_ - bz) * (aim.f_82481_ - bz));
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
                    st.escape = 0;
                    st.still = 0;
                    return aim;
                }
                return new Vec3(st.ex, st.ey, st.ez);
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
                net.minecraft.core.BlockPos air = nearestAirAhead(broom, aim);
                if (air != null) {
                    st.ex = air.m_123341_() + 0.5;
                    st.ey = air.m_123342_() + 0.5;
                    st.ez = air.m_123343_() + 0.5;
                    st.escape = ESCAPE_TICKS;
                    long now = System.currentTimeMillis();
                    if (now - st.lastLog >= UNSTICK_LOG_GAP_MS) {
                        st.lastLog = now;
                        com.maidsmart.tool.PromaidLog.log("扫帚卡墙",
                                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 被方块顶住不动了 → 先飘到最近的空气格 ("
                                        + air.m_123341_() + ", " + air.m_123342_() + ", " + air.m_123343_()
                                        + ")，到了再续原链路");
                    }
                    return new Vec3(st.ex, st.ey, st.ez);
                }
            }
        } catch (Throwable ignored) {
        }
        return aim;
    }

    /**
     * 她身边最近的、**能容下她**的空气格：水平 ±{@link #ESCAPE_R}、上下 ±2。
     *
     * <p>三条件缺一不可：这一格是空气、**上面一格也是空气**（她连扫帚差不多两格高，只空一格会卡住）、
     * 且**离目标比现在更近**（只挑"往目标那一侧"的格子，否则脱困就变成原地打转）。
     * 取"离她最近"的那个——玩家原话就是"最近的空气方块"。
     */
    private static net.minecraft.core.BlockPos nearestAirAhead(EntityBroom broom, Vec3 aim) {
        try {
            net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) broom.m_9236_();
            net.minecraft.core.BlockPos base = broom.m_20183_();
            net.minecraft.core.BlockPos goal = new net.minecraft.core.BlockPos(
                    (int) Math.floor(aim.f_82479_), base.m_123342_(), (int) Math.floor(aim.f_82481_));
            double here = base.m_123331_(goal);
            double best = Double.MAX_VALUE;
            net.minecraft.core.BlockPos bestPos = null;
            for (int dx = -ESCAPE_R; dx <= ESCAPE_R; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -ESCAPE_R; dz <= ESCAPE_R; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        net.minecraft.core.BlockPos p = base.m_7918_(dx, dy, dz);
                        if (!level.m_8055_(p).m_60795_() || !level.m_8055_(p.m_7494_()).m_60795_()) {
                            continue;
                        }
                        if (p.m_123331_(goal) >= here) {
                            continue; // 不比现在更靠近目标 → 不选（防原地打转）
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

    /** 这只女仆下线/卸载：清掉她的盘旋相位、爬升相位、本场盘旋高度、找扫帚状态与跟随迟滞 */
    public static void forgetMaid(UUID maidId) {
        if (maidId == null) {
            return;
        }
        ORBIT.remove(maidId);
        HOME_ORBIT.remove(maidId);
        CLIMB.remove(maidId);
        COMBAT_ALT.remove(maidId);
        HUNTING.remove(maidId);
        HUNT_COOLDOWN.remove(maidId);
        STUCK.remove(maidId);
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
        HUNTING.clear();
        HUNT_COOLDOWN.clear();
        STUCK.clear();
    }

    /* ==================== 内部工具 ==================== */

    /** 从她身上抽 1 件扫帚物品（主手 → 副手 → 背包 → 额外容器）；没有则空栈 */
    private static ItemStack takeBroomItem(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandlerModifiable h =
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            for (int slot = 0; slot <= 1; slot++) {
                if (MaidBroomKit.isBroomItem(h.getStackInSlot(slot))) {
                    ItemStack one = h.extractItem(slot, 1, false);
                    if (!one.m_41619_()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 额外容器（精妙背包 / 旅行者背包）里有的先请 TLM 搬进来一个（与 MaidFlightKit 同款）
            com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidBroomKit::isBroomItem, 1);
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (MaidBroomKit.isBroomItem(inv.getStackInSlot(i))) {
                    ItemStack one = inv.extractItem(i, 1, false);
                    if (!one.m_41619_()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
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
                    && broom.m_6688_() instanceof net.minecraft.world.entity.player.Player;
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
            long now = maid.m_9236_().m_46467_();
            Long last = TAKEOFF_AT.get(maid.m_20148_());
            if (last != null && now - last < TAKEOFF_DEBOUNCE) {
                return false;
            }
            TAKEOFF_AT.put(maid.m_20148_(), Long.valueOf(now));
            startClimb(maid, TAKEOFF, maid.m_20186_() + RISE_BLOCKS + CLIMB_LEAD);
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
            Long last = MOUNT_LOGGED.get(maid.m_20148_());
            if (last != null && now - last < MOUNT_LOG_GAP_MS) {
                return;
            }
            MOUNT_LOGGED.put(maid.m_20148_(), Long.valueOf(now));
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
            UUID id = maid.m_20148_();
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
            BROOMLESS_SINCE.remove(maid.m_20148_());
        } catch (Throwable ignored) {
        }
    }

    /** 她已经在扫帚上了（不是我们放的）——每只女仆记一次，让"没取出扫帚"不再等于"没骑上" */
    private static void noteAdopted(EntityMaid maid) {
        try {
            UUID id = maid.m_20148_();
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
            UUID id = maid.m_20148_();
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
            return String.valueOf(e.m_6095_());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 女仆当前载具的注册名（没有载具 = "无"） */
    private static String entityName(EntityMaid maid) {
        try {
            return entityName(maid.m_20202_());
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
     * 【实测六百七十八】接敌爬升高度（格，相对敌人脚底）：配置 {@code combat.broom.climb}，
     * 默认 15（【实测六百八十二】10 → 15；六百七十八 之前写死 8，六百七十八 起的 10），
     * 面板「移动与行为 → 扫帚模式 → 接敌爬升高度」可调 2~32。
     * 配置没挂上时退回 15——与配置里的默认值对齐（本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double climbCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_CLIMB.get();
        } catch (Throwable ignored) {
            return 15.0;
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
}
