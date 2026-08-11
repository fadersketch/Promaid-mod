package com.maidsmart.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * AI 记忆数据模型（对齐 maidsoulcore v2：paragraphs/entities/relations/episodes/profiles）。
 * 全部用 Gson 序列化为 jsonl 单行（UTF-8），可人工检查/恢复。
 * 时间字段约定：
 * - createdAt/updatedAt/lastAccessed：系统毫秒（衰减/最近访问用）
 * - eventTimeStart/eventTimeEnd：游戏 tick（事件时间窗/每日回顾过滤用）
 */
public final class AiMemoryModels {

    /** Gson：record 序列化（2.10+ 支持 record 反序列化） */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private AiMemoryModels() {
    }

    /** 记忆段落（对齐 MemoryParagraph 精简版） */
    public record Paragraph(
            String hash, String sourceType, String role, String content, String tags,
            String metadata, long createdAt, long updatedAt,
            long eventTimeStart, long eventTimeEnd,
            int salience, int accessCount, long lastAccessed,
            boolean permanent, boolean deleted, long protectedUntil) {

        /** 简化：从内容建段落（hash 由内容归一化生成，去重键） */
        public static Paragraph create(String sourceType, String role, String content,
                                       String tags, int salience, boolean permanent,
                                       long now, long gameTime) {
            return new Paragraph(hashOf(content), sourceType, role, clip(content, 300),
                    tags, "", now, now, gameTime, gameTime,
                    salience, 0, now, permanent, false, 0L);
        }

        public Paragraph touch(long now) {
            return new Paragraph(hash, sourceType, role, content, tags, metadata,
                    createdAt, now, eventTimeStart, eventTimeEnd,
                    salience, accessCount + 1, now, permanent, deleted, protectedUntil);
        }

        /** v1.5.251：附加世界标注（tags 加 world:<id>，已有则不重复） */
        public Paragraph withWorld(String worldId) {
            if (worldId == null || worldId.isEmpty() || (tags != null && tags.contains("world:"))) {
                return this;
            }
            String t = tags == null || tags.isBlank() ? "world:" + worldId : tags + ",world:" + worldId;
            return new Paragraph(hash, sourceType, role, content, t, metadata,
                    createdAt, updatedAt, eventTimeStart, eventTimeEnd,
                    salience, accessCount, lastAccessed, permanent, deleted, protectedUntil);
        }

        /** v1.5.251：世界标注（tags 里 world:xxx，无则 null） */
        public String worldOf() {
            if (tags == null) {
                return null;
            }
            for (String t : tags.split(",")) {
                String s = t.trim();
                if (s.startsWith("world:")) {
                    return s.substring(6);
                }
            }
            return null;
        }

        public Paragraph withSalience(int s) {
            return new Paragraph(hash, sourceType, role, content, tags, metadata,
                    createdAt, updatedAt, eventTimeStart, eventTimeEnd,
                    Math.max(salience, s), accessCount, lastAccessed, permanent, deleted, protectedUntil);
        }

        public boolean isPermanent() {
            return permanent || protectedUntil > System.currentTimeMillis();
        }
    }

    /** 实体（对齐 MemoryEntity：自动 upsert，appearanceCount 累计出现次数） */
    public record Entity(String hash, String name, String kind, int appearanceCount,
                         long createdAt, String metadata) {

        public static Entity create(String name, String kind, long now) {
            return new Entity(name.hashCode() + ":" + kind, name, kind, 1, now, "");
        }

        public Entity appeared(long now) {
            return new Entity(hash, name, kind, appearanceCount + 1, createdAt, metadata);
        }
    }

    /** 关系三元组（对齐 MemoryRelation：subject-predicate-object + 置信度） */
    public record Relation(String hash, String subject, String predicate, String object,
                           double confidence, String sourceParagraph, boolean permanent,
                           boolean inactive, long protectedUntil, int accessCount, long createdAt) {

        public static Relation create(String subject, String predicate, String object,
                                      double confidence, String sourceParagraph,
                                      boolean permanent, long now) {
            return new Relation(subject + "|" + predicate + "|" + object,
                    subject, predicate, clip(object, 120), confidence,
                    sourceParagraph, permanent, false, 0L, 0, now);
        }
    }

    /** 事件片段（对齐 MemoryEpisode：按事件时间窗聚合段落） */
    public record Episode(String episodeId, String source, String title, String summary,
                          String participants, String keywords, String evidenceIds,
                          long eventTimeStart, long eventTimeEnd, double confidence,
                          int paragraphCount, long createdAt, long updatedAt) {

        public static Episode create(String source, String title, String summary,
                                     long start, long end, double confidence,
                                     String evidenceId, long now) {
            return new Episode(title.hashCode() + ":" + start, source, clip(title, 40),
                    clip(summary, 200), "", "", evidenceId,
                    start, end, confidence, 1, now, now);
        }
    }

    /** 人物画像快照（对齐 PersonProfileSnapshot：personId + 画像文本 + 证据段落） */
    public record Profile(String personId, String profileText, String evidenceIds,
                          long updatedAt) {

        public static Profile create(String personId, String text, String evidenceId, long now) {
            return new Profile(personId, clip(text, 400), evidenceId, now);
        }
    }

    /** 元数据（提取位置/每日回顾标记） */
    public record Meta(long lastExtractedTime, int lastDailyDay) {
        public static Meta empty() {
            return new Meta(0L, -1);
        }
    }

    /** 内容归一化 hash（去重键：空白折叠 + 小写后取 hash 与长度） */
    /** v1.5.251：记忆元信息后缀——来源世界 + 获得时间（显示用） */
    public static String memoryMeta(Paragraph p) {
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String w = p.worldOf();
        if (w != null && !w.isEmpty()) {
            sb.append("\u00a78[").append(w).append("]");
        }
        String t = timeText(p.createdAt());
        if (!t.isEmpty()) {
            sb.append("\u00a78 ").append(t);
        }
        return sb.toString();
    }

    /** v1.5.251：时间戳格式化（MM-dd HH:mm） */
    public static String timeText(long millis) {
        if (millis <= 0) {
            return "";
        }
        try {
            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
            return java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault()).format(fmt);
        } catch (Exception e) {
            return "";
        }
    }

    public static String hashOf(String content) {
        String norm = normalize(content);
        return Integer.toHexString(norm.hashCode()) + ":" + norm.length();
    }

    /** 归一化：折叠空白 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    public static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
