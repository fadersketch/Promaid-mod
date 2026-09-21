package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * v1.2.2 实测六百〇八 / 六百一十一【飞行跟随】：主人自己飞走了，她也能背上鞘翅追上来——而不是只在
 * 底下垫方块干着急。
 *
 * ── 需求原文（六百〇八）──
 * "女仆跟随能不能给她整个使用鞘翅一起飞呢，自己飞了后女仆只能搭着方块干着急了，两人一起飞
 * 想想还挺有意思的。"（本条由作者回复确认为**可选玩法**：默认关闭，要玩的人在手册/面板里打开）
 *
 * ── 六百一十一 改的三件事（用户反馈原文）──
 * "飞行跟随的启动只认烟花，而且动作没有换成空袭飞行的动作。而且不需要和主人离那么近，在飞行
 * 跟随期间自身周围 15 格内找到主人那么此链路就中断，不需要紧挨着主人。具体的表现就跟空袭女仆
 * 把怪打死一样。自然滑翔。反正飞出去了会再次启动链路。"
 * <ol>
 *   <li><b>推进剂口径并入羽扇</b>：起飞与补推的燃料从"只认烟花"扩成
 *       {@link MaidFlightKit#hasFlightFuel}（烟花火箭 **或** 孔雀羽扇），与空袭
 *       {@code canLaunch} 同一口径；有扇先挥扇（{@link TwilightFanKit#boostGlide}），
 *       顺序也与空袭的"扇子优先"一致。</li>
 *   <li><b>收手距离 4 格 → 15 格，并且不再"抬头泄速"</b>：主人进到她
 *       {@link #END_RADIUS}（15）格内，本趟链路就中断——**不需要贴到身边**；她若还在空中，
 *       就照原样继续自然滑翔、落地自然收尾，与"空袭女仆把怪打死之后"那一段
 *       （{@code MaidFlightCombatBehavior.endFlightSafely}）完全是同一套。</li>
 *   <li><b>外观与空袭同款</b>：滑翔中的女仆现在也走空袭那三样——模型游泳（展翅）姿态、
 *       鞘翅翅膀图层、跟随俯角的前倾。判据统一取"滑翔位"（同步过的共享标志位 7），
 *       多人下客户端也认得出（与 {@code MaidFlightKit.isGliding} 同源）。</li>
 * </ol>
 *
 * ── 触发位置（为什么卡在"搭路"这一档）──
 * 作者给的口径："开启开关之后，女仆在判定使用搭路时，发现主人离自己太远且自己跟主人之间
 * 没有方块阻拦，自己包里面还有鞘翅和烟花的时候，target=主人，执行飞行（跟空袭模式的起飞
 * 是一样的，但是 target 等于主人）"。所以本行为注册在 core 246——**刚好压过搭路（245）**：
 * 条件满足时她直接起飞，条件不满足时本行为不启动、搭路那条老链路一字不动（搭路自己另加了
 * 一道"飞行跟随进行中就不搭"的让位，见 {@code BridgeUpBehavior.checkExtraStartConditions}）。
 *
 * ── 判定（全部满足才起飞）──
 * <ul>
 *   <li>开关 {@code bridge.flightFollow}（**默认关**）打开；</li>
 *   <li>她不是空袭任务（那两个任务有自己的飞行作战链路，不抢）、不在守家/坐姿/骑乘/睡觉、
 *       不在自保、任务也没有实质占用（{@link com.maidsmart.task.BridgeUpBehavior#isTaskOccupied}，
 *       与搭路同一口径——"另一种空闲"照飞，接战中绝不飞）；</li>
 *   <li>主人（或调试目标，见下）存在、活着、同维度，且**3D 距离超过
 *       {@code bridge.flightFollowDist}（默认 16 格）**——太近就走路/搭路，犯不上烧烟花；</li>
 *   <li>她包里有**可用鞘翅**，以及**能飞的道具**——烟花火箭 **或** 孔雀羽扇
 *       （{@link MaidFlightKit#hasFlightFuel}，缺一件就不飞；六百一十一 起不再只认烟花，
 *       与空袭的燃料口径对齐）；</li>
 *   <li>与主人之间**没有方块阻拦**（raycast 视线，复用自保那套 {@code hasSight}）；</li>
 *   <li>周围 {@code bridge.threatDist} 格内没有敌对生物（与搭路同口径，绝不往怪堆里飞）。</li>
 * </ul>
 *
 * ── 飞起来是什么样（"跟空袭的起飞一样"）──
 * 完全照搬空袭那套起手机制，只是把"敌人"换成主人：
 * <ol>
 *   <li><b>起跳滑翔</b>——先原地跳一下离地（{@code updateFallFlying} 硬要求非落地），
 *       置滑翔位；抬头至少 {@link #TAKEOFF_PITCH} 度先换一点高度；</li>
 *   <li><b>放烟花</b>——挂载型烟花（只写 {@code Flight}、不写爆炸星，所以到期不会炸到她自己，
 *       与空袭 {@code launchFirework} 同一口径）。推力沿她的**视线**方向生效，而她此刻正看着
 *       主人，所以推力方向天然就是"朝主人去"（不需要额外造速度）；</li>
 *   <li><b>持续操纵</b>——每 tick 把视线钉在主人身上（滑翔的操纵杆就是视线），
 *       离得远/比主人低/速度掉了就补一口推进（{@link #BOOST_INTERVAL} = 1.5 秒最短间隔；
 *       有孔雀羽扇先挥扇，没扇才烧烟花——与空袭的推进顺序同款）；</li>
 *   <li><b>飞出去就重启</b>（六百一十一）——她滑过头、或者主人又飞远了，距离重新超过
 *       「飞行跟随距离」时本行为再次启动（再起飞 → 再补推 → 再飞过来），一趟一趟地跟，
 *       不需要玩家干预。**每趟仍有界**：一趟最长 {@link #MAX_TICKS}（60 秒）；超时 / 燃料
 *       没了 / 鞘翅没了 / 威胁出现 / 自保 / 主人跨维度 一律立刻收手（收手**不清滑翔位**——
 *       空中清位就是自由落体，那条老教训见 {@code MaidFlightCombatBehavior.endFlightSafely}）。</li>
 * </ol>
 *
 * ── 收手长什么样（六百一十一 改口径：与"空袭把怪打死之后"完全一致）──
 * 主人进到 {@link #END_RADIUS} 格内 → 本趟中断。她此刻可能还在空中：那就**什么都不做**，
 * 原版滑翔物理会把她自然带下去（滑翔期间每 tick 清坠落距离，所以不会摔伤），落地那一 tick
 * 由 {@link #settleOnGround} 把胸甲还回去。旧版（六百〇八）要贴到 4 格、还要"抬头
 * {@code FLARE_PITCH} 度泄速 {@code FLARE_TICKS} tick"才算收手——用户要的是**自然滑翔**，
 * 那套主动减速整段删掉了。
 *
 * ── 外观：与空袭同款（六百一十一）──
 * 用户原话是"动作没有换成空袭飞行的动作"。空袭那三样外观各有各的判据，原来全都写着
 * {@code MaidFlightKit.isFlightTask(maid)}——而**跟着飞的她并不是飞行任务**，于是三样一样都没跟上：
 * <ul>
 *   <li>{@code MaidSwimGlideMixin}：滑翔时让 {@code isVisuallySwimming} 为真 → TLM 的
 *       {@code AnimationRegister} 播放自带的游泳（展翅）动画。**实测六百一十一 扫过 TLM
 *       1.5.3 的 2344 个类：引用这个方法的只有 3 个（AnimationRegister / SwimAnimation /
 *       EntityMaid 自己的覆写），全是客户端动画**——所以这里放宽成"只要在滑翔就为真"
 *       （正是原版 {@code LivingEntity} 里被 TLM 覆写丢掉的那一支），不会碰到任何玩法判定；</li>
 *   <li>{@code LayerMaidElytra} / {@code LayerMaidElytraGecko}：背上画鞘翅翅膀（并在离地时
 *       强制展翅）；</li>
 *   <li>{@code FlightDiveTilt}：按俯角叠前倾（玩家鞘翅那套 {@code -getXRot()}）。</li>
 * </ul>
 * 判据统一收在 {@code MaidFlightKit.isFlightVisual(maid)} = {@code isFlightTask || isGliding}：
 * 空袭那边一字不变（它本来就滑翔），跟着飞的她从此也认；判据取**同步过的滑翔位**，所以多人下
 * 客户端不需要服务端那张 {@link #FOLLOWING} 表也认得出。
 *
 * ── 两个"省料"开关（作者要求"可以调整是否消耗烟花和鞘翅耐久"）──
 * <ul>
 *   <li>{@code bridge.flightFollowFirework}（默认**开** = 真消耗）：关掉之后**照旧需要包里有
 *       能飞的道具**（烟花火箭或孔雀羽扇，它是"她能飞"的凭证），但每次补推不再从背包扣那一枚
 *       ——纯观赏档，适合"只想看她跟着飞"的存档。**背包里有羽扇时走扇子那条**（不烧烟花，
 *       照羽扇自己的口径扣耐久），这条开关只管烟花那一支。</li>
 *   <li>{@code bridge.flightFollowElytra}（默认**开** = 照原版扣）：关掉之后滑翔不再啃鞘翅耐久，
 *       由 {@link com.maidsmart.mixin.ElytraWearGuardMixin} 在
 *       {@code ElytraItem.elytraFlightTick} 入口拦掉那次 {@code hurtAndBreak}
 *       （**注意**：只认原版 {@code ElytraItem} 及其子类；模组"内置鞘翅的护甲"走它自己的
 *       钩子，这里拦不到——边界写在手册里）。</li>
 * </ul>
 *
 * ── 与其它链路的关系（全部是"让位"，不是"抢") ──
 * <ul>
 *   <li><b>自动传送</b>：飞行期间她的滑翔位为真 → {@code isFlightAirborne} 为真 → TLM 原生
 *       teleportToOwner 与"跨维度跟随"本来就让位；**同维度远距拉回**额外认一条
 *       {@link #isFollowing}——不然她飞到 48 格会被直接传送过去，"一起飞"这件事当场结束；</li>
 *   <li><b>搭路</b>：{@code BridgeUpBehavior.checkExtraStartConditions} 见 {@link #isFollowing}
 *       直接返回 false（她正在飞，脚下没有桥要铺）；她自己的垫脚方块那一套由
 *       {@code MaidPlaceGuard} 的"滑翔中不搭"管住；</li>
 *   <li><b>落地保护</b>：{@code MaidFlightWallGuard} 的撞墙/摔落免疫扩到本状态——她原本不是
 *       飞行任务，那两个开关按 {@code isFlightTask} 判会把跟着飞的她漏掉（20 血撞一次墙就没了）。</li>
 * </ul>
 *
 * ── 有界性 ──
 * 见上一条"飞出去就重启"：每趟最多 {@link #MAX_TICKS}（60 秒），到点/缺料/威胁一律收手，
 * 收手之后能不能再起飞由下一趟判定重新决定（**重启没有次数上限**，但每次都重新过一遍全套判定）。
 *
 * ── 专用服务器上的验收入口 ──
 * 这条链的 target 是**在线主人实体**（{@code maid.getOwner()} 走 PlayerList，专用服务器上
 * 没有玩家就是 null），所以与"女仆喂女仆"（实测五百四十五）同一类困境：结构上无法端到端触发。
 * 照那条的先例留了 {@code /maid_smart flyfollow <实体>} 指定一个"替代主人"
 * （{@link #setDebugTarget}）——走的仍是本类**同一套**判定与飞行链路，只替换目标来源。
 */
public class MaidFlightFollowBehavior extends Behavior<EntityMaid> {

    /**
     * 中断判定（格）：主人进到她这么近的球里，本趟链路就中断、交回普通跟随。
     *
     * v1.2.2 实测六百一十一 由 4 改成 15——用户口径："在飞行跟随期间自身周围 15 格内找到主人
     * 那么此链路就中断，不需要紧挨着主人。"（旧值 4 会让她一路贴到你脸前，还得抬头泄速刹车，
     * 与"自然滑翔"相反。）实际取值见 {@link #endRadius()}：触发距离被调小时它跟着缩，
     * 不会出现"刚起飞就收手"。
     */
    private static final double END_RADIUS = 15.0;
    /** 滑翔操纵杆的俯仰限幅（度）：抬头 60 / 低头 45（与空袭同档） */
    private static final float PITCH_UP = 60.0f;
    private static final float PITCH_DOWN = 45.0f;
    /** 起跳后至少抬头这么多度：滑翔初速为 0，不先换点高度会一路贴地 */
    private static final float TAKEOFF_PITCH = 20.0f;
    /** 两次补推的最短间隔（tick，30 = 1.5 秒；与空袭 fireworkCooldown 默认值同档） */
    private static final int BOOST_INTERVAL = 30;
    /** 一趟飞行的最长时长（tick，1200 = 60 秒）——到点还没追上就放弃 */
    private static final long MAX_TICKS = 1200L;
    /** "补推"日志限频（tick，100 = 5 秒）——补推每次都会发生，不能每 1.5 秒刷一行 */
    private static final long LOG_INTERVAL = 100L;
    /**
     * "起飞/结束"这两行的限频（tick，100 = 5 秒）。
     *
     * 【六百一十一 新增，起因是新的重启口径】收手距离改成 15 格之后，她在"刚过 15 格"与
     * "刚过触发距离"之间来回滑是常态（滑过去 → 中断 → 再滑出去 → 重启），若不加限频，
     * 这两行会跟着来回刷。**与补推那行的限频分开**（各有各的 5 秒预算），否则补推会把
     * 起降两行挤掉——而这两行正是玩家/验收用来判断"她这一趟到底飞没飞"的证据。
     * **起飞与结束之间也各记各的**（{@link #START_LOG} / {@link #END_LOG} 两张表）：共用一张
     * 表时"起飞 1~2 秒后收手"这种短趟会把**结束行整条吞掉**（绿轮实测踩到）。
     */
    private static final long STATE_LOG_INTERVAL = 100L;

    /** 正在飞行跟随（滑翔位之外的第二判据——收翅猛击那套让位判据的同类，见类注释） */
    private static final Set<UUID> FOLLOWING = new HashSet<>();
    /** 下次可以补推的 gameTime */
    private static final Map<UUID, Long> BOOST_READY = new HashMap<>();
    /** 本趟起飞时刻（超时用） */
    private static final Map<UUID, Long> STARTED_AT = new HashMap<>();
    /** 我们替她换上的鞘翅：原胸甲物品（收手时原样还回去；她自己本来就穿着鞘翅时不记） */
    private static final Map<UUID, ItemStack> SWAPPED_CHEST = new HashMap<>();
    /**
     * "已收手但还在滑翔下降"的小名单：收手那一刻她若还在空中，胸甲上那件鞘翅必须继续戴着
     * （空中摘 = 自由落体，见 {@link #stop} 注释），于是有一段"行为已停、人还在鞘翅上"的
     * 窗口。落地那一 tick 由 {@link #settleOnGround} 收尾。撞墙免疫与"免耐久"都要认这条。
     */
    private static final Set<UUID> SETTLING = new HashSet<>();
    /** 补推日志限频 */
    private static final Map<UUID, Long> LAST_LOG = new HashMap<>();
    /**
     * "起飞 / 结束"两行的限频（**各记各的**，见 {@link #STATE_LOG_INTERVAL}）。
     *
     * 【为什么要分成两张表：绿轮实测踩到的】一开始两行共用一张表，于是"起飞 → 1~2 秒后收手"
     * 这种**又快又短的一趟**里，结束那行被起飞那行的 5 秒预算吃掉——运行日志里只剩「起飞、
     * 补推、已落地」，**偏偏少掉验收要读的「结束（主人 N 格…）」**（实测六百一十一 绿轮第一趟
     * 就是这样：起飞 22.0 格 → 1 秒后收手，结束行一个字都没落盘）。分开之后每类各自 5 秒最多
     * 一行，最坏情况 2 行/5 秒/只，仍然不刷屏，但**任何一趟的起降都能各留一行**。
     */
    private static final Map<UUID, Long> START_LOG = new HashMap<>();
    private static final Map<UUID, Long> END_LOG = new HashMap<>();
    /** "为什么不起飞"诊断日志限频（tick，200 = 10 秒/只女仆；只在开关开着时才可能记） */
    private static final long SKIP_LOG_INTERVAL = 200L;
    private static final Map<UUID, Long> SKIP_LOG = new HashMap<>();
    /** checkExtraStartConditions 的昂贵判定（背包扫描 + 视线 raycast + 威胁扫描）10 tick 节流，与搭路同款 */
    private static final Map<Integer, Integer> CANUSE_THROTTLE = new HashMap<>();

    /**
     * 调试/验收用的"替代主人"：专用服务器上没有在线玩家，{@code getOwner()} 恒为 null，
     * 这条链就永远触发不了（与实测五百四十五 的"女仆喂女仆"同一类困境）。
     * {@code /maid_smart flyfollow &lt;实体&gt;} 在这里挂一个实体当跟随目标，走的是本类
     * **同一套**判定与飞行链路，只替换目标来源。键用弱引用（女仆卸载即回收）。
     */
    private static final Map<EntityMaid, LivingEntity> DEBUG_TARGET =
            Collections.synchronizedMap(new WeakHashMap<>());

    public MaidFlightFollowBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 开关 ==================== */

    private static boolean cfg() {
        return MaidSmartConfig.BRIDGE_FLIGHT_FOLLOW.get();
    }

    private static double cfgDist() {
        return MaidSmartConfig.BRIDGE_FLIGHT_FOLLOW_DIST.get();
    }

    private static boolean cfgFirework() {
        return MaidSmartConfig.BRIDGE_FLIGHT_FOLLOW_FIREWORK.get();
    }

    private static boolean cfgElytra() {
        return MaidSmartConfig.BRIDGE_FLIGHT_FOLLOW_ELYTRA.get();
    }

    /* ==================== 对外只读/清理 ==================== */

    /** 她此刻正在"飞行跟随"（同维度拉回让位、搭路让位、落地保护都认这一条） */
    public static boolean isFollowing(EntityMaid maid) {
        try {
            return maid != null && FOLLOWING.contains(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她已经收手、但还在滑翔下降（胸甲上那件鞘翅还没还回去）。
     *
     * 【为什么单独一个判据】收手那一刻如果她还在空中，鞘翅必须继续戴着（空中摘 = 自由落体，
     * 见 {@link #stop} 注释），于是存在一小段"行为已停、人还在鞘翅上"的窗口——撞墙免疫
     * 若不认这一条就会在这段窗口里漏掉她。
     */
    public static boolean isSettling(EntityMaid maid) {
        try {
            return maid != null && SETTLING.contains(maid.getUUID());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 滑翔扣鞘翅耐久这一下要不要免掉（{@link com.maidsmart.mixin.ElytraWearGuardMixin} 用）。
     *
     * 只看两件事：她是女仆、她正在飞行跟随、且"不消耗鞘翅耐久"开关关着（= 玩家要省料）。
     * 任何异常一律 false（不免）——最坏情况是照原版扣耐久，绝不会误伤别人。
     */
    public static boolean shouldSkipElytraWear(Object entity) {
        try {
            if (!(entity instanceof EntityMaid maid)) {
                return false;
            }
            return !cfgElytra() && (isFollowing(maid) || isSettling(maid));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 挂一个"替代主人"（调试/验收入口，见 DEBUG_TARGET 注释）；null = 摘掉 */
    public static void setDebugTarget(EntityMaid maid, LivingEntity target) {
        if (maid == null) {
            return;
        }
        try {
            if (target == null) {
                DEBUG_TARGET.remove(maid);
            } else {
                DEBUG_TARGET.put(maid, target);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 摘掉全部"替代主人"（{@code /maid_smart flyfollow clear} 用；这张表本来就很小） */
    public static void clearDebugTargets() {
        try {
            DEBUG_TARGET.clear();
        } catch (Throwable ignored) {
        }
    }

    /** 女仆卸载/死亡/服务器停止：清这个 UUID 的全部状态（收手时的归还由实体侧自己走） */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        FOLLOWING.remove(maidId);
        BOOST_READY.remove(maidId);
        STARTED_AT.remove(maidId);
        SWAPPED_CHEST.remove(maidId);
        SETTLING.remove(maidId);
        LAST_LOG.remove(maidId);
        START_LOG.remove(maidId);
        END_LOG.remove(maidId);
        SKIP_LOG.remove(maidId);
        CANUSE_THROTTLE.remove(maidId);
        try {
            DEBUG_TARGET.keySet().removeIf(m -> maidId.equals(m.getUUID()));
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        FOLLOWING.clear();
        BOOST_READY.clear();
        STARTED_AT.clear();
        SWAPPED_CHEST.clear();
        SETTLING.clear();
        LAST_LOG.clear();
        START_LOG.clear();
        END_LOG.clear();
        SKIP_LOG.clear();
        CANUSE_THROTTLE.clear();
        DEBUG_TARGET.clear();
    }

    /* ==================== 行为本体 ==================== */

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // 【落地结算】胸甲的归还只允许在"她已经站到地上"时发生（见 settleOnGround 的注释：
        // 空中一摘鞘翅 = 原版下一 tick 就清滑翔位 = 自由落体）。绝大多数 tick 这里零开销。
        settleOnGround(maid);
        if (!cfg()) {
            return false; // 开关关着：完全不介入（也不记日志，默认档零噪音）
        }
        long now = level.getGameTime(); // 诊断日志限频用
        try {
            if (maid.isSleeping() || maid.isPassenger() || maid.isMaidInSittingPose()) {
                return skip(maid, now, "睡觉/骑乘/坐下中"); // 与搭路同口径
            }
            if (maid.isHomeModeEnable()) {
                return skip(maid, now, "守家（home）模式中");
            }
            if (MaidFlightKit.isFlightTask(maid)) {
                return skip(maid, now, "空袭任务（它自己有飞行作战链路）");
            }
            if (SelfPreservationBehavior.isSelfPreserving(maid)) {
                return skip(maid, now, "自保中");
            }
            if (com.maidsmart.task.BridgeUpBehavior.isTaskOccupied(maid)) {
                return skip(maid, now, "任务占用中（在干活/正在接战）");
            }
            LivingEntity target = targetOf(maid);
            if (target == null || !target.isAlive() || target.level() != level) {
                return skip(maid, now, "没有可追的目标（主人离线/跨维度，或调试目标没挂上）");
            }
            double dSq = maid.distanceToSqr(target.getX(), target.getY(), target.getZ());
            if (dSq <= cfgDist() * cfgDist()) {
                return skip(maid, now, String.format("距离不够（%.1f 格 ≤ 阈值 %.1f）",
                        Math.sqrt(dSq), cfgDist())); // 还不够远：走路/搭路足够
            }
            // 昂贵的判定（背包扫描 + 视线 raycast + 威胁扫描）10 tick 一次，与搭路同款节流
            int eid = maid.getId();
            Integer cd = CANUSE_THROTTLE.get(eid);
            if (cd != null && cd > 0) {
                CANUSE_THROTTLE.put(eid, cd - 1);
                return false; // 节流中，不算失败（不记日志）
            }
            CANUSE_THROTTLE.put(eid, 10);
            if (!MaidFlightKit.hasElytra(maid)) {
                return skip(maid, now, "背包里没有可用鞘翅");
            }
            if (!MaidFlightKit.hasFlightFuel(maid)) {
                // 六百一十一：燃料口径与空袭一致——烟花火箭 **或** 孔雀羽扇任一即可
                return skip(maid, now, "背包里既没有烟花火箭、也没有孔雀羽扇（能飞的道具）");
            }
            if (!SelfPreservationBehavior.hasSight(maid, target)) {
                return skip(maid, now, "与目标之间被方块挡住视线"); // 让她自己绕（搭路/走路）
            }
            if (threatNearby(level, maid)) {
                return skip(maid, now, "附近有敌对生物（威胁半径内）");
            }
            return true;
        } catch (Throwable t) {
            return skip(maid, now, "判定抛异常：" + t);
        }
    }

    /**
     * 不起飞时记一条"为什么"（限频 {@link #SKIP_LOG_INTERVAL} tick = 10 秒/只女仆）。
     *
     * 【为什么值得常驻】开关一开，玩家看到的现象就是"她怎么不飞"——没有这一行，判定链上十来个
     * 条件里到底是哪一个没过，玩家与作者都只能猜（这跟"搭路跳过/轰炸跳过"是同一类可核验性问题）。
     * 【为什么不会吵】只在 {@code bridge.flightFollow} 开着时才可能记（默认关 = 一行都没有），
     * 且每只女仆 10 秒最多一条。运行日志搜「飞行跟随跳过」即可。
     */
    private static boolean skip(EntityMaid maid, long now, String why) {
        try {
            UUID id = maid.getUUID();
            Long last = SKIP_LOG.get(id);
            if (last == null || now - last >= SKIP_LOG_INTERVAL) {
                SKIP_LOG.put(id, now);
                com.maidsmart.tool.PromaidLog.log("飞行跟随跳过",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 不起飞：" + why);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        FOLLOWING.add(id);
        BOOST_READY.remove(id);
        STARTED_AT.put(id, gameTime);
        wearElytra(maid, id);
        logState(START_LOG, maid, id, gameTime, "主人飞远了（" + fmtDist(maid, targetOf(maid))
                + " 格），背上鞘翅追过去（燃料=" + fuelLabel(maid)
                + "，烟花=" + (cfgFirework() ? "消耗" : "不消耗")
                + "，鞘翅耐久=" + (cfgElytra() ? "照原版扣" : "不消耗") + "）");
    }

    /**
     * 这一趟按什么飞（六百一十一 起燃料有两种，日志里写清楚是哪一种）。
     *
     * 【为什么值得单独一行日志】"她说自己没烟花却照样飞了"这类疑问，读一行就知道走的是羽扇那条；
     * 与空袭 {@code tryLaunch} 的"扇子优先"同序（那边也有「挥羽扇起飞」/「放烟花起飞」两行）。
     */
    private static String fuelLabel(EntityMaid maid) {
        try {
            return TwilightFanKit.hasFan(maid) ? "孔雀羽扇（优先）" : "烟花火箭";
        } catch (Throwable ignored) {
            return "烟花火箭";
        }
    }

    /** 她与目标的距离（格），给日志用；目标没了就返回 `?`（不抛异常） */
    private static String fmtDist(EntityMaid maid, LivingEntity target) {
        try {
            if (target == null) {
                return "?";
            }
            return String.format("%.1f", maid.distanceTo(target));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        LivingEntity target = targetOf(maid);
        if (target == null) {
            return; // canStillUse 会收手
        }
        // ── 起跳滑翔（与空袭的 jumpForLaunch 同一套：先离地，滑翔位才站得住）──
        if (maid.onGround()) {
            faceToward(maid, target, true);
            Vec3 dm = maid.getDeltaMovement();
            maid.setDeltaMovement(new Vec3(dm.x, 0.42, dm.z));
            MaidFlightKit.setGliding(maid, true);
            return;
        }
        // ── 空中：保持滑翔 + 视线钉在主人身上 + 需要时补一口推进 ──
        MaidFlightKit.setGliding(maid, true);
        if (maid.distanceTo(target) <= endRadius()) {
            // 主人已经进到 15 格内：本趟到此为止（canStillUse 下一 tick 收手）。
            // 【这里故意什么都不做】不再摆朝向 = 她保持当前的滑翔方向自然滑过去，
            // 与"空袭女仆把怪打死之后"那一段一样（用户要的"自然滑翔"）。
            return;
        }
        faceToward(maid, target, false);
        if (shouldBoost(maid, target, gameTime)) {
            boost(level, maid, id, gameTime);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        if (!cfg()) {
            return false;
        }
        LivingEntity target = targetOf(maid);
        if (target == null || !target.isAlive() || target.level() != level) {
            return false; // 目标没了/跨维度 → 追不上也不该追
        }
        if (maid.isSleeping() || maid.isPassenger() || maid.isMaidInSittingPose() || maid.isHomeModeEnable()) {
            return false;
        }
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData()
                .getBoolean(SelfPreservationBehavior.PRESERVE_TAG)) {
            return false;
        }
        if (gameTime - STARTED_AT.getOrDefault(id, gameTime) > MAX_TICKS) {
            return false; // 超时收手
        }
        if (!MaidFlightKit.hasElytra(maid) || !MaidFlightKit.hasFlightFuel(maid)) {
            return false; // 鞘翅飞坏了 / 能飞的道具没了（烟花烧完且没羽扇）→ 落地走
        }
        if (threatNearby(level, maid)) {
            return false; // 威胁出现：交回战斗/自保
        }
        // 主人已经进到 15 格内 → 本趟中断（**不需要贴到身边**，用户口径）。
        // 空中/地面一视同仁：落地的就地结束，还在空中的交给自然滑翔（收手不清滑翔位）。
        return maid.distanceTo(target) > endRadius();
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        FOLLOWING.remove(id);
        BOOST_READY.remove(id);
        STARTED_AT.remove(id);
        LAST_LOG.remove(id);
        // 【绝不在空中摘鞘翅——它和"空中清滑翔位"是同一件事】原版 updateFallFlying 每 tick 都要
        // 看胸甲槽里那件鞘翅能不能飞（{@code ItemStack.canElytraFly} + {@code elytraFlightTick}）：
        // 空中把鞘翅摘走，下一 tick 滑翔位就被清掉 = 自由落体（女仆 20 血，实测四百七十二 的教训）。
        // 所以只在【已经落地】时收翅 + 还胸甲；还在空中就把两样都留着，让原版继续把她安全滑下去，
        // 落地那一 tick 由 checkExtraStartConditions 开头的 settleOnGround 收尾。
        boolean grounded = maid.onGround();
        if (grounded) {
            MaidFlightKit.setGliding(maid, false);
            restoreChest(maid, id);
        } else {
            SETTLING.add(id); // 交给 settleOnGround 在她落地那一 tick 收尾
        }
        logState(END_LOG, maid, id, gameTime, "结束（主人 " + fmtDist(maid, targetOf(maid)) + " 格，"
                + (grounded ? "她已落地，胸甲还回去" : "她还在滑翔，落地后自动还回胸甲") + "）");
    }

    /**
     * 收手时她还在空中 → 那件鞘翅先别摘，等她落地再还（{@link #stop} 的注释里讲了为什么）。
     * 挂在 checkExtraStartConditions 最开头每 tick 探一次：无记录时只做一次 set 查找，零开销。
     */
    private static void settleOnGround(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            if (!SETTLING.contains(id)) {
                return; // 绝大多数 tick 走这里（她没在收手后继续滑翔）
            }
            if (!maid.onGround() || FOLLOWING.contains(id)) {
                return; // 还在空中，或者她这次又飞起来了（飞行中由行为自己管）
            }
            SETTLING.remove(id);
            restoreChest(maid, id);
            MaidFlightKit.setGliding(maid, false);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 已落地，把胸甲还回去");
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 起飞/操纵 ==================== */

    /**
     * 本趟"够了"的半径（格）：主人进到这么近就中断本趟链路。
     *
     * 默认口径 = {@link #END_RADIUS}（15 格，用户原话），但**触发距离被玩家调小时它得跟着缩**
     * ——否则"飞行跟随距离 = 8"的存档会变成"一起飞就已经在 15 格内"= 起飞即刻收手，一次都飞不起来。
     * 所以取 {@code min(15, 触发距离 - 1)}：默认 16 → 15；调到 8 → 7（中间留 1 格滞回，
     * 免得她在边界上每 tick 起降一次）。
     */
    private static double endRadius() {
        return Math.max(1.0, Math.min(END_RADIUS, cfgDist() - 1.0));
    }

    /** 本 tick 该不该补一口推进：比主人低、或者速度掉了（滑翔没速度就等着掉高） */
    private static boolean shouldBoost(EntityMaid maid, LivingEntity target, long gameTime) {
        Long ready = BOOST_READY.get(maid.getUUID());
        if (ready != null && gameTime < ready) {
            return false;
        }
        Vec3 v = maid.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dy = target.getY() - maid.getY();
        // 【六百一十一：删掉了"最后十几格就松油门"那一条】旧版要靠它压住"离得近还补推 → 超车 →
        // 掉头 → 再超车"的绕圈（六百〇八 实测吃过两次：22 格的目标飞了 43 秒、烧掉 29 枚烟花）。
        // 现在收手半径从 4 格提到 15 格，**这一趟根本进不到"最后十几格"**（进 15 格就中断了），
        // 那条判据成了死代码，索性去掉；剩下的两条就是"该补才补"：低于主人（爬升）或速度掉了。
        return dy > 0.5 || speed < 0.35;
    }

    /**
     * 补一口推进：**有孔雀羽扇先挥扇**（与空袭 {@code tryLaunch} 同序），没扇才烧烟花。
     *
     * 【为什么扇子优先】空袭从实测五百六十三 起就是"有扇用扇"（扇子按它自己的公式推进、
     * 扣它自己的耐久）。飞行跟随是同一件事（都只是"给滑翔补一口推力"），口径必须同源，
     * 否则同一个背包在两套模式里会烧不同的东西。
     */
    private static void boost(ServerLevel level, EntityMaid maid, UUID id, long gameTime) {
        if (TwilightFanKit.hasFan(maid)) {
            if (!TwilightFanKit.boostGlide(level, maid)) {
                return; // 扇子挥不动（异常/扇子没了）——这一 tick 就算了
            }
            // 必须置位：滑翔每 tick 吃朝向，扇子那一口速度也只在滑翔状态下站得住（同空袭）
            MaidFlightKit.setGliding(maid, true);
            BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
            logThrottled(maid, id, gameTime, "挥羽扇追主人");
            return;
        }
        ItemStack display;
        if (cfgFirework()) {
            ItemStack one = MaidFlightKit.takeFirework(maid);
            if (one.isEmpty()) {
                return; // 消耗档：背包真空了（canStillUse 下一 tick 就会收手）
            }
            display = one;
        } else {
            // 不消耗档：**照旧要求背包里有能飞的道具**（它是"能飞"的凭证），但不扣那一枚
            display = new ItemStack(Items.FIREWORK_ROCKET);
        }
        if (!MaidFlightKit.launchBoostRocket(level, maid)) {
            return;
        }
        FlightFireworkPose.show(maid, display);
        BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
        logThrottled(maid, id, gameTime, "补一枚烟花追主人（烟花="
                + (cfgFirework() ? "消耗" : "不消耗") + "）");
    }

    /** 补推日志：同一只女仆 {@link #LOG_INTERVAL}（5 秒）最多一行 */
    private static void logThrottled(EntityMaid maid, UUID id, long gameTime, String what) {
        try {
            if (gameTime - LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) < LOG_INTERVAL) {
                return;
            }
            LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + what);
        } catch (Throwable ignored) {
        }
    }

    /**
     * "起飞/结束"两行：**每类各自** {@link #STATE_LOG_INTERVAL}（5 秒）最多一行。
     *
     * 【六百一十一 新增，起因是新的重启口径】收手半径提到 15 格之后，她会在"滑出去 → 重启 →
     * 滑回来 → 中断"之间反复，这两行要限频；但又不能跟补推那行共用一个预算（补推会把起降挤掉，
     * 而那两行才是"她这一趟到底飞没飞"的证据）。
     * 【两行也不能共用一张表】详见 {@link #START_LOG} 的注释：共用时"起飞 1 秒后收手"这种短趟
     * 会把结束行整条吞掉（绿轮实测）。
     */
    private static void logState(Map<UUID, Long> table, EntityMaid maid, UUID id, long gameTime,
                                 String what) {
        try {
            if (gameTime - table.getOrDefault(id, Long.MIN_VALUE / 2) < STATE_LOG_INTERVAL) {
                return;
            }
            table.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + what);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 朝向 ==================== */

    /** 把视线钉在目标上（滑翔的操纵杆就是视线）；takeoff=true 时至少抬头 TAKEOFF_PITCH 度 */
    private static void faceToward(EntityMaid maid, LivingEntity target, boolean takeoff) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double eyeT = target.getY() + target.getBbHeight() * 0.5;
        double eyeM = maid.getY() + maid.getBbHeight() * 0.5;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Math.atan2(eyeT - eyeM, Math.max(1.0E-4, dh)) * (180.0 / Math.PI)));
        pitch = Math.max(-PITCH_UP, Math.min(PITCH_DOWN, pitch));
        if (takeoff) {
            pitch = Math.min(pitch, -TAKEOFF_PITCH); // 起跳那一下先抬头（负 = 抬头）
        }
        applyRotation(maid, yaw, pitch, target);
    }

    /**
     * 立即把身体与头转向指定角度，并把"期望角度"交给 LookControl。
     *
     * 【为什么不能只调 lookAt】LookControl 每 tick 会把 xRot 归零（1.20.1 与 1.21.1 都一样，
     * 反编译实证见 {@code MaidFlightCombatBehavior.faceAwayAndUp}），只写期望值下一 tick 就被
     * 抹平 —— 所以既要直接写实体（含 O 值，渲染插值用），也要交给 LookControl 让它每 tick 重施加。
     */
    private static void applyRotation(EntityMaid maid, float yaw, float pitch, LivingEntity target) {
        maid.setYRot(yaw);
        maid.setXRot(pitch);
        maid.yRotO = yaw;
        maid.xRotO = pitch;
        maid.setYHeadRot(yaw);
        maid.setYBodyRot(yaw);
        try {
            maid.getLookControl().setLookAt(target, 360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 装备/归还 ==================== */

    /**
     * 背上鞘翅（她自己本来就穿着可用鞘翅时一字不动）。
     *
     * 【为什么要"记原物、收手还原"】飞行跟随是**跟着走路的女仆**的观赏玩法——她本来可能穿着
     * 普通胸甲（甚至钻石胸甲），我们不能飞完就把玩家的护甲弄丢/长期占着胸甲槽。所以换下的那件
     * 记在 {@link #SWAPPED_CHEST}，收手时原样还回去（鞘翅回背包）。
     */
    private static void wearElytra(EntityMaid maid, UUID id) {
        try {
            if (MaidFlightKit.isElytraLike(maid.getItemBySlot(EquipmentSlot.CHEST), maid)) {
                return; // 已经穿着
            }
            ItemStack ely = MaidFlightKit.takeElytra(maid);
            if (ely.isEmpty()) {
                return;
            }
            ItemStack old = maid.getItemBySlot(EquipmentSlot.CHEST);
            maid.setItemSlot(EquipmentSlot.CHEST, ely);
            SWAPPED_CHEST.put(id, old.isEmpty() ? ItemStack.EMPTY : old.copy());
            if (!old.isEmpty()) {
                com.maidsmart.tool.MaidGiveBack.give(maid, old, "飞行跟随换下胸甲");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 收手归还：只有"胸甲槽还保持着我们给她穿的鞘翅"才动它——玩家中途换过的东西一律不覆盖 */
    private static void restoreChest(EntityMaid maid, UUID id) {
        try {
            ItemStack orig = SWAPPED_CHEST.remove(id);
            if (orig == null) {
                return; // 她本来就穿着鞘翅（我们没换过）
            }
            ItemStack now = maid.getItemBySlot(EquipmentSlot.CHEST);
            if (!MaidFlightKit.isElytraLike(now, maid)) {
                if (!orig.isEmpty()) {
                    com.maidsmart.tool.MaidGiveBack.give(maid, orig, "飞行跟随结束还回胸甲");
                }
                return;
            }
            maid.setItemSlot(EquipmentSlot.CHEST, orig);
            if (!now.isEmpty()) {
                com.maidsmart.tool.MaidGiveBack.give(maid, now, "飞行跟随结束，还回鞘翅");
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 目标/环境 ==================== */

    /** 本 tick 要追的目标：调试目标优先（专用服务器验收用），否则就是主人 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            LivingEntity dbg = DEBUG_TARGET.get(maid);
            if (dbg != null && dbg.isAlive()) {
                return dbg;
            }
            return maid.getOwner();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 周围 bridge.threatDist 格内有敌对生物（与搭路同口径：绝不往怪堆里飞） */
    private static boolean threatNearby(ServerLevel level, EntityMaid maid) {
        double r = MaidSmartConfig.BRIDGE_THREAT_DIST.get();
        for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, maid.getBoundingBox().inflate(r), x -> true)) {
            if (e.isAlive() && e instanceof Enemy) {
                return true;
            }
        }
        return false;
    }
}
