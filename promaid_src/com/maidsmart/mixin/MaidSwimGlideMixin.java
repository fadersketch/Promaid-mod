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
 *
 * ── v1.2.2 实测六百一十一【判据从"飞行任务"放宽成"正在滑翔"】──
 * 用户反馈："飞行跟随……动作没有换成空袭飞行的动作。"根因就在这里：这一支原来多问了一句
 * {@link MaidFlightKit#isFlightTask}，而**飞行跟随（跟着主人飞）的女仆并不是飞行任务**，
 * 于是她滑翔时模型仍是走路/站立的姿态——空袭那套展翅动画一点没跟上。
 *
 * 【为什么放心放宽：扫过整个 TLM 1.5.3 的 2344 个类，引用 `m_6067_` 的只有 3 个】
 * `client/animation/gecko/AnimationRegister`（动画选择谓词）、
 * `client/animation/special/SwimAnimation`（游泳动画本体）、
 * `entity/passive/EntityMaid`（就是它自己的覆写）——**全是客户端动画**，没有一个玩法判定读它，
 * 所以这个放宽只影响渲染。放成"只要在滑翔就为真"同时也是把原版语义还回来（原版
 * `LivingEntity.isVisuallySwimming` 本来就把 FALL_FLYING 算进去，是 TLM 覆写时丢的），
 * 而且判据取的是**同步过的滑翔位**——多人下客户端也认得出，不依赖服务端那张状态表。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "m_6067_()Z", at = @At("HEAD"), cancellable = true)
    private void promaid$glideUsesSwimPose(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 六百一十一：只看"在不在滑翔"，不再要求"是飞行任务"——原因见类注释
        // （飞行跟随不是飞行任务；且 TLM 里读这个方法的只有 3 个客户端动画类）
        if (maid.m_21255_()) {
            cir.setReturnValue(true);
        }
    }
}
