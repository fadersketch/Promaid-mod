package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 实测七百四十五·点1【飞行聚晶 · 1.20.1 非 OP 的开关网络层】——两个包：
 *
 * <ul>
 *   <li>{@link TogglePacket}（C2S）：{@code action} 0 = 查询当前状态、1 = 设置；</li>
 *   <li>{@link StatePacket}（S2C）：把该女仆的自动档状态回给发起者，客户端写进缓存供界面显示。</li>
 * </ul>
 *
 * 【为什么需要这一层（玩家原话：「聚晶必须要使用指令这些 OP 权限才可以使用吗？常规生存不能使用？」）】
 * 七百一十八 那一版把 Goety 位移聚晶**只**挂在 {@code /maid_smart goety_*} 命令上（OP 专属），
 * 于是普通生存玩家即使身上真有飞行聚晶也**开不了这个功能**。而"仿创造飞行"（同类飞行功能）
 * 一直有主人可用的路径（快捷键 + 女仆配置界面一行，见 {@code MaidFreeFlightNetworking}）。
 * 这里照搬那套：**主人本人或 OP** 即可开自己的女仆，且要求她在加载范围内、距离 8 格以内。
 *
 * 【服务端校验】与 {@code MaidFreeFlightNetworking} 完全同口径：主人或 OP + 8 格内。
 * 另外这一档还要额外满足 {@link MaidGoetyCompat#hasFocus}（她身上**真的**有飞行聚晶）——
 * 保真门禁：没有道具就不给开，免得玩家以为"开了却不飞"是坏了。
 *
 * 【1.20.1 落法】1.21.1 上是 NeoForge 的 {@code CustomPacketPayload}+{@code PayloadRegistrar}；
 * 1.20.1/Forge 是 {@link SimpleChannel}（本树已有 8 处同样写法）。这里照抄
 * {@code MaidFreeFlightNetworking} 那份，语义一字不改，只把方法名换成 SRG
 * （{@code m_130070_} = writeUtf、{@code m_130277_} = readUtf、{@code m_8791_} = level.getEntity、
 * {@code m_21830_} = isOwnedBy、{@code m_20310_} = hasPermissions、{@code m_5661_} = sendSystemMessage、
 * {@code m_20280_} = distanceToSqr、{@code m_9236_} = level）。
 */
public final class MaidGoetyNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "goety"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private MaidGoetyNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, TogglePacket.class,
                TogglePacket::encode, TogglePacket::decode, TogglePacket::handle);
        CHANNEL.registerMessage(1, StatePacket.class,
                StatePacket::encode, StatePacket::decode, StatePacket::handle);
    }

    /* ---------------- C2S ---------------- */

    public static class TogglePacket {
        public final String maidUuid;
        /** 0 = 查询、1 = 设置 */
        public final byte action;
        public final boolean value;

        public TogglePacket(String maidUuid, byte action, boolean value) {
            this.maidUuid = maidUuid;
            this.action = action;
            this.value = value;
        }

        public static void encode(TogglePacket pkt, FriendlyByteBuf buf) {
            buf.m_130070_(pkt.maidUuid == null ? "" : pkt.maidUuid);
            buf.writeByte(pkt.action);
            buf.writeBoolean(pkt.value);
        }

        public static TogglePacket decode(FriendlyByteBuf buf) {
            return new TogglePacket(buf.m_130277_(), buf.readByte(), buf.readBoolean());
        }

        public static void handle(TogglePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                try {
                    ServerPlayer player = ctx.getSender();
                    if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                        return;
                    }
                    EntityMaid maid = null;
                    try {
                        maid = (EntityMaid) level.m_8791_(UUID.fromString(pkt.maidUuid));
                    } catch (Throwable ignored) {
                    }
                    if (maid == null) {
                        player.m_5661_(Component.m_237113_("\u00a7c她不在加载范围内（走近一点再试）。"), false);
                        return;
                    }
                    boolean isOwner = maid.m_21830_(player);
                    if (!isOwner && !player.m_20310_(2)) {
                        player.m_5661_(Component.m_237113_("\u00a7c只有主人或 OP 能改她的飞行聚晶自动档。"), false);
                        return;
                    }
                    if (player.m_20280_(maid) > 64.0) {
                        player.m_5661_(Component.m_237113_("\u00a7c离她太远了（8 格以内才能改）。"), false);
                        return;
                    }
                    if (pkt.action == 1) {
                        // 保真门禁：她身上必须真的带着飞行聚晶，否则"开了也不飞"，先如实告诉她。
                        if (pkt.value && !MaidGoetyCompat.hasFocus(maid, MaidGoetyCompat.FOCUS_FLYING)) {
                            player.m_5661_(Component.m_237113_(
                                    "\u00a7c她身上没有飞行聚晶（goety:flying_focus）——"
                                            + "把聚晶放进法杖插到她身上/背包/饰品栏，或放进聚晶包。"), false);
                            return;
                        }
                        MaidGoetyAuto.setAuto(maid, pkt.value);
                        player.m_5661_(Component.m_237113_("\u00a7e" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " \u00a7r的飞行聚晶自动档：" + (pkt.value ? "\u00a7a开" : "\u00a77关")), false);
                    }
                    boolean on = MaidGoetyAuto.isAuto(maid);
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new StatePacket(pkt.maidUuid, on));
                } catch (Throwable ignored) {
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    /* ---------------- S2C ---------------- */

    public static class StatePacket {
        public final String maidUuid;
        public final boolean on;

        public StatePacket(String maidUuid, boolean on) {
            this.maidUuid = maidUuid;
            this.on = on;
        }

        public static void encode(StatePacket pkt, FriendlyByteBuf buf) {
            buf.m_130070_(pkt.maidUuid == null ? "" : pkt.maidUuid);
            buf.writeBoolean(pkt.on);
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            return new StatePacket(buf.m_130277_(), buf.readBoolean());
        }

        public static void handle(StatePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> MaidGoetyAuto.pushClientState(pkt.maidUuid, pkt.on));
            ctx.setPacketHandled(true);
        }
    }
}
