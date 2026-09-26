package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
/**
 * Promaid 手册网络（v1.5.16）：SimpleChannel，两个包：
 * - S2C OpenBlueprintBookPacket：服务端打开手册时下发蓝图目录（id/名称/描述），
 *   客户端收到后直接打开 BlueprintBookScreen（无需 Menu 容器）
 * - C2S SelectBlueprintPacket：玩家点击目录条目，服务端执行建造
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class BlueprintBookNetworking {
        static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** v1.5.252v：手册速度/ETA 诊断日志节流（每 5 秒一条，latest.log 搜 "hud book"） */
    private static long lastBookLogMs = 0L;

    /** 目录包（73+ 蓝图 × 材料清单 + 200 女仆）可达数百 KB——连接层帧上限 2MB
     *  （Varint21FrameDecoder 2097151），SimpleChannel 默认即可承载，无需额外配置。 */
    
    private BlueprintBookNetworking() {
    }

        @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(BlueprintBookBuildPackets.OpenBlueprintBookPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.OpenBlueprintBookPacket::encode, BlueprintBookBuildPackets.OpenBlueprintBookPacket::decode), BlueprintBookBuildPackets.OpenBlueprintBookPacket::handle);
        r.playToServer(BlueprintBookBuildPackets.SelectBlueprintPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.SelectBlueprintPacket::encode, BlueprintBookBuildPackets.SelectBlueprintPacket::decode), BlueprintBookBuildPackets.SelectBlueprintPacket::handle);
        r.playToServer(BlueprintBookBuildPackets.BuildControlPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.BuildControlPacket::encode, BlueprintBookBuildPackets.BuildControlPacket::decode), BlueprintBookBuildPackets.BuildControlPacket::handle);
        r.playToClient(BlueprintBookBuildPackets.ProgressUpdatePacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.ProgressUpdatePacket::encode, BlueprintBookBuildPackets.ProgressUpdatePacket::decode), BlueprintBookBuildPackets.ProgressUpdatePacket::handle);
        r.playToServer(BlueprintBookEntityPackets.AiMemoryTogglePacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.AiMemoryTogglePacket::encode, BlueprintBookEntityPackets.AiMemoryTogglePacket::decode), BlueprintBookEntityPackets.AiMemoryTogglePacket::handle);
        r.playToServer(BlueprintBookEntityPackets.DeleteBlueprintPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.DeleteBlueprintPacket::encode, BlueprintBookEntityPackets.DeleteBlueprintPacket::decode), BlueprintBookEntityPackets.DeleteBlueprintPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.MemoryViewRequestPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MemoryViewRequestPacket::encode, BlueprintBookEntityPackets.MemoryViewRequestPacket::decode), BlueprintBookEntityPackets.MemoryViewRequestPacket::handle);
        r.playToClient(BlueprintBookEntityPackets.MemoryViewResponsePacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MemoryViewResponsePacket::encode, BlueprintBookEntityPackets.MemoryViewResponsePacket::decode), BlueprintBookEntityPackets.MemoryViewResponsePacket::handle);
        r.playToServer(BlueprintBookEntityPackets.ClearMemoryPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.ClearMemoryPacket::encode, BlueprintBookEntityPackets.ClearMemoryPacket::decode), BlueprintBookEntityPackets.ClearMemoryPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.MaidDebugRequestPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MaidDebugRequestPacket::encode, BlueprintBookEntityPackets.MaidDebugRequestPacket::decode), BlueprintBookEntityPackets.MaidDebugRequestPacket::handle);
        r.playToClient(BlueprintBookEntityPackets.MaidDebugResponsePacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MaidDebugResponsePacket::encode, BlueprintBookEntityPackets.MaidDebugResponsePacket::decode), BlueprintBookEntityPackets.MaidDebugResponsePacket::handle);
        r.playToServer(BlueprintBookEntityPackets.MaidDebugActionPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MaidDebugActionPacket::encode, BlueprintBookEntityPackets.MaidDebugActionPacket::decode), BlueprintBookEntityPackets.MaidDebugActionPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.VoicePackImportPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.VoicePackImportPacket::encode, BlueprintBookEntityPackets.VoicePackImportPacket::decode), BlueprintBookEntityPackets.VoicePackImportPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.VoicePackQueryPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.VoicePackQueryPacket::encode, BlueprintBookEntityPackets.VoicePackQueryPacket::decode), BlueprintBookEntityPackets.VoicePackQueryPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.BuildImportPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.BuildImportPacket::encode, BlueprintBookEntityPackets.BuildImportPacket::decode), BlueprintBookEntityPackets.BuildImportPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.WorldImportPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.WorldImportPacket::encode, BlueprintBookEntityPackets.WorldImportPacket::decode), BlueprintBookEntityPackets.WorldImportPacket::handle);
        r.playToClient(BlueprintBookEntityPackets.MemoryStateSyncPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MemoryStateSyncPacket::encode, BlueprintBookEntityPackets.MemoryStateSyncPacket::decode), BlueprintBookEntityPackets.MemoryStateSyncPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.MemoryStateQueryPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.MemoryStateQueryPacket::encode, BlueprintBookEntityPackets.MemoryStateQueryPacket::decode), BlueprintBookEntityPackets.MemoryStateQueryPacket::handle);
        r.playToClient(BlueprintBookBuildPackets.BuildHudPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.BuildHudPacket::encode, BlueprintBookBuildPackets.BuildHudPacket::decode), BlueprintBookBuildPackets.BuildHudPacket::handle);
        r.playToServer(BlueprintBookBuildPackets.OpenBookRequestPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.OpenBookRequestPacket::encode, BlueprintBookBuildPackets.OpenBookRequestPacket::decode), BlueprintBookBuildPackets.OpenBookRequestPacket::handle);
        r.playToServer(BlueprintBookEntityPackets.AiLlmTogglePacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.AiLlmTogglePacket::encode, BlueprintBookEntityPackets.AiLlmTogglePacket::decode), BlueprintBookEntityPackets.AiLlmTogglePacket::handle);
        r.playToClient(BlueprintBookEntityPackets.LlmStateSyncPacket.TYPE, StreamCodec.ofMember(BlueprintBookEntityPackets.LlmStateSyncPacket::encode, BlueprintBookEntityPackets.LlmStateSyncPacket::decode), BlueprintBookEntityPackets.LlmStateSyncPacket::handle);
        r.playToServer(BlueprintBookBuildPackets.ProjectionRequestPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.ProjectionRequestPacket::encode, BlueprintBookBuildPackets.ProjectionRequestPacket::decode), BlueprintBookBuildPackets.ProjectionRequestPacket::handle);
        r.playToClient(BlueprintBookBuildPackets.ProjectionDataPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.ProjectionDataPacket::encode, BlueprintBookBuildPackets.ProjectionDataPacket::decode), BlueprintBookBuildPackets.ProjectionDataPacket::handle);
        r.playToClient(BlueprintBookBuildPackets.RegionSyncPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.RegionSyncPacket::encode, BlueprintBookBuildPackets.RegionSyncPacket::decode), BlueprintBookBuildPackets.RegionSyncPacket::handle);
        // v1.1.0 实测四百二十：内置日语语音包播放（S2C——只发女仆实体 id + jar 内文件名）
        r.playToClient(BlueprintBookBuildPackets.PlayJarVoicePacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.PlayJarVoicePacket::encode, BlueprintBookBuildPackets.PlayJarVoicePacket::decode), BlueprintBookBuildPackets.PlayJarVoicePacket::handle);
        // v1.1.0 实测四百二十一：冷却可视化（S2C）——复活倒计时 / 回魂符冷却每秒快照
        r.playToClient(BlueprintBookBuildPackets.CooldownHudPacket.TYPE, StreamCodec.ofMember(BlueprintBookBuildPackets.CooldownHudPacket::encode, BlueprintBookBuildPackets.CooldownHudPacket::decode), BlueprintBookBuildPackets.CooldownHudPacket::handle);
    }

    /** v1.1.0 实测四百二十一：把冷却快照发给单个玩家（CooldownHudTracker 用） */
    public static void sendCooldownHud(ServerPlayer player, java.util.List<String[]> entries) {
        try {
            PacketDistributor.sendToPlayer(player, new BlueprintBookBuildPackets.CooldownHudPacket(entries));
        } catch (Exception ignored) {
        }
    }

    /** v1.5.100b：全部女仆列表（记忆页用——不限距离、不限任务）：
     *  {uuid, 名字, 记忆开关 "1"/"0", 段落数}——v1.5.167 加第 4 字段段落数
     *  （记忆调试面板可视化：女仆列表直接显示各自记忆量）
     *  v1.5.178：加第 5/6/7 字段——任务 UID、绑定区块显示名（所在维度计划名）、
     *  建筑状态（建造中/缺料:xx/暂停；非建筑女仆为空）——女仆管理页绑定显示用 */
    public static List<String[]> collectAllMaids(net.minecraft.server.level.ServerLevel level) {
        List<String[]> all = new ArrayList<>();
        java.nio.file.Path memRoot = com.maidsmart.memory.AiMemoryExtractor.memoryRoot(level.getServer());
        // v1.5.180：绑定显示名按【女仆绑定的区块】查（多区块共存）
        java.util.Map<String, Integer> counts = nameCounts(level.getServer());
        for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.EntitySnapshot.of(level)) {
            if (!(e instanceof EntityMaid m) || !m.isAlive()) {
                continue;
            }
            // v1.5.167：段落数（读 store 计数；失败给 "?" 不阻塞列表——只在打开手册时
            // 读一次，AiMemoryStore 有 WeakHashMap 缓存，后续请求零开销）
            String count = "?";
            try {
                count = String.valueOf(com.maidsmart.soul.SoulBindingService.storeFor(m, level).paragraphs().size());
            } catch (Exception ignored) {
            }
            // v1.5.178：任务 UID + 绑定区块显示名 + 建筑状态（女仆管理页绑定/解绑用）
            String taskUid = m.getTask() == null || m.getTask().getUid() == null
                    ? "" : m.getTask().getUid().getPath();
            String bindName = "";
            String pid = BuildPlan.getBoundPlanId(m);
            if (pid != null) {
                BuildPlan.PlanState bps = BuildPlan.getPlanById(pid);
                if (bps != null && bps.dim.equals(m.level().dimension())) {
                    bindName = displayName(counts, bps);
                }
            }
            String bState = "";
            if ("build".equals(taskUid)) {
                if (BuildPlan.isMaidPaused(m)) {
                    bState = "暂停";
                } else {
                    String miss = MaidBuildBehavior.lastMissing(m);
                    bState = miss != null ? "缺料:" + miss.replace("minecraft:", "") : "建造中";
                }
            }
            all.add(new String[]{m.getUUID().toString(), m.getDisplayName().getString(),
                    com.maidsmart.memory.AiMemoryManager.isEnabled(m) ? "1" : "0", count,
                    taskUid, bindName, bState,
                    BuildPlan.isExplicitForeman(m) ? "1" : "0",
                    BuildPlan.getBoundPlanId(m) == null ? "" : BuildPlan.getBoundPlanId(m),
                    // v1.0.3：第 10 字段 = per-maid LLM 开关（女仆记忆页「LLM:开/关」）
                    com.maidsmart.memory.LlmEnableManager.isEnabled(m) ? "1" : "0"});
            if (all.size() >= 200) {
                break; // 极端数量保护
            }
        }
        all.sort(java.util.Comparator.comparing(a -> a[1])); // 按名字排序，查找稳定
        return all;
    }

    /** v1.5.178：维度中文名（区块列表显示用） */
    private static String dimName(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        if (dim == null) {
            return "?";
        }
        String path = dim.location().getPath();
        switch (path) {
            case "overworld":
                return "主世界";
            case "the_nether":
                return "下界";
            case "the_end":
                return "末地";
            default:
                return path;
        }
    }

    /** v1.5.178：任务 UID → 中文名（女仆管理页显示用） */
    public static String taskNameCn(String uid) {
        if (uid == null || uid.isEmpty()) {
            return "空闲";
        }
        switch (uid) {
            case "build":
                return "建筑";
            case "mine":
                return "挖矿";
            case "cook":
                return "烧制"; // v1.1.0 实测一百六十一：烹饪改名
            case "brew":
                return "酿造";
            case "attack":
                return "攻击";
            case "ranged_attack":
                return "弓击";
            case "crossbow_attack":
                return "弩击";
            case "trident_attack":
                return "三叉戟";
            case "danmaku_attack":
                return "弹幕";
            case "idle":
                return "空闲";
            case "follow":
                return "跟随";
            case "farm":
                return "农务";
            default:
                return uid;
        }
    }

    /**
     * v1.5.180：所有有效建造区块（跨维度展平，每区块一行）→
     * {planId, 显示名, 维度名, 状态, x, y, z, 宽W, 高H, 深D, blueprintId, 创建X, 创建Y, 创建Z}。
     * 显示名按建筑名去重编号：同名区块 → 「小木屋」「小木屋2」「小木屋3」。
     * 尺寸为方块范围（客户端据此判定玩家在哪个区块内）。
     * v1.5.279：追加【创建坐标】= 玩家创建区块时的原点（PlanState.origin，玩家
     * 站的位置）——与 box min（r[0..2]，蓝图包围盒）不同，用于区块打标签显示。 */
    public static List<String[]> collectBuildRegions(net.minecraft.server.MinecraftServer server) {
        List<String[]> regions = new ArrayList<>();
        if (server == null) {
            return regions;
        }
        java.util.Map<String, Integer> counts = nameCounts(server);
        for (net.minecraft.server.level.ServerLevel lv : server.getAllLevels()) {
            for (BuildPlan.PlanState ps : BuildPlan.getPlans(lv)) {
                int[] r = BuildPlan.planRegion(ps);
                if (r == null) {
                    continue;
                }
                String display = displayName(counts, ps);
                String status = ps.paused ? "暂停中" : "建造中";
                // v1.5.188：x/y/z 用区块 box 的 min 角（r[0..2]，planRegion 精确范围）——
                // 旧版用 origin（蓝图中心）导致红色区块框整体偏移、实际搭建"超出区块"，
                // 客户端 inPlanRegion 判定也跟着错（区块内右击跳转不到详情页）
                regions.add(new String[]{ps.planId, display, dimName(lv.dimension()), status,
                        String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[2]),
                        String.valueOf(r[3] - r[0]), String.valueOf(r[4] - r[1]), String.valueOf(r[5] - r[2]),
                        ps.blueprintId,
                        String.valueOf(ps.origin.getX()),
                        String.valueOf(ps.origin.getY()),
                        String.valueOf(ps.origin.getZ()),
                        // v1.1.0 实测九十七：r[14] 朝向（0~3 × 90°）——客户端橙影按 id#quarters 取点云
                        String.valueOf(ps.quarters)});
            }
        }
        return regions;
    }

    /** v1.5.180：跨维度同名建筑编号统计（「X」「X2」「X3」） */
    private static java.util.Map<String, Integer> nameCounts(net.minecraft.server.MinecraftServer server) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        if (server == null) {
            return m;
        }
        for (net.minecraft.server.level.ServerLevel lv : server.getAllLevels()) {
            for (BuildPlan.PlanState ps : BuildPlan.getPlans(lv)) {
                String base = ps.name;
                if (base == null || base.isEmpty()) {
                    base = "未命名";
                }
                m.merge(base, 1, Integer::sum);
            }
        }
        return m;
    }

    /** v1.5.180：区块显示名（按统计编号） */
    private static String displayName(java.util.Map<String, Integer> counts, BuildPlan.PlanState ps) {
        String base = ps.name;
        if (base == null || base.isEmpty()) {
            base = "未命名";
        }
        int c = counts.getOrDefault(base, 1);
        return base + (c > 1 ? String.valueOf(c) : "");
    }

    /** v1.5.43：周围建造女仆状态行 {uuid, 名字, 状态}（状态：建造中/缺料:xx/暂停/待命） */
    public static List<String[]> collectMaidStatus(ServerPlayer player) {
        List<String[]> maids = new ArrayList<>();
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(128.0);
        for (EntityMaid m : player.level().getEntitiesOfClass(EntityMaid.class, box)) {
            if (!BlueprintBuildExecutor.isBuildingTask(m)) {
                continue;
            }
            String status;
            if (BuildPlan.isMaidPaused(m)) {
                status = "暂停";
            } else {
                String missing = MaidBuildBehavior.lastMissing(m);
                if (missing != null) {
                    status = "缺料:" + missing.replace("minecraft:", "");
                } else {
                    status = "建造中";
                }
            }
            maids.add(new String[]{m.getUUID().toString(), m.getDisplayName().getString(), status,
                    BuildPlan.isExplicitForeman(m) ? "1" : "0"}); // v1.5.69：工头标记（v1.5.72 严格判断，无工头时无人标记）
            // v1.5.63：女仆管理面板上限（配置面板建造页可调，v1.0.4 起服务端也读
            // 配置——旧版服务端硬编码 30，调大 BUILD_MAX_MAIDS 无效）
            int maxMaids = Math.max(1, com.maidsmart.config.MaidSmartConfig.BUILD_MAX_MAIDS.get());
            if (maids.size() >= maxMaids) {
                break;
            }
        }
        return maids;
    }

    /** v1.5.162：当前进行中计划的区块标记（{x,y,z,宽,高,深}；无计划时 x = Integer.MIN_VALUE）。
     * v1.5.180：多区块共存——客户端判定改用 regions 列表（带尺寸），此单值方法
     * 保留仅作兼容（返回玩家所在区块或第一个区块）。
     */
    public static int[] collectRegion(net.minecraft.server.level.ServerLevel level) {
        int[] r = {Integer.MIN_VALUE, 0, 0, 0, 0, 0};
        if (level == null) {
            return r;
        }
        List<BuildPlan.PlanState> plans = BuildPlan.getPlans(level);
        if (plans.isEmpty()) {
            return r;
        }
        BuildPlan.PlanState ps = plans.get(0);
        int[] reg = BuildPlan.planRegion(ps);
        if (reg == null) {
            return r;
        }
        r[0] = ps.origin.getX();
        r[1] = ps.origin.getY();
        r[2] = ps.origin.getZ();
        r[3] = reg[3] - reg[0];
        r[4] = reg[4] - reg[1];
        r[5] = reg[5] - reg[2];
        return r;
    }

    /** v2.0：玩家是否位于当前计划区块内（v1.5.180：多区块——任一区块命中即 true） */
    public static boolean playerInPlanRegion(net.minecraft.server.level.ServerLevel level,
                                             net.minecraft.world.entity.player.Player player) {
        return findPlayerPlan(level, player) != null;
    }

    /**
     * v1.5.180：玩家所在区块（多区块共存——遍历该维度所有区块，范围公式与
     * 客户端一致：[ox-W/2, ox-W/2+W) × [oy, oy+H) × [oz-D/2, oz-D/2+D)）。
     * 不在任何区块内返回 null。
     */
    public static BuildPlan.PlanState findPlayerPlan(net.minecraft.server.level.ServerLevel level,
                                                     net.minecraft.world.entity.player.Player player) {
        if (level == null || player == null) {
            return null;
        }
        net.minecraft.core.BlockPos p = player.blockPosition();
        for (BuildPlan.PlanState ps : BuildPlan.getPlans(level)) {
            int[] r = BuildPlan.planRegion(ps);
            if (r == null) {
                continue;
            }
            int x0 = r[0];
            int z0 = r[2];
            if (p.getX() >= x0 && p.getX() < r[3]
                    && p.getY() >= r[1] && p.getY() < r[4]
                    && p.getZ() >= z0 && p.getZ() < r[5]) {
                return ps;
            }
        }
        return null;
    }

    /** v1.5.180：玩家所在区块的蓝图 id（区块外返回 null；客户端据此定位详情页条目） */
    public static String currentPlanId(net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.world.entity.player.Player player) {
        BuildPlan.PlanState ps = findPlayerPlan(level, player);
        return ps == null ? null : ps.blueprintId;
    }

    /** v1.5.43：玩家附近最近的女仆（SelectBlueprintPacket 与 BuildControlPacket 共用） */
    private static EntityMaid findMaidNear(ServerPlayer player) {
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(32.0);
        List<EntityMaid> maids = player.level().getEntitiesOfClass(EntityMaid.class, box);
        EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityMaid m : maids) {
            double d = m.position().distanceTo(player.position());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    public static void sendToPlayer(ServerPlayer player, BlueprintBookBuildPackets.OpenBlueprintBookPacket pkt) {
        PacketDistributor.sendToPlayer(player, pkt);
    }

    /** v1.5.62：手册面板进度行文本（v1.5.180：按玩家所在区块；区块外 = 空）。
     *  v1.5.179：实时缺料统计需玩家背包 */
    public static String buildProgressText(net.minecraft.server.level.ServerLevel level,
                                           net.minecraft.world.entity.player.Player owner) {
        BuildPlan.PlanState ps = findPlayerPlan(level, owner);
        return ps == null ? "" : BuildPlan.statusText(level, ps, owner);
    }

    /** v1.5.65：进度百分比（v1.5.180：按玩家所在区块；-1 = 区块外）——进度条绘制用 */
    public static int buildProgressPct(net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.world.entity.player.Player player) {
        BuildPlan.PlanState ps = findPlayerPlan(level, player);
        return ps == null ? -1 : BuildPlan.progressPct(ps);
    }

    /** v1.5.252ad：打开手册速度诊断日志（latest.log 搜 "hud book"，BlueprintBookItem 调用） */
    public static void logBookSpeed(String tag, String planId, String speedBps, int etaSec) {
        LOGGER.info("hud book: {} plan={} speedBps={} etaSec={}",
                tag, planId == null ? "null" : planId,
                speedBps == null || speedBps.isEmpty() ? "(空)" : speedBps, etaSec);
    }

    /** v1.5.62：控制操作后回发状态快照（客户端面板即时刷新） */
    public static void sendProgressUpdate(ServerPlayer player) {        if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        // v1.5.162：计划区块标记（中心点 + 尺寸，兼容字段）
        int[] r = collectRegion(level);
        // v1.5.180：按玩家所在区块发状态（区块外 → 空进度文本 + planId=null）
        BuildPlan.PlanState ps = findPlayerPlan(level, player);
        // v1.5.252s：进度条旁显示 块/秒 + 预计完成时间（复用 HUD 统计）
        double[] se = ps == null ? null : com.maidsmart.build.BuildHudTracker.speedEtaOf(ps.planId);
        int etaSec = se == null ? -1 : (int) Math.round(se[1]);
        String speedBps = se == null ? "" : String.format("%.1f", se[0]);
        // v1.5.252v：限频诊断（每 5 秒一条）——验证手册收到的速度/ETA 值
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBookLogMs > 5000L) {
            lastBookLogMs = nowMs;
            LOGGER.info("hud book: plan={} speedBps={} etaSec={}",
                    ps == null ? "null" : ps.planId, speedBps.isEmpty() ? "(空)" : speedBps, etaSec);
        }        // v1.5.178：全部女仆 + 有效建造区块（女仆管理页轮询刷新）
        PacketDistributor.sendToPlayer(player, new BlueprintBookBuildPackets.ProgressUpdatePacket(
                        ps == null ? "" : BuildPlan.statusText(level, ps, player),
                        collectMaidStatus(player),
                        ps != null && ps.paused, MaidBuildBehavior.speedLabel(),
                        ps == null ? -1 : BuildPlan.progressPct(ps),
                        r[0], r[1], r[2], r[3], r[4], r[5],
                        collectAllMaids(level), collectBuildRegions(level.getServer()),
                        ps == null ? null : ps.planId, etaSec, speedBps));
    }

    /** v1.5.94：重发完整目录包（删除蓝图后刷新手册目录用，构建逻辑与手册右键一致） */
    public static void sendCatalog(ServerPlayer player) {
        if (player == null) {
            return;
        }
        List<BlueprintBookBuildPackets.Entry> entries = new ArrayList<>();
        for (Map.Entry<String[], Map<String, int[]>> e
                : BlueprintLib.buildCatalogEntriesWithMaterials(player).entrySet()) {
            String[] base = e.getKey();
            // v1.5.159：占地尺寸（区块显示预览用）——v1.5.375 走缓存（启动预热）
            int[] size = BlueprintLib.blueprintSizeCached(base[0], BlueprintLib.getBlueprint(base[0]));
            entries.add(new BlueprintBookBuildPackets.Entry(base[0], base[1], base[2],
                    new ArrayList<>(e.getValue().entrySet().stream()
                            .map(m -> new String[]{m.getKey(), String.valueOf(m.getValue()[0]), String.valueOf(m.getValue()[1])})
                            .toList()),
                    size[0], size[1], size[2]));
        }
        net.minecraft.server.level.ServerLevel sl = player.level() instanceof net.minecraft.server.level.ServerLevel l
                ? l : null;
        // v1.5.162：计划区块标记（中心点 + 尺寸，兼容字段）
        int[] region = collectRegion(sl);
        // v1.5.180：玩家所在区块（区块内右击 → 详情页；区块外 → 目录）
        BuildPlan.PlanState here = sl == null ? null : findPlayerPlan(sl, player);
        // v1.5.252z：打开手册立即显示速度/ETA（不等 2 秒轮询）
        double[] se = here == null ? null : com.maidsmart.build.BuildHudTracker.speedEtaOf(here.planId);
        int openEta = se == null ? -1 : (int) Math.round(se[1]);
        String openBps = se == null ? "" : String.format("%.1f", se[0]);
        PacketDistributor.sendToPlayer(player, new BlueprintBookBuildPackets.OpenBlueprintBookPacket(entries, collectMaidStatus(player),
                        sl == null ? new ArrayList<>() : collectAllMaids(sl),
                        here != null && here.paused, MaidBuildBehavior.speedLabel(),
                        sl == null ? "" : buildProgressText(sl, player),
                        sl == null ? -1 : buildProgressPct(sl, player),
                        region[0], region[1], region[2], region[3], region[4], region[5],
                        here != null, here == null ? null : here.blueprintId,
                        sl == null ? new ArrayList<>() : collectBuildRegions(sl.getServer()),
                        openEta, openBps, 0));
    }

    // ==================== v1.0.3:per-maid 大语言模型开关 ====================

    /** v1.5.95：服务端收集女仆记忆为文本行（段落/关系/画像/开关）。
     *  v1.5.167：加记忆系统调试块（存储目录/统计/来源分布）+ 段落重要度颜色分级
     *  （可视化：金色 ≥8 / 绿色 ≥5 / 灰色 <5）+ 访问次数——"记忆调试面板"。 */
    static List<String> collectMemoryLines(EntityMaid maid, net.minecraft.server.level.ServerLevel level) {
        List<String> lines = new ArrayList<>();
        boolean enabled = com.maidsmart.memory.AiMemoryManager.isEnabled(maid);
        // v1.5.242：手册记忆页显示诊断（每次打开）——验证服务端真实开关状态，
        // 与 persistentData/磁盘备份的读取结果对得上
        org.slf4j.LoggerFactory.getLogger(BlueprintBookNetworking.class)
                .info("memory display: maid={} enabled={}",
                        maid.getDisplayName() != null ? maid.getDisplayName().getString() : "?", enabled);
        // v1.5.100b：开关切换已在本页（女仆记忆列表行的"记忆"按钮），不再把提示塞进记忆行
        lines.add((enabled ? "\u00a7a记忆开启" : "\u00a77记忆关闭"));
        // v1.5.228：记忆关闭时【不显示任何记忆内容】——"关了但记忆还在列表里"的
        // 直接体感来源；只保留开关行 + 提示 + 清空入口说明
        if (!enabled) {
            lines.add("\u00a77（记忆已关闭——在女仆列表点\u00a7a记忆:开\u00a77可重新开启；");
            lines.add("\u00a77\u00a7 已有记忆不会自动删除，可在本页使用\u00a7c清空记忆\u00a77）");
            return lines;
        }
        com.maidsmart.memory.AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        // v1.5.167：调试块——存储目录（世界目录/promaid_memory/<uuid>，过长截断）+ 统计
        String root = com.maidsmart.memory.AiMemoryExtractor.memoryRoot(level.getServer())
                .resolve(maid.getUUID().toString()).toString().replace('\\', '/');
        if (root.length() > 64) {
            root = "…" + root.substring(root.length() - 64);
        }
        lines.add("\u00a78[调试]\u00a77 存储: " + root);
        List<com.maidsmart.memory.AiMemoryModels.Paragraph> paras =
                new ArrayList<>(store.paragraphs());
        List<com.maidsmart.memory.AiMemoryModels.Relation> rels =
                new ArrayList<>(store.relations());
        List<com.maidsmart.memory.AiMemoryModels.Profile> profs =
                new ArrayList<>(store.profiles());
        List<com.maidsmart.memory.AiMemoryModels.Episode> eps =
                new ArrayList<>(store.episodes());
        int permanent = 0;
        int deleted = 0;
        for (com.maidsmart.memory.AiMemoryModels.Paragraph p : paras) {
            if (p.isPermanent()) {
                permanent++;
            }
            if (p.deleted()) {
                deleted++;
            }
        }
        StringBuilder stat = new StringBuilder("\u00a78[调试]\u00a77 统计: 段落 " + paras.size());
        if (permanent > 0) {
            stat.append("（永久").append(permanent).append("）");
        }
        if (deleted > 0) {
            stat.append("（已删").append(deleted).append("）");
        }
        stat.append(" · 关系 ").append(rels.size())
                .append(" · 画像 ").append(profs.size())
                .append(" · 片段 ").append(eps.size());
        lines.add(stat.toString());
        // 来源分布（sourceType 计数 top5——看记忆是从哪积累的：聊天/喂食/好感等）
        java.util.Map<String, Integer> srcCount = new java.util.LinkedHashMap<>();
        for (com.maidsmart.memory.AiMemoryModels.Paragraph p : paras) {
            if (p.deleted()) {
                continue;
            }
            String s = p.sourceType() == null || p.sourceType().isEmpty()
                    ? "未知" : p.sourceType();
            srcCount.merge(s, 1, Integer::sum);
        }
        if (!srcCount.isEmpty()) {
            List<java.util.Map.Entry<String, Integer>> top =
                    new ArrayList<>(srcCount.entrySet());
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder("\u00a78[调试]\u00a77 来源: ");
            for (int i = 0; i < top.size() && i < 5; i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                sb.append(top.get(i).getKey()).append("×").append(top.get(i).getValue());
            }
            lines.add(sb.toString());
        }
        // v1.5.382：记忆日记（多级记忆索引——日/3日/周/月日记式摘要，最新 6 篇）
        if (com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            List<com.maidsmart.memory.AiMemoryIndexStore.IndexRecord> idx =
                    new ArrayList<>(store.index().all());
            if (idx.isEmpty()) {
                lines.add("\u00a7d记忆日记\u00a77（暂无——睡一觉后自动整理生成）");
            } else {
                idx.sort((a, b) -> Long.compare(b.endTick(), a.endTick()));
                lines.add("\u00a7d记忆日记\u00a77（共" + idx.size() + " 篇，最新：）");
                for (int i = 0; i < idx.size() && i < 6; i++) {
                    com.maidsmart.memory.AiMemoryIndexStore.IndexRecord r = idx.get(i);
                    lines.add("\u00a7d  [" + r.level() + "记 第" + r.startDay() + "~" + r.endDay()
                            + "天]\u00a7f " + com.maidsmart.memory.AiMemoryModels.clip(r.content(), 70));
                }
            }
        }
        // 关系（按置信度降序）
        rels.sort(java.util.Comparator.comparingDouble(
                com.maidsmart.memory.AiMemoryModels.Relation::confidence).reversed());
        for (com.maidsmart.memory.AiMemoryModels.Relation r : rels) {
            if (!r.inactive()) {
                lines.add("\u00a7d\u5173\u7cfb\u00a7f " + r.subject() + r.predicate() + r.object()
                        + "（\u7f6e\u4fe1\u5ea6" + String.format(java.util.Locale.ROOT, "%.1f", r.confidence()) + "）");
            }
        }
        // 段落（按重要度降序；v1.5.167：颜色分级可视化 + 访问次数）
        paras.sort(java.util.Comparator
                .comparingInt(com.maidsmart.memory.AiMemoryModels.Paragraph::salience).reversed()
                .thenComparingLong(com.maidsmart.memory.AiMemoryModels.Paragraph::lastAccessed));
        for (com.maidsmart.memory.AiMemoryModels.Paragraph p : paras) {
            String color = p.salience() >= 8 ? "\u00a7e"
                    : (p.salience() >= 5 ? "\u00a7a" : "\u00a77");
            // v1.5.382：【长期】标记（long_term——年龄+重要度达标沉淀的长期记忆）
            String tag = (p.isPermanent() ? "\u00a7b\u3010\u6c38\u4e45\u3011" : "")
                    + (p.tags().contains("long_term") ? "\u00a7d\u3010\u957f\u671f\u3011" : "");
            String acc = p.accessCount() > 0
                    ? "\u00a78·\u8bbf\u95ee" + p.accessCount() + "\u6b21 " : "";
            lines.add(tag + color + "[\u91cd\u8981\u5ea6" + p.salience() + "] \u00a7f"
                    + p.content() + acc
                    // v1.5.251：来源世界 + 获得时间
                    + com.maidsmart.memory.AiMemoryModels.memoryMeta(p));
        }
        // 画像
        for (com.maidsmart.memory.AiMemoryModels.Profile pr : profs) {
            lines.add("\u00a7a\u753b\u50cf\u00a7f " + pr.profileText());
        }
        if (lines.size() <= 1) {
            lines.add("\u00a77\uff08\u8fd8\u6ca1\u6709\u8bb0\u5fc6\u2014\u2014\u591a\u8ddf\u5979\u804a\u5929\u3001\u5582\u98df\u3001\u597d\u611f\u5347\u7ea7\u4f1a\u81ea\u52a8\u79ef\u7d2f\uff09");
        }
        return lines;
    }

    // ================= v1.5.192 女仆工作链路调试（Promaid 手册） =================

    /**
     * v1.5.192：服务端收集"工作链路 + 各项状况"调试快照为文本行。
     * 链路：任务 / 日程 / 拾取 / 卡住原因；状态：血 / 好感 / 位置 / 背包；
     * 情绪：PAD + 亲密冲突思念；记忆统计；主动对话阶段；工作笔记；绑定区块。
     */
    private static List<String> collectDebugStatus(EntityMaid maid, net.minecraft.server.level.ServerLevel level) {
        List<String> lines = new ArrayList<>();
        try {
            // 工作链路
            String taskUid = "（空闲）";
            if (maid.getTask() != null && maid.getTask().getUid() != null) {
                taskUid = maid.getTask().getUid().getPath();
            }
            String reason = com.maidsmart.dialogue.WorkStatusReporter.reasonOf(maid);
            lines.add("工作链路: 任务=" + taskUid
                    + " · 日程=" + maid.getSchedule()
                    + " · 拾取=" + (maid.isPickup() ? "开" : "关")
                    + (reason != null ? " · 卡住原因=" + reason : " · 状态=干活中"));
            // 状态
            int favor = maid.getFavorability();
            int flv = favor < 64 ? 0 : (favor < 192 ? 1 : (favor < 384 ? 2 : 3));
            net.minecraft.core.BlockPos pos = maid.blockPosition();
            String dim = maid.level().dimension().location().getPath();
            int invSlots = 0;
            int invUsed = 0;
            try {
                net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
                if (inv != null) {
                    invSlots = inv.getSlots();
                    for (int i = 0; i < invSlots; i++) {
                        if (inv.getStackInSlot(i) != null && !inv.getStackInSlot(i).isEmpty()) {
                            invUsed++;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            lines.add("状态: 血量=" + String.format(java.util.Locale.ROOT, "%.0f", maid.getHealth())
                    + "/" + String.format(java.util.Locale.ROOT, "%.0f", maid.getMaxHealth())
                    + " · 好感=" + flv + "级(" + favor + "/384)"
                    + " · 位置=" + dim + "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")"
                    + " · 背包=" + invUsed + "/" + invSlots);
            // 情绪（PAD + 关系维度 + 修复债）
            try {
                lines.add("情绪: " + com.maidsmart.affect.AffectManager.render(maid));
            } catch (Exception ignored) {
            }
            // 记忆统计
            com.maidsmart.memory.AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
            List<com.maidsmart.memory.AiMemoryModels.Paragraph> paras = store.paragraphs();
            int permanent = 0, deleted = 0;
            for (com.maidsmart.memory.AiMemoryModels.Paragraph p : paras) {
                if (p.isPermanent()) {
                    permanent++;
                }
            }
            com.maidsmart.memory.AiMemoryModels.Meta meta = store.meta();
            lines.add("记忆: " + (com.maidsmart.memory.AiMemoryManager.isEnabled(maid) ? "开" : "关")
                    + " · 段落=" + paras.size() + "（永久" + permanent + "）"
                    + " · 关系=" + store.relations().size()
                    + " · 画像=" + store.profiles().size()
                    + " · 片段=" + store.episodes().size()
                    + " · 提取水位=" + meta.lastExtractedTime());
            // 主动对话阶段
            lines.add(com.maidsmart.dialogue.ProactiveDialogueManager.stageInfo(maid));
            // v1.2.1：人格 / 人设统一（TLM 原版人设检测 → 补充/完整模式）
            try {
                boolean pOn = com.maidsmart.config.MaidSmartConfig.MEMORY_PERSONA.get();
                String pname = com.maidsmart.persona.PersonaPackage.personaName(store.dir());
                int pcore = com.maidsmart.persona.PersonaPackage.coreMemoryCount(store.dir());
                StringBuilder pLine = new StringBuilder("人格: ");
                if (!pOn) {
                    pLine.append("关");
                } else if (pname == null) {
                    pLine.append("未生成");
                } else {
                    pLine.append(pname).append(" · 核心记忆").append(pcore).append("条");
                    if (com.maidsmart.config.MaidSmartConfig.MEMORY_PERSONA_UNIFY.get()) {
                        pLine.append(" · TLM人设=")
                                .append(com.maidsmart.memory.AiMemoryContext.tlmHasPersona(maid) ? "补充" : "完整");
                    }
                }
                lines.add(pLine.toString());
            } catch (Exception ignored) {
            }
            // v1.1.0：双 agent 提取 + 每日关心点
            try {
                boolean dual = com.maidsmart.config.MaidSmartConfig.MEMORY_DUAL_AGENT.get();
                boolean extracting = com.maidsmart.memory.AiMemoryExtractor.isExtracting(maid.getUUID());
                int cares = com.maidsmart.memory.CarePointGenerator.generate(store,
                        com.maidsmart.affect.AffectManager.load(maid)).size();
                lines.add("双agent: " + (dual ? "开" : "关") + " · 提取中=" + (extracting ? "是" : "否")
                        + " · 关心点" + cares + "条");
            } catch (Exception ignored) {
            }
            // 工作笔记
            String note = com.maidsmart.memory.WorkingNoteTool.readNote(maid);
            if (!note.isBlank()) {
                lines.add("工作笔记: " + com.maidsmart.memory.AiMemoryModels.clip(note, 80));
            }
            // 绑定区块/建筑状态（从 allMaids 冗余字段读不到——直接查 BuildPlan）
            String pid = com.maidsmart.build.BuildPlan.getBoundPlanId(maid);
            if (pid != null) {
                lines.add("绑定区块: " + pid);
            }
        } catch (Exception e) {
            lines.add("调试快照收集失败: " + e.getClass().getSimpleName());
        }
        return lines;
    }

    /** v1.5.192：收集可调试记忆对象行 {type, key, info, text}：
     *  段落（type=para，key=hash，info=重要度/访问/tags）按重要度降序
     *  + 关系（type=rel，key=predicate，info=置信度）按置信度降序 */
    private static List<String[]> collectDebugRows(EntityMaid maid, net.minecraft.server.level.ServerLevel level) {
        List<String[]> rows = new ArrayList<>();
        try {
            com.maidsmart.memory.AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
            List<com.maidsmart.memory.AiMemoryModels.Paragraph> paras =
                    new ArrayList<>(store.paragraphs());
            paras.sort(java.util.Comparator
                    .comparingInt(com.maidsmart.memory.AiMemoryModels.Paragraph::salience).reversed()
                    .thenComparingLong(com.maidsmart.memory.AiMemoryModels.Paragraph::lastAccessed));
            for (com.maidsmart.memory.AiMemoryModels.Paragraph p : paras) {
                rows.add(new String[]{"para", p.hash(),
                        "重要度" + p.salience() + "·访问" + p.accessCount() + "·" + (p.tags() == null ? "" : p.tags()),
                        (p.isPermanent() ? "[永久] " : "") + p.content()});
            }
            List<com.maidsmart.memory.AiMemoryModels.Relation> rels =
                    new ArrayList<>(store.relations());
            rels.sort(java.util.Comparator.comparingDouble(
                    com.maidsmart.memory.AiMemoryModels.Relation::confidence).reversed());
            for (com.maidsmart.memory.AiMemoryModels.Relation r : rels) {
                if (r.inactive()) {
                    continue;
                }
                rows.add(new String[]{"rel", r.predicate(),
                        "置信度" + String.format(java.util.Locale.ROOT, "%.1f", r.confidence()),
                        r.subject() + r.predicate() + r.object()});
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

    /** v1.5.192：回发调试快照（请求/动作成功后共用） */
    static void sendDebugResponse(ServerPlayer player, EntityMaid maid,
                                          net.minecraft.server.level.ServerLevel level, String uuid) {
        PacketDistributor.sendToPlayer(player, new BlueprintBookEntityPackets.MaidDebugResponsePacket(uuid,
                        collectDebugStatus(maid, level),
                        collectDebugRows(maid, level)));
    }

    // ==================== v1.1.0 实测八十二：蓝图投影 ====================

    /**
     * v1.1.0 实测九十五：向全体玩家广播当前建造区块行（ProMaidExtension 每秒
     * HUD 广播块内调用）。计划数量通常个位数、每行 14 个短串，全量广播开销可忽略；
     * 无计划时发空列表让客户端清掉残留的框与投影。
     */
    public static void broadcastRegionSync(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        BlueprintBookBuildPackets.RegionSyncPacket pkt = new BlueprintBookBuildPackets.RegionSyncPacket(collectBuildRegions(server));
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                PacketDistributor.sendToPlayer(p, pkt);
            } catch (Exception ignored) {
            }
        }
    }
}
