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

    private AiMemoryExtractor() {
    }

    /** 记忆根目录：<世界目录>/promaid_memory
     *  v1.5.250【SRG 修复+迁移】：LevelResource.f_78174_ 是 "advancements" 不是
     *  ROOT——旧版记忆数据写到了 <世界>/advancements/promaid_memory（读写一致能
     *  工作，但位置错误）；ROOT 是 f_78182_（"."）。首次启动把旧目录搬到世界
     *  根目录，搬不动则回退旧位置（数据不丢）。 */
    public static Path memoryRoot(MinecraftServer server) {
        Path root = server.m_129843_(net.minecraft.world.level.storage.LevelResource.f_78182_);
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
        UUID id = maid.m_20148_();
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
        AiMemoryStore store = maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sLevel
                ? com.maidsmart.soul.SoulBindingService.storeFor(maid, sLevel)
                : AiMemoryStore.of(id, memoryRoot(server));
        long last = store.meta().lastExtractedTime();
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
        long gameTime = maid.m_9236_().m_46467_();
        String prompt = buildPrompt(batch, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_MESSAGE_CHARS.get());
        sendExtraction(maid, server, os, model, prompt, store, newestTime, gameTime);
    }

    /** v1.5.198：记忆 API 自定义回退——自定义值非空用自定义，否则跟随 TLM 站点 */
    private static String pick(String tlmValue, String custom) {
        return custom != null && !custom.isBlank() ? custom.trim() : tlmValue;
    }

    /** 仅纯文本对话消息（跳过工具调用/系统消息）。
     *  v1.5.231b：跳过【纯模组注入】的 user 消息——爱憎分明（Love Loathe）等
     *  会把"当前状态/信任值/恐惧值/心情"模板拼进用户消息；若整条消息几乎全是
     *  注入特征（含状态字段 + 短），判定为纯注入，不参与记忆提取（否则 LLM 会
     *  反复把注入内容提取成"用户重复设定"——"记忆系统认为用户在多次重复设定"）。 */
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

    /** v1.5.231b：消息是否【几乎全是】模组注入的状态描述（爱憎分明特征） */
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
        // 短消息 + 含状态字段 → 大概率是注入模板（真实用户消息一般不会这样写）
        return true;
    }

    // ---------- 提取指令 ----------

    private static String buildPrompt(List<LLMMessage> batch, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是车万女仆的长期记忆提取器。以下是最近 ").append(batch.size())
                .append(" 条与主人的对话记录。\n");
        sb.append("提取值得长期记住的信息，严格按以下格式逐行输出（没有就写 NONE，不要输出其他内容）：\n\n");
        sb.append("SUMMARY: 一句话摘要（这段对话发生了什么）\n");
        sb.append("IMPORTANCE: 1-5（这段对话整体重要度）\n");
        sb.append("TAGS: tag1,tag2（英文小写，如 relationship,preference,world）\n");
        sb.append("FACT: category|key|confidence|content（每行一条；category 只能是 preference,boundary,trait,relation,promise；key 简短英文；confidence 1-5；content 中文一句话，可直接进入用户画像）\n");
        sb.append("EVENT: salience|content（每行一条；salience 1-10；只记值得长期记住的事件/决定/情绪线索）\n\n");
        sb.append("规则：\n");
        sb.append("- 不要记录寒暄、临时语气词、模型自夸、无意义重复\n");
        // v1.5.231b：爱憎分明（Love Loathe）等模组会把"当前状态/信任值/恐惧值/心情"
        // 之类的状态描述注入进用户消息——这些不是主人说的话/设定，忽略，不要提取
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

    private static void sendExtraction(EntityMaid maid, MinecraftServer server, LLMOpenAISite site,
                                       String model, String prompt, AiMemoryStore store,
                                       long newestTime, long gameTime) {
        try {
            ChatCompletion req = ChatCompletion.create().model(model).userChat(prompt);
            if (site.hasThinkingField()) {
                req = req.disableThinking(); // 提取不需要思考，省钱
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
                                // v1.5.131：失败写日志（旧版静默推进位置——"记忆不增长"
                                // 无法诊断；此处日志能看到状态码/超时/401 等真实原因）
                                LOGGER.info("AiMemoryExtractor: 提取失败 {}（err={}，status={}）",
                                        maid.m_20148_(),
                                        err != null ? err.getClass().getSimpleName() : "null",
                                        resp == null ? "null" : resp.statusCode());
                                fail(store, newestTime, id);
                                return;
                            }
                            ChatCompletionResponse parsed = AiMemoryModels.GSON
                                    .fromJson(resp.body(), ChatCompletionResponse.class);
                            String content = parsed == null || parsed.getFirstChoice() == null
                                    ? null : parsed.getFirstChoice().getContent();
                            if (content == null || content.isBlank()) {
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
            EXTRACTING.remove(maid.m_20148_());
        }
    }

    /** 失败/空响应：静默推进提取位置（防重试风暴），移除进行中标记 */
    private static void fail(AiMemoryStore store, long newestTime, UUID id) {
        store.setMeta(new AiMemoryModels.Meta(newestTime, store.meta().lastDailyDay()));
        EXTRACTING.remove(id);
    }

    // ---------- 解析与写入 ----------

    /** 提取结果 */
    private record Extraction(String summary, int importance, List<String> tags,
                              List<String> facts, List<String> events) {
    }

    private static Extraction parse(String content) {
        String summary = "";
        int importance = 3;
        List<String> tags = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> events = new ArrayList<>();
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
            } else if (line.startsWith("FACT:")) {
                facts.add(line.substring("FACT:".length()).trim());
            } else if (line.startsWith("EVENT:")) {
                events.add(line.substring("EVENT:".length()).trim());
            }
        }
        return new Extraction(summary, importance, tags, facts, events);
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

    /** EVENT 行：salience|content → 段落 + 片段 */
    private static void writeEvent(AiMemoryStore store, String event, List<String> tags,
                                   long now, long gameTime) {
        String[] parts = event.split("\\|", 2);
        if (parts.length < 2) {
            return;
        }
        int salience;
        try {
            salience = Math.max(1, Math.min(10, Integer.parseInt(parts[0].trim())));
        } catch (NumberFormatException e) {
            salience = 5;
        }
        String content = parts[1].trim();
        if (content.isEmpty() || content.equalsIgnoreCase("none")) {
            return;
        }
        List<String> tagList = new ArrayList<>(tags);
        tagList.add("extracted");
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
