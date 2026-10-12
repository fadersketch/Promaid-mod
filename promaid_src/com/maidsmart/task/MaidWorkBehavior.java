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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
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
        double x = Math.max(pos.m_123341_(), Math.min(maid.m_20185_(), pos.m_123341_() + 1));
        double y = Math.max(pos.m_123342_(), Math.min(maid.m_20186_(), pos.m_123342_() + 1));
        double z = Math.max(pos.m_123343_(), Math.min(maid.m_20189_(), pos.m_123343_() + 1));
        double dx = maid.m_20185_() - x;
        double dy = maid.m_20186_() - y;
        double dz = maid.m_20189_() - z;
        return dx * dx + dy * dy + dz * dz;
    }
    /** v1.5.102c：方块注册名 path → 中文名。v1.5.140：未知方块回退改用 BlueprintLib
     *  cnName（CN_NAMES 精确表 + 颜色/材质规则兜底，mod 方块也尽量出中文——
     *  旧版直接回退英文 path，反馈"阻挡汇报用英文字符"） */
    protected static String blockCnName(net.minecraft.world.level.block.Block block) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return "方块";
        }
        String cn = com.maidsmart.build.BlueprintLib.cnName(key.toString());
        return cn.isEmpty() ? key.m_135815_() : cn;
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
        if (pick.m_41619_() || !(pick.m_41720_() instanceof net.minecraft.world.item.DiggerItem d)) {
            return true; // 徒手/非镐 → 视为软（软方块开路路径，不扣镐耐久）
        }
        return !d.m_8096_(st); // 镐非正确工具 = 徒手可挖的软方块
    }
    /**
     * v1.5.85：当前主手是否能挖这个方块（正确工具判定，对齐玩家破坏判定）。
     * 矿都需要对应等级的镐子：非镐工具 / 徒手 / 等级不够（DiggerItem.m_8096_ 检查
     * needs_stone/iron/diamond_tool tag）都返回 false → 该矿被跳过。
     */
    protected static boolean canHarvest(EntityMaid maid, BlockState state) {
        ItemStack tool = maid.m_21205_();
        if (tool.m_41619_() || !(tool.m_41720_() instanceof net.minecraft.world.item.DiggerItem digger)) {
            return false;
        }
        return digger.m_8096_(state);
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

    /** v1.1.0 实测六十九：发呆看门狗——最近一次"真实进展"时刻（挖掉/垫了方块）。长时间零进展
     *  且原地不动 = 发呆/死循环，自动整体重置该女仆的全部行为状态（等效收回魂符再放下去）。 */
    protected static final Map<Integer, Long> LAST_PROGRESS = new HashMap<>();

    /** 看门狗窗口采样（实体 ID → [窗口起点 tick, 起点 x/y/z 各自的 double 位模式]） */
    protected static final Map<Integer, long[]> WATCH_SAMPLE = new HashMap<>();

    /** 看门狗重置播报限频（实体 ID → 上次播报 tick） */
    protected static final Map<Integer, Long> RESET_REPORT_SINCE = new HashMap<>();

    /** v1.1.0 实测六十九：登记一次真实进展（挖掉/垫了方块）——给看门狗续命 */
    protected static void markProgress(EntityMaid maid, long gameTime) {
        LAST_PROGRESS.put(maid.m_19879_(), gameTime);
    }

    /** v1.5.87：搭方块防掉落窗口（搭块后 12 tick 内钳制，防刚搭完滑落） */
    protected int pillarGuardTicks = 0;

    /** v1.5.47：刚弃置的目标（findOre 排除一次；挖掉任一矿后清除） */
    protected BlockPos abandonedPos = null;

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

    /** 实测八百六十九：锁定目标期间的重挑倒计时（见 {@link #MINE_REPICK_INTERVAL}） */
    protected int repickCooldown = 0;

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

    /** 任意维度的存活女仆站在该位置（跨维度判定；实测四十二） */
    protected static boolean anyMaidStanding(net.minecraft.server.MinecraftServer server,
                                           BlockPos pos, java.util.function.Predicate<EntityMaid> filter) {
        for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
            if (PlacedBlockTracker.anyMaidStanding(lvl, pos, filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.5.161：自动收集——掉落物塞进女仆背包（优先堆叠已有同物品，再放空槽；
     * 背包放不下才落地为掉落物，不吞物品）。与 dropResources 同款精准/时运判定
     * （调用方用 getDrops 六参版 m_49874_ 计算）。
     */
    protected static void insertIntoMaidInventory(EntityMaid maid, ServerLevel level,
                                                java.util.List<ItemStack> drops, BlockPos pos) {
        if (drops == null || drops.isEmpty()) {
            return;
        }
        try {
            net.minecraftforge.items.wrapper.CombinedInvWrapper inv = maid.getAvailableInv(true);
            for (ItemStack stack : drops) {
                if (stack.m_41619_()) {
                    continue;
                }
                ItemStack remain = net.minecraftforge.items.ItemHandlerHelper
                        .insertItemStacked(inv, stack, false);
                if (!remain.m_41619_()) {
                    // v1.2.4 实测六百三十六：她自己的背包满了，先试她身上的"额外容器"
                    // （饰品栏里的精妙背包 / 旅行者背包，见 MaidExtraContainer）
                    remain = com.maidsmart.tool.MaidExtraContainer.overflow(maid, remain);
                }
                if (!remain.m_41619_()) {
                    Block.m_49840_(level, pos, remain); // 背包满：落地（原版 popResource）
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
        maid.getPersistentData().m_128359_(PROGRESS_TAG, String.valueOf(this.destroyProgress));
    }

    /** v1.5.113：立即写进度（挖完/弃置/失效时——这些点必须马上持久化，防重启残留旧进度） */
    protected void saveProgressNow(EntityMaid maid) {
        this.progressSaveCooldown = 0;
        this.saveProgress(maid);
    }

    /** v1.5.22：读取持久化进度 */
    protected float loadProgress(EntityMaid maid) {
        try {
            return Float.parseFloat(maid.getPersistentData().m_128461_(PROGRESS_TAG));
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("MaidMineBehavior", e); return 0.0f;
        }
    }

    /** v1.1.0 实测六十九：窗口起点采样 */
    protected static long[] watchSample(long gameTime, EntityMaid maid) {
        return new long[]{gameTime,
                Double.doubleToLongBits(maid.m_20185_()),
                Double.doubleToLongBits(maid.m_20186_()),
                Double.doubleToLongBits(maid.m_20189_())};
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
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(maid.m_19879_(), pos, stage);
            for (net.minecraft.server.level.ServerPlayer p : level.m_6907_()) {
                if (p.m_20275_(pos.m_123341_(), pos.m_123342_(), pos.m_123343_()) < 1024.0) {
                    p.f_8906_.m_9829_(pkt); // ServerGamePacketListenerImpl.send(Packet)
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
        if (this.lastWalkTarget != null
                && Math.abs(stand.m_123341_() - this.lastWalkTarget.m_123341_()) <= 1
                && Math.abs(stand.m_123343_() - this.lastWalkTarget.m_123343_()) <= 1
                && stand.m_123342_() == this.lastWalkTarget.m_123342_()
                && !maid.m_21573_().m_26571_()) {
            return; // 目标未变（±1 容差内）且导航还在走：不重设
        }
        this.lastWalkTarget = stand.m_7949_();
        // v1.5.127：直接导航（借鉴 TLM 农务/伐木移动方式）——不再写 WALK_TARGET
        // 记忆，MoveToTargetSink 永不启动。原版 sink 有 150~250 tick 的 maxDuration，
        // 到点强制 doStop 清 WALK_TARGET（长距离走路每 7.5~12.5 秒被"刹车"停顿一次），
        // 卡住时还触发 random(40)（0~2 秒）冷却 → "走路一停一停"的第二来源。
        // 直接 moveTo 后走路全程无中断；到达/搭路判定由行为自己的 distSq 控制（不变）。
        // 注意：m_26519_ 内部同目标寻路有缓存（f_26496_ 复用），配合上面的
        // "目标未变且导航行进中跳过"，不会每 tick 重新寻路。
        // 寻路范围：PathNavigation 的搜索盒 = followRange + 1（女仆约 16~17 格）， 目标更远时 createPath 返回 null → 直接 moveTo 会失败原地不动——超过 14 格就先朝目标方向走 12 格的中间点（导航到达后再重设，自然接力）。
        double hd = Math.sqrt(
                (stand.m_123341_() + 0.5 - maid.m_20185_()) * (stand.m_123341_() + 0.5 - maid.m_20185_())
                        + (stand.m_123343_() + 0.5 - maid.m_20189_()) * (stand.m_123343_() + 0.5 - maid.m_20189_()));
        if (hd > 14.0) {
            double k = 12.0 / hd;
            maid.m_21573_().m_26519_(maid.m_20185_() + (stand.m_123341_() + 0.5 - maid.m_20185_()) * k,
                    maid.m_20186_(),
                    maid.m_20189_() + (stand.m_123343_() + 0.5 - maid.m_20189_()) * k, speed);
        } else {
            maid.m_21573_().m_26519_(stand.m_123341_() + 0.5, stand.m_123342_() + 0.5,
                    stand.m_123343_() + 0.5, speed);
        }
    }

    /** v1.1.0 实测一百九十二：垫台阶/桥块后登记"走上去"目标（BlockWalkOn 持续推送，
     *  踏入即停）——取代寻路版 walkToStep（跨沟/断崖寻路半路折断 = 死循环根因） */
    protected void startStepOn(EntityMaid maid, double hx, double hz, double hDist) {
        this.startStepOn(maid, hx, hz, hDist, 0);
    }

    /** v1.1.0 下方下降：yOffset = -1 时目标脚位比当前低 1 格（下行台阶） */
    protected void startStepOn(EntityMaid maid, double hx, double hz, double hDist, int yOffset) {
        int y = maid.m_20183_().m_123342_() + yOffset;
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockWalkOn.start(maid, tx + 0.5, y, tz + 0.5);
    }

    /** v1.5.113（C2）：搭方块材料耗尽播报（限频 30 秒） */
    protected void notifyNoBuildBlock(EntityMaid maid) {
        // 实测四百四十三：悬空禁搭导致的"取料失败"不是真的没材料——静默
        if (com.maidsmart.tool.MaidPlaceGuard.blocked(maid)) {
            return;
        }
        long now = maid.m_9236_().m_46467_();
        Long last = NO_BLOCK_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < 600L) {
            return;
        }
        NO_BLOCK_REPORT_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "背包里没有能搭的方块了（圆石/泥土等），够不着高处的矿……请给我一些方块～");
    }

    /** v1.5.25f：防窒息兜底（移植自保 antiSuffocate）——每 tick 检测女仆"身体中心"
     * （半身位置）所在格是否为实心满块——是则说明她卡进了方块（搭方块后位移滞后 /
     * 放偏 / 被推挤），直接 setPos 把位置强制移到该方块顶面之上。头顶检查拦不住
     * 横向卡入，这是最后一道保险：宁可瞬移半步也不让她被自己搭的方块闷住。
     */
    protected void antiSuffocate(EntityMaid maid) {        net.minecraft.world.phys.AABB box = maid.m_20191_();
        double cy = box.f_82289_ + (box.f_82292_ - box.f_82289_) * 0.5; // 身体中心高度（半身）
        BlockPos mid = new BlockPos((int) Math.floor(maid.m_20185_()),
                (int) Math.floor(cy), (int) Math.floor(maid.m_20189_()));
        BlockState st = maid.m_9236_().m_8055_(mid);
        if (st.m_60795_() || !st.m_60796_(maid.m_9236_(), mid)) {
            return; // 身体中心在空气 / 非实心满块（楼梯台阶不算卡住）
        }
        double top = mid.m_123342_() + 1.0;
        if (top > box.f_82289_ + 0.01) {
            maid.m_6034_(maid.m_20185_(), top + 0.02, maid.m_20189_());
        }
    }

    /** v1.5.24：取背包中数量最多的可搭方块（BlockItem + 非下落），用于搭高挖矿。
     *  v1.1.0 实测二百三十一：含手部栏（主/副手）——手里拿的方块也能垫（审计修复） */
    protected Item takeBuildBlock(EntityMaid maid) {
        // 实测四百四十三：悬空/坠落中禁搭方块——统一闸口
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
        net.minecraftforge.items.IItemHandler __inv = maid.getAvailableBackpackInv();
        net.minecraftforge.items.IItemHandler __hands = maid.getHandsInvWrapper();
        String __c0 = com.maidsmart.tool.MaidBuildBlockFilter.probeCounts(__inv, __hands)
                + " hands[" + com.maidsmart.tool.MaidBuildBlockFilter.probeHands(__hands) + "]";
        Item __took = com.maidsmart.tool.MaidBuildBlockFilter.takeBuildBlock(__inv, __hands, null, null,
                com.maidsmart.combat.BombPose.offhandBorrowed(maid));
        return __took;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.113：挖矿任务期间【常驻】——目标挖完不结束，下一 tick 继续扫描/
        // 迁移（与 canStart 常驻配套）；切走任务才停止（m_6732_ 退场）。
        // 旧版 targetPos 挖完即 false → 行为停止 → 女仆原地发呆不找下一个矿。
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:mine").equals(maid.getTask().getUid());
    }

    /** v1.5.189：危险方块注册名（与自保 SelfPreservationBehavior.DANGER_BLOCKS 同款） */
    protected static final String[] DANGER_BLOCK_IDS = {
            "minecraft:lava", "minecraft:fire", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:campfire"
    };

    /** v1.5.47：是否可开路废石（穿透挖掘的临时目标；v1.5.87 含徒手可挖软方块）
     *  v1.5.99c：m_135827_ 是 getNamespace（SRG）返回 "minecraft"，永远匹配不上白名单
     *  → 泥土/圆石全被当硬挡路（"拒绝挖掘被泥土包裹的矿物"根因）。getPath 的
     *  正确 SRG 名是 m_135815_（javap 字节码实证：构造器参数 1=namespace→f_135804_，
     *  m_135827_ 返回 f_135804_，m_135815_ 返回 f_135805_=path）。
     *  v1.5.101b：并入面板障碍物名单（MINE_BREAKABLES），统一走 isBreakable。 */
    protected boolean isOpenStone(ServerLevel level, BlockPos pos) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(level.m_8055_(pos).m_60734_());
        return key != null && isBreakable(key.m_135815_());
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
        state.m_204343_().forEach(t -> paths.add(t.f_203868_().m_135815_()));
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
