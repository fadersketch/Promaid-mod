package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.affect.AffectManager;
import com.maidsmart.memory.AiMemoryExtractor;
import com.maidsmart.memory.AiMemoryModels;
import com.maidsmart.memory.AiMemoryStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天观察 + 回复反馈学习（v1.5.191，对齐 maidsoulcore ReplyEffectTracker 精简版）。
 *
 * 每 5 秒扫一次女仆聊天历史（TLM 公共 API，只读，与 AiMemoryExtractor 同源），
 * 按 gameTime watermark 只处理新消息，提供三块能力：
 *
 * 1. 真实沉默计时：ownerChatTick —— 主人最后一次聊天的 gameTime。
 *    （旧版主动对话用"女仆自己上次发言"计时：女仆说完话自己等 20 分钟——
 *    主人没说话也算"沉默"，主人刚说完反而不算；这是 bug。7 阶段状态机
 *    需要真实主人聊天时间。）
 * 2. 主人意图检测：提问（主人上条是问题且女仆未答 → LIGHT_FOLLOWUP 阶段用）、
 *    道歉（→ AffectManager.onOwnerApology）。
 * 3. 回复反馈学习（dialogue.replyFeedback 开关）：
 *    - 负面（别说了/好烦/不是…）→ repairMode + 拉黑该话题到当日结束 +
 *      写 error_mark 记忆（每小时最多 1 条防刷）——主动会话跳过被否定的主题、语气更克制
 *    - 修复（我说的是/你理解错…）→ repairMode（听岔了，下次先确认再开口）
 *    - 正面（谢谢/说得对…）→ 解除 repairMode + 强化对应记忆（salience+1，记得更牢）
 *
 * 聊天历史被清除（原版清空聊天记录）：watermark 兜底重置——已存记忆不受影响，
 * 新对话照常被观察。
 */
public class ReplyFeedbackTracker {
    /** 女仆 UUID → 已处理消息的最大 gameTime（水位） */
    private static final Map<UUID, Long> WATERMARKS = new ConcurrentHashMap<>();
    /** 女仆 UUID → 主人最后一次聊天的 gameTime（真沉默计时） */
    private static final Map<UUID, Long> OWNER_CHAT_TICKS = new ConcurrentHashMap<>();
    /** 女仆 UUID → 主人聊天代次（每次新聊天 +1；主动对话用它判断"该重置周期了"） */
    private static final Map<UUID, Integer> OWNER_GENERATION = new ConcurrentHashMap<>();
    /** 女仆 UUID → 主人上条消息是否提问且女仆尚未回答 */
    private static final Map<UUID, Boolean> QUESTION_PENDING = new ConcurrentHashMap<>();
    /** 女仆 UUID → 最近一次主动话题（负面反馈时拉黑它） */
    private static final Map<UUID, String> LAST_TOPIC = new ConcurrentHashMap<>();
    /** 女仆 UUID → 拉黑话题列表（条目 {话题, 到期 tick}，到期自动失效） */
    private static final Map<UUID, List<String[]>> TOPIC_BLACKLIST = new ConcurrentHashMap<>();
    /** 女仆 UUID → 上次写 error_mark 的时间（小时限流） */
    private static final Map<UUID, Long> LAST_ERROR_MARK = new ConcurrentHashMap<>();
    /** 女仆 UUID → 修复模式（最近被纠正/否定，语气该克制） */
    private static final Map<UUID, Boolean> REPAIR_MODE = new ConcurrentHashMap<>();

    /** 负面模式串（主人嫌女仆话多/说错） */
    private static final String[] NEGATIVE_PATTERNS = {
            "别说了", "闭嘴", "好烦", "烦死", "不想听", "别烦", "少说", "闭嘴吧", "别说话",
            "不是这样", "不是这个意思", "听不懂", "没懂", "没听懂", "错了", "不对", "无语",
            "离谱", "你在说什么", "算了", "停"
    };
    /** 修复模式串（主人指出女仆理解错了——不算负面，但要先确认再开口） */
    private static final String[] REPAIR_PATTERNS = {
            "我说的是", "我是说", "重新说", "再说一遍", "不是问", "你理解错", "你搞错", "我问的是", "纠正", "重新回答"
    };
    /** 正面模式串（主人认可/感谢——解除克制 + 强化记忆） */
    private static final String[] POSITIVE_PATTERNS = {
            "谢谢", "感谢", "懂了", "明白了", "可以", "有用", "不错", "好耶", "太好了",
            "说得对", "有道理", "真贴心", "哈哈", "很棒"
    };
    /** 道歉模式串（主人道歉——安抚情绪，不视为对女仆发言的负面反馈） */
    private static final String[] APOLOGY_PATTERNS = {
            "对不起", "抱歉", "我错了", "对不住", "是我的错", "说重了", "道歉"
    };
    /** 提问起始词（主人消息以这些词开头视为提问） */
    private static final String[] QUESTION_STARTS = {
            "能", "可以", "会", "为什么", "怎么", "啥", "哪", "多少", "帮我", "给我", "去", "看看"
    };

