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
 * 方块形状 / 材质族判定（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 半砖/高矮/宽窄/无碰撞判定、材质族名、方块功能名、台阶替代表——
 * 材料替代与摆放选位的依据。
 */
public final class BlueprintBlockTraits {
    private BlueprintBlockTraits() {
    }

    public static boolean isSlabHeight(Block block) {
        return block instanceof net.minecraft.world.level.block.SlabBlock;
    }

    public static boolean isTallHeight(Block block) {
        return isTallVertical(block) || isWideHeight(block);
    }

    public static boolean isTallVertical(Block block) {
        return block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof net.minecraft.world.level.block.DoublePlantBlock
                || block instanceof net.minecraft.world.level.block.SugarCaneBlock
                || block instanceof net.minecraft.world.level.block.BambooStalkBlock;
    }

    public static boolean isWideHeight(Block block) {
        return block instanceof net.minecraft.world.level.block.BedBlock;
    }

    public static boolean isNoClip(Block block) {
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return false; // 液体不算
        }
        return !st.canOcclude(); // isSolid false = 无碰撞箱
    }

    public static String materialFamily(Block block) {
        if (block == null) {
            return "\u4ed6"; // 他
        }
        // v1.5.284：getKey 判空——未注册方块（外部蓝图/未装 mod 的方块）不 NPE
        net.minecraft.resources.ResourceLocation key0 = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
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
        net.minecraft.resources.ResourceLocation key1 = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
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

    public static List<String> altWides() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_WIDES.get());
    }

    public static List<String> altNoClips() {
        return new ArrayList<>(com.maidsmart.config.MaidSmartConfig.BUILD_ALT_NOCLIPS.get());
    }

    public static net.minecraft.world.level.block.state.BlockState parseStepState(
            net.minecraft.server.level.ServerLevel level, Block placed, String stateSnbt) {
        if (stateSnbt == null) {
            return null;
        }
        try {
            net.minecraft.nbt.CompoundTag stateTag = net.minecraft.nbt.NbtUtils.snbtToStructure(stateSnbt);
            if (!stateTag.contains("Name", 8)) {
                // v1.5.371：state-only SNBT（{facing:"south",lit:"false"}）→ 把 Name 之外
                // 的属性【包进 Properties 子标签】——旧版只补 Name，facing/half/shape 等
                // 全挂在顶层，readBlockState(readBlockState) 只读 Properties → 全部回落默认态
                // （朝向全朝北/朝下）——机器/门/床/楼梯朝向全错的根因（"机器都用不了"）。
                net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
                for (String key : stateTag.getAllKeys()) {
                    if ("Name".equals(key)) {
                        continue;
                    }
                    net.minecraft.nbt.Tag v = stateTag.get(key);
                    if (v != null) {
                        props.put(key, v);
                    }
                }
                net.minecraft.nbt.CompoundTag wrapped = new net.minecraft.nbt.CompoundTag();
                ResourceLocation rid = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(placed);
                if (rid != null) {
                    wrapped.putString("Name", rid.toString());
                }
                wrapped.put("Properties", props);
                stateTag = wrapped;
            }
            net.minecraft.world.level.block.state.BlockState parsed = net.minecraft.nbt.NbtUtils.readBlockState(
                    level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), stateTag);
            return parsed != null && !parsed.isAir() ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }
}
