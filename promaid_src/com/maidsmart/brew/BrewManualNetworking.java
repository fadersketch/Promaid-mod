package com.maidsmart.brew;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 女仆药剂手册网络层（v1.1.0 实测二百七十七）——手册右键女仆打开的配置 GUI 全走这里。
 *
 * 包清单：
 * - 0 OpenBrewManualPacket（S2C）：女仆 UUID + 当前配置 → 客户端开屏
 * - 1 SaveBrewConfigPacket（C2S）：保存配置（服务端校验归属/药水存在 → 写 NBT）
 */
public final class BrewManualNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "brew_manual"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private BrewManualNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, OpenBrewManualPacket.class,
                OpenBrewManualPacket::encode, OpenBrewManualPacket::decode, OpenBrewManualPacket::handle);
        CHANNEL.registerMessage(1, SaveBrewConfigPacket.class,
                SaveBrewConfigPacket::encode, SaveBrewConfigPacket::decode, SaveBrewConfigPacket::handle);
    }

    /* ==================== 打开 UI ==================== */

    /** 服务端：给玩家发打开包（手册右键女仆调用） */
    public static void openFor(ServerPlayer player, EntityMaid maid) {
        try {
            BrewConfig cfg = BrewConfig.load(maid);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenBrewManualPacket(maid.m_20148_().toString(), cfg));
        } catch (Throwable ignored) {
        }
    }

    /** S2C 打开包：女仆 UUID + 当前配置 */
    public static class OpenBrewManualPacket {
        public final String uuid;
        public final BrewConfig cfg;

        public OpenBrewManualPacket(String uuid, BrewConfig cfg) {
            this.uuid = uuid;
            this.cfg = cfg;
        }

        public static void encode(OpenBrewManualPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeInt(pkt.cfg.mode);
            buf.writeInt(pkt.cfg.enhance);
            buf.writeInt(pkt.cfg.form);
            buf.m_130072_(pkt.cfg.targetPotion == null ? "" : pkt.cfg.targetPotion, 256);
        }

        public static OpenBrewManualPacket decode(FriendlyByteBuf buf) {
            String uuid = buf.m_130136_(64);
            BrewConfig cfg = new BrewConfig();
            cfg.mode = buf.readInt();
            cfg.enhance = buf.readInt();
            cfg.form = buf.readInt();
            cfg.targetPotion = buf.m_130136_(256);
            return new OpenBrewManualPacket(uuid, cfg);
        }

        public static void handle(OpenBrewManualPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // S2C 方向校验（同排班表 OpenSchedulePacket）——恶意客户端把 S2C 包发往
            // 服务端会加载客户端 Screen 类 → 专用服 NoClassDefFoundError
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.brew.BrewManualScreen.open(pkt.uuid, pkt.cfg));
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 保存配置 ==================== */

    /** C2S 保存配置 */
    public static class SaveBrewConfigPacket {
        public final String uuid;
        public final BrewConfig cfg;

        public SaveBrewConfigPacket(String uuid, BrewConfig cfg) {
            this.uuid = uuid;
            this.cfg = cfg;
        }

        public static void encode(SaveBrewConfigPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeInt(pkt.cfg.mode);
            buf.writeInt(pkt.cfg.enhance);
            buf.writeInt(pkt.cfg.form);
            buf.m_130072_(pkt.cfg.targetPotion == null ? "" : pkt.cfg.targetPotion, 256);
        }

        public static SaveBrewConfigPacket decode(FriendlyByteBuf buf) {
            String uuid = buf.m_130136_(64);
            BrewConfig cfg = new BrewConfig();
            cfg.mode = buf.readInt();
            cfg.enhance = buf.readInt();
            cfg.form = buf.readInt();
            cfg.targetPotion = buf.m_130136_(256);
            return new SaveBrewConfigPacket(uuid, cfg);
        }

        public static void handle(SaveBrewConfigPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    return;
                }
                // 防御：来自客户端的包不信任——夹取枚举值
                BrewConfig safe = new BrewConfig();
                safe.mode = clamp(pkt.cfg.mode, BrewConfig.MODE_BATCH, BrewConfig.MODE_TARGETED, BrewConfig.MODE_BATCH);
                safe.enhance = clamp(pkt.cfg.enhance, BrewConfig.ENHANCE_NONE, BrewConfig.ENHANCE_GLOWSTONE, BrewConfig.ENHANCE_NONE);
                safe.form = clamp(pkt.cfg.form, BrewConfig.FORM_DRINK, BrewConfig.FORM_LINGERING, BrewConfig.FORM_DRINK);
                safe.targetPotion = pkt.cfg.targetPotion == null ? "" : pkt.cfg.targetPotion;
                // 定向模式：目标药水必须真实存在，否则回退批量
                if (safe.mode == BrewConfig.MODE_TARGETED && !safe.hasValidTarget()) {
                    safe.mode = BrewConfig.MODE_BATCH;
                    safe.targetPotion = "";
                }
                BrewConfig.save(maid, safe);
                // v1.1.0 实测二百八十一：保存后系统提示（含模式/目标/形态摘要——
                // 玩家能确认"喷溅/滞留"真的存进去了，旧版保存无任何反馈）
                String modeCn = safe.mode == BrewConfig.MODE_BATCH ? "批量酿造" : "定向酿造";
                String enhanceCn = safe.enhance == BrewConfig.ENHANCE_REDSTONE ? "红石延长"
                        : safe.enhance == BrewConfig.ENHANCE_GLOWSTONE ? "萤石强化" : "无强化";
                String formCn = safe.form == BrewConfig.FORM_SPLASH ? "喷溅"
                        : safe.form == BrewConfig.FORM_LINGERING ? "滞留" : "饮用";
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "\u00a7a[酿造配置已保存]\u00a7f " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + "：" + modeCn
                                + (safe.mode == BrewConfig.MODE_BATCH
                                        ? " / 强化=" + enhanceCn
                                        : " / 目标=" + (safe.targetPotion.isEmpty() ? "无" : safe.targetPotion))
                                + " / 形态=" + formCn
                                + "。把链上材料放她背包即开工"));
                com.maidsmart.tool.PromaidLog.log("酿造",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 保存药剂手册配置：模式=" + (safe.mode == BrewConfig.MODE_BATCH ? "批量" : "定向")
                                + " 强化=" + safe.enhance + " 形态=" + safe.form
                                + " 目标=" + (safe.targetPotion.isEmpty() ? "无" : safe.targetPotion));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 工具 ==================== */

    /** 按 UUID 找女仆（跨维度，与排班表口径一致） */
    static EntityMaid findMaid(ServerLevel level, String uuid) {
        try {
            java.util.UUID id = java.util.UUID.fromString(uuid);
            for (ServerLevel lvl : level.m_7654_().m_129785_()) {
                EntityMaid m = (EntityMaid) lvl.m_8791_(id);
                if (m != null) {
                    return m;
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 权限：主人本人或 OP */
    static boolean allowed(ServerPlayer player, EntityMaid maid) {
        return maid.m_21830_(player) || player.m_20310_(2);
    }

    private static int clamp(int v, int min, int max, int def) {
        return v >= min && v <= max ? v : def;
    }
}
