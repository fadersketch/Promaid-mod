package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界探查引擎（v1.5.196，移植 PatchouliAI SpatialPerceptionTool + BuildingPlanner +
 * QueryExecutor 的纯逻辑部分）。
 *
 * 给 LLM 提供"先查后做"的环境信息：
 * - lookAround：ASCII 空间感知网格（@ . ^ , v # ~ ! x T ?，含图例与朝向）
 * - analyzeTerrain：地形平坦度报告（平均高度/高度差/可建面积比）
 * - analyzeBuildSite：建造场地分析（障碍物分类 TREE/WALL/LAVA/WATER/HEIGHT_DIFF + 可建性建议）
 * - inspectBlock：单方块详情（ID/硬度/掉落工具/状态属性/距离）
 * - scanBlock：按 ID 扫描方块（坐标+距离）
 * - scanEntities：按类型扫描实体（hostile/passive/player/all + 血量/距离）
 *
 * 全部只读，无副作用；SRG 映射已在 promaid 其它类实证。
 */
public final class WorldProbe {
    private WorldProbe() {
    }

    private static ServerLevel level(EntityMaid maid) {
        return maid.m_9236_() instanceof ServerLevel sl ? sl : null;
    }

    /* ---------------- ASCII 空间感知网格（look_around） ---------------- */

    /** 分类一列：脚/头方块 → 单个语义字符 */
    private static char classifyColumn(ServerLevel lv, int x, int feetY, int z) {
        BlockState feet = lv.m_8055_(new BlockPos(x, feetY, z));
        BlockState head = lv.m_8055_(new BlockPos(x, feetY + 1, z));
        if (isLava(feet) || isLava(head)) {
            return '!';
        }
        if (isLiquid(feet) || isLiquid(head)) {
            return '~';
        }
        Integer standY = null;
        for (int y = feetY + 1; y >= feetY - 3; y--) {
            if (canStandAt(lv, x, y, z)) {
                standY = y;
                break;
            }
        }
        if (standY == null) {
            boolean bodyClear = fullyPassable(lv, x, feetY, z) && fullyPassable(lv, x, feetY + 1, z);
            if (!bodyClear) {
                if (isTree(feet) || isTree(head)) {
                    return 'T';
                }
                return '#';
            }
            return 'v';
        }
        int delta = standY - feetY;
        if (delta >= 2) {
            return '#';
        }
        if (delta == 1) {
            return '^';
        }
        if (delta == 0) {
            return '.';
        }
        if (delta >= -2) {
            return ',';
        }
        return 'v';
    }

    private static boolean canStandAt(ServerLevel lv, int x, int y, int z) {
        BlockState below = lv.m_8055_(new BlockPos(x, y - 1, z));
        BlockState body = lv.m_8055_(new BlockPos(x, y, z));
        BlockState head = lv.m_8055_(new BlockPos(x, y + 1, z));
        return hasCollision(below) && fullyPassable(lv, x, y, z) && fullyPassable(lv, x, y + 1, z);
    }

    private static boolean hasCollision(BlockState state) {
        return !state.m_60819_().m_76178_() && !state.m_60815_();
    }

    private static boolean fullyPassable(ServerLevel lv, int x, int y, int z) {
        BlockState state = lv.m_8055_(new BlockPos(x, y, z));
        return state.m_60819_().m_76178_() && state.m_60815_();
    }

    private static boolean isLava(BlockState state) {
        String path = blockPath(state.m_60734_());
        return "lava".equals(path) || "flowing_lava".equals(path);
    }

    private static boolean isLiquid(BlockState state) {
        return !state.m_60819_().m_76178_() && !isLava(state);
    }

    private static boolean isTree(BlockState state) {
        String path = blockPath(state.m_60734_());
        return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_leaves");
    }

    /** 方块注册表 path（如 oak_planks；Forge 注册表，避免 srg BuiltInRegistries 字段歧义） */
    private static String blockPath(Block block) {
        try {
            ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
            return id == null ? "" : id.m_135815_();
        } catch (Exception e) {
            return "";
        }
    }

