package com.maidsmart.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * AI 记忆存储（v1.5.86，对齐 maidsoulcore v2 的四表 jsonl + 生命周期维护）。
 *
 * 落盘：<世界目录>/promaid_memory/<女仆UUID>/ 下五张 jsonl（逐行 JSON，可人工检查）：
 * - paragraphs.jsonl：段落（记忆本体）
 * - entities.jsonl   ：实体（自动 upsert，出现次数累计）
 * - relations.jsonl  ：关系三元组（subject-predicate-object + 置信度）
 * - episodes.jsonl   ：事件片段（按事件时间窗聚合）
 * - profiles.jsonl   ：人物画像快照（带证据段落 hash）
 * - meta.json        ：提取位置 / 每日回顾标记
 *
 * 生命周期（对齐 v2 维护策略 exact_dedupe + structural_merge + solidify + age_decay）：
 * - 写入时精确去重：同 hash 段落合并（salience 取 max，更新时间）
 * - prune()：同 tag 桶 ngram-Jaccard 结构合并 + 非永久条目 30 天未访问衰减删除
 *   + 超上限按 (salience, 最近访问) 淘汰
 * - 所有 IO 异常静默（记忆系统不能影响游戏运行），并发访问 synchronized。
 */
public final class AiMemoryStore {
    /** 女仆 UUID → 存储实例（弱引用防泄漏；服务端与提取回调共用同一实例） */
    private static final Map<Object, AiMemoryStore> CACHE = new WeakHashMap<>();
    /** v1.5.190：防抖写盘——批量 flush 的最小间隔（毫秒）与上次批量时间 */
    private static final long FLUSH_INTERVAL_MS = 500L;
    private static long LAST_FLUSH = 0L;
    /** v1.5.102：衰减周期（天）/衰减保留重要度从配置面板读取（memory.decayDays/decaySalience） */

    /** v1.5.231b：写入诊断日志节流（10 秒一条，latest.log 搜 "memory write"） */
    private static long LAST_WRITE_LOG = 0L;

    private final Path dir;
    /** v1.5.251：来源世界（维度名，写入段落时自动加 world: 标签；非 final——
     *  首次 of() 未带世界名时后续可补充注入） */
    private volatile String worldId;
    private final List<AiMemoryModels.Paragraph> paragraphs = new ArrayList<>();
    private final List<AiMemoryModels.Entity> entities = new ArrayList<>();
    private final List<AiMemoryModels.Relation> relations = new ArrayList<>();
    private final List<AiMemoryModels.Episode> episodes = new ArrayList<>();
    private final List<AiMemoryModels.Profile> profiles = new ArrayList<>();
    private AiMemoryModels.Meta meta = AiMemoryModels.Meta.empty();
    private boolean dirty = false;

    private AiMemoryStore(Path dir, String worldId) {
        this.dir = dir;
        this.worldId = worldId;
        this.load();
    }

    /** 获取（或新建）某只女仆的记忆存储（目录不存在时自动创建） */
    public static synchronized AiMemoryStore of(java.util.UUID maidUuid, Path rootDir) {
        return of(maidUuid, rootDir, null);
    }

    /** v1.5.251：带世界标注的版本。目录变化时自动重建（灵魂绑定后目录从世界
     *  目录切到全局灵魂目录——旧 store 指向旧目录必须重建，旧数据已落盘） */
    public static synchronized AiMemoryStore of(java.util.UUID maidUuid, Path rootDir, String worldId) {
        Path dir = rootDir.resolve(maidUuid.toString());
        AiMemoryStore store = CACHE.get(maidUuid);
        if (store == null || !store.dir.equals(dir)) {
            store = new AiMemoryStore(dir, worldId);
            CACHE.put(maidUuid, store);
        } else if (store.worldId == null && worldId != null) {
            store.worldId = worldId; // 首次注入世界标注
        }
        return store;
    }

    /**
     * v1.5.190：批量落盘——把当前所有脏 store 一次性写入。
     * 由 AiMemoryManager 的 tick 调度调用（每 scanInterval 秒一次）：
     * 内部用固定间隔（500ms）防抖，避免高频连续写入被拆成多次磁盘 IO。
     */
    public static synchronized void flushAll(long nowMs) {
        if (nowMs - LAST_FLUSH < FLUSH_INTERVAL_MS) {
            return;
        }
        LAST_FLUSH = nowMs;
        for (AiMemoryStore s : CACHE.values()) {
            if (s != null && s.dirty) {
                s.save();
            }
        }
    }

    // ---------- 读取 ----------

