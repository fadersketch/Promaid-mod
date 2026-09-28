package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * 骑乘链路的能力探测与公共小工具（v1.3.0(beta)·原版生物骑乘，1.21.1 版）。
 *
 * <p>本类的唯一职责：回答两个问题——「这只生物能不能给她骑」与「骑上之后该走多快」。
 * 绑定/驱动/下坐骑在 {@link RideBindManager}，物品是 {@link RideBatonItem}。
 *
 * ── 【为什么是"套僵尸的骑乘代码"】──
 * 原版僵尸骑鸡走的就是 {@code startRiding(force)} + 让**载具自己**的 AI 带着走
 * （{@code Zombie.finalizeSpawn} 里那一记 {@code startRiding(chicken)}，javap 实证）。
 * 我们原样借用：
 * <ul>
 *   <li><b>上鞍</b>：{@code maid.startRiding(mount, true)}——force = true 跳过
 *       {@code canRide}/{@code canAddPassenger} 两道门（javap 实证）。她成为乘客后 TLM
 *       自动把大脑切到 RIDE_IDLE/RIDE_WORK/RIDE_REST（{@code MaidUpdateActivityFromSchedule}
 *       字节码实证，判据 {@code isMaidInSittingPose() || isPassenger()}）。</li>
 *   <li><b>驱动</b>：原版 {@code LivingEntity.aiStep} 只在
 *       {@code getControllingPassenger() instanceof Player} 时才走 {@code travelRidden}，
 *       否则走普通 {@code travel()}（javap 实证）。而马/猪/炽足兽的
 *       {@code getControllingPassenger()} 只认**第一乘客是 Player**（猪/炽足兽还要钓竿，
 *       javap 实证；骆驼/马继承自 {@code AbstractHorse} 的同一实现）→ 女仆当乘客时它
 *       **恒为 null**，于是载具走的是普通 travel、导航照常推着它走。我们只把目的地喂进
 *       它自己的 {@code PathNavigation}。</li>
 *   <li><b>它自己的闲逛会抢方向</b>：{@code RandomStrollGoal.canUse()} 只在
 *       {@code hasControllingPassenger()} 为真时返回 false（javap 实证）——女仆当乘客时
 *       那个判据是 false（见上），所以载具自己的随机闲逛仍会跑。这一条由
 *       {@code RandomStrollGoalRiddenMixin} 补上。</li>
 * </ul>
 *
 * ── 【"可骑乘"= 能力探测】──
 * {@code instanceof Saddleable && isSaddled()}：原版马/驴/骡/骷髅马/僵尸马、猪、炽足兽、
 * 骆驼全部实现 {@link Saddleable}（javap 全量扫描实证），且都要求已上鞍。模组生物只要也
 * 实现这个接口、已上鞍，**零适配**即可骑。
 *
 * ── 【速度：载具上限与女仆上限取最大】──
 * 导航的 speed 参数是**倍率**（{@code MoveControl} 内部 {@code speedModifier ×
 * MOVEMENT_SPEED}，javap 实证），所以换算成 {@code max(载具, 女仆) / 载具}。
 */
public final class MaidRideKit {

    /** persistentData：她当前骑的这只坐骑（UUID 字符串）——跨存档恢复用 */
    public static final String TAG_RIDE_MOUNT = "maid_smart_ride_mount";

    private MaidRideKit() {
    }

    /** 总开关（配置 combat.ride，默认开） */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_ENABLE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static double followDist() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FOLLOW_DIST.get();
        } catch (Throwable ignored) {
            return 5.0;
        }
    }

    public static double speedScale() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SPEED_SCALE.get();
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /* ==================== 能力探测 ==================== */

    public static boolean isRideableMount(Entity e, EntityMaid maid) {
        try {
            if (!(e instanceof Mob) || e == maid || !e.isAlive()) {
                return false;
            }
            if (e instanceof EntityMaid) {
                return false;
            }
            if (!(e instanceof Saddleable saddle) || !saddle.isSaddled()) {
                return false;
            }
            return e.getPassengers().isEmpty() || e.getPassengers().get(0) == maid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 为什么骑不上（能骑时返回 null） */
    public static String denyReason(Entity e, EntityMaid maid) {
        try {
            if (!(e instanceof Mob) || e == maid || e instanceof EntityMaid) {
                return "这个不能当坐骑～";
            }
            if (!e.isAlive()) {
                return "它已经不在了……";
            }
            if (!(e instanceof Saddleable)) {
                return "它不是能上鞍的坐骑～";
            }
            if (!((Saddleable) e).isSaddled()) {
                return "先给它装上鞍再绑给我吧～";
            }
            if (!e.getPassengers().isEmpty() && e.getPassengers().get(0) != maid) {
                return "它背上已经有人了～";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ==================== 速度 ==================== */

    public static double moveSpeedOf(Entity e) {
        try {
            if (e instanceof LivingEntity le) {
                return le.getAttributeValue(Attributes.MOVEMENT_SPEED);
            }
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    public static double speedModifierFor(Entity mount, EntityMaid maid) {
        try {
            double m = moveSpeedOf(mount);
            double d = moveSpeedOf(maid);
            if (m <= 0.0) {
                return 1.0;
            }
            double target = Math.max(m, d) * speedScale();
            double mod = target / m;
            return Math.max(0.3, Math.min(mod, 4.0));
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /* ==================== 骑乘关系 ==================== */

    public static Entity ridingMount(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            Entity v = maid.getVehicle();
            return v instanceof EntityMaid ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isRidingMount(EntityMaid maid) {
        return ridingMount(maid) != null;
    }

    public static EntityMaid riderOf(Entity mount) {
        try {
            for (Entity p : mount.getPassengers()) {
                if (p instanceof EntityMaid m) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isDriven(Entity mount) {
        try {
            EntityMaid m = riderOf(mount);
            if (m == null) {
                return false;
            }
            if (!com.maidsmart.tool.MaidScope.owned(m)) {
                return false;
            }
            if (MaidBroomKit.isBroomTask(m)) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static LivingEntity ownerToFollow(EntityMaid maid) {
        try {
            LivingEntity owner = maid.getOwner();
            if (owner == null || !owner.isAlive() || owner.isRemoved()) {
                return null;
            }
            if (owner.level() != maid.level()) {
                return null;
            }
            return owner;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void feedNavigation(Entity mount, Vec3 target, double modifier) {
        try {
            if (!(mount instanceof Mob mob)) {
                return;
            }
            PathNavigation nav = mob.getNavigation();
            nav.moveTo(target.x, target.y, target.z, modifier);
        } catch (Throwable ignored) {
        }
    }

    public static void stopNavigation(Entity mount) {
        try {
            if (mount instanceof Mob mob) {
                mob.getNavigation().stop();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String describe(Entity e) {
        try {
            if (e instanceof EntityMaid m) {
                return com.maidsmart.tool.PromaidLog.nameOf(m);
            }
            if (e != null) {
                return String.valueOf(e.getType()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static String tag(UUID id) {
        return id == null ? "" : id.toString();
    }
}
