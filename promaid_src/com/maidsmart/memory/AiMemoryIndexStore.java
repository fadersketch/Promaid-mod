package com.maidsmart.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 多级记忆索引库（移植自 Sphantosis 的 memory_index_db / MemoryIndexRecord）。
 *
 * 按"剧情时间"（游戏日）组织四级日记式摘要索引：
 * - 日：当天全部关键事件（详细）
 * - 3日：最近 3 天滚动（中等压缩）
 * - 周：每 7 游戏日（较压缩，仅关键事件与转折）
 * - 月：每 30 游戏日（高压缩，仅最重要事件 top N）
 *
 * 全部永久归档（memory_index.jsonl，与段落等 jsonl 同目录），跨度越大压缩越高；
 * 每条记录标注关键事件段落 hash，供 query_memory_index 工具按需回查原文。
 * 由 AiMemoryArchiver 在跨日/周/月边界（或玩家睡醒）时自动生成。
 */
public final class AiMemoryIndexStore {

    /** 索引级别常量（与 Sphantosis 的 日/3日/周/月 对齐） */
    public static final String LEVEL_DAY = "日";
    public static final String LEVEL_3DAY = "3日";
    public static final String LEVEL_WEEK = "周";
    public static final String LEVEL_MONTH = "月";

    /** 一条索引记录（日记式摘要 + 关键事件段落引用） */
    public record IndexRecord(String level, long startTick, long endTick,
                              int startDay, int endDay,
                              String content, List<String> eventIds, long createdAt) {
    }

    private final Path file;
    private final List<IndexRecord> records = new ArrayList<>();
    /** 脏回调（宿主 store 注入，add 时触发防抖写盘链路——审计优化5：不再依赖调用方手动 saveNow） */
    private Runnable onDirty;

    public AiMemoryIndexStore(Path storeDir) {
        this.file = storeDir.resolve("memory_index.jsonl");
        load();
    }

    /** 由 AiMemoryStore 构造时注入（this::markDirty） */
    void setDirtyCallback(Runnable callback) {
        this.onDirty = callback;
    }

    private void load() {
        this.records.clear();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    IndexRecord r = AiMemoryModels.GSON.fromJson(line, IndexRecord.class);
                    if (r != null) {
                        this.records.add(r);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 落盘（由 AiMemoryStore.save() 统一调度，跟随其防抖批量机制） */
    void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (IndexRecord r : records) {
                sb.append(AiMemoryModels.GSON.toJson(r)).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 是否已有该边界索引（±1200 tick ≈ 1 游戏分钟容差，对齐 Sphantosis 的 60s 容差） */
    public synchronized boolean has(String level, long startTick, long endTick) {
        for (IndexRecord r : records) {
            if (r.level().equals(level)
                    && Math.abs(r.startTick() - startTick) < 1200
                    && Math.abs(r.endTick() - endTick) < 1200) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否已有"同级别、同起点、终点覆盖"的更长记录——登出的"当日部分天"日记
     * 被后续"完整一天"覆盖时视为已归档（审计优化3：避免同一天两份近似日记）。
     */
    public synchronized boolean covers(String level, long startTick, long endTick) {
        for (IndexRecord r : records) {
            if (r.level().equals(level)
                    && Math.abs(r.startTick() - startTick) < 1200
                    && r.endTick() >= endTick - 1200) {
                return true;
            }
        }
        return false;
    }

    /** 新增一条（去重：同边界已存在则忽略；触发宿主脏标记走防抖写盘） */
    public synchronized void add(IndexRecord record) {
        if (has(record.level(), record.startTick(), record.endTick())) {
            return;
        }
        this.records.add(record);
        if (this.onDirty != null) {
            this.onDirty.run();
        }
    }

    /** 某级别全部记录（按开始时间升序） */
    public synchronized List<IndexRecord> byLevel(String level) {
        List<IndexRecord> out = new ArrayList<>();
        for (IndexRecord r : records) {
            if (r.level().equals(level)) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Long.compare(a.startTick(), b.startTick()));
        return out;
    }

    /** 按游戏日范围过滤（含边界；startDay/endDay 为空时分别取极值） */
    public synchronized List<IndexRecord> findRange(String level, Integer startDay, Integer endDay) {
        List<IndexRecord> out = new ArrayList<>();
        int s = startDay == null ? Integer.MIN_VALUE : startDay;
        int e = endDay == null ? Integer.MAX_VALUE : endDay;
        for (IndexRecord r : records) {
            if (!r.level().equals(level)) {
                continue;
            }
            // 记录区间与查询区间有交集即命中（对齐 Sphantosis find_range 的跨度命中语义）
            if (r.endDay() >= s && r.startDay() <= e) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Long.compare(a.startTick(), b.startTick()));
        return out;
    }

    /** 全部记录（快照） */
    public synchronized List<IndexRecord> all() {
        return new ArrayList<>(records);
    }

    /** 清空（跟随记忆清空） */
    public synchronized void clear() {
        this.records.clear();
    }
}
