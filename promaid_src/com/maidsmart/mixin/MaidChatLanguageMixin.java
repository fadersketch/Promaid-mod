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
 * v1.5.205：对话语言根治——TLM 每次对话（tryToChat）都把
 * chatLanguage = clientInfo.language()（= 客户端游戏语言，用户客户端是日语），
 * 旧修复只改了 promaid 自己的 3 个主动对话调用点（ChatInfoUtil.fromMaid）——
 * 玩家在 TLM 聊天 GUI 发起对话（客户端语言 = 日语）后 chatLanguage 被覆盖，
 * 之后的对话全变日语（"前几句中文（promaid 主动对话强制 zh）→ 玩家聊几句 →
 * 全日文"）。
 *
 * 这里在 tryToChat 入口直接替换 ChatClientInfo.language（@ModifyVariable
 * argsOnly，方法体内两处读取都吃到新值：chatLanguage 字段 + 提示词语言）——
 * 覆盖所有调用方（玩家 GUI / promaid 主动对话 / TLM 其他对话）。
 * 配置 dialogue.outputLanguage 非空时强制；留空 = 强制中文（v1.5.228）。
 *
 * v1.5.231b：输出语言【二次检测】（配置 dialogue.langCheck，默认开）——
 * LLMCallback.onSuccess 收到 LLM 回复时检测文字是否为设定语言：目标中文时
 * 假名/英文占比过高 = 语言不符 → 丢弃该回复显示提示（日志保留原文前 80 字符
 * 供排查）。提示词强制（v1.5.228）+ 输出检测（这里）双保险。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidChatLanguageMixin {

    @ModifyVariable(method = "tryToChat", at = @At("HEAD"), argsOnly = true, index = 2)
    private ChatClientInfo maidsmart$forceOutputLanguage(ChatClientInfo clientInfo) {
        String lang = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
        // v1.5.228：配置留空 = 默认强制中文（zh_cn）——旧版"留空 = 跟随 TLM/客户端"
        // 导致用户游戏里对话持续输出日文（TLM chatLanguage 被客户端/聊天 GUI 覆盖
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
     * 检测 chatText 是否为设定语言：目标中文时，汉字占比过低或假名占比过高
     * = 不符 → 【内嵌翻译】（v1.5.250 软化，替代旧"审查打回重刷"）：
     * - 把原文交给 LLM 翻译成目标语言（一条 system 翻译指令 + 原文），
     *   翻译结果直接作为最终回复显示；
     * - 翻译结果仍非中文 → 放行原文（不再循环翻译，也不提示"已放弃"）；
     * - 日志搜 "lang translate" 看重试/原文。
     */
    @Mixin(LLMCallback.class)
    public abstract static class MaidLangCheckMixin {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        /** 每个 callback 的翻译计数（WeakHashMap 防泄漏——callback 用完即弃） */
        private static final java.util.Map<LLMCallback, Integer> RETRIES =
                new java.util.WeakHashMap<>();

        @Inject(method = "onSuccess", at = @At("HEAD"), cancellable = true)
        private void maidsmart$langCheck(ResponseChat response, CallbackInfo ci) {
            try {
                if (!MaidSmartConfig.DIALOGUE_LANG_CHECK.get()) {
                    return;
                }
                String target = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
                if (target == null || target.isBlank()) {
                    target = "zh_cn";
                }
                if (!target.startsWith("zh")) {
                    return; // 非中文目标暂不检测（当前主要解决日文输出问题）
                }
                LLMCallback cb = (LLMCallback) (Object) this;
                String text = response.getChatText();
                if (text == null || text.isBlank() || isLikelyChinese(text)) {
                    return; // 中文 → 放行
                }
                int retry = RETRIES.merge(cb, 1, Integer::sum);
                ci.cancel(); // 拦截本次回复（不显示原文）
                // v1.5.250【软化：审查打回 → 内嵌翻译】旧版：追加强化提示词重刷
                // （最多 2 次，超限放弃——烧 token 且打断对话流）。新版：把原文
                // 交给 LLM 翻译成目标语言，翻译结果直接作为最终回复（一次翻译，
                // 不循环）
                if (retry > 1) {
                    // 已翻译过一次仍非中文 → 放弃翻译，放行原文（总比"已放弃"
                    // 提示强——女仆至少回答了）
                    LOGGER.warn("lang translate giveup: maid={} text={}",
                            cb.getMaid().m_5446_(), clip(text));
                    showBubble(cb, text);
                    return;
                }
                LOGGER.warn("lang translate: maid={} text={}", cb.getMaid().m_5446_(), clip(text));
                java.util.List<LLMMessage> messages = new java.util.ArrayList<>();
                messages.add(LLMMessage.systemChat(cb.getMaid(),
                        "你是翻译助手。请把用户消息翻译成简体中文（zh_cn）。只输出译文本身，"
                                + "不要任何解释、注释或前缀。"));
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
            } catch (Exception ignored) {
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

        /** 中文判定：汉字占比 ≥40% 且假名占比 <10%（日文 = 假名多；英文 = 汉字 0） */
        private static boolean isLikelyChinese(String text) {
            int han = 0;
            int kana = 0;
            int letters = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= '\u4e00' && c <= '\u9fff') {
                    han++;
                    letters++;
                } else if (c >= '\u3040' && c <= '\u30ff') {
                    kana++;
                    letters++;
                } else if (Character.isLetter(c)) {
                    letters++;
                }
            }
            if (letters == 0) {
                return true; // 纯符号/数字不判定
            }
            return han >= letters * 0.4 && kana < letters * 0.1;
        }
    }
}
