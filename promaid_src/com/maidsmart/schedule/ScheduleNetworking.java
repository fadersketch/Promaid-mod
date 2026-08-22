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
    }

    /* ==================== 打开 UI ==================== */

    /** 服务端：收集女仆列表 + 任务清单，给玩家发打开包（排班表物品右键调用） */
    public static void openFor(ServerPlayer player) {
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        // 女仆列表：{uuid, 名字, 任务UID, 工作模式(0早/1晚/2全), 排班开"1"/"0", 段数}
        List<String[]> maids = new ArrayList<>();
        List<String> taskUids = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.m_8583_()) {
            if (!(e instanceof EntityMaid m) || !m.m_6084_()) {
                continue;
            }
            if (!m.m_21830_(player)) {
                continue; // 只列自己的女仆
            }
            String taskUid = m.getTask() == null ? "touhou_little_maid:idle"
                    : m.getTask().getUid().toString();
            int mode = m.getSchedule() == null ? 2 : m.getSchedule().ordinal();
            maids.add(new String[]{m.m_20148_().toString(), m.m_5446_().getString(),
                    taskUid, String.valueOf(mode),
                    ScheduleData.isOn(m) ? "1" : "0",
                    String.valueOf(ScheduleData.load(m).size())});
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
                for (int i = 0; i < 6; i++) {
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

    /** C2S 保存日程（服务端再归一化一次防御） */
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
                List<ScheduleData.Segment> norm = ScheduleData.normalize(pkt.segments);
                ScheduleData.save(maid, norm, pkt.on);
                // 保存后立即按当前时间应用一次（不用等下一个整分检查）
                com.maidsmart.schedule.ScheduleManager.applyNow(maid, level);
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
