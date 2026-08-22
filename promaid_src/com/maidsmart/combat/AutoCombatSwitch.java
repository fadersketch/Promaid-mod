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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
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
 * 触发：主人（玩家）被敌对生物攻击 → 响应半径内所有自己的女仆（非自保、非战斗任务、
 * 非幼年）立即切换为战斗任务——无论她当前在干什么（挖矿/伐木/烹饪/建造/跟随…）。
 *
 * 选模式：
 * - 枪械优先（配置开）：装了 TACZ/卓越前线且背包有枪+弹药 → touhou_little_maid:gun_attack
 *   （开枪/换弹/自动搜弹药由 TLM 内置枪械任务负责；promaid 战术照常接管走位）
 * - 否则从 近战/弓/弩/三叉戟/弹幕 里按"背包有对应武器"过滤后随机选
 * - 全都没有 → 近战（空手也上）
 *
 * 还原：威胁（周围敌对生物）消失持续 N tick（默认 400 = 20 秒）→ 切回战斗前原任务。
 *
 * 优先级链（本功能在其中的位置）：自保 > 排班表 > 主动战斗（含还原）> 玩家手动/LLM。
 * 自保中的女仆不响应切换（自保优先），还原也等自保结束。
 */
public class AutoCombatSwitch {
    /** 战斗前原任务 UID（persistentData，切战斗时写入，还原时读取） */
    private static final String PREV_TASK_TAG = "maid_smart_combat_prev_task";
    /** 最近一次看到威胁的 gameTime（还原延迟计时基准） */
    private static final String LAST_THREAT_TAG = "maid_smart_combat_last_threat";
    /** 已被本系统切到战斗的女仆（还原扫描只遍历这些，非全量女仆） */
    private static final java.util.Set<java.util.UUID> SWITCHED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Random RNG = new Random();
    /** 还原扫描节流（每 20 tick = 1 秒一次） */
    private int restoreThrottle = 0;

