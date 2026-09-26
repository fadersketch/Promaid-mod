package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实测六百七十三【仿创造飞行 · 控制器】——让女仆悬浮并自由升降（创造模式飞行的手感）。
 *
 * 【这一步解决什么】整合包里给"创造飞行/悬浮"的手段五花八门（饰品、护甲套装、药水效果、
 * 重力归零类法术……），而它们几乎全部只对**玩家**生效；女仆拿到手上一字不动。本控制器不去
 * 复刻每个物品的物理，而是：**只要她有资格（见 {@link MaidFreeFlightKit}），我们就托住她**——
 * `setNoGravity(true)` + 每 tick 直接给速度，形成"悬停 + 平滑位移"的创造飞行手感。
 *
 * 【为什么挂在事件上而不是 brain 行为（实测六百七十三 记录）】最初写的是 core 行为
 * （priority 40，与其它附加行为同一条注册链）。现场诊断显示：行为**被实例化了**（大脑构建时
 * 确实取用了它），但它的 `checkExtraStartConditions` **从来没有被调用过**——同一条注册链上的
 * 其它行为（如 235 的重锤门）却能正常被咨询。原因待作者确认，这里先用 `MaidTickEvent`
 * 事件驱动实现同一套逻辑：不依赖大脑调度，行为等价、挂载点更直接。
 * （TLM 的 `MaidTickEvent` 在 `EntityMaid.tick()` 里**早于** brain tick 发出，所以我们的
 * 速度设置在原版 travel 之前生效。）
 *
 * 【飞行力学：一阶，绝不累加】转向照抄扫帚模式那套已实测的公式
 * （`speed = min(1, dist / 1.5)`，分量限速 0.35/0.22，`dist < 0.35` 直接零速悬停）：速度**直接由
 * 距离算**，不做任何累加——实测六百五十五 的教训是，一旦接管了 travel（没有原版 0.91 阻力），
 * 增量式推进就是无阻尼谐振子，表现为"升空后空中上下摆动"。一阶系统数学上不可能振荡。
 *
 * 【安全底线】收工时若她还在半空 → 先"软着陆"（保持无重力、缓慢下降），落地或超时才交还重力；
 * 异常也保持悬停，绝不因为一次异常摔死她（她只有 20 血）。
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
    /** 软着陆最长持续（tick）：到点就交还重力，避免"永远飘不下去" */
    private static final int SOFT_LAND_MAX = 120;
    /** 软着陆下降速度（格/tick）：0.12 ≈ 2.4 格/秒，稳稳落地 */
    private static final double SOFT_LAND_SPEED = 0.12;

    /* ---------------- 实测六百七十七【朝向：倒退着飞 + 延迟转身 + 平滑转体】 ---------------- */

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
        DEBUG_TARGET.put(maid.getUUID(), pos);
    }

    public static void clearDebugTarget(EntityMaid maid) {
        DEBUG_TARGET.remove(maid.getUUID());
    }

    public static boolean isFlying(EntityMaid maid) {
        return maid != null && STATE.getOrDefault(maid.getUUID(), ST_OFF) == ST_FLYING;
    }

    /** 能不能飞（命令校验与主循环共用同一口径） */
    public static boolean canFly(EntityMaid maid) {
        try {
            return maid != null && maid.isAlive() && MaidFreeFlightKit.isModeActive(maid)
                    && MaidFreeFlightFlags.effective(maid) && !blocked(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 一行可读的状态（命令/日志用） */
    public static String status(EntityMaid maid) {
        try {
            return (isFlying(maid) ? "飞行中" : "未起飞") + "；" + MaidFreeFlightKit.diag(maid)
                    + "；骑乘=" + maid.isPassenger() + " 坐姿=" + maid.isMaidInSittingPose()
                    + " 可动=" + maid.canBrainMoving() + " 守家=" + maid.isHomeModeEnable()
                    + " 飞行任务=" + com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)
                    + " 扫帚任务=" + com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        } catch (Throwable t) {
            return "状态异常：" + t;
        }
    }

    private static boolean blocked(EntityMaid maid) {
        try {
            if (maid.isPassenger() || maid.isMaidInSittingPose() || maid.isSleeping()) {
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

    /* ---------------- 主循环（MaidTickEvent） ---------------- */

    /* ---------------- 状态机（实测六百七十八：加"落地待命"与智能待命判定） ---------------- */

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
     *
     * 【为什么需要】这条链的目标是**在线主人实体**：TLM 的 {@code getOwner()} 走
     * {@code server.getPlayerList().getPlayer(uuid)}，专用服务器上没有玩家就恒为 null，整条
     * "静止→落地待命→再起飞"**结构上无法端到端触发**。挂一个替代主人（僵尸/盔甲架都行）就能
     * 走**同一套**判定与飞行链路，只替换目标来源。键用弱引用，女仆卸载即回收。
     */
    private static final Map<EntityMaid, LivingEntity> SUB_OWNER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void setSubstituteOwner(EntityMaid maid, LivingEntity target) {
        if (maid != null && target != null) {
            SUB_OWNER.put(maid, target);
            OWNER_STILL.remove(maid.getUUID());
            OWNER_LAST.remove(maid.getUUID());
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
            if (sub != null && sub.isAlive() && sub.level() == maid.level()) {
                return sub;
            }
        } catch (Throwable ignored) {
        }
        return maid.getOwner();
    }

    /** 进入"落地待命"的时刻（tick）——落地后留一秒落稳宽限，避免刚站住就被判"她在半空"再次起飞 */
    private static final Map<UUID, Long> STANDBY_SINCE = new HashMap<>();
    /** "为什么还没落地"的诊断限频 */
    private static final Map<UUID, String> NO_LAND_LAST = new HashMap<>();
    private static final Map<UUID, Long> NO_LAND_AT = new HashMap<>();

    private static final int ST_OFF = 0;
    private static final int ST_FLYING = 1;
    private static final int ST_SOFT_LAND = 2;
    private static final int ST_STANDBY = 3;
    /** 判定"主人在动"的水平位移阈值（格/tick）：0.03 ≈ 0.6 格/秒 */
    private static final double OWNER_MOVE_EPS = 0.03;

    public static void tick(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            if (!maid.isAlive()) {
                release(maid, "她没了");
                return;
            }
            int st = STATE.getOrDefault(id, ST_OFF);
            LivingEntity owner = resolveOwner(maid);
            boolean ownerHere = owner != null && owner.isAlive() && maid.level() == owner.level();
            boolean ownerMoving = ownerHere && trackOwnerMoving(maid, owner);
            boolean allowed = canFly(maid);
            // 【实测六百七十九】战斗意图优先：有敌人时不要把她往主人身边拉（那正是用户报的冲突）
            LivingEntity enemy = allowed ? combatTarget(maid) : null;
            boolean enemyTooFar = enemy != null && horizontalDist(maid, enemy) > 4.0;

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
                    takeOff(maid, "起飞追击敌人");   // 待命时敌人来了且够不着 → 飞过去打
                    return;
                }
                if (shouldTakeOff(maid, ownerHere ? owner : null, ownerMoving)) {
                    takeOff(maid, "重新起飞");
                }
                return;
            }
            // ③ 飞行中
            if (st == ST_FLYING) {
                if (!allowed || maid.isInWater() || maid.isInLava()) {
                    beginSoftLanding(maid, maid.isInWater() || maid.isInLava() ? "落水/岩浆" : "能力消失或状态变化", ST_OFF);
                    return;
                }
                // 战斗意图优先：有敌人就飞去打（**不**落地待命——那是"回到主人身边"的逻辑）
                if (enemy != null) {
                    combatFly(maid, enemy);
                    return;
                }
                if (shouldLandAndWait(maid, ownerHere ? owner : null, ownerMoving)) {
                    beginSoftLanding(maid, "主人停下来了，落地待命", ST_STANDBY);
                    return;
                }
                flyTick(maid, id, ownerHere ? owner : null);
                return;
            }
            // ④ 未接管：够资格 + （要追敌人 或 该起飞）→ 起飞
            if (allowed && (enemyTooFar || shouldTakeOff(maid, ownerHere ? owner : null, ownerMoving))) {
                takeOff(maid, enemyTooFar ? "起飞追击敌人" : "起飞");
            }
        } catch (Throwable t) {
            // 异常兜底：只在"飞行中"才强行保持悬停；落地待命时就当她不存在（别让她飘起来）
            try {
                int st = STATE.getOrDefault(maid.getUUID(), ST_OFF);
                if (st == ST_FLYING || st == ST_SOFT_LAND) {
                    maid.setNoGravity(true);
                    maid.setDeltaMovement(Vec3.ZERO);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void takeOff(EntityMaid maid, String why) {
        try {
            STATE.put(maid.getUUID(), ST_FLYING);
            maid.setNoGravity(true);
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
            if (DEBUG_TARGET.containsKey(maid.getUUID())) {
                return "坐标档";
            }
            LivingEntity owner = resolveOwner(maid);
            if (owner == null) {
                return "没有主人";
            }
            Long since = STANDBY_SINCE.get(maid.getUUID());
            if (since != null && maid.tickCount - since < 20) {
                return "（宽限内）";
            }
            if (!maid.onGround() && (maid.fallDistance > 0.5 || maid.getDeltaMovement().y < -0.12)) {
                return "她在下坠 fall=" + String.format("%.2f", maid.fallDistance)
                        + " vy=" + String.format("%.3f", maid.getDeltaMovement().y);
            }
            if (!maid.onGround()) {
                return "她在半空但没在下坠（onGround=false vy=" + String.format("%.3f", maid.getDeltaMovement().y) + "）";
            }
            if (OWNER_LAST.containsKey(maid.getUUID())
                    && trackOwnerMovingSnapshot(maid, owner)) {
                return "主人在动";
            }
            if (horizontalDist(maid, owner) > 4.0) {
                return "离主人 > 4 格";
            }
            if (Math.abs(maid.getY() - owner.getY()) > 2.0) {
                return "高差 " + String.format("%.1f", Math.abs(maid.getY() - owner.getY())) + " > 2";
            }
            return "主人可能在空中";
        } catch (Throwable ignored) {
            return "诊断异常";
        }
    }

    /** 只读版的主人移动判定（诊断用，不更新状态表） */
    private static boolean trackOwnerMovingSnapshot(EntityMaid maid, LivingEntity owner) {
        double[] last = OWNER_LAST.get(maid.getUUID());
        if (last == null) {
            return false;
        }
        double dx = owner.getX() - last[0];
        double dz = owner.getZ() - last[1];
        return Math.sqrt(dx * dx + dz * dz) > OWNER_MOVE_EPS;
    }

    /** 飞行中的每 tick：选目标点 → 一阶转向 → 保持无重力 → 抢移动 */
    private static void flyTick(EntityMaid maid, UUID id, LivingEntity owner) {
        Vec3 aim = pickAim(maid);
        Vec3 dbg = DEBUG_TARGET.get(id);
        // 跟随主人的那一档才启用"社交朝向"（倒退/面向主人）；坐标档不参与
        Vec3 ownerPos = (dbg == null && owner != null && owner.isAlive() && maid.level() == owner.level())
                ? owner.position() : null;
        steer(maid, aim, true, ownerPos);
        maid.setNoGravity(true);
        suppressWalk(maid);
        if (dbg != null && maid.position().distanceTo(dbg) <= 1.0) {
            beginSoftLanding(maid, "已到指定坐标", ST_OFF);
        }
    }

    /**
     * 软着陆：**保持无重力** + 恒速下降 +（若要落地待命）朝主人轻微漂移。
     *
     * 【为什么必须保持无重力】第一版忘了这条：重力一回来她就在软着陆期间加速下坠，
     * 实测 4 秒掉 23 格（等于没做缓冲，20 血的女仆摔一下就没）。
     * 每 tick 清零坠落距离，保证"软"到底。
     */
    private static void tickSoftLand(EntityMaid maid, LivingEntity owner) {
        UUID id = maid.getUUID();
        if (maid.onGround() || expired(maid)) {
            int goal = LAND_GOAL.getOrDefault(id, ST_OFF);
            SOFT_LAND_START.remove(id);
            LAND_GOAL.remove(id);
            if (goal == ST_STANDBY && canFly(maid)) {
                STATE.put(id, ST_STANDBY);
                STANDBY_SINCE.put(id, (long) maid.tickCount);
                maid.setNoGravity(false);
                maid.setDeltaMovement(0.0, Math.min(0.0, maid.getDeltaMovement().y), 0.0);
                YAW.remove(id);
                RETREAT.remove(id);
                clearDebugTarget(maid);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 落地待命（贴着你站好）");
            } else {
                release(maid, "已着陆");
            }
            return;
        }
        // 【实测六百七十八 修起落抖动】两段式进场：**先飞到他身边**（用飞行速度），
        // 贴近了才垂直下降。旧版是"边下降边慢漂"（漂移上限 0.15 格/tick + 下降 2.4 格/秒），
        // 从 8 格外根本来不及漂到，于是她落在 5 格开外 → 又触发"离主人 > 4 格"的起飞判据
        // → 起飞 → 再落 → 起落抖动（诊断日志实测："重新起飞（离主人 5.4 格 > 4）"）。
        if (owner != null) {
            double dh = horizontalDist(maid, owner);
            if (dh > nearDist() + 0.5) {
                double dx = owner.getX() - maid.getX();
                double dz = owner.getZ() - maid.getZ();
                double len = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
                // 目标点 = 主人身边 nearDist 处、与他同高略上（这样不会一头扎进他身体里）
                Vec3 aim = new Vec3(owner.getX() - dx / len * nearDist(),
                        owner.getY() + 1.0,
                        owner.getZ() - dz / len * nearDist());
                steer(maid, aim, true, owner.position());   // 社交朝向：面向主人倒退着过去
                maid.setNoGravity(true);
                maid.resetFallDistance();
                suppressWalk(maid);
                return;
            }
        }
        // 贴近了 → 恒速垂直下降（保持无重力 + 每 tick 清零坠落距离，保证"软"到底）
        maid.setNoGravity(true);
        maid.setDeltaMovement(0.0, -SOFT_LAND_SPEED, 0.0);
        maid.hasImpulse = true;
        maid.hurtMarked = true;
        maid.resetFallDistance();
        suppressWalk(maid);
    }

    /**
     * 该不该起飞（实测六百七十八 的"智能待命"口径）。
     *
     * 旧行为 = 够资格就一直悬着（`return true`），于是喂食/摸头这类**需要贴身的交互**
     * 全都够不到（原版实体交互距离 3 格，而她悬在 3.5 格 + 高 2 格 ⇒ ≈4 格）。
     * 新版：**赶路时飞、你停下来时她落到你脚边待命**。
     */
    private static boolean shouldTakeOff(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        if (!idleMode()) {
            return true;                                  // "始终悬停"档：够资格就飞（旧行为）
        }
        if (DEBUG_TARGET.containsKey(maid.getUUID())) {
            return true;                                  // 坐标档：命令让她飞，就飞
        }
        if (owner == null) {
            return true;                                  // 没主人（单人调试/无主）：保持原地悬停
        }
        // 【实测六百七十八 修抖动】旧版这里只看 `!onGround()`：她刚落地那一瞬 onGround 还是 false
        // ⇒ 立刻被判"她在半空"→ 重新起飞 → 再落 → 起落抖动（实测日志里 1 秒内往复多轮）。
        // 现在两条一起用：**落地后 1 秒宽限** + 只有"真的在下坠"（坠落距离/垂直速度）才算半空。
        Long since = STANDBY_SINCE.get(maid.getUUID());
        if (since != null && maid.tickCount - since < 20) {
            return false;                                 // 刚落稳，先站着
        }
        if (!maid.onGround() && (maid.fallDistance > 0.5 || maid.getDeltaMovement().y < -0.12)) {
            return true;                                  // 她在往下掉（被推下悬崖/脚下被挖空）→ 接管
        }
        if (ownerMoving) {
            return true;                                  // 主人在走
        }
        if (horizontalDist(maid, owner) > 4.0) {
            return true;                                  // 被拉开
        }
        if (Math.abs(maid.getY() - owner.getY()) > 2.0) {
            return true;                                  // 高差（他上坡/爬塔/上天）
        }
        return ownerAirborne(owner);                      // 主人在滑翔/创造飞行 → 跟上去
    }

    /**
     * 该不该落地待命——判定与"为什么没落地"共用 {@link #noLandReason}（单一事实源，
     * 免得判定与日志各写一套、排查时对不上）。
     *
     * 【离主人的距离门槛已放宽到 8 格】旧版要求"已经贴近到 3.5 格内"才允许落，
     * 而她悬停的位置正好就在 3.5 格边缘（跟随圈半径）⇒ 判定在临界值上抖 ✗。
     * 现在只要"主人身边 8 格内"就落，**靠近的动作由软着陆的漂移负责**（≤0.15 格/tick 漂到 2 格内）。
     */
    private static boolean shouldLandAndWait(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        String reason = noLandReason(maid, owner, ownerMoving);
        if (reason == null) {
            return true;
        }
        // 只在"主人其实已经停下来很久了"这种情况才记日志（避免正常跟随时刷屏）
        if (!ownerMoving && OWNER_STILL.getOrDefault(maid.getUUID(), 0) >= idleSeconds() * 20) {
            logNoLand(maid, reason);
        }
        return false;
    }

    private static boolean ownerAirborne(LivingEntity owner) {
        try {
            if (owner.isFallFlying()) {
                return true;
            }
            return owner instanceof net.minecraft.world.entity.player.Player p && p.getAbilities().flying;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 落点安全（实测六百七十八 修）：**从她当前位置往下找**落点，而不是查"她脚下有没有方块"。
     *
     * 【旧版的致命 bug】第一版写成 `solidGround(maid.blockPosition())`——她**正悬在半空**，
     * 脚下本来就是空气 ⇒ 恒为 false ⇒ "落点安全"永远判不过 ⇒ **永远落不下来**
     * （用户实测：静止等了约 10 分钟她依然悬着）。正确的问法是"她往下落会落在哪、那里安全吗"。
     */
    private static String noLandReason(EntityMaid maid, LivingEntity owner, boolean ownerMoving) {
        try {
            if (!idleMode()) {
                return "智能待命关着（配置里可开）";
            }
            if (owner == null) {
                return "没有在线主人（专用服务器上 getOwner 恒为 null）";
            }
            if (DEBUG_TARGET.containsKey(maid.getUUID())) {
                return "坐标档进行中";
            }
            if (ownerMoving) {
                return "主人还在动";
            }
            int still = OWNER_STILL.getOrDefault(maid.getUUID(), 0);
            if (still < idleSeconds() * 20) {
                return "主人静止 " + (still / 20) + "s（需 " + idleSeconds() + "s）";
            }
            if (ownerAirborne(owner)) {
                return "主人在空中（滑翔/创造飞行）";
            }
            if (maid.isInWater() || maid.isInLava()) {
                return "她在水里/岩浆里";
            }
            double d = horizontalDist(maid, owner);
            if (d > 8.0) {
                // 不把距离写进字符串：否则每接近 1 格就变成"新理由"、绕过限频刷屏
                return "离主人太远（> 8 格，正在飞过去）";
            }
            if (!solidGround(maid.level(), owner.blockPosition())) {
                return "主人脚下不是实地";
            }
            net.minecraft.core.BlockPos landing = findGroundBelow(maid.level(), maid.blockPosition(), 32);
            if (landing == null) {
                return "她下方 32 格内没有安全落点（虚空/水/岩浆/顶上被堵？）";
            }
            if (landing.getY() < owner.blockPosition().getY() - 4) {
                return "落点比主人低太多（他在高处/她悬在悬崖外）";
            }
            return null;   // 可以落
        } catch (Throwable ignored) {
            return "判定异常";
        }
    }

    /** pos 脚下是不是实心地面（且不是水/岩浆）——判断"主人是不是站在地上" */
    private static boolean solidGround(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        try {
            var below = level.getBlockState(pos.below());
            if (below.isAir()
                    || below.is(net.minecraft.world.level.block.Blocks.WATER)
                    || below.is(net.minecraft.world.level.block.Blocks.LAVA)) {
                return false;
            }
            return !below.getCollisionShape(level, pos.below()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从 from 往下找第一个可以站的位置（≤maxDown 格）；找不到返回 null */
    private static net.minecraft.core.BlockPos findGroundBelow(net.minecraft.world.level.Level level,
                                                               net.minecraft.core.BlockPos from, int maxDown) {
        try {
            net.minecraft.core.BlockPos.MutableBlockPos p = from.mutable();
            for (int i = 0; i <= maxDown; i++) {
                var here = level.getBlockState(p);
                if (!here.getCollisionShape(level, p).isEmpty()) {
                    net.minecraft.core.BlockPos spot = p.above();
                    var s0 = level.getBlockState(spot);
                    var s1 = level.getBlockState(spot.above());
                    if (s0.is(net.minecraft.world.level.block.Blocks.WATER)
                            || s0.is(net.minecraft.world.level.block.Blocks.LAVA)
                            || s0.is(net.minecraft.world.level.block.Blocks.FIRE)) {
                        return null;
                    }
                    boolean free = s0.getCollisionShape(level, spot).isEmpty()
                            && s1.getCollisionShape(level, spot.above()).isEmpty();
                    return free ? spot : null;
                }
                if (here.is(net.minecraft.world.level.block.Blocks.WATER)
                        || here.is(net.minecraft.world.level.block.Blocks.LAVA)) {
                    return null;   // 水面/岩浆面：不落
                }
                p.move(0, -1, 0);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 为什么没落地（同理由限频记一条，便于实机排查）——`noLandReason` 的日志壳 */
    private static void logNoLand(EntityMaid maid, String reason) {
        try {
            if (reason == null) {
                return;
            }
            UUID id = maid.getUUID();
            String last = NO_LAND_LAST.get(id);
            Long at = NO_LAND_AT.get(id);
            long t = maid.tickCount;
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
        UUID id = maid.getUUID();
        double[] last = OWNER_LAST.get(id);
        double x = owner.getX();
        double z = owner.getZ();
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
        double dx = maid.getX() - owner.getX();
        double dz = maid.getZ() - owner.getZ();
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

    private static boolean expired(EntityMaid maid) {
        Long at = SOFT_LAND_START.get(maid.getUUID());
        return at != null && maid.tickCount - at > SOFT_LAND_MAX * 20;
    }

    private static void beginSoftLanding(EntityMaid maid, String why, int goal) {
        try {
            UUID id = maid.getUUID();
            if (!SOFT_LAND_START.containsKey(id)) {
                SOFT_LAND_START.put(id, (long) maid.tickCount);
                LAND_GOAL.put(id, goal);
                STATE.put(id, ST_SOFT_LAND);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 开始软着陆（" + why + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 彻底放手：清掉这只女仆的全部状态，把重力与控制权交回 TLM */
    private static void forget(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            STATE.remove(id);
            LAND_GOAL.remove(id);
            SOFT_LAND_START.remove(id);
            OWNER_LAST.remove(id);
            OWNER_STILL.remove(id);
            STANDBY_SINCE.remove(id);
            YAW.remove(id);
            RETREAT.remove(id);
            DEBUG_TARGET.remove(id);
            maid.setNoGravity(false);
        } catch (Throwable ignored) {
        }
    }

    private static void release(EntityMaid maid, String reason) {
        try {
            UUID id = maid.getUUID();
            boolean was = STATE.remove(id) != null;
            LAND_GOAL.remove(id);
            SOFT_LAND_START.remove(id);
            OWNER_LAST.remove(id);
            OWNER_STILL.remove(id);
            DEBUG_TARGET.remove(id);
            YAW.remove(id);
            RETREAT.remove(id);
            if (maid.isAlive()) {
                maid.setNoGravity(false);
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
     * 【为什么要认它（实测六百七十九）】用户实机反馈的**冲突 bug**：一般战斗模式下她**想在地面
     * 走向敌人并攻击**，而创造飞行却要求她**飞向主人**——两套逻辑打架（这也是"飞行时会强制保持
     * 与主人的距离/高差"的直接后果）。正确姿态是：**飞行是她的"移动层"，要跟着她的意图走**——
     * 有敌人就飞去打，没敌人再回到主人身边/落地待命。
     * （用户对两种飞行的定位也很清楚：鞘翅＝战斗机、靠高速与爬升俯冲拿优势，作者的"空袭"就是围着
     * 它设计的；创造飞行＝直升机、慢而稳而灵活，是"配合其他工作"的万金油移动层——两者重合很少。）
     */
    private static LivingEntity combatTarget(EntityMaid maid) {
        try {
            LivingEntity sub = SUB_ENEMY.get(maid);
            if (sub != null && sub.isAlive() && sub.level() == maid.level()) {
                return sub;   // 验收用替代敌人优先
            }
            LivingEntity t = null;
            var mem = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
            if (mem != null && mem.isPresent()) {
                t = mem.get();
            }
            if (t == null) {
                t = maid.getTarget();
            }
            if (t != null && t.isAlive() && t.level() == maid.level()) {
                return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 替代敌人（无头/专用服验收入口，同"替代主人"的思路）：主链路仍读 brain 的 ATTACK_TARGET，
     * 这里只是为"没有真主人⇒TLM 战斗 AI 不跑⇒拿不到攻击目标"的场景留一个可验收的口子
     * （实测：无主女仆的 {@code Brain.memories} 是空的，攻击任务压根不启动）。
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
            // 站定距离：近战够得着（原版近战距离约 3 格）、又不至于撞进它身体里
            double standoff = 2.2;
            double dx = maid.getX() - enemy.getX();
            double dz = maid.getZ() - enemy.getZ();
            double len = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
            // 目标点：贴着敌人的水平 standoff 处、抬到它身体中段略上（避免蹭地/卡进方块）
            Vec3 aim = new Vec3(enemy.getX() + dx / len * standoff,
                    enemy.getY() + enemy.getBbHeight() * 0.5 + 0.5,
                    enemy.getZ() + dz / len * standoff);
            // 面向锚点传敌人 → 沿用 steer 里的社交朝向规则（悬停时面向它）
            steer(maid, aim, true, enemy.position());
            maid.setNoGravity(true);
            suppressWalk(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 目标点：调试坐标 → 主人身后的跟随圈（同扫帚口径）→ 原地悬停 */
    private static Vec3 pickAim(EntityMaid maid) {
        Vec3 dbg = DEBUG_TARGET.get(maid.getUUID());
        if (dbg != null) {
            return dbg;
        }
        LivingEntity owner = resolveOwner(maid);
        if (owner != null && owner.isAlive() && maid.level() == owner.level()) {
            double dx = maid.getX() - owner.getX();
            double dz = maid.getZ() - owner.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.1) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            return new Vec3(owner.getX() + dx / len * FOLLOW_DIST,
                    owner.getY() + FOLLOW_HEIGHT,
                    owner.getZ() + dz / len * FOLLOW_DIST);
        }
        return maid.position();
    }

    /** 一阶转向：速度 = 方向 × 距离决定的速率，绝不累加 */
    private static void steer(EntityMaid maid, Vec3 desired, boolean vertical, Vec3 ownerPos) {
        try {
            double dx = desired.x - maid.getX();
            double dy = vertical ? desired.y - maid.getY() : 0.0;
            double dz = desired.z - maid.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vec3 vel;
            if (dist < MaidFreeFlightKit.ARRIVE) {
                vel = Vec3.ZERO;                                  // 到点 = 零速悬停
            } else {
                double speed = Math.min(1.0, dist / MaidFreeFlightKit.ARRIVE_RAMP);
                double inv = 1.0 / dist;
                vel = new Vec3(dx * inv * MaidFreeFlightKit.MAX_H_SPEED * speed,
                        dy * inv * MaidFreeFlightKit.MAX_V_SPEED * speed,
                        dz * inv * MaidFreeFlightKit.MAX_H_SPEED * speed);
            }
            maid.setDeltaMovement(vel);
            maid.hasImpulse = true;
            maid.hurtMarked = true;

            // ── 朝向（实测六百七十七）：不再"永远等于速度方向" ──
            // 旧版两个问题：①她站在主人前面、主人向前走时，她会瞬间从"面朝主人"翻成"背朝主人"，
            // 而且主人的移动带随机抖动，这个 180° 来回翻特别突兀；②旧代码连 yRotO 一起拍成新值，
            // 等于把客户端的逐 tick 插值也掐死了——转体永远是硬切。
            // 现在：悬停/慢速 → 面向主人（陪伴感）；迎着主人飞 → 面朝前进方向；
            // 退着飞（主人在她身后推进）→ 先**面向主人倒退**，持续 3 秒才转身朝前；
            // 转身本身逐 tick 转（每 tick 最多 12°，180° 用 0.75 秒），不再碰 yRotO。
            UUID id = maid.getUUID();
            float cur = YAW.getOrDefault(id, maid.getYRot());
            double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            float target;
            if (ownerPos != null) {
                float toOwner = (float) (-Math.atan2(ownerPos.x - maid.getX(),
                        ownerPos.z - maid.getZ()) * MaidFreeFlightKit.DEG);
                if (hSpeed < HOVER_SPEED) {
                    target = toOwner;                    // 悬停：面向主人
                    RETREAT.remove(id);
                } else {
                    float travel = (float) (-Math.atan2(vel.x, vel.z) * MaidFreeFlightKit.DEG);
                    boolean towardOwner = vel.x * (ownerPos.x - maid.getX())
                            + vel.z * (ownerPos.z - maid.getZ()) > 0;
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
                        ? (float) (-Math.atan2(vel.x, vel.z) * MaidFreeFlightKit.DEG)
                        : cur;
                RETREAT.remove(id);
            }
            float next = rotateToward(cur, target);
            YAW.put(id, next);
            maid.setYRot(next);
            maid.setYHeadRot(next);
            maid.setYBodyRot(next);
            // 刻意不写 yRotO/yBodyRotO：让原版逐 tick 同步 + 客户端插值接手，转体才是平滑的。
            // 把"期望点"一并交给 LookControl，免得它拿残留的旧目标把头拧回去（实测五百二十九 同款坑）
            double fx = -Math.sin(Math.toRadians(next));
            double fz = Math.cos(Math.toRadians(next));
            maid.getLookControl().setLookAt(maid.getX() + fx * 4.0, maid.getEyeY(),
                    maid.getZ() + fz * 4.0, 360.0f, 360.0f);
        } catch (Throwable ignored) {
        }
    }

    /** 每 tick 最多转 {@link #YAW_STEP} 度，走最短弧 */
    private static float rotateToward(float cur, float target) {
        float diff = net.minecraft.util.Mth.wrapDegrees(target - cur);
        if (Math.abs(diff) <= YAW_STEP) {
            return target;
        }
        return net.minecraft.util.Mth.wrapDegrees(cur + Math.signum(diff) * YAW_STEP);
    }

    /** 抢移动：清走路目标 + 停导航（她不是乘客，TLM 的寻路仍在跑） */
    private static void suppressWalk(EntityMaid maid) {
        try {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            maid.getNavigationManager().resetNavigation();
        } catch (Throwable ignored) {
        }
    }
}
