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
 * 主动参战——威胁判定与接战（v1.2.4 从 AutoCombatSwitch 拆出）。
 * 
 * 谁算威胁（打我 / 打我的女仆 / 愤怒的中立生物 / 驯服生物的归属）、
 * 接触时间戳、被袭击女仆的接战，以及威胁详情文案。
 * 全部是 static 判定函数，原类保留同签名转发。
 */
public final class AutoCombatTargeting {
    private AutoCombatTargeting() {
    }

    static void tryEngagePublic(EntityMaid maid) {
        try {
            if (!MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
                return;
            }
            tryEngageMaid(maid);
        } catch (Throwable ignored) {
        }
    }

    static boolean isTargetingOurSide(net.minecraft.world.entity.Entity e,
                                       net.minecraft.world.entity.LivingEntity owner,
                                       net.minecraft.world.entity.LivingEntity maid) {
        try {
            if (!(e instanceof net.minecraft.world.entity.Mob mob) || !mob.m_6084_()) {
                return false;
            }
            // 女仆之间不打（同主人姐妹/其他玩家的女仆都不是威胁）
            if (mob instanceof EntityMaid) {
                return false;
            }
            net.minecraft.world.entity.LivingEntity t = mob.m_5448_(); // getTarget
            return t == maid || (owner != null && t == owner);
        } catch (Throwable ex) {
            return false;
        }
    }

    static boolean isTargetingAnyoneOf(net.minecraft.world.entity.Entity e,
                                                net.minecraft.world.entity.LivingEntity owner) {
        try {
            if (!(e instanceof net.minecraft.world.entity.Mob mob) || !mob.m_6084_()) {
                return false;
            }
            if (mob instanceof EntityMaid) {
                return false; // 女仆之间不打
            }
            net.minecraft.world.entity.LivingEntity t = mob.m_5448_(); // getTarget
            if (t == null) {
                return false;
            }
            if (t == owner) {
                return true;
            }
            // 锁定的是主人的任意女仆
            return t instanceof EntityMaid tm && tm.m_269323_() == owner;
        } catch (Throwable ex) {
            return false;
        }
    }

    static void touchContactPublic(EntityMaid maid) {
        touchContact(maid);
    }

    static void touchContact(EntityMaid maid) {
        try {
            maid.getPersistentData().m_128356_(AutoCombatSwitch.LAST_CONTACT_TAG, maid.m_9236_().m_46467_());
        } catch (Exception ignored) {
        }
    }

    static void touchContactFromSource(EntityMaid maid,
                                               net.minecraft.world.damagesource.DamageSource source) {
        touchContact(maid);
        try {
            net.minecraft.world.entity.Entity attacker = source != null ? source.m_7640_() : null;
            if (!(attacker instanceof net.minecraft.world.entity.monster.Enemy)
                    && !isAngryNeutralAt(attacker, maid)
                    && !isAngryTamedAt(attacker, maid.m_269323_())
                    && !isTargetingOurSide(attacker, maid.m_269323_(), maid)) {
                attacker = source != null ? source.m_7639_() : null;
            }
            // v1.1.0 实测八十七：登记口径 = Enemy 或 记仇女仆的中立生物
            // v1.1.0 实测三百一十八：驯服宠物记仇主人也算（狼记仇主人、咬女仆）
            // v1.1.0 实测三百四十六：锁定主人/女仆的任意生物也算圈来源（普适行为化口径）
            if (attacker instanceof net.minecraft.world.entity.monster.Enemy
                    || isAngryNeutralAt(attacker, maid)
                    || isAngryTamedAt(attacker, maid.m_269323_())
                    || isTargetingOurSide(attacker, maid.m_269323_(), maid)) {
                maid.getPersistentData().m_128359_(AutoCombatSwitch.ATTACKER_UUID_TAG, attacker.m_20148_().toString());
                maid.getPersistentData().m_128356_(AutoCombatSwitch.ATTACKER_TIME_TAG, maid.m_9236_().m_46467_());
            }
        } catch (Exception ignored) {
        }
    }

