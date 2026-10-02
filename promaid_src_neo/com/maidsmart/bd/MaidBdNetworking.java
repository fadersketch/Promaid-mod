package com.maidsmart.bd;

import com.maidsmart.tool.PromaidLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * 实测七百七十二【超越维度联动的网络层：让"配置面板"能改服务端规则】。
 *
 * <h2>为什么必须有它</h2>
 * 配置面板（{@code PromaidConfigScreen}）是**纯客户端**的 {@code Screen}：它改的
 * {@code ModConfigSpec} 只在客户端内存 + 客户端自己的 toml 里生效，**到不了服务端**（本 mod
 * 没有任何 config 同步包）。而超越维度的规则文件 {@code config/promaid_bd_rules.json} 与
 * "某只女仆要不要产出回收"的 {@code persistentData} 都是**服务端**的东西。所以面板要提供这些
 * 选项，就得多一条 C2S 通道——本类就是那条通道（照 {@code MaidSeatNetworking} /
 * {@code ScheduleNetworking} 的骨架）。
 *
 * <h2>三个包</h2>
 * <ul>
 *   <li><b>BdRulePacket（C2S）</b>：改规则名单（addMove/addKeep/setKeepN/addKeepAtLeast/remove）
 *       或仅查询（query）。服务端 {@code hasPermission(2)} 校验后调 {@link MaidBdRules}
 *       （改完即落盘），无论改没改都回推一份最新状态。</li>
 *   <li><b>BdRulesStatePacket（S2C）</b>：把四类规则编码下发，面板子页据此刷新列表。</li>
 *   <li><b>BdPerMaidPacket（C2S）</b>：开/关"产出回收"（{@code maidUuid} 空 = 取玩家附近最近的
 *       一只自己的女仆），校验 主人/OP + 8 格内；回推 {@strong BdPerMaidStatePacket} 让面板显示。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class MaidBdNetworking {

    /** 客户端最近一次拿到的规则状态（面板子页渲染用）。 */
    public static volatile List<String> MOVE = new ArrayList<>();
    public static volatile List<String> KEEP = new ArrayList<>();
    public static volatile List<String> KEEP_N = new ArrayList<>();
    public static volatile List<String> AT_LEAST = new ArrayList<>();
    /** 客户端最近一次拿到的"最近女仆·产出回收"状态（面板行显示用）。 */
    public static volatile boolean PER_MAID_ON = false;
    public static volatile String PER_MAID_NAME = "";

    private MaidBdNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToServer(BdRulePacket.TYPE,
                StreamCodec.ofMember(BdRulePacket::encode, BdRulePacket::decode),
                BdRulePacket::handle);
        r.playToClient(BdRulesStatePacket.TYPE,
                StreamCodec.ofMember(BdRulesStatePacket::encode, BdRulesStatePacket::decode),
                BdRulesStatePacket::handle);
        r.playToServer(BdPerMaidPacket.TYPE,
                StreamCodec.ofMember(BdPerMaidPacket::encode, BdPerMaidPacket::decode),
                BdPerMaidPacket::handle);
        r.playToClient(BdPerMaidStatePacket.TYPE,
                StreamCodec.ofMember(BdPerMaidStatePacket::encode, BdPerMaidStatePacket::decode),
                BdPerMaidStatePacket::handle);
    }

    // ---------------- 客户端出口 ----------------

    /** 请求一次规则状态（进入子页时调）。 */
    public static void requestRuleState() {
        sendRule((byte) 0, "", 0);
    }

    public static void sendRule(byte op, String entry, int n) {
        try {
            PacketDistributor.sendToServer(new BdRulePacket(op, entry, n));
        } catch (Throwable ignored) {
        }
    }

    /** 开/关"产出回收"；{@code maidUuid} 空 = 取最近的一只自己的女仆。 */
    public static void togglePerMaid(String maidUuid, boolean on) {
        try {
            PacketDistributor.sendToServer(new BdPerMaidPacket(maidUuid == null ? "" : maidUuid, on));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 服务端：规则 ----------------

    private static void pushState(ServerPlayer player) {
        try {
            PacketDistributor.sendToPlayer(player, new BdRulesStatePacket(
                    MaidBdRules.describeMatches((byte) 1),
                    MaidBdRules.describeMatches((byte) 2),
                    MaidBdRules.describeKeepN(),
                    MaidBdRules.describeAtLeast()));
        } catch (Throwable ignored) {
        }
    }

    public static class BdRulePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BdRulePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "bd_rule"));

        public final byte op;
        public final String entry;
        public final int n;

        public BdRulePacket(byte op, String entry, int n) {
            this.op = op;
            this.entry = entry == null ? "" : entry;
            this.n = n;
        }

        public static void encode(BdRulePacket pkt, FriendlyByteBuf buf) {
            buf.writeByte(pkt.op);
            buf.writeUtf(pkt.entry);
            buf.writeInt(pkt.n);
        }

        public static BdRulePacket decode(FriendlyByteBuf buf) {
            return new BdRulePacket(buf.readByte(), buf.readUtf(), buf.readInt());
        }

        public static void handle(BdRulePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) {
                    return;
                }
                if (!sp.hasPermissions(2)) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c超越维度规则是全局配置，只有 OP 能改。"));
                    return;
                }
                try {
                    switch (pkt.op) {
                        case 1 -> MaidBdRules.addMove(pkt.entry);
                        case 2 -> MaidBdRules.addKeep(pkt.entry);
                        case 3 -> MaidBdRules.setKeepN(pkt.entry, pkt.n);
                        case 4 -> MaidBdRules.addKeepAtLeast(pkt.entry, pkt.n);
                        case 5 -> MaidBdRules.remove(pkt.entry);
                        default -> { /* 0 = 仅查询 */ }
                    }
                } catch (Throwable t) {
                    PromaidLog.log("超越维度规则", "面板改规则失败：" + t);
                }
                pushState(sp);
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static class BdRulesStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BdRulesStatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "bd_rules_state"));

        public final List<String> move;
        public final List<String> keep;
        public final List<String> keepN;
        public final List<String> atLeast;

        public BdRulesStatePacket(List<String> move, List<String> keep, List<String> keepN, List<String> atLeast) {
            this.move = move;
            this.keep = keep;
            this.keepN = keepN;
            this.atLeast = atLeast;
        }

        public static void encode(BdRulesStatePacket pkt, FriendlyByteBuf buf) {
            writeList(buf, pkt.move);
            writeList(buf, pkt.keep);
            writeList(buf, pkt.keepN);
            writeList(buf, pkt.atLeast);
        }

        public static BdRulesStatePacket decode(FriendlyByteBuf buf) {
            return new BdRulesStatePacket(readList(buf), readList(buf), readList(buf), readList(buf));
        }

        private static void writeList(FriendlyByteBuf buf, List<String> list) {
            buf.writeInt(list.size());
            for (String s : list) {
                buf.writeUtf(s);
            }
        }

        private static List<String> readList(FriendlyByteBuf buf) {
            int n = buf.readInt();
            List<String> out = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
            for (int i = 0; i < n && i < 4096; i++) {
                out.add(buf.readUtf());
            }
            return out;
        }

        public static void handle(BdRulesStatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                MOVE = pkt.move;
                KEEP = pkt.keep;
                KEEP_N = pkt.keepN;
                AT_LEAST = pkt.atLeast;
                // 面板子页开着就地刷新（列表/高亮立刻反映服务端最新状态）
                try {
                    net.minecraft.client.gui.screens.Screen cur =
                            net.minecraft.client.Minecraft.getInstance().screen;
                    if (cur instanceof com.maidsmart.config.PromaidConfigScreen s) {
                        s.onBdRulesState();
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- 服务端：每女仆 ----------------

    public static class BdPerMaidPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BdPerMaidPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "bd_per_maid"));

        public final String maidUuid;
        public final boolean on;

        public BdPerMaidPacket(String maidUuid, boolean on) {
            this.maidUuid = maidUuid == null ? "" : maidUuid;
            this.on = on;
        }

        public static void encode(BdPerMaidPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeBoolean(pkt.on);
        }

        public static BdPerMaidPacket decode(FriendlyByteBuf buf) {
            return new BdPerMaidPacket(buf.readUtf(), buf.readBoolean());
        }

        public static void handle(BdPerMaidPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)
                        || !(sp.level() instanceof ServerLevel level)) {
                    return;
                }
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid = null;
                try {
                    if (!pkt.maidUuid.isEmpty()) {
                        maid = (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid)
                                level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                    } else {
                        // 空 uuid = 取玩家附近最近的一只自己的女仆（与 bd_deposit 命令同口径）
                        maid = level.getEntitiesOfClass(
                                        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                                        sp.getBoundingBox().inflate(64.0))
                                .stream()
                                .filter(m -> m.isOwnedBy(sp))
                                .min(java.util.Comparator.comparingDouble(sp::distanceToSqr))
                                .orElse(null);
                    }
                } catch (Throwable ignored) {
                }
                if (maid == null) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c附近没有你的女仆（或她不在加载范围内）。"));
                    return;
                }
                if (!maid.isOwnedBy(sp) && !sp.hasPermissions(2)) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c只有主人或 OP 能改她的产出回收。"));
                    return;
                }
                if (sp.distanceToSqr(maid) > 64.0) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c离她太远了（8 格以内才能改）。"));
                    return;
                }
                MaidBdDeposit.setOn(maid, pkt.on);
                try {
                    PacketDistributor.sendToPlayer(sp, new BdPerMaidStatePacket(
                            maid.getUUID().toString(),
                            maid.getDisplayName() == null ? "女仆" : maid.getDisplayName().getString(),
                            MaidBdDeposit.isOn(maid)));
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static class BdPerMaidStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BdPerMaidStatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "bd_per_maid_state"));

        public final String uuid;
        public final String name;
        public final boolean on;

        public BdPerMaidStatePacket(String uuid, String name, boolean on) {
            this.uuid = uuid;
            this.name = name;
            this.on = on;
        }

        public static void encode(BdPerMaidStatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid);
            buf.writeUtf(pkt.name);
            buf.writeBoolean(pkt.on);
        }

        public static BdPerMaidStatePacket decode(FriendlyByteBuf buf) {
            return new BdPerMaidStatePacket(buf.readUtf(), buf.readUtf(), buf.readBoolean());
        }

        public static void handle(BdPerMaidStatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                PER_MAID_ON = pkt.on;
                PER_MAID_NAME = pkt.name;
                try {
                    net.minecraft.client.gui.screens.Screen cur =
                            net.minecraft.client.Minecraft.getInstance().screen;
                    if (cur instanceof com.maidsmart.config.PromaidConfigScreen s) {
                        s.onBdPerMaidState();
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
