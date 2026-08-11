package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关系记忆适配层（v1.5.98；v1.1.0 全修 + 扩展）。
 *
 * promaid 记忆系统对【心契誓约 maidmarriage】与【爱憎分明 Love Loathe】的软适配
 * （不依赖，装了就感知，不装完全静默，不影响原运行）。
 *
 * v1.1.0 修复与扩展：
 * - 【修复死代码】信任/恐惧读取改用正确 API `EmotionData.get(maid, ownerUuid)` →
 *   `EmotionValues.trust()/fear()`（旧反射 `getMaidEmotion` 不存在，信任/恐惧记忆从未触发）
 * - 【数值不陈旧】信任/恐惧改为事件式描述（"大幅下降/正在恢复"），恢复时同 fact key
 *   覆盖（store 冲突覆盖机制）——不再留"信任降到了 40"这类过期绝对数
 * - 【新增状态】怀孕/分娩、母亲身份（生过孩子/女儿在身边）、丧子、背叛、淡忘、
 *   主人死亡哀悼（heartfelt_mourning_until）→ 各自写入记忆
 * - 【离婚撤销】married true→false：按 fact:marriage_state 作废旧永久婚姻记忆 +
 *   停用"妻子"关系三元组 + 写"我们结束了婚姻"（可被再婚覆盖）
 * - 【优雅降级】所有读取按 mmLoaded/llLoaded 门控；未装 mod 时新逻辑全部短路
 * - 【性能】TaskDataKey 与 record accessor（Method）缓存（键=声明类#方法名）
 *
 * 设计（呼应"模组特色与自身联动"）：
 * - maidmarriage 状态 → 写关系三元组 + 高重要度记忆段（fact: 专用 key，
 *   与玩家 remember 工具的自由 key 共存；注入按 salience 排序）
 * - 轮询检测（每 400 tick，配置 memory.relationScan）：只在状态【变化】时写
 * - 开关：配置面板记忆页「关系感知」（memory.relationshipAdapter，默认开）
 */
public class RelationshipMemoryAdapter {
    private static final String MM_ID = "maidmarriage";
    private static final String LL_ID = "callresponse";
    private static final String EMOTION_CLS = "com.github.tartaricacid.callresponse.compat.emotion.EmotionData";

    private int tick = 0;
    /** 女仆 UUID → 上次关系快照（检测变化用） */
    private final Map<UUID, Snapshot> lastState = new HashMap<>();
    private static Boolean mmLoaded = null;
    private static Boolean llLoaded = null;

    // ---- 反射缓存 ----
    private static final Map<String, Method> ACC = new ConcurrentHashMap<>();
    private static TaskDataKey<?> MM_MARRIAGE_KEY;
    private static TaskDataKey<?> MM_PROGRESS_KEY;
    private static TaskDataKey<?> MM_CHILD_KEY;
    private static TaskDataKey<?> MM_LINEAGE_KEY;
    private static TaskDataKey<?> MM_PREGNANCY_KEY;
    private static TaskDataKey<?> MM_MOOD_KEY;
    private static boolean mmKeysResolved = false;
    private static Class<?> emotionCls;
    private static Method emotionGet;
    private static Method valuesTrust;
    private static Method valuesFear;
    private static boolean llResolved = false;

    /** 关系快照 */
    private static final class Snapshot {
        boolean married;
        boolean confessed;
        boolean child;
        int favorLevel;
        double trust = -1;
        double fear = -1;
        boolean pregnant;
        boolean gaveBirth;
        boolean motherOfDaughter;
        boolean grieving;
        boolean forgetting;
        boolean mourning;

