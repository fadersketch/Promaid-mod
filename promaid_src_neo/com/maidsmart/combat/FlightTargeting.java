package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * v1.2.0 实测五百二十一（1.21.1 NeoForge）：空袭专用索敌器。
 *
 * 【需求原文】"将两个空袭状态下的索敌范围强制改为以自身为圆心，半径 50 格。不走 TLM 原版的
 * 机制。现在这个版本近战空袭有一个非常奇怪的点，女仆很容易因为飞的太高然后丢失了自己的目标，
 * 然后就在空中往其他地方飞了，直接脱离了战场。我们要做的是持续瞄准，让女仆往那个方向飞。"
 *
 * 【与 1.20.1 树同源】两树本类逻辑逐行一致，差别只在命名（Mojmap）与个别 API：
 * {@code getUUID()} / {@code level()} / {@code getBrain()} / {@code canAttack()} /
 * {@code isWithinRestriction()} / {@code distanceToSqr()} / {@code Sensor.isEntityAttackable()}。
 * 设计理由与取证过程见 1.20.1 树同名类的注释，此处只留结论。
 *
 * ── 为什么必须绕开 TLM 那条链路（CFR 反编译实证）──
 * TLM 索敌是"传感器扫盒子 → 写可见列表 → 任务从列表里选"：
 * <pre>
 *   MaidNearestLivingEntitySensor.doTick:
 *       AABB box = maid.searchDimension();
 *       list = level.getEntitiesOfClass(LivingEntity.class, box, e -&gt; e != maid &amp;&amp; e.isAlive());
 *       brain.setMemory(NEAREST_VISIBLE_LIVING_ENTITIES, new NearestVisibleLivingEntities(maid, list));
 * </pre>
 * 而 {@code EntityMaid.searchDimension()} **按排班活动分流**：
 * <pre>
 *   if (getScheduleDetail() == Activity.WORK) return getTask().searchDimension(this);
 *   return TaskManager.getIdleTask().searchDimension(this);   // ← 非 WORK 一律走这里
 * </pre>
 * 我们两个空袭任务覆写的 50 格盒子**只在活动恰为 WORK 时参与**；其余时段走 idle 任务的默认
 * 口径（{@code inflate(searchRadius, 4.0, searchRadius)}，**垂直硬编码 4 格**）。她爬高十几格
 * 后敌人就掉出盒子 → 可见列表里没有它 → 目标丢失。
 *
 * ── "飞太高 → 丢目标 → 飘走"的链条 ──
 * 目标掉出列表后 {@code StartAttacking} 再也选不到它；空袭行为的 canStillUse 又要求"有目标"，
 * 目标一丢行为立刻停止、状态被 {@code forget} 整清，她在高空保持滑翔却**没有任何朝向指令**，
 * 于是顺惯性飘出战场。
 *
 * ── 现在的口径 ──
 * 把索敌从 TLM 链路**完全摘出来**，每 tick 自己维持：① 自己扫以自身碰撞箱为中心、半径 50 格的
 * **球**（AABB 粗筛 + 距离平方精筛），**不看排班活动**；② 目标选定后**每 tick 回写**
 * {@code ATTACK_TARGET}/{@code LOOK_TARGET}，TLM 什么时候擦都不影响我们；③ 已锁定目标只要
 * "活着 + 能攻击 + 在活动范围内 + 仍在 {@link #HOLD_RANGE} 内"就继续用、**不再要求视线**——
 * 这正是"持续瞄准"的实质：飞高时视线常被地形/树叶/怪物身体挡住，旧口径会因此放弃目标。
 *
 * 【与 TLM "隔墙不出手"无冲突】新锁定仍要过一道视线 raycast
 * （{@code SelfPreservationBehavior.hasSight}，与实测五百零五给自保近战补的是同一个方法），
 * 墙后的怪不会被新锁定；改变的只是"已打起来的目标不会因为她飞高、视线被挡一下就被丢掉"。
 *
 * ── v1.2.0 实测五百二十四（本类的第二处修正，来自实测日志）──
 * 上一版（五百二十一）留下两个会把"持续瞄准"重新打断的洞，本轮补齐：
 * <ol>
 *   <li>{@link #scan} 的视线门当年误用了 {@code Sensor.isEntityAttackable}——反编译实证它
 *       **内含 {@code range(16.0)}**，于是"50 格索敌"实际只能在 16 格内生效。她一旦被起飞
 *       弧线推到 16 格外就再也拿不回目标（实测日志里大片
 *       {@code 候选=3 → 可攻击=1 → 有视线=0 → 选中=-}）；</li>
 *   <li>维持锁定与新锁定共用 50 格上限——**起飞那口气就把她推出 50 格**
 *       （实测：锁定监守者时 7.1 格，放烟花起飞 2 秒后目标就没了）。现拆成
 *       {@link #RANGE}（发现）/ {@link #HOLD_RANGE}（维持）。</li>
 * </ol>
 *
 * 【纯服务端】不写网络、不碰渲染。状态表由
 * {@code MaidFlightCombatBehavior.forget/clearAll/pauseRound} 与
 * {@code MaidBroomBehavior.stop} 在女仆移除、服务器停止、换任务、以及行为中途停止时清理。
 *
 * ── v1.3.0(beta) 实测六百八十五：扫帚模式也并入本索敌器 ──
 * 【用户原话】"目前的女仆不像空袭状态下不那么容易丢失锁敌。处于扫帚模式（下面还挂着主人）
 * 状态下的女仆容易出现丢失锁敌的情况。大致就是飞到了空中，然后 boss 也飞上来，boss 掉下去。
 * 然后女仆就会骑着扫帚悬停在那边，一动不动。"
 *
 * 【为什么偏偏是扫帚】差别只有一条：**空袭有自己的索敌器（本类），扫帚没有**。扫帚那边
 * （{@code MaidBroomBehavior.currentTarget}）读的是 TLM 那条链，而那条链的"维持"判据是
 * 援护半径（WORK 活动 16 格、其余时段 8 格）+ 传感器盒子（非 WORK 时段垂直硬编码 4 格）：
 * boss 一掉下去、距离一过线，目标当场作废；TLM 每 tick 重挑时既没有 128 格兜底、盒子也
 * 够不着 → 再也拿不回来。扫帚行为随后落进"没目标"那一档，而**挂着主人时那一档的出口正是
 * 原地悬停**（{@code MaidBroomBehavior} ⑥ 的拴绳分支）——"骑在扫帚上悬停、一动不动"就是它。
 * 这也解释了为什么空袭没事而扫帚有事：空袭的 canStillUse 要求"有目标"，目标一丢它自己就
 * 停下来收尾（不会僵在原地），扫帚则是**一直在跑、只是每 tick 都走悬停那一支**。
 *
 * 【现在的口径】扫帚任务一并纳入本类：发现 50 格 / 维持 128 格 / 每 tick 回写
 * {@code ATTACK_TARGET} 与 {@code LOOK_TARGET}，与空袭**同一套数字、同一套判据**（用户要的
 * "像空袭那样不容易丢"就是字面意思——同一个索敌器）。扫帚侧新增的两个接入点是
 * {@code MaidBroomBehavior.aimTarget()}（先问本类、问不到才退回 TLM 链，所以今天能锁到的
 * 目标一个都不会丢）与它 {@code stop()} 里的 {@link #forget}。
 *
 * 【为什么不新建一套"扫帚索敌器"】那会有两份"什么算有效目标"的判据，将来必然漂移——本模组
 * 反复强调的红线是"口径只有一处"（见 {@code MaidBroomDrive} 对扫帚/空袭同款公式的说明）。
 */
public final class FlightTargeting {

    /** 索敌半径（格）——以自身为圆心（用户指定 50）。**只用于"能不能发现"（新锁定）** */
    public static final double RANGE = 50.0;
    private static final double RANGE_SQR = RANGE * RANGE;

    /**
     * 维持锁定半径（格）——**已打起来的目标不再按 50 丢**。
     *
     * 【v1.2.0 实测五百二十四：为什么必须与 RANGE 分开】
     * 空袭的起飞动作是**主动背离敌人 + 抬头 62°**去把烟花推力吃满（见
     * {@code MaidFlightCombatBehavior.faceAwayAndUp} 与 {@code LAUNCH_TICKS_MELEE}），
     * 这一口气就能把她推出 50 格外——实测日志（本机 1.21.1，2026-09-16 22:20:25）：
     * 锁定监守者时距离 7.1 格、放烟花起飞，**2 秒后就已经没有目标了**。
     * 旧口径"超过 RANGE 就丢锁"= 每一轮起飞都在半路自我解除瞄准，行为随即停止、
     * 状态被整清，她在高空保持滑翔却**没有任何朝向指令** → 顺着惯性飘出战场。
     * 这正是"飞得特别高就失去方向"的来源。
     *
     * 所以：**50 只管发现，打起来之后由 128 兜底**（起飞弧线最坏约 60 格；
     * 128 有约两倍余量，同时仍能防住"追着跑出战场"）。真正该收尾的情形（目标死了 /
     * 打不着 / 越出活动范围）由 {@link #keepable} 里其余几条负责，与新锁定同一口径。
     */
    private static final double HOLD_RANGE = 128.0;
    private static final double HOLD_RANGE_SQR = HOLD_RANGE * HOLD_RANGE;

    /**
     * 无锁定时的扫描限频（tick）。
     *
     * 【为什么必须限频】行为**未运行**时，Brain 每 tick 仍会评估它的
     * {@code checkExtraStartConditions}（就是调用本类的 ③ 那一步）。若不限频，"没有敌人"的
     * 空袭女仆会每 tick 扫一次 50 格 AABB 盒——白烧性能。有了它，没锁定时最多每 0.5 秒扫一次。
     * 一旦锁定成功，① 会短路，扫描表根本不参与，所以这只影响"找目标"的那一下。
     */
    private static final int SCAN_INTERVAL = 10;

    /**
     * 锁定表：女仆 UUID → 当前目标。
     *
     * 用 {@link java.lang.ref.WeakReference} 存值而不是强引用：原版 brain 的
     * {@code ATTACK_TARGET} 本来就强引用着目标实体，但我们不该成为"brain 已擦、我们还在
     * 抱着"的那份额外持有者——否则女仆长期存活而目标早已死亡移除时会留下悬挂引用。
     * 弱引用让"目标没了"自然退化成 null，由 {@link #resolve} 重新扫描。
     */
    private static final java.util.Map<java.util.UUID, java.lang.ref.WeakReference<LivingEntity>> LOCKED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 扫描限频表：女仆 UUID → 下次允许真正扫描的 gameTime */
    private static final java.util.Map<java.util.UUID, Long> NEXT_SCAN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 同 tick 去重表：女仆 UUID → 本 tick 已解析出的 gameTime。
     *
     * 【为什么需要】{@link #resolve} 会被空袭行为**同一 tick 调三次**
     * （{@code checkExtraStartConditions} / {@code canStillUse} / {@code tick}，各自都要拿目标）。
     * 不去重的话 keepable 的判据（注册名查询、配置列表扫描）与 brain 写入都做三遍；更要紧的是
     * **三次必须给出同一个答案**——否则会出现"启动判定看到一个目标、运行中维持的是另一个"的分裂。
     * 命中本表就直接复用 {@link #LOCKED} 里的结果。
     */
    private static final java.util.Map<java.util.UUID, Long> RESOLVED_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FlightTargeting() {
    }

    /** 清某个女仆的锁定与限频状态（女仆移除 / 任务切换时调用） */
    public static void forget(java.util.UUID maidId) {
        if (maidId != null) {
            LOCKED.remove(maidId);
            NEXT_SCAN.remove(maidId);
            RESOLVED_TICK.remove(maidId);
        }
    }

    /**
     * v1.2.0 实测五百二十四：行为中途停止时的**轻量清理——保留锁定**。
     *
     * 【为什么不能沿用 forget】空袭行为每次"停一下"都走 forget()，而 forget 会把
     * {@link #LOCKED} 一起清掉。可空袭一轮里行为本来就会短暂停：三件不齐的那两拍、
     * 以及 canStillUse 判定与 tick 之间的边界。锁定一清，她就得重新走"限频扫描 +
     * 视线门"那一整套——而她此刻多半正在高空（对地面目标经常不过视线门），于是变成
     * **有敌人却拿不到目标**：日志里大片 `候选=3 → 可攻击=1 → 有视线=0 → 选中=-`
     * 就是这个状态，表现即"失去方向、自动飘走"。
     * 现在：停止只清"每 tick 的缓存"，**瞄准跨过这次停止**——这才是"持续瞄准"。
     * 真正该断的情况（女仆移除、切走空袭任务、服务器停止）仍走 {@link #forget} / {@link #clearAll}。
     */
    public static void pause(java.util.UUID maidId) {
        if (maidId != null) {
            NEXT_SCAN.remove(maidId);
            RESOLVED_TICK.remove(maidId);
        }
    }

    /** 全清（服务器停止 / 重新加载时调用） */
    public static void clearAll() {
        LOCKED.clear();
        NEXT_SCAN.clear();
        RESOLVED_TICK.clear();
    }

    /**
     * 解析本 tick 应当瞄准的目标，并负责把它写回 brain。
     *
     * @return null = 50 格球内确实没有可打的目标（调用方据此走收尾）
     */
    public static LivingEntity resolve(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        try {
            java.util.UUID id = maid.getUUID();
            if (!MaidFlightKit.isFlightTask(maid) && !MaidBroomKit.isBroomTask(maid)) {
                // 只有两个空袭任务与扫帚任务走本索敌器（扫帚见类注释 实测六百八十五），
                // 其它任务不受影响；顺手清掉残留（切走任务时不该继续抱着旧目标，否则她切回
                // 地面任务后我们还会往 brain 里写空战目标）
                LOCKED.remove(id);
                NEXT_SCAN.remove(id);
                RESOLVED_TICK.remove(id);
                return null;
            }

            long now = maid.level().getGameTime();

            // ⓪ 同 tick 去重：本 tick 已经解析过就直接复用锁定结果（见 RESOLVED_TICK 注释）
            Long done = RESOLVED_TICK.get(id);
            if (done != null && done == now) {
                return lockedOf(id);
            }

            // ① 维持锁定：还活着 / 同维度 / 能攻击 / 在活动范围内 / 仍在 HOLD_RANGE 内 → 继续用它。
            //    【刻意不查视线】——这是"持续瞄准"的关键，见类注释 ③。
            //    顺手把扫描冷却推到"下个 tick 即可扫"——一旦这条锁定线后面真的断了，
            //    重新索敌不必再等最多 0.5 秒的限频（限频只为"没敌人时别空扫"）。
            LivingEntity locked = lockedOf(id);
            if (locked != null && keepable(maid, locked)) {
                RESOLVED_TICK.put(id, now);
                NEXT_SCAN.put(id, now);
                writeBrain(maid, locked);
                return locked;
            }

            // ② 接管 brain 里已有的合法目标：切换任务/刚进战斗时她可能已经有目标（TLM 写的、
            //    或本模组其它驱动写的）。先采纳它，避免"刚接管就重扫、把目标换成另一个更近的"。
            LivingEntity inBrain = brainTarget(maid);
            if (inBrain != null && keepable(maid, inBrain)) {
                LOCKED.put(id, new java.lang.ref.WeakReference<>(inBrain));
                RESOLVED_TICK.put(id, now);
                NEXT_SCAN.put(id, now);
                writeBrain(maid, inBrain);
                return inBrain;
            }

            // ③ 新锁定：限频扫 50 格球，取最近的合法目标（**这一环含视线门**，与 TLM 原版一致）
            Long next = NEXT_SCAN.get(id);
            if (next != null && now < next) {
                LOCKED.remove(id);
                RESOLVED_TICK.put(id, now);
                return null; // 扫描冷却中：本轮不扫（最多晚 0.5 秒选到目标）
            }
            NEXT_SCAN.put(id, now + SCAN_INTERVAL);
            LivingEntity found = scan(maid);
            RESOLVED_TICK.put(id, now);
            if (found != null) {
                LOCKED.put(id, new java.lang.ref.WeakReference<>(found));
                writeBrain(maid, found);
                return found;
            }

            // ④ 确实没有目标了：清锁，让调用方走收尾
            LOCKED.remove(id);
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ---------------- 内部 ---------------- */

    private static LivingEntity lockedOf(java.util.UUID id) {
        java.lang.ref.WeakReference<LivingEntity> ref = LOCKED.get(id);
        return ref == null ? null : ref.get();
    }

    /**
     * 该目标是否还值得**继续锁定**（维持锁定用的判据，比新锁定少一条视线）。
     *
     * 逐条理由：
     * <ul>
     *   <li>存活——目标死了当然要换；</li>
     *   <li>同维度——跨维度她过不去，交给调用方收尾；</li>
     *   <li>{@code maid.canAttack(e)}——即 {@code EntityMaid.canAttack} → {@code IAttackTask
     *       .canAttack} → {@code DefaultMonsterType.canAttack}：排除玩家/盔甲架/宠物/村民，
     *       中立生物要记仇。与 TLM 原版同一口径（也顺带保证被驯服后不会再被锁）；</li>
     *   <li>{@code isWithinRestriction}——有 home/工作点限制时不能越界追；</li>
     *   <li>距离 ≤ {@link #HOLD_RANGE}——**不是**新锁定用的 {@link #RANGE}：起飞那一口气
     *       就会把她推出 50 格，按 50 丢锁 = 每轮都在半路自我解除瞄准（详见 HOLD_RANGE 注释）；</li>
     *   <li>{@code FriendlyFireGuard.isFriendly}——主人/同主女仆/友军双保险。</li>
     * </ul>
     */
    private static boolean keepable(EntityMaid maid, LivingEntity e) {
        try {
            if (e == null || !e.isAlive() || e.level() != maid.level()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, e)) {
                return false;
            }
            if (!maid.canAttack(e)) {
                return false;
            }
            if (!maid.isWithinRestriction(e.blockPosition())) {
                return false;
            }
            return maid.distanceToSqr(e) <= HOLD_RANGE_SQR;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** brain 里现有的目标（ATTACK_TARGET 优先，退回 Mob.getTarget） */
    private static LivingEntity brainTarget(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
            if (mem.isPresent() && mem.get().isAlive()) {
                return mem.get();
            }
        } catch (Throwable ignored) {
        }
        LivingEntity t = maid.getTarget();
        return t != null && t.isAlive() ? t : null;
    }

    /**
     * 扫 50 格球，取最近的合法目标（新锁定用，**含视线门**）。
     *
     * 两步筛：先用 AABB 膨胀 50 格粗筛（{@code inflate} 三轴独立膨胀，等价于边长 100 的
     * 立方盒——它比球宽松，斜角方向会多捞进来一些），再用距离平方精筛成**球**。这样实际
     * 生效范围就是用户要的"以自身为圆心、半径 50 格"，而粗筛仍是引擎最擅长的 AABB 查询。
     */
    private static LivingEntity scan(EntityMaid maid) {
        try {
            net.minecraft.world.phys.AABB box = maid.getBoundingBox().inflate(RANGE, RANGE, RANGE);
            LivingEntity best = null;
            double bestSqr = Double.MAX_VALUE;
            for (LivingEntity e : maid.level().getEntitiesOfClass(LivingEntity.class, box, x -> true)) {
                if (e == maid || !e.isAlive()) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, e)) {
                    continue; // 主人 / 同主女仆 / 友军
                }
                if (!maid.canAttack(e)) {
                    continue; // 类型 / 记仇口径（与 TLM IAttackTask.canAttack 一致）
                }
                if (!maid.isWithinRestriction(e.blockPosition())) {
                    continue; // 活动范围
                }
                double d = maid.distanceToSqr(e);
                if (d > RANGE_SQR) {
                    continue; // 精筛成球
                }
                // 视线门：**这一环曾经是"50 格索敌"整个失效的原因**（v1.2.0 实测五百二十四）。
                //
                // 【旧口径为什么错】旧版这里调的是 `Sensor.isEntityAttackable(maid, e)`，
                // 想当然地把它当成"视线判定"。`javap` 反编译 1.21.1 客户端 jar 实证：
                //
                //     static {
                //         TARGET_CONDITIONS = TargetingConditions.forCombat().range(16.0);
                //         ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING =
                //                 forCombat().range(16.0).ignoreInvisibilityTesting();
                //     }
                //     isEntityAttackable(a, b) {
                //         if (a.getBrain().isMemoryValue(ATTACK_TARGET, b))
                //                 return ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING.test(a, b);
                //         return TARGET_CONDITIONS.test(a, b);
                //     }
                //     TargetingConditions.test: d = a.distanceToSqr(b);
                //                               if (d > (range * visibility)²) return false;
                //
                // 两个变体**都带 `range(16.0)`**，也就是这一环真正卡的是"**16 格以内**"
                // （1.21.1 两个变体都仍然要求视线；1.20.1 那对里有一个忽略视线的变体）。
                // 于是：我们扫的是 50 格球、候选也都捞进来了（日志 `候选=3 存活=2 可攻击=1`），
                // 却**在 16 格这一关被全数否决**（日志 `有视线=0 → 选中=-`）。
                // 她一旦被起飞弧线推到 16 格外，就再也拿不回目标——高空无目标 = 无朝向
                // 指令 → 顺惯性飘出战场，正是用户报的"飞得特别高就失去方向"。
                //
                // 【现在的口径】改用本模组自有的 `SelfPreservationBehavior.hasSight`：
                // 一次纯 raycast（`ClipContext.Block.COLLIDER`，中心点上抬 1.2 格），
                // **没有距离上限**——"隔墙不出手"照样成立（墙后的怪仍然被挡在这里），
                // 但"能不能发现"重新交回给用户指定的 50 格球。与实测五百零五给自保
                // 近战链路补视线时用的是同一个方法，口径统一。
                if (!SelfPreservationBehavior.hasSight(maid, e)) {
                    continue;
                }
                if (d < bestSqr) {
                    bestSqr = d;
                    best = e;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把目标写回 brain（每 tick 调用 = 抵抗 TLM 那边的擦除）。
     *
     * 两件一起写，缺一不可：
     * <ul>
     *   <li>{@code ATTACK_TARGET}——原版/TLM 的攻击行为读它；空袭行为自己也读它；</li>
     *   <li>{@code LOOK_TARGET}——{@code LookAtTargetSink}（CORE 组，任何活动都跑）读它来
     *       转头，不写她就会"身体朝一边、眼睛看另一边"。{@code EntityTracker} 是
     *       {@code PositionTracker} 的子类，与 {@code StartAttacking} 的配套写法同款。</li>
     * </ul>
     */
    private static void writeBrain(EntityMaid maid, LivingEntity target) {
        try {
            maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, java.util.Optional.of(target));
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    java.util.Optional.of(new EntityTracker(target, true)));
        } catch (Throwable ignored) {
        }
    }
}
