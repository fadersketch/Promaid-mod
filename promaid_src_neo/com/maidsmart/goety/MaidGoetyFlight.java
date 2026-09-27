package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实测 G-1/G-2/G-3【外部持续推进：把 Goety 的飞行聚晶当第三种飞行】。
 *
 * <h2>它在三种飞行里的位置</h2>
 * <ul>
 *   <li><b>鞘翅</b>＝战斗机（高速、要配机动武器，空袭体系围着它设计）；</li>
 *   <li><b>仿创造飞行</b>＝直升机（慢、稳、灵活，万金油移动层）；</li>
 *   <li><b>本档＝喷气式</b>：推力**由外部法术给**（飞行聚晶每 tick 把速度覆盖成视线方向 ×
 *       0.5~1.0 格/tick），我们**不改威力**，只管三件事：**瞄准 → 续放 → 到地方收手**。</li>
 * </ul>
 *
 * <h2>三种任务（G-3 加的第三档就是"接敌机动"）</h2>
 * <pre>
 *   ARRIVE  ／goety_fly      —— 飞坐标：巡航 → 下降 → 落地收手（一次性，必然终止）
 *   FOLLOW  ／goety_follow   —— 跟实体：远了直追、近了绕圈伴飞
 *   COMBAT  ／goety_combat   —— 打盘旋：**每 tick 取上游算好的盘旋点**（见下），绕着打
 * </pre>
 *
 * <h2>为什么战斗档能直接复用上游的"接敌机动"</h2>
 * 上游把接敌机动抽成了可插的两层：{@code CombatOrbit}（圆：半径/旋向/相位随机）+
 * {@code CombatManeuver}（五种打法：环绕/蛇形/高悠悠/脱离再进/8 字）。消费它们的是**飞行后端**——
 * 现在只有鞘翅空战与扫帚两个。而 {@code MaidBroomDrive.combatPoint(maid, target)} 是**公开静态**、
 * 一次调用就把半径/旋向/相位/高度基准/最近距离/回家夹取全折好 ⇒ 我们**只调它、不改它**，
 * 于是 Goety 推进等于零改动地成了**第三个后端**，五种机动照跑。
 *
 * <h2>实测教训（都写在注释里，别再踩）</h2>
 * <ol>
 *   <li><b>G-1 恒定速度是真的</b>：每 tick 放一发，速度恒为 0.500 格/tick、高度零漂移、
 *       {@code NoGravity} 始终 false；停手立刻 (0,-0.078,0.455)（重力 + 0.91 阻力）
 *       ⇒ 必须每 tick 续放，且**落地前那一下要提前收手**。</li>
 *   <li><b>G-1 落体伤害不用管</b>：{@code FlyingSpell.SpellResult} 里有 {@code fallDistance = 0}，
 *       施法期间坠落距离永远归零 ⇒ 可以"硬着陆"（不像仿创造飞行那样必须写软着陆）。</li>
 *   <li><b>G-2 方向必须我们说了算</b>：飞行聚晶取的是 {@code caster.getLookAngle()}，而
 *       **有主人的女仆**会被 TLM 的跟随/闲逛持续下行走目标 ⇒ 原版 {@code MoveControl} 每 tick
 *       把她的 yaw 掰向那个目标 ⇒ 她绕着主人画圈（实机撞到过）。所以：①新增
 *       {@code MaidGoetyMoveSuppressMixin} 在推进期间从源头掐掉走路目标；②放完法术后**再按我们
 *       算出来的瞄准方向重写一遍速度**（大小仍取自聚晶）。</li>
 *   <li><b>G-2 推力器会卡在坑里</b>：贴地/撞墙时水平推力全被碰撞吃掉，判据命中就短暂抬头拔高。</li>
 * </ol>
 */
public final class MaidGoetyFlight {

    /** 发射所用法术键（{@link MaidGoetyCompat#cast} 的口径）。 */
    private static final String SPELL_FLYING = "flying";
    /** 加力所用法术键（发射聚晶：一次性冲量）。 */
    private static final String SPELL_LAUNCH = "launch";

