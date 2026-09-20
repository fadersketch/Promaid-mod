package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * v1.2.2 实测五百八十七：轰炸标记网络层（S2C，单包）。
 *
 * 需求原文："女仆放置的这些物品默认会加一层很淡的粉色渲染，当然，这个效果也可以在手册内关闭。"
 *
 * 【为什么必须走包】"哪些东西是女仆刚放下的"是纯服务端知识（客户端只看得到世界里多了个
 * 黑曜石/水晶/TNT），客户端要画出那层淡粉色只能由服务端告诉它。标记自带存活时间
 * （ttl，tick），客户端自己到期清理——不需要"消失通知"，包量每发炸弹就一个。
 *
 * 【发谁】发"正在追踪这只女仆的玩家"（与她的实体追踪范围一致）：她一定在炸弹旁边，
 * 能看到她的玩家就在附近，正是需要看到那层粉色的人。多人下也不刷屏。
 */
public final class BombMarkNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "bomb_mark"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private BombMarkNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, MarkPacket.class,
                MarkPacket::encode, MarkPacket::decode, MarkPacket::handle);
    }

    /**
     * 服务端 → 附近玩家：标记一个"女仆刚放下/刚扔出"的东西。
     * kind=0：方块（用 x/y/z）；kind=1：实体（用 entityId，客户端按实体实时位置画，
     * 所以扔出去的 TNT 那层粉框会跟着飞）。
     */
    public static void send(EntityMaid maid, int kind, int entityId, BlockPos pos, int ttl) {
        if (maid == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> maid),
                    new MarkPacket(kind, entityId,
                            pos == null ? 0 : pos.m_123341_(),
                            pos == null ? 0 : pos.m_123342_(),
                            pos == null ? 0 : pos.m_123343_(),
                            ttl));
        } catch (Throwable ignored) {
        }
    }

    public static class MarkPacket {
        public final int kind;
        public final int entityId;
        public final int x;
        public final int y;
        public final int z;
        public final int ttl;

        public MarkPacket(int kind, int entityId, int x, int y, int z, int ttl) {
            this.kind = kind;
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ttl = ttl;
        }

        public static void encode(MarkPacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.kind);
            buf.writeInt(pkt.entityId);
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
            buf.writeInt(pkt.ttl);
        }

        public static MarkPacket decode(FriendlyByteBuf buf) {
            return new MarkPacket(buf.readByte(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(MarkPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                int ttl = Math.max(1, Math.min(pkt.ttl, 6000));
                if (pkt.kind == 1) {
                    com.maidsmart.client.BombMarkClient.markEntity(pkt.entityId, ttl);
                } else {
                    com.maidsmart.client.BombMarkClient.markBlock(pkt.x, pkt.y, pkt.z, ttl);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