    /** 扫描间隔（tick，100 = 5 秒） */
    private static final int SCAN_TICKS = 100;

    private int tick = 0;

    // ---------- 对外查询（主动对话状态机用） ----------

    /** 主人最后一次聊天的 gameTime（0 = 从没聊过） */
    public static long ownerChatTick(EntityMaid maid) {
        return OWNER_CHAT_TICKS.getOrDefault(maid.m_20148_(), 0L);
    }

    /** 主人聊天代次（每次新聊天 +1；主动对话据此重置周期） */
    public static int ownerGeneration(EntityMaid maid) {
        return OWNER_GENERATION.getOrDefault(maid.m_20148_(), 0);
    }

    /** 主人上条消息是否提问且女仆尚未回答（LIGHT_FOLLOWUP 阶段用） */
    public static boolean isQuestionPending(EntityMaid maid) {
        return QUESTION_PENDING.getOrDefault(maid.m_20148_(), false);
    }

    /** 修复模式：最近被纠正/否定，主动发言应更克制 */
    public static boolean repairMode(EntityMaid maid) {
        return REPAIR_MODE.getOrDefault(maid.m_20148_(), false);
    }

    /** 该话题是否被拉黑（dialogue.topicBackoffMin 分钟内有效） */
    public static boolean topicBlocked(EntityMaid maid, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        UUID id = maid.m_20148_();
        long nowTick;
        try {
            nowTick = maid.m_9236_().m_46467_();
        } catch (Exception e) {
            return false;
        }
        List<String[]> list = TOPIC_BLACKLIST.get(id);
        if (list == null) {
            return false;
        }
        for (String[] e : list) {
            long expiry;
            try {
                expiry = Long.parseLong(e[1]);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (nowTick >= expiry) {
                continue;
            }
            if ((e[0].length() >= 3 && content.contains(e[0])) || e[0].contains(content)) {
                return true;
            }
        }
        return false;
    }

    /** 主动对话开火时记录本次话题（负面反馈时拉黑它） */
    public static void markProactiveFire(EntityMaid maid, String topic) {
        LAST_TOPIC.put(maid.m_20148_(), topic == null ? null : AiMemoryModels.clip(topic, 40));
    }

    // ---------- 周期扫描 ----------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (++this.tick < SCAN_TICKS) {
            return;
        }
        this.tick = 0;
        for (ServerLevel level : server.m_129785_()) {
            for (ServerPlayer player : level.m_6907_()) {
                level.m_45976_(EntityMaid.class, player.m_20191_().m_82400_(128.0)).forEach(this::observe);
            }
        }
    }

    private void observe(EntityMaid maid) {
        if (!maid.m_21824_() || !maid.m_6084_()) {
            return;
        }
        if (!(maid.m_9236_() instanceof ServerLevel)) {
            return;
        }
        MaidAIChatManager cm = maid.getAiChatManager();
        if (cm == null) {
            return;
        }
        UUID id = maid.m_20148_();
        long watermark = WATERMARKS.getOrDefault(id, 0L);
        // 历史被清空兜底：deque 为空或最大 gameTime 小于水位 → 重置水位（不卡死）
        long newest = 0;
        for (LLMMessage msg : cm.getHistory().getDeque()) {
            if (msg.gameTime() > newest) {
                newest = msg.gameTime();
            }
        }
        if (newest < watermark) {
            WATERMARKS.put(id, newest);
            return;
        }
        for (LLMMessage msg : cm.getHistory().getDeque()) {
            if (msg.gameTime() <= watermark) {
                continue;
            }
            if (msg.role() == Role.USER && msg.message() != null && !msg.message().isBlank()
                    && (msg.toolCalls() == null || msg.toolCalls().isEmpty())) {
                handleOwnerMessage(maid, id, msg.message(), msg.gameTime());
            }
        }
        // 提问待答判定：批处理完，最新一条是女仆回复 → 已答
        if (QUESTION_PENDING.getOrDefault(id, false)) {
            LLMMessage lastMsg = null;
            for (LLMMessage msg : cm.getHistory().getDeque()) {
                if (msg.gameTime() > watermark) {
                    lastMsg = msg;
                }
            }
            if (lastMsg != null && lastMsg.role() == Role.ASSISTANT) {
                QUESTION_PENDING.put(id, false);
            }
        }
        WATERMARKS.put(id, newest);
    }

    // ---------- 主人消息处理 ----------

    private static void handleOwnerMessage(EntityMaid maid, UUID id, String text, long gameTime) {
        OWNER_CHAT_TICKS.put(id, gameTime);
        OWNER_GENERATION.merge(id, 1, Integer::sum);
        boolean affectOn = com.maidsmart.config.MaidSmartConfig.AFFECT_ENABLE.get();
        // 真聊天也算"主人对话"（旧版只有右键喂食算——见 AffectEventHooks.onOwnerInteract）
        if (affectOn) {
            AffectManager.onOwnerMessage(maid);
        }
        if (containsAny(text, APOLOGY_PATTERNS)) {
            // 道歉：安抚情绪、消修复债；不视为对女仆发言的负面反馈（跳过下面的拉黑）
            if (affectOn) {
                AffectManager.onOwnerApology(maid);
            }
            REPAIR_MODE.put(id, false);
            return;
        }
        if (isQuestion(text)) {
            QUESTION_PENDING.put(id, true);
            if (affectOn) {
                AffectManager.onOwnerQuestion(maid);
            }
        }
        if (!com.maidsmart.config.MaidSmartConfig.DIALOGUE_REPLY_FEEDBACK.get()) {
            return;
        }
        if (containsAny(text, NEGATIVE_PATTERNS)) {
            REPAIR_MODE.put(id, true);
            blacklistLastTopic(maid, id);
            writeErrorMark(maid, id);
        } else if (containsAny(text, REPAIR_PATTERNS)) {
            REPAIR_MODE.put(id, true);
        } else if (containsAny(text, POSITIVE_PATTERNS)) {
            REPAIR_MODE.put(id, false);
            reinforceLastTopic(maid, id);
        }
    }

    /** 拉黑最近一次主动话题（dialogue.topicBackoffMin 分钟有效） */
    private static void blacklistLastTopic(EntityMaid maid, UUID id) {
        String last = LAST_TOPIC.get(id);
        if (last == null || last.isBlank()) {
            return;
        }
        long expiry;
        try {
            long nowTick = maid.m_9236_().m_46467_();
            long backoff = (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_TOPIC_BACKOFF_MIN.get() * 1200L;
            expiry = nowTick + backoff;
        } catch (Exception e) {
            expiry = Long.MAX_VALUE;
        }
        List<String[]> list = TOPIC_BLACKLIST.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(new String[]{last, String.valueOf(expiry)});
        if (list.size() > 20) {
            list.remove(0);
        }
    }

    /** 写 error_mark 记忆（每小时最多 1 条防刷）——以后主动会话跳过被否定的话题 */
    private static void writeErrorMark(EntityMaid maid, UUID id) {
        long now = System.currentTimeMillis();
        Long lastW = LAST_ERROR_MARK.get(id);
        if (lastW != null && now - lastW < 3600_000L) {
            return;
        }
        LAST_ERROR_MARK.put(id, now);
        try {
            ServerLevel level = (ServerLevel) maid.m_9236_();
            AiMemoryStore store = AiMemoryStore.of(id, AiMemoryExtractor.memoryRoot(level.m_7654_()));
            String topic = LAST_TOPIC.get(id);
            String content = "主人刚才明确表示不想听/觉得我说得不对（"
                    + (topic != null ? "话题：" + topic : "对话中") + "）。以后主动开口要更克制，不要重复提起这件事。";
            AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create("dialogue", "user", content,
                    "error_mark,feedback", 5, false, now, level.m_46467_());
            store.addParagraph(p);
        } catch (Exception ignored) {
        }
    }

    /** 主人认可 → 强化对应记忆（salience+1，记得更牢） */
    private static void reinforceLastTopic(EntityMaid maid, UUID id) {
        String last = LAST_TOPIC.get(id);
        if (last == null || last.isBlank()) {
            return;
        }
        try {
            ServerLevel level = (ServerLevel) maid.m_9236_();
            AiMemoryStore store = AiMemoryStore.of(id, AiMemoryExtractor.memoryRoot(level.m_7654_()));
            store.reinforceByTopic(last);
        } catch (Exception ignored) {
        }
    }

    // ---------- 判定工具 ----------

    private static boolean isQuestion(String text) {
        String s = text.trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.endsWith("?") || s.endsWith("？")) {
            return true;
        }
        for (String q : QUESTION_STARTS) {
            if (s.startsWith(q)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] patterns) {
        for (String p : patterns) {
            if (text.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
