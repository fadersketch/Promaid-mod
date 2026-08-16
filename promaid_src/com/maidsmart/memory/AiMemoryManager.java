package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidFavorabilityLevelChangeEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 记忆管理器（v1.5.86）：事件源 + tick 调度 + 每日巩固。
 *
 * 事件源（取代旧 MaidMemoryManager 的 4 键 Map——旧数据不迁移，重新积累）：
 * - 喂食 → EVENT 段落（salience 7）
 * - 好感升级 → RELATION 段落（salience 9，永久）
 * - 玩家死亡 → EVENT 段落（salience 10）
 *
 * tick 调度（每 400 tick ≈ 20 秒）：
 * - 对玩家周围的女仆触发 AiMemoryExtractor.maybeExtract（后台异步提取）
 * - 游戏日切换时生成"今日回顾"（纯规则零 LLM，对齐 DailyMemoryConsolidator）
 *
 * per-maid 开关：Forge persistentData（"maid_smart_memory"，Byte；无 = 继承全局配置）。
 * v1.5.101：原实现存 TLM TaskData（maid_smart:ai_memory_toggle，Codec.BOOL 编成 ByteTag）
 * ——TLM 的 TaskData 同步（TaskDataRegister.writeSyncData）强制把编码结果强转 CompoundTag
 * → 一点开关就 ClassCastException 崩溃（08:19 实测）。改用 persistentData 彻底绕开 TLM
 * 同步机制（开关只在服务端读写，无需同步客户端——手册记忆列表/详情都由服务端 isEnabled 取值）。
 */
public class AiMemoryManager {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(AiMemoryManager.class);
    /** per-maid 开关（persistentData key，Byte；无 = 跟随全局配置） */
    private static final String PERSIST_TAG = "maid_smart_memory";

    /**
     * v1.5.242：服务端开关磁盘备份（promaid_memory/toggles.json）——persistentData
     * 只随实体 NBT 保存，女仆实体被移除/重载/跨维度时可能丢失（丢失后 isEnabled
     * 回落全局配置 = "开"，"关了的开关又显示回开"的根因）。setEnabled 同时写
     * 磁盘，isEnabled 服务端在 persistentData 无标记时回落此备份。仅服务端写。
     */
    private static final java.util.Map<String, Boolean> DISK_TOGGLES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static java.nio.file.Path togglesFile = null;

    /**
     * v1.5.226：客户端缓存服务端广播的开关状态。persistentData 只在服务端读写、
     * 不同步客户端——重开女仆配置界面时客户端 isEnabled 会读到过期旧值（关了的
     * 开关又显示回"开"）。服务端每次 setEnabled 后广播 MemoryStateSyncPacket，
     * 客户端写入此缓存；服务端不写此缓存（服务端始终读 persistentData 为准）。
     */
    private static final java.util.Map<String, Boolean> CLIENT_STATE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** v1.5.251：客户端缓存的灵魂 id（SyncPacket 附带；配置界面本地读记忆时
     *  按灵魂目录路由——全局共享存储，跨存档双向同步的显示侧） */
    private static final java.util.Map<String, String> CLIENT_SOUL_IDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端接收 S2C MemoryStateSyncPacket 后写入（仅客户端调用） */
    public static void pushClientState(String maidUuid, boolean enabled) {
        CLIENT_STATE.put(maidUuid, enabled);
    }

    /** v1.5.251：客户端缓存灵魂 id（仅客户端调用） */
    public static void pushClientSoulId(String maidUuid, String soulId) {
        if (soulId != null) {
            CLIENT_SOUL_IDS.put(maidUuid, soulId);
        }
    }

    /** v1.5.251：客户端查灵魂 id（无返回 null） */
    public static String clientSoulId(String maidUuid) {
        return CLIENT_SOUL_IDS.get(maidUuid);
    }

    /** v1.5.102：调度间隔从配置面板读取（memory.scanInterval，秒→tick） */
    private int tick = 0;
    /** v1.5.191：维护周期累计 tick（每 maintenanceMin 分钟跑一次 runMaintenance） */
    private long maintainTicks = 0;

