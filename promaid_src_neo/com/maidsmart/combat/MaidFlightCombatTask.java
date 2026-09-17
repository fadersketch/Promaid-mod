package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.2.0（1.21.1）：飞行作战——新模式，图标就是鞘翅。
 *
 * 【模式定位】本质上是一个新的作战模式（实现 IAttackTask，因此武器匹配/换装/战斗
 * 判定等原版链路照常工作）。但【不响应自主切换】——见 AutoCombatSwitch.buildPools
 * 的显式排除（按 UID 跳过），确保主人被打时她不会被自动切进来/切出去。
 *
 * 【激活条件】鞘翅 + 重锤 + 烟花火箭三件齐备（背包/主副手/护甲位任一），否则
 * 本模式不激活，行为表现与普通攻击模式完全一致（地面近战）——这是用户明确要求：
 * "缺少任意一个都会使进入此模式失败，导致切换到此模式女仆行为表现就跟正常攻击
 * 模式一致"。
 *
 * 【行为】激活后照搬 JerotesWarehouse 里"类玩家单位使用鞘翅时的长矛机制"
 * （JerotesPlayerEntity 字节码实证：目标升空/自身坠落/已滑翔时置滑翔标志位第 7 位，
 * 并按 20 tick 冷却用挂载型 FireworkRocketEntity 助推）——武器由长矛换成重锤，
 * 并保留重锤的下落加成与附魔结算。
 */
public class MaidFlightCombatTask implements IAttackTask {

    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:flight_combat");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 图标 = 鞘翅（用户要求："图标就是鞘翅的图标"） */
    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.ELYTRA);
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    /**
     * 与 TaskAttack 同构的索敌链（StartAttacking / StopAttackingIfTargetInvalid），
     * 但【不注册 TLM 的 MaidMeleeAttack】：Brain 对同一 activity 内的行为是"全部
     * tryStart"并发运行（优先级只决定启动尝试顺序，不互斥），如果同时挂着普通近战，
     * 她会在落点先打出一记普通近战、给目标打上 invulnerableTime，随后重锤下落加成
     * 被无敌帧吸收（附魔/下落全部白算）。
     *
     * 所以攻击权统一由 MaidFlightCombatBehavior 独占：
     * - 三件齐备 → 滑翔 / 烟花推进 / 收翅俯冲重锤；
     * - 三件缺一（模式未激活）→ 行为内的 groundMelee 用普通攻击力挥砍 + 走近，
     *   表现与普通攻击模式一致（与用户要求一致）。
     * 走近目标由本行为的滑翔/地面逻辑自行处理（不依赖 SetWalkTargetFromAttackTarget，
     * 否则爬升/俯冲时会被寻路拽回地面）。
     */
    private static List<Pair<Integer, BehaviorControl<? super EntityMaid>>> buildBrain() {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StartAttacking.create(
                IAttackTask::findFirstValidAttackTarget)));
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid.create()));
        // 飞行作战：唯一的攻击与移动执行者（激活=空中连招；未激活=地面近战退路）
        list.add(Pair.of(4, new MaidFlightCombatBehavior(false)));
        return list;
    }

    /** 工作点任务 = false（作战模式不绑工作站） */
    @Override
    public boolean workPointTask(EntityMaid maid) {
        return false;
    }

    /**
     * v1.2.0：本模式的武器位 = **任意近战武器**（原为"重锤"）。
     *
     * 必须覆写：IAttackTask.isWeapon 默认【恒 false】（javap 实证 iconst_0/ireturn）。
     * 不覆写会有两个后果：
     * ① 自主切换认为"这个战斗任务没有可用武器"→ tryEngageMaid 不 return 2，
     *    继续走 pickCombatTask 把她切去原版 attack——等于【被自主切换切出】，
     *    与"不响应自主切换"的设计矛盾（玩家手动指定的飞行作战被顶掉）；
     * ② MaidToolAutoEquip 对模组命名空间任务用 isWeapon 匹配武器，恒 false 会让
     *    换装逻辑完全不动（本模式换装由 MaidFlightKit 自己负责，正好不打架）。
     * 判据复用自动装备的同一口径（镐/弓/弩/御币排除），保证两边一致。
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
     * （AABB 三轴独立膨胀，各轴都是 ±50，即边长 100 的立方体）。**水平/垂直一视同仁**。
     *
     * 【隔墙不出手 —— 由 TLM 原版机制保证，不是本方法负责】
     * 本方法只决定"**扫多大范围**"（`MaidNearestLivingEntitySensor` 用它做 AABB 扫描，
     * 结果写进 `NEAREST_VISIBLE_LIVING_ENTITIES`）。而"能不能打"由 TLM 原版的
     * `IAttackTask.findFirstValidAttackTarget` 决定，它调用
     * `NearestVisibleLivingEntities.findClosest(predicate)`——**那个类的可见性判定自带视线过滤**
     * （构造器把 `Sensor.isEntityAttackable` 作为可见性谓词，字节码实证；它一路走到
     * `TargetingConditions.test`，其 `ignoreLineOfSight` 字段默认 false → `Sensing.hasLineOfSight`）。
     * 所以放大扫描范围**不会**让女仆隔墙出手：墙后的怪能进候选列表，但会在可见性那一关被滤掉。
     * 这与"和 TLM 原版一样，有隔墙阻挡不出手"的要求完全一致——我们没改这条链路的任何一环。
     *
     * 【活动范围限制分支照旧】有 home/工作点限制时以限制中心为基准膨胀（与 TLM 同款），
     * 免得守家中的空袭女仆因为盒子变大而跨出活动范围。
     */
    private static final double FLIGHT_SEARCH_RADIUS = 50.0;

    @Override
    public net.minecraft.world.phys.AABB searchDimension(EntityMaid maid) {
        if (maid.hasRestriction()) {
            return new net.minecraft.world.phys.AABB(maid.getRestrictCenter())
                    .inflate(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
        }
        return maid.getBoundingBox().inflate(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
    }

    @Override
    public String getMaidActionSummary() {
        return "近战空袭";
    }
}
