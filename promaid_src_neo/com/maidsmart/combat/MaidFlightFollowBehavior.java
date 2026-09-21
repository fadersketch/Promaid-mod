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
 * v1.2.2 实测六百〇八【飞行跟随】：主人自己飞走了，她也能背上鞘翅追上来——而不是只在
 * 底下垫方块干着急。
 *
 * ── 需求原文 ──
 * "女仆跟随能不能给她整个使用鞘翅一起飞呢，自己飞了后女仆只能搭着方块干着急了，两人一起飞
 * 想想还挺有意思的。"（本条由作者回复确认为**可选玩法**：默认关闭，要玩的人在手册/面板里打开）
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
 *   <li>她包里有**可用鞘翅 + 烟花火箭**（缺一件就不飞——这正是需求里点名的那两件）；</li>
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
 *       离得远/比主人低/速度掉了就补一枚烟花（{@link #BOOST_INTERVAL} = 1.5 秒最短间隔）；</li>
 *   <li><b>追上就收手</b>——贴到 {@link #STOP_DIST} 格：落地的就地结束；还在空中的先抬头
 *       {@link #FLARE_PITCH} 度泄速（flare）{@link #FLARE_TICKS} tick，再交回普通跟随，
 *       剩下的高度靠滑翔自然落下去（滑翔期间原版每 tick 清坠落距离，所以不会摔伤）。</li>
 * </ol>
 *
 * ── 两个"省料"开关（作者要求"可以调整是否消耗烟花和鞘翅耐久"）──
 * <ul>
 *   <li>{@code bridge.flightFollowFirework}（默认**开** = 真消耗）：关掉之后**照旧需要背包里
 *       有烟花**（它是"她能飞"的凭证，也是 {@code isFlightFuel} 的口径），但每次补推不再从
 *       背包扣那一枚——纯观赏档，适合"只想看她跟着飞"的存档。</li>
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
 * 一趟最多 {@link #MAX_TICKS}（60 秒）；超时/燃料没了/鞘翅没了/威胁出现/自保/主人跨维度
 * 一律立刻收手（收手**不清滑翔位**——空中清位就是自由落体，那条老教训见
 * {@code MaidFlightCombatBehavior.endFlightSafely}）。
 *
 * ── 专用服务器上的验收入口 ──
 * 这条链的 target 是**在线主人实体**（{@code maid.getOwner()} 走 PlayerList，专用服务器上
 * 没有玩家就是 null），所以与"女仆喂女仆"（实测五百四十五）同一类困境：结构上无法端到端触发。
 * 照那条的先例留了 {@code /maid_smart flyfollow <实体>} 指定一个"替代主人"
 * （{@link #setDebugTarget}）——走的仍是本类**同一套**判定与飞行链路，只替换目标来源。
 */
public class MaidFlightFollowBehavior extends Behavior<EntityMaid> {

    /** 追上判定（格）：离目标这么近就算追上，交回普通跟随 */
    private static final double STOP_DIST = 4.0;
    /** 滑翔操纵杆的俯仰限幅（度）：抬头 60 / 低头 45（与空袭同档） */
    private static final float PITCH_UP = 60.0f;
    private static final float PITCH_DOWN = 45.0f;
    /** 起跳后至少抬头这么多度：滑翔初速为 0，不先换点高度会一路贴地 */
    private static final float TAKEOFF_PITCH = 20.0f;
    /** 两次补烟花的最短间隔（tick，30 = 1.5 秒；与空袭 fireworkCooldown 默认值同档） */
    private static final int BOOST_INTERVAL = 30;
    /** 进入"最后十几格"就不再多补推的半径（格，见 {@link #shouldBoost} 的超车问题） */
    private static final double NEAR_DIST = 10.0;
    /** 一趟飞行的最长时长（tick，1200 = 60 秒）——到点还没追上就放弃 */
    private static final long MAX_TICKS = 1200L;
    /** 贴到主人身边却还在空中：先抬头泄速这么多 tick，再收手让她自然滑翔落地 */
    private static final int FLARE_TICKS = 60;
    /** flare 时的抬头角度（度） */
    private static final float FLARE_PITCH = 15.0f;
    /** "飞行跟随"日志限频（tick，100 = 5 秒）——补推每次都会发生，不能每 1.5 秒刷一行 */
    private static final long LOG_INTERVAL = 100L;

    /** 正在飞行跟随（滑翔位之外的第二判据——收翅猛击那套让位判据的同类，见类注释） */
    private static final Set<UUID> FOLLOWING = new HashSet<>();
    /** 下次可以补烟花的 gameTime */
    private static final Map<UUID, Long> BOOST_READY = new HashMap<>();
    /** flare 剩余 tick（贴到主人但还在空中） */
    private static final Map<UUID, Integer> FLARE_LEFT = new HashMap<>();
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
    /** 日志限频 */
    private static final Map<UUID, Long> LAST_LOG = new HashMap<>();
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
        FLARE_LEFT.remove(maidId);
        STARTED_AT.remove(maidId);
        SWAPPED_CHEST.remove(maidId);
        SETTLING.remove(maidId);
        LAST_LOG.remove(maidId);
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
        FLARE_LEFT.clear();
        STARTED_AT.clear();
        SWAPPED_CHEST.clear();
        SETTLING.clear();
        LAST_LOG.clear();
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
            if (!MaidFlightKit.hasFirework(maid)) {
                return skip(maid, now, "背包里没有烟花火箭");
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
        FLARE_LEFT.remove(id);
        STARTED_AT.put(id, gameTime);
        wearElytra(maid, id);
        com.maidsmart.tool.PromaidLog.log("飞行跟随",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 主人飞远了（"
                        + (int) Math.sqrt(maid.distanceToSqr(targetOf(maid) == null ? maid.getX()
                        : targetOf(maid).getX(),
                        targetOf(maid) == null ? maid.getY() : targetOf(maid).getY(),
                        targetOf(maid) == null ? maid.getZ() : targetOf(maid).getZ()))
                        + " 格），背上鞘翅追过去（烟花=" + (cfgFirework() ? "消耗" : "不消耗")
                        + "，鞘翅耐久=" + (cfgElytra() ? "照原版扣" : "不消耗") + "）");
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
        // ── 空中：保持滑翔 + 视线钉在主人身上 + 需要时补一枚烟花 ──
        MaidFlightKit.setGliding(maid, true);
        double dist = maid.distanceTo(target);
        if (dist <= STOP_DIST) {
            faceFlare(maid, target); // 贴到了却还在空中：抬头泄速，等滑翔把她放下来
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
        if (!MaidFlightKit.hasElytra(maid) || !MaidFlightKit.hasFirework(maid)) {
            return false; // 鞘翅飞坏了 / 烟花烧完了 → 落地走
        }
        if (threatNearby(level, maid)) {
            return false; // 威胁出现：交回战斗/自保
        }
        double dist = maid.distanceTo(target);
        if (dist > STOP_DIST) {
            FLARE_LEFT.remove(id);
            return true;
        }
        // 贴到主人身边：落地的就地结束；还在空中的给她一段 flare，之后收手自然滑翔落地
        if (maid.onGround()) {
            return false;
        }
        int flare = FLARE_LEFT.getOrDefault(id, FLARE_TICKS) - 1;
        FLARE_LEFT.put(id, flare);
        return flare > 0;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        FOLLOWING.remove(id);
        BOOST_READY.remove(id);
        FLARE_LEFT.remove(id);
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
        com.maidsmart.tool.PromaidLog.log("飞行跟随",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 结束（"
                        + (grounded ? "已落地" : "仍在滑翔下降，落地后自动还回胸甲") + "）");
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

    /** 本 tick 该不该补一枚烟花：比主人低、或者速度掉了（滑翔没速度就等着掉高） */
    private static boolean shouldBoost(EntityMaid maid, LivingEntity target, long gameTime) {
        Long ready = BOOST_READY.get(maid.getUUID());
        if (ready != null && gameTime < ready) {
            return false;
        }
        Vec3 v = maid.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dy = target.getY() - maid.getY();
        // 【进入"最后十几格"就松油门（不含"比她低"的情况）】滑翔是"放出去收不回来"的：离得近
        // 还补推，她必然超车 → 掉头 → 再超车（滑翔掉头半径很大）。本批实测两次吃到：22 格的目标
        // 飞了 43 秒、烧掉 29 枚烟花；加了"近且正在靠近才不补"之后仍有第二趟绕圈（掉头时"正在
        // 靠近"为假 → 又补推 → 又超车）。所以这里的判据干脆只用两条：**近 + 不低于你** → 不补。
        // 低于你（dy > 0.5）时照补——那是爬升，不补就够不着；靠滑翔进场 + 收手前的抬头泄速落地。
        if (maid.distanceTo(target) <= NEAR_DIST && dy <= 0.5) {
            return false;
        }
        return dy > 0.5 || speed < 0.35;
    }

    /** 真放一枚（或不放只摆样子）挂载烟花，并把"她在放烟花"摆到副手上（FlightFireworkPose） */
    private static void boost(ServerLevel level, EntityMaid maid, UUID id, long gameTime) {
        ItemStack display;
        if (cfgFirework()) {
            ItemStack one = MaidFlightKit.takeFirework(maid);
            if (one.isEmpty()) {
                return; // 消耗档：背包真空了（canStillUse 下一 tick 就会收手）
            }
            display = one;
        } else {
            // 不消耗档：**照旧要求背包里有烟花**（它是"能飞"的凭证），但不扣那一枚
            display = new ItemStack(Items.FIREWORK_ROCKET);
        }
        if (!MaidFlightKit.launchBoostRocket(level, maid)) {
            return;
        }
        FlightFireworkPose.show(maid, display);
        BOOST_READY.put(id, gameTime + BOOST_INTERVAL);
        if (gameTime - LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) >= LOG_INTERVAL) {
            LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("飞行跟随",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 补一枚烟花追主人（烟花="
                            + (cfgFirework() ? "消耗" : "不消耗") + "）");
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

    /** 贴到主人但还在空中：抬头泄速（滑翔抬头 = 拿速度换高度、很快慢下来） */
    private static void faceFlare(EntityMaid maid, LivingEntity target) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        applyRotation(maid, yaw, -FLARE_PITCH, target);
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