    /** 巡航时的转向速度（度/tick）——比仿创造飞行快（那边 12°，因为它是"悬停微调"）。 */
    private static final float YAW_STEP = 18.0F;
    /** 俯仰转向速度（度/tick）。 */
    private static final float PITCH_STEP = 12.0F;
    /** 巡航俯仰限幅（度）。 */
    private static final float PITCH_LIMIT = 55.0F;

    /** 水平到位半径（格）：进了这个圈就转下降。 */
    private static final double ARRIVE_H = 2.2;
    /** 跟随时"够近"的距离（格）。 */
    private static final double FOLLOW_HOLD = 4.0;
    /** 下落阶段瞄的"下方偏移"（格）：6 格前 + 3 格下 ≈ -27° → 约 4.6 格/秒。 */
    private static final double DESCEND_AHEAD = 6.0;
    private static final double DESCEND_DOWN = 3.0;
    /** 离地这么近就收手（格）。 */
    private static final double GROUND_STOP = 1.2;
    /** 一次性任务（坐标/跟随）的最长时长（tick）——30 秒；按 10 格/秒算够飞 300 格。 */
    private static final long MAX_TICKS = 600L;
    /** 战斗档的最长时长（tick）——5 分钟（一场遭遇的上限，正常由"丢目标"结束）。 */
    private static final long MAX_TICKS_COMBAT = 6000L;

    /** 加力的自管冷却（tick）：Goety 的发射聚晶是 20 tick，但**怪物施法路径不进冷却**，
     *  所以冷却必须我们自己管，否则会变成无限连发。 */
    private static final long BOOST_COOLDOWN = 20L;
    /** 加力的抬升角（度）：脱离时略微上扬，顺带脱离近战怪的攻击面。 */
    private static final double BOOST_PITCH_UP = 20.0;
    /**
     * 【G-3 实测发现】加力窗口（tick）：点着加力之后，**这一段时间内不再续放飞行聚晶**——
     * 否则冲量会在下一 tick 被"恒速巡航"覆盖掉（实测：放完瞬间速度 1.50，0.4 秒后已回到 0.38）。
     * 真实加力就是"点着推一段、然后滑行"，这里照抄那个手感：1.5 秒的窗口 ≈ 多飞 15~20 格。
     * 窗口内保留 {@code fallDistance = 0}（与飞行聚晶自己的做法一致）与瞄准转向，
     * 窗口结束自动回到正常巡航。
     */
    private static final long BOOST_WINDOW = 30L;

    private enum Mode { ARRIVE, FOLLOW, COMBAT }

    private enum Phase { CRUISE, DESCEND }

    /**
     * 一个"持续推进"任务。
     *
     * @param auto 这个任务是**自动层自己下发**的（true）还是**玩家命令**下发的（false）。
     *             自动层只允许撤销/覆盖自己下发的任务——实测发现过：少了这个标记，
     *             自动层在"她暂时没有主人"时会把玩家刚下的 {@code goety_fly} 一并掐掉。
     */
    private record Task(Mode mode, UUID targetId, Vec3 point, ItemStack staff, Phase phase, long start,
                        boolean auto) {
    }

    private static final Map<UUID, Task> TASKS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_BOOST = new LinkedHashMap<>();
    /** 加力窗口：她在这个 tick 之前不续放巡航推力（让冲量自己滑）。 */
    private static final Map<UUID, Long> BOOST_UNTIL = new LinkedHashMap<>();
    private static boolean hooked;
    /** 临时诊断计数（每 20 tick 打一行）。 */
    private static int DBG;

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

    /** 当前任务档位名（日志/命令用）。 */
    public static String describe(EntityMaid maid) {
        Task t = maid == null ? null : TASKS.get(maid.getUUID());
        return t == null ? "无" : t.mode().name();
    }

    /**
     * 【保真门禁】她身上必须**真的带着这个聚晶**（法杖当前槽 / 聚晶包里 / 任意容器里都算）。
     * 返回 null 表示放行，否则返回拒绝原因。
     */
    public static String requireFocus(EntityMaid maid, String focusId, String what) {
        if (!MaidGoetyCompat.available()) {
            return "服务器没装 Goety（或版本反射路径不符）";
        }
        if (!MaidGoetyCompat.hasFocus(maid, focusId)) {
            return "她身上没有" + what + "（" + focusId + "）——把聚晶放进法杖插到她身上/背包/饰品栏，或放进聚晶包";
        }
        return null;
    }

