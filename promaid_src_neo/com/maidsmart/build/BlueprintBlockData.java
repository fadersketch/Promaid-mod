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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 蓝图方块分类表（v1.2.4 从 BlueprintLib 拆出：纯数据 + 判定）。
 * 
 * 黑名单 FORBIDDEN（结构文件不可放）、不可拆 UNBREAKABLE、地形方块 TERRAIN_BLOCKS、
 * 可覆盖自然地形 ALLOWED_GROUND、建筑白名单 WHITELIST、等价材料族
 * EQUIVALENT_GROUPS / BUILT_EQUIV_GROUPS。表内容与拆分前逐字一致。
 */
public final class BlueprintBlockData {
    private BlueprintBlockData() {
    }

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

    public static final Set<String> UNBREAKABLE = new HashSet<>();

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

    public static boolean canBreak(Block block) {
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            return false;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return id != null && !UNBREAKABLE.contains(id.toString());
    }

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

    private static final Set<String> BUILT_EQUIV_GROUPS = Set.of("minecraft:dirt", "minecraft:grass_block");

    public static boolean isBuiltEquivalent(String blockId, Block actual) {
        if (!BUILT_EQUIV_GROUPS.contains(blockId)) {
            return false;
        }
        return isEquivalent(blockId, actual);
    }

    public static boolean isEquivalent(String blockId, Block actual) {
        if (actual == null) {
            return false;
        }
        ResourceLocation actualId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual);
        if (actualId == null) {
            return false;
        }
        Set<String> group = BlueprintLooseMatching.equivalentGroup(blockId);
        return group != null && group.contains(actualId.toString());
    }

    public static boolean isAllowedGround(net.minecraft.world.level.block.state.BlockState state) {
        if (state.isAir()) {
            return true;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && ALLOWED_GROUND.contains(id.toString());
    }
}
