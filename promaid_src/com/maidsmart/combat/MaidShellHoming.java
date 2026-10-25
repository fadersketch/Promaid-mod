package com.maidsmart.combat;

import java.util.Iterator;
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
 * 固定翼火炮末制导：把女仆从固定翼打出去的卓越前线炮弹逐拍掰向她的目标。
 *
 * <p>普通炮弹是纯抛物线（{@code ProjectileEntity.tick} 只加重力），固定翼高速移动时开火解算
 * 很快过期。{@code tickAttack} 每打一发就 {@link #note 记一笔}；本类每服务端 tick 扫一遍
 * 在飞的卓越前线炮弹，按 {@code owner} 认出她的弹，限速转向目标（只改方向、不改速度）。
 * 普通弹**不设 3 秒时限**：只要弹还在飞、目标还活着就持续纠向（远距离也追得上）；
 * 制导弹只锁前 {@link #MISSILE_LOCK_TICKS} 拍。
 *
 * <p>只接管固定翼（{@link MaidMountCompat#isAircraft}）；坦克/直升机/地面载具与炸弹不接管。
 * 目标死亡/跨维度立即放手，表里只存 UUID，不持引用。
 */
public final class MaidShellHoming {

    private MaidShellHoming() {
    }

    /** 一条"该被纠向目标"的炮弹记录。 */
    private static final class Track {
        final UUID target;
        final UUID maid;
        final int maxTicks;
        int ticks;
        /** 【实测一千〇六十一】最后一次观测到的弹体位置与速度（弹体消失后据此写"落点"）。 */
        double lx;
        double ly;
        double lz;
        double lastSpeed;

        Track(UUID target, UUID maid, int maxTicks) {
            this.target = target;
            this.maid = maid;
            this.maxTicks = maxTicks;
        }
    }

    /** 本拍刚开火、弹体还没出现的那些：目标 + 炮口位置 + 出生/过期拍。 */
    private static final class Pending {
        final UUID target;
        /** 这一发从哪个炮口打出来（按最近炮口匹配，见 {@link #claim}）；null = 引擎原路。 */
        final Vec3 muzzle;
        /** 登记时的世界拍：SWB 的炮弹是延迟生成的，认领时用得到这个差（见 {@link #claim}）。 */
        final long bornAt;
        final long expireAt;

        Pending(UUID target, Vec3 muzzle, long bornAt, long expireAt) {
            this.target = target;
            this.muzzle = muzzle;
            this.bornAt = bornAt;
            this.expireAt = expireAt;
        }
    }

    /**
     * 本拍刚开火、弹体还没出现的那些（引擎原路）。
     *
     * <p>{@code vehicleShoot} 是延迟执行的，开火这一拍弹体还没生成，所以这里只记"她刚朝谁
     * 开火"，真正的弹体由 {@link #tick} 按 owner 认领。
     */
    public static void note(EntityMaid maid, Entity mount, LivingEntity target) {
        noteInternal(maid, mount, target, null, PENDING_TTL_TICKS);
    }

    /**
     * 【实测一千〇六十三】直瞄开火**当场**登记炮口位置：多目标切换时，旧版"一只女仆一个
     * pending 槽"会被下一拍的新目标覆盖，炮弹还没被扫描到就认成了另一只敌人的目标——
     * 实机表现就是"炮弹先拐向另一边、一头扎进地里"。按炮口位置逐发匹配后，每一发永远
     * 归它开火时瞄准的那一只。
     */
    public static void noteSpawned(EntityMaid maid, Entity mount, LivingEntity target, Vec3 muzzle) {
        noteInternal(maid, mount, target, muzzle, PENDING_TTL_TICKS);
    }

    /**
     * 待认领记录能活多久（拍）。SWB 的 {@code vehicleShoot} 是延迟执行的：实机日志里
     * 「发射」到炮弹出现在实体表相隔约 10～11 拍。旧值 2～3 拍会让绝大多数记录在弹体
     * 生成前就过期（实测 177 发只认领到 9 发），炮弹因此退化成无制导直飞。
     */
    private static final long PENDING_TTL_TICKS = 30L;

    /** 认领时"炮口离炮弹生成点"的容差（格）。延迟生成时弹体仍出现在登记过的旧炮口附近。 */
    private static final double MUZZLE_MATCH = 24.0;

    private static void noteInternal(EntityMaid maid, Entity mount, LivingEntity target,
                                     Vec3 muzzle, long ttlTicks) {
        try {
            if (maid == null || mount == null || target == null) {
                return;
            }
            // 只给固定翼；坦克/直升机/地面载具不接管（保持既有行为）。
            if (!MaidMountCompat.isAircraft(mount)) {
                return;
            }
            if (PENDING.size() > 256) {
                PENDING.clear();
            }
            long now = mount.m_9236_().m_46467_();
            java.util.List<Pending> list = PENDING.computeIfAbsent(
                    maid.m_20148_(), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            // 每车一 tick 最多几门炮；30 拍寿命内最多约十几条，留 16 条足够，防止表无限涨。
            list.removeIf(p -> now > p.expireAt);
            while (list.size() >= 16) {
                list.remove(0);
            }
            list.add(new Pending(target.m_20148_(), muzzle, now, now + ttlTicks));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /** 待认领表（女仆 UUID → 本拍/上两拍打出的炮口记录）。 */
    private static final Map<UUID, java.util.List<Pending>> PENDING = new ConcurrentHashMap<>();

    /** 在飞的炮弹（弹体 UUID → 记录）。 */
    private static final Map<UUID, Track> TRACKED = new ConcurrentHashMap<>();

    /** 普通弹的纠向兜底上限（≈30 秒）。这不是"追踪 3 秒"的旧时限——384 格内任何弹的
     *  飞行时间都远小于它，实际放手条件只有命中/落地/被删/目标死亡；留这个数只为防止
     *  极端情况下绕圈弹被永久接管。 */
    private static final int MAX_TICKS_PER_SHELL = 600;

    /** 制导弹只锁 0.2 秒，之后交还它自己的末端制导。 */
    private static final int MISSILE_LOCK_TICKS = 4;

    /** 每拍最多转多少度。 */
    private static final double TURN_DEG_PER_TICK = 25.0;

    private static final long HOMING_LOG_MS = 5000L;
    private static final Map<UUID, Long> HOMING_AT = new ConcurrentHashMap<>();

    private static final String SWB_PKG = "com.atsuishio.superbwarfare.";

    /** 服务端每 tick 一次（由 {@code ProMaidExtension} 调）。 */
    public static void tick(MinecraftServer server) {
        try {
            if (server == null) {
                return;
            }
            if (!PENDING.isEmpty()) {
                claim(server);
            }
            if (TRACKED.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<UUID, Track>> it = TRACKED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Track> e = it.next();
                if (!steerOne(server, e.getKey(), e.getValue())) {
                    logShellEnd(server, e.getKey(), e.getValue());
                    it.remove();
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /** 把待认领表里的记录与真实弹体对上。 */
    private static void claim(MinecraftServer server) {
        try {
            // 她不在了 → 作废她的整张待认领表
            PENDING.entrySet().removeIf(e -> findEntity(server, e.getKey()) == null);
            if (PENDING.isEmpty()) {
                return;
            }
            for (ServerLevel lvl : server.m_129785_()) {
                long now = lvl.m_46467_();
                for (Entity en : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
                    if (!(en instanceof Projectile proj)) {
                        continue;
                    }
                    String cn = en.getClass().getName();
                    if (!cn.startsWith(SWB_PKG)) {
                        continue;
                    }
                    // 炸弹走自己的投掷物理，不接管。
                    if (cn.toLowerCase(java.util.Locale.ROOT).contains("bomb")) {
                        continue;
                    }
                    boolean missile = cn.contains("Missile");
                    UUID key = en.m_20148_();
                    if (TRACKED.containsKey(key)) {
                        continue;
                    }
                    Entity owner = proj.m_19749_();
                    if (owner == null) {
                        continue;
                    }
                    java.util.List<Pending> list = PENDING.get(owner.m_20148_());
                    if (list == null || list.isEmpty()) {
                        continue; // 不是"我们刚开火的那只女仆"打的
                    }
                    // 【实测一千〇六十三】按炮口位置认领：同一 tick 多门炮可能打不同的敌人，
                    // 旧版"一只女仆一个 pending 槽"会被后一门炮覆盖，炮弹认成别人的目标。
                    Pending p = takePending(list, en.m_20182_(), now);
                    if (p == null) {
                        continue;
                    }
                    UUID target = p.target;
                    Track t = new Track(target, owner.m_20148_(),
                            missile ? MISSILE_LOCK_TICKS : MAX_TICKS_PER_SHELL);
                    t.lx = en.m_20185_();
                    t.ly = en.m_20186_();
                    t.lz = en.m_20189_();
                    t.lastSpeed = proj.m_20184_().m_82553_();
                    TRACKED.put(key, t);
                    LivingEntity tl = findLiving(lvl, target);
                    double td = tl == null ? -1.0
                            : en.m_20182_().m_82554_(tl.m_20191_().m_82399_());
                    logShellNow("认领 #" + shortId(key) + (missile ? " 导弹" : " 炮弹")
                            + " 起点=" + fmtVec(en.m_20182_())
                            + " 延迟=" + Math.max(0L, now - p.bornAt) + "拍"
                            + (p.muzzle == null ? ""
                                : " 炮口距=" + String.format(java.util.Locale.ROOT, "%.1f",
                                        p.muzzle.m_82554_(en.m_20182_())) + "格")
                            + " 初速=" + String.format(java.util.Locale.ROOT, "%.2f", t.lastSpeed)
                            + " 目标=" + describe(lvl, target)
                            + " 距离=" + (td < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.1f", td) + "格"));
                    log(owner.m_20148_(), (missile ? "接管一枚导弹（锁定 0.2 秒）→ " : "接管一发炮弹 → ")
                            + describe(lvl, target));
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /**
     * 给这一发弹体挑一条待认领记录：**优先取炮口离弹体最近的那条**（同 tick 多门炮各打各的）；
     * 没有炮口匹配（引擎原路 / 弹体位置偏太多）才退回最早那条。取走即删，避免同一记录认领多发。
     */
    private static Pending takePending(java.util.List<Pending> list, Vec3 at, long now) {
        Pending best = null;
        double bestD = MUZZLE_MATCH * MUZZLE_MATCH;
        Pending oldest = null;
        for (Pending p : list) {
            if (p == null || now > p.expireAt) {
                continue;
            }
            if (oldest == null || p.bornAt < oldest.bornAt) {
                oldest = p;
            }
            if (p.muzzle == null) {
                continue;
            }
            double d = p.muzzle.m_82554_(at);
            if (d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        Pending chosen = best != null ? best : oldest;
        if (chosen != null) {
            list.remove(chosen);
        }
        return chosen;
    }

    /** 把一发炮弹的速度方向朝目标掰。 */
    private static boolean steerOne(MinecraftServer server, UUID shellId, Track t) {
        try {
            if (t == null) {
                return false;
            }
            if (++t.ticks > t.maxTicks) {
                return false;
            }
            Entity shell = findEntity(server, shellId);
            if (shell == null || !shell.m_6084_() || shell.m_213877_()) {
                return false;
            }
            if (!(shell instanceof Projectile)) {
                return false;
            }
            if (!shell.getClass().getName().startsWith(SWB_PKG)) {
                return false;
            }
            LivingEntity target = findLiving(shell.m_9236_(), t.target);
            if (target == null) {
                return false;
            }
            Vec3 dm = shell.m_20184_();
            double speed = dm.m_82553_();
            if (speed < 0.05) {
                return true;
            }
            Vec3 from = shell.m_20182_();
            t.lx = from.f_82479_;
            t.ly = from.f_82480_;
            t.lz = from.f_82481_;
            t.lastSpeed = speed;
            Vec3 to = target.m_20191_().m_82399_();
            Vec3 want = to.m_82546_(from);
            double dist = want.m_82553_();
            if (dist < 0.5) {
                return true;
            }
            // 【R1074】提前量必须加在**目标位置**上再取方向：目标速度 × 剩余飞行时间。
            // 旧版先归一化再相加（单位向量 + 位移），提前量被放大成"距离"倍，目标一带
            // 下落速度弹就近乎垂直下扎，全部砸进目标前方地面。上限 40 → 120 拍同 Neo。
            double remain = dist / Math.max(speed, 0.1);
            Vec3 tv = target.m_20184_();
            Vec3 aim = to.m_82549_(tv.m_82490_(Math.min(remain, 120.0))).m_82546_(from);
            want = aim.m_82556_() > 1.0E-6 ? aim.m_82541_() : want.m_82541_();
            Vec3 cur = dm.m_82541_();
            double deg = Math.toDegrees(Math.acos(clamp(cur.m_82526_(want), -1.0, 1.0)));
            if (Double.isNaN(deg)) {
                return true;
            }
            Vec3 newDir;
            // 【实测一千〇六十二】固定翼首发直接掰正：远距离实测炮弹认领时仍比目标方向偏
            // 40～60°（炮管固定在车体上，实际初速不是解算方向）。25°/拍要 2～3 拍才转回来，
            // 180 格外这 2～3 拍已经偏出 50～100 格，落点就进地形了。前两拍允许一次直接
            // 对准（只改方向、不改速度），其余情况仍保持 25°/拍的平滑转向。
            double maxTurn = t.ticks <= 2 ? 180.0 : TURN_DEG_PER_TICK;
            boolean snap = t.ticks <= 2 && deg > TURN_DEG_PER_TICK;
            if (deg <= maxTurn) {
                newDir = want;
            } else {
                double f = maxTurn / deg;
                Vec3 blended = cur.m_82549_(want.m_82546_(cur).m_82490_(f));
                if (blended.m_82556_() < 1.0E-8) {
                    return true;
                }
                newDir = blended.m_82541_();
            }
            shell.m_20256_(newDir.m_82490_(speed));
            shell.m_146922_((float) Math.toDegrees(Math.atan2(newDir.f_82479_, newDir.f_82481_)));
            shell.m_146926_((float) Math.toDegrees(Math.atan2(newDir.f_82480_, newDir.m_165924_())));
            log(t.maid, "纠向 " + String.valueOf(target.m_6095_()).replace("entity.minecraft.", "")
                    + " 偏角=" + (long) deg + "° 距离=" + (long) dist + "格");
            logShell(shellId, "纠向 #" + shortId(shellId)
                    + " 位置=" + fmtVec(from)
                    + " 目标=" + target.m_5446_().getString()
                    + " 距=" + (long) dist + "格 偏角=" + (long) deg + "°"
                    + " 速度=" + String.format(java.util.Locale.ROOT, "%.2f", speed)
                    + (snap ? " 首发校准" : ""));
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
            return false;
        }
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        try {
            for (ServerLevel lvl : server.m_129785_()) {
                Entity e = lvl.m_8791_(id);
                if (e != null) {
                    return e;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return null;
    }

    private static LivingEntity findLiving(net.minecraft.world.level.Level level, UUID id) {
        try {
            if (level instanceof ServerLevel sl) {
                Entity e = sl.m_8791_(id);
                if (e instanceof LivingEntity le && le.m_6084_()) {
                    return le;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return null;
    }

    private static String describe(ServerLevel lvl, UUID id) {
        try {
            Entity e = lvl.m_8791_(id);
            if (e != null) {
                return String.valueOf(e.m_6095_()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return "?";
    }

    /** 【实测一千〇六十一】逐发弹道日志（1 秒/发）——日志搜「模组坐骑·弹道」。 */
    private static final Map<UUID, Long> SHELL_LOG_AT = new ConcurrentHashMap<>();

    private static void logShell(UUID shellId, String msg) {
        try {
            long now = System.currentTimeMillis();
            Long last = SHELL_LOG_AT.get(shellId);
            if (last != null && now - last < 1000L) {
                return;
            }
            SHELL_LOG_AT.put(shellId, now);
            if (SHELL_LOG_AT.size() > 512) {
                SHELL_LOG_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·弹道", msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    private static void logShellNow(String msg) {
        try {
            com.maidsmart.tool.PromaidLog.log("模组坐骑·弹道", msg);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /** 【实测一千〇六十一】这一发的终点：命中 / 落地 / 被删，都会从实体表消失。 */
    private static void logShellEnd(MinecraftServer server, UUID shellId, Track t) {
        try {
            Vec3 last = new Vec3(t.lx, t.ly, t.lz);
            String near = "";
            String tinfo = "";
            Entity te = findEntity(server, t.target);
            if (te != null) {
                tinfo = " 目标坐标=" + fmtVec(te.m_20182_())
                        + " 高差=" + String.format(java.util.Locale.ROOT, "%.1f", te.m_20186_() - last.f_82480_);
            }
            Entity e = findEntity(server, t.maid);
            EntityMaid m = e instanceof EntityMaid em ? em : null;
            if (m != null && m.m_20202_() != null) {
                double d = last.m_82554_(m.m_20202_().m_20182_());
                near = " 距载具=" + String.format(java.util.Locale.ROOT, "%.1f", d) + "格"
                        + (d < 4.0 ? "（疑似自击/近炸）" : "");
            }
            logShellNow("结束 #" + shortId(shellId)
                    + " 追踪=" + t.ticks + "拍"
                    + " 最后位置=" + fmtVec(last)
                    + " 速度=" + String.format(java.util.Locale.ROOT, "%.2f", t.lastSpeed)
                    + " 目标=" + describeTarget(server, t.target) + tinfo + near);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    private static String describeTarget(MinecraftServer server, UUID id) {
        try {
            Entity e = findEntity(server, id);
            if (e != null) {
                return String.valueOf(e.m_6095_()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return "?";
    }

    private static String fmtVec(Vec3 v) {
        return v == null ? "?" : String.format(java.util.Locale.ROOT,
                "(%.2f,%.2f,%.2f)", v.f_82479_, v.f_82480_, v.f_82481_);
    }

    private static String shortId(UUID id) {
        String s = id == null ? "?" : id.toString();
        return s.length() <= 8 ? s : s.substring(0, 8);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /**
     * 这架载具/这名女仆被瞬移后，丢掉她名下所有在飞与待认领的炮弹。
     * <p>旧位置的炮弹追不到新位置的目标（还可能落在未加载区块里不 tick、位置冻住），
     * 留着只会继续被纠向、继续写「结束」日志。瞬移后它们已经不可能命中，直接放手。
     */
    public static void forgetMaid(UUID maidId) {
        try {
            if (maidId == null) {
                return;
            }
            PENDING.remove(maidId);
            TRACKED.entrySet().removeIf(e -> maidId.equals(e.getValue().maid));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /** 世界卸载/服务器停止时清空。 */
    public static void clearAll() {
        try {
            TRACKED.clear();
            PENDING.clear();
            HOMING_AT.clear();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
