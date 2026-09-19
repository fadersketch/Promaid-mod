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
 * 排班表网络层（v1.1.0）——排班表物品（纸+墨囊合成）右键打开的 UI 全走这里。
 *
 * 包清单：
 * - 0 OpenSchedulePacket（S2C）：打开 UI——女仆列表 + 可选任务清单
 * - 1 QuickApplyPacket（C2S）：快捷设置——工作模式 / 任务 / 排班开关（-1 = 不改）
 * - 2 SchedLoadRequestPacket（C2S）：请求某女仆的日程数据
 * - 3 SchedDataPacket（S2C）：下发日程数据（详情页「日程设置」tab 用）
 * - 4 SchedSavePacket（C2S）：保存日程（归一化在客户端做，服务端再归一化一次防御）
 */
public final class ScheduleNetworking {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new net.minecraft.resources.ResourceLocation("maid_smart", "schedule_book"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private ScheduleNetworking() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, OpenSchedulePacket.class,
                OpenSchedulePacket::encode, OpenSchedulePacket::decode, OpenSchedulePacket::handle);
        CHANNEL.registerMessage(1, QuickApplyPacket.class,
                QuickApplyPacket::encode, QuickApplyPacket::decode, QuickApplyPacket::handle);
        CHANNEL.registerMessage(2, SchedLoadRequestPacket.class,
                SchedLoadRequestPacket::encode, SchedLoadRequestPacket::decode, SchedLoadRequestPacket::handle);
        CHANNEL.registerMessage(3, SchedDataPacket.class,
                SchedDataPacket::encode, SchedDataPacket::decode, SchedDataPacket::handle);
        CHANNEL.registerMessage(4, SchedSavePacket.class,
                SchedSavePacket::encode, SchedSavePacket::decode, SchedSavePacket::handle);
        // v1.1.0 实测六十（借鉴 Maid_Roster 军队管理）：批量应用 / 一键集合 / 改名
        CHANNEL.registerMessage(6, BatchApplyPacket.class,
                BatchApplyPacket::encode, BatchApplyPacket::decode, BatchApplyPacket::handle);
        CHANNEL.registerMessage(7, SummonPacket.class,
                SummonPacket::encode, SummonPacket::decode, SummonPacket::handle);
        CHANNEL.registerMessage(8, RenameMaidPacket.class,
                RenameMaidPacket::encode, RenameMaidPacket::decode, RenameMaidPacket::handle);
        // v1.1.0 实测一百九十一：排班守卫拒绝后的客户端重同步（TLM 面板本地 setTask
        // 改的是【客户端实体】——服务端拦截生效但客户端实体脱钩，面板永远显示"改
        // 成功"，观感=排班锁失效；守卫拒绝时主动发本包把客户端实体扳回服务端口径）
        CHANNEL.registerMessage(9, MaidTaskResyncPacket.class,
                MaidTaskResyncPacket::encode, MaidTaskResyncPacket::decode, MaidTaskResyncPacket::handle);
        CHANNEL.registerMessage(10, MaidSummonPacket.class,
                MaidSummonPacket::encode, MaidSummonPacket::decode, MaidSummonPacket::handle);
        // v1.1.0 实测二百六十八：排班生效 → 同步给打开排班书的主人（快捷设置页立即
        // 显示排班规定的模式/任务并锁定，不再停留在打开时的旧状态）
        CHANNEL.registerMessage(11, MaidStateSyncPacket.class,
                MaidStateSyncPacket::encode, MaidStateSyncPacket::decode, MaidStateSyncPacket::handle);
        // v1.1.0 实测三百四十二：排班表内单独调整女仆在家模式（不依赖排班开关——
        // 不开排班也能让女仆守家；排班开着时 home 由排班管理，按钮锁定）
        CHANNEL.registerMessage(12, HomeTogglePacket.class,
                HomeTogglePacket::encode, HomeTogglePacket::decode, HomeTogglePacket::handle);
        // v1.1.0 实测三百四十三：批量调整全部女仆在家模式（列表页「全员在家」按钮，
        // 与全员模式/批量任务同款——排班中的女仆跳过，home 由排班管理）
        CHANNEL.registerMessage(13, BatchHomePacket.class,
                BatchHomePacket::encode, BatchHomePacket::decode, BatchHomePacket::handle);
        // v1.1.0 实测三百四十九（反馈："在排班表内对女仆进行改名，但是在排班表内
        // 并没有显示出来，还是原来的名字"）：改名成功 → S2C 回发新名字，GUI 同步
        // 列表行与详情页标题（旧版只改服务端，客户端列表是打开排班表那一刻的快照）
        CHANNEL.registerMessage(14, MaidRenameSyncPacket.class,
                MaidRenameSyncPacket::encode, MaidRenameSyncPacket::decode, MaidRenameSyncPacket::handle);
        // v1.2.0：快捷设置页显示女仆当前坐标 + 「去她身边」——坐标由服务端回（客户端
        // 只拿到打开排班表那一刻的快照，女仆走动后就不准了，所以每秒问一次）
        CHANNEL.registerMessage(15, MaidCoordRequestPacket.class,
                MaidCoordRequestPacket::encode, MaidCoordRequestPacket::decode, MaidCoordRequestPacket::handle);
        CHANNEL.registerMessage(16, MaidCoordPacket.class,
                MaidCoordPacket::encode, MaidCoordPacket::decode, MaidCoordPacket::handle);
        CHANNEL.registerMessage(17, MaidTeleportToPacket.class,
                MaidTeleportToPacket::encode, MaidTeleportToPacket::decode, MaidTeleportToPacket::handle);
        // 实测五百六十二：潜行+中键 工位标记（把身边 home 女仆的工作区域锚点标过去）
        CHANNEL.registerMessage(18, MarkWorkPosPacket.class,
                MarkWorkPosPacket::encode, MarkWorkPosPacket::decode, MarkWorkPosPacket::handle);
    }

    /* ==================== 排班生效 → GUI 状态同步 ==================== */

    /** 服务端：把女仆当前真实状态（任务/模式/排班开关）推给其主人——排班段应用
     *  成功后调用，打开着排班书的玩家 GUI 立即更新为排班状态（快捷设置页同步+锁定） */
    public static void sendMaidStateSync(net.minecraft.server.level.ServerPlayer player, EntityMaid maid) {
        try {
            String taskUid = maid.getTask() == null ? "touhou_little_maid:idle"
                    : maid.getTask().getUid().toString();
            int sched = maid.getSchedule() == null ? 2 : maid.getSchedule().ordinal();
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new MaidStateSyncPacket(maid.m_20148_().toString(), taskUid, sched,
                            ScheduleData.isOn(maid)));
        } catch (Throwable ignored) {
        }
    }

    public static class MaidStateSyncPacket {
        private final String uuid;
        private final String taskUid;
        private final int scheduleOrdinal;
        private final boolean schedOn;

        public MaidStateSyncPacket(String uuid, String taskUid, int scheduleOrdinal, boolean schedOn) {
            this.uuid = uuid;
            this.taskUid = taskUid;
            this.scheduleOrdinal = scheduleOrdinal;
            this.schedOn = schedOn;
        }

        public static void encode(MaidStateSyncPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.m_130072_(pkt.taskUid, 256);
            buf.writeInt(pkt.scheduleOrdinal);
            buf.writeBoolean(pkt.schedOn);
        }

        public static MaidStateSyncPacket decode(FriendlyByteBuf buf) {
            return new MaidStateSyncPacket(buf.m_130136_(64), buf.m_130136_(256),
                    buf.readInt(), buf.readBoolean());
        }

        public static void handle(MaidStateSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // v1.1.0 实测十六（审查 P2-4）：S2C 方向校验（同 OpenSchedulePacket）
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.schedule.ScheduleBookScreen.syncMaidState(
                            pkt.uuid, pkt.taskUid, pkt.scheduleOrdinal, pkt.schedOn));
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 排班表内单独调整在家模式（实测三百四十二） ==================== */

    /** C2S 在家模式开关：不依赖排班开关——不开排班也能让女仆守家（home 模式）；
     *  排班开着时 home 由排班管理（开排班自动 home），按钮锁定由客户端控制，
     *  服务端同样兜底拦截。 */
    public static class HomeTogglePacket {
        public final String uuid;
        public final boolean on;

        public HomeTogglePacket(String uuid, boolean on) {
            this.uuid = uuid;
            this.on = on;
        }

        public static void encode(HomeTogglePacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeBoolean(pkt.on);
        }

        public static HomeTogglePacket decode(FriendlyByteBuf buf) {
            return new HomeTogglePacket(buf.m_130136_(64), buf.readBoolean());
        }

        public static void handle(HomeTogglePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    return;
                }
                // 排班开着时 home 由排班管理（开排班自动 home、关排班解除）——手动改无效
                if (ScheduleData.isOn(maid)) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§c【排班】「" + (maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆")
                                    + "」的日程表开着，在家模式由排班管理——请先关闭她的排班再单独调整"));
                    return;
                }
                maid.setHomeModeEnable(pkt.on);
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        (pkt.on ? "§a已开启" : "§7已关闭") + "「"
                                + (maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆")
                                + "」的在家模式" + (pkt.on ? "——她将守家不跟随，想召回先关闭" : "")));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S 批量在家模式（实测三百四十三）：作用于主人全部已加载女仆（跨维度扫描，
     *  与批量应用同口径）；排班中的女仆跳过（home 由排班管理）。 */
    public static class BatchHomePacket {
        public final boolean on;

        public BatchHomePacket(boolean on) {
            this.on = on;
        }

        public static void encode(BatchHomePacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.on);
        }

        public static BatchHomePacket decode(FriendlyByteBuf buf) {
            return new BatchHomePacket(buf.readBoolean());
        }

        public static void handle(BatchHomePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                int applied = 0;
                int schedSkipped = 0;
                for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                    for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                        if (!(e instanceof EntityMaid m) || !m.m_6084_() || !m.m_21830_(player)) {
                            continue;
                        }
                        // 排班中 home 由排班管理（开排班自动 home、关排班解除）——跳过
                        if (ScheduleData.isOn(m)) {
                            schedSkipped++;
                            continue;
                        }
                        m.setHomeModeEnable(pkt.on);
                        applied++;
                    }
                }
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        (pkt.on ? "§a已开启 " : "§7已关闭 ") + applied + " 名女仆的在家模式"
                                + (schedSkipped > 0 ? "§7（" + schedSkipped
                                + " 名排班中保持原样——先关闭她们的排班才能一键更改）" : "")));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 排班守卫拒绝 → 客户端重同步 ==================== */

    /** 服务端：守卫拒绝后把女仆当前真实任务/作息同步给其的主人（客户端实体扳回） */
    public static void sendResync(net.minecraft.server.level.ServerPlayer player, EntityMaid maid) {
        try {
            String taskUid = maid.getTask() == null ? "touhou_little_maid:idle"
                    : maid.getTask().getUid().toString();
            int sched = maid.getSchedule() == null ? -1 : maid.getSchedule().ordinal();
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new MaidTaskResyncPacket(maid.m_19879_(), taskUid, sched));
        } catch (Throwable ignored) {
        }
    }

    public static class MaidTaskResyncPacket {
        private final int maidId;
        private final String taskUid;
        private final int scheduleOrdinal;

        public MaidTaskResyncPacket(int maidId, String taskUid, int scheduleOrdinal) {
            this.maidId = maidId;
            this.taskUid = taskUid;
            this.scheduleOrdinal = scheduleOrdinal;
        }

        public static void encode(MaidTaskResyncPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maidId);
            buf.m_130072_(pkt.taskUid, 256);
            buf.writeInt(pkt.scheduleOrdinal);
        }

        public static MaidTaskResyncPacket decode(FriendlyByteBuf buf) {
            return new MaidTaskResyncPacket(buf.readInt(), buf.m_130136_(256), buf.readInt());
        }

        public static void handle(MaidTaskResyncPacket pkt, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                try {
                    var mc = net.minecraft.client.Minecraft.m_91087_();
                    if (mc.f_91073_ == null) {
                        return;
                    }
                    net.minecraft.world.entity.Entity e = mc.f_91073_.m_6815_(pkt.maidId);
                    if (!(e instanceof EntityMaid maid)) {
                        return;
                    }
                    var task = TaskManager.findTask(
                                    net.minecraft.resources.ResourceLocation.parse(pkt.taskUid))
                            .orElse(TaskManager.getIdleTask());
                    maid.setTask(task); // 客户端实体：本地脱钩的假任务被扳回
                    if (pkt.scheduleOrdinal >= 0
                            && pkt.scheduleOrdinal < MaidSchedule.values().length) {
                        maid.setSchedule(MaidSchedule.values()[pkt.scheduleOrdinal]);
                    }
                } catch (Throwable ignored) {
                    // 客户端侧容错——绝不影响游戏
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 打开 UI ==================== */

    /** 服务端：收集女仆列表 + 任务清单，给玩家发打开包（排班表物品右键调用）
     *  v1.1.0 实测六十：扫描【全部维度】（旧版只扫玩家所在维度——下界/末地的
     *  女仆直接消失在列表里）；条目扩到 8 字段：+血量百分比 + 维度标签
     *  （空串 = 与玩家同维度不显示），借鉴 Maid_Roster 的状态显示（在场/其他维度）*/
    public static void openFor(ServerPlayer player) {
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        // 女仆列表：{uuid, 名字, 任务UID, 工作模式(0早/1晚/2全), 排班开"1"/"0", 段数, 血量%, 维度标签, 在家模式"1"/"0"}
        List<String[]> maids = new ArrayList<>();
        List<String> taskUids = new ArrayList<>();
        for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
            for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                if (!(e instanceof EntityMaid m) || !m.m_6084_()) {
                    continue;
                }
                if (!m.m_21830_(player)) {
                    continue; // 只列自己的女仆
                }
                String taskUid = m.getTask() == null ? "touhou_little_maid:idle"
                        : m.getTask().getUid().toString();
                int mode = m.getSchedule() == null ? 2 : m.getSchedule().ordinal();
                // v1.2.0【血量百分比公式修正】：旧版写成 getMaxHealth / getHealth——取反了。
                // m_21233_ = getMaxHealth（读 MAX_HEALTH 属性）、m_21223_ = getHealth（读同步器
                // 当前血量），字节码实证。满血时恰好 100% 所以一直没被发现；一旦掉血，数字会
                // 往上涨（15/20 显示 133%、10/20 显示 200%、1/20 显示 2000%）。改为与其它
                // 8 处一致的【当前/最大】：掉血就显示 75%、50%、5%。
                int hp = (int) Math.round(m.m_21223_() / Math.max(1.0f, m.m_21233_()) * 100.0f);
                String dimTag = "";
                if (lvl != level) {
                    dimTag = switch (lvl.m_46472_().m_135782_().m_135815_()) {
                        case "overworld" -> "主世界";
                        case "the_nether" -> "下界";
                        case "the_end" -> "末地";
                        default -> lvl.m_46472_().m_135782_().m_135815_();
                    };
                }
                maids.add(new String[]{m.m_20148_().toString(), m.m_5446_().getString(),
                        taskUid, String.valueOf(mode),
                        ScheduleData.isOn(m) ? "1" : "0",
                        String.valueOf(ScheduleData.load(m).size()),
                        String.valueOf(hp), dimTag,
                        m.isHomeModeEnable() ? "1" : "0"});
                // 任务清单：用第一只女仆生成（隐藏任务因女仆而异，取代表）
                if (taskUids.isEmpty()) {
                    try {
                        for (var task : TaskManager.getNotHiddenTaskList(m)) {
                            taskUids.add(task.getUid().toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (maids.size() >= 200) {
                    break; // 极端数量保护（与手册一致）
                }
            }
            if (maids.size() >= 200) {
                break;
            }
        }
        maids.sort(java.util.Comparator.comparing(a -> a[1]));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenSchedulePacket(maids, taskUids));
    }

    /** S2C 打开包：女仆列表 + 可选任务 */
    public static class OpenSchedulePacket {
        public final List<String[]> maids;
        public final List<String> taskUids;

        public OpenSchedulePacket(List<String[]> maids, List<String> taskUids) {
            this.maids = maids;
            this.taskUids = taskUids;
        }

        public static void encode(OpenSchedulePacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.maids.size());
            for (String[] m : pkt.maids) {
                // v1.1.0 实测三百四十二：9 字段（追加在家模式 m[8]）
                for (int i = 0; i < 9; i++) {
                    buf.m_130072_(m.length > i ? m[i] : "", 256);
                }
            }
            buf.writeInt(pkt.taskUids.size());
            for (String t : pkt.taskUids) {
                buf.m_130072_(t, 256);
            }
        }

        public static OpenSchedulePacket decode(FriendlyByteBuf buf) {
            int n = buf.readInt();
            List<String[]> maids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // v1.1.0 实测三百四十二：9 字段（追加在家模式 m[8]）
                maids.add(new String[]{buf.m_130136_(256), buf.m_130136_(256), buf.m_130136_(256),
                        buf.m_130136_(256), buf.m_130136_(256), buf.m_130136_(256),
                        buf.m_130136_(256), buf.m_130136_(256), buf.m_130136_(256)});
            }
            int tn = buf.readInt();
            List<String> tasks = new ArrayList<>();
            for (int i = 0; i < tn; i++) {
                tasks.add(buf.m_130136_(256));
            }
            return new OpenSchedulePacket(maids, tasks);
        }

        public static void handle(OpenSchedulePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // v1.1.0 实测十六（审查 P2-4）：S2C 包方向校验——恶意客户端把 S2C 包 ID
            // 发往服务端时，handle 会加载 ScheduleBookScreen（引用客户端 Minecraft 类）
            // → 专用服 NoClassDefFoundError。只接受 PLAY_TO_CLIENT 方向。
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.schedule.ScheduleBookScreen.open(pkt.maids, pkt.taskUids));
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 快捷设置 ==================== */

    /** C2S 快捷设置：mode/taskUid/on 均可 -1/空 = 不改 */
    public static class QuickApplyPacket {
        public final String uuid;
        public final int mode;
        public final String taskUid;
        public final int on;

        public QuickApplyPacket(String uuid, int mode, String taskUid, int on) {
            this.uuid = uuid;
            this.mode = mode;
            this.taskUid = taskUid;
            this.on = on;
        }

        public static void encode(QuickApplyPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeInt(pkt.mode);
            buf.m_130072_(pkt.taskUid == null ? "" : pkt.taskUid, 256);
            buf.writeInt(pkt.on);
        }

        public static QuickApplyPacket decode(FriendlyByteBuf buf) {
            return new QuickApplyPacket(buf.m_130136_(64), buf.readInt(), buf.m_130136_(256), buf.readInt());
        }

        public static void handle(QuickApplyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    return;
                }
                // 先处理排班开关（实测七十：开排班同步进入 home 模式，关排班解除）
                if (pkt.on == 0 || pkt.on == 1) {
                    ScheduleData.setOn(maid, pkt.on == 1);
                    maid.setHomeModeEnable(pkt.on == 1);
                    // v1.1.0 实测一百三十七【切换至排班 = 强制接管】：开启的瞬间清掉可能
                    // 残留的本段去抖键/尝试记录/重试冷却，立即按当前时段应用（手头任务
                    // 强制改为该时段排班的任务）。旧版只等 1 秒扫描，且残留去抖键会直接
                    // 跳过——手头任务要拖到下个时段边界才切，观感"开了排班没生效"
                    if (pkt.on == 1) {
                        com.maidsmart.schedule.ScheduleManager.clearAppliedForSave(maid);
                        com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
                        // v1.1.0 实测一百四十四：同保存路径——战斗中开启明确告知
                        if (com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid)) {
                            String nm = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                            player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                    "§e【排班】「" + nm + "」正在战斗中——战斗结束自动切换当前时段（模式/任务）"));
                        }
                    }
                }
                // v1.1.0 实测七十六：排班开着时，工作模式与任务【都】由日程表管理——
                // 手动改哪一个都会在下个时段边界被日程翻回去，索性硬性拦下（先关排班）
                boolean schedOn = ScheduleData.isOn(maid);
                String maidName = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                if (pkt.mode >= 0 && pkt.mode <= 2) {
                    if (schedOn) {
                        player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "§c【排班】「" + maidName + "」的日程表开着，工作模式由日程表管理——请先关闭她的排班再修改"));
                    } else {
                        maid.setSchedule(com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule
                                .values()[pkt.mode]);
                    }
                }
                if (pkt.taskUid != null && !pkt.taskUid.isEmpty()) {
                    if (schedOn) {
                        player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "§c【排班】「" + maidName + "」的日程表开着，任务由日程表管理——请先关闭她的排班再切换任务"));
                    } else {
                        TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(pkt.taskUid))
                                .ifPresent(maid::setTask);
                        // 手动切任务 → 排班去抖键更新为"当前段"，避免下一秒排班又切回去
                        touchAppliedKey(maid, level);
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 日程加载/保存 ==================== */

    /** C2S 请求日程数据 */
    public static class SchedLoadRequestPacket {
        public final String uuid;

        public SchedLoadRequestPacket(String uuid) {
            this.uuid = uuid;
        }

        public static void encode(SchedLoadRequestPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
        }

        public static SchedLoadRequestPacket decode(FriendlyByteBuf buf) {
            return new SchedLoadRequestPacket(buf.m_130136_(64));
        }

        public static void handle(SchedLoadRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    // v1.1.0 实测十六（审查 P2-9）：跨维度女仆在本维度找不到时，
                    // 旧版静默 return → 客户端 waiting 永不清除，日程 tab 永远卡在
                    // "请求中…"。回一个空数据包让客户端正常显示空表
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new SchedDataPacket(pkt.uuid, false, java.util.Collections.emptyList()));
                    return;
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new SchedDataPacket(pkt.uuid, ScheduleData.isOn(maid),
                                ScheduleData.load(maid)));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C 日程数据 */
    public static class SchedDataPacket {
        public final String uuid;
        public final boolean on;
        public final List<ScheduleData.Segment> segments;

        public SchedDataPacket(String uuid, boolean on, List<ScheduleData.Segment> segments) {
            this.uuid = uuid;
            this.on = on;
            this.segments = segments;
        }

        public static void encode(SchedDataPacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeBoolean(pkt.on);
            buf.writeInt(pkt.segments.size());
            for (ScheduleData.Segment s : pkt.segments) {
                buf.writeInt(s.startMin());
                buf.writeInt(s.endMin());
                buf.writeInt(s.mode());
                buf.m_130072_(s.taskUid() == null ? "" : s.taskUid(), 256);
            }
        }

        public static SchedDataPacket decode(FriendlyByteBuf buf) {
            String uuid = buf.m_130136_(64);
            boolean on = buf.readBoolean();
            int n = buf.readInt();
            List<ScheduleData.Segment> segs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                segs.add(new ScheduleData.Segment(buf.readInt(), buf.readInt(),
                        buf.readInt(), buf.m_130136_(256)));
            }
            return new SchedDataPacket(uuid, on, segs);
        }

        public static void handle(SchedDataPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            // v1.1.0 实测十六（审查 P2-4）：S2C 方向校验（同 OpenSchedulePacket）
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                ctx.get().setPacketHandled(true);
                return;
            }
            ctx.get().enqueueWork(() ->
                    com.maidsmart.schedule.ScheduleBookScreen.showSchedule(pkt.uuid, pkt.on, pkt.segments));
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S 保存日程（实测五十一：班次+6 槽在客户端已转段并合并；服务端只防御越界，
     *  不再 normalize——normalize 会把末段延伸到 24:00，把休息时间也吃掉） */
    public static class SchedSavePacket {
        public final String uuid;
        public final boolean on;
        public final List<ScheduleData.Segment> segments;

        public SchedSavePacket(String uuid, boolean on, List<ScheduleData.Segment> segments) {
            this.uuid = uuid;
            this.on = on;
            this.segments = segments;
        }

        public static void encode(SchedSavePacket pkt, FriendlyByteBuf buf) {
            buf.m_130072_(pkt.uuid, 64);
            buf.writeBoolean(pkt.on);
            buf.writeInt(pkt.segments.size());
            for (ScheduleData.Segment s : pkt.segments) {
                buf.writeInt(s.startMin());
                buf.writeInt(s.endMin());
                buf.writeInt(s.mode());
                buf.m_130072_(s.taskUid() == null ? "" : s.taskUid(), 256);
            }
        }

        public static SchedSavePacket decode(FriendlyByteBuf buf) {
            String uuid = buf.m_130136_(64);
            boolean on = buf.readBoolean();
            int n = buf.readInt();
            List<ScheduleData.Segment> segs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                segs.add(new ScheduleData.Segment(buf.readInt(), buf.readInt(),
                        buf.readInt(), buf.m_130136_(256)));
            }
            return new SchedSavePacket(uuid, on, segs);
        }

        public static void handle(SchedSavePacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    return;
                }
                // 防御：段范围/模式越界夹取（来自客户端的包不信任）
                List<ScheduleData.Segment> safe = new ArrayList<>();
                for (ScheduleData.Segment s : pkt.segments) {
                    int st = Math.max(0, Math.min(1440, s.startMin()));
                    int en = Math.max(st, Math.min(1440, s.endMin()));
                    int md = Math.max(0, Math.min(2, s.mode()));
                    if (en > st) {
                        safe.add(new ScheduleData.Segment(st, en, md, s.taskUid()));
                    }
                }
                // 玩家手动保存 = 明确意图，清战斗还原宽限立即生效（实测六十一）
                maid.getPersistentData().m_128356_(ScheduleData.GRACE_TAG, 0L);
                ScheduleData.save(maid, safe, pkt.on);
                // v1.1.0 实测一百二十九：保存审计日志——对账"客户端以为开了/存了，
                // 服务端实际状态"（排班失效排查的入口证据）
                com.maidsmart.tool.PromaidLog.log("排班",
                        com.maidsmart.tool.PromaidLog.nameOf(maid)
                                + " 保存日程：" + (pkt.on ? "开启" : "关闭")
                                + " 段=" + safe.size() + " 首段="
                                + (safe.isEmpty() ? "无" : ScheduleData.fmt(safe.get(0).startMin())
                                + "~" + ScheduleData.fmt(safe.get(0).endMin())
                                + " 模式=" + safe.get(0).mode()
                                + " 任务=" + safe.get(0).taskUid()));
                // v1.1.0 实测一百三十五/一百三十七：仅【开启】排班的保存才"清残留 + 立即应用"——
                // 切换至排班 = 手头任务强制改为当前时段任务（去抖键只记段起点不记内容，
                // 旧版改当前段保存后被去抖挡掉，要等段边界；一百三十七补齐 QuickApply 开启
                // 路径）。【关闭】排班保存时不再调用 applyNow——旧版无条件调用，applyNow 会
                // 反向强制 home 模式并按日程应用段任务，等于"关了排班却还在执行排班"。
                // 关闭时也清掉残留记录/冷却，避免下次再开排班被旧状态干扰。
                com.maidsmart.schedule.ScheduleManager.clearAppliedForSave(maid);
                if (pkt.on) {
                    // v1.1.0 实测七十：开启的瞬间她自动进入在家模式（守家按日程干活）
                    maid.setHomeModeEnable(true);
                    // 保存后立即按当前时间应用一次（不用等下一个整分检查）
                    com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
                    // v1.1.0 实测一百四十四：战斗中开启排班 → 明确告知（排班让位于
                    // 战斗，战斗结束自动按当前时段切换模式/任务——此前静默等待，
                    // 观感"开了排班没生效"）
                    if (com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid)) {
                        String nm = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                        player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                                "§e【排班】「" + nm + "」正在战斗中——战斗结束自动切换当前时段（模式/任务）"));
                    }
                } else {
                    maid.setHomeModeEnable(false);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 批量应用 / 一键集合 / 改名（实测六十，借鉴 Maid_Roster） ==================== */

    /** C2S 批量应用：mode 0~2 应用工作模式（-1 = 跳过）；taskUid 非空应用任务。
     *  作用于主人【全部已加载女仆】（跨维度扫描——与列表页全维度口径一致）。
     *  未加载区块里的女仆不在线上找不到，天然跳过（Maid_Roster 靠点名册存位置
     *  才能唤醒，我们不做绑定，这是唯一学不来的部分）。 */
    public static class BatchApplyPacket {
        public final int mode;
        public final String taskUid;

        public BatchApplyPacket(int mode, String taskUid) {
            this.mode = mode;
            this.taskUid = taskUid == null ? "" : taskUid;
        }

        public static void encode(BatchApplyPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.mode);
            buf.m_130072_(pkt.taskUid, 256);
        }

        public static BatchApplyPacket decode(FriendlyByteBuf buf) {
            return new BatchApplyPacket(buf.readInt(), buf.m_130136_(256));
        }

        public static void handle(BatchApplyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !(player.m_9236_() instanceof ServerLevel level)) {
                    return;
                }
                var modes = com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule.values();
                boolean applyMode = pkt.mode >= 0 && pkt.mode < modes.length;
                boolean applyTask = pkt.taskUid != null && !pkt.taskUid.isEmpty()
                        && net.minecraft.resources.ResourceLocation.m_135830_(pkt.taskUid);
                if (!applyMode && !applyTask) {
                    return;
                }
                int applied = 0;
                int schedSkipped = 0; // v1.1.0 实测七十：排班中被跳过的数量
                for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                    for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                        if (!(e instanceof EntityMaid m) || !m.m_6084_() || !m.m_21830_(player)) {
                            continue;
                        }
                        // v1.1.0 实测六十二（自查修复）：跳过自保中/主动战斗中的女仆——
                        // 批量覆盖她们的任务会打断保命流程、污染战斗还原链（还原到被
                        // 批量改过的"原任务"）。单女仆快捷设置同样不检查，但批量是
                        // 一改一整队，必须兜住
                        // v1.1.0 实测一百六十三：真实战斗判定（残留标记不再挡批量应用）
                        // v1.1.0 实测二百六十五/二百六十六（反馈："满血的时候全员模式
                        // 也没用"）：旧版用 isReallyCombatActive 跳过——排班关闭时它含
                        // IAttackTask 兜底（当前任务是任意攻击任务即判"战斗中"），玩家
                        // 手动安排攻击任务的女仆（满血、无排班）被永久跳过。批量应用是
                        // 玩家明确意图，只跳过【真本系统战斗】（ASSIGNED 匹配当前任务）
                        // 的女仆；残留标记由 isRealCombatActive 顺带清掉。
                        // v1.1.0 实测二百六十七（反馈："剪刀模式女仆解除排班满血，全员
                        // 模式不响应"）：跳过/失败完全静默——加逐只诊断日志（latest.log
                        // 搜 "batch-apply"）+ 应用后读回校验（TLM setSchedule/setTask 有
                        // 守卫会静默拒绝）+ 单只 try/catch 隔离（一只异常不再中断整队）。
                        String mName = m.m_5446_() != null ? m.m_5446_().getString() : m.m_20148_().toString();
                        if (m.getPersistentData().m_128471_(com.maidsmart.combat.SelfPreservationBehavior.PRESERVE_TAG)) {
                            com.mojang.logging.LogUtils.getLogger().info(
                                    "batch-apply: skip {} reason=preserve", mName);
                            continue;
                        }
                        if (com.maidsmart.combat.AutoCombatSwitch.isRealCombatActive(m)) {
                            com.mojang.logging.LogUtils.getLogger().info(
                                    "batch-apply: skip {} reason=combat", mName);
                            continue;
                        }
                        if (ScheduleData.isOn(m)) {
                            schedSkipped++;
                            com.mojang.logging.LogUtils.getLogger().info(
                                    "batch-apply: skip {} reason=sched", mName);
                            continue;
                        }
                        try {
                            boolean changed = false;
                            if (applyMode) {
                                m.setSchedule(modes[pkt.mode]);
                                // 读回校验：TLM setSchedule 有守卫（睡眠/活动中）会静默拒绝
                                var after = m.getSchedule();
                                if (after == null || after.ordinal() != pkt.mode) {
                                    com.mojang.logging.LogUtils.getLogger().info(
                                            "batch-apply: {} mode not applied (setSchedule rejected? want={} got={})",
                                            mName, pkt.mode, after == null ? "null" : after.ordinal());
                                } else {
                                    changed = true;
                                }
                            }
                            if (applyTask) {
                                try {
                                    TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(pkt.taskUid))
                                            .ifPresent(task -> {
                                                m.setTask(task);
                                                touchAppliedKey(m, lvl);
                                            });
                                    // v1.1.0 实测二百七十（反馈："点击应用空闲，女仆仍然
                                    // 显示自己在别的模式。哪怕排班没有开启"）：TLM setTask
                                    // 无守卫但客户端实体脱钩/任务被系统换回时静默失败——
                                    // 读回校验暴露"应用了但没生效"。findTask 不存在的任务
                                    // 时 ifPresent 不执行，同样计入 changed（虚报）。
                                    var afterTask = m.getTask();
                                    if (afterTask != null && afterTask.getUid() != null
                                            && afterTask.getUid().toString().equals(pkt.taskUid)) {
                                        changed = true;
                                    } else {
                                        com.mojang.logging.LogUtils.getLogger().info(
                                                "batch-apply: {} task not applied (want={}, got={})",
                                                mName, pkt.taskUid,
                                                afterTask == null || afterTask.getUid() == null
                                                        ? "null" : afterTask.getUid().toString());
                                    }
                                } catch (Exception ex) {
                                    com.mojang.logging.LogUtils.getLogger().info(
                                            "batch-apply: {} task error: {}", mName, ex.toString());
                                }
                            }
                            if (changed) {
                                applied++;
                                com.mojang.logging.LogUtils.getLogger().info(
                                        "batch-apply: {} applied mode={} task={}", mName,
                                        applyMode ? pkt.mode : -1, applyTask ? pkt.taskUid : "");
                            }
                        } catch (Throwable t) {
                            // 单只隔离：一只女仆异常不中断整队（旧版无保护，setSchedule
                            // 抛异常会让循环中断、后面的女仆全部不应用）
                            com.mojang.logging.LogUtils.getLogger().info(
                                    "batch-apply: {} error: {}", mName, t.toString());
                        }
                    }
                }
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a已为 " + applied + " 名女仆更新设置"
                                + (schedSkipped > 0 ? "§7（" + schedSkipped
                                + " 名排班中保持原样——先关闭她们的排班才能一键更改）" : "")));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S 一键集合：把主人全部在场女仆（跨维度）传送到身边；不在已加载区块里的
     *  按「最后出现位置」强载区块并自动召回（实测七十）。传送复用实测四十四的
     *  真传送链路（teleportTo + 落点找站立格）。 */
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

    /** C2S 日程表详情页「传送到我身边」（实测二百零八）：只召唤指定 UUID 这一只女仆
     *  （跨维度查找任意已加载世界；豁免口径与一键集合同——坐/骑/家/死亡不传，回复说明原因）。
     *  服务端参照 SummonPacket 结构：enqueueWork + 聊天回复。 */
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

    /** C2S 改名：设置女仆自定义名（等同命名牌；§ 色号剥除，超长截断 30） */
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
                    EntityMaid m = findMaid(lvl, pkt.uuid);
                    if (m != null && m.m_21830_(player)) {
                        maid = m;
                        break;
                    }
                }
                if (maid == null || !allowed(player, maid)) {
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
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new MaidRenameSyncPacket(maid.m_20148_().toString(), n));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C 改名同步（实测三百四十九）：服务端改名成功 → 推新名字给打开排班书的玩家 */
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

    /* ==================== 坐标显示 / 去她身边（v1.2.0） ==================== */

    /** C2S 问坐标：详情页打开期间每秒一次（女仆走动后打开时的快照就不准了）。
     *  只在详情页开着时发，界面关闭即停——不产生常驻流量。 */
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
                EntityMaid maid = findMaid(level, pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    // 不在了（被收进魂符/换维度加载不到）——回一个空标记让界面停止刷新
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new MaidCoordPacket(pkt.uuid, true, "", 0, 0, 0));
                    return;
                }
                BlockPos p = maid.m_20183_();
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new MaidCoordPacket(pkt.uuid, false, dimName(maid.m_9236_()),
                                p.m_123341_(), p.m_123342_(), p.m_123343_()));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C 坐标回包。gone=true = 服务端找不到她（界面显示"不在场"并停止刷新）。 */
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

    /** C2S 把【玩家】传到【女仆】身边（与「传送到我身边」方向相反）。
     *  女仆那一侧不动——是她待的地方，玩家过去找她。 */
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
                EntityMaid maid = findMaid((ServerLevel) player.m_9236_(), pkt.uuid);
                if (maid == null || !allowed(player, maid)) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没找到她——可能已被收进魂符或不在已加载区块"));
                    return;
                }
                if (maid.m_9236_() instanceof ServerLevel target && !target.m_46472_()
                        .equals(player.m_9236_().m_46472_())) {
                    // 跨维度：目的地用她脚下的安全落点（findStand 的判定口径与召回一致）
                    String nm = maid.m_5446_() != null ? maid.m_5446_().getString() : "女仆";
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§e她不在你这个维度（在" + dimName(maid.m_9236_()) + "）——正在把你送过去"));
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
                            ? "§a已传送到「" + nm + "」身边（" + dimName(target) + "）"
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

    /** 维度显示名（主世界/下界/末地，其余用注册路径） */
    static String dimName(net.minecraft.world.level.Level level) {
        try {
            return switch (level.m_46472_().m_135782_().m_135815_()) {
                case "overworld" -> "主世界";
                case "the_nether" -> "下界";
                case "the_end" -> "末地";
                default -> level.m_46472_().m_135782_().m_135815_();
            };
        } catch (Exception e) {
            return "?";
        }
    }

    /* ==================== 工具 ==================== */

    /** 按 UUID 找女仆（当前维度；找不到返回 null）。
     *  v1.1.0 实测二百六十一：排班书列表是全维度扫描的（openFor 跨维度收集），但
     *  快捷设置/开关/保存只查【玩家当前维度】——女仆在别的维度（下界/末地/别的
     *  区块）时"开排班/关排班/改模式"全部静默失败：客户端列表显示已改、服务端
     *  根本没执行（开关没写、模式没切、锁定没解除）。改为跨维度查找（与列表口径
     *  一致），找不到才返回 null。 */
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

    /** 更新排班去抖键为当前段（手动切任务后排班不立即覆盖） */
    static void touchAppliedKey(EntityMaid maid, ServerLevel level) {
        var seg = ScheduleData.segmentAt(ScheduleData.load(maid), ScheduleData.currentMinute(level));
        if (seg != null) {
            maid.getPersistentData().m_128359_(ScheduleData.APPLIED_TAG,
                    ScheduleData.dayIndex(level) + "|" + seg.startMin());
        }
    }

    /* ==================== 实测五百六十二：潜行+中键 工位标记 ==================== */

    /** C2S：玩家潜行+中键方块 → 把身边 32 格内自家 home 模式女仆的工位/休闲锚点
     *  标到该处并立即 restrictTo。粉丝反馈的直接入口："不跟随状态下女仆找不到
     *  工作区域"——标记=快速设定 TLM home 的工位锚点（复用原版 SchedulePos 骨架，
     *  不另起一套定点系统）。睡眠锚点不动（夜间照常回原处）。 */
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
}
