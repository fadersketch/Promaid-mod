package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * v1.1.0 实测三百四十四（用户："遇到发狂的狼，我打狼或是狼打我，女仆一点反应
 * 都没" + "有一部分 mod 会魔改原版被动生物，让被动生物都会变成像狼这样的中立
 * 生物，所以我觉得这方面的判定需要加强"）：
 *
 * 中立/魔改生物威胁驱动——TLM 的索敌链对这类生物是【结构性盲区】（反编译实证）：
 * - MaidHostilesSensor：ACCEPTABLE_DISTANCE_FROM_HOSTILES 白名单【只有苦力怕】
 *   一个条目——isHostile() 只查白名单，发狂的狼永远不会被写进 NEAREST_HOSTILE
 * - TaskAttack.createBrainTasks：用原版 StartAttacking.create(canAttack, targetSelector)
 *   ——targetSelector 是 getNearestAttackableTarget：只认 Monster 或
 *   NeutralMob.isAngryAt(女仆)。发狂的狼记仇的是【主人】不是女仆 → isAngryAt(女仆)
 *   恒 false → 切了战斗任务也【选不到目标】→ 女仆站着（"一点反应都没"的直接原因）
 * - AutoCombatSwitch 实测三百一十八只补了【驯服】狼（isAngryTamedAt 要求 isTame）；
 *   野狼（未驯服）+ 模组魔改生物（不实现 NeutralMob/Tamable 接口）全部漏判
 *
 * 修复理念（用户补充的判定加强）：威胁判定全面【行为化】——看生物当前是否
 * 锁定主人/女仆为目标（Mob.getTarget()），不看类型接口（Enemy/NeutralMob/
 * TamableAnimal 全是原版类型体系，魔改被动生物不实现任何一个是常态）。
 * 狼发狂咬人的瞬间 getTarget()==主人——这是最普适、最准确的"正在构成威胁"
 * 信号，覆盖：原版野狼/驯服狼/北极熊/蜜蜂 + 任何模组魔改的"被动生物变中立"。
 *
 * 做三件事（每 10 tick = 0.5 秒一轮，节流省性能）：
 * 1. 【索敌写目标】扫 16 格内 getTarget()==主人/本女仆 的任意 Mob（不看类型）
 *    → 写进女仆 brain 的 ATTACK_TARGET（m_21886_ setMemory，与 StartAttacking
 *    同款写入）——MaidMeleeAttack 的攻击执行链反编译实证 m_217066_ 只是距离
 *    判定，不滤类型 → 写了目标就能打。战斗任务/参战女仆都受益：TLM 索敌
 *    传感器选不到的狼，这里直接喂给攻击行为。
 * 2. 【无战斗任务也能打】非战斗任务（农场/挖矿/跟随…）的女仆没有攻击行为
 *    （WORK 组行为由任务 createBrainTasks 注册），只写目标没人执行 → 直接
 *    调 m_7327_（doHurtTarget，EntityMaid 覆写含横扫/饰品事件，自保近身
 *    反击同款通道）+ 直连导航走向目标——不切任务也能自卫反击（自保 250 只管
 *    低血逃命，健康女仆被狼咬原来只能干挨）。
 * 3. 【触发参战】锁定主人/女仆的狼出现 → 触发 AutoCombatSwitch.tryEngagePublic
 *    （切战斗任务，全链路接管）——与"主人被攻击"触发互补（主人打狼时事件
 *    在狼记仇状态设置【之前】发，AutoCombatSwitch 的 isAngry 判定恒 false
 *    漏触发；这里 0.5 秒后扫到 getTarget 已就位，补上迟到的触发）。
 *
 * 与各系统的关系：
 * - 战术行为（230）读到 ATTACK_TARGET 自动接管走位/跳劈/风筝（isActive 判定
 *   只查目标存在+距离）——本驱动只负责【把目标写进去】，移动战术照常生效
 * - 排班中的女仆：跳过（tryEngageMaid 内部让位排班；写目标也不做——排班段
 *   任务可能不是攻击任务，写了目标没有攻击行为反而干扰）
 * - 自保中的女仆：索敌让位（自保优先）；自保的威胁扫描（PerceptionManager.
 *   isThreat）已含 getTarget 锁定判定，天然覆盖魔改生物
 */
