package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPanicTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.129：干活不被打断③——小伤不再恐慌逃跑。
 *
 * 根因：MaidPanicTask（CORE 优先级 1）只要"被攻击过/附近有敌对"就切 PANIC 活动
 * 逃跑，农活/挖矿/建造女仆被打一下就丢下工作满场跑。
 *
 * 修复：非战斗任务进行中、血量 ≥ 30% 时不恐慌（继续干活）；血量 < 30% 仍正常
 * 恐慌，且我们的自保行为（SelfPreservationBehavior，core 250）会在低血+威胁时
 * 接管逃跑/回血——不会致死。总开关：misc.workUninterrupted。
 */
@Mixin(MaidPanicTask.class)
public abstract class PanicGatingMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noPanicWhileWorking(ServerLevel level, EntityMaid maid, long gameTime,
                                               CallbackInfo ci) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)
                && maid.m_21223_() >= maid.m_21233_() * 0.3f) {
            ci.cancel();
        }
    }
}
