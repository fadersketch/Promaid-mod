package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * v1.2.0 实测五百一十五：空袭索敌诊断日志（一次性排查工具，验证完可删）。
 *
 * 【为什么需要它】反馈"锁敌范围仍然很奇怪"——加了 513 的半径 50 之后依然锁不到。
 * 排查中已用字节码证实一个**结构性事实**：`EntityMaid.searchDimension()` 的实现是
 *
 * <pre>
 *   if (getScheduleDetail() == Activity.WORK) return getTask().searchDimension(this);
 *   return TaskManager.getIdleTask().searchDimension(this);   // ← 非 WORK 一律走这里
 * </pre>
 *
 * 也就是说**我们覆写的 `searchDimension` 只在排班活动恰为 WORK 时参与**；其余时段
 * （夜间 REST/IDLE 等）扫描盒子来自**空闲任务**的默认口径（水平 = 限制半径
 * `getRestrictRadius()`，垂直硬编码 4.0），我们那个 50 根本不参与。这足以解释
 * "范围仍然很奇怪"。
 *
 * 但"是不是还有别的堵点"不能靠推断——本类就是用来**逐环取证**的：把
 * `IAttackTask.findFirstValidAttackTarget` 那一条链上的每个拒绝点分别计数落盘，
 * 让日志直接指出候选是在哪一环被滤掉的。
 *
 * 【逐环口径】v1.2.0 实测五百二十四起，逐环计数**改照我方索敌链**
 * （{@code FlightTargeting.scan}），这样日志末端的 `我方选中` 就是她真正会锁的目标：
 * 1. `scan`   —— 我们自己按 `maid.searchDimension()` 复扫一遍 AABB（`Level.getEntitiesOfClass`），
 *                记录盒子尺寸与扫到的活体总数。这一步验证"盒子到底多大"。
 * 2. `alive`  —— `isAlive() && e != maid`。
 * 3. `canAttack` —— `EntityMaid.canAttack(e)` → `IAttackTask.canAttack` →
 *                `DefaultMonsterType.canAttack`（Enemy→HOSTILE 直接 true；
 *                TamableAnimal/Npc→FRIENDLY 直接 false；其余 NEUTRAL 要查记仇）。
 *                这一环滤掉动物/村民/玩家/盔甲架。
 * 4. `inRange` —— `EntityMaid.isWithinRestriction(blockPos)`：有 home/工作点限制时
 *                目标必须落在限制半径内（无限制时恒 true）。
 * 5. `inSphere` —— 距离平方 ≤ `2500.0`（= 50 格球）：用户指定的索敌半径。
 * 6. `inSight` —— `SelfPreservationBehavior.hasSight`（raycast）：**"隔墙不出手"就在这一环**，
 *                也是我方新锁定唯一的视线门槛（它没有距离上限）。
 * 7. `picked` —— 七环全过 → 我方会锁定的那个（取最近）。
 *
 * 另外单独输出 `原版可判` = `Sensor.isEntityAttackable` 的通过数，但**它含 `range(16.0)`**
 * （`javap` 实证），不等于"有视线"——旧日志把它标成"有视线"，一度把排查带偏，现在明确标注。
 *
 * 日志每女仆每 `LOG_INTERVAL` tick 最多一条（默认 40 = 2 秒），且只在
 * **空袭 / 扫帚任务且当前没有攻击目标**时打印——正是"锁不到"的现场。有目标或其它
 * 任务一律静默，零刷屏。走 {@link com.maidsmart.tool.PromaidLog}（受运行日志总开关
 * 控制、同时写 logs/promaid.log 与 latest.log），搜关键词 {@code 空袭索敌}。
 *
 * 【实测六百八十五：扫帚也纳进来】扫帚模式原来没有自己的索敌器（只有 TLM 那条链），
 * 所以"她为什么丢目标"在扫帚上一句话都查不到；六百八十五 把扫帚并进
 * {@link FlightTargeting} 之后，本探针的逐环计数对**扫帚同样成立**（两边用的是同一个
 * `scan`）。用户报的"骑着扫帚悬停、一动不动"如果再现，搜 `空袭索敌` 就能看出是
 * "50 格球里真的没有可打的"（picked=- 且前六环都有数）还是"看见了却被某一环滤掉"。
 *
 * 【纯只读】本类不写任何 brain 记忆、不改任何状态、不参与任何战斗决策——
 * 只是一次扫描 + 计数 + 落盘。
 */
public final class FlightTargetProbe {

    /** 诊断间隔（tick）：每 2 秒最多一条（同女仆）。锁不到时的现场足够密。 */
    private static final int LOG_INTERVAL = 40;

    /** maidId → 上次打印的 gameTime */
    private static final java.util.Map<java.util.UUID, Long> LAST =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FlightTargetProbe() {
    }

