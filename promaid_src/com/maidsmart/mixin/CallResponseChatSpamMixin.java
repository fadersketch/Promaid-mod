package com.maidsmart.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.5.316：屏蔽爱憎分明(callresponse 2.0.2)女仆背包界面的"饱食度"聊天调试消息。
 *
 * 崩溃根因(用户 crash-2026-08-14_12.32.47 实证)：
 * - callresponse 的 MaidHungerGuiDisplay.onMaidGuiRender 在渲染女仆背包界面时
 *   每 500 帧往聊天框发一条 "§e饱食度: N" 系统消息（frameCount % 500 == 0，
 *   反编译确认）——GUI 上它已渲染"饥饿值"文本，这条聊天消息纯属调试残留；
 * - 与 tweakermore/tweakerge 的 ChatHud 消息限制 mixin 叠加，高频 add/remove
 *   冲突会损坏 ChatComponent 的内部 LinkedList（出现 null 元素）→ 渲染聊天
 *   面板 / 再次 addMessage 时 NPE → "Rendering screen" 崩溃。
 *
 * 做法：@Redirect 掉 onMaidGuiRender 里对 LocalPlayer.m_213846_ 的调用 → no-op。
 * 聊天框不再被写入 → 链表不再损坏。GUI 上"饥饿值"文本渲染不受影响。
 * @Pseudo + 字符串类名：callresponse 可选，未安装时静默跳过（与 LoveLoathe
 * 软集成同一模式）。不修改 callresponse 本体 jar。
 */
@Pseudo
@Mixin(targets = "com.github.JumDa5he.callresponse.compat.hunger.MaidHungerGuiDisplay")
public abstract class CallResponseChatSpamMixin {
    /** 屏蔽调试消息：被调用的 m_213846_ 换为 no-op（接收者+参数保持签名兼容） */
    @Redirect(method = "onMaidGuiRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;m_213846_(Lnet/minecraft/network/chat/Component;)V"))
    private void maidsmart$silenceHungerChat(net.minecraft.client.player.LocalPlayer player,
                                             net.minecraft.network.chat.Component component) {
        // no-op：不发聊天消息（GUI 上"饥饿值"文本仍正常渲染）
    }
}
