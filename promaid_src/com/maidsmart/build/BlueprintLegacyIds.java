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
 * 旧版结构文件兼容层（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * ① 1.12 及更早的「数字 id + meta」方块表（legacyBlockState / mapSchematicBlock /
 *    applyLegacyData）与 .schematic / 老 .schem 的调色板映射；
 * ② 方块状态串的读写（paletteStateString / parseStateString）与朝向名解析。
 * 纯查表 + 字符串拼装，无副作用。
 */
public final class BlueprintLegacyIds {
    private BlueprintLegacyIds() {
    }

    static String paletteStateString(net.minecraft.nbt.CompoundTag ps) {
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

    private static final String[] LEGACY_FACINGS = {"south", "west", "north", "east"};

    private static final String[] LEGACY_TORCH_FACINGS = {"north", "east", "west", "south", "north"};

    private static final String[] LEGACY_WALL_FACINGS = {"north", "north", "north", "south", "west", "east"};

    private static final String[] LEGACY_DOOR_FACINGS = {"east", "north", "west", "south"};

    private static final String[] LEGACY_AXIS = {"y", "x", "z"};

    private static final String[] LEGACY_RAIL_SHAPES = {"north_south", "east_west", "ascending_east",
            "ascending_west", "ascending_north", "ascending_south", "south_east", "south_west",
            "north_west", "north_east"};

    private static final String[] LEGACY_PISTON_FACINGS = {"down", "up", "north", "south", "west", "east"};

    private static String legacySnbt(String v) {
        return "\"" + v + "\"";
    }

    private static String legacyStairsState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",half:" + legacySnbt((meta & 4) != 0 ? "top" : "bottom") + "}";
    }

    private static String legacySlabState(String block, int meta, boolean hasHalf) {
        String type = hasHalf ? ((meta & 8) != 0 ? "top" : "bottom") : "double";
        return block + "{type:" + legacySnbt(type) + "}";
    }

    private static String legacyDoorState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_DOOR_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false") + "}";
    }

    private static String legacyTrapdoorState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false")
                + ",half:" + legacySnbt((meta & 8) != 0 ? "top" : "bottom") + "}";
    }

    private static String legacyGateState(String block, int meta) {
        return block + "{facing:" + legacySnbt(LEGACY_FACINGS[meta & 3])
                + ",open:" + legacySnbt((meta & 4) != 0 ? "true" : "false") + "}";
    }

    private static String legacyColoredBlock(String stem, int meta) {
        return "minecraft:" + LEGACY_COLORS[meta & 15] + "_" + stem;
    }

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

    static String legacyBlockState(int id, int meta) {
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

    static void applyLegacyData(int id, int data, net.minecraft.nbt.CompoundTag st) {
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

    static net.minecraft.core.Direction dirByName(String name) {        return switch (name) {
            case "north" -> net.minecraft.core.Direction.NORTH;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "east" -> net.minecraft.core.Direction.EAST;
            case "west" -> net.minecraft.core.Direction.WEST;
            case "up" -> net.minecraft.core.Direction.UP;
            case "down" -> net.minecraft.core.Direction.DOWN;
            default -> null;
        };
    }

    static String mapSchematicBlock(int id, int data) {
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

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow",
            "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    static net.minecraft.nbt.CompoundTag parseStateString(String s) {
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
}
