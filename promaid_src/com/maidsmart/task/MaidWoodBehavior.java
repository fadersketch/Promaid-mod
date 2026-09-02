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
import net.minecraft.world.item.AxeItem;
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
 * 伐木行为（v1.1.0）：找最近且最有价值的木材 → 走过去 → 手持斧渐进开采。
 * 架构完整克隆挖矿（MaidMineBehavior），木材特化差异：
 * - 目标表 = 木材表（原木/菌柄/竹/去皮变体；wood 段配置接管）
 * - 工具 = 斧（空手/非斧才从背包装备——玩家手中放的斧不动；完全没斧也照样空手
 *   慢速砍（v1.1.0 终审三：木材无挖掘等级，不因空手拒绝工作）
 * - **树叶不挡视线/不计阻挡**——树冠内的树干照常可见可挖（砍树核心场景）
 * - 深度惩罚默认 0（树在地表，不偏好浅层）
 * - 连锁采集天然适配整棵树（同 Block BFS 沿树干蔓延砍完）
 * - 废石策略与挖矿一致（挖穿泥土/石头开路产生的废石照常限量丢弃）
 */
public class MaidWoodBehavior extends Behavior<EntityMaid> {
    /** 诊断日志（连锁采集/自动收集是否生效的排查用） */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 木材价值（分数减项，越高越优先）；价值权重/深度惩罚从配置面板读取（wood 段） */
    private static final Map<Block, Integer> WOOD_VALUE = new HashMap<>();

    static {
        reloadBuiltinWoods(); // 初始默认木材表；首次 loadCustomWoods() 后完全由配置接管
    }

    /** v1.1.0：模组原木自动识别——原版 tag 键（与手册木材网格过滤同一套，TagKey 按注册名等值） */
    private static final net.minecraft.tags.TagKey<Block> LOGS_TAG = net.minecraft.tags.BlockTags.create(
            ResourceLocation.parse("minecraft:logs"));
    private static final net.minecraft.tags.TagKey<Block> BAMBOO_TAG = net.minecraft.tags.BlockTags.create(
            ResourceLocation.parse("minecraft:bamboo_blocks"));
    /** v1.1.0 实测五：树苗 tag（原版+模组树苗都带 #minecraft:saplings） */
    private static final net.minecraft.tags.TagKey<Block> SAPLINGS_TAG = net.minecraft.tags.BlockTags.create(
            ResourceLocation.parse("minecraft:saplings"));

    /** 可砍判定：名单内；或 wood.tagAuto 开时带原版 logs/bamboo_blocks 标签（模组原木照砍） */
    private static boolean isWoodState(BlockState state) {
        if (WOOD_VALUE.containsKey(state.m_60734_())) {
            return true;
        }
        return com.maidsmart.config.MaidSmartConfig.WOOD_TAG_AUTO.get()
                && (state.m_204336_(LOGS_TAG) || state.m_204336_(BAMBOO_TAG));
    }

    /** 木材价值：名单值优先；tag 自动识别（不在名单）= 300（与内置原木同价） */
    private static Integer woodValueOf(BlockState state) {
        Integer v = WOOD_VALUE.get(state.m_60734_());
        if (v != null) {
            return v;
        }
        if (com.maidsmart.config.MaidSmartConfig.WOOD_TAG_AUTO.get()
                && (state.m_204336_(LOGS_TAG) || state.m_204336_(BAMBOO_TAG))) {
            return Integer.valueOf(300);
        }
        return null;
    }

