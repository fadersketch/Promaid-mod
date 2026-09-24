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
        // 【v1.3.0(beta) 实测六百六十四：扫帚模式一起豁免】玩家原话：「开着 home 的扫帚模式，
        //  女仆不应该响应排班表的传送」。她骑在扫帚上时 TLM 的 SchedulePos.tick 本来就传不动她
        //  （canBrainMoving() 为 false：乘客/坐着/睡觉/被拴），但**下扫帚那几拍**（缺件待命、
        //  刚落地）会照常被"出圈就传送回工位"抓走，而她要飞来飞去，被拽回圈心一次就得重新起飞。
        //  所以扫帚模式整段按"干活不打断"处理：圈心照常刷新，传送/走回一律跳过。
        if ((com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid))
                || com.maidsmart.combat.MaidBroomKit.isBroomTask(maid)) {
            // 与原版 tick 同节奏：每 2 秒刷新一次限制圈（圈心随当前活动对齐锚点）
            if (maid.tickCount % 40 == 0) {
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