public final class NeutralThreatDriver {

    private static boolean registered = false;
    private int throttle = 0;

    private NeutralThreatDriver() {
    }

    public static void ensureRegistered() {
        if (!registered) {
            registered = true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new NeutralThreatDriver());
        }
    }

    /** 索敌半径（格）——与 COMBAT_AUTO_SWITCH_RADIUS 默认 16 同口径 */
    private static final double SEARCH_RADIUS = 16.0;
    /** 直接攻击距离（格）——doHurtTarget 的实际攻击距离（自保近身反击用 2.5，
     *  这里稍宽：3.1 是女仆近战攻击距离上限） */
    private static final double STRIKE_DIST = 3.1;
    /** 攻击间隔（tick）——≈ 玩家平A 节奏（自保近身反击同款 12 tick） */
    private static final int ATTACK_COOLDOWN = 12;
    /** 走向目标速度 */
    private static final float CHASE_SPEED = 1.1f;

    /** v1.1.0 实测三百四十四：每女仆攻击冷却（maidId → 到期 tick） */
    private static final java.util.Map<java.util.UUID, Long> ATTACK_CDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 实测三百四十四：本驱动写入的目标（maidId → 目标 UUID）——还原扫描/
     *  诊断用；目标消失/威胁解除由 TLM StopAttackingIfTargetInvalid 照常清理 */
    private static final java.util.Map<java.util.UUID, java.util.UUID> ASSIGNED_TARGETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++this.throttle < 10) {
            return; // 每 10 tick = 0.5 秒一轮（行为化判定是 getTarget 字段读，O(1)）
        }
        this.throttle = 0;
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        try {
            // 有限 AABB 全图扫描（Entity.class 全量 + instanceof——ClassInstanceMultiMap
            // 桶 bug 与 ±∞ 溢出均已绕开，HomeWorkMovementDriver 同款口径）
            net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                    -131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);
            for (ServerLevel level : server.m_129785_()) {
                for (Entity e : level.m_45976_(net.minecraft.world.entity.Entity.class, whole)) {
                    if (e instanceof EntityMaid maid) {
                        try {
                            drive(level, maid);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void drive(ServerLevel level, EntityMaid maid) {
        if (!maid.m_6084_() || maid.m_6162_() || maid.isMaidInSittingPose()) {
            return; // 死亡/幼年/坐下不参战
        }
        // 排班中：不写目标、不打、不触发参战（任务全由日程表管理）
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner == null || !owner.m_6084_()) {
            return;
        }
        // 自保中：让位（自保 250 优先，威胁扫描已含行为化判定）
        if (maid.getPersistentData().m_128471_(
                com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return;
        }
        Mob threat = findThreateningMob(maid, owner);
        if (threat == null) {
            // 威胁解除：清登记（ATTACK_TARGET 的清理交给 TLM StopAttacking/
            // 传感器时效，这里只清自己的冷却登记，防 Map 膨胀）
            ATTACK_CDS.remove(maid.m_20148_());
            ASSIGNED_TARGETS.remove(maid.m_20148_());
            return;
        }
        // ① 触发参战（AutoCombatSwitch 内部自带排班/自保/幼年让位与去重）——
        // 主人打狼时狼的记仇状态在事件后才就位，这里补上迟到的触发
        com.maidsmart.combat.AutoCombatSwitch.tryEngagePublic(maid);
        // ② 索敌写目标：战斗任务的女仆写了目标就有攻击行为执行（TLM 索敌
        //    传感器选不到魔改生物，这里直接喂目标）
        boolean hasBrainTarget = maid.m_6274_()
                .m_21952_(MemoryModuleType.f_26372_).isPresent();
        if (!hasBrainTarget) {
            try {
                // ATTACK_TARGET 泛型是 LivingEntity（javap 实证 f_26372_ 类型）——
                // 直接写实体本体（StartAttacking 同款：setMemory(type, Optional.of)）
                maid.m_6274_().m_21886_(MemoryModuleType.f_26372_,
                        java.util.Optional.of(threat));
                // LOOK_TARGET 泛型是 PositionTracker——EntityTracker 是其子类
                //（让 LookAtTargetSink 能转头看向目标，与 StartAttacking 同款配套）
                maid.m_6274_().m_21886_(MemoryModuleType.f_26371_,
                        java.util.Optional.of(new EntityTracker(threat, true)));
                ASSIGNED_TARGETS.put(maid.m_20148_(), threat.m_20148_());
            } catch (Throwable ignored) {
            }
        }
        // ③ 攻击执行兜底（实测三百四十五，用户："进入了攻击模式，但是并没有进行
        //    攻击行为"）：写目标只解决"有目标"，TLM 攻击行为（MaidMeleeAttack）是
        //    WORK 组行为，还有一串 canUse 条件（任务重建时序/活动段/NEAREST_VISIBLE
        //    可见列表/攻击距离）——任一环断了女仆就"进了攻击模式站着不动"（本次
        //    实测现象）。兜底不区分任务：无论战斗任务还是农场/挖矿/跟随，只要威胁
        //    还在就自己打——走近 + 挥砍。
        // v1.1.0 实测三百四十七三处修正（用户："反击没攻击动画；空手反击太强、
        // 频率又高伤害又高；只会空手反击，原有的攻击没了"）：
        // ①【真兜底让位】TLM 近 2 秒（40 tick）内有过攻击动作（ATTACK_COOLING_DOWN
        //    记忆在 = MaidMeleeAttack 刚写入）→ 完全让位（TLM 链路健康，别抢）。
        //    旧版只查"本 tick 冷却记忆是否在"——TLM 打完冷却只持续十几 tick，
        //    下一个攻击循环的间隙里兜底就插进来，观感就是"原有的攻击没了、
        //    只剩空手连拍"。真兜底 = TLM 长时间（2 秒）没动作才接管。
        // ②【空手不打】空手反击伤害/频率失衡（用户实测"空手太强"）——没有武器
        //    的女仆只导航跟随（保持威胁在场触发参战/战术），不亲自挥刀；参战切
        //    换会自动从背包装备武器（CombatTaskCompat.prepareSwitch），装上后
        //    自然恢复挥砍。
        // ③【按攻击速度属性算冷却】冷却 = 20 / 攻击速度（原版攻击间隔公式）——
        //    旧版固定 12 tick 一刀无视属性，比拿剑的正常攻击还快。
        // ④【补挥臂动画】doHurtTarget 直调不出摆臂动画——swing 主手让玩家
        //    看到"挥了一下"，与 TLM 攻击观感一致。
        double dist = maid.m_20270_(threat);
        long now = level.m_46467_();
        // ① 真兜底让位：TLM 近 40 tick 内打过（冷却记忆在）→ 这一波完全交给 TLM
        boolean tlmAttacking = maid.m_6274_()
                .m_21952_(MemoryModuleType.f_26373_).isPresent();
        if (tlmAttacking) {
            LAST_TLM_SWING.put(maid.m_20148_(), now); // 记 TLM 动作时刻
            ATTACK_CDS.remove(maid.m_20148_());
            return; // TLM 在正常打——让位（不打也不导航，战术行为管走位）
        }
        // TLM 长时间没动作 → 兜底接管；但兜底自己刚挥过刀也走冷却
        Long lastTlm = LAST_TLM_SWING.get(maid.m_20148_());
        boolean tlmRecently = lastTlm != null && now - lastTlm < 40L;
        Long cd = ATTACK_CDS.get(maid.m_20148_());
        if (cd != null && now < cd) {
            return; // 兜底自己的攻击冷却中
        }
        // ② 空手不打：主手没有带攻击力属性的物品（剑/斧/镐等）→ 只导航跟随
        boolean armed = isArmed(maid);
        if (dist <= STRIKE_DIST) {
            if (!armed) {
                return; // 空手：不挥刀（伤害/频率失衡），保持威胁登记让参战链处理
            }
            if (tlmRecently) {
                return; // TLM 刚打过（冷却间隙）——等它下一个循环，别插刀
            }
            // ③+④ 攻击速度属性算冷却 + 挥臂动画
            boolean hit = maid.m_7327_(threat);
            maid.m_21011_(net.minecraft.world.InteractionHand.MAIN_HAND, true); // swing 摆臂
            int cdTicks = attackIntervalTicks(maid);
            ATTACK_CDS.put(maid.m_20148_(), now + Math.max(12, cdTicks));
            if (hit) {
                // 女仆自己打的也记一次战斗接触（还原扫描的僵局逃逸阀计时用）
                com.maidsmart.combat.AutoCombatSwitch.touchContactPublic(maid);
            }
        } else {
            // 远：直连导航走过去（不走 MoveToTargetSink——站桩标记拦不住；
            // 战术行为（230）激活时它自己管走位，isActive 判定只查目标+距离，
            // 有目标时它自然接管，这里只是兜底导航）
            if (!com.maidsmart.combat.MaidCombatTacticsBehavior.isActive(maid)) {
                maid.m_21573_().m_26519_(
                        threat.m_20185_(), threat.m_20186_(), threat.m_20189_(), CHASE_SPEED);
            }
        }
    }

    /** v1.1.0 实测三百四十七：TLM 最近一次攻击动作时刻（真兜底让位判定——
     *  TLM 打完后冷却记忆只持续十几 tick，记忆消失≠TLM 停了（下一个循环的
     *  攻击间隙而已）；40 tick 内有过动作就视为"TLM 链路健康" */
    private static final java.util.Map<java.util.UUID, Long> LAST_TLM_SWING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** v1.1.0 实测三百四十七：主手是否带攻击力属性（剑/斧/镐/模组武器等）——
     *  空手（没武器）不挥刀（空手伤害高频率快，实测失衡） */
    private static boolean isArmed(EntityMaid maid) {
        try {
            ItemStack main = maid.m_21205_();
            if (main.m_41619_()) {
                return false;
            }
            return main.m_41638_(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                    .containsKey(net.minecraft.world.entity.ai.attributes.Attributes.f_22281_);
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.1.0 实测三百四十七：按攻击速度属性计算攻击间隔（tick）——
     *  原版公式 20/attack_speed（剑 1.6 → 12.5 tick，斧 1.0 → 20 tick），
     *  旧版固定 12 tick 无视属性 */
    private static int attackIntervalTicks(EntityMaid maid) {
        try {
            var atkSpeed = maid.m_21051_(
                    net.minecraft.world.entity.ai.attributes.Attributes.f_22284_);
            double speed = atkSpeed != null ? atkSpeed.m_22115_() : 4.0;
            if (speed <= 0) {
                speed = 4.0;
            }
            return (int) Math.ceil(20.0 / speed);
        } catch (Throwable t) {
            return 20;
        }
    }

    /**
     * 找 16 格内【正在锁定主人或本女仆】的任意 Mob（行为化判定，不看类型）。
     * v1.1.0 实测三百四十四：这是对魔改生物判定的加强核心——getTarget 是
     * Mob 基类字段（所有敌对/中立/魔改生物 AI 都写它），狼发狂咬人的瞬间
     * target==主人；模组把牛/羊改成中立攻击性生物，攻击时同样写 target。
     * 不认 Enemy/NeutralMob/Tamable 接口 = 不依赖原版类型体系。
     */
    private static Mob findThreateningMob(EntityMaid maid, LivingEntity owner) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : maid.m_9236_().m_45976_(net.minecraft.world.entity.Mob.class,
                maid.m_20191_().m_82400_(SEARCH_RADIUS))) {
            if (!(e instanceof Mob mob) || !mob.m_6084_() || mob == maid) {
                continue;
            }
            // 自己姐妹/主人的其他女仆不算（女仆之间不打）
            if (mob instanceof EntityMaid) {
                continue;
            }
            LivingEntity t;
            try {
                t = mob.m_5448_(); // getTarget——行为化锁定的核心信号
            } catch (Throwable ex) {
                continue;
            }
            if (t != maid && t != owner) {
                continue; // 没锁定我方人员（含未锁定）→ 不算威胁
            }
            // 主人自己的驯服宠物记仇主人（发狂驯服狼）也在此列——getTarget==主人
            // 即真实威胁（TLM MaidMeleeAttack 打它没有心理负担：狼已对主人兵刃相向）
            double d = maid.m_20270_(mob);
            if (d < bestDist) {
                bestDist = d;
                best = mob;
            }
        }
        return best;
    }

    /** 实测三百四十四：查询本驱动为该女仆登记的目标（还原扫描/诊断用） */
    public static java.util.UUID assignedTargetOf(EntityMaid maid) {
        return ASSIGNED_TARGETS.get(maid.m_20148_());
    }
}