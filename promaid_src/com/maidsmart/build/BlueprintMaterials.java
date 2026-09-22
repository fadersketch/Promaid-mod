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
 * 材料统计与交付（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 按步骤列表统计需求、算主人/女仆背包缺口、把材料从主人转给女仆、
 * 消耗方块取物品形态。
 */
public final class BlueprintMaterials {
    private BlueprintMaterials() {
    }

    public static Map<String, Integer> countNeeds(List<String> steps) {
        Map<String, Integer> needed = new HashMap<>();
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
            if (parts != null) {
                String id = parts[3];
                if (BlueprintBlockData.FORBIDDEN.contains(id)) {
                    continue;
                }
                needed.merge(id, 1, Integer::sum);
            }
        }
        return needed;
    }

    public static Map<String, Integer> fluidBucketNeeds(List<String> steps) {
        Map<String, Integer> out = new HashMap<>();
        int water = 0;
        int lava = 0;
        int rails = 0;
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
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

    static final Map<String, Map<String, Integer>> NEEDS_CACHE = new HashMap<>();

    static final Map<String, Long> NEEDS_MTIME = new HashMap<>();

    public static Map<String, Integer> countNeedsCached(String id, List<String> steps) {
        if (id == null || steps == null || steps.isEmpty()) {
            return new HashMap<>();
        }
        Long mtime = BlueprintFileIo.EXTERNAL_MTIMES.get(id);
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

    public static boolean isCreative(Player player) {
        return player != null && player.m_150110_().f_35937_;
    }

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

    public static int countPlayerMaterial(Player player, String blockId) {
        if (player == null) {
            return 0;
        }
        if (isCreative(player)) {
            return Integer.MAX_VALUE; // 创造模式：任何材料视为备齐
        }
        Set<String> group = BlueprintLooseMatching.equivalentGroup(blockId);
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

    private static void transferFromPlayer(Player player, EntityMaid maid, String blockId, int count) {
        if (count <= 0) {
            return;
        }
        Set<String> group = BlueprintLooseMatching.equivalentGroup(blockId);
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
            ItemStack left = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            moved += take - left.m_41613_();
            // 审计1.5.385修复：旧版 back 公式把 left（女仆背包放不下的部分）计入，
            // 但 whole.split(back) 最多只能分出 whole 剩余（n-take）——left 直接
            // 凭空消失（注释声称"放不下的留在玩家原处"实际没有）。改为：
            // 剩余放回原槽，放不下的还回玩家背包，背包也满则掉落身侧。
            int back = n - take;
            if (back > 0) {
                inv.m_6836_(i, whole.m_255036_(back));
            }
            if (!left.m_41619_()) {
                ItemStack rest = ItemHandlerHelper.insertItemStacked(
                        new net.minecraftforge.items.wrapper.PlayerMainInvWrapper(inv), left, false);
                if (!rest.m_41619_()) {
                    net.minecraft.world.entity.item.ItemEntity ent = new net.minecraft.world.entity.item.ItemEntity(
                            player.m_9236_(), player.m_20185_(), player.m_20186_() + 0.5, player.m_20189_(), rest);
                    player.m_9236_().m_7967_(ent);
                }
            }
        }
    }

    public static int countMaterial(EntityMaid maid, String blockId) {
        Set<String> group = BlueprintLooseMatching.equivalentGroup(blockId);
        int count = 0;
        IItemHandler inv = maid.getAvailableBackpackInv();
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

    public static Item consumeBlock(EntityMaid maid, String blockId) {
        // v1.5.24：主人处于创造模式 → 材料视为无限，直接返回对应物品（不扣任何背包）
        if (maid.m_269323_() instanceof Player owner && isCreative(owner)) {
            // v1.5.287：itemForBlock（redstone_wire → 红石粉——否则创造也建不出线）
            Item exact = itemForBlock(blockId);
            if (exact != null) {
                return exact;
            }
            Set<String> cg = BlueprintLooseMatching.equivalentGroup(blockId);
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
            ItemStack taken = extractExact(maid.getAvailableBackpackInv(), lavaBucket, 1);
            if (!taken.m_41619_()) {
                return lavaBucket; // 已从女仆背包取走 1 岩浆桶
            }
            return null; // 调用方会尝试主人背包（tryTakeFromOwner）
        }
        Item item = itemForBlock(blockId);
        IItemHandler inv = maid.getAvailableBackpackInv();
        // 1. 精确匹配优先
        if (item != null) {
            ItemStack taken = extractExact(inv, item, 1);
            if (!taken.m_41619_()) {
                return item;
            }
        }
        // 2. 等价族内任意物品
        Set<String> group = BlueprintLooseMatching.equivalentGroup(blockId);
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
        // 顶坏建筑（需求："1 格只能用 1 格替换，半格两格同理"）
        // v1.5.275：两格再分竖/横（门/高植物 ↔ 床），无碰撞方块单独表
        if (com.maidsmart.config.MaidSmartConfig.BUILD_ALT_ENABLED.get()) {
            Block targetBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(blockId));
            List<String> alts = targetBlock == null ? BlueprintBlockTraits.altBlocks()
                    : BlueprintBlockTraits.isSlabHeight(targetBlock) ? BlueprintBlockTraits.altSlabs()
                    : BlueprintBlockTraits.isTallVertical(targetBlock) ? BlueprintBlockTraits.altTalls()
                    : BlueprintBlockTraits.isWideHeight(targetBlock) ? BlueprintBlockTraits.altWides()
                    : BlueprintBlockTraits.isNoClip(targetBlock) ? BlueprintBlockTraits.altNoClips()
                    : BlueprintBlockTraits.altBlocks();
            // v1.5.279：同族优先——第一轮只扫与目标同材质族的替代品，第二轮才扫
            // 其余（多维度划分的落地：高度/碰撞严格匹配之上，材质族作为优先序）
            String fam = BlueprintBlockTraits.materialFamily(targetBlock);
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
                        if (BlueprintBlockTraits.isSlabHeight(targetBlock) || BlueprintBlockTraits.isSlabHeight(altBlock)) {
                            match = BlueprintBlockTraits.isSlabHeight(targetBlock) && BlueprintBlockTraits.isSlabHeight(altBlock);
                        } else if (BlueprintBlockTraits.isTallVertical(targetBlock) || BlueprintBlockTraits.isTallVertical(altBlock)) {
                            match = BlueprintBlockTraits.isTallVertical(targetBlock) && BlueprintBlockTraits.isTallVertical(altBlock);
                        } else if (BlueprintBlockTraits.isWideHeight(targetBlock) || BlueprintBlockTraits.isWideHeight(altBlock)) {
                            match = BlueprintBlockTraits.isWideHeight(targetBlock) && BlueprintBlockTraits.isWideHeight(altBlock);
                        } else if (BlueprintBlockTraits.isNoClip(targetBlock) || BlueprintBlockTraits.isNoClip(altBlock)) {
                            match = BlueprintBlockTraits.isNoClip(targetBlock) && BlueprintBlockTraits.isNoClip(altBlock);
                        } else {
                            match = true;
                        }
                        if (!match) {
                            continue;
                        }
                    }
                    if (pass == 0 && (fam == null || !fam.equals(BlueprintBlockTraits.materialFamily(altBlock)))) {
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

    public static String itemIdForBlock(String blockId) {
        String ov = BLOCK_ITEM_OVERRIDES.get(blockId);
        if (ov != null) {
            return ov;
        }
        if (hasItemForm(blockId)) {
            return blockId;
        }
        String stripped = stripWallToken(blockId);
        if (stripped != null && hasItemForm(stripped)) {
            return stripped;
        }
        return blockId;
    }

    private static boolean hasItemForm(String itemId) {
        Item it = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(itemId));
        return it != null && it != net.minecraft.world.item.Items.f_41852_;
    }

    private static String stripWallToken(String blockId) {
        if (blockId == null) {
            return null;
        }
        int ns = blockId.indexOf(':');
        String path = ns >= 0 ? blockId.substring(ns + 1) : blockId;
        int at = path.indexOf("wall_");
        if (at < 0) {
            return null;
        }
        String stripped = path.substring(0, at) + path.substring(at + "wall_".length());
        if (stripped.isEmpty()) {
            return null;
        }
        return ns >= 0 ? blockId.substring(0, ns + 1) + stripped : stripped;
    }

    public static Item itemForBlock(String blockId) {
        String id = itemIdForBlock(blockId);
        if (id == null) {
            return null;
        }
        Item it = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        return it == null || it == net.minecraft.world.item.Items.f_41852_ ? null : it;
    }

    public static String cnItemName(String blockId) {
        return BlueprintNames.cnName(itemIdForBlock(blockId));
    }

    static ItemStack extractExact(IItemHandler inv, Item item, int count) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                return inv.extractItem(i, count, false);
            }
        }
        return ItemStack.f_41583_;
    }

    private static boolean hasBucketTool(EntityMaid maid, String bucketItemId) {
        Item bucket = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.parse(bucketItemId));
        if (bucket == null) {
            return false;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
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
                maid.getAvailableBackpackInv(), bucketStack, false);
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
}
