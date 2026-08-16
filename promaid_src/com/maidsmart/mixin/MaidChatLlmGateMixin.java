package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.memory.LlmEnableManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * per-maid 大语言模型开关(v1.0.3)——拦截 TLM 的 `MaidAIChatManager.chat(...)`
 * （AI 聊天唯一入口:TLM 原版按 J/点击聊天、heartfelt 全部主动对话
 * (chatWithQuota→chat)、promaid 主动对话都汇聚于此）。
 *
 * LlmEnableManager.isEnabled(maid)==false → 取消请求(不发 LLM),并给玩家一条
 * 系统提示「该女仆的大语言模型已关闭」——对话静默不报错。
 * 默认开:未设置开关的女仆不受影响。
 *
 * TLM 类未混淆(开发名 chat/getMaid),编译 cp 上有 MaidAIChatManager → 普通 @Mixin;
 * promaid mixins.json required:true + defaultRequire:1,方法名必须精确匹配。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidChatLlmGateMixin {

    @Inject(method = "chat", at = @At("HEAD"), cancellable = true)
    private void maidsmart$gateLlm(String prompt, ChatClientInfo info, ServerPlayer player,
            CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null || LlmEnableManager.isEnabled(maid)) {
            return;
        }
        ci.cancel();
        if (player != null) {
            player.m_213846_(Component.m_237113_(
                    "\u00a77【大语言模型】" + maid.m_5446_().getString()
                            + " 的 LLM 对话已关闭，用记忆与固定文本回应。"));
        }
    }
}
