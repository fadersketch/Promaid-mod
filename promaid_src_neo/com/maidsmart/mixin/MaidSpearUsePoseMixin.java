package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.SpearCoreCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端隐藏长矛蓄力期间的"使用物品"手臂姿态。
 *
 * <p>TLM 的 {@code MaidBaseAnimation} 在 {@code isUsingItem() && 主手} 时把手臂
 * 直接抬到约 80°，长矛蓄力本身要求持续 {@code startUsingItem}，于是整段冲锋
 * 都保持这个姿势——这是"不停挥臂"的另一半来源，`swing` 闸同样拦不到。
 * 只在客户端把 {@code isUsingItem} 报成 false（服务端状态不变，伤害照常结算）；
 * 真正命中那一下仍由 spearcore 的二参 swing 播放一次摆臂。
 */
@Mixin(LivingEntity.class)
public abstract class MaidSpearUsePoseMixin {

    @Inject(method = "isUsingItem", at = @At("HEAD"), cancellable = true)
    private void promaid$hideSpearUsePose(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof EntityMaid maid) || !maid.level().isClientSide()) {
            return;
        }
        try {
            ItemStack use = maid.getUseItem();
            if (!use.isEmpty() && SpearCoreCompat.isSpear(use)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidSpearUsePoseMixin", ignored);
        }
    }
}
