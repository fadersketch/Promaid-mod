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
 * 从存档区段文件（.mca）提取建筑（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 读取 region 文件 → 解压 → 反序列化区块 NBT → 按地形过滤 → 逐层裁剪，
 * 产出统一的步骤列表。
 */
public final class BlueprintWorldExtract {
    private BlueprintWorldExtract() {
    }

    private static long packWorldKey(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
    }

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

    static java.nio.file.Path findWorldRoot(java.nio.file.Path dir) {
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

    public static List<String> extractFromWorldZip(java.nio.file.Path dir) {
        try {
            double[] anchor = worldAnchor(dir);
            if (anchor == null) {
                BlueprintLib.LOGGER.warn("extractFromWorldZip: 找不到锚点（playerdata/level.dat 玩家位置）");
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
                BlueprintLib.LOGGER.warn("extractFromWorldZip: 锚点附近没有可提取方块");
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
            int cap = BlueprintLib.structureMaxBlocks();
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
            List<String> trimmed = BlueprintStepMath.trimTerrainLayers(steps);
            BlueprintLib.LOGGER.info("extractFromWorldZip: 提取 {} 块（原 {} 块，锚点 {}, {}, {}，范围 {}x{}x{}）",
                    trimmed == null ? steps.size() : trimmed.size(), steps.size(),
                    (int) anchor[0], (int) anchor[1], (int) anchor[2],
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
            return trimmed != null ? trimmed : steps;
        } catch (Exception e) {
            BlueprintLib.LOGGER.warn("extractFromWorldZip 异常 -> {}", e.toString());
            return null;
        }
    }

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

    private static boolean skipWorldBlock(String blockId, int wx, int wz,
                                          double[] anchor, int range) {
        if (BlueprintBlockData.TERRAIN_BLOCKS.contains(blockId)
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
                        String state = BlueprintLegacyIds.legacyBlockState(id, meta);
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
                                ? "" : BlueprintLegacyIds.paletteStateString(ps);
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
}
