package com.maidsmart.accessory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * v1.5.394 实测八百零五：「大运」BGM 的网络层（S2C 单包）。
 *
 * <p>照搬 hatmod 的 {@code HatNetwork.MusicMessage}：服务端把
 * {@code (佩戴者实体 id, 曲目序号, 开始/停止)} 发给听得见的玩家，
 * 客户端 {@code DaYunMusicPlayer} 据此为每个佩戴者建/停一条**自己的**声音实例。
 *
 * <p>【为什么不发 playSound 包】原版声音包不带"这条音乐属于哪个实体"，
 * 两个佩戴者同曲时会互相掐（详见 {@link DaYunMusic} 类注释）。
 *
 * <p>【1.20.1 落法】本树用 Forge {@link SimpleChannel}（本仓库 8 处同样写法，
 * 如 {@code MaidFreeFlightNetworking}）。
 */
public final class DaYunMusicNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "da_yun_music"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private DaYunMusicNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, MusicPacket.class,
                MusicPacket::encode, MusicPacket::decode, MusicPacket::handle);
    }

    /** 服务端 → 听得见的玩家：某个佩戴者的 BGM 开始 / 停止了。 */
    public static void sendMusic(ServerPlayer player, int wearerId, int track, boolean playing) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new MusicPacket(wearerId, track, playing));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("DaYunMusicNetworking", ignored);
        }
    }

    public static class MusicPacket {
        public final int wearerId;
        public final int track;
        public final boolean playing;

        public MusicPacket(int wearerId, int track, boolean playing) {
            this.wearerId = wearerId;
            this.track = track;
            this.playing = playing;
        }

        public static void encode(MusicPacket pkt, FriendlyByteBuf buf) {
            buf.m_130130_(pkt.wearerId);
            buf.m_130130_(pkt.track);
            buf.writeBoolean(pkt.playing);
        }

        public static MusicPacket decode(FriendlyByteBuf buf) {
            return new MusicPacket(buf.m_130242_(), buf.m_130242_(), buf.readBoolean());
        }

        public static void handle(MusicPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.handleDaYunMusic(
                    pkt.wearerId, pkt.track, pkt.playing));
            ctx.setPacketHandled(true);
        }
    }
}
