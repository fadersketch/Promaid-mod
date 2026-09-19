package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * v1.2.0（1.20.1）：飞行作战行为——完整链路。
 *
 * ── 链路 ──
 * 遇到敌人 →【起跳滑翔】→ 放烟花（初速方向 = 敌人反方向 + 向上 62°）
 * → 1.5 秒后转入飞行、朝敌人飞 → 进入 3.5 格 →【取消滑翔】收翅自由落体
 * → 触底前一次**近战猛击**（1.20.1 无重锤 → 按原版规则结算【暴击 ×1.5】）
 * →【切回滑翔】→ 再放烟花 → 如此反复，直到怪物死亡或模式退出。
 * 烟花用完 / 鞘翅损坏 → 三件不齐 → 模式自然变为未激活 → 退回普通攻击模式（地面近战）。
 *
 * ── 为什么开头必须先"起跳滑翔" ──
 * 挂载型烟花**只对正在滑翔的实体给推力**（反编译 `FireworkRocketEntity.m_8119_`：
 * 只有 `if (attachedToEntity.m_21255_())` 才按 `m_20154_()`（getLookAngle）加速）。
 * 而 `LivingEntity.updateFallFlying`（SRG `m_21323_`，aiStep 内、travel 之前）每 tick 用
 * "已置位 && !onGround && 胸甲可用鞘翅"重算第 7 位——**站在地上置位一定被清掉**。
 * 所以第一步必须先跳一下离地，下一 tick 再放烟花才吃得到推力。
 *
 * ── 为什么"取消滑翔"就能吃到暴击 ──
 * 原版暴击条件（`Player.attack`，本模组 1.20.1 的 MaidCombatTacticsBehavior 里已有
 * 一份同款复刻）：`fallDistance > 0 && !onGround && !onClimbable && !isInWater
 * && !hasEffect(BLINDNESS) && !isPassenger && !isSprinting` → 伤害 ×1.5。
 * 而滑翔中 `LivingEntity.travel` 每 tick 调 `Entity.m_245125_()` 把 `fallDistance`
 * **钳在最多 1.0**；收翅后只要再自由落体 2~3 tick 就满足 `fallDistance > 0`。
 */
public class MaidFlightCombatBehavior extends Behavior<EntityMaid> {

    /** 阶段一：放烟花后维持"背离+向上"朝向的时长——**近战**用（1.5 秒）。
     *  v1.2.0 实测四百七十二【回退】：近战必须爬够高度才能维持滑翔（贴地就掉），
     *  故与本值配套的仰角一起回退到远战调参之前的口径。 */
    private static final int LAUNCH_TICKS_MELEE = 30;
    /** 阶段一：放烟花后维持"背离+向上"朝向的时长——**远战**用（1 秒）。
     *  v1.2.0 实测四百六十九：远战 1.5s → 1s，提前转入朝敌盘旋，少爬高
     *  （远战只求悬停高度、不吃俯冲，爬太高反而够不到地面敌人）。 */
    private static final int LAUNCH_TICKS_RANGED = 20;
    /** 烟花最小间隔（tick）——1.5 秒 */
    private static final int FIREWORK_COOLDOWN = 30;
    /** 实测五百六十三：羽扇最小间隔（tick）——原版 getUseDuration=20，每秒最多挥一次 */
    private static final int FAN_COOLDOWN = 20;
    /** 起飞仰角正切——**近战**用：1.88 ≈ 62°（回退到远战调参之前的原值）。
     *  近战链路是"起飞→扑击→收翅猛击→再起飞"的循环，每次重新起飞都要先把
     *  高度拉起来；仰角过低时她一放烟花就往目标方向压头，几 tick 内就贴地，
     *  滑翔位被 `updateFallFlying` 清掉 → 直接变自由落体摔死（实测四百七十二
     *  的 fall 21~23 全部由此而来）。 */
    private static final double LAUNCH_CLIMB_TAN_MELEE = 1.88;
    /** 起飞仰角正切——**远战**用：1.0 = 45°（实测四百六十九"飞太高够不到地面敌人"）。 */
    private static final double LAUNCH_CLIMB_TAN_RANGED = 1.0;
    /** 地面重新起飞的最大距离（格）：太远先跑过去，避免"越炸越远"
     *  v1.2.0 实测五百二十五：这个门槛只对**水平**距离生效（见 tick 里 distH 的注释）——
     *  3D 距离会让"比她高 50 格的敌人"永远进不了起飞分支。 */
    private static final double LAUNCH_RANGE = 20.0;
    /**
     * 高度容差（格）：她与目标的高度差在此范围内即视为**已占位（可以开打）**。
     *
     * v1.2.0 实测五百二十七：初版取 **0** = 严格按需求"高于或等于就走正常路径"；
     * **实测五百三十改为 10**——用户口径："改为10，也就是说，除非高出10格以上，
     * 否则都可以用原链路来应付。"（差 10 格以内的敌人，原链路本来就能打）
     *
     * 判据是 {@code maid.getY() >= target.getY() - ALTITUDE_TOLERANCE}：她比目标低
     * **10 格以内**即视为**已占位**，直接落回原链路（近战俯冲猛击 / 远战盘旋开火）。
     * 这个常量同时也是起飞朝向的判据（见 faceLaunchDirection），所以"10 格以内"连
     * 起飞也回到老口径"背离 + 向上"——正是"用原链路应付"的意思。
     *
     * 实测五百二十八补充：够高（= 进入 10 格以内）之后本相位还要把**当前这枚烟花**的
     * 推力吃完才交棒（见 tickClimbToAltitude），所以调这个值不会省下烟花，
     * 只是早/晚一点进入持推段。
     */
    private static final double ALTITUDE_TOLERANCE = 10.0;
    /** 起跳滑翔的等待上限（tick）：起跳失败（低矮空间）就放弃本轮 */
    private static final int JUMP_TICKS = 3;

    /** 取消滑翔的触发距离（格）：进入即收翅自由落体 */
    private static final double SMASH_RANGE = 3.5;
    /** 猛击命中判定距离（格） */
    private static final double SMASH_HIT_RANGE = 4.0; // v1.2.0：2.5 → 4.0 格（提高命中率）
    /** v1.2.0：范围强制命中半径（格）——见 smashHit */
    private static final double FORCED_HIT_RADIUS = 2.5;
    /** 猛击下落加成门槛（格）：滑翔中 fallDistance 被钳在 1.0，收翅后补几 tick 即可 */
    private static final float SMASH_MIN_FALL = 1.5f;
    /** 猛击段最长 tick：超时按打空收尾（不摔伤、切回滑翔） */
    private static final int SMASH_MAX_TICKS = 20;    /** 地面近战触及距离（格） */
    private static final double MELEE_REACH = 3.0;
    /** 阶段二俯仰限幅（度） */
    private static final float MAX_PITCH_UP = 55.0f;
    private static final float MAX_PITCH_DOWN = 70.0f;

