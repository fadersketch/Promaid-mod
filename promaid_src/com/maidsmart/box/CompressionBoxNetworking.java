package com.maidsmart.box;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.function.Supplier;

/**
 * 压缩盒网络层（v1.2.2 实测六百一十六）——打开界面与每一次取放。
 *
 * 「打开的是哪一个盒子」用**手**来指认（主手 / 副手），不传坐标：盒子是纯道具，
 * 服务端每次操作都重新确认那只手里还是压缩盒（见 {@link CompressionBoxService}）。
 *
 * 包清单：
 * <ul>
 *   <li>0 {@link BoxStatePacket}（S2C）：「开屏 / 刷新」——手序号 + 5 格内容 + 是否开屏。
 *       内容里的大堆（114514）走 {@link #writeItems} 自己编：**物品本体写成 1 个**
 *       （原版编解码），真实数量另写一个 int——原版 {@code writeItem} 把数量写成
 *       1 字节，直接发 114514 会变成 82（javap 实证）；</li>
 *   <li>1 {@link BoxActionPacket}（C2S）：一次点击（手 + 动作 + 格子号）→ 交给
 *       {@link CompressionBoxService} 搬东西，然后 S2C 回一份新内容。</li>
 * </ul>
 * 客户端侧的类（{@code CompressionBoxScreen}）只在 S2C 的 enqueueWork 里被加载，
 * 专用服务器不会碰到它（与排班表/药剂手册同款约定）。
 */
public final class CompressionBoxNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "compression_box"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private CompressionBoxNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, BoxStatePacket.class,
                BoxStatePacket::encode, BoxStatePacket::decode, BoxStatePacket::handle);
        CHANNEL.registerMessage(1, BoxActionPacket.class,
                BoxActionPacket::encode, BoxActionPacket::decode, BoxActionPacket::handle);
    }

    /** 服务端：让某个玩家打开手上这个盒子的界面（右键道具时调用） */
    public static void openFor(ServerPlayer player, InteractionHand hand, List<ItemStack> items) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BoxStatePacket(hand.ordinal(), items, true));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：把新内容推给已经开着这个界面的玩家 */
    public static void syncTo(ServerPlayer player, int handOrdinal, List<ItemStack> items) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BoxStatePacket(handOrdinal, items, false));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 大堆物品的编解码 ==================== */

    /** 物品本体 1 个（原版编解码）+ 真实数量 int——大堆不许走原版的字节数量 */
    static void writeItems(FriendlyByteBuf buf, List<ItemStack> items) {
        buf.writeInt(items.size());
        for (ItemStack s : items) {
            if (s.m_41619_()) {
                buf.writeByte(0);
                continue;
            }
            buf.writeByte(1);
            buf.m_130055_(s.m_255036_(1));
            buf.writeInt(s.m_41613_());
        }
    }

    static List<ItemStack> readItems(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<ItemStack> out = CompressionBoxData.empty();
        for (int i = 0; i < n && i < CompressionBoxData.SLOTS; i++) {
            if (buf.readByte() == 0) {
                continue;
            }
            ItemStack s = buf.m_130267_();
            int count = buf.readInt();
            if (s.m_41619_()) {
                continue;
            }
            s.m_41769_(Math.max(1, Math.min(count, CompressionBoxData.maxStack())));
            out.set(i, s);
        }
        return out;
    }

    /* ==================== 0：开屏 / 刷新 ==================== */

    public static class BoxStatePacket {
        public final int hand;
        public final List<ItemStack> items;
        public final boolean open;

        public BoxStatePacket(int hand, List<ItemStack> items, boolean open) {
            List<ItemStack> copy = CompressionBoxData.empty();
            for (int i = 0; i < copy.size() && i < items.size(); i++) {
                copy.set(i, items.get(i).m_41777_());
            }
            this.hand = hand;
            this.items = copy;
            this.open = open;
        }

        public static void encode(BoxStatePacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.open ? 1 : 0);
            writeItems(buf, pkt.items);
        }

        public static BoxStatePacket decode(FriendlyByteBuf buf) {
            int hand = buf.readByte();
            boolean open = buf.readByte() != 0;
            return new BoxStatePacket(hand, readItems(buf), open);
        }

        public static void handle(BoxStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return; // 方向校验：恶意客户端把 S2C 包发往服务端会加载客户端类
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.client.CompressionBoxScreen.accept(pkt.hand, pkt.items, pkt.open));
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 1：一次点击 ==================== */

    public static class BoxActionPacket {
        public final int hand;
        public final int action;
        public final int index;

        public BoxActionPacket(int hand, int action, int index) {
            this.hand = hand;
            this.action = action;
            this.index = index;
        }

        /** 客户端发一次点击（由界面调用） */
        public static void send(int hand, int action, int index) {
            try {
                CHANNEL.sendToServer(new BoxActionPacket(hand, action, index));
            } catch (Throwable ignored) {
            }
        }

        public static void encode(BoxActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.action);
            buf.writeInt(pkt.index);
        }

        public static BoxActionPacket decode(FriendlyByteBuf buf) {
            int hand = buf.readByte();
            int action = buf.readByte();
            int index = buf.readInt();
            return new BoxActionPacket(hand, action, index);
        }

        public static void handle(BoxActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            if (c.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                c.setPacketHandled(true);
                return;
            }
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) {
                    return;
                }
                if (CompressionBoxService.handle(player, pkt.hand, pkt.action, pkt.index)) {
                    ItemStack box = player.m_21120_(CompressionBoxService.handOf(pkt.hand));
                    if (CompressionBoxData.isBox(box)) {
                        syncTo(player, pkt.hand, CompressionBoxData.read(box));
                    }
                }
            });
            c.setPacketHandled(true);
        }
    }
}
