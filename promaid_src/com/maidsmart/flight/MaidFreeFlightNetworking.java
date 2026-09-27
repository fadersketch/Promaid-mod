package com.maidsmart.flight;

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
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · per-maid 开关的网络层】——两个包：
 *
 * <ul>
 *   <li>{@link TogglePacket}（C2S）：action 0 = 查询当前状态、1 = 设置；</li>
 *   <li>{@link StatePacket}（S2C）：把该女仆的显式状态回给发起者
 *       （0 = 未设置/跟随全局、1 = 显式开、2 = 显式关），客户端写进缓存供界面显示。</li>
 * </ul>
 *
 * 【为什么必须有 S2C】per-maid 标记存在服务端 {@code persistentData} 里（见 {@link MaidFreeFlightFlags}），
 * 客户端读不到——界面重开时若只靠本地猜，就会把"被关掉的开关"显示成"开"，
 * 玩家一点反而又打开（AiMemoryManager 踩过同一个坑）。
 *
 * 【服务端校验】只允许**主人本人或 OP** 改，且要她在加载范围内、距离不能太远（防远程遥控别人家的女仆）。
 *
 * 【1.20.1 落法：照仓库现有范式写一份】1.21.1 上是 NeoForge 的
 * {@code CustomPacketPayload}+{@code PayloadRegistrar}；1.20.1/Forge 是
 * {@link SimpleChannel}（本树已有 8 处同样写法：GunnerTetherNetworking / EmotionNetworking /
 * ScheduleNetworking / IndexStoneNetworking / BlueprintBookNetworking / BrewManualNetworking /
 * CompressionBoxNetworking / BombMarkNetworking）。这里照抄其中之一，语义一字不改。
 */
public final class MaidFreeFlightNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "free_flight"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private MaidFreeFlightNetworking() {
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
                        player.m_5661_(Component.m_237113_("\u00a7c只有主人或 OP 能改她的创造飞行开关。"), false);
                        return;
                    }
                    if (player.m_20280_(maid) > 64.0) {
                        // 文案与代码对齐：判据 64.0 = 8 格
                        player.m_5661_(Component.m_237113_("\u00a7c离她太远了（8 格以内才能改）。"), false);
                        return;
                    }
                    if (pkt.action == 1) {
                        MaidFreeFlightFlags.setServer(maid, pkt.value);
                        player.m_5661_(Component.m_237113_("\u00a7e" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " \u00a7r的仿创造飞行：" + (pkt.value ? "\u00a7a开" : "\u00a77关")), false);
                    }
                    Boolean explicit = MaidFreeFlightFlags.explicitServer(maid);
                    byte st = explicit == null ? 0 : (explicit ? (byte) 1 : (byte) 2);
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new StatePacket(pkt.maidUuid, st));
                } catch (Throwable ignored) {
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    /* ---------------- S2C ---------------- */

    public static class StatePacket {
        public final String maidUuid;
        /** 0 = 未设置（跟随全局）、1 = 显式开、2 = 显式关 */
        public final byte state;

        public StatePacket(String maidUuid, byte state) {
            this.maidUuid = maidUuid;
            this.state = state;
        }

        public static void encode(StatePacket pkt, FriendlyByteBuf buf) {
            buf.m_130070_(pkt.maidUuid == null ? "" : pkt.maidUuid);
            buf.writeByte(pkt.state);
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            return new StatePacket(buf.m_130277_(), buf.readByte());
        }

        public static void handle(StatePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> MaidFreeFlightFlags.pushClientState(pkt.maidUuid,
                    pkt.state == 0 ? null : pkt.state == 1));
            ctx.setPacketHandled(true);
        }
    }
}
