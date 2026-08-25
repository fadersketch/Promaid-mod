package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
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
    /** 僵局日志节流（每女仆 30 秒一条，latest.log 搜 "auto-combat stale"） */
    private static final java.util.Map<java.util.UUID, Long> STALE_LOG =
            new java.util.HashMap<>();
    private static final Random RNG = new Random();
    /** 还原扫描节流（每 20 tick = 1 秒一次） */
    private int restoreThrottle = 0;

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
        if (maid.getPersistentData().m_128471_(PREV_TASK_TAG)) {
            if (isOnAssignedCombatTask(maid)) {
                // v1.1.0 实测八十四b：续杯安全计时只在【真实存在敌对威胁】时进行——
                // 旧版任何触发（含主人打被动生物的连锁评估）都无条件刷新 LAST_THREAT，
                // 无威胁战斗里还原时钟被反复推走 = 打完收不回去的第二道源头
                if (hasThreatNearby(maid)) {
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
                }
                return 0;
            }
            clearMarkers(maid); // 接管退出——不还原、不再背着旧标记
        }
        // 已是攻击类任务（IAttackTask：玩家手动安排的近战/弓/弹幕，或万法皆通/
        // 史诗战斗等第三方攻击任务）→ 她本来就能打，尊重现状不切换不记录
        if (MaidWorkTags.isCombatTask(maid)) {
            return 2;
        }
        IMaidTask combat = pickCombatTask(maid);
        if (combat == null) {
            return 0; // 单只找不到任务不连坐（此前 return 会跳过同半径的其他女仆）
        }
        String prevUid = maid.getTask() != null
                ? maid.getTask().getUid().toString() : "touhou_little_maid:idle";
        maid.getPersistentData().m_128359_(PREV_TASK_TAG, prevUid);
        maid.getPersistentData().m_128359_(ASSIGNED_TAG, combat.getUid().toString());
        maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
        // v1.1.0 实测八十四：参战即视为一次接触（僵局逃逸阀计时起点刷新）
        touchContact(maid);
        maid.setTask(combat);
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
                if (!maid.m_6084_() || !maid.getPersistentData().m_128471_(PREV_TASK_TAG)) {
                    continue;
                }
                // 自保中不还原（等自保结束；自保退出有自己的回主人逻辑）
                if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
                    continue;
                }
                // 战斗期间任务被玩家/排班/LLM 换过 → 玩家接管：只清标记退出，不动当前任务
                if (!isOnAssignedCombatTask(maid)) {
                    clearMarkers(maid);
                    // v1.1.0 实测九十四：运行日志
                    com.maidsmart.tool.PromaidLog.log("战斗",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 战斗中任务被接管（玩家/排班/LLM），清标记退出");
                    continue;
                }
                long now = level.m_46467_();
                // v1.1.0 实测八十四：僵局逃逸阀——威胁仍在还原半径内，但双方超过
                // N 秒没有任何伤害往来（怪卡墙后/玻璃后/传送门里/飞行够不着等
                // "杀不掉也够不着"的死局），不再无限续杯安全计时 → 正常走还原。
                // 被动生物（动物）本就不算威胁（判定只认 Enemy 接口），与本次无关；
                // 该阀门专治"敌对生物永久滞留半径内"的卡死。
                boolean threatNearby = hasThreatNearby(maid);
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
                if (!restoreOn) {
                    continue; // 自动还原关：只换战术不还原
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
                    // 原任务已不存在（模组卸载等）→ 只能留在战斗任务，标记保留
                    //（每秒重试无意义，但保留标记让玩家手动切换后能正常接管退出）
                    // v1.1.0 实测九十四：运行日志
                    com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 还原失败：原任务 '" + prevUid + "' 已不存在，保留标记等待手动接管");
                    continue;
                }
                clearMarkers(maid);
                boolean restored = false;
                boolean stillOnCombat = maid.getTask() != null
                        && maid.getTask().getUid().toString().equals(assignedUid);
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
                    maid.setTask(prevTask);
                    restored = true;
                }
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
                    String curTask = maid.getTask() == null ? "null" : maid.getTask().getUid().toString();
                    com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 无需还原：任务战中已被换为 " + curTask);
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
     * 【模组隔离】v1.1.0 实测八十六：战中互换仅限【原版任务】——原版任务可切入
     * 模组池（保留火力），但模组任务永不被换出：模组武器普遍更强且常带专属机制
     * （史诗战斗姿态/万法皆通施法循环），isRangedTask 的二分法对它们只是瞎猜，
     * 切走反而坏事；被近身时信任模组武器自身的近身机制（近身反击击退兜底）。
     * 本门是纯提前返回、不写任何状态——参战触发/还原/僵局阀/动态圈零影响。
     */
    private static void retuneCombatTactics(EntityMaid maid) {
        IMaidTask cur = maid.getTask();
        if (cur == null) {
            return;
        }
        // v1.1.0 实测八十六：模组隔离门（见上）——m_135827_ = getNamespace
        if (!"touhou_little_maid".equals(cur.getUid().m_135827_())) {
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
        maid.setTask(next);
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
     */
    private static boolean isRangedTask(IMaidTask task) {
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
        // 史诗战斗（ef_tlm 的 FightModeTask）/拔刀剑系任务 = 近战
        if (ns.equals("ef_tlm") || ns.equals("slashblade") || ns.equals("sbr_core")
                || ns.equals("truepower")) {
            return false;
        }
        // 万法皆通等法术系任务按远程处理（法术是投射输出）
        if (ns.equals("maidspell") || ns.equals("spellbook")) {
            return true;
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
     * 优先走任务自己的 isWeapon（模组任务自定义判定），异常/全否时对
     * 原版五件套做物品类型兜底（与旧版判定同口径）。
     */
    private static boolean hasWeaponForTask(EntityMaid maid,
                                            com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask task) {
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

    /** 单件物品的 isWeapon 安全判定——模组任务实现抛异常只算这件不匹配 */
    private static boolean isWeaponSafe(com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask task,
                                        EntityMaid maid, ItemStack s) {
        try {
            return task.isWeapon(maid, s);
        } catch (Throwable ignored) {
            return false;
        }
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
        return maid.getPersistentData().m_128471_(PREV_TASK_TAG);
    }

    /** 清全部标记（putBoolean false 不删键——判定一律走 getBoolean，contains 会永远为 true）
     *  v1.1.0 实测六十一：一并清战中换战术的稳定状态（内存态） */
    private static void clearMarkers(EntityMaid maid) {
        maid.getPersistentData().m_128379_(PREV_TASK_TAG, false);
        maid.getPersistentData().m_128379_(LAST_THREAT_TAG, false);
        maid.getPersistentData().m_128379_(ASSIGNED_TAG, false);
        // v1.1.0 实测八十四：接触标记一并清（判定走 getBoolean，putBoolean false 不删键）
        maid.getPersistentData().m_128379_(LAST_CONTACT_TAG, false);
        TACTIC_STATE.remove(maid.m_20148_());
    }

    /** 女仆仍在本系统指派的战斗任务上（任务被换过 = 玩家/排班/LLM 已接管） */
    private static boolean isOnAssignedCombatTask(EntityMaid maid) {
        String assigned = maid.getPersistentData().m_128461_(ASSIGNED_TAG);
        return maid.getTask() != null && !assigned.isEmpty()
                && assigned.equals(maid.getTask().getUid().toString());
    }
}
