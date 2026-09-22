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
 * 放置与场地处理（v1.2.4 从 BlueprintLib 拆出）。
 * 
 * 破坏/清理掉落物、支撑面方向、悬空重力、红石重算、已建过滤、
 * 障碍物预检、门洞开凿、已存在建筑的定位。
 */
public final class BlueprintPlacement {
    private BlueprintPlacement() {
    }

    public static void forceBreak(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.core.BlockPos pos,
                                  net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.world.level.block.Block.m_49950_(state, level, pos); // dropResources
        level.m_7731_(pos, net.minecraft.world.level.block.Blocks.f_50016_.m_49966_(), 2);
        level.m_46796_(2001, pos, net.minecraft.world.level.block.Block.m_49956_(state));
    }

    public static void cleanupDrops(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
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
            String[] pp = BlueprintStepMath.parseStep(plan.get(i));
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
            BlueprintLib.LOGGER.info("cleanupDrops: 清理建造区掉落物 {} 个", n);
        }
    }

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
                    net.minecraft.core.Direction d = BlueprintLegacyIds.dirByName(n);
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
        net.minecraft.core.Direction dir = BlueprintLegacyIds.dirByName(facing);
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

    public static boolean isFluidStepAt(List<String> plan, net.minecraft.core.BlockPos origin,
                                        net.minecraft.core.BlockPos worldPos) {
        int wx = worldPos.m_123341_();
        int wy = worldPos.m_123342_();
        int wz = worldPos.m_123343_();
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
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

    public static int countSuspendedGravity(net.minecraft.server.level.ServerLevel level,
                                            net.minecraft.core.BlockPos origin,
                                            java.util.List<String> plan) {
        int count = 0;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
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

    public static void recalcRedstone(net.minecraft.server.level.ServerLevel level, BlockPos origin, List<String> plan) {
        java.util.List<int[]> comps = new java.util.ArrayList<>();
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintStepMath.parseStep(plan.get(i));
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

    public static List<String> filterBuilt(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        List<String> pending = new ArrayList<>();
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
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
                        && (state.m_60734_() == want || BlueprintBlockData.isBuiltEquivalent(parts[3], state.m_60734_()))) {
                    continue; // v2.0：区块内已见匹配方块 = 已建（跳过，不拆）
                }
            } catch (NumberFormatException ignored) {
            }
            pending.add(step);
        }
        return pending;
    }

    public static Map<String, Integer> countBuiltMaterials(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        Map<String, Integer> built = new HashMap<>();
        if (level == null || origin == null) {
            return built;
        }
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
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
                if (state.m_60734_() == want || BlueprintBlockData.isBuiltEquivalent(parts[3], state.m_60734_())) {
                    built.merge(parts[3], 1, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return built;
    }

    public static String findObstacles(
            net.minecraft.world.level.Level level, BlockPos origin, List<String> steps) {
        for (String step : steps) {
            String[] parts = BlueprintStepMath.parseStep(step);
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
            if (want != null && (state.m_60734_() == want || BlueprintBlockData.isBuiltEquivalent(parts[3], state.m_60734_()))) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (id != null && BlueprintBlockData.ALLOWED_GROUND.contains(id.toString())) {
                continue; // 自然地形可覆盖
            }
            String name = id != null ? BlueprintNames.cnName(id.toString()) : "未知方块";
            // v1.5.84：以玩家所在那一格为参照的描述（建造原点 = 玩家脚下）
            return "这个地方有障碍物：" + name + "（" + target.m_123341_() + "," + target.m_123342_() + "," + target.m_123343_() + "）。请换一个开阔平坦的地方，或者清掉障碍物后再试。";
        }
        return null;
    }

    public static void carveEntrance(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                     List<String> plan, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        try {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 1; i < plan.size(); i++) {
                String[] parts = BlueprintStepMath.parseStep(plan.get(i));
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

    public static BlockPos findExistingOrigin(net.minecraft.server.level.ServerLevel level,
                                              com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid,
                                              List<String> steps) {
        try {
            List<int[]> rel = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (String step : steps) {
                String[] parts = BlueprintStepMath.parseStep(step);
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
            // v1.5.147b（方案）：先确认【区域内实际存在的方块种类】，再与蓝图
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
                        if (bid == null || BlueprintBlockData.ALLOWED_GROUND.contains(bid.toString())) {
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
                    if (ids.get(i).equals(anchorId) || BlueprintBlockData.isBuiltEquivalent(ids.get(i), rareBlock)) {
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
                        if (want != null && (st.m_60734_() == want || BlueprintBlockData.isBuiltEquivalent(ids.get(idx), st.m_60734_()))) {
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

    private static boolean blueprintWants(java.util.Set<String> wantIds, String worldId, Block worldBlock) {
        if (wantIds.contains(worldId)) {
            return true;
        }
        for (String w : wantIds) {
            if (BlueprintBlockData.isBuiltEquivalent(w, worldBlock)) {
                return true;
            }
        }
        return false;
    }

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
