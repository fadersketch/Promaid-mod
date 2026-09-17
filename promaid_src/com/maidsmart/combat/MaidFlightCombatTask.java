package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.2.0（1.20.1）：飞行作战任务——图标 = 鞘翅，**不响应自主切换**。
 *
 * 【激活口径（武器无关）】鞘翅 + **任意近战武器** + 烟花火箭，三件齐备才激活；
 * 缺任意一件 → 行为与普通攻击模式一致（地面近战）。1.20.1 原版没有重锤，
 * 所以武器位放宽为任意近战武器（判据复用自动装备同一口径），收翅下落命中时
 * 按原版规则结算【暴击 ×1.5】。
 *
 * 【本任务的 brain 只有"索敌 + 飞行行为"三件】
 * 刻意不注册 TLM 的 MaidMeleeAttack 与 SetWalkTargetFromAttackTarget：
 * - 攻击（滑翔接触 / 地面）由 MaidFlightCombatBehavior 独占，避免普通近战先上一记、
 *   给目标上无敌帧把暴击/伤害吃掉；
 * - 走位同样由行为自己负责（滑翔靠视线、地面靠 navigation）。
 *
 * 【不响应自主切换】靠三处保证：本任务 isWeapon 声明"近战武器即武器"，让
 * AutoCombatSwitch 认为"已在战斗且武器可用"而尊重现状；另外在 AutoCombatSwitch 的
 * buildPools（入战选任务）与 retuneCombatTactics（战中换战术）里按 UID 显式排除。
 */
public class MaidFlightCombatTask implements IAttackTask {

    public static final ResourceLocation UID =
            new ResourceLocation("maid_smart", "flight_combat");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 图标 = 鞘翅（与玩家穿戴鞘翅一致的模式标识） */
    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.f_42741_);
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain 会往里追加行为
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(5, StartAttacking.m_257741_((EntityMaid m) -> true,
                IAttackTask::findFirstValidAttackTarget)));
        list.add(Pair.of(5, StopAttackingIfTargetInvalid.m_257990_(target -> false)));
        list.add(Pair.of(4, new MaidFlightCombatBehavior(false)));
        return list;
    }

    /**
     * 本模式的武器位 = 任意近战武器（与自动装备同一判据：镐/弓/弩/御币排除）。
     * 必须覆写：IAttackTask.isWeapon 默认恒 false，会让自主切换认为"这个战斗任务
     * 没有可用武器"从而把她切去原版 attack。
     */
    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return MaidFlightKit.isMeleeWeapon(stack);
    }

    /** 关掉随机散步：与其余战斗任务一致，避免 RunOne 抢夺移动目标 */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /** 与其余 5 个任务一致：不参与 TLM 的工作点逻辑 */
    @Override
    public boolean workPointTask(EntityMaid maid) {
        return false;
    }

    /**
     * v1.2.0 实测五百一十三【空袭索敌改为"以自身为圆心、半径 50 的立方盒"】。
     *
     * 【演进】实测五百零八 先把**垂直**半径从 TLM 默认的 4 格提到 50 格（水平仍沿用
     * `searchRadius`，默认 16），修掉了"凋灵飞高就锁不上"。但实测下来**水平 16 格仍然太小**
     * （用户反馈："索敌范围还是太小"）——空袭是立体作战，敌人在斜上方 20~40 格时
     * 水平距离早就出界了。
     *
     * 【现在】三个轴统一取 {@link #FLIGHT_SEARCH_RADIUS} = 50 格：
     * `AABB.inflate(50, 50, 50)` 等价于"以自身碰撞箱为中心、半径 50 的立方盒"
     * （AABB 是三轴独立膨胀，各轴都是 ±50，即一个边长 100 的立方体）。
     * **水平/垂直一视同仁**，不再区分。
     *
     * 【隔墙不出手 —— 这一点由 TLM 原版机制保证，不是本方法负责】
     * 本方法只决定"**扫多大范围**"（`MaidNearestLivingEntitySensor` 用它做 AABB 扫描，
     * 结果写进 `NEAREST_VISIBLE_LIVING_ENTITIES`）。而"能不能打"由 TLM 原版的
     * `IAttackTask.findFirstValidAttackTarget` 决定，它调用
     * `NearestVisibleLivingEntities.findClosest(predicate)`——**那个类的可见性判定自带视线过滤**
     * （构造器把 `Sensor.isEntityAttackable` 作为可见性谓词，字节码实证；
     * 它一路走到 `TargetingConditions.test`，其 `ignoreLineOfSight` 字段默认 false
     * → 最终查 `Sensing.hasLineOfSight`）。
     * 所以放大扫描范围**不会**让女仆隔墙出手：墙后的怪能进候选列表，但会在可见性那一关被滤掉。
     * 这与"和 TLM 原版一样，有隔墙阻挡不出手"的要求完全一致——我们没改这条链路的任何一环。
     *
     * 【活动范围限制分支照旧】有 home/工作点限制时以限制中心为基准膨胀（与 TLM 同款），
     * 免得守家中的空袭女仆因为盒子变大而跨出活动范围。
     */
    private static final double FLIGHT_SEARCH_RADIUS = 50.0;

    @Override
    public net.minecraft.world.phys.AABB searchDimension(EntityMaid maid) {
        if (maid.m_21536_()) {
            return new net.minecraft.world.phys.AABB(maid.m_21534_())
                    .m_82377_(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
        }
        return maid.m_20191_().m_82377_(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
    }

    @Override
    public String getMaidActionSummary() {
        return "近战空袭";
    }
}
