package com.maidsmart.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【R1109】"气泡与系统消息"总开关的服务端闸：开关关掉时，只吞掉**调用栈来自本模组**的
 * 聊天系统消息（{@link com.maidsmart.tool.PromaidHintGate} 判定），原版与别的模组不受影响。
 * 两个重载都拦：普通聊天框与 actionbar（{@code sendSystemMessage(Component, true)}）。
 */
@Mixin(ServerPlayer.class)
public abstract class PromaidMessageGateMixin {

    @Inject(method = "m_213846_(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$gateChat(Component component, CallbackInfo ci) {
        try {
            if (com.maidsmart.tool.PromaidHintGate.shouldSuppress()) {
                ci.cancel();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidMessageGateMixin", ignored);
        }
    }

    @Inject(method = "m_5661_(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$gateOverlay(Component component, boolean overlay, CallbackInfo ci) {
        try {
            if (com.maidsmart.tool.PromaidHintGate.shouldSuppress()) {
                ci.cancel();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PromaidMessageGateMixin", ignored);
        }
    }
}
