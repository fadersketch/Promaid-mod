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
 * 本类保留这三条的**观感**（朝目标点飞 / 到点收干 / {@code -atan2(dx,dz)} 取朝向），两处改动：
 * <ol>
 *   <li>站位距离从凋灵的"碰撞箱大小"（近战贴脸）换成可配的 {@code combat.broomRange}
 *       （默认 8 格）——她要拿远程武器打，贴脸没意义；</li>
 *   <li><b>推进方式：增量累加 → 速度直接由距离决定。</b>凋灵那句
 *       {@code delta += d.scale(speed*0.05/len)} 之所以稳定，靠的是原版 {@code travel}
 *       每 tick 给速度乘一次空气阻力（0.91）；本模式的扫帚走的是 mixin 接管、**travel 被
 *       cancel**，没有那层阻力 → 增量式退化成无阻尼振荡器（空中上下摆动，实测六百五十五）。
 *       详见 {@link #steerTo}。</li>
 * </ol>
 * 另加两条凋灵没有的：**水平/垂直分别限速**与**起飞相位**（骑上先垂直抬起 1 格，
 * 见 {@link #riseTarget}）与**方位角缓慢旋转**（见 {@link #combatPoint}）。
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
    /** 扫帚 UUID → 起飞相位要爬到的 Y（骑上那一刻的 Y + {@link #RISE_BLOCKS}） */
    private static final Map<UUID, Double> RISE = new HashMap<>();

    /** 起飞相位的高度（格）——玩家要求"女仆会立刻用扫帚飞起来 1 格" */
    private static final double RISE_BLOCKS = 1.0;
    /** 到达判定（格）：进入这个距离视为"已到位"，速度直接清零悬停 */
    private static final double ARRIVE = 0.35;
    /**
     * 到点减速带（格）：距离小于此值开始线性降速（速度 ∝ 距离），到点自然收干不冲过头。
     * <p>
     * 这一条**取代**了凋灵 MoveControl 那句 `delta.add(d.scale(speed * 0.05 / len))` 的
     * 增量式推进——原因见 {@link #steerTo} 的长注释（实测六百五十五 的真凶：
     * 增量式在"我们接管了 travel"的场景里是一个**无阻尼振荡器**）。
     */
    private static final double ARRIVE_RAMP = 1.5;
    /** 弧度→度（凋灵那行 `* 57.295776F`） */
    private static final float DEG = 57.295776F;
    /** 水平限速（格/tick）：0.35 ≈ 7 格/秒，比玩家冲刺略快、不至于甩掉主人 */
    private static final double MAX_H_SPEED = 0.35;
    /** 垂直限速（格/tick）：比水平更慢，免得上下猛蹿看着像抽搐 */
    private static final double MAX_V_SPEED = 0.22;
    /**
     * 战斗盘旋的**线速度**（格/tick）：0.14 ≈ 2.8 格/秒。
     * <p>
     * 取"线速度恒定"而不是"角速度恒定"，是因为盘旋半径可配（默认 8 格）：角速度固定时
     * 半径越大、目标点的圆周线速度越大（8 格半径 + 0.09 rad/tick = 0.72 格/tick，
     * 是 {@link #MAX_H_SPEED} 的两倍多）——她永远追不上那个点，表现成"绕不动、
     * 只在原地抖"。按线速度换算（{@code step = ORBIT_SPEED / 半径}）后，
     * 任何半径下她都能稳稳跟上。
     */
    private static final double ORBIT_SPEED = 0.14;
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
        if (taken.isEmpty()) {
            return null;
        }
        EntityBroom broom = null;
        try {
            broom = new EntityBroom(level);
            broom.moveTo(maid.getX(), maid.getY(), maid.getZ(), maid.getYRot(), 0.0f);
            try {
                broom.setOwnerUUID(maid.getOwnerUUID());
            } catch (Throwable ignored) {
            }
            if (!level.addFreshEntity(broom)) {
                giveBack(maid, taken);
                return null;
            }
            if (!maid.startRiding(broom)) {
                // 骑不上（例如她已经在骑别的东西）：把扫帚收掉、物品还她，不留孤儿实体
                broom.kill();
                giveBack(maid, taken);
                return null;
            }
        } catch (Throwable t) {
            if (broom != null) {
                try {
                    broom.kill();
                } catch (Throwable ignored) {
                }
            }
            giveBack(maid, taken);
            return null;
        }
        DEBT.put(broom.getUUID(), taken);
        ORBIT.remove(maid.getUUID());
        // 玩家要求："女仆会立刻用扫帚飞起来 1 格"——记下起飞相位（爬到她脚下 +1 格），
        // 由 MaidBroomBehavior 先垂直抬起来、抬到位再开始"去哪"的正常逻辑。
        // 原来这里直接塞一个向上的瞬时速度是不行的：同一 tick 里紧跟的 steerTo 会把它覆盖掉，
        // 而且"给一次速度"在没有 travel 阻尼的情况下根本不会停在 1 格处（见 steerTo 的长注释）。
        RISE.put(broom.getUUID(), maid.getY() + RISE_BLOCKS);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 取出扫帚骑上并起飞（消耗 " + taken.getCount() + "x "
                + taken.getHoverName().getString() + "，收工时原物归还）");
        return broom;
    }

    /**
     * 起飞相位的目标高度：还没爬到位就返回那个 Y（调用方应直奔它、先别管去哪打）；
     * 已经到位（或根本没有起飞记录）返回 null 并把记录清掉。
     */
    public static Double riseTarget(EntityBroom broom, double currentY) {
        if (broom == null) {
            return null;
        }
        Double to = RISE.get(broom.getUUID());
        if (to == null) {
            return null;
        }
        if (currentY >= to - 0.05) {
            RISE.remove(broom.getUUID());
            return null;
        }
        return to;
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
        ItemStack debt = DEBT.remove(broom.getUUID());
        try {
            maid.stopRiding();
        } catch (Throwable ignored) {
        }
        if (debt == null) {
            // 不是我们放出去的扫帚（玩家自己放的 / 别的模组）：一个字都不动，留给玩家
            return;
        }
        try {
            if (broom.isAlive()) {
                // kill() 只移除实体、**不**按原版掉落（原版掉落走 killEntity），物品由我们精确归还
                broom.kill();
            }
        } catch (Throwable ignored) {
        }
        forget(broom.getUUID());
        giveBack(maid, debt);
        com.maidsmart.tool.PromaidLog.log("扫帚模式", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 收工下扫帚，扫帚已放回背包");
    }

    /* ==================== 意图：写 / 读 ==================== */

    public static void setThrust(EntityBroom broom, Vec3 vel, float yaw) {
        if (broom == null || vel == null) {
            return;
        }
        THRUST.put(broom.getUUID(), vel);
        YAW.put(broom.getUUID(), yaw);
    }

    /** mixin 取走本 tick 的推进矢量（取走即清：没有新意图的 tick 就悬停原地，绝不自由落体） */
    public static Vec3 takeThrust(EntityBroom broom) {
        if (broom == null) {
            return null;
        }
        return THRUST.remove(broom.getUUID());
    }

    /** mixin 取朝向（保留最后写入的那个，悬停时朝向不抖） */
    public static float yaw(EntityBroom broom) {
        if (broom == null) {
            return 0.0f;
        }
        Float f = YAW.get(broom.getUUID());
        return f == null ? broom.getYRot() : f;
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
        YAW.put(broom.getUUID(), yaw);
    }

    /* ==================== 去哪：战斗盘旋 / 平时跟随 ==================== */

    /**
     * 战斗时的目标点——**凋灵式**：保持在目标的水平外圈、缓慢绕着她转、高度压在她上方一点。
     *
     * 【为什么要绕而不是直线怼过去】原版凋灵是近战 boss，它的 MoveControl 只是"朝目标点飞"；
     * 那个目标点由 AI 每拍重设。而远程女仆必须**持续把距离拉开**才有输出窗口（贴脸会被近战
     * 反打），所以这里把"目标点"取成目标周围的**圆周上一点**，方位角每 tick 转
     * {@link #ORBIT_SPEED} 换算出来的那一步 —— 表现就是"绕着她转圈打"，这正是玩家说的
     * "同凋灵"的观感。
     *
     * @return 期望位置（已过 {@link MaidBroomKit#clampToHome} 夹取，见 {@link #steerTo}）
     */
    public static Vec3 combatPoint(EntityMaid maid, LivingEntity target) {
        UUID id = maid.getUUID();
        double r = Math.max(1.0, rangeCfg());
        // 角速度由固定线速度换算（见 ORBIT_SPEED 的注释）：任何半径下她都能跟上这个点
        double ang = ORBIT.getOrDefault(id, 0.0) + ORBIT_SPEED / r;
        ORBIT.put(id, ang);
        return new Vec3(target.getX() + Math.cos(ang) * r,
                target.getY() + hoverCfg(),
                target.getZ() + Math.sin(ang) * r);
    }

    /**
     * 平时（没有敌人）的目标点：**悬停在主人身边**——保持主人到她当前所在的这个方位、
     * 水平 {@link #FOLLOW_DIST} 格、高 {@link #FOLLOW_HEIGHT} 格。
     * 用"她当前方位"而不是固定方位，是为了她不会为了换边而横穿主人的脸。
     */
    public static Vec3 followPoint(EntityMaid maid, LivingEntity owner) {
        double dx = maid.getX() - owner.getX();
        double dz = maid.getZ() - owner.getZ();
        double ang = (Math.abs(dx) + Math.abs(dz) < 0.05) ? 0.0 : Math.atan2(dz, dx);
        return new Vec3(owner.getX() + Math.cos(ang) * FOLLOW_DIST,
                owner.getY() + FOLLOW_HEIGHT,
                owner.getZ() + Math.sin(ang) * FOLLOW_DIST);
    }

    /**
     * 把"想去哪"变成一个速度脉冲写进意图表——推进公式照搬凋灵 MoveControl 的**形状**
     * （朝目标点推进、到点收干、朝向 = 速度方向），但推进方式由"增量累加"改成"直接给速度"。
     *
     * <p>目标点先过 {@link MaidBroomKit#clampToHome}：这是"移动逻辑与 home 模式"那条账的落点
     * ——她骑上扫帚后 TLM 自身的范围约束整条失效，圈内这件事只剩这里把关。
     *
     * <p>── 实测六百五十五【"空中上下摆动、不朝主人/敌人飞"的真凶】──
     * 凋灵那套是 `delta = delta + d.scale(speed*0.05/len)`，**每一拍在上一拍的速度上再加一点**。
     * 它之所以在凋灵身上稳定，是因为凋灵走原版 {@code travel}——那里每 tick 会给 delta 乘一次
     * 空气阻力（0.91），"加一点 + 乘 0.91"配起来正好是一阶收敛：速度∝距离，到点自然收干。
     * <p>而本模式的扫帚**由 mixin 在 HEAD 处 cancel 掉 travel**（见
     * {@link com.maidsmart.mixin.EntityBroomMaidTravelMixin}）——阻力那一层没了。于是
     * `delta += d*k` 变成一个**无阻尼的谐振子**：她冲向目标点、速度不收、冲过去、反向加速、
     * 再冲回来……振幅 = 初始偏差、永不衰减。玩家看到的就是
     * "她升到空中以后在那儿上下摆动（鬼畜状态），脸朝着主人但不会朝主人飞"——因为垂直方向的
     * 偏差（悬停高 2 格）正好把它喂成了一个 1.4 秒周期的上下振荡，而水平方向她本来就
     * 站在跟随圈上，偏差≈0，所以"看不出在飞"。
     * <p>修法：**速度直接由距离决定**（比例导引 + 到点减速带），不做任何累加。
     * 这样"位移 → 速度 → 位移"是一阶系统，数学上不可能振荡；同时保留玩家要的凋灵观感：
     * 朝目标点飞、到点停住悬停、朝向 = 速度方向。
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
        double dx = aim.x - broom.getX();
        double dy = aim.y - broom.getY();
        double dz = aim.z - broom.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (dist < ARRIVE) {
            // 到点：速度清零原地悬停（mixin 拿到零矢量就是"零速悬停"，不会自由落体）
            setThrust(broom, Vec3.ZERO, yaw(broom));
            return;
        }
        // 到点减速带：远处全速、近了按距离线性收干（分子分母同量纲，dist→0 时速度→0）
        double speed = Math.min(1.0, dist / ARRIVE_RAMP);
        double inv = 1.0 / dist;
        Vec3 nv = new Vec3(dx * inv * MAX_H_SPEED * speed,
                dy * inv * MAX_V_SPEED * speed,
                dz * inv * MAX_H_SPEED * speed);
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
        RISE.remove(broomId);
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
        RISE.clear();
    }

    /* ==================== 内部工具 ==================== */

    /** 从她身上抽 1 件扫帚物品（主手 → 副手 → 背包 → 额外容器）；没有则空栈 */
    private static ItemStack takeBroomItem(EntityMaid maid) {
        try {
            net.neoforged.neoforge.items.IItemHandlerModifiable h =
                    (net.neoforged.neoforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper();
            for (int slot = 0; slot <= 1; slot++) {
                if (MaidBroomKit.isBroomItem(h.getStackInSlot(slot))) {
                    ItemStack one = h.extractItem(slot, 1, false);
                    if (!one.isEmpty()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 额外容器（精妙背包 / 旅行者背包）里有的先请 TLM 搬进来一个（与 MaidFlightKit 同款）
            com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidBroomKit::isBroomItem, 1);
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (MaidBroomKit.isBroomItem(inv.getStackInSlot(i))) {
                    ItemStack one = inv.extractItem(i, 1, false);
                    if (!one.isEmpty()) {
                        return one;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
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
