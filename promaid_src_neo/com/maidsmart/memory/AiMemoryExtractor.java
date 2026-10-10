package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ChatCompletionResponse;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 记忆提取器（v1.5.86，后台异步，对齐 maidsoulcore 的 MemoryExtractorAgent）。
 *
 * 流程（全程不阻塞游戏主线程）：
 * 1. 服务端 tick 扫描女仆聊天历史（TLM getHistory，public API），攒满 threshold 条新对话
 * 2. 自建 HTTP 请求 LLM（复用 TLM 的站点配置：LLM_HTTP_CLIENT / ChatCompletion /
 *    ChatCompletionResponse）——【不经 TLM 的 LLMCallback】，不烧玩家 token 配额、
 *    不污染女仆历史、无气泡/TTS 副作用
 * 3. HttpClient.sendAsync 后台线程执行 → 完成回调切回服务器线程写记忆
 *
 * 失败兜底：失败/超时静默推进提取位置（不重试风暴）；进行中不重复触发；
 * 提取请求超过 5 分钟视为卡死允许重试。
 */
public final class AiMemoryExtractor {
    /** 进行中的提取（女仆 UUID → 开始时间戳）；v1.5.102：超时从配置面板读取（memory.extractTimeoutMin） */
    private static final Map<UUID, Long> EXTRACTING = new HashMap<>();

    /** v1.5.131：提取失败诊断日志（服务器日志可见真实原因：401/超时/站点禁用） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 实测八百二十九c：待总结对话的"在飞"批次（UUID → 轮），成功后从续写队列摘除。 */
    private static final java.util.Map<UUID, List<AiMemoryContinuityStore.Turn>> INFLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 实测八百二十九c：崩溃恢复补发的限频（UUID → 上次尝试毫秒）。 */
    private static final java.util.Map<String, Long> LAST_RECOVERY =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 实测八百二十九c：摘要计数（每 N 次刷一次记忆快照）。 */
    private static final java.util.Map<String, Integer> SUMMARY_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private AiMemoryExtractor() {
    }

    /** v1.2.1：提取是否进行中（手册链路调试面板显示用） */
    public static boolean isExtracting(UUID maidUuid) {
        return EXTRACTING.containsKey(maidUuid);
    }

