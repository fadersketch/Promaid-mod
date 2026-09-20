package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * v1.2.2 实测五百八十七：轰炸标记网络层（S2C，单包，1.21.1 NeoForge 版）。
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
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class BombMarkNetworking {

    private BombMarkNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(MarkPacket.TYPE,
                StreamCodec.ofMember(MarkPacket::encode, MarkPacket::decode),
                MarkPacket::handle);
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
            PacketDistributor.sendToPlayersTrackingEntity(maid,
                    new MarkPacket(kind, entityId,
                            pos == null ? 0 : pos.getX(),
                            pos == null ? 0 : pos.getY(),
                            pos == null ? 0 : pos.getZ(),
                            ttl));
        } catch (Throwable ignored) {
        }
    }

    public static class MarkPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MarkPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "bomb_mark"));

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

        public static void handle(MarkPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                int ttl = Math.max(1, Math.min(pkt.ttl, 6000));
                if (pkt.kind == 1) {
                    com.maidsmart.client.BombMarkClient.markEntity(pkt.entityId, ttl);
                } else {
                    com.maidsmart.client.BombMarkClient.markBlock(pkt.x, pkt.y, pkt.z, ttl);
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
