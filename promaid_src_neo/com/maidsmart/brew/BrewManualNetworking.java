package com.maidsmart.brew;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;


/**
 * 女仆药剂手册网络层（v1.1.0 实测二百七十七）——手册右键女仆打开的配置 GUI 全走这里。
 *
 * 包清单：
 * - 0 OpenBrewManualPacket（S2C）：女仆 UUID + 当前配置 → 客户端开屏
 * - 1 SaveBrewConfigPacket（C2S）：保存配置（服务端校验归属/药水存在 → 写 NBT）
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class BrewManualNetworking {
    
    
    private BrewManualNetworking() {
    }

        @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenBrewManualPacket.TYPE, StreamCodec.ofMember(OpenBrewManualPacket::encode, OpenBrewManualPacket::decode), OpenBrewManualPacket::handle);
        r.playToServer(SaveBrewConfigPacket.TYPE, StreamCodec.ofMember(SaveBrewConfigPacket::encode, SaveBrewConfigPacket::decode), SaveBrewConfigPacket::handle);
    }

    /* ==================== 打开 UI ==================== */

    /** 服务端：给玩家发打开包（手册右键女仆调用） */
    public static void openFor(ServerPlayer player, EntityMaid maid) {
        try {
            BrewConfig cfg = BrewConfig.load(maid);
            PacketDistributor.sendToPlayer(player, new OpenBrewManualPacket(maid.getUUID().toString(), cfg));
        } catch (Throwable ignored) {
        }
    }

    /** S2C 打开包：女仆 UUID + 当前配置 */
    public static class OpenBrewManualPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenBrewManualPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_brew_manual"));
        public final String uuid;
        public final BrewConfig cfg;

        public OpenBrewManualPacket(String uuid, BrewConfig cfg) {
            this.uuid = uuid;
            this.cfg = cfg;
        }

        public static void encode(OpenBrewManualPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
            buf.writeInt(pkt.cfg.mode);
            buf.writeInt(pkt.cfg.enhance);
            buf.writeInt(pkt.cfg.form);
            buf.writeUtf(pkt.cfg.targetPotion == null ? "" : pkt.cfg.targetPotion, 256);
        }

        public static OpenBrewManualPacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf(64);
            BrewConfig cfg = new BrewConfig();
            cfg.mode = buf.readInt();
            cfg.enhance = buf.readInt();
            cfg.form = buf.readInt();
            cfg.targetPotion = buf.readUtf(256);
            return new OpenBrewManualPacket(uuid, cfg);
        }

        public static void handle(OpenBrewManualPacket pkt, IPayloadContext ctx) {
            // S2C 方向校验（同排班表 OpenSchedulePacket）——恶意客户端把 S2C 包发往
            // 服务端会加载客户端 Screen 类 → 专用服 NoClassDefFoundError
            if (ctx.flow() != PacketFlow.CLIENTBOUND) {
                
                return;
            }
            ctx.enqueueWork(() ->
                    com.maidsmart.brew.BrewManualScreen.open(pkt.uuid, pkt.cfg));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /* ==================== 保存配置 ==================== */

    /** C2S 保存配置 */
    public static class SaveBrewConfigPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SaveBrewConfigPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "save_brew_config"));
        public final String uuid;
        public final BrewConfig cfg;

        public SaveBrewConfigPacket(String uuid, BrewConfig cfg) {
            this.uuid = uuid;
            this.cfg = cfg;
        }

        public static void encode(SaveBrewConfigPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
            buf.writeInt(pkt.cfg.mode);
            buf.writeInt(pkt.cfg.enhance);
            buf.writeInt(pkt.cfg.form);
            buf.writeUtf(pkt.cfg.targetPotion == null ? "" : pkt.cfg.targetPotion, 256);
        }

        public static SaveBrewConfigPacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf(64);
            BrewConfig cfg = new BrewConfig();
            cfg.mode = buf.readInt();
            cfg.enhance = buf.readInt();
            cfg.form = buf.readInt();
            cfg.targetPotion = buf.readUtf(256);
            return new SaveBrewConfigPacket(uuid, cfg);
        }

        public static void handle(SaveBrewConfigPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof ServerLevel level)) {
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
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
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
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /* ==================== 工具 ==================== */

    /** 按 UUID 找女仆（跨维度，与排班表口径一致） */
    static EntityMaid findMaid(ServerLevel level, String uuid) {
        try {
            java.util.UUID id = java.util.UUID.fromString(uuid);
            for (ServerLevel lvl : level.getServer().getAllLevels()) {
                EntityMaid m = (EntityMaid) lvl.getEntity(id);
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
        return maid.isOwnedBy(player) || player.hasPermissions(2);
    }

    private static int clamp(int v, int min, int max, int def) {
        return v >= min && v <= max ? v : def;
    }
}
