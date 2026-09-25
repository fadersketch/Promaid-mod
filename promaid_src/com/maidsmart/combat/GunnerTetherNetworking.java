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
 * v1.3.7 实测六百六十七：武装拴绳网络层（S2C，单包，骨架同 {@link BombMarkNetworking}）。
 *
 * 【为什么必须走包】挂载关系里"谁是她的二号位枪手"是服务端知识（客户端虽然能看到
 * 原版同步的乘客关系，但分不清这个乘客是拴绳挂的、TLM 的可骑还是别的模组干的）——
 * {@link GunnerTetherManager#SYNCED_PAIRS} 供 mixin 定位（挂下方）与绳子渲染，
 * 只能由服务端说了算。
 *
 * 【实测六百七十四：包里多了 state（相位）】玩家原话："此机制没有引用到空袭状态，而且现在玩家
 * 会直接坐到空袭女仆的头上。明明说好的是挂在身下的。" 根因是**牵绳 → 起飞挂载**那一跳没重发包
 * （发的是 attach 时刻的，那时玩家还不是乘客 → riderId 记 -1 → 客户端把这一对清掉），
 * 于是客户端不认那个枪手、悬挂定位 mixin 不生效。现在：
 * <ul>
 *   <li>{@code state} 0 = 没挂 / 1 = 牵绳档（没骑她）/ 2 = 悬挂档（吊在她下面）；</li>
 *   <li>{@code riderId} 由**链路**给出（{@link GunnerTetherManager#phaseOf}），不再问
 *       {@code getFirstPassenger}——牵绳档他不是乘客；</li>
 *   <li>服务端每次相位变化都重发一遍（{@code GunnerTetherManager.sync}）。</li>
 * </ul>
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

    /** 向"追踪这只女仆的玩家 + 背上的枪手"广播当前相位（state=0 = 解除） */
    public static void send(EntityMaid maid, int state, int riderId) {
        if (maid == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> maid),
                    new SyncPacket(maid.m_19879_(), riderId, state));
        } catch (Throwable ignored) {
        }
    }

    /** 发给指定玩家（StartTracking 补发用；watcher 由调用方保证非空） */
    public static void sendTo(ServerPlayer watcher, int maidId, int riderId, int state) {
        if (watcher == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> watcher), new SyncPacket(maidId, riderId, state));
        } catch (Throwable ignored) {
        }
    }

    public static class SyncPacket {
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

        public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    com.maidsmart.client.GunnerTetherClient.onSync(pkt.maidId, pkt.riderId, pkt.state));
            ctx.get().setPacketHandled(true);
        }
    }
}
