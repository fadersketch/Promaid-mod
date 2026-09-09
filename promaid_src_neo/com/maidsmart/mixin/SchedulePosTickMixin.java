package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.maidsmart.task.MaidWorkTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.287：干活不被打断⑤——周期拉回补漏。
 *
 * 根因：SchedulePullbackMixin 只堵住了【切班瞬间】的 restrictTo + 走回工位；
 * SchedulePos.tick 由 EntityMaid.tick 每 40 tick（2 秒）调用一次，home 模式
 * 下会再次 restrictTo（收紧活动半径）并【出圈就传送/走回】——javap 实证
 * tick 方法体只有这段拉回逻辑，别无职责。女仆在远处挖矿/干农活时照样每
 * 2 秒被拽一次（旧 patch 漏网，用户："干活不被打断"）。
 *
 * 修复：非战斗干活中（isNonCombatWork，v1.5.287 起已排除 idle——待机女仆
 * 照常被日程管理）跳过本 tick 拉回。总开关 misc.workUninterrupted。
 */
@Mixin(SchedulePos.class)
public abstract class SchedulePosTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noPeriodicPullbackWhileWorking(EntityMaid maid, CallbackInfo ci) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)) {
            ci.cancel(); // 干活中：跳过本 tick 的 restrictTo/传送/走回
        }
    }
}
