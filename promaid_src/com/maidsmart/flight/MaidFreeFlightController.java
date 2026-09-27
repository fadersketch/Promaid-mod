package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 控制器】——让女仆悬浮并自由升降（创造模式飞行的手感）。
 *
 * 【这一步解决什么】整合包里给"创造飞行/悬浮"的手段五花八门（饰品、护甲套装、药水效果……），
 * 而它们几乎全部只对**玩家**生效；女仆拿到手上一字不动。本控制器不去复刻每个物品的物理，而是：
 * **只要她有资格（见 {@link MaidFreeFlightKit}），我们就托住她**——
 * {@code setNoGravity(true)} + 每 tick 直接给速度，形成"悬停 + 平滑位移"的创造飞行手感。
 *
 * 【为什么挂在事件上而不是 brain 行为】最初写的是 core 行为（priority 40，与其它附加行为同一条
 * 注册链）。现场诊断显示：行为**被实例化了**，但它的 {@code checkExtraStartConditions} **从来没有
 * 被调用过**。这里先用 {@code MaidTickEvent} 事件驱动实现同一套逻辑：不依赖大脑调度。
 * （TLM 的 {@code MaidTickEvent} 在 {@code EntityMaid.tick()} 里**早于** brain tick 发出，
 * 所以我们的速度设置在原版 travel 之前生效。）
 *
 * 【飞行力学：一阶，绝不累加】转向照抄扫帚模式那套已实测的公式
 * （{@code speed = min(1, dist / 1.5)}，分量限速 0.35/0.22，{@code dist < 0.35} 直接零速悬停）：速度
 * **直接由距离算**，不做任何累加——一旦接管了 travel（没有原版 0.91 阻力），增量式推进就是无阻尼
 * 谐振子，表现为"升空后空中上下摆动"。一阶系统数学上不可能振荡。
 *
 * 【安全底线】收工时若她还在半空 → 先"软着陆"（保持无重力、缓慢下降），落地或超时才交还重力；
 * 异常也保持悬停，绝不因为一次异常摔死她（她只有 20 血）。
 *
 * ── 1.20.1 精简版与 1.21.1 的差异（逐条对照，只有三处）──
 * <ol>
 *   <li><b>资格探测只有两路</b>：重力归零启发式（没有 {@code Attributes.GRAVITY}）与数据组件两路
 *       （1.20.5+ 才有）已砍，见 {@link MaidFreeFlightKit} 的类注释；</li>
 *   <li><b>名字是 SRG</b>：本树手工编译、无 refmap，原版成员一律用运行时名（{@code m_xxxxx_}、
 *       {@code f_xxxxx_}）；TLM 自己的方法保持可读名（如 {@code isMaidInSittingPose}）；</li>
 *   <li><b>Forge 的持久数据是 {@code maid.getPersistentData()} 的默认方法</b>（readable，无需强转）。</li>
 * </ol>
 * 其余（状态机、软着陆、智能待命、朝向 QoL、战斗档、赶路档、"在主人身边干活"、表寿命回收、遗留
 * 无重力交还）与 1.21.1 树逐字一致——它们全是纯数学与事件驱动，不碰版本差异。
 */
public final class MaidFreeFlightController {

    private MaidFreeFlightController() {
    }

    /** 软着陆相位（UUID → 开始时刻） */
    private static final Map<UUID, Long> SOFT_LAND_START = new HashMap<>();
    /** 调试目标（/maid_smart freeflight_goto）：UUID → 坐标 */
    private static final Map<UUID, Vec3> DEBUG_TARGET = new HashMap<>();

    /** 与主人保持的水平距离（格）——与扫帚模式同口径 */
    private static final double FOLLOW_DIST = 3.5;
    /** 与主人保持的高度（格，相对主人脚下） */
    private static final double FOLLOW_HEIGHT = 2.0;
    /** 软着陆"提速线"（秒）：过了这么久她还在下（说明飞得太高），把下降速度提到 FAST_SPEED */
    private static final int SOFT_LAND_FAST_AFTER_SECONDS = 20;
    /** 提速之后的下降速度（格/tick）：0.48 ≈ 9.6 格/秒，从 300 格高下来约半分钟 */
    private static final double SOFT_LAND_FAST_SPEED = 0.48;
    /** 软着陆下降速度（格/tick）：0.12 ≈ 2.4 格/秒，稳稳落地 */
    private static final double SOFT_LAND_SPEED = 0.12;

    /* ---------------- 朝向：倒退着飞 + 延迟转身 + 平滑转体 ---------------- */

    /** 当前平滑后的朝向（UUID → yaw）——转体是逐 tick 转出来的，不是瞬切 */
    private static final Map<UUID, Float> YAW = new HashMap<>();
    /** 持续"背对主人倒退"的计时（UUID → tick）——攒够 {@link #RETREAT_FLIP_TICKS} 才转身朝前 */
    private static final Map<UUID, Integer> RETREAT = new HashMap<>();
    /** 每 tick 最多转多少度：12° → 180° 用 15 tick（0.75 秒）转完 */
    private static final float YAW_STEP = 12.0f;
    /** 倒退多少 tick 之后才转身朝前（60 = 3 秒） */
    private static final int RETREAT_FLIP_TICKS = 60;
    /** 悬停判定的水平速度（格/tick）：低于它视为"停着"，面向主人 */
    private static final double HOVER_SPEED = 0.055;

    /* ---------------- 手动触发 ---------------- */

    public static void setDebugTarget(EntityMaid maid, Vec3 pos) {
        DEBUG_TARGET.put(maid.m_20148_(), pos);
    }

    public static void clearDebugTarget(EntityMaid maid) {
        DEBUG_TARGET.remove(maid.m_20148_());
    }

    public static boolean isFlying(EntityMaid maid) {
        return maid != null && STATE.getOrDefault(maid.m_20148_(), ST_OFF) == ST_FLYING;
    }

    /**
     * 我们此刻是否**正在控制她的移动**（飞行中 / 软着陆中）。
     *
     * 【给"走位型"链路让位用】搭路（{@code BridgeUpBehavior}）是一边铺方块一边用走路的 move
     * 驱动她，与我们的飞行抢移动。作者给"飞行跟随"已经加过同款让位，这里照同一套写法给创造飞行补上。
     * 注意**不含"落地待命"档**（STANDBY）：那一档她已经落地、由 TLM 正常跟随，搭路该照常工作。
     */
    public static boolean isControlling(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        int st = STATE.getOrDefault(maid.m_20148_(), ST_OFF);
        return st == ST_FLYING || st == ST_SOFT_LAND;
    }

