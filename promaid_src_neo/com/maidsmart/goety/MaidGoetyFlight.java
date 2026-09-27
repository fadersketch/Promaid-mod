package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实测七百〇四【第三种飞行：外部持续推进（Goety 飞行聚晶）】。
 *
 * <h2>它和另外两种飞行的分工</h2>
 * <ul>
 *   <li><b>鞘翅</b>＝战斗机（高速、要配机动武器）；<b>仿创造飞行</b>＝直升机（慢、稳、灵活）；</li>
 *   <li><b>本档＝喷气式</b>：推力**由外部法术给**（飞行聚晶每 tick 把速度覆盖成
 *       视线方向 × 0.5~1.0 格/tick），我们**一个速度都不写**，只做三件事：
 *       <b>瞄准 → 续放 → 到地方收手</b>。</li>
 * </ul>
 *
 * <h2>为什么"瞄准就是驾驶"</h2>
 * Goety 的飞行聚晶（{@code FlyingSpell}）每次施放的唯一效果是
 * {@code caster.setDeltaMovement(getLookAngle() × d0)}（d0 = 0.5，风之魔杖 1.0）。
 * 所以**她的视线方向就是推力方向**——转向 = 改她的 yaw/pitch，别的一概不用管。
 *
 * <h2>无头实测（2026-09-27，本工程第一次用真 Goety 跑通）</h2>
 * <pre>
 *   每 tick 放一发 → 位置 (0.5, -60.000, 15.5→55.5)、速度恒为 (0, -0.000, 0.500)、
 *   NoGravity 始终 false、着地 false；停下后立刻 (0, -0.078, 0.455)（重力 + 0.91 阻力）。
 * </pre>
 * 三条结论：①**恒速不衰减**（每 tick 覆盖）；②**不掉高**（每 tick 覆盖把重力压住，
 * 而 Goety 自己**不设** NoGravity）；③**女仆的 travel/MoveControl 不会吃掉它**
 * （这一条本来是最大的未知——Goety 玩家版不用管，女仆是 Mob，`aiStep` 阶段会改速度）。
 *
 * <h2>为什么这里可以"硬着陆"而不用软着陆</h2>
 * {@code FlyingSpell.SpellResult} 里有一句 {@code caster.fallDistance = 0}——**施法期间坠落
 * 距离永远归零**。所以降落只要"保持施法、把头压下去"，落到贴地再收手（最后那 1 格之内
 * 的坠落距离摔不动她）。这一点与仿创造飞行相反：那边没有这一句，所以必须自己写软着陆。
 *
 * <h2>与上游的边界</h2>
 * 本文件**独立成档**：不碰 {@code MaidFreeFlightController}（那是上游接手的文件），
 * 只用公开的 TLM 事件与自己的状态表。将来要并给作者时，删掉这个包就是"没有 Goety 兼容"。
 */
public final class MaidGoetyFlight {

    /** 发射所用法术键（{@link MaidGoetyCompat#cast} 的口径）。 */
    private static final String SPELL_FLYING = "flying";

    /** 巡航时的转向速度（度/tick）——比仿创造飞行快（那边 12°，因为它是"悬停微调"）。 */
    private static final float YAW_STEP = 18.0F;
    /** 俯仰转向速度（度/tick）。 */
    private static final float PITCH_STEP = 12.0F;
    /** 巡航俯仰限幅（度）：别让她一直往上/往下扎。 */
    private static final float PITCH_LIMIT = 55.0F;

    /** 水平到位半径（格）：进了这个圈就转下降。 */
    private static final double ARRIVE_H = 2.2;
    /** 跟随时"够近"的距离（格）：近了就绕圈伴飞，不再直冲。 */
    private static final double FOLLOW_HOLD = 4.0;
    /** 下落阶段瞄的"下方偏移"（格）：越小越陡。6 格前 + 3 格下 ≈ -27° → 约 4.6 格/秒。 */
    private static final double DESCEND_AHEAD = 6.0;
    private static final double DESCEND_DOWN = 3.0;
    /** 离地这么近就收手（格）。 */
    private static final double GROUND_STOP = 1.2;
    /** 单个任务的最长时长（tick）——30 秒。 */
    private static final long MAX_TICKS = 600L;

    private enum Phase { CRUISE, DESCEND }

    /** 一个"持续推进"任务。 */
    private record Task(boolean follow, UUID targetId, Vec3 point, ItemStack staff, Phase phase, long start) {
    }

    private static final Map<UUID, Task> TASKS = new LinkedHashMap<>();
    /** 临时诊断计数（每 5 tick 打一行）。 */
    private static int DBG;
    private static boolean hooked;

    private MaidGoetyFlight() {
    }

