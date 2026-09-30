package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * v1.3.0(beta) 实测七百二十七·点4：**悬空鞍位（冰火传说的龙）配对的客户端同步**（S2C，单包）。
 *
 * <h2>玩家原话</h2>
 * 「如果龙是静止的时候还好。女仆可以很好待在龙背上，但是一旦龙开始飞行和跟随主人的时候，
 *  女仆的位置就会发生严重的改变和错乱。」
 *
 * <h2>根因（反编译 ClientLevel / ServerEntity 实证）</h2>
 * <b>摆位只在服务端做</b>——{@link RideBindManager} 的每拍摆位（{@code seatOnDragon}）挂在
 * 服务端 tick 上（客户端那一支被 {@code level().isClientSide()} 直接挡掉了，因为
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
 * 与 {@link GunnerTetherNetworking}（武装拴绳）**同一套骨架**：把"谁是悬空鞍位上的那位"
 * 由服务端 S2C 同步给客户端（{@link RideBindManager#SYNCED_CHAIRS}），客户端在**自己那一拍
 * 实体 tick 之后**用同一个 {@code seatOnDragon} 把她摆到龙身上。于是客户端不再依赖限流的位置包，
 * 而是与服务端**逐字同一条算式**从"龙的当前（插值后）位置"算出鞍位——她与龙永远同帧。
 *
 * <p>【1.20.1 侧差异】本树没有 1.21 的 {@code CustomPacketPayload} / {@code StreamCodec} 那套
 * 注册 API，改用 Forge 的 {@code SimpleChannel.registerMessage}（与 {@link GunnerTetherNetworking}
 * 逐字同款）；包体字段与 handle 落点完全一致。注册由 {@code ProMaidMod} 构造期统一调用
 * （本树不需要 1.21 的"MOD 总线上注册"那一步）。
 */
public final class MaidSeatNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "maid_seat"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private MaidSeatNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, SyncPacket.class,
                SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
    }

    /** 向"追踪这只女仆的玩家 + 她自己"广播配对（{@code mountId < 0} = 解除）。 */
    public static void send(EntityMaid maid, int mountId) {
        if (maid == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> maid),
                    new SyncPacket(maid.m_19879_(), mountId));
        } catch (Throwable ignored) {
        }
    }

    /** 发给指定玩家（StartTracking 补发用；watcher 由调用方保证非空）。 */
    public static void sendTo(ServerPlayer watcher, int maidId, int mountId) {
        if (watcher == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> watcher), new SyncPacket(maidId, mountId));
        } catch (Throwable ignored) {
        }
    }

    public static class SyncPacket {
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

        public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> RideBindManager.onSeatSync(pkt.maidId, pkt.mountId));
            ctx.get().setPacketHandled(true);
        }
    }
}
