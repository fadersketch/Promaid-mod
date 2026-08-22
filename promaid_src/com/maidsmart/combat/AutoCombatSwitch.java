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
     */
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
        switchNearbyMaids(player);
    }

    /**
     * v1.1.0 实测二十：主人攻击了别的生物 → 也触发（护主不只被动挨打才算开战，
     * 主人主动开火同样进入战斗）。监听主人对【非自己女仆】造成伤害的事件。
     */
    @SubscribeEvent
    public void onOwnerAttack(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getSource().m_7639_() instanceof ServerPlayer attacker)) {
            return; // 只认玩家亲手造成的伤害（女仆打的不连锁触发）
        }
        if (attacker != player) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // 打的是自己的女仆不算开战（误伤/管教场景）
        if (event.getEntity() instanceof EntityMaid m && m.m_269323_() == attacker) {
            return;
        }
        switchNearbyMaids(attacker);
    }

    /** 响应半径内自己的女仆全体评估参战（被攻击/主动开火共用） */
    private void switchNearbyMaids(ServerPlayer player) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
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
                String prevUid = maid.getPersistentData().m_128461_(PREV_TASK_TAG);
                String assignedUid = maid.getPersistentData().m_128461_(ASSIGNED_TAG);
                clearMarkers(maid);
                boolean stillOnCombat = maid.getTask() != null
                        && maid.getTask().getUid().toString().equals(assignedUid);
                if (stillOnCombat
                        && com.maidsmart.schedule.ScheduleData.isOn(maid)
                        && !com.maidsmart.schedule.ScheduleData.load(maid).isEmpty()) {
                    maid.getPersistentData().m_128359_(
                            com.maidsmart.schedule.ScheduleData.APPLIED_TAG, "");
                    com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
                    stillOnCombat = maid.getTask() != null
                            && maid.getTask().getUid().toString().equals(assignedUid);
                }
                if (stillOnCombat) {
                    TaskManager.findTask(ResourceLocation.parse(prevUid)).ifPresent(maid::setTask);
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
     * 旧版"枪械优先"是当时只考虑 TACZ 兼容的产物（枪械肯定比原版武器强）。
     * 现在附属生态加入了大量模组（万法皆通/史诗战斗/真正的力量等），它们的
     * 攻击任务与枪械强度等价——不再有谁绝对优先。新口径：
     * - 扫描 TaskManager 全部任务，找出实现 IAttackTask 的攻击类任务
     * - 对每个任务问 maid 背包"能不能打"（task.isWeapon 按背包匹配）
     * - 能打的任务【等权重】随机选
     * - 原版五件套（attack/ranged/crossbow/trident/danmaku）权重 ×0.5——
     *   模组武器普遍更强，有模组选项时优先模组（但不是绝对排除原版）
     * - 全都匹配不上 → 近战（空手也上）
     */
    private static IMaidTask pickCombatTask(EntityMaid maid) {
        // 候选收集：任务 → 权重（原版 1.0，模组 2.0）
        List<IMaidTask> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        String vanillaNs = "touhou_little_maid";
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
            double w = vanillaNs.equals(task.getUid().m_135827_()) ? 1.0 : 2.0; // 原版降半权
            pool.add(task);
            weights.add(w);
        }
        if (pool.isEmpty()) {
            // 全都匹配不上 → 近战兜底（空手也上）
            return TaskManager.findTask(ResourceLocation.parse("touhou_little_maid:attack")).orElse(null);
        }
        // 加权随机
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
