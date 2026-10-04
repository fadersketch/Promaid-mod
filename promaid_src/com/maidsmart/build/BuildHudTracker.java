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
    /**
     * v1.2.0 实测五百五十七【建造完成后 HUD 不消失】：上一轮是否真的发过内容。
     *
     * 旧版"没有进行中计划"时直接 return（一个包都不发），而 HUD 是客户端拿快照画的
     * ——计划一完成/取消，服务端从此静默，客户端就把最后那一帧（实测：卡在 99%）
     * 一直挂在左上角，直到退出游戏。现在计划清空时**补发一次空快照**，客户端据此清屏。
     */
    private static boolean lastHadEntries = false;

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
                // v1.2.0 实测五百五十七：最后一批计划结束（完成/取消/清空）→ 补发一次空快照，
                // 客户端左上角 HUD 才会消失（旧版这里直接 return，HUD 永久挂着最后一帧）
                if (lastHadEntries) {
                    lastHadEntries = false;
                    sendToInvolved(server, new java.util.ArrayList<>(), null);
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
                // 剩余——要求"还需多久 = 剩余方块 ÷ 速度，已放的不算"）
                // v1.2.0 实测五百五十七：已放 = 真放置 + **开工时就已是目标方块的格子**
                // （旧版只算前者 → "全部建好"时进度永远差最后那几格，屏幕上卡在 99%）
                int built = p.placedCount + p.prebuiltCount;
                int remaining = Math.max(0, total - built - p.skipped);
                int eta = st.ema > 0.01 ? (int) Math.ceil(remaining / st.ema) : -1;
                st.lastEta = eta;
                entries.add(new String[]{ps.planId, ps.name, String.valueOf(built),
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
                lastHadEntries = true;
                sendToInvolved(server, entries, java.util.Set.copyOf(alive));
            }
        } catch (Exception ignored) {
            // HUD 广播失败不影响建造
        }
    }

    /**
     * 【联机·服务端】只把建造 HUD 发给**与该区块有关的人**：站在区块范围内、
     * 或区块里绑着ta（已加载）的女仆的玩家。
     *
     * <p>旧版是 {@code PacketDistributor.ALL}——多人在线时**每个玩家**每秒都会收到
     * **全服务器所有人**的建造进度（区块名/进度/速度/ETA）。既把别人的工地进度泄露给无关玩家，
     * 又让无关玩家屏幕上无条件挂出别人的建造 HUD。这里改成"谁的事发给谁"。
     *
     * <p>{@code planIds == null} = 计划结束那次补发的空快照 / 兜底路径（发给所有人）。
     */
    private static void sendToInvolved(net.minecraft.server.MinecraftServer server,
                                       java.util.List<String[]> allEntries,
                                       java.util.Set<String> planIds) {
        // 先把"每份快照"的判定材料算一次（区块范围 + 绑定女仆主人），别对每个玩家重算一遍
        java.util.List<Object[]> infos = new java.util.ArrayList<>();
        if (planIds != null) {
            for (String[] e : allEntries) {
                if (!planIds.contains(e[0])) {
                    continue;
                }
                Object[] info = planInfo(server, e[0]);
                if (info != null) {
                    infos.add(new Object[]{e, info[1], info[2], info[3]});
                }
            }
        }
        for (net.minecraft.server.level.ServerPlayer p : server.m_6846_().m_11314_()) {
            try {
                java.util.List<String[]> out;
                if (planIds == null) {
                    out = allEntries; // 兜底 / 清屏：发给所有人
                } else {
                    out = new java.util.ArrayList<>();
                    for (Object[] info : infos) {
                        if (playerInvolved(p, info)) {
                            out.add((String[]) info[0]);
                        }
                    }
                }
                // 每人一份自己相关的快照（含空表——空表用于清屏，见 lastHadEntries）
                BlueprintBookNetworking.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                        new BlueprintBookBuildPackets.BuildHudPacket(
                                out == null ? new java.util.ArrayList<>() : out));
            } catch (Exception ignored) {
            }
        }
    }

    /** 一份快照的判定材料：{planId, ServerLevel, int[] region, Set<UUID> boundOwners}；取不到 → null。 */
    private static Object[] planInfo(net.minecraft.server.MinecraftServer server, String planId) {
        try {
            BuildPlan.PlanState ps = BuildPlan.getPlanById(planId);
            if (ps == null) {
                return null;
            }
            net.minecraft.server.level.ServerLevel lvl = server.m_129880_(ps.dim);
            if (lvl == null) {
                return null;
            }
            java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
            for (java.util.UUID id : BuildPlan.boundMaidUuids(planId)) {
                if (lvl.m_8791_(id)
                        instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                        && m.m_269323_() != null) {
                    owners.add(m.m_269323_().m_20148_());
                }
            }
            return new Object[]{planId, lvl, BuildPlan.planRegion(ps), owners};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这名玩家与这份快照有关吗：① 站在区块范围内；② 区块里绑着一只归他的女仆。 */
    private static boolean playerInvolved(net.minecraft.server.level.ServerPlayer p, Object[] info) {
        try {
            net.minecraft.server.level.ServerLevel lvl =
                    (net.minecraft.server.level.ServerLevel) info[1];
            int[] r = (int[]) info[2];
            if (lvl == p.m_9236_() && r != null) {
                net.minecraft.core.BlockPos pp = p.m_20183_();
                if (pp.m_123341_() >= r[0] && pp.m_123341_() < r[3]
                        && pp.m_123342_() >= r[1] && pp.m_123342_() < r[4]
                        && pp.m_123343_() >= r[2] && pp.m_123343_() < r[5]) {
                    return true;
                }
            }
            @SuppressWarnings("unchecked")
            java.util.Set<java.util.UUID> owners = (java.util.Set<java.util.UUID>) info[3];
            return owners.contains(p.m_20148_());
        } catch (Throwable ignored) {
            return false;
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
