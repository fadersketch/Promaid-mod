package com.maidsmart.config;

import com.maidsmart.tool.PromaidLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多人下「配置以服务端为准」的同步层（开关 {@code misc.serverForcedConfig}，默认关）。
 * 打开后：服务端把整份配置连同"你是不是 OP / 是不是多人"推给每个客户端，客户端在内存里套用
 * （**不写客户端文件**），非 OP 的面板只读；OP 的改动发 C2S、由服务端落盘并广播全服。
 * 三个 C2S（SetForced / SetValues / ApplyPreset，一律 {@code hasPermissions(2)} 校验）加
 * 一个 S2C（ConfigState）。客户端静态缓存照 {@code MaidGoetyAuto} / {@code MaidBdNetworking}。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class ConfigSyncNetworking {

    /** 开关自身的路径：不走通用值通道（只由 SetForced 改） */
    private static final String FORCED_PATH = "misc.serverForcedConfig";
    /** 单个列表值上限（防异常包撑爆内存） */
    private static final int LIST_CAP = 8192;

    private static volatile boolean FORCED = false;
    private static volatile boolean AM_OP = false;
    private static volatile boolean MULTIPLAYER = false;
    private static volatile Map<String, Object> SERVER_VALUES = new LinkedHashMap<>();
    private static volatile Map<String, Object> OWN_VALUES = null;

    private ConfigSyncNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(ConfigStatePacket.TYPE,
                StreamCodec.ofMember(ConfigStatePacket::encode, ConfigStatePacket::decode),
                ConfigStatePacket::handle);
        r.playToServer(SetForcedPacket.TYPE,
                StreamCodec.ofMember(SetForcedPacket::encode, SetForcedPacket::decode),
                SetForcedPacket::handle);
        r.playToServer(SetValuesPacket.TYPE,
                StreamCodec.ofMember(SetValuesPacket::encode, SetValuesPacket::decode),
                SetValuesPacket::handle);
        r.playToServer(ApplyPresetPacket.TYPE,
                StreamCodec.ofMember(ApplyPresetPacket::encode, ApplyPresetPacket::decode),
                ApplyPresetPacket::handle);
    }

    // ---------------- 状态查询（面板用） ----------------

    public static boolean isForced() {
        return FORCED;
    }

    public static boolean isAmOp() {
        return AM_OP;
    }

    /** 面板只读：全服以服务端为准、且本机不是 OP */
    public static boolean isLocked() {
        return FORCED && !AM_OP;
    }

    /** 该不该显示「配置以服务端为准」这一行：多人 + 本机是 OP */
    public static boolean showForcedRow() {
        return MULTIPLAYER && AM_OP;
    }

    /** 服务端最近一次下发的值（OP 在面板里按它做差量） */
    public static Map<String, Object> serverValues() {
        return SERVER_VALUES;
    }

    // ---------------- 客户端出口 ----------------

    public static void pushForced(boolean on) {
        try {
            PacketDistributor.sendToServer(new SetForcedPacket(on));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    public static void pushValues(Map<String, Object> changed) {
        try {
            if (changed != null && !changed.isEmpty()) {
                PacketDistributor.sendToServer(new SetValuesPacket(changed));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    public static void pushPreset(boolean diy) {
        try {
            PacketDistributor.sendToServer(new ApplyPresetPacket(diy));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    /** 本机当前值与服务端快照的差量（只含真正变了的项）。 */
    public static Map<String, Object> diffFromServer() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (Object[] e : MaidConfigPreset.fields()) {
                String path = (String) e[0];
                if (FORCED_PATH.equals(path)) {
                    continue;
                }
                Object mine = readCv(e[1]);
                Object srv = SERVER_VALUES.get(path);
                if (mine instanceof List<?> l) {
                    mine = new ArrayList<>(l);
                }
                if (mine != null && !mine.equals(srv)) {
                    out.put(path, mine);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        return out;
    }

    /** 非 OP 关面板时把服务端快照再套一遍，丢掉可能的本地漂移。 */
    public static void reapplyServer() {
        applyMap(SERVER_VALUES);
    }

    /** 退出世界/解锁时把客户端自己的那份值还原回去。 */
    public static void restoreOwn() {
        Map<String, Object> own = OWN_VALUES;
        if (own != null) {
            applyMap(own);
            OWN_VALUES = null;
        }
    }

    // ---------------- 客户端：收到状态 ----------------

    private static void onState(ConfigStatePacket pkt) {
        SERVER_VALUES = pkt.values;
        boolean wasForced = FORCED;
        FORCED = pkt.forced;
        AM_OP = pkt.amOp;
        MULTIPLAYER = pkt.multiplayer;
        try {
            if (FORCED) {
                captureOwn();
                applyMap(pkt.values);
            } else if (wasForced) {
                restoreOwn();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        refreshScreen();
    }

    /** 首次进入锁定态时把客户端自己的值留一份（解锁时还原）。 */
    private static void captureOwn() {
        if (OWN_VALUES != null) {
            return;
        }
        Map<String, Object> own = new LinkedHashMap<>();
        try {
            for (Object[] e : MaidConfigPreset.fields()) {
                String path = (String) e[0];
                if (FORCED_PATH.equals(path)) {
                    continue;
                }
                Object v = readCv(e[1]);
                if (v instanceof List<?> l) {
                    v = new ArrayList<>(l);
                }
                if (v != null) {
                    own.put(path, v);
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        OWN_VALUES = own;
    }

    private static void applyMap(Map<String, Object> vals) {
        if (vals == null) {
            return;
        }
        for (Map.Entry<String, Object> e : vals.entrySet()) {
            try {
                Object cv = byPath(e.getKey());
                if (cv != null) {
                    ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) cv).set(e.getValue());
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
            }
        }
    }

    private static Object readCv(Object cv) {
        try {
            return ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) cv).get();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored); return null;
        }
    }

    private static Object byPath(String path) {
        for (Object[] e : MaidConfigPreset.fields()) {
            if (path.equals(e[0])) {
                return e[1];
            }
        }
        return null;
    }

    /** 面板开着就地刷新（同包直连，面板类由本模组自持，不引入跨模块依赖）。 */
    private static void refreshScreen() {
        try {
            net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
            if (cur instanceof PromaidConfigScreen s) {
                s.onConfigState();
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    // ---------------- 服务端 ----------------

    private static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object[] e : MaidConfigPreset.fields()) {
            String path = (String) e[0];
            if (FORCED_PATH.equals(path)) {
                continue;
            }
            Object v = readCv(e[1]);
            if (v instanceof List<?> l) {
                v = new ArrayList<>(l);
            }
            if (v != null) {
                m.put(path, v);
            }
        }
        return m;
    }

    private static boolean isMultiplayer(MinecraftServer srv) {
        try {
            return srv != null && (srv.isDedicatedServer() || srv.getPlayerList().getPlayerCount() > 1);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored); return false;
        }
    }

    public static void pushState(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            MinecraftServer srv = player.level().getServer();
            PacketDistributor.sendToPlayer(player, new ConfigStatePacket(
                    MaidSmartConfig.MISC_SERVER_FORCED_CONFIG.get(),
                    player.hasPermissions(2),
                    isMultiplayer(srv),
                    snapshot()));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    /** 逐个推（amOp 因人而异，不能一条广播包发给所有人）。 */
    public static void broadcast(MinecraftServer srv) {
        if (srv == null) {
            return;
        }
        try {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                pushState(p);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    private static void persistAndRefresh() {
        try {
            com.maidsmart.ProMaidMod.persistConfigQuietly();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        try {
            com.maidsmart.task.MaidMineBehavior.loadCustomOres();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        try {
            com.maidsmart.task.MaidWoodBehavior.loadCustomWoods();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
        try {
            com.maidsmart.task.MaidCookBehavior.loadCookLists();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("ConfigSyncNetworking", ignored);
        }
    }

    /** 只收与该配置项同型的值，不同型一律丢弃（越界数值由 NeoForge 下次加载配置时按声明区间纠正） */
    private static Object sanitize(Object cv, Object v) {
        if (cv == null || v == null) {
            return null;
        }
        if (cv instanceof net.neoforged.neoforge.common.ModConfigSpec.BooleanValue) {
            return v instanceof Boolean ? v : null;
        }
        if (cv instanceof net.neoforged.neoforge.common.ModConfigSpec.IntValue) {
            return v instanceof Integer ? v : null;
        }
        if (cv instanceof net.neoforged.neoforge.common.ModConfigSpec.DoubleValue) {
            return v instanceof Double ? v : null;
        }
        if (cv instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> any) {
            Object cur = any.get();
            if (cur instanceof List) {
                return v instanceof List ? v : null;
            }
            if (cur instanceof String) {
                return v instanceof String ? v : null;
            }
        }
        return v;
    }

    private static void applyFromClient(ServerPlayer sp, Map<String, Object> vals, String source) {
        int n = 0;
        for (Map.Entry<String, Object> e : vals.entrySet()) {
            String path = e.getKey();
            if (FORCED_PATH.equals(path)) {
                continue;
            }
            try {
                Object cv = byPath(path);
                if (cv == null) {
                    continue;
                }
                Object v = sanitize(cv, e.getValue());
                if (v != null) {
                    ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) cv).set(v);
                    n++;
                }
            } catch (Throwable t) {
                PromaidLog.log("配置同步", "写不进 " + path + "：" + t);
            }
        }
        if (n > 0) {
            persistAndRefresh();
        }
        broadcast(sp.level().getServer());
        PromaidLog.log("配置同步", source + "：服务端改 " + n + " 项");
    }

    // ---------------- 值编解码（带类型标签） ----------------

    private static void writeValue(FriendlyByteBuf buf, Object v) {
        if (v instanceof Boolean b) {
            buf.writeByte(0);
            buf.writeBoolean(b);
        } else if (v instanceof Integer i) {
            buf.writeByte(1);
            buf.writeInt(i);
        } else if (v instanceof Double d) {
            buf.writeByte(2);
            buf.writeDouble(d);
        } else if (v instanceof List<?> l) {
            buf.writeByte(4);
            buf.writeInt(l.size());
            for (Object o : l) {
                buf.writeUtf(String.valueOf(o));
            }
        } else {
            buf.writeByte(3);
            buf.writeUtf(v == null ? "" : String.valueOf(v));
        }
    }

    private static Object readValue(FriendlyByteBuf buf) {
        byte t = buf.readByte();
        if (t == 0) {
            return buf.readBoolean();
        }
        if (t == 1) {
            return buf.readInt();
        }
        if (t == 2) {
            return buf.readDouble();
        }
        if (t == 4) {
            int n = buf.readInt();
            List<String> out = new ArrayList<>(Math.max(0, Math.min(n, LIST_CAP)));
            for (int i = 0; i < n && i < LIST_CAP; i++) {
                out.add(buf.readUtf());
            }
            return out;
        }
        return buf.readUtf();
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, Object> m) {
        buf.writeInt(m.size());
        for (Map.Entry<String, Object> e : m.entrySet()) {
            buf.writeUtf(e.getKey());
            writeValue(buf, e.getValue());
        }
    }

    private static Map<String, Object> readMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < n && i < LIST_CAP; i++) {
            out.put(buf.readUtf(), readValue(buf));
        }
        return out;
    }

    // ---------------- 包 ----------------

    public static class ConfigStatePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ConfigStatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "config_state"));

        public final boolean forced;
        public final boolean amOp;
        public final boolean multiplayer;
        public final Map<String, Object> values;

        public ConfigStatePacket(boolean forced, boolean amOp, boolean multiplayer, Map<String, Object> values) {
            this.forced = forced;
            this.amOp = amOp;
            this.multiplayer = multiplayer;
            this.values = values == null ? new LinkedHashMap<>() : values;
        }

        public static void encode(ConfigStatePacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.forced);
            buf.writeBoolean(pkt.amOp);
            buf.writeBoolean(pkt.multiplayer);
            writeMap(buf, pkt.values);
        }

        public static ConfigStatePacket decode(FriendlyByteBuf buf) {
            return new ConfigStatePacket(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), readMap(buf));
        }

        public static void handle(ConfigStatePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> onState(pkt));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static class SetForcedPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SetForcedPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "config_set_forced"));

        public final boolean on;

        public SetForcedPacket(boolean on) {
            this.on = on;
        }

        public static void encode(SetForcedPacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.on);
        }

        public static SetForcedPacket decode(FriendlyByteBuf buf) {
            return new SetForcedPacket(buf.readBoolean());
        }

        public static void handle(SetForcedPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) {
                    return;
                }
                if (!sp.hasPermissions(2)) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c「配置以服务端为准」是全局设置，只有 OP 能改。"));
                    return;
                }
                try {
                    MaidSmartConfig.MISC_SERVER_FORCED_CONFIG.set(pkt.on);
                    persistAndRefresh();
                } catch (Throwable t) {
                    PromaidLog.log("配置同步", "切换「配置以服务端为准」失败：" + t);
                }
                broadcast(sp.level().getServer());
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static class SetValuesPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SetValuesPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "config_set_values"));

        public final Map<String, Object> values;

        public SetValuesPacket(Map<String, Object> values) {
            this.values = values == null ? new LinkedHashMap<>() : values;
        }

        public static void encode(SetValuesPacket pkt, FriendlyByteBuf buf) {
            writeMap(buf, pkt.values);
        }

        public static SetValuesPacket decode(FriendlyByteBuf buf) {
            return new SetValuesPacket(readMap(buf));
        }

        public static void handle(SetValuesPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) {
                    return;
                }
                if (!sp.hasPermissions(2)) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c本服配置以服务端为准，只有 OP 能改。"));
                    return;
                }
                applyFromClient(sp, pkt.values, "面板改配置");
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static class ApplyPresetPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ApplyPresetPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "config_apply_preset"));

        public final boolean diy;

        public ApplyPresetPacket(boolean diy) {
            this.diy = diy;
        }

        public static void encode(ApplyPresetPacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.diy);
        }

        public static ApplyPresetPacket decode(FriendlyByteBuf buf) {
            return new ApplyPresetPacket(buf.readBoolean());
        }

        public static void handle(ApplyPresetPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer sp)) {
                    return;
                }
                if (!sp.hasPermissions(2)) {
                    sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c本服配置以服务端为准，只有 OP 能改。"));
                    return;
                }
                int changed;
                try {
                    changed = pkt.diy ? MaidConfigPreset.applyDiy() : MaidConfigPreset.applyFactoryDefault();
                } catch (Throwable t) {
                    PromaidLog.log("配置同步", "应用预设失败：" + t);
                    return;
                }
                broadcast(sp.level().getServer());
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7a[身怀百技] 全服预设已" + (pkt.diy ? "切到 DIY 模式" : "恢复默认模式")
                                + "（改动 " + changed + " 项）"));
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
