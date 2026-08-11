package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * v1.5.66：建造区块冻结——建造期间区块内"方块时间静止"：
 * 强制加载保持（放置方块需要区块存在），但 randomTick 被 ChunkFreezeMixin 拦截
 * （树叶不衰减消失、草不传播、作物不生长）；红石不激活由 v1.5.57 静默放置保证。
 * 计划完成/取消时解冻，一切恢复正常。
 *
 * v1.5.180：多区块共存——冻结/树叶保护按 planId 分桶（每区块独立），
 * 任一区块释放只解自己的冻结，其他区块冻结不受影响。
 */
public final class ChunkFreeze {
    /** 维度 → {planId → 冻结区块集合} */
    private static final Map<ResourceKey<Level>, Map<String, Set<Long>>> FROZEN = new HashMap<>();

    /** v1.5.67：蓝图树叶位置（永久豁免随机刻度衰减——装饰树被"打印"出来后不消失，
     *  女仆逐块放置的树不算自然树，树叶 distance 超限会在 randomTick 时掉落消失） */
    private static final Map<ResourceKey<Level>, Map<String, Set<Long>>> PROTECTED_LEAVES = new HashMap<>();

    private ChunkFreeze() {
    }

    /** v1.5.67：注册蓝图内全部树叶位置（计划激活时；重启后随计划恢复自动重新注册） */
    public static void protectLeaves(net.minecraft.server.level.ServerLevel level,
                                     String planId, java.util.List<String> plan, net.minecraft.core.BlockPos origin) {
        Set<Long> set = new HashSet<>();
        for (int i = 1; i < plan.size(); i++) {
            String[] head = plan.get(i).split("\\|", -1)[0].split(",", -1);
            if (head.length < 4 || !head[3].contains("leaves")) {
                continue;
            }
            try {
                int x = Integer.parseInt(head[0]);
                int y = Integer.parseInt(head[1]);
                int z = Integer.parseInt(head[2]);
                set.add(leafKey(origin.m_123341_() + x, origin.m_123342_() + y, origin.m_123343_() + z));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!set.isEmpty()) {
            PROTECTED_LEAVES.computeIfAbsent(level.m_46472_(), k -> new HashMap<>()).put(planId, set);
        }
    }

    /** 该位置是否任一区块蓝图保护的树叶（永不衰减） */
    public static boolean isProtectedLeaf(ResourceKey<Level> dim, BlockPos pos) {
        Map<String, Set<Long>> m = PROTECTED_LEAVES.get(dim);
        if (m == null) {
            return false;
        }
        long k = leafKey(pos.m_123341_(), pos.m_123342_(), pos.m_123343_());
        for (Set<Long> s : m.values()) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static long leafKey(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
    }

    /** 冻结单个区块计划区域（v1.5.180：按 planId 分桶，多区块共存） */
    public static void freeze(ServerLevel level, String planId, int minCx, int maxCx, int minCz, int maxCz) {
        Set<Long> set = new HashSet<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                set.add(key(cx, cz));
            }
        }
        FROZEN.computeIfAbsent(level.m_46472_(), k -> new HashMap<>()).put(planId, set);
    }

    /** 解冻单个区块计划（只解自己；其他区块冻结不受影响） */
    public static void unfreeze(ServerLevel level, String planId) {
        Map<String, Set<Long>> m = FROZEN.get(level.m_46472_());
        if (m != null) {
            m.remove(planId);
            if (m.isEmpty()) {
                FROZEN.remove(level.m_46472_());
            }
        }
    }

    /** 该位置是否处于任一建造冻结区块内 */
    public static boolean isFrozen(ResourceKey<Level> dim, BlockPos pos) {
        return isFrozen(dim, pos.m_123341_() >> 4, pos.m_123343_() >> 4);
    }

    public static boolean isFrozen(ResourceKey<Level> dim, int cx, int cz) {
        Map<String, Set<Long>> m = FROZEN.get(dim);
        if (m == null) {
            return false;
        }
        long k = key(cx, cz);
        for (Set<Long> s : m.values()) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }
}
