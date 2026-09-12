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
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** v1.5.252v：手册速度/ETA 诊断日志节流（每 5 秒一条，latest.log 搜 "hud book"） */
    private static long lastBookLogMs = 0L;

    /** 目录包（73+ 蓝图 × 材料清单 + 200 女仆）可达数百 KB——连接层帧上限 2MB
     *  （Varint21FrameDecoder 2097151），SimpleChannel 默认即可承载，无需额外配置。 */
    
    private BlueprintBookNetworking() {
    }

        @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenBlueprintBookPacket.TYPE, StreamCodec.ofMember(OpenBlueprintBookPacket::encode, OpenBlueprintBookPacket::decode), OpenBlueprintBookPacket::handle);
        r.playToServer(SelectBlueprintPacket.TYPE, StreamCodec.ofMember(SelectBlueprintPacket::encode, SelectBlueprintPacket::decode), SelectBlueprintPacket::handle);
        r.playToServer(BuildControlPacket.TYPE, StreamCodec.ofMember(BuildControlPacket::encode, BuildControlPacket::decode), BuildControlPacket::handle);
        r.playToClient(ProgressUpdatePacket.TYPE, StreamCodec.ofMember(ProgressUpdatePacket::encode, ProgressUpdatePacket::decode), ProgressUpdatePacket::handle);
        r.playToServer(AiMemoryTogglePacket.TYPE, StreamCodec.ofMember(AiMemoryTogglePacket::encode, AiMemoryTogglePacket::decode), AiMemoryTogglePacket::handle);
        r.playToServer(DeleteBlueprintPacket.TYPE, StreamCodec.ofMember(DeleteBlueprintPacket::encode, DeleteBlueprintPacket::decode), DeleteBlueprintPacket::handle);
        r.playToServer(MemoryViewRequestPacket.TYPE, StreamCodec.ofMember(MemoryViewRequestPacket::encode, MemoryViewRequestPacket::decode), MemoryViewRequestPacket::handle);
        r.playToClient(MemoryViewResponsePacket.TYPE, StreamCodec.ofMember(MemoryViewResponsePacket::encode, MemoryViewResponsePacket::decode), MemoryViewResponsePacket::handle);
        r.playToServer(ClearMemoryPacket.TYPE, StreamCodec.ofMember(ClearMemoryPacket::encode, ClearMemoryPacket::decode), ClearMemoryPacket::handle);
        r.playToServer(MaidDebugRequestPacket.TYPE, StreamCodec.ofMember(MaidDebugRequestPacket::encode, MaidDebugRequestPacket::decode), MaidDebugRequestPacket::handle);
        r.playToClient(MaidDebugResponsePacket.TYPE, StreamCodec.ofMember(MaidDebugResponsePacket::encode, MaidDebugResponsePacket::decode), MaidDebugResponsePacket::handle);
        r.playToServer(MaidDebugActionPacket.TYPE, StreamCodec.ofMember(MaidDebugActionPacket::encode, MaidDebugActionPacket::decode), MaidDebugActionPacket::handle);
        r.playToServer(VoicePackImportPacket.TYPE, StreamCodec.ofMember(VoicePackImportPacket::encode, VoicePackImportPacket::decode), VoicePackImportPacket::handle);
        r.playToServer(VoicePackQueryPacket.TYPE, StreamCodec.ofMember(VoicePackQueryPacket::encode, VoicePackQueryPacket::decode), VoicePackQueryPacket::handle);
        r.playToServer(BuildImportPacket.TYPE, StreamCodec.ofMember(BuildImportPacket::encode, BuildImportPacket::decode), BuildImportPacket::handle);
        r.playToServer(WorldImportPacket.TYPE, StreamCodec.ofMember(WorldImportPacket::encode, WorldImportPacket::decode), WorldImportPacket::handle);
        r.playToClient(MemoryStateSyncPacket.TYPE, StreamCodec.ofMember(MemoryStateSyncPacket::encode, MemoryStateSyncPacket::decode), MemoryStateSyncPacket::handle);
        r.playToServer(MemoryStateQueryPacket.TYPE, StreamCodec.ofMember(MemoryStateQueryPacket::encode, MemoryStateQueryPacket::decode), MemoryStateQueryPacket::handle);
        r.playToClient(BuildHudPacket.TYPE, StreamCodec.ofMember(BuildHudPacket::encode, BuildHudPacket::decode), BuildHudPacket::handle);
        r.playToServer(OpenBookRequestPacket.TYPE, StreamCodec.ofMember(OpenBookRequestPacket::encode, OpenBookRequestPacket::decode), OpenBookRequestPacket::handle);
        r.playToServer(AiLlmTogglePacket.TYPE, StreamCodec.ofMember(AiLlmTogglePacket::encode, AiLlmTogglePacket::decode), AiLlmTogglePacket::handle);
        r.playToClient(LlmStateSyncPacket.TYPE, StreamCodec.ofMember(LlmStateSyncPacket::encode, LlmStateSyncPacket::decode), LlmStateSyncPacket::handle);
        r.playToServer(ProjectionRequestPacket.TYPE, StreamCodec.ofMember(ProjectionRequestPacket::encode, ProjectionRequestPacket::decode), ProjectionRequestPacket::handle);
        r.playToClient(ProjectionDataPacket.TYPE, StreamCodec.ofMember(ProjectionDataPacket::encode, ProjectionDataPacket::decode), ProjectionDataPacket::handle);
        r.playToClient(RegionSyncPacket.TYPE, StreamCodec.ofMember(RegionSyncPacket::encode, RegionSyncPacket::decode), RegionSyncPacket::handle);
        // v1.1.0 实测四百二十：内置日语语音包播放（S2C——只发女仆实体 id + jar 内文件名）
        r.playToClient(PlayJarVoicePacket.TYPE, StreamCodec.ofMember(PlayJarVoicePacket::encode, PlayJarVoicePacket::decode), PlayJarVoicePacket::handle);
        // v1.1.0 实测四百二十一：冷却可视化（S2C）——复活倒计时 / 回魂符冷却每秒快照
        r.playToClient(CooldownHudPacket.TYPE, StreamCodec.ofMember(CooldownHudPacket::encode, CooldownHudPacket::decode), CooldownHudPacket::handle);
    }

    /**
     * v1.1.0 实测四百二十一：冷却可视化（S2C）。每项 4 字段
     * {kind, 显示名, 剩余秒, 总秒}，kind = "revive"（复活倒计时）/ "soul"（回魂符冷却）。
     */
    public static class CooldownHudPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CooldownHudPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "cooldown_hud"));
        public final java.util.List<String[]> entries;

        public CooldownHudPacket(java.util.List<String[]> entries) {
            this.entries = entries == null ? new java.util.ArrayList<>() : entries;
        }

        public static void encode(CooldownHudPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (String[] e : pkt.entries) {
                for (int i = 0; i < 4; i++) {
                    buf.writeUtf(e.length > i ? e[i] : "");
                }
            }
        }

        public static CooldownHudPacket decode(FriendlyByteBuf buf) {
            int n = Integer.parseInt(buf.readUtf());
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            return new CooldownHudPacket(list);
        }

        public static void handle(CooldownHudPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.CooldownHudRenderer.onSnapshot(pkt.entries));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.1.0 实测四百二十一：把冷却快照发给单个玩家（CooldownHudTracker 用） */
    public static void sendCooldownHud(ServerPlayer player, java.util.List<String[]> entries) {
        try {
            PacketDistributor.sendToPlayer(player, new CooldownHudPacket(entries));
        } catch (Exception ignored) {
        }
    }

    /** v1.5.275：请求重新打开手册（C2S——配置面板跳转女仆管理：关配置 → 服务端
     *  重新下发手册包（initialView=2 女仆管理，与 BlueprintBookScreen.VIEW_MAIDS 一致）
     *  → 客户端开手册并切到女仆管理页） */
    public static class OpenBookRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenBookRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_book_request"));
        /** 0 = 默认大目录；2 = 女仆管理页（BlueprintBookScreen.VIEW_MAIDS） */
        public final int view;

        public OpenBookRequestPacket(int view) {
            this.view = view;
        }

        public static void encode(OpenBookRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.view);
        }

        public static OpenBookRequestPacket decode(FriendlyByteBuf buf) {
            return new OpenBookRequestPacket(buf.readInt());
        }

        public static void handle(OpenBookRequestPacket pkt,
                                  IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.server.level.ServerPlayer sp = (ServerPlayer) ctx.player();
                if (sp != null) {
                    net.minecraft.world.item.ItemStack hand = sp.getMainHandItem();
                    // 无论主手是什么都重新打开（openFor 不依赖物品）
                    com.maidsmart.build.BlueprintBookItem.openFor(sp, pkt.view);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 蓝图目录条目（v1.5.18：含材料缺口 {物品id, 已有, 需要}；v1.5.159：含占地尺寸
     *  {宽, 高, 深}——手册"区块显示"预览用） */
    public record Entry(String id, String name, String desc, List<String[]> materials,
                        int sizeX, int sizeY, int sizeZ) {
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
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
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

    /** 服务端：打开手册时发送（携带完整目录 + 材料缺口 + v1.5.43 女仆状态列表 +
     *  v1.5.48 建造状态：全局暂停 + 速度档位） */
    public static class OpenBlueprintBookPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenBlueprintBookPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_blueprint_book"));
        public final List<Entry> entries;
        public final List<String[]> maids; // {uuid, 名字, 状态}
        /** v1.5.100b：全部女仆（记忆页用）：{uuid, 名字, 记忆开关 "1"/"0"}——不限距离 */
        public final List<String[]> allMaids;
        public final boolean paused;
        public final String speed;
        /** v1.5.62：建造进度文本（面板内显示，真实放置数） */
        public final String progressText;
        /** v1.5.65：进度百分比（-1 = 无计划；v1.5.162：客户端据此 + 计划区块标记判断控制按钮显示） */
        public final int progress;
        /** v1.5.162：计划区块标记（中心点 + 尺寸；无计划 regionX = Integer.MIN_VALUE） */
        public final int regionX;
        public final int regionY;
        public final int regionZ;
        public final int regionW;
        public final int regionH;
        public final int regionD;
        /** v2.0：玩家是否位于当前计划区块内（区块内右击手册 → 客户端直接进计划详情页） */
        public final boolean inPlanRegion;
        /** v2.0：当前计划的蓝图 id（无计划 = null；客户端据此定位详情页条目） */
        public final String currentPlanId;
        /** v1.5.178：所有有效建造区块 {显示名, 维度名, 状态, 坐标}（女仆管理页区块列表） */
        public final List<String[]> regions;
        /** v1.5.252z：打开手册立即显示——预计完成秒（-1=未知）+ 实时速度（块/秒） */
        public final int etaSec;
        public final String speedBps;
        /** v1.5.275：初始视图（0=大目录 1=女仆管理——配置面板"跳转女仆管理"） */
        public final int initialView;

        public OpenBlueprintBookPacket(List<Entry> entries, List<String[]> maids, List<String[]> allMaids,
                                       boolean paused, String speed, String progressText, int progress,
                                       int regionX, int regionY, int regionZ,
                                       int regionW, int regionH, int regionD,
                                       boolean inPlanRegion, String currentPlanId,
                                       List<String[]> regions, int etaSec, String speedBps,
                                       int initialView) {
            this.entries = entries;
            this.maids = maids;
            this.allMaids = allMaids;
            this.paused = paused;
            this.speed = speed;
            this.progressText = progressText;
            this.progress = progress;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.regionW = regionW;
            this.regionH = regionH;
            this.regionD = regionD;
            this.inPlanRegion = inPlanRegion;
            this.currentPlanId = currentPlanId;
            this.initialView = initialView;
            this.regions = regions == null ? new ArrayList<>() : regions;
            this.etaSec = etaSec;
            this.speedBps = speedBps == null ? "" : speedBps;
        }

        public static void encode(OpenBlueprintBookPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (Entry e : pkt.entries) {
                buf.writeUtf(e.id());
                buf.writeUtf(e.name());
                buf.writeUtf(e.desc());
                buf.writeUtf(String.valueOf(e.sizeX()));
                buf.writeUtf(String.valueOf(e.sizeY()));
                buf.writeUtf(String.valueOf(e.sizeZ()));
                buf.writeUtf(String.valueOf(e.materials() == null ? 0 : e.materials().size()));
                if (e.materials() != null) {
                    for (String[] m : e.materials()) {
                        buf.writeUtf(m[0]);
                        buf.writeUtf(String.valueOf(m[1]));
                        buf.writeUtf(String.valueOf(m[2]));
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.maids == null ? 0 : pkt.maids.size()));
            if (pkt.maids != null) {
                for (String[] m : pkt.maids) {
                    buf.writeUtf(m[0]);
                    buf.writeUtf(m[1]);
                    buf.writeUtf(m[2]);
                    buf.writeUtf(m.length > 3 ? m[3] : "0");
                }
            }
            buf.writeUtf(String.valueOf(pkt.allMaids == null ? 0 : pkt.allMaids.size()));
            if (pkt.allMaids != null) {
                for (String[] m : pkt.allMaids) {
                    // v1.5.178：全字段（记忆开关/段落数/任务/绑定/状态/工头）
                    // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                    for (int i = 0; i < 10; i++) {
                        buf.writeUtf(m.length > i ? m[i] : "");
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.paused));
            buf.writeUtf(pkt.speed == null ? "×1" : pkt.speed);
            buf.writeUtf(pkt.progressText == null ? "" : pkt.progressText);
            buf.writeUtf(String.valueOf(pkt.progress));
            buf.writeUtf(String.valueOf(pkt.regionX));
            buf.writeUtf(String.valueOf(pkt.regionY));
            buf.writeUtf(String.valueOf(pkt.regionZ));
            buf.writeUtf(String.valueOf(pkt.regionW));
            buf.writeUtf(String.valueOf(pkt.regionH));
            buf.writeUtf(String.valueOf(pkt.regionD));
            // v2.0：玩家是否在计划区块内 + 当前计划蓝图 id（区块内右击 → 详情页）
            buf.writeUtf(String.valueOf(pkt.inPlanRegion));
            buf.writeUtf(pkt.currentPlanId == null ? "" : pkt.currentPlanId);
            // v1.5.178：有效建造区块列表（v1.5.180：11 字段含 planId/尺寸/蓝图 id）
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.5.290：14 字段（v1.5.279 起 regions 追加创建坐标 r[11..13]，
                    // 旧版写死 11 → 坐标字段永远没发出去 → 客户端 r.length>11 恒 false，
                    // 区块"创建于 x,y,z"从未显示——用户："显示坐标还是没有做好"）
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
            // v1.5.252z：打开手册立即显示速度/ETA（追加在末尾，解码按序读）
            buf.writeUtf(String.valueOf(pkt.etaSec));
            buf.writeUtf(pkt.speedBps);
            // v1.5.275：初始视图（0=大目录 1=女仆管理——配置面板跳转用）
            buf.writeUtf(String.valueOf(pkt.initialView));
        }

        public static OpenBlueprintBookPacket decode(FriendlyByteBuf buf) {
            int size = Integer.parseInt(buf.readUtf());
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String id = buf.readUtf();
                String name = buf.readUtf();
                String desc = buf.readUtf();
                int sx = Integer.parseInt(buf.readUtf());
                int sy = Integer.parseInt(buf.readUtf());
                int sz = Integer.parseInt(buf.readUtf());
                int matCount = Integer.parseInt(buf.readUtf());
                List<String[]> mats = new ArrayList<>();
                for (int j = 0; j < matCount; j++) {
                    mats.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf()});
                }
                entries.add(new Entry(id, name, desc, mats, sx, sy, sz));
            }
            int maidCount = Integer.parseInt(buf.readUtf());
            List<String[]> maids = new ArrayList<>();
            for (int i = 0; i < maidCount; i++) {
                maids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            int allCount = Integer.parseInt(buf.readUtf());
            List<String[]> allMaids = new ArrayList<>();
            for (int i = 0; i < allCount; i++) {
                // v1.5.178：全字段（记忆开关/段落数/任务/绑定/状态/工头）
                // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                allMaids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            boolean paused = Boolean.parseBoolean(buf.readUtf());
            String speed = buf.readUtf();
            String progressText = buf.readUtf();
            int progress = Integer.parseInt(buf.readUtf());
            int regionX = Integer.parseInt(buf.readUtf());
            int regionY = Integer.parseInt(buf.readUtf());
            int regionZ = Integer.parseInt(buf.readUtf());
            int regionW = Integer.parseInt(buf.readUtf());
            int regionH = Integer.parseInt(buf.readUtf());
            int regionD = Integer.parseInt(buf.readUtf());
            // v2.0：玩家是否在计划区块内 + 当前计划蓝图 id
            boolean inPlanRegion = Boolean.parseBoolean(buf.readUtf());
            String currentPlanId = buf.readUtf();
            // v1.5.178：有效建造区块列表（v1.5.180：11 字段）
            int regionCount = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) {
                // v1.5.296：14 字段——v1.5.290 只改了 encode（写 14），decode 漏改仍读 11：
                // (a) 客户端区块永远只有 11 字段 → r[11..13] 创建坐标缺失 →"坐标显示没做出来"；
                // (b) 每个区块剩 3 个坐标字符串错位到后续字段，≥2 个区块时 initialView 读到
                // 蓝图 id（非数字）→ NumberFormatException → 连接损坏 →"连接已丢失"
                //（日志实证 06:21:55 创建第二个区块后开手册即断连）
                String[] rr = new String[15];
                for (int j = 0; j < 15; j++) {
                    rr[j] = buf.readUtf();
                }
                regions.add(rr);
            }
            // v1.5.252z：速度/ETA（与 encode 末尾顺序一致）
            int etaSec = Integer.parseInt(buf.readUtf());
            String speedBps = buf.readUtf();
            // v1.5.275：初始视图（0=大目录 2=女仆管理）
            int initialView = Integer.parseInt(buf.readUtf());
            return new OpenBlueprintBookPacket(entries, maids, allMaids, paused, speed, progressText, progress,
                    regionX, regionY, regionZ, regionW, regionH, regionD,
                    inPlanRegion, currentPlanId, regions, etaSec, speedBps, initialView);
        }

        public static void handle(OpenBlueprintBookPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                // v1.5.164：打开手册同步计划区块标记 → 红色固定框（v1.5.180：多框）
                com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions);
                BlueprintBookScreen.open(pkt.entries, pkt.maids, pkt.allMaids, pkt.paused, pkt.speed,
                        pkt.progressText, pkt.progress, pkt.regionX, pkt.regionY, pkt.regionZ,
                        pkt.regionW, pkt.regionH, pkt.regionD,
                        pkt.inPlanRegion, pkt.currentPlanId, pkt.regions,
                        pkt.etaSec, pkt.speedBps, pkt.initialView);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    /** v1.5.62：S2C 状态刷新——进度/速度/暂停/女仆状态面板内即时更新（不重开手册） */
    public static class ProgressUpdatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProgressUpdatePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "progress_update"));
        public final String progressText;
        public final List<String[]> maids;
        public final boolean paused;
        public final String speed;
        /** v1.5.65：进度百分比（-1 = 无计划） */
        public final int progress;
        /** v1.5.162：进行中计划的区块标记（中心点 + 宽/高/深；无计划时 regionX = Integer.MIN_VALUE）——
         *  客户端据此判定"玩家是否处于建造区块内"（控制按钮只在区块内显示） */
        public final int regionX;
        public final int regionY;
        public final int regionZ;
        public final int regionW;
        public final int regionH;
        public final int regionD;
        /** v1.5.178：全部女仆（女仆管理页——含绑定区块显示名/任务/建筑状态） */
        public final List<String[]> allMaids;
        /** v1.5.178：有效建造区块列表 {显示名, 维度名, 状态, 坐标} */
        public final List<String[]> regions;
        /** v1.5.180：玩家所在区块 planId（无 = 区块外；客户端当前区块上下文） */
        public final String planId;
        /** v1.5.252s：进度条旁显示——预计完成秒（-1 = 未知）+ 实时速度（块/秒） */
        public final int etaSec;
        public final String speedBps;

        public ProgressUpdatePacket(String progressText, List<String[]> maids,
                                    boolean paused, String speed, int progress,
                                    int regionX, int regionY, int regionZ,
                                    int regionW, int regionH, int regionD,
                                    List<String[]> allMaids, List<String[]> regions,
                                    String planId, int etaSec, String speedBps) {
            this.progressText = progressText;
            this.maids = maids;
            this.paused = paused;
            this.speed = speed;
            this.progress = progress;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.regionW = regionW;
            this.regionH = regionH;
            this.regionD = regionD;
            this.allMaids = allMaids == null ? new ArrayList<>() : allMaids;
            this.regions = regions == null ? new ArrayList<>() : regions;
            this.planId = planId;
            this.etaSec = etaSec;
            this.speedBps = speedBps == null ? "" : speedBps;
        }

        public static void encode(ProgressUpdatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.progressText == null ? "" : pkt.progressText);
            buf.writeUtf(String.valueOf(pkt.maids == null ? 0 : pkt.maids.size()));
            if (pkt.maids != null) {
                for (String[] m : pkt.maids) {
                    buf.writeUtf(m[0]);
                    buf.writeUtf(m[1]);
                    buf.writeUtf(m[2]);
                    buf.writeUtf(m.length > 3 ? m[3] : "0");
                }
            }
            buf.writeUtf(String.valueOf(pkt.paused));
            buf.writeUtf(pkt.speed == null ? "×1" : pkt.speed);
            buf.writeUtf(String.valueOf(pkt.progress));
            buf.writeUtf(String.valueOf(pkt.regionX));
            buf.writeUtf(String.valueOf(pkt.regionY));
            buf.writeUtf(String.valueOf(pkt.regionZ));
            buf.writeUtf(String.valueOf(pkt.regionW));
            buf.writeUtf(String.valueOf(pkt.regionH));
            buf.writeUtf(String.valueOf(pkt.regionD));
            // v1.5.178：全部女仆 + 有效建造区块（女仆管理页）
            buf.writeUtf(String.valueOf(pkt.allMaids == null ? 0 : pkt.allMaids.size()));
            if (pkt.allMaids != null) {
                for (String[] m : pkt.allMaids) {
                    // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                    for (int i = 0; i < 10; i++) {
                        buf.writeUtf(m.length > i ? m[i] : "");
                    }
                }
            }
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.5.290：14 字段（v1.5.279 起 regions 追加创建坐标 r[11..13]，
                    // 旧版写死 11 → 坐标字段从未发出去）
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
            buf.writeUtf(pkt.planId == null ? "" : pkt.planId);
            // v1.5.252s：进度条旁显示（追加在末尾，解码按序读）
            buf.writeUtf(String.valueOf(pkt.etaSec));
            buf.writeUtf(pkt.speedBps);
        }

        public static ProgressUpdatePacket decode(FriendlyByteBuf buf) {
            String progressText = buf.readUtf();
            int maidCount = Integer.parseInt(buf.readUtf());
            List<String[]> maids = new ArrayList<>();
            for (int i = 0; i < maidCount; i++) {
                maids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            boolean paused = Boolean.parseBoolean(buf.readUtf());
            String speed = buf.readUtf();
            int progress = Integer.parseInt(buf.readUtf());
            int regionX = Integer.parseInt(buf.readUtf());
            int regionY = Integer.parseInt(buf.readUtf());
            int regionZ = Integer.parseInt(buf.readUtf());
            int regionW = Integer.parseInt(buf.readUtf());
            int regionH = Integer.parseInt(buf.readUtf());
            int regionD = Integer.parseInt(buf.readUtf());
            // v1.5.178：全部女仆 + 有效建造区块
            int allCount = Integer.parseInt(buf.readUtf());
            List<String[]> allMaids = new ArrayList<>();
            for (int i = 0; i < allCount; i++) {
                // v1.5.182：第 9 字段 = 绑定 planId；v1.0.3：第 10 字段 = LLM 开关
                allMaids.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            int regionCount = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) {
                // v1.5.296：14 字段（与 OpenBlueprintBookPacket 同修——v1.5.290 漏改
                // decode：坐标字段缺失 + 多区块时后续字段错位致解析崩溃）
                String[] rr = new String[15];
                for (int j = 0; j < 15; j++) {
                    rr[j] = buf.readUtf();
                }
                regions.add(rr);
            }
            String planId = buf.readUtf();
            // v1.5.252s：进度条旁显示（与 encode 末尾顺序一致）
            int etaSec = Integer.parseInt(buf.readUtf());
            String speedBps = buf.readUtf();
            return new ProgressUpdatePacket(progressText, maids, paused, speed, progress,
                    regionX, regionY, regionZ, regionW, regionH, regionD,
                    allMaids, regions, planId, etaSec, speedBps);
        }

        public static void handle(ProgressUpdatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                // v1.5.164：计划区块标记 → 红色固定框（v1.5.180：多区块由 regions 列表驱动）
                com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions);
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.updateStatus(pkt.progressText, pkt.maids, pkt.paused, pkt.speed, pkt.progress,
                            pkt.regionX, pkt.regionY, pkt.regionZ, pkt.regionW, pkt.regionH, pkt.regionD,
                            pkt.allMaids, pkt.regions, pkt.planId, pkt.etaSec, pkt.speedBps);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.252j：建造 HUD 快照（S2C）——服务端每秒广播进行中区块的
     *  进度/速度/预计完成时间，客户端 BuildHudRenderer 左上角显示。
     *  每项 8 字段 {planId, 显示名, 已建, 总数, 跳过, 速度(块/秒), 预计秒(-1=未知), 暂停} */
    public static class BuildHudPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildHudPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_hud"));
        public final java.util.List<String[]> entries;

        public BuildHudPacket(java.util.List<String[]> entries) {
            this.entries = entries == null ? new java.util.ArrayList<>() : entries;
        }

        public static void encode(BuildHudPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.entries.size()));
            for (String[] e : pkt.entries) {
                for (int i = 0; i < 8; i++) {
                    buf.writeUtf(e.length > i ? e[i] : "");
                }
            }
        }

        public static BuildHudPacket decode(FriendlyByteBuf buf) {
            int n = Integer.parseInt(buf.readUtf());
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(new String[]{buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()});
            }
            return new BuildHudPacket(list);
        }

        public static void handle(BuildHudPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.build.BuildHudRenderer.onSnapshot(pkt.entries));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 客户端：点击目录条目（蓝图 id） */
    public static class SelectBlueprintPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SelectBlueprintPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "select_blueprint"));
        public final String blueprintId;
        /** v1.1.0 实测九十七：朝向（0~3 × 90° 顺时针）——金色预览按 P 选定后携带 */
        public final int quarters;

        public SelectBlueprintPacket(String blueprintId, int quarters) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
        }

        public static void encode(SelectBlueprintPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
        }

        public static SelectBlueprintPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            return new SelectBlueprintPacket(id, q);
        }

        public static void handle(SelectBlueprintPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                // v1.5.180：创建区块【不需要女仆在场】——以玩家脚下为原点创建
                //（手册点击 = 明确意图：材料不足时直接先建材料够的部分）
                BlueprintBuildExecutor.Outcome outcome = BlueprintBuildExecutor.execute(
                        level, player.blockPosition(), pkt.blueprintId, true, player,
                        Math.floorMod(pkt.quarters, 4));
                String bubble;
                switch (outcome.type()) {
                    case BlueprintBuildExecutor.TYPE_OK -> bubble = outcome.message();
                    case BlueprintBuildExecutor.TYPE_SHORTFALL -> bubble = "材料不够……" + outcome.message();
                    case BlueprintBuildExecutor.TYPE_OBSTACLE -> bubble = "这个地方有障碍物……" + outcome.message();
                    // v1.5.180：重叠拒绝（创建区块唯一硬性要求）
                    case BlueprintBuildExecutor.TYPE_OVERLAP -> bubble = "\u00a7c" + outcome.message();
                    case BlueprintBuildExecutor.TYPE_BUSY -> bubble = "重复建造被拒绝：" + outcome.message();
                    default -> bubble = "这个蓝图我打不开……";
                }
                // v1.5.164：下达成功/失败都立即推送计划区块标记（红色固定框立刻出现，
                // 不等 2 秒轮询——被拒时玩家立刻看到已有区块在哪）
                sendProgressUpdate(player);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a7f" + bubble));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.43：手册控制面板（C2S）——全员加入/暂停/速度/强制续建/进度/取消/逐只暂停 */
    public static class BuildControlPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildControlPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_control"));
        public static final int JOIN_ALL = 0;
        public static final int TOGGLE_PAUSE = 1;
        public static final int CYCLE_SPEED = 2;
        // v1.5.162：FORCE_RESUME(3) 强制续建已删除——续建走暂停/继续，重复下达被拒绝
        public static final int SHOW_PROGRESS = 4;
        public static final int CANCEL = 5;
        public static final int TOGGLE_MAID = 6;
        public static final int SET_FOREMAN = 7; // v1.5.69：手动设定工头
        // v1.5.162：FORCE_BUILD(8) 强制建造已删除——建造默认强制执行（渲染给出范围 = 玩家选择）
        // v1.5.178：BIND_MAID(8) / UNBIND_MAID(9) 女仆-区块绑定操作（女仆管理页，无位置限制——
        // 本质是女仆任务切换；其余区块控制类操作必须站在区块内）
        public static final int BIND_MAID = 8;
        public static final int UNBIND_MAID = 9;

        public final int action;
        public final String maidUuid; // TOGGLE_MAID 用
        /** v1.5.180：目标区块 planId（控制/绑定指定区块；无 = 兼容旧调用） */
        public final String planId;

        public BuildControlPacket(int action, String maidUuid) {
            this(action, maidUuid, null);
        }

        public BuildControlPacket(int action, String maidUuid, String planId) {
            this.action = action;
            this.maidUuid = maidUuid;
            this.planId = planId;
        }

        public static void encode(BuildControlPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.action));
            buf.writeUtf(pkt.maidUuid == null ? "" : pkt.maidUuid);
            buf.writeUtf(pkt.planId == null ? "" : pkt.planId);
        }

        public static BuildControlPacket decode(FriendlyByteBuf buf) {
            return new BuildControlPacket(Integer.parseInt(buf.readUtf()), buf.readUtf(), buf.readUtf());
        }

        public static void handle(BuildControlPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                // v1.5.180：目标区块解析（控制/绑定指定区块）
                // v1.5.183：UNBIND 解绑不针对具体区块（planId 允许为空），不要求 target
                BuildPlan.PlanState target = BuildPlan.getPlanById(pkt.planId);
                if (pkt.action != SHOW_PROGRESS && pkt.action != UNBIND_MAID && target == null) {
                    // v1.5.252ae：planId 为空（客户端未定位到区块）提示更准确——
                    // 旧版一律"区块不存在"（用户实测：区块明明存在却提示不存在）
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            (pkt.planId == null || pkt.planId.isEmpty())
                                    ? "\u00a7c请先站在建造区块内再操作（区块外无法定位区块）。"
                                    : "\u00a7c区块不存在（可能已被取消/完成）。"));
                    return;
                }
                // v1.5.178：区块内控制限制——暂停/继续/取消/速度/全员加入/逐只暂停/设工头
                // 必须站在【目标区块】内才有用；绑定/解绑是女仆任务切换（无位置限制），
                // SHOW_PROGRESS 是只读轮询（不限制）
                boolean needsRegion = switch (pkt.action) {
                    case JOIN_ALL, TOGGLE_PAUSE, CYCLE_SPEED, CANCEL, TOGGLE_MAID, SET_FOREMAN -> true;
                    default -> false;
                };
                if (needsRegion) {
                    BuildPlan.PlanState here = findPlayerPlan(level, player);
                    if (here == null || !here.planId.equals(target.planId)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c必须站在该建造区块内才能操作（暂停/继续/取消/速度/全员加入）。"));
                        return;
                    }
                }
                switch (pkt.action) {
                    case JOIN_ALL -> {
                        int n = BuildPlan.joinAll(level, player, target.planId);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已同步绑定 " + n + " 只建造女仆到「" + target.name + "」。"));
                    }
                    case TOGGLE_PAUSE -> {
                        boolean paused = BuildPlan.togglePause(level, target);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(paused
                                ? "\u00a7e「" + target.name + "」已暂停——女仆们停下等待。"
                                : "\u00a7a「" + target.name + "」已恢复——女仆们继续建造。"));
                    }
                    case CYCLE_SPEED -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7a建造速度已切换为 " + MaidBuildBehavior.cycleSpeed()
                                    + "。\u8b66：极速时服务器负载明显升高。"));
                    case SHOW_PROGRESS -> {
                        // v1.5.63：静默——进度由 ProgressUpdatePacket 在手册面板内
                        // 实时显示（客户端每 2 秒轮询触发，无需退出手册/按键）
                    }
                    case CANCEL -> {
                        BuildPlan.cancel(level, target.planId, player);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c已取消区块「" + target.name + "」（已建方块保留，重下达自动续建）。"));
                    }
                    case TOGGLE_MAID -> {
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开范围）。"));
                            return;
                        }
                        // 审计 P-2：暂停/恢复他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        boolean nowPaused = !BuildPlan.isMaidPaused(maid);
                        BuildPlan.setMaidPaused(maid, nowPaused);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                (nowPaused ? "\u00a7e已暂停 " : "\u00a7a已恢复 ")
                                        + maid.getDisplayName().getString() + "的建造。"));
                    }
                    case SET_FOREMAN -> {
                        // v1.5.69：手动设定工头（建造反馈统一由其发出）
                        EntityMaid fm = findMaidByUuid(player, pkt.maidUuid);
                        if (fm == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开范围）。"));
                            return;
                        }
                        // 审计 P-2：设他人女仆为工头需主人或 OP
                        if (!fm.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        BuildPlan.setForeman(level, target, pkt.maidUuid);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已设定 " + fm.getDisplayName().getString()
                                        + " 为「" + target.name + "」工头，建造反馈将统一由它发出。"));
                    }
                    case BIND_MAID -> {
                        // v1.5.180：手动绑定——女仆切换到建筑任务并绑定到【指定区块】
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开）。"));
                            return;
                        }
                        if (!maid.level().dimension().equals(target.dim)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c女仆与区块不在同一维度，无法绑定。"));
                            return;
                        }
                        // 审计 P-2：绑定他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        switchTask(maid, "maid_smart:build");
                        BuildPlan.bindMaid(maid, target.planId);
                        BuildPlan.setMaidPaused(maid, false);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7a已绑定 " + maid.getDisplayName().getString()
                                        + " 到区块「" + target.name + "」——她将开始建造。"));
                    }
                    case UNBIND_MAID -> {
                        // v1.5.180：手动解绑——女仆切换到空闲任务并解除绑定（不再参与任何区块）
                        EntityMaid maid = findMaidByUuid(player, pkt.maidUuid);
                        if (maid == null) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c找不到这只女仆（可能已离开）。"));
                            return;
                        }
                        // 审计 P-2：解绑他人女仆需主人或 OP
                        if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7c只能操作自己的女仆（或 OP）。"));
                            return;
                        }
                        switchTask(maid, "touhou_little_maid:idle");
                        BuildPlan.unbindMaid(maid);
                        BuildPlan.setMaidPaused(maid, false);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7e已解绑 " + maid.getDisplayName().getString() + "——她不再参与该区块建造。"));
                    }
                    default -> {
                    }
                }
                // v1.5.62：面板内即时刷新（速度/暂停/进度/女仆状态，无需退出手册看聊天）
                sendProgressUpdate(player);
            });
            
        }

        private static EntityMaid findMaidByUuid(net.minecraft.server.level.ServerPlayer player, String uuidStr) {
            if (uuidStr == null || uuidStr.isEmpty() || player == null) {
                return null;
            }
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
            // v1.5.187b：玩家周围 128 格扫描（女仆管理页列出的就是 128 格内；
            // 旧版全图 ±3E7 AABB 遍历 visibleChunks 树曾触发死循环卡死游戏）
            net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(128.0);
            for (EntityMaid m : player.level().getEntitiesOfClass(EntityMaid.class, box)) {
                if (m.getUUID().equals(uuid)) {
                    return m;
                }
            }
            return null;
        }

        /** v1.5.178：切换女仆任务（绑定/解绑用；findTask 失败静默——调用方提示语兜底） */
        private static void switchTask(EntityMaid maid, String uidStr) {
            try {
                com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                        .findTask(net.minecraft.resources.ResourceLocation.parse(uidStr))
                        .ifPresent(maid::setTask);
            } catch (Exception ignored) {
            }
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
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

    public static void sendToPlayer(ServerPlayer player, OpenBlueprintBookPacket pkt) {
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
        PacketDistributor.sendToPlayer(player, new ProgressUpdatePacket(
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
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String[], Map<String, int[]>> e
                : BlueprintLib.buildCatalogEntriesWithMaterials(player).entrySet()) {
            String[] base = e.getKey();
            // v1.5.159：占地尺寸（区块显示预览用）——v1.5.375 走缓存（启动预热）
            int[] size = BlueprintLib.blueprintSizeCached(base[0], BlueprintLib.getBlueprint(base[0]));
            entries.add(new Entry(base[0], base[1], base[2],
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
        PacketDistributor.sendToPlayer(player, new OpenBlueprintBookPacket(entries, collectMaidStatus(player),
                        sl == null ? new ArrayList<>() : collectAllMaids(sl),
                        here != null && here.paused, MaidBuildBehavior.speedLabel(),
                        sl == null ? "" : buildProgressText(sl, player),
                        sl == null ? -1 : buildProgressPct(sl, player),
                        region[0], region[1], region[2], region[3], region[4], region[5],
                        here != null, here == null ? null : here.blueprintId,
                        sl == null ? new ArrayList<>() : collectBuildRegions(sl.getServer()),
                        openEta, openBps, 0));
    }

    /**
     * v1.5.86：AI 记忆 per-maid 开关（C2S）。
     * 服务端：校验发送者是女仆主人或 OP → 写 TaskData 并同步回客户端。
     */
    public static class AiMemoryTogglePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AiMemoryTogglePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "ai_memory_toggle"));
        public final String maidUuid;
        public final boolean enabled;

        public AiMemoryTogglePacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(AiMemoryTogglePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static AiMemoryTogglePacket decode(FriendlyByteBuf buf) {
            return new AiMemoryTogglePacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(AiMemoryTogglePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    // v1.5.251b：女仆不在加载范围（远处/瞬移卸载）——仍记录开关
                    // 到磁盘（isEnabled 磁盘优先），她加载后立即生效。
                    // 审计 P-1：离线/未加载女仆无法判断归属，只允许 OP 修改。
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c只有 OP 可以修改未加载女仆的记忆开关。"));
                        return;
                    }
                    com.maidsmart.memory.AiMemoryManager.setEnabledDiskOnly(
                            pkt.maidUuid, pkt.enabled, level);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a77\u5973\u4ec6\u4e0d\u5728\u9644\u8fd1\uff08\u672a\u52a0\u8f7d\uff09\uff0c"
                                    + "\u8bb0\u5fc6\u5f00\u5173\u5df2\u8bb0\u5f55\uff0c\u5979\u52a0\u8f7d\u540e\u751f\u6548\u3002"));
                    return;
                }
                // 权限：女仆主人 或 OP
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u5207\u6362\u8bb0\u5fc6\u5f00\u5173\u3002"));
                    return;
                }
                com.maidsmart.memory.AiMemoryManager.setEnabled(maid, pkt.enabled);
                // v1.5.226：广播开关状态给追踪该女仆的所有客户端（persistentData
                // 不同步客户端——不广播的话客户端显示会停留在旧状态）
                // v1.5.249：TRACKING_ENTITY 只发给"正在追踪该女仆"的玩家——玩家在
                // 女仆配置界面/离女仆远时收不到 → CLIENT_STATE 不更新 → 界面显示旧
                // 值"开"，用户再点又发 false（日志实证 7 秒内 4 次 toggle(false)，
                // "关了又自己打开"的根因）。改为【操作玩家必达 + 追踪玩家】双发。
                // v1.5.251：附 soulId——客户端本地读记忆按灵魂目录路由
                final EntityMaid fMaid = maid;
                String soul = com.maidsmart.soul.SoulBindingService.getSoulId(maid);
                MemoryStateSyncPacket sync = new MemoryStateSyncPacket(pkt.maidUuid,
                        pkt.enabled, soul == null ? "" : soul);
                PacketDistributor.sendToPlayer(player, sync);
                PacketDistributor.sendToPlayersTrackingEntity(fMaid, sync);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (pkt.enabled ? "\u00a7a" : "\u00a77") + "\u3010AI \u8bb0\u5fc6\u3011"
                                + maid.getDisplayName().getString() + "\u5df2"
                                + (pkt.enabled ? "\u542f\u7528\uff08\u5bf9\u8bdd\u79ef\u7d2f\u540e\u81ea\u52a8\u63d0\u53d6\uff09"
                                : "\u5173\u95ed\uff08\u4e0d\u63d0\u53d6\u4e0d\u6ce8\u5165\uff09")));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.226：S2C 记忆开关状态同步（服务端 setEnabled 后广播）。
     * persistentData 只在服务端读写、不同步客户端，客户端直接读 isEnabled 会
     * 拿到过期旧值（重开女仆配置界面开关显示回"开"）；收到本包后客户端把状态
     * 缓存进 AiMemoryManager.CLIENT_STATE，isEnabled 优先读缓存。
     */
    public static class MemoryStateSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryStateSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_state_sync"));
        public final String maidUuid;
        public final boolean enabled;
        /** v1.5.251：女仆灵魂 id（空 = 未绑定）——客户端缓存后，配置界面本地
         *  读记忆时按灵魂目录路由（全局共享存储，跨存档双向同步的显示侧） */
        public final String soulId;

        public MemoryStateSyncPacket(String maidUuid, boolean enabled) {
            this(maidUuid, enabled, "");
        }

        public MemoryStateSyncPacket(String maidUuid, boolean enabled, String soulId) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
            this.soulId = soulId == null ? "" : soulId;
        }

        public static void encode(MemoryStateSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
            buf.writeUtf(pkt.soulId);
        }

        public static MemoryStateSyncPacket decode(FriendlyByteBuf buf) {
            return new MemoryStateSyncPacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()), buf.readUtf());
        }

        public static void handle(MemoryStateSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                com.maidsmart.memory.AiMemoryManager.pushClientState(pkt.maidUuid, pkt.enabled);
                if (!pkt.soulId.isEmpty()) {
                    com.maidsmart.memory.AiMemoryManager.pushClientSoulId(pkt.maidUuid, pkt.soulId);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ==================== v1.0.3:per-maid 大语言模型开关 ====================

    /**
     * C2S：手册女仆记忆页「LLM:开/关」按钮 → 服务端写 LlmEnableManager
     * （persistentData + 磁盘备份），并广播 LlmStateSyncPacket（操作玩家必达 +
     * 追踪玩家双发，防客户端显示回"开"）。
     */
    public static class AiLlmTogglePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AiLlmTogglePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "ai_llm_toggle"));
        public final String maidUuid;
        public final boolean enabled;

        public AiLlmTogglePacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(AiLlmTogglePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static AiLlmTogglePacket decode(FriendlyByteBuf buf) {
            return new AiLlmTogglePacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(AiLlmTogglePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    // 女仆不在加载范围——仍记录到磁盘（isEnabled 磁盘优先），加载后生效。
                    // 审计 P-1：离线/未加载女仆无法判断归属，只允许 OP 修改。
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c只有 OP 可以修改未加载女仆的 LLM 开关。"));
                        return;
                    }
                    com.maidsmart.memory.LlmEnableManager.setEnabledDiskOnly(
                            pkt.maidUuid, pkt.enabled, level);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a77女仆不在附近（未加载），LLM 开关已记录，她加载后生效。"));
                    return;
                }
                // 权限：女仆主人 或 OP
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c只有女仆的主人或 OP 可以切换 LLM 开关。"));
                    return;
                }
                com.maidsmart.memory.LlmEnableManager.setEnabled(maid, pkt.enabled);
                // 广播开关状态（persistentData 不同步客户端——不广播则界面显示旧值）
                final EntityMaid fMaid = maid;
                LlmStateSyncPacket sync = new LlmStateSyncPacket(pkt.maidUuid, pkt.enabled);
                PacketDistributor.sendToPlayer(player, sync);
                PacketDistributor.sendToPlayersTrackingEntity(fMaid, sync);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (pkt.enabled ? "\u00a7a" : "\u00a77") + "\u3010大语言模型\u3011"
                                + maid.getDisplayName().getString() + "已"
                                + (pkt.enabled ? "启用（对话由 LLM 驱动）"
                                : "关闭（不发 LLM 请求，主动对话降级为固定文本）")));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：LLM 开关状态同步（服务端 setEnabled 后广播，客户端缓存纠正显示） */
    public static class LlmStateSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LlmStateSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "llm_state_sync"));
        public final String maidUuid;
        public final boolean enabled;

        public LlmStateSyncPacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(LlmStateSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static LlmStateSyncPacket decode(FriendlyByteBuf buf) {
            return new LlmStateSyncPacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(LlmStateSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                com.maidsmart.memory.LlmEnableManager.pushClientState(pkt.maidUuid, pkt.enabled);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.227：C2S 记忆开关状态查询——女仆配置界面（MaidConfigMemoryMixin）打开时
     * 发送，服务端读 persistentData 真实状态后回一个 MemoryStateSyncPacket（id 16，
     * 客户端缓存进 AiMemoryManager → 按钮/面板显示即时纠正）。
     */
    public static class MemoryStateQueryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryStateQueryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_state_query"));
        public final String maidUuid;

        public MemoryStateQueryPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MemoryStateQueryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MemoryStateQueryPacket decode(FriendlyByteBuf buf) {
            return new MemoryStateQueryPacket(buf.readUtf());
        }

        public static void handle(MemoryStateQueryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    return;
                }
                // 审计优化7修复：状态查询补主人/OP 校验（旧版任何人可查任意女仆开关+灵魂id）
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    return;
                }
                String soul = com.maidsmart.soul.SoulBindingService.getSoulId(maid);
                PacketDistributor.sendToPlayer(player, new MemoryStateSyncPacket(pkt.maidUuid,
                                com.maidsmart.memory.AiMemoryManager.isEnabled(maid),
                                soul == null ? "" : soul));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.94：删除蓝图（C2S）——手册目录行"删除"按钮发送。
     * 服务端：校验只删外部蓝图（内置蓝图不可删）→ BlueprintLib.deleteBlueprint
     * 删文件 + 清缓存 → 回发状态快照（手册目录即时刷新，被删蓝图消失）。
     */
    public static class DeleteBlueprintPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DeleteBlueprintPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "delete_blueprint"));
        public final String blueprintId;

        public DeleteBlueprintPacket(String blueprintId) {
            this.blueprintId = blueprintId;
        }

        public static void encode(DeleteBlueprintPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId);
        }

        public static DeleteBlueprintPacket decode(FriendlyByteBuf buf) {
            return new DeleteBlueprintPacket(buf.readUtf());
        }

        public static void handle(DeleteBlueprintPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                String id = pkt.blueprintId;
                if (id == null || id.isEmpty() || !id.startsWith("maid_smart_ext:")) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5185\u7f6e\u84dd\u56fe\u4e0d\u80fd\u5220\u9664\u3002"));
                    return;
                }
                // 审计M2修复：联机服务器上任意玩家可删外部蓝图（grief）——仅 OP；
                // 单机/局域网主机不受影响（同机同人）
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                if (BlueprintLib.deleteBlueprint(id)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5df2\u5220\u9664\u84dd\u56fe\u3002"));
                    // 重发完整目录（被删蓝图从手册消失）
                    sendCatalog(player);
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5220\u9664\u5931\u8d25\uff1a\u84dd\u56fe\u4e0d\u5b58\u5728\u6216\u4e3a\u5185\u7f6e\u3002"));
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.95：查看女仆记忆（C2S）——手册"女仆记忆"页点某女仆发送。
     * 服务端：校验发送者是女仆主人或 OP → 收集记忆段落/关系/画像/开关 → 回发。
     */
    public static class MemoryViewRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryViewRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_view_request"));
        public final String maidUuid;

        public MemoryViewRequestPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MemoryViewRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MemoryViewRequestPacket decode(FriendlyByteBuf buf) {
            return new MemoryViewRequestPacket(buf.readUtf());
        }

        public static void handle(MemoryViewRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u67e5\u770b\u8bb0\u5fc6\u3002"));
                    return;
                }
                PacketDistributor.sendToPlayer(player, new MemoryViewResponsePacket(pkt.maidUuid,
                                collectMemoryLines(maid, level)));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.95：服务端收集女仆记忆为文本行（段落/关系/画像/开关）。
     *  v1.5.167：加记忆系统调试块（存储目录/统计/来源分布）+ 段落重要度颜色分级
     *  （可视化：金色 ≥8 / 绿色 ≥5 / 灰色 <5）+ 访问次数——"记忆调试面板"。 */
    private static List<String> collectMemoryLines(EntityMaid maid, net.minecraft.server.level.ServerLevel level) {
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

    /** v1.5.103：清空女仆记忆（C2S）——手册记忆页"清空记忆"按钮；
     *  服务端：校验主人/OP → 清空 AiMemoryStore → 回发空记忆列表刷新页面 */
    public static class ClearMemoryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClearMemoryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "clear_memory"));
        public final String maidUuid;

        public ClearMemoryPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(ClearMemoryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static ClearMemoryPacket decode(FriendlyByteBuf buf) {
            return new ClearMemoryPacket(buf.readUtf());
        }

        public static void handle(ClearMemoryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u64cd\u4f5c\u8bb0\u5fc6\u3002"));
                    return;
                }
                com.maidsmart.soul.SoulBindingService.storeFor(maid, level).clearAll();
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7a\u5df2\u6e05\u7a7a" + maid.getDisplayName().getString() + "\u7684\u5168\u90e8\u8bb0\u5fc6\u3002"));
                PacketDistributor.sendToPlayer(player, new MemoryViewResponsePacket(pkt.maidUuid, collectMemoryLines(maid, level)));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.95：下发女仆记忆（S2C）——客户端手册"女仆记忆"页显示 */
    public static class MemoryViewResponsePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryViewResponsePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_view_response"));
        public final String maidUuid;
        public final List<String> lines;

        public MemoryViewResponsePacket(String maidUuid, List<String> lines) {
            this.maidUuid = maidUuid;
            this.lines = lines;
        }

        public static void encode(MemoryViewResponsePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.lines == null ? 0 : pkt.lines.size()));
            if (pkt.lines != null) {
                for (String l : pkt.lines) {
                    buf.writeUtf(l);
                }
            }
        }

        public static MemoryViewResponsePacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf();
            int n = Integer.parseInt(buf.readUtf());
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(buf.readUtf());
            }
            return new MemoryViewResponsePacket(uuid, lines);
        }

        public static void handle(MemoryViewResponsePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.showMemoryLines(pkt.maidUuid, pkt.lines);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
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
                net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
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

    /** v1.5.192：请求女仆调试快照（C2S）——手册"链路调试"页进入/刷新时发 */
    public static class MaidDebugRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_request"));
        public final String maidUuid;

        public MaidDebugRequestPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MaidDebugRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MaidDebugRequestPacket decode(FriendlyByteBuf buf) {
            return new MaidDebugRequestPacket(buf.readUtf());
        }

        public static void handle(MaidDebugRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u67e5\u770b\u8c03\u8bd5\u9762\u677f\u3002"));
                    return;
                }
                sendDebugResponse(player, maid, level, pkt.maidUuid);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.192：下发调试快照（S2C）——statusLines + 可调试对象行 */
    public static class MaidDebugResponsePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugResponsePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_response"));
        public final String maidUuid;
        public final List<String> statusLines;
        public final List<String[]> rows;

        public MaidDebugResponsePacket(String maidUuid, List<String> statusLines, List<String[]> rows) {
            this.maidUuid = maidUuid;
            this.statusLines = statusLines;
            this.rows = rows;
        }

        public static void encode(MaidDebugResponsePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.statusLines == null ? 0 : pkt.statusLines.size()));
            if (pkt.statusLines != null) {
                for (String l : pkt.statusLines) {
                    buf.writeUtf(l);
                }
            }
            buf.writeUtf(String.valueOf(pkt.rows == null ? 0 : pkt.rows.size()));
            if (pkt.rows != null) {
                for (String[] r : pkt.rows) {
                    buf.writeUtf(r[0]);
                    buf.writeUtf(r[1]);
                    buf.writeUtf(r[2]);
                    buf.writeUtf(r[3]);
                }
            }
        }

        public static MaidDebugResponsePacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf();
            int n = Integer.parseInt(buf.readUtf());
            List<String> status = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                status.add(buf.readUtf());
            }
            int m = Integer.parseInt(buf.readUtf());
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                rows.add(new String[]{buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            return new MaidDebugResponsePacket(uuid, status, rows);
        }

        public static void handle(MaidDebugResponsePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.showDebugSnapshot(pkt.maidUuid, pkt.statusLines, pkt.rows);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.192：调试动作（C2S）——改重要度/标记删除/恢复/强化/停用关系；
     *  成功后回发最新快照让客户端即时刷新 */
    public static class MaidDebugActionPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugActionPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_action"));
        public final String maidUuid;
        public final String action;
        public final String target;

        public MaidDebugActionPacket(String maidUuid, String action, String target) {
            this.maidUuid = maidUuid;
            this.action = action;
            this.target = target == null ? "" : target;
        }

        public static void encode(MaidDebugActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(pkt.action);
            buf.writeUtf(pkt.target);
        }

        public static MaidDebugActionPacket decode(FriendlyByteBuf buf) {
            return new MaidDebugActionPacket(buf.readUtf(), buf.readUtf(), buf.readUtf());
        }

        public static void handle(MaidDebugActionPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u8c03\u8bd5\u8bb0\u5fc6\u3002"));
                    return;
                }
                com.maidsmart.memory.AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
                boolean ok = true;
                switch (pkt.action) {
                    case "salience_up" -> {
                        int ns = store.adjustSalienceByHash(pkt.target, 1);
                        if (ns >= 0) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7a\u5df2\u5c06\u8be5\u8bb0\u5fc6\u91cd\u8981\u5ea6\u8c03\u4e3a " + ns + "\u3002"));
                        } else {
                            ok = false;
                        }
                    }
                    case "salience_down" -> {
                        int ns = store.adjustSalienceByHash(pkt.target, -1);
                        if (ns >= 0) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7a\u5df2\u5c06\u8be5\u8bb0\u5fc6\u91cd\u8981\u5ea6\u8c03\u4e3a " + ns + "\u3002"));
                        } else {
                            ok = false;
                        }
                    }
                    case "delete" -> ok = store.markDeletedByHash(pkt.target);
                    case "restore" -> ok = store.restoreByHash(pkt.target);
                    case "reinforce" -> ok = store.reinforceByHash(pkt.target);
                    case "deactivate_rel" -> ok = store.deactivateRelationsByPredicate(pkt.target) > 0;
                    default -> ok = false;
                }
                if (!ok) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u8c03\u8bd5\u5931\u8d25\uff1a\u4e0d\u5b58\u5728\u6216\u5df2\u5904\u7406\u3002"));
                    return;
                }
                // 成功后回发最新快照（客户端即时刷新）
                sendDebugResponse(player, maid, level, pkt.maidUuid);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.192：回发调试快照（请求/动作成功后共用） */
    private static void sendDebugResponse(ServerPlayer player, EntityMaid maid,
                                          net.minecraft.server.level.ServerLevel level, String uuid) {
        PacketDistributor.sendToPlayer(player, new MaidDebugResponsePacket(uuid,
                        collectDebugStatus(maid, level),
                        collectDebugRows(maid, level)));
    }

    /**
     * v1.5.198：语音包导入（C2S）——设置面板"语音"页导入路径填写保存后发送。
     * 服务端：导入 zip/文件夹 → 重载 manifest → 结果直接回玩家聊天框。
     */
    public static class VoicePackImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VoicePackImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "voice_pack_import"));
        public final String path;

        public VoicePackImportPacket(String path) {
            this.path = path;
        }

        public static void encode(VoicePackImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static VoicePackImportPacket decode(FriendlyByteBuf buf) {
            return new VoicePackImportPacket(buf.readUtf());
        }

        public static void handle(VoicePackImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：联机服务器上路径来自客户端、不可信（任意文件读取）——
                // 仅 OP 可用（管理员凭服务端本地路径导入）；单机/局域网主机不受影响
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.voice.SystemVoicePack.importPack(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.224：导入世界地图（C2S）——手册"导入世界地图"按钮选完 .zip 后发送路径；
     * 服务端复制到 blueprints 目录并从世界存档提取建筑，结果（块数/尺寸）回聊天框。
     */
    public static class WorldImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "world_import"));
        public final String path;

        public WorldImportPacket(String path) {
            this.path = path;
        }

        public static void encode(WorldImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static WorldImportPacket decode(FriendlyByteBuf buf) {
            return new WorldImportPacket(buf.readUtf());
        }

        public static void handle(WorldImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：同语音包导入——联机服务器仅 OP
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.build.BlueprintLib.importWorldFile(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.5.220：导入建筑（C2S）——手册"导入建筑"按钮选完文件后发送绝对路径；
     * 服务端复制到 config/maid_smart/blueprints/ 并解析校验注册，结果回聊天框。
     * 单机场景客户端与服务端同机，绝对路径服务端可直接读取。
     */
    public static class BuildImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_import"));
        public final String path;

        public BuildImportPacket(String path) {
            this.path = path;
        }

        public static void encode(BuildImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static BuildImportPacket decode(FriendlyByteBuf buf) {
            return new BuildImportPacket(buf.readUtf());
        }

        public static void handle(BuildImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：同语音包导入——联机服务器仅 OP
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.build.BlueprintLib.importBuildFile(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** v1.5.198：语音包查询（C2S）——kind="reload" 重新加载 manifest；kind="status"
     *  只查看状态。结果（映射条数 + 缓存文件数）直接回玩家聊天框。
     */
    public static class VoicePackQueryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VoicePackQueryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "voice_pack_query"));
        public final String kind;

        public VoicePackQueryPacket(String kind) {
            this.kind = kind;
        }

        public static void encode(VoicePackQueryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.kind);
        }

        public static VoicePackQueryPacket decode(FriendlyByteBuf buf) {
            return new VoicePackQueryPacket(buf.readUtf());
        }

        public static void handle(VoicePackQueryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                if ("reload".equals(pkt.kind)) {
                    com.maidsmart.voice.SystemVoicePack.reload();
                }
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e" + com.maidsmart.voice.SystemTTSManager.statusText()));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ==================== v1.1.0 实测八十二：蓝图投影 ====================

    /** C2S：请求某蓝图的投影点云（金色预览/红色区块首次出现时发送；
     *  服务端抽壳降采样后回 ProjectionDataPacket）。
     *  v1.1.0 实测九十七：+quarters——按 P 旋转后客户端请求对应朝向的点云。 */
    public static class ProjectionRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProjectionRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "projection_request"));
        public final String blueprintId;
        public final int quarters;

        public ProjectionRequestPacket(String blueprintId, int quarters) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
        }

        public static void encode(ProjectionRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId == null ? "" : pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
        }

        public static ProjectionRequestPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            return new ProjectionRequestPacket(id, q);
        }

        public static void handle(ProjectionRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                String id = pkt.blueprintId == null ? "" : pkt.blueprintId;
                int q = Math.floorMod(pkt.quarters, 4);
                // v1.1.0 实测八十三b：全程 try/catch + 日志——旧版异常静默（enqueueWork
                // 吞掉堆栈），投影断链无从排查
                try {
                    // v1.1.0 实测九十七：holder 从发送方所在维度取（旋转 BlockState 必需）
                    var holder = player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK);
                    List<String> centered = BlueprintProjectionSampler.centeredStepsOf(id, q, holder);
                    if (centered == null || centered.isEmpty()) {
                        LOGGER.info("projection: id={} q={} unavailable (missing/empty), reply empty", id, q);
                        PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q, "0,0,0", ""));
                        return;
                    }
                    // v1.1.0 实测九十七：旋转版尺寸不进 SIZE_CACHE（该缓存按 id 存未旋转尺寸）
                    int[] sz = q == 0 ? BlueprintLib.blueprintSizeCached(id, centered)
                            : BlueprintLib.blueprintSize(centered);
                    String cloud = BlueprintProjectionSampler.sampleCloud(id, q, holder);
                    PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q,
                                    sz[0] + "," + sz[1] + "," + sz[2],
                                    cloud));
                    LOGGER.info("projection: id={} q={} size={}x{}x{} chars={} (sent)",
                            id, q, sz[0], sz[1], sz[2], cloud.length());
                } catch (Exception e) {
                    LOGGER.error("projection: generate failed id={} q={}", id, q, e);
                    PacketDistributor.sendToPlayer(player, new ProjectionDataPacket(id, q, "0,0,0", ""));
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C：投影点云下发。cloud = "x,y,z;x,y,z;…"（相对居中坐标；空串 = 无投影，
     *  客户端据此清缓存）。尺寸字段随包携带（客户端暂仅用于调试显示）。
     *  v1.1.0 实测八十三b【分块传输】：FriendlyByteBuf.writeUtf 单字符串上限
     *  32767 字符——大蓝图点云（≤3000 点 × 每点 12~16 字符 ≈ 最高 4.8 万字符）
     *  在服务端编码阶段就超限，netty 抛异常、包静默发不出，客户端永远等不到数据
     * （"投影完全不显示"的根因）。改为每块 ≤16000 字符分多条写，客户端拼接还原，
     *  尺寸上限彻底解除。 */
    private static final int PROJECTION_CHUNK_CHARS = 16000;

    public static class ProjectionDataPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ProjectionDataPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "projection_data"));
        public final String blueprintId;
        /** v1.1.0 实测九十七：朝向（0~3 × 90°）——客户端按 id#quarters 缓存 */
        public final int quarters;
        public final String size;
        public final String cloud;

        public ProjectionDataPacket(String blueprintId, int quarters, String size, String cloud) {
            this.blueprintId = blueprintId;
            this.quarters = quarters;
            this.size = size;
            this.cloud = cloud;
        }

        public static void encode(ProjectionDataPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId == null ? "" : pkt.blueprintId);
            buf.writeUtf(String.valueOf(pkt.quarters));
            buf.writeUtf(pkt.size == null ? "0,0,0" : pkt.size);
            String cloud = pkt.cloud == null ? "" : pkt.cloud;
            int chunks = Math.max(1, (cloud.length() + PROJECTION_CHUNK_CHARS - 1)
                    / PROJECTION_CHUNK_CHARS);
            buf.writeUtf(String.valueOf(chunks));
            for (int i = 0; i < chunks; i++) {
                int from = i * PROJECTION_CHUNK_CHARS;
                int to = Math.min(cloud.length(), from + PROJECTION_CHUNK_CHARS);
                buf.writeUtf(cloud.substring(from, to));
            }
        }

        public static ProjectionDataPacket decode(FriendlyByteBuf buf) {
            String id = buf.readUtf();
            int q = 0;
            try {
                q = Integer.parseInt(buf.readUtf());
            } catch (NumberFormatException ignored) {
            }
            String size = buf.readUtf();
            int chunks = Integer.parseInt(buf.readUtf());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks && i < 64; i++) { // 上限防御：正常 ≤4 块
                sb.append(buf.readUtf());
            }
            return new ProjectionDataPacket(id, q, size, sb.toString());
        }

        public static void handle(ProjectionDataPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.build.BlueprintAreaPreview.setProjection(
                            pkt.blueprintId, pkt.quarters, pkt.size, pkt.cloud));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.1.0 实测九十五：建造区块行每秒同步（S2C）——行格式与
     * collectBuildRegions 一致（15 字段：planId/显示名/维度/状态/box min xyz/
     * WHD/blueprintId/创建原点 xyz/朝向 quarters）。客户端
     * BlueprintAreaPreview.setRegions 据此画红色区块框 + 橙色幽灵方块投影；
     * 空列表 = 计划取消/完成 → 客户端清空。
     */
    public static class RegionSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RegionSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "region_sync"));
        public final List<String[]> regions;

        public RegionSyncPacket(List<String[]> regions) {
            this.regions = regions;
        }

        public static void encode(RegionSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(String.valueOf(pkt.regions == null ? 0 : pkt.regions.size()));
            if (pkt.regions != null) {
                for (String[] r : pkt.regions) {
                    // v1.1.0 实测九十七：15 字段（追加 r[14] 朝向 quarters）
                    for (int i = 0; i < 15; i++) {
                        buf.writeUtf(r.length > i ? r[i] : "");
                    }
                }
            }
        }

        public static RegionSyncPacket decode(FriendlyByteBuf buf) {
            int size = Integer.parseInt(buf.readUtf());
            List<String[]> regions = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String[] r = new String[15];
                for (int j = 0; j < 15; j++) {
                    r[j] = buf.readUtf();
                }
                regions.add(r);
            }
            return new RegionSyncPacket(regions);
        }

        public static void handle(RegionSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.build.BlueprintAreaPreview.setRegions(pkt.regions));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.1.0 实测四百二十：内置日语语音包播放（S2C）。
     * 服务端匹配到系统消息对应的日语语音后发本包；客户端按 maidId 找女仆、
     * 从自己的 jar 取出同名 ogg 播放，并在播放窗口内压制 TLM 原生语音包。
     */
    public static class PlayJarVoicePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayJarVoicePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "play_jar_voice"));
        public final int maidId;
        /** jar 内音频文件名（assets/promaid/voice/ 下，如 "build_done.ogg"） */
        public final String file;

        public PlayJarVoicePacket(int maidId, String file) {
            this.maidId = maidId;
            this.file = file;
        }

        public static void encode(PlayJarVoicePacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.writeUtf(pkt.file == null ? "" : pkt.file);
        }

        public static PlayJarVoicePacket decode(FriendlyByteBuf buf) {
            return new PlayJarVoicePacket(buf.readInt(), buf.readUtf());
        }

        public static void handle(PlayJarVoicePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.client.PromaidVoiceSoundInstance.playFromPacket(pkt.maidId, pkt.file));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.1.0 实测九十五：向全体玩家广播当前建造区块行（ProMaidExtension 每秒
     * HUD 广播块内调用）。计划数量通常个位数、每行 14 个短串，全量广播开销可忽略；
     * 无计划时发空列表让客户端清掉残留的框与投影。
     */
    public static void broadcastRegionSync(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        RegionSyncPacket pkt = new RegionSyncPacket(collectBuildRegions(server));
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                PacketDistributor.sendToPlayer(p, pkt);
            } catch (Exception ignored) {
            }
        }
    }
}
