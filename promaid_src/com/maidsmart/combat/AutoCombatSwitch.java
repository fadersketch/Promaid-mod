package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 主动切换战斗模式（v1.1.0）。
 *
 * 触发（v1.1.0 实测二十扩展）：主人被攻击（任意来源——敌对生物/玩家/弹射物，
 * 自伤除外）或主人攻击了【敌对生物】（主动开火也算开战；实测八十四b 起打被动
 * 生物不算——宰牲畜不参战，也根绝"无威胁战斗收不回去"）→ 响应半径内所有自己的
 * 女仆（非自保、非战斗任务、非幼年）立即切换为战斗任务——无论她当前在干什么
 * （挖矿/伐木/烹饪/建造/跟随…）。
 *
 * 选模式（v1.1.0 实测二十重构）：
 * - 扫描 TaskManager 全部实现 IAttackTask 的攻击类任务（含模组任务：
 *   万法皆通法术/史诗战斗/真正的力量/TLM 枪械等）
 * - 按任务自己的 isWeapon 匹配背包武器过滤出候选
 * - 候选池加权随机：模组任务权重 2.0、原版五件套（近战/弓/弩/三叉戟/弹幕）
 *   权重 1.0（模组武器普遍更强，降半权但不绝对排除）
 * - 全都匹配不上（无任何攻击物品）→ 不参战维持原任务（实测六十七；
 *   「空手不参战」开关可关回旧的空手近战兜底）
 *
 * 还原：威胁（周围敌对生物，独立小半径）消失持续 N tick（默认 400 = 20 秒）→ 切回
 * 战斗前原任务；有排班表的女仆还原时直接交给排班当前段（排班在主动战斗之上）。
 * 玩家中途接管：战斗期间任务被玩家/排班/LLM 换过 → 还原只清标记退出，
 * 绝不把玩家安排的任务翻回去（还原前先校验"仍在指派的战斗任务上"）。
 * v1.1.0 实测八十四【僵局逃逸】：威胁在半径内但双方久无伤害往来（怪卡墙后/
 * 传送门里等够不着的死局）→ 超时后不再续杯安全计时，照常还原（autoSwitchStaleSeconds）。
 *
 * 优先级链（本功能在其中的位置）：自保 > 排班表 > 主动战斗（含还原）> 玩家手动/LLM。
 * 自保中的女仆不响应切换（自保优先），还原也等自保结束。
 *
 * v1.1.0 发布前审查修掉的坑：标记判定一律走 getBoolean——putBoolean(false) 不删键，
 * contains 会永远为 true，旧判定会让女仆打完一仗后再也不响应主动参战、
 * 排班调度器也会因为她"看似在战斗中"而永久让位。
 */
public class AutoCombatSwitch {
    /** v1.1.0 实测一百零四：战斗激活布尔标记——PREV_TASK_TAG 存的是字符串（前任务
     *  UID），getBoolean 对字符串标签永远返回 false → 恢复逻辑永远不触发。新增独立
     *  布尔标记解决此问题。 */
    private static final String COMBAT_ACTIVE_TAG = "maid_smart_combat_active";
    /** 战斗前原任务 UID（persistentData，切战斗时写入，还原时读取） */
    private static final String PREV_TASK_TAG = "maid_smart_combat_prev_task";
    /** 最近一次看到威胁的 gameTime（还原延迟计时基准） */
    private static final String LAST_THREAT_TAG = "maid_smart_combat_last_threat";
    /** 本系统指派的战斗任务 UID（还原时校验任务没被玩家换过——换过=玩家接管，只清标记退出） */
    private static final String ASSIGNED_TAG = "maid_smart_combat_task";
    /** v1.1.0 实测八十四：最近一次与敌对生物有伤害往来的 gameTime（僵局逃逸阀计时——
     *  威胁在半径内但双方久无接触 = 够不着的死局，不再无限续杯安全计时） */
    private static final String LAST_CONTACT_TAG = "maid_smart_combat_last_contact";
    /** v1.1.0 实测八十五：最近伤害来源（动态威胁圈）——uuid + 登记时刻。
     *  还原扫描时该生物若仍存活且在扩展窗口内，威胁圈自动放大把它包含进来 */
    private static final String ATTACKER_UUID_TAG = "maid_smart_combat_attacker";
    private static final String ATTACKER_TIME_TAG = "maid_smart_combat_attacker_time";
    /** v1.1.0 实测一百四十九（参考 tlm_beyond_space 会话快照 RegularRescueSupport）：
     *  战斗前的 home 模式——还原时一并恢复（"切回之前的模式"闭环：任务+home+作息） */
    private static final String COMBAT_PREV_HOME_TAG = "maid_smart_combat_prev_home";
    /** 战斗前的作息（MaidSchedule.name；空 = 未记录） */
    private static final String COMBAT_PREV_SCHEDULE_TAG = "maid_smart_combat_prev_schedule";
    /** v1.1.0 实测一百六十二：战斗会话开始时间（tick）——硬性超时还原兜底的计时基准 */
    private static final String COMBAT_START_TAG = "maid_smart_combat_start";
    /** v1.1.0 实测一百六十二：战斗会话硬性超时（tick，90 秒）——超过仍未还原就强制
     *  切回，杜绝任何门（威胁判定/安全计时/僵局阀失效）把女仆永久卡在战斗态。 */
    private static final long COMBAT_HARD_DEADLINE_TICKS = 1800L;
    /** 僵局日志节流（每女仆 30 秒一条，latest.log 搜 "auto-combat stale"） */
    private static final java.util.Map<java.util.UUID, Long> STALE_LOG =
            new java.util.HashMap<>();
    private static final Random RNG = new Random();
    /** 还原扫描节流（每 20 tick = 1 秒一次） */
    private int restoreThrottle = 0;
    /** v1.1.0 实测一百六十四：还原扫描诊断节流（maidId → 上次诊断 tick）——定位
     *  "还原扫描卡在哪个门"（用户追问：为什么卡住，不能只加超时兜底）。每 10 秒/女仆
     *  一条 latest.log（搜 "restore-scan"）。 */
    private static final java.util.Map<java.util.UUID, Long> RESTORE_DIAG_SINCE = new java.util.HashMap<>();
    /** v1.1.0 实测一百六十九：候选池诊断节流（maidId → 上次诊断 tick）——确认模组
     *  武器任务有没有进战斗候选池（用户："女仆仍然不会使用模组的武器"）。每 5 秒/
     *  女仆一条 latest.log（搜 "combat pools"）。 */
    private static final java.util.Map<java.util.UUID, Long> POOL_DIAG_SINCE = new java.util.HashMap<>();

