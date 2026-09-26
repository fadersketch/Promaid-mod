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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * v1.1.0 实测三百四十四（反馈："遇到发狂的狼，我打狼或是狼打我，女仆一点反应
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
 * 修复理念（玩家补充的判定加强）：威胁判定全面【行为化】——看生物当前是否
 * 锁定主人/女仆为目标（Mob.getTarget()），不看类型接口（Enemy/NeutralMob/
 * TamableAnimal 全是原版类型体系，魔改被动生物不实现任何一个是常态）。
 * 狼发狂咬人的瞬间 getTarget()==主人——这是最普适、最准确的"正在构成威胁"
 * 信号，覆盖：原版野狼/驯服狼/北极熊/蜜蜂 + 任何模组魔改的"被动生物变中立"。
 *
 * 做三件事（每 10 tick = 0.5 秒一轮，节流省性能）：
 * 1. 【索敌写目标】扫 16 格内 getTarget()==主人/本女仆 的任意 Mob（不看类型）
 *    → 写进女仆 brain 的 ATTACK_TARGET（setMemory setMemory，与 StartAttacking
 *    同款写入）——MaidMeleeAttack 的攻击执行链反编译实证 isWithinMeleeAttackRange 只是距离
 *    判定，不滤类型 → 写了目标就能打。战斗任务/参战女仆都受益：TLM 索敌
 *    传感器选不到的狼，这里直接喂给攻击行为。
 * 2. 【无战斗任务也能打】非战斗任务（农场/挖矿/跟随…）的女仆没有攻击行为
 *    （WORK 组行为由任务 createBrainTasks 注册），只写目标没人执行 → 直接
 *    调 doHurtTarget（doHurtTarget，EntityMaid 覆写含横扫/饰品事件，自保近身
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
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new NeutralThreatDriver());
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
    public void onServerTick(ServerTickEvent.Post event) {
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
            for (ServerLevel level : server.getAllLevels()) {
                // v1.2.0（2026-09-18）【Sable 兼容】：全世界 AABB → getAllEntities()（超大 AABB 被 Sable 拒查并且每次刷一份堆栈日志）
                for (Entity e : level.getAllEntities()) {
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
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包对野生女仆的规则一律不动）
        }
        if (!maid.isAlive() || maid.isBaby() || maid.isMaidInSittingPose()) {
            return; // 死亡/幼年/坐下不参战
        }
        // 排班中：不写目标、不打、不触发参战（任务全由日程表管理）
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return;
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null || !owner.isAlive()) {
            return;
        }
        // 自保中：让位（自保 250 优先，威胁扫描已含行为化判定）
        if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(
                com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
            return;
        }
        // ★ v1.3.8 新增（借自别人改过的 TLM 1.5.3 的 shouldAbandonTarget）：目标跑远
        //    就撒手——只松手、不加戏，所以放在"主动参战"总开关的判定之前
        abandonFarTarget(maid, owner);
        // ★ v1.3.8 新增（借自 findOwnerPriorityAttackTarget）：主人最近的仇人优先，
        //    没有再退回原来的"谁锁定了主人/女仆"扫描
        Mob threat = ownerEnemy(maid, owner);
        if (threat == null) {
            threat = findThreateningMob(maid, owner);
        }
        if (threat == null) {
            // 威胁解除：清登记（ATTACK_TARGET 的清理交给 TLM StopAttacking/
            // 传感器时效，这里只清自己的冷却登记，防 Map 膨胀）
            ATTACK_CDS.remove(maid.getUUID());
            ASSIGNED_TARGETS.remove(maid.getUUID());
            return;
        }
        // ① 触发参战（AutoCombatSwitch 内部自带排班/自保/幼年让位与去重）——
        // 主人打狼时狼的记仇状态在事件后才就位，这里补上迟到的触发
        com.maidsmart.combat.AutoCombatSwitch.tryEngagePublic(maid);

        // v1.2.0 实测五百一十六【严格门控：②③ 也归"主动参战"总开关管】。
        //
        // 【反馈】"是默认会自动清敌对怪吗？把主动参战关了好像也会自己打怪，工作模式甚至
        // 都没变，似乎是空手攻击。创造模式下检测不出来，一旦切回生存模式，就会主动攻击
        // 敌对生物。"——用户选定**严格口径**：关了主动参战，女仆就只挨打不还手。
        //
        // 【旧版漏在哪】①②③ 三件事里只有 ①（切战斗任务）在 AutoCombatSwitch 内部
        // 查了 COMBAT_AUTO_SWITCH；②（往 brain 写 ATTACK_TARGET）与 ③（直接
        // doHurtTarget 挥砍 + 导航追人）**一个配置项都没读**（本类此前不引用任何配置）。
        // 于是关掉总开关后：任务不切（①被挡），但②③照跑——**任务 UID 不变、人却自己
        // 走上去挥刀**，正是用户看到的"工作模式没变却在打怪"。又因为 `isArmed` 只查主手
        // 有没有 ATTACK_DAMAGE 属性、而镐/斧/锹/锄这类工具**都带该属性**（DiggerItem
        // 构造器挂了 "Tool modifier"，字节码实证），拿着锄头的女仆也算"有武器"→
        // 挥出来的却是工具那点加成，观感就是"空手攻击"。
        //
        // 【创造模式为何测不出】本驱动的威胁判据是行为化的 `mob.getTarget() == 主人/女仆`，
        // 而原版 `TargetingConditions.test` 会经 `canBeSeenAsEnemy()` 过滤目标，创造模式
        // 玩家的 `Abilities.invulnerable` 为 true → 该判定 false → **怪物根本不会把创造
        // 模式的玩家当成敌人**，getTarget 恒为 null → 本驱动静默。切回生存
        // （invulnerable=false）→ 怪物立刻锁定主人 → getTarget 就位 → 当轮即出手。
        //
        // 【门控位置】放在 ① 之后、②③ 之前：威胁扫描与"威胁解除清理"（上面 threat==null
        // 那两个 remove）照常运行，保持 Map 不积压；只是不再主动出手。
        //
        // 【顺手收回自己写过的目标】关掉开关时若她脑里还留着**我们写的** ATTACK_TARGET，
        // TLM 的攻击行为仍会照着打——所以用 ASSIGNED_TARGETS 这个"只登记我们写过的"
        // 表精确判断：命中才清 ATTACK_TARGET/LOOK_TARGET，不碰 TLM 自己写的目标
        //（战斗任务的女仆由 TLM 传感器写目标，本表无记录）。
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_AUTO_SWITCH.get()) {
            boolean mine = ASSIGNED_TARGETS.remove(maid.getUUID()) != null;
            ATTACK_CDS.remove(maid.getUUID());
            if (mine) {
                try {
                    maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
                } catch (Throwable ignored) {
                }
            }
            return; // 关了主动参战 → ②写目标 / ③挥砍 一律不做（只挨打不还手）
        }

        // ② 索敌写目标：战斗任务的女仆写了目标就有攻击行为执行（TLM 索敌
        //    传感器选不到魔改生物，这里直接喂目标）
        boolean hasBrainTarget = maid.getBrain()
                .getMemory(MemoryModuleType.ATTACK_TARGET).isPresent();
        if (!hasBrainTarget) {
            try {
                // ATTACK_TARGET 泛型是 LivingEntity（javap 实证 ATTACK_TARGET 类型）——
                // 直接写实体本体（StartAttacking 同款：setMemory(type, Optional.of)）
                maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET,
                        java.util.Optional.of(threat));
                // LOOK_TARGET 泛型是 PositionTracker——EntityTracker 是其子类
                //（让 LookAtTargetSink 能转头看向目标，与 StartAttacking 同款配套）
                maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                        java.util.Optional.of(new EntityTracker(threat, true)));
                ASSIGNED_TARGETS.put(maid.getUUID(), threat.getUUID());
            } catch (Throwable ignored) {
            }
        }
        // ③ 攻击执行兜底（实测三百四十五，反馈："进入了攻击模式，但是并没有进行
        //    攻击行为"）：写目标只解决"有目标"，TLM 攻击行为（MaidMeleeAttack）是
        //    WORK 组行为，还有一串 canUse 条件（任务重建时序/活动段/NEAREST_VISIBLE
        //    可见列表/攻击距离）——任一环断了女仆就"进了攻击模式站着不动"（本次
        //    实测现象）。兜底不区分任务：无论战斗任务还是农场/挖矿/跟随，只要威胁
        //    还在就自己打——走近 + 挥砍。
        // v1.1.0 实测三百四十七三处修正（反馈："反击没攻击动画；空手反击太强、
        // 频率又高伤害又高；只会空手反击，原有的攻击没了"）：
        // ①【真兜底让位】TLM 近 2 秒（40 tick）内有过攻击动作（ATTACK_COOLING_DOWN
        //    记忆在 = MaidMeleeAttack 刚写入）→ 完全让位（TLM 链路健康，别抢）。
        //    旧版只查"本 tick 冷却记忆是否在"——TLM 打完冷却只持续十几 tick，
        //    下一个攻击循环的间隙里兜底就插进来，观感就是"原有的攻击没了、
        //    只剩空手连拍"。真兜底 = TLM 长时间（2 秒）没动作才接管。
        // ②【空手不打】空手反击伤害/频率失衡（实测"空手太强"）——没有武器
        //    的女仆只导航跟随（保持威胁在场触发参战/战术），不亲自挥刀；参战切
        //    换会自动从背包装备武器（CombatTaskCompat.prepareSwitch），装上后
        //    自然恢复挥砍。
        // ③【按攻击速度属性算冷却】冷却 = 20 / 攻击速度（原版攻击间隔公式）——
        //    旧版固定 12 tick 一刀无视属性，比拿剑的正常攻击还快。
        // ④【补挥臂动画】doHurtTarget 直调不出摆臂动画——swing 主手让玩家
        //    看到"挥了一下"，与 TLM 攻击观感一致。
        double dist = maid.distanceTo(threat);
        long now = level.getGameTime();
        // ① 真兜底让位：TLM 近 40 tick 内打过（冷却记忆在）→ 这一波完全交给 TLM
        boolean tlmAttacking = maid.getBrain()
                .getMemory(MemoryModuleType.ATTACK_COOLING_DOWN).isPresent();
        if (tlmAttacking) {
            LAST_TLM_SWING.put(maid.getUUID(), now); // 记 TLM 动作时刻
            ATTACK_CDS.remove(maid.getUUID());
            return; // TLM 在正常打——让位（不打也不导航，战术行为管走位）
        }
        // TLM 长时间没动作 → 兜底接管；但兜底自己刚挥过刀也走冷却
        Long lastTlm = LAST_TLM_SWING.get(maid.getUUID());
        boolean tlmRecently = lastTlm != null && now - lastTlm < 40L;
        Long cd = ATTACK_CDS.get(maid.getUUID());
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
            if (FriendlyFireGuard.isFriendly(maid, threat)) {
                return; // 主人/友方不做直接近战反击
            }
            // v1.2.0 实测五百零五【隔墙挥空根因】：本兜底直调 doHurtTarget，绕过了 TLM
            // 原生近战那道视线门。TLM `MaidMeleeAttack`（两树反编译实证）的出手条件是
            // `!isHoldingUsableProjectileWeapon && isWithinMeleeAttackRange(target)
            //  && nearestVisibleLivingEntities.contains(target)`——最后那条 `contains`
            // 走 `Sensor.isEntityAttackable` → `TargetingConditions.test`
            // （`ignoreLineOfSight` 默认 false）→ `Sensing.hasLineOfSight`。
            // 也就是说**原版女仆隔墙本来打不到怪**；而本驱动只按"距离 + getTarget 锁定"
            // 就出手，于是墙后的怪一直锁定主人/女仆 → 女仆每 12 tick 对着墙挥一刀。
            // 修法：出手前补一次视线判定（复用项目现有的那条 raycast 工具，不新造轮子）。
            // 【隔墙不罚站】这里只在"挥砍"这一步拦——下面导航照常，她仍会朝怪走过去、
            // 绕到能看见的位置再打，追击欲望不受影响。
            if (!com.maidsmart.combat.SelfPreservationBehavior.hasSight(maid, threat)) {
                navigateTo(maid, threat); // 看不见 → 不挥空刀，继续走近找视线
                return;
            }
            boolean hit = maid.doHurtTarget(threat);
            maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true); // swing 摆臂
            int cdTicks = attackIntervalTicks(maid);
            ATTACK_CDS.put(maid.getUUID(), now + Math.max(12, cdTicks));
            if (hit) {
                // 女仆自己打的也记一次战斗接触（还原扫描的僵局逃逸阀计时用）
                com.maidsmart.combat.AutoCombatSwitch.touchContactPublic(maid);
            }
        } else {
            navigateTo(maid, threat);
        }
    }

    /**
     * 直连导航走向威胁（不走 MoveToTargetSink——站桩标记拦不住；战术行为（230）
     * 激活时它自己管走位，isActive 判定只查目标+距离，有目标时它自然接管，
     * 这里只是兜底导航）。
     *
     * v1.2.0 实测五百零五：从原来"远距离分支的内联写法"提成方法，供"看不见所以
     * 不出手"的那条路径复用（隔墙继续走近找视线，而不是原地罚站）。
     */
    private static void navigateTo(EntityMaid maid, Mob threat) {
        if (com.maidsmart.combat.MaidCombatTacticsBehavior.isActive(maid)) {
            return;
        }
        // v1.3.2 实测六百五十六：骑扫帚飞行期间不导航——她是乘客，导航产生的位移每 tick
        // 会被扫帚的 positionRider 按回鞍位（"反复被拉回"的同一根因），位置由
        // MaidBroomDrive 全权决定。战术那条闸（isActive）此时已经是 false（战术让位），
        // 所以这里必须自己再拦一道。
        if (com.maidsmart.combat.MaidBroomKit.isBroomAirborne(maid)) {
            return;
        }
        maid.getNavigation().moveTo(threat.getX(), threat.getY(), threat.getZ(), CHASE_SPEED);
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
            ItemStack main = maid.getMainHandItem();
            if (main.isEmpty()) {
                return false;
            }
            return main.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
                .anyMatch(en -> en.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.1.0 实测三百四十七：按攻击速度属性计算攻击间隔（tick）——
     *  原版公式 20/attack_speed（剑 1.6 → 12.5 tick，斧 1.0 → 20 tick），
     *  旧版固定 12 tick 无视属性 */
    private static int attackIntervalTicks(EntityMaid maid) {
        try {
            var atkSpeed = maid.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            double speed = atkSpeed != null ? atkSpeed.getBaseValue() : 4.0;
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
     *
     * ── 实测六百一十九【看得见才算威胁】（用户反馈）──
     * 原文："在地面下面有空洞里面有僵尸，酒狐隔着方块就感知到了，但是因为没有可以
     * 下去的入口，所以就会开始原地打转。或许改成有方块遮挡的怪不能被感知到会好一些？"
     *
     * 【根因：写进 ATTACK_TARGET 会把"看不见"这件事永久变成"看得见"】原版
     * {@code Sensor.isEntityAttackable}（= 所有"这个实体我可见吗"的判据，javap 实证）是
     * "**如果它就是我的 ATTACK_TARGET，就跳过视线判定**，否则才走
     * TargetingConditions.forCombat（含 hasLineOfSight）"。本驱动此前只按
     * "距离 + getTarget 锁定" 就写 ATTACK_TARGET——一旦写进去，它自己、TLM 的
     * 走位（SetWalkTargetFromAttackTargetIfTargetOutOfReach）、AidTask 的
     * {@code nearestVisibleLivingEntities.contains(target)} 全线都当那只怪
     * 看得见：隔着墙也追、隔着墙也挥刀（实测五百零五修的是**我们自己的**挥刀那条，
     * TLM 自己那条一直被这个绕过）。追不到的怪 → 原地打转。
     *
     * 【修法】威胁候选加一条视线门（复用项目现成的 raycast，不新造轮子）：
     * 被方块挡住的怪**不当威胁**，于是根本不会被写进 ATTACK_TARGET——上面那条
     * 绕过链从源头断开。判据本身与原版同口径（{@code Entity.hasLineOfSight} 也是
     * COLLIDER + 不看流体，见 {@link SelfPreservationBehavior#hasSight}）。
     * 为了可测/可读，这一条单独留成 {@link #perceivable}，扫描与自检共用同一份，
     * 不允许各写一份。
     */
    // ───────────────────────────────────────────────────────────────────────
    // v1.3.8 实测六百六十三【借来的两条：援护主人的仇人 + 目标跑远就撒手】
    //
    // 出处：别人改过的 TLM 1.5.3（拿它与官方 1.5.3 逐类 javap 对比后实证）：
    //   · IAttackTask.findOwnerPriorityAttackTarget —— 优先打「主人最近的仇人」；
    //   · IAttackTask.shouldAbandonTarget / isWithinAttackRange —— 目标离她和主人都
    //     超过半径就撒手（两条判据互为反面、共用同一个半径）；
    //   · 新配置 MaidCombatRange（默认 16，范围 8~48，"Temporary expanded assist
    //     radius when combat is detected in Non-Home mode"）。
    // 他们是【改 TLM 源码重编译】，我们是【外挂模组】——所以照搬的是判据与阈值，
    // 不是类结构；落点选在本类，因为本类本来就是"往 ATTACK_TARGET 写目标"的那一处。
    //
    // 照搬时故意改了两点（照搬不照抄）：
    //  ① 他们读主人的 getLastHurtByMob / getLastHurtMob **不看时间**：原版这两个字段
    //     一旦写上就不再清空，于是"主人十分钟前打过的那只怪"永远算主人的仇人、永远
    //     被优先。我们按本模组既有的"最近交手窗口"口径（见 {@link CombatWorkRange#inCombat}）
    //     一并查时间戳，超出 {@link #OWNER_COMBAT_TICKS} 不算。
    //  ② 他们那边还有一条"够不到就放弃"（MaidClearStaleAttackTarget，读
    //     CANT_REACH_WALK_TARGET_SINCE 记忆）。**这一条没有照搬**：他们的实现是
    //     {@code 记忆值 > 100}——记忆里存的是"走不到那一刻的 gameTime 绝对值"，拿它跟
    //     常量 100 比，在任何跑了 5 秒以上的世界里恒真（实际效果 = 只要出现过一次
    //     "走不到"就立刻放弃，与他们自己那个 CANT_REACH_GIVE_UP_TICKS = 100 的意图
    //     不符）。要照搬必须先改成 {@code now - since > 100}，那是一条独立行为，
    //     等实测反馈再定——本类 ③ 的兜底导航/挥砍与战术行为（230）已经能兜住这种情况。
    // ───────────────────────────────────────────────────────────────────────

    /** 援护半径（格）：主人的仇人算不算"该去帮"、"跑多远算撒手"。0 = 这两条一起关 */
    private static int assistRadius() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_ASSIST_RADIUS.get();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 主人"最近交手"的窗口（tick）——与 {@link CombatWorkRange#inCombat} 同口径（5 秒） */
    private static final int OWNER_COMBAT_TICKS = 100;

    /**
     * 主人此刻的仇人：优先"最近打主人的人"（最该反击的那个），其次"主人最近打的人"。
     * 两个字段都带时间戳，超过窗口不算（见类内注释 ①；时间戳初值 0 的头 5 秒一并挡掉，
     * 与 {@link CombatWorkRange} 里"初生女仆不算刚交过手"同一条护栏）。
     * 返回 null = 主人这段时间没交手，或那个对象过不了 {@link #legalAssist} 的合法性链。
     */
    private static Mob ownerEnemy(EntityMaid maid, LivingEntity owner) {
        try {
            int now = owner.tickCount; // tickCount
            if (now < OWNER_COMBAT_TICKS) {
                return null;
            }
            LivingEntity by = owner.getLastHurtByMob(); // getLastHurtByMob（最近打主人的人）
            if (by != null && now - owner.getLastHurtByMobTimestamp() < OWNER_COMBAT_TICKS) {
                Mob m = legalAssist(maid, owner, by);
                if (m != null) {
                    return m;
                }
            }
            LivingEntity at = owner.getLastHurtMob(); // getLastHurtMob（主人最近打的人）
            if (at != null && now - owner.getLastHurtMobTimestamp() < OWNER_COMBAT_TICKS) {
                return legalAssist(maid, owner, at);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 援护对象的合法性——他们那两条判据逐条换成本模组现成的实现，不另写一份：
     * <ol>
     *   <li><b>她认它是敌人</b>：{@code BombThrow.legalThrowTarget}（"她的任务认的敌人
     *       + 非友军"，与轰炸投掷同一条口径）；</li>
     *   <li><b>看得见</b>：{@link #perceivable}——实测六百一十九 的视线门；看不见就写
     *       目标 = 她会隔着墙原地打转，这条不能省；</li>
     *   <li><b>在圈里</b>：{@code WorkAreaClamp.allows}（home/排班模式的"工作区域"，
     *       非 home 恒放行）——对应他们的 {@code isWithinRestriction(目标位置)}；</li>
     *   <li><b>距离</b>：主人离它、或她自己离它 ≤ 援护半径（他们的
     *       {@code isWithinAttackRange} 就是"两者取近"这条口径）。</li>
     * </ol>
     */
    private static Mob legalAssist(EntityMaid maid, LivingEntity owner, LivingEntity le) {
        try {
            if (!(le instanceof Mob mob)) {
                return null; // 本类后面要拿它导航/挥砍，兜底通道只认 Mob
            }
            if (!BombThrow.legalThrowTarget(maid, mob)) {
                return null;
            }
            if (!perceivable(maid, owner, mob)) {
                return null;
            }
            if (!com.maidsmart.follow.WorkAreaClamp.allows(maid, mob.blockPosition())) {
                return null;
            }
            int r = assistRadius();
            return (owner.distanceTo(mob) <= r || maid.distanceTo(mob) <= r) ? mob : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 目标跑远就撒手（对应他们的 shouldAbandonTarget）：她的目标离**她**和**主人**都
     * 超过援护半径 → 清目标。清的三条记忆与他们 clearAttackMemories 一致
     * （ATTACK_TARGET / CANT_REACH_WALK_TARGET_SINCE / WALK_TARGET），另加本模组自己
     * 写过的 LOOK_TARGET——目标都松手了，视线还锁着它没有意义。
     *
     * 为什么这一条不受"主动参战"总开关管：它只做减法（松手），不做加法（不会让她去打谁）。
     * 追一个已经跑掉的目标本身就是"追出去→被圈拽回来"的循环源头之一——她还在追、战斗
     * 判定还在，圈就一直大着；松手之后战斗判定自然回落，她该回岗回岗。整条想关就把
     * 援护半径设 0。
     */
    private static void abandonFarTarget(EntityMaid maid, LivingEntity owner) {
        int r = assistRadius();
        if (r <= 0) {
            return;
        }
        try {
            LivingEntity cur = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
            if (cur == null || !cur.isAlive()) {
                return;
            }
            // 【实测六百八十五】扫帚模式的她**带着主人一起在追**——本条判据的前提（她自己跑远了、
            //   主人还在原地）在扫帚上根本不成立：主人就挂在她下面。所以扫帚空中一律让位，追到
            //   哪儿去由扫帚自己的接敌链路决定（目标维持已交给 FlightTargeting 的 128 格）。
            //   不加这一条的话：她一边正确地追着 boss，这里一边每 10 tick 擦掉 ATTACK_TARGET
            //   并打一行"目标跑出 N 格→松手"，日志看着像故障，brain 里的目标也一直在闪。
            if (MaidBroomKit.isBroomAirborne(maid)) {
                return;
            }
            if (maid.distanceTo(cur) <= r || owner.distanceTo(cur) <= r) {
                return; // 任一方还在半径内 → 留着
            }
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); // ATTACK_TARGET
            maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET); // LOOK_TARGET
            maid.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE); // CANT_REACH_WALK_TARGET_SINCE
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); // WALK_TARGET
            java.util.UUID id = maid.getUUID();
            ASSIGNED_TARGETS.remove(id);
            ATTACK_CDS.remove(id);
            com.maidsmart.tool.PromaidLog.log("援护", "目标跑出 " + r + " 格（她与主人都超）→ 松手");
        } catch (Throwable ignored) {
        }
    }

    public static Mob findThreateningMob(EntityMaid maid, LivingEntity owner) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : maid.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                maid.getBoundingBox().inflate(SEARCH_RADIUS))) {
            if (!perceivable(maid, owner, e)) {
                continue;
            }
            Mob mob = (Mob) e;
            double d = maid.distanceTo(mob);
            if (d < bestDist) {
                bestDist = d;
                best = mob;
            }
        }
        return best;
    }

    /**
     * 这只怪算不算"她感知得到的威胁"（实测六百一十九）。
     *
     * 三个条件缺一不可，顺序按"便宜的先算"（前两条是一次字段读，第三条是 raycast）：
     * <ol>
     *   <li>是 Mob、活着、不是她自己、不是别的女仆（姐妹之间不打）；</li>
     *   <li>它的 {@code getTarget()} 正是主人或本女仆（行为化锁定，不看类型）；</li>
     *   <li><b>她真的看得见它</b>——被方块挡住的怪不算威胁（本次修的就是这条）。</li>
     * </ol>
     * 任何一条读抛异常 → 按"不算威胁"处理（宁可少一次主动出手，也不要把一个
     * 够不着的目标写进她脑子里）。
     */
    public static boolean perceivable(EntityMaid maid, LivingEntity owner, Entity candidate) {
        try {
            if (!(candidate instanceof Mob mob) || !mob.isAlive() || mob == maid) {
                return false;
            }
            if (mob instanceof EntityMaid) {
                return false;
            }
            LivingEntity t = mob.getTarget(); // getTarget——行为化锁定的核心信号
            if (t != maid && t != owner) {
                return false;
            }
            // 主人自己的驯服宠物记仇主人（发狂驯服狼）也在此列——getTarget==主人
            // 即真实威胁（TLM MaidMeleeAttack 打它没有心理负担：狼已对主人兵刃相向）
            return com.maidsmart.combat.SelfPreservationBehavior.hasSight(maid, mob);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** 实测三百四十四：查询本驱动为该女仆登记的目标（还原扫描/诊断用） */
    public static java.util.UUID assignedTargetOf(EntityMaid maid) {
        return ASSIGNED_TARGETS.get(maid.getUUID());
    }
}