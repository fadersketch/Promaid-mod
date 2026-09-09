package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日关心点（v1.1.0，借鉴 maidsoulcore DailyMemoryConsolidator 的 carePoints）。
 *
 * 纯规则零 LLM：从情绪状态 + 记忆画像（边界/风格/情绪残留）推导"下次该怎么
 * 对主人"的行动建议，附加到每日回顾，随投影注入对话；主动会话（TOPIC_PUSH
 * 读 daily 标签段落）会自动把关心点当作话题素材——实现记忆 ↔ 主动对话联动。
 */
public final class CarePointGenerator {
    /** 最多生成几条（防每日回顾刷屏） */
    private static final int MAX_POINTS = 3;

    private CarePointGenerator() {
    }

    /**
     * 生成关心点列表（有序：情绪残留优先，其次边界/风格）。
     *
     * @param store  女仆记忆存储（扫描边界/风格/情绪残留段落）
     * @param affect 当前情绪快照（可为 null——纯规则不依赖）
     */
    public static List<String> generate(AiMemoryStore store,
                                        com.maidsmart.affect.AffectManager.AffectProfile affect) {
        List<String> out = new ArrayList<>();
        if (store == null) {
            return out;
        }
        boolean hasBoundary = false;
        boolean hasStyle = false;
        boolean hasRepair = false;
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            String tags = p.tags() == null ? "" : p.tags();
            if (tags.contains("boundary")) {
                hasBoundary = true;
            }
            if (tags.contains("conversation_style") || tags.contains("style")) {
                hasStyle = true;
            }
            if (tags.contains("repair_debt") || tags.contains("affect_event")) {
                hasRepair = true;
            }
        }
        if (affect != null) {
            if (affect.hurtDebt > 0.3 || affect.repairDebt > 0.3) {
                out.add("关系里还有受伤或生气的残留，不要假装一切已经没发生");
            }
            if (affect.valence < 0.25) {
                out.add("主人心情偏低时先轻轻确认状态，不要一上来讲方案");
            }
            if (affect.intimacy > 0.6 && affect.longing > 0.5 && affect.conflict < 0.1) {
                out.add("可以更温柔更粘人一点，但不要无视当前事实");
            }
        }
        if (hasBoundary) {
            out.add("玩家表达过明确边界，后续回应需要优先尊重");
        }
        if (hasRepair) {
            out.add("对方情绪烦躁时需要先被接住，再慢慢展开话题");
        }
        if (hasStyle) {
            out.add("玩家重视自然、有连续性、不过度刷屏的聊天节奏");
        }
        return out.size() > MAX_POINTS ? out.subList(0, MAX_POINTS) : out;
    }
}
