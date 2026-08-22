package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;

import java.util.Optional;
import java.util.Set;

/**
 * v1.5.134：单兵作战战术（PVP 式战斗）——替代已删除的 v1.5.132 战斗协同。
 *
 * 定位：core 行为优先级 230（低于自保 250 / 落地水 240，高于自动装备 200 / 避让 150）。
 * 只对 TLM 原生战斗任务（attack/ranged_attack/crossbow_attack/trident_attack/
 * danmaku_attack）生效，只接管【移动】，不碰攻击本身——攻击仍由 TLM 的
 * MaidMeleeAttack / MaidShootTargetTask 按原节奏执行。
 * 可行性根基（反编译实证）：1.20.1 Brain 的启动循环遍历全部 priority 组、对每个
 * STOPPED 行为尝试启动，【没有高优先级阻断 break】——常驻 core 行为不会阻止
 * WORK 组战斗行为启动；tick 顺序是低 priority 先跑（TreeMap 升序），我们 230
 * 在最末尾，导航天然最后覆盖。
 *
 * 移动接管方式（与自保逃跑同款）：每 tick 清 WALK_TARGET + 直连导航（m_26519_）。
 * MoveToTargetSink 的抑制由 MaidMoveSuppressMixin 负责（isActive 时取消本 tick），
 * TLM 的 SetWalkTargetFromAttackTargetIfTargetOutOfReach / MaidRangedWalkToTarget
 * 写的 WALK_TARGET 无人执行，我们的导航独占。
 *
 * 三套 PVP 操作：
 * 1. 近战（主手非投射武器）：
 *    - 弧形接近：追击不走直线贴脸（径向为主 + 横向偏置），少吃迎面刀
 *    - 贴脸绕圈：围绕目标圆周侧移（默认半径 2.2 格，每 12~20 tick 随机换向）
 *    - 打退拉扯（hit&run）：攻击完成的瞬间（冷却记忆刚写入）后撤 10 tick 再逼近
 *    - 跳劈接近：追击途中攻击冷却已满时概率跳跃（下落中命中 = 原版暴击 1.5 倍）
 * 2. 远程（弓/弩/三叉戟）：
 *    - 距离管理：保持在理想射程（最大射程 × 配置倍率 0.6）——原版
 *      MaidRangedWalkToTarget 会走到贴脸 0 格才射，弓贴脸 = 废物
 *    - 横移绕圈：理想距离内圆周侧移放风筝（大半径）
 * 3. 时机举盾：MaidShieldTimingMixin 拦截 MaidUseShieldTask（原版"8 格内一直举盾"
 *    = 站桩挨打），改为"攻击冷却中 + 目标贴身"才举盾、冷却满放盾攻击——攻防交替。
 *
 * 总开关：combat.tactics（面板"战斗自保"段，另含近战/远程/举盾子开关 + 两个数值）。
 */
public class MaidCombatTacticsBehavior extends Behavior<EntityMaid> {

