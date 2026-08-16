package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ChatCompletionResponse;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 记忆自动归档器（移植自 Sphantosis computing_engines/memory_archiver.py）。
 *
 * 记忆分层（对应关系）：
 * - Sphantosis：短期记忆图（short_term_memory）→ 长期记忆（episodic_core），
 *   连通子图整体转移
 * - promaid：段落 layer "short_context"（短期层）→ long_term 标记（长期层），
 *   以内容相似度（ngram-Jaccard）聚类近似"连通子图"——簇内全部段落年龄
 *   超过阈值才整簇转移，不拆散相互关联的事件簇
 *
 * 多级记忆索引（日/3日/周/月）：
 * - 跨游戏日边界：生成昨日「日」索引 + 滚动「3日」索引
 * - 跨周（7 游戏日）/跨月（30 游戏日）边界：生成上周/上月索引
 * - 玩家睡醒（"睡一觉自动处理"）或收尾场景：强制生成当日「日」索引
 * - LLM 日记式摘要（第一人称），跨度越大压缩越高；月级仅保留最重要 top N
 * - 生成失败不静默：记日志并挂入待重试队列，下个 tick 重试
 */
public final class AiMemoryArchiver {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final long DAY_TICKS = 24000L;

    /** 进行中的索引生成（女仆 UUID → 开始毫秒；超时视为卡死允许重试） */
    private static final Map<UUID, Long> ARCHIVING = new HashMap<>();

    /** 归档器持久状态（存 <store>/archiver_state.json） */
    public record ArchiverState(int lastDay, int lastWeek, int lastMonth,
                                List<PendingIndex> pending) {
        public static ArchiverState initial() {
            return new ArchiverState(-1, -1, -1, new ArrayList<>());
        }
    }

    /** 待重试的索引边界 */
    public record PendingIndex(String level, long startTick, long endTick) {
    }

    private AiMemoryArchiver() {
    }

    // ========== 维护调度入口（对齐 MemoryArchiver.tick） ==========

    /**
     * 记忆维护调度入口（服务端线程调用；由 AiMemoryManager 周期调度与玩家睡醒触发）。
     *
     * - 首次调用对齐边界标记（不追溯历史跨度）
     * - 跨日边界：生成昨日「日」索引 + 滚动「3日」索引
     * - 跨周/跨月边界：生成上周/上月索引
     * - forceDayIndex：玩家睡醒等收尾场景，强制生成当日「日」索引
     * - 重试此前失败的索引边界
     * - 执行短期→长期簇转移
     */
    public static void tick(EntityMaid maid, ServerLevel level, boolean forceDayIndex) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            return;
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        long gameTime = level.m_46467_();
        int day = (int) (gameTime / DAY_TICKS);
        int week = day / 7;
        int month = day / 30;

        ArchiverState state = loadState(store);
        if (state.lastDay() < 0) {
            // 首次：对齐边界，不批量回溯（对齐 Sphantosis 行为）
            state = new ArchiverState(day, week, month, state.pending());
            saveState(store, state);
        }

        List<PendingIndex> pending = new ArrayList<>(state.pending());
        if (day != state.lastDay()) {
            // 昨日（完整一天）
            generateOrRetry(maid, level, AiMemoryIndexStore.LEVEL_DAY,
                    (day - 1) * DAY_TICKS, day * DAY_TICKS, pending);
            // 3日索引（滚动最近 3 个自然日，按日对齐保证幂等）
            generateOrRetry(maid, level, AiMemoryIndexStore.LEVEL_3DAY,
                    (day - 2) * DAY_TICKS, (day + 1) * DAY_TICKS, pending);
        }
        if (week != state.lastWeek()) {
            // 上一周（完整 7 游戏日）
            int weekStartDay = week * 7;
            generateOrRetry(maid, level, AiMemoryIndexStore.LEVEL_WEEK,
                    (weekStartDay - 7) * DAY_TICKS, weekStartDay * DAY_TICKS, pending);
        }
        if (month != state.lastMonth()) {
            // 上一月（完整 30 游戏日）
            int monthStartDay = month * 30;
            generateOrRetry(maid, level, AiMemoryIndexStore.LEVEL_MONTH,
                    (monthStartDay - 30) * DAY_TICKS, monthStartDay * DAY_TICKS, pending);
        }
        if (forceDayIndex) {
            // 当日（部分天，睡醒/收尾归档点）
            generateOrRetry(maid, level, AiMemoryIndexStore.LEVEL_DAY,
                    day * DAY_TICKS, gameTime, pending);
        }