    /** 主人被敌对生物攻击 → 附近女仆切战斗 */
    @SubscribeEvent
    public void onOwnerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            return;
        }
        // 只响应敌对生物的攻击（玩家互打/自伤/环境伤害不触发）
        if (!(event.getSource().m_7639_() instanceof Enemy)) {
            return;
        }
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
            // 已被本系统切过（战斗中再次受击只刷新威胁计时，不重复切换）
            if (maid.getPersistentData().m_128471_(PREV_TASK_TAG)) {
                maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
                continue;
            }
            // 已是战斗任务（玩家手动安排）→ 尊重玩家安排，不记录不切换
            if (MaidWorkTags.isCombatTask(maid)) {
                continue;
            }
            IMaidTask combat = pickCombatTask(maid);
            if (combat == null) {
                return;
            }
            String prevUid = maid.getTask() != null
                    ? maid.getTask().getUid().toString() : "touhou_little_maid:idle";
            maid.getPersistentData().m_128359_(PREV_TASK_TAG, prevUid);
            maid.getPersistentData().m_128356_(LAST_THREAT_TAG, maid.m_9236_().m_46467_());
            SWITCHED.add(maid.m_20148_());
            maid.setTask(combat);
        }
    }

    /** 威胁消失持续够久 → 还原原任务 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (SWITCHED.isEmpty()) {
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
                if (!maid.m_6084_() || !SWITCHED.contains(maid.m_20148_())) {
                    continue;
                }
                // 自保中不还原（等自保结束；自保退出有自己的回主人逻辑）
                if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
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
                // 还原原任务
                String prevUid = maid.getPersistentData().m_128461_(PREV_TASK_TAG);
                TaskManager.findTask(ResourceLocation.parse(prevUid)).ifPresent(maid::setTask);
                maid.getPersistentData().m_128379_(PREV_TASK_TAG, false);
                maid.getPersistentData().m_128379_(LAST_THREAT_TAG, false);
                SWITCHED.remove(maid.m_20148_());
            }
        }
        // 清理已消失女仆的残留记录（卸载/魂符收起/死亡）
        SWITCHED.removeIf(uuid -> {
            for (ServerLevel level : event.getServer().m_129785_()) {
                for (EntityMaid maid : level.m_45976_(EntityMaid.class,
                        new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY))) {
                    if (maid.m_20148_().equals(uuid) && maid.m_6084_()) {
                        return false;
                    }
                }
            }
            return true;
        });
    }

    /** 女仆周围（响应半径内）是否有活的敌对生物（Monster 覆盖常规敌对） */
    private static boolean hasThreatNearby(EntityMaid maid) {
        double r = MaidSmartConfig.COMBAT_AUTO_SWITCH_RADIUS.get();
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
     * 选战斗任务：枪械优先（配置开且有枪+弹药）→ 否则按背包武器过滤 {近战/弓/弩/三叉戟/弹幕}
     * 随机选一个 → 全无武器 → 近战（空手）。找不到任务定义返回 null（理论上不会）。
     */
    private static IMaidTask pickCombatTask(EntityMaid maid) {
        // 枪械优先
        if (MaidSmartConfig.COMBAT_AUTO_SWITCH_GUN_PREFER.get()
                && GunCompat.hasGunAndAmmo(maid)) {
            IMaidTask gun = TaskManager.findTask(
                    ResourceLocation.parse("touhou_little_maid:gun_attack")).orElse(null);
            if (gun != null) {
                return gun;
            }
        }
        // 扫描背包 + 主手有什么武器
        boolean melee = false;
        boolean bow = false;
        boolean crossbow = false;
        boolean trident = false;
        boolean gohei = false;
        ItemStack main = maid.m_21205_();
        if (!main.m_41619_()) {
            melee |= hasAttackDamage(main);
            bow |= main.m_41720_() instanceof BowItem;
            crossbow |= main.m_41720_() instanceof CrossbowItem;
            trident |= main.m_41720_() instanceof TridentItem;
            gohei |= com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(main);
        }
        IItemHandler inv = maid.getMaidInv();
        outer:
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.m_41619_()) {
                continue;
            }
            melee |= hasAttackDamage(s);
            bow |= s.m_41720_() instanceof BowItem;
            crossbow |= s.m_41720_() instanceof CrossbowItem;
            trident |= s.m_41720_() instanceof TridentItem;
            gohei |= com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(s);
            if (melee && bow && crossbow && trident && gohei) {
                break outer; // 全都有，不用再扫
            }
        }
        // 有武器的模式候选 → 随机选（用户指定的"根据背包随机选择"）
        List<String> uids = new ArrayList<>();
        if (melee) {
            uids.add("touhou_little_maid:attack");
        }
        if (bow) {
            uids.add("touhou_little_maid:ranged_attack");
        }
        if (crossbow) {
            uids.add("touhou_little_maid:crossbow_attack");
        }
        if (trident) {
            uids.add("touhou_little_maid:trident_attack");
        }
        if (gohei) {
            uids.add("touhou_little_maid:danmaku_attack");
        }
        if (uids.isEmpty()) {
            uids.add("touhou_little_maid:attack"); // 空手也上（近战）
        }
        String uid = uids.get(RNG.nextInt(uids.size()));
        return TaskManager.findTask(ResourceLocation.parse(uid)).orElse(null);
    }

    /** 是否带攻击力属性的物品（剑/斧/镐等——对齐 TaskAttack.isWeapon 语义，简化版） */
    private static boolean hasAttackDamage(ItemStack stack) {
        try {
            return stack.m_41638_(EquipmentSlot.MAINHAND).containsKey(Attributes.f_22281_);
        } catch (Exception e) {
            return false;
        }
    }

    /** 女仆实体卸载/消失时清理 SWITCHED 记录（AiMemoryManager.forgetMaid 调） */
    public static void forgetMaid(java.util.UUID uuid) {
        SWITCHED.remove(uuid);
    }

    /** 该女仆当前处于本系统主动切换的战斗状态（排班调度器让位用——战斗还原后排班接管） */
    public static boolean isAutoCombatActive(EntityMaid maid) {
        return maid.getPersistentData().m_128471_(PREV_TASK_TAG);
    }
}
