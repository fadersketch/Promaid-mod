package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidFavorabilityLevelChangeEvent;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.action.EmotionalActionExecutor;
import com.maidsmart.memory.AiMemoryManager;
import com.maidsmart.memory.AiMemoryModels;
import com.maidsmart.memory.AiMemorySearch;
import com.maidsmart.memory.AiMemoryStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆主动对话（环境优先）。
 *
 * <p>阶段：观察上一轮追问 → 观察此刻环境 → 安静陪伴 → 空闲。主人一开口就重置周期；
 * 每轮最多 {@code dialogue.maxReplies} 次，两次开口之间至少隔 {@code dialogue.proactiveCooldown} 分钟。
 *
 * <p>环境简报来自当前位置、维度、时间、天气、主人血量/手持物/距离、女仆任务与附近威胁。
 * 长期记忆只作为可选补充：用环境关键词检索，只有确实相关的段落才会被带进提示词。
 * 内部触发以 {@link ChatInfoUtil#internal(String)} 标记，由混入把它作为系统消息发送，
 * 不写入主人对话历史，避免模型把女仆自己的提示当成主人发言。
 */
public class ProactiveDialogueManager {
    /** 每女仆状态机 */
    private static final class Ms {
        ProactiveStage stage = ProactiveStage.start();
        long nextStageAt = 0;
        int generation = 0;
        int firedCount = 0;
        long lastFireTick = -1;
        String lastFocus = "";
    }

    private static final Map<UUID, Ms> states = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> dailyCount = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastEventTime = new ConcurrentHashMap<>();
    /** 玩家 UUID → 已提示过“今日主动上限”的游戏日（全服每玩家每天最多一条提示） */
    private static final Map<UUID, Integer> playerLimitNotified = new ConcurrentHashMap<>();
    /** 玩家 UUID → 上次“主人受伤/死亡”事件开火 tick（防同一 tick 多只女仆一起开口） */
    private static final Map<UUID, Long> playerHurtAt = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> playerDeathAt = new ConcurrentHashMap<>();

    public static void forgetMaid(UUID maidUuid) {
        states.remove(maidUuid);
        dailyCount.remove(maidUuid);
        lastEventTime.remove(maidUuid);
    }

    private long lastDay = -1;

    public ProactiveDialogueManager() {
    }

    /** 主动对话是否被任何一层开关允许。 */
    private static boolean allowed() {
        return com.maidsmart.config.MaidSmartConfig.AI_ENABLE.get()
                && com.maidsmart.config.MaidSmartConfig.DIALOGUE_ENABLE.get()
                && com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE.get()
                && AIConfig.LLM_ENABLED.get();
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

    private static long delayTicks(ProactiveStage stage) {
        return stage.delaySeconds <= 0 ? 0 : (long) stage.delaySeconds * 20L;
    }

    /** 状态机快照（手册调试面板用）。 */
    public static String stageInfo(EntityMaid maid) {
        try {
            Ms s = states.get(maid.m_20148_());
            if (s == null) {
                return "主动对话：未激活（等待主人互动或环境触发）";
            }
            String stageName = switch (s.stage) {
                case IDLE -> "空闲";
                case LIGHT_FOLLOWUP -> "追问";
                case WORLD_OBSERVE -> "观察环境";
                case QUIET_MOMENT -> "安静陪伴";
            };
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
            com.maidsmart.diag.Diag.swallow("ProactiveDialogueManager", e);
            return "主动对话：读取失败";
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!allowed()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
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

    private void notifyDailyLimit(EntityMaid maid, ServerPlayer player) {
        try {
            int day = (int) (maid.m_9236_().m_46467_() / 24000L);
            // 每玩家每天只提示一次：女仆多时不再按人头刷聊天栏
            if (playerLimitNotified.getOrDefault(player.m_20148_(), -1) == day) {
                return;
            }
            playerLimitNotified.put(player.m_20148_(), day);
            if (com.maidsmart.combat.BuildShieldGuard.shouldMute(maid)) {
                return;
            }
            player.m_213846_(net.minecraft.network.chat.Component.m_237113_("\u00a77\u3010\u4e3b\u52a8\u5bf9\u8bdd\u3011\u4eca\u5929\u7684\u4e3b\u52a8\u5bf9\u8bdd\u5df2\u8fbe\u4e0a\u9650\uff08"
                            + proactiveDaily() + "\u6b21\uff09\uff0c\u660e\u5929\u518d\u804a\u5427\u3002"));
        } catch (Exception ignored) {
            com.maidsmart.diag.Diag.swallow("ProactiveDialogueManager", ignored);
        }
    }

    private void tryScanFire(EntityMaid maid, ServerPlayer player, long tick) {
        UUID id = maid.m_20148_();
        Ms s = this.states.computeIfAbsent(id, k -> new Ms());
        if (!com.maidsmart.memory.LlmEnableManager.isEnabled(maid)) {
            return; // 单女仆 LLM 关闭：不推进阶段、不烧配额（聊天入口也会静默取消）
        }
        int gen = ReplyFeedbackTracker.ownerGeneration(maid);
        if (gen != s.generation) {
            s.generation = gen;
            s.firedCount = 0;
            s.stage = ProactiveStage.start();
            s.nextStageAt = Math.max(s.nextStageAt, tick + proactiveCooldown());
        }
        if (s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        if (this.dailyCount.getOrDefault(id, 0) >= proactiveDaily()) {
            this.notifyDailyLimit(maid, player);
            return;
        }
        if (com.maidsmart.dialogue.PerceptionManager.dangerActive(maid)) {
            return;
        }
        if (tick < s.nextStageAt) {
            return;
        }
        ProactiveStage stage = s.stage;
        if (stage == ProactiveStage.IDLE) {
            s.stage = ProactiveStage.start();
            s.firedCount = 0;
            s.nextStageAt = tick + (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_SCAN.get() * 20;
            return;
        }
        WorldSnapshot snap = snapshot(maid, player, s.lastFocus);
        String trigger = null;
        String topic = "";
        if (stage == ProactiveStage.LIGHT_FOLLOWUP) {
            if (ReplyFeedbackTracker.isQuestionPending(maid) && !ReplyFeedbackTracker.repairMode(maid)) {
                trigger = "【系统·主动补充，不是主人说的话】"
                        + "主人刚才问了你一个问题，你好像没有答上。"
                        + "请直接补一句回答或确认，别寒暄、别解释为什么没答上，只写要对主人说的话。";
                topic = "followup";
            }
        } else if (stage == ProactiveStage.WORLD_OBSERVE) {
            if (snap.notable()) {
                trigger = observePrompt(snap, false, relevantMemory(maid, snap));
                topic = snap.focus();
            }
        } else if (stage == ProactiveStage.QUIET_MOMENT) {
            trigger = observePrompt(snap, !snap.notable(), relevantMemory(maid, snap));
            topic = "quiet";
        }
        if (trigger == null) {
            s.stage = stage.next();
            s.nextStageAt = s.stage == ProactiveStage.IDLE ? tick + idleMinTicks() : tick + delayTicks(s.stage);
            return;
        }
        // 被主人否定过的话题（dialogue.topicBackoffMin 内）不再主动提，直接跳过本阶段
        if (!topic.isBlank() && ReplyFeedbackTracker.topicBlocked(maid, topic)) {
            s.stage = stage.next();
            s.nextStageAt = s.stage == ProactiveStage.IDLE ? tick + idleMinTicks() : tick + delayTicks(s.stage);
            return;
        }
        if (ReplyFeedbackTracker.repairMode(maid)) {
            trigger += "（主人最近觉得你话多或说错过话——这次只说一句，点到为止。）";
        }
        if (!ApiQuotaManager.tryAcquire()) {
            return;
        }
        s.lastFireTick = tick;
        s.firedCount++;
        this.dailyCount.merge(id, 1, Integer::sum);
        if (!snap.focus().isBlank()) {
            s.lastFocus = snap.focus();
        }
        ReplyFeedbackTracker.markProactiveFire(maid, topic);
        this.playEmotionalActions(maid, player);
        sendInternal(maid, player, trigger);
        if (s.firedCount >= maxReplies()) {
            s.stage = ProactiveStage.IDLE;
            s.nextStageAt = tick + idleMinTicks();
        } else {
            s.stage = stage.next();
            s.nextStageAt = s.stage == ProactiveStage.IDLE ? tick + idleMinTicks() : tick + delayTicks(s.stage);
        }
    }

    // ---------- 环境快照 ----------

    private record WorldSnapshot(String brief, String focus, String memoryQuery, boolean notable) {
    }

    /** 当前环境简报（自主决策等其它 AI 链路复用，避免模型凭空猜天色/天气）。 */
    public static String environmentBrief(EntityMaid maid, ServerPlayer player) {
        return snapshot(maid, player, "").brief();
    }

    private static WorldSnapshot snapshot(EntityMaid maid, ServerPlayer player, String lastFocus) {
        Level level = player.m_9236_();
        ArrayList<String> facts = new ArrayList<>();
        ArrayList<String> focus = new ArrayList<>();
        ArrayList<String> query = new ArrayList<>();

        String dim = dimensionName(level.m_46472_().m_135782_().toString());
        facts.add("你们在" + dim);
        query.add(dim);

        long dayTime = level.m_46468_();
        String phase = timePhase(dayTime);
        facts.add("现在是" + phase);
        if (dayTime >= 12000L && dayTime < 23000L) {
            focus.add("现在已经是" + phase);
            query.add(phase);
        }

        if (level.m_46470_()) {
            facts.add("外面雷雨交加");
            focus.add("外面雷雨交加");
            query.add("雷雨");
        } else if (level.m_46758_(player.m_20183_())) {
            facts.add("外面正在下雨");
            focus.add("外面正在下雨");
            query.add("下雨");
        } else {
            facts.add("天气晴朗");
        }

        double distance = maid.m_20270_(player);
        facts.add("主人离你大约" + Math.round(distance) + "格");

        ItemStack ownerHand = player.m_21205_();
        String ownerItem = itemName(ownerHand);
        if (ownerItem.isEmpty()) {
            facts.add("主人空着手");
        } else {
            facts.add("主人手里拿着" + ownerItem);
            focus.add("主人正拿着" + ownerItem);
            query.add(ownerItem);
        }

        float ownerRatio = player.m_21223_() / Math.max(1.0f, player.m_21233_());
        if (ownerRatio < 0.9f) {
            facts.add("主人生命值" + Math.round(ownerRatio * 100.0f) + "%");
        }
        if (ownerRatio < 0.6f) {
            focus.add("主人生命值只剩" + Math.round(ownerRatio * 100.0f) + "%");
            query.add("主人受伤");
        }

        String task = taskName(maid);
        facts.add("你正在" + task);
        if (!"待命".equals(task)) {
            focus.add("你正在" + task);
            query.add(task);
        }

        ItemStack maidHand = maid.m_21205_();
        String maidItem = itemName(maidHand);
        if (!maidItem.isEmpty()) {
            facts.add("你手里拿着" + maidItem);
            query.add(maidItem);
        }

        float maidRatio = maid.m_21223_() / Math.max(1.0f, maid.m_21233_());
        if (maidRatio < 0.6f) {
            facts.add("你的生命值只有" + Math.round(maidRatio * 100.0f) + "%");
            focus.add("你自己也受了伤");
            query.add("受伤");
        }

        int threats = 0;
        double nearest = Double.MAX_VALUE;
        for (Mob mob : level.m_45976_(Mob.class, player.m_20191_().m_82400_(12.0))) {
            if (PerceptionManager.isThreat(mob, maid)) {
                threats++;
                nearest = Math.min(nearest, mob.m_20270_(player));
            }
        }
        if (threats > 0) {
            String line = "附近有" + threats + "个敌人";
            if (nearest < Double.MAX_VALUE) {
                line += "（最近约" + Math.round(nearest) + "格）";
            }
            facts.add(line);
            focus.add(line);
            query.add("敌人");
        }

        String picked = pickFocus(focus, lastFocus);
        return new WorldSnapshot(String.join("；", facts) + "。", picked,
                AiMemoryModels.clip(String.join(" ", query), 200), !focus.isEmpty());
    }

    private static String pickFocus(ArrayList<String> focus, String last) {
        for (String f : focus) {
            if (!f.equals(last)) {
                return f;
            }
        }
        return focus.isEmpty() ? "" : focus.get(0);
    }

    private static String observePrompt(WorldSnapshot snap, boolean quiet, String memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统·主动开口，不是主人说的话】")
                .append("你不是在回复主人，也没有新的主人消息。请根据此刻真实的环境，自己决定要不要自然地说一句。\n")
                .append("此刻：").append(snap.brief()).append("\n");
        if (quiet) {
            sb.append("现在很安静，主人在你旁边做自己的事。\n");
        } else if (!snap.focus().isBlank()) {
            sb.append("最值得注意：").append(snap.focus()).append("。\n");
        }
        if (!memory.isBlank()) {
            sb.append("你隐约想起一件和此刻有关的事：").append(memory)
                    .append("。只在这件事和眼前场景自然相关时才提，不要硬扯。\n");
        }
        sb.append("要求：只写你要说的话，1-2 句，尽量不超过 40 字；像人在这个场景里随口说的。")
                .append("不要复述上面的环境清单，不要用括号写动作，不要问“主人还在吗”，")
                .append("不要道歉、告别或催主人，不要编造没有发生的事。")
                .append("如果此刻确实不想说话，只输出一个句号。");
        return sb.toString();
    }

    private static String relevantMemory(EntityMaid maid, WorldSnapshot snap) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return "";
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return "";
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        ArrayList<String> parts = new ArrayList<>();
        for (AiMemorySearch.Hit h : AiMemorySearch.searchEnvironment(
                store, snap.memoryQuery(), 2, level.m_46467_())) {
            String c = AiMemoryModels.clip(h.content(), 220);
            if (!c.isBlank() && !parts.contains(c)) {
                parts.add(c);
            }
        }
        return String.join("；", parts);
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return "";
        }
        return stack.m_41786_().getString();
    }

    private static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> id;
        };
    }

    private static String timePhase(long dayTime) {
        long t = dayTime % 24000L;
        if (t < 1000L) {
            return "清晨";
        }
        if (t < 11000L) {
            return "白天";
        }
        if (t < 13000L) {
            return "黄昏";
        }
        if (t < 18000L) {
            return "夜晚";
        }
        if (t < 23000L) {
            return "深夜";
        }
        return "凌晨";
    }

    private static String taskName(EntityMaid maid) {
        if (maid.getTask() == null || maid.getTask().getUid() == null) {
            return "待命";
        }
        String uid = maid.getTask().getUid().toString();
        return switch (uid) {
            case "maid_smart:mine" -> "挖矿";
            case "maid_smart:woodcut" -> "伐木";
            case "maid_smart:build" -> "建造";
            case "maid_smart:cook" -> "烧制";
            case "maid_smart:brew" -> "酿造";
            case "maid_smart:slaughter" -> "宰杀";
            case "maid_smart:flight_combat" -> "空袭";
            case "maid_smart:flight_ranged" -> "远程空袭";
            case "maid_smart:broom" -> "骑扫帚";
            case "maid_smart:index_build" -> "铺路";
            case "touhou_little_maid:farm" -> "种田";
            case "touhou_little_maid:idle" -> "待命";
            default -> "执行任务";
        };
    }

    private static void sendInternal(EntityMaid maid, ServerPlayer player, String prompt) {
        maid.getAiChatManager().chat(ChatInfoUtil.internal(prompt), ChatInfoUtil.fromMaid(maid), player);
    }

    private void playEmotionalActions(EntityMaid maid, ServerPlayer player) {
        EmotionalActionExecutor.walkToOwner(maid, player);
        EmotionalActionExecutor.lookAtOwner(maid, player);
    }

    // ---------- 事件驱动 ----------

    private void fireEvent(EntityMaid maid, ServerPlayer player, String trigger) {
        this.fireEvent(maid, player, trigger, false);
    }

    private void fireEvent(EntityMaid maid, ServerPlayer player, String trigger, boolean urgent) {
        if (!allowed()) {
            return;
        }
        if (maid.m_9236_() != player.m_9236_()) {
            return; // 跨维度坐标接近不算“在身边”
        }
        if (!com.maidsmart.memory.LlmEnableManager.isEnabled(maid)) {
            return; // 单女仆 LLM 关闭：不发请求、不烧配额
        }
        if (!urgent && ReplyFeedbackTracker.repairMode(maid)) {
            return; // 主人刚说过“别说了”：非紧急事件不再开口
        }
        UUID id = maid.m_20148_();
        long tick = maid.m_9236_().m_46467_();
        Ms s = this.states.computeIfAbsent(id, k -> new Ms());
        if (s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return; // 事件消息同样受主动对话节奏限制，不能绕过冷却
        }
        if (PerceptionManager.dangerActive(maid)) {
            return;
        }
        if (this.dailyCount.getOrDefault(id, 0) >= proactiveDaily()) {
            this.notifyDailyLimit(maid, player);
            return;
        }
        if (!ApiQuotaManager.tryAcquire()) {
            return;
        }
        s.lastFireTick = tick;
        this.dailyCount.merge(id, 1, Integer::sum);
        ReplyFeedbackTracker.markProactiveFire(maid, "");
        this.playEmotionalActions(maid, player);
        sendInternal(maid, player, "【系统·事件，不是主人说的话】" + trigger);
    }

    @SubscribeEvent
    public void onFavorabilityLevelUp(MaidFavorabilityLevelChangeEvent event) {
        if (!allowed() || event.getNewLevel() <= event.getOldLevel()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        if (maid.m_20270_(player) > 16.0f) {
            return;
        }
        long tick = maid.m_9236_().m_46467_();
        UUID maidId = maid.m_20148_();
        Ms s = this.states.get(maidId);
        if (s != null && s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        EmotionalActionExecutor.heartParticles(maid);
        this.fireEvent(maid, player, "你刚刚和主人的关系变得更好了（好感度提升了）。"
                + "你心里暖暖的，请主动对主人说一句开心的话。");
    }

    @SubscribeEvent
    public void onMaidKill(LivingDeathEvent event) {
        if (!allowed() || !com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_KILL.get()) {
            return;
        }
        if (event.getSource() == null || !(event.getSource().m_7639_() instanceof EntityMaid maid)) {
            return;
        }
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        if (maid.m_20270_(player) > 16.0f) {
            return;
        }
        long tick = maid.m_9236_().m_46467_();
        UUID maidId = maid.m_20148_();
        Ms s = this.states.get(maidId);
        if (s != null && s.lastFireTick >= 0 && tick - s.lastFireTick < proactiveCooldown()) {
            return;
        }
        this.fireEvent(maid, player, "你刚刚击败了一个敌人，保护了主人。"
                + "请对主人说一句简短的话，可以邀功、可以平淡，也可以撒娇。");
    }

    @SubscribeEvent
    public void onPlayerHurt(LivingHurtEvent event) {
        if (!allowed() || !(event.getEntity() instanceof ServerPlayer player) || !player.m_6084_()) {
            return;
        }
        float ratio = player.m_21223_() / Math.max(1.0f, player.m_21233_());
        if (ratio >= com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_LOW_HP.get() / 100.0f) {
            return;
        }
        long tick = player.m_9236_().m_46467_();
        Long lastPlayerEvent = this.playerHurtAt.get(player.m_20148_());
        if (lastPlayerEvent != null && tick - lastPlayerEvent
                < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
            return; // 同一玩家的事件窗口内只让一只女仆开口，避免一队女仆同 tick 刷屏
        }
        this.playerHurtAt.put(player.m_20148_(), tick);
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0))) {
            if (!maid.m_21824_() || !maid.m_6084_() || maid.m_269323_() != player) {
                continue;
            }
            UUID maidId = maid.m_20148_();
            Long lastEvent = this.lastEventTime.get(maidId);
            if (lastEvent != null && tick - lastEvent
                    < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
                continue;
            }
            this.lastEventTime.put(maidId, tick);
            this.fireEvent(maid, player, "你的主人刚刚受了重伤，生命值很低，看起来很痛苦。"
                    + "请立刻对他说一句简短的话表达担心和关心。", true);
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!allowed() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        long tick = player.m_9236_().m_46467_();
        Long lastPlayerDeath = this.playerDeathAt.get(player.m_20148_());
        if (lastPlayerDeath != null && tick - lastPlayerDeath
                < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
            return; // 同一玩家的死亡事件窗口内只让一只女仆开口
        }
        this.playerDeathAt.put(player.m_20148_(), tick);
        for (EntityMaid maid : player.m_9236_().m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0))) {
            if (!maid.m_21824_() || !maid.m_6084_() || maid.m_269323_() != player) {
                continue;
            }
            UUID maidId = maid.m_20148_();
            Long lastEvent = this.lastEventTime.get(maidId);
            if (lastEvent != null && tick - lastEvent
                    < (long) com.maidsmart.config.MaidSmartConfig.DIALOGUE_PROACTIVE_EVENT_CD.get() * 20) {
                continue;
            }
            this.lastEventTime.put(maidId, tick);
            this.fireEvent(maid, player, "你的主人刚刚在你面前死去了。你非常震惊和难过，"
                    + "请对他说一句简短的话表达你的悲痛和不舍。", true);
        }
    }
}
