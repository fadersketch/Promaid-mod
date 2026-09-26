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
 * 排班表网络包——排班数据本身（v1.2.4 从 ScheduleNetworking 拆出）。
 * 
 * 状态同步/回家开关/任务重同步/打开界面/快速套用/读取/回传/保存/批量套用。
 * 原为 ScheduleNetworking 的嵌套类，搬出后外部引用已全树改写。编解码逐字未动。
 */
public final class SchedulePacketsPlan {
    private SchedulePacketsPlan() {
    }

    public static class MaidStateSyncPacket {
        final String uuid;
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
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
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

    public static class MaidTaskResyncPacket {
        final int maidId;
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
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
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
                        ScheduleNetworking.touchAppliedKey(maid, level);
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

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
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
                    // v1.1.0 实测十六（审查 P2-9）：跨维度女仆在本维度找不到时，
                    // 旧版静默 return → 客户端 waiting 永不清除，日程 tab 永远卡在
                    // "请求中…"。回一个空数据包让客户端正常显示空表
                    ScheduleNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new SchedDataPacket(pkt.uuid, false, java.util.Collections.emptyList()));
                    return;
                }
                ScheduleNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new SchedDataPacket(pkt.uuid, ScheduleData.isOn(maid),
                                ScheduleData.load(maid)));
            });
            ctx.get().setPacketHandled(true);
        }
    }

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
                EntityMaid maid = ScheduleNetworking.findMaid(level, pkt.uuid);
                if (maid == null || !ScheduleNetworking.allowed(player, maid)) {
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
                    for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
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
                                                ScheduleNetworking.touchAppliedKey(m, lvl);
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
}