    /**
     * 【有创造飞行能力就禁用搭路（需求方口径）】需求方原话："既然引入了创造飞行这个概念，那么它
     * 无疑是搭方块的上位替代，有创造飞行能力，那就禁用搭路。"
     *
     * <p>与 {@link #isControlling} 的区别就是"能力"与"正在飞"。这里问的是"她此刻**具备**飞行能力"
     * （总开关开 + 没被单独关掉 + 资格物品/效果其一），**不含**"此刻是否在空中"——落地待命档同样不搭。
     */
    public static boolean bridgeDisabled(EntityMaid maid) {
        try {
            return maid != null
                    && MaidFreeFlightKit.isModeActive(maid)
                    && MaidFreeFlightFlags.effective(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 能不能飞（命令校验与主循环共用同一口径） */
    public static boolean canFly(EntityMaid maid) {
        try {
            return maid != null && maid.m_6084_() && MaidFreeFlightKit.isModeActive(maid)
                    && MaidFreeFlightFlags.effective(maid) && !blocked(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 一行可读的状态（命令/日志用） */
    public static String status(EntityMaid maid) {
        try {
            return (isFlying(maid) ? "飞行中" : "未起飞") + "；" + MaidFreeFlightKit.diag(maid)
                    + "；骑乘=" + maid.m_20159_() + " 坐姿=" + maid.isMaidInSittingPose()
                    + " 可动=" + maid.canBrainMoving() + " 守家=" + maid.isHomeModeEnable()
                    + " 飞行任务=" + com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)
                    + " 扫帚任务=" + com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        } catch (Throwable t) {
            return "状态异常：" + t;
        }
    }

    private static boolean blocked(EntityMaid maid) {
        try {
            if (maid.m_20159_() || maid.isMaidInSittingPose() || maid.m_5803_()) {
                return true;
            }
            if (!maid.canBrainMoving() || maid.isHomeModeEnable()) {
                return true;   // 守家：不把她从家里吊出去
            }
            if (com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
                return true;   // 空袭/远战自己管飞行
            }
            return com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ---------------- 实测：本来要走过去的活，交给飞 ---------------- */

    /**
     * 走位请求表（UUID → {x, y, z, 记下的世界刻}）——由 {@link #takeOverWalk} 写入，
     * 由 {@link #travelAim} 读取（过期即摘）。
     */
    private static final Map<UUID, double[]> TRAVEL = new HashMap<>();
    /** 这趟飞行是"赶去干活"而不是"跟主人"（UUID 集合） */
    private static final java.util.Set<UUID> TRAVEL_FLIGHT = new java.util.HashSet<>();
    /** 「赶路」那条日志的限频（UUID → 上次记的世界刻）：同一只 5 秒最多一条 */
    private static final Map<UUID, Long> TRAVEL_LOGGED = new HashMap<>();
    /** 走位请求的新鲜期（tick）：这么久没有新请求 = 她不再需要移动 */
    private static final int TRAVEL_TTL = 20;
    /** 竖直差超过这么多格也算"走过去费劲"（上坡 / 上天 / 跨沟） */
    private static final double TRAVEL_RISE = 2.0;
    /** 计入"到了"的水平 / 竖直半径（格）：进了它就落下去干活 */
    private static final double TRAVEL_ARRIVE = 1.5;
    /** 落地前要求目标格下方这么多格内有实地（没有就**完全不接管**） */
    private static final int TRAVEL_LANDING_SCAN = 24;
    /** "在主人身边干活"的水平半径（格） */
    private static final double WORK_NEAR_OWNER = 16.0;
    /** "在主人身边干活"的竖直上限（格） */
    private static final double WORK_NEAR_OWNER_Y = 8.0;

    /**
     * 【"本来要走路过去的活"也交给仿创造飞行】需求方口径（原话）："本来就走过去应该交给创造。"
     *
     * <p>调用点：{@code FreeFlightWalkGuardMixin}（{@code PathNavigation.moveTo(DDDD)Z} 的 HEAD，
     * = 挖矿 / 伐木 / 农活这类**直连寻路**）。返回 true = 这一发地面寻路被接管。
     *
     * <p>接管条件（四条全中）：①她有飞行资格；②开关开着且她在非战斗的工作任务上；③这一格确实够
     * "费劲"（水平超 {@code freeFlightTravelDist} 或高差超 2 格）；④那一格底下落得下去。
     */
    public static boolean takeOverWalk(EntityMaid maid, double x, double y, double z) {
        try {
            if (maid == null || maid.m_9236_().m_5776_()) {
                return false;
            }
            if (!walkTravelOn() || !canFly(maid) || !walkTravelAllowed(maid)) {
                return false;
            }
            UUID id = maid.m_20148_();
            double dx = x - maid.m_20185_();
            double dz = z - maid.m_20189_();
            double hd = Math.sqrt(dx * dx + dz * dz);
            double vd = Math.abs(y - maid.m_20186_());
            if (!TRAVEL_FLIGHT.contains(id) && hd <= walkTravelDist() && vd <= TRAVEL_RISE) {
                return false;
            }
            if (!landableAt(maid, x, y, z)) {
                return false;
            }
            TRAVEL.put(id, new double[]{x, y, z, (double) maid.m_9236_().m_46467_()});
            logTravel(maid, hd, vd);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean walkTravelOn() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double walkTravelDist() {
        try {
            return Math.max(0.0, com.maidsmart.config.MaidSmartConfig.MISC_FREE_FLIGHT_TRAVEL_DIST.get());
        } catch (Throwable ignored) {
            return 8.0;
        }
    }

    /** 这只女仆的直连寻路该不该被我们接管——**只认"本来走过去干活"这一类** */
    private static boolean walkTravelAllowed(EntityMaid maid) {
        try {
            if (com.maidsmart.combat.SelfPreservationBehavior.isSelfPreserving(maid)) {
                return false;
            }
            if (com.maidsmart.task.MaidWorkTags.isAttackTask(maid)) {
                return false;
            }
            if (!com.maidsmart.task.MaidWorkTags.isNonCombatWork(maid)) {
                return false;
            }
            if (com.maidsmart.task.MaidWorkTags.isSpawnerTorchRun(maid)) {
                return false;
            }
            return !com.maidsmart.task.MaidWorkTags.isStill(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 那一格底下有没有能站的地方（水 / 岩浆 / 虚空上方都不算） */
    private static boolean landableAt(EntityMaid maid, double x, double y, double z) {
        try {
            net.minecraft.core.BlockPos from =
                    net.minecraft.core.BlockPos.m_274561_(x, y + 1.0, z);
            return findGroundBelow(maid.m_9236_(), from, TRAVEL_LANDING_SCAN) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这只的走位请求还新鲜吗 */
    private static boolean travelFresh(EntityMaid maid) {
        double[] t = TRAVEL.get(maid.m_20148_());
        if (t == null) {
            return false;
        }
        try {
            return maid.m_9236_().m_46467_() - (long) t[3] <= TRAVEL_TTL;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前这一拍要飞过去的走位目标（过期即摘并返回 null） */
    private static Vec3 travelAim(EntityMaid maid) {
        double[] t = TRAVEL.get(maid.m_20148_());
        if (t == null) {
            return null;
        }
        if (!travelFresh(maid)) {
            TRAVEL.remove(maid.m_20148_());
            return null;
        }
        return new Vec3(t[0], t[1], t[2]);
    }

    /** 赶路这一趟作废（落水 / 能力没了 / 已到达） */
    private static void travelDone(EntityMaid maid) {
        try {
            UUID id = maid.m_20148_();
            TRAVEL.remove(id);
            TRAVEL_FLIGHT.remove(id);
        } catch (Throwable ignored) {
        }
    }

    /** 因为"要去干活"而起飞（标记这趟的身份） */
    private static void takeOffTravel(EntityMaid maid) {
        TRAVEL_FLIGHT.add(maid.m_20148_());
        takeOff(maid, "起飞赶去干活的地方");
    }

    /** 【赶路飞行】朝她自己的走位目标飞，到了就落下去把活还给她 */
    private static void flyTravel(EntityMaid maid, UUID id, Vec3 aim) {
        double dx = aim.f_82479_ - maid.m_20185_();
        double dz = aim.f_82481_ - maid.m_20189_();
        double hd = Math.sqrt(dx * dx + dz * dz);
        double vd = Math.abs(aim.f_82480_ - maid.m_20186_());
        if (hd <= TRAVEL_ARRIVE && vd <= TRAVEL_ARRIVE) {
            travelDone(maid);
            beginSoftLanding(maid, "到地方了，落下来干活", ST_OFF);
            return;
        }
        steer(maid, aim, true, null);
        maid.m_20242_(true);
        suppressWalk(maid);
    }

    private static void logTravel(EntityMaid maid, double hd, double vd) {
        try {
            UUID id = maid.m_20148_();
            long now = maid.m_9236_().m_46467_();
            Long at = TRAVEL_LOGGED.get(id);
            if (at != null && now - at < 100) {
                return;
            }
            TRAVEL_LOGGED.put(id, now);
            PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid)
                    + " 赶路：飞去她本来要走过去的地方（直线 " + String.format("%.1f", hd)
                    + " 格 / 高差 " + String.format("%.1f", vd) + " 格）——有飞行能力就不走路、也不搭路");
        } catch (Throwable ignored) {
        }
    }

    /** "她正在主人身边干活"——这时候飞行层只负责把她送到活上，**不负责把她拽向主人** */
    private static boolean workingNearOwner(EntityMaid maid, LivingEntity owner) {
        try {
            if (owner == null || !com.maidsmart.task.MaidWorkTags.isNonCombatWork(maid)) {
                return false;
            }
            return horizontalDist(maid, owner) <= WORK_NEAR_OWNER
                    && Math.abs(maid.m_20186_() - owner.m_20186_()) <= WORK_NEAR_OWNER_Y;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 状态机 ---------------- */

    /** 状态：0=未接管 1=飞行中 2=软着陆 3=落地待命 */
    private static final Map<UUID, Integer> STATE = new HashMap<>();
    /** 软着陆之后的去向（0=收工 3=落地待命） */
    private static final Map<UUID, Integer> LAND_GOAL = new HashMap<>();
    /** 主人上一次的水平位置（判断"主人在不在动"） */
    private static final Map<UUID, double[]> OWNER_LAST = new HashMap<>();
    /** 主人已连续静止的 tick 数 */
    private static final Map<UUID, Integer> OWNER_STILL = new HashMap<>();

    /**
     * 替代主人（专用服务器验收入口）——照上游「飞行跟随」的 {@code /maid_smart flyfollow} 先例。
     * TLM 的 {@code getOwner()} 走 PlayerList，专用服务器上没有玩家就恒为 null。键用弱引用，女仆卸载即回收。
     */
    private static final Map<EntityMaid, LivingEntity> SUB_OWNER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void setSubstituteOwner(EntityMaid maid, LivingEntity target) {
        if (maid != null && target != null) {
            SUB_OWNER.put(maid, target);
            OWNER_STILL.remove(maid.m_20148_());
            OWNER_LAST.remove(maid.m_20148_());
        }
    }

    public static void clearSubstituteOwner(EntityMaid maid) {
        if (maid != null) {
            SUB_OWNER.remove(maid);
        }
    }

    /** 目标来源：替代主人优先，否则她的真主人 */
    private static LivingEntity resolveOwner(EntityMaid maid) {
        try {
            LivingEntity sub = SUB_OWNER.get(maid);
            if (sub != null && sub.m_6084_() && sub.m_9236_() == maid.m_9236_()) {
                return sub;
            }
        } catch (Throwable ignored) {
        }
        return maid.m_269323_();
    }

    /** 进入"落地待命"的时刻（tick）——落地后留一秒落稳宽限，避免刚站住就被判"她在半空"再次起飞 */
    private static final Map<UUID, Long> STANDBY_SINCE = new HashMap<>();
    /** "为什么还没落地"的诊断限频 */
    private static final Map<UUID, String> NO_LAND_LAST = new HashMap<>();
    private static final Map<UUID, Long> NO_LAND_AT = new HashMap<>();
    /** 软着陆"提速"那条日志只记一次 */
    private static final Map<UUID, Boolean> FAST_LOGGED = new HashMap<>();

    /* ---------------- 状态表的寿命 ---------------- */

    /** 最后一次看到她的世界刻（刻意不用 tickCount：它随实体重登归零，跨实体比大小会算错） */
    private static final Map<UUID, Long> LAST_SEEN = new HashMap<>();
    /** 多久没见到她就把她的键全部回收（刻）：6000 = 5 分钟 */
    private static final long SEEN_TTL = 6000L;
    private static long lastPurge = 0L;

    private static void touch(UUID id, EntityMaid maid) {
        try {
            long now = maid.m_9236_().m_46467_();
            LAST_SEEN.put(id, now);
            if (now - lastPurge > SEEN_TTL) {
                lastPurge = now;
                purge(now);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 超过 SEEN_TTL 没露面的键，从所有表里一起删掉（服务器上跑一天不会攒几千个永不回收的键） */
    private static void purge(long now) {
        try {
            java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = LAST_SEEN.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<UUID, Long> e = it.next();
                if (now - e.getValue() <= SEEN_TTL) {
                    continue;   // 还在
                }
                UUID id = e.getKey();
                it.remove();
                STATE.remove(id);
                LAND_GOAL.remove(id);
                SOFT_LAND_START.remove(id);
                OWNER_LAST.remove(id);
                OWNER_STILL.remove(id);
                STANDBY_SINCE.remove(id);
                NO_LAND_LAST.remove(id);
                NO_LAND_AT.remove(id);
                FAST_LOGGED.remove(id);
                YAW.remove(id);
                RETREAT.remove(id);
                DEBUG_TARGET.remove(id);
                TRAVEL.remove(id);
                TRAVEL_FLIGHT.remove(id);
                TRAVEL_LOGGED.remove(id);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 遗留的无重力 ---------------- */

    /** 我们自己写的"她此刻由我们托着"标记（persistentData，随实体存盘） */
    private static final String PERSIST_AIRBORNE = "maid_smart_free_flight_airborne";

    private static void markAirborne(EntityMaid maid, boolean on) {
        try {
            maid.getPersistentData().m_128379_(PERSIST_AIRBORNE, on);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【把上一局遗留的无重力交还回去】{@code Entity#noGravity} 是**跟着实体 NBT 存盘**的，
     * 而本类的状态表只活在内存里。她若在飞行中存档退出 / 被卸载，下次进来 STATE 是空的、
     * 谁也不认领她——旧版就会"她带着 NoGravity 永远飘在那儿，叫不下来"。
     *
     * <p>判据用 **persistentData 里我们自己写的那枚标记**（同样随实体存盘）：只管得住"我们留下的
     * 无重力"，绝不会误伤空袭/扫帚或别的代码设的无重力。
     */
    private static void reconcileGravity(EntityMaid maid) {
        try {
            if (STATE.containsKey(maid.m_20148_())) {
                return;   // 这一局正由我们管着，不插手
            }
            CompoundTag data = maid.getPersistentData();
            if (!data.m_128471_(PERSIST_AIRBORNE)) {
                return;
            }
            data.m_128379_(PERSIST_AIRBORNE, false);
            if (maid.m_20068_()) {
                maid.m_20242_(false);
            }
            PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid)
                    + " 交还重力（上次存档/卸载时她还在飞，重载后不该继续飘着）");
        } catch (Throwable ignored) {
        }
    }

    private static final int ST_OFF = 0;
    private static final int ST_FLYING = 1;
    private static final int ST_SOFT_LAND = 2;
    private static final int ST_STANDBY = 3;
    /** 起飞判定用的"被拉开"距离（格）。与落地判定的 8 格形成**滞回** */
    private static final double TAKEOFF_DIST = 6.0;
    /** 判定"主人在动"的水平位移阈值（格/tick）：0.03 ≈ 0.6 格/秒 */
    private static final double OWNER_MOVE_EPS = 0.03;

    public static void tick(EntityMaid maid) {
        try {
            // 【先挡客户端】MaidTickEvent 两侧都会发（EntityMaid.tick 在客户端也跑），而本方法
            // 从头到尾都是**服务端写操作**：setNoGravity、每 tick 写速度、清 brain 记忆、停导航。
            // 放在客户端跑 = 每 tick 和同步下来的位置/速度打架。所以这里是这条链唯一的分侧口。
            if (maid == null || maid.m_9236_().m_5776_()) {
                return;
            }
            UUID id = maid.m_20148_();
            reconcileGravity(maid);
            if (!maid.m_6084_()) {
                release(maid, "她没了");
                return;
            }
            touch(id, maid);
            int st = STATE.getOrDefault(id, ST_OFF);
            LivingEntity owner = resolveOwner(maid);
            boolean ownerHere = owner != null && owner.m_6084_() && maid.m_9236_() == owner.m_9236_();
            boolean allowed = canFly(maid);
            // 主人轨迹只在"她真的可能飞"时才记：旧版无条件记录，于是**总开关默认关**时全服女仆
            // 也会每 tick 往 OWNER_LAST 里塞坐标——纯浪费，且女仆一多就是几千个键永不回收。
            boolean ownerMoving = allowed && ownerHere && trackOwnerMoving(maid, owner);
            if (!allowed) {
                OWNER_LAST.remove(id);
                OWNER_STILL.remove(id);
                TRAVEL.remove(id);
                TRAVEL_FLIGHT.remove(id);
            }
            // 战斗意图优先：有敌人时不要把她往主人身边拉
            LivingEntity enemy = allowed ? combatTarget(maid) : null;
            boolean enemyTooFar = enemy != null && horizontalDist(maid, enemy) > 6.0;
            Vec3 travel = allowed ? travelAim(maid) : null;

            // ① 软着陆相位：恒速下降（保持无重力）
            if (st == ST_SOFT_LAND) {
                tickSoftLand(maid, ownerHere ? owner : null);
                return;
            }
            // ② 落地待命：这一档她就是个普通女仆（交给 TLM 跟随），只在"该起飞"时接管
            if (st == ST_STANDBY) {
                if (!allowed) {
                    forget(maid);
                    return;
                }
                if (enemyTooFar) {
                    takeOff(maid, "起飞追击敌人");
                    return;
                }
                if (travel != null) {
                    takeOffTravel(maid);
                    return;
                }
                if (shouldTakeOff(maid, ownerHere ? owner : null, ownerMoving)) {
                    takeOff(maid, "重新起飞");
                }
                return;
            }
            // ③ 飞行中
            if (st == ST_FLYING) {
                if (!allowed || maid.m_20069_() || maid.m_20077_()) {
                    travelDone(maid);
                    beginSoftLanding(maid, maid.m_20069_() || maid.m_20077_() ? "落水/岩浆" : "能力消失或状态变化", ST_OFF);
                    return;
                }
                if (enemy != null) {
                    combatFly(maid, enemy);
                    return;
                }
                if (travel != null) {
                    flyTravel(maid, id, travel);
                    return;
                }
                if (TRAVEL_FLIGHT.remove(id)) {
                    beginSoftLanding(maid, "赶路结束（她不再要移动了）", ST_OFF);
                    return;
                }
                if (shouldLandAndWait(maid, ownerHere ? owner : null, ownerMoving)) {
                    beginSoftLanding(maid, "主人停下来了，落地待命", ST_STANDBY);
                    return;
                }
                flyTick(maid, id, ownerHere ? owner : null);
                return;
            }
            // ④ 未接管：够资格 + （要追敌人 / 要去干活 / 该起飞）→ 起飞
            if (allowed && travel != null) {
                takeOffTravel(maid);
                return;
            }
            if (allowed && (enemyTooFar || shouldTakeOff(maid, ownerHere ? owner : null, ownerMoving))) {
                takeOff(maid, enemyTooFar ? "起飞追击敌人" : "起飞");
            }
        } catch (Throwable t) {
            // 异常兜底：只在"飞行中"才强行保持悬停；落地待命时就当她不存在
            try {
                int st = STATE.getOrDefault(maid.m_20148_(), ST_OFF);
                if (st == ST_FLYING || st == ST_SOFT_LAND) {
                    maid.m_20242_(true);
                    maid.m_20256_(Vec3.f_82478_);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void takeOff(EntityMaid maid, String why) {
        try {
            STATE.put(maid.m_20148_(), ST_FLYING);
            maid.m_20242_(true);
            markAirborne(maid, true);
            PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " " + why
                    + "（" + takeOffReason(maid) + "；" + MaidFreeFlightKit.diag(maid) + "）");
        } catch (Throwable ignored) {
        }
    }

    /** 诊断：这一 tick 是**哪一条**起飞条件成立的（"她怎么又起飞了"的唯一自查手段） */
    private static String takeOffReason(EntityMaid maid) {
        try {
            if (!idleMode()) {
                return "始终悬停档";
            }
            if (DEBUG_TARGET.containsKey(maid.m_20148_())) {
                return "坐标档";
            }
            LivingEntity owner = resolveOwner(maid);
            if (owner == null) {
                return "没有主人";
            }
            Long since = STANDBY_SINCE.get(maid.m_20148_());
            if (since != null && maid.f_19797_ - since < 20) {
                return "（宽限内）";
            }
            if (!maid.m_20096_() && (maid.f_19789_ > 0.5 || maid.m_20184_().f_82480_ < -0.12)) {
                return "她在下坠 fall=" + String.format("%.2f", maid.f_19789_)
                        + " vy=" + String.format("%.3f", maid.m_20184_().f_82480_);
            }
            if (!maid.m_20096_()) {
                return "她在半空但没在下坠（onGround=false vy=" + String.format("%.3f", maid.m_20184_().f_82480_) + "）";
            }
            if (OWNER_LAST.containsKey(maid.m_20148_())
                    && trackOwnerMovingSnapshot(maid, owner)) {
                return "主人在动";
            }
            if (horizontalDist(maid, owner) > TAKEOFF_DIST) {
                return "离主人 > " + (int) TAKEOFF_DIST + " 格";
            }
            if (Math.abs(maid.m_20186_() - owner.m_20186_()) > 2.0) {
                return "高差 " + String.format("%.1f", Math.abs(maid.m_20186_() - owner.m_20186_())) + " > 2";
            }
            return "主人可能在空中";
        } catch (Throwable ignored) {
            return "诊断异常";
        }
    }

    /** 只读版的主人移动判定（诊断用，不更新状态表） */
    private static boolean trackOwnerMovingSnapshot(EntityMaid maid, LivingEntity owner) {
        double[] last = OWNER_LAST.get(maid.m_20148_());
        if (last == null) {
            return false;
        }
        double dx = owner.m_20185_() - last[0];
        double dz = owner.m_20189_() - last[1];
        return Math.sqrt(dx * dx + dz * dz) > OWNER_MOVE_EPS;
    }

    /** 飞行中的每 tick：选目标点 → 一阶转向 → 保持无重力 → 抢移动 */
    private static void flyTick(EntityMaid maid, UUID id, LivingEntity owner) {
        Vec3 aim = pickAim(maid);
        Vec3 dbg = DEBUG_TARGET.get(id);
        // 跟随主人的那一档才启用"社交朝向"（倒退/面向主人）；坐标档不参与
        Vec3 ownerPos = (dbg == null && owner != null && owner.m_6084_() && maid.m_9236_() == owner.m_9236_())
                ? owner.m_20182_() : null;
        steer(maid, aim, true, ownerPos);
        maid.m_20242_(true);
        suppressWalk(maid);
        if (dbg != null && maid.m_20182_().m_82554_(dbg) <= 1.0) {
            beginSoftLanding(maid, "已到指定坐标", ST_OFF);
        }
    }

    /**
     * 软着陆：**保持无重力** + 恒速下降 +（若要落地待命）朝主人轻微漂移。
     *
     * 【为什么必须保持无重力】第一版忘了这条：重力一回来她就在软着陆期间加速下坠，
     * 实测 4 秒掉 23 格（等于没做缓冲，20 血的女仆摔一下就没）。每 tick 清零坠落距离，保证"软"到底。
     */
    private static void tickSoftLand(EntityMaid maid, LivingEntity owner) {
        UUID id = maid.m_20148_();
        if (maid.m_20096_() || softLandGiveUp(maid)) {
            boolean timeout = !maid.m_20096_();
            int goal = LAND_GOAL.getOrDefault(id, ST_OFF);
            SOFT_LAND_START.remove(id);
            LAND_GOAL.remove(id);
            if (goal == ST_STANDBY && canFly(maid)) {
                STATE.put(id, ST_STANDBY);
                STANDBY_SINCE.put(id, (long) maid.f_19797_);
                markAirborne(maid, false);
                maid.m_20242_(false);
                maid.m_20334_(0.0, Math.min(0.0, maid.m_20184_().f_82480_), 0.0);
                YAW.remove(id);
                RETREAT.remove(id);
                clearDebugTarget(maid);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 落地待命（贴着你站好）");
            } else {
                release(maid, timeout ? "已到世界底部（放下重力）" : "已着陆");
            }
            return;
        }
        if (softLandFast(maid) && !Boolean.TRUE.equals(FAST_LOGGED.get(id))) {
            FAST_LOGGED.put(id, Boolean.TRUE);   // 只记一次，免得每 tick 刷屏
            PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid)
                    + " 软着陆提速（她已经下了 " + (softLandAge(maid) / 20) + " 秒还没到底，"
                    + "下降速度提到 " + SOFT_LAND_FAST_SPEED + " 格/tick）");
        }
        double down = softLandFast(maid) ? SOFT_LAND_FAST_SPEED : SOFT_LAND_SPEED;
        // 两段式进场：**先飞到他身边**（用飞行速度），贴近了才垂直下降。
        if (owner != null) {
            double dh = horizontalDist(maid, owner);
            if (dh > nearDist() + 0.5) {
                double dx = owner.m_20185_() - maid.m_20185_();
                double dz = owner.m_20189_() - maid.m_20189_();
                double len = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
                Vec3 aim = new Vec3(owner.m_20185_() - dx / len * nearDist(),
                        owner.m_20186_() + 1.0,
                        owner.m_20189_() - dz / len * nearDist());
                steer(maid, aim, true, owner.m_20182_());   // 社交朝向：面向主人倒退着过去
                maid.m_20242_(true);
                maid.m_183634_();
                suppressWalk(maid);
                return;
            }
        }
        maid.m_20242_(true);
        maid.m_20334_(0.0, -down, 0.0);
        maid.f_19812_ = true;
        maid.f_19864_ = true;
        maid.m_183634_();
        suppressWalk(maid);
    }

    /**
     * 该不该起飞（"智能待命"口径）。
     *
     * 旧行为 = 够资格就一直悬着，于是喂食/摸头这类**需要贴身的交互**全都够不到
     * （原版实体交互距离 3 格，而她悬在 3.5 格 + 高 2 格 ⇒ ≈4 格）。
     * 新版：**赶路时飞、你停下来时她落到你脚边待命**。
     */
    private static boolean shouldTakeOff(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        if (!idleMode()) {
            return true;                                  // "始终悬停"档：够资格就飞（旧行为）
        }
        if (DEBUG_TARGET.containsKey(maid.m_20148_())) {
            return true;                                  // 坐标档：命令让她飞，就飞
        }
        if (owner == null) {
            return true;                                  // 没主人：保持原地悬停
        }
        // 两条一起用：**落地后 1 秒宽限** + 只有"真的在下坠"（坠落距离/垂直速度）才算半空
        Long since = STANDBY_SINCE.get(maid.m_20148_());
        if (since != null && maid.f_19797_ - since < 20) {
            return false;
        }
        if (!maid.m_20096_() && (maid.f_19789_ > 0.5 || maid.m_20184_().f_82480_ < -0.12)) {
            return true;
        }
        // "在主人身边干活"时不做**跟随**起飞（否则会出现工位↔主人的往复）
        if (workingNearOwner(maid, owner)) {
            return false;
        }
        if (ownerMoving) {
            return true;
        }
        if (horizontalDist(maid, owner) > TAKEOFF_DIST) {
            return true;
        }
        if (Math.abs(maid.m_20186_() - owner.m_20186_()) > 2.0) {
            return true;
        }
        return ownerAirborne(owner);
    }

    /** 该不该落地待命——判定与"为什么没落地"共用 {@link #noLandReason}（单一事实源） */
    private static boolean shouldLandAndWait(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        String reason = noLandReason(maid, owner, ownerMoving);
        if (reason == null) {
            return true;
        }
        if (!ownerMoving && OWNER_STILL.getOrDefault(maid.m_20148_(), 0) >= idleSeconds() * 20) {
            logNoLand(maid, reason);
        }
        return false;
    }

    private static boolean ownerAirborne(LivingEntity owner) {
        try {
            if (owner.m_21255_()) {
                return true;
            }
            return owner instanceof net.minecraft.world.entity.player.Player p && p.m_150110_().f_35937_;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 落点安全：**从她当前位置往下找**落点，而不是查"她脚下有没有方块"。
     *
     * 【旧版的致命 bug】第一版写成 solidGround(maid.blockPosition())——她**正悬在半空**，
     * 脚下本来就是空气 ⇒ 恒为 false ⇒ **永远落不下来**。
     */
    private static String noLandReason(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        try {
            if (!idleMode()) {
                return "智能待命关着（配置里可开）";
            }
            if (owner == null) {
                return "没有在线主人（专用服务器上 getOwner 恒为 null）";
            }
            if (DEBUG_TARGET.containsKey(maid.m_20148_())) {
                return "坐标档进行中";
            }
            if (ownerMoving) {
                return "主人还在动";
            }
            int still = OWNER_STILL.getOrDefault(maid.m_20148_(), 0);
            if (still < idleSeconds() * 20) {
                return "主人静止 " + (still / 20) + "s（需 " + idleSeconds() + "s）";
            }
            if (ownerAirborne(owner)) {
                return "主人在空中（滑翔/创造飞行）";
            }
            if (maid.m_20069_() || maid.m_20077_()) {
                return "她在水里/岩浆里";
            }
            double d = horizontalDist(maid, owner);
            if (d > 8.0) {
                return "离主人太远（> 8 格，正在飞过去）";
            }
            if (!solidGround(maid.m_9236_(), owner.m_20183_())) {
                return "主人脚下不是实地";
            }
            net.minecraft.core.BlockPos landing = findGroundBelow(maid.m_9236_(), maid.m_20183_(), 32);
            if (landing == null) {
                return "她下方 32 格内没有安全落点（虚空/水/岩浆/顶上被堵？）";
            }
            if (landing.m_123342_() < owner.m_20183_().m_123342_() - 4) {
                return "落点比主人低太多（他在高处/她悬在悬崖外）";
            }
            return null;   // 可以落
        } catch (Throwable ignored) {
            return "判定异常";
        }
    }

    /** pos 脚下是不是实心地面（且不是水/岩浆） */
    private static boolean solidGround(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        try {
            var below = level.m_8055_(pos.m_7495_());
            if (below.m_60795_()
                    || below.m_60713_(net.minecraft.world.level.block.Blocks.f_49990_)
                    || below.m_60713_(net.minecraft.world.level.block.Blocks.f_49991_)) {
                return false;
            }
            // m_60742_ = BlockStateBase.getCollisionShape(BlockGetter, BlockPos, CollisionContext)
            // m_83281_ = VoxelShape.isEmpty（两处 SRG 均由 javap 实证，与本树既有写法一致）
            return !below.m_60742_(level, pos.m_7495_(),
                    net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从 from 往下找第一个可以站的位置（≤maxDown 格）；找不到返回 null */
    private static net.minecraft.core.BlockPos findGroundBelow(net.minecraft.world.level.Level level,
                                                               net.minecraft.core.BlockPos from, int maxDown) {
        try {
            net.minecraft.core.BlockPos p = from;
            for (int i = 0; i <= maxDown; i++) {
                var here = level.m_8055_(p);
                // 空碰撞形状（火把/花/草丛）不算地面，继续往下
                if (!here.m_60742_(level, p,
                        net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_()) {
                    net.minecraft.core.BlockPos spot = p.m_7494_();
                    var s0 = level.m_8055_(spot);
                    var s1 = level.m_8055_(spot.m_7494_());
                    if (s0.m_60713_(net.minecraft.world.level.block.Blocks.f_49990_)
                            || s0.m_60713_(net.minecraft.world.level.block.Blocks.f_49991_)
                            || s0.m_60713_(net.minecraft.world.level.block.Blocks.f_50083_)) {
                        return null;
                    }
                    boolean free = s0.m_60742_(level, spot,
                            net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_()
                            && s1.m_60742_(level, spot.m_7494_(),
                            net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_();
                    return free ? spot : null;
                }
                if (here.m_60713_(net.minecraft.world.level.block.Blocks.f_49990_)
                        || here.m_60713_(net.minecraft.world.level.block.Blocks.f_49991_)) {
                    return null;   // 水面/岩浆面：不落
                }
                p = p.m_7495_();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 为什么没落地（同理由限频记一条，便于实机排查） */
    private static void logNoLand(EntityMaid maid, String reason) {
        try {
            if (reason == null) {
                return;
            }
            UUID id = maid.m_20148_();
            String last = NO_LAND_LAST.get(id);
            Long at = NO_LAND_AT.get(id);
            long t = maid.f_19797_;
            if (reason.equals(last) && at != null && t - at < 600) {
                return;
            }
            NO_LAND_LAST.put(id, reason);
            NO_LAND_AT.put(id, t);
            PromaidLog.log("仿创造飞行·待命", PromaidLog.nameOf(maid) + " 还没落地：" + reason);
        } catch (Throwable ignored) {
        }
    }

    /** 记录主人是否在动（水平位移 > 阈值）；返回 true = 在动 */
    private static boolean trackOwnerMoving(EntityMaid maid, LivingEntity owner) {
        UUID id = maid.m_20148_();
        double[] last = OWNER_LAST.get(id);
        double x = owner.m_20185_();
        double z = owner.m_20189_();
        OWNER_LAST.put(id, new double[]{x, z});
        if (last == null) {
            return false;
        }
        double d = Math.sqrt((x - last[0]) * (x - last[0]) + (z - last[1]) * (z - last[1]));
        if (d > OWNER_MOVE_EPS) {
            OWNER_STILL.put(id, 0);
            return true;
        }
        OWNER_STILL.merge(id, 1, Integer::sum);
        return false;
    }

    private static double horizontalDist(EntityMaid maid, LivingEntity owner) {
        double dx = maid.m_20185_() - owner.m_20185_();
        double dz = maid.m_20189_() - owner.m_20189_();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean idleMode() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_FREE_FLIGHT_IDLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int idleSeconds() {
        try {
            return Math.max(1, com.maidsmart.config.MaidSmartConfig.MISC_FREE_FLIGHT_IDLE_SECONDS.get());
        } catch (Throwable ignored) {
            return 3;
        }
    }

    private static double nearDist() {
        try {
            return Math.max(1, com.maidsmart.config.MaidSmartConfig.MISC_FREE_FLIGHT_NEAR_DIST.get());
        } catch (Throwable ignored) {
            return 2.0;
        }
    }

    /** 软着陆起算到现在过了多少 tick（没在软着陆 → -1） */
    private static long softLandAge(EntityMaid maid) {
        Long at = SOFT_LAND_START.get(maid.m_20148_());
        return at == null ? -1L : (long) maid.f_19797_ - at;
    }

    private static boolean softLandFast(EntityMaid maid) {
        long age = softLandAge(maid);
        return age > SOFT_LAND_FAST_AFTER_SECONDS * 20;
    }

    /**
     * 该放手了吗——**只在世界底部以下**（虚空）。软着陆的下降是无条件推进的，所以"永远落不下来"
     * 只可能发生在虚空里；松开重力在虚空里也不会有摔伤。除此之外**一律不放手**。
     */
    private static boolean softLandGiveUp(EntityMaid maid) {
        try {
            return maid.m_20186_() < maid.m_9236_().m_141937_() - 8;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void beginSoftLanding(EntityMaid maid, String why, int goal) {
        try {
            UUID id = maid.m_20148_();
            if (!SOFT_LAND_START.containsKey(id)) {
                SOFT_LAND_START.put(id, (long) maid.f_19797_);
                LAND_GOAL.put(id, goal);
                STATE.put(id, ST_SOFT_LAND);
                markAirborne(maid, true);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 开始软着陆（" + why + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 彻底放手：清掉这只女仆的全部状态，把重力与控制权交回 TLM */
    private static void forget(EntityMaid maid) {
        try {
            UUID id = maid.m_20148_();
            STATE.remove(id);
            LAND_GOAL.remove(id);
            SOFT_LAND_START.remove(id);
            OWNER_LAST.remove(id);
            OWNER_STILL.remove(id);
            STANDBY_SINCE.remove(id);
            NO_LAND_LAST.remove(id);
            NO_LAND_AT.remove(id);
            FAST_LOGGED.remove(id);
            YAW.remove(id);
            RETREAT.remove(id);
            DEBUG_TARGET.remove(id);
            TRAVEL.remove(id);
            TRAVEL_FLIGHT.remove(id);
            TRAVEL_LOGGED.remove(id);
            SUB_OWNER.remove(maid);
            markAirborne(maid, false);
            maid.m_20242_(false);
        } catch (Throwable ignored) {
        }
    }

    private static void release(EntityMaid maid, String reason) {
        try {
            UUID id = maid.m_20148_();
            boolean was = STATE.remove(id) != null;
            LAND_GOAL.remove(id);
            SOFT_LAND_START.remove(id);
            OWNER_LAST.remove(id);
            OWNER_STILL.remove(id);
            DEBUG_TARGET.remove(id);
            YAW.remove(id);
            RETREAT.remove(id);
            // 收工要把原来漏掉的三张表一起清，并把"我在飞"标记抹掉
            STANDBY_SINCE.remove(id);
            NO_LAND_LAST.remove(id);
            NO_LAND_AT.remove(id);
            FAST_LOGGED.remove(id);
            TRAVEL.remove(id);
            TRAVEL_FLIGHT.remove(id);
            TRAVEL_LOGGED.remove(id);
            markAirborne(maid, false);
            if (maid.m_6084_()) {
                maid.m_20242_(false);
                maid.m_183634_();   // 收工那一刻清一次坠落距离
            }
            if (was || reason != null && !"她没了".equals(reason)) {
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 收工（" + reason + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 她当前要打的敌人（TLM 攻击任务写在 brain 的 {@code ATTACK_TARGET}；兜底认 {@code Mob#getTarget}）。
     *
     * 【为什么要认它】用户实机反馈的**冲突 bug**：一般战斗模式下她**想在地面走向敌人并攻击**，
     * 而创造飞行却要求她**飞向主人**——两套逻辑打架。正确姿态是：**飞行是她的"移动层"，要跟着她
     * 的意图走**——有敌人就飞去打，没敌人再回到主人身边/落地待命。
     */
    private static LivingEntity combatTarget(EntityMaid maid) {
        try {
            LivingEntity sub = SUB_ENEMY.get(maid);
            if (sub != null && sub.m_6084_() && sub.m_9236_() == maid.m_9236_()) {
                return sub;   // 验收用替代敌人优先
            }
            LivingEntity t = null;
            var mem = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
            if (mem != null && mem.isPresent()) {
                t = mem.get();
            }
            if (t == null) {
                t = maid.m_5448_();
            }
            if (t != null && t.m_6084_() && t.m_9236_() == maid.m_9236_()) {
                return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 替代敌人（无头/专用服验收入口，同"替代主人"的思路）：为"没有真主人⇒TLM 战斗 AI 不跑⇒拿不到
     * 攻击目标"的场景留一个可验收的口子。
     */
    private static final Map<EntityMaid, LivingEntity> SUB_ENEMY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void setSubstituteEnemy(EntityMaid maid, LivingEntity target) {
        if (maid != null && target != null) {
            SUB_ENEMY.put(maid, target);
        }
    }

    public static void clearSubstituteEnemy(EntityMaid maid) {
        if (maid != null) {
            SUB_ENEMY.remove(maid);
        }
    }

    /** 战斗档：飞向敌人（保持"够得着"的间距），悬停时面朝敌人 */
    private static void combatFly(EntityMaid maid, LivingEntity enemy) {
        try {
            double standoff = 2.2;   // 近战够得着（原版近战距离约 3 格）、又不至于撞进它身体里
            double dx = maid.m_20185_() - enemy.m_20185_();
            double dz = maid.m_20189_() - enemy.m_20189_();
            double len = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
            Vec3 aim = new Vec3(enemy.m_20185_() + dx / len * standoff,
                    enemy.m_20186_() + enemy.m_20206_() * 0.5 + 0.5,
                    enemy.m_20189_() + dz / len * standoff);
            steer(maid, aim, true, enemy.m_20182_());
            maid.m_20242_(true);
            suppressWalk(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 目标点：调试坐标 → 主人身后的跟随圈（同扫帚口径）→ 原地悬停 */
    private static Vec3 pickAim(EntityMaid maid) {
        Vec3 dbg = DEBUG_TARGET.get(maid.m_20148_());
        if (dbg != null) {
            return dbg;
        }
        LivingEntity owner = resolveOwner(maid);
        if (owner != null && owner.m_6084_() && maid.m_9236_() == owner.m_9236_()) {
            double dx = maid.m_20185_() - owner.m_20185_();
            double dz = maid.m_20189_() - owner.m_20189_();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.1) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            return new Vec3(owner.m_20185_() + dx / len * FOLLOW_DIST,
                    owner.m_20186_() + FOLLOW_HEIGHT,
                    owner.m_20189_() + dz / len * FOLLOW_DIST);
        }
        return maid.m_20182_();
    }

    /** 一阶转向：速度 = 方向 × 距离决定的速率，绝不累加 */
    private static void steer(EntityMaid maid, Vec3 desired, boolean vertical, Vec3 ownerPos) {
        try {
            double dx = desired.f_82479_ - maid.m_20185_();
            double dy = vertical ? desired.f_82480_ - maid.m_20186_() : 0.0;
            double dz = desired.f_82481_ - maid.m_20189_();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vec3 vel;
            if (dist < MaidFreeFlightKit.ARRIVE) {
                vel = Vec3.f_82478_;                              // 到点 = 零速悬停
            } else {
                double speed = Math.min(1.0, dist / MaidFreeFlightKit.ARRIVE_RAMP);
                double inv = 1.0 / dist;
                vel = new Vec3(dx * inv * MaidFreeFlightKit.MAX_H_SPEED * speed,
                        dy * inv * MaidFreeFlightKit.MAX_V_SPEED * speed,
                        dz * inv * MaidFreeFlightKit.MAX_H_SPEED * speed);
            }
            maid.m_20256_(vel);
            maid.f_19812_ = true;
            maid.f_19864_ = true;

            // ── 朝向：不再"永远等于速度方向" ──
            // 悬停/慢速 → 面向主人（陪伴感）；迎着主人飞 → 面朝前进方向；
            // 退着飞（主人在她身后推进）→ 先**面向主人倒退**，持续 3 秒才转身朝前；
            // 转身本身逐 tick 转（每 tick 最多 12°），不再碰 yRotO（让客户端插值接手，转体才平滑）。
            UUID id = maid.m_20148_();
            float cur = YAW.getOrDefault(id, maid.m_146908_());
            double hSpeed = Math.sqrt(vel.f_82479_ * vel.f_82479_ + vel.f_82481_ * vel.f_82481_);
            float target;
            if (ownerPos != null) {
                float toOwner = (float) (-Math.atan2(ownerPos.f_82479_ - maid.m_20185_(),
                        ownerPos.f_82481_ - maid.m_20189_()) * MaidFreeFlightKit.DEG);
                if (hSpeed < HOVER_SPEED) {
                    target = toOwner;                    // 悬停：面向主人
                    RETREAT.remove(id);
                } else {
                    float travel = (float) (-Math.atan2(vel.f_82479_, vel.f_82481_) * MaidFreeFlightKit.DEG);
                    boolean towardOwner = vel.f_82479_ * (ownerPos.f_82479_ - maid.m_20185_())
                            + vel.f_82481_ * (ownerPos.f_82481_ - maid.m_20189_()) > 0;
                    if (towardOwner) {
                        target = travel;                 // 迎着主人飞：面朝前进方向
                        RETREAT.remove(id);
                    } else {
                        // 退着飞：先面向主人倒退，攒够 3 秒才转身朝前
                        int r = RETREAT.merge(id, 1, Integer::sum);
                        target = r < RETREAT_FLIP_TICKS ? toOwner : travel;
                    }
                }
            } else {
                // 坐标档：面朝前进方向；停着就保持当前朝向
                target = hSpeed > 0.02
                        ? (float) (-Math.atan2(vel.f_82479_, vel.f_82481_) * MaidFreeFlightKit.DEG)
                        : cur;
                RETREAT.remove(id);
            }
            float next = rotateToward(cur, target);
            YAW.put(id, next);
            maid.m_146922_(next);
            maid.m_5616_(next);
            maid.m_5618_(next);
            // 刻意不写 yRotO/yBodyRotO：让原版逐 tick 同步 + 客户端插值接手，转体才是平滑的。
            // 把"期望点"一并交给 LookControl，免得它拿残留的旧目标把头拧回去
            double fx = -Math.sin(Math.toRadians(next));
            double fz = Math.cos(Math.toRadians(next));
            maid.m_21563_().m_24950_(maid.m_20185_() + fx * 4.0, maid.m_20188_(),
                    maid.m_20189_() + fz * 4.0, 360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick 最多转 {@link #YAW_STEP} 度，走最短弧 */
    private static float rotateToward(float cur, float target) {
        float diff = net.minecraft.util.Mth.m_14177_(target - cur);
        if (Math.abs(diff) <= YAW_STEP) {
            return target;
        }
        return net.minecraft.util.Mth.m_14177_(cur + Math.signum(diff) * YAW_STEP);
    }

    /** 抢移动：清走路目标 + 停导航（她不是乘客，TLM 的寻路仍在跑） */
    private static void suppressWalk(EntityMaid maid) {
        try {
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            maid.m_6274_().m_21936_(MemoryModuleType.f_26371_);
            maid.getNavigationManager().resetNavigation();
        } catch (Throwable ignored) {
        }
    }
}
