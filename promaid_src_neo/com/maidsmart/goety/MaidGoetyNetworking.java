package com.maidsmart.goety;

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
 * 实测七百四十五·点1【飞行聚晶 · 非 OP 的开关网络层】——两个包：
 *
 * <ul>
 *   <li>{@link TogglePacket}（C2S）：{@code action} 0 = 查询当前状态、1 = 设置；</li>
 *   <li>{@link StatePacket}（S2C）：把该女仆的自动档状态回给发起者，客户端写进缓存供界面显示。</li>
 * </ul>
 *
 * 【为什么需要这一层（玩家原话：「聚晶必须要使用指令这些 OP 权限才可以使用吗？常规生存不能使用？」）】
 * 七百一十八 那一版把 Goety 位移聚晶**只**挂在了 {@code /maid_smart goety_*} 命令上，而那批命令
 * 全部 {@code requires(hasPermission(2))}（OP 专属）。于是一个普通生存玩家即使身上真有飞行聚晶、
 * 也把女仆驯服了，**开不了这个功能**——只有服主能开。而"仿创造飞行"（同类飞行功能）一直有
 * 主人可用的路径（快捷键 + 女仆配置界面一行，见 {@code MaidFreeFlightNetworking}）。
 * 这里照搬那套：**主人本人或 OP** 即可开自己的女仆，且要求她在加载范围内、距离 8 格以内。
 *
 * 【服务端校验】与 {@code MaidFreeFlightNetworking} 完全同口径：主人或 OP + 8 格内。
 * 另外这一档还要额外满足 {@link MaidGoetyCompat#hasFocus}（她身上**真的**有飞行聚晶）——
 * 保真门禁：没有道具就不给开，免得玩家以为"开了却不飞"是坏了。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class MaidGoetyNetworking {

    private MaidGoetyNetworking() {
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
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "goety_toggle"));

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
                    player.sendSystemMessage(Component.literal("\u00a7c只有主人或 OP 能改她的飞行聚晶自动档。"));
                    return;
                }
                if (player.distanceToSqr(maid) > 64.0) {
                    player.sendSystemMessage(Component.literal("\u00a7c离她太远了（8 格以内才能改）。"));
                    return;
                }
                if (pkt.action == 1) {
                    // 保真门禁：她身上必须真的带着飞行聚晶，否则"开了也不飞"，先如实告诉她。
                    if (pkt.value && !MaidGoetyCompat.hasFocus(maid, MaidGoetyCompat.FOCUS_FLYING)) {
                        player.sendSystemMessage(Component.literal(
                                "\u00a7c她身上没有飞行聚晶（goety:flying_focus）——"
                                        + "把聚晶放进法杖插到她身上/背包/饰品栏，或放进聚晶包。"));
                        return;
                    }
                    MaidGoetyAuto.setAuto(maid, pkt.value);
                    player.sendSystemMessage(Component.literal("\u00a7e" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                            + " \u00a7r的飞行聚晶自动档：" + (pkt.value ? "\u00a7a开" : "\u00a77关")));
                }
                boolean on = MaidGoetyAuto.isAuto(maid);
                PacketDistributor.sendToPlayer(player, new StatePacket(pkt.maidUuid, on));
            });
        }
    }

    /* ---------------- S2C ---------------- */

    public static class StatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StatePacket> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("maid_smart", "goety_state"));

        public final String maidUuid;
        public final boolean on;

        public StatePacket(String maidUuid, boolean on) {
            this.maidUuid = maidUuid;
            this.on = on;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(StatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeBoolean(pkt.on);
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            return new StatePacket(buf.readUtf(), buf.readBoolean());
        }

        public static void handle(StatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> MaidGoetyAuto.pushClientState(pkt.maidUuid, pkt.on));
        }
    }
}
