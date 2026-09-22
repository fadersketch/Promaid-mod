package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 蓝图卷轴——AI 记忆/调试/导入 相关的网络包（v1.2.4 从 BlueprintBookNetworking 拆出）。
 * 
 * 原先是 BlueprintBookNetworking 的嵌套类，搬出来成为顶层类；
 * 外部引用已全树改写为 BlueprintBookEntityPackets.X。编码/解码逻辑逐字未动。
 */
public final class BlueprintBookEntityPackets {
    private BlueprintBookEntityPackets() {
    }

    public static class AiMemoryTogglePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AiMemoryTogglePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "ai_memory_toggle"));
        public final String maidUuid;
        public final boolean enabled;

        public AiMemoryTogglePacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(AiMemoryTogglePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static AiMemoryTogglePacket decode(FriendlyByteBuf buf) {
            return new AiMemoryTogglePacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(AiMemoryTogglePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    // v1.5.251b：女仆不在加载范围（远处/瞬移卸载）——仍记录开关
                    // 到磁盘（isEnabled 磁盘优先），她加载后立即生效。
                    // 审计 P-1：离线/未加载女仆无法判断归属，只允许 OP 修改。
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c只有 OP 可以修改未加载女仆的记忆开关。"));
                        return;
                    }
                    com.maidsmart.memory.AiMemoryManager.setEnabledDiskOnly(
                            pkt.maidUuid, pkt.enabled, level);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a77\u5973\u4ec6\u4e0d\u5728\u9644\u8fd1\uff08\u672a\u52a0\u8f7d\uff09\uff0c"
                                    + "\u8bb0\u5fc6\u5f00\u5173\u5df2\u8bb0\u5f55\uff0c\u5979\u52a0\u8f7d\u540e\u751f\u6548\u3002"));
                    return;
                }
                // 权限：女仆主人 或 OP
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u5207\u6362\u8bb0\u5fc6\u5f00\u5173\u3002"));
                    return;
                }
                com.maidsmart.memory.AiMemoryManager.setEnabled(maid, pkt.enabled);
                // v1.5.226：广播开关状态给追踪该女仆的所有客户端（persistentData
                // 不同步客户端——不广播的话客户端显示会停留在旧状态）
                // v1.5.249：TRACKING_ENTITY 只发给"正在追踪该女仆"的玩家——玩家在
                // 女仆配置界面/离女仆远时收不到 → CLIENT_STATE 不更新 → 界面显示旧
                // 值"开"，玩家再点又发 false（日志实证 7 秒内 4 次 toggle(false)，
                // "关了又自己打开"的根因）。改为【操作玩家必达 + 追踪玩家】双发。
                // v1.5.251：附 soulId——客户端本地读记忆按灵魂目录路由
                final EntityMaid fMaid = maid;
                String soul = com.maidsmart.soul.SoulBindingService.getSoulId(maid);
                MemoryStateSyncPacket sync = new MemoryStateSyncPacket(pkt.maidUuid,
                        pkt.enabled, soul == null ? "" : soul);
                PacketDistributor.sendToPlayer(player, sync);
                PacketDistributor.sendToPlayersTrackingEntity(fMaid, sync);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (pkt.enabled ? "\u00a7a" : "\u00a77") + "\u3010AI \u8bb0\u5fc6\u3011"
                                + maid.getDisplayName().getString() + "\u5df2"
                                + (pkt.enabled ? "\u542f\u7528\uff08\u5bf9\u8bdd\u79ef\u7d2f\u540e\u81ea\u52a8\u63d0\u53d6\uff09"
                                : "\u5173\u95ed\uff08\u4e0d\u63d0\u53d6\u4e0d\u6ce8\u5165\uff09")));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MemoryStateSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryStateSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_state_sync"));
        public final String maidUuid;
        public final boolean enabled;
        /** v1.5.251：女仆灵魂 id（空 = 未绑定）——客户端缓存后，配置界面本地
         *  读记忆时按灵魂目录路由（全局共享存储，跨存档双向同步的显示侧） */
        public final String soulId;

        public MemoryStateSyncPacket(String maidUuid, boolean enabled) {
            this(maidUuid, enabled, "");
        }

        public MemoryStateSyncPacket(String maidUuid, boolean enabled, String soulId) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
            this.soulId = soulId == null ? "" : soulId;
        }

        public static void encode(MemoryStateSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
            buf.writeUtf(pkt.soulId);
        }

        public static MemoryStateSyncPacket decode(FriendlyByteBuf buf) {
            return new MemoryStateSyncPacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()), buf.readUtf());
        }

        public static void handle(MemoryStateSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                com.maidsmart.memory.AiMemoryManager.pushClientState(pkt.maidUuid, pkt.enabled);
                if (!pkt.soulId.isEmpty()) {
                    com.maidsmart.memory.AiMemoryManager.pushClientSoulId(pkt.maidUuid, pkt.soulId);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class AiLlmTogglePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AiLlmTogglePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "ai_llm_toggle"));
        public final String maidUuid;
        public final boolean enabled;

        public AiLlmTogglePacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(AiLlmTogglePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static AiLlmTogglePacket decode(FriendlyByteBuf buf) {
            return new AiLlmTogglePacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(AiLlmTogglePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    // 女仆不在加载范围——仍记录到磁盘（isEnabled 磁盘优先），加载后生效。
                    // 审计 P-1：离线/未加载女仆无法判断归属，只允许 OP 修改。
                    if (!player.hasPermissions(2)) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "\u00a7c只有 OP 可以修改未加载女仆的 LLM 开关。"));
                        return;
                    }
                    com.maidsmart.memory.LlmEnableManager.setEnabledDiskOnly(
                            pkt.maidUuid, pkt.enabled, level);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a77女仆不在附近（未加载），LLM 开关已记录，她加载后生效。"));
                    return;
                }
                // 权限：女仆主人 或 OP
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c只有女仆的主人或 OP 可以切换 LLM 开关。"));
                    return;
                }
                com.maidsmart.memory.LlmEnableManager.setEnabled(maid, pkt.enabled);
                // 广播开关状态（persistentData 不同步客户端——不广播则界面显示旧值）
                final EntityMaid fMaid = maid;
                LlmStateSyncPacket sync = new LlmStateSyncPacket(pkt.maidUuid, pkt.enabled);
                PacketDistributor.sendToPlayer(player, sync);
                PacketDistributor.sendToPlayersTrackingEntity(fMaid, sync);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (pkt.enabled ? "\u00a7a" : "\u00a77") + "\u3010大语言模型\u3011"
                                + maid.getDisplayName().getString() + "已"
                                + (pkt.enabled ? "启用（对话由 LLM 驱动）"
                                : "关闭（不发 LLM 请求，主动对话降级为固定文本）")));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class LlmStateSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LlmStateSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "llm_state_sync"));
        public final String maidUuid;
        public final boolean enabled;

        public LlmStateSyncPacket(String maidUuid, boolean enabled) {
            this.maidUuid = maidUuid;
            this.enabled = enabled;
        }

        public static void encode(LlmStateSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.enabled));
        }

        public static LlmStateSyncPacket decode(FriendlyByteBuf buf) {
            return new LlmStateSyncPacket(buf.readUtf(),
                    Boolean.parseBoolean(buf.readUtf()));
        }

        public static void handle(LlmStateSyncPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                com.maidsmart.memory.LlmEnableManager.pushClientState(pkt.maidUuid, pkt.enabled);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MemoryStateQueryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryStateQueryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_state_query"));
        public final String maidUuid;

        public MemoryStateQueryPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MemoryStateQueryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MemoryStateQueryPacket decode(FriendlyByteBuf buf) {
            return new MemoryStateQueryPacket(buf.readUtf());
        }

        public static void handle(MemoryStateQueryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    return;
                }
                // 审计优化7修复：状态查询补主人/OP 校验（旧版任何人可查任意女仆开关+灵魂id）
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    return;
                }
                String soul = com.maidsmart.soul.SoulBindingService.getSoulId(maid);
                PacketDistributor.sendToPlayer(player, new MemoryStateSyncPacket(pkt.maidUuid,
                                com.maidsmart.memory.AiMemoryManager.isEnabled(maid),
                                soul == null ? "" : soul));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class DeleteBlueprintPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DeleteBlueprintPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "delete_blueprint"));
        public final String blueprintId;

        public DeleteBlueprintPacket(String blueprintId) {
            this.blueprintId = blueprintId;
        }

        public static void encode(DeleteBlueprintPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.blueprintId);
        }

        public static DeleteBlueprintPacket decode(FriendlyByteBuf buf) {
            return new DeleteBlueprintPacket(buf.readUtf());
        }

        public static void handle(DeleteBlueprintPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                String id = pkt.blueprintId;
                if (id == null || id.isEmpty() || !id.startsWith("maid_smart_ext:")) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5185\u7f6e\u84dd\u56fe\u4e0d\u80fd\u5220\u9664\u3002"));
                    return;
                }
                // 审计M2修复：联机服务器上任意玩家可删外部蓝图（grief）——仅 OP；
                // 单机/局域网主机不受影响（同机同人）
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                if (BlueprintLib.deleteBlueprint(id)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5df2\u5220\u9664\u84dd\u56fe\u3002"));
                    // 重发完整目录（被删蓝图从手册消失）
                    BlueprintBookNetworking.sendCatalog(player);
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u5220\u9664\u5931\u8d25\uff1a\u84dd\u56fe\u4e0d\u5b58\u5728\u6216\u4e3a\u5185\u7f6e\u3002"));
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MemoryViewRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryViewRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_view_request"));
        public final String maidUuid;

        public MemoryViewRequestPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MemoryViewRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MemoryViewRequestPacket decode(FriendlyByteBuf buf) {
            return new MemoryViewRequestPacket(buf.readUtf());
        }

        public static void handle(MemoryViewRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u67e5\u770b\u8bb0\u5fc6\u3002"));
                    return;
                }
                PacketDistributor.sendToPlayer(player, new MemoryViewResponsePacket(pkt.maidUuid,
                                BlueprintBookNetworking.collectMemoryLines(maid, level)));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class ClearMemoryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClearMemoryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "clear_memory"));
        public final String maidUuid;

        public ClearMemoryPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(ClearMemoryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static ClearMemoryPacket decode(FriendlyByteBuf buf) {
            return new ClearMemoryPacket(buf.readUtf());
        }

        public static void handle(ClearMemoryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u64cd\u4f5c\u8bb0\u5fc6\u3002"));
                    return;
                }
                com.maidsmart.soul.SoulBindingService.storeFor(maid, level).clearAll();
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7a\u5df2\u6e05\u7a7a" + maid.getDisplayName().getString() + "\u7684\u5168\u90e8\u8bb0\u5fc6\u3002"));
                PacketDistributor.sendToPlayer(player, new MemoryViewResponsePacket(pkt.maidUuid, BlueprintBookNetworking.collectMemoryLines(maid, level)));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MemoryViewResponsePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MemoryViewResponsePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "memory_view_response"));
        public final String maidUuid;
        public final List<String> lines;

        public MemoryViewResponsePacket(String maidUuid, List<String> lines) {
            this.maidUuid = maidUuid;
            this.lines = lines;
        }

        public static void encode(MemoryViewResponsePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.lines == null ? 0 : pkt.lines.size()));
            if (pkt.lines != null) {
                for (String l : pkt.lines) {
                    buf.writeUtf(l);
                }
            }
        }

        public static MemoryViewResponsePacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf();
            int n = Integer.parseInt(buf.readUtf());
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(buf.readUtf());
            }
            return new MemoryViewResponsePacket(uuid, lines);
        }

        public static void handle(MemoryViewResponsePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.showMemoryLines(pkt.maidUuid, pkt.lines);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidDebugRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_request"));
        public final String maidUuid;

        public MaidDebugRequestPacket(String maidUuid) {
            this.maidUuid = maidUuid;
        }

        public static void encode(MaidDebugRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
        }

        public static MaidDebugRequestPacket decode(FriendlyByteBuf buf) {
            return new MaidDebugRequestPacket(buf.readUtf());
        }

        public static void handle(MaidDebugRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u627e\u4e0d\u5230\u8fd9\u53ea\u5973\u4ec6\u3002"));
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u67e5\u770b\u8c03\u8bd5\u9762\u677f\u3002"));
                    return;
                }
                BlueprintBookNetworking.sendDebugResponse(player, maid, level, pkt.maidUuid);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidDebugResponsePacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugResponsePacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_response"));
        public final String maidUuid;
        public final List<String> statusLines;
        public final List<String[]> rows;

        public MaidDebugResponsePacket(String maidUuid, List<String> statusLines, List<String[]> rows) {
            this.maidUuid = maidUuid;
            this.statusLines = statusLines;
            this.rows = rows;
        }

        public static void encode(MaidDebugResponsePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(String.valueOf(pkt.statusLines == null ? 0 : pkt.statusLines.size()));
            if (pkt.statusLines != null) {
                for (String l : pkt.statusLines) {
                    buf.writeUtf(l);
                }
            }
            buf.writeUtf(String.valueOf(pkt.rows == null ? 0 : pkt.rows.size()));
            if (pkt.rows != null) {
                for (String[] r : pkt.rows) {
                    buf.writeUtf(r[0]);
                    buf.writeUtf(r[1]);
                    buf.writeUtf(r[2]);
                    buf.writeUtf(r[3]);
                }
            }
        }

        public static MaidDebugResponsePacket decode(FriendlyByteBuf buf) {
            String uuid = buf.readUtf();
            int n = Integer.parseInt(buf.readUtf());
            List<String> status = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                status.add(buf.readUtf());
            }
            int m = Integer.parseInt(buf.readUtf());
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                rows.add(new String[]{buf.readUtf(), buf.readUtf(),
                        buf.readUtf(), buf.readUtf()});
            }
            return new MaidDebugResponsePacket(uuid, status, rows);
        }

        public static void handle(MaidDebugResponsePacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                net.minecraft.client.gui.screens.Screen cur = net.minecraft.client.Minecraft.getInstance().screen;
                if (cur instanceof BlueprintBookScreen s) {
                    s.showDebugSnapshot(pkt.maidUuid, pkt.statusLines, pkt.rows);
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidDebugActionPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidDebugActionPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_debug_action"));
        public final String maidUuid;
        public final String action;
        public final String target;

        public MaidDebugActionPacket(String maidUuid, String action, String target) {
            this.maidUuid = maidUuid;
            this.action = action;
            this.target = target == null ? "" : target;
        }

        public static void encode(MaidDebugActionPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.maidUuid);
            buf.writeUtf(pkt.action);
            buf.writeUtf(pkt.target);
        }

        public static MaidDebugActionPacket decode(FriendlyByteBuf buf) {
            return new MaidDebugActionPacket(buf.readUtf(), buf.readUtf(), buf.readUtf());
        }

        public static void handle(MaidDebugActionPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                    return;
                }
                EntityMaid maid = null;
                try {
                    maid = (EntityMaid) level.getEntity(java.util.UUID.fromString(pkt.maidUuid));
                } catch (IllegalArgumentException ignored) {
                }
                if (maid == null) {
                    return;
                }
                if (!maid.isOwnedBy(player) && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u53ea\u6709\u5973\u4ec6\u7684\u4e3b\u4eba\u6216 OP \u53ef\u4ee5\u8c03\u8bd5\u8bb0\u5fc6\u3002"));
                    return;
                }
                com.maidsmart.memory.AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
                boolean ok = true;
                switch (pkt.action) {
                    case "salience_up" -> {
                        int ns = store.adjustSalienceByHash(pkt.target, 1);
                        if (ns >= 0) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7a\u5df2\u5c06\u8be5\u8bb0\u5fc6\u91cd\u8981\u5ea6\u8c03\u4e3a " + ns + "\u3002"));
                        } else {
                            ok = false;
                        }
                    }
                    case "salience_down" -> {
                        int ns = store.adjustSalienceByHash(pkt.target, -1);
                        if (ns >= 0) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "\u00a7a\u5df2\u5c06\u8be5\u8bb0\u5fc6\u91cd\u8981\u5ea6\u8c03\u4e3a " + ns + "\u3002"));
                        } else {
                            ok = false;
                        }
                    }
                    case "delete" -> ok = store.markDeletedByHash(pkt.target);
                    case "restore" -> ok = store.restoreByHash(pkt.target);
                    case "reinforce" -> ok = store.reinforceByHash(pkt.target);
                    case "deactivate_rel" -> ok = store.deactivateRelationsByPredicate(pkt.target) > 0;
                    default -> ok = false;
                }
                if (!ok) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c\u8c03\u8bd5\u5931\u8d25\uff1a\u4e0d\u5b58\u5728\u6216\u5df2\u5904\u7406\u3002"));
                    return;
                }
                // 成功后回发最新快照（客户端即时刷新）
                BlueprintBookNetworking.sendDebugResponse(player, maid, level, pkt.maidUuid);
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class VoicePackImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VoicePackImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "voice_pack_import"));
        public final String path;

        public VoicePackImportPacket(String path) {
            this.path = path;
        }

        public static void encode(VoicePackImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static VoicePackImportPacket decode(FriendlyByteBuf buf) {
            return new VoicePackImportPacket(buf.readUtf());
        }

        public static void handle(VoicePackImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：联机服务器上路径来自客户端、不可信（任意文件读取）——
                // 仅 OP 可用（管理员凭服务端本地路径导入）；单机/局域网主机不受影响
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.voice.SystemVoicePack.importPack(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class WorldImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<WorldImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "world_import"));
        public final String path;

        public WorldImportPacket(String path) {
            this.path = path;
        }

        public static void encode(WorldImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static WorldImportPacket decode(FriendlyByteBuf buf) {
            return new WorldImportPacket(buf.readUtf());
        }

        public static void handle(WorldImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：同语音包导入——联机服务器仅 OP
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.build.BlueprintLib.importWorldFile(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class BuildImportPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BuildImportPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "build_import"));
        public final String path;

        public BuildImportPacket(String path) {
            this.path = path;
        }

        public static void encode(BuildImportPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.path);
        }

        public static BuildImportPacket decode(FriendlyByteBuf buf) {
            return new BuildImportPacket(buf.readUtf());
        }

        public static void handle(BuildImportPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || pkt.path == null || pkt.path.isBlank()) {
                    return;
                }
                // 审计S1修复：同语音包导入——联机服务器仅 OP
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "\u00a7c该操作仅限管理员（OP）使用。"));
                    return;
                }
                String result = com.maidsmart.build.BlueprintLib.importBuildFile(pkt.path);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        (result.startsWith("导入失败") ? "\u00a7c" : "\u00a7a") + result));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class VoicePackQueryPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VoicePackQueryPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "voice_pack_query"));
        public final String kind;

        public VoicePackQueryPacket(String kind) {
            this.kind = kind;
        }

        public static void encode(VoicePackQueryPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.kind);
        }

        public static VoicePackQueryPacket decode(FriendlyByteBuf buf) {
            return new VoicePackQueryPacket(buf.readUtf());
        }

        public static void handle(VoicePackQueryPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                if ("reload".equals(pkt.kind)) {
                    com.maidsmart.voice.SystemVoicePack.reload();
                }
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7e" + com.maidsmart.voice.SystemTTSManager.statusText()));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
