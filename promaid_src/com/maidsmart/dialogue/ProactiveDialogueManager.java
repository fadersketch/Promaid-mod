package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidFavorabilityLevelChangeEvent;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.action.EmotionalActionExecutor;
import com.maidsmart.memory.AiMemoryManager;
import com.maidsmart.memory.AiMemoryModels;
import com.maidsmart.memory.AiMemoryStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆主动对话管理器（P3，v1.5.191 重写为 7 阶段状态机）。
 *
 * 旧版（v1.5.190）：周期扫描按"低血 → 夜晚 → 沉默"三契机一次性挑一个触发，
 * 沉默计时用"女仆自己上次发言"（主人没说话也算沉默、主人刚说完反而不算——bug）。
 *
 * 新版（对齐 maidsoulcore ProactiveStage 7 阶段）：
 * LIGHT_FOLLOWUP → TOPIC_PUSH → WORLD_OBSERVE → RELATION_CANDIDATE
 * → FINAL_NOTICE → LONG_SILENCE_CHECK → IDLE
 * - 阶段推进 = 无主人互动且到点；主人互动（聊天/喂食）→ 周期重置回起点
 * - 每轮主动会话最多 dialogue.maxReplies 次（默认 4），7 阶段不会一次性全喷
 * - 沉默计时改用 ReplyFeedbackTracker 的真实"主人聊天时间"（bug 修复）
 * - 配额只在真正开火前获取：先做阶段选择（纯计算无副作用），选中才 tryAcquire，
 *   失败不推进阶段、不烧冷却（配额改革）
 * - repairMode（主人最近嫌话多/纠正过）时追加克制后缀，被拉黑话题不选
 *
 * 事件驱动（好感/击杀/重伤/死亡）保留旧 4 处理器，走统一 fireEvent()，
 * 计入日上限与全局配额，但不吃每轮 maxReplies 预算（紧急事件优先）。
 */
public class ProactiveDialogueManager {
    /** 每女仆状态机 */
    private static final class Ms {
        ProactiveStage stage = ProactiveStage.start();
        long nextStageAt = 0;   // 当前阶段到期 tick
        int generation = 0;     // 上次见过的主人聊天代次（变化 → 周期重置）
        int firedCount = 0;     // 本轮主动发言次数
        int silentChecks = 0;   // 今日长沉默确认次数
        int day = -1;           // 游戏日（silentChecks 跨日清零）
        int dailyLimitNotified = -1; // v1.5.250：日上限提示已发游戏日（每女仆每日只提示一次）
        long lastFireTick = -1; // 最近一次发言 tick（扫描/事件共用，4 分钟最小间隔）
    }

    /** v1.5.192：states/dailyCount/lastEventTime 改 static——手册调试面板（网络包
     *  线程上下文）需要无实例查询 stageInfo；单实例事件订阅者不影响并发语义 */
    private static final Map<UUID, Ms> states = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> dailyCount = new ConcurrentHashMap<>();
    /** 紧急事件冷却（重伤/死亡，30 秒） */
    private static final Map<UUID, Long> lastEventTime = new ConcurrentHashMap<>();
    private long lastDay = -1;

    /** v1.2.0：单例句柄——纪念日等外部联动经此调用 fireEventFor（ProMaidExtension
     *  只构造一次本类，构造器赋值即可） */
    public static ProactiveDialogueManager INSTANCE;

    public ProactiveDialogueManager() {
        INSTANCE = this;
    }