    /** 主人被攻击（任意来源）→ 附近女仆切战斗
     *  v1.1.0 实测二十：旧版只认敌对生物攻击（Enemy）——玩家互打/PVP、其他模组的
     *  非标准敌对生物、环境伤害都不触发。现改为任意来源受伤即触发（自伤除外）。
     *
     *  v1.1.0 实测三十六（用户："主人满血受伤仍不触发，当时拿的是卓越前线充能手枪"）：
     *  LivingHurtEvent 不是唯一的"主人受伤"信号——SBW（卓越前线）等枪械模组的
     *  伤害走自定义管线（DamageHandler 自管伤害计算），受伤事件可能【不发】或
     *  被其他订阅者【取消】（EF/SBW 都会 cancel LivingHurtEvent 改走自己的减伤）。
     *  兑底方案：同时监听 LivingAttackEvent（受伤链最上游，cancel 之前必经）+
     *  LivingDamageEvent（结算层）——三个事件任何一个先到就触发，后到的被节流
     *  跳过（同一次攻击只切一次）。 */
    @SubscribeEvent
    public void onOwnerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测二十：不再限定敌对生物来源——主人被【任何东西】攻击都算开战
        //（PVP 玩家互打、模组自定义敌对生物、弹射物等都覆盖；自伤仍排除）
        if (event.getSource() == null || event.getSource().m_7639_() == null
                || event.getSource().m_7639_() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：LivingAttackEvent 兑底——受伤链最上游（hurt() 开头就发，
     *  在任何模组 cancel LivingHurtEvent 之前必然经过）。 */
    @SubscribeEvent
    public void onOwnerAttacked(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        if (event.getSource() == null || event.getSource().m_7639_() == null
                || event.getSource().m_7639_() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：LivingDamageEvent 兑底——结算层事件（LivingHurtEvent 之后；
     *  枪械模组自定义管线常直接走到这层）。 */
    @SubscribeEvent
    public void onOwnerDamaged(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        if (event.getSource() == null || event.getSource().m_7639_() == null
                || event.getSource().m_7639_() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：三事件去重节流——同一玩家 20 tick（1 秒）内多个事件
     *  只触发一次切换扫描（Attack/Hurt/Damage 三连发是同一次攻击的正常现象） */
    private final java.util.Map<java.util.UUID, Long> triggerThrottle = new java.util.HashMap<>();

    private void tryTrigger(ServerPlayer player) {
        long now = player.m_9236_().m_46467_();
        Long last = this.triggerThrottle.get(player.m_20148_());
        if (last != null && now - last < 20L) {
            return;
        }
        this.triggerThrottle.put(player.m_20148_(), now);
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat trigger: owner={} hp={}/{}",
                player.m_5446_().getString(),
                String.format("%.0f", player.m_21223_()), String.format("%.0f", player.m_21233_()));
        switchNearbyMaids(player);
    }

    /**
     * v1.1.0 实测二十：主人攻击了别的生物 → 也触发（护主不只被动挨打才算开战，
     * 主人主动开火同样进入战斗）。
     *
     * v1.1.0 实测二十八修复（用户："主动战斗没有成功生效"）：旧版把
     * event.getEntity()（=【受害者】）instanceof ServerPlayer 当判断——受害者
     * 是玩家、来源又是玩家的组合只在"玩家打玩家"才成立；主人打怪时受害者是
     * Monster，第一个 if 直接 return，主动开火触发从未生效。正确写法：
     * 受害者任意、【来源】是玩家才算"主人开火"。
     *
     * v1.1.0 实测三十六：补 LivingDamageEvent 来源侧监听——SBW 枪械伤害走自定义
     * 管线时 LivingHurtEvent 可能不发，结算层事件兜底。
     */
    @SubscribeEvent
    public void onOwnerAttack(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        // 来源（m_7639_=getEntity）是玩家 = 主人亲手造成的伤害（女仆打的不连锁触发）
        if (!(event.getSource() != null && event.getSource().m_7639_() instanceof ServerPlayer attacker)) {
            return;
        }
        // 打的是自己的女仆不算开战（误伤/管教场景）
        if (event.getEntity() instanceof EntityMaid m && m.m_269323_() == attacker) {
            return;
        }
        // v1.1.0 实测八十四b：目标必须是【敌对生物】才算主动开战——宰牛杀鸡/剪羊毛
        // 等被动生物交互不再让女仆全员拔刀。这类"无威胁战斗"还原扫描永远扫不到
        // 威胁、安全计时只被主人的后续命中无限续杯，是"打完收不回去"的根源。
        // v1.1.0 实测八十七：中立激怒口径——正在记仇主人的中立生物（追着主人咬的
        // 狼/带崽北极熊）也算交战对象，帮打合理；平静态的照样不触发。
        net.minecraft.world.entity.Entity victimEnt = event.getEntity();
        if (!(victimEnt instanceof net.minecraft.world.entity.monster.Enemy)
                && !isAngryNeutralAt(victimEnt, attacker)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** v1.1.0 实测三十六：主人开火的 LivingDamageEvent 兜底（同 onOwnerAttack 的
     *  来源侧判定，事件换结算层）。v1.1.0 实测八十四b：同样要求目标是敌对生物。 */
    @SubscribeEvent
    public void onOwnerAttackDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (!(event.getSource() != null && event.getSource().m_7639_() instanceof ServerPlayer attacker)) {
            return;
        }
        if (event.getEntity() instanceof EntityMaid m && m.m_269323_() == attacker) {
            return;
        }
        // v1.1.0 实测八十七：同 onOwnerAttack——敌对生物或记仇主人的中立生物
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)
                && !isAngryNeutralAt(event.getEntity(), attacker)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** 响应半径内自己的女仆全体评估参战（被攻击/主动开火共用）
     *  v1.1.0 实测二十八：加限频诊断日志（latest.log 搜 "auto-combat"）——
     *  此前整个链路零日志，"没生效"无从排查；现在记录触发源/扫描结果/切换结果
     *  v1.1.0 实测五十八：单只评估逻辑抽到 tryEngageMaid（"女仆被怪打"触发共用） */
    private void switchNearbyMaids(ServerPlayer player) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
        int switched = 0;
        int skippedCombat = 0;
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class,
                player.m_20191_().m_82400_(r))) {
            if (maid.m_269323_() != player) {
                continue; // 只响应主人自己的女仆
            }
            int result = tryEngageMaid(maid);
            if (result == 1) {
                switched++;
            } else if (result == 2) {
                skippedCombat++;
            }
        }
        // v1.1.0 实测二十八：触发但一只都没切（全部让位/已是战斗/无匹配武器）也记一笔——
        // 排查"没生效"时能区分"事件没触发"和"触发了但全被跳过"
        if (switched == 0 && skippedCombat > 0) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat: triggered by owner={} but 0 switched ({} already combat)",
                    player.m_5446_().getString(), skippedCombat);
        }
    }

    /* ==================== v1.1.0 实测五十八：女仆被怪打 → 自主参战 ==================== */

