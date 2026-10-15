package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.AxeItem;

/**
 * 采集类 AI 行为的共享基类（挖矿 / 伐木）：承接两行为**逐字相同**的静态数据表、
 * per-maid 状态表与纯工具。两树各一份（映射名不同，不跨树共享源码）。
 */
public abstract class MaidWorkBehavior extends Behavior<EntityMaid> {

    /** 共享构造：与两个子类原来的 super(emptyMap, MAX, MAX) 完全一致（无限时长）。 */
    protected MaidWorkBehavior() {
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** v1.5.47：废石白名单（丢弃判定用；注册名 path） */
    protected static final java.util.Set<String> JUNK_STONES = java.util.Set.of(
            "stone", "cobblestone", "deepslate", "cobbled_deepslate", "granite", "diorite",
            "andesite", "tuff", "gravel", "dirt", "netherrack", "blackstone", "basalt",
            "soul_sand", "soul_soil", "magma_block", "calcite", "dripstone_block", "sandstone",
            "red_sandstone");
    /**
     * v1.5.47：可开路方块白名单（穿透挖掘只挖这些，不碰箱子/机器等）。
     * v1.5.87 细分：石头类（需镐，耗耐久开路）+ 徒手可挖软方块（草/土/沙，开路不耗镐子耐久）。
     * 名单之外的实心挡路（箱子/机器/基岩等）→ 报点 + 弃置该矿，绝不隔墙挖矿。
     */
    protected static final java.util.Set<String> OPEN_BREAKABLE = new java.util.HashSet<>(JUNK_STONES);

    static {
        OPEN_BREAKABLE.add("grass_block");
        OPEN_BREAKABLE.add("dirt");
        OPEN_BREAKABLE.add("coarse_dirt");
        OPEN_BREAKABLE.add("rooted_dirt");
        OPEN_BREAKABLE.add("podzol");
        OPEN_BREAKABLE.add("mycelium");
        OPEN_BREAKABLE.add("sand");
        OPEN_BREAKABLE.add("red_sand");
        OPEN_BREAKABLE.add("clay");
        OPEN_BREAKABLE.add("mud");
        OPEN_BREAKABLE.add("moss_block");
        OPEN_BREAKABLE.add("snow_block");
        // v1.5.100c：批量加入自然生成、硬度相近的方块（原木/菌柄/去皮/蘑菇/
        // 南瓜西瓜/冰/苔石/珊瑚）——女仆挖矿遇到不再当硬挡路报点弃置。
        // 只进开路白名单，不进 JUNK_STONES（挖出的原木等不是废石，保留）
        OPEN_BREAKABLE.add("oak_log");
        OPEN_BREAKABLE.add("spruce_log");
        OPEN_BREAKABLE.add("birch_log");
        OPEN_BREAKABLE.add("jungle_log");
        OPEN_BREAKABLE.add("acacia_log");
        OPEN_BREAKABLE.add("dark_oak_log");
        OPEN_BREAKABLE.add("mangrove_log");
        OPEN_BREAKABLE.add("cherry_log");
        OPEN_BREAKABLE.add("crimson_stem");
        OPEN_BREAKABLE.add("warped_stem");
        OPEN_BREAKABLE.add("bamboo_block");
        OPEN_BREAKABLE.add("stripped_oak_log");
        OPEN_BREAKABLE.add("stripped_spruce_log");
        OPEN_BREAKABLE.add("stripped_birch_log");
        OPEN_BREAKABLE.add("stripped_jungle_log");
        OPEN_BREAKABLE.add("stripped_acacia_log");
        OPEN_BREAKABLE.add("stripped_dark_oak_log");
        OPEN_BREAKABLE.add("stripped_mangrove_log");
        OPEN_BREAKABLE.add("stripped_cherry_log");
        OPEN_BREAKABLE.add("stripped_crimson_stem");
        OPEN_BREAKABLE.add("stripped_warped_stem");
        OPEN_BREAKABLE.add("brown_mushroom_block");
        OPEN_BREAKABLE.add("red_mushroom_block");
        OPEN_BREAKABLE.add("mushroom_stem");
        OPEN_BREAKABLE.add("pumpkin");
        OPEN_BREAKABLE.add("melon");
        OPEN_BREAKABLE.add("ice");
        OPEN_BREAKABLE.add("packed_ice");
        OPEN_BREAKABLE.add("mossy_cobblestone");
        OPEN_BREAKABLE.add("mossy_stone_bricks");
        OPEN_BREAKABLE.add("tube_coral_block");
        OPEN_BREAKABLE.add("brain_coral_block");
        OPEN_BREAKABLE.add("bubble_coral_block");
        OPEN_BREAKABLE.add("fire_coral_block");
        OPEN_BREAKABLE.add("horn_coral_block");
        OPEN_BREAKABLE.add("dead_tube_coral_block");
        OPEN_BREAKABLE.add("dead_brain_coral_block");
        OPEN_BREAKABLE.add("dead_bubble_coral_block");
        OPEN_BREAKABLE.add("dead_fire_coral_block");
        OPEN_BREAKABLE.add("dead_horn_coral_block");
        // v1.5.102c：石质方块族批量进开路白名单——地表/建筑常见的石头变体
        // （石砖/磨制/深板岩砖/砂岩切制/石英等）不再当硬挡路报点弃置
        OPEN_BREAKABLE.add("smooth_stone");
        OPEN_BREAKABLE.add("stone_bricks");
        OPEN_BREAKABLE.add("cracked_stone_bricks");
        OPEN_BREAKABLE.add("chiseled_stone_bricks");
        OPEN_BREAKABLE.add("polished_granite");
        OPEN_BREAKABLE.add("polished_diorite");
        OPEN_BREAKABLE.add("polished_andesite");
        OPEN_BREAKABLE.add("deepslate_bricks");
        OPEN_BREAKABLE.add("cracked_deepslate_bricks");
        OPEN_BREAKABLE.add("deepslate_tiles");
        OPEN_BREAKABLE.add("cracked_deepslate_tiles");
        OPEN_BREAKABLE.add("polished_deepslate");
        OPEN_BREAKABLE.add("chiseled_deepslate");
        OPEN_BREAKABLE.add("polished_basalt");
        OPEN_BREAKABLE.add("polished_blackstone");
        OPEN_BREAKABLE.add("polished_blackstone_bricks");
        OPEN_BREAKABLE.add("cracked_polished_blackstone_bricks");
        OPEN_BREAKABLE.add("chiseled_polished_blackstone");
        OPEN_BREAKABLE.add("smooth_sandstone");
        OPEN_BREAKABLE.add("cut_sandstone");
        OPEN_BREAKABLE.add("chiseled_sandstone");
        OPEN_BREAKABLE.add("smooth_red_sandstone");
        OPEN_BREAKABLE.add("cut_red_sandstone");
        OPEN_BREAKABLE.add("chiseled_red_sandstone");
        OPEN_BREAKABLE.add("quartz_block");
        OPEN_BREAKABLE.add("smooth_quartz");
        OPEN_BREAKABLE.add("chiseled_quartz_block");
        OPEN_BREAKABLE.add("quartz_bricks");
        OPEN_BREAKABLE.add("prismarine");
        OPEN_BREAKABLE.add("dark_prismarine");
        OPEN_BREAKABLE.add("prismarine_bricks");
        OPEN_BREAKABLE.add("purpur_block");
        OPEN_BREAKABLE.add("purpur_pillar");
        OPEN_BREAKABLE.add("end_stone");
        OPEN_BREAKABLE.add("end_stone_bricks");
        OPEN_BREAKABLE.add("nether_bricks");
        OPEN_BREAKABLE.add("red_nether_bricks");
        OPEN_BREAKABLE.add("cracked_nether_bricks");
        OPEN_BREAKABLE.add("chiseled_nether_bricks");
        OPEN_BREAKABLE.add("packed_mud");
        OPEN_BREAKABLE.add("mud_bricks");
    }
    /** v1.5.102c：报点用中文方块名（矿石/石头/常见挡路块；未知回退英文 path） */
    protected static final java.util.Map<String, String> BLOCK_CN = new java.util.HashMap<>();

    static {
        BLOCK_CN.put("stone", "石头");
        BLOCK_CN.put("cobblestone", "圆石");
        BLOCK_CN.put("deepslate", "深板岩");
        BLOCK_CN.put("cobbled_deepslate", "深板岩圆石");
        BLOCK_CN.put("granite", "花岗岩");
        BLOCK_CN.put("diorite", "闪长岩");
        BLOCK_CN.put("andesite", "安山岩");
        BLOCK_CN.put("tuff", "凝灰岩");
        BLOCK_CN.put("smooth_stone", "平滑石头");
        BLOCK_CN.put("stone_bricks", "石砖");
        BLOCK_CN.put("dirt", "泥土");
        BLOCK_CN.put("coarse_dirt", "砂土");
        BLOCK_CN.put("sand", "沙子");
        BLOCK_CN.put("red_sand", "红沙");
        BLOCK_CN.put("gravel", "沙砾");
        BLOCK_CN.put("clay", "黏土");
        BLOCK_CN.put("mud", "泥巴");
        BLOCK_CN.put("grass_block", "草方块");
        BLOCK_CN.put("bedrock", "基岩");
        BLOCK_CN.put("obsidian", "黑曜石");
        BLOCK_CN.put("water", "水");
        BLOCK_CN.put("lava", "岩浆");
        BLOCK_CN.put("chest", "箱子");
        BLOCK_CN.put("barrel", "木桶");
        BLOCK_CN.put("furnace", "熔炉");
        BLOCK_CN.put("crafting_table", "工作台");
        BLOCK_CN.put("netherrack", "下界岩");
        BLOCK_CN.put("blackstone", "黑石");
        BLOCK_CN.put("basalt", "玄武岩");
        BLOCK_CN.put("magma_block", "岩浆块");
        BLOCK_CN.put("soul_sand", "灵魂沙");
        BLOCK_CN.put("soul_soil", "灵魂土");
        BLOCK_CN.put("calcite", "方解石");
        BLOCK_CN.put("dripstone_block", "滴水石块");
        BLOCK_CN.put("sandstone", "砂岩");
        BLOCK_CN.put("red_sandstone", "红砂岩");
        BLOCK_CN.put("quartz_block", "石英块");
        // 矿石（跳过/稀有播报用）
        BLOCK_CN.put("diamond_ore", "钻石矿石");
        BLOCK_CN.put("deepslate_diamond_ore", "深层钻石矿石");
        BLOCK_CN.put("emerald_ore", "绿宝石矿石");
        BLOCK_CN.put("deepslate_emerald_ore", "深层绿宝石矿石");
        BLOCK_CN.put("gold_ore", "金矿石");
        BLOCK_CN.put("deepslate_gold_ore", "深层金矿石");
        BLOCK_CN.put("nether_gold_ore", "下界金矿石");
        BLOCK_CN.put("iron_ore", "铁矿石");
        BLOCK_CN.put("deepslate_iron_ore", "深层铁矿石");
        BLOCK_CN.put("copper_ore", "铜矿石");
        BLOCK_CN.put("deepslate_copper_ore", "深层铜矿石");
        BLOCK_CN.put("redstone_ore", "红石矿石");
        BLOCK_CN.put("deepslate_redstone_ore", "深层红石矿石");
        BLOCK_CN.put("lapis_ore", "青金石矿石");
        BLOCK_CN.put("deepslate_lapis_ore", "深层青金石矿石");
        BLOCK_CN.put("coal_ore", "煤矿石");
        BLOCK_CN.put("deepslate_coal_ore", "深层煤矿石");
        BLOCK_CN.put("nether_quartz_ore", "下界石英矿石");
        BLOCK_CN.put("ancient_debris", "远古残骸");
        BLOCK_CN.put("amethyst_block", "紫水晶块");
    }
    /** v1.1.0 实测一百一十九：目标方块 AABB 上离女仆最近点的距离平方（与伐木同款）。
     *  旧版取方块中心距离——垂直边界场景（站坑里挖正上方矿石/头顶矿）中心恰好压
     *  在伸手边界上，水平偏移 0.1 格就够不着 → 永远进搭路分支 → 冻结点。最近点
     *  判定后正上方矿底面稳定够得着。 */
    protected static double distSqToBlock(EntityMaid maid, BlockPos pos) {
        double x = Math.max(pos.getX(), Math.min(maid.getX(), pos.getX() + 1));
        double y = Math.max(pos.getY(), Math.min(maid.getY(), pos.getY() + 1));
        double z = Math.max(pos.getZ(), Math.min(maid.getZ(), pos.getZ() + 1));
        double dx = maid.getX() - x;
        double dy = maid.getY() - y;
        double dz = maid.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }
    /** v1.5.102c：方块注册名 path → 中文名。v1.5.140：未知方块回退改用 BlueprintLib
     *  cnName（CN_NAMES 精确表 + 颜色/材质规则兜底，mod 方块也尽量出中文——
     *  旧版直接回退英文 path，反馈"阻挡汇报用英文字符"） */
    protected static String blockCnName(net.minecraft.world.level.block.Block block) {
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return "方块";
        }
        String cn = com.maidsmart.build.BlueprintLib.cnName(key.toString());
        return cn.isEmpty() ? key.getPath() : cn;
    }
    /**
     * 开路不耗镐子耐久、穿透预算不计阻挡；硬方块（矿/石头/箱子/基岩）反之。
     */
    /**
     * 旧判定 hardness ≤ 1 会把低硬度的 mod 矿物（如匠魂/魔法 mod 的 1 硬度矿石）
     * 误判为"软方块"→ softNoDurability=true 时挖矿物也不扣耐久（反馈
     * "就算挖的是矿物也不会掉耐久"的根因）。改为按"镐是否为正确工具"判定： 镐是正确工具的方块（原版+mod 全部矿物/石头/深板岩，无论硬度）一律算硬，必扣。
     */
    protected static boolean isSoft(ItemStack pick, BlockState st) {
        if (pick.isEmpty() || !(pick.getItem() instanceof net.minecraft.world.item.DiggerItem d)) {
            return true; // 徒手/非镐 → 视为软（软方块开路路径，不扣镐耐久）
        }
        return !d.isCorrectToolForDrops(pick, st); // 镐非正确工具 = 徒手可挖的软方块
    }
    /**
     * v1.5.85：当前主手是否能挖这个方块（正确工具判定，对齐玩家破坏判定）。
     * 矿都需要对应等级的镐子：非镐工具 / 徒手 / 等级不够（DiggerItem.m_8096_ 检查
     * needs_stone/iron/diamond_tool tag）都返回 false → 该矿被跳过。
     */
    protected static boolean canHarvest(EntityMaid maid, BlockState state) {
        ItemStack tool = maid.getMainHandItem();
        if (tool.isEmpty() || !(tool.getItem() instanceof net.minecraft.world.item.DiggerItem digger)) {
            return false;
        }
        return digger.isCorrectToolForDrops(tool, state);
    }

