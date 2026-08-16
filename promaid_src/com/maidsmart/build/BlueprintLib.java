package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建筑蓝图库（v1.4，v1.5.12 起支持 numen 式结构文件蓝图）。
 *
 * 蓝图格式：List<String>，每个元素 "x,y,z,blockid" 或 "x,y,z,blockid|stateSnbt|beSnbt"
 * （相对坐标，y 从 0 起；stateSnbt = BlockState NBT 文本，beSnbt = 方块实体 NBT 文本）。
 * 来源：
 * - 内置预设（离线可用）：hut 小木屋 / gazebo 凉亭 / fountain 喷泉 / tower 瞭望塔 / well 水井
 * - LLM 现场生成（联网）：smart_build 工具传入 JSON 蓝图
 * - 外部文件（v1.5.11 JSON；v1.5.12 起 .nbt/.snbt 结构文件，numen 式）：
 *   config/maid_smart/blueprints/ 与 存档 schematics/ 文件夹，放入即用（增量扫描）
 *
 * 安全与平衡：
 * - LLM JSON 蓝图：方块白名单（~45 种建筑方块）+ 平面 ±12 + 高度 ≤8 + ≤200 块
 * - 结构文件蓝图：无白名单（玩家自己的文件），黑名单（基岩/命令方块/液体等）+ ≤8192 块
 * - 材料预检：调用时统计背包缺口并回报
 */