    /** 近战接战范围（格）：目标在此距离内才接管移动（超远追击交还原版 SetWalkTarget） */
    private static final double MELEE_ENGAGE_RANGE = 8.0;
    /** 近战贴脸判定（格）：小于此距离进入绕圈/拉扯模式 */
    private static final double MELEE_MELEE_RANGE = 2.6;
    /** 攻击完成后撤时长（tick）：打一刀退一步的"退"（v1.5.141：10→14 更明显） */
    private static final int RETREAT_TICKS = 14;
    /** v1.5.280：近战贴脸后退触发距离（格）——敌人进入此距离内主动后退远离
     *  （用户："周围两格内有自己识别的敌人时,会自己往后退远离"；2.0 恰为
     *  僵尸/骷髅的近战攻击距离，贴进 2 格 = 在挨打范围内，必须拉开） */
    private static final double KITE_MELEE_RANGE = 2.0;
    /** v1.5.280：后退目标距离（格）——退到 3 格即停：女仆手长（攻击距离 3.1）
     *  完全打得到（用户："女仆的手很长,完全打得到"），同时脱离敌人近战范围 */
    private static final double KITE_BACK_DIST = 3.0;
    /** 跳劈冷却（tick） */
    private static final int JUMP_COOLDOWN = 40;
    /**
     * 跳劈垂直速度 = 玩家跳跃数值（LivingEntity.jumpFromGround 的 0.42F，
     * 峰高 ≈1.25 格）——v1.5.184：普通跳劈【只加这个垂直速度】、不改变平面
     * 速度（行走持续、空中滑翔逼近），下落时挥刀暴击；跳劈辨识度由空中挥砍 +
     * 暴击粒子/音效提供（v1.5.143→v1.5.146 曾试 0.8/0.5，v1.5.182 起定 0.42
     * 与玩家完全一致）。
     */
    private static final double JUMP_VELOCITY = 0.42;
    /**
     * v1.5.174：跳劈近距上限——距离超过此值 = "离得太远" → 冷却好就大跳跳过去
     * 给跳劈（不论敌方多少）。v1.5.185：跳劈近距上限 4.5 → 大跳触发区间
     * 4.5~6.5（JUMP_DASH_MAX，大跳实际可达并命中的距离），区间外先走近再跳。
     */
    private static final double JUMP_DASH_DIST = 4.5;
    /**
     * v1.5.174：大跳最远距离——旧 12.0 的依据（v1.5.175 注释"滞空 17 tick ×
     * 水平 0.5 = 位移 ≈8.5 格"）算错了：水平速度在空中每 tick 衰减 0.91，
     * 0.5 初速滞空 ≈19 tick 的全程位移实际只有 ≈4.6 格 → 8~12 格起跳落点
     * 距敌 3~7 格，大跳完还剩一大截非常尴尬（用户反馈）。
     * v1.5.185：收紧到 6.5——即大跳实际可达并能在下落中命中（挥刀 3D 距离
     * ≤3.1）的上限；超过 6.5 跳过去也够不着，先走近再跳。
     */
    private static final double JUMP_DASH_MAX = 6.5;
    /** v1.5.175：抛物线大跳参数——垂直初速 0.95（峰高 ≈5.6 格，明显大弧线）；
     *  水平初速按距离缩放（近跳慢/远跳快，落点恰好落在敌人附近，不过头不扑空）
     *  v1.5.181：0.95 → 0.75（峰高 ≈3.5 格）——旧版跳太高触发落地水（落脚瞬间
     *  自动放水），不帅且打断战斗节奏；0.75 是 v1.5.154 历史实证值（落距 3.5
     *  无伤、不触发落地水），仍比正常跳劈（1.7 格）高一大截，抛物线依旧明显 */
    private static final double JUMP_DASH_VERTICAL = 0.75;
    private static final double JUMP_DASH_HORIZ_MIN = 0.25;
    private static final double JUMP_DASH_HORIZ_MAX = 0.5;
    /** v1.5.181：大跳（跳劈斩）冷却 10 秒——防止不间断连续大跳；正常跳劈
     *  （普通垂直跳）不受影响，仍走 JUMP_COOLDOWN（2 秒） */
    private static final int JUMP_DASH_CD = 200;
    /**
     * v1.5.174：单敌跳劈概率（主体是跳劈——穿插横扫补刀）。
     * v1.5.282：0.7 → 1.0【单敌必跳劈】——用户："剑面对单怪直接改为 100%，
     * 因为本来就会在跳劈的中间穿插横扫"。跳劈冷却 2 秒空档由 TLM 攻击行为
     * （MaidMeleeAttack）自动打普攻/横扫（我们只接管移动+跳劈，不碰攻击），
     * 所以跳劈 100% 不会挤掉横扫——每次跳劈之间 TLM 照常挥砍补刀。
     */
    private static final double SOLO_JUMP_CHANCE = 1.0;
    /** v1.5.188c：群怪跳劈概率常量已删除——多敌【完全不跳劈】（尽可能横扫） */
    /** v1.5.174：人数分流判定半径（索敌/接战范围 8 格——逼近战斗的敌人才算数） */
    private static final double CROWD_RADIUS = 8.0;

    /** v1.5.202（战斗自保）：战术总开关——不再限定战斗任务：任何任务/跟随/待机
     *  被怪物锁定在接战范围内都会进入战术战斗（"该打就打"）；轻量自保只负责保命
     *  动作，两者互补不冲突（盾牌 mixin 判定用） */
    public static boolean isTacticsEnabled(EntityMaid maid) {
        return com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS.get();
    }

    /** 是否战术激活（MaidMoveSuppressMixin 用）：开关 + 有存活目标 + 接战范围内 */
    public static boolean isActive(EntityMaid maid) {
        if (!isTacticsEnabled(maid)) {
            return false;
        }
        Optional<LivingEntity> target = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
        if (target.isEmpty() || !target.get().m_6084_()) {
            return false;
        }
        return maid.m_20270_(target.get()) <= engageRange(maid);
    }

    /** 接战范围：近战 8 格；远程（弓/弩/三叉戟/枪械 v1.1.0）= 任务搜索半径 */
    private static double engageRange(EntityMaid maid) {
        ItemStack main = maid.m_21205_();
        if (main.m_41720_() instanceof ProjectileWeaponItem
                || main.m_41720_() instanceof TridentItem
                || GunCompat.isGun(main)) {
            return maid.searchRadius();
        }
        return MELEE_ENGAGE_RANGE;
    }

