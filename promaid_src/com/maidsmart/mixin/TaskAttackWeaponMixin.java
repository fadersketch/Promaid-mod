package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.225：锄头不作为武器——TLM 的 TaskAttack.isWeapon 判定"主手属性里有攻击
 * 伤害属性"即视为武器，而 1.20.1 的锄头（HoeItem）带攻击伤害属性 → 女仆拿锄头
 * 攻击（日志实证 weapon=minecraft:golden_hoe）。锄头是农具，战斗/自动装备时应
 * 排除——任何锄头（木/石/铁/金/钻/下界合金）都不算武器。
 */
@Mixin(TaskAttack.class)
public abstract class TaskAttackWeaponMixin {
    @Inject(method = "isWeapon", at = @At("HEAD"), cancellable = true)
    private void maidsmart$excludeHoe(EntityMaid maid, ItemStack stack,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (stack != null && stack.m_41720_() instanceof net.minecraft.world.item.HoeItem) {
            cir.setReturnValue(false); // 锄头不是武器（农具），不参与攻击/装备判定
        }
    }
}
