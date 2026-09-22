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
 * 机器蓝图识别与流体流水记录（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 按部件关键词/结构件关键词判断「这是不是机器」，决定是否保留流体，
 * 以及材料替代时记录被剥掉的流体。
 */
public final class BlueprintMachineDetect {
    private BlueprintMachineDetect() {
    }

    private static final String[] MACHINE_PART_KEYS = {
            "piston", "observer", "repeater", "comparator", "dispenser", "dropper", "hopper",
            "redstone_torch", "redstone_wire", "redstone_block", "redstone_lamp", "lever",
            "target", "note_block", "detector_rail", "powered_rail", "tripwire_hook",
            "daylight_detector", "trapped_chest", "slime_block", "honey_block", "soul_sand"};

    private static final String[] MACHINE_STRUCT_KEYS = {
            "magma_block", "ice", "packed_ice", "blue_ice", "soul_sand", "soul_soil",
            "bubble_column", "anvil", "stonecutter", "composter", "cauldron", "scaffolding"};

    public static String machineKeywordHint() {
        return "村民 / 分类机 / 打包机 / 仓库 / 南瓜 / 甘蔗 / 铁砧 / 轰炸机";
    }

    static final Map<String, Boolean> MACHINE_BY_CONTENT = new java.util.concurrent.ConcurrentHashMap<>();

    static final Map<String, String> MACHINE_CONTENT_DESC = new java.util.concurrent.ConcurrentHashMap<>();

    static void recordMachineByContent(String blueprintId, net.minecraft.nbt.CompoundTag tag) {
        if (blueprintId == null) {
            return;
        }
        MachineLook look = scanMachineParts(tag);
        MACHINE_BY_CONTENT.put(blueprintId, look.machine());
        MACHINE_CONTENT_DESC.put(blueprintId, look.describe());
    }

    private record MachineLook(java.util.Set<String> parts, java.util.Set<String> structs) {
        boolean machine() {
            // 三条任一即算机器：红石/物流件 >= 3 种（原口径）；
            // 红石件 >= 1 且 结构件 >= 1（史莱姆农场 = 漏斗 + 岩浆块）；
            // 结构件 >= 2（两种以上的结构件，如 冰 + 浮冰 / 蓝冰）。
            // 注意"冰 + 水"数不到 2 —— 水不在任何一栏，见 MACHINE_STRUCT_KEYS 头上的边界说明
            return parts.size() >= 3 || (parts.size() >= 1 && structs.size() >= 1) || structs.size() >= 2;
        }

        String describe() {
            return "红石/物流件 " + parts.size() + " 种" + (parts.isEmpty() ? "" : "（" + String.join("、", parts) + "）")
                    + " / 结构件 " + structs.size() + " 种" + (structs.isEmpty() ? "" : "（" + String.join("、", structs) + "）");
        }
    }

    private static MachineLook scanMachineParts(net.minecraft.nbt.CompoundTag tag) {
        java.util.Set<String> parts = new java.util.LinkedHashSet<>();
        java.util.Set<String> structs = new java.util.LinkedHashSet<>();
        try {
            net.minecraft.nbt.ListTag palette = tag.m_128425_("palettes", 9)
                    ? tag.m_128437_("palettes", 9).m_128744_(0)
                    : tag.m_128437_("palette", 10);
            if (palette == null) {
                return new MachineLook(parts, structs);
            }
            for (int i = 0; i < palette.size(); i++) {
                String nm = palette.m_128728_(i).m_128461_("Name");
                if (nm == null) {
                    continue;
                }
                String path = nm.contains(":") ? nm.substring(nm.indexOf(':') + 1) : nm;
                if (matchesAny(path, MACHINE_PART_KEYS)) {
                    parts.add(path);
                } else if (matchesAny(path, MACHINE_STRUCT_KEYS)) {
                    structs.add(path);
                }
            }
        } catch (Exception ignored) {
        }
        return new MachineLook(parts, structs);
    }

    private static boolean matchesAny(String path, String[] keys) {
        for (String k : keys) {
            if (path.equals(k) || path.endsWith("_" + k)) {
                return true;
            }
        }
        return false;
    }

    public static boolean keepFluidsFor(net.minecraft.nbt.CompoundTag tag, String stem) {
        String mode = com.maidsmart.config.MaidSmartConfig.BUILD_KEEP_FLUIDS.get();
        if ("always".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("never".equalsIgnoreCase(mode)) {
            return false;
        }
        if (stem != null && BlueprintMachineFinish.machineFamily(stem) != null) {
            return true;
        }
        return looksLikeMachine(tag);
    }

    private static boolean looksLikeMachine(net.minecraft.nbt.CompoundTag tag) {
        return scanMachineParts(tag).machine();
    }

    private static final Map<String, String> FLUID_STRIPPED = new java.util.concurrent.ConcurrentHashMap<>();

    static void recordFluidStrip(String blueprintId, net.minecraft.nbt.CompoundTag tag, String fname) {
        try {
            int water = 0;
            int lava = 0;
            net.minecraft.nbt.ListTag palette = tag.m_128425_("palettes", 9)
                    ? tag.m_128437_("palettes", 9).m_128744_(0)
                    : tag.m_128437_("palette", 10);
            net.minecraft.nbt.ListTag cells = tag.m_128437_("blocks", 10);
            if (palette == null) {
                return;
            }
            for (net.minecraft.nbt.Tag t : cells) {
                int si = ((net.minecraft.nbt.CompoundTag) t).m_128451_("state");
                if (si < 0 || si >= palette.size()) {
                    continue;
                }
                String nm = palette.m_128728_(si).m_128461_("Name");
                if ("minecraft:water".equals(nm)) {
                    water++;
                } else if ("minecraft:lava".equals(nm)) {
                    lava++;
                }
            }
            if (water == 0 && lava == 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (water > 0) {
                sb.append("水×").append(water);
            }
            if (lava > 0) {
                sb.append(sb.length() > 0 ? "、" : "").append("岩浆×").append(lava);
            }
            FLUID_STRIPPED.put(blueprintId, sb.toString());
            BlueprintLib.LOGGER.info("loadExternalFile: {} 含流体（{} 格）已按「普通建筑」剥离（内容判据：{}）——"
                            + "需要流体的机器图纸请把文件名加上机器关键词（{}），"
                            + "或把配置 build.keepFluids 改成 always",
                    fname, sb, scanMachineParts(tag).describe(), machineKeywordHint());
        } catch (Exception ignored) {
        }
    }

    public static String fluidStripWarning(String blueprintId) {
        String s = blueprintId == null ? null : FLUID_STRIPPED.get(blueprintId);
        if (s == null) {
            return "";
        }
        return "\u00a7e注意：这份图纸原本含 " + s + "，已按「普通建筑」处理并剥离（不会建水/岩浆）。"
                + "如果它是靠流体工作的机器：把文件名加上关键词（" + machineKeywordHint()
                + "）后重新导入，或到「模组详细配置 → 建造 → 图纸流体保留」改成「always」再重建。";
    }
}
