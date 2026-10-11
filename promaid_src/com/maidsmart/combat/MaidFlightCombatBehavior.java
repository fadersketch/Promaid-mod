package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 飞行作战行为（空袭）：放烟花爬升 → 朝敌滑翔俯冲 → 收翅自由落体近战猛击 → 再滑翔，循环到敌人
 * 死或模式退出；烟花用完 / 鞘翅损坏（三件不齐）→ 退回地面近战。
 *
 * 开头必须先"起跳滑翔"：挂载型烟花**只对正在滑翔的实体**给推力（{@code FireworkRocketEntity} 仅在
 * {@code isFallFlying()} 时沿视线加速），而 {@code LivingEntity.updateFallFlying} 每 tick 重算滑翔位
 * ——站在地上置位必被清掉，所以先跳离地、下一 tick 再放烟花才吃得到推力。
 * 收翅就能吃暴击：原版暴击要 {@code fallDistance>0 && !onGround && !climbing && !inWater &&
 * !blindness && !passenger && !sprinting}，而滑翔把 {@code fallDistance} 钳在 ≤1.0，收翅后再自由
 * 落体 2~3 tick 即满足。
 */
public class MaidFlightCombatBehavior extends Behavior<EntityMaid> {

    /** 阶段一：放烟花后维持"背离+向上"朝向的时长——**近战**用（1.5 秒）。
     *  v1.2.0 实测四百七十二【回退】：近战必须爬够高度才能维持滑翔（贴地就掉），
     *  故与本值配套的仰角一起回退到远战调参之前的口径。 */
    // ---- v1.2.2 实测五百八十一【空袭数值面板】：下面这些 xxxYyy() 访问器取代了原来的
    //      硬编码常量（经 MaidSmartConfig.AIR_RAID_* 读配置），默认值与原常量一字未改；
    //      每一项的面板位置都是「战斗与自保 → 空袭数值」。需求原文：「关于空袭等
    //      各项数值也要有一个详细的配置面板，在模组详细配置。」 ----
    private static int launchTicksMelee() {
        return MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_MELEE.get();
    }
    /** 阶段一：放烟花后维持"背离+向上"朝向的时长——**远战**用（1 秒）。
     *  v1.2.0 实测四百六十九：远战 1.5s → 1s，提前转入朝敌盘旋，少爬高
     *  （远战只求悬停高度、不吃俯冲，爬太高反而够不到地面敌人）。 */
    private static int launchTicksRanged() {
        return MaidSmartConfig.AIR_RAID_LAUNCH_TICKS_RANGED.get();
    }
    /** 烟花最小间隔（tick）——1.5 秒 */
    private static int fireworkCooldown() {
        return MaidSmartConfig.AIR_RAID_FIREWORK_COOLDOWN.get();
    }
    /** 实测五百六十三：羽扇最小间隔（tick）——原版 getUseDuration=20，每秒最多挥一次 */
    private static int fanCooldown() {
        return MaidSmartConfig.AIR_RAID_FAN_COOLDOWN.get();
    }
    /** 起飞仰角正切——**近战**用：1.88 ≈ 62°（回退到远战调参之前的原值）。
     *  近战链路是"起飞→扑击→收翅猛击→再起飞"的循环，每次重新起飞都要先把
     *  高度拉起来；仰角过低时她一放烟花就往目标方向压头，几 tick 内就贴地，
     *  滑翔位被 `updateFallFlying` 清掉 → 直接变自由落体摔死（实测四百七十二
     *  的 fall 21~23 全部由此而来）。 */
    private static double launchClimbTanMelee() {
        return MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_MELEE.get();
    }
    /** 起飞仰角正切——**远战**用：1.0 = 45°（实测四百六十九"飞太高够不到地面敌人"）。 */
    private static double launchClimbTanRanged() {
        return MaidSmartConfig.AIR_RAID_LAUNCH_CLIMB_TAN_RANGED.get();
    }
    /** 地面重新起飞的最大距离（格）：太远先跑过去，避免"越炸越远"
     *  v1.2.0 实测五百二十五：这个门槛只对**水平**距离生效（见 tick 里 distH 的注释）——
     *  3D 距离会让"比她高 50 格的敌人"永远进不了起飞分支。 */
    private static double launchRange() {
        return MaidSmartConfig.AIR_RAID_LAUNCH_RANGE.get();
    }
    /**
     * 高度容差（格）：她与目标高度差在此以内即视为**已占位**（落回原链路近战俯冲 / 远战盘旋）。
     * 判据 {@code maid.getY() >= target.getY() - altitudeTolerance()}；取 10 = "除非高出 10 格以上，
     * 否则用原链路应付"。这个常量同时是起飞朝向判据（见 {@link #faceLaunchDirection}），所以 10 格
     * 以内连起飞也走老口径"背离 + 向上"。够高后本相位仍要把当前这枚烟花的推力吃完才交棒（见
     * {@link #tickClimbToAltitude}），故调此值只改进入持推段的早晚，不省烟花。
     */
    private static double altitudeTolerance() {
        return MaidSmartConfig.AIR_RAID_ALTITUDE_TOLERANCE.get();
    }
    /** 起跳滑翔的等待上限（tick）：起跳失败（低矮空间）就放弃本轮 */
    private static int jumpTicks() {
        return MaidSmartConfig.AIR_RAID_JUMP_TICKS.get();
    }

    /** 取消滑翔的触发距离（格）：进入即收翅自由落体 */
    private static double smashRange() {
        return MaidSmartConfig.AIR_RAID_SMASH_RANGE.get();
    }
    /** 猛击命中判定距离（格） */
    private static double smashHitRange() {
        return MaidSmartConfig.AIR_RAID_SMASH_HIT_RANGE.get();
    }
    /** v1.2.0：范围强制命中半径（格）——见 smashHit */
    private static double forcedHitRadius() {
        return MaidSmartConfig.AIR_RAID_FORCED_HIT_RADIUS.get();
    }
    /** 猛击下落加成门槛（格）：滑翔中 fallDistance 被钳在 1.0，收翅后补几 tick 即可 */
    private static final float SMASH_MIN_FALL = 1.5f;
    /** 猛击段最长 tick：超时按打空收尾（不摔伤、切回滑翔） */
    private static int smashMaxTicks() {
        return MaidSmartConfig.AIR_RAID_SMASH_MAX_TICKS.get();
    }
    /** 地面近战触及距离（格）——v1.2.2 实测五百八十一改为配置项 */
    private static double meleeReach() {
        return MaidSmartConfig.AIR_RAID_MELEE_REACH.get();
    }
    /** 阶段二俯仰限幅（度） */
    private static float maxPitchUp() {
        return MaidSmartConfig.AIR_RAID_MAX_PITCH_UP.get().floatValue();
    }
    private static float maxPitchDown() {
        return MaidSmartConfig.AIR_RAID_MAX_PITCH_DOWN.get().floatValue();
    }

    private static final Map<UUID, Integer> LAUNCH_LEFT = new HashMap<>();
    private static final Map<UUID, Integer> JUMP_LEFT = new HashMap<>();
    /** 【实测七百〇七】"起飞受阻"（起跳窗走完仍未离地）日志限频：每只女仆 5 秒一条 */
    private static final Map<UUID, Long> LAUNCH_STUCK_LOG = new HashMap<>();
    private static final long LAUNCH_STUCK_LOG_INTERVAL = 100L;
    private static final Set<UUID> SMASH = new HashSet<>();
    private static final Map<UUID, Integer> SMASH_TICKS = new HashMap<>();
    private static final Set<UUID> WAIT_LAUNCH = new HashSet<>();
    /** v1.2.0 实测五百二十八：垂直占位相位正在跑（正在爬 / 刚爬够高度但烟花推力还没烧完）。
     *  进相位的两条判据之一是它 + `LAUNCH_LEFT>0`，见 {@link #tickClimbToAltitude}。 */
    private static final Set<UUID> CLIMB_BOOST = new HashSet<>();
    private static final Map<UUID, Long> FIREWORK_READY = new HashMap<>();
    /** v1.2.0：攻击冷却到期 gameTime——强制受击的间隔 = 女仆自己的攻击频率，不是无条件触发 */
    private static final Map<UUID, Long> ATTACK_READY = new HashMap<>();

    /**
     * 实测五百六十七（PR #8 移植）：下一次可以在空袭中发起施法的 gameTime。
     *
     * 法术模组（Touhou Little Maid: Spell）自己管"吟唱多久、冷却多久、放哪个法术"，
     * 这张表只管**我们这边的发起节奏**——不设间隔的话，飞在目标上方的那几个 tick 里
     * 会每 tick 喊一次 castSpell（它内部虽然有 isCasting 去重，但连续秒放会让"法术"
     * 彻底盖过"武器"，与需求（用武器打的同时顺带放法术）不符）。
     */
    private static final Map<UUID, Long> SPELL_NEXT_CAST = new HashMap<>();

    /** 施法日志限频（每只女仆 30 秒至多一条）——空袭施法是常态，不节流会把 promaid.log 刷满 */
    private static final Map<UUID, Long> SPELL_LAST_LOG = new HashMap<>();
    private static final long SPELL_LOG_INTERVAL = 600L;
    /** 地面近战冷却到期 gameTime */
    private static final Map<UUID, Long> GROUND_READY = new HashMap<>();
    /** v1.2.2 实测六百〇六：俯冲段冲刺·下次可用 gameTime（与其它燃料冷却同一套"到点才能再来"的口径） */
    private static final Map<UUID, Long> DIVE_NEXT = new HashMap<>();
    /**
     * 俯冲段"冲刺"日志的限频表（gameTime）。不复用 {@code SPELL_LOG_INTERVAL}（600 tick = 30 秒，
     * 为常态施法设的）：60 秒观察窗里只会看到 2 行，像"只偶尔生效"；本表 100 tick = 5 秒一条，
     * 比施法密、又不至于每 1.5 秒刷一行。
     */
    private static final Map<UUID, Long> DIVE_LAST_LOG = new HashMap<>();
    private static final long DIVE_LOG_INTERVAL = 100L;

    /** v1.2.0：true = 飞行远战（空中盘旋 + 远程开火），false = 飞行近战（扑击 + 收翅猛击） */
    private final boolean ranged;

    public MaidFlightCombatBehavior() {
        this(false);
    }

