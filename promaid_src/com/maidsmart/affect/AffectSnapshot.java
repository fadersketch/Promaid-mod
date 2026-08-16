package com.maidsmart.affect;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 情绪快照（v1.1.0，借鉴 maidsoulcore AffectSnapshot 精简版）。
 *
 * 写入记忆段落 Paragraph.metadata 的紧凑 JSON（{"affect":{...}}），
 * 让每条记忆带"当时的心情"，供回看/分析/未来情绪相关检索。
 * 纯数据类，零 MC 依赖；字段 = AffectProfile 的 8 个数值维度。
 */
public record AffectSnapshot(
        double valence, double arousal, double dominance,
        double intimacy, double conflict, double longing,
        double hurtDebt, double repairDebt) {

    /** 从 AffectProfile 生成快照（null 返回 null） */
    public static AffectSnapshot from(AffectManager.AffectProfile p) {
        if (p == null) {
            return null;
        }
        return new AffectSnapshot(p.valence, p.arousal, p.dominance,
                p.intimacy, p.conflict, p.longing, p.hurtDebt, p.repairDebt);
    }

    /** 紧凑 JSON（两位小数，控 token/体积）：{"affect":{"v":0.30,"a":0.40,...}} */
    public String toJson() {
        return "{\"affect\":{"
                + "\"v\":" + fmt(valence)
                + ",\"a\":" + fmt(arousal)
                + ",\"d\":" + fmt(dominance)
                + ",\"i\":" + fmt(intimacy)
                + ",\"c\":" + fmt(conflict)
                + ",\"l\":" + fmt(longing)
                + ",\"hurt\":" + fmt(hurtDebt)
                + ",\"repair\":" + fmt(repairDebt)
                + "}}";
    }

    /** 从女仆记忆目录读 affect.json 生成快照；无文件/异常返回 null */
    public static AffectSnapshot load(Path maidDir) {
        try {
            Path f = maidDir.resolve(AffectManager.AFFECT_FILE);
            if (!Files.exists(f)) {
                return null;
            }
            AffectManager.AffectProfile p = com.maidsmart.memory.AiMemoryModels.GSON.fromJson(
                    Files.readString(f), AffectManager.AffectProfile.class);
            return p == null ? null : from(p);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从段落 metadata 解析快照；无/异常返回 null */
    @SuppressWarnings("unchecked")
    public static AffectSnapshot fromMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            java.util.Map<String, Object> root = com.maidsmart.memory.AiMemoryModels.GSON.fromJson(
                    metadata, java.util.Map.class);
            if (root == null) {
                return null;
            }
            Object affect = root.get("affect");
            if (!(affect instanceof java.util.Map)) {
                return null;
            }
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) affect;
            return new AffectSnapshot(
                    num(m.get("v")), num(m.get("a")), num(m.get("d")),
                    num(m.get("i")), num(m.get("c")), num(m.get("l")),
                    num(m.get("hurt")), num(m.get("repair")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