    static boolean maidVictimOfMonster(Entity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (!(victim instanceof EntityMaid)) {
            return false;
        }
        if (!com.maidsmart.tool.MaidScope.owned(victim)) {
            return false; // v1.2.2 实测六百：无主女仆不参战（整合包自己的仇恨体系接管）
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
        // v1.1.0 实测三百一十八：驯服宠物记仇【主人】也算——狼记仇的是主人不是
        // 女仆，isAngryAt(女仆) 恒 false → 女仆被发狂驯服狼咬不参战（反馈："狼打我
        // 女仆一点反应都没"）。记仇主人的驯服宠物咬女仆 = 真实威胁，帮打合理。
        // v1.1.0 实测三百四十四【行为化兜底】：魔改生物不实现 NeutralMob/Tamable
        // 接口（类型判定恒 false），但攻击前必写 getTarget——正在锁定【受害女仆
        // 或其主人】的任意 Mob 都算真实威胁（与 isTargetingOurSide 同口径）。
        // 野狼咬女仆时 getTarget 可能仍指向主人（记仇主人但顺手咬近身的女仆），
        // 两侧都查。
        EntityMaid vm = (EntityMaid) victim;
        if (isTargetingOurSide(cause, vm.m_269323_(), vm)
                || isTargetingOurSide(direct, vm.m_269323_(), vm)) {
            return true;
        }
        return isAngryNeutralAt(cause, victim) || isAngryNeutralAt(direct, victim)
                || isAngryTamedAt(cause, victim) || isAngryTamedAt(direct, victim);
    }

    static boolean isAngryNeutralAt(net.minecraft.world.entity.Entity e,
                                            net.minecraft.world.entity.Entity target) {
        try {
            return e instanceof net.minecraft.world.entity.NeutralMob nm
                    && nm.m_21674_((net.minecraft.world.entity.LivingEntity) target);
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean isAngryTamedAt(net.minecraft.world.entity.Entity e,
                                          net.minecraft.world.entity.Entity target) {
        try {
            // m_21674_ 是 NeutralMob 接口方法（TamableAnimal 类没有）——驯服狼
            // 同时实现 NeutralMob + TamableAnimal，两个 instanceof 都成立
            return e instanceof net.minecraft.world.entity.TamableAnimal t
                    && t.m_21824_()
                    && e instanceof net.minecraft.world.entity.NeutralMob nm
                    && nm.m_21674_((net.minecraft.world.entity.LivingEntity) target);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean neutralAngry(net.minecraft.world.entity.NeutralMob nm) {
        try {
            return nm.m_21660_();
        } catch (Exception e) {
            return false;
        }
    }

    static int tryEngageMaid(EntityMaid maid) {
        if (!maid.m_6084_() || maid.m_6162_()) {
            return 0; // 死亡/幼年不参战
        }
        // v1.2.0：飞行作战（近战/远战）下【自主战斗不介入】——模式自己负责索敌/攻击/换装，
        // 自主战斗不得把她切走、不得换战术、不得还原（返回 2 = "已是战斗任务，跳过"）
        if (com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            return 2;
        }
        // v1.2.2 实测五百九十：傀儡模式（第三方玩法）下【自主作战不介入】——不切走、
        // 不换战术、不还原（返回 2 = 「已是战斗任务，跳过」；她由玩家安排着玩那个玩法）
        if (com.maidsmart.compat.MaidModeCompat.isPuppetMode(maid)) {
            return 2;
        }
        // v1.1.0 实测一百六十三（反馈："退而求其次——让排班拥有更高的优先级。排班
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
        if (maid.getPersistentData().m_128471_(AutoCombatSwitch.COMBAT_ACTIVE_TAG)) {
            IMaidTask curTask = maid.getTask();
            // v1.1.0 实测一百六十二【主动攻击被吞根治】：旧版把 idle 读数（战斗早已
            // 结束、任务已回落 idle）也当"已在战斗"直接 return 0——老女仆身上残留的
            // COMBAT_ACTIVE 永远清不掉，参战被永久吞掉（新女仆没残留所以正常触发）。
            // 现在只有【真实还在战斗任务】（指派或任意攻击任务）才吞；idle 读数
            // = 残留标记 → 清掉后继续走参战评估；被外部换走的任务同样清标记后重评。
            if (AutoCombatPools.isAssignedOrCombatTask(maid, curTask)) {
                // v1.1.0 实测八十四b：续杯安全计时只在【真实存在敌对威胁】时进行
                if (hasThreatNearby(maid)) {
                    maid.getPersistentData().m_128356_(AutoCombatSwitch.LAST_THREAT_TAG, maid.m_9236_().m_46467_());
                }
                return 0; // 真在战斗：只续威胁计时，不重复参战
            }
            // v1.1.0 实测一百七十一【参战重选抖动根治】：刚参战 3 秒内读到 idle =
            // DATA_TASK 同步抖动（setTask 还没同步到 getTask），不是真残留——不清
            // 标记、不重选。否则每次触发都走"自愈+重选"，随机数落回原版武器（日志
            // 实证 00:59:19 先选 true_power_of_maid:slashblade_attack、10 毫秒后重选
            // 成原版 attack = "不会拿出拔刀剑"的直接原因）。
            long combatStart = maid.getPersistentData().m_128454_(AutoCombatSwitch.COMBAT_START_TAG);
            long nowT = maid.m_9236_().m_46467_();
            if (AutoCombatPools.isIdleReadingTask(curTask) && combatStart > 0 && nowT - combatStart < 60L) {
                if (hasThreatNearby(maid)) {
                    maid.getPersistentData().m_128356_(AutoCombatSwitch.LAST_THREAT_TAG, nowT);
                }
                return 0; // 刚参战，抖动读数——保持当前切换，不重选
            }
            AutoCombatPools.clearMarkers(maid);
            // v1.1.0 实测一百四十九：任务被外部接管 → 尊重新任务不动它，但 home/作息还原
            AutoCombatPools.restorePrevMode(maid);
            if (AutoCombatPools.isIdleReadingTask(curTask)) {
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
            if (AutoCombatPools.hasWeaponForTask(maid, maid.getTask())) {
                return 2;
            }
            com.maidsmart.tool.PromaidLog.log("战斗",
                    com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " 当前战斗任务 " + maid.getTask().getUid()
                            + " 无可用武器（模组武器被拿走？），重新选择参战任务");
        }
        IMaidTask combat = AutoCombatPools.pickCombatTask(maid);
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
        String prevUid = AutoCombatPools.resolvePrevTaskUid(maid);
        maid.getPersistentData().m_128359_(AutoCombatSwitch.PREV_TASK_TAG, prevUid);
        maid.getPersistentData().m_128359_(AutoCombatSwitch.ASSIGNED_TAG, combat.getUid().toString());
        maid.getPersistentData().m_128356_(AutoCombatSwitch.LAST_THREAT_TAG, maid.m_9236_().m_46467_());
        maid.getPersistentData().m_128379_(AutoCombatSwitch.COMBAT_ACTIVE_TAG, true);
        // v1.1.0 实测一百六十二：记录战斗开始时间（硬性超时还原兜底）
        maid.getPersistentData().m_128356_(AutoCombatSwitch.COMBAT_START_TAG, maid.m_9236_().m_46467_());
        // v1.1.0 实测一百四十九（参考 tlm_beyond_space RegularRescueSupport）：参战瞬间
        // 快照 home 模式与作息——还原时一并恢复（"切回之前的模式"的完整状态闭环）
        maid.getPersistentData().m_128379_(AutoCombatSwitch.COMBAT_PREV_HOME_TAG, maid.isHomeModeEnable());
        try {
            maid.getPersistentData().m_128359_(AutoCombatSwitch.COMBAT_PREV_SCHEDULE_TAG,
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

    static boolean hasThreatNearby(EntityMaid maid) {
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
            // v1.1.0 实测三百一十八：驯服宠物记仇主人也算威胁（发狂的驯服狼在附近
            // 不还原——否则还原后立刻被咬又拉回，反复横跳）
            if (isAngryTamedAt(e, maid.m_269323_())) {
                return true;
            }
            // v1.1.0 实测三百四十四【行为化口径】：正在锁定主人/本女仆的任意 Mob
            // 都是现役威胁（魔改生物不实现 NeutralMob/Tamable——类型判定漏掉的
            // 全靠 getTarget 兜住；发狂的狼 getTarget==主人）。否则还原后立刻又被
            // 咬回战斗态，反复横跳。
            if (isTargetingOurSide(e, maid.m_269323_(), maid)) {
                return true;
            }
        }
        // ---- 动态威胁圈 ----
        int sec = MaidSmartConfig.COMBAT_AUTO_SWITCH_EXPAND.get();
        if (sec <= 0) {
            return false; // 关闭：只用固定半径
        }
        try {
            long now = maid.m_9236_().m_46467_();
            long marked = maid.getPersistentData().m_128454_(AutoCombatSwitch.ATTACKER_TIME_TAG);
            if (now - marked > sec * 20L) {
                return false; // 扩展窗口已过
            }
            String uuidStr = maid.getPersistentData().m_128461_(AutoCombatSwitch.ATTACKER_UUID_TAG);
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
            // v1.1.0 实测三百一十八：驯服宠物记仇主人也算圈来源（同固定圈口径）
            // v1.1.0 实测三百四十六：锁定主人/女仆的任意生物也算（普适行为化口径）
            boolean ringSource = attacker instanceof net.minecraft.world.entity.monster.Enemy
                    || (attacker instanceof net.minecraft.world.entity.NeutralMob nm
                    && neutralAngry(nm))
                    || isAngryTamedAt(attacker, maid.m_269323_())
                    || isTargetingOurSide(attacker, maid.m_269323_(), maid);
            if (!ringSource) {
                return false;
            }
            // 硬上限 32 格：防跨基地区域的荒谬放大
            return maid.m_20238_(attacker.m_20182_()) <= 32.0 * 32.0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static double nearestThreatDist(EntityMaid maid) {
        try {
            double best = -1;
            // v1.1.0 实测六十八：Monster -> Enemy（同 hasThreatNearby 口径）
            // v1.1.0 实测三百四十四：行为化口径并入——锁定主人/本女仆的魔改生物
            // 也算敌人（换战术距离判定要追得上/够得着它们）
            for (net.minecraft.world.entity.Entity e : maid.m_9236_().m_45976_(
                    net.minecraft.world.entity.Entity.class,
                    maid.m_20191_().m_82400_(24.0))) {
                if (!e.m_6084_()) {
                    continue;
                }
                boolean threat = e instanceof net.minecraft.world.entity.monster.Enemy
                        || isTargetingOurSide(e, maid.m_269323_(), maid);
                if (!threat) {
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

    static String threatDetail(EntityMaid maid) {
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
                // v1.1.0 实测三百一十八：驯服宠物记仇主人（发狂驯服狼）诊断口径
                if (isAngryTamedAt(e, maid.m_269323_())) {
                    return "fixed-tamed:" + String.format("%.1f",
                            Math.sqrt(maid.m_20238_(e.m_20182_())));
                }
                // v1.1.0 实测三百四十四：行为化威胁（锁定主人/女仆的魔改生物）
                if (isTargetingOurSide(e, maid.m_269323_(), maid)) {
                    return "fixed-targeting:" + String.format("%.1f",
                            Math.sqrt(maid.m_20238_(e.m_20182_())));
                }
            }
            int sec = MaidSmartConfig.COMBAT_AUTO_SWITCH_EXPAND.get();
            if (sec > 0) {
                long nowT = maid.m_9236_().m_46467_();
                long marked = maid.getPersistentData().m_128454_(AutoCombatSwitch.ATTACKER_TIME_TAG);
                if (nowT - marked <= sec * 20L) {
                    String uuidStr = maid.getPersistentData().m_128461_(AutoCombatSwitch.ATTACKER_UUID_TAG);
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
}
