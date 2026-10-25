package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * 烟花尾迹的 S2C 开关：服务端点火时告诉"正在追踪她的客户端"这一枚要画多久。
 *
 * <p>【为什么必须换到客户端发粒子】服务端 {@code sendParticles} 发的是**服务端那一刻的坐标**，
 * 而客户端画她用的是**插值后的渲染坐标**（{@code xo → x}，再叠加位置包限流与网络延迟）。
 * 她滑翔时一拍能走一格多，两条坐标之间就差了整整一拍——玩家看到的就是"粒子先飞出去、
 * 人还在后面"。原版挂载烟花没有这个问题：它的粒子是 {@code FireworkRocketEntity.tick}
 * 的**客户端**分支每拍在**火箭自己的插值坐标**上发的（见 {@code FireworkRocketEntity.tick}：
 * {@code level.addParticle(FIREWORK, getX(), getY(), getZ(), gauss×0.05, -dy×0.5, gauss×0.05)}）。
 *
 * <p>所以推力仍在服务端（权威），尾迹这一层挪到客户端：本包只带"哪只女仆、多少拍"，
 * 客户端在每帧渲染时按 {@link #FireworkTrailPacket} 记下的窗口、在她的**渲染坐标**上补一拍一粒。
 * 服务端不再发任何粒子。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class FireworkTrailNetworking {

    private FireworkTrailNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(TrailPacket.TYPE,
                StreamCodec.ofMember(TrailPacket::encode, TrailPacket::decode),
                TrailPacket::handle);
    }

    /** 开一段尾迹（{@code ticks <= 0} = 立刻收掉）。 */
    public static void start(EntityMaid maid, int ticks) {
        if (maid == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(maid,
                    new TrailPacket(maid.getId(), ticks));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FireworkTrailNetworking", ignored);
        }
    }

    public static class TrailPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TrailPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "firework_trail"));

        public final int maidId;
        /** 尾迹持续拍数；{@code <= 0} = 停止 */
        public final int ticks;

        public TrailPacket(int maidId, int ticks) {
            this.maidId = maidId;
            this.ticks = ticks;
        }

        public static void encode(TrailPacket pkt, FriendlyByteBuf buf) {
            buf.writeVarInt(pkt.maidId);
            buf.writeVarInt(pkt.ticks);
        }

        public static TrailPacket decode(FriendlyByteBuf buf) {
            return new TrailPacket(buf.readVarInt(), buf.readVarInt());
        }

        public static void handle(TrailPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() ->
                    com.maidsmart.client.FireworkTrailClient.start(pkt.maidId, pkt.ticks));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