    private static long proactiveCooldown() {
        return (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_COOLDOWN.get() * 1200L;
    }

    private static int proactiveDaily() {
        return com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_DAILY.get();
    }

    private static int maxReplies() {
        return com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_MAX_REPLIES.get();
    }

    private static long idleMinTicks() {
        return (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_IDLE_MIN.get() * 1200L;
    }

    private static int longSilenceMax() {
        return com.maidsmart.config.MaidSmartConfig.DIALOGUE_LONG_SILENCE_MAX.get();
    }

    /** 阶段延迟（tick） */
    private static long delayTicks(ProactiveStage stage) {
        return stage.delaySeconds <= 0 ? 0 : (long) stage.delaySeconds * 20L;
    }

    /**
     * v1.5.192：主动对话状态机快照（Promaid 手册·链路调试面板用）。
     * 返回中文摘要：当前阶段 / 本轮已发次数 / 修复模式 / 主人聊天时间。
     */
    public static String stageInfo(EntityMaid maid) {
        try {
            Ms s = states.get(maid.m_20148_());
            if (s == null) {
                return "主动对话：未激活（等待主人互动或沉默触发）";
            }
            String stageName = s.stage == ProactiveStage.IDLE ? "空闲" : s.stage.name();
            long ownerTick = ReplyFeedbackTracker.ownerChatTick(maid);
            long nowTick = maid.m_9236_().m_46467_();
            String lastChat = "从未";
            if (ownerTick > 0) {
                long mins = (nowTick - ownerTick) / 1200L;
                lastChat = Math.max(0, mins) + " 分钟前";
            }
            return "主动对话：阶段=" + stageName + "（本轮已发 " + s.firedCount + "/"
                    + maxReplies() + "）· 主人聊天=" + lastChat
                    + " · 修复模式=" + (ReplyFeedbackTracker.repairMode(maid) ? "开" : "关");
        } catch (Exception e) {
            return "主动对话：读取失败";
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE.get()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long tick = server.m_129921_();
        if (tick % ((long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_SCAN.get() * 20) != 0) {
            return;
        }
        long day = tick / 24000L;
        if (day != this.lastDay) {
            this.lastDay = day;
            this.dailyCount.clear();
        }
        for (ServerPlayer player : server.m_6846_().m_11314_()) {
            player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0)).forEach(maid -> {
                if (!maid.m_21824_() || !maid.m_6084_()) {
                    return;
                }
                LivingEntity owner = maid.m_269323_();
                if (!(owner instanceof ServerPlayer) || owner != player) {
                    return;
                }
                this.tryScanFire(maid, player, tick);
            });
        }
    }

    // ---------- 状态机 ----------

    /** v1.5.250：主动对话日上限提示——超限时给主人发系统消息（每女仆每游戏日
     *  只提示一次，防扫描循环刷屏） */
    private void notifyDailyLimit(EntityMaid maid, ServerPlayer player) {
        try {
            UUID id = maid.m_20148_();
            Ms s = this.states.computeIfAbsent(id, k -> new Ms());
            int day = (int) (maid.m_9236_().m_46467_() / 24000L);
            if (s.dailyLimitNotified == day) {
                return;
            }
            s.dailyLimitNotified = day;
            String name = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
            player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a77\u3010\u4e3b\u52a8\u5bf9\u8bdd\u3011" + name
                            + "\u4eca\u5929\u7684\u4e3b\u52a8\u5bf9\u8bdd\u5df2\u8fbe\u4e0a\u9650\uff08"
                            + proactiveDaily() + "\u6b21\uff09\uff0c\u660e\u5929\u518d\u804a\u5427\u3002"));
        } catch (Exception ignored) {
        }
    }

