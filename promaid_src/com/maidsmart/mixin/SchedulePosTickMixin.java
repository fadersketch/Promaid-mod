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
 * 2 秒被拽一次（旧 patch 漏网，反馈："干活不被打断"）。
 *
 * 实测五百六十二（工作区域回归）：改成【只免掉拉回，圈心照常刷新】——
 * 原版整段 cancel 后限制圈停在旧锚点上，而任务扫描（WorkAreaClamp）与
 * 巡逻/驱动都以圈心为准，圈一旦过期整套工作区域就漂了。现在干活期间照常
 * restrictTo（圈心/半径保持最新），只跳过出圈时的传送/走回；目标选点已由
 * WorkAreaClamp 钳在圈内，出圈只剩被推/被打这类意外，收工后由原版拉回兜底。
 * 注意 restrictTo 只在 SchedulePos 已配置（isConfigured）时才调——建造行为
 * 会把 homeMode 置 true 但不配锚点，配置前 restrictTo 会拿零点当圈心把人
 * 拴到世界原点。总开关 misc.workUninterrupted。
 */
@Mixin(SchedulePos.class)
public abstract class SchedulePosTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noPeriodicPullbackWhileWorking(EntityMaid maid, CallbackInfo ci) {
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)) {
            // 与原版 tick 同节奏：每 2 秒刷新一次限制圈（圈心随当前活动对齐锚点）
            if (maid.f_19797_ % 40 == 0) {
                try {
                    var sp = maid.getSchedulePos();
                    if (sp != null && sp.isConfigured()) {
                        sp.restrictTo(maid);
                    }
                } catch (Throwable ignored) {
                }
            }
            ci.cancel(); // 干活中：跳过本 tick 的传送/走回（restrictTo 已在上面补过）
        }
    }
}