    /** 幂等挂载（命令注册时调一次）。 */
    public static void ensureHooked() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidGoetyFlight());
    }

    public static boolean isActive(EntityMaid maid) {
        return maid != null && TASKS.containsKey(maid.getUUID());
    }

    /**
     * 【保真门禁】她身上必须**真的带着飞行聚晶**（{@code goety:flying_focus}）——
     * 法杖当前槽 / 聚晶包里 / 任意容器里都算。返回 null 表示可以起飞，否则返回拒绝原因。
     */
    public static String requireFocus(EntityMaid maid) {
        if (!MaidGoetyCompat.available()) {
            return "服务器没装 Goety（或版本反射路径不符）";
        }
        if (!MaidGoetyCompat.hasFocus(maid, MaidGoetyCompat.FOCUS_FLYING)) {
            return "她身上没有飞行聚晶（" + MaidGoetyCompat.FOCUS_FLYING + "）——"
                    + "把聚晶放进风之魔杖插到她身上/背包/饰品栏，或放进聚晶包";
        }
        return null;
    }

    /** 飞到某个坐标（一次性：巡航 → 下降 → 收手）。返回 null 表示已开始，否则是拒绝原因。 */
    public static String flyTo(EntityMaid maid, Vec3 point) {
        String bad = requireFocus(maid);
        if (bad != null) {
            return bad;
        }
        TASKS.put(maid.getUUID(), new Task(false, null, point, pickStaff(maid), Phase.CRUISE,
                maid.level().getGameTime()));
        return null;
    }

    /** 跟着某个实体飞（远了直追、近了绕圈伴飞）。返回 null 表示已开始，否则是拒绝原因。 */
    public static String follow(EntityMaid maid, Entity target) {
        String bad = requireFocus(maid);
        if (bad != null) {
            return bad;
        }
        TASKS.put(maid.getUUID(), new Task(true, target.getUUID(), null, pickStaff(maid), Phase.CRUISE,
                maid.level().getGameTime()));
        return null;
    }

    /** 收手（交还控制权，不再放法术）。 */
    public static void stop(EntityMaid maid, String why) {
        if (maid != null && TASKS.remove(maid.getUUID()) != null) {
            PromaidLog.log("Goety推进", maid.getName().getString() + " 收手（" + why + "）当前位置 "
                    + fmt(maid.position()));
        }
    }

    private static ItemStack pickStaff(EntityMaid maid) {
        for (ItemStack s : MaidGoetyCompat.staffs(maid).values()) {
            return s;   // 风之魔杖 → 威力翻倍；没有就空栈（基准 0.5 格/tick = 10 格/秒）
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        try {
            tick(event.getMaid());
        } catch (Throwable ignored) {
        }
    }

    private static void tick(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        Task task = TASKS.get(maid.getUUID());
        if (task == null) {
            return;
        }
        long now = maid.level().getGameTime();
        if (now - task.start() > MAX_TICKS) {
            stop(maid, "超时");
            return;
        }

        // 目标点：跟随档每 tick 重取（目标会动），坐标档固定
        Vec3 goal = task.point();
        if (task.follow()) {
            Entity t = maid.level() instanceof net.minecraft.server.level.ServerLevel sl
                    ? sl.getEntity(task.targetId()) : null;
            if (t == null || !t.isAlive()) {
                stop(maid, "目标没了");
                return;
            }
            goal = t.position();
        }
        if (goal == null) {
            stop(maid, "没有目标点");
            return;
        }

        double dh = Math.hypot(goal.x - maid.getX(), goal.z - maid.getZ());
        double dv = goal.y - maid.getY();

        Vec3 aim;
        Phase phase = task.phase();
        if (phase == Phase.CRUISE) {
            // 【实测七百〇四 修】到达判定必须**水平与垂直都到**：
            // 旧写法只对跟随档看 dv，于是"目标在头顶 20 格、她水平已进 2.2 格圈"会被判成到位
            // → 直接进 DESCEND → 看到贴地就收手（实测现场：飞往 (0.5,-40,0.5) 一秒后"收手（落地）"）。
            boolean arrived = dh < (task.follow() ? FOLLOW_HOLD : ARRIVE_H)
                    && Math.abs(dv) < (task.follow() ? 2.5 : 2.0);
            if (arrived) {
                if (task.follow()) {
                    // 跟随档不降落：绕圈伴飞（推力恒定 ⇒ 悬停不了，绕圈是最自然的"待命"）
                    double ang = Math.atan2(maid.getZ() - goal.z, maid.getX() - goal.x) + 0.9;
                    aim = new Vec3(goal.x + Math.cos(ang) * FOLLOW_HOLD, goal.y + 1.0,
                            goal.z + Math.sin(ang) * FOLLOW_HOLD);
                } else {
                    phase = Phase.DESCEND;
                    aim = descendAim(maid, goal);
                }
            } else {
                aim = goal;
            }
        } else {
            aim = descendAim(maid, goal);
            if (maid.onGround() || nearGround(maid)) {
                stop(maid, "落地");
                return;
            }
        }

        // 【实测七百〇四 修 2：卡在坑里抠出来】推力飞行器最典型的坑——
        // 落在 1 格深的坑/贴墙时，水平方向的推力全被碰撞吃掉（现场：位置一动不动、
        // 速度只剩重力、法术却在正常施放），于是永远出不来。判据用「贴地 或 撞墙」，
        // 命中就短暂抬头拔高（相当于给旋翼加总距），出了坑自然回到正常瞄准。
        if (phase == Phase.CRUISE && (maid.onGround() || maid.horizontalCollision)) {
            Vec3 flat = new Vec3(goal.x - maid.getX(), 0, goal.z - maid.getZ());
            if (flat.length() < 1.0E-4) {
                flat = new Vec3(maid.getLookAngle().x, 0, maid.getLookAngle().z);
            }
            flat = flat.normalize().scale(3.0);
            aim = maid.position().add(flat).add(0, 4.0, 0);
        }
        steer(maid, aim, phase);
        // 她自己写的走路目标与导航会跟"每 tick 覆盖速度"抢移动——从源头掐掉（与仿创造飞行同口径）
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        try {
            maid.getNavigation().stop();
        } catch (Throwable ignored) {
        }
        String err = MaidGoetyCompat.castDiag(maid, SPELL_FLYING, task.staff());
        // 【临时诊断】每 5 tick 打一行：法术放没放出去、放完那一瞬间速度是多少。
        // 用来分清"法术没生效"与"速度被别的东西抹掉"——两者现场长得一模一样（都只剩重力）。
        if (err != null) {
            // 法术放不出去是"致命的静默失败"（现场只剩重力、却看不出原因）——一定要留痕
            PromaidLog.log("Goety推进", maid.getName().getString() + " 施放失败：" + err);
        } else if (DBG % 20 == 0 || maid.onGround() || maid.horizontalCollision) {
            PromaidLog.log("Goety推进", maid.getName().getString()
                    + " dbg 位置 " + fmt(maid.position())
                    + " 放后速度 " + fmt(maid.getDeltaMovement())
                    + " pitch=" + String.format(java.util.Locale.ROOT, "%.1f", maid.getXRot())
                    + " 着地=" + maid.onGround()
                    + " err=" + (err == null ? "无" : err.substring(0, Math.min(220, err.length()))));
        }
        DBG++;
        TASKS.put(maid.getUUID(), new Task(task.follow(), task.targetId(), task.point(), task.staff(),
                phase, task.start()));
    }

    /** 下落阶段的瞄准点：往前 6 格、往下 3 格（约 -27°）——边往目标漂边降。 */
    private static Vec3 descendAim(EntityMaid maid, Vec3 goal) {
        Vec3 flat = new Vec3(goal.x - maid.getX(), 0, goal.z - maid.getZ());
        if (flat.length() < 1.0E-4) {
            flat = maid.getLookAngle().multiply(1, 0, 1);
        }
        flat = flat.normalize().scale(DESCEND_AHEAD);
        return maid.position().add(flat).add(0, -DESCEND_DOWN, 0);
    }

    /**
     * 转向：**限速**把她的 yaw/pitch 转过去。
     * 同时写 {@code setLookAt}——TLM 的 {@code LookControl.tick()} 每 tick 都会把俯仰掰回去，
     * 只写旋转会被它吃掉（这是本工程做鞘翅赶路时踩过的坑：单写 rotation 无效）。
     */
    private static void steer(EntityMaid maid, Vec3 aim, Phase phase) {
        double dx = aim.x - maid.getX();
        double dy = aim.y - (maid.getY() + maid.getEyeHeight());
        double dz = aim.z - maid.getZ();
        double dh = Math.hypot(dx, dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(dh, 1.0E-4))));
        if (phase == Phase.CRUISE) {
            wantPitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, wantPitch));
        }
        float yaw = approach(maid.getYRot(), wantYaw, YAW_STEP);
        float pitch = approach(maid.getXRot(), wantPitch, PITCH_STEP);
        maid.setYRot(yaw);
        maid.setXRot(pitch);
        maid.yRotO = yaw;
        maid.xRotO = pitch;
        try {
            maid.getLookControl().setLookAt(aim.x, aim.y, aim.z, 360.0F, 360.0F);
        } catch (Throwable ignored) {
        }
    }

    /** 角度逼近（处理跨 ±180 的环绕）。 */
    private static float approach(float from, float to, float step) {
        float d = net.minecraft.util.Mth.wrapDegrees(to - from);
        if (Math.abs(d) <= step) {
            return to;
        }
        return from + Math.copySign(step, d);
    }

    /** 脚下 3 格内有东西就算"贴着地"。 */
    private static boolean nearGround(EntityMaid maid) {
        BlockPos p = maid.blockPosition();
        for (int i = 1; i <= (int) Math.ceil(GROUND_STOP) + 1; i++) {
            BlockPos q = p.below(i);
            if (!maid.level().getBlockState(q).getCollisionShape(maid.level(), q).isEmpty()) {
                return maid.getY() - q.getY() < GROUND_STOP;
            }
        }
        return false;
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
