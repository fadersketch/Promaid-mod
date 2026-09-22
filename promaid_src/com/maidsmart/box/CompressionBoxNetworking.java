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
 * 压缩盒网络层（v1.2.2 实测六百一十六；六百一十八加「鼠标上那一叠」）。
 *
 * 「打开的是哪一个盒子」用**手**来指认（主手 / 副手），不传坐标：盒子是纯道具，
 * 服务端每次操作都重新确认那只手里还是压缩盒（见 {@link CompressionBoxService}）。
 *
 * 包清单：
 * <ul>
 *   <li>0 {@link BoxStatePacket}（S2C）：「开屏 / 刷新」——手序号 + 5 格内容 +
 *       **鼠标上那一叠** + 是否开屏。内容里的大堆（114514）走 {@link #writeItems} 自己编：
 *       **物品本体写成 1 个**（原版编解码），真实数量另写一个 int——原版 {@code writeItem}
 *       把数量写成 1 字节，直接发 114514 会变成 82（javap 实证）；</li>
 *   <li>1 {@link BoxActionPacket}（C2S）：一次点击（手 + 动作 + 格子号 + 左右键 + 是否 Shift）
 *       → 交给 {@link CompressionBoxService} 搬东西，然后 S2C 回一份新内容。</li>
 * </ul>
 *
 * 【为什么 600 十八要动协议号】{@link BoxActionPacket} 多了「左右键 / Shift」两个字节，
 * {@link BoxStatePacket} 多了「手上那一叠」一段：老客户端连上新服务端会把后面那段
 * 当成别的包读，直接错位。协议号从 "1" 提到 "2"，版本对不上的客户端连不进来——
 * 这正是 Forge 这套 {@code NetworkRegistry} 校验存在的意义。
 *
 * 客户端侧的类（{@code CompressionBoxScreen}）只在 S2C 的 enqueueWork 里被加载，
 * 专用服务器不会碰到它（与排班表/药剂手册同款约定）。
 */
public final class CompressionBoxNetworking {

    private static final String PROTOCOL_VERSION = "2";

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
                    new BoxStatePacket(hand.ordinal(), items,
                            CompressionBoxService.carryOf(player), true));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：把新内容推给已经开着这个界面的玩家 */
    public static void syncTo(ServerPlayer player, int handOrdinal, List<ItemStack> items) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BoxStatePacket(handOrdinal, items,
                            CompressionBoxService.carryOf(player), false));
        } catch (Throwable ignored) {
        }
    }

    /** 服务端：那一叠变了也要推一次（手上拿起来了、放下去了一部分） */
    public static void syncCarry(ServerPlayer player, int handOrdinal) {
        try {
            ItemStack box = player.m_21120_(CompressionBoxService.handOf(handOrdinal));
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BoxStatePacket(handOrdinal, CompressionBoxData.read(box),
                            CompressionBoxService.carryOf(player), false));
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
            // 【六百一十八修】这里原来是 m_41769_（= grow，= setCount(getCount()+n)）：
            // 编过去的堆恒为 1 个，grow(3) 就变成 4 —— **界面上每个数字都多 1**（盒子那 5 格、
            // 鼠标上那一叠、开屏/刷新各一次都错）。setCount 是 m_41764_（javap 实证）。
            // neo 那棵树一直写的是 setCount，两树口径从这一批起对齐。
            s.m_41764_(Math.max(1, Math.min(count, CompressionBoxData.maxStack())));
            out.set(i, s);
        }
        return out;
    }

    /** 一个堆（1 个本体 + 真实数量 int）。空堆写 0，读回来是空——鼠标上那一叠用 */
    static void writeStack(FriendlyByteBuf buf, ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            buf.writeByte(0);
            return;
        }
        buf.writeByte(1);
        buf.m_130055_(stack.m_255036_(1));
        buf.writeInt(stack.m_41613_());
    }

    static ItemStack readStack(FriendlyByteBuf buf) {
        if (buf.readByte() == 0) {
            return ItemStack.f_41583_;
        }
        ItemStack s = buf.m_130267_();
        int count = buf.readInt();
        if (s.m_41619_()) {
            return ItemStack.f_41583_;
        }
        // 同上：setCount（m_41764_），不是 grow
        s.m_41764_(Math.max(1, Math.min(count, CompressionBoxData.maxStack())));
        return s;
    }

    /* ==================== 0：开屏 / 刷新 ==================== */

    public static class BoxStatePacket {
        public final int hand;
        public final List<ItemStack> items;
        /** 鼠标上挂着的那一叠（服务端说了算；空 = 没挂着） */
        public final ItemStack carry;
        public final boolean open;

        public BoxStatePacket(int hand, List<ItemStack> items, ItemStack carry, boolean open) {
            List<ItemStack> copy = CompressionBoxData.empty();
            for (int i = 0; i < copy.size() && i < items.size(); i++) {
                copy.set(i, items.get(i).m_41777_());
            }
            this.hand = hand;
            this.items = copy;
            this.carry = carry == null ? ItemStack.f_41583_ : carry.m_41777_();
            this.open = open;
        }

        public static void encode(BoxStatePacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.open ? 1 : 0);
            writeItems(buf, pkt.items);
            writeStack(buf, pkt.carry);
        }

        public static BoxStatePacket decode(FriendlyByteBuf buf) {
            int hand = buf.readByte();
            boolean open = buf.readByte() != 0;
            List<ItemStack> items = readItems(buf);
            return new BoxStatePacket(hand, items, readStack(buf), open);
        }

        public static void handle(BoxStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return; // 方向校验：恶意客户端把 S2C 包发往服务端会加载客户端类
            }
            ctx.get().enqueueWork(() -> com.maidsmart.client.CompressionBoxScreen.accept(
                    pkt.hand, pkt.items, pkt.carry, pkt.open));
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 1：一次点击 ==================== */

    public static class BoxActionPacket {
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
                CHANNEL.sendToServer(new BoxActionPacket(hand, action, index, button, shift));
            } catch (Throwable ignored) {
            }
        }

        public static void encode(BoxActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.hand);
            buf.writeByte(pkt.action);
            buf.writeInt(pkt.index);
            buf.writeByte(pkt.button);
            buf.writeByte(pkt.shift ? 1 : 0);
        }

        public static BoxActionPacket decode(FriendlyByteBuf buf) {
            int hand = buf.readByte();
            int action = buf.readByte();
            int index = buf.readInt();
            int button = buf.readByte();
            boolean shift = buf.readByte() != 0;
            return new BoxActionPacket(hand, action, index, button, shift);
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
                if (CompressionBoxService.handle(player, pkt.hand, pkt.action, pkt.index,
                        pkt.button, pkt.shift)) {
                    // 回一份新内容（含鼠标上那一叠）——手上拿起来/放下去也要让界面跟上
                    syncCarry(player, pkt.hand);
                }
            });
            c.setPacketHandled(true);
        }
    }
}
