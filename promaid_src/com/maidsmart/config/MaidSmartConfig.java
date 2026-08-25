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
/** v1.5.316：红石机器专属搭建（专属顺序 + 活建造 + 自动放矿车），默认开 */
public static final ForgeConfigSpec.BooleanValue BUILD_MACHINE_SMART;
// v1.5.331：TNT 点火保护期（秒）——建造期/完工激活期/宽限期压制一切 TNT 点火
public static final ForgeConfigSpec.IntValue BUILD_TNT_IGNITION_GRACE;
/** v1.1.0 实测八十二：蓝图投影预览——区块显示时叠加半透明幽灵方块轮廓（确认朝向/形状） */
public static final ForgeConfigSpec.BooleanValue BUILD_PROJECTION;
    // v1.5.254：缺料自动替代（先同族后自定义；按高度分类的三张自定义表）
    public static final ForgeConfigSpec.BooleanValue BUILD_ALT_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_SLABS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_TALLS;
    /** v1.5.275：横两格（床）替代品表 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_WIDES;
    /** v1.5.275：无碰撞方块替代品表 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_NOCLIPS;
    /** v1.5.102：以下把模组其余硬编码数值全部纳入面板（用户要求"所有数值都可调"） */
    public static final ForgeConfigSpec.IntValue BUILD_STALL_INTERVAL;
    public static final ForgeConfigSpec.IntValue BUILD_LOOKAHEAD;
    public static final ForgeConfigSpec.IntValue BUILD_DEFERRED_SCAN_CAP;
    public static final ForgeConfigSpec.IntValue BUILD_STRUCTURE_MAX_VOLUME;

    // ================= 挖矿 =================
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MINE_ORE_VALUES;
    /** v1.5.101b：额外可挖穿方块（障碍物名单，path 名如 oak_log；面板挖矿-障碍物管理） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MINE_BREAKABLES;
    /** v1.0.4：已取消挖穿的内置障碍物（排除名单，path 名如 stone；默认空=全开） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MINE_DISABLED_BREAKABLES;
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
    // v1.0.4：透视感知开关（默认关——关闭后仅发现视线无阻的矿物，见 hasClearSight）
    public static final ForgeConfigSpec.BooleanValue MINE_SEEK_THROUGH_WALLS;
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

    // ================= 伐木（v1.1.0，克隆挖矿；障碍物两名单与挖矿共享） =================
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WOOD_VALUES;
    /** v1.1.0：自动识别带原版 logs 标签的模组原木（默认开） */
    public static final ForgeConfigSpec.BooleanValue WOOD_TAG_AUTO;
    public static final ForgeConfigSpec.IntValue WOOD_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue WOOD_DOWN_RANGE;
    public static final ForgeConfigSpec.IntValue WOOD_UP_RANGE;
    public static final ForgeConfigSpec.IntValue WOOD_BREAK_BUDGET;
    public static final ForgeConfigSpec.DoubleValue WOOD_VALUE_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue WOOD_DEPTH_PENALTY;
    public static final ForgeConfigSpec.DoubleValue WOOD_SPEED_FACTOR;
    public static final ForgeConfigSpec.DoubleValue WOOD_MOVE_SPEED;
    public static final ForgeConfigSpec.IntValue WOOD_JUNK_KEEP;
    public static final ForgeConfigSpec.IntValue WOOD_PLACED_LIFETIME;
    public static final ForgeConfigSpec.BooleanValue WOOD_SOFT_NO_DURABILITY;
    public static final ForgeConfigSpec.BooleanValue WOOD_PILLAR_GUARD;
    public static final ForgeConfigSpec.BooleanValue WOOD_HARD_BLOCK_REPORT;
    public static final ForgeConfigSpec.IntValue WOOD_CREATIVE_DEFAULT_VALUE;
    public static final ForgeConfigSpec.BooleanValue WOOD_SEEK_THROUGH_WALLS;
    public static final ForgeConfigSpec.IntValue WOOD_ANCHOR_TIMEOUT;
    public static final ForgeConfigSpec.IntValue WOOD_RELOCATE_THROTTLE;
    public static final ForgeConfigSpec.IntValue WOOD_TARGET_TIMEOUT;
    public static final ForgeConfigSpec.DoubleValue WOOD_REACH;
    public static final ForgeConfigSpec.IntValue WOOD_PILLAR_COOLDOWN;
    public static final ForgeConfigSpec.IntValue WOOD_JUNK_CHECK_INTERVAL;
    public static final ForgeConfigSpec.IntValue WOOD_SKIP_REPORT_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue WOOD_CHAIN_MINING;
    public static final ForgeConfigSpec.BooleanValue WOOD_AUTO_COLLECT;
    public static final ForgeConfigSpec.IntValue WOOD_CHAIN_LIMIT;
public static final ForgeConfigSpec.BooleanValue WOOD_LEAVES_CLEAR;

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
    // v1.5.190：记忆防抖写盘（主动会话记忆主题注入已废弃，见 v1.0.4）
    public static final ForgeConfigSpec.BooleanValue MEMORY_LAZY_SAVE;
    // v1.5.191：记忆维护周期（定期固化/衰减/关系置信度衰减/error_mark 传播）
    public static final ForgeConfigSpec.IntValue MEMORY_MAINTENANCE_MIN;
    public static final ForgeConfigSpec.IntValue MEMORY_RELATION_DECAY_DAYS;
    // v1.5.198：记忆独立 API（留空 = 跟随 TLM 女仆当前 LLM 站点配置）
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> MEMORY_API_MODEL;
    /** 多级记忆索引（日/3日/周/月日记式摘要，移植自 Sphantosis MemoryArchiver） */
    public static final ForgeConfigSpec.BooleanValue MEMORY_INDEX_ENABLE;
    /** 睡一觉自动处理：玩家睡醒后强制归档当日记忆（生成日级日记索引 + 短期→长期转移） */
    public static final ForgeConfigSpec.BooleanValue MEMORY_INDEX_ON_SLEEP;
    /** 会话收尾归档：玩家登出（真人睡觉/结束一天）时收尾当日记忆，下次进游戏补完成 */
    public static final ForgeConfigSpec.BooleanValue MEMORY_INDEX_ON_LOGOUT;
    /** 月级索引按重要度保留的最大事件数 */
    public static final ForgeConfigSpec.IntValue MEMORY_INDEX_MONTH_TOP_N;
    /** 单次索引喂给 LLM 的事件数上限（超出按重要度裁剪——上下文长度管理） */
    public static final ForgeConfigSpec.IntValue MEMORY_INDEX_MAX_EVENTS;
    /** 短期→长期转移阈值（游戏日）：关联簇内全部段落超过该年龄才整簇转移 */
    public static final ForgeConfigSpec.IntValue MEMORY_SHORT_TERM_DAYS;
    // v1.1.0：记忆升级（借鉴 maidsoulcore）——情绪快照 / 人格种子 / 每日关心点 / 双 agent 提取
    public static final ForgeConfigSpec.BooleanValue MEMORY_AFFECT_SNAPSHOT;
    public static final ForgeConfigSpec.BooleanValue MEMORY_PERSONA;
    public static final ForgeConfigSpec.BooleanValue MEMORY_CARE_POINTS;
    public static final ForgeConfigSpec.BooleanValue MEMORY_DUAL_AGENT;
    // v1.2.0：heartfelt 纪念日联动（软感知，未装 heartfelt 时 tag 永不出现）
    public static final ForgeConfigSpec.BooleanValue MEMORY_HEARTFELT_ANNIVERSARY;
    // v1.2.1：人设统一（TLM 已有人设时人格块降级为补充）
    public static final ForgeConfigSpec.BooleanValue MEMORY_PERSONA_UNIFY;

    // ================= 对话与提示 =================
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_STATUS_REPORTER;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_INTERVAL;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_PROACTIVE;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_COOLDOWN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_DAILY;
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
    // v1.5.287：查看主人物品栏工具（只读查询主人背包）
    public static final ForgeConfigSpec.BooleanValue TOOL_OWNER_INVENTORY;

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
    // v1.1.0：主动切换战斗模式（主人受攻击 → 附近女仆立即切战斗，威胁消失还原）
    public static final ForgeConfigSpec.BooleanValue COMBAT_AUTO_SWITCH;
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMBAT_AUTO_SWITCH_VANILLA_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue COMBAT_AUTO_SWITCH_MOD_WEIGHT;
    // v1.1.0 实测五十八：近战/远程偏好权重（两者皆可用时选池倾向 + 战中换战术开关量）
    public static final ForgeConfigSpec.IntValue COMBAT_PREF_MELEE_WEIGHT;
    public static final ForgeConfigSpec.IntValue COMBAT_PREF_RANGED_WEIGHT;
    // v1.1.0 实测六十一（借鉴 TLM-Sincerely 防抖三件套）：战中换战术最短持有/反向窗口/反向冷却
    public static final ForgeConfigSpec.IntValue COMBAT_TACTIC_HOLD_TICKS;
    public static final ForgeConfigSpec.IntValue COMBAT_REVERSE_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue COMBAT_REVERSE_COOLDOWN_TICKS;
    // v1.1.0 实测六十七：空手（无任何攻击物品）不参战
    public static final ForgeConfigSpec.BooleanValue COMBAT_UNARMED_SKIP;
    // v1.1.0 实测六十一：战斗还原后排班宽限（防威胁闪烁导致的反复切换）
    public static final ForgeConfigSpec.IntValue MISC_SCHEDULE_RESTORE_GRACE;
    // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：伐木/挖矿全量扫描每 tick 预算
    public static final ForgeConfigSpec.IntValue WOOD_SCAN_BUDGET;
    public static final ForgeConfigSpec.IntValue MINE_SCAN_BUDGET;
    /** v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态 */
    public static final ForgeConfigSpec.BooleanValue WOOD_STUCK_WATCHDOG;
    public static final ForgeConfigSpec.IntValue WOOD_STUCK_RESET_SECONDS;
    public static final ForgeConfigSpec.BooleanValue MINE_STUCK_WATCHDOG;
    public static final ForgeConfigSpec.IntValue MINE_STUCK_RESET_SECONDS;
    /** v1.1.0 实测七十三：默认可挖矿表（原版全家桶，价值统一 300）——抽成常量供
     *  配置迁移复用（旧档的空表/缺铜表在加载时按此补齐；见 ProMaidMod.onConfigLoad）。
     *  必须声明在 static{} 块之前（块内的 defineList 要引用它） */
    public static final java.util.List<String> DEFAULT_ORE_VALUES = java.util.List.of(
            "minecraft:gold_ore=300", "minecraft:deepslate_gold_ore=300",
            "minecraft:coal_ore=300", "minecraft:deepslate_coal_ore=300",
            "minecraft:iron_ore=300", "minecraft:deepslate_iron_ore=300",
            "minecraft:copper_ore=300", "minecraft:deepslate_copper_ore=300",
            "minecraft:diamond_ore=300", "minecraft:deepslate_diamond_ore=300",
            "minecraft:lapis_ore=300", "minecraft:deepslate_lapis_ore=300",
            "minecraft:emerald_ore=300", "minecraft:deepslate_emerald_ore=300",
            "minecraft:redstone_ore=300", "minecraft:deepslate_redstone_ore=300",
            "minecraft:nether_gold_ore=300", "minecraft:nether_quartz_ore=300",
            "minecraft:ancient_debris=300");
    public static final ForgeConfigSpec.BooleanValue COMBAT_AUTO_SWITCH_RESTORE;
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_RESTORE_DELAY;
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST;
    /** v1.1.0 实测八十四：战斗僵局逃逸——威胁够不着时不再无限续杯安全计时 */
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_STALE;
    /** v1.1.0 实测八十五：动态威胁圈——最近伤害来源在扩展窗口内则圈自动放大包含它 */
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_EXPAND;

    // ================= 搭路（v1.1.0，主人在上方时垫方块靠近，默认关） =================
    public static final ForgeConfigSpec.BooleanValue BRIDGE_ENABLED;