    /** 女仆的 AI 记忆是否开启（per-maid 覆盖全局配置） */
    public static boolean isEnabled(EntityMaid maid) {
        // v1.5.226：客户端优先用服务端广播的缓存值（persistentData 不同步客户端，
        // 直接读会拿到过期旧值导致开关显示回"开"）
        if (maid.m_9236_() != null && maid.m_9236_().m_5776_()) { // isClientSide
            Boolean cached = CLIENT_STATE.get(maid.m_20148_().toString());
            if (cached != null) {
                return cached;
            }
        }
        // v1.5.251b：服务端【磁盘优先】——Toggle 在实体未加载时也可写磁盘生效
        // （防御"远处/未加载女仆关不掉"：手册列表即使只能列出已加载实体，也防
        //  瞬间卸载/跨维度场景）；磁盘无记录再回落 persistentData
        if (maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sLevel) {
            togglesFile(sLevel); // 确保备份已加载（惰性，仅首次 IO）
            Boolean disk = DISK_TOGGLES.get(maid.m_20148_().toString());
            if (disk != null) {
                return disk;
            }
        }
        net.minecraft.nbt.CompoundTag pd = maid.getPersistentData();
        if (pd.m_128425_(PERSIST_TAG, 1)) { // contains(PERSIST_TAG, TAG_BYTE)
            // v1.5.250【重大修复】：m_128441_(String) 是 containsKey 不是 getBoolean
            // ——旧代码这里返回"键是否存在"（putBoolean 后恒 true）→ 服务端
            // isEnabled 恒 true → Query 回包永远 Sync(true) → 客户端被覆盖回"开"
            // （"关了又自己打开"的根因）。getBoolean 等价实现 = getByte != 0。
            return pd.m_128435_(PERSIST_TAG) != 0;
        }
        return com.maidsmart.config.MaidSmartConfig.MEMORY_ENABLE.get();
    }

    /** 设置 per-maid 开关（手册记忆页/maidmarriage 调试面板调用） */
    public static void setEnabled(EntityMaid maid, boolean enabled) {
        maid.getPersistentData().m_128379_(PERSIST_TAG, enabled); // putBoolean
        // v1.5.242：磁盘备份（防 persistentData 丢失后开关回弹）
        if (maid.m_9236_() instanceof ServerLevel level) {
            DISK_TOGGLES.put(maid.m_20148_().toString(), enabled);
            saveDiskToggles(level);
        }
        LOGGER.info("memory toggle: maid={} enabled={}",
                maid.m_5446_() != null ? maid.m_5446_().getString() : "?", enabled);
    }

    /** v1.5.251b：仅写磁盘的开关（实体未加载/找不到时用）——isEnabled 磁盘优先，
     *  实体下次加载后立即读到新值 */
    public static void setEnabledDiskOnly(String maidUuid, boolean enabled, ServerLevel level) {
        DISK_TOGGLES.put(maidUuid, enabled);
        saveDiskToggles(level);
        LOGGER.info("memory toggle(disk-only): maid={} enabled={}", maidUuid, enabled);
    }

    // ---------- v1.5.242：开关磁盘备份 ----------

