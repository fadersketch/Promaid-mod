package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.0（1.20.1）：飞行作战——滑翔时套用女仆的游泳动画（= 展翅姿态）。
 *
 * 为什么必须 mixin 这里：
 * 1. `EntityMaid` 覆写了 `isVisuallySwimming()`（SRG `m_6067_()`）为
 *    `return this.isSwimming();`（`m_6069_()`），**丢掉了 LivingEntity 里
 *    "FALL_FLYING pose 也算 visually swimming" 的分支** → 光靠滑翔 pose 不会出游泳动画；
 * 2. `MaidSwimManager.updateSwimming()`（由 `EntityMaid.m_5844_()` 每 tick 调）会在服务端
 *    每 tick 把游泳位清掉（`updatePose` → `setSwimming(false)`），所以不能靠 setSwimming 硬置。
 * 在 `m_6067_` 的 HEAD 直接按"是否滑翔"返回，最稳。
 *
 * 刻意不动 pose：`EntityMaid.m_6972_(Pose)` 对 SWIMMING 会返回 0.6×0.6 的判定箱，
 * 把滑翔塞进 SWIMMING pose 会让女仆判定箱缩水，副作用太大。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "m_6067_()Z", at = @At("HEAD"), cancellable = true)
    private void promaid$glideUsesSwimPose(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        if (maid.m_21255_() && MaidFlightKit.isFlightTask(maid)) {
            cir.setReturnValue(true);
        }
    }
}
