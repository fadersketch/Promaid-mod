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
 * 结构文件 → 统一 NBT 的格式解码（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * litematic / .schem / .schematic 三种格式各自解成规范化的 CompoundTag，
 * 再由 BlueprintLib.parseStructure 走同一套步骤生成逻辑。
 */
public final class BlueprintStructureCodec {
    private BlueprintStructureCodec() {
    }

    static volatile int noItemCells = 0;

    /**
     * 「二次半块」= 门的上半扇。放置下半扇后由放置侧补全（{@code ensureDoorUpper}），
     * 蓝图里的上半扇步骤直接跳过——**一件门物品只对应两格**，两扇都从计划里放会吞两份料。
     *
     * <p>【v1.3.0 实测八百一十五：床不再走这里】旧版这条还顺带把 {@code part=head}
     * 的床也当二次半块跳过（注释写着"MC 自动补全"），可床根本没有补全的代码——
     * 于是蓝图只落下了 foot 半扇，而原版床的掉落表只在 {@code part=head} 时才给物品
     * （字节码/掉落表实证）→ 玩家看到的"床只剩半张、挖了也不掉"。床现在由
     * {@link BlueprintLib#parseStructure} 按"配对半扇在不在计划里"单独判，
     * 见 {@link #isBedHead}。
     */
    static boolean isSecondaryHalf(net.minecraft.nbt.CompoundTag stateTag) {
        try {
            if (stateTag.contains("Properties", 10)) {
                return "upper".equals(stateTag.getCompound("Properties").getString("half"));
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
        }
        return false;
    }

    /** 是不是床方块（注册名以 {@code _bed} 结尾）。 */
    static boolean isBedState(net.minecraft.nbt.CompoundTag stateTag) {
        try {
            return stateTag.getString("Name").endsWith("_bed");
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
            return false;
        }
    }

    /** 床的 part 属性（{@code head} / {@code foot}；读不到返回空串）。 */
    static String bedPart(net.minecraft.nbt.CompoundTag stateTag) {
        try {
            return stateTag.getCompound("Properties").getString("part");
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
            return "";
        }
    }

    /** 床的朝向（facing 属性字符串；读不到返回空串）。头/脚配对关系：
     *  {@code foot.relative(facing) == head}（原版 {@code getNeighbourDirection} 字节码实证）。 */
    static String bedFacing(net.minecraft.nbt.CompoundTag stateTag) {
        try {
            return stateTag.getCompound("Properties").getString("facing");
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
            return "";
        }
    }

    /** 计划里所有「床脚」（{@code part=foot}）的位置键——**原始坐标**（旋转前）。
     *  用来判定某个床头的配对半扇在不在计划里（旋转是等距变换，配对关系不变）。 */
    static java.util.Set<Long> bedFootKeys(net.minecraft.nbt.ListTag blocks,
                                           java.util.List<net.minecraft.nbt.CompoundTag> palette) {
        java.util.Set<Long> out = new HashSet<>();
        try {
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag cell = blocks.getCompound(i);
                int si = cell.getInt("state");
                if (si < 0 || si >= palette.size()) {
                    continue;
                }
                net.minecraft.nbt.CompoundTag st = palette.get(si);
                if (!isBedState(st) || !"foot".equals(bedPart(st))) {
                    continue;
                }
                net.minecraft.nbt.ListTag pos = cell.getList("pos", 3);
                out.add(key3(pos.getInt(0), pos.getInt(1), pos.getInt(2)));
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
        }
        return out;
    }

    /**
     * 这一格床是不是「多余的半扇」——配对的另一半也在计划里，只该保留一格。
     *
     * <p>规则：**留 foot**（与玩家摆放同序——先落脚、{@code setPlacedBy} 补头），
     * {@code head} 只有在"配对 foot 不在计划里"时才保留。放置侧会为留下的那一格
     * 补全配对半扇（{@code ensureBedHalf}），所以一件床物品仍然只占一格计划。
     */
    static boolean isRedundantBedHalf(net.minecraft.nbt.CompoundTag stateTag, int x, int y, int z,
                                      java.util.Set<Long> footKeys) {
        try {
            if (!isBedState(stateTag) || !"head".equals(bedPart(stateTag))) {
                return false; // foot 一律保留
            }
            net.minecraft.core.Direction f = BlueprintLegacyIds.dirByName(bedFacing(stateTag));
            if (f == null) {
                return false; // 朝向读不到 → 保守保留（放置侧补全自会兜底）
            }
            // head 的配对 foot 在 facing 的反方向
            net.minecraft.core.BlockPos paired =
                    new net.minecraft.core.BlockPos(x, y, z).relative(f.getOpposite());
            return footKeys.contains(key3(paired.getX(), paired.getY(), paired.getZ()));
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("BlueprintStructureCodec", ignored);
            return false;
        }
    }

    static net.minecraft.nbt.CompoundTag fromLitematic(net.minecraft.nbt.CompoundTag root) {
        net.minecraft.nbt.CompoundTag regions = root.getCompound("Regions");
        if (regions.isEmpty()) {            throw new IllegalArgumentException("litematic has no regions");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        java.util.List<net.minecraft.nbt.CompoundTag> regionTags = new ArrayList<>();
        for (String key : regions.getAllKeys()) {
            net.minecraft.nbt.CompoundTag region = regions.getCompound(key);
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
            net.minecraft.nbt.ListTag regionPalette = region.getList("BlockStatePalette", 10);
            boolean[] isAir = new boolean[regionPalette.size()];
            for (int i = 0; i < regionPalette.size(); i++) {
                net.minecraft.nbt.CompoundTag entry = regionPalette.getCompound(i);
                isAir[i] = entry.getString("Name").endsWith("air");
                palette.add(entry.copy());
            }
            int[] min = litematicRegionMin(region);
            int[] abs = litematicRegionAbsSize(region);
            long[] packed = region.getLongArray("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, regionPalette.size() - 1)));
            java.util.Map<Long, net.minecraft.nbt.CompoundTag> regionData = new HashMap<>();
            for (net.minecraft.nbt.Tag t : region.getList("TileEntities", 10)) {
                net.minecraft.nbt.CompoundTag be = ((net.minecraft.nbt.CompoundTag) t).copy();
                long key = key3(be.getInt("x"), be.getInt("y"), be.getInt("z"));
                be.remove("x");
                be.remove("y");
                be.remove("z");
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
        net.minecraft.nbt.CompoundTag pos = region.getCompound("Position");
        net.minecraft.nbt.CompoundTag size = region.getCompound("Size");
        return new int[]{pos.getInt("x") + Math.min(0, size.getInt("x") + 1),
                pos.getInt("y") + Math.min(0, size.getInt("y") + 1),
                pos.getInt("z") + Math.min(0, size.getInt("z") + 1)};
    }

    private static int[] litematicRegionAbsSize(net.minecraft.nbt.CompoundTag region) {
        net.minecraft.nbt.CompoundTag size = region.getCompound("Size");
        return new int[]{Math.abs(size.getInt("x")), Math.abs(size.getInt("y")), Math.abs(size.getInt("z"))};
    }

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

    static net.minecraft.nbt.CompoundTag fromSchem(net.minecraft.nbt.CompoundTag root) {
        if (root.contains("Schematic", 10)) {
            root = root.getCompound("Schematic");
        }
        net.minecraft.nbt.CompoundTag blocksHolder = root.contains("Blocks", 10) ? root.getCompound("Blocks") : root;
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IllegalArgumentException("schem has zero dimension");
        }
        if ((long) width * height * length > com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()) {
            throw new IllegalArgumentException("schem too large: " + width + "x" + height + "x" + length);
        }
        java.util.Map<Long, net.minecraft.nbt.CompoundTag> beData = new HashMap<>();
        net.minecraft.nbt.ListTag beList = blocksHolder.contains("BlockEntities", 9)
                ? blocksHolder.getList("BlockEntities", 10) : root.getList("BlockEntities", 10);
        for (net.minecraft.nbt.Tag t : beList) {
            net.minecraft.nbt.CompoundTag be = (net.minecraft.nbt.CompoundTag) t;
            int[] at = be.getIntArray("Pos");
            if (at.length != 3) {
                continue;
            }
            net.minecraft.nbt.CompoundTag data = be.contains("Data", 10) ? be.getCompound("Data").copy() : be.copy();
            data.remove("Pos");
            data.remove("Id");
            if (be.contains("Id", 8)) {
                data.putString("id", be.getString("Id"));
            }
            beData.put(key3(at[0], at[1], at[2]), data);
        }
        net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag paletteMap = blocksHolder.getCompound("Palette");
        int maxId = -1;
        for (String key : paletteMap.getAllKeys()) {
            maxId = Math.max(maxId, paletteMap.getInt(key));
        }
        net.minecraft.nbt.CompoundTag[] byId = new net.minecraft.nbt.CompoundTag[maxId + 1];
        boolean[] isAir = new boolean[maxId + 1];
        for (String key : paletteMap.getAllKeys()) {
            int id = paletteMap.getInt(key);
            byId[id] = BlueprintLegacyIds.parseStateString(key);
            isAir[id] = byId[id].getString("Name").endsWith("air");
        }
        net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
        int[] remap = new int[maxId + 1];
        for (int id = 0; id <= maxId; id++) {
            remap[id] = palette.size();
            if (byId[id] != null) {
                palette.add(byId[id]);
            }
        }
        byte[] data = blocksHolder.contains("Data", 7) ? blocksHolder.getByteArray("Data") : blocksHolder.getByteArray("BlockData");
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

    static net.minecraft.nbt.CompoundTag fromSchematic(net.minecraft.nbt.CompoundTag root) {
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IllegalArgumentException("schematic has zero dimension");
        }
        if ((long) width * height * length > com.maidsmart.config.MaidSmartConfig.BUILD_STRUCTURE_MAX_VOLUME.get()) {
            throw new IllegalArgumentException("schematic too large: " + width + "x" + height + "x" + length);
        }
        byte[] blocks = root.getByteArray("Blocks");
        byte[] data = root.contains("Data", 7) ? root.getByteArray("Data") : new byte[0];
        java.util.List<net.minecraft.nbt.CompoundTag> palette = new ArrayList<>();
        java.util.Map<String, Integer> palIdx = new HashMap<>();
        net.minecraft.nbt.ListTag cellList = new net.minecraft.nbt.ListTag();
        long volume = (long) width * height * length;
        for (long i = 0L; i < volume && i < blocks.length; i++) {
            int id = blocks[(int) i] & 0xFF;
            int dv = i < data.length ? (data[(int) i] & 0xFF) : 0;
            String name = BlueprintLegacyIds.mapSchematicBlock(id, dv);
            if (name == null) {
                continue; // 空气/液体/基岩/无物品等：跳过
            }
            Integer idx = palIdx.get(name + "|" + dv);
            if (idx == null) {
                net.minecraft.nbt.CompoundTag st = new net.minecraft.nbt.CompoundTag();
                st.putString("Name", name);
                // v1.5.45：旧版 data 值 → 1.13+ 方块状态（火把/按钮/拉杆朝向等）——
                // 旧版 mapSchematicBlock 丢弃 data → 全部默认朝上/朝北 → 悬空火把/拉杆
                BlueprintLegacyIds.applyLegacyData(id, dv, st);
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

    private static long key3(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (long) (z & 0x1FFFFF);
    }

    private static net.minecraft.nbt.CompoundTag litematicCell(int x, int y, int z, int stateIndex,
                                                               net.minecraft.nbt.CompoundTag data) {
        net.minecraft.nbt.CompoundTag cell = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag pos = new net.minecraft.nbt.ListTag();
        pos.add(net.minecraft.nbt.IntTag.valueOf(x));
        pos.add(net.minecraft.nbt.IntTag.valueOf(y));
        pos.add(net.minecraft.nbt.IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", stateIndex);
        if (data != null && !data.isEmpty()) {
            cell.put("nbt", data);
        }
        return cell;
    }

    private static net.minecraft.nbt.CompoundTag assembleStructure(int sx, int sy, int sz,
                                                                   net.minecraft.nbt.ListTag palette,
                                                                   net.minecraft.nbt.ListTag blocks,
                                                                   net.minecraft.nbt.ListTag entities) {
        net.minecraft.nbt.CompoundTag out = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(sx));
        size.add(net.minecraft.nbt.IntTag.valueOf(sy));
        size.add(net.minecraft.nbt.IntTag.valueOf(sz));
        out.put("size", size);
        out.put("palette", palette);
        out.put("blocks", blocks);
        out.put("entities", entities);
        return out;
    }
}
