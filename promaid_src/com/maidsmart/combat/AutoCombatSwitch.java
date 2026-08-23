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
 * 自伤除外）或主人攻击了别的生物（主动开火也算开战）→ 响应半径内所有自己的
 * 女仆（非自保、非战斗任务、非幼年）立即切换为战斗任务——无论她当前在干什么
 * （挖矿/伐木/烹饪/建造/跟随…）。
 *
 * 选模式（v1.1.0 实测二十重构）：
 * - 扫描 TaskManager 全部实现 IAttackTask 的攻击类任务（含模组任务：
 *   万法皆通法术/史诗战斗/真正的力量/TLM 枪械等）
 * - 按任务自己的 isWeapon 匹配背包武器过滤出候选
 * - 候选池加权随机：模组任务权重 2.0、原版五件套（近战/弓/弩/三叉戟/弹幕）
 *   权重 1.0（模组武器普遍更强，降半权但不绝对排除）
 * - 全都匹配不上 → 近战（空手也上）
 *
 * 还原：威胁（周围敌对生物，独立小半径）消失持续 N tick（默认 400 = 20 秒）→ 切回
 * 战斗前原任务；有排班表的女仆还原时直接交给排班当前段（排班在主动战斗之上）。
 * 玩家中途接管：战斗期间任务被玩家/排班/LLM 换过 → 还原只清标记退出，
 * 绝不把玩家安排的任务翻回去（还原前先校验"仍在指派的战斗任务上"）。
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
        if (event.getSource() == null || event.getSource().m_7639_() == player) {
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
        if (event.getSource() == null || event.getSource().m_7639_() == player) {
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
        if (event.getSource() == null || event.getSource().m_7639_() == player) {
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
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** v1.1.0 实测三十六：主人开火的 LivingDamageEvent 兜底（同 onOwnerAttack 的
     *  来源侧判定，事件换结算层）。 */
    @SubscribeEvent
    public void onOwnerAttackDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (!(event.getSource() != null && event.getSource().m_7639_() instanceof ServerPlayer attacker)) {
            return;
        }
        if (event.getEntity() instanceof EntityMaid m && m.m_269323_() == attacker) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** 响应半径内自己的女仆全体评估参战（被攻击/主动开火共用）
     *  v1.1.0 实测二十八：加限频诊断日志（latest.log 搜 "auto-combat"）——
     *  此前整个链路零日志，"没生效"无从排查；现在记录触发源/扫描结果/切换结果 */
    private void switchNearbyMaids(ServerPlayer player) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
        int switched = 0;
        int skippedCombat = 0;
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class,
                player.m_20191_().m_82400_(r))) {
            if (!maid.m_6084_() || maid.m_6162_()) {
                continue; // 死亡/幼年不参战
            }
            if (maid.m_269323_() != player) {
                continue; // 只响应主人自己的女仆
            }
            // 自保中让位（自保优先，血量恢复后自然退出再正常参与）
            if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
                continue;
            }
            // 已被本系统切过：还在指派的战斗任务上 → 只刷新威胁计时；任务已被
            // 玩家/排班/LLM 换走 → 玩家接管，清标记后按"当前任务"重新评估参战
            // v1.1.0 终审修复落地（实测十六）：判定必须走 getBoolean（m_128471_）——
            // 此前代码用 contains（m_128441_），而 clearMarkers 是 putBoolean(false)
            // 不删键 → 打过一仗后 contains 永远 true：排班调度器对她永久让位
            // （排班再也不生效）+ 还原扫描每秒对每只退役女仆做 3 次无效 NBT 写
            if (maid.getPersistentData().m_128471_(PREV_TASK_TAG)) {
                if (isOnAssignedCombatTask(maid)) {
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
                    continue;
                }
                clearMarkers(maid); // 接管退出——不还原、不再背着旧标记
            }
            // 已是攻击类任务（IAttackTask：玩家手动安排的近战/弓/弹幕，或万法皆通/
            // 史诗战斗等第三方攻击任务）→ 她本来就能打，尊重现状不切换不记录
            if (MaidWorkTags.isCombatTask(maid)) {
                skippedCombat++;
                continue;
            }
            IMaidTask combat = pickCombatTask(maid);
            if (combat == null) {
                continue; // 单只找不到任务不连坐（此前 return 会跳过同半径的其他女仆）
            }
            String prevUid = maid.getTask() != null
                    ? maid.getTask().getUid().toString() : "touhou_little_maid:idle";
            maid.getPersistentData().m_128359_(PREV_TASK_TAG, prevUid);
            maid.getPersistentData().m_128359_(ASSIGNED_TAG, combat.getUid().toString());
            maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
            maid.setTask(combat);
            switched++;
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat: maid={} {} -> {} (owner={})",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    prevUid, combat.getUid(), player.m_5446_().getString());
        }
        // v1.1.0 实测二十八：触发但一只都没切（全部让位/已是战斗/无匹配武器）也记一笔——
        // 排查"没生效"时能区分"事件没触发"和"触发了但全被跳过"
        if (switched == 0 && skippedCombat > 0) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat: triggered by owner={} but 0 switched ({} already combat)",
                    player.m_5446_().getString(), skippedCombat);
        }
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
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()
                || !MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE.get()) {
            return;
        }
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
                    continue;
                }
                long now = level.m_46467_();
                if (hasThreatNearby(maid)) {
                    maid.getPersistentData().m_128356_(LAST_THREAT_TAG, now);
                    continue;
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
                    String failName = maid.m_5446_() != null ? maid.m_5446_().getString() : String.valueOf(maid.m_20148_());
                    com.mojang.logging.LogUtils.getLogger().info("auto-combat restore FAIL: maid={} prev task '{}' not found, keeping markers",
                            failName, prevUid);
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
                String maidName = maid.m_5446_() != null ? maid.m_5446_().getString() : String.valueOf(maid.m_20148_());
                if (restored) {
                    com.mojang.logging.LogUtils.getLogger().info("auto-combat restore: maid={} {} -> {} (threat gone {}s)",
                            maidName, assignedUid, prevUid,
                            (now - lastThreat) / 20);
                } else {
                    // 任务在还原前被换（排班/玩家接管）——标记已清，正常退出
                    String curTask = maid.getTask() == null ? "null" : maid.getTask().getUid().toString();
                    com.mojang.logging.LogUtils.getLogger().info("auto-combat restore: maid={} task changed during combat ({}), no restore",
                            maidName, curTask);
                }
            }
        }
    }

    /**
     * 女仆周围（还原威胁半径，默认 8——独立配置，不复用 16 格响应半径）是否有活的
     * 敌对生物。v1.1.0 审查：旧版复用响应半径，刷怪频繁的整合包里远处怪一直"续杯"，
     * 女仆永远等不满 20 秒安全期，卡在战斗任务回不了岗。
     */
    private static boolean hasThreatNearby(EntityMaid maid) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST.get();
        for (net.minecraft.world.entity.monster.Monster e
                : maid.m_9236_().m_45976_(net.minecraft.world.entity.monster.Monster.class,
                maid.m_20191_().m_82400_(r))) {
            if (e.m_6084_()) {
                return true;
            }
        }
        return false;
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
        // 候选收集：任务 → 权重（原版/模组各自读配置）
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
        // 两类都有 → 按最近敌人距离选池；只有一类 → 直接用
        if (!meleePool.isEmpty() && !rangedPool.isEmpty()) {
            double dist = nearestThreatDist(maid);
            // 没找到敌人（威胁消失边缘）→ 远程优先（安全输出）；近 → 近战
            boolean useMelee = dist >= 0 && dist <= MELEE_RANGE;
            com.mojang.logging.LogUtils.getLogger().info(
                    "auto-combat pick: maid={} both-pools dist={} -> {}",
                    maid.m_5446_() != null ? maid.m_5446_().getString() : maid.m_20148_(),
                    String.format("%.1f", dist), useMelee ? "melee" : "ranged");
            return useMelee
                    ? weightedPick(meleePool, meleeWeights)
                    : weightedPick(rangedPool, rangedWeights);
        }
        if (!meleePool.isEmpty()) {
            return weightedPick(meleePool, meleeWeights);
        }
        if (!rangedPool.isEmpty()) {
            return weightedPick(rangedPool, rangedWeights);
        }
        // 全都匹配不上 → 近战兜底（空手也上）
        return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);
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
            for (net.minecraft.world.entity.monster.Monster e : maid.m_9236_().m_45976_(
                    net.minecraft.world.entity.monster.Monster.class,
                    maid.m_20191_().m_82400_(24.0))) {
                if (!e.m_6084_()) {
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
        // 1. 任务自己的判定（IAttackTask.isWeapon）
        try {
            ItemStack main = maid.m_21205_();
            if (!main.m_41619_() && task.isWeapon(maid, main)) {
                return true;
            }
            IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && task.isWeapon(maid, s)) {
                    return true;
                }
            }
            return true; // 任务对所有物品都返回 false = 不限武器（如部分模组任务）→ 视为可参战
        } catch (Throwable ignored) {
        }
        return true; // 判定异常时保守放行（别让模组任务因此选不上）
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

    /** 清全部标记（putBoolean false 不删键——判定一律走 getBoolean，contains 会永远为 true） */
    private static void clearMarkers(EntityMaid maid) {
        maid.getPersistentData().m_128379_(PREV_TASK_TAG, false);
        maid.getPersistentData().m_128379_(LAST_THREAT_TAG, false);
        maid.getPersistentData().m_128379_(ASSIGNED_TAG, false);
    }

    /** 女仆仍在本系统指派的战斗任务上（任务被换过 = 玩家/排班/LLM 已接管） */
    private static boolean isOnAssignedCombatTask(EntityMaid maid) {
        String assigned = maid.getPersistentData().m_128461_(ASSIGNED_TAG);
        return maid.getTask() != null && !assigned.isEmpty()
                && assigned.equals(maid.getTask().getUid().toString());
    }
}
