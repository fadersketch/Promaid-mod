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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骑乘链路的能力探测与公共工具：只回答「能不能给她骑」与「骑上后该走多快」。
 * 绑定 / 驱动 / 下坐骑在 {@link RideBindManager}，物品是 {@link RideBatonItem}。
 *
 * <p>上鞍：{@code maid.startRiding(mount, true)}——force 跳过原版 canRide / canAddPassenger
 * 两道门（javap 实证）。不自己写驱动：女仆当乘客时马/猪/骆驼的
 * {@code getControllingPassenger()} 恒为 null（只认第一乘客是 Player），原版走普通
 * {@code travel()}——只把目的地喂进载具自己的 {@code PathNavigation} 即可，绕障/跳跃交给它。
 * 载具自己的 {@code RandomStrollGoal} 仍会抢方向，由 {@code RandomStrollGoalRiddenMixin}
 * 按"玩家骑就不闲逛"的原版规则抑制（口径同 {@link #isDriven}）。
 *
 * <p>"可骑乘"= 能力探测不写死 id：{@code Saddleable && isSaddled()}，模组生物零适配。
 * <p>速度见 {@link #speedModifierFor}：导航 speed 是倍率，最终 = max(载具, 女仆) / 载具速度。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    public static double followDist() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FOLLOW_DIST.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return 5.0;
        }
    }

    public static double speedScale() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SPEED_SCALE.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return 1.0;
        }
    }

    /* ==================== 家具类坐骑黑名单 ==================== */

    /**
     * TLM 的椅子 / 坐垫让女仆变成"乘客"，会误落进本链路的乘客判据（当坐骑、抑制闲逛、连坐骑搬）；
     * 它们其实是家具，不是坐骑。判据用**实体类型注册名**（两树共用；配置可加别的可坐家具 id，
     * 默认 {@code touhou_little_maid:chair} / {@code sit}）。实测七百一十七。
     */
    public static boolean isFurniture(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            java.util.List<? extends String> list;
            try {
                list = com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FURNITURE_BLACKLIST.get();
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
                return false;
            }
            if (list == null || list.isEmpty()) {
                return false;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (key == null) {
                return false;
            }
            String id = key.toString();
            for (String s : list) {
                if (s == null) {
                    continue;
                }
                String t = s.trim();
                if (!t.isEmpty() && t.equalsIgnoreCase(id)) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
        return false;
    }

    /** 她此刻是不是正坐在黑名单化的家具上（= 本模组所有骑乘改动对她一律不生效） */
    public static boolean isOnFurniture(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            Entity v = maid.getVehicle();
            return v != null && isFurniture(v);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /* ==================== 扫帚：不是坐骑 ==================== */

    /**
     * 扫帚让女仆也成为"乘客"，但它有自己一整条飞行链路（MaidBroomKit / MaidBroomDrive）——当坐骑会打架
     * （指挥棒会把她拽下来、{@link #isDriven} 会抑制扫帚行为、传送会连扫帚一起搬），故一刀切掉。
     * 判据用**类型**而非注册名（与 MaidBroomKit.isBroomItem 同写法，TLM 改 id 不静默失效）。实测七百一十八·点4。
     */
    public static boolean isBroom(Entity e) {
        try {
            return e instanceof com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /* ==================== 「只有棍子绑的才算」 ==================== */

    /**
     * 判据 = 她身上带着 {@link #TAG_RIDE_MOUNT}（绑定成功那刻写、解绑/脱落时清，随存档持久化）。
     * 只有"我们用指挥棒绑的"才走我们的骑乘链路；原版/别的模组让她当乘客（船/矿车/椅子/扫帚…）
     * 一律交还原版规则。取她自己身上的痕迹而非查链路表——服务端重启/区块重载后链路表未重建
     * 的那一拍也能认出她（与 MaidBroomKit.isBroomAirborne 同口径）。实测七百一十八·点4。
     */
    public static boolean isBatonBound(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            String tag = maid.getPersistentData().getString(TAG_RIDE_MOUNT);
            return tag != null && !tag.isEmpty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /* ==================== 能力探测 ==================== */

    public static boolean isRideableMount(Entity e, EntityMaid maid) {
        try {
            if (e == null || e == maid || !e.isAlive()) {
                return false;
            }
            if (e instanceof EntityMaid) {
                return false;
            }
            if (isFurniture(e)) {
                return false; // 实测七百一十七：家具（椅子/坐垫）不是坐骑——本链路一律不碰
            }
            if (isBroom(e)) {
                return false; // 实测七百一十八：扫帚有自己那条飞行链路，不算坐骑
            }
            // v1.3.0(beta) 实测七百一十九【模组坐骑通解通法】：卓越前线载具 / 冰火传说龙
            // **不是 Mob、不是 Saddleable**，走不了下面那条通用档——单独认出来（反射，编译期
            // 不依赖两家模组）。它们能不能驾由 MaidMountCompat.denyReason 回答。
            if (MaidMountCompat.kindOf(e) != null) {
                return MaidMountCompat.denyReason(e) == null;
            }
            if (!(e instanceof Mob)) {
                return false;
            }
            // 【无鞍可骑仆从后门】诡厄巫法的红石巨兽一类：实现 PlayerRideable/IAutoRideable
            // 但**没有鞍**这一环（原版靠"空手右击即上车"）。必须在下面那条 Saddleable 闸之前认。
            if (isNoSaddleRideable(e)) {
                return true;
            }
            if (!(e instanceof Saddleable saddle) || !saddle.isSaddled()) {
                return false;
            }
            return e.getPassengers().isEmpty() || e.getPassengers().get(0) == maid;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /* ==================== 【无鞍可骑仆从后门】 ==================== */

    /** 无鞍可骑的总开关（配置 combat.ride.noSaddlePets，默认开）。 */
    public static boolean noSaddlePetsEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_NO_SADDLE_PETS.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return true;
        }
    }

    /**
     * "声明可骑但没有鞍"的那一类（诡厄红石巨兽 / 下界合金巨兽仆从）——本模组的后门判据。
     * 它们实现 {@code PlayerRideable}（空标记接口）或诡厄 {@code IAutoRideable}，都不是
     * {@link Saddleable}，原版只给"已上鞍的 Saddleable"放行因而整类被挡。判据：
     * ① 是 {@link Mob}；② 是 {@code PlayerRideable} 或 {@code IAutoRideable}（反射探测）；
     * ③ <b>不能装鞍</b>（{@code !(e instanceof Saddleable)}）——③必须留，否则没上鞍的原版
     * 马/骆驼也会落进本档、绕过"先装鞍"那道闸。实测七百七十。
     */
    public static boolean isNoSaddleRideable(Entity e) {
        try {
            if (!noSaddlePetsEnabled()) {
                return false;
            }
            if (!(e instanceof Mob)) {
                return false;
            }
            if (e instanceof Saddleable) {
                return false; // 有鞍这一环的（原版兽）走原来的装鞍语义，本条不碰
            }
            if (e instanceof net.minecraft.world.entity.PlayerRideable) {
                return true; // 原版标记接口（编译期判据；红石巨兽、下界合金巨兽仆从都实现了它）
            }
            // 诡厄自己的接口：整条链路上它不一定被声明，只声明在类上，所以按类名反射探测其接口表，
            // 编译期不依赖诡厄（没装 / 换版本 → false，一个字节都不碰）。两套包根与
            // {@code MaidGoetyCompat.ROOTS} 同口径（官方版在前，万法皆通 1.21 分支那套在后）。
            Class<?> c = e.getClass();
            while (c != null) {
                for (Class<?> itf : c.getInterfaces()) {
                    String n = itf.getName();
                    if (n.endsWith(".api.entities.IAutoRideable")
                            && (n.startsWith("com.Polarice3.Goety") || n.startsWith("za.co.infernos.goety"))) {
                        return true;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
        return false;
    }

    /* ==================== 【实测七百七十·模组仆从坐骑：单独一个区间】 ==================== */

    /**
     * "模组仆从坐骑"单独一个区间（与卓越前线载具同级）：判据 = {@link #isNoSaddleRideable}。
     * 这类仆从自带整套战斗 AI；女仆骑上后本模组只做两件事——{@link #applyRiddenSpeed} 赋速 +
     * {@link #reopenRiddenCombatGoals} 开回它的 goal 控制位，其余一字不写（不喂走位 / 不写目标 /
     * 不拦寻路）。home 模式例外：那时不开控制位 = 坐骑停住。原版兽 / 载具 / 别的模组均不受影响。
     * 实测七百七十。
     */
    public static boolean servantAutoEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SERVANT_AUTO.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return true;
        }
    }

    /** 【实测七百七十】女仆受伤转给坐骑的总开关（配置 {@code combat.ride.servantDamageTransfer}，默认开）。 */
    public static boolean servantTransferEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SERVANT_TRANSFER.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return true;
        }
    }

    /* ==================== 【实测七百七十·只给速度】 ==================== */

    /** 我们给坐骑挂的"女仆速度"修改器的 id（1.21.1 用注册名；1.20.1 用同一名称派生的 UUID）。 */
    private static final net.minecraft.resources.ResourceLocation SERVANT_SPEED_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "ride_servant_speed");

    /**
     * 【实测七百七十】把她的移动速度赋给这只模组仆从坐骑——本区间我们唯一做的"行动"。
     *
     * <p>做法：每拍把坐骑 {@code MOVEMENT_SPEED} 上的"我们的修改器"更新成
     * {@code 她的速度 × speedScale() − 它不带我们修改器时的速度}（ADDITION）——于是它的**有效速度
     * 恰好等于她**，且随时跟着她的属性变（好感度 / 装备 / 药水都会变）。同一 id 覆盖 = 幂等；
     * 解绑时由 {@link #clearRiddenSpeed} 摘掉，不留痕、不写存档（transient 修改器不落 NBT）。
     */
    public static void applyRiddenSpeed(Entity mount, EntityMaid maid) {
        try {
            if (!(mount instanceof LivingEntity le) || maid == null) {
                return;
            }
            net.minecraft.world.entity.ai.attributes.AttributeInstance inst =
                    le.getAttribute(Attributes.MOVEMENT_SPEED);
            if (inst == null) {
                return;
            }
            double hers = maid.getAttributeValue(Attributes.MOVEMENT_SPEED) * speedScale();
            if (!(hers > 0.0)) {
                return;
            }
            inst.removeModifier(SERVANT_SPEED_ID);
            double base = inst.getValue();
            inst.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    SERVANT_SPEED_ID, hers - base,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    /** 摘掉 {@link #applyRiddenSpeed} 挂的修改器（解绑 / 链路失效时）。 */
    public static void clearRiddenSpeed(Entity mount) {
        try {
            if (mount instanceof LivingEntity le) {
                net.minecraft.world.entity.ai.attributes.AttributeInstance inst =
                        le.getAttribute(Attributes.MOVEMENT_SPEED);
                if (inst != null) {
                    inst.removeModifier(SERVANT_SPEED_ID);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    /* ==================== 【实测七百七十·把它自己的 AI 开回来】 ==================== */

    /**
     * 把"被女仆骑着"误关掉的 goal 控制位开回来，让模组仆从坐骑用它自己的 AI。根因：原版
     * {@code Mob.updateControlFlags()} 见"驾驶者是 Mob"就把 goalSelector 的 MOVE/JUMP/LOOK 全停
     * （{@code getControllingPassenger()} 认第一乘客是 Mob，女仆就是 Mob），诡厄又叠加关掉 TARGET
     * ——下界合金巨兽仆从的接近与全部技能因此全废。本方法在 {@code MobRiddenControlFlagsMixin}
     * 调用点注入（兼容诡厄重写）；四判据（总开关 / 是 NoSaddleRideable / 是棍子绑的 / 不在 home）
     * 全中则开回 MOVE/LOOK/JUMP/TARGET。重开 TARGET 不打背上的女仆（SummonTargetGoal 排除 owner）。实测七百七十。
     */
    public static void reopenRiddenCombatGoals(Mob mount) {
        try {
            if (!servantAutoEnabled()) {
                return;
            }
            if (mount == null || !isNoSaddleRideable(mount)) {
                return;
            }
            if (!(mount.getFirstPassenger() instanceof EntityMaid maid)) {
                return;
            }
            if (!isDriven(mount)) {
                return;
            }
            if (maid.isHomeModeEnable()) {
                return; // home 模式：不接管 = 它自己的 goal 保持关闭 = 坐骑停住（通用例外）
            }
            mount.goalSelector.setControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE, true);
            mount.goalSelector.setControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.LOOK, true);
            mount.goalSelector.setControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.JUMP, true);
            mount.goalSelector.setControlFlag(net.minecraft.world.entity.ai.goal.Goal.Flag.TARGET, true);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    /** 为什么骑不上（能骑时返回 null） */
    public static String denyReason(Entity e, EntityMaid maid) {
        try {
            if (e == null) {
                return "这个不能当坐骑～";
            }
            // 家具/扫帚的判据放在最前：它们不是 Mob，若先走下面那句"不是能上鞍的坐骑"
            // 就永远说不出它们的专属理由（实测七百一十八·点4）。
            if (isFurniture(e)) {
                return "这是家具，不是坐骑～"; // 实测七百一十七：椅子/坐垫等黑名单家具
            }
            if (isBroom(e)) {
                return "扫帚有它自己的飞法，不用棍子管～"; // 实测七百一十八：指挥棒不许选中扫帚
            }
            // v1.3.0(beta) 实测七百一十九【模组坐骑】——卓越前线载具 / 冰火传说龙（反射探测）
            if (MaidMountCompat.kindOf(e) != null) {
                return MaidMountCompat.denyReason(e);
            }
            if (!(e instanceof Mob) || e == maid || e instanceof EntityMaid) {
                return "这个不能当坐骑～";
            }
            if (!e.isAlive()) {
                return "它已经不在了……";
            }
            // 【无鞍可骑仆从后门】放在装鞍闸之前：红石巨兽这类本来就没有鞍这一环，不能回
            // "它不是能上鞍的坐骑～"。
            if (isNoSaddleRideable(e)) {
                return null;
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
        return null;
    }

    /* ==================== 速度 ==================== */

    public static double moveSpeedOf(Entity e) {
        try {
            if (e instanceof LivingEntity le) {
                return le.getAttributeValue(Attributes.MOVEMENT_SPEED);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return 1.0;
        }
    }

    /* ==================== 【实测七百四十九·点1】「有没有模式在指挥她走位」 ==================== */

    /**
     * 她此刻是不是"有模式在指挥走位"——判据 = 任务不是 TLM 的空闲任务 {@code touhou_little_maid:idle}
     * （空闲 / 未就绪 → false = 走默认"跟主人走"档）。为什么看任务而不是"导航有没有目标"：她当乘客
     * 时 TLM 跟随与我们的 core 行为照样在她自己的导航上写走位点，只有看任务才能区分"模式在指挥"
     * 还是"跟随 / 闲逛在写"。实测七百四十九·点1。
     */
    public static boolean hasModeMovement(EntityMaid maid) {
        try {
            if (maid == null || maid.getTask() == null) {
                return false; // 任务还没就绪 → 当"没有模式"，走默认跟随档（最保守）
            }
            net.minecraft.resources.ResourceLocation uid = maid.getTask().getUid();
            if (uid == null) {
                return false;
            }
            return !("touhou_little_maid".equals(uid.getNamespace()) && "idle".equals(uid.getPath()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /* ==================== 【实测七百四十九·点3】「别叠罗汉」的公共标准 ==================== */

    /**
     * 坐骑也要防叠罗汉，与扫帚**同一套标准**：相位错开（按 UUID）+ 邻近互斥（目标点被同伴顶开）。
     * 坐骑无"盘旋"，只需后半截；四个参数与算式都放在本类，扫帚 / 坐骑各自只负责凑齐同伴位置。
     * 实测七百四十九·点3。
     */

    /** 两只"载着女仆的坐骑/扫帚"之间的最小水平间距（格）：近到这个数以内就互相让位。 */
    public static final double SEP_R = 2.0;

    /**
     * 接敌期间的最小水平间距（格）——比平时的 {@link #SEP_R} 大一档。
     *
     * <p>理由与扫帚那边逐字相同：平时靠得近只是观感问题，**接敌**时两只靠得近就是活靶子
     * （敌人一箭穿过前面那只还会打到后面那只）。
     */
    public static final double SEP_R_COMBAT = 4.0;

    /** 一次让位最多挪出去多少格（封顶：互斥只做修正，绝不把"去哪"整条盖掉）。 */
    public static final double SEP_MAX = 1.5;

    /** 接敌档的更大封顶（格）：间距要求放宽到 {@link #SEP_R_COMBAT} 之后，1.5 格追不上要求。 */
    public static final double SEP_MAX_COMBAT = 2.5;

    /**
     * 每只女仆一个**稳定的相位**（弧度，0~2π）：只跟她的 UUID 有关，同一只女仆永远同一个值。
     *
     * <p>用途与扫帚那边的 {@code MaidBroomDrive.phaseOf} 完全一致——当两个目标点**水平方向完全
     * 叠在一起**（推不开的退化情形）时，需要一个**确定**的"往哪边让"的方向；随机的话她会原地抖。
     */
    public static double ridePhase(java.util.UUID id) {
        try {
            return id == null ? 0.0 : ((id.hashCode() & 0xFFFF) / 65536.0) * (Math.PI * 2.0);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return 0.0;
        }
    }

    /**
     * 「别叠罗汉」公共算式：把目标点 {@code aim} 推离一批同伴位置 {@code peers}。只推水平
     * （竖直交给起飞 / 爬升相位）；有封顶（互斥只做修正，不盖掉真正"去哪"）；退化情形（同伴与
     * 目标点几乎重合）用 {@link #ridePhase} 定"往哪边让"，必须确定。
     * @param aim 原始目标点  @param phase 兜底方向（弧度）  @param peers 同伴位置
     * @param combat 这一拍是否接敌（决定用哪档间距与封顶）
     * @return 修正后的目标点；不需修正 / 异常一律原样返回 {@code aim}
     */
    public static Vec3 separateAim(Vec3 aim, double phase, java.util.List<Vec3> peers, boolean combat) {
        return separateAim(aim, phase, peers,
                combat ? SEP_R_COMBAT : SEP_R,
                combat ? SEP_MAX_COMBAT : SEP_MAX);
    }

    /**
     * 同一算式的**可调间距档**——给卓越前线载具（坦克 / 装甲车这类大车）用。2 / 4 格间距是按扫帚
     * 与人形定的，对 4 格宽坦克等于没让位，故载具按"车体全宽 + 1.5 格缝"调大再走本档；**算式仍只有
     * 这一处**。实测七百五十八。
     * @param sepR 要求的水平间距（格）；&le;0 视为"不修正"
     * @param sepMax 一次让位的位移封顶（格）
     */
    public static Vec3 separateAim(Vec3 aim, double phase, java.util.List<Vec3> peers,
                                   double sepR, double sepMax) {
        if (aim == null) {
            return null;
        }
        try {
            if (peers == null || peers.isEmpty() || sepR <= 0.0) {
                return aim;
            }
            double ox = 0.0;
            double oz = 0.0;
            for (Vec3 p : peers) {
                if (p == null) {
                    continue;
                }
                double dx = aim.x - p.x;
                double dz = aim.z - p.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= sepR) {
                    continue;
                }
                if (d < 0.05) {
                    ox += Math.cos(phase) * sepR * 0.5;
                    oz += Math.sin(phase) * sepR * 0.5;
                    continue;
                }
                double push = (sepR - d) / d;
                ox += dx * push;
                oz += dz * push;
            }
            double len = Math.sqrt(ox * ox + oz * oz);
            if (len < 1.0E-4) {
                return aim;
            }
            double k = Math.min(1.0, sepMax / len);
            return new Vec3(aim.x + ox * k, aim.y, aim.z + oz * k);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return aim;
        }
    }

    /* ==================== 骑乘关系 ==================== */

    public static Entity ridingMount(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            Entity v = maid.getVehicle();
            if (v instanceof EntityMaid) {
                return null;
            }
            if (isFurniture(v)) {
                return null; // 实测七百一十七：家具（椅子/坐垫）不算坐骑——本链路一律不碰
            }
            return v;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return null;
        }
    }

    public static boolean isRidingMount(EntityMaid maid) {
        return ridingMount(maid) != null;
    }

    /**
     * 她是不是"被棍子绑上坐骑、需连坐骑一起搬运"的骑乘女仆。判据自洽（不查链路表）：正骑着一只非女仆的
     * {@link Saddleable} 已上鞍生物 + 身上带 {@link #TAG_RIDE_MOUNT}（持久化痕迹），故重启 / 区块重载后
     * 链路表未重建也能认出（与 MaidBroomKit.isBroomAirborne 同口径）。为什么传送要单独问她：乘客状态下
     * 原版 {@code teleportTo} 会先 {@code unRide()}，直接传 = 把坐骑扔下，所以要改走"连坐骑一起搬"。
     * 实测七百一十六·点4 / 七百一十八·点4 收紧。
     */
    public static boolean isRideRider(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            if (!isBatonBound(maid)) {
                return false; // 不是骑乘棒绑的 → 与本链路无关
            }
            Entity v = ridingMount(maid);
            if (v == null) {
                return false;
            }
            // v1.3.0(beta) 实测七百一十九：模组坐骑（卓越前线载具 / 冰火传说龙）
            if (MaidMountCompat.kindOf(v) != null) {
                return MaidMountCompat.denyReason(v) == null;
            }
            if (!(v instanceof Mob)) {
                return false;
            }
            // 【无鞍可骑仆从后门】她现在骑的这只若是红石巨兽一类（声明可骑但没鞍），一样算
            // "我们棍子绑上去的"——否则她骑上去之后 isOurRider 认不出她，就下不来了。
            if (isNoSaddleRideable(v)) {
                return true;
            }
            if (!(v instanceof Saddleable saddle) || !saddle.isSaddled()) {
                return false; // 现在骑的不是"已上鞍的坐骑"（船/矿车/别人的椅子一律不算）
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return false;
        }
    }

    /**
     * 【实测七百一十六·点2】她自己此刻**想去的地方**——读她自己的 {@code PathNavigation}
     * 的目标点（"她的两条腿本来打算怎么走"）。这就是：
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
        return null;
    }

    public static boolean isDriven(Entity mount) {
        try {
            if (isFurniture(mount)) {
                return false; // 实测七百一十七：家具（椅子/坐垫）不抑制闲逛、不施加任何骑乘改动
            }
            if (isBroom(mount)) {
                return false; // 实测七百一十八：扫帚另有链路，不抑制它的闲逛
            }
            EntityMaid m = riderOf(mount);
            if (m == null) {
                return false;
            }
            if (!isBatonBound(m)) {
                return false; // 实测七百一十八·点4：不是骑乘棒绑的 → 原版规则一个字不动
            }
            if (!com.maidsmart.tool.MaidScope.owned(m)) {
                return false;
            }
            if (MaidBroomKit.isBroomTask(m)) {
                return false;
            }
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
            return null;
        }
    }

    /**
     * 给载具喂"去这里"，分两个渠道：① 地面寻路（{@code GroundPathNavigation}，马 / 猪 / 骆驼等）把目标点
     * 喂进它自己的寻路，绕障 / 跳跃交给它，1:1 还原她两条腿的走位；② 非地面寻路（飞行 / 两栖 / 别的模组
     * 坐骑）改**直连操纵**（{@code MoveControl.setWantedPosition}），不重规划路径。两渠道互斥。实测七百一十六·点2。
     */
    public static void feedNavigation(Entity mount, Vec3 target, double modifier) {
        feedNavigation(mount, target, modifier, null);
    }

    /**
     * 同上，带 {@code rider}（骑在上面的女仆）。为什么要传她：卓越前线轮椅引擎的转向只读
     * {@code getFirstPassenger().getYHeadRot()}（反编译实证），需由她定头朝向。实测七百二十三。
     */
    public static void feedNavigation(Entity mount, Vec3 target, double modifier, Entity rider) {
        try {
            // v1.3.0(beta) 实测七百一十九【模组坐骑通解通法】：先问两家模组驱动
            // （卓越前线 processInput / 冰火传说 flightManager）——它们不是 Mob、没有
            // PathNavigation，下面两个渠道都表达不了，必须由各自的驱动接管。
            if (MaidMountCompat.drive(mount, target, modifier, rider)) {
                return;
            }
            if (!(mount instanceof Mob mob)) {
                return;
            }
            // 【实测七百七十】旧版的"喂导航授权令牌"（navFeedAuthorize/Release）与它配套的
            // PathNavigationRiddenGuardMixin 已随本区间的重构一起删除：模组仆从坐骑不再由我们
            // 喂走位，也就没有"我们自己在写导航"需要放行这回事了。
            if (mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation) {
                // 渠道一：只会跑的坐骑 —— 1:1 走位（喂它自己的地面寻路）
                // 【R1037】寻路失败（目标点在围栏/窄缝/刷怪笼死角，马体进不去）时，
                // `PathNavigation.moveTo` 会把旧路径清成 null；每 tick 重试 = 每 tick 清一次，
                // 表现就是"坐骑原地不动"。这里退一步用 MoveControl 直推：不建路径、不依赖
                // 绕障，至少让她朝目标压过去（贴墙时由碰撞挡住，不会穿墙）。
                // 只在她**确实有活攻击目标**时启用：平时跟随主人跨沟/跨岩浆时，寻路失败
                // 就保持原地，不能把坐骑往直线上带。
                if (!mob.getNavigation().moveTo(target.x, target.y, target.z, modifier)) {
                    if (rider instanceof EntityMaid fighting && fighting.getTarget() != null) {
                        mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, modifier);
                        logNavFallback(mob, target);
                    }
                }
            } else {
                // 渠道二：不只是会跑路的坐骑 —— 直连操纵，不依赖地面寻路
                mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, modifier);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    /** 停下载具（她下鞍 / 找不到主人时用）——两个渠道都要收手 */
    public static void stopNavigation(Entity mount) {
        try {
            // 模组坐骑：各自的驱动收手（载具清位掩码 / 龙把飞行目标设成脚下）
            MaidMountCompat.stop(mount);
            if (!(mount instanceof Mob mob)) {
                return;
            }
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    /** 寻路失败改直推的留痕（节流 5 秒/只，日志搜「模组坐骑·直达」）。 */
    private static final Map<UUID, Long> NAV_FALLBACK_AT = new ConcurrentHashMap<>();

    private static void logNavFallback(Entity mount, Vec3 target) {
        try {
            long now = System.currentTimeMillis();
            Long last = NAV_FALLBACK_AT.get(mount.getUUID());
            if (last != null && now - last < 5000L) {
                return;
            }
            NAV_FALLBACK_AT.put(mount.getUUID(), now);
            if (NAV_FALLBACK_AT.size() > 512) {
                NAV_FALLBACK_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·直达",
                    String.valueOf(mount.getType()).replace("entity.minecraft.", "")
                            + " 寻路失败 → MoveControl 直推（"
                            + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", target.x, target.y, target.z)
                            + "）");
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
        }
    }

    public static String describe(Entity e) {
        try {
            if (e instanceof EntityMaid m) {
                return com.maidsmart.tool.PromaidLog.nameOf(m);
            }
            if (e != null) {
                String mod = MaidMountCompat.describeKind(e);
                if (!mod.isEmpty()) {
                    return mod;
                }
                return String.valueOf(e.getType()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidRideKit", ignored);
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
