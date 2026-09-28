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

    /**
     * 【实测七百一十六·点4】她此刻是不是"被棍子绑上坐骑、需要连坐骑一起搬运"的骑乘女仆。
     * 判据自洽（不查链路表）：正骑着一只非女仆的 {@link Saddleable} 已上鞍生物，且身上带着
     * {@link #TAG_RIDE_MOUNT}（绑定成功时写的持久化痕迹）。服务端重启/区块重载后、链路表
     * 还没重建时照样认得出她——与扫帚那边 {@code MaidBroomKit.isBroomAirborne} 同口径。
     *
     * <p>为什么传送要单独问她：她一旦是乘客，原版 {@code teleportTo} 内部会先 {@code unRide()}
     * ——直接传她就等于"把坐骑扔在原地、人掉到主人身边"。所以传送链路必须先认出来她，改走
     * "连坐骑一起搬"（见 {@code MaidChunkLoadManager.recallRideRider}）。
     */
    public static boolean isRideRider(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Entity v = ridingMount(maid);
            if (v == null || !(v instanceof Mob)) {
                return false;
            }
            if (!(v instanceof Saddleable saddle) || !saddle.isSaddled()) {
                return false; // 现在骑的不是"已上鞍的坐骑"（船/矿车/别人的椅子一律不算）
            }
            String tag = maid.getPersistentData().getString(TAG_RIDE_MOUNT);
            return tag != null && !tag.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百一十六·点2】她自己此刻**想去的地方**——读她自己的 {@code PathNavigation}
     * 的目标点（"她的两条腿本来打算怎么走"）。这就是玩家说的"1:1 还原女仆原有的走路逻辑"：
     * 骑上坐骑后她的走位意图并没有消失（我们的单兵战术 core 230 与 TLM 跟随 core 3 都照常
     * 在她自己的导航上写目的地），只是位移被"她是乘客、位置由载具决定"吃掉而已。把这个目标点
     * 原样转达给坐骑的导航，坐骑就按她原本的走位跑（马这种只会跑的坐骑由此 1:1 复现）。
     */    public static Vec3 ownNavigationTarget(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            PathNavigation nav = maid.getNavigation();
            if (nav == null || nav.isDone()) {
                return null;
            }
            net.minecraft.core.BlockPos p = nav.getTargetPos();
            if (p == null) {
                return null;
            }
            return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        } catch (Throwable ignored) {
            return null;
        }
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

    /**
     * 给载具喂"去这里"（【实测七百一十六·点2】分两个渠道）。
     *
     * <p>玩家原话："至少对于像马这种只会跑步的应该要 1:1 还原女仆原有的走路逻辑，其他模组的
     * 生物同理。如果他不只是会跑路，那么再另开一个渠道。"
     * <ul>
     *   <li><b>渠道一（{@code GroundPathNavigation}）＝只会跑的坐骑</b>：把目标点喂进它自己的
     *       寻路——它按地面寻路跑过去，上下坡/绕障/跳跃交给它，这就是"1:1 还原她两条腿的走位"
     *       （走位点由她的导航给出，见 {@link #ownNavigationTarget}）。</li>
     *   <li><b>渠道二（非地面寻路：飞行/两栖/别的模组坐骑）＝不只是会跑路的</b>：地面寻路表达
     *       不了她的意图，改**直连操纵**（{@code MoveControl#setWantedPosition}）——不重新规划
     *       路径，直接朝目标点给操纵意图。与渠道一互斥。</li>
     * </ul>
     */
    public static void feedNavigation(Entity mount, Vec3 target, double modifier) {
        try {
            if (!(mount instanceof Mob mob)) {
                return;
            }
            if (mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation) {
                // 渠道一：只会跑的坐骑 —— 1:1 走位（喂它自己的地面寻路）
                mob.getNavigation().moveTo(target.x, target.y, target.z, modifier);
            } else {
                // 渠道二：不只是会跑路的坐骑 —— 直连操纵，不依赖地面寻路
                mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, modifier);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 停下载具（她下鞍 / 找不到主人时用）——两个渠道都要收手 */
    public static void stopNavigation(Entity mount) {
        try {
            if (!(mount instanceof Mob mob)) {
                return;
            }
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
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
