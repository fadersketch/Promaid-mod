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
            MaidRunOne.class);

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
                if (second instanceof Behavior<?> behavior && !INFRA.contains(behavior.getClass())) {
                    setInfiniteDuration(behavior);
                }
            }
        }
        return ImmutableList.copyOf(list);
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