        // 重试此前失败的索引边界
        if (!pending.isEmpty()) {
            List<PendingIndex> rest = new ArrayList<>();
            for (PendingIndex item : pending) {
                if (!generateIndex(maid, level, item.level(), item.startTick(), item.endTick())) {
                    rest.add(item);
                }
            }
            pending = rest;
        }

        ArchiverState next = new ArchiverState(day, week, month, pending);
        if (!next.equals(state)) {
            saveState(store, next);
        }

        // 短期→长期簇转移
        runTransfer(store, gameTime);
    }

    /** 生成；失败挂入 pending 重试队列（对齐 _generate_or_retry） */
    private static void generateOrRetry(EntityMaid maid, ServerLevel level, String lv,
                                        long startTick, long endTick, List<PendingIndex> pending) {
        if (!generateIndex(maid, level, lv, startTick, endTick)) {
            PendingIndex item = new PendingIndex(lv, startTick, endTick);
            if (!pending.contains(item)) {
                pending.add(item);
            }
        }
    }

    // ========== 多级记忆索引生成（对齐 generate_index / _llm_summarize） ==========

    /** 生成并归档某级别索引（异步 LLM 日记式摘要）。跨度内无事件时跳过（视为成功）。 */
    public static boolean generateIndex(EntityMaid maid, ServerLevel level, String lv,
                                        long startTick, long endTick) {
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            return false;
        }
        if (store.index().has(lv, startTick, endTick)) {
            return true;
        }
        String eventBlock = collectEventBlock(store, startTick, endTick, lv);
        if (eventBlock.isBlank()) {
            return true; // 无事件节点，跳过（不生成空索引）
        }
        // LLM 站点（复用 TLM 女仆站点配置，与提取器一致；无站点静默跳过挂重试）
        MaidAIChatManager cm = maid.getAiChatManager();
        if (cm == null) {
            return false;
        }
        LLMSite site = cm.getLLMSite();
        if (!(site instanceof LLMOpenAISite os) || !os.enabled()) {
            return false;
        }
        String model = pick(cm.getLLMModel(), com.maidsmart.config.MaidSmartConfig.MEMORY_API_MODEL.get());
        if (model == null || model.isBlank()) {
            return false;
        }
        UUID id = maid.m_20148_();
        Long since = ARCHIVING.get(id);
        if (since != null) {
            if (System.currentTimeMillis() - since
                    < (long) com.maidsmart.config.MaidSmartConfig.MEMORY_EXTRACT_TIMEOUT_MIN.get() * 60000L) {
                return false; // 进行中
            }
            ARCHIVING.remove(id); // 超时 → 允许重试
        }
        ARCHIVING.put(id, System.currentTimeMillis());
        String role = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
        String prompt = buildPrompt(role, lv, startTick, endTick, eventBlock);
        sendIndexRequest(maid, level, os, model, prompt, store, lv, startTick, endTick);
        // 异步发出即视为本 tick 处理完（结果回调里落库；失败在回调里不推进，
        // 下个跨日 tick 由 has() 判定仍缺失 → 重新进入 pending）
        return true;
    }

    /** 收集跨度内事件段落（跳表范围查询；排除摘要层/已删除/被否定），格式化为摘要输入。
     *  月级仅保留按重要度排序的最重要事件（MEMORY_INDEX_MONTH_TOP_N）。 */
    private static String collectEventBlock(AiMemoryStore store, long startTick, long endTick, String lv) {
        List<String> hashes = store.timeIndex().queryRange(startTick, endTick);
        List<AiMemoryModels.Paragraph> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String hash : hashes) {
            if (seen.contains(hash)) {
                continue;
            }
            seen.add(hash);
            AiMemoryModels.Paragraph p = store.paragraphByHash(hash);
            if (p == null || p.deleted()) {
                continue;
            }
            // 摘要段落本身是压缩产物（含旧版每日回顾），不作为事件节点索引——
            // 对齐 Sphantosis 只索引事件节点
            if (p.sourceType().equals("summary") || p.tags().contains("daily")) {
                continue;
            }
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            nodes.add(p);
        }
        if (nodes.isEmpty()) {
            return "";
        }
        if (AiMemoryIndexStore.LEVEL_MONTH.equals(lv)) {
            nodes.sort((a, b) -> Integer.compare(b.salience(), a.salience()));
            int topN = com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_MONTH_TOP_N.get();
            if (nodes.size() > topN) {
                nodes = new ArrayList<>(nodes.subList(0, topN));
            }
        }
        nodes.sort((a, b) -> Long.compare(a.eventTimeStart(), b.eventTimeStart()));

        StringBuilder sb = new StringBuilder();
        for (AiMemoryModels.Paragraph p : nodes) {
            int d = (int) (p.eventTimeStart() / DAY_TICKS);
            sb.append("[第").append(d).append("天|").append(p.sourceType()).append("] ")
                    .append(p.content())
                    .append("（重要度k=").append(p.salience()).append("）")
                    .append("【事件节点id：").append(p.hash()).append("】\n");
        }
        return sb.toString();
    }

    /** 提示词（移植 memory_index_generator.txt，适配 MC 游戏日时间轴） */
    private static String buildPrompt(String role, String lv, long startTick, long endTick, String eventBlock) {
        int sd = (int) (startTick / DAY_TICKS);
        int ed = (int) (endTick / DAY_TICKS);
        return "你正在为角色「" + role + "」撰写记忆索引日记。\n\n"
                + "索引级别：" + lv + "（可选：日 / 3日 / 周 / 月）\n"
                + "时间跨度：第" + sd + "天 ~ 第" + ed + "天\n\n"
                + "跨度内的关键事件节点（格式：[天|类型] 内容（重要性k=xx）【事件节点id：hash】，重要性越高越值得写进日记）：\n"
                + eventBlock + "\n"
                + "要求：\n"
                + "1. 以日记形式组织摘要，使用「" + role + "」的第一人称视角。\n"
                + "2. 压缩程度与跨度级别严格对应，跨度越大信息压缩程度越高：\n"
                + "   - 日：详细记录当天全部关键事件；\n"
                + "   - 3日：中等压缩，覆盖最近3天的重要进展与转折；\n"
                + "   - 周：较压缩，仅保留该周关键事件与重要转折；\n"
                + "   - 月：仅保留该月最重要的事件记录（高压缩，只保留少数最重要事件）。\n"
                + "3. 摘要中凡引用到的事件，必须在输出 JSON 的\"事件节点\"数组中标注其事件节点id（即上面的hash）与名称。\n"
                + "4. 禁止编造未出现在事件节点中的内容；信息缺失时如实指出。\n"
                + "5. 输出必须为严格 JSON（无多余文字），格式如下：\n"
                + "```json\n"
                + "{\n"
                + "  \"日记\": \"……\",\n"
                + "  \"事件节点\": [{\"id\": \"事件节点id（hash）\", \"名称\": \"事件名\"}]\n"
                + "}\n"
                + "```";
    }

    /** 异步请求（与 AiMemoryExtractor 同款直连方式：不经 TLM 回调、无副作用） */
    private static void sendIndexRequest(EntityMaid maid, ServerLevel level, LLMOpenAISite site,
                                         String model, String prompt, AiMemoryStore store,
                                         String lv, long startTick, long endTick) {
        MinecraftServer server = level.m_7654_();
        try {
            ChatCompletion req = ChatCompletion.create().model(model).userChat(prompt);
            if (site.hasThinkingField()) {
                req = req.disableThinking();
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(pick(site.url(),
                            com.maidsmart.config.MaidSmartConfig.MEMORY_API_URL.get())))
                    .header("Authorization", "Bearer " + pick(site.secretKey(),
                            com.maidsmart.config.MaidSmartConfig.MEMORY_API_KEY.get()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(AiMemoryModels.GSON.toJson(req)));
            if (site.headers() != null) {
                site.headers().forEach(builder::header);
            }
            LLMSite.LLM_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> server.execute(() -> {
                        UUID id = maid.m_20148_();
                        try {
                            if (err != null || resp == null || resp.statusCode() != 200
                                    || resp.body() == null || resp.body().isBlank()) {
                                LOGGER.info("AiMemoryArchiver: {}索引生成失败 {}（err={}，status={}）",
                                        lv, id,
                                        err != null ? err.getClass().getSimpleName() : "null",
                                        resp == null ? "null" : resp.statusCode());
                                addPending(store, lv, startTick, endTick); // 下个 tick 重试
                                return;
                            }
                            ChatCompletionResponse parsed = AiMemoryModels.GSON
                                    .fromJson(resp.body(), ChatCompletionResponse.class);
                            String content = parsed == null || parsed.getFirstChoice() == null
                                    ? null : parsed.getFirstChoice().getContent();
                            if (content == null || content.isBlank()) {
                                addPending(store, lv, startTick, endTick);
                                return;
                            }
                            handleIndexResponse(store, content, lv, startTick, endTick,
                                    level.m_46467_());
                        } catch (Exception e) {
                            LOGGER.info("AiMemoryArchiver: {}索引解析异常 {}", lv, id, e);
                        } finally {
                            ARCHIVING.remove(id);
                        }
                    }));
        } catch (Exception e) {
            ARCHIVING.remove(maid.m_20148_());
        }
    }

    /** 解析严格 JSON 响应并归档（容忍 ```json 围栏） */
    private static void handleIndexResponse(AiMemoryStore store, String content, String lv,
                                            long startTick, long endTick, long gameTime) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('\n');
            int e = json.lastIndexOf("```");
            if (s >= 0 && e > s) {
                json = json.substring(s + 1, e).trim();
            }
        }
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String diary = obj.has("日记") && !obj.get("日记").isJsonNull()
                ? obj.get("日记").getAsString().trim() : "";
        if (diary.isEmpty()) {
            LOGGER.info("AiMemoryArchiver: {}索引生成结果为空（待重试）", lv);
            addPending(store, lv, startTick, endTick);
            return;
        }
        List<String> eventIds = new ArrayList<>();
        if (obj.has("事件节点") && obj.get("事件节点").isJsonArray()) {
            for (var el : obj.get("事件节点").getAsJsonArray()) {
                if (el.isJsonObject() && el.getAsJsonObject().has("id")
                        && !el.getAsJsonObject().get("id").isJsonNull()) {
                    eventIds.add(el.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        store.index().add(new AiMemoryIndexStore.IndexRecord(lv, startTick, endTick,
                (int) (startTick / DAY_TICKS), (int) (endTick / DAY_TICKS),
                diary, eventIds, System.currentTimeMillis()));
        store.saveNow();
        LOGGER.info("AiMemoryArchiver: 已归档{}索引 第{}天~第{}天（关键事件{}个）",
                lv, startTick / DAY_TICKS, endTick / DAY_TICKS, eventIds.size());
    }

    // ========== 短期→长期簇转移（对齐 run_transfer） ==========

    /**
     * 将短期层（short_context）中满足条件的"关联簇"整体转移到长期层。
     *
     * 条件：簇内所有段落的年龄（now - eventTimeEnd）均超过
     * MEMORY_SHORT_TERM_DAYS 游戏日（存在任何节点少于该跨度则不转移）。
     * 簇 = ngram-Jaccard ≥ 0.42 的连通分量（近似 Sphantosis 的图连通分量）。
     * 转移 = 打 long_term 标记（豁免衰减遗忘，检索作为长期记忆加权）。
     */
    public static int runTransfer(AiMemoryStore store, long gameTime) {
        int thresholdDays = com.maidsmart.config.MaidSmartConfig.MEMORY_SHORT_TERM_DAYS.get();
        long threshold = thresholdDays * DAY_TICKS;
        List<AiMemoryModels.Paragraph> candidates = new ArrayList<>();
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            if (!"short_context".equals(p.sourceType()) || p.tags().contains("long_term")) {
                continue;
            }
            candidates.add(p);
        }
        if (candidates.isEmpty()) {
            return 0;
        }
        // 并查集聚类（jaccard ≥ 0.42 连边）
        int n = candidates.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<Set<String>> grams = new ArrayList<>(n);
        for (AiMemoryModels.Paragraph p : candidates) {
            grams.add(ngrams(p.content()));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (jaccard(grams.get(i), grams.get(j)) >= 0.42) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        // 分量分组
        Map<Integer, List<Integer>> comps = new HashMap<>();
        for (int i = 0; i < n; i++) {
            comps.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        int moved = 0;
        for (List<Integer> comp : comps.values()) {
            boolean allOld = true;
            for (int idx : comp) {
                AiMemoryModels.Paragraph p = candidates.get(idx);
                if (gameTime - p.eventTimeEnd() < threshold) {
                    allOld = false;
                    break;
                }
            }
            if (!allOld) {
                continue; // 簇内有新事件——不拆散，等下次
            }
            for (int idx : comp) {
                store.markLongTermByHash(candidates.get(idx).hash());
            }
            moved++;
        }
        if (moved > 0) {
            store.saveNow();
            LOGGER.info("AiMemoryArchiver: 转移 {} 个关联簇（{} 段落）到长期记忆",
                    moved, comps.values().size());
        }
        return moved;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    // ---------- 状态持久化（<store>/archiver_state.json） ----------

    /** 异步回调失败路径：把该边界挂入持久 pending 队列（服务端线程执行） */
    private static void addPending(AiMemoryStore store, String lv, long startTick, long endTick) {
        ArchiverState state = loadState(store);
        PendingIndex item = new PendingIndex(lv, startTick, endTick);
        if (!state.pending().contains(item)) {
            List<PendingIndex> pending = new ArrayList<>(state.pending());
            pending.add(item);
            saveState(store, new ArchiverState(state.lastDay(), state.lastWeek(),
                    state.lastMonth(), pending));
        }
    }

    private static java.nio.file.Path stateFile(AiMemoryStore store) {
        return store.dir().resolve("archiver_state.json");
    }

    private static ArchiverState loadState(AiMemoryStore store) {
        try {
            java.nio.file.Path f = stateFile(store);
            if (java.nio.file.Files.exists(f)) {
                ArchiverState s = AiMemoryModels.GSON.fromJson(
                        java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8),
                        ArchiverState.class);
                if (s != null) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return ArchiverState.initial();
    }

    private static void saveState(AiMemoryStore store, ArchiverState state) {
        try {
            java.nio.file.Files.createDirectories(store.dir());
            java.nio.file.Files.writeString(stateFile(store),
                    AiMemoryModels.GSON.toJson(state), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String pick(String tlmValue, String custom) {
        return custom != null && !custom.isBlank() ? custom.trim() : tlmValue;
    }

    // ---------- 局部 ngram 工具（AiMemoryStore 内同名实现为 private，此处对齐逻辑） ----------

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
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
}
