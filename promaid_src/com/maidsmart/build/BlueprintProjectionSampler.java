package com.maidsmart.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.1.0 实测八十二：蓝图投影采样器（建造范围预览的"幽灵方块"数据源）。
 *
 * 粉丝反馈：区块预览只有一个方框，不好确认建筑的朝向/形状。本类把蓝图步骤
 * 转成【轮廓点云】下发给客户端渲染：
 * - 输入 = 居中后的蓝图步骤（与 BlueprintBuildExecutor 建造执行同一坐标变换
 *   —— centerSteps 的 x/z 居中偏移一致，投影位置 = 锚点 + 相对坐标，
 *   金色预览锚点 = 玩家脚下格、红色区块锚点 = 计划原点，与实际搭建完全重合）；
 * - 抽壳过滤：只保留六邻至少一个方向没有方块的外壳块（墙体/门窗洞/屋顶），
 *   实心大建筑的内部几万块全部丢弃——门洞天然保留，朝向一眼可辨；
 * - 降采样封顶：外壳仍超 {@link #MAX_POINTS} 时按顺序等距抽稀（点云剪影不变）。
 *
 * 缓存：居中结果按蓝图 id 缓存并以【源步骤引用同一性】校验——外部蓝图重扫/
 * 删除重建后源列表是新实例，自动失效重算；55 万块级蓝图只付一次解析成本。
 */
public final class BlueprintProjectionSampler {

    /** 点云上限（个）。3000 × 每帧 6 面半透明片 ≈ 1.8 万 quad，低端机可承受；
     *  抽稀后剪影仍清晰（外壳本来就是稀疏的） */
    public static final int MAX_POINTS = 3000;

    private record Cached(List<String> src, List<String> centered) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private BlueprintProjectionSampler() {
    }

    /** 取蓝图的居中步骤（带缓存；与建造执行共用同一 centerSteps 变换） */
    public static List<String> centeredStepsOf(String blueprintId) {
        List<String> src = BlueprintLib.getBlueprint(blueprintId);
        if (src == null || src.isEmpty()) {
            return null;
        }
        Cached c = CACHE.get(blueprintId);
        if (c != null && c.src == src) {
            return c.centered();
        }
        List<String> centered = BlueprintLib.centerSteps(src);
        CACHE.put(blueprintId, new Cached(src, centered));
        return centered;
    }

    /**
     * 生成投影点云文本："x,y,z;x,y,z;…"（相对居中坐标；空串 = 无可渲染块）。
     * 编码走 UTF 字符串——与本网络通道既有字段风格一致（避免新 SRG 依赖）。
     */
    public static String sampleCloud(String blueprintId) {
        List<String> steps = centeredStepsOf(blueprintId);
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        // 收集非禁置块位置（保序）；FORBIDDEN 含 air/structure_void/水岩浆等女仆不放的块
        LinkedHashMap<Long, int[]> pos = new LinkedHashMap<>(steps.size());
        for (String step : steps) {
            String[] p = BlueprintLib.parseStep(step);
            if (p == null) {
                continue; // 首部 tag 行
            }
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                if (BlueprintLib.FORBIDDEN.contains(p[3])) {
                    continue;
                }
                pos.putIfAbsent(pack(x, y, z), new int[]{x, y, z});
            } catch (NumberFormatException ignored) {
            }
        }
        if (pos.isEmpty()) {
            return "";
        }
        // 抽壳：六邻至少一个方向不在蓝图内 = 外壳块
        List<int[]> shell = new java.util.ArrayList<>(pos.size());
        for (Map.Entry<Long, int[]> e : pos.entrySet()) {
            int[] a = e.getValue();
            if (!(pos.containsKey(pack(a[0] + 1, a[1], a[2])) && pos.containsKey(pack(a[0] - 1, a[1], a[2]))
                    && pos.containsKey(pack(a[0], a[1] + 1, a[2])) && pos.containsKey(pack(a[0], a[1] - 1, a[2]))
                    && pos.containsKey(pack(a[0], a[1], a[2] + 1)) && pos.containsKey(pack(a[0], a[1], a[2] - 1)))) {
                shell.add(a);
            }
        }
        // 降采样封顶（等距抽稀，保持遍历顺序 = 剪影均匀变疏）
        int stride = shell.size() > MAX_POINTS ? (shell.size() + MAX_POINTS - 1) / MAX_POINTS : 1;
        StringBuilder sb = new StringBuilder(shell.size() * 12 / stride + 16);
        int kept = 0;
        for (int i = 0; i < shell.size(); i += stride) {
            int[] a = shell.get(i);
            if (kept > 0) {
                sb.append(';');
            }
            sb.append(a[0]).append(',').append(a[1]).append(',').append(a[2]);
            kept++;
        }
        return sb.toString();
    }

    /** 与 BuildPlan.Progress.plannedPositions 同款打包（负坐标按低位补码折叠，两侧一致） */
    private static long pack(int x, int y, int z) {
        return (long) (x & 0xFFFFF) << 42 | (long) (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
    }
}
