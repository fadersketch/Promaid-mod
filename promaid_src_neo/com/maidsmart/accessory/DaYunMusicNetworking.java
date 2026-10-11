package com.maidsmart.accessory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * v1.5.394 实测八百零五：「大运」BGM 的网络层（S2C 单包，NeoForge 1.21.1）。
 *
 * <p>照搬 hatmod 的 {@code HatNetwork.MusicMessage}：服务端把
 * {@code (佩戴者实体 id, 曲目序号, 开始/停止)} 发给听得见的玩家，
 * 客户端 {@code DaYunMusicPlayer} 据此为每个佩戴者建/停一条**自己的**声音实例。
 *
 * <p>【为什么不发 playSound 包】原版声音包不带"这条音乐属于哪个实体"，
 * 两个佩戴者同曲时会互相掐（详见 {@link DaYunMusic} 类注释）。
 *
 * <p>【1.21.1 落法】本树用 NeoForge 的 {@code CustomPacketPayload} +
 * {@code RegisterPayloadHandlersEvent}（与 {@code MaidSeatNetworking} 同一套骨架，
 * 由 {@code @EventBusSubscriber} 自注册）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class DaYunMusicNetworking {

    private DaYunMusicNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(MusicPacket.TYPE,
                StreamCodec.ofMember(MusicPacket::encode, MusicPacket::decode),
                MusicPacket::handle);
    }

    /** 服务端 → 听得见的玩家：某个佩戴者的 BGM 开始 / 停止了。 */
    public static void sendMusic(ServerPlayer player, int wearerId, int track, boolean playing) {
        if (player == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(player, new MusicPacket(wearerId, track, playing));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunMusicNetworking", ignored);
        }
    }

    public static class MusicPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MusicPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "da_yun_music"));

        public final int wearerId;
        public final int track;
        public final boolean playing;

        public MusicPacket(int wearerId, int track, boolean playing) {
            this.wearerId = wearerId;
            this.track = track;
            this.playing = playing;
        }

        public static void encode(MusicPacket pkt, FriendlyByteBuf buf) {
            buf.writeVarInt(pkt.wearerId);
            buf.writeVarInt(pkt.track);
            buf.writeBoolean(pkt.playing);
        }

        public static MusicPacket decode(FriendlyByteBuf buf) {
            return new MusicPacket(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        public static void handle(MusicPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.handleDaYunMusic(
                    pkt.wearerId, pkt.track, pkt.playing));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
