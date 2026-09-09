package com.maidsmart.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 写入策略（v1.5.86，对齐 maidsoulcore 的 MemoryWriteStrategy）：
 * 按类型 + tags 决策记忆分层 / salience 下限 / 保护 / 永久。
 *
 * - layer：short_context（默认）→ user_profile / relationship_event / world_fact /
 *   self_memory / repair_debt / summary
 * - salience 下限：关系/承诺 ≥8、偏好/边界/情绪 ≥7、世界事实 ≥6
 * - protect：关系/偏好/承诺/情绪类不参与衰减淘汰
 * - permanent：salience ≥9 且 边界/承诺/关系/自省类 → 永久
 */
public final class AiMemoryWriteStrategy {

    /** 写入决策结果 */
    public record Plan(String layer, int salience, boolean protect, boolean permanent, List<String> tags) {
    }

    private AiMemoryWriteStrategy() {
    }

    public static Plan plan(AiMemoryType type, int salience, List<String> tags) {
        Set<String> t = normalizeTags(tags);
        String layer = layerFrom(type, t);
        int s = Math.max(1, Math.min(10, salience));
        s = Math.max(s, salienceFloor(type, t));
        boolean protect = shouldProtect(type, t);
        boolean permanent = shouldPermanent(s, t);
        // 把 layer 作为标签之一写回（对齐 maidsoul：tags.add(layer)）
        t.add(layer);
        return new Plan(layer, s, protect, permanent, List.copyOf(t));
    }

    private static String layerFrom(AiMemoryType type, Set<String> tags) {
        if (tags.contains("world_fact") || type == AiMemoryType.WORLD) {
            return "world_fact";
        }
        if (tags.contains("self_memory")) {
            return "self_memory";
        }
        if (tags.contains("repair_debt") || tags.contains("affect_event") || type == AiMemoryType.EMOTION) {
            return "repair_debt";
        }
        if (tags.contains("relationship_event") || tags.contains("promise")
                || type == AiMemoryType.RELATION || type == AiMemoryType.PROMISE) {
            return "relationship_event";
        }
        if (tags.contains("user_profile") || tags.contains("preference") || tags.contains("boundary")
                || type == AiMemoryType.PREFERENCE) {
            return "user_profile";
        }
        if (tags.contains("summary") || type == AiMemoryType.SUMMARY) {
            return "summary";
        }
        return "short_context";
    }

    /** salience 下限（对齐 maidsoul salienceFrom 的类型抬升） */
    private static int salienceFloor(AiMemoryType type, Set<String> tags) {
        if (type == AiMemoryType.PROMISE || type == AiMemoryType.RELATION
                || tags.contains("relationship_event")) {
            return 8;
        }
        if (type == AiMemoryType.PREFERENCE || tags.contains("preference") || tags.contains("boundary")) {
            return 7;
        }
        if (type == AiMemoryType.EMOTION || tags.contains("repair_debt") || tags.contains("affect_event")) {
            return 7;
        }
        if (type == AiMemoryType.WORLD || tags.contains("world_fact")) {
            return 6;
        }
        return 1;
    }

    private static boolean shouldProtect(AiMemoryType type, Set<String> tags) {
        return type == AiMemoryType.PREFERENCE || type == AiMemoryType.PROMISE
                || type == AiMemoryType.RELATION || type == AiMemoryType.EMOTION
                || tags.contains("boundary") || tags.contains("promise")
                || tags.contains("relationship_event") || tags.contains("repair_debt")
                || tags.contains("self_memory") || tags.contains("correction");
    }

    private static boolean shouldPermanent(int salience, Set<String> tags) {
        if (salience < 9) {
            return false;
        }
        return tags.contains("boundary") || tags.contains("promise")
                || tags.contains("relationship_event") || tags.contains("self_memory");
    }

    private static Set<String> normalizeTags(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        if (tags == null) {
            return out;
        }
        for (String t : tags) {
            if (t != null && !t.isBlank()) {
                out.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
