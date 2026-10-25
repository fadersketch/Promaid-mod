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
 * <h2>
 * 「这边需要再加一个强力的后门让女仆发射出来的炮弹拥有直接指向敌人的效果。」
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
 * </ul> <h2>本类做什么</h2> {@code MaidMountCompat.tickAttack} 每打一发就 {@link #note 记一笔}"她刚朝谁开火"；本类每 服务端 tick 扫一遍**在飞的卓越前线炮弹**，把"owner 是她、且还没接管过"的那些挑出来， 然后逐拍把它们的速度方向**朝目标掰**（限速转向，保住初速大小）。于是不论炮口当时歪多少、 车与目标怎么动，炮弹都会自己拐到敌人身上。 <h2>怎么认出"是我们的炮弹"</h2> <ol> <li>{@code instanceof Projectile}（否则连"从哪里来"都问不出来）；</li> <li>类名以 {@code com.atsuishio.superbwarfare.} 开头——**用类名字符串判**，不 import 那个 模组的任何类型（它与本模组是并列的可选依赖，反编译/加载期都不能硬链，本工程通篇如此）；</li> <li>{@code getOwner()} 的 UUID == 我们记下的**那只女仆**——反编译 {@code GunItem.shootBullet} 实证：弹体创建后第一件事就是 {@code setOwner(parameters.shooter)}，而女仆开火那条重载 把 {@code shooter} 传的就是她本人。这一层同时挡掉了玩家的炮弹与别的模组的弹。</li> </ol> <h2>为什么在服务端 tick 里扫，而不是挂到弹体上</h2> 弹体的产生是 Kt 的对象池 + {@code EntityType.create}（反编译 {@code GunItem.shootBullet$lambda$5}）， 我们拿不到"刚创建"那个时刻；而且给一个第三方类挂逐拍回调要动 mixin，风险大于收益。 服务端每 tick 扫一次实体表（走 {@code EntitySnapshot} 快照，见该类注释）代价可忽略， 且**天然只作用于在飞的**（打中/落地的那一刻它就被 {@code discard()} 了，下一拍自然掉出）。 <h2>边界（一条都不越）</h2> <ul> <li>**只掰方向、不改速度大小**——否则等于给玩家变出一门"加速炮"；</li> <li>**制导弹只锁 0.2 秒**（{@link #MISSILE_LOCK_TICKS}）——与投掷 TNT 同一条链路：开头 几拍把它掰向敌人，之后撒手、交还它自己的末端制导（旧版整条跳过 = 乱飞，见该常量注释）；</li> <li>固定翼每发最多纠 {@link #MAX_TICKS_PER_SHELL} 拍（≈30 秒；其它载具仍为 {@link #LEGACY_MAX_TICKS_PER_SHELL} 拍）——**不再是 3 秒**：远距离（300+ 格）的弹飞行时间本就超过 3 秒，旧时限会让它在半路撒手；现在只要弹还在飞、目标还活着就持续纠向，上限只作“绕圈弹永久接管”的兜底；</li> <li>目标死了/不在同维度 → 立刻放手，炮弹恢复自由飞行；</li> <li>只记 UUID，每拍从实体表反查——断线/退出世界不留引用。</li> </ul>
 */
public final class MaidShellHoming {

    private MaidShellHoming() {
    }

    /** 一条"该被纠向目标"的炮弹记录。 */
    private static final class Track {
        final UUID target;
        final UUID maid;
        /** 这一发最多纠向多少拍（普通弹 {@link #MAX_TICKS_PER_SHELL}；制导弹 {@link #MISSILE_LOCK_TICKS}）。 */
        final int maxTicks;
        /** 是不是固定翼打出来的（决定提前量上限，见 {@link #LEAD_CAP_AIRCRAFT} / {@link #LEAD_CAP_LEGACY}）。 */
        final boolean aircraft;
        int ticks;
        boolean claimed;
        /** 【实测一千〇六十一】最后一次观测到的弹体位置与速度（弹体消失后据此写"落点"）。 */
        double lx;
        double ly;
        double lz;
        double lastSpeed;

        Track(UUID target, UUID maid, int maxTicks, boolean aircraft) {
            this.target = target;
            this.maid = maid;
            this.maxTicks = maxTicks;
            this.aircraft = aircraft;
        }
    }

    /** 刚开火时的待认领记录：目标 + 是不是固定翼 + 炮口位置 + 出生/过期拍。 */
    private static final class Pending {
        final UUID target;
        final boolean aircraft;
        /** 这一发是从哪个炮口打出来的（按最近炮口匹配，见 {@link #claim}）；null = 引擎原路（取不到炮口）。 */
        final Vec3 muzzle;
        /** 登记时的世界拍：SWB 的炮弹是延迟生成的，认领时用得到这个差（见 {@link #claim}）。 */
        final long bornAt;
        final long expireAt;

        Pending(UUID target, boolean aircraft, Vec3 muzzle, long bornAt, long expireAt) {
            this.target = target;
            this.aircraft = aircraft;
            this.muzzle = muzzle;
            this.bornAt = bornAt;
            this.expireAt = expireAt;
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
            // Neo 侧原本就对所有卓越前线载具生效；保持原行为，不收窄（其他载具不要改）。
            if (!MaidMountCompat.isVehicle(mount)) {
                return;
            }
            if (PENDING.size() > 256) {
                PENDING.clear();
            }
            long now = mount.level().getGameTime();
            java.util.List<Pending> list = PENDING.computeIfAbsent(
                    maid.getUUID(), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            // 每车一 tick 最多几门炮；30 拍寿命内最多约十几条，留 16 条足够，防止表无限涨。
            list.removeIf(p -> now > p.expireAt);
            while (list.size() >= 16) {
                list.remove(0);
            }
            list.add(new Pending(target.getUUID(), MaidMountCompat.isAircraft(mount), muzzle,
                    now, now + ttlTicks));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
    }

    /** 待认领表（女仆 UUID → 本拍/上两拍打出的炮口记录）。 */
    private static final Map<UUID, java.util.List<Pending>> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 在飞的炮弹（弹体 UUID → 记录）。 */
    private static final Map<UUID, Track> TRACKED = new ConcurrentHashMap<>();

    /** 普通弹的纠向兜底上限（≈30 秒）。这不是"追踪 3 秒"的旧时限——384 格内任何弹的
     *  飞行时间都远小于它，实际放手条件只有命中/落地/被删/目标死亡；留这个数只为防止
     *  极端情况下绕圈弹被永久接管。 */
    private static final int MAX_TICKS_PER_SHELL = 600;

    /** 【实测一千零五十八】非固定翼的旧版追踪上限：60 拍（3 秒）+ 40 拍提前量。
     *  短距交战里给到 600 拍/120 拍会让炮弹一直回头找目标，玩家看到的"子弹鬼畜"；
     *  固定翼是从上往下打、距离长，才需要 {@link #MAX_TICKS_PER_SHELL} 那一档。 */
    private static final int LEGACY_MAX_TICKS_PER_SHELL = 60;

    /** 固定翼的一阶提前量上限（拍）：300+ 格弹道剩余飞行时间常超过 40 拍。 */
    private static final double LEAD_CAP_AIRCRAFT = 120.0;

    /** 非固定翼的旧版提前量上限（拍）。 */
    private static final double LEAD_CAP_LEGACY = 40.0;

    /**
     * <h2>
     * 「1.21.1 女仆在使用飞机上导弹武器的时候，导弹会出现乱飞的情况，我觉得最好还是跟投掷 TNT
     * 一个链路，只会盯着敌人飞 0.2 秒（TNT 是 0.5 秒，但是考虑到导弹的飞行速度很快，所以照搬时
     * 削成 0.2），不会一直盯着敌人飞行。」
     * <h2>为什么"不碰制导弹"那条旧边界要改</h2>
     * 寻的是**它自己的目标来源**（发射时的锁定/朝向前方），与我们"朝她此刻的敌人"并不是同一个 目标——于是导弹一离架就按自己那套锁一个别的方向，玩家看到的就是"乱飞"。 现在改成与投掷 TNT **同一条链路**：接管后**只在前 {@code MISSILE_LOCK_TICKS} 拍** （0.2 秒）把它掰向敌人，之后立刻撒手、完全交还它自己的制导——既不"一直盯着敌人飞" （那会让它对着一动不动的点直线冲、失去末端机动），也治了开头那一下乱飞。 窗口比 TNT 的 0.5 秒短，正是因为导弹初速快、这几拍已经足够把初始朝向纠到敌人身上。
     */
    private static final int MISSILE_LOCK_TICKS = 4;

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
                    logShellEnd(server, e.getKey(), e.getValue());
                    it.remove(); // ② 逐发纠向；返回 false = 该放手了
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
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
            // 她不在了 → 作废她的整张待认领表
            PENDING.entrySet().removeIf(e -> findEntity(server, e.getKey()) == null);
            if (PENDING.isEmpty()) {
                return;
            }
            for (ServerLevel lvl : server.getAllLevels()) {
                long now = lvl.getGameTime();
                for (Entity en : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
                    if (!(en instanceof Projectile proj)) {
                        continue;
                    }
                    String cn = en.getClass().getName();
                    if (!cn.startsWith(SWB_PKG)) {
                        continue; // 只管卓越前线的弹体
                    }
                    // 【实测七百五十三·点2】制导弹**不再跳过**：与投掷 TNT 同一链路，接管后只纠
                    // {@link #MISSILE_LOCK_TICKS} 拍（0.2 秒）就撒手，交还它自己的末端制导。
                    // 旧版整条跳过，于是它按自己那套锁定乱飞（见 MISSILE_LOCK_TICKS 注释）。
                    boolean missile = cn.contains("Missile");
                    UUID key = en.getUUID();
                    if (TRACKED.containsKey(key)) {
                        continue; // 已经认领过
                    }
                    Entity owner = proj.getOwner();
                    if (owner == null) {
                        continue;
                    }
                    java.util.List<Pending> list = PENDING.get(owner.getUUID());
                    if (list == null || list.isEmpty()) {
                        continue; // 不是"我们刚开火的那只女仆"打的
                    }
                    // 【实测一千〇六十三】按炮口位置认领：同一 tick 多门炮可能打不同的敌人，
                    // 旧版"一只女仆一个 pending 槽"会被后一门炮覆盖，炮弹认成别人的目标。
                    Pending p = takePending(list, en.position(), now);
                    if (p == null) {
                        continue;
                    }
                    // 固定翼：600 拍；其它载具：旧版 60 拍。制导弹仍是 4 拍。
                    int maxTicks = missile ? MISSILE_LOCK_TICKS
                            : (p.aircraft ? MAX_TICKS_PER_SHELL : LEGACY_MAX_TICKS_PER_SHELL);
                    Track t = new Track(p.target, owner.getUUID(), maxTicks, p.aircraft);
                    t.claimed = true;
                    t.lx = en.getX();
                    t.ly = en.getY();
                    t.lz = en.getZ();
                    t.lastSpeed = proj.getDeltaMovement().length();
                    TRACKED.put(key, t);
                    LivingEntity tl = findLiving(lvl, p.target);
                    double td = tl == null ? -1.0
                            : en.position().distanceTo(tl.getBoundingBox().getCenter());
                    logShellNow("认领 #" + shortId(key) + (missile ? " 导弹" : " 炮弹")
                            + " 起点=" + fmtVec(en.position())
                            + " 延迟=" + Math.max(0L, now - p.bornAt) + "拍"
                            + (p.muzzle == null ? ""
                                : " 炮口距=" + String.format(java.util.Locale.ROOT, "%.1f",
                                        p.muzzle.distanceTo(en.position())) + "格")
                            + " 初速=" + String.format(java.util.Locale.ROOT, "%.2f", t.lastSpeed)
                            + " 目标=" + describe(server, lvl, p.target)
                            + " 距离=" + (td < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.1f", td) + "格"));
                    log(owner.getUUID(), (missile ? "接管一枚导弹（锁定 0.2 秒）→ " : "接管一发炮弹 → ")
                            + describe(server, lvl, p.target));
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
            double d = p.muzzle.distanceToSqr(at);
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
            if (++t.ticks > t.maxTicks) {
                return false; // 超时放手（普通弹可能是绕圈的火箭弹；制导弹交还它自己的末端制导）
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
            t.lx = from.x;
            t.ly = from.y;
            t.lz = from.z;
            t.lastSpeed = speed;
            Vec3 to = target.getBoundingBox().getCenter();
            Vec3 want = to.subtract(from);
            double dist = want.length();
            if (dist < 0.5) {
                return true; // 已经贴上了，交给命中判定
            }
            // 一阶提前量：目标速度 × 剩余飞行时间；固定翼上限 120 拍，其它载具 40 拍。
            // 【R1074】提前量必须加在**目标位置**上再取方向。旧版先 normalize 再 add——
            // 单位向量 + 一段几十格的位移，等于把提前量放大成"距离"倍；目标只要带一点
            // 下落速度，弹就被掰成近乎垂直下扎，全部砸进目标前方的地面。
            double remain = dist / Math.max(speed, 0.1);
            Vec3 tv = target.getDeltaMovement();
            Vec3 aim = to.add(tv.scale(Math.min(remain,
                    t.aircraft ? LEAD_CAP_AIRCRAFT : LEAD_CAP_LEGACY))).subtract(from);
            want = aim.lengthSqr() > 1.0E-6 ? aim.normalize() : want.normalize();
            // 限速转向
            Vec3 cur = dm.normalize();
            double deg = Math.toDegrees(Math.acos(clamp(cur.dot(want), -1.0, 1.0)));
            if (Double.isNaN(deg)) {
                return true;
            }
            Vec3 newDir;
            // 【实测一千〇六十二】固定翼首发直接掰正：远距离实测炮弹认领时仍比目标方向偏
            // 40～60°（炮管固定在车体上，实际初速不是解算方向）。25°/拍要 2～3 拍才转回来，
            // 180 格外这 2～3 拍已经偏出 50～100 格，落点就进地形了。固定翼前两拍允许一次
            // 直接对准（只改方向、不改速度），其余载具仍保持 25°/拍的平滑转向。
            double maxTurn = (t.aircraft && t.ticks <= 2) ? 180.0 : TURN_DEG_PER_TICK;
            boolean snap = t.aircraft && t.ticks <= 2 && deg > TURN_DEG_PER_TICK;
            if (deg <= maxTurn) {
                newDir = want;
            } else {
                double f = maxTurn / deg; // 0..1
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
            logShell(shellId, "纠向 #" + shortId(shellId)
                    + " 位置=" + fmtVec(from)
                    + " 目标=" + target.getDisplayName().getString()
                    + " 距=" + (long) dist + "格 偏角=" + (long) deg + "°"
                    + " 速度=" + String.format(java.util.Locale.ROOT, "%.2f", speed)
                    + (snap ? " 首发校准" : ""));
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
            return false;
        }
    }

    /* ==================== 反查工具（一律按 UUID 反查，不持引用） ==================== */

    private static Entity findEntity(MinecraftServer server, UUID id) {
        try {
            Entity e = findIn(server, id);
            return e;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return null;
    }

    private static String describe(MinecraftServer server, ServerLevel lvl, UUID id) {
        try {
            Entity e = lvl.getEntity(id);
            if (e != null) {
                return String.valueOf(e.getType()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidShellHoming", ignored);
        }
        return "?";
    }

    /** 【实测一千〇六十一】这一发的终点：命中 / 落地 / 被删，都会从实体表消失。 */
    private static void logShellEnd(MinecraftServer server, UUID shellId, Track t) {
        try {
            Vec3 last = new Vec3(t.lx, t.ly, t.lz);
            String near = "";
            String tinfo = "";
            Entity te = findEntity(server, t.target);
            if (te != null) {
                tinfo = " 目标坐标=" + fmtVec(te.position())
                        + " 高差=" + String.format(java.util.Locale.ROOT, "%.1f", te.getY() - last.y);
            }
            Entity e = findEntity(server, t.maid);
            EntityMaid m = e instanceof EntityMaid em ? em : null;
            if (m != null && m.getVehicle() != null) {
                double d = last.distanceTo(m.getVehicle().position());
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
                return String.valueOf(e.getType()).replace("entity.minecraft.", "");
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

    private static String fmtVec(Vec3 v) {
        return v == null ? "?" : String.format(java.util.Locale.ROOT,
                "(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
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

    /** 世界卸载/服务器停止时清空（防跨存档残留）。 */
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