    private static void setValue(String id, int value) {
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(id));
        if (block != null) {
            WOOD_VALUE.put(block, value);
        }
    }

    /**
     * 内置木材表（默认占位；一旦 loadCustomWoods 从配置重建，仅配置为唯一事实源）。
     * 全部原版原木/菌柄/竹/去皮变体——模组木材通过配置面板加入（创造网格已按木质 tag 过滤）。
     */
    private static void reloadBuiltinWoods() {
        setValue("minecraft:oak_log", 300);
        setValue("minecraft:spruce_log", 300);
        setValue("minecraft:birch_log", 300);
        setValue("minecraft:jungle_log", 300);
        setValue("minecraft:acacia_log", 300);
        setValue("minecraft:dark_oak_log", 300);
        setValue("minecraft:mangrove_log", 300);
        setValue("minecraft:cherry_log", 300);
        setValue("minecraft:crimson_stem", 300);
        setValue("minecraft:warped_stem", 300);
        setValue("minecraft:bamboo_block", 300);
        setValue("minecraft:stripped_oak_log", 300);
        setValue("minecraft:stripped_spruce_log", 300);
        setValue("minecraft:stripped_birch_log", 300);
        setValue("minecraft:stripped_jungle_log", 300);
        setValue("minecraft:stripped_acacia_log", 300);
        setValue("minecraft:stripped_dark_oak_log", 300);
        setValue("minecraft:stripped_mangrove_log", 300);
        setValue("minecraft:stripped_cherry_log", 300);
        setValue("minecraft:stripped_crimson_stem", 300);
        setValue("minecraft:stripped_warped_stem", 300);
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
     * v1.0.3.1：先 clear 再按配置重建——旧版只 put 不 remove，界面取消勾选矿物后
     * WOOD_VALUE 残留该方块 → 女仆仍继续挖（"取消勾选不生效"根因）。
     * 配置 oreValues 是唯一事实源：从配置删除的矿（含曾 add 的模组矿如银矿石、
     * 以及从默认列表删掉的内置矿）立即从 WOOD_VALUE 消失；删光矿 = 女仆不挖矿，
     * 这是玩家主动选择，属正常响应。
     */
    public static void loadCustomWoods() {
        try {
            WOOD_VALUE.clear(); // v1.0.3.1：先清空——保证移除/取消勾选生效
            for (String line : com.maidsmart.config.MaidSmartConfig.WOOD_VALUES.get()) {
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(parts[1].trim());
                    Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(parts[0].trim()));
                    if (b != null) {
                        WOOD_VALUE.put(b, v);
                    }
                } catch (Exception ignored) {
                }
            }
            customOresLoaded = true;
            WOOD_CACHE.clear(); // v1.5.113：矿表变化 → 缓存失效，强制重建
        } catch (Exception ignored) {
        }
    }

    /** v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值） */
    private static void ensureCustomWoods() {
        if (!customOresLoaded) {
            loadCustomWoods();
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
        double r = com.maidsmart.config.MaidSmartConfig.WOOD_REACH.get();
        return r * r;
    }

    /**
     * v1.1.0 实测一百一十九：目标方块 AABB 上离女仆最近点的距离平方。
     * 玩家手长的正确语义：方块【任一点】够得着就能挖。旧版取方块中心距离——
     * 垂直边界场景（站进砍空的树洞/树基处挖正上方的木头）下一根木头的中心
     * 恰好压在 4.5 格伸手边界上，女仆实体水平偏移 0.1 格（走进树洞/寻路抖动）
     * 就 distSq > reachSq → 永远进搭路分支 → 搭方块被防窒息/没材料挡住 →
     * 原地发呆（看门狗重置后几何不变照样冻死，"砍树发呆修不好"的根因）。
     * 最近点判定后：正上方的木头底面对女仆只有 4.0 格（dy=4）甚至更近，
     * 稳定够得着，逐节往上啃；够不着的（dy≥5）才走搭方块爬树。
     */
    private static double distSqToBlock(EntityMaid maid, BlockPos pos) {
        double x = Math.max(pos.m_123341_(), Math.min(maid.m_20185_(), pos.m_123341_() + 1));
        double y = Math.max(pos.m_123342_(), Math.min(maid.m_20186_(), pos.m_123342_() + 1));
        double z = Math.max(pos.m_123343_(), Math.min(maid.m_20189_(), pos.m_123343_() + 1));
        double dx = maid.m_20185_() - x;
        double dy = maid.m_20186_() - y;
        double dz = maid.m_20189_() - z;
        return dx * dx + dy * dy + dz * dz;
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
     * 旧版 findWood 以女仆当前位置为中心——挖一块后中心跟着动，越挖越远（漂移），
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
    /** v1.1.0：没有斧头播报限频（实体 ID → 上次播报 tick） */
    private static final Map<Integer, Long> NO_AXE_REPORT_SINCE = new HashMap<>();
    /** v1.1.0 实测六十九：发呆看门狗——最近一次"真实进展"时刻（挖掉/垫了方块）。长时间零进展
     *  且原地不动 = 发呆/死循环，自动整体重置该女仆的全部行为状态（等效收回魂符再放下去——
     *  收放正是靠换实体 ID 让这些以实体 ID 为键的表失效来治好卡死的）。 */
    private static final Map<Integer, Long> LAST_PROGRESS = new HashMap<>();
    /** 看门狗窗口采样（实体 ID → [窗口起点 tick, 起点 x/y/z 各自的 double 位模式]） */
    private static final Map<Integer, long[]> WATCH_SAMPLE = new HashMap<>();
    /** 看门狗重置播报限频（实体 ID → 上次播报 tick） */
    private static final Map<Integer, Long> RESET_REPORT_SINCE = new HashMap<>();

    /** v1.1.0 实测六十九：登记一次真实进展（挖掉/垫了方块）——给看门狗续命 */
    private static void markProgress(EntityMaid maid, long gameTime) {
        LAST_PROGRESS.put(maid.m_19879_(), gameTime);
    }
    /** v1.5.113：找矿结果缓存——全量扫描每 5 秒一次，期间只做廉价校验（A1 性能优化） */
    private static final Map<Integer, WoodCache> WOOD_CACHE = new HashMap<>();
    /** 缓存 TTL（tick，5 秒）——矿石静态不变，5 秒内只校验存在性即可 */
    private static final long WOOD_CACHE_TTL = 100L;

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
        NO_AXE_REPORT_SINCE.remove(maidEntityId);
        com.maidsmart.task.MaidPlanting.forget(maidEntityId); // 实测二百二十八：随手种树冷却表
        WOOD_CACHE.remove(maidEntityId);
        WOOD_SCANS.remove(maidEntityId); // 实测六十一：分帧扫描游标一并清
        LAST_PROGRESS.remove(maidEntityId); // 实测六十九：看门狗三表一并清
        WATCH_SAMPLE.remove(maidEntityId);
        RESET_REPORT_SINCE.remove(maidEntityId);
    }

    /** 审计 P-9：按 UUID 清理 WOODING 集合（int 表与 UUID 表分开清理） */
    public static void forgetUuid(java.util.UUID maidUuid) {
        WOODING.remove(maidUuid);
    }

    /** v1.5.113：找矿缓存条目——全量扫描得到的矿位置 + 各自挡路预算（供缓存轮复用） */
    private record WoodCache(long builtAt, java.util.List<BlockPos> ores,
                            java.util.Map<BlockPos, Integer> blocking) {
    }

    /**
     * v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：全量扫描的分帧游标状态。
     * 一次扫不完（WOOD_SCAN_BUDGET 格/tick），游标 (dy,dx,dz) 跨 tick 续进；锚点变了
     * 或状态太旧（>200 tick）自动作废重扫。按女仆实体 id 存（forget/purge 一并清）。
     */
    private static final class WoodScanState {
        final BlockPos anchor;
        final long startedAt;
        int dy;
        int dx;
        int dz;
        final java.util.List<BlockPos> found = new java.util.ArrayList<>();
        final java.util.Map<BlockPos, Integer> blockingCache = new java.util.HashMap<>();
        BlockPos best;
        double bestScore = Double.MAX_VALUE;

        WoodScanState(BlockPos anchor, long startedAt, int radius, int down) {
            this.anchor = anchor;
            this.startedAt = startedAt;
            this.dy = -down;
            this.dx = -radius;
            this.dz = -radius;
        }
    }

    private static final Map<Integer, WoodScanState> WOOD_SCANS = new HashMap<>();

    /** v1.5.87：正在挖矿的女仆（拾取任务据此让位——捡掉落物最低优先级） */
    private static final java.util.Set<java.util.UUID> WOODING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** v1.5.87：女仆是否正在挖矿（MaidPickupPriorityMixin 检查用） */
    public static boolean isWooding(EntityMaid maid) {
        return WOODING.contains(maid.m_20148_());
    }

    /** v1.5.87：该位置是否是女仆搭的方块（实测七十一改跨系统统一查询——伐木的
     *  扫描/连锁/挡路判定不再把搭路/自保/挖矿垫的方块当成木材目标或开路块） */
    public static boolean isWoodingPlaced(ServerLevel level, BlockPos pos) {
        return PlacedBlockTracker.isAnyPlaced(level, pos);
    }

    /** v1.5.87：搭方块防掉落窗口（搭块后 12 tick 内钳制，防刚搭完滑落） */
    private int pillarGuardTicks = 0;
    /** v1.5.47：刚弃置的目标（findWood 排除一次；挖掉任一矿后清除） */
    private BlockPos abandonedPos = null;
    /** v1.5.87：被硬挡路（箱子/机器等）弃置的矿——持续排除，不再反复选中报点 */
    private final java.util.Set<BlockPos> blockedWoods = new java.util.HashSet<>();
    /** v1.5.85：本次扫描中镐子挖不动的矿（记录价值最高的一个，用于播报） */
    private BlockPos skippedWoodPos = null;
    private String skippedWoodName = null;
    private String skippedWoodTool = null;
    private int skippedWoodValue = -1;

    private BlockPos targetPos = null;
    /** 当前目标累计破坏进度（0~1，对齐 maid_useful_task 渐进挖掘） */
    private float destroyProgress = 0.0f;
    /**
     * v1.5.161：连锁采集队列（借鉴 FTB Ultimine 连锁破坏思路）——挖完一块矿后，
     * 从刚挖的位置 BFS 找相连的同族矿石排队，挖完一块自动接下一块，直到队列空。
     * 默认关闭（配置 mine.chainMining）；队列只装"矿"（WOOD_VALUE 表内），不连锁石头。
     */
    private final java.util.ArrayDeque<BlockPos> chainQueue = new java.util.ArrayDeque<>();
    /** v1.5.161：当前连锁的矿方块类型（挖掉一块后记录，BFS 按同 Block 匹配） */
    private net.minecraft.world.level.block.Block chainBlock = null;
    /** v1.5.22：进度持久化 key（行为被抢占重启时不丢进度） */
    private static final String PROGRESS_TAG = "maid_smart_wood_progress";
    /** 目标扫描节流：找不到矿时每 20 tick 才扫一次，避免每 tick 全量查询 */
    private int scanCooldown = 0;
    /** v1.5.113：上一次 findWood 是否做了全量重建（缓存轮=false）——只有全量重建
     *  确认框空才滑动锚点迁移（缓存轮空不迁移，等 5 秒重建再判） */
    private boolean lastScanWasFull = true;
    /** v1.5.24：搭高节奏计数 */
    private int pillarCooldown = 0;
    /** v1.5.47：废石检查节流 */
    private int junkCooldown = 0;
    /** v1.1.0 实测九：身边树叶冲破节流（20 tick 一轮） */
    private int leafBurstCooldown = 0;
    /** v1.1.0 实测二百三十五：随手种树兜底调起（任务级驱动之外的二次保险，20 tick 一查，
     *  模块内部另有 5 秒冷却——双驱动同源、由冷却天然去重） */
    private int plantScanCooldown = 0;
    /** v1.5.105：走过去重设 WalkTarget 节流——每 tick 重设会让 TLM 每 tick 重寻路 → 移动顿挫 */
    private int walkRetargetCooldown = 0;
    /** v1.5.116：上次设置的移动目标站立点——目标没变且导航行进中不重设
     *  （旧版每 8 tick 无条件重设 WalkTarget → 导航每次重新寻路 → "一走一停"鬼畜） */
    private BlockPos lastWalkTarget = null;
    /** v1.5.25：本次自己搭过的位置（搭高/斜坡/搭桥）——10 秒后自动销毁，绝不挖自然地形。
     *  v1.5.28：改为【全局静态追踪器】——旧版挂在行为实例上，行为停止（挖完矿
     *  canContinue=false）后 expirePlacedBlocks 不再运行 → 搭的方块永久残留。
     *  现在放置即登记到全局表，由 ServerTickEvent 每 tick 统一清理，与行为生命周期无关。
     *  v1.5.102：清理时限从配置面板读取（mine.placedLifetime，秒→tick）
     *  v1.1.0 实测四十二：换 PlacedBlockTracker——绑定搭建女仆 + 魂符收回暂停计时 */

    /** 全局追踪器（绑定搭建者 + 魂符暂停计时；实测四十二） */
    static final PlacedBlockTracker PLACED_TRACKER = new PlacedBlockTracker(
            () -> com.maidsmart.config.MaidSmartConfig.WOOD_PLACED_LIFETIME.get() * 20L);

    /** v1.5.28：登记一个挖矿搭的方块（每块从自己放置时刻起单独计时，满 10 秒各自销毁） */
    private static void trackPlaced(ServerLevel level, BlockPos pos, Block block, EntityMaid maid) {
        PLACED_TRACKER.track(level, pos, block, maid);
        markProgress(maid, level.m_46467_()); // 实测六十九：垫方块也是进展
    }

    /** v1.5.28：销毁所有放置超过 10 秒的挖矿搭方块。
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker（绑定搭建者/魂符暂停）。 */
    public static void expirePlaced(net.minecraft.server.MinecraftServer server, long gameTime) {
        PLACED_TRACKER.expirePlaced(server, gameTime,
                pos -> anyMaidStanding(server, pos, m -> WOODING.contains(m.m_20148_())));
    }

    /** 任意维度的存活女仆站在该位置（跨维度判定；实测四十二） */
    private static boolean anyMaidStanding(net.minecraft.server.MinecraftServer server,
                                           BlockPos pos, java.util.function.Predicate<EntityMaid> filter) {
        for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
            if (PlacedBlockTracker.anyMaidStanding(lvl, pos, filter)) {
                return true;
            }
        }
        return false;
    }

    /** v1.5.28：服务器停止时清场——内存追踪器会随进程消失，残留方块立即销毁回收
     *  （不等待 10 秒；重进存档不会看到"永不消失"的搭方块）
     *  v1.1.0 实测四十二：改走 PlacedBlockTracker.clearAll */
    public static void clearAll(net.minecraft.server.MinecraftServer server) {
        PLACED_TRACKER.clearAll(server);
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
            ids.addAll(NO_AXE_REPORT_SINCE.keySet());
            ids.addAll(com.maidsmart.task.MaidPlanting.knownIds()); // v1.1.0 实测二百二十八：随手种树表
            ids.addAll(WOOD_CACHE.keySet());
            ids.addAll(LAST_PROGRESS.keySet()); // 实测六十九
            ids.addAll(WATCH_SAMPLE.keySet());
            ids.addAll(RESET_REPORT_SINCE.keySet());
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
        NO_AXE_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
        com.maidsmart.task.MaidPlanting.purgeStale(pid -> !alive.contains(pid));
            WOOD_CACHE.keySet().removeIf(id -> !alive.contains(id));
            WOOD_SCANS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十一
            LAST_PROGRESS.keySet().removeIf(id -> !alive.contains(id)); // 实测六十九
            WATCH_SAMPLE.keySet().removeIf(id -> !alive.contains(id));
            RESET_REPORT_SINCE.keySet().removeIf(id -> !alive.contains(id));
        } catch (Exception ignored) {
        }
    }

    /** 破坏一个追踪方块：v1.1.0 实测四十二后由 PlacedBlockTracker 内部处理，
     *  本方法保留签名兼容（实际已无调用者）。 */
    @SuppressWarnings("unused")
    private static void destroyMarked(ServerLevel level, BlockPos pos, String blockId) {
        BlockState state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return;
        }
        net.minecraft.resources.ResourceLocation cur = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        String curId = cur != null ? cur.toString() : "";
        if (!blockId.isEmpty() && !blockId.equals(curId)) {
            return; // 方块已被替换，不误破坏
        }
        level.m_46796_(2001, pos, Block.m_49956_(state));
        BlockEntity be = level.m_7702_(pos);
        if (com.maidsmart.config.MaidSmartConfig.WOOD_AUTO_COLLECT.get()) {
            EntityMaid miner = nearbyWooder(level, pos);
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
    private static EntityMaid nearbyWooder(ServerLevel level, BlockPos pos) {
        EntityMaid best = null;
        double bestD = Double.MAX_VALUE;
        for (EntityMaid m : level.m_45976_(EntityMaid.class,
                new net.minecraft.world.phys.AABB(pos).m_82400_(3.0))) {
            if (!m.m_6084_() || !WOODING.contains(m.m_20148_())) {
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

    public MaidWoodBehavior() {
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
                && ResourceLocation.parse("maid_smart:woodcut").equals(maid.getTask().getUid());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.164：诊断日志（确认女仆跑的是 maid_smart:mine 任务）
        LOGGER.info("wood behavior start: maid={}", maid.m_20148_());
        // v1.5.107：不再无条件装备最高级镐——目标矿已由 doTick 按需换镐；
        // 这里仅兜底"start 时 targetPos 为空重新找矿"的按需换镐
        if (this.targetPos == null) {
            BlockPos anchor = this.resolveAnchor(level, maid);
            if (anchor != null) {
                this.targetPos = this.findWood(level, maid, anchor);
                if (this.targetPos != null) {
                    MaidToolAutoEquip.ensureAxeForTarget(maid, level.m_8055_(this.targetPos));
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
        WOODING.add(maid.m_20148_());
    }

    /** v1.5.87：行为停止 → 退出"正在挖矿"（拾取任务恢复） */
    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        WOODING.remove(maid.m_20148_());
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
        // v1.1.0 实测六十九：发呆看门狗——长时间零进展且原地不动时整体重置状态。
        // 用户反馈：站坑发呆要收回魂符重放才恢复（重放换实体 ID 清空全部静态表）；
        // 现在行为自己周期性做同款复位，不再需要玩家手动救
        if (com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_WATCHDOG.get()
                && this.stuckReset(level, maid, gameTime)) {
            return; // 本 tick 已重置，下 tick 从头评估
        }
        // v1.5.109：移除 pullTowardTarget（setDeltaMovement 直接注入速度）——
        // 它与导航互相覆盖、搭高时把女仆从柱子上推走（"移速过快疯狂漂移"根因）。
        // 移动完全交给导航：目标够得着→挖；够不着→搭方块/走过去（approachWood）。
        // v1.5.111：珍稀矿物掉落物回收子系统整体移除（用户反馈：女仆挖矿途中被
        // 掉落物吸引、满场飞奔去捡，支柱永远建不起来）——挖出的掉落物就地由
        // pickupWoodDrops 收进背包；捡不到的留给拾取任务处理，不再打断挖矿流程。
        // v1.5.87：搭方块防掉落窗口——刚搭完方块钳制在格子中心（潜行效果，速度不变），
        // 防止重心滑出方块边缘从柱子上掉下去；v1.5.88 可配置（mine.pillarGuard）
        if (com.maidsmart.config.MaidSmartConfig.WOOD_PILLAR_GUARD.get() && this.pillarGuardTicks > 0
                && !BlockWalkOn.isActive(maid)) { // 实测一百九十二：步进推送期间不钳制（防按回上一块中心与推送互相抵消）
            this.pillarGuardTicks--;
            this.pillarGuard(level, maid);
        }
        // v1.5.28：搭方块 10 秒后统一销毁（全局表——行为停止后由 ServerTickEvent 兜底，
        // 此处保留双保险；不做任何即时破坏）
        expirePlaced(level.m_7654_(), gameTime);
        // v1.1.0 实测九（用户："伐木状态下直接冲破自己周围的树叶"）：每 20 tick
        // 把身边 3×3×3 立方体内的树叶直接摧毁（产生掉落物落地/自动收集）——
        // 走路被树冠卡住时不再绕路或站桩等，直接穿过去；掉落物与树冠清理同款
        // 进背包口径。原版女仆在树冠里会被树叶挤压减速绕圈，这是"砍树不痛快"
        // 的观感来源之一。开关跟随树冠清理（wood.leavesClear）——不想让她清叶
        // 就一起关。
        if (com.maidsmart.config.MaidSmartConfig.WOOD_LEAVES_CLEAR.get()
                && --this.leafBurstCooldown <= 0) {
            this.leafBurstCooldown = 20;
            this.burstNearbyLeaves(level, maid);
        }
        // v1.1.0 实测二百三十五【兜底调起】：任务级驱动（MaidPlanting 自监听 ServerTick）
        // 之外的二次保险——行为每 20 tick 再调一次；模块内部 5 秒冷却天然去重，双驱动
        // 不会重复种。旧版只靠这个钩子时因注册时机问题可能不生效，现在两条腿走路。
        if (--this.plantScanCooldown <= 0) {
            this.plantScanCooldown = 20;
            com.maidsmart.task.MaidPlanting.tick(level, maid);
        }
        // v1.5.47：废石丢弃（每 100 tick 一次；保留 JUNK_KEEP 份，超出销毁）
        if (--this.junkCooldown <= 0) {
            this.junkCooldown = com.maidsmart.config.MaidSmartConfig.WOOD_JUNK_CHECK_INTERVAL.get();
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
                if (chained != null && this.isWood(level, chained)) {
                    this.targetPos = chained;
                    TARGET_SINCE.put(maid.m_19879_(), gameTime);
                    WOODING.add(maid.m_20148_());
                }
            }
            if (this.targetPos == null) {
                // v1.5.113（A1）：扫描节流——每 20 tick 一次廉价校验（全量重建由
                // findWood 内部按 5 秒 TTL 控制，旧版每 3 tick 全量扫 8.9 万格）
                // v1.5.124：20 → 10 tick——挖完一块后找下一块矿更快（缓存轮只校验
                // 已记录矿的位置，廉价），"原地愣几秒"减少一半
                // v1.5.127：10 → 5 tick——缓存轮空时 findWood 已改为立即全量重建，
                // 此节流只控制"挖完后重新评估"的频率，再减半让块间衔接更紧凑
                // v1.1.0 实测六十五（自查修复）：分帧扫描进行中【不受节流、每 tick 推进】
                // ——节流在扫描期会把实际吞吐压到预算的 1/5（默认预算下扫完一框从
                // 0.85 秒变成 20 秒，女仆干等）；节流只该管"发起新一轮扫描"
                if (!WOOD_SCANS.containsKey(maid.m_19879_()) && this.scanCooldown-- > 0) {
                    return;
                }
                this.scanCooldown = 5;
                this.targetPos = this.findWood(level, maid, anchor);
                if (this.targetPos == null && WOOD_SCANS.containsKey(maid.m_19879_())) {
                    // v1.1.0 实测六十二（自查修复）：分帧扫描进行中——只等扫描，不做
                    // 抬头兜底/迁移/播报（低扫描预算下单轮扫描超过 5 秒时，旧逻辑会把
                    // 锚点滑走 → 游标状态因锚点变化作废 → 重扫再滑 → 永远扫不完）
                    return;
                }
                if (this.targetPos == null) {
                // v1.1.0 实测五十四（用户："站到挖掉的树干位置发呆，对头顶较高的木头
                // 视而不见"）：扫描框/排除表都给不出目标时，先【抬头看一眼】——脚下
                // 正上方同柱还有木材就直接当目标（树洞场景最终兜底：挖掉树干下几节
                // 站进洞里后，剩余树干就在头顶，不该对着它发呆）。刚弃置 30 秒内的
                // 头顶木材仍跳过（没垫脚材料时让她照常迁移换树，不原地吊死）
                BlockPos aboveIdle = this.firstWoodAbove(level, maid);
                java.util.Map<BlockPos, Long> discIdle = RECENT_DISCARD.get(maid.m_19879_());
                if (aboveIdle != null
                        && (discIdle == null || !discIdle.containsKey(aboveIdle.m_7949_()))) {
                    this.targetPos = aboveIdle;
                    TARGET_SINCE.put(maid.m_19879_(), gameTime);
                    WOODING.add(maid.m_20148_());
                    return;
                }
                // v1.5.140：挖矿空闲（附近无矿）→ 退出"挖矿中"标记，拾取任务恢复正常
                //（用户反馈：空闲时捡东西积极性太低；空闲 = 与其他工作任务的空闲一致）
                WOODING.remove(maid.m_20148_());
                // v1.5.85：框内有矿但镐子挖不动 → 不迁移（换镐还是挖不动）：
                // 气泡+主人聊天栏播报一次（限频）等玩家换镐
                if (this.skippedWoodPos != null) {
                    this.reportSkippedWood(maid, gameTime);
                    return;
                }
                // v1.5.113：只有【全量重建】确认本框没矿才迁移——缓存轮空不迁移
                // （等 5 秒重建再判，防锚点高速滑动）
                if (this.lastScanWasFull) {
                    // v1.5.102d：此前有被硬物挡住的矿 → 上报一次挡路原因
                    if (!this.blockedWoods.isEmpty()) {
                        this.reportBlockedArea(maid, level);
                    }
                    // v1.5.113（B1/B2）：框内挖空 → 锚点朝主人方向滑动一个环宽
                    // （不走路、不原地重埋——旧版"朝主人走走出旧框"被墙堵住时
                    // 原地重埋与旧框重合 → 死循环；纯走路又零产出）
                    this.slideAnchor(level, maid, anchor);
                }
                return;
            }
            // v1.5.107：找到矿 → 按需换镐（手中够用不换；不够换背包能挖的）
            // v1.1.0 终审三：木材无挖掘等级——ensureAxeForTarget 只在"手+背包
            // 都没有对应斧"时返回 false（模组带等级木材），不影响普通流程
            MaidToolAutoEquip.ensureAxeForTarget(maid, level.m_8055_(this.targetPos));
            TARGET_SINCE.put(maid.m_19879_(), gameTime);
            // v1.5.140：有目标 = 挖矿进行中 → 登记标记（拾取任务让位，与 doStart 一致）
            WOODING.add(maid.m_20148_());
            }
        }
        // v1.5.47：弃置检查——目标 5 秒够不着且无任何破坏进度 → 弃置重选（防原地磨蹭）
        // v1.5.113（B4）：弃置矿进入 30 秒短时排除（RECENT_DISCARD），不再反复选中；
        // 框内只剩这块够不着的矿时也不会来回折腾
        Long since = TARGET_SINCE.get(maid.m_19879_());
        if (since != null && gameTime - since >= com.maidsmart.config.MaidSmartConfig.WOOD_TARGET_TIMEOUT.get()
                && this.destroyProgress <= 0.0f
                && distSqToBlock(maid, this.targetPos) > reachSq()) {
            this.abandonedPos = this.targetPos;
            RECENT_DISCARD.computeIfAbsent(maid.m_19879_(), k -> new java.util.HashMap<>())
                    .put(this.targetPos.m_7949_(), gameTime);
            markProgress(maid, gameTime); // 实测七十四：弃置也是前进（防看门狗误伤长舞蹈）
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            this.saveProgressNow(maid);
            return;
        }
        // v1.5.47：目标失效检查——矿 或 开路废石（穿透挖掘的临时目标）
        if (!isWood(level, this.targetPos) && !this.isOpenStone(level, this.targetPos)) {
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            return;
        }
        // v1.1.0 终审三：旧版此分支对木材做"主手斧挖不动→换斧→换不到弃目标"检查
        // ——但木材没有挖掘等级（空手也能挖），canHarvest 对空手恒 false，空手时会
        // 误弃目标。整段移除：木材目标的工具充分性交给扫描层（canHarvestWoodOrBareHand）
        // 与挖掘入口（ensureAnyAxe + 慢速分支），这里不再拦。
        // v1.5.90：挡路块优先判定——不必等够到矿本身（"非矿物挡路只报不挖"根因）。
        // 旧版只有"矿在伸手范围内"才检查挡路：矿被厚土墙/岩壁隔着时女仆永远够不着
        // 矿 → 走到墙边站着不动（或 15 秒超时弃置），绝不挖开挡路方块。
        // 现在只要"挡路块"在伸手范围内就开挖，挖穿一层再评估下一层，逐层推进到矿。
        Blocker blocker = null;
        if (isWood(level, this.targetPos)) {
            // v1.0.4：关闭透视时，已锁定目标也不隔墙挖——从当前眼睛位置看不到该矿
            // 立即放弃。否则 findBlockingBlock 会把它当挡路块：墙在障碍物名单里就
            // 挖穿（=女仆再次获得透视），不在名单里才报点弃置（"勾选障碍物后透视
            // 失效"根因）。视线被挡的矿本就不该被发现/继续挖；正在挖的挡路块不在此
            // 检查范围，当前块挖完后 targetPos 置空，下 tick 重选时自然被视线过滤。
            if (!com.maidsmart.config.MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get()
                    && !this.hasClearSight(level, maid, this.targetPos)) {
                // v1.1.0 实测六十九：视线被挡的目标进 30 秒短排——旧版只丢目标不记录，
                // 扫描层几 tick 后又选中同一块，"选中→看不见→丢弃"无限循环站桩发呆
                RECENT_DISCARD.computeIfAbsent(maid.m_19879_(), k -> new java.util.HashMap<>())
                        .put(this.targetPos.m_7949_(), level.m_46467_());
                markProgress(maid, gameTime); // 实测七十四：弃置也是前进（防看门狗误伤长舞蹈）
                this.targetPos = null;
                this.destroyProgress = 0.0f;
                this.saveProgress(maid);
                return;
            }
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
                && com.maidsmart.config.MaidSmartConfig.WOOD_HARD_BLOCK_REPORT.get()) {
            // v1.5.87：硬挡路——报点（气泡+主人聊天栏）+ 弃置该矿（持续排除，不再反复选中）
            this.blockedWoods.add(this.targetPos);
            this.reportBlockedOre(maid, level, this.targetPos, blocker.pos());
            this.targetPos = null;
            this.destroyProgress = 0.0f;
            this.saveProgress(maid);
            return;
        }
        double distSq = distSqToBlock(maid, this.targetPos);
        if (distSq > reachSq()) {
            // v1.5.25：够不着（超过玩家手长 4.5 格）→ 三选一搭路决策
            //（向上搭高 / 向前搭斜坡 / 搭桥+走过去），每 tick 重算直到够得着
            this.approachWood(level, maid);
            return;
        }
          ItemStack mainHand = maid.m_21205_();
          if (mainHand.m_41619_() || !(mainHand.m_41720_() instanceof AxeItem)) {
              // v1.1.0 终审三（用户：木材空手也能挖，不因空手拒绝工作）：
              // 优先尝试从背包装备斧（背包有斧就换——与矿镐"空手才装备"同机制，
              // 玩家手中放的斧在非空手分支永远不动）；背包也没斧 → 空手照样开挖
              // （下 tick 走挖掘公式慢速分支，与玩家空手砍树一致），不再弃目标。
              MaidToolAutoEquip.ensureAnyAxe(maid);
              if (maid.m_21205_().m_41619_()
                      || !(maid.m_21205_().m_41720_() instanceof AxeItem)) {
                  this.notifyNoAxe(maid); // 没斧播报（限频）——然后继续空手开挖
                  // v1.1.0 实测十六修复：旧版这里 return → 没斧的女仆每 tick 走到
                  // 树前 return，永远到不了下面的挖掘公式，"空手照砍"承诺失效
                  //（表现为锁树后站在树旁永久发呆；notifyNoAxe 也成了死代码）
              } else {
                  return; // 换斧成功：等下一 tick 用新斧开始挖
              }
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
                            * (float) (double) com.maidsmart.config.MaidSmartConfig.WOOD_SPEED_FACTOR.get();
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
        if (com.maidsmart.config.MaidSmartConfig.WOOD_AUTO_COLLECT.get()) {
            java.util.List<ItemStack> drops = Block.m_49874_(state, level, this.targetPos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, this.targetPos);
            // v1.5.164：诊断日志（排查"自动收集没生效"）
            LOGGER.info("wood autoCollect: maid={} pos={} drops={}",
                    maid.m_20148_(), this.targetPos, drops == null ? 0 : drops.size());
        } else {
            // v1.5.85：掉落物+经验球走完整 dropResources（六参版）——内部做精准采集检查，
            // 精准采集镐挖矿不掉经验球，与原版玩家完全一致（旧 4 参版只掉物品不掉经验）
            Block.m_49881_(state, level, this.targetPos, be, maid, mainHand);
            // v1.5.87：挖出的矿石掉落物即时回收（原地拾取，不走"走过去捡"）——
            // 拾取任务在挖矿中已让位，这里直接把刚挖出的掉落物收进背包，不掉地上
            this.pickupWoodDrops(level, maid);
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
        boolean softNoDur = com.maidsmart.config.MaidSmartConfig.WOOD_SOFT_NO_DURABILITY.get();
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
        this.blockedWoods.clear();
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
        markProgress(maid, gameTime); // 实测六十九：喂看门狗（真实进展）
        // v1.1.0 实测三十（用户："树冠清理不自然——直接整个树冠一下子清掉；
        // 我要的只是以女仆为中心 3×3×3 范围内的树叶被破坏，带音效和特效"）：
        // 删除 clearTreeTop 整冠 BFS 清除（一次性清掉整棵树的树叶不自然）。
        // 树叶清理完全交给 burstNearbyLeaves——它本来就在行为激活期间每 20 tick
        // 以女仆为中心清 3×3×3，女仆走过去自然会"经过"整片树冠逐层清完。
        // （实测九已有的机制，本次让它成为唯一的树叶清理路径）
        // v1.1.0 实测二百二十八（用户："种树逻辑直接分开来就好——与伐木不相关"）：
        // 伐木内部的补种已移除——「随手种树」是独立行为（MaidPlantSaplingBehavior，
        // 手里有苗+附近有可种土块就种，什么都不干时也种）；本行为回归纯砍树/清叶。
        // 树叶掉落的树苗仍直接进背包（自动收集开关之外的单品规则），供随手种树取用。
        this.targetPos = null;
        this.destroyProgress = 0.0f;
        this.saveProgressNow(maid);
    }

    /**
     * v1.5.172：连锁采集【同时破坏】——把 refillChainQueue 填好的队列（相连同族矿）
     * 一次性全部破坏：掉落物直接进女仆背包（放不下落地，与单块自动收集一致），
     * 音效/粒子只在目标矿播（连锁块静默，防 16 连爆音刷屏），镐耐久只扣目标矿一次。
     * 破坏后队列自然清空（下一 tick 取队列时 isWood 校验失败被 poll 掉，不残留）。
     */
    private void chainBreakAll(ServerLevel level, EntityMaid maid, ItemStack mainHand) {
        if (!com.maidsmart.config.MaidSmartConfig.WOOD_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            return;
        }
        int broken = 0;
        int limit = com.maidsmart.config.MaidSmartConfig.WOOD_CHAIN_LIMIT.get();
        while (!this.chainQueue.isEmpty() && broken < limit) {
            BlockPos pos = this.chainQueue.poll();
            if (pos == null || pos.equals(this.targetPos)) {
                continue;
            }
            BlockState st = level.m_8055_(pos);
            if (st.m_60795_() || st.m_60734_() != this.chainBlock || !this.isWood(level, pos)) {
                continue; // 已被挖掉/类型不符（队列里的过期矿位）
            }
            if (isWoodingPlaced(level, pos)) {
                continue; // 自己搭的方块不连锁
            }
            BlockEntity be = level.m_7702_(pos);
            java.util.List<ItemStack> drops = Block.m_49874_(st, level, pos, be, maid, mainHand);
            insertIntoMaidInventory(maid, level, drops, pos);
            level.m_7731_(pos, Blocks.f_50016_.m_49966_(), 3);
            broken++;
        }
        if (broken > 0) {
            LOGGER.info("wood chain burst: maid={} block={} broken={}",
                    maid.m_20148_(), ForgeRegistries.BLOCKS.getKey(this.chainBlock), broken);
        }
    }

    /**
     * v1.1.0 实测四：树冠清理——从刚砍的树干位置向上 BFS 清树叶。
     * v1.1.0 实测三十：【已删除】——用户反馈"整个树冠一下子清掉不自然"，要的只是
     * 以女仆为中心 3×3×3 的树叶破坏（burstNearbyLeaves 已有）。方法保留为空壳
     * 防外部调用残留（实际无外部调用者，纯保险）。
     */
    @SuppressWarnings("unused")
    private void clearTreeTop(ServerLevel level, EntityMaid maid, ItemStack mainHand) {
        // v1.1.0 实测三十：整体树冠 BFS 清除已删除——见调用点注释
    }

    /**
     * v1.1.0 实测二百二十八：伐木内部的补种已移除——种树逻辑拆成独立模块
     * （MaidPlanting，自己的 5 秒冷却）仍由本行为调起（触发 = 伐木模式），
     * 见 tick 内 saplingScanCooldown 调起点。
     */

    /** 实测二百二十六：是否为树苗物品（ItemNameBlockItem 且方块带 #minecraft:saplings——
     *  原版全部树苗 + 模组树苗自动兼容；树叶掉落分类用）。 */
    private static boolean isSaplingItem(ItemStack stack) {
        try {
            if (stack.m_41619_() || !(stack.m_41720_() instanceof net.minecraft.world.item.ItemNameBlockItem)) {
                return false;
            }
            Block b = ((net.minecraft.world.item.ItemNameBlockItem) stack.m_41720_()).m_40614_();
            return b != null && b.m_49966_().m_204336_(SAPLINGS_TAG);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * v1.1.0 实测九：冲破身边树叶——以女仆为中心的 3×3×3 立方体内所有树叶
     * 直接摧毁。掉落物走 getDrops（树苗/苹果/木棍按概率），autoCollect 开着
     * 直接进背包，否则落地（走原版掉落，拾取任务后续处理）。只清树叶——
     * 立方体内的原木不碰（那是正常挖掘目标，走挖掘流程有工具加成）。
     * v1.1.0 实测三十（用户："每块树叶要有破坏音效和破坏特效"）：每块树叶
     * 破坏时各自播 levelEvent 2001（同玩家挖方块——音效+粒子一起），不再
     * 只在中心播一次（旧版静默清除观感突兀）。树冠清理（clearTreeTop）删除后
     * 本方法是唯一的树叶清理路径：女仆走动经过树冠就逐层清 3×3×3。
     */
    private void burstNearbyLeaves(ServerLevel level, EntityMaid maid) {
        try {
            BlockPos center = maid.m_20183_();
            int broken = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = center.m_7918_(dx, dy, dz);
                        BlockState st = level.m_8055_(p);
                        if (!(st.m_60734_() instanceof net.minecraft.world.level.block.LeavesBlock)) {
                            continue;
                        }
                        // 玩家同款破坏音效+粒子（levelEvent 2001 逐块播）
                        level.m_46796_(2001, p, Block.m_49956_(st));
                        BlockEntity be = level.m_7702_(p);
                        java.util.List<ItemStack> drops = Block.m_49874_(st, level, p, be, maid, ItemStack.f_41583_);
                        if (com.maidsmart.config.MaidSmartConfig.WOOD_AUTO_COLLECT.get()) {
                            insertIntoMaidInventory(maid, level, drops, p);
                        } else {
                            // v1.1.0 实测二百二十六（用户："女仆并不会种植树苗"）：树苗
                            // 永远直进背包（其余掉落物遵循自动收集开关）——树苗是"砍树→
                            // 掉苗→补种"闭环的种子，落地后伐木中拾取任务让位、大概率捡
                            // 不到（日志实证：每次补种都 skip=no-sapling-in-inventory）
                            for (ItemStack s : drops) {
                                if (isSaplingItem(s)) {
                                    insertIntoMaidInventory(maid, level, java.util.List.of(s), p);
                                } else {
                                    Block.m_49840_(level, p, s);
                                }
                            }
                        }
                        level.m_7731_(p, Blocks.f_50016_.m_49966_(), 3);
                        broken++;
                    }
                }
            }
            if (broken > 0) {
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 摆臂动画
                LOGGER.info("wood leaves burst: maid={} broken={}", maid.m_20148_(), broken);
                markProgress(maid, level.m_46467_()); // 实测六十九：清树叶也算活动
            }
        } catch (Exception ignored) {
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

    /** v1.1.0 实测六十九：窗口起点采样 */
    private static long[] watchSample(long gameTime, EntityMaid maid) {
        return new long[]{gameTime,
                Double.doubleToLongBits(maid.m_20185_()),
                Double.doubleToLongBits(maid.m_20186_()),
                Double.doubleToLongBits(maid.m_20189_())};
    }

    /**
     * v1.1.0 实测六十九：发呆看门狗判定。每 tick 廉价检查；窗口到期（默认 30 秒）结算：
     * 期间【挪动过 ≥1.5 格】或【有过真实进展（挖掉/垫方块）】= 正常工作，续窗；否则判发呆，
     * 整体重置该女仆在本行为里的全部状态并返回 true。走路赶路位置一直在变，不会误触发。
     */
    private boolean stuckReset(ServerLevel level, EntityMaid maid, long gameTime) {
        int id = maid.m_19879_();
        long window = com.maidsmart.config.MaidSmartConfig.WOOD_STUCK_RESET_SECONDS.get() * 20L;
        if (window <= 0) {
            return false;
        }
        long[] sample = WATCH_SAMPLE.get(id);
        if (sample == null) {
            WATCH_SAMPLE.put(id, watchSample(gameTime, maid));
            LAST_PROGRESS.putIfAbsent(id, gameTime);
            return false;
        }
        if (gameTime - sample[0] < window) {
            return false; // 窗口未到期
        }
        double moved = Math.sqrt(
                Math.pow(maid.m_20185_() - Double.longBitsToDouble(sample[1]), 2)
                        + Math.pow(maid.m_20186_() - Double.longBitsToDouble(sample[2]), 2)
                        + Math.pow(maid.m_20189_() - Double.longBitsToDouble(sample[3]), 2));
        Long lastProg = LAST_PROGRESS.get(id);
        if (moved >= 1.5 || (lastProg != null && lastProg >= sample[0])) {
            WATCH_SAMPLE.put(id, watchSample(gameTime, maid)); // 有在干活：续窗
            return false;
        }
        this.hardResetStuck(level, maid, gameTime);
        return true;
    }

    /**
     * v1.1.0 实测六十九：发呆整体重置——forget 清空本女仆的全部静态表（锚点/缓存/
     * 扫描游标/弃置排除/各类限频），实例字段（目标/进度/连锁队列/挡路名单/走路记忆等）
     * 一并归零，等效收回魂符再放下去；气泡播报一次（60 秒限频）。
     */
    private void hardResetStuck(ServerLevel level, EntityMaid maid, long gameTime) {
        int id = maid.m_19879_();
        long idleTicks = gameTime - LAST_PROGRESS.getOrDefault(id, gameTime);
        // 实测七十四：保留 30 秒短排表——那是"学到的不可达知识"（600 tick 自过期）。
        // 旧版连它一起清：够不着目标的长舞蹈（每个候选 15 秒超时）会被看门狗反复
        // 打断归零，形成"每 30 秒重置一次"的新死循环
        java.util.Map<BlockPos, Long> keptDiscard = RECENT_DISCARD.get(id);
        BlockPos keptTarget = this.targetPos;
        Long keptSince = TARGET_SINCE.get(id);
        forget(id);
        if (keptDiscard != null && !keptDiscard.isEmpty()) {
            RECENT_DISCARD.put(id, keptDiscard);
        }
        // 实测七十五：当前目标与它的超时等待时钟一并保留——看门狗窗口（默认已降到
        // 8 秒）小于目标超时（15 秒），若重置把计时清零，够不着的目标永远等不到弃置、
        // 舞蹈永远走不完；恢复后失效检查照常逐 tick 复核该目标，坏了自然会被丢掉
        if (keptTarget != null && keptSince != null) {
            this.targetPos = keptTarget;
            TARGET_SINCE.put(id, keptSince);
        } else {
            this.targetPos = null;
        }
        this.destroyProgress = 0.0f;
        this.saveProgressNow(maid);
        this.abandonedPos = null;
        this.blockedWoods.clear();
        this.skippedWoodPos = null;
        this.skippedWoodName = null;
        this.skippedWoodTool = null;
        this.skippedWoodValue = -1;
        this.chainQueue.clear();
        this.chainBlock = null;
        this.lastWalkTarget = null;
        this.scanCooldown = 0;
        this.pillarCooldown = 0;
        this.walkRetargetCooldown = 0;
        if (this.lastCrackPos != null) {
            this.broadcastCrack(level, maid, this.lastCrackPos, 10); // 清残留挖掘裂纹
        }
        this.lastCrackPos = null;
        this.lastCrackStage = -1;
        LAST_PROGRESS.put(id, gameTime);
        WATCH_SAMPLE.put(id, watchSample(gameTime, maid));
        LOGGER.info("wood stuck-reset: maid={} idle={}t pos={}", id, idleTicks, maid.m_20183_());
        Long lastReport = RESET_REPORT_SINCE.get(id);
        if (lastReport == null || gameTime - lastReport >= 1200L) {
            RESET_REPORT_SINCE.put(id, gameTime);
            maid.getChatBubbleManager().addTextChatBubble("好像走神了……我重新理一下思路，继续干活！");
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
    private void approachWood(ServerLevel level, EntityMaid maid) {
        // v1.1.0 实测一百九十二【垫块后走上去】——步进推送消费本 tick（不导航不垫块）；
        // 旧版垫完靠 walkToStep 寻路：跨沟/断崖半路折断 → 女仆踩不上刚垫的方块，
        // 在某几格上死循环（用户："运动的幅度真的太小了"）
        if (BlockWalkOn.tick(maid)) {
            return;
        }
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
            // v1.1.0 实测四十七：垫不上去（典型场景：女仆挖掉树干下 3 节后站进
            // 树洞，头顶就是树干本体——pillarUpStep 的防窒息检查拒绝在树洞里垫块
            // → 旧版就此卡死：不垫、不走、也不挖头顶的木头，"站树洞里发呆"根因）。
            // 修复：把目标改为头顶正上方第一块木材（下一 tick 走正常挖掘入口——
            // 伸手范围内直接挖掉，逐节往上啃完整棵树，不需要垫方块）
            // v1.1.0 实测五十四：目标已经是头顶木材（above == t，垫块冷却中/没材料
            // /防窒息暂不可垫）→ 原地等待重试，【不再跌进 walkToWoodBase】——头顶
            // 目标的"站立点"全是导航去不了的空中格（findStandNearWood 兜底已删，
            // 但 walkTargetFor 仍可能选出树冠里的悬空格），走到垃圾目标 = 发呆。
            // 等待期间 15 秒目标超时照常生效（够不着且零进度 → 弃置换树）
            // v1.1.0 实测二百二十六：水平偏开时的"原地等"修正——旧版 above == t 无
            // 条件等待：女仆站在树干旁 1~2 格时"等"= 站到超时/看门狗重置循环。
            // 现在她水平偏开 >1 格就先走到【树干柱底最近可站格】（她已挖穿的树洞
            // 底部或柱下地面——可站格沿柱向下找，第一个空气且脚下有支撑的格），
            // 走位到正下方后挖掘入口自然接管（正头上方木材伸手可及）。
            BlockPos above = this.firstWoodAbove(level, maid);
            if (above != null) {
                if (!above.equals(t)) {
                    this.targetPos = above;
                    TARGET_SINCE.put(maid.m_19879_(), level.m_46467_());
                    return;
                }
                double hx2 = t.m_123341_() + 0.5 - maid.m_20185_();
                double hz2 = t.m_123343_() + 0.5 - maid.m_20189_();
                if (Math.sqrt(hx2 * hx2 + hz2 * hz2) > 1.0) {
                    BlockPos stand = null;
                    for (int dy2 = -1; dy2 >= -10; dy2--) {
                        BlockPos c = t.m_7918_(0, dy2, 0);
                        if (level.m_8055_(c).m_60795_()) {
                            BlockPos uc = c.m_7918_(0, -1, 0);
                            if (!level.m_8055_(uc).m_60795_()
                                    && level.m_8055_(uc).m_60796_(level, uc)) {
                                stand = c;
                            }
                            break;
                        }
                    }
                    if (stand == null) {
                        this.walkToWoodBase(level, maid, t);
                    } else {
                        this.setWalkTarget(maid, stand, approachSpeed(maid, t));
                    }
                }
                return;
            }
        }
        // 2) 斜上方矿：向前垫台阶（斜坡），水平+垂直同时逼近
        // v1.5.113：垫完【走一步到刚垫的台阶上】——旧版垫完只 walkToWoodBase
        // （目的地可能隔着未垫完的沟，导航找不到路 → 站着不动）；改走一步再评估
        // v1.1.0 实测三（用户："伐木不像挖矿会四处走，喜欢站原地"）：斜坡/搭桥的
        // 距离门槛从 4.5 格放宽到 8 格——树是竖直目标，树干下半部常在 4.5~8 格处，
        // 旧版这个距离段"不垫也不走"（walkToWoodBase 的站立点在树底下、贴着就停）
        // → 女仆在树旁站桩干等；放宽后中距离持续垫台阶/铺桥逼近（挖矿同款行为）
        if (dy >= 1 && hDist > 2.5 && hDist <= 8.0) {
            if (this.slopeStep(level, maid, hx, hz, hDist)) {
                this.startStepOn(maid, hx, hz, hDist);
                return;
            }
        }
        // 3) 其他：目标方向前方脚下悬空 → 搭桥；否则走过去
        // v1.5.113：搭桥后【走一步上桥】——旧版搭完直接 return（无移动目标）
        // → 女仆站在桥头"搭一格就站着不动"根因
        if (this.bridgeToWood(level, maid, hx, hz, hDist)) {
            this.startStepOn(maid, hx, hz, hDist);
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
            this.walkToWoodBase(level, maid, t);
        }
    }

    /**
     * v1.1.0 实测四十七：找女仆头顶上方（同列）最近的木材块（最多 10 格）。
     * 用于"站树洞里头顶就是树干"场景——pillarUpStep 防窒息拒绝垫块时，
     * 改挖头顶树干逐节往上啃，不再站桩发呆。
     * v1.1.0 实测一百一十九：起始 dy 2 → 1——树基的木头被砍掉后女仆站进树洞，
     * 下一根木头就在她【头平齐】的 dy=1 处（不是 2），旧版从 dy=2 起跳会漏掉
     * 它：扫描/链式都给不出目标时对着近在咫尺的头顶木头发呆。
     * v1.1.0 实测二百二十六（用户："对树上比自己高几格的木头站在木头下一动不动"）：
     * 旧版只搜【女仆自己所在列】——站在树干旁 1~2 格（走位/寻路落点差一格、从树冠
     * 上滑落）时头顶列是空气/树叶，找不到木材 → 搭高失败后跌回 walkToWoodBase
     * （最近可站格恰是她脚下）→ 原地站到 15 秒超时/看门狗重置 → 反复循环。
     * 改为【5×5 列簇】：离女仆最近的列（|dx|+|dz| 环序）优先、列内 dy 从 1 起——
     * 覆盖"站在树干旁一两格"的全部场景；找到后设为目标即可正常挖（无需垫块）。
     */
    private BlockPos firstWoodAbove(ServerLevel level, EntityMaid maid) {
        BlockPos feet = maid.m_20183_();
        int[][] colOrder = {{0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {2, 1}, {1, 2}, {-1, 2}, {-2, 1}, {-2, -1}, {-1, -2}, {1, -2}, {2, -1},
                {2, 2}, {-2, 2}, {-2, -2}, {2, -2}};
        for (int[] off : colOrder) {
            for (int dy = 1; dy <= 10; dy++) {
                BlockPos p = feet.m_7918_(off[0], dy, off[1]);
                // v1.1.0 实测六十九：被硬挡路弃置的不回选（否则「抬头选中→硬挡弃置→再抬头」
                // 死循环，站在树洞里永远出不来）
                if (this.isWood(level, p) && !isWoodingPlaced(level, p)
                        && !this.blockedWoods.contains(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * v1.5.116：设置移动目标——目标没变 且 导航仍在行进 → 不重设。
     * 导航每次收新目标都要停下重新寻路（PathNavigation.recomputePath），
     * 旧版 walkToWoodBase 每 8 tick 无条件重设 → 女仆"走一下顿一下"鬼畜。
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
    private BlockPos walkTargetFor(ServerLevel level, EntityMaid maid, BlockPos t) {
        BlockPos stand = findStandNearWood(level, maid, t);
        return stand;
    }

    private void walkToWoodBase(ServerLevel level, EntityMaid maid, BlockPos t) {
        BlockPos stand = walkTargetFor(level, maid, t);
        if (stand == null) {
            // v1.1.0 实测三：树被完全包住找不到站立点 → 直接朝树干坐标走
            // （旧版"不设移动目标"= 站桩干等的另一来源；走过去后挡路挖掘/搭高接管）
            stand = t;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, t));
    }

    /**
     * v1.5.113（B5）：找矿附近最近的【可站立格】——候选 = 矿 6 方向各 1 格 + 矿下方 2 格，
     * 要求：候选格可呼吸（空气）、其脚下（候选-1）实心可站、且离女仆最近（走过去最近）。
     * 找不到（矿被完全包住）返回 null（挡路挖掘接管）。
     */
    private BlockPos findStandNearWood(ServerLevel level, EntityMaid maid, BlockPos t) {
        // v1.1.0 实测三：偏移量扩到 2 格——树干是竖直列，1 格圈常整圈被树叶/枝干
        // 占满找不到站立点（返回 null → 不设移动目标 → 站桩干等）；2 格圈在树冠
        // 外围，总能找到可站格，女仆保持围着树转着砍的"四处走"观感
        int[][] offsets = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -2, 0},
                {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2}, {2, -1, 0}, {-2, -1, 0}, {0, -1, 2}, {0, -1, -2}};
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
        // v1.1.0 实测五十四：删除"目标正上方向上找最近空气格"兜底——伐木目标是
        // 竖直树干，扩展圈全部无立足点时，树干上方的空气格是【导航去不了的空中格】
        // （悬空、无支撑），设为移动目标后寻路必失败 → 女仆站坑发呆（站树洞里
        // 盯着头顶树干发呆的组成环节之一）。改返回 null：walkToWoodBase 对 null
        // 的既有处理是直接朝树干坐标走——走到树跟前，够得着就挖、够不着搭高接管。
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

    /** v1.1.0 实测一百九十二：垫台阶/桥块后登记"走上去"目标（BlockWalkOn 持续推送，
     *  踏入即停）——取代寻路版 walkToStep（跨沟/断崖寻路半路折断 = 死循环根因） */
    private void startStepOn(EntityMaid maid, double hx, double hz, double hDist) {
        int y = maid.m_20183_().m_123342_();
        int tx = (int) Math.floor(maid.m_20185_() + hx / hDist);
        int tz = (int) Math.floor(maid.m_20189_() + hz / hDist);
        BlockWalkOn.start(maid, tx + 0.5, y, tz + 0.5);
    }

    /**
     * v1.5.113（C1）：两档接近速度——矿远（>8 格）1.5× 快走赶路，矿近（≤3 格）
     * 0.8× 慢走（准备搭高/挖掘，防冲过头漂移）；中间距离用基础速度（wood.moveSpeed）。
     * v1.1.0 实测四十八：基础速度 0.6→0.3（用户反馈伐木移速过快像狂奔）。
     */
    private float approachSpeed(EntityMaid maid, BlockPos t) {
        if (t == null) {
            return (float) (double) com.maidsmart.config.MaidSmartConfig.WOOD_MOVE_SPEED.get();
        }
        double d = maid.m_20275_(t.m_123341_() + 0.5, t.m_123342_() + 0.5, t.m_123343_() + 0.5);
        float base = (float) (double) com.maidsmart.config.MaidSmartConfig.WOOD_MOVE_SPEED.get();
        if (d > 8.0) {
            return base * 1.25f; // 远处赶路加成 1.5→1.25（随基础速度一起减）
        }
        if (d < 3.0) {
            return base * 0.8f;
        }
        return base;
    }

    /**
     * v1.5.90：朝挡路块推进（穿透挖掘用）——目标是挡路块"脚下 1 格"（与挡路块
     * 同柱的地面），女仆走到墙根后挡路块就在伸手范围内 → 下一 tick 开始挖穿。
     * 与 walkToWoodBase 的区别：挡路块脚下通常是实心地基，不需要"找上方空气站到
     * 矿正上方往下挖"（那是埋地矿专用的）；脚下悬空才退回 walkToWoodBase 兜底。
     */
    private void walkToBlockFace(ServerLevel level, EntityMaid maid, BlockPos b) {
        BlockPos stand = new BlockPos(b.m_123341_(), b.m_123342_() - 1, b.m_123343_());
        BlockState below = level.m_8055_(stand);
        if (below.m_60795_() || !below.m_60796_(level, stand)) {
            this.walkToWoodBase(level, maid, b);
            return;
        }
        this.setWalkTarget(maid, stand, approachSpeed(maid, b));
    }

    /**
     * v1.5.87：挖出的掉落物即时回收——拾取任务在挖矿中已让位（最低优先级），
     * 这里把刚挖出（生成在挖矿格附近）的掉落物原地收进背包，不掉地上、不走"走过去捡"。
     */
    private void pickupWoodDrops(ServerLevel level, EntityMaid maid) {
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
     * - 在矿表内（isWood，含自定义矿表）——只连锁矿，不连锁石头/泥土
     * - 不是女仆自己搭的方块（isWoodingPlaced 排除）
     * 队列上限从配置面板读取（mine.chainLimit，默认 16）；BFS 展开上限
     * 64 防超大矿脉卡顿。开关关闭时直接清空。
     */
    private void refillChainQueue(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.WOOD_CHAIN_MINING.get()
                || this.chainBlock == null) {
            this.chainQueue.clear();
            this.chainBlock = null;
            return;
        }
        if (!this.chainQueue.isEmpty()) {
            return; // 队列还有货，挖完再补
        }
        int limit = com.maidsmart.config.MaidSmartConfig.WOOD_CHAIN_LIMIT.get();
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
                if (!this.isWood(level, nb)) {
                    continue; // 只连锁矿脉，不连锁石头
                }
                if (isWoodingPlaced(level, nb)) {
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
            LOGGER.info("wood chain filled: maid={} block={} queued={} limit={}",
                    maid.m_20148_(),
                    ForgeRegistries.BLOCKS.getKey(this.chainBlock),
                    this.chainQueue.size(), limit);
        } else if (com.maidsmart.config.MaidSmartConfig.WOOD_CHAIN_MINING.get()) {
            LOGGER.info("wood chain empty: maid={} block={} (no adjacent wood)",
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
        // 防窒息：放置格必须空气（不能覆盖目标木材/任何方块）；头顶空间
        // （place+1/place+2）放宽——目标木材所在格（搭高就是为了够它，垫块后
        // 头短暂插进目标木材，下一 tick 挖掘入口立即挖掉，属预期）与树叶等
        // 不窒息方块（isSuffocating=false）不拦；只拦真正会闷住她的实心满块。
        // v1.1.0 实测二百四十（用户："伐木不会像挖矿一样搭方块够上面的树木"）：
        // 旧版 isAir 检查在伐木场景几乎必然失败——头顶正上方就是目标木材（树干）
        // 或树冠树叶，都不是空气 → 搭高永远失败 → 女仆原地等待到超时弃置。
        if (!level.m_8055_(place).m_60795_()
                || (this.suffocates(level, place.m_7918_(0, 1, 0)) && !place.m_7918_(0, 1, 0).equals(this.targetPos))
                || (this.suffocates(level, place.m_7918_(0, 2, 0)) && !place.m_7918_(0, 2, 0).equals(this.targetPos))) {
            return false;
        }
        // v1.5.25e 借鉴自保 buildUp：女仆实际头顶（bounding box 顶面）上方必须空旷——
        // 快速连续搭高时实体位移滞后，blockPosition 检查不够，必须按真实位置判定，
        // 否则"搭太快把自己埋了"窒息（挖矿搭高也会踩同一坑）
        // v1.1.0 实测二百四十：isAir → suffocates——树冠场景女仆头顶就是树叶
        // （isSuffocating 恒 false，不闷人），旧版 isAir 检查把树叶当"堵头"拦死
        // 搭高（"伐木不搭方块"根因之二）；只拦真正会闷住她的实心满块。
        // v1.1.0 实测二百四十七（用户："挖掘一定数量后不再搭方块"）：headPos+1
        // 检查【漏了目标木材排除】——女仆搭了几块柱子后站在柱顶，目标木材就在
        // 头顶 1~2 格（headPos+1 恰好是目标木材）→ suffocates 拦死后续搭高。
        // 与 place+1/place+2 同口径：目标木材所在格排除（搭高就是为了够它）。
        double headY = maid.m_20191_().m_82374_(net.minecraft.core.Direction.Axis.Y);
        BlockPos headPos = new BlockPos((int) maid.m_20185_(), (int) (headY + 0.05), (int) maid.m_20189_());
        if ((this.suffocates(level, headPos) && !headPos.equals(this.targetPos))
                || (this.suffocates(level, headPos.m_7918_(0, 1, 0))
                && !headPos.m_7918_(0, 1, 0).equals(this.targetPos))) {
            return false; // 实际头顶被会窒息的实心块堵住（正在被顶起中）→ 等站稳再垫，防窒息
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
        trackPlaced(level, place, block, maid); // v1.5.28：全局登记（实测四十二：绑定搭建女仆）
        // v1.1.0 实测三十七（用户："搭方块的时候也播放一下动作"）：摆臂动画 + 放置音效
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        com.maidsmart.task.PlacedBlockTracker.placeSound(level, place, block);
        this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
        // v1.5.113（B6）：冷却只在【成功放置】后设置——旧版失败也消耗冷却，
        // 搭高被错误阻塞
        this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.WOOD_PILLAR_COOLDOWN.get();
        return true;
    }

    /** v1.1.0 实测二百四十：该格是否【会窒息】——isSuffocating（m_60796_）为 true
     *  的实心满块（石头/泥土/原木等）会闷住女仆；树叶/空气/水/火把等不窒息。
     *  搭高防窒息检查用它替代 isAir：伐木场景头顶是树干/树冠，isAir 检查
     *  让搭高永远失败（"伐木不搭方块"根因），不窒息判定只拦真正会闷住她的块。 */
    private boolean suffocates(ServerLevel level, BlockPos pos) {
        BlockState st = level.m_8055_(pos);
        return !st.m_60795_() && st.m_60796_(level, pos);
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
                "背包里没有能搭的方块了（圆石/泥土等），够不着高处的木材……请给我一些方块～");
    }

    /** v1.1.0 终审三：没有斧头播报（限频 30 秒）——空手也能砍，只是慢；给把斧更快 */
    private void notifyNoAxe(EntityMaid maid) {
        long now = maid.m_9236_().m_46467_();
        Long last = NO_AXE_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < 600L) {
            return;
        }
        NO_AXE_REPORT_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "我没有斧头，只能用手慢慢掰了……要是给我一把斧就快多了～");
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
            trackPlaced(level, fill, block, maid); // v1.5.28：全局登记（实测四十二：绑定搭建女仆）
            // v1.1.0 实测三十七：搭方块摆臂动画 + 放置音效
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            com.maidsmart.task.PlacedBlockTracker.placeSound(level, fill, block);
            this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
            this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.WOOD_PILLAR_COOLDOWN.get(); // v1.5.113（B6）
            return true;
        }
        return false;
    }

    /**
     * v1.5.25 搭桥：目标方向前方 1 格脚下悬空（断崖/水）→ 垫 1 块桥，再走过去。
     * 只垫"该垫"的位置，不破坏任何方块；垫一块后重算（下 tick 再评估/走）。
     */
    private boolean bridgeToWood(ServerLevel level, EntityMaid maid, double hx, double hz, double hDist) {
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
            trackPlaced(level, fill, block, maid); // v1.5.28：全局登记（实测四十二：绑定搭建女仆）
            // v1.1.0 实测三十七：搭方块摆臂动画 + 放置音效
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            com.maidsmart.task.PlacedBlockTracker.placeSound(level, fill, block);
            this.pillarGuardTicks = 12; // v1.5.87：搭块防掉落窗口
            this.pillarCooldown = com.maidsmart.config.MaidSmartConfig.WOOD_PILLAR_COOLDOWN.get(); // v1.5.113（B6）
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
        if (!isWoodingPlaced(level, under) && !isWoodingPlaced(level, feet)) {
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

    /** v1.5.24：取背包中数量最多的可搭方块（BlockItem + 非下落），用于搭高挖矿。
     *  v1.1.0 实测二百三十一：含手部栏（主/副手）——手里拿的方块也能垫（审计修复） */
    private Item takeBuildBlock(EntityMaid maid) {
        // v1.1.0 实测七：统一走 MaidBuildBlockFilter——火把等无碰撞方块不再入选
        return com.maidsmart.tool.MaidBuildBlockFilter.takeBuildBlock(
                maid.getMaidInv(), maid.getHandsInvWrapper(), null, null);
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // v1.5.113：挖矿任务期间【常驻】——目标挖完不结束，下一 tick 继续扫描/
        // 迁移（与 canStart 常驻配套）；切走任务才停止（m_6732_ 退场）。
        // 旧版 targetPos 挖完即 false → 行为停止 → 女仆原地发呆不找下一个矿。
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:woodcut").equals(maid.getTask().getUid());
    }

    private boolean isWood(ServerLevel level, BlockPos pos) {
        return isWoodState(level.m_8055_(pos));
    }

    /**
     * v1.5.189：危险方块规避——该格（或脚下）是岩浆/火/岩浆块/仙人掌/甜浆果/营火
     * 视为危险：挖矿目标或路径上不选（照抄自保 DANGER_BLOCKS 判定，天然回避）
     */
    private boolean isDangerAt(ServerLevel level, BlockPos pos) {
        try {
            net.minecraft.world.level.block.state.BlockState state = level.m_8055_(pos);
            net.minecraft.world.level.block.state.BlockState below = level.m_8055_(pos.m_7918_(0, -1, 0));
            // v1.1.0 实测十六（审查 P2）：用 static 缓存的 Block[] 替代每次 parse+查注册表
            for (net.minecraft.world.level.block.Block b : DANGER_BLOCKS) {
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
    /** v1.1.0 实测十六：DANGER_BLOCK_IDS 解析后的 Block 缓存（类加载时一次，防每候选 6 次注册表查询） */
    private static final net.minecraft.world.level.block.Block[] DANGER_BLOCKS = initDangerBlocks();
    private static net.minecraft.world.level.block.Block[] initDangerBlocks() {
        java.util.List<net.minecraft.world.level.block.Block> list = new java.util.ArrayList<>();
        for (String id : DANGER_BLOCK_IDS) {
            try {
                net.minecraft.world.level.block.Block b =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                                net.minecraft.resources.ResourceLocation.parse(id));
                if (b != null) {
                    list.add(b);
                }
            } catch (Exception ignored) {
            }
        }
        return list.toArray(new net.minecraft.world.level.block.Block[0]);
    }

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
     *  v1.5.102d：基岩/屏障等不可破坏方块永远返回 false（防面板误加后女仆傻挖）
     *  v1.0.4：内置方块再扣除"已取消挖穿"的排除名单（MINE_DISABLED_BREAKABLES）——
     *  界面取消内置障碍物打勾后，isBreakable 对其返回 false，女仆不再挖穿 */
    private static boolean isBreakable(String path) {
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
        } catch (Exception ignored) {
        }
        return false;
    }

    /** v1.0.4：内置障碍物是否已被界面取消挖穿（加入 MINE_DISABLED_BREAKABLES 排除名单） */
    private static boolean isDisabledBreakable(String path) {
        try {
            for (String e : com.maidsmart.config.MaidSmartConfig.MINE_DISABLED_BREAKABLES.get()) {
                if (e != null && e.equals(path)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
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
            } else if (now - since >= com.maidsmart.config.MaidSmartConfig.WOOD_ANCHOR_TIMEOUT.get()) {
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
        int radius = com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.WOOD_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.WOOD_UP_RANGE.get();
        return Math.abs(feet.m_123341_() - anchor.m_123341_()) > radius
                || Math.abs(feet.m_123343_() - anchor.m_123343_()) > radius
                || feet.m_123342_() - anchor.m_123342_() > up
                || anchor.m_123342_() - feet.m_123342_() > down;
    }

    /** v1.5.47：重埋锚点（脚下；20 tick 节流） */
    private void relocate(ServerLevel level, EntityMaid maid, long now) {
        int id = maid.m_19879_();
        Long last = LAST_RELOCATE.get(id);
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.WOOD_RELOCATE_THROTTLE.get()) {
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
     * 同时清 WOOD_CACHE（新框未扫描）并朝新框中心设一个移动目标（赶路不干等）。
     */
    private void slideAnchor(ServerLevel level, EntityMaid maid, BlockPos old) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        Long last = SLIDE_SINCE.get(id);
        if (last != null && now - last < 100L) {
            return; // 5 秒一次
        }
        SLIDE_SINCE.put(id, now);
        int step = Math.max(4, com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get());
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
        WOOD_CACHE.remove(id); // 新框未扫描，强制重建
        // 朝新框中心走（空框时也在赶路，不原地干等；速度随 wood.moveSpeed 配置，
        // 实测四十八起默认 0.3——旧版硬编码 0.6 正是"移速过快"的一处来源）
        this.setWalkTarget(maid, na,
                (float) (double) com.maidsmart.config.MaidSmartConfig.WOOD_MOVE_SPEED.get());
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
    private void recordSkippedWood(EntityMaid maid, BlockPos pos, BlockState state) {
        Integer value = woodValueOf(state);
        if (value == null || (this.skippedWoodPos != null && value <= this.skippedWoodValue)) {
            return;
        }
        this.skippedWoodValue = value;
        this.skippedWoodPos = pos.m_7949_();
        // v1.5.102c：报中文矿石名（旧版英文 path 如 diamond_ore）
        this.skippedWoodName = blockCnName(state.m_60734_());
        this.skippedWoodTool = requiredTool(state);
    }

    /** v1.1.0：伐木需要什么工具——木材无挖掘等级 tag，斧子即可（保留 tag 判定兼容模组木材） */
    private static String requiredTool(BlockState state) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        state.m_204343_().forEach(t -> paths.add(t.f_203868_().m_135815_()));
        if (paths.contains("needs_diamond_tool")) {
            return "钻石斧";
        }
        if (paths.contains("needs_iron_tool")) {
            return "铁斧";
        }
        if (paths.contains("needs_stone_tool")) {
            return "石斧";
        }
        return "斧子";
    }

    /**
     * 播报"附近有木材但斧子挖不动"——气泡 + 主人聊天栏，
     * 报坐标和需要的斧子等级；每 30 秒最多一次，不刷屏。
     */
    private void reportSkippedWood(EntityMaid maid, long gameTime) {
        if (this.skippedWoodPos == null) {
            return;
        }
        Long last = SKIP_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && gameTime - last < com.maidsmart.config.MaidSmartConfig.WOOD_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        SKIP_REPORT_SINCE.put(maid.m_19879_(), gameTime);
        // 诊断——带上主手实际物品（排查"斧没装备导致砍不了"）
        String handName = "空";
        ItemStack hand = maid.m_21205_();
        if (!hand.m_41619_()) {
            net.minecraft.resources.ResourceLocation hk = ForgeRegistries.ITEMS.getKey(hand.m_41720_());
            handName = hk != null ? com.maidsmart.build.BlueprintLib.cnName(hk.toString()) : hand.m_41720_().toString();
        }
        maid.getChatBubbleManager().addTextChatBubble(
                "我发现了一个" + this.skippedWoodName + "（坐标 " + this.skippedWoodPos.m_123341_()
                        + ", " + this.skippedWoodPos.m_123342_() + ", " + this.skippedWoodPos.m_123343_()
                        + "），需要" + this.skippedWoodTool + "才能砍，我现在的斧子（主手：" + handName
                        + "）砍不动，先跳过啦～");
    }

    /**
     * 播报"木材被硬挡路（箱子/机器/基岩等）挡住"——气泡 + 主人聊天栏，
     * 报挡路方块与坐标；每 30 秒限频一次。该木材同时加入 blockedWoods 持续排除。
     */
    private void reportBlockedOre(EntityMaid maid, ServerLevel level, BlockPos orePos, BlockPos blockerPos) {
        long now = level.m_46467_();
        Long last = BLOCKED_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.WOOD_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.m_19879_(), now);
        // 报中文方块名
        String name = blockCnName(level.m_8055_(blockerPos).m_60734_());
        String woodName = blockCnName(level.m_8055_(orePos).m_60734_());
        maid.getChatBubbleManager().addTextChatBubble(
                "前方有" + name + "（坐标 " + blockerPos.m_123341_() + ", " + blockerPos.m_123342_()
                        + ", " + blockerPos.m_123343_() + "）挡住了" + woodName + "（坐标 " + orePos.m_123341_()
                        + ", " + orePos.m_123342_() + ", " + orePos.m_123343_() + "），我砍不过去，先换个目标啦～");
    }

    /**
     * v1.5.102d：场上没有可挖矿物、但此前有被硬物挡住的矿时，仍上报一次挡路原因
     * （30 秒限频）——让主人知道"没矿"其实是被基岩/箱子等挡住了，而不是女仆偷懒。
     */
    private void reportBlockedArea(EntityMaid maid, ServerLevel level) {
        long now = level.m_46467_();
        Long last = BLOCKED_REPORT_SINCE.get(maid.m_19879_());
        if (last != null && now - last < com.maidsmart.config.MaidSmartConfig.WOOD_SKIP_REPORT_INTERVAL.get()) {
            return;
        }
        BLOCKED_REPORT_SINCE.put(maid.m_19879_(), now);
        maid.getChatBubbleManager().addTextChatBubble(
                "附近有木材被硬方块挡住（基岩/箱子等），我砍不过去，暂时没有可砍的木材～");
        if (maid.m_269323_() instanceof net.minecraft.server.level.ServerPlayer owner) {
            // v1.1.0 实测二百七十四：建造女仆静默非建造字幕（气泡已由 mixin 拦）
            if (!com.maidsmart.combat.BuildShieldGuard.shouldMute(maid)) {
                owner.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7c【伐木】" + maid.m_5446_().getString()
                                + "：附近有木材被硬方块挡住，暂时没有可砍的木材～"));
            }
        }
    }

    /**
     * v1.5.47：找最优矿石（以【锚点】为中心，杜绝"越挖越远"漂移）：
     * score = 距离平方 + 深度惩罚 - 价值加成，取最低分。
     * 穿透预算：女仆到矿之间实心挡路方块 ≤MAX_BREAK_BUDGET 可选（借鉴 maidmining）；
     * 刚弃置的目标跳过一次。
     *
     * v1.5.113（A1/A3/B4）：性能与行为优化——
     * - 全量扫描（8.9 万格）每 5 秒一次（WOOD_CACHE_TTL），期间缓存轮只校验
     *   已记录的矿（存在性/框内/可挖性），廉价得多（旧版每 3 tick 全量扫）；
     * - 挡路预算按矿坐标缓存（缓存轮复用），不再对每个候选重算视线；
     * - 镐能力判定：主手先查（零开销），主手不够才查背包最高级镐（一次遍历）；
     * - 弃置矿 30 秒短时排除（RECENT_DISCARD，B4）。
     */
    private BlockPos findWood(ServerLevel level, EntityMaid maid, BlockPos anchor) {
        // v1.1.0 终审三（用户：木材空手也能挖，不能因为空手就拒绝工作）：
        // 找树前仍优先换斧（有斧砍得快），但没斧照样干活——空手慢挖。
        // 换斧机制与矿镐一致：玩家手中放的斧不动，空手/非斧才从背包装备。
        if (maid.m_21205_().m_41619_()
                || !(maid.m_21205_().m_41720_() instanceof AxeItem)) {
            MaidToolAutoEquip.ensureAnyAxe(maid);
        }
        int id = maid.m_19879_();
        long now = level.m_46467_();
        // v1.5.85：每次扫描重置"镐子挖不动的矿"记录（记录本次扫描价值最高的，用于播报）
        this.skippedWoodPos = null;
        this.skippedWoodName = null;
        this.skippedWoodTool = null;
        this.skippedWoodValue = -1;
        // v1.5.113（B4）：修剪过期的短时排除（30 秒）
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        if (disc != null) {
            disc.entrySet().removeIf(e -> now - e.getValue() > 600L);
            if (disc.isEmpty()) {
                RECENT_DISCARD.remove(id);
            }
        }
        // v1.5.88：懒加载自定义矿表（config 文件加载完成后首次扫描才真正读到值）
        ensureCustomWoods();
        WoodCache cache = WOOD_CACHE.get(id);
        if (cache != null) {
            // v1.1.0 实测一百四十二：去掉 5 秒 TTL 到期强制重建（与挖矿同款）——
            // 缓存轮逐格校验木材是否还在，到期重建只会造成"每 5 秒停一下重扫/换目标"；
            // 缓存一直用到轮空才全量重建（锚点变化/迁框另有强制重建路径）
            this.lastScanWasFull = false;
            BlockPos fromCache = this.pickFromCache(level, maid, anchor, cache);
            if (fromCache != null) {
                return fromCache;
            }
            // v1.5.127：缓存轮空 → 不等 TTL 立即全量重建——"原地愣 2~3 秒"根因：
            // 挖完缓存里最后一块矿后 pickFromCache 返回 null，旧版要等 WOOD_CACHE_TTL
            // （5 秒）到期才重建，女仆在这 2~5 秒里站着发呆；重建顺带把"框内无矿
            // 迁移"从等 5 秒提速到即时。重建仍无 → 返回 null 走迁移逻辑（不变）。
            this.lastScanWasFull = true;
            return this.fullScanWoods(level, maid, anchor, now);
        }
        this.lastScanWasFull = true;
        return this.fullScanWoods(level, maid, anchor, now);
    }

    /**
     * v1.5.113：全量重建扫描（每 5 秒一次）——扫满整个搜索框，记录候选矿 + 挡路预算。
     * v1.1.0 实测六十一（借鉴 TLM-Sincerely 预算制探测）：旧版一帧内同步扫完
     * （半径 24 时 ≈ 6.7 万格 = 单 tick 尖峰），改为【分帧游标续扫】——每 tick 最多
     * 检查 WOOD_SCAN_BUDGET 格，游标跨 tick 续进；扫完才写 WOOD_CACHE 返回最优。
     * 扫描期间返回 null（调用方按"本 tick 无目标"处理，scanCooldown 节流下女仆
     * 短暂待机）；锚点变化/状态过期自动作废重扫。
     */
    private BlockPos fullScanWoods(ServerLevel level, EntityMaid maid, BlockPos anchor, long now) {
        int id = maid.m_19879_();
        int feetY = anchor.m_123342_();
        int radius = com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.WOOD_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.WOOD_UP_RANGE.get();
        WoodScanState st = WOOD_SCANS.get(id);
        if (st == null || !st.anchor.equals(anchor) || now - st.startedAt > 1200L
                || st.dy < -down || st.dy > up
                || st.dx < -radius || st.dx > radius || st.dz < -radius || st.dz > radius) {
            st = new WoodScanState(anchor, now, radius, down);
            WOOD_SCANS.put(id, st);
        }
        int budget = com.maidsmart.config.MaidSmartConfig.WOOD_SCAN_BUDGET.get();
        int breakBudget = com.maidsmart.config.MaidSmartConfig.WOOD_BREAK_BUDGET.get();
        double valueWeight = com.maidsmart.config.MaidSmartConfig.WOOD_VALUE_WEIGHT.get();
        double depthPenalty = com.maidsmart.config.MaidSmartConfig.WOOD_DEPTH_PENALTY.get();
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        int processed = 0;
        while (st.dy <= up && processed < budget) {
            BlockPos p = anchor.m_7918_(st.dx, st.dy, st.dz);
            int dx = st.dx;
            int dz = st.dz;
            int dy = st.dy;
            // 游标前进：dz → dx → dy（dz/dx 越界回绕进位）
            st.dz++;
            if (st.dz > radius) {
                st.dz = -radius;
                st.dx++;
            }
            if (st.dx > radius) {
                st.dx = -radius;
                st.dy++;
            }
            processed++;
            if (p.equals(this.abandonedPos) || this.blockedWoods.contains(p)
                    || (disc != null && disc.containsKey(p.m_7949_()))) {
                continue; // v1.5.47：刚弃置跳过；v1.5.87：硬挡路持续排除；v1.5.113：短时排除
            }
            BlockState woodState = level.m_8055_(p);
            Integer value = woodValueOf(woodState);
            if (value == null) {
                continue;
            }
            // v1.1.0：不把自己 10 秒内搭的方块当木材（搭路材料几乎必是原木——
            // 不跳过会把刚垫脚的原木砍掉，循环拆了再搭）
            if (isWoodingPlaced(level, p)) {
                continue;
            }
            // v1.0.4：透视感知开关（默认关）——关闭时女仆像玩家一样只发现视线无阻
            // 的矿物：被墙/实心方块挡住的矿不可见（不进候选，也就没有系统/气泡播报）；
            // 开启 = 旧版隔墙找矿逻辑，不检查视线
            if (!com.maidsmart.config.MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get()
                    && !this.hasClearSight(level, maid, p)) {
                continue;
            }
            // v1.5.189：危险方块规避——目标矿自身或路径上有岩浆/火/岩浆块/
            // 仙人掌/甜浆果/营火 → 不选（挖过去会烫伤/引燃；复用自保 DANGER 判定）
            if (this.isDangerAt(level, p)) {
                continue;
            }
            // v1.5.85/107：手+背包都挖不动才算真挖不动（跳过+播报）
            // v1.5.113（A3）：先查主手（零开销），主手不够才查背包最高级镐
            // v1.1.0 终审三：木材没有挖掘等级——空手也能挖。此过滤只拦
            // "模组木材带挖掘等级 tag 且手+背包都没有对应斧"的极端情况
            //（原版全部木材永远通过）。
            if (!MaidToolAutoEquip.canHarvestWoodOrBareHand(maid, woodState)) {
                this.recordSkippedWood(maid, p, woodState);
                continue;
            }
            // v1.5.47：穿透预算——挡路实心方块 >预算 的矿不选（挖不过去）
            int blocking = this.countBlocking(level, maid, p);
            if (blocking > breakBudget) {
                continue;
            }
            st.blockingCache.put(p.m_7949_(), blocking); // v1.5.113（A3）：缓存轮复用
            st.found.add(p.m_7949_());
            double score = dx * dx + dz * dz + dy * dy
                    + depthPenalty * Math.max(0, feetY - p.m_123342_())
                    - value * valueWeight;
            if (score < st.bestScore) {
                st.bestScore = score;
                st.best = p.m_7949_();
            }
        }
        if (st.dy <= up) {
            return null; // 预算用尽，下 tick 续扫
        }
        // 扫描完成：写缓存（与旧版同口径），清游标状态
        WOOD_SCANS.remove(id);
        WOOD_CACHE.put(id, new WoodCache(now, st.found, st.blockingCache));
        return st.best;
    }

    /** v1.5.113：缓存轮——只校验已记录的矿（存在性/框内/可挖/挡路预算），廉价 */
    private BlockPos pickFromCache(ServerLevel level, EntityMaid maid, BlockPos anchor, WoodCache cache) {
        int id = maid.m_19879_();
        long now = level.m_46467_();
        int feetY = anchor.m_123342_();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get();
        int down = com.maidsmart.config.MaidSmartConfig.WOOD_DOWN_RANGE.get();
        int up = com.maidsmart.config.MaidSmartConfig.WOOD_UP_RANGE.get();
        int budget = com.maidsmart.config.MaidSmartConfig.WOOD_BREAK_BUDGET.get();
        double valueWeight = com.maidsmart.config.MaidSmartConfig.WOOD_VALUE_WEIGHT.get();
        double depthPenalty = com.maidsmart.config.MaidSmartConfig.WOOD_DEPTH_PENALTY.get();
        java.util.Map<BlockPos, Long> disc = RECENT_DISCARD.get(id);
        for (BlockPos p : cache.ores) {
            if (p.equals(this.abandonedPos) || this.blockedWoods.contains(p)
                    || (disc != null && disc.containsKey(p))) {
                continue;
            }
            if (!this.isWood(level, p)) {
                continue; // 已被挖掉
            }
            // v1.1.0：缓存轮同样跳过自己 10 秒内搭的方块（不当木材砍，与全量扫描同口径）
            if (isWoodingPlaced(level, p)) {
                continue;
            }
            // v1.0.4：透视感知默认关——缓存轮同样只认视线无阻的矿（女仆移动/配置热更新后
            // 与发现层判定一致；开启透视则跳过）
            if (!com.maidsmart.config.MaidSmartConfig.WOOD_SEEK_THROUGH_WALLS.get()
                    && !this.hasClearSight(level, maid, p)) {
                continue;
            }
            int dx = p.m_123341_() - anchor.m_123341_();
            int dz = p.m_123343_() - anchor.m_123343_();
            int dy = p.m_123342_() - anchor.m_123342_();
            if (Math.abs(dx) > radius || Math.abs(dz) > radius
                    || dy > up || -dy > down) {
                continue; // 锚点滑动/重埋后出框
            }
            BlockState st = level.m_8055_(p);
            // v1.1.0 终审三：空手也能挖（同全量扫描口径——只拦模组木材带挖掘等级的极端情况）
            if (!MaidToolAutoEquip.canHarvestWoodOrBareHand(maid, st)) {
                this.recordSkippedWood(maid, p, st);
                continue;
            }
            // v1.5.189：危险方块规避（缓存轮同样跳过岩浆/火/岩浆块等目标）
            if (this.isDangerAt(level, p)) {
                continue;
            }
            Integer value = woodValueOf(st);
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

    /** 女仆眼睛高度（脚下偏移，格）。原版公式 0.85×身高：默认女仆身高 1.5 → 约 1.27；
     *  玩家使用的大正酒狐女仆模型实测头中心约 1.5 格，v1.0.5 起按模型取 1.5。 */
    private static final double EYE_HEIGHT = 1.5;

    /**
     * v1.0.4：视线感知（透视感知开关默认关时启用）——女仆像玩家一样只能发现视线无阻的矿物。
     * 从眼睛到矿物中心采样（每 0.5 格，与 countBlocking 同一口径），到达矿物前碰到任何
     * 【非空气、非流体】方块即判定视线被挡 → 不可见。除水/岩浆外一律挡视线：
     * - 泥土/石头/矿石/箱子等自不必说；
     * - 玻璃/半砖/树叶等透明或半格方块也挡——它们不是矿洞天然生成物，玩家手动把矿
     *   裹起来说明不想破坏里面的矿（半格高按一格高算）；
     * - 水/岩浆是矿洞常见液体，女仆要能挖水下/岩浆旁的矿（岩浆旁的目标另有危险规避）。
     * 注意与 countBlocking 的区别：那是"挖不挖得过去"（穿透预算），这里是"看不得看见"
     * （发现层过滤）——关闭透视后视线被挡的矿不进候选，也不产生任何播报。
     * v1.0.5：眼睛高度改 1.5（按玩家的大正酒狐模型）；判定改多点——矿的中心/顶面/
     * 底面任一可见即算可见（贴地矿顶面可见、悬空矿底面可见，玩家能看到矿的任何一个
     * 面就算看见）。凹槽里的矿仍须视线从开口进入：开口顶面低于眼睛时三条线都撞墙，
     * 依旧不可见（与玩家站在同一位置一致）。
     */
    private boolean hasClearSight(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.m_20185_();
        double sy = maid.m_20186_() + EYE_HEIGHT; // 与 countBlocking/findBlockingBlock 同一眼睛高度
        double sz = maid.m_20189_();
        double cx = target.m_123341_() + 0.5;
        double cz = target.m_123343_() + 0.5;
        return hasClearRay(level, sx, sy, sz, cx, target.m_123342_() + 0.5, cz, target)
                || hasClearRay(level, sx, sy, sz, cx, target.m_123342_() + 1.0, cz, target)
                || hasClearRay(level, sx, sy, sz, cx, target.m_123342_(), cz, target);
    }

    /** 从眼睛位置到目标点每 0.5 格采样：到矿本身即停，路径上任何非空气/非流体方块都挡 */
    private boolean hasClearRay(ServerLevel level, double sx, double sy, double sz,
                                double tx, double ty, double tz, BlockPos target) {
        double dist = Math.sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz));
        int steps = Math.max(1, (int) Math.ceil(dist * 2.0));
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
                continue; // 空气不挡
            }
            if (!st.m_60819_().m_76178_()) {
                continue; // 水/岩浆等流体不挡（m_76178_ = FluidState.isEmpty）
            }
            if (st.m_60734_() instanceof net.minecraft.world.level.block.LeavesBlock) {
                continue; // v1.1.0：树叶不挡视线——树冠内的树干照常可见（砍树核心场景）
            }
            return false; // 任何其他方块（含玻璃/半砖/土/石）都挡视线
        }
        return true;
    }

    /**
     * v1.5.47：女仆到目标路径上的实心非矿方块数（≤预算可穿透；矿石/空气/非满块/水不算）。
     * 改造自 v1.5.25g hasLineOfSight——旧版"全遮挡排除"导致被岩壁包住的矿永远挖不到。
     */
    private int countBlocking(ServerLevel level, EntityMaid maid, BlockPos target) {
        double sx = maid.m_20185_();
        double sy = maid.m_20186_() + EYE_HEIGHT; // 眼睛高度
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
            if (isWoodState(st)) {
                continue; // 木材不挡（连着的目标树干不算阻挡；模组原木同口径）
            }
            if (st.m_60734_() instanceof net.minecraft.world.level.block.LeavesBlock) {
                continue; // v1.1.0：树叶不计阻挡——树冠包着的树干不算被挡
            }
            if (isWoodingPlaced(level, sample)) {
                continue; // v1.1.0：自己 10 秒内搭的方块不算阻挡（到期自动销毁，不算预算）
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
        double sy = maid.m_20186_() + EYE_HEIGHT; // 眼睛高度
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
            if (st.m_60795_() || isWoodState(st)
                    || st.m_60734_() instanceof net.minecraft.world.level.block.LeavesBlock
                    || !st.m_60796_(level, sample)) {
                continue; // 空气/目标木材/树叶（v1.1.0 放行）/非满块不算挡路
            }
            // v1.1.0：自己 10 秒内搭的方块不当挡路块砍——伐木搭路材料常是原木（正好在
            // 木材表里），旧版会把它当障碍物砍掉 → 掉下来 → 再搭 → 再砍 死循环；
            // 搭的方块到期自动销毁，挖穿判断直接跳过（找后面的真实障碍）
            if (isWoodingPlaced(level, sample)) {
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
        int keep = com.maidsmart.config.MaidSmartConfig.WOOD_JUNK_KEEP.get();
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
