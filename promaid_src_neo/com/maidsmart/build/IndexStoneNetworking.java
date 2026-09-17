package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * 指标石网络层（v1.2.0，1.21.1 NeoForge 版）。
 *
 * - C2S LockRequestPacket：客户端把"长射线锁定/解锁"的位置发给服务端
 *   （绿框跟随指针是纯客户端实时渲染；真正锁定必须由服务端记录会话 + 校验不可锁空气）。
 * - S2C StatePacket：服务端把会话状态（锁定方块 / 绑定女仆 UUID / 蓝图格集合）下发，
 *   橙影幽灵格以【服务端展开的同一份格集合】渲染，保证"看到什么就填什么"。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class IndexStoneNetworking {

    private IndexStoneNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToServer(LockRequestPacket.TYPE,
                StreamCodec.ofMember(LockRequestPacket::encode, LockRequestPacket::decode),
                LockRequestPacket::handle);
        r.playToClient(StatePacket.TYPE,
                StreamCodec.ofMember(StatePacket::encode, StatePacket::decode),
                StatePacket::handle);
    }

    /** 服务端 → 该玩家：下发当前会话状态 */
    public static void syncTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        IndexStoneService.Session s = IndexStoneService.session(player.getUUID());
        if (s == null) {
            PacketDistributor.sendToPlayer(player,
                    new StatePacket(false, 0, 0, 0, "", java.util.Collections.emptyList()));
            return;
        }
        boolean locked = s.isLocked();
        int x = locked ? s.lockedBlock.getX() : 0;
        int y = locked ? s.lockedBlock.getY() : 0;
        int z = locked ? s.lockedBlock.getZ() : 0;
        String maid = s.maidId == null ? "" : s.maidId.toString();
        PacketDistributor.sendToPlayer(player, new StatePacket(locked, x, y, z, maid, s.cells));
    }

    // ==================== C2S：锁定/解锁请求 ====================

    public static class LockRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LockRequestPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "maid_smart", "index_stone_lock"));

        public final boolean unlock;
        public final int x;
        public final int y;
        public final int z;

        public LockRequestPacket(boolean unlock, int x, int y, int z) {
            this.unlock = unlock;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static void encode(LockRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.unlock);
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
        }

        public static LockRequestPacket decode(FriendlyByteBuf buf) {
            return new LockRequestPacket(buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(LockRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !IndexStoneService.isEnabled()) {
                    return;
                }
                // 安全校验：必须手持指标石（防伪造包）
                if (!IndexStoneInteractHandler.isIndexStone(
                        player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND))
                        && !IndexStoneInteractHandler.isIndexStone(
                        player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND))) {
                    return;
                }
                if (pkt.unlock) {
                    IndexStoneService.toggleLock(player, null);
                } else {
                    BlockPos pos = new BlockPos(pkt.x, pkt.y, pkt.z);
                    if (player.position().distanceToSqr(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                            > IndexStoneService.LOCK_RANGE * IndexStoneService.LOCK_RANGE) {
                        return;
                    }
                    IndexStoneService.toggleLock(player, pos);
                }
                IndexStoneNetworking.syncTo(player);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==================== S2C：状态同步 ====================

    public static class StatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "maid_smart", "index_stone_state"));

        public final boolean locked;
        public final int x;
        public final int y;
        public final int z;
        public final String maid;
        public final List<int[]> cells;

        public StatePacket(boolean locked, int x, int y, int z, String maid, List<int[]> cells) {
            this.locked = locked;
            this.x = x;
            this.y = y;
            this.z = z;
            this.maid = maid == null ? "" : maid;
            this.cells = cells == null ? java.util.Collections.emptyList() : cells;
        }

        public static void encode(StatePacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.locked);
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
            buf.writeUtf(pkt.maid);
            buf.writeInt(pkt.cells.size());
            for (int[] c : pkt.cells) {
                buf.writeInt(c[0]);
                buf.writeInt(c[1]);
                buf.writeInt(c[2]);
            }
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            boolean locked = buf.readBoolean();
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String maid = buf.readUtf();
            int n = Math.max(0, Math.min(buf.readInt(), IndexStonePlan.MAX_CELLS));
            List<int[]> cells = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                cells.add(new int[]{buf.readInt(), buf.readInt(), buf.readInt()});
            }
            return new StatePacket(locked, x, y, z, maid, cells);
        }

        public static void handle(StatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.build.IndexStonePreviewClient.set(
                    pkt.locked, pkt.x, pkt.y, pkt.z, pkt.maid, pkt.cells));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