public final class BlueprintLib {
    /** v1.5.25h：LogUtils（log4j，必进 latest.log）——之前 System.out 不进日志，
     *  导致 .snbt 解析失败原因一直看不到（诊断盲区） */
    /** v1.5.88：读配置面板（build 段） */
    public static int maxBlocks() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_BLOCKS.get();
    }

    public static int maxRange() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_RANGE.get();
    }

    public static int maxHeight() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_MAX_HEIGHT.get();
    }

    public static int structureMaxBlocks() {
        return com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_BLOCKS.get();
    }

    private static int catalogMaxBlocks() {
        return structureMaxBlocks();
    }


    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** v1.5.227：外部文件解析失败 WARN 去重（目录反复重扫时每个文件只提示一次） */
    private static final java.util.Set<String> WARNED_PARSE_FAIL =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 结构文件蓝图上限（v1.5.34：32768 → 131072——社区大建筑（佛寺 81588 块、
     *  迷你要塞 42656 块）之前被挡在手册外；上限对齐"宏大建筑"需求，
     *  建造时间按 0.15 秒/块：8 万块 ≈ 3.4 小时）v1.5.39：→ 1000000——
     *  现代红石智能住宅 547686 块入库；多女仆共享计划 + 放置间隔 3 tick
     *  加速后，10 名女仆 ≈ 2 小时/54 万块，娱乐玩法可接受 */

    /** 结构文件蓝图黑名单（无论如何都不允许女仆放置） */
    public static final Set<String> FORBIDDEN = new HashSet<>();

    static {
        FORBIDDEN.add("minecraft:air");
        FORBIDDEN.add("minecraft:bedrock");
        FORBIDDEN.add("minecraft:barrier");
        FORBIDDEN.add("minecraft:command_block");
        FORBIDDEN.add("minecraft:chain_command_block");
        FORBIDDEN.add("minecraft:repeating_command_block");
        FORBIDDEN.add("minecraft:structure_block");
        FORBIDDEN.add("minecraft:structure_void");
        FORBIDDEN.add("minecraft:jigsaw");
        FORBIDDEN.add("minecraft:end_portal");
        FORBIDDEN.add("minecraft:end_gateway");
        FORBIDDEN.add("minecraft:end_portal_frame");
        FORBIDDEN.add("minecraft:nether_portal");
        FORBIDDEN.add("minecraft:water");
        FORBIDDEN.add("minecraft:lava");
        FORBIDDEN.add("minecraft:bubble_column");
        FORBIDDEN.add("minecraft:moving_piston");
        FORBIDDEN.add("minecraft:piston_head");
        FORBIDDEN.add("minecraft:fire");
    }

    /** 外部蓝图支持的文件扩展名（v1.5.37：+ .schematic——Planet Minecraft 标准格式；
     *  v1.5.222：+ .zip——打包蓝图，解压提取内部建筑文件） */
    private static final List<String> EXTENSIONS = List.of(".json", ".nbt", ".snbt", ".litematic", ".schem", ".schematic", ".zip");
    /** v1.5.28：手册目录显示上限 = 结构上限（>32768 的蓝图 parseStructure 直接返回 null
     *  不会注册，因此所有已注册蓝图都能在手册显示；describe/needs 已有缓存，大蓝图不再卡顿。
     *  旧版 5000 导致巨型猫咪雕像/骑士雕像等大建筑从手册消失） */
    /** 格式转换器体积上限（v1.5.102：从配置面板读取 build.structureMaxVolume，litematic/schem 单区域/整体） */

    /**
     * 可覆盖的自然地形（v1.5.13 障碍物预检：建造区域内这类方块不算障碍物，
     * 女仆直接覆盖；树/房子/箱子等其他方块视为障碍物 → 气泡提示并拒绝建造）。
     */
    public static final Set<String> ALLOWED_GROUND = new HashSet<>();

    static {
        ALLOWED_GROUND.add("minecraft:grass_block");
        ALLOWED_GROUND.add("minecraft:dirt");
        ALLOWED_GROUND.add("minecraft:coarse_dirt");
        ALLOWED_GROUND.add("minecraft:rooted_dirt");
        ALLOWED_GROUND.add("minecraft:podzol");
        ALLOWED_GROUND.add("minecraft:mycelium");
        ALLOWED_GROUND.add("minecraft:stone");
        ALLOWED_GROUND.add("minecraft:andesite");
        ALLOWED_GROUND.add("minecraft:granite");
        ALLOWED_GROUND.add("minecraft:diorite");
        ALLOWED_GROUND.add("minecraft:deepslate");
        ALLOWED_GROUND.add("minecraft:tuff");
        ALLOWED_GROUND.add("minecraft:sand");
        ALLOWED_GROUND.add("minecraft:red_sand");
        ALLOWED_GROUND.add("minecraft:gravel");
        ALLOWED_GROUND.add("minecraft:snow_block");
        ALLOWED_GROUND.add("minecraft:moss_block");
        ALLOWED_GROUND.add("minecraft:mud");
        ALLOWED_GROUND.add("minecraft:water");
        ALLOWED_GROUND.add("minecraft:lava");
        ALLOWED_GROUND.add("minecraft:air");
    }

    /** v1.5.58：建造破坏黑名单——这些方块女仆不可拆（基岩/命令方块/结构方块等关键方块） */
    public static final Set<String> UNBREAKABLE = new HashSet<>();

    /**
     * v1.5.80：地形方块（自然生成、非建筑结构）——世界提取硬过滤（skipWorldBlock）
     * 与"底部还原区"逐层占比判定（trimTerrainLayers）共用。
     * v1.5.252l：剔除 stone/deepslate/sandstone 等【双用途建材】——天安门事件根因：
     * 世界提取硬过滤把石头建筑主体当"地形"砍掉（天安门城楼只剩 32 块非石头方块）。
     * 建材与山体无法简单区分，提取时保留（山体石头一并提取，建筑优先完整）；
     * 纯地形（泥土/沙/草地/矿石/基岩/雪泥黏土等）才过滤与压缩。
     */
    public static final Set<String> TERRAIN_BLOCKS = Set.of(
            "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium",
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
            "minecraft:snow_block", "minecraft:mud", "minecraft:clay",
            "minecraft:bedrock",
            "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
            "minecraft:redstone_ore", "minecraft:copper_ore", "minecraft:lapis_ore",
            "minecraft:diamond_ore", "minecraft:emerald_ore",
            "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore",
            "minecraft:deepslate_gold_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:deepslate_copper_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:deepslate_diamond_ore", "minecraft:deepslate_emerald_ore"
    );
    static {
        UNBREAKABLE.add("minecraft:bedrock");
        UNBREAKABLE.add("minecraft:command_block");
        UNBREAKABLE.add("minecraft:chain_command_block");
        UNBREAKABLE.add("minecraft:repeating_command_block");
        UNBREAKABLE.add("minecraft:barrier");
        UNBREAKABLE.add("minecraft:structure_block");
        UNBREAKABLE.add("minecraft:jigsaw");
        UNBREAKABLE.add("minecraft:spawner");
        UNBREAKABLE.add("minecraft:end_portal");
        UNBREAKABLE.add("minecraft:end_portal_frame");
        UNBREAKABLE.add("minecraft:end_gateway");
        UNBREAKABLE.add("minecraft:dragon_egg");
        UNBREAKABLE.add("minecraft:nether_portal");
        UNBREAKABLE.add("minecraft:reinforced_deepslate");
        UNBREAKABLE.add("minecraft:light");
        UNBREAKABLE.add("minecraft:structure_void");
        UNBREAKABLE.add("minecraft:budding_amethyst");
    }

    /** v1.5.58：该方块是否可被建造女仆拆掉（黑名单外的方块）——"位置不对就破坏重建" */
    public static boolean canBreak(Block block) {
        if (block == null || block == net.minecraft.world.level.block.Blocks.f_50016_) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && !UNBREAKABLE.contains(id.toString());
    }

    /** 建筑方块白名单（方块 id） */
    public static final Set<String> WHITELIST = new HashSet<>();

    static {
        // 木板/原木/树木
        add("oak_planks"); add("spruce_planks"); add("birch_planks"); add("dark_oak_planks");
        add("oak_log"); add("spruce_log"); add("birch_log"); add("dark_oak_log");
        add("oak_leaves"); add("spruce_leaves");
        // 石/砖
        add("stone"); add("stone_bricks"); add("cracked_stone_bricks"); add("mossy_stone_bricks");
        add("cobblestone"); add("mossy_cobblestone"); add("bricks");
        add("smooth_stone"); add("polished_andesite"); add("polished_granite"); add("polished_diorite");
        // 玻璃
        add("glass"); add("glass_pane"); add("white_stained_glass");
        // 门/楼梯/台阶/栅栏
        add("oak_door"); add("spruce_door"); add("dark_oak_door");
        add("oak_stairs"); add("spruce_stairs"); add("stone_brick_stairs");
        add("oak_slab"); add("spruce_slab"); add("stone_brick_slab");
        add("oak_fence"); add("spruce_fence"); add("oak_fence_gate");
        // 照明/装饰
        add("torch"); add("lantern"); add("sea_lantern"); add("glowstone");
        add("white_wool"); add("red_wool"); add("blue_wool"); add("green_wool"); add("yellow_wool");
        add("white_carpet"); add("red_carpet");
        // 生活
        add("chest"); add("crafting_table"); add("furnace"); add("bookshelf");
        add("grass_block"); add("dirt"); add("gravel"); add("sand");
        add("flower_pot"); add("poppy"); add("dandelion"); add("azure_bluet");
        add("oak_planks_sign");
        // v1.5.311：红石族（配合 recalcRedstone v2 完成唤醒 + JSON state 支持——
        // LLM 现场生成的简单红石机器现在可以真实运行；v1.5.300 删机器是唤醒失效
        // 所致，根因已修复。全部有对应物品；redstone_wire 走特例→红石粉）
        add("redstone_wire"); add("redstone_torch"); add("redstone_block"); add("redstone_lamp");
        add("lever"); add("stone_button"); add("oak_button");
        add("repeater"); add("comparator"); add("observer");
        add("piston"); add("sticky_piston");
        add("dispenser"); add("dropper"); add("hopper");
        add("iron_door"); add("stone_pressure_plate"); add("oak_pressure_plate");
        add("powered_rail"); add("detector_rail"); add("rail");
        add("target"); add("note_block"); add("trapped_chest");
    }

    private static void add(String id) {
        WHITELIST.add("minecraft:" + id);
    }

    /**
     * 等价材料族：蓝图要求的方块可用族内任意物品替代。
     * 例：蓝图要橡木木板，背包里的云杉/白桦/深色橡木木板都算数（v1.5.2 起）。
     */
    public static final Map<String, Set<String>> EQUIVALENT_GROUPS = new HashMap<>();

    static {
        addGroup("minecraft:oak_planks", "minecraft:oak_planks", "minecraft:spruce_planks",
                "minecraft:birch_planks", "minecraft:jungle_planks", "minecraft:acacia_planks",
                "minecraft:dark_oak_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
                "minecraft:bamboo_planks", "minecraft:crimson_planks", "minecraft:warped_planks");
        addGroup("minecraft:oak_log", "minecraft:oak_log", "minecraft:spruce_log",
                "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log",
                "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
                "minecraft:oak_wood", "minecraft:spruce_wood", "minecraft:birch_wood",
                "minecraft:jungle_wood", "minecraft:acacia_wood", "minecraft:dark_oak_wood");
        addGroup("minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
                "minecraft:cracked_stone_bricks", "minecraft:deepslate_bricks",
                "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_tiles");
        addGroup("minecraft:cobblestone", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
                "minecraft:stone", "minecraft:smooth_stone", "minecraft:deepslate");
        addGroup("minecraft:glass", "minecraft:glass", "minecraft:glass_pane",
                "minecraft:white_stained_glass", "minecraft:white_stained_glass_pane");
        addGroup("minecraft:oak_door", "minecraft:oak_door", "minecraft:spruce_door",
                "minecraft:birch_door", "minecraft:jungle_door", "minecraft:acacia_door",
                "minecraft:dark_oak_door", "minecraft:mangrove_door", "minecraft:cherry_door",
                "minecraft:iron_door");
        // v1.5.55：地形等价——草方块传播把泥土变草后，已建判定不再误判"未建"，
        // 女仆不会反复重放泥土与草方块互搏（叠加态闪烁、进度卡死）
        addGroup("minecraft:dirt", "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt",
                "minecraft:podzol", "minecraft:mycelium", "minecraft:rooted_dirt", "minecraft:mud",
                "minecraft:moss_block");
        // v1.5.56：草方块反向等价——图纸要草皮、位置被草退化/传播变化后判定"已建"，
        // 不再反复重放草方块（草被压变泥土/被传播覆盖是 MC 正常机制，重放也没用）
        addGroup("minecraft:grass_block", "minecraft:grass_block", "minecraft:dirt",
                "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:mycelium",
                "minecraft:rooted_dirt", "minecraft:moss_block");
        // v1.5.254：台阶/楼梯等价族（缺料替代"先同族"覆盖半格/一格类）
        addGroup("minecraft:oak_slab", "minecraft:oak_slab", "minecraft:spruce_slab",
                "minecraft:birch_slab", "minecraft:jungle_slab", "minecraft:acacia_slab",
                "minecraft:dark_oak_slab", "minecraft:mangrove_slab", "minecraft:cherry_slab",
                "minecraft:bamboo_slab", "minecraft:crimson_slab", "minecraft:warped_slab");
        addGroup("minecraft:stone_slab", "minecraft:stone_slab", "minecraft:sandstone_slab",
                "minecraft:cobblestone_slab", "minecraft:brick_slab", "minecraft:stone_brick_slab",
                "minecraft:nether_brick_slab", "minecraft:quartz_slab", "minecraft:red_sandstone_slab",
                "minecraft:purpur_slab", "minecraft:smooth_stone_slab", "minecraft:smooth_sandstone_slab",
                "minecraft:smooth_quartz_slab", "minecraft:smooth_red_sandstone_slab",
                "minecraft:deepslate_slab", "minecraft:deepslate_brick_slab",
                "minecraft:deepslate_tile_slab", "minecraft:polished_deepslate_slab",
                "minecraft:cut_sandstone_slab", "minecraft:cut_red_sandstone_slab",
                "minecraft:cobbled_deepslate_slab", "minecraft:blackstone_slab",
                "minecraft:polished_blackstone_slab", "minecraft:polished_blackstone_brick_slab",
                "minecraft:end_stone_brick_slab", "minecraft:mossy_cobblestone_slab",
                "minecraft:mossy_stone_brick_slab", "minecraft:prismarine_slab",
                "minecraft:prismarine_brick_slab", "minecraft:dark_prismarine_slab",
                "minecraft:purpur_slab", "minecraft:granite_slab", "minecraft:polished_granite_slab",
                "minecraft:diorite_slab", "minecraft:polished_diorite_slab", "minecraft:andesite_slab",
                "minecraft:polished_andesite_slab", "minecraft:oxidized_cut_copper_slab",
                "minecraft:weathered_cut_copper_slab", "minecraft:exposed_cut_copper_slab",
                "minecraft:cut_copper_slab");
        addGroup("minecraft:oak_stairs", "minecraft:oak_stairs", "minecraft:spruce_stairs",
                "minecraft:birch_stairs", "minecraft:jungle_stairs", "minecraft:acacia_stairs",
                "minecraft:dark_oak_stairs", "minecraft:mangrove_stairs", "minecraft:cherry_stairs",
                "minecraft:bamboo_stairs", "minecraft:crimson_stairs", "minecraft:warped_stairs");
        addGroup("minecraft:stone_brick_stairs", "minecraft:stone_brick_stairs",
                "minecraft:cobblestone_stairs", "minecraft:brick_stairs",
                "minecraft:nether_brick_stairs", "minecraft:sandstone_stairs",
                "minecraft:quartz_stairs", "minecraft:red_sandstone_stairs",
                "minecraft:purpur_stairs", "minecraft:deepslate_brick_stairs",
                "minecraft:deepslate_tile_stairs", "minecraft:polished_deepslate_stairs",
                "minecraft:blackstone_stairs", "minecraft:polished_blackstone_brick_stairs",
                "minecraft:end_stone_brick_stairs", "minecraft:mossy_cobblestone_stairs",
                "minecraft:mossy_stone_brick_stairs", "minecraft:prismarine_stairs",
                "minecraft:prismarine_brick_stairs", "minecraft:dark_prismarine_stairs",
                "minecraft:granite_stairs", "minecraft:polished_granite_stairs",
                "minecraft:diorite_stairs", "minecraft:polished_diorite_stairs",
                "minecraft:andesite_stairs", "minecraft:polished_andesite_stairs");
    }

    private static void addGroup(String key, String... members) {
        Set<String> group = new HashSet<>();
        for (String member : members) {
            group.add(member);
        }
        EQUIVALENT_GROUPS.put(key, group);
    }

    /**
     * v1.5.156："已建"判定的等价白名单——只限地形类组（泥土/草方块传播退化互搏，
     * v1.5.55/56 场景）。建材类组（石砖/圆石/木板/门/玻璃等）"已建"只认同方块：
     * 用户日志实证——瞭望塔下达到深板岩层（远古城市），34 个石砖步骤被
     * deepslate_bricks 等价匹配为"已建"→ 0.25 秒"建造完成"。材料消耗
     * （背包匹配）仍用完整等价（isEquivalent），不受影响。
     */
    private static final Set<String> BUILT_EQUIV_GROUPS = Set.of("minecraft:dirt", "minecraft:grass_block");

    /** v1.5.156：已建判定用等价（仅地形组）；建材组一律返回 false（只认同方块） */
    public static boolean isBuiltEquivalent(String blockId, Block actual) {
        if (!BUILT_EQUIV_GROUPS.contains(blockId)) {
            return false;
        }
        return isEquivalent(blockId, actual);
    }

    /** 蓝图要求的方块（blockId）与目标位置的方块（actual）是否等价（同族） */
    public static boolean isEquivalent(String blockId, Block actual) {
        if (actual == null) {
            return false;
        }
        ResourceLocation actualId = ForgeRegistries.BLOCKS.getKey(actual);
        if (actualId == null) {
            return false;
        }
        Set<String> group = EQUIVALENT_GROUPS.get(blockId);
        return group != null && group.contains(actualId.toString());
    }

    private BlueprintLib() {
    }

    /** 内置蓝图：id → 步骤列表（v1.5.366：3 小屋 + 12 新结构建筑 + 3 别墅 + 1 熔炉 =
     *  19 个，程序化生成——BuiltinHouses；方块 500~5000，材料生存易得） */
    public static List<String> getBuiltIn(String id) {
        return com.maidsmart.build.BuiltinHouses.get(id);
    }

    /** 内置蓝图目录（v1.5.366：19 个内置——3 小屋 + 12 新结构建筑 + 3 别墅 + 熔炉，
     *  供 smart_build_list 与提示词使用） */
    public static String buildCatalog() {
        StringBuilder sb = new StringBuilder();
        sb.append("内置蓝图：\n");
        for (String id : BUILT_IN_NAMES.keySet()) {
            List<String> steps = getBuiltIn(id);
            if (steps != null && !steps.isEmpty()) {
                sb.append(id).append(" — ").append(BUILT_IN_NAMES.get(id))
                        .append("（").append(steps.size()).append(" 块）\n");
            }
        }
        scanExternalBlueprints();
        if (!EXTERNAL.isEmpty()) {
            sb.append("\n外部蓝图（config/maid_smart/blueprints 或 存档 schematics/，支持 .json/.nbt/.snbt/.litematic/.schem）：\n");
            for (Map.Entry<String, List<String>> entry : EXTERNAL.entrySet()) {
                sb.append(entry.getKey()).append(" — ")
                        .append(EXTERNAL_NAMES.getOrDefault(entry.getKey(), entry.getKey()))
                        .append("（").append(describe(entry.getKey(), entry.getValue())).append("）\n");
            }
        } else {
            sb.append("\n（当前没有外部蓝图——把 .nbt/.snbt/.schem 等蓝图文件放进 config/maid_smart/blueprints 即可）");
        }
        return sb.toString();
    }

    /** 内置蓝图中文名（v1.5.271：15 生存小屋 + 10 别墅；v1.5.366：删雷同 → 3 小屋 +
     *  12 新结构建筑 + 3 别墅 + 1 熔炉 = 19；v1.5.369：+ 6 座农场 = 25） */
    private static final Map<String, String> BUILT_IN_NAMES = new HashMap<>();

    static {
        for (String id : new String[]{
                "maid_smart:house_oak_log", "maid_smart:house_sandstone",
                "maid_smart:house_snowy",
                // v1.5.366：新增 12 种不同结构建筑（500~5000 块）
                "maid_smart:house_aframe", "maid_smart:house_watchtower",
                "maid_smart:house_lighthouse", "maid_smart:house_courtyard",
                "maid_smart:house_barn", "maid_smart:house_stilt",
                "maid_smart:house_windmill", "maid_smart:house_bunker",
                "maid_smart:house_tree", "maid_smart:house_hillside",
                "maid_smart:house_dual", "maid_smart:house_gatehouse",
                // v1.5.366：别墅保留 3 种风格（删 7 个同款盒）
                "maid_smart:villa_stone", "maid_smart:villa_glass",
                "maid_smart:villa_terracotta",
                // v1.5.300：红石机器只留自动熔炉组（用户："红石机器基本都不能用，
                // 先全都删了，只保留一个自动熔炉组"——甘蔗机/南瓜机/自动灯删除）
                "maid_smart:machine_furnace_array",
                // v1.5.369：6 座生存农场（仿 MCBlanky《10 个 1 分钟农场》）
                "maid_smart:farm_cactus", "maid_smart:farm_superfurnace",
                "maid_smart:farm_lavafountain", "maid_smart:farm_crop",
                "maid_smart:farm_tree", "maid_smart:farm_chicken",
        }) {
            BUILT_IN_NAMES.put(id, com.maidsmart.build.BuiltinHouses.nameOf(id));
        }
    }

    /** v1.5.28：外部 .snbt 文件名 → 中文显示名（手册/LLM 都用中文；未收录的文件名兜底用原名） */
    private static final Map<String, String> EXT_CN_NAMES = new HashMap<>();

    static {
        EXT_CN_NAMES.put("ancient_tree", "远古巨树");
        EXT_CN_NAMES.put("barn", "谷仓");
        EXT_CN_NAMES.put("castle_keep", "城堡主楼");
        EXT_CN_NAMES.put("chapel", "礼拜堂");
        EXT_CN_NAMES.put("chinese_courtyard", "中式庭院");
        EXT_CN_NAMES.put("cliff_camp", "悬崖营地");
        EXT_CN_NAMES.put("colossal_knight", "巨型骑士雕像");
        EXT_CN_NAMES.put("command_center", "指挥中心");
        EXT_CN_NAMES.put("cottage", "乡间小屋");
        EXT_CN_NAMES.put("crystal_palace", "水晶宫殿");
        EXT_CN_NAMES.put("data_center", "数据中心");
        EXT_CN_NAMES.put("desert_hut", "沙漠小屋");
        EXT_CN_NAMES.put("enchanted_manor", "魔法庄园");
        EXT_CN_NAMES.put("farm_cabin", "农舍");
        EXT_CN_NAMES.put("fisherman_hut", "渔夫小屋");
        EXT_CN_NAMES.put("forge_shed", "锻造工棚");
        EXT_CN_NAMES.put("gatehouse", "门楼");
        EXT_CN_NAMES.put("giant_cat_statue", "巨型猫咪雕像");
        EXT_CN_NAMES.put("golden_temple", "黄金神庙");
        EXT_CN_NAMES.put("gothic_chapel", "哥特教堂");
        EXT_CN_NAMES.put("high_tech_lab", "高科技实验室");
        EXT_CN_NAMES.put("jade_garden_house", "翡翠花园宅邸");
        EXT_CN_NAMES.put("lighthouse", "灯塔");
        EXT_CN_NAMES.put("lumberjack_cabin", "伐木小屋");
        EXT_CN_NAMES.put("luxury_villa", "豪华别墅");
        EXT_CN_NAMES.put("marble_villa", "大理石别墅");
        EXT_CN_NAMES.put("miner_shack", "矿工棚屋");
        EXT_CN_NAMES.put("modern_villa", "现代别墅");
        EXT_CN_NAMES.put("nomad_tent", "游牧帐篷");
        EXT_CN_NAMES.put("observatory", "天文台");
        EXT_CN_NAMES.put("oriental_dragon", "东方巨龙");
        EXT_CN_NAMES.put("oriental_palace", "东方宫殿");
        EXT_CN_NAMES.put("reactor_core", "反应堆核心");
        EXT_CN_NAMES.put("renaissance_mansion", "文艺复兴府邸");
        EXT_CN_NAMES.put("root_cellar", "酒窖");
        EXT_CN_NAMES.put("round_tower", "圆塔");
        EXT_CN_NAMES.put("royal_palace", "皇家宫殿");
        EXT_CN_NAMES.put("sci_fi_bunker", "科幻地堡");
        EXT_CN_NAMES.put("seaside_house", "海景小屋");
        EXT_CN_NAMES.put("snow_cabin", "雪屋");
        EXT_CN_NAMES.put("solar_energy_tower", "太阳能塔");
        EXT_CN_NAMES.put("space_rocket", "太空火箭");
        EXT_CN_NAMES.put("survival_hut", "生存小屋");
        EXT_CN_NAMES.put("teleporter_hub", "传送枢纽");
        EXT_CN_NAMES.put("treehouse", "树屋");
        EXT_CN_NAMES.put("watchtower", "瞭望塔");
        EXT_CN_NAMES.put("wizard_tower", "巫师塔");
        // v1.5.30：预制大型蓝图（内置资源，首次启动自动复制到 blueprints 目录）
        EXT_CN_NAMES.put("high_tech_villa", "高科技别墅");
        EXT_CN_NAMES.put("seaside_villa", "海景别墅");
        EXT_CN_NAMES.put("grand_palace", "大型宫殿");
        EXT_CN_NAMES.put("skyscraper", "摩天大楼");
        // v1.5.31：大型狸花猫雕像
        EXT_CN_NAMES.put("tabby_cat_statue", "大型狸花猫雕像");
        // v1.5.34：超大规模预制
        EXT_CN_NAMES.put("mega_castle", "巨型城堡");
        EXT_CN_NAMES.put("mega_pyramid", "巨型金字塔");
        EXT_CN_NAMES.put("mega_colosseum", "巨型竞技场");
        // v1.5.35：巨型骑士雕像
        EXT_CN_NAMES.put("mega_knight_statue", "巨型骑士雕像");
        // v1.5.38：生存实用房（材料简单/建造快速/多层隐蔽/光照/机动性）
        EXT_CN_NAMES.put("survival_woodcabin", "林间隐舍");
        EXT_CN_NAMES.put("survival_bunker", "地下避难所");
        EXT_CN_NAMES.put("survival_watchtower", "哨塔居");
        // v1.5.39：PM 下载的现代红石智能住宅（547686 块，上限提升后入库）
        EXT_CN_NAMES.put("modernredstonesmarthouse8649399", "现代红石智能住宅");
        // v1.5.252k：挖空版已删除——挖空设计导致内部大量附着物悬空，触发强制补支撑
        // 垫的支撑块把下部红石设施卡死（用户实测），仅保留原版
    }

    /**
     * 外部蓝图（v1.5.11 JSON；v1.5.12 起 .nbt/.snbt 结构文件，numen 式）。
     * 目录：config/maid_smart/blueprints/ 与 存档 schematics/（放入即用，增量扫描，
     * 文件变更后自动重新解析，无需重启）。
     * - .json：LLM JSON 格式 {"name":"小屋","blocks":[...]}
     * - .nbt：结构方块导出 / 标准结构文件
     * - .snbt：文本版 NBT（可手写/编辑）
     * id 均为 maid_smart_ext:文件名（去扩展名）。
     */
    private static final Map<String, List<String>> EXTERNAL = new HashMap<>();
    private static final Map<String, String> EXTERNAL_NAMES = new HashMap<>();
    /** 文件最后修改时间（增量重扫：mtime 变化才重新解析） */
    private static final Map<String, Long> EXTERNAL_MTIMES = new HashMap<>();
    /** v1.5.312：外部目录扫描节流——手册打开时 getBlueprint/describe 会对每个目录条目
     *  触发 scanExternalBlueprints，失败文件（损坏 zip / 世界 zip 提取）每次重扫都要
     *  重新解析（世界 zip 提取 0.5s+），几十个条目叠加 → 服务端主线程卡死。
     *  3 秒内只真正扫描一次，其余调用直接返回（读内存缓存）。 */
    private static volatile long LAST_EXTERNAL_SCAN_MS = 0L;
    private static final long EXTERNAL_SCAN_INTERVAL_MS = 3000L;
    /** id → 文件路径（旋转时需要重新读取解析） */
    private static final Map<String, java.nio.file.Path> EXTERNAL_PATHS = new HashMap<>();
    /** 存档根（schematics/ 目录扫描用；服务端启动时注入，停止时清空） */
    private static volatile net.minecraft.server.MinecraftServer SERVER = null;

    public static void setServer(net.minecraft.server.MinecraftServer server) {
        SERVER = server;
        EXTERNAL.clear();
        EXTERNAL_NAMES.clear();
        EXTERNAL_MTIMES.clear();
        EXTERNAL_PATHS.clear();
        NEEDS_CACHE.clear();
        NEEDS_MTIME.clear();
        DESCRIBE_CACHE.clear();
        SIZE_CACHE.clear();
        if (server != null) {
            installBuiltinBlueprints();
            // v1.5.25g：服务端启动时预热外部蓝图需求缓存——手册右击不再首次卡 5 秒
            // （countNeeds 对每个蓝图遍历上万步骤在启动时完成，右击只读缓存）
            warmupNeedsCache();
        }
    }

    /** v1.5.25g：启动时预热所有外部蓝图的材料需求（countNeedsCached 首次计算） */
    private static void warmupNeedsCache() {
        scanExternalBlueprints();
        for (Map.Entry<String, List<String>> e : EXTERNAL.entrySet()) {
            countNeedsCached(e.getKey(), e.getValue());
            // v1.5.375：一并预热摘要与占地尺寸——手册打开时 buildCatalogEntries 对
            // 每个外部蓝图调 describe、openFor 对每个调 blueprintSize，旧版只在
            // 首次打开时现算（8000+ 蓝图 × 遍历全部步骤 = 主线程卡死）；这里在
            // 服务端启动（读世界阶段）预热完，打开手册纯读缓存
            describe(e.getKey(), e.getValue());
            blueprintSizeCached(e.getKey(), e.getValue());
        }
        // v1.5.375：内置预设同样预热（小屋/农场/别墅，量小但一并覆盖）
        for (String id : BUILT_IN_NAMES.keySet()) {
            List<String> steps = getBuiltIn(id);
            if (steps != null && !steps.isEmpty()) {
                countNeedsCached(id, steps);
                describe(id, steps);
                blueprintSizeCached(id, steps);
            }
        }
    }

    /** 内置预制建筑（v1.5.15，jar 内 assets/maid_smart/builtin_blueprints/*.snbt）
     *  v1.5.270：移除全部 <2000 块的（用户要求"删除所有方块数量小于2000的建筑"）
     *  ——只剩 8 个大型预制（2 千 ~ 5 万块级） */
    private static final String[] BUILTIN_BLUEPRINT_FILES = {
            "seaside_villa.snbt", "grand_palace.snbt", "skyscraper.snbt",
            "tabby_cat_statue.snbt",
            "mega_castle.snbt", "mega_pyramid.snbt", "mega_colosseum.snbt",
            "mega_knight_statue.snbt"
    };

    /**
     * 首次启动时把预制精美建筑复制到 config/maid_smart/blueprints/（已存在则跳过，
     * 玩家可自由修改/删除）。复制后由增量扫描自动注册为外部蓝图。
     */
    private static void installBuiltinBlueprints() {
        try {
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            for (String file : BUILTIN_BLUEPRINT_FILES) {
                java.nio.file.Path out = dir.resolve(file);
                if (java.nio.file.Files.exists(out)) {
                    continue;
                }
                try (java.io.InputStream in = BlueprintLib.class.getClassLoader()
                        .getResourceAsStream("assets/maid_smart/builtin_blueprints/" + file)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, out);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 扫描全部外部蓝图目录（增量：仅重读变化的文件；删除的文件自动移除） */
    /** v1.5.220：手册"导入建筑"——把外部文件复制到 config/maid_smart/blueprints/，
     *  用与扫描完全相同的解析流程校验（版本兼容粗检：解析失败 = 格式/版本不兼容，
     *  提示可能损毁并删除副本），成功后重新扫描注册。
     *  返回中文结果文本（成功含方块数 / 失败原因），服务端回玩家聊天框。 */
    public static String importBuildFile(String path) {
        try {
            java.io.File src = new java.io.File(path);
            if (!src.isFile()) {
                return "导入失败: 文件不存在（" + path + "）";
            }
            String lower = src.getName().toLowerCase(java.util.Locale.ROOT);
            String ext = null;
            for (String e : EXTENSIONS) {
                if (lower.endsWith(e)) {
                    ext = e;
                    break;
                }
            }
            if (ext == null) {
                return "导入失败: 不支持的格式（支持 .schem/.litematic/.nbt/.snbt/.schematic/.json）";
            }
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            // 审计1.5.384：文件名清洗——客户端可控文件名含路径分隔符/.. 可逃逸
            // blueprints 目录（服务器端已 OP 门槛，此处纵深防御）
            String fname = src.getName();
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")
                    || fname.equals(".")) {
                return "导入失败: 文件名不合法（" + fname + "）";
            }
            java.io.File dst = new java.io.File(dir.toFile(), fname);
            if (dst.exists()) {
                return "导入失败: 同名文件已存在（" + dst.getAbsolutePath()
                        + "，可直接使用，无需重复导入）";
            }
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
            try {
                List<String> steps = loadExternalFile(dst.toPath(), ext);
                if (steps == null || steps.isEmpty()) {
                    java.nio.file.Files.deleteIfExists(dst.toPath());
                    return "导入失败: 文件无法解析（可能是版本不兼容——蓝图需与当前 "
                            + "MC 1.20.1 一致，否则建筑可能损毁；已删除无效副本）";
                }
                scanExternalBlueprints(true);
                return "导入成功: " + fname + "（" + steps.size() + " 块，"
                        + "已注册，可在手册建造目录找到）";
            } catch (Exception e) {
                try {
                    java.nio.file.Files.deleteIfExists(dst.toPath());
                } catch (Exception ignored) {
                }
                return "导入失败: 文件无法解析（" + e.getClass().getSimpleName()
                        + "——可能是版本不兼容，蓝图需与当前 MC 1.20.1 一致；已删除无效副本）";
            }
        } catch (Exception e) {
            return "导入失败: " + e.getMessage();
        }
    }

    /** v1.5.224：手册"导入世界地图"——只接受 .zip（世界存档压缩包或纯建筑包），
     *  复制到 blueprints 目录 → 解析（世界存档自动提取建筑）→ 注册。
     *  返回详细中文结果（提取块数/尺寸 或 失败原因）。 */
    public static String importWorldFile(String path) {
        try {
            java.io.File src = new java.io.File(path);
            if (!src.isFile()) {
                return "导入失败: 文件不存在（" + path + "）";
            }
            String lower = src.getName().toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".zip")) {
                return "导入失败: 世界地图导入只支持 .zip 压缩包（世界存档或建筑包）";
            }
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            // 审计1.5.384：文件名清洗（与 importBuildFile 一致）
            String fname = src.getName();
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")
                    || fname.equals(".")) {
                return "导入失败: 文件名不合法（" + fname + "）";
            }
            java.io.File dst = new java.io.File(dir.toFile(), fname);
            if (dst.exists()) {
                return "导入失败: 同名文件已存在（" + dst.getAbsolutePath()
                        + "，可直接使用，无需重复导入）";
            }
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
            List<String> steps;
            try {
                steps = loadExternalFile(dst.toPath(), ".zip");
            } catch (Exception e) {
                java.nio.file.Files.deleteIfExists(dst.toPath());
                return "导入失败: 文件无法解析（" + e.getClass().getSimpleName()
                        + "——可能是版本不兼容，已删除无效副本）";
            }
            if (steps == null || steps.isEmpty()) {
                java.nio.file.Files.deleteIfExists(dst.toPath());
                return "导入失败: 未提取到任何建筑（zip 需为世界存档——含 level.dat 与 "
                        + "region/*.mca——或打包了 .schem/.litematic/.nbt 等建筑文件；"
                        + "世界存档会以玩家最后位置为中心自动提取，已删除无效副本）";
            }
            int[] size = blueprintSize(steps);
            scanExternalBlueprints(true);
            return "导入成功: " + src.getName() + "（提取 " + steps.size() + " 块，"
                    + "尺寸 " + size[0] + "×" + size[1] + "×" + size[2]
                    + "，已注册，可在手册建造目录找到）";
        } catch (Exception e) {
            return "导入失败: " + e.getMessage();
        }
    }

    public static void scanExternalBlueprints() {
        scanExternalBlueprints(false);
    }

    /** v1.5.312：force=true 用于导入流程（导入后必须立即刷新目录，不被节流吞掉） */
    public static void scanExternalBlueprints(boolean force) {
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - LAST_EXTERNAL_SCAN_MS < EXTERNAL_SCAN_INTERVAL_MS) {
            return;
        }
        LAST_EXTERNAL_SCAN_MS = nowMs;
        List<java.nio.file.Path> dirs = new ArrayList<>();
        try {
            java.nio.file.Path cfg = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            if (java.nio.file.Files.isDirectory(cfg)) {
                dirs.add(cfg);
            }
        } catch (Exception ignored) {
        }
        if (SERVER != null) {
            try {
                java.nio.file.Path sched = SERVER.m_6237_().toPath().resolve("schematics");
                if (java.nio.file.Files.isDirectory(sched)) {
                    dirs.add(sched);
                }
            } catch (Exception ignored) {
            }
        }
        // v1.5.25 诊断日志：确认扫描到的目录与文件数（帮助排查"手册看不到蓝图"）
        int[] registered = {0};
        // 已扫描文件集合（用于删除检测）
        Set<String> seen = new HashSet<>();
        for (java.nio.file.Path dir : dirs) {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                files.forEach(p -> {
                    String fileName = p.getFileName().toString();
                    String lower = fileName.toLowerCase(java.util.Locale.ROOT);
                    String ext = null;
                    for (String e : EXTENSIONS) {
                        if (lower.endsWith(e)) {
                            ext = e;
                            break;
                        }
                    }
                    if (ext == null) {
                        return;
                    }
                    String id = "maid_smart_ext:" + fileName.substring(0, fileName.length() - ext.length());
                    seen.add(id);
                    try {
                        long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                        if (mtime == EXTERNAL_MTIMES.getOrDefault(id, -1L)) {
                            return; // 未变化
                        }
                        List<String> steps = loadExternalFile(p, ext);
                        if (steps != null && !steps.isEmpty()) {
                            // v1.5.25g：不再在扫描阶段跳过超大蓝图——LLM smart_build 走
                            // getBlueprint/EXTERNAL 也需要大建筑（之前 5000 限制导致 LLM
                            // 也建不了）。大小限制只作用于手册目录显示（buildCatalogEntries）。
                            EXTERNAL.put(id, steps);
                            EXTERNAL_NAMES.put(id, externalName(p, ext, steps));
                            EXTERNAL_PATHS.put(id, p);
                            EXTERNAL_MTIMES.put(id, mtime);
                            // v1.5.25d：文件变化 → 清材料需求缓存
                            NEEDS_CACHE.remove(id);
                            NEEDS_MTIME.remove(id);
                            DESCRIBE_CACHE.remove(id);
                            SIZE_CACHE.remove(id);
                            registered[0]++;
                        } else {
                            EXTERNAL.remove(id);
                            EXTERNAL_NAMES.remove(id);
                            EXTERNAL_PATHS.remove(id);
                            // v1.5.312：失败也记 mtime → 本次会话不再反复重扫（损坏 zip /
                            // 世界 zip 提取失败每次 0.5s+，目录循环重扫会卡死服务端主线程）；
                            // 文件被替换后 mtime 变化才会重新尝试解析。
                            EXTERNAL_MTIMES.put(id, mtime);
                            NEEDS_CACHE.remove(id);
                            NEEDS_MTIME.remove(id);
                            DESCRIBE_CACHE.remove(id);
                            SIZE_CACHE.remove(id);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
        // 删除的文件：移除缓存
        EXTERNAL.keySet().removeIf(id -> !seen.contains(id));
        EXTERNAL_NAMES.keySet().removeIf(id -> !seen.contains(id));
        EXTERNAL_PATHS.keySet().removeIf(id -> !seen.contains(id));
        EXTERNAL_MTIMES.keySet().removeIf(id -> !seen.contains(id));
        SIZE_CACHE.keySet().removeIf(id -> !seen.contains(id));
        if (registered[0] > 0) {
            // v1.5.25 诊断日志（LogUtils → 进 latest.log）
            LOGGER.info("scanExternalBlueprints: 新注册 {} 个外部蓝图，共 {} 个", registered[0], EXTERNAL.size());
        }
    }

    /** 读取并解析一个外部蓝图文件；返回 null 表示格式错误/超限 */
    private static List<String> loadExternalFile(java.nio.file.Path p, String ext) {
        try {
            if (".zip".equals(ext)) {
                String zipName = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                // v1.5.312：zip 分支失败 WARN 同样只提示一次（与 .nbt 等一致），
                // 损坏 zip 在目录里反复重扫（即使已节流到 3 秒一次）不再刷屏
                // v1.5.222：ZIP 打包蓝图——解压 zip 内所有建筑文件
                // （.schem/.litematic/.nbt/.snbt/.schematic/.json，跳过 .zip 防递归）
                // 到蓝图目录，返回第一个建筑文件的解析结果；解出的子文件会被
                // scanExternalBlueprints 各自注册（一次导入多个建筑）。
                // 防路径穿越：只取纯文件名。
                // v1.5.223：若 zip 是【世界存档】（含 level.dat/region/*.mca），
                // 世界文件解压到 蓝图目录/<zip名>/ 子目录，并尝试从中提取完整建筑
                // （以玩家最后位置为锚点解析区块）——提取成功则作为该 zip 的蓝图。
                java.nio.file.Path dir = p.getParent();
                String zipBase = p.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
                java.nio.file.Path worldDir = null;
                List<String> first = null;
                java.util.zip.ZipFile zf0 = null;
                try {
                    zf0 = new java.util.zip.ZipFile(p.toFile());
                } catch (Exception utf8Fail) {
                    // v1.5.313：中文 Windows 工具/百度网盘压缩的 zip 条目名是 GBK 编码，
                    // Java 默认按 UTF-8 解码 → "invalid CEN header (bad entry name or
                    // comment)" 打不开（豪华大别墅.zip 实测）；GBK 兜底重试。
                    try {
                        zf0 = new java.util.zip.ZipFile(p.toFile(),
                                java.nio.charset.Charset.forName("GBK"));
                    } catch (Exception gbkFail) {
                        throw new java.io.IOException("zip open failed: " + p, gbkFail);
                    }
                }
                try (java.util.zip.ZipFile zf = zf0) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry ze = en.nextElement();
                        if (ze.isDirectory()) {
                            continue;
                        }
                        String name = ze.getName();
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        boolean worldFile = lower.endsWith(".mca")
                                || lower.endsWith("level.dat")
                                || lower.endsWith("level.dat_old")
                                || lower.contains("/playerdata/")
                                || lower.endsWith("/level.dat");
                        if (worldFile) {
                            // 世界存档文件 → 解压到子目录（保持相对路径，防穿越）
                            if (worldDir == null) {
                                worldDir = dir.resolve(zipBase);
                            }
                            java.nio.file.Path out = worldDir.resolve(name).normalize();
                            if (!out.startsWith(worldDir)) {
                                continue;
                            }
                            java.nio.file.Files.createDirectories(out.getParent());
                            try (java.io.InputStream in = zf.getInputStream(ze)) {
                                java.nio.file.Files.copy(in, out,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            continue;
                        }
                        String subExt = null;
                        for (String e : EXTENSIONS) {
                            if (!".zip".equals(e) && lower.endsWith(e)) {
                                subExt = e;
                                break;
                            }
                        }
                        if (subExt == null) {
                            continue; // 不是建筑文件
                        }
                        java.nio.file.Path out = new java.io.File(
                                dir.toFile(), new java.io.File(name).getName()).toPath();
                        try (java.io.InputStream in = zf.getInputStream(ze)) {
                            java.nio.file.Files.copy(in, out,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (first == null) {
                            first = loadExternalFile(out, subExt); // 第一个建筑文件即本 zip 的蓝图
                        }
                    }
                } catch (Exception e) {
                    if (WARNED_PARSE_FAIL.add(zipName)) {
                        LOGGER.warn("loadExternalFile: zip {} 异常 -> {}", p.getFileName(), e.toString());
                    }
                }
                // v1.5.223：世界存档 zip → 提取完整建筑优先
                // v1.5.252i：先递归定位世界根目录——网上下载的世界 zip 几乎都带
                // 一层顶层目录（<zip名>/<世界名>/level.dat），直接扫只查直接子层
                // 会"找不到锚点/没有 mca" → 误报导入失败
                if (worldDir != null) {
                    java.nio.file.Path worldRoot = findWorldRoot(worldDir);
                    if (worldRoot == null) {
                        if (WARNED_PARSE_FAIL.add(zipName)) {
                            LOGGER.warn("loadExternalFile: {} 解压后未找到 level.dat", p.getFileName());
                        }
                        worldRoot = worldDir;
                    }
                    List<String> worldSteps = extractFromWorldZip(worldRoot);
                    if (worldSteps != null && !worldSteps.isEmpty()) {
                        return worldSteps;
                    }
                }
                if (first == null) {
                    if (WARNED_PARSE_FAIL.add(zipName)) {
                        LOGGER.warn("loadExternalFile: {} zip 内没有可识别的建筑文件", p.getFileName());
                    }
                }
                return first;
            }
            if (".json".equals(ext)) {
                String json = java.nio.file.Files.readString(p);
                return parseJson(json);
            }
            net.minecraft.nbt.CompoundTag tag;
            if (".snbt".equals(ext)) {
                // v1.5.27 根因修复：NbtUtils.m_178024_ 内部会做 BlockState 转换
                // （m_178071_：palette 元素 → BlockStateParser 字符串格式），NBT 形式的
                // palette（{Name:..,Properties:..}）全部转换失败被清空 → 结构解析必失败。
                // 用 TagParser 纯解析（SNBT→NBT，完整保留结构，已独立验证 47 个文件全通过）。
                tag = net.minecraft.nbt.TagParser.m_129359_(java.nio.file.Files.readString(p));
            } else {
                tag = net.minecraft.nbt.NbtIo.m_128937_(p.toFile());
            }
            // 非标准结构格式 → 先转换为标准结构格式（numen 同款转换器）
            if (".litematic".equals(ext)) {
                tag = fromLitematic(tag);
            } else if (".schem".equals(ext)) {
                tag = fromSchem(tag);
            } else if (".schematic".equals(ext)) {
                // v1.5.37：Planet Minecraft 标准格式（MCEdit 老格式：旧方块 ID + Data）
                tag = fromSchematic(tag);
            }
            // v1.5.317：机器蓝图（文件名匹配机器家族）保留水/岩浆步骤——机器水道/
            // 气泡柱/岩浆焚烧口需要；普通建筑维持剥离（防洪水/岩浆事故）
            boolean keepFluids = false;
            if (p.getFileName() != null) {
                String fname = p.getFileName().toString();
                int dot = fname.lastIndexOf('.');
                if (dot > 0) {
                    keepFluids = machineFamily(fname.substring(0, dot)) != null;
                }
            }
            List<String> steps = parseStructure(tag, 0, null, keepFluids);
            if (steps == null) {
                // v1.5.25f 诊断：解析失败原因（LogUtils → 进 latest.log）
                // v1.5.227：同一文件只 WARN 一次——目录每 2 秒重扫一次外部文件，
                // 失败文件每次重扫都打 WARN → 日志被刷屏（实测 www/qqq/aaa.nbt 每秒
                // 几十条）
                String fname = p.getFileName() != null ? p.getFileName().toString() : p.toString();
                if (WARNED_PARSE_FAIL.add(fname)) {
                    LOGGER.warn("loadExternalFile: {} 解析返回 null（仅提示一次，目录会持续重扫）", fname);
                }
            }
            return steps;
        } catch (Exception e) {
            // v1.5.25f 诊断：记录具体异常（LogUtils → 进 latest.log）
            String fname = p.getFileName() != null ? p.getFileName().toString() : p.toString();
            if (WARNED_PARSE_FAIL.add(fname)) {
                LOGGER.warn("loadExternalFile: {} 异常 -> {}（仅提示一次）", fname, e.toString());
            }
            return null;
        }
    }

    /* ==================== v1.5.223 世界存档提取（ZIP map → 建筑蓝图） ====================
     * 以玩家最后位置（playerdata/level.dat）为锚点，解析锚点所在 region（及 8 邻域）
     * 的全部已生成 chunk（1.13+ Sections palette 位解包），收集非空气方块，收敛
     * 包围盒后归一化并压缩地形层，转 plan 步骤。 */

    /** 世界坐标打包键（内部去重用） */
    private static long packWorldKey(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
    }

    /** v1.5.252i：递归收集目录树内全部 .mca 文件（region 可能嵌套在顶层目录下） */
    private static void collectMca(java.io.File dir, java.util.List<java.io.File> out) {
        java.io.File[] subs = dir.listFiles();
        if (subs == null) {
            return;
        }
        for (java.io.File f : subs) {
            if (f.isDirectory()) {
                collectMca(f, out);
            } else if (f.getName().endsWith(".mca")) {
                out.add(f);
            }
        }
    }

    /** v1.5.252i：递归定位世界根目录（含 level.dat 的目录）；找不到返回 null */
    private static java.nio.file.Path findWorldRoot(java.nio.file.Path dir) {
        if (new java.io.File(dir.toFile(), "level.dat").isFile()) {
            return dir;
        }
        java.io.File[] subs = dir.toFile().listFiles();
        if (subs == null) {
            return null;
        }
        for (java.io.File f : subs) {
            if (f.isDirectory()) {
                java.nio.file.Path r = findWorldRoot(f.toPath());
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 从世界存档目录提取建筑（level.dat + region/*.mca + playerdata/*.dat）；
     *  成功返回 plan 步骤列表，失败返回 null */
    public static List<String> extractFromWorldZip(java.nio.file.Path dir) {
        try {
            double[] anchor = worldAnchor(dir);
            if (anchor == null) {
                LOGGER.warn("extractFromWorldZip: 找不到锚点（playerdata/level.dat 玩家位置）");
                return null;
            }
            int arx = (int) Math.floor(anchor[0] / 16.0) >> 5;
            int arz = (int) Math.floor(anchor[2] / 16.0) >> 5;
            java.util.Map<Long, int[]> blocks = new java.util.LinkedHashMap<>();   // key → {x,y,z,stateIdx}
            java.util.Map<String, Integer> stateIds = new java.util.HashMap<>();   // 状态串 → id
            java.util.List<String> stateList = new java.util.ArrayList<>();        // id → 状态串
            // v1.5.252i：递归收集 .mca（region 目录可能在世界根的直接子层，
            // 也可能因顶层目录嵌套在更深处）
            java.util.List<java.io.File> mcaList = new java.util.ArrayList<>();
            collectMca(dir.toFile(), mcaList);
            java.io.File[] files = mcaList.toArray(new java.io.File[0]);
            if (files.length == 0) {
                return null;
            }
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
            int range = 256; // v1.5.223：锚点 ±256 格聚焦建筑（排除远处地形/零散建筑）
            for (java.io.File mca : files) {
                java.util.regex.Matcher m = pat.matcher(mca.getName());
                if (!m.matches()) {
                    continue;
                }
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                if (Math.abs(rx - arx) > 1 || Math.abs(rz - arz) > 1) {
                    continue; // 只解析锚点附近 3×3 region
                }
                scanRegionFile(mca, blocks, stateIds, stateList, anchor, range);
            }
            if (blocks.isEmpty()) {
                LOGGER.warn("extractFromWorldZip: 锚点附近没有可提取方块");
                return null;
            }
            // 收敛包围盒
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] b : blocks.values()) {
                minX = Math.min(minX, b[0]);
                maxX = Math.max(maxX, b[0]);
                minY = Math.min(minY, b[1]);
                maxY = Math.max(maxY, b[1]);
                minZ = Math.min(minZ, b[2]);
                maxZ = Math.max(maxZ, b[2]);
            }
            // 转 plan 步骤（y 升序，上限截断；归一化到 0 起始）
            List<int[]> sorted = new java.util.ArrayList<>(blocks.values());
            sorted.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1])
                    : (a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2])));
            List<String> steps = new ArrayList<>();
            int cap = structureMaxBlocks();
            for (int[] b : sorted) {
                if (steps.size() >= cap) {
                    break;
                }
                String state = b[3] < stateList.size() ? stateList.get(b[3]) : "";
                if (state.isEmpty()) {
                    continue;
                }
                int br = state.indexOf('{');
                String blockId = br < 0 ? state : state.substring(0, br);
                steps.add((b[0] - minX) + "," + (b[1] - minY) + "," + (b[2] - minZ) + "," + blockId
                        + (br < 0 ? "" : "|" + state.substring(br)));
            }
            if (steps.isEmpty()) {
                return null;
            }
            // 压缩地形层（外部蓝图常含原址地形）
            List<String> trimmed = trimTerrainLayers(steps);
            LOGGER.info("extractFromWorldZip: 提取 {} 块（原 {} 块，锚点 {}, {}, {}，范围 {}x{}x{}）",
                    trimmed == null ? steps.size() : trimmed.size(), steps.size(),
                    (int) anchor[0], (int) anchor[1], (int) anchor[2],
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
            return trimmed != null ? trimmed : steps;
        } catch (Exception e) {
            LOGGER.warn("extractFromWorldZip 异常 -> {}", e.toString());
            return null;
        }
    }

    /** 锚点：playerdata/*.dat 的 Pos → level.dat Data.Player.Pos → SpawnX/Y/Z */
    private static double[] worldAnchor(java.nio.file.Path dir) {
        java.io.File[] pds = dir.toFile().listFiles((d, n) -> n.endsWith(".dat"));
        if (pds != null) {
            for (java.io.File pd : pds) {
                if (pd.getName().equals("level.dat")) {
                    continue;
                }
                try {
                    net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.m_128937_(pd);
                    if (root.m_128425_("Pos", 9)) {
                        net.minecraft.nbt.ListTag pos = root.m_128437_("Pos", 5);
                        if (pos.size() >= 3) {
                            return new double[]{pos.m_128772_(0), pos.m_128772_(1), pos.m_128772_(2)};
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        java.io.File ld = new java.io.File(dir.toFile(), "level.dat");
        if (ld.isFile()) {
            try {
                net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.m_128937_(ld);
                if (root.m_128425_("Data", 10)) {
                    net.minecraft.nbt.CompoundTag data = root.m_128469_("Data");
                    if (data.m_128425_("Player", 10)) {
                        net.minecraft.nbt.CompoundTag player = data.m_128469_("Player");
                        if (player.m_128425_("Pos", 9)) {
                            net.minecraft.nbt.ListTag pos = player.m_128437_("Pos", 5);
                            if (pos.size() >= 3) {
                                return new double[]{pos.m_128772_(0), pos.m_128772_(1), pos.m_128772_(2)};
                            }
                        }
                    }
                    if (data.m_128425_("SpawnX", 3) && data.m_128425_("SpawnY", 3)
                            && data.m_128425_("SpawnZ", 3)) {
                        return new double[]{data.m_128451_("SpawnX"),
                                data.m_128451_("SpawnY"), data.m_128451_("SpawnZ")};
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 解析一个 mca 区域文件：头部定位表 → chunk 数据（gzip/zlib）→ parseChunk */
    private static void scanRegionFile(java.io.File mca, java.util.Map<Long, int[]> blocks,
                                       java.util.Map<String, Integer> stateIds,
                                       java.util.List<String> stateList,
                                       double[] anchor, int range) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mca, "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int ci = 0; ci < 1024; ci++) {
                int off = ((header[ci * 4] & 0xFF) << 16 | (header[ci * 4 + 1] & 0xFF) << 8
                        | (header[ci * 4 + 2] & 0xFF)) * 4096;
                if (off == 0) {
                    continue; // 未生成 chunk
                }
                raf.seek(off);
                int len = raf.readInt();
                int compression = raf.readByte();
                if (len <= 1 || len > 5_000_000) {
                    continue;
                }
                byte[] payload = new byte[len - 1];
                raf.readFully(payload);
                byte[] nbt = inflate(payload, compression == 1);
                if (nbt == null) {
                    continue;
                }
                net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.m_128928_(
                        new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbt)));
                parseChunk(root, blocks, stateIds, stateList, anchor, range);
            }
        } catch (Exception ignored) {
        }
    }

    /** gzip/zlib 解压 */
    private static byte[] inflate(byte[] data, boolean gzip) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = gzip
                    ? new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))
                    : new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(data));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 世界提取过滤：基础地形/液体/空气跳过；锚点 ±range 外跳过（聚焦建筑） */
    private static boolean skipWorldBlock(String blockId, int wx, int wz,
                                          double[] anchor, int range) {
        if (TERRAIN_BLOCKS.contains(blockId)
                || "minecraft:water".equals(blockId) || "minecraft:lava".equals(blockId)
                || "minecraft:snow_layer".equals(blockId) || "minecraft:ice".equals(blockId)
                || "minecraft:air".equals(blockId)) {
            return true;
        }
        if (anchor != null && range > 0
                && (Math.abs(wx - anchor[0]) > range || Math.abs(wz - anchor[2]) > range)) {
            return true;
        }
        return false;
    }

    /** 解析 chunk NBT（1.13+ Sections palette；1.8-1.11 旧格式 section Blocks+Data） */
    private static void parseChunk(net.minecraft.nbt.CompoundTag root, java.util.Map<Long, int[]> blocks,
                                   java.util.Map<String, Integer> stateIds,
                                   java.util.List<String> stateList,
                                   double[] anchor, int range) {
        try {
            net.minecraft.nbt.CompoundTag level = root.m_128425_("Level", 10) ? root.m_128469_("Level") : root;
            int cx = level.m_128451_("xPos");
            int cz = level.m_128451_("zPos");
            if (!level.m_128425_("Sections", 9)) {
                return;
            }
            net.minecraft.nbt.ListTag sections = level.m_128437_("Sections", 10);
            for (int si = 0; si < sections.size(); si++) {
                net.minecraft.nbt.CompoundTag sec = sections.m_128728_(si);
                int sy = sec.m_128445_("Y");
                // 1.8-1.12 旧格式：section 直接是 Blocks byte[] + Data（无 palette；
                // 1.8+ 可带 Add 高位扩展）
                if (sec.m_128425_("Blocks", 7)) {
                    byte[] raw = sec.m_128463_("Blocks");
                    byte[] add = sec.m_128425_("Add", 7) ? sec.m_128463_("Add") : null;
                    // v1.5.253：元数据 nibble（羊毛颜色/台阶类型/楼梯朝向等）
                    byte[] data = sec.m_128425_("Data", 7) ? sec.m_128463_("Data") : null;
                    for (int i = 0; i < raw.length; i++) {
                        int id = raw[i] & 0xFF;
                        if (add != null) {
                            int ai = i >> 1;
                            id |= (i % 2 == 0 ? (add[ai] & 0x0F) : (add[ai] >> 4)) << 8;
                        }
                        if (id == 0) {
                            continue;
                        }
                        int meta = 0;
                        if (data != null) {
                            int di = i >> 1;
                            meta = (i & 1) == 0 ? (data[di] & 0x0F) : ((data[di] >> 4) & 0x0F);
                        }
                        String state = legacyBlockState(id, meta);
                        if (state == null) {
                            continue;
                        }
                        int lx = i & 15;
                        int lz = (i >> 4) & 15;
                        int ly = (i >> 8) & 15;
                        int wx = cx * 16 + lx;
                        int wy = sy * 16 + ly;
                        int wz = cz * 16 + lz;
                        String blockId = state.indexOf('{') < 0 ? state : state.substring(0, state.indexOf('{'));
                        if (skipWorldBlock(blockId, wx, wz, anchor, range)) {
                            continue;
                        }
                        Integer sid = stateIds.get(state);
                        if (sid == null) {
                            sid = stateList.size();
                            stateIds.put(state, sid);
                            stateList.add(state);
                        }
                        blocks.put(packWorldKey(wx, wy, wz), new int[]{wx, wy, wz, sid});
                    }
                    continue;
                }
                // 1.13+ palette 格式
                if (!sec.m_128425_("block_states", 10)) {
                    continue;
                }
                net.minecraft.nbt.CompoundTag bs = sec.m_128469_("block_states");
                net.minecraft.nbt.ListTag palette = bs.m_128437_("palette", 10);
                if (palette == null || palette.size() == 0) {
                    continue;
                }
                // 全空气 section 跳过（省去 4096 次位解包）
                if (palette.size() == 1
                        && "minecraft:air".equals(palette.m_128728_(0).m_128461_("Name"))) {
                    continue;
                }
                long[] data = bs.m_128425_("data", 12) ? bs.m_128467_("data") : null;
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                long mask = bits >= 64 ? -1L : (1L << bits) - 1;
                // 局部缓存：palette 索引 → 状态串（每 chunk 只构建一次）
                java.util.Map<Integer, String> palState = new java.util.HashMap<>();
                for (int i = 0; i < 4096; i++) {
                    int idx;
                    if (data == null) {
                        idx = 0;
                    } else {
                        int startBit = i * bits;
                        int li = startBit >> 6;
                        if (li >= data.length) {
                            continue;
                        }
                        int bi = startBit & 63;
                        idx = (int) ((data[li] >>> bi) & mask);
                        if (bi + bits > 64 && li + 1 < data.length) {
                            idx |= (int) ((data[li + 1] << (64 - bi)) & mask);
                        }
                    }
                    if (idx < 0 || idx >= palette.size()) {
                        continue;
                    }
                    String state = palState.get(idx);
                    if (state == null) {
                        net.minecraft.nbt.CompoundTag ps = palette.m_128728_(idx);
                        String name = ps.m_128461_("Name");
                        state = (name == null || "minecraft:air".equals(name))
                                ? "" : paletteStateString(ps);
                        palState.put(idx, state);
                    }
                    if (state.isEmpty()) {
                        continue;
                    }
                    String blockId = state.indexOf('{') < 0 ? state : state.substring(0, state.indexOf('{'));
                    int lx = i & 15;
                    int lz = (i >> 4) & 15;
                    int ly = (i >> 8) & 15;
                    int wx = cx * 16 + lx;
                    int wy = sy * 16 + ly;
                    int wz = cz * 16 + lz;
                    if (skipWorldBlock(blockId, wx, wz, anchor, range)) {
                        continue;
                    }
                    Integer sid = stateIds.get(state);
                    if (sid == null) {
                        sid = stateList.size();
                        stateIds.put(state, sid);
                        stateList.add(state);
                    }
                    blocks.put(packWorldKey(wx, wy, wz), new int[]{wx, wy, wz, sid});
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** palette 状态 → "minecraft:xxx{prop:\"val\",...}"（v1.5.253：规范 SNBT——
     *  键值用 ':' 分隔、值带引号，doPlace 的 NbtUtils.m_178024_ 保证可解析；
     *  旧版 "{prop=val}" 无引号写法能否被 SNBT 解析器接受不确定，解析失败
     *  时状态静默退化为默认（台阶/楼梯朝向全丢）） */
    private static String paletteStateString(net.minecraft.nbt.CompoundTag ps) {
        String name = ps.m_128461_("Name");
        if (name == null) {
            return "";
        }
        if (!ps.m_128425_("Properties", 10)) {
            return name;
        }
        net.minecraft.nbt.CompoundTag props = ps.m_128469_("Properties");
        StringBuilder sb = new StringBuilder(name).append('{');
        boolean first = true;
        for (String k : props.m_128431_()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(k).append(":\"").append(props.m_128461_(k)).append('"');
        }
        return sb.append('}').toString();
    }

    // ================= v1.5.253：1.12.2 完整注册表映射（天安门镂空根因修复） =================
    // 旧版 legacyBlockId 只有 1.8 常用子集：天安门 1.12 地图（DataVersion 1343）因此
    // 丢失 125/126 木台阶 5470 块、96 活板门 511、82 黏土 1068、139 石墙 156、
    // 136 丛林楼梯 312、235-250 釉陶瓦/混凝土 1900+；且 159 染色陶瓦 14930 块被映射成
    // 1.20.1 不存在的 stained_hardened_clay → 解析为空气 → 当"清除步骤"不建 → 城楼
    // 中间完全镂空。本表按 PrismarineJS minecraft-data pc/1.12 注册表核对，实测元数据
    // 分布逐 id 验证（见 analysis/ 脚本）。
    private static final String[] LEGACY_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] LEGACY_WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] LEGACY_STONE_VARIANTS = {"stone", "granite", "polished_granite",
            "diorite", "polished_diorite", "andesite", "polished_andesite"};
    private static final String[] LEGACY_SLAB_BLOCKS = {"stone_slab", "sandstone_slab", "oak_slab",
            "cobblestone_slab", "brick_slab", "stone_brick_slab", "nether_brick_slab", "quartz_slab"};
    private static final String[] LEGACY_SLAB2_BLOCKS = {"red_sandstone_slab", "purpur_slab"};
    private static final String[] LEGACY_STONEBRICK = {"stone_bricks", "mossy_stone_bricks",
            "cracked_stone_bricks", "chiseled_stone_bricks"};
    private static final String[] LEGACY_MONSTER_EGG = {"stone", "cobblestone", "stone_bricks",
            "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks"};
    private static final String[] LEGACY_FLOWERS = {"poppy", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"};
    private static final String[] LEGACY_DOUBLE_PLANT = {"sunflower", "lilac", "tall_grass",
            "large_fern", "rose_bush", "peony"};
    /** 楼梯/栅栏门/活板门/釉陶瓦 meta&3 → 朝向（EnumFacing.getHorizontal：0=south,1=west,2=north,3=east） */
    private static final String[] LEGACY_FACINGS = {"south", "west", "north", "east"};
    /** 火把 meta 1-4 → 朝向（1=east,2=west,3=south,4=north） */
    private static final String[] LEGACY_TORCH_FACINGS = {"north", "east", "west", "south", "north"};
    /** 梯子/告示牌/箱子/熔炉/骷髅头 meta 2-5 → 朝向（2=north,3=south,4=west,5=east） */
    private static final String[] LEGACY_WALL_FACINGS = {"north", "north", "north", "south", "west", "east"};
    /** 门 meta&3 → 朝向（getHorizontal(meta).rotateYCCW：0=east,1=north,2=west,3=south） */
    private static final String[] LEGACY_DOOR_FACINGS = {"east", "north", "west", "south"};
    private static final String[] LEGACY_AXIS = {"y", "x", "z"};
    private static final String[] LEGACY_RAIL_SHAPES = {"north_south", "east_west", "ascending_east",
            "ascending_west", "ascending_north", "ascending_south", "south_east", "south_west",
            "north_west", "north_east"};
    /** 活塞 meta 0-5 → 朝向（0=down,1=up,2=north,3=south,4=west,5=east） */
    private static final String[] LEGACY_PISTON_FACINGS = {"down", "up", "north", "south", "west", "east"};

    /** SNBT 字符串值（NbtUtils.m_178024_ 解析，带引号保证可解析） */
    private static String legacySnbt(String v) {
        return "\"" + v + "\"";
    }

    /** 楼梯状态串（meta&3 朝向，meta&4 上半；shape 用默认 straight） */
    private static String legacyStairsState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",half:" + legacySnbt((meta & 4) != 0 ? "top" : "bottom") + "}";
    }

    /** 台阶状态串（hasHalf 时 meta&8 上半；double 无半位） */
    private static String legacySlabState(String block, int meta, boolean hasHalf) {
        String type = hasHalf ? ((meta & 8) != 0 ? "top" : "bottom") : "double";
        return block + "{type:" + legacySnbt(type) + "}";
    }

    /** 门状态串（meta&3 朝向，meta&4 开；上半由调用方跳过） */
    private static String legacyDoorState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_DOOR_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false") + "}";
    }

    /** 活板门状态串（meta&3 朝向，meta&4 开，meta&8 上半） */
    private static String legacyTrapdoorState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false")
                + ",half:" + legacySnbt((meta & 8) != 0 ? "top" : "bottom") + "}";
    }

    /** 栅栏门状态串（meta&3 朝向，meta&4 开） */
    private static String legacyGateState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false") + "}";
    }

    /** 16 色方块（羊毛/陶瓦/玻璃/地毯等），meta 0-15 与 1.20.1 颜色顺序一致 */
    private static String legacyColoredBlock(String stem, int meta) {
        return "minecraft:" + LEGACY_COLORS[meta & 15] + "_" + stem;
    }

    /** 拉杆/按钮状态串（meta&7：0 地板,1-4 墙,5-6 天花板） */
    private static String legacyFaceState(String block, int meta) {
        int i = meta & 7;
        if (i >= 1 && i <= 4) {
            return block + "{face:" + legacySnbt("wall")
                    + ",facing:" + legacySnbt(LEGACY_FACINGS[i & 3]) + "}";
        }
        if (i == 5) {
            return block + "{face:" + legacySnbt("ceiling") + ",facing:" + legacySnbt("north") + "}";
        }
        if (i == 6) {
            return block + "{face:" + legacySnbt("ceiling") + ",facing:" + legacySnbt("south") + "}";
        }
        return block + "{face:" + legacySnbt("floor") + ",facing:" + legacySnbt("north") + "}";
    }

    /** 藤蔓状态串（meta 位：1=up,2=south,4=west,8=north,16=east） */
    private static String legacyVineState(int meta) {
        StringBuilder sb = new StringBuilder("minecraft:vine{");
        boolean first = true;
        String[] dirs = {"up", "south", "west", "north", "east"};
        for (int b = 0; b < 5; b++) {
            if ((meta & (1 << b)) != 0) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(dirs[b]).append(':').append(legacySnbt("true"));
            }
        }
        return sb.append('}').toString();
    }

    /** 1.12.2 数字方块 id + 元数据 → 1.20.1 方块状态串（"minecraft:xxx" 或
     *  "minecraft:xxx{prop:\"val\"}"，SNBT 兼容，doPlace 直接 NbtUtils 解析）。
     *  返回 null = 跳过（瞬态/黑名单/无对应物/地形由调用方过滤器处理）。 */
    private static String legacyBlockState(int id, int meta) {
        int m = meta & 15;
        switch (id) {
            case 1:
                return "minecraft:" + LEGACY_STONE_VARIANTS[Math.min(m, 6)];
            case 2:
                return "minecraft:grass_block";
            case 3:
                return m == 1 ? "minecraft:coarse_dirt" : m == 2 ? "minecraft:podzol" : "minecraft:dirt";
            case 4:
                return "minecraft:cobblestone";
            case 5:
                return "minecraft:" + LEGACY_WOODS[Math.min(m, 5)] + "_planks";
            case 6:
                return "minecraft:" + LEGACY_WOODS[Math.min(m, 5)] + "_sapling";
            case 7:
                return "minecraft:bedrock";
            case 12:
                return m == 1 ? "minecraft:red_sand" : "minecraft:sand";
            case 13:
                return "minecraft:gravel";
            case 14:
                return "minecraft:gold_ore";
            case 15:
                return "minecraft:iron_ore";
            case 16:
                return "minecraft:coal_ore";
            case 17:
                return "minecraft:" + LEGACY_WOODS[m & 3] + "_log{axis:"
                        + legacySnbt(LEGACY_AXIS[(meta >> 2) & 3]) + "}";
            case 18:
                return "minecraft:" + LEGACY_WOODS[m & 3] + "_leaves";
            case 19:
                return m == 1 ? "minecraft:wet_sponge" : "minecraft:sponge";
            case 20:
                return "minecraft:glass";
            case 21:
                return "minecraft:lapis_ore";
            case 22:
                return "minecraft:lapis_block";
            case 23:
                return "minecraft:dispenser";
            case 24:
                return m == 1 ? "minecraft:chiseled_sandstone"
                        : m == 2 ? "minecraft:smooth_sandstone" : "minecraft:sandstone";
            case 25:
                return "minecraft:note_block";
            case 26:
                return (meta & 8) != 0 ? null : "minecraft:red_bed"; // 上半跳过（床自动补全）
            case 27:
                return "minecraft:powered_rail";
            case 28:
                return "minecraft:detector_rail";
            case 29:
                return "minecraft:sticky_piston{facing:"
                        + legacySnbt(LEGACY_PISTON_FACINGS[Math.min(m, 5)]) + "}";
            case 30:
                return "minecraft:cobweb";
            case 31:
                return m == 1 ? "minecraft:short_grass" : m == 2 ? "minecraft:fern" : "minecraft:dead_bush";
            case 32:
                return "minecraft:dead_bush";
            case 33:
                return "minecraft:piston{facing:"
                        + legacySnbt(LEGACY_PISTON_FACINGS[Math.min(m, 5)]) + "}";
            case 34:
            case 36:
                return null; // 活塞头/活塞扩展（瞬态）
            case 35:
                return legacyColoredBlock("wool", m);
            case 37:
                return "minecraft:dandelion";
            case 38:
                return "minecraft:" + LEGACY_FLOWERS[Math.min(m, 8)];
            case 39:
                return "minecraft:brown_mushroom";
            case 40:
                return "minecraft:red_mushroom";
            case 41:
                return "minecraft:gold_block";
            case 42:
                return "minecraft:iron_block";
            case 43:
                return legacySlabState("minecraft:" + LEGACY_SLAB_BLOCKS[Math.min(m, 7)], meta, false);
            case 44:
                return legacySlabState("minecraft:" + LEGACY_SLAB_BLOCKS[Math.min(m, 7)], meta, true);
            case 45:
                return "minecraft:bricks";
            case 46:
                return "minecraft:tnt";
            case 47:
                return "minecraft:bookshelf";
            case 48:
                return "minecraft:mossy_cobblestone";
            case 49:
                return "minecraft:obsidian";
            case 50:
                return m >= 1 && m <= 4 ? "minecraft:wall_torch{facing:"
                        + legacySnbt(LEGACY_TORCH_FACINGS[m]) + "}" : "minecraft:torch";
            case 51:
                return null; // 火
            case 52:
                return "minecraft:spawner";
            case 53:
                return legacyStairsState("minecraft:oak_stairs", meta);
            case 54:
                return "minecraft:chest{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 55:
                return "minecraft:redstone_wire";
            case 56:
                return "minecraft:diamond_ore";
            case 57:
                return "minecraft:diamond_block";
            case 58:
                return "minecraft:crafting_table";
            case 59:
                return "minecraft:wheat";
            case 60:
                return "minecraft:farmland";
            case 61:
            case 62:
                return "minecraft:furnace{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 63:
                return "minecraft:oak_sign{rotation:" + legacySnbt(String.valueOf(m)) + "}";
            case 64:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:oak_door", meta);
            case 65:
                return "minecraft:ladder{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 66:
                return "minecraft:rail{shape:"
                        + legacySnbt(LEGACY_RAIL_SHAPES[Math.min(m, 9)]) + "}";
            case 67:
                return legacyStairsState("minecraft:cobblestone_stairs", meta);
            case 68:
                return "minecraft:oak_wall_sign{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 69:
                return legacyFaceState("minecraft:lever", meta);
            case 70:
                return "minecraft:stone_pressure_plate";
            case 71:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:iron_door", meta);
            case 72:
                return "minecraft:oak_pressure_plate";
            case 73:
            case 74:
                return "minecraft:redstone_ore";
            case 75:
            case 76:
                return m >= 1 && m <= 4 ? "minecraft:redstone_wall_torch{facing:"
                        + legacySnbt(LEGACY_TORCH_FACINGS[m]) + "}" : "minecraft:redstone_torch";
            case 77:
                return legacyFaceState("minecraft:stone_button", meta);
            case 78:
                return null; // 雪层（地形）
            case 79:
                return "minecraft:ice";
            case 80:
                return "minecraft:snow_block";
            case 81:
                return "minecraft:cactus";
            case 82:
                return "minecraft:clay";
            case 83:
                return "minecraft:sugar_cane";
            case 84:
                return "minecraft:jukebox";
            case 85:
                return "minecraft:oak_fence";
            case 86:
            case 91:
                return "minecraft:pumpkin";
            case 87:
                return "minecraft:netherrack";
            case 88:
                return "minecraft:soul_sand";
            case 89:
                return "minecraft:glowstone";
            case 90:
                return null; // 传送门
            case 92:
                return "minecraft:cake";
            case 93:
            case 94:
                return "minecraft:repeater";
            case 95:
                return legacyColoredBlock("stained_glass", m);
            case 96:
                return legacyTrapdoorState("minecraft:oak_trapdoor", meta);
            case 97:
                return "minecraft:" + LEGACY_MONSTER_EGG[Math.min(m, 5)];
            case 98:
                return "minecraft:" + LEGACY_STONEBRICK[Math.min(m, 3)];
            case 99:
                return "minecraft:brown_mushroom_block";
            case 100:
                return "minecraft:red_mushroom_block";
            case 101:
                return "minecraft:iron_bars";
            case 102:
                return "minecraft:glass_pane";
            case 103:
                return "minecraft:melon";
            case 104:
            case 105:
                return null; // 瓜茎（瞬态）
            case 106:
                return legacyVineState(meta);
            case 107:
                return legacyGateState("minecraft:oak_fence_gate", meta);
            case 108:
                return legacyStairsState("minecraft:brick_stairs", meta);
            case 109:
                return legacyStairsState("minecraft:stone_brick_stairs", meta);
            case 110:
                return "minecraft:mycelium";
            case 111:
                return "minecraft:lily_pad";
            case 112:
                return "minecraft:nether_bricks";
            case 113:
                return "minecraft:nether_brick_fence";
            case 114:
                return legacyStairsState("minecraft:nether_brick_stairs", meta);
            case 115:
                return "minecraft:nether_wart";
            case 116:
                return "minecraft:enchanting_table";
            case 117:
                return "minecraft:brewing_stand";
            case 118:
                return "minecraft:cauldron";
            case 119:
                return "minecraft:end_portal_frame";
            case 120:
                return "minecraft:end_stone";
            case 121:
                return "minecraft:dragon_egg";
            case 122:
            case 123:
                return "minecraft:redstone_lamp";
            case 124:
            case 125:
                return legacySlabState("minecraft:" + LEGACY_WOODS[Math.min(m, 5)] + "_slab", meta, false);
            case 126:
                return legacySlabState("minecraft:" + LEGACY_WOODS[Math.min(m, 5)] + "_slab", meta, true);
            case 127:
                return null; // 可可豆（作物）
            case 128:
                return legacyStairsState("minecraft:sandstone_stairs", meta);
            case 129:
                return "minecraft:emerald_ore";
            case 130:
                return "minecraft:ender_chest{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 131:
                return "minecraft:tripwire_hook{facing:" + legacySnbt(LEGACY_FACINGS[m & 3]) + "}";
            case 132:
                return "minecraft:tripwire";
            case 133:
                return "minecraft:emerald_block";
            case 134:
                return legacyStairsState("minecraft:spruce_stairs", meta);
            case 135:
                return legacyStairsState("minecraft:birch_stairs", meta);
            case 136:
                return legacyStairsState("minecraft:jungle_stairs", meta);
            case 137:
                return null; // 命令方块
            case 138:
                return "minecraft:beacon";
            case 139:
                return m == 1 ? "minecraft:mossy_cobblestone_wall" : "minecraft:cobblestone_wall";
            case 140:
                return "minecraft:flower_pot";
            case 141:
                return "minecraft:carrots";
            case 142:
                return "minecraft:potatoes";
            case 143:
                return legacyFaceState("minecraft:oak_button", meta);
            case 144:
                return m >= 1 && m <= 4 ? "minecraft:skeleton_wall_skull{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[m]) + "}" : "minecraft:skeleton_skull";
            case 145:
                return "minecraft:anvil{facing:"
                        + legacySnbt(new String[]{"north", "east", "south", "west"}[m & 3]) + "}";
            case 146:
                return "minecraft:trapped_chest";
            case 147:
                return "minecraft:light_weighted_pressure_plate";
            case 148:
                return "minecraft:heavy_weighted_pressure_plate";
            case 149:
            case 150:
                return "minecraft:comparator";
            case 151:
            case 178:
                return "minecraft:daylight_detector";
            case 152:
                return "minecraft:redstone_block";
            case 153:
                return null; // 石英矿（不在地形过滤表，避免进蓝图）
            case 154:
                return "minecraft:hopper";
            case 155:
                return m == 1 ? "minecraft:chiseled_quartz_block"
                        : m == 2 ? "minecraft:quartz_pillar" : "minecraft:quartz_block";
            case 156:
                return legacyStairsState("minecraft:quartz_stairs", meta);
            case 157:
                return "minecraft:activator_rail";
            case 158:
                return "minecraft:dropper";
            case 159:
                // 染色陶瓦 14930 块（天安门红墙）——旧版映射成 1.20.1 不存在的
                // stained_hardened_clay → 解析为空气 → 整面墙当"清除步骤"不建
                return legacyColoredBlock("terracotta", m);
            case 160:
                return legacyColoredBlock("stained_glass_pane", m);
            case 161:
                return (m & 1) == 1 ? "minecraft:dark_oak_leaves" : "minecraft:acacia_leaves";
            case 162:
                return "minecraft:" + ((m & 1) == 1 ? "dark_oak" : "acacia") + "_log{axis:"
                        + legacySnbt(LEGACY_AXIS[(meta >> 2) & 3]) + "}";
            case 163:
                return legacyStairsState("minecraft:acacia_stairs", meta);
            case 164:
                return legacyStairsState("minecraft:dark_oak_stairs", meta);
            case 165:
                return "minecraft:slime_block";
            case 166:
                return null; // 屏障
            case 167:
                return legacyTrapdoorState("minecraft:iron_trapdoor", meta);
            case 168:
                return m == 1 ? "minecraft:prismarine_bricks"
                        : m == 2 ? "minecraft:dark_prismarine" : "minecraft:prismarine";
            case 169:
                return "minecraft:sea_lantern";
            case 170:
                return "minecraft:hay_block{axis:" + legacySnbt(LEGACY_AXIS[(meta >> 2) & 3]) + "}";
            case 171:
                return legacyColoredBlock("carpet", m);
            case 172:
                return "minecraft:terracotta"; // 硬化陶瓦 → 1.20.1 陶瓦（旧版同名 ID 在 1.20.1 不存在）
            case 173:
                return "minecraft:coal_block";
            case 174:
                return "minecraft:packed_ice";
            case 175:
                return (meta & 8) != 0 ? null : "minecraft:" + LEGACY_DOUBLE_PLANT[Math.min(m, 5)];
            case 176:
                return "minecraft:white_banner";
            case 177:
                return "minecraft:white_wall_banner{facing:"
                        + legacySnbt(LEGACY_WALL_FACINGS[Math.min(m, 5)]) + "}";
            case 179:
                return m == 1 ? "minecraft:chiseled_red_sandstone"
                        : m == 2 ? "minecraft:smooth_red_sandstone" : "minecraft:red_sandstone";
            case 180:
                return legacyStairsState("minecraft:red_sandstone_stairs", meta);
            case 181:
                return legacySlabState("minecraft:" + LEGACY_SLAB2_BLOCKS[Math.min(m, 1)], meta, false);
            case 182:
                // 旧版误映射为 stone_pressure_plate（1.8 布局）——实为石台阶2
                return legacySlabState("minecraft:" + LEGACY_SLAB2_BLOCKS[Math.min(m, 1)], meta, true);
            case 183:
                return legacyGateState("minecraft:spruce_fence_gate", meta);
            case 184:
                return legacyGateState("minecraft:birch_fence_gate", meta);
            case 185:
                return legacyGateState("minecraft:jungle_fence_gate", meta);
            case 186:
                return legacyGateState("minecraft:dark_oak_fence_gate", meta);
            case 187:
                return legacyGateState("minecraft:acacia_fence_gate", meta);
            case 188:
                return "minecraft:spruce_fence";
            case 189:
                return "minecraft:birch_fence";
            case 190:
                return "minecraft:jungle_fence";
            case 191:
                return "minecraft:dark_oak_fence";
            case 192:
                return "minecraft:acacia_fence";
            case 193:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:spruce_door", meta);
            case 194:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:birch_door", meta);
            case 195:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:jungle_door", meta);
            case 196:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:acacia_door", meta);
            case 197:
                return (meta & 8) != 0 ? null : legacyDoorState("minecraft:dark_oak_door", meta);
            case 198:
                return "minecraft:end_rod";
            case 199:
                return "minecraft:chorus_plant";
            case 200:
                return "minecraft:chorus_flower";
            case 201:
                return "minecraft:purpur_block";
            case 202:
                return "minecraft:purpur_pillar{axis:" + legacySnbt(LEGACY_AXIS[(meta >> 2) & 3]) + "}";
            case 203:
                return legacyStairsState("minecraft:purpur_stairs", meta);
            case 204:
                return legacySlabState("minecraft:purpur_slab", meta, false);
            case 205:
                return legacySlabState("minecraft:purpur_slab", meta, true);
            case 206:
                return "minecraft:end_stone_bricks";
            case 207:
                return "minecraft:beetroots";
            case 208:
                return "minecraft:dirt_path";
            case 209:
                return null; // 末地折跃门
            case 210:
            case 211:
                return null; // 命令方块
            case 212:
                return "minecraft:ice"; // 霜冰
            case 213:
                return "minecraft:magma_block";
            case 214:
                return "minecraft:nether_wart_block";
            case 215:
                return "minecraft:red_nether_bricks";
            case 216:
                return "minecraft:bone_block{axis:" + legacySnbt(LEGACY_AXIS[(meta >> 2) & 3]) + "}";
            case 217:
                return null; // 结构空位
            case 218:
                return "minecraft:observer";
            case 219:
            case 220:
            case 221:
            case 222:
            case 223:
            case 224:
            case 225:
            case 226:
            case 227:
            case 228:
            case 229:
            case 230:
            case 231:
            case 232:
            case 233:
            case 234:
                return "minecraft:" + LEGACY_COLORS[id - 219] + "_shulker_box";
            case 235:
            case 236:
            case 237:
            case 238:
            case 239:
            case 240:
            case 241:
            case 242:
            case 243:
            case 244:
            case 245:
            case 246:
            case 247:
            case 248:
            case 249:
            case 250:
                return "minecraft:" + LEGACY_COLORS[id - 235] + "_glazed_terracotta{facing:"
                        + legacySnbt(LEGACY_FACINGS[m & 3]) + "}";
            case 251:
                return "minecraft:white_concrete";
            case 252:
                return "minecraft:white_concrete_powder";
            default:
                return null; // 未知旧 id → 跳过（旧存档兜底，不阻塞）
        }
    }

    private static String externalName(java.nio.file.Path p, String ext, List<String> steps) {
        if (".json".equals(ext)) {
            try {
                JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
                JsonElement name = root.get("name");
                if (name != null && !name.getAsString().isEmpty()) {
                    return name.getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        // v1.5.28：.snbt 文件名 → 中文显示名（litematic/schem 文件名本身多为中文，直接返回原名）
        String base = p.getFileName().toString().replace(ext, "");
        String cn = EXT_CN_NAMES.get(base);
        return cn != null ? cn : base;
    }

    /**
     * v1.5.28：LLM 现场生成的 JSON 蓝图落盘到 config/maid_smart/blueprints/——
     * 生成即保存，scanExternalBlueprints 增量扫描自动注册进手册（之前生成完就丢，
     * "高科技别墅/海景房"等生成过的蓝图从未进过手册）。
     * 文件名 = 蓝图 name（非法字符替换）；同名再次生成 → 覆盖（mtime 变化自动重扫）。
     */
    public static void saveJsonBlueprint(String name, String json) {
        try {
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("maid_smart").resolve("blueprints");
            java.nio.file.Files.createDirectories(dir);
            String safe = name == null || name.trim().isEmpty() ? "llm_blueprint" : name.trim();
            safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
            java.nio.file.Path out = dir.resolve(safe + ".json");
            java.nio.file.Files.writeString(out, json);
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.94：删除蓝图（手册删除按钮用）。
     * 只能删【外部蓝图】（config/maid_smart/blueprints 或存档 schematics/ 下的文件，
     * 含 AI 生成的 .json）；内置蓝图（maid_smart:hut 等）无文件、不可删 → false。
     * 删除后清内存缓存 + 增量扫描移除注册；下次扫描自动同步（文件已不在）。
     * v1.5.324：全扫描目录清除【同名文件】——旧版只删 EXTERNAL_PATHS 登记的那一份，
     * 若同一蓝图文件同时存在于 config/maid_smart/blueprints 与 <cwd>/schematics（或
     * 多实例/多存档残留），另一份会在下次扫描时重新注册 → "删了又出现"
     * （RRR/SSS 删不掉、重登/跨存档又出现的根因）；现在按文件名（去扩展名、
     * 大小写不敏感）在所有扫描目录里清掉全部副本。
     */
    public static boolean deleteBlueprint(String id) {
        try {
            java.nio.file.Path path = EXTERNAL_PATHS.get(id);
            String base = id.startsWith("maid_smart_ext:")
                    ? id.substring("maid_smart_ext:".length()) : id;
            // v1.5.252ad：删除诊断（latest.log 搜 "deleteBlueprint"）
            LOGGER.info("deleteBlueprint: 请求删除 id={} path={}", id,
                    path == null ? "(无注册路径!)" : path.toString());
            boolean deletedAny = false;
            if (path != null) {
                java.nio.file.Files.deleteIfExists(path);
                deletedAny = true;
            }
            // 收集全部扫描目录（与 scanExternalBlueprints 同一套：config + <cwd>/schematics）
            java.util.List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
            try {
                java.nio.file.Path cfg = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                        .resolve("maid_smart").resolve("blueprints");
                if (java.nio.file.Files.isDirectory(cfg)) {
                    dirs.add(cfg);
                }
            } catch (Exception ignored) {
            }
            try {
                if (SERVER != null) {
                    java.nio.file.Path sched = SERVER.m_6237_().toPath().resolve("schematics");
                    if (java.nio.file.Files.isDirectory(sched)) {
                        dirs.add(sched);
                    }
                }
            } catch (Exception ignored) {
            }
            // 在所有扫描目录里清除同名副本（去扩展名、大小写不敏感——RRR/rrr 都删）
            for (java.nio.file.Path dir : dirs) {
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path hit : files
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                int dot = n.lastIndexOf('.');
                                String nb = dot > 0 ? n.substring(0, dot) : n;
                                return nb.equalsIgnoreCase(base);
                            })
                            .toList()) {
                        java.nio.file.Files.deleteIfExists(hit);
                        deletedAny = true;
                        LOGGER.info("deleteBlueprint: 清理副本 {}", hit);
                    }
                } catch (Exception ignored) {
                }
            }
            if (!deletedAny) {
                return false; // 内置蓝图（无文件）或全部未找到
            }
            EXTERNAL.remove(id);
            EXTERNAL_NAMES.remove(id);
            EXTERNAL_PATHS.remove(id);
            EXTERNAL_MTIMES.remove(id);
            NEEDS_CACHE.remove(id);
            NEEDS_MTIME.remove(id);
            DESCRIBE_CACHE.remove(id);
            LOGGER.info("deleteBlueprint: 已删除蓝图 {} ({})", id, base);
            return true;
        } catch (Exception e) {
            LOGGER.warn("deleteBlueprint: 删除 {} 失败 -> {}", id, e.toString());
            return false;
        }
    }

    /**
     * 解析标准结构文件（结构方块导出格式）为步骤列表。
     * 支持 rotation（顺时针 0/90/180/270，需 holder 才能旋转方块状态；holder 为 null 时忽略旋转）。
     * 规则（对齐 numen）：
     * - 黑名单方块 / 液体 / 无物品方块 / 二次半块（门上半、床头）→ 跳过
     * - v1.5.317：keepFluids=true（红石机器蓝图）→ 水/岩浆保留为可建步骤
     *   （机器水道/气泡柱/岩浆焚烧口需要）；普通建筑维持剥离（防洪水/岩浆事故）。
     * - 精确 BlockState 与方块实体数据随步骤携带（|stateSnbt|beSnbt）
     */
    public static List<String> parseStructure(net.minecraft.nbt.CompoundTag tag, int quarters,
                                              net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder,
                                              boolean keepFluids) {
        try {
            net.minecraft.nbt.ListTag sizeTag = tag.m_128437_("size", 3);
            if (sizeTag.m_128763_(0) < 1) {
                return null;
            }
            int sx = sizeTag.m_128763_(0);
            int sy = sizeTag.m_128763_(1);
            int sz = sizeTag.m_128763_(2);
            net.minecraft.nbt.ListTag paletteTag = tag.m_128425_("palettes", 9)
                    ? tag.m_128437_("palettes", 9).m_128744_(0)
                    : tag.m_128437_("palette", 10);
            if (paletteTag == null || paletteTag.size() == 0) {
                return null;
            }
            net.minecraft.world.level.block.Rotation rotation = net.minecraft.world.level.block.Rotation.NONE;
            int q = Math.floorMod(quarters, 4);
            if (q == 1) {
                rotation = net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            } else if (q == 2) {
                rotation = net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            } else if (q == 3) {
                rotation = net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            }
            // 旋转后的 palette 状态（numen 同款：m_247651_ 解析 + m_60717_ 旋转）
            List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
            for (int i = 0; i < paletteTag.size(); i++) {
                net.minecraft.nbt.CompoundTag stateTag = paletteTag.m_128728_(i);
                if (q != 0 && holder != null) {
                    net.minecraft.world.level.block.state.BlockState state =
                            net.minecraft.nbt.NbtUtils.m_247651_(holder, stateTag).m_60717_(rotation);
                    stateTag = net.minecraft.nbt.NbtUtils.m_129202_(state);
                }
                palette.add(stateTag);
            }
            net.minecraft.nbt.ListTag blocks = tag.m_128437_("blocks", 10);
            List<String> steps = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag cell = blocks.m_128728_(i);
                net.minecraft.nbt.ListTag pos = cell.m_128437_("pos", 3);
                int x = pos.m_128763_(0);
                int y = pos.m_128763_(1);
                int z = pos.m_128763_(2);
                int stateIndex = cell.m_128451_("state");
                if (stateIndex < 0 || stateIndex >= palette.size()) {
                    continue;
                }
                // 坐标旋转（numen 同款矩阵）
                int rx;
                int rz;
                if (q == 1) {
                    rx = sz - 1 - z;
                    rz = x;
                } else if (q == 2) {
                    rx = sx - 1 - x;
                    rz = sz - 1 - z;
                } else if (q == 3) {
                    rx = z;
                    rz = sx - 1 - x;
                } else {
                    rx = x;
                    rz = z;
                }
                net.minecraft.nbt.CompoundTag stateTag = palette.get(stateIndex);
                String blockName = stateTag.m_128461_("Name");
                // v1.5.317：机器蓝图（keepFluids）保留水/岩浆；其余黑名单照旧剥离
                boolean fluidKept = keepFluids && ("minecraft:water".equals(blockName)
                        || "minecraft:lava".equals(blockName));
                if (FORBIDDEN.contains(blockName) && !fluidKept) {
                    continue;
                }
                // 二次半块（门上半/床头）跳过——放置主半块时 MC 自动补全
                if (isSecondaryHalf(stateTag)) {
                    continue;
                }
                net.minecraft.world.level.block.Block block =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockName));
                if (block == null || (block.m_5456_() == net.minecraft.world.item.Items.f_41852_ && !fluidKept)) {
                    continue; // 无此方块或没有对应物品（对齐 numen：无物品方块跳过）
                }
                StringBuilder step = new StringBuilder();
                step.append(rx).append(',').append(y).append(',').append(rz).append(',').append(blockName);
                // 精确状态（台阶/楼梯朝向等）随步骤携带
                if (stateTag.m_128425_("Properties", 10)) {
                    step.append('|').append(net.minecraft.nbt.NbtUtils.m_178057_(stateTag));
                }
                // 方块实体数据（箱子内容/告示牌文字等）
                if (cell.m_128425_("nbt", 10)) {
                    step.append('|').append(net.minecraft.nbt.NbtUtils.m_178057_(cell.m_128469_("nbt")));
                }
                steps.add(step.toString());
            }
            if (steps.isEmpty() || steps.size() > structureMaxBlocks()) {
                return null;
            }
            return steps;
        } catch (Exception e) {
            // v1.5.26：记录真实异常（之前静默吞掉 → 日志只有"解析返回 null"看不到原因）
            LOGGER.warn("parseStructure: 解析结构异常 -> {}", e.toString());
            return null;
        }
    }

    /** 兼容重载：非机器路径（普通建筑 .nbt/旋转）默认剥离水/岩浆 */
    public static List<String> parseStructure(net.minecraft.nbt.CompoundTag tag, int quarters,
                                              net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        return parseStructure(tag, quarters, holder, false);
    }

    /** 门上半/床床头等二次半块（放置主半块时 MC 自动补全，跳过避免重复消耗） */
    private static boolean isSecondaryHalf(net.minecraft.nbt.CompoundTag stateTag) {
        try {
            if (stateTag.m_128425_("Properties", 10)) {
                net.minecraft.nbt.CompoundTag props = stateTag.m_128469_("Properties");
                if ("upper".equals(props.m_128461_("half"))) {
                    return true;
                }
                if ("head".equals(props.m_128461_("part"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /* ================= 格式转换器（v1.5.14，迁移自 numen BlueprintFormats） =================
     * .litematic（Litematica 模组导出）/ .schem（WorldEdit/Schematica 导出）/
     * .schematic（v1.5.37 Planet Minecraft 标准 MCEdit 格式）→ 标准结构格式
     * （size/palette/blocks/entities），随后统一走 parseStructure。
     * 网上分享的复杂精美建筑图纸绝大多数是这三种格式——这是"能建精美建筑"的关键。
     */

    private static net.minecraft.nbt.CompoundTag fromLitematic(net.minecraft.nbt.CompoundTag root) {
        net.minecraft.nbt.CompoundTag regions = root.m_128469_("Regions");
        if (regions.m_128456_()) {            throw new IllegalArgumentException("litematic has no regions");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        java.util.List<net.minecraft.nbt.CompoundTag> regionTags = new ArrayList<>();
        for (String key : regions.m_128431_()) {
            net.minecraft.nbt.CompoundTag region = regions.m_128469_(key);
            regionTags.add(region);
            int[] min = litematicRegionMin(region);
            int[] abs = litematicRegionAbsSize(region);
            minX = Math.min(minX, min[0]);
            minY = Math.min(minY, min[1]);
            minZ = Math.min(minZ, min[2]);
            maxX = Math.max(maxX, min[0] + abs[0] - 1);
            maxY = Math.max(maxY, min[1] + abs[1] - 1);
            maxZ = Math.max(maxZ, min[2] + abs[2] - 1);
        }
        net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.ListTag blocks = new net.minecraft.nbt.ListTag();
        for (net.minecraft.nbt.CompoundTag region : regionTags) {
            int paletteBase = palette.size();
            net.minecraft.nbt.ListTag regionPalette = region.m_128437_("BlockStatePalette", 10);
            boolean[] isAir = new boolean[regionPalette.size()];
            for (int i = 0; i < regionPalette.size(); i++) {
                net.minecraft.nbt.CompoundTag entry = regionPalette.m_128728_(i);
                isAir[i] = entry.m_128461_("Name").endsWith("air");
                palette.add(entry.m_6426_());
            }
            int[] min = litematicRegionMin(region);
            int[] abs = litematicRegionAbsSize(region);
            long[] packed = region.m_128467_("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, regionPalette.size() - 1)));
            java.util.Map<Long, net.minecraft.nbt.CompoundTag> regionData = new HashMap<>();
            for (net.minecraft.nbt.Tag t : region.m_128437_("TileEntities", 10)) {
                net.minecraft.nbt.CompoundTag be = ((net.minecraft.nbt.CompoundTag) t).m_6426_();
                long key = key3(be.m_128451_("x"), be.m_128451_("y"), be.m_128451_("z"));
                be.m_128473_("x");
                be.m_128473_("y");
                be.m_128473_("z");
                regionData.put(key, be);
            }
            long volume = (long) abs[0] * abs[1] * abs[2];
            if (volume > com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()) {
                throw new IllegalArgumentException("litematic region too large: " + abs[0] + "x" + abs[1] + "x" + abs[2]);
            }
            for (long i = 0L; i < volume; i++) {
                int idx = unpackPacked(packed, bits, i);
                if (idx >= regionPalette.size() || isAir[idx]) {
                    continue;
                }
                int x = (int) (i % abs[0]);
                int z = (int) (i / abs[0] % abs[2]);
                int y = (int) (i / ((long) abs[0] * abs[2]));
                blocks.add(litematicCell(min[0] + x - minX, min[1] + y - minY, min[2] + z - minZ,
                        paletteBase + idx, regionData.get(key3(x, y, z))));
            }
        }
        return assembleStructure(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, palette, blocks, entities);
    }

    private static int[] litematicRegionMin(net.minecraft.nbt.CompoundTag region) {
        net.minecraft.nbt.CompoundTag pos = region.m_128469_("Position");
        net.minecraft.nbt.CompoundTag size = region.m_128469_("Size");
        return new int[]{pos.m_128451_("x") + Math.min(0, size.m_128451_("x") + 1),
                pos.m_128451_("y") + Math.min(0, size.m_128451_("y") + 1),
                pos.m_128451_("z") + Math.min(0, size.m_128451_("z") + 1)};
    }

    private static int[] litematicRegionAbsSize(net.minecraft.nbt.CompoundTag region) {
        net.minecraft.nbt.CompoundTag size = region.m_128469_("Size");
        return new int[]{Math.abs(size.m_128451_("x")), Math.abs(size.m_128451_("y")), Math.abs(size.m_128451_("z"))};
    }

    /** Litematica 变长打包解包（bit-packed long[]） */
    private static int unpackPacked(long[] longs, int bits, long index) {
        long mask = (1L << bits) - 1L;
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) ((startOffset + bits - 1) >> 6);
        int startBit = (int) (startOffset & 0x3FL);
        if (startArr >= longs.length) {
            return 0;
        }
        if (startArr == endArr) {
            return (int) (longs[startArr] >>> startBit & mask);
        }
        int endOffset = 64 - startBit;
        long high = endArr < longs.length ? longs[endArr] : 0L;
        return (int) ((longs[startArr] >>> startBit | high << endOffset) & mask);
    }

    private static net.minecraft.nbt.CompoundTag fromSchem(net.minecraft.nbt.CompoundTag root) {
        if (root.m_128425_("Schematic", 10)) {
            root = root.m_128469_("Schematic");
        }
        net.minecraft.nbt.CompoundTag blocksHolder = root.m_128425_("Blocks", 10) ? root.m_128469_("Blocks") : root;
        int width = root.m_128448_("Width") & 0xFFFF;
        int height = root.m_128448_("Height") & 0xFFFF;
        int length = root.m_128448_("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IllegalArgumentException("schem has zero dimension");
        }
        if ((long) width * height * length > com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()) {
            throw new IllegalArgumentException("schem too large: " + width + "x" + height + "x" + length);
        }
        java.util.Map<Long, net.minecraft.nbt.CompoundTag> beData = new HashMap<>();
        net.minecraft.nbt.ListTag beList = blocksHolder.m_128425_("BlockEntities", 9)
                ? blocksHolder.m_128437_("BlockEntities", 10) : root.m_128437_("BlockEntities", 10);
        for (net.minecraft.nbt.Tag t : beList) {
            net.minecraft.nbt.CompoundTag be = (net.minecraft.nbt.CompoundTag) t;
            int[] at = be.m_128465_("Pos");
            if (at.length != 3) {
                continue;
            }
            net.minecraft.nbt.CompoundTag data = be.m_128425_("Data", 10) ? be.m_128469_("Data").m_6426_() : be.m_6426_();
            data.m_128473_("Pos");
            data.m_128473_("Id");
            if (be.m_128425_("Id", 8)) {
                data.m_128359_("id", be.m_128461_("Id"));
            }
            beData.put(key3(at[0], at[1], at[2]), data);
        }
        net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag paletteMap = blocksHolder.m_128469_("Palette");
        int maxId = -1;
        for (String key : paletteMap.m_128431_()) {
            maxId = Math.max(maxId, paletteMap.m_128451_(key));
        }
        net.minecraft.nbt.CompoundTag[] byId = new net.minecraft.nbt.CompoundTag[maxId + 1];
        boolean[] isAir = new boolean[maxId + 1];
        for (String key : paletteMap.m_128431_()) {
            int id = paletteMap.m_128451_(key);
            byId[id] = parseStateString(key);
            isAir[id] = byId[id].m_128461_("Name").endsWith("air");
        }
        net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
        int[] remap = new int[maxId + 1];
        for (int id = 0; id <= maxId; id++) {
            remap[id] = palette.size();
            if (byId[id] != null) {
                palette.add(byId[id]);
            }
        }
        byte[] data = blocksHolder.m_128425_("Data", 7) ? blocksHolder.m_128463_("Data") : blocksHolder.m_128463_("BlockData");
        net.minecraft.nbt.ListTag blocks = new net.minecraft.nbt.ListTag();
        int cursor = 0;
        long volume = (long) width * height * length;
        for (long i = 0L; i < volume && cursor < data.length; i++) {
            int id = 0;
            int shift = 0;
            while (true) {
                byte b = data[cursor++];
                id |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (id > maxId || byId[id] == null || isAir[id]) {
                continue;
            }
            int x = (int) (i % width);
            int z = (int) (i / width % length);
            int y = (int) (i / ((long) width * length));
            blocks.add(litematicCell(x, y, z, remap[id], beData.get(key3(x, y, z))));
        }
        return assembleStructure(width, height, length, palette, blocks, entities);
    }

    /* ============ .schematic（v1.5.37，Planet Minecraft 标准 MCEdit 老格式） ============
     * 结构与 .schem 完全不同：Width/Height/Length + Blocks（旧版方块 ID 字节数组）
     * + Data（旧版 metadata）。通过 mapSchematicBlock(id, data) 映射为 1.13+ 方块名。
     * 未映射的 ID（空气/液体/基岩等）返回 null → 跳过。
     */
    private static net.minecraft.nbt.CompoundTag fromSchematic(net.minecraft.nbt.CompoundTag root) {
        int width = root.m_128448_("Width") & 0xFFFF;
        int height = root.m_128448_("Height") & 0xFFFF;
        int length = root.m_128448_("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IllegalArgumentException("schematic has zero dimension");
        }
        if ((long) width * height * length > com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()) {
            throw new IllegalArgumentException("schematic too large: " + width + "x" + height + "x" + length);
        }
        byte[] blocks = root.m_128463_("Blocks");
        byte[] data = root.m_128425_("Data", 7) ? root.m_128463_("Data") : new byte[0];
        java.util.List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
        java.util.Map<String, Integer> palIdx = new HashMap<>();
        net.minecraft.nbt.ListTag cellList = new net.minecraft.nbt.ListTag();
        long volume = (long) width * height * length;
        for (long i = 0L; i < volume && i < blocks.length; i++) {
            int id = blocks[(int) i] & 0xFF;
            int dv = i < data.length ? (data[(int) i] & 0xFF) : 0;
            String name = mapSchematicBlock(id, dv);
            if (name == null) {
                continue; // 空气/液体/基岩/无物品等：跳过
            }
            Integer idx = palIdx.get(name + "|" + dv);
            if (idx == null) {
                net.minecraft.nbt.CompoundTag st = new net.minecraft.nbt.CompoundTag();
                st.m_128359_("Name", name);
                // v1.5.45：旧版 data 值 → 1.13+ 方块状态（火把/按钮/拉杆朝向等）——
                // 旧版 mapSchematicBlock 丢弃 data → 全部默认朝上/朝北 → 悬空火把/拉杆
                applyLegacyData(id, dv, st);
                palette.add(st);
                idx = palette.size() - 1;
                palIdx.put(name + "|" + dv, idx);
            }
            int x = (int) (i % width);
            int z = (int) (i / width % length);
            int y = (int) (i / ((long) width * length));
            cellList.add(litematicCell(x, y, z, idx, null));
        }
        net.minecraft.nbt.ListTag paletteList = new net.minecraft.nbt.ListTag();
        paletteList.addAll(palette);
        return assembleStructure(width, height, length, paletteList, cellList, new net.minecraft.nbt.ListTag());
    }

    /** v1.5.45：旧版方块 ID + data → 1.13+ 方块状态 Properties（朝向/上下/延迟等）。
     *  旧版 .schematic 的 data 编码朝向（火把 1-4 侧挂、拉杆 0-7、按钮 2-5 等），
     *  不恢复则全部按默认状态放置 → 悬空火把/按钮/拉杆。 */
    private static void applyLegacyData(int id, int data, net.minecraft.nbt.CompoundTag st) {
        switch (id) {
            case 50, 75, 76 -> { // torch / redstone_torch：1-4 侧挂（1=east,2=west,3=south,4=north），5=up（默认）
                int d = data & 7;
                if (d >= 1 && d <= 4) {
                    st.m_128359_("facing", new String[]{"east", "west", "south", "north"}[d - 1]);
                }
            }
            case 69 -> { // lever：0-3 地面（0=east,1=west,2=south,3=north），4-7 墙挂（east/west/south/north）
                int d = data & 7;
                if (d <= 3) {
                    st.m_128359_("face", "floor");
                    st.m_128359_("facing", new String[]{"east", "west", "south", "north"}[d]);
                } else {
                    st.m_128359_("face", "wall");
                    st.m_128359_("facing", new String[]{"east", "west", "south", "north"}[d - 4]);
                }
            }
            case 77, 143 -> { // button：2-5 墙挂（2=north,3=south,4=west,5=east）
                int d = data & 7;
                if (d >= 2 && d <= 5) {
                    st.m_128359_("face", "wall");
                    st.m_128359_("facing", new String[]{"north", "south", "west", "east"}[d - 2]);
                }
            }
            case 93, 94 -> { // redstone_repeater：0-3 朝向（0=east,1=south,2=west,3=north）+ 延迟档
                int d = data & 15;
                st.m_128359_("facing", new String[]{"east", "south", "west", "north"}[d & 3]);
                int delay = ((d >> 2) & 3) + 1;
                if (delay > 1) {
                    st.m_128405_("delay", delay);
                }
            }
            case 29, 33 -> { // piston / sticky_piston：0-5 朝向（0=down,1=up,2=north,3=south,4=west,5=east）
                int d = data & 7;
                if (d <= 5) {
                    st.m_128359_("facing", new String[]{"down", "up", "north", "south", "west", "east"}[d]);
                }
            }
            case 23, 158 -> { // dispenser / dropper：同活塞朝向
                int d = data & 7;
                if (d <= 5) {
                    st.m_128359_("facing", new String[]{"down", "up", "north", "south", "west", "east"}[d]);
                }
            }
            case 54, 146, 61, 65 -> { // chest / trapped_chest / furnace / ladder：2-5 朝向
                int d = data & 7;
                if (d >= 2 && d <= 5) {
                    st.m_128359_("facing", new String[]{"north", "south", "west", "east"}[d - 2]);
                }
            }
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180 -> { // stairs：0-3 朝向 + 4=上下翻转
                int d = data & 7;
                st.m_128359_("facing", new String[]{"east", "west", "south", "north"}[d & 3]);
                if ((d & 4) != 0) {
                    st.m_128359_("half", "top");
                }
            }
            case 44, 126, 182 -> { // slab：8=上半
                if ((data & 8) != 0) {
                    st.m_128359_("type", "top");
                }
            }
            case 96, 167 -> { // trapdoor：&3 朝向（0=north,1=south,2=west,3=east），&8=上半
                int d = data & 15;
                st.m_128359_("facing", new String[]{"north", "south", "west", "east"}[d & 3]);
                if ((d & 8) != 0) {
                    st.m_128359_("half", "top");
                }
            }
            case 154 -> { // hopper：0=down，2-5 = north/south/west/east
                int d = data & 7;
                if (d == 0) {
                    st.m_128359_("facing", "down");
                } else if (d >= 2 && d <= 5) {
                    st.m_128359_("facing", new String[]{"north", "south", "west", "east"}[d - 2]);
                }
            }
            case 64, 193, 194, 195, 196, 197 -> { // 门（下半，各材质）：&3 朝向（0=west,1=north,2=east,3=south）
                int d = data & 7;
                st.m_128359_("facing", new String[]{"west", "north", "east", "south"}[d & 3]);
            }
            default -> {
            }
        }
    }

    /** v1.5.81：强制拆除——目标方块【破坏成掉落物】（可回收，玩家可捡）。
     *  普通模式与强制建造模式统一使用。清除用 flag 2 静默（不触发邻居更新，
     *  建造期稳定——重力由 GravityFreezeMixin 冻结，红石由 recalcRedstone 收尾）。
     *  dropResources SRG：Block.m_49950_(BlockState, Level, BlockPos)。 */
    public static void forceBreak(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.core.BlockPos pos,
                                  net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.world.level.block.Block.m_49950_(state, level, pos); // dropResources
        level.m_7731_(pos, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 2);
        level.m_46796_(2001, pos, net.minecraft.world.level.block.Block.m_49956_(state));
    }

    /** v1.5.46：清理建造原点附近掉落物（悬空方块历史掉落的物品堆积，完成时调用一次） */
    /** v1.5.46：清理建造区掉落物（悬空方块历史掉落的物品堆积，实体区块曾达 3.36MB）。
     *  v1.5.75：清理范围限制到【蓝图包围盒 + 4 格边距】——旧版固定 96 格球形会把
     *  建筑外的玩家丢弃物/战利品误清。plan 为空时回退旧行为（不动）。 */
    public static void cleanupDrops(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            try {
                minX = Math.min(minX, Integer.parseInt(parts[0]));
                maxX = Math.max(maxX, Integer.parseInt(parts[0]));
                minY = Math.min(minY, Integer.parseInt(parts[1]));
                maxY = Math.max(maxY, Integer.parseInt(parts[1]));
                minZ = Math.min(minZ, Integer.parseInt(parts[2]));
                maxZ = Math.max(maxZ, Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        if (minX > maxX) {
            return;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                origin.m_123341_() + minX - 4.0, origin.m_123342_() + minY - 4.0, origin.m_123343_() + minZ - 4.0,
                origin.m_123341_() + maxX + 5.0, origin.m_123342_() + maxY + 5.0, origin.m_123343_() + maxZ + 5.0);
        // v1.5.81：只清理【蓝图方块】的掉落物（悬空放置失败的历史堆积）；
        // 强制建造拆除的非蓝图方块掉落物（玩家可回收的材料）保留不清。
        java.util.Set<String> planBlocks = new java.util.HashSet<>();
        for (int i = 1; i < plan.size(); i++) {
            String[] pp = parseStep(plan.get(i));
            if (pp != null) {
                planBlocks.add(pp[3]);
            }
        }
        int n = 0;
        for (net.minecraft.world.entity.item.ItemEntity e
                : level.m_45976_(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            net.minecraft.world.item.ItemStack stack = e.m_32055_(); // ItemEntity.getItem
            if (stack.m_41619_()) {
                continue;
            }
            net.minecraft.resources.ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (itemId != null && planBlocks.contains(itemId.toString())) {
                e.m_142687_(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED); // discard
                n++;
            }
        }
        if (n > 0) {
            LOGGER.info("cleanupDrops: 清理建造区掉落物 {} 个", n);
        }
    }

    /** v1.5.45：附着方块的支撑方向（null = 无需支撑）。
     *  火把/按钮/拉杆/梯子/墙牌/活板门/红石线/中继器/压板/铁轨/绊线钩等——
     *  支撑面缺失时放置会悬空掉落；放置前检查支撑，缺失则延后等支撑块建好。 */
    public static net.minecraft.core.Direction supportDirection(net.minecraft.world.level.block.state.BlockState state) {
        Block block = state.m_60734_();
        boolean attach = block instanceof net.minecraft.world.level.block.TorchBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock
                || block instanceof net.minecraft.world.level.block.LadderBlock
                || block instanceof net.minecraft.world.level.block.WallSignBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                // v1.5.46：红石科技件与机械件——同样需要支撑（悬空会掉落成物品）
                || block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || block instanceof net.minecraft.world.level.block.DiodeBlock
                || block instanceof net.minecraft.world.level.block.BaseRailBlock
                || block instanceof net.minecraft.world.level.block.PressurePlateBlock
                || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                || block instanceof net.minecraft.world.level.block.FlowerPotBlock
                || block instanceof net.minecraft.world.level.block.BrewingStandBlock
                || block instanceof net.minecraft.world.level.block.CauldronBlock
                || block instanceof net.minecraft.world.level.block.TripWireHookBlock
                || block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.StandingSignBlock
                || block instanceof net.minecraft.world.level.block.WaterlilyBlock
                // v1.5.51：装饰类同样需要支撑——地毯/花/草/蕨/作物/蘑菇/甘蔗/树苗
                // （BushBlock 基类全包）/雪层/站立旗帜 → 下方；墙挂旗帜 → 墙面
                || block instanceof net.minecraft.world.level.block.CarpetBlock
                || block instanceof net.minecraft.world.level.block.BushBlock
                || block instanceof net.minecraft.world.level.block.SnowLayerBlock
                || block instanceof net.minecraft.world.level.block.BannerBlock
                || block instanceof net.minecraft.world.level.block.WallBannerBlock
                // v1.5.82：甘蔗需要下方支撑（沙子/泥土/甘蔗），缺失会掉
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                // v1.5.268：藤蔓/发光地衣——贴墙/贴面附着方块，缺墙会掉落。
                // 旧版漏网：supportDirection 返回 null → 墙未建时直接放 → 掉落
                // → 3 次失败永久跳过 → 墙建好后也不补（现代红石智能住宅大量
                // 藤蔓 skip，完成时报"悬空放不上"）
                || block instanceof net.minecraft.world.level.block.VineBlock
                || block instanceof net.minecraft.world.level.block.GlowLichenBlock
                // v1.5.268：可可豆贴丛林木、仙人掌需沙地——同样漏网（缺支撑掉落）
                || block instanceof net.minecraft.world.level.block.CocoaBlock
                || block instanceof net.minecraft.world.level.block.CactusBlock;
        if (!attach) {
            return null;
        }
        // v1.5.218：门/活板门/中继器的 facing 是【开合/输出方向】，不是附着面——
        // 旧版按 facing 判成"需要 facing 方向的墙"→ 支撑格永远不满足（门口/输出
        // 方向通常是空气）→ 延后 3 次永久跳过 = "民居几百个悬空搭不上的方块"根因
        if (block instanceof net.minecraft.world.level.block.DoorBlock) {
            return net.minecraft.core.Direction.DOWN; // 门：支撑在下方（门框/地板）
        }
        if (block instanceof net.minecraft.world.level.block.TrapDoorBlock) {
            // 活板门：half=top → 上方支撑；bottom → 下方支撑（原版 canSurvive 同款）
            for (net.minecraft.world.level.block.state.properties.Property<?> p : state.m_61147_()) {
                if ("half".equals(p.m_61708_())) {
                    return "top".equals(String.valueOf(state.m_61143_(p)))
                            ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN;
                }
            }
            return net.minecraft.core.Direction.DOWN;
        }
        if (block instanceof net.minecraft.world.level.block.DiodeBlock) {
            return net.minecraft.core.Direction.DOWN; // 中继器/比较器：支撑在下方
        }
        // v1.5.268：藤蔓/发光地衣——多方向布尔属性（north/east/south/west/up/down
        // 任一为 true = 贴该面）→ 支撑方向取任意一个贴面方向（墙在建好前延后，
        // 建好后补建；悬空无墙时补石头）
        if (block instanceof net.minecraft.world.level.block.VineBlock
                || block instanceof net.minecraft.world.level.block.GlowLichenBlock) {
            for (net.minecraft.world.level.block.state.properties.Property<?> p : state.m_61147_()) {
                String n = p.m_61708_();
                if (("north".equals(n) || "east".equals(n) || "south".equals(n)
                        || "west".equals(n) || "up".equals(n) || "down".equals(n))
                        && Boolean.TRUE.equals(state.m_61143_(p))) {
                    net.minecraft.core.Direction d = dirByName(n);
                    if (d != null) {
                        return d; // 支撑 = 贴面所在方向（墙）
                    }
                }
            }
            return net.minecraft.core.Direction.DOWN;
        }
        String facing = null;
        String face = null;
        String half = null;
        // 按属性名遍历（避开 SRG 字段名依赖）
        for (net.minecraft.world.level.block.state.properties.Property<?> p : state.m_61147_()) {
            String n = p.m_61708_();
            if ("facing".equals(n)) {
                facing = String.valueOf(state.m_61143_(p));
            } else if ("face".equals(n)) {
                face = String.valueOf(state.m_61143_(p));
            } else if ("half".equals(n)) {
                half = String.valueOf(state.m_61143_(p));
            }
        }
        if (facing == null) {
            return net.minecraft.core.Direction.DOWN; // 朝上火把等 → 下方支撑
        }
        net.minecraft.core.Direction dir = dirByName(facing);
        if (dir == null) {
            return net.minecraft.core.Direction.DOWN;
        }
        if ("wall".equals(face) || block instanceof net.minecraft.world.level.block.LadderBlock
                || block instanceof net.minecraft.world.level.block.WallSignBlock
                || block instanceof net.minecraft.world.level.block.TripWireHookBlock
                || block instanceof net.minecraft.world.level.block.WallBannerBlock) {
            return dir; // 墙挂 → 该方向墙
        }
        if ("floor".equals(face)) {
            return net.minecraft.core.Direction.DOWN;
        }
        if ("ceiling".equals(face)) {
            return net.minecraft.core.Direction.UP;
        }
        if ("top".equals(half)) {
            return net.minecraft.core.Direction.UP; // 活板门上半 → 上方支撑
        }
        return net.minecraft.core.Direction.DOWN;
    }

    /**
     * v1.5.349：蓝图指定世界坐标是否为【流体步骤】(水/岩浆)——识别"设计意图的
     * 流体支撑"：村民机水闸活板门等，顶部活板门上方就是水（原版 canSurvive 放
     * 不上，蓝图作者用工具强放；活板门 neighborChanged 只切开合不自毁，javap
     * 实证）。补支撑逻辑识别后直接放置附着块，不替换流体、不无限延后——否则旧
     * 版要么把水换成石头（水闸毁掉→机器不工作），要么永远延后缺失。
     * 只在支撑格已是流体且计划含该格时调用（罕见），O(N) 扫描可接受。
     */
    public static boolean isFluidStepAt(List<String> plan, net.minecraft.core.BlockPos origin,
                                        net.minecraft.core.BlockPos worldPos) {
        int wx = worldPos.m_123341_();
        int wy = worldPos.m_123342_();
        int wz = worldPos.m_123343_();
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            try {
                if (origin.m_123341_() + Integer.parseInt(parts[0]) == wx
                        && origin.m_123342_() + Integer.parseInt(parts[1]) == wy
                        && origin.m_123343_() + Integer.parseInt(parts[2]) == wz) {
                    return "minecraft:water".equals(parts[3]) || "minecraft:lava".equals(parts[3]);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    /**
     * v1.5.78：搭建优先级（拟人化建造顺序）：
     * - 0 = 结构主体（最先）：默认方块——墙/地板/屋顶/承重（石头、砖、木板、混凝土等）
     * - 1 = 功能/家具（次之）：门/活板门/栅栏门/楼梯/台阶/栅栏/墙（walls）/箱子/床
     * - 2 = 装饰与红石机械（最后）：花/草/作物/藤蔓/地毯/火把/按钮/拉杆/压力板/
     *       旗帜/告示牌/花盆/雪层/睡莲/甘蔗/红石线/轨道/活塞/发射器/投掷器/漏斗/
     *       观察者/红石灯/中继器/比较器
     * 同优先级内仍按 y 升序（从下到上）。prioCache 按 blockId 缓存（蓝图同种
     * 方块大量重复，避免每次比较都查注册表）。
     */
    /** v1.5.252x：是否骨架方块——x 或 z 位于蓝图水平轮廓边界（四面墙圈/四角柱/
     *  屋顶边缘 = 骨架，内部 = 填充）。skel = {minX, maxX, minZ, maxZ} */
    private static boolean isSkeleton(String[] parts, int[] skel) {
        try {
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[2]);
            return x == skel[0] || x == skel[1] || z == skel[2] || z == skel[3];
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int buildPriority(String blockId, java.util.Map<String, Integer> prioCache) {
        Integer cached = prioCache.get(blockId);
        if (cached != null) {
            return cached;
        }
        int prio = 1;
        Block block = ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(blockId));
        if (block != null) {
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                // v1.5.314：TNT 最后放（优先级低于红石 prio 2）——先放的红石线/
                // 中继器等激活信号会把已放置的 TNT 点燃（TNT 大炮蓝图实证：
                // "红石信号最后放上去就炸"）；TNT 排最后时信号源已就位稳定，
                // 放置 TNT 不再产生新的红石更新，不会炸。
                prio = 3;
            } else if (block instanceof net.minecraft.world.level.block.BushBlock
                    || block instanceof net.minecraft.world.level.block.CarpetBlock
                    || block instanceof net.minecraft.world.level.block.TorchBlock
                    || block instanceof net.minecraft.world.level.block.ButtonBlock
                    || block instanceof net.minecraft.world.level.block.LeverBlock
                    || block instanceof net.minecraft.world.level.block.PressurePlateBlock
                    || block instanceof net.minecraft.world.level.block.BannerBlock
                    || block instanceof net.minecraft.world.level.block.SignBlock
                    || block instanceof net.minecraft.world.level.block.FlowerPotBlock
                    || block instanceof net.minecraft.world.level.block.SnowLayerBlock
                    || block instanceof net.minecraft.world.level.block.VineBlock
                    || block instanceof net.minecraft.world.level.block.WaterlilyBlock
                    || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                    || block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.RailBlock
                    || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || block instanceof net.minecraft.world.level.block.DispenserBlock
                    || block instanceof net.minecraft.world.level.block.DropperBlock
                    || block instanceof net.minecraft.world.level.block.HopperBlock
                    || block instanceof net.minecraft.world.level.block.ObserverBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneLampBlock
                    || block instanceof net.minecraft.world.level.block.DiodeBlock
                    // v1.5.252p：补常见装饰方块——灯笼/书架/蜡烛/陶罐/发光地衣/
                    // 紫水晶簇/钟乳石/孢子花/大滴水叶/珊瑚/干草捆等原被归为结构
                    // （prio 0）与墙一起先建，拟人化效果被稀释（wizard_tower 实证）
                    || block instanceof net.minecraft.world.level.block.LanternBlock
                    || block instanceof net.minecraft.world.level.block.ChiseledBookShelfBlock
                    || block instanceof net.minecraft.world.level.block.CandleBlock
                    || block instanceof net.minecraft.world.level.block.DecoratedPotBlock
                    || block instanceof net.minecraft.world.level.block.GlowLichenBlock
                    || block instanceof net.minecraft.world.level.block.AmethystClusterBlock
                    || block instanceof net.minecraft.world.level.block.PointedDripstoneBlock
                    || block instanceof net.minecraft.world.level.block.SporeBlossomBlock
                    || block instanceof net.minecraft.world.level.block.BigDripleafBlock
                    || block instanceof net.minecraft.world.level.block.CoralBlock
                    || block instanceof net.minecraft.world.level.block.CoralFanBlock
                    || block instanceof net.minecraft.world.level.block.CoralPlantBlock
                    || block instanceof net.minecraft.world.level.block.HayBlock
                    || block instanceof net.minecraft.world.level.block.CocoaBlock
                    || block instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                    || block instanceof net.minecraft.world.level.block.CaveVinesBlock
                    || block instanceof net.minecraft.world.level.block.CaveVinesPlantBlock
                    || block instanceof net.minecraft.world.level.block.MangrovePropaguleBlock
                    || block instanceof net.minecraft.world.level.block.EndRodBlock
                    || block instanceof net.minecraft.world.level.block.ChainBlock) {
                prio = 2;
            } else if (block instanceof net.minecraft.world.level.block.DoorBlock
                    || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || block instanceof net.minecraft.world.level.block.FenceGateBlock
                    || block instanceof net.minecraft.world.level.block.StairBlock
                    || block instanceof net.minecraft.world.level.block.SlabBlock
                    || block instanceof net.minecraft.world.level.block.FenceBlock
                    || block instanceof net.minecraft.world.level.block.WallBlock
                    || block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BedBlock) {
                prio = 1;
            } else {
                prio = 0;
            }
        }
        prioCache.put(blockId, prio);
        return prio;
    }

    /**
     * v1.5.79：统计建造完成时仍悬空的重力方块数（沙子/沙砾/混凝土粉末/铁砧）——
     * 图纸的悬浮设计在原版物理中不成立：建造期间被重力冻结（GravityFreezeMixin）
     * 保持原位，解冻后会在下一次方块更新时落下。完成时提示玩家。
     * 判定：重力方块步骤的世界位置，下方当前为空气/流体 → 悬空。
     */
    public static int countSuspendedGravity(net.minecraft.server.level.ServerLevel level,
                                            net.minecraft.core.BlockPos origin,
                                            java.util.List<String> plan) {
        int count = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(parts[3]));
            if (block == null || !(block instanceof net.minecraft.world.level.block.FallingBlock)) {
                continue;
            }
            try {
                net.minecraft.core.BlockPos pos = origin.m_7918_(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                net.minecraft.world.level.block.state.BlockState below = level.m_8055_(pos.m_7918_(0, -1, 0));
                // v1.5.259：m_60815_ 是 isSolid——旧版当 isLiquid 用（下方空气或液体→计数）
                if (below.m_60795_()
                        || below.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                    count++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return count;
    }

    private static net.minecraft.core.Direction dirByName(String name) {        return switch (name) {
            case "north" -> net.minecraft.core.Direction.NORTH;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "east" -> net.minecraft.core.Direction.EAST;
            case "west" -> net.minecraft.core.Direction.WEST;
            case "up" -> net.minecraft.core.Direction.UP;
            case "down" -> net.minecraft.core.Direction.DOWN;
            default -> null;
        };
    }

    /** 旧版方块 ID + data → 1.13+ 方块名；null = 跳过（空气/液体/基岩/命令方块等） */
    private static String mapSchematicBlock(int id, int data) {
        switch (id) {
            case 0: return null; // air
            case 1: return "minecraft:stone";
            case 2: return "minecraft:grass_block";
            case 3: return "minecraft:dirt";
            case 4: return "minecraft:cobblestone";
            case 5: return "minecraft:oak_planks";
            case 7: return null; // bedrock
            case 8:
            case 9: return null; // water
            case 10:
            case 11: return null; // lava
            case 12: return "minecraft:sand";
            case 13: return "minecraft:gravel";
            case 14: return "minecraft:gold_ore";
            case 15: return "minecraft:iron_ore";
            case 16: return "minecraft:coal_ore";
            case 17: return new String[]{"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log"}[data & 3];
            case 18: return new String[]{"minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves", "minecraft:jungle_leaves"}[data & 3];
            case 19: return "minecraft:sponge";
            case 20: return "minecraft:glass";
            case 21: return "minecraft:lapis_ore";
            case 23: return "minecraft:dispenser"; // v1.5.39：现代红石住宅缺失映射补全
            case 24: return "minecraft:sandstone";
            case 25: return "minecraft:note_block";
            case 26: return null; // bed（双格方块，跳过）
            case 29: return "minecraft:sticky_piston";
            case 30: return "minecraft:cobweb";
            case 31: return new String[]{"minecraft:dead_bush", "minecraft:tall_grass", "minecraft:fern"}[Math.min(2, data)];
            case 32: return "minecraft:dead_bush";
            case 33: return "minecraft:piston";
            case 34: return null; // piston_head（伸出时自动生成）
            case 35: return "minecraft:" + COLORS[data & 15] + "_wool";
            case 37: return "minecraft:dandelion";
            case 38: return "minecraft:poppy";
            case 39: return "minecraft:brown_mushroom";
            case 40: return "minecraft:red_mushroom";
            case 41: return "minecraft:gold_block";
            case 42: return "minecraft:iron_block";
            case 43: return new String[]{"minecraft:stone", "minecraft:sandstone", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:brick_block", "minecraft:stone_bricks", "minecraft:nether_brick", "minecraft:quartz_block"}[data & 7];
            case 44: return new String[]{"minecraft:stone_slab", "minecraft:sandstone_slab", "minecraft:oak_slab", "minecraft:cobblestone_slab", "minecraft:brick_slab", "minecraft:stone_brick_slab", "minecraft:nether_brick_slab", "minecraft:quartz_slab"}[data & 7];
            case 45: return "minecraft:brick_block";
            case 46: return "minecraft:tnt";
            case 47: return "minecraft:bookshelf";
            case 48: return "minecraft:mossy_cobblestone";
            case 49: return "minecraft:obsidian";
            case 50: return "minecraft:torch";
            case 53: return "minecraft:oak_stairs";
            case 54: return "minecraft:chest";
            case 55: return "minecraft:redstone_wire"; // v1.5.39 补全
            case 56: return "minecraft:diamond_ore";
            case 57: return "minecraft:diamond_block";
            case 58: return "minecraft:crafting_table";
            case 60: return "minecraft:farmland";
            case 61:
            case 62: return "minecraft:furnace";
            case 63: return "minecraft:oak_sign";
            case 64: return (data & 8) != 0 ? null : "minecraft:oak_door"; // 门上半跳过
            case 65: return "minecraft:ladder";
            case 66: return "minecraft:rail";
            case 67: return "minecraft:cobblestone_stairs";
            case 68: return "minecraft:oak_sign";
            case 69: return "minecraft:lever";
            case 70: return "minecraft:stone_pressure_plate"; // v1.5.39 补全
            case 72: return "minecraft:oak_pressure_plate"; // v1.5.39 补全
            case 73:
            case 74: return "minecraft:redstone_ore";
            case 75: return "minecraft:redstone_torch"; // v1.5.39 补全（熄灭态火把按火把放）
            case 76: return "minecraft:redstone_torch";
            case 77: return "minecraft:stone_button";
            case 78: return "minecraft:snow";
            case 79: return "minecraft:ice";
            case 80: return "minecraft:snow_block";
            case 81: return "minecraft:cactus";
            case 82: return "minecraft:clay"; // v1.5.39 修正：82 是泥块不是陶瓦
            case 83: return "minecraft:sugar_cane";
            case 84: return "minecraft:jukebox";
            case 85: return "minecraft:oak_fence";
            case 86: return "minecraft:pumpkin";
            case 87: return "minecraft:netherrack";
            case 88: return "minecraft:soul_sand";
            case 89: return "minecraft:glowstone";
            case 91: return "minecraft:jack_o_lantern";
            case 93:
            case 94: return "minecraft:redstone_repeater"; // v1.5.39 补全（亮/灭态统一）
            case 95: return "minecraft:" + COLORS[data & 15] + "_stained_glass";
            case 96: return "minecraft:oak_trapdoor";
            case 97: return "minecraft:stone";
            case 98: return "minecraft:stone_bricks";
            case 101: return "minecraft:iron_bars";
            case 102: return "minecraft:glass_pane";
            case 103: return "minecraft:melon";
            case 106: return "minecraft:vine";
            case 107: return "minecraft:oak_fence_gate";
            case 108: return "minecraft:brick_stairs";
            case 109: return "minecraft:stone_brick_stairs";
            case 110: return "minecraft:mycelium";
            case 111: return "minecraft:lily_pad";
            case 112: return "minecraft:nether_brick";
            case 113: return "minecraft:nether_brick_fence";
            case 114: return "minecraft:nether_brick_stairs";
            case 117: return "minecraft:brewing_stand"; // v1.5.39 补全
            case 118: return "minecraft:cauldron"; // v1.5.39 补全
            case 121: return "minecraft:end_stone";
            case 122: return "minecraft:end_stone_bricks";
            case 123:
            case 124: return "minecraft:redstone_lamp";
            case 125: return new String[]{"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks"}[data & 7];
            case 126: return new String[]{"minecraft:oak_slab", "minecraft:spruce_slab", "minecraft:birch_slab", "minecraft:jungle_slab", "minecraft:acacia_slab", "minecraft:dark_oak_slab"}[data & 7];
            case 128: return "minecraft:sandstone_stairs";
            case 129: return "minecraft:emerald_block";
            case 130: return "minecraft:emerald_ore";
            case 131: return "minecraft:tripwire_hook"; // v1.5.39 补全
            case 132: return null; // tripwire（无物品方块，绊线由钩子生成）
            case 133: return null; // command block
            case 134: return "minecraft:spruce_stairs";
            case 135: return "minecraft:birch_stairs";
            case 136: return "minecraft:jungle_stairs";
            case 138: return "minecraft:beacon";
            case 139: return "minecraft:cobblestone_wall";
            case 140: return "minecraft:flower_pot"; // v1.5.39 补全
            case 141: return "minecraft:carrots";
            case 142: return "minecraft:potatoes";
            case 143: return "minecraft:oak_button";
            case 145: return "minecraft:anvil";
            case 146: return "minecraft:trapped_chest";
            case 148: return "minecraft:light_weighted_pressure_plate"; // v1.5.39 补全
            case 149: return "minecraft:detector_rail"; // v1.5.39 补全
            case 151: return "minecraft:daylight_detector"; // v1.5.39 补全
            case 152: return "minecraft:redstone_block";
            case 153: return new String[]{"minecraft:quartz_block", "minecraft:chiseled_quartz_block", "minecraft:quartz_pillar"}[data & 3];
            case 154: return "minecraft:hopper"; // v1.5.39 补全
            case 155: return new String[]{"minecraft:quartz_block", "minecraft:chiseled_quartz_block", "minecraft:quartz_pillar"}[data & 3]; // v1.5.39 修正：155 是石英块
            case 156: return "minecraft:quartz_stairs"; // v1.5.39 补全
            case 158: return "minecraft:dropper";
            case 159: return "minecraft:" + COLORS[data & 15] + "_terracotta";
            case 160: return "minecraft:" + COLORS[data & 15] + "_stained_glass_pane";
            case 161: return "minecraft:acacia_leaves";
            case 162: return "minecraft:acacia_log";
            case 163: return "minecraft:acacia_stairs";
            case 164: return "minecraft:dark_oak_stairs";
            case 165: return "minecraft:dark_oak_log";
            case 166: return "minecraft:dark_oak_leaves";
            case 167: return "minecraft:iron_trapdoor";
            case 168: return new String[]{"minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine"}[data & 3]; // v1.5.39 补全
            case 169: return "minecraft:sea_lantern"; // v1.5.39 补全
            case 170: return "minecraft:hay_block";
            case 171: return "minecraft:" + COLORS[data & 15] + "_carpet";
            case 172: return "minecraft:terracotta";
            case 173: return "minecraft:coal_block";
            case 174: return "minecraft:packed_ice";
            case 179: return "minecraft:red_sandstone";
            case 180: return "minecraft:red_sandstone_stairs";
            case 182: return "minecraft:red_sandstone_slab";
            case 188: return "minecraft:spruce_fence";
            case 189: return "minecraft:birch_fence";
            case 190: return "minecraft:jungle_fence";
            case 191: return "minecraft:dark_oak_fence";
            case 192: return "minecraft:acacia_fence";
            case 193: return (data & 8) != 0 ? null : "minecraft:spruce_door";
            case 194: return (data & 8) != 0 ? null : "minecraft:birch_door";
            case 195: return (data & 8) != 0 ? null : "minecraft:jungle_door";
            case 196: return (data & 8) != 0 ? null : "minecraft:acacia_door";
            case 197: return (data & 8) != 0 ? null : "minecraft:dark_oak_door";
            case 198: return "minecraft:end_rod";
            case 199: return "minecraft:chorus_plant";
            case 200: return "minecraft:chorus_flower";
            case 201: return "minecraft:purpur_block";
            case 203: return "minecraft:purpur_slab";
            case 204: return "minecraft:purpur_pillar";
            case 205: return "minecraft:purpur_stairs";
            case 206: return "minecraft:end_stone_bricks";
            case 208: return "minecraft:grass_path";
            default: return null; // 未映射（稀有/特殊方块）→ 跳过
        }
    }

    /** 染色方块 0-15 → 颜色名 */
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow",
            "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /** "minecraft:oak_stairs[facing=north,half=bottom]" → {Name, Properties} */
    private static net.minecraft.nbt.CompoundTag parseStateString(String s) {
        net.minecraft.nbt.CompoundTag out = new net.minecraft.nbt.CompoundTag();
        int bracket = s.indexOf('[');
        if (bracket < 0) {
            out.m_128359_("Name", s);
            return out;
        }
        out.m_128359_("Name", s.substring(0, bracket));
        net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
        String body = s.substring(bracket + 1, s.endsWith("]") ? s.length() - 1 : s.length());
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            props.m_128359_(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        out.m_128365_("Properties", props);
        return out;
    }

    private static long key3(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (long) (z & 0x1FFFFF);
    }

    private static net.minecraft.nbt.CompoundTag litematicCell(int x, int y, int z, int stateIndex,
                                                               net.minecraft.nbt.CompoundTag data) {
        net.minecraft.nbt.CompoundTag cell = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.IntTag.m_128679_(x));
        pos.add(net.minecraft.nbt.IntTag.m_128679_(y));
        pos.add(net.minecraft.nbt.IntTag.m_128679_(z));
        cell.m_128365_("pos", pos);
        cell.m_128405_("state", stateIndex);
        if (data != null && !data.m_128456_()) {
            cell.m_128365_("nbt", data);
        }
        return cell;
    }

    private static net.minecraft.nbt.CompoundTag assembleStructure(int sx, int sy, int sz,
                                                                   net.minecraft.nbt.ListTag palette,
                                                                   net.minecraft.nbt.ListTag blocks,
                                                                   net.minecraft.nbt.ListTag entities) {
        net.minecraft.nbt.CompoundTag out = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.m_128679_(sx));
        size.add(net.minecraft.nbt.IntTag.m_128679_(sy));
        size.add(net.minecraft.nbt.IntTag.m_128679_(sz));
        out.m_128365_("size", size);
        out.m_128365_("palette", palette);
        out.m_128365_("blocks", blocks);
        out.m_128365_("entities", entities);
        return out;
    }

    /** 空气或自然地形（可覆盖）？运行时放置兜底判定 */
    public static boolean isAllowedGround(net.minecraft.world.level.block.state.BlockState state) {
        if (state.m_60795_()) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        return id != null && ALLOWED_GROUND.contains(id.toString());
    }

    /** 解析步骤串 → {x,y,z,blockid,stateSnbt,beSnbt}（state/be 可为 null） */
    public static String[] parseStep(String step) {
        String[] head = step.split("\\|");
        String[] pos = head[0].split(",");
        if (pos.length != 4) {
            return null;
        }
        String[] out = new String[6];
        out[0] = pos[0];
        out[1] = pos[1];
        out[2] = pos[2];
        out[3] = pos[3];
        out[4] = head.length > 1 ? head[1] : null;
        out[5] = head.length > 2 ? head[2] : null;
        return out;
    }

    /** v1.5.159：蓝图占地尺寸 {宽, 高, 深}（相对坐标 min..max +1；无法解析返回 {0,0,0}）——
     *  手册"区块显示"预览用：以玩家为中心展示建造范围 */
    public static int[] blueprintSize(List<String> steps) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        if (steps != null) {
            for (int i = 1; i < steps.size(); i++) {
                String[] p = parseStep(steps.get(i));
                if (p == null) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(p[0]);
                    int y = Integer.parseInt(p[1]);
                    int z = Integer.parseInt(p[2]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return new int[]{0, 0, 0};
        }
        return new int[]{maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
    }

    /** v1.5.375：占地尺寸缓存（id → {宽,高,深}）——8000+ 外部蓝图下，openFor 对每个
     *  蓝图调 blueprintSize 遍历全部步骤（一次 64M 次坐标解析）会卡死主线程；
     *  启动预热后按 id 命中缓存，手册打开零遍历。 */
    private static final Map<String, int[]> SIZE_CACHE = new HashMap<>();

    /** 带缓存的占地尺寸（预热后按 id 命中；未命中时计算并缓存） */
    public static int[] blueprintSizeCached(String id, List<String> steps) {
        if (id != null) {
            int[] c = SIZE_CACHE.get(id);
            if (c != null) {
                return c;
            }
        }
        int[] sz = blueprintSize(steps);
        if (id != null) {
            SIZE_CACHE.put(id, sz);
        }
        return sz;
    }

    /** 统一查找蓝图（旋转 0）：内置优先，其次外部（config/maid_smart/blueprints + 存档 schematics/） */
    public static List<String> getBlueprint(String id) {
        List<String> builtIn = getBuiltIn(id);
        if (builtIn != null) {
            return builtIn;
        }
        scanExternalBlueprints();
        return EXTERNAL.get(id);
    }

    /**
     * 统一查找蓝图并应用旋转（仅外部结构蓝图支持；内置/JSON 不支持旋转时返回 0 度版本）。
     * holder 为 null 时忽略旋转。
     */
    public static List<String> getBlueprintRotated(String id, int quarters,
                                                   net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        List<String> builtIn = getBuiltIn(id);
        if (builtIn != null) {
            return builtIn;
        }
        scanExternalBlueprints();
        java.nio.file.Path p = EXTERNAL_PATHS.get(id);
        if (p == null) {
            return EXTERNAL.get(id);
        }
        String lower = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int q = Math.floorMod(quarters, 4);
        boolean structural = lower.endsWith(".nbt") || lower.endsWith(".snbt")
                || lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic");
        if (q == 0 || !structural || holder == null) {
            return EXTERNAL.get(id);
        }
        try {
            net.minecraft.nbt.CompoundTag tag;
            if (lower.endsWith(".snbt")) {
                // v1.5.27：同 loadExternalFile——TagParser 纯解析（m_178024_ 会清空 palette）
                tag = net.minecraft.nbt.TagParser.m_129359_(java.nio.file.Files.readString(p));
            } else {
                tag = net.minecraft.nbt.NbtIo.m_128937_(p.toFile());
                if (lower.endsWith(".litematic")) {
                    tag = fromLitematic(tag);
                } else if (lower.endsWith(".schem")) {
                    tag = fromSchem(tag);
                } else if (lower.endsWith(".schematic")) {
                    // v1.5.37：Planet Minecraft 标准 MCEdit 格式
                    tag = fromSchematic(tag);
                }
            }
            return parseStructure(tag, q, holder);
        } catch (Exception e) {
            return EXTERNAL.get(id);
        }
    }

    /** 蓝图显示名（内置中文名 / 外部文件名或 JSON name 字段） */
    public static String getBlueprintName(String id) {
        String name = BUILT_IN_NAMES.get(id);
        if (name != null) {
            return name;
        }
        scanExternalBlueprints();
        return EXTERNAL_NAMES.getOrDefault(id, id);
    }

    /** 全部蓝图目录条目（v1.5.16，Promaid 手册 GUI 用）：每项 {id, 显示名, 描述}
     *  v1.5.366：内置 19 个（3 小屋 + 12 新结构建筑 + 3 别墅 + 熔炉）+ 外部蓝图 */
    public static List<String[]> buildCatalogEntries() {
        List<String[]> out = new ArrayList<>();
        // v1.5.373：玩家导入的地图放【第 1 页】（尊重玩家感受——自己导入的内容优先展示，
        // 旧版内置预设占前几页、导入的地图排在最后）
        scanExternalBlueprints();
        int externalCount = 0;
        List<String> externalIds = new ArrayList<>(EXTERNAL.keySet());
        externalIds.sort(String::compareTo);
        for (String id : externalIds) {
            List<String> steps = EXTERNAL.get(id);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            // v1.5.25g：手册目录只显示 ≤catalogMaxBlocks() 的蓝图（防右击卡死）；
            // 超大的仍注册在 EXTERNAL，LLM smart_build 可直接下达（不受此限）
            if (steps.size() > catalogMaxBlocks()) {
                continue;
            }
            out.add(new String[]{id, getBlueprintName(id), describe(id, steps)});
            externalCount++;
        }
        int builtInCount = 0;
        for (String id : BUILT_IN_NAMES.keySet()) {
            List<String> steps = getBuiltIn(id);
            if (steps != null && !steps.isEmpty()) {
                out.add(new String[]{id, getBlueprintName(id), describe(id, steps)});
                builtInCount++;
            }
        }
        // v1.5.25 诊断：内置/外部各多少（LogUtils → 进 latest.log）
        LOGGER.info("buildCatalogEntries: 外部 {} 个, 内置 {} 个, 合计 {} 个", externalCount,
                builtInCount, out.size());
        return out;
    }

    /**
     * 带材料缺口的目录条目（v1.5.18；v1.5.24 改为以【主人背包】为准——材料从主人
     * 背包确认并自动交付，避免多女仆/女仆背包空时误导）。
     * 每项：{id, 显示名, 描述} + 材料清单 Map<物品id, int[]{已有, 需要}>。
     * 已有 = 玩家背包持有（等价族感知），需要 = 蓝图总需求。
     */
    /**
     * 带材料缺口的目录条目（v1.5.18；v1.5.24 改为以【主人背包】为准——材料从主人
     * 背包确认并自动交付，避免多女仆/女仆背包空时误导）。
     * 每项：{id, 显示名, 描述} + 材料清单 Map<物品id, int[]{已有, 需要}>。
     * 已有 = 玩家背包持有（等价族感知），需要 = 蓝图总需求。
     *
     * v1.5.221：材料口径修正（用户要求：区块内已建造 + 女仆背包 + 玩家背包）：
     * - 需要 = 蓝图总需求 − 区块内已累计搭建的（活跃区块计划 placedSet 命中，
     *   含恢复时预登记的世界已建）；
     * - 已有 = 已搭建 + 绑定女仆背包 + 玩家背包；
     * 旧版：需要 = 全量需求（建到一半还显示 0/88 全量）、已有 = 仅玩家背包 →
     * 表单严重虚高，建了一半的材料还提示全缺。
     */
    public static Map<String[], Map<String, int[]>> buildCatalogEntriesWithMaterials(Player player) {
        Map<String[], Map<String, int[]>> out = new java.util.LinkedHashMap<>();
        List<String[]> entries = buildCatalogEntries();
        // v1.5.221：已建统计（活跃区块计划 placedSet）+ 绑定女仆背包统计
        // v1.5.278：已建按【蓝图 id 隔离】——旧版把所有计划的 placedSet 混在一个
        // Map，不同蓝图共用材料时（石质山景别墅 + 现代红石智能住宅都用石头/木板/石砖），
        // 其他计划的已建会把本蓝图"剩余需求"扣成 0 → 材料表只剩 3 种未污染的
        //（用户截图实证：圆石墙/蓝色床/火把，其余全显示"已建完"）
        java.util.Map<String, java.util.Map<String, Integer>> builtByBlueprint = new java.util.HashMap<>();
        java.util.Map<String, Integer> maidHaveMap = new java.util.HashMap<>();
        if (player.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
            for (BuildPlan.PlanState ps : BuildPlan.getPlans(sl)) {
                BuildPlan.Progress prog = BuildPlan.progress(ps);
                if (prog == null) {
                    continue;
                }
                List<String> plan = ps.steps;
                java.util.Map<String, Integer> builtCur = builtByBlueprint.computeIfAbsent(
                        ps.blueprintId, k -> new java.util.HashMap<>());
                for (int i = 1; i < plan.size(); i++) {
                    String[] parts = parseStep(plan.get(i));
                    if (parts == null) {
                        continue;
                    }
                    try {
                        long key = (long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                                | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                                | (Integer.parseInt(parts[2]) & 0x1FFFFF);
                        if (prog.placedSet.contains(key)) {
                            builtCur.merge(parts[3], 1, Integer::sum); // 已累计搭建
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // 绑定过区块计划的女仆背包（本维度）
            net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                    sl.m_141937_(), sl.m_141937_(), sl.m_141937_(),
                    sl.m_151558_(), sl.m_151558_(), sl.m_151558_());
            for (EntityMaid m : sl.m_45976_(EntityMaid.class, whole)) {
                if (BuildPlan.getBoundPlanId(m) == null) {
                    continue;
                }
                net.minecraftforge.items.IItemHandler inv = m.getMaidInv();
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (s.m_41619_()) {
                        continue;
                    }
                    net.minecraft.resources.ResourceLocation key =
                            net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.m_41720_());
                    if (key != null) {
                        maidHaveMap.merge(key.toString(), s.m_41613_(), Integer::sum);
                    }
                }
            }
        }
        for (String[] e : entries) {
            String id = e[0];
            List<String> steps = getBlueprint(id);
            if (steps == null || steps.isEmpty()) {
                continue;
            }
            // 统计总需求（等价族合并到"主方块"上；v1.5.25d：带缓存，避免每次右击
            // 手册都对 59 个蓝图重新遍历上万步骤 → 服务端主线程卡顿）
            Map<String, Integer> need = countNeedsCached(id, steps);
            Map<String, int[]> mats = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> builtMap = builtByBlueprint.getOrDefault(id,
                    java.util.Collections.emptyMap());
            for (Map.Entry<String, Integer> entry : need.entrySet()) {
                int built = builtMap.getOrDefault(entry.getKey(), 0);           // 已搭建
                int maidHave = maidHaveMap.getOrDefault(entry.getKey(), 0);     // 绑定女仆背包
                int remaining = Math.max(0, entry.getValue() - built);          // 剩余需求
                // v1.5.252p：创造模式"已有"直接=剩余需求（显示 X/X 齐）——旧版
                // built + MAX_VALUE 溢出成 -2147483648（-21 亿,用户实测）
                int have = isCreative(player)
                        ? remaining
                        : built + maidHave + countPlayerMaterial(player, entry.getKey()); // 已建+女仆+玩家
                mats.put(entry.getKey(), new int[]{have, remaining});
            }
            // v1.5.318：液体工具/材料需求（水桶/岩浆桶）补进材料表——水/岩浆被
            // countNeeds 排除（FORBIDDEN），但玩家需要知道要备桶：水=1 水桶作工具、
            // 岩浆=N 岩浆桶（放置后返还空桶）
            Map<String, Integer> fluidNeed = fluidBucketNeeds(steps);
            for (Map.Entry<String, Integer> fe : fluidNeed.entrySet()) {
                int remaining = fe.getValue();
                int have = isCreative(player)
                        ? remaining
                        : maidHaveMap.getOrDefault(fe.getKey(), 0)
                                + countPlayerMaterial(player, fe.getKey());
                mats.put(fe.getKey(), new int[]{have, remaining});
            }
            out.put(e, mats);
        }
        return out;
    }

    /** v1.5.25h：describe 结果缓存（id → 摘要）——右击手册时 buildCatalogEntries 对每个
     *  蓝图调 describe 遍历全部步骤（上万块大蓝图），不缓存每次右击都卡 5 秒 */
    private static final Map<String, String> DESCRIBE_CACHE = new HashMap<>();

    /** 蓝图摘要（尺寸/块数/材料 top5），供 smart_build 汇报。
     *  v1.5.25h：按 id 缓存（外部蓝图 mtime 变化时由 scanExternalBlueprints 清缓存） */
    public static String describe(String id, List<String> steps) {
        if (id != null) {
            String cached = DESCRIBE_CACHE.get(id);
            if (cached != null) {
                return cached;
            }
        }
        if (steps == null || steps.isEmpty()) {
            return "空蓝图";
        }
        StringBuilder sb = new StringBuilder();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        if (minX <= maxX) {
            sb.append(maxX - minX + 1).append('x').append(maxY - minY + 1).append('x').append(maxZ - minZ + 1);
            // v1.5.278：块数用【去重后】计数——与下达建造的 plan 一致（旧版用原始
            // steps.size()，生成器/转换器的同坐标重复步骤导致"共2138块" vs 进度
            // "2024块"不一致，用户截图实证 114 块差额）
            int n = 0;
            for (String s : dedupeSteps(steps)) {
                if (parseStep(s) != null) {
                    n++;
                }
            }
            sb.append("，共 ").append(n).append(" 块");
        } else {
            sb.append("共 ").append(steps.size()).append(" 块");
        }
        // 材料统计 top5
        Map<String, Integer> counts = new HashMap<>();
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts != null) {
                counts.merge(parts[3], 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        if (!sorted.isEmpty()) {
            sb.append("，材料：");
            int shown = Math.min(5, sorted.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append('、');
                }
                Map.Entry<String, Integer> e = sorted.get(i);
                sb.append(cnName(e.getKey())).append('x').append(e.getValue());
            }
            if (sorted.size() > shown) {
                sb.append(" 等 ").append(sorted.size()).append(" 种");
            }
        }
        // v1.5.318：液体工具/材料需求（水桶/岩浆桶）单独提示——水/岩浆不在普通
        // 材料统计（FORBIDDEN 排除），但玩家需要知道要备桶
        String fluidText = fluidNeedText(steps);
        if (!fluidText.isEmpty()) {
            sb.append('，').append(fluidText);
        }
        String result = sb.toString();
        if (id != null) {
            DESCRIBE_CACHE.put(id, result); // v1.5.25h：缓存，右击手册不再重算
        }
        return result;
    }

    /** 小木屋：5x5 橡木地板 + 原木角柱 + 木板墙（含门）+ 平顶 */
    private static List<String> builtInHut() {
        List<String> steps = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                steps.add(x + ",0," + z + ",minecraft:oak_planks");
            }
        }
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    if (!edge) {
                        continue;
                    }
                    boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
                    boolean door = x == 2 && z == 0;
                    if (door) {
                        steps.add(x + "," + y + "," + z + ",minecraft:oak_door");
                    } else if (corner) {
                        steps.add(x + "," + y + "," + z + ",minecraft:oak_log");
                    } else {
                        steps.add(x + "," + y + "," + z + ",minecraft:oak_planks");
                    }
                }
            }
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                steps.add(x + ",3," + z + ",minecraft:oak_planks");
            }
        }
        return steps;
    }

    /** 凉亭：4x4 石砖地板 + 四角柱 + 石砖顶 */
    private static List<String> builtInGazebo() {
        List<String> steps = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                steps.add(x + ",0," + z + ",minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 3; y++) {
            steps.add("0," + y + ",0,minecraft:stone_bricks");
            steps.add("3," + y + ",0,minecraft:stone_bricks");
            steps.add("0," + y + ",3,minecraft:stone_bricks");
            steps.add("3," + y + ",3,minecraft:stone_bricks");
        }
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                steps.add(x + ",4," + z + ",minecraft:stone_bricks");
            }
        }
        return steps;
    }

    /** 喷泉：3x3 石砖底座 + 四角柱 + 中央海晶灯 */
    private static List<String> builtInFountain() {
        List<String> steps = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                steps.add(x + ",0," + z + ",minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 2; y++) {
            steps.add("0," + y + ",0,minecraft:stone_bricks");
            steps.add("2," + y + ",0,minecraft:stone_bricks");
            steps.add("0," + y + ",2,minecraft:stone_bricks");
            steps.add("2," + y + ",2,minecraft:stone_bricks");
        }
        steps.add("1,1,1,minecraft:sea_lantern");
        steps.add("1,2,1,minecraft:lantern");
        return steps;
    }

    /** 瞭望塔：3x3 石砖底座 + 四角柱（4 层）+ 石砖顶 + 顶灯 */
    private static List<String> builtInTower() {
        List<String> steps = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                steps.add(x + ",0," + z + ",minecraft:stone_bricks");
            }
        }
        for (int y = 1; y <= 4; y++) {
            steps.add("0," + y + ",0,minecraft:stone_bricks");
            steps.add("2," + y + ",0,minecraft:stone_bricks");
            steps.add("0," + y + ",2,minecraft:stone_bricks");
            steps.add("2," + y + ",2,minecraft:stone_bricks");
        }
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                steps.add(x + ",5," + z + ",minecraft:stone_bricks");
            }
        }
        steps.add("1,6,1,minecraft:lantern");
        return steps;
    }

    /** 水井：3x3 圆石环 + 四角柱 + 顶梁 + 灯笼 */
    private static List<String> builtInWell() {
        List<String> steps = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                steps.add(x + ",0," + z + ",minecraft:cobblestone");
            }
        }
        steps.add("0,1,0,minecraft:cobblestone");
        steps.add("2,1,0,minecraft:cobblestone");
        steps.add("0,1,2,minecraft:cobblestone");
        steps.add("2,1,2,minecraft:cobblestone");
        steps.add("0,2,0,minecraft:cobblestone");
        steps.add("2,2,0,minecraft:cobblestone");
        steps.add("0,2,2,minecraft:cobblestone");
        steps.add("2,2,2,minecraft:cobblestone");
        steps.add("1,3,0,minecraft:cobblestone");
        steps.add("1,3,2,minecraft:cobblestone");
        steps.add("0,3,1,minecraft:cobblestone");
        steps.add("2,3,1,minecraft:cobblestone");
        steps.add("1,3,1,minecraft:lantern");
        return steps;
    }

    /** v1.5.311：JSON 对象可选字符串字段（缺省/空串/null 返回 null） */
    private static String getJsonString(JsonObject obj, String key) {
        try {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                    && !el.getAsString().isEmpty()) {
                return el.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析 LLM 生成的 JSON 蓝图；失败返回 null */
    public static List<String> parseJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray blocks = root.getAsJsonArray("blocks");
            // v1.5.197：块数硬上限从 maxBlocks()（200）放宽到 structureMaxBlocks()（100 万）——
            // 自绘蓝图（smart_design 子 Agent）大小无限制，落盘后扫描注册也走 parseJson；
            // 尺寸上限由调用方各自校验（smart_build 现场 JSON 仍走 validate() 限 200，
            // smart_design 走 looseValidate() 限 designMaxBlocks）。此处只做空/物理上限兜底。
            if (blocks == null || blocks.size() == 0 || blocks.size() > structureMaxBlocks()) {
                return null;
            }
            List<String> steps = new ArrayList<>();
            for (JsonElement element : blocks) {
                JsonObject obj = element.getAsJsonObject();
                int x = obj.get("x").getAsInt();
                int y = obj.get("y").getAsInt();
                int z = obj.get("z").getAsInt();
                String block = obj.get("block").getAsString();
                if (Math.abs(x) > maxRange() || Math.abs(z) > maxRange() || y < 0 || y > maxHeight()) {
                    return null;
                }
                if (!WHITELIST.contains(block)) {
                    return null;
                }
                // v1.5.311：JSON 蓝图支持可选 state / nbt（SNBT 字符串）——LLM 可以
                // 指定门/楼梯/活塞等定向方块的真实朝向与方块实体数据（旧版只有 id，
                // 一律默认态：门朝北开不了、中继器朝向固定、漏斗方向全错）。
                // 步骤格式不变：x,y,z,blockid|stateSnbt|beSnbt（state 缺省留空段）。
                String state = getJsonString(obj, "state");
                String nbt = getJsonString(obj, "nbt");
                StringBuilder step = new StringBuilder(x + "," + y + "," + z + "," + block);
                if (state != null) {
                    step.append('|').append(state);
                    if (nbt != null) {
                        step.append('|').append(nbt);
                    }
                } else if (nbt != null) {
                    step.append("||").append(nbt);
                }
                steps.add(step.toString());
            }
            return steps;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 JSON 蓝图中的 name 字段（可选）；解析失败返回 null */
    public static String parseJsonName(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement name = root.get("name");
            if (name != null && !name.getAsString().isEmpty()) {
                return name.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 校验步骤列表（块数/范围/白名单），非法返回错误信息，合法返回 null */
    public static String validate(List<String> steps) {
        if (steps == null || steps.size() > maxBlocks()) {
            return "蓝图无效：块数必须为 1~200";
        }
        for (String step : steps) {
            // v1.5.311：走 parseStep——步骤可能带 |stateSnbt|beSnbt（SNBT 内含逗号），
            // 旧版 step.split(",") 会把 state 里的逗号拆开误报"步骤格式错误"
            String[] parts = parseStep(step);
            if (parts == null || parts.length < 4) {
                return "蓝图无效：步骤格式错误";
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (Math.abs(x) > maxRange() || Math.abs(z) > maxRange() || y < 0 || y > maxHeight()) {
                    return "蓝图无效：坐标超出范围（平面 ±12，高度 0~8）";
                }
                if (!WHITELIST.contains(parts[3])) {
                    return "蓝图无效：方块不在建筑白名单内: " + parts[3];
                }
            } catch (NumberFormatException e) {
                return "蓝图无效：坐标不是数字";
            }
        }
        return null;
    }

    /** 统计蓝图步骤的总需求（blockId → 数量；等价族合并到"主方块"上）。
     *  v1.5.252w：排除 FORBIDDEN 黑名单（空气/基岩/屏障等生存不可获取方块）——
     *  不再出现在材料表（用户实测：材料表出现"空气 0/0"），缺料计算也默认
     *  玩家持有（空气步骤本就无限，主循环单独处理，不影响搭建）。 */
    public static Map<String, Integer> countNeeds(List<String> steps) {
        Map<String, Integer> needed = new HashMap<>();
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts != null) {
                String id = parts[3];
                if (FORBIDDEN.contains(id)) {
                    continue;
                }
                needed.merge(id, 1, Integer::sum);
            }
        }
        return needed;
    }

    /** v1.5.318：蓝图液体【工具/材料】需求——水需要 1 个水桶作工具（不消耗，无限放
     *  水源）；岩浆需要 N 个岩浆桶（放置后返还空桶，需周转）。
     *  v1.5.320：轰炸机类蓝图追加【矿车】需求（探测铁轨数）——完工自动放矿车需
     *  消耗矿车（女仆/主人背包有才启动，不再凭空白给）。
     *  返回 {桶/矿车物品id → 需求数}，无需求返回空 Map。材料提示/材料表据此告知
     *  玩家要准备什么（countNeeds 排除 FORBIDDEN，水/岩浆/矿车不进普通材料统计，
     *  否则玩家建造前完全不知道要备桶/矿车）。 */
    public static Map<String, Integer> fluidBucketNeeds(List<String> steps) {
        Map<String, Integer> out = new HashMap<>();
        int water = 0;
        int lava = 0;
        int rails = 0;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            if ("minecraft:water".equals(parts[3])) {
                water++;
            } else if ("minecraft:lava".equals(parts[3])) {
                lava++;
            } else if ("minecraft:detector_rail".equals(parts[3])) {
                rails++;
            }
        }
        if (water > 0) {
            out.put("minecraft:water_bucket", 1);
        }
        if (lava > 0) {
            out.put("minecraft:lava_bucket", lava);
        }
        if (rails > 0) {
            out.put("minecraft:minecart", rails);
        }
        return out;
    }

    /** v1.5.318：液体/工具需求中文提示——"另需水桶×1(作工具，不消耗)、岩浆桶×N
     *  (放置后返还空桶)、矿车×N(完工自动放置，需备齐)"；蓝图无需求返回空串。 */
    public static String fluidNeedText(List<String> steps) {
        Map<String, Integer> needs = fluidBucketNeeds(steps);
        if (needs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("另需");
        boolean first = true;
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            if (!first) {
                sb.append('、');
            }
            first = false;
            if ("minecraft:water_bucket".equals(e.getKey())) {
                sb.append("水桶×1（作工具，不消耗）");
            } else if ("minecraft:lava_bucket".equals(e.getKey())) {
                sb.append("岩浆桶×").append(e.getValue()).append("（放置后返还空桶）");
            } else if ("minecraft:minecart".equals(e.getKey())) {
                sb.append("矿车×").append(e.getValue()).append("（完工自动放置，需备齐）");
            } else {
                sb.append(e.getKey()).append('x').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** v1.5.25d：蓝图材料需求缓存（id → 需求 Map）——右击手册时避免对每个蓝图
     *  重新遍历上万步骤（59 蓝图 × 1.4 万步 = 80 万次解析 → 服务端主线程卡顿）。
     *  外部蓝图 mtime 变化时失效重建（scanExternalBlueprints 负责清缓存）。 */
    private static final Map<String, Map<String, Integer>> NEEDS_CACHE = new HashMap<>();
    /** 外部蓝图 id → 对应的文件 mtime（缓存失效依据） */
    private static final Map<String, Long> NEEDS_MTIME = new HashMap<>();

    /** 带缓存的材料需求统计；steps 为 null 时返回空 Map */
    public static Map<String, Integer> countNeedsCached(String id, List<String> steps) {
        if (id == null || steps == null || steps.isEmpty()) {
            return new HashMap<>();
        }
        Long mtime = EXTERNAL_MTIMES.get(id);
        Long cachedMtime = NEEDS_MTIME.get(id);
        if (mtime != null && mtime.equals(cachedMtime)) {
            Map<String, Integer> hit = NEEDS_CACHE.get(id);
            if (hit != null) {
                return hit;
            }
        }
        Map<String, Integer> need = countNeeds(steps);
        NEEDS_CACHE.put(id, need);
        if (mtime != null) {
            NEEDS_MTIME.put(id, mtime);
        }
        return need;
    }

    /** 材料预检：返回缺失清单（block → 缺口数量）；材料充足返回 null */
    public static Map<String, Integer> calcShortfall(EntityMaid maid, List<String> steps) {
        Map<String, Integer> needed = countNeeds(steps);
        Map<String, Integer> shortfall = new HashMap<>();
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            int have = countMaterial(maid, entry.getKey());
            if (have < entry.getValue()) {
                shortfall.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return shortfall.isEmpty() ? null : shortfall;
    }

    /** v1.5.24：玩家是否创造模式（创造模式材料视为无限，跳过检测/扣除/交付） */
    public static boolean isCreative(Player player) {
        return player != null && player.m_150110_().f_35937_;
    }

    /**
     * v1.5.124：玩家 + 女仆合计持有（防整数溢出）——创造模式玩家 countPlayerMaterial
     * 返回 Integer.MAX_VALUE（无限），若直接与女仆持有相加会溢出成负数，缺口算出
     * "缺 2147483647"（21 亿）并误判材料不足 → 创造模式下达蓝图不建造
     * （"创造模式还缺 21 亿"根因）。任一侧视为无限时直接返回 MAX_VALUE。
     */
    public static int combinedHave(Player owner, EntityMaid maid, String blockId) {
        int havePlayer = owner != null ? countPlayerMaterial(owner, blockId) : 0;
        if (havePlayer >= Integer.MAX_VALUE / 2) {
            return Integer.MAX_VALUE;
        }
        int haveMaid = countMaterial(maid, blockId);
        if (haveMaid >= Integer.MAX_VALUE / 2) {
            return Integer.MAX_VALUE;
        }
        return havePlayer + haveMaid;
    }

    /**
     * v1.5.179：主人背包 + 该维度所有【绑定女仆】（建筑任务）背包的持有量合计——
     * 实时缺料 = 需求 − 已建 − 该合计。创造模式任一侧视为无限时返回 MAX_VALUE
     *（与 combinedHave 同一溢出保护）。
     */
    public static int combinedHaveAll(net.minecraft.server.level.ServerLevel level, Player owner, String blockId) {
        int have = 0;
        if (owner != null) {
            have = countPlayerMaterial(owner, blockId);
            if (have >= Integer.MAX_VALUE / 2) {
                return Integer.MAX_VALUE;
            }
        }
        if (level != null) {
            // v1.5.187b：安全扫描——按建造区块 box 外扩的小盒收集绑定女仆
            //（旧版全图 ±3E7 AABB 遍历 visibleChunks 树曾触发 fastutil 病态 Subset
            //  死循环导致游戏全卡死；绑定女仆必然在区块附近建造，小盒覆盖足够）
            for (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                    : com.maidsmart.build.BuildPlan.scanAreaMaids(level)) {
                if (!com.maidsmart.build.BlueprintBuildExecutor.isBuildingTask(m)) {
                    continue;
                }
                int n = countMaterial(m, blockId);
                if (n >= Integer.MAX_VALUE / 2) {
                    return Integer.MAX_VALUE;
                }
                have += n;
                if (have >= Integer.MAX_VALUE / 2) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return have;
    }

    // ==================== 方块/物品 id → 中文名（v1.5.126：材料提示友好输出） ====================

    /** 精确表：蓝图用到的常见方块 + 矿物 + 常见物品（材料提示/缺料报告用） */
    private static final Map<String, String> CN_NAMES = new HashMap<>();

    private static void cn(String id, String name) {
        CN_NAMES.put(id, name);
    }

    static {
        // —— 木材（原木/木板/树叶/门/楼梯/台阶/栅栏/木材）——
        cn("minecraft:oak_log", "橡木原木"); cn("minecraft:spruce_log", "云杉原木");
        cn("minecraft:birch_log", "白桦原木"); cn("minecraft:jungle_log", "丛林原木");
        cn("minecraft:acacia_log", "金合欢原木"); cn("minecraft:dark_oak_log", "深色橡木原木");
        cn("minecraft:cherry_log", "樱花原木"); cn("minecraft:mangrove_log", "红树原木");
        cn("minecraft:oak_wood", "橡木"); cn("minecraft:spruce_wood", "云杉木");
        cn("minecraft:birch_wood", "白桦木"); cn("minecraft:jungle_wood", "丛林木");
        cn("minecraft:acacia_wood", "金合欢木"); cn("minecraft:dark_oak_wood", "深色橡木");
        cn("minecraft:oak_planks", "橡木木板"); cn("minecraft:spruce_planks", "云杉木板");
        cn("minecraft:birch_planks", "白桦木板"); cn("minecraft:jungle_planks", "丛林木板");
        cn("minecraft:acacia_planks", "金合欢木板"); cn("minecraft:dark_oak_planks", "深色橡木板");
        cn("minecraft:cherry_planks", "樱花木板"); cn("minecraft:mangrove_planks", "红树木板");
        cn("minecraft:bamboo_planks", "竹木板"); cn("minecraft:crimson_planks", "绯红木板");
        cn("minecraft:warped_planks", "诡异木板");
        cn("minecraft:oak_leaves", "橡树树叶"); cn("minecraft:dark_oak_leaves", "深色橡树树叶");
        cn("minecraft:birch_leaves", "白桦树叶"); cn("minecraft:jungle_leaves", "丛林树叶");
        cn("minecraft:acacia_leaves", "金合欢树叶"); cn("minecraft:spruce_leaves", "云杉树叶");
        cn("minecraft:oak_door", "橡木门"); cn("minecraft:spruce_door", "云杉木门");
        cn("minecraft:birch_door", "白桦木门"); cn("minecraft:jungle_door", "丛林门");
        cn("minecraft:acacia_door", "金合欢门"); cn("minecraft:dark_oak_door", "深色橡木门");
        cn("minecraft:cherry_door", "樱花木门"); cn("minecraft:mangrove_door", "红树木门");
        cn("minecraft:iron_door", "铁门");
        cn("minecraft:oak_stairs", "橡木楼梯"); cn("minecraft:spruce_stairs", "云杉木楼梯");
        cn("minecraft:birch_stairs", "白桦木楼梯"); cn("minecraft:jungle_stairs", "丛林楼梯");
        cn("minecraft:acacia_stairs", "金合欢楼梯"); cn("minecraft:dark_oak_stairs", "深色橡木楼梯");
        cn("minecraft:oak_slab", "橡木台阶"); cn("minecraft:spruce_slab", "云杉木台阶");
        cn("minecraft:birch_slab", "白桦木台阶"); cn("minecraft:jungle_slab", "丛林台阶");
        cn("minecraft:acacia_slab", "金合欢台阶"); cn("minecraft:dark_oak_slab", "深色橡木台阶");
        cn("minecraft:oak_fence", "橡木栅栏"); cn("minecraft:spruce_fence", "云杉木栅栏");
        cn("minecraft:dark_oak_fence", "深色橡木栅栏"); cn("minecraft:jungle_fence", "丛林栅栏");
        cn("minecraft:acacia_fence", "金合欢栅栏");
        cn("minecraft:oak_fence_gate", "橡木栅栏门"); cn("minecraft:oak_button", "橡木按钮");
        cn("minecraft:oak_pressure_plate", "橡木压力板"); cn("minecraft:oak_sign", "橡木告示牌");
        cn("minecraft:oak_trapdoor", "橡木活板门"); cn("minecraft:iron_trapdoor", "铁活板门");
        // —— 石头/建材 ——
        cn("minecraft:stone", "石头"); cn("minecraft:cobblestone", "圆石");
        cn("minecraft:mossy_cobblestone", "苔石"); cn("minecraft:stone_bricks", "石砖");
        cn("minecraft:stone_brick_slab", "石砖台阶"); cn("minecraft:stone_brick_stairs", "石砖楼梯");
        cn("minecraft:mossy_stone_bricks", "苔石砖"); cn("minecraft:cracked_stone_bricks", "裂纹石砖");
        cn("minecraft:smooth_stone", "平滑石头"); cn("minecraft:stone_slab", "石头台阶");
        cn("minecraft:cobblestone_slab", "圆石台阶"); cn("minecraft:cobblestone_stairs", "圆石楼梯");
        cn("minecraft:cobblestone_wall", "圆石墙"); cn("minecraft:stone_button", "石按钮");
        cn("minecraft:stone_pressure_plate", "石压力板");
        cn("minecraft:deepslate", "深板岩"); cn("minecraft:deepslate_bricks", "深板岩砖");
        cn("minecraft:deepslate_tiles", "深板岩瓦"); cn("minecraft:cracked_deepslate_bricks", "裂纹深板岩砖");
        cn("minecraft:andesite", "安山岩"); cn("minecraft:polished_andesite", "磨制安山岩");
        cn("minecraft:diorite", "闪长岩"); cn("minecraft:granite", "花岗岩");
        cn("minecraft:tuff", "凝灰岩"); cn("minecraft:calcite", "方解石");
        cn("minecraft:sandstone", "砂岩"); cn("minecraft:smooth_sandstone", "平滑砂岩");
        cn("minecraft:chiseled_sandstone", "錾制砂岩"); cn("minecraft:cut_sandstone", "切制砂岩");
        cn("minecraft:sandstone_slab", "砂岩台阶"); cn("minecraft:sandstone_stairs", "砂岩楼梯");
        cn("minecraft:red_sandstone", "红砂岩"); cn("minecraft:red_sandstone_slab", "红砂岩台阶");
        cn("minecraft:red_sandstone_stairs", "红砂岩楼梯");
        cn("minecraft:quartz_block", "石英块"); cn("minecraft:quartz_pillar", "石英柱");
        cn("minecraft:quartz_stairs", "石英楼梯"); cn("minecraft:quartz_slab", "石英台阶");
        cn("minecraft:smooth_quartz", "平滑石英"); cn("minecraft:chiseled_quartz_block", "錾制石英块");
        cn("minecraft:prismarine", "海晶石"); cn("minecraft:dark_prismarine", "暗海晶石");
        cn("minecraft:prismarine_bricks", "海晶石砖"); cn("minecraft:sea_lantern", "海晶灯");
        cn("minecraft:bricks", "红砖块"); cn("minecraft:brick_block", "红砖块");
        cn("minecraft:brick_slab", "红砖台阶"); cn("minecraft:brick_stairs", "红砖楼梯");
        cn("minecraft:nether_brick", "地狱砖"); cn("minecraft:nether_brick_fence", "地狱砖栅栏");
        cn("minecraft:nether_brick_stairs", "地狱砖楼梯"); cn("minecraft:nether_brick_slab", "地狱砖台阶");
        cn("minecraft:end_stone", "末地石"); cn("minecraft:end_stone_bricks", "末地石砖");
        cn("minecraft:purpur_block", "紫珀块"); cn("minecraft:purpur_pillar", "紫珀柱");
        cn("minecraft:purpur_stairs", "紫珀楼梯"); cn("minecraft:purpur_slab", "紫珀台阶");
        cn("minecraft:obsidian", "黑曜石"); cn("minecraft:bedrock", "基岩");
        cn("minecraft:terracotta", "陶瓦");
        cn("minecraft:white_terracotta", "白色陶瓦"); cn("minecraft:orange_terracotta", "橙色陶瓦");
        cn("minecraft:red_terracotta", "红色陶瓦"); cn("minecraft:brown_terracotta", "棕色陶瓦");
        cn("minecraft:black_terracotta", "黑色陶瓦");
        cn("minecraft:white_concrete", "白色混凝土"); cn("minecraft:orange_concrete", "橙色混凝土");
        cn("minecraft:pink_concrete", "粉色混凝土"); cn("minecraft:gray_concrete", "灰色混凝土");
        cn("minecraft:light_gray_concrete", "淡灰色混凝土"); cn("minecraft:lime_concrete", "黄绿色混凝土");
        cn("minecraft:green_concrete", "绿色混凝土"); cn("minecraft:red_concrete", "红色混凝土");
        cn("minecraft:yellow_concrete", "黄色混凝土"); cn("minecraft:black_concrete", "黑色混凝土");
        cn("minecraft:white_wool", "白色羊毛"); cn("minecraft:red_wool", "红色羊毛");
        cn("minecraft:magenta_wool", "品红色羊毛");
        cn("minecraft:glass", "玻璃"); cn("minecraft:glass_pane", "玻璃板");
        cn("minecraft:white_stained_glass", "白色玻璃"); cn("minecraft:black_stained_glass", "黑色玻璃");
        cn("minecraft:blue_stained_glass", "蓝色玻璃"); cn("minecraft:cyan_stained_glass", "青色玻璃");
        cn("minecraft:green_stained_glass", "绿色玻璃"); cn("minecraft:white_stained_glass_pane", "白色玻璃板");
        cn("minecraft:iron_bars", "铁栏杆");
        cn("minecraft:white_bed", "白色床"); cn("minecraft:red_bed", "红色床");
        cn("minecraft:blue_bed", "蓝色床"); cn("minecraft:green_bed", "绿色床");
        cn("minecraft:amethyst_block", "紫水晶块"); cn("minecraft:budding_amethyst", "紫水晶母岩");
        cn("minecraft:dripstone_block", "滴水石块"); cn("minecraft:magma_block", "岩浆块");
        // —— 功能/装饰方块 ——
        cn("minecraft:bookshelf", "书架"); cn("minecraft:crafting_table", "工作台");
        cn("minecraft:furnace", "熔炉"); cn("minecraft:chest", "箱子");
        cn("minecraft:trapped_chest", "陷阱箱"); cn("minecraft:hopper", "漏斗");
        cn("minecraft:dispenser", "发射器"); cn("minecraft:dropper", "投掷器");
        cn("minecraft:brewing_stand", "酿造台"); cn("minecraft:enchanting_table", "附魔台");
        cn("minecraft:anvil", "铁砧"); cn("minecraft:beacon", "信标");
        cn("minecraft:jukebox", "唱片机"); cn("minecraft:note_block", "音符盒");
        cn("minecraft:daylight_detector", "阳光探测器"); cn("minecraft:lever", "拉杆");
        cn("minecraft:redstone_lamp", "红石灯"); cn("minecraft:redstone_block", "红石块");
        cn("minecraft:redstone_torch", "红石火把"); cn("minecraft:redstone_repeater", "红石中继器");
        // v1.5.287：红石机器蓝图用件补全中文名（缺省会显示英文 id）
        cn("minecraft:redstone_wire", "红石粉"); cn("minecraft:repeater", "红石中继器");
        cn("minecraft:comparator", "红石比较器"); cn("minecraft:observer", "观察者");
        cn("minecraft:hopper", "漏斗"); cn("minecraft:sticky_piston", "粘性活塞");
        cn("minecraft:dropper", "投掷器"); cn("minecraft:dispenser", "发射器");
        cn("minecraft:note_block", "音符盒"); cn("minecraft:sugar_cane", "甘蔗");
        cn("minecraft:torch", "火把"); cn("minecraft:lantern", "灯笼");
        cn("minecraft:soul_lantern", "灵魂灯笼"); cn("minecraft:campfire", "营火");
        cn("minecraft:soul_campfire", "灵魂营火"); cn("minecraft:glowstone", "荧石");
        cn("minecraft:jack_o_lantern", "南瓜灯"); cn("minecraft:pumpkin", "南瓜");
        cn("minecraft:melon", "西瓜"); cn("minecraft:hay_block", "干草块");
        cn("minecraft:ladder", "梯子"); cn("minecraft:rail", "铁轨");
        cn("minecraft:detector_rail", "探测铁轨"); cn("minecraft:piston", "活塞");
        cn("minecraft:sticky_piston", "粘性活塞"); cn("minecraft:tnt", "TNT");
        cn("minecraft:sponge", "海绵"); cn("minecraft:cobweb", "蜘蛛网");
        cn("minecraft:ice", "冰"); cn("minecraft:packed_ice", "浮冰");
        cn("minecraft:snow_block", "雪块"); cn("minecraft:snow", "雪");
        cn("minecraft:clay", "黏土"); cn("minecraft:gravel", "沙砾");
        cn("minecraft:sand", "沙子"); cn("minecraft:red_sand", "红沙");
        cn("minecraft:dirt", "泥土"); cn("minecraft:coarse_dirt", "砂土");
        cn("minecraft:grass_block", "草方块"); cn("minecraft:mycelium", "菌丝");
        cn("minecraft:podzol", "灰化土"); cn("minecraft:moss_block", "苔藓块");
        cn("minecraft:rooted_dirt", "缠根泥土"); cn("minecraft:mud", "泥巴");
        cn("minecraft:farmland", "耕地"); cn("minecraft:grass_path", "土径");
        cn("minecraft:water", "水"); cn("minecraft:lava", "岩浆");
        cn("minecraft:fire", "火"); cn("minecraft:water_bucket", "水桶");
        cn("minecraft:lava_bucket", "岩浆桶"); // v1.5.318：材料表/缺料提示用
        cn("minecraft:minecart", "矿车"); // v1.5.320：轰炸机启动工具（完工自动放置）
        cn("minecraft:kelp", "海带"); cn("minecraft:sugar_cane", "甘蔗");
        cn("minecraft:cactus", "仙人掌"); cn("minecraft:lily_pad", "睡莲");
        cn("minecraft:vine", "藤蔓"); cn("minecraft:fern", "蕨类");
        cn("minecraft:tall_grass", "高草丛"); cn("minecraft:dead_bush", "枯死的灌木");
        cn("minecraft:dandelion", "蒲公英"); cn("minecraft:poppy", "虞美人");
        cn("minecraft:sweet_berry_bush", "甜浆果丛"); cn("minecraft:red_mushroom", "红色蘑菇");
        cn("minecraft:brown_mushroom", "棕色蘑菇"); cn("minecraft:flower_pot", "花盆");
        cn("minecraft:nether_wart", "地狱疣"); cn("minecraft:chorus_plant", "紫颂植株");
        cn("minecraft:chorus_flower", "紫颂花"); cn("minecraft:end_rod", "末地烛");
        cn("minecraft:dragon_egg", "龙蛋"); cn("minecraft:spawner", "刷怪笼");
        cn("minecraft:barrier", "屏障"); cn("minecraft:structure_void", "结构空位");
        cn("minecraft:structure_block", "结构方块"); cn("minecraft:jigsaw", "拼图方块");
        cn("minecraft:command_block", "命令方块"); cn("minecraft:chain_command_block", "连锁命令方块");
        cn("minecraft:repeating_command_block", "循环命令方块");
        // —— 矿物/矿石/矿物块 ——
        cn("minecraft:coal_ore", "煤矿石"); cn("minecraft:deepslate_coal_ore", "深层煤矿石");
        cn("minecraft:iron_ore", "铁矿石"); cn("minecraft:deepslate_iron_ore", "深层铁矿石");
        cn("minecraft:gold_ore", "金矿石"); cn("minecraft:deepslate_gold_ore", "深层金矿石");
        cn("minecraft:diamond_ore", "钻石矿石"); cn("minecraft:deepslate_diamond_ore", "深层钻石矿石");
        cn("minecraft:emerald_ore", "绿宝石矿石"); cn("minecraft:deepslate_emerald_ore", "深层绿宝石矿石");
        cn("minecraft:copper_ore", "铜矿石"); cn("minecraft:deepslate_copper_ore", "深层铜矿石");
        cn("minecraft:lapis_ore", "青金石矿石"); cn("minecraft:deepslate_lapis_ore", "深层青金石矿石");
        cn("minecraft:redstone_ore", "红石矿石"); cn("minecraft:deepslate_redstone_ore", "深层红石矿石");
        cn("minecraft:nether_quartz_ore", "下界石英矿石"); cn("minecraft:nether_gold_ore", "下界金矿石");
        cn("minecraft:netherrack", "下界岩"); cn("minecraft:soul_sand", "灵魂沙");
        cn("minecraft:diamond_block", "钻石块"); cn("minecraft:gold_block", "金块");
        cn("minecraft:iron_block", "铁块"); cn("minecraft:emerald_block", "绿宝石块");
        cn("minecraft:coal_block", "煤炭块"); cn("minecraft:lapis_block", "青金石块");
        // —— 常见物品（烹饪/酿造/交付报告）——
        cn("minecraft:diamond", "钻石"); cn("minecraft:coal", "煤炭");
        cn("minecraft:charcoal", "木炭"); cn("minecraft:iron_ingot", "铁锭");
        cn("minecraft:gold_ingot", "金锭"); cn("minecraft:emerald", "绿宝石");
        cn("minecraft:bread", "面包"); cn("minecraft:apple", "苹果");
        cn("minecraft:golden_apple", "金苹果"); cn("minecraft:enchanted_golden_apple", "附魔金苹果");
        cn("minecraft:beef", "生牛肉"); cn("minecraft:porkchop", "生猪排");
        cn("minecraft:chicken", "生鸡肉"); cn("minecraft:mutton", "生羊肉");
        cn("minecraft:rabbit", "生兔肉"); cn("minecraft:cod", "生鳕鱼");
        cn("minecraft:salmon", "生鲑鱼"); cn("minecraft:pufferfish", "河豚");
        cn("minecraft:cooked_beef", "牛排"); cn("minecraft:cooked_porkchop", "熟猪排");
        cn("minecraft:cooked_chicken", "烤鸡"); cn("minecraft:cooked_mutton", "熟羊肉");
        cn("minecraft:cooked_rabbit", "烤兔肉"); cn("minecraft:cooked_cod", "熟鳕鱼");
        cn("minecraft:cooked_salmon", "熟鲑鱼"); cn("minecraft:potato", "马铃薯");
        cn("minecraft:carrot", "胡萝卜"); cn("minecraft:golden_carrot", "金胡萝卜");
        cn("minecraft:glistering_melon_slice", "闪烁的西瓜片");
        cn("minecraft:blaze_powder", "烈焰粉"); cn("minecraft:ghast_tear", "恶魂之泪");
        cn("minecraft:magma_cream", "岩浆膏"); cn("minecraft:phantom_membrane", "幻翼膜");
        cn("minecraft:ender_pearl", "末影珍珠"); cn("minecraft:glass_bottle", "玻璃瓶");
        cn("minecraft:sugar", "糖"); cn("minecraft:rabbit_foot", "兔子脚");
        cn("minecraft:cauldron", "炼药锅"); cn("minecraft:nether_portal", "下界传送门");
        cn("minecraft:end_portal", "末地传送门"); cn("minecraft:end_portal_frame", "末地传送门框架");
        cn("minecraft:end_gateway", "末地折跃门");
    }

    /** 规则兜底：颜色前缀 */
    private static final String[][] CN_COLORS = {
            {"light_blue", "淡蓝"}, {"light_gray", "淡灰"}, {"dark_oak", ""}, // dark_oak 是木头名不是颜色
            {"white", "白"}, {"orange", "橙"}, {"magenta", "品红"}, {"yellow", "黄"},
            {"lime", "黄绿"}, {"pink", "粉"}, {"gray", "灰"}, {"cyan", "青"},
            {"purple", "紫"}, {"blue", "蓝"}, {"brown", "棕"}, {"green", "绿"},
            {"red", "红"}, {"black", "黑"},
    };

    /** 规则兜底：词干 → 中文（含木头/石料名） */
    private static final String[][] CN_BASE = {
            {"oak", "橡木"}, {"spruce", "云杉木"}, {"birch", "白桦木"}, {"jungle", "丛林木"},
            {"acacia", "金合欢木"}, {"dark_oak", "深色橡木"}, {"cherry", "樱花木"},
            {"mangrove", "红树木"}, {"bamboo", "竹"}, {"crimson", "绯红木"}, {"warped", "诡异木"},
            {"stone", "石头"}, {"cobblestone", "圆石"}, {"deepslate", "深板岩"},
            {"sandstone", "砂岩"}, {"red_sandstone", "红砂岩"}, {"quartz", "石英"},
            {"purpur", "紫珀"}, {"prismarine", "海晶石"}, {"brick", "红砖"},
            {"nether_brick", "地狱砖"}, {"end_stone", "末地石"}, {"stone_brick", "石砖"},
            {"blackstone", "黑石"}, {"polished_blackstone", "磨制黑石"},
            {"concrete", "混凝土"}, {"terracotta", "陶瓦"}, {"wool", "羊毛"},
            {"glass", "玻璃"}, {"stained", "染色"}, {"iron", "铁"}, {"gold", "金"}, {"diamond", "钻石"},
            {"emerald", "绿宝石"}, {"lapis", "青金石"}, {"redstone", "红石"},
            {"coal", "煤炭"}, {"copper", "铜"}, {"snow", "雪"}, {"ice", "冰"},
            {"netherrack", "下界岩"}, {"soul_sand", "灵魂沙"}, {"bedrock", "基岩"},
            {"glowstone", "荧石"}, {"obsidian", "黑曜石"}, {"clay", "黏土"},
            {"gravel", "沙砾"}, {"sand", "沙子"}, {"dirt", "泥土"}, {"log", "原木"},
            {"planks", "木板"}, {"leaves", "树叶"}, {"slab", "台阶"}, {"stairs", "楼梯"},
            {"fence", "栅栏"}, {"trapdoor", "活板门"}, {"door", "门"}, {"wall", "墙"},
            {"ore", "矿石"}, {"block", "块"}, {"bricks", "砖"}, {"brick", "砖"},
            {"lantern", "灯笼"}, {"torch", "火把"}, {"button", "按钮"}, {"sign", "告示牌"},
            {"bed", "床"}, {"carpet", "地毯"}, {"glass_pane", "玻璃板"}, {"bars", "栏杆"},
            // 工具/装备（镐子报告等）
            {"pickaxe", "镐"}, {"axe", "斧"}, {"shovel", "锹"}, {"hoe", "锄"},
            {"sword", "剑"}, {"bow", "弓"}, {"helmet", "头盔"}, {"chestplate", "胸甲"},
            {"leggings", "护腿"}, {"boots", "靴子"}, {"golden", "金"}, {"wooden", "木"},
            {"leather", "皮革"}, {"chainmail", "锁链"}, {"netherite", "下界合金"}, {"elytra", "鞘翅"},
    };

    /** v1.5.126：方块/物品 id → 中文名（材料提示用）。精确表优先；未收录的走
     *  规则兜底（颜色/木头/词干 + 后缀），仍不认识就返回去掉 minecraft: 前缀的
     *  id（非原版 id 保留完整命名空间，方便识别）。 */
    public static String cnName(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String exact = CN_NAMES.get(id);
        if (exact != null) {
            return exact;
        }
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (s.isEmpty()) {
            return id;
        }
        // 颜色前缀（最长优先，dark_oak 除外——它是木头名）
        for (String[] c : CN_COLORS) {
            String prefix = c[0] + "_";
            if (s.startsWith(prefix) && !s.startsWith("dark_oak_")) {
                return c[1] + cnBase(s.substring(prefix.length()));
            }
        }
        String base = cnBase(s);
        return base.equals(s) && id.startsWith("minecraft:") ? s : base;
    }

    /** 规则兜底：词干 + 后缀组合（后缀按长度降序匹配，trapdoor 不会被 door 抢先） */
    private static String cnBase(String s) {
        for (String[] b : CN_BASE) {
            if (s.equals(b[0])) {
                return b[1];
            }
        }
        String bestSuf = null;
        String bestName = null;
        for (String[] b : CN_BASE) {
            String suf = b[0];
            if (suf.length() < 2 || !s.endsWith(suf) || s.equals(suf)) {
                continue;
            }
            if (bestSuf == null || suf.length() > bestSuf.length()) {
                bestSuf = suf;
                bestName = b[1];
            }
        }
        if (bestSuf != null) {
            String stem = s.substring(0, s.length() - bestSuf.length());
            if (stem.endsWith("_")) {
                stem = stem.substring(0, stem.length() - 1);
            }
            return cnBase(stem) + bestName;
        }
        return s;
    }

    /** v1.5.24：玩家背包材料预检（材料以主人背包为准）——返回缺失清单；充足返回 null */
    public static Map<String, Integer> calcPlayerShortfall(Player player, List<String> steps) {
        if (isCreative(player)) {
            return null; // 创造模式材料默认齐全
        }
        Map<String, Integer> needed = countNeeds(steps);
        Map<String, Integer> shortfall = new HashMap<>();
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            int have = countPlayerMaterial(player, entry.getKey());
            if (have < entry.getValue()) {
                shortfall.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return shortfall.isEmpty() ? null : shortfall;
    }

    /** v1.5.24：统计玩家背包中指定方块物品的持有数量（等价族感知；玩家为 null 返回 0） */
    public static int countPlayerMaterial(Player player, String blockId) {
        if (player == null) {
            return 0;
        }
        if (isCreative(player)) {
            return Integer.MAX_VALUE; // 创造模式：任何材料视为备齐
        }
        Set<String> group = EQUIVALENT_GROUPS.get(blockId);
        // v1.5.287：itemForBlock（redstone_wire → 红石粉）
        Item exact = itemForBlock(blockId);
        int count = 0;
        net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
        for (int i = 0; i < inv.m_6643_(); i++) {
            ItemStack stack = inv.m_8020_(i);
            if (stack.m_41619_()) {
                continue;
            }
            if (group != null) {
                ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                if (stackId != null && group.contains(stackId.toString())) {
                    count += stack.m_41613_();
                }
            } else if (exact != null && stack.m_41720_() == exact) {
                count += stack.m_41613_();
            }
        }
        return count;
    }

    /**
     * v1.5.24：材料确认后，把蓝图所需（女仆背包缺的部分）从玩家背包自动转交给女仆。
     * 等价族感知：蓝图要橡木木板时，玩家包里的云杉/白桦木板也算。
     */
    public static void deliverToMaid(Player player, EntityMaid maid, Map<String, Integer> need) {
        if (player == null || need == null) {
            return;
        }
        if (isCreative(player)) {
            return; // 创造模式：不扣玩家材料、不转给女仆（女仆放置时按创造模式豁免）
        }
        for (Map.Entry<String, Integer> entry : need.entrySet()) {
            int haveMaid = countMaterial(maid, entry.getKey());
            int want = entry.getValue() - haveMaid;
            if (want > 0) {
                transferFromPlayer(player, maid, entry.getKey(), want);
            }
        }
    }

    /** 从玩家背包把指定数量的方块物品转给女仆背包；放不下的留在玩家原槽 */
    private static void transferFromPlayer(Player player, EntityMaid maid, String blockId, int count) {
        if (count <= 0) {
            return;
        }
        Set<String> group = EQUIVALENT_GROUPS.get(blockId);
        // v1.5.287：itemForBlock（redstone_wire → 红石粉）
        Item exact = itemForBlock(blockId);
        net.minecraft.world.entity.player.Inventory inv = player.m_150109_();
        int moved = 0;
        for (int i = 0; i < inv.m_6643_() && moved < count; i++) {
            ItemStack stack = inv.m_8020_(i);
            if (stack.m_41619_()) {
                continue;
            }
            boolean match;
            if (group != null) {
                ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                match = stackId != null && group.contains(stackId.toString());
            } else {
                match = exact != null && stack.m_41720_() == exact;
            }
            if (!match) {
                continue;
            }
            int n = stack.m_41613_();
            int take = Math.min(count - moved, n);
            // 取走整槽 → copyWithCount 分出 take 个交给女仆，剩余放回玩家原槽
            ItemStack whole = inv.m_8016_(i);
            if (whole.m_41619_()) {
                continue;
            }
            ItemStack taken = whole.m_255036_(take);
            ItemStack left = ItemHandlerHelper.insertItemStacked(maid.getMaidInv(), taken, false);
            moved += take - left.m_41613_();
            int back = (n - take) + left.m_41613_();
            if (back > 0) {
                inv.m_6836_(i, whole.m_255036_(back));
            }
        }
    }

    /** 统计背包中指定方块物品的持有数量（支持等价族：族内任意物品都算） */
    public static int countMaterial(EntityMaid maid, String blockId) {
        Set<String> group = EQUIVALENT_GROUPS.get(blockId);
        int count = 0;
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.m_41619_()) {
                continue;
            }
            if (group != null) {
                ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                if (stackId != null && group.contains(stackId.toString())) {
                    count += stack.m_41613_();
                }
            } else {
                // v1.5.287：itemForBlock（redstone_wire → 红石粉）
                Item item = itemForBlock(blockId);
                if (item != null && stack.m_41720_() == item) {
                    count += stack.m_41613_();
                }
            }
        }
        return count;
    }

    /**
     * 从背包取 1 个指定方块对应的物品（支持等价族 + v1.5.254 自定义替代）。
     * 返回实际消耗的物品（用于放置对应方块）；背包没有返回 null。
     */
    public static Item consumeBlock(EntityMaid maid, String blockId) {
        // v1.5.24：主人处于创造模式 → 材料视为无限，直接返回对应物品（不扣任何背包）
        if (maid.m_269323_() instanceof Player owner && isCreative(owner)) {
            // v1.5.287：itemForBlock（redstone_wire → 红石粉——否则创造也建不出线）
            Item exact = itemForBlock(blockId);
            if (exact != null) {
                return exact;
            }
            Set<String> cg = EQUIVALENT_GROUPS.get(blockId);
            if (cg != null) {
                for (String id : cg) {
                    Item gi = itemForBlock(id);
                    if (gi != null) {
                        return gi;
                    }
                }
            }
            return null;
        }
        // v1.5.317：水/岩浆特殊材料（机器蓝图保留液体步骤后）——
        // 水 = 无限材料：女仆或主人背包有 1 个水桶作【工具】即可放任意多水源
        // （不消耗，与原版水源可再生一致）；岩浆 = 消耗 1 岩浆桶（放置后返还
        // 空桶，见 returnEmptyBucket——岩浆不可再生，需玩家备 N 桶周转）。
        if ("minecraft:water".equals(blockId)) {
            return hasBucketTool(maid, "minecraft:water_bucket")
                    ? net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:water_bucket"))
                    : null;
        }
        if ("minecraft:lava".equals(blockId)) {
            Item lavaBucket = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:lava_bucket"));
            ItemStack taken = extractExact(maid.getMaidInv(), lavaBucket, 1);
            if (!taken.m_41619_()) {
                return lavaBucket; // 已从女仆背包取走 1 岩浆桶
            }
            return null; // 调用方会尝试主人背包（tryTakeFromOwner）
        }
        Item item = itemForBlock(blockId);
        IItemHandler inv = maid.getMaidInv();
        // 1. 精确匹配优先
        if (item != null) {
            ItemStack taken = extractExact(inv, item, 1);
            if (!taken.m_41619_()) {
                return item;
            }
        }
        // 2. 等价族内任意物品
        Set<String> group = EQUIVALENT_GROUPS.get(blockId);
        if (group != null) {
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                ResourceLocation stackId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
                if (stackId != null && group.contains(stackId.toString())) {
                    ItemStack taken = inv.extractItem(i, 1, false);
                    if (!taken.m_41619_()) {
                        return taken.m_41720_();
                    }
                }
            }
        }
        // 3. v1.5.254：自定义替代（开关开启时）——按目标方块高度分类选表
        //（半格=台阶类 / 两格=门/双植物/床/甘蔗/竹子 / 其余=一格），表内按序
        // 取第一个背包里有的替代品（替代品必须有对应方块，防消耗物品却放不出方块）
        // v1.5.261：替代品高度类别必须与目标【严格一致】——旧配置/误添加的
        // 错配条目（半格表里的一格方块等）执行时跳过，防止半格位置被一格方块
        // 顶坏建筑（用户需求："1 格只能用 1 格替换，半格两格同理"）
        // v1.5.275：两格再分竖/横（门/高植物 ↔ 床），无碰撞方块单独表
        if (com.maidsmart.config.MaidSmartConfig.BUILD_ALT_ENABLED.get()) {
            Block targetBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockId));
            List<String> alts = targetBlock == null ? altBlocks()
                    : isSlabHeight(targetBlock) ? altSlabs()
                    : isTallVertical(targetBlock) ? altTalls()
                    : isWideHeight(targetBlock) ? altWides()
                    : isNoClip(targetBlock) ? altNoClips()
                    : altBlocks();
            // v1.5.279：同族优先——第一轮只扫与目标同材质族的替代品，第二轮才扫
            // 其余（多维度划分的落地：高度/碰撞严格匹配之上，材质族作为优先序）
            String fam = materialFamily(targetBlock);
            for (int pass = 0; pass < 2; pass++) {
                for (String alt : alts) {
                    Item altItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(alt));
                    if (altItem == null) {
                        continue;
                    }
                    ResourceLocation altId = ForgeRegistries.ITEMS.getKey(altItem);
                    Block altBlock = altId != null ? ForgeRegistries.BLOCKS.getValue(altId) : null;
                    if (altBlock == null || altBlock == net.minecraft.world.level.block.Blocks.f_50016_) {
                        continue; // 无对应方块（剑/工具等）→ 跳过，不能当替代品
                    }
                    // v1.5.261：类别严格匹配（半格↔半格、一格↔一格、两格↔两格）
                    // v1.5.275：两格再分竖/横，无碰撞单独匹配
                    if (targetBlock != null) {
                        boolean match;
                        if (isSlabHeight(targetBlock) || isSlabHeight(altBlock)) {
                            match = isSlabHeight(targetBlock) && isSlabHeight(altBlock);
                        } else if (isTallVertical(targetBlock) || isTallVertical(altBlock)) {
                            match = isTallVertical(targetBlock) && isTallVertical(altBlock);
                        } else if (isWideHeight(targetBlock) || isWideHeight(altBlock)) {
                            match = isWideHeight(targetBlock) && isWideHeight(altBlock);
                        } else if (isNoClip(targetBlock) || isNoClip(altBlock)) {
                            match = isNoClip(targetBlock) && isNoClip(altBlock);
                        } else {
                            match = true;
                        }
                        if (!match) {
                            continue;
                        }
                    }
                    if (pass == 0 && (fam == null || !fam.equals(materialFamily(altBlock)))) {
                        continue; // 第一轮只要同族（fam null = 目标无方块 → 直接过）
                    }
                    ItemStack taken = extractExact(inv, altItem, 1);
                    if (!taken.m_41619_()) {
                        return altItem;
                    }
                }
            }
        }
        return null;
    }

    // ================= v1.5.254：缺料自定义替代（高度分类） =================

    /**
     * v1.5.287：方块 id → 物品 id 特例——1.20.1 不存在 redstone_wire 物品
     * （红石粉物品是 minecraft:redstone）→ 材料链（统计/转移/消耗）按红石粉结算；
     * 放置层用方块注册名不受影响（放的是 redstone_wire 方块本身）。修复前
     * 红石线步骤 consumeBlock 恒返回 null → 生存/创造都建不出线（红石机器
     * 建不起来的根因）。
     */
    public static final Map<String, String> BLOCK_ITEM_OVERRIDES = Map.of(
            "minecraft:redstone_wire", "minecraft:redstone",
            // 红石机器蓝图用件：茎方块无物品形式（物品是种子）；耕地无物品（消耗泥土）
            "minecraft:pumpkin_stem", "minecraft:pumpkin_seeds",
            "minecraft:melon_stem", "minecraft:melon_seeds",
            "minecraft:farmland", "minecraft:dirt",
            // v1.5.317：水/岩浆——方块无物品（物品是桶）。机器蓝图保留水/岩浆步骤后，
            // 材料链按桶结算：水=无限材料（1 水桶作工具，不消耗）；岩浆=消耗 1 岩浆桶
            // （放置后返还空桶，见 returnEmptyBucket）。
            "minecraft:water", "minecraft:water_bucket",
            "minecraft:lava", "minecraft:lava_bucket");

    /** 方块 id → 对应物品 id（含特例映射；无特例返回原 id） */
    public static String itemIdForBlock(String blockId) {
        return BLOCK_ITEM_OVERRIDES.getOrDefault(blockId, blockId);
    }

    /** 方块 id → 对应物品（含特例：redstone_wire → 红石粉）——材料链统一入口 */
    public static Item itemForBlock(String blockId) {
        return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemIdForBlock(blockId)));
    }

    /** 半格高判定（台阶类——替换表按此分类） */
    public static boolean isSlabHeight(Block block) {
        return block instanceof net.minecraft.world.level.block.SlabBlock;
    }

    /** 两格高判定（门/双植物/甘蔗/竹子——替换表按此分类）
     *  v1.5.275：拆分为"竖两格"（本方法）与"横两格"（isWideHeight——床）。
     *  用户："横着高的两格方块和竖着的两格方块，这两类物品也是不一样的" */
    public static boolean isTallHeight(Block block) {
        return isTallVertical(block) || isWideHeight(block);
    }

    /** 竖两格（高 2 格：门/高植物/甘蔗/竹子） */
    public static boolean isTallVertical(Block block) {
        return block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.DoublePlantBlock
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                || block instanceof net.minecraft.world.level.block.BambooStalkBlock;
    }

    /** 横两格（宽 2 格：床） */
    public static boolean isWideHeight(Block block) {
        return block instanceof net.minecraft.world.level.block.BedBlock;
    }

    /** 无碰撞体积方块（花/草/蕨/火把/地毯/线/红石线/压力板/按钮/梯子/铁轨/花盆/
     *  横幅/告示牌/雪层等——isSolid（有碰撞）为 false 的可放置方块）。
     *  v1.5.275：面板单独一个区（用户："没有实体碰撞体积的方块单独画一个区"） */
    public static boolean isNoClip(Block block) {
        if (block == null || block == net.minecraft.world.level.block.Blocks.f_50016_) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState st = block.m_49966_();
        if (st.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
            return false; // 液体不算
        }
        return !st.m_60815_(); // isSolid false = 无碰撞箱
    }

    /* ================= v1.5.279：替代方块多维度划分 =================
     * 用户："自定义方块的种类需要根据多方面维度进行新的划分，仅仅根据一格高、
     * 半格高这些不够"。新增两个自动判定维度（无需配置）：
     * - 材质族：木/石/砖/矿/璃/陶/毛/他（匹配时同族优先）
     * - 功能：红石/照明/存储/炉/装饰/结构（面板显示标记）
     * 与既有形态（半格/一格/竖两格/横两格）+ 碰撞（无碰撞区）共同构成多维标签。 */

    /** 材质族标记（替代品面板显示 + 同族优先匹配用） */
    public static String materialFamily(Block block) {
        if (block == null) {
            return "\u4ed6"; // 他
        }
        // v1.5.284：getKey 判空——未注册方块（外部蓝图/未装 mod 的方块）不 NPE
        net.minecraft.resources.ResourceLocation key0 = ForgeRegistries.BLOCKS.getKey(block);
        if (key0 == null) {
            return "\u4ed6"; // 他
        }
        String id = key0.toString();
        if (id.contains("glass")) {
            return "\u7483"; // 璃
        }
        if (id.contains("wool") || id.contains("_carpet")) {
            return "\u6bdb"; // 毛
        }
        if (id.contains("terracotta")) {
            return "\u9676"; // 陶
        }
        if (id.contains("_ore")
                || id.contains("coal_block") || id.contains("iron_block") || id.contains("gold_block")
                || id.contains("diamond_block") || id.contains("emerald_block") || id.contains("lapis_block")
                || id.contains("copper_block") || id.contains("netherite_block") || id.contains("quartz_block")) {
            return "\u77ff"; // 矿
        }
        if (block instanceof net.minecraft.world.level.block.RotatedPillarBlock
                && (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae"))
                || id.contains("_planks") || id.endsWith("_log") || id.endsWith("_wood")
                || id.contains("_wooden_") || id.contains("oak_") || id.contains("spruce_")
                || id.contains("birch_") || id.contains("jungle_") || id.contains("acacia_")
                || id.contains("dark_oak_") || id.contains("mangrove_") || id.contains("cherry_")
                || id.contains("crimson_") || id.contains("warped_") || id.contains("bamboo_")) {
            return "\u6728"; // 木
        }
        if (id.contains("stone") || id.contains("cobble") || id.contains("deepslate")
                || id.contains("andesite") || id.contains("diorite") || id.contains("granite")
                || id.contains("blackstone") || id.contains("basalt") || id.contains("calcite")
                || id.contains("tuff") || id.contains("dripstone") || id.contains("obsidian")
                || id.contains("purpur") || id.contains("prismarine")) {
            return "\u77f3"; // 石
        }
        if (id.contains("_bricks") || id.contains("_brick_") || id.endsWith("_brick")) {
            return "\u7816"; // 砖
        }
        return "\u4ed6"; // 他
    }

    /** 功能标记（替代品面板显示）——判定顺序：红石 > 照明 > 存储 > 炉 > 装饰 > 结构 */
    public static String blockFunction(Block block) {
        if (block == null) {
            return "\u7ed3\u6784"; // 结构
        }
        if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || block instanceof net.minecraft.world.level.block.DiodeBlock
                || block instanceof net.minecraft.world.level.block.LeverBlock
                || block instanceof net.minecraft.world.level.block.ButtonBlock
                || block instanceof net.minecraft.world.level.block.PressurePlateBlock
                || block instanceof net.minecraft.world.level.block.ObserverBlock
                || block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || block instanceof net.minecraft.world.level.block.DispenserBlock
                || block instanceof net.minecraft.world.level.block.DropperBlock
                || block instanceof net.minecraft.world.level.block.NoteBlock
                || block instanceof net.minecraft.world.level.block.TargetBlock
                || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock
                || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                || block instanceof net.minecraft.world.level.block.TripWireHookBlock
                || block instanceof net.minecraft.world.level.block.RedstoneLampBlock) {
            return "\u7ea2\u77f3"; // 红石
        }
        // 照明：火把/灯笼/蜡烛/末地烛 + 无独立类的发光方块（glowstone/海晶灯/
        // 菌光体/南瓜灯——1.20.1 为纯 Block 实例，按注册名判定）
        // v1.5.284：getKey 判空——未注册方块不 NPE
        net.minecraft.resources.ResourceLocation key1 = ForgeRegistries.BLOCKS.getKey(block);
        String bid = key1 == null ? "" : key1.toString();
        if (block instanceof net.minecraft.world.level.block.TorchBlock
                || block instanceof net.minecraft.world.level.block.LanternBlock
                || block instanceof net.minecraft.world.level.block.CandleBlock
                || block instanceof net.minecraft.world.level.block.EndRodBlock
                || bid.contains("glowstone") || bid.contains("sea_lantern")
                || bid.contains("shroomlight") || bid.contains("jack_o_lantern")) {
            return "\u7167\u660e"; // 照明
        }
        if (block instanceof net.minecraft.world.level.block.ChestBlock
                || block instanceof net.minecraft.world.level.block.BarrelBlock
                || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                || block instanceof net.minecraft.world.level.block.HopperBlock
                || block instanceof net.minecraft.world.level.block.EnderChestBlock) {
            return "\u5b58\u50a8"; // 存储
        }
        if (block instanceof net.minecraft.world.level.block.FurnaceBlock
                || block instanceof net.minecraft.world.level.block.BlastFurnaceBlock
                || block instanceof net.minecraft.world.level.block.SmokerBlock) {
            return "\u7089"; // 炉
        }
        if (block instanceof net.minecraft.world.level.block.CarpetBlock
                || block instanceof net.minecraft.world.level.block.FlowerPotBlock
                || block instanceof net.minecraft.world.level.block.SignBlock
                || block instanceof net.minecraft.world.level.block.BannerBlock
                || block instanceof net.minecraft.world.level.block.FenceBlock
                || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                || block instanceof net.minecraft.world.level.block.DoorBlock) {
            return "\u88c5\u9970"; // 装饰（花/地毯/栅栏/门/活板门/告示牌/花盆）
        }
        return "\u7ed3\u6784"; // 结构
    }

    public static List<String> altSlabs() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_SLABS.get());
    }

    public static List<String> altBlocks() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_BLOCKS.get());
    }

    public static List<String> altTalls() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_TALLS.get());
    }

    /** v1.5.275：横两格替代品（床） */
    public static List<String> altWides() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_WIDES.get());
    }

    /** v1.5.275：无碰撞替代品（花/火把/地毯等） */
    public static List<String> altNoClips() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_NOCLIPS.get());
    }

    /**
     * v1.5.254：解析步骤状态串为 BlockState。状态串只有 Properties 没有 Name
     * （提取/palette 生成的 "{facing:...}" 格式）→ 补上实际放置方块的注册名——
     * 旧版 NbtUtils.m_247651_ 对无 Name 的 tag 直接返回空气（字节码实证第 0-17
     * 行）→ doPlace 里被 m_60795_() 过滤 → 台阶上下半/楼梯朝向/门开合从未生效。
     * 解析失败或结果为空气返回 null（调用方用默认状态兜底）。
     */
    public static net.minecraft.world.level.block.state.BlockState parseStepState(
            net.minecraft.server.level.ServerLevel level, Block placed, String stateSnbt) {
        if (stateSnbt == null) {
            return null;
        }
        try {
            net.minecraft.nbt.CompoundTag stateTag = net.minecraft.nbt.NbtUtils.m_178024_(stateSnbt);
            if (!stateTag.m_128425_("Name", 8)) {
                // v1.5.371：state-only SNBT（{facing:"south",lit:"false"}）→ 把 Name 之外
                // 的属性【包进 Properties 子标签】——旧版只补 Name，facing/half/shape 等
                // 全挂在顶层，readBlockState(m_247651_) 只读 Properties → 全部回落默认态
                // （朝向全朝北/朝下）——机器/门/床/楼梯朝向全错的根因（"机器都用不了"）。
                net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
                for (String key : stateTag.m_128431_()) {
                    if ("Name".equals(key)) {
                        continue;
                    }
                    net.minecraft.nbt.Tag v = stateTag.m_128423_(key);
                    if (v != null) {
                        props.m_128365_(key, v);
                    }
                }
                net.minecraft.nbt.CompoundTag wrapped = new net.minecraft.nbt.CompoundTag();
                ResourceLocation rid = ForgeRegistries.BLOCKS.getKey(placed);
                if (rid != null) {
                    wrapped.m_128359_("Name", rid.toString());
                }
                wrapped.m_128365_("Properties", props);
                stateTag = wrapped;
            }
            net.minecraft.world.level.block.state.BlockState parsed = net.minecraft.nbt.NbtUtils.m_247651_(
                    level.m_246945_(net.minecraft.core.registries.Registries.f_256747_), stateTag);
            return parsed != null && !parsed.m_60795_() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack extractExact(IItemHandler inv, Item item, int count) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                return inv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    /** v1.5.317：女仆或主人背包是否有指定物品（水桶工具检查——只查不取） */
    private static boolean hasBucketTool(EntityMaid maid, String bucketItemId) {
        Item bucket = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse(bucketItemId));
        if (bucket == null) {
            return false;
        }
        IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && s.m_41720_() == bucket) {
                return true;
            }
        }
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player owner) {
            net.minecraft.world.Container ci = owner.m_150109_();
            for (int i = 0; i < ci.m_6643_(); i++) {
                ItemStack s = ci.m_8020_(i);
                if (!s.m_41619_() && s.m_41720_() == bucket) {
                    return true;
                }
            }
        }
        return false;
    }

    /** v1.5.317：岩浆放置后返还空桶（岩浆桶用后变空桶）——主人背包优先，其次女仆
     *  背包，最后掉落在主人/女仆身边（不掉在建造区内，防被完工清理回收）。 */
    public static void returnEmptyBucket(net.minecraft.server.level.ServerLevel level,
                                         EntityMaid maid, net.minecraft.core.BlockPos target) {
        if (maid == null || level == null) {
            return;
        }
        Item bucket = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:bucket"));
        if (bucket == null) {
            return;
        }
        ItemStack bucketStack = new ItemStack(bucket);
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player owner) {
            if (owner.m_150109_().m_36054_(bucketStack)) {
                return; // 放入主人背包
            }
        }
        ItemStack left = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                maid.getMaidInv(), bucketStack, false);
        if (left.m_41619_()) {
            return; // 放入女仆背包
        }
        // 双背包都满 → 掉落在主人（无主人则女仆）身边
        double x = target.m_123341_() + 0.5;
        double y = target.m_123342_() + 1.0;
        double z = target.m_123343_() + 0.5;
        if (maid.m_269323_() != null) {
            x = maid.m_269323_().m_20185_();
            y = maid.m_269323_().m_20186_();
            z = maid.m_269323_().m_20189_();
        } else {
            x = maid.m_20185_();
            y = maid.m_20186_();
            z = maid.m_20189_();
        }
        net.minecraft.world.entity.item.ItemEntity ent = new net.minecraft.world.entity.item.ItemEntity(
                level, x, y, z, left);
        level.m_7967_(ent);
    }

    /**
     * 以女仆为中心：把蓝图步骤整体平移，使蓝图的平面中心落在原点
     * （原点 = 女仆脚下，由调用方给出）。返回新的步骤列表。
     */
    /**
     * v1.5.80：地形层压缩——外部蓝图（.schem/.litematic）常包含导出时的原址
     * 地形（草地/泥土/石头/矿物，红石建筑甚至还原整个地下矿层）。逐层统计：
     * 底部连续"地形层"（层方块数 ≥32 且地形方块占比 ≥85%）判定为还原区，
     * 只保留紧贴建筑的最多 1 层作为地基，更深的全丢弃——大幅节省材料与时间。
     * 小层（<32 块）不判定（树/装饰等少量方块不受影响）；
     * 纯地形蓝图（无建筑层）不压缩；地下室等地下建筑层（非地形占比高）不受影响。
     * 返回压缩后的步骤列表（保留首行 tag 与顺序）。
     */
    public static List<String> trimTerrainLayers(List<String> steps) {
        if (steps == null || steps.size() < 4) {
            return steps;
        }
        java.util.TreeMap<Integer, int[]> perLayer = new java.util.TreeMap<>(); // y -> [地形数, 总数]
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            try {
                int y = Integer.parseInt(parts[1]);
                int[] c = perLayer.computeIfAbsent(y, k -> new int[2]);
                c[1]++;
                if (TERRAIN_BLOCKS.contains(parts[3])) {
                    c[0]++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (perLayer.size() <= 1) {
            return steps;
        }
        // 从最低层向上找第一个"非地形层"（建筑开始层）
        int groundY = -1;
        for (java.util.Map.Entry<Integer, int[]> e : perLayer.entrySet()) {
            int[] c = e.getValue();
            if (c[1] < 32 || c[0] * 100 / c[1] < 85) {
                groundY = e.getKey();
                break;
            }
        }
        if (groundY < 0) {
            return steps; // 全是地形层（纯地形蓝图）——不压缩
        }
        int minKeep = groundY - 1; // 保留建筑层及其下 1 层地基
        if (perLayer.firstKey() >= minKeep) {
            return steps; // 无还原区
        }
        java.util.List<String> out = new java.util.ArrayList<>(steps.size());
        int dropped = 0;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts != null) {
                try {
                    if (Integer.parseInt(parts[1]) < minKeep) {
                        dropped++;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(step);
        }
        if (dropped > 0) {
            LOGGER.info("地形层压缩：丢弃 {} 个地形方块步骤（{} 层之下的还原区，保留 {} 层地基）",
                    dropped, groundY, minKeep);
        }
        return out;
    }

    public static List<String> centerSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        // v1.5.80：地形层压缩（外部蓝图常含原址地形——草地/矿层，不还原）
        steps = trimTerrainLayers(steps);
        // v1.5.77：y 升序排序（底部先建）——外部蓝图（.schem/.nbt）步骤保持文件
        // 原始顺序，装饰/植物可能排在支撑地面之前 → "支撑缺失死锁"：主循环窗口
        // 全是放不上的附着方块（延后），支撑步骤在游标之后永远建不到，延后集
        // 越积越多、游标停滞（build stall 死循环）。排序保证地面/墙体先于其上的
        // 附着物；首行 tag（parseStep 不可解析行）保序在前；同层稳定排序。
        java.util.List<String> head = new java.util.ArrayList<>();
        java.util.List<String> body = new java.util.ArrayList<>();
        for (String step : steps) {
            if (parseStep(step) == null) {
                head.add(step);
            } else {
                body.add(step);
            }
        }
        // v1.5.78：拟人化搭建优先级——排序键 = 优先级 → y → x → z。
        // 结构主体（墙/地板/屋顶）最先立起 → 功能/家具（门/楼梯/箱子）次之 →
        // 装饰与红石机械（花/火把/按钮/活塞）最后。视觉上"骨架先成型再填充"；
        // 装饰的支撑（结构方块）先建好 → 支撑缺失延后大幅减少 → 不钻牛角尖；
        // 中途取消/中断时主体已完整，损失最小。
        // v1.5.252x：结构类内部再分【骨架优先】——水平外轮廓（x/z 在蓝图边界）的
        // 方块先建，内部填充后建（排序键 = prio → 骨架 → y → x → z）。
        // 效果：四面墙圈/四角柱/屋顶边缘先立起来（建筑"轮廓"从底到顶成型），
        // 再逐层填充墙面与内部——更接近真人"先搭骨架再填墙"的建造习惯。
        java.util.Map<String, Integer> prioCache = new java.util.HashMap<>();
        // 骨架判定用的水平范围（非空气步骤的 x/z 边界）
        final int[] skel = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (String s : body) {
            String[] pp = parseStep(s);
            if (pp == null) {
                continue;
            }
            try {
                int px = Integer.parseInt(pp[0]);
                int pz = Integer.parseInt(pp[2]);
                skel[0] = Math.min(skel[0], px);
                skel[1] = Math.max(skel[1], px);
                skel[2] = Math.min(skel[2], pz);
                skel[3] = Math.max(skel[3], pz);
            } catch (NumberFormatException ignored) {
            }
        }
        body.sort((a, b) -> {
            String[] pa = parseStep(a);
            String[] pb = parseStep(b);
            if (pa == null || pb == null) {
                return 0;
            }
            int pra = buildPriority(pa[3], prioCache);
            int prb = buildPriority(pb[3], prioCache);
            if (pra != prb) {
                return pra - prb;
            }
            // v1.5.252x：结构类（prio 0）内部——骨架（水平外轮廓）优先于填充
            if (pra == 0 && skel[0] != Integer.MAX_VALUE) {
                boolean sa = isSkeleton(pa, skel);
                boolean sb = isSkeleton(pb, skel);
                if (sa != sb) {
                    return sa ? -1 : 1;
                }
            }
            try {
                int ya = Integer.parseInt(pa[1]);
                int yb = Integer.parseInt(pb[1]);
                if (ya != yb) {
                    return ya - yb;
                }
                int xa = Integer.parseInt(pa[0]);
                int xb = Integer.parseInt(pb[0]);
                if (xa != xb) {
                    return xa - xb;
                }
                return Integer.parseInt(pa[2]) - Integer.parseInt(pb[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        steps = new java.util.ArrayList<>(head.size() + body.size());
        steps.addAll(head);
        steps.addAll(body);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            int x = Integer.parseInt(parts[0]);
            int z = Integer.parseInt(parts[2]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        int offX = -(minX + maxX) / 2;
        int offZ = -(minZ + maxZ) / 2;
        if (offX == 0 && offZ == 0) {
            return dedupeSteps(steps);
        }
        List<String> out = new ArrayList<>(steps.size());
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                out.add(step);
                continue;
            }
            int x = Integer.parseInt(parts[0]) + offX;
            int z = Integer.parseInt(parts[2]) + offZ;
            StringBuilder sb = new StringBuilder();
            sb.append(x).append(',').append(parts[1]).append(',').append(z).append(',').append(parts[3]);
            if (parts[4] != null) {
                sb.append('|').append(parts[4]);
            }
            if (parts[5] != null) {
                sb.append('|').append(parts[5]);
            }
            out.add(sb.toString());
        }
        return dedupeSteps(out);
    }

    /**
     * v1.5.55：步骤坐标去重——同 (x,y,z) 多步骤（生成器/格式转换器缺陷）只保留
     * 最后一条（顺序保留首次出现位置）。根治"石头↔草方块叠加态"互搏：
     * 同坐标两个步骤会被不同女仆反复覆盖放置（放石头→盖草→放石头→…无限循环）。
     * 所有步骤必经（centerSteps 在 getBlueprint 后统一调用），一处去重全链路生效。
     */
    private static List<String> dedupeSteps(List<String> steps) {
        if (steps == null || steps.size() < 2) {
            return steps;
        }
        java.util.LinkedHashMap<Long, String> dedup = new java.util.LinkedHashMap<>();
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                dedup.put(-1L - dedup.size(), step); // 无法解析的行保序保留
                continue;
            }
            long key;
            try {
                key = (long) (Integer.parseInt(parts[0]) & 0xFFFFF) << 42
                        | (long) (Integer.parseInt(parts[1]) & 0x1FFFFF) << 21
                        | (Integer.parseInt(parts[2]) & 0x1FFFFF);
            } catch (NumberFormatException e) {
                dedup.put(-1L - dedup.size(), step);
                continue;
            }
            dedup.put(key, step); // 后写覆盖先写 = 保留最后一条
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * v1.5.57：建造完成 → 红石统一激活。建造期间全部静默放置（flag 2，机械冻结，
     * 活塞不会推掉刚建的墙），完成后对蓝图区域的红石组件重放一次（flag 3 触发
     * 邻居更新）→ 红石线重算 power → 活塞/灯/机械正常启动。
     *
     * v1.5.288：红石唤醒 v2（红石机器"建好不运行/状态冻结"根因修复）：
     * - 1.20.1 setBlock（Level.m_6933_）对【同状态重放】直接走"状态相等"分支，
     *   不会调用 onPlace（m_60705_）；updateNeighborsAt（m_46717_）又只广播
     *   【水平】邻居 + 比较器穿透，不含上下方向——旧版 recalc 对线/火把/活塞
     *   基本等于"什么都没做"：红石线保留图纸导出时的冻结 power、活塞保留冻结
     *   伸缩态 → 机器建好不运行 / 状态错乱。
     * - v2 每轮补齐：① 组件自身 neighborChanged（m_6861_ 直调——红石线重算
     *   power、活塞 checkIfExtend 重新决定伸缩（含 BUD/准连接）、灯/铁轨/火把
     *   重新检查供电）；② 垂直邻居手动补广播（m_46717_ 不含上下方向）；
     *   ③ 水平邻居由 m_46717_ 自带（含比较器穿透）。
     * - 白名单补全：活塞 / 铁轨 / 绊线 / 陷阱箱 / 门 / 音符盒 / 标靶（旧版缺失，
     *   这些方块被冻结时永远不会被唤醒）。
     * - 多轮收敛（3 轮）：组件重算依赖其他组件的最新 power，一轮内处理顺序有
     *   先后 → 2~3 轮后全部稳定；幂等无害（状态已稳定时重放/重算均无副作用）。
     * - 替代品兼容：目标格现状方块是红石组件就刷新（不要求与蓝图方块同名）。
     */
    public static void recalcRedstone(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) {
        java.util.List<int[]> comps = new java.util.ArrayList<>();
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            net.minecraft.world.level.block.Block b = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse(parts[3]));
            if (b == null || !isRedstoneRelevant(b)) {
                continue;
            }
            comps.add(new int[]{x, y, z});
        }
        if (comps.isEmpty()) {
            return;
        }
        for (int round = 0; round < 3; round++) {
            for (int[] c : comps) {
                BlockPos pos = origin.m_7918_(c[0], c[1], c[2]);
                if (!level.m_46749_(pos)) {
                    continue; // 区块未加载（完成时正常不会发生）
                }
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(pos);
                net.minecraft.world.level.block.Block cur = st.m_60734_();
                // 现状方块是红石组件就唤醒（替代品放置的位置同样刷新）
                if (isRedstoneRelevant(cur)) {
                    kickRedstone(level, pos, cur, st);
                }
            }
        }
    }

    /** v1.5.288：红石组件判定（recalcRedstone 白名单——旧版缺活塞/铁轨/绊线等） */
    private static boolean isRedstoneRelevant(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.TorchBlock
                || b instanceof net.minecraft.world.level.block.LeverBlock
                || b instanceof net.minecraft.world.level.block.ButtonBlock
                || b instanceof net.minecraft.world.level.block.PressurePlateBlock
                || b instanceof net.minecraft.world.level.block.PoweredBlock
                || b instanceof net.minecraft.world.level.block.RedstoneLampBlock
                || b instanceof net.minecraft.world.level.block.TripWireHookBlock
                || b instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                || b instanceof net.minecraft.world.level.block.ObserverBlock
                || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                || b instanceof net.minecraft.world.level.block.BaseRailBlock
                || b instanceof net.minecraft.world.level.block.TripWireBlock
                || b instanceof net.minecraft.world.level.block.TrappedChestBlock
                || b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.NoteBlock
                || b instanceof net.minecraft.world.level.block.TargetBlock;
    }

    /**
     * v1.5.288：单格红石唤醒——等价于"玩家在旁边重新放了一块"：
     * ① 同状态重放 flag 3（信号源走 m_46717_ 广播水平邻居 + 比较器穿透）；
     * ② 垂直邻居手动补广播（1.20.1 m_46717_ 只走水平）；
     * ③ 组件自身 neighborChanged 直调——红石线重算 power（旧版冻结不动）、
     *    活塞 checkIfExtend 重新决定伸缩（BUD/准连接也能唤醒）、灯/铁轨/火把
     *    重新检查供电。canSurvive 失败时跳过自调（防止线被拆掉掉落）。
     */
    private static void kickRedstone(net.minecraft.server.level.ServerLevel level, BlockPos pos,
                                     net.minecraft.world.level.block.Block block,
                                     net.minecraft.world.level.block.state.BlockState st) {
        level.m_7731_(pos, st, 3);
        level.m_46717_(pos, block);
        level.m_213960_(level.m_8055_(pos.m_7495_()), pos.m_7495_(), block, pos, false);
        level.m_213960_(level.m_8055_(pos.m_7494_()), pos.m_7494_(), block, pos, false);
        if (st.m_60796_(level, pos)) {
            block.m_6861_(st, level, pos, block, pos, false); // Block.neighborChanged（6 参，state 在前）
        }
    }

    /* ==================== v1.5.315+ 红石机器专属建造策略 ====================
     * 逐个解析 blueprints 目录 21 台外部机器蓝图（litematic 方块构成+红石朝向）后归类：
     * - 轰炸机类（三向/双向/2TNT/自返回/推土机）：活塞+粘液块弹头，红石块为移动
     *   信号源，观察者检测弹头运动，探测铁轨（矿车压轨）触发 TNT 复制。
     * - 打包机类（伪12倍速/六倍速/混杂/带分类/自适应）：灵魂沙气泡柱+冰道水流冲
     *   物品→漏斗→投掷器/活塞打包入箱，观察者/音符盒时钟自转。
     * - 潜影盒仓库类（单排/双排/大仓库）：观察者/音符盒 BUD 检测物品输入→活塞推
     *   潜影盒→比较器/漏斗分类，冰道+气泡柱运输。
     * - 其他：分类机（漏斗链+比较器）/铁砧机（观察者时钟）/村民机（压板触发）/
     *   南瓜机（附生茎无法建造）/甘蔗机（纯水流半自动）。
     * - Never4Get：巨型 TNT 大炮——手动装填触发。
     *
     * v1.5.316【红石机器改革：专属顺序 + 活建造（去禁锢）】：
     * - 机器不再"静默放置（flag 2）+ 完工唤醒/不唤醒"（旧禁锢：机器建好冻结在
     *   蓝图导出时的瞬态，线保留冻结 power、活塞保留冻结伸缩态 → 建好不运行）。
     * - 机器改走【专属搭建顺序 sortMachinePlan】：按红石拓扑分层
     *   结构 → 惰性机构 → 活动件 → 传感/信号 → 动力源 → TNT，动力源最后落位。
     * - 机器【活放置】（doPlace flag 3）：红石/水流随放随算；机器在最后一格
     *   动力源落下时自然进入运行态——"建好就能跑"，无需完工唤醒。
     * - 轰炸机类完工自动在探测铁轨上生成矿车（spawnStartMinecarts）启动复制循环。
     * - 关闭开关 MaidSmartConfig.BUILD_MACHINE_SMART = 完整回退旧行为。
     */

    /** v1.5.315：蓝图 id（内置 maid_smart:xxx 或外部 maid_smart_ext:<文件名>）→ 机器家族；
     *  非机器返回 null。 */
    public static String machineFamily(String id) {
        if (id == null) {
            return null;
        }
        String low = id.toLowerCase(java.util.Locale.ROOT);
        // v1.5.369：内置农场（机械类蓝图）——走机器专属搭建顺序 + 完工使用说明
        if (low.equals("maid_smart:farm_cactus")) {
            return "farm_cactus";
        }
        if (low.equals("maid_smart:farm_superfurnace")) {
            return "farm_superfurnace";
        }
        if (low.equals("maid_smart:farm_lavafountain")) {
            return "farm_lavafountain";
        }
        if (low.equals("maid_smart:farm_crop")) {
            return "farm_crop";
        }
        if (low.equals("maid_smart:farm_tree")) {
            return "farm_tree";
        }
        if (low.equals("maid_smart:farm_chicken")) {
            return "farm_chicken";
        }
        if (low.equals("maid_smart:machine_furnace_array")) {
            return "furnace_array";
        }
        if (low.contains("never4get")) {
            return "never4get";
        }
        if (low.contains("轰炸机") || low.contains("bomber") || low.contains("推土机")) {
            return "bomber";
        }
        if (low.contains("打包机") || low.contains("packer") || low.contains("打包")) {
            return "packer";
        }
        if (low.contains("潜影盒") || low.contains("shulker") || low.contains("仓库")) {
            return "warehouse";
        }
        if (low.contains("分类机") || low.contains("sorter") || low.contains("分类")) {
            return "sorter";
        }
        if (low.contains("南瓜") || low.contains("西瓜") || low.contains("melon")) {
            return "farm_pumpkin";
        }
        if (low.contains("甘蔗") || low.contains("sugar_cane")) {
            return "farm_sugar";
        }
        if (low.contains("铁砧") || low.contains("anvil")) {
            return "anvil";
        }
        if (low.contains("村民") || low.contains("villager")) {
            return "villager";
        }
        return null;
    }

    /** v1.5.315：TNT 类机器完成后【不唤醒红石】——旧禁锢遗留：唤醒瞬间活塞预伸缩/
     *  观察者预脉冲会把弹头推飞、TNT 错位（轰炸机/Never4Get 巨型炮）。
     *  v1.5.316 起机器改走活建造，不再需要"唤醒/不唤醒"区分；本方法保留兼容，
     *  已无调用点。 */
    public static boolean machineQuietFinish(String id) {
        String fam = machineFamily(id);
        return "bomber".equals(fam) || "never4get".equals(fam);
    }

    /** v1.5.316：是否红石机器蓝图（内置 machine_ 前缀 或 外部机器家族名匹配）——
     *  是则走机器专属搭建顺序 + 活建造。 */
    public static boolean isMachineBlueprint(String id) {
        if (id == null) {
            return false;
        }
        return id.startsWith("maid_smart:machine_") || machineFamily(id) != null;
    }

    /** v1.5.315：机器完成提示（中文）——告知玩家该机器如何启动/预期行为；非机器返回空串。 */
    public static String machineFinishTip(String id) {
        String fam = machineFamily(id);
        if (fam == null) {
            return "";
        }
        switch (fam) {
            case "bomber":
                // v1.5.320：动态提示由完工流程生成（已放置 X 辆 / 缺矿车未启动）
                return "";
            case "never4get":
                return "\u00a7e【机器】巨型红石结构已就位,未自动激活——请手动触发标靶/总开关";
            case "packer":
                return "\u00a7e【机器】打包机时钟已就位——放入物品即自动收集打包（有拉杆的机器先拉杆启动）";
            case "warehouse":
                return "\u00a7e【机器】潜影盒仓库已就位——物品入漏斗后自动分类入盒";
            case "sorter":
                return "\u00a7e【机器】分类机已就位——漏斗设好过滤物品后自动分类";
            case "farm_pumpkin":
                return "\u00a7e【机器】注意:该南瓜机蓝图无自动触发时钟,长成后需玩家手动确认";
            case "farm_sugar":
                return "\u00a7e【机器】注意:该甘蔗机为纯水流半自动,无自动收割装置";
            case "anvil":
                return "\u00a7e【机器】铁砧机观察者时钟已就位——放入铁砧/原料自动运作";
            case "villager":
                return "\u00a7e【机器】村民机已就位——放入村民与床后自动运作";
            // v1.5.369：内置农场使用说明（完工系统消息附带）
            case "farm_cactus":
                return "\u00a7e【使用】仙人掌长到 3 格会被断顶环碰碎，掉落物被四角水流冲进中央井，"
                        + "下井梯取箱内仙人掌即可；两层塔自动同时产";
            case "farm_superfurnace":
                return "\u00a7e【使用】顶部一排箱放待烧物品、侧面(背面)一排箱放煤/岩浆桶，"
                        + "成品自动流到底部输出箱——纯漏斗无红石，放料即烧";
            case "farm_lavafountain":
                return "\u00a7e【使用】滴水石锥会随时间把岩浆滴满炼药锅，满了用桶从锅里舀岩浆即可（无限岩浆）";
            case "farm_crop":
                return "\u00a7e【使用】3 个发射器里装满骨粉，站在前方台阶上拿种子对着耕地长按右键，"
                        + "边拨拉杆边种——每次拨杆 3 台发射器齐喷骨粉催熟作物";
            case "farm_tree":
                return "\u00a7e【使用】发射器里装满骨粉，拨拉杆开启后骨粉催熟树苗，活塞塔把长高的树推倒，"
                        + "原木落到前方漏斗进箱；砍完再种树苗重复即可";
            case "farm_chicken":
                return "\u00a7e【使用】在顶部 3×3 漏斗平台上放鸡，鸡下的蛋自动进发射器；拨拉杆向鸡舍投蛋，"
                        + "小鸡长大后透过前排半砖缝隙用剑击杀取熟肉/经验，掉落自动进中央箱";
            case "furnace_array":
                return "\u00a7e【使用】顶部箱子放待烧物品、侧面燃料箱放煤，成品自动流到底部输出箱——"
                        + "纯漏斗无红石";
            default:
                return "";
        }
    }

    /** v1.5.316：机器专属搭建顺序——排序键 = 机器相位 → y → x → z（自下而上稳定）。
     *  红石机器按红石拓扑分层放置：结构 → 惰性机构 → 活动件 → 传感/信号 →
     *  动力源 → TNT。动力源最后落位，配合活放置（flag 3）机器在最后一格落下时
     *  自然进入运行态。相位内 y 升序保证支撑/槽壁/灵魂沙/冰先于其上的附着物/水。 */
    public static List<String> sortMachinePlan(List<String> steps, String family) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        List<String> head = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (String step : steps) {
            if (parseStep(step) == null) {
                head.add(step);
            } else {
                body.add(step);
            }
        }
        Map<String, Integer> cache = new HashMap<>();
        body.sort((a, b) -> {
            String[] pa = parseStep(a);
            String[] pb = parseStep(b);
            if (pa == null || pb == null) {
                return 0;
            }
            int pha = machinePhase(pa[3], family, cache);
            int phb = machinePhase(pb[3], family, cache);
            if (pha != phb) {
                return pha - phb;
            }
            // 动力源相位内：红石线（导体）先于红石块/火把/拉杆等源——源最后放，
            // 全网络在最后一格落下时一次性带电（避免中途半网通电误触）
            if (pha == 5) {
                boolean wa = "minecraft:redstone_wire".equals(pa[3]);
                boolean wb = "minecraft:redstone_wire".equals(pb[3]);
                if (wa != wb) {
                    return wa ? -1 : 1;
                }
            }
            try {
                int ya = Integer.parseInt(pa[1]);
                int yb = Integer.parseInt(pb[1]);
                if (ya != yb) {
                    return ya - yb;
                }
                int xa = Integer.parseInt(pa[0]);
                int xb = Integer.parseInt(pb[0]);
                if (xa != xb) {
                    return xa - xb;
                }
                return Integer.parseInt(pa[2]) - Integer.parseInt(pb[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        List<String> out = new ArrayList<>(head.size() + body.size());
        out.addAll(head);
        out.addAll(body);
        return out;
    }

    /** v1.5.316：机器搭建相位——1 结构/外壳 → 2 惰性机构 → 3 活动件 →
     *  4 传感/信号 → 5 动力源 → 6 TNT。识别失败默认 1（结构，最安全）。 */
    private static int machinePhase(String blockId, String family, Map<String, Integer> cache) {
        Integer cached = cache.get(blockId);
        if (cached != null) {
            return cached;
        }
        int phase = 1;
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockId));
        if (block != null) {
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                phase = 6;
            } else if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneTorchBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneWallTorchBlock
                    || block instanceof net.minecraft.world.level.block.PoweredBlock
                    || block instanceof net.minecraft.world.level.block.LeverBlock
                    || block instanceof net.minecraft.world.level.block.ButtonBlock
                    || block instanceof net.minecraft.world.level.block.PressurePlateBlock) {
                phase = 5;
            } else if (block instanceof net.minecraft.world.level.block.ObserverBlock
                    || block instanceof net.minecraft.world.level.block.DiodeBlock
                    || block instanceof net.minecraft.world.level.block.TripWireHookBlock
                    || block instanceof net.minecraft.world.level.block.TripWireBlock
                    || block instanceof net.minecraft.world.level.block.TargetBlock
                    || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
                    || block instanceof net.minecraft.world.level.block.TrappedChestBlock) {
                phase = 4;
            } else if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                    || block instanceof net.minecraft.world.level.block.SlimeBlock
                    || block instanceof net.minecraft.world.level.block.HoneyBlock
                    || block instanceof net.minecraft.world.level.block.RedstoneLampBlock) {
                phase = 3;
            } else if (block instanceof net.minecraft.world.level.block.HopperBlock
                    || block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock
                    || block instanceof net.minecraft.world.level.block.DispenserBlock
                    || block instanceof net.minecraft.world.level.block.DropperBlock
                    || block instanceof net.minecraft.world.level.block.ComposterBlock
                    || block instanceof net.minecraft.world.level.block.NoteBlock
                    || block instanceof net.minecraft.world.level.block.BaseRailBlock
                    || block instanceof net.minecraft.world.level.block.IceBlock
                    || block instanceof net.minecraft.world.level.block.SoulSandBlock
                    || block instanceof net.minecraft.world.level.block.LiquidBlock
                    || block instanceof net.minecraft.world.level.block.FarmBlock
                    || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                    || block instanceof net.minecraft.world.level.block.DoorBlock
                    || block instanceof net.minecraft.world.level.block.TrapDoorBlock
                    || block instanceof net.minecraft.world.level.block.FenceGateBlock
                    || block instanceof net.minecraft.world.level.block.BedBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                    || block instanceof net.minecraft.world.level.block.AnvilBlock
                    || block instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    || block instanceof net.minecraft.world.level.block.BubbleColumnBlock) {
                phase = 2;
            }
        }
        cache.put(blockId, phase);
        return phase;
    }

    /** v1.5.316：机器模式状态归一化——丢弃蓝图导出时的冻结瞬态（伸出活塞/通电铁轨/
     *  冻结 power/lit），让方块以自然初始态落地，由活放置（flag 3）收敛到设计态。
     *  只处理红石相关方块；朝向/台阶/楼梯等设计属性原样保留。
     *  状态串格式 "minecraft:xxx{key:\"value\",...}"（paletteStateString 规范 SNBT）。 */
    public static String normalizeMachineState(String blockId, String stateSnbt) {
        if (stateSnbt == null || stateSnbt.indexOf('{') < 0) {
            return stateSnbt;
        }
        String keys;
        if ("minecraft:redstone_wire".equals(blockId)) {
            keys = "power|east|west|north|south|up|down";
        } else if ("minecraft:water".equals(blockId) || "minecraft:lava".equals(blockId)) {
            // v1.5.317：液体只放源块（level 归一为 0）——蓝图导出的流动态 level>0
            // 不直接放置，流动由水源模拟产生（防串流/重复；气泡柱/冰道需要源块）
            keys = "level";
        } else if (blockId.contains("piston")) {
            keys = "extended";
        } else if (blockId.contains("rail")) {
            keys = "powered";
        } else if (blockId.contains("torch") || blockId.contains("lamp")) {
            keys = "lit";
        } else if (blockId.contains("lever") || blockId.contains("button")
                || blockId.contains("pressure_plate") || blockId.contains("target")) {
            keys = "powered";
        } else if (blockId.contains("repeater") || blockId.contains("comparator")) {
            keys = "powered|locked";
        } else if (blockId.contains("observer") || blockId.contains("daylight_detector")) {
            keys = "powered";
        } else if (blockId.contains("tnt")) {
            // v1.5.328：TNT 归一 unstable=false——蓝图若导出"已点燃"的 TNT
            //（unstable=true，结构文件在点燃瞬间保存），活建造下任意邻居更新
            // 都会点燃；建造期应放稳定 TNT，由机器运行时触发（neighborChanged）
            keys = "unstable";
        } else {
            return stateSnbt;
        }
        String out = stateSnbt;
        // ① 删 ",key:\"value\""（属性不在最前）
        out = out.replaceAll(",(?:" + keys + "):\"[^\"]*\"", "");
        // ② 删 "key:\"value\","（属性在最前）
        out = out.replaceAll("(?<![A-Za-z])(?:" + keys + "):\"[^\"]*\",", "");
        // ③ 删 "{key:\"value\"}"（唯一属性）→ "{}"
        out = out.replaceAll("\\{(?:" + keys + "):\"[^\"]*\"\\}", "{}");
        return out;
    }

    /** v1.5.316：轰炸机家族完工自动启动——在蓝图全部探测铁轨上生成矿车（探测铁轨被
     *  矿车压住才带电，是 TNT 复制循环的触发条件）。无探测铁轨的轰炸机（2TNT/29方块/
     *  推土机——纯观察者/活塞自启动）自动跳过；铁轨未建成（缺料/被替换）也跳过。
     *  v1.5.320：矿车不再凭空白给——每条探测铁轨消耗 1 辆矿车（女仆背包优先、
     *  主人背包兜底；主人创造模式无限）。背包矿车不足时只放够数的，返回实际放置数，
     *  由调用方提示"还差 X 辆"。 */
    public static int spawnStartMinecarts(net.minecraft.server.level.ServerLevel level,
                                          net.minecraft.core.BlockPos origin,
                                          List<String> plan, String family, EntityMaid maid) {
        if (!"bomber".equals(family)) {
            return 0;
        }
        int spawned = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            if (!"minecraft:detector_rail".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                if (!(level.m_8055_(pos).m_60734_() instanceof net.minecraft.world.level.block.DetectorRailBlock)) {
                    continue; // 探测铁轨未建成 → 跳过
                }
                // v1.5.320：消耗 1 辆矿车（没有矿车 → 不自动生成，调用方提示缺矿车）
                if (maid != null && !consumeMinecart(maid)) {
                    break;
                }
                net.minecraft.world.entity.vehicle.Minecart cart = new net.minecraft.world.entity.vehicle.Minecart(
                        net.minecraft.world.entity.EntityType.f_20469_, level);
                cart.m_6034_(x + 0.5, y + 0.0625, z + 0.5);
                level.m_7967_(cart);
                spawned++;
            } catch (NumberFormatException ignored) {
            }
        }
        if (spawned > 0) {
            LOGGER.info("spawnStartMinecarts: 轰炸机启动矿车 {} 辆", spawned);
        }
        return spawned;
    }

    /** v1.5.320：消耗 1 辆矿车——女仆背包优先，主人背包兜底；主人创造模式无限。
     *  成功返回 true，背包没有矿车返回 false。 */
    private static boolean consumeMinecart(EntityMaid maid) {
        Item minecart = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse("minecraft:minecart"));
        if (minecart == null) {
            return false;
        }
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player owner) {
            if (com.maidsmart.build.BlueprintLib.isCreative(owner)) {
                return true; // 创造模式无限
            }
            // 主人背包：取 1 辆矿车
            net.minecraft.world.entity.player.Inventory inv = owner.m_150109_();
            for (int i = 0; i < inv.m_6643_(); i++) {
                net.minecraft.world.item.ItemStack s = inv.m_8020_(i);
                if (!s.m_41619_() && s.m_41720_() == minecart) {
                    s.m_41774_(1); // shrink(1)
                    if (s.m_41619_()) {
                        inv.m_6836_(i, net.minecraft.world.item.ItemStack.f_41583_);
                    }
                    return true;
                }
            }
        }
        // 女仆背包：取 1 辆矿车
        net.minecraft.world.item.ItemStack taken = extractExact(maid.getMaidInv(), minecart, 1);
        return !taken.m_41619_();
    }

    /** v1.5.315：重放蓝图内全部水方块（flag 3）→ 触发流动计算/灵魂沙气泡柱生成。
     *  建造期水是静默放置（flag 2 无更新）不流动，气泡柱/冰道水道失效 → 打包机、
     *  潜影盒仓库"建好不动"的根因。只碰水，不碰 lava。 */
    public static void activateWater(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.core.BlockPos origin, List<String> plan) {
        int n = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            if (!"minecraft:water".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(pos);
                if (st.m_60734_() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    level.m_7731_(pos, st, 3); // 同状态重放 → 流动计算/气泡柱生成
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            LOGGER.info("activateWater: 重放 {} 个水方块激活水流/气泡柱", n);
        }
    }

    /**
     * v1.5.331：完工 TNT 点火结算——遍历蓝图 TNT 步骤，只点燃【当前邻接带电】的
     * TNT（恢复正确终态）：轰炸机（矿车压轨带电）当场启动复制循环；天机屠龙炮等
     * 观察者/活塞触发机器（TNT 静止时无带电邻居——探针实证 320 TNT 六邻无红石）
     * 保持惰性，触发时才点火——不再"刚建好炸膛"。配合 BuildTntGuard 保护窗口
     * （建造期/完工激活期压制一切 TNT 点火入口），本方法在窗口内直接调用
     * primeTnt（m_57433_，不被 mixin 压制），只认"此刻静止带电"。
     */
    public static void settleTntIgnition(net.minecraft.server.level.ServerLevel level,
                                         net.minecraft.core.BlockPos origin, List<String> plan) {
        int n = 0;
        for (int i = 1; i < plan.size(); i++) {
            String step = plan.get(i);
            if (step == null || !step.contains("tnt")) {
                continue; // 廉价预筛（parseStep 的 split 更贵）
            }
            String[] parts = parseStep(step);
            if (parts == null || !"minecraft:tnt".equals(parts[3])) {
                continue;
            }
            try {
                int x = origin.m_123341_() + Integer.parseInt(parts[0]);
                int y = origin.m_123342_() + Integer.parseInt(parts[1]);
                int z = origin.m_123343_() + Integer.parseInt(parts[2]);
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                net.minecraft.world.level.block.state.BlockState st = level.m_8055_(pos);
                if (!(st.m_60734_() instanceof net.minecraft.world.level.block.TntBlock)) {
                    continue;
                }
                // 只有"当前邻接带电"才点燃（等效于它此刻自然触发）
                if (level.m_276867_(pos)) {
                    net.minecraft.world.level.block.TntBlock.m_57433_(level, pos);
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (n > 0) {
            LOGGER.info("settleTntIgnition: 完工点燃 {} 个邻接带电的 TNT", n);
        }
    }

    /**
     * v1.5.25：过滤掉已建好的步骤（目标格已是目标方块或等价族内替代品），
     * 返回"尚未建造"的步骤列表。续建核心：分批补料后重新下达蓝图时，
     * 已建部分不再重复计算材料/障碍，新料全部用于未建部分。
     * v2.0：统一已建感知——任何下达都扫描区块内与蓝图匹配的方块（同方块或
     * 等价族）视为已建（不拆、只补缺），材料缺口 = 需求 − 已建 − 背包。
     * v1.5.180：level 版（不再依赖女仆实体——创建区块不需要女仆在场）。
     */
    public static List<String> filterBuilt(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        List<String> pending = new ArrayList<>();
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Block want = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(parts[3]));
                net.minecraft.world.level.block.state.BlockState state = level.m_8055_(origin.m_7918_(x, y, z));
                if (want != null
                        && (state.m_60734_() == want || isBuiltEquivalent(parts[3], state.m_60734_()))) {
                    continue; // v2.0：区块内已见匹配方块 = 已建（跳过，不拆）
                }
            } catch (NumberFormatException ignored) {
            }
            pending.add(step);
        }
        return pending;
    }

    /**
     * v1.5.179：扫描区块内已与蓝图匹配（已建）的方块，按材料种类计数——
     * 实时缺料 = 总需求 − 已建（本方法）− 背包（combinedHaveAll）。
     * 与 filterBuilt 同一匹配规则（同方块或等价族）；不依赖女仆实体，直接按
     * 维度扫描（statusText 每 2 秒轮询时用；计划区块已被强制加载，getBlockState
     * 均为内存命中，大蓝图单次全量约毫秒级）。
     */
    public static Map<String, Integer> countBuiltMaterials(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        Map<String, Integer> built = new HashMap<>();
        if (level == null || origin == null) {
            return built;
        }
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null || parts.length < 4) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Block want = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(parts[3]));
                if (want == null) {
                    continue;
                }
                net.minecraft.world.level.block.state.BlockState state =
                        level.m_8055_(origin.m_7918_(x, y, z));
                if (state.m_60734_() == want || isBuiltEquivalent(parts[3], state.m_60734_())) {
                    built.merge(parts[3], 1, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return built;
    }

    /**
     * 建造区域障碍物预检（v1.5.13）：检查每个目标格是否可建造。
     * - 空气 / 自然地形（ALLOWED_GROUND）→ 可建（覆盖）
     * - 已建好的目标方块（或等价族）→ 跳过（v1.5.25：续建时不再误判为障碍）
     * - 其他方块（树/建筑/箱子等）→ 障碍物，返回提示文本
     * 全部可建返回 null。
     * v1.5.180：level 版（不再依赖女仆实体——创建区块不需要女仆在场）。
     */
    public static String findObstacles(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        for (String step : steps) {
            String[] parts = parseStep(step);
            if (parts == null) {
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            BlockPos target = origin.m_7918_(x, y, z);
            net.minecraft.world.level.block.state.BlockState state = level.m_8055_(target);
            if (state.m_60795_()) {
                continue; // 空气
            }
            // v1.5.28：可替换方块（草/花/雪层/藤蔓/水等 canBeReplaced=true）→ 可建。
            // 旧版只认 ALLOWED_GROUND 自然地形，玩家"清空"后的草地残留短草/花
            // 会被误判为障碍物 → 换再多空间也提示"区域内有障碍物"（中式庭院无法建造根因）
            // v1.5.259：m_60815_ 是 isSolid——旧版写成 `if (m_60815_()) continue` 把
            // 实心方块全跳过（障碍检测失效）；意图"可替换（非实心）→ 跳过"
            if (!state.m_60815_()) {
                continue;
            }
            // v1.5.25：目标格已是蓝图要求方块（或地形等价族）→ 已建好，不算障碍
            // v1.5.156：建材等价不再算已建（远古城市 deepslate_bricks 不算石砖已建）
            Block want = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(parts[3]));
            if (want != null && (state.m_60734_() == want || isBuiltEquivalent(parts[3], state.m_60734_()))) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (id != null && ALLOWED_GROUND.contains(id.toString())) {
                continue; // 自然地形可覆盖
            }
            String name = id != null ? cnName(id.toString()) : "未知方块";
            // v1.5.84：以玩家所在那一格为参照的描述（建造原点 = 玩家脚下）
            return "这个地方有障碍物：" + name + "（" + target.m_123341_() + "," + target.m_123342_() + "," + target.m_123343_() + "）。请换一个开阔平坦的地方，或者清掉障碍物后再试。";
        }
        return null;
    }

    /**
     * v1.5.28：建造完成后自动开入口（外部蓝图普遍没有门的痛点）。
     * - 蓝图本身含门（door）→ 已有入口，跳过
     * - 否则在【主人所在方向】的外墙开 1x2 门洞：y = 地板上方 1~2 层，
     *   水平在墙面中央，沿法线方向打通至多 2 格（覆盖常见墙厚）。
     * - 只移除实心方块：门洞位置若是空气（雕像腿间、镂空建筑）→ 自然不开洞。
     */
    public static void carveEntrance(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                     List<String> plan, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        try {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 1; i < plan.size(); i++) {
                String[] parts = parseStep(plan.get(i));
                if (parts == null) {
                    continue;
                }
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                if (parts[3].contains("door")) {
                    return; // 蓝图自带门：已有入口
                }
                // v1.5.311：蓝图含红石组件 → 不开门洞（墙内红石布线不能被挖断；
                // 旧版只豁免 machine_ 前缀的内置机器，外部红石蓝图仍会被开洞切断）
                net.minecraft.world.level.block.Block pb = ForgeRegistries.BLOCKS.getValue(
                        net.minecraft.resources.ResourceLocation.parse(parts[3]));
                if (pb != null && isRedstoneRelevant(pb)) {
                    return;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            if (minX == Integer.MAX_VALUE) {
                return;
            }
            int doorY1 = minY + 1;
            int doorY2 = minY + 2;
            if (doorY2 > maxY) {
                return; // 建筑不足 2 层高，不用开门
            }
            // 主人相对建筑中心的方向 → 选墙
            double cx = origin.m_123341_() + (minX + maxX) / 2.0;
            double cz = origin.m_123343_() + (minZ + maxZ) / 2.0;
            double dx = 0.0, dz = 0.0;
            if (maid.m_269323_() != null) {
                dx = maid.m_269323_().m_20185_() - cx;
                dz = maid.m_269323_().m_20189_() - cz;
            }
            boolean xWall = Math.abs(dx) >= Math.abs(dz);
            boolean positive = xWall ? dx >= 0 : dz >= 0;
            int doorZ = xWall ? (minZ + maxZ) / 2 : (minX + maxX) / 2;
            Block air = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("minecraft:air"));
            if (air == null) {
                return;
            }
            // 从外向内打通 2 格深（墙厚 1~2 常见；内部若已是空气则自然停手）
            for (int d = 0; d < 2; d++) {
                for (int y = doorY1; y <= doorY2; y++) {
                    BlockPos target = xWall
                            ? origin.m_7918_(positive ? maxX - d : minX + d, y, doorZ)
                            : origin.m_7918_(doorZ, y, positive ? maxZ - d : minZ + d);
                    net.minecraft.world.level.block.state.BlockState st = level.m_8055_(target);
                    // v1.5.259：m_60815_ 是 isSolid——旧版当 isLiquid（非空气非液体→清空）
                    if (!st.m_60795_()
                            && !st.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                        level.m_7731_(target, air.m_49966_(), 3);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.5.33：识别世界中"蓝图已建部分"的原点（计划丢失/名字不匹配时的续建兜底）。
     * 思路：找蓝图中最稀有的目标方块 → 在女仆附近扫描该方块 → 每个命中作为候选
     * 原点基准 → 采样验证匹配率 → 返回最优原点；未找到返回 null。
     * 用途：重进游戏/计划丢失后重新下达蓝图，女仆能认出"这是之前建过的"并继续，
     * 而不是把已建建筑当障碍物拒绝。
     */
    public static BlockPos findExistingOrigin(net.minecraft.server.level.ServerLevel level,
                                              com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid,
                                              List<String> steps) {
        try {
            List<int[]> rel = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (String step : steps) {
                String[] parts = parseStep(step);
                if (parts == null) {
                    continue;
                }
                rel.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                ids.add(parts[3]);
            }
            if (rel.isEmpty()) {
                return null;
            }
            int mx = maid.m_20183_().m_123341_();
            int my = maid.m_20183_().m_123342_();
            int mz = maid.m_20183_().m_123343_();
            // v1.5.147b（用户方案）：先确认【区域内实际存在的方块种类】，再与蓝图
            // 比对取交集——锚点从"世界存在 ∩ 蓝图需要"的方块里选：缺料没建的方块
            // 根本不在世界里，不会当选锚点（瞭望塔 = 灯笼没建 → 自动落到石砖）。
            // 一次扫描同时收集种类与位置；交集方块按【世界出现次数】升序（世界越
            // 稀有优先，候选少、判定快），最多试 3 种，每种命中上限 256。
            java.util.Set<String> wantIds = new java.util.HashSet<>(ids);
            java.util.Map<String, java.util.List<int[]>> worldHits = new java.util.HashMap<>();
            for (int dy = -12; dy <= 12; dy++) {
                for (int dx = -48; dx <= 48; dx += 2) {
                    for (int dz = -48; dz <= 48; dz += 2) {
                        net.minecraft.world.level.block.state.BlockState st = level.m_8055_(
                                new BlockPos(mx + dx, my + dy, mz + dz));
                        // v1.5.259：m_60815_ 是 isSolid——旧版当 isLiquid（"空气/液体跳过"）
                        if (st.m_60795_()
                                || st.m_60819_().m_205070_(net.minecraft.tags.FluidTags.f_13131_)) {
                            continue; // 空气/液体跳过
                        }
                        net.minecraft.resources.ResourceLocation bid =
                                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.m_60734_());
                        if (bid == null || ALLOWED_GROUND.contains(bid.toString())) {
                            continue; // 自然地形跳过（只统计人工方块）
                        }
                        if (!blueprintWants(wantIds, bid.toString(), st.m_60734_())) {
                            continue; // 蓝图不需要的方块不收集
                        }
                        worldHits.computeIfAbsent(bid.toString(), k -> new java.util.ArrayList<>())
                                .add(new int[]{mx + dx, my + dy, mz + dz});
                    }
                }
            }
            java.util.List<String> candidates = new java.util.ArrayList<>(worldHits.keySet());
            candidates.sort(java.util.Comparator.comparingInt(k -> worldHits.get(k).size()));
            int anchorCount = Math.min(3, candidates.size());
            for (int a = 0; a < anchorCount; a++) {
                String anchorId = candidates.get(a);
                Block rareBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(anchorId));
                if (rareBlock == null) {
                    continue;
                }
                int anchorIdx = -1;
                for (int i = 0; i < ids.size(); i++) {
                    if (ids.get(i).equals(anchorId) || isBuiltEquivalent(ids.get(i), rareBlock)) {
                        anchorIdx = i;
                        break;
                    }
                }
                if (anchorIdx < 0) {
                    continue;
                }
                java.util.List<int[]> hits = worldHits.get(anchorId);
                if (hits.size() > 256) {
                    hits = new java.util.ArrayList<>(hits.subList(0, 256));
                }
            // v1.5.43：半成品判定——按 y 分层采样（半成品从底部建起，y-major 顺序下
            // 已建部分集中在低 y 层；旧版"全计划 30% 匹配率"对早期建筑（2/84 层 ≈ 2.4%）
            // 永远判不出来 → 回落新原点 → 把已建半成品当障碍）。采样预算 4096 按层均分，
            // 统计每层匹配率；判定：存在 ≥3 个连续层匹配率 ≥50%（空间相关性，随机命中
            // 概率极低），或 全局匹配数 ≥8 且匹配率 ≥10%
            java.util.Map<Integer, java.util.List<Integer>> byY = new java.util.TreeMap<>();
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int i = 0; i < rel.size(); i++) {
                int y = rel.get(i)[1];
                byY.computeIfAbsent(y, k -> new ArrayList<>()).add(i);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            int layerCount = Math.max(1, maxY - minY + 1);
            int budgetPerLayer = Math.max(4, 4096 / layerCount);
            java.util.List<java.util.List<Integer>> layerSamples = new ArrayList<>();
            for (int y = minY; y <= maxY; y++) {
                java.util.List<Integer> idx = byY.getOrDefault(y, new ArrayList<>());
                java.util.List<Integer> sample = new ArrayList<>();
                if (!idx.isEmpty()) {
                    int step = Math.max(1, idx.size() / budgetPerLayer);
                    for (int k = 0; k < idx.size(); k += step) {
                        sample.add(idx.get(k));
                    }
                }
                layerSamples.add(sample);
            }
            int[] rp = rel.get(anchorIdx);
            int[] best = null;
            int bestMatch = -1;
            for (int[] h : hits) {
                int ox = h[0] - rp[0];
                int oy = h[1] - rp[1];
                int oz = h[2] - rp[2];
                int matched = 0;
                int total = 0;
                int[] layerMatched = new int[layerCount];
                int[] layerTotal = new int[layerCount];
                for (int ly = 0; ly < layerCount; ly++) {
                    for (int idx : layerSamples.get(ly)) {
                        int[] p = rel.get(idx);
                        net.minecraft.world.level.block.state.BlockState st = level.m_8055_(
                                new BlockPos(ox + p[0], oy + p[1], oz + p[2]));
                        Block want = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(ids.get(idx)));
                        layerTotal[ly]++;
                        total++;
                        if (want != null && (st.m_60734_() == want || isBuiltEquivalent(ids.get(idx), st.m_60734_()))) {
                            layerMatched[ly]++;
                            matched++;
                        }
                    }
                }
                // 判定：连续 ≥3 层 ≥50% 或 全局 ≥8 且 ≥10%（候选内筛选，不再事后一刀切）
                boolean ok = (matched >= 8 && total > 0 && matched * 10 >= total)
                        || hasThreeConsecutiveLayers(layerMatched, layerTotal);
                if (ok && matched > bestMatch) {
                    bestMatch = matched;
                    best = new int[]{ox, oy, oz};
                }
            }
            if (best != null) {
                return new BlockPos(best[0], best[1], best[2]);
            }
            // 该锚点无匹配候选 → 换下一种锚点方块
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** v1.5.147b：蓝图是否需要该世界方块（精确 ID 或等价族匹配，如蓝图要求橡木木板、世界是云杉木板） */
    private static boolean blueprintWants(java.util.Set<String> wantIds, String worldId, Block worldBlock) {
        if (wantIds.contains(worldId)) {
            return true;
        }
        for (String w : wantIds) {
            if (isBuiltEquivalent(w, worldBlock)) {
                return true;
            }
        }
        return false;
    }

    /** v1.5.43：存在 ≥3 个连续层匹配率 ≥50%（每层样本 ≥2 个）——半成品的空间相关信号 */
    private static boolean hasThreeConsecutiveLayers(int[] layerMatched, int[] layerTotal) {
        int run = 0;
        for (int i = 0; i < layerTotal.length; i++) {
            if (layerTotal[i] >= 2 && layerMatched[i] * 2 >= layerTotal[i]) {
                run++;
                if (run >= 3) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }
}
