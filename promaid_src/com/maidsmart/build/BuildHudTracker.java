package com.maidsmart.build;

/**
 * v1.5.252j：建造 HUD 速度/ETA 统计（服务端）——每 20 tick（1 秒）由
 * ProMaidExtension 调用 broadcast()：对每个进行中区块采样 placedCount 增量
 * 计算速度（EMA 平滑），按剩余块数估预计完成时间，打包 BuildHudPacket 广播
 * 给所有玩家（客户端 BuildHudRenderer 左上角显示）。
 *
 * 首帧只记录基准，速度/ETA 从第二次采样起才有值（首秒显示 "--"）。
 * 统计随区块清除自动清理；广播异常不影响建造。
 */
public final class BuildHudTracker {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final java.util.Map<String, Stat> STATS = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> TOTAL = new java.util.HashMap<>();
    /** v1.5.252s：HUD 广播验证日志节流（每 5 秒一条，latest.log 搜 "hud broadcast"） */
    private static long lastHudLogNanos = 0L;

    private static final class Stat {
        long lastNanos = -1;
        int lastPlaced = -1;
        double ema = -1.0; // 块/秒（指数移动平均）
        int lastEta = -1;  // 最近一次广播算出的预计秒（-1 = 未知）
    }

    private BuildHudTracker() {
    }

    /** 服务端每 20 tick 调用：采样全部区块 → 广播 HUD 快照 */
    public static void broadcast(net.minecraft.server.MinecraftServer server) {
        try {
            java.util.List<BuildPlan.PlanState> plans = BuildPlan.allPlansSnapshot();
            if (plans.isEmpty()) {
                if (!STATS.isEmpty()) {
                    STATS.clear();
                    TOTAL.clear();
                }
                return;
            }
            long now = System.nanoTime();
            java.util.List<String[]> entries = new java.util.ArrayList<>();
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (BuildPlan.PlanState ps : plans) {
                alive.add(ps.planId);
                BuildPlan.Progress p = BuildPlan.progress(ps);
                Stat st = STATS.computeIfAbsent(ps.planId, k -> new Stat());
                int total = totalBlocks(ps);
                if (st.lastNanos < 0) {
                    // 首帧：记基准，下一轮（约 1 秒后）出速度
                    st.lastNanos = now;
                    st.lastPlaced = p.placedCount;
                    continue;
                }
                double dt = (now - st.lastNanos) / 1.0e9;
                if (dt <= 0) {
                    continue;
                }
                double inst = (p.placedCount - st.lastPlaced) / dt;
                st.ema = st.ema < 0 ? inst : st.ema * 0.6 + inst * 0.4;
                st.lastNanos = now;
                st.lastPlaced = p.placedCount;
                // v1.5.252ac：剩余 = 总 − 已放 − 永久跳过（跳过的不可能再放，不算
                // 剩余——用户要求"还需多久 = 剩余方块 ÷ 速度，已放的不算"）
                int remaining = Math.max(0, total - p.placedCount - p.skipped);
                int eta = st.ema > 0.01 ? (int) Math.ceil(remaining / st.ema) : -1;
                st.lastEta = eta;
                entries.add(new String[]{ps.planId, ps.name, String.valueOf(p.placedCount),
                        String.valueOf(total), String.valueOf(p.skipped),
                        String.format("%.1f", st.ema), String.valueOf(eta),
                        String.valueOf(ps.paused)});
            }
            // v1.5.252s：限频验证日志（每 5 秒一条）——确认 HUD 服务端广播在跑、数值正确
            if (now - lastHudLogNanos > 5_000_000_000L && !entries.isEmpty()) {
                lastHudLogNanos = now;
                String[] e0 = entries.get(0);
                LOGGER.info("hud broadcast: plan={} name={} placed={} total={} speed={} eta={}",
                        e0[0], e0[1], e0[2], e0[3], e0[5], e0[6]);
            }
            // 清理已清除区块的统计
            STATS.keySet().removeIf(k -> !alive.contains(k));
            TOTAL.keySet().removeIf(k -> !alive.contains(k));
            if (!entries.isEmpty()) {
                BlueprintBookNetworking.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                        new BlueprintBookNetworking.BuildHudPacket(entries));
            }
        } catch (Exception ignored) {
            // HUD 广播失败不影响建造
        }
    }

    /** 总块数（可解析步骤数——steps 可能含头部行，直接 size() 会 +1 让 ETA 偏长；
     *  懒构建只算一次，后续走缓存） */
    private static int totalBlocks(BuildPlan.PlanState ps) {
        Integer t = TOTAL.get(ps.planId);
        if (t == null) {
            int n = 0;
            for (String s : ps.steps) {
                if (BlueprintLib.parseStep(s) != null) {
                    n++;
                }
            }
            t = Math.max(1, n);
            TOTAL.put(ps.planId, t);
        }
        return t;
    }

    /** v1.5.252s：手册进度条旁显示用——{块/秒, 预计秒}；null = 尚无统计（未广播/无计划） */
    public static double[] speedEtaOf(String planId) {
        Stat st = STATS.get(planId);
        if (st == null || st.ema < 0.0) {
            return null;
        }
        return new double[]{st.ema, st.lastEta};
    }
}
