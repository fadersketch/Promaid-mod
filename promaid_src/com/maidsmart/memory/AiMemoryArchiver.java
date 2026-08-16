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
 * 记忆自动归档器（移植自 Sphantosis computing_engines/memory_archiver.py，
 * v1.5.379 按 promaid 线性架构修订）。
 *
 * v1.5.379 修订（架构适配）：
 * - 【触发】索引生成只发生在【真实睡眠收尾】（SleepFinishedTimeEvent——
 *   全员睡过夜、时间被跳到清晨才触发），熬夜过夜/未入睡不再触发归档；
 *   周期调度退化为纯 pending 重试（每 scanInterval 秒，O(pending) 轻量）
 * - 【转移】移除 v1.5.378 的 ngram-Jaccard 伪图聚簇——promaid 段落是扁平
 *   线性列表、单一存储，Sphantosis 的"图连通分量整簇转移"（保住 LLM 建立
 *   的因果边簇 + 双存储迁移）在此没有对应物，伪图聚簇是 O(n²) 常驻开销的
 *   负优化。改为与线性架构匹配的单遍规则：短期层段落年龄 ≥ shortTermDays
 *   且重要度 ≥ 衰减保留线 → 打 long_term 标记（豁免衰减遗忘）
 * - 【上下文长度】所有级别索引的事件块增加 indexMaxEvents 上限（超限按
 *   重要度裁剪后再按时间排序；月级另受 indexMonthTopN 更紧约束）——
 *   忙日不会撑爆摘要 prompt
 * - 【跨度管理】多日未睡产生的日级空档不回填——由 3日/周/月 更粗粒度
 *   层级自然覆盖（多级索引自愈），睡觉时只生成"刚结束这一天"+滚动3日
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

    // ========== 周期调度入口（仅重试，不生成） ==========

    /**
     * 周期调度入口（AiMemoryManager 每 scanInterval 秒调用）。
     * v1.5.379 起只做失败索引的 pending 重试——索引生成收敛到真实睡眠收尾
     * （sleepWrapUp），熬夜过夜不再触发归档。无 pending 时零 LLM、零遍历。
     */
    public static void tick(EntityMaid maid, ServerLevel level) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            return;
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        ArchiverState state = loadState(store);
        if (state.pending().isEmpty()) {
            return;
        }
        List<PendingIndex> rest = new ArrayList<>();
        for (PendingIndex item : state.pending()) {
            if (isArchivedOrEmpty(store, item)) {
                continue; // 已归档（has 容差命中）或跨度无事件 → 出队
            }
            generateIndex(maid, level, item.level(), item.startTick(), item.endTick());
            rest.add(item); // 发出后仍留在队列——直到 has() 确认才出队（关服竞态可自愈）
        }
        if (!rest.equals(state.pending())) {
            saveState(store, new ArchiverState(state.lastDay(), state.lastWeek(),
                    state.lastMonth(), rest));
        }
    }

    // ========== 睡眠收尾入口（叙事日维度） ==========

    /**
     * 睡一觉自动处理（移植 Sphantosis 的 start_role_sleep → wrap-up →
     * archiver.tick(force_day_index=True) 链路；由 SleepFinishedTimeEvent
     * 调用——全员真实睡过夜、时间跳到清晨，排除熬夜/未入睡的假触发）。
     *
     * - 首次调用对齐边界标记（不追溯历史跨度，但仍执行转移）
     * - 跨日：生成刚结束这一天的「日」索引 + 滚动「3日」索引
     *   （多日未睡的中间空档不逐日回填——由 3日/周/月 粗粒度层级覆盖）
     * - 跨周/跨月：生成上周/上月索引
     * - 重试此前失败的索引边界
     * - 执行短期→长期提升（年龄 + 重要度单遍规则）
     */
    public static void sleepWrapUp(EntityMaid maid, ServerLevel level) {
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
            saveState(store, new ArchiverState(day, week, month, state.pending()));
            runTransfer(store, gameTime);
            return;
        }

        List<PendingIndex> pending = new ArrayList<>(state.pending());
        if (day != state.lastDay()) {
            // 刚结束的一天（完整一天）
            // 审计 P-5：先生成完整天前清掉登出 partial 生成的部分天索引，避免重复日级日记
            store.index().removeCovered(AiMemoryIndexStore.LEVEL_DAY,
                    (day - 1) * DAY_TICKS, day * DAY_TICKS);
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

        // 重试此前失败的索引边界（已确认归档/空跨度才出队）
        if (!pending.isEmpty()) {
            List<PendingIndex> rest = new ArrayList<>();
            for (PendingIndex item : pending) {
                if (isArchivedOrEmpty(store, item)) {
                    continue;
                }
                generateIndex(maid, level, item.level(), item.startTick(), item.endTick());
                rest.add(item);
            }
            pending = rest;
        }

        ArchiverState next = new ArchiverState(day, week, month, pending);
        if (!next.equals(state)) {
            saveState(store, next);
        }

        // 短期→长期提升
        runTransfer(store, gameTime);
    }

    // ========== 会话收尾入口（真人日维度，v1.5.380） ==========

    /**
     * 会话收尾（玩家登出/退出游戏）——补齐 Sphantosis 睡眠语义中被漏掉的
     * 【真人用户】维度：原项目的 wrap-up 不只覆盖角色入睡，也覆盖真人用户
     * 结束一天的聊天下线（用户状态机 active/sleeping/offline）。MC 里真人
     * 睡觉 = 退出游戏：登出时把当日（部分天）「日」级日记边界【先持久化进
     * pending 再尝试生成】——单人模式关服后异步响应可能丢失，pending 已
     * 落盘，下次进游戏由周期 tick 自动补生成（"女仆在你睡觉时整理好了
     * 昨天的记忆"）。
     */
    public static void sessionWrapUp(EntityMaid maid, ServerLevel level) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()
                || !com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ON_LOGOUT.get()) {
            return;
        }
        // v1.5.381：服务器/单机区别——只有【服务器】(剧情时间持续推进,玩家登出后
        // 世界照常运转)才需要登出收尾;【单机集成服】玩家退出即关服、剧情时间停滞,
        // 登出处理无意义——记忆原地不动,下次游戏内睡眠收尾会以"完整一天"的日级
        // 索引自然覆盖,无需在关服竞态里抢跑
        MinecraftServer server = level.m_7654_();
        if (!(server instanceof net.minecraft.server.dedicated.DedicatedServer)) {
            return;
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        long gameTime = level.m_46467_();
        int day = (int) (gameTime / DAY_TICKS);
        ArchiverState state = loadState(store);
        PendingIndex item = new PendingIndex(AiMemoryIndexStore.LEVEL_DAY, day * DAY_TICKS, gameTime);
        if (state.lastDay() >= 0 && !state.pending().contains(item)) {
            List<PendingIndex> pending = new ArrayList<>(state.pending());
            pending.add(item);
            // 先落盘再尝试——关服竞态下响应丢失也不丢"待归档"事实
            saveState(store, new ArchiverState(state.lastDay(), state.lastWeek(),
                    state.lastMonth(), pending));
        }
        sleepWrapUp(maid, level); // 复用：边界检查 + pending 重试 + 短期→长期提升
    }

    /** pending 出队判定：已归档（has 容差命中 / 被更长记录覆盖）或跨度无事件（空跨度视为已处理） */
    private static boolean isArchivedOrEmpty(AiMemoryStore store, PendingIndex item) {
        if (store.index().has(item.level(), item.startTick(), item.endTick())) {
            return true;
        }
        // 审计优化3：登出的"当日部分天"被后续"完整一天"覆盖时也算已归档——
        // 避免同一天生成两份近似日记、花两次 LLM
        if (store.index().covers(item.level(), item.startTick(), item.endTick())) {
            return true;
        }
        return !hasEventsInRange(store, item.startTick(), item.endTick());
    }

    /** 跨度内是否存在可索引的事件段落（首个命中即返回——判空专用，不拼事件块字符串） */
    private static boolean hasEventsInRange(AiMemoryStore store, long startTick, long endTick) {
        java.util.Set<String> seen = new HashSet<>();
        for (String hash : store.timeIndex().queryRange(startTick, endTick)) {
            if (seen.contains(hash)) {
                continue;
            }
            seen.add(hash);
            AiMemoryModels.Paragraph p = store.paragraphByHash(hash);
            if (p == null || p.deleted()) {
                continue;
            }
            if (p.sourceType().equals("summary") || p.tags().contains("daily")) {
                continue;
            }
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 边界先入队再尝试生成（在途丢失由 pending 自愈；对齐 _generate_or_retry 语义增强版） */
    private static void generateOrRetry(EntityMaid maid, ServerLevel level, String lv,
                                        long startTick, long endTick, List<PendingIndex> pending) {
        PendingIndex item = new PendingIndex(lv, startTick, endTick);
        if (!pending.contains(item)) {
            pending.add(item);
        }
        generateIndex(maid, level, lv, startTick, endTick);
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
        // 异步发出即视为本 tick 处理完（结果回调里落库；失败在回调里挂 pending）
        return true;
    }

    /**
     * 收集跨度内事件段落（跳表范围查询；排除摘要层/已删除/被否定），格式化为摘要输入。
     * 上下文长度管理：月级仅保留重要度 top indexMonthTopN；其余级别超 indexMaxEvents
     * 时按重要度裁剪——忙日不会撑爆摘要 prompt。
     */
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
        int cap;
        if (AiMemoryIndexStore.LEVEL_MONTH.equals(lv)) {
            cap = com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_MONTH_TOP_N.get();
        } else {
            cap = com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_MAX_EVENTS.get();
        }
        if (nodes.size() > cap) {
            nodes.sort((a, b) -> Integer.compare(b.salience(), a.salience()));
            nodes = new ArrayList<>(nodes.subList(0, cap));
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
                + "跨度内的关键事件节点（格式：[天|类型] 内容（重要性k=xx）【事件节点id：hash】，重要性越高越值得写进日记；"
                + "若事件过多已被按重要性截取，日记侧重最重要的事件即可）：\n"
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
                                addPending(store, lv, startTick, endTick); // 下个周期重试
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
                            handleIndexResponse(store, content, lv, startTick, endTick);
                        } catch (Exception e) {
                            // 审计S2修复：解析异常（LLM偶发坏JSON）不再静默丢边界——
                            // 挂入 pending 由周期 tick 重试（对齐 Sphantosis 重试语义）
                            LOGGER.info("AiMemoryArchiver: {}索引解析异常 {}（待重试）", lv, id, e);
                            addPending(store, lv, startTick, endTick);
                        } finally {
                            ARCHIVING.remove(id);
                        }
                    }));
        } catch (Exception e) {
            // 审计 P-7：同步构造/发送失败不再静默，记录日志并挂入持久 pending 重试
            UUID id = maid.m_20148_();
            LOGGER.info("AiMemoryArchiver: 索引请求构造失败 {}（待重试）", id, e);
            ARCHIVING.remove(id);
            addPending(store, lv, startTick, endTick);
        }
    }

    /** 解析严格 JSON 响应并归档（容忍 ```json 围栏） */
    private static void handleIndexResponse(AiMemoryStore store, String content, String lv,
                                            long startTick, long endTick) {
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

    // ========== 短期→长期提升（线性架构适配版） ==========

    /**
     * 短期→长期提升（v1.5.379 重写）。
     *
     * promaid 段落是扁平线性列表（无图边、单一存储），Sphantosis 的
     * "图连通分量整簇转移"没有对应物，也不再做内容相似度伪聚簇——
     * 与线性架构匹配的单遍规则：
     *   短期层（short_context）段落，年龄 ≥ shortTermDays 游戏日
     *   且重要度 ≥ 衰减保留线（decaySalience，即"本可躲过遗忘"的记忆）
     *   → 打 long_term 标记，豁免 prune 衰减遗忘（长期沉淀）。
     * O(n) 单遍，只在睡眠收尾时执行。
     */
    public static int runTransfer(AiMemoryStore store, long gameTime) {
        long threshold = (long) com.maidsmart.config.MaidSmartConfig.MEMORY_SHORT_TERM_DAYS.get() * DAY_TICKS;
        int salienceFloor = com.maidsmart.config.MaidSmartConfig.MEMORY_DECAY_SALIENCE.get();
        // 审计 P-6：long_term 也要受总量约束，避免占满整段记忆预算后不再老化
        int maxLongTerm = Math.max(16, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_ENTRIES.get() / 2);
        int longTermCount = 0;
        for (AiMemoryModels.Paragraph existing : store.paragraphs()) {
            if (existing.tags().contains("long_term")) {
                longTermCount++;
            }
        }
        int moved = 0;
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            if (AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            if (!"short_context".equals(p.sourceType()) || p.tags().contains("long_term")) {
                continue;
            }
            if (longTermCount >= maxLongTerm) {
                continue;
            }
            if (p.salience() < salienceFloor) {
                continue;
            }
            if (gameTime - p.eventTimeEnd() < threshold) {
                continue;
            }
            if (store.markLongTermByHash(p.hash())) {
                moved++;
                longTermCount++;
            }
        }
        if (moved > 0) {
            store.saveNow();
            LOGGER.info("AiMemoryArchiver: {} 条短期记忆沉淀为长期（long_term）", moved);
        }
        return moved;
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

    /** 状态内存缓存（写穿）——审计优化1：消除周期 tick 每 20 秒/女仆的磁盘读 */
    private static final java.util.Map<java.nio.file.Path, ArchiverState> STATE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 状态失效（记忆清空时调用——clearAll 删除 archiver_state.json 后同步丢缓存） */
    static void invalidateState(AiMemoryStore store) {
        STATE_CACHE.remove(stateFile(store));
    }

    private static ArchiverState loadState(AiMemoryStore store) {
        java.nio.file.Path f = stateFile(store);
        ArchiverState cached = STATE_CACHE.get(f);
        if (cached != null) {
            return cached;
        }
        try {
            if (java.nio.file.Files.exists(f)) {
                ArchiverState s = AiMemoryModels.GSON.fromJson(
                        java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8),
                        ArchiverState.class);
                if (s != null) {
                    STATE_CACHE.put(f, s);
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return ArchiverState.initial();
    }

    private static void saveState(AiMemoryStore store, ArchiverState state) {
        STATE_CACHE.put(stateFile(store), state);
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
}
