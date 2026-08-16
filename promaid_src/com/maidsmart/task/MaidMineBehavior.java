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

/**
 * 挖矿行为：找最近且最有价值的矿石 → 走过去 → 手持镐渐进开采（v1.5.19）。
 * 平衡设计（对齐 maid_useful_task 伐木逻辑 + 玩家挖掘公式）：
 * - 必须手持镐才能开采（无镐时只寻路不破坏）
 * - **渐进挖掘**：不是秒破——每 tick 累计破坏进度（与玩家同公式）
 * - **工具加速**：石镐/铁镐/钻石镐/效率附魔自动加快挖掘（空手极慢）
 * - **正确工具判定**（v1.5.24）：石镐挖钻石矿这类"需更高阶镐"的矿石，
 *   按玩家规则用 100 除数（慢 3.3 倍），不会"秒破钻石"
 * - **女仆稍快**（v1.5.24）：整体 ×1.2——人机智商不够，用数值补
 * - **破坏音效**：挖完用 levelEvent 2001（与玩家挖矿完全相同的方块破坏音+粒子）
 * - 只开采白名单矿石，不破坏地形
 * - 掉落物走女仆拾取模式，不凭空生成
 * - v1.5.4（借鉴 numen MiningEconomics）：目标选择 = 距离平方 + 深度惩罚
 *   (3.0/格，越深越远成本越高) - 矿石价值加成（钻石/绿宝石 > 金/铁 > 煤/石英）
 */
public class MaidMineBehavior extends Behavior<EntityMaid> {
    /** v1.5.164：诊断日志（连锁采集/自动收集是否生效的排查用） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 矿石价值（分数减项，越高越优先）；v1.5.102：价值权重/深度惩罚从配置面板读取（mine 段） */
    private static final Map<Block, Integer> ORE_VALUE = new HashMap<>();

    static {
        setValue("minecraft:diamond_ore", 500);
        setValue("minecraft:deepslate_diamond_ore", 500);
        setValue("minecraft:emerald_ore", 450);
        setValue("minecraft:deepslate_emerald_ore", 450);
        setValue("minecraft:gold_ore", 250);
        setValue("minecraft:deepslate_gold_ore", 250);
        setValue("minecraft:nether_gold_ore", 250);
        setValue("minecraft:iron_ore", 250);
        setValue("minecraft:deepslate_iron_ore", 250);
        setValue("minecraft:copper_ore", 200);
        setValue("minecraft:deepslate_copper_ore", 200);
        setValue("minecraft:redstone_ore", 200);
        setValue("minecraft:deepslate_redstone_ore", 200);
        setValue("minecraft:lapis_ore", 200);
        setValue("minecraft:deepslate_lapis_ore", 200);
        setValue("minecraft:coal_ore", 100);
        setValue("minecraft:deepslate_coal_ore", 100);
        setValue("minecraft:nether_quartz_ore", 100);
    }

