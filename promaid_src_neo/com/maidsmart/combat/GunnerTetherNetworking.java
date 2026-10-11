package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * 武装拴绳网络层（S2C，单包，骨架同 {@link BombMarkNetworking}）。
 *
 * 【为什么必须走包】挂载关系里"谁是她的二号位枪手"是服务端知识（客户端能看到原版同步的乘客关系，但
 * 分不清这个乘客是拴绳挂的、TLM 的可骑还是别的模组干的）；{@link GunnerTetherManager#SYNCED_PAIRS}
 * 供 mixin 定位（挂下方）与绳子渲染，只能由服务端说了算。
 * 【包里带 state（相位）】{@code state} 0 = 没挂 / 1 = 牵绳档（没骑她）/ 2 = 悬挂档（吊在她下面）；
 * {@code riderId} 由**链路**给出（{@link GunnerTetherManager#phaseOf}），不再问 {@code getFirstPassenger}
 * ——牵绳档他不是乘客（旧版发的是 attach 时刻的，那时玩家还不是乘客 → riderId=-1 → 客户端把这对清掉、
 * 悬挂定位不生效）；服务端每次相位变化都重发（{@code GunnerTetherManager.sync}）。
 * 【发谁】挂载/解除发"正在追踪这只女仆的玩家 + 她自己背上的枪手"；新玩家开始追踪时由 StartTracking
 * 事件补发当前状态（晚进服/传过来的人也能看到绳子）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class GunnerTetherNetworking {

    private GunnerTetherNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(SyncPacket.TYPE,
                StreamCodec.ofMember(SyncPacket::encode, SyncPacket::decode),
                SyncPacket::handle);
    }

    /** 向"追踪这只女仆的玩家 + 背上的枪手"广播当前相位（state=0 = 解除） */
    public static void send(EntityMaid maid, int state, int riderId) {
        if (maid == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(maid,
                    new SyncPacket(maid.getId(), riderId, state));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GunnerTetherNetworking", ignored);
        }
    }

    /** 发给指定玩家（StartTracking 补发用；watcher 由调用方保证非空） */
    public static void sendTo(ServerPlayer watcher, int maidId, int riderId, int state) {
        if (watcher == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(watcher, new SyncPacket(maidId, riderId, state));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GunnerTetherNetworking", ignored);
        }
    }

    public static class SyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "gunner_tether"));

        public final int maidId;
        public final int riderId;
        /** 相位：0 没挂 / 1 牵绳档 / 2 悬挂档（见 {@link GunnerTetherManager#ST_LEASH}） */
        public final int state;

        public SyncPacket(int maidId, int riderId, int state) {
            this.maidId = maidId;
            this.riderId = riderId;
            this.state = state;
        }

        public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.writeInt(pkt.riderId);
            buf.writeInt(pkt.state);
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(SyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.client.GunnerTetherClient.onSync(pkt.maidId, pkt.riderId, pkt.state));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
