package com.maidsmart.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * v1.3.0(beta) 实测七百四十二·点3【强力后门：女仆打出去的炮弹直接追敌】。
 *
 * <h2>玩家原话</h2>
 * 「这边需要再加一个强力的后门让女仆发射出来的炮弹拥有直接指向敌人的效果。」
 *
 * <h2>为什么"把炮口对准"还不够</h2>
 * 卓越前线的炮弹是**纯抛物线、无制导**的（反编译 {@code ProjectileEntity.tick}：每拍只做
 * {@code setDeltaMovement(Δ + (0,-gravity,0))}，没有任何寻的项；只有 {@code MissileProjectile}
 * 走制导）。所以只要出现下面任何一条，炮弹就偏——
 * <ul>
 *   <li>车在动、目标在动，而提前量算的是"开火那一刻"的解，飞行途中两个都在变；</li>
 *   <li>炮管方向写在**车体坐标**里（AC-130H 三门炮、AH-6 机炮、飞艇炸弹）——炮管不能独立转，
 *       只能靠转机头，而飞行档每拍还在按跟随/盘旋改机头；</li>
 *   <li>我们还有"连续若干拍对不上就直接开火"那条保底档（见
 *       {@code MaidMountCompat.AIM_FORCE_TICKS}），开火那一刻炮口本来就歪着。</li>
 * </ul>
 *
 * <h2>本类做什么</h2>
 * {@code MaidMountCompat.tickAttack} 每打一发就 {@link #note 记一笔}"她刚朝谁开火"；本类每
 * 服务端 tick 扫一遍**在飞的卓越前线炮弹**，把"owner 是她、且还没接管过"的那些挑出来，
 * 然后逐拍把它们的速度方向**朝目标掰**（限速转向，保住初速大小）。于是不论炮口当时歪多少、
 * 车与目标怎么动，炮弹都会自己拐到敌人身上。
 *
 * <h2>怎么认出"是我们的炮弹"</h2>
 * <ol>
 *   <li>{@code instanceof Projectile}（否则连"从哪里来"都问不出来）；</li>
 *   <li>类名以 {@code com.atsuishio.superbwarfare.} 开头——**用类名字符串判**，不 import 那个
 *       模组的任何类型（它与本模组是并列的可选依赖，反编译/加载期都不能硬链，本工程通篇如此）；</li>
 *   <li>{@code getOwner()} 的 UUID == 我们记下的**那只女仆**——反编译 {@code GunItem.shootBullet}
 *       实证：弹体创建后第一件事就是 {@code setOwner(parameters.shooter)}，而女仆开火那条重载
 *       把 {@code shooter} 传的就是她本人。这一层同时挡掉了玩家的炮弹与别的模组的弹。</li>
 * </ol>
 *
 * <h2>为什么在服务端 tick 里扫，而不是挂到弹体上</h2>
 * 弹体的产生是 Kt 的对象池 + {@code EntityType.create}（反编译 {@code GunItem.shootBullet$lambda$5}），
 * 我们拿不到"刚创建"那个时刻；而且给一个第三方类挂逐拍回调要动 mixin，风险大于收益。
 * 服务端每 tick 扫一次实体表（走 {@code EntitySnapshot} 快照，见该类注释）代价可忽略，
 * 且**天然只作用于在飞的**（打中/落地的那一刻它就被 {@code discard()} 了，下一拍自然掉出）。
 *
 * <h2>边界（一条都不越）</h2>
 * <ul>
 *   <li>**只掰方向、不改速度大小**——否则等于给玩家变出一门"加速炮"；</li>
 *   <li>**不碰制导弹**（类名含 {@code Missile}）——它们本来就有自己的寻的，插手只会打架；</li>
 *   <li>每发最多纠 {@link #MAX_TICKS_PER_SHELL} 拍（≈3 秒），之后放手——免得绕圈弹被永久接管；</li>
 *   <li>目标死了/不在同维度 → 立刻放手，炮弹恢复自由飞行；</li>
 *   <li>只记 UUID，每拍从实体表反查——断线/退出世界不留引用。</li>
 * </ul>
 */
public final class MaidShellHoming {

    private MaidShellHoming() {
    }

    /** 一条"该被纠向目标"的炮弹记录。 */
    private static final class Track {
        final UUID target;
        final UUID maid;
        int ticks;
        boolean claimed;

        Track(UUID target, UUID maid) {
            this.target = target;
            this.maid = maid;
        }
    }

    /**
     * 本拍刚开火、弹体还没出现的那些——{@code tickAttack} 里"打出去了"之后立刻调。
     *
     * <p>为什么不直接把弹体传进来：{@code vehicleShoot} 是**延迟执行**的（反编译实证它先
     * {@code queueServerWork(SHOOT_DELAY_TIME)} 再发射），这一拍开火时弹体**还没生成**。
     * 所以这里只记"她刚朝谁开火"，真正的弹体由 {@link #tick} 在它出现后按 owner 认领。
     */
    public static void note(EntityMaid maid, Entity mount, LivingEntity target) {
        try {
            if (maid == null || mount == null || target == null) {
                return;
            }
            if (!MaidMountCompat.isVehicle(mount)) {
                return;
            }
            if (PENDING.size() > 256) {
                PENDING.clear();
            }
            // 按"女仆"记：一辆车上就她一名炮手，下一发覆盖上一发即可
            // （弹体在下一拍就出现，覆盖窗口只有那 1 拍）。
            PENDING.put(maid.getUUID(), target.getUUID());
        } catch (Throwable ignored) {
        }
    }

    /** 待认领表（女仆 UUID → 目标 UUID）。 */
    private static final Map<UUID, UUID> PENDING = new ConcurrentHashMap<>();

    /** 在飞的炮弹（弹体 UUID → 记录）。 */
    private static final Map<UUID, Track> TRACKED = new ConcurrentHashMap<>();

    /** 每发最多纠向的拍数（≈3 秒）。超了就放手（它可能是绕圈的火箭弹）。 */
    private static final int MAX_TICKS_PER_SHELL = 60;

    /** 每拍最多转多少度（限速转向：转弯半径像样、不会瞬间掉头）。 */
    private static final double TURN_DEG_PER_TICK = 25.0;

    /** 纠向的日志频限（毫秒）：日志搜「模组坐骑·制导」。 */
    private static final long HOMING_LOG_MS = 5000L;
    private static final Map<UUID, Long> HOMING_AT = new ConcurrentHashMap<>();

    /** 卓越前线的包名（用字符串判，不 import 那个模组的任何类）。 */
    private static final String SWB_PKG = "com.atsuishio.superbwarfare.";

    /**
     * 服务端每 tick 一次（由 {@code ProMaidExtension} 调）。
     */
    public static void tick(MinecraftServer server) {
        try {
            if (server == null) {
                return;
            }
            if (!PENDING.isEmpty()) {
                claim(server); // ① 给"刚开火"的那些找出弹体
            }
            if (TRACKED.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<UUID, Track>> it = TRACKED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Track> e = it.next();
                if (!steerOne(server, e.getKey(), e.getValue())) {
                    it.remove(); // ② 逐发纠向；返回 false = 该放手了
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把待认领表里的记录与真实弹体对上。
     *
     * <p>每个维度只遍历一次实体表（不是"每个 pending 一次"），所以哪怕同时有好几只女仆在开火,
     * 代价也是一趟 O(实体数) 的扫描。
     */
    private static void claim(MinecraftServer server) {
        try {
            // 收集"这一拍还活着、且在开火的"女仆 → 目标。
            Map<UUID, UUID> want = new ConcurrentHashMap<>(PENDING);
            for (Map.Entry<UUID, UUID> e : want.entrySet()) {
                if (findEntity(server, e.getKey()) == null) {
                    PENDING.remove(e.getKey()); // 她不在了 → 作废
                }
            }
            if (want.isEmpty()) {
                return;
            }
            for (ServerLevel lvl : server.getAllLevels()) {
                for (Entity en : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
                    if (!(en instanceof Projectile proj)) {
                        continue;
                    }
                    String cn = en.getClass().getName();
                    if (!cn.startsWith(SWB_PKG)) {
                        continue; // 只管卓越前线的弹体
                    }
                    if (cn.contains("Missile")) {
                        continue; // 制导弹有自己的寻的，别插手
                    }
                    UUID key = en.getUUID();
                    if (TRACKED.containsKey(key)) {
                        continue; // 已经认领过
                    }
                    Entity owner = proj.getOwner();
                    if (owner == null) {
                        continue;
                    }
                    UUID target = want.get(owner.getUUID());
                    if (target == null) {
                        continue; // 不是"我们刚开火的那只女仆"打的
                    }
                    Track t = new Track(target, owner.getUUID());
                    t.claimed = true;
                    TRACKED.put(key, t);
                    log(owner.getUUID(), "接管一发炮弹 → " + describe(server, lvl, target));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把一发炮弹的速度方向朝目标掰。
     *
     * @return false = 这发不用再管了（该从表里摘掉）
     */
    private static boolean steerOne(MinecraftServer server, UUID shellId, Track t) {
        try {
            if (t == null) {
                return false;
            }
            if (++t.ticks > MAX_TICKS_PER_SHELL) {
                return false; // 超时放手（它可能在绕圈，别永久接管）
            }
            Entity shell = findEntity(server, shellId);
            if (shell == null || !shell.isAlive() || shell.isRemoved()) {
                return false; // 打中/落地/被删 → 忘掉
            }
            if (!(shell instanceof Projectile)) {
                return false;
            }
            if (!shell.getClass().getName().startsWith(SWB_PKG)) {
                return false; // 换过类型 → 放手
            }
            LivingEntity target = findLiving(shell.level(), t.target);
            if (target == null) {
                return false; // 目标没了/不在同维度 → 放手
            }
            Vec3 dm = shell.getDeltaMovement();
            double speed = dm.length();
            if (speed < 0.05) {
                return true; // 刚发射那一拍速度还没给上 → 下拍再看
            }
            Vec3 from = shell.position();
            Vec3 to = target.getBoundingBox().getCenter();
            Vec3 want = to.subtract(from);
            double dist = want.length();
            if (dist < 0.5) {
                return true; // 已经贴上了，交给命中判定
            }
            want = want.normalize();
            // 一阶提前量：目标横向速度 × 剩余飞行时间（近处权重自然变小）
            double remain = dist / Math.max(speed, 0.1);
            Vec3 tv = target.getDeltaMovement();
            Vec3 aim = want.add(tv.scale(Math.min(remain, 40.0)));
            if (aim.lengthSqr() > 1.0E-6) {
                want = aim.normalize();
            }
            // 限速转向
            Vec3 cur = dm.normalize();
            double deg = Math.toDegrees(Math.acos(clamp(cur.dot(want), -1.0, 1.0)));
            if (Double.isNaN(deg)) {
                return true;
            }
            Vec3 newDir;
            if (deg <= TURN_DEG_PER_TICK) {
                newDir = want;
            } else {
                double f = TURN_DEG_PER_TICK / deg; // 0..1
                Vec3 blended = cur.add(want.subtract(cur).scale(f));
                if (blended.lengthSqr() < 1.0E-8) {
                    return true;
                }
                newDir = blended.normalize();
            }
            shell.setDeltaMovement(newDir.scale(speed)); // 只改方向，保住原速度
            // 朝向跟着走（渲染/部分判定读它；弹体自己每拍 updateHeading 也做同一件事）
            shell.setYRot((float) Math.toDegrees(Math.atan2(newDir.x, newDir.z)));
            shell.setXRot((float) Math.toDegrees(Math.atan2(newDir.y, newDir.horizontalDistance())));
            log(t.maid, "纠向 " + String.valueOf(target.getType()).replace("entity.minecraft.", "")
                    + " 偏角=" + (long) deg + "° 距离=" + (long) dist + "格");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 反查工具（一律按 UUID 反查，不持引用） ==================== */

    private static Entity findEntity(MinecraftServer server, UUID id) {
        try {
            Entity e = findIn(server, id);
            return e;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Entity findIn(MinecraftServer server, UUID id) {
        for (ServerLevel lvl : server.getAllLevels()) {
            Entity e = lvl.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** 在**指定**维度里找一个活着的 LivingEntity（跨维度的目标对炮弹没有意义）。 */
    private static LivingEntity findLiving(net.minecraft.world.level.Level level, UUID id) {
        try {
            if (level instanceof ServerLevel sl) {
                Entity e = sl.getEntity(id);
                if (e instanceof LivingEntity le && le.isAlive()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String describe(MinecraftServer server, ServerLevel lvl, UUID id) {
        try {
            Entity e = lvl.getEntity(id);
            if (e != null) {
                return String.valueOf(e.getType()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static void log(UUID maid, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = HOMING_AT.get(maid);
            if (last != null && now - last < HOMING_LOG_MS) {
                return;
            }
            HOMING_AT.put(maid, now);
            if (HOMING_AT.size() > 512) {
                HOMING_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·制导", msg);
        } catch (Throwable ignored) {
        }
    }

    /** 世界卸载/服务器停止时清空（防跨存档残留）。 */
    public static void clearAll() {
        try {
            TRACKED.clear();
            PENDING.clear();
            HOMING_AT.clear();
        } catch (Throwable ignored) {
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