public static final ForgeConfigSpec.IntValue BRIDGE_MAX_DIST;
public static final ForgeConfigSpec.IntValue BRIDGE_AIR_MAX_DIST;
public static final ForgeConfigSpec.IntValue BRIDGE_MIN_DY;
    public static final ForgeConfigSpec.IntValue BRIDGE_THREAT_DIST;
    public static final ForgeConfigSpec.IntValue BRIDGE_STEP_COOLDOWN;
    public static final ForgeConfigSpec.IntValue BRIDGE_PLACED_LIFETIME;
public static final ForgeConfigSpec.BooleanValue BRIDGE_RECLAIM_TO_MAID;
/** v1.1.0 实测十七：战斗搭方块（自保搭高/翻墙/搭桥/封头盖帽）清理时间（秒，默认 60） */
public static final ForgeConfigSpec.IntValue COMBAT_PLACED_LIFETIME;
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
    /** v1.1.0：落地雪（细雪桶版落地水——下界也能用） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_SNOW_CLUTCH;
    // v1.5.134：单兵作战战术（替代已删除的 v1.5.132 战斗协同——PVP 式走位/拉扯/时机格挡）
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_MELEE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_RANGED;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_SHIELD;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_ORBIT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_KITE_RANGE;
    // v1.5.280：近战贴脸后退（被敌人贴进 2 格内主动后退拉开，女仆手长 3 格仍能挥砍）
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_MELEE_KITE;
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
    // v1.1.0 实测六十二：女仆着火不传主人
    public static final ForgeConfigSpec.BooleanValue MAID_FIRE_GUARD;
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
     public static final ForgeConfigSpec.BooleanValue MISC_MAID_CHUNK_LOAD;
    /** v1.1.0 实测七十九：受困救援（下界基岩顶/虚空自动传回主人身边） */
    public static final ForgeConfigSpec.BooleanValue MISC_MAID_RESCUE;
    // v1.5.161：农场连锁收获 / 收获物自动收集（默认关闭）
    public static final ForgeConfigSpec.BooleanValue MISC_CHAIN_HARVEST;
    public static final ForgeConfigSpec.BooleanValue MISC_AUTO_COLLECT;
    // v1.5.163：农场连锁收获数量上限
    public static final ForgeConfigSpec.IntValue MISC_CHAIN_HARVEST_LIMIT;
    // v1.5.236：农场批量种植 / 上限（与连锁收获同格式）
    public static final ForgeConfigSpec.BooleanValue MISC_BATCH_PLANT;
    public static final ForgeConfigSpec.IntValue MISC_BATCH_PLANT_LIMIT;
    // v1.1.0：排班表系统全局开关（关闭后排班调度器停摆——已保存的日程保留，重开恢复）
    public static final ForgeConfigSpec.BooleanValue MISC_SCHEDULE_ENABLED;
    // v1.5.199：爱憎分明饥饿/撑死测试开关（默认 true = 禁用其饥饿系统）
    public static final ForgeConfigSpec.BooleanValue MISC_LOVELOATHE_DISABLE_HUNGER;
    // v1.5.310：爱憎分明（Love Loathe, modId=callresponse）软联动开关组——未装爱憎分明不受影响
    public static final ForgeConfigSpec.BooleanValue MISC_LOVELOATHE_MASTER;
    public static final ForgeConfigSpec.BooleanValue MISC_LOVELOATHE_EXTREME_HUNGER;
    public static final ForgeConfigSpec.BooleanValue MISC_LOVELOATHE_EMOTION;

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
                .define("speedTier", "x1",
                        o -> o instanceof String s && (s.equals("x1") || s.equals("x1.5") || s.equals("x3")));
        BUILD_TURBO = BUILDER.comment("极速模式（吃满服务器上限，性能风险）")
                .translation("config.promaid.build.turbo").define("turbo", false);
        BUILD_GLOBAL_QUOTA = BUILDER.comment("全局放置配额（每秒方块数上限，性能敏感）")
                .translation("config.promaid.build.globalQuota")
                .defineInRange("globalQuota", 350, 50, 1500);
        BUILD_MAX_FORCE_CHUNKS = BUILDER.comment("建造区强制加载区块上限")
                .translation("config.promaid.build.maxForceChunks")
                .defineInRange("maxForceChunks", 1024, 64, 8192);
        BUILD_MAX_BLOCKS = BUILDER.comment("LLM 蓝图最大方块数（v1.5.222：上限放开到 50 万——构建链统一支持 50 万块级建筑）")
                .translation("config.promaid.build.maxBlocks").defineInRange("maxBlocks", 200000, 16, 500000);
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
        // v1.5.316：红石机器改革开关——机器专属搭建顺序 + 活建造（去禁锢）
        BUILD_MACHINE_SMART = BUILDER.comment("红石机器专属搭建（v1.5.316 改革）：机器按红石拓扑分层放置（结构→惰性机构→活动件→传感→动力源→TNT，动力源最后落位）+ 活建造（红石/水流随放随算），机器建好即自然运行；轰炸机类完工自动放矿车启动。关 = 回退旧行为（常规顺序+静默放置+完工唤醒）")
                .translation("config.promaid.build.machineSmart").define("machineSmart", true);
        // v1.5.331：TNT 点火保护期（秒）——建造期/完工激活期/宽限期内压制一切 TNT
        // 点火（放置/活塞推动/邻居更新），防"刚建好炸膛"（天机屠龙炮：观察者→活塞
        // 推 TNT 链在完工瞬间触发）；完工点火结算只点燃邻接带电的 TNT（轰炸机当场
        // 启动），期满后机器按正常红石逻辑点火。0 = 关闭保护（回到 1.5.328 行为）
        BUILD_TNT_IGNITION_GRACE = BUILDER.comment("TNT 点火保护期（秒，默认 120）：建造期+完工激活期+宽限期内压制一切 TNT 点火（放置/活塞推动/邻居更新），防机器'刚建好炸膛'（天机屠龙炮等观察者→活塞推 TNT 的机器）；完工点火结算只点燃邻接带电的 TNT（轰炸机当场启动），期满后机器按正常红石逻辑点火。0 = 关闭保护")
                .translation("config.promaid.build.tntIgnitionGrace").defineInRange("tntIgnitionGrace", 120, 0, 3600);
        // v1.1.0 实测八十二：蓝图投影——只有区块框不好确认建筑朝向/形状
        BUILD_PROJECTION = BUILDER.comment("蓝图投影预览：「区块显示」与建造中区块叠加半透明幽灵方块轮廓（外壳抽稀采样，确认建筑朝向/形状）；关闭则只显示区块框")
                .translation("config.promaid.build.projection").define("projection", true);
        // v1.5.254：缺料自动替代（先同族后自定义；按高度分类的三张自定义表）
        BUILD_ALT_ENABLED = BUILDER.comment("缺料自动替代开关：目标方块没有时，先找同族（木板/原木/石砖等等价族），再按高度分类（半格/一格/两格）用自定义替代表")
                .translation("config.promaid.build.altEnabled").define("altEnabled", true);
        BUILD_ALT_SLABS = BUILDER.comment("半格高替代品（台阶类方块缺料时按序使用，填完整注册名如 minecraft:oak_slab）")
                .translation("config.promaid.build.altSlabs")
                .defineList("altSlabs", List.of("minecraft:oak_slab"), o -> o instanceof String s && !s.isEmpty());
        BUILD_ALT_BLOCKS = BUILDER.comment("一格高替代品（整方块缺料时按序使用，填完整注册名如 minecraft:stone_bricks）")
                .translation("config.promaid.build.altBlocks")
                .defineList("altBlocks", List.of("minecraft:oak_planks"), o -> o instanceof String s && !s.isEmpty());
        BUILD_ALT_TALLS = BUILDER.comment("两格高替代品（门/双植物等缺料时按序使用，填完整注册名如 minecraft:oak_door）")
                .translation("config.promaid.build.altTalls")
                .defineList("altTalls", List.of("minecraft:oak_door"), o -> o instanceof String s && !s.isEmpty());
        // v1.5.275：两格再分竖/横 + 无碰撞方块单独表（用户："横着高的两格和竖着的两格不一样；无碰撞方块单独画一个区"）
        BUILD_ALT_WIDES = BUILDER.comment("横两格替代品（床等宽 2 格方块缺料时按序使用，填完整注册名如 minecraft:red_bed）")
                .translation("config.promaid.build.altWides")
                .defineList("altWides", List.of("minecraft:white_bed"), o -> o instanceof String s && !s.isEmpty());
        BUILD_ALT_NOCLIPS = BUILDER.comment("无碰撞替代品（花/火把/地毯等无碰撞箱方块缺料时按序使用，填完整注册名如 minecraft:oak_sapling）")
                .translation("config.promaid.build.altNoClips")
                .defineList("altNoClips", List.of("minecraft:torch"), o -> o instanceof String s && !s.isEmpty());
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
                .defineList("oreValues", DEFAULT_ORE_VALUES,
                        o -> o instanceof String s && s.contains("="));
        MINE_BREAKABLES = BUILDER.comment("额外可挖穿方块（障碍物名单，path 名如 oak_log——女仆挖矿遇到会挖穿而非当硬挡路报点弃置）")
                .translation("config.promaid.mine.breakables")
                .defineList("extraBreakables", List.of(),
                        o -> o instanceof String s && !s.isEmpty());
        MINE_DISABLED_BREAKABLES = BUILDER.comment("已取消挖穿的障碍物（排除名单，path 名如 spruce_log）：v1.0.4 起内置自然软方块（原木/菌柄/竹/蘑菇/南瓜/西瓜/冰/珊瑚等）默认在此名单 → 女仆默认不挖穿树木植被，被其挡住的矿报点弃置而非硬挖；在面板「障碍物」页勾选它们可恢复挖穿。石头/泥土/沙等矿洞常见方块不在此列，默认可挖穿")
                .translation("config.promaid.mine.disabledBreakables")
                .defineList("disabledBreakables", List.of(
                        "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                        "dark_oak_log", "mangrove_log", "cherry_log",
                        "crimson_stem", "warped_stem", "bamboo_block",
                        "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log",
                        "stripped_jungle_log", "stripped_acacia_log", "stripped_dark_oak_log",
                        "stripped_mangrove_log", "stripped_cherry_log",
                        "stripped_crimson_stem", "stripped_warped_stem",
                        "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
                        "pumpkin", "melon", "ice", "packed_ice",
                        "tube_coral_block", "brain_coral_block", "bubble_coral_block",
                        "fire_coral_block", "horn_coral_block",
                        "dead_tube_coral_block", "dead_brain_coral_block",
                        "dead_bubble_coral_block", "dead_fire_coral_block", "dead_horn_coral_block"),
                        o -> o instanceof String s && !s.isEmpty());
        MINE_SEARCH_RADIUS = BUILDER.comment("矿物检索半径（水平）")
                .translation("config.promaid.mine.searchRadius").defineInRange("searchRadius", 24, 8, 64);
        MINE_DOWN_RANGE = BUILDER.comment("垂直向下搜索范围")
                .translation("config.promaid.mine.downRange").defineInRange("downRange", 12, 4, 48);
        MINE_UP_RANGE = BUILDER.comment("垂直向上搜索范围")
                .translation("config.promaid.mine.upRange").defineInRange("upRange", 24, 4, 64);
        // v1.1.0 实测七十二（用户反馈："矿洞里一直往下打洞"）：预算重新计入实心
        // 可开路方块（石头/泥土），22 的旧默认等于无限穿墙选矿 → 默认降为 6；
        // 旧档里存的 22 由 ProMaidMod 的配置迁移自动改成 6
        MINE_BREAK_BUDGET = BUILDER.comment("穿透预算（默认 6）：选矿时统计女仆到矿之间要穿过多少层实心方块（含石头/泥土等可开路的），超过预算的矿不选——走近了会重新评估；调大=更爱穿墙打隧道（旧版行为约等于无限），调小=只挑眼前暴露的矿")
                .translation("config.promaid.mine.breakBudget").defineInRange("breakBudget", 6, 0, 64);
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
        // v1.1.0 实测二十七：默认开启——软方块（徒手可挖）不磨损镐，与伐木一致。
        // v1.5.138 曾改 false（用户反馈"挖矿不消耗耐久"），实测二十七按新需求改回。
        MINE_SOFT_NO_DURABILITY = BUILDER.comment("软方块（徒手可挖）开路不消耗镐耐久（默认开——与伐木一致）")
                .translation("config.promaid.mine.softNoDurability").define("softNoDurability", true);
        MINE_PILLAR_GUARD = BUILDER.comment("搭方块防掉落（潜行效果，速度不变）")
                .translation("config.promaid.mine.pillarGuard").define("pillarGuard", true);
        MINE_HARD_BLOCK_REPORT = BUILDER.comment("硬挡路（箱子/机器等）报点弃置该矿")
                .translation("config.promaid.mine.hardBlockReport").define("hardBlockReport", true);
        MINE_CREATIVE_DEFAULT_VALUE = BUILDER.comment("创造面板添加矿物的默认价值")
                .translation("config.promaid.mine.creativeDefaultValue")
                .defineInRange("creativeDefaultValue", 300, 10, 1000);
        MINE_SEEK_THROUGH_WALLS = BUILDER.comment("透视感知（隔墙找矿，默认关）：开启后女仆能发现视线被方块挡住的矿物并挖通开路（等同旧版逻辑）；关闭后女仆像玩家一样只能发现视线无阻的矿物——除水/岩浆外任何方块（泥土/石头/玻璃/半砖等）都挡视线，被挡的矿不可见、不报点，也不会隔墙挖穿；已经看得见的矿，身前有可挖障碍物照常挖穿开路")
                .translation("config.promaid.mine.seekThroughWalls").define("seekThroughWalls", false);
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
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        MINE_SCAN_BUDGET = BUILDER.comment("挖矿扫描预算（格/tick，默认 4096）：全量扫描矿框改为分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前女仆短暂无目标）；调小更不卡服但找矿变慢，调大找矿快但单 tick 尖峰高")
                .translation("config.promaid.mine.scanBudget").defineInRange("scanBudget", 4096, 256, 65536);
        MINE_SKIP_REPORT_INTERVAL = BUILDER.comment("跳过矿/捡不到掉落播报间隔（tick，防刷屏）")
                .translation("config.promaid.mine.skipReportInterval")
                .defineInRange("skipReportInterval", 600, 100, 2400);
        // v1.5.161：进阶挖矿——连锁采集 / 自动收集（默认关闭，借鉴 FTB Ultimine 连锁破坏思路）
        MINE_CHAIN_MINING = BUILDER.comment("连锁采集（挖矿时自动连锁挖掘相连的同族矿石——矿脉一次挖完；v1.5.189 默认开启）")
                .translation("config.promaid.mine.chainMining").define("chainMining", false);
        MINE_AUTO_COLLECT = BUILDER.comment("自动收集（挖掘掉落物直接进女仆背包，不进世界；背包放不下才落地）")
                .translation("config.promaid.mine.autoCollect").define("autoCollect", false);
        // v1.5.163：连锁采集数量上限可自定义
        MINE_CHAIN_LIMIT = BUILDER.comment("连锁采集上限（块）：一次连锁挖掘的最大方块数（默认 16）")
                .translation("config.promaid.mine.chainLimit").defineInRange("chainLimit", 16, 4, 64);
        // v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态
        MINE_STUCK_WATCHDOG = BUILDER.comment("发呆看门狗（默认开）：挖矿期间连续 N 秒既没挖掉任何方块、位置也没挪动（原地发呆/内部状态卡死）时，自动整体重置该女仆的挖矿状态——锚点/扫描缓存/排除表/目标全部清空重新开始，等效收回魂符再放下去，不用玩家手动救；走路赶路、垫方块搭路都算进展，不会误触发")
                .translation("config.promaid.mine.stuckWatchdog").define("stuckWatchdog", true);
        MINE_STUCK_RESET_SECONDS = BUILDER.comment("看门狗判定时长（秒，默认 8，用户实测发呆出现很快）：连续这么久既没挖掉/垫过方块、也没挪动就整体重置状态。重置不会打断「够不着目标」的超时弃置流程（等待时钟跨重置保留）")
                .translation("config.promaid.mine.stuckResetSeconds").defineInRange("stuckResetSeconds", 8, 4, 300);
        BUILDER.pop();

        // ---- 伐木（v1.1.0：克隆挖矿架构；障碍物名单与挖矿共享 extraBreakables/disabledBreakables） ----
        BUILDER.comment("伐木设置").translation("config.promaid.wood").push("wood");
        WOOD_VALUES = BUILDER.comment("可砍伐木材表：每项 方块注册名=价值，如 minecraft:oak_log=300；带原版 logs 标签的模组原木默认自动识别（见 tagAuto，无需加入）；未打标签的模组木材在此加入（创造面板已按木质 tag 过滤显示）")
                .translation("config.promaid.wood.values")
                .defineList("woodValues", List.of(
                                "minecraft:oak_log=300", "minecraft:spruce_log=300", "minecraft:birch_log=300",
                                "minecraft:jungle_log=300", "minecraft:acacia_log=300", "minecraft:dark_oak_log=300",
                                "minecraft:mangrove_log=300", "minecraft:cherry_log=300",
                                "minecraft:crimson_stem=300", "minecraft:warped_stem=300",
                                "minecraft:bamboo_block=300",
                                "minecraft:stripped_oak_log=300", "minecraft:stripped_spruce_log=300",
                                "minecraft:stripped_birch_log=300", "minecraft:stripped_jungle_log=300",
                                "minecraft:stripped_acacia_log=300", "minecraft:stripped_dark_oak_log=300",
                                "minecraft:stripped_mangrove_log=300", "minecraft:stripped_cherry_log=300",
                                "minecraft:stripped_crimson_stem=300", "minecraft:stripped_warped_stem=300"),
                        o -> o instanceof String s && s.contains("="));
        WOOD_TAG_AUTO = BUILDER.comment("自动识别模组原木（默认开）：凡带原版 #minecraft:logs / #minecraft:bamboo_blocks 标签的方块（模组原木）都自动视为可砍木材（价值 300，无需进名单）；关闭则只认名单")
                .translation("config.promaid.wood.tagAuto").define("tagAuto", true);
        WOOD_SEARCH_RADIUS = BUILDER.comment("木材检索半径（水平）")
                .translation("config.promaid.wood.searchRadius").defineInRange("searchRadius", 24, 8, 64);
        WOOD_DOWN_RANGE = BUILDER.comment("垂直向下搜索范围（格）——树在地表，默认只往下看 4 格")
                .translation("config.promaid.wood.downRange").defineInRange("downRange", 4, 1, 32);
        WOOD_UP_RANGE = BUILDER.comment("垂直向上搜索范围（格）——树冠/巨型蘑菇很高，默认 24")
                .translation("config.promaid.wood.upRange").defineInRange("upRange", 24, 4, 64);
        WOOD_BREAK_BUDGET = BUILDER.comment("穿透预算（允许挖开多少层不可开路挡路方块）——与挖矿共享障碍物名单")
                .translation("config.promaid.wood.breakBudget").defineInRange("breakBudget", 22, 0, 64);
        WOOD_VALUE_WEIGHT = BUILDER.comment("价值权重：木材价值对选材的加成")
                .translation("config.promaid.wood.valueWeight").defineInRange("valueWeight", 2.0, 0.5, 5.0);
        WOOD_DEPTH_PENALTY = BUILDER.comment("深度惩罚（每格扣分）——树在地表，默认 0（不偏好浅层）")
                .translation("config.promaid.wood.depthPenalty").defineInRange("depthPenalty", 0.0, 0.0, 10.0);
        WOOD_SPEED_FACTOR = BUILDER.comment("砍伐速度系数（1.0=玩家速度，1.2=快20%）")
                .translation("config.promaid.wood.speedFactor").defineInRange("speedFactor", 1.2, 0.5, 3.0);
        WOOD_MOVE_SPEED = BUILDER.comment("接近木材速度倍率（v1.1.0 实测四十八：0.6→0.3——用户反馈伐木移速至少快一倍，观感像狂奔；0.3 = 挖矿同款基础的一半，悠闲走向下一棵树）")
                .translation("config.promaid.wood.moveSpeed").defineInRange("moveSpeed", 0.3, 0.2, 1.5);
        WOOD_JUNK_KEEP = BUILDER.comment("废石保留量——砍树途中挖穿泥土/石头产生的废石每种保留几组")
                .translation("config.promaid.wood.junkKeep").defineInRange("junkKeep", 32, 4, 128);
        WOOD_PLACED_LIFETIME = BUILDER.comment("搭方块清理时间（秒）")
                .translation("config.promaid.wood.placedLifetime").defineInRange("placedLifetime", 10, 3, 60);
        WOOD_SOFT_NO_DURABILITY = BUILDER.comment("软方块（徒手可挖）开路不消耗斧耐久")
                .translation("config.promaid.wood.softNoDurability").define("softNoDurability", true);
        WOOD_PILLAR_GUARD = BUILDER.comment("搭方块防掉落（潜行效果，速度不变）")
                .translation("config.promaid.wood.pillarGuard").define("pillarGuard", true);
        WOOD_HARD_BLOCK_REPORT = BUILDER.comment("硬挡路（箱子/机器等）报点弃置该木材")
                .translation("config.promaid.wood.hardBlockReport").define("hardBlockReport", true);
        WOOD_CREATIVE_DEFAULT_VALUE = BUILDER.comment("创造面板默认价值：木材页锁定方块后，输入框留空直接点「添加」时用的分数")
                .translation("config.promaid.wood.creativeDefaultValue").defineInRange("creativeDefaultValue", 300, 10, 1000);
        // v1.1.0 实测四十一（用户："隔墙找木材视线感知默认打开——增加容错率"）：
        // 树木天然被树冠/地形遮挡，关着容错率太低（玩家反感"找不到树"）
        WOOD_SEEK_THROUGH_WALLS = BUILDER.comment("透视感知（隔墙找木材，默认开）——开启后女仆能发现视线被方块挡住的木材并挖通开路；关闭则像玩家一样只发现视线无阻的木材（树叶不挡视线）")
                .translation("config.promaid.wood.seekThroughWalls").define("seekThroughWalls", true);
        WOOD_ANCHOR_TIMEOUT = BUILDER.comment("锚点出框超时（tick）")
                .translation("config.promaid.wood.anchorTimeout").defineInRange("anchorTimeout", 200, 40, 1200);
        WOOD_RELOCATE_THROTTLE = BUILDER.comment("重定位节流（tick，防边界抖动）")
                .translation("config.promaid.wood.relocateThrottle").defineInRange("relocateThrottle", 20, 4, 200);
        WOOD_TARGET_TIMEOUT = BUILDER.comment("目标超时（tick，够不到木材超时放弃）")
                .translation("config.promaid.wood.targetTimeout").defineInRange("targetTimeout", 300, 60, 1200);
        WOOD_REACH = BUILDER.comment("砍伐距离（格）")
                .translation("config.promaid.wood.reach").defineInRange("reach", 4.5, 2.0, 8.0);
        WOOD_PILLAR_COOLDOWN = BUILDER.comment("搭方块冷却（tick，垫脚下/搭路节奏）")
                .translation("config.promaid.wood.pillarCooldown")
                // v1.1.0 实测五十四：4→2——垫块间隙的"停一下再挖"顿挫感减半（冷却期
                // 女仆原地站桩等下一步，4 tick 的停顿在连续垫高砍树时肉眼可见）
                .defineInRange("pillarCooldown", 2, 1, 20);
        WOOD_JUNK_CHECK_INTERVAL = BUILDER.comment("废石清理检查间隔（tick）")
                .translation("config.promaid.wood.junkCheckInterval").defineInRange("junkCheckInterval", 100, 20, 400);
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        WOOD_SCAN_BUDGET = BUILDER.comment("伐木扫描预算（格/tick，默认 4096）：全量扫描木材框改为分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前女仆短暂无目标）；调小更不卡服但找树变慢，调大找树快但单 tick 尖峰高")
                .translation("config.promaid.wood.scanBudget").defineInRange("scanBudget", 4096, 256, 65536);
        WOOD_SKIP_REPORT_INTERVAL = BUILDER.comment("跳过木材/被挡住播报间隔（tick，防刷屏）")
                .translation("config.promaid.wood.skipReportInterval").defineInRange("skipReportInterval", 600, 100, 2400);
        WOOD_CHAIN_MINING = BUILDER.comment("连锁砍伐（同一棵树的相连木材一次砍完——树干天然相连，默认开启）")
                .translation("config.promaid.wood.chainMining").define("chainMining", true);
        WOOD_AUTO_COLLECT = BUILDER.comment("自动收集（砍伐掉落物直接进女仆背包，不进世界）")
                .translation("config.promaid.wood.autoCollect").define("autoCollect", false);
        WOOD_CHAIN_LIMIT = BUILDER.comment("连锁砍伐上限（块）：一次连锁砍伐的最大方块数")
                .translation("config.promaid.wood.chainLimit").defineInRange("chainLimit", 16, 4, 64);
        WOOD_LEAVES_CLEAR = BUILDER.comment("树冠清理（默认开）：树干连锁砍完后，顺手把上方树冠的树叶也清掉（树叶 BFS 清到半径 3 格，掉落物/树苗直接进背包——树叶不清会挂着挡视线还慢慢掉东西；关闭则只砍树干、树叶靠自然衰减）")
                .translation("config.promaid.wood.leavesClear").define("leavesClear", true);
        // v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态
        WOOD_STUCK_WATCHDOG = BUILDER.comment("发呆看门狗（默认开）：伐木期间连续 N 秒既没砍掉任何方块、位置也没挪动（典型如站进挖掉的树洞里对着头顶树干发呆）时，自动整体重置该女仆的伐木状态——锚点/扫描缓存/排除表/目标全部清空重新找树，等效收回魂符再放下去，不用玩家手动救；走路赶路、垫方块搭高都算进展，不会误触发")
                .translation("config.promaid.wood.stuckWatchdog").define("stuckWatchdog", true);
        WOOD_STUCK_RESET_SECONDS = BUILDER.comment("看门狗判定时长（秒，默认 8，用户实测发呆出现很快）：连续这么久既没砍掉/垫过方块、也没挪动就整体重置状态。重置不会打断「够不着目标」的超时弃置流程（等待时钟跨重置保留）")
                .translation("config.promaid.wood.stuckResetSeconds").defineInRange("stuckResetSeconds", 8, 4, 300);
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
        // 多级记忆索引（移植自 Sphantosis MemoryArchiver / memory_index_db）：
        // 跨日/周/月边界与玩家睡醒时自动生成日/3日/周/月四级日记式摘要索引，
        // 永久归档供对话检索（query_memory_index 工具 + 召回路 + 投影注入）
        MEMORY_INDEX_ENABLE = BUILDER.comment("多级记忆索引（日/3日/周/月日记式摘要，跨边界与睡醒自动生成，移植自 Sphantosis）")
                .translation("config.promaid.memory.indexEnable").define("indexEnable", true);
        MEMORY_INDEX_ON_SLEEP = BUILDER.comment("睡一觉自动处理（玩家睡醒后生成当日记忆日记 + 短期记忆整簇转长期）")
                .translation("config.promaid.memory.indexOnSleep").define("indexOnSleep", true);
        MEMORY_INDEX_ON_LOGOUT = BUILDER.comment("会话收尾归档（玩家登出=真人结束一天，收尾当日记忆；单人关服竞态由下次进游戏自动补完成）")
                .translation("config.promaid.memory.indexOnLogout").define("indexOnLogout", true);
        MEMORY_INDEX_MONTH_TOP_N = BUILDER.comment("月级索引保留事件数（按重要度排序保留的最多事件数）")
                .translation("config.promaid.memory.indexMonthTopN")
                .defineInRange("indexMonthTopN", 20, 5, 100);
        MEMORY_INDEX_MAX_EVENTS = BUILDER.comment("单次索引事件上限（跨度内事件过多时按重要度裁剪再生成日记——控制摘要上下文长度）")
                .translation("config.promaid.memory.indexMaxEvents")
                .defineInRange("indexMaxEvents", 40, 10, 200);
        MEMORY_SHORT_TERM_DAYS = BUILDER.comment("短期→长期转移阈值（游戏日，关联簇全部段落超过该年龄才整簇转移）")
                .translation("config.promaid.memory.shortTermDays")
                .defineInRange("shortTermDays", 3, 1, 30);
        // v1.1.0：记忆升级（借鉴 maidsoulcore AffectEngine/CharacterPackage/DailyMemoryConsolidator）
        MEMORY_AFFECT_SNAPSHOT = BUILDER.comment("情绪快照写入记忆（每条新记忆附带当时 PAD 情绪，供回看/分析；旧记忆不受影响）")
                .translation("config.promaid.memory.affectSnapshot").define("affectSnapshot", true);
        MEMORY_PERSONA = BUILDER.comment("人格种子注入（每女仆 persona.properties + traits.properties + core_memories.jsonl 只读投影——人设与聊天记忆分离，聊天不改写人格；首次自动生成默认模板）")
                .translation("config.promaid.memory.persona").define("persona", true);
        MEMORY_CARE_POINTS = BUILDER.comment("每日关心点（每日回顾附上'下次该怎么对主人'的行动建议——从情绪残留/边界/风格推导，主动会话自动复用）")
                .translation("config.promaid.memory.carePoints").define("carePoints", true);
        MEMORY_DUAL_AGENT = BUILDER.comment("双 agent 提取（摘要与事实/事件分两次独立 LLM 调用，更聚焦互不阻塞；关 = 单次合并提取省 token）")
                .translation("config.promaid.memory.dualAgent").define("dualAgent", true);
        MEMORY_HEARTFELT_ANNIVERSARY = BUILDER.comment("纪念日联动（heartfelt 纪念日里程碑达成/临近 → 写关系记忆 + 情绪脉冲；heartfelt 未触发时 promaid 补位说话；不依赖，未装则静默）")
                .translation("config.promaid.memory.heartfeltAnniversary").define("heartfeltAnniversary", true);
        MEMORY_PERSONA_UNIFY = BUILDER.comment("人设统一（TLM 原版已有人设时，人格种子块降级为补充——只补人格参数/核心记忆，不再重复身份，冲突以 TLM 设定为准；关 = 双人设并存旧行为）")
                .translation("config.promaid.memory.personaUnify").define("personaUnify", true);
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
                .defineInRange("lookTicks", 6, 1, 30);
        PERCEPTION_LOOK_ENTER_DEG = BUILDER.comment("看向进入角度（度）")
                .translation("config.promaid.perception.lookEnterDeg")
                .defineInRange("lookEnterDeg", 30.0, 10.0, 80.0);
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
        // v1.5.287：查看主人物品栏工具（只读查询主人背包内容）
        TOOL_OWNER_INVENTORY = BUILDER.comment("smart_owner_inventory 工具（查看主人背包里有什么——只读查询，不修改物品）")
                .translation("config.promaid.aitools.ownerInventory").define("ownerInventory", true);
        BUILDER.pop();

        // ---- 对话与提示 ----
        BUILDER.comment("对话与提示设置").translation("config.promaid.dialogue").push("dialogue");
        DIALOGUE_STATUS_REPORTER = BUILDER.comment("工作状态播报（女仆卡住时气泡解释原因）")
                .translation("config.promaid.dialogue.statusReporter").define("statusReporter", true);
        DIALOGUE_REPORT_INTERVAL = BUILDER.comment("工作播报间隔（秒，默认 20——与原硬编码一致）：女仆卡住时气泡播报的最短间隔，防刷屏")
                .translation("config.promaid.dialogue.reportInterval").defineInRange("reportInterval", 20, 3, 120);
        DIALOGUE_REPORT_RADIUS = BUILDER.comment("工作播报扫描范围")
                .translation("config.promaid.dialogue.reportRadius").defineInRange("reportRadius", 20, 8, 128);
        DIALOGUE_PROACTIVE = BUILDER.comment("主动对话（关心/夜晚/好感等主动开口）")
                .translation("config.promaid.dialogue.proactive").define("proactive", true);
        DIALOGUE_PROACTIVE_COOLDOWN = BUILDER.comment("两次主动发言最小间隔（分钟）")
                .translation("config.promaid.dialogue.proactiveCooldown").defineInRange("proactiveCooldown", 4, 1, 60);
        DIALOGUE_PROACTIVE_DAILY = BUILDER.comment("主动对话日上限（次，控 token 成本；v1.5.191：4 → 12——7 阶段状态机需要更多发言额度）")
                .translation("config.promaid.dialogue.proactiveDaily").defineInRange("proactiveDaily", 12, 0, 50);
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
                .translation("config.promaid.combat.threatDistance").defineInRange("threatDistance", 8, 4, 32);
        COMBAT_WATER_CLUTCH = BUILDER.comment("落地水（有水桶+坠落自动放水缓冲）")
                .translation("config.promaid.combat.waterClutch").define("waterClutch", true);
        COMBAT_WATER_FALL_DISTANCE = BUILDER.comment("落地水触发高度（格）")
                .translation("config.promaid.combat.waterFallDistance").defineInRange("waterFallDistance", 4.0, 2.0, 20.0);
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
                .defineInRange("fleeSpeed", 1.1, 0.8, 3.0);
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
                .defineInRange("announceCooldown", 500, 40, 600);
        COMBAT_WATER_HOLD = BUILDER.comment("落地水保持时长（tick）")
                .translation("config.promaid.combat.waterHold")
                .defineInRange("waterHold", 20, 5, 100);
        COMBAT_WATER_LANDING_SCAN = BUILDER.comment("落地水下探格数（提前放水检测）")
                .translation("config.promaid.combat.waterLandingScan")
                .defineInRange("waterLandingScan", 3, 2, 16);
        // v1.1.0：落地雪——细雪桶版落地水（下界水会蒸发细雪不会；细雪接触 7 秒才开始
        // 冻伤，保持时长上限 100 tick 远低于冻伤线 140 tick）
        COMBAT_SNOW_CLUTCH = BUILDER.comment("落地雪（细雪桶版落地水，默认开）：高空坠落时在【落点平面】铺 3×3 细雪垫接住她并收回（桶不消耗）——细雪不流动、落点必须正好是雪，故铺 3×3 容错且坠落途中逐 tick 跟着落点补垫；绝不在高处拦她减速（出雪后剩下的路照样摔）；下界也能用（水会瞬间蒸发、细雪不会）；与落地水共用触发高度/保持时长/下探格数，两者都有桶时优先用水")
                .translation("config.promaid.combat.snowClutch").define("snowClutch", true);
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
        // v1.5.280：近战贴脸后退——用户："战斗状态且非自保状态下,即使是近战武器也应该
        // 尝试与敌人稍微拉开距离,而不是贴身搏斗……周围两格内有敌人时会自己往后退远离"
        COMBAT_TACTICS_MELEE_KITE = BUILDER.comment("近战贴脸后退（敌人贴进 2 格内主动后退拉开距离，女仆手长 3 格仍能挥砍）")
                .translation("config.promaid.combat.tacticsMeleeKite").define("tacticsMeleeKite", true);
        // v1.5.189：玩家贴身辅助（被动技能，非工作状态——女仆随时照看主人）
        AID_OWNER_ENABLE = BUILDER.comment("自动投喂/治疗主人（被动：主人饿/血低自动喂食或投掷治疗药水）")
                .translation("config.promaid.combat.aidOwnerEnable").define("aidOwnerEnable", true);
        // v1.5.301：范围上限 18 → 20——旧版注释写"0-20"但 defineInRange 上限 18：
        // 面板填 20 被 Forge 静默钳制回 18（输入框显示 20、实际生效 18），
        // 饱食度 18~19 时永远不喂（用户："那个修改按键要真实有效"——测试调 20
        // 只为确认"只要不满就喂"）
        AID_FOOD_THRESHOLD = BUILDER.comment("投喂触发饱食度（4-20：主人饱食度低于此值自动喂食；20=只要不满就喂）")
                .translation("config.promaid.combat.aidFoodThreshold").defineInRange("aidFoodThreshold", 12, 4, 20);
        AID_HEALTH_THRESHOLD = BUILDER.comment("治疗触发血量（0.1-1：主人血量低于此比例自动治疗；1=掉血就治）")
                .translation("config.promaid.combat.aidHealthThreshold").defineInRange("aidHealthThreshold", 0.30, 0.1, 1.0);
        TORCH_PLACER_ENABLE = BUILDER.comment("被动插火把（主人周围黑暗自动插火把照明）")
                .translation("config.promaid.combat.torchPlacerEnable").define("torchPlacerEnable", true);
        // v1.1.0 实测六十二：女仆着火不传主人（攻击路径取消 + 接触路径自动灭火）
        MAID_FIRE_GUARD = BUILDER.comment("女仆着火不传主人（默认开）：燃烧的女仆贴着主人时不会把火烧到主人身上——她烧她的，主人不点火；主人自己站火里/岩浆里则不干预")
                .translation("config.promaid.combat.maidFireGuard").define("maidFireGuard", true);
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
                .translation("config.promaid.combat.playerDamageMode").defineInRange("playerDamageMode", 4, 0, 4);
        PLAYER_DAMAGE_MAID_CAP = BUILDER.comment("玩家伤害上限比例（0-1：模式 3 时单次伤害 = 女仆最大生命 × 此比例；默认 0.1 = 10%）")
                .translation("config.promaid.combat.playerDamageMaidCap").defineInRange("playerDamageMaidCap", 0.1, 0.01, 0.5);
        // v1.1.0：主动切换战斗模式——主人被敌对生物攻击时，附近非自保女仆无论什么任务
        // 都立即切战斗（枪械优先，其余按背包武器随机），威胁消失后自动还原原任务
        COMBAT_AUTO_SWITCH = BUILDER.comment("主动切换战斗模式（主人被敌对生物攻击时，附近女仆无论什么任务都立即切战斗保护主人；默认开启）")
                .translation("config.promaid.combat.autoSwitch").define("autoSwitch", true);
        COMBAT_AUTO_SWITCH_RADIUS = BUILDER.comment("主动切战斗响应半径（格）：主人受伤或开火时，此半径内的女仆才会响应切换")
                .translation("config.promaid.combat.autoSwitchRadius").defineInRange("autoSwitchRadius", 16, 4, 64);
        // v1.1.0 实测二十一：武器权重可配置（原版/模组各一条）——选战斗任务时
        // 加权随机：模组任务默认 2.0（优先）、原版五件套默认 1.0（降半但不排除）。
        // 例：背包有法书+铁剑 → 法术:近战 = 2:1 ≈ 67%:33%；想五五开就把两条都设 1。
        COMBAT_AUTO_SWITCH_MOD_WEIGHT = BUILDER.comment("模组武器权重（选战斗任务时的加权随机权重，默认 2.0）：模组攻击任务（万法皆通/史诗战斗/真正的力量/枪械等）普遍更强故默认优先；与原版权重成比例决定被选概率")
                .translation("config.promaid.combat.autoSwitchModWeight").defineInRange("autoSwitchModWeight", 2.0, 0.1, 10.0);
        COMBAT_AUTO_SWITCH_VANILLA_WEIGHT = BUILDER.comment("原版武器权重（默认 1.0）：原版五件套（近战/弓/弩/三叉戟/弹幕）的加权随机权重——设 0.5=更少选原版，设 2=与模组平起平坐")
                .translation("config.promaid.combat.autoSwitchVanillaWeight").defineInRange("autoSwitchVanillaWeight", 1.0, 0.1, 10.0);
        // v1.1.0 实测五十八：近战/远程偏好权重——两者皆可用（近战远程任务池都有候选）
        // 且敌人在近身距离（≤5 格）时按权重随机选池；同时是战中换战术（实测五十七）
        // 的开关量：某类权重 0 = 永不主动选/切向该类
        COMBAT_PREF_MELEE_WEIGHT = BUILDER.comment("近战偏好权重（默认 3）：近战远程武器都有、敌人在近身距离（≤5 格）时按 近战:远程 权重随机选——3 配远程 1 ≈ 75% 选近战；设 0 = 永不主动选近战（战中也不会切近战，近身只靠反击击退）")
                .translation("config.promaid.combat.prefMeleeWeight").defineInRange("prefMeleeWeight", 3, 0, 10);
        COMBAT_PREF_RANGED_WEIGHT = BUILDER.comment("远程偏好权重（默认 1）：近战远程武器都有、敌人在近身距离（≤5 格）时按 近战:远程 权重随机选——调大则近身也更倾向保持远程输出；设 0 = 永不主动选远程（战中也不会切远程）")
                .translation("config.promaid.combat.prefRangedWeight").defineInRange("prefRangedWeight", 1, 0, 10);
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 防抖三件套）：战中换战术稳定机制
        COMBAT_TACTIC_HOLD_TICKS = BUILDER.comment("战中换战术最短持有（tick，默认 40=2 秒）：近远程切换后至少持有这么久才允许再次评估换战术——防敌人在门槛距离徘徊时频繁换任务重建 brain；0 = 不限制")
                .translation("config.promaid.combat.tacticHoldTicks").defineInRange("tacticHoldTicks", 40, 0, 600);
        COMBAT_REVERSE_WINDOW_TICKS = BUILDER.comment("战中反向切换窗口（tick，默认 100=5 秒）：换战术后在此窗口内又想换回上一个战术，视为来回横跳")
                .translation("config.promaid.combat.reverseWindowTicks").defineInRange("reverseWindowTicks", 100, 20, 600);
        COMBAT_REVERSE_COOLDOWN_TICKS = BUILDER.comment("战中反向切换冷却（tick，默认 200=10 秒）：横跳被判定后进入冷却，期间不再换战术（保持当前战术硬打）——0 = 关闭反向抑制")
                .translation("config.promaid.combat.reverseCooldownTicks").defineInRange("reverseCooldownTicks", 200, 0, 1200);
        // v1.1.0 实测六十七（用户："手上完全没有攻击性物品的女仆，就不应该触发自主战斗"）
        COMBAT_UNARMED_SKIP = BUILDER.comment("空手不参战（默认开）：背包和主手都没有任何攻击任务认可的武器（剑/弓/枪械/模组武器等）的女仆，不触发自主战斗、维持原任务继续干活；关闭恢复旧行为（没有武器也空手近战兜底）")
                .translation("config.promaid.combat.unarmedSkip").define("unarmedSkip", true);
        // v1.1.0 实测二十：枪械优先开关已删除——附属生态（万法皆通/史诗战斗/真正的
        // 力量等）加入后模组攻击任务与枪械等价，改为任务池加权随机（原版武器降半权）
        COMBAT_AUTO_SWITCH_RESTORE = BUILDER.comment("战斗结束自动还原（威胁消失一段时间后切回战斗前的原任务；关闭则保持战斗模式直到玩家手动切换）")
                .translation("config.promaid.combat.autoSwitchRestore").define("autoSwitchRestore", true);
        COMBAT_AUTO_SWITCH_RESTORE_DELAY = BUILDER.comment("战斗结束还原延迟（tick，200=10 秒）：威胁消失后持续安全这么久才切回原任务")
                .translation("config.promaid.combat.autoSwitchRestoreDelay").defineInRange("autoSwitchRestoreDelay", 200, 60, 3600);
        COMBAT_AUTO_SWITCH_RESTORE_THREAT_DIST = BUILDER.comment("还原判定威胁半径（格，默认 8）：女仆周围此范围内无敌对生物才算\"威胁消失\"、开始还原计时——独立于响应半径（远处怪不该让女仆一直卡在战斗里回不了岗）；战斗中玩家手动换的任务不会被还原翻回去")
                .translation("config.promaid.combat.autoSwitchRestoreThreatDist").defineInRange("autoSwitchRestoreThreatDist", 8, 2, 32);
        // v1.1.0 实测八十四：僵局逃逸——够不着的敌对生物不再让女仆永远卡在战斗任务
        COMBAT_AUTO_SWITCH_STALE = BUILDER.comment("战斗僵局逃逸（秒，默认 60）：威胁仍在还原半径内、但女仆与敌对生物超过这么久没有任何伤害往来（怪卡墙后/玻璃后/传送门里/飞行绕圈等杀不掉也够不着的死局）→ 不再无限等待，按正常安全计时切回原任务；latest.log 搜 auto-combat stale 可查是哪种怪卡住的。0 = 关闭（旧版行为，可能永远卡在战斗任务）")
                .translation("config.promaid.combat.autoSwitchStaleSeconds").defineInRange("autoSwitchStaleSeconds", 60, 0, 3600);
        // v1.1.0 实测八十五：动态威胁圈——远程风筝怪不再引发"还原又中箭"反复横跳
        COMBAT_AUTO_SWITCH_EXPAND = BUILDER.comment("动态威胁圈（秒，默认 10）：最近伤害过女仆的敌对生物即使站在还原半径（8 格）之外，只要它还活着、距离不超过 32 格、且这个时间内有过接触，还原判定的威胁圈就自动放大把它包含进来——被远程怪压着打期间保持战斗态还击，不再'刚还原又中箭反复横跳'；怪死/走远/超窗后圈回落。0 = 关闭（只用固定半径）")
                .translation("config.promaid.combat.autoSwitchThreatExpandSeconds").defineInRange("autoSwitchThreatExpandSeconds", 10, 0, 120);
        BUILDER.pop();

        // ---- 搭路（v1.1.0：主人在上方一定距离内 → 垫方块靠近，默认关） ----
        BUILDER.comment("搭路设置").translation("config.promaid.bridge").push("bridge");
        BRIDGE_ENABLED = BUILDER.comment("搭路（默认关）：主人在女仆上方一定距离内、周围无威胁、背包有方块时，女仆走过去垫方块搭高靠近主人（借鉴僵尸搭方块追人；搭的方块 N 秒后自动回收）")
                .translation("config.promaid.bridge.enabled").define("enabled", false);
        BRIDGE_MAX_DIST = BUILDER.comment("搭路触发距离（格，默认 7=传送判定距离）：主人距女仆小于此值才搭路；超过则交给传送/跟随")
                .translation("config.promaid.bridge.maxDist").defineInRange("maxDist", 7, 2, 32);
        BRIDGE_AIR_MAX_DIST = BUILDER.comment("空中搭桥触发距离（格，默认 24）：女仆已在空中（脚下悬空/站在垫的方块上）且够不着地面导航时，主人再远也直接空中铺桥走过去——空中没有'走路过去'的选项，7 格传送阈值不再适用；设为 0 关闭空中远距铺桥（只保留 7 格近距逻辑）")
                .translation("config.promaid.bridge.airMaxDist").defineInRange("airMaxDist", 24, 0, 64);
        BRIDGE_MIN_DY = BUILDER.comment("搭路最小高差（格，默认 2）：主人至少高于女仆这么多格才搭路（平路/低处走路处理）")
                .translation("config.promaid.bridge.minDy").defineInRange("minDy", 2, 1, 8);
        BRIDGE_THREAT_DIST = BUILDER.comment("搭路威胁半径（格，默认 8）：周围此范围内有敌对生物时不搭路（塔会被拆/搭一半挨打）；刷怪频繁的整合包里可再调小，过大会导致搭路几乎永不触发")
                .translation("config.promaid.bridge.threatDist").defineInRange("threatDist", 8, 4, 32);
        BRIDGE_STEP_COOLDOWN = BUILDER.comment("搭路节奏（tick/块，默认 8）：每垫一块方块的最短间隔——调大搭得更从容")
                .translation("config.promaid.bridge.stepCooldown").defineInRange("stepCooldown", 8, 2, 40);
        BRIDGE_PLACED_LIFETIME = BUILDER.comment("搭路方块清理时间（秒，默认 10）：垫的方块放置 N 秒后自动变掉落物回收（女仆站在上面时延后）")
                .translation("config.promaid.bridge.placedLifetime").defineInRange("placedLifetime", 10, 3, 60);
        BRIDGE_RECLAIM_TO_MAID = BUILDER.comment("搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用）：开启后到期/被摧毁的搭脚方块不掉落地面，直接塞回附近女仆（8 格内最近者）的背包——背包满/附近没女仆才落地；关闭则恢复掉落物落地")
                .translation("config.promaid.bridge.reclaimToMaid").define("reclaimToMaid", true);
        // v1.1.0 实测十七：战斗方块清理时间（默认 60 秒——战斗节奏多变女仆可能在
        // 塔上待一阵，比挖矿/搭路的 10 秒长；实测十八：女仆踩着时刷新计时，走开后
        // 每块还有完整寿命缓冲，不会整塔瞬间塌）
        COMBAT_PLACED_LIFETIME = BUILDER.comment("战斗搭方块清理时间（秒，默认 60）：自保行为（搭高/翻墙/搭桥/封头盖帽/岩浆垫高）搭的方块 N 秒后自动变掉落物回收；战斗节奏多变，比挖矿/搭路的 10 秒长——女仆还站在上面的方块会刷新计时（走开后才开始倒数），不会把她摔下去")
                .translation("config.promaid.combat.placedLifetime").defineInRange("combatPlacedLifetime", 60, 3, 600);
        BUILDER.pop();

        // ---- 杂项 ----
        BUILDER.comment("杂项设置").translation("config.promaid.misc").push("misc");
        MISC_COOK_RADIUS = BUILDER.comment("烹饪任务熔炉搜索范围")
                .translation("config.promaid.misc.cookRadius").defineInRange("cookRadius", 16, 4, 48);
        MISC_BREW_RADIUS = BUILDER.comment("酿造任务酿造台搜索范围")
                .translation("config.promaid.misc.brewRadius").defineInRange("brewRadius", 16, 4, 48);
        MISC_PROCESS_COOLDOWN = BUILDER.comment("烹饪/酿造处理间隔（tick）")
                .translation("config.promaid.misc.processCooldown").defineInRange("processCooldown", 10, 10, 200);
        MISC_BUBBLE_LIMIT_MS = BUILDER.comment("对话气泡限频（毫秒，防刷屏）")
                .translation("config.promaid.misc.bubbleLimitMs").defineInRange("bubbleLimitMs", 10000, 500, 60000);
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
        // v1.1.0 实测四十四：女仆区块强制加载（"约等于玩家"）——与主人不同维度的
        // 女仆所在区块挂强制加载票（实体正常 ticking），保证跨维度跟随/死亡传送
        // 永远能找到她（旧版主人在远处的女仆区块卸载后传送静默失效）
        MISC_MAID_CHUNK_LOAD = BUILDER.comment("女仆区块强制加载（与主人不同维度的女仆所在区块保持加载，随时可跨维度传送；关闭后远处女仆所在区块卸载时无法传送）")
                .translation("config.promaid.misc.maidChunkLoad").define("maidChunkLoad", true);
        // v1.1.0 实测七十九：受困救援——死亡瞬间的坏落点（下界基岩顶等）已被
        // "主人非存活不追"挡住，这里兜底捞回历史上已经受困的女仆
        MISC_MAID_RESCUE = BUILDER.comment("受困救援（默认开）：女仆被困在下界基岩顶层（高度≥126）或掉出世界底部时，自动安全传送到存活的主人身边（跨维度通用；在家模式的女仆也救——基岩顶不是家）；已在主人身边 8 格内不触发")
                .translation("config.promaid.misc.maidRescue").define("maidRescue", true);
    // v1.5.161：农场连锁收获 / 收获物自动收集（v1.5.189：连锁默认开启——用户要求
    // "连锁采集也应加入"；收获物收集保持默认关，避免自动拾取导致背包爆炸）
    MISC_CHAIN_HARVEST = BUILDER.comment("农场连锁收获（收割时以目标格为中心蔓延连锁收割相连农田里的成熟作物）")
            .translation("config.promaid.misc.chainHarvest").define("chainHarvest", false);
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
    // v1.1.0：排班表总开关（用户"玩家可操作"原则——新功能都要有手册内开关）
    MISC_SCHEDULE_ENABLED = BUILDER.comment("排班表系统（默认开）：按游戏内时间自动应用女仆的排班日程；关闭后排班调度停摆（已保存的日程不丢，重新打开恢复生效），女仆保持当前任务")
            .translation("config.promaid.misc.scheduleEnabled").define("scheduleEnabled", true);
    // v1.1.0 实测六十一：战斗还原后排班宽限——威胁在还原威胁半径边缘闪烁时，
    // 战斗↔还原循环不再立刻把排班段任务压回去（还原后先干原任务一段时间）
    MISC_SCHEDULE_RESTORE_GRACE = BUILDER.comment("战斗还原后排班宽限（tick，默认 60=3 秒）：主动战斗结束还原原任务后，排班调度等待这么久才接管（期间她继续干战斗前的任务）——防威胁闪烁导致战斗/还原/排班反复拉扯；0 = 还原立即交排班")
            .translation("config.promaid.misc.scheduleRestoreGrace").defineInRange("scheduleRestoreGrace", 60, 0, 400);
    // v1.5.199：爱憎分明饥饿测试开关——其自动进食会优先吃腐肉导致"越吃越饿/饿死"，
    // 饿死/撑死伤害与速度惩罚也一并关闭（测试期默认关闭；关闭本项恢复原版饥饿行为）
    MISC_LOVELOATHE_DISABLE_HUNGER = BUILDER.comment("禁用爱憎分明饥饿/撑死（默认开：饿死伤害/撑死/自动进食（含腐肉）/速度惩罚全禁；关掉恢复原版）")
            .translation("config.promaid.misc.loveLoatheHungerOff").define("loveLoatheHungerOff", true);
    // v1.5.310：爱憎分明软联动开关组（仅在安装爱憎分明时由「爱憎分明模组调试」页可见可调）
    MISC_LOVELOATHE_MASTER = BUILDER.comment("爱憎分明联动总开关（默认开：极端饥饿/情绪数据等反射联动；关闭后不再读取爱憎分明数据，仅「禁用饥饿」开关独立生效）")
            .translation("config.promaid.misc.loveLoatheMaster").define("loveLoatheMaster", true);
    MISC_LOVELOATHE_EXTREME_HUNGER = BUILDER.comment("极端饥饿保命联动（默认开：女仆极端饥饿——爱憎分明饥饿值 ≤9——且无其他治疗食物时，吃金苹果/附魔金苹果保命）")
            .translation("config.promaid.misc.loveLoatheExtremeHunger").define("loveLoatheExtremeHunger", true);
    MISC_LOVELOATHE_EMOTION = BUILDER.comment("情绪数据联动（默认开：记忆系统感知爱憎分明情绪投影——信任/恐惧值影响关系记忆与 AI 上下文注入）")
            .translation("config.promaid.misc.loveLoatheEmotion").define("loveLoatheEmotion", true);
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
