package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.3.0「扫帚模式」的骑乘 / 悬停 / 战斗盘旋驱动（两树镜像）。
 *
 * ── 为什么必须由我们驱动（TLM 的扫帚 API 不够用）──
 * TLM 的扫帚是**真载具**（{@code EntityBroom extends AbstractEntityFromItem extends LivingEntity}），
 * 也确实留了 {@code IBroomControl} 扩展点。但字节码实证：
 * <pre>
 *   EntityBroom.getControllingPassenger()  →  只有【第一乘客是 Player】时才返回非空
 *   EntityBroom.travel(Vec3)               →  只有"控制乘客是 Player 且第二乘客是 EntityMaid"
 *                                             才遍历 broomControls 调 IBroomControl；
 *                                             否则走重力下坠分支
 * </pre>
 * 也就是说 {@code IBroomControl} 的**每一个回调都要求一个 Player 参数**——它是给
 * "玩家驾驶、女仆搭乘"设计的。**女仆单骑**这条路上没有任何回调点：她一个人骑上去，
 * {@code getControllingPassenger()} 返回 null，扫帚直接自由落体。
 * 所以"女仆独立骑扫帚"只能由我们在 {@code EntityBroom.travel} 上接管
 * （见 {@link com.maidsmart.mixin.EntityBroomMaidTravelMixin}）：
 * **没有玩家驾驶时**，用本类写下的推进意图替它飞；**有玩家驾驶时一个字都不改**。
 *
 * ── 本类与行为的分工 ──
 * <ul>
 *   <li>{@link MaidBroomBehavior} 是"大脑"：决定**去哪**（打谁、跟谁、守在哪）；</li>
 *   <li>本类是"手"：把"去哪"翻译成**这一 tick 的速度矢量与朝向**，写在 {@link #THRUST} /
 *       {@link #YAW} 里，由 mixin 读取并消费。</li>
 * </ul>
 * 写在表里而不是直接 {@code move()}，是因为**行为 tick 与实体 tick 的先后顺序不保证**
 * （服务端按实体列表顺序），中间隔一 tick 的延迟完全无感，但"谁在什么时候动"这件事
 * 必须只有一个执行者——真正调 {@code move()} 的永远只有 mixin 那一处。
 *
 * ── 推进公式（凋灵式，字节码取证）──
 * 用户要求"战斗时运动的逻辑就同凋灵"。原版凋灵的移动不在 {@code WitherBoss.customServerAiStep}
 * 里，而在它的内部类 {@code WitherBoss$WitherMoveControl.tick()}（javap 实证，常量逐个对上）：
 * <pre>
 *   Vec3 d  = wantedPos - wither.pos;
 *   double len = d.length();
 *   if (len &lt; boundingBox.size) {            // 到点
 *       wither.setDeltaMovement(delta.scale(0.5));          // ← 0.5 阻尼
 *   } else {
 *       wither.setDeltaMovement(delta.add(d.scale(speed * 0.05 / len)));   // ← 0.05 推进系数
 *       wither.setYRot(-((float) Mth.atan2(d.x, d.z)) * 57.295776F);       // ← 朝向 = 速度方向
 *   }
 * </pre>
 * 本类**照搬这三条**（0.05 推进 / 0.5 到点阻尼 / {@code -atan2(dx,dz)} 取朝向），只改一处：
 * 站位距离从凋灵的"碰撞箱大小"（近战贴脸）换成可配的 {@code combat.broomRange}（默认 8 格）
 * ——她要拿远程武器打，贴脸没意义。另加两条凋灵没有的：**水平/垂直分别限速**（凋灵靠
 * MoveControl 自己收，我们没有那一层）与**方位角缓慢旋转**（见 {@link #combatPoint}）。
 */
public final class MaidBroomDrive {

    private MaidBroomDrive() {
    }

    /* ==================== 状态表（按 UUID，异常一律吞掉） ==================== */

    /** 扫帚 UUID → 本 tick 的推进矢量（行为写、mixin 读后**移除**） */
    private static final Map<UUID, Vec3> THRUST = new HashMap<>();
    /** 扫帚 UUID → 本 tick 的朝向（度） */
    private static final Map<UUID, Float> YAW = new HashMap<>();
    /**
     * 我们放出去的那些扫帚：扫帚 UUID → **当初从她身上抽走的那一件物品**。
     * 收工时按这一份精确归还（连自定义名字一起），而不是让原版 {@code killEntity()} 掉在地上
     * ——那样东西会散在离她几格远的地方，玩家得自己去捡。
     */
    private static final Map<UUID, ItemStack> DEBT = new HashMap<>();
    /** 女仆 UUID → 战斗盘旋的方位角（弧度），每 tick 缓慢增长 → "绕着她打" */
    private static final Map<UUID, Double> ORBIT = new HashMap<>();

    /** 起飞/爬升的瞬时抬升速度（"立刻用扫帚飞起来 1 格"）：0.42 与飞行跟随的起跳同一档 */
    private static final double RISE_VELOCITY = 0.42;
    /** 到达判定（格）：与凋灵的"碰撞箱大小"同一量级 */
    private static final double ARRIVE = 0.4;
    /** 凋灵 MoveControl 的推进系数（`delta.add(d.scale(speed * 0.05 / len))` 里的 0.05） */
    private static final double WITHER_ACCEL = 0.05;
    /** 到点后的阻尼（凋灵 `delta.scale(0.5)`） */
    private static final double ARRIVE_DAMP = 0.5;
    /** 弧度→度（凋灵那行 `* 57.295776F`） */
    private static final float DEG = 57.295776F;
    /** 水平限速（格/tick）：0.35 ≈ 7 格/秒，比玩家冲刺略快、不至于甩掉主人 */
    private static final double MAX_H_SPEED = 0.35;
    /** 垂直限速（格/tick）：比水平更慢，免得上下猛蹿看着像抽搐 */
    private static final double MAX_V_SPEED = 0.28;
    /** 盘旋角速度（弧度/tick）：0.09 ≈ 一圈 70 tick（3.5 秒），肉眼能看出"在绕" */
    private static final double ORBIT_STEP = 0.09;
    /** 平时跟随的水平距离（格） */
    private static final double FOLLOW_DIST = 3.5;
    /** 平时跟随的高度（格，相对主人脚下） */
    private static final double FOLLOW_HEIGHT = 2.0;

    /* ==================== 骑上 / 下来 ==================== */

    /**
     * 确保她骑在一把扫帚上（已经骑着就原样返回）。
     *
     * 【扫帚从哪来】按玩家（未作答的选择题）的推荐项：**用她背包里的**——主手 → 副手 →
     * 背包 → 精妙背包/旅行者背包这类额外容器，抽走 1 件，在**她脚下**放出一把
     * {@code EntityBroom}（与玩家手持扫帚右键放置同款：{@code setOwnerUUID(主人)} +
     * 入世界 + 消耗那件物品），然后骑上去。收工时把**这一件**精确还回她背包
     * （见 {@link #dismount}），所以扫帚不会损耗、也不会掉在地上。
     *
     * 【为什么认"主人"而不是"她"】{@code EntityBroom.canMaidRide} 要求女仆与扫帚的
     * ownerUUID 相等（字节码实证）；写成女仆自己会让别的女仆被 {@code pushEntities}
     * 顺带拽上来。挂主人头上与玩家自己放置完全一致。
     *
     * @return 骑上的那把扫帚；null = 没扫帚 / 放不出来（调用方按"缺扫帚"处理）
     */
    public static EntityBroom ensureMounted(ServerLevel level, EntityMaid maid) {
        if (level == null || maid == null) {
            return null;
        }
        EntityBroom riding = MaidBroomKit.ridingBroom(maid);
        if (riding != null) {
            return riding;
        }
        ItemStack taken = takeBroomItem(maid);
        if (taken.m_41619_()) {
            return null;
        }
        EntityBroom broom = null;
        try {
            broom = new EntityBroom(level);
            broom.m_7678_(maid.m_20185_(), maid.m_20186_(), maid.m_20189_(), maid.m_146908_(), 0.0f);
            try {
                broom.setOwnerUUID(maid.m_21805_());
            } catch (Throwable ignored) {
            }
            if (!level.m_7967_(broom)) {
                giveBack(maid, taken);
                return null;
            }
            if (!maid.m_20329_(broom)) {
                // 骑不上（例如她已经在骑别的东西）：把扫帚收掉、物品还她，不留孤儿实体
                broom.m_6075_();
                giveBack(maid, taken);
                return null;
            }
        } catch (Throwable t) {
            if (broom != null) {
                try {
                    broom.m_6075_();
                } catch (Throwable ignored) {
                }
            }
            giveBack(maid, taken);
            return null;
        }
        DEBT.put(broom.m_20148_(), taken);
        ORBIT.remove(maid.m_20148_());
        // 用户要求："女仆会立刻用扫帚飞起来 1 格"——骑上来的第一 tick 直接给一个向上的速度
        setThrust(broom, new Vec3(0.0, RISE_VELOCITY, 0.0), maid.m_146908_());
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 取出扫帚骑上并起飞（消耗 " + taken.m_41613_() + "x "
                + taken.m_41786_().getString() + "，收工时原物归还）");
        return broom;
    }

    /** 收工：下扫帚 + 把当初那件扫帚**精确**还回她背包（背包塞不下就落脚下，走 MaidGiveBack） */
    public static void dismount(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        if (broom == null) {
            return;
        }
        ItemStack debt = DEBT.remove(broom.m_20148_());
        try {
            maid.m_8127_();
        } catch (Throwable ignored) {
        }
        if (debt == null) {
            // 不是我们放出去的扫帚（玩家自己放的 / 别的模组）：一个字都不动，留给玩家
            return;
        }
        try {
            if (broom.m_6084_()) {
                // kill() 只移除实体、**不**按原版掉落（原版掉落走 killEntity），物品由我们精确归还
                broom.m_6075_();
            }
        } catch (Throwable ignored) {
        }
        forget(broom.m_20148_());
        giveBack(maid, debt);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 收工下扫帚，扫帚已放回背包");
    }

    /* ==================== 意图：写 / 读 ==================== */

    public static void setThrust(EntityBroom broom, Vec3 vel, float yaw) {
        if (broom == null || vel == null) {
            return;
        }
        THRUST.put(broom.m_20148_(), vel);
        YAW.put(broom.m_20148_(), yaw);
    }

    /** mixin 取走本 tick 的推进矢量（取走即清：没有新意图的 tick 就悬停原地，绝不自由落体） */
    public static Vec3 takeThrust(EntityBroom broom) {
        if (broom == null) {
            return null;
        }
        return THRUST.remove(broom.m_20148_());
    }

    /** mixin 取朝向（保留最后写入的那个，悬停时朝向不抖） */
    public static float yaw(EntityBroom broom) {
        if (broom == null) {
            return 0.0f;
        }
        Float f = YAW.get(broom.m_20148_());
        return f == null ? broom.m_146908_() : f;
    }

    /**
     * 覆盖本 tick 的朝向——战斗时用"看着目标"而不是"朝着速度方向"：她一边绕圈一边射击，
     * 脸得对着目标（弓弦/枪口朝哪是表现，弹道一直按坐标算，但观感全靠这一条）。
     * 必须**在 {@link #steerTo} 之后**调用，否则会被 steerTo 写的速度方向盖掉。
     */
    public static void faceYaw(EntityBroom broom, float yaw) {
        if (broom == null) {
            return;
        }
        YAW.put(broom.m_20148_(), yaw);
    }

    /* ==================== 去哪：战斗盘旋 / 平时跟随 ==================== */

    /**
     * 战斗时的目标点——**凋灵式**：保持在目标的水平外圈、缓慢绕着她转、高度压在她上方一点。
     *
     * 【为什么要绕而不是直线怼过去】原版凋灵是近战 boss，它的 MoveControl 只是"朝目标点飞"；
     * 那个目标点由 AI 每拍重设。而远程女仆必须**持续把距离拉开**才有输出窗口（贴脸会被近战
     * 反打），所以这里把"目标点"取成目标周围的**圆周上一点**，方位角每 tick 转
     * {@link #ORBIT_STEP} —— 表现就是"绕着她转圈打"，这正是玩家说的"同凋灵"的观感。
     *
     * @return 期望位置（已过 {@link MaidBroomKit#clampToHome} 夹取，见 {@link #steerTo}）
     */
    public static Vec3 combatPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.m_20148_();
        double ang = ORBIT.getOrDefault(id, 0.0) + ORBIT_STEP;
        ORBIT.put(id, ang);
        double r = rangeCfg();
        return new Vec3(target.m_20185_() + Math.cos(ang) * r,
                target.m_20186_() + hoverCfg(),
                target.m_20189_() + Math.sin(ang) * r);
    }

    /**
     * 平时（没有敌人）的目标点：**悬停在主人身边**——保持主人到她当前所在的这个方位、
     * 水平 {@link #FOLLOW_DIST} 格、高 {@link #FOLLOW_HEIGHT} 格。
     * 用"她当前方位"而不是固定方位，是为了她不会为了换边而横穿主人的脸。
     */
    public static Vec3 followPoint(EntityMaid maid, LivingEntity owner) {
        double dx = maid.m_20185_() - owner.m_20185_();
        double dz = maid.m_20189_() - owner.m_20189_();
        double ang = (Math.abs(dx) + Math.abs(dz) < 0.05) ? 0.0 : Math.atan2(dz, dx);
        return new Vec3(owner.m_20185_() + Math.cos(ang) * FOLLOW_DIST,
                owner.m_20186_() + FOLLOW_HEIGHT,
                owner.m_20189_() + Math.sin(ang) * FOLLOW_DIST);
    }

    /**
     * 把"想去哪"变成一个速度脉冲写进意图表——**推进公式照搬凋灵 MoveControl**（见类文档）。
     *
     * 目标点先过 {@link MaidBroomKit#clampToHome}：这是"移动逻辑与 home 模式"那条账的落点
     * ——她骑上扫帚后 TLM 自身的范围约束整条失效，圈内这件事只剩这里把关。
     */
    public static void steerTo(EntityMaid maid, Vec3 desired) {
        if (maid == null || desired == null) {
            return;
        }
        EntityBroom broom = MaidBroomKit.ridingBroom(maid);
        if (broom == null) {
            return;
        }
        Vec3 aim = MaidBroomKit.clampToHome(maid, desired);
        if (aim == null) {
            return;
        }
        double dx = aim.f_82479_ - broom.m_20185_();
        double dy = aim.f_82480_ - broom.m_20186_();
        double dz = aim.f_82481_ - broom.m_20189_();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        Vec3 v = broom.m_20184_();
        if (v == null) {
            v = Vec3.f_82478_;
        }
        if (dist < ARRIVE) {
            // 到点：阻尼（凋灵 `delta.scale(0.5)`）；朝向保持上一拍，免得原地打转
            setThrust(broom, v.m_82490_(ARRIVE_DAMP), yaw(broom));
            return;
        }
        double inv = 1.0 / dist;
        Vec3 nv = v.m_82549_(new Vec3(dx * inv * WITHER_ACCEL, dy * inv * WITHER_ACCEL, dz * inv * WITHER_ACCEL));
        // 限速：凋灵有 MoveControl 那一层替它收，我们没有，必须自己夹
        double hs = Math.sqrt(nv.f_82479_ * nv.f_82479_ + nv.f_82481_ * nv.f_82481_);
        if (hs > MAX_H_SPEED && hs > 1.0E-6) {
            double k = MAX_H_SPEED / hs;
            nv = new Vec3(nv.f_82479_ * k, nv.f_82480_, nv.f_82481_ * k);
        }
        if (nv.f_82480_ > MAX_V_SPEED) {
            nv = new Vec3(nv.f_82479_, MAX_V_SPEED, nv.f_82481_);
        } else if (nv.f_82480_ < -MAX_V_SPEED) {
            nv = new Vec3(nv.f_82479_, -MAX_V_SPEED, nv.f_82481_);
        }
        // 朝向 = 速度方向（凋灵 `-atan2(d.x, d.z) * 57.295776`）；纯垂直位移时不改朝向
        float yaw = horiz > 0.15 ? (float) (-Math.atan2(dx, dz) * DEG) : yaw(broom);
        setThrust(broom, nv, yaw);
    }

    /* ==================== 清理 ==================== */

    /** 这具扫帚实体没了：丢掉它的意图与"欠账"（物品已由原版掉落兜底，不能再还一次） */
    public static void forget(UUID broomId) {
        if (broomId == null) {
            return;
        }
        THRUST.remove(broomId);
        YAW.remove(broomId);
        DEBT.remove(broomId);
    }

    /** 这只女仆下线/卸载：清掉她的盘旋相位与欠账（她骑的那把扫帚随后自然落地） */
    public static void forgetMaid(UUID maidId) {
        if (maidId == null) {
            return;
        }
        ORBIT.remove(maidId);
    }

    /** 服务端停止：整表清空 */
    public static void clearAll() {
        THRUST.clear();
        YAW.clear();
        DEBT.clear();
        ORBIT.clear();
    }

    /* ==================== 内部工具 ==================== */

    /** 从她身上抽 1 件扫帚物品（主手 → 副手 → 背包 → 额外容器）；没有则空栈 */
    private static ItemStack takeBroomItem(EntityMaid maid) {
        try {
            net.minecraftforge.items.IItemHandlerModifiable h =
                    (net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            for (int slot = 0; slot <= 1; slot++) {
                if (MaidBroomKit.isBroomItem(h.getStackInSlot(slot))) {
                    ItemStack one = h.extractItem(slot, 1, false);
                    if (!one.m_41619_()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 额外容器（精妙背包 / 旅行者背包）里有的先请 TLM 搬进来一个（与 MaidFlightKit 同款）
            com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidBroomKit::isBroomItem, 1);
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (MaidBroomKit.isBroomItem(inv.getStackInSlot(i))) {
                    ItemStack one = inv.extractItem(i, 1, false);
                    if (!one.m_41619_()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    private static void giveBack(EntityMaid maid, ItemStack stack) {
        com.maidsmart.tool.MaidGiveBack.give(maid, stack, "扫帚模式");
    }

    /** 战斗盘旋距离（格） */
    private static double rangeCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_RANGE.get();
        } catch (Throwable ignored) {
            return 8.0;
        }
    }

    /** 悬停高度（格，相对目标脚底） */
    private static double hoverCfg() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_HOVER.get();
        } catch (Throwable ignored) {
            return 2.0;
        }
    }
}
