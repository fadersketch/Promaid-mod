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
 * v1.2.5 实测六百五十六【仿创造飞行 · 控制器】——让女仆悬浮并自由升降（创造模式飞行的手感）。
 *
 * 【这一步解决什么】整合包里给"创造飞行/悬浮"的手段五花八门（饰品、护甲套装、药水效果、
 * 重力归零类法术……），而它们几乎全部只对**玩家**生效；女仆拿到手上一字不动。本控制器不去
 * 复刻每个物品的物理，而是：**只要她有资格（见 {@link MaidFreeFlightKit}），我们就托住她**——
 * `setNoGravity(true)` + 每 tick 直接给速度，形成"悬停 + 平滑位移"的创造飞行手感。
 *
 * 【为什么挂在事件上而不是 brain 行为（实测六百五十六 记录）】最初写的是 core 行为
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

    /** 当前是否由我们托着（UUID → true） */
    private static final Map<UUID, Boolean> FLYING = new HashMap<>();
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

    /* ---------------- 手动触发 ---------------- */

    public static void setDebugTarget(EntityMaid maid, Vec3 pos) {
        DEBUG_TARGET.put(maid.getUUID(), pos);
    }

    public static void clearDebugTarget(EntityMaid maid) {
        DEBUG_TARGET.remove(maid.getUUID());
    }

    public static boolean isFlying(EntityMaid maid) {
        return maid != null && Boolean.TRUE.equals(FLYING.get(maid.getUUID()));
    }

    /** 能不能飞（命令校验与主循环共用同一口径） */
    public static boolean canFly(EntityMaid maid) {
        try {
            return maid != null && maid.isAlive() && MaidFreeFlightKit.isModeActive(maid) && !blocked(maid);
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

    public static void tick(EntityMaid maid) {
        try {
            UUID id = maid.getUUID();
            if (!maid.isAlive()) {
                release(maid, "她没了");
                return;
            }
            boolean soft = SOFT_LAND_START.containsKey(id);
            boolean flying = Boolean.TRUE.equals(FLYING.get(id));

            // ① 软着陆相位：只管把她稳稳放下去（保持无重力 + 恒定慢速下降）
            if (soft) {
                if (maid.onGround() || expired(maid)) {
                    release(maid, "已着陆");
                    return;
                }
                // 【必须保持无重力】第一版忘了这一条：重力一回来她就在软着陆期间加速下坠，
                // 实测 4 秒掉了 23 格（等于没做缓冲）。这里恒速下降 + 每 tick 清零坠落距离，
                // 保证"软着陆"真的是软的（20 血的女仆摔一下就没）。
                maid.setNoGravity(true);
                maid.setDeltaMovement(0.0, -SOFT_LAND_SPEED, 0.0);
                maid.hasImpulse = true;
                maid.hurtMarked = true;
                maid.resetFallDistance();
                suppressWalk(maid);
                return;
            }
            // ② 没在飞：够资格就起飞
            if (!flying) {
                if (!canFly(maid)) {
                    return;
                }
                FLYING.put(id, true);
                maid.setNoGravity(true);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 起飞（" + MaidFreeFlightKit.diag(maid) + "）");
            }
            // ③ 在飞：能力/状态不允许、或落水进岩浆 → 软着陆收工
            if (!canFly(maid) || maid.isInWater() || maid.isInLava()) {
                beginSoftLanding(maid, maid.isInWater() || maid.isInLava() ? "落水/岩浆" : "能力消失或状态变化");
                return;
            }
            // ④ 正常飞行：选目标点 → 一阶转向 → 保持无重力 → 抢移动
            Vec3 aim = pickAim(maid);
            steer(maid, aim, true);
            maid.setNoGravity(true);
            suppressWalk(maid);
            // 调试目标到达即收工
            Vec3 dbg = DEBUG_TARGET.get(id);
            if (dbg != null && maid.position().distanceTo(dbg) <= 1.0) {
                beginSoftLanding(maid, "已到指定坐标");
            }
        } catch (Throwable t) {
            // 异常也保持悬停（不放手、不放重力），下一 tick 再试
            try {
                maid.setNoGravity(true);
                maid.setDeltaMovement(Vec3.ZERO);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 内部 ---------------- */

    private static boolean expired(EntityMaid maid) {
        Long at = SOFT_LAND_START.get(maid.getUUID());
        return at != null && maid.tickCount - at > SOFT_LAND_MAX * 20;
    }

    private static void beginSoftLanding(EntityMaid maid, String why) {
        try {
            UUID id = maid.getUUID();
            if (!SOFT_LAND_START.containsKey(id)) {
                SOFT_LAND_START.put(id, (long) maid.tickCount);
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 开始软着陆（" + why + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void release(EntityMaid maid, String reason) {
        try {
            UUID id = maid.getUUID();
            boolean was = FLYING.remove(id) != null;
            SOFT_LAND_START.remove(id);
            DEBUG_TARGET.remove(id);
            if (maid.isAlive()) {
                maid.setNoGravity(false);
            }
            if (was || reason != null && !"她没了".equals(reason)) {
                PromaidLog.log("仿创造飞行", PromaidLog.nameOf(maid) + " 收工（" + reason + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 目标点：调试坐标 → 主人身后的跟随圈（同扫帚口径）→ 原地悬停 */
    private static Vec3 pickAim(EntityMaid maid) {
        Vec3 dbg = DEBUG_TARGET.get(maid.getUUID());
        if (dbg != null) {
            return dbg;
        }
        LivingEntity owner = maid.getOwner();
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
    private static void steer(EntityMaid maid, Vec3 desired, boolean vertical) {
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
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz > 0.15) {
                float yaw = (float) (-Math.atan2(dx, dz) * MaidFreeFlightKit.DEG);
                maid.setYRot(yaw);
                maid.yRotO = yaw;
                maid.setYHeadRot(yaw);
                maid.setYBodyRot(yaw);
            }
        } catch (Throwable ignored) {
        }
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
