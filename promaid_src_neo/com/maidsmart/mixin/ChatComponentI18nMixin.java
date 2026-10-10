package com.maidsmart.mixin;

import com.maidsmart.i18n.PromaidI18n;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Promaid 英文翻译（1.21.1）· 聊天栏出口。
 *
 * <p>同 Forge 版：本模组聊天消息是 literal，**在客户端"加入聊天"处**按本机玩家语言
 * 整串替换为英文——服务端不改、协议不改、各玩家各按自己语言显示。
 * 两个 {@code addMessage} 重载都注入（javap 实测），
 * {@link PromaidI18n#tr(Component)} 幂等，重复无害。
 */
@Mixin(ChatComponent.class)
public class ChatComponentI18nMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trChatAdd(Component message) {
        return PromaidI18n.tr(message);
    }

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trChatAddFull(Component message) {
        return PromaidI18n.tr(message);
    }
}