    /** v1.5.164：诊断日志（连锁采集/自动收集是否生效的排查用） */
    protected static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * v1.5.89 遗留说明：旧 equipPickaxe 写错位置——getMaidInv() 是背包（36 格），
     * 不是主手；真正的主手是 getHandsInvWrapper() slot 0。换工具已统一收敛到
     * MaidToolAutoEquip（v1.5.90，按任务 UID 自动装备，攻击/弓/弩/三叉戟/挖矿通用）。
     */

    /** v1.5.88：自定义矿表是否已加载（config 在 mod 加载后期才读文件，static 块里读不到） */
    protected static boolean customOresLoaded = false;

    /**
     * v1.5.47：锚点 = 锁定的搜索中心（借鉴 maidmining 的锚定挖矿）。
     * 旧版 findOre 以女仆当前位置为中心——挖一块后中心跟着动，越挖越远（漂移），
     * 锚点锁死后框内由近到远挖光，框空才迁移（v1.5.113：锚点朝主人方向滑动一个
     * 环宽，不走路、不死循环）。
     * 静态 per-maid 表（key = 实体 ID；魂符收放 ID 变化自动失效）。
     */
    protected static final Map<Integer, BlockPos> ANCHORS = new HashMap<>();

    protected static final Map<Integer, Long> OUT_SINCE = new HashMap<>();

