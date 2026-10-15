package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实测九百一十一【干活时临时扩圈】（挖矿 / 伐木）：与 {@link CombatWorkRangeMixin}
 * 挂在同一个读数 {@code EntityMaid.getRestrictRadius} 上——
 * TLM 里"工作范围"的全部判据都读它（圈内判定 / 寻路 MaidNodeEvaluator /
 * {@code SchedulePos.tick} 的"出圈（半径 + 4）就传送回工位，否则走回去"），
 * 所以一处出口改完 = "不会被一下子传送回来、也不会被拽着走回来"全线一致。
 *
 * <p>两条注入互不干扰：都只在出口取一次 max，谁先谁后结果相同。口径、放大到多少、
 * 客户端为什么不动、开关是谁，全部见 {@link com.maidsmart.task.MaidWorkRangeExpand}。
 */
@Mixin(EntityMaid.class)
public abstract class WorkRangeExpandMixin {

    @Inject(method = "getRestrictRadius", at = @At("RETURN"), cancellable = true)
    private void maidsmart$workRangeExpand(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(com.maidsmart.task.MaidWorkRangeExpand.radius(
                (EntityMaid) (Object) this, cir.getReturnValueF()));
    }
}
