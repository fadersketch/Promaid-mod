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
 * 然后逐拍把它们的速度方向**朝目标掰**（限速转向，保住初速大小）。
 *
 * <h2>怎么认出"是我们的炮弹"</h2>
 * <ol>
 *   <li>{@code instanceof Projectile}（否则连"从哪里来"都问不出来）；</li>
 *   <li>类名以 {@code com.atsuishio.superbwarfare.} 开头——**用类名字符串判**，不 import 那个
 *       模组的任何类型（它与本模组是并列的可选依赖，两侧都不能硬链，本工程通篇如此）；</li>
 *   <li>{@code getOwner()} 的 UUID == 我们记下的**那只女仆**——反编译 {@code GunItem.shootBullet}
 *       实证：弹体创建后第一件事就是 {@code setOwner(parameters.shooter)}，而女仆开火那条重载
 *       把 {@code shooter} 传的就是她本人。这一层同时挡掉了玩家的炮弹与别的模组的弹。</li>
 * </ol>
 *
 * <p>【1.20.1 侧差异】只有原版名字不同（{@code m_8583_}/{@code m_20148_}/{@code m_19879_}），
 * 逻辑与 1.21.1 树逐字一致。
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
        int ticks;

        Track(UUID target, UUID maid, int maxTicks) {
            this.target = target;
            this.maid = maid;
            this.maxTicks = maxTicks;
        }
    }

    /** 待认领表（女仆 UUID → 目标 UUID）。 */
    private static final Map<UUID, UUID> PENDING = new ConcurrentHashMap<>();

    /** 在飞的炮弹（弹体 UUID → 记录）。 */
    private static final Map<UUID, Track> TRACKED = new ConcurrentHashMap<>();

    /** 每发最多纠向的拍数（≈3 秒）。超了就放手（它可能是绕圈的火箭弹）。 */
    private static final int MAX_TICKS_PER_SHELL = 60;

    /**
     * 【实测七百五十三·点2】制导弹（类名含 {@code Missile}）的纠向窗口：**4 拍 = 0.2 秒**。
     *
     * <h2>玩家原话</h2>
     * 「1.20.1 女仆在使用飞机上导弹武器的时候，导弹会出现乱飞的情况，我觉得最好还是跟投掷 TNT
     * 一个链路，只会盯着敌人飞 0.2 秒（TNT 是 0.5 秒，但是考虑到导弹的飞行速度很快，所以照搬时
     * 削成 0.2），不会一直盯着敌人飞行。」（1.21.1 侧同一处同口径，两树镜像。）
     *
     * <h2>为什么"不碰制导弹"那条旧边界要改</h2>
     * 旧版这一档**跳过**类名含 {@code Missile} 的弹体（理由：它自己带寻的，插手会打架）。但那种
     * 寻的是**它自己的目标来源**（发射时的锁定/朝向前方），与我们"朝她此刻的敌人"并不是同一个
     * 目标——于是导弹一离架就按自己那套锁一个别的方向，玩家看到的就是"乱飞"。
     * 现在改成与投掷 TNT **同一条链路**：接管后**只在前 {@code MISSILE_LOCK_TICKS} 拍**
     * （0.2 秒）把它掰向敌人，之后立刻撒手、完全交还它自己的制导——既不"一直盯着敌人飞"
     * （那会让它对着一动不动的点直线冲、失去末端机动），也治了开头那一下乱飞。
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
     * 本拍刚开火、弹体还没出现的那些——{@code tickAttack} 里"打出去了"之后立刻调。
     *
     * <p>为什么不直接把弹体传进来：{@code vehicleShoot} 是**延迟执行**的（反编译实证它先
     * {@code queueServerWork(SHOOT_DELAY_TIME)} 再发射），这一拍开火时弹体**还没生成**。
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
            PENDING.put(maid.m_20148_(), target.m_20148_());
        } catch (Throwable ignored) {
        }
    }

    /** 服务端每 tick 一次（由 {@code ProMaidExtension} 调）。 */
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

    /** 把待认领表里的记录与真实弹体对上（每个维度只遍历一次实体表）。 */
    private static void claim(MinecraftServer server) {
        try {
            Map<UUID, UUID> want = new ConcurrentHashMap<>(PENDING);
            for (Map.Entry<UUID, UUID> e : want.entrySet()) {
                if (findEntity(server, e.getKey()) == null) {
                    PENDING.remove(e.getKey()); // 她不在了 → 作废
                }
            }
            if (want.isEmpty()) {
                return;
            }
            for (ServerLevel lvl : server.m_129785_()) {
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
                    UUID key = en.m_20148_();
                    if (TRACKED.containsKey(key)) {
                        continue; // 已经认领过
                    }
                    Entity owner = proj.m_19749_();
                    if (owner == null) {
                        continue;
                    }
                    UUID target = want.get(owner.m_20148_());
                    if (target == null) {
                        continue; // 不是"我们刚开火的那只女仆"打的
                    }
                    TRACKED.put(key, new Track(target, owner.m_20148_(),
                            missile ? MISSILE_LOCK_TICKS : MAX_TICKS_PER_SHELL));
                    log(owner.m_20148_(), (missile ? "接管一枚导弹（锁定 0.2 秒）→ " : "接管一发炮弹 → ")
                            + describe(lvl, target));
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
            if (++t.ticks > t.maxTicks) {
                return false; // 超时放手（普通弹可能是绕圈的火箭弹；制导弹交还它自己的末端制导）
            }
            Entity shell = findEntity(server, shellId);
            if (shell == null || !shell.m_6084_() || shell.m_213877_()) {
                return false; // 打中/落地/被删 → 忘掉
            }
            if (!(shell instanceof Projectile)) {
                return false;
            }
            if (!shell.getClass().getName().startsWith(SWB_PKG)) {
                return false; // 换过类型 → 放手
            }
            LivingEntity target = findLiving(shell.m_9236_(), t.target);
            if (target == null) {
                return false; // 目标没了/不在同维度 → 放手
            }
            Vec3 dm = shell.m_20184_();
            double speed = dm.m_82553_();
            if (speed < 0.05) {
                return true; // 刚发射那一拍速度还没给上 → 下拍再看
            }
            Vec3 from = shell.m_20182_();
            Vec3 to = target.m_20191_().m_82399_();
            Vec3 want = to.m_82549_(from);
            double dist = want.m_82553_();
            if (dist < 0.5) {
                return true; // 已经贴上了，交给命中判定
            }
            want = want.m_82541_();
            // 一阶提前量：目标横向速度 × 剩余飞行时间（近处权重自然变小）
            double remain = dist / Math.max(speed, 0.1);
            Vec3 tv = target.m_20184_();
            Vec3 aim = want.m_82505_(tv.m_82490_(Math.min(remain, 40.0)));
            if (aim.m_82556_() > 1.0E-6) {
                want = aim.m_82541_();
            }
            // 限速转向
            Vec3 cur = dm.m_82541_();
            double deg = Math.toDegrees(Math.acos(clamp(cur.m_82554_(want), -1.0, 1.0)));
            if (Double.isNaN(deg)) {
                return true;
            }
            Vec3 newDir;
            if (deg <= TURN_DEG_PER_TICK) {
                newDir = want;
            } else {
                double f = TURN_DEG_PER_TICK / deg; // 0..1
                Vec3 blended = cur.m_82505_(want.m_82549_(cur).m_82490_(f));
                if (blended.m_82556_() < 1.0E-8) {
                    return true;
                }
                newDir = blended.m_82541_();
            }
            shell.m_20256_(newDir.m_82490_(speed)); // 只改方向，保住原速度
            // 朝向跟着走（渲染/部分判定读它；弹体自己每拍 updateHeading 也做同一件事）
            shell.m_146922_((float) Math.toDegrees(Math.atan2(newDir.f_82479_, newDir.f_82481_)));
            shell.m_146926_((float) Math.toDegrees(Math.atan2(newDir.f_82480_,
                    Math.sqrt(newDir.f_82479_ * newDir.f_82479_ + newDir.f_82481_ * newDir.f_82481_))));
            log(t.maid, "纠向 " + String.valueOf(target.m_6095_()).replace("entity.minecraft.", "")
                    + " 偏角=" + (long) deg + "° 距离=" + (long) dist + "格");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 反查工具（一律按 UUID 反查，不持引用） ==================== */

    private static Entity findEntity(MinecraftServer server, UUID id) {
        try {
            for (ServerLevel lvl : server.m_129785_()) {
                Entity e = lvl.m_8791_(id);
                if (e != null) {
                    return e;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在**指定**维度里找一个活着的 LivingEntity（跨维度的目标对炮弹没有意义）。 */
    private static LivingEntity findLiving(net.minecraft.world.level.Level level, UUID id) {
        try {
            if (level instanceof ServerLevel sl) {
                Entity e = sl.m_8791_(id);
                if (e instanceof LivingEntity le && le.m_6084_()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String describe(ServerLevel lvl, UUID id) {
        try {
            Entity e = lvl.m_8791_(id);
            if (e != null) {
                return String.valueOf(e.m_6095_()).replace("entity.minecraft.", "");
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