    /**
     * 女仆被怪物攻击（近身拍打/远程弹射物——弹射物伤害来源=射击者）→ 她【本人】
     * 立即参战（不受响应半径限制——她就在现场），同主人的姐妹在响应半径内一并
     * 响应（与护主同款群体防御）。
     * 来源只认 Monster（敌对生物）：玩家打女仆走 TLM 自己的仇恨/管教体系不在这里
     * 反击；主人打女仆是管教不还手；女仆之间不打架（防不同主人的女仆互殴升级成
     * 连环混战）。与护主触发同款三事件监听（枪械等模组可能取消中间层事件）。
     */
    @SubscribeEvent
    public void onMaidHurt(LivingHurtEvent event) {
        if (maidVictimOfMonster(event.getEntity(), event.getSource())) {
            touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    @SubscribeEvent
    public void onMaidAttacked(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (maidVictimOfMonster(event.getEntity(), event.getSource())) {
            touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    @SubscribeEvent
    public void onMaidDamaged(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (maidVictimOfMonster(event.getEntity(), event.getSource())) {
            touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    /**
     * v1.1.0 实测八十四：记录一次与敌对生物的真实接触（任意方向伤害）。
     * v1.1.0 实测八十五：挨打侧同时登记【伤害来源生物】（uuid + 时刻）——
     * 动态威胁圈的放大依据（hasThreatNearby 按需把半径扩到包含它）。
     */
    private static void touchContact(EntityMaid maid) {
        try {
            maid.getPersistentData().m_128356_(LAST_CONTACT_TAG, maid.m_9236_().m_46467_());
        } catch (Exception ignored) {
        }
    }

    private static void touchContactFromSource(EntityMaid maid,
                                               net.minecraft.world.damagesource.DamageSource source) {
        touchContact(maid);
        try {
            net.minecraft.world.entity.Entity attacker = source != null ? source.m_7640_() : null;
            if (!(attacker instanceof net.minecraft.world.entity.monster.Enemy)
                    && !isAngryNeutralAt(attacker, maid)) {
                attacker = source != null ? source.m_7639_() : null;
            }
            // v1.1.0 实测八十七：登记口径 = Enemy 或 记仇女仆的中立生物
            if (attacker instanceof net.minecraft.world.entity.monster.Enemy
                    || isAngryNeutralAt(attacker, maid)) {
                maid.getPersistentData().m_128359_(ATTACKER_UUID_TAG, attacker.m_20148_().toString());
                maid.getPersistentData().m_128356_(ATTACKER_TIME_TAG, maid.m_9236_().m_46467_());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.1.0 实测八十四：女仆【打到】敌对生物也算一次战斗接触——僵局逃逸阀的
     * 另一个计时来源（只算"挨打"的话，远程女仆放风筝全程无伤会被误判成死局）。
     * m_7640_ = DamageSource.getEntity（造成者；弓箭等弹射物的造成者是射手本体，
     * 与 m_7639_ getDirectEntity=箭矢实体相对）。
     */
    @SubscribeEvent
    public void onMaidStrikeEnemy(LivingHurtEvent event) {
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        if (!(event.getSource().m_7640_() instanceof EntityMaid maid)) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)) {
            return;
        }
        touchContact(maid);
    }

    /** 受害者是女仆、来源是怪物（与护主触发同款总开关门控）
     *  v1.1.0 实测六十五：Monster -> Enemy——史莱姆/岩浆怪等敌对生物不实现
     *  Monster 但实现 Enemy 接口，旧判定被它们打了不参战。
     *  v1.1.0 实测八十五：补弹射物口径——骷髅放箭时【直接实体】(m_7639_)是箭矢、
     *  【造成者】(m_7640_)才是骷髅，旧版只查直接实体 → 远程怪放风筝女仆永不参战；
     *  现在两侧任一是 Enemy 即算。
     *  v1.1.0 实测八十七：中立激怒口径——蜜蜂/北极熊/狼等 NeutralMob 平时不算
     *  敌意来源，但【正在记仇女仆】的（isAngryAt 命中）按敌对处理。 */
    private static boolean maidVictimOfMonster(Entity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (!(victim instanceof EntityMaid)) {
            return false;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return false;
        }
        if (source == null) {
            return false;
        }
        net.minecraft.world.entity.Entity cause = source.m_7640_();
        if (cause instanceof net.minecraft.world.entity.monster.Enemy) {
            return true;
        }
        net.minecraft.world.entity.Entity direct = source.m_7639_();
        if (direct instanceof net.minecraft.world.entity.monster.Enemy) {
            return true;
        }
        // v1.1.0 实测八十七：中立生物激怒即通行证
        return isAngryNeutralAt(cause, victim) || isAngryNeutralAt(direct, victim);
    }

    /**
     * v1.1.0 实测八十七：该实体是否为【正在记仇 target 的中立生物】。
     * m_21674_ = NeutralMob.isAngryAt(LivingEntity)（字节码实证默认实现：
     * canAnger 门 → 玩家通用怒火规则 → 持久仇恨 UUID 匹配）。
     */
    private static boolean isAngryNeutralAt(net.minecraft.world.entity.Entity e,
                                            net.minecraft.world.entity.Entity target) {
        try {
            return e instanceof net.minecraft.world.entity.NeutralMob nm
                    && nm.m_21674_((net.minecraft.world.entity.LivingEntity) target);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * v1.1.0 实测八十七b：isAngry（m_21660_，剩余记仇时间>0）防御封装——
     * 模组 NeutralMob 实现的 getter 抛异常只按"不记仇"处理，绝不炸 tick。
     */
    private static boolean neutralAngry(net.minecraft.world.entity.NeutralMob nm) {
        try {
            return nm.m_21660_();
        } catch (Exception e) {
            return false;
        }
    }

    /** 被打女仆 + 周围同主人姐妹一起参战（三事件 20 tick 去重，与护主触发同口径） */
    private void engageAttackedMaid(EntityMaid victim) {
        long now = victim.m_9236_().m_46467_();
        Long last = this.triggerThrottle.get(victim.m_20148_());
        if (last != null && now - last < 20L) {
            return;
        }
        this.triggerThrottle.put(victim.m_20148_(), now);
        this.tryEngageMaid(victim); // 挨打的本人在哪都响应
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
        for (EntityMaid maid : victim.m_9236_().m_45976_(EntityMaid.class,
                victim.m_20191_().m_82400_(r))) {
            if (maid == victim) {
                continue;
            }
            if (maid.m_269323_() == null || maid.m_269323_() != victim.m_269323_()) {
                continue; // 只带同主人的姐妹（无主野女仆不卷入）
            }
            this.tryEngageMaid(maid);
        }
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat: maid attacked -> engage self + sisters (victim={})",
                victim.m_5446_() != null ? victim.m_5446_().getString() : victim.m_20148_());
    }

    /**
     * 单只女仆参战评估（护主扫描 / 女仆被怪打 共用）。
     * 返回：1=已切换战斗 0=不参战（自保中/已参战刷新威胁/无匹配任务）2=已是战斗任务跳过
     */
    private static int tryEngageMaid(EntityMaid maid) {
        if (!maid.m_6084_() || maid.m_6162_()) {
            return 0; // 死亡/幼年不参战
        }
        // v1.1.0 实测一百六十三（用户："退而求其次——让排班拥有更高的优先级。排班
        // 状态下不触发自主战斗，也不会响应"）：排班开启的女仆【不参与自主战斗】——
        // 任务/模式全由日程表管理，杜绝战斗让位/还原链与排班互相拉扯（8月28日起
        // "排班不切换、女仆一直跟随主人"的根因就是战斗 COMBAT_ACTIVE 残留把排班
        // 让位挡死）。想让她打 → 排班段任务直接配攻击任务（日程表驱动战斗），或
        // 关闭该女仆排班。
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return 0;
        }
        // 自保中让位（自保优先，血量恢复后自然退出再正常参与）
        if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
            return 0;
        }
        // 已被本系统切过：还在指派的战斗任务上 → 只刷新威胁计时；任务已被
        // 玩家/排班/LLM 换走 → 玩家接管，清标记后按"当前任务"重新评估参战
        // v1.1.0 终审修复落地（实测十六）：判定必须走 getBoolean（m_128471_）——
        // 此前代码用 contains（m_128441_），而 clearMarkers 是 putBoolean(false)
        // 不删键 → 打过一仗后 contains 永远 true：排班调度器对她永久让位
        // （排班再也不生效）+ 还原扫描每秒对每只退役女仆做 3 次无效 NBT 写
        if (maid.getPersistentData().m_128471_(COMBAT_ACTIVE_TAG)) {
            IMaidTask curTask = maid.getTask();
            // v1.1.0 实测一百六十二【主动攻击被吞根治】：旧版把 idle 读数（战斗早已
            // 结束、任务已回落 idle）也当"已在战斗"直接 return 0——老女仆身上残留的
            // COMBAT_ACTIVE 永远清不掉，参战被永久吞掉（新女仆没残留所以正常触发）。
            // 现在只有【真实还在战斗任务】（指派或任意攻击任务）才吞；idle 读数
            // = 残留标记 → 清掉后继续走参战评估；被外部换走的任务同样清标记后重评。
            if (isAssignedOrCombatTask(maid, curTask)) {
                // v1.1.0 实测八十四b：续杯安全计时只在【真实存在敌对威胁】时进行
                if (hasThreatNearby(maid)) {
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
                }
                return 0; // 真在战斗：只续威胁计时，不重复参战
            }
            clearMarkers(maid);
            // v1.1.0 实测一百四十九：任务被外部接管 → 尊重新任务不动它，但 home/作息还原
            restorePrevMode(maid);
            if (isIdleReadingTask(curTask)) {
                com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 残留战斗标记自愈（当前 idle），重新评估参战");
            } else {
                com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 战斗中任务被接管（当前 " + (curTask != null ? curTask.getUid() : "null")
                        + " 非攻击任务），清标记退出");
            }
            // 不 return——清完标记继续走下面的参战评估
        }
        // 已是攻击类任务（IAttackTask：玩家手动安排的近战/弓/弹幕，或万法皆通/
        // 史诗战斗等第三方攻击任务）→ 她本来就能打，尊重现状不切换不记录
        if (MaidWorkTags.isCombatTask(maid)) {
            // v1.1.0 实测一百四十八：当前战斗任务【已无可用武器】（模组武器被
            // 玩家拿走）→ 不视为"已在战斗"，继续走重选——否则永远 return 2，
            // 主动战斗再也不触发（"塞入模组武器后即使再拿出来也不触发"的根因之一）
            if (hasWeaponForTask(maid, maid.getTask())) {
                return 2;
            }
            com.maidsmart.tool.PromaidLog.log("战斗",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 当前战斗任务 " + maid.getTask().getUid()
                            + " 无可用武器（模组武器被拿走？），重新选择参战任务");
        }
        IMaidTask combat = pickCombatTask(maid);
        if (combat == null) {
            return 0; // 单只找不到任务不连坐（此前 return 会跳过同半径的其他女仆）
        }
        // v1.1.0 实测一百四十八（参考 tlm_beyond_space TaskSwitchService）：切任务前
        // 预检 + 自动装备——onFunctionCallSwitch 默认实现 = 主手无武器则从背包装备，
        // 装不上返回 MISSING_REQUIRED_ITEM。预检失败就不切入：不会把女仆卡在打不出
        // 伤害的战斗任务上（武器被拿走/任务要求特殊物品）
        if (CombatTaskCompat.prepareSwitch(maid, combat)
                == FunctionCallSwitchResult.MISSING_REQUIRED_ITEM) {
            com.maidsmart.tool.PromaidLog.log("战斗",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 参战预检失败：" + combat.getUid() + " 无可装备武器，不参战");
            return 0;
        }
        String prevUid = resolvePrevTaskUid(maid);
        maid.getPersistentData().m_128359_(PREV_TASK_TAG, prevUid);
        maid.getPersistentData().m_128359_(ASSIGNED_TAG, combat.getUid().toString());
        maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
        maid.getPersistentData().m_128379_(COMBAT_ACTIVE_TAG, true);
        // v1.1.0 实测一百六十二：记录战斗开始时间（硬性超时还原兜底）
        maid.getPersistentData().m_128356_(COMBAT_START_TAG, maid.m_9236_().m_46467_());
        // v1.1.0 实测一百四十九（参考 tlm_beyond_space RegularRescueSupport）：参战瞬间
        // 快照 home 模式与作息——还原时一并恢复（"切回之前的模式"的完整状态闭环）
        maid.getPersistentData().m_128379_(COMBAT_PREV_HOME_TAG, maid.isHomeModeEnable());
        try {
            maid.getPersistentData().m_128359_(COMBAT_PREV_SCHEDULE_TAG,
                    maid.getSchedule() == null ? "" : maid.getSchedule().name());
        } catch (Throwable ignored) {
        }
        // v1.1.0 实测八十四：参战即视为一次接触（僵局逃逸阀计时起点刷新）
        touchContact(maid);
        // v1.1.0 实测一百三十六：主动战斗是【自动系统】——setTask 打内部标记，
        // 排班守卫 mixin 据此放行（否则排班中的女仆会连战斗切换都被拦）
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(maid.m_20148_(),
                combat.getUid(), () -> maid.setTask(combat));
        // v1.1.0 实测九十四：运行日志
        com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 参战：" + prevUid + " -> " + combat.getUid());
        return 1;
    }

    /**
     * 威胁消失持续够久 → 还原原任务。扫描持久化标记（非内存集合）——
     * 存档重读/魂符收放/换维度后仍能正确还原，不会卡死在战斗任务上。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.restoreThrottle < 20) {
            return; // 每秒检查一次
        }
        this.restoreThrottle = 0;
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测五十七：战中近远程换战术只依赖总开关——自动还原关掉时，
        // 换战术仍然工作（还原关 = 用户要她打到底，但打得聪明依旧成立）
        boolean restoreOn = MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE.get();
        for (ServerLevel level : event.getServer().m_129785_()) {
            for (EntityMaid maid : level.m_45976_(EntityMaid.class,
                    new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY))) {
                if (!maid.m_6084_() || !maid.getPersistentData().m_128471_(COMBAT_ACTIVE_TAG)) {
                    continue;
                }
                // v1.1.0 实测一百六十三：排班开启的女仆不参与自主战斗——残留的战斗
                // 标记直接清掉（她的任务/模式由日程表管理，战斗还原链不再适用）
                if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                    clearMarkers(maid);
                    continue;
                }
                // 自保中不还原（等自保结束；自保退出有自己的回主人逻辑）
                if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
                    continue;
                }
                // 战斗期间任务被玩家/排班/LLM 换过（真实的其他任务）→ 玩家接管：只清标记退出，
                // 不动当前任务。v1.1.0 实测一百三十九：getTask() 抖动回落 idle 不算接管
                //（idle 读数继续走还原，否则清标记丢还原链 = "切不回原来模式"）
                // v1.1.0 实测一百四十九：判定用【单次任务读取】（DATA_TASK 同步抖动
                // 防自相矛盾，同 tryEngageMaid）——"接管"误判（日志实证判定读非 idle、
                // 打印却变 idle）会让还原链被丢 = 切不回原来模式
                IMaidTask curTask = maid.getTask();
                // v1.1.0 实测一百六十四：还原扫描诊断（每 10 秒/女仆一条，latest.log 搜
                // "restore-scan"）——定位"还原扫描卡在哪个门"（用户追问：为什么卡住，
                // 不能只加超时兜底）：task=当前任务 / assigned=指派战斗任务 /
                // lastThreatAge=距上次威胁刷新秒数 / threatDetail=威胁来源明细 /
                // preserve=自保 / isOnSched=排班开启
                Long diagLast = RESTORE_DIAG_SINCE.get(maid.m_20148_());
                long diagNow = level.m_46467_();
                if (diagLast == null || diagNow - diagLast >= 200L) {
                    RESTORE_DIAG_SINCE.put(maid.m_20148_(), diagNow);
                    com.mojang.logging.LogUtils.getLogger().info(
                            "auto-combat restore-scan: maid={} task={} assigned={} lastThreatAge={}s threatDetail={} preserve={} isOnSched={}",
                            com.maidsmart.tool.PromaidLog.nameOf(maid),
                            curTask != null && curTask.getUid() != null ? curTask.getUid() : "null",
                            maid.getPersistentData().m_128461_(ASSIGNED_TAG),
                            (diagNow - maid.getPersistentData().m_128454_(LAST_THREAT_TAG)) / 20,
                            threatDetail(maid),
                            maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG),
                            com.maidsmart.schedule.ScheduleData.isOn(maid));
                }
                if (!isAssignedOrCombatTask(maid, curTask) && !isIdleReadingTask(curTask)) {
                    clearMarkers(maid);
                    // v1.1.0 实测一百四十九（参考 tlm_beyond_space restoreAfterExternalTaskChange）：
                    // 任务被外部接管 → 尊重新任务不动它，但 home/作息还原到战斗前
                    //（"切回之前的模式"兜底，不再只有"清标记"半途而废）
                    restorePrevMode(maid);
                    // v1.1.0 实测九十四：运行日志
                    com.maidsmart.tool.PromaidLog.log("战斗",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 战斗中任务被接管（玩家/排班/LLM），清标记退出");
                    continue;
                }
                long now = level.m_46467_();
                // v1.1.0 实测一百四十八【主动战斗再也不触发根治】：当前战斗任务已无
                // 可用武器（玩家把模组武器拿走等）——武器没了打不死怪，威胁永不消失、
                // 还原等待永久卡住 = 女仆永远"战斗中"，之后的参战触发全被 COMBAT_ACTIVE
                // 分支跳过 = 主动战斗再也不触发。检测到武器不可用 → 强制走还原
                // （跳过威胁刷新与安全时长等待），还原后她有武器时再正常参战。
                boolean weaponless = curTask != null
                        && curTask instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask
                        && !hasWeaponForTask(maid, curTask);
                // v1.1.0 实测一百六十二【硬性兜底】：战斗会话超过 90 秒仍未还原——
                // 无论威胁是否仍在、安全计时是否被续杯、僵局阀是否失效，都强制切回。
                // 保证任何门都卡不死女仆（用户："怎么都没法还原"）。
                long combatStart = maid.getPersistentData().m_128454_(COMBAT_START_TAG);
                boolean hardDeadline = combatStart > 0 && now - combatStart > COMBAT_HARD_DEADLINE_TICKS;
                // v1.1.0 实测一百六十三【残留标记自愈】：老版本（无 COMBAT_START 时间戳）
                // 留下的 COMBAT_ACTIVE=true + 当前任务已不是攻击任务 = 残留 → 强制还原
                //（排班被它挡死、参战被它吞掉的双重根因，见 isReallyCombatActive）
                boolean staleMarker = combatStart <= 0
                        && !(curTask instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask);
                boolean forceRestore = weaponless || hardDeadline || staleMarker;
                boolean threatNearby = forceRestore ? false : hasThreatNearby(maid);
                if (forceRestore) {
                    com.maidsmart.tool.PromaidLog.log("战斗",
                            com.maidsmart.tool.PromaidLog.nameOf(maid)
                                    + (hardDeadline
                                            ? " 战斗会话超时 " + (COMBAT_HARD_DEADLINE_TICKS / 20)
                                            + " 秒仍未还原，强制还原"
                                            : staleMarker
                                                    ? " 残留战斗标记自愈（无开始时间戳且当前任务非攻击）——强制还原"
                                                    : " 战斗任务 " + curTask.getUid()
                                                    + " 无可用武器（模组武器被拿走？），强制还原"));
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, 0L);
                }
                // v1.1.0 实测八十四：僵局逃逸阀——威胁仍在还原半径内，但双方超过
                // N 秒没有任何伤害往来（怪卡墙后/玻璃后/传送门里/飞行够不着等
                // "杀不掉也够不着"的死局），不再无限续杯安全计时 → 正常走还原。
                // 被动生物（动物）本就不算威胁（判定只认 Enemy 接口），与本次无关；
                // 该阀门专治"敌对生物永久滞留半径内"的卡死。
                if (threatNearby) {
                    int staleSec = MaidSmartConfig.COMBAT_AUTO_SWITCH_STALE.get();
                    long lastContact = maid.getPersistentData().m_128454_(LAST_CONTACT_TAG);
                    if (staleSec > 0 && now - lastContact >= staleSec * 20L) {
                        threatNearby = false;
                        Long lastLog = STALE_LOG.get(maid.m_20148_());
                        if (lastLog == null || now - lastLog >= 600L) {
                            STALE_LOG.put(maid.m_20148_(), now);
                            // v1.1.0 实测九十四：运行日志（替代原 latest.log 直写）
                            com.maidsmart.tool.PromaidLog.log("战斗",
                                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 僵局逃逸阀触发：威胁仍在 "
                                            + MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get()
                                            + " 格内但已 " + ((now - lastContact) / 20) + " 秒无伤害往来 → 强制走还原");
                        }
                    }
                }
                if (threatNearby) {
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, now);
                    // v1.1.0 实测五十七：威胁仍在 → 每秒评估一次近/远程是否该互换
                    retuneCombatTactics(maid);
                    continue;
                }
                // v1.1.0 实测一百一十五【距离切换武器落实】：威胁圈（8 格）外但仍在
                // 近战够不着、远程够得着的范围（6~16 格）——旧版直接走还原退出战斗：
                // 远处敌人女仆不切弓而是直接退出（"不会根据距离远近切换武器"的设计
                // 在 8~16 格出现空洞）。现在远处敌人先尝试按距离切远程：切成功
                // （近战→远程）或已是远程 → 维持战斗继续射；切不动（背包没有远程
                // 武器）→ 落回正常还原（10 秒安全期后退出）。
                double farDist = nearestThreatDist(maid);
                // v1.1.0 实测一百四十八：武器已被拿走时跳过"远处切远程"分支——
                // 该分支切成功会续杯 LAST_THREAT 并 continue（继续战斗），与上面的
                // 强制还原冲突（武器没了还留在战斗里 = 卡死）
                if (!forceRestore && farDist > JUMP_UNREACHABLE_DIST && farDist <= TARGETING_RANGE) {
                    String beforeTask = maid.getTask() != null ? maid.getTask().getUid().toString() : "";
                    retuneCombatTactics(maid);
                    String afterTask = maid.getTask() != null ? maid.getTask().getUid().toString() : "";
                    boolean canRanged = !afterTask.equals(beforeTask)
                            || (maid.getTask() != null && isRangedTask(maid.getTask()));
                    if (canRanged) {
                        if (!afterTask.equals(beforeTask)) {
                            com.maidsmart.tool.PromaidLog.log("战斗",
                                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                                            + " 距离切换：" + beforeTask + " -> " + afterTask
                                            + "（远处威胁 " + String.format("%.0f", farDist) + " 格）");
                        }
                        maid.getPersistentData().m_128356_(LAST_THREAT_TAG, now);
                        continue;
                    }
                    // 无远程手段 → 落回正常还原（安全期后退出）
                }
                if (!restoreOn && !forceRestore) {
                    continue; // 自动还原关：只换战术不还原（强制还原时例外——照常还原）
                }
                long lastThreat = maid.getPersistentData().m_128454_(LAST_THREAT_TAG);
                if (now - lastThreat < MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_DELAY.get()) {
                    continue; // 安全时长还不够
                }
                // 还原。战斗期间排班表可能已跨段——排班在主动战斗之上，还原时先清
                // 排班去抖键并立即重应用当前段；没排班/重应用没换成 → 落回"战斗前任务"
                // v1.1.0 实测三十九修复（用户："消除威胁后无法转回原任务，女仆停在
                // 切换的模式"）：旧版【先 clearMarkers 再还原】——还原链路任何一环
                // 失败（findTask 找不到原任务/排班 applyNow 抛异常/任务 UID 非法），
                // 标记已被清掉：下次触发时 isCombatTask 把她当"玩家手动安排"跳过、
                // 还原扫描因无 PREV_TASK_TAG 也跳过 → 永久卡在战斗任务。
                // 修复：先解析原任务（解析失败保留标记下轮重试 + 记日志），全部
                // 就绪才清标记执行还原；全程加日志（latest.log 搜 "auto-combat restore"）。
                String prevUid = maid.getPersistentData().m_128461_(PREV_TASK_TAG);
                String assignedUid = maid.getPersistentData().m_128461_(ASSIGNED_TAG);
                IMaidTask prevTask = null;
                try {
                    prevTask = TaskManager.findTask(ResourceLocation.parse(prevUid)).orElse(null);
                } catch (Exception ignored) {
                }
                if (prevTask == null) {
                    // v1.1.0 实测一百零二：原任务不存在时不再永久卡在战斗任务——
                    // 兜底还原到 idle（空闲），清标记释放女仆。
                    com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 原任务 '" + prevUid + "' 已不存在，兜底还原到空闲");
                    // v1.1.0 实测一百五十（参考 tlm_beyond_space restoreTemporaryState：
                    // 先还原、后清会话——失败不清标记下轮重试）：兜底切换【成功后】才清标记
                    boolean fallbackDone = false;
                    if (com.maidsmart.schedule.ScheduleData.isOn(maid)
                            && !com.maidsmart.schedule.ScheduleData.load(maid).isEmpty()) {
                        try {
                            maid.getPersistentData().m_128359_(
                                    com.maidsmart.schedule.ScheduleData.APPLIED_TAG, "");
                            com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
                            fallbackDone = maid.getTask() != null
                                    && !maid.getTask().getUid().toString().equals(assignedUid);
                        } catch (Exception ignored) {
                        }
                    }
                    if (!fallbackDone) {
                        // 无排班或排班未生效 → 找 TLM 内置 idle 任务
                        try {
                            var idleTask = TaskManager.findTask(
                                    new net.minecraft.resources.ResourceLocation("touhou_little_maid", "idle"))
                                    .orElse(null);
                            if (idleTask != null) {
                                com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                                        maid.m_20148_(), idleTask.getUid(), () -> maid.setTask(idleTask));
                                fallbackDone = maid.getTask() != null
                                        && maid.getTask().getUid() != null
                                        && idleTask.getUid().equals(maid.getTask().getUid());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (fallbackDone) {
                        clearMarkers(maid);
                        // v1.1.0 实测一百四十九：兜底还原同样恢复 home/作息（排班关闭时）
                        restorePrevMode(maid);
                    } else {
                        com.maidsmart.tool.PromaidLog.log("战斗",
                                com.maidsmart.tool.PromaidLog.nameOf(maid)
                                        + " 兜底还原未生效（TLM setTask 守卫拒绝？），保留标记下轮重试");
                    }
                    continue;
                }
                boolean restored = false;
                // v1.1.0 实测一百一十四：仍在任意攻击任务（含 retune 换战术/同步抖动后
                // 与 ASSIGNED 不一致的战斗任务）都算"本系统战斗"，还原到战斗前任务——
                // 旧版只认 ASSIGNED 完全一致，换过战术/任务被第三方改过的战斗女仆
                // 永不还原（"威胁解除后回不了原任务"）。
                boolean stillOnCombat = maid.getTask() != null
                        && (maid.getTask().getUid().toString().equals(assignedUid)
                        || MaidWorkTags.isCombatTask(maid));
                if (stillOnCombat
                        && com.maidsmart.schedule.ScheduleData.isOn(maid)
                        && !com.maidsmart.schedule.ScheduleData.load(maid).isEmpty()) {
                    try {
                        maid.getPersistentData().m_128359_(
                                com.maidsmart.schedule.ScheduleData.APPLIED_TAG, "");
                        com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
                    } catch (Exception e) {
                        com.mojang.logging.LogUtils.getLogger().info("auto-combat restore: schedule applyNow threw: {}", e.toString());
                    }
                    stillOnCombat = maid.getTask() != null
                            && maid.getTask().getUid().toString().equals(assignedUid);
                }
                if (stillOnCombat) {
                    // v1.1.0 实测一百三十六：战斗还原也是自动系统——打内部标记放行
                    IMaidTask restoreTask = prevTask; // 快照：prevTask 非最终变量，lambda 需捕获
                    // v1.1.0 实测一百四十九（参考 tlm_beyond_space TaskSwitchService.restore）：
                    // 还原前先 prepareSwitch——把原任务需要的武器/工具装回主手（战斗中
                    // 可能被换走）；结果忽略（MISSING 也照常还原任务本身）
                    try {
                        com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, restoreTask);
                    } catch (Throwable ignored) {
                    }
                    com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                            maid.m_20148_(), restoreTask.getUid(), () -> maid.setTask(restoreTask));
                    // v1.1.0 实测一百五十（参考 tlm_beyond_space restoreTemporaryState：先还原、
                    // 后清会话——失败不清标记下轮重试）：TLM setTask 有守卫（睡眠/活动等）
                    // 会静默拒绝（实测一百二十九的读回校验同源）——旧版无条件清标记，
                    // setTask 一旦被拒 = 任务没切走、标记也没了 = 永久卡在战斗任务
                    // （"切不回原来的模式"的兜底漏洞）。读回校验：切走了才算还原成功；
                    // 没切走保留 COMBAT_ACTIVE，下轮扫描继续重试。
                    restored = maid.getTask() != null
                            && maid.getTask().getUid() != null
                            && restoreTask.getUid().equals(maid.getTask().getUid());
                    if (!restored) {
                        com.maidsmart.tool.PromaidLog.log("战斗",
                                com.maidsmart.tool.PromaidLog.nameOf(maid)
                                        + " 还原未生效：setTask 未切到 " + restoreTask.getUid()
                                        + "（TLM 守卫拒绝？），保留标记下轮重试");
                    }
                }
                // v1.1.0 实测一百五十：还原成功（或排班接管成功）才清标记——参考项目
                // "先还原后清会话"；还原失败保留标记，下轮扫描继续重试（不会丢还原链）
                if (restored || !stillOnCombat) {
                    clearMarkers(maid);
                    // v1.1.0 实测一百四十九（参考 tlm_beyond_space TaskSwitchService.restore）：
                    // 还原 home 模式与作息（排班关闭时）——"切回之前的模式"完整闭环；
                    // 排班开启时作息由日程表管理（调度器每秒重断言），此处不覆盖
                    restorePrevMode(maid);
                    // v1.1.0 实测六十一：还原宽限——还原后先让她干战斗前的原任务一段时间，
                    // 排班调度宽限期满后再接管当前段（防威胁闪烁导致战斗/还原/排班反复拉扯）。
                    // 宽限期写在女仆 persistentData（ScheduleData.GRACE_TAG），ScheduleManager.applyNow 入口检查
                    int grace = MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE.get();
                    if (grace > 0) {
                        maid.getPersistentData().m_128356_(com.maidsmart.schedule.ScheduleData.GRACE_TAG,
                                level.m_46467_() + grace);
                    }
                    // v1.1.0 实测九十四：运行日志（替代原 latest.log 直写）
                    if (restored) {
                        com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 战斗还原：" + assignedUid + " -> " + prevUid
                                + "（威胁消失 " + ((now - lastThreat) / 20) + " 秒）");
                    } else {
                        // 任务在还原前被换（排班/玩家接管）——标记已清，正常退出
                        String curTaskUid = maid.getTask() == null ? "null" : maid.getTask().getUid().toString();
                        com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 无需还原：任务战中已被换为 " + curTaskUid);
                    }
                }
            }
        }
    }

    /**
     * 女仆周围（还原威胁半径，默认 8——独立配置，不复用 16 格响应半径）是否有活的
     * 敌对生物。v1.1.0 审查：旧版复用响应半径，刷怪频繁的整合包里远处怪一直"续杯"，
     * 女仆永远等不满 20 秒安全期，卡在战斗任务回不了岗。
     *
     * v1.1.0 实测八十五【动态威胁圈】（用户设计："威胁圈会自然放大，直至包含检索到
     * 伤害来源的那个生物"）：固定圆与参战触发的口径不对称——骷髅站在 10 多格外放
     * 风筝时，固定 8 格扫不到它 → 还原计时照走 → 还原 10 秒后又中一箭再参战，反复
     * 横跳。现在挨打时登记伤害来源生物（uuid + 时刻），还原扫描发现该生物仍存活、
     * 距离不超过硬上限（32 格）、且还在扩展窗口（默认 10 秒）内 → 威胁圈自然放大把
     * 它包进来：被压着打期间保持战斗态还击；怪死/走远/超窗后圈回落到固定半径。
     */
    private static boolean hasThreatNearby(EntityMaid maid) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get();
        // v1.1.0 实测六十八：Monster -> Enemy（与参战判定同口径——史莱姆等
        // 敌对生物也算威胁，否则还原后立刻被再次触发、反复横跳）。
        // Enemy 是接口，getEntitiesOfClass 不收——按 Entity 扫描再过滤。
        // v1.1.0 实测八十七：中立激怒口径——记仇状态（isAngry）的中立动物
        // （蜜蜂/北极熊/狼等）也是真实威胁，还原不再被它们打断又拉回。
        for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(
                net.minecraft.world.entity.Entity.class, maid.m_20191_().m_82400_(r))) {
            if (!e.m_6084_()) {
                continue;
            }
            if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                return true;
            }
            // v1.1.0 实测八十七b：经防御封装调用——模组 NeutralMob 实现的记仇时间
            // getter 若抛异常，不能顺着每秒一次的还原扫描炸穿服务端 tick
            if (e instanceof net.minecraft.world.entity.NeutralMob nm && neutralAngry(nm)) {
                return true; // isAngry：记仇时间未清零 = 现役威胁
            }
        }
        // ---- 动态威胁圈 ----
        int sec = MaidSmartConfig.COMBAT_AUTO_SWITCH_EXPAND.get();
        if (sec <= 0) {
            return false; // 关闭：只用固定半径
        }
        try {
            long now = maid.m_9236_().m_46467_();
            long marked = maid.getPersistentData().m_128454_(ATTACKER_TIME_TAG);
            if (now - marked > sec * 20L) {
                return false; // 扩展窗口已过
            }
            String uuidStr = maid.getPersistentData().m_128461_(ATTACKER_UUID_TAG);
            if (uuidStr.isEmpty()) {
                return false;
            }
            net.minecraft.world.entity.Entity attacker =
                    ((net.minecraft.server.level.ServerLevel) maid.m_9236_())
                            .m_8791_(java.util.UUID.fromString(uuidStr));
            if (!attacker.m_6084_()) {
                return false; // 来源已死/已移除
            }
            // v1.1.0 实测八十七：圈来源口径放宽——Enemy 或 记仇中的中立生物
            boolean ringSource = attacker instanceof net.minecraft.world.entity.monster.Enemy
                    || (attacker instanceof net.minecraft.world.entity.NeutralMob nm
                    && neutralAngry(nm));
            if (!ringSource) {
                return false;
            }
            // 硬上限 32 格：防跨基地区域的荒谬放大
            return maid.m_20238_(attacker.m_20182_()) <= 32.0 * 32.0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * v1.1.0 实测二十：选战斗任务——武器等权随机（原版武器降权重）。
     *
     * v1.1.0 实测三十八（用户："选武器不再单纯随机，加距离判定——史诗战斗/拔刀剑
     * 算近战、枪械等算远程；近战远程都有时按距离选"）：改为【距离感知的两段选择】：
     * ① 候选任务按武器类型分近战/远程两池（分类规则见 classifyTask）；
     * ② 只有一类有候选 → 该类内按原加权随机；
     * ③ 两类都有 → 看最近敌人距离：≤ meleeRange（默认 5 格）用近战池，
     *    > meleeRange 用远程池（池内仍按原权重随机——同池内不退化成固定顺序）。
     * 找不到敌人（威胁已消失的边缘场景）→ 按远程优先（安全距离输出）。
     */
    private static IMaidTask pickCombatTask(EntityMaid maid) {
        TaskPools pools = buildPools(maid);
        // 两类都有 → 按最近敌人距离+偏好权重选池；只有一类 → 直接用
        if (!pools.meleePool().isEmpty() && !pools.rangedPool().isEmpty()) {
            double dist = nearestThreatDist(maid);
            boolean useMelee;
            if (dist >= 0 && dist <= MELEE_RANGE) {
                // v1.1.0 实测五十八：近身两池皆可用 → 按 近战:远程 偏好权重随机选池
                //（默认 3:1 ≈ 75% 近战；某类权重 0 = 永不主动选该类；双 0 → 远程兜底）
                double mw = Math.max(0, MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get());
                double rw = Math.max(0, MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT.get());
                useMelee = mw + rw > 0 && RNG.nextDouble() * (mw + rw) < mw;
            } else {
                // 远距离（近战够不着）/ 找不到敌人（威胁消失边缘）→ 远程（实测三十八口径）
                useMelee = false;
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat pick: maid={} both-pools dist={} -> {}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    String.format("%.1f", dist), useMelee ? "melee" : "ranged");
            return useMelee
                    ? weightedPick(pools.meleePool(), pools.meleeWeights())
                    : weightedPick(pools.rangedPool(), pools.rangedWeights());
        }
        if (!pools.meleePool().isEmpty()) {
            return weightedPick(pools.meleePool(), pools.meleeWeights());
        }
        if (!pools.rangedPool().isEmpty()) {
            return weightedPick(pools.rangedPool(), pools.rangedWeights());
        }
        // v1.1.0 实测六十七（用户："手上完全没有攻击性物品的女仆，就不应该触发自主
        // 战斗，应该维持原任务"）：两池全空 = 主手/背包没有任何攻击任务认的武器
        // → 不参战（返回 null，tryEngageMaid 跳过、维持原任务）；
        // 开关关闭时保留旧行为（空手近战兜底）
        if (!MaidSmartConfig.COMBAT_UNARMED_SKIP.get()) {
            return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);
        }
        return null;
    }

    /** v1.1.0 实测五十七：候选池结构（近战/远程两池 + 各自权重）——
     *  入战选任务（pickCombatTask）与战中换战术（retuneCombatTactics）共用 */
    private record TaskPools(List<IMaidTask> meleePool, List<Double> meleeWeights,
                             List<IMaidTask> rangedPool, List<Double> rangedWeights) {
    }

    /** 扫描全部攻击类任务 → 按武器匹配 + 类型分池（入战/战中共用，逻辑同旧 pickCombatTask） */
    private static TaskPools buildPools(EntityMaid maid) {
        List<IMaidTask> meleePool = new ArrayList<>();
        List<Double> meleeWeights = new ArrayList<>();
        List<IMaidTask> rangedPool = new ArrayList<>();
        List<Double> rangedWeights = new ArrayList<>();
        String vanillaNs = "touhou_little_maid";
        double vanillaW = MaidSmartConfig.COMBAT_AUTO_SWITCH_VANILLA_WEIGHT.get();
        double modW = MaidSmartConfig.COMBAT_AUTO_SWITCH_MOD_WEIGHT.get();
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            if (!(task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask attack)) {
                continue; // 只认攻击类任务
            }
            try {
                if (task.isHidden(maid)) { // isHidden——隐藏任务不进候选（接口方法名编译期已实证（TLM jar 未混淆该方法））
                    continue;
                }
            } catch (Throwable ignored) {
            }
            // 背包/主手有该任务认的武器才算候选（isWeapon 是 IAttackTask 的默认方法：
            // 原版任务按武器类型判；模组任务自定义判定——法书/史诗武器等）
            if (!hasWeaponForTask(maid, attack)) {
                continue;
            }
            // v1.1.0 实测一百二十①【枪械弹药闸】：枪械任务进池必须弹药可用——
            // TLM TaskGunAttack.isWeapon 只查 isGun（javap 实证），有枪没子弹也会
            // 进池被切到 gun_attack → TLM 换弹失败原地干等。hasGunAndAmmo 判定
            // （背包有枪+任意弹药；卓越前线能量武器免弹药），不满足 → 枪械任务
            // 不进任何池（参战选任务/距离切换都不会把她切到打不出伤害的模式）。
            if ("touhou_little_maid:gun_attack".equals(task.getUid().toString())
                    && !com.maidsmart.combat.GunCompat.hasGunAndAmmo(maid)) {
                continue;
            }
            // v1.1.0 实测二十一：权重可配置（原版/模组各一条）——模组默认 2.0 优先、
            // 原版默认 1.0 降半；两条都是权重值（>0），比例决定被选概率
            double w = vanillaNs.equals(task.getUid().m_135827_()) ? vanillaW : modW;
            w = Math.max(0.01, w);
            if (isRangedTask(task)) {
                rangedPool.add(task);
                rangedWeights.add(w);
            } else {
                meleePool.add(task);
                meleeWeights.add(w);
            }
        }
        // v1.1.0 实测一百六十九：候选池内容诊断（每 5 秒/女仆一条，latest.log 搜
        // "combat pools"）——确认模组武器任务（ef_tlm/拔刀剑/truepower 等）有没有进池；
        // 池里只有原版任务 = 模组武器没被 isWeapon 认到；池里有模组任务 = 权重随机问题
        try {
            long nowT = maid.m_9236_().m_46467_();
            Long poolLast = POOL_DIAG_SINCE.get(maid.m_20148_());
            if (poolLast == null || nowT - poolLast >= 100L) {
                POOL_DIAG_SINCE.put(maid.m_20148_(), nowT);
                StringBuilder mp = new StringBuilder();
                for (IMaidTask t : meleePool) {
                    mp.append(t.getUid()).append(',');
                }
                StringBuilder rp = new StringBuilder();
                for (IMaidTask t : rangedPool) {
                    rp.append(t.getUid()).append(',');
                }
                com.mojang.logging.LogUtils.getLogger().info(
                        "combat pools: maid={} melee=[{}] ranged=[{}]",
                        com.maidsmart.tool.PromaidLog.nameOf(maid), mp, rp);
            }
        } catch (Throwable ignored) {
        }
        return new TaskPools(meleePool, meleeWeights, rangedPool, rangedWeights);
    }

    /** v1.1.0 实测五十七：近战"够不着"阈值（格）——女仆跳跃横距约 3~4 格，敌人比这
     *  还远 = "大跳都跳不过去"，近战状态继续追只会被远程怪风筝 */
    private static final double JUMP_UNREACHABLE_DIST = 6.0;
    /** v1.1.0 实测五十七：索敌范围上限（格）——超过不切远程（超出弓/弩/枪械有效输出） */
    private static final double TARGETING_RANGE = 16.0;

    /**
     * v1.1.0 实测六十一（借鉴 TLM-Sincerely 防抖三件套）：战中换战术稳定状态。
     * lastSwitchTick=上次切换时刻；holdUntil=最短持有到期；cooldownUntil=反向横跳冷却到期；
     * fromUid=上次切换来源任务（反向判定用）。内存态——女仆下线/参战标记清掉时一并清除，
     * 最坏情况（重进后状态丢失）只是多做一次切换，无害。
     */
    private record TacticState(long lastSwitchTick, long holdUntil, long cooldownUntil, String fromUid) {
    }

    private static final java.util.Map<java.util.UUID, TacticState> TACTIC_STATE = new java.util.HashMap<>();

    /**
     * v1.1.0 实测五十七：战斗中近远程动态换战术（每秒评估一次——仅本系统指派的
     * 战斗女仆、威胁仍在、任务没被玩家/排班接管时）。
     *
     * - 战术一（远程被近身 → 切近战）：当前是远程任务、最近敌人 ≤ MELEE_RANGE，
     *   背包/主手有近战类武器（含模组——史诗战斗/拔刀剑等，走 isWeapon 匹配）→
     *   切近战池（池内仍按原版/模组权重随机）。
     * - 战术二（近战够不着 → 切远程）：当前是近战任务、最近敌人 > JUMP_UNREACHABLE_DIST
     *   （大跳都跳不过去）且 ≤ TARGETING_RANGE（仍在索敌范围）→ 有远程武器切远程池。
     * - 5~6 格滞回带防横跳：远程→近战门槛 5、近战→远程门槛 6，两门槛错开 1 格，
     *   敌人在 5~6 格徘徊时不会每秒来回换任务（换任务 = 重建 brain，代价高）。
     * - 池空不切：被近身但没近战武器 → 保持远程硬打（近身反击击退机制兜底）；
     *   够不着但没远程武器 → 保持近战追击。
     *
     * 【兼容关键】切换后同步 ASSIGNED_TAG——还原链路用它判断"任务是否被玩家/排班
     * 换过"（换过=接管，只清标记不还原）。不同步的话战中一换任务就被当成接管，
     * 威胁消失后永不还原 = 卡死在战斗任务。PREV_TASK_TAG（战斗前原任务）不动，
     * 还原流程零改动；LAST_THREAT_TAG 由外层照常刷新，威胁消失计时不受影响。
     *
     * 【模组参与】v1.1.0 实测八十六的"模组隔离（模组任务永不被换出）"在实测
     * 一百零七已作废：旧版只允许 touhou_little_maid 命名空间任务参与近远程切换，
     * 模组任务（拔刀剑/弹幕/御币等）永远被排除；一百零七改为任意 IAttackTask
     * 均可参与——模组任务现在【双向参与】：可被切入（池内含模组候选，火力保留）
     * 也可被换出（当前是模组任务时按距离分类照常评估）。与 pickCombatTask/
     * buildPools 同口径；本门是纯提前返回、不写任何状态——参战触发/还原/僵局阀/
     * 动态圈零影响。
     */
    private static void retuneCombatTactics(EntityMaid maid) {
        IMaidTask cur = maid.getTask();
        if (cur == null) {
            return;
        }
        // v1.1.0 实测一百零七（用户："女仆不会自己的近远战切换"）：旧版只允许
        // touhou_little_maid 命名空间任务参与近远程切换，模组任务（拔刀剑/弹幕/御币等）
        // 永远被排除——即使女仆拿着弓站在远处也只会傻站着近战。修复：改为
        // IAttackTask 实例即可参与切换（与 pickCombatTask/buildPools 同口径）。
        if (!(cur instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask)) {
            return;
        }
        // v1.1.0 实测六十一：最短持有 / 反向横跳冷却（防抖三件套之二）
        long now = maid.m_9236_().m_46467_();
        TacticState st = TACTIC_STATE.get(maid.m_20148_());
        if (st != null) {
            if (now < st.holdUntil()) {
                return; // 刚换过战术，持有期内不再评估
            }
            if (now < st.cooldownUntil()) {
                return; // 横跳冷却中，保持当前战术硬打
            }
        }
        boolean curRanged = isRangedTask(cur);
        double dist = nearestThreatDist(maid);
        if (dist < 0) {
            return; // 扫不到敌人（威胁半径外的残余判定），不动
        }
        boolean wantMelee;
        if (curRanged) {
            if (dist > MELEE_RANGE) {
                return; // 还没被近身，远程继续输出
            }
            // v1.1.0 实测五十八：近战偏好权重 0 = 用户不要近战——被近身也不切，
            // 保持远程硬打（近身反击击退机制兜底）
            if (MaidSmartConfig.COMBAT_PREF_MELEE_WEIGHT.get() <= 0) {
                return;
            }
            wantMelee = true;
        } else {
            if (dist <= JUMP_UNREACHABLE_DIST || dist > TARGETING_RANGE) {
                return; // 追得上（跳跃+贴身可达）或超出索敌范围，维持近战
            }
            // v1.1.0 实测五十八：远程偏好权重 0 = 用户不要远程——够不着也保持近战追击
            if (MaidSmartConfig.COMBAT_PREF_RANGED_WEIGHT.get() <= 0) {
                return;
            }
            wantMelee = false;
        }
        TaskPools pools = buildPools(maid);
        IMaidTask next = wantMelee
                ? (pools.meleePool().isEmpty() ? null : weightedPick(pools.meleePool(), pools.meleeWeights()))
                : (pools.rangedPool().isEmpty() ? null : weightedPick(pools.rangedPool(), pools.rangedWeights()));
        if (next == null || next.getUid().equals(cur.getUid())) {
            return; // 没有对应武器的任务可换 / 选中的就是当前任务
        }
        // v1.1.0 实测一百四十八：换战术前预检 + 自动装备（同参战入口）——装不上
        // （MISSING_REQUIRED_ITEM）就不切，保持现状（模组武器判定走 isWeaponCap 兼容）
        if (CombatTaskCompat.prepareSwitch(maid, next)
                == FunctionCallSwitchResult.MISSING_REQUIRED_ITEM) {
            return;
        }
        // v1.1.0 实测六十一：反向抑制——刚从 fromUid 换到当前任务，窗口内又想换回去
        // = 来回横跳，拒绝本次切换并进入冷却期
        if (st != null && !st.fromUid().isEmpty() && st.fromUid().equals(next.getUid().toString())
                && now - st.lastSwitchTick() <= MaidSmartConfig.COMBAT_REVERSE_WINDOW_TICKS.get()) {
            long cd = MaidSmartConfig.COMBAT_REVERSE_COOLDOWN_TICKS.get();
            if (cd > 0) {
                TACTIC_STATE.put(maid.m_20148_(), new TacticState(st.lastSwitchTick(), 0, now + cd, ""));
                com.mojang.logging.LogUtils.getLogger().info(
                        "auto-combat retune: maid={} reverse {}->{} suppressed, cooldown {} ticks",
                        maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                        cur.getUid(), next.getUid(), cd);
            }
            return;
        }
        // v1.1.0 实测一百三十六：战斗换战术是自动系统——打内部标记放行
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(maid.m_20148_(),
                next.getUid(), () -> maid.setTask(next));
        // 兼容关键：同步指派标记（见方法注释），否则还原链路误判"玩家接管"
        maid.getPersistentData().m_128359_(ASSIGNED_TAG, next.getUid().toString());
        // 记录稳定状态：最短持有 + 来源任务（反向判定用）
        TACTIC_STATE.put(maid.m_20148_(), new TacticState(now,
                now + MaidSmartConfig.COMBAT_TACTIC_HOLD_TICKS.get(), 0, cur.getUid().toString()));
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat retune: maid={} {} -> {} (dist={})",
                maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                cur.getUid(), next.getUid(), String.format("%.1f", dist));
    }

    /** v1.1.0 实测三十八：近战判定距离（格）——敌人 ≤ 此距离用近战 */
    private static final double MELEE_RANGE = 5.0;

    /** 加权随机（原逻辑抽出——同池内仍按原版/模组权重随机，不退化成固定顺序） */
    private static IMaidTask weightedPick(List<IMaidTask> pool, List<Double> weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double roll = RNG.nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * v1.1.0 实测三十八：任务武器类型分类——true=远程，false=近战。
     * 用户口径：史诗战斗/拔刀剑算近战；枪械/弓/弩/三叉戟/弹幕算远程。
     * 判定顺序：任务 UID 白名单（原版五件套 + 枪械）→ 命名空间推断
     * （ef_tlm=史诗战斗、slashblade=拔刀剑 → 近战）→ 默认近战
     * （未知模组任务按近战兜底——冲脸总比站桩安全）。
     * public（实测一百二十②）：MaidCombatTacticsBehavior 的走位分支据此把
     * 法术书/法杖等非投射远程任务也当远程处理。
     */
    public static boolean isRangedTask(IMaidTask task) {
        String uid = task.getUid().toString();
        // 原版远程五件套（弓/弩/三叉戟/弹幕/枪械）——近战 attack 不在表里
        if (uid.equals("touhou_little_maid:ranged_attack")
                || uid.equals("touhou_little_maid:crossbow_attack")
                || uid.equals("touhou_little_maid:trident_attack")
                || uid.equals("touhou_little_maid:danmaku_attack")
                || uid.equals("touhou_little_maid:gun_attack")) {
            return true;
        }
        String ns = task.getUid().m_135827_();
        // v1.1.0 实测一百零七：模组远程任务识别——法术系/投射系按远程处理
        if (ns.equals("maidspell") || ns.equals("spellbook")) {
            return true;
        }
        // v1.1.0 实测一百零七：模组远程任务——UID 包含 ranged/gun/danmaku/spell 关键词
        String uidLower = uid.toLowerCase();
        if (uidLower.contains("ranged") || uidLower.contains("gun")
                || uidLower.contains("danmaku") || uidLower.contains("spell")
                || uidLower.contains("crossbow") || uidLower.contains("trident")
                || uidLower.contains("bow")) {
            return true;
        }
        // 明确的近战模组
        if (ns.equals("ef_tlm") || ns.equals("slashblade") || ns.equals("sbr_core")
                || ns.equals("truepower")) {
            return false;
        }
        // 未知模组任务默认近战（冲脸兜底）
        return false;
    }

    /** v1.1.0 实测三十八：女仆周围最近敌对生物距离（格）；没有敌人返回 -1 */
    private static double nearestThreatDist(EntityMaid maid) {
        try {
            double best = -1;
            // v1.1.0 实测六十八：Monster -> Enemy（同 hasThreatNearby 口径）
            for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(
                    net.minecraft.world.entity.Entity.class,
                    maid.m_20191_().m_82400_(24.0))) {
                if (!(e instanceof net.minecraft.world.entity.monster.Enemy) || !e.m_6084_()) {
                    continue;
                }
                double d = maid.m_20238_(e.m_20182_());
                if (best < 0 || d < best) {
                    best = d;
                }
            }
            return best;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * v1.1.0 实测二十：女仆是否持有该攻击任务认可的武器。
     * v1.1.0 实测一百四十八：判定统一走 CombatTaskCompat.isWeapon——ef_tlm 的
     * isWeapon 恒 false（未覆写，javap 实证），必须用 isWeaponCap 反射补上，
     * 否则史诗战斗的武器永远进不了候选池/永远不被自动装备（"切换武器时不用
     * 模组武器"）。
     */
    private static boolean hasWeaponForTask(EntityMaid maid, IMaidTask task) {
        // v1.1.0 实测六十八（用户："拿斧子的女仆被切到三叉戟模式无法攻击"）：
        // 旧版异常兜底是【整个方法级】的——任何物品的 isWeapon 抛异常就让整个
        // 方法 return true，该任务无凭无据进候选池（三叉戟任务就是这样混进去的，
        // 没三叉戟的女仆切过去根本无法攻击，怪杀不掉威胁不消失也永远不还原）。
        // 改为【逐物品】安全判定：单件物品判定异常只跳过该件，绝不放行整个任务。
        try {
            ItemStack main = maid.m_21205_();
            if (!main.m_41619_() && isWeaponSafe(task, maid, main)) {
                return true;
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && isWeaponSafe(task, maid, s)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false; // 背包遍历本身异常 → 视为无武器（与「空手不参战」同口径）
        }
    }

    /** 单件物品的兼容 isWeapon 判定——模组任务实现抛异常只算这件不匹配 */
    private static boolean isWeaponSafe(IMaidTask task, EntityMaid maid, ItemStack s) {
        return com.maidsmart.combat.CombatTaskCompat.isWeapon(maid, task, s);
    }

    /** 是否带攻击力属性的物品（剑/斧/镐等——对齐 TaskAttack.isWeapon 语义，简化版） */
    @SuppressWarnings("unused")
    private static boolean hasAttackDamage(ItemStack stack) {
        try {
            return stack.m_41638_(EquipmentSlot.MAINHAND).containsKey(Attributes.f_22281_);
        } catch (Exception e) {
            return false;
        }
    }

    /** 该女仆当前处于本系统主动切换的战斗状态（排班调度器让位用——战斗还原后排班接管） */
    public static boolean isAutoCombatActive(EntityMaid maid) {
        return maid.getPersistentData().m_128471_(COMBAT_ACTIVE_TAG);
    }

    /** v1.1.0 实测一百六十三：是否【真实】在战斗中——标记 + 当前任务确实是攻击任务
     *  （或仍在本系统指派的战斗任务上）。仅标记残留 true、任务已不是攻击任务
     *  （老版本残留/战斗早已结束）→ 返回 false：排班不再被残留标记挡死（用户：
     *  "排班不切换、女仆一直跟随主人"——根因就是残留 COMBAT_ACTIVE 让排班永久让位）。 */
    public static boolean isReallyCombatActive(EntityMaid maid) {
        if (!maid.getPersistentData().m_128471_(COMBAT_ACTIVE_TAG)) {
            return false;
        }
        IMaidTask task = maid.getTask();
        return isAssignedOrCombatTask(maid, task);
    }

    /** 清全部标记（putBoolean false 不删键——判定一律走 getBoolean，contains 会永远为 true）
     *  v1.1.0 实测六十一：一并清战中换战术的稳定状态（内存态） */
    private static void clearMarkers(EntityMaid maid) {
        maid.getPersistentData().m_128379_(COMBAT_ACTIVE_TAG, false);
        maid.getPersistentData().m_128379_(PREV_TASK_TAG, false);
        maid.getPersistentData().m_128379_(LAST_THREAT_TAG, false);
        maid.getPersistentData().m_128379_(ASSIGNED_TAG, false);
        // v1.1.0 实测八十四：接触标记一并清（判定走 getBoolean，putBoolean false 不删键）
        maid.getPersistentData().m_128379_(LAST_CONTACT_TAG, false);
        // v1.1.0 实测一百六十二：战斗开始时间一并清
        maid.getPersistentData().m_128379_(COMBAT_START_TAG, false);
        RESTORE_DIAG_SINCE.remove(maid.m_20148_());
        TACTIC_STATE.remove(maid.m_20148_());
    }

    /** v1.1.0 实测一百六十四：威胁判定详情（诊断用）——fixed=固定半径内敌对生物距离；
     *  ring=动态圈来源（存活/已死）；none=无威胁；err=异常。定位 hasThreatNearby 为何
     *  一直 true（固定扫描 vs 动态圈哪个在挡还原）。 */
    private static String threatDetail(EntityMaid maid) {
        try {
            double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get();
            for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(
                    net.minecraft.world.entity.Entity.class, maid.m_20191_().m_82400_(r))) {
                if (!e.m_6084_()) {
                    continue;
                }
                if (e instanceof net.minecraft.world.entity.monster.Enemy) {
                    return "fixed:" + String.format("%.1f",
                            Math.sqrt(maid.m_20238_(e.m_20182_())));
                }
                if (e instanceof net.minecraft.world.entity.NeutralMob nm && neutralAngry(nm)) {
                    return "fixed-neutral:" + String.format("%.1f",
                            Math.sqrt(maid.m_20238_(e.m_20182_())));
                }
            }
            int sec = MaidSmartConfig.COMBAT_AUTO_SWITCH_EXPAND.get();
            if (sec > 0) {
                long nowT = maid.m_9236_().m_46467_();
                long marked = maid.getPersistentData().m_128454_(ATTACKER_TIME_TAG);
                if (nowT - marked <= sec * 20L) {
                    String uuidStr = maid.getPersistentData().m_128461_(ATTACKER_UUID_TAG);
                    if (!uuidStr.isEmpty()) {
                        net.minecraft.world.entity.Entity attacker =
                                ((net.minecraft.server.level.ServerLevel) maid.m_9236_())
                                        .m_8791_(java.util.UUID.fromString(uuidStr));
                        if (attacker != null && attacker.m_6084_()) {
                            return "ring:alive@" + String.format("%.1f",
                                    Math.sqrt(maid.m_20238_(attacker.m_20182_())));
                        }
                        return "ring:dead";
                    }
                }
            }
            return "none";
        } catch (Exception e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }

    /** 女仆仍在本系统指派的战斗任务上（任务被换过 = 玩家/排班/LLM 已接管）
     *  v1.1.0 实测一百一十四【还原失效根因】：TLM getTask() 读同步数据 DATA_TASK，
     *  javap 实证 findTask(uid).orElse(getIdleTask())——uid 解析失败/同步抖动时
     *  直接回落成 idle 任务；加上战中 retune 换战术、第三方模组换任务，getTask()
     *  与 ASSIGNED 比对极易不一致 → 旧版一律判"玩家接管"→ 静默清标记 → 战斗任务
     *  永不还原（日志实证：15 次参战零还原，每次 prev 都显示 idle）。
     *  修复：只要当前任务仍是【攻击类任务】（IAttackTask，与 buildPools 同口径），
     *  一律视为"本系统的战斗"继续推进还原；只有任务真被换成非攻击任务才按接管处理。
     *  v1.1.0 实测一百四十九：改为【传入任务】的判定——同一 tick 只读一次 getTask()，
     *  所有判定共用同一读数（DATA_TASK 同步抖动时多次读取会自相矛盾）。 */
    private static boolean isAssignedOrCombatTask(EntityMaid maid, IMaidTask task) {
        String assigned = maid.getPersistentData().m_128461_(ASSIGNED_TAG);
        if (assigned.isEmpty() || task == null) {
            return false;
        }
        if (assigned.equals(task.getUid().toString())) {
            return true;
        }
        return task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
    }

    /** v1.1.0 实测一百三十九：当前任务读数是否为"假 idle"——TLM getTask() 读同步
     *  数据 DATA_TASK，uid 解析失败/同步抖动时回落成 idle 任务（实测一百一十四的
     *  javap 实证）。idle 读数不代表"玩家接管"，还原链不能被它清掉。
     *  v1.1.0 实测一百四十九：传入任务判定（单次读取，防抖动自相矛盾）。 */
    private static boolean isIdleReadingTask(IMaidTask task) {
        return task == null || task.getUid() == null
                || "touhou_little_maid:idle".equals(task.getUid().toString());
    }

    /** v1.1.0 实测一百四十九（参考 tlm_beyond_space TaskSwitchService.restore / 
     *  restoreAfterExternalTaskChange）：还原战斗前的 home 模式与作息（MaidSchedule）
     *  ——"切回之前的模式"完整闭环（任务 + home + 作息）。只在【排班关闭】时生效：
     *  排班开启时作息/守家由日程表管理（调度器每秒重断言），此处覆盖会与排班打架；
     *  且 setSchedule 在排班开启时会被守卫 mixin 拦（此处仅排班关闭时调用，天然放行）。 */
    private static void restorePrevMode(EntityMaid maid) {
        try {
            if (!com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                maid.setHomeModeEnable(maid.getPersistentData().m_128471_(COMBAT_PREV_HOME_TAG));
                String sched = maid.getPersistentData().m_128461_(COMBAT_PREV_SCHEDULE_TAG);
                if (!sched.isEmpty()) {
                    for (MaidSchedule ms : MaidSchedule.values()) {
                        if (ms.name().equals(sched)) {
                            maid.setSchedule(ms);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.1.0 实测一百三十九（参考 tlm_beyond_space 的会话快照机制）：参战前原任务 UID
     * 的【可靠】取值——日志实证旧版每次参战都录成 idle（getTask 抖动回落），还原回
     * idle = "切不回原来模式"。取值顺序：① getTask() 真实任务（非 idle）→ 用它
     *（玩家手动安排的任务优先）；② 排班开启且有段 → 用当前时段排班任务（排班是
     * 权威，还原就该回排班）；③ 兜底 idle。
     */
    private static String resolvePrevTaskUid(EntityMaid maid) {
        if (maid.getTask() != null && maid.getTask().getUid() != null
                && !"touhou_little_maid:idle".equals(maid.getTask().getUid().toString())) {
            return maid.getTask().getUid().toString();
        }
        try {
            if (com.maidsmart.schedule.ScheduleData.isOn(maid)
                    && maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
                var segs = com.maidsmart.schedule.ScheduleData.load(maid);
                if (!segs.isEmpty()) {
                    var seg = com.maidsmart.schedule.ScheduleData.segmentAt(segs,
                            com.maidsmart.schedule.ScheduleData.currentMinute(sl));
                    if (seg != null && seg.taskUid() != null && !seg.taskUid().isEmpty()
                            && !"touhou_little_maid:idle".equals(seg.taskUid())) {
                        return seg.taskUid();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "touhou_little_maid:idle";
    }
}