    /** 飞到某个坐标（一次性：巡航 → 下降 → 收手）。返回 null 表示已开始，否则是拒绝原因。 */
    public static String flyTo(EntityMaid maid, Vec3 point) {
        return flyTo(maid, point, false);
    }

    /** 同上，但可标记为"自动层下发"（自动层只撤销自己下发的任务）。 */
    public static String flyTo(EntityMaid maid, Vec3 point, boolean auto) {
        String bad = requireFocus(maid, MaidGoetyCompat.FOCUS_FLYING, "飞行聚晶");
        if (bad != null) {
            return bad;
        }
        TASKS.put(maid.getUUID(), new Task(Mode.ARRIVE, null, point, pickStaff(maid), Phase.CRUISE,
                maid.level().getGameTime(), auto));
        return null;
    }

    /** 当前任务是不是**玩家命令**下发的（自动层必须让路）。 */
    public static boolean isManual(EntityMaid maid) {
        Task t = maid == null ? null : TASKS.get(maid.getUUID());
        return t != null && !t.auto();
    }

    /** 当前任务是不是自动层下发的。 */
    public static boolean isAutoTask(EntityMaid maid) {
        Task t = maid == null ? null : TASKS.get(maid.getUUID());
        return t != null && t.auto();
    }

    /** 跟着某个实体飞（远了直追、近了绕圈伴飞）。返回 null 表示已开始，否则是拒绝原因。 */
    public static String follow(EntityMaid maid, Entity target) {
        String bad = requireFocus(maid, MaidGoetyCompat.FOCUS_FLYING, "飞行聚晶");
        if (bad != null) {
            return bad;
        }
        TASKS.put(maid.getUUID(), new Task(Mode.FOLLOW, target.getUUID(), null, pickStaff(maid), Phase.CRUISE,
                maid.level().getGameTime(), false));
        return null;
    }

    /**
     * 【G-3 战斗档】绕着目标打盘旋：每 tick 取 {@code MaidBroomDrive.combatPoint}（上游那套
     * 接敌机动的几何层），朝它推。丢目标/超时都会**转成下降**（而不是直接收手——高空撒手会摔）。
     */
    public static String combat(EntityMaid maid, LivingEntity target) {
        return combat(maid, target, false);
    }

    /** 同上，但可标记为"自动层下发"。 */
    public static String combat(EntityMaid maid, LivingEntity target, boolean auto) {
        String bad = requireFocus(maid, MaidGoetyCompat.FOCUS_FLYING, "飞行聚晶");
        if (bad != null) {
            return bad;
        }
        if (target == null) {
            return "没有目标";
        }
        TASKS.put(maid.getUUID(), new Task(Mode.COMBAT, target.getUUID(), null, pickStaff(maid), Phase.CRUISE,
                maid.level().getGameTime(), auto));
        return null;
    }