    protected static final Map<Integer, Long> LAST_RELOCATE = new HashMap<>();

    /** 【实测九百三十·全链路监听】每只女仆 × 每个"原因"的最后一次打印 tick（见 {@link #traceExit}）。 */
    protected static final Map<Integer, java.util.Map<String, Long>> TRACE_AT = new HashMap<>();
    /** 【实测九百三十】全链路监听的限频间隔（拍）：100 拍 = 5 秒/原因/只。 */
    protected static final long TRACE_INTERVAL = 100L;

    /** v1.5.113：锚点滑动迁移节流（空框时每 5 秒滑一次，防高速漂移） */
    protected static final Map<Integer, Long> SLIDE_SINCE = new HashMap<>();

    /** v1.5.47：目标设定时刻（5 秒未挖动 → 弃置） */
    protected static final Map<Integer, Long> TARGET_SINCE = new HashMap<>();

    /** v1.5.102：播报限频间隔（tick）从配置面板读取（mine.skipReportInterval） */
    protected static final Map<Integer, Long> SKIP_REPORT_SINCE = new HashMap<>();

    /** v1.5.87：硬挡路（箱子/机器/基岩）报点限频（实体 ID → 上次播报 tick） */
    protected static final Map<Integer, Long> BLOCKED_REPORT_SINCE = new HashMap<>();

    /** v1.5.113：够不着的矿短时排除（弃置后 30 秒内不再反复选中；挖掉任一矿即清） */
    protected static final Map<Integer, java.util.Map<BlockPos, Long>> RECENT_DISCARD = new HashMap<>();

    /** v1.5.113：搭方块材料耗尽播报限频（实体 ID → 上次播报 tick） */
    protected static final Map<Integer, Long> NO_BLOCK_REPORT_SINCE = new HashMap<>();

    /** 实测九百七十七：无目标静默窗口截止 tick（实体 ID → 恢复时刻）——挖矿/伐木共用 */
    protected static final Map<Integer, Long> IDLE_PAUSE_UNTIL = new HashMap<>();

    /** v1.5.87：搭方块防掉落窗口（搭块后 12 tick 内钳制，防刚搭完滑落） */
    protected int pillarGuardTicks = 0;

    protected BlockPos targetPos = null;

    /** 当前目标累计破坏进度（0~1，对齐 maid_useful_task 渐进挖掘） */
    protected float destroyProgress = 0.0f;

    /**
     * v1.5.161：连锁采集队列（借鉴 FTB Ultimine 连锁破坏思路）——挖完一块矿后，
     * 从刚挖的位置 BFS 找相连的同族矿石排队，挖完一块自动接下一块，直到队列空。
     * 默认关闭（配置 mine.chainMining）；队列只装"矿"（ORE_VALUE 表内），不连锁石头。
     */
    protected final java.util.ArrayDeque<BlockPos> chainQueue = new java.util.ArrayDeque<>();

    /** v1.5.161：当前连锁的矿方块类型（挖掉一块后记录，BFS 按同 Block 匹配） */
    protected net.minecraft.world.level.block.Block chainBlock = null;

    /** v1.5.22：进度持久化 key（行为被抢占重启时不丢进度） */
    protected static final String PROGRESS_TAG = "maid_smart_mine_progress";

    /** 目标扫描节流：找不到矿时每 20 tick 才扫一次，避免每 tick 全量查询 */
    protected int scanCooldown = 0;

    /** v1.5.113：上一次 findOre 是否做了全量重建（缓存轮=false）——只有全量重建
     *  确认框空才滑动锚点迁移（缓存轮空不迁移，等 5 秒重建再判） */
    protected boolean lastScanWasFull = true;

    /** v1.5.24：搭高节奏计数 */
    protected int pillarCooldown = 0;

    /** v1.5.47：废石检查节流 */
    protected int junkCooldown = 0;

    /** v1.5.105：走过去重设 WalkTarget 节流——每 tick 重设会让 TLM 每 tick 重寻路 → 移动顿挫 */
    protected int walkRetargetCooldown = 0;

    /** v1.5.116：上次设置的移动目标站立点——目标没变且导航行进中不重设
     *  （旧版每 8 tick 无条件重设 WalkTarget → 导航每次重新寻路 → "一走一停"鬼畜） */
    protected BlockPos lastWalkTarget = null;

    /**
     * 实测九百二十八【导航在走 ≠ 人在走】：脚下格没动的判决窗口（拍）。
     * <p>{@link #setWalkTarget} 那句"目标没变且导航还在走就不重设"是为防"一走一停"加的，可**导航在走却走不动**
     * （目标在墙里 / 路径第一段是死路 / 她卡在洞里）时，它与 TLM 自己的恢复通道会互相挡着、永远不重算路径
     * ——玩家看到的就是"站在原地不动"，而且一条日志都没有。满这么多拍没挪窝就强制停导航、重发目标。
     */
    protected static final int WALK_STILL_TICKS = 20;

    /** 上次见到的脚下格（判"有没有在挪窝"） */
    protected BlockPos lastWalkPos = null;

    /** 脚下格上一次变化的 gameTime（-1 = 还没采过样） */
    protected long walkStillSince = -1L;

