package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidBrain;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidBegTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidRunOne;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleMoveBlockTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleUseTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUpdateActivityFromSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask;
import com.mojang.datafixers.util.Pair;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.Set;
import net.minecraft.world.entity.ai.behavior.Behavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.5.129：TLM 原生任务通用呆滞修复①——行为 60 tick 单轮重启。
 *
 * 根因：原版 Behavior(Map) 单参构造默认 maxDuration=60 tick，TLM 全部原生任务
 * （farm/挤奶/钓鱼/剪毛/蜂蜜等）的行为都走这个构造 → 每 3 秒强制 doStop 重启，
 * 而 doStop 常重新挂起 MaidCheckRateTask 的限频计时 → "干一下活 → 发呆 → 再干"。
 *
 * 修复：在 MaidBrain 把任务行为注册进 WORK/RIDE_WORK 活动的瞬间（ImmutableList
 * .copyOf 处 @Redirect），把除 TLM 基建行为（切班/讨食/吃饭/偷吃/随机散步——
 * 这些有自己的生命周期设计）外的任务行为时长改为无限（Integer.MAX_VALUE）。
 * 注：f_22525_/f_22526_ 是 Behavior 的 final 字段，用反射写入（一次性开销，
 * 每只女仆 brain 初始化时执行一次）；我们的 5 个任务本就无限时长，无影响。
 * 总开关：misc.nativeTaskSmooth。
 */
@Mixin(MaidBrain.class)
public abstract class NativeTaskSmoothMixin {
    /** TLM 基建行为（不参与时长修补——它们靠 60 tick 周期做轮换/节流） */
    private static final Set<Class<?>> INFRA = Set.of(
            MaidUpdateActivityFromSchedule.class,
            MaidBegTask.class,
            MaidWorkMealTask.class,
            MaidStealEdibleMoveBlockTask.class,
            MaidStealEdibleUseTask.class,
            MaidRunOne.class,
            // v1.1.0 实测三百一十九（用户："农场模式下如果女仆是 home 模式就会待在
            // 原地一动不动……放了一天那女仆也待在原地"）：MaidFarmMoveTask 必须保持
            // 原版 60 tick 周期重启——searchForDestination（设置 TARGET_POS）只在
            // start 时调用一次，改无限时长后行为永不 doStop → 永不重新搜索目标 →
            // 处理完第一个目标后 TARGET_POS 不再更新 → MaidFarmPlantTask 永远等不到
            // 新目标 → 农场停摆（"原地站死"根因）。farm 移动任务靠周期重启驱动。
            com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFarmMoveTask.class,
            // v1.1.0 实测三百二十八（用户："农场功能仍然是一按到 home 就完全停了下
            // 来"）：MaidFarmPlantTask 同构漏网——javap 实证 IFarmTask.createBrainTasks
            // 注册 [move(优先级5), plant(优先级6)]，plant 构造是单参 Map（maxDuration
            // =60），start 处理 TARGET_POS 目标格后清目标、无 tick 覆盖——它和 move
            // 一样靠 60 tick 周期 doStop 重启来响应 move 重搜出的新目标。v319 只豁免
            // 了 move，plant 被改无限时长后：处理完第一个目标 → 行为永远空转 → 新
            // TARGET_POS 出现也不重启 → 农场只处理一个目标就停摆（"一按 home 就停"
            // 的时序：切 home 瞬间活动切换打断行为，重启后 move 找目标、plant 处理
            // 一个目标，之后永不再动）。与 move 同款豁免。
            com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFarmPlantTask.class);

    private static final java.lang.reflect.Field MIN_DURATION_FIELD = field("f_22525_");
    private static final java.lang.reflect.Field MAX_DURATION_FIELD = field("f_22526_");

    private static java.lang.reflect.Field field(String name) {
        try {
            java.lang.reflect.Field f = Behavior.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Redirect(method = "registerWorkGoals",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableList;copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;"))
    private static ImmutableList<?> maidsmart$patchWorkBehaviors(Collection<?> list) {
        return patch(list);
    }

    @Redirect(method = "registerRideWorkGoals",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableList;copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;"))
    private static ImmutableList<?> maidsmart$patchRideWorkBehaviors(Collection<?> list) {
        return patch(list);
    }

    private static ImmutableList<?> patch(Collection<?> list) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get()) {
            for (Object o : list) {
                if (!(o instanceof Pair<?, ?> pair)) {
                    continue;
                }
                Object second = pair.getSecond();
                if (second instanceof Behavior<?> behavior && !isInfra(behavior)) {
                    setInfiniteDuration(behavior);
                }
            }
        }
        return ImmutableList.copyOf(list);
    }

    /** v1.1.0 实测三百二十二（用户："农场模式下如果女仆是 home 模式就会待在原地
     *  一动不动……放了一天那女仆也待在原地"）：INFRA 判定改为【父类兼容】——
     *  旧版 Set.contains(behavior.getClass()) 精确匹配，而甘蔗/西瓜等特殊农场
     *  任务用 MaidFarmSurroundingMoveTask（extends MaidFarmMoveTask，javap 实证），
     *  子类实例不命中 → 时长照样被改无限 → searchForDestination 只在 start 调用
     *  一次 → 处理完目标后永不重新搜索 → 农场停摆（"原地站死"根因）。isAssignableFrom
     *  让父类条目覆盖全部子类。 */
    private static boolean isInfra(Behavior<?> behavior) {
        Class<?> cls = behavior.getClass();
        for (Class<?> infra : INFRA) {
            if (infra.isAssignableFrom(cls)) {
                return true;
            }
        }
        return false;
    }

    /** 反射写入（final 字段，setAccessible 后允许）——失败静默（保持原 60 tick） */
    private static void setInfiniteDuration(Behavior<?> behavior) {
        try {
            if (MIN_DURATION_FIELD != null) {
                MIN_DURATION_FIELD.setInt(behavior, Integer.MAX_VALUE);
            }
            if (MAX_DURATION_FIELD != null) {
                MAX_DURATION_FIELD.setInt(behavior, Integer.MAX_VALUE);
            }
        } catch (Exception ignored) {
        }
    }
}
