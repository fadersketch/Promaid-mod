package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 实测五百五十三③：**建造区块内的容器取料**（客户端无关，纯服务端工具）。
 *
 * 需求："这个 mod 里面可以检索周围箱子以及潜影箱的方块拿过来建造"——但范围按你说的
 * 限定为**建造区块内**（外扩 {@code build.chestSearchMargin}，默认 4 格），
 * 区块外更远的箱子不去拿，避免女仆为了材料满世界跑。
 *
 * - 容器识别：`BlockEntity instanceof Container` —— 箱子/陷阱箱/桶/潜影箱/各类模组
 *   容器全覆盖；末影箱（EnderChestBlockEntity）天然不含 Container，所以不会被翻；
 * - 扫描方式：按区块 `LevelChunk.getBlockEntities()` 枚举**真实存在的方块实体**
 *   （不是逐格 getBlockEntity）——100×100 的工地也就 49 个区块，代价可忽略，
 *   大蓝图也不会因为逐格扫描卡住；
 * - 物品匹配：走 {@link BlueprintLib#itemForBlock(String)}（红石线→红石、耕地→泥土、
 *   水→水桶这些映射都在里面），不能用 `Block.asItem()`——那会取错东西；
 * - 取出时的剩余还回**原槽**（与 {@link BlueprintLib#transferFromPlayer} 同一口径）。
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
        return new int[]{origin.m_123341_() - m, origin.m_123342_() - m, origin.m_123343_() - m,
                origin.m_123341_() + sx + m, origin.m_123342_() + sy + m, origin.m_123343_() + sz + m};
    }

    /** 该物品是不是"这一格要用的料" */
    public static boolean matches(String blockId, ItemStack s) {
        if (s == null || s.m_41619_()) {
            return false;
        }
        Item want = BlueprintLib.itemForBlock(blockId);
        return want != null && s.m_41720_() == want;
    }

    /** 找一个装着该料的容器（返回方块实体；找不到返回 null） */
    public static net.minecraft.world.level.block.entity.BlockEntity findContainer(
            ServerLevel level, int[] box, String blockId) {
        for (net.minecraft.world.level.block.entity.BlockEntity be : blockEntitiesIn(level, box)) {
            if (!(be instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.m_6643_(); i++) {
                if (matches(blockId, c.m_8020_(i))) {
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
            for (int i = 0; i < c.m_6643_(); i++) {
                ItemStack s = c.m_8020_(i);
                if (matches(blockId, s)) {
                    n += s.m_41613_();
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
            for (int i = 0; i < c.m_6643_(); i++) {
                ItemStack s = c.m_8020_(i);
                if (!s.m_41619_() && wants.contains(s.m_41720_())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从该容器取最多 max 个该料进女仆背包（塞不下的还回原槽）。
     * 返回实际取到的数量。
     */
    public static int take(Container c, String blockId, int max, EntityMaid maid) {
        if (c == null || maid == null || max <= 0) {
            return 0;
        }
        int got = 0;
        for (int i = 0; i < c.m_6643_() && got < max; i++) {
            ItemStack s = c.m_8020_(i);
            if (!matches(blockId, s)) {
                continue;
            }
            int move = Math.min(max - got, s.m_41613_());
            ItemStack taken = c.m_7407_(i, move);
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableInv(true);
            ItemStack remain = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(inv, taken, false);
            if (!remain.m_41619_()) {
                c.m_6836_(i, remain); // 背包满了 → 还回原槽（不吞东西）
            }
            got += taken.m_41613_() - remain.m_41613_();
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
        // 上限：最多扫 64×64 个区块（超大蓝图也不至于把一帧搭进去）
        int guard = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (++guard > 4096) {
                    return out;
                }
                net.minecraft.world.level.chunk.LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> e
                        : chunk.m_62954_().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.m_123341_() < box[0] || p.m_123341_() >= box[3]
                            || p.m_123342_() < box[1] || p.m_123342_() >= box[4]
                            || p.m_123343_() < box[2] || p.m_123343_() >= box[5]) {
                        continue;
                    }
                    out.add(e.getValue());
                }
            }
        }
        return out;
    }
}
