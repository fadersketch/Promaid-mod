package com.maidsmart.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 女仆放置方块的金色描边同步（S2C，实测一千〇六，1.21.1 NeoForge 版）。
 * 【口径】描边对象 = {@code PlacedBlockTracker} 全部实例登记的女仆搭方块（搭路 / 垫脚 /
 * 挖矿垫块 / 战斗垫块）——蓝图建造与指标石临时搭建不走追踪表，天然不在描边范围。
 * "哪些方块是她搭的"是纯服务端知识，客户端要画描边只能由服务端告知。
 * 【增删怎么发】登记 / 移除逐格累积，满 {@code FLUSH_OPS} 格或每 {@code FLUSH_TICKS}
 * tick 合并成一包，发给该维度全部玩家（搭路每秒几格，批量后包量与方块量同阶）。
 * 【快照】进服 / 换维度补一份全量（{@code SNAPSHOT_CHUNK} 格一包，第一包 full 清底），
 * 断线重连不丢描边；退出世界由客户端自清（见 {@code PlacedMarkClient}）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class PlacedMarkNetworking {

    /** 全量快照分片大小 */
    private static final int SNAPSHOT_CHUNK = 1024;
    /** 增删合并阈值：攒够这么多格先发一包 */
    private static final int FLUSH_OPS = 512;
    /** 增删合并节流：距上次发包超过这么多 tick 就发一包 */
    private static final long FLUSH_TICKS = 5L;
    /** 描边存活到方块本身被回收：不再按 3 秒 TTL 自动淡出。
     *  旧版金色光标会先于方块消失，战场上看不出哪些是女仆的垫高块；
     *  撤框统一由 {@link #noteRemove}（回收/拆除/替换）触发。 */
    private static final long OUTLINE_ALIVE = Long.MAX_VALUE;

    /** 逐维度描边到期时刻（gameTime）；当前用 {@link #OUTLINE_ALIVE} 表示跟随方块寿命 */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> OUTLINE_UNTIL = new HashMap<>();
    /** 逐维度描边格 → 登记时的方块注册名：客户端逐帧比对，方块被替换也立即不画 */
    private static final Map<ResourceKey<Level>, Map<BlockPos, String>> OUTLINE_BLOCK = new HashMap<>();

    /** 服务端对账节流：每 10 tick 核对一次"描边格是否还是她登记的那块方块" */
    private static final long RECONCILE_TICKS = 10L;
    private static long lastReconcile;
    private static long lastReconcileLog = -100000L;

    /** 逐维度待发包（服务端主线程读写；true = 描边，false = 撤描边） */
    private static final class Pending {
        final Map<BlockPos, Boolean> ops = new java.util.LinkedHashMap<>();
        long lastFlush;
        /** 实测一千〇六·续三：链路诊断日志节流（每维度 2 秒最多一条） */
        long lastLog = -100000L;
    }

    private static final Map<ResourceKey<Level>, Pending> PENDING = new HashMap<>();

    private PlacedMarkNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("2");
        r.playToClient(MarksPacket.TYPE,
                StreamCodec.ofMember(MarksPacket::encode, MarksPacket::decode),
                MarksPacket::handle);
    }

    /** 开关（描边 + 强制回收共用一条；关 = 一个包都不发、右键也不接管） */
    private static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.MISC_PLACED_GOLD_MARK.get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
            return true;
        }
    }

    /** 登记一个描边点（PlacedBlockTracker.track 调用） */
    public static void noteAdd(Level level, BlockPos pos, String blockId) {
        note(level, pos, blockId, true, true);
    }

    /** 撤销一个描边点（到期回收 / 强制回收 / 女仆主动拆除调用） */
    public static void noteRemove(Level level, BlockPos pos) {
        note(level, pos, "", false, true);
    }

    /** 对账补帧等批量场景：仍走 5 tick 合并（一次可能补几百格，逐格发会刷包） */
    private static void note(Level level, BlockPos pos, String blockId, boolean add) {
        note(level, pos, blockId, add, false);
    }

    /**
     * @param immediate 实测一千〇七【描边跟手】：真实放置/拆除必须立即发。旧版一律
     *  按 5 tick 合并，刚放下的那一格要等下一次 flush 才到客户端——连续搭路时
     *  永远慢一拍（反馈："黄色光标有概率会有一个方块左右的延迟"）。方块本身
     *  一两秒才一格，逐格发不存在包量问题；只有对账补帧才需要合并。
     */
    private static void note(Level level, BlockPos pos, String blockId, boolean add, boolean immediate) {
        try {
            if (!enabled() || !(level instanceof ServerLevel sl) || pos == null) {
                return;
            }
            Pending p = PENDING.computeIfAbsent(sl.dimension(), k -> new Pending());
            long now = sl.getGameTime();
            BlockPos key = pos.immutable();
            if (add) {
                OUTLINE_UNTIL.computeIfAbsent(sl.dimension(), k -> new HashMap<>())
                        .put(key, OUTLINE_ALIVE);
                OUTLINE_BLOCK.computeIfAbsent(sl.dimension(), k -> new HashMap<>())
                        .put(key, blockId == null ? "" : blockId);
            } else {
                Map<BlockPos, Long> until = OUTLINE_UNTIL.get(sl.dimension());
                if (until != null) {
                    until.remove(key);
                    if (until.isEmpty()) {
                        OUTLINE_UNTIL.remove(sl.dimension());
                    }
                }
                Map<BlockPos, String> ids = OUTLINE_BLOCK.get(sl.dimension());
                if (ids != null) {
                    ids.remove(key);
                    if (ids.isEmpty()) {
                        OUTLINE_BLOCK.remove(sl.dimension());
                    }
                }
            }
            p.ops.put(key, add);
            if (immediate || p.ops.size() >= FLUSH_OPS || now - p.lastFlush >= FLUSH_TICKS) {
                flush(sl, p, now);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
        }
    }

    private static void flush(ServerLevel level, Pending p, long now) {
        if (p.ops.isEmpty()) {
            p.lastFlush = now;
            return;
        }
        List<int[]> adds = new ArrayList<>();
        List<String> addIds = new ArrayList<>();
        List<int[]> rems = new ArrayList<>();
        Map<BlockPos, String> ids = OUTLINE_BLOCK.get(level.dimension());
        for (Map.Entry<BlockPos, Boolean> e : p.ops.entrySet()) {
            BlockPos pos = e.getKey();
            if (e.getValue()) {
                adds.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
                addIds.add(ids == null ? "" : ids.getOrDefault(pos, ""));
            } else {
                rems.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
            }
        }
        p.ops.clear();
        p.lastFlush = now;
        if (now - p.lastLog >= 40L) {
            p.lastLog = now;
            com.maidsmart.tool.PromaidLog.log("描边", "同步 增" + adds.size() + " 撤" + rems.size()
                    + " → " + level.dimension().location().getPath());
        }
        send(level, new MarksPacket(false, adds, addIds, rems));
    }

    private static void send(ServerLevel level, MarksPacket pkt) {
        try {
            // 实测一千〇六·续三：逐玩家发（与 ConfigSyncNetworking.broadcast 同款分发口）——
            // sendToPlayersInDimension 是本工程唯一一处用法、未经实测覆盖，换成实测过的 sendToPlayer；
            // 只发本维度玩家（客户端位置表不带维度，跨维度发会把别维度的框画到本维度）。
            for (ServerPlayer sp : level.players()) {
                PacketDistributor.sendToPlayer(sp, pkt);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
        }
    }

    /** 每 tick 检查描边存活：当前策略是"跟随方块本身"（{@link #OUTLINE_ALIVE}），
     *  这里保留到期扫描作为兜底；正常撤框走 {@link #noteRemove}。 */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        try {
            if (server == null) {
                return;
            }
            if (!enabled()) {
                OUTLINE_UNTIL.clear();
                OUTLINE_BLOCK.clear();
                return;
            }
            for (ServerLevel sl : server.getAllLevels()) {
                Map<BlockPos, Long> until = OUTLINE_UNTIL.get(sl.dimension());
                if (until == null || until.isEmpty()) {
                    continue;
                }
                long now = sl.getGameTime();
                List<BlockPos> expired = null;
                Iterator<Map.Entry<BlockPos, Long>> it = until.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<BlockPos, Long> e = it.next();
                    if (now < e.getValue()) {
                        continue;
                    }
                    it.remove();
                    Map<BlockPos, String> ids = OUTLINE_BLOCK.get(sl.dimension());
                    if (ids != null) {
                        ids.remove(e.getKey());
                        if (ids.isEmpty()) {
                            OUTLINE_BLOCK.remove(sl.dimension());
                        }
                    }
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(e.getKey());
                }
                if (until.isEmpty()) {
                    OUTLINE_UNTIL.remove(sl.dimension());
                }
                if (expired != null) {
                    for (BlockPos pos : expired) {
                        note(sl, pos, "", false);
                    }
                }
            }
            // 硬绑定：每 10 tick 对账一次——描边格必须仍然是"她登记的那块方块"。
            // 方块被炸掉/替换/自然消失而没有走 untrackAny 时，这里立刻撤框。
            long now = server.overworld().getGameTime();
            if (now - lastReconcile >= RECONCILE_TICKS) {
                lastReconcile = now;
                for (ServerLevel sl : server.getAllLevels()) {
                    reconcile(sl);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
        }
    }

    /**
     * 描边与方块硬绑定：描边格必须仍是 {@code PlacedBlockTracker} 登记的那块方块。
     * 方块消失/被替换（爆炸、活塞、玩家、其他模组）→ 立刻撤框并清登记。
     */
    private static void reconcile(ServerLevel sl) {
        try {
            Map<BlockPos, Long> until = OUTLINE_UNTIL.get(sl.dimension());
            List<BlockPos> stale = null;
            if (until != null && !until.isEmpty()) {
                for (BlockPos pos : new ArrayList<>(until.keySet())) {
                    if (!sl.isLoaded(pos)) {
                        continue; // 区块没加载：拿不到真实方块，先保留
                    }
                    String tracked = PlacedBlockTracker.trackedBlockId(sl, pos);
                    Map<BlockPos, String> ids = OUTLINE_BLOCK.get(sl.dimension());
                    String expected = ids == null ? null : ids.get(pos);
                    if (expected == null || expected.isEmpty()) {
                        expected = tracked;
                    }
                    BlockState state = sl.getBlockState(pos);
                    ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    boolean match = expected != null && !state.isAir()
                            && key != null && expected.equals(key.toString());
                    if (!match) {
                        if (stale == null) {
                            stale = new ArrayList<>();
                        }
                        stale.add(pos);
                    }
                }
            }
            if (stale != null) {
                for (BlockPos pos : stale) {
                    PlacedBlockTracker.untrackAny(sl, pos); // 内部会 noteRemove
                    note(sl, pos, "", false);                  // 没有登记时也兜底撤框
                }
                long now = sl.getGameTime();
                if (now - lastReconcileLog >= 40L) {
                    lastReconcileLog = now;
                    BlockPos p = stale.get(0);
                    com.maidsmart.tool.PromaidLog.log("描边", "对账撤框 " + stale.size()
                            + " 格（方块已消失/被替换）@(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")");
                }
            }
            // 反向补齐：仍登记但没有描边的，补一帧
            for (BlockPos pos : PlacedBlockTracker.snapshot(sl)) {
                Map<BlockPos, Long> cur = OUTLINE_UNTIL.get(sl.dimension());
                if (cur == null || !cur.containsKey(pos)) {
                    String id = PlacedBlockTracker.trackedBlockId(sl, pos);
                    note(sl, pos, id == null ? "" : id, true);
                }
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
        }
    }

    /** 进服 / 换维度：把该维度当前全部描边点分片补发（第一包清底；开关关着 = 补空清底） */
    public static void pushSnapshot(ServerPlayer player) {
        try {
            if (player == null || !(player.level() instanceof ServerLevel sl)) {
                return;
            }
            List<BlockPos> all = enabled()
                    ? new ArrayList<>(PlacedBlockTracker.snapshot(sl)) : new ArrayList<>();
            if (!all.isEmpty()) {
                Map<BlockPos, Long> until = OUTLINE_UNTIL.get(sl.dimension());
                long now = sl.getGameTime();
                all.removeIf(pos -> {
                    Long deadline = until == null ? null : until.get(pos);
                    return deadline == null || now >= deadline;
                });
            }
            com.maidsmart.tool.PromaidLog.log("描边", "全量补发 " + all.size() + " 格"
                    + (enabled() ? "" : "（开关关）") + " → " + player.getName().getString());
            if (all.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new MarksPacket(true, List.of(), List.of(), List.of()));
                return;
            }
            boolean first = true;
            Map<BlockPos, String> ids = OUTLINE_BLOCK.get(sl.dimension());
            for (int i = 0; i < all.size(); i += SNAPSHOT_CHUNK) {
                List<int[]> chunk = new ArrayList<>();
                List<String> chunkIds = new ArrayList<>();
                for (int j = i; j < Math.min(all.size(), i + SNAPSHOT_CHUNK); j++) {
                    BlockPos pos = all.get(j);
                    chunk.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
                    chunkIds.add(ids == null ? "" : ids.getOrDefault(pos, ""));
                }
                PacketDistributor.sendToPlayer(player, new MarksPacket(first, chunk, chunkIds, List.of()));
                first = false;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PlacedMarkNetworking", ignored);
        }
    }

    /** 服务器停止清场（防跨存档残留；方块本身由 PlacedBlockTracker.clearAll 回收） */
    public static void clearAll() {
        PENDING.clear();
        OUTLINE_UNTIL.clear();
        OUTLINE_BLOCK.clear();
    }

    /** full=true：客户端清底后灌入 add；false：增删合并包（按 pos 逐个应用）。
     *  addIds 与 add 等长：服务端登记时的方块注册名，供客户端逐帧核对"还是不是那块"。 */
    public static class MarksPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MarksPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "placed_mark"));

        public final boolean full;
        public final List<int[]> add;
        public final List<String> addIds;
        public final List<int[]> remove;

        public MarksPacket(boolean full, List<int[]> add, List<int[]> remove) {
            this(full, add, java.util.Collections.emptyList(), remove);
        }

        public MarksPacket(boolean full, List<int[]> add, List<String> addIds, List<int[]> remove) {
            this.full = full;
            this.add = add == null ? java.util.Collections.emptyList() : add;
            this.addIds = addIds == null ? java.util.Collections.emptyList() : addIds;
            this.remove = remove == null ? java.util.Collections.emptyList() : remove;
        }

        public static void encode(MarksPacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.full);
            writeList(buf, pkt.add);
            writeStringList(buf, pkt.addIds);
            writeList(buf, pkt.remove);
        }

        private static void writeList(FriendlyByteBuf buf, List<int[]> list) {
            buf.writeInt(list.size());
            for (int[] c : list) {
                buf.writeInt(c[0]);
                buf.writeInt(c[1]);
                buf.writeInt(c[2]);
            }
        }

        private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
            buf.writeInt(list.size());
            for (String s : list) {
                buf.writeUtf(s == null ? "" : s);
            }
        }

        public static MarksPacket decode(FriendlyByteBuf buf) {
            boolean full = buf.readBoolean();
            List<int[]> add = readList(buf);
            List<String> addIds = readStringList(buf);
            List<int[]> remove = readList(buf);
            return new MarksPacket(full, add, addIds, remove);
        }

        private static List<int[]> readList(FriendlyByteBuf buf) {
            int declared = buf.readInt();
            int n = Math.max(0, Math.min(declared, 4096));
            List<int[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new int[]{buf.readInt(), buf.readInt(), buf.readInt()});
            }
            return out;
        }

        private static List<String> readStringList(FriendlyByteBuf buf) {
            int declared = buf.readInt();
            int n = Math.max(0, Math.min(declared, 4096));
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(buf.readUtf());
            }
            return out;
        }

        public static void handle(MarksPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PlacedMarkClient.apply(
                    pkt.full, pkt.add, pkt.addIds, pkt.remove));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
