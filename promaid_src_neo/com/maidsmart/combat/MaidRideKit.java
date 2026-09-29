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

    /* ==================== 家具类坐骑黑名单 ==================== */

    /**
     * v1.3.0(beta) 实测七百一十七【家具类坐骑黑名单后门】。
     *
     * <p>玩家原话：「我们需要给原版 tlm 的椅子这些道具开一个后门，他们虽然是家具类物品，
     * 但是从某种意义上，他们也算坐骑，需要开一个额外的黑名单，保证女仆坐在这个上面的时候，
     * 我们所做的所有骑乘更改全都不生效。」
     *
     * <p>TLM 的椅子（{@code EntityChair}）/坐垫（{@code EntitySit}）都让女仆变成"乘客"
     * （{@code isPassenger()} = true），于是它们天然落进本链路的几个"乘客"判据里：指挥棒把她
     * 当成"骑在一只 Saddleable 上"、{@link #isDriven} 会去抑制载具闲逛、传送链路会想着
     * "连坐骑一起搬"。可它们根本不是坐骑，是家具——玩家明确把她安放在那儿的。默认名单 =
     * {@code touhou_little_maid:chair} / {@code touhou_little_maid:sit}；别的模组的可坐家具
     * 只要往配置里填一行实体 id 即可。
     *
     * <p>判据是**实体类型注册名**（不写死类引用，1.20.1 / 1.21.1 两树共用同一份 id）。
     */
    public static boolean isFurniture(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            java.util.List<? extends String> list;
            try {
                list = com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FURNITURE_BLACKLIST.get();
            } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 扫帚：不是坐骑 ==================== */

    /**
     * v1.3.0(beta) 实测七百一十八【点4：指挥棒不许选中坐垫/扫帚，且只有棍子绑的才算骑乘】。
     *
     * <p>玩家原话：「只有拿骑乘棒让女仆进入骑乘状态才走我们的骑乘链路，且骑乘棒无法选中坐垫和扫帚」。
     *
     * <p>扫帚（TLM {@code EntityBroom}）让女仆也变成"乘客"（{@code isPassenger()} = true），
     * 于是它天然落进本链路那几个"乘客"判据里。可扫帚有**自己的一整条飞行链路**
     * （{@code MaidBroomKit}/{@code MaidBroomDrive}），把它当坐骑会打架：指挥棒点她会把
     * 她从扫帚上拽下来（{@code releaseMaid} → {@code stopRiding}）、{@link #isDriven} 会去
     * 抑制扫帚自己的行为、传送链路会想"连扫帚一起搬"。所以这里一刀切掉。
     *
     * <p>判据用**类型**（编译期盯着）而不是注册名——与 {@code MaidBroomKit.isBroomItem}
     * 同一种写法（TLM 改 id 不会让我们静默失效）。
     */
    public static boolean isBroom(Entity e) {
        try {
            return e instanceof com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 「只有棍子绑的才算」 ==================== */

    /**
     * v1.3.0(beta) 实测七百一十八【点4：收紧进入条件】。
     *
     * <p>玩家原话：「只有拿骑乘棒让女仆进入骑乘状态才走我们的骑乘链路」。
     *
     * <p>判据 = 她自己身上带着 {@link #TAG_RIDE_MOUNT}（绑定成功那一刻写、解绑/自然脱落时清除，
     * 随存档持久化）。所以**不是**我们把她放上坐骑的场合——原版/别的模组让她成为乘客
     * （坐船、坐矿车、被别的模组拽上坐骑、TLM 椅子/坐垫、扫帚）——我们的驱动、闲逛抑制、
     * 连坐骑传送**一个字节都不生效**，完全交还原版规则。
     *
     * <p>【为什么判据是"她自己身上的痕迹"而不是查链路表】服务端重启/区块重载后链路表
     * 还没重建的那一拍，传送入口也要能认出她——与 {@code MaidBroomKit.isBroomAirborne}
     * 同口径（那个也不查表，只看"任务 + 真的骑着扫帚"）。
     */
    public static boolean isBatonBound(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            String tag = maid.getPersistentData().getString(TAG_RIDE_MOUNT);
            return tag != null && !tag.isEmpty();
        } catch (Throwable ignored) {
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
            if (v instanceof EntityMaid) {
                return null;
            }
            if (isFurniture(v)) {
                return null; // 实测七百一十七：家具（椅子/坐垫）不算坐骑——本链路一律不碰
            }
            return v;
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
     *
     * <p>【v1.3.0(beta) 实测七百一十八·点4 收紧】判据加上"必须是我们用骑乘棒绑的"
     * （{@link #isBatonBound}）——原版/别的模组让她坐船、坐矿车、被拽上别人的坐骑，
     * 一律不再走"连坐骑一起搬"，完全交还原版乘客规则。玩家原话：
     * 「只有拿骑乘棒让女仆进入骑乘状态才走我们的骑乘链路」。
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
            if (!(v instanceof Saddleable saddle) || !saddle.isSaddled()) {
                return false; // 现在骑的不是"已上鞍的坐骑"（船/矿车/别人的椅子一律不算）
            }
            return true;
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
        feedNavigation(mount, target, modifier, null);
    }

    /**
     * 同上，带 {@code rider}（= 骑在上面的那只女仆）。
     *
     * <p>【实测七百二十三】为什么要把她传下去：卓越前线的**轮椅（WHEELCHAIR）**引擎
     * 的转向只读 {@code getFirstPassenger().getYHeadRot()}（反编译实证），不读左右位——
     * 所以驱动层必须拿到"她"，才能把目标方位写成她的头朝向。玩家原话
     * 「为什么骑马就不会出现这种情况呢？」的答案也在这条链上：马走的是下面那个
     * {@code GroundPathNavigation} 渠道，压根不经过这套引擎。
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
            // 模组坐骑：各自的驱动收手（载具清位掩码 / 龙把飞行目标设成脚下）
            MaidMountCompat.stop(mount);
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
                String mod = MaidMountCompat.describeKind(e);
                if (!mod.isEmpty()) {
                    return mod;
                }
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
