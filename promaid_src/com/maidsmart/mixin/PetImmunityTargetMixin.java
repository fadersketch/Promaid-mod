package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 宠物免疫·目标选取拦截（v1.1.0 实测一百九十三，用户："都被打上了玩家宠物的字样。
 * 女仆便不会再对他造成伤害（包括 aoe，防误伤）并去除仇恨"）。
 *
 * IAttackTask.canAttack 是所有攻击任务（近战/远程弹幕/枪械）共用的目标选取谓词
 * （TLM MaidAttackStrafingTask / findFirstValidAttackTarget 都走它）——命中宠物
 * 标记目标直接返回 false：仇恨不进攻击记忆；TLM TaskAttack 的 StopAttackingIf
 * TargetInvalid（javap 实证原版也在用）同谓词会自动清掉已持有的目标（去仇恨）。
 * 伤害层的兜底在 PetImmunityGuard（LivingHurtEvent 总闸）。
 */
@Mixin(com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask.class)
public abstract class PetImmunityTargetMixin {
    @Inject(method = "canAttack", at = @At("HEAD"), cancellable = true)
    private void maidsmart$petImmune(EntityMaid maid, LivingEntity target,
                                     CallbackInfoReturnable<Boolean> cir) {
        try {
            if (com.maidsmart.combat.PetImmunityGuard.isPetMarked(maid, target)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
