package com.maidsmart.mixin;

import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * v1.5.129：TLM 原生任务通用呆滞修复③——MoveToTargetSink 的 150~250 tick"刹车"。
 *
 * 根因：原版 MoveToTargetSink() 默认构造 = (minDuration 150, maxDuration 250)——
 * 走路满 7.5~12.5 秒就强制 doStop（清 WALK_TARGET + 停导航），原生任务重新寻路
 * 时出现停顿；导航卡死还会 random(40)（0~2 秒）冷却。
 *
 * 修复：时长 150→600、250→1200（30~60 秒才"刹车"一次，走路基本连续）。
 * 我们的 5 个任务已改直接导航（不用 sink），不受影响。总开关：misc.nativeTaskSmooth。
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkDurationMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 150))
    private static int maidsmart$sinkMinDuration(int value) {
        return com.maidsmart.config.MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get() ? 600 : value;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 250))
    private static int maidsmart$sinkMaxDuration(int value) {
        return com.maidsmart.config.MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get() ? 1200 : value;
    }
}
