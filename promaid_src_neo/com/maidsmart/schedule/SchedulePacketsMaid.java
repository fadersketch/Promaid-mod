package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
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
 * 排班表网络包——女仆个体操作（v1.2.4 从 ScheduleNetworking 拆出）。
 * 
 * 召唤/改名/坐标查询与回传/传送到指定点/工位标记。
 * 原为 ScheduleNetworking 的嵌套类，搬出后外部引用已全树改写。编解码逐字未动。
 */
public final class SchedulePacketsMaid {
    private SchedulePacketsMaid() {
    }

    public static class MarkWorkPosPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MarkWorkPosPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "mark_work_pos"));
        public final int x;
        public final int y;
        public final int z;

        public MarkWorkPosPacket(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static void encode(MarkWorkPosPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
        }

        public static MarkWorkPosPacket decode(FriendlyByteBuf buf) {
            return new MarkWorkPosPacket(buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(MarkWorkPosPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (!(ctx.player() instanceof ServerPlayer player)) {
                    return;
                }
                if (!(player.level() instanceof ServerLevel level)) {
                    return;
                }
                // 标记点距玩家 ≤64 格（客户端射线 8 格，这里防御恶意远标）
                double dSq = player.distanceToSqr(pkt.x + 0.5, pkt.y + 0.5, pkt.z + 0.5);
                if (dSq > 64.0 * 64.0) {
                    return;
                }
                BlockPos pos = new BlockPos(pkt.x, pkt.y, pkt.z);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        player.getX() - 32, player.getY() - 32, player.getZ() - 32,
                        player.getX() + 32, player.getY() + 32, player.getZ() + 32);
                int n = 0;
                for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                        net.minecraft.world.entity.Entity.class, box)) {
                    if (!(e instanceof EntityMaid maid)
                            || maid.getOwner() != player
                            || !maid.isHomeModeEnable()) {
                        continue;
                    }
                    var sp = maid.getSchedulePos();
                    if (sp == null) {
                        continue;
                    }
                    sp.setWorkPos(pos);
                    sp.setIdlePos(pos);
                    sp.setConfigured(true);
                    sp.restrictTo(maid);
                    n++;
                }
                String at = "(" + pkt.x + ", " + pkt.y + ", " + pkt.z + ")";
                if (n > 0) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§a已把 " + n + " 名女仆的工作区域标到 " + at
                                    + "——她们的任务选点/散步/巡逻都会收进这里（半径=「排班活动半径」）"));
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7附近 32 格内没有在家模式（不跟随/排班中）的女仆，标记没生效"));
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class SummonPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SummonPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "summon"));

        public SummonPacket() {
        }

        public static void encode(SummonPacket pkt, FriendlyByteBuf buf) {
        }

        public static SummonPacket decode(FriendlyByteBuf buf) {
            return new SummonPacket();
        }

        public static void handle(SummonPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    return;
                }
                // v1.1.0 实测七十（反馈：一键集合跨维度做不到）：根因是未加载
                // 区块里的女仆根本不在实体列表里（票务只救"还加载着"的）。改为：
                // ① 在场女仆立即传回（坐着/骑乘/在家模式豁免——实测七十八起 home
                //    女仆恢复不响应集合，想召回先解除她的排班/在家模式）；② 不在场的
                // 按最后出现位置强载区块进待召回队列，实体一出现自动传回并回报
                var r = com.maidsmart.follow.MaidChunkLoadManager.summonAll(player);
                java.util.List<String> parts = new ArrayList<>();
                if (r.summoned() > 0) {
                    parts.add("§a已集合 " + r.summoned() + " 名女仆到身边");
                }
                if (r.pending() > 0) {
                    parts.add("§e另有 " + r.pending()
                            + " 名不在已加载区块——正在按最后出现位置强载区块并自动召回（稍候几秒，无需再点）");
                }
                if (r.failStand() > 0) {
                    // v1.2.0 实测五百四十六：集合已改强制 + 无视地块，这条不再是"无可站立点"
                    parts.add("§7" + r.failStand() + " 名传送失败（状态异常，稍后再试）");
                }
                if (r.kept() > 0) {
                    parts.add("§7" + r.kept() + " 名坐着/骑乘/在家模式保持原位");
                }
                if (parts.isEmpty()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7没有需要集合的女仆（都在身边或不在场上）"));
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            String.join("§r；", parts)));
                }
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidSummonPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidSummonPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_summon"));
        public final String uuid;

        public MaidSummonPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidSummonPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
        }

        public static MaidSummonPacket decode(FriendlyByteBuf buf) {
            try {
                return new MaidSummonPacket(buf.readUtf(64));
            } catch (Exception e) {
                return new MaidSummonPacket("");
            }
        }

        public static void handle(MaidSummonPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                int r = com.maidsmart.follow.MaidChunkLoadManager.summonOne(player, pkt.uuid);
                String msg;
                if (r == 1) {
                    msg = "§a已将女仆传送到你身边";
                } else if (r == 2) {
                    // v1.2.0 实测五百四十六：人工传送已改**强制 + 无视地块**（找不到可站立格
                    // 就直接落在你所在的位置，空中也行），所以这条"你身边无可站立点"的
                    // 旧拒绝路径不再存在；走到这里只剩状态异常（维度不可达/实体异常）。
                    msg = "§7女仆没有传送：她当前状态异常，稍后再试一次";
                } else if (r == 3) {
                    msg = "§7她坐着/骑乘/在家模式（排班中）保持原位——想强制召回先关闭排班/解除坐姿";
                } else {
                    msg = "§7没找到她——不在已加载区块/不是你的女仆（试试列表页「⚑ 一键集合」，会自动强载区块召回）";
                }
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class RenameMaidPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RenameMaidPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "rename_maid"));
        public final String uuid;
        public final String name;

        public RenameMaidPacket(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public static void encode(RenameMaidPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
            buf.writeUtf(pkt.name == null ? "" : pkt.name, 64);
        }

        public static RenameMaidPacket decode(FriendlyByteBuf buf) {
            return new RenameMaidPacket(buf.readUtf(64), buf.readUtf(64));
        }

        public static void handle(RenameMaidPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    return;
                }
                // 跨维度找女仆（列表是全维度的，改名目标可能不在玩家维度）
                EntityMaid maid = null;
                for (ServerLevel lvl : player.level().getServer().getAllLevels()) {
                    EntityMaid m = ScheduleNetworking.findMaid(lvl, pkt.uuid);
                    if (m != null && m.isOwnedBy(player)) {
                        maid = m;
                        break;
                    }
                }
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    return;
                }
                String n = pkt.name == null ? "" : pkt.name.replace("§", "").trim();
                if (n.isEmpty()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c名字不能为空"));
                    return;
                }
                if (n.length() > 30) {
                    n = n.substring(0, 30);
                }
                maid.setCustomName(net.minecraft.network.chat.Component.literal(n));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§a已改名为「" + n + "」"));
                // v1.1.0 实测三百四十九：改名成功 → 回发新名字，排班表 GUI 立即
                // 同步列表行与详情页标题（客户端 maids 快照是打开那一刻的旧名字）
                PacketDistributor.sendToPlayer(player, new MaidRenameSyncPacket(maid.getUUID().toString(), n));
            });
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidRenameSyncPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidRenameSyncPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_rename_sync"));
        public final String uuid;
        public final String name;

        public MaidRenameSyncPacket(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public static void encode(MaidRenameSyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
            buf.writeUtf(pkt.name == null ? "" : pkt.name, 64);
        }

        public static MaidRenameSyncPacket decode(FriendlyByteBuf buf) {
            return new MaidRenameSyncPacket(buf.readUtf(64), buf.readUtf(64));
        }

        public static void handle(MaidRenameSyncPacket pkt, IPayloadContext ctx) {
            // S2C 方向校验（同 MaidStateSyncPacket——实测十六审查 P2-4 口径）
            if (ctx.flow() != PacketFlow.CLIENTBOUND) {
                
                return;
            }
            ctx.enqueueWork(() ->
                    com.maidsmart.schedule.ScheduleBookScreen.syncMaidName(pkt.uuid, pkt.name));
            
        }
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidCoordRequestPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidCoordRequestPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_coord_request"));
        public final String uuid;

        public MaidCoordRequestPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidCoordRequestPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
        }

        public static MaidCoordRequestPacket decode(FriendlyByteBuf buf) {
            return new MaidCoordRequestPacket(buf.readUtf(64));
        }

        public static void handle(MaidCoordRequestPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    // 不在了（被收进魂符/换维度加载不到）——回一个空标记让界面停止刷新
                    PacketDistributor.sendToPlayer(player,
                            new MaidCoordPacket(pkt.uuid, true, "", 0, 0, 0));
                    return;
                }
                BlockPos p = maid.blockPosition();
                PacketDistributor.sendToPlayer(player,
                        new MaidCoordPacket(pkt.uuid, false, ScheduleNetworking.dimName(maid.level()),
                                p.getX(), p.getY(), p.getZ()));
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidCoordPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidCoordPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_coord"));
        public final String uuid;
        public final boolean gone;
        public final String dim;
        public final int x;
        public final int y;
        public final int z;

        public MaidCoordPacket(String uuid, boolean gone, String dim, int x, int y, int z) {
            this.uuid = uuid;
            this.gone = gone;
            this.dim = dim == null ? "" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static void encode(MaidCoordPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
            buf.writeBoolean(pkt.gone);
            buf.writeUtf(pkt.dim, 64);
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
        }

        public static MaidCoordPacket decode(FriendlyByteBuf buf) {
            return new MaidCoordPacket(buf.readUtf(64), buf.readBoolean(), buf.readUtf(64),
                    buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(MaidCoordPacket pkt, IPayloadContext ctx) {
            // S2C 方向校验（同 MaidRenameSyncPacket——实测十六审查 P2-4 口径）
            if (ctx.flow() != PacketFlow.CLIENTBOUND) {
                return;
            }
            ctx.enqueueWork(() -> com.maidsmart.schedule.ScheduleBookScreen
                    .onCoord(pkt.uuid, pkt.gone, pkt.dim, pkt.x, pkt.y, pkt.z));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static class MaidTeleportToPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MaidTeleportToPacket> TYPE = new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "maid_teleport_to"));
        public final String uuid;

        public MaidTeleportToPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidTeleportToPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
        }

        public static MaidTeleportToPacket decode(FriendlyByteBuf buf) {
            return new MaidTeleportToPacket(buf.readUtf(64));
        }

        public static void handle(MaidTeleportToPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid((ServerLevel) player.level(), pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7没找到她——可能已被收进魂符或不在已加载区块"));
                    return;
                }
                if (maid.level() instanceof ServerLevel target
                        && !target.dimension().equals(player.level().dimension())) {
                    // 跨维度：目的地用她脚下的安全落点（findStand 的判定口径与召回一致）
                    String nm = maid.getDisplayName() != null ? maid.getDisplayName().getString() : "女仆";
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§e她不在你这个维度（在" + ScheduleNetworking.dimName(maid.level()) + "）——正在把你送过去"));
                    BlockPos stand = com.maidsmart.follow.MaidChunkLoadManager
                            .findStandNear(target, maid.blockPosition());
                    if (stand == null) {
                        // v1.2.0 实测五百四十六【强制 + 无视地块】：旧版这里直接取消传送
                        //（"她脚下没找到可站立的位置"）——空袭女仆悬停/飞在海上或虚空上时
                        // 就永远传不过去。现在退到**她自己所在的那一格**（同维度分支早就
                        // 是这么兜底的，跨维度漏了）；她那格若是空中，就把你放到空中。
                        stand = maid.blockPosition();
                    }
                    boolean ok = player.teleportTo(target, stand.getX() + 0.5,
                            stand.getY(), stand.getZ() + 0.5,
                            java.util.Collections.emptySet(), player.getYRot(), player.getXRot());
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(ok
                            ? "§a已传送到「" + nm + "」身边（" + ScheduleNetworking.dimName(target) + "）"
                            : "§c传送失败"));
                    return;
                }
                // 同维度：她脚下找落点；找不到就退到她自己站的那一格（她站的地方总归能站人）
                BlockPos stand = com.maidsmart.follow.MaidChunkLoadManager
                        .findStandNear((ServerLevel) player.level(), maid.blockPosition());
                if (stand == null) {
                    stand = maid.blockPosition();
                }
                player.teleportTo((ServerLevel) player.level(), stand.getX() + 0.5,
                        stand.getY(), stand.getZ() + 0.5,
                        java.util.Collections.emptySet(), player.getYRot(), player.getXRot());
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§a已传送到她身边（" + stand.getX() + " " + stand.getY()
                                + " " + stand.getZ() + "）"));
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * v1.3.0 实测六百五十五：快捷设置页的「女仆配置」按钮——**直接打开她的原版女仆界面**
     * （与玩家右键女仆同款：背包 / 外观 / 行为设置，即 {@code openMaidGui} 的 index 0 那页）。
     *
     * 【为什么需要这个包】排班表界面挡着，右键点不到女仆；而她把扫帚骑上以后，玩家右键
     * 是"上/下扫帚"、同样打不开界面——两条路都断了。"想给骑在扫帚上的她换件武器/看眼背包"
     * 就成了死路，所以由服务端替玩家开一次。
     *
     * 【为什么必须服务端开】{@code EntityMaid.openMaidGui} 内部只对 {@code ServerPlayer} 生效
     * （字节码实证：走 {@code NetworkHooks.openScreen(serverPlayer, provider, buf)}），
     * 客户端单方面调用没有任何效果——所以这一发必须是 C2S。
     */
    public static class OpenMaidConfigPacket implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenMaidConfigPacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("maid_smart", "open_maid_config"));
        public final String uuid;

        public OpenMaidConfigPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(OpenMaidConfigPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.uuid, 64);
        }

        public static OpenMaidConfigPacket decode(FriendlyByteBuf buf) {
            return new OpenMaidConfigPacket(buf.readUtf(64));
        }

        public static void handle(OpenMaidConfigPacket pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player == null) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid((ServerLevel) player.level(), pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7没找到她——可能已被收进魂符或不在已加载区块"));
                    return;
                }
                // v1.3.4【真正的远程开界面】（实测六百五十九，玩家原话："我想要真正的远程开界面"）
                // 上一版（实测六百五十八）在这里做的是"离得远就**不开**、回一句明白话"——那是止血，
                // 不是玩家要的东西。真正的根因是 TLM 的 AbstractMaidContainer.m_6875_（= stillValid）
                // 每 tick 都要问一遍「玩家到她 ≤ 3 格」（字节码实证），人一站远服务端下一 tick 就
                // closeContainer，客户端看到的就是"点进去一下、立刻闪退"。
                // 现在改成**真的开**：先把这一对（玩家, 她）登记进 RemoteMaidGui，容器那条距离判据
                // 由 MaidContainerRemoteOpenMixin 对这一对放行；界面关掉时授权自动收回。
                String blocked = openBlockedReason(player, maid);
                if (blocked != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(blocked));
                    return;
                }
                RemoteMaidGui.enable(player, maid);
                // 与右键女仆同一个入口。她在别的维度、骑着扫帚、正在干活都照开
                //（界面是玩家的，关掉之后她照旧在原地干自己的事）
                if (!maid.openMaidGui(player)) {
                    // 没开成：把刚登记的授权收回，别留一份"她那边没有界面、我们这边还认这个远程"的悬空登记
                    RemoteMaidGui.disable(player, maid);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§7没能打开「" + (maid.getDisplayName() == null
                                    ? "女仆" : maid.getDisplayName().getString()) + "」的配置界面"));
                }
            });
        }

        /**
         * 这次开她的配置界面会不会"开了就关"——会的话返回给玩家看的那句话，不会则 null。
         *
         * <p>判据的顺序照抄 {@code AbstractMaidContainer.m_6875_}（TLM 的 stillValid）：
         * 容器那边是"越靠前的条件先否掉"，我们按同一个顺序给理由，玩家看到的就是真正的那个原因。
         * 仍是本模组那条铁律的又一次应用：同一个口径只有一处实现——这里是**客户端表现**那一侧的
         * 同一份口径，改了 TLM 的判据我们也得跟着改，所以注释里把出处写死。
         *
         * <p>【v1.3.4 实测六百五十九：距离那一条不在这里拦了】玩家原话"我想要真正的远程开界面"。
         * 「离她 ≤ 3 格」那一条改由 {@code MaidContainerRemoteOpenMixin} 对**登记过的**这一对
         * （玩家, 她）整条放行——那是 TLM 每 tick 关界面的唯一原因。留在这里的只剩两项：
         * <ol>
         *   <li>她死了 / 正在睡——这两条是 stillValid 里"距离之前"的条件，**照旧拦**
         *       （不然服务端下一 tick 还是关，玩家只会看到闪退而不是原因）；</li>
         *   <li>**客户端根本看不到她**——TLM 的容器是按实体的网络 id 在客户端找她的
         *       （字节码实证：{@code AbstractMaidContainer} 构造里
         *       {@code level.getEntity(int)} + {@code checkcast EntityMaid}），而"玩家背包那一片
         *       槽位"只在 {@code maid != null} 时才建——客户端没同步到这个实体时打开的是残界面。
         *       边界取**原版自己的实体同步距离**（{@link RemoteMaidGui#remoteRange}），不是我们拍一个数。</li>
         * </ol>
         */
        private static String openBlockedReason(ServerPlayer player, EntityMaid maid) {
            if (!maid.isAlive()) {
                return "§7她已经不在了（被收回 / 已死亡），打不开配置界面";
            }
            if (maid.isSleeping()) {
                return "§7她正在睡觉——醒了再开配置界面（睡觉中开会被原版立刻关掉）";
            }
            double far = RemoteMaidGui.remoteRange(maid);
            if (player.distanceTo(maid) > far) {
                return "§7她离你 " + String.format(java.util.Locale.ROOT, "%.1f", player.distanceTo(maid))
                        + " 格——超出客户端能看到她的距离（约 " + ((int) far)
                        + " 格，原版的实体同步距离），那种距离界面打开也是残的。"
                        + "走近到 " + ((int) far) + " 格以内再点；只想让她过来就用「召她过来」/「去她身边」";
            }
            return null;
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
