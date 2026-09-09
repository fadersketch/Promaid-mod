package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆检索（v1.5.86，对齐 maidsoulcore v2 的 mixed 检索 + RRF 融合）。
 * 三路召回：
 * - 段落关键词打分（n-gram 命中 + salience 加成）
 * - 最近段落（lastAccessed/createdAt，最近优先）
 * - 画像证据（profiles 文本匹配）
 * RRF 融合：score = Σ 1/(k + rank)，k=60；取 top N 渲染。
 *
 * v1.5.95 增强：
 * - uni+bi-gram 混合（旧版只有 2-gram，中文单字关键词如"茶"召回差）
 * - 关系三元组作为第四路（query 命中 subject/predicate/object → 关系进结果）
 * - deleted 段落已在 store.paragraphs() 过滤（冲突覆盖的旧段落不参与检索）
 */
public final class AiMemorySearch {
    /** v1.5.102：RRF 融合参数从配置面板读取（memory.rrfK） */

    private AiMemorySearch() {
    }

    /** 检索结果条目 */
    public record Hit(String type, String content, double score) {
    }

    /** 检索（五路召回 + RRF 融合） */
    public static List<Hit> search(AiMemoryStore store, String query, int limit) {
        Map<String, Hit> fused = new HashMap<>();
        addRrf(fused, searchParagraphs(store, query), "p");
        addRrf(fused, searchRecent(store), "r");
        addRrf(fused, searchProfiles(store, query), "f");
        addRrf(fused, searchRelations(store, query), "e"); // v1.5.95：关系路
        addRrf(fused, searchIndex(store, query), "i"); // 多级记忆索引路（日/3日/周/月日记）
        List<Hit> out = new ArrayList<>(fused.values());
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /** 检索结果渲染为 prompt 文本 */
    public static String render(List<Hit> hits, int limit) {
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Hit h : hits) {
            if (n >= limit) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(h.content());
            n++;
        }
        return sb.toString();
    }

    // ---------- 召回路 ----------

    /** 路 1：段落关键词（uni+bi-gram 命中 + salience 加成） */
    private static List<Hit> searchParagraphs(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        Set<String> qgrams = ngrams(query);
        if (qgrams.isEmpty()) {
            return out;
        }
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            // v1.5.191：被主人否定的段落（error_mark/error_affected）不参与检索
            if (com.maidsmart.memory.AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            Set<String> grams = ngrams(p.content());
            int hit = 0;
            for (String g : qgrams) {
                if (grams.contains(g)) {
                    hit++;
                }
            }
            if (hit == 0) {
                continue;
            }
            // v1.5.95：命中数 × 2 + 长度加权（短段落更相关）+ salience 加成
            double score = hit * 2.0 + p.salience() * 0.5 + 1.0 / (1.0 + Math.abs(p.content().length() - query.length()) / 20.0);
            out.add(new Hit("paragraph", "（重要度" + p.salience() + "）" + p.content(), score));
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        return out;
    }

    /** 路 2：最近段落（最近访问/创建优先） */
    private static List<Hit> searchRecent(AiMemoryStore store) {
        List<AiMemoryModels.Paragraph> list = new ArrayList<>(store.paragraphs());
        // v1.5.191：被主人否定的段落不进"最近"路（避免错误内容反复被召回）
        list.removeIf(com.maidsmart.memory.AiMemoryStore::hasErrorTag);
        list.sort(Comparator.comparingLong(AiMemoryModels.Paragraph::lastAccessed).reversed());
        List<Hit> out = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : list) {
            out.add(new Hit("recent", "（重要度" + p.salience() + "）" + p.content(), 1.0));
        }
        return out;
    }

    /** 路 3：画像证据（profileText 匹配 query 词） */
    private static List<Hit> searchProfiles(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        Set<String> qgrams = ngrams(query);
        for (AiMemoryModels.Profile pr : store.profiles()) {
            Set<String> grams = ngrams(pr.profileText());
            int hit = 0;
            for (String g : qgrams) {
                if (grams.contains(g)) {
                    hit++;
                }
            }
            if (hit > 0) {
                out.add(new Hit("profile", "主人的画像：" + pr.profileText(), hit * 2.0));
            }
        }
        return out;
    }

    /** v1.5.95：路 4——关系三元组（query 命中 subject/predicate/object 任意部分） */
    private static List<Hit> searchRelations(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        Set<String> qgrams = ngrams(query);
        for (AiMemoryModels.Relation r : store.relations()) {
            if (r.inactive()) {
                continue;
            }
            Set<String> grams = ngrams(r.subject() + r.predicate() + r.object());
            int hit = 0;
            for (String g : qgrams) {
                if (grams.contains(g)) {
                    hit++;
                }
            }
            if (hit > 0) {
                out.add(new Hit("relation",
                        "关系：" + r.subject() + r.predicate() + r.object()
                                + "（置信度" + String.format(java.util.Locale.ROOT, "%.1f", r.confidence()) + "）",
                        hit * 2.5)); // 关系命中权重更高（对话最重要的参考）
            }
        }
        return out;
    }

    // ---------- RRF 融合 ----------

    /**
     * 路 5：多级记忆索引（日/3日/周/月日记式摘要，移植自 Sphantosis query_memory_index
     * 的检索语义）——query 命中日记内容 → 该跨度日记整体作为一条召回（日记是压缩
     * 摘要，命中即覆盖整段时间线的记忆；详细原文由 query_memory_index 工具二次回查）。
     */
    private static List<Hit> searchIndex(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            return out;
        }
        Set<String> qgrams = ngrams(query);
        if (qgrams.isEmpty()) {
            return out;
        }
        for (AiMemoryIndexStore.IndexRecord r : store.index().all()) {
            Set<String> grams = ngrams(r.content());
            int hit = 0;
            for (String g : qgrams) {
                if (grams.contains(g)) {
                    hit++;
                }
            }
            if (hit > 0) {
                out.add(new Hit("index",
                        "【" + r.level() + "记 第" + r.startDay() + "~" + r.endDay() + "天】"
                                + AiMemoryModels.clip(r.content(), 120),
                        hit * 2.0));
            }
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        return out;
    }

    private static void addRrf(Map<String, Hit> fused, List<Hit> ranked, String prefix) {
        int rank = 1;
        for (Hit h : ranked) {
            double contrib = 1.0 / (com.maidsmart.config.MaidSmartConfig.MEMORY_RRF_K.get() + rank);
            String key = prefix + "|" + h.content();
            Hit old = fused.get(key);
            if (old == null) {
                fused.put(key, new Hit(h.type(), h.content(), contrib));
            } else {
                fused.put(key, new Hit(old.type(), old.content(), old.score() + contrib));
            }
            rank++;
        }
    }

    /** v1.5.95：uni+bi-gram 混合（单字也参与匹配，中文召回更好） */
    private static Set<String> ngrams(String s) {
        Set<String> out = new HashSet<>();
        String norm = AiMemoryModels.normalize(s).replaceAll("[\\p{Punct}。，、！？；：]", "");
        for (int i = 0; i < norm.length(); i++) {
            out.add("u:" + norm.charAt(i)); // unigram
        }
        for (int i = 0; i + 1 < norm.length(); i++) {
            out.add("b:" + norm.substring(i, i + 2)); // bigram
        }
        return out;
    }
}