    /** 岩浆/危险扩散一圈（x 表示危险缓冲区） */
    private static char[][] inflateHazards(char[][] grid, int size) {
        List<int[]> hazards = new ArrayList<>();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] == '!') {
                    hazards.add(new int[]{r, c});
                }
            }
        }
        for (int[] p : hazards) {
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) {
                        continue;
                    }
                    int nr = p[0] + dr;
                    int nc = p[1] + dc;
                    if (nr < 0 || nr >= size || nc < 0 || nc >= size) {
                        continue;
                    }
                    char c = grid[nr][nc];
                    if (c == '.' || c == '^' || c == ',') {
                        grid[nr][nc] = 'x';
                    }
                }
            }
        }
        return grid;
    }

    private static String facing(EntityMaid maid) {
        float yaw = maid.m_146908_();
        yaw = (yaw % 360.0f + 360.0f) % 360.0f;
        if (yaw >= 315.0f || yaw < 45.0f) {
            return "south";
        }
        if (yaw >= 45.0f && yaw < 135.0f) {
            return "west";
        }
        if (yaw >= 135.0f && yaw < 225.0f) {
            return "north";
        }
        return "east";
    }

    /** 生成 ASCII 空间感知网格（radius 4-16，默认 8） */
    public static String lookAround(EntityMaid maid, int radius) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return "空间感知仅在服务端可用";
        }
        int r = Math.max(4, Math.min(16, radius));
        int size = r * 2 + 1;
        BlockPos center = maid.m_20183_();
        int feetY = center.m_123342_();
        int cx = center.m_123341_();
        int cz = center.m_123343_();
        char[][] grid = new char[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                grid[row][col] = classifyColumn(lv, cx + (col - r), feetY, cz + (row - r));
            }
        }
        grid[r][r] = '@';
        inflateHazards(grid, size);
        StringBuilder sb = new StringBuilder();
        sb.append("look_around center=(").append(cx).append(",").append(feetY).append(",").append(cz)
                .append(") facing=").append(facing(maid))
                .append(" | 1 cell = 1 block, @ = you, North = up (-Z), East = right (+X)\n\n");
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                sb.append(grid[row][col]);
                if (col < size - 1) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        sb.append("\nlegend: @ you | . flat | ^ step-up 1 | , step-down 1-2 | v drop>=3 | # wall/blocked")
                .append(" | ~ water | ! lava/hazard | x caution | T tree | ? unloaded");
        sb.append("\nto route: trace cell by cell (. ^ , are walkable; # ~ ! v x block or endanger you).");
        return sb.toString();
    }

    /* ---------------- 地形分析（terrain） ---------------- */

    private static int findGroundHeight(ServerLevel lv, int x, int startY, int z) {
        for (int y = startY; y > startY - 10; y--) {
            BlockState state = lv.m_8055_(new BlockPos(x, y, z));
            if (!state.m_60819_().m_76178_() || state.m_60815_()) {
                continue;
            }
            return y;
        }
        return startY - 10;
    }

    /** 地形平坦度报告（radius 4-16，默认 8） */
    public static String analyzeTerrain(EntityMaid maid, int radius) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return "地形分析仅在服务端可用";
        }
        int r = Math.max(4, Math.min(16, radius));
        BlockPos center = maid.m_20183_();
        int cx = center.m_123341_();
        int cy = center.m_123342_();
        int cz = center.m_123343_();
        int n = (r * 2 + 1) * (r * 2 + 1);
        int[] heightMap = new int[n];
        int idx = 0;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        long sum = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h = findGroundHeight(lv, cx + dx, cy + 3, cz + dz);
                heightMap[idx++] = h;
                sum += h;
                if (h < minH) {
                    minH = h;
                }
                if (h > maxH) {
                    maxH = h;
                }
            }
        }
        int avg = (int) (sum / n);
        int diff = maxH - minH;
        String flatness = diff <= 1 ? "平坦（高度差≤1格，适合直接建造）"
                : diff <= 3 ? "缓坡（高度差2-3格，建议先平整地基）"
                : "崎岖（高度差≥4格，需要大量平整工作）";
        int buildable = 0;
        for (int h : heightMap) {
            if (Math.abs(h - avg) <= 1) {
                buildable++;
            }
        }
        double ratio = buildable * 100.0 / n;
        StringBuilder sb = new StringBuilder();
        sb.append("地形分析结果 (中心: ").append(cx).append(",").append(cy).append(",").append(cz).append("):\n");
        sb.append("  扫描范围: ").append(r * 2 + 1).append("x").append(r * 2 + 1).append(" (").append(n).append("格)\n");
        sb.append("  平均高度: Y=").append(avg).append("\n");
        sb.append("  最低点: Y=").append(minH).append("\n");
        sb.append("  最高点: Y=").append(maxH).append("\n");
        sb.append("  高度差: ").append(diff).append("格\n");
        sb.append("  平坦度: ").append(flatness).append("\n");
        sb.append("  可建面积: ").append(String.format("%.1f%%", ratio)).append("（高度差≤1格的占比）\n");
        sb.append("  建议起始Y: ").append(maxH).append("（以最高点为地基，向下填充）");
        return sb.toString();
    }

    /* ---------------- 建造场地分析（build_site） ---------------- */

    /** 单格障碍物 */
    public record Obstacle(BlockPos pos, String type, String desc) {
    }

    /** 场地分析结果 */
    public record SiteInfo(int centerX, int centerY, int centerZ, int width, int depth, int baseY,
                           String flatness, List<Obstacle> obstacles, int clearCount,
                           boolean buildable, String recommendation) {
    }

    /** 分析以女仆为中心的 width×depth 场地（默认 7×7） */
    public static SiteInfo analyzeBuildSite(EntityMaid maid, int width, int depth) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return new SiteInfo(0, 0, 0, width, depth, 0, "未知", List.of(), 0, false, "仅服务端可执行分析");
        }
        width = Math.max(3, Math.min(32, width));
        depth = Math.max(3, Math.min(32, depth));
        BlockPos center = maid.m_20183_();
        int cx = center.m_123341_();
        int cy = center.m_123342_();
        int cz = center.m_123343_();
        int halfW = width / 2;
        int halfD = depth / 2;
        int[][] heightMap = new int[width][depth];
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int ground = findGroundHeight(lv, cx - halfW + dx, cy + 5, cz - halfD + dz);
                heightMap[dx][dz] = ground;
                if (ground < minH) {
                    minH = ground;
                }
                if (ground > maxH) {
                    maxH = ground;
                }
            }
        }
        int diff = maxH - minH;
        String flatness = diff <= 1 ? "平坦" : diff <= 3 ? "缓坡" : "崎岖";
        List<Obstacle> obstacles = new ArrayList<>();
        int clearCount = 0;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int x = cx - halfW + dx;
                int z = cz - halfD + dz;
                int base = heightMap[dx][dz];
                for (int y = base + 1; y <= base + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = lv.m_8055_(pos);
                    if (state.m_60819_().m_76178_() && state.m_60815_()) {
                        continue;
                    }
                    if (blockPath(state.m_60734_()).endsWith("_log")
                            || blockPath(state.m_60734_()).endsWith("_stem")
                            || blockPath(state.m_60734_()).endsWith("_leaves")) {
                        obstacles.add(new Obstacle(pos, "TREE", "树木障碍"));
                        clearCount++;
                        continue;
                    }
                    if (isLava(state)) {
                        obstacles.add(new Obstacle(pos, "LAVA", "岩浆"));
                        continue;
                    }
                    if (!state.m_60819_().m_76178_()) {
                        obstacles.add(new Obstacle(pos, "WATER", "水体"));
                        continue;
                    }
                    obstacles.add(new Obstacle(pos, "WALL", "障碍方块 " + blockPath(state.m_60734_())));
                    clearCount++;
                }
                if (heightMap[dx][dz] > minH + 2) {
                    obstacles.add(new Obstacle(new BlockPos(x, base, z), "HEIGHT_DIFF",
                            "地面偏高，需挖除 " + (heightMap[dx][dz] - minH) + " 格"));
                }
            }
        }
        int baseY = minH;
        boolean buildable = diff <= 5 && obstacles.stream().noneMatch(o -> "LAVA".equals(o.type));
        String recommendation = buildable
                ? (obstacles.isEmpty() ? "地形平坦，可直接建造。" : "可在此处建造，需清理 " + clearCount + " 个障碍物。")
                : "不建议在此处建造：地形过于崎岖或存在岩浆等危险。建议寻找更平坦的区域。";
        return new SiteInfo(cx, cy, cz, width, depth, baseY, flatness, obstacles, clearCount, buildable, recommendation);
    }

    /** 场地分析报告（喂 LLM） */
    public static String formatSiteReport(SiteInfo a) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 建筑场地分析报告 ==\n");
        sb.append("  中心坐标: (").append(a.centerX()).append(", ").append(a.centerY()).append(", ").append(a.centerZ()).append(")\n");
        sb.append("  建造范围: ").append(a.width()).append("x").append(a.depth()).append("\n");
        sb.append("  地基高度: Y=").append(a.baseY()).append("\n");
        sb.append("  地形平坦度: ").append(a.flatness()).append("\n");
        sb.append("  适合建造: ").append(a.buildable() ? "是" : "否").append("\n");
        if (!a.obstacles().isEmpty()) {
            sb.append("  障碍物 (").append(a.obstacles().size()).append("个):\n");
            int shown = 0;
            for (Obstacle o : a.obstacles()) {
                if (shown >= 10) {
                    sb.append("    ... 等 ").append(a.obstacles().size() - shown).append(" 个\n");
                    break;
                }
                sb.append("    [").append(o.type()).append("] (").append(o.pos().m_123341_()).append(",")
                        .append(o.pos().m_123342_()).append(",").append(o.pos().m_123343_()).append(") ")
                        .append(o.desc()).append("\n");
                shown++;
            }
        }
        if (a.clearCount() > 0) {
            sb.append("  障碍清理: 需移除 ").append(a.clearCount()).append(" 个方块\n");
        }
        sb.append("  建议: ").append(a.recommendation());
        return sb.toString();
    }

    /* ---------------- 方块查询（inspect / scanblock） ---------------- */

    /** 单方块详情（坐标 → ID/硬度/掉落工具/状态/距离） */
    public static String inspectBlock(EntityMaid maid, int x, int y, int z) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return "方块检查仅在服务端可用";
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = lv.m_8055_(pos);
        Block block = state.m_60734_();
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        // m_60800_=getDestroySpeed（BlockBehaviour 反编译实证）；掉落工具需求无公开 getter，
        // 用硬度推断（基岩/黑曜石硬度 <0 不可破坏，其余需要工具的方块硬度较高）
        float hardness = state.m_60800_(lv, pos);
        boolean requiresTool = hardness >= 1.0f;
        StringBuilder sb = new StringBuilder();
        sb.append("方块检查结果:\n");
        sb.append("  坐标: ").append(x).append(", ").append(y).append(", ").append(z).append("\n");
        sb.append("  方块ID: ").append(id).append("\n");
        sb.append("  硬度: ").append(hardness).append("\n");
        sb.append("  掉落: ").append(requiresTool ? "需要正确工具" : "无需特定工具").append("\n");
        sb.append("  状态: ").append(state.m_60795_() ? "空气" : blockPath(state.m_60734_())).append("\n");
        return sb.toString();
    }

    private static Block resolveBlock(String name) {
        try {
            ResourceLocation rl = name.contains(":") ? ResourceLocation.parse(name)
                    : ResourceLocation.parse("minecraft:" + name);
            return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(rl);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按方块 ID 扫描（radius 默认 64，上限 128；返回最近 32 个坐标） */
    public static String scanBlock(EntityMaid maid, String blockName, int radius) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return "方块扫描仅在服务端可用";
        }
        Block target = resolveBlock(blockName);
        if (target == null) {
            return "未找到方块: " + blockName;
        }
        int r = Math.min(Math.max(1, radius), 128);
        BlockPos origin = maid.m_20183_();
        double ox = maid.m_20185_();
        double oy = maid.m_20186_();
        double oz = maid.m_20189_();
        List<Object[]> found = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = origin.m_7918_(dx, dy, dz);
                    if (!lv.m_46749_(pos)) {
                        continue; // 未加载区块不触发加载，避免卡服
                    }
                    if (lv.m_8055_(pos).m_60734_() != target) {
                        continue;
                    }
                    double dist = Math.sqrt(Math.pow(pos.m_123341_() - ox, 2)
                            + Math.pow(pos.m_123342_() - oy, 2)
                            + Math.pow(pos.m_123343_() - oz, 2));
                    found.add(new Object[]{pos, dist});
                    if (found.size() >= 512) {
                        break;
                    }
                }
                if (found.size() >= 512) {
                    break;
                }
            }
            if (found.size() >= 512) {
                break;
            }
        }
        if (found.isEmpty()) {
            return "扫描完成: 半径 " + r + " 格内未找到 " + blockName;
        }
        found.sort((a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        int limit = Math.min(found.size(), 32);
        StringBuilder sb = new StringBuilder();
        sb.append("扫描完成: 半径 ").append(r).append(" 格内找到 ").append(blockName)
                .append("（共").append(found.size()).append("个，显示最近").append(limit).append("个）\n");
        for (int i = 0; i < limit; i++) {
            BlockPos p = (BlockPos) found.get(i)[0];
            sb.append("    at ").append(p.m_123341_()).append(", ").append(p.m_123342_()).append(", ")
                    .append(p.m_123343_()).append(" dist=").append(String.format("%.1f", (Double) found.get(i)[1])).append("格\n");
        }
        return sb.toString();
    }

    /* ---------------- 实体扫描（scanentity） ---------------- */

    private static boolean isHostile(Mob mob) {
        return mob instanceof Monster;
    }

    private static String entityId(LivingEntity e) {
        try {
            net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
            return key == null ? "unknown" : key.toString();
        } catch (Exception ex) {
            return "unknown";
        }
    }

    /** 实体扫描（type: hostile/passive/player/all，radius 默认 32，上限 128） */
    public static String scanEntities(EntityMaid maid, String type, int radius) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return "实体扫描仅在服务端可用";
        }
        String t = type == null || type.isBlank() ? "all" : type.trim().toLowerCase();
        int r = Math.min(Math.max(4, radius), 128);
        double x = maid.m_20185_();
        double y = maid.m_20186_();
        double z = maid.m_20189_();
        AABB box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);
        List<Object[]> entries = new ArrayList<>();
        boolean wantHostile = t.equals("hostile") || t.equals("all");
        boolean wantPassive = t.equals("passive") || t.equals("all");
        boolean wantPlayer = t.equals("player") || t.equals("all");
        if (wantHostile || wantPassive) {
            for (Mob m : lv.m_6443_(Mob.class, box, e -> true)) {
                double dist = m.m_20238_(maid.m_20182_());
                if (dist > r) {
                    continue;
                }
                boolean hostile = isHostile(m);
                if (hostile && wantHostile) {
                    entries.add(new Object[]{"[敌对]", entityId(m), m.m_20183_(), m.m_21223_(), m.m_21233_(), dist});
                } else if (!hostile && wantPassive) {
                    entries.add(new Object[]{"[中立/被动]", entityId(m), m.m_20183_(), m.m_21223_(), m.m_21233_(), dist});
                }
            }
        }
        if (wantPlayer) {
            for (Player p : lv.m_6443_(Player.class, box, e -> true)) {
                if (p.m_20148_().equals(maid.m_20148_())) {
                    continue;
                }
                double dist = p.m_20238_(maid.m_20182_());
                if (dist <= r) {
                    entries.add(new Object[]{"[玩家]", p.m_5446_() != null ? p.m_5446_().getString() : "?", p.m_20183_(), p.m_21223_(), p.m_21233_(), dist});
                }
            }
        }
        if (entries.isEmpty()) {
            return "实体扫描结果 (半径" + r + "格): 未找到匹配实体";
        }
        entries.sort((a, b) -> Double.compare((Double) a[5], (Double) b[5]));
        int limit = Math.min(entries.size(), 20);
        StringBuilder sb = new StringBuilder();
        sb.append("实体扫描结果 (半径").append(r).append("格，共").append(entries.size())
                .append("个，显示最近").append(limit).append("个):\n");
        for (int i = 0; i < limit; i++) {
            Object[] e = entries.get(i);
            BlockPos p = (BlockPos) e[2];
            sb.append("  ").append(e[0]).append(" ").append(e[1])
                    .append(" at ").append(p.m_123341_()).append(",").append(p.m_123342_()).append(",").append(p.m_123343_())
                    .append(" hp=").append(String.format("%.1f", (Float) e[3])).append("/").append(String.format("%.1f", (Float) e[4]))
                    .append(" dist=").append(String.format("%.1f", (Double) e[5])).append("格\n");
        }
        return sb.toString();
    }

    /** 威胁数量（v1.5.196 给 build_site/世界简报用）：对女仆/主人携带仇恨的生物 + 敌对生物 */
    public static int countThreats(EntityMaid maid, double radius) {
        ServerLevel lv = level(maid);
        if (lv == null) {
            return 0;
        }
        int n = 0;
        for (Mob m : lv.m_6443_(Mob.class, maid.m_20191_().m_82400_(radius), e -> true)) {
            if (PerceptionManager.isThreat(m, maid)) {
                n++;
            }
        }
        return n;
    }

    /** 附近生物种类计数（worldBrief 用，避免重复扫描） */
    public static Map<String, Integer> countNearbyMobs(EntityMaid maid, double radius) {
        ServerLevel lv = level(maid);
        Map<String, Integer> out = new HashMap<>();
        if (lv == null) {
            return out;
        }
        for (Mob m : lv.m_6443_(Mob.class, maid.m_20191_().m_82400_(radius), e -> true)) {
            out.merge(entityId(m), 1, Integer::sum);
        }
        return out;
    }
}
