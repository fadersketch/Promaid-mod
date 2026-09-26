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
 *
 * ── v1.2.2 实测六百一十一【判据从"飞行任务"放宽成"正在滑翔"】──
 * 用户反馈："飞行跟随……动作没有换成空袭飞行的动作。"根因就在这里：这一支原来多问了一句
 * {@link com.maidsmart.combat.MaidFlightKit#isFlightTask}，而**飞行跟随（跟着主人飞）的女仆
 * 并不是飞行任务**，于是她滑翔时模型仍是走路/站立的姿态——空袭那套展翅动画一点没跟上。
 *
 * 【为什么放心放宽：扫过整个 TLM 的 2344 个类，引用 isVisuallySwimming 的只有 3 个】
 * `client/animation/gecko/AnimationRegister`（动画选择谓词）、
 * `client/animation/special/SwimAnimation`（游泳动画本体）、
 * `entity/passive/EntityMaid`（就是它自己的覆写）——**全是客户端动画**，没有一个玩法判定读它，
 * 所以这个放宽只影响渲染。放成"只要在滑翔就为真"同时也是把原版语义还回来（原版
 * `LivingEntity.isVisuallySwimming` 本来就把 FALL_FLYING 算进去，是 TLM 覆写时丢的），
 * 而且判据取的是**同步过的滑翔位**——多人下客户端也认得出，不依赖服务端状态表。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void promaid$glideAsSwimming(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 六百一十一：只看"在不在滑翔"，不再要求"是飞行任务"——原因见类注释
        // （飞行跟随不是飞行任务；且 TLM 里读这个方法的只有 3 个客户端动画类）
        // 实测六百七十五：滑翔动画二选一——默认沿用游泳动作（兼容性最好：官方包与第三方包
        // 普遍都有 swim，而几乎没有 elytra_fly）；打开「滑翔时用鞘翅动画」后**不再顶游泳位**，
        // 改由 MaidFreeFlightAnimMixin 注册的 elytra_fly 状态接管（模型包做了那条动画才有效果）。
        if (maid.isFallFlying() && !com.maidsmart.config.MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()) {
            cir.setReturnValue(true);
        }
    }
}
