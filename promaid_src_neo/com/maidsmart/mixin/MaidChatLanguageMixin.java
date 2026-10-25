package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidsmart.config.MaidSmartConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 对话语言根治——TLM 每次对话（{@code tryToChat}）都会把 {@code chatLanguage = clientInfo.language()}
 * （= 客户端游戏语言）覆盖上去，所以玩家在 TLM 聊天 GUI 发起一次对话后，之后的对话全变客户端语言
 * （"前几句中文 → 玩家聊几句 → 全日文"）。
 *
 * 这里在 {@code tryToChat} 入口用 {@code @ModifyVariable}(argsOnly) 直接替换
 * {@code ChatClientInfo.language}——方法体内两处读取（chatLanguage 字段 + 提示词语言）都吃到新值，覆盖
 * 所有调用方（玩家 GUI / promaid 主动对话 / TLM 其他对话）。配置 {@code dialogue.outputLanguage} 非空时
 * 强制；留空 = 强制中文。
 *
 * 输出语言二次检测/翻译在 {@link MaidLangCheckMixin}，内部触发跳过历史在
 * {@link MaidInternalHistoryMixin}——Mixin 不会把嵌套 @Mixin 类注册成独立混入，所以两者必须是顶层类。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidChatLanguageMixin {

    @ModifyVariable(method = "tryToChat", at = @At("HEAD"), argsOnly = true, index = 2)
    private ChatClientInfo maidsmart$forceOutputLanguage(ChatClientInfo clientInfo) {
        if (!MaidSmartConfig.AI_ENABLE.get()) {
            return clientInfo; // Promaid AI 总开关关闭：不介入 TLM 自己的对话语言
        }
        String lang = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
        // v1.5.228：配置留空 = 默认强制中文（zh_cn）——旧版"留空 = 跟随 TLM/客户端"
        // 导致玩家游戏里对话持续输出日文（TLM chatLanguage 被客户端/聊天 GUI 覆盖
        // 成日语，且每次 tryToChat 都重写）。想要跟随其他语言就显式填 ja_jp 等。
        if (lang == null || lang.isBlank()) {
            lang = "zh_cn";
        }
        if (lang.equals(clientInfo.language())) {
            return clientInfo;
        }
        return new ChatClientInfo(lang, clientInfo.name(), clientInfo.description());
    }

    /**
     * 主动对话/自主决策的内部触发按系统消息发送——模型不会把它当成主人刚说的话。
     * 跳过 addUserHistory 的逻辑见 {@link MaidInternalHistoryMixin}（不改这个调用点，
     * 避免和 callresponse 的 @Redirect 冲突）。
     */
    @Redirect(method = "normalChat",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;userChat(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Ljava/lang/String;)Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;"))
    private static LLMMessage maidsmart$internalAsSystem(EntityMaid maid, String prompt) {
        if (com.maidsmart.dialogue.ChatInfoUtil.isInternal(prompt)) {
            return LLMMessage.systemChat(maid, com.maidsmart.dialogue.ChatInfoUtil.stripInternal(prompt));
        }
        return LLMMessage.userChat(maid, prompt);
    }
}
