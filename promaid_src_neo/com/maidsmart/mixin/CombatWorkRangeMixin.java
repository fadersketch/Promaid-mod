package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实测六百一十九【战斗时临时扩圈】（用户反馈：战斗时超出工作范围被传送回来、来回循环）。
 *
 * 只挂一个读数：{@code EntityMaid.getRestrictRadius}（javap 实证是读同步数据
 * RESTRICT_RADIUS 的纯 getter）。TLM 里"工作范围"的**全部**判据都读它
 * （{@code isWithinRestriction} 的圈内判定、寻路 MaidNodeEvaluator、
 * {@code SchedulePos.tick} 的"半径 + 4 就传送回工位"、各任务"离家多远该回去"），
 * 所以一处出口改完 = 全线一致，且不写任何存储（活动切段的 restrictTo 照常覆盖半径，
 * 我们的放大只在读数那一瞬生效）。
 *
 * 细则、口径与"为什么取 max / 为什么客户端不改"见
 * {@link com.maidsmart.combat.CombatWorkRange}。
 */
@Mixin(EntityMaid.class)
public abstract class CombatWorkRangeMixin {

    @Inject(method = "getRestrictRadius", at = @At("RETURN"), cancellable = true)
    private void maidsmart$combatWorkRange(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(com.maidsmart.combat.CombatWorkRange.radius(
                (EntityMaid) (Object) this, cir.getReturnValueF()));
    }
}
