package com.maidsmart.bd;

import com.maidsmart.tool.PromaidLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 实测七百七十二【超越维度联动的网络层：让"配置面板"能改服务端规则】。
 *
 * <h2>为什么必须有它</h2>
 * 配置面板（{@code PromaidConfigScreen}）是**纯客户端**的 {@code Screen}：它改的
 * {@code ModConfigSpec} 只在客户端内存 + 客户端自己的 toml 里生效，**到不了服务端**（本 mod
 * 没有任何 config 同步包）。而超越维度的规则文件 {@code config/promaid_bd_rules.json} 与
 * "某只女仆要不要产出回收"的 {@code persistentData} 都是**服务端**的东西。所以面板要提供这些
 * 选项，就得多一条 C2S 通道——本类就是那条通道（照 {@code MaidSeatNetworking} /
 * {@code MaidGoetyNetworking} 的骨架）。
 *
 * <h2>四个包</h2>
 * <ul>
 *   <li><b>BdRulePacket（C2S）</b>：改规则名单（addMove/addKeep/setKeepN/addKeepAtLeast/remove）
 *       或仅查询（query）。服务端 {@code hasPermission(2)} 校验后调 {@link MaidBdRules}
 *       （改完即落盘），无论改没改都回推一份最新状态。</li>
 *   <li><b>BdRulesStatePacket（S2C）</b>：把四类规则编码下发，面板子页据此刷新列表。</li>
 *   <li><b>BdPerMaidPacket（C2S）</b>：开/关"产出回收"（{@code maidUuid} 空 = 取玩家附近最近的
 *       一只自己的女仆），校验 主人/OP + 8 格内；回推 {@code BdPerMaidStatePacket} 让面板显示。</li>
 *   <li><b>BdPerMaidStatePacket（S2C）</b>：把该女仆的产出回收状态回给发起者。</li>
 * </ul>
 *
 * <p>【1.20.1 差异】1.21.1 上是 NeoForge 的 {@code CustomPacketPayload}+{@code PayloadRegistrar}
 * 与 {@code @EventBusSubscriber(Bus.MOD)} 自动注册；1.20.1/Forge 改用 {@link SimpleChannel}
 * （本树已有 8 处同样写法），注册由 {@code ProMaidMod} 构造期调 {@link #register()}。
 * S2C 走 {@code PacketDistributor.PLAYER.with(() -> player)}（与 MaidGoetyNetworking 同款）。
 * 客户端面板回调用**反射**调用 {@code onBdRulesState()} / {@code onBdPerMaidState()}——
 * 那两处方法由配置面板（PromaidConfigScreen）提供，此处不强绑编译期。
 */
public final class MaidBdNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "bd_compat"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

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

    public static void register() {
        CHANNEL.registerMessage(0, BdRulePacket.class,
                BdRulePacket::encode, BdRulePacket::decode, BdRulePacket::handle);
        CHANNEL.registerMessage(1, BdRulesStatePacket.class,
                BdRulesStatePacket::encode, BdRulesStatePacket::decode, BdRulesStatePacket::handle);
        CHANNEL.registerMessage(2, BdPerMaidPacket.class,
                BdPerMaidPacket::encode, BdPerMaidPacket::decode, BdPerMaidPacket::handle);
        CHANNEL.registerMessage(3, BdPerMaidStatePacket.class,
                BdPerMaidStatePacket::encode, BdPerMaidStatePacket::decode, BdPerMaidStatePacket::handle);
    }

    // ---------------- 客户端出口 ----------------

    /** 请求一次规则状态（进入子页时调）。 */
    public static void requestRuleState() {
        sendRule((byte) 0, "", 0);
    }

    public static void sendRule(byte op, String entry, int n) {
        try {
            CHANNEL.sendToServer(new BdRulePacket(op, entry, n));
        } catch (Throwable ignored) {
        }
    }

    /** 开/关"产出回收"；{@code maidUuid} 空 = 取最近的一只自己的女仆。 */
    public static void togglePerMaid(String maidUuid, boolean on) {
        try {
            CHANNEL.sendToServer(new BdPerMaidPacket(maidUuid == null ? "" : maidUuid, on));
        } catch (Throwable ignored) {
        }
    }

    /** 反射通知当前面板（若有）刷新——面板类由调用方维护，此处不强绑编译期。 */
    private static void refreshScreen(String method) {
        try {
            net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.m_91087_().f_91080_;
            if (cur == null) {
                return;
            }
            Class<?> c = cur.getClass();
            while (c != null) {
                try {
                    c.getDeclaredMethod(method).invoke(cur);
                    return;
                } catch (NoSuchMethodException ignored) {
                    c = c.getSuperclass();   // 面板可能被匿名/子类包一层
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 服务端：规则 ----------------

    private static void pushState(ServerPlayer player) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BdRulesStatePacket(
                    MaidBdRules.describeMatches((byte) 1),
                    MaidBdRules.describeMatches((byte) 2),
                    MaidBdRules.describeKeepN(),
                    MaidBdRules.describeAtLeast()));
        } catch (Throwable ignored) {
        }
    }

    public static class BdRulePacket {
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
            buf.m_130070_(pkt.entry);
            buf.writeInt(pkt.n);
        }

        public static BdRulePacket decode(FriendlyByteBuf buf) {
            return new BdRulePacket(buf.readByte(), buf.m_130277_(), buf.readInt());
        }

        public static void handle(BdRulePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sp = ctx.getSender();
                if (sp == null) {
                    return;
                }
                if (!sp.m_20310_(2)) {
                    sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
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
            ctx.setPacketHandled(true);
        }
    }

    public static class BdRulesStatePacket {
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
                buf.m_130070_(s);
            }
        }

        private static List<String> readList(FriendlyByteBuf buf) {
            int n = buf.readInt();
            List<String> out = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
            for (int i = 0; i < n && i < 4096; i++) {
                out.add(buf.m_130277_());
            }
            return out;
        }

        public static void handle(BdRulesStatePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                MOVE = pkt.move;
                KEEP = pkt.keep;
                KEEP_N = pkt.keepN;
                AT_LEAST = pkt.atLeast;
                // 面板子页开着就地刷新（列表/高亮立刻反映服务端最新状态）
                refreshScreen("onBdRulesState");
            });
            ctx.setPacketHandled(true);
        }
    }

    // ---------------- 服务端：每女仆 ----------------

    public static class BdPerMaidPacket {
        public final String maidUuid;
        public final boolean on;

        public BdPerMaidPacket(String maidUuid, boolean on) {
            this.maidUuid = maidUuid == null ? "" : maidUuid;
            this.on = on;
        }

        public static void encode(BdPerMaidPacket pkt, FriendlyByteBuf buf) {
            buf.m_130070_(pkt.maidUuid);
            buf.writeBoolean(pkt.on);
        }

        public static BdPerMaidPacket decode(FriendlyByteBuf buf) {
            return new BdPerMaidPacket(buf.m_130277_(), buf.readBoolean());
        }

        public static void handle(BdPerMaidPacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sp = ctx.getSender();
                if (sp == null || !(sp.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid = null;
                try {
                    if (!pkt.maidUuid.isEmpty()) {
                        maid = (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid)
                                level.m_8791_(java.util.UUID.fromString(pkt.maidUuid));
                    } else {
                        // 空 uuid = 取玩家附近最近的一只自己的女仆（与 bd_deposit 命令同口径）
                        maid = level.m_45976_(
                                        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                                        sp.m_20191_().m_82400_(64.0))
                                .stream()
                                .filter(m -> m.m_21830_(sp))
                                .min(java.util.Comparator.comparingDouble(sp::m_20280_))
                                .orElse(null);
                    }
                } catch (Throwable ignored) {
                }
                if (maid == null) {
                    sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7c附近没有你的女仆（或她不在加载范围内）。"));
                    return;
                }
                if (!maid.m_21830_(sp) && !sp.m_20310_(2)) {
                    sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7c只有主人或 OP 能改她的产出回收。"));
                    return;
                }
                if (sp.m_20280_(maid) > 64.0) {
                    sp.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7c离她太远了（8 格以内才能改）。"));
                    return;
                }
                MaidBdDeposit.setOn(maid, pkt.on);
                try {
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new BdPerMaidStatePacket(
                            maid.m_20148_().toString(),
                            maid.m_5446_() == null ? "女仆" : maid.m_5446_().getString(),
                            MaidBdDeposit.isOn(maid)));
                } catch (Throwable ignored) {
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    public static class BdPerMaidStatePacket {
        public final String uuid;
        public final String name;
        public final boolean on;

        public BdPerMaidStatePacket(String uuid, String name, boolean on) {
            this.uuid = uuid;
            this.name = name;
            this.on = on;
        }

        public static void encode(BdPerMaidStatePacket pkt, FriendlyByteBuf buf) {
            buf.m_130070_(pkt.uuid);
            buf.m_130070_(pkt.name);
            buf.writeBoolean(pkt.on);
        }

        public static BdPerMaidStatePacket decode(FriendlyByteBuf buf) {
            return new BdPerMaidStatePacket(buf.m_130277_(), buf.m_130277_(), buf.readBoolean());
        }

        public static void handle(BdPerMaidStatePacket pkt, Supplier<NetworkEvent.Context> ctxSup) {
            NetworkEvent.Context ctx = ctxSup.get();
            ctx.enqueueWork(() -> {
                PER_MAID_ON = pkt.on;
                PER_MAID_NAME = pkt.name;
                refreshScreen("onBdPerMaidState");
            });
            ctx.setPacketHandled(true);
        }
    }
}
