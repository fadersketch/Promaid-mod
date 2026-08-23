package com.maidsmart.schedule;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
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
        // 女仆列表：{uuid, 名字, 任务UID, 工作模式(0早/1晚/2全), 排班开"1"/"0", 段数, 血量%, 维度标签}
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
                int hp = (int) Math.round(m.m_21233_() / Math.max(1.0f, m.m_21223_()) * 100.0f);
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
                        String.valueOf(hp), dimTag});
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
                for (int i = 0; i < 8; i++) {
                    buf.m_130072_(m[i], 256);
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
                maids.add(new String[]{buf.m_130136_(256), buf.m_130136_(256), buf.m_130136_(256),
                        buf.m_130136_(256), buf.m_130136_(256), buf.m_130136_(256),
                        buf.m_130136_(256), buf.m_130136_(256)});
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
                if (pkt.mode >= 0 && pkt.mode <= 2) {
                    maid.setSchedule(com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule
                            .values()[pkt.mode]);
                }
                if (pkt.taskUid != null && !pkt.taskUid.isEmpty()) {
                    TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(pkt.taskUid))
                            .ifPresent(maid::setTask);
                    // 手动切任务 → 排班去抖键更新为"当前段"，避免下一秒排班又切回去
                    touchAppliedKey(maid, level);
                }
                if (pkt.on == 0 || pkt.on == 1) {
                    ScheduleData.setOn(maid, pkt.on == 1);
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
                ScheduleData.save(maid, safe, pkt.on);
                // 保存后立即按当前时间应用一次（不用等下一个整分检查）
                com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
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
                for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                    for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                        if (!(e instanceof EntityMaid m) || !m.m_6084_() || !m.m_21830_(player)) {
                            continue;
                        }
                        boolean changed = false;
                        if (applyMode) {
                            m.setSchedule(modes[pkt.mode]);
                            changed = true;
                        }
                        if (applyTask) {
                            try {
                                TaskManager.findTask(net.minecraft.resources.ResourceLocation.parse(pkt.taskUid))
                                        .ifPresent(t -> {
                                            m.setTask(t);
                                            touchAppliedKey(m, lvl);
                                        });
                                changed = true;
                            } catch (Exception ignored) {
                            }
                        }
                        if (changed) {
                            applied++;
                        }
                    }
                }
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a已为 " + applied + " 名女仆更新设置"), false);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S 一键集合：把主人全部已加载女仆（跨维度）传送到身边。
     *  传送复用实测四十四的真传送链路（teleportTo + 落点找站立格）。 */
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
                int ok = 0;
                int fail = 0;
                for (ServerLevel lvl : player.m_9236_().m_7654_().m_129785_()) {
                    for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                        if (!(e instanceof EntityMaid m) || !m.m_6084_() || !m.m_21830_(player)) {
                            continue;
                        }
                        // 已在身边 5 格内的不折腾
                        if (lvl == level && m.m_20238_(player.m_20182_()) < 25.0) {
                            continue;
                        }
                        if (com.maidsmart.follow.MaidChunkLoadManager.summonMaidTo(m, player)) {
                            ok++;
                        } else {
                            fail++;
                        }
                    }
                }
                if (ok == 0 && fail == 0) {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§7没有需要集合的女仆（都在身边或不在场上）"), false);
                } else {
                    player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "§a已集合 " + ok + " 名女仆到身边"
                                    + (fail > 0 ? "§7（" + fail + " 名未能到达——身边没有可站立点）" : "")), false);
                }
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
                            "§c名字不能为空"), false);
                    return;
                }
                if (n.length() > 30) {
                    n = n.substring(0, 30);
                }
                maid.m_6593_(net.minecraft.network.chat.Component.m_237113_(n));
                player.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                        "§a已改名为「" + n + "」"), false);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /* ==================== 工具 ==================== */

    /** 按 UUID 找女仆（当前维度；找不到返回 null） */
    static EntityMaid findMaid(ServerLevel level, String uuid) {
        try {
            return (EntityMaid) level.m_8791_(java.util.UUID.fromString(uuid));
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
}