    private static final Map<UUID, Integer> LAUNCH_LEFT = new HashMap<>();
    private static final Map<UUID, Integer> JUMP_LEFT = new HashMap<>();
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
        RANGED_PUSH_LEFT.remove(maidId);
        RANGED_PUSH_LAST_LOG.remove(maidId);
        GROUND_READY.remove(maidId);
        SPELL_NEXT_CAST.remove(maidId);
        SPELL_LAST_LOG.remove(maidId);
        // v1.2.0 实测五百二十一：空袭专用索敌器的锁定/限频也一并清（见 FlightTargeting）
        FlightTargeting.forget(maidId);
        // v1.2.0 实测五百一十一：这里【不】清 FlightFireworkPose 的表——它的归还要靠
        // **实体引用**，而 forget 只有 UUID。清表会让"原副手物品"快照被丢掉、盾牌/食物
        // 永久留在烟花状态。归还统一由 core 行为 MaidToolAutoEquipBehavior 每 tick 调用的
        // FlightFireworkPose.tick(maid) 完成（那个行为任何 activity 都跑，是最可靠的回收入口），
        // 所以即使本行为被强杀，最迟 10 tick 后也会自动还原。
    }

    /**
     * v1.2.0 实测五百二十四：行为**中途停止**时的清场——只清"这一轮的动作状态"，
     * **保留瞄准与烟花冷却**。
     *
     * 【为什么与 forget 分开】空袭一轮里行为本来就会短暂停（三件不齐那两拍、
     * canStillUse 与 tick 的边界）。旧版这些地方都走 `forget()`，而 forget 会连带清掉：
     * <ul>
     *   <li>`FlightTargeting` 的锁定 → 她得重新过"限频扫描 + 视线门"；此时她多半在高空，
     *       拿不回目标 → **有敌人却没有目标 = 没有任何朝向指令 → 顺惯性飘走**
     *       （这正是用户报的"飞得特别高就失去方向"）；</li>
     *   <li>`FIREWORK_READY` 烟花冷却 → 停一下就能重开一次，同一秒里连放两次烟花。</li>
     * </ul>
     * 现在停止只复位 `LAUNCH/JUMP/SMASH/WAIT` 这些"本轮动作"，下次启动自然接着
     * "朝目标飞"（空中直接进阶段二，不会再补一轮背离爬升）。
     *
     * 【与 forget 的分工】forget = 硬清（女仆移除、清空换任务）；
     * pauseRound = 软清（行为收尾）。"跑出战场就不再追"由 FlightTargeting 自己的
     * HOLD_RANGE 负责，不靠停行为实现。
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
        RANGED_PUSH_LEFT.clear();
        RANGED_PUSH_LAST_LOG.clear();
        GROUND_READY.clear();
        NOTIFY_READY.clear();
        SPELL_NEXT_CAST.clear();
        SPELL_LAST_LOG.clear();
        // v1.2.0 实测五百二十一：索敌器状态全清（服务器停止 / 重新加载时）
        FlightTargeting.clearAll();
        FlightFireworkPose.clearAll();
    }

    /**
     * v1.2.0【实测四百八十七】：本轮飞行攻击是否**正在进行中**——
     * 起跳（JUMP_LEFT）/ 烟花爬升（LAUNCH_LEFT）/ 收翅猛击（SMASH）/
     * 等待再放烟花（WAIT_LAUNCH）/ 垂直占位爬升（CLIMB_BOOST）/ 远程俯冲助推（RANGED_BOOST_LEFT）
     * 任一为真即算"正在打这一轮"。
     *
     * 【为什么需要】收翅猛击时 `tickSmash` 会**主动** `setGliding(false)`
     * （收翅才吃得到猛击判定），于是"飞向敌人、贴近地面"那一刻滑翔位恰好是清的——
     * 只认滑翔位的"空中禁传送"在此失效：主人一远，自动传送就把她拽走，
     * 这一轮扑击直接白费（用户反馈的原话："导致本次攻击被卡掉"）。
     *
     * 状态表由 `forget()` 在行为收尾时整清，所以豁免是"有界"的：一轮打完、
     * 目标消失、或行为停止后立刻恢复正常传送。
     *
     * v1.2.0 实测五百四十四：**激流的空中旋转冲击**也并进来（它同样会主动收翅、同样是
     * "一次不能被打断的出手"），判据见 {@link MaidTridentSpinBehavior#isDashing}。
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
     * 本 tick 要打的目标。
     *
     * v1.2.0 实测五百二十一【空袭索敌改由 {@link FlightTargeting} 独占】。
     *
     * 【反馈原文】"将两个空袭状态下的索敌范围强制改为以自身为圆心，半径 50 格。不走 TLM 原版
     * 的机制。现在这个版本近战空袭有一个非常奇怪的点，女仆很容易因为飞的太高然后丢失了自己的
     * 目标，然后就在空中往其他地方飞了，直接脱离了战场。我们要做的是持续瞄准，让女仆往那个
     * 方向飞。"
     *
     * 【旧版为什么丢目标】旧版这里就是"读 brain 的 ATTACK_TARGET，读不到退回 Mob.getTarget"——
     * 等于把目标的**来源与存续完全交给 TLM**：TLM 的传感器按 `maid.searchDimension()` 扫盒子、
     * 而那个方法**按排班活动分流**（只有活动恰为 WORK 才用我们任务覆写的 50 格，其余时段走 idle
     * 任务的默认口径、**垂直只有 4 格**）。她一爬高，敌人就掉出那个 4 格高的盒子；目标一丢，
     * 本行为的 canStillUse 立刻为假 → 行为停止 → `forget()` 清状态 → 她在高空保持滑翔却**没有任何
     * 朝向指令**，于是顺惯性飘出战场。这正是"飞太高→丢目标→往别处飞"。
     *
     * 【现在】全部交给 {@code FlightTargeting.resolve(maid)}：它自己扫"以自身为圆心、半径 50 格
     * 的球"、自己选定、**每 tick 回写** brain 的 ATTACK_TARGET/LOOK_TARGET。于是 TLM 那边什么时候
     * 擦、按什么判据擦都不再影响空袭——下一 tick 就补回来，她也就会一直朝着那个方向飞。
     *
     * 【为什么在 canUse/canStillUse/tick 三处都用它】三处原本都调本方法；现在它们统一走同一条
     * 索敌口径，就不会出现"启动判定看到的目标"和"运行中维持的目标"来自两套机制的分裂。
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
        return currentTarget(maid) != null;
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        stopGunAim(maid); // 退出模式时收枪（TACZ 的 ADS 状态不会自己复位）
        endFlightSafely(maid, maid.m_20148_());
    }

    /**
     * v1.2.0 实测四百七十四：空战三件套不齐时的提示（气泡 + 系统消息）。
     * v1.2.0 实测四百八十八【触发时机修复】：改为**任务一进入就报**，不再等遇到敌人。
     *
     * 需求：两种空战没进入激活状态时给玩家一条气泡对话 + 系统消息。
     * 走 `addTextChatBubble` 一个入口就够——`ChatBubbleLimitMixin` 会把它同时
     * 同步成主人的系统消息（青色 [名字] 前缀）并按 misc.bubbleLimitMs 限频、
     * 且汇入 `SystemTTSManager` 朗读（所以这条台词要进语音包 manifest）。
     *
     * 【旧版为什么"不触发"】notifyNotReady 只被 `tick()` 里那两处"三件不齐"分支调用，
     * 而 `tick()` 要先过 `canUse`：`currentTarget(maid) == null` 时行为**根本不启动**——
     * 也就是说她在没有敌人时缺装备，永远走不到提示分支；只有等真遇到怪、行为开始跑，
     * 才会顺带报一句。观感就是"文本触发条件很奇怪，只在遇到敌人才发"。
     *
     * 【现在的口径】本方法改为 public，并由 `MaidToolAutoEquipBehavior.canUse`
     * （core 行为，任何 activity、每 tick 都跑）在飞行任务下直接调用——**进入任务即检查**：
     * 缺件立刻报一条；补齐后再次缺失（或换回该任务）会重新报。
     *
     * 【限频】两层：本方法 15 秒冷却负责"同一状态不刷屏"；`ChatBubbleLimitMixin`
     * 兜底气泡节流。三件齐备时本方法会**重置冷却**，所以"补齐→又缺"能立刻再报，
     * 不会被上一次的冷却吃掉。
     */
    private static final java.util.Map<EntityMaid, Long> NOTIFY_READY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void notifyNotReady(EntityMaid maid, long gameTime) {
        try {
            if (maid == null) {
                return;
            }
            String missing = MaidFlightKit.missingParts(maid);
            if (missing == null) {
                // 三件齐备 = 这一轮"缺件"状态结束 → 清冷却，下次再缺立刻能报
                NOTIFY_READY.remove(maid);
                return; // 其实是齐的（判定竞态）→ 不误报
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
            maid.getChatBubbleManager().addTextChatBubble(
                    "空战装备不齐，缺" + missing + "，先按普通战斗来");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测四百七十二】目标丢失 / 三件不齐 / 行为结束时的安全收尾：**空中不切滑翔**。
     *
     * 旧版这三处都无条件 `setGliding(false)`——若此刻她正在高空（烟花推进的必然
     * 结果），滑翔位一清就立刻变自由落体：实测日志里飞行期的死亡**全部是
     * `类型=fall 伤害=21~23`**（她只有 20 血，必死），而位置清一色在地面高度。
     * 正确做法与玩家同款：没有推力就【一路滑翔下来】（原版 `travel` 的滑翔分支会
     * 自然减速），落地再收翅。因此空中一律保留滑翔位，落地的清理由原版
     * `updateFallFlying`（落地即清）自然完成。鞘翅真的损坏时该标志也会被原版
     * 自己清掉——那是"翅膀没了"，与本次修复无关。
     *
     * v1.2.0 实测五百二十四：这里由 `forget(id)` 改为 `pauseRound(id)`——**只清本轮动作，
     * 保留瞄准与烟花冷却**。理由见 pauseRound 的注释：空袭一轮里行为会短暂停好几次，
     * 每次都用 forget 把瞄准一起清掉，她就会"有敌人却拿不到目标"而在高空飘走。
     */
    private static void endFlightSafely(EntityMaid maid, UUID id) {
        pauseRound(id);
        if (maid.m_20096_()) {
            MaidFlightKit.setGliding(maid, false);
        }
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
        LivingEntity target = currentTarget(maid);
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

        // ── 实测五百四十四：激流旋转突进期间让位（**只让那十几 tick 的速度与滑翔权**）──
        // 【为什么必须让】突进是"这一 tick 的速度由我指定"的打法；空袭这边同一 tick 会
        // ①放烟花（推力沿她的视线方向）②开滑翔（`travel` 的滑翔分支每 tick 把水平速度往
        // 视线方向拽并限速）。三套速度互相覆盖的结果就是"刚起手就被冲掉，只闪一下"，
        // 旋转根本放不完。早先（实测五百三十八）在空袭里加过早退，但那是"目标进 N 格就自主
        // 起手"的年代——突进占了大半时间，于是起跳滑翔被反复打断，实机"突然不会起飞了"。
        // 【这次为什么不会重演】①起手时机 = **她的攻击时机**（有攻击冷却 + 收招硬直，
        // 不是按距离自主触发）；②让位只在这个有界窗口（上限一次旋转的时长 + 陈旧自愈，
        // 见 MaidTridentSpinBehavior.isDashing）；③放在三件套维护与"缺件提示"**之后**——
        // 装备照穿、缺件照报，被让掉的只有状态机本身。
        if (MaidTridentSpinBehavior.isDashing(maid)) {
            MaidFlightKit.setGliding(maid, false); // 收翅：滑翔会把突进速度拽回去、还限速
            suppressVanillaMelee(maid);
            return;
        }

        // ── 第 0 步：起跳滑翔（离地后立刻放烟花，才吃得到烟花推力）──
        Integer jumpLeft = JUMP_LEFT.get(id);
        if (jumpLeft != null) {
            if (maid.m_20096_()) {
                if (jumpLeft <= 1) {
                    JUMP_LEFT.remove(id);
                } else {
                    // 实测五百七十：离地前的每一 tick 都补一跳。旧版重试只摆头不补跳——
                    // 第一跳被同 tick 的 AI 走位/贴墙吃掉时，整个窗口她都离不了地，
                    // 烟花点不着、落地、再来一轮，观感就是"有时候触发起飞有点困难"。
                    // 补跳与 jumpForLaunch 同一冲量（0.42），离地后照旧走原流程。
                    Vec3 dm = maid.m_20184_();
                    maid.m_20256_(new Vec3(dm.f_82479_, 0.42, dm.f_82481_));
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

        // ── 垂直占位（v1.2.0 实测五百二十七 / 实测五百二十八 / 实测五百三十）──
        // 判据里的"够高"= 她的高度追到目标身下 ALTITUDE_TOLERANCE 格以内（现为 10 格）：
        // 目标只高出 10 格以内时不进本相位，原链路（俯冲猛击 / 盘旋开火）自己就能打。
        // "时刻检查自己的高度是否高于目标单位，高于或等于就走正常路径；否则持续上升
        //  释放火箭飞行（走内置 CD），期间持续判定高度"——判据见 onTargetAltitude。
        // 只拦**空中**这一段：她还在地上时不在这里起飞（地面支点那一段自带
        // "水平太远先跑过去"，而且起跳/放烟花的朝向同样由 faceLaunchDirection 判高度，
        // 所以她一离地就在朝目标爬）。
        // 【实测五百二十八】"够高"不再是唯一的留守条件：本相位点着的这枚烟花
        // 推力还没烧完也不许走。原因见 tickClimbToAltitude 的注释——一到高度就切走，
        // 剩下的推力会被后一段"瞄准敌人"的朝向摊到水平方向，她永远攒不出"在它头上"。
        boolean needClimb = !onTargetAltitude(maid, target);
        boolean holdingBoost = !maid.m_20096_() && CLIMB_BOOST.contains(id)
                && LAUNCH_LEFT.getOrDefault(id, 0) > 0;
        if (!maid.m_20096_() && (needClimb || holdingBoost)) {
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
            faceLaunchDirection(maid, target);
            MaidFlightKit.setGliding(maid, !maid.m_20096_());
            suppressVanillaMelee(maid);
            return;
        }

        double dist = maid.m_20270_(target);
        // v1.2.0 实测五百二十五【敌人比她高时飞不起来】：起飞门槛只看【水平距离】。
        //
        // 【旧版为什么"只会看着"】旧版这里拿 3D 距离（`m_20270_`）与 LAUNCH_RANGE(=20) 比。
        // 敌人比她高 50 格时，**光是垂直分量就 ≥50 > 20**——她站在敌人正下方也永远进不了
        // 起飞分支：既不跳、也不放烟花，只会原地仰头看（用户反馈原话："只会对着比自己高
        // 50 格的敌人看着，但是不知道该怎么起飞"）。
        //
        // 【为什么改成水平】LAUNCH_RANGE 这句话的原意是"太远先跑过去，避免越炸越远"
        // （见常量注释），那本来就是**水平**概念；垂直方向本来就不该有限制——空袭是
        // 立体作战，敌人飞多高都要能打。爬升量由起飞仰角（近战 62°/远战 45°）承担，
        // 而"目标在头顶时朝它爬而不是背离"由 {@link #faceLaunchDirection} 负责。
        double dxh = maid.m_20185_() - target.m_20185_();
        double dzh = maid.m_20189_() - target.m_20189_();
        double distH = Math.sqrt(dxh * dxh + dzh * dzh);

        // ── 地面：贴身就地近战收尾；否则起跳滑翔 → 放烟花（新一轮的起点）──
        if (maid.m_20096_()) {
            if (ranged) {
                // 飞行远战：落地就重新起飞恢复盘旋（开火由 performRangedAttack 通道负责）；
                // 没烟花/冷却中就站着，目标会在原地继续被远程打
                if (distH <= LAUNCH_RANGE && canLaunch(maid, gameTime)) {
                    jumpForLaunch(maid, target, id);
                    return;
                }
                MaidFlightKit.setGliding(maid, false);
                return;
            }
            if (distH <= LAUNCH_RANGE && canLaunch(maid, gameTime)) {
                jumpForLaunch(maid, target, id);
                return;
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

        suppressVanillaMelee(maid);
        faceTarget(maid, target);

        // 即将接触 → 取消滑翔，转入猛击段
        if (dist <= SMASH_RANGE && !WAIT_LAUNCH.contains(id)) {
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
        Vec3 dm = maid.m_20184_();
        maid.m_20256_(new Vec3(dm.f_82479_, 0.42, dm.f_82481_));
        MaidFlightKit.setGliding(maid, true);
        JUMP_LEFT.put(id, JUMP_TICKS);
    }

    private static boolean canLaunch(EntityMaid maid, long gameTime) {
        // 实测五百六十三：燃料口径 = 烟花 或 孔雀羽扇（扇子优先，见 tryLaunch）
        return !onFireworkCooldown(maid, gameTime) && MaidFlightKit.hasFlightFuel(maid);
    }

    private boolean tryLaunch(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        if (onFireworkCooldown(maid, gameTime)) {
            return false;
        }
        // 实测五百六十三【扇子优先】：有暮色森林孔雀羽扇就挥扇起飞——推进公式、
        // 扇风盒、扣耐久全部照搬扇子自己（见 TwilightFanKit），使用节奏 = 原版
        // getUseDuration 的 20 tick/次；没扇才走烟花。
        if (TwilightFanKit.hasFan(maid) && TwilightFanKit.boostGlide(level, maid)) {
            faceLaunchDirection(maid, target);
            // 必须置位：与烟花同通道——鞘翅滑翔每 tick 吃朝向
            MaidFlightKit.setGliding(maid, true);
            LAUNCH_LEFT.put(id, this.ranged ? LAUNCH_TICKS_RANGED : LAUNCH_TICKS_MELEE);
            WAIT_LAUNCH.remove(id);
            FIREWORK_READY.put(id, gameTime + FAN_COOLDOWN);
            com.maidsmart.tool.PromaidLog.log("飞行作战",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 挥羽扇起飞");
            return true;
        }
        ItemStack fw = MaidFlightKit.takeFirework(maid);
        if (fw.m_41619_()) {
            return false;
        }
        launchFirework(level, maid, fw);
        // v1.2.0 实测五百一十一/五百一十四：副手"亮一下"烟花模型（纯表现，用完还原原物）；
        // 展示的就是本次真正消耗掉的那一枚（模型与它完全一致）
        FlightFireworkPose.show(maid, fw);
        faceLaunchDirection(maid, target);
        // 必须置位：挂载烟花只对"正在滑翔"的实体给推力
        MaidFlightKit.setGliding(maid, true);
        LAUNCH_LEFT.put(id, this.ranged ? LAUNCH_TICKS_RANGED : LAUNCH_TICKS_MELEE);
        WAIT_LAUNCH.remove(id);
        FIREWORK_READY.put(id, gameTime + FIREWORK_COOLDOWN);
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
     *
     * 【安全】1.20.1 的烟花是 NBT 结构（没有 DataComponents）：只写 `Fireworks.Flight`、
     * **不写 `Explosions`**，这样 `getExplosions()` 为空 → 到期 `explode()` 时
     * `dealExplosionDamage()` 的伤害为 0，不会像带爆炸星的挂载烟花那样按 5+2n 炸到
     * 骑乘者自己（女仆只有 20 血）。消耗的仍是玩家给的真实烟花（1 枚）。
     */
    private void launchFirework(ServerLevel level, EntityMaid maid, ItemStack consumedIgnored) {
        try {
            ItemStack rocketStack = new ItemStack(Items.f_42688_);
            CompoundTag fireworks = new CompoundTag();
            fireworks.m_128405_("Flight", 1);
            rocketStack.m_41784_().m_128365_("Fireworks", fireworks);
            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                    new net.minecraft.world.entity.projectile.FireworkRocketEntity(level, rocketStack, maid);
            level.m_7967_(rocket);
            SoundEvent launch = ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation("minecraft", "entity.firework_rocket.launch"));
            if (launch != null) {
                level.m_5594_(null, maid.m_20183_(), launch, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {
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
        boolean inReach = sweepWithin(maid, target, SMASH_HIT_RANGE);

        // v1.2.0：够近就尽快打（t>=2 即可——收翅那一刻 fallDistance 已被滑翔钳在 ~1.0，
        // 暴击条件 fallDistance>0 已成立；久等只会让她擦身而过）
        if (inReach && (t >= 2 || nearGround || landed) && smashHit(level, maid, target, gameTime)) {
            endSmash(maid, id, gameTime);
            return;
        }
        // 收尾：落地 / 贴地 / 超时 → 清零坠落距离（不自我摔伤）并切回滑翔。
        // 【安全必需】必须在"未命中"时也能走到：攻击冷却未好时 smashHit 返回 false，
        // 若像旧版那样在命中分支里 return，贴地时既没清零也没收尾 → 她带着满坠落距离撞地。
        // 【命中率】空中且冷却中不在此列（未落地未贴地且 t < MAX）→ 下一 tick 继续压着打，
        // 不再"整个俯冲白打一次"（旧版无论打没打中都 endSmash，是命中率差的一大来源）。
        if (landed || nearGround || t >= SMASH_MAX_TICKS) {
            maid.f_19789_ = 0.0f;
            endSmash(maid, id, gameTime);
        }
    }

    /** 猛击收尾：清状态 → 切回滑翔（"攻击完之后再切换为滑翔模式"）→ 等待再放烟花 */
    private void endSmash(EntityMaid maid, UUID id, long gameTime) {
        SMASH.remove(id);
        SMASH_TICKS.remove(id);
        WAIT_LAUNCH.add(id);
        // 实测五百四十四：这一记被换成了激流旋转冲击时**先别开滑翔**——滑翔的 `travel` 会
        // 每 tick 把水平速度往视线方向拽并限速，正好把突进速度磨掉。突进结束后空袭自然回到
        // 本状态机，那时再开滑翔（阶段二那一条），中间只差十几 tick。
        if (!maid.m_20096_() && !MaidTridentSpinBehavior.isDashing(maid)) {
            MaidFlightKit.setGliding(maid, true);
        }
        FIREWORK_READY.put(id, Math.max(FIREWORK_READY.getOrDefault(id, 0L), gameTime));
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
        //    判定框经常判不到；这里对身边 FORCED_HIT_RADIUS 格内的**其他合法敌对目标**
        //    也结算一次。只认 IAttackTask.canAttack（不会误伤主人/宠物/中立动物），
        //    并且【女仆一律跳过】——女仆免疫这一记重锤友伤。
        try {
            net.minecraft.world.phys.AABB box = maid.m_20191_().m_82400_(FORCED_HIT_RADIUS);
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
        } catch (Throwable ignored) {
        }
        ItemStack weapon = maid.m_21205_();
        weapon.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND));
        maid.f_19789_ = 0.0f;
        return true;
    }

    /**
     * 单次命中结算：附魔 → 暴击 ×1.5 → **强制命中** → 击退 + 火焰附加 + 荆棘 + 特效。
     * 强制命中：若这一记被目标的无敌帧吃掉（刚被别的来源打过），清掉无敌帧再打一次——
     * 保证"触发了链路攻击就一定打到"（这也是用户要的后门）。
     *
     * v1.2.0 实测四百九十二【击退量不吃击退附魔】：旧版这里写死 `m_147240_(1.0, ...)`。
     * 原版 `Mob.m_7327_`（反编译）的击退是
     * `knockback = ATTACK_KNOCKBACK 属性 + EnchantmentHelper.m_44894_(攻击者)`（= 击退附魔），
     * 然后 `m_147240_(knockback * 0.5f, ...)`。旧版把这条附魔整段丢了，
     * 所以"锋利/火焰附加"生效而"击退"不生效。现按原版算式补齐（保留原有的
     * dx/len 方向归一化写法，只把量换成原版口径）。
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
                } catch (Throwable ignored) {
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
        }
    }

    /* ---------------- 朝向 ---------------- */

    /**
     * v1.2.0 实测五百二十七【垂直占位：先爬到目标头上，再谈出手】。
     *
     * 【需求原文】"在激活此状态且存在敌人的时候，时刻检查自己的高度是否高于目标单位，
     * 高于或等于的话就正常走路径。不考虑的话就持续上升释放火箭飞行（走内置 CD）。
     * 期间持续判定高度。直至高度超过 → 处于悬空状态 → 进入瞄准敌人的分支。"
     *
     * 【口径】进本相位的判据有两条（或）：{@link #onTargetAltitude} 为假
     * （目标高出她 10 格以上，见 {@link #ALTITUDE_TOLERANCE}），
     * 或"本相位点着的这枚烟花的推力还没烧完"。两条都不成立才落回原链路
     * （近战俯冲猛击 / 远战盘旋开火）。
     *
     * 【实测五百二十八：够高之后不能立刻走人——"等火箭的加速跑完"】用户原话：
     * "在空中状态下女仆是悬空的，没有办法像地上一样用火箭进行一次加速飞到敌人上方……
     *  并不是一达到高度就立刻取消火箭的加速，而是等火箭的加速效果结束之后才走下一个链路。"
     *
     * 【为什么必须这样】挂载型烟花的推力方向**每 tick 跟着她当时的视线走**，而"抬头朝
     * 目标爬"这个朝向只有本相位在给。一到高度就切走 → 朝向立刻被"瞄准敌人"改成基本水平
     * → 同一枚烟花剩下的 20~30 tick 推力全被摊到水平方向，她刚爬到的这点高度又还回去；
     * 敌人只要还在升，她就在目标高度上下来回蹭，永远攒不出"在它头上"这个占位。
     *
     * 【本相位自管烟花与朝向】阶段一的倒计时（`LAUNCH_LEFT`）在这里被改写口径为
     * **"当前这枚烟花的推力还剩多少 tick"**（不再是阶段一那个"维持背离 + 向上"的倒计时，
     * 那个是给地面目标设计的躲闪爬升，本相位也不该白等它——本相位就是这段时间的"阶段一"）；
     * "等待再放"（`WAIT_LAUNCH`）在这里作废。够高之后**不再补烟花**（否则冷却一好又点一枚，
     * 相位被无限续命、一路烧到世界顶端），只是把已经点着的那一枚烧完。
     *
     * 【烟花走内置 CD】复用 {@link #canLaunch}（`FIREWORK_READY` 30 tick 冷却 + 有烟花），
     * 与地面起飞、远战补烟花同一套闸门，不另开计时器。而 `LAUNCH_LEFT`(30/20) 与
     * `FIREWORK_COOLDOWN`(30) 基本同量级，所以"推力刚烧完"就是"下一枚可以点了"。
     *
     * 【远战一边爬一边照常开火】近战爬升途中没有可打的（要贴到 3.5 格才收翅猛击），
     * 远战则不然——所以这里额外调一次 {@link #fireRanged}，让它在爬升途中继续输出。
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
        double climbTan = this.ranged ? LAUNCH_CLIMB_TAN_RANGED : LAUNCH_CLIMB_TAN_MELEE;
        return (float) (-Math.toDegrees(Math.atan(climbTan)));
    }

    /**
     * 是否已"占位"——高度是否已经**追到目标身下 {@link #ALTITUDE_TOLERANCE} 格以内**
     * （v1.2.0 实测五百二十七 引入，实测五百三十 把容差从 0 放到 10）。
     *
     * 这是本模组空袭唯一的垂直判据（取代了 实测五百二十五 那个"高过 8 格"的启发式）：
     * 只有目标高出她 **10 格以上**才值得专门爬——那种高度差原链路（俯冲 / 盘旋）够不着，
     * 得先升上去；10 格以内原链路本来就能应付，于是直接落回"瞄准敌人"那条链路。
     * 判据每 tick 重算，所以爬升途中一够高就马上转段，不会多烧烟花。
     */
    private static boolean onTargetAltitude(EntityMaid maid, LivingEntity target) {
        return maid.m_20186_() >= target.m_20186_() - ALTITUDE_TOLERANCE;
    }

    /**
     * 起飞/起跳时的朝向（v1.2.0 实测五百二十五 引入，实测五百二十七 换成垂直判据）。
     *
     * 两条分支，判据就是 {@link #onTargetAltitude}：
     * <ul>
     *   <li>**目标高出 10 格以上**（{@link #ALTITUDE_TOLERANCE} 之外） → 朝目标 + 抬头
     *       （"顺着敌人的方向爬上去"）；</li>
     *   <li>**其余情况**（差 10 格以内 / 等高 / 已在它头上） → 老口径"背离 + 向上"——
     *       那是为地面目标设计的：爬升时别站在对方近战/爆炸包线里。10 格以内的目标
     *       本来就归原链路管（实测五百三十），这里也一并交还给老口径。</li>
     * </ul>
     *
     * 【旧版为什么"只会看着"】① 起飞门槛曾用 3D 距离（见 {@code tick} 里 distH 的注释）；
     * ② 曾经只有"目标高过 8 格"才朝它爬，在那之下的高度差一律"背离+向上"——目标在半空
     * 时她会一边爬一边越飞越远，还得掉头回来，全程烧烟花。
     */
    private void faceLaunchDirection(EntityMaid maid, LivingEntity target) {
        if (!onTargetAltitude(maid, target)) {
            faceUpForward(maid, target, climbPitch());
            return;
        }
        faceAwayAndUp(maid, target);
    }

    /** 阶段一/起跳：朝向 = 敌人反方向的水平朝向 + 向上（近战 62° / 远战 45°）
     *  ——推力与滑翔都吃这个朝向。实测四百七十二：两种模式必须分开取值，
     *  共用远战那套 45°/20t 会让近战爬升腰斩、贴地摔死。
     *
     *  v1.2.0 实测四百八十一【1.20.1 只会水平飞的根因】：本方法原先只用 `m_7618_(lookAt)`，
     *  但 `LookControl.m_8128_(tick)` 每 tick 会在 `m_8106_()`（字节码 `iconst_1/ireturn`，
     *  **恒真**）时执行 `mob.m_146926_(0f)` —— 也就是**把 xRot 归零**。`lookAt` 刚写进去的
     *  抬头角下一 tick 就被抹掉，于是烟花推力只吃得到水平分量 → 女仆平飞、不爬升。
     *  1.21.1 侧靠 `applyRotation()` + `getLookControl().setLookAt(...)` 对抗（那边
     *  `resetXRotOnTick()` 同样恒真），这套机制当时没有同步到 1.20.1，现补上。
     */
    private void faceAwayAndUp(EntityMaid maid, LivingEntity target) {
        double climbTan = this.ranged ? LAUNCH_CLIMB_TAN_RANGED : LAUNCH_CLIMB_TAN_MELEE;
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
        // 与 1.21.1 同款：直接算角度并【立即写入实体】——只靠 lookAt 会被 LookControl 抹掉
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-Math.toDegrees(Math.atan(climbTan)));
        applyRotation(maid, yaw, pitch);
        // 同时把"期望角度"交给 LookControl：它的 tick 会按这个期望值重新施加，
        // 抵消每 tick 的 xRot 归零（少了这步，下一 tick 又会变回水平）
        try {
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + ux, maid.m_20188_() + climbTan, maid.m_20189_() + uz,
                    360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

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
        } catch (Throwable ignored) {
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
        pitch = Math.max(-MAX_PITCH_UP, Math.min(MAX_PITCH_DOWN, pitch));
        applyRotation(maid, yaw, pitch);
        try {
            maid.m_21563_().m_24960_(target, 360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 退路 ---------------- */

    /** 地面近战（模式未激活 / 目标贴身 / 太远先跑近时使用） */
    private void groundMelee(ServerLevel level, EntityMaid maid, LivingEntity target, long gameTime) {
        try {
            faceTarget(maid, target);
            double dist = maid.m_20270_(target);
            if (dist > MELEE_REACH) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
            return 20L;
        }
    }

    /* ---------------- 飞行远战：空中盘旋（用户指定） ---------------- */

    /** 盘旋半径（格） */
    private static final double ORBIT_RADIUS = 10.0;
    /** 期望盘旋高度（格，目标上方）——v1.2.0 实测四百六十九：6 → 3.5（飞太高锁不到敌，
     *  女仆在目标斜上方 3.5 格既能盘旋又保持锁敌与开火视线） */
    private static final double RANGED_HOLD_HEIGHT = 3.5;
    /** 高度偏差 → 俯仰角增益（度/格）：低了抬头把速度换成高度、高了低头把高度换成速度 */
    private static final double RANGED_HOLD_GAIN = 5.0;
    /** 高度偏置（格）：抵消滑翔的固定下沉与转弯损耗，让平衡点落在略抬头处 */
    private static final double RANGED_HOLD_BIAS = 1.0;
    /** 低于期望高度这么多格才补烟花（5 秒间隔仍是下限，避免浪费烟花） */
    private static final double RANGED_BOOST_DROP = 3.0;
    /** 盘旋俯仰限幅（度） */
    private static final float RANGED_ORBIT_UP_MAX = 45.0f;
    private static final float RANGED_ORBIT_DOWN_MAX = 35.0f;
    /** 补烟花的间隔下限（tick）——用户要求 5 秒 */
    private static final int RANGED_BOOST_INTERVAL = 100;
    /** 补烟花时"抬头窗口"的 tick 数——v1.2.0 实测四百六十九：20 → 10（提前转圈，
     *  别把整枚烟花的推力都用去爬高，只借前半段升一点就回到盘旋） */
    private static final int RANGED_BOOST_AIM_TICKS = 10;
    /** 抬头窗口的仰角（度）——v1.2.0 实测四百六十九：55° → 45°（配合用户"角度调整到 45 度"） */
    private static final float RANGED_BOOST_PITCH = -45.0f;
    /** 开火间隔（tick） */
    private static final int RANGED_SHOT_COOLDOWN = 20;
    /** 开火的最大距离（格） */
    private static final double RANGED_ATTACK_RANGE = 24.0;

    /**
     * v1.2.0 实测五百零三【远程空袭的"近身弹开"】：怪物贴到这么近就给她一个**远离怪物**
     * 的速度矢量，防止她在远程攻击时仍然往敌人身上飞、下落途中被贴脸打死。
     *
     * 需求原文："当周围三格内出现怪物的时候，女仆被弹开（或者强制增加一个远离怪物的
     * 速度矢量），防止女仆在远程攻击的时候还是采用向敌人飞的策略，导致下落的时候直接
     * 被敌人打死。女仆自己被弹开更加平衡，弹开敌人太超模……（也保证了在狭小空间内，
     * 敌人仍然有命中的可能）"
     */
    private static final double RANGED_PUSH_RADIUS = 3.0;
    /**
     * 弹开的水平速度（格/tick）。取 0.55 的理由：
     * - 鞘翅滑翔的水平巡航速度大致就在 0.4~0.8 区间，0.55 足以在一两 tick 内把
     *   "向敌飞"的矢量**反向压过去**，但她仍是一条连续的弧线而非瞬移；
     * - 刻意**不用** impulse 式的 1.0+：那会让女仆被"弹飞"，既不像盘旋也不平衡。
     */
    private static final double RANGED_PUSH_SPEED = 0.55;
    /** 弹开时附加的向上分量（格/tick）——顺手把高度抬一点，脱离怪物的近战竖直包线 */
    private static final double RANGED_PUSH_UP = 0.25;
    /**
     * 弹开后的"冷却/持续"时长（tick）。这段时间内**持续施加**远离矢量（不是打一枪就完），
     * 否则下一 tick 盘旋的向敌分量会立刻把速度拉回去、等于没弹。
     * 30 tick = 1.5 秒，与 {@link #FIREWORK_COOLDOWN} 同量级——足够飘出怪物的一次攻击间隔。
     */
    private static final int RANGED_PUSH_TICKS = 30;
    /** 弹开日志限频（毫秒）——这是高频事件，绝不能让日志被它刷屏 */
    private static final long RANGED_PUSH_LOG_INTERVAL_MS = 5000L;

    private static final Map<UUID, Integer> RANGED_PUSH_LEFT = new HashMap<>();
    private static final Map<UUID, Long> RANGED_PUSH_LAST_LOG = new HashMap<>();

    private static final Map<UUID, Integer> RANGED_BOOST_LEFT = new HashMap<>();
    private static final Map<UUID, Long> RANGED_NEXT_BOOST = new HashMap<>();
    private static final Map<UUID, Long> RANGED_NEXT_SHOT = new HashMap<>();

    /**
     * 本次开火后的间隔（tick）。
     *
     * v1.2.0 实测五百三十一【快速装填】：原版弩的蓄力时长 = `25 - 5 × 快速装填等级`
     * （1.20.1 `CrossbowItem.m_40939_` 字节码：`bipush 25; iconst_5; iload; imul; isub`），
     * 飞行时没有蓄力动作，所以把这个**相对幅度**搬到开火间隔上：
     * `间隔 = 基础 20 tick × (25 - 5L) / 25`（III 级 20 → 8 tick，与"25 → 10 tick"同为 2.5 倍）。
     * 下限 4 tick，避免高等级附魔叠出荒谬射速。弓没有射速类附魔，一律走基础间隔。
     */
    private static int rangedShotCooldown(ItemStack weapon) {
        if (!(weapon.m_41720_() instanceof net.minecraft.world.item.CrossbowItem)) {
            return RANGED_SHOT_COOLDOWN;
        }
        int quickCharge = 0;
        try {
            quickCharge = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44843_(
                    net.minecraft.world.item.enchantment.Enchantments.f_44960_, weapon);
        } catch (Throwable ignored) {
        }
        if (quickCharge <= 0) {
            return RANGED_SHOT_COOLDOWN;
        }
        int cd = (int) Math.round(RANGED_SHOT_COOLDOWN * (25.0 - 5.0 * quickCharge) / 25.0);
        return Math.max(4, Math.min(RANGED_SHOT_COOLDOWN, cd));
    }

    /** v1.2.0 实测四百六十八：枪械开火冷却（tick）——由 performGunAttack 的返回值驱动 */
    private static final Map<UUID, Integer> RANGED_GUN_CD = new HashMap<>();
    /** 退出模式时交给 TLM 收枪用的一次性 task 实例（见 stopGunAim） */
    private static final com.github.tartaricacid.touhoulittlemaid.compat.gun.common.ai.GunShootTargetTask
            GUN_STOP_TASK = new com.github.tartaricacid.touhoulittlemaid.compat.gun.common.ai.GunShootTargetTask();

    /**
     * 飞行远战的空中阶段（用户指定）：起飞后不再扑击，而是**持续在天上盘旋（类似幻翼）**、
     * 并用**手持远程武器**在锁敌范围内开火——弓弩走 TLM 自己的 `EntityMaid.m_6504_`
     * (performRangedAttack) 通道（本任务实现了 IRangedAttackTask）；**枪械**（TACZ / 卓越前线）
     * 走 TLM 的枪械通道（换弹 + 自动瞄准 + 开火，见 {@link #tickGunFire}）。
     * 击败敌人后行为自然结束 → 清滑翔落地（与近战一致）。
     *
     * 高度维持分两层（v1.2.0 实测四百六十七）：①盘旋时按"与期望高度的偏差"给俯仰——纯鞘翅
     * 滑翔在转弯时也会掉高度，固定 -6° 顶不住；②真的掉出高度带才补烟花（抬头 55° 放，把推力
     * 用在爬升上），5 秒间隔只是下限，不再无条件每 5 秒烧一枚。
     */
    private void tickRangedAir(ServerLevel level, EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        MaidFlightKit.setGliding(maid, true);

        // 期望盘旋高度（目标上方 RANGED_HOLD_HEIGHT 格）
        double holdY = target.m_20186_() + RANGED_HOLD_HEIGHT;

        // ① 按需补烟花：只在真的掉出高度带时才补一口（5 秒间隔只是下限）
        int boostLeft = RANGED_BOOST_LEFT.getOrDefault(id, 0);
        boolean tooLow = maid.m_20186_() < holdY - RANGED_BOOST_DROP;
        if (boostLeft <= 0 && tooLow && gameTime >= RANGED_NEXT_BOOST.getOrDefault(id, 0L)
                && canLaunch(maid, gameTime)) {
            // 实测五百六十三：扇子优先——掉高时挥羽扇维持高度（推进/扣耐久照搬扇子
            // 自己，节奏 20t），没扇才烧烟花
            if (TwilightFanKit.hasFan(maid) && TwilightFanKit.boostGlide(level, maid)) {
                FIREWORK_READY.put(id, gameTime + FAN_COOLDOWN);
                RANGED_NEXT_BOOST.put(id, gameTime + RANGED_BOOST_INTERVAL);
                RANGED_BOOST_LEFT.put(id, RANGED_BOOST_AIM_TICKS);
                boostLeft = RANGED_BOOST_AIM_TICKS;
                com.maidsmart.tool.PromaidLog.log("远程空袭",
                        com.maidsmart.tool.PromaidLog.nameOf(maid) + " 掉高挥羽扇");
            } else {
                ItemStack fw = MaidFlightKit.takeFirework(maid);
                if (!fw.m_41619_()) {
                    launchFirework(level, maid, fw);
                    // v1.2.0 实测五百一十一/五百一十四：副手"亮一下"实际消耗的那枚烟花
                    FlightFireworkPose.show(maid, fw);
                    FIREWORK_READY.put(id, gameTime + FIREWORK_COOLDOWN);
                    RANGED_NEXT_BOOST.put(id, gameTime + RANGED_BOOST_INTERVAL);
                    RANGED_BOOST_LEFT.put(id, RANGED_BOOST_AIM_TICKS);
                    boostLeft = RANGED_BOOST_AIM_TICKS;
                    com.maidsmart.tool.PromaidLog.log("远程空袭",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 掉高补烟花");
                }
            }
        }

        // ② 开火（必须放在"朝向"之前——枪械开火会自己把身体拧向目标，随后要把盘旋朝向
        //    盖回去，否则滑翔会顺着那次瞄准把女仆直接拉向目标，"盘旋"就散了）
        //    法术同款（实测五百六十七，PR #8 移植）：法术模组的吟唱也会拧朝向，所以同样
        //    放在"朝向"之前一起被盖回去。
        tryCastSpell(maid, target, id, gameTime);
        fireRanged(maid, target, id, gameTime);

        // ③ v1.2.0 实测五百零三【近身弹开】：怪物贴到 3 格内就给一个"远离怪物"的速度矢量，
        //    并让它在接下来 RANGED_PUSH_TICKS 内**持续**生效（见 pushAwayFromThreat）。
        //
        //    【为什么必须"同时改速度 + 改朝向"】滑翔的物理在 `LivingEntity.travel` 的
        //    `isFallFlying()` 分支里（反编译实证）：它每 tick 都做一次
        //    `速度 += (视线水平单位向量 × 当前速率 - 速度水平分量) × 0.1`——也就是
        //    **持续把水平速度往"视线方向"拽**。所以只压速度不改朝向的话，最多一两 tick
        //    就被这份转向力拉回"朝敌人"，等于没弹；只改朝向不压速度则起步太慢（10%/tick）。
        //    两者一起给，才是"立刻离开 + 持续保持"。
        //    正因为有这个转向力，朝向与推力必须引用**同一个**威胁，否则会自相拉扯
        //    （朝 A 飞、被 B 推），所以这里让 pushAwayFromThreat 把威胁对象一并返回。
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
            faceUpForward(maid, target, RANGED_BOOST_PITCH);
        } else {
            faceOrbit(maid, target, holdY);
        }
    }

    /**
     * v1.2.0 实测五百零三：远程空袭的**近身弹开**（用户指定的自保机制）。
     *
     * 需求："当周围三格内出现怪物的时候，女仆被弹开（或者强制增加一个远离怪物的速度
     * 矢量），防止女仆在远程攻击的时候还是采用向敌人飞的策略，导致下落的时候直接被敌人
     * 打死。女仆自己被弹开更加平衡，弹开敌人太超模……（也保证了在狭小空间内，敌人仍然
     * 有命中的可能）"
     *
     * 【为什么必须有它】远程空袭的盘旋逻辑本身**没有"别贴脸"这个概念**：
     * `faceOrbit` 的切向+径向修正只保证"绕着一个半径 10 格的圈飞"，一旦怪物主动贴过来
     * （或者她被地形/烟花推力挤到怪物身边），盘旋的"向敌切向分量"仍然会让她贴着怪物转，
     * 而她的血量只有 20——被贴脸打两下就没了。烟花推进的"背离敌人+向上"只在起飞那一瞬
     * 有效，进入盘旋后就不再有这个保护。
     *
     * 【为什么只弹女仆、不弹怪物】用户明确否定"弹开敌人"：那等于远程角色获得一个持续
     * 的、无需操作的群体击退，太超模。弹开自己则是"用机动换安全"——她离开怪物的同时
     * 也就脱离了输出位，而且**在狭小空间里跑不掉**（墙角/洞穴），所以敌人仍然有机会命中，
     * 这正是用户要的平衡。
     *
     * 【强度取值的理由】见 {@link #RANGED_PUSH_SPEED}：不给 impulse，给的是
     * "在一两 tick 内把向敌速度压过去"的连续修正；持续 1.5 秒而不是一 tick，是因为
     * 盘旋的向敌分量每 tick 都在拉她。
     *
     * @return 本 tick 需要"弹开"时返回**要背离的那个威胁**（调用方据此同时改朝向——
     *         理由见 `tickRangedAir` 里关于滑翔转向力的说明）；不需要弹开时返回 null
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
                && maid.m_20280_(nearest) <= RANGED_PUSH_RADIUS * RANGED_PUSH_RADIUS;

        int left = RANGED_PUSH_LEFT.getOrDefault(id, 0);
        if (tooClose) {
            // 贴脸：刷新持续时间（怪物一直在身边就一直保持脱离姿态），并施加远离矢量
            RANGED_PUSH_LEFT.put(id, RANGED_PUSH_TICKS);
            left = RANGED_PUSH_TICKS;
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
                    maid.m_20191_().m_82400_(RANGED_PUSH_RADIUS),
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
        } catch (Throwable ignored) {
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
                } catch (Exception ignored) {
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
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 施加"远离该威胁"的速度矢量：水平方向背离 × {@link #RANGED_PUSH_SPEED}，
     * 竖直方向抬一点（{@link #RANGED_PUSH_UP}）。
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
                    ux * RANGED_PUSH_SPEED,
                    Math.max(v.f_82480_, 0.0) + RANGED_PUSH_UP,
                    uz * RANGED_PUSH_SPEED));
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
    }

    /**
     * 实测五百六十七（PR #8 移植）【空袭·法术层】——在空袭途中顺带向当前目标发起一次施法。
     *
     * 【需求】"女仆能使用近战/远程空袭的默认武器的同时进行法术释放"——所以这是一个
     * **叠加层**，不是新任务、也不占武器位：武器三件套（鞘翅 + 武器 + 烟花）一条不变，
     * 法术书放在**饰品栏/背包**里就行（法术模组自己扫背包与 curios，不看主手）。
     *
     * 【为什么只在这两个相位调用】法术模组在吟唱期间每 tick 把她的朝向拧向目标
     * （forceLookAtTarget 直写 yaw/pitch），而鞘翅滑翔的转向力来自视线方向
     * ——吟唱一旦开始，我们这一 tick 之后写的朝向都会被它下一 tick 覆盖。
     * 所以发起时机只挑"本来就该面向目标、且不需要背离抬头"的两处：
     * 远战盘旋（tickRangedAir ② 开火之前）、近战"已在目标上方、正要压低朝向俯冲"那一刻。
     * 而**爬升（tickClimbToAltitude）与收翅猛击那一段不发起施法**：前者要求
     * "背离敌人 + 抬头"把烟花推力吃满，后者是"这一轮唯一的致命一击"，都不能被吟唱抢朝向。
     *
     * 【距离口径】默认 24 格 = 法术模组自己的 Config.maxSpellRange（其行为层用的就是
     * 这个上限）。我们直连 provider，它不会替我们拦距离，所以这里必须自己判——
     * 判据用**3D 距离**（空袭是立体作战，敌人常在斜上方）。
     *
     * @return true = 该相位这一 tick 发起了一次施法（实际法术成不成立由法术模组决定）
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

    /**
     * 远战开火：主手是枪械（TACZ / 卓越前线）→ 走 TLM 自己的枪械通道；否则走本任务
     * 实现的 `performRangedAttack`（箭矢通道）。射程分口径：枪械用 TLM 的枪械中距离
     * 配置（`GunCompat.gunMaxRange`），弓弩用 {@link #RANGED_ATTACK_RANGE}。
     */
    private void fireRanged(EntityMaid maid, LivingEntity target, UUID id, long gameTime) {
        ItemStack main = maid.m_21205_();
        boolean gun = GunCompat.isGun(main);
        double range = gun ? GunCompat.gunMaxRange() : RANGED_ATTACK_RANGE;
        double dist = maid.m_20270_(target);
        if (dist > range) {
            return;
        }
        // v1.2.0 实测五百二十七【出手要看方块阻隔】：弓弩这一路原先不看视线（箭矢自己
        // 撞墙是另一回事——先扣冷却、白放一箭，还可能隔着树叶对着墙射）。枪械那一路
        // 本来就有 canSeeGunTarget，这里补齐弓弩，两条枪口口径一致。
        if (!SelfPreservationBehavior.hasSight(maid, target)) {
            return;
        }
        if (gun) {
            tickGunFire(maid, target, id);
            return;
        }
        if (gameTime >= RANGED_NEXT_SHOT.getOrDefault(id, 0L)) {
            try {
                maid.m_6504_(target, 1.0f);
                RANGED_NEXT_SHOT.put(id, gameTime + rangedShotCooldown(main));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 枪械开火——照搬 TLM `GunShootTargetTask.tick` 的开火段（字节码实证）：
     * ①`GunCommonUtil.tick` 负责换弹 / 上膛（每 tick 都要调）；
     * ②冷却结束且能看到目标时 `performGunAttack` 开火，返回值就是下一发冷却；
     * ③冷却再按女仆的 `MAID_GUN_ATTACK_SPEED` 属性缩放（TLM 同款）。
     * 开火失败（没子弹 / 没拉栓）它自己会返回一个大冷却，所以不必在这里额外判弹药。
     */
    private void tickGunFire(EntityMaid maid, LivingEntity target, UUID id) {
        ItemStack gun = maid.m_21205_();
        try {
            com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .tick(maid, target, gun);
        } catch (Throwable ignored) {
        }
        int cd = RANGED_GUN_CD.getOrDefault(id, 0) - 1;
        if (cd <= 0 && canSeeGunTarget(maid, target)) {
            try {
                cd = com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                        .performGunAttack(maid, target, gun);
                net.minecraft.world.entity.ai.attributes.AttributeInstance attr = maid.m_21051_(
                        com.github.tartaricacid.touhoulittlemaid.init.InitAttribute.MAID_GUN_ATTACK_SPEED.get());
                if (attr != null && attr.m_22135_() > 0.0) {
                    cd = (int) (cd / attr.m_22135_());
                }
            } catch (Throwable ignored) {
                cd = 100;
            }
        }
        RANGED_GUN_CD.put(id, cd);
    }

    private static boolean canSeeGunTarget(EntityMaid maid, LivingEntity target) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil
                    .canSee(maid, target).orElse(false);
        } catch (Throwable ignored) {
            return false;
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
        }
    }

    /** 绕目标盘旋 + 高度保持：切向视线绕圈，俯仰由"与期望高度的偏差"决定
     *  v1.2.0 实测四百八十一：立即写角度（理由同 faceAwayAndUp）。 */
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
        double radial = (r - ORBIT_RADIUS) * 0.5;
        double ox = -uz - ux * radial;
        double oz = ux - uz * radial;
        // 高度保持：低于期望高度就抬头（速度换高度），高了就低头（高度换速度）
        double err = (holdY - maid.m_20186_()) + RANGED_HOLD_BIAS;
        double pitch = -err * RANGED_HOLD_GAIN;
        pitch = Math.max(-RANGED_ORBIT_UP_MAX, Math.min(RANGED_ORBIT_DOWN_MAX, pitch));
        double h = Math.sqrt(ox * ox + oz * oz);
        float yaw = (float) (Math.atan2(oz, ox) * (180.0 / Math.PI)) - 90.0f;
        applyRotation(maid, yaw, (float) pitch);
        try {
            maid.m_21563_().m_24950_(
                    maid.m_20185_() + ox,
                    maid.m_20188_() + Math.tan(Math.toRadians(-pitch)) * h,
                    maid.m_20189_() + oz,
                    360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }
}