    private static void setValue(String id, int value) {
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(id));
        if (block != null) {
            ORE_VALUE.put(block, value);
        }
    }

    /**
     * v1.5.89 遗留说明：旧 equipPickaxe 写错位置——getMaidInv() 是背包（36 格），
     * 不是主手；真正的主手是 getHandsInvWrapper() slot 0。换工具已统一收敛到
     * MaidToolAutoEquip（v1.5.90，按任务 UID 自动装备，攻击/弓/弩/三叉戟/挖矿通用）。
     */

    /** v1.5.88：自定义矿表是否已加载（config 在 mod 加载后期才读文件，static 块里读不到） */
    private static boolean customOresLoaded = false;

    /**
     * v1.5.88：从配置面板的"可挖掘方块表"（mine.oreValues：modid:block=价值）加载
     * 自定义矿表——适配其他 mod 的矿石。首次扫描/配置保存后调用。
     */
    public static void loadCustomOres() {
        try {
            for (String line : com.maidsmart.config.MaidSmartConfig.MINE_ORE_VALUES.get()) {
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(parts[1].trim());
                    Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(parts[0].trim()));
                    if (b != null) {
                        ORE_VALUE.put(b, v);
                    }
                } catch (Exception ignored) {
                }
            }
            customOresLoaded = true;
            ORE_CACHE.clear(); // v1.5.113：矿表变化 → 缓存失效，强制重建
        } catch (Exception ignored) {
        }
    }

    /** v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值） */
    private static void ensureCustomOres() {
        if (!customOresLoaded) {
            loadCustomOres();
        }
    }

    /** v1.5.25g：搜索半径扩大（16→24）——区块内更多矿物能感知。
     *  v1.5.47：改为以【锚点】为中心（防漂移），水平半径 16（33×33 扫描面）
     *  v1.5.87：水平半径放大到 24（49×49 扫描面）——矿物检索范围更大
     *  v1.5.88：改为读配置面板（mine.searchRadius） */
    /** v1.5.102：检索半径/锚点半径/穿透预算/垂直范围/废石保留量等已由配置面板驱动（mine 段）。
     *  剩余数值（锚点超时/重定位节流/迁移停滞/目标超时/挖掘距离/搭高节奏/清理间隔/播报限频）
     *  同样全部改从配置面板读取，常量已删。 */
    private static double reachSq() {
        double r = com.maidsmart.config.MaidSmartConfig.MINE_REACH.get();
        return r * r;
    }
    /** v1.5.47：废石白名单（丢弃判定用；注册名 path） */
    private static final java.util.Set<String> JUNK_STONES = java.util.Set.of(
            "stone", "cobblestone", "deepslate", "cobbled_deepslate", "granite", "diorite",
            "andesite", "tuff", "gravel", "dirt", "netherrack", "blackstone", "basalt",
            "soul_sand", "soul_soil", "magma_block", "calcite", "dripstone_block", "sandstone",
            "red_sandstone");
    /**
     * v1.5.47：可开路方块白名单（穿透挖掘只挖这些，不碰箱子/机器等）。
     * v1.5.87 细分：石头类（需镐，耗耐久开路）+ 徒手可挖软方块（草/土/沙，开路不耗镐子耐久）。
     * 名单之外的实心挡路（箱子/机器/基岩等）→ 报点 + 弃置该矿，绝不隔墙挖矿。
     */
    private static final java.util.Set<String> OPEN_BREAKABLE = new java.util.HashSet<>(JUNK_STONES);

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
    private static final java.util.Map<String, String> BLOCK_CN = new java.util.HashMap<>();

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

    /** v1.5.102c：方块注册名 path → 中文名。v1.5.140：未知方块回退改用 BlueprintLib
     *  cnName（CN_NAMES 精确表 + 颜色/材质规则兜底，mod 方块也尽量出中文——
     *  旧版直接回退英文 path，用户反馈"阻挡汇报用英文字符"） */
    private static String blockCnName(net.minecraft.world.level.block.Block block) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return "方块";
        }
        String cn = com.maidsmart.build.BlueprintLib.cnName(key.toString());
        return cn.isEmpty() ? key.m_135815_() : cn;
    }

    /**
     * v1.5.87：软方块判定（徒手可挖：硬度 0~1，如草/土/沙）——
     * 开路不耗镐子耐久、穿透预算不计阻挡；硬方块（矿/石头/箱子/基岩）反之。
     */
    /**
     * v1.5.139：软方块判定 = "镐不是正确工具"的方块（草/土/沙/粘土——铲或徒手挖）。
     * 旧判定 hardness ≤ 1 会把低硬度的 mod 矿物（如匠魂/魔法 mod 的 1 硬度矿石）
     * 误判为"软方块"→ softNoDurability=true 时挖矿物也不扣耐久（用户反馈
     * "就算挖的是矿物也不会掉耐久"的根因）。改为按"镐是否为正确工具"判定：
     * 镐是正确工具的方块（原版+mod 全部矿物/石头/深板岩，无论硬度）一律算硬，必扣。
     */
    private static boolean isSoft(ItemStack pick, BlockState st) {
        if (pick.m_41619_() || !(pick.m_41720_() instanceof net.minecraft.world.item.DiggerItem d)) {
            return true; // 徒手/非镐 → 视为软（软方块开路路径，不扣镐耐久）
        }
        return !d.m_8096_(st); // 镐非正确工具 = 徒手可挖的软方块
    }

    /**
     * v1.5.47：锚点 = 锁定的搜索中心（借鉴 maidmining 的锚定挖矿）。
     * 旧版 findOre 以女仆当前位置为中心——挖一块后中心跟着动，越挖越远（漂移），
     * 锚点锁死后框内由近到远挖光，框空才迁移（v1.5.113：锚点朝主人方向滑动一个
     * 环宽，不走路、不死循环）。
     * 静态 per-maid 表（key = 实体 ID；魂符收放 ID 变化自动失效）。
     */
    private static final Map<Integer, BlockPos> ANCHORS = new HashMap<>();
    private static final Map<Integer, Long> OUT_SINCE = new HashMap<>();
    private static final Map<Integer, Long> LAST_RELOCATE = new HashMap<>();
    /** v1.5.113：锚点滑动迁移节流（空框时每 5 秒滑一次，防高速漂移） */
    private static final Map<Integer, Long> SLIDE_SINCE = new HashMap<>();
    /** v1.5.47：目标设定时刻（5 秒未挖动 → 弃置） */
    private static final Map<Integer, Long> TARGET_SINCE = new HashMap<>();
    /** v1.5.102：播报限频间隔（tick）从配置面板读取（mine.skipReportInterval） */
    private static final Map<Integer, Long> SKIP_REPORT_SINCE = new HashMap<>();
    /** v1.5.87：硬挡路（箱子/机器/基岩）报点限频（实体 ID → 上次播报 tick） */
    private static final Map<Integer, Long> BLOCKED_REPORT_SINCE = new HashMap<>();
    /** v1.5.113：够不着的矿短时排除（弃置后 30 秒内不再反复选中；挖掉任一矿即清） */
    private static final Map<Integer, java.util.Map<BlockPos, Long>> RECENT_DISCARD = new HashMap<>();
    /** v1.5.113：搭方块材料耗尽播报限频（实体 ID → 上次播报 tick） */
    private static final Map<Integer, Long> NO_BLOCK_REPORT_SINCE = new HashMap<>();
    /** v1.5.113：找矿结果缓存——全量扫描每 5 秒一次，期间只做廉价校验（A1 性能优化） */
    private static final Map<Integer, OreCache> ORE_CACHE = new HashMap<>();
    /** 缓存 TTL（tick，5 秒）——矿石静态不变，5 秒内只校验存在性即可 */
    private static final long ORE_CACHE_TTL = 100L;

    /** 审计M4修复（v1.5.383）：女仆实体卸载时清理其全部行为状态表（防长会话泄漏） */
    public static void forget(int maidEntityId) {
        ANCHORS.remove(maidEntityId);
        OUT_SINCE.remove(maidEntityId);
        LAST_RELOCATE.remove(maidEntityId);
        SLIDE_SINCE.remove(maidEntityId);
        TARGET_SINCE.remove(maidEntityId);
        SKIP_REPORT_SINCE.remove(maidEntityId);
        BLOCKED_REPORT_SINCE.remove(maidEntityId);
        RECENT_DISCARD.remove(maidEntityId);
        NO_BLOCK_REPORT_SINCE.remove(maidEntityId);
        ORE_CACHE.remove(maidEntityId);
    }

    /** v1.5.113：找矿缓存条目——全量扫描得到的矿位置 + 各自挡路预算（供缓存轮复用） */
    private record OreCache(long builtAt, java.util.List<BlockPos> ores,
                            java.util.Map<BlockPos, Integer> blocking) {
    }

    /** v1.5.87：正在挖矿的女仆（拾取任务据此让位——捡掉落物最低优先级） */
    private static final java.util.Set<java.util.UUID> MINING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** v1.5.87：女仆是否正在挖矿（MaidPickupPriorityMixin 检查用） */
    public static boolean isMining(EntityMaid maid) {
        return MINING.contains(maid.m_20148_());
    }

    /** v1.5.87：该位置是否是挖矿搭的方块（GLOBAL_PLACED 追踪；搭方块防掉落用） */
    public static boolean isMiningPlaced(ServerLevel level, BlockPos pos) {
        java.util.Map<BlockPos, PlacedMark> marks = GLOBAL_PLACED.get(level.m_46472_());
        return marks != null && marks.containsKey(pos.m_7949_());
    }

    /** v1.5.87：搭方块防掉落窗口（搭块后 12 tick 内钳制，防刚搭完滑落） */
    private int pillarGuardTicks = 0;
    /** v1.5.47：刚弃置的目标（findOre 排除一次；挖掉任一矿后清除） */
    private BlockPos abandonedPos = null;
    /** v1.5.87：被硬挡路（箱子/机器等）弃置的矿——持续排除，不再反复选中报点 */
    private final java.util.Set<BlockPos> blockedOres = new java.util.HashSet<>();
    /** v1.5.85：本次扫描中镐子挖不动的矿（记录价值最高的一个，用于播报） */
    private BlockPos skippedOrePos = null;
    private String skippedOreName = null;
    private String skippedOreTool = null;
    private int skippedOreValue = -1;

    private BlockPos targetPos = null;
    /** 当前目标累计破坏进度（0~1，对齐 maid_useful_task 渐进挖掘） */
    private float destroyProgress = 0.0f;
    /**
     * v1.5.161：连锁采集队列（借鉴 FTB Ultimine 连锁破坏思路）——挖完一块矿后，
     * 从刚挖的位置 BFS 找相连的同族矿石排队，挖完一块自动接下一块，直到队列空。
     * 默认关闭（配置 mine.chainMining）；队列只装"矿"（ORE_VALUE 表内），不连锁石头。
     */
    private final java.util.ArrayDeque<BlockPos> chainQueue = new java.util.ArrayDeque<>();
    /** v1.5.161：当前连锁的矿方块类型（挖掉一块后记录，BFS 按同 Block 匹配） */
    private net.minecraft.world.level.block.Block chainBlock = null;
    /** v1.5.22：进度持久化 key（行为被抢占重启时不丢进度） */
    private static final String PROGRESS_TAG = "maid_smart_mine_progress";
    /** 目标扫描节流：找不到矿时每 20 tick 才扫一次，避免每 tick 全量查询 */
    private int scanCooldown = 0;
    /** v1.5.113：上一次 findOre 是否做了全量重建（缓存轮=false）——只有全量重建
     *  确认框空才滑动锚点迁移（缓存轮空不迁移，等 5 秒重建再判） */
    private boolean lastScanWasFull = true;
    /** v1.5.24：搭高节奏计数 */
    private int pillarCooldown = 0;
    /** v1.5.47：废石检查节流 */
    private int junkCooldown = 0;
    /** v1.5.105：走过去重设 WalkTarget 节流——每 tick 重设会让 TLM 每 tick 重寻路 → 移动顿挫 */
    private int walkRetargetCooldown = 0;
    /** v1.5.116：上次设置的移动目标站立点——目标没变且导航行进中不重设
     *  （旧版每 8 tick 无条件重设 WalkTarget → 导航每次重新寻路 → "一走一停"鬼畜） */
    private BlockPos lastWalkTarget = null;
    /** v1.5.25：本次自己搭过的位置（搭高/斜坡/搭桥）——10 秒后自动销毁，绝不挖自然地形。
     *  v1.5.28：改为【全局静态追踪器】——旧版挂在行为实例上，行为停止（挖完矿
     *  canContinue=false）后 expirePlacedBlocks 不再运行 → 搭的方块永久残留。
     *  现在放置即登记到全局表，由 ServerTickEvent 每 tick 统一清理，与行为生命周期无关。
     *  v1.5.102：清理时限从配置面板读取（mine.placedLifetime，秒→tick） */

    /** 全局追踪条目：放置 tick + 方块 id（清理时校验，玩家换掉的方块不误破坏） */
    private record PlacedMark(long tick, String blockId) {
    }

    /** 维度 → 位置 → 放置标记（v1.5.28） */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            java.util.Map<BlockPos, PlacedMark>> GLOBAL_PLACED = new java.util.HashMap<>();

    /** v1.5.28：登记一个挖矿搭的方块（每块从自己放置时刻起单独计时，满 10 秒各自销毁） */
    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        GLOBAL_PLACED.computeIfAbsent(level.m_46472_(), k -> new java.util.HashMap<>())
                .put(pos.m_7949_(), new PlacedMark(level.m_46467_(), key != null ? key.toString() : ""));
    }

    /** v1.5.28：销毁所有放置超过 10 秒的挖矿搭方块（玩家同款破坏音效+粒子+掉落物）。
     *  每 tick 调用（ServerTickEvent 兜底 + 行为运行中 m_6725_ 双保险）。
     *  v1.5.88：清理时间从配置面板读取（mine.placedLifetime 秒）。
     *  v1.5.113（B3）：【女仆正站在上面的搭方块延后清理】——搭高挖垂直矿脉时
     *  底部柱子满 10 秒自动消失会把女仆摔下去；只对"即将销毁的旧块"检查附近
     *  是否有挖矿女仆站着（脚下/所在格），有则下轮再清（走开即清，不残留）。 */
    public static void expirePlaced(ServerLevel level, long gameTime) {
        java.util.Map<BlockPos, PlacedMark> marks = GLOBAL_PLACED.get(level.m_46472_());
        if (marks == null || marks.isEmpty()) {
            return;
        }
        long lifetime = com.maidsmart.config.MaidSmartConfig.MINE_PLACED_LIFETIME.get() * 20L;
        java.util.Iterator<java.util.Map.Entry<BlockPos, PlacedMark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<BlockPos, PlacedMark> e = it.next();
            if (gameTime - e.getValue().tick < lifetime) {
                continue;
            }
            BlockPos pos = e.getKey();
            // v1.5.113：只对"到期的旧块"检查是否正被女仆踩着（最多 1-2 块/tick，开销可忽略）
            if (supportsAnyMiner(level, pos)) {
                continue; // 有人站在上面 → 延后到下一轮
            }
            it.remove();
            destroyMarked(level, pos, e.getValue());
        }
    }

    /** v1.5.113：该搭方块是否正被某只挖矿女仆站在上面（脚下格或所在格） */
    private static boolean supportsAnyMiner(ServerLevel level, BlockPos pos) {
        for (EntityMaid m : level.m_45976_(EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).m_82400_(2.0))) {
            if (!m.m_6084_() || !MINING.contains(m.m_20148_())) {
                continue;
            }
            BlockPos feet = m.m_20183_();
            if (feet.m_7949_().equals(pos)
                    || feet.m_7918_(0, -1, 0).m_7949_().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** v1.5.28：服务器停止时清场——内存追踪器会随进程消失，残留方块立即销毁回收
     *  （不等待 10 秒；重进存档不会看到"永不消失"的搭方块） */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.m_129785_()) {
            java.util.Map<BlockPos, PlacedMark> marks = GLOBAL_PLACED.remove(level.m_46472_());
            if (marks == null) {
                continue;
            }
            for (java.util.Map.Entry<BlockPos, PlacedMark> e : marks.entrySet()) {
                destroyMarked(level, e.getKey(), e.getValue());
            }
        }
        GLOBAL_PLACED.clear();
    }

    /** v1.5.103：清理已不在任何维度中的女仆的静态 per-maid 数据（防长时运行内存泄漏）。
     *  以 maid.m_19879_()（Entity.getId，单调递增永不回收）为 key 的各 Map 只增不清，
     *  女仆消失（魂符收放/卸载/死亡）后条目永久残留。用 ServerLevel.m_6815_(id)（getEntity）
     *  O(1) 查存活，定期由 ProMaidExtension 调用（每 30 秒）。 */
    public static void purgeStaleMaids(net.minecraft.server.MinecraftServer server) {
        try {
            // 全部 per-maid 表的 key 并集（不能只查 ANCHORS——其他表可能有 ANCHORS 没有的 id）
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            ids.addAll(ANCHORS.keySet());
            ids.addAll(OUT_SINCE.keySet());
            ids.addAll(LAST_RELOCATE.keySet());
            ids.addAll(SLIDE_SINCE.keySet());
            ids.addAll(TARGET_SINCE.keySet());
            ids.addAll(SKIP_REPORT_SINCE.keySet());
            ids.addAll(BLOCKED_REPORT_SINCE.keySet());
            ids.addAll(RECENT_DISCARD.keySet());
            ids.addAll(NO_BLOCK_REPORT_SINCE.keySet());
            ids.addAll(ORE_CACHE.keySet());
            java.util.Set<Integer> alive = new java.util.HashSet<>();
            for (int id : ids) {
                for (ServerLevel lvl : server.m_129785_()) {
                    net.minecraft.world.entity.Entity e = lvl.m_6815_(id);
                    if (e != null && e.m_6084_()) {
                        alive.add(id);
                        break;
                    }
                }
            }
            ANCHORS.keySet().removeIf(id -> !alive.contains(id));
            OUT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            LAST_RELOCATE.keySet().removeIf(id -> !alive.contains(id));
            SLIDE_SINCE.keySet().removeIf(id -> !alive.contains(id));
            TARGET_SINCE.keySet().removeIf(id -> !alive.contains(id));
            SKIP_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            BLOCKED_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            RECENT_DISCARD.keySet().removeIf(id -> !alive.contains(id));
            NO_BLOCK_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
            ORE_CACHE.keySet().removeIf(id -> !alive.contains(id));
        } catch (Exception ignored) {
        }
    }

    /** 破坏一个追踪方块：玩家同款破坏音效+粒子+掉落物（女仆拾取模式自动回收）。
     *  当前位置已是空气 → 跳过；被玩家换成别的方块 → 不破坏（尊重玩家改动）。
     *  v1.5.161：自动收集开启且附近有挖矿女仆时，掉落物直接进她背包（不进世界）。 */
    private static void destroyMarked(ServerLevel level, BlockPos pos, PlacedMark mark) {
        BlockState state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        net.minecraft.resources.ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        String curId = cur != null ? cur.toString() : "";
        if (!mark.blockId.isEmpty() && !mark.blockId.equals(curId)) {
            return; // 方块已被替换，不误破坏
        }
        level.m_46796_(2001, pos, Block.m_49956_(state));
        BlockEntity be = level.m_7702_(pos);
        if (com.maidsmart.config.MaidSmartConfig.MINE_AUTO_COLLECT.get()) {
            EntityMaid miner = nearbyMiner(level, pos);
            if (miner != null) {
                insertIntoMaidInventory(miner, level,
                        Block.m_49874_(state, level, pos, be, miner, miner.m_21205_()), pos);
                level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
                return;
            }
        }
        Block.m_49892_(state, level, pos, be);
        level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
    }

    /**
     * v1.5.161：自动收集——找离该位置最近的挖矿女仆（搭方块销毁时把掉落物给她，
     * 而不是掉在地上等拾取任务来捡）。3 格内没有就不收集（保留原落地逻辑）。
     */
    private static EntityMaid nearbyMiner(ServerLevel level, BlockPos pos) {
        EntityMaid best = null;
        double bestD = Double.MAX_VALUE;
        for (EntityMaid m : level.m_45976_(EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).m_82400_(3.0))) {
            if (!m.m_6084_() || !MINING.contains(m.m_20148_())) {
                continue;
            }
            double d = m.m_20275_(pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /**
     * v1.5.161：自动收集——掉落物塞进女仆背包（优先堆叠已有同物品，再放空槽；
     * 背包放不下才落地为掉落物，不吞物品）。与 dropResources 同款精准/时运判定
     * （调用方用 getDrops 六参版 m_49874_ 计算）。
     */
    private static void insertIntoMaidInventory(EntityMaid maid, ServerLevel level,
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
                    Block.m_49840_(level, pos, remain); // 背包满：落地（原版 popResource）
                }
            }
        } catch (Exception ignored) {
        }
    }

    public MaidMineBehavior() {
        // v1.5.124：无限运行时长——旧版 super(emptyMap) 默认 60 tick 上限，
        // 行为每 3 秒自动停止再启动（m_6735_ 反复执行、实例状态反复重置，
        // 日志刷屏）；挖矿是常驻行为（任务期间持续运行），不应自动重启
        super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** v1.5.22：进度写入持久数据（跨行为重启保留）。
     *  v1.5.113（A2）：节流——每 tick 改持久化 NBT 会让实体常驻 dirty（存档压力）；
     *  每 20 tick 写一次，进度只需行为重启时恢复，1 秒粒度足够。 */
    private int progressSaveCooldown = 0;

    private void saveProgress(EntityMaid maid) {
        if (this.progressSaveCooldown-- > 0) {
            return;
        }
        this.progressSaveCooldown = 20;
        maid.getPersistentData().m_128359_(PROGRESS_TAG, String.valueOf(this.destroyProgress));
    }

    /** v1.5.113：立即写进度（挖完/弃置/失效时——这些点必须马上持久化，防重启残留旧进度） */
    private void saveProgressNow(EntityMaid maid) {
        this.progressSaveCooldown = 0;
        this.saveProgress(maid);
    }

    /** v1.5.22：读取持久化进度 */
    private float loadProgress(EntityMaid maid) {
        try {
            return Float.parseFloat(maid.getPersistentData().m_128461_(PROGRESS_TAG));
        } catch (Exception e) {
            return 0.0f;
        }
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        // v1.5.113：挖矿行为【常驻】——只要任务还是挖矿就持续运行：扫描找矿、
        // 换镐、滑动迁移全部收敛到 doTick 统一处理。旧版 canStart 无矿即返回
        // false → 行为停止 → doTick 不再运行 → 空框时女仆原地发呆、永不迁移
        // （"矿挖完就站住不动"根因）。
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:mine").equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.164：诊断日志（确认女仆跑的是 maid_smart:mine 任务）
        LOGGER.info("mine behavior start: maid={}", maid.m_20148_());
        // v1.5.107：不再无条件装备最高级镐——目标矿已由 doTick 按需换镐；
        // 这里仅兜底"start 时 targetPos 为空重新找矿"的按需换镐
        if (this.targetPos == null) {
            BlockPos anchor = this.resolveAnchor(level, maid);
            if (anchor != null) {
                this.targetPos = this.findOre(level, maid, anchor);
                if (this.targetPos != null) {
                    MaidToolAutoEquip.ensureForTarget(maid, level.m_8055_(this.targetPos));
                    TARGET_SINCE.put(maid.m_19879_(), gameTime);
                }
            }
        }
        // v1.5.24：重置搭高节奏（行为重启不残留）
        this.pillarCooldown = 0;
        // v1.5.28：不再清 placedBlocks——追踪已移入全局表（GLOBAL_PLACED），
        // 行为重启不清除，10 秒清理由 ServerTickEvent 统一执行（行为停转也不残留）
        // v1.5.22：恢复持久化进度（行为重启不从头挖）
        this.destroyProgress = this.loadProgress(maid);
        if (this.destroyProgress >= 1.0f) {
            this.destroyProgress = 0.0f; // 异常残留：重置
        }
        // v1.5.87：登记"正在挖矿"——拾取任务据此让位（捡掉落物 = 最低优先级）
        MINING.add(maid.m_20148_());
    }

    /** v1.5.87：行为停止 → 退出"正在挖矿"（拾取任务恢复） */
    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        MINING.remove(maid.m_20148_());
        // v1.5.161：停止时清连锁队列（任务切换/被抢占后不残留过期矿位）
        this.chainQueue.clear();
        this.chainBlock = null;
        super.m_6732_(level, maid, gameTime);
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.25f：每 tick 防窒息兜底（移植自保 antiSuffocate）——半身卡进方块
        // 立即强制上移到方块顶面之上。搭高挖矿时实体位移滞后/放偏，头顶检查
        // 拦不住横向卡入，这是最后一道保险：宁可瞬移半步也不被自己搭的方块闷住
        this.antiSuffocate(maid);
        // v1.5.109：移除 pullTowardTarget（setDeltaMovement 直接注入速度）——
        // 它与导航互相覆盖、搭高时把女仆从柱子上推走（"移速过快疯狂漂移"根因）。
        // 移动完全交给导航：目标够得着→挖；够不着→搭方块/走过去（approachOre）。
        // v1.5.111：珍稀矿物掉落物回收子系统整体移除（用户反馈：女仆挖矿途中被
        // 掉落物吸引、满场飞奔去捡，支柱永远建不起来）——挖出的掉落物就地由
        // pickupOreDrops 收进背包；捡不到的留给拾取任务处理，不再打断挖矿流程。
        // v1.5.87：搭方块防掉落窗口——刚搭完方块钳制在格子中心（潜行效果，速度不变），
        // 防止重心滑出方块边缘从柱子上掉下去；v1.5.88 可配置（mine.pillarGuard）
        if (com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_GUARD.get() && this.pillarGuardTicks > 0) {
            this.pillarGuardTicks--;
            this.pillarGuard(level, maid);
        }
        // v1.5.28：搭方块 10 秒后统一销毁（全局表——行为停止后由 ServerTickEvent 兜底，
        // 此处保留双保险；不做任何即时破坏）
        expirePlaced(level, gameTime);
        // v1.5.47：废石丢弃（每 100 tick 一次；保留 JUNK_KEEP 份，超出销毁）
        if (--this.junkCooldown <= 0) {
            this.junkCooldown = com.maidsmart.config.MaidSmartConfig.MINE_JUNK_CHECK_INTERVAL.get();
            this.maybeDropJunk(maid);
        }
        // v1.5.47：锚点解析——迁移推进 / 出框超时重埋（防漂移的边界兜底）
        BlockPos anchor = this.resolveAnchor(level, maid);
        if (anchor == null) {
            this.targetPos = null;
            return;
        }
        if (this.targetPos == null) {
            // v1.5.161：连锁采集——队列里还有相连的同族矿就先挖队列（不重新扫描；
            // 队列矿若已被挖掉/失效，走下面的目标失效检查自然跳过，再取下一块）
            if (!this.chainQueue.isEmpty()) {
                BlockPos chained = this.chainQueue.poll();
                if (chained != null && this.isOre(level, chained)) {
                    this.targetPos = chained;
                    TARGET_SINCE.put(maid.m_19879_(), gameTime);
                    MINING.add(maid.m_20148_());
                }
            }
            if (this.targetPos == null) {
                // v1.5.113（A1）：扫描节流——每 20 tick 一次廉价校验（全量重建由
                // findOre 内部按 5 秒 TTL 控制，旧版每 3 tick 全量扫 8.9 万格）
                // v1.5.124：20 → 10 tick——挖完一块后找下一块矿更快（缓存轮只校验
                // 已记录矿的位置，廉价），"原地愣几秒"减少一半
                // v1.5.127：10 → 5 tick——缓存轮空时 findOre 已改为立即全量重建，
                // 此节流只控制"挖完后重新评估"的频率，再减半让块间衔接更紧凑
                if (this.scanCooldown-- > 0) {
                    return;
                }
                this.scanCooldown = 5;
                this.targetPos = this.findOre(level, maid, anchor);
                if (this.targetPos == null) {
                // v1.5.140：挖矿空闲（附近无矿）→ 退出"挖矿中"标记，拾取任务恢复正常
                //（用户反馈：空闲时捡东西积极性太低；空闲 = 与其他工作任务的空闲一致）
                MINING.remove(maid.m_20148_());
                // v1.5.85：框内有矿但镐子挖不动 → 不迁移（换镐还是挖不动）：
                // 气泡+主人聊天栏播报一次（限频）等玩家换镐
                if (this.skippedOrePos != null) {
                    this.reportSkippedOre(maid, gameTime);
                    return;
                }
                // v1.5.113：只有【全量重建】确认本框没矿才迁移——缓存轮空不迁移
                // （等 5 秒重建再判，防锚点高速滑动）
                if (this.lastScanWasFull) {
                    // v1.5.102d：此前有被硬物挡住的矿 → 上报一次挡路原因
                    if (!this.blockedOres.isEmpty()) {
                        this.reportBlockedArea(maid, level);
                    }
                    // v1.5.113（B1/B2）：框内挖空 → 锚点朝主人方向滑动一个环宽
                    // （不走路、不原地重埋——旧版"朝主人走走出旧框"被墙堵住时
                    // 原地重埋与旧框重合 → 死循环；纯走路又零产出）
                    this.slideAnchor(level, maid, anchor);
                }
                return;
            }
            // v1.5.107：找到矿 → 按需换镐（手中够用不换；不够换背包能挖的；换不到播报）
            MaidToolAutoEquip.ensureForTarget(maid, level.m_8055_(this.targetPos));
            TARGET_SINCE.put(maid.m_19879_(), gameTime);
            // v1.5.140：有目标 = 挖矿进行中 → 登记标记（拾取任务让位，与 doStart 一致）
            MINING.add(maid.m_20148_());
            }
        }
        // v1.5.47：弃置检查——目标 5 秒够不着且无任何破坏进度 → 弃置重选（防原地磨蹭）
        // v1.5.113（B4）：弃置矿进入 30 秒短时排除（RECENT_DISCARD），不再反复选中；
        // 框内只剩这块够不着的矿时也不会来回折腾
        Long since = TARGET_SINCE.get(maid.m_19879_());
        if (since != null && gameTime - since >= com.maidsmart.config.MaidSmartConfig.MINE_TARGET_TIMEOUT.get()
                && this.destroyProgress <= 0.0f
                && maid.m_20275_(this.targetPos.m_123341_() + 0.5,
                this.targetPos.m_123342_() + 0.5, this.targetPos.m_123343_() + 0.5) > reachSq()) {
            this.abandonedPos = this.targetPos;
            RECENT_DISCARD.computeIfAbsent(maid.m_19879_(), k -> new java.util.HashMap<>())
                    .put(this.targetPos.m_7949_(), gameTime);
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            this.saveProgressNow(maid);
            return;
        }
        // v1.5.47：目标失效检查——矿 或 开路废石（穿透挖掘的临时目标）
        if (!isOre(level, this.targetPos) && !this.isOpenStone(level, this.targetPos)) {
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            return;
        }
        // v1.5.85：目标矿镐子挖不动（中途换镐/低等级镐/镐断）→ 先试着从背包装备
        // 能挖的镐（v1.5.107 按需换镐），换不到才弃目标重选
        // v1.5.113（C3）：换不到镐 → 立即记录并播报"需要更高镐"（不用等 15 秒目标超时）
        if (isOre(level, this.targetPos) && !canHarvest(maid, level.m_8055_(this.targetPos))) {
            boolean swapped = MaidToolAutoEquip.ensureForTarget(maid, level.m_8055_(this.targetPos));
            if (!swapped || !canHarvest(maid, level.m_8055_(this.targetPos))) {
                this.recordSkippedOre(maid, this.targetPos, level.m_8055_(this.targetPos));
                this.reportSkippedOre(maid, gameTime);
                // v1.5.161：队列里的矿是同类型——挖不动就全清，别反复试（等换到
                // 更高阶镐后正常找矿流程自然会重新选中）
                this.chainQueue.clear();
                this.chainBlock = null;
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.saveProgressNow(maid);
                return;
            }
        }
        // v1.5.90：挡路块优先判定——不必等够到矿本身（"非矿物挡路只报不挖"根因）。
        // 旧版只有"矿在伸手范围内"才检查挡路：矿被厚土墙/岩壁隔着时女仆永远够不着
        // 矿 → 走到墙边站着不动（或 15 秒超时弃置），绝不挖开挡路方块。
        // 现在只要"挡路块"在伸手范围内就开挖，挖穿一层再评估下一层，逐层推进到矿。
        Blocker blocker = null;
        if (isOre(level, this.targetPos)) {
            blocker = this.findBlockingBlock(level, maid, this.targetPos);
        }
        if (blocker != null && blocker.openable()) {
            double bDistSq = maid.m_20275_(blocker.pos().m_123341_() + 0.5,
                    blocker.pos().m_123342_() + 0.5, blocker.pos().m_123343_() + 0.5);
            if (bDistSq <= reachSq()) {
                // 挡路块够得着 → 切目标为挡路块，走下面正常挖掘流程
                this.targetPos = blocker.pos();
                TARGET_SINCE.put(maid.m_19879_(), gameTime);
            } else {
                // 挡路块还够不着 → 朝挡路块推进（目标 = 挡路块脚下，不朝矿——
                // 矿在岩壁深处，以矿为终点永远走不到）
                this.walkToBlockFace(level, maid, blocker.pos());
                return;
            }
        } else if (blocker != null
                && com.maidsmart.config.MaidSmartConfig.MINE_HARD_BLOCK_REPORT.get()) {
            // v1.5.87：硬挡路——报点（气泡+主人聊天栏）+ 弃置该矿（持续排除，不再反复选中）
            this.blockedOres.add(this.targetPos);
            this.reportBlockedOre(maid, level, this.targetPos, blocker.pos());
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            this.saveProgress(maid);
            return;
        }
        double distSq = maid.m_20275_(this.targetPos.m_123341_() + 0.5, this.targetPos.m_123342_() + 0.5, this.targetPos.m_123343_() + 0.5);
        if (distSq > reachSq()) {
            // v1.5.25：够不着（超过玩家手长 4.5 格）→ 三选一搭路决策
            //（向上搭高 / 向前搭斜坡 / 搭桥+走过去），每 tick 重算直到够得着
            this.approachOre(level, maid);
            return;
        }
        ItemStack mainHand = maid.m_21205_();
        if (mainHand.m_41619_() || !(mainHand.m_41720_() instanceof PickaxeItem)) {
            // v1.5.113（C3）：主手镐没了（碎裂/被换走）且背包也没有能挖的镐 →
            // 立即播报并弃目标（不用等 15 秒超时；有备用镐时 ensureForTarget 已换好）
            if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, level.m_8055_(this.targetPos))) {
                this.recordSkippedOre(maid, this.targetPos, level.m_8055_(this.targetPos));
                this.reportSkippedOre(maid, gameTime);
                // v1.5.161：主手镐没了且背包装备不上 → 连锁队列一并清空
                this.chainQueue.clear();
                this.chainBlock = null;
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.saveProgressNow(maid);
            }
            return; // 镐被换走/碎裂，暂停挖掘
        }
        // v1.5.19 渐进挖掘；v1.5.22 重写——直接公式，不依赖 FakePlayer
        // （旧版依赖 FakePlayer.getDestroyProgress，假玩家状态异常时 delta=0 → 永远挖不完）
        BlockState state = level.m_8055_(this.targetPos);
        float hardness = state.m_60734_().m_7749_(state, level, this.targetPos); // Block.getDestroySpeed
        if (hardness <= 0.0f || hardness == Float.MAX_VALUE) {
            this.targetPos = null; // 不可破坏，换目标
            this.destroyProgress = 0.0f;
            this.saveProgress(maid);
            return;
        }
        // v1.5.24：完全对齐玩家公式（Player.getDestroyProgress）——
        // 1. 正确工具判定：石镐挖钻石矿（需铁镐）→ 除数 100 慢 3.3 倍，与玩家一致
        // 2. 效率附魔加成：效率 I = +2、效率 V = +26，与玩家 getDigSpeed 相同
        // 3. 女仆整体 ×1.2（人机智商不够，用数值补：比玩家稍快）
        boolean correct = mainHand.m_41720_() instanceof net.minecraft.world.item.DiggerItem
                && ((net.minecraft.world.item.DiggerItem) mainHand.m_41720_()).m_8096_(state);
        float digSpeed = Math.max(1.0f, mainHand.m_41691_(state));
        net.minecraft.world.item.enchantment.Enchantment eff =
                net.minecraftforge.registries.ForgeRegistries.ENCHANTMENTS
                        .getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:efficiency"));
        if (eff != null && digSpeed > 1.0f) {
            int effLevel = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44843_(eff, mainHand);
            if (effLevel > 0) {
                digSpeed += effLevel * effLevel + 1;
            }
        }
        // 玩家同款公式：每 tick 进度 = digSpeed / hardness / 除数（正确工具 30，不合适 100）
                    // v1.5.88：挖矿速度系数从配置面板读取（mine.speedFactor，默认 1.2）
                    float delta = digSpeed / hardness / (correct ? 30.0f : 100.0f)
                            * (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_SPEED_FACTOR.get();
                    // v1.5.102e：单 tick 进度封顶 0.25——高等级镐+效率附魔会让石头/泥土
                    // 1~2 tick"秒破"（Efficiency V 下 delta≈0.93）。封顶后任何方块至少
                    // 4 tick（0.2 秒）挖完，配合裂纹动画保留渐进破坏观感
                    delta = Math.min(delta, 0.25f);
        maid.m_21011_(InteractionHand.MAIN_HAND, true); // 每 tick 挥镐（挖掘动作）
        this.destroyProgress += delta;
        this.saveProgress(maid); // v1.5.22：进度持久化，行为重启不丢
        // v1.5.102e：客户端显示挖掘裂纹（0~9 阶段）——女仆不是玩家，原版不会自动发
        // 破坏进度包，方块一直无裂纹到瞬间消失 = "秒破"观感。每 tick 广播裂纹阶段
        this.broadcastCrack(level, maid, this.targetPos,
                Math.max(0, Math.min(9, (int) (this.destroyProgress * 10.0f))));
        if (this.destroyProgress < 1.0f) {
            return; // 还在挖掘中，下一 tick 继续
        }
        // 挖完：清除裂纹（stage 10 = 移除）再破坏方块
        this.broadcastCrack(level, maid, this.targetPos, 10);
        // 挖完：玩家同款破坏音效+粒子（levelEvent 2001），再掉落实体方块
        level.m_46796_(2001, this.targetPos, Block.m_49956_(state));
        BlockEntity be = level.m_7702_(this.targetPos);
        // v1.5.161：自动收集（mine.autoCollect，默认关闭）——掉落物不进世界，
        // 直接塞进女仆背包（放不下才落地）；getDrops 六参版（m_49874_）与
        // dropResources 同款精准/时运判定。关闭时保持原逻辑（落地 + 即时回收）
        if (com.maidsmart.config.MaidSmartConfig.MINE_AUTO_COLLECT.get()) {
            java.util.List<ItemStack> drops = Block.m_49874_(state, level, this.targetPos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, this.targetPos);
            // v1.5.164：诊断日志（排查"自动收集没生效"）
            LOGGER.info("mine autoCollect: maid={} pos={} drops={}",
                    maid.m_20148_(), this.targetPos, drops == null ? 0 : drops.size());
        } else {
            // v1.5.85：掉落物+经验球走完整 dropResources（六参版）——内部做精准采集检查，
            // 精准采集镐挖矿不掉经验球，与原版玩家完全一致（旧 4 参版只掉物品不掉经验）
            Block.m_49881_(state, level, this.targetPos, be, maid, mainHand);
            // v1.5.87：挖出的矿石掉落物即时回收（原地拾取，不走"走过去捡"）——
            // 拾取任务在挖矿中已让位，这里直接把刚挖出的掉落物收进背包，不掉地上
            this.pickupOreDrops(level, maid);
        }
        level.m_7731_(this.targetPos, Blocks.f_50016_.m_49966_(), 3);
        // v1.5.85：对齐原版——镐子扣耐久（hurtAndBreak；归零碎裂：广播破坏事件
        // 客户端自动播碎裂音效 + 移除碎裂的镐）
        // v1.5.87：只扣"需要工具"的方块（矿/石头）——徒手可挖软方块开路不磨损镐子
        // v1.5.88：软方块不耗耐久可配置（mine.softNoDurability）
        // v1.5.102e：修复耐久扣错对象——旧版在背包里找"同类型的镐"扣耐久/清除，
        // 但主手是独立手部栏（getHandsInvWrapper），背包刚好有同款镐时会扣背包那把、
        // 主手那把永远不磨损（v1.5.90 改手部栏后残留的旧逻辑）。现在直接扣主手那把，
        // 碎裂清主手槽（下一 tick 自动换装备用镐）。
        boolean softNoDur = com.maidsmart.config.MaidSmartConfig.MINE_SOFT_NO_DURABILITY.get();
        if (!mainHand.m_41619_() && (!softNoDur || !isSoft(mainHand, state))) {
            final ItemStack damaged = mainHand;
            damaged.m_41622_(1, maid, broken -> {
                maid.m_6674_(InteractionHand.MAIN_HAND);
                try {
                    ((net.minecraftforge.items.IItemHandlerModifiable) maid.getHandsInvWrapper())
                            .setStackInSlot(0, ItemStack.f_41583_);
                } catch (Exception ignored) {
                }
            });
        }
        // v1.5.47：挖掉任一矿/开路块 → 清除弃置记录（弃置理由随地形变化失效）
        this.abandonedPos = null;
        // v1.5.113（B4）：挖掉任一方块 → 清短时排除（刚才够不着的矿可能已能到达）
        RECENT_DISCARD.remove(maid.m_19879_());
        // v1.5.87：硬挡弃置同样清除（开路改变了地形，阻挡可能已解除）
        this.blockedOres.clear();
        // v1.5.25h：挖到矿后不再主动清理/下柱（旧 cleanupStep/descendStep 已移除）——
        // 搭的方块统一由 expirePlacedBlocks 在放置 10 秒后自动销毁变掉落物回收。
        // 女仆直接找下一个矿继续挖；站在柱子上的话等方块自然消失（掉落物回收）
        // v1.5.161：连锁采集——记录刚挖掉的方块类型并 BFS 填充连锁队列（队列里的
        // 同族矿挖完一块自动接下一块，直到挖完矿脉）；开关关闭时清空队列走正常找矿
        this.chainBlock = state.m_60734_();
        this.refillChainQueue(level, maid);
        // v1.5.172：连锁采集【同时破坏】（FTB Ultimine 式）——目标矿挖完瞬间把
        // 队列里相连的同族矿一次性全部破坏（掉落直接进背包），视觉上一挖一串；
        // 不再逐个挖（旧版"自动连挖"看不出连锁效果，用户反馈）
        this.chainBreakAll(level, maid, mainHand);
        this.targetPos = null;
        this.destroyProgress = 0.0f;
        this.saveProgressNow(maid);
    }

    /**
     * v1.5.172：连锁采集【同时破坏】——把 refillChainQueue 填好的队列（相连同族矿）
     * 一次性全部破坏：掉落物直接进女仆背包（放不下落地，与单块自动收集一致），
     * 音效/粒子只在目标矿播（连锁块静默，防 16 连爆音刷屏），镐耐久只扣目标矿一次。
     * 破坏后队列自然清空（下一 tick 取队列时 isOre 校验失败被 poll 掉，不残留）。
     */
    private void chainBreakAll(ServerLevel level, EntityMaid maid, ItemStack mainHand) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            return;
        }
        int broken = 0;
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        while (!this.chainQueue.isEmpty() && broken < limit) {
            BlockPos pos = this.chainQueue.poll();
            if (pos == null || pos.equals(this.targetPos)) {
                continue;
            }
            BlockState st = level.m_8055_(pos);
            if (st.m_60795_() || st.m_60734_() != this.chainBlock || !this.isOre(level, pos)) {
                continue; // 已被挖掉/类型不符（队列里的过期矿位）
            }
            if (isMiningPlaced(level, pos)) {
                continue; // 自己搭的方块不连锁
            }
            BlockEntity be = level.m_7702_(pos);
            java.util.List<ItemStack> drops = Block.m_49874_(st, level, pos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, pos);
            level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
            broken++;
        }
        if (broken > 0) {
            LOGGER.info("mine chain burst: maid={} block={} broken={}",
                    maid.m_20148_(), ForgeRegistries.BLOCKS.getKey(this.chainBlock), broken);
        }
    }

    /** v1.5.102e：向周围玩家广播挖掘裂纹（ClientboundBlockDestructionPacket）——
     *  stage 0~9 = 裂纹阶段，10 = 移除。女仆不是玩家，原版不会自动发此包，
     *  不加的话方块全程无裂纹、到点瞬间消失（"秒破"观感）。
     *  v1.5.103：只在【阶段变化】时发——同一阶段连发是浪费（慢方块 30 tick
     *  有大量重复阶段），缓存上次位置+阶段，变化才广播。 */
    private BlockPos lastCrackPos = null;
    private int lastCrackStage = -1;

    private void broadcastCrack(ServerLevel level, EntityMaid maid, BlockPos pos, int stage) {
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
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.25 搭路决策：向上搭 vs 向前搭 的判定链。
     * 目标够不着时的三种接近策略（每 tick 重算，垫一块就重新评估，直到够得着）：
     * 1. 上方矿 + 水平近（dy≥2 且 hDist≤2.5）→ 脚下搭高（垫高自己）
     * 2. 斜上方矿（dy≥1 且 2.5<hDist≤4.5）→ 向前垫台阶（斜坡逼近，水平+垂直同时接近）
     * 3. 其他（太远 / 在下方）→ 走过去；目标方向前方脚下悬空（断崖/水）→ 先搭桥
     * 搭高失败（没料/头顶堵）不立刻放弃——退到"走过去"兜底，走不通由导航处理。
     */
    private void approachOre(ServerLevel level, EntityMaid maid) {
        BlockPos t = this.targetPos;
        double hx = t.m_123341_() + 0.5 - maid.m_20185_();
        double hz = t.m_123343_() + 0.5 - maid.m_20189_();
        double hDist = Math.sqrt(hx * hx + hz * hz);
        int dy = t.m_123342_() - maid.m_20183_().m_123342_();
        // v1.5.111：搭高【站桩】——停掉直接导航（v1.5.127：旧版清 WALK_TARGET 记忆，
        // 现在改为停导航，语义一致：原地站定让 pillarGuard 钳位，垂直列干净成型）
        if (dy >= 2 && hDist <= 2.5) {
            if (this.pillarUpStep(level, maid)) {
                maid.m_21573_().m_26573_();
                return;
            }
        }
        // 2) 斜上方矿：向前垫台阶（斜坡），水平+垂直同时逼近
        // v1.5.113：垫完【走一步到刚垫的台阶上】——旧版垫完只 walkToOreBase
        // （目的地可能隔着未垫完的沟，导航找不到路 → 站着不动）；改走一步再评估
        if (dy >= 1 && hDist > 2.5 && hDist <= 4.5) {
            if (this.slopeStep(level, maid, hx, hz, hDist)) {
                this.walkToStep(level, maid, hx, hz, hDist);
                return;
            }
        }
        // 3) 其他：目标方向前方脚下悬空 → 搭桥；否则走过去
        // v1.5.113：搭桥后【走一步上桥】——旧版搭完直接 return（无移动目标）
        // → 女仆站在桥头"搭一格就站着不动"根因
        if (this.bridgeToOre(level, maid, hx, hz, hDist)) {
            this.walkToStep(level, maid, hx, hz, hDist);
            return;
        }
        // v1.5.87：走路目标 = 矿基座（矿下方 1 格）——旧版目标是矿本身（常在空中/岩壁内），
        // 导航走不到 → 女仆站在远处不动（"欲望太低"根因）；改为可达位置后她会持续贴近矿物
        // v1.5.105：重设节流——单纯"走过去"时每 30 tick 才尝试重设一次 WalkTarget，
        // 避免每 tick 重设让 TLM 每 tick 重寻路（移动顿挫）；目标没变（±1 容差）时
        // setWalkTarget 直接跳过（v1.5.116/121），此节流只是兜底下限；搭高/斜坡
        // 成功后的即时重设（垫一步走一步）不受影响
        if (this.walkRetargetCooldown > 0) {
            this.walkRetargetCooldown--;
        } else {
            // v1.5.124：重设节流 30 → 10 tick——旧版 1.5 秒才重试一次目标，
            // 撞上 MoveToTargetSink 的失败冷却（最多 40 tick）时女仆原地愣 2~3 秒；
            // 10 tick 后重试，setWalkTarget 内部对"目标未变且导航行进中"本就跳过
            // （不重新寻路），节流只是兜底，调低响应更快、不会增加寻路负担
            this.walkRetargetCooldown = 10;
            this.walkToOreBase(level, maid, t);
        }
    }

    /**
     * v1.5.116：设置移动目标——目标没变 且 导航仍在行进 → 不重设。
     * 导航每次收新目标都要停下重新寻路（PathNavigation.recomputePath），
     * 旧版 walkToOreBase 每 8 tick 无条件重设 → 女仆"走一下顿一下"鬼畜。
     * v1.5.121：±1 容差（相邻站立格抖动不重设）——但去掉 !isDone 条件导致
     * 女仆到达站立点后永远不再重设 = "完全不会走路"卡死（回归，v1.5.122 修复）。
     * v1.5.122：容差 + 导航行进中（!isDone）才跳过；导航到达/失败（isDone）时
     * 重设以重新激活寻路，女仆不会停在原地。
     */
    private void setWalkTarget(EntityMaid maid, BlockPos stand, float speed) {
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
        // 寻路范围：PathNavigation 的搜索盒 = followRange + 1（女仆约 16~17 格），
        // 目标更远时 createPath 返回 null → 直接 moveTo 会失败原地不动——超过
        // 14 格就先朝目标方向走 12 格的中间点（导航到达后再重设，自然接力）。
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

    /**
     * v1.5.87：朝矿基座推进——搭高/垫台阶成功后立刻走，让女仆在水平方向持续贴近矿物。
     * v1.5.113（B5/C1）：站立点改为【矿 6 方向最近的可站空气格】——旧版只向上找
     * "矿正上方空气格"，岩壁深处最近空气在矿上方 5+ 格 → 女仆走到够不着的地方空转；
     * 且接近速度两档（远快走近慢走，防搭高漂移）。
     */
    private void walkToOreBase(ServerLevel level, EntityMaid maid, BlockPos t) {
        BlockPos stand = findStandNearOre(level, maid, t);
        if (stand == null) {
            // 矿被完全包住（6 方向都无立足点）→ 不设移动目标，挡路挖掘（blocker）
            // 下一 tick 接管：够得着的可挖穿方块会被逐层挖开，直到矿暴露
            return;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, t));
    }

    /**
     * v1.5.113（B5）：找矿附近最近的【可站立格】——候选 = 矿 6 方向各 1 格 + 矿下方 2 格，
     * 要求：候选格可呼吸（空气）、其脚下（候选-1）实心可站、且离女仆最近（走过去最近）。
     * 找不到（矿被完全包住）返回 null（挡路挖掘接管）。
     */
    private BlockPos findStandNearOre(ServerLevel level, EntityMaid maid, BlockPos t) {
        int[][] offsets = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -2, 0}};
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] o : offsets) {
            BlockPos cand = t.m_7918_(o[0], o[1], o[2]);
            if (!level.m_8055_(cand).m_60795_()) {
                continue; // 候选格不是空气
            }
            BlockPos under = cand.m_7918_(0, -1, 0);
            BlockState underState = level.m_8055_(under);
            if (underState.m_60795_() || !underState.m_60796_(level, under)) {
                continue; // 脚下无支撑（悬空）
            }
            double d = maid.m_20275_(cand.m_123341_() + 0.5, cand.m_123342_() + 0.5, cand.m_123343_() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = cand;
            }
        }
        if (best != null) {
            return best;
        }
        // 兜底：矿正上方向上的最近空气（旧逻辑；仅当 6 方向都无立足点时）
        int sy = t.m_123342_();
        for (int y = sy + 1; y < sy + 5; y++) {
            BlockPos cand = new BlockPos(t.m_123341_(), y, t.m_123343_());
            if (level.m_8055_(cand).m_60795_()) {
                return cand;
            }
        }
        return null;
    }

    /**
     * v1.5.113：搭路后【走一步】——目标 = 前方 1 格（刚垫桥/台阶的位置，脚下已有
     * 支撑），走过去后下一 tick 再评估下一块。修复"搭一格就站着不动"。
     */
    private void walkToStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockPos stand = new BlockPos(tx, y, tz);
        if (!level.m_8055_(stand).m_60795_()) {
            stand = stand.m_7918_(0, 1, 0); // 前方格被占用（方块顶起）→ 站上一格
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, this.targetPos));
    }

    /**
     * v1.5.113（C1）：两档接近速度——矿远（>8 格）1.5× 快走赶路，矿近（≤3 格）
     * 0.8× 慢走（准备搭高/挖掘，防冲过头漂移）；中间距离用基础速度（mine.moveSpeed）。
     */
    private float approachSpeed(EntityMaid maid, BlockPos t) {
        if (t == null) {
            return (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_MOVE_SPEED.get();
        }
        double d = maid.m_20275_(t.m_123341_() + 0.5, t.m_123342_() + 0.5, t.m_123343_() + 0.5);
        float base = (float) (double) com.maidsmart.config.MaidSmartConfig.MINE_MOVE_SPEED.get();
        if (d > 8.0) {
            return base * 1.5f;
        }
        if (d < 3.0) {
            return base * 0.8f;
        }
        return base;
    }

    /**
     * v1.5.90：朝挡路块推进（穿透挖掘用）——目标是挡路块"脚下 1 格"（与挡路块
     * 同柱的地面），女仆走到墙根后挡路块就在伸手范围内 → 下一 tick 开始挖穿。
     * 与 walkToOreBase 的区别：挡路块脚下通常是实心地基，不需要"找上方空气站到
     * 矿正上方往下挖"（那是埋地矿专用的）；脚下悬空才退回 walkToOreBase 兜底。
     */
    private void walkToBlockFace(ServerLevel level, EntityMaid maid, BlockPos b) {
        BlockPos stand = new BlockPos(b.m_123341_(), b.m_123342_() - 1, b.m_123343_());
        BlockState below = level.m_8055_(stand);
        if (below.m_60795_() || !below.m_60796_(level, stand)) {
            this.walkToOreBase(level, maid, b);
            return;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, b));
    }

    /**
     * v1.5.87：挖出的掉落物即时回收——拾取任务在挖矿中已让位（最低优先级），
     * 这里把刚挖出（生成在挖矿格附近）的掉落物原地收进背包，不掉地上、不走"走过去捡"。
     */
    private void pickupOreDrops(ServerLevel level, EntityMaid maid) {
        BlockPos p = this.targetPos;
        if (p == null) {
            return;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(p).m_82400_(1.5);
        for (net.minecraft.world.entity.item.ItemEntity e :
                level.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (e.m_6084_()) {
                maid.pickupItem(e, false);
            }
        }
    }

    /**
     * v1.5.161：连锁采集——从刚挖掉的位置 BFS（6 方向）找相连的同族矿排队。
     * 匹配规则（借鉴 FTB Ultimine 的 BlockMatcher：同 Block 判定）：
     * - 方块类型与刚挖掉的矿相同（chainBlock）
     * - 在矿表内（isOre，含自定义矿表）——只连锁矿，不连锁石头/泥土
     * - 不是女仆自己搭的方块（isMiningPlaced 排除）
     * 队列上限从配置面板读取（mine.chainLimit，默认 16）；BFS 展开上限
     * 64 防超大矿脉卡顿。开关关闭时直接清空。
     */
    private void refillChainQueue(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            this.chainBlock = null;
            return;
        }
        if (!this.chainQueue.isEmpty()) {
            return; // 队列还有货，挖完再补
        }
        int limit = com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_LIMIT.get();
        BlockPos start = this.targetPos;
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> bfs = new java.util.ArrayDeque<>();
        bfs.add(start.m_7949_());
        visited.add(start.m_7949_());
        while (!bfs.isEmpty() && this.chainQueue.size() < limit) {
            BlockPos cur = bfs.poll();
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.Vec3i step = d.m_122436_();
                BlockPos nb = cur.m_7918_(step.m_123341_(), step.m_123342_(), step.m_123343_());
                if (!visited.add(nb.m_7949_())) {
                    continue;
                }
                BlockState ns = level.m_8055_(nb);
                if (ns.m_60795_() || ns.m_60734_() != this.chainBlock) {
                    continue;
                }
                if (!this.isOre(level, nb)) {
                    continue; // 只连锁矿脉，不连锁石头
                }
                if (isMiningPlaced(level, nb)) {
                    continue; // 自己搭的方块不连锁
                }
                if (this.chainQueue.size() < limit) {
                    this.chainQueue.add(nb.m_7949_());
                }
                if (bfs.size() < 64) {
                    bfs.add(nb.m_7949_());
                }
            }
        }
        // v1.5.164：诊断日志（排查"连锁采集没生效"）——每次新矿脉填充打一次
        if (!this.chainQueue.isEmpty()) {
            LOGGER.info("mine chain filled: maid={} block={} queued={} limit={}",
                    maid.m_20148_(),
                    ForgeRegistries.BLOCKS.getKey(this.chainBlock),
                    this.chainQueue.size(), limit);
        } else if (com.maidsmart.config.MaidSmartConfig.MINE_CHAIN_MINING.get()) {
            LOGGER.info("mine chain empty: maid={} block={} (no adjacent ore)",
                    maid.m_20148_(), ForgeRegistries.BLOCKS.getKey(this.chainBlock));
        }
    }

    /**
     * v1.5.25 搭高一步：往脚下放方块把自己垫高。
     * v1.5.24 头顶检查防窒息；v1.5.25 放块位置【脚下格优先】（脚下悬空垫脚下，
     * 人自然站上去不夹人；脚下实心才放所在格顶起），头顶 1 格检查放宽——
     * 目标矿在正上方也不误判为"障碍"（旧版把要挖的矿当障碍直接放弃 → 成功率低）。
     */
    private boolean pillarUpStep(ServerLevel level, EntityMaid maid) {
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true; // 冷却中：下 tick 再垫（v1.5.113 B6：成功放置才设冷却）
        }
        BlockPos pos = maid.m_20183_();
        // v1.5.25b：选择放置格——脚下悬空 → 垫所在格 pos（站上去升高）；
        // 脚下实心（平地）→ 垫身体格 pos+1（挤压顶起一格，不碰地面）。
        // 旧版要求"所在格必须空气"：女仆站在矿洞底平地上脚部格是地面方块
        // → 搭高永远失败 → 一直"走过去"死循环（"搭方块取钻石成功率低"根因）。
        BlockPos place;
        boolean posAir = level.m_8055_(pos).m_60795_();
        if (posAir) {
            place = pos; // 悬空：垫所在格，站上去升高
        } else {
            place = pos.m_7918_(0, 1, 0); // 平地：垫身体格顶起
        }
        // 防窒息：放置格 + 上方 2 格必须空气（顶起后的身体空间 + 头顶空间）
        if (!level.m_8055_(place).m_60795_()
                || !level.m_8055_(place.m_7918_(0, 1, 0)).m_60795_()
                || !level.m_8055_(place.m_7918_(0, 2, 0)).m_60795_()) {
            return false;
        }
        // v1.5.25e 借鉴自保 buildUp：女仆实际头顶（bounding box 顶面）上方必须空旷——
        // 快速连续搭高时实体位移滞后，blockPosition 检查不够，必须按真实位置判定，
        // 否则"搭太快把自己埋了"窒息（挖矿搭高也会踩同一坑）
        double headY = maid.m_20191_().m_82374_(net.minecraft.core.Direction.Axis.Y);
        BlockPos headPos = new BlockPos((int) maid.m_20185_(), (int) (headY + 0.05), (int) maid.m_20189_());
        if (!level.m_8055_(headPos).m_60795_()
                || !level.m_8055_(headPos.m_7918_(0, 1, 0)).m_60795_()) {
            return false; // 实际头顶被堵（正在被顶起中）→ 等站稳再垫，防窒息
        }
        // 目标矿在正上方时不误判为"障碍"（v1.5.25：头顶检查放宽，不检查矿所在格）
        Item item = this.takeBuildBlock(maid);
        if (item == null) {
            // v1.5.113（C2）：搭方块材料耗尽 → 气泡提示一次（限频），不再反复尝试
            this.notifyNoBuildBlock(maid);
            return false;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
        if (block == null) {
            return false;
        }
        level.m_7731_(place, block.m_49966_(), 3);
        trackPlaced(level, place, block); // v1.5.28：全局登记（每块满 10 秒各自销毁）
        this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
        // v1.5.113（B6）：冷却只在【成功放置】后设置——旧版失败也消耗冷却，
        // 搭高被错误阻塞
        this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get();
        return true;
    }

    /** v1.5.113（C2）：搭方块材料耗尽播报（限频 30 秒） */
    private void notifyNoBuildBlock(EntityMaid maid) {
        long now = maid.m_9236_().m_46467_();
        Long last = NO_BLOCK_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < 600L) {
            return;
        }
        NO_BLOCK_REPORT_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "背包里没有能搭的方块了（圆石/泥土等），够不着高处的矿……请给我一些方块～");
    }

    /**
     * v1.5.25 斜坡逼近：目标在斜上方（水平 2.5~4.5、垂直差 ≥1）时，
     * 在朝目标方向的前方 1 格脚下垫方块形成台阶——女仆走上去后水平+垂直同时接近，
     * 反复垫直到够得着。前方脚下是平地（不悬空）→ 不需要垫，直接走过去即可。
     */
    private boolean slopeStep(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true; // v1.5.113（B6）：冷却中——等一步走完再垫（不重复消耗）
        }
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockPos ahead = new BlockPos(tx, y, tz);
        BlockPos fill = ahead.m_7918_(0, -1, 0);
        BlockState below = level.m_8055_(fill);
        // 前方脚下悬空（空气/水）且前方格本身可走 → 垫台阶/桥
        boolean gap = below.m_60795_() || below.m_60734_()
                == ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
        if (gap && level.m_8055_(ahead).m_60795_()) {
            Item item = this.takeBuildBlock(maid);
            if (item == null) {
                this.notifyNoBuildBlock(maid); // v1.5.113（C2）
                return false;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
            if (block == null) {
                return false;
            }
            level.m_7731_(fill, block.m_49966_(), 3);
            trackPlaced(level, fill, block); // v1.5.28：全局登记（10 秒后统一销毁）
            this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
            this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get(); // v1.5.113（B6）
            return true;
        }
        return false;
    }

    /**
     * v1.5.25 搭桥：目标方向前方 1 格脚下悬空（断崖/水）→ 垫 1 块桥，再走过去。
     * 只垫"该垫"的位置，不破坏任何方块；垫一块后重算（下 tick 再评估/走）。
     */
    private boolean bridgeToOre(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
        if (hDist < 1.0) {
            return false;
        }
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
            return true; // v1.5.113（B6）：冷却中——等一步走完再垫（不重复消耗）
        }
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockPos ahead = new BlockPos(tx, y, tz);
        BlockPos fill = ahead.m_7918_(0, -1, 0);
        BlockState below = level.m_8055_(fill);
        boolean gap = below.m_60795_() || below.m_60734_()
                == ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse("minecraft:water"));
        if (gap && level.m_8055_(ahead).m_60795_()) {
            Item item = this.takeBuildBlock(maid);
            if (item == null) {
                this.notifyNoBuildBlock(maid); // v1.5.113（C2）
                return false;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(ForgeRegistries.ITEMS.getKey(item));
            if (block == null) {
                return false;
            }
            level.m_7731_(fill, block.m_49966_(), 3);
            trackPlaced(level, fill, block); // v1.5.28：全局登记（10 秒后统一销毁）
            this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
            this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.MINE_PILLAR_COOLDOWN.get(); // v1.5.113（B6）
            return true;
        }
        return false;
    }

    /** v1.5.25f：防窒息兜底（移植自保 antiSuffocate）——每 tick 检测女仆"身体中心"
     * （半身位置）所在格是否为实心满块——是则说明她卡进了方块（搭方块后位移滞后 /
     * 放偏 / 被推挤），直接 setPos 把位置强制移到该方块顶面之上。头顶检查拦不住
     * 横向卡入，这是最后一道保险：宁可瞬移半步也不让她被自己搭的方块闷住。
     */
    private void antiSuffocate(EntityMaid maid) {        net.minecraft.world.phys.AABB box = maid.m_20191_();
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

    /**
     * v1.5.87：搭方块防掉落（仅在挖矿搭方块时生效，效果照搬玩家潜行、移速不变）——
     * 刚搭完柱子/桥的短窗口内，女仆站在自己搭的方块上时，水平位置不允许超出
     * 脚下支撑方块的范围（碰撞箱不越出边缘，与玩家潜行"边缘夹住"一致），
     * 而不是缓慢拉回；窗口结束自动释放不困住女仆。
     */
    private void pillarGuard(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.m_20183_();
        BlockPos under = feet.m_7918_(0, -1, 0);
        if (!isMiningPlaced(level, under) && !isMiningPlaced(level, feet)) {
            return; // 脚下不是自己搭的方块 → 不钳制
        }
        // 潜行核心：实体中心距支撑方块中心的最大偏移 = 0.5 - 碰撞箱半宽
        // （中心到边缘的余量正好让碰撞箱贴着方块边缘，不悬空越界）
        double halfW = maid.m_20205_() / 2.0;
        double limit = Math.max(0.05, 0.5 - halfW);
        double cx = under.m_123341_() + 0.5;
        double cz = under.m_123343_() + 0.5;
        double x = maid.m_20185_();
        double z = maid.m_20189_();
        double nx = Math.max(cx - limit, Math.min(cx + limit, x));
        double nz = Math.max(cz - limit, Math.min(cz + limit, z));
        if (nx != x || nz != z) {
            maid.m_6034_(nx, maid.m_20186_(), nz);
        }
    }

    /** v1.5.24：取背包中数量最多的可搭方块（BlockItem + 非下落），用于搭高挖矿 */
    private Item takeBuildBlock(EntityMaid maid) {
        IItemHandler inv = maid.getMaidInv();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            Block block = bi.m_40614_();
            if (block == null || block instanceof FallingBlock) {
                continue; // 下落方块（沙/砾石/铁砧）不用
            }
            counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
        }
        Item best = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) {
            return null;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == best) {
                ItemStack taken = inv.extractItem(i, 1, false);
                if (!taken.m_41619_()) {
                    return best;
                }
            }
        }
        return null;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.113：挖矿任务期间【常驻】——目标挖完不结束，下一 tick 继续扫描/
        // 迁移（与 canStart 常驻配套）；切走任务才停止（m_6732_ 退场）。
        // 旧版 targetPos 挖完即 false → 行为停止 → 女仆原地发呆不找下一个矿。
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:mine").equals(maid.getTask().getUid());
    }

    private boolean isOre(ServerLevel level, BlockPos pos) {
        return ORE_VALUE.containsKey(level.m_8055_(pos).m_60734_());
    }

    /**
     * v1.5.189：危险方块规避——该格（或脚下）是岩浆/火/岩浆块/仙人掌/甜浆果/营火
     * 视为危险：挖矿目标或路径上不选（照抄自保 DANGER_BLOCKS 判定，天然回避）
     */
    private boolean isDangerAt(ServerLevel level, BlockPos pos) {
        try {
            net.minecraft.world.level.block.state.BlockState state = level.m_8055_(pos);
            net.minecraft.world.level.block.state.BlockState below = level.m_8055_(pos.m_7918_(0, -1, 0));
            for (String id : DANGER_BLOCK_IDS) {
                net.minecraft.world.level.block.Block b =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                                net.minecraft.resources.ResourceLocation.parse(id));
                if (b != null && (state.m_60734_() == b || below.m_60734_() == b)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.189：危险方块注册名（与自保 SelfPreservationBehavior.DANGER_BLOCKS 同款） */
    private static final String[] DANGER_BLOCK_IDS = {
            "minecraft:lava", "minecraft:fire", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:campfire"
    };

    /** v1.5.47：是否可开路废石（穿透挖掘的临时目标；v1.5.87 含徒手可挖软方块）
     *  v1.5.99c：m_135827_ 是 getNamespace（SRG）返回 "minecraft"，永远匹配不上白名单
     *  → 泥土/圆石全被当硬挡路（"拒绝挖掘被泥土包裹的矿物"根因）。getPath 的
     *  正确 SRG 名是 m_135815_（javap 字节码实证：构造器参数 1=namespace→f_135804_，
     *  m_135827_ 返回 f_135804_，m_135815_ 返回 f_135805_=path）。
     *  v1.5.101b：并入面板障碍物名单（MINE_BREAKABLES），统一走 isBreakable。 */
    private boolean isOpenStone(ServerLevel level, BlockPos pos) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(level.m_8055_(pos).m_60734_());
        return key != null && isBreakable(key.m_135815_());
    }

    /** v1.5.101b：是否可挖穿——内置 OPEN_BREAKABLE（自然生成方块）或 面板障碍物名单。
     *  v1.5.102d：基岩/屏障等不可破坏方块永远返回 false（防面板误加后女仆傻挖） */
    private static boolean isBreakable(String path) {
        if ("bedrock".equals(path) || "barrier".equals(path)) {
            return false;
        }
        if (OPEN_BREAKABLE.contains(path)) {
            return true;
        }
        try {
            for (String e : com.maidsmart.config.MaidSmartConfig.MINE_BREAKABLES.get()) {
                if (e != null && e.equals(path)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.5.102d：是否内置自然方块（配置面板障碍物页据此预勾选"所有自然生成的方块"） */
    public static boolean isBuiltInBreakable(String path) {
        return !"bedrock".equals(path) && !"barrier".equals(path) && OPEN_BREAKABLE.contains(path);
    }

    /**
     * v1.5.47：解析锚点——无锚点则设脚下；出框超时重埋。
     * v1.5.113：迁移不再走独立状态机（旧 MIGRATING/MIGRATE_SINCE 已删）——
     * 框空时由 doTick 直接 slideAnchor（滑动锚点，一次到位）。
     * 返回当前锚点（null = 无锚点，调用方应停止）。
     */
    private BlockPos resolveAnchor(ServerLevel level, EntityMaid maid) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        BlockPos anchor = ANCHORS.get(id);
        if (anchor == null) {
            this.relocate(level, maid, now); // 首次定位：脚下
            return ANCHORS.get(id);
        }
        // 出框超时：连续 10 秒在框外 → 重埋（节流 20 tick 防边界抖动）
        if (this.outOfBox(maid.m_20183_(), anchor)) {
            Long since = OUT_SINCE.get(id);
            if (since == null) {
                OUT_SINCE.put(id, now);
            } else if (now - since >= com.maidsmart.config.MaidSmartConfig.MINE_ANCHOR_TIMEOUT.get()) {
                OUT_SINCE.remove(id);
                this.relocate(level, maid, now);
            }
        } else {
            OUT_SINCE.remove(id);
        }
        return ANCHORS.get(id);
    }

    /** v1.5.47：女仆是否在锚点框外（水平 ANCHOR_RADIUS + 垂直 DOWN/UP 范围）
     *  v1.5.88：锚点半径跟随配置面板 mine.searchRadius */
    private boolean outOfBox(BlockPos feet, BlockPos anchor) {
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        return Math.abs(feet.m_123341_() - anchor.m_123341_()) > radius
                || Math.abs(feet.m_123343_() - anchor.m_123343_()) > radius
                || feet.m_123342_() - anchor.m_123342_() > up
                || anchor.m_123342_() - feet.m_123342_() > down;
    }

    /** v1.5.47：重埋锚点（脚下；20 tick 节流） */
    private void relocate(ServerLevel level, EntityMaid maid, long now) {
        int id = maid.m_19879_();
        Long last = LAST_RELOCATE.get(id);
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_RELOCATE_THROTTLE.get()) {
            return;
        }
        LAST_RELOCATE.put(id, now);
        ANCHORS.put(id, maid.m_20183_().m_7949_());
        OUT_SINCE.remove(id);
    }

    /**
     * v1.5.113（B1/B2）：滑动锚点迁移——框内确认挖空后，把锚点朝主人方向滑动
     * 一个搜索环宽（新框与旧框错开一半以上），女仆无需走路、马上能挖新框的矿。
     * 旧版"朝主人走、走出旧框才重埋"有两个致命问题：
     * ① 被墙/悬崖堵住走不动 → 30 秒停滞就地重埋 → 新框与旧框重合 → 又空 → 又迁移
     *    （死循环）；② 迁移期纯走路零产出。
     * 每 5 秒最多滑一次（SLIDE_SINCE 节流），空旷地形也不会高速漂移。
     * 同时清 ORE_CACHE（新框未扫描）并朝新框中心设一个移动目标（赶路不干等）。
     */
    private void slideAnchor(ServerLevel level, EntityMaid maid, BlockPos old) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        Long last = SLIDE_SINCE.get(id);
        if (last != null && now - last < 100L) {
            return; // 5 秒一次
        }
        SLIDE_SINCE.put(id, now);
        int step = Math.max(4, com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get());
        int dirX = 1;
        int dirZ = 0;
        // v1.5.113：owner 直接取（m_269323_ 静态类型就是 LivingEntity，
        // instanceof LivingEntity 在 --release 17 是"无条件模式"编译错误）
        net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
        if (owner != null) {
            double dx = owner.m_20185_() - old.m_123341_();
            double dz = owner.m_20189_() - old.m_123343_();
            if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
                dirX = 0;
                dirZ = 0; // 主人在身边 → 不迁移（以主人脚下为新框）
            } else {
                dirX = dx >= 0 ? 1 : -1;
                dirZ = dz >= 0 ? 1 : -1;
            }
        }
        BlockPos na = new BlockPos(old.m_123341_() + dirX * step,
                old.m_123342_(), old.m_123343_() + dirZ * step).m_7949_();
        ANCHORS.put(id, na);
        OUT_SINCE.remove(id);
        ORE_CACHE.remove(id); // 新框未扫描，强制重建
        // 朝新框中心走（空框时也在赶路，不原地干等；0.6 = 伐木同款基础速度，v1.5.118）
        this.setWalkTarget(maid, na, 0.6f);
    }

    /**
     * v1.5.85：当前主手是否能挖这个方块（正确工具判定，对齐玩家破坏判定）。
     * 矿都需要对应等级的镐子：非镐工具 / 徒手 / 等级不够（DiggerItem.m_8096_ 检查
     * needs_stone/iron/diamond_tool tag）都返回 false → 该矿被跳过。
     */
    private static boolean canHarvest(EntityMaid maid, BlockState state) {
        ItemStack tool = maid.m_21205_();
        if (tool.m_41619_() || !(tool.m_41720_() instanceof net.minecraft.world.item.DiggerItem digger)) {
            return false;
        }
        return digger.m_8096_(state);
    }

    /** v1.5.85：记录本次扫描中镐子挖不动的矿（只保留价值最高的一个，用于播报） */
    private void recordSkippedOre(EntityMaid maid, BlockPos pos, BlockState state) {
        Integer value = ORE_VALUE.get(state.m_60734_());
        if (value == null || (this.skippedOrePos != null && value <= this.skippedOreValue)) {
            return;
        }
        this.skippedOreValue = value;
        this.skippedOrePos = pos.m_7949_();
        // v1.5.102c：报中文矿石名（旧版英文 path 如 diamond_ore）
        this.skippedOreName = blockCnName(state.m_60734_());
        this.skippedOreTool = requiredTool(state);
    }

    /** v1.5.85：这个矿需要什么等级的镐子（按原版挖掘 tag 判定） */
    private static String requiredTool(BlockState state) {
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

    /**
     * v1.5.85：播报"附近有矿但镐子挖不动"——气泡 + 主人聊天栏（v1.5.68 气泡同步），
     * 报坐标和需要的镐子等级；每 30 秒最多一次，不刷屏。
     */
    private void reportSkippedOre(EntityMaid maid, long gameTime) {
        if (this.skippedOrePos == null) {
            return;
        }
        Long last = SKIP_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && gameTime - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        SKIP_REPORT_SINCE.put(maid.m_19879_(), gameTime);
        // v1.5.89：诊断——带上主手实际物品（排查"镐没装备导致挖不了"）
        String handName = "空";
        ItemStack hand = maid.m_21205_();
        if (!hand.m_41619_()) {
            net.minecraft.resources.ResourceLocation hk = ForgeRegistries.ITEMS.getKey(hand.m_41720_());
            handName = hk != null ? com.maidsmart.build.BlueprintLib.cnName(hk.toString()) : hand.m_41720_().toString();
        }
        maid.getChatBubbleManager().addTextChatBubble(
                "我发现了一个" + this.skippedOreName + "（坐标 " + this.skippedOrePos.m_123341_()
                        + ", " + this.skippedOrePos.m_123342_() + ", " + this.skippedOrePos.m_123343_()
                        + "），需要" + this.skippedOreTool + "才能挖，我现在的镐子（主手：" + handName
                        + "）挖不动，先跳过啦～");
    }

    /**
     * v1.5.87：播报"矿被硬挡路（箱子/机器/基岩等）挡住"——气泡 + 主人聊天栏，
     * 报挡路方块与坐标；每 30 秒限频一次。该矿同时加入 blockedOres 持续排除。
     */
    private void reportBlockedOre(EntityMaid maid, ServerLevel level, BlockPos orePos, BlockPos blockerPos) {
        long now = level.m_46467_();
        Long last = BLOCKED_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.m_19879_(), now);
        // v1.5.102c：报中文方块名（旧版英文 path，如 "bedrock"/"chest"）
        String name = blockCnName(level.m_8055_(blockerPos).m_60734_());
        // v1.5.143：被挡的矿物名也报中文（旧版只说"挡住了矿物"，不说挡住的是哪种矿）
        String oreName = blockCnName(level.m_8055_(orePos).m_60734_());
        maid.getChatBubbleManager().addTextChatBubble(
                "前方有" + name + "（坐标 " + blockerPos.m_123341_() + ", " + blockerPos.m_123342_()
                        + ", " + blockerPos.m_123343_() + "）挡住了" + oreName + "（坐标 " + orePos.m_123341_()
                        + ", " + orePos.m_123342_() + ", " + orePos.m_123343_() + "），我挖不过去，先换个目标啦～");
    }

    /**
     * v1.5.102d：场上没有可挖矿物、但此前有被硬物挡住的矿时，仍上报一次挡路原因
     * （30 秒限频）——让主人知道"没矿"其实是被基岩/箱子等挡住了，而不是女仆偷懒。
     */
    private void reportBlockedArea(EntityMaid maid, ServerLevel level) {
        long now = level.m_46467_();
        Long last = BLOCKED_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.MINE_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "附近有矿物被硬方块挡住（基岩/箱子等），我挖不过去，暂时没有可挖的矿物～");
        if (maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer owner) {
            owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                    "\u00a7c【挖矿】" + maid.m_5446_().getString()
                            + "：附近有矿物被硬方块挡住，暂时没有可挖的矿物～"));
        }
    }

    /**
     * v1.5.47：找最优矿石（以【锚点】为中心，杜绝"越挖越远"漂移）：
     * score = 距离平方 + 深度惩罚 - 价值加成，取最低分。
     * 穿透预算：女仆到矿之间实心挡路方块 ≤MAX_BREAK_BUDGET 可选（借鉴 maidmining）；
     * 刚弃置的目标跳过一次。
     *
     * v1.5.113（A1/A3/B4）：性能与行为优化——
     * - 全量扫描（8.9 万格）每 5 秒一次（ORE_CACHE_TTL），期间缓存轮只校验
     *   已记录的矿（存在性/框内/可挖性），廉价得多（旧版每 3 tick 全量扫）；
     * - 挡路预算按矿坐标缓存（缓存轮复用），不再对每个候选重算视线；
     * - 镐能力判定：主手先查（零开销），主手不够才查背包最高级镐（一次遍历）；
     * - 弃置矿 30 秒短时排除（RECENT_DISCARD，B4）。
     */
    private BlockPos findOre(ServerLevel level, EntityMaid maid, BlockPos anchor) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        // v1.5.85：每次扫描重置"镐子挖不动的矿"记录（记录本次扫描价值最高的，用于播报）
        this.skippedOrePos = null;
        this.skippedOreName = null;
        this.skippedOreTool = null;
        this.skippedOreValue = -1;
        // v1.5.113（B4）：修剪过期的短时排除（30 秒）
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        if (disc != null) {
            disc.entrySet().removeIf(e -> now - e.getValue() > 600L);
            if (disc.isEmpty()) {
                RECENT_DISCARD.remove(id);
            }
        }
        // v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值）
        ensureCustomOres();
        OreCache cache = ORE_CACHE.get(id);
        if (cache != null && now - cache.builtAt < ORE_CACHE_TTL) {
            // 缓存轮（每 10 tick）：只校验已记录的矿
            this.lastScanWasFull = false;
            BlockPos fromCache = this.pickFromCache(level, maid, anchor, cache);
            if (fromCache != null) {
                return fromCache;
            }
            // v1.5.127：缓存轮空 → 不等 TTL 立即全量重建——"原地愣 2~3 秒"根因：
            // 挖完缓存里最后一块矿后 pickFromCache 返回 null，旧版要等 ORE_CACHE_TTL
            // （5 秒）到期才重建，女仆在这 2~5 秒里站着发呆；重建顺带把"框内无矿
            // 迁移"从等 5 秒提速到即时。重建仍无 → 返回 null 走迁移逻辑（不变）。
            this.lastScanWasFull = true;
            return this.fullScanOres(level, maid, anchor, now);
        }
        this.lastScanWasFull = true;
        return this.fullScanOres(level, maid, anchor, now);
    }

    /** v1.5.113：全量重建扫描（每 5 秒一次）——扫满整个搜索框，记录候选矿 + 挡路预算 */
    private BlockPos fullScanOres(ServerLevel level, EntityMaid maid, BlockPos anchor, long now) {
        int id = maid.m_19879_();
        int feetY = anchor.m_123342_();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        int budget = com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get();
        double valueWeight = com.maidsmart.config.MaidSmartConfig.MINE_VALUE_WEIGHT.get();
        double depthPenalty = com.maidsmart.config.MaidSmartConfig.MINE_DEPTH_PENALTY.get();
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        java.util.Map<BlockPos, Integer> blockingCache = new java.util.HashMap<>();
        for (int dy = -down; dy <= up; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = anchor.m_7918_(dx, dy, dz);
                    if (p.equals(this.abandonedPos) || this.blockedOres.contains(p)
                            || (disc != null && disc.containsKey(p.m_7949_()))) {
                        continue; // v1.5.47：刚弃置跳过；v1.5.87：硬挡路持续排除；v1.5.113：短时排除
                    }
                    BlockState oreState = level.m_8055_(p);
                    Integer value = ORE_VALUE.get(oreState.m_60734_());
                    if (value == null) {
                        continue;
                    }
                    // v1.5.189：危险方块规避——目标矿自身或路径上有岩浆/火/岩浆块/
                    // 仙人掌/甜浆果/营火 → 不选（挖过去会烫伤/引燃；复用自保 DANGER 判定）
                    if (this.isDangerAt(level, p)) {
                        continue;
                    }
                    // v1.5.85/107：手+背包都挖不动才算真挖不动（跳过+播报）
                    // v1.5.113（A3）：先查主手（零开销），主手不够才查背包最高级镐
                    if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, oreState)) {
                        this.recordSkippedOre(maid, p, oreState);
                        continue;
                    }
                    // v1.5.47：穿透预算——挡路实心方块 >预算 的矿不选（挖不过去）
                    int blocking = this.countBlocking(level, maid, p);
                    if (blocking > budget) {
                        continue;
                    }
                    blockingCache.put(p.m_7949_(), blocking); // v1.5.113（A3）：缓存轮复用
                    found.add(p.m_7949_());
                    double score = dx * dx + dz * dz + dy * dy
                            + depthPenalty * Math.max(0, feetY - p.m_123342_())
                            - value * valueWeight;
                    if (score < bestScore) {
                        bestScore = score;
                        best = p.m_7949_();
                    }
                }
            }
        }
        ORE_CACHE.put(id, new OreCache(now, found, blockingCache));
        return best;
    }

    /** v1.5.113：缓存轮——只校验已记录的矿（存在性/框内/可挖/挡路预算），廉价 */
    private BlockPos pickFromCache(ServerLevel level, EntityMaid maid, BlockPos anchor, OreCache cache) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        int feetY = anchor.m_123342_();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.MINE_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.MINE_UP_RANGE.get();
        int budget = com.maidsmart.config.MaidSmartConfig.MINE_BREAK_BUDGET.get();
        double valueWeight = com.maidsmart.config.MaidSmartConfig.MINE_VALUE_WEIGHT.get();
        double depthPenalty = com.maidsmart.config.MaidSmartConfig.MINE_DEPTH_PENALTY.get();
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        for (BlockPos p : cache.ores) {
            if (p.equals(this.abandonedPos) || this.blockedOres.contains(p)
                    || (disc != null && disc.containsKey(p))) {
                continue;
            }
            if (!this.isOre(level, p)) {
                continue; // 已被挖掉
            }
            int dx = p.m_123341_() - anchor.m_123341_();
            int dz = p.m_123343_() - anchor.m_123343_();
            int dy = p.m_123342_() - anchor.m_123342_();
            if (Math.abs(dx) > radius || Math.abs(dz) > radius
                    || dy > up || -dy > down) {
                continue; // 锚点滑动/重埋后出框
            }
            BlockState st = level.m_8055_(p);
            if (!MaidToolAutoEquip.canHarvestWithHandOrBackpack(maid, st)) {
                this.recordSkippedOre(maid, p, st);
                continue;
            }
            // v1.5.189：危险方块规避（缓存轮同样跳过岩浆/火/岩浆块等目标）
            if (this.isDangerAt(level, p)) {
                continue;
            }
            Integer value = ORE_VALUE.get(st.m_60734_());
            int blocking = cache.blocking.getOrDefault(p, budget + 1);
            if (value == null || blocking > budget) {
                continue;
            }
            double score = dx * dx + dz * dz + dy * dy
                    + depthPenalty * Math.max(0, feetY - p.m_123342_())
                    - value * valueWeight;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /**
     * v1.5.47：女仆到目标路径上的实心非矿方块数（≤预算可穿透；矿石/空气/非满块/水不算）。
     * 改造自 v1.5.25g hasLineOfSight——旧版"全遮挡排除"导致被岩壁包住的矿永远挖不到。
     */
    private int countBlocking(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.m_20185_();
        double sy = maid.m_20186_() + 1.2; // 眼睛高度
        double sz = maid.m_20189_();
        double tx = target.m_123341_() + 0.5;
        double ty = target.m_123342_() + 0.5;
        double tz = target.m_123343_() + 0.5;
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0)); // 每 0.5 格采样一次
        int blocking = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = sx + (tx - sx) * t;
            double y = sy + (ty - sy) * t;
            double z = sz + (tz - sz) * t;
            BlockPos sample = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sample.equals(target)) {
                break; // 到矿本身，不再检查
            }
            BlockState st = level.m_8055_(sample);
            if (st.m_60795_()) {
                continue; // 空气
            }
            if (ORE_VALUE.containsKey(st.m_60734_())) {
                continue; // 矿石不挡
            }
            if (!st.m_60796_(level, sample)) {
                continue; // 非满块（楼梯/台阶/花/草）不挡；水不算
            }
            // v1.5.87：可开路方块（OPEN_BREAKABLE：石头类 + 徒手可挖软方块）不计阻挡——
            // 与 findBlockingBlock 的开路判定同一标准。旧版只按硬度判软（安山岩 1.5 被算
            // 阻挡），大量泥土/安山岩包围的矿被预算排除 → "发现矿但挖不了"根因
            if (isSoft(maid.m_21205_(), st) || isOpenStone(level, sample)) {
                continue;
            }
            blocking++;
        }
        return blocking;
    }

    /**
     * v1.5.47：路径上第一个挡路块（穿透挖掘用——够得着但中间挡着方块时细分处理）。
     * v1.5.87 细分：
     * - OPEN_BREAKABLE（石头类 + 徒手可挖软方块）→ Blocker(pos, true)，挖掉开路
     * - 其他实心满块（箱子/机器/基岩等）→ Blocker(pos, false)，报点弃置（绝不隔墙挖矿）
     * - 无挡路 → null（视线通畅，直接挖矿）
     */
    private Blocker findBlockingBlock(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.m_20185_();
        double sy = maid.m_20186_() + 1.2;
        double sz = maid.m_20189_();
        double tx = target.m_123341_() + 0.5;
        double ty = target.m_123342_() + 0.5;
        double tz = target.m_123343_() + 0.5;
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = sx + (tx - sx) * t;
            double y = sy + (ty - sy) * t;
            double z = sz + (tz - sz) * t;
            BlockPos sample = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sample.equals(target)) {
                return null; // 无挡路
            }
            BlockState st = level.m_8055_(sample);
            if (st.m_60795_() || ORE_VALUE.containsKey(st.m_60734_()) || !st.m_60796_(level, sample)) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(st.m_60734_());
            boolean openable = key != null && isBreakable(key.m_135815_());
            return new Blocker(sample.m_7949_(), openable);
        }
        return null;
    }

    /** v1.5.87：挡路块判定结果——openable=true 挖掉开路；false 报点弃置 */
    private record Blocker(BlockPos pos, boolean openable) {
    }

    /** v1.5.47：废石丢弃——每种保留 JUNK_KEEP 份，超出直接销毁（不生成掉落物，防被捡回）
     *  v1.5.88：保留量从配置面板读取（mine.junkKeep） */
    private void maybeDropJunk(EntityMaid maid) {
        int keep = com.maidsmart.config.MaidSmartConfig.MINE_JUNK_KEEP.get();
        IItemHandler inv = maid.getMaidInv();
        java.util.Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_() || !(stack.m_41720_() instanceof BlockItem bi)) {
                continue;
            }
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.BLOCKS.getKey(bi.m_40614_());
            if (key != null && JUNK_STONES.contains(key.m_135815_())) {
                counts.merge(stack.m_41720_(), stack.m_41613_(), Integer::sum);
            }
        }
        for (java.util.Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() <= keep) {
                continue;
            }
            int excess = e.getValue() - keep;
            for (int i = 0; i < inv.getSlots() && excess > 0; i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.m_41619_() && stack.m_41720_() == e.getKey()) {
                    int take = Math.min(excess, stack.m_41613_());
                    inv.extractItem(i, take, false);
                    excess -= take;
                }
            }
        }
    }
}
