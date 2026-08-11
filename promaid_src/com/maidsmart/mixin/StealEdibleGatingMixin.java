package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleMoveBlockTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleUseTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.129：干活不被打断②——工作中禁止"偷吃"（跑去拆/吃甜浆果丛等可食用方块）。
 *
 * 偷吃任务（MoveBlockTask 找目标 + UseTask 吃掉）在饿/看到可食方块时会把女仆
 * 从工位拽走。非战斗任务进行中直接禁止（饥饿由 TLM 1.5.3 的惰性机制决定——
 * 不吃不会死）。总开关：misc.workUninterrupted。
 */
@Mixin({MaidStealEdibleMoveBlockTask.class, MaidStealEdibleUseTask.class})
public abstract class StealEdibleGatingMixin {
    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noStealWhileWorking(ServerLevel level, EntityMaid maid,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)) {
            cir.setReturnValue(false);
        }
    }
}
