package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;

/**
 * v1.5.198：对话语言强制——原版 TLM 每次对话把 chatLanguage = 客户端游戏语言
 *（MaidAIChatManager.tryToChat），系统提示词按 ${chat_language} 要求 LLM 输出
 *（"突然全是日语"根因在此：客户端语言/女仆 ChatLanguage 是日语）。
 * 配置 dialogue.outputLanguage 非空时强制该语言；留空 = 默认强制中文（zh_cn）。
 */
public final class ChatInfoUtil {
    private ChatInfoUtil() {
    }

    /** 取女仆 ChatClientInfo，并按配置强制输出语言（name/description 保持原样） */
    public static ChatClientInfo fromMaid(EntityMaid maid) {
        ChatClientInfo info = ChatClientInfo.fromMaid(maid);
        String lang = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
        if (lang == null || lang.isBlank()) {
            lang = "zh_cn";
        }
        if (!lang.equals(info.language())) {
            info = new ChatClientInfo(lang, info.name(), info.description());
        }
        return info;
    }
}
