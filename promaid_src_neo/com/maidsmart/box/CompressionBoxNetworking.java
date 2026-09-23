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
 * 压缩盒网络层（v1.2.2 实测六百一十六；六百一十八加「鼠标上那一叠」）。
 *
 * 「打开的是哪一个盒子」用**手**来指认（主手 / 副手），不传坐标：盒子是纯道具，
 * 服务端每次操作都重新确认那只手里还是压缩盒（见 {@link CompressionBoxService}）。
 *
 * 包清单：
 * <ul>
 *   <li>0 {@link BoxStatePacket}（S2C）：「开屏 / 刷新」——手序号 + 5 格内容 +
 *       **鼠标上那一叠** + 是否开屏。内容里的大堆（114514）走 {@link #writeItems} 自己编：
 *       **物品本体只写 1 个**（原版编解码），真实数量另写一个 int——两棵树用同一套口径，
 *       原版那边数量字段的取值范围就碰不到它；</li>
 *   <li>1 {@link BoxActionPacket}（C2S）：一次点击（手 + 动作 + 格子号 + 左右键 + 是否 Shift）
 *       → 交给 {@link CompressionBoxService} 搬东西，然后 S2C 回一份新内容。</li>
 * </ul>
 *
 * 【为什么 600 十八要动协议号】{@link BoxActionPacket} 多了「左右键 / Shift」两个字节，
 * {@link BoxStatePacket} 多了「手上那一叠」一段：老客户端连上新服务端会把后面那段
 * 当成别的包读，直接错位。协议号从 "1" 提到 "2"，版本对不上的客户端连不进来
 * ——这正是 NeoForge 这套 {@code PayloadRegistrar} 校验存在的意义。
 *
 * 【 600 二十 又提到 "3"】{@link BoxStatePacket} 末尾多了一段「这次为什么没成」
 * （{@link CompressionBoxService#noticeOf}）：裁决在服务端，玩家得在界面上看见
 * 那句话。老客户端少读一段 = 内容错位，所以同样要提号。
 *
 * 客户端侧的类（{@code CompressionBoxScreen}）只在 S2C 的 enqueueWork 里被加载，
 * 专用服务器不会碰到它（与排班表/药剂手册同款约定）。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class CompressionBoxNetworking {

    private CompressionBoxNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("3");
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
            PacketDistributor.sendToPlayer(player, new BoxStatePacket(hand.ordinal(), items,
                    CompressionBoxService.carryOf(player),
                    CompressionBoxService.noticeOf(player), true));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：把新内容推给已经开着这个界面的玩家 */
    public static void syncTo(ServerPlayer player, int handOrdinal, List<ItemStack> items) {
        try {
            PacketDistributor.sendToPlayer(player, new BoxStatePacket(handOrdinal, items,
                    CompressionBoxService.carryOf(player),
                    CompressionBoxService.noticeOf(player), false));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：那一叠变了、或者「这次为什么没成」变了，都要推一次 */
    public static void syncCarry(ServerPlayer player, int handOrdinal) {
        try {
            ItemStack box = player.getItemInHand(CompressionBoxService.handOf(handOrdinal));
            PacketDistributor.sendToPlayer(player, new BoxStatePacket(handOrdinal,
                    CompressionBoxData.read(box), CompressionBoxService.carryOf(player),
                    CompressionBoxService.noticeOf(player), false));
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

    /** 一个堆（1 个本体 + 真实数量 int）。空堆写 0，读回来是空——鼠标上那一叠用 */
    static void writeStack(RegistryFriendlyByteBuf buf, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            buf.writeByte(0);
            return;
        }
        buf.writeByte(1);
        ItemStack.STREAM_CODEC.encode(buf, stack.copyWithCount(1));
        buf.writeInt(stack.getCount());
    }

    static ItemStack readStack(RegistryFriendlyByteBuf buf) {
        if (buf.readByte() == 0) {
            return ItemStack.EMPTY;
        }
        ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
        int count = buf.readInt();
        if (s.isEmpty()) {
            return ItemStack.EMPTY;
        }
        s.setCount(Math.max(1, Math.min(count, CompressionBoxData.maxStack())));
        return s;
    }

    /* ==================== 0：开屏 / 刷新 ==================== */

    public static class BoxStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoxStatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "maid_smart", "compression_box_state"));

        public final int hand;
        public final List<ItemStack> items;
        /** 鼠标上挂着的那一叠（服务端说了算；空 = 没挂着） */
        public final ItemStack carry;
        /**
         * 「这次为什么没成」——服务端拒绝的原因（v1.2.2 实测六百二十；空串 = 这次没被拒）。
         *
         * 界面上那几行红字是**客户端自己算的**（即写死的那两条判据），但裁决始终在
         * 服务端——被拒的时候只有服务端说得清为什么，所以让服务端把那句话带过来，
         * 界面照原样画出来（见 {@code CompressionBoxScreen}）。
         */
        public final String notice;
        public final boolean open;

        public BoxStatePacket(int hand, List<ItemStack> items, ItemStack carry, String notice,
                              boolean open) {
            List<ItemStack> copy = CompressionBoxData.empty();
            for (int i = 0; i < copy.size() && i < items.size(); i++) {
                copy.set(i, items.get(i).copy());
            }
            this.hand = hand;
            this.items = copy;
            this.carry = carry == null ? ItemStack.EMPTY : carry.copy();
            this.notice = notice == null ? "" : notice;
            this.open = open;
        }

        public static void encode(BoxStatePacket pkt, RegistryFriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.open ? 1 : 0);
            writeItems(buf, pkt.items);
            writeStack(buf, pkt.carry);
            buf.writeUtf(pkt.notice);
        }

        public static BoxStatePacket decode(RegistryFriendlyByteBuf buf) {
            int hand = buf.readByte();
            boolean open = buf.readByte() != 0;
            List<ItemStack> items = readItems(buf);
            ItemStack carry = readStack(buf);
            return new BoxStatePacket(hand, items, carry, buf.readUtf(), open);
        }

        public static void handle(BoxStatePacket pkt, IPayloadContext ctx) {
            // S2C 方向校验（同排班表/药剂手册）——恶意客户端把 S2C 包发往服务端会
            // 加载客户端 Screen 类 → 专用服 NoClassDefFoundError
            if (ctx.flow() != PacketFlow.CLIENTBOUND) {
                return;
            }
            ctx.enqueueWork(() -> com.maidsmart.client.CompressionBoxScreen.accept(
                    pkt.hand, pkt.items, pkt.carry, pkt.notice, pkt.open));
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
        /** 0 = 左键，1 = 右键（只有 {@link CompressionBoxService#SLOT_CLICK} 用） */
        public final int button;
        /** 按着 Shift（快速移动）；只有 SLOT_CLICK 用 */
        public final boolean shift;

        public BoxActionPacket(int hand, int action, int index, int button, boolean shift) {
            this.hand = hand;
            this.action = action;
            this.index = index;
            this.button = button;
            this.shift = shift;
        }

        /** 客户端发一次点击（由界面调用） */
        public static void send(int hand, int action, int index, int button, boolean shift) {
            try {
                PacketDistributor.sendToServer(new BoxActionPacket(hand, action, index, button, shift));
            } catch (Throwable ignored) {
            }
        }

        public static void encode(BoxActionPacket pkt, RegistryFriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.action);
            buf.writeInt(pkt.index);
            buf.writeByte(pkt.button);
            buf.writeByte(pkt.shift ? 1 : 0);
        }

        public static BoxActionPacket decode(RegistryFriendlyByteBuf buf) {
            int hand = buf.readByte();
            int action = buf.readByte();
            int index = buf.readInt();
            int button = buf.readByte();
            boolean shift = buf.readByte() != 0;
            return new BoxActionPacket(hand, action, index, button, shift);
        }

        public static void handle(BoxActionPacket pkt, IPayloadContext ctx) {
            if (ctx.flow() != PacketFlow.SERVERBOUND) {
                return;
            }
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                // 不论成没成都要回一份内容：成了 = 界面上那一叠/那几格跟上；
                // 没成 = 把「这次为什么没成」那句话带给界面（六百二十：裁决在服务端，
                // 客户端自己那份只是"先说一声"）
                CompressionBoxService.handle(player, pkt.hand, pkt.action, pkt.index,
                        pkt.button, pkt.shift);
                syncCarry(player, pkt.hand);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