    public MaidFlightCombatBehavior(boolean ranged) {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        this.ranged = ranged;
    }

    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        LAUNCH_LEFT.remove(maidId);
        JUMP_LEFT.remove(maidId);
        SMASH.remove(maidId);
        SMASH_TICKS.remove(maidId);
        WAIT_LAUNCH.remove(maidId);
        CLIMB_BOOST.remove(maidId);
        FIREWORK_READY.remove(maidId);
        ATTACK_READY.remove(maidId);
        RANGED_BOOST_LEFT.remove(maidId);
        RANGED_NEXT_BOOST.remove(maidId);
        RANGED_NEXT_SHOT.remove(maidId);
        RANGED_GUN_CD.remove(maidId);
        GUN_STATE_TICKED.remove(maidId);
        RANGED_FIRE_LAST_LOG.remove(maidId);
        RANGED_PUSH_LEFT.remove(maidId);
        RANGED_PUSH_LAST_LOG.remove(maidId);
        GROUND_READY.remove(maidId);
        SPELL_NEXT_CAST.remove(maidId);
        SPELL_LAST_LOG.remove(maidId);
        DASH_NEXT.remove(maidId);
        DASH_LAST_LOG.remove(maidId);
        DIVE_NEXT.remove(maidId);
        DIVE_LAST_LOG.remove(maidId);
        STANDOFF_LAST_LOG.remove(maidId); // 【实测七百〇四】"拉开距离"日志节流
        LAUNCH_STUCK_LOG.remove(maidId);  // 【实测七百〇七】"起飞受阻"日志节流
        STANDOFF_TICKS.remove(maidId);    // 【实测七百〇四】"拉开距离"每轮预算
        STANDOFF_DONE.remove(maidId);     // 【实测七百〇六】"本轮已拉开过"标记
        FLOOR_LAST_LOG.remove(maidId);    // 【实测七百〇六】"高度地板"日志节流
        MaidBombing.forget(maidId);
        // v1.2.0 实测五百二十一：空袭专用索敌器的锁定/限频也一并清（见 FlightTargeting）
        FlightTargeting.forget(maidId);
        // 【实测六百九十三】锁敌后的随机环绕参数（半径比例 / 重掷节拍）随她一起丢
        CombatOrbit.forget(maidId);
        // 【实测七百〇三】接敌机动（这一场抽到的那一种 + 波形相位）随她一起丢
        CombatManeuver.forget(maidId);
        // v1.2.0 实测五百一十一：这里【不】清 FlightFireworkPose 的表——它的归还要靠
        // **实体引用**，而 forget 只有 UUID。清表会让"原副手物品"快照被丢掉、盾牌/食物
        // 永久留在烟花状态。归还统一由 core 行为 MaidToolAutoEquipBehavior 每 tick 调用的
        // FlightFireworkPose.tick(maid) 完成（那个行为任何 activity 都跑，是最可靠的回收入口），
        // 所以即使本行为被强杀，最迟 10 tick 后也会自动还原。
    }

    /**
     * 行为**中途停止**时的清场：只复位本轮的 LAUNCH/JUMP/SMASH/WAIT 等动作，**保留瞄准与烟花冷却**。
     * 不能走 {@link #forget}——那是硬清（连 {@link FlightTargeting} 锁定与 {@link #FIREWORK_READY}
     * 一起清）：空袭一轮里行为本就会短暂停，清锁定会让她"有敌人却拿不到目标"→ 高空无朝向→顺惯性飘走；
     * 清冷却则停一下就能重开一枚烟花。分工：forget = 女仆移除 / 换任务；pauseRound = 行为收尾。
     * "跑出战场不再追"由 {@link FlightTargeting#HOLD_RANGE} 管。
     */
    public static void pauseRound(UUID maidId) {
        if (maidId == null) {
            return;
        }
        LAUNCH_LEFT.remove(maidId);
        JUMP_LEFT.remove(maidId);
        SMASH.remove(maidId);
        SMASH_TICKS.remove(maidId);
        WAIT_LAUNCH.remove(maidId);
        CLIMB_BOOST.remove(maidId);
        ATTACK_READY.remove(maidId);
        RANGED_BOOST_LEFT.remove(maidId);
        RANGED_NEXT_BOOST.remove(maidId);
        RANGED_NEXT_SHOT.remove(maidId);
        RANGED_GUN_CD.remove(maidId);
        GUN_STATE_TICKED.remove(maidId);
        RANGED_FIRE_LAST_LOG.remove(maidId);
        RANGED_PUSH_LEFT.remove(maidId);
        RANGED_PUSH_LAST_LOG.remove(maidId);
        GROUND_READY.remove(maidId);
        SPELL_NEXT_CAST.remove(maidId);
        // 【刻意不动的两张表】
        //  - FIREWORK_READY：烟花冷却必须跨过这次停止（否则同一秒能连放两枚）
        //  - FlightTargeting 的锁定：瞄准必须跨过这次停止（见 FlightTargeting.pause）
        FlightTargeting.pause(maidId);
    }

    public static void clearAll() {
        LAUNCH_LEFT.clear();
        JUMP_LEFT.clear();
        SMASH.clear();
        SMASH_TICKS.clear();
        WAIT_LAUNCH.clear();
        CLIMB_BOOST.clear();
        FIREWORK_READY.clear();
        ATTACK_READY.clear();
        RANGED_BOOST_LEFT.clear();
        RANGED_NEXT_BOOST.clear();
        RANGED_NEXT_SHOT.clear();
        RANGED_GUN_CD.clear();
        GUN_STATE_TICKED.clear();
        RANGED_FIRE_LAST_LOG.clear();
        RANGED_PUSH_LEFT.clear();
        RANGED_PUSH_LAST_LOG.clear();
        GROUND_READY.clear();
        NOTIFY_READY.clear();
        SPELL_NEXT_CAST.clear();
        SPELL_LAST_LOG.clear();
        DASH_NEXT.clear();
        DASH_LAST_LOG.clear();
        DIVE_NEXT.clear();
        DIVE_LAST_LOG.clear();
        STANDOFF_LAST_LOG.clear(); // 【实测七百〇四】"拉开距离"日志节流
        STANDOFF_TICKS.clear();    // 【实测七百〇四】"拉开距离"每轮预算
        STANDOFF_DONE.clear();     // 【实测七百〇六】"本轮已拉开过"标记
        FLOOR_LAST_LOG.clear();    // 【实测七百〇六】"高度地板"日志节流
        MaidBombing.clearAll();
        // v1.2.0 实测五百二十一：索敌器状态全清（服务器停止 / 重新加载时）
        FlightTargeting.clearAll();
        FlightFireworkPose.clearAll();
        // 【实测六百九十三】锁敌后的随机环绕表（扫帚那条链路也用同一张，见 CombatOrbit）
        CombatOrbit.clearAll();
        // 【实测七百〇三】接敌机动表（含"这是她第几场遭遇"的序列）
        CombatManeuver.clearAll();
    }

    /**
     * 本轮飞行攻击是否**正在进行中**：JUMP_LEFT / LAUNCH_LEFT / SMASH / WAIT_LAUNCH / CLIMB_BOOST /
     * RANGED_BOOST_LEFT 任一为真即算。
     * 用途：{@code tickSmash} 会主动 {@code setGliding(false)}（收翅才吃得到猛击判定），于是"空中禁
     * 传送"若只认滑翔位会在此失效——主人一远就把她拽走，扑击白费。状态表由 {@link #forget} 收尾整清，
     * 故豁免有界：一轮打完 / 目标消失 / 行为停止后立刻恢复传送。
     * 激流旋转冲击（{@link MaidTridentSpinBehavior#isDashing}）同样主动收翅，一并并入。
     */
    public static boolean isEngaged(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        // 实测五百四十四：空中的旋转冲击也算"本轮正在打"——它只有十几 tick，一旦主人走远、
        // 自动传送把她当场拽走，这一记就白转了（与 实测四百八十七 收翅猛击被传送打断同一类）。
        // isDashing 自带过期自愈，所以豁免同样有界。
        return isEngaged(maid.m_20148_())
                || com.maidsmart.combat.MaidTridentSpinBehavior.isDashing(maid);
    }

    /** 同上（按 UUID 判定，供只在 server tick 里拿得到 id 的调用方用） */
    public static boolean isEngaged(UUID id) {
        if (id == null) {
            return false;
        }
        return JUMP_LEFT.containsKey(id) || LAUNCH_LEFT.containsKey(id)
                || SMASH.contains(id) || WAIT_LAUNCH.contains(id)
                || CLIMB_BOOST.contains(id)
                || RANGED_BOOST_LEFT.containsKey(id);
    }

    /**
     * 本 tick 的目标。全部交给 {@link FlightTargeting#resolve(EntityMaid)}：它自扫"以自身为圆心、
     * 半径 50 格的球"、自选、并**每 tick 回写** brain 的 ATTACK_TARGET/LOOK_TARGET，所以 TLM 何时
     * 按何判据擦都不影响空袭。
     * 旧版读 brain/TLM 会被 {@code maid.searchDimension()} 的排班活动分流坑掉（非 WORK 时垂直只 4 格），
     * 一爬高目标即掉出 → canStillUse 假 → forget 清状态 → 高空滑翔却无朝向 → 飘出战场。
     * canUse / canStillUse / tick 三处都走本方法，口径唯一，避免"启动目标"与"运行目标"分裂。
     */
    private static LivingEntity currentTarget(EntityMaid maid) {
        return FlightTargeting.resolve(maid);
    }

    /* ---------------- 行为骨架 ---------------- */

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_MODE.get()) {
            return false;
        }
        if (maid.m_5803_() || maid.m_20159_()) {
            return false;
        }
        LivingEntity target = currentTarget(maid);
        if (target == null) {
            return false;
        }
        if (target instanceof Player p && (p.m_5833_() || p.m_7500_())) {
            return false;
        }
        return !FriendlyFireGuard.isFriendly(maid, target);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid.m_5803_() || maid.m_20159_()) {
            return false;
        }
        // TD-10：总开关也在 canStillUse 里问——关掉空战模式时正在跑的这一轮即刻停。
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_MODE.get()) {
            return false;
        }
        return currentTarget(maid) != null;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        stopGunAim(maid); // 退出模式时收枪（TACZ 的 ADS 状态不会自己复位）
        endFlightSafely(maid, maid.m_20148_());
    }

    /**
     * 空战三件套不齐提示的限频表（15 秒冷却；{@code ChatBubbleLimitMixin} 再兜底气泡节流）。由
     * {@link MaidToolAutoEquipBehavior#canUse}（core 行为，任何 activity 每 tick 跑）在飞行任务下直接
     * 调用——**进入任务即检查**，不再等遇到敌人（旧版挂在本行为 tick 里，而 canUse 要求
     * currentTarget != null，没敌人时永远走不到提示分支 → "只在遇到敌人才发"）。
     * 三件齐备时重置冷却，故"补齐 → 又缺"能立刻再报。
     */
    private static final java.util.Map<EntityMaid, Long> NOTIFY_READY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 第一次见到这只女仆的 gameTime（同一实体实例只记一次）。刚放出 / 区块重载后，法术模组的书单要
     * 一拍才初始化——那一 tick {@code hasClimbSpell} 还是 false → 缺件判定成立 → 立刻误报。入世界后
     * 前 {@link #NOTIFY_GRACE_TICKS} tick 宽限（不报、不占冷却）。键是实体实例，故"放出来 = 新实例"
     * 天然重置宽限。
     */
    private static final java.util.Map<EntityMaid, Long> NOTIFY_FIRST_SEEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** 入世界后的静默窗口（tick）——0.5 秒，与反馈建议一致 */
    private static final long NOTIFY_GRACE_TICKS = 10;

    /**
     * 缺件 / 齐备都必须**连续成立**这么久（tick）才认账（2 秒）。旧版"见一拍齐就清冷却、见一拍缺就
     * 播报"，而本模组自己的动作表现（{@code BombPose} / {@code FlightFireworkPose} 借走副手十几 tick）
     * 每隔十几秒就造一次抖动 → "缺→报→还→齐→清冷却→再报"的循环。2 秒够盖住任何一次"举一下"（单次
     * 10 tick 起、连续接力最长约 30 tick），又不至于让真缺件提示迟到多久。
     */
    private static final long STABLE_TICKS = 40;
    /** maid → "缺件"最早被看到的 gameTime（一旦齐备就清掉） */
    private static final java.util.Map<EntityMaid, Long> MISSING_SINCE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** maid → "齐备"最早被看到的 gameTime（一旦缺件就清掉） */
    private static final java.util.Map<EntityMaid, Long> COMPLETE_SINCE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void notifyNotReady(EntityMaid maid, long gameTime) {
        try {
            if (maid == null) {
                return;
            }
            String missing = MaidFlightKit.missingParts(maid);
            if (missing == null) {
                // 三件齐备 = 这一轮"缺件"状态结束 → 清冷却，下次再缺立刻能报。
                // 实测六百〇九：**齐备也要站稳 STABLE_TICKS 才算真齐**——见 STABLE_TICKS 的注释
                //（旧版见到一拍齐就清冷却，于是"我们自己的副手动作借出去又还回来"这种
                // 十几 tick 的抖动每轮都能把冷却清零，假提示每 10 秒一条、永远不停）。
                MISSING_SINCE.remove(maid);
                Long cSince = COMPLETE_SINCE.get(maid);
                if (cSince == null) {
                    COMPLETE_SINCE.put(maid, gameTime);
                    return;
                }
                if (gameTime - cSince >= STABLE_TICKS) {
                    COMPLETE_SINCE.remove(maid);
                    NOTIFY_READY.remove(maid);
                }
                return; // 其实是齐的（判定竞态）→ 不误报
            }
            COMPLETE_SINCE.remove(maid);
            // 实测五百七十六：入世界后的前 0.5 秒静默（法术/饰品数据要一拍才就绪，早了会误报）
            Long firstSeen = NOTIFY_FIRST_SEEN.get(maid);
            if (firstSeen == null) {
                NOTIFY_FIRST_SEEN.put(maid, gameTime);
                return;
            }
            if (gameTime - firstSeen < NOTIFY_GRACE_TICKS) {
                return;
            }
            // 实测六百〇九：缺件同样要**站稳 STABLE_TICKS** 才播报（见 STABLE_TICKS）
            Long mSince = MISSING_SINCE.get(maid);
            if (mSince == null) {
                MISSING_SINCE.put(maid, gameTime);
                return;
            }
            if (gameTime - mSince < STABLE_TICKS) {
                return;
            }
            Long ready = NOTIFY_READY.get(maid);
            int cooldown = 300; // 15 秒
            if (ready != null && gameTime < ready) {
                return;
            }
            NOTIFY_READY.put(maid, gameTime + cooldown);
            // v1.2.0 实测五百五十五【模组鞘翅装备排查】：与播报同一节流（最多 15 秒一行）——
            // 缺鞘翅时把"胸甲装的是什么、它自称能不能滑翔"写进运行日志。模组"内置鞘翅的装备"
            // 认不认的问题，看这一行就知道是"我们没认"还是"那个物品没实现滑翔钩子"。
            if (!MaidFlightKit.hasElytra(maid)) {
                com.maidsmart.tool.PromaidLog.log("空袭装备",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 缺鞘翅（" + MaidFlightKit.elytraDiagnostic(maid) + "）");
            }
            // v1.2.2 实测六百〇九【缺燃料也要有一行日志】：与上面那行同一节流（最多 15 秒一行）。
            // 旧版缺"可以飞行的道具"时日志里什么都不写，气泡却照发——现场无法复核。
            // 见 {@link MaidFlightKit#fuelDiagnostic}。
            if (missing.contains("可以飞行的道具")) {
                com.maidsmart.tool.PromaidLog.log("空袭装备",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 缺可以飞行的道具（" + MaidFlightKit.fuelDiagnostic(maid)
                                // 【实测七百一十】把"自推鞘翅"那一路也印在同一行：现场"她明明
                                // 穿着伊卡洛斯/神秘遗物的鞘翅却不飞"时，这一行能直接分开
                                // "我们没认它"与"那件东西没让她滑起来"（同节流，最多 15 秒一行）。
                                + "；" + com.maidsmart.combat.MaidSelfPropelledWings.diag(maid) + "）");
            }
            // 实测五百七十四：措辞按缺件内容分流——缺"可以飞行的道具"时把几种手段点出来
            // （烟花火箭 / 孔雀羽扇 / 能上天的位移类法术任一即可），免得玩家以为只能用烟花。
            // v1.2.4 实测六百三十三：再添一件——带激流附魔的三叉戟。
            // v1.3.0(beta) 实测七百一十：再添第五件——"自推鞘翅"（伊卡洛斯之翼空域系 /
            // 神秘遗物+ 的壮丽·混沌）：穿着它就不需要烟花（推力由本模组替她施加）。
            String hint = missing.contains("可以飞行的道具")
                    ? "（烟花火箭 / 孔雀羽扇 / 位移类法术 / 激流三叉戟 / 自推鞘翅任一）" : "";
            maid.getChatBubbleManager().addTextChatBubble(
                    "空战装备不齐，没有" + missing + hint + "，先按普通战斗来");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 目标丢失 / 三件不齐 / 行为结束时的安全收尾：**空中不切滑翔**。旧版这几处无条件
     * {@code setGliding(false)}，而她此刻多半在高空（烟花推进的必然结果），一清滑翔位即自由落体
     * ——实测飞行期死亡全是 {@code fall 21~23}（她只有 20 血）。正确做法同玩家：没有推力就一路滑翔
     * 下来（原版 {@code travel} 滑翔分支自然减速），落地由 {@code updateFallFlying} 自然清位。这里走
     * {@link #pauseRound}（只清本轮动作、留瞄准与冷却），不走 forget（理由见 pauseRound）。
     */
    private static void endFlightSafely(EntityMaid maid, UUID id) {
        pauseRound(id);
        // v1.2.4 实测六百四十（六百四十一 起口径不变）：这一轮既然收了，激流推进剂窗口也一起
        // 摘掉（它只在 m_6725_ 里被驱动，链路走不到这里就没人再推）——留着只是表里的陈旧项。
        // **注意**：这里只丢窗口引用，不作任何减速（"保持动量自然滑翔"那一档的语义，同 BOOST_ROCKET）。
        com.maidsmart.combat.MaidRiptideBoost.clear(maid);
        // 【实测七百一十】自推鞘翅的推力窗同理：它只在 m_6725_ 里被驱动，链路一停就该摘掉
        //（**已给的速度同样不动**——保持动量自然滑翔那一档的语义，同 BOOST_ROCKET）。
        com.maidsmart.combat.MaidSelfPropelledWings.clear(maid);
        // 【实测七百〇四：这一轮到此为止，两套随机作废、下一轮重抽】
        //
        //  只在换任务/下线（{@code forget}）时清，而"丢目标/这一轮收手"走的是本方法（它调的是
        //  pauseRound，**不清这两张表**）。于是 begin 的幂等语义变成"一次抽签用一整天"。
        //  本方法是空袭唯一的"这一轮结束"漏斗（pauseRound 是轮内短暂停，刻意不动这两张表），
        //  所以重抽放在这里：她的下一场遭遇一定看得见换打法。
        CombatOrbit.forget(id);
        CombatManeuver.forget(id);
        if (maid.m_20096_()) {
            MaidFlightKit.setGliding(maid, false);
        }
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
        // 【实测七百一十一】烫伤脱困：空袭途中她真泡进岩浆/着火了 → 立刻传送出去，本 tick 就此
        // 打住（刚传送完再走相位会把她从落点上推走）。判据见 MaidHeatEscape。
        if (MaidHeatEscape.tick(maid)) {
            return;
        }
        LivingEntity target = currentTarget(maid);
        // 轰炸已改判为"附加链路"：旧版这里有一道 `if (MaidBombing.isBombing(maid)) return;`，整段让位
        // ——起手那一刻正是"收翅猛击打完、马上再起飞"，让位等于把起飞压后 0.5~1 秒，一轮反复几次肉眼
        // 可见"怎么不起飞"。现在轰炸走自己的服务端驱动（放方块 / 挂水晶 / 充能都不需要她停手），本行为
        // 一眼都不看轰炸状态：该起飞起飞、该扑击扑击、该开火开火。
        if (target == null) {
            // v1.2.0 实测四百七十二【摔死主因】：目标一没就无条件清滑翔位 —— 她多半
            // 正在高空（烟花推进的必然结果），清位即自由落体，20 血必死。改为
            // 空中保留滑翔自然下降，落地由原版清位（见 endFlightSafely）。
            endFlightSafely(maid, id);
            return;
        }

        // 三件不齐 = 模式未激活（烟花用完 / 鞘翅损坏 / 武器没了）→ 与普通攻击模式一致，绝不滑翔
        if (!MaidFlightKit.isModeActive(maid)) {
            notifyNotReady(maid, gameTime);
            endFlightSafely(maid, id);
            if (maid.m_20096_()) {
                groundMelee(level, maid, target, gameTime);
            }
            return;
        }
        MaidFlightKit.equip(maid);
        if (!MaidFlightKit.isModeActive(maid)) {
            notifyNotReady(maid, gameTime);
            endFlightSafely(maid, id);
            if (maid.m_20096_()) {
                groundMelee(level, maid, target, gameTime);
            }
            return;
        }

        // 激流旋转突进期间只让掉"这十几 tick 的速度与滑翔权"。为何必须让：突进"这一 tick 速度由我指定"，
        // 而空袭同 tick 会放烟花 + 开滑翔，三套速度互相覆盖 → 旋转放不完。此次不会重演：①起手时机 =
        // 她的攻击时机（有冷却 + 收招硬直，非按距离自主触发）；②让位窗口有界（一次旋转时长 + 陈旧自愈，
        // 见 MaidTridentSpinBehavior.isDashing）；③放在三件套维护与缺件提示**之后**，装备照穿、缺件照报，
        // 被让的只有状态机本身。
        if (MaidTridentSpinBehavior.isDashing(maid)) {
            MaidFlightKit.setGliding(maid, false); // 收翅：滑翔会把突进速度拽回去、还限速
            suppressVanillaMelee(maid);
            return;
        }

        // 激流推进剂的每 tick 维持（拟真烟花）：把整个速度矢量往"视线 × 当前力度"上拉
        // （v ← v×0.5 + 视线×(力度/2)，照原版挂载烟花的递推形状），力度 = 玩家水中那一记 3.0×(1+等级)/4
        // ×1.3，按水阻力 ×0.80/tick 递减到滑翔常态。旧版是一次性冲量 + 只压水平，竖直管不住、起飞特别高。
        // 位置：突进让位之后、各相位派发之前（每个空中相位都该继续推；收翅猛击那段由窗口自己的"只在
        // 滑翔时生效"挡掉）。无窗口时这里只是一次 Map 查询。
        MaidRiptideBoost.tick(maid);
        // 【实测七百一十】自推鞘翅的每 tick 推力：与上一行同一个位置、同一个约定
        //（各相位派发**之前**、只在她滑翔时生效）。窗口由 {@code tryLaunch} 的第五条腿 /
        // 飞行跟随的补推开，这里只负责"窗开着就推一下"。没有窗口时这只是一次 Map 查询。
        MaidSelfPropelledWings.tick(maid);

        // ── 第 0 步：起跳滑翔（离地后立刻放烟花，才吃得到烟花推力）──
        Integer jumpLeft = JUMP_LEFT.get(id);
        if (jumpLeft != null) {
            if (maid.m_20096_()) {
                if (jumpLeft <= 1) {
                    JUMP_LEFT.remove(id);
                    // 【实测七百〇七】起跳窗走完仍未离地：留痕（每只女仆 5 秒一条）。
                    // 需求原文："女仆一旦执行这个起飞的链路，一定要成功执行，而不是在原地浪费。"
                    // 正常情况这里一次都不该打出来——打出来就说明那一跳被同 tick 的移动源吃掉了。
                    logLaunchStuck(maid, gameTime);
                } else {
                    // 离地前每 tick 都补一跳。只写 0.42 不行：同 tick 的 TLM / 战术 / 自保走位会立刻把她按回
                    // 地面（MoveToTargetSink 在同一 brain 阶段重写位移），0.42 被摊平 ⇒ 原地蹦好几下却离不了地；
                    // 而被敌人打中那一下不属"女仆方移动意图"，反把她顶离地 → "挨打后才起飞"。修法见
                    // {@link #kickOffGround}：掐掉同 tick 抢移动的意图 + 重给竖直冲量 + 置 hasImpulse。
                    kickOffGround(maid);
                    JUMP_LEFT.put(id, jumpLeft - 1);
                    faceLaunchDirection(maid, target);
                    return;
                }
            } else {
                JUMP_LEFT.remove(id);
                MaidFlightKit.setGliding(maid, true);
                if (tryLaunch(level, maid, target, id, gameTime)) {
                    return;
                }
            }
        }

        // ── 猛击段：已收翅、自由落体，触底前砸一记 ──
        if (SMASH.contains(id)) {
            tickSmash(level, maid, target, id, gameTime);
            return;
        }

        // 垂直占位：判据"够高" = 她追到目标身下 altitudeTolerance() 格（现 10）以内；目标只高出 10 格
        // 以内不进本相位，原链路（俯冲 / 盘旋）自能打。只拦**空中**这段——她还在地上时不在此起飞（地面
        // 支点自带"水平太远先跑过去"，起跳 / 烟花朝向同样由 faceLaunchDirection 判高度）。另外"够高"非
        // 唯一留守条件：本相位点着的这枚烟花推力没烧完也不许走，否则剩下的推力会被下一段水平朝向摊掉，
        // 她永远攒不出"在它头上"（见 tickClimbToAltitude）。
        boolean needClimb = !onTargetAltitude(maid, target);
        boolean holdingBoost = !maid.m_20096_() && CLIMB_BOOST.contains(id)
                && LAUNCH_LEFT.getOrDefault(id, 0) > 0;
        if (!maid.m_20096_() && (needClimb || holdingBoost)) {
            // v1.2.0 实测五百七十二【补高】：她确实需要高度时，先用"提供高度"的位移法术顶一口
            // （比烟花更省：不消耗燃料、也不占烟花的冷却），没得用才走原来的烟花爬升
            if (needClimb && tryDashClimb(maid, target, id, gameTime, false)) {
                return;
            }
            tickClimbToAltitude(level, maid, target, id, gameTime);
            return;
        }
        if (!holdingBoost) {
            // 走了别的链路（含落地）→ 本相位点的那枚烟花不再占位，标记得撤干净
            CLIMB_BOOST.remove(id);
        }

        // ── 阶段一：烟花刚出手，维持"背离敌人 + 向上"把推力吃满 ──
        int left = LAUNCH_LEFT.getOrDefault(id, 0);
        if (left > 0) {
            LAUNCH_LEFT.put(id, left - 1);
            // 【实测六百八十二：远战这一段也开火】旧版这一整段（远战 20 tick = 1 秒）一枪不放——
            //  每一轮"落地 → 重新起飞"都要白丢 1 秒输出，正是
            //
            //  【顺序：开火在前、摆朝向在后】枪械开火（TLM 的 performGunAttack）会自己把她的身体拧向
            //  目标；下面 faceLaunchDirection 紧随其后把朝向摆回"背离/朝目标 + 抬头"。烟花的推力方向
            //  取的是她**当拍**的视线，每 tick 现算，所以这一口的推力方向一个字都没变
            //  （与 tickRangedAir 里"开火必须早于朝向"是同一条规矩）。近战不调（它本来也不开火）。
            if (this.ranged) {
                fireRanged(maid, target, id, gameTime);
            }
            faceLaunchDirection(maid, target);
            MaidFlightKit.setGliding(maid, !maid.m_20096_());
            suppressVanillaMelee(maid);
            return;
        }

        double dist = maid.m_20270_(target);
        // 起飞门槛只看**水平距离**（launchRange = 20）。旧版拿 3D 距离比：敌人高 50 格时光垂直就 >20，
        // 她站在正下方也永远进不了起飞分支，只原地仰头看。launchRange 的原意"太远先跑过去、避免越炸越远"
        // 本就是水平概念，垂直不该受限（空袭立体作战，敌人飞多高都要能打）；爬升量由起飞仰角承担，
        // "目标在头顶时朝它爬而非背离"由 {@link #faceLaunchDirection} 负责。
        double dxh = maid.m_20185_() - target.m_20185_();
        double dzh = maid.m_20189_() - target.m_20189_();
        double distH = Math.sqrt(dxh * dxh + dzh * dzh);

        // ── 地面：贴身就地近战收尾；否则起跳滑翔 → 放烟花（新一轮的起点）──
        if (maid.m_20096_()) {
            if (ranged) {
                // 飞行远战：落地就重新起飞恢复盘旋（开火由 performRangedAttack 通道负责）；
                // 真的一点飞行手段都没有（烟花/羽扇/位移法术全缺）才站着，目标会在原地继续被远程打
                if (distH <= launchRange() && canTakeOff(maid, gameTime)) {
                    if (canJumpToLaunch(maid, gameTime)) {
                        jumpForLaunch(maid, target, id); // 起跳滑翔 + 空中放烟花/挥扇/点火激流
                        return;
                    }
                    // 实测五百七十二（五百七十四并入总判定）：烟花不可用（用完/冷却）→
                    // 用"提供高度"的位移法术平地起飞
                    if (tryDashClimb(maid, target, id, gameTime, true)) {
                        return;
                    }
                }
                MaidFlightKit.setGliding(maid, false);
                // ── v1.2.4 实测六百四十三：站在地上也要打 ──
                // 开火那一句原先**只**写在 tickRangedAir（空中盘旋相位）里，地面这条分支一路
                // return 到底——于是"落地、等重新起飞"的那几秒她举着枪一枪不放。实测日志实证
                // （2026-09-23 17:15，监守者）：距敌 2.6 格、主手 tacz:modern_kinetic_gun、
                // 背包里有 tacz:ammo_box，站桩被打死，全程零开火。
                // 这里补一次同样的开火：弓弩走 performRangedAttack（本任务的回调），枪械走 TLM
                // 枪械通道；节奏由 fireRanged 内部的冷却表管，不会连发。
                fireRanged(maid, target, id, gameTime);
                return;
            }
            if (distH <= launchRange() && canTakeOff(maid, gameTime)) {
                if (canJumpToLaunch(maid, gameTime)) {
                    jumpForLaunch(maid, target, id);
                    return;
                }
                // 实测五百七十二（五百七十四并入总判定）：烟花不可用（用完/冷却）→
                // 用"提供高度"的位移法术平地起飞，不用再站在地上等
                if (tryDashClimb(maid, target, id, gameTime, true)) {
                    return;
                }
            }
            MaidFlightKit.setGliding(maid, false);
            groundMelee(level, maid, target, gameTime);
            return;
        }

        // ── 阶段二 ──
        MaidFlightKit.setGliding(maid, true);
        if (ranged) {
            // 飞行远战：空中盘旋（类似幻翼）+ 每 5 秒补烟花 + 用手持远程武器开火
            tickRangedAir(level, maid, target, id, gameTime);
            return;
        }
        // 【空袭·法术层】（实测五百六十七，PR #8 移植）近战空袭的施法时机：此刻她已在
        // 目标上方、正要压低朝向俯冲——法术模组的"吟唱期间把朝向钉在目标上"与这一段
        // 的意图一致。放在 faceTarget 之前，让本 tick 的朝向仍以空袭的为准（吟唱抢朝向
        // 发生在下一 tick）。爬升相位（tickClimbToAltitude）与猛击段刻意不调用，理由见
        // tryCastSpell 的注释。
        tryCastSpell(maid, target, id, gameTime);

        // 近战也先拉开距离再俯冲：距离未到 {@code airRaid.minStandoff} 时先背离敌人平飞一小段（借滑翔
        // 自然位移，不俯冲、不烧燃料）。两条判据必须统一成 **3D 距离**——俯冲闸门用 3D、拉开若用水平，
        // "想俯冲的每一 tick 水平必然也 < minStandoff = 6"，拉开判据在每次俯冲门前都成立 → 俯冲被反复
        // 掐断（实测 27 行「拉开」、0 行猛击）。且**一轮只拉开一次**：够远或预算（{@link #STANDOFF_MAX_TICKS}
        // = 40）用完即记入 {@link #STANDOFF_DONE}，直到 {@link #endSmash} 摘标记才重新评估（旧版预算自复位
        // → "拉开 40、放开 1、再拉开 40"）。{@link #WAIT_LAUNCH} 期间不拉开。拉开的是"进场距离"，
        // 瞄准与命中判定一个字不改。
        if (!ranged) {
            double dist3 = maid.m_20270_(target); // 【七百〇六】3D 距离（旧版"拉开"用水平距离，见上）
            if (WAIT_LAUNCH.contains(id)) {
                // (c) 猛击收尾后的"等待再起飞"：不拉开
                STANDOFF_TICKS.remove(id);
            } else if (STANDOFF_DONE.contains(id) || dist3 <= smashRange()) {
                // 本轮已经拉开过 / 已经进收翅线：放行俯冲，不再拉开
                STANDOFF_TICKS.remove(id);
            } else if (dist3 >= airMinStandoffCfg()) {
                // 已经够远 → 本轮"拉开"到此为止，接下来一路放行俯冲（(b) 的关键一步：
                //  她接下来往回压的那一段必然穿过 6 格线以内，若不留这个标记就会在线上无限抖动）
                STANDOFF_DONE.add(id);
                STANDOFF_TICKS.remove(id);
            } else {
                int pulled = STANDOFF_TICKS.getOrDefault(id, 0);
                if (pulled < STANDOFF_MAX_TICKS) {
                    STANDOFF_TICKS.put(id, pulled + 1);
                    // 背离敌人 + **平飞**（不抬头：高度已经占好了，抬头会掉速、还把她顶得更高）。
                    // 与 faceAwayAndUp 同一套抗 LookControl 归零做法，否则下一 tick 会被 xRot 归零抹平。
                    faceAwayFlat(maid, target);
                    suppressVanillaMelee(maid);
                    logStandoff(maid, dist3);
                    return;
                }
                // 预算用完还拉不开（墙角 / 敌人同速追 / 她本来就在敌人正上方）→ 本轮放弃拉开、直接俯冲。
                //  旧版这里只把预算归零，于是下一 tick 又从头拉开——一轮都不俯冲。
                STANDOFF_DONE.add(id);
                STANDOFF_TICKS.remove(id);
                logStandoffGiveUp(maid, dist3);
            }
        }

        suppressVanillaMelee(maid);
        faceTarget(maid, target);

        // v1.2.2 实测六百〇六【俯冲段冲刺加速】：**这一段（朝目标压低机头、一路滑翔扎下去）
        // 就是用户说的"向下朝着敌人俯冲"**——它才是周期里最长的一截。放在 faceTarget 之后
        // 调用，因为那一口加速的方向取的是"她此刻的朝向"（刚被钉在目标上）。
        // 旧版这里单独调 tryDashBoost（只认位移法术）；现在收进 tickDiveBoost 统一排序：
        // 法术 → 烟花 → 羽扇（见方法注释）。
        tickDiveBoost(level, maid, target, id, gameTime);

        // 即将接触 → 取消滑翔，转入猛击段
        if (dist <= smashRange() && !WAIT_LAUNCH.contains(id)) {
            MaidFlightKit.setGliding(maid, false);
            // 实测五百七十：收翅起手那一刻挥臂。旧版唯一的挥臂在命中结算里（1~20 tick
            // 俯冲的最末尾），高速俯冲中那一瞬几乎看不见——用户观感"近战空袭没有攻击动作"。
            // 起手挥臂让整段俯冲带着攻击动作；命中结算里的那一挥保留。激流三叉戟有自转
            // 动画（随后 tryStartDash 接管），起手挥臂对它跳过。
            if (!MaidTridentSpinBehavior.replacesMelee(maid)) {
                maid.m_6674_(InteractionHand.MAIN_HAND);
            }
            SMASH.add(id);
            SMASH_TICKS.put(id, 0);
            return;
        }
        // 猛击完（或打空收尾）后：再次放烟花，回到阶段一
        if (WAIT_LAUNCH.contains(id) && tryLaunch(level, maid, target, id, gameTime)) {
            return;
        }
    }

    /* ---------------- 起跳滑翔 / 放烟花 ---------------- */

    /** 第 0 步：跳一下离地（落地时 updateFallFlying 会清滑翔位，必须先离地） */
    private void jumpForLaunch(EntityMaid maid, LivingEntity target, UUID id) {
        faceLaunchDirection(maid, target);
        kickOffGround(maid);
        MaidFlightKit.setGliding(maid, true);
        JUMP_LEFT.put(id, jumpTicks());
    }

    /**
     * 起跳链路"一定要成功"：把这一 tick 的竖直运动**独占**给起跳冲量。只 {@code setDeltaMovement(x,0.42,z)}
     * 不够——同 tick 还在 brain 阶段抢"移动"的有：TLM {@code MaidMoveControl} 的直接速度矢量、本模组战术
     * {@code meleeTick} 与自保的位移重写、{@code MoveToTargetSink} 把 WALK_TARGET 变寻路。0.42 被摊平 → 原地蹦；
     * 而"挨打"那一下不属女仆方移动意图，反把她顶离地（"被打了反而能起飞"）。
     * 修法（同 {@code MaidMoveSuppressMixin} 清意图口径）：① 清 WALK_TARGET + 停导航；② 重给竖直冲量 0.42；
     * ③ 置 {@code hasImpulse}（否则客户端按旧速度把她画在原地）。全程 try/catch。
     */
    private static void kickOffGround(EntityMaid maid) {
        try {
            maid.m_6274_().m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_); // WALK_TARGET
            maid.m_21573_().m_26573_(); // 导航 stop()
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
        try {
            Vec3 dm = maid.m_20184_();
            maid.m_20256_(new Vec3(dm.f_82479_, 0.42, dm.f_82481_));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
        try {
            maid.m_245125_(); // Entity.hasImpulse = true（服务端权威重发位置）
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /** 【实测七百〇七】起跳窗耗尽仍未离地：留痕（每只女仆 5 秒一条，搜「起飞受阻」）。 */
    private static void logLaunchStuck(EntityMaid maid, long gameTime) {
        try {
            UUID id = maid.m_20148_();
            Long last = LAUNCH_STUCK_LOG.get(id);
            if (last != null && gameTime - last < LAUNCH_STUCK_LOG_INTERVAL) {
                return;
            }
            if (LAUNCH_STUCK_LOG.size() > 512) {
                LAUNCH_STUCK_LOG.clear();
            }
            LAUNCH_STUCK_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("起飞受阻",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 起跳窗走完仍未离地（低矮空间 / 被同 tick 移动源吃掉），本轮起跳放弃");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    private static boolean canLaunch(EntityMaid maid, long gameTime) {
        // 实测五百六十三：燃料口径 = 烟花 或 孔雀羽扇（扇子优先，见 tryLaunch）
        // 【实测七百一十】自推鞘翅（伊卡洛斯空域系 / 神秘遗物+）**不吃烟花的冷却**——
        // 它不消耗物资、也不是"一记"，所以那条闸只该闸住要烧东西的那几条腿（见 tryLaunch）。
        if (MaidSelfPropelledWings.hasWings(maid)) {
            return true;
        }
        return !onFireworkCooldown(maid, gameTime) && MaidFlightKit.hasFlightFuel(maid);
    }

    /**
     * 可起飞 = 烟花 / 羽扇可用（{@link #canLaunch}）**或** 她会"提供高度"的位移法术
     * （{@link MaidFlightKit#hasClimbSpell}，不看冷却——沿用套件判定的既有口径）。旧版只认烟花 / 羽扇、
     * 法术仅作兜底，与 {@link MaidFlightKit#isModeActive} / {@link MaidFlightKit#missingParts}（两处都认
     * 法术）不一致，文案也只点名烟花 / 羽扇。顺序不变：有烟花先走烟花链路，没有才用位移法术。
     */
    private static boolean canTakeOff(EntityMaid maid, long gameTime) {
        // v1.2.4 实测六百三十三：再添一件——带激流附魔的三叉戟（"强抬一下激流三叉戟"）。
        // 与 hasClimbSpell 同一口径（不看它的收招硬直/冷却，只看"她有没有这件能起飞的东西"）。
        // 【实测七百一十】自推鞘翅那一条腿由 canLaunch 直接放行（它不吃烟花冷却），
        // 所以这里不必再写第二遍——同一个口径只留一处。
        return canLaunch(maid, gameTime) || MaidFlightKit.hasClimbSpell(maid)
                || MaidFlightKit.hasRiptide(maid);
    }

    /**
     * 地面"起跳"判据：**冷却已过 且（烟花 / 羽扇 或 手持激流三叉戟）**。
     * 旧版只认 {@link #canLaunch}（烟花 / 羽扇）才 {@link #jumpForLaunch}，而激流那条腿写在
     * {@link #tryLaunch} 里、只有**空中**两入口会调，于是"只带激流三叉戟"的她落地后再等多久都起不来
     * ——只能等地面旋转突进把她弹起（还吃 20 tick 收招 + 20 tick 冷却），即"愣一下"。现在起跳与烟花同路
     * （点火仍由空中 {@link #tryLaunch} 做，顺序：扇子 → 烟花 → 激流）。必须连冷却一起判：激流复用烟花
     * 冷却表（{@code FIREWORK_READY}），不看冷却就跳会"跳起来却点不着任何东西"，原地一蹦一蹦。
     */
    private static boolean canJumpToLaunch(EntityMaid maid, long gameTime) {
        // 【实测七百一十】自推鞘翅不吃烟花冷却（见 canLaunch），单独放行。
        if (MaidSelfPropelledWings.hasWings(maid)) {
            return true;
        }
        return !onFireworkCooldown(maid, gameTime)
                && (MaidFlightKit.hasFlightFuel(maid) || MaidFlightKit.hasRiptide(maid));
    }

    private boolean tryLaunch(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        // 【实测七百一十】这张冷却表管的是"刚点过一记要烧/要消耗的推进"（扇子/烟花/激流共用它）。
        // 自推鞘翅**不消耗任何物资**，不该被它挡住——所以只有"她身上确实还有那几件消耗品"时
        // 才吃这道闸；只带自推鞘翅的她直接往下走（否则"烟花刚烧完的那 30 tick 里她推不动"）。
        if (onFireworkCooldown(maid, gameTime)
                && (TwilightFanKit.hasFan(maid) || MaidFlightKit.hasFirework(maid)
                        || MaidFlightKit.hasRiptide(maid))) {
            return false;
        }
        // 实测五百六十三【扇子优先】：有暮色森林孔雀羽扇就挥扇起飞——推进公式、
        // 扇风盒、扣耐久全部照搬扇子自己（见 TwilightFanKit），使用节奏 = 原版
        // getUseDuration 的 20 tick/次；没扇才走烟花。
        if (TwilightFanKit.hasFan(maid) && TwilightFanKit.boostGlide(level, maid)) {
            faceLaunchDirection(maid, target);
            // 必须置位：与烟花同通道——鞘翅滑翔每 tick 吃朝向
            MaidFlightKit.setGliding(maid, true);
            LAUNCH_LEFT.put(id, this.ranged ? launchTicksRanged() : launchTicksMelee());
            WAIT_LAUNCH.remove(id);
            FIREWORK_READY.put(id, gameTime + fanCooldown());
            com.maidsmart.tool.PromaidLog.log("飞行作战",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 挥羽扇起飞");
            return true;
        }
        ItemStack fw = MaidFlightKit.takeFirework(maid);
        if (fw.m_41619_()) {
            // v1.2.4 实测六百三十三【第四条腿：激流三叉戟】——扇子不在、烟花也拿不出来的最后一试。
            // 排在这个位置是刻意的：**不改变任何"有烟花/羽扇"存档的起飞手感与烧料节奏**
            // （顺序仍是 扇子 → 烟花 → 激流），只有真拿不出前两样时才轮到它。
            if (MaidFlightKit.hasRiptide(maid)) {
                // 朝向必须先摆好：原版玩家是自己抬头瞄着放，而这一记**每 tick 都按她的视线推**
                // （v1.2.4 实测六百四十一：烟花式递推、力度照玩家在水里那一记再 ×1.3、按水阻力递减——
                // 所以"照搬该相位该有的朝向"是必须的；"之后视线回到目标"也不再是问题，那正是准度）。
                // 见 MaidRiptideBoost。
                faceLaunchDirection(maid, target);
                if (MaidTridentSpinBehavior.boostForFlight(level, maid)) {
                    MaidFlightKit.setGliding(maid, true); // 与烟花/扇子同通道：滑翔每 tick 吃朝向
                    LAUNCH_LEFT.put(id, this.ranged ? launchTicksRanged() : launchTicksMelee());
                    WAIT_LAUNCH.remove(id);
                    FIREWORK_READY.put(id, gameTime + fanCooldown()); // 与羽扇共用同一个间隔
                    com.maidsmart.tool.PromaidLog.log("飞行作战",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 激流三叉戟起飞");
                    return true;
                }
            }
            // v1.3.0(beta) 实测七百一十【第五条腿：自推鞘翅】——扇子不在、烟花拿不出来、
            // 也没有激流三叉戟时的最后一试。**它不给一次性冲量**（那几件是"点火一记"），
            // 而是开一扇推力窗：那件鞘翅本身会持续推（公式与数值照抄它自己，见
            // MaidSelfPropelledWings），方向由她这一 tick 的视线决定——所以先摆朝向
            // （与烟花/激流同一条纪律：视线朝哪，推力就朝哪）。
            // 排在最末是刻意的：**不改变任何"有烟花/羽扇/法术/激流"存档的起飞手感**。
            if (MaidSelfPropelledWings.hasWings(maid)) {
                faceLaunchDirection(maid, target);
                MaidFlightKit.setGliding(maid, true); // 与烟花/扇子同通道：滑翔每 tick 吃朝向
                if (MaidSelfPropelledWings.ignite(maid)) {
                    LAUNCH_LEFT.put(id, this.ranged ? launchTicksRanged() : launchTicksMelee());
                    WAIT_LAUNCH.remove(id);
                    // 不写 FIREWORK_READY：它不吃那张冷却（见本方法开头），写进去反而会
                    // 连带把同一张表上的扇子/烟花那几条腿也一起冻住（它们共用这一张表）。
                    com.maidsmart.tool.PromaidLog.log("飞行作战",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 自推鞘翅起飞");
                    return true;
                }
            }
            return false;
        }
        launchFirework(level, maid, fw, false);
        // v1.2.0 实测五百一十一/五百一十四：副手"亮一下"烟花模型（纯表现，用完还原原物）；
        // 展示的就是本次真正消耗掉的那一枚（模型与它完全一致）
        FlightFireworkPose.show(maid, fw);
        faceLaunchDirection(maid, target);
        // 必须置位：挂载烟花只对"正在滑翔"的实体给推力
        MaidFlightKit.setGliding(maid, true);
        LAUNCH_LEFT.put(id, this.ranged ? launchTicksRanged() : launchTicksMelee());
        WAIT_LAUNCH.remove(id);
        FIREWORK_READY.put(id, gameTime + fireworkCooldown());
        com.maidsmart.tool.PromaidLog.log("飞行作战",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放烟花起飞");
        return true;
    }

    private static boolean onFireworkCooldown(EntityMaid maid, long gameTime) {
        Long ready = FIREWORK_READY.get(maid.m_20148_());
        return ready != null && gameTime < ready;
    }

    /**
     * 生成挂载型烟花助推。
     * 【安全】1.20.1 烟花是 NBT：只写 {@code Fireworks.Flight}、**不写 Explosions**，这样
     * {@code getExplosions()} 为空 → 到期 {@code explode()} 伤害为 0，不会像带爆炸星的挂载烟花按
     * 5+2n 炸到骑乘者自己（她只有 20 血）。消耗的仍是玩家给的真实烟花（1 枚）。
     * @param scaled true = 用 {@link DiveBoostRocket}——**只在俯冲段那一枚**上，除原版推力再补一份沿视线的
     *               "俯冲段冲刺·烟花力度倍数"推力；起飞 / 爬升 / 盘旋一律 false（照原版）。
     */
    private void launchFirework(ServerLevel level, EntityMaid maid, ItemStack consumedIgnored,
                                boolean scaled) {
        try {
            ItemStack rocketStack = new ItemStack(Items.f_42688_);
            CompoundTag fireworks = new CompoundTag();
            fireworks.m_128405_("Flight", 1);
            rocketStack.m_41784_().m_128365_("Fireworks", fireworks);
            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket = scaled
                    ? new DiveBoostRocket(level, rocketStack, maid)
                    : new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                            level, rocketStack, maid);
            level.m_7967_(rocket);
            SoundEvent launch = ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation("minecraft", "entity.firework_rocket.launch"));
            if (launch != null) {
                level.m_5594_(null, maid.m_20183_(), launch, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 俯冲段烟花力度 ×1.4：只在**俯冲段**点的那一枚挂载烟花上生效（{@code private static}，只有俯冲
     * 那一次 {@code launchFirework(…, true)} 会 new 它）。
     * 原理（javap 实证，两版本一致）：原版挂载烟花对骑手是"每 tick 递推" {@code v ← v×0.5 + 视线×0.85}
     * ——收敛到"1.7 倍视线"的不动点。所以"力度"不能乘冲量，要抬不动点：{@code super.tick()}（原版那一口）
     * 之后再补 {@code 视线×0.85×(倍数−1)}，代入得 {@code v ← v×0.5 + 视线×(0.85×倍数)}，不动点正好 1.7×倍数。
     * 写在 {@code super.tick()} 之后（而非行为里每 tick）是因为两实体 tick 顺序不定、原版那一口的"折半"
     * 会让结果差一倍。
     * {@code getType()} 仍是原版 FIREWORK_ROCKET，客户端按原版渲染，服务端跑本子类；倍数 1.0 时不补任何
     * 东西（逐字节等价旧版）。
     */
    private static final class DiveBoostRocket
            extends net.minecraft.world.entity.projectile.FireworkRocketEntity {

        DiveBoostRocket(ServerLevel level, ItemStack stack, EntityMaid maid) {
            super(level, stack, maid);
        }

        @Override
        public void m_8119_() { // tick
            super.m_8119_(); // 原版那一口：v ← v×0.5 + 视线×0.85（含"未滑翔就不推"的门）
            try {
                double k = diveBoostFireworkScale();
                if (k <= 1.0) {
                    return; // 1.0 = 完全照原版（不补任何东西）
                }
                net.minecraft.world.entity.Entity rider = this.m_19749_(); // getOwner
                if (!(rider instanceof LivingEntity r) || !r.m_21255_()) { // isFallFlying
                    return; // 与原版同一个门：不是滑翔中的骑手就没有推力可乘
                }
                double add = 0.85 * (k - 1.0); // 原版那一口的系数 0.85 ×(倍数−1)
                // 用 m_82520_(add,add,add)（Vec3.add，本文件扇子那一路已在用）而不是 scale()：
                // 同一个动作只留一种写法，arm 上少一个待验证的 SRG 名
                Vec3 look = r.m_20154_(); // getLookAngle
                Vec3 v = r.m_20184_();    // getDeltaMovement
                r.m_20256_(v.m_82520_(look.f_82479_ * add, look.f_82480_ * add,
                        look.f_82481_ * add)); // setDeltaMovement
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            }
        }
    }

    /* ---------------- 猛击段 ---------------- */

    /** 取消滑翔 → 自由落体 → 触底前一次近战猛击（暴击）→ 切回滑翔 */
    private void tickSmash(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        int t = SMASH_TICKS.getOrDefault(id, 0) + 1;
        SMASH_TICKS.put(id, t);
        MaidFlightKit.setGliding(maid, false); // 保持收翅
        suppressVanillaMelee(maid);
        faceTarget(maid, target);

        Vec3 dm = maid.m_20184_();
        double dx = target.m_20185_() - maid.m_20185_();
        double dz = target.m_20189_() - maid.m_20189_();
        maid.m_20256_(new Vec3(dm.f_82479_ * 0.95 + dx * 0.02, dm.f_82480_, dm.f_82481_ * 0.95 + dz * 0.02));

        boolean landed = maid.m_20096_();
        boolean nearGround = !landed && isNearGround(level, maid);
        // v1.2.0 实测四百七十三【命中率】：改用"点到本 tick 位移线段"的距离，
        // 而不是只看当前瞬间距离——她俯冲 1~2 格/tick，瞬时判定会整段穿过去
        // （这就是"命中率堪忧"的主因）。擦身而过的这一 tick 也算够得着。
        boolean inReach = sweepWithin(maid, target, smashHitRange());

        // v1.2.0：够近就尽快打（t>=2 即可——收翅那一刻 fallDistance 已被滑翔钳在 ~1.0，
        // 暴击条件 fallDistance>0 已成立；久等只会让她擦身而过）
        if (inReach && (t >= 2 || nearGround || landed) && smashHit(level, maid, target, gameTime)) {
            // v1.2.2 实测五百八十七【空袭轰炸】：这一记打中了 → 起手轰炸（材料齐才起手；
            // 相位接管下面两 tick：先放方块、再放水晶，之后照旧 endSmash → 起飞）。
            // v1.2.2 实测五百九十【TNT 在链路最末】：这一记猛击打完 = 攻击链路收尾——
            // 没起手轰炸段（材料不齐）就地投 TNT；起手了则由 MaidBombing.tick 在该段收尾
            // 之后投，顺序是「猛击 → 黑曜石/末地水晶那一段 → TNT」
            if (!MaidBombing.tryStartMelee(level, maid, target)) {
                MaidBombing.onAttackChainEnd(level, maid, target);
            }
            endSmash(maid, id, gameTime);
            return;
        }
        // 收尾：落地 / 贴地 / 超时 → 清零坠落距离（不自我摔伤）并切回滑翔。
        // 【安全必需】必须在"未命中"时也能走到：攻击冷却未好时 smashHit 返回 false，
        // 若像旧版那样在命中分支里 return，贴地时既没清零也没收尾 → 她带着满坠落距离撞地。
        // 【命中率】空中且冷却中不在此列（未落地未贴地且 t < MAX）→ 下一 tick 继续压着打，
        // 不再"整个俯冲白打一次"（旧版无论打没打中都 endSmash，是命中率差的一大来源）。
        if (landed || nearGround || t >= smashMaxTicks()) {
            maid.f_19789_ = 0.0f;
            endSmash(maid, id, gameTime);
        }
    }

    /** 猛击收尾：清状态 → 切回滑翔（"攻击完之后再切换为滑翔模式"）→ 等待再放烟花 */
    private void endSmash(EntityMaid maid, UUID id, long gameTime) {
        SMASH.remove(id);
        SMASH_TICKS.remove(id);
        WAIT_LAUNCH.add(id);
        // 【实测七百〇六】一轮俯冲走完 = 下一轮可以重新评估"要不要先拉开距离"（见 STANDOFF_DONE）
        STANDOFF_DONE.remove(id);
        // 实测五百四十四：这一记被换成了激流旋转冲击时**先别开滑翔**——滑翔的 `travel` 会
        // 每 tick 把水平速度往视线方向拽并限速，正好把突进速度磨掉。突进结束后空袭自然回到
        // 本状态机，那时再开滑翔（阶段二那一条），中间只差十几 tick。
        if (!maid.m_20096_() && !MaidTridentSpinBehavior.isDashing(maid)) {
            MaidFlightKit.setGliding(maid, true);
        }
        FIREWORK_READY.put(id, Math.max(FIREWORK_READY.getOrDefault(id, 0L), gameTime));
    }

    /* ==================== v1.2.2 实测六百〇六：俯冲段冲刺加速 ==================== */

    /**
     * 近战空袭俯冲段（阶段②：压低机头朝目标滑翔扎下去）的逐 tick 加速——周期里最长、最影响 DPS 的一截。
     * 燃料优先级：法术 → 激流三叉戟（只扣耐久，排在真要烧掉的烟花前）→ 烟花（真消耗 1 枚，走 {@link DiveBoostRocket}
     * 补推力）→ 羽扇（默认关）。都无则静默跳过（全模组"缺料跳过"口径）。羽扇那一式自带 +1.25 竖直升力、把速度
     * 往"视线×2"收敛，俯冲里等于顶成平飞，故只借动作与消耗，速度改用 {@link TwilightFanKit#boostGlideWith}。
     * 闸口：① 3D 距离 ∈ [{@code diveBoostMinRange()}, {@code diveBoostMaxRange()}]（<5 已贴脸、>40 未到位）；
     * ② 节流 {@code diveBoostInterval()}；③ 激流突进期间让位。
     */
    private void tickDiveBoost(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        try {
            if (!cfgDiveBoost()) {
                return;
            }
            if (gameTime < DIVE_NEXT.getOrDefault(id, 0L)) {
                return;
            }
            if (MaidTridentSpinBehavior.isDashing(maid)) {
                return; // 突进期间速度归它管（与 tick 的让位同口径）
            }
            double d2 = maid.m_20275_(target.m_20185_(), target.m_20186_(), target.m_20189_());
            if (d2 < diveBoostMinRange() * diveBoostMinRange()
                    || d2 > diveBoostMaxRange() * diveBoostMaxRange()) {
                return;
            }
            String what = null;
            // ① 法术（不消耗物资；它自带冲量、沿视线冲刺——成功即算这一口）
            if (MaidSpellCastCompat.dashUsable() && tryDashBoost(maid, target, id, gameTime)) {
                what = "位移法术";
            }
            // ①·5 激流三叉戟（v1.2.4 实测六百三十四）：第二条腿——**只扣耐久、不是消耗品**，
            //      所以排在那枚真的要烧掉的烟花之前。方向取她**此刻的视线**（本方法由
            //      tickDiveBoost 在 faceTarget 之后调用，视线已经被钉在敌人身上 = 朝下扎）。
            //      v1.2.4 实测六百四十一：这一记现在是**拟真烟花**——每 tick 把整个速度矢量往
            //      "视线 × 当前力度"上拉（力度 = 玩家在水里那一记的 3.0×(1+等级)/4 × 1.3，按水阻力
            //      ×0.80/tick 递减）——所以俯冲是"扎得又快、又不会一路飘出去"
            //      （III 级总行程约 17 格，与玩家在水里同一把三叉戟同量级）。见 MaidRiptideBoost。
            //      普通旋转突进（近战那一条）没跑时它才用——那一条自己有 16 tick 的速度权。
            if (what == null && cfgDiveBoostRiptide() && MaidRiptideBoost.diveDash(level, maid)) {
                what = "激流三叉戟";
            }
            // ② 烟花：真的点一枚（原版推力沿视线生效，方向不变），并让副手亮一下
            //     六百一十五：这一枚走 **DiveBoostRocket**——原版推力之外再补一份
            //     "×（俯冲段冲刺·烟花力度倍数）"的沿视线推力（默认 1.4，见那个类的注释）
            if (what == null && cfgDiveBoostFirework() && MaidFlightKit.hasFirework(maid)) {
                ItemStack fw = MaidFlightKit.takeFirework(maid);
                if (!fw.m_41619_()) {
                    launchFirework(level, maid, fw, true);
                    FlightFireworkPose.show(maid, fw);
                    what = "烟花×" + trimScale(diveBoostFireworkScale());
                }
            }
            // ③ 羽扇：借它的动作与消耗（挥臂/音效/扇风盒/扣耐久），速度用"方向不变、只加大小"那一口
            if (what == null && cfgDiveBoostFan() && TwilightFanKit.hasFan(maid)) {
                Vec3 look = maid.m_20154_();
                double k = diveBoostImpulse();
                Vec3 v = maid.m_20184_().m_82520_(look.f_82479_ * k, look.f_82480_ * k, look.f_82481_ * k);
                if (TwilightFanKit.boostGlideWith(level, maid, v)) {
                    what = "羽扇";
                }
            }
            if (what == null) {
                return; // 一件燃料都没有：静默跳过（不占间隔）
            }
            DIVE_NEXT.put(id, gameTime + diveBoostInterval());
            if (gameTime - DIVE_LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) >= DIVE_LOG_INTERVAL) {
                DIVE_LAST_LOG.put(id, gameTime);
                com.maidsmart.tool.PromaidLog.log("空袭·俯冲",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 俯冲加速（" + what
                                + "，距敌 " + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(d2))
                                + " 格）");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    private static boolean cfgDiveBoost() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST.get();
    }
    private static int diveBoostInterval() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_INTERVAL.get();
    }
    private static double diveBoostMinRange() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_MIN_RANGE.get();
    }
    private static double diveBoostMaxRange() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_MAX_RANGE.get();
    }
    private static double diveBoostImpulse() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_IMPULSE.get();
    }
    private static boolean cfgDiveBoostFirework() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_FIREWORK.get();
    }
    /** 俯冲段那一枚烟花的推力倍数（默认 1.4，v1.2.2 实测六百一十五）：1.0 = 完全照原版 */
    private static double diveBoostFireworkScale() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_FIREWORK_SCALE.get();
    }
    /** 日志里的倍数写法：1.4→"1.4"、2.0→"2"（去掉尾随的 0，别写"2.0 倍"这种别扭字） */
    private static String trimScale(double k) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", k);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
    private static boolean cfgDiveBoostFan() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_FAN.get();
    }
    /** 俯冲段冲刺·用激流三叉戟（v1.2.4 实测六百三十四，默认开） */
    private static boolean cfgDiveBoostRiptide() {
        return MaidSmartConfig.AIR_RAID_DIVE_BOOST_RIPTIDE.get();
    }
    /** 掉高补推·用激流三叉戟（v1.2.4 实测六百三十四，默认开） */
    private static boolean cfgRangedBoostRiptide() {
        return MaidSmartConfig.AIR_RAID_RANGED_BOOST_RIPTIDE.get();
    }

    /**
     * 收翅下落中的这一击（1.20.1 无重锤 → 走原版暴击）：
     * 伤害 = 攻击力 × 1.5 + 附魔加成（与玩家暴击完全一致，本模组
     * `MaidCombatTacticsBehavior.swingCritHit` 同款算法）+ 击退 + 火焰附加 + 荆棘 +
     * 暴击粒子/音效；收尾 `fallDistance = 0`（不自我摔伤）。
     */
    private static boolean smashHit(ServerLevel level, EntityMaid maid, LivingEntity target, long gameTime) {
        // v1.2.0：强制受击的间隔 = 女仆自己的攻击频率（不是无条件触发）：冷却没好就不出手
        UUID attackId = maid.m_20148_();
        if (gameTime < ATTACK_READY.getOrDefault(attackId, 0L)) {
            return false; // 冷却中：本次没出手，调用方要保持俯冲继续压（别当成打空收尾）
        }
        // v1.2.0 实测五百二十七【出手要看方块阻隔】：主目标这一记原先无条件结算，
        // 只有下面的"范围补刀"过了视线——隔着一层方块也能凭空打中（与 TLM 原生近战
        // 「出手前必过 hasLineOfSight」以及本模组其它链路的口径不一致）。
        // 这里放在冷却检查【之后、消费冷却之前】：看不见 = 这次没出手，不吃攻击冷却，
        // 调用方（tickSmash）会继续俯冲压着打，等视线一通就打出去。
        if (!SelfPreservationBehavior.hasSight(maid, target)) {
            return false;
        }
        // 实测五百三十八 / 五百四十一 / 五百四十四：主手是激流三叉戟时，这一记换成朝目标的
        // 旋转冲击（起手成功 = 这次出手了，吃攻击冷却）。起不了手（硬直中 / 超出突进距离）
        // 就照常走下面的俯冲猛击 —— 绝不吞掉这一记，否则她在空袭里会变成不出手的哑巴。
        // 【实测五百四十四】这里是**唯一允许空中起手**的入口：收翅俯冲本来就是"她主动朝敌人
        // 砸下去"的那一瞬（用户口径："这个功能在空中的实战价值更大"）。airborne 直接取当前是否
        // 离地——她已经落地时这一记自动退回地面规则（连"顶着怪推"的那道刹车也一起退回去）。
        if (MaidTridentSpinBehavior.replacesMelee(maid)
                && MaidTridentSpinBehavior.tryStartDash(level, maid, target, !maid.m_20096_())) {
            ATTACK_READY.put(attackId, gameTime + attackCooldown(maid));
            return true;
        }
        ATTACK_READY.put(attackId, gameTime + attackCooldown(maid));
        maid.m_6674_(InteractionHand.MAIN_HAND);
        // v1.2.0 实测五百：拔刀剑（可选模组）——TLM 的拔刀斩触发被硬编码门控在它自己的
        // 攻击任务 UID 上，我们的空袭任务永远进不去，所以在这里补上同款调用。
        // 位置与 TLM 的 MaidMeleeAttack 一致：挥刀之后、原版命中结算之前（反编译实证它
        // 也是 m_6674_ 与 m_7327_ 相邻两条）。缺模组/非拔刀剑时是一次 isLoaded 判断即返回。
        SlashBladeCompat.swingSlash(maid);
        // 1) 主目标：正常结算（附魔 + 暴击 ×1.5 + 击退 + 火焰附加 + 荆棘 + 特效）
        hitOne(level, maid, target);
        // 2) v1.2.0 范围强制命中（"走点后门"提高命中率，用户要求）：她俯冲速度快，按精确
        //    判定框经常判不到；这里对身边 forcedHitRadius() 格内的**其他合法敌对目标**
        //    也结算一次。只认 IAttackTask.canAttack（不会误伤主人/宠物/中立动物），
        //    并且【女仆一律跳过】——女仆免疫这一记重锤友伤。
        try {
            net.minecraft.world.phys.AABB box = maid.m_20191_().m_82400_(forcedHitRadius());
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
            if (task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask attackTask) {
                for (LivingEntity other : level.m_6443_(LivingEntity.class, box, e -> e != maid && e != target)) {
                    if (other instanceof EntityMaid) {
                        continue; // 女仆免疫重锤友伤
                    }
                    if (FriendlyFireGuard.isFriendly(maid, other)) {
                        continue;
                    }
                    if (!attackTask.canAttack(maid, other)) {
                        continue; // 只打合法敌对目标
                    }
                    // v1.2.0 实测五百零五：范围命中也要过视线——旧版按 AABB 直接结算，
                    // 墙后的怪会被这一记"隔墙重锤"打到（与 TLM 原生近战
                    // nearestVisibleLivingEntities.contains 的视线门控不一致）。
                    if (!SelfPreservationBehavior.hasSight(maid, other)) {
                        continue;
                    }
                    hitOne(level, maid, other);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
        ItemStack weapon = maid.m_21205_();
        weapon.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND));
        maid.f_19789_ = 0.0f;
        return true;
    }

    /**
     * 单次命中结算：附魔 → 暴击 ×1.5 → **强制命中** → 击退 + 火焰附加 + 荆棘 + 特效。强制命中 = 若被
     * 目标无敌帧吃掉（刚被别的来源打过）就清无敌帧再打一次，保证"触发了链路攻击就一定打到"。
     * 击退量按原版算式：{@code ATTACK_KNOCKBACK 属性 + 击退附魔}，再 {@code ×0.5f}（旧版写死 1.0、整段
     * 丢了击退附魔，所以"锋利 / 火焰附加生效而击退不生效"）；dx/len 方向归一化写法保持不变。
     */
    private static void hitOne(ServerLevel level, EntityMaid maid, LivingEntity victim) {
        ItemStack weapon = maid.m_21205_();
        float base = (float) maid.m_21133_(Attributes.f_22281_);
        float ench = EnchantmentHelper.m_44833_(weapon, victim.m_6336_());
        boolean critical = isCriticalHit(maid);
        float dmg = critical ? base * 1.5F + ench : base + ench;
        boolean hit = victim.m_6469_(maid.m_269291_().m_269333_(maid), dmg);
        if (!hit && victim.f_19802_ > 0) {
            victim.f_19802_ = 0;
            hit = victim.m_6469_(maid.m_269291_().m_269333_(maid), dmg);
        }
        if (hit) {
            double dx = victim.m_20185_() - maid.m_20185_();
            double dz = victim.m_20189_() - maid.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                // 原版口径：属性击退 + 击退附魔，再 ×0.5（Mob.m_7327_ 同款算式）
                float knockback = (float) maid.m_21133_(Attributes.f_22282_);
                try {
                    knockback += (float) EnchantmentHelper.m_44894_(maid);
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
                }
                victim.m_147240_(knockback * 0.5, dx / len, dz / len);
            }
            int fireAspect = EnchantmentHelper.m_44914_(maid);
            if (fireAspect > 0 && !victim.m_6060_()) {
                victim.m_20254_(fireAspect * 4);
            }
            EnchantmentHelper.m_44823_(victim, maid);
            if (critical) {
                spawnCritParticles(level, victim, 12);
                playCritSound(level, victim);
            }
        }
    }

    /** 原版 Player.attack 的暴击条件（与 1.21.1 同款；滑翔中 fallDistance 被钳在 1.0） */
    private static boolean isCriticalHit(EntityMaid maid) {
        return maid.f_19789_ > 0.0F
                && !maid.m_20096_()
                && !maid.m_6147_()
                && !maid.m_20069_()
                && !maid.m_21023_(net.minecraft.world.effect.MobEffects.f_19610_)
                && !maid.m_20159_()
                && !maid.m_20142_();
    }

    private static void spawnCritParticles(ServerLevel level, LivingEntity target, int count) {
        try {
            net.minecraft.core.particles.SimpleParticleType crit =
                    (net.minecraft.core.particles.SimpleParticleType) ForgeRegistries.PARTICLE_TYPES
                            .getValue(new ResourceLocation("minecraft", "crit"));
            if (crit == null) {
                return;
            }
            double x = target.m_20185_();
            double y = target.m_20186_() + target.m_20206_() * 0.6;
            double z = target.m_20189_();
            level.m_8767_(crit, x, y, z, count, 0.3, 0.2, 0.3, 0.2);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    private static void playCritSound(ServerLevel level, LivingEntity target) {
        try {
            SoundEvent snd = ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation("minecraft", "entity.player.attack.crit"));
            if (snd == null) {
                return;
            }
            level.m_5594_(null, target.m_20183_(), snd, SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /* ---------------- 朝向 ---------------- */

    /**
     * 垂直占位：先爬到目标头上再谈出手。进本相位判据（或）：{@link #onTargetAltitude} 为假（目标高出 10 格以上），
     * 或"本相位点着的这枚烟花推力没烧完"；都不成立才落回原链路。够高后**不能立刻走人**：挂载烟花推力每 tick 跟
     * 视线走，而"抬头朝目标爬"只有本相位在给；一到高度就切走 → 朝向被改平 → 同一枚烟花剩下的 20~30 tick 推力全
     * 摊到水平，刚爬的高度又还回去。本相位自管烟花与朝向：{@code LAUNCH_LEFT} 改写为"这枚烟花还剩多少 tick"，
     * {@code WAIT_LAUNCH} 作废，够高后不再补烟花（否则相位无限续命）；烟花走 {@link #canLaunch} 内置 CD。
     * 远战额外调 {@link #fireRanged}，让它在爬升途中继续输出。
     */
    private void tickClimbToAltitude(ServerLevel level, EntityMaid maid, LivingEntity target,
                                     UUID id, long gameTime) {
        WAIT_LAUNCH.remove(id);
        // 推力倒计时（本相位专属口径，见方法注释）：每 tick 减一，减到 0 就等于
        // "这次的加速跑完了"，够高的话下一 tick 自然交棒给瞄准链路。
        int boostLeft = LAUNCH_LEFT.getOrDefault(id, 0);
        if (boostLeft > 1) {
            LAUNCH_LEFT.put(id, boostLeft - 1);
        } else if (boostLeft == 1) {
            // 烧完就【清键】而不是写 0：`isEngaged` 认的是 containsKey，
            // 留一个 0 值挂在表里会让"本轮正在打"永远为真（自动传送就再也唤不回她）。
            LAUNCH_LEFT.remove(id);
        }
        suppressVanillaMelee(maid);
        // 离地就保持滑翔位：挂载型烟花只对"正在滑翔"的实体给推力
        MaidFlightKit.setGliding(maid, true);
        // 朝目标水平方向 + 抬头（角度与起飞同口径：近战 62° / 远战 45°）
        faceUpForward(maid, target, climbPitch());
        if (this.ranged) {
            fireRanged(maid, target, id, gameTime);
        }
        // 【实测五百二十八】只在"还差高度"时补烟花：够高了就绝不再点火，
        // 免得本相位被自己续命、往天上无限爬。
        if (!onTargetAltitude(maid, target) && canLaunch(maid, gameTime)
                && tryLaunch(level, maid, target, id, gameTime)) {
            CLIMB_BOOST.add(id); // 这枚推力没烧完之前不离开本相位
        }
    }

    /**
     * 起飞 / 爬升共用的抬头角度（MC 约定：负 = 抬头）。近战 62° / 远战 45°。
     * 两种模式必须分开取值（实测四百七十二）：共用远战那套 45° 会让近战爬升腰斩、贴地摔死。
     */
    private float climbPitch() {
        double climbTan = this.ranged ? launchClimbTanRanged() : launchClimbTanMelee();
        return (float) (-Math.toDegrees(Math.atan(climbTan)));
    }

    /**
     * 是否已"占位"——高度是否追到目标身下 {@link #altitudeTolerance()} 格（现 10）以内。空袭唯一的
     * 垂直判据：只有目标高出她 **10 格以上**才值得专门爬（那种高度差原链路俯冲 / 盘旋够不着），10 格
     * 以内原链路本就能应付，直接落回"瞄准敌人"。每 tick 重算，故爬升途中一够高就马上转段。
     */
    private static boolean onTargetAltitude(EntityMaid maid, LivingEntity target) {
        return maid.m_20186_() >= target.m_20186_() - altitudeTolerance();
    }

    /**
     * 起飞 / 起跳时的朝向，判据就是 {@link #onTargetAltitude}：
     * · 目标高出 10 格以上（{@link #altitudeTolerance()} 之外）→ 朝目标 + 抬头（"顺着敌人方向爬上去"）；
     * · 其余（差 10 格内 / 等高 / 已在它头上）→ 老口径"背离 + 向上"——那是为地面目标设计的：爬升时别站
     *   在对方近战 / 爆炸包线里（10 格以内本就归原链路管）。
     * 旧版① 起飞门槛曾用 3D 距离；② 曾只有"目标高过 8 格"才朝它爬，之下高度差一律"背离 + 向上"，目标
     * 在半空时她一边爬一边越飞越远，还得掉头回来、全程烧烟花。
     */
    private void faceLaunchDirection(EntityMaid maid, LivingEntity target) {
        if (!onTargetAltitude(maid, target)) {
            faceUpForward(maid, target, climbPitch());
            return;
        }
        faceAwayAndUp(maid, target);
    }

    /**
     * 阶段一 / 起跳朝向 = 敌人反方向的水平朝向 + 向上（近战 62° / 远战 45°）——推力与滑翔都吃它。
     * 近战 / 远战必须分开取值：共用远战那套 45°/20t 会让近战爬升腰斩、贴地摔死。
     * 抗 LookControl 归零：{@code LookControl.tick} 每 tick 在 {@code resetXRotOnTick()}（字节码恒真）
     * 时执行 {@code setXRot(0f)}，把 {@code lookAt} 刚写的抬头角抹掉 → 推力只吃水平分量 → 平飞不爬。
     * 做法：{@code lookAt} 之后再用 {@code applyRotation()} + {@code getLookControl().setLookAt(...)} 对抗。
     */
    private void faceAwayAndUp(EntityMaid maid, LivingEntity target) {
        double climbTan = this.ranged ? launchClimbTanRanged() : launchClimbTanMelee();
        double dx = maid.m_20185_() - target.m_20185_();
        double dz = maid.m_20189_() - target.m_20189_();
        double dh = Math.sqrt(dx * dx + dz * dz);
        if (dh < 1.0E-4) {
            double yawRad = maid.m_146908_() * (Math.PI / 180.0);
            dx = Math.sin(yawRad);
            dz = -Math.cos(yawRad);
            dh = 1.0;
        }
        double ux = dx / dh;
        double uz = dz / dh;
        // 【实测七百〇三：空中防叠罗汉——只偏**爬升**方位】多只女仆同时接同一个敌人时，
        //  这一段（背离敌人抬头爬升，近战 30 tick = 1.5 秒）原本飞的是**同一条直线**——
        //  那就是在近战空袭这边的形态。给每只女仆一个只跟 UUID 有关的
        //  稳定偏置（±12°）之后，她们的爬升线天然叉开。
        //  【为什么只在这里偏】俯冲那一记的朝向由 faceTarget 给（瞄准方向），偏一点点都会
        //  掉命中率——这个模组在 实测四百七十三 / 四百八十三 上专门修过两轮，不会为了好看毁掉它。
        //  爬升朝向本来就是"背离敌人"这种不需要精度的方向，偏十几度没有任何代价。
        double spreadDeg = 0.0;
        if (!this.ranged && airSeparationEnabled()) {
            // 只给近战那一档偏：远程的爬升期同时也在开火（实测六百八十二），偏了会影响输出。
            spreadDeg = CombatManeuver.azimuthSpread(maid.m_20148_(), CLIMB_SPREAD_DEG);
        }
        // 与 1.21.1 同款：直接算角度并【立即写入实体】——只靠 lookAt 会被 LookControl 抹掉
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f + (float) spreadDeg;
        float pitch = (float) (-Math.toDegrees(Math.atan(climbTan)));
        applyRotation(maid, yaw, pitch);
        // 同时把"期望角度"交给 LookControl：它的 tick 会按这个期望值重新施加，
        // 抵消每 tick 的 xRot 归零（少了这步，下一 tick 又会变回水平）
        // 【偏置也要过 LookControl】否则"期望角度"每 tick 把偏置拉回 0，实机看不到任何错开。
        double yawRad = Math.toRadians(yaw + 90.0);
        try {
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + Math.cos(yawRad), maid.m_20188_() + climbTan,
                    maid.m_20189_() + Math.sin(yawRad),
                    360.0f, 360.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 近战空袭"先拉开距离"那一段的朝向：**背离敌人 + 水平**（俯仰 0）。与 {@link #faceAwayAndUp} 只差
     * 俯仰——那是爬升用（62° 抬头换高度），这条是"高度已占好位、只想把水平距离拉出去"时用；抬头会掉
     * 水平速度、还把她顶更高（更易掉出高度判据，也拖长俯冲）。
     * 平飞即能拉开：滑翔物理里视线就是操纵杆（原版 {@code travel} 滑翔分支沿视线加速并做水平对齐），把
     * 视线摆成"敌人反方向的水平向量"她自然滑走；这一段不点火、不额外烧燃料。
     * 抗 LookControl 归零同 {@link #faceAwayAndUp}（两版本都恒真）。
     */
    private void faceAwayFlat(EntityMaid maid, LivingEntity target) {
        double dx = maid.m_20185_() - target.m_20185_();
        double dz = maid.m_20189_() - target.m_20189_();
        double dh = Math.sqrt(dx * dx + dz * dz);
        if (dh < 1.0E-4) {
            double yawRad = maid.m_146908_() * (Math.PI / 180.0);
            dx = Math.sin(yawRad);
            dz = -Math.cos(yawRad);
            dh = 1.0;
        }
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        applyRotation(maid, yaw, 0.0f);
        double yawRad = Math.toRadians(yaw + 90.0);
        try {
            maid.m_21563_().m_24950_(maid.m_20185_() + Math.cos(yawRad), maid.m_20188_(),
                    maid.m_20189_() + Math.sin(yawRad), 360.0f, 360.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /** 「拉开距离」日志限频（每只女仆 5 秒至多一条）——它是每 tick 可能命中的常态段，不节流会刷屏 */
    private static final Map<UUID, Long> STANDOFF_LAST_LOG = new HashMap<>();

    /** 「高度地板」日志限频（每只女仆 5 秒至多一条）——低于地板时每 tick 都会命中，不节流会刷屏 */
    private static final Map<UUID, Long> FLOOR_LAST_LOG = new HashMap<>();

    /** 【实测七百〇六】低于「离敌最低高度」→ 写一行「高度地板」（节流 5 秒），供实机核对。 */
    private void logHeightFloor(EntityMaid maid, double above) {
        try {
            long now = System.currentTimeMillis();
            Long last = FLOOR_LAST_LOG.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            FLOOR_LAST_LOG.put(maid.m_20148_(), now);
            com.maidsmart.tool.PromaidLog.log("远程空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 高度地板：只比敌人高 " + fmt2(above)
                            + " 格 < airRaid.minAboveHeight " + fmt2(airMinAboveHeightCfg())
                            + " 格 → 俯仰钉成最大抬头并立即补推（不再继续下沉）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 【实测七百〇四】"先拉开距离"这一段的**每轮预算**（tick，= 2 秒）——见 {@code m_6725_} 近战那一支。
     *
     * <p>没有这道上限，她在墙角 / 追同速飞行目标时会一直平飞、永远不俯冲（把"贴脸硬碰"变成"永远不打"）。
     */
    private static final int STANDOFF_MAX_TICKS = 40;
    /** 女仆 UUID → 本场遭遇"拉开距离"已经走了多少 tick */
    private static final Map<UUID, Integer> STANDOFF_TICKS = new HashMap<>();
    /**
     * 本场遭遇**已经拉开过**、接下来一律放行俯冲的女仆。把"拉开距离"从**每 tick 复评**改成**每场遭遇
     * 一次**：旧版"拉开用水平距离、俯冲闸门用 3D 距离，且预算用完就归零"→ 她永远停在 6 格线上抖动、
     * 一次都压不下去（实测 27 行「拉开」、0 行猛击）。现在"够远"或"预算用完"即置位，本轮余下时间全让给
     * 俯冲；由 {@link #endSmash}（一轮收尾）摘标记，故**下一轮**照样能重新拉开。
     */
    private static final Set<UUID> STANDOFF_DONE = new HashSet<>();

    private void logStandoff(EntityMaid maid, double dist) {
        try {
            long now = System.currentTimeMillis();
            Long last = STANDOFF_LAST_LOG.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            STANDOFF_LAST_LOG.put(maid.m_20148_(), now);
            com.maidsmart.tool.PromaidLog.log("近战空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 拉开距离：距敌 " + fmt2(dist)
                            + " 格 < airRaid.minStandoff " + fmt2(airMinStandoffCfg())
                            + " 格 → 先背离平飞，拉开了再俯冲（本轮只拉开这一次；瞄准与命中判定不变）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /** 【实测七百〇六】"拉开"预算用完（墙角 / 敌人同速追）→ 本轮放弃拉开、直接俯冲，写一行说明。 */
    private void logStandoffGiveUp(EntityMaid maid, double dist) {
        try {
            long now = System.currentTimeMillis();
            Long last = STANDOFF_LAST_LOG.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            STANDOFF_LAST_LOG.put(maid.m_20148_(), now);
            com.maidsmart.tool.PromaidLog.log("近战空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 放弃拉开：拉开这么久仍只有 "
                            + fmt2(dist) + " 格（墙角 / 目标同速）→ 本轮直接俯冲，不再横飞");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 【实测七百〇三】近战空袭爬升方位的最大偏置（度，±12）。
     *
     * <p>取 12° 的理由：① 它足够把两只女仆在 1.5 秒爬升里的水平距离拉开约
     * {@code 2 × sin(6°) × 爬升水平位移}——十几格的水平位移下就是 1~2 格，肉眼可见、
     * 又不至于让她们"各飞各的"；② 再大就会让"背离敌人"变成"侧着飞"，爬升效率下降
     * （近战那一档的 62° 仰角是 实测四百七十二 调出来的，水平分量不能随便变大）。
     */
    private static final double CLIMB_SPREAD_DEG = 12.0;

    /**
     * 立即把身体（含头/身朝向）转到指定角度。
     *
     * v1.2.0 实测四百八十一：与 1.21.1 的 `applyRotation` 对齐。必须连 `f_19859_/f_19860_`
     * （yRotO/xRotO）一起写——渲染插值用的是 O 值，只写当前值会让模型在"上一帧角度→本帧角度"
     * 之间插值，看起来仍是旧的（水平）朝向。
     */
    private static void applyRotation(EntityMaid maid, float yaw, float pitch) {
        maid.m_146922_(yaw);
        maid.m_146926_(pitch);
        maid.f_19859_ = yaw;
        maid.f_19860_ = pitch;
        maid.m_5616_(yaw);
        maid.m_5618_(yaw);
    }

    /**
     * v1.2.0 实测四百七十三【命中率】：目标是否在本 tick 的**位移线段**范围内。
     *
     * 俯冲速度约 1~2 格/tick，只比较"当帧中心距"时，判定框虽大，但她一整段可能
     * 从目标侧上方掠过——每一帧都不在半径内，于是整套俯冲白打。这里把"上一帧
     * 位置 → 本帧位置"连成线段，取目标到线段的最短距离，擦身而过的那一帧也算
     * 命中（与弹射物扫掠判定同思路）。
     */
    private static boolean sweepWithin(EntityMaid maid, LivingEntity target, double range) {
        try {
            // v1.2.0 实测四百八十三【同步 1.21.1】：xo/yo/zo 未初始化时（刚生成/刚传送，
            // 三者全 0）线段会从世界原点拉到当前位置 → 扫掠判定被放大成超大命中范围
            // （等于放宽猛击命中）。这种情形退回"当前位置"这一退化为点的线段。
            Vec3 from = (maid.f_19854_ == 0.0 && maid.f_19855_ == 0.0 && maid.f_19856_ == 0.0)
                    ? maid.m_20182_()
                    : new Vec3(maid.f_19854_, maid.f_19855_, maid.f_19856_);
            Vec3 to = maid.m_20182_();
            Vec3 seg = to.m_82546_(from);
            double lenSqr = seg.m_82553_();
            Vec3 p = new Vec3(target.m_20185_(), target.m_20186_() + target.m_20206_() * 0.5, target.m_20189_());
            Vec3 rel = p.m_82546_(from);
            double tt = lenSqr < 1.0E-6 ? 0.0 : Math.max(0.0, Math.min(1.0, rel.m_82526_(seg) / lenSqr));
            Vec3 closest = from.m_82549_(seg.m_82490_(tt));
            return p.m_82531_(closest.f_82479_, closest.f_82480_, closest.f_82481_) <= range * range;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return maid.m_20270_(target) <= range;
        }
    }

    /**
     * 阶段二/猛击段：把身体对准目标。滑翔的操纵杆就是视线方向
     * （原版 travel 的滑翔分支按 getLookAngle 加速），不做人工转弯。
     *
     * v1.2.0 实测四百八十一：与 1.21.1 对齐——直接算 yaw/pitch 并立即写入，
     * 只依赖 `m_7618_` 会被 `LookControl` 的每 tick xRot 归零抹掉（详见 faceAwayAndUp）。
     */
    private void faceTarget(EntityMaid maid, LivingEntity target) {
        double dx = target.m_20185_() - maid.m_20185_();
        double dz = target.m_20189_() - maid.m_20189_();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double eyeT = target.m_20186_() + target.m_20206_() * 0.5;
        double eyeM = maid.m_20186_() + maid.m_20206_() * 0.5;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Math.atan2(eyeT - eyeM, Math.max(1.0E-4, dh)) * (180.0 / Math.PI)));
        pitch = Math.max(-maxPitchUp(), Math.min(maxPitchDown(), pitch));
        applyRotation(maid, yaw, pitch);
        try {
            maid.m_21563_().m_24960_(target, 360.0f, 360.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /* ---------------- 退路 ---------------- */

    /** 地面近战（模式未激活 / 目标贴身 / 太远先跑近时使用） */
    private void groundMelee(ServerLevel level, EntityMaid maid, LivingEntity target, long gameTime) {
        try {
            faceTarget(maid, target);
            double dist = maid.m_20270_(target);
            if (dist > meleeReach()) {
                maid.m_21573_().m_5624_(target, 1.0);
                return;
            }
            Long ready = GROUND_READY.get(maid.m_20148_());
            if (ready != null && gameTime < ready) {
                return;
            }
            if (hasUsableRangedWeapon(maid)) {
                return;
            }
            // v1.2.0 实测五百零五：地面近战同样要过视线（否则隔墙挥空，与 TLM 原生
            // 近战的视线门控不一致）。看不见就走过去，别对着墙砍。
            if (!SelfPreservationBehavior.hasSight(maid, target)) {
                maid.m_21573_().m_5624_(target, 1.0);
                return;
            }
            // 实测五百三十八 / 五百四十一：持激流三叉戟时，地面近战换成旋转冲击；
            // 起不了手（硬直中 / 超出突进距离）就照常挥砍，别让她变成哑巴。
            if (MaidTridentSpinBehavior.replacesMelee(maid)
                    && MaidTridentSpinBehavior.tryStartDash(level, maid, target)) {
                double atkSpeed = maid.m_21133_(Attributes.f_22283_);
                long cd = atkSpeed > 0.0 ? (long) (20.0 / atkSpeed) : 20L;
                GROUND_READY.put(maid.m_20148_(), gameTime + Math.max(1L, cd));
                return;
            }
            maid.m_6674_(InteractionHand.MAIN_HAND);
            // v1.2.0 实测五百：地面退路同样补拔刀斩（与 smashHit 同一口径——"空袭不适配
            // 拔刀剑"包含她落地后那段贴身近战，只补俯冲那一记会留下"落地就变哑巴"的半截体验）
            SlashBladeCompat.swingSlash(maid);
            maid.m_7327_(target);
            double atkSpeed = maid.m_21133_(Attributes.f_22283_);
            long cd = atkSpeed > 0.0 ? (long) (20.0 / atkSpeed) : 20L;
            GROUND_READY.put(maid.m_20148_(), gameTime + Math.max(1L, cd));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * v1.2.0 实测四百八十一【同步 1.21.1 的飞行优化】：
     * 压制原版近战——给 Brain 写一条较长的 `ATTACK_COOLING_DOWN`，让 TLM 自带的
     * 近战行为在飞行期间不插手（否则她会一边滑翔一边被原版近战逻辑拽过去挥砍，
     * 与"滑翔靠视线当操纵杆"打架）。1.21.1 早有这套，1.20.1 当时没同步。
     */
    private static void suppressVanillaMelee(EntityMaid maid) {
        try {
            // f_26373_ = ATTACK_COOLING_DOWN（官方 mappings 实证：obf p → f_26373_；
            // 同一张表里 ATTACK_TARGET 是 f_26372_，与项目既有用法一致，可交叉验证）
            maid.m_6274_().m_21882_(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26373_, true, 40L);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    private static boolean hasUsableRangedWeapon(EntityMaid maid) {
        try {
            // 枪械也算"远程武器"——地面退路时不该拿枪去挥砍（与弓同口径）
            if (GunCompat.isGun(maid.m_21205_())) {
                return true;
            }
            return maid.m_21093_(stack -> {
                net.minecraft.world.item.Item item = stack.m_41720_();
                return item instanceof net.minecraft.world.item.ProjectileWeaponItem
                        && maid.m_5886_((net.minecraft.world.item.ProjectileWeaponItem) item);
            });
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return false;
        }
    }

    private static boolean isNearGround(ServerLevel level, EntityMaid maid) {
        BlockPos p = maid.m_20183_();
        for (int i = 0; i <= 1; i++) {
            BlockPos q = new BlockPos(p.m_123341_(), p.m_123342_() - i, p.m_123343_());
            if (!level.m_8055_(q).m_60795_()) {
                return true;
            }
        }
        return false;
    }

    /** 女仆的攻击间隔（tick）：与普通近战同口径 = 20 / 攻击速度（TLM MaidMeleeAttack 同款） */
    private static long attackCooldown(EntityMaid maid) {
        try {
            double speed = maid.m_21133_(Attributes.f_22283_);
            return speed > 0.0 ? Math.max(1L, (long) (20.0 / speed)) : 20L;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 20L;
        }
    }

    /* ---------------- 飞行远战：空中盘旋（用户指定） ---------------- */

    /** 盘旋半径（格） */
    private static double orbitRadius() {
        return MaidSmartConfig.AIR_RAID_ORBIT_RADIUS.get();
    }
    /**
     * 期望盘旋高度（格，目标上方）。曾从 6 降到 3.5（"飞太高锁不到敌"），后按"改为目标高度以上 10 格"
     * 改回 **10**——3.5 的高度带太薄：滑翔转弯本身就掉高度，一掉就低于敌人（观感差、也不安全，远程空袭
     * 的核心要求是"脚不沾地"）。10 格 + {@link #rangedBoostDrop()}（低于带 0.5 格就补）才留得出余量。
     * 锁敌 / 开火不受影响：射程判据是 3D 的 {@link #rangedAttackRange()}（24 格），"目标上方 10 格"的
     * 3D 距离约 14 格，仍在射程内。
     */
    private static double rangedHoldHeight() {
        return MaidSmartConfig.AIR_RAID_RANGED_HOLD_HEIGHT.get();
    }
    /** 高度偏差 → 俯仰角增益（度/格）：低了抬头把速度换成高度、高了低头把高度换成速度 */
    private static double rangedHoldGain() {
        return MaidSmartConfig.AIR_RAID_RANGED_HOLD_GAIN.get();
    }
    /** 高度偏置（格）：抵消滑翔的固定下沉与转弯损耗，让平衡点落在略抬头处 */
    private static double rangedHoldBias() {
        return MaidSmartConfig.AIR_RAID_RANGED_HOLD_BIAS.get();
    }
    /**
     * 低于期望高度这么多格就补推（5 秒间隔仍是下限，避免浪费燃料）。取 0.5 = "比期望高度带低半格就补"。
     * 旧值 3.0 与当时期望高度 3.5 相消 ⇒ 触发点 = 目标脚下 +0.5 格（对地面目标等于"已经落地"），该分支
     * 永远走不到，直接掉到地上再走地面支点重新起飞。
     */
    private static double rangedBoostDrop() {
        return MaidSmartConfig.AIR_RAID_RANGED_BOOST_DROP.get();
    }
    /** 盘旋俯仰限幅（度） */
    private static float rangedOrbitUpMax() {
        return MaidSmartConfig.AIR_RAID_RANGED_ORBIT_UP_MAX.get().floatValue();
    }
    private static float rangedOrbitDownMax() {
        return MaidSmartConfig.AIR_RAID_RANGED_ORBIT_DOWN_MAX.get().floatValue();
    }
    /** 补烟花的间隔下限（tick）——用户要求 5 秒 */
    private static int rangedBoostInterval() {
        return MaidSmartConfig.AIR_RAID_RANGED_BOOST_INTERVAL.get();
    }
    /** 补烟花时"抬头窗口"的 tick 数——v1.2.0 实测四百六十九：20 → 10（提前转圈，
     *  别把整枚烟花的推力都用去爬高，只借前半段升一点就回到盘旋） */
    private static int rangedBoostAimTicks() {
        return MaidSmartConfig.AIR_RAID_RANGED_BOOST_AIM_TICKS.get();
    }
    /** 抬头窗口的仰角（度）——v1.2.0 实测四百六十九：55° → 45°（配合用户"角度调整到 45 度"） */
    private static float rangedBoostPitch() {
        return MaidSmartConfig.AIR_RAID_RANGED_BOOST_PITCH.get().floatValue();
    }
    /** 开火间隔（tick） */
    private static int rangedShotCooldown() {
        return MaidSmartConfig.AIR_RAID_RANGED_SHOT_COOLDOWN.get();
    }
    /** 开火的最大距离（格） */
    private static double rangedAttackRange() {
        return MaidSmartConfig.AIR_RAID_RANGED_ATTACK_RANGE.get();
    }

    /**
     * 【实测六百八十二】有效开火距离（格；0 = 不限）：超出它就不扣扳机，改为继续盘旋把她拉近。
     *
     * <p>需求原文："远程空袭状态下……女仆在此状态下飞的太远后打枪的准度特别的低。"
     * 取值口径（为什么默认 24）见配置项 {@code airRaid.rangedFireRange} 的注释；读不到配置时
     * 退回 24（与默认值对齐——本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double rangedFireRange() {
        try {
            return MaidSmartConfig.AIR_RAID_RANGED_FIRE_RANGE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 24.0;
        }
    }

    /**
     * 远程空袭的"近身弹开"触发半径（格）：怪物贴到这么近就给她一个**远离怪物**的速度矢量，防止她远程
     * 攻击时仍往敌人身上飞、下落途中被贴脸打死。弹开自己（而非敌人）更平衡——弹开敌人等于远程角色获得
     * 无需操作的持续群体击退，太超模；而且弹自己在狭小空间里跑不掉，敌人仍有机会命中。
     */
    private static double rangedPushRadius() {
        return MaidSmartConfig.AIR_RAID_RANGED_PUSH_RADIUS.get();
    }
    /**
     * 弹开的水平速度（格/tick）。取 0.55 的理由：
     * - 鞘翅滑翔的水平巡航速度大致就在 0.4~0.8 区间，0.55 足以在一两 tick 内把
     *   "向敌飞"的矢量**反向压过去**，但她仍是一条连续的弧线而非瞬移；
     * - 刻意**不用** impulse 式的 1.0+：那会让女仆被"弹飞"，既不像盘旋也不平衡。
     */
    private static double rangedPushSpeed() {
        return MaidSmartConfig.AIR_RAID_RANGED_PUSH_SPEED.get();
    }
    /** 弹开时附加的向上分量（格/tick）——顺手把高度抬一点，脱离怪物的近战竖直包线 */
    private static double rangedPushUp() {
        return MaidSmartConfig.AIR_RAID_RANGED_PUSH_UP.get();
    }
    /**
     * 弹开后的"冷却/持续"时长（tick）。这段时间内**持续施加**远离矢量（不是打一枪就完），
     * 否则下一 tick 盘旋的向敌分量会立刻把速度拉回去、等于没弹。
     * 30 tick = 1.5 秒，与 {@link #fireworkCooldown()} 同量级——足够飘出怪物的一次攻击间隔。
     */
    private static int rangedPushTicks() {
        return MaidSmartConfig.AIR_RAID_RANGED_PUSH_TICKS.get();
    }
    /** 弹开日志限频（毫秒）——这是高频事件，绝不能让日志被它刷屏 */
    private static final long RANGED_PUSH_LOG_INTERVAL_MS = 5000L;

    private static final Map<UUID, Integer> RANGED_PUSH_LEFT = new HashMap<>();
    private static final Map<UUID, Long> RANGED_PUSH_LAST_LOG = new HashMap<>();

    private static final Map<UUID, Integer> RANGED_BOOST_LEFT = new HashMap<>();
    private static final Map<UUID, Long> RANGED_NEXT_BOOST = new HashMap<>();
    private static final Map<UUID, Long> RANGED_NEXT_SHOT = new HashMap<>();

    /**
     * 本次开火后的间隔（tick）。飞行时没有蓄力动作，就把原版弩蓄力时长 {@code 25 - 5×快速装填等级} 的
     * 相对幅度搬到开火间隔上：{@code 间隔 = 20 × (25-5L)/25}（III 级 20→8 tick，与"25→10"同为 2.5 倍）。
     * 下限 4 tick，避免高等级叠出荒谬射速；弓无射速类附魔，一律走基础间隔。
     */
    private static int rangedShotCooldown(ItemStack weapon) {
        if (!(weapon.m_41720_() instanceof net.minecraft.world.item.CrossbowItem)) {
            return rangedShotCooldown();
        }
        int quickCharge = 0;
        try {
            quickCharge = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44843_(
                    net.minecraft.world.item.enchantment.Enchantments.f_44960_, weapon);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
        if (quickCharge <= 0) {
            return rangedShotCooldown();
        }
        int cd = (int) Math.round(rangedShotCooldown() * (25.0 - 5.0 * quickCharge) / 25.0);
        return Math.max(4, Math.min(rangedShotCooldown(), cd));
    }

    /** v1.2.0 实测四百六十八：枪械开火冷却（tick）——由 performGunAttack 的返回值驱动 */
    private static final Map<UUID, Integer> RANGED_GUN_CD = new HashMap<>();
    /** v1.3.0 实测六百六十六：枪械状态机本 tick 是否已推进过（见 {@link #tickGunState} 的幂等说明） */
    private static final Map<UUID, Long> GUN_STATE_TICKED = new HashMap<>();
    /** v1.2.4 实测六百四十三：远程开火诊断节流（每 2 秒一条，latest.log 搜「远程开火」） */
    private static final Map<UUID, Long> RANGED_FIRE_LAST_LOG = new HashMap<>();
    private static final long RANGED_FIRE_LOG_INTERVAL = 40L;

    /**
     * 远程开火诊断行（节流：同一只女仆最多 2 秒一行）。
     *
     * 【为什么必须有】实测六百四十三 之前，"她拿着枪 / 弓在空袭里一枪不放"这件事在日志里
     * **完全查不到**——只有一条"空战装备不齐，没有弹药"的气泡，而真正的闸门（TLM 的枪型
     * 距离档）连一行都不写，现场只能靠猜。现在每一档不出手的原因（超出射程 / 视线被挡 /
     * 等冷却 / TLM 返回值）都会印出来，与「空袭索敌」「空袭装备」同一风格，直接 grep 复核。
     */
    private static void logFireThrottled(EntityMaid maid, UUID id, long gameTime, String msg) {
        try {
            Long last = RANGED_FIRE_LAST_LOG.get(id);
            if (last != null && gameTime - last < RANGED_FIRE_LOG_INTERVAL) {
                return;
            }
            RANGED_FIRE_LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("远程开火",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }
    /** 退出模式时交给 TLM 收枪用的一次性 task 实例（见 stopGunAim） */
    private static final com.github.tartaricacid.touhoulittlemaid.compat.gun.common.ai.GunShootTargetTask
            GUN_STOP_TASK = new com.github.tartaricacid.touhoulittlemaid.compat.gun.common.ai.GunShootTargetTask();

    /**
     * 飞行远战的空中阶段：起飞后不再扑击，而是**持续在天上盘旋（类似幻翼）**并用**手持远程武器**在锁敌
     * 范围内开火——弓弩走 TLM 自己的 {@code performRangedAttack} 通道（本任务实现 IRangedAttackTask），
     * 枪械（TACZ / 卓越前线）走 TLM 枪械通道（见 {@link #tickGunFire}）。击败敌人后行为自然结束、清滑翔
     * 落地（与近战一致）。
     * 高度维持两层：① 盘旋时按"与期望高度的偏差"给俯仰（纯滑翔转弯也掉高度，固定 -6° 顶不住）；
     * ② 真掉出高度带才补烟花（抬头 55° 放），5 秒间隔只是下限，不再无条件每 5 秒烧一枚。
     */
    private void tickRangedAir(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        MaidFlightKit.setGliding(maid, true);

        // 期望盘旋高度（目标上方 rangedHoldHeight() 格）
        double holdY = target.m_20186_() + rangedHoldHeight();

        // 【实测七百〇六：高度地板】
        //
        //  旧版只有"软回正"（按高度误差给俯仰）+ "掉高补推 5 秒冷却"——三段叠加让高度在带内
        //  持续锯齿、偶尔探到 8 格以下甚至只剩一两格。这里加一条**硬地板** `floorY`：
        //  ① 低于它就算"掉高"（不必等掉出 10 格带），补推的冷却一好就补；
        //  ② 盘旋俯仰直接钉成最大抬头（见下面 ④），于是她立刻转入爬升、绝不会继续往下滑。
        double floorY = target.m_20186_() + airMinAboveHeightCfg();
        boolean belowFloor = airMinAboveHeightCfg() > 0.0 && maid.m_20186_() < floorY;
        if (belowFloor) {
            logHeightFloor(maid, maid.m_20186_() - target.m_20186_());
        }

        // ① 按需补推：只在真的掉出高度带时才补（5 秒间隔只是下限）
        int boostLeft = RANGED_BOOST_LEFT.getOrDefault(id, 0);
        boolean tooLow = belowFloor || maid.m_20186_() < holdY - rangedBoostDrop();
        if (boostLeft <= 0 && tooLow && gameTime >= RANGED_NEXT_BOOST.getOrDefault(id, 0L)) {
            // 实测五百七十八【A 方案】第一顺位是**位移法术**：不消耗燃料、也不占烟花冷却，
            // "能像玩家那样持续飞很久"就落在这一条上；它成功时会自己开同一个抬头窗口。
            if (tryDashHold(maid, target, id, gameTime)) {
                boostLeft = RANGED_BOOST_LEFT.getOrDefault(id, 0);
            } else if (cfgRangedBoostRiptide()
                    && MaidRiptideBoost.liftForRanged(level, maid, target, rangedBoostPitch())) {
                // 激流抬升的第二条腿：**先抬头再推**才能抬升（射线方向就是这一口的矢量方向；盘旋期视线是
                // "绕圈切线"，照它推只在圈上窜一下、抬不起来）。仰角与烟花 / 法术共用 airRaid.rangedBoostPitch，
                // 并开同一个抬头窗口（吃到推力后 0.5 秒仍按该角度抬着飞）。
                // 现在是**拟真烟花**：先抬头摆好视线，之后每 tick 把速度矢量往"视线×当前力度"上拉（力度照
                // 玩家水里那一记 ×1.3，按水阻力 ×0.80 递减），故竖直分量也被钉在视线方向上、随力度收敛，
                // 不再"一口气顶到 100 格外"（III 级行程约 17 格）。排在烟花 / 羽扇**之前**：它只扣三叉戟耐久，
                // 而烟花是真要烧掉一枚。
                RANGED_NEXT_BOOST.put(id, gameTime + rangedBoostInterval());
                RANGED_BOOST_LEFT.put(id, rangedBoostAimTicks());
                boostLeft = rangedBoostAimTicks();
                com.maidsmart.tool.PromaidLog.log("远程空袭",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 掉高挥激流三叉戟");
            } else if (canLaunch(maid, gameTime)) {
                // 实测五百六十三：扇子优先——掉高时挥羽扇维持高度（推进/扣耐久照搬扇子
                // 自己，节奏 20t），没扇才烧烟花
                if (TwilightFanKit.hasFan(maid) && TwilightFanKit.boostGlide(level, maid)) {
                    FIREWORK_READY.put(id, gameTime + fanCooldown());
                    RANGED_NEXT_BOOST.put(id, gameTime + rangedBoostInterval());
                    RANGED_BOOST_LEFT.put(id, rangedBoostAimTicks());
                    boostLeft = rangedBoostAimTicks();
                    com.maidsmart.tool.PromaidLog.log("远程空袭",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 掉高挥羽扇");
                } else {
                    ItemStack fw = MaidFlightKit.takeFirework(maid);
                    if (!fw.m_41619_()) {
                        launchFirework(level, maid, fw, false);
                        // v1.2.0 实测五百一十一/五百一十四：副手"亮一下"实际消耗的那枚烟花
                        FlightFireworkPose.show(maid, fw);
                        FIREWORK_READY.put(id, gameTime + fireworkCooldown());
                        RANGED_NEXT_BOOST.put(id, gameTime + rangedBoostInterval());
                        RANGED_BOOST_LEFT.put(id, rangedBoostAimTicks());
                        boostLeft = rangedBoostAimTicks();
                        com.maidsmart.tool.PromaidLog.log("远程空袭",
                                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 掉高补烟花");
                    }
                }
            }
        }

        // ② 开火（必须放在"朝向"之前——枪械开火会自己把身体拧向目标，随后要把盘旋朝向
        //    盖回去，否则滑翔会顺着那次瞄准把女仆直接拉向目标，"盘旋"就散了）
        //    法术同款（实测五百六十七，PR #8 移植）：法术模组的吟唱也会拧朝向，所以同样
        //    放在"朝向"之前一起被盖回去。
        tryCastSpell(maid, target, id, gameTime);
        fireRanged(maid, target, id, gameTime);
        // v1.2.2 实测五百八十七：盘旋期间的附加链路——TNT + 点火料（打火石 / 烈焰弹）齐了才扔，
        // 缺料静默跳过。
        // v1.2.2 实测六百〇三：这一步现在**还负责轰炸起手**（需求原文："将重生锚之类的放置也
        // 加入到远程空袭，同时走后门让它在空中也可以放置"）——先试放重生锚 / 末地水晶 / 床，
        // 材料齐就交给相位（相位收尾自己投 TNT），没起手才就地投 TNT，与近战猛击那条路对称。
        // 放在开火之后、朝向之前（同"开火必须早于朝向"的道理：投掷会给命中方向一点朝向扰动）。
        MaidBombing.tickRangedTnt(level, maid, target, id, gameTime);

        // 近身弹开：怪物贴到 3 格内就给一个"远离怪物"的速度矢量，并在 rangedPushTicks() 内**持续**生效。
        // 必须"同时改速度 + 改朝向"：滑翔物理（原版 travel 的 isFallFlying 分支）每 tick 把水平速度往
        // "视线方向"拽 10%，只压速度不改朝向 → 一两 tick 就被拉回"朝敌人"（等于没弹）；只改朝向不压速度
        // → 起步太慢。两者齐给才"立刻离开 + 持续保持"。正因这个转向力，朝向与推力必须引用**同一个**威胁
        // （朝 A 飞、被 B 推会自相拉扯），所以 pushAwayFromThreat 把威胁对象一并返回。
        LivingEntity pushFrom = pushAwayFromThreat(level, maid, id);
        if (pushFrom != null) {
            // 背离该威胁 + 抬头：抬头既脱离怪物的近战竖直包线，也让滑翔的转向力
            // 与推力同向（都指向"离开"）。
            faceAwayAndUp(maid, pushFrom);
            return;
        }

        // ④ 朝向：抬头窗口内抬头爬升，其余时间绕目标盘旋 + 高度保持
        if (boostLeft > 0) {
            RANGED_BOOST_LEFT.put(id, boostLeft - 1);
            // 【实测五百七十八】朝向先摆（冲量沿视线走，必须在本 tick 的施法之前）：
            // 这就是烟花掉高窗口那 45°，"提供速度"那一口因此与烟花的推力同向。
            faceUpForward(maid, target, rangedBoostPitch());
            // "提供速度"只在窗口内放（旧版挂在盘旋相位的每一 tick，等于当着敌人的面从圈上切进去）
            tryDashBoost(maid, target, id, gameTime);
        } else {
            faceOrbit(maid, target, holdY);
        }

        // ⑤ v1.3.0(beta) 实测六百九十六【飞行危险环境避让】：盘旋/掉高这一段若正朝着
        //    岩浆/火/危险方块沉下去，就把竖直分量抬平（脚下或正前方那格是危险方块时）。
        //    放在最后：姿态与速度都摆完之后这一句才有最终话语权；它**只动竖直分量**，
        //    不改朝向、不碰攻击动作（收翅俯冲/俯冲助推那些"朝目标去"的段落各自 return，
        //    走不到这里）。
        MaidFlightHazardGuard.antiSink(maid);
    }

    /**
     * 远程空袭的**近身弹开**（自保）：怪物贴到 {@link #rangedPushRadius()} 内就给一个背离它的速度矢量并持续 1.5 秒。
     * 盘旋逻辑本身没有"别贴脸"概念——{@code faceOrbit} 只保证"绕半径 10 格的圈飞"，怪物主动贴来（或被挤过去）时她
     * 仍贴着转，而血量只有 20，两下就没了。
     * 只弹女仆不弹怪物：弹敌人 = 远程角色获得持续的、无需操作的群体击退，太超模；弹自己则是"用机动换安全"（离怪
     * 同时脱离输出位，狭小空间还跑不掉），敌人仍有机会命中。持续 1.5 秒而非一 tick，是因盘旋的向敌分量每 tick 都
     * 在拉她；强度见 {@link #rangedPushSpeed()}（连续修正而非 impulse）。@return 需弹开时返回**要背离的威胁**；null = 不需
     */
    private LivingEntity pushAwayFromThreat(ServerLevel level, EntityMaid maid, UUID id) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_RANGED_PUSH.get()) {
            // 开关关闭：清掉残留状态，避免"关掉后仍在弹"的尾巴
            RANGED_PUSH_LEFT.remove(id);
            return null;
        }
        // 只在滑翔中弹（落地/被骑乘时不叠加速度，免得出现诡异的贴地滑行）。
        // 判据用 m_21255_() = isFallFlying() = getSharedFlag(7)——本项目既有口径
        // （MaidFlightKit / EntityFlagInvoker / MaidSwimGlideMixin 全用这个名），
        // **不是** m_20161_()（那个反编译出来是 m_6144_()，与滑翔无关）。
        if (!maid.m_21255_()) {
            RANGED_PUSH_LEFT.remove(id);
            return null;
        }

        // 找最近的、真正能威胁她的目标（与 AutoCombatSwitch 同一口径：Enemy 或
        // 已记仇的中立 / 正在锁定她或她主人的生物）。**只认能攻击她的**，
        // 避免把"中立动物路过"也算成贴脸。
        LivingEntity nearest = findNearestThreat(level, maid);
        boolean tooClose = nearest != null
                && maid.m_20280_(nearest) <= rangedPushRadius() * rangedPushRadius();

        int left = RANGED_PUSH_LEFT.getOrDefault(id, 0);
        if (tooClose) {
            // 贴脸：刷新持续时间（怪物一直在身边就一直保持脱离姿态），并施加远离矢量
            RANGED_PUSH_LEFT.put(id, rangedPushTicks());
            left = rangedPushTicks();
            applyAwayVelocity(maid, nearest);
            logPushThrottled(maid, nearest);
            return nearest;
        }
        // 已经离开了：剩余时间继续施加"远离"（方向按"离开最近的威胁"算），
        // 否则下一 tick 盘旋就把速度拉回来，等于没弹
        if (left > 0 && nearest != null) {
            RANGED_PUSH_LEFT.put(id, left - 1);
            applyAwayVelocity(maid, nearest);
            return nearest;
        }
        RANGED_PUSH_LEFT.remove(id);
        return null;
    }

    /**
     * 找最近的真实威胁。判据与 {@code AutoCombatSwitch.hasThreatNearby} 同一口径
     * （那份是每秒扫描用的，这里是每 tick，所以半径按弹开半径取小值，不按还原半径）。
     *
     * 只收 {@link net.minecraft.world.entity.monster.Enemy}、已记仇的中立生物、
     * 以及**正在锁定她或她主人**的任意 Mob —— 后者是魔改生物（不实现 Enemy/NeutralMob）
     * 唯一的兜底口径，与本模组其它地方（如飞行远战的范围命中）保持一致。
     */
    private static LivingEntity findNearestThreat(ServerLevel level, EntityMaid maid) {
        try {
            LivingEntity best = null;
            double bestSqr = Double.MAX_VALUE;
            for (net.minecraft.world.entity.Entity e : level.m_6443_(
                    net.minecraft.world.entity.Entity.class,
                    maid.m_20191_().m_82400_(rangedPushRadius()),
                    ent -> true)) {
                if (!(e instanceof LivingEntity le) || e == maid || !e.m_6084_()) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, le)) {
                    continue; // 主人 / 同主女仆 / 友军：绝不弹开、也不当作威胁
                }
                if (!isThreat(maid, le)) {
                    continue;
                }
                double d = maid.m_20280_(le);
                if (d < bestSqr) {
                    bestSqr = d;
                    best = le;
                }
            }
            return best;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return null;
        }
    }

    /** 该目标是否算"会威胁到她的怪"（威胁口径与 AutoCombatSwitch.hasThreatNearby 对齐） */
    private static boolean isThreat(EntityMaid maid, LivingEntity e) {
        try {
            if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                return true;
            }
            // 中立记仇：走 isAngry（m_21660_），与 AutoCombatSwitch.neutralAngry 同一口径。
            // 防御封装是必要的——模组实现的 NeutralMob getter 抛异常不能炸 tick
            // （实测八十七b 的既有教训，此处照抄同一处理）。
            if (e instanceof net.minecraft.world.entity.NeutralMob nm) {
                try {
                    if (nm.m_21660_()) {
                        return true; // 记仇中（蜜蜂/狼/北极熊等）
                    }
                } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
                }
            }
            // 行为化口径：正在锁定本女仆或她主人的任意 Mob
            // （魔改生物不实现 Enemy/NeutralMob，全靠这条兜住）
            if (e instanceof net.minecraft.world.entity.Mob mob) {
                LivingEntity mt = mob.m_5448_();
                LivingEntity owner = maid.m_269323_();
                if (mt != null && (mt == maid || (owner != null && mt == owner))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return false;
        }
    }

    /**
     * 施加"远离该威胁"的速度矢量：水平方向背离 × {@link #rangedPushSpeed()}，
     * 竖直方向抬一点（{@link #rangedPushUp()}）。
     *
     * 水平分量是**覆盖式**的（直接写水平速度）而不是叠加：叠加会让反复触发时速度越滚越大，
     * 几 tick 后就变成"被弹飞"，与用户要的"平衡"相反。竖直分量保留原值再抬升，
     * 免得把她往地面压。
     */
    private static void applyAwayVelocity(EntityMaid maid, LivingEntity threat) {
        try {
            double dx = maid.m_20185_() - threat.m_20185_();
            double dz = maid.m_20189_() - threat.m_20189_();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1.0E-4) {
                // 完全重叠：没有可靠的"背离方向"，用她当前朝向的反方向兜底
                double yawRad = maid.m_146908_() * (Math.PI / 180.0);
                dx = -Math.sin(yawRad);
                dz = Math.cos(yawRad);
                d = 1.0;
            }
            double ux = dx / d;
            double uz = dz / d;
            Vec3 v = maid.m_20184_();
            maid.m_20256_(new Vec3(
                    ux * rangedPushSpeed(),
                    Math.max(v.f_82480_, 0.0) + rangedPushUp(),
                    uz * rangedPushSpeed()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /** 弹开日志：高频事件，这里按 {@link #RANGED_PUSH_LOG_INTERVAL_MS} 限频 */
    private static void logPushThrottled(EntityMaid maid, LivingEntity threat) {
        try {
            long now = System.currentTimeMillis();
            Long last = RANGED_PUSH_LAST_LOG.get(maid.m_20148_());
            if (last != null && now - last < RANGED_PUSH_LOG_INTERVAL_MS) {
                return;
            }
            RANGED_PUSH_LAST_LOG.put(maid.m_20148_(), now);
            var nameComp = threat.m_5446_();
            String threatName = nameComp != null ? nameComp.getString() : "?";
            double dist = Math.sqrt(maid.m_20280_(threat));
            com.maidsmart.tool.PromaidLog.log("远程空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 近身弹开（"
                            + String.format(java.util.Locale.ROOT, "%.1f", dist) + " 格："
                            + threatName + "）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 空袭·法术叠加层：途中顺带向当前目标发起一次施法。不是新任务、不占武器位——三件套（鞘翅 + 武器 + 烟花）
     * 不变，法术书放饰品栏 / 背包即可（法术模组自扫背包与 curios）。只在这两处发起：远战盘旋
     * （{@link #tickRangedAir} ② 开火前）、近战"已在目标上方、正要压低朝向俯冲"那刻——吟唱期法术模组每 tick
     * 把朝向拧向目标（forceLookAtTarget），会覆盖我们写的朝向，故只挑本来就该面向目标、不需背离抬头的相位；
     * 爬升与收翅猛击**不发起**。距离口径：默认 24 格 = 法术模组自己的 Config.maxSpellRange；直连 provider 不拦
     * 距离，故这里必须自己判，用 **3D 距离**（空袭立体作战，敌人常在斜上方）。@return true = 这一 tick 发起了一次施法
     */
    private boolean tryCastSpell(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST.get()) {
            return false;
        }
        if (gameTime < SPELL_NEXT_CAST.getOrDefault(id, 0L)) {
            return false;
        }
        double range = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_RANGE.get();
        if (maid.m_20270_(target) > range * range) {
            return false;
        }
        // 与开火同款"要看得见才出手"——隔墙施法既浪费冷却，也会让"她在墙这边对着墙
        // 放法术"看起来像 bug（法术模组的弹道自己会撞墙）。
        if (!SelfPreservationBehavior.hasSight(maid, target)) {
            return false;
        }
        if (!MaidSpellCastCompat.castSpell(maid, target)) {
            return false; // 没装法术模组 / 探针失败 → 静默不生效
        }
        SPELL_NEXT_CAST.put(id, gameTime
                + com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_SPELL_CAST_INTERVAL.get());
        // 限频日志：确认"她在空中确实把法术交出去了"（法术成不成立由法术模组决定——
        // 没带法术书 / 全在冷却时它会静默收下这条指令，这是它的口径，不是我们的 bug）
        if (gameTime - SPELL_LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) >= SPELL_LOG_INTERVAL) {
            SPELL_LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("空袭·法术",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 空中施法（目标 "
                            + String.format(java.util.Locale.ROOT, "%.1f",
                                    Math.sqrt(maid.m_20270_(target)))
                            + " 格" + (ranged ? "，远程空袭" : "，近战空袭") + "）");
        }
        return true;
    }

    // 位移类法术（提供高度 / 提供速度）用于**飞行加速**与**平地起飞**；按冲量方向分两类（ISS 源码实证）：
    //  · 「提供高度」AscensionSpell：视线水平分量 + (0,5,0) → 向上初速；用于起飞 / 补高，施法前必须把俯仰
    //    摆到抬头、并清掉法术模组那份施法目标（否则它 forceLookAtTarget 会把朝向拧平）；
    //  · 「提供速度」BurningDashSpell：forward×(3,1,3) 归一 + (0,.25,0) → 沿视线冲刺（垂直分量保留，所以
    //    只带它也照样能抬头起飞）；用于飞行加速，对着目标冲即可。
    // 节流：下次可用 gameTime（两类共用一张表——抢的是同一种"施法机会"）。
    private static final Map<UUID, Long> DASH_NEXT = new HashMap<>();
    /** 位移法术日志限频 */
    private static final Map<UUID, Long> DASH_LAST_LOG = new HashMap<>();
    /**
     * 空中冲刺的最近 / 最远距离（格）：太近没必要、太远别白冲。朝向一律取自"该相位烟花会用的朝向"
     * （A 方案）：起飞 / 补高统一调 {@link #faceLaunchDirection}（抬头角跟着 {@code climbPitch()}：
     * 近战 62° / 远战 45°）。旧版写死一个起飞抬头角且**朝目标**，而烟花的起飞是"地面目标 = 背离 + 抬头"，
     * 再叠加 ISS 冲量把水平放大 3 倍，"抬头 62°"实际只有约 45°，于是位移法术起飞变成**斜着往敌人脸上窜**。
     */
    private static double dashBoostMinRange() {
        return MaidSmartConfig.AIR_RAID_DASH_BOOST_MIN_RANGE.get();
    }
    private static double dashBoostMaxRange() {
        return MaidSmartConfig.AIR_RAID_DASH_BOOST_MAX_RANGE.get();
    }

    private static void markDash(UUID id, long gameTime, EntityMaid maid, String what) {
        DASH_NEXT.put(id, gameTime
                + com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL.get());
        if (gameTime - DASH_LAST_LOG.getOrDefault(id, Long.MIN_VALUE / 2) >= SPELL_LOG_INTERVAL) {
            DASH_LAST_LOG.put(id, gameTime);
            com.maidsmart.tool.PromaidLog.log("空袭·位移",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + what);
        }
    }

    /** 从配置表里取"提供高度"的法术 id 表。
     *  v1.2.2 实测六百一十三：实现搬进 {@link MaidSpellCastCompat#climbSpellIds()}——
     *  飞行跟随也要用同一张表（含那份配置读不出来时的兜底），表与取法都只能有一份。
     *  这里只留一个转发，空袭的行为一字不变。 */
    private static String[] climbSpellIds() {
        return MaidSpellCastCompat.climbSpellIds();
    }

    /** 从配置表里取"提供速度"的法术 id 表。
     *  v1.2.2 实测六百一十四：实现搬进 {@link MaidSpellCastCompat#boostSpellIds()}——
     *  本批新增的"id 认不出来"诊断要同时读两张表（见 {@link MaidSpellCastCompat#checkSpellTables}），
     *  诊断与施法各读一次配置就会分裂。这里只留一个转发，空袭的行为一字不变。 */
    private static String[] boostSpellIds() {
        return MaidSpellCastCompat.boostSpellIds();
    }

    /**
     * 挑法术之前先校验配置表（v1.2.2 实测六百一十四，取自粉丝 Roderick32 的「鞘翅赶路」分支）：
     * id 写错时本链路只会"静默跳过"，看不出任何原因——本方法把认不出的 id 报一条日志
     * （同一个 id 只报一次，见 {@link MaidSpellCastCompat#warnUnknownSpellIds}）。
     */
    private static void checkSpellTables() {
        MaidSpellCastCompat.checkSpellTables();
    }

    /** v1.2.0 实测五百七十二：按她书里**铭刻的等级**施法（读不到就按 1 级）
     *  v1.2.2 实测六百一十三：口径搬进 {@link MaidSpellCastCompat#spellLevelOrDefault}，飞行跟随共用 */
    private static int dashSpellLevel(EntityMaid maid, String spellId) {
        return MaidSpellCastCompat.spellLevelOrDefault(maid, spellId);
    }

    /**
     * 写回冷却时的取值（按角色分档）。
     * @param boost true = 「提供速度」（飞行加速）：按配置决定是否尊重法术自身冷却；false = 「提供高度」
     *              （起飞 / 补高）：**始终只按空袭间隔**，不受法术自身冷却约束（位移手段一向让女仆比玩家
     *              宽松：激流三叉戟忽略"水中 / 雨中"限制；不这样就满足不了"没有烟花也能持续飞"）。
     */
    private static int dashCooldownFor(String spellId, boolean boost) {
        int ours = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_INTERVAL.get();
        try {
            if (!boost || !com.maidsmart.config.MaidSmartConfig
                    .COMBAT_FLIGHT_DASH_BOOST_RESPECT_COOLDOWN.get()) {
                return ours;
            }
            return Math.max(ours, com.maidsmart.combat.MaidSpellCastCompat.spellCooldownTicks(spellId));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return ours;
        }
    }

    /**
     * 提供高度：起飞 / 补高。两种用途共用一条逻辑（抬起下巴 → 放"提供高度"表里的法术）：
     * · {@code takeoff=true}：她在地面、烟花不可用——用位移法术平地起飞；
     * · {@code takeoff=false}：她在空中但不够高（爬升相位 / 远战掉出高度带）——补一口高度。
     * 朝向是关键，一律沿用烟花在该相位的朝向：先把法术模组那份施法目标清空（否则它施法前
     * forceLookAtTarget 会把朝向拧平），再调 {@link #faceLaunchDirection}（地面目标 = 背离 + 抬头；
     * 目标高 10 格以上 = 朝目标 + 抬头）；抬头角跟 {@code climbPitch()} 走。
     */
    private boolean tryDashClimb(EntityMaid maid, LivingEntity target, UUID id, long gameTime,
                                 boolean takeoff) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.get()) {
            return false;
        }
        if (gameTime < DASH_NEXT.getOrDefault(id, 0L)) {
            return false;
        }
        // 起飞/补高**不看法术自身冷却**（见 dashCooldownFor 注释）
        checkSpellTables(); // 实测六百一十四：id 写错时先报一条（否则只是"第一个法术永不生效"）
        String spell = MaidSpellCastCompat.findClimbSpellIgnoringCooldown(maid, climbSpellIds());
        if (spell == null) {
            return false;
        }
        MaidSpellCastCompat.clearCastTarget(maid);  // 别让它把朝向拧平（见方法注释）
        faceLaunchDirection(maid, target);          // 与烟花起飞/爬升同一套朝向（A 方案）
        if (!MaidSpellCastCompat.castSpecific(maid, spell,
                dashSpellLevel(maid, spell), dashCooldownFor(spell, false))) {
            return false;
        }
        if (takeoff) {
            MaidFlightKit.setGliding(maid, true);
        }
        markDash(id, gameTime, maid, (takeoff ? "平地起飞（" : "空中补高（") + spell
                + " Lv" + dashSpellLevel(maid, spell) + "）");
        return true;
    }

    /**
     * 提供速度：飞行加速。滑翔途中用"提供速度"表里的法术给速度续一口——鞘翅"掉速就是掉高度"。只在烟花
     * 同款窗口里放（调用点仅两处，都是该相位本来就对着目标的时刻）：远战盘旋的**掉高窗口**
     * （{@code boostLeft>0}，视线由窗口自己给：抬头 45° 朝目标，与烟花推力同向）；近战空袭俯冲前那刻（视线
     * 由上一 tick 的 {@code faceTarget} 给）。冲量方向因此永远等于该相位的朝向，不再由这里发明角度。旧版
     * 挂在盘旋相位每一 tick（6~28 格 + 看得见就冲）、自己摆视线，而该相位烟花根本不会点，于是她周期性从
     * 圈上切进去冲敌人。
     */
    private boolean tryDashBoost(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST.get()) {
            return false;
        }
        if (gameTime < DASH_NEXT.getOrDefault(id, 0L)) {
            return false;
        }
        // 1.20.1 的 Entity 没有 distanceToSqr(Entity) 重载（1.21.1 才有）→ 按目标坐标展开
        double d2 = maid.m_20275_(target.m_20185_(), target.m_20186_(), target.m_20189_());
        if (d2 < dashBoostMinRange() * dashBoostMinRange()
                || d2 > dashBoostMaxRange() * dashBoostMaxRange()) {
            return false;
        }
        if (!SelfPreservationBehavior.hasSight(maid, target)) {
            return false; // 隔墙冲过去没意义
        }
        checkSpellTables(); // 实测六百一十四：两张表一起校验（见该方法注释）
        String spell = MaidSpellCastCompat.findAvailableDashSpell(maid, boostSpellIds());
        if (spell == null) {
            return false;
        }
        // 实测五百七十八：**不再自己摆朝向**——冲量沿视线走，而视线由调用相位负责
        // （远战窗口 = 45° 朝目标；近战 = faceTarget）。理由见方法注释。
        if (!MaidSpellCastCompat.castSpecific(maid, spell,
                dashSpellLevel(maid, spell), dashCooldownFor(spell, true))) {
            return false;
        }
        markDash(id, gameTime, maid, "空中冲刺加速（" + spell
                + " Lv" + dashSpellLevel(maid, spell) + "）");
        return true;
    }

    /**
     * 位移法术接替烟花维持盘旋高度带：它不是"没烟花时的备胎"，而是**第一顺位的续航手段**（不耗燃料、不占烟花冷却）。
     * 与烟花做同一件事就用同一套口径：**同一触发**（掉出期望高度带，{@link #rangedBoostDrop()}）、**同一朝向**
     * （{@code faceUpForward(target, rangedBoostPitch())}，即烟花掉高窗口那 45° 抬头朝目标）、**同一窗口**（成功即开
     * {@link #rangedBoostAimTicks()} tick 抬头窗口，于是"只带法术不带烟花"的女仆也进得了这个相位）。
     * 表序：先"提供高度"（**不看法术自身冷却**，与滑翔掉高的救急口径一致，也是"没有烟花也能持续飞"的来源），
     * 无则退到"提供速度"（尊重冷却）。两表共用节流 {@link #DASH_NEXT}，同一 tick 只放一口。@return true = 已放一口
     */
    private boolean tryDashHold(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        boolean climbOk = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.get();
        boolean boostOk = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_BOOST.get();
        if (!climbOk && !boostOk) {
            return false;
        }
        if (gameTime < DASH_NEXT.getOrDefault(id, 0L)) {
            return false;
        }
        String spell = climbOk
                ? MaidSpellCastCompat.findClimbSpellIgnoringCooldown(maid, climbSpellIds()) : null;
        boolean fromBoostTable = false;
        if (spell == null && boostOk) {
            spell = MaidSpellCastCompat.findAvailableDashSpell(maid, boostSpellIds());
            fromBoostTable = spell != null;
        }
        if (spell == null) {
            return false;
        }
        MaidSpellCastCompat.clearCastTarget(maid);          // 别让它把 45° 拧平（同 tryDashClimb）
        faceUpForward(maid, target, rangedBoostPitch());    // 与烟花掉高窗口同朝向
        if (!MaidSpellCastCompat.castSpecific(maid, spell, dashSpellLevel(maid, spell),
                dashCooldownFor(spell, fromBoostTable))) {
            return false;
        }
        RANGED_BOOST_LEFT.put(id, rangedBoostAimTicks());  // 法术自己开窗口（纯法术女仆靠这条）
        markDash(id, gameTime, maid, "掉高·位移法术顶高度（" + spell
                + " Lv" + dashSpellLevel(maid, spell) + "）");
        return true;
    }

    /**
     * 远战开火：主手是枪械（TACZ / 卓越前线）→ 走 TLM 自己的枪械通道；否则走本任务实现的
     * {@code performRangedAttack}（箭矢通道）。射程分口径：枪械用 TLM 的 {@code GunCompat.gunMaxRange}，
     * 弓弩用 {@link #rangedAttackRange()}。
     * 扫帚模式（{@link MaidBroomBehavior}）复用**同一个入口**开火——射程、视线、冷却、枪械换弹 / 瞄准全部
     * 同款。它读写的 RANGED_NEXT_SHOT / RANGED_GUN_CD 都是按女仆 UUID 分键的静态表，而她同一时刻只可能处在
     * 一个任务里，故两个模式共用不互相污染。
     */
    public static void fireRanged(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        ItemStack main = maid.m_21205_();
        boolean gun = GunCompat.isGun(main);
        if (gun) {
            // v1.3.0 实测六百六十六：枪械状态机**必须在下面所有门之前**推进，而且每 tick 一次
            // （理由见 tickGunState：旧版放在冷却门之后 = 卓越前线的充能/换弹永远走不完 → 哑火）
            tickGunState(maid, target, id, gameTime);
        }
        double range = gun ? GunCompat.gunMaxRange() : rangedAttackRange();
        // 【实测六百八十二】再叠一道"有效开火距离"：枪械模组自己的射程（实测现场 48 格）比弓弩的
        // 24 大一倍，旧版于是会在 40 格开外一路点射——子弹是有飞行时间的实体，打一直在动的 boss
        // 基本打不中（）。超出这条就**不扣扳机**，
        // 改为继续盘旋（盘旋的径向修正会把她拉回半径 10 的圈上）再打。0 = 关掉这条门。
        double fireCap = rangedFireRange();
        boolean cappedByFireRange = false;
        if (fireCap > 0.0 && fireCap < range) {
            range = fireCap;
            cappedByFireRange = true;
        }
        double dist = maid.m_20270_(target);
        if (dist > range) {
            // v1.2.4 实测六百四十三：不出手的每一档都留一行（节流 2 秒）。
            // 以前"她拿着枪为什么不射"在日志里完全查不到，现场只能靠猜。
            logFireThrottled(maid, id, gameTime, "主手=" + itemName(main) + " 距敌 "
                    + fmt2(dist) + " 格 > " + (cappedByFireRange ? "有效开火距离 " : "射程 ")
                    + fmt2(range) + " 格 → 不出手"
                    + (cappedByFireRange ? "（先拉近再打；调 airRaid.rangedFireRange 可放宽，0 = 不限）" : ""));
            return;
        }
        // v1.2.0 实测五百二十七【出手要看方块阻隔】：弓弩这一路原先不看视线（箭矢自己
        // 撞墙是另一回事——先扣冷却、白放一箭，还可能隔着树叶对着墙射）。
        // v1.2.4 实测六百四十三：枪械那一路原先另有一道"枪口口径"判据（canSeeGunTarget），
        // 现按 TLM 自家 GunShootTargetTask 的口径统一成**这一条视线**——理由见 tickGunFire。
        if (!SelfPreservationBehavior.hasSight(maid, target)) {
            logFireThrottled(maid, id, gameTime, "主手=" + itemName(main) + " 距敌 "
                    + fmt2(dist) + " 格 但视线被挡 → 不出手");
            return;
        }
        if (gun) {
            tickGunFire(maid, target, id, gameTime);
            return;
        }
        if (gameTime >= RANGED_NEXT_SHOT.getOrDefault(id, 0L)) {
            try {
                maid.m_6504_(target, 1.0f);
                RANGED_NEXT_SHOT.put(id, gameTime + rangedShotCooldown(main));
                logFireThrottled(maid, id, gameTime, "主手=" + itemName(main) + " 距敌 "
                        + fmt2(dist) + " 格 → 开火");
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            }
        }
    }

    /**
     * 枪械开火——照搬 TLM {@code GunShootTargetTask.tick} 的开火段：①{@code GunCommonUtil.tick} 负责换弹 / 上膛
     * （现由 {@link #tickGunState} 在 {@link #fireRanged} 所有门之前推进）；②冷却结束时 {@code performGunAttack}
     * 开火、返回值即下一发冷却；③冷却再按 {@code MAID_GUN_ATTACK_SPEED} 缩放。开火失败它自己返回大冷却，不必额外判弹药。
     * **不再挂 TLM 的 canSeeGunTarget**：它内部的 {@code targetConditionsTest} 只是"把 TargetingConditions.range(N)
     * 设成枪型档再做一次 test"，那是 TLM 地面枪械挑 / 留目标的"交战距离带"、非开火合法性；空袭绕圈且停在上方，
     * 3D 距离天生落带外 → 每 tick false → 一枪不放。现视线由上层 {@link #fireRanged} 统一判（hasSight），这里只看冷却。
     */
    public static void tickGunFire(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        ItemStack gun = maid.m_21205_();
        // GunCommonUtil.tick 不在这里了——它是卓越前线枪械状态机（充能 / 换弹 / 拉栓 / 散热）的唯一推进者，
        // 写在这里等于"只有轮到她开火的那一拍才推进"（射程 / 视线 / 冷却三道门都会提前 return）。现由
        // {@link #tickGunState} 在 {@link #fireRanged} 的**所有门之前**推进，每 tick 恰好一次（幂等）。
        // 冷却递减也一并搬到 tickGunState：旧版递减写在"射程门 + 视线门之后"，于是"飞太远 / 视线被挡"的那几拍
        // 冷却冻住不动，与地面枪械模式每 tick 都走一比，就是"开枪频率比正常枪械模式低很多"。这里只读、只开火。
        int cd = RANGED_GUN_CD.getOrDefault(id, 0);
        if (cd > 0) {
            logFireThrottled(maid, id, gameTime, "主手=" + itemName(gun) + " 距敌 "
                    + fmt2(maid.m_20270_(target)) + " 格 → 枪械冷却剩 " + cd + " tick");
            return;
        }
        try {
            cd = com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .performGunAttack(maid, target, gun);
            int raw = cd; // 属性缩放**之前**的原始返回值——判"这一发出去没有"要看它
            net.minecraft.world.entity.ai.attributes.AttributeInstance attr = maid.m_21051_(
                    com.github.tartaricacid.touhoulittlemaid.init.InitAttribute.MAID_GUN_ATTACK_SPEED.get());
            if (attr != null && attr.m_22135_() > 0.0) {
                cd = (int) (cd / attr.m_22135_());
            }
            // 返回值读法（{@code TacInnerCompat.performGunAttack} 字节码逐支对照）：2 / 10~14 = 这一发真的打出去了
            // （AUTO / SEMI|BURST）；>=20 = 还在开镜 / 抽枪 / 拉栓 / 换弹；100 = 枪械不可用（IGun 或枪包数据
            // 缺失）。这一档不节流成 2 秒——频率本就由冷却决定。三个现场量一起印：开镜 = TACZ synIsAiming
            // （反射读；TLM 那档"距离>活动半径且没开镜→先开镜、返回 aimTime×20+2"看着像小冷却、其实没打出去，
            // 有这字段才能区分）；TLM档 = 旧版那道"枪型距离带"判据（现只记录、不再当开火门禁）；推进 = 此刻挂着
            // 哪一路飞行推进剂（回答"为什么三叉戟不行"）。
            com.maidsmart.tool.PromaidLog.log("远程开火", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 主手=" + itemName(gun) + " 距敌 " + fmt2(maid.m_20270_(target)) + " 格"
                    + " 开镜=" + gunAiming(maid) + " TLM档=" + gunBandOk(maid, target)
                    + " 推进=" + propellant(maid)
                    + " 弹=" + (GunCompat.canFeed(maid, gun) ? "有" : "无")
                    + " → 枪械开火返回 " + cd + " tick（原始 " + raw
                    + (raw >= 20 ? (GunCompat.isSbwGun(gun)
                            ? "；SBW：50=canShoot 假（没打出去）100=不是枪 5=换弹/拉栓 10/20=开镜"
                            : "；TACZ：≥20 = 装填/开镜/抽枪/拉栓，没打出去")
                            : "") + "）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            cd = 100;
            logFireThrottled(maid, id, gameTime, "主手=" + itemName(gun)
                    + " → 枪械开火抛异常（按 100 tick 冷却处理）");
        }
        RANGED_GUN_CD.put(id, cd);
    }

    /**
     * 枪械状态机每 tick 推进一步——"打个几发就哑火"的根因修复。{@code GunCommonUtil.tick} 在 TACZ 支是空操作
     * （TACZ 自己的 Mixin 每 tick 推进所有 LivingEntity），在卓越前线支则是 {@code GunData.tick(maid,true)}——SBW
     * 枪械充能 / 换弹 / 拉栓 / 散热的唯一推进者。旧版写在 {@link #tickGunFire} 里、而它在三道门（射程→视线→冷却）
     * 之后，于是"只有轮到她开火的那一拍状态机才走一步"；而 SBW {@code canShoot} 要求充能 / 换弹 / 拉栓归零、那些
     * 计时器只在 GunData.tick 里递减 → 冷却 40 tick 的重武器装一发要 1600 tick、蓄力枪充能永远走不完。
     * 口径：每 tick **恰好一次**——**幂等**（同 gameTime 只推一次）；**在所有门之前**；主手不是枪直接返回。
     */
    public static void tickGunState(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        if (maid == null || id == null) {
            return;
        }
        ItemStack main = maid.m_21205_();
        if (!GunCompat.isGun(main)) {
            return;
        }
        Long last = GUN_STATE_TICKED.get(id);
        if (last != null && last == gameTime) {
            return;
        }
        GUN_STATE_TICKED.put(id, gameTime);
        try {
            com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .tick(maid, target, main);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
        // 【实测六百八十二：枪械冷却也在这里走一步】与上面 GunCommonUtil.tick 同一条理由——
        // 这里在 fireRanged 的**所有门之前**（射程 / 视线 / 冷却），而且是幂等的（同一个 gameTime
        // 只走一次）。旧版递减写在 tickGunFire 里，于是"她飞得太远 / 视线被挡"的那几拍冷却冻住不动，
        // 开火频率比地面枪械模式低——。
        int cd = RANGED_GUN_CD.getOrDefault(id, 0);
        if (cd > 0) {
            RANGED_GUN_CD.put(id, cd - 1);
        }
    }

    /** 物品注册名（日志用；空栈给「空」） */
    private static String itemName(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return "空";
            }
            // 1.20.1：物品注册表走 ForgeRegistries.ITEMS（BuiltInRegistries.ITEM 在 SRG 环境里
            // 是 f_122959_，直接写字面名编译不过——与 MaidArmyCommand 同一处理）
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return key == null ? String.valueOf(stack.m_41720_()) : key.toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return "?";
        }
    }

    /** 日志里的两位小数（Locale.ROOT 定点，免得不同 JVM 打出科学计数法） */
    private static String fmt2(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /* ==================== 枪械开火诊断（v1.2.4 实测六百四十三；只读，不改任何行为） ==================== */

    /** TACZ `IGunOperator` 的惰性反射句柄：[0]=类，[1]=fromLivingEntity(LivingEntity)，
     *  [2]=getSynIsAiming()。空数组 = TACZ 不在场 / 解析失败（一律返回 false）。
     *  做法与 {@code GunCompat} 读 TACZ 弹药箱完全一致——TACZ 不在编译期类路径上。 */
    private static volatile Object[] TACZ_OP_API;
    private static final Object TACZ_OP_LOCK = new Object();

    private static Object[] taczOpApi() {
        Object[] api = TACZ_OP_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_OP_LOCK) {
            if (TACZ_OP_API == null) {
                if (!net.minecraftforge.fml.ModList.get().isLoaded("tacz")) { // TD-15：到场判断先于探针
                    TACZ_OP_API = new Object[0];
                    return TACZ_OP_API;
                }
                try {
                    Class<?> cls = Class.forName("com.tacz.guns.api.entity.IGunOperator");
                    TACZ_OP_API = new Object[]{cls,
                            cls.getMethod("fromLivingEntity", net.minecraft.world.entity.LivingEntity.class),
                            cls.getMethod("getSynIsAiming")};
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
                    TACZ_OP_API = new Object[0];
                }
            }
            return TACZ_OP_API;
        }
    }

    /** TACZ 此刻是否"已开镜"（只读，日志用；TACZ 不在场或读失败一律 false） */
    private static boolean gunAiming(EntityMaid maid) {
        try {
            Object[] api = taczOpApi();
            if (api.length == 0) {
                return false;
            }
            Object op = ((java.lang.reflect.Method) api[1]).invoke(null, maid);
            return op != null && (Boolean) ((java.lang.reflect.Method) api[2]).invoke(op);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return false;
        }
    }

    /**
     * 旧版那道「枪型距离带」判据（TLM `GunCommonUtil.canSee`）——**现在只记录，不再当开火门禁**。
     * 留着它是为了让同一行日志能回答两件事：这一发为什么没出去、以及旧版那道门当时开没开。
     */
    private static boolean gunBandOk(EntityMaid maid, LivingEntity target) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .canSee(maid, target).orElse(false);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return false;
        }
    }

    /** 此刻挂着哪一路飞行推进剂（日志用；激流推进剂窗口在不在见 MaidRiptideBoost） */
    private static String propellant(EntityMaid maid) {
        try {
            return MaidRiptideBoost.isThrusting(maid) ? "激流推进中" : "-";
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return "?";
        }
    }

    /**
     * 退出飞行模式时收枪——TLM 在 `GunShootTargetTask.stop` 里就是调 `GunCommonUtil.onStop`：
     * TACZ 侧复位 ADS 瞄准、卓越前线侧复位手雷冷却。本模式没有那个 task，用一个一次性实例顶上
     * （只有 offhand 是卓越前线手雷时它才会写回冷却，写在这个一次性实例上无副作用）。
     */
    private static void stopGunAim(EntityMaid maid) {
        try {
            if (GunCompat.isGun(maid.m_21205_())) {
                com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                        .onStop(maid, GUN_STOP_TASK);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /** 抬头 + 朝目标水平方向（补烟花的爬升窗口）
     *  v1.2.0 实测四百八十一：立即写角度（理由同 faceAwayAndUp）。 */
    private void faceUpForward(EntityMaid maid, LivingEntity target, float pitch) {
        double dx = target.m_20185_() - maid.m_20185_();
        double dz = target.m_20189_() - maid.m_20189_();
        double dh = Math.sqrt(dx * dx + dz * dz);
        if (dh < 1.0E-4) {
            dx = 0.0;
            dz = 1.0;
            dh = 1.0;
        }
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        applyRotation(maid, yaw, pitch);
        try {
            double tan = Math.tan(Math.toRadians(-pitch));
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + dx / dh, maid.m_20188_() + tan, maid.m_20189_() + dz / dh,
                    360.0f, 360.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 绕目标盘旋 + 高度保持：切向视线绕圈，俯仰由"与期望高度的偏差"决定；立即写角度（理由同 {@link #faceAwayAndUp}）。
     * 半径与旋向都随机 + 一条硬上界：否则**所有**女仆同一个圆、同一高度、同一旋向，敌人一条射线就能串到对面那只。
     * 旋向 {@link CombatOrbit#direction}（UUID 派生）、半径 {@link CombatOrbit#radius}（[orbitRadius×0.75, orbitMax]，
     * 每 4 秒缓动重掷，不是死圈）；{@code orbitMax}（airRaid.orbitMax）既是随机区间顶点也是**硬牵引**——水平距离
     * 一越线，径向增益从 0.5 提到 1.0（双倍往回带）。另有"接敌机动"（波形调制 / 慢波 / 8 字翻转）与"空中防叠罗汉"
     * （{@link #separationRadius}）；高度一个字没动（仍 holdY + 俯仰增益）。
     */
    private void faceOrbit(EntityMaid maid, LivingEntity target, double holdY) {
        double dx = maid.m_20185_() - target.m_20185_();
        double dz = maid.m_20189_() - target.m_20189_();
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r < 1.0E-4) {
            dx = 1.0;
            dz = 0.0;
            r = 1.0;
        }
        double ux = dx / r;
        double uz = dz / r;
        // 【实测六百九十三】这一拍的随机环绕：半径区间顶点 = 「离敌最远距离」（硬上界），
        // 近端 = 基础盘旋半径的 75%（但绝不越过顶点）；旋向每只女仆恒定、彼此对半。
        UUID oid = maid.m_20148_();
        double base = Math.max(2.0, orbitRadius());
        double orbMax = Math.max(2.0, orbitMaxCfg());
        double orbLo = Math.min(orbMax, Math.max(2.0, base * 0.75));
        // 【实测七百〇四：拉开距离——盘旋半径的硬下限】
        //
        //  （airRaid.orbitMax）调小时近端跟着一起塌，她可能绕到 3 格、正好进近战怪的攻击范围。
        //  现在近端取 max(区间近端, airRaid.minStandoff)：随机环绕只发生在这条线之外。
        double standoff = airMinStandoffCfg();
        orbLo = Math.max(orbLo, standoff);
        // 区间退化（硬下限越过顶点）时以顶点为准——绝不能出现 lo > hi 的倒挂
        if (orbLo > orbMax) {
            orbLo = orbMax;
        }
        double wantR = Math.max(1.0, CombatOrbit.radius(oid, orbLo, orbMax));
        double dir = CombatOrbit.direction(oid);
        // 【实测七百〇五】旋向每 8 秒对半概率掉头（与扫帚那条同一处口径，见 CombatOrbit.flipSign）。
        //  位置连续（只改角速度符号），不需要插值；掉头那一刻写一行「反向环绕」。
        double dflip = CombatOrbit.flipSign(oid);
        dir *= dflip;
        if (CombatOrbit.flipped(oid)) {
            com.maidsmart.tool.PromaidLog.log("远程空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 反向环绕：旋向翻转为 "
                            + (dir > 0 ? "逆时针" : "顺时针") + "（每 "
                            + (CombatOrbit.flipTicks() / 20) + " 秒对半概率决定是否掉头）");
        }
        // 【实测七百〇三】接敌机动（见 CombatManeuver）：开场抽一次（幂等），之后每 tick 推进波形。
        boolean maneuverOn = maneuverEnabled();
        if (maneuverOn) {
            CombatManeuver.begin(oid);
            CombatManeuver.tick(oid);
        } else {
            CombatManeuver.forget(oid);
        }
        // 【实测七百〇三】空中防叠罗汉：给半径一份**稳定的**偏置（UUID 派生，同扫帚那条 phaseOf 的思路）。
        //  它只把她们的圈叉开，最终仍夹进 [orbLo, orbMax]——玩家的「离敌最远距离」一个字节不让。
        double sepR = airSeparationEnabled() ? separationRadius(oid) : 0.0;
        wantR += sepR;
        // 机动给的半径倍率（蛇形 / 脱离再进）：同样夹回区间
        if (maneuverOn) {
            wantR *= CombatManeuver.radiusScale(oid);
        }
        wantR = Math.max(orbLo, Math.min(orbMax, wantR));
        double mAngle = maneuverOn ? CombatManeuver.angleScale(oid) : 1.0;
        double mRev = maneuverOn ? CombatManeuver.reversal(oid) : 1.0;
        if (CombatOrbit.entering(oid)) {
            com.maidsmart.tool.PromaidLog.log("远程空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 随机环绕：半径 " + fmt2(wantR)
                            + " 格（区间 " + fmt2(orbLo) + "~" + fmt2(orbMax) + "，上界=airRaid.orbitMax "
                            + fmt2(orbitMaxCfg()) + "），旋向 " + (dir > 0 ? "逆时针" : "顺时针"));
        }
        if (maneuverOn && CombatManeuver.entering(oid)) {
            CombatManeuver.Kind mk = CombatManeuver.kind(oid);
            int seg = CombatManeuver.segment(oid);
            com.maidsmart.tool.PromaidLog.log("远程空袭",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 接敌机动："
                            + (mk == null ? "环绕" : mk.cn)
                            + (seg == 0
                                    ? "（本场遭遇开场；每 " + (CombatManeuver.segmentTicks() / 20)
                                            + " 秒换一种）"
                                    : "（本场遭遇第 " + (seg + 1) + " 段·换打法；每 "
                                            + (CombatManeuver.segmentTicks() / 20) + " 秒换一种）"));
        }
        double tx = dir * mRev * -uz;
        double tz = dir * mRev * ux;
        // 径向修正：太远往回带、太近往外推；越过「最远距离」时增益加倍（硬牵引，不许借随机飘出去）
        double radial = r > orbMax ? (r - orbMax) * 1.0 : (r - wantR) * 0.5;
        double ox = tx * mAngle - ux * radial;
        double oz = tz * mAngle - uz * radial;
        // 高度保持：低于期望高度就抬头（速度换高度），高了就低头（高度换速度）
        // 【实测七百〇三】高悠悠的增量**只加**在 holdY 上（0~amp 的慢波），所以"基础比敌人高多少格"永远成立。
        double effHold = holdY + (maneuverOn ? CombatManeuver.heightAdd(oid, yoyoAmpCfg()) : 0.0);
        double err = (effHold - maid.m_20186_()) + rangedHoldBias();
        double pitch = -err * rangedHoldGain();
        // 【实测七百〇六：高度硬地板】低于「离敌最低高度」时**不走增益**，直接把俯仰钉成最大抬头
        //  ——软增益在死区附近给出的角度太小、救不回高度（这正是"飞着飞着只剩一点高度"的由来）。
        //  硬钉最大抬头让她立刻转入爬升；地板之上的行为一个字不改。
        double floorOff = airMinAboveHeightCfg();
        boolean belowFloor = floorOff > 0.0 && maid.m_20186_() < target.m_20186_() + floorOff;
        if (belowFloor) {
            pitch = -rangedOrbitUpMax();
        } else {
            pitch = Math.max(-rangedOrbitUpMax(), Math.min(rangedOrbitDownMax(), pitch));
        }
        double h = Math.sqrt(ox * ox + oz * oz);
        float yaw = (float) (Math.atan2(oz, ox) * (180.0 / Math.PI)) - 90.0f;
        applyRotation(maid, yaw, (float) pitch);
        try {
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + ox,
                    maid.m_20188_() + Math.tan(Math.toRadians(-pitch)) * h,
                    maid.m_20189_() + oz,
                    360.0f, 360.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
        }
    }

    /**
     * 空袭的**半径偏置**（格，可正可负）：由 UUID 派生、同一只女仆恒定（与扫帚那条
     * {@code MaidBroomDrive.phaseOf} 同思路——那条错的是相位，这条错的是半径）。
     * 空袭的半径基准（airRaid.orbitRadius）在多只之间原本只由 CombatOrbit 的随机决定，且重掷节拍会让她们偶尔
     * 凑到一起；给每只一个恒定偏置后"她们的圈"从进场那刻就分开，随机漂移只在这个基准上继续飘——两层叠加才是
     * "稳定错开"。
     * 不会越界：返回值 ±sepRadius，最终半径由 {@link #faceOrbit} 夹进 [orbLo, orbMax]，硬上界一个字节不让。
     */
    private static double separationRadius(UUID id) {
        if (id == null) {
            return 0.0;
        }
        // 用 UUID 的位当 0~1，再线性映射到 ±sep：稳定、可复现、无分配
        long h = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        double u = ((h >>> 9) & 0xFFFFL) / 65535.0;
        return (u * 2.0 - 1.0) * airSeparationRadiusCfg();
    }

    /** 【实测七百〇三】空袭防叠罗汉开关：配置 {@code combat.airSeparation}，默认开（兜底跟着默认值） */
    private static boolean airSeparationEnabled() {
        try {
            return MaidSmartConfig.COMBAT_AIR_SEPARATION.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return true;
        }
    }

    /** 【实测七百〇三】空袭半径偏置强度（格）：配置 {@code combat.airSeparationRadius}，默认 2.0 */
    private static double airSeparationRadiusCfg() {
        try {
            return MaidSmartConfig.COMBAT_AIR_SEPARATION_RADIUS.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 2.0;
        }
    }

    /** 【实测七百〇三】接敌机动总开关：配置 {@code combat.maneuver.enable}，默认开 */
    private static boolean maneuverEnabled() {
        try {
            return MaidSmartConfig.COMBAT_MANEUVER_ENABLE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return true;
        }
    }

    /** 【实测七百〇三】高悠悠高度幅度（格）：配置 {@code combat.maneuver.yoyoAmp}，默认 4.0 */
    private static double yoyoAmpCfg() {
        try {
            return MaidSmartConfig.COMBAT_MANEUVER_YOYO_AMP.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 4.0;
        }
    }

    /**
     * 空袭的**离敌最低高度**（格）：配置 {@code airRaid.minAboveHeight}，默认 8.0。它是远程空袭盘旋时的**硬
     * 地板**（见 {@link #faceOrbit} 与 {@link #tickRangedAir}）：高度差低于它就把俯仰钉成最大抬头 + 把"掉高补推"
     * 的触发门槛提到它。0 = 关闭（退回旧行为）。配置没挂上时退回 8.0——与配置默认值对齐（本项目老规矩：兜底值
     * 必须跟着默认值走）。
     */
    private static double airMinAboveHeightCfg() {
        try {
            return MaidSmartConfig.AIR_RAID_MIN_ABOVE_HEIGHT.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 8.0;
        }
    }

    /**
     * 【实测七百〇四】空袭的**离敌最小距离**（格）：配置 {@code airRaid.minStandoff}，默认 6.0。
     *
     * <p>它同时是远程空袭盘旋半径的**硬下限**（见 {@link #faceOrbit}）与近战空袭"先拉开距离
     * 再俯冲"的门槛（见 {@code m_6725_} 近战那一支）。配置没挂上时退回 6.0——与配置里的默认值
     * 对齐（本项目的老规矩：兜底值必须跟着默认值走）。0 = 关闭（退回旧行为）。
     */
    private static double airMinStandoffCfg() {
        try {
            return MaidSmartConfig.AIR_RAID_MIN_STANDOFF.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 6.0;
        }
    }

    /**
     * 【实测六百九十三】锁敌之后离敌的**最远距离**（格）：配置 {@code airRaid.orbitMax}，
     * 默认 12（= 基础盘旋半径 10 的 1.2 倍，均值仍落在 10 附近）。它同时是随机环绕区间的顶点
     * 与硬牵引的界（见 {@link #faceOrbit}）。配置没挂上时退回 12——与配置里的默认值对齐
     * （本项目的老规矩：兜底值必须跟着默认值走）。
     */
    private static double orbitMaxCfg() {
        try {
            return MaidSmartConfig.AIR_RAID_ORBIT_MAX.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFlightCombatBehavior", ignored);
            return 12.0;
        }
    }
}
