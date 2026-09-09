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

    /** 点云上限（个）。实测二百二十三：点云只发坐标（x,y,z，每点约 12~16 字符）——
     *  旧版每点附带 blockId|state（实测一百四十七起渲染已改用 DebugRenderer 填充盒，
     *  BlockState 不再参与绘制，客户端却仍在做 SNBT 解析——纯浪费），去掉后同样
     *  带宽容量 3000 → 12000，覆盖率 4 倍。渲染侧逐盒距离分档（近处填充上限 2400/次），
     *  12000 的棱线描边单缓冲成批，帧内开销可控。 */
    public static final int MAX_POINTS = 12000;

    private record Cached(List<String> src, List<String> centered) {
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private BlueprintProjectionSampler() {
    }

    /** 取蓝图的居中步骤（带缓存；与建造执行共用同一 centerSteps 变换）。
     *  v1.1.0 实测九十七：缓存键 = id#quarters——旋转版步骤是新列表（引用不同），
     *  天然绕过同键命中；holder 由服务端调用方传入（旋转 BlockState 必需）。 */
    public static List<String> centeredStepsOf(String blueprintId, int quarters,
                                               net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        String key = blueprintId + "#" + Math.floorMod(quarters, 4);
        List<String> src = BlueprintLib.getBlueprintRotated(blueprintId, quarters, holder);
        if (src == null || src.isEmpty()) {
            return null;
        }
        Cached c = CACHE.get(key);
        if (c != null && c.src == src) {
            return c.centered();
        }
        List<String> centered = BlueprintLib.centerSteps(src);
        CACHE.put(key, new Cached(src, centered));
        return centered;
    }

    /**
     * 生成投影点云文本："x,y,z;x,y,z;…"（相对居中坐标；空串 = 无可渲染块）。
     * 实测二百二十三：只发坐标——渲染走 DebugRenderer 填充盒（每盒 1×1×1，
     * 颜色按区域蓝/橙/青），BlockState 不影响绘制，附带的 SNBT 解析纯浪费带宽
     * 与客户端 CPU；"x,y,z" 每点约 12~16 字符，同样带宽可装 4 倍点数。
     */
    public static String sampleCloud(String blueprintId, int quarters,
                                     net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> holder) {
        List<String> steps = centeredStepsOf(blueprintId, quarters, holder);
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        // 收集非禁置块位置（保序）；FORBIDDEN 含 air/structure_void/水岩浆等
        LinkedHashMap<Long, int[]> pos = new LinkedHashMap<>(steps.size());
        for (String step : steps) {
            String[] p = BlueprintLib.parseStep(step);
            if (p == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                if (BlueprintLib.FORBIDDEN.contains(p[3])) {
                    continue;
                }
                long key = pack(x, y, z);
                if (!pos.containsKey(key)) {
                    pos.put(key, new int[]{x, y, z});
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (pos.isEmpty()) {
            return "";
        }
        // 抽壳：六邻至少一个方向不在蓝图内 = 外壳块
        java.util.List<int[]> shell = new java.util.ArrayList<>(pos.size());
        for (Map.Entry<Long, int[]> e : pos.entrySet()) {
            int[] a = e.getValue();
            if (!(pos.containsKey(pack(a[0] + 1, a[1], a[2])) && pos.containsKey(pack(a[0] - 1, a[1], a[2]))
                    && pos.containsKey(pack(a[0], a[1] + 1, a[2])) && pos.containsKey(pack(a[0], a[1] - 1, a[2]))
                    && pos.containsKey(pack(a[0], a[1], a[2] + 1)) && pos.containsKey(pack(a[0], a[1], a[2] - 1)))) {
                shell.add(a);
            }
        }
        // 实测二百二十三【确定性洗牌 + 等距抽稀】：旧版按扫描序（x 主序）每第 N 个
        // ——密集墙面取成一格一格规则竖条纹（"零星复刻一个大概形状"的直接来源）；
        // 洗牌后 ≈ 空间均匀点阵，剪影完整可辨。种子取蓝图 id 哈希：同蓝图永远同点集，
        // 刷新/换向/多客户端一致。抽稀保序遍历（均匀变疏，不改变"全体覆盖"观感）。
        if (shell.size() > MAX_POINTS) {
            java.util.Random rnd = new java.util.Random((long) blueprintId.hashCode() * 0x9E3779B97F4A7C15L);
            java.util.Collections.shuffle(shell, rnd);
        }
        int stride = shell.size() > MAX_POINTS ? (shell.size() + MAX_POINTS - 1) / MAX_POINTS : 1;
        StringBuilder sb = new StringBuilder(shell.size() * 14 / stride + 16);
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
