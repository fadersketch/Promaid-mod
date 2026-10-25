package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.memory.LlmEnableManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * per-maid 大语言模型开关(v1.0.3)——拦截 TLM 的 `MaidAIChatManager.chat(...)`
 * （AI 聊天唯一入口:TLM 原版按 J/点击聊天、promaid 主动对话都汇聚于此）。
 *
 * LlmEnableManager.isEnabled(maid)==false → 取消请求(不发 LLM),并给玩家一条
 * 关闭时只静默取消：不往聊天栏发提示（主动对话会反复触发，会刷屏）。
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
        ci.cancel(); // 只静默取消：不往聊天栏发「已关闭」提示（主动对话会反复触发，刷屏）
    }
}
