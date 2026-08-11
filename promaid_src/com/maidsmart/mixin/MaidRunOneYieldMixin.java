package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidRunOne;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.129：TLM 原生任务通用呆滞修复②——MaidRunOne 随机散步覆盖任务目标。
 *
 * 根因：WORK 活动的 MaidRunOne@20（含 RandomStroll 随机散步）与任务行为并发运行。
 * 原生任务（farm/挤奶等）通过 WALK_TARGET/TARGET_POS 记忆驱动移动，RandomStroll
 * 启动时会设置自己的 WALK_TARGET 把任务目标盖掉 → 女仆"走一步停一步/偏离目标"
 * （与当初挖矿"一步一停"同根因；我们的 5 个任务已用 enableLookAndRandomWalk=false
 * 关掉 RunOne，这里补上原生任务）。
 *
 * 修复：任务正走向目标（大脑已有 WALK_TARGET 或 TARGET_POS）时 RunOne 让位
 * （tryStart 直接返回 false）；任务空闲/冷却间隙才允许随机散步（符合设计）。
 * 总开关：misc.nativeTaskSmooth。
 */
@Mixin(MaidRunOne.class)
public abstract class MaidRunOneYieldMixin {
    @Inject(method = "tryStart", at = @At("HEAD"), cancellable = true)
    private void maidsmart$yieldWhenTaskTargets(ServerLevel level, EntityMaid maid, long gameTime,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_NATIVE_TASK_SMOOTH.get()
                && (maid.m_6274_().m_21874_(MemoryModuleType.f_26370_)
                || maid.m_6274_().m_21874_(InitEntities.TARGET_POS.get()))) {
            cir.setReturnValue(false);
        }
    }
}
