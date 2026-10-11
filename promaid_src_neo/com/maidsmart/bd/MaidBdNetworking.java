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
 * 超越维度联动的网络层：让**纯客户端**的配置面板（{@code PromaidConfigScreen}）能改**服务端**规则。
 * 面板改的 {@code ModConfigSpec} 只在客户端内存 + 客户端 toml 生效、到不了服务端（本 mod 没有
 * config 同步包），而规则文件 {@code config/promaid_bd_rules.json} 与"某只女仆要不要产出回收"的
 * {@code persistentData} 都是服务端的——所以要一条 C2S 通道（照 {@code MaidSeatNetworking} 骨架）。
 *
 * 四个包：① {@code BdRulePacket}（C2S）改规则名单（addMove/addKeep/setKeepN/addKeepAtLeast/remove）
 * 或查询；服务端 {@code hasPermission(2)} 校验后调 {@link MaidBdRules}（改完即落盘），无论改没改都
 * 回推最新状态。② {@code BdRulesStatePacket}（S2C）下发四类规则，面板子页据此刷新。③
 * {@code BdPerMaidPacket}（C2S）开/关"产出回收"（{@code maidUuid} 空 = 取附近最近的一只自己的女仆），
 * 校验主人/OP + 8 格内。④ {@code BdPerMaidStatePacket}（S2C）回该女仆状态。
 * 【平台差异】1.21.1 用 NeoForge {@code CustomPacketPayload}+{@code PayloadRegistrar} 自动注册；
 * 1.20.1/Forge 用 {@link SimpleChannel}，注册由 {@code ProMaidMod} 构造期调 {@link #register()}。
 * 客户端面板回调走**反射**调 {@code onBdRulesState()} / {@code onBdPerMaidState()}，不强绑编译期。
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
        }
    }

    /** 开/关"产出回收"；{@code maidUuid} 空 = 取最近的一只自己的女仆。 */
    public static void togglePerMaid(String maidUuid, boolean on) {
        try {
            PacketDistributor.sendToServer(new BdPerMaidPacket(maidUuid == null ? "" : maidUuid, on));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
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
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
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
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
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
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
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
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
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
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidBdNetworking", ignored);
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
