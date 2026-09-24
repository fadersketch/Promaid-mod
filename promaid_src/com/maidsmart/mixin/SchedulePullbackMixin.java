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
    /**
     * v1.3.0(beta) 实测六百六十四：豁免口径收到一处——非战斗的活儿 + **扫帚模式**。
     *
     * <p>玩家原话：「开着 home 的扫帚模式，女仆不应该响应排班表的传送」。扫帚模式要飞来飞去，
     * 切班时被 restrictTo 收紧半径 + setWalkAndLookTarget 拽回工位一次，她就得重新起飞一次。
     * 与 {@code SchedulePosTickMixin} 同一口径（那边管每 2 秒的周期拉回，这边管切班那一拍）。
     */
@Mixin(MaidUpdateActivityFromSchedule.class)
public abstract class SchedulePullbackMixin {

    private static boolean maidsmart$exempt(EntityMaid maid) {
        try {
            return (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                    && MaidWorkTags.isNonCombatWork(maid))
                    || com.maidsmart.combat.MaidBroomKit.isBroomTask(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Redirect(method = "start",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/SchedulePos;restrictTo(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V"))
    private void maidsmart$noRestrictWhileWorking(SchedulePos pos, EntityMaid maid) {
        if (!maidsmart$exempt(maid)) {
            pos.restrictTo(maid);
        }
    }

    @Redirect(method = "start",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorUtils;m_22617_(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/BlockPos;FI)V"))
    private void maidsmart$noPullbackWalkWhileWorking(LivingEntity entity, BlockPos pos,
                                                      float speed, int closeEnough) {
        boolean working = entity instanceof EntityMaid maid && maidsmart$exempt(maid);
        if (!working) {
            BehaviorUtils.m_22617_(entity, pos, speed, closeEnough);
        }
    }
}
