package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * 骑乘链路的能力探测与公共小工具（v1.3.0(beta)·原版生物骑乘）。
 *
 * <p>本类的唯一职责：回答两个问题——「这只生物能不能给她骑」与「骑上之后该走多快」。
 * 绑定/驱动/下坐骑在 {@link RideBindManager}，物品是 {@link RideBatonItem}。
 *
 * ── 【为什么是"套僵尸的骑乘代码"，而不是自己写驱动】──
 * 原版僵尸骑鸡走的就是 {@code startRiding(force)} + 让**载具自己**的 AI 带着走
 * （{@code Zombie.finalizeSpawn} 里那一记 {@code startRiding(chicken)}，javap 实证）。
 * 我们原样借用：
 * <ul>
 *   <li><b>上鞍</b>：{@code maid.startRiding(mount, true)}——force = true 跳过原版
 *       {@code canRide}/{@code canAddPassenger} 两道门（javap 实证 {@code m_7998_} 的
 *       {@code iload_2 ifne} 直接跳门）。她成为乘客后 TLM 自己把大脑切到 RIDE_IDLE/
 *       RIDE_WORK/RIDE_REST（{@code MaidUpdateActivityFromSchedule} 字节码实证，判据是
 *       {@code isMaidInSittingPose() || isPassenger()}）。</li>
 *   <li><b>驱动</b>：原版 {@code LivingEntity.aiStep} 只在
 *       {@code getControllingPassenger() instanceof Player} 时才走 {@code travelRidden}，
 *       否则走普通 {@code travel()}（javap 实证）。而马/猪/炽足兽/骆驼的
 *       {@code getControllingPassenger()} 只认**第一乘客是 Player**（javap 实证
 *       {@code instanceof Player} + 猪还要胡萝卜钓竿）→ 女仆当乘客时它**恒为 null**，
 *       于是载具走的是**普通 travel**、导航照常把它推着走。我们只要把目的地喂进
 *       载具自己的 {@code PathNavigation} 即可——载具的寻路会自己处理上下坡/绕障/
 *       跳跃/动画，这就是"降级偷懒"。</li>
 *   <li><b>它自己的闲逛会抢方向</b>：{@code RandomStrollGoal.canUse()} 只在
 *       {@code mob.hasControllingPassenger()} 为真时返回 false（javap 实证）——而女仆当
 *       乘客时那个判据是 <b>false</b>（见上：getControllingPassenger 对女仆恒 null），
 *       所以载具**自己的随机闲逛仍然会跑**、跟我们喂的导航抢方向。这一条由
 *       {@code RandomStrollGoalRiddenMixin} 补上：把原版"玩家骑就不闲逛"的规则
 *       原样扩展到"本模组的女仆骑也不闲逛"（口径与 {@link #isDriven} 同一处）。</li>
 * </ul>
 *
 * ── 【"可骑乘"的判据：能力探测，不写死实体 id】──
 * {@code instanceof Saddleable && isSaddled()}：原版马/驴/骡/骷髅马/僵尸马（AbstractHorse）、
 * 猪、炽足兽、骆驼全部实现 {@link Saddleable}（javap 全量扫描实证，见 changelog），
 * 且都要求"已上鞍"才允许被骑（原版语义）。模组生物只要也实现这个接口、且已上鞍，
 * **零适配**即可骑。未上鞍的（原版要玩家先装鞍）一律拒绝并给提示，不做"替她装鞍"。
 *
 * ── 【速度：载具上限与女仆上限取最大】──
 * 玩家原话："速度上是坐骑的最大速度与女仆的最大速度之间取最大值。"见
 * {@link #speedModifierFor}：导航的 speed 参数是**倍率**（{@code MoveControl} 内部
 * {@code speedModifier × MOVEMENT_SPEED}，javap 实证），所以换算成
 * {@code max(载具速度, 女仆速度) / 载具速度}，最终位移速度就是那个最大值。
 */
public final class MaidRideKit {

    /** persistentData：她当前骑的这只坐骑（UUID 字符串）——跨存档恢复用 */
    public static final String TAG_RIDE_MOUNT = "maid_smart_ride_mount";

    private MaidRideKit() {
    }

    /** 总开关（配置 combat.ride，默认开）。关掉 = 整条链路不激活、退回原版 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_ENABLE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 跟随停下的距离（格） */
    public static double followDist() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_FOLLOW_DIST.get();
        } catch (Throwable ignored) {
            return 5.0;
        }
    }

    /** 速度总倍率（乘在"取最大值"之上） */
    public static double speedScale() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SPEED_SCALE.get();
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /* ==================== 能力探测 ==================== */

    /**
     * 这只生物此刻**能不能**被女仆骑：已上鞍 + 是个可承载乘客的 Mob。
     *
     * <p>为什么还要 {@code instanceof Mob}：{@link Saddleable} 是"能装鞍"的接口，
     * 我们还需要它有 {@code getNavigation()} 可喂（见类注释的驱动口径）——原版里
     * 这四种鞍类生物都是 {@code Mob}/{@code PathfinderMob}。
     */
    public static boolean isRideableMount(Entity e, EntityMaid maid) {
        try {
            if (!(e instanceof Mob) || e == maid || !e.m_6084_()) {
                return false;
            }
            if (e instanceof EntityMaid) {
                return false; // 女仆骑女仆不在本链路范围内
            }
            if (!(e instanceof Saddleable saddle) || !saddle.m_6254_()) {
                return false; // 没上鞍：原版语义不许骑（我们不替她装鞍）
            }
            return e.m_20197_().isEmpty() || e.m_20197_().get(0) == maid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 为什么骑不上（给玩家看的拒绝理由；能骑时返回 null） */
    public static String denyReason(Entity e, EntityMaid maid) {
        try {
            if (!(e instanceof Mob) || e == maid || e instanceof EntityMaid) {
                return "这个不能当坐骑～";
            }
            if (!e.m_6084_()) {
                return "它已经不在了……";
            }
            if (!(e instanceof Saddleable)) {
                return "它不是能上鞍的坐骑～";
            }
            if (!((Saddleable) e).m_6254_()) {
                return "先给它装上鞍再绑给我吧～";
            }
            if (!e.m_20197_().isEmpty() && e.m_20197_().get(0) != maid) {
                return "它背上已经有人了～";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ==================== 速度 ==================== */

    /** 一只生物的 MOVEMENT_SPEED 属性值（拿不到 → -1） */
    public static double moveSpeedOf(Entity e) {
        try {
            if (e instanceof LivingEntity le) {
                return le.m_21133_(Attributes.f_22279_);
            }
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    /**
     * 换算成喂给载具 {@code PathNavigation.moveTo} 的**倍率**：最终速度 =
     * {@code max(载具速度, 女仆速度) × speedScale}。
     *
     * <p>为什么"取最大"要写成倍率：导航的 speed 参数不是绝对速度，载具的
     * {@code MoveControl} 内部会乘上它自己的 MOVEMENT_SPEED（javap 实证）——所以
     * {@code 目标 / 载具速度} 才是我们要传的倍率。载具速度拿不到（极罕见）时退回 1.0
     * （= 原样走它自己的速度，绝不因为探测失败把她卡住）。
     */
    public static double speedModifierFor(Entity mount, EntityMaid maid) {
        try {
            double m = moveSpeedOf(mount);
            double d = moveSpeedOf(maid);
            if (m <= 0.0) {
                return 1.0;
            }
            double target = Math.max(m, d) * speedScale();
            double mod = target / m;
            // 夹在合理区间：太快会让她在复杂地形上"甩出去"，太慢等于不走
            return Math.max(0.3, Math.min(mod, 4.0));
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /* ==================== 骑乘关系 ==================== */

    /** 她此刻骑着的那只坐骑（没骑返回 null） */
    public static Entity ridingMount(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            Entity v = maid.m_20202_();
            return v instanceof EntityMaid ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 她此刻是不是"骑着一只非女仆的生物"（= 本链路意义上的骑乘状态） */
    public static boolean isRidingMount(EntityMaid maid) {
        return ridingMount(maid) != null;
    }

    /** 这只坐骑背上是不是正驮着本模组的女仆（{@link #isDriven} 的前半） */
    public static EntityMaid riderOf(Entity mount) {
        try {
            for (Entity p : mount.m_20197_()) {
                if (p instanceof EntityMaid m) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 这只生物此刻是不是**正被本模组的女仆驾驶**：乘客里有本模组（有主）的女仆、
     * 且她的任务不是扫帚模式（扫帚另有链路，不能让两条抢）。
     *
     * <p>这个判据是"一处口径"：闲逛抑制（mixin）、驱动（{@link RideBindManager}）、
     * 以及"别去动原版/别人的坐骑"三处都问它。
     */
    public static boolean isDriven(Entity mount) {
        try {
            EntityMaid m = riderOf(mount);
            if (m == null) {
                return false;
            }
            if (!com.maidsmart.tool.MaidScope.owned(m)) {
                return false; // 无主女仆不干预（整合包对野生女仆的规则一律不动）
            }
            if (MaidBroomKit.isBroomTask(m)) {
                return false; // 扫帚模式另有链路
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她骑的这只坐骑要不要"跟着主人走"（主人活着、同维度、离得够远） */
    public static LivingEntity ownerToFollow(EntityMaid maid) {
        try {
            LivingEntity owner = maid.m_269323_();
            if (owner == null || !owner.m_6084_() || owner.m_5833_()) {
                return null;
            }
            if (owner.m_9236_() != maid.m_9236_()) {
                return null;
            }
            return owner;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 给载具喂"去这里"（水平面；y 用主人脚底，载具自己的寻路会处理地面） */
    public static void feedNavigation(Entity mount, Vec3 target, double modifier) {
        try {
            if (!(mount instanceof Mob mob)) {
                return;
            }
            PathNavigation nav = mob.m_21573_();
            nav.m_26519_(target.f_82479_, target.f_82480_, target.f_82481_, modifier);
        } catch (Throwable ignored) {
        }
    }

    /** 停下载具的寻路（她下鞍 / 找不到主人时用） */
    public static void stopNavigation(Entity mount) {
        try {
            if (mount instanceof Mob mob) {
                mob.m_21573_().m_26573_();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 日志用的短名（女仆名 / 实体类型名） */
    public static String describe(Entity e) {
        try {
            if (e instanceof EntityMaid m) {
                return com.maidsmart.tool.PromaidLog.nameOf(m);
            }
            if (e != null) {
                return String.valueOf(e.m_6095_()).replace("entity.minecraft.", "");
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 数字格式化（日志统一小数点，避免各语言区域差异） */
    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** UUID → 字符串（持久化用） */
    public static String tag(UUID id) {
        return id == null ? "" : id.toString();
    }
}
