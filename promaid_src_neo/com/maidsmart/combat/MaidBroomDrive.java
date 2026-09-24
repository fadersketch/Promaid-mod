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
    /** 女仆 UUID → 当前爬升相位（起飞 / 接敌） */
    private static final Map<UUID, Climb> CLIMB = new HashMap<>();

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
    /** 接敌爬升的高度（格）——玩家要求"先向上飞 8 格" */
    private static final double CLIMB_BLOCKS = 8.0;
    /**
     * 爬升相位的目标 Y 写在"想抬的高度 + 这个余量"处（{@link #ARRIVE} 格）。
     * <p>
     * 为什么要这个余量：她到位的方式是 {@link #steerTo} 的比例导引，而那一支在距目标
     * {@link #ARRIVE} 格时就判定"到点、收干悬停"——所以想让她**真的抬满 1 / 8 格**，
     * 目标就得写在"到点线"之上 {@link #ARRIVE} 格处。两边共用同一个常量，改一处两边一起动。
     */
    private static final double CLIMB_LEAD = ARRIVE;
    /** 爬升相位的触发起飞标识（与"目标 UUID"这种 key 并列，只有一个语义：这一次爬升是谁要的） */
    private static final Object TAKEOFF = new Object();
    /** 弧度→度（凋灵那行 `* 57.295776F`，也是 MC 的 yaw 约定） */
    private static final float DEG = 57.295776F;
    /** 爬升相位的"升不动"判定：连续这么多 tick 没有长高就算头顶被顶住（见 Climb.stalled） */
    private static final int STALL_TICKS = 4;
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
        // 玩家要求："女仆会立刻用扫帚飞起来 1 格"——登记起飞相位（她脚下 +1 格，另加
        // 到点判定的余量，见 CLIMB_LEAD），由行为先垂直抬起来、抬到位再开始"去哪"的正常逻辑。
        startClimb(maid, TAKEOFF, maid.getY() + RISE_BLOCKS + CLIMB_LEAD);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 取出扫帚骑上（消耗 " + taken.getCount() + "x "
                + taken.getHoverName().getString() + "，收工时原物归还）→ 原地抬起 "
                + RISE_BLOCKS + " 格");
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
     * 接敌爬升相位的目标 Y：「遇到敌人之后，先向上飞 8 格，然后绕着敌人盘旋」。
     *
     * <p>**以目标（敌人）为单位起相位**：换了一个敌人就重新爬一次 8 格（同一场遭遇里
     * 只爬一次——所以相位做完是**留在表里打 done 标记**而不是删掉，删掉的话下一 tick
     * 又会当"新相位"从头爬，变成无限上蹿）。相位结束（到位 / 顶头）后返回 null，
     * 调用方转去 {@link #combatPoint} 盘旋。丢目标时由 {@link #clearClimb} 清掉，
     * 于是下一次接敌会重新爬。
     *
     * @param target 当前敌人（只取它的 UUID 当相位的 key）
     */
    public static Double combatClimbTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null) {
            return null;
        }
        Object key = target.getUUID();
        Climb c = CLIMB.get(maid.getUUID());
        if (c == null || !c.key.equals(key)) {
            double to = maid.getY() + CLIMB_BLOCKS + CLIMB_LEAD;
            startClimb(maid, key, to);
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 接敌 → 先向上爬 " + CLIMB_BLOCKS + " 格再盘旋");
            return to;
        }
        if (c.done) {
            return null; // 这一场遭遇已经爬过了，直接盘旋
        }
        return climbTarget(maid, key);
    }

    /** 结束爬升相位（没目标了 / 收了工） */
    public static void clearClimb(EntityMaid maid) {
        if (maid != null) {
            CLIMB.remove(maid.getUUID());
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
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 爬升到位（y " + fmt(c.startY) + " → " + fmt(y) + "，想抬 " + fmt(c.lift)
                    + " 格）");
            return null;
        }
        c.observe(y);
        if (c.stalled()) {
            c.done = true;
            // 【日志要能分辨两种"顶头"】真被方块顶住 vs 驱动根本没生效。两者在旧日志里都是
            // 一句"头顶被顶住"，但处置完全不同（前者正常、后者是 bug）。所以这里把起止高度、
            // 实际抬了多少格一起写出来：抬了 0 格 = 驱动没生效；抬了一半 = 真的撞到东西了。
            com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 头顶被顶住 → 就地悬停（y " + fmt(c.startY) + " → " + fmt(y) + "，"
                    + STALL_TICKS + " tick 没长高、整段只抬了 " + fmt(y - c.startY) + " 格）");
            return null;
        }
        return c.targetY;
    }

    /** 一个爬升相位：key = 谁要的（起飞哨兵 / 敌人 UUID），targetY = 想爬到的 Y */
    private static final class Climb {
        final Object key;
        final double targetY;
        /** 相位开始时的 y（日志用：起止一对比就知道是真顶头还是驱动没生效） */
        final double startY;
        /** 想抬的格数（= targetY - startY，含 {@link #CLIMB_LEAD} 余量；日志用） */
        final double lift;
        /** 相位是否已经做完——**留在表里**，否则下一 tick 会被当成新相位重新爬 */
        boolean done;
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
    }

    /** 收工：下扫帚 + 把当初那件扫帚**精确**还回她背包（背包塞不下就落脚下，走 MaidGiveBack） */
    public static void dismount(EntityMaid maid) {
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
     * @return 期望位置（已过 {@link MaidBroomKit#clampToHome} 夹取，见 {@link #steerTo}）
     */
    public static Vec3 combatPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        double r = Math.max(1.0, rangeCfg());
        // 角速度由固定线速度换算（见 ORBIT_SPEED 的注释）：任何半径下她都能跟上这个点
        double ang = ORBIT.getOrDefault(id, 0.0) + ORBIT_SPEED / r;
        ORBIT.put(id, ang);
        return new Vec3(target.getX() + Math.cos(ang) * r,
                target.getY() + hoverCfg(),
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
        return new Vec3(owner.getX() + Math.cos(ang) * FOLLOW_DIST,
                owner.getY() + FOLLOW_HOVER,
                owner.getZ() + Math.sin(ang) * FOLLOW_DIST);
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

    /** 这只女仆下线/卸载：清掉她的盘旋相位、爬升相位与跟随迟滞 */
    public static void forgetMaid(UUID maidId) {
        if (maidId == null) {
            return;
        }
        ORBIT.remove(maidId);
        CLIMB.remove(maidId);
    }

    /** 服务端停止：整表清空 */
    public static void clearAll() {
        THRUST.clear();
        YAW.clear();
        DEBT.clear();
        ORBIT.clear();
        CLIMB.clear();
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

    /** 悬停高度（格，相对目标脚底） */
    private static double hoverCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_HOVER.get();
        } catch (Throwable ignored) {
            return 2.0;
        }
    }
}