    /**
     * 每 tick 由 core 行为调用（仅空袭 / 扫帚任务、且无目标时才真正扫描）。
     *
     * 【实测六百八十五】判据从"空袭任务"放宽到"空袭 **或** 扫帚任务"：扫帚那边的索敌
     * 现在也是 {@link FlightTargeting}（同一把尺），所以这一串逐环计数对扫帚同样是他真正
     * 走的那条链——用户报的"骑着扫帚悬停不动"就靠它在日志里定位。
     *
     * @param maid 空袭 / 扫帚女仆
     */
    public static void tick(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (!MaidFlightKit.isFlightTask(maid) && !MaidBroomKit.isBroomTask(maid)) {
                return;
            }
            long now = maid.level().getGameTime();
            Long last = LAST.get(maid.getUUID());
            if (last != null && now - last < LOG_INTERVAL) {
                return;
            }
            // 有目标就说明锁上了，不用记（这条日志专治"锁不到"）
            java.util.Optional<net.minecraft.world.entity.LivingEntity> cur =
                    maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            if (cur.isPresent() && cur.get().isAlive()) {
                return;
            }
            LAST.put(maid.getUUID(), now);
            probe(maid);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 扫一遍候选并按拒绝点逐环计数。
     *
     * v1.2.0 实测五百二十四：口径跟着 {@code FlightTargeting.scan} 一起修——旧版这一环
     * 只统计 `Sensor.isEntityAttackable` 并把它标成"有视线"，可那个方法**内含 `range(16.0)`**
     * （`javap` 实证），所以日志里那句"有视线=0"常常根本不是视线问题、而是目标在 16 格外。
     * 误导性极强（本轮就是靠它才把根因找出来，但也多绕了一圈）。现在：
     * ① 逐环计数**完全照我方索敌链**（可攻击 → 活动范围内 → 50 格球内 → 我方视线），
     *    末端的 `选中` 就等于 {@code scan} 会选中的那个；
     * ② 原版那一关单独列出来并**标明它含 16 格上限**，两个口径一眼可区分。
     */
    private static void probe(EntityMaid maid) {
        net.minecraft.world.phys.AABB box = maid.searchDimension();
        java.util.List<net.minecraft.world.entity.LivingEntity> cands = maid.level().getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, box, e -> true);

        int alive = 0, canAttack = 0, inRange = 0, inSphere = 0, inSight = 0, vanilla = 0;
        String picked = "-";
        double bestSqr = Double.MAX_VALUE;

        for (net.minecraft.world.entity.LivingEntity e : cands) {
            if (e == maid || !e.isAlive()) {
                continue;
            }
            alive++;
            if (!maid.canAttack(e)) {
                continue; // EntityMaid.canAttack → DefaultMonsterType：动物/村民/玩家被滤
            }
            canAttack++;
            if (!maid.isWithinRestriction(e.blockPosition())) {
                continue; // isWithinRestriction：有 home/工作点限制时越界被滤
            }
            inRange++;
            double d = maid.distanceToSqr(e);
            if (d > 2500.0) {
                continue; // （我方）50 格球精筛——用户指定的索敌半径
            }
            inSphere++;
            if (!net.minecraft.world.entity.ai.sensing.Sensor.isEntityAttackable(maid, e)) {
                vanilla++; // 只统计原版那一关，不再用它拦人（它含 16 格上限，见方法注释）
            }
            if (!SelfPreservationBehavior.hasSight(maid, e)) {
                continue; // （我方）视线 raycast——"隔墙不出手"就在这一环
            }
            inSight++;
            if (d < bestSqr) {
                bestSqr = d;
                picked = desc(e) + "@" + String.format("%.1f", Math.sqrt(d));
            }
        }

        com.maidsmart.tool.PromaidLog.log("空袭索敌", String.format(
                "maid=%s task=%s activity=%s 盒尺寸=%.1f/%.1f/%.1f 候选=%d"
                        + " → 存活=%d → 可攻击=%d → 活动范围内=%d → 50格内=%d → 我方有视线=%d"
                        + " → 我方选中=%s（原版可判(含16格上限)=%d）",
                com.maidsmart.tool.PromaidLog.nameOf(maid),
                taskUid(maid),
                activityName(maid),
                box.getXsize(), box.getYsize(), box.getZsize(),
                cands.size(), alive, canAttack, inRange, inSphere, inSight, picked, vanilla));
    }

    /** 当前排班活动名（WORK 才走我们的 searchDimension，其余走空闲任务——日志的关键字段） */
    private static String activityName(EntityMaid maid) {
        try {
            net.minecraft.world.entity.schedule.Activity a = maid.getScheduleDetail();
            return a == null ? "null" : a.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String taskUid(EntityMaid maid) {
        try {
            return maid.getTask() == null ? "null" : maid.getTask().getUid().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 实体描述：类型名（诊断用，只读 getType） */
    private static String desc(net.minecraft.world.entity.LivingEntity e) {
        try {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return key == null ? e.getType().toString() : key.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