    private void tryScanFire(EntityMaid maid, ServerPlayer player, long tick) {
        UUID id = maid.m_20148_();
        Ms s = this.states.computeIfAbsent(id, k -> new Ms());
        if (s.day != (int) (tick / 24000L)) {
            s.day = (int) (tick / 24000L);
            s.silentChecks = 0;
        }
        // 主人互动（聊天/喂食）→ 周期重置（generation 由 ReplyFeedbackTracker 维护）
        int gen = ReplyFeedbackTracker.ownerGeneration(maid);
        if (gen != s.generation) {
            s.generation = gen;
            s.firedCount = 0;
            s.stage = ProactiveStage.start();
            // 互动后留一段缓冲（4 分钟冷却），不立刻开口
            s.nextStageAt = Math.max(s.nextStageAt, tick + proactiveCooldown());
        }
        // 最小发言间隔（4 分钟，扫描与事件共用）
        if (s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        if (this.dailyCount.getOrDefault(id, 0) >= proactiveDaily()) {
            // v1.5.250：超限提示——每女仆每游戏日只发一次系统消息（扫描每 20 秒
            // 一次，不节流会一直刷）
            this.notifyDailyLimit(maid, player);
            return;
        }
        // v1.5.193：敌袭冻结——周围 12 格有存活敌对生物时，整条 7 阶段主动对话暂停
        //（不烧配额、不推阶段、不记冷却，解除敌袭后恢复继续；情绪价值的话等安全再说）
        if (com.maidsmart.dialogue.PerceptionManager.dangerActive(maid)) {
            return;
        }
        if (tick < s.nextStageAt) {
            return;
        }
        // ---- 阶段选择（纯计算，无副作用；失败不烧配额） ----
        ProactiveStage stage = s.stage;
        if (stage == ProactiveStage.IDLE) {
            if (tick < s.nextStageAt) {
                return;
            }
            // 空闲到期（或周期已跑满）→ 重启新周期
            s.stage = ProactiveStage.start();
            s.firedCount = 0;
            s.nextStageAt = tick + (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_SCAN.get() * 20;
            return;
        }
        String topic = null;
        String trigger = null;
        switch (stage) {
            case LIGHT_FOLLOWUP -> {
                // 主人上条是提问且女仆还没答 → 轻轻追问；修复模式不追问
                if (ReplyFeedbackTracker.isQuestionPending(maid) && !ReplyFeedbackTracker.repairMode(maid)) {
                    trigger = "主人刚才问了你一个问题，但你没有回答上。"
                            + "请主动说一句简短的话，温柔地确认他到底想问什么（别啰嗦）。";
                }
            }
            case TOPIC_PUSH -> {
                topic = memoryTopic(maid, "user_profile,daily");
                if (topic != null && !ReplyFeedbackTracker.topicBlocked(maid, topic)) {
                    trigger = "主人好一阵子没和你说话了，你有点想他。你想起了以前的事："
                            + topic + "。请主动对主人说一句简短的话，提起这件事（要自然，别念说明书）。";
                }
            }
            case WORLD_OBSERVE -> {
                String brief = worldBrief(maid, player);
                if (brief != null) {
                    trigger = "你看了看四周：" + brief + "。"
                            + "请主动对主人说一句简短的话，自然地提到其中一件你注意到的事（结合你对他的情感）。";
                }
            }
            case RELATION_CANDIDATE -> {
                topic = relationMemory(maid);
                if (topic != null && !ReplyFeedbackTracker.topicBlocked(maid, topic)) {
                    trigger = "你想起了你们之间的关系：" + topic
                            + "。请主动对主人说一句简短的话，温柔地提起这件事。";
                }
            }
            case FINAL_NOTICE -> {
                // 接近本轮上限才"不打扰你了"收尾；远没到上限就跳过（不硬凑话）
                if (s.firedCount >= maxReplies() - 1) {
                    trigger = "你已经和主人聊了几句了，他看起来在忙。"
                            + "请主动说一句简短的收尾（比如不打扰你了/有事叫我），别再多说。";
                }
            }
            case LONG_SILENCE_CHECK -> {
                if (s.silentChecks < longSilenceMax()) {
                    trigger = "主人已经很久没有和你说话了，你有点担心他是不是不在。"
                            + "请主动说一句非常简短的确认（比如\"主人还在吗\"），别追问、别展开。";
                }
            }
            default -> {
            }
        }
        if (trigger == null) {
            // 本阶段无内容 → 跳过推进（不烧配额、不记冷却）
            s.stage = stage.next();
            s.nextStageAt = tick + delayTicks(s.stage);
            return;
        }
        // 修复模式：话少、克制（主人最近嫌话多/纠正过）
        if (ReplyFeedbackTracker.repairMode(maid)) {
            trigger += "（主人最近觉得你话多、说过不对——这次只说一句，点到为止。）";
        }
        // v1.5.191：配额只在真正开火前获取——失败不推进阶段、不烧冷却，下轮重试
        if (!ApiQuotaManager.tryAcquire()) {
            return;
        }
        // ---- 开火 ----
        s.lastFireTick = tick;
        s.firedCount++;
        if (stage == ProactiveStage.LONG_SILENCE_CHECK) {
            s.silentChecks++;
        }
        this.dailyCount.merge(id, 1, Integer::sum);
        ReplyFeedbackTracker.markProactiveFire(maid, topic == null ? "" : topic);
        this.playEmotionalActions(maid, player, trigger);
        // v1.5.198：语言强制（dialogue.outputLanguage；留空 = 跟随 TLM/客户端语言）
        maid.getAiChatManager().chat(trigger, ChatInfoUtil.fromMaid(maid), player);
        // ---- 推进 ----
        if (s.firedCount >= maxReplies()) {
            // 本轮额度用尽 → 进入空闲（下次互动或 idleMin 后重启）
            s.stage = ProactiveStage.IDLE;
            s.nextStageAt = tick + idleMinTicks();
        } else {
            s.stage = stage.next();
            s.nextStageAt = tick + delayTicks(s.stage);
        }
    }

    // ---------- 记忆/世界素材 ----------

    /**
     * 从长期记忆挑话题（按 salience 排序，取 tags 与 focus 交集的高重要度段落；
     * 跳过 deleted/error 标记/被拉黑话题）。
     */
    private static String memoryTopic(EntityMaid maid, String focusTags) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return null;
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return null;
        }
        // v1.1.0：统一走灵魂路由（旧版直接读世界目录——灵魂女仆会读错位置，
        // 人格/情绪/关心点等新记忆也会路由不一致）
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        List<AiMemoryModels.Paragraph> paras = new ArrayList<>(store.paragraphs());
        paras.sort(java.util.Comparator
                .comparingInt(AiMemoryModels.Paragraph::salience).reversed());
        String[] focus = focusTags.split(",");
        for (AiMemoryModels.Paragraph p : paras) {
            if (p.deleted() || AiMemoryStore.hasErrorTag(p)) {
                continue;
            }
            String tags = p.tags();
            if (tags == null) {
                continue;
            }
            boolean hit = false;
            for (String f : focus) {
                if (tags.contains(f.trim())) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            String c = p.content();
            if (c == null || c.isBlank()) {
                continue;
            }
            return AiMemoryModels.clip(c, 40);
        }
        return null;
    }

    /** 关系记忆（关系三元组里挑一条置信度最高的非停用关系） */
    private static String relationMemory(EntityMaid maid) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return null;
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return null;
        }
        // v1.1.0：统一走灵魂路由（同 memoryTopic）
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        List<AiMemoryModels.Relation> rels = new ArrayList<>(store.relations());
        rels.sort(java.util.Comparator
                .comparingDouble(AiMemoryModels.Relation::confidence).reversed());
        for (AiMemoryModels.Relation r : rels) {
            if (r.inactive()) {
                continue;
            }
            String s = r.subject() + r.predicate() + r.object();
            if (s.isBlank()) {
                continue;
            }
            return AiMemoryModels.clip(s, 40);
        }
        return null;
    }

    /** 世界简报（真实信息，不瞎编）：夜晚/天气/附近怪物数/主人血量/当前任务 */
    private static String worldBrief(EntityMaid maid, ServerPlayer player) {
        List<String> bits = new ArrayList<>();
        net.minecraft.world.level.Level level = player.m_9236_();
        if (level.m_46468_() >= 13000L) {
            bits.add("现在已经是夜晚");
        }
        if (level.m_46470_()) {
            bits.add("外面雷雨交加");
        } else if (level.m_46758_(player.m_20183_())) {
            bits.add("外面正在下雨");
        }
        // v1.5.195：威胁计数 = 敌对生物 + 对女仆/主人带仇恨的中立生物（isThreat）
        int mobs = 0;
        for (net.minecraft.world.entity.Mob mob : level.m_45976_(net.minecraft.world.entity.Mob.class,
                player.m_20191_().m_82400_(12.0))) {
            if (com.maidsmart.dialogue.PerceptionManager.isThreat(mob, maid)) {
                mobs++;
            }
        }
        if (mobs > 0) {
            bits.add("附近有" + mobs + "个敌人");
        }
        float ratio = player.m_21223_() / Math.max(1.0f, player.m_21233_());
        if (ratio < 0.9f) {
            bits.add("主人生命值只有" + Math.round(ratio * 100.0f) + "%");
        }
        String taskName = "待命";
        if (maid.getTask() != null && maid.getTask().getUid() != null) {
            taskName = maid.getTask().getUid().toString();
        }
        bits.add("我现在的任务是" + taskName);
        if (bits.isEmpty()) {
            return null;
        }
        return String.join("，", bits);
    }

    /** 情绪价值动作：根据契机类型执行对应动作（与说话同时触发）。 */
    private void playEmotionalActions(EntityMaid maid, ServerPlayer player, String trigger) {
        if (trigger.contains("重伤")) {
            EmotionalActionExecutor.walkToOwner(maid, player);
            EmotionalActionExecutor.giveFoodToOwner(maid, player);
        } else if (trigger.contains("夜晚") || trigger.contains("四周")) {
            EmotionalActionExecutor.walkToOwner(maid, player);
            EmotionalActionExecutor.lookAtOwner(maid, player);
        } else {
            EmotionalActionExecutor.walkToOwner(maid, player);
        }
    }

    // ---------- 事件驱动（保留旧 4 处理器，走统一 fireEvent） ----------

    /** 事件开火：配额先判后记，计入日上限与全局配额，但不吃每轮 maxReplies 预算 */
    private void fireEvent(EntityMaid maid, ServerPlayer player, String trigger) {
        UUID id = maid.m_20148_();
        // v1.5.193：敌袭冻结——战斗中不邀功/不感慨/不关心（主人受伤信息由感知红字
        // 气泡覆盖，不会漏报；情绪价值的话等安全再说）
        if (com.maidsmart.dialogue.PerceptionManager.dangerActive(maid)) {
            return;
        }
        if (this.dailyCount.getOrDefault(id, 0) >= proactiveDaily()) {
            // v1.5.250：超限提示——每女仆每游戏日只发一次系统消息（扫描每 20 秒
            // 一次，不节流会一直刷）
            this.notifyDailyLimit(maid, player);
            return;
        }
        if (!ApiQuotaManager.tryAcquire()) {
            return;
        }
        long tick = maid.m_9236_().m_46467_();
        Ms s = this.states.computeIfAbsent(id, k -> new Ms());
        s.lastFireTick = tick;
        this.dailyCount.merge(id, 1, Integer::sum);
        ReplyFeedbackTracker.markProactiveFire(maid, "");
        this.playEmotionalActions(maid, player, trigger);
        // v1.5.198：语言强制（dialogue.outputLanguage；留空 = 跟随 TLM/客户端语言）
        maid.getAiChatManager().chat(trigger, ChatInfoUtil.fromMaid(maid), player);
    }

    /** v1.2.0：外部事件驱动入口（纪念日联动用）——委托 private fireEvent，
     *  复用日上限/全局配额/敌袭冻结/反馈学习；与现有 4 个事件处理器同构 */
    public void fireEventFor(EntityMaid maid, ServerPlayer player, String trigger) {
        this.fireEvent(maid, player, trigger);
    }

    @SubscribeEvent
    public void onFavorabilityLevelUp(MaidFavorabilityLevelChangeEvent event) {
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        if (event.getNewLevel() <= event.getOldLevel()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) owner;
        if (maid.m_20270_(player) > 16.0f) {
            return;
        }
        long tick = maid.m_9236_().m_46467_();
        UUID maidId = maid.m_20148_();
        Ms s = this.states.get(maidId);
        if (s != null && s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        String trigger = "你刚刚和主人的关系变得更好了（好感度提升了）！"
                + "你心里暖暖的，请主动对主人说一句开心的话表达你的喜悦。";
        EmotionalActionExecutor.heartParticles(maid);
        EmotionalActionExecutor.walkToOwner(maid, player);
        this.fireEvent(maid, player, trigger);
    }

    @SubscribeEvent
    public void onMaidKill(LivingDeathEvent event) {
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        if (event.getSource() == null || !(event.getSource().m_7639_() instanceof EntityMaid)) {
            return;
        }
        EntityMaid maid = (EntityMaid) event.getSource().m_7639_();
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) owner;
        if (maid.m_20270_(player) > 16.0f) {
            return;
        }
        long tick = maid.m_9236_().m_46467_();
        UUID maidId = maid.m_20148_();
        Ms s = this.states.get(maidId);
        if (s != null && s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        String trigger = "你刚刚击败了一个敌人，保护了主人。"
                + "请主动对主人说一句简短的话（可以邀功、可以平淡、可以撒娇，取决于你对他的情感）。";
        EmotionalActionExecutor.walkToOwner(maid, player);
        EmotionalActionExecutor.lookAtOwner(maid, player);
        this.fireEvent(maid, player, trigger);
    }

    /**
     * 主人重伤（事件驱动）：玩家受伤后血量低于阈值立即触发，
     * 不受周期扫描限制（仍有 30 秒事件冷却）。
     */
    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        if (!player.m_6084_()) {
            return;
        }
        float ratio = player.m_21223_() / Math.max(1.0f, player.m_21233_());
        if (ratio >= com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_LOW_HP.get() / 100.0f) {
            return;
        }
        long tick = player.m_9236_().m_46467_();
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0))) {
            if (!maid.m_21824_() || !maid.m_6084_() || maid.m_269323_() != player) {
                continue;
            }
            UUID maidId = maid.m_20148_();
            Long lastEvent = this.lastEventTime.get(maidId);
            if (lastEvent != null && tick - lastEvent < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
                continue;
            }
            this.lastEventTime.put(maidId, tick);
            String trigger = "你的主人刚刚受了重伤，生命值很低，看起来很痛苦。"
                    + "请立刻对他说一句简短的话表达担心和关心（结合你对他的情感）。";
            EmotionalActionExecutor.walkToOwner(maid, player);
            EmotionalActionExecutor.giveFoodToOwner(maid, player);
            this.fireEvent(maid, player, trigger);
        }
    }

    /**
     * 主人死亡（事件驱动）：玩家死亡瞬间立即触发 LLM 反馈
     * （记忆写入由 AiMemoryManager.onPlayerDeath 负责，这里只负责即时对话）。
     */
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        long tick = player.m_9236_().m_46467_();
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0))) {
            if (!maid.m_21824_() || !maid.m_6084_() || maid.m_269323_() != player) {
                continue;
            }
            UUID maidId = maid.m_20148_();
            Long lastEvent = this.lastEventTime.get(maidId);
            if (lastEvent != null && tick - lastEvent < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
                continue;
            }
            this.lastEventTime.put(maidId, tick);
            String trigger = "你的主人刚刚在你面前死去了！你非常震惊和难过。"
                    + "请对他说一句简短的话表达你的悲痛和不舍。";
            this.fireEvent(maid, player, trigger);
        }
    }
}
