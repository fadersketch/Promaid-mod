package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 飞行作战——滑翔时套用女仆的**游泳动画**（= 展翅姿态）。
 * 【为什么必须 mixin 这里】① {@code EntityMaid} 覆写了 {@code isVisuallySwimming()} 为
 * {@code return this.isSwimming()}，**丢了"FALL_FLYING pose 也算"的分支** → 光靠滑翔 pose 不出游泳动画；
 * ② {@code MaidSwimManager.updateSwimming()} 每 tick 在服务端清掉游泳位，不能靠 {@code setSwimming} 硬置。
 * 故在 {@code isVisuallySwimming} 的 HEAD 按"是否滑翔"直接返回；【不动 pose】{@code setPose(SWIMMING)} 会把
 * 判定箱缩到 0.6×0.6，副作用太大。
 * 【判据 = "飞行任务**或**正在滑翔"】早先多问"是不是飞行任务"，而**飞行跟随的女仆并不是飞行任务** → 她滑翔时
 * 模型仍是走路姿态。扫过整个 TLM 的 2344 个类，引用该方法的只有 3 个（动画选择谓词 / 游泳动画本体 /
 * {@code EntityMaid} 自己的覆写）——**全是客户端动画**，无一玩法判定读它，故放宽只影响渲染；判据取同步的滑翔位。
 * 【姿态三层同口径】判据进一步改成 {@code isFlightVisual(maid) && !onGround()}——与展翅、前倾**逐字一致**：
 * 空袭里滑翔位是**闪的**（收翅猛击/激流突进/落地都 {@code setGliding(false)}），旧口径下那几拍游泳姿态一掉、
 * 身体回正而翅膀照旧张着、前倾照旧转 = "半立着又被硬掰了俯角"的破碎姿势；三层同进同退后，一轮空袭从起跳到
 * 收手都是俯卧+展翅+前倾，落地立刻全收。本树官方名 {@code isVisuallySwimming}/{@code isFallFlying}。
 */
@Mixin(EntityMaid.class)
public abstract class MaidSwimGlideMixin {

    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void promaid$glideAsSwimming(CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        // 六百一十一：只看"在不在飞"，不再要求"是飞行任务"——原因见类注释
        // （飞行跟随不是飞行任务；且 TLM 里读这个方法的只有 3 个客户端动画类）
        // 实测六百七十五：滑翔动画二选一——默认沿用游泳动作（兼容性最好：官方包与第三方包
        // 普遍都有 swim，而几乎没有 elytra_fly）；打开「滑翔时用鞘翅动画」后**不再顶游泳位**，
        // 改由 MaidFreeFlightAnimMixin 注册的 elytra_fly 状态接管（模型包做了那条动画才有效果）。
        // 实测八百一十一：判据从 `isFallFlying()` 改成 `isFlightVisual && !onGround()`，
        // 与展翅 / 跟随前倾同口径——修掉"收翅那几拍姿势散架"（原因见类注释）。
        if (!com.maidsmart.config.MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()
                && !maid.onGround()
                && com.maidsmart.combat.MaidFlightKit.isFlightVisual(maid)) {
            cir.setReturnValue(true);
            return;
        }
        // 实测八百一十二【自动兜底】：开关开着，但**这个模型根本没做 elytra_fly**
        // （玩家实测现场：模型包里 0 条同名动画）→ 那一档取不出动画、模型会掉回绑定姿势。
        // 这时照样顶游泳位：宁可播游泳动作，也不能让她姿势散架。
        // 判据只看**客户端 TLM 动画表**（与 TLM 取动画同一张表、同一个键），服务端一次都不碰。
        if (com.maidsmart.config.MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()
                && !maid.onGround()
                && com.maidsmart.combat.MaidFlightKit.isFlightVisual(maid)
                && !com.maidsmart.flight.MaidGlideAnimSupport.hasElytraFly(maid)) {
            cir.setReturnValue(true);
        }
    }
}
