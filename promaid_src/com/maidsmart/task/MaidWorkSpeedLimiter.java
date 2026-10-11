package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * <p>【
 * <p>【先纠正一个前提——查证结论】TLM 1.5.3 **并没有**「好感度 → 移动速度」这条加成。
 * 把官方 jar / 整合包里的 {@code [国创]} 分支 jar / 服务器 jar 逐类扫过：能同时认出
 * 「和女仆有关」且「碰 MOVEMENT_SPEED」的类只有一个 {@code EntityFairy}（妖精，写的是妖精
 * 自己的 BABY 加速，不是女仆）；{@code FavorabilityManager} 只把 ATTACK_DAMAGE 与 MAX_HEALTH
 * 调 {@code m_22100_}（setBaseValue），好感度档位表里（LEVEL_0..3）**没有任何 SPEED 项**；
 * TLM 自己的游戏内手册也写着 「Higher favorability will increase the maid's health and attack」
 * （只提血和攻击）。
 * <p>【那「高好感就乱窜」是怎么来的】真实链路是**间接**的：
 * <ol>
 *   <li>好感度档位决定**饰品栏格数**（{@code BaubleContainer}：0~1 级 10 格 / 2 级 20 格 /
 *       3 级 30 格）——好感度高的女仆能同时挂更多带移速/加速的饰品；</li>
 *   <li>本模组 {@code misc.strollSpeed}（空闲散步倍率）默认乘在女仆 **0.7** 的基础移速上， 服务器配置若还停在旧的 0.7 就是约 8 格/秒（比玩家跑步 5.61 还快），观感就是「刹不住」； <li>本模组还刻意去掉了原版走路的刹车窗口（MoveToTargetSink 时长延长 / NativeTaskSmooth 把任务行为时长拉到 Integer.MAX_VALUE / 跟随收紧每 tick 重断言）——速度看着「不停」。</li> </ol> <p>【所以这一项做什么】玩家要的语义是「干活时按 0 好感档的移速走」。既然 TLM 本身没有这条 加成，本项就**照玩家的口径把它显式实现出来**：干活（非战斗、非空闲/跟随）时，把女仆的 MOVEMENT_SPEED 有效值**钳到她的基础值**（= 0.7，原版 LivingEntity 默认， TLM 不写它、等于 0 好感/无饰品的裸值），战斗任务与空闲/跟随/骑乘一律不碰。 <p>【怎么钳——不写 base，只挂一个自纠正的加法修改器】照搬本模组 {@code MaidRideKit.applyRiddenSpeed} 的做法：每 tick 取一次 {@code m_22115_}（getBaseValue，她自己属性表里的裸值）+ 我们那个 id 的 现有修改器金额，算出「去掉我们这一笔之后她会是多少」= {@code current - ourAmount}，再令 我们的金额 = {@code base - that}，于是**有效值恒等于 base**。好处： <ul> <li>不写 {@code m_22100_}（setBaseValue，不污染她的存档属性，transient 修改器不落 NBT）；</li> <li>同一 id 覆盖 = 幂等，别的来源（饰品/药水）增减我们自动补偿，不需要枚举它们；</li> <li>战斗/空闲切回来时把我们的金额摘掉，她的真实移速立刻恢复原样，不留残影。</li> </ul> <p>【判定口径】「干活」用 {@link MaidWorkTags#isNonCombatWork}（非攻击任务且非 idle/follow） ——与「干活不被打断」系列同一个闸门，挖矿/伐木/建造/烹饪/酿造/农活/刷怪笼插火把全部命中， 战斗类（含第三方 IAttackTask）、idle、follow 全部放行。另外坐着/当乘客/骑乘时不干预 （她本来就不靠自己的腿走，这时压速只会干扰坐具/坐骑逻辑）。 <p>【开关】{@code misc.workSpeedLevel0}，默认开。关掉 = 完全不挂修改器，与旧版逐字节一致。 <p>【1.20.1 与 1.21.1 的差异】只有 id 与 SRG 名：1.20.1 的 {@code AttributeModifier} 用 {@code (UUID, name, amount, op)} 构造，{@code getModifier/removeModifier} 收 UUID； 1.21.1 改成了 {@code ResourceLocation}，故这里用同一名称派生的 UUID（与 {@code MaidRideKit.SERVANT_SPEED_NAME} 同款手法），行为完全一致。
 */
public final class MaidWorkSpeedLimiter {

    /** 我们挂在女仆 MOVEMENT_SPEED 上的修改器名（1.20.1 用它的 UUID 派生做标识）。 */
    private static final String WORK_SPEED_NAME = "maid_smart:work_level0_speed";

    private MaidWorkSpeedLimiter() {
    }

    /**
     * 每 tick 调一次（由 {@code MaidWorkSpeedHandler} 从 TLM 的 {@code MaidTickEvent} 转进来）。
     * 只服务端有意义（属性写在服务端；客户端同步由原版属性同步包负责）。
     */
    public static void tick(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        try {
            if (maid.m_9236_().m_5776_()) {
                return; // 属性改动只在服务端；客户端那份由原版属性同步包刷，不重算
            }
            if (!com.maidsmart.tool.MaidScope.owned(maid)) {
                return; // 无主女仆不干预（与跟随收紧/移动抑制同一条规矩：整合包对野生女仆的规则一律不动）
            }
            AttributeInstance inst = maid.m_21051_(Attributes.f_22279_);
            if (inst == null) {
                return;
            }
            if (shouldLimit(maid)) {
                applyLimit(inst);
            } else {
                clearLimit(inst);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkSpeedLimiter", ignored);
        }
    }

    /** 是否该把她的移速压回 0 好感档（干活中、且不是坐着/乘客/骑乘）。 */
    private static boolean shouldLimit(EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_WORK_SPEED_LEVEL0.get()) {
            return false;
        }
        if (maid.m_20159_() || maid.isMaidInSittingPose()) {
            return false; // 乘客 / 坐姿：她不靠自己的腿走
        }
        return MaidWorkTags.isNonCombatWork(maid);
    }

    /**
     * 把有效移速钳到 base：令我们的加法修改器金额 = base −（去掉我们之后的值）。
     * 幂等；别的来源怎么变都自动补偿。
     */
    private static void applyLimit(AttributeInstance inst) {
        double base = inst.m_22115_(); // 她自己属性表里的裸值（0.7）——TLM 不写它，等于 0 好感裸值
        if (!(base > 0.0)) {
            return; // 拿不到合理基础值：不干预
        }
        java.util.UUID id = id();
        AttributeModifier ours = inst.m_22111_(id);
        double withoutOurs = inst.m_22135_() - (ours == null ? 0.0 : ours.m_22218_());
        double amount = base - withoutOurs;
        if (ours != null && Math.abs(ours.m_22218_() - amount) < 1.0E-7) {
            return; // 已经是目标值：不动（避免每 tick 无谓地摘/挂）
        }
        inst.m_22120_(id);
        if (Math.abs(amount) < 1.0E-7) {
            return; // 本来就等于 base：不必挂一笔 0
        }
        inst.m_22118_(new AttributeModifier(
                id, WORK_SPEED_NAME, amount, AttributeModifier.Operation.ADDITION));
    }

    /** 摘掉我们的修改器（战斗/空闲/关开关/坐着时）——她的真实移速立刻恢复。 */
    private static void clearLimit(AttributeInstance inst) {
        java.util.UUID id = id();
        if (inst.m_22111_(id) != null) {
            inst.m_22120_(id);
        }
    }

    private static java.util.UUID id() {
        return java.util.UUID.nameUUIDFromBytes(WORK_SPEED_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