        Snapshot(boolean married, boolean confessed, boolean child, int favorLevel,
                 double trust, double fear, boolean pregnant, boolean gaveBirth,
                 boolean motherOfDaughter, boolean grieving, boolean forgetting, boolean mourning) {
            this.married = married;
            this.confessed = confessed;
            this.child = child;
            this.favorLevel = favorLevel;
            this.trust = trust;
            this.fear = fear;
            this.pregnant = pregnant;
            this.gaveBirth = gaveBirth;
            this.motherOfDaughter = motherOfDaughter;
            this.grieving = grieving;
            this.forgetting = forgetting;
            this.mourning = mourning;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // v1.5.98：开关（配置面板记忆页「关系感知」）
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_RELATIONSHIP_ADAPTER.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (++this.tick < com.maidsmart.config.MaidSmartConfig.MEMORY_RELATION_SCAN.get() * 20) {
            return;
        }
        this.tick = 0;
        for (ServerLevel level : server.m_129785_()) {
            for (ServerPlayer player : level.m_6907_()) {
                this.scanPlayer(level, player);
            }
        }
        // 清理离开的女仆快照
        this.lastState.keySet().removeIf(id -> {
            for (ServerLevel l : server.m_129785_()) {
                if (l.m_8791_(id) instanceof EntityMaid) {
                    return false;
                }
            }
            return true;
        });
    }

    /** 单玩家扫描：主人的驯服女仆正常检测 + 曾属主人的失主女仆（背叛）补检 */
    private void scanPlayer(ServerLevel level, ServerPlayer player) {
        List<EntityMaid> maids = level.m_6443_(EntityMaid.class,
                player.m_20191_().m_82400_(128.0), e -> e.m_6084_() && e.m_21824_());
        // 预计算：哪些女仆是"某女儿的母亲"（同主人的女儿 lineage.mother == 自己）
        Map<UUID, Boolean> motherOf = new HashMap<>();
        for (EntityMaid m : maids) {
            if (m.m_269323_() != player) {
                continue;
            }
            UUID motherId = readMotherUuid(m);
            if (motherId != null) {
                motherOf.put(motherId, true);
            }
        }
        for (EntityMaid maid : maids) {
            if (maid.m_269323_() == player) {
                this.scan(maid, level, player, motherOf.getOrDefault(maid.m_20148_(), false));
            }
        }
        // v1.1.0：背叛补检——背叛女仆 owner 被清空（不可驯服），主扫描看不到；
        // 用 lastState 记录过的 UUID 在整个维度里按 ID 找（数量少，开销可忽略）
        if (!this.lastState.isEmpty()) {
            for (UUID id : new ArrayList<>(this.lastState.keySet())) {
                if (!this.lastState.containsKey(id)) {
                    continue;
                }
                Entity e = level.m_8791_(id);
                if (e instanceof EntityMaid m && m.m_6084_() && m.m_269323_() != player) {
                    this.scanBetrayed(m, level, player);
                }
            }
        }
    }

