package com.maidsmart.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系知识图谱·多跳召回（移植自 maidsoulcore v3 {@code MemoryGraphStore} 的
 * {@code findPaths / personalizedPageRank} 语义的精简版）。
 *
 * <p>旧版只有"一跳"：query 命中了某条三元组的 subject/predicate/object 才召回它。于是
 * "主人的朋友是谁？"这种问题，只要"主人-朋友-小明"和"小明-送过-我礼物"是两条分开的关系，
 * 就答不出来（跳不过去）。这里把关系图建成邻接表，从 query 命中的实体出发做有限深度传播
 * （每跳衰减 0.7，类似 personalizedPageRank 的随机游走收敛值），把沿途关系按传播分排序召回。
 *
 * <p>刻意不做完整 PageRank 迭代：关系数量小（几十条），BFS 传播已足够，且每次检索重算也不过
 * 微秒级——不值得维护一套迭代/收敛状态。两树逐字相同。
 */
public final class AiMemoryGraph {

    /** 图上的一条关系（从 {@link AiMemoryModels.Relation} 投影而来，去掉存储细节）。 */
    public record Rel(String subject, String predicate, String object, double confidence) {
    }

    /** 一条多跳命中：关系本体 + 跳数 + 传播分。 */
    public record Path(String subject, String predicate, String object, int hops, double score) {
    }

    private static final double HOP_DECAY = 0.7;

    private AiMemoryGraph() {
    }

    /**
     * 从 query 命中的实体出发，在 {@code maxHops} 跳内传播，返回沿途关系（按传播分降序）。
     *
     * @param rels    关系集（已过滤 inactive）
     * @param query   查询文本
     * @param maxHops 最大跳数（1 = 只含直接命中，等价旧行为）
     */
    public static List<Path> multiHop(List<Rel> rels, String query, int maxHops, int limit) {
        List<Path> out = new ArrayList<>();
        if (rels == null || rels.isEmpty() || query == null || query.isBlank()) {
            return out;
        }
        Set<String> qTokens = AiMemoryTokens.queryTokens(query);
        String qNorm = AiMemoryModels.normalize(query);
        // 邻接：实体 → 与之相连的关系下标（无向）
        Map<String, List<Integer>> adj = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) {
            Rel r = rels.get(i);
            adj.computeIfAbsent(r.subject(), k -> new ArrayList<>()).add(i);
            adj.computeIfAbsent(r.object(), k -> new ArrayList<>()).add(i);
        }
        // 种子：实体名与 query 有词项交集，或实体名整体出现在 query 里
        Map<String, Double> nodeScore = new HashMap<>();
        for (String entity : adj.keySet()) {
            if (entity == null || entity.isBlank()) {
                continue;
            }
            double s = 0;
            String eNorm = AiMemoryModels.normalize(entity);
            if (!eNorm.isEmpty() && qNorm.contains(eNorm)) {
                s = 1.0;
            } else {
                Set<String> eTokens = AiMemoryTokens.queryTokens(entity);
                for (String t : eTokens) {
                    if (qTokens.contains(t)) {
                        s += 0.6;
                    }
                }
            }
            if (s > 0) {
                nodeScore.put(entity, s);
            }
        }
        if (nodeScore.isEmpty()) {
            return out;
        }
        // BFS 传播
        int hopsLimit = Math.max(1, maxHops);
        Deque<String> frontier = new ArrayDeque<>(nodeScore.keySet());
        Map<String, Integer> hopOf = new HashMap<>();
        for (String s : nodeScore.keySet()) {
            hopOf.put(s, 0);
        }
        Set<String> visited = new HashSet<>(nodeScore.keySet());
        while (!frontier.isEmpty()) {
            String cur = frontier.poll();
            int h = hopOf.getOrDefault(cur, 0);
            if (h >= hopsLimit) {
                continue;
            }
            double curScore = nodeScore.getOrDefault(cur, 0.0);
            List<Integer> links = adj.get(cur);
            if (links == null) {
                continue;
            }
            double share = curScore * HOP_DECAY / Math.max(1, links.size());
            for (int ri : links) {
                Rel r = rels.get(ri);
                String other = r.subject().equals(cur) ? r.object() : r.subject();
                if (other == null || other.isBlank()) {
                    continue;
                }
                nodeScore.merge(other, share, Double::sum);
                if (visited.add(other)) {
                    hopOf.put(other, h + 1);
                    frontier.add(other);
                }
            }
        }
        // 收集沿途关系（去重：同一关系只按最高分出现一次）
        Map<String, Path> best = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) {
            Rel r = rels.get(i);
            double ss = nodeScore.getOrDefault(r.subject(), 0.0);
            double os = nodeScore.getOrDefault(r.object(), 0.0);
            double raw = Math.max(ss, os);
            if (raw <= 0) {
                continue;
            }
            int hops = Math.min(hopOf.getOrDefault(r.subject(), 0), hopOf.getOrDefault(r.object(), 0));
            double score = raw * Math.max(0.1, r.confidence());
            String key = r.subject() + "|" + r.predicate() + "|" + r.object();
            Path prev = best.get(key);
            if (prev == null || score > prev.score()) {
                best.put(key, new Path(r.subject(), r.predicate(), r.object(), hops, score));
            }
        }
        out.addAll(best.values());
        out.sort(Comparator.comparingDouble(Path::score).reversed());
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /** 图上的实体全集（诊断/调试用）。 */
    public static Set<String> entities(List<Rel> rels) {
        Set<String> out = new LinkedHashSet<>();
        if (rels == null) {
            return out;
        }
        for (Rel r : rels) {
            if (r.subject() != null && !r.subject().isBlank()) {
                out.add(r.subject());
            }
            if (r.object() != null && !r.object().isBlank()) {
                out.add(r.object());
            }
        }
        return out;
    }
}
