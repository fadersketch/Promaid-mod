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
/** v1.2.0 实测五百五十七：建造默认速度迁移标记（内部，一次性） */
public static final ForgeConfigSpec.BooleanValue BUILD_SPEED_MIGRATED;
/** v1.2.2 实测五百六十二：排班活动半径默认迁移标记（内部，一次性） */
public static final ForgeConfigSpec.BooleanValue SCHEDULE_RANGE_MIGRATED;
    public static final ForgeConfigSpec.IntValue BUILD_GLOBAL_QUOTA;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_FORCE_CHUNKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_RANGE;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_HEIGHT;
    public static final ForgeConfigSpec.IntValue BUILD_DESIGN_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_STRUCTURE_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_MAX_MAIDS;
    public static final ForgeConfigSpec.BooleanValue BUILD_ORIGIN_PLAYER;
    /** v1.2.0：指标石（临时蓝图制作器）总开关 */
    public static final ForgeConfigSpec.BooleanValue BUILD_INDEX_STONE;
/** v1.5.316：红石机器专属搭建（专属顺序 + 活建造 + 自动放矿车），默认开 */
public static final ForgeConfigSpec.BooleanValue BUILD_MACHINE_SMART;
/** v1.2.2 实测五百八十五（issue #15）：图纸流体保留档位 auto/always/never */
public static final ForgeConfigSpec.ConfigValue<String> BUILD_KEEP_FLUIDS;
/** v1.2.2 实测五百八十六（issue #14）：缺料同类宽松档位 off/machine/always */
public static final ForgeConfigSpec.ConfigValue<String> BUILD_LOOSE_MATERIALS;
// v1.5.331：TNT 点火保护期（秒）——建造期/完工激活期/宽限期压制一切 TNT 点火
public static final ForgeConfigSpec.IntValue BUILD_TNT_IGNITION_GRACE;
/** v1.1.0 实测八十二：蓝图投影预览——区块显示时叠加半透明幽灵方块轮廓（确认朝向/形状） */
public static final ForgeConfigSpec.BooleanValue BUILD_PROJECTION;
/** 实测五百五十三③：建造缺料时从区块内容器取料 */
public static final ForgeConfigSpec.BooleanValue BUILD_FETCH_FROM_CHESTS;
/** 实测五百五十三③：取料扫描在区块外再外扩的格数 */
public static final ForgeConfigSpec.IntValue BUILD_CHEST_SEARCH_MARGIN;
/** 实测五百五十三③：每趟每格容器取多少个 */
public static final ForgeConfigSpec.IntValue BUILD_CHEST_FETCH_PER_TAKE;
/** 实测五百五十三③：两趟取料之间的最短间隔（tick） */
public static final ForgeConfigSpec.IntValue BUILD_CHEST_FETCH_COOLDOWN;
    // v1.5.254：缺料自动替代（先同族后自定义；按高度分类的三张自定义表）
    public static final ForgeConfigSpec.BooleanValue BUILD_ALT_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_SLABS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_TALLS;
    /** v1.5.275：横两格（床）替代品表 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_WIDES;
    /** v1.5.275：无碰撞方块替代品表 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILD_ALT_NOCLIPS;
    /** v1.5.102：以下把模组其余硬编码数值全部纳入面板（要求"所有数值都可调"） */
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
    // v1.1.0 实测一百五十六：骑乘中禁止搭方块（扫帚上挖矿不再垫方块）
    public static final ForgeConfigSpec.BooleanValue MINE_RIDE_NO_PILLAR;

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
    /** v1.1.0 实测二百二十八/二百二十九：随手种树总开关（伐木面板可调，默认开） */
    public static final ForgeConfigSpec.BooleanValue WOOD_PLANT_SAPLING_ENABLED;
    /** v1.1.0 实测二百二十八：随手种树冷却（伐木面板可调，默认 100 tick = 5 秒） */
    public static final ForgeConfigSpec.IntValue WOOD_PLANT_SAPLING_COOLDOWN;

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
    // v1.5.102：记忆剩余数值（调度/投影/超时/检索/衰减）
    public static final ForgeConfigSpec.IntValue MEMORY_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue MEMORY_PROJECTION_CHARS;
    public static final ForgeConfigSpec.IntValue MEMORY_EXTRACT_TIMEOUT_MIN;
    public static final ForgeConfigSpec.DoubleValue MEMORY_RRF_K;
    public static final ForgeConfigSpec.IntValue MEMORY_DECAY_DAYS;
    public static final ForgeConfigSpec.IntValue MEMORY_DECAY_SALIENCE;
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
    // v1.2.1：人设统一（TLM 已有人设时人格块降级为补充）
    public static final ForgeConfigSpec.BooleanValue MEMORY_PERSONA_UNIFY;

    // ================= 对话与提示 =================
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_STATUS_REPORTER;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_INTERVAL;
    public static final ForgeConfigSpec.IntValue DIALOGUE_REPORT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_PROACTIVE;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_COOLDOWN;
    public static final ForgeConfigSpec.IntValue DIALOGUE_PROACTIVE_DAILY;
    // v1.1.0 实测一百九十四：击杀邀功对话开关（默认关——反馈击杀日志时刻刷屏）
    public static final ForgeConfigSpec.BooleanValue DIALOGUE_PROACTIVE_KILL;
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
    // v1.2.2 实测五百七十八：指挥三件套（切任务 / 空袭 / 工位）+ 状态自检
    public static final ForgeConfigSpec.BooleanValue TOOL_SWITCH_TASK;
    public static final ForgeConfigSpec.BooleanValue TOOL_AIR_RAID;
    public static final ForgeConfigSpec.BooleanValue TOOL_WORK_AREA;
    public static final ForgeConfigSpec.BooleanValue TOOL_READINESS;

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
    // v1.2.0：飞行作战模式（鞘翅 + 任意近战武器 + 烟花；1.20.1 无重锤 → 收翅下落命中结算暴击）
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_MODE;
    /** v1.2.0 实测四百六十九：飞行作战时免疫"鞘翅撞墙伤害"（默认开，属生存与复活分区） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_NO_WALL_DAMAGE;
    /**
     * v1.2.0 实测四百九十四：空袭时免疫【摔落伤害】（**默认关**，属生存与复活分区）。
     *
     * 默认关是有意的：空袭的落地水/雪本身就能接住（见 WaterClutchBehavior 的空袭
     * 落地缓冲分支），这条是"即使没桶/没接住也不摔死"的硬保险。用户明确要求默认关。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_NO_FALL_DAMAGE;
    /**
     * v1.2.0 实测五百三十四：激流三叉戟的**旋转突进**（默认开）。
     *
     * 需求原文："能不能想办法把玩家一的代码套到女仆身上呢？当处于攻击模式/近战空袭且
     * 手中的武器为激流三叉戟时调用。"
     *
     * v1.2.0 实测五百三十八：改成**独立攻击链路**——开启后，攻击模式 / 空袭下主手拿着
     * 激流三叉戟时，"她的攻击"就是朝目标冲过去旋转一击（10 格内直接发起、旋转 20 tick、
     * 撞到就结算一次伤害），原本的普通挥砍由这条链路取代。默认开——这是"激流三叉戟"
     * 这个附魔存在的意义，关掉等于把她手里的激流三叉戟降级成普通三叉戟。
     */
    public static final ForgeConfigSpec.BooleanValue RIPTIDE_DASH_ENABLE;
    /**
     * v1.2.0 实测五百三十五：弩是否可以用**普通烟花**（无爆炸组件）当弹药（默认开）。
     *
     * 默认开 = 任意烟花都能当弩弹药，与原版 `CrossbowItem` 的弹药谓词一致
     * （玩家拿一叠普通烟花配弩照样能射）。
     * 关掉 = 只有**攻击性烟花**（合成时放了烟火之星、带 `Explosions` 的那种）才当弹药，
     * 普通烟花留着当飞行燃料，绝不被弩烧掉。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_CROSSBOW_PLAIN_FIREWORK;
    /**
     * v1.2.0 实测五百零三：远程空袭的**近身弹开**（默认开）。
     *
     * 需求："周围三格内出现怪物时女仆被弹开（强制加一个远离怪物的速度矢量），
     * 防止远程攻击时还往敌人身上飞、下落途中被贴脸打死。女仆自己被弹开更平衡，
     * 弹开敌人太超模（也保证狭小空间内敌人仍有命中的可能）。"
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_RANGED_PUSH;
    /**
     * v1.2.0 实测五百四十七【空袭牵引绳】（默认 100 格，0 = 关闭）。
     *
     * 需求原文："空袭期间加个机制，如果以自身为圆心，半径100格范围内没有发现主人。
     * 立即执行一次传送到主人身边（等效拿排班表的传送）。防止女仆飞太高把目标打死后，
     * 自己回不来。"
     *
     * 只在"她确实在空中"时生效（地面上交给同维度拉回那套更保守的规则）；距离按 3D 算，
     * 所以"飞太高"本身也会触发。完整口径见 {@code com.maidsmart.combat.MaidFlightRecall}。
     */
    public static final ForgeConfigSpec.IntValue COMBAT_FLIGHT_RECALL_DISTANCE;
    /**
     * 实测五百六十七（PR #8 移植）【空袭·法术层】——空袭途中顺带释放法术（默认开）。
     *
     * 需求原文："女仆能使用近战/远程空袭的默认武器（近战：鞘翅+重锤，远程：鞘翅+弓/枪械）
     * 的同时进行法术释放。"
     *
     * 这是**叠加层**：不新增任务、不占武器位、不改三件套激活口径。女仆身上（背包 /
     * 饰品栏 / 主手任一）有法术书时，空袭途中会按 COMBAT_FLIGHT_SPELL_CAST_INTERVAL
     * 的节奏向她当前的空袭目标发起一次施法；法术书放在**饰品栏**完全可用（法术模组
     * 自己的 ISpellContainer 扫描覆盖 curios，不看主手）。
     *
     * 需要装《车万女仆：万法皆通》（touhou_little_maid_spell）——没装时本项无任何效果
     * （软兼容，反射适配层见 com.maidsmart.combat.MaidSpellCastCompat）。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_SPELL_CAST;
    /** 实测五百六十七：空袭期间两次施法之间的最短间隔（tick，默认 20 = 1 秒）。 */
    public static final ForgeConfigSpec.IntValue COMBAT_FLIGHT_SPELL_CAST_INTERVAL;
    /** 实测五百六十七：空袭期间的施法距离（格，默认 24 = 法术模组自己的 maxSpellRange）。 */
    public static final ForgeConfigSpec.DoubleValue COMBAT_FLIGHT_SPELL_CAST_RANGE;
    /**
     * v1.2.0 实测五百七十二【空袭·位移法术：分"提供高度"与"提供速度"两类】（默认开）。
     *
     * 需求：① 位移类法术用于**飞行加速**；② 用于**平地起飞**（来自法术模组作者转达的玩家反馈）。
     *
     * 【为什么要分两类】ISS 里两类位移法术的**冲量方向**不同，行为逻辑因此也不同：
     * - 「升腾」`irons_spellbooks:ascension`：`motion = 视线水平分量 + (0,5,0)` —— 给**向上**初速；
     * - 「烈焰冲锋」`irons_spellbooks:burning_dash`：`forward.multiply(3,1,3).normalize().add(0,.25,0)`——
     *   **沿视线**冲刺，而且**垂直分量被保留**（水平只放大 3 倍），站在地上时还会先把她抬高 1.5 格。
     *   也就是说：**抬头瞄着放烈焰冲锋 = 也能起飞**（实测仰角 62° 时冲量 Y ≈ 0.98 格/tick）。
     *
     * 因此本组配置分成：
     * - {@link #COMBAT_FLIGHT_DASH_CLIMB_SPELLS}「提供高度」：用于**起飞**与**补高**——施法前把她的
     *   俯仰摆到抬头（并清掉法术模组那份施法目标，否则它施法前会把朝向拧平）；
     * - {@link #COMBAT_FLIGHT_DASH_BOOST_SPELLS}「提供速度」：用于**飞行加速**——对着目标冲，
     *   让法术模组自己的朝向逻辑生效即可。
     * 一个法术可以**同时出现在两张表里**（烈焰冲锋默认就是这样：既顶得起人、也能在空中续速）。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_DASH_CLIMB;
    /** v1.2.0 实测五百七十二：「提供速度」那一类是否启用（默认开）——飞行途中对着目标冲刺续速 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_DASH_BOOST;
    /** v1.2.0 实测五百七十二：「提供高度」的法术表（用于起飞与补高） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> COMBAT_FLIGHT_DASH_CLIMB_SPELLS;
    /** v1.2.0 实测五百七十二：「提供速度」的法术表（用于飞行加速） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> COMBAT_FLIGHT_DASH_BOOST_SPELLS;
    /**
     * v1.2.0 实测五百七十二：两次位移法术之间的最短间隔（tick，默认 40 = 2 秒）。
     * 这是我们这边的节流下限；会不会把法术模组自己的冷却压短见
     * {@link #COMBAT_FLIGHT_DASH_RESPECT_COOLDOWN}。
     */
    public static final ForgeConfigSpec.IntValue COMBAT_FLIGHT_DASH_INTERVAL;
    /**
     * v1.2.0 实测五百七十二：**「提供速度」那一类**是否尊重法术自身冷却（默认开）。
     *
     * 【口径：起飞不设限、加速照旧】"提供高度"（起飞/补高）**始终不受法术自身冷却约束**——
     * 只按 {@link #COMBAT_FLIGHT_DASH_INTERVAL} 的节奏放。依据有两条：
     * ① 本模组对**位移手段**一向是"让女仆比玩家宽松"：激流三叉戟那一套就是
     *   **忽略原版"必须在水中/雨中"的限制**（玩家做不到、女仆能做）；
     * ② "平地起飞"这个需求本身要求她**没有烟花也能持续飞**——若起飞也卡法术冷却，
     *   升腾 15 秒一记只能把她抬约 6 格、随后缓降（实测），需求就等于没满足。
     *
     * 本项管的是"提供速度"那一类（飞行加速）：开启时写回冷却取
     * `max(空袭位移间隔, 法术自身冷却)`（例：烈焰冲锋 10 秒），不会让空中冲刺比玩家更频繁；
     * 关掉则那一类也完全按空袭间隔来。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLIGHT_DASH_BOOST_RESPECT_COOLDOWN;

    // ================= 空袭数值（v1.2.2 实测五百八十一：原来全是 MaidFlightCombatBehavior 里的硬编码常量，现在全部可调；配置面板：战斗与自保 → 空袭数值） =================
    /** 起飞段时长·近战（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_LAUNCH_TICKS_MELEE;
    /** 起飞段时长·远程（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_LAUNCH_TICKS_RANGED;
    /** 起飞仰角·近战（正切值）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_LAUNCH_CLIMB_TAN_MELEE;
    /** 起飞仰角·远程（正切值）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_LAUNCH_CLIMB_TAN_RANGED;
    /** 起飞触发距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_LAUNCH_RANGE;
    /** 占位高度容差（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_ALTITUDE_TOLERANCE;
    /** 起跳等待上限（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_JUMP_TICKS;
    /** 烟花最小间隔（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_FIREWORK_COOLDOWN;
    /** 羽扇最小间隔（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_FAN_COOLDOWN;
    /** 收翅俯冲触发距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_SMASH_RANGE;
    /** 猛击命中判定距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_SMASH_HIT_RANGE;
    /** 范围强制命中半径（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_FORCED_HIT_RADIUS;
    /** 猛击段最长（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_SMASH_MAX_TICKS;
    /** 地面近战触及距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_MELEE_REACH;
    /** 俯仰限幅·抬头（度）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_MAX_PITCH_UP;
    /** 俯仰限幅·低头（度）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_MAX_PITCH_DOWN;
    /** 盘旋半径（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_ORBIT_RADIUS;
    /** 期望盘旋高度（目标上方格数）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_HOLD_HEIGHT;
    /** 高度修正增益。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_HOLD_GAIN;
    /** 高度修正偏置（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_HOLD_BIAS;
    /** 掉高容差（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_BOOST_DROP;
    /** 盘旋抬头上限（度）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_ORBIT_UP_MAX;
    /** 盘旋低头上限（度）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_ORBIT_DOWN_MAX;
    /** 掉高补推间隔（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_RANGED_BOOST_INTERVAL;
    /** 补推抬头窗口（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_RANGED_BOOST_AIM_TICKS;
    /** 补推仰角（度）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_BOOST_PITCH;
    /** 远程开火基础间隔（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_RANGED_SHOT_COOLDOWN;
    /** 远程射程（格，弓弩）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_ATTACK_RANGE;
    /** 弹开触发半径（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_PUSH_RADIUS;
    /** 弹开水平速度。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_PUSH_SPEED;
    /** 弹开抬升速度。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_RANGED_PUSH_UP;
    /** 弹开保持（tick）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.IntValue AIR_RAID_RANGED_PUSH_TICKS;
    /** 冲刺最小距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_DASH_BOOST_MIN_RANGE;
    /** 冲刺最大距离（格）。数值口径见 {@code com.maidsmart.combat.MaidFlightCombatBehavior} 里的同名访问器 */
    public static final ForgeConfigSpec.DoubleValue AIR_RAID_DASH_BOOST_MAX_RANGE;

    // ================= 空袭轰炸（v1.2.2 实测五百八十七：近战空袭打完放炸弹 + 远程空袭投掷 TNT。
    //   配置面板：战斗与自保 → 空袭数值 → ⑦ 空袭轰炸；口径与反编译实证见 com.maidsmart.combat.MaidBombing） =================
    /** 近战空袭轰炸总开关（默认开） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_MELEE;
    /** 远程空袭投掷 TNT（默认开） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_TNT;
    /** 起爆延迟（tick，默认 10 = 0.5 秒） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_FUSE;
    /** 投掷 TNT 的引信（tick，默认 40 = 2 秒） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_TNT_FUSE;
    /** 投掷 TNT 的间隔（tick，默认 120 = 6 秒） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_TNT_INTERVAL;
    /** 投掷初速（格/tick，默认 0.9） */
    public static final ForgeConfigSpec.DoubleValue COMBAT_BOMBING_TNT_SPEED;
    /** 爆炸是否破坏方块（默认关） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_BREAK_BLOCKS;
    /** 爆炸是否伤到主人/友军（默认关） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_HURT_FRIENDLY;
    /** 女仆放置物的淡粉色标记（默认开，纯客户端渲染） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_PINK_MARK;
    /** 投掷索敌半径（格，默认 12） */
    public static final ForgeConfigSpec.DoubleValue COMBAT_BOMBING_TNT_RANGE;
    /** 残血连投阈值（默认 0.7） */
    public static final ForgeConfigSpec.DoubleValue COMBAT_BOMBING_TNT_BURST_RATIO;
    /** 连投最多几发（默认 3） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_TNT_BURST_COUNT;
    /** 炸弹底座回收延迟（秒，默认 10；0 = 起爆即回收） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_RECLAIM_SECONDS;
    /** 方块与『挂水晶 / 充能』之间的可见间隔（tick，默认 10 = 0.5 秒；v1.2.2 实测五百九十一） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_PLACE_GAP;
    /** 维度闸（默认开；v1.2.2 实测五百九十一）：只在原版会爆炸的维度开放重生锚 / 床链路 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_DIMENSION_GUARD;
    /** TNT 追踪（默认开，v1.2.2 实测五百九十：只改方向、速度不变） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_TNT_TRACK;
    /** TNT 追踪时长（tick，默认 10 = 0.5 秒；v1.2.2 实测五百九十一，0 = 不追踪） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_TNT_TRACK_TICKS;
    /** 重生锚是否需要萤石（默认 true = 原版口径；v1.2.2 实测五百九十二回到"要一颗"） */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE;
    /** 整条轰炸链路的最短间隔（tick，默认 200 = 10 秒；v1.2.2 实测五百九十二） */
    public static final ForgeConfigSpec.IntValue COMBAT_BOMBING_BOMB_INTERVAL;
    /** 动作表现总开关（默认开；v1.2.2 实测五百九十四）：放置 / 充能 / 投掷时副手亮一下 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_POSE;
    /** 爆炸火焰改粉色（默认开；v1.2.2 实测五百九十七）：重生锚 / 床那一炸生成的火换成粉色火 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_PINK_FIRE;
    /** 粉色火焰是否渲染（默认开；v1.2.2 实测五百九十七）：关 = 火还在（按下面那条决定伤不伤人）但不画出来 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_FIRE_RENDER;
    /** 火焰伤害保护（默认开；v1.2.2 实测五百九十七）：玩家与女仆免疫这种火的点燃与掉血 */
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_FIRE_PROTECT;
    /** 第三方玩法模式黑名单（默认开，v1.2.2 实测五百九十：傀儡装配的「傀儡师」） */
    public static final ForgeConfigSpec.BooleanValue COMPAT_PUPPET_BLACKLIST;
    public static final ForgeConfigSpec.BooleanValue COMBAT_BOMBING_AIR_PLACE;

    /**
     * v1.2.2 实测五百六十【友军风免】（默认开）。
     *
     * 需求原文："玩家和其他女仆免疫女仆释放的风暴/风弹效果，不会被震开。当前版本免疫伤害，
     * 但是会被震风。导致从高空攻击的时候会直接把主人也打到空中。"
     *
     * 伤害那条路本来就已经免了（{@code FriendlyFireGuard} + 万法皆通自己的盟友事件），
     * 漏的是**击退**：原版 Explosion 的击退与铁魔法"呼啸之风"这类效果都直接改速度、
     * 不走伤害事件。本项开 = 女仆的法术/炸弹/风弹不再震开主人与同主女仆
     * （只拦"明显外力"，女仆自己的机动一字不改）。口径见
     * {@code com.maidsmart.combat.FriendlyWindGuard}。
     */
    public static final ForgeConfigSpec.BooleanValue COMBAT_FRIENDLY_WIND_IMMUNE;
    public static final ForgeConfigSpec.IntValue COMBAT_AUTO_SWITCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMBAT_AUTO_SWITCH_VANILLA_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue COMBAT_AUTO_SWITCH_MOD_WEIGHT;
    // v1.1.0 实测三百七十九：模组任务参与自主切换（模组物品背书 / 可全关）
    public static final ForgeConfigSpec.BooleanValue COMBAT_AUTO_SWITCH_ALLOW_MOD_TASKS;
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
    public static final ForgeConfigSpec.IntValue BRIDGE_MIN_RADIUS;
    // v1.1.0 实测一百八十七（反馈："水平距离搭建方块有没有启动要求呢？结合实际情况，加个启动要求"）
    public static final ForgeConfigSpec.DoubleValue BRIDGE_START_H_DIST;
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
    // v1.2.0：落地雪的独立数值——旧版只借用水的触发高度/保持时长/下探格数，
    // 面板里只有水的项、雪的调不了；现在两项各自可调
    public static final ForgeConfigSpec.DoubleValue COMBAT_SNOW_FALL_DISTANCE;
    public static final ForgeConfigSpec.IntValue COMBAT_SNOW_HOLD;
    public static final ForgeConfigSpec.IntValue COMBAT_SNOW_LANDING_SCAN;
    // v1.5.134：单兵作战战术（替代已删除的 v1.5.132 战斗协同——PVP 式走位/拉扯/时机格挡）
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_MELEE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_RANGED;
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_SHIELD;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_ORBIT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMBAT_TACTICS_KITE_RANGE;
    // v1.5.280：近战贴脸后退（被敌人贴进 2 格内主动后退拉开，女仆手长 3 格仍能挥砍）
    public static final ForgeConfigSpec.BooleanValue COMBAT_TACTICS_MELEE_KITE;
    // 实测四百零二：低血量自动回魂符（参考 maid_survival——受致死伤害且无保命
    // 物品时，把女仆收进主人背包的空魂符，免去神龛复活；冷却防反复收放）
    public static final ForgeConfigSpec.BooleanValue SOUL_SPELL_ENABLE;
    public static final ForgeConfigSpec.BooleanValue SOUL_SPELL_LETHAL_GUARD;
    public static final ForgeConfigSpec.DoubleValue SOUL_SPELL_OWNER_RADIUS;
    public static final ForgeConfigSpec.IntValue SOUL_SPELL_COOLDOWN_SECONDS;
    // 实测四百一十六：女仆自动复活（死亡后墓碑到期消失，在主人重生点复活）
    public static final ForgeConfigSpec.BooleanValue AUTO_RESURRECT_ENABLE;
    public static final ForgeConfigSpec.IntValue AUTO_RESURRECT_DELAY_SECONDS;
    public static final ForgeConfigSpec.DoubleValue AUTO_RESURRECT_HEALTH_RATIO;
    // 实测四百二十六：复活时机（0 = 延迟秒后复活；1 = 次日黎明复活，照驯养革新宠物床）
    public static final ForgeConfigSpec.IntValue AUTO_RESURRECT_TIMING;
    // v1.5.199：水桶垫水（岩浆逃生——放水 1 秒后收回，水桶不消耗；击退搭高垫水
    // v1.5.250 已删除）
    public static final ForgeConfigSpec.BooleanValue COMBAT_WATER_BUCKET_LAVA;
    // v1.5.203：搭高安全高度（补完目标，与落地水触发高度配合）
    public static final ForgeConfigSpec.IntValue COMBAT_PILLAR_SAFE_HEIGHT;
    // v1.1.0 实测一百五十三/一百五十四：TLM 保护饰品识别（火焰/溺水）——佩戴时对应环境危险不再惊慌
    public static final ForgeConfigSpec.BooleanValue COMBAT_FIRE_PROTECT_BAUBLE;
    public static final ForgeConfigSpec.BooleanValue COMBAT_DROWN_PROTECT_BAUBLE;
    // v1.1.0 实测一百五十五：保命物品（绀珠之药/不死图腾）下是否保留逃跑
    public static final ForgeConfigSpec.BooleanValue COMBAT_FLEE_WITH_SAVE_ITEM;

    // v1.5.189：被动技能（玩家贴身辅助）阈值——喂食/治疗/插火把/共享盾牌/图腾
    public static final ForgeConfigSpec.BooleanValue AID_OWNER_ENABLE;
    // v1.1.0：女仆之间互相支援（同主人、16 格内的姐妹低血/着火时投药水/喂食）
    public static final ForgeConfigSpec.BooleanValue AID_MAID_MUTUAL;
    public static final ForgeConfigSpec.IntValue AID_FOOD_THRESHOLD;
    // v1.2.0 实测五百一十九：投喂食物黑名单（通用判定 + 黑名单）
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> AID_FOOD_BLACKLIST;
    // 实测五百七十一：喂水（软联动「口渴」Thirst Was Taken，modid=thirst）——判定位点 =
    // 主人口渴值。模组不在场时这两项【不注册】（保持 null，配置文件与面板都不出现；
    // 需求原文："如果没有装这个模组，那么此配置项目不会出现"）
    public static final ForgeConfigSpec.IntValue AID_THIRST_THRESHOLD;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> AID_DRINK_WHITELIST;
    /**
     * 实测五百七十六【喂水最低水质】（0-3，默认 2 = 可接受的）：喂水时**装水容器**必须达到这个
     * 水质等级（口渴模组自己的四档：0 肮脏 / 1 有点脏 / 2 可接受的 / 3 纯净）。
     *
     * 反馈："口渴模组对水的品质是有要求的，如果女仆给玩家喂脏水那么反而会耽误玩家。"
     * 只对"装水容器"生效（果汁/牛奶这类没有水质概念的饮品不受影响）；0 = 脏水也喂。
     */
    public static final ForgeConfigSpec.IntValue AID_DRINK_MIN_PURITY;
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
    // v1.1.0 实测一百五十七：熔炉兼容矿物类可烧制物（带矿物/原料标签且有熔炉配方）
    public static final ForgeConfigSpec.BooleanValue MISC_COOK_SMELT_ORES;
    // v1.1.0 实测一百八十二：通用可烧制物回退（有熔炉配方且非装备类即喂，装备类永不熔）
    public static final ForgeConfigSpec.BooleanValue MISC_COOK_SMELT_ANY;
    // v1.1.0 实测一百八十三（反馈："增加女仆散步的频率和速度"）：散步行为开关组
    public static final ForgeConfigSpec.BooleanValue MISC_STROLL_ENABLED;
    public static final ForgeConfigSpec.IntValue MISC_STROLL_INTERVAL;
    public static final ForgeConfigSpec.IntValue MISC_STROLL_RADIUS;
    public static final ForgeConfigSpec.DoubleValue MISC_STROLL_SPEED;
    // v1.1.0 实测四百一十八（反馈："让女仆床和玩家床的代码互通。女仆和玩家可以互相使用对方的床"）
    public static final ForgeConfigSpec.BooleanValue MISC_BED_INTEROP;
    // v1.1.0 实测四百二十一：冷却可视化 HUD（女仆复活倒计时 / 回魂符冷却显示在玩家屏幕上）
    public static final ForgeConfigSpec.BooleanValue MISC_COOLDOWN_HUD;
    /**
     * 实测五百七十三【中键工位标记开关】（默认开）。
     *
     * 潜行 + 鼠标中键方块 = 把身边自家 home 模式女仆的工位/休闲锚点标过去（实测五百六十二）。
     * 反馈：这条手势与 TLM 自带的【河童的罗盘】（`touhou_little_maid:kappa_compass`）撞车——
     * 罗盘是"右键方块记坐标 → 右键女仆写入"的另一套入口，两者**写的是同一份 TLM 排班锚点**
     * （谁后写谁生效）。关掉本项 = 中键完全交还原版取方块；另外**手持河童的罗盘时本功能自动
     * 让位**（不管开关），避免"两个都在标"。
     */
    public static final ForgeConfigSpec.BooleanValue MISC_WORK_POS_MARKER;
    // 实测四百四十三：悬空禁搭方块（自保搭高/搭路/挖矿垫脚/伐木垫脚统一闸口）
    public static final ForgeConfigSpec.BooleanValue MISC_NO_PLACE_IN_AIR;
    // 实测五百三十六：不得搭在主人身上（目标格被主人碰撞箱占着就不搭——
    // 防把主人挤住/卡住；覆盖四个自主搭块模块 + 插火把 + smart_place + 蓝图/碑石建造）
    public static final ForgeConfigSpec.BooleanValue MISC_NO_PLACE_ON_OWNER;
    // 实测四百四十八：蛋糕"可食用"特性开关（默认开——关掉后蛋糕不再是可吃物品，
    // 女仆不再吃蛋糕/不给好感加成，用于与第三方模组冲突时的逃生通道）
    public static final ForgeConfigSpec.BooleanValue MISC_CAKE_EDIBLE;
    // v1.1.0 实测一百五十八：兼容高炉与烟熏炉（烟熏炉按烟熏配方喂生食、高炉按高炉配方喂矿石/粗金属）
    public static final ForgeConfigSpec.BooleanValue MISC_COOK_SMOKER_BLAST;
    // v1.1.0 实测三百：烧木材开关（默认关——木材类默认黑名单不烧，勾选后才烧）
    public static final ForgeConfigSpec.BooleanValue MISC_COOK_BURN_WOOD;
    // v1.1.0 实测三百一十一：宰杀任务阈值（同种牲畜超过此数才杀，默认 5）
    public static final ForgeConfigSpec.IntValue MISC_SLAUGHTER_COUNT;
    // v1.1.0 实测三百一十八：宰杀扫描半径（默认 16，旧版硬编码 5×5 扫不到远处牲畜）
    public static final ForgeConfigSpec.IntValue MISC_SLAUGHTER_RADIUS;
    public static final ForgeConfigSpec.IntValue MISC_BUBBLE_LIMIT_MS;
    public static final ForgeConfigSpec.BooleanValue MISC_SCHEDULE_BUBBLE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MISC_SCHEDULE_BUBBLE_RADIUS;
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
    // v1.1.0 实测一百三十四：同维度远距拉回（跨区块传送的补丁——TLM 只拉"非home
    // 非工作"的跟随女仆且传送可能静默失败，这里补统一兜底）
    public static final ForgeConfigSpec.BooleanValue MISC_MAID_SAME_DIM_PULL;
    public static final ForgeConfigSpec.IntValue MISC_MAID_SAME_DIM_DIST;
    // v1.1.0 实测一百八十八（反馈："传送机制不检测 Y 轴。女仆搭得太高不会自己传送下来"）
    public static final ForgeConfigSpec.IntValue MISC_MAID_SAME_DIM_VERTICAL;
    /** v1.1.0 实测七十九：受困救援（下界基岩顶/虚空自动传回主人身边） */
    public static final ForgeConfigSpec.BooleanValue MISC_MAID_RESCUE;
    // v1.1.0 实测一百五十一：跟随收紧（每 tick 重断言跟随目标，平常跟随在 4 格内）
    public static final ForgeConfigSpec.BooleanValue MISC_FOLLOW_TIGHTEN;
    // v1.1.0 实测一百五十二：有增益也喂牛奶（很多装备/饰品带永久增益，旧版"无增益才喝"导致中毒/凋零也不解）
    public static final ForgeConfigSpec.BooleanValue MISC_MILK_FEED_WITH_BUFF;
    /** v1.1.0 实测八十九：寻路危险方块避让（女仆寻路绕开岩浆/火等） */
    public static final ForgeConfigSpec.BooleanValue MISC_DANGER_AVOID;
    /** v1.1.0 实测八十九：危险方块表（注册名列表，可增删） */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MISC_DANGER_BLOCKS;
    /** v1.1.0 实测九十：险境脱离——已身处危险方块上时自动挪到最近安全格 */
    public static final ForgeConfigSpec.BooleanValue MISC_DANGER_ESCAPE;
    // v1.1.0 实测九十四：运行日志总开关（游戏目录/logs/promaid.log）
    public static final ForgeConfigSpec.BooleanValue MISC_LOG_ENABLED;
    // v1.5.161：农场连锁收获 / 收获物自动收集（默认关闭）
    public static final ForgeConfigSpec.BooleanValue MISC_CHAIN_HARVEST;
    public static final ForgeConfigSpec.BooleanValue MISC_AUTO_COLLECT;
    // v1.5.163：农场连锁收获数量上限
    public static final ForgeConfigSpec.IntValue MISC_CHAIN_HARVEST_LIMIT;
    /** v1.1.0 实测二百三十四：女仆手持光源发实光（隐藏光块跟随）总开关 */
    public static final ForgeConfigSpec.BooleanValue MISC_HELD_LIGHT_ENABLED;
    // v1.5.236：农场批量种植 / 上限（与连锁收获同格式）
    public static final ForgeConfigSpec.BooleanValue MISC_BATCH_PLANT;
    public static final ForgeConfigSpec.IntValue MISC_BATCH_PLANT_LIMIT;
    // v1.1.0 实测三百五十二：树苗骨粉催熟（伐木女仆背包有骨粉时对身边树苗催熟）
    public static final ForgeConfigSpec.BooleanValue MISC_MAID_BONEMEAL_SAPLING;
    // v1.1.0 实测三百五十五：农场作物骨粉催熟（农场女仆背包有骨粉时对身边未成熟作物催熟）
    public static final ForgeConfigSpec.BooleanValue MISC_MAID_BONEMEAL_FARM;
    // v1.1.0：排班表系统全局开关（关闭后排班调度器停摆——已保存的日程保留，重开恢复）
    public static final ForgeConfigSpec.BooleanValue MISC_SCHEDULE_ENABLED;
    // v1.1.0 实测一百三十三：排班切换三件套（可用性检测 / 反向抑制）
    public static final ForgeConfigSpec.BooleanValue MISC_SCHEDULE_AVAILABILITY_CHECK;
    public static final ForgeConfigSpec.IntValue MISC_SCHEDULE_REVERSE_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue MISC_SCHEDULE_REVERSE_THRESHOLD;
    public static final ForgeConfigSpec.IntValue MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS;
    // v1.1.0 实测一百七十六（移植 TLM-Sincerely MaidSwitchState.canSwitchNormally）：排班最短持有期
    public static final ForgeConfigSpec.IntValue MISC_SCHEDULE_MIN_HOLD_TICKS;
    // v1.1.0 实测一百七十六（移植 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）：切段后大脑自愈
    public static final ForgeConfigSpec.BooleanValue MISC_SCHEDULE_FORCE_BRAIN_REFRESH;
    // v1.1.0 实测一百八十三（反馈："排班状态下增大活动的范围"）：排班/home 模式活动半径下限
    public static final ForgeConfigSpec.IntValue SCHEDULE_ACTIVITY_RANGE;

    // ================= 语音（v1.5.198） =================
    public static final ForgeConfigSpec.DoubleValue TTS_VOLUME_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue TTS_SYSTEM_ENABLED;
    public static final ForgeConfigSpec.IntValue TTS_SYSTEM_COOLDOWN_S;
    public static final ForgeConfigSpec.BooleanValue TTS_VOICE_PACK_ENABLED;
    public static final ForgeConfigSpec.IntValue TTS_CACHE_MAX_FILES;
    // v1.1.0 实测四百二十：内置日语语音包（随 jar 分发、最高优先级、可调音量/间隔/压原生）
    public static final ForgeConfigSpec.BooleanValue TTS_JAR_PACK_ENABLED;
    public static final ForgeConfigSpec.DoubleValue TTS_JAR_PACK_VOLUME;
    public static final ForgeConfigSpec.IntValue TTS_JAR_PACK_MIN_INTERVAL_S;
    public static final ForgeConfigSpec.BooleanValue TTS_JAR_PACK_MUTE_NATIVE;

    public static final ForgeConfigSpec SPEC;

    static {
        // ---- 建造 ----
        BUILDER.comment("建造系统设置").translation("config.promaid.build").push("build");
        BUILD_SPEED_TIER = BUILDER.comment("建造速度档位：x1 / x1.5 / x3")
                .translation("config.promaid.build.speedTier")
                .define("speedTier", "x1",
                        o -> o instanceof String s && (s.equals("x1") || s.equals("x1.5") || s.equals("x3")));
        // v1.2.0 实测五百五十七：极速模式默认由【开】改【关】——旧默认下所有新档一上来就是
        // "吃满服务器上限"（1427 块/秒），既看不出建造过程也白烧性能；默认回到 ×1。
        BUILD_TURBO = BUILDER.comment("极速模式（吃满服务器上限，性能风险）")
                .translation("config.promaid.build.turbo").define("turbo", false);
        // v1.2.0 实测五百五十七：迁移标记（内部，一次性）——上面这条默认值改了以后，
        // 老存档的 toml 里已经写着 turbo = true（那是旧默认，不是玩家选的），只凭值分不出来；
        // 所以用这个标记把"迁移只做一次"钉死：跑过之后玩家再手动打开极速就不会被改回去。
        BUILD_SPEED_MIGRATED = BUILDER.comment("内部标记：建造默认速度迁移（极速→关、x1.5→x1）是否已执行；一次性，请勿手动修改")
                .translation("config.promaid.build.speedMigrated").define("speedMigrated", false);
        // v1.2.2 实测五百六十二：同款一次性标记——scheduleActivityRange 旧默认 32 写在
        // 老档 toml 里，只凭值分不出"旧默认"和"玩家就要 32"，用标记钉死只迁一次
        SCHEDULE_RANGE_MIGRATED = BUILDER.comment("内部标记：排班活动半径默认迁移（32→12）是否已执行；一次性，请勿手动修改")
                .translation("config.promaid.misc.scheduleRangeMigrated").define("scheduleRangeMigrated", false);
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
        // v1.2.0：指标石（临时蓝图制作器）——右击方块锁定（绿→红）→ 右击女仆绑定
        // → 两点之间的空气格组成临时蓝图，女仆立刻用背包/主人背包里数量最多的
        // 可搭方块逐格填充（材料不固定，搭路同款取材规则）
        BUILD_INDEX_STONE = BUILDER.comment("指标石（默认开）：手持指标石右击方块锁定（绿→红，可锁很远）→ 右击你的女仆绑定 → 从女仆所在格到锁定格之间的空气方块组成临时蓝图，她立刻从自己背包（不够从你背包）取材料逐格填充，材料不固定（数量最多者优先、必须有碰撞）。关闭后指标石退化为普通物品")
                .translation("config.promaid.build.indexStone").define("indexStone", true);
        // v1.5.316：红石机器改革开关——机器专属搭建顺序 + 活建造（去禁锢）
        BUILD_MACHINE_SMART = BUILDER.comment("红石机器专属搭建（v1.5.316 改革）：机器按红石拓扑分层放置（结构→惰性机构→活动件→传感→动力源→TNT，动力源最后落位）+ 活建造（红石/水流随放随算），机器建好即自然运行；轰炸机类完工自动放矿车启动。关 = 回退旧行为（常规顺序+静默放置+完工唤醒）")
                .translation("config.promaid.build.machineSmart").define("machineSmart", true);
        // v1.2.2 实测五百八十五（issue #15）：流体保留判据——旧版只看文件名关键词，
        // 玩家改个文件名（或图纸本来就不含关键词的刷石机/熔炉组）就从"能跑"变"坏的"，
        // 而且剥离时完全静默（建好了机器一动不动、没有日志没有提示）。
        BUILD_KEEP_FLUIDS = BUILDER.comment("图纸流体保留（水/岩浆）：auto = 机器图纸保留（文件名含机器关键词，或图纸里有 >=3 种红石机器件）、普通建筑剥离；always = 任何图纸都按图纸建水/岩浆；never = 一律剥离。剥离时会在日志与建造开始时写明剥掉了多少格，并提示怎么改成保留。普通建筑保留流体有淹水/岩浆事故风险，默认 auto")
                .translation("config.promaid.build.keepFluids")
                .define("keepFluids", "auto",
                        o -> o instanceof String s && (s.equals("auto")
                                || s.equals("always") || s.equals("never")));
        // v1.2.2 实测五百八十六（issue #14）：缺料同类宽松——建筑要外观严格，机器只要功能
        BUILD_LOOSE_MATERIALS = BUILDER.comment("缺料同类宽松：off = 只认同族表（外观优先，旧行为）；machine = 只对机器蓝图放宽（机器里告示牌/树叶/羊毛/染色玻璃只是功能件）；always = 所有图纸都放宽。放宽范围：任意告示牌↔任意告示牌、任意树叶↔任意树叶、任意羊毛/地毯↔同类型、任意染色玻璃/玻璃板↔同类型（颜色/树种不再严格）")
                .translation("config.promaid.build.looseMaterials")
                .define("looseMaterials", "machine",
                        o -> o instanceof String s && (s.equals("off")
                                || s.equals("machine") || s.equals("always")));
        // v1.5.331：TNT 点火保护期（秒）——建造期/完工激活期/宽限期内压制一切 TNT
        // 点火（放置/活塞推动/邻居更新），防"刚建好炸膛"（天机屠龙炮：观察者→活塞
        // 推 TNT 链在完工瞬间触发）；完工点火结算只点燃邻接带电的 TNT（轰炸机当场
        // 启动），期满后机器按正常红石逻辑点火。0 = 关闭保护（回到 1.5.328 行为）
        BUILD_TNT_IGNITION_GRACE = BUILDER.comment("TNT 点火保护期（秒，默认 120）：建造期+完工激活期+宽限期内压制一切 TNT 点火（放置/活塞推动/邻居更新），防机器'刚建好炸膛'（天机屠龙炮等观察者→活塞推 TNT 的机器）；完工点火结算只点燃邻接带电的 TNT（轰炸机当场启动），期满后机器按正常红石逻辑点火。0 = 关闭保护")
                .translation("config.promaid.build.tntIgnitionGrace").defineInRange("tntIgnitionGrace", 120, 0, 3600);
        // v1.1.0 实测八十二：蓝图投影——只有区块框不好确认建筑朝向/形状
        BUILD_PROJECTION = BUILDER.comment("蓝图投影预览：「区块显示」与建造中区块叠加半透明幽灵方块轮廓（外壳抽稀采样，确认建筑朝向/形状）；关闭则只显示区块框")
                .translation("config.promaid.build.projection").define("projection", true);
        // v1.2.0 实测五百五十三③：区块内容器取料
        BUILD_FETCH_FROM_CHESTS = BUILDER.comment("从箱子取材料（默认开）：建造缺料时，女仆会去**建造区块内**的箱子/桶/潜影箱取该材料（走过去 + 开箱动画），取完回工地继续盖")
                .translation("config.promaid.build.fetchFromChests").define("fetchFromChests", true);
        BUILD_CHEST_SEARCH_MARGIN = BUILDER.comment("取料扫描外扩（格，默认 4）：在建造区块边界外再向外找几格的容器；0 = 只扫区块本身")
                .translation("config.promaid.build.chestSearchMargin")
                .defineInRange("chestSearchMargin", 4, 0, 16);
        BUILD_CHEST_FETCH_PER_TAKE = BUILDER.comment("每趟每格容器取多少（个，默认 8）")
                .translation("config.promaid.build.chestFetchPerTake")
                .defineInRange("chestFetchPerTake", 8, 1, 64);
        BUILD_CHEST_FETCH_COOLDOWN = BUILDER.comment("取料冷却（tick，默认 60）：一趟取完回工地后，至少隔这么久才会再去取下一次（防箱子被锁/取不到时来回跑）")
                .translation("config.promaid.build.chestFetchCooldown")
                .defineInRange("chestFetchCooldown", 60, 10, 1200);
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
        // v1.5.275：两格再分竖/横 + 无碰撞方块单独表（反馈："横着高的两格和竖着的两格不一样；无碰撞方块单独画一个区"）
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
                .defineInRange("structureMaxVolume", 16777216, 100000, 67108864);
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
        // v1.1.0 实测七十二（反馈："矿洞里一直往下打洞"）：预算重新计入实心
        // 可开路方块（石头/泥土），曾把默认从 22 降为 6；二百零六 按玩家当前配置
        // 同步回 22（玩家实值）
        MINE_BREAK_BUDGET = BUILDER.comment("穿透预算（默认 22）：选矿时统计女仆到矿之间要穿过多少层实心方块（含石头/泥土等可开路的），超过预算的矿不选——走近了会重新评估；调大=更爱穿墙打隧道，调小=只挑眼前暴露的矿")
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
        MINE_MOVE_SPEED = BUILDER.comment("发现矿物后的移动速度（v1.5.118 起 0.6 = TLM 伐木任务同款移速（IFarmTask 实测 0.6f），走路观感自然；v1.5.111 曾 0.4：旧值偏快（每秒 17+ 格狂奔），搭高时女仆直接冲出柱子范围；0.4 又偏慢像爬行）")
                .translation("config.promaid.mine.moveSpeed")
                .defineInRange("moveSpeed", 0.6, 0.2, 1.5);
        MINE_JUNK_KEEP = BUILDER.comment("废石保留量（每种超出销毁）")
                .translation("config.promaid.mine.junkKeep").defineInRange("junkKeep", 32, 4, 128);
        MINE_PLACED_LIFETIME = BUILDER.comment("搭方块自动清理时间（秒）")
                .translation("config.promaid.mine.placedLifetime").defineInRange("placedLifetime", 10, 3, 60);
        // v1.1.0 实测二十七：默认开启——软方块（徒手可挖）不磨损镐，与伐木一致。
        // v1.5.138 曾改 false（反馈"挖矿不消耗耐久"），实测二十七按新需求改回。
        MINE_SOFT_NO_DURABILITY = BUILDER.comment("软方块（徒手可挖）开路不消耗镐耐久（默认开——与伐木一致）")
                .translation("config.promaid.mine.softNoDurability").define("softNoDurability", true);
        MINE_PILLAR_GUARD = BUILDER.comment("搭方块防掉落（潜行效果，速度不变）")
                .translation("config.promaid.mine.pillarGuard").define("pillarGuard", true);
        MINE_HARD_BLOCK_REPORT = BUILDER.comment("硬挡路（箱子/机器等）报点弃置该矿")
                .translation("config.promaid.mine.hardBlockReport").define("hardBlockReport", true);
        MINE_CREATIVE_DEFAULT_VALUE = BUILDER.comment("创造面板添加矿物的默认价值")
                .translation("config.promaid.mine.creativeDefaultValue")
                .defineInRange("creativeDefaultValue", 300, 10, 1000);
        MINE_SEEK_THROUGH_WALLS = BUILDER.comment("透视感知（隔墙找矿，默认关）：开启后女仆能发现视线被方块挡住的矿物并挖通开路；关闭（默认）后像玩家一样只能发现视线无阻的矿物——除水/岩浆外任何方块都挡视线，被挡的矿不可见、不报点，也不会隔墙挖穿；已经看得见的矿，身前有可挖障碍物照常挖穿开路")
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
        // v1.1.0 实测一百五十六：骑乘中禁止搭方块（扫帚上挖矿不再垫方块）
        MINE_RIDE_NO_PILLAR = BUILDER.comment("骑乘中禁止搭方块（默认开）：女仆骑乘中（扫帚等载具）执行挖矿模式时不再垫方块搭高/搭桥——骑乘移动由载具控制，垫方块只会留一堆残渣；关闭 = 旧行为（骑乘也照常搭）")
                .translation("config.promaid.mine.rideNoPillar").define("rideNoPillar", true);
        MINE_JUNK_CHECK_INTERVAL = BUILDER.comment("废石清理检查间隔（tick）")
                .translation("config.promaid.mine.junkCheckInterval")
                .defineInRange("junkCheckInterval", 100, 20, 400);
        // v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描分帧执行
        MINE_SCAN_BUDGET = BUILDER.comment("挖矿扫描预算（格/tick，默认 4096）：全量扫描矿框改为分帧执行——每 tick 最多检查这么多格，剩余下 tick 继续（扫完前女仆短暂无目标）；调小更不卡服但找矿变慢，调大找矿快但单 tick 尖峰高")
                .translation("config.promaid.mine.scanBudget").defineInRange("scanBudget", 4096, 256, 65536);
        MINE_SKIP_REPORT_INTERVAL = BUILDER.comment("跳过矿/捡不到掉落播报间隔（tick，防刷屏）")
                .translation("config.promaid.mine.skipReportInterval")
                .defineInRange("skipReportInterval", 600, 100, 2400);
        // v1.5.161：进阶挖矿——连锁采集 / 自动收集（自动收集默认关闭；连锁采集
        // 二百零六 按玩家当前配置同步为默认开）
        MINE_CHAIN_MINING = BUILDER.comment("连锁采集（默认开）：挖矿时自动连锁挖掘相连的同族矿石——矿脉一次挖完")
                .translation("config.promaid.mine.chainMining").define("chainMining", true);
        MINE_AUTO_COLLECT = BUILDER.comment("自动收集（挖掘掉落物直接进女仆背包，不进世界；背包放不下才落地）")
                .translation("config.promaid.mine.autoCollect").define("autoCollect", false);
        // v1.5.163：连锁采集数量上限可自定义
        MINE_CHAIN_LIMIT = BUILDER.comment("连锁采集上限（块）：一次连锁挖掘的最大方块数（默认 16）")
                .translation("config.promaid.mine.chainLimit").defineInRange("chainLimit", 16, 4, 64);
        // v1.1.0 实测六十九：发呆看门狗——零进展且原地不动超时自动重置状态
        MINE_STUCK_WATCHDOG = BUILDER.comment("发呆看门狗（默认开）：挖矿期间连续 N 秒既没挖掉任何方块、位置也没挪动（原地发呆/内部状态卡死）时，自动整体重置该女仆的挖矿状态——锚点/扫描缓存/排除表/目标全部清空重新开始，等效收回魂符再放下去，不用玩家手动救；走路赶路、垫方块搭路都算进展，不会误触发")
                .translation("config.promaid.mine.stuckWatchdog").define("stuckWatchdog", true);
        MINE_STUCK_RESET_SECONDS = BUILDER.comment("看门狗判定时长（秒，默认 8，实测发呆出现很快）：连续这么久既没挖掉/垫过方块、也没挪动就整体重置状态。重置不会打断「够不着目标」的超时弃置流程（等待时钟跨重置保留）")
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
        WOOD_MOVE_SPEED = BUILDER.comment("接近木材速度倍率（v1.1.0 实测四十八：0.6→0.3——实测伐木移速至少快一倍，观感像狂奔；0.3 = 挖矿同款基础的一半，悠闲走向下一棵树）")
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
        // v1.1.0 实测四十一（反馈："隔墙找木材视线感知默认打开——增加容错率"）：
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
                // v1.1.0 实测五十四：4→2；实测二百一十五：默认回到 4——连续高速垫块
                // 容易失足摔死，与搭路节奏（4 tick）统一，只比玩家手速略快一点点
                .defineInRange("pillarCooldown", 4, 1, 20);
        // v1.1.0 实测二百二十八（反馈："种树 CD 差不多五秒左右，可以在伐木面板调"）：
        // 随手种树——独立模块（MaidPlanting），触发 = 伐木模式（伐木行为每 20 tick 调起）
        // v1.1.0 实测二百二十九（反馈："是否能够种树也是有个开关的，默认开启"）：总开关
        WOOD_PLANT_SAPLING_ENABLED = BUILDER.comment("随手种树（默认开）：她手上有树苗、附近（半径 6 格）有可种土块时随手种一棵（触发 = 伐木模式，独立模块）；关闭 = 只砍树不种树（树苗留在背包/地上）")
                .translation("config.promaid.wood.plantSaplingEnabled").define("plantSaplingEnabled", true);
        WOOD_PLANT_SAPLING_COOLDOWN = BUILDER.comment("补种树苗冷却（tick，默认 100≈5 秒）：她手上有树苗、附近（半径 6 格）有可种土块时随手种一棵，两次种植最短间隔；调小种得更勤（树苗消耗也更快）")
                .translation("config.promaid.wood.plantSaplingCooldown").defineInRange("plantSaplingCooldown", 100, 20, 600);
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
        WOOD_STUCK_RESET_SECONDS = BUILDER.comment("看门狗判定时长（秒，默认 8）：连续这么久既没砍掉/垫过方块、也没挪动就整体重置状态。重置不会打断「够不着目标」的超时弃置流程（等待时钟跨重置保留）")
                .translation("config.promaid.wood.stuckResetSeconds").defineInRange("stuckResetSeconds", 8, 3, 300);
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
                .defineInRange("lookTicks", 3, 1, 30);
        PERCEPTION_LOOK_ENTER_DEG = BUILDER.comment("看向进入角度（度）")
                .translation("config.promaid.perception.lookEnterDeg")
                .defineInRange("lookEnterDeg", 35.0, 10.0, 80.0);
        PERCEPTION_LOOK_EXIT_DEG = BUILDER.comment("看向退出角度（度）")
                .translation("config.promaid.perception.lookExitDeg")
                .defineInRange("lookExitDeg", 55.0, 20.0, 90.0);
        BUILDER.pop();

        // ---- 情绪（v1.5.95 新段：PAD 情绪层）----
        BUILDER.comment("情绪设置（PAD 情绪层，独立于 TLM 好感等既有数值）")
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
        // v1.2.2 实测五百七十八：指挥三件套 + 状态自检（让模型能真正换模式/起飞/定工位/先查后做）
        TOOL_SWITCH_TASK = BUILDER.comment("smart_switch_task 工具（切换任务/工作模式——TLM 原生 + 本模组任务全可切，支持空袭/挖矿/砍树/建造/攻击/待命等中文别名；排班中的女仆拒绝外部指派）")
                .translation("config.promaid.aitools.switchTask").define("switchTask", true);
        TOOL_AIR_RAID = BUILDER.comment("smart_air_raid 工具（起飞空袭/停止空袭——可指定近战或远程、锁定目标，并回报\"缺不缺件\"）")
                .translation("config.promaid.aitools.airRaid").define("airRaid", true);
        TOOL_WORK_AREA = BUILDER.comment("smart_work_area 工具（把工作区设到此处并驻守 / 解除驻守恢复跟随 / 查看锚点——写的是与中键工位标记同一份 SchedulePos 数据）")
                .translation("config.promaid.aitools.workArea").define("workArea", true);
        TOOL_READINESS = BUILDER.comment("smart_readiness 工具（只读自检——空袭三件套/弹药/驻守/排班/工作锚点/血量，让模型\"先查后做\"）")
                .translation("config.promaid.aitools.readiness").define("readiness", true);
        BUILDER.pop();

        // ---- 对话与提示 ----
        BUILDER.comment("对话与提示设置").translation("config.promaid.dialogue").push("dialogue");
        DIALOGUE_STATUS_REPORTER = BUILDER.comment("工作状态播报（女仆卡住时气泡解释原因）")
                .translation("config.promaid.dialogue.statusReporter").define("statusReporter", true);
        DIALOGUE_REPORT_INTERVAL = BUILDER.comment("工作播报间隔（秒，默认 10）：女仆卡住时气泡播报的最短间隔，防刷屏")
                .translation("config.promaid.dialogue.reportInterval").defineInRange("reportInterval", 10, 3, 120);
        DIALOGUE_REPORT_RADIUS = BUILDER.comment("工作播报扫描范围")
                .translation("config.promaid.dialogue.reportRadius").defineInRange("reportRadius", 32, 8, 128);
        DIALOGUE_PROACTIVE = BUILDER.comment("主动对话（关心/夜晚/好感等主动开口）")
                .translation("config.promaid.dialogue.proactive").define("proactive", true);
        DIALOGUE_PROACTIVE_COOLDOWN = BUILDER.comment("两次主动发言最小间隔（分钟）")
                .translation("config.promaid.dialogue.proactiveCooldown").defineInRange("proactiveCooldown", 4, 1, 60);
        DIALOGUE_PROACTIVE_DAILY = BUILDER.comment("主动对话日上限（次，控 token 成本；v1.5.191：4 → 12——7 阶段状态机需要更多发言额度）")
                .translation("config.promaid.dialogue.proactiveDaily").defineInRange("proactiveDaily", 12, 0, 50);
        // v1.1.0 实测一百九十四（反馈："有一些击杀日志时刻显示在我的屏幕上。能去掉吗？"）
        DIALOGUE_PROACTIVE_KILL = BUILDER.comment("击杀邀功对话（默认关）：女仆击杀敌人（主人 16 格内）后主动向主人邀功的 LLM 对话气泡——战斗频繁时击杀就冒一次（时刻刷屏很吵）；开启恢复旧行为")
                .translation("config.promaid.dialogue.proactiveKill").define("proactiveKill", false);
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
                .translation("config.promaid.combat.enterRatio").defineInRange("enterRatio", 0.3, 0.05, 1.0);
        // v1.5.153：默认 0.60→0.70——血量恢复到 70% 及以上无条件解除自保
        // 实测三百六十二：另加"威胁消失 + 血 ≥ safeReturnRatio（0.45）即解除"，
        // 治旧版 30%~70% 灰区里 tag 不清、女仆脱险后仍被各系统让位的干耗
        COMBAT_EXIT_RATIO = BUILDER.comment("自保绝对解除血量（0-1，默认 0.7：血量到此无条件解除自保；另一解除线 = 威胁消失且血量恢复到安全回归血量 safeReturnRatio）")
                .translation("config.promaid.combat.exitRatio").defineInRange("exitRatio", 0.7, 0.1, 1.0);
        COMBAT_THREAT_DISTANCE = BUILDER.comment("威胁感知距离")
                .translation("config.promaid.combat.threatDistance").defineInRange("threatDistance", 12, 4, 32);
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
                .defineInRange("pearlRatio", 0.3, 0.05, 0.5);
        COMBAT_PEARL_DIST = BUILDER.comment("末影珍珠逃生威胁距离（威胁小于此格数才扔珍珠）")
                .translation("config.promaid.combat.pearlDist")
                .defineInRange("pearlDist", 8.0, 2.0, 16.0);
        // 实测三百六十四：默认 0.45→0.70（反馈："低血量和解除线差距太小，改回
        // 70%"——残血自保要真回血才归位）；塔顶没回血资源被围困另有 10 秒接回兜底
        COMBAT_SAFE_RETURN_RATIO = BUILDER.comment("安全回归血量（0-1，默认 0.7：血量恢复到此线即解除自保回归工作/战斗——威胁还在也解除，战斗交还战术；触发血量 0.3 与本线之间为滞回防抖带）")
                .translation("config.promaid.combat.safeReturnRatio")
                .defineInRange("safeReturnRatio", 0.7, 0.2, 0.9);
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
        // 实测三百六十三：逃跑删除——本项现供自保小幅走位（拉开身位）使用
        COMBAT_FLEE_SPEED = BUILDER.comment("走位速度倍率（自保小幅走位拉开身位的移动加成，1.0=正常）")
                .translation("config.promaid.combat.fleeSpeed")
                .defineInRange("fleeSpeed", 1.4, 0.8, 3.0);
        // v1.1.0 实测一百五十三：TLM 火焰保护饰品识别
        COMBAT_FIRE_PROTECT_BAUBLE = BUILDER.comment("火焰保护饰品识别（默认开）：女仆饰品栏佩戴 TLM 火焰保护饰品（火焰伤害免疫+受伤时给 15 秒抗火并喷灭火剂）时，着火/泡岩浆不再惊慌灭火/找水/往主人身边跑——饰品自己会处理；关闭 = 旧行为（着火照常走灭火链路）")
                .translation("config.promaid.combat.fireProtectBauble").define("fireProtectBauble", true);
        // v1.1.0 实测一百五十四：TLM 溺水保护饰品识别
        COMBAT_DROWN_PROTECT_BAUBLE = BUILDER.comment("溺水保护饰品识别（默认开）：女仆饰品栏佩戴 TLM 溺水保护饰品（溺水伤害免疫+空气自动补满）时，泡水不再喊\"溺水\"上浮找空气/喝水肺——饰品每 tick 自己补空气；关闭 = 旧行为（照常上浮）")
                .translation("config.promaid.combat.drownProtectBauble").define("drownProtectBauble", true);
        // v1.1.0 实测一百五十五；实测三百六十三：自保逃跑删除，本项现只管
        // TLM 原生惊慌（PanicGatingMixin）与"情况不妙"播报
        COMBAT_FLEE_WITH_SAVE_ITEM = BUILDER.comment("保命物品下允许惊慌（默认关）：女仆携带保命物品（TLM 绀珠之药=ExtraLifeBauble 死亡复活 / 不死图腾）时是否还惊慌逃窜/喊\"情况不妙\"——默认关 = 不惊慌不喊话（她死不了，继续战斗/垫高/治疗）；开 = 照常。注：自保自身的走位/搭高不受此开关影响")
                .translation("config.promaid.combat.fleeWithSaveItem").define("fleeWithSaveItem", false);
        // 实测四百零二：低血量自动回魂符（参考 maid_survival-1.9.5 MaidSoulSpellGuard）
        // 实测四百零三：触发口径收紧——仅致死伤害且无保命物品时收符（低血量不触发，
        // 否则自保的喝药/搭高/珍珠全成小丑；有绀珠之药/不死图腾让保命物品生效）
        SOUL_SPELL_ENABLE = BUILDER.comment("致死伤害自动回魂符（默认开）：女仆受到一击必杀的伤害且没有保命物品（绀珠之药/不死图腾）时，自动收进主人背包里的空魂符（TLM 魂符）——免去神龛复活；主人需同维度且在半径内、背包有空魂符；成功收符后进入冷却（默认 180 秒），期间不再触发；魂符右键释放时冷却写回女仆，防收放循环")
                .translation("config.promaid.combat.soulSpellEnable").define("soulSpellEnable", true);
        SOUL_SPELL_LETHAL_GUARD = BUILDER.comment("致死伤害保护（默认开）：受到一击必杀的伤害时立即尝试收魂符（成功则取消伤害）——比死亡强；有保命物品时让保命物品生效，不抢收")
                .translation("config.promaid.combat.soulSpellLethalGuard").define("soulSpellLethalGuard", true);
        SOUL_SPELL_OWNER_RADIUS = BUILDER.comment("主人收符半径（格，默认 24）：女仆与主人距离超过此值不自动收符（太远收不了，魂符在主人背包）")
                .translation("config.promaid.combat.soulSpellOwnerRadius")
                .defineInRange("soulSpellOwnerRadius", 24.0, 1.0, 256.0);
        SOUL_SPELL_COOLDOWN_SECONDS = BUILDER.comment("收符冷却（秒，默认 60）：收符后冷却期内不再触发（防\"放出即死→又收又放\"抖振）——实测四百零四：冷却从【释放时刻】重新起算（旧版沿用收符时刻，释放时剩 175 秒导致第二次作战必死不收）")
                .translation("config.promaid.combat.soulSpellCooldownSeconds")
                .defineInRange("soulSpellCooldownSeconds", 60, 0, 86400);
        // 实测四百一十六：女仆自动复活（反馈："女仆死亡后 60 秒那个墓碑就会自己消失掉，
        // 然后在主人的出生点复活，也是 60 秒的 CD"）
        AUTO_RESURRECT_ENABLE = BUILDER.comment("女仆自动复活（默认开）：女仆死亡后墓碑在延迟时间到期时自动消失，女仆在主人重生点（床/重生锚）满血或按比例复活——不再需要手动去墓碑处取回；**重生点不可用**（床被拆/重生锚没电/维度不允许/从没设过）时直接在**主人所在位置**复活（强制生效、不看地形，主人在高空/岩浆边也照落）；关掉恢复 TLM 原版死亡流程")
                .translation("config.promaid.combat.autoResurrectEnable").define("autoResurrectEnable", true);
        AUTO_RESURRECT_DELAY_SECONDS = BUILDER.comment("复活延迟（秒，默认 60）：死亡后墓碑存在这么久才自动消失并复活女仆（也是墓碑存在的时长）")
                .translation("config.promaid.combat.autoResurrectDelaySeconds")
                .defineInRange("autoResurrectDelaySeconds", 60, 1, 86400);
        AUTO_RESURRECT_HEALTH_RATIO = BUILDER.comment("复活血量比（默认 1.0 = 满血）：复活时女仆恢复的血量比例（0.35 = 35%）")
                .translation("config.promaid.combat.autoResurrectHealthRatio")
                .defineInRange("autoResurrectHealthRatio", 1.0, 0.05, 1.0);
        // 实测四百二十六：复活时机（照驯养革新宠物床：0=延迟秒；1=次日黎明 dayTime≈1）
        AUTO_RESURRECT_TIMING = BUILDER.comment("复活时机（0 = 延迟秒后复活，用上面的「复活延迟（秒）」；1 = 次日黎明复活，照驯养革新宠物床 dayTime 到 1 才复活）：两种都保留——右键墓碑可随时立即复活，不受本项影响")
                .translation("config.promaid.combat.autoResurrectTiming")
                .defineInRange("autoResurrectTiming", 0, 0, 1);
        COMBAT_STUCK_WINDOW = BUILDER.comment("卡住判定窗口（tick）")
                .translation("config.promaid.combat.stuckWindow")
                .defineInRange("stuckWindow", 20, 5, 100);
        COMBAT_STUCK_THRESHOLD = BUILDER.comment("卡住位移阈值（格）")
                .translation("config.promaid.combat.stuckThreshold")
                .defineInRange("stuckThreshold", 0.3, 0.05, 1.0);
        COMBAT_THREAT_GONE_EXIT = BUILDER.comment("威胁消失退出时长（tick，400=20 秒：威胁消失后观察 20 秒确认安全才结束自保/传回主人身边）")
                .translation("config.promaid.combat.threatGoneExit")
                .defineInRange("threatGoneExit", 400, 40, 1200);
        // 实测三百六十二：语义重定义——本项 = 【成功】传送后的冷却（默认 600=30 秒，
        // 一场遭遇战最多被接走一次，根治"传回→跑回去→再传"连传循环）；
        // 传送失败（主人身边有怪/无落点）的重试间隔固定 5 秒，不随本项
        COMBAT_TELEPORT_COOLDOWN = BUILDER.comment("传送回家成功冷却（tick，默认 600 = 30 秒：成功传送后此冷却内不再传，一场遭遇战最多被接走一次；传送失败 5 秒后即重试，不随本项）")
                .translation("config.promaid.combat.teleportCooldown")
                .defineInRange("teleportCooldown", 600, 100, 6000);
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
                .defineInRange("waterHold", 5, 5, 100);
        COMBAT_WATER_LANDING_SCAN = BUILDER.comment("落地水下探格数（提前放水检测）")
                .translation("config.promaid.combat.waterLandingScan")
                .defineInRange("waterLandingScan", 2, 2, 16);
        // v1.1.0：落地雪——细雪桶版落地水（下界水会蒸发细雪不会；细雪接触 7 秒才开始
        // 冻伤，保持时长上限 100 tick 远低于冻伤线 140 tick）
        COMBAT_SNOW_CLUTCH = BUILDER.comment("落地雪（细雪桶版落地水，默认开）：高空坠落时在【落点平面】铺 1×1 细雪垫接住她并收回（桶不消耗）——细雪不流动、落点必须正好是雪：1×1 无容错，能否接住全靠坠落途中逐 tick 跟着落点补垫（落点预测偏一格即空摔，追求稳请用水桶）；绝不在高处拦她减速（出雪后剩下的路照样摔）；下界也能用（水会瞬间蒸发、细雪不会）；触发高度/保持时长/下探格数各自独立可调（见下方三项），两者都有桶时优先用水")
                .translation("config.promaid.combat.snowClutch").define("snowClutch", true);
        // v1.2.0：落地雪独立数值（旧版借用水的三项；默认与落地水一致，保持旧行为）
        COMBAT_SNOW_FALL_DISTANCE = BUILDER.comment("落地雪触发高度（格，默认 4）：累计坠落高度超过此值才铺雪垫缓冲")
                .translation("config.promaid.combat.snowFallDistance").defineInRange("snowFallDistance", 4.0, 2.0, 20.0);
        COMBAT_SNOW_HOLD = BUILDER.comment("落地雪保持时长（tick，默认 5）：铺出的细雪保留多久后收回（上限 100 tick = 5 秒 < 细雪冻伤线 140 tick——安全）")
                .translation("config.promaid.combat.snowHold")
                .defineInRange("snowHold", 5, 5, 100);
        COMBAT_SNOW_LANDING_SCAN = BUILDER.comment("落地雪下探格数（默认 2）：提前向下探测几格判断要不要铺雪垫（防高空误放）")
                .translation("config.promaid.combat.snowLandingScan")
                .defineInRange("snowLandingScan", 2, 2, 16);
        // v1.5.134：单兵作战战术（v1.5.132 战斗协同已删除——协同不如单兵 PVP 操作感）
        COMBAT_TACTICS = BUILDER.comment("单兵作战战术（绕圈走位/打退拉扯/距离控制/时机举盾——PVP 式战斗）")
                .translation("config.promaid.combat.tactics").define("tactics", true);
        COMBAT_TACTICS_MELEE = BUILDER.comment("近战战术（贴脸绕圈、打一刀退一步、跳劈接近）")
                .translation("config.promaid.combat.tacticsMelee").define("tacticsMelee", true);
        COMBAT_TACTICS_RANGED = BUILDER.comment("远程战术（保持理想射程、横移绕圈风筝）")
                .translation("config.promaid.combat.tacticsRanged").define("tacticsRanged", true);
        // 实测四百零一：高地狙击已整体移除（定夺）——配置项一并删除
        COMBAT_TACTICS_SHIELD = BUILDER.comment("时机举盾（攻击冷却间隙举盾格挡、攻防交替；替代原版一直举盾）")
                .translation("config.promaid.combat.tacticsShield").define("tacticsShield", true);
        COMBAT_TACTICS_ORBIT_RADIUS = BUILDER.comment("绕圈半径（格）：近战贴脸绕圈 / 远程横移的圆周半径")
                .translation("config.promaid.combat.tacticsOrbitRadius")
                .defineInRange("tacticsOrbitRadius", 2.2, 1.2, 4.0);
        COMBAT_TACTICS_KITE_RANGE = BUILDER.comment("远程理想射程倍率（0.6 = 保持在最大射程 60% 的距离放风筝）")
                .translation("config.promaid.combat.tacticsKiteRange")
                .defineInRange("tacticsKiteRange", 0.6, 0.3, 0.9);
        // v1.5.280：近战贴脸后退——反馈："战斗状态且非自保状态下,即使是近战武器也应该
        // 尝试与敌人稍微拉开距离,而不是贴身搏斗……周围两格内有敌人时会自己往后退远离"
        COMBAT_TACTICS_MELEE_KITE = BUILDER.comment("近战贴脸后退（敌人贴进 2 格内主动后退拉开距离，女仆手长 3 格仍能挥砍）")
                .translation("config.promaid.combat.tacticsMeleeKite").define("tacticsMeleeKite", true);
        // v1.5.189：玩家贴身辅助（被动技能，非工作状态——女仆随时照看主人）
        AID_OWNER_ENABLE = BUILDER.comment("自动投喂/治疗主人（被动：主人饿/血低自动喂食或投掷治疗药水）")
                .translation("config.promaid.combat.aidOwnerEnable").define("aidOwnerEnable", true);
        // v1.1.0：女仆互助开关——同主人、16 格内的姐妹低血/着火/负面效果时，
        // 从自己背包取药水/食物支援她（默认开；只影响女仆↔女仆，主人链不受影响）
        AID_MAID_MUTUAL = BUILDER.comment("女仆之间互相支援（默认开）：同主人、16 格内的其他女仆低血/着火/中毒时，自动投药水/金苹果/喂食支援她（与支援主人同一套方案）；关闭 = 女仆只管主人、不互相支援")
                .translation("config.promaid.combat.aidMaidMutual").define("aidMaidMutual", true);
        // v1.5.301：范围上限 18 → 20——旧版注释写"0-20"但 defineInRange 上限 18：
        // 面板填 20 被 Forge 静默钳制回 18（输入框显示 20、实际生效 18），
        // 饱食度 18~19 时永远不喂（反馈："那个修改按键要真实有效"——测试调 20
        // 只为确认"只要不满就喂"）
        AID_FOOD_THRESHOLD = BUILDER.comment("投喂触发饱食度（4-20：主人饱食度低于此值自动喂食；20=只要不满就喂）")
                .translation("config.promaid.combat.aidFoodThreshold").defineInRange("aidFoodThreshold", 12, 4, 20);
        // v1.2.0 实测五百一十九（反馈："女仆的喂食功能可以喂其他mod的食物吗？检测背包中是否有能够
        // 喂食的食物的时候有没有跳过模组食物？这个提醒是只判定原版食物吗，往女仆背包里塞一堆三明治
        // 疯狂跳没食物"）：投喂判定从【21 项硬编码原版白名单】改为【通用判定 + 黑名单】——
        // 与 TLM 自己的 DefaultMaidHealSelfMeal.isHealMeal 同口径（那是"有 FoodProperties 且不在
        // 黑名单"），模组食物（三明治等）现在也能喂，可吃但有害的用本黑名单排除。
        // 默认黑名单 = 腐肉/蜘蛛眼/毒马铃薯/河豚/紫颂果/可疑炖菜 + 全部生食（生鸡肉有中毒概率）。
        // v1.2.0 实测五百二十五：补上【不祥之瓶】（1.21 才有这个物品；1.20.1 注册表里不存在，
        // 留在这张表里是空操作，只为两端配置默认值保持一致）。它在 1.21 带食物组件、能"喝"，
        // 于是被通用判定当成普通食物，喂下去等于给女仆挂【不祥之兆】——玩家喂她是为了回饱食度，
        // 不该顺手给她上一层负面标记，故默认拉黑。
        AID_FOOD_BLACKLIST = BUILDER.comment("投喂食物黑名单（完整注册名，逗号分隔；留空 = 只按\"能吃\"判定，所有食物可喂）。"
                + "\n\n【剩余次数细分（实测五百八十）】能喂好几次的物品（水壶这类耐久容器）可以按剩余次数分开禁："
                + "条目写成「完整注册名#剩余次数」（例：mod:water_canteen#1 = 只剩 1 次的别喂），"
                + "不带 # 的裸注册名 = 该物品**任意剩余次数**都不能喂（旧配置语义不变）。")
                .translation("config.promaid.combat.aidFoodBlacklist")
                .defineList("aidFoodBlacklist", java.util.List.of(
                        "minecraft:rotten_flesh",
                        "minecraft:spider_eye",
                        "minecraft:poisonous_potato",
                        "minecraft:pufferfish",
                        "minecraft:chorus_fruit",
                        "minecraft:suspicious_stew",
                        "minecraft:beef",
                        "minecraft:porkchop",
                        "minecraft:chicken",
                        "minecraft:mutton",
                        "minecraft:rabbit",
                        "minecraft:cod",
                        "minecraft:salmon",
                        "minecraft:tropical_fish",
                        "minecraft:golden_apple",
                        "minecraft:enchanted_golden_apple",
                        "minecraft:ominous_bottle"), o -> o instanceof String s && !s.isEmpty());
        // ── 实测五百七十一：喂水配置（软联动「口渴」Thirst Was Taken）——
        // 构建期判定：模组在 → 注册；不在 → 保持 null。面板（贴身辅助小节）同步按
        // ThirstCompat.available() 条件渲染，toml 里也不会出现这两项。
        if (thirstModLoaded()) {
            AID_THIRST_THRESHOLD = BUILDER.comment("投喂触发口渴度（4-20：主人口渴值低于此值自动喂水；需装「口渴」Thirst Was Taken。判定位点 = 口渴值，效果与玩家自己喝一致——模组的口渴/纯度结算与玻璃瓶等容器返还都走原版喝的路径）")
                    .translation("config.promaid.combat.aidThirstThreshold").defineInRange("aidThirstThreshold", 15, 4, 20);
            AID_DRINK_MIN_PURITY = BUILDER.comment("喂水最低水质（0-3，默认 2=可接受的）：喂水时【装水容器】必须达到这个水质等级——口渴模组自己的四档：0 肮脏 / 1 有点脏 / 2 可接受的 / 3 纯净。反馈：如果女仆给玩家喂脏水那么反而会耽误玩家，所以默认只喂可接受的及以上；只对装水容器生效（果汁/牛奶这类没有水质概念的饮品不受影响）；填 0 = 脏水也喂")
                .translation("config.promaid.combat.aidDrinkMinPurity")
                .defineInRange("aidDrinkMinPurity", 2, 0, 3);
        AID_DRINK_WHITELIST = BUILDER.comment("喂水白名单（完整注册名，逗号分隔）：只喂名单里、且「口渴」模组认识（能回口渴值）的饮品——其他 mod 的装水容器把注册名加进来即可；minecraft:potion 只认纯净水（水瓶），其它药水永不喂；留空 = 不喂水。默认：水瓶、陶碗水。"
                + "\n\n【剩余次数细分（实测五百八十）】能喝好几次的容器（水壶这类）可以按剩余次数分开允许："
                + "条目写成「完整注册名#剩余次数」（例：mod:water_canteen#4 = 只喂满的那个），"
                + "不带 # 的裸注册名 = 该物品**任意剩余次数**都算命中（旧配置语义不变）")
                    .translation("config.promaid.combat.aidDrinkWhitelist")
                    .defineList("aidDrinkWhitelist", java.util.List.of(
                            "minecraft:potion",
                            "thirst:terracotta_water_bowl"), o -> o instanceof String s && !s.isEmpty());
        } else {
            AID_THIRST_THRESHOLD = null;
            AID_DRINK_WHITELIST = null;
            AID_DRINK_MIN_PURITY = null;
        }
        AID_HEALTH_THRESHOLD = BUILDER.comment("治疗触发血量（0.1-1：主人血量低于此比例自动治疗；1=掉血就治）")
                .translation("config.promaid.combat.aidHealthThreshold").defineInRange("aidHealthThreshold", 0.3, 0.1, 1.0);
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
        // v1.2.0：飞行作战（1.20.1 版，武器无关）——任务表里的"飞行作战"模式总开关
        COMBAT_FLIGHT_MODE = BUILDER.comment("飞行作战模式（鞘翅 + 任意近战武器 + 烟花火箭：遇到敌人先放烟花给初速，1.5 秒后朝敌人飞，进入 3.5 格取消滑翔自由落体、触底前近战命中结算暴击，命中后切回滑翔再放烟花循环；默认开启）")
                .translation("config.promaid.combat.flightMode").define("flightMode", true);
        // v1.2.0 实测四百六十九：飞行作战免疫"鞘翅撞击伤害"（用户指定"开个后门"，默认开）
        COMBAT_FLIGHT_NO_WALL_DAMAGE = BUILDER.comment("飞行作战免疫鞘翅撞击伤害（默认开）：女仆在飞行作战滑翔中撞到方块不再受到 fly_into_wall 伤害——高速滑翔撞墙在飞行链路里很容易发生，一撞就掉血会打断连招；关闭则恢复原版撞击伤害")
                .translation("config.promaid.combat.flightNoWallDamage").define("flightNoWallDamage", true);
        // v1.2.0 实测四百九十四：空袭免疫摔落伤害
        // v1.2.0 实测五百二十六：**默认关 → 默认开**（用户实测后回头要求"飞行的摔落免疫还是默认开吧"）。
        // 理由：空袭链路本身是"高空盘旋 + 收翅俯冲"，落地缓冲（水/雪）只是兜底，
        // 而兜底失败（背包没桶 / 落点被占 / 被打断 / 水里滑翔分支被顶掉）代价是十几点伤害甚至摔死，
        // 她只有 20 血——那属于"机制没接住"，不该由玩家承担。想按原版吃摔伤随时可关。
        COMBAT_FLIGHT_NO_FALL_DAMAGE = BUILDER.comment("空袭免疫摔落伤害（默认开）：开启后两种空袭模式（近战空袭/远程空袭）下的女仆完全不受摔落伤害——空袭常态是高空盘旋与收翅俯冲，落地水/雪万一没接住（背包没桶、落点被占、被打断）就是十几点伤害甚至摔死；开启本项即彻底免摔。关闭 = 恢复按落地水/雪（与重锤同款特殊落地缓冲）保护")
                .translation("config.promaid.combat.flightNoFallDamage").define("flightNoFallDamage", true);
        // v1.2.0 实测五百三十四：激流三叉戟的旋转突进（用户点名"把玩家的代码套到女仆身上"）
        // v1.2.0 实测五百三十八：从"偶尔多打一下"改成"她的攻击就是旋转冲击"——
        // 触发距离 5 → 10 格（原来 III 级 3 格/tick 只要 2 tick 就撞上，旋转根本看不见）、
        // 突进期间每 tick 顶住标志位（撞人不再提前收招，20 tick 完整放完）、持戟时普通挥砍
        // 被这条链路取代（TLM 原生近战不再出手）。
        RIPTIDE_DASH_ENABLE = BUILDER.comment("激流三叉戟旋转冲击（默认开）：攻击模式 / 空袭下主手拿着【激流】三叉戟时，**她原本那一记普通挥砍会被换成旋转冲击**——近身 4 格内替换（地面要站在地上；空袭的**收翅俯冲那一记在空中也替换**：那一下本来就在空中、实战价值更大），旋转 16 tick（与玩家同款：平躺 + 高速自转），撞到谁就结算一次伤害（攻击力 + 附魔，同一目标每次突进只打一下；空中旋转期间不自我摔伤）；触发时机就是她的攻击时机，走路、索敌、排班等一律不变。默认开——这才是激流这个附魔存在的意义；关闭 = 激流三叉戟只当普通三叉戟挥砍")
                .translation("config.promaid.combat.riptideDash").define("riptideDash", true);
        // v1.2.0 实测五百三十五：普通烟花能否当弩弹药（用户要求"加一下开关"）
        // v1.2.0 实测五百三十七：默认改为【关】。用户实测"烟花火箭竟然一点伤害都没有"，
        // 取证结论：这不是版本差异、也不是他哪里出错，而是原版机制——`FireworkRocketEntity`
        // 的爆炸结算（1.20.1 `m_37087_`、1.21.1 `dealExplosionDamage`）只认
        // `Fireworks.Explosions`：空则伤害恒为 0，只冒烟（`m_37086_` 同样为假，
        // 连撞方块的爆炸都不触发）。所以普通烟花当弹药 = 白烧一枚飞行燃料。
        COMBAT_CROSSBOW_PLAIN_FIREWORK = BUILDER.comment("弩可用普通烟花当弹药（默认关）：普通烟花火箭（合成时没放烟火之星）在原版任何版本都是【0 伤害】——反编译 FireworkRocketEntity 的爆炸结算：伤害 = 5 + 2×爆炸条目数，而爆炸条目为空时整段早退，只冒烟不掉血。拿它当弩弹药等于白烧一枚飞行燃料，所以默认关：只有带烟火之星的【攻击性烟花】才当弩弹药，普通烟花一律留给飞行推进用。打开 = 与原版玩家的弹药判据一致（原版 CrossbowItem 不看有没有爆炸组件），普通烟花也会被打出去（仍然 0 伤害）。两种情况下都会优先挑威力大的（合成用烟火之星多的）")
                .translation("config.promaid.combat.crossbowPlainFirework").define("crossbowPlainFirework", false);
        // v1.2.0 实测五百零三：远程空袭近身弹开（用户指定，默认开）
        COMBAT_FLIGHT_RANGED_PUSH = BUILDER.comment("远程空袭近身弹开（默认开）：怪物贴到 3 格内时，女仆会被施加一个【远离怪物】的速度矢量并保持 1.5 秒，防止她在远程攻击时仍往敌人身上飞、下落途中被贴脸打死。只弹开女仆自己、不弹开怪物——她脱离的同时也就离开了输出位，且在狭小空间（墙角/洞穴）里跑不掉，所以敌人仍有命中机会。关闭 = 恢复旧行为（贴着怪物盘旋）")
                .translation("config.promaid.combat.flightRangedPush").define("flightRangedPush", true);
        // v1.2.0 实测五百四十七：空袭牵引绳（用户指定半径 100 格，0 = 关闭）
        COMBAT_FLIGHT_RECALL_DISTANCE = BUILDER.comment("空袭牵引绳（格，默认 100，0=关闭）：空袭期间以女仆为圆心、半径这么大范围内【找不到主人】（3D 距离，水平+竖直一起算）时，立刻把她传送到主人身边——与排班表的人工传送同一条链路（强制生效、无视地块、可以空中传送）。防的是「她放烟花冲上天、打完目标后主人早已不在脚下，自己回不来」。只在【她确实在空中】时生效：落回地面后交给同维度拉回那套更保守的规则（48 格，且守家/坐姿/干活都有豁免），所以不会把守家站桩的空袭女仆拽走；主人跨维度时也不抢——那一路由跨维度跟随在本轮攻击结束后处理（立刻抢会打断扑击）。触发时会给她主人发一条系统消息（10 秒最多一条）。")
                .translation("config.promaid.combat.flightRecallDistance")
                .defineInRange("flightRecallDistance", 100, 0, 1000);
        // 实测五百六十七（PR #8 移植）：空袭·法术层（需求："用空袭的默认武器的同时进行法术释放"）
        COMBAT_FLIGHT_SPELL_CAST = BUILDER.comment("空袭顺带施法（默认开，需装《车万女仆：万法皆通》touhou_little_maid_spell）：女仆在近战空袭 / 远程空袭途中，除了用默认武器打，还会向当前目标顺带释放法术——法术书放在背包或饰品栏即可（法术模组自己扫背包与 curios，不看主手，所以不占武器位）。施法时机只挑「本来就该面向目标」的两个相位（远战盘旋开火前、近战已在目标上方准备俯冲时）：法术模组在吟唱期间每 tick 把女仆朝向拧向目标，而鞘翅滑翔的转向力来自视线方向，挑这两个时机才不会被抢朝向（爬升段要求背离敌人抬头吃烟花推力、收翅俯冲那一记是致命一击，这两段刻意不施法）。没装法术模组时本项无任何效果。关闭 = 空袭只用手上的武器")
                .translation("config.promaid.combat.flightSpellCast").define("flightSpellCast", true);
        COMBAT_FLIGHT_SPELL_CAST_INTERVAL = BUILDER.comment("空袭施法间隔（tick，默认 20 = 1 秒）：两次发起施法之间的最短间隔。法术模组自己管吟唱时长、法术冷却与「放哪个法术」（随机挑一个不在冷却、不在黑名单的），这一项只管发起节奏——调小 = 法术放得更密、武器退居其次；调大 = 武器为主、法术为辅")
                .translation("config.promaid.combat.flightSpellCastInterval")
                .defineInRange("flightSpellCastInterval", 20, 5, 200);

        // v1.2.0 实测五百七十二：位移法术分两类——"提供高度"（起飞/补高）与"提供速度"（飞行加速）
        COMBAT_FLIGHT_DASH_CLIMB = BUILDER.comment("空袭·位移法术【提供高度】（默认开，需装《车万女仆：万法皆通》+ 对应法术）：用于**平地起飞**与**补高**——朝向**完全照抄烟花**（实测五百七十八 A 方案）：起飞/爬升用烟花那一套（地面目标＝背离敌人＋抬头，目标高出 10 格以上才朝目标爬），并清掉法术模组那份施法目标（否则它施法前会把朝向拧平）。默认表里放了【升腾】irons_spellbooks:ascension（原生的向上冲量）与【烈焰冲锋】irons_spellbooks:burning_dash（沿视线冲刺、垂直分量保留，所以抬头瞄着放同样能起飞——只带这一个也能上天）。没有烟花/羽扇时，它也是空袭模式能否激活的那件「推进剂」")
                .translation("config.promaid.combat.flightDashClimb").define("flightDashClimb", true);
        COMBAT_FLIGHT_DASH_BOOST = BUILDER.comment("空袭·位移法术【提供速度】（默认开，需装《车万女仆：万法皆通》+ 对应法术）：在盘旋的**掉高窗口**里（与烟花同窗口、同朝向：抬头 45° 朝目标）冲一口给速度续命（鞘翅掉速就是掉高度）——实测五百七十八：窗口之外不再放，否则等于当着敌人的面从盘旋圈上切进去。这一类只负责「在空中加速」，不参与起飞判定、也不参与维持高度；默认表里是【烈焰冲锋】irons_spellbooks:burning_dash")
                .translation("config.promaid.combat.flightDashBoost").define("flightDashBoost", true);
        COMBAT_FLIGHT_DASH_CLIMB_SPELLS = BUILDER.comment("【提供高度】的法术表（填完整法术 id）：用于起飞与补高；一个法术可以同时出现在两张表里。默认 ascension + burning_dash（后者抬头瞄着放也能顶人起来，这样「只带烈焰冲锋」的女仆同样能平地起飞）")
                .translation("config.promaid.combat.flightDashClimbSpells")
                .defineList("flightDashClimbSpells",
                        List.of("irons_spellbooks:ascension", "irons_spellbooks:burning_dash"),
                        o -> o instanceof String s && !s.isEmpty());
        COMBAT_FLIGHT_DASH_BOOST_SPELLS = BUILDER.comment("【提供速度】的法术表（填完整法术 id）：用于飞行加速。默认只有 burning_dash；要加别的冲刺类法术往这里加（例如把某些瞬移/突进法术也当加速用）")
                .translation("config.promaid.combat.flightDashBoostSpells")
                .defineList("flightDashBoostSpells",
                        List.of("irons_spellbooks:burning_dash"),
                        o -> o instanceof String s && !s.isEmpty());
        COMBAT_FLIGHT_DASH_INTERVAL = BUILDER.comment("位移法术间隔（tick，默认 40 = 2 秒）：两次起飞/冲刺/补高之间的最短间隔（我们这边的节流下限）。搭配下面那条「尊重法术自身冷却」一起看——默认下真实间隔取两者较大值")
                .translation("config.promaid.combat.flightDashInterval")
                .defineInRange("flightDashInterval", 40, 10, 600);
        COMBAT_FLIGHT_DASH_BOOST_RESPECT_COOLDOWN = BUILDER.comment("位移法术·【提供速度】尊重法术自身冷却（默认开）：只管空中冲刺那一类——开启时写回冷却取 max(空袭位移间隔, 法术自身冷却)（例：烈焰冲锋原版 10 秒），她不会比玩家更频繁；关掉则那一类也完全按上面的间隔来。\n\n注意【提供高度】（起飞/补高）**始终不受法术自身冷却约束**、只按上面的间隔放——依据是本模组对位移手段一向让女仆比玩家宽松（激流三叉戟就是忽略原版「必须在水中/雨中」的限制），而且「平地起飞」本身就要求她没烟花也能持续飞：若这里也卡冷却，升腾 15 秒一记只抬约 6 格后缓降（实测），需求等于没满足")
                .translation("config.promaid.combat.flightDashBoostRespectCooldown")
                .define("flightDashBoostRespectCooldown", true);
        COMBAT_FLIGHT_SPELL_CAST_RANGE = BUILDER.comment("空袭施法距离（格，默认 24）：空袭中只在目标进入这个 3D 距离内才发起施法。默认 24 与法术模组自己的 maxSpellRange 一致（它的任务行为用的就是这个上限）；调大可让她在更远处起手（法术飞行途中还能命中），调小 = 只有贴近了才放法术")
                .translation("config.promaid.combat.flightSpellCastRange")
                .defineInRange("flightSpellCastRange", 24.0, 4.0, 64.0);
        // v1.2.2 实测五百六十：友军风免（玩家/同主女仆不被女仆的法术·风弹震开）
        COMBAT_FRIENDLY_WIND_IMMUNE = BUILDER.comment("友军风免（默认开）：女仆放出的风暴/火球/风弹不再把你和同主女仆震开。伤害本来就已免疫，漏的是击退——原版爆炸（铁魔法火球正是用女仆当来源构造的原版爆炸）与呼啸之风这类效果都直接改速度、不经过伤害事件，所以「血不掉、人还是飞了」。开 = 只对主人与同主女仆生效、只拦明显的外力位移（女仆自己的烟花推进/风弹自起跳完全不受影响）；关 = 恢复旧行为（会被震开）。")
                .translation("config.promaid.combat.friendlyWindImmune").define("friendlyWindImmune", true);
        COMBAT_AUTO_SWITCH_RADIUS = BUILDER.comment("主动切战斗响应半径（格）：主人受伤或开火时，此半径内的女仆才会响应切换")
                .translation("config.promaid.combat.autoSwitchRadius").defineInRange("autoSwitchRadius", 16, 4, 64);
        // v1.1.0 实测二十一：武器权重可配置（原版/模组各一条）——选战斗任务时
        // 加权随机：模组任务默认 2.0（优先）、原版五件套默认 1.0（降半但不排除）。
        // 例：背包有法书+铁剑 → 法术:近战 = 2:1 ≈ 67%:33%；想五五开就把两条都设 1。
        COMBAT_AUTO_SWITCH_MOD_WEIGHT = BUILDER.comment("模组武器权重（选战斗任务时的加权随机权重，默认 2.0）：模组攻击任务（万法皆通/史诗战斗/真正的力量/枪械等）普遍更强故默认优先；与原版权重成比例决定被选概率")
                .translation("config.promaid.combat.autoSwitchModWeight").defineInRange("autoSwitchModWeight", 2.0, 0.1, 10.0);
        COMBAT_AUTO_SWITCH_VANILLA_WEIGHT = BUILDER.comment("原版武器权重（默认 1.0）：原版五件套（近战/弓/弩/三叉戟/弹幕）的加权随机权重——设 0.5=更少选原版，设 2=与模组平起平坐")
                .translation("config.promaid.combat.autoSwitchVanillaWeight").defineInRange("autoSwitchVanillaWeight", 1.0, 0.1, 10.0);
        // v1.1.0 实测三百七十九（反馈："为啥自主战斗老喜欢切换到魔法？明明我只给了
        // 原版武器"）：万法皆通的魔法任务 isWeapon 恒 true（javap 反汇编实证）——
        // 背包里任何物品都被认作它的武器，模组任务凭空进候选池 + 模组让位规则
        // （实测一百八十一）把原版任务挤掉 → 只给原版武器也会被切去魔法。
        COMBAT_AUTO_SWITCH_ALLOW_MOD_TASKS = BUILDER.comment("模组任务参与自主切换（默认开）：开 = 模组攻击任务（万法皆通魔法/史诗战斗/拔刀剑等）在女仆持有【非原版物品】时才参与切换；关 = 自主战斗只用原版任务（近战/弓/弩/三叉戟/弹幕/枪械），模组任务一律不自动切入")
                .translation("config.promaid.combat.autoSwitchAllowModTasks").define("autoSwitchAllowModTasks", true);
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
        // v1.1.0 实测六十七（反馈："手上完全没有攻击性物品的女仆，就不应该触发自主战斗"）
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


        // ---- 空袭数值（v1.2.2 实测五百八十一）----
        // 需求原文：「关于空袭等各项数值也要有一个详细的配置面板，在模组详细配置。」
        // 这 30 多项原本是 MaidFlightCombatBehavior 里的硬编码常量（起飞时长/仰角/俯冲判定/
        // 盘旋半径/期望高度/补推窗口/开火射程/弹开强度/冲刺距离……），现在逐项搬进配置，
        // **默认值与原常量一字未改**（不改行为，只是把旋钮交给玩家）。面板：战斗与自保 → 空袭数值。
        BUILDER.comment("空袭各项数值（配置面板：战斗与自保 → 空袭数值）").translation("config.promaid.airRaid").push("airRaid");
        AIR_RAID_LAUNCH_TICKS_MELEE = BUILDER.comment("起飞段时长·近战（tick，默认 30 = 1.5 秒）：放烟花后维持「背离敌人 + 抬头」朝向的时长。近战靠这一段把高度拉起来（贴地滑翔位会被清掉），所以比远程长")
                .translation("config.promaid.airRaid.launchTicksMelee")
                .defineInRange("launchTicksMelee", 30, 5, 200);
        AIR_RAID_LAUNCH_TICKS_RANGED = BUILDER.comment("起飞段时长·远程（tick，默认 20 = 1 秒）：远战只求悬停高度、不吃俯冲，故比近战短（爬太高反而够不到地面敌人）")
                .translation("config.promaid.airRaid.launchTicksRanged")
                .defineInRange("launchTicksRanged", 20, 5, 200);
        AIR_RAID_LAUNCH_CLIMB_TAN_MELEE = BUILDER.comment("起飞仰角正切·近战（默认 1.88 ≈ 62°）：1 = 45°、1.88 ≈ 62°、2.75 ≈ 70°。仰角过低她一放烟花就往目标方向压头，几 tick 内贴地、滑翔位被清掉即摔")
                .translation("config.promaid.airRaid.launchClimbTanMelee")
                .defineInRange("launchClimbTanMelee", 1.88, 0.0, 10.0);
        AIR_RAID_LAUNCH_CLIMB_TAN_RANGED = BUILDER.comment("起飞仰角正切·远程（默认 1.0 = 45°）：远战起飞只要够悬停，平飞一段更早进入盘旋")
                .translation("config.promaid.airRaid.launchClimbTanRanged")
                .defineInRange("launchClimbTanRanged", 1.0, 0.0, 10.0);
        AIR_RAID_LAUNCH_RANGE = BUILDER.comment("地面重新起飞的最大水平距离（格，默认 20）：太远就先跑过去再起飞，避免「越炸越远」；只按水平距离算，比她高很多的敌人不受这条限制")
                .translation("config.promaid.airRaid.launchRange")
                .defineInRange("launchRange", 20.0, 0.0, 64.0);
        AIR_RAID_ALTITUDE_TOLERANCE = BUILDER.comment("占位高度容差（格，默认 10）：她比目标低不超过这么多格就视为已占位、直接走原链路（近战俯冲 / 远程盘旋）；同时也是起飞朝向的判据（容差内起飞走「背离 + 抬头」）")
                .translation("config.promaid.airRaid.altitudeTolerance")
                .defineInRange("altitudeTolerance", 10.0, 0.0, 64.0);
        AIR_RAID_JUMP_TICKS = BUILDER.comment("起跳等待上限（tick，默认 3）：先跳一下离地、下一 tick 再放烟花才吃得到推力；这么久还没离地（低矮空间）就放弃本轮")
                .translation("config.promaid.airRaid.jumpTicks")
                .defineInRange("jumpTicks", 3, 0, 20);
        AIR_RAID_FIREWORK_COOLDOWN = BUILDER.comment("烟花最小间隔（tick，默认 30 = 1.5 秒）：两次点火之间的最短间隔")
                .translation("config.promaid.airRaid.fireworkCooldown")
                .defineInRange("fireworkCooldown", 30, 0, 400);
        AIR_RAID_FAN_COOLDOWN = BUILDER.comment("羽扇最小间隔（tick，默认 20 = 1 秒，与原版 getUseDuration 一致）：两次挥扇之间的最短间隔")
                .translation("config.promaid.airRaid.fanCooldown")
                .defineInRange("fanCooldown", 20, 0, 400);
        AIR_RAID_SMASH_RANGE = BUILDER.comment("收翅俯冲触发距离（格，默认 3.5）：水平距离进入此值即取消滑翔、收翅自由落体俯冲")
                .translation("config.promaid.airRaid.smashRange")
                .defineInRange("smashRange", 3.5, 0.5, 32.0);
        AIR_RAID_SMASH_HIT_RANGE = BUILDER.comment("猛击命中判定距离（格，默认 4.0）：按「点到本 tick 位移线段」算距离，俯冲 1~2 格/tick 也不会整段穿过去")
                .translation("config.promaid.airRaid.smashHitRange")
                .defineInRange("smashHitRange", 4.0, 0.5, 32.0);
        AIR_RAID_FORCED_HIT_RADIUS = BUILDER.comment("范围强制命中半径（格，默认 2.5）：身边这个范围内的其他合法敌对目标也会被结算一次猛击（俯冲时判定框经常判不到贴身怪）")
                .translation("config.promaid.airRaid.forcedHitRadius")
                .defineInRange("forcedHitRadius", 2.5, 0.0, 16.0);
        AIR_RAID_SMASH_MAX_TICKS = BUILDER.comment("猛击段最长（tick，默认 20 = 1 秒）：超时按打空收尾（不摔伤、切回滑翔）")
                .translation("config.promaid.airRaid.smashMaxTicks")
                .defineInRange("smashMaxTicks", 20, 1, 200);
        AIR_RAID_MELEE_REACH = BUILDER.comment("地面近战触及距离（格，默认 3.0）：模式未激活 / 目标贴身 / 太远先跑近时用的地面近战，距离超过此值先走位靠过去")
                .translation("config.promaid.airRaid.meleeReach")
                .defineInRange("meleeReach", 3.0, 0.5, 16.0);
        AIR_RAID_MAX_PITCH_UP = BUILDER.comment("阶段二俯仰限幅·抬头（度，默认 55）：飞向目标时抬头不超过这个角度")
                .translation("config.promaid.airRaid.maxPitchUp")
                .defineInRange("maxPitchUp", 55.0, 0.0, 89.0);
        AIR_RAID_MAX_PITCH_DOWN = BUILDER.comment("阶段二俯仰限幅·低头（度，默认 70）：飞向目标时低头不超过这个角度（90 = 垂直扎下去）")
                .translation("config.promaid.airRaid.maxPitchDown")
                .defineInRange("maxPitchDown", 70.0, 0.0, 89.0);
        AIR_RAID_ORBIT_RADIUS = BUILDER.comment("远程空袭的盘旋半径（格，默认 10）：以目标为圆心维持的水平距离，靠径向修正拉回圈上")
                .translation("config.promaid.airRaid.orbitRadius")
                .defineInRange("orbitRadius", 10.0, 2.0, 48.0);
        AIR_RAID_RANGED_HOLD_HEIGHT = BUILDER.comment("期望盘旋高度（目标上方格数，默认 10）：低于这条高度带就补推——远程空袭的核心是「脚不沾地」，实测五百七十九由 3.5 提到 10")
                .translation("config.promaid.airRaid.rangedHoldHeight")
                .defineInRange("rangedHoldHeight", 10.0, 0.0, 64.0);
        AIR_RAID_RANGED_HOLD_GAIN = BUILDER.comment("高度修正增益（默认 5.0）：高度误差 → 俯仰角度的比例系数，越大越急着回到期望高度")
                .translation("config.promaid.airRaid.rangedHoldGain")
                .defineInRange("rangedHoldGain", 5.0, 0.0, 50.0);
        AIR_RAID_RANGED_HOLD_BIAS = BUILDER.comment("高度修正偏置（格，默认 1.0）：给高度误差加一点正偏置，让她略微偏高于期望高度（留余量）")
                .translation("config.promaid.airRaid.rangedHoldBias")
                .defineInRange("rangedHoldBias", 1.0, -20.0, 20.0);
        AIR_RAID_RANGED_BOOST_DROP = BUILDER.comment("掉高容差（格，默认 0.5）：掉出期望高度带这么多格就补一口推（烟花 / 位移法术），实测五百七十八由 3.0 收到 0.5")
                .translation("config.promaid.airRaid.rangedBoostDrop")
                .defineInRange("rangedBoostDrop", 0.5, 0.0, 20.0);
        AIR_RAID_RANGED_ORBIT_UP_MAX = BUILDER.comment("盘旋抬头上限（度，默认 45）：高度修正抬头时的角度上限")
                .translation("config.promaid.airRaid.rangedOrbitUpMax")
                .defineInRange("rangedOrbitUpMax", 45.0, 0.0, 89.0);
        AIR_RAID_RANGED_ORBIT_DOWN_MAX = BUILDER.comment("盘旋低头上限（度，默认 35）：高度修正低头时的角度上限（低头会掉速掉高，所以比抬头上限小）")
                .translation("config.promaid.airRaid.rangedOrbitDownMax")
                .defineInRange("rangedOrbitDownMax", 35.0, 0.0, 89.0);
        AIR_RAID_RANGED_BOOST_INTERVAL = BUILDER.comment("掉高补推间隔（tick，默认 100 = 5 秒）：两次补推（烟花 / 位移法术）之间的最短间隔")
                .translation("config.promaid.airRaid.rangedBoostInterval")
                .defineInRange("rangedBoostInterval", 100, 0, 1200);
        AIR_RAID_RANGED_BOOST_AIM_TICKS = BUILDER.comment("补推抬头窗口（tick，默认 10 = 0.5 秒）：补推成功后就按「抬头朝目标」维持这么久，把推力吃满才会回到盘旋朝向")
                .translation("config.promaid.airRaid.rangedBoostAimTicks")
                .defineInRange("rangedBoostAimTicks", 10, 1, 100);
        AIR_RAID_RANGED_BOOST_PITCH = BUILDER.comment("补推仰角（度，默认 -45 = 抬头 45°）：掉高窗口里朝目标抬头的角度（烟花与位移法术共用同一口径）")
                .translation("config.promaid.airRaid.rangedBoostPitch")
                .defineInRange("rangedBoostPitch", -45.0, -89.0, 0.0);
        AIR_RAID_RANGED_SHOT_COOLDOWN = BUILDER.comment("远程开火基础间隔（tick，默认 20 = 1 秒）：弓弩的基础射击间隔；快速装填附魔会按比例缩短（最低 4 tick、不超过本值）")
                .translation("config.promaid.airRaid.rangedShotCooldown")
                .defineInRange("rangedShotCooldown", 20, 1, 200);
        AIR_RAID_RANGED_ATTACK_RANGE = BUILDER.comment("远程射程（格，默认 24）：弓弩的 3D 距离射程（枪械用枪械模组自己的射程）；也是远程空袭锁敌的上限")
                .translation("config.promaid.airRaid.rangedAttackRange")
                .defineInRange("rangedAttackRange", 24.0, 4.0, 64.0);
        AIR_RAID_RANGED_PUSH_RADIUS = BUILDER.comment("弹开触发半径（格，默认 3）：怪物贴到这么近就触发「近身弹开」（开关在落地缓冲那页）")
                .translation("config.promaid.airRaid.rangedPushRadius")
                .defineInRange("rangedPushRadius", 3.0, 0.0, 16.0);
        AIR_RAID_RANGED_PUSH_SPEED = BUILDER.comment("弹开水平速度（默认 0.55）：弹开时施加的、远离威胁方向的水平速度大小")
                .translation("config.promaid.airRaid.rangedPushSpeed")
                .defineInRange("rangedPushSpeed", 0.55, 0.0, 5.0);
        AIR_RAID_RANGED_PUSH_UP = BUILDER.comment("弹开抬升速度（默认 0.25）：弹开时同时给一点上升速度，避免弹开途中继续下坠")
                .translation("config.promaid.airRaid.rangedPushUp")
                .defineInRange("rangedPushUp", 0.25, 0.0, 5.0);
        AIR_RAID_RANGED_PUSH_TICKS = BUILDER.comment("弹开保持（tick，默认 30 = 1.5 秒）：弹开速度持续施加这么久")
                .translation("config.promaid.airRaid.rangedPushTicks")
                .defineInRange("rangedPushTicks", 30, 0, 200);
        AIR_RAID_DASH_BOOST_MIN_RANGE = BUILDER.comment("【提供速度】位移法术的最小施放距离（格，默认 6）：目标太近就不冲（会直接冲过头/扎进敌人身上）")
                .translation("config.promaid.airRaid.dashBoostMinRange")
                .defineInRange("dashBoostMinRange", 6.0, 0.0, 64.0);
        AIR_RAID_DASH_BOOST_MAX_RANGE = BUILDER.comment("【提供速度】位移法术的最大施放距离（格，默认 28）：目标太远也不冲（冲刺是加速手段、不是位移追击）")
                .translation("config.promaid.airRaid.dashBoostMaxRange")
                .defineInRange("dashBoostMaxRange", 28.0, 0.0, 128.0);
        BUILDER.pop();

        // ---- 空袭轰炸（v1.2.2 实测五百八十七）----
        // 需求原文："当包内同时存在黑耀石/基岩，末地水晶时，在空袭近战攻击打出后再次起飞之前
        // 加几步……远程空袭加一个投掷 TNT 的机制……上述提到的爆炸效果，默认不对玩家造成伤害
        // 以及击飞，同时不破坏方块。但都可以在手册内部调试。"
        // 反编译实证：重生锚那一炸是写死的 5.0F，与萤石充能等级无关（充能只决定"炸不炸"），
        // 所以重生锚链路只要 1 颗萤石；末地水晶 6.0F、床 5.0F、TNT 4.0F，详见 MaidBombing。
        BUILDER.comment("空袭轰炸（配置面板：战斗与自保 → 空袭数值 → ⑦ 空袭轰炸）")
                .translation("config.promaid.bombing").push("bombing");
        COMBAT_BOMBING_MELEE = BUILDER.comment("战斗模式轰炸（默认开）：**所有攻击模式**打完一记之后按包里材料放一枚炸弹——地面近战 / 弓弩 / 三叉戟 / 弹幕 / 枪械 / 近战空袭（猛击命中后、再次起飞前）/ 第三方战斗任务都会放；**远程空袭除外**（她一直飞在天上，本来就放不了）。三段按顺序取第一个材料齐的：① 黑曜石/基岩 + 末地水晶（威力 6，优先）② 重生锚 + 萤石（威力 5，下界不生效）③ 床（威力 5，主世界不生效）。放置失败就整段跳过；『下界 / 主世界不生效』由维度闸按当前维度自动判，两次之间还有『轰炸最短间隔』兜底")
                .translation("config.promaid.bombing.melee").define("melee", true);
        COMBAT_BOMBING_TNT = BUILDER.comment("战斗模式的 TNT 投掷（默认开）：**所有有战斗标签的模式**（近战 / 弓弩 / 三叉戟 / 弹幕 / 枪械 / 近战空袭 / 远程空袭 / 第三方战斗任务）都会朝最近的敌人扔 TNT，需要同时有 TNT 与打火石（每发 1 TNT + 打火石 1 耐久）；材料不齐直接跳过、不影响本职开火。防误伤：主人与友军绝不作为目标，炸弹的伤害与击飞对主人/友军/她自己都不生效")
                .translation("config.promaid.bombing.tnt").define("tnt", true);
        COMBAT_BOMBING_FUSE = BUILDER.comment("起爆延迟（tick，默认 10 = 0.5 秒）：放下炸弹之后多久响——这半秒正好够她重新起飞，爆炸与起飞重叠（她自己免疫自己炸弹的伤害与击飞）")
                .translation("config.promaid.bombing.fuse").defineInRange("fuse", 10, 1, 200);
        COMBAT_BOMBING_TNT_FUSE = BUILDER.comment("投掷 TNT 的引信（tick，默认 40 = 2 秒）：扔出去到爆炸的时间，调小 = 落地即炸更准、调大 = 更容易被躲开")
                .translation("config.promaid.bombing.tntFuse").defineInRange("tntFuse", 40, 10, 200);
        COMBAT_BOMBING_TNT_INTERVAL = BUILDER.comment("投掷 TNT 的最短间隔（tick，默认 200 = 10 秒）：TNT 已改成【攻击链路末段】投放——她打完一记（近战猛击命中 / 远程开火 / 任意战斗任务攻击冷却刚写入）之后才扔，这条间隔只是两次投放之间的下限，不再是驱动本身。调小 = 打得更凶更费 TNT；老配置里还是 120 / 40 的会在启动时自动迁移到 200")
                .translation("config.promaid.bombing.tntInterval").defineInRange("tntInterval", 200, 10, 1200);
        COMBAT_BOMBING_TNT_SPEED = BUILDER.comment("投掷初速（格/tick，默认 0.9）：水平方向的速度，调大 = 飞得更快更直、调小 = 抛物线更明显")
                .translation("config.promaid.bombing.tntSpeed").defineInRange("tntSpeed", 0.9, 0.1, 3.0);
        COMBAT_BOMBING_BREAK_BLOCKS = BUILDER.comment("轰炸破坏方块（默认关）：关 = 只炸伤害与击退、不动地形（ExplosionInteraction.NONE）；开 = 原版爆炸，照原样炸出坑。注意女仆自己放的那几块无论开关都不会留在世界里：黑曜石/基岩到期回收进她背包（背包满落地），重生锚/床起爆即被它们自己那一炸消耗掉")
                .translation("config.promaid.bombing.breakBlocks").define("breakBlocks", false);
        COMBAT_BOMBING_HURT_FRIENDLY = BUILDER.comment("轰炸伤到主人/友军（默认关）：关 = 爆炸归因给女仆，主人与同主女仆既不掉血也不被震（与重锤风爆同一套风免）；开 = 完全不归因的原版爆炸，主人/友军照掉血照被炸飞，女仆自己也吃自己那一发")
                .translation("config.promaid.bombing.hurtFriendly").define("hurtFriendly", false);
        COMBAT_BOMBING_TNT_RANGE = BUILDER.comment("投掷索敌半径（格，默认 12）：战斗任务下她自动找这么近的敌人扔 TNT——照《女仆生存》那套 12 格索敌；调小 = 只贴脸扔，调大 = 主动远投")
                .translation("config.promaid.bombing.tntRange")
                .defineInRange("tntRange", 12.0, 2.0, 64.0);
        COMBAT_BOMBING_TNT_BURST_RATIO = BUILDER.comment("残血连投阈值（默认 0.7 = 七成血以下）：血量比例降到这条线以下时，一次投掷改成连投数发（《女仆生存》的『低血量爆发』思路）")
                .translation("config.promaid.bombing.tntBurstRatio")
                .defineInRange("tntBurstRatio", 0.7, 0.0, 1.0);
        COMBAT_BOMBING_TNT_BURST_COUNT = BUILDER.comment("连投最多几发（默认 3）：残血时一次投出的上限（每发各消耗 1 个 TNT 与 1 点打火石耐久）；1 = 关掉连投")
                .translation("config.promaid.bombing.tntBurstCount")
                .defineInRange("tntBurstCount", 3, 1, 16);
        COMBAT_BOMBING_RECLAIM_SECONDS = BUILDER.comment("炸弹底座回收延迟（秒，默认 10，0 = 起爆即回收）：末地水晶链路里那块黑曜石 / 基岩在起爆后**留在原地**这么久，再由她**收进自己的背包**——黑曜石留着才能看出『水晶是放在黑曜石上』那副样子。回收进她自己的背包；**背包满就掉在她脚下**（与挖矿 / 搭路的方块回收同一口径）；重生锚 / 床不进这张表：它们自己那一炸就把方块消耗掉了")
                .translation("config.promaid.bombing.reclaimSeconds")
                .defineInRange("reclaimSeconds", 10, 0, 600);
        COMBAT_BOMBING_PLACE_GAP = BUILDER.comment("放置间隔（tick，默认 10 = 0.5 秒）：先放下黑曜石 / 重生锚 / 床，停这么久再挂末地水晶 / 给她充能——不然两步是同一瞬间完成的，玩家根本看不出中间有过动作。0 = 不间隔")
                .translation("config.promaid.bombing.placeGap").defineInRange("placeGap", 10, 0, 40);
        COMBAT_BOMBING_DIMENSION_GUARD = BUILDER.comment("维度闸（默认开）：只在这个维度『原版真的会炸』时才开放重生锚 / 床链路——判据就是原版 use() 里判断要不要炸的那两句（重生锚看 respawnAnchorWorks、床看 bedWorks）。其他模组新增的维度只要把这两条写成『能用』，这两条链路就整段不开放，不会在『那个维度根本炸不了』的地方硬炸。关掉 = 只看材料、不看维度")
                .translation("config.promaid.bombing.dimensionGuard").define("dimensionGuard", true);
        COMBAT_BOMBING_ANCHOR_NEEDS_GLOWSTONE = BUILDER.comment("重生锚需要萤石（默认开 = 原版口径）：重生锚 0 级充能右键不炸（充能只决定『炸不炸』、不决定『炸多狠』，那一炸固定 5.0），所以炸之前要 1 颗萤石把等级从 0 顶到 1——链路是「先放锚（摆臂）→ 副手换成萤石 → 充能（摆臂）→ 0.5 秒后挥臂，正好压上它自己那一炸」。关掉 = 不消耗萤石，她直接替你把那 1 级补上（照样有充能音效与动作）")
                .translation("config.promaid.bombing.anchorNeedsGlowstone")
                .define("anchorNeedsGlowstone", true);
        COMBAT_BOMBING_BOMB_INTERVAL = BUILDER.comment("轰炸最短间隔（tick，默认 200 = 10 秒）：整条轰炸链路（黑曜石+末地水晶 / 重生锚+萤石 / 床）两次之间的下限——轰炸已经挂进攻击链路（**打完一记之后**才放），这条只是下限；推广到所有攻击模式之后，不加下限会在几秒内烧光她的黑曜石 / 水晶。缺料那一下不占用间隔")
                .translation("config.promaid.bombing.bombInterval")
                .defineInRange("bombInterval", 200, 10, 2400);
        COMBAT_BOMBING_POSE = BUILDER.comment("副手动作表现（默认开）：放置黑曜石/重生锚/床、给重生锚充能、投掷 TNT 时，副手短暂举起她正在用的那一件（方块 / 萤石 / 打火石）——『好像真的打了一下』就是靠它，起爆那一刻还会再举一次。关掉 = 副手全程不被换，只剩挥臂 / 音效 / 爆炸（挥臂是打斗反馈，不归这个开关管）。半路关掉也不会把她的盾牌 / 食物留在手上：还原逻辑独立于开关")
                .translation("config.promaid.bombing.pose").define("pose", true);
        COMBAT_BOMBING_AIR_PLACE = BUILDER.comment("空中强制放置（默认开）：空袭时她一直在飞、脚边常常没有地面——开启后先在目标脚边、再在**她正下方**找可放置的格子；都找不到支撑面时就**直接悬空放下**（原版放置本身允许悬空，只是玩家手点不到空气）。关 = 找不到带支撑的落点就整段跳过")
                .translation("config.promaid.bombing.airPlace")
                .define("airPlace", true);
        COMBAT_BOMBING_PINK_MARK = BUILDER.comment("女仆放置物的淡粉色标记（默认开，纯客户端）：她刚放下的黑曜石/重生锚/床、刚挂上的末地水晶、刚扔出的 TNT 会套一层很淡的粉色描边与填充，便于分辨「哪些是女仆放的」")
                .translation("config.promaid.bombing.pinkMark").define("pinkMark", true);
        COMBAT_BOMBING_PINK_FIRE = BUILDER.comment("爆炸火焰改粉色（默认开）：**只有她自己那一炸点着的火换成粉色**（重生锚 / 床那一炸会按原版口径在地上留火——javap 实证：着火只看 fire=true，与「破不破坏方块」无关）。换的时机是「边点边换」：那一炸点着的每一格直接生成为粉色火，与距离无关（实测：一炸 119 格全部直接变粉）。**世界里别的火一概不碰**——打火石 / 闪电 / 岩浆 / 别的模组 /早先留下的原版火，本模组既不换也不灭（实测六百〇一收回：曾经「全都换」，那等于顺手把全世界的火蔓延关掉，是越界）。粉火长这样：粉色火焰贴图 + 粉色火星，不蔓延、几秒后自己熄灭。关 = 保持原版橙色火（等同旧版行为）。注意末地水晶与 TNT 原版都是 fire=false、本来就不留火，所以这条对它们没有可见变化")
                .translation("config.promaid.bombing.pinkFire").define("pinkFire", true);
        COMBAT_BOMBING_FIRE_RENDER = BUILDER.comment("粉色火焰渲染（默认开）：关掉之后粉色火**还在那个位置**（熄灭逻辑、伤害判定照旧按下面那条走），只是不画出来——相当于「看不见的火」。想彻底不要火请关上面那条『爆炸火焰改粉色』")
                .translation("config.promaid.bombing.fireRender").define("fireRender", true);
        COMBAT_BOMBING_FIRE_PROTECT = BUILDER.comment("火焰伤害保护（默认开）：她炸出来的这种粉色火对**玩家与女仆**完全无效——既不点燃也不掉血（其它生物照常被烧，她扔的毕竟是炸弹）。只保护**她这一炸点着的粉火**：别处的火（你自己点的 / 岩浆 / 别的模组 / 早先留下的原版火）是原版规则，本模组不碰。关掉 = 照原版口径烧人（与站在普通火里一样）")
                .translation("config.promaid.bombing.fireProtect").define("fireProtect", true);
        COMBAT_BOMBING_TNT_TRACK = BUILDER.comment("TNT 追踪飞行（默认开）：扔出去的 TNT 在**离手后的一小段时间里**朝目标方向拐一点弯——**只改方向、速度不变**（目标跑得快也更容易吃到）；关 = 纯弹道抛物线。追多久见下面那条『TNT 追踪时长』")
                .translation("config.promaid.bombing.tntTrack").define("tntTrack", true);
        COMBAT_BOMBING_TNT_TRACK_TICKS = BUILDER.comment("TNT 追踪时长（tick，默认 10 = 0.5 秒，0 = 不追踪）：扔出去之后只在这段时间内修正方向，之后按当时方向直飞——0.5 秒刚好是『稍微修一下准度』的量；另外每 tick 最多转 5 度（限转角），所以观感是一段平滑小弧线，不会像旧版那样一离手就折线乱拐")
                .translation("config.promaid.bombing.tntTrackTicks").defineInRange("tntTrackTicks", 10, 0, 200);
        BUILDER.pop();

        // ---- 第三方玩法模式黑名单（v1.2.2 实测五百九十：傀儡装配 Modular Golems 的「傀儡师」） ----
        //   口径：自主系统永不切进去；玩家手动切进去之后本模组战术全体让位
        //（见 com.maidsmart.compat.MaidModeCompat）
        BUILDER.comment("第三方模组兼容").translation("config.promaid.compat").push("compat");
        COMPAT_PUPPET_BLACKLIST = BUILDER.comment("傀儡模式黑名单（默认开）：检测到《傀儡装配》Modular Golems 给女仆注册的「傀儡师」模式时自动列入——① 自主参战与 LLM 自主切换永不切到这个模式；② 玩家手动切进去之后，本模组的战术（单兵走位/跳劈/举盾、投弹与轰炸、自动换装、排班换段）全体让位，只保留那个模组原汁原味的玩法，切回来即自动恢复；保命动作（自保/落地水/防火）不在让位之列")
                .translation("config.promaid.compat.puppetBlacklist").define("puppetBlacklist", true);
        BUILDER.pop();

        // ---- 搭路（v1.1.0：主人在上方一定距离内 → 垫方块靠近，默认关） ----
        BUILDER.comment("搭路设置").translation("config.promaid.bridge").push("bridge");
        BRIDGE_ENABLED = BUILDER.comment("搭路（默认开）：她背包有可放置方块、周围无威胁时，朝主人方向铺方块搭桥/搭高靠近（借鉴僵尸搭方块追人；搭的方块到期自动回收）")
                .translation("config.promaid.bridge.enabled").define("enabled", true);
        BRIDGE_MAX_DIST = BUILDER.comment("搭路触发距离（格，默认 32）：主人【高于女仆】需垂直搭高时的启动上限——超过交给传送/跟随；平路/低高差追逐（主人不低于女仆）不受此限制，水平多远都启动平桥追逐（v1.1.0 实测一百六十五，参考僵尸搭桥追人）")
                .translation("config.promaid.bridge.maxDist").defineInRange("maxDist", 32, 2, 32);
        BRIDGE_AIR_MAX_DIST = BUILDER.comment("空中搭桥触发距离（格，默认 50）：主人【高于女仆】需爬高/或女仆已在空中时，主人再远也直接铺桥走过去——空中没有'走路过去'的选项；设为 0 关闭远距铺桥（只保留近距逻辑）。v1.1.0 实测一百六十五：平路/低高差追逐（主人不低于女仆）已不受任何距离上限约束")
                .translation("config.promaid.bridge.airMaxDist").defineInRange("airMaxDist", 50, 0, 128);
        BRIDGE_MIN_DY = BUILDER.comment("搭路最小高差（格，默认 3）：主人至少高于女仆这么多格才走垂直搭高（平路/低处走路或铺桥处理）——3 格 = 玩家手长：她搭到与你只差 3 格内你就能近身收回/互动；隔得更远你伸手够不到她")
                .translation("config.promaid.bridge.minDy").defineInRange("minDy", 3, 1, 8);
        BRIDGE_MIN_RADIUS = BUILDER.comment("搭路最小球面半径（格，默认 3）：以女仆为圆心的 3D 欧氏距离（竖直+水平一起算）——主人在此球面内（只近不高）不启桥靠跟随走路；球面外才启桥：高度差够→垂直搭高，竖直差不多+水平远+前方脚下悬空（低头没路）→平铺搭桥；实心地面平路纯走导航不启桥（防反复启停抖动）")
                .translation("config.promaid.bridge.minRadius").defineInRange("minRadius", 3, 1, 8);
        // v1.1.0 实测一百八十七（反馈："水平距离搭建方块有没有启动要求呢？结合实际情况，加个启动要求"）
        // v1.1.0 实测一百九十九（反馈："给搭路再加一个配置项。水平距离小于 5 的时候不会触发水平搭建方块。
        // 此项目仍然可以在面板内自己进行配置"）：默认值 6 → 5（该配置已存在，语义=水平距离小于此值不触发
        // 水平搭桥；仅按玩家指定调整默认值，面板可调范围不变）
        BRIDGE_START_H_DIST = BUILDER.comment("平桥启动水平距离（格，默认 6）：女仆与主人【水平距离】达到此值、且朝主人方向前方脚下悬空才启动水平搭桥（垫块踩过去）——小于此值只走路跟随；范围 3~64（3 = 最灵敏，接近一百七十九旧行为）。竖直搭高（主人更高、原地垫柱）不受影响")
                .translation("config.promaid.bridge.startHDist").defineInRange("startHDist", 6.0, 3.0, 64.0);
        BRIDGE_THREAT_DIST = BUILDER.comment("搭路威胁半径（格，默认 8）：周围此范围内有敌对生物时不搭路（塔会被拆/搭一半挨打）；刷怪频繁的整合包里可再调小，过大会导致搭路几乎永不触发")
                .translation("config.promaid.bridge.threatDist").defineInRange("threatDist", 8, 4, 32);
        // v1.1.0 实测一百二十二（反馈："女仆搭方块速度不要跟玩家有过大出入，可以
        // 稍微快一点"）：原版无放置冷却，玩家持续搭约 4~6 块/秒（人手点击上限）。
        // 实测二百一十五（反馈"搭建速度过快容易失足摔死——降低默认搭建速度"）：
        // 默认定格 4 tick/块（≈5 块/秒，只比玩家快一档）；2 tick ≈10 块/秒太快
        BRIDGE_STEP_COOLDOWN = BUILDER.comment("搭路节奏（tick/块，默认 4）：每垫一块方块的最短间隔——越小铺得越快（默认 4 tick ≈ 5 块/秒 = 比玩家手速 4~6 块/秒略快一点点；2 tick ≈ 10 块/秒太快，连续跳块容易失足摔死）")
                .translation("config.promaid.bridge.stepCooldown").defineInRange("stepCooldown", 4, 2, 40);
        BRIDGE_PLACED_LIFETIME = BUILDER.comment("搭路方块清理时间（秒，默认 3）：垫的方块放置 N 秒后自动变掉落物回收（女仆站在上面时延后）——与搭块速度联动：默认节奏下同时存在约 20~30 块，不会堆积成片")
                .translation("config.promaid.bridge.placedLifetime").defineInRange("placedLifetime", 3, 1, 60);
        BRIDGE_RECLAIM_TO_MAID = BUILDER.comment("搭路方块回收进背包（默认开，全局开关——搭路/挖矿/伐木/战斗搭方块一切女仆搭的垫脚方块都适用）：开启后到期/被摧毁的搭脚方块不掉落地面，直接塞回附近女仆（8 格内最近者）的背包——背包满/附近没女仆才落地；关闭则恢复掉落物落地")
                .translation("config.promaid.bridge.reclaimToMaid").define("reclaimToMaid", true);
        // v1.1.0 实测十七：战斗方块清理时间（默认 60 秒——战斗节奏多变女仆可能在
        // 塔上待一阵，比挖矿/搭路的 10 秒长；实测十八：女仆踩着时刷新计时，走开后
        // 每块还有完整寿命缓冲，不会整塔瞬间塌）
        // 实测三百六十六：寿命 60→30 秒（要求"利落"）；女仆还站在上面的
        // 方块照旧刷新计时（走开后才开始倒数），塔上狙击/守势不受影响
        COMBAT_PLACED_LIFETIME = BUILDER.comment("战斗搭方块清理时间（秒，默认 30）：自保（搭高/搭桥）与高地狙击搭的方块 N 秒后自动回收；女仆还站在上面的方块会刷新计时（走开后才开始倒数），不会把她摔下去")
                .translation("config.promaid.combat.placedLifetime").defineInRange("combatPlacedLifetime", 30, 3, 600);
        BUILDER.pop();

        // ---- 杂项 ----
        BUILDER.comment("杂项设置").translation("config.promaid.misc").push("misc");
        MISC_COOK_RADIUS = BUILDER.comment("烧制任务熔炉搜索范围")
                .translation("config.promaid.misc.cookRadius").defineInRange("cookRadius", 16, 4, 48);
        MISC_BREW_RADIUS = BUILDER.comment("酿造任务酿造台搜索范围")
                .translation("config.promaid.misc.brewRadius").defineInRange("brewRadius", 16, 4, 48);
        MISC_PROCESS_COOLDOWN = BUILDER.comment("烧制/酿造处理间隔（tick）")
                .translation("config.promaid.misc.processCooldown").defineInRange("processCooldown", 40, 10, 200);
        // v1.1.0 实测一百五十七：熔炉兼容矿物类可烧制物
        MISC_COOK_SMELT_ORES = BUILDER.comment("熔炉烧矿物（默认开）：烧制任务里背包没有食材时，兼容带矿物/原料标签（forge:ores、minecraft:*_ores、forge:raw_materials 等）且当前世界有熔炉配方的物品——铁矿石/粗铁/金矿石/远古残骸等照常放进熔炉烧；关闭 = 只烧食材白名单")
                .translation("config.promaid.misc.cookSmeltOres").define("cookSmeltOres", true);
        // v1.1.0 实测一百八十二：通用可烧制物回退——治"女仆只投燃料不投烧制物"
        MISC_COOK_SMELT_ANY = BUILDER.comment("烧任何可烧制物（默认开）：背包没有食材白名单/矿物标签物品时，回退喂任何【当前世界有熔炉配方 且 非装备类】的物品——沙子→玻璃、圆石→石头、原木→木炭、各类模组食材/模组粗矿等都能喂（装备类永不熔：铁金钻石工具盔甲等有烧成粒配方的会被排除）；关闭 = 只按「熔炉烧矿物」+食材白名单喂")
                .translation("config.promaid.misc.cookSmeltAny").define("cookSmeltAny", true);
        // v1.1.0 实测一百八十三：散步行为——治 TLM 原生散步又少又慢又近（0.3 倍速/5 格/
        // 概率 0.001×0.09²≈平均一两小时才走一次）
        MISC_STROLL_ENABLED = BUILDER.comment("空闲散步（默认开）：女仆空闲时按间隔主动散步——替代 TLM 原生散步（原生只有 0.3 倍速、5 格半径、概率约每两小时才触发一次）；战斗/自保/站桩工作/有移动目标时不打扰")
                .translation("config.promaid.misc.strollEnabled").define("strollEnabled", true);
        MISC_STROLL_INTERVAL = BUILDER.comment("散步间隔（tick，默认 200=10 秒）：空闲女仆每隔这么久散步一次（找得到落点就走，找不到顺延）")
                .translation("config.promaid.misc.strollInterval").defineInRange("strollInterval", 200, 20, 24000);
        MISC_STROLL_RADIUS = BUILDER.comment("散步半径（格，默认 16）：每次散步在周围这个半径内随机选点（排班/在家模式下不会超出「排班活动半径」）")
                .translation("config.promaid.misc.strollRadius").defineInRange("strollRadius", 16, 4, 128);
        MISC_STROLL_SPEED = BUILDER.comment("散步速度倍率（默认 0.7；1.0 = 全速走路会显得鬼畜——突然冲刺又急停，TLM 原生散步原本只有 0.3 倍速；试过 1.0 后按实测调低）")
                .translation("config.promaid.misc.strollSpeed").defineInRange("strollSpeed", 0.7, 0.3, 2.5);
        // 实测四百一十八：床铺互通（女仆睡原版床 / 玩家睡女仆床）
        MISC_BED_INTEROP = BUILDER.comment("床铺互通（默认开）：女仆能睡原版床（16 色床，TLM 原生只认女仆床），玩家也能睡女仆床（并可把女仆床设为重生点）——两个方向互开；关掉恢复 TLM 原版行为（女仆只睡女仆床、玩家不能睡女仆床）")
                .translation("config.promaid.misc.bedInterop").define("bedInterop", true);
        // 实测四百二十一：冷却可视化 HUD（反馈："我希望女仆复活的CD及自己回魂符的CD在玩家屏幕上可视化"）
        MISC_COOLDOWN_HUD = BUILDER.comment("冷却可视化 HUD（默认开）：在玩家屏幕左上角实时显示本人女仆的自动复活倒计时与回魂符冷却倒计时——女仆死亡等待复活、或放出后处于回魂符冷却窗口时显示；关掉不显示也不发同步包")
                .translation("config.promaid.misc.cooldownHud").define("cooldownHud", true);
        // 实测五百七十三：中键工位标记开关（与 TLM 自带的「河童的罗盘」撞车 → 给开关 + 让位）
        MISC_WORK_POS_MARKER = BUILDER.comment("中键工位标记（默认开）：潜行 + 鼠标中键方块 = 把身边自家「在家/排班」女仆的工位与休闲锚点标到那个方块（范围=排班活动半径）。与 TLM 自带的「河童的罗盘」功能重叠——两者写的是同一份排班锚点（谁后写谁生效），所以手持河童的罗盘时本功能自动让位；这里关掉则中键完全交还原版取方块")
                .translation("config.promaid.misc.workPosMarker").define("workPosMarker", true);
        // 实测四百四十三：悬空禁搭方块（反馈："女仆在悬空状态下应该禁止搭建方块——
        // 挖矿/伐木也通用；下落悬空时搭方块又放不了落地水，结果自己摔死"）
        MISC_NO_PLACE_IN_AIR = BUILDER.comment("悬空禁搭方块（默认开）：女仆未落地时不再搭方块——涵盖自保搭高/搭路/挖矿垫脚/伐木垫脚四个模块。触发口径：坠落距离达到「落地水触发高度」时禁（此时落地水会接管，搭方块既救不了她、又会挡住落地水）。水里/岩浆里、骑乘、鞘翅滑翔不算悬空；站在地面照常搭")
                .translation("config.promaid.misc.noPlaceInAir").define("noPlaceInAir", true);
        // 实测五百三十六：不得搭在主人身上（反馈："不得将方块搭在主人（尤其是头部）
        // 所在位置…即不得将方块搭在主人碰撞箱所触碰到的空气方块位置"）
        MISC_NO_PLACE_ON_OWNER = BUILDER.comment("不得搭在主人身上（默认开）：目标格被主人碰撞箱占着时不搭方块——防把主人挤住、卡住或盖住头部。覆盖四个自主搭块模块（自保搭高/搭路/挖矿垫脚/伐木垫脚）、插火把、AI 工具 smart_place，以及蓝图建造与碑石建造；蓝图类遇到该情形是【延后】而非跳过——主人让开后自动续建。判据用碰撞箱真正交叠（严格不等式），所以主人站在方块上时不会误判他脚下那格。关掉 = 恢复旧行为（允许搭在主人身上）")
                .translation("config.promaid.misc.noPlaceOnOwner").define("noPlaceOnOwner", true);
        // 实测四百四十八：蛋糕可食用开关（反馈建议的兜底逃生通道——把蛋糕变可食是
        // 全局改动，第三方模组的食物阈值判定会因此把蛋糕纳入，冲突时可直接关掉）
        MISC_CAKE_EDIBLE = BUILDER.comment("蛋糕可食用（默认开）：让女仆把蛋糕当食物（女仆爱吃蛋糕的设定；女仆吃整块蛋糕回复 14 点生命并 +10 好感，玩家用蛋糕右击自己的女仆也会触发投喂）。关闭后蛋糕恢复原版行为（只能放置、不能被女仆当食物），「女仆吃蛋糕」相关功能全部停用——这是与第三方模组冲突时的逃生通道（某些模组会把「可食用物品」判定为投喂目标，从而抢走野生女仆的驯服交互）")
                .translation("config.promaid.misc.cakeEdible").define("cakeEdible", true);
        // v1.1.0 实测一百五十八：兼容高炉/烟熏炉
        MISC_COOK_SMOKER_BLAST = BUILDER.comment("兼容高炉/烟熏炉（默认开）：烧制任务不只操作熔炉——高炉按高炉配方喂料（矿石/粗金属等）、烟熏炉按烟熏配方喂料（生食），成品/燃料逻辑照常；高炉喂料受「熔炉烧矿物」开关约束（高炉只烧矿物，关掉后高炉只收成品/补燃料不喂料）；关闭 = 只操作熔炉（旧行为）")
                .translation("config.promaid.misc.cookSmokerBlast").define("cookSmokerBlast", true);
        // v1.1.0 实测三百：木材黑名单开关
        MISC_COOK_BURN_WOOD = BUILDER.comment("烧木材（默认关）：木材类（原木/木板/树苗/竹等）默认进黑名单不烧——女仆不会拿木材当原料烧（避免「用木头烧木头」）；勾选后木材类照常可烧（仍受「烧任何可烧制物」开关约束）")
                .translation("config.promaid.misc.cookBurnWood").define("cookBurnWood", false);
        // v1.1.0 实测三百一十一：宰杀任务阈值
        MISC_SLAUGHTER_COUNT = BUILDER.comment("宰杀数量阈值（默认 5）：宰杀任务女仆检测周围同种牲畜（牛/猪/羊/鸡/兔等按类型分组）的数量，某组超过此数 → 每 3 秒随机宰杀一只该组牲畜（播放动画）；≤ 阈值不动")
                .translation("config.promaid.misc.slaughterCount").defineInRange("slaughterCount", 5, 2, 64);
        // v1.1.0 实测三百一十八：宰杀扫描半径（默认 16，与酿造/熔炉一致）——
        // 旧版硬编码 5×5（±2.5 格），畜栏稍大/牛在 3 格外就扫不到 → 永远"无超阈值组"
        MISC_SLAUGHTER_RADIUS = BUILDER.comment("宰杀扫描半径（默认 16）：宰杀任务女仆检测周围水平半径内同种牲畜的数量（垂直 ±4 格）")
                .translation("config.promaid.misc.slaughterRadius").defineInRange("slaughterRadius", 16, 4, 48);
        MISC_BUBBLE_LIMIT_MS = BUILDER.comment("对话气泡限频（毫秒，防刷屏）")
                .translation("config.promaid.misc.bubbleLimitMs").defineInRange("bubbleLimitMs", 5000, 500, 60000);
        // v1.1.0 实测四百一十：排班女仆贴身情绪气泡（30 条文本池，触发 CD 30 秒）
        MISC_SCHEDULE_BUBBLE_ENABLED = BUILDER.comment("排班贴身气泡（默认开）：靠近排班中的女仆（3.5 格内）时，她随机冒出一条贴身气泡对话（30 条文本池，每只女仆 30 秒最多一条）——情绪价值小彩蛋；战斗/自保/睡觉中不打扰")
                .translation("config.promaid.misc.scheduleBubbleEnabled").define("scheduleBubbleEnabled", true);
        MISC_SCHEDULE_BUBBLE_RADIUS = BUILDER.comment("排班贴身气泡触发距离（格，默认 3.5）：主人距排班女仆水平小于此值时可能触发（每只女仆触发冷却 30 秒，与气泡全局限频独立）")
                .translation("config.promaid.misc.scheduleBubbleDist").defineInRange("scheduleBubbleDist", 3.5, 1.0, 16.0);
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
        MISC_MAID_CHUNK_LOAD = BUILDER.comment("女仆区块强制加载（所有有主女仆所在区块持续保持实体 ticking，随时可传送/召回/救援；关闭后远处女仆所在区块卸载时会冻结失联）")
                .translation("config.promaid.misc.maidChunkLoad").define("maidChunkLoad", true);
        // v1.1.0 实测一百三十四：同维度远距拉回——TLM 自带的"过远自动传送"只在
        // 非 home、非工作、主人同维度时触发，且 teleportToOwner 偶发静默失败
        //（±3 格随机试探找不到落点）；这里补一道统一兜底：同维度、不守家、没在
        // 干重活、且距离超过阈值 → 直接按跨维度同款 findStand+teleportTo 拉回
        MISC_MAID_SAME_DIM_PULL = BUILDER.comment("同维度远距拉回（默认开）：女仆与主人在同一维度但距离超过阈值时自动传送到主人身边（跨区块传送的兜底——TLM 只拉非home非工作的跟随女仆且可能静默失败）。守家/坐姿/骑乘/干活中的女仆不拉")
                .translation("config.promaid.misc.maidSameDimPull").define("maidSameDimPull", true);
        MISC_MAID_SAME_DIM_DIST = BUILDER.comment("同维度拉回距离阈值（格，默认 48）：女仆与主人同维度且水平/垂直距离超过此值才拉回——低于此值靠走路/跟随，不打扰她")
                .translation("config.promaid.misc.maidSameDimDist").defineInRange("maidSameDimDist", 48, 16, 256);
        // v1.1.0 实测一百八十八：Y 轴拉回（反馈："传送机制不检测 Y 轴。女仆搭得太高不会自己传送下来"）
        MISC_MAID_SAME_DIM_VERTICAL = BUILDER.comment("Y 轴拉回门槛（格，默认 16）：女仆与主人同维度、水平距离没超上一条阈值但【垂直高度差】超过本值时——若主人旁边 16 格内有安全落点（findStand）就传送过来；没有安全落点则不传（等有落点/再试）。旧版只有 48 格 3D 距离阈值，水平贴身、竖直搭高 30 格的女仆永远不触发（骑到你头顶挂机）；守家/坐姿/骑乘/干活中同样不拉")
                .translation("config.promaid.misc.maidSameDimVertical").defineInRange("maidSameDimVertical", 16, 4, 128);
        // v1.1.0 实测一百五十一：跟随收紧（参考改版 TLM jar——每 tick 重断言跟随目标）
        MISC_FOLLOW_TIGHTEN = BUILDER.comment("跟随收紧（默认开，参考改版 TLM jar 设计）：跟随模式的女仆每 tick 重新断言跟随目标——平常跟随在 4 格以内，被其他行为/寻路刹车干扰走远时立即拉回，不再走走停停/乱跑；关闭 = 官方 1.5.3 原版行为（只在跟随行为启动时设一次目标）")
                .translation("config.promaid.misc.followTighten").define("followTighten", true);
        // v1.1.0 实测一百五十二：有增益也喂牛奶（装备/饰品永久增益不再阻止解负面）
        MISC_MILK_FEED_WITH_BUFF = BUILDER.comment("有增益也喂牛奶（默认开）：女仆自己喝牛奶解负面 / 给主人喂牛奶解负面时，身上有增益效果（很多装备/饰品带永久增益，旧版\"无增益才喂\"导致中毒/凋零也不解）也照喂——牛奶会连增益一起清掉；关闭 = 有增益时不喂牛奶（只喂蜂蜜解中毒）")
                .translation("config.promaid.misc.milkFeedWithBuff").define("milkFeedWithBuff", true);
        // v1.1.0 实测八十九：寻路危险方块避让——女仆绕开岩浆/火/仙人掌等
        MISC_DANGER_AVOID = BUILDER.comment("寻路危险方块避让（默认开）：女仆规划路径时自动绕开危险表中的方块（岩浆/火/仙人掌/甜浆果丛/细雪等），宁可停下等过远传送兜底也不往里走；已身处险境时仍保留逃出路径")
                .translation("config.promaid.misc.dangerAvoid").define("dangerAvoid", true);
        MISC_DANGER_BLOCKS = BUILDER.comment("危险方块表（完整注册名，一行一个；命中站立格或脚下方块即视为危险）")
                .translation("config.promaid.misc.dangerBlocks")
                .defineList("dangerBlocks", List.of(
                        "minecraft:lava",
                        "minecraft:fire",
                        "minecraft:soul_fire",
                        "minecraft:magma_block",
                        "minecraft:cactus",
                        "minecraft:sweet_berry_bush",
                        "minecraft:wither_rose",
                        "minecraft:powder_snow",
                        "minecraft:pointed_dripstone"), o -> o instanceof String s && !s.isEmpty());
        // v1.1.0 实测九十：险境脱离——已身处险境的女仆自动挪到最近安全格
        MISC_DANGER_ESCAPE = BUILDER.comment("险境脱离（默认开）：女仆已站在危险方块上（岩浆/火/岩浆块等）时，每 0.5 秒巡检并自动挪到最近的安全格+应急灭火——不等血量跌破自保线白挨伤害。坐姿/骑乘中的不处理（由椅子/载具系统负责），自保中让位（有专属珍珠/放水链路）；0.5 秒一轮、每女仆 1.5 秒冷却防振荡")
                .translation("config.promaid.misc.dangerEscape").define("dangerEscape", true);
        // v1.1.0 实测七十九：受困救援——死亡瞬间的坏落点（下界基岩顶等）已被
        // "主人非存活不追"挡住，这里兜底捞回历史上已经受困的女仆
        MISC_MAID_RESCUE = BUILDER.comment("受困救援（默认开）：女仆被困在下界基岩顶层（高度≥126）或掉出世界底部时，自动安全传送到存活的主人身边（跨维度通用；在家模式的女仆也救——基岩顶不是家）；已在主人身边 8 格内不触发")
                .translation("config.promaid.misc.maidRescue").define("maidRescue", true);
        // v1.1.0 实测九十四：运行日志——状态迁移事件落盘 logs/promaid.log，方便事后验查
        MISC_LOG_ENABLED = BUILDER.comment("运行日志（默认开）：把排班段应用、战斗参战/还原/僵局阀/任务被接管、险境脱离挪格与应急灭火、跨维跟随传送、自保标记自愈等状态变化写入 游戏目录/logs/promaid.log（满 4MB 自动轮换为 promaid.log.old，并镜像到 latest.log）——出问题后按时间线对账；关闭后完全静默")
                .translation("config.promaid.misc.logEnabled").define("logEnabled", true);
        // v1.1.0 实测二百三十四：手持光源发实光（隐藏光块跟随；1.20.1 无实体发光机制，
        // 用隐形 minecraft:light 光块产出真实方块光）
        MISC_HELD_LIGHT_ENABLED = BUILDER.comment("手持光源发实光（默认开）：女仆主手/副手持有光源类物品（火把/灯笼/萤石/菌光体/灵魂火把等——亮度取自身方块光强）时，她脚底自动跟随一个隐形光块，周围的方块被真实照亮（与所持光源亮度一致）；不拿光源或关闭后光块自动移除。与其他环境光源同等待遇，插火把判定不受读写影响（亮处本就不该插）")
                .translation("config.promaid.misc.heldLightMaid").define("heldLightMaid", true);
    // v1.5.161：农场连锁收获 / 收获物自动收集（v1.5.189：连锁默认开启——要求
    // "连锁采集也应加入"；收获物收集保持默认关，避免自动拾取导致背包爆炸）
    // v1.1.0 实测二百二十七（反馈："所有连锁采集默认为开启"）：默认值保持开并注明
    MISC_CHAIN_HARVEST = BUILDER.comment("农场连锁收获（默认开：收割时以目标格为中心蔓延连锁收割相连农田里的成熟作物）")
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
    // v1.1.0 实测三百五十二：树苗骨粉催熟（伐木女仆背包有骨粉 → 对身边树苗催熟）
    // v1.1.0 实测三百五十三：节拍改 0.5 秒一次（要求）
    MISC_MAID_BONEMEAL_SAPLING = BUILDER.comment("树苗骨粉催熟（默认开）：伐木模式的女仆背包里有骨粉时，对身边（半径 6 格、垂直 ±2）的树苗使用骨粉催熟——每 0.5 秒尝试一次，优先催熟已种下的树苗而不是种新的；【骨粉催熟不受光照限制】（地下/室内种下照常催熟），但树干上方被实心方块挡死的树苗不浪费骨粉（长不出来）；深色橡树苗不催（单株永不生长）。关闭 = 女仆不使用骨粉")
            .translation("config.promaid.misc.maidBonemealSapling").define("maidBonemealSapling", true);
    // v1.1.0 实测三百五十五：农场作物骨粉催熟（与树苗同款逻辑——主副手/背包找骨粉、
    // 施肥时主手换持骨粉、0.5 秒一株、粒子反馈）
    MISC_MAID_BONEMEAL_FARM = BUILDER.comment("农场作物骨粉催熟（默认开）：农场模式的女仆背包里有骨粉时，对身边（半径 16 格、垂直 ±4）的未成熟作物使用骨粉催熟——每 0.5 秒尝试一株（带粒子特效），优先催熟已种下的作物而不是等自然成熟；只催【当前世界有骨粉配方】的作物（原版/模组作物自动兼容），成熟作物不催（催了也白费）；施肥时主手临时换持骨粉，停止 1 秒后自动还原。关闭 = 女仆不使用骨粉")
            .translation("config.promaid.misc.maidBonemealFarm").define("maidBonemealFarm", true);
    // v1.1.0：排班表总开关（反馈"玩家可操作"原则——新功能都要有手册内开关）
    MISC_SCHEDULE_ENABLED = BUILDER.comment("排班表系统（默认开）：按游戏内时间自动应用女仆的排班日程；关闭后排班调度停摆（已保存的日程不丢，重新打开恢复生效），女仆保持当前任务")
            .translation("config.promaid.misc.scheduleEnabled").define("scheduleEnabled", true);
    // v1.1.0 实测六十一：战斗还原后排班宽限——威胁在还原威胁半径边缘闪烁时，
    // 战斗↔还原循环不再立刻把排班段任务压回去（还原后先干原任务一段时间）
    MISC_SCHEDULE_RESTORE_GRACE = BUILDER.comment("战斗还原后排班宽限（tick，默认 60=3 秒）：主动战斗结束还原原任务后，排班调度等待这么久才接管（期间她继续干战斗前的任务）——防威胁闪烁导致战斗/还原/排班反复拉扯；0 = 还原立即交排班")
            .translation("config.promaid.misc.scheduleRestoreGrace").defineInRange("scheduleRestoreGrace", 60, 0, 400);
    // v1.1.0 实测一百三十三：切换前可用性检测 + 反向抑制三件套
    MISC_SCHEDULE_AVAILABILITY_CHECK = BUILDER.comment("排班切换前完整可用性检测（默认开，实测二百零二同步为你当前配置值）：开启时段任务应用前还检查目标任务附近有没有活干（挖矿有无矿/伐木有无树/烧制有无炉子/酿造有无酿造台/农场有无作物）——没活不切、保持当前任务；关闭 = 只查任务自己的可用开关（isEnable），任务状态跟着时间段落真实切换（实测一百七十档案：旧默认的\"没活不切\"曾把女仆钉死在原地、任务不随段切换——若发现排班任务不切换，先把本项关掉）")
            .translation("config.promaid.misc.scheduleAvailabilityCheck").define("scheduleAvailabilityCheck", true);
    MISC_SCHEDULE_REVERSE_WINDOW_TICKS = BUILDER.comment("排班反向切换窗口（tick，默认 200=10 秒）：两次任务切换间隔在此窗口内才可能被判为 A→B→A 反向横跳；正常时段切换相隔约 2000 tick，天然不会被误判")
            .translation("config.promaid.misc.scheduleReverseWindowTicks").defineInRange("scheduleReverseWindowTicks", 200, 20, 1200);
    MISC_SCHEDULE_REVERSE_THRESHOLD = BUILDER.comment("排班反向切换阈值（默认 2）：窗口内累计反向次数达到该值即压制本次切换")
            .translation("config.promaid.misc.scheduleReverseThreshold").defineInRange("scheduleReverseThreshold", 2, 1, 20);
    MISC_SCHEDULE_REVERSE_COOLDOWN_TICKS = BUILDER.comment("排班反向切换冷却（tick，默认 200=10 秒）：压制反向切换后保持多久不再反向切")
            .translation("config.promaid.misc.scheduleReverseCooldownTicks").defineInRange("scheduleReverseCooldownTicks", 200, 20, 1200);
    // v1.1.0 实测一百七十六（移植 TLM-Sincerely MINIMUM_TASK_HOLD_TICKS）：排班最短持有期
    MISC_SCHEDULE_MIN_HOLD_TICKS = BUILDER.comment("排班最短持有期（tick，默认 60=3 秒，借鉴 TLM-Sincerely MINIMUM_TASK_HOLD_TICKS）：任何一次排班切换后，此期间内不允许再切换（无论段怎么变）——防段边界秒切/战斗还原压任务导致的连切；正常时段切换相隔约 2000 tick，不受影响；0 = 关闭最短持有")
            .translation("config.promaid.misc.scheduleMinHoldTicks").defineInRange("scheduleMinHoldTicks", 60, 0, 1200);
    // v1.1.0 实测一百七十六（移植 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）：切段后大脑自愈
    MISC_SCHEDULE_FORCE_BRAIN_REFRESH = BUILDER.comment("排班切段后大脑自愈（默认开，借鉴 TLM-Sincerely FORCE_BRAIN_REFRESH_ON_STUCK）：段任务应用成功后 3 秒，若女仆任务仍是段任务、但脑内无任何工作记忆（走位/攻击/目标——非坐姿站桩工作可能被 TLM 脑活动卡住），强制 refreshBrain 一次重建 AI；关 = 完全信任 TLM")
            .translation("config.promaid.misc.scheduleForceBrainRefresh").define("scheduleForceBrainRefresh", true);
    // v1.1.0 实测一百八十三（反馈："排班状态下增大活动的范围"）：TLM home 模式 restrictTo
    // 的半径下限（TLM 自带 MAID_WORK/IDLE/SLEEP_RANGE 默认只有 8~16 格）
    // v1.2.2 实测五百六十二：默认 32 → 12 回归 TLM 原版量级——旧默认放大三倍正是
    // "不跟随女仆工作区域走乱"的观感放大器；32 变成明确的大范围选项（迁移只迁旧默认）
    SCHEDULE_ACTIVITY_RANGE = BUILDER.comment("排班活动半径（格，默认 12）：排班/在家模式下女仆的工作区域半径下限——这是 TLM home 模式的「工作区域」圈（工位/休闲锚点 ± 本值），任务选点、散步、巡逻都被钳在圈内；本项取 max(本值, TLM 设置) 生效，想让她大范围干活就调大（8~512）")
            .translation("config.promaid.misc.scheduleActivityRange").defineInRange("scheduleActivityRange", 12, 8, 512);
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
        // v1.1.0 实测四百二十：内置日语语音包（要求——训练日语系统消息语音打进 jar，
        // 触发系统消息自动播放；可在手册/面板调开关、音量、最小间隔；播放时暂压 TLM 原生语音包）
        TTS_JAR_PACK_ENABLED = BUILDER.comment("内置日语语音包（默认开）：随 mod 附带的女仆日语语音（122 条：67 条系统消息 + 49 条排班气泡 + 6 条拥抱/摸头亲昵台词），触发系统消息时自动播放——优先级高于 TLM 原生语音包与 TTS 合成；关掉则只走磁盘语音包/TTS。实测四百四十五：已按情境分五档情绪（战斗·紧张/关心·温柔/俏皮·日常/干活·汇报/请求·为难）重制——同一位女仆的两条参考音频 + 语速区分，不再一律平淡")
                .translation("config.promaid.voice.jarPackEnabled").define("jarPackEnabled", true);
        TTS_JAR_PACK_VOLUME = BUILDER.comment("内置语音包音量倍率（默认 1.0，范围 0.1-20.0）：只作用于内置日语语音包的播放音量，与上面的「TTS 语音播放音量倍率」相乘。实测四百二十七：语音素材已做峰值归一化（响度约 +11 dB），1.0~2.0 一般就够；仍嫌小可调到最高 20")
                .translation("config.promaid.voice.jarPackVolume")
                .defineInRange("jarPackVolume", 1.0, 0.1, 20.0);
        TTS_JAR_PACK_MIN_INTERVAL_S = BUILDER.comment("内置语音包最小间隔（秒，默认 5）：同一女仆两次播放内置语音之间的最小间隔，防连续系统消息刷屏轰炸。"
                        + "实测四百七十五：8 → 5 秒——支援/互助类语音与系统消息共用这道门，间隔太长时一场战斗里只播得出一两句，听感上像「支援语音没做」。"
                        + "调小 = 语音更密（可能重叠），调 0 = 不限制")
                .translation("config.promaid.voice.jarPackMinIntervalS")
                .defineInRange("jarPackMinIntervalS", 5, 0, 60);
        TTS_JAR_PACK_MUTE_NATIVE = BUILDER.comment("播放时暂压原生语音包（默认开）：内置语音播放期间，TLM 原生语音包（女仆音效/语音）暂时静音，播放结束自动解除——避免两套语音重叠")
                .translation("config.promaid.voice.jarPackMuteNative").define("jarPackMuteNative", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * 「口渴」Thirst Was Taken（modid=thirst）是否在场——喂水两项配置的条件注册判据。
     * 本类静态初始化发生在 mod 构造期（ModList 已就绪）；万一被更早加载，保守视为未装
     * （喂水整段不出现，运行时行为由 ThirstCompat.available() 同口径把关，不会矛盾）。
     */
    private static boolean thirstModLoaded() {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded("thirst");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private MaidSmartConfig() {
    }
}
