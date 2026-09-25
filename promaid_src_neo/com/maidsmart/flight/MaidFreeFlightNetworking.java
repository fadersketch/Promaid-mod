package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * 实测六百七十八【仿创造飞行 · per-maid 开关的网络层】——两个包：
 *
 * <ul>
 *   <li>{@link TogglePacket}（C2S）：action 0 = 查询当前状态、1 = 设置；</li>
 *   <li>{@link StatePacket}（S2C）：把该女仆的显式状态回给发起者
 *       （0 = 未设置/跟随全局、1 = 显式开、2 = 显式关），客户端写进缓存供界面显示。</li>
 * </ul>
 *
 * 【为什么必须有 S2C】per-maid 标记存在服务端 `persistentData` 里（见 {@link MaidFreeFlightFlags}），
 * 客户端读不到——界面重开时若只靠本地猜，就会把"被关掉的开关"显示成"开"，
 * 玩家一点反而又打开（AiMemoryManager 踩过同一个坑）。
 *
 * 【服务端校验】只允许**主人本人或 OP** 改，且要她在加载范围内、距离不能太远（防远程遥控别人家的女仆）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class MaidFreeFlightNetworking {

    private MaidFreeFlightNetworking() {
    }

    @SubscribeEvent
    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToServer(TogglePacket.TYPE,
                StreamCodec.ofMember(TogglePacket::encode, TogglePacket::decode), TogglePacket::handle);
        r.playToClient(StatePacket.TYPE,
                StreamCodec.ofMember(StatePacket::encode, StatePacket::decode), StatePacket::handle);
    }

    /* ---------------- C2S ---------------- */

    public static class TogglePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TogglePacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "free_flight_toggle"));

        public final String maidUuid;
        /** 0 = 查询、1 = 设置 */
        public final byte action;
        public final boolean value;

        public TogglePacket(String maidUuid, byte action, boolean value) {
            this.maidUuid = maidUuid;
            this.action = action;
            this.value = value;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(TogglePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeByte(pkt.action);
            buf.writeBoolean(pkt.value);
        }

        public static TogglePacket decode(FriendlyByteBuf buf) {
            return new TogglePacket(buf.readUtf(), buf.readByte(), buf.readBoolean());
        }

        public static void handle(TogglePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)
                        || !(player.level() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(UUID.fromString(pkt.maidUuid));
                } catch (Throwable ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(Component.literal("\u00a7c她不在加载范围内（走近一点再试）。"));
                    return;
                }
                boolean isOwner = maid.isOwnedBy(player);
                if (!isOwner && !player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("\u00a7c只有主人或 OP 能改她的创造飞行开关。"));
                    return;
                }
                if (player.distanceToSqr(maid) > 64.0) {
                    player.sendSystemMessage(Component.literal("\u00a7c离她太远了（4 格以内才能改）。"));
                    return;
                }
                if (pkt.action == 1) {
                    MaidFreeFlightFlags.setServer(maid, pkt.value);
                    player.sendSystemMessage(Component.literal("\u00a7e" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " \u00a7r的仿创造飞行：" + (pkt.value ? "\u00a7a开" : "\u00a77关")));
                }
                Boolean explicit = MaidFreeFlightFlags.explicitServer(maid);
                byte st = explicit == null ? 0 : (explicit ? (byte) 1 : (byte) 2);
                PacketDistributor.sendToPlayer(player, new StatePacket(pkt.maidUuid, st));
            });
        }
    }

    /* ---------------- S2C ---------------- */

    public static class StatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StatePacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "free_flight_state"));

        public final String maidUuid;
        /** 0 = 未设置（跟随全局）、1 = 显式开、2 = 显式关 */
        public final byte state;

        public StatePacket(String maidUuid, byte state) {
            this.maidUuid = maidUuid;
            this.state = state;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(StatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeByte(pkt.state);
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            return new StatePacket(buf.readUtf(), buf.readByte());
        }

        public static void handle(StatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> MaidFreeFlightFlags.pushClientState(pkt.maidUuid,
                    pkt.state == 0 ? null : pkt.state == 1));
        }
    }
}
