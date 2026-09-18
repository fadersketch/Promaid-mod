package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 实测五百五十三③：**建造区块内的容器取料**（服务端工具）。
 *
 * 需求："这个 mod 里面可以检索周围箱子以及潜影箱的方块拿过来建造"——范围按用户要求
 * 限定为**建造区块内**（外扩 {@code build.chestSearchMargin}，默认 4 格）。
 *
 * - 容器识别：`BlockEntity instanceof Container` —— 箱子/陷阱箱/桶/潜影箱/模组容器
 *   全覆盖；末影箱（EnderChestBlockEntity）不是 Container，天然不会被翻；
 * - 扫描方式：按区块枚举**真实存在的方块实体**（不是逐格 getBlockEntity）；
 * - 物品匹配：走 {@link BlueprintLib#itemForBlock(String)}（红石线→红石、耕地→泥土、
 *   水→水桶这些映射都在里面），不能用 `Block.asItem()`；
 * - 取出时塞不下的还回**原槽**（与 {@link BlueprintLib#transferFromPlayer} 同口径）。
 */
public final class BuildContainerSource {
    private BuildContainerSource() {
    }

    /** 计划区块范围（外扩 margin）：{minX,minY,minZ,maxX+1,maxY+1,maxZ+1} */
    public static int[] regionOf(BuildPlan.PlanState ps) {
        int[] r = BuildPlan.planRegion(ps);
        if (r == null) {
            return null;
        }
        int m = com.maidsmart.config.MaidSmartConfig.BUILD_CHEST_SEARCH_MARGIN.get();
        return new int[]{r[0] - m, r[1] - m, r[2] - m, r[3] + m, r[4] + m, r[5] + m};
    }

    /** 由"原点 + 蓝图尺寸"构造范围（开建预检时还没有 PlanState 用） */
    public static int[] regionOf(BlockPos origin, int sx, int sy, int sz) {
        int m = com.maidsmart.config.MaidSmartConfig.BUILD_CHEST_SEARCH_MARGIN.get();
        return new int[]{origin.getX() - m, origin.getY() - m, origin.getZ() - m,
                origin.getX() + sx + m, origin.getY() + sy + m, origin.getZ() + sz + m};
    }

    /** 该物品是不是"这一格要用的料" */
    public static boolean matches(String blockId, ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        Item want = BlueprintLib.itemForBlock(blockId);
        return want != null && s.getItem() == want;
    }

    /** 找一个装着该料的容器（返回方块实体；找不到返回 null） */
    public static net.minecraft.world.level.block.entity.BlockEntity findContainer(
            ServerLevel level, int[] box, String blockId) {
        for (net.minecraft.world.level.block.entity.BlockEntity be : blockEntitiesIn(level, box)) {
            if (!(be instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (matches(blockId, c.getItem(i))) {
                    return be;
                }
            }
        }
        return null;
    }

    /** 范围内容器里该料的总数（开建预检 / 实时缺料用） */
    public static int countIn(ServerLevel level, int[] box, String blockId) {
        int n = 0;
        for (net.minecraft.world.level.block.entity.BlockEntity be : blockEntitiesIn(level, box)) {
            if (!(be instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (matches(blockId, s)) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    /** 范围内容器里有没有这些料中的任意一种（一次扫描；"有材料就能开建"的判定用） */
    public static boolean hasAnyOf(ServerLevel level, int[] box, java.util.Collection<String> blockIds) {
        java.util.Set<Item> wants = new java.util.HashSet<>();
        for (String id : blockIds) {
            Item it = BlueprintLib.itemForBlock(id);
            if (it != null) {
                wants.add(it);
            }
        }
        if (wants.isEmpty()) {
            return false;
        }
        for (net.minecraft.world.level.block.entity.BlockEntity be : blockEntitiesIn(level, box)) {
            if (!(be instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && wants.contains(s.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从该容器取最多 max 个该料进女仆背包（塞不下的还回原槽）。返回实际取到的数量。
     */
    public static int take(Container c, String blockId, int max, EntityMaid maid) {
        if (c == null || maid == null || max <= 0) {
            return 0;
        }
        int got = 0;
        for (int i = 0; i < c.getContainerSize() && got < max; i++) {
            ItemStack s = c.getItem(i);
            if (!matches(blockId, s)) {
                continue;
            }
            int move = Math.min(max - got, s.getCount());
            ItemStack taken = c.removeItem(i, move);
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableInv(true);
            ItemStack remain = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(inv, taken, false);
            if (!remain.isEmpty()) {
                c.setItem(i, remain); // 背包满了 → 还回原槽（不吞东西）
            }
            got += taken.getCount() - remain.getCount();
        }
        return got;
    }

    /** 范围内容器的方块实体（按区块枚举，够快；区块没加载自然跳过） */
    private static java.util.List<net.minecraft.world.level.block.entity.BlockEntity> blockEntitiesIn(
            ServerLevel level, int[] box) {
        java.util.List<net.minecraft.world.level.block.entity.BlockEntity> out = new java.util.ArrayList<>();
        if (level == null || box == null) {
            return out;
        }
        int cx0 = box[0] >> 4;
        int cx1 = (box[3] - 1) >> 4;
        int cz0 = box[2] >> 4;
        int cz1 = (box[5] - 1) >> 4;
        int guard = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (++guard > 4096) {
                    return out; // 超大蓝图也最多扫 64×64 区块
                }
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> e
                        : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() < box[0] || p.getX() >= box[3]
                            || p.getY() < box[1] || p.getY() >= box[4]
                            || p.getZ() < box[2] || p.getZ() >= box[5]) {
                        continue;
                    }
                    out.add(e.getValue());
                }
            }
        }
        return out;
    }
}
