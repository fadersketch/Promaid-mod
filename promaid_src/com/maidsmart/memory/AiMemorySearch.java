package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 记忆检索（v1.5.86 起，对齐 maidsoulcore；实测八百二十九c 补齐 v3 的检索能力）。
 *
 * <p>多路召回 + RRF 融合（score = Σ 1/(k + rank)，k 见 memory.rrfK）：
 * <ul>
 *   <li><b>p 段落</b>：{@link AiMemoryBm25}（原来手搓 n-gram 打分 → 换成 BM25 + CJK 分词）；</li>
 *   <li><b>r 最近</b>：最近访问/创建优先；</li>
 *   <li><b>f 画像</b>：结构化画像条目 + 画像文本命中 query 词；</li>
 *   <li><b>e 关系</b>：{@link AiMemoryGraph} 多跳（原来只有一跳词面命中）；</li>
 *   <li><b>i 索引</b>：多级记忆索引（日/3日/周/月日记）；</li>
 *   <li><b>t 时间</b>：{@link AiMemoryTimeQuery} 把"昨天/上周/N天前"解析成游戏日窗口后按时距排序；</li>
 *   <li><b>v 语义</b>：{@link AiMemoryVectorIndex} 余弦相似（需调用方提供查询向量，见下）。</li>
 * </ul>
 *
 * <p>关于语义路：算查询向量要发一次 embeddings 请求，**不能**在服务端 tick/主线程里同步做。
 * 所以这里不主动算；调用方拿到查询向量后走 {@link #search(AiMemoryStore, String, int, float[])}
 * 即可让语义路参与融合，否则该路贡献为 0、其余照常（优雅降级）。两树逐字相同。
 */
public final class AiMemorySearch {

    /** store → BM25 索引（弱引用防泄漏；签名变了懒重建）。 */
    private static final Map<AiMemoryStore, AiMemoryBm25> BM25 =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AiMemorySearch() {
    }

    /** 检索结果条目 */
    public record Hit(String type, String content, double score) {
    }

    /** 检索（六/七路召回 + RRF 融合；不含语义路）。 */
    public static List<Hit> search(AiMemoryStore store, String query, int limit) {
        return search(store, query, limit, null);
    }

    /** 检索（带查询向量的语义路版本）。 */
    public static List<Hit> search(AiMemoryStore store, String query, int limit, float[] queryVector) {
        Map<String, Hit> fused = new HashMap<>();
        addRrf(fused, searchParagraphsBm25(store, query), "p");
        addRrf(fused, searchRecent(store), "r");
        addRrf(fused, searchProfiles(store, query), "f");
        addRrf(fused, searchRelations(store, query), "e");
        addRrf(fused, searchIndex(store, query), "i");
        addRrf(fused, searchTemporal(store, query), "t");
        if (queryVector != null) {
            addRrf(fused, searchSemantic(store, queryVector), "v");
        }
        List<Hit> out = new ArrayList<>(fused.values());
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /** 语义路单列（供需要"纯语义"召回的调用方直接用）。 */
    public static List<Hit> semanticHits(AiMemoryStore store, float[] queryVector, int limit) {
        return searchSemantic(store, queryVector, limit);
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

    /** 路 1：段落 BM25（词项 IDF + 文档长度归一 + 小 salience 破平局）。 */
    private static List<Hit> searchParagraphsBm25(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        List<AiMemoryModels.Paragraph> paras = store.paragraphs();
        List<AiMemoryBm25.Doc> docs = new ArrayList<>(paras.size());
        Map<String, AiMemoryModels.Paragraph> byId = new HashMap<>();
        for (AiMemoryModels.Paragraph p : paras) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue; // 被主人否定的段落不参与检索
            }
            docs.add(new AiMemoryBm25.Doc(p.hash(), p.content(), p.salience()));
            byId.put(p.hash(), p);
        }
        if (docs.isEmpty()) {
            return out;
        }
        AiMemoryBm25 idx = BM25.computeIfAbsent(store, s -> new AiMemoryBm25());
        idx.ensureDocuments(docs);
        for (AiMemoryBm25.Hit h : idx.search(query, 12)) {
            AiMemoryModels.Paragraph p = byId.get(h.id());
            if (p != null) {
                out.add(new Hit("paragraph", "（重要度" + p.salience() + "）" + p.content(), h.score()));
            }
        }
        return out;
    }

    /** 路 2：最近段落（最近访问/创建优先） */
    private static List<Hit> searchRecent(AiMemoryStore store) {
        List<AiMemoryModels.Paragraph> list = new ArrayList<>(store.paragraphs());
        list.removeIf(AiMemoryStore::hasErrorTag);
        list.sort(Comparator.comparingLong(AiMemoryModels.Paragraph::lastAccessed).reversed());
        List<Hit> out = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : list) {
            out.add(new Hit("recent", "（重要度" + p.salience() + "）" + p.content(), 1.0));
        }
        return out;
    }

    /** 路 3：画像证据（结构化条目 + 画像文本匹配 query 词） */
    private static List<Hit> searchProfiles(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        Set<String> qgrams = ngrams(query);
        for (AiMemoryModels.ProfileItem it : store.profileItems()) {
            Set<String> grams = ngrams(it.content() + it.key());
            int hit = countHits(qgrams, grams);
            if (hit > 0) {
                out.add(new Hit("profile_item",
                        "主人的" + it.category() + "：" + it.content()
                                + "（置信度" + it.confidence() + "）", hit * 2.5 + it.confidence() * 0.3));
            }
        }
        for (AiMemoryModels.Profile pr : store.profiles()) {
            Set<String> grams = ngrams(pr.profileText());
            int hit = countHits(qgrams, grams);
            if (hit > 0) {
                out.add(new Hit("profile", "主人的画像：" + pr.profileText(), hit * 2.0));
            }
        }
        return out;
    }

    /** 路 4：关系三元组——图多跳（query 命中实体 → maxHops 跳内传播） */
    private static List<Hit> searchRelations(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_GRAPH_ENABLE.get()) {
            // 关图 → 回退旧的一跳词面命中
            return searchRelationsOneHop(store, query);
        }
        List<AiMemoryGraph.Rel> rels = new ArrayList<>();
        for (AiMemoryModels.Relation r : store.relations()) {
            if (r.inactive()) {
                continue;
            }
            rels.add(new AiMemoryGraph.Rel(r.subject(), r.predicate(), r.object(), r.confidence()));
        }
        int hops = com.maidsmart.config.MaidSmartConfig.MEMORY_GRAPH_HOPS.get();
        for (AiMemoryGraph.Path p : AiMemoryGraph.multiHop(rels, query, hops, 6)) {
            out.add(new Hit("relation",
                    "关系：" + p.subject() + p.predicate() + p.object()
                            + "（置信度" + String.format(java.util.Locale.ROOT, "%.1f", p.score())
                            + (p.hops() > 1 ? "，" + p.hops() + "跳" : "") + "）",
                    p.score() * 2.5));
        }
        return out;
    }

    /** 旧版一跳词面命中（关图时回退）。 */
    private static List<Hit> searchRelationsOneHop(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        Set<String> qgrams = ngrams(query);
        for (AiMemoryModels.Relation r : store.relations()) {
            if (r.inactive()) {
                continue;
            }
            Set<String> grams = ngrams(r.subject() + r.predicate() + r.object());
            int hit = countHits(qgrams, grams);
            if (hit > 0) {
                out.add(new Hit("relation",
                        "关系：" + r.subject() + r.predicate() + r.object()
                                + "（置信度" + String.format(java.util.Locale.ROOT, "%.1f", r.confidence()) + "）",
                        hit * 2.5));
            }
        }
        return out;
    }

    /** 路 5：多级记忆索引（日/3日/周/月日记式摘要）。 */
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
            int hit = countHits(qgrams, ngrams(r.content()));
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

    /**
     * 路 6：时间路——把查询里的相对时间（昨天/上周/N天前…）解析成游戏日窗口，按事件日与窗口的
     * 距离排序。"昨天我们在哪里找到铁矿"这种问法，答案在"昨天"的段落里，词面根本对不上，
     * 只有时间路能命中。
     */
    private static List<Hit> searchTemporal(AiMemoryStore store, String query) {
        List<Hit> out = new ArrayList<>();
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_TEMPORAL_ENABLE.get()
                || !AiMemoryTimeQuery.hasCue(query)) {
            return out;
        }
        List<AiMemoryModels.Paragraph> paras = store.paragraphs();
        if (paras.isEmpty()) {
            return out;
        }
        long nowTick = 0L;
        for (AiMemoryModels.Paragraph p : paras) {
            nowTick = Math.max(nowTick, p.eventTimeEnd());
        }
        long nowDay = AiMemoryTimeQuery.gameDayOf(nowTick);
        AiMemoryTimeQuery.Window w = AiMemoryTimeQuery.resolve(query, nowDay);
        if (w == null) {
            return out; // "那天/曾经"这类无锚点 → 回退其它路
        }
        for (AiMemoryModels.Paragraph p : paras) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            double prox = AiMemoryTimeQuery.proximity(p.eventTimeStart(), w, nowDay);
            if (prox <= 0) {
                continue;
            }
            out.add(new Hit("temporal", "（重要度" + p.salience() + "）" + p.content(), prox * 3.0));
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    /** 路 7：语义路（余弦相似；需调用方提供查询向量）。 */
    private static List<Hit> searchSemantic(AiMemoryStore store, float[] queryVector) {
        return searchSemantic(store, queryVector, 8);
    }

    private static List<Hit> searchSemantic(AiMemoryStore store, float[] queryVector, int limit) {
        List<Hit> out = new ArrayList<>();
        if (queryVector == null || !com.maidsmart.config.MaidSmartConfig.MEMORY_VECTOR_ENABLE.get()) {
            return out;
        }
        for (AiMemoryVectorIndex.Hit h : store.vector().search(queryVector, limit)) {
            AiMemoryModels.Paragraph p = store.paragraphByHash(h.id());
            if (p != null && !p.deleted() && !AiMemoryStore.hasErrorTag(p)) {
                out.add(new Hit("semantic", "（重要度" + p.salience() + "）" + p.content(), h.score() * 4.0));
            }
        }
        return out;
    }

    // ---------- RRF 融合 ----------

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

    private static int countHits(Set<String> q, Set<String> doc) {
        int hit = 0;
        for (String g : q) {
            if (doc.contains(g)) {
                hit++;
            }
        }
        return hit;
    }

    /** uni+bi-gram 混合（画像/索引路仍用；段落路已换 BM25）。 */
    private static Set<String> ngrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) {
            return out;
        }
        String norm = AiMemoryModels.normalize(s).replaceAll("[\\p{Punct}。，、！？；：]", "");
        for (int i = 0; i < norm.length(); i++) {
            out.add("u:" + norm.charAt(i));
        }
        for (int i = 0; i + 1 < norm.length(); i++) {
            out.add("b:" + norm.substring(i, i + 2));
        }
        return out;
    }
}
