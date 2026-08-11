package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidWorkMealTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.129：干活不被打断①——工作餐不再打断干活。
 *
 * 根因：MaidWorkMealTask@7 优先级高于任务行为，每 ~50 tick 检查一次、好感冷却
 * （3 分钟）一到就进食——进食动画（startUsingItem）约 2~3 秒，干活的节奏被打断。
 * 且 TLM 1.5.3 的饥饿值（DATA_HUNGER）经全 jar 扫描确认是惰性数据（只有
 * EntityMaid 自身 NBT 读写，无消耗、无饿死机制）——工作餐本质是刷好感，不是
 * 生存需要。
 *
 * 修复：非战斗任务进行中直接跳过工作餐（空闲/跟随/战斗时照常吃；受伤自疗餐
 * 是独立任务不受影响）。总开关：misc.workUninterrupted。
 */
@Mixin(MaidWorkMealTask.class)
public abstract class MealTaskGatingMixin {
    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidsmart$deferMealWhileWorking(ServerLevel level, EntityMaid maid,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)) {
            cir.setReturnValue(false);
        }
    }
}
