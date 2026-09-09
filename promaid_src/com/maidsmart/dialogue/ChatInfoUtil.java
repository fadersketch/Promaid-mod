package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;

import java.util.Collections;
import java.util.List;

/**
 * v1.5.198：对话语言强制——原版 TLM 每次对话把 chatLanguage = 客户端游戏语言
 *（MaidAIChatManager.tryToChat），系统提示词按 ${chat_language} 要求 LLM 输出
 *（"突然全是日语"根因在此：客户端语言/女仆 ChatLanguage 是日语）。
 * 配置 dialogue.outputLanguage 非空时强制该语言；留空 = 默认强制中文（zh_cn）。
 *
 * v1.1.0【专用服务器崩溃修复】：不再调用 TLM 的 ChatClientInfo.fromMaid(EntityMaid)——
 * 该方法标注 @OnlyIn(Dist.CLIENT)（内部用 Minecraft.getInstance() 读客户端语言、
 * CustomPackLoader.MAID_MODELS 读模型描述），Forge 专用服务器加载 TLM 时会被
 * RuntimeDistCleaner 直接剥离 → 服务端调用即 NoSuchMethodError → 服务器 tick 崩溃
 *（粉丝服崩报告实证：ChatInfoUtil.fromMaid ← ProactiveDialogueManager.tryScanFire）。
 * 改为服务端安全地自行构造：语言取配置（默认 zh_cn），名字取女仆实体名，描述留空。
 */
public final class ChatInfoUtil {
    private ChatInfoUtil() {
    }

    /** 取女仆 ChatClientInfo，并按配置强制输出语言（name/description 保持原样） */
    public static ChatClientInfo fromMaid(EntityMaid maid) {
        String lang = MaidSmartConfig.DIALOGUE_OUTPUT_LANGUAGE.get();
        if (lang == null || lang.isBlank()) {
            lang = "zh_cn";
        }
        String name = maid.m_7755_() != null ? maid.m_7755_().getString() : "";
        List<String> description = Collections.emptyList();
        return new ChatClientInfo(lang, name, description);
    }
}
