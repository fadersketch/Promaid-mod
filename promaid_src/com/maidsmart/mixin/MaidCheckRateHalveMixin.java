package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.129：TLM 原生任务通用呆滞修复④——MaidCheckRateTask 限频减半。
 *
 * 根因：原生任务（挤奶/农场等）的"检查节流"是 maxCheckRate + random(0..maxCheckRate)
 * 即 1~2 倍间隔（挤奶 40→40~80 tick = 2~4 秒才检查下一次）。完成一个单位工作后
 * 女仆站着发呆等下一次检查。
 *
 * 修复：间隔整体减半（0.5~1 倍），干活衔接更紧凑。总开关：misc.nativeTaskSmooth。
 */
@Mixin(MaidCheckRateTask.class)
public abstract class MaidCheckRateHalveMixin {
    @Shadow
    private int maxCheckRate;

    @Shadow
    private int nextCheckTickCount;

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidsmart$halveCheckRate(ServerLevel level, EntityMaid maid,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!com.maidsmart.config.MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get()) {
            return;
        }
        if (this.nextCheckTickCount > 0) {
            this.nextCheckTickCount--;
            cir.setReturnValue(false);
        } else {
            int half = Math.max(1, this.maxCheckRate / 2);
            this.nextCheckTickCount = half + maid.m_217043_().m_188503_(half);
            cir.setReturnValue(true);
        }
    }
}