    /** 加载/定位 toggles.json（惰性；首次调用才读盘） */
    private static java.nio.file.Path togglesFile(ServerLevel level) {
        if (togglesFile == null) {
            togglesFile = AiMemoryExtractor.memoryRoot(level.m_7654_()).resolve("toggles.json");
            try {
                if (java.nio.file.Files.exists(togglesFile)) {
                    String json = java.nio.file.Files.readString(togglesFile,
                            java.nio.charset.StandardCharsets.UTF_8);
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                            java.util.HashMap<String, Boolean>>() {
                    }.getType();
                    java.util.HashMap<String, Boolean> loaded = AiMemoryModels.GSON.fromJson(json, type);
                    if (loaded != null) {
                        DISK_TOGGLES.putAll(loaded);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return togglesFile;
    }

    private static void saveDiskToggles(ServerLevel level) {
        try {
            java.nio.file.Files.writeString(togglesFile(level),
                    AiMemoryModels.GSON.toJson(DISK_TOGGLES),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---------- 事件源 ----------

    @SubscribeEvent
    public void onFeed(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        if (!maid.m_21824_() || event.getEntity() != maid.m_269323_()) {
            return;
        }
        ItemStack held = event.getEntity().m_21205_();
        if (held.m_41619_() || held.getFoodProperties(maid) == null) {
            return;
        }
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        // v1.5.226：记忆关闭时事件源不再写入（否则关了开关记忆还在涨）
        if (!isEnabled(maid)) {
            return;
        }
        String itemName = ForgeRegistries.ITEMS.getKey(held.m_41720_()).toString().replace("minecraft:", "");
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        writeParagraph(store, AiMemoryType.EVENT, "主人喂了我" + itemName,
                "food,user_profile", 7, System.currentTimeMillis(), level.m_46467_());
    }

    @SubscribeEvent
    public void onFavorabilityLevelUp(MaidFavorabilityLevelChangeEvent event) {
        if (event.getNewLevel() <= event.getOldLevel()) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (!(maid.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        // v1.5.226：记忆关闭时事件源不再写入
        if (!isEnabled(maid)) {
            return;
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        writeParagraph(store, AiMemoryType.RELATION,
                "我们的关系等级达到了" + event.getNewLevel() + "，越来越亲密",
                "relationship_event", 9, System.currentTimeMillis(), level.m_46467_());
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        level.m_45976_(EntityMaid.class, player.m_20191_().m_82400_(16.0)).forEach(maid -> {
            if (!maid.m_21824_() || maid.m_269323_() != player) {
                return;
            }
            // v1.5.226：记忆关闭时事件源不再写入
            if (!isEnabled(maid)) {
                return;
            }
            AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
            writeParagraph(store, AiMemoryType.EVENT, "主人曾经在我面前死去，我很害怕",
                    "emotion", 10, System.currentTimeMillis(), level.m_46467_());
        });
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
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "event", content,
                String.join(",", plan.tags()), plan.salience(), plan.permanent(), now, gameTime);
        store.addParagraph(p);
    }

    // ---------- tick 调度 ----------

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (++this.tick < com.maidsmart.config.MaidSmartConfig.MEMORY_SCAN_INTERVAL.get() * 20) {
            return;
        }
        this.tick = 0;
        // v1.5.190：防抖写盘——先批量落盘脏记忆（再扫描提取，提取回调里的写会再次标脏）
        AiMemoryStore.flushAll(System.currentTimeMillis());
        // v1.5.191：维护周期——旧版 prune 只挂在写入路径上，老记忆永远不衰减；
        // 现在每 maintenanceMin 分钟对全部已加载存储跑一次 runMaintenance
        // （固化/年龄衰减/访问半衰/关系置信度衰减/error_mark 传播）
        this.maintainTicks += (long) com.maidsmart.config.MaidSmartConfig.MEMORY_SCAN_INTERVAL.get() * 20;
        if (this.maintainTicks >= (long) com.maidsmart.config.MaidSmartConfig.MEMORY_MAINTENANCE_MIN.get() * 60 * 20) {
            this.maintainTicks = 0;
            AiMemoryStore.maintainAll(System.currentTimeMillis());
        }
        for (ServerLevel level : server.m_129785_()) {
            long levelGameTime = level.m_46467_();
            int day = (int) (levelGameTime / 24000);
            // 每日巩固 + 提取调度：只对玩家周围的女仆（聊天有距离限制，远离主人不会积累新对话）
            for (ServerPlayer player : level.m_6907_()) {
                level.m_45976_(EntityMaid.class, player.m_20191_().m_82400_(128.0)).forEach(maid -> {
                    if (!maid.m_21824_() || !isEnabled(maid)) {
                        return;
                    }
                    dailyConsolidate(maid, level, day, levelGameTime);
                    AiMemoryExtractor.maybeExtract(maid, server);
                    // 记忆归档——仅重试失败的索引边界（O(pending) 轻量）。
                    // v1.5.379：索引生成收敛到真实睡眠收尾（onSleepFinished），
                    // 熬夜过夜（时间自然跨日但没人睡觉）不再触发归档
                    AiMemoryArchiver.tick(maid, level);
                });
            }
        }
    }

    /**
     * 睡一觉自动处理（移植自 Sphantosis 的 start_role_sleep → wrap-up →
     * archiver.tick(force_day_index=True) 链路）。
     *
     * v1.5.379：改用 SleepFinishedTimeEvent——只有全员真实睡过夜、时间被
     * 跳到清晨才触发（服务端事件）；PlayerWakeUpEvent 会在白天躺床即起、
     * 入睡失败等情况下误触发，且周期跨日兜底会让"熬夜"也归档，均已排除。
     * 对周围女仆：生成刚结束这一天的「日」级日记 + 3日/周/月按边界 + 短期→长期提升。
     */
    @SubscribeEvent
    public void onSleepFinished(net.minecraftforge.event.level.SleepFinishedTimeEvent event) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()
                || !com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ON_SLEEP.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (ServerPlayer player : level.m_6907_()) {
            level.m_45976_(EntityMaid.class, player.m_20191_().m_82400_(128.0)).forEach(maid -> {
                if (!maid.m_21824_() || !isEnabled(maid)) {
                    return;
                }
                AiMemoryArchiver.sleepWrapUp(maid, level);
            });
        }
    }

    // ---------- 每日巩固（纯规则零 LLM，对齐 DailyMemoryConsolidator） ----------

    /**
     * 游戏日切换时生成"今日回顾"：当天经历条数 + 类型统计 + top2 高重要度内容 + 关注点。
     * 写入 SUMMARY 段落（tags daily），注入时随投影展示。
     */
    private static void dailyConsolidate(EntityMaid maid, ServerLevel level, int day, long gameTime) {
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        AiMemoryModels.Meta meta = store.meta();
        if (meta.lastDailyDay() == day) {
            return;
        }
        long dayStart = (long) day * 24000;
        List<AiMemoryModels.Paragraph> today = new ArrayList<>();
        java.util.Map<String, Integer> typeCount = new java.util.HashMap<>();
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            if (p.eventTimeStart() < dayStart || p.sourceType().equals("summary")) {
                continue;
            }
            today.add(p);
            typeCount.merge(p.sourceType(), 1, Integer::sum);
        }
        store.setMeta(new AiMemoryModels.Meta(meta.lastExtractedTime(), day));
        if (today.isEmpty()) {
            return; // 今天没有新记忆，不生成空回顾
        }
        today.sort(java.util.Comparator.comparingInt(AiMemoryModels.Paragraph::salience).reversed());
        StringBuilder sb = new StringBuilder("今天发生了 ").append(today.size()).append(" 件事");
        if (!typeCount.isEmpty()) {
            sb.append("，主要类型：");
            java.util.StringJoiner j = new java.util.StringJoiner("、");
            typeCount.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> j.add(e.getKey() + "×" + e.getValue()));
            sb.append(j);
        }
        sb.append("。");
        int top = Math.min(2, today.size());
        for (int i = 0; i < top; i++) {
            sb.append("难忘的事：").append(AiMemoryModels.clip(today.get(i).content(), 60)).append("。");
        }
        // v1.1.0：每日关心点（规则推导"下次该怎么对主人"，随每日回顾注入；
        // 主动会话 TOPIC_PUSH 读 daily 标签段落会自动复用——记忆↔主动对话联动）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_CARE_POINTS.get()) {
            java.util.List<String> cares = CarePointGenerator.generate(store,
                    com.maidsmart.affect.AffectManager.load(maid));
            if (!cares.isEmpty()) {
                sb.append("关心点：").append(String.join("；", cares)).append("。");
            }
        }
        writeParagraph(store, AiMemoryType.SUMMARY, sb.toString(), "daily,summary",
                6, System.currentTimeMillis(), gameTime);
    }
}
