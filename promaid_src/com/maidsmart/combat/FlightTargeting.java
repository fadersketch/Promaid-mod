package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 空袭/扫帚/载具共用的索敌器，完全绕开 TLM 的"传感器扫盒子→可见列表→任务选"链路，每 tick
 * 自己维持目标。为什么绕开：{@code EntityMaid.searchDimension()} 按排班活动分流，只有活动为
 * WORK 时才用任务自己的扫描盒，其余时段走 idle 默认口径（垂直硬编码 4 格）；她爬高十几格后
 * 敌人就掉出盒子 → {@code StartAttacking} 选不到 → 行为 canStillUse 失败即停止、状态被
 * {@code forget} 清空，她在高空滑翔却无朝向指令，顺惯性飘出战场（"飞太高→丢目标→飘走"）。
 *
 * 本类口径：① 自扫球（AABB 粗筛 + 距离平方精筛），不看排班活动；② 每 tick 回写
 * {@code ATTACK_TARGET}/{@code LOOK_TARGET}；③ 已锁定目标只要活着、能攻击、在活动范围内、
 * 仍在 {@link #HOLD_RANGE} 内就继续用，**不再要求视线**（视线只作新锁定门槛，见
 * {@code SelfPreservationBehavior.hasSight}）。
 * 【纯服务端】不写网络、不碰渲染；状态表由各行为的 forget/clearAll/pause/stop 清理。
 * 发现/维持半径的拆分见 {@link #HOLD_RANGE}，固定翼单独一档见 {@link #AIRCRAFT_RANGE}。
 */
public final class FlightTargeting {

    /** 索敌半径（格）——以自身为圆心（用户指定 50）。**只用于"能不能发现"（新锁定）** */
    public static final double RANGE = 50.0;
    private static final double RANGE_SQR = RANGE * RANGE;

    /**
     * 维持锁定半径（格）——已打起来的目标不按发现半径 {@link #RANGE}(50) 丢，由 128 兜底。
     * 起飞动作（背离敌人 + 抬头 62° 吃满烟花推力）一口气能把她推出 50 格外（实测：锁定监守者
     * 7.1 格，起飞 2 秒后目标即丢），旧口径"超过 RANGE 就丢锁"= 每轮都在半路自我解除瞄准。
     * 128 约为最坏起飞弧线（约 60 格）的两倍余量，同时仍能防"追着跑出战场"。真正该收尾的情形
     * （目标死 / 打不着 / 越出活动范围）由 {@link #keepable} 其余几条负责，与新锁定同一口径。
     */
    private static final double HOLD_RANGE = 128.0;
    private static final double HOLD_RANGE_SQR = HOLD_RANGE * HOLD_RANGE;

    /**
     * 固定翼（AC-130H / A-10 等）的发现半径（格，度量用**水平**距离），圆心取**载具**。
     * maid-frontline 的空中索敌就是 384 / 维持 576（A-10 900），圆心也是载具：固定翼一次通场
     * 就飞出上百格，若仍用 50 格、以主人或自身为圆心，等于"只打眼前这几十格"。
     * 直升机/飞艇/扫帚/地面载具一个字节不变（仍以自身为圆心的 50 格球）。
     */
    public static final double AIRCRAFT_RANGE = 384.0;
    private static final double AIRCRAFT_RANGE_SQR = AIRCRAFT_RANGE * AIRCRAFT_RANGE;

    /** 固定翼的维持锁定半径（格，水平）：发现 384 → 维持 576，与 maid-frontline 同档。 */
    private static final double AIRCRAFT_HOLD_RANGE = 576.0;
    private static final double AIRCRAFT_HOLD_RANGE_SQR = AIRCRAFT_HOLD_RANGE * AIRCRAFT_HOLD_RANGE;

    /** A-10 的维持锁定半径单独为 900 格（jar 里它打得更远、也更不该丢锁）。 */
    private static final double A10_HOLD_RANGE = 900.0;
    private static final double A10_HOLD_RANGE_SQR = A10_HOLD_RANGE * A10_HOLD_RANGE;

    /**
     * 无锁定时的扫描限频（tick）。
     *
     * 【为什么必须限频】行为**未运行**时，Brain 每 tick 仍会评估它的
     * {@code checkExtraStartConditions}（就是本类的 ③ 那一步）。若不限频，"没有敌人"的空袭
     * 女仆会每 tick 扫一次 50 格 AABB 盒——白烧性能。有了它，没锁定时最多每 0.5 秒扫一次。
     * 一旦锁定成功，① 会短路，扫描表根本不参与，所以这只影响"找目标"的那一下。
     */
    private static final int SCAN_INTERVAL = 10;

    /**
     * R1125：主人的"当前目标"有效期（tick，200 = 10 秒）。原版 {@code getLastHurtMob} 一旦写上
     * 就永不清空，直接读等于"主人十分钟前打过的那只怪"永远优先；因此只认最近这段时间内
     * 主人打过 / 打过主人的目标（与 {@code LiveThreat} 的时效口径同源）。
     */
    private static final int OWNER_THREAT_TICKS = 200;

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
     * 同 tick 去重表：女仆 UUID → 本 tick 已解析出的 gameTime。{@link #resolve} 被空袭行为同一
     * tick 调三次（checkExtraStartConditions / canStillUse / tick），不去重则 keepable 判据与
     * brain 写入做三遍；更要紧的是三次必须给出同一答案，否则会"启动看到 A、运行中维持 B"。
     * 命中本表直接复用 {@link #LOCKED} 里的结果。
     */
    private static final java.util.Map<java.util.UUID, Long> RESOLVED_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百八十五】「固定翼索敌」日志的独立频限表（女仆 UUID → 上次打印的毫秒时刻）。
     * 与其它日志互不顶掉；节流 5 秒，见 {@link #logAircraft}。
     */
    private static final java.util.Map<java.util.UUID, Long> AIRCRAFT_LOG_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FlightTargeting() {
    }

    /** 清某个女仆的锁定与限频状态（女仆移除 / 任务切换时调用） */
    public static void forget(java.util.UUID maidId) {
        if (maidId != null) {
            LOCKED.remove(maidId);
            NEXT_SCAN.remove(maidId);
            RESOLVED_TICK.remove(maidId);
            AIRCRAFT_LOG_AT.remove(maidId);
        }
    }

    /**
     * 载具瞬移后的重对齐：**连同 brain 里的旧目标一起擦掉**。
     * <p>旧位置锁定的目标在瞬移后可能还在 576 格维持圈里——留着它，飞机就会掉头飞回旧战场；
     * 擦掉之后下一拍按新位置重新扫描（固定翼圆心=载具、水平 384 格）。
     */
    public static void forgetAndErase(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        forget(maid.m_20148_());
        eraseBrain(maid);
    }

    /**
     * 行为中途停止时的轻量清理——只清每 tick 缓存（{@link #NEXT_SCAN}），**保留 {@link #LOCKED} 锁定**。
     * 空袭一轮里行为本就会短暂停（三件不齐的两拍、canStillUse 与 tick 的边界）；若按 {@link #forget}
     * 连锁定一起清，她得重走"限频扫描 + 视线门"，而她此刻多半在高空、对地面目标常不过视线门，于是
     * "有敌人却拿不到目标"（日志大片 候选=3→可攻击=1→有视线=0→选中=-），表现为失去方向、自动飘走。
     * 真正该断的情况（女仆移除、切走任务、服务器停止）仍走 {@link #forget} / {@link #clearAll}。
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
        AIRCRAFT_LOG_AT.clear();
    }

    /**
     * 解析本 tick 应当瞄准的目标，并负责把它写回 brain。
     *
     * @return null = 发现范围内确实没有可打的目标（调用方据此走收尾）
     */
    public static LivingEntity resolve(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        try {
            java.util.UUID id = maid.m_20148_();
            // R1125：显式「飞过去」期间索敌整段让位——她会一直飞向目标点，不因为路边有怪
            // 就拐去接敌（玩家口径：飞过去不应该被锁敌/战斗打断）。
            if (com.maidsmart.patrol.MaidTravelTarget.aim(maid) != null) {
                LOCKED.remove(id);
                NEXT_SCAN.remove(id);
                RESOLVED_TICK.remove(id);
                eraseBrain(maid);
                return null;
            }
            // 【R1129】载具接敌闸：任务不是攻击类、或她自己打不了且车里没弹 → 当作周围没有敌人。
            // 只约束"骑卓越前线载具"这一档（MaidMountCompat 对其他坐骑恒返回 true）。
            if (!MaidMountCompat.riderEngageAllowed(maid)) {
                LOCKED.remove(id);
                NEXT_SCAN.remove(id);
                RESOLVED_TICK.remove(id);
                eraseBrain(maid);
                return null;
            }
            if (!MaidFlightKit.isFlightTask(maid) && !MaidBroomKit.isBroomTask(maid)
                    && !isFlightMountRider(maid) && !isVehicleMountRider(maid)) {
                // 只有两个空袭任务、扫帚任务，以及【实测七百三十】被指挥棒绑在**飞行坐骑**
                // （直升机/固定翼）上、【实测七百三十八】绑在任何**卓越前线载具**（含地面车/坦克）
                // 上的女仆走本索敌器，其它任务不受影响；顺手清掉残留
                // （切走任务时不该继续抱着旧目标，否则她切回地面任务后我们还会往 brain 里写空战目标）
                LOCKED.remove(id);
                NEXT_SCAN.remove(id);
                RESOLVED_TICK.remove(id);
                return null;
            }

            long now = maid.m_9236_().m_46467_();
            // 【实测七百八十五 → 一千零五十四】固定翼单独一档（飞得高 + 一次通场上百格）：
            // 发现/维持改成"以载具为圆心的水平 384 / 576 格（A-10 维持 900）"。
            boolean aircraft = isAircraftRider(maid);

            // ⓪ 同 tick 去重：本 tick 已经解析过就直接复用锁定结果（见 RESOLVED_TICK 注释）
            Long done = RESOLVED_TICK.get(id);
            if (done != null && done == now) {
                return lockedOf(id);
            }

            // ⓪ R1125：主人正在打 / 正在打主人的目标永远排在最前（飞行载具与扫帚上的她
            // 先帮主人处理这一个）。锁上之后由 ① 的 HOLD_RANGE 维持——旁边出现更近的怪也不转火，
            // 除非这一个离开锁敌范围 / 死亡 / 不再可攻击。
            LivingEntity hot = ownerThreat(maid);
            if (hot != null && keepable(maid, hot, aircraft)) {
                if (lockedOf(id) != hot) {
                    LOCKED.put(id, new java.lang.ref.WeakReference<>(hot));
                }
                RESOLVED_TICK.put(id, now);
                NEXT_SCAN.put(id, now);
                writeBrain(maid, hot);
                return hot;
            }

            // ① 维持锁定：还活着 / 同维度 / 能攻击 / 在活动范围内 / 仍在 HOLD_RANGE 内 → 继续用它。
            //    【刻意不查视线】——这是"持续瞄准"的关键，见类注释 ③。
            //    顺手把扫描冷却推到"下个 tick 即可扫"——一旦这条锁定线后面真的断了，
            //    重新索敌不必再等最多 0.5 秒的限频（限频只为"没敌人时别空扫"，不该在
            //    交战中拖延重新锁定）。
            LivingEntity locked = lockedOf(id);
            if (locked != null && keepable(maid, locked, aircraft)) {
                RESOLVED_TICK.put(id, now);
                NEXT_SCAN.put(id, now);
                writeBrain(maid, locked);
                return locked;
            }

            // ② 接管 brain 里已有的合法目标：切换任务/刚进战斗时她可能已经有目标（TLM 写的、
            //    或本模组其它驱动写的）。先采纳它，避免"刚接管就重扫、把目标换成另一个更近的"。
            LivingEntity inBrain = brainTarget(maid);
            if (inBrain != null && keepable(maid, inBrain, aircraft)) {
                LOCKED.put(id, new java.lang.ref.WeakReference<>(inBrain));
                RESOLVED_TICK.put(id, now);
                NEXT_SCAN.put(id, now);
                writeBrain(maid, inBrain);
                return inBrain;
            }

            // ③ 新锁定：限频扫发现半径（固定翼 = 以载具为圆心的水平 384 格），取最近的合法目标
            //    （**这一环含视线门**，与 TLM 原版一致）
            Long next = NEXT_SCAN.get(id);
            if (next != null && now < next) {
                LOCKED.remove(id);
                RESOLVED_TICK.put(id, now);
                if (aircraft) {
                    eraseBrain(maid);
                }
                return null; // 扫描冷却中：本轮不扫（最多晚 0.5 秒选到目标）
            }
            NEXT_SCAN.put(id, now + SCAN_INTERVAL);
            LivingEntity found = scan(maid, aircraft);
            RESOLVED_TICK.put(id, now);
            if (found != null) {
                LOCKED.put(id, new java.lang.ref.WeakReference<>(found));
                writeBrain(maid, found);
                if (aircraft) {
                    net.minecraft.world.entity.Entity c = scanCenter(maid, true);
                    logAircraft(maid, "锁定（圆心=" + (c == maid ? "自身" : "载具")
                            + "，水平 " + Math.round(Math.sqrt(horizontalDistSqr(c, found))) + " 格）");
                }
                return found;
            }

            // ④ 确实没有目标了：清锁，让调用方走收尾
            LOCKED.remove(id);
            if (aircraft) {
                // 固定翼还要把 brain 里那只**已被本索敌器否掉**的目标擦掉：TLM 的
                // StartAttacking 可能还在往 ATTACK_TARGET 写另一个远处目标；不擦的话
                // tickAttack / 引擎自带的 "Mob 乘客自动开火" 仍会拿它开火（实机日志：
                // 索敌器说"没有活敌人"，炮弹却追着 400+ 格外的 netherite_monstrosity）。
                eraseBrain(maid);
                logAircraft(maid, "附近没有活敌人（新锁定以载具为圆心、水平 " + (long) AIRCRAFT_RANGE + " 格）");
            }
            return null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return null;
        }
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 【实测七百三十】她是不是"被骑乘指挥棒绑在一只**飞行坐骑**上"的女仆。
     *
     * <p>
     * 所以这一档并入本索敌器：**发现 50 格 / 维持 128 格**（固定翼另档，见
     * {@link #AIRCRAFT_RANGE}），与扫帚/空袭同一套判据
     * （红线"口径只有一处"）。判据走载具自己的引擎名（反射拿不到 → false，一个字节都不碰）。
     */
    private static boolean isFlightMountRider(EntityMaid maid) {
        try {
            if (maid == null || !MaidRideKit.isBatonBound(maid)) {
                return false;
            }
            net.minecraft.world.entity.Entity v = maid.m_20202_();
            return v != null && MaidMountCompat.isFlyingVehicle(v);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return false;
        }
    }

    /**
     * 她是不是"被骑乘指挥棒绑在一只卓越前线载具上"的女仆（飞行与地面载具都算）。
     * 这类骑乘也要"发现 50 格"这套索敌，否则她只能看见 TLM 16/8 格援护半径里的敌人，
     * 绕圈半径才 2.5 格却"发现不了"几格外的目标。判据：指挥棒绑着 + 骑的是载具
     * （{@code Kind.VEHICLE}）；原版兽走 TLM 自己那条链，冰火传说的龙是悬空鞍位、她不是乘客
     * （{@code getVehicle()} 为 null），天然落不进来。
     */
    private static boolean isVehicleMountRider(EntityMaid maid) {
        try {
            if (maid == null || !MaidRideKit.isBatonBound(maid)) {
                return false;
            }
            net.minecraft.world.entity.Entity v = maid.m_20202_();
            return v != null && MaidMountCompat.kindOf(v) == MaidMountCompat.Kind.VEHICLE;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return false;
        }
    }

    /**
     * 她是不是"被骑乘指挥棒绑在一只固定翼上"的女仆（{@code AIRCRAFT} 引擎：AC-130H / A-10 /
     * J-16 / Ju-87）。固定翼要单独一档的理由与改动见 {@link #AIRCRAFT_RANGE}。判据走载具
     * 自己的引擎名（{@link MaidMountCompat#isAircraft}，反射拿不到 → false）；直升机 / 飞艇 /
     * 扫帚 / 地面载具一个都不在此列。
     */
    private static boolean isAircraftRider(EntityMaid maid) {
        try {
            if (maid == null || !MaidRideKit.isBatonBound(maid)) {
                return false;
            }
            net.minecraft.world.entity.Entity v = maid.m_20202_();
            return v != null && MaidMountCompat.isAircraft(v);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return false;
        }
    }

    /**
     * 【实测七百八十五 → 一千零五十四】固定翼新锁定的扫描圆心：**她乘坐的载具**（取不到才回落到她）。
     * 与 maid-frontline 同口径：固定翼会带着主人飞出几百格，战斗发生在机头前方，以载具为圆心
     * 才是"这架飞机能打到的范围"。非固定翼一律回她自己（既有口径不变）。
     */
    private static net.minecraft.world.entity.Entity scanCenter(EntityMaid maid, boolean aircraft) {
        if (aircraft) {
            try {
                net.minecraft.world.entity.Entity v = maid.m_20202_();
                if (v != null && v.m_9236_() == maid.m_9236_()) {
                    return v;
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            }
        }
        return maid;
    }

    /** 固定翼的维持半径平方：A-10 900 格，其余 576 格（见 {@link #AIRCRAFT_HOLD_RANGE}）。 */
    private static double aircraftHoldRangeSqr(EntityMaid maid) {
        try {
            net.minecraft.world.entity.Entity v = maid.m_20202_();
            return v != null && MaidMountCompat.isA10(v) ? A10_HOLD_RANGE_SQR : AIRCRAFT_HOLD_RANGE_SQR;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return AIRCRAFT_HOLD_RANGE_SQR;
        }
    }

    /** 两个实体的**水平**距离平方（x/z 两轴，忽略 y）——固定翼的度量，见 {@link #AIRCRAFT_RANGE}。 */
    private static double horizontalDistSqr(net.minecraft.world.entity.Entity a,
                                            net.minecraft.world.entity.Entity b) {
        double dx = a.m_20185_() - b.m_20185_();
        double dz = a.m_20189_() - b.m_20189_();
        return dx * dx + dz * dz;
    }

    private static LivingEntity lockedOf(java.util.UUID id) {
        java.lang.ref.WeakReference<LivingEntity> ref = LOCKED.get(id);
        return ref == null ? null : ref.get();
    }

    /**
     * 该目标是否还值得**继续锁定**（维持判据，比新锁定少一条视线）。
     * 存活 / 同维度（跨维度她过不去，交给调用方收尾）/ {@code maid.canAttack(e)}（排除玩家/
     * 盔甲架/宠物/村民，中立生物记仇，与 TLM 同一口径）/ {@code isWithinRestriction}（有 home/
     * 工作点限制时不越界追）/ 距离 ≤ {@link #HOLD_RANGE}（不是发现用的 {@link #RANGE}；固定翼
     * 这一档改用水平距离，见 {@link #AIRCRAFT_RANGE}）/ {@code FriendlyFireGuard.isFriendly}
     * （主人/同主女仆/友军双保险）。
     */
    private static boolean keepable(EntityMaid maid, LivingEntity e, boolean aircraft) {
        try {
            if (e == null || !e.m_6084_() || e.m_9236_() != maid.m_9236_()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, e)) {
                return false;
            }
            // 【实测七百五十七】改用 LiveThreat（带时效）：TLM 的 canAttack 对中立生物是**永久记仇**
            // ——主人很久以前打过一下的马，永远过 canAttack，50 格索敌就会永远把它当猎物
            // （玩家实测：车上的女仆对着自家的马开炮）。详见 {@link LiveThreat}。
            if (!LiveThreat.live(maid, e)) {
                return false;
            }
            // 【实测一千零五十八】固定翼**不看 home/工作点限制**：它的交战包络已经是
            // "以载具为圆心、水平 384 发现 / 576 维持（A-10 900）"，外面还有主人 450 格的
            // 接敌绳；再叠一层通常只有几十格的 home 限制，结果就是"刚锁上、一飞出家门就丢锁"。
            // 实机日志正是这样：索敌器说"没有活敌人"（限制判 false），brain 里却还留着
            // 400+ 格外的 boss，旧版又从 brain 捡回来继续开火。其它坐骑/地面载具口径不变。
            // 【R1083】home 模式下**停在原地的载具**仍要能开火：它不再移动，只受武器射程
            // 约束；此时放行 home 限制（否则家圈外的敌人一律不打，玩家看到的就是"守家时
            // 载具停止攻击"）。
            boolean homeVehicle = !aircraft && isVehicleMountRider(maid)
                    && maid.isHomeModeEnable();
            if (!aircraft && !homeVehicle && !maid.m_21444_(e.m_20183_())) {
                return false;
            }
            // 【实测七百八十五 → 一千零五十四】固定翼按**水平**距离、以载具为圆心维持，
            // 半径 576 格（A-10 900）；高度差不该算进"还愿不愿意打"的预算。
            if (aircraft) {
                return horizontalDistSqr(scanCenter(maid, true), e) <= aircraftHoldRangeSqr(maid);
            }
            return maid.m_20280_(e) <= HOLD_RANGE_SQR;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return false;
        }
    }

    /**
     * R1125：主人此刻的"仇人"——优先"正在攻击主人"的那一只，其次"主人正在攻击"的那一只；
     * 都只在最近 {@link #OWNER_THREAT_TICKS} 内有效，且必须同维度、存活。
     */
    private static LivingEntity ownerThreat(EntityMaid maid) {
        try {
            LivingEntity owner = maid.m_269323_();
            if (owner == null || !owner.m_6084_() || owner.m_9236_() != maid.m_9236_()) {
                return null;
            }
            long now = owner.f_19797_;
            LivingEntity attacker = owner.m_21188_();
            if (attacker != null && attacker.m_6084_() && attacker.m_9236_() == maid.m_9236_()
                    && now - owner.m_21213_() <= OWNER_THREAT_TICKS) {
                return attacker;
            }
            LivingEntity prey = owner.m_21214_();
            if (prey != null && prey.m_6084_() && prey.m_9236_() == maid.m_9236_()
                    && now - owner.m_21215_() <= OWNER_THREAT_TICKS) {
                return prey;
            }
            return null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return null;
        }
    }

    /** brain 里现有的目标（ATTACK_TARGET 优先，退回 Mob.getTarget） */
    private static LivingEntity brainTarget(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
            if (mem.isPresent() && mem.get().m_6084_()) {
                return mem.get();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
        }
        LivingEntity t = maid.m_5448_();
        return t != null && t.m_6084_() ? t : null;
    }

    /**
     * 扫半径那么多格，取最近的合法目标（新锁定用，**含视线门**）。
     * 两步筛：先 AABB 膨胀粗筛（三轴独立膨胀 = 边长两倍半径的立方盒，比球宽松，斜角会多捞些），
     * 再用距离平方精筛，非固定翼的实际生效范围正好是"以她为圆心、半径 50 格"。
     * 固定翼三处不同（见 {@link #AIRCRAFT_RANGE}）：圆心取载具（{@link #scanCenter}）、精筛用水平
     * 距离、半径 384 格；直升机 / 飞艇 / 扫帚 / 地面载具仍是以她自身为圆心的 50 格球。
     */
    private static LivingEntity scan(EntityMaid maid, boolean aircraft) {
        try {
            net.minecraft.world.entity.Entity center = scanCenter(maid, aircraft);
            double range = aircraft ? AIRCRAFT_RANGE : RANGE;
            double rangeSqr = aircraft ? AIRCRAFT_RANGE_SQR : RANGE_SQR;
            net.minecraft.world.phys.AABB box = center.m_20191_().m_82377_(range, range, range);
            LivingEntity best = null;
            double bestSqr = Double.MAX_VALUE;
            for (LivingEntity e : maid.m_9236_().m_6443_(LivingEntity.class, box, x -> true)) {
                if (e == maid || e == center || !e.m_6084_()) {
                    continue;
                }
                if (FriendlyFireGuard.isFriendly(maid, e)) {
                    continue; // 主人 / 同主女仆 / 友军
                }
                // 【实测七百五十七】类型/记仇口径改走 LiveThreat（带 5 秒窗口）——见类内注释。
                if (!LiveThreat.live(maid, e)) {
                    continue;
                }
                // 固定翼的 home 限制已由 384/576 交战包络 + 主人 450 格接敌绳替代，
                // 见 keepable 的说明；其它载具仍按活动范围筛。
                // 【R1083】home 模式下停住的载具只受武器射程约束，见 keepable。
                boolean homeVehicle = !aircraft && isVehicleMountRider(maid)
                        && maid.isHomeModeEnable();
                if (!aircraft && !homeVehicle && !maid.m_21444_(e.m_20183_())) {
                    continue; // 活动范围
                }
                // 【实测七百八十五 → 一千零五十四】固定翼按**水平**距离精筛（她比敌人高 40+ 格，
                // 3D 球会把水平窗口压得很小；且圆心已换成载具、半径 384）。
                double d = aircraft ? horizontalDistSqr(center, e) : maid.m_20280_(e);
                if (d > rangeSqr) {
                    continue; // 精筛成球（固定翼：水平圆）
                }
                // 视线门：新锁定必须过这道 raycast（{@code SelfPreservationBehavior.hasSight}，"隔墙不出手"）。
                // 不能用 {@code Sensor.isEntityAttackable}——反编译实证它内含 {@code range(16.0)}
                // （TargetingConditions 里 {@code d > (16*visibility)²} 即 false），真正卡的是"16 格以内"，
                // 视线只是顺带；于是 50 格扫出的候选会在 16 格这一关被全数否决（日志 候选=3→可攻击=1→
                // 有视线=0→选中=-），她一旦被起飞弧线推到 16 格外就再也拿不回目标。hasSight 是纯 raycast
                // （{@code ClipContext.Block.COLLIDER}，中心点上抬 1.2 格）、无距离上限，与自保近战同一方法。
                if (!SelfPreservationBehavior.hasSight(maid, e)) {
                    continue;
                }
                if (d < bestSqr) {
                    bestSqr = d;
                    best = e;
                }
            }
            return best;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
            return null;
        }
    }

    /**
     * 把目标写回 brain（每 tick 调用 = 抵抗 TLM 那边的擦除）。
     * 两件一起写、缺一不可：{@code ATTACK_TARGET}（原版/TLM 攻击行为与空袭行为都读它）与
     * {@code LOOK_TARGET}（{@code LookAtTargetSink} 在 CORE 组任何活动都跑，不写就"身体朝一边、
     * 眼睛看另一边"）。{@code EntityTracker} 是 {@code PositionTracker} 子类，与
     * {@code StartAttacking} 的配套写法同款。
     */
    private static void writeBrain(EntityMaid maid, LivingEntity target) {
        try {
            maid.m_6274_().m_21886_(MemoryModuleType.f_26372_, java.util.Optional.of(target));
            maid.m_6274_().m_21886_(MemoryModuleType.f_26371_,
                    java.util.Optional.of(new EntityTracker(target, true)));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
        }
    }

    /**
     * 把 brain 里的攻击/注视目标清空（只对固定翼用，见 {@link #resolve} ③/④）。
     * <p>固定翼的开火目标现在统一走 {@link #resolve}，brain 只作为 TLM 行为与卓越前线
     * "Mob 乘客自动开火"的兼容出口；索敌器已经判"没有目标"时若还留着旧值，那两路会继续
     * 用旧目标开火。{@code Optional.empty()} 在 1.20.1/1.21.1 的 {@code Brain.setMemory}
     * 里都等价于 erase，两树同名写法一致。
     */
    private static void eraseBrain(EntityMaid maid) {
        try {
            maid.m_6274_().m_21886_(MemoryModuleType.f_26372_, java.util.Optional.empty());
            maid.m_6274_().m_21886_(MemoryModuleType.f_26371_, java.util.Optional.empty());
            maid.m_6710_(null);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
        }
    }

    /**
     * 【实测七百八十五】固定翼"锁定 / 没锁定"打一行日志（节流 5 秒/女仆），搜「固定翼索敌」。
     *
     * <p>只服务排查：时，这一行直接给出**圆心是主人还是自身**与
     * **水平距离**——不写的话只能从巡航档那行"距目标/高差"反推（而且那两列的坐标系容易看错）。
     * 非固定翼一档一个字都不打（本模组其它索敌口径零噪音）。异常一律吞（日志绝不影响索敌）。
     */
    private static void logAircraft(EntityMaid maid, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = AIRCRAFT_LOG_AT.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            AIRCRAFT_LOG_AT.put(maid.m_20148_(), now);
            if (AIRCRAFT_LOG_AT.size() > 256) {
                AIRCRAFT_LOG_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("固定翼索敌",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " " + msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightTargeting", ignored);
        }
    }
}
