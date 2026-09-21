package com.maidsmart.box;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * 压缩盒网络层（v1.2.2 实测六百一十六）——打开界面与每一次取放。
 *
 * 「打开的是哪一个盒子」用**手**来指认（主手 / 副手），不传坐标：盒子是纯道具，
 * 服务端每次操作都重新确认那只手里还是压缩盒（见 {@link CompressionBoxService}）。
 *
 * 包清单：
 * <ul>
 *   <li>0 {@link BoxStatePacket}（S2C）：「开屏 / 刷新」——手序号 + 5 格内容 + 是否开屏。
 *       内容里的大堆（114514）走 {@link #writeItems} 自己编：**物品本体只写 1 个**
 *       （原版编解码），真实数量另写一个 int——两棵树用同一套口径，原版那边
 *       数量字段的取值范围就碰不到它；</li>
 *   <li>1 {@link BoxActionPacket}（C2S）：一次点击（手 + 动作 + 格子号）→ 交给
 *       {@link CompressionBoxService} 搬东西，然后 S2C 回一份新内容。</li>
 * </ul>
 * 客户端侧的类（{@code CompressionBoxScreen}）只在 S2C 的 enqueueWork 里被加载，
 * 专用服务器不会碰到它（与排班表/药剂手册同款约定）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class CompressionBoxNetworking {

    private CompressionBoxNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(BoxStatePacket.TYPE,
                StreamCodec.ofMember(BoxStatePacket::encode, BoxStatePacket::decode),
                BoxStatePacket::handle);
        r.playToServer(BoxActionPacket.TYPE,
                StreamCodec.ofMember(BoxActionPacket::encode, BoxActionPacket::decode),
                BoxActionPacket::handle);
    }

    /** 服务端：让某个玩家打开手上这个盒子的界面（右键道具时调用） */
    public static void openFor(ServerPlayer player, InteractionHand hand, List<ItemStack> items) {
        try {
            PacketDistributor.sendToPlayer(player, new BoxStatePacket(hand.ordinal(), items, true));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：把新内容推给已经开着这个界面的玩家 */
    public static void syncTo(ServerPlayer player, int handOrdinal, List<ItemStack> items) {
        try {
            PacketDistributor.sendToPlayer(player, new BoxStatePacket(handOrdinal, items, false));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 大堆物品的编解码 ==================== */

    /** 物品本体 1 个（原版编解码）+ 真实数量 int——大堆不许走原版的数量口径 */
    static void writeItems(RegistryFriendlyByteBuf buf, List<ItemStack> items) {
        buf.writeInt(items.size());
        for (ItemStack s : items) {
            if (s.isEmpty()) {
                buf.writeByte(0);
                continue;
            }
            buf.writeByte(1);
            ItemStack.STREAM_CODEC.encode(buf, s.copyWithCount(1));
            buf.writeInt(s.getCount());
        }
    }

    static List<ItemStack> readItems(RegistryFriendlyByteBuf buf) {
        int n = buf.readInt();
        List<ItemStack> out = CompressionBoxData.empty();
        for (int i = 0; i < n && i < CompressionBoxData.SLOTS; i++) {
            if (buf.readByte() == 0) {
                continue;
            }
            ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
            int count = buf.readInt();
            if (s.isEmpty()) {
                continue;
            }
            s.setCount(Math.max(1, Math.min(count, CompressionBoxData.maxStack())));
            out.set(i, s);
        }
        return out;
    }

    /* ==================== 0：开屏 / 刷新 ==================== */

    public static class BoxStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxStatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "maid_smart", "compression_box_state"));

        public final int hand;
        public final List<ItemStack> items;
        public final boolean open;

        public BoxStatePacket(int hand, List<ItemStack> items, boolean open) {
            List<ItemStack> copy = CompressionBoxData.empty();
            for (int i = 0; i < copy.size() && i < items.size(); i++) {
                copy.set(i, items.get(i).copy());
            }
            this.hand = hand;
            this.items = copy;
            this.open = open;
        }

        public static void encode(BoxStatePacket pkt, RegistryFriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.open ? 1 : 0);
            writeItems(buf, pkt.items);
        }

        public static BoxStatePacket decode(RegistryFriendlyByteBuf buf) {
            int hand = buf.readByte();
            boolean open = buf.readByte() != 0;
            return new BoxStatePacket(hand, readItems(buf), open);
        }

        public static void handle(BoxStatePacket pkt, IPayloadContext ctx) {
            // S2C 方向校验（同排班表/药剂手册）——恶意客户端把 S2C 包发往服务端会
            // 加载客户端 Screen 类 → 专用服 NoClassDefFoundError
            if (ctx.flow() != PacketFlow.CLIENTBOUND) {
                return;
            }
            ctx.enqueueWork(() ->
                    com.maidsmart.client.CompressionBoxScreen.accept(pkt.hand, pkt.items, pkt.open));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /* ==================== 1：一次点击 ==================== */

    public static class BoxActionPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxActionPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "maid_smart", "compression_box_action"));

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
                PacketDistributor.sendToServer(new BoxActionPacket(hand, action, index));
            } catch (Throwable ignored) {
            }
        }

        public static void encode(BoxActionPacket pkt, RegistryFriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.action);
            buf.writeInt(pkt.index);
        }

        public static BoxActionPacket decode(RegistryFriendlyByteBuf buf) {
            int hand = buf.readByte();
            int action = buf.readByte();
            int index = buf.readInt();
            return new BoxActionPacket(hand, action, index);
        }

        public static void handle(BoxActionPacket pkt, IPayloadContext ctx) {
            if (ctx.flow() != PacketFlow.SERVERBOUND) {
                return;
            }
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                if (CompressionBoxService.handle(player, pkt.hand, pkt.action, pkt.index)) {
                    ItemStack box = player.getItemInHand(CompressionBoxService.handOf(pkt.hand));
                    if (CompressionBoxData.isBox(box)) {
                        syncTo(player, pkt.hand, CompressionBoxData.read(box));
                    }
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
