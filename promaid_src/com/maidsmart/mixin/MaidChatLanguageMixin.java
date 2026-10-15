package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidsmart.config.MaidSmartConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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
 * 输出语言**二次检测**（配置 {@code dialogue.langCheck}，默认开）：{@code LLMCallback.onSuccess} 收到回复
 * 时检测文字是否为设定语言（目标中文时假名/英文占比过高 = 不符）→ 丢弃该回复并提示（日志保留原文前 80
 * 字符供排查）。提示词强制 + 输出检测双保险。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidChatLanguageMixin {

    @ModifyVariable(method = "tryToChat", at = @At("HEAD"), argsOnly = true, index = 2)
    private ChatClientInfo maidsmart$forceOutputLanguage(ChatClientInfo clientInfo) {
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
     * v1.5.231b：输出语言检测——LLM 回复落地处（LLMCallback.onSuccess）。
     * 检测 chatText 是否符合**设定的输出语言**；不符则【内嵌翻译】成目标语言：
     * - 把原文交给 LLM 翻译成目标语言（一条 system 翻译指令 + 原文），
     *   翻译结果直接作为最终回复显示；
     * - 翻译结果仍不符 → 放行原文（不再循环翻译，也不提示"已放弃"）；
     * - 日志搜 "lang translate" 看重试/原文。
     *
     * <p>v1.3.2 R948：旧版只查中文目标（{@code !target.startsWith("zh") → return}），
     * 于是"目标不是中文"时模型飘回中文没有任何兜底。现在判据按目标语言走
     * （{@link com.maidsmart.dialogue.OutputLanguage#matches}），翻译指令里的
     * 目标语言也跟着设定走（旧版写死"翻译成简体中文"）。
     */
    @Mixin(LLMCallback.class)
    public abstract static class MaidLangCheckMixin {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        /** 每个 callback 的翻译计数（WeakHashMap 防泄漏——callback 用完即弃） */
        private static final java.util.Map<LLMCallback, Integer> RETRIES =
                new java.util.WeakHashMap<>();

        @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
                at = @At("HEAD"), cancellable = true)
        private void maidsmart$langCheck(ResponseChat response, CallbackInfo ci) {
            try {
                if (!MaidSmartConfig.DIALOGUE_LANG_CHECK.get()) {
                    return;
                }
                String target = com.maidsmart.dialogue.OutputLanguage.current();
                LLMCallback cb = (LLMCallback) (Object) this;
                String text = response.getChatText();
                if (text == null || text.isBlank()
                        || com.maidsmart.dialogue.OutputLanguage.matches(text, target)) {
                    return; // 符合设定语言 → 放行
                }
                int retry = RETRIES.merge(cb, 1, Integer::sum);
                ci.cancel(); // 拦截本次回复（不显示原文）
                // v1.5.250【软化：审查打回 → 内嵌翻译】旧版：追加强化提示词重刷
                // （最多 2 次，超限放弃——烧 token 且打断对话流）。新版：把原文
                // 交给 LLM 翻译成目标语言，翻译结果直接作为最终回复（一次翻译，
                // 不循环）
                if (retry > 1) {
                    // 已翻译过一次仍不符 → 放弃翻译，放行原文（总比"已放弃"
                    // 提示强——女仆至少回答了）
                    LOGGER.warn("lang translate giveup: maid={} target={} text={}",
                            cb.getMaid().m_5446_(), target, clip(text));
                    showBubble(cb, text);
                    return;
                }
                LOGGER.warn("lang translate: maid={} target={} text={}",
                        cb.getMaid().m_5446_(), target, clip(text));
                java.util.List<LLMMessage> messages = new java.util.ArrayList<>();
                messages.add(LLMMessage.systemChat(cb.getMaid(),
                        "你是翻译助手。请把用户消息翻译成"
                                + com.maidsmart.dialogue.OutputLanguage.displayName(target)
                                + "（" + target + "）。只输出译文本身，不要任何解释、注释或前缀。"
                                + " You are a translation assistant: translate the user message into "
                                + com.maidsmart.dialogue.OutputLanguage.displayName(target)
                                + ". Output only the translation, with no explanation, notes or prefix."));
                messages.add(LLMMessage.userChat(cb.getMaid(), text));
                // 移除旧"思考中"气泡，发起翻译请求
                cb.getMaid().getChatBubbleManager().removeChatBubble(cb.getWaitingChatBubbleId());
                com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite site =
                        cb.getChatManager().getLLMSite();
                if (site == null || !site.enabled()) {
                    showBubble(cb, text); // 站点不可用 → 放行原文
                    return;
                }
                com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client = site.client();
                LLMCallback translateCb = new LLMCallback(cb.getChatManager(), messages);
                RETRIES.put(translateCb, retry); // 延续计数——翻译结果仍非中文则不再翻
                client.chat(translateCb);
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidChatLanguageMixin", ignored);
            }
        }

        /** 截断 80 字符（日志用） */
        private static String clip(String text) {
            return text.length() > 80 ? text.substring(0, 80) : text;
        }

        /** 服务端线程显示气泡（onSuccess 可能不在服务端线程） */
        private static void showBubble(LLMCallback cb, String text) {
            net.minecraft.server.level.ServerLevel level =
                    cb.getMaid().m_9236_() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
            if (level != null) {
                level.m_7654_().m_18707_(() ->
                        cb.getMaid().getChatBubbleManager().addLLMChatText(text, cb.getWaitingChatBubbleId()));
            }
        }
    }
}
