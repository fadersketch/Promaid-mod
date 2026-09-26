package com.maidsmart.tool;

/**
 * v1.3.0(beta) 实测六百八十【原版天然方块表】——「搭方块禁用名单」那条默认规则的那张表。
 *
 * <p>玩家原话：「加一个额外的配置界面……是一个黑名单面板，选择方即让女仆禁止使用哪个东西
 * 来搭方块（此配置对于自保搭高、挖矿、伐木、搭路都生效），默认禁止搭建的为所有非的原版
 * 自然生成方块。」
 *
 * <p>【这张表是什么】"原版自然生成、且女仆真能拿来垫脚"的方块白名单（注册名全 id）。
 * 默认规则就是它的补集：**不在表里 = 默认禁止**（模组方块一律不在表里 → 模组方块默认全禁，
 * 这正是玩家要的"想用模组方块自己去面板里取消勾选"）。
 *
 * <p>【为什么是"手写表"而不是"查世界生成数据"】原版没有任何"这方块会不会自然生成"的
 * API；从 `data/minecraft/worldgen/**` 反推出来的集合既缺项又串味（实测反推：
 * 缺 `cobblestone`/`cobbled_deepslate`/`obsidian`/`bedrock`，却混进 `oak_planks`/
 * `gold_block`/`glass`——那些是结构处理器的输出，不是地形）。表一旦"看起来没道理"，
 * 玩家就再也信不过默认值。所以这里用**人工审定的一份短表**：只收"从地形里挖出来的"方块，
 * 判定靠一句话就能讲清（见下），漏了什么在面板里点一下就能放开——面板存在就是为了这个。
 *
 * <p>【收录口径（一句话）】原版方块、能在世界里自然捡到／挖到、放下去能站人（有完整碰撞）。
 * 因此**不收**：合成/烧炼/切石产物（木板、玻璃、石砖、羊毛、混凝土、铜块、紫珀……）、
 * 结构专属建材（海晶石、下界砖）、无碰撞的植物（花/草/火把/树叶/地毯——它们本来就被
 * {@link MaidBuildBlockFilter} 的碰撞判定拦掉）、以及一切模组方块。
 *
 * <p>【与既有黑名单的关系（互不冲突、层层叠加）】沙子/砾石这类**下落方块**、
 * 仙人掌/岩浆块这类**伤害方块**、{@code dangerBlocks} 配置表——它们仍由
 * {@link MaidBuildBlockFilter#isUsableBuildBlock} 原有的几条判定拦住，本表收不收都不影响
 * （沙子在表里、照样不许搭——正好证明两层判定是各自独立的）。
 *
 * <p>【两树同一份】本类只认**注册名字符串**，不碰任何加载器注册表，所以 SRG 树与 Mojmap
 * 树里逐字相同（调用方 {@link MaidBuildBlockFilter} 自己把 Block 换成注册名再问这里）。
 */
public final class NaturalBlocks {

    private NaturalBlocks() {
    }

    private static final java.util.Set<String> NATURAL = new java.util.HashSet<>();

    private static void add(String path) {
        NATURAL.add("minecraft:" + path);
    }

    static {
        // ---- 地表/泥土 ----
        add("grass_block");
        add("dirt");
        add("coarse_dirt");
        add("rooted_dirt");
        add("podzol");
        add("mycelium");
        add("mud");
        add("muddy_mangrove_roots");
        add("mangrove_roots");
        add("moss_block");
        add("clay");
        add("gravel");
        add("sand");
        add("red_sand");
        add("sandstone");
        add("red_sandstone");
        // ---- 雪/冰 ----
        add("snow_block");
        add("ice");
        add("packed_ice");
        add("blue_ice");
        // ---- 石头一族 ----
        add("stone");
        add("cobblestone");
        add("mossy_cobblestone");
        add("granite");
        add("diorite");
        add("andesite");
        add("deepslate");
        add("cobbled_deepslate");
        add("tuff");
        add("calcite");
        add("dripstone_block");
        add("obsidian");
        // ---- 染色陶瓦（恶地自然生成）----
        add("terracotta");
        add("white_terracotta");
        add("orange_terracotta");
        add("magenta_terracotta");
        add("light_blue_terracotta");
        add("yellow_terracotta");
        add("lime_terracotta");
        add("pink_terracotta");
        add("gray_terracotta");
        add("light_gray_terracotta");
        add("cyan_terracotta");
        add("purple_terracotta");
        add("blue_terracotta");
        add("brown_terracotta");
        add("green_terracotta");
        add("red_terracotta");
        add("black_terracotta");
        // ---- 矿石（含深板岩变种）----
        add("coal_ore");
        add("iron_ore");
        add("copper_ore");
        add("gold_ore");
        add("redstone_ore");
        add("lapis_ore");
        add("diamond_ore");
        add("emerald_ore");
        add("deepslate_coal_ore");
        add("deepslate_iron_ore");
        add("deepslate_copper_ore");
        add("deepslate_gold_ore");
        add("deepslate_redstone_ore");
        add("deepslate_lapis_ore");
        add("deepslate_diamond_ore");
        add("deepslate_emerald_ore");
        add("nether_quartz_ore");
        add("nether_gold_ore");
        add("ancient_debris");
        // ---- 木头（原木/菌柄与"木"形态；木板是合成品，不在表里）----
        add("oak_log");
        add("spruce_log");
        add("birch_log");
        add("jungle_log");
        add("acacia_log");
        add("dark_oak_log");
        add("mangrove_log");
        add("cherry_log");
        add("crimson_stem");
        add("warped_stem");
        add("oak_wood");
        add("spruce_wood");
        add("birch_wood");
        add("jungle_wood");
        add("acacia_wood");
        add("dark_oak_wood");
        add("mangrove_wood");
        add("cherry_wood");
        add("crimson_hyphae");
        add("warped_hyphae");
        // ---- 下界 ----
        add("netherrack");
        add("soul_sand");
        add("soul_soil");
        add("basalt");
        add("smooth_basalt");
        add("blackstone");
        add("gilded_blackstone");
        add("magma_block");
        add("glowstone");
        add("shroomlight");
        add("nether_wart_block");
        add("warped_wart_block");
        add("crimson_nylium");
        add("warped_nylium");
        // ---- 末地 ----
        add("end_stone");
        // ---- 洞穴/晶洞/深暗 ----
        add("amethyst_block");
        add("budding_amethyst");
        add("sculk");
        // ---- 天然生长的"方块"（南瓜/西瓜/蘑菇块/蜂巢）----
        add("pumpkin");
        add("melon");
        add("brown_mushroom_block");
        add("red_mushroom_block");
        add("mushroom_stem");
        add("bee_nest");
    }

    /** 该注册名（可带可不带 {@code minecraft:} 前缀，大小写不敏感）是否在"原版天然方块"表里。 */
    public static boolean contains(String blockId) {
        return NATURAL.contains(normalize(blockId));
    }

    /** 表大小（面板/诊断文案用）。 */
    public static int size() {
        return NATURAL.size();
    }

    /** 表内容只读视图（诊断/日志用）。 */
    public static java.util.Set<String> table() {
        return java.util.Collections.unmodifiableSet(NATURAL);
    }

    /** "stone" / " Minecraft:Stone " → "minecraft:stone"（无 namespace 按 minecraft 补全）。 */
    public static String normalize(String blockId) {
        if (blockId == null) {
            return "";
        }
        String s = blockId.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }
}