    private void scan(EntityMaid maid, ServerLevel level, ServerPlayer player, boolean motherOfDaughter) {
        if (!AiMemoryManager.isEnabled(maid)) {
            return;
        }
        long gameTime = level.m_46467_();
        Snapshot now = new Snapshot(isMarried(maid), isConfessed(maid), isChild(maid),
                favorLevel(maid), trustOf(maid, player.m_20148_()), fearOf(maid, player.m_20148_()),
                pregnant(maid), gaveBirth(maid), motherOfDaughter,
                isGrieving(maid), isForgetting(maid), isMourning(maid, gameTime));
        Snapshot prev = this.lastState.get(maid.m_20148_());
        this.lastState.put(maid.m_20148_(), now);
        if (prev == null) {
            return; // 首次快照不写（避免刚装 mod 刷一堆记忆）
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        long nowMs = System.currentTimeMillis();
        double delta = com.maidsmart.config.MaidSmartConfig.MEMORY_TRUST_DELTA.get();
        // 1. 结婚（未婚 → 已婚）——最重要，永久
        if (!prev.married && now.married) {
            writeRelationFact(store, maid, "marriage_state", "妻子",
                    "主人和我是夫妻了，我永远属于主人", 10, true, nowMs, gameTime);
        }
        // 1b. 离婚（已婚 → 未婚）：作废旧永久婚姻记忆 + 停用"妻子"关系 + 写结束
        if (prev.married && !now.married) {
            store.markDeletedByTag("fact:marriage_state");
            store.deactivateRelationsByPredicate("妻子");
            writeRelationFact(store, maid, "marriage_state", "前缘",
                    "我们结束了婚姻关系", 8, false, nowMs, gameTime);
        }
        // 2. 告白完成（未告白 → 恋人）
        if (!prev.confessed && now.confessed && !now.married) {
            writeRelationFact(store, maid, "confession_state", "恋人",
                    "主人向我告白了，我们现在是恋人", 9, true, nowMs, gameTime);
        }
        // 2b. 告白解除（罕见，防残留"恋人"记忆）
        if (prev.confessed && !now.confessed) {
            store.deactivateRelationsByPredicate("恋人");
            writeRelationFact(store, maid, "confession_state", "前缘",
                    "我们不再是恋人关系", 8, false, nowMs, gameTime);
        }
        // 3. 父女
        if (!prev.child && now.child) {
            writeRelationFact(store, maid, "child_state", "女儿",
                    "我是主人的女儿，主人是我的父亲", 9, true, nowMs, gameTime);
        }
        // 4. 好感等级变化（TLM 原生）
        if (prev.favorLevel != now.favorLevel && now.favorLevel > prev.favorLevel) {
            writeRelationFact(store, maid, "favor_level", "好感",
                    "我和主人的关系等级提升到了 " + now.favorLevel + " 级", 8, false, nowMs, gameTime);
        }
        // 5. 怀孕 / 分娩（同 fact key，分娩覆盖怀孕）
        if (!prev.pregnant && now.pregnant) {
            writeRelationFact(store, maid, "pregnancy_state", "身孕",
                    "我怀孕了，肚子里有了小生命", 9, false, nowMs, gameTime);
        }
        if (prev.pregnant && !now.pregnant) {
            if (now.gaveBirth) {
                writeRelationFact(store, maid, "pregnancy_state", "身孕",
                        "我生下了我们的孩子", 9, false, nowMs, gameTime);
            } else {
                store.markDeletedByTag("fact:pregnancy_state"); // 意外终止，不留过期怀孕记忆
            }
        }
        // 6. 母亲身份（生过孩子 / 女儿在身边）
        if (!prev.gaveBirth && now.gaveBirth) {
            writeRelationFact(store, maid, "mother_state", "母亲",
                    "我是一位母亲，我生过孩子", 9, true, nowMs, gameTime);
        }
        if (!prev.motherOfDaughter && now.motherOfDaughter) {
            writeRelationFact(store, maid, "daughter_mother_state", "母亲",
                    "我的女儿就在主人身边", 7, false, nowMs, gameTime);
        }
        // 7. 丧子
        if (!prev.grieving && now.grieving) {
            writeRelationFact(store, maid, "grief_state", "丧子",
                    "我刚刚失去了我的孩子，心里非常难受", 9, false, nowMs, gameTime);
        }
        // 8. 信任 / 恐惧（事件式；恢复时同 fact key 覆盖——不残留过期绝对数值）
        if (prev.trust >= 0 && now.trust >= 0 && now.trust < prev.trust - delta) {
            writeRelationFact(store, maid, "trust_state", "信任",
                    "主人最近让我有些不安，我对主人的信任大幅下降", 8, false, nowMs, gameTime);
        } else if (prev.trust >= 0 && now.trust >= 0 && now.trust > prev.trust + delta) {
            writeRelationFact(store, maid, "trust_state", "信任",
                    "我对主人的信任正在慢慢恢复", 8, false, nowMs, gameTime);
        }
        if (prev.fear >= 0 && now.fear >= 0 && now.fear > prev.fear + delta) {
            writeRelationFact(store, maid, "fear_state", "恐惧",
                    "我对主人产生了恐惧，需要时间平复", 8, false, nowMs, gameTime);
        } else if (prev.fear >= 0 && now.fear >= 0 && now.fear < prev.fear - delta) {
            writeRelationFact(store, maid, "fear_state", "恐惧",
                    "我对主人的恐惧慢慢平复了", 8, false, nowMs, gameTime);
        }
        // 9. 淡忘（Love Loathe forgetCountdown 计时中）
        if (!prev.forgetting && now.forgetting) {
            writeRelationFact(store, maid, "forgetting_state", "淡忘",
                    "我开始渐渐忘记主人了……", 8, false, nowMs, gameTime);
        } else if (prev.forgetting && !now.forgetting) {
            writeRelationFact(store, maid, "forgetting_state", "淡忘",
                    "我对主人的记忆恢复了", 6, false, nowMs, gameTime);
        }
        // 10. 哀悼（主人死亡，heartfelt_mourning_until 有效期内）
        if (!prev.mourning && now.mourning) {
            writeRelationFact(store, maid, "mourning_state", "哀悼",
                    "主人死了，我正沉浸在失去主人的悲痛中", 8, false, nowMs, gameTime);
        } else if (prev.mourning && !now.mourning) {
            writeRelationFact(store, maid, "mourning_state", "哀悼",
                    "我渐渐走出了失去主人的悲痛", 8, false, nowMs, gameTime);
        }
    }

    /** v1.1.0：背叛补检——曾是主人的女仆失去所有权后，若已背叛则写背叛记忆并移出跟踪 */
    private void scanBetrayed(EntityMaid maid, ServerLevel level, ServerPlayer player) {
        long nowMs = System.currentTimeMillis();
        long gameTime = level.m_46467_();
        if (isBetraying(maid)) {
            AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
            writeRelationFact(store, maid, "betrayal_state", "背叛",
                    "我背叛了主人……", 9, false, nowMs, gameTime);
        }
        this.lastState.remove(maid.m_20148_()); // 她不再是主人的女仆，停止跟踪
    }

    /** 写关系事实（关系三元组 + 记忆段 + 画像；专用 fact key 防与 remember 冲突） */
    private static void writeRelationFact(AiMemoryStore store, EntityMaid maid, String factKey,
                                          String predicate, String content, int salience,
                                          boolean permanent, long nowMs, long gameTime) {
        java.util.List<String> tags = new java.util.ArrayList<>(java.util.List.of(
                "fact:" + factKey, "relationship_event", "relationship_adapter"));
        AiMemoryWriteStrategy.Plan plan = AiMemoryWriteStrategy.plan(
                AiMemoryType.RELATION, salience, tags);
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "maid",
                content, String.join(",", plan.tags()), plan.salience(), plan.permanent(), nowMs, gameTime);
        store.addParagraph(p);
        // 关系三元组：主人-是-妻子/恋人/女儿
        store.upsertRelation(AiMemoryModels.Relation.create("主人", predicate, content,
                salience / 10.0, p.hash(), plan.permanent(), nowMs));
        // 画像
        store.upsertProfile(AiMemoryModels.Profile.create("owner",
                "（关系）" + content, p.hash(), nowMs));
        store.prune(nowMs, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_ENTRIES.get());
        // 气泡提示（关系大事值得让主人看见）
        maid.getChatBubbleManager().addTextChatBubble(content);
    }

