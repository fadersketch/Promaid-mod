package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 指标石几何（v1.2.0）——服务端开建与客户端幽灵渲染【共用同一套格集合】。
 *
 * 本类只依赖 BlockGetter/BlockPos/BlockState（两树、客户端/服务端都存在的类型），
 * 不引用 ServerPlayer/ServerLevel/Minecraft 等分侧类型，因此可以被客户端渲染器安全
 * 调用——保证"橙色幽灵显示什么、女仆就填什么"逐格一致（对齐项目既有约定：投影点云
 * 与实际搭建同一坐标系）。
 */
public final class IndexStonePlan {

    /** 单次临时蓝图格数上限（防玩家把距离拉到几百格导致一次几万格） */
    public static final int MAX_CELLS = 8192;

    private IndexStonePlan() {
    }

    /**
     * 女仆起点 → 锁定方块之间【所有空气方块】（契约：线接触到的空气方块组建临时蓝图）。
     * 只保留空气格（不替换已有方块），排除起点格本身；按"离起点近→远"排序，
     * 使女仆从自己所在格逐步向锁定格推进。
     */
    public static List<int[]> airCells(BlockGetter level, BlockPos start, BlockPos end, int max) {
        List<int[]> out = new ArrayList<>();
        if (level == null || start == null || end == null || start.equals(end)) {
            return out;
        }
        int cap = max > 0 ? max : MAX_CELLS;
        for (int[] p : rasterize(start, end)) {
            BlockPos pos = new BlockPos(p[0], p[1], p[2]);
            if (pos.equals(start)) {
                continue;
            }
            BlockState st = level.m_8055_(pos);
            if (!st.m_60795_()) {
                continue; // 非空气 → 不填
            }
            out.add(p);
            if (out.size() >= cap) {
                break;
            }
        }
        final double sx = start.m_123341_() + 0.5;
        final double sy = start.m_123342_() + 0.5;
        final double sz = start.m_123343_() + 0.5;
        out.sort((a, b) -> Double.compare(dist2(a, sx, sy, sz), dist2(b, sx, sy, sz)));
        return out;
    }

    private static double dist2(int[] p, double x, double y, double z) {
        double dx = p[0] + 0.5 - x;
        double dy = p[1] + 0.5 - y;
        double dz = p[2] + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 3D 体素连线（Amanatides-Woo 遍历）——返回连线穿过的每一格，无重复。
     * 相比朴素插值，保证"线接触到的所有格"都被覆盖（对角步进不漏格）。
     */
    public static List<int[]> rasterize(BlockPos a, BlockPos b) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int x = a.m_123341_(), y = a.m_123342_(), z = a.m_123343_();
        int ex = b.m_123341_(), ey = b.m_123342_(), ez = b.m_123343_();
        double dx = ex - x, dy = ey - y, dz = ez - z;
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dx);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dy);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(dz);
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        long guard = 0;
        while (guard++ < 200000L) {
            if (seen.add(keyOf(x, y, z))) {
                out.add(new int[]{x, y, z});
                if (out.size() >= MAX_CELLS + 2) {
                    break;
                }
            }
            if (x == ex && y == ey && z == ez) {
                break;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return out;
    }

    private static long keyOf(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (long) (z & 0x1FFFFF);
    }
}
