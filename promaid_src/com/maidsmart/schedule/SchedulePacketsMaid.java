package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 排班表网络包——女仆个体操作（v1.2.4 从 ScheduleNetworking 拆出）。
 * 
 * 召唤/改名/坐标查询与回传/传送到指定点/工位标记。
 * 原为 ScheduleNetworking 的嵌套类，搬出后外部引用已全树改写。编解码逐字未动。
 */
public final class SchedulePacketsMaid {
    private SchedulePacketsMaid() {
    }

    public static class SummonPacket {

        public SummonPacket() {
        }

        public static void encode(SummonPacket pkt, FriendlyByteBuf buf) {
        }

        public static SummonPacket decode(FriendlyByteBuf buf) {
            return new SummonPacket();
        }

        public static void handle(SummonPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
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
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没有需要集合的女仆（都在身边或不在场上）"));
                } else {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            String.join("§r；", parts)));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MaidSummonPacket {
        public final String uuid;

        public MaidSummonPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidSummonPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
        }

        public static MaidSummonPacket decode(FriendlyByteBuf buf) {
            try {
                return new MaidSummonPacket(buf.m_130136_(64));
            } catch (Exception e) {
                return new MaidSummonPacket("");
            }
        }

        public static void handle(MaidSummonPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
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
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(msg));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class RenameMaidPacket {
        public final String uuid;
        public final String name;

        public RenameMaidPacket(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public static void encode(RenameMaidPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.m_130072_(pkt.name == null ? "" : pkt.name, 64);
        }

        public static RenameMaidPacket decode(FriendlyByteBuf buf) {
            return new RenameMaidPacket(buf.m_130136_(64), buf.m_130136_(64));
        }

        public static void handle(RenameMaidPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                // 跨维度找女仆（列表是全维度的，改名目标可能不在玩家维度）
                EntityMaid maid = null;
                for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                    EntityMaid m = ScheduleNetworking.findMaid(lvl, pkt.uuid);
                    if (m != null && m.m_21830_(player)) {
                        maid = m;
                        break;
                    }
                }
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    return;
                }
                String n = pkt.name == null ? "" : pkt.name.replace("§", "").trim();
                if (n.isEmpty()) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§c名字不能为空"));
                    return;
                }
                if (n.length() > 30) {
                    n = n.substring(0, 30);
                }
                maid.m_6593_(net.minecraft.network.chat.Component.m_237113_(n));
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a已改名为「" + n + "」"));
                // v1.1.0 实测三百四十九：改名成功 → 回发新名字，排班表 GUI 立即
                // 同步列表行与详情页标题（客户端 maids 快照是打开那一刻的旧名字）
                ScheduleNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new MaidRenameSyncPacket(maid.m_20148_().toString(), n));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MaidRenameSyncPacket {
        public final String uuid;
        public final String name;

        public MaidRenameSyncPacket(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public static void encode(MaidRenameSyncPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.m_130072_(pkt.name == null ? "" : pkt.name, 64);
        }

        public static MaidRenameSyncPacket decode(FriendlyByteBuf buf) {
            return new MaidRenameSyncPacket(buf.m_130136_(64), buf.m_130136_(64));
        }

        public static void handle(MaidRenameSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // S2C 方向校验（同 MaidStateSyncPacket——实测十六审查 P2-4 口径）
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.schedule.ScheduleBookScreen.syncMaidName(pkt.uuid, pkt.name));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MaidCoordRequestPacket {
        public final String uuid;

        public MaidCoordRequestPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidCoordRequestPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
        }

        public static MaidCoordRequestPacket decode(FriendlyByteBuf buf) {
            return new MaidCoordRequestPacket(buf.m_130136_(64));
        }

        public static void handle(MaidCoordRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    // 不在了（被收进魂符/换维度加载不到）——回一个空标记让界面停止刷新
                    ScheduleNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new MaidCoordPacket(pkt.uuid, true, "", 0, 0, 0));
                    return;
                }
                BlockPos p = maid.m_20183_();
                ScheduleNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new MaidCoordPacket(pkt.uuid, false, ScheduleNetworking.dimName(maid.m_9236_()),
                                p.m_123341_(), p.m_123342_(), p.m_123343_()));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MaidCoordPacket {
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
            buf.m_130072_(pkt.uuid, 64);
            buf.writeBoolean(pkt.gone);
            buf.m_130072_(pkt.dim, 64);
            buf.writeInt(pkt.x);
            buf.writeInt(pkt.y);
            buf.writeInt(pkt.z);
        }

        public static MaidCoordPacket decode(FriendlyByteBuf buf) {
            return new MaidCoordPacket(buf.m_130136_(64), buf.readBoolean(), buf.m_130136_(64),
                    buf.readInt(), buf.readInt(), buf.readInt());
        }

        public static void handle(MaidCoordPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() -> com.maidsmart.schedule.ScheduleBookScreen
                    .onCoord(pkt.uuid, pkt.gone, pkt.dim, pkt.x, pkt.y, pkt.z));
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MaidTeleportToPacket {
        public final String uuid;

        public MaidTeleportToPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(MaidTeleportToPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
        }

        public static MaidTeleportToPacket decode(FriendlyByteBuf buf) {
            return new MaidTeleportToPacket(buf.m_130136_(64));
        }

        public static void handle(MaidTeleportToPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid((ServerLevel) player.m_9236_(), pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没找到她——可能已被收进魂符或不在已加载区块"));
                    return;
                }
                if (maid.m_9236_() instanceof ServerLevel target && !target.m_46472_()
                        .equals(player.m_9236_().m_46472_())) {
                    // 跨维度：目的地用她脚下的安全落点（findStand 的判定口径与召回一致）
                    String nm = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§e她不在你这个维度（在" + ScheduleNetworking.dimName(maid.m_9236_()) + "）——正在把你送过去"));
                    BlockPos stand = com.maidsmart.follow.MaidChunkLoadManager
                            .findStandNear(target, maid.m_20183_());
                    if (stand == null) {
                        // v1.2.0 实测五百四十六【强制 + 无视地块】：旧版这里直接取消传送
                        //（"她脚下没找到可站立的位置"）——空袭女仆悬停/飞在海上或虚空上时
                        // 就永远传不过去。现在退到**她自己所在的那一格**（同维度分支早就
                        // 是这么兜底的，跨维度漏了）；她那格若是空中，就把你放到空中。
                        stand = maid.m_20183_();
                    }
                    boolean ok = player.m_264318_(target, stand.m_123341_() + 0.5,
                            stand.m_123342_(), stand.m_123343_() + 0.5,
                            java.util.Collections.emptySet(), player.m_146908_(), player.m_146909_());
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(ok
                            ? "§a已传送到「" + nm + "」身边（" + ScheduleNetworking.dimName(target) + "）"
                            : "§c传送失败"));
                    return;
                }
                // 同维度：她脚下找落点；找不到就退到她自己站的那一格（她站的地方总归能站人）
                BlockPos stand = com.maidsmart.follow.MaidChunkLoadManager
                        .findStandNear((ServerLevel) player.m_9236_(), maid.m_20183_());
                if (stand == null) {
                    stand = maid.m_20183_();
                }
                player.m_264318_((ServerLevel) player.m_9236_(), stand.m_123341_() + 0.5,
                        stand.m_123342_(), stand.m_123343_() + 0.5,
                        java.util.Collections.emptySet(), player.m_146908_(), player.m_146909_());
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a已传送到她身边（" + stand.m_123341_() + " " + stand.m_123342_()
                                + " " + stand.m_123343_() + "）"));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class MarkWorkPosPacket {
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

        public static void handle(MarkWorkPosPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                // 标记点距玩家 ≤64 格（客户端射线 8 格，这里防御恶意远标）
                double dSq = player.m_20275_(pkt.x + 0.5, pkt.y + 0.5, pkt.z + 0.5);
                if (dSq > 64.0 * 64.0) {
                    return;
                }
                BlockPos pos = new BlockPos(pkt.x, pkt.y, pkt.z);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        player.m_20185_() - 32, player.m_20186_() - 32, player.m_20189_() - 32,
                        player.m_20185_() + 32, player.m_20186_() + 32, player.m_20189_() + 32);
                int n = 0;
                for (net.minecraft.world.entity.Entity e : level.m_45976_(
                        net.minecraft.world.entity.Entity.class, box)) {
                    if (!(e instanceof EntityMaid maid)
                            || maid.m_269323_() != player
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
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a7a已把 " + n + " 名女仆的工作区域标到 " + at
                                    + "——她们的任务选点/散步/巡逻都会收进这里（半径=「排班活动半径」）"));
                } else {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "\u00a77附近 32 格内没有在家模式（不跟随/排班中）的女仆，标记没生效"));
                }
            });
            ctx.get().setPacketHandled(true);
        }
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
    public static class OpenMaidConfigPacket {
        public final String uuid;

        public OpenMaidConfigPacket(String uuid) {
            this.uuid = uuid == null ? "" : uuid;
        }

        public static void encode(OpenMaidConfigPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
        }

        public static OpenMaidConfigPacket decode(FriendlyByteBuf buf) {
            return new OpenMaidConfigPacket(buf.m_130136_(64));
        }

        public static void handle(OpenMaidConfigPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                EntityMaid maid = ScheduleNetworking.findMaid((ServerLevel) player.m_9236_(), pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没找到她——可能已被收进魂符或不在已加载区块"));
                    return;
                }
                // 与右键女仆同一个入口。她在别的维度、骑着扫帚、正在干活都照开
                //（界面是玩家的，关掉之后她照旧在原地干自己的事）
                if (!maid.openMaidGui(player)) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没能打开「" + (maid.m_5446_() == null
                                    ? "女仆" : maid.m_5446_().getString()) + "」的配置界面"));
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