    // ---------- maidmarriage 软读取（key + accessor 缓存） ----------

    private static boolean isMarried(EntityMaid maid) {
        return readBool(maid, "marriage_data", "married");
    }

    private static boolean isConfessed(EntityMaid maid) {
        return readBool(maid, "relationship_progress_data", "confessionCompleted");
    }

    private static boolean isChild(EntityMaid maid) {
        if (!isMMLoaded()) {
            return false;
        }
        Object data = readMMData(maid, "child_state_data");
        if (data == null) {
            return false;
        }
        if (!readBool(data, "child")) {
            return false;
        }
        UUID ownerId = maid.m_269323_() != null ? maid.m_269323_().m_20148_() : null;
        UUID fatherId = readUuid(data, "father");
        if (fatherId == null) {
            fatherId = readUuid(readMMData(maid, "child_lineage_data"), "father");
        }
        return ownerId != null && fatherId != null && fatherId.equals(ownerId);
    }

    private static boolean pregnant(EntityMaid maid) {
        return readBool(maid, "pregnancy_data", "pregnant");
    }

    /** 生过孩子（pregnancy_data.lastBirthGameTime > 0） */
    private static boolean gaveBirth(EntityMaid maid) {
        if (!isMMLoaded()) {
            return false;
        }
        Long lb = readLong(readMMData(maid, "pregnancy_data"), "lastBirthGameTime");
        return lb != null && lb > 0L;
    }