    /** "走路卡住"留痕限频（gameTime，5 秒/条） */
    protected long walkStuckLogAt = 0L;

    /** 实测九百七十九【链路卡死自愈】：同一目标 + 同一双脚 + 挖掘进度一字未动，连续这么多拍
     *  （160 拍 = 8 秒）→ 认这块真够不到，弃掉重扫。比 WALK_STILL_TICKS 那条"停导航重发"
     *  更久：那条重发的还是同一个走不到的目标，真够不着时她会顶着它无限循环（日志里
     *  "够不着就继续推进 / 走不到→改走接近链"反复出现、气泡一条没有、走位权一直不放）。 */
    protected static final int STALL_GIVEUP_TICKS = 160;
    protected BlockPos stallFeet = null;
    protected BlockPos stallTargetRef = null;
    protected float stallProgressRef = -1.0f;
    protected int stallTicks = 0;
    /** 连续自愈次数（弃目标后又卡 → 累加；她挪窝即清零） */
    protected int stallStreak = 0;

    /** 实测九百三十七【水流卡住兜底】：脚下格不变持续这么多拍即判"被水流卡住"。 */
    protected static final int WATER_STUCK_TICKS = 40;
    /** 上次检测到的脚下格 + 该格开始的时刻（-1 = 不在水里/刚换格）。 */
    protected BlockPos waterStuckAt;
    protected long waterStuckSince = -1L;

    /** 实测九百三十八：目标路径有岩浆的播报节流（实体 ID → 上次 tick）。 */
    protected static final java.util.Map<Integer, Long> LAVA_PATH_REPORT_SINCE = new java.util.HashMap<>();

