package com.maidsmart.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【R1108 准星描边】客户端 {@code isCurrentlyGlowing} 覆写：手持武装拴绳/指标石时，准星
 * 指向的女仆按"发光"渲染——与光灵箭同一条描边管线（{@code OutlineBufferSource}），
 * 但不写实体标志位、不给别的玩家看。口径照傀儡装配的命令手杖（l2library 的同一招）。
 *
 * <p>只在客户端生效：目标表由客户端 tick 维护，专用服务器上恒为空，方法本身也先判
 * {@code isClientSide}，两侧共用同一个方法名也不会有任何服务端副作用。
 */
@Mixin(Entity.class)
public abstract class MaidAimGlowMixin {

    @Inject(method = "isCurrentlyGlowing()Z", at = @At("HEAD"), cancellable = true)
    private void promaid$aimGlowOutline(CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity) (Object) this;
            if (!self.level().isClientSide()) {
                return;
            }
            if (com.maidsmart.client.MaidAimGlowClient.isAimed(self)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidAimGlowMixin", ignored);
        }
    }
}
