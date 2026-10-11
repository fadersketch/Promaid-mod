package com.maidsmart.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆向量索引（移植自 maidsoulcore v3 {@code MemoryVectorIndex}）。
 *
 * <p>给每条记忆段落存一个向量（由 {@link AiMemoryEmbeddingClient} 算出），检索时用余弦相似度
 * 找语义最近的段落。落盘 {@code <store>/vectors.jsonl}：每行 {@code {"id":"段落hash","vec":[...]}}。
 * 只有 {@code memory.vectorEnable} 打开、且 embeddings 端点配置可用时才索引/检索；否则整类不介入。
 *
 * <p>为什么单独一个索引文件而不是塞进段落：向量又大又只对语义检索有用，混进 paragraphs.jsonl
 * 会让人工检查记忆库变得不可读。索引只是"段落 hash → 向量"的旁路表，缺了它一切照常、只是没有
 * 语义召回。
 *
 * <p>线程安全。两树逐字相同。
 */
public final class AiMemoryVectorIndex {

    /** 一条命中：段落 id + 余弦相似度。 */
    public record Hit(String id, double score) {
    }

    /** 落盘条目（Gson 实体；用普通类而非 record，兼容老 Gson 反序列化）。 */
    public static final class Entry {
        public String id;
        public float[] vec;

        public Entry() {
        }

        public Entry(String id, float[] vec) {
            this.id = id;
            this.vec = vec;
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public AiMemoryVectorIndex(Path dir) {
        this.file = dir.resolve("vectors.jsonl");
        load();
    }

    private void load() {
        this.entries.clear();
        try {
            if (Files.exists(this.file)) {
                for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Entry e = AiMemoryModels.GSON.fromJson(line, Entry.class);
                    if (e != null && e.id != null && e.vec != null && e.vec.length > 0) {
                        this.entries.add(e);
                    }
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryVectorIndex", ignored);
        }
    }

    private void save() {
        try {
            Files.createDirectories(this.file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : this.entries) {
                sb.append(AiMemoryModels.GSON.toJson(e)).append('\n');
            }
            Files.writeString(this.file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryVectorIndex", ignored);
        }
    }

    /** 写入/更新一条向量（同 id 覆盖）。 */
    public synchronized void put(String id, float[] vec) {
        if (id == null || vec == null || vec.length == 0) {
            return;
        }
        for (int i = 0; i < this.entries.size(); i++) {
            if (id.equals(this.entries.get(i).id)) {
                this.entries.set(i, new Entry(id, vec));
                save();
                return;
            }
        }
        this.entries.add(new Entry(id, vec));
        save();
    }

    /** 语义检索：按余弦相似度降序取 top limit。 */
    public synchronized List<Hit> search(float[] query, int limit) {
        List<Hit> out = new ArrayList<>();
        if (query == null || query.length == 0) {
            return out;
        }
        for (Entry e : this.entries) {
            if (e.vec.length != query.length) {
                continue; // 维度不一致（换过 embedding 模型）→ 跳过，不崩
            }
            double s = cosine(query, e.vec);
            if (s > 0.05) {
                out.add(new Hit(e.id, s));
            }
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public synchronized int size() {
        return this.entries.size();
    }

    public synchronized boolean has(String id) {
        for (Entry e : this.entries) {
            if (e.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    public synchronized Set<String> indexedIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : this.entries) {
            out.add(e.id);
        }
        return out;
    }

    public synchronized void clear() {
        this.entries.clear();
        try {
            Files.deleteIfExists(this.file);
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("AiMemoryVectorIndex", ignored);
        }
    }

    /** 余弦相似度（任一为零向量返回 0）。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