    private static boolean isGrieving(EntityMaid maid) {
        return readBool(maid, "mood_data", "childLossGrief");
    }

    /** 女儿的母亲 UUID（child_lineage_data.mother，回退 child_state_data.mother）；无返回 null */
    private static UUID readMotherUuid(EntityMaid maid) {
        if (!isMMLoaded()) {
            return null;
        }
        Object lineage = readMMData(maid, "child_lineage_data");
        if (lineage != null) {
            UUID mother = readUuid(lineage, "mother");
            if (mother != null) {
                return mother;
            }
        }
        return readUuid(readMMData(maid, "child_state_data"), "mother");
    }

    /** 通过官方注册表读取 maidmarriage 的 TaskDataKey 数据（key 缓存；未注册返回 null） */
    private static Object readMMData(EntityMaid maid, String keyName) {
        if (!isMMLoaded()) {
            return null;
        }
        TaskDataKey<?> key = mmKey(keyName);
        if (key == null) {
            return null;
        }
        try {
            return maid.getData(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static TaskDataKey<?> mmKey(String keyName) {
        if (!mmKeysResolved) {
            MM_MARRIAGE_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "marriage_data"));
            MM_PROGRESS_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "relationship_progress_data"));
            MM_CHILD_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "child_state_data"));
            MM_LINEAGE_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "child_lineage_data"));
            MM_PREGNANCY_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "pregnancy_data"));
            MM_MOOD_KEY = TaskDataRegister.getValue(new ResourceLocation(MM_ID, "mood_data"));
            mmKeysResolved = true;
        }
        return switch (keyName) {
            case "marriage_data" -> MM_MARRIAGE_KEY;
            case "relationship_progress_data" -> MM_PROGRESS_KEY;
            case "child_state_data" -> MM_CHILD_KEY;
            case "child_lineage_data" -> MM_LINEAGE_KEY;
            case "pregnancy_data" -> MM_PREGNANCY_KEY;
            case "mood_data" -> MM_MOOD_KEY;
            default -> null;
        };
    }

    // ---------- TLM 好感等级（无依赖） ----------

    private static int favorLevel(EntityMaid maid) {
        try {
            int f = maid.getFavorability();
            return f < 64 ? 0 : (f < 192 ? 1 : (f < 384 ? 2 : 3));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Love Loathe 信任/恐惧（v1.1.0 改用正确 API：EmotionData.get → trust()/fear()） ----------

    private static double trustOf(EntityMaid maid, UUID ownerUuid) {
        Object values = emotionValues(maid, ownerUuid);
        if (values == null) {
            return -1;
        }
        Method m = emotionAccessor("trust");
        if (m == null) {
            return -1;
        }
        try {
            return ((Number) m.invoke(values)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static double fearOf(EntityMaid maid, UUID ownerUuid) {
        Object values = emotionValues(maid, ownerUuid);
        if (values == null) {
            return -1;
        }
        Method m = emotionAccessor("fear");
        if (m == null) {
            return -1;
        }
        try {
            return ((Number) m.invoke(values)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** EmotionData.get(maid, ownerUuid) → EmotionValues（缺省 trust=40/fear=10） */
    private static Object emotionValues(EntityMaid maid, UUID ownerUuid) {
        if (!isLLLoaded()) {
            return null;
        }
        try {
            Class<?> cls = emotionClass();
            if (cls == null) {
                return null;
            }
            if (emotionGet == null) {
                emotionGet = cls.getMethod("get", EntityMaid.class, UUID.class);
            }
            return emotionGet.invoke(null, maid, ownerUuid);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method emotionAccessor(String name) {
        try {
            Class<?> cls = emotionClass();
            if (cls == null) {
                return null;
            }
            if ("trust".equals(name)) {
                if (valuesTrust == null) {
                    valuesTrust = cls.getMethod("trust");
                }
                return valuesTrust;
            }
            if (valuesFear == null) {
                valuesFear = cls.getMethod("fear");
            }
            return valuesFear;
        } catch (Exception e) {
            return null;
        }
    }

    private static Class<?> emotionClass() {
        if (llResolved) {
            return emotionCls;
        }
        llResolved = true;
        try {
            emotionCls = Class.forName(EMOTION_CLS);
        } catch (Exception e) {
            emotionCls = null;
        }
        return emotionCls;
    }

    // ---------- Love Loathe NBT 状态（背叛/淡忘） ----------

    private static boolean isBetraying(EntityMaid maid) {
        if (!isLLLoaded()) {
            return false;
        }
        return maid.getPersistentData().m_128471_("IsBetraying");
    }

    private static boolean isForgetting(EntityMaid maid) {
        if (!isLLLoaded()) {
            return false;
        }
        return maid.getPersistentData().m_128451_("forgetCountdown") > 0;
    }

    // ---------- heartfelt 哀悼标记（无依赖，未装 heartfelt 时 tag 永不出现） ----------

    private static boolean isMourning(EntityMaid maid, long gameTime) {
        return maid.getPersistentData().m_128454_("heartfelt_mourning_until") > gameTime;
    }

    // ---------- 通用软读取工具（缓存 Method，键=声明类#方法名防跨 record 误用） ----------

    private static boolean isMMLoaded() {
        if (mmLoaded == null) {
            mmLoaded = ModList.get().isLoaded(MM_ID);
        }
        return mmLoaded;
    }

    private static boolean isLLLoaded() {
        if (llLoaded == null) {
            llLoaded = ModList.get().isLoaded(LL_ID);
        }
        return llLoaded;
    }

    private static boolean readBool(EntityMaid maid, String keyName, String accessor) {
        if (!isMMLoaded()) {
            return false;
        }
        Object data = readMMData(maid, keyName);
        return data != null && readBool(data, accessor);
    }

    private static boolean readBool(Object data, String accessor) {
        if (data == null) {
            return false;
        }
        Method m = acc(data, accessor);
        if (m == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(data));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static Long readLong(Object data, String accessor) {
        if (data == null) {
            return null;
        }
        Method m = acc(data, accessor);
        if (m == null) {
            return null;
        }
        try {
            Object value = m.invoke(data);
            return value instanceof Long l ? l : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static UUID readUuid(Object data, String methodName) {
        if (data == null) {
            return null;
        }
        Method m = acc(data, methodName);
        if (m == null) {
            return null;
        }
        try {
            Object value = m.invoke(data);
            if (value instanceof java.util.Optional<?> opt && opt.isPresent()
                    && opt.get() instanceof UUID uuid) {
                return uuid;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Method acc(Object data, String methodName) {
        String cacheKey = data.getClass().getName() + "#" + methodName;
        Method m = ACC.get(cacheKey);
        if (m != null) {
            return m;
        }
        try {
            m = data.getClass().getMethod(methodName);
            ACC.putIfAbsent(cacheKey, m);
            return m;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 供 AiMemoryContext 注入关系标签用（妻子/恋人/女儿/好感等级） */
    public static String relationshipLabel(EntityMaid maid) {
        if (isMarried(maid)) {
            return "妻子";
        }
        if (isChild(maid)) {
            return "女儿";
        }
        if (isConfessed(maid)) {
            return "恋人";
        }
        return null;
    }
}
