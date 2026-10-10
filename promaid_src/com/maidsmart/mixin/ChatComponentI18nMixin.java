package com.maidsmart.mixin;

import com.maidsmart.i18n.PromaidI18n;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Promaid 英文翻译（1.20.1）· 聊天栏出口。
 *
 * <p>本模组发往聊天栏的所有系统消息都以 {@code Component.literal("中文…")} 构造，
 * 原样经网络送到客户端。**在客户端"加入聊天队列"处**按本机玩家语言整串替换，
 * 因此：
 * <ul>
 *   <li>服务端一字不改——不改发包内容、不改协议、不改任何服务端状态；</li>
 *   <li>每个玩家各按自己的语言显示（中文玩家看中文，英文玩家看英文）；</li>
 *   <li>不触碰日志/记忆/LLM 提示词等非玩家可见文本。</li>
 * </ul>
 * 两个入口均经对 1.20.1-srg 的 javap 实测确认：{@code m_93785_(Component)} 委托给
 * {@code m_240964_(Component,MessageSignature,GuiMessageTag)}，两个都注入以兜住所有路径
 * （同一 Component 不会被重复翻译：{@link PromaidI18n#tr(Component)} 幂等）。
 */
@Mixin(ChatComponent.class)
public class ChatComponentI18nMixin {

    @ModifyVariable(method = "m_93785_(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trChatAdd(Component message) {
        return PromaidI18n.tr(message);
    }

    @ModifyVariable(method = "m_240964_(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component promaid$trChatAddFull(Component message) {
        return PromaidI18n.tr(message);
    }
}
