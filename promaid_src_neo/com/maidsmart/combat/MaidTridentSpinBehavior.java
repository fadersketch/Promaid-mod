package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.mixin.LivingEntitySpinAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * v1.2.0 实测五百三十四【激流三叉戟：把玩家的"旋转突进"套到女仆身上】
 * ＋ 实测五百三十八【把它做成一条独立的攻击链路】。
 *
 * ── 需求原文 ──
 * 五百三十四："能不能想办法把玩家一的代码套到女仆身上呢？当处于攻击模式/近战空袭且手中
 * 的武器为激流三叉戟时调用。这样子就也可以用激流三叉戟了，就是动画这一块不知道该怎么做。"
 * 五百三十八："激流我只能听到音效和看到伤害，但是没有像玩家那样的旋转特效，动作也不明显。
 * 并且原版的三叉戟是可以往其他方向飞的。我觉得拿到激流三叉戟的时候需要单独写一条链路：
 * **攻击为朝向敌人发动一次旋转冲击然后结算伤害**（在攻击/近战空袭中生效，替换原本的攻击
 * 环节），其他的如索敌等不变。"
 *
 * ── 原版玩家是怎么做的（1.21.1 `TridentItem.releaseUsing` 字节码实证）──
 * <pre>
 *   ① 闸门：激流 > 0 且 !isInWaterOrRain → 直接 return（水里/雨里才允许）
 *   ② 方向 = 视线方向（yaw/pitch 换算）      // 所以玩家想往哪飞就往哪飞
 *   ③ 力度 = 3.0 × (1 + 激流等级) / 4        // I 级 1.5、III 级 3.0 格/tick
 *   ④ player.push(方向 × 力度)               // 加在速度上（不是直接赋值）
 *   ⑤ startAutoSpinAttack(20 tick, 伤害, 武器) → 置【标志位 4】+ 记 tick 数
 *   ⑥ 之后由 LivingEntity.tick 每 tick 递减，并 checkAutoSpinAttack：
 *      扫过本次位移的包围盒，碰到 LivingEntity 就 doAutoAttackOnTouch 并**立刻收招**；
 *      撞到横向方块（horizontalCollision）也收招
 *   ⑦ 音效按等级：TRIDENT_RIPTIDE_1/2/3；耐久扣 1（与投掷共用那一条）
 * </pre>
 *
 * ── 女仆这边怎么落地的（关键：**标志位是通用的**）──
 * 反编译实证：`autoSpinAttackTicks` 与"标志位 4"都在 **LivingEntity** 上，
 * `LivingEntity.tick` 里就会递减 + 扫掠（`checkAutoSpinAttack`），**不是 Player 专属**。
 * 旋转的**画面**同样不是 Player 专属：原版 `LivingEntityRenderer.setupRotations` 里就有
 * `isAutoSpinAttack` 分支（平躺 + 75°/tick 自转），Gecko 女仆走的那条渲染器另有一对
 * **反号重复旋转**把它抵消掉了——见 {@link com.maidsmart.mixin.MaidGeckoSpinDedupMixin}
 * （实测五百三十七已修）。
 *
 * ── 实测五百三十八：从"偶尔多打一下"改成"**她的攻击就是旋转冲击**" ──
 * 旧版的三个毛病，都是这次要解决的：
 * <ol>
 *   <li><b>动作看不清</b>：触发距离只有 5 格，而 III 级力度是 3 格/tick —— 2 tick 就撞上，
 *       而"撞到实体"按原版会**立刻收招**（`checkAutoSpinAttack` 里计数清零），于是 20 tick
 *       的旋转只放了 2 tick，等于闪一下。现在改三处：触发距离放宽到 {@link #DASH_RANGE}
 *       （10 格）、突进期间**每 tick 顶住标志位**（撞人不再提前收招，20 tick 的画面完整
 *       放完）、撞到目标后不再让它"贴脸停死"（见 {@link #PASS_THROUGH_KEEP} 的注释）。</li>
 *   <li><b>和普通挥砍并存</b>：旧版只是在旁边"多打一下"，她照样按普通三叉戟挥砍。现在
 *       持激流三叉戟时，攻击环节被本链路**替换**：TLM 原生近战（`MaidMeleeAttack`，
 *       由 {@code MaidMeleeRiptideMixin} 掐掉"近战可达"判据）与空袭自己的
 *       `smashHit`/`groundMelee` 都改走 {@link #tryStartDash}。走路、索敌、冷却、排班
 *       等一律不变。</li>
 *   <li><b>方向</b>：原版玩家想往哪飞就往哪飞（视线方向）；女仆没有"想"，所以按需求
 *       "朝向敌人"突进，竖直分量夹在 [MIN_UP, MAX_UP]（原版可以一头扎地，因为玩家会自己
 *       抬头，女仆得靠这条夹住）。</li>
 * </ol>
 *
 * ── 与玩家版本的**刻意差异**（四处，都有理由）──
 * <ol>
 *   <li><b>不要求在水里/雨中</b>：原版那道闸门是为"游泳突进"设计的，而女仆在空袭/陆战时
 *       根本不在水里——照搬闸门等于这功能永远触发不了。</li>
 *   <li><b>方向取"她→目标"而不是"她的视线"</b>，竖直分量夹住（见上）。</li>
 *   <li><b>撞人后不立刻收招</b>：原版是玩家自己撞上去、收招也很自然；女仆每次都朝怪撞，
 *       照搬就永远看不到旋转（实测五百三十八 的现象）。</li>
 *   <li><b>伤害 = 攻击力 + 附魔加成</b>：1.20.1 的玩家激流走普通 `attack()`（伤害随攻击
 *       冷却缩放），1.21.1 是固定 8.0 + 附魔；女仆统一用"攻击力 + 附魔"（与
 *       {@code MaidFlightCombatBehavior.hitOne} 同口径，不吃暴击倍率），两树一致。</li>
 * </ol>
 *
 * ── 触发口径（实测五百四十一 定稿 / 实测五百四十四 放行空中那一记）──
 * 起手只有三个入口，全都是"本来要挥砍的那一记"被替换掉：
 * <ol>
 *   <li>{@code MaidMeleeRiptideMixin} —— TLM 原生近战任务里那一记 `doHurtTarget`；</li>
 *   <li>空袭的**地面**近战 `groundMelee`（站在地上时）；</li>
 *   <li>空袭的**收翅俯冲**那一记 `smashHit`（实测五百四十四：唯一允许在**空中**起手的入口）。</li>
 * </ol>
 * 共同前提：开关开 + 攻击模式 / 空袭任务 + 主手**激流**三叉戟 + 目标不超
 * {@link #DASH_RANGE} + 不在突进中 + 收招硬直已过。总开关：`combat.riptideDash`（默认开）。
 * 地面那两个入口额外要求**站在地上**（实测五百四十一 的护栏，原样保留）。
 *
 * ── 实测五百四十四：空中那一记（收翅俯冲）──
 * 需求原文："就是将原本的近战攻击链路替换为一次面朝敌人的激流三叉戟而已。
 * 因为这个功能在空中的实战价值更大。"
 * 于是**只放行空袭的收翅俯冲这一个窗口**（`smashHit` 起手时传 `airborne = !onGround()`）；
 * TLM 原生近战那一记仍然要求站在地上 —— 五百四十一 的护栏（"别在起跳/爬升途中横插一次
 * 突进"）一条都不动，因为收翅俯冲本来就是"她主动朝敌人砸下去"的那一瞬。
 *
 * 空中与地面的三条差异（都有理由）：
 * <ol>
 *   <li><b>不提前刹住</b>：地面那版一旦离目标 1.5 格内就把速度归零（免得"顶着怪推"），
 *       而空中归零＝停在半空自由落体，且**离目标 1.5 格根本碰不到它的碰撞箱** → 白转一圈。
 *       空中改成只判"越过目标所在的那条线"，于是一路扫过它的碰撞箱——原版
 *       `checkAutoSpinAttack` 的判定是**上一帧到本帧的包围盒并集**（扫掠），
 *       扫过去就必定结算一次，这正是玩家那记"撞上去"。</li>
 *   <li><b>重力照常</b>：反编译实证原版对旋转突进**没有任何**改重力 / 改速度的特殊处理
 *       （`LivingEntity` 里 `autoSpinAttackTicks` 只出现在 `aiStep` 的递减与
 *       `checkAutoSpinAttack` 两处），所以空中旋转就是"一次朝敌人的冲量 + 正常抛物线
 *       下落"，与玩家同款。这里刻意不造"悬停"。</li>
 *   <li><b>坠落伤害由本链路兜掉</b>：既然重力照常、旋转又有 16 tick，她会边转边掉。
 *       突进期间每 tick 把坠落距离清零（与空袭收翅猛击同款口径），落地不自我摔伤；
 *       突进结束后空袭自己会切回滑翔（滑翔把坠落距离钳在 1.0）。</li>
 * </ol>
 *
 * **空袭让位**：突进期间 {@code MaidFlightCombatBehavior} 跳过自己的状态机（那 16 tick 的
 * 速度与滑翔权归突进）——否则它会在同一 tick 里放烟花（推力沿视线）+ 开滑翔
 * （`travel` 的滑翔分支会把水平速度往视线方向拽并限速），把这次旋转当场冲掉，
 * 表现成"刚起手就闪一下"。让位**只跳状态机**，三件套维护与"缺件提示"照常跑；
 * 判据是 {@link #isDashing}，它自带 {@link #SPIN_TICKS} 的硬上限与陈旧自愈，
 * 所以不会再重演 实测五百四十一 那次"卡住起飞"。
 *
 * 五百四十一 为什么改成这个模型：早先版本是"目标进 N 格就自主起手"，实机代价是
 * ①她会在空袭的起跳/俯冲途中横插一次突进，把状态机搅乱（"突然不会起飞了"，而且一旦留下
 * 陈旧的突进状态，换什么武器都一直卡着）；②玩家看不懂她什么时候会触发（"判定很奇怪，
 * 容易被别的行为覆盖"）。现在**起手时机 = 她的攻击时机**，其余时间这个行为什么都不做。
 */
public class MaidTridentSpinBehavior extends Behavior<EntityMaid> {

    /** 一次旋转的持续 tick（原版是 20；这里收到 16，收招更快、不显得"卡在原地转"） */
    private static final int SPIN_TICKS = 16;
    /** 收招硬直（tick）——原版靠武器冷却，女仆用这条防止原地连转 */
    private static final int RECOVER_TICKS = 20;
    /**
     * 触发距离（格）。实测五百三十八 曾放大到 10 想让旋转看清，结果她变成"从十格开外
     * 一路冲过去"——移动被突进接管，实机反馈"行动非常诡异"，空袭那边还会因此卡住起飞。
     * 实测五百四十一 收回**近战级**：她自己走过去（正常 AI/寻路），进了这个距离才发一次
     * 旋转冲击。想要更远的起手距离把这个值调大即可，但要知道那等于把她的接近过程也交给突进。
     *
     * 【实测五百四十四 补记】这三个常量曾在 1.21.1 树上漏改（doc 与 changelog 都写了 4/16/20，
     * 代码却还是 538 那版 10/20/10）——**两树口径必须一致**，本树这次已对齐。
     */
    private static final double DASH_RANGE = 4.0;
    /** 冲量基数：照原版 `3.0f * (1 + 激流等级) / 4.0f` */
    private static final double POWER_BASE = 3.0;
    /** 竖直分量上下限（刻意偏离原版，见类文档差异②） */
    private static final double MIN_UP = -0.15;
    private static final double MAX_UP = 0.35;
    /**
     * 越过目标之后每 tick 保留的速度比例。0 = 撞到就停在那转完剩下的圈。
     *
     * 【为什么要这一条】原版撞到实体后 `checkAutoSpinAttack` 会
     * `setDeltaMovement(delta × -0.2)` ——她是朝怪撞的，于是每 tick 都被"反弹"一次，
     * 表现成贴在怪身上抽搐。而且她也不该像玩家那样一路滑出去 30 格。所以：越过目标所在的
     * 那条线就停住，把剩下的旋转放完（画面清楚、站位也留在怪身边）。
     */
    private static final double PASS_THROUGH_KEEP = 0.0;
    /**
     * 还没越过目标时的最低速度比例。撞人那一 tick 速度会被原版乘以 -0.2，若不每 tick
     * 重新给推力就会出现"撞一下→顿住→再撞"的抖动。
     */
    private static final double APPROACH_MIN_RATIO = 0.30;

    /** 收招硬直到期 gameTime */
    private static final Map<UUID, Long> READY = new HashMap<>();
    /** 正在突进中的女仆 → 本次突进的状态（也是 canStillUse 的依据） */
    private static final Map<UUID, Dash> DASH = new HashMap<>();

    /** 一次突进的全部状态 */
    private static final class Dash {
        /** 剩余 tick */
        int left;
        /** 起手时的 gameTime —— 用来识别"陈旧的突进状态"并自愈（见 checkExtraStartConditions 的注释） */
        final long launchTick;
        /** 起手速度矢量（原样存下来，后面按剩余比例缩放） */
        final double vx;
        final double vy;
        final double vz;
        /**
         * 空中起手（实测五百四十四）：只由空袭的收翅俯冲那一记传 true。
         * 影响两处——目标到达判据（空中不提前刹住）与坠落伤害清零，见类文档。
         */
        final boolean airborne;
        /** 本次突进已经结算过的目标（同一枚目标只打一次，与"撞击即收招"的原版口径一致） */
        final Set<UUID> hit = new HashSet<>();

        Dash(long launchTick, double vx, double vy, double vz, boolean airborne) {
            this.left = SPIN_TICKS;
            this.launchTick = launchTick;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.airborne = airborne;
        }
    }

    public MaidTridentSpinBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /* ==================== 对外口径（近战链路 / 渲染 / 混入都用这些） ==================== */

    /**
     * 主手是不是"能用的激流三叉戟"：三叉戟 + 激流附魔（等级由 {@link MaidFlightKit#isRiptide} 判）
     */
    public static ItemStack spinWeapon(EntityMaid maid) {
        try {
            ItemStack main = maid.getMainHandItem();
            if (!(main.getItem() instanceof net.minecraft.world.item.TridentItem)) {
                return ItemStack.EMPTY;
            }
            return MaidFlightKit.isRiptide(main) ? main : ItemStack.EMPTY;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** 该任务是否允许激流突进：攻击模式 + 空袭（需求原文点名的两种） */
    public static boolean taskAllows(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
            if (task == null) {
                return false;
            }
            ResourceLocation uid = task.getUid();
            if (MaidFlightKit.UID.equals(uid)) {
                return true; // 空袭
            }
            return "touhou_little_maid".equals(uid.getNamespace()) && "attack".equals(uid.getPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 现在是否该由本链路**取代普通攻击**：开关开 + 这两种任务 + 主手激流三叉戟。
     *
     * 【谁在用】{@code MaidMeleeRiptideMixin}（掐掉 TLM 原生近战）与
     * {@code MaidFlightCombatBehavior} 的 `smashHit` / `groundMelee`（空袭的近战落点）。
     * 之所以要带"任务"这一条：不在这两个任务里时（种田/伐木……）她照样要能正常挥砍，
     * 否则拿激流三叉戟种田的女仆会变成不会还手的沙包。
     */
    public static boolean replacesMelee(EntityMaid maid) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.RIPTIDE_DASH_ENABLE.get()) {
                return false;
            }
            return taskAllows(maid) && !spinWeapon(maid).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 供**近战链路**在"本来要挥砍"的那一刻调用：把这一记换成朝目标的旋转冲击。
     *
     * 【地面版】要求她站在地上——TLM 原生近战与空袭地面近战这两个入口走这条。
     *
     * @return true = 已经发起突进（调用方按"这次出手了"处理：吃攻击冷却、收尾俯冲）
     */
    public static boolean tryStartDash(ServerLevel level, EntityMaid maid, LivingEntity target) {
        return tryStartDash(level, maid, target, false);
    }

    /**
     * 同 {@link #tryStartDash(ServerLevel, EntityMaid, LivingEntity)}，但可指定**空中起手**。
     *
     * @param airborne true = 允许在空中起手。当前**只有空袭的收翅俯冲那一记**这么传
     *                 （`MaidFlightCombatBehavior.smashHit` 传 `!maid.onGround()`），
     *                 见类文档"实测五百四十四：空中那一记"。
     */
    public static boolean tryStartDash(ServerLevel level, EntityMaid maid, LivingEntity target,
                                      boolean airborne) {
        if (maid == null || level == null || target == null || !replacesMelee(maid)) {
            return false;
        }
        UUID id = maid.getUUID();
        if (DASH.containsKey(id)) {
            return false; // 正在转
        }
        // 【地面起手必须站在地上】实测五百四十一：滑翔/腾空时突进会把空袭的状态机（起跳滑翔 →
        // 放烟花助推 → 俯冲）整段搅乱，实机表现就是"女仆突然不会起飞了"。
        // 实测五百四十四 只开一个例外：空袭的收翅俯冲（airborne = true，那时她本来就已收翅、
        // 正朝目标砸下去），并且空袭在突进期间会让位——见类文档"空袭让位"。
        if (!airborne && !maid.onGround()) {
            return false;
        }
        if (level.getGameTime() < READY.getOrDefault(id, 0L)) {
            return false; // 收招硬直
        }
        if (!target.isAlive() || target.level() != level) {
            return false;
        }
        if (FriendlyFireGuard.isFriendly(maid, target) || maid.distanceTo(target) > DASH_RANGE) {
            return false;
        }
        ItemStack weapon = spinWeapon(maid);
        if (weapon.isEmpty()) {
            return false;
        }
        launch(level, maid, target, weapon, airborne);
        return true;
    }

    /**
     * 现在是否**正在**旋转突进（供空袭让位与"空中禁传送"用，见类文档"空袭让位"）。
     *
     * 【为什么自带过期自愈】突进状态是 static 表：行为没跑到收招（任务切换 / 大脑重建 /
     * 女仆被收回）就会残留。外部一旦按它让位，残留就等于"永久让位"——实测五百四十一
     * 的"突然不会起飞"正是这么来的。所以这里与 {@link #checkExtraStartConditions} 用同一把
     * 尺子：超过一次旋转的时长还在，直接丢掉再回答 false。
     */
    public static boolean isDashing(EntityMaid maid) {
        try {
            return liveDash(maid) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取"有效的"突进状态：不存在 / 已过期（陈旧）都返回 null，并顺手把陈旧的丢掉 */
    private static Dash liveDash(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        UUID id = maid.getUUID();
        Dash dash = DASH.get(id);
        if (dash == null) {
            return null;
        }
        try {
            if (maid.level() instanceof ServerLevel level
                    && level.getGameTime() - dash.launchTick > SPIN_TICKS + 5) {
                DASH.remove(id);
                return null;
            }
        } catch (Throwable ignored) {
        }
        return dash;
    }

    /* ==================== 行为本体（只当"维持器"，不再自己找目标起手） ==================== */

    /**
     * 起手**只**来自"本来要挥砍的那一记"（{@link #tryStartDash}，由
     * {@code MaidMeleeRiptideMixin} 与空袭的 `groundMelee` 调用）——本行为不再自己按距离
     * 找目标起手。
     *
     * 【为什么（实测五百四十一）】早先版本是"目标进 N 格就自主起手"，结果是：
     * ①她会在空袭的起跳/俯冲途中横插一次突进，把状态机搅乱（"突然不会起飞了"，而且一旦
     * 留下陈旧的突进状态，换什么武器都一直卡着）；②玩家看不懂她什么时候会触发
     * （"判定很奇怪，不知道什么时候触发，容易被别的行为覆盖"）。
     * 现在起手时机 = **她的攻击时机**，其余时间这个行为什么都不做；它在这里的唯一职责是把
     * 已经开始的那次突进维持到结束（并且顺手自愈"陈旧的突进状态"）。
     */
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // 【自愈】陈旧状态只可能由"起手后本行为没能跑起来"留下（任务切换 / 大脑重建 /
        // 女仆被收回）。超过一次旋转的时长还在，就当它是陈旧的直接丢掉 —— 否则她会永久
        // 被判定成"正在突进"，既不能再起手，也会拖住别的链路（实测五百四十一 的"换武器也不行"）。
        // 判据与 {@link #isDashing} 共用（liveDash），外部让位与本行为启动口径永远一致。
        return liveDash(maid) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return DASH.containsKey(maid.getUUID());
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        // 起手已经在 {@link #tryStartDash} 里做完了（那里才是唯一的发动入口）。这里**不能再
        // launch 一次**——否则每次她挥砍都会起手两回、旋转计数被写两遍。
    }

    /**
     * 突进期间每 tick 的维持（实测五百三十八 加的，五百四十一 收敛过，五百四十四 补空中两条）：
     * <ol>
     *   <li><b>顶住标志位</b>：原版撞到实体就会把计数清零并清标志，这里每 tick 把剩余 tick
     *       与标志位重新写回 —— 旋转画面完整放完（见 {@link #SPIN_TICKS}）；</li>
     *   <li><b>顶住速度</b>：原版撞人后速度乘 -0.2，这里按剩余比例重新给推力，
     *       到目标就停住（地面 = 越过它所在横线**或**已到它身边 1.5 格内；
     *       空中 = 只认越过，见 {@link #PASS_THROUGH_KEEP} 与类文档差异①）；</li>
     *   <li><b>不许寻路把她拽回去</b>（这一下是"冲过去撞"，不是"走过去"）；</li>
     *   <li><b>空中不自我摔伤</b>：重力照常、她是边转边掉的，所以每 tick 清坠落距离
     *       （实测五百四十四，见类文档差异③）。</li>
     * </ol>
     * 收招条件：剩余 tick 走完 / 撞到横向方块（原版同款，但起手那一 tick 不判——见下）。
     *
     * 【不动别的东西】实测五百四十一：这里**只**管突进自己的速度与旋转，不抢空袭那边的东西。
     * （空袭侧那 16 tick 的让位由 {@code MaidFlightCombatBehavior} 自己按 {@link #isDashing}
     * 收窄执行——它只跳状态机，起跳/装备维护照旧，因此不会再卡住起飞。）
     */
    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        Dash dash = DASH.get(id);
        if (dash == null) {
            return;
        }
        // 【起手那一 tick 不判撞墙】`horizontalCollision` 反映的是**上一次移动**的结果：
        // 她贴墙起手时那一 tick 还是"撞墙"状态，判了就会刚起手就被取消。跳过首 tick。
        boolean firstTick = dash.left == SPIN_TICKS;
        if (--dash.left <= 0 || (!firstTick && maid.horizontalCollision)) {
            finish(maid, id, gameTime);
            return;
        }
        LivingEntity target = targetOf(maid);

        // ① 标志位与剩余 tick 顶回去（原版撞人/撞墙的清零由这里覆盖）
        LivingEntitySpinAccessor spin = (LivingEntitySpinAccessor) (Object) maid;
        spin.promaid$setSpinTicks(dash.left);
        spin.promaid$setLivingFlag(4, true);

        // ② 速度：还没到目标就按比例继续推（最低 APPROACH_MIN_RATIO，保证冲到底），
        //    到了之后按 PASS_THROUGH_KEEP 收住。竖直分量交给原版（重力照常），
        //    否则她会平飘，看着像飞。
        //    "到了"= 越过目标所在横线 **或** 已经贴到它身边（1.5 格内）——只判前者的话，
        //    她撞到怪的碰撞箱后就再也"过不去"，会一路顶着怪推。
        double ratio = APPROACH_MIN_RATIO;
        if (target != null) {
            double dx = maid.getX() - target.getX();
            double dz = maid.getZ() - target.getZ();
            double len = Math.sqrt(dash.vx * dash.vx + dash.vz * dash.vz);
            // "越过目标所在的横线" = 她已经在目标背后（与突进同向的点积为正）。
            boolean passed = len > 1.0E-4 && (dx * dash.vx + dz * dash.vz) / len > 0.0;
            // 【空中不提前刹住】实测五百四十四：地面那条"离它 1.5 格就停住"是为"别顶着怪推"
            // 设计的；空中照用＝停在半空自由落体，而且 1.5 格的距离根本够不到它的碰撞箱，
            // 旋转一整套放完也不结算（见类文档差异①）。空中只认"越过"，于是扫过去必中。
            boolean arrived = passed
                    || (!dash.airborne && Math.sqrt(dx * dx + dz * dz) <= 1.5);
            ratio = arrived ? PASS_THROUGH_KEEP
                            : Math.max(APPROACH_MIN_RATIO, dash.left / (double) SPIN_TICKS);
        }
        Vec3 v = maid.getDeltaMovement();
        maid.setDeltaMovement(new Vec3(dash.vx * ratio, v.y, dash.vz * ratio));

        // ③ 空中突进期间不自我摔伤（实测五百四十四，见类文档差异③）：反编译实证原版对旋转
        //    突进没有任何"抵消重力/清坠落距离"的处理，所以她是边转边掉的。这里每 tick 清零
        //    （与空袭收翅猛击的收尾同款口径）：落地那一刻只剩当 tick 的 Δy，够不到摔伤阈值。
        if (!maid.onGround()) {
            maid.resetFallDistance();
        }

        // ④ 这一秒的移动权归突进
        try {
            maid.getNavigation().stop();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        finish(maid, maid.getUUID(), gameTime);
    }

    /* ==================== 起手 / 收招 ==================== */

    /** 起手：方向 = 她→目标（竖直夹住）、冲量照原版、置标志位、音效、耐久 −1 */
    private static void launch(ServerLevel level, EntityMaid maid, LivingEntity target, ItemStack weapon,
                               boolean airborne) {
        UUID id = maid.getUUID();
        int level_ = riptideLevel(maid, weapon);

        double dx = target.getX() - maid.getX();
        double dy = target.getEyeY() - maid.getEyeY();
        double dz = target.getZ() - maid.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            len = 1.0;
        }
        double uy = Math.max(MIN_UP, Math.min(MAX_UP, dy / len));
        double uxh = dx / len;
        double uzh = dz / len;
        double hlen = Math.sqrt(uxh * uxh + uzh * uzh);
        if (hlen < 1.0E-4) {
            uxh = 0.0;
            uzh = 1.0;
            hlen = 1.0;
        }
        uxh /= hlen;
        uzh /= hlen;

        double power = POWER_BASE * (1.0 + level_) / 4.0; // ③ 照原版
        double vx = uxh * power;
        double vy = uy * power;
        double vz = uzh * power;
        maid.push(vx, vy, vz); // ④ push = 加在速度上

        // ⑤ 置 tick 数 + 标志位 4
        LivingEntitySpinAccessor spin = (LivingEntitySpinAccessor) (Object) maid;
        spin.promaid$setSpinTicks(SPIN_TICKS);
        spin.promaid$setLivingFlag(4, true);

        DASH.put(id, new Dash(level.getGameTime(), vx, vy, vz, airborne));

        // 面向目标（模型自转由渲染器负责，这里只是让她的 yRot 与突进同向）
        try {
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            maid.setYRot(yaw);
            maid.yRotO = yaw;
        } catch (Throwable ignored) {
        }
        // 突进期间别再让寻路把她拽回去
        try {
            maid.getNavigation().stop();
        } catch (Throwable ignored) {
        }

        // ⑦ 音效按等级（原版三选一）+ 耐久扣 1（原版与投掷共用那一条）
        try {
            net.minecraft.sounds.SoundEvent snd = level_ >= 3
                    ? net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_3.value()
                    : (level_ == 2 ? net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_2.value()
                                   : net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_1.value());
            level.playSound(null, maid.blockPosition(), snd, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        weapon.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);

        com.maidsmart.tool.PromaidLog.log("激流突进", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 旋转冲击（" + (airborne ? "空中" : "地面") + "起手，激流 " + level_ + " 级，力度 "
                + String.format("%.2f", power) + "，距离 "
                + String.format("%.1f", maid.distanceTo(target)) + "，目标 "
                + (target.getCustomName() != null ? target.getCustomName().getString()
                                                  : target.getType().toString()) + "）");
    }

    /**
     * 收招：清状态、留硬直、把旋转计数与标志位一起收掉。
     *
     * 【为什么标志位要显式清】原版只在 `checkAutoSpinAttack`（`m_21071_`）的尾巴里清标志位，
     * 而那个方法**只在计数 > 0 时才被调用**。所以"先把计数清零、等原版自己清标志"会漏掉：
     * 下一 tick 计数已经 ≤ 0，原版那一段整段跳过，标志位就**永远挂着**——女仆会一直保持
     * 平躺自转的姿势。这里必须自己把它清掉。
     */
    private static void finish(EntityMaid maid, UUID id, long gameTime) {
        DASH.remove(id);
        READY.put(id, gameTime + RECOVER_TICKS);
        try {
            LivingEntitySpinAccessor spin = (LivingEntitySpinAccessor) (Object) maid;
            spin.promaid$setSpinTicks(0);
            spin.promaid$setLivingFlag(4, false);
        } catch (Throwable ignored) {
        }
    }

    /** 当前锁定目标（与项目其它战斗代码同一口径：ATTACK_TARGET 记忆） */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            Optional<LivingEntity> t = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
            if (t.isEmpty()) {
                return null;
            }
            LivingEntity e = t.get();
            return e.isAlive() ? e : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
    /** 读激流等级（1.21.1 用 TLM 的 EnchantmentKeys，与 MaidFlightKit.isRiptide 同一套） */
    private static int riptideLevel(EntityMaid maid, ItemStack stack) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                    .getEnchantmentLevel(maid.level().registryAccess(),
                            net.minecraft.world.item.enchantment.Enchantments.RIPTIDE, stack);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /* ================= 伤害结算（由 MaidSpinAttackTouchMixin 调用） ================= */

    /**
     * 原版 `checkAutoSpinAttack` 碰到实体时回调 `doAutoAttackOnTouch`——那个方法在
     * `LivingEntity` 上是**空实现**（只有 Player 覆写成 `attack()`），所以女仆这一记由本方法结算。
     *
     * 【每枚目标只打一次】实测五百三十八：突进期间标志位是被我们每 tick 顶住的，所以她撞上
     * 目标后会**停在原地继续转**——原版"撞击即收招"那条天然的一次性保护就没了，
     * 不自己去重会变成每 tick 打一下。这里按本次突进记 hit 集合，同一枚目标只结算一次
     * （与"撞击即收招"的最终效果一致）。
     *
     * 口径与 {@code MaidFlightCombatBehavior.hitOne} 一致：攻击力 + 附魔加成 × 命中 → 击退 →
     * 附魔后效 → 命中音。主人/友方与非法目标一律跳过（绝不误伤）。
     */
    public static void onSpinTouch(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !target.isAlive()) {
            return;
        }
        try {
            if (FriendlyFireGuard.isFriendly(maid, target)) {
                return;
            }
            Dash dash = DASH.get(maid.getUUID());
            if (dash != null && !dash.hit.add(target.getUUID())) {
                return; // 本次突进里已经打过它了
            }
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
            if (task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask atk
                    && !atk.canAttack(maid, target)) {
                return;
            }
            ItemStack weapon = maid.getMainHandItem();
            float base = (float) maid.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            net.minecraft.world.damagesource.DamageSource src = maid.damageSources().mobAttack(maid);
            float dmg = net.minecraft.world.item.enchantment.EnchantmentHelper
                    .modifyDamage(maid.level() instanceof ServerLevel sl ? sl : null,
                            weapon, target, src, base);
            if (target.hurt(src, dmg)) {
                double dx = target.getX() - maid.getX();
                double dz = target.getZ() - maid.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01) {
                    target.knockback(0.5, dx / len, dz / len);
                }
                try {
                    maid.level().playSound(null, target.blockPosition(),
                            net.minecraft.sounds.SoundEvents.TRIDENT_HIT,
                            net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
                } catch (Throwable ignored) {
                }
                // 附魔后效（火焰附加 / 荆棘等，与 MaidMaceSmashBehavior 同款调用）
                if (maid.level() instanceof ServerLevel sl) {
                    net.minecraft.world.item.enchantment.EnchantmentHelper
                            .doPostAttackEffects(sl, target, src);
                    float kb = net.minecraft.world.item.enchantment.EnchantmentHelper
                            .modifyKnockback(sl, weapon, target, src, 0.0f);
                    if (kb > 0.0f && len > 0.01) {
                        target.knockback(kb * 0.5f, dx / len, dz / len);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清场（女仆移除 / 服务器停止） */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        READY.remove(maidId);
        DASH.remove(maidId);
    }

    public static void clearAll() {
        READY.clear();
        DASH.clear();
    }
}
