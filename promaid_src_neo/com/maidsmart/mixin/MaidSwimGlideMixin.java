package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.0（1.21.1）：飞行作战——滑翔时套用女仆的【游泳动作】。
 *
 * 【机制（字节码实证）】TLM 的动画选择只看 isVisuallySwimming()：
 *   - Gecko 路径：client/animation/gecko/AnimationRegister 的 "swim" 谓词 =
 *     IMaid.asEntity().isVisuallySwimming()；
 *   - Bedrock 路径：HardcodedAnimationManger.init 里的 SwimAnimation 读
 *     mob.isVisuallySwimming() / getSwimAmount。
 * 而 EntityMaid.isVisuallySwimming() 【直接返回 isSwimming()】（= 共享标志位第 4 位，
 * 忽略 pose）——所以只要让滑翔时它也返回 true，游泳动作就会自动播放。
 *
 * 【为什么用 mixin 而不是 setSwimming(true)】EntityMaid.updateSwimming() 每 tick 走
 * MaidSwimManager.updatePose()，在非水中会 setSwimming(false) 把标志位清掉——直接置位
 * 会被每 tick 覆盖。改判 isVisuallySwimming 的返回值则不受此影响，且服务端/客户端
 * 都生效（谓词两端都会求值）。
 *
 * 注意：pose 保持站立（不设 Pose.SWIMMING）——setPose(SWIMMING) 会把判定箱缩到
 * 0.6×0.6（getDefaultDimensions 实证），滑翔时体积变化会影响碰撞与客户端插值。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void promaid$glideAsSwimming(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 仅飞行作战的滑翔/俯冲中套用游泳动作；其余情况保持 TLM 原逻辑
        if (maid.isFallFlying()
                && com.maidsmart.combat.MaidFlightKit.isFlightTask(maid)) {
            cir.setReturnValue(true);
        }
    }
}