    /** 记忆根目录：<世界目录>/promaid_memory
     *  v1.5.250【SRG 修复+迁移】：LevelResource.PLAYER_ADVANCEMENTS_DIR 是 "advancements" 不是
     *  ROOT——旧版记忆数据写到了 <世界>/advancements/promaid_memory（读写一致能
     *  工作，但位置错误）；ROOT 是 ROOT（"."）。首次启动把旧目录搬到世界
     *  根目录，搬不动则回退旧位置（数据不丢）。 */
    public static Path memoryRoot(MinecraftServer server) {
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        Path dir = root.resolve("promaid_memory");
        Path legacy = root.resolve("advancements").resolve("promaid_memory");
        try {
            if (!java.nio.file.Files.exists(dir) && java.nio.file.Files.isDirectory(legacy)) {
                try {
                    java.nio.file.Files.move(legacy, dir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception ignored) {
                }
            }
            if (java.nio.file.Files.exists(dir)) {
                return dir;
            }
            if (java.nio.file.Files.isDirectory(legacy)) {
                return legacy; // 迁移失败——回退旧位置，数据不丢
            }
        } catch (Exception ignored) {
        }
        return dir;
    }

    /** 扫描并尝试提取（由 AiMemoryManager 每 400 tick 调用） */
    public static void maybeExtract(EntityMaid maid, MinecraftServer server) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return;
        }
        UUID id = maid.getUUID();
        Long since = EXTRACTING.get(id);
        if (since != null) {
            if (System.currentTimeMillis() - since < (long) com.maidsmart.config.MaidSmartConfig.MEMORY_EXTRACT_TIMEOUT_MIN.get() * 60000L) {
                return; // 提取进行中
            }
            EXTRACTING.remove(id); // 超时卡死 → 允许重试
        }
        MaidAIChatManager cm = maid.getAiChatManager();
        if (cm == null) {
            return;
        }
        LLMSite site = cm.getLLMSite();
        if (!(site instanceof LLMOpenAISite os) || !os.enabled()) {
            return; // 仅 OpenAI 兼容站点支持自建请求；站点缺失/禁用静默跳过
        }
        // v1.5.198：记忆 API 绑定——配置面板可填独立 地址/密钥/模型（格式同 TLM）；
        // 留空 = 跟随 TLM 女仆当前 LLM 站点（默认行为不变）
        String model = pick(cm.getLLMModel(), com.maidsmart.config.MaidSmartConfig.MEMORY_API_MODEL.get());
        if (model == null || model.isBlank()) {
            return;
        }
        // v1.5.251：绑定灵魂的女仆读写全局灵魂目录（跨存档双向同步）
        AiMemoryStore store = maid.level() instanceof net.minecraft.server.level.ServerLevel sLevel
                ? com.maidsmart.soul.SoulBindingService.storeFor(maid, sLevel)
                : AiMemoryStore.of(id, memoryRoot(server));
        long last = store.meta().lastExtractedTime();
        // 实测八百二十九c：崩溃恢复——上次没总结完的对话（关服/崩溃遗留）优先补发一次
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_CONTINUITY_ENABLE.get()) {
            recoverPending(maid, server, os, model, store, maid.level().getGameTime());
        }
        List<LLMMessage> fresh = new ArrayList<>();
        for (LLMMessage msg : cm.getHistory().getDeque()) {
            if (msg.gameTime() > last && isChatMessage(msg)) {
                fresh.add(msg);
            }
        }
        // v1.5.190：聊天历史被清空保护——watermark 兜底推进到当前最新消息，
        // 避免"清掉历史 → 新对话永远攒不满 8 条"的静默丢失（原版清聊天记录
        // 只影响对话历史，不影响已存的 promaid_memory；此处只是不让提取器卡死）
        long newestInHistory = 0;
        for (LLMMessage msg : cm.getHistory().getDeque()) {
            if (msg.gameTime() > newestInHistory) {
                newestInHistory = msg.gameTime();
            }
        }
        if (fresh.isEmpty() && newestInHistory > last) {
            store.setMeta(new AiMemoryModels.Meta(newestInHistory, store.meta().lastDailyDay()));
            return;
        }
        int threshold = com.maidsmart.config.MaidSmartConfig.MEMORY_EXTRACT_THRESHOLD.get();
        if (fresh.size() < threshold) {
            return;
        }
        // 取最新 threshold 条（deque 时间有序）
        List<LLMMessage> batch = fresh.subList(fresh.size() - threshold, fresh.size());
        EXTRACTING.put(id, System.currentTimeMillis());
        long newestTime = batch.get(batch.size() - 1).gameTime();
        long gameTime = maid.level().getGameTime();
        int maxChars = com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_MESSAGE_CHARS.get();
        // v1.1.0：双 agent——摘要与事实/事件分两次独立 prompt（更聚焦、互不阻塞）；
        // memory.dualAgent 关闭时回退单 agent 合并版（原行为）
        String prompt = buildPrompt(batch, maxChars);
        String summaryPrompt = buildSummaryPrompt(batch, maxChars);
        String factsPrompt = buildFactsPrompt(batch, maxChars);
        // 实测八百二十九c：发请求前把这批对话挂进续写队列（关服/崩溃后仍能补发）
        continuityEnqueue(store, id, batch, gameTime);
        sendExtraction(maid, server, os, model, prompt, summaryPrompt, factsPrompt,
                store, newestTime, gameTime);
    }

    /** v1.5.198：记忆 API 自定义回退——自定义值非空用自定义，否则跟随 TLM 站点 */
    private static String pick(String tlmValue, String custom) {
        return custom != null && !custom.isBlank() ? custom.trim() : tlmValue;
    }

    /** 仅纯文本对话消息（跳过工具调用/系统消息）。
     *  v1.5.231b：跳过【纯模组注入】的 user 消息——部分模组
     *  会把"当前状态/信任值/恐惧值/心情"模板拼进玩家消息；若整条消息几乎全是
     *  注入特征（含状态字段 + 短），判定为纯注入，不参与记忆提取（否则 LLM 会
     *  反复把注入内容提取成"玩家重复设定"——"记忆系统认为玩家在多次重复设定"）。 */
    private static boolean isChatMessage(LLMMessage msg) {
        Role role = msg.role();
        if (role != Role.USER && role != Role.ASSISTANT) {
            return false;
        }
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            return false;
        }
        if (msg.message() == null || msg.message().isBlank()) {
            return false;
        }
        if (role == Role.USER && isInjectedStatusOnly(msg.message())) {
            return false;
        }
        return true;
    }

    /** v1.5.231b：消息是否【几乎全是】模组注入的状态描述 */
    private static boolean isInjectedStatusOnly(String text) {
        String t = text.trim();
        if (t.length() > 120) {
            return false; // 长消息含真实内容，交给规则提示处理
        }
        boolean hasStatus = t.contains("信任值") || t.contains("恐惧值")
                || t.contains("当前状态") || t.contains("心情")
                || t.contains("好感值") || t.contains("状态：");
        if (!hasStatus) {
            return false;
        }
        // 短消息 + 含状态字段 → 大概率是注入模板（真实玩家消息一般不会这样写）
        return true;
    }

    // ---------- 提取指令 ----------

    /** 单 agent 合并版（memory.dualAgent=关 时用，原行为） */
    private static String buildPrompt(List<LLMMessage> batch, int maxChars) {
        return buildPromptBase(batch, maxChars, true, true);
    }

    /** v1.1.0：双 agent·摘要 agent（SUMMARY/IMPORTANCE/TAGS） */
    private static String buildSummaryPrompt(List<LLMMessage> batch, int maxChars) {
        return buildPromptBase(batch, maxChars, true, false);
    }

    /** v1.1.0：双 agent·事实 agent（TAGS/FACT/EVENT） */
    private static String buildFactsPrompt(List<LLMMessage> batch, int maxChars) {
        return buildPromptBase(batch, maxChars, false, true);
    }

    private static String buildPromptBase(List<LLMMessage> batch, int maxChars,
                                          boolean includeSummary, boolean includeFacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是车万女仆的长期记忆提取器。以下是最近 ").append(batch.size())
                .append(" 条与主人的对话记录。\n");
        sb.append("提取值得长期记住的信息，严格按以下格式逐行输出（没有就写 NONE，不要输出其他内容）：\n\n");
        if (includeSummary) {
            sb.append("SUMMARY: 一句话摘要（这段对话发生了什么）\n");
            sb.append("IMPORTANCE: 1-5（这段对话整体重要度）\n");
            // 实测八百二十九c：记忆快照——女仆第一人称的一段"心里话"（≤200字），
            // 每 N 次摘要才真正落盘（memory.snapshotEveryN）；没有深刻经历写 none。
            sb.append("SNAPSHOT: 用女仆第一人称、不超过200字写下最近最深刻的一件事的心里话"
                    + "（如\"我一直记得那天主人……\"）；没有值得一直记住的事写 none\n");
        }
        sb.append("TAGS: tag1,tag2（英文小写，如 relationship,preference,world）\n");
        if (includeFacts) {
            sb.append("FACT: category|key|confidence|content（每行一条；category 只能是 preference,boundary,trait,relation,promise；key 简短英文；confidence 1-5；content 中文一句话，可直接进入用户画像）\n");
            // v1.3.0(beta)【移植自 Sphantosis 的事件元数据】EVENT 行补两个字段：
            //   attention（注意力等级 A/B/C/D）+ open（事件是否未结束 1/0）。
            sb.append("EVENT: salience|attention|open|content（每行一条；salience 1-10；"
                    + "attention 是 A/B/C/D 单字母；open 是 1 或 0；content 中文一句话）\n\n");
            sb.append("attention（注意力等级，只评这条事件里主人投入的关注程度，"
                    + "与事件核心无关的内容不计入）：A=极端专注（危险操作/精密活）；"
                    + "B=较高专注（考试、复杂活）；C=中等（写笔记、聊天反思）；D=最低（丢垃圾这类）。\n");
            sb.append("open（这条事件是否*尚未结束*）：1=明显还没完（主人说要去做某事、在等一个结果、"
                    + "计划尚未落地、悬念未揭开）；0=已完结或看不出（拿不准一律写 0）。"
                    + "这条只对「有明确未完成线索」的事件写 1，宁缺毋滥。\n\n");
        }
        sb.append("规则：\n");
        sb.append("- 不要记录寒暄、临时语气词、模型自夸、无意义重复\n");
        // v1.5.231b：部分模组会把"当前状态/信任值/恐惧值/心情"之类的状态描述注入进
        // 玩家消息——这些不是主人说的话/设定，忽略，不要提取
        sb.append("- 对话里模组注入的状态描述（如\"当前状态\"\"信任值\"\"恐惧值\"\"心情\"等）"
                + "不是主人的真实表达，忽略它们，不要提取为设定/事实\n");
        sb.append("- 只从用户明确表达或多轮稳定体现的信息提取，不要臆测\n\n");
        sb.append("对话记录：\n");
        for (LLMMessage msg : batch) {
            String who = msg.role() == Role.USER ? "用户" : "女仆";
            String text = AiMemoryModels.clip(msg.message(), maxChars).replace('\n', ' ');
            sb.append("[").append(who).append("] ").append(text).append('\n');
        }
        return sb.toString();
    }

    // ---------- 后台请求 ----------

    /**
     * v1.1.0：双 agent 分发——memory.dualAgent 开启时，摘要与事实/事件各发一次
     * 独立请求（任一失败不阻塞对方；水位线等两个都完成才推进，防重试风暴）；
     * 关闭时回退单 agent 合并请求（原行为）。
     */
    private static void sendExtraction(EntityMaid maid, MinecraftServer server, LLMOpenAISite site,
                                       String model, String singlePrompt, String summaryPrompt,
                                       String factsPrompt, AiMemoryStore store,
                                       long newestTime, long gameTime) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_DUAL_AGENT.get()) {
            sendSingle(maid, server, site, model, singlePrompt, store, newestTime, gameTime);
            return;
        }
        // 双 agent：两个请求共享完成计数（回调全部经 server.execute 切回服务器线程，
        // 串行执行，计数安全）
        int[] remaining = {2};
        sendAgent(maid, server, site, model, summaryPrompt, store, newestTime, gameTime, remaining, true);
        sendAgent(maid, server, site, model, factsPrompt, store, newestTime, gameTime, remaining, false);
    }

    /** 单 agent 合并请求（memory.dualAgent=关 的原行为） */
    private static void sendSingle(EntityMaid maid, MinecraftServer server, LLMOpenAISite site,
                                   String model, String prompt, AiMemoryStore store,
                                   long newestTime, long gameTime) {
        try {
            ChatCompletion req = ChatCompletion.create().model(model).userChat(prompt);
            if (site.hasThinkingField()) {
                req = req.disableThinking(); // 提取不需要思考，省钱
            }
            HttpRequest.Builder builder = buildRequest(site, req);
            LLMSite.LLM_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> server.execute(() -> {
                        UUID id = maid.getUUID();
                        try {
                            String content = responseContent(resp, err);
                            if (content == null) {
                                logFail(maid, err, resp);
                                fail(store, newestTime, id);
                                return;
                            }
                            handleExtraction(store, content, newestTime, gameTime, id);
                        } catch (Exception e) {
                            fail(store, newestTime, id);
                        }
                    }));
        } catch (Exception e) {
            // 构造请求失败（URL 非法等）——同样推进位置，防卡死
            EXTRACTING.remove(maid.getUUID());
        }
    }

    /**
     * v1.1.0：双 agent 单请求——完成回调里按 agent 类型写记忆，再结算计数。
     * isSummaryAgent=true → 摘要；false → 事实/事件。
     */
    private static void sendAgent(EntityMaid maid, MinecraftServer server, LLMOpenAISite site,
                                  String model, String prompt, AiMemoryStore store,
                                  long newestTime, long gameTime, int[] remaining,
                                  boolean isSummaryAgent) {
        UUID id = maid.getUUID();
        try {
            ChatCompletion req = ChatCompletion.create().model(model).userChat(prompt);
            if (site.hasThinkingField()) {
                req = req.disableThinking();
            }
            HttpRequest.Builder builder = buildRequest(site, req);
            LLMSite.LLM_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> server.execute(() -> {
                        try {
                            String content = responseContent(resp, err);
                            if (content == null) {
                                logFail(maid, err, resp);
                                done(store, newestTime, id, remaining);
                                return;
                            }
                            if (isSummaryAgent) {
                                handleSummary(store, content, gameTime, id);
                            } else {
                                handleFacts(store, content, gameTime);
                            }
                            done(store, newestTime, id, remaining);
                        } catch (Exception e) {
                            done(store, newestTime, id, remaining);
                        }
                    }));
        } catch (Exception e) {
            // 构造请求失败（URL 非法等）——同样计入完成计数
            done(store, newestTime, id, remaining);
        }
    }

    /** 构造 OpenAI 兼容请求（共享 URL/密钥/超时/额外头逻辑） */
    private static HttpRequest.Builder buildRequest(LLMOpenAISite site, ChatCompletion req) {
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
        return builder;
    }

    /** 成功响应正文；失败/非 200/空 body/解析异常返回 null */
    private static String responseContent(HttpResponse<String> resp, Throwable err) {
        if (err != null || resp == null || resp.statusCode() != 200
                || resp.body() == null || resp.body().isBlank()) {
            return null;
        }
        try {
            ChatCompletionResponse parsed = AiMemoryModels.GSON
                    .fromJson(resp.body(), ChatCompletionResponse.class);
            String content = parsed == null || parsed.getFirstChoice() == null
                    ? null : parsed.getFirstChoice().getContent();
            return (content == null || content.isBlank()) ? null : content;
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取失败诊断日志（服务器日志可见真实原因：401/超时/站点禁用） */
    private static void logFail(EntityMaid maid, Throwable err, HttpResponse<String> resp) {
        LOGGER.info("AiMemoryExtractor: 提取失败 {}（err={}，status={}）",
                maid.getUUID(),
                err != null ? err.getClass().getSimpleName() : "null",
                resp == null ? "null" : resp.statusCode());
    }

    /** v1.1.0：双 agent 完成结算——两个 agent 都完成（无论成败）才推进水位线 + 移除进行中标记 */
    private static void done(AiMemoryStore store, long newestTime, UUID id, int[] remaining) {
        if (remaining[0] <= 0) {
            return;
        }
        remaining[0]--;
        if (remaining[0] == 0) {
            store.setMeta(new AiMemoryModels.Meta(newestTime, store.meta().lastDailyDay()));
            EXTRACTING.remove(id);
            continuityComplete(store, id); // 实测八百二十九c：成功 → 从续写队列摘除
        }
    }

    /** 失败/空响应：静默推进提取位置（防重试风暴），移除进行中标记 */
    private static void fail(AiMemoryStore store, long newestTime, UUID id) {
        store.setMeta(new AiMemoryModels.Meta(newestTime, store.meta().lastDailyDay()));
        EXTRACTING.remove(id);
        continuityDrop(id); // 实测八百二十九c：失败 → 留在续写队列，下次补发
    }

    // ---------- 实测八百二十九c：续写队列 / 快照 / 崩溃恢复 ----------

    /** 把这一批对话转成续写队列的轮，并挂进"在飞"表。 */
    private static void continuityEnqueue(AiMemoryStore store, UUID id, List<LLMMessage> batch, long gameTime) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MEMORY_CONTINUITY_ENABLE.get()) {
                return;
            }
            List<AiMemoryContinuityStore.Turn> turns = new ArrayList<>(batch.size());
            for (LLMMessage m : batch) {
                turns.add(new AiMemoryContinuityStore.Turn(
                        m.role() == Role.USER ? "user" : "maid", m.message(), m.gameTime()));
            }
            store.continuity().enqueue(turns);
            INFLIGHT.put(id, turns);
        } catch (Throwable ignored) {
        }
    }

    /** 成功：从续写队列摘除本批。 */
    private static void continuityComplete(AiMemoryStore store, UUID id) {
        try {
            List<AiMemoryContinuityStore.Turn> turns = INFLIGHT.remove(id);
            if (turns != null) {
                store.continuity().complete(turns);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 失败：只清"在飞"表，续写队列里的待办保留供恢复。 */
    private static void continuityDrop(UUID id) {
        INFLIGHT.remove(id);
    }

    /**
     * 崩溃/关服恢复：续写队列里还有没总结完的对话 → 重发一次（限频 60 秒，避免失败时刷请求）。
     * 成功写回后从队列摘除。
     */
    private static void recoverPending(EntityMaid maid, MinecraftServer server, LLMOpenAISite site,
                                       String model, AiMemoryStore store, long gameTime) {
        UUID id = maid.getUUID();
        if (EXTRACTING.containsKey(id) || INFLIGHT.containsKey(id)) {
            return;
        }
        List<AiMemoryContinuityStore.Turn> pending = store.continuity().nextBatch();
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastTry = LAST_RECOVERY.get(id.toString());
        if (lastTry != null && now - lastTry < 60000L) {
            return;
        }
        LAST_RECOVERY.put(id.toString(), now);
        String prompt = buildRecoveryPrompt(pending,
                com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_MESSAGE_CHARS.get());
        try {
            ChatCompletion req = ChatCompletion.create().model(model).userChat(prompt);
            if (site.hasThinkingField()) {
                req = req.disableThinking();
            }
            HttpRequest.Builder builder = buildRequest(site, req);
            LLMSite.LLM_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> server.execute(() -> {
                        String content = responseContent(resp, err);
                        if (content == null) {
                            return; // 下次再试（pending 留着）
                        }
                        try {
                            handleExtraction(store, content, latestTick(pending), gameTime, id);
                            store.continuity().complete(pending);
                            LOGGER.info("AiMemoryExtractor: 补发未总结对话 {} 条 maid={}",
                                    pending.size(), id);
                        } catch (Exception ignored) {
                        }
                    }));
        } catch (Exception ignored) {
        }
    }

    private static long latestTick(List<AiMemoryContinuityStore.Turn> turns) {
        long t = 0;
        for (AiMemoryContinuityStore.Turn turn : turns) {
            t = Math.max(t, turn.tick());
        }
        return t;
    }

    /** 恢复用提示词（与主提示同格式，标注"上次未能总结"）。 */
    private static String buildRecoveryPrompt(List<AiMemoryContinuityStore.Turn> turns, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是车万女仆的长期记忆提取器。以下是【上次未能总结】的 ").append(turns.size())
                .append(" 条与主人的对话记录（服务器中断前遗留）。\n");
        sb.append("提取值得长期记住的信息，严格按以下格式逐行输出（没有就写 NONE）：\n\n");
        sb.append("SUMMARY: 一句话摘要（这段对话发生了什么）\n");
        sb.append("IMPORTANCE: 1-5（这段对话整体重要度）\n");
        sb.append("TAGS: tag1,tag2（英文小写）\n");
        sb.append("FACT: category|key|confidence|content（每行一条）\n");
        sb.append("EVENT: salience|attention|open|content（每行一条）\n\n");
        sb.append("对话记录：\n");
        for (AiMemoryContinuityStore.Turn t : turns) {
            sb.append("[").append("user".equals(t.role()) ? "用户" : "女仆").append("] ")
                    .append(AiMemoryModels.clip(t.text(), maxChars).replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    /** 解析 SNAPSHOT 行（无则空串）。 */
    private static String parseSnapshot(String content) {
        if (content == null) {
            return "";
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("SNAPSHOT:")) {
                return line.substring("SNAPSHOT:".length()).trim();
            }
        }
        return "";
    }

    /** 每 N 次摘要刷一次记忆快照：优先用模型给的 SNAPSHOT，没给就用摘要兜底。 */
    private static void maybeUpdateSnapshot(AiMemoryStore store, String content, String summary, UUID id) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_SNAPSHOT_ENABLE.get()) {
            return;
        }
        int n = SUMMARY_COUNT.merge(id.toString(), 1, Integer::sum);
        int every = Math.max(1, com.maidsmart.config.MaidSmartConfig.MEMORY_SNAPSHOT_EVERY_N.get());
        if (n % every != 0) {
            return;
        }
        String snap = parseSnapshot(content);
        if (snap.isBlank() || snap.equalsIgnoreCase("none")) {
            snap = summary == null ? "" : summary;
        }
        if (!snap.isBlank() && !snap.equalsIgnoreCase("none")) {
            store.saveSnapshot(snap);
        }
    }

    // ---------- 解析与写入 ----------

    /** 提取结果 */
    private record Extraction(String summary, int importance, List<String> tags,
                              List<String> facts, List<String> events) {
    }

    private static Extraction parse(String content) {
        SummaryParts s = parseSummaryParts(content);
        FactParts f = parseFactParts(content);
        return new Extraction(s.summary(), s.importance(), s.tags(), f.facts(), f.events());
    }

    /** v1.1.0：摘要部分（SUMMARY/IMPORTANCE/TAGS） */
    private record SummaryParts(String summary, int importance, List<String> tags) {
    }

    private static SummaryParts parseSummaryParts(String content) {
        String summary = "";
        int importance = 3;
        List<String> tags = new ArrayList<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("SUMMARY:")) {
                summary = line.substring("SUMMARY:".length()).trim();
            } else if (line.startsWith("IMPORTANCE:")) {
                try {
                    importance = Integer.parseInt(line.substring("IMPORTANCE:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
                importance = Math.max(1, Math.min(5, importance));
            } else if (line.startsWith("TAGS:")) {
                for (String t : line.substring("TAGS:".length()).trim().split(",")) {
                    if (!t.isBlank()) {
                        tags.add(t.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        return new SummaryParts(summary, importance, tags);
    }

    /** v1.1.0：事实/事件部分（FACT/EVENT） */
    private record FactParts(List<String> facts, List<String> events) {
    }

    private static FactParts parseFactParts(String content) {
        List<String> facts = new ArrayList<>();
        List<String> events = new ArrayList<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("FACT:")) {
                facts.add(line.substring("FACT:".length()).trim());
            } else if (line.startsWith("EVENT:")) {
                events.add(line.substring("EVENT:".length()).trim());
            }
        }
        return new FactParts(facts, events);
    }

    /** 解析结果写入存储（服务器线程内执行） */
    private static void handleExtraction(AiMemoryStore store, String content,
                                         long newestTime, long gameTime, UUID id) {
        Extraction ex = parse(content);
        long now = System.currentTimeMillis();
        if (!ex.summary().isBlank() && !ex.summary().equalsIgnoreCase("none")) {
            writeParagraph(store, AiMemoryType.SUMMARY, ex.summary(),
                    mergeTags(ex.tags(), "summary"), ex.importance() * 2, now, gameTime);
        }
        maybeUpdateSnapshot(store, content, ex.summary(), id); // 实测八百二十九c：单 agent 路也刷快照
        for (String fact : ex.facts()) {
            writeFact(store, fact, now, gameTime);
        }
        for (String ev : ex.events()) {
            writeEvent(store, ev, ex.tags(), now, gameTime);
        }
        store.upsertEntity("主人", "person", now);
        store.prune(now, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_ENTRIES.get());
        store.setMeta(new AiMemoryModels.Meta(newestTime, store.meta().lastDailyDay()));
        EXTRACTING.remove(id);
    }

    /** v1.1.0：双 agent·摘要写入（SUMMARY/IMPORTANCE/TAGS + 实测八百二十九c 的 SNAPSHOT 快照） */
    private static void handleSummary(AiMemoryStore store, String content, long gameTime, UUID id) {
        SummaryParts s = parseSummaryParts(content);
        long now = System.currentTimeMillis();
        if (!s.summary().isBlank() && !s.summary().equalsIgnoreCase("none")) {
            writeParagraph(store, AiMemoryType.SUMMARY, s.summary(),
                    mergeTags(s.tags(), "summary"), s.importance() * 2, now, gameTime);
        }
        maybeUpdateSnapshot(store, content, s.summary(), id);
    }

    /** v1.1.0：双 agent·事实/事件写入（FACT/EVENT + TAGS；upsert/prune 在此） */
    private static void handleFacts(AiMemoryStore store, String content, long gameTime) {
        FactParts f = parseFactParts(content);
        SummaryParts s = parseSummaryParts(content); // 事实 prompt 也带 TAGS 行，供 EVENT 段打标
        long now = System.currentTimeMillis();
        for (String fact : f.facts()) {
            writeFact(store, fact, now, gameTime);
        }
        for (String ev : f.events()) {
            writeEvent(store, ev, s.tags(), now, gameTime);
        }
        store.upsertEntity("主人", "person", now);
        store.prune(now, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_ENTRIES.get());
    }

    /** 统一段落写入（走写入策略分层） */
    private static void writeParagraph(AiMemoryStore store, AiMemoryType type, String content,
                                       String tags, int salience, long now, long gameTime) {
        List<String> tagList = new ArrayList<>();
        for (String t : tags.split(",")) {
            if (!t.isBlank()) {
                tagList.add(t.trim());
            }
        }
        tagList.add(type.name().toLowerCase(java.util.Locale.ROOT));
        AiMemoryWriteStrategy.Plan plan = AiMemoryWriteStrategy.plan(type, salience, tagList);
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "maid", content,
                String.join(",", plan.tags()), plan.salience(), plan.permanent(), now, gameTime);
        store.addParagraph(p);
    }

    /** FACT 行：category|key|confidence|content → 段落 + 画像 + 关系 */
    private static void writeFact(AiMemoryStore store, String fact, long now, long gameTime) {
        String[] parts = fact.split("\\|", 4);
        if (parts.length < 4) {
            return;
        }
        String category = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String key = parts[1].trim();
        double confidence;
        try {
            confidence = Math.max(1, Math.min(5, Integer.parseInt(parts[2].trim())));
        } catch (NumberFormatException e) {
            confidence = 3;
        }
        String content = parts[3].trim();
        if (content.isEmpty() || content.equalsIgnoreCase("none")) {
            return;
        }
        List<String> tags = new ArrayList<>(List.of("user_profile", category, "fact:" + key));
        AiMemoryType type = switch (category) {
            case "boundary" -> AiMemoryType.PREFERENCE;
            case "relation" -> AiMemoryType.RELATION;
            case "promise" -> AiMemoryType.PROMISE;
            case "trait" -> AiMemoryType.PREFERENCE;
            default -> AiMemoryType.PREFERENCE; // preference
        };
        AiMemoryWriteStrategy.Plan plan = AiMemoryWriteStrategy.plan(type, (int) (confidence * 2), tags);
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "maid",
                "主人的" + categoryLabel(category) + "：" + content,
                String.join(",", plan.tags()), plan.salience(), plan.permanent(), now, gameTime);
        store.addParagraph(p);
        // 画像聚合（personId = owner）
        store.upsertProfile(AiMemoryModels.Profile.create("owner",
                "（" + categoryLabel(category) + "）" + content, p.hash(), now));
        // 实测八百二十九c：结构化画像条目（同类同 key 去重合并，带置信度）
        store.upsertProfileItem(AiMemoryModels.ProfileItem.create(
                category, key, content, (int) Math.round(confidence), p.hash(), now));
        // relation 类 FACT 进关系三元组
        if (category.equals("relation") || category.equals("promise")) {
            store.upsertRelation(AiMemoryModels.Relation.create("主人", key, content,
                    confidence / 5.0, p.hash(), plan.permanent(), now));
        }
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "boundary" -> "边界";
            case "relation" -> "关系";
            case "promise" -> "承诺";
            case "trait" -> "特质";
            default -> "偏好";
        };
    }

    /**
     * EVENT 行：{@code salience|content}（旧两段式）或
     * {@code salience|attention|open|content}（v1.3.0(beta)：移植自 Sphantosis 的事件元数据）。
     *
     * <p>两种都认：老模型仍可能只回两段，解析到就按"attention=C、open=0"处理——
     * 绝不因为模型没听话就丢掉整条事件。开标记 {@code open:1} 会进段落 tags，
     * 由 {@link AiMemoryContext} 渲染成「她记挂着的事」那一段。
     */
    private static void writeEvent(AiMemoryStore store, String event, List<String> tags,
                                   long now, long gameTime) {
        String[] parts = event.split("\\|");
        if (parts.length < 2) {
            return;
        }
        int salience;
        try {
            salience = Math.max(1, Math.min(10, Integer.parseInt(parts[0].trim())));
        } catch (NumberFormatException e) {
            salience = 5;
        }
        char attention = 'C';
        boolean open = false;
        String content;
        if (parts.length >= 4) {
            String att = parts[1].trim().toUpperCase(java.util.Locale.ROOT);
            if (!att.isEmpty() && "ABCD".indexOf(att.charAt(0)) >= 0) {
                attention = att.charAt(0);
            }
            String op = parts[2].trim();
            open = op.equals("1") || op.equalsIgnoreCase("true") || op.equals("是");
            content = parts[3].trim();
        } else {
            content = parts[1].trim();
        }
        if (content.isEmpty() || content.equalsIgnoreCase("none")) {
            return;
        }
        // 实测八百二十九c：世界事件策略——过滤原版背景噪音（路遇/刷怪），抬升危险/承诺类重要度
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_EVENT_POLICY.get()) {
            String tagStr = String.join(",", tags) + (open ? ",open:1" : "");
            if (!AiMemoryEventPolicy.shouldPersist(content, salience, tagStr)) {
                return;
            }
            salience = AiMemoryEventPolicy.escalateSalience(content, salience);
        }
        List<String> tagList = new ArrayList<>(tags);
        tagList.add("extracted");
        tagList.add("attention:" + attention);
        if (open) {
            tagList.add("open:1");
        }
        AiMemoryWriteStrategy.Plan plan = AiMemoryWriteStrategy.plan(AiMemoryType.EVENT, salience, tagList);
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "maid", content,
                String.join(",", plan.tags()), plan.salience(), plan.permanent(), now, gameTime);
        store.addParagraph(p);
        // 事件片段
        store.upsertEpisode(AiMemoryModels.Episode.create("chat", content,
                "", gameTime - 100, gameTime, salience / 10.0, p.hash(), now));
    }

    private static String mergeTags(List<String> a, String extra) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(a);
        out.add(extra);
        return String.join(",", out);
    }
}
