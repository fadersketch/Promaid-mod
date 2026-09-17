package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.function.Supplier;

/**
 * 指标石网络层（v1.2.0）：SimpleChannel，两个包。
 *
 * - C2S LockRequestPacket：客户端把"视线锁定/解锁"的位置发给服务端
 *   （绿框跟随指针是【纯客户端】实时渲染；真正锁定必须由服务端记录会话，
 *    并且要服务端校验不可锁空气）。
 * - S2C StatePacket：服务端把会话状态（锁定方块 / 绑定女仆 UUID / 蓝图格）
 *   下发给客户端——橙影幽灵格以【服务端展开的同一份格集合】渲染，
 *   保证"看到什么就填什么"逐格一致。
 */
public final class IndexStoneNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "index_stone"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private IndexStoneNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, LockRequestPacket.class,
                LockRequestPacket::encode, LockRequestPacket::decode,
                LockRequestPacket::handle);
        CHANNEL.registerMessage(1, StatePacket.class,
                StatePacket::encode, StatePacket::decode,
                StatePacket::handle);
    }

    /** 服务端 → 该玩家：下发当前会话状态（锁/绑/格集合） */
    public static void syncTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        IndexStoneService.Session s = IndexStoneService.session(player.m_20148_());
        if (s == null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new StatePacket(false, 0, 0, 0, "", java.util.Collections.emptyList()));
            return;
        }
        boolean locked = s.isLocked();
        int x = locked ? s.lockedBlock.m_123341_() : 0;
        int y = locked ? s.lockedBlock.m_123342_() : 0;
        int z = locked ? s.lockedBlock.m_123343_() : 0;
        String maid = s.maidId == null ? "" : s.maidId.toString();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new StatePacket(locked, x, y, z, maid, s.cells));
    }

    // ==================== C2S：锁定/解锁请求 ====================

    /**
     * 客户端已把"视线指向的方块"算好（客户端 raycast 才能做到跟随指针 + 超远距离），
     * 这里只提交坐标；服务端做不可锁空气 / 距离 / 开关校验后落会话。
     */
    public static class LockRequestPacket {
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

        public static void handle(LockRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !IndexStoneService.isEnabled()) {
                    return;
                }
                // 安全校验：手持必须是指标石（防伪造包）
                if (!IndexStoneInteractHandler.isIndexStone(player.m_21120_(net.minecraft.world.InteractionHand.MAIN_HAND))
                        && !IndexStoneInteractHandler.isIndexStone(player.m_21120_(net.minecraft.world.InteractionHand.OFF_HAND))) {
                    return;
                }
                if (pkt.unlock) {
                    IndexStoneService.toggleLock(player, null);
                } else {
                    BlockPos pos = new BlockPos(pkt.x, pkt.y, pkt.z);
                    // 距离校验（几乎无视距离，但拒绝异常坐标）
                    // Vec3.atCenterOf 在 1.20.1 是 Vec3.m_82512_?(BlockPos)——SRG 无独立名，
                    // 直接用中心坐标构造（等价、零歧义）
                    if (player.m_20238_(new net.minecraft.world.phys.Vec3(
                            pos.m_123341_() + 0.5, pos.m_123342_() + 0.5, pos.m_123343_() + 0.5))
                            > IndexStoneService.LOCK_RANGE * IndexStoneService.LOCK_RANGE) {
                        return;
                    }
                    IndexStoneService.toggleLock(player, pos);
                }
                IndexStoneNetworking.syncTo(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // ==================== S2C：状态同步 ====================

    public static class StatePacket {
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
            buf.m_130070_(pkt.maid);
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
            String maid = buf.m_130277_();
            int n = Math.max(0, Math.min(buf.readInt(), IndexStonePlan.MAX_CELLS));
            List<int[]> cells = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                cells.add(new int[]{buf.readInt(), buf.readInt(), buf.readInt()});
            }
            return new StatePacket(locked, x, y, z, maid, cells);
        }

        public static void handle(StatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.maidsmart.build.IndexStonePreviewClient.set(
                    pkt.locked, pkt.x, pkt.y, pkt.z, pkt.maid, pkt.cells));
            ctx.get().setPacketHandled(true);
        }
    }
}
