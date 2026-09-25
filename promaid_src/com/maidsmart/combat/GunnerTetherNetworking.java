package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * v1.3.7 实测六百六十七：武装拴绳网络层（S2C，单包，骨架同 {@link BombMarkNetworking}）。
 *
 * 【为什么必须走包】挂载关系里"谁是她的二号位枪手"是服务端知识（客户端虽然能看到
 * 原版同步的乘客关系，但分不清这个乘客是拴绳挂的、TLM 的可骑还是别的模组干的）——
 * {@link GunnerTetherManager#SYNCED_PAIRS} 供 mixin 定位（挂下方）与绳子渲染，
 * 只能由服务端说了算。
 *
 * 【发谁】挂载/解除发"正在追踪这只女仆的玩家 + 她自己背上的枪手"
 * （TRACKING_ENTITY_AND_SELF——枪手骑着女仆，一定在追踪她，但为保险并入 self）；
 * 新玩家开始追踪女仆时由 StartTracking 事件补发当前状态（晚进服/传过来的人也能看到绳子）。
 */
public final class GunnerTetherNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "gunner_tether"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private GunnerTetherNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, SyncPacket.class,
                SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
    }

    /** 向"追踪这只女仆的玩家 + 背上的枪手"广播当前挂载（attach=false = 解除，riderId 记 -1） */
    public static void send(EntityMaid maid, boolean attach) {
        if (maid == null) {
            return;
        }
        Entity rider = maid.m_146895_();
        int riderId = attach && rider != null ? rider.m_19879_() : -1;
        try {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> maid),
                    new SyncPacket(maid.m_19879_(), riderId));
        } catch (Throwable ignored) {
        }
    }

    /** 发给指定玩家（StartTracking 补发用；watcher 由调用方保证非空） */
    public static void sendTo(ServerPlayer watcher, int maidId, int riderId) {
        if (watcher == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> watcher), new SyncPacket(maidId, riderId));
        } catch (Throwable ignored) {
        }
    }

    public static class SyncPacket {
        public final int maidId;
        public final int riderId;

        public SyncPacket(int maidId, int riderId) {
            this.maidId = maidId;
            this.riderId = riderId;
        }

        public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.writeInt(pkt.riderId);
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readInt(), buf.readInt());
        }

        public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    com.maidsmart.client.GunnerTetherClient.onSync(pkt.maidId, pkt.riderId));
            ctx.get().setPacketHandled(true);
        }
    }
}