    /**
     * 【G-3 加力档】放一发**发射聚晶**（一次性冲量：基准 1.5、风之魔杖 2.5 格/tick ≈ 50 格/秒）。
     * 方向：给了 {@code awayFrom} 就**朝背离它**的方向并略抬 20°（脱离用）；否则用她当前视线。
     * 冷却由我们自己管（怪物施法路径不进 Goety 冷却表）。返回 null 表示已放，否则是拒绝原因。
     */
    public static String boost(EntityMaid maid, Entity awayFrom) {
        String bad = requireFocus(maid, MaidGoetyCompat.FOCUS_LAUNCH, "发射聚晶");
        if (bad != null) {
            return bad;
        }
        long now = maid.level().getGameTime();
        Long last = LAST_BOOST.get(maid.getUUID());
        if (last != null && now - last < BOOST_COOLDOWN) {
            return "加力还在冷却（还剩 " + (BOOST_COOLDOWN - (now - last)) + " tick）";
        }
        if (awayFrom != null) {
            Vec3 flat = maid.position().subtract(awayFrom.position()).multiply(1, 0, 1);
            if (flat.lengthSqr() < 1.0E-4) {
                flat = new Vec3(1, 0, 0);
            }
            flat = flat.normalize();
            double p = Math.toRadians(BOOST_PITCH_UP);
            Vec3 look = new Vec3(flat.x * Math.cos(p), Math.sin(p), flat.z * Math.cos(p)).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) (-Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, look.y)))));
            maid.setYRot(yaw);
            maid.setXRot(pitch);
            maid.yRotO = yaw;
            maid.xRotO = pitch;
        }
        String err = MaidGoetyCompat.castDiag(maid, SPELL_LAUNCH, pickStaff(maid));
        if (err != null) {
            return "施放失败：" + err;
        }
        LAST_BOOST.put(maid.getUUID(), now);
        BOOST_UNTIL.put(maid.getUUID(), now + BOOST_WINDOW);   // 让冲量自己滑一段，别被巡航覆盖
        if (LAST_BOOST.size() > 64) {
            LAST_BOOST.entrySet().removeIf(e -> now - e.getValue() > 1200L);   // 一分钟没用的清掉
        }
        PromaidLog.log("Goety加力", maid.getName().getString() + " 加力（发射聚晶）视线 pitch="
                + String.format(java.util.Locale.ROOT, "%.1f", maid.getXRot())
                + (awayFrom == null ? "" : " 背离 " + awayFrom.getName().getString()));
        return null;
    }

    /** 收手（交还控制权，不再放法术）。 */
    public static void stop(EntityMaid maid, String why) {
        if (maid != null && TASKS.remove(maid.getUUID()) != null) {   // 玩家命令与自动层都可以收手
            PromaidLog.log("Goety推进", maid.getName().getString() + " 收手（" + why + "）当前位置 "
                    + fmt(maid.position()));
        }
    }

    private static ItemStack pickStaff(EntityMaid maid) {
        for (ItemStack s : MaidGoetyCompat.staffs(maid).values()) {
            return s;   // 同系法杖（风之魔杖）→ 威力翻倍；没有就空栈（基准 0.5 格/tick = 10 格/秒）
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
        long limit = task.mode() == Mode.COMBAT ? MAX_TICKS_COMBAT : MAX_TICKS;
        if (now - task.start() > limit) {
            stopIfGroundedOrTooLong(maid, task, "超时");
            return;
        }

        // 目标点：跟随/战斗档每 tick 重取（目标会动），坐标档固定
        Vec3 goal = task.point();
        if (task.mode() == Mode.COMBAT) {
            Entity t = lookup(maid, task.targetId());
            if (!(t instanceof LivingEntity living) || !living.isAlive()) {
                // 丢目标：不要在高空直接撒手（会摔），转成"原地下降"
                TASKS.put(maid.getUUID(), new Task(Mode.ARRIVE, null, maid.position(), task.staff(),
                        Phase.DESCEND, task.start(), task.auto()));
                PromaidLog.log("Goety推进", maid.getName().getString() + " 丢目标 → 原地下降收手");
                return;
            }
            // ★ 上游的接敌机动几何层：半径/旋向/相位/高度基准/最近距离/回家夹取，全在这一句里
            goal = com.maidsmart.combat.MaidBroomDrive.combatPoint(maid, living);
        } else if (task.mode() == Mode.FOLLOW) {
            Entity t = lookup(maid, task.targetId());
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
            if (task.mode() == Mode.COMBAT) {
                aim = goal;   // 盘旋点一直在动，不需要"到位"判定——绕着打就是她的宿命
            } else {
                // 【G-1 修】到达判定必须**水平与垂直都到**：旧写法只对跟随档看 dv，于是"目标在
                // 头顶 20 格、她水平已进 2.2 格圈"会被判成到位 → 直接下降 → 贴地就收手。
                boolean arrived = dh < (task.mode() == Mode.FOLLOW ? FOLLOW_HOLD : ARRIVE_H)
                        && Math.abs(dv) < (task.mode() == Mode.FOLLOW ? 2.5 : 2.0);
                if (arrived) {
                    if (task.mode() == Mode.FOLLOW) {
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
            }
        } else {
            aim = descendAim(maid, goal);
            if (maid.onGround() || nearGround(maid)) {
                stop(maid, "落地");
                return;
            }
            // 虚空保护：掉到世界底部以下就放手（那里再松重力也摔不死，反之会一直往下降）
            if (maid.getY() < maid.level().getMinBuildHeight() + 1) {
                stop(maid, "到世界底部了");
                return;
            }
        }

        // 【G-2 修 2：卡在坑里抠出来】推力飞行器落在 1 格深的坑/贴墙时，水平推力全被碰撞吃掉
        // （位置一动不动、速度只剩重力、法术却在正常施放），于是永远出不来。判据命中就短暂抬头。
        if (phase == Phase.CRUISE && (maid.onGround() || maid.horizontalCollision)) {
            Vec3 flat = new Vec3(goal.x - maid.getX(), 0, goal.z - maid.getZ());
            if (flat.length() < 1.0E-4) {
                flat = new Vec3(maid.getLookAngle().x, 0, maid.getLookAngle().z);
            }
            aim = maid.position().add(flat.normalize().scale(3.0)).add(0, 4.0, 0);
        }

        steer(maid, aim, phase);
        // 她自己写的走路目标与导航会跟"每 tick 覆盖速度"抢方向盘——从源头掐掉
        // （同口径的 mixin 还有 MaidGoetyMoveSuppressMixin，那道更彻底）
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        try {
            maid.getNavigation().stop();
        } catch (Throwable ignored) {
        }

        Long boostUntil = BOOST_UNTIL.get(maid.getUUID());
        if (boostUntil != null && now < boostUntil) {
            // 加力窗口内：只保留瞄准与"清零坠落距离"，**不续放巡航推力**（否则冲量下一 tick 就没了）
            maid.fallDistance = 0.0F;
            if (DBG % 20 == 0) {
                PromaidLog.log("Goety推进", maid.getName().getString() + " 加力滑行中 位置 "
                        + fmt(maid.position()) + " 速度 " + fmt(maid.getDeltaMovement())
                        + " 剩余 " + (boostUntil - now) + " tick");
            }
            DBG++;
            TASKS.put(maid.getUUID(), new Task(task.mode(), task.targetId(), task.point(), task.staff(),
                    phase, task.start(), task.auto()));
            return;
        }
        if (boostUntil != null) {
            BOOST_UNTIL.remove(maid.getUUID());
        }

        String err = MaidGoetyCompat.castDiag(maid, SPELL_FLYING, task.staff());
        if (err == null) {
            // 【G-2 修 1：方向由我们定，威力仍由聚晶给】见类注释第 3 条。
            Vec3 dir = aim.subtract(maid.getEyePosition());
            if (dir.lengthSqr() > 1.0E-6) {
                double d0 = MaidGoetyCompat.thrustPower(maid, task.staff());
                maid.setDeltaMovement(dir.normalize().scale(d0));
                maid.hasImpulse = true;
                maid.fallDistance = 0.0F;
            }
        } else {
            PromaidLog.log("Goety推进", maid.getName().getString() + " 施放失败：" + err);
        }
        if (DBG % 20 == 0 || maid.onGround() || maid.horizontalCollision) {
            PromaidLog.log("Goety推进", maid.getName().getString()
                    + " t=" + task.mode().name() + "/" + phase.name()
                    + " 位置 " + fmt(maid.position()) + " 速度 " + fmt(maid.getDeltaMovement())
                    + " 目标 " + fmt(goal) + " 着地=" + maid.onGround());
        }
        DBG++;
        TASKS.put(maid.getUUID(), new Task(task.mode(), task.targetId(), task.point(), task.staff(),
                phase, task.start(), task.auto()));
    }

    /** 超时处理：已经贴地就直接收手，还在空中就转成下降（别高空撒手）。 */
    private static void stopIfGroundedOrTooLong(EntityMaid maid, Task task, String why) {
        if (maid.onGround() || nearGround(maid)) {
            stop(maid, why);
            return;
        }
        TASKS.put(maid.getUUID(), new Task(Mode.ARRIVE, null, maid.position(), task.staff(),
                Phase.DESCEND, maid.level().getGameTime(), task.auto()));
        PromaidLog.log("Goety推进", maid.getName().getString() + " " + why + " → 原地下降收手");
    }

    private static Entity lookup(EntityMaid maid, UUID id) {
        if (id == null) {
            return null;
        }
        return maid.level() instanceof net.minecraft.server.level.ServerLevel sl ? sl.getEntity(id) : null;
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
     * 转向：限速把她的 yaw/pitch 转过去。
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
