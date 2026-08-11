package com.maidsmart.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Promaid 全模组配置（v1.5.88，COMMON——客户端/服务端都可读）。
 * 6 个 section：build（建造）/ mine（挖矿）/ memory（AI 记忆）/ dialogue（对话提示）/
 * combat（战斗自保）/ misc（杂项）。
 * 所有项带 .translation("config.promaid.*")，配置面板（PromaidConfigScreen）按 key 显示中文。
 * 面板保存时 SPEC.save() 写 config/promaid-common.toml；运行时 .set() 热更新（内存立即生效）。
 */
public final class MaidSmartConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ================= 建造 =================
    public static final ForgeConfigSpec.ConfigValue<String> BUILD_SPEED_TIER;
    public static final ForgeConfigSpec.BooleanValue BUILD_TURBO;
    public static final ForgeConfigSpec.IntValue BUILD_GLOBAL_QUOTA;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_FORCE_CHUNKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_RANGE;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_HEIGHT;
    public static final ForgeConfigSpec.IntValue BUILD_DESIGN_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_STRUCTURE_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_MAIDS;
    public static final ForgeConfigSpec.BooleanValue BUILD_ORIGIN_PLAYER;
    /** v1.5.102：以下把模组其余硬编码数值全部纳入面板（用户要求"所有数值都可调"） */
    public static final ForgeConfigSpec.IntValue BUILD_REGION_TELEPORT_CD;
    public static final ForgeConfigSpec.IntValue BUILD_RESTORE_CACHE_TTL;
    public static final ForgeConfigSpec.IntValue BUILD_STALL_INTERVAL;
    public static final ForgeConfigSpec.IntValue BUILD_LOOKAHEAD;
    public static final ForgeConfigSpec.IntValue BUILD_DEFERRED_SCAN_CAP;
    public static final ForgeConfigSpec.IntValue BUILD_STRUCTURE_MAX_VOLUME;

    // ================= 挖矿 =================
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MINE_ORE_VALUES;
    /** v1.5.101b：额外可挖穿方块（障碍物名单，path 名如 oak_log；面板挖矿-障碍物管理） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MINE_BREAKABLES;
    public static final ForgeConfigSpec.IntValue MINE_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue MINE_DOWN_RANGE;
    public static final ForgeConfigSpec.IntValue MINE_UP_RANGE;
    public static final ForgeConfigSpec.IntValue MINE_BREAK_BUDGET;
    public static final ForgeConfigSpec.DoubleValue MINE_VALUE_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue MINE_DEPTH_PENALTY;
    public static final ForgeConfigSpec.DoubleValue MINE_SPEED_FACTOR;
    public static final ForgeConfigSpec.DoubleValue MINE_MOVE_SPEED;
    public static final ForgeConfigSpec.IntValue MINE_JUNK_KEEP;
    public static final ForgeConfigSpec.IntValue MINE_PLACED_LIFETIME;
    public static final ForgeConfigSpec.BooleanValue MINE_SOFT_NO_DURABILITY;
    public static final ForgeConfigSpec.BooleanValue MINE_PILLAR_GUARD;
    public static final ForgeConfigSpec.BooleanValue MINE_HARD_BLOCK_REPORT;
    // v1.5.102：挖矿剩余数值（锚点/超时/距离/节奏/播报）
    public static final ForgeConfigSpec.IntValue MINE_CREATIVE_DEFAULT_VALUE;
    public static final ForgeConfigSpec.IntValue MINE_ANCHOR_TIMEOUT;
    public static final ForgeConfigSpec.IntValue MINE_RELOCATE_THROTTLE;
    public static final ForgeConfigSpec.IntValue MINE_TARGET_TIMEOUT;
    public static final ForgeConfigSpec.DoubleValue MINE_REACH;
    public static final ForgeConfigSpec.IntValue MINE_PILLAR_COOLDOWN;
    public static final ForgeConfigSpec.IntValue MINE_JUNK_CHECK_INTERVAL;
    public static final ForgeConfigSpec.IntValue MINE_SKIP_REPORT_INTERVAL;
    // v1.5.161：进阶挖矿——连锁采集 / 自动收集（默认关闭）
    public static final ForgeConfigSpec.BooleanValue MINE_CHAIN_MINING;
    public static final ForgeConfigSpec.BooleanValue MINE_AUTO_COLLECT;
    // v1.5.163：连锁采集数量上限
    public static final ForgeConfigSpec.IntValue MINE_CHAIN_LIMIT;

    // ================= AI 记忆 =================
    public static final ForgeConfigSpec.BooleanValue MEMORY_ENABLE;
    public static final ForgeConfigSpec.IntValue MEMORY_EXTRACT_THRESHOLD;
    public static final ForgeConfigSpec.IntValue MEMORY_MAX_ENTRIES;
    public static final ForgeConfigSpec.IntValue MEMORY_PROMPT_TOP_N;
    public static final ForgeConfigSpec.IntValue MEMORY_MAX_MESSAGE_CHARS;
    // v1.5.95：记忆子功能精准开关（接更强 agent 时可单独关闭让位）
    public static final ForgeConfigSpec.BooleanValue MEMORY_RELATION_INJECT;
    public static final ForgeConfigSpec.BooleanValue MEMORY_CONFLICT_OVERRIDE;
    public static final ForgeConfigSpec.BooleanValue MEMORY_CORE_FOLD;
    public static final ForgeConfigSpec.BooleanValue MEMORY_WORKING_NOTE;
    // v1.5.98：关系记忆适配（软感知 maidmarriage/Love Loathe 状态写入记忆）
    public static final ForgeConfigSpec.BooleanValue MEMORY_RELATIONSHIP_ADAPTER;
    // v1.5.102：记忆剩余数值（调度/投影/超时/检索/衰减）
    public static final ForgeConfigSpec.IntValue MEMORY_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue MEMORY_PROJECTION_CHARS;
    public static final ForgeConfigSpec.IntValue MEMORY_EXTRACT_TIMEOUT_MIN;
    public static final ForgeConfigSpec.DoubleValue MEMORY_RRF_K;
    public static final ForgeConfigSpec.IntValue MEMORY_DECAY_DAYS;
    public static final ForgeConfigSpec.IntValue MEMORY_DECAY_SALIENCE;
    public static final ForgeConfigSpec.IntValue MEMORY_RELATION_SCAN;
    public static final ForgeConfigSpec.DoubleValue MEMORY_TRUST_DELTA;
    // v1.5.190：记忆防抖写盘 / 主动会话记忆主题注入
    public static final ForgeConfigSpec.BooleanValue MEMORY_LAZY_SAVE;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_MEMORY_TOPIC;
    // v1.5.191：记忆维护周期（定期固化/衰减/关系置信度衰减/error_mark 传播）
    public static final ForgeConfigSpec.IntValue MEMORY_MAINTENANCE_MIN;
    public static final ForgeConfigSpec.IntValue MEMORY_RELATION_DECAY_DAYS;
    // v1.5.198：记忆独立 API（留空 = 跟随 TLM 女仆当前 LLM 站点配置）
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_MODEL;

    // ================= 对话与提示 =================
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_STATUS_REPORTER;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_INTERVAL;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_PROACTIVE;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_COOLDOWN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_DAILY;
    public static final ForgeConfigSpec.IntValue DIALOGUE_SILENCE_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_AUTONOMOUS;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTONOMOUS_COOLDOWN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTONOMOUS_DAILY;
    public static final ForgeConfigSpec.IntValue DIALOGUE_API_DAILY_LIMIT;
    // v1.5.102：对话/自主决策剩余数值
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_CHECK;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_SCAN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_LOW_HP;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_EVENT_CD;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTO_SCAN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTO_OWNER_RANGE;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTO_DAY_START;
    public static final ForgeConfigSpec.IntValue DIALOGUE_AUTO_DAY_END;
    // v1.5.191：主动对话 7 阶段状态机配置
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_MAX_REPLIES;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_IDLE_MIN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_LONG_SILENCE_MAX;
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_REPLY_FEEDBACK;
    public static final ForgeConfigSpec.IntValue DIALOGUE_TOPIC_BACKOFF_MIN;
    // v1.5.198：对话输出语言强制（留空 = 强制中文，v1.5.228）
    public static final ForgeConfigSpec.ConfigValue<String> DIALOGUE_OUTPUT_LANGUAGE;
    // v1.5.231b：对话输出语言检测（LLM 回复非设定语言时丢弃并提示）
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_LANG_CHECK;
    // v1.5.95：感知（快照对比检测）
    public static final ForgeConfigSpec.BooleanValue PERCEPTION_ENABLE;
    public static final ForgeConfigSpec.BooleanValue PERCEPTION_HOSTILE;
    public static final ForgeConfigSpec.BooleanValue PERCEPTION_OWNER;
    public static final ForgeConfigSpec.BooleanValue PERCEPTION_WEATHER;
    // v1.5.102：感知数值（扫描/限频/阈值/注视角度）
    public static final ForgeConfigSpec.IntValue PERCEPTION_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue PERCEPTION_EVENT_COOLDOWN;
    /** v1.5.119：敌对感知显示单独限频（秒）——检测照常，仅显示降频 */
    public static final ForgeConfigSpec.IntValue PERCEPTION_HOSTILE_SHOW_COOLDOWN;
    public static final ForgeConfigSpec.IntValue PERCEPTION_OWNER_LOW_HEALTH;
    public static final ForgeConfigSpec.IntValue PERCEPTION_LOOK_TICKS;
    public static final ForgeConfigSpec.DoubleValue PERCEPTION_LOOK_ENTER_DEG;
    public static final ForgeConfigSpec.DoubleValue PERCEPTION_LOOK_EXIT_DEG;
    // v1.5.95：情绪（PAD 情绪层）
    public static final ForgeConfigSpec.BooleanValue AFFECT_ENABLE;
    public static final ForgeConfigSpec.BooleanValue AFFECT_INJECT;
    // v1.5.102：情绪静默恢复间隔
    public static final ForgeConfigSpec.IntValue AFFECT_RECOVER_INTERVAL;
    // v1.5.95：AI 工具
    public static final ForgeConfigSpec.BooleanValue TOOL_REMEMBER;
    public static final ForgeConfigSpec.BooleanValue TOOL_WORKING_NOTE;
    // v1.5.190：新 AI 工具（帮主人做事）
    public static final ForgeConfigSpec.BooleanValue TOOL_CRAFT;
    public static final ForgeConfigSpec.BooleanValue TOOL_PLACE;
    // v1.5.196：感知查询工具（先查后做：look_around/terrain/build_site/inspect/scanblock/scanentity）
    public static final ForgeConfigSpec.BooleanValue TOOL_PERCEPTION;
    // v1.5.196：工作清单注入（query_todo/build_need：任务计划与缺料查询闭环）
    public static final ForgeConfigSpec.BooleanValue TOOL_WORK_LIST;

    // ================= 战斗与自保 =================
    public static final ForgeConfigSpec.BooleanValue COMBAT_SELF_PRESERVE;
    public static final ForgeConfigSpec.DoubleValue COMBAT_ENTER_RATIO;
    public static final ForgeConfigSpec.DoubleValue COMBAT_EXIT_RATIO;
    public static final ForgeConfigSpec.IntValue COMBAT_THREAT_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_WATER_CLUTCH;
    public static final ForgeConfigSpec.DoubleValue COMBAT_WATER_FALL_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_MASTER_DEATH_TELEPORT;
    /** v1.5.101f：末影珍珠逃生三数值（冷却/触发血量/威胁距离——面板可调） */
    public static final ForgeConfigSpec.IntValue COMBAT_PEARL_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue COMBAT_PEARL_RATIO;
    public static final ForgeConfigSpec.DoubleValue COMBAT_PEARL_DIST;
    // v1.5.102：自保/落地水/避让剩余数值（原硬编码常量全部面板化）
    public static final ForgeConfigSpec.DoubleValue COMBAT_SAFE_RETURN_RATIO;
    public static final ForgeConfigSpec.DoubleValue COMBAT_CLOSE_DISTANCE;
    // v1.5.186：近战/远程搭高上限合并为唯一"至多向上搭多少个方块"（不再按敌人类别划分）
    public static final ForgeConfigSpec.IntValue COMBAT_PILLAR_MAX;
    public static final ForgeConfigSpec.IntValue COMBAT_HEAL_COOLDOWN;
    public static final ForgeConfigSpec.IntValue COMBAT_THREAT_SCAN;
    public static final ForgeConfigSpec.DoubleValue COMBAT_FLEE_SPEED;
    public static final ForgeConfigSpec.IntValue COMBAT_STUCK_WINDOW;
    public static final ForgeConfigSpec.DoubleValue COMBAT_STUCK_THRESHOLD;
    public static final ForgeConfigSpec.IntValue COMBAT_THREAT_GONE_EXIT;
    public static final ForgeConfigSpec.IntValue COMBAT_TELEPORT_COOLDOWN;
    /** v1.5.112：自保传送双判定安全半径（自己/主人身边此半径内无怪物才传，默认 4） */
    public static final ForgeConfigSpec.DoubleValue COMBAT_TELEPORT_SAFE_RADIUS;
    public static final ForgeConfigSpec.IntValue COMBAT_POTION_COOLDOWN;
    public static final ForgeConfigSpec.IntValue COMBAT_ALERT_COOLDOWN;
    public static final ForgeConfigSpec.IntValue COMBAT_ANNOUNCE_COOLDOWN;
    public static final ForgeConfigSpec.IntValue COMBAT_WATER_HOLD;
    public static final ForgeConfigSpec.IntValue COMBAT_WATER_LANDING_SCAN;
    // v1.5.134：单兵作战战术（替代已删除的 v1.5.132 战斗协同——PVP 式走位/拉扯/时机格挡）
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_MELEE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_RANGED;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_SHIELD;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_ORBIT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_KITE_RANGE;
    // v1.5.199：水桶垫水（岩浆逃生——放水 1 秒后收回，水桶不消耗；击退搭高垫水
    // v1.5.250 已删除）
    public static final ForgeConfigSpec.BooleanValue COMBAT_WATER_BUCKET_LAVA;
    // v1.5.203：搭高安全高度（补完目标，与落地水触发高度配合）
    public static final ForgeConfigSpec.IntValue COMBAT_PILLAR_SAFE_HEIGHT;

    // v1.5.189：被动技能（玩家贴身辅助）阈值——喂食/治疗/插火把/共享盾牌/图腾
    public static final ForgeConfigSpec.BooleanValue AID_OWNER_ENABLE;
    public static final ForgeConfigSpec.IntValue AID_FOOD_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue AID_HEALTH_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue TORCH_PLACER_ENABLE;
    public static final ForgeConfigSpec.IntValue TORCH_DARK_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue SHIELD_SHARE_ENABLE;
    public static final ForgeConfigSpec.BooleanValue TOTEM_SHARE_ENABLE;
    // v1.5.207：玩家对女仆伤害策略（0=TLM原版÷5封顶2 / 1=完全免疫 / 2=无限制 / 3=有上限）
    public static final ForgeConfigSpec.IntValue PLAYER_DAMAGE_MODE;
    public static final ForgeConfigSpec.DoubleValue PLAYER_DAMAGE_MAID_CAP;

    // ================= 杂项 =================
    public static final ForgeConfigSpec.IntValue MISC_COOK_RADIUS;
    public static final ForgeConfigSpec.IntValue MISC_BREW_RADIUS;
    public static final ForgeConfigSpec.IntValue MISC_PROCESS_COOLDOWN;
    public static final ForgeConfigSpec.IntValue MISC_BUBBLE_LIMIT_MS;
    public static final ForgeConfigSpec.BooleanValue MISC_PICKUP_PRIORITY;
    // v1.5.102：烹饪/酿造垂直搜索范围（v1.5.134 整理任务已删除，仅烹饪/酿造使用）
    public static final ForgeConfigSpec.IntValue MISC_VERTICAL_RANGE;
    // v1.5.252：酿造自动下料（true=自动两阶段酿药 / false=只维持：补燃料+收成品，
    // 不主动下料——配合 LLM 指令指定目标药水）
    public static final ForgeConfigSpec.BooleanValue MISC_BREW_AUTO;
    // v1.5.129：TLM 原生任务通用呆滞修复（总开关）
    public static final ForgeConfigSpec.BooleanValue MISC_NATIVE_TASK_SMOOTH;
    // v1.5.129：干活不被打断（吃饭/偷吃/恐慌/切班拉回，总开关）
    public static final ForgeConfigSpec.BooleanValue MISC_WORK_UNINTERRUPTED;
    // v1.5.130：产出型任务专项增强（农场连收连种 / 钓鱼主动找水带坐垫）
    public static final ForgeConfigSpec.BooleanValue MISC_PRODUCE_TASK_ENHANCE;
    // v1.5.142：跟随女仆跨维度传送（主人换维度后 5 秒内传送到主人身边）
    public static final ForgeConfigSpec.BooleanValue MISC_DIMENSION_FOLLOW;
    // v1.5.161：农场连锁收获 / 收获物自动收集（默认关闭）
    public static final ForgeConfigSpec.BooleanValue MISC_CHAIN_HARVEST;
    public static final ForgeConfigSpec.BooleanValue MISC_AUTO_COLLECT;
    // v1.5.163：农场连锁收获数量上限
    public static final ForgeConfigSpec.IntValue MISC_CHAIN_HARVEST_LIMIT;
    // v1.5.236：农场批量种植 / 上限（与连锁收获同格式）
    public static final ForgeConfigSpec.BooleanValue MISC_BATCH_PLANT;
    public static final ForgeConfigSpec.IntValue MISC_BATCH_PLANT_LIMIT;
    // v1.5.189：畜牧数量控制（杀幼保成，默认关）
    public static final ForgeConfigSpec.BooleanValue ANIMAL_CAP_CONTROL;
    public static final ForgeConfigSpec.IntValue ANIMAL_CAP_LIMIT;
    // v1.5.199：爱憎分明饥饿/撑死测试开关（默认 true = 禁用其饥饿系统）
    public static final ForgeConfigSpec.BooleanValue MISC_LOVELOATHE_DISABLE_HUNGER;

    // ================= 语音（v1.5.198） =================
    public static final ForgeConfigSpec.DoubleValue TTS_VOLUME_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue TTS_SYSTEM_ENABLED;
    public static final ForgeConfigSpec.IntValue TTS_SYSTEM_COOLDOWN_S;
    public static final ForgeConfigSpec.BooleanValue TTS_VOICE_PACK_ENABLED;
    public static final ForgeConfigSpec.IntValue TTS_CACHE_MAX_FILES;

    public static final ForgeConfigSpec SPEC;

    static {
        // ---- 建造 ----
        BUILDER.comment("建造系统设置").translation("config.promaid.build").push("build");
        BUILD_SPEED_TIER = BUILDER.comment("建造速度档位：x1 / x1.5 / x3")
                .translation("config.promaid.build.speedTier")
                .define("speedTier", "x1.5",
                        o -> o instanceof String s && (s.equals("x1") || s.equals("x1.5") || s.equals("x3")));
        BUILD_TURBO = BUILDER.comment("极速模式（吃满服务器上限，性能风险）")
                .translation("config.promaid.build.turbo").define("turbo", true);
        BUILD_GLOBAL_QUOTA = BUILDER.comment("全局放置配额（每秒方块数上限，性能敏感）")
                .translation("config.promaid.build.globalQuota")
                .defineInRange("globalQuota", 350, 50, 1500);
        BUILD_MAX_FORCE_CHUNKS = BUILDER.comment("建造区强制加载区块上限")
                .translation("config.promaid.build.maxForceChunks")
                .defineInRange("maxForceChunks", 1024, 64, 8192);
        BUILD_MAX_BLOCKS = BUILDER.comment("LLM 蓝图最大方块数（v1.5.222：上限放开到 50 万——构建链统一支持 50 万块级建筑）")
                .translation("config.promaid.build.maxBlocks").defineInRange("maxBlocks", 200, 16, 500000);
        BUILD_MAX_RANGE = BUILDER.comment("LLM 蓝图平面范围（±）")
                .translation("config.promaid.build.maxRange").defineInRange("maxRange", 12, 4, 64);
        BUILD_MAX_HEIGHT = BUILDER.comment("LLM 蓝图高度上限")
                .translation("config.promaid.build.maxHeight").defineInRange("maxHeight", 8, 2, 64);
        BUILD_DESIGN_MAX_BLOCKS = BUILDER.comment("AI 子 Agent 设计蓝图方块上限（v1.5.222：默认与上限放开到 50 万——构建链统一支持 50 万块级建筑）")
                .translation("config.promaid.build.designMaxBlocks").defineInRange("designMaxBlocks", 500000, 100, 500000);
        BUILD_STRUCTURE_MAX_BLOCKS = BUILDER.comment("结构文件蓝图方块上限（100万是服务器负担）")
                .translation("config.promaid.build.structureMaxBlocks")
                .defineInRange("structureMaxBlocks", 1000000, 10000, 4000000);
        BUILD_MAX_MAIDS = BUILDER.comment("Promaid 手册女仆管理列表上限")
                .translation("config.promaid.build.maxMaids").defineInRange("maxMaids", 30, 8, 64);
        BUILD_ORIGIN_PLAYER = BUILDER.comment("建造地点基准：true=玩家脚下（默认），false=女仆脚下")
                .translation("config.promaid.build.originPlayer").define("originPlayer", true);
        BUILD_REGION_TELEPORT_CD = BUILDER.comment("防窒息传送冷却（秒，女仆卡进建造区后传送到区外的冷却）")
                .translation("config.promaid.build.regionTeleportCd")
                .defineInRange("regionTeleportCd", 10, 3, 60);
        BUILD_RESTORE_CACHE_TTL = BUILDER.comment("恢复缓存保留（毫秒，恢复建造状态的内存缓存）")
                .translation("config.promaid.build.restoreCacheTtl")
                .defineInRange("restoreCacheTtl", 5000, 500, 60000);
        BUILD_STALL_INTERVAL = BUILDER.comment("卡住/放置节流（tick，建不动时重试间隔）")
                .translation("config.promaid.build.stallInterval")
                .defineInRange("stallInterval", 20, 4, 100);
        BUILD_LOOKAHEAD = BUILDER.comment("单轮扫描步数上限（建造计划每轮最多推进的步骤）")
                .translation("config.promaid.build.lookahead")
                .defineInRange("lookahead", 512, 64, 4096);
        BUILD_DEFERRED_SCAN_CAP = BUILDER.comment("延后步骤轮询上限（每轮检查的延后步骤数）")
                .translation("config.promaid.build.deferredScanCap")
                .defineInRange("deferredScanCap", 256, 32, 2048);
        BUILD_STRUCTURE_MAX_VOLUME = BUILDER.comment("结构文件体积上限（宽×高×长，超限拒绝加载）")
                .translation("config.promaid.build.structureMaxVolume")
                .defineInRange("structureMaxVolume", 0x1000000, 100000, 67108864);
        BUILDER.pop();

        // ---- 挖矿 ----
        BUILDER.comment("挖矿设置").translation("config.promaid.mine").push("mine");
        MINE_ORE_VALUES = BUILDER.comment("可挖掘方块表（自定义矿表）：每项 方块注册名=价值，如 minecraft:mod_ore=300；适配其他 mod 的矿石")
                .translation("config.promaid.mine.oreValues")
                .defineList("oreValues", List.of(),
                        o -> o instanceof String s && s.contains("="));
        MINE_BREAKABLES = BUILDER.comment("额外可挖穿方块（障碍物名单，path 名如 oak_log——女仆挖矿遇到会挖穿而非当硬挡路报点弃置）")
                .translation("config.promaid.mine.breakables")
                .defineList("extraBreakables", List.of(),
                        o -> o instanceof String s && !s.isEmpty());
        MINE_SEARCH_RADIUS = BUILDER.comment("矿物检索半径（水平）")
                .translation("config.promaid.mine.searchRadius").defineInRange("searchRadius", 24, 8, 64);
        MINE_DOWN_RANGE = BUILDER.comment("垂直向下搜索范围")
                .translation("config.promaid.mine.downRange").defineInRange("downRange", 12, 4, 48);
        MINE_UP_RANGE = BUILDER.comment("垂直向上搜索范围")
                .translation("config.promaid.mine.upRange").defineInRange("upRange", 24, 4, 64);
        MINE_BREAK_BUDGET = BUILDER.comment("穿透预算（允许挖开多少层不可开路挡路方块）")
                .translation("config.promaid.mine.breakBudget").defineInRange("breakBudget", 22, 0, 64);
        MINE_VALUE_WEIGHT = BUILDER.comment("价值权重（高价值矿优先程度）")
                .translation("config.promaid.mine.valueWeight")
                .defineInRange("valueWeight", 2.0, 0.5, 5.0);
        MINE_DEPTH_PENALTY = BUILDER.comment("深度惩罚（越深成本越高）")
                .translation("config.promaid.mine.depthPenalty")
                .defineInRange("depthPenalty", 3.0, 0.0, 10.0);
        MINE_SPEED_FACTOR = BUILDER.comment("挖矿速度系数（1.0=玩家速度，1.2=快20%）")
                .translation("config.promaid.mine.speedFactor")
                .defineInRange("speedFactor", 1.2, 0.5, 3.0);
        MINE_MOVE_SPEED = BUILDER.comment("发现矿物后的移动速度（v1.5.118 默认 0.6 = TLM 伐木任务同款移速（IFarmTask 实测 0.6f），走路观感自然；v1.5.111 曾 0.4：旧 1.35×爱憎分明饥饿档基础速度（最高 0.85）= 每秒 17+ 格狂奔，搭高时女仆直接冲出柱子范围；0.4 又偏慢像爬行）")
                .translation("config.promaid.mine.moveSpeed")
                .defineInRange("moveSpeed", 0.6, 0.2, 1.5);
        MINE_JUNK_KEEP = BUILDER.comment("废石保留量（每种超出销毁）")
                .translation("config.promaid.mine.junkKeep").defineInRange("junkKeep", 32, 4, 128);
        MINE_PLACED_LIFETIME = BUILDER.comment("搭方块自动清理时间（秒）")
                .translation("config.promaid.mine.placedLifetime").defineInRange("placedLifetime", 10, 3, 60);
        // v1.5.138：默认改为 false——与伐木一致，每次挖块都扣镐耐久
        //（旧默认 true 时软方块开路不磨损，用户反馈"挖矿不消耗耐久"；
        //  已生成配置请在面板"软方块不耗耐久"关闭）
        MINE_SOFT_NO_DURABILITY = BUILDER.comment("软方块（徒手可挖）开路不消耗镐耐久（默认关——与伐木一致每次都扣）")
                .translation("config.promaid.mine.softNoDurability").define("softNoDurability", false);
        MINE_PILLAR_GUARD = BUILDER.comment("搭方块防掉落（潜行效果，速度不变）")
                .translation("config.promaid.mine.pillarGuard").define("pillarGuard", true);
        MINE_HARD_BLOCK_REPORT = BUILDER.comment("硬挡路（箱子/机器等）报点弃置该矿")
                .translation("config.promaid.mine.hardBlockReport").define("hardBlockReport", true);
        MINE_CREATIVE_DEFAULT_VALUE = BUILDER.comment("创造面板添加矿物的默认价值")
                .translation("config.promaid.mine.creativeDefaultValue")
                .defineInRange("creativeDefaultValue", 300, 10, 1000);
        MINE_ANCHOR_TIMEOUT = BUILDER.comment("锚点出框超时（tick，出框超过此时长重埋锚点）")
                .translation("config.promaid.mine.anchorTimeout")
                .defineInRange("anchorTimeout", 200, 40, 1200);
        MINE_RELOCATE_THROTTLE = BUILDER.comment("重定位节流（tick，防边界抖动）")
                .translation("config.promaid.mine.relocateThrottle")
                .defineInRange("relocateThrottle", 20, 4, 200);
        MINE_TARGET_TIMEOUT = BUILDER.comment("目标超时（tick，够不到矿超时放弃）")
                .translation("config.promaid.mine.targetTimeout")
                .defineInRange("targetTimeout", 300, 60, 1200);
        MINE_REACH = BUILDER.comment("挖掘/捡拾距离（格）")
                .translation("config.promaid.mine.reach")
                .defineInRange("reach", 4.5, 2.0, 8.0);
        MINE_PILLAR_COOLDOWN = BUILDER.comment("搭方块冷却（tick，垫脚下/搭路节奏）")
                .translation("config.promaid.mine.pillarCooldown")
                .defineInRange("pillarCooldown", 4, 1, 20);
        MINE_JUNK_CHECK_INTERVAL = BUILDER.comment("废石清理检查间隔（tick）")
                .translation("config.promaid.mine.junkCheckInterval")
                .defineInRange("junkCheckInterval", 100, 20, 400);
        MINE_SKIP_REPORT_INTERVAL = BUILDER.comment("跳过矿/捡不到掉落播报间隔（tick，防刷屏）")
                .translation("config.promaid.mine.skipReportInterval")
                .defineInRange("skipReportInterval", 600, 100, 2400);
        // v1.5.161：进阶挖矿——连锁采集 / 自动收集（默认关闭，借鉴 FTB Ultimine 连锁破坏思路）
        MINE_CHAIN_MINING = BUILDER.comment("连锁采集（挖矿时自动连锁挖掘相连的同族矿石——矿脉一次挖完；v1.5.189 默认开启）")
                .translation("config.promaid.mine.chainMining").define("chainMining", true);
        MINE_AUTO_COLLECT = BUILDER.comment("自动收集（挖掘掉落物直接进女仆背包，不进世界；背包放不下才落地）")
                .translation("config.promaid.mine.autoCollect").define("autoCollect", false);
        // v1.5.163：连锁采集数量上限可自定义
        MINE_CHAIN_LIMIT = BUILDER.comment("连锁采集上限（块）：一次连锁挖掘的最大方块数（默认 16）")
                .translation("config.promaid.mine.chainLimit").defineInRange("chainLimit", 16, 4, 64);
        BUILDER.pop();

        // ---- AI 记忆 ----
        BUILDER.comment("AI 记忆设置").translation("config.promaid.memory").push("memory");
        MEMORY_ENABLE = BUILDER.comment("AI 记忆系统全局开关（per-maid 可覆盖）")
                .translation("config.promaid.memory.enable").define("enable", true);
        MEMORY_EXTRACT_THRESHOLD = BUILDER.comment("攒满多少条新对话触发一次 LLM 提取")
                .translation("config.promaid.memory.extractThreshold")
                // v1.5.131：12 → 8——旧默认偏高，日常短聊（几句寒暄）永远攒不满 → "记忆没反应"感
                .defineInRange("extractThreshold", 8, 4, 64);
        MEMORY_MAX_ENTRIES = BUILDER.comment("记忆段落上限（超出淘汰低重要度）")
                .translation("config.promaid.memory.maxEntries").defineInRange("maxEntries", 64, 16, 256);
        MEMORY_PROMPT_TOP_N = BUILDER.comment("注入对话的相关记忆条数")
                .translation("config.promaid.memory.promptTopN").defineInRange("promptTopN", 3, 1, 10);
        MEMORY_MAX_MESSAGE_CHARS = BUILDER.comment("提取时每条对话消息最大字符数")
                .translation("config.promaid.memory.maxMessageChars")
                .defineInRange("maxMessageChars", 200, 50, 500);
        // v1.5.95：记忆子功能精准开关（接更强 agent 时可单独关闭让位）
        MEMORY_RELATION_INJECT = BUILDER.comment("关系三元组注入对话（主人-喜欢-红茶）")
                .translation("config.promaid.memory.relationInject").define("relationInject", true);
        MEMORY_CONFLICT_OVERRIDE = BUILDER.comment("冲突覆盖（新记忆高重要度覆盖旧记忆）")
                .translation("config.promaid.memory.conflictOverride").define("conflictOverride", true);
        MEMORY_CORE_FOLD = BUILDER.comment("摘要折叠（核心记忆常驻+扩展按需）")
                .translation("config.promaid.memory.coreFold").define("coreFold", true);
        MEMORY_WORKING_NOTE = BUILDER.comment("工作笔记（跨对话任务状态注入）")
                .translation("config.promaid.memory.workingNote").define("workingNote", true);
        MEMORY_RELATIONSHIP_ADAPTER = BUILDER.comment("关系感知适配（软感知 maidmarriage 结婚/告白/父女 + Love Loathe 信任/恐惧 → 写入记忆；不依赖，未装则静默）")
                .translation("config.promaid.memory.relationshipAdapter").define("relationshipAdapter", true);
        MEMORY_SCAN_INTERVAL = BUILDER.comment("记忆调度扫描间隔（秒）")
                .translation("config.promaid.memory.scanInterval")
                .defineInRange("scanInterval", 20, 5, 120);
        MEMORY_PROJECTION_CHARS = BUILDER.comment("注入对话的记忆投影字符上限")
                .translation("config.promaid.memory.projectionChars")
                .defineInRange("projectionChars", 600, 100, 2000);
        MEMORY_EXTRACT_TIMEOUT_MIN = BUILDER.comment("LLM 提取超时（分钟，超时允许重试）")
                .translation("config.promaid.memory.extractTimeoutMin")
                .defineInRange("extractTimeoutMin", 5, 1, 30);
        MEMORY_RRF_K = BUILDER.comment("检索融合参数（RRF k，越大越平均）")
                .translation("config.promaid.memory.rrfK")
                .defineInRange("rrfK", 60.0, 10.0, 200.0);
        MEMORY_DECAY_DAYS = BUILDER.comment("记忆衰减周期（天，未访问且重要度低删除）")
                .translation("config.promaid.memory.decayDays")
                .defineInRange("decayDays", 30, 1, 180);
        MEMORY_DECAY_SALIENCE = BUILDER.comment("衰减保留重要度（低于此值的非永久记忆可能被删）")
                .translation("config.promaid.memory.decaySalience")
                .defineInRange("decaySalience", 3, 1, 10);
        MEMORY_RELATION_SCAN = BUILDER.comment("关系感知轮询间隔（秒）")
                .translation("config.promaid.memory.relationScan")
                .defineInRange("relationScan", 20, 5, 120);
        MEMORY_TRUST_DELTA = BUILDER.comment("信任/恐惧显著变化阈值（Love Loathe）")
                .translation("config.promaid.memory.trustDelta")
                .defineInRange("trustDelta", 15.0, 1.0, 50.0);
        // v1.5.190：记忆防抖写盘——写盘延迟合并（默认 20 秒一次批量写），
        // 避免每次写入/检索都全量重写 6 个 jsonl（多女仆时是服务端 IO 热点）
        MEMORY_LAZY_SAVE = BUILDER.comment("防抖写盘（内存累积后按 scanInterval 批量落盘，减少磁盘 IO）")
                .translation("config.promaid.memory.lazySave").define("lazySave", true);
        // v1.5.190：主动会话参考记忆——沉默找话题时从记忆里挑一条值得聊的内容
        // 作为话题（"上次我们一起做过的事/你记得的偏好"），让主动会话有记忆依据
        PROACTIVE_MEMORY_TOPIC = BUILDER.comment("主动会话记忆话题（沉默时从长期记忆选话题，对话更有'记得你'的感觉）")
                .translation("config.promaid.memory.proactiveTopic").define("proactiveTopic", true);
        // v1.5.191：记忆维护周期——之前 prune 只挂在写入路径上，老记忆永远不衰减；
        // 现在由调度器每 N 分钟跑一次 runMaintenance（固化/年龄衰减/访问半衰/关系置信度衰减/error_mark 传播）
        MEMORY_MAINTENANCE_MIN = BUILDER.comment("记忆维护周期（分钟，定期固化重要记忆、衰减陈旧记忆、降旧关系置信度）")
                .translation("config.promaid.memory.maintenanceMin")
                .defineInRange("maintenanceMin", 10, 1, 120);
        MEMORY_RELATION_DECAY_DAYS = BUILDER.comment("关系置信度衰减周期（天，非永久关系 N 天未被强化则置信度×0.85，低到 0.15 变 inactive）")
                .translation("config.promaid.memory.relationDecayDays")
                .defineInRange("relationDecayDays", 60, 7, 365);
        // v1.5.198：记忆独立 API 绑定——填写格式同 TLM（OpenAI 兼容 地址/密钥/模型）；
        // 全部留空 = 跟随 TLM 女仆当前 LLM 站点；任一填写则该项用自定义值，其余仍跟随 TLM
        MEMORY_API_URL = BUILDER.comment("记忆 API 地址（OpenAI 兼容 chat/completions 端点，留空 = 跟随 TLM）")
                .translation("config.promaid.memory.apiUrl").define("apiUrl", "");
        MEMORY_API_KEY = BUILDER.comment("记忆 API 密钥（留空 = 跟随 TLM；明文存 config/promaid-common.toml，与 TLM sites/llm.json 一致）")
                .translation("config.promaid.memory.apiKey").define("apiKey", "");
        MEMORY_API_MODEL = BUILDER.comment("记忆 API 模型（留空 = 跟随 TLM 女仆当前模型）")
                .translation("config.promaid.memory.apiModel").define("apiModel", "");
        BUILDER.pop();

        // ---- 感知（v1.5.95 新段：借鉴 maidsoulcore 感知变化检测）----
        BUILDER.comment("感知设置（快照对比检测变化，纯规则气泡播报）")
                .translation("config.promaid.perception").push("perception");
        PERCEPTION_ENABLE = BUILDER.comment("感知变化检测总开关")
                .translation("config.promaid.perception.enable").define("enable", true);
        PERCEPTION_HOSTILE = BUILDER.comment("敌对检测（出现/接近/消失）")
                .translation("config.promaid.perception.hostile").define("hostile", true);
        PERCEPTION_OWNER = BUILDER.comment("主人检测（受伤/血量低/看向女仆）")
                .translation("config.promaid.perception.owner").define("owner", true);
        PERCEPTION_WEATHER = BUILDER.comment("天气变化检测")
                .translation("config.promaid.perception.weather").define("weather", true);
        PERCEPTION_SCAN_INTERVAL = BUILDER.comment("快照扫描间隔（tick）")
                .translation("config.promaid.perception.scanInterval")
                .defineInRange("scanInterval", 20, 5, 100);
        PERCEPTION_EVENT_COOLDOWN = BUILDER.comment("同类事件播报限频（秒，非敌对事件用）")
                .translation("config.promaid.perception.eventCooldown")
                .defineInRange("eventCooldown", 30, 5, 300);
        PERCEPTION_HOSTILE_SHOW_COOLDOWN = BUILDER.comment("敌对感知显示限频（秒，v1.5.119：感知检测照常、仅'发现怪物'类显示大大降频；默认 300 秒 = 5 分钟一条）")
                .translation("config.promaid.perception.hostileShowCooldown")
                .defineInRange("hostileShowCooldown", 300, 60, 3600);
        PERCEPTION_OWNER_LOW_HEALTH = BUILDER.comment("主人血量低阈值（%）")
                .translation("config.promaid.perception.ownerLowHealth")
                .defineInRange("ownerLowHealth", 30, 10, 90);
        PERCEPTION_LOOK_TICKS = BUILDER.comment("主人持续注视判定时长（秒）")
                .translation("config.promaid.perception.lookTicks")
                .defineInRange("lookTicks", 3, 1, 30);
        PERCEPTION_LOOK_ENTER_DEG = BUILDER.comment("看向进入角度（度）")
                .translation("config.promaid.perception.lookEnterDeg")
                .defineInRange("lookEnterDeg", 35.0, 10.0, 80.0);
        PERCEPTION_LOOK_EXIT_DEG = BUILDER.comment("看向退出角度（度）")
                .translation("config.promaid.perception.lookExitDeg")
                .defineInRange("lookExitDeg", 55.0, 20.0, 90.0);
        BUILDER.pop();

        // ---- 情绪（v1.5.95 新段：PAD 情绪层）----
        BUILDER.comment("情绪设置（PAD 情绪层，独立于好感/心契/爱憎）")
                .translation("config.promaid.affect").push("affect");
        AFFECT_ENABLE = BUILDER.comment("PAD 情绪层总开关（事件驱动+落盘）")
                .translation("config.promaid.affect.enable").define("enable", true);
        AFFECT_INJECT = BUILDER.comment("情绪注入对话上下文（ai_affect）")
                .translation("config.promaid.affect.inject").define("inject", true);
        AFFECT_RECOVER_INTERVAL = BUILDER.comment("情绪静默恢复间隔（秒，无事件时情绪值缓慢回归）")
                .translation("config.promaid.affect.recoverInterval")
                .defineInRange("recoverInterval", 20, 5, 300);
        BUILDER.pop();

        // ---- AI 工具（v1.5.95 新段：LLM 工具精准开关）----
        BUILDER.comment("AI 工具设置（LLM 对话中可调用的增强工具）")
                .translation("config.promaid.aitools").push("aitools");
        TOOL_REMEMBER = BUILDER.comment("remember 工具（LLM 主动写记忆，\"记住…\"）")
                .translation("config.promaid.aitools.remember").define("remember", true);
        TOOL_WORKING_NOTE = BUILDER.comment("working_note 工具（跨对话任务笔记）")
                .translation("config.promaid.aitools.workingNote").define("workingNote", true);
        // v1.5.190：新 AI 工具——帮主人做事的两个"双手"工具
        TOOL_CRAFT = BUILDER.comment("smart_craft 工具（按配方合成——从自己背包取材料，成品交给主人）")
                .translation("config.promaid.aitools.craft").define("craft", true);
        TOOL_PLACE = BUILDER.comment("smart_place 工具（从背包取出方块放到指定位置）")
                .translation("config.promaid.aitools.place").define("place", true);
        // v1.5.196：感知查询工具——先查后做（移植 PatchouliAI 查询工具集）
        TOOL_PERCEPTION = BUILDER.comment("perception_query 工具（look_around/terrain/build_site/inspect/scanblock/scanentity——建造前先探查环境，降低超时）")
                .translation("config.promaid.aitools.perception").define("perception", true);
        // v1.5.196：工作清单注入——查询-行动闭环（任务计划 + 材料缺口）
        TOOL_WORK_LIST = BUILDER.comment("work_list 工具（query_todo/build_need——当前任务清单与建造材料缺口查询，杜绝'先生成清单再开工'的重复轮次）")
                .translation("config.promaid.aitools.workList").define("workList", true);
        BUILDER.pop();

        // ---- 对话与提示 ----
        BUILDER.comment("对话与提示设置").translation("config.promaid.dialogue").push("dialogue");
        DIALOGUE_STATUS_REPORTER = BUILDER.comment("工作状态播报（女仆卡住时气泡解释原因）")
                .translation("config.promaid.dialogue.statusReporter").define("statusReporter", true);
        DIALOGUE_REPORT_INTERVAL = BUILDER.comment("工作播报间隔（秒）")
                .translation("config.promaid.dialogue.reportInterval").defineInRange("reportInterval", 10, 3, 120);
        DIALOGUE_REPORT_RADIUS = BUILDER.comment("工作播报扫描范围")
                .translation("config.promaid.dialogue.reportRadius").defineInRange("reportRadius", 32, 8, 128);
        DIALOGUE_PROACTIVE = BUILDER.comment("主动对话（关心/夜晚/好感等主动开口）")
                .translation("config.promaid.dialogue.proactive").define("proactive", true);
        DIALOGUE_PROACTIVE_COOLDOWN = BUILDER.comment("两次主动发言最小间隔（分钟）")
                .translation("config.promaid.dialogue.proactiveCooldown").defineInRange("proactiveCooldown", 4, 1, 60);
        DIALOGUE_PROACTIVE_DAILY = BUILDER.comment("主动对话日上限（次，控 token 成本；v1.5.191：4 → 12——7 阶段状态机需要更多发言额度）")
                .translation("config.promaid.dialogue.proactiveDaily").defineInRange("proactiveDaily", 12, 0, 50);
        DIALOGUE_SILENCE_THRESHOLD = BUILDER.comment("沉默多久触发找话题（分钟）")
                .translation("config.promaid.dialogue.silenceThreshold").defineInRange("silenceThreshold", 20, 2, 240);
        DIALOGUE_AUTONOMOUS = BUILDER.comment("自主决策（女仆自己换任务干活）")
                .translation("config.promaid.dialogue.autonomous").define("autonomous", true);
        DIALOGUE_AUTONOMOUS_COOLDOWN = BUILDER.comment("自主决策冷却（分钟）")
                .translation("config.promaid.dialogue.autonomousCooldown").defineInRange("autonomousCooldown", 10, 1, 120);
        DIALOGUE_AUTONOMOUS_DAILY = BUILDER.comment("自主决策日上限（次）")
                .translation("config.promaid.dialogue.autonomousDaily").defineInRange("autonomousDaily", 10, 0, 50);
        DIALOGUE_API_DAILY_LIMIT = BUILDER.comment("所有女仆每日主动 LLM 调用总量上限（token 成本；v1.5.191：10 → 40，0 = 不限——旧语义 0=永远禁言是 bug）")
                .translation("config.promaid.dialogue.apiDailyLimit").defineInRange("apiDailyLimit", 40, 0, 400);
        DIALOGUE_REPORT_CHECK = BUILDER.comment("工作播报检查间隔（tick）")
                .translation("config.promaid.dialogue.reportCheck")
                .defineInRange("reportCheck", 20, 4, 200);
        DIALOGUE_PROACTIVE_SCAN = BUILDER.comment("主动对话周期扫描间隔（秒）")
                .translation("config.promaid.dialogue.proactiveScan")
                .defineInRange("proactiveScan", 20, 5, 300);
        DIALOGUE_PROACTIVE_LOW_HP = BUILDER.comment("主动关心主人低血阈值（%）")
                .translation("config.promaid.dialogue.proactiveLowHp")
                .defineInRange("proactiveLowHp", 30, 10, 90);
        DIALOGUE_PROACTIVE_EVENT_CD = BUILDER.comment("主动对话事件驱动冷却（秒，重伤/死亡等紧急事件）")
                .translation("config.promaid.dialogue.proactiveEventCd")
                .defineInRange("proactiveEventCd", 30, 5, 300);
        DIALOGUE_AUTO_SCAN = BUILDER.comment("自主决策检查间隔（秒）")
                .translation("config.promaid.dialogue.autoScan")
                .defineInRange("autoScan", 60, 10, 600);
        DIALOGUE_AUTO_OWNER_RANGE = BUILDER.comment("自主决策触发主人范围")
                .translation("config.promaid.dialogue.autoOwnerRange")
                .defineInRange("autoOwnerRange", 16, 4, 64);
        DIALOGUE_AUTO_DAY_START = BUILDER.comment("自主决策工作开始时刻（游戏 tick）")
                .translation("config.promaid.dialogue.autoDayStart")
                .defineInRange("autoDayStart", 1000, 0, 23000);
        DIALOGUE_AUTO_DAY_END = BUILDER.comment("自主决策工作结束时刻（游戏 tick）")
                .translation("config.promaid.dialogue.autoDayEnd")
                .defineInRange("autoDayEnd", 13000, 0, 24000);
        // v1.5.191：主动对话 7 阶段状态机（对齐 maidsoulcore ProactiveStage）
        DIALOGUE_PROACTIVE_MAX_REPLIES = BUILDER.comment("每轮主动会话最多发言次数（v1.5.191：7 阶段不会一次性全喷——主人一次互动周期内最多主动发 N 次，之后进入空闲）")
                .translation("config.promaid.dialogue.maxReplies")
                .defineInRange("maxReplies", 4, 1, 7);
        DIALOGUE_PROACTIVE_IDLE_MIN = BUILDER.comment("主动对话空闲重启（分钟，一轮跑完/被打断后主人 N 分钟没互动才重启新周期）")
                .translation("config.promaid.dialogue.proactiveIdleMin")
                .defineInRange("proactiveIdleMin", 60, 5, 600);
        DIALOGUE_LONG_SILENCE_MAX = BUILDER.comment("长沉默确认每日上限（次，\"主人还在吗\"这类确认一天最多几次，防烦人）")
                .translation("config.promaid.dialogue.longSilenceMax")
                .defineInRange("longSilenceMax", 2, 0, 10);
        DIALOGUE_REPLY_FEEDBACK = BUILDER.comment("回复反馈学习（主人说'别说了/好烦'→ 记 error_mark 停止该话题；说'谢谢/说得对'→ 记忆强化；真沉默计时也靠它）")
                .translation("config.promaid.dialogue.replyFeedback").define("replyFeedback", true);
        DIALOGUE_TOPIC_BACKOFF_MIN = BUILDER.comment("话题冷却（分钟，被主人否定的主动话题 N 分钟内不再提起）")
                .translation("config.promaid.dialogue.topicBackoffMin")
                .defineInRange("topicBackoffMin", 60, 5, 600);
        // v1.5.198：对话输出语言强制——原版机制按 Minecraft 客户端语言要求 LLM 输出
        //（每次对话写入女仆 ChatLanguage，"突然变日语"= 客户端语言/女仆设置是日语）。
        // v1.5.228：留空 = 默认强制中文（zh_cn）——"留空跟随"导致日文对话持续出现；
        // 想跟随其他语言显式填 ja_jp/en_us 等
        DIALOGUE_OUTPUT_LANGUAGE = BUILDER.comment("对话输出语言（留空 = 强制中文输出；填 ja_jp/en_us 等强制对应语言）")
                .translation("config.promaid.dialogue.outputLanguage").define("outputLanguage", "");
        // v1.5.231b：输出语言二次检测——LLM 回复落地时检查文字是否为设定语言，
        // 不符（日文/英文混入）则丢弃并提示（日志搜 "lang check" 看原文）
        DIALOGUE_LANG_CHECK = BUILDER.comment("对话输出语言检测（v1.5.250 起：LLM 回复非设定语言时【内嵌翻译】成目标语言再显示——替代旧版审查打回重刷）")
                .translation("config.promaid.dialogue.langCheck").define("langCheck", true);
        BUILDER.pop();

        // ---- 战斗与自保 ----
        BUILDER.comment("战斗与自保设置").translation("config.promaid.combat").push("combat");
        COMBAT_SELF_PRESERVE = BUILDER.comment("自保行为（低血逃跑/搭高/治疗）")
                .translation("config.promaid.combat.selfPreserve").define("selfPreserve", true);
        COMBAT_ENTER_RATIO = BUILDER.comment("自保触发血量（0-1）")
                .translation("config.promaid.combat.enterRatio").defineInRange("enterRatio", 0.30, 0.05, 1.0);
        // v1.5.153：默认 0.60→0.70——自保取消机制之一为血量恢复到 70% 及以上
        COMBAT_EXIT_RATIO = BUILDER.comment("自保解除血量（0-1，v1.5.153 默认 0.7：血量恢复到 70% 及以上即取消自保；另一取消机制 = 成功传送回主人身边）")
                .translation("config.promaid.combat.exitRatio").defineInRange("exitRatio", 0.70, 0.1, 1.0);
        COMBAT_THREAT_DISTANCE = BUILDER.comment("威胁感知距离")
                .translation("config.promaid.combat.threatDistance").defineInRange("threatDistance", 12, 4, 32);
        COMBAT_WATER_CLUTCH = BUILDER.comment("落地水（有水桶+坠落自动放水缓冲）")
                .translation("config.promaid.combat.waterClutch").define("waterClutch", true);
        COMBAT_WATER_FALL_DISTANCE = BUILDER.comment("落地水触发高度（格）")
                .translation("config.promaid.combat.waterFallDistance").defineInRange("waterFallDistance", 3.0, 2.0, 20.0);
        // v1.5.199：水桶垫水——岩浆逃生时放水灭火（1 秒后收回）
        COMBAT_WATER_BUCKET_LAVA = BUILDER.comment("岩浆逃生放水（垫高后周围无水源且包里有水桶 → 在自己垫的方块上放水灭火，1 秒后收回；岩浆源可能变黑曜石）")
                .translation("config.promaid.combat.waterBucketLava").define("waterBucketLava", true);
        COMBAT_MASTER_DEATH_TELEPORT = BUILDER.comment("主人死亡强制传送（无视战斗/距离）")
                .translation("config.promaid.combat.masterDeathTeleport").define("masterDeathTeleport", true);
        COMBAT_PEARL_COOLDOWN = BUILDER.comment("末影珍珠逃生冷却（tick，20=1 秒；默认 100=5 秒）")
                .translation("config.promaid.combat.pearlCooldown")
                .defineInRange("pearlCooldown", 100, 20, 1200);
        COMBAT_PEARL_RATIO = BUILDER.comment("末影珍珠逃生触发血量（0-1，低于此值且威胁贴身才扔）")
                .translation("config.promaid.combat.pearlRatio")
                .defineInRange("pearlRatio", 0.30, 0.05, 0.5);
        COMBAT_PEARL_DIST = BUILDER.comment("末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠）")
                .translation("config.promaid.combat.pearlDist")
                .defineInRange("pearlDist", 8.0, 2.0, 16.0);
        COMBAT_SAFE_RETURN_RATIO = BUILDER.comment("安全回归血量（0-1，威胁消失后回到此血量解除自保）")
                .translation("config.promaid.combat.safeReturnRatio")
                .defineInRange("safeReturnRatio", 0.45, 0.2, 0.9);
        COMBAT_CLOSE_DISTANCE = BUILDER.comment("贴身距离（格，低于此值判定被近身）")
                .translation("config.promaid.combat.closeDistance")
                .defineInRange("closeDistance", 4.0, 2.0, 8.0);
    // v1.5.186：原"近战搭高上限（默认10）/远程搭高上限（默认30）"合并为唯一
    // 控制项"至多向上搭多少个方块"，默认 30，不再按敌人近战/远程划分
    COMBAT_PILLAR_MAX = BUILDER.comment("至多向上搭多少个方块（格）")
            .translation("config.promaid.combat.pillarMax")
            .defineInRange("pillarMax", 30, 5, 64);
    // v1.5.203：搭高安全高度（补完目标）——默认 5：搭高惯性/补完垫到 5 格后跳下，
    // fallDistance 到落地水阈值（默认 3.0）时离地还有约 2 格放水窗口，稳定触发落地水
    //（水减速怪物的小配合；旧写死 4 太临界，触发时已贴近地面放水来不及）
    COMBAT_PILLAR_SAFE_HEIGHT = BUILDER.comment("搭高安全高度（格，默认 5）：搭高惯性/补完垫到的高度——调高可配合落地水触发高度（跳下稳定触发落地水减速怪物），调低则更快下柱")
            .translation("config.promaid.combat.pillarSafeHeight")
            .defineInRange("pillarSafeHeight", 5, 2, 12);
    COMBAT_HEAL_COOLDOWN = BUILDER.comment("治疗食物冷却（tick）")
                .translation("config.promaid.combat.healCooldown")
                .defineInRange("healCooldown", 40, 10, 200);
        COMBAT_THREAT_SCAN = BUILDER.comment("威胁扫描间隔（tick）")
                .translation("config.promaid.combat.threatScan")
                .defineInRange("threatScan", 5, 1, 40);
        COMBAT_FLEE_SPEED = BUILDER.comment("逃跑速度倍率")
                .translation("config.promaid.combat.fleeSpeed")
                .defineInRange("fleeSpeed", 1.4, 0.8, 3.0);
        COMBAT_STUCK_WINDOW = BUILDER.comment("卡住判定窗口（tick）")
                .translation("config.promaid.combat.stuckWindow")
                .defineInRange("stuckWindow", 20, 5, 100);
        COMBAT_STUCK_THRESHOLD = BUILDER.comment("卡住位移阈值（格）")
                .translation("config.promaid.combat.stuckThreshold")
                .defineInRange("stuckThreshold", 0.3, 0.05, 1.0);
        COMBAT_THREAT_GONE_EXIT = BUILDER.comment("威胁消失退出时长（tick，400=20 秒：威胁消失后观察 20 秒确认安全才结束自保/传回主人身边）")
                .translation("config.promaid.combat.threatGoneExit")
                .defineInRange("threatGoneExit", 400, 40, 1200);
        // v1.5.152：冷却默认 1200→200（10 秒）——自保全程实时尝试传送回主人，
        // 传不走（主人身边有怪）10 秒后重试；传送成功即结束自保，不会循环
        COMBAT_TELEPORT_COOLDOWN = BUILDER.comment("传送回家冷却（tick，默认 200 = 10 秒：自保实时判定传送，传不走 10 秒后重试）")
                .translation("config.promaid.combat.teleportCooldown")
                .defineInRange("teleportCooldown", 200, 100, 6000);
        // v1.5.150：只判主人身边；v1.5.151：默认 5 格（防远程怪；传回主人身边后
        // 主人可直接拿魂符收起来绝对安全，判定不需要太大）
        COMBAT_TELEPORT_SAFE_RADIUS = BUILDER.comment("传送安全判定半径（格，主人身边此半径内无可见怪物才传送回主人，默认 5）")
                .translation("config.promaid.combat.teleportSafeRadius")
                .defineInRange("teleportSafeRadius", 5.0, 2.0, 8.0);
        COMBAT_POTION_COOLDOWN = BUILDER.comment("药水尝试间隔（tick）")
                .translation("config.promaid.combat.potionCooldown")
                .defineInRange("potionCooldown", 40, 10, 200);
        COMBAT_ALERT_COOLDOWN = BUILDER.comment("头顶警示粒子间隔（tick）")
                .translation("config.promaid.combat.alertCooldown")
                .defineInRange("alertCooldown", 60, 10, 300);
        COMBAT_ANNOUNCE_COOLDOWN = BUILDER.comment("策略播报间隔（tick，防刷屏）")
                .translation("config.promaid.combat.announceCooldown")
                .defineInRange("announceCooldown", 200, 40, 600);
        COMBAT_WATER_HOLD = BUILDER.comment("落地水保持时长（tick）")
                .translation("config.promaid.combat.waterHold")
                .defineInRange("waterHold", 20, 5, 100);
        COMBAT_WATER_LANDING_SCAN = BUILDER.comment("落地水下探格数（提前放水检测）")
                .translation("config.promaid.combat.waterLandingScan")
                .defineInRange("waterLandingScan", 8, 2, 16);
        // v1.5.134：单兵作战战术（v1.5.132 战斗协同已删除——协同不如单兵 PVP 操作感）
        COMBAT_TACTICS = BUILDER.comment("单兵作战战术（绕圈走位/打退拉扯/距离控制/时机举盾——PVP 式战斗）")
                .translation("config.promaid.combat.tactics").define("tactics", true);
        COMBAT_TACTICS_MELEE = BUILDER.comment("近战战术（贴脸绕圈、打一刀退一步、跳劈接近）")
                .translation("config.promaid.combat.tacticsMelee").define("tacticsMelee", true);
        COMBAT_TACTICS_RANGED = BUILDER.comment("远程战术（保持理想射程、横移绕圈风筝）")
                .translation("config.promaid.combat.tacticsRanged").define("tacticsRanged", true);
        COMBAT_TACTICS_SHIELD = BUILDER.comment("时机举盾（攻击冷却间隙举盾格挡、攻防交替；替代原版一直举盾）")
                .translation("config.promaid.combat.tacticsShield").define("tacticsShield", true);
        COMBAT_TACTICS_ORBIT_RADIUS = BUILDER.comment("绕圈半径（格）：近战贴脸绕圈 / 远程横移的圆周半径")
                .translation("config.promaid.combat.tacticsOrbitRadius")
                .defineInRange("tacticsOrbitRadius", 2.2, 1.2, 4.0);
        COMBAT_TACTICS_KITE_RANGE = BUILDER.comment("远程理想射程倍率（0.6 = 保持在最大射程 60% 的距离放风筝）")
                .translation("config.promaid.combat.tacticsKiteRange")
                .defineInRange("tacticsKiteRange", 0.6, 0.3, 0.9);
        // v1.5.189：玩家贴身辅助（被动技能，非工作状态——女仆随时照看主人）
        AID_OWNER_ENABLE = BUILDER.comment("自动投喂/治疗主人（被动：主人饿/血低自动喂食或投掷治疗药水）")
                .translation("config.promaid.combat.aidOwnerEnable").define("aidOwnerEnable", true);
        AID_FOOD_THRESHOLD = BUILDER.comment("投喂触发饱食度（0-20：主人饱食度低于此值自动喂食）")
                .translation("config.promaid.combat.aidFoodThreshold").defineInRange("aidFoodThreshold", 12, 4, 18);
        AID_HEALTH_THRESHOLD = BUILDER.comment("治疗触发血量（0-1：主人血量低于此比例自动治疗）")
                .translation("config.promaid.combat.aidHealthThreshold").defineInRange("aidHealthThreshold", 0.30, 0.1, 0.8);
        TORCH_PLACER_ENABLE = BUILDER.comment("被动插火把（主人周围黑暗自动插火把照明）")
                .translation("config.promaid.combat.torchPlacerEnable").define("torchPlacerEnable", true);
        TORCH_DARK_THRESHOLD = BUILDER.comment("插火把亮度阈值（0-15：主人脚下亮度低于此值自动插火把）")
                .translation("config.promaid.combat.torchDarkThreshold").defineInRange("torchDarkThreshold", 7, 4, 12);
        SHIELD_SHARE_ENABLE = BUILDER.comment("共享盾牌（主人盾牌耐久低/空时，从自己背包取盾给主人——不动自己副手）")
                .translation("config.promaid.combat.shieldShareEnable").define("shieldShareEnable", true);
        TOTEM_SHARE_ENABLE = BUILDER.comment("共享不死图腾（主人致命伤时，女仆背包/饰品栏的不死图腾优先救主人，特效同原版）")
                .translation("config.promaid.combat.totemShareEnable").define("totemShareEnable", true);
        // v1.5.207：玩家对女仆伤害模式——TLM 原版是"主人攻击 ÷5 封顶 2 点"（原版剑
        // 看起来打不到、高伤武器（如更好的战斗）能打出 2 点），玩家可自选策略
        // v1.5.252h：defineInRange 上限 3 → 4——旧版面板第 5 档"仅一点伤害"（值 4）
        // 超出范围保存不进去（货不对板：mixin 支持 0~4 但配置只收 0~3）
        PLAYER_DAMAGE_MODE = BUILDER.comment("玩家对女仆伤害模式（0=TLM原版压制÷5封顶2点、1=玩家伤害完全免疫、2=玩家伤害无限制、3=玩家伤害有上限（比例见 playerDamageMaidCap）、4=仅受到一点伤害（单次上限1点，被打有反馈但不疼））")
                .translation("config.promaid.combat.playerDamageMode").defineInRange("playerDamageMode", 0, 0, 4);
        PLAYER_DAMAGE_MAID_CAP = BUILDER.comment("玩家伤害上限比例（0-1：模式 3 时单次伤害 = 女仆最大生命 × 此比例；默认 0.1 = 10%）")
                .translation("config.promaid.combat.playerDamageMaidCap").defineInRange("playerDamageMaidCap", 0.1, 0.01, 0.5);
        BUILDER.pop();

        // ---- 杂项 ----
        BUILDER.comment("杂项设置").translation("config.promaid.misc").push("misc");
        MISC_COOK_RADIUS = BUILDER.comment("烹饪任务熔炉搜索范围")
                .translation("config.promaid.misc.cookRadius").defineInRange("cookRadius", 16, 4, 48);
        MISC_BREW_RADIUS = BUILDER.comment("酿造任务酿造台搜索范围")
                .translation("config.promaid.misc.brewRadius").defineInRange("brewRadius", 16, 4, 48);
        MISC_PROCESS_COOLDOWN = BUILDER.comment("烹饪/酿造处理间隔（tick）")
                .translation("config.promaid.misc.processCooldown").defineInRange("processCooldown", 40, 10, 200);
        MISC_BUBBLE_LIMIT_MS = BUILDER.comment("对话气泡限频（毫秒，防刷屏）")
                .translation("config.promaid.misc.bubbleLimitMs").defineInRange("bubbleLimitMs", 5000, 500, 60000);
        MISC_PICKUP_PRIORITY = BUILDER.comment("挖矿中禁止拾取（捡掉落物最低优先级）")
                .translation("config.promaid.misc.pickupPriority").define("pickupPriority", true);
        MISC_VERTICAL_RANGE = BUILDER.comment("烹饪/酿造垂直搜索范围")
                .translation("config.promaid.misc.verticalRange")
                .defineInRange("verticalRange", 4, 1, 16);
        MISC_BREW_AUTO = BUILDER.comment("酿造自动下料（true=自动两阶段酿药 / false=只维持：补燃料+收成品，不主动下料——配合 LLM 指令指定目标药水）")
                .translation("config.promaid.misc.brewAuto").define("brewAuto", true);
        // v1.5.129：原生任务呆滞修复 + 干活不被打断
        MISC_NATIVE_TASK_SMOOTH = BUILDER.comment("TLM 原生任务呆滞修复（行为无限时长/随机散步让位/走路少刹车/检查节流减半）")
                .translation("config.promaid.misc.nativeTaskSmooth").define("nativeTaskSmooth", true);
        MISC_WORK_UNINTERRUPTED = BUILDER.comment("干活不被打断（工作中跳过吃饭/偷吃/小伤恐慌/切班拽回）")
                .translation("config.promaid.misc.workUninterrupted").define("workUninterrupted", true);
        // v1.5.130：产出型任务专项增强
        MISC_PRODUCE_TASK_ENHANCE = BUILDER.comment("产出任务增强（农场连收连种 / 钓鱼主动找水域自带坐垫）")
                .translation("config.promaid.misc.produceTaskEnhance").define("produceTaskEnhance", true);
        // v1.5.142：跨维度跟随
        MISC_DIMENSION_FOLLOW = BUILDER.comment("跟随女仆跨维度传送（主人换维度后，跟随模式女仆自动传送到主人身边；坐着的/在家模式的女仆不拉）")
                .translation("config.promaid.misc.dimensionFollow").define("dimensionFollow", true);
    // v1.5.161：农场连锁收获 / 收获物自动收集（v1.5.189：连锁默认开启——用户要求
    // "连锁采集也应加入"；收获物收集保持默认关，避免自动拾取导致背包爆炸）
    MISC_CHAIN_HARVEST = BUILDER.comment("农场连锁收获（收割时以目标格为中心蔓延连锁收割相连农田里的成熟作物）")
            .translation("config.promaid.misc.chainHarvest").define("chainHarvest", true);
    MISC_AUTO_COLLECT = BUILDER.comment("收获物自动收集（收割产物——作物/种子等直接进女仆背包，不落地）")
            .translation("config.promaid.misc.autoCollect").define("autoCollect", false);
    // v1.5.163：农场连锁收获数量上限可自定义
    MISC_CHAIN_HARVEST_LIMIT = BUILDER.comment("农场连锁收获上限（格）：一次连锁收割的最大格数（默认 24，大农田多轮清完）")
            .translation("config.promaid.misc.chainHarvestLimit").defineInRange("chainHarvestLimit", 24, 4, 96);
    // v1.5.236：农场批量种植（与连锁收获同格式）——到田里一次种一片空耕地
    MISC_BATCH_PLANT = BUILDER.comment("农场批量种植（种植时以当前格为中心蔓延，把相连农田里的空耕地一次全种上）")
            .translation("config.promaid.misc.batchPlant").define("batchPlant", true);
    MISC_BATCH_PLANT_LIMIT = BUILDER.comment("农场批量种植上限（格）：一次批量种植的最大格数（默认 24，大农田多轮种完）")
            .translation("config.promaid.misc.batchPlantLimit").defineInRange("batchPlantLimit", 24, 4, 96);
    // v1.5.189：畜牧数量控制（杀幼保成）——默认关（激进操作，玩家手动开启）
    ANIMAL_CAP_CONTROL = BUILDER.comment("畜牧数量控制（杀幼保成）：附近同种成年动物超过上限时击杀多余幼年动物（激进操作，默认关）")
            .translation("config.promaid.misc.animalCapControl").define("animalCapControl", false);
    ANIMAL_CAP_LIMIT = BUILDER.comment("畜牧数量上限（只，默认 50）：同种动物超过此数时执行杀幼保成")
            .translation("config.promaid.misc.animalCapLimit").defineInRange("animalCapLimit", 50, 5, 200);
    // v1.5.199：爱憎分明饥饿测试开关——其自动进食会优先吃腐肉导致"越吃越饿/饿死"，
    // 饿死/撑死伤害与速度惩罚也一并关闭（测试期默认关闭；关闭本项恢复原版饥饿行为）
    MISC_LOVELOATHE_DISABLE_HUNGER = BUILDER.comment("禁用爱憎分明饥饿/撑死（默认开：饿死伤害/撑死/自动进食（含腐肉）/速度惩罚全禁；关掉恢复原版）")
            .translation("config.promaid.misc.loveLoatheHungerOff").define("loveLoatheHungerOff", true);
    BUILDER.pop();

        // ---- 语音（v1.5.198：TTS 音量倍率 / 系统消息朗读 / 系统语音包 / 语音缓存）----
        BUILDER.comment("语音设置（TTS 音量倍率 / 系统消息朗读 / 系统语音包 / 语音缓存）")
                .translation("config.promaid.voice").push("voice");
        TTS_VOLUME_MULTIPLIER = BUILDER.comment("TTS 语音播放音量倍率（与伤害/减伤无关！）：TLM 播放 TTS 语音时的原始音量为 1.0（偏小），此值直接乘在播放音量上——1.5 = 音量放大 50%，2.0 = 放大一倍，0.5 = 减半。默认 2.0，范围 0.1-5.0。作用于 LLM 对话 TTS 与系统消息 TTS 的播放音量")
                .translation("config.promaid.voice.volumeMultiplier")
                .defineInRange("volumeMultiplier", 2.0, 0.1, 5.0);
        TTS_SYSTEM_ENABLED = BUILDER.comment("系统消息朗读（感知/工作/自保等规则气泡也播放 TTS 语音）")
                .translation("config.promaid.voice.systemEnabled").define("systemEnabled", true);
        TTS_SYSTEM_COOLDOWN_S = BUILDER.comment("系统消息朗读冷却（秒，同一女仆两次朗读最小间隔）")
                .translation("config.promaid.voice.systemCooldownS")
                .defineInRange("systemCooldownS", 8, 0, 60);
        TTS_VOICE_PACK_ENABLED = BUILDER.comment("系统语音包（config/maid_smart/system_voice/ 下 manifest.json 映射文本→ogg，命中则免 TTS 直接播放）")
                .translation("config.promaid.voice.voicePackEnabled").define("voicePackEnabled", true);
        TTS_CACHE_MAX_FILES = BUILDER.comment("TTS 语音缓存上限（config/maid_smart/voice_cache/，训练一次保存后复用；超出删最旧）")
                .translation("config.promaid.voice.cacheMaxFiles")
                .defineInRange("cacheMaxFiles", 200, 10, 2000);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private MaidSmartConfig() {
    }
}