    /**
     * 时机举盾判定（MaidShieldTimingMixin 用）：
     * - 近战敌人：攻击冷却中 + 目标贴身 → 举盾格挡；冷却满 → 放盾攻击（攻防交替）；
     * - 远程敌人（v1.5.135 骷髅对策）：15 格内有视线就举盾——盾挡箭矢推进，
     *   贴身再放盾砍（PVP 打骷髅的标准操作；原版"一直举盾"是站桩挨打，
     *   v1.5.134 只贴身举盾又导致骷髅射程内不挡，这次两者都修）；
     * - 弓/弩任务（主手投射武器）：放风筝优先，不举盾。
     */
    public static boolean shouldUseShield(EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_SHIELD.get()) {
            return false;
        }
        if (!maid.canUseShield()) {
            return false;
        }
        // 弓/弩任务：主手投射武器 → 自己放风筝，不举盾
        // v1.1.0：枪械任务同理——双手持枪射击，不举盾
        if (maid.m_21205_().m_41720_() instanceof ProjectileWeaponItem
                || GunCompat.isGun(maid.m_21205_())) {
            return false;
        }
        Optional<LivingEntity> target = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
        if (target.isEmpty() || !target.get().m_6084_()) {
            return false;
        }
        LivingEntity t = target.get();
        double d = maid.m_20270_(t);
        // 远程敌人：举盾格挡推进（盾挡箭矢，接近到贴身再放盾砍）
        if (isRangedAttacker(t)) {
            return d < 15.0 && SelfPreservationBehavior.hasSight(maid, t);
        }
        // 近战敌人：攻防交替（冷却中 + 贴身才举盾，保持机动）
        if (d > 2.6) {
            return false;
        }
        return maid.m_6274_().m_21952_(MemoryModuleType.f_26373_).isPresent();
    }

    /** v1.5.135：是否远程攻击者——RangedAttackMob（骷髅/烈焰人等）或手持弓/弩/三叉戟/投掷物 */
    private static boolean isRangedAttacker(LivingEntity target) {
        if (target instanceof net.minecraft.world.entity.monster.RangedAttackMob) {
            return true;
        }
        for (net.minecraft.world.item.ItemStack s : new net.minecraft.world.item.ItemStack[]{
                target.m_21205_(), target.m_21206_()}) {
            net.minecraft.world.item.Item item = s.m_41720_();
            if (item instanceof ProjectileWeaponItem || item instanceof TridentItem
                    || GunCompat.isGun(s)
                    || item instanceof net.minecraft.world.item.SnowballItem
                    || item instanceof net.minecraft.world.item.EggItem
                    || item instanceof net.minecraft.world.item.EnderpearlItem
                    || item instanceof net.minecraft.world.item.ExperienceBottleItem
                    || item instanceof net.minecraft.world.item.SplashPotionItem
                    || item instanceof net.minecraft.world.item.LingeringPotionItem) {
                return true; // 弓/弩/三叉戟/雪球/鸡蛋/末影珍珠/经验瓶/投掷药水等
            }
        }
        return false;
    }

    /** 绕圈方向（±1）与角度 */
    private int orbitSide = 1;
    private double orbitAngle = 0;
    /** 换向倒计时 */
    private int orbitSwapTick = 0;
    /** v1.5.141：蛇形走位（追击时左右摆动逼近，躲远程箭矢）——侧偏方向与换向倒计时 */
    private int serpentSide = 1;
    private int serpentTick = 0;
    /** 近战后撤剩余 tick（>0 时只退不追） */
    private int retreatTicks = 0;
    /** 上一 tick 是否冷却中（检测"攻击完成瞬间"） */
    private boolean wasCooling = false;
    /** 跳劈冷却 */
    private int jumpCooldown = 0;
    /** v1.5.181：大跳（跳劈斩）冷却——10 秒内不重复大跳；正常跳劈不受影响 */
    private int jumpDashCooldown = 0;
    /** v1.5.143：跳劈暴击特效——本次跳劈是否还在空中（起跳置 true、落地清 false） */
    private boolean jumpAir = false;
    /** v1.5.143：本次跳劈是否已挥砍过（一次跳劈只砍一刀） */
    private boolean critDone = false;
    /** v1.5.154：本次跳劈是否为"跳劈斩"（30% 概率：跳向敌人 + 大击退；
     *  否则为普通垂直跳，仅改变高度） */
    private boolean dashAttack = false;
    /** v1.5.155：本次跳劈的起跳 gameTime（挥砍延迟用——先跳起来、隔一会再出刀） */
    private long jumpStartTime = 0;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public MaidCombatTacticsBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!isTacticsEnabled(maid)) {
            return false;
        }
        // 近战/远程子战术全关 → 不接管（举盾 mixin 独立于本行为）
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_MELEE.get()
                && !com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_RANGED.get()) {
            return false;
        }
        return isActive(maid);
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        this.orbitSide = maid.m_217043_().m_188501_() < 0.5 ? -1 : 1;
        this.orbitAngle = maid.m_217043_().m_188503_(628) / 100.0; // 0~6.28
        this.orbitSwapTick = 0;
        this.retreatTicks = 0;
        this.wasCooling = false;
        this.jumpCooldown = 0;
        // v1.5.188c：start 不再重置大跳 CD——之前行为每次进场都清零，10 秒 CD
        // 形同虚设（每场战斗 start 一次 → 永远能跳）；CD 改为跨场持续（每次大跳
        // 置 200，期间无论是否脱离战斗都不再大跳，直到倒计时归零）
        this.jumpAir = false;
        this.critDone = false;
        this.dashAttack = false;
        this.jumpStartTime = 0;
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.202（边打边保命）：自保正在执行移动类保命动作（濒死垫高/逃跑、
        // 环境逃生导航）→ 本 tick 让位——不抢移动控制（走位/跳劈会与自保的
        // 垫高/逃跑互相覆盖）；瞬时保命动作（喝药/传送）不在集合内，战斗照常
        if (SelfPreservationBehavior.isMovingToSurvive(maid)) {
            return;
        }
        Optional<LivingEntity> ot = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
        if (ot.isEmpty() || !ot.get().m_6084_()) {
            return;
        }
        LivingEntity target = ot.get();
        // 兜底清 WALK_TARGET（MoveToTargetSink 已被 mixin 取消，双保险防时序）
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        // v1.5.143：跳劈暴击检测——放在坠落早退【之前】：跳劈的命中发生在下落中
        //（fallDistance > 2.5 时 meleeTick 已早退），这里独立捕捉"空中 + 攻击冷却
        // 刚写入"的瞬间，在目标身上喷暴击粒子（玩家跳劈同款特效）
        this.tickJumpCrit(level, maid, target);
        // 坠落中不导航：让落地水/物理接管（防战术行为干扰落地水的停导航）。
        // v1.5.137：旧 m_20162_() 实为 isSneaking（字节码实证 getFlag(1)），
        // 改用 fallDistance 直接判定——落距 > 2.5 即视为坠落中
        // v1.5.164：阈值 2.5 → 4.0——跳劈斩（dash）起跳 0.75 落距约 3.5（无伤，
        // 不需要落地水），旧阈值把 dash 跳劈落地瞬间误判"坠落中"→ 落地后 1~2 tick
        // 站立空档不移动不攻击（"跳劈连贯性差"的一环）；>4 才是真正坠落（落地水接管）
        if (maid.f_19789_ > 4.0f) {
            return;
        }
        ItemStack main = maid.m_21205_();
        if (main.m_41720_() instanceof ProjectileWeaponItem
                || main.m_41720_() instanceof TridentItem
                || GunCompat.isGun(main)) {
            if (com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_RANGED.get()) {
                this.rangedTick(maid, target);
            }
        } else if (com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_MELEE.get()) {
            this.meleeTick(maid, target);
        }
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        if (!m_6114_(level, maid)) {
            return false; // 目标消失/切任务/超距 → 交还移动权
        }
        return true;
    }

    /* ============ 近战 ============ */

    private void meleeTick(EntityMaid maid, LivingEntity target) {
        double dist = maid.m_20270_(target);
        boolean cooling = maid.m_6274_().m_21952_(MemoryModuleType.f_26373_).isPresent();
        // 攻击完成瞬间（冷却记忆刚写入）→ 后撤拉扯：打一刀退一步
        // v1.5.155：只适用于横扫——跳劈期间（jumpAir）不触发后撤（跳劈是接近
        // 动作，跳完不该后退）
        if (cooling && !this.wasCooling && !this.jumpAir) {
            this.retreatTicks = RETREAT_TICKS;
        }
        this.wasCooling = cooling;
        if (this.retreatTicks > 0) {
            this.retreatTicks--;
            // v1.5.141：后撤 3.5 格（原 3.0，更明显躲开敌人攻击范围）+ 1.3 速
            this.navigateAway(maid, target, 3.5, 1.3f);
            return;
        }
        // v1.5.280：近战贴脸后退——敌人贴进 2 格内主动后退拉开（不再贴身互搏：
        // 贴身白挨敌人近战刀，女仆手长退到 3 格照样挥砍）。触发条件天然满足
        // "战斗 + 非自保"：本行为在自保移动类保命动作时已让位（m_6725_ 开头
        // isMovingToSurvive），能走到这里就是正常战斗走位。
        // 与打一刀退一步（retreatTicks，攻击瞬间 14 tick 后撤）互补：前者是
        // 攻击节奏的短撤，这里是贴脸持续拉开；与跳劈互补：贴脸（<2 格）不跳劈，
        // 后退拉开到 3 格后再蛇形接近/跳劈——斧的"跳劈→普攻→后退"节奏由此成立
        if (com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_MELEE_KITE.get()
                && dist < KITE_MELEE_RANGE && !this.jumpAir) {
            this.navigateAway(maid, target, KITE_BACK_DIST - dist, 1.15f);
            return;
        }
        if (dist > MELEE_MELEE_RANGE) {
            // v1.5.174：跳劈触发重构——【距离触发 + 人数分流】：
            // - 离得太远（> JUMP_DASH_DIST，跳劈近距上限之外）→ 冷却好就【大跳】
            //   跳过去给跳劈（不论敌方多少）；太远（>6.5，大跳够不着）先走近再跳
            //   （v1.5.185：旧 12 起跳会"大跳完还剩一大截"，已收紧到实际可达）。
            // - 跳劈距离内按【索敌范围敌人数量】分流：
            //   单敌 → 主体跳劈（70% 概率——"主体仍然是尝试跳劈"），跳劈空档由
            //   TLM 正常攻击穿插横扫补伤害（不再有单独的"平A"状态）；
            //   多敌（索敌范围 ≥2）→ 尽可能横扫（跳劈概率降到 12%）。
            boolean crowd = this.nearbyEnemies(target, CROWD_RADIUS) >= 2;
            // v1.5.181：大跳（跳劈斩）10 秒冷却——CD 期间远距不再必跳（先走近），
            // 近距的 30% 跳劈斩概率也被抑制（只做普通垂直跳）；正常跳劈不受影响
            boolean dashReady = this.jumpDashCooldown <= 0;
            // v1.5.277：斧特殊战术——斧无横扫（ToolActions.SWORD_SWEEP 只有剑），
            // 跳劈是它唯一的高伤手段：每一击都尝试跳劈（无视单/群怪分流——旧版
            // 70% 概率 + 群怪全禁是剑的横扫节奏，不适用于斧）
            boolean axeMode = maid.m_21205_().m_41720_()
                    instanceof net.minecraft.world.item.AxeItem;
            // v1.5.188c：群怪抑制跳劈——多敌（索敌范围 ≥2）时【完全不跳劈】
            //（旧版 12% 概率仍会跳，用户反馈"面对群怪反而特别喜欢用跳劈"；
            // 群怪应尽最大可能横扫补伤害，跳劈单体收益低还破坏横扫节奏）
            if (this.jumpCooldown <= 0 && dist > 1.8) {
                boolean tryJump;
                if (axeMode) {
                    // 斧：跳劈距离内 100% 尝试跳劈；更远需大跳 → 受 10 秒大跳 CD
                    // 约束（CD 期间先走近再跳）
                    tryJump = dist <= JUMP_DASH_DIST
                            || (dist < JUMP_DASH_MAX && dashReady);
                } else if (dist > JUMP_DASH_DIST) {
                    tryJump = dist < JUMP_DASH_MAX && dashReady && !crowd; // 远距大跳受 CD + 群怪抑制
                } else {
                    // 跳劈距离内：单敌必跳劈（v1.5.282：100%——跳劈空档由 TLM
                    // 攻击行为穿插横扫）/ 多敌完全不跳（v1.5.188c 群怪尽横扫）
                    tryJump = crowd ? false
                            : maid.m_217043_().m_188501_() < SOLO_JUMP_CHANCE;
                }
                if (tryJump) {
                    // v1.5.154：跳劈更帅——30% 概率【跳劈斩】：朝敌人方向【跳】过去
                    //（v1.5.155：起跳 0.75 ≈ 3.5 格峰高、水平按距离缩放滑翔——弧线
                    //  明显，下落中挥刀 + 大击退，见 tickJumpCrit）；其余情况普通
                    //  跳劈（纯垂直 0.42，玩家同款高度，见下方 v1.5.184 分支）。
                    // v1.5.174：远距大跳 = 强制跳劈斩（跳过去）；近距 30% 概率斩、否则竖直跳
                    // v1.5.181：大跳受 10 秒 CD——CD 期间近距只做普通跳劈（不受影响）
                    this.dashAttack = dist > JUMP_DASH_DIST
                            || (dashReady && maid.m_217043_().m_188501_() < 0.3);
                    if (this.dashAttack) {
                        double dx = target.m_20185_() - maid.m_20185_();
                        double dz = target.m_20189_() - maid.m_20189_();
                        double len = Math.sqrt(dx * dx + dz * dz);
                        if (len < 0.01) {
                            len = 1.0;
                            dx = 0.0;
                            dz = 0.0;
                        }
                        // v1.5.175：抛物线大跳（用户要求"那种抛物线曲线"）——
                        // v1.5.181：垂直初速 0.95 → 0.75（峰高 ≈3.5 格——刚好不触发
                        // 落地水，v1.5.154 历史实证值；落地时清 fallDistance 无伤）；
                        // v1.5.185：水平初速按【实际可达距离】缩放——水平速度空中
                        // 每 tick 衰减 0.91，0.5 初速滞空 ≈19 tick 全程位移仅 ≈4.6
                        // 格（旧 dist*0.05 公式在 6 格起跳只给 0.3，落点距敌 3+ 格、
                        // 下落中 3D 距离 >3.1 砍不中 = "大跳完还剩相当远"的尴尬）；
                        // 新公式 (dist-2.5)/7 让下落中段（t≈15~17）恰好进入 3.1
                        // 命中窗口，落点距敌 ≈1.4~2.4 格（贴近不扑空）
                        double horiz = Math.max(JUMP_DASH_HORIZ_MIN,
                                Math.min(JUMP_DASH_HORIZ_MAX, (dist - 2.5) / 7.0));
                        maid.m_20256_(new net.minecraft.world.phys.Vec3(
                                dx / len * horiz, JUMP_DASH_VERTICAL, dz / len * horiz));
                        // v1.5.181：大跳 10 秒 CD（防连续大跳）
                        this.jumpDashCooldown = JUMP_DASH_CD;
                    } else {
                        // v1.5.184：普通跳劈 = 纯玩家式跳劈——【不改变平面速度】
                        //（X/Z 原样保留，行走继续、空中滑翔逼近），仅把 Y 设为
                        // 玩家跳跃同款 0.42（峰高 ≈1.25 格，跟玩家完全一致），
                        // 隔零点几秒后下落中挥刀暴击（见 tickJumpCrit）。
                        // 旧版（v1.5.182）水平前扑 0.3 与导航移动互相叠加干扰、
                        // 落点漂移 → 观感就是"莫名其妙跳一下"（动作同步没到位）；
                        // 纯垂直跳后女仆按行走速度滑翔，动作自然连贯、砍得中。
                        net.minecraft.world.phys.Vec3 mv = maid.m_20184_();
                        maid.m_20256_(new net.minecraft.world.phys.Vec3(
                                mv.m_7096_(), JUMP_VELOCITY, mv.m_7094_()));
                    }
                    this.jumpAir = true; // 标记跳劈空中状态（暴击挥砍检测用）
                    this.critDone = false;
                    // v1.5.155：记录起跳时刻（挥砍延迟——先跳、隔一会再出刀）
                    this.jumpStartTime = maid.m_9236_().m_46467_();
                    // v1.5.164：单敌跳劈冷却 25 → 40 tick（2 秒一次）——旧 1.25 秒一次
                    // 女仆几乎不停跳劈（"兔子跳"），连贯性/节奏差；2 秒一次更像战术动作，
                    // 中间留给绕圈/普攻/横扫衔接
                    this.jumpCooldown = crowd ? JUMP_COOLDOWN : 40;
                    // v1.5.149：跳劈诊断日志（确认"有没有产生跳劈"用）
                    LOGGER.info("jump attack trigger: maid={} dist={} dash={} crowd={} axe={}",
                            maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                            String.format("%.1f", dist), this.dashAttack, crowd, axeMode);
                }
            }
            // v1.5.141：蛇形走位逼近（每 8 tick 换侧偏方向，左右摆动躲箭）——
            // 替代旧固定横向偏置的弧形接近；面对远程敌人配合时机举盾 = 举盾蛇形推进
            if (this.serpentTick-- <= 0) {
                this.serpentTick = 8;
                this.serpentSide = -this.serpentSide;
            }
            this.navigateArc(maid, target, this.serpentSide * 1.2, 1.05f);
        } else {
            // 贴脸绕圈：圆周侧移，12~20 tick 换向
            if (this.orbitSwapTick-- <= 0) {
                this.orbitSwapTick = 12 + maid.m_217043_().m_188503_(9);
                this.orbitSide = -this.orbitSide;
            }
            this.navigateOrbit(maid, target,
                    com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_ORBIT_RADIUS.get(),
                    1.0f);
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        if (this.jumpDashCooldown > 0) {
            this.jumpDashCooldown--;
        }
    }

    /** v1.5.152：目标周围 radius 格内的敌对单位数（Monster 类；横扫范围命中数判定用） */
    private int nearbyEnemies(LivingEntity target, double radius) {
        try {
            return target.m_9236_().m_6443_(net.minecraft.world.entity.monster.Monster.class,
                    target.m_20191_().m_82400_(radius), m -> m.m_6084_()).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * v1.5.149：跳劈空中挥砍 + 暴击提示（每 tick 调用，先于坠落早退执行）。
     * 旧版只"跳"不"打"——攻击仍由 TLM 攻击行为执行，它通常在【落地后】才挥砍
     * （onGround）→ 不满足"空中命中"→ 暴击粒子永不触发（"跳劈不明显"根因）。
     * 现在跳劈的挥砍由本行为亲自执行：空中贴近目标 → 玩家式暴击结算（挥刀动画 +
     * 伤害（攻击力×1.5 + 附魔加成）+ 暴击粒子 + 暴击音效）。一次跳劈只砍一刀。
     * v1.5.184：结算完全对齐玩家 Player.attack 的暴击分支——普通跳劈（纯垂直
     * 0.42 跳）与跳劈斩（抛物线大跳）都走玩家暴击：伤害 = ATTACK_DAMAGE × 1.5
     * + 锋利加成，命中后触发火焰附加（主手）与荆棘反击（目标护甲）；跳劈斩
     * 额外大击退（1.5 力度劈飞，冲刺跳劈同款手感）。
     */
    private void tickJumpCrit(ServerLevel level, EntityMaid maid, LivingEntity target) {
        if (!this.jumpAir) {
            return;
        }
        if (maid.m_20096_()) {
            this.jumpAir = false; // 落地 → 本次跳劈结束
            this.critDone = false;
            // v1.5.174：落地后不再禁横扫——跳劈空档由 TLM 正常攻击穿插横扫
            // 补伤害（用户："平A没意义"——不再有单独的平A状态）
            // v1.5.175：跳劈落地清摔落距离——抛物线大跳垂直初速 0.75（峰高
            // ≈3.5 格）自然摔落仍可能掉血，跳劈是进攻动作不应自损（v1.5.181：
            // 0.95→0.75 后落距 <4，也不再触发落地水）
            maid.f_19789_ = 0.0f;
            return;
        }
        // v1.5.155：挥砍延迟——先跳起来、隔一段时间再出刀（观感同步：
        // 跳跃在前、伤害在后）。
        // v1.5.184：普通跳劈延迟 7 tick（≈0.35 秒——0.42 垂直滞空 ≈10 tick，
        // 过峰后下落中出刀，"隔个零点几秒"）；跳劈斩延迟 11 tick（滞空 ≈19 tick，
        // 下落中段出刀）
        long elapsed = level.m_46467_() - this.jumpStartTime;
        int swingDelay = this.dashAttack ? 11 : 7;
        if (elapsed < swingDelay) {
            return;
        }
        if (!this.critDone && target.m_6084_()
                && maid.m_20270_(target) <= MELEE_MELEE_RANGE + 0.5) {
            if (this.swingCritHit(level, maid, target)) {
                this.critDone = true;
            }
        }
    }

    /**
     * v1.5.184：玩家式暴击结算（对齐 Player.attack 暴击分支）——
     * 挥刀动画 + 伤害 = ATTACK_DAMAGE × 1.5 + 锋利附魔加成（暴击倍率只乘攻击力，
     * 附魔后加——与玩家完全一致），命中后喷暴击粒子 + 暴击音效（玩家暴击同款
     * "叮"声）；跳劈斩（大跳）额外把目标【大击退】劈飞（1.5 力度，方向 =
     * 女仆→目标——玩家冲刺跳劈的动量击退手感）；最后按玩家同款触发火焰附加
     * （主手）与荆棘反击（目标护甲）。
     */
    private boolean swingCritHit(ServerLevel level, EntityMaid maid, LivingEntity target) {
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥刀动画
        net.minecraft.world.item.ItemStack weapon = maid.m_21205_();
        float base = (float) maid.m_21133_(
                net.minecraft.world.entity.ai.attributes.Attributes.f_22281_);
        float ench = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44833_(
                weapon, target.m_6336_());
        float dmg = base * 1.5F + ench; // 玩家暴击：攻击力 × 1.5 + 附魔加成
        boolean hit = target.m_6469_(maid.m_269291_().m_269333_(maid), dmg);
        if (!hit) {
            return false;
        }
        if (this.dashAttack) {
            double dx = target.m_20185_() - maid.m_20185_();
            double dz = target.m_20189_() - maid.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                target.m_147240_(1.5, dx / len, dz / len);
            }
            this.spawnCritParticles(level, target, 16); // 跳劈斩粒子更多更帅
            this.playCritSound(level, target);
            LOGGER.info("jump dash crit: maid={} target={} dmg={}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    target.m_5446_() != null ? target.m_5446_().getString() : target.m_6095_().toString(),
                    String.format("%.1f", dmg));
        } else {
            this.spawnCritParticles(level, target, 12);
            this.playCritSound(level, target);
            LOGGER.info("jump crit hit: maid={} target={} dmg={}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    target.m_5446_() != null ? target.m_5446_().getString() : target.m_6095_().toString(),
                    String.format("%.1f", dmg));
        }
        // 玩家同款附魔后效：火焰附加（主手——m_44896_ 只扫护甲，女仆非玩家，
        // 按 Player.attack 的 m_44914_ 逻辑手动点燃）+ 荆棘（目标护甲反击）
        int fireAspect = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44914_(maid);
        if (fireAspect > 0 && !target.m_6060_()) {
            target.m_20254_(fireAspect * 4);
        }
        net.minecraft.world.item.enchantment.EnchantmentHelper.m_44823_(target, maid);
        return true;
    }

    /** v1.5.154：暴击粒子（minecraft:crit，count 颗大扩散——普通跳劈 12 颗、跳劈斩 16 颗） */
    private void spawnCritParticles(ServerLevel level, LivingEntity target, int count) {
        try {
            net.minecraft.core.particles.SimpleParticleType crit =
                    (net.minecraft.core.particles.SimpleParticleType) net.minecraftforge.registries.ForgeRegistries.PARTICLE_TYPES
                            .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:crit"));
            if (crit == null) {
                return;
            }
            double x = target.m_20185_();
            double y = target.m_20186_() + target.m_20206_() * 0.6; // 身体中上部
            double z = target.m_20189_();
            level.m_8767_(crit, x, y, z, count, 0.3, 0.2, 0.3, 0.2);
        } catch (Exception ignored) {
        }
    }

    /** v1.5.149：暴击音效（玩家暴击同款 minecraft:entity.player.attack.crit——明显的提示声） */
    private void playCritSound(ServerLevel level, LivingEntity target) {
        try {
            net.minecraft.sounds.SoundEvent snd =
                    net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:entity.player.attack.crit"));
            if (snd == null) {
                return;
            }
            level.m_5594_(null, target.m_20183_(), snd,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    /* ============ 远程 ============ */

    private void rangedTick(EntityMaid maid, LivingEntity target) {
        double dist = maid.m_20270_(target);
        ItemStack main = maid.m_21205_();
        // v1.1.0：枪械走 TLM 枪械距离配置（与 gun_attack 任务同源）；三叉戟不是
        // ProjectileWeaponItem → 任务搜索半径兜底；弓/弩用原版 getRange（弓 15 / 弩 8）
        int maxRange = main.m_41720_() instanceof ProjectileWeaponItem pw
                ? pw.m_6615_()
                : GunCompat.isGun(main) ? (int) GunCompat.gunMaxRange()
                : (int) maid.searchRadius();
        double ideal = maxRange * com.maidsmart.config.MaidSmartConfig.COMBAT_TACTICS_KITE_RANGE.get();
        if (dist < ideal * 0.55) {
            // 太近（弓贴脸 = 废物）：后退拉开
            this.navigateAway(maid, target, Math.max(4.0, ideal - dist + 2.0), 1.15f);
        } else if (dist > ideal * 1.4) {
            // 太远：前进逼近
            this.navigateTo(maid, target, 1.05f);
        } else {
            // 理想距离：横移绕圈放风筝（大半径，15~25 tick 换向）
            if (this.orbitSwapTick-- <= 0) {
                this.orbitSwapTick = 15 + maid.m_217043_().m_188503_(11);
                this.orbitSide = -this.orbitSide;
            }
            this.navigateOrbit(maid, target, Math.max(3.0, maxRange * 0.45), 1.0f);
        }
    }

    /* ============ 导航辅助（全部直连导航，绕开 MoveToTargetSink） ============ */

    /** 直线走向目标 */
    private void navigateTo(EntityMaid maid, LivingEntity target, float speed) {
        maid.m_21573_().m_26519_(target.m_20185_(), target.m_20186_(), target.m_20189_(), speed);
    }

    /** 远离目标：反方向走 dist 格（带地面高度修正） */
    private void navigateAway(EntityMaid maid, LivingEntity target, double dist, float speed) {
        double dx = maid.m_20185_() - target.m_20185_();
        double dz = maid.m_20189_() - target.m_20189_();
        double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        double tx = maid.m_20185_() + dx / len * dist;
        double tz = maid.m_20189_() + dz / len * dist;
        maid.m_21573_().m_26519_(tx, this.groundY(maid, tx, tz), tz, speed);
    }

    /** 弧形接近：目标点带横向偏置（绕到侧面打，少吃迎面刀） */
    private void navigateArc(EntityMaid maid, LivingEntity target, double side, float speed) {
        double dx = target.m_20185_() - maid.m_20185_();
        double dz = target.m_20189_() - maid.m_20189_();
        double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        double px = target.m_20185_() + (-dz / len) * side;
        double pz = target.m_20189_() + (dx / len) * side;
        maid.m_21573_().m_26519_(px, this.groundY(maid, px, pz), pz, speed);
    }

    /** 绕圈：以目标为圆心、半径 r 的圆周切向运动。角速度与半径成反比
     *  （0.2 / r → 切向线速度恒约 0.2 格/tick ≈ 4 格/秒，略低于走路速度，
     *  导航目标点追得上，路径平滑不抖动——写死角速度在大半径时目标点
     *  每秒移动 12 格，导航会持续重算路径导致绕圈抖动） */
    private void navigateOrbit(EntityMaid maid, LivingEntity target, double r, float speed) {
        this.orbitAngle += this.orbitSide * (0.2 / Math.max(1.0, r));
        double px = target.m_20185_() + Math.cos(this.orbitAngle) * r;
        double pz = target.m_20189_() + Math.sin(this.orbitAngle) * r;
        maid.m_21573_().m_26519_(px, this.groundY(maid, px, pz), pz, speed);
    }

    /** 目标点地面高度：从女仆当前 y 向下找第一个非空气格（防导航目标悬空） */
    private double groundY(EntityMaid maid, double x, double z) {
        int y = (int) Math.floor(maid.m_20186_());
        for (int i = 0; i < 6; i++) {
            if (!maid.m_9236_().m_8055_(new net.minecraft.core.BlockPos(
                    (int) Math.floor(x), y - 1 - i, (int) Math.floor(z))).m_60795_()) {
                return y - i;
            }
        }
        return maid.m_20186_();
    }
}
