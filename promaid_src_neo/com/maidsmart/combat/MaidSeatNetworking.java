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
 * v1.3.0(beta) 实测七百二十七·点4：**悬空鞍位（冰火传说的龙）配对的客户端同步**（S2C，单包）。
 *
 * <h2>玩家原话</h2>
 * 「如果龙是静止的时候还好。女仆可以很好待在龙背上，但是一旦龙开始飞行和跟随主人的时候，
 *  女仆的位置就会发生严重的改变和错乱。」
 *
 * <h2>根因（反编译 ClientLevel / ServerEntity 实证）</h2>
 * <b>摆位只在服务端做</b>——{@link RideBindManager} 的每拍摆位（{@code seatOnDragon}）挂在
 * 服务端 tick 上（客户端那一支被 {@code e.level().isClientSide()} 直接挡掉了，因为
 * {@code LINKS} 链路表只活在服务端）。于是客户端的她**不是乘客、又没人摆位**，位置完全由
 * 原版位置包 + 客户端自己的 {@code lerpSteps} 插值决定：
 * <ul>
 *   <li>原版位置包**限流**（{@code ServerEntity.m_8533_}：每 2 tick 一次位置包，角度另算；
 *       而且她每拍被服务端 {@code setPos} 回鞍位 = "位移忽大忽小"，正是限流最容易出偏差的形态）；</li>
 *   <li>而龙**是**乘客体系之外的原版实体，客户端每拍按"龙的插值位置"画它——两者插值节拍不同步，
 *       误差就累积成玩家看到的"位置严重改变和错乱"。</li>
 * </ul>
 * 龙静止时看不出来（两者都不动）；一飞起来，20 Hz 的限流位置包 + 每帧插值的龙 = 明显错位。
 *
 * <h2>本档做什么</h2>
 * 与 {@code GunnerTetherNetworking}（武装拴绳）**同一套骨架**：把"谁是悬空鞍位上的那位"
 * 由服务端 S2C 同步给客户端（{@link RideBindManager#SYNCED_CHAIRS}），客户端在**自己那一拍
 * 实体 tick 之后**用同一个 {@code seatOnDragon} 把她摆到龙身上。于是客户端不再依赖限流的位置包，
 * 而是与服务端**逐字同一条算式**从"龙的当前（插值后）位置"算出鞍位——她与龙永远同帧。
 *
 * <p>【为什么与服务端不冲突】客户端这一支只写**她自己**的位置（她不是乘客），服务端仍在权威侧
 * 摆位；两边算式相同，客户端只是把"限流位置包带来的台阶"抹平。断线/退出世界时清空。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class MaidSeatNetworking {

    private MaidSeatNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(SyncPacket.TYPE,
                StreamCodec.ofMember(SyncPacket::encode, SyncPacket::decode),
                SyncPacket::handle);
        // 【实测七百四十一·点1】指挥棒左击换座（C2S，单包、无回执）。
        r.playToServer(SwapSeatPacket.TYPE,
                StreamCodec.ofMember(SwapSeatPacket::encode, SwapSeatPacket::decode),
                SwapSeatPacket::handle);
    }

    /**
     * 【实测七百四十一·点1】请求"把我换到另一个座位"（主驾 ↔ 副驾来回换）。
     *
     * <p>玩家原话：「如果玩家处于副座，可以通过手持骑乘指挥棒进行左击，从而把自己交换到主座位。
     * 再左击一下再换回去。」——只带"要换到哪辆车"，不信任客户端算出的座位号：服务端自己按
     * SWB 的座位表算（客户端那半只负责发这一下）。
     */
    public static class SwapSeatPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SwapSeatPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "swap_seat"));

        public final int vehicleId;

        public SwapSeatPacket(int vehicleId) {
            this.vehicleId = vehicleId;
        }

        public static void encode(SwapSeatPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.vehicleId);
        }

        public static SwapSeatPacket decode(FriendlyByteBuf buf) {
            return new SwapSeatPacket(buf.readInt());
        }

        public static void handle(SwapSeatPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    com.maidsmart.combat.RideBindManager.handleSwapSeatRequest(sp, pkt.vehicleId);
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端：向服务端请求"把我换到另一个座位"。 */
    public static void requestSwapSeat(int vehicleId) {
        try {
            PacketDistributor.sendToServer(new SwapSeatPacket(vehicleId));
        } catch (Throwable ignored) {
        }
    }

    /** 向"追踪这只女仆的玩家 + 她自己"广播配对（{@code mountId < 0} = 解除）。 */
    public static void send(EntityMaid maid, int mountId) {
        if (maid == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(maid, new SyncPacket(maid.getId(), mountId));
        } catch (Throwable ignored) {
        }
    }

    /** 发给指定玩家（StartTracking 补发用；watcher 由调用方保证非空）。 */
    public static void sendTo(ServerPlayer watcher, int maidId, int mountId) {
        if (watcher == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(watcher, new SyncPacket(maidId, mountId));
        } catch (Throwable ignored) {
        }
    }

    public static class SyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_seat"));

        public final int maidId;
        /** 她挂的那条龙（实体 id）；{@code < 0} = 没挂 */
        public final int mountId;

        public SyncPacket(int maidId, int mountId) {
            this.maidId = maidId;
            this.mountId = mountId;
        }

        public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.writeInt(pkt.mountId);
        }

        public static SyncPacket decode(FriendlyByteBuf buf) {
            return new SyncPacket(buf.readInt(), buf.readInt());
        }

        public static void handle(SyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> RideBindManager.onSeatSync(pkt.maidId, pkt.mountId));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
