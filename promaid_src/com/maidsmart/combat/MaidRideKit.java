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

    /* ==================== 家具类坐骑黑名单 ==================== */

    /**
     * v1.3.0(beta) 实测七百一十七【家具类坐骑黑名单后门】。
     *
     * <p>玩家原话：「我们需要给原版 tlm 的椅子这些道具开一个后门，他们虽然是家具类物品，
     * 但是从某种意义上，他们也算坐骑，需要开一个额外的黑名单，保证女仆坐在这个上面的时候，
     * 我们所做的所有骑乘更改全都不生效。」
     *
     * <p>TLM 的椅子（{@code EntityChair}）/坐垫（{@code EntitySit}）都让女仆变成"乘客"
     * （{@code isPassenger()} = true，javap 实证 {@code AbstractEntityFromItem} 那条链），
     * 于是它们天然落进本链路的几个"乘客"判据里：指挥棒把她当成"骑在一只 Saddleable 上"、
     * {@link #isDriven} 会去抑制载具闲逛、传送链路会想着"连坐骑一起搬"。可它们根本不是坐骑，
     * 是家具——玩家明确把她安放在那儿的。默认名单 = {@code touhou_little_maid:chair} /
     * {@code touhou_little_maid:sit}；别的模组的可坐家具只要往配置里填一行实体 id 即可。
     *
     * <p>判据是**实体类型注册名**（不写死类引用，1.20.1 / 1.21.1 两树的 TLM 各自类名不同也能共用）。
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
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
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
            Entity v = maid.m_20202_();
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
            String tag = maid.getPersistentData().m_128461_(TAG_RIDE_MOUNT);
            return tag != null && !tag.isEmpty();
        } catch (Throwable ignored) {
            return false;
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
            if (e == null || e == maid || !e.m_6084_()) {
                return false;
            }
            if (e instanceof EntityMaid) {
                return false; // 女仆骑女仆不在本链路范围内
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
            if (!(e instanceof Saddleable saddle) || !saddle.m_6254_()) {
                return false; // 没上鞍：原版语义不许骑（我们不替她装鞍）
            }
            return e.m_20197_().isEmpty() || e.m_20197_().get(0) == maid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 【无鞍可骑仆从后门】 ==================== */

    /** 无鞍可骑的总开关（配置 combat.ride.noSaddlePets，默认开）。 */
    public static boolean noSaddlePetsEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_NO_SADDLE_PETS.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 这只仆从是不是"声明了可骑、但压根没有鞍"的那一类——本模组的**后门**判据。
     *
     * <h2>玩家原话</h2>
     * 「像诡厄巫法的可骑仆从（红石巨兽），以及某些整合包魔改的套用代码的仆从（下界合金巨兽），
     *  这些都是没有办法让女仆骑乘的（不能装鞍），能不能走个后门让女仆可以骑乘那些？」
     *
     * <h2>根因（反编译实证，本地 goety-3.1.5.1 / goety-2.5.58.4 / goety_cataclysm 三个 jar）</h2>
     * 这些仆从实现的是原版 {@code net.minecraft.world.entity.PlayerRideable}——一个
     * **一个方法都没有的空标记接口**——或者诡厄自己的
     * {@code com.Polarice3.Goety.api.entities.IAutoRideable}；**都不是** {@link Saddleable}。
     * 原版红石巨兽的骑乘入口是 {@code mobInteract} 里那一记 {@code doPlayerRide(player)}
     * （反编译实证）：主人空手右击一下就直接 {@code startRiding}，全程没有鞍。而本模组原来的
     * 判据只认"已上鞍的 Saddleable"，于是整类被挡在门外，回一句"它不是能上鞍的坐骑～"。
     *
     * <h2>判据（不写死任何实体 id，两树共用）</h2>
     * <ol>
     *   <li>是 {@link Mob}（要能喂它自己的 {@code PathNavigation}，与通用档同口径）；</li>
     *   <li>声明了 {@code PlayerRideable}（编译期判据）或诡厄的 {@code IAutoRideable}
     *       （反射探测，编译期不依赖诡厄）；</li>
     *   <li><b>不能装鞍</b>—— {@code !(e instanceof Saddleable)}。</li>
     * </ol>
     * 第 ③ 条是**必须留着**的：否则"没上鞍的原版马/骆驼"也会落进本档，把
     * {@code denyReason} 里"先给它装上鞍再绑给我吧～"那道闸整个绕过（变成一个凭空可骑的 bug）。
     * 所以本档只放行"从来没有鞍这一环"的仆从，原版兽的装鞍语义一字不改。
     *
     * <p>【为什么不是"凡是 PlayerRideable 全放行"】扫描本地全部模组 jar 后发现：光看接口会把
     * 突变生物的蜘蛛猪、ALEX 洞穴的独角兽这类**别的模组自己的可骑生物**也一起放行；它们与我们的
     * 驱动链路没有适配过。玩家要的是诡厄这一类（同一套 {@code IAutoRideable} 代码被整合包仆从
     * 沿用），所以窄判据 = {@code PlayerRideable} **或** {@code IAutoRideable}，二者取并集正好
     * 覆盖红石巨兽与下界合金巨兽仆从，又不会把无关模组的坐骑卷进来。
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
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ==================== 【实测七百七十·模组仆从坐骑：单独一个区间】 ==================== */

    /**
     * 【实测七百七十】"模组仆从坐骑"单独一个区间——与卓越前线载具同级的通解档。
     *
     * <h2>玩家定档（原话）</h2>
     * 「女仆坐上去只会赋予仆从相应的速度，其余的行动逻辑全都换成仆从自己的。（如果开启 home 模式，
     * 那么坐骑还是会停下来，这一点是通用的）」「如果女仆受到了伤害，会将伤害转移给身下坐着的仆从。」
     * 并明确要求：「只对 mod 类仆从类生效，就跟卓越前线一样是单独开了个区间。不影响其他区间以及
     * 原版生物或者通用的骑乘逻辑。」
     *
     * <h2>区间判据 = {@link #isNoSaddleRideable}</h2>
     * 诡厄巫法 / 诡厄灾变的"无鞍可骑仆从"（红石巨兽、下界合金巨兽仆从这一族）。它们**自带一整套
     * 战斗 AI**：{@code targetSelector} 里有 {@code SummonTargetGoal}/{@code ServantHurtByTargetGoal}
     * 自主锁敌，{@code goalSelector} 里有巡逻 / 接近 / 全部技能（反编译 goety-3.1.5.1 与
     * goety_cataclysm 实证）。女仆骑上它之后，本模组**只做两件事**：
     * <ol>
     *   <li>{@link #applyRiddenSpeed}：把她的移动速度赋给坐骑（"只会赋予仆从相应的速度"）；</li>
     *   <li>{@link #reopenRiddenCombatGoals}：把它自己的 goal 控制位开回来，让它的 AI 在"被骑"
     *       状态下照常运转（否则它的 goal 会被原版驾驶规则整个掐停，见那个方法）。</li>
     * </ol>
     * 除此之外**一个字都不写**——不再喂走位、不再写目标、不再拦它的寻路、不再强制它出招。
     * 唯一例外是 home 模式：那时**不**给它开控制位 = 它的 goal 全停 = 坐骑停住（玩家要求保留的
     * 通用例外）。**原版马 / 骆驼、卓越前线载具、冰火传说龙、别的模组生物、通用骑乘逻辑一律不受
     * 本区间影响**（判据只在 {@link #isNoSaddleRideable} 上）。1.20.1 与 1.21.1 两树逐字同源。
     */
    public static boolean servantAutoEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SERVANT_AUTO.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 【实测七百七十】女仆受伤转给坐骑的总开关（配置 {@code combat.ride.servantTransfer}，默认开）。 */
    public static boolean servantTransferEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_SERVANT_TRANSFER.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 【实测七百七十·只给速度】 ==================== */

    /** 我们给坐骑挂的"女仆速度"修改器的名称（1.20.1 用名称派生 UUID；1.21.1 用同一名称派生的注册名）。 */
    private static final String SERVANT_SPEED_NAME = "maid_smart:ride_servant_speed";

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
                    le.m_21051_(Attributes.f_22279_); // f_22279_ = MOVEMENT_SPEED
            if (inst == null) {
                return;
            }
            double hers = maid.m_21133_(Attributes.f_22279_) * speedScale();
            if (!(hers > 0.0)) {
                return;
            }
            UUID id = java.util.UUID.nameUUIDFromBytes(SERVANT_SPEED_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (inst.m_22111_(id) != null) { // m_22111_ = getModifier(UUID)
                inst.m_22120_(id);           // m_22120_ = removeModifier(UUID)
            }
            double base = inst.m_22135_();   // m_22135_ = getValue
            inst.m_22118_(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    id, SERVANT_SPEED_NAME, hers - base,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        } catch (Throwable ignored) {
        }
    }

    /** 摘掉 {@link #applyRiddenSpeed} 挂的修改器（解绑 / 链路失效时）。 */
    public static void clearRiddenSpeed(Entity mount) {
        try {
            if (mount instanceof LivingEntity le) {
                net.minecraft.world.entity.ai.attributes.AttributeInstance inst =
                        le.m_21051_(Attributes.f_22279_);
                if (inst != null) {
                    UUID id = java.util.UUID.nameUUIDFromBytes(SERVANT_SPEED_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    inst.m_22120_(id);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 【实测七百七十·把它自己的 AI 开回来】 ==================== */

    /**
     * 【实测七百七十】把"被女仆骑着"误关掉的 goal 控制位开回来，让模组仆从坐骑用它自己的 AI。
     *
     * <h2>根因（javap 两版逐字实证）</h2>
     * 原版 {@code Mob.m_8022_()}（= updateControlFlags，{@code f_19797_ % 5 == 0} 时由
     * {@code Mob.m_8119_()} 调用）第一句就是
     * {@code flag = !(this.getControllingPassenger() instanceof Mob);}，随后把 {@code goalSelector}
     * 的 {@code MOVE}/{@code JUMP}/{@code LOOK} 设成 {@code flag}——「**驾驶者是个生物** → 坐骑
     * 自己的 goal 全部停摆」。而 {@code getControllingPassenger()} 认的正是"第一乘客是 {@code Mob}"
     * ——**女仆就是个 Mob**。诡厄 {@code Summoned.m_8022_()} 又在这之上把 {@code TARGET} 一并关掉。
     * {@code GoalSelector} 于是把所有带这些控制位的 goal 直接 {@code stop()}：诡厄灾变的下界合金
     * 巨兽仆从（反编译实证）的接近（{@code InternalSummonMoveGoal}）与全部技能
     * （{@code SMASH}/{@code EARTHQUAKE}/{@code MagmaShoot}/{@code FlareShoot}/{@code ShoulderCheck}）
     * 恰好全带这些位 ⇒ 它一步走不了、一招放不出。
     *
     * <h2>本区间口径（与 768 的差别）</h2>
     * 768 只在"它此刻有目标"时开、且我们还要替它写目标、替它带路；770 起**整段让给它自己的 AI**：
     * 由 {@code MobRiddenControlFlagsMixin} 在 {@code Mob.m_8119_()} 里 {@code m_8022_()} 的
     * **调用之后立刻**回调本方法（调用点注入，天然兼容诡厄的重写版本），判据全部命中就把
     * MOVE / LOOK / JUMP / TARGET 一起开回来（含 TARGET，因为诡厄把它也关了）：
     * <ol>
     *   <li>总开关开着（{@link #servantAutoEnabled}）；</li>
     *   <li>是 {@link #isNoSaddleRideable} 的模组仆从（只治这一类）；</li>
     *   <li>第一乘客是女仆、且是我们棍子绑上去的（{@link #isDriven}）；</li>
     *   <li>她**不在 home 模式**——home 模式不接管，控制位保持关闭 = 它自己的 goal 全停 = 坐骑停住
     *       （玩家明确要求保留的通用例外）。</li>
     * </ol>
     * 开回来之后它用自己的 {@code SummonTargetGoal} 自主锁敌、用自己的接近 goal 追、用自己的技能
     * 出招——完全"换成仆从自己的"。**重开 TARGET 不会让它攻击背上的女仆**：反编译
     * {@code MobUtil.isOwnedTargetable(仆从, 女仆)} 对"非敌对、未被记仇"的目标返回 false，且
     * {@code SummonTargetGoal.canUse()} 显式排除 {@code getTrueOwner()}。
     *
     * <p>不满足判据 → 一个控制位都不动，原版（含诡厄）行为逐字节不变。1.20.1 与 1.21.1 两树同源。
     */
    public static void reopenRiddenCombatGoals(Mob mount) {
        try {
            if (!servantAutoEnabled()) {
                return;
            }
            if (mount == null || !isNoSaddleRideable(mount)) {
                return;
            }
            if (!(mount.m_146895_() instanceof EntityMaid maid)) { // m_146895_ = getFirstPassenger
                return;
            }
            if (!isDriven(mount)) {
                return;
            }
            if (maid.isHomeModeEnable()) {
                return; // home 模式：不接管 = 它自己的 goal 保持关闭 = 坐骑停住（通用例外）
            }
            mount.f_21345_.m_25360_(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE, true); // f_21345_ = goalSelector
            mount.f_21345_.m_25360_(net.minecraft.world.entity.ai.goal.Goal.Flag.LOOK, true);
            mount.f_21345_.m_25360_(net.minecraft.world.entity.ai.goal.Goal.Flag.JUMP, true);
            mount.f_21345_.m_25360_(net.minecraft.world.entity.ai.goal.Goal.Flag.TARGET, true);
        } catch (Throwable ignored) {
        }
    }

    /** 为什么骑不上（给玩家看的拒绝理由；能骑时返回 null） */
    public static String denyReason(Entity e, EntityMaid maid) {
        try {
            if (e == null) {
                return "这个不能当坐骑～";
            }
            // 家具/扫帚的判据放在最前：它们是 LivingEntity 但**不是 Mob**，若先走下面那句
            // "不是能上鞍的坐骑"就永远说不出它们的专属理由（实测七百一十八·点4）。
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
            if (e instanceof EntityMaid || e == maid) {
                return "这个不能当坐骑～";
            }
            if (!(e instanceof Mob)) {
                return "这个不能当坐骑～";
            }
            if (!e.m_6084_()) {
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

    /* ==================== 【实测七百四十九·点1】「有没有模式在指挥她走位」 ==================== */

    /**
     * v1.3.0(beta) 实测七百四十九【点1：默认档"跟着主人走"、有模式才走模式逻辑】。
     *
     * <p>玩家原话：「要求是平时马头朝着主人移动靠近了就停止。其他时候就动用该模式下的运动逻辑。
     * （因为骑的马仍然是陆地载具，跟女仆自己在地上走区别不大，所以直接采用女仆的运动逻辑。）」
     *
     * <p>判据就是「她此刻的**任务**是不是"没有任务"」——TLM 的空闲任务 uid 固定为
     * {@code touhou_little_maid:idle}（其它所有任务：矿/木/农/建造/战斗/钓鱼/耕地…都有自己的
     * 运动逻辑）。空任务 / 任务还没就绪 → 返回 {@code false} = "没有模式在指挥"。
     *
     * <p>为什么用任务而不是"她的导航有没有目标"：她**是乘客**时，TLM 的跟随
     * （{@code MaidFollowOwnerTask}）与我们自己的若干 core 行为照样在她自己的
     * {@code PathNavigation} 上写走位点——那正是 716 之后坐骑"乱窜"的来源。要区分"这是模式
     * 在指挥"还是"这只是跟随/闲逛在写"，只能看任务。
     *
     * <p>【1.20.1 的 SRG 名】{@code m_135827_ = getNamespace}、{@code m_135815_ = getPath}。
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
            return !("touhou_little_maid".equals(uid.m_135827_()) && "idle".equals(uid.m_135815_()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 【实测七百四十九·点3】「别叠罗汉」的公共标准 ==================== */

    /**
     * v1.3.0(beta) 实测七百四十九【点3：坐骑也要防叠罗汉，与扫帚**同一套标准**】。
     *
     * <p>玩家原话：「扫帚模式下已经拥有了防叠罗汉的机制，但是坐骑方面还没有。这个应该是一个
     * 统一的标准，不管是扫帚还是坐骑，应该都需要防叠罗汉。」
     *
     * <p>扫帚那套（实测六百九十一）由两部分组成：**相位错开**（每只女仆的盘旋起点按 UUID 错开
     * 一整圈）+ **邻近互斥**（目标点被别的"女仆骑着的扫帚"顶开）。坐骑这边没有"盘旋"这回事，
     * 所以只需要后半截——把"去这里"的目标点推离同伴。为了真的是"统一的标准"而不是"抄一份
     * 数字过去"，四个参数与那段算式**都放在本类**，扫帚与坐骑各自只负责"凑齐同伴位置"这一步。
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
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /**
     * 「别叠罗汉」的公共算式：把"去这里"（{@code aim}）推离一批同伴位置（{@code peers}）。
     *
     * <p>【只推水平】要的是水平方向错开；竖直那一份交给各自的起飞/爬升相位，互斥插一脚只会抖动。
     * <p>【有封顶】互斥只做修正，绝不把"去哪"整条盖掉（真正"去哪"由调用方决定）。
     * <p>【退化情形】同伴与目标点几乎重合（d&lt;0.05）时，用 {@link #ridePhase} 当"往哪边让"的
     * 方向——必须是确定的，不能每次算出来不一样。
     *
     * @param aim    原始目标点
     * @param phase  "往哪边让"的兜底方向（弧度，{@link #ridePhase}）
     * @param peers  同伴（"别的女仆正骑着的坐骑/扫帚"）的位置
     * @param combat 这一拍是不是在接敌（决定用哪一档间距与封顶）
     * @return 修正后的目标点；不需要修正 / 出任何异常一律原样返回 {@code aim}
     */
    public static Vec3 separateAim(Vec3 aim, double phase, java.util.List<Vec3> peers, boolean combat) {
        return separateAim(aim, phase, peers,
                combat ? SEP_R_COMBAT : SEP_R,
                combat ? SEP_MAX_COMBAT : SEP_MAX);
    }

    /**
     * 【实测七百五十八】同一个算式的**可调间距档**——给**卓越前线载具**（坦克/装甲车这类大车）用。
     *
     * <p>为什么必须能调：{@link #SEP_R}（2 格）/ {@link #SEP_R_COMBAT}（4 格）是 749 按**扫帚与
     * 人形**定的间距；对一辆车体 4 格宽的坦克，2 格间距等于"让位让了个寂寞"——车与车照样叠在
     * 一起（玩家实测：「多个女仆乘坐多个坦克，仍然会出现严重的叠罗汉情况」）。载具那边把间距
     * 放大到"车体全宽 + 1.5 格缝"、封顶按比例抬起来，再走本档；**算式仍然只有这一处**。
     *
     * @param sepR   要求的水平间距（格）；&le;0 视为"不修正"
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
                // 1.20.1 的 Vec3 字段是 SRG 名：f_82479_ = x、f_82480_ = y、f_82481_ = z
                double dx = aim.f_82479_ - p.f_82479_;
                double dz = aim.f_82481_ - p.f_82481_;
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
            return new Vec3(aim.f_82479_ + ox * k, aim.f_82480_, aim.f_82481_ + oz * k);
        } catch (Throwable ignored) {
            return aim;
        }
    }

    /* ==================== 骑乘关系 ==================== */

    /** 她此刻骑着的那只坐骑（没骑返回 null；坐在黑名单家具上也算"没骑坐骑"） */
    public static Entity ridingMount(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            Entity v = maid.m_20202_();
            if (v instanceof EntityMaid) {
                return null;
            }
            if (isFurniture(v)) {
                return null; // 实测七百一十七：家具（椅子/坐垫）不算坐骑——本链路一律不碰
            }
            if (isBroom(v)) {
                return null; // 实测七百一十八：扫帚不是坐骑（她自己那条飞行链路管）
            }
            // v1.3.0(beta) 实测七百一十九：模组坐骑（卓越前线载具 / 冰火传说龙）也算"骑乘状态"
            return v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 她此刻是不是"骑着一只非女仆的生物"（= 本链路意义上的骑乘状态） */
    public static boolean isRidingMount(EntityMaid maid) {
        return ridingMount(maid) != null;
    }

    /**
     * 【实测七百一十六·点4】她此刻是不是"被棍子绑上坐骑、需要连坐骑一起搬运"的骑乘女仆。
     *
     * <p>判据是**自洽的**（不查链路表）：正骑着一只非女仆的 {@link Saddleable} 已上鞍生物，
     * 且身上带着 {@link #TAG_RIDE_MOUNT}（棍子绑定成功时写的那道痕迹，随存档持久化）。
     * 这样服务端重启/区块重载后、链路表还没重建时，传送入口照样认得出她——与扫帚那边
     * {@code MaidBroomKit.isBroomAirborne} 的口径一致（那个也不查表，只看"任务+真的骑着扫帚"）。
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
            // 【无鞍可骑仆从后门】她现在骑的这只若是红石巨兽一类（声明可骑但没鞍），一样算
            // "我们棍子绑上去的"——否则她骑上去之后 isOurRider 认不出她，就下不来了。
            if (isNoSaddleRideable(v)) {
                return true;
            }
            if (!(v instanceof Saddleable saddle) || !saddle.m_6254_()) {
                return false; // 现在骑的不是"已上鞍的坐骑"（船/矿车/别人的椅子一律不算）
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百一十六·点2】她自己此刻**想去的地方**——读的是她自己的 {@code PathNavigation}
     * 的目标点，也就是"她的两条腿本来打算怎么走"。
     *
     * <p>这就是玩家说的"1:1 还原女仆原有的走路逻辑"：她骑上坐骑以后，走位意图**并没有消失**
     * ——我们的单兵战术（core 优先级 230）与 TLM 的跟随（core 3）都照常在她自己的导航上
     * 写目的地，只是那些位移被"她是乘客、位置由载具决定"这一条吃掉，所以她原地不动、
     * 看起来"近战/远程走位全废了"。把这个目标点原样转达给坐骑的导航，坐骑就按她原本的
     * 走位逻辑跑——马这种只会跑的坐骑由此 1:1 复现她两条腿的走位；会飞/会跳的坐骑
     * 属于"不只是会跑路"的另一种渠道，不在这一档里。
     *
     * <p>路径已走完（{@code isDone}）或没有目标 → 返回 null（= 她没有前进意图，交给下一优先级）。
     */
    public static Vec3 ownNavigationTarget(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            PathNavigation nav = maid.m_21573_();
            if (nav == null || nav.m_26571_()) {
                return null;
            }
            net.minecraft.core.BlockPos p = nav.m_26567_();
            if (p == null) {
                return null;
            }
            return new Vec3(p.m_123341_() + 0.5, p.m_123342_(), p.m_123343_() + 0.5);
        } catch (Throwable ignored) {
            return null;
        }
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
     * 她是被**骑乘棒**绑上去的（{@link #isBatonBound}）、且她的任务不是扫帚模式
     * （扫帚另有链路，不能让两条抢）。
     *
     * <p>这个判据是"一处口径"：闲逛抑制（mixin）、驱动（{@link RideBindManager}）、
     * 以及"别去动原版/别人的坐骑"三处都问它。v1.3.0(beta) 实测七百一十八·点4 起，
     * 判据补上"必须是我们绑的"——原版/别的模组让女仆当乘客的场合不再被误当驾驶。
     */
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

    /**
     * 给载具喂"去这里"（【实测七百一十六·点2】分两个渠道）。
     *
     * <p>玩家原话："至少对于像马这种只会跑步的应该要 1:1 还原女仆原有的走路逻辑，其他模组的
     * 生物同理。如果他不只是会跑路，那么再另开一个渠道。"
     * <ul>
     *   <li><b>渠道一（{@link GroundPathNavigation}）＝只会跑的坐骑</b>（马/驴/骡/骆驼/猪等）：
     *       把目标点喂进它自己的寻路——它按自己的地面寻路跑过去，上下坡/绕障/跳跃全交给它，
     *       这就是"1:1 还原她两条腿的走位"（走位点由她的导航给出，见
     *       {@link #ownNavigationTarget}）。</li>
     *   <li><b>渠道二（非地面寻路：飞行/两栖/别的模组坐骑）＝不只是会跑路的</b>：
     *       地面寻路表达不了她的意图，改**直连操纵**（{@link MoveControl#m_6849_}）——不重新
     *       规划路径，直接朝目标点给操纵意图。这是"另开的一个渠道"，与渠道一互斥。</li>
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
            // 【实测七百七十】旧版的"喂导航授权令牌"（navFeedAuthorize/Release）与它配套的
            // PathNavigationRiddenGuardMixin 已随本区间的重构一起删除：模组仆从坐骑不再由我们
            // 喂走位，也就没有"我们自己在写导航"需要放行这回事了。
            if (mob.m_21573_() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation) {
                // 渠道一：只会跑的坐骑 —— 1:1 走位（喂它自己的地面寻路）
                mob.m_21573_().m_26519_(target.f_82479_, target.f_82480_, target.f_82481_, modifier);
            } else {
                // 渠道二：不只是会跑路的坐骑 —— 直连操纵，不依赖地面寻路
                mob.m_21566_().m_6849_(target.f_82479_, target.f_82480_, target.f_82481_, modifier);
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
            mob.m_21573_().m_26573_();
            // 渠道二：把操纵目标设成它自己脚下，操纵层立即失去推力（否则会保持上一拍的方向）
            mob.m_21566_().m_6849_(mob.m_20185_(), mob.m_20186_(), mob.m_20189_(), 0.0);
        } catch (Throwable ignored) {
        }
    }

    /** 日志用的短名（女仆名 / 实体类型名 / 模组坐骑的细分名） */
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