    private void load() {
        this.paragraphs.clear();
        this.entities.clear();
        this.relations.clear();
        this.episodes.clear();
        this.profiles.clear();
        try {
            if (Files.isDirectory(this.dir)) {
                for (String line : readLines(this.dir.resolve("paragraphs.jsonl"))) {
                    this.paragraphs.add(AiMemoryModels.GSON.fromJson(line, AiMemoryModels.Paragraph.class));
                }
                for (String line : readLines(this.dir.resolve("entities.jsonl"))) {
                    this.entities.add(AiMemoryModels.GSON.fromJson(line, AiMemoryModels.Entity.class));
                }
                for (String line : readLines(this.dir.resolve("relations.jsonl"))) {
                    this.relations.add(AiMemoryModels.GSON.fromJson(line, AiMemoryModels.Relation.class));
                }
                for (String line : readLines(this.dir.resolve("episodes.jsonl"))) {
                    this.episodes.add(AiMemoryModels.GSON.fromJson(line, AiMemoryModels.Episode.class));
                }
                for (String line : readLines(this.dir.resolve("profiles.jsonl"))) {
                    this.profiles.add(AiMemoryModels.GSON.fromJson(line, AiMemoryModels.Profile.class));
                }
                Path metaPath = this.dir.resolve("meta.json");
                if (Files.exists(metaPath)) {
                    this.meta = AiMemoryModels.GSON.fromJson(Files.readString(metaPath, StandardCharsets.UTF_8),
                            AiMemoryModels.Meta.class);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        try {
            Files.createDirectories(this.dir);
            writeLines(this.dir.resolve("paragraphs.jsonl"), this.paragraphs);
            writeLines(this.dir.resolve("entities.jsonl"), this.entities);
            writeLines(this.dir.resolve("relations.jsonl"), this.relations);
            writeLines(this.dir.resolve("episodes.jsonl"), this.episodes);
            writeLines(this.dir.resolve("profiles.jsonl"), this.profiles);
            Files.writeString(this.dir.resolve("meta.json"),
                    AiMemoryModels.GSON.toJson(this.meta), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static List<String> readLines(Path p) throws IOException {
        return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
    }

    private static void writeLines(Path p, List<?> rows) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object row : rows) {
                sb.append(AiMemoryModels.GSON.toJson(row)).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    // ---------- 写入 ----------

    /** 写入一段记忆（精确去重：同 hash 合并 salience 取 max；永久标记合并保留）
     *  v1.5.95：冲突覆盖——同一 fact:xxx 的新段落若 salience 更高（置信度更高/更新），
     *  旧段落标记 deleted（不再注入/检索），实现"主人喜欢红茶 → 主人戒了红茶"的覆盖。
     *  v1.5.190：写盘改防抖批量（memory.lazySave 开启时只标脏，由 AiMemoryManager
     *  tick 调 flushAll 统一落盘）——不再每次写入都全量重写 6 个 jsonl。 */
    public synchronized void addParagraph(AiMemoryModels.Paragraph p) {
        // v1.5.251：自动标注来源世界（world:xxx 进 tags；已标注的不重复）
        if (this.worldId != null && !this.worldId.isEmpty()) {
            p = p.withWorld(this.worldId);
        }
        // v1.5.231b：记忆写入诊断（10 秒节流）——"关了开关记忆还在涨"的排查：
        // 看关闭后段落数是否仍在增长（latest.log 搜 "memory write"）
        long now = System.currentTimeMillis();
        if (now - LAST_WRITE_LOG > 10000) {
            LAST_WRITE_LOG = now;
            org.slf4j.Logger log = com.mojang.logging.LogUtils.getLogger();
            log.info("memory write: store={} paragraphs={} salience={}",
                    this.dir.getFileName(), this.paragraphs.size(), p.salience());
        }
        if (p.deleted()) {
            return;
        }
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph old = this.paragraphs.get(i);
            if (old.hash().equals(p.hash())) {
                this.paragraphs.set(i, mergeParagraph(old, p));
                this.markDirty();
                return;
            }
        }
        // v1.5.95：冲突覆盖（同 fact key、新 salience 更高 → 旧段落作废）
        // v1.5.96：可配置开关 memory.conflictOverride（关 = 回退旧版仅去重）
        String newFactKey = com.maidsmart.config.MaidSmartConfig.MEMORY_CONFLICT_OVERRIDE.get()
                ? factKeyOf(p.tags()) : null;
        if (newFactKey != null) {
            for (int i = 0; i < this.paragraphs.size(); i++) {
                AiMemoryModels.Paragraph old = this.paragraphs.get(i);
                if (old.deleted() || !newFactKey.equals(factKeyOf(old.tags()))) {
                    continue;
                }
                // 新记忆重要度更高（或同为永久）→ 覆盖旧（旧段作废）
                if (p.salience() >= old.salience() || (p.isPermanent() && !old.isPermanent())) {
                    this.paragraphs.set(i, markDeleted(old));
                    this.markDirty();
                    // 同 key 的画像证据同步清理（旧画像文本不再被注入）
                    this.profiles.removeIf(pr -> pr.evidenceIds().contains(old.hash()));
                }
            }
        }
        this.paragraphs.add(p);
        this.markDirty();
    }

    /** 提取段落 tags 中的 fact:key（无则 null） */
    private static String factKeyOf(String tags) {
        if (tags == null) {
            return null;
        }
        for (String t : tags.split(",")) {
            String tt = t.trim();
            if (tt.startsWith("fact:")) {
                return tt;
            }
        }
        return null;
    }

    /** 标记段落作废（deleted=true，paragraphs()/检索/注入自动跳过） */
    private static AiMemoryModels.Paragraph markDeleted(AiMemoryModels.Paragraph p) {
        return new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                p.metadata(), p.createdAt(), p.updatedAt(), p.eventTimeStart(), p.eventTimeEnd(),
                p.salience(), p.accessCount(), p.lastAccessed(), p.permanent(), true, p.protectedUntil());
    }

    /** 段落合并：salience 取 max、保留永久/保护、时间取新
     *  v1.5.190：accessCount 取 max 不再求和——旧版求和导致每次检索 touch
     *  都翻倍（合并 old=N 与 touched=N+1 → 2N+1），检索几次就指数膨胀 */
    private static AiMemoryModels.Paragraph mergeParagraph(AiMemoryModels.Paragraph a,
                                                           AiMemoryModels.Paragraph b) {
        return new AiMemoryModels.Paragraph(
                a.hash(), b.sourceType(), b.role(), b.content(), mergeTags(a.tags(), b.tags()),
                a.metadata(), Math.min(a.createdAt(), b.createdAt()), Math.max(a.updatedAt(), b.updatedAt()),
                Math.min(a.eventTimeStart(), b.eventTimeStart()), Math.max(a.eventTimeEnd(), b.eventTimeEnd()),
                Math.max(a.salience(), b.salience()), Math.max(a.accessCount(), b.accessCount()),
                Math.max(a.lastAccessed(), b.lastAccessed()),
                a.permanent() || b.permanent(), false,
                Math.max(a.protectedUntil(), b.protectedUntil()));
    }

    private static String mergeTags(String a, String b) {
        Set<String> tags = new java.util.LinkedHashSet<>();
        for (String t : a.split(",")) {
            if (!t.isBlank()) {
                tags.add(t.trim());
            }
        }
        for (String t : b.split(",")) {
            if (!t.isBlank()) {
                tags.add(t.trim());
            }
        }
        return String.join(",", tags);
    }

    /** v1.5.191：是否带错误标记（error_mark = 主人否定的直接记录；error_affected = 被传播标记的同类内容） */
    public static boolean hasErrorTag(AiMemoryModels.Paragraph p) {
        String tags = p.tags();
        if (tags == null) {
            return false;
        }
        return tags.contains("error_mark") || tags.contains("error_affected");
    }

    /**
     * v1.5.191：主人认可某个话题 → 强化对应记忆（salience+1、访问+1、刷新时间）。
     * 匹配规则：段落内容包含该话题文本（话题来自主动对话对段落内容的 clip，故
     * 原段落 content 必以该文本开头/包含之）。找到第一条即强化。
     */
    public synchronized boolean reinforceByTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        String t = AiMemoryModels.normalize(topic);
        if (t.length() < 2) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || hasErrorTag(p)) {
                continue;
            }
            if (!AiMemoryModels.normalize(p.content()).contains(t)) {
                continue;
            }
            this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                    p.metadata(), p.createdAt(), now, p.eventTimeStart(), p.eventTimeEnd(),
                    Math.min(10, p.salience() + 1), p.accessCount() + 1, now,
                    p.permanent(), p.deleted(), p.protectedUntil()));
            this.markDirty();
            return true;
        }
        return false;
    }

    /**
     * v1.5.190：统一脏标记——防抖写盘开启时只标脏（由 flushAll 批量落盘）；
     * 关闭时保持旧行为（立即写盘，可靠性优先）。
     */
    private void markDirty() {
        this.dirty = true;
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_LAZY_SAVE.get()) {
            this.save();
        }
    }

    /** upsert 实体（同名同类出现次数 +1） */
    public synchronized void upsertEntity(String name, String kind, long now) {
        for (int i = 0; i < this.entities.size(); i++) {
            AiMemoryModels.Entity e = this.entities.get(i);
            if (e.name().equals(name) && e.kind().equals(kind)) {
                this.entities.set(i, e.appeared(now));
                this.markDirty();
                return;
            }
        }
        this.entities.add(AiMemoryModels.Entity.create(name, kind, now));
        this.markDirty();
    }

    /** upsert 关系三元组（同 subject|predicate|object 更新置信度/证据） */
    public synchronized void upsertRelation(AiMemoryModels.Relation r) {
        for (int i = 0; i < this.relations.size(); i++) {
            AiMemoryModels.Relation old = this.relations.get(i);
            if (old.hash().equals(r.hash())) {
                this.relations.set(i, new AiMemoryModels.Relation(old.hash(), old.subject(), old.predicate(),
                        old.object(), Math.max(old.confidence(), r.confidence()),
                        r.sourceParagraph(), old.permanent() || r.permanent(), false,
                        Math.max(old.protectedUntil(), r.protectedUntil()),
                        old.accessCount() + 1, old.createdAt()));
                this.markDirty();
                return;
            }
        }
        this.relations.add(r);
        this.markDirty();
    }

    /** upsert 事件片段（同事件时间窗聚合段落数） */
    public synchronized void upsertEpisode(AiMemoryModels.Episode e) {
        for (int i = 0; i < this.episodes.size(); i++) {
            AiMemoryModels.Episode old = this.episodes.get(i);
            if (old.episodeId().equals(e.episodeId())) {
                this.episodes.set(i, new AiMemoryModels.Episode(old.episodeId(), old.source(), old.title(),
                        e.summary().isEmpty() ? old.summary() : e.summary(), old.participants(),
                        mergeTags(old.keywords(), e.keywords()),
                        old.evidenceIds() + "," + e.evidenceIds(),
                        Math.min(old.eventTimeStart(), e.eventTimeStart()),
                        Math.max(old.eventTimeEnd(), e.eventTimeEnd()),
                        Math.max(old.confidence(), e.confidence()),
                        old.paragraphCount() + 1, old.createdAt(), Math.max(old.updatedAt(), e.updatedAt())));
                this.markDirty();
                return;
            }
        }
        this.episodes.add(e);
        this.markDirty();
    }

    /** upsert 人物画像（按 personId 合并文本 + 证据） */
    public synchronized void upsertProfile(AiMemoryModels.Profile p) {
        for (int i = 0; i < this.profiles.size(); i++) {
            AiMemoryModels.Profile old = this.profiles.get(i);
            if (old.personId().equals(p.personId())) {
                String merged = old.profileText() + "；" + p.profileText();
                this.profiles.set(i, new AiMemoryModels.Profile(old.personId(),
                        AiMemoryModels.clip(merged, 800),
                        old.evidenceIds() + "," + p.evidenceIds(), p.updatedAt()));
                this.markDirty();
                return;
            }
        }
        this.profiles.add(p);
        this.markDirty();
    }

    // ---------- 生命周期维护（对齐 v2：结构合并 + 衰减 + 淘汰） ----------

    /**
     * 维护周期（每次提取写入后调用）：
     * 1. 同 tag 桶 ngram-Jaccard ≥ 0.55 的结构相似段落合并（保留 salience 高者）
     * 2. 非永久且 30 天未访问且 salience ≤ 3 → 删除（遗忘）
     * 3. 超上限：按 (salience, 最近访问) 淘汰最低的非永久段落
     */
    public synchronized void prune(long now, int maxEntries) {
        boolean changed = false;
        // 1. 结构合并：同源同 tag 桶的相似内容合并
        Map<String, List<AiMemoryModels.Paragraph>> buckets = new HashMap<>();
        for (AiMemoryModels.Paragraph p : this.paragraphs) {
            if (p.deleted()) {
                continue;
            }
            String key = structuralKey(p);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        Set<String> merged = new HashSet<>();
        for (List<AiMemoryModels.Paragraph> bucket : buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    AiMemoryModels.Paragraph a = bucket.get(i);
                    AiMemoryModels.Paragraph b = bucket.get(j);
                    if (a.isPermanent() && b.isPermanent()) {
                        continue;
                    }
                    // v1.5.103：bucket 是快照，可能引用已被前面配对合并移除的段落——
                    // 跳过不在当前列表里的，杜绝 indexOf(keep)=-1 → set(-1) 抛 IOOBE 崩服
                    if (!this.paragraphs.contains(a) || !this.paragraphs.contains(b)) {
                        continue;
                    }
                    if (jaccard(a.content(), b.content()) >= 0.55) {
                        AiMemoryModels.Paragraph keep = a.salience() >= b.salience() ? a : b;
                        AiMemoryModels.Paragraph drop = a.salience() >= b.salience() ? b : a;
                        if (drop.permanent()) {
                            keep = keep.permanent() ? keep : drop;
                        }
                        // v1.5.103：合并内容（drop 的独有细节并入 keep，clip 600）——
                        // 旧版只 remove(drop) 不并入，drop 的内容直接丢失；hash 随合并内容重算
                        String mergedContent = AiMemoryModels.clip(keep.content() + "；" + drop.content(), 600);
                        long nowMs = System.currentTimeMillis();
                        AiMemoryModels.Paragraph mergedP = new AiMemoryModels.Paragraph(
                                AiMemoryModels.hashOf(mergedContent), keep.sourceType(), keep.role(),
                                mergedContent, keep.tags(), keep.metadata(),
                                keep.createdAt(), nowMs, keep.eventTimeStart(), keep.eventTimeEnd(),
                                Math.max(keep.salience(), drop.salience()), keep.accessCount(),
                                nowMs, keep.permanent(), keep.deleted(), keep.protectedUntil());
                        this.paragraphs.remove(drop);
                        int idx = this.paragraphs.indexOf(keep);
                        if (idx >= 0) {
                            this.paragraphs.set(idx, mergedP);
                        } else {
                            this.paragraphs.add(mergedP); // keep 也被前面合并移除（极端）→ 放回
                        }
                        merged.add(drop.hash());
                        changed = true;
                    }
                }
            }
        }
        // 2. 衰减遗忘：30 天未访问且低重要度的非永久段落
        Iterator<AiMemoryModels.Paragraph> it = this.paragraphs.iterator();
        while (it.hasNext()) {
            AiMemoryModels.Paragraph p = it.next();
            if (!p.isPermanent() && !p.deleted()
                    && p.salience() <= com.maidsmart.config.MaidSmartConfig.MEMORY_DECAY_SALIENCE.get()
                    && now - p.lastAccessed() > (long) com.maidsmart.config.MaidSmartConfig.MEMORY_DECAY_DAYS.get() * 86400000L) {
                it.remove();
                changed = true;
            }
        }
        // 3. 上限淘汰：按 (salience 升序, 最近访问 升序) 淘汰非永久
        if (this.paragraphs.size() > maxEntries) {
            this.paragraphs.sort(Comparator
                    .comparingInt((AiMemoryModels.Paragraph p) -> p.isPermanent() ? Integer.MAX_VALUE : p.salience())
                    .thenComparingLong(AiMemoryModels.Paragraph::lastAccessed));
            int toRemove = this.paragraphs.size() - maxEntries;
            Iterator<AiMemoryModels.Paragraph> pit = this.paragraphs.iterator();
            int removed = 0;
            while (pit.hasNext() && removed < toRemove) {
                AiMemoryModels.Paragraph p = pit.next();
                if (p.isPermanent()) {
                    continue;
                }
                pit.remove();
                removed++;
                changed = true;
            }
        }
        if (changed) {
            this.dirty = true;
            this.save();
        }
    }

    /**
     * v1.5.190：安全清理并立即落盘——防抖写盘开启时也要在卸载/迁移等
     * 需要立即持久化的场景强制写盘。
     */
    public synchronized void saveNow() {
        this.dirty = true;
        this.save();
    }

    // ---------- v1.5.191 维护周期（runMaintenance，由 AiMemoryManager 定时调度） ----------

    /** 维护结果摘要（供日志/调试面板） */
    public record MaintenanceReport(int solidified, int decayed, int halfLived,
                                    int relDecayed, int relInactive, int errProp) {
        public boolean changed() {
            return solidified > 0 || decayed > 0 || halfLived > 0 || relDecayed > 0 || relInactive > 0 || errProp > 0;
        }

        @Override
        public String toString() {
            return "固化" + solidified + " 衰减" + decayed + " 半衰" + halfLived
                    + " 关系降" + relDecayed + " 关系停用" + relInactive + " 错误传播" + errProp;
        }
    }

    /**
     * 对 CACHE 中所有已加载存储执行维护（由 AiMemoryManager 每 maintenanceMin 分钟调用）。
     */
    public static synchronized void maintainAll(long now) {
        for (AiMemoryStore s : CACHE.values()) {
            if (s != null) {
                s.runMaintenance(now);
            }
        }
    }

    /**
     * 记忆维护周期（对齐 maidsoulcore v2 maintainCycle 精简移植）：
     * 1. 固化：非永久且重要（boundary/promise/relationship_event/self_memory 或 salience≥9）
     *    → permanent + protectedUntil = now + 30 天（与写入策略 AiMemoryWriteStrategy 对齐）
     * 2. 年龄衰减：非保护且 7 天未更新、salience>1、无访问 → salience-1；
     *    且 30 天（memory.decayDays）未访问、salience ≤ decaySalience → deleted
     * 3. accessCount 半衰：14 天未访问 → 减半（防无限增长）
     * 4. 关系置信度衰减：非永久、无访问、年龄 ≥ memory.relationDecayDays（60 天）
     *    → confidence×0.85；<0.15 → inactive（不再注入/检索）
     * 5. error_mark 传播：被主人否定的段落 → 同类（同话题/结构相似 jaccard≥0.42）
     *    段落打 error_affected + salience 封顶 2（主动会话不再提起）
     * 有变化才落盘 + 写 maintenance_log.jsonl。
     */
    public synchronized MaintenanceReport runMaintenance(long now) {
        final long DAY = 86400000L;
        int solidified = 0, decayed = 0, halfLived = 0, relDecayed = 0, relInactive = 0, errProp = 0;

        // 1. 固化
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || p.permanent()) {
                continue;
            }
            String tags = p.tags() == null ? "" : p.tags();
            boolean important = tags.contains("boundary") || tags.contains("promise")
                    || tags.contains("relationship_event") || tags.contains("self_memory")
                    || p.salience() >= 9;
            if (important) {
                this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                        p.metadata(), p.createdAt(), p.updatedAt(), p.eventTimeStart(), p.eventTimeEnd(),
                        p.salience(), p.accessCount(), p.lastAccessed(), true, false,
                        Math.max(p.protectedUntil(), now + 30 * DAY)));
                solidified++;
            }
        }
        // 2. 年龄衰减 + 遗忘
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || p.isPermanent()) {
                continue;
            }
            long ageDays = Math.max(0, (now - p.updatedAt()) / DAY);
            if (ageDays >= 7 && p.salience() > 1 && p.accessCount() == 0) {
                this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                        p.metadata(), p.createdAt(), now, p.eventTimeStart(), p.eventTimeEnd(),
                        p.salience() - 1, p.accessCount(), p.lastAccessed(), p.permanent(), p.deleted(), p.protectedUntil()));
                decayed++;
                p = this.paragraphs.get(i);
            }
            if (!p.deleted() && !p.isPermanent()
                    && now - p.lastAccessed() > (long) com.maidsmart.config.MaidSmartConfig.MEMORY_DECAY_DAYS.get() * DAY
                    && p.salience() <= com.maidsmart.config.MaidSmartConfig.MEMORY_DECAY_SALIENCE.get()) {
                this.paragraphs.set(i, markDeleted(p));
                decayed++;
            }
        }
        // 3. accessCount 半衰（14 天未访问）
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || p.accessCount() <= 1) {
                continue;
            }
            if (now - p.lastAccessed() > 14 * DAY) {
                this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                        p.metadata(), p.createdAt(), p.updatedAt(), p.eventTimeStart(), p.eventTimeEnd(),
                        p.salience(), p.accessCount() / 2, p.lastAccessed(), p.permanent(), p.deleted(), p.protectedUntil()));
                halfLived++;
            }
        }
        // 4. 关系置信度衰减
        for (int i = 0; i < this.relations.size(); i++) {
            AiMemoryModels.Relation r = this.relations.get(i);
            if (r.inactive() || r.permanent()) {
                continue;
            }
            long ageDays = Math.max(0, (now - r.createdAt()) / DAY);
            if (r.accessCount() == 0 && ageDays >= (long) com.maidsmart.config.MaidSmartConfig.MEMORY_RELATION_DECAY_DAYS.get()) {
                double nc = r.confidence() * 0.85;
                boolean inactive = nc < 0.15;
                this.relations.set(i, new AiMemoryModels.Relation(r.hash(), r.subject(), r.predicate(), r.object(),
                        nc, r.sourceParagraph(), r.permanent(), inactive,
                        r.protectedUntil(), r.accessCount(), r.createdAt()));
                if (inactive) {
                    relInactive++;
                } else {
                    relDecayed++;
                }
            }
        }
        // 5. error_mark 传播（同话题精确匹配优先，结构相似 jaccard 兜底）
        for (AiMemoryModels.Paragraph mark : new ArrayList<>(this.paragraphs)) {
            if (mark.deleted() || !hasErrorTag(mark)) {
                continue;
            }
            String topic = markTopic(mark.content());
            for (int i = 0; i < this.paragraphs.size(); i++) {
                AiMemoryModels.Paragraph p = this.paragraphs.get(i);
                if (p.deleted() || p == mark || hasErrorTag(p)) {
                    continue;
                }
                boolean same = !topic.isEmpty() && p.content() != null && p.content().contains(topic);
                if (!same && jaccard(mark.content(), p.content()) >= 0.42) {
                    same = true;
                }
                if (!same) {
                    continue;
                }
                this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(),
                        mergeTags(p.tags(), "error_affected"),
                        p.metadata(), p.createdAt(), p.updatedAt(), p.eventTimeStart(), p.eventTimeEnd(),
                        Math.min(p.salience(), 2), p.accessCount(), p.lastAccessed(), p.permanent(), p.deleted(), p.protectedUntil()));
                errProp++;
            }
        }
        if (solidified > 0 || decayed > 0 || halfLived > 0 || relDecayed > 0 || relInactive > 0 || errProp > 0) {
            appendMaintenanceLog(new MaintenanceReport(solidified, decayed, halfLived,
                    relDecayed, relInactive, errProp).toString());
            this.saveNow();
        }
        return new MaintenanceReport(solidified, decayed, halfLived, relDecayed, relInactive, errProp);
    }

    /** 从 error_mark 段落内容提取被否定的话题（"话题：xxx" 片段）；无则空串 */
    private static String markTopic(String content) {
        if (content == null) {
            return "";
        }
        int i = content.indexOf("话题：");
        int j = content.indexOf("）。", i + 3);
        if (i >= 0 && j > i + 3) {
            return content.substring(i + 3, j).trim();
        }
        return "";
    }

    /** 追加维护日志（maintenance_log.jsonl，人工检查维护行为） */
    private void appendMaintenanceLog(String text) {
        try {
            java.nio.file.Files.createDirectories(this.dir);
            java.nio.file.Path f = this.dir.resolve("maintenance_log.jsonl");
            String line = "{\"time\":" + System.currentTimeMillis() + ",\"text\":\""
                    + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n";
            java.nio.file.Files.writeString(f, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // ---------- v1.5.192 手动调试（Promaid 手册·链路调试面板动作） ----------

    /** 按 hash 标记段落作废（调试面板"标记删除"）；返回是否命中 */
    public synchronized boolean markDeletedByHash(String hash) {
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || !p.hash().equals(hash)) {
                continue;
            }
            this.paragraphs.set(i, markDeleted(p));
            this.profiles.removeIf(pr -> pr.evidenceIds().contains(hash));
            this.markDirty();
            return true;
        }
        return false;
    }

    /** 按 hash 恢复已删除段落（调试面板"恢复"）；返回是否命中 */
    public synchronized boolean restoreByHash(String hash) {
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (!p.deleted() || !p.hash().equals(hash)) {
                continue;
            }
            this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                    p.metadata(), p.createdAt(), System.currentTimeMillis(), p.eventTimeStart(), p.eventTimeEnd(),
                    p.salience(), p.accessCount(), System.currentTimeMillis(), p.permanent(), false, p.protectedUntil()));
            this.markDirty();
            return true;
        }
        return false;
    }

    /** 按 hash 调整重要度（1..10 夹取；调试面板"↑/↓重要度"）；返回新值，未命中返回 -1 */
    public synchronized int adjustSalienceByHash(String hash, int delta) {
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || !p.hash().equals(hash)) {
                continue;
            }
            int ns = Math.max(1, Math.min(10, p.salience() + delta));
            this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                    p.metadata(), p.createdAt(), System.currentTimeMillis(), p.eventTimeStart(), p.eventTimeEnd(),
                    ns, p.accessCount(), p.lastAccessed(), p.permanent(), p.deleted(), p.protectedUntil()));
            this.markDirty();
            return ns;
        }
        return -1;
    }

    /** 按 hash 强化（salience+1 封顶 10、访问+1、刷新时间；调试面板"强化"）；返回是否命中 */
    public synchronized boolean reinforceByHash(String hash) {
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || !p.hash().equals(hash)) {
                continue;
            }
            long now = System.currentTimeMillis();
            this.paragraphs.set(i, new AiMemoryModels.Paragraph(p.hash(), p.sourceType(), p.role(), p.content(), p.tags(),
                    p.metadata(), p.createdAt(), now, p.eventTimeStart(), p.eventTimeEnd(),
                    Math.min(10, p.salience() + 1), p.accessCount() + 1, now,
                    p.permanent(), p.deleted(), p.protectedUntil()));
            this.markDirty();
            return true;
        }
        return false;
    }

    /** 按 hash 查找段落（调试面板选中时显示详情用；含已删除） */
    public synchronized AiMemoryModels.Paragraph paragraphByHash(String hash) {
        for (AiMemoryModels.Paragraph p : this.paragraphs) {
            if (p.hash().equals(hash)) {
                return p;
            }
        }
        return null;
    }

    /** 结构合并桶键：sourceType + 首 tag（对齐 v2 sameStructuralBucket：同源同结构 tag） */
    private static String structuralKey(AiMemoryModels.Paragraph p) {
        String tag = "";
        for (String t : p.tags().split(",")) {
            if (!t.isBlank()) {
                tag = t.trim();
                break;
            }
        }
        return p.sourceType() + "|" + tag;
    }

    /** ngram-Jaccard 相似度（2-gram 集合） */
    private static double jaccard(String a, String b) {
        Set<String> ga = ngrams(a);
        Set<String> gb = ngrams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        Set<String> union = new HashSet<>(ga);
        union.addAll(gb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> ngrams(String s) {
        Set<String> out = new HashSet<>();
        String norm = AiMemoryModels.normalize(s).replaceAll("[\\p{Punct}。，、！？；：]", "");
        for (int i = 0; i + 1 < norm.length(); i++) {
            out.add(norm.substring(i, i + 2));
        }
        return out;
    }

    // ---------- 读取访问（供检索/注入） ----------

    public synchronized List<AiMemoryModels.Paragraph> paragraphs() {
        List<AiMemoryModels.Paragraph> out = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : this.paragraphs) {
            if (!p.deleted()) {
                out.add(p);
            }
        }
        return out;
    }

    public synchronized List<AiMemoryModels.Relation> relations() {
        return new ArrayList<>(this.relations);
    }

    public synchronized List<AiMemoryModels.Episode> episodes() {
        return new ArrayList<>(this.episodes);
    }

    public synchronized List<AiMemoryModels.Profile> profiles() {
        return new ArrayList<>(this.profiles);
    }

    public synchronized AiMemoryModels.Meta meta() {
        return this.meta;
    }

    public synchronized void setMeta(AiMemoryModels.Meta m) {
        this.meta = m;
        // v1.5.190：提取位置是关键状态（丢了会重复提取）——防抖写盘也立即落盘
        this.dirty = true;
        this.save();
    }

    /** v1.5.103：清空全部记忆（段落/关系/实体/片段/画像 + 元数据）——手册"清空记忆"按钮用
     *  v1.5.190：同时清除工作笔记文件（防止"全部记忆已清空"后笔记还残留在上下文里） */
    public synchronized void clearAll() {
        this.paragraphs.clear();
        this.entities.clear();
        this.relations.clear();
        this.episodes.clear();
        this.profiles.clear();
        this.meta = AiMemoryModels.Meta.empty();
        this.dirty = true;
        this.save();
        try {
            java.nio.file.Files.deleteIfExists(this.dir.resolve("working_note.txt"));
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.1.0：按 tag 标记段落作废（离婚/关系反转撤销用）——
     * deleted 段落自动从注入/检索/记忆面板过滤；同段落 hash 的画像证据一并清理。
     */
    public synchronized int markDeletedByTag(String tag) {
        int n = 0;
        for (int i = 0; i < this.paragraphs.size(); i++) {
            AiMemoryModels.Paragraph p = this.paragraphs.get(i);
            if (p.deleted() || p.tags() == null
                    || !("," + p.tags() + ",").contains("," + tag + ",")) {
                continue;
            }
            this.paragraphs.set(i, markDeleted(p));
            this.profiles.removeIf(pr -> pr.evidenceIds().contains(p.hash()));
            n++;
        }
        if (n > 0) {
            this.dirty = true;
            this.save();
        }
        return n;
    }

    /**
     * v1.1.0：按谓词停用关系三元组（离婚撤"妻子/恋人"关系用）——
     * inactive 关系自动从注入（AiMemoryContext）/检索（AiMemorySearch）过滤。
     */
    public synchronized int deactivateRelationsByPredicate(String predicate) {
        int n = 0;
        for (int i = 0; i < this.relations.size(); i++) {
            AiMemoryModels.Relation r = this.relations.get(i);
            if (r.inactive() || !predicate.equals(r.predicate())) {
                continue;
            }
            this.relations.set(i, new AiMemoryModels.Relation(r.hash(), r.subject(), r.predicate(), r.object(),
                    r.confidence(), r.sourceParagraph(), r.permanent(), true,
                    r.protectedUntil(), r.accessCount(), r.createdAt()));
            n++;
        }
        if (n > 0) {
            this.dirty = true;
            this.save();
        }
        return n;
    }
}
