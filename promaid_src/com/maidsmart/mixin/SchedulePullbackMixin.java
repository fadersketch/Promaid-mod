package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUpdateActivityFromSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.5.129：干活不被打断④——切班时不再被拽回工位/回家。
 *
 * 根因：MaidUpdateActivityFromSchedule 在活动切换（白班→午休→夜班）时，home 模式
 * 下会 restrictTo（收紧活动半径）并 setWalkAndLookTarget 把女仆拉回 SchedulePos——
 * 正在远处挖矿/干农活的女仆被硬拽走。
 *
 * 修复：非战斗任务进行中跳过这两个动作（活动照常切换、任务行为照常停止/恢复，
 * 只是不拽人、不收半径；回家休息由玩家决定，女仆留在工位附近）。总开关：
 * misc.workUninterrupted。
 */
@Mixin(MaidUpdateActivityFromSchedule.class)
public abstract class SchedulePullbackMixin {
    @Redirect(method = "start",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/SchedulePos;restrictTo(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V"))
    private void maidsmart$noRestrictWhileWorking(SchedulePos pos, EntityMaid maid) {
        if (!(com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid))) {
            pos.restrictTo(maid);
        }
    }

    @Redirect(method = "start",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorUtils;m_22617_(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/BlockPos;FI)V"))
    private void maidsmart$noPullbackWalkWhileWorking(LivingEntity entity, BlockPos pos,
                                                      float speed, int closeEnough) {
        boolean working = entity instanceof EntityMaid maid
                && com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid);
        if (!working) {
            BehaviorUtils.m_22617_(entity, pos, speed, closeEnough);
        }
    }
}
