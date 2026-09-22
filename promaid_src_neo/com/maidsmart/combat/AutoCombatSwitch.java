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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.items.IItemHandler;

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
 * - v1.2.2 实测六百二十一【战斗模式分类表】：上面那套"谁能参与、算近战还是远程、
 *   模组任务是否优先"原本全是写死的推断（反馈："这个逻辑太笼统了"），现在可以在
 *   主动参战面板里逐任务点名（{@code CombatModeTable}，配置 {@code combat.taskModes}
 *   + {@code combat.vanillaYieldToMod}）——写「不参与」的任务两条路都不选它，
 *   写「近战/远程」的任务分类以表为准且不被"模组优先让位"挤掉。空表 = 旧行为。
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
    static final String COMBAT_ACTIVE_TAG = "maid_smart_combat_active";
    /** 战斗前原任务 UID（persistentData，切战斗时写入，还原时读取） */
    static final String PREV_TASK_TAG = "maid_smart_combat_prev_task";
    /** 最近一次看到威胁的 gameTime（还原延迟计时基准） */
    static final String LAST_THREAT_TAG = "maid_smart_combat_last_threat";
    /** 本系统指派的战斗任务 UID（还原时校验任务没被玩家换过——换过=玩家接管，只清标记退出） */
    static final String ASSIGNED_TAG = "maid_smart_combat_task";
    /** v1.1.0 实测八十四：最近一次与敌对生物有伤害往来的 gameTime（僵局逃逸阀计时——
     *  威胁在半径内但双方久无接触 = 够不着的死局，不再无限续杯安全计时） */
    static final String LAST_CONTACT_TAG = "maid_smart_combat_last_contact";
    /** v1.1.0 实测八十五：最近伤害来源（动态威胁圈）——uuid + 登记时刻。
     *  还原扫描时该生物若仍存活且在扩展窗口内，威胁圈自动放大把它包含进来 */
    static final String ATTACKER_UUID_TAG = "maid_smart_combat_attacker";
    static final String ATTACKER_TIME_TAG = "maid_smart_combat_attacker_time";
    /** v1.1.0 实测一百四十九（参考 tlm_beyond_space 会话快照 RegularRescueSupport）：
     *  战斗前的 home 模式——还原时一并恢复（"切回之前的模式"闭环：任务+home+作息） */
    static final String COMBAT_PREV_HOME_TAG = "maid_smart_combat_prev_home";
    /** 战斗前的作息（MaidSchedule.name；空 = 未记录） */
    static final String COMBAT_PREV_SCHEDULE_TAG = "maid_smart_combat_prev_schedule";
    /** v1.1.0 实测一百六十二：战斗会话开始时间（tick）——硬性超时还原兜底的计时基准 */
    static final String COMBAT_START_TAG = "maid_smart_combat_start";
    /** v1.1.0 实测一百六十二：战斗会话硬性超时（tick，90 秒）——超过仍未还原就强制
     *  切回，杜绝任何门（威胁判定/安全计时/僵局阀失效）把女仆永久卡在战斗态。 */
    private static final long COMBAT_HARD_DEADLINE_TICKS = 1800L;
    /** 僵局日志节流（每女仆 30 秒一条，latest.log 搜 "auto-combat stale"） */
    private static final java.util.Map<java.util.UUID, Long> STALE_LOG =
            new java.util.HashMap<>();
    static final Random RNG = new Random();
    /** 还原扫描节流（每 20 tick = 1 秒一次） */
    private int restoreThrottle = 0;
    /** v1.1.0 实测一百七十二：还原扫描心跳（每 10 秒一条，latest.log 搜 "auto-combat
     *  scan"）——确认 onServerTick 在跑 + 战斗态女仆数 + 被排班清理数，定位"还原
     *  永不触发"是扫描没跑 / 女仆被门拦 / 还是还原动作本身失败 */
    private long lastScanHeartbeat = 0;
    /** v1.1.0 实测一百六十四：还原扫描诊断节流（maidId → 上次诊断 tick）——定位
     *  "还原扫描卡在哪个门"（追问：为什么卡住，不能只加超时兜底）。每 10 秒/女仆
     *  一条 latest.log（搜 "restore-scan"）。 */
    static final java.util.Map<java.util.UUID, Long> RESTORE_DIAG_SINCE = new java.util.HashMap<>();
    /** v1.1.0 实测一百六十九：候选池诊断节流（maidId → 上次诊断 tick）——确认模组
     *  武器任务有没有进战斗候选池（反馈："女仆仍然不会使用模组的武器"）。每 5 秒/
     *  女仆一条 latest.log（搜 "combat pools"）。 */
    static final java.util.Map<java.util.UUID, Long> POOL_DIAG_SINCE = new java.util.HashMap<>();

    /** 主人被攻击（任意来源）→ 附近女仆切战斗
     *  v1.1.0 实测二十：旧版只认敌对生物攻击（Enemy）——玩家互打/PVP、其他模组的
     *  非标准敌对生物、环境伤害都不触发。现改为任意来源受伤即触发（自伤除外）。
     *
     *  v1.1.0 实测三十六（反馈："主人满血受伤仍不触发，当时拿的是卓越前线充能手枪"）：
     *  LivingIncomingDamageEvent 不是唯一的"主人受伤"信号——SBW（卓越前线）等枪械模组的
     *  伤害走自定义管线（DamageHandler 自管伤害计算），受伤事件可能【不发】或
     *  被其他订阅者【取消】（EF/SBW 都会 cancel LivingIncomingDamageEvent 改走自己的减伤）。
     *  兑底方案：同时监听 LivingIncomingDamageEvent（受伤链最上游，cancel 之前必经）+
     *  LivingIncomingDamageEvent（结算层）——三个事件任何一个先到就触发，后到的被节流
     *  跳过（同一次攻击只切一次）。 */
    @SubscribeEvent
    public void onOwnerHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测三百二十（反馈："装了 mod 后带女仆到雪地，空闲模式会打雪仗然后
        // 对我触发攻击，会冲我跳劈"）：0 伤害攻击不触发参战——TLM 原版空闲模式在
        // 雪地有打雪仗行为（MaidSnowballTargetTask），雪球命中主人伤害为 0 但事件
        // 照发，旧版不检查伤害值 → 女仆被"雪球"拉进战斗 → 切攻击任务 → 战术行为
        // 把主人当目标跳劈（脱甲 4 颗心）。雪球/鸡蛋等娱乐性弹射物伤害恒 0，直接
        // 拦截；真实攻击（僵尸 3 点等）照常触发。
        if (event.getAmount() <= 0.0f) {
            return;
        }
        // v1.1.0 实测二十：不再限定敌对生物来源——主人被【任何东西】攻击都算开战
        //（PVP 玩家互打、模组自定义敌对生物、弹射物等都覆盖；自伤仍排除）
        if (event.getSource() == null || event.getSource().getEntity() == null
                || event.getSource().getEntity() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：LivingIncomingDamageEvent 兑底——受伤链最上游（hurt() 开头就发，
     *  在任何模组 cancel LivingIncomingDamageEvent 之前必然经过）。 */
    @SubscribeEvent
    public void onOwnerAttacked(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测三百二十：0 伤害攻击不触发（雪球/鸡蛋等娱乐性弹射物——
        // 见 onOwnerHurt 注释）
        if (event.getAmount() <= 0.0f) {
            return;
        }
        if (event.getSource() == null || event.getSource().getEntity() == null
                || event.getSource().getEntity() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：LivingIncomingDamageEvent 兑底——结算层事件（LivingIncomingDamageEvent 之后；
     *  枪械模组自定义管线常直接走到这层）。 */
    @SubscribeEvent
    public void onOwnerDamaged(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测三百二十：0 伤害不触发（雪球/鸡蛋等娱乐性弹射物——见 onOwnerHurt 注释）
        if (event.getAmount() <= 0.0f) {
            return;
        }
        if (event.getSource() == null || event.getSource().getEntity() == null
                || event.getSource().getEntity() == player) {
            return;
        }
        this.tryTrigger(player);
    }

    /** v1.1.0 实测三十六：三事件去重节流——同一玩家 20 tick（1 秒）内多个事件
     *  只触发一次切换扫描（Attack/Hurt/Damage 三连发是同一次攻击的正常现象） */
    private final java.util.Map<java.util.UUID, Long> triggerThrottle = new java.util.HashMap<>();

    private void tryTrigger(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long last = this.triggerThrottle.get(player.getUUID());
        if (last != null && now - last < 20L) {
            return;
        }
        this.triggerThrottle.put(player.getUUID(), now);
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat trigger: owner={} hp={}/{}",
                player.getDisplayName().getString(),
                String.format("%.0f", player.getHealth()), String.format("%.0f", player.getMaxHealth()));
        switchNearbyMaids(player);
    }

    /**
     * v1.1.0 实测二十：主人攻击了别的生物 → 也触发（护主不只被动挨打才算开战，
     * 主人主动开火同样进入战斗）。
     *
     * v1.1.0 实测二十八修复（反馈："主动战斗没有成功生效"）：旧版把
     * event.getEntity()（=【受害者】）instanceof ServerPlayer 当判断——受害者
     * 是玩家、来源又是玩家的组合只在"玩家打玩家"才成立；主人打怪时受害者是
     * Monster，第一个 if 直接 return，主动开火触发从未生效。正确写法：
     * 受害者任意、【来源】是玩家才算"主人开火"。
     *
     * v1.1.0 实测三十六：补 LivingIncomingDamageEvent 来源侧监听——SBW 枪械伤害走自定义
     * 管线时 LivingIncomingDamageEvent 可能不发，结算层事件兜底。
     */
    @SubscribeEvent
    public void onOwnerAttack(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        // 来源（getEntity=getEntity）是玩家 = 主人亲手造成的伤害（女仆打的不连锁触发）
        if (!(event.getSource() != null && event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        // 打的是自己的女仆不算开战（误伤/管教场景）
        if (event.getEntity() instanceof EntityMaid m && m.getOwner() == attacker) {
            return;
        }
        // v1.1.0 实测八十四b：目标必须是【敌对生物】才算主动开战——宰牛杀鸡/剪羊毛
        // 等被动生物交互不再让女仆全员拔刀。这类"无威胁战斗"还原扫描永远扫不到
        // 威胁、安全计时只被主人的后续命中无限续杯，是"打完收不回去"的根源。
        // v1.1.0 实测八十七：中立激怒口径——正在记仇主人的中立生物（追着主人咬的
        // 狼/带崽北极熊）也算交战对象，帮打合理；平静态的照样不触发。
        // v1.1.0 实测三百一十八：驯服宠物记仇主人也算（发狂的驯服狼——受伤事件在
        // 记仇状态设置前触发，isAngryAt 恒 false，见 isAngryTamedAt 注释）
        // v1.1.0 实测三百四十六（反馈："只要是 target=主人/女仆的都会被额外列入
        // 威胁"）：加行为化判定——正在锁定【主人或主人身边任意女仆】的生物即使
        // 记仇状态未就位（模组生物用自有仇恨系统、isAngry 不写）也算交战对象
        net.minecraft.world.entity.Entity victimEnt = event.getEntity();
        if (!(victimEnt instanceof net.minecraft.world.entity.monster.Enemy)
                && !AutoCombatTargeting.isAngryNeutralAt(victimEnt, attacker)
                && !AutoCombatTargeting.isAngryTamedAt(victimEnt, attacker)
                && !AutoCombatTargeting.isTargetingAnyoneOf(victimEnt, attacker)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** v1.1.0 实测三十六：主人开火的 LivingIncomingDamageEvent 兜底（同 onOwnerAttack 的
     *  来源侧判定，事件换结算层）。v1.1.0 实测八十四b：同样要求目标是敌对生物。 */
    @SubscribeEvent
    public void onOwnerAttackDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getSource() != null && event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        if (event.getEntity() instanceof EntityMaid m && m.getOwner() == attacker) {
            return;
        }
        // v1.1.0 实测八十七：同 onOwnerAttack——敌对生物或记仇主人的中立生物
        // v1.1.0 实测三百一十八：驯服宠物记仇主人也算（同 onOwnerAttack 口径）
        // v1.1.0 实测三百四十六：行为化口径（同 onOwnerAttack——锁定主人/
        // 女仆的生物即使 isAngry 未就位也算）
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)
                && !AutoCombatTargeting.isAngryNeutralAt(event.getEntity(), attacker)
                && !AutoCombatTargeting.isAngryTamedAt(event.getEntity(), attacker)
                && !AutoCombatTargeting.isTargetingAnyoneOf(event.getEntity(), attacker)) {
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
        for (EntityMaid maid : player.level().getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(r))) {
            if (maid.getOwner() != player) {
                continue; // 只响应主人自己的女仆
            }
            int result = AutoCombatTargeting.tryEngageMaid(maid);
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
                    player.getDisplayName().getString(), skippedCombat);
        }
    }

    /* ==================== v1.1.0 实测五十八：女仆被怪打 → 自主参战 ==================== */
    /** v1.1.0 实测三百四十四：对外触发单只女仆参战评估（NeutralThreatDriver 用）——扫到"锁定主人/女仆为目标"的魔改/中立生物时补触发（主 —— 实现见 AutoCombatTargeting.tryEngagePublic（v1.2.4 拆分）。 */
    static void tryEngagePublic(EntityMaid maid) { AutoCombatTargeting.tryEngagePublic(maid); }

    /** v1.1.0 实测三百四十四（反馈："有一部分 mod 会魔改原版被动生物，让被动生物都会变成像狼这样的中立生物，所以我觉得这方面的判定需要加强"） —— 实现见 AutoCombatTargeting.isTargetingOurSide（v1.2.4 拆分）。 */
    static boolean isTargetingOurSide(net.minecraft.world.entity.Entity e, net.minecraft.world.entity.LivingEntity owner, net.minecraft.world.entity.LivingEntity maid) { return AutoCombatTargeting.isTargetingOurSide(e, owner, maid); }

    /**
     * 女仆被怪物攻击（近身拍打/远程弹射物——弹射物伤害来源=射击者）→ 她【本人】
     * 立即参战（不受响应半径限制——她就在现场），同主人的姐妹在响应半径内一并
     * 响应（与护主同款群体防御）。
     * 来源只认 Monster（敌对生物）：玩家打女仆走 TLM 自己的仇恨/管教体系不在这里
     * 反击；主人打女仆是管教不还手；女仆之间不打架（防不同主人的女仆互殴升级成
     * 连环混战）。与护主触发同款三事件监听（枪械等模组可能取消中间层事件）。
     */
    @SubscribeEvent
    public void onMaidHurt(LivingIncomingDamageEvent event) {
        // v1.1.0 实测一百七十三：LivingHurt 等实体事件客户端也会触发（日志实证
        // 14:15:11 Render thread 跑了整套 engage）——客户端实体与服务器实体是两份
        // 独立 persistentData，客户端上写标记/切任务不落服务端，还会污染 promaid.log
        // 的"参战"记录。客户端一律跳过，只服务端处理。
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (AutoCombatTargeting.maidVictimOfMonster(event.getEntity(), event.getSource())) {
            AutoCombatTargeting.touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    @SubscribeEvent
    public void onMaidAttacked(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (AutoCombatTargeting.maidVictimOfMonster(event.getEntity(), event.getSource())) {
            AutoCombatTargeting.touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    @SubscribeEvent
    public void onMaidDamaged(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (AutoCombatTargeting.maidVictimOfMonster(event.getEntity(), event.getSource())) {
            AutoCombatTargeting.touchContactFromSource((EntityMaid) event.getEntity(), event.getSource());
            this.engageAttackedMaid((EntityMaid) event.getEntity());
        }
    }

    /** 实测三百四十五：对外记录一次战斗接触（NeutralThreatDriver 直接挥砍成功时调用——还原扫描的僵局逃逸阀以"最近伤害往来"计时，我方补刀也算往来 —— 实现见 AutoCombatTargeting.touchContactPublic（v1.2.4 拆分）。 */
    static void touchContactPublic(EntityMaid maid) { AutoCombatTargeting.touchContactPublic(maid); }

    /**
     * v1.1.0 实测八十四：女仆【打到】敌对生物也算一次战斗接触——僵局逃逸阀的
     * 另一个计时来源（只算"挨打"的话，远程女仆放风筝全程无伤会被误判成死局）。
     * getDirectEntity = DamageSource.getEntity（造成者；弓箭等弹射物的造成者是射手本体，
     * 与 getEntity getDirectEntity=箭矢实体相对）。
     */
    @SubscribeEvent
    public void onMaidStrikeEnemy(LivingIncomingDamageEvent event) {
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        if (!(event.getSource().getDirectEntity() instanceof EntityMaid maid)) {
            return;
        }
        // 客户端同款事件跳过（见 onMaidHurt 注释）
        if (maid.level().isClientSide()) {
            return;
        }
        // v1.1.0 实测三百四十六（反馈："不论是哪种生物，只要是 target=主人/女仆
        // 的都会被额外列入威胁"）：女仆打到【锁定主人/女仆的任意生物】也算一次
        // 战斗接触——旧版只认 Enemy，打狼/魔改生物时僵局逃逸阀收不到接触信号
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)
                && !isTargetingOurSide(event.getEntity(),
                ((EntityMaid) event.getSource().getDirectEntity()).getOwner(), maid)) {
            return;
        }
        AutoCombatTargeting.touchContact(maid);
    }

    /** 被打女仆 + 周围同主人姐妹一起参战（三事件 20 tick 去重，与护主触发同口径） */
    private void engageAttackedMaid(EntityMaid victim) {
        long now = victim.level().getGameTime();
        Long last = this.triggerThrottle.get(victim.getUUID());
        if (last != null && now - last < 20L) {
            return;
        }
        this.triggerThrottle.put(victim.getUUID(), now);
        AutoCombatTargeting.tryEngageMaid(victim); // 挨打的本人在哪都响应
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
        for (EntityMaid maid : victim.level().getEntitiesOfClass(EntityMaid.class,
                victim.getBoundingBox().inflate(r))) {
            if (maid == victim) {
                continue;
            }
            if (maid.getOwner() == null || maid.getOwner() != victim.getOwner()) {
                continue; // 只带同主人的姐妹（无主野女仆不卷入）
            }
            AutoCombatTargeting.tryEngageMaid(maid);
        }
        com.mojang.logging.LogUtils.getLogger().info(
                "auto-combat: maid attacked -> engage self + sisters (victim={})",
                victim.getDisplayName() != null ? victim.getDisplayName().getString() : victim.getUUID());
    }

    /**
     * 威胁消失持续够久 → 还原原任务。扫描持久化标记（非内存集合）——
     * 存档重读/魂符收放/换维度后仍能正确还原，不会卡死在战斗任务上。
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
if (++this.restoreThrottle < 20) {
            return; // 每秒检查一次
        }
        this.restoreThrottle = 0;
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // v1.1.0 实测五十七：战中近远程换战术只依赖总开关——自动还原关掉时，
        // 换战术仍然工作（还原关 = 玩家要她打到底，但打得聪明依旧成立）
        boolean restoreOn = MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE.get();
        int activeCount = 0;
        int schedCleared = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // v1.1.0 实测一百七十三【还原永不触发的总根因】：无限 AABB 经
            // SectionPos.blockToSection 换算后 ±∞ 都溢出收敛到同一个值
            // 134217727（floor 溢出回绕 + >>4）——section 循环只执行一次、且落在
            // 世界外的列上 → getEntitiesOfClass 永远返回空列表 → 还原扫描自 v1.1.0
            // 起从未扫到过任何战斗女仆（日志实证：心跳在跑、active 恒 0、restore-scan
            // 零输出、还原动作从未执行）。改为覆盖整个可玩范围的有限 AABB
            // （x/z ±131072 = ±128km，y ±4096 覆盖全部建筑高度）：blockToSection
            // 对有限值正常换算，循环覆盖所有已加载区块。
            // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
            // instanceof 过滤——ClassInstanceMultiMap 桶 bug（同 FarmTillDriver）：
            // 未预建 EntityMaid 桶的 section 被整段跳过，还原扫描扫不到该 section
            // 里的战斗女仆 → 战斗还原永不触发
            // v1.2.0（2026-09-18）【Sable 兼容】：全世界 AABB → getAllEntities()（超大 AABB 被 Sable 拒查并且每次刷一份堆栈日志）
            for (net.minecraft.world.entity.Entity ent : level.getAllEntities()) {
                if (!(ent instanceof EntityMaid maid) || !maid.isAlive()
                        || !((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(COMBAT_ACTIVE_TAG)) {
                    continue;
                }
                activeCount++;
                // v1.2.2 实测五百九十：傀儡模式（第三方玩法）期间【不还原、不干预】——
                // 只清掉残留的战斗标记就退出（她的任务由玩家管，本模组战术全体让位）
                if (com.maidsmart.compat.MaidModeCompat.isPuppetMode(maid)) {
                    AutoCombatPools.clearMarkers(maid);
                    continue;
                }
                // v1.1.0 实测一百六十三：排班开启的女仆不参与自主战斗——残留的战斗
                // 标记直接清掉（她的任务/模式由日程表管理，战斗还原链不再适用）
                if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                    AutoCombatPools.clearMarkers(maid);
                    schedCleared++;
                    continue;
                }
                // 自保中不还原（等自保结束；自保退出有自己的回主人逻辑）
                if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(SelfPreservationBehavior.PRESERVE_TAG)) {
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
                // "restore-scan"）——定位"还原扫描卡在哪个门"（追问：为什么卡住，
                // 不能只加超时兜底）：task=当前任务 / assigned=指派战斗任务 /
                // lastThreatAge=距上次威胁刷新秒数 / threatDetail=威胁来源明细 /
                // preserve=自保 / isOnSched=排班开启
                Long diagLast = RESTORE_DIAG_SINCE.get(maid.getUUID());
                long diagNow = level.getGameTime();
                if (diagLast == null || diagNow - diagLast >= 200L) {
                    RESTORE_DIAG_SINCE.put(maid.getUUID(), diagNow);
                    com.mojang.logging.LogUtils.getLogger().info(
                            "auto-combat restore-scan: maid={} task={} assigned={} lastThreatAge={}s threatDetail={} preserve={} isOnSched={}",
                            com.maidsmart.tool.PromaidLog.nameOf(maid),
                            curTask != null && curTask.getUid() != null ? curTask.getUid() : "null",
                            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(ASSIGNED_TAG),
                            (diagNow - ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getLong(LAST_THREAT_TAG)) / 20,
                            AutoCombatTargeting.threatDetail(maid),
                            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(SelfPreservationBehavior.PRESERVE_TAG),
                            com.maidsmart.schedule.ScheduleData.isOn(maid));
                }
                if (!AutoCombatPools.isAssignedOrCombatTask(maid, curTask) && !AutoCombatPools.isIdleReadingTask(curTask)) {
                    AutoCombatPools.clearMarkers(maid);
                    // v1.1.0 实测一百四十九（参考 tlm_beyond_space restoreAfterExternalTaskChange）：
                    // 任务被外部接管 → 尊重新任务不动它，但 home/作息还原到战斗前
                    //（"切回之前的模式"兜底，不再只有"清标记"半途而废）
                    AutoCombatPools.restorePrevMode(maid);
                    // v1.1.0 实测九十四：运行日志
                    com.maidsmart.tool.PromaidLog.log("战斗",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 战斗中任务被接管（玩家/排班/LLM），清标记退出");
                    continue;
                }
                long now = level.getGameTime();
                // v1.1.0 实测一百四十八【主动战斗再也不触发根治】：当前战斗任务已无
                // 可用武器（玩家把模组武器拿走等）——武器没了打不死怪，威胁永不消失、
                // 还原等待永久卡住 = 女仆永远"战斗中"，之后的参战触发全被 COMBAT_ACTIVE
                // 分支跳过 = 主动战斗再也不触发。检测到武器不可用 → 强制走还原
                // （跳过威胁刷新与安全时长等待），还原后她有武器时再正常参战。
                boolean weaponless = curTask != null
                        && curTask instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask
                        && !AutoCombatPools.hasWeaponForTask(maid, curTask);
                // v1.1.0 实测一百六十二【硬性兜底】：战斗会话超过 90 秒仍未还原——
                // 无论威胁是否仍在、安全计时是否被续杯、僵局阀是否失效，都强制切回。
                // 保证任何门都卡不死女仆（反馈："怎么都没法还原"）。
                long combatStart = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getLong(COMBAT_START_TAG);
                boolean hardDeadline = combatStart > 0 && now - combatStart > COMBAT_HARD_DEADLINE_TICKS;
                // v1.1.0 实测一百六十三【残留标记自愈】：老版本（无 COMBAT_START 时间戳）
                // 留下的 COMBAT_ACTIVE=true + 当前任务已不是攻击任务 = 残留 → 强制还原
                //（排班被它挡死、参战被它吞掉的双重根因，见 isReallyCombatActive）
                boolean staleMarker = combatStart <= 0
                        && !(curTask instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask);
                boolean forceRestore = weaponless || hardDeadline || staleMarker;
                boolean threatNearby = forceRestore ? false : AutoCombatTargeting.hasThreatNearby(maid);
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
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putLong(LAST_THREAT_TAG, 0L);
                }
                // v1.1.0 实测八十四：僵局逃逸阀——威胁仍在还原半径内，但双方超过
                // N 秒没有任何伤害往来（怪卡墙后/玻璃后/传送门里/飞行够不着等
                // "杀不掉也够不着"的死局），不再无限续杯安全计时 → 正常走还原。
                // 被动生物（动物）本就不算威胁（判定只认 Enemy 接口），与本次无关；
                // 该阀门专治"敌对生物永久滞留半径内"的卡死。
                if (threatNearby) {
                    int staleSec = MaidSmartConfig.COMBAT_AUTO_SWITCH_STALE.get();
                    long lastContact = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getLong(LAST_CONTACT_TAG);
                    if (staleSec > 0 && now - lastContact >= staleSec * 20L) {
                        threatNearby = false;
                        Long lastLog = STALE_LOG.get(maid.getUUID());
                        if (lastLog == null || now - lastLog >= 600L) {
                            STALE_LOG.put(maid.getUUID(), now);
                            // v1.1.0 实测九十四：运行日志（替代原 latest.log 直写）
                            com.maidsmart.tool.PromaidLog.log("战斗",
                                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 僵局逃逸阀触发：威胁仍在 "
                                            + MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get()
                                            + " 格内但已 " + ((now - lastContact) / 20) + " 秒无伤害往来 → 强制走还原");
                        }
                    }
                }
                if (threatNearby) {
                    ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putLong(LAST_THREAT_TAG, now);
                    // v1.1.0 实测五十七：威胁仍在 → 每秒评估一次近/远程是否该互换
                    AutoCombatPools.retuneCombatTactics(maid);
                    continue;
                }
                // v1.1.0 实测一百一十五【距离切换武器落实】：威胁圈（8 格）外但仍在
                // 近战够不着、远程够得着的范围（6~16 格）——旧版直接走还原退出战斗：
                // 远处敌人女仆不切弓而是直接退出（"不会根据距离远近切换武器"的设计
                // 在 8~16 格出现空洞）。现在远处敌人先尝试按距离切远程：切成功
                // （近战→远程）或已是远程 → 维持战斗继续射；切不动（背包没有远程
                // 武器）→ 落回正常还原（10 秒安全期后退出）。
                double farDist = AutoCombatTargeting.nearestThreatDist(maid);
                // v1.1.0 实测一百四十八：武器已被拿走时跳过"远处切远程"分支——
                // 该分支切成功会续杯 LAST_THREAT 并 continue（继续战斗），与上面的
                // 强制还原冲突（武器没了还留在战斗里 = 卡死）
                if (!forceRestore && farDist > AutoCombatPools.JUMP_UNREACHABLE_DIST && farDist <= AutoCombatPools.TARGETING_RANGE) {
                    String beforeTask = maid.getTask() != null ? maid.getTask().getUid().toString() : "";
                    AutoCombatPools.retuneCombatTactics(maid);
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
                        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putLong(LAST_THREAT_TAG, now);
                        continue;
                    }
                    // 无远程手段 → 落回正常还原（安全期后退出）
                }
                if (!restoreOn && !forceRestore) {
                    continue; // 自动还原关：只换战术不还原（强制还原时例外——照常还原）
                }
                long lastThreat = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getLong(LAST_THREAT_TAG);
                if (now - lastThreat < MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_DELAY.get()) {
                    continue; // 安全时长还不够
                }
                // 还原。战斗期间排班表可能已跨段——排班在主动战斗之上，还原时先清
                // 排班去抖键并立即重应用当前段；没排班/重应用没换成 → 落回"战斗前任务"
                // v1.1.0 实测三十九修复（反馈："消除威胁后无法转回原任务，女仆停在
                // 切换的模式"）：旧版【先 clearMarkers 再还原】——还原链路任何一环
                // 失败（findTask 找不到原任务/排班 applyNow 抛异常/任务 UID 非法），
                // 标记已被清掉：下次触发时 isCombatTask 把她当"玩家手动安排"跳过、
                // 还原扫描因无 PREV_TASK_TAG 也跳过 → 永久卡在战斗任务。
                // 修复：先解析原任务（解析失败保留标记下轮重试 + 记日志），全部
                // 就绪才清标记执行还原；全程加日志（latest.log 搜 "auto-combat restore"）。
                String prevUid = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(PREV_TASK_TAG);
                String assignedUid = ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(ASSIGNED_TAG);
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
                            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putString(
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
                                    ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "idle"))
                                    .orElse(null);
                            if (idleTask != null) {
                                com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                                        maid.getUUID(), idleTask.getUid(), () -> maid.setTask(idleTask));
                                fallbackDone = maid.getTask() != null
                                        && maid.getTask().getUid() != null
                                        && idleTask.getUid().equals(maid.getTask().getUid());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (fallbackDone) {
                        AutoCombatPools.clearMarkers(maid);
                        // v1.1.0 实测一百四十九：兜底还原同样恢复 home/作息（排班关闭时）
                        AutoCombatPools.restorePrevMode(maid);
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
                // v1.2.0：飞行作战【绝不被还原链切走】——它是玩家手动指定的模式
                // （需三件齐备）。若女仆曾被自动参战过（残留 COMBAT_ACTIVE 标记）后又
                // 被玩家切到飞行作战，还原链会因 isCombatTask(flight)=true 把她当成
                // "本系统的战斗任务"还原回战斗前任务 = 顶掉玩家选择。这里按
                // "玩家已接管"处理：不动她的任务，只清我们的战斗簿记（走下面
                // !stillOnCombat 分支：clearMarkers + restorePrevMode）。
                if (stillOnCombat && maid.getTask() != null
                        && com.maidsmart.combat.MaidFlightKit.isFlightUid(maid.getTask().getUid())) {
                    stillOnCombat = false;
                    com.maidsmart.tool.PromaidLog.log("战斗", com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 当前为飞行作战（玩家手动指定），还原链不动任务，仅清战斗标记");
                }
                if (stillOnCombat
                        && com.maidsmart.schedule.ScheduleData.isOn(maid)
                        && !com.maidsmart.schedule.ScheduleData.load(maid).isEmpty()) {
                    try {
                        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putString(
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
                            maid.getUUID(), restoreTask.getUid(), () -> maid.setTask(restoreTask));
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
                    AutoCombatPools.clearMarkers(maid);
                    // v1.1.0 实测一百四十九（参考 tlm_beyond_space TaskSwitchService.restore）：
                    // 还原 home 模式与作息（排班关闭时）——"切回之前的模式"完整闭环；
                    // 排班开启时作息由日程表管理（调度器每秒重断言），此处不覆盖
                    AutoCombatPools.restorePrevMode(maid);
                    // v1.1.0 实测六十一：还原宽限——还原后先让她干战斗前的原任务一段时间，
                    // 排班调度宽限期满后再接管当前段（防威胁闪烁导致战斗/还原/排班反复拉扯）。
                    // 宽限期写在女仆 persistentData（ScheduleData.GRACE_TAG），ScheduleManager.applyNow 入口检查
                    int grace = MaidSmartConfig.MISC_SCHEDULE_RESTORE_GRACE.get();
                    if (grace > 0) {
                        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putLong(com.maidsmart.schedule.ScheduleData.GRACE_TAG,
                                level.getGameTime() + grace);
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
        // v1.1.0 实测一百七十二：心跳诊断（每 10 秒一条，latest.log 搜 "auto-combat scan"）
        // ——确认还原扫描 onServerTick 在跑：active=战斗态女仆数（扫描看到几只）、
        // schedCleared=被排班门清理的（这些不还原是排班优先设计的正常行为）
        long hbTick = event.getServer().overworld().getGameTime();
        if (hbTick - this.lastScanHeartbeat >= 200L) {
            this.lastScanHeartbeat = hbTick;
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat scan: running tick={} active={} schedCleared={}",
                    hbTick, activeCount, schedCleared);
        }
    }

    /** v1.1.0 实测三十八：任务武器类型分类——true=远程，false=近战 —— 实现见 AutoCombatPools.isRangedTask（v1.2.4 拆分）。 */
    public static boolean isRangedTask(IMaidTask task) { return AutoCombatPools.isRangedTask(task); }

    /** 内置的近远分类规则（分类表没写这个任务时用）——原 isRangedTask 的主体，一字未改 —— 实现见 AutoCombatPools.defaultRangedByUid（v1.2.4 拆分）。 */
    public static boolean defaultRangedByUid(IMaidTask task) { return AutoCombatPools.defaultRangedByUid(task); }

    /** 该女仆当前处于本系统主动切换的战斗状态（排班调度器让位用——战斗还原后排班接管） —— 实现见 AutoCombatPools.isAutoCombatActive（v1.2.4 拆分）。 */
    public static boolean isAutoCombatActive(EntityMaid maid) { return AutoCombatPools.isAutoCombatActive(maid); }

    /** v1.1.0 实测三百八十：当前任务是否为【本系统自动指派】（ASSIGNED_TAG 与当前任务一致） —— 实现见 AutoCombatPools.isTaskAutoAssigned（v1.2.4 拆分）。 */
    public static boolean isTaskAutoAssigned(EntityMaid maid) { return AutoCombatPools.isTaskAutoAssigned(maid); }

    /** v1.1.0 实测一百六十三：是否【真实】在战斗中——标记 + 当前任务确实是攻击任务（或仍在本系统指派的战斗任务上） —— 实现见 AutoCombatPools.isReallyCombatActive（v1.2.4 拆分）。 */
    public static boolean isReallyCombatActive(EntityMaid maid) { return AutoCombatPools.isReallyCombatActive(maid); }

    /** v1.1.0 实测二百六十五/二百六十六：供外部系统（批量应用等）处理战斗标记——返回 true = 【真本系统战斗】（ASSIGNED 匹配当前任务，应跳过— —— 实现见 AutoCombatPools.isRealCombatActive（v1.2.4 拆分）。 */
    public static boolean isRealCombatActive(EntityMaid maid) { return AutoCombatPools.isRealCombatActive(maid); }

    /** v1.1.0 实测二百六十五：供外部系统（批量应用等）清理【残留】战斗标记——调用方已确认标记残留（当前任务非攻击任务），直接清全部标记 + 还原 home/作 —— 实现见 AutoCombatPools.clearMarkersForExternal（v1.2.4 拆分）。 */
    public static void clearMarkersForExternal(EntityMaid maid) { AutoCombatPools.clearMarkersForExternal(maid); }

}
