package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 稀疏索引（移植自 maidsoulcore v3 {@code MemoryV3SparseIndex}）。
 *
 * <p>替换旧版 {@link AiMemorySearch} 里「n-gram 命中数 × 2 + salience × 0.5 + 长度项」的手搓打分：
 * 那套没有 IDF（常见字"的""了"与关键词同权）也没有文档长度归一（长段落天然命中多）。BM25 两个
 * 都补上，配合 {@link AiMemoryTokens} 的 CJK 分词，中文召回质量提升最明显。
 *
 * <p>公式（标准 BM25，k1=1.2、b=0.75）：
 * {@code score += idf(t) * tf*(k1+1) / (tf + k1*(1 - b + b*dl/avgdl))}；
 * 其中 {@code idf(t) = ln(1 + (N - df + 0.5)/(df + 0.5))}。额外叠加很小的 salience 加成
 * （0.15/级）做破平局——不改变 BM25 的相对次序，只在分数接近时让更重要的记忆靠前。
 *
 * <p>索引按「文档集合签名（id 序列哈希）」懒重建：{@link AiMemorySearch} 每次检索把当前段落
 * 投影成文档列表传入，签名没变就直接用缓存，避免每轮全量重算 DF。线程安全（synchronized）。
 * 两树逐字相同。
 */
public final class AiMemoryBm25 {

    /** BM25 调参（与 maidsoulcore 同款默认值）。 */
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 一篇可检索文档：id（段落 hash）+ 正文 + 重要度（破平局用）。 */
    public record Doc(String id, String content, int salience) {
    }

    /** 一条命中：文档 id + BM25 分数。 */
    public record Hit(String id, double score) {
    }

    private final Map<String, Map<String, Integer>> postings = new HashMap<>(); // term -> docId -> tf
    private final Map<String, Integer> docLen = new HashMap<>();
    private final Map<String, Integer> docSalience = new HashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgdl = 1.0;
    private int docCount = 0;
    private String signature = "";

    /** 用当前文档集重建索引（若签名未变则跳过）。 */
    public synchronized void ensureDocuments(List<Doc> docs) {
        String sig = signatureOf(docs);
        if (!sig.isEmpty() && sig.equals(this.signature)) {
            return;
        }
        this.postings.clear();
        this.docLen.clear();
        this.docSalience.clear();
        this.df.clear();
        long totalLen = 0;
        for (Doc d : docs) {
            List<String> toks = AiMemoryTokens.tokenize(d.content());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : toks) {
                tf.merge(t, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                this.postings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(d.id(), e.getValue());
                this.df.merge(e.getKey(), 1, Integer::sum);
            }
            this.docLen.put(d.id(), Math.max(1, toks.size()));
            this.docSalience.put(d.id(), d.salience());
            totalLen += Math.max(1, toks.size());
        }
        this.docCount = docs.size();
        this.avgdl = this.docCount == 0 ? 1.0 : (double) totalLen / this.docCount;
        this.signature = sig;
    }

    /** 检索：返回按 BM25 降序的命中（最多 limit 条）。 */
    public synchronized List<Hit> search(String query, int limit) {
        List<Hit> out = new ArrayList<>();
        if (this.docCount == 0 || query == null || query.isBlank()) {
            return out;
        }
        Set<String> q = AiMemoryTokens.queryTokens(query);
        if (q.isEmpty()) {
            return out;
        }
        Map<String, Double> acc = new HashMap<>();
        for (String term : q) {
            Map<String, Integer> posting = this.postings.get(term);
            if (posting == null) {
                continue;
            }
            int n = this.df.getOrDefault(term, 1);
            double idf = Math.log(1.0 + (this.docCount - n + 0.5) / (n + 0.5));
            for (Map.Entry<String, Integer> e : posting.entrySet()) {
                int tf = e.getValue();
                int dl = this.docLen.getOrDefault(e.getKey(), 1);
                double denom = tf + K1 * (1.0 - B + B * dl / this.avgdl);
                double s = idf * (tf * (K1 + 1.0)) / denom;
                acc.merge(e.getKey(), s, Double::sum);
            }
        }
        for (Map.Entry<String, Double> e : acc.entrySet()) {
            double s = e.getValue() + 0.15 * this.docSalience.getOrDefault(e.getKey(), 0);
            out.add(new Hit(e.getKey(), s));
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /** 已索引文档数（诊断用）。 */
    public synchronized int documentCount() {
        return this.docCount;
    }

    private static String signatureOf(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(docs.size() * 12);
        for (Doc d : docs) {
            sb.append(d.id()).append(',').append(d.content().length()).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + docs.size();
    }
}
