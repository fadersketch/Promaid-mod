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
 * 宽松材料替代与等价族合并（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * equivalentGroup 把「严格等价组」与「宽松桶」取并集；looseBucket 按物品 id 归桶。
 */
public final class BlueprintLooseMatching {
    private BlueprintLooseMatching() {
    }

    private static volatile String materialScope = null;

    public static void setMaterialScope(String blueprintId) {
        materialScope = blueprintId;
    }

    public static Set<String> equivalentGroup(String blockId) {
        Set<String> base = BlueprintBlockData.EQUIVALENT_GROUPS.get(blockId);
        Set<String> wide = looseGroup(blockId);
        if (wide == null || wide.isEmpty()) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return wide;
        }
        Set<String> union = new HashSet<>(base);
        union.addAll(wide);
        return union;
    }

    public static boolean isMachineForMaterials(String id) {
        if (id == null) {
            return false;
        }
        if (id.startsWith("maid_smart:machine_") || BlueprintMachineFinish.machineFamily(id) != null) {
            return true;
        }
        return Boolean.TRUE.equals(BlueprintMachineDetect.MACHINE_BY_CONTENT.get(id));
    }

    private static boolean looseAllowed() {
        String mode = com.maidsmart.config.MaidSmartConfig.BUILD_LOOSE_MATERIALS.get();
        if ("always".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("machine".equalsIgnoreCase(mode)) {
            String scope = materialScope;
            return scope != null && isMachineForMaterials(scope);
        }
        return false;
    }

    private static final Set<String> NOT_RELAXED_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logNotRelaxed(String blockId, String bucket) {
        try {
            if (!"machine".equalsIgnoreCase(
                    com.maidsmart.config.MaidSmartConfig.BUILD_LOOSE_MATERIALS.get())) {
                return; // off / always 两种档位不需要解释
            }
            String scope = materialScope;
            if (scope == null || bucket == null) {
                return;
            }
            if (!NOT_RELAXED_LOGGED.add(scope + "|" + bucket)) {
                return;
            }
            String desc = BlueprintMachineDetect.MACHINE_CONTENT_DESC.getOrDefault(scope, "未记录");
            BlueprintLib.LOGGER.info("缺料同类宽松：图纸 {} 判为【非机器】→ 不放宽同类材料（{} 桶缺色就照缺色报）。"
                            + "内容判据：{}；要放宽请把文件名加上机器关键词（{}），"
                            + "或把配置 build.looseMaterials 改成 always",
                    scope, bucket, desc, BlueprintMachineDetect.machineKeywordHint());
        } catch (Throwable ignored) {
        }
    }

    private static final Map<String, Set<String>> LOOSE_BUCKETS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Set<String> looseGroup(String blockId) {
        if (!looseAllowed()) {
            logNotRelaxed(blockId, looseBucket(BlueprintMaterials.itemIdForBlock(blockId)));
            return null;
        }
        String bucket = looseBucket(BlueprintMaterials.itemIdForBlock(blockId));
        if (bucket == null) {
            return null;
        }
        Set<String> cached = LOOSE_BUCKETS.get(bucket);
        if (cached != null) {
            return cached;
        }
        Set<String> set = new HashSet<>();
        for (ResourceLocation key : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            if (bucket.equals(looseBucket(key.toString()))) {
                set.add(key.toString());
            }
        }
        LOOSE_BUCKETS.put(bucket, set);
        return set;
    }

    private static String looseBucket(String itemId) {
        if (itemId == null) {
            return null;
        }
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        if (path.endsWith("_wall_hanging_sign") || path.endsWith("_hanging_sign")) {
            return "hanging_sign";
        }
        if (path.endsWith("_wall_sign") || path.endsWith("_sign")) {
            return "sign";
        }
        if (path.endsWith("_leaves")) {
            return "leaves";
        }
        if (path.endsWith("_wool")) {
            return "wool";
        }
        if (path.endsWith("_carpet")) {
            return "carpet";
        }
        if (path.endsWith("_stained_glass_pane")) {
            return "stained_glass_pane";
        }
        if (path.endsWith("_stained_glass")) {
            return "stained_glass";
        }
        return null;
    }
}