    /** 任意维度的存活女仆站在该位置（跨维度判定；实测四十二） */
    protected static boolean anyMaidStanding(net.minecraft.server.MinecraftServer server,
                                           BlockPos pos, java.util.function.Predicate<EntityMaid> filter) {
        for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
            if (PlacedBlockTracker.anyMaidStanding(lvl, pos, filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.5.161：自动收集——掉落物塞进女仆背包（优先堆叠已有同物品，再放空槽；
     * 背包放不下才落地为掉落物，不吞物品）。与 dropResources 同款精准/时运判定
     * （调用方用 getDrops 六参版 getDrops 计算）。
     */
    protected static void insertIntoMaidInventory(EntityMaid maid, ServerLevel level,
                                                java.util.List<ItemStack> drops, BlockPos pos) {
        if (drops == null || drops.isEmpty()) {
            return;
        }
        try {
            net.neoforged.neoforge.items.wrapper.CombinedInvWrapper inv = maid.getAvailableInv(true);
            for (ItemStack stack : drops) {
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStack remain = net.neoforged.neoforge.items.ItemHandlerHelper
                        .insertItemStacked(inv, stack, false);
                if (!remain.isEmpty()) {
                    // v1.2.4 实测六百三十六：她自己的背包满了，先试她身上的"额外容器"
                    // （饰品栏里的精妙背包 / 旅行者背包，见 MaidExtraContainer）
                    remain = com.maidsmart.tool.MaidExtraContainer.overflow(maid, remain);
                }
                if (!remain.isEmpty()) {
                    Block.popResource(level, pos, remain); // 背包满：落地（原版 popResource）
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
    }

    /** v1.5.22：进度写入持久数据（跨行为重启保留）。
     *  v1.5.113（A2）：节流——每 tick 改持久化 NBT 会让实体常驻 dirty（存档压力）；
     *  每 20 tick 写一次，进度只需行为重启时恢复，1 秒粒度足够。 */
    protected int progressSaveCooldown = 0;

    protected void saveProgress(EntityMaid maid) {
        if (this.progressSaveCooldown-- > 0) {
            return;
        }
        this.progressSaveCooldown = 20;
        ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putString(PROGRESS_TAG, String.valueOf(this.destroyProgress));
    }

    /** v1.5.113：立即写进度（挖完/弃置/失效时——这些点必须马上持久化，防重启残留旧进度） */
    protected void saveProgressNow(EntityMaid maid) {
        this.progressSaveCooldown = 0;
        this.saveProgress(maid);
    }

    /** v1.5.22：读取持久化进度 */
    protected float loadProgress(EntityMaid maid) {
        try {
            return Float.parseFloat(((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getString(PROGRESS_TAG));
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("MaidMineBehavior", e); return 0.0f;
        }
    }

    /** v1.5.102e：向周围玩家广播挖掘裂纹（ClientboundBlockDestructionPacket）——
     *  stage 0~9 = 裂纹阶段，10 = 移除。女仆不是玩家，原版不会自动发此包，
     *  不加的话方块全程无裂纹、到点瞬间消失（"秒破"观感）。
     *  v1.5.103：只在【阶段变化】时发——同一阶段连发是浪费（慢方块 30 tick
     *  有大量重复阶段），缓存上次位置+阶段，变化才广播。 */
    protected BlockPos lastCrackPos = null;

    protected int lastCrackStage = -1;

    protected void broadcastCrack(ServerLevel level, EntityMaid maid, BlockPos pos, int stage) {
        if (pos.equals(this.lastCrackPos) && stage == this.lastCrackStage) {
            return;
        }
        this.lastCrackPos = pos;
        this.lastCrackStage = stage;
        try {
            net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket pkt =
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(maid.getId(), pos, stage);
            for (net.minecraft.server.level.ServerPlayer p : level.players()) {
                if (p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 1024.0) {
                    p.connection.send(pkt); // ServerGamePacketListenerImpl.send(Packet)
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
    }

    /**
     * 导航每次收新目标都要停下重新寻路（PathNavigation.recomputePath），
     * 女仆到达站立点后永远不再重设 = "完全不会走路"卡死（回归，v1.5.122 修复）。
     * 重设以重新激活寻路，女仆不会停在原地。
     */
    protected void setWalkTarget(EntityMaid maid, BlockPos stand, float speed) {
        // 实测九百二十八【导航在走 ≠ 人在走】：先采一次"脚下格变没变"——下面那句"目标没变就不重设"
        // 只在她**确实在挪窝**时才成立（见 WALK_STILL_TICKS 的注释）。
        BlockPos walkFeet = maid.blockPosition();
        long walkNow = maid.level().getGameTime();
        if (!walkFeet.equals(this.lastWalkPos)) {
            this.lastWalkPos = walkFeet;
            this.walkStillSince = walkNow;
        }
        boolean walkStill = this.walkStillSince >= 0L && walkNow - this.walkStillSince >= WALK_STILL_TICKS;
        boolean sameWalkTarget = this.lastWalkTarget != null
                && Math.abs(stand.getX() - this.lastWalkTarget.getX()) <= 1
                && Math.abs(stand.getZ() - this.lastWalkTarget.getZ()) <= 1
                && stand.getY() == this.lastWalkTarget.getY();
        if (sameWalkTarget) {
            if (!maid.getNavigation().isDone()) {
                if (!walkStill) {
                    this.traceExit(maid, "walk 沿用同一目标(导航在走)", "stand=" + stand + " feet=" + walkFeet);
                    return; // 目标未变（±1 容差内）且导航还在走、她也在挪窝：不重设
                }
                // 导航自称在走，可她整整 1 秒没挪窝 → 它握着一条走不动的路径，谁也不重算。停掉再重发一次，
                // 下面那次 moveTo 才会真正重跑 createPath（"站在原地不动"的根因之一）。
                maid.getNavigation().stop();
            }
            if (walkStill) {
                // 实测九百二十九：导航**已经走完**（isDone = 压根没算出路径）也一样要留痕——旧版
                // 这条闸只在"导航还在走"时说话，于是"无路可走"这种卡法一条日志都没有。
                this.reportWalkStuck(maid, stand, walkNow);
                this.walkStillSince = walkNow;   // 重发之后重新计时，免得每拍都判"卡死"
            }
        }
        this.lastWalkTarget = stand.immutable();
        // v1.5.127：直接导航（借鉴 TLM 农务/伐木移动方式）——不再写 WALK_TARGET
        // 记忆，MoveToTargetSink 永不启动。原版 sink 有 150~250 tick 的 maxDuration，
        // 到点强制 doStop 清 WALK_TARGET（长距离走路每 7.5~12.5 秒被"刹车"停顿一次），
        // 卡住时还触发 random(40)（0~2 秒）冷却 → "走路一停一停"的第二来源。
        // 直接 moveTo 后走路全程无中断；到达/搭路判定由行为自己的 distSq 控制（不变）。
        // 注意：moveTo 内部同目标寻路有缓存（path 复用），配合上面的
        // "目标未变且导航行进中跳过"，不会每 tick 重新寻路。
        // 寻路范围：PathNavigation 的搜索盒 = followRange + 1（女仆约 16~17 格）， 目标更远时 createPath 返回 null → 直接 moveTo 会失败原地不动——超过 14 格就先朝目标方向走 12 格的中间点（导航到达后再重设，自然接力）。
        double hd = Math.sqrt(
                (stand.getX() + 0.5 - maid.getX()) * (stand.getX() + 0.5 - maid.getX())
                        + (stand.getZ() + 0.5 - maid.getZ()) * (stand.getZ() + 0.5 - maid.getZ()));
        if (hd > 14.0) {
            this.traceExit(maid, "walk 远距离接力(>14格)", "hd=" + (long) hd + " stand=" + stand);
            double k = 12.0 / hd;
            maid.getNavigation().moveTo(maid.getX() + (stand.getX() + 0.5 - maid.getX()) * k,
                    maid.getY(),
                    maid.getZ() + (stand.getZ() + 0.5 - maid.getZ()) * k, speed);
        } else {
            maid.getNavigation().moveTo(stand.getX() + 0.5, stand.getY() + 0.5,
                    stand.getZ() + 0.5, speed);
        }
    }

    /** 实测九百二十八：走路卡住的留痕（5 秒/条）——"站在原地不动"必须能从日志里看出为什么。 */
    protected void reportWalkStuck(EntityMaid maid, BlockPos stand, long now) {
        if (now - this.walkStuckLogAt < 100L) {
            return;
        }
        this.walkStuckLogAt = now;
        try {
            double d = Math.sqrt(maid.distanceToSqr(stand.getX() + 0.5, stand.getY() + 0.5, stand.getZ() + 0.5));
            LOGGER.info("work walk stuck: maid={} target={} stand={} feet={} dist={} path={} -> stop+retarget",
                    maid.getUUID(), this.targetPos, stand, maid.blockPosition(), (long) d, !maid.getNavigation().isDone());
        } catch (Exception e) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", e);
        }
    }

    /** 实测九百七十九【链路卡死自愈】：返回 true = 本拍已自愈处理（调用方直接 return）。
     *  <p>判据 = 同一目标 ∧ 同一双脚格 ∧ 挖掘进度一字未动，连续 {@link #STALL_GIVEUP_TICKS} 拍。
     *  成立即：目标进 30 秒短排 → 清目标/进度/连锁 → 停导航 + 清走位目标 + **放走位权**
     *  → 限频气泡说一声（"不报错"的另一半）；连弃 3 次交 {@link #onStallStreak} 换一片。 */
    protected boolean stallWatchdog(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos feet = maid.blockPosition();
        if (this.stallWatchdogSuppressed(level, maid)) {
            this.stallFeet = feet;
            this.stallTargetRef = this.targetPos;
            this.stallProgressRef = this.destroyProgress;
            this.stallTicks = 0;
            return false;
        }
        boolean same = this.targetPos != null && feet.equals(this.stallFeet)
                && this.targetPos.equals(this.stallTargetRef)
                && Math.abs(this.destroyProgress - this.stallProgressRef) < 1.0E-4f;
        if (!same) {
            if (!feet.equals(this.stallFeet)) {
                this.stallStreak = 0; // 挪窝了 = 在干活，连弃计数清零
            }
            this.stallFeet = feet;
            this.stallTargetRef = this.targetPos;
            this.stallProgressRef = this.destroyProgress;
            this.stallTicks = 0;
            return false;
        }
        if (++this.stallTicks < STALL_GIVEUP_TICKS) {
            return false;
        }
        this.stallTicks = 0;
        this.stallStreak++;
        BlockPos dead = this.targetPos;
        RECENT_DISCARD.computeIfAbsent(maid.getId(), k -> new HashMap<>())
                .put(dead.immutable(), gameTime);
        this.onStallGiveUp(level, maid, gameTime, dead);
        this.targetPos = null;
        this.destroyProgress = 0.0f;
        this.chainQueue.clear();
        this.chainBlock = null;
        this.scanCooldown = 0;
        this.walkRetargetCooldown = 0;
        this.lastWalkTarget = null;
        this.walkStillSince = -1L;
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        MaidWorkTags.setChainDrive(maid, false);
        this.notifyNoTarget(maid, gameTime, "这块我够不到，先换个地方找找～");
        LOGGER.info("work stall self-heal: maid={} target={} feet={} streak={}",
                maid.getUUID(), dead, feet, this.stallStreak);
        if (this.stallStreak >= 3) {
            this.stallStreak = 0;
            this.onStallStreak(level, maid, gameTime);
        }
        return true;
    }

    /** 卡死判定豁免（子类：分帧扫描进行中她的"站着等"是正常的，不打表） */
    protected boolean stallWatchdogSuppressed(ServerLevel level, EntityMaid maid) {
        return false;
    }

    /** 卡死弃目标的子类收尾（进度落盘等） */
    protected void onStallGiveUp(ServerLevel level, EntityMaid maid, long gameTime, BlockPos dead) {
    }

    /** 连弃 3 次：换一片（子类清缓存 + 挪锚点） */
    protected void onStallStreak(ServerLevel level, EntityMaid maid, long gameTime) {
    }

    /**
     * 【实测九百三十·全链路监听】挖矿/伐木**每一拍走的是哪条出口**都留一行（同一"原因"5 秒一条）。
     * <p>只写日志，**不改任何控制流**：再现"站着不动"时，把 {@code work exit:} 那几行贴出来即可定位分支。
     * @param reason 出口名（带 {@code mine } / {@code walk } 前缀，便于筛选）
     * @param detail 该出口的关键现场值（距离 / 目标 / 工具 / 进度 / 计数……）
     */
    protected void traceExit(EntityMaid maid, String reason, String detail) {
        try {
            int id = maid.getId();
            long now = maid.level().getGameTime();
            java.util.Map<String, Long> per = TRACE_AT.computeIfAbsent(id, k -> new java.util.HashMap<>());
            Long last = per.get(reason);
            if (last != null && now - last < TRACE_INTERVAL) {
                return;
            }
            per.put(reason, now);
            LOGGER.info("work exit: maid={} reason={} detail={}", maid.getUUID(), reason, detail);
            if (TRACE_AT.size() > 512) {
                TRACE_AT.clear(); // 兜底：表不会无限涨
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", ignored);
        }
    }

    /** v1.1.0 实测一百九十二：垫台阶/桥块后登记"走上去"目标（BlockWalkOn 持续推送，
     *  踏入即停）——取代寻路版 walkToStep（跨沟/断崖寻路半路折断 = 死循环根因） */
    protected void startStepOn(EntityMaid maid, double hx, double hz, double hDist) {
        this.startStepOn(maid, hx, hz, hDist, 0);
    }

    /** v1.1.0 下方下降：yOffset = -1 时目标脚位比当前低 1 格（下行台阶） */
    protected void startStepOn(EntityMaid maid, double hx, double hz, double hDist, int yOffset) {
        int y = maid.blockPosition().getY() + yOffset;
        int tx = (int) Math.floor(maid.getX() + hx / hDist);
        int tz = (int) Math.floor(maid.getZ() + hz / hDist);
        BlockWalkOn.start(maid, tx + 0.5, y, tz + 0.5);
    }

    /** 实测九百七十三：本框确认没有目标了 → 气泡告诉主人一声（30 秒/条）。
     *  限频表复用基类的 NO_BLOCK_REPORT_SINCE（挖矿/伐木共用一份，反正一只女仆同一时刻只干一件活）。 */
    protected void notifyNoTarget(EntityMaid maid, long gameTime, String text) {
        Long last = NO_BLOCK_REPORT_SINCE.get(maid.getId());
        if (last != null && gameTime - last < 600L) {
            return; // 30 秒内已经说过一次
        }
        NO_BLOCK_REPORT_SINCE.put(maid.getId(), gameTime);
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", ignored);
        }
    }

    // 实测九百八十【删"自己写的闲逛"】：无目标静默窗口与"框空"一律不再由本模组写走位点——
    // 只把走位权与导航让出去，这 10 秒她走的是 TLM 空闲那一套（跟随主人 / 她自己闲逛）。
    // 旧版自写的闲逛点会抢 TLM 的跟随目标（"只跟主人两秒"），点还常常离她不到 1 格
    // （"走一步停一步"）。
    /** 实测九百七十七：进入无目标静默窗口（秒数取配置 mine.noTargetPause；0 = 关掉本机制）。
     *  窗口内挖矿/伐木 tick 直接让位：清目标、还走位权、空闲闲逛，见两个子类 tick 头部。 */
    protected void startIdlePause(EntityMaid maid, long gameTime) {
        int sec = com.maidsmart.config.MaidSmartConfig.MINE_NO_TARGET_PAUSE.get();
        if (sec <= 0) {
            return;
        }
        IDLE_PAUSE_UNTIL.put(maid.getId(), gameTime + sec * 20L);
        // 实测九百八十二：静默窗口同时按"自主战斗切换"那套切一次任务（见下）
        armWorkIdleSwitch(maid, gameTime, sec);
    }


    // ---- 实测九百八十二：无目标 → 照自主战斗切换那套「切走 → 到点切回」 ----
    /** 待切换状态：实体 ID → [切换到期 tick, 还原 tick, 空闲时长] */
    protected static final Map<Integer, long[]> WORK_IDLE_MEM = new HashMap<>();
    /** 切走前她在哪个任务（还原用同一份 UID） */
    protected static final Map<Integer, String> WORK_IDLE_PREV = new HashMap<>();
    /** 气泡之后等这么久才切（40 拍 = 2 秒，先让主人看清那句提醒） */
    protected static final int WORK_IDLE_DELAY_TICKS = 40;

    /** 实测九百八十二：记下她当前的工作任务，{@link #WORK_IDLE_DELAY_TICKS} 拍后切空闲。
     *  排班模式不参与（任务归排班管，与自主战斗同一口径）。 */
    protected void armWorkIdleSwitch(EntityMaid maid, long gameTime, int sec) {
        try {
            if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                return;
            }
            if (maid.getTask() == null || maid.getTask().getUid() == null) {
                return;
            }
            int id = maid.getId();
            WORK_IDLE_PREV.put(id, maid.getTask().getUid().toString());
            WORK_IDLE_MEM.put(id, new long[]{gameTime + WORK_IDLE_DELAY_TICKS, 0L, sec * 20L});
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", ignored);
        }
    }

    /** 实测九百八十二：每 tick（挂在常驻 core 行为上）——到点切空闲、满协议时长切回工作。
     *  真在战斗 / 排班接管 → 顺延让位，等它收场；被打断过也一样把她送回工作。 */
    public static void tickWorkIdleSwitch(EntityMaid maid, long gameTime) {
        try {
            int id = maid.getId();
            long[] st = WORK_IDLE_MEM.get(id);
            if (st == null) {
                return;
            }
            if (st[1] == 0L) {
                if (gameTime < st[0]) {
                    return;
                }
                if (!switchMaidTask(maid, net.minecraft.resources.ResourceLocation.parse("touhou_little_maid:idle"))) {
                    WORK_IDLE_MEM.remove(id); // 切不过去（任务没了/守卫拒绝）：别赖着
                    WORK_IDLE_PREV.remove(id);
                    return;
                }
                st[1] = gameTime + st[2];
                LOGGER.info("work idle-switch: maid={} -> idle {} tick", maid.getUUID(), st[2]);
                return;
            }
            if (gameTime < st[1]) {
                return;
            }
            if (com.maidsmart.combat.AutoCombatSwitch.isReallyCombatActive(maid)
                    || com.maidsmart.schedule.ScheduleData.isOn(maid)) {
                st[1] = gameTime + 20L; // 让位：战斗/排班收场后再还原
                return;
            }
            String prev = WORK_IDLE_PREV.remove(id);
            WORK_IDLE_MEM.remove(id);
            if (prev == null) {
                return;
            }
            if (switchMaidTask(maid, net.minecraft.resources.ResourceLocation.parse(prev))) {
                LOGGER.info("work idle-switch: maid={} -> back {}", maid.getUUID(), prev);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", ignored);
        }
    }

    /** 实测九百八十二：自动系统切任务——照自主战斗那条路（ScheduleSwitchGuard 放行内部切换） */
    private static boolean switchMaidTask(EntityMaid maid,
                                          net.minecraft.resources.ResourceLocation uid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task =
                    com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.findTask(uid).orElse(null);
            if (task == null) {
                return false;
            }
            com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(maid.getUUID(), task.getUid(),
                    () -> maid.setTask(task));
            return maid.getTask() != null && task.getUid().equals(maid.getTask().getUid());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", ignored);
            return false;
        }
    }

    /** 实测九百七十七：是否处于无目标静默窗口（到期自动清除，下 tick 恢复正常链路） */
    protected boolean inIdlePause(EntityMaid maid, long gameTime) {
        Long until = IDLE_PAUSE_UNTIL.get(maid.getId());
        if (until == null) {
            return false;
        }
        if (gameTime >= until) {
            IDLE_PAUSE_UNTIL.remove(maid.getId());
            return false;
        }
        return true;
    }

    /** v1.5.113（C2）：搭方块材料耗尽播报（限频 30 秒） */
    protected void notifyNoBuildBlock(EntityMaid maid) {
        // 实测四百四十三：悬空禁搭导致的"取料失败"不是真的没材料——静默
        if (com.maidsmart.tool.MaidPlaceGuard.blocked(maid)) {
            return;
        }
        long now = maid.level().getGameTime();
        Long last = NO_BLOCK_REPORT_SINCE.get(maid.getId());
        if (last != null && now - last < 600L) {
            return;
        }
        NO_BLOCK_REPORT_SINCE.put(maid.getId(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "背包里没有能搭的方块了（圆石/泥土等），够不着高处的矿……请给我一些方块～");
    }

    /** v1.5.25f：防窒息兜底（移植自保 antiSuffocate）——每 tick 检测女仆"身体中心"
     * （半身位置）所在格是否为实心满块——是则说明她卡进了方块（搭方块后位移滞后 /
     * 放偏 / 被推挤），直接 setPos 把位置强制移到该方块顶面之上。头顶检查拦不住
     * 横向卡入，这是最后一道保险：宁可瞬移半步也不让她被自己搭的方块闷住。
     */
    protected void antiSuffocate(EntityMaid maid) {        net.minecraft.world.phys.AABB box = maid.getBoundingBox();
        double cy = box.minY + (box.maxY - box.minY) * 0.5; // 身体中心高度（半身）
        BlockPos mid = new BlockPos((int) Math.floor(maid.getX()),
                (int) Math.floor(cy), (int) Math.floor(maid.getZ()));
        BlockState st = maid.level().getBlockState(mid);
        if (st.isAir() || !st.isRedstoneConductor(maid.level(), mid)) {
            return; // 身体中心在空气 / 非实心满块（楼梯台阶不算卡住）
        }
        double top = mid.getY() + 1.0;
        if (top > box.minY + 0.01) {
            maid.setPos(maid.getX(), top + 0.02, maid.getZ());
        }
    }

    /**
     * 实测九百三十七【水流卡住兜底】：水流会把女仆顶在方块上 / 泡在一格水里动不了——头在水外
     * 不触发窒息、也没人管，她就一直卡在原地不干活。每拍检测"她在水里 + 脚下格不变"，
     * 卡够 {@link #WATER_STUCK_TICKS} 拍就找附近一个【干燥可站】的落脚点把她挪过去
     * （与 antiSuffocate 同一种"宁可瞬移半步"的最后手段）；挪完留一行日志。
     */
    protected void antiWaterStuck(net.minecraft.server.level.ServerLevel level, EntityMaid maid, long gameTime) {
        if (!maid.isInWater()) {
            this.waterStuckAt = null;
            this.waterStuckSince = -1L;
            return;
        }
        BlockPos feet = maid.blockPosition();
        if (this.waterStuckAt == null || !feet.equals(this.waterStuckAt)) {
            this.waterStuckAt = feet;
            this.waterStuckSince = gameTime;
            return;
        }
        if (gameTime - this.waterStuckSince < WATER_STUCK_TICKS) {
            return;
        }
        this.waterStuckSince = gameTime;
        BlockPos dry = findDryStand(level, feet);
        if (dry != null) {
            maid.getNavigation().stop();
            maid.setPos(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5);
            this.waterStuckAt = dry;
            LOGGER.info("work water stuck escape: maid={} from={} to={}", maid.getUUID(), feet, dry);
        }
    }

    /** 附近（水平 ±3、垂直 -1..+2）最近的一格【干燥可站】：本格与其上一格都不是水、本格空气、脚下实心。取不到 → null。 */
    protected BlockPos findDryStand(net.minecraft.server.level.ServerLevel level, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos c = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(c).isAir() || waterAt(level, c)) {
                        continue;
                    }
                    if (waterAt(level, c.above())) {
                        continue;
                    }
                    BlockPos under = c.below();
                    BlockState us = level.getBlockState(under);
                    if (us.isAir() || !us.isRedstoneConductor(level, under)) {
                        continue;
                    }
                    double d = (double) dx * dx + (double) dz * dz + (double) dy * dy * 2.0;
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    /** 这一格是不是水（读不到 → 不算水）。 */
    protected static boolean waterAt(net.minecraft.server.level.ServerLevel level, BlockPos p) {
        try {
            return level.getBlockState(p).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        } catch (Throwable t) {
            com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", t);
            return false;
        }
    }

    /** 实测九百三十八：目标"跑出检索半径"判据——水平距离 > radius 就算（垂直不算）。 */
    protected boolean targetOutOfRadius(EntityMaid maid, BlockPos target, int radius) {
        double dx = maid.getX() - (target.getX() + 0.5);
        double dz = maid.getZ() - (target.getZ() + 0.5);
        return dx * dx + dz * dz > (double) radius * radius;
    }

    /** 实测九百三十八：她到目标（眼睛 → 目标中心，每 0.5 格采样）之间有没有岩浆挡着。 */
    protected boolean lavaOnPath(net.minecraft.server.level.ServerLevel level, EntityMaid maid, BlockPos target) {
        try {
            double sx = maid.getX();
            double sy = maid.getY() + 1.5;
            double sz = maid.getZ();
            double dx = (target.getX() + 0.5) - sx;
            double dyy = (target.getY() + 0.5) - sy;
            double dz = (target.getZ() + 0.5) - sz;
            double dist = Math.sqrt(dx * dx + dyy * dyy + dz * dz);
            int steps = Math.max(2, (int) (dist / 0.5));
            for (int i = 1; i < steps; i++) {
                double t = (double) i / steps;
                BlockPos p = new BlockPos((int) Math.floor(sx + dx * t),
                        (int) Math.floor(sy + dyy * t), (int) Math.floor(sz + dz * t));
                if (level.getBlockState(p).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            com.maidsmart.diag.Diag.swallow("MaidWorkBehavior", t);
        }
        return false;
    }

    /** 实测九百三十八：目标路径上有岩浆的播报（30 秒/条，不刷屏）。 */
    protected void reportLavaPath(EntityMaid maid, BlockPos target, long gameTime) {
        Long last = LAVA_PATH_REPORT_SINCE.get(maid.getId());
        if (last != null && gameTime - last < 600L) {
            return;
        }
        LAVA_PATH_REPORT_SINCE.put(maid.getId(), gameTime);
        LOGGER.info("work lava on path: maid={} target={}", maid.getUUID(), target);
        maid.getChatBubbleManager().addTextChatBubble(
                "去目标（坐标 " + target.getX() + ", " + target.getY() + ", " + target.getZ()
                        + "）的路上有岩浆，我先绕一下～");
    }

    /** v1.5.24：取背包中数量最多的可搭方块（BlockItem + 非下落），用于搭高挖矿。
     *  v1.1.0 实测二百三十一：含手部栏（主/副手）——手里拿的方块也能垫（审计修复） */
    protected Item takeBuildBlock(EntityMaid maid) {
        // 实测四百四十三：悬空/坠落中禁搭方块（含重锤跃起）——统一闸口
        if (com.maidsmart.tool.MaidPlaceGuard.blocked(maid)) {
            return null;
        }
        // v1.1.0 实测七：统一走 MaidBuildBlockFilter——火把等无碰撞方块不再入选
        // v1.2.4 实测六百三十九【先判背包，再判精妙背包】：她自己背包里没有可用垫脚方块时，
        // 先请 TLM 的额外容器系统从精妙背包/旅行者背包搬一组进来（pull 先扫她自己的背包，
        // 找到就什么都不做——零副作用）。与 issue #19/#21 的"搭路取材"共用同一份口径。
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                s -> com.maidsmart.tool.MaidBuildBlockFilter.isUsableBuildStack(s, null, null), -1);
        // ---- v1.2.3-dbg 探针：取料前后真实库存对照（查完删掉） ----
        net.neoforged.neoforge.items.IItemHandler __inv = maid.getAvailableBackpackInv();
        net.neoforged.neoforge.items.IItemHandler __hands = maid.getHandsInvWrapper();
        String __c0 = com.maidsmart.tool.MaidBuildBlockFilter.probeCounts(__inv, __hands)
                + " hands[" + com.maidsmart.tool.MaidBuildBlockFilter.probeHands(__hands) + "]";
        Item __took = com.maidsmart.tool.MaidBuildBlockFilter.takeBuildBlock(__inv, __hands, null, null,
                com.maidsmart.combat.BombPose.offhandBorrowed(maid));
        return __took;
    }

    /**
     * 本行为对应的任务 uid（**子类唯一事实源**）：canStart / canStillUse 都读它，不各写一份字面量。
     *
     * <p>实测九百一十一：成员上提时把挖矿那一份 uid 判定写死进基类 → 伐木的 canStillUse
     * 恒 false（任务 uid 是 woodcut，永远等不上下面的 mine）→ 行为每 tick 刚启动就被停，
     * 女仆「彻底不会砍树」（`logs/promaid.log` 里只剩反复的 `wood behavior start`）。
     * 所以 uid 归子类，基类只问"你干的是哪个任务"。
     */
    protected abstract ResourceLocation workTaskUid();

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.113：工作任务期间【常驻】——目标挖完不结束，下一 tick 继续扫描/
        // 迁移（与 canStart 常驻配套）；切走任务才停止（stop 退场）。
        // 旧版 targetPos 挖完即 false → 行为停止 → 女仆原地发呆不找下一个目标。
        return maid.getTask() != null && workTaskUid().equals(maid.getTask().getUid());
    }

    /** v1.5.189：危险方块注册名（与自保 SelfPreservationBehavior.DANGER_BLOCKS 同款） */
    protected static final String[] DANGER_BLOCK_IDS = {
            "minecraft:lava", "minecraft:fire", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:campfire"
    };

    /** v1.5.47：是否可开路废石（穿透挖掘的临时目标；v1.5.87 含徒手可挖软方块）
     *  v1.5.99c：getNamespace 是 getNamespace（SRG）返回 "minecraft"，永远匹配不上白名单
     *  → 泥土/圆石全被当硬挡路（"拒绝挖掘被泥土包裹的矿物"根因）。getPath 的
     *  正确 SRG 名是 getPath（javap 字节码实证：构造器参数 1=namespace→namespace，
     *  getNamespace 返回 namespace，getPath 返回 path=path）。
     *  v1.5.101b：并入面板障碍物名单（MINE_BREAKABLES），统一走 isBreakable。 */
    protected boolean isOpenStone(ServerLevel level, BlockPos pos) {
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return key != null && isBreakable(key.getPath());
    }

    /** v1.5.101b：是否可挖穿——内置 OPEN_BREAKABLE（自然生成方块）或 面板障碍物名单。
     *  v1.5.102d：基岩/屏障等不可破坏方块永远返回 false（防面板误加后女仆傻挖）
     *  v1.0.4：内置方块再扣除"已取消挖穿"的排除名单（MINE_DISABLED_BREAKABLES）——
     *  界面取消内置障碍物打勾后，isBreakable 对其返回 false，女仆不再挖穿 */
    protected static boolean isBreakable(String path) {
        if ("bedrock".equals(path) || "barrier".equals(path)) {
            return false;
        }
        if (OPEN_BREAKABLE.contains(path)) {
            return !isDisabledBreakable(path);
        }
        try {
            for (String e : com.maidsmart.config.MaidSmartConfig.MINE_BREAKABLES.get()) {
                if (e != null && e.equals(path)) {
                    return true;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
        return false;
    }

    /** v1.0.4：内置障碍物是否已被界面取消挖穿（加入 MINE_DISABLED_BREAKABLES 排除名单） */
    protected static boolean isDisabledBreakable(String path) {
        try {
            for (String e : com.maidsmart.config.MaidSmartConfig.MINE_DISABLED_BREAKABLES.get()) {
                if (e != null && e.equals(path)) {
                    return true;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("MaidMineBehavior", ignored);
        }
        return false;
    }

    /**
     * v1.5.102d：是否内置自然方块（配置面板障碍物页据此预勾选"所有自然生成的方块"）。
     * v1.0.4：被取消挖穿（在排除名单）的内置方块不再计入"已勾选"。
     */
    public static boolean isBuiltInBreakable(String path) {
        return !"bedrock".equals(path) && !"barrier".equals(path)
                && OPEN_BREAKABLE.contains(path) && !isDisabledBreakable(path);
    }

    /**
     * v1.0.4：是否为内置可挖穿方块（不关心是否已被取消挖穿）——供界面 toggle 判定
     * 该方块走"内置排除名单"还是"额外正名单"，即使已取消勾选也仍是内置方块。
     */
    public static boolean isBuiltinBreakableBlock(String path) {
        return !"bedrock".equals(path) && !"barrier".equals(path) && OPEN_BREAKABLE.contains(path);
    }

    /** v1.5.85：这个矿需要什么等级的镐子（按原版挖掘 tag 判定） */
    protected static String requiredTool(BlockState state) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        state.getTags().forEach(t -> paths.add(t.location().getPath()));
        if (paths.contains("needs_diamond_tool")) {
            return "钻石镐";
        }
        if (paths.contains("needs_iron_tool")) {
            return "铁镐";
        }
        if (paths.contains("needs_stone_tool")) {
            return "石镐";
        }
        return "镐子";
    }

    /** 女仆眼睛高度（脚下偏移，格）。原版公式 0.85×身高：默认女仆身高 1.5 → 约 1.27；
     *  玩家使用的大正酒狐女仆模型实测头中心约 1.5 格，v1.0.5 起按模型取 1.5。 */
    protected static final double EYE_HEIGHT = 1.5;

    /** v1.5.87：挡路块判定结果——openable=true 挖掉开路；false 报点弃置 */
    protected record Blocker(BlockPos pos, boolean openable) {
    }
}
