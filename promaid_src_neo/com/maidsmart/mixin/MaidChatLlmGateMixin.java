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
 * 另：系统主动对话撞上 TLM 全局关闭 AI 对话时，也在 HEAD 静默取消——那条红色
 * 「AI 对话功能已被禁用」是 TLM 的 chat() 自己发的，主动对话每轮都试会刷屏。
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
        // 系统主动对话（promaid 内部标记）撞上"AI 已关闭/没配 LLM 站点"时，TLM 的 chat()
        // 会往聊天栏发红色提示（chat.disable / chat.llm.empty），而主动对话每轮都会再试
        // → 刷屏。这一档在 HEAD 静默取消；主人自己发起的聊天没有内部标记，照旧走 TLM 原逻辑。
        if (com.maidsmart.dialogue.ChatInfoUtil.isInternal(prompt) && !systemChatUsable(self)) {
            ci.cancel();
            return;
        }
        if (maid == null || LlmEnableManager.isEnabled(maid)) {
            return;
        }
        ci.cancel(); // 只静默取消：不往聊天栏发「已关闭」提示（主动对话会反复触发，刷屏）
    }

    /** 系统主动对话此刻能不能真的发出去（会失败且会发系统提示的一律当"不能"）。 */
    private static boolean systemChatUsable(MaidAIChatManager self) {
        if (!com.maidsmart.config.MaidSmartConfig.AI_ENABLE.get()
                || !com.maidsmart.config.MaidSmartConfig.DIALOGUE_ENABLE.get()
                || !com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig.LLM_ENABLED.get()) {
            return false;
        }
        try {
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite site = self.getLLMSite();
            return site != null && site.enabled();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidChatLlmGateMixin", ignored);
            return false;
        }
    }
}
