package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.3.0「扫帚模式」——新模式，图标就是 TLM 那把扫帚。
 *
 * ── 定位 ──
 * 一个**远程作战模式**：骑 TLM 的扫帚实体飞起来打（{@link MaidBroomTask} 实现
 * {@link IRangedAttackTask}，因此武器匹配/换装/战斗判定的原版链路照常工作）。
 * **不响应自主切换**——与两种空袭同款（{@code AutoCombatPools} 按 UID 显式排除）：
 * 只有玩家在女仆界面手动把任务改成它、或通过指令/LLM 切进来，她才会骑扫帚。
 * 这一条是玩家（未作答的选择题）的推荐项，也是行为最可预测的一档。
 *
 * ── 为什么开火走 {@code IRangedAttackTask} ──
 * {@code EntityMaid.performRangedAttack} 的字节码是：
 * <pre>
 *   IMaidTask t = this.getTask();
 *   if (!(t instanceof IRangedAttackTask r)) return;      // ← 不是就【静默 no-op】
 *   ... r.performRangedAttack(this, target, distance);
 * </pre>
 * 也就是说实现这个接口 = 拿到"她用手上的远程武器开火"这条通道，而武器的分支处理
 * （弓/弩/御币弹幕/弩射烟花/三叉戟）**整段在 {@link MaidFlightRangedTask#fireRangedWeapon}
 * 里现成**——扫帚模式委托同一个方法即可，不抄第二份。这正是玩家说的
 * "手上武器的运作直接照搬远程空袭模式"。
 */
public class MaidBroomTask implements IRangedAttackTask {

    public static final ResourceLocation UID = MaidBroomKit.UID;

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 图标 = TLM 的扫帚（注册名取不到时给空栈——这条路径实际不会走到，TLM 必然注册了扫帚） */
    @Override
    public ItemStack getIcon() {
        try {
            net.minecraft.world.item.Item broom = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(ResourceLocation.parse("touhou_little_maid:broom"));
            if (broom != null) {
                return new ItemStack(broom);
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    /**
     * **必须与 {@link #createBrainTasks} 返回同一套行为**——她骑上扫帚之后就是**乘客**，
     * 而 TLM 选哪套大脑的判据正是 {@code isPassenger()}（字节码实证，见
     * {@code MaidUpdateActivityFromSchedule.updateActivityFromSchedule}：坐着或当乘客 →
     * 用 RIDE_IDLE / RIDE_WORK / RIDE_REST 三个 ride 活动，行为来自 {@code createRideBrainTasks}）。
     * 不实现这一个，她骑上去的**下一 tick** 行为就不在 ride 大脑里了 → 整套链路当场停摆、
     * 扫帚上挂着一个不动的女仆。两种空袭任务同样实现了它，理由一模一样。
     */
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    /**
     * 与空袭同构的索敌链，另加**唯一的执行者** {@link MaidBroomBehavior}。
     *
     * 【为什么不注册 TLM 的 {@code MaidMeleeAttack}】与飞行近战同一个理由：Brain 对同一活动内的
     * 行为是"全部 tryStart"、优先级只决定尝试顺序、并不互斥。挂上普通近战，她会在扫帚上先挥出
     * 一记近战、给目标打上 invulnerableTime，随后的远程命中被无敌帧吃掉。
     * 攻击与移动的所有权统一归 {@link MaidBroomBehavior}：它决定去哪、以及什么时候开火。
     */
    private static List<Pair<Integer, BehaviorControl<? super EntityMaid>>> buildBrain() {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StartAttacking.create(
                IRangedAttackTask::findFirstValidAttackTarget)));
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid.create()));
        list.add(Pair.of(4, new MaidBroomBehavior()));
        return list;
    }

    /** 武器位 = 远程武器（与扫帚模式的激活判据同一口径） */
    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return MaidFlightKit.isRangedWeapon(stack);
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /** 作战模式不绑工作站 */
    @Override
    public boolean workPointTask(EntityMaid maid) {
        return false;
    }

    /**
     * 索敌范围：与两种空袭**完全同款**的 50 格立方盒（水平/垂直一视同仁）——他是立体作战，
     * 敌人在斜上方二三十格时按水平十几格的默认范围早就出界。有 home/工作点限制时以限制中心
     * 为基准膨胀，免得守家中的她因为盒子变大而跨出活动范围。
     * （"能不能打"的视线过滤在 TLM 原版链路里，本方法只管"扫多大范围"，与空袭同一个说明。）
     */
    private static final double BROOM_SEARCH_RADIUS = 50.0;

    @Override
    public net.minecraft.world.phys.AABB searchDimension(EntityMaid maid) {
        if (maid.hasRestriction()) {
            return new net.minecraft.world.phys.AABB(maid.getRestrictCenter())
                    .inflate(BROOM_SEARCH_RADIUS, BROOM_SEARCH_RADIUS, BROOM_SEARCH_RADIUS);
        }
        return maid.getBoundingBox().inflate(BROOM_SEARCH_RADIUS, BROOM_SEARCH_RADIUS, BROOM_SEARCH_RADIUS);
    }

    /**
     * 开火（由 {@code EntityMaid.performRangedAttack} 回调）——**直接委托远程空袭那一份实现**：
     * 枪械排除 / 三叉戟 / 御币弹幕 / 弩射烟花 / 弩装配 / 弓造箭，整套口径只有一处。
     */
    @Override
    public void performRangedAttack(EntityMaid shooter, LivingEntity target, float power) {
        MaidFlightRangedTask.fireRangedWeapon(shooter, target, power);
    }

    @Override
    public String getMaidActionSummary() {
        return "扫帚模式";
    }
}
