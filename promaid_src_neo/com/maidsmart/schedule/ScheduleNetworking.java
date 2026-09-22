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
 * 排班表网络层（v1.1.0）——排班表物品（纸+墨囊合成）右键打开的 UI 全走这里。
 *
 * 包清单：
 * - 0 OpenSchedulePacket（S2C）：打开 UI——女仆列表 + 可选任务清单
 * - 1 QuickApplyPacket（C2S）：快捷设置——工作模式 / 任务 / 排班开关（-1 = 不改）
 * - 2 SchedLoadRequestPacket（C2S）：请求某女仆的日程数据
 * - 3 SchedDataPacket（S2C）：下发日程数据（详情页「日程设置」tab 用）
 * - 4 SchedSavePacket（C2S）：保存日程（归一化在客户端做，服务端再归一化一次防御）
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class ScheduleNetworking {
    
    
    private ScheduleNetworking() {
    }

        @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(SchedulePacketsPlan.OpenSchedulePacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.OpenSchedulePacket::encode, SchedulePacketsPlan.OpenSchedulePacket::decode), SchedulePacketsPlan.OpenSchedulePacket::handle);
        r.playToServer(SchedulePacketsPlan.QuickApplyPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.QuickApplyPacket::encode, SchedulePacketsPlan.QuickApplyPacket::decode), SchedulePacketsPlan.QuickApplyPacket::handle);
        r.playToServer(SchedulePacketsPlan.SchedLoadRequestPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.SchedLoadRequestPacket::encode, SchedulePacketsPlan.SchedLoadRequestPacket::decode), SchedulePacketsPlan.SchedLoadRequestPacket::handle);
        r.playToClient(SchedulePacketsPlan.SchedDataPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.SchedDataPacket::encode, SchedulePacketsPlan.SchedDataPacket::decode), SchedulePacketsPlan.SchedDataPacket::handle);
        r.playToServer(SchedulePacketsPlan.SchedSavePacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.SchedSavePacket::encode, SchedulePacketsPlan.SchedSavePacket::decode), SchedulePacketsPlan.SchedSavePacket::handle);
        r.playToServer(SchedulePacketsPlan.BatchApplyPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.BatchApplyPacket::encode, SchedulePacketsPlan.BatchApplyPacket::decode), SchedulePacketsPlan.BatchApplyPacket::handle);
        r.playToServer(SchedulePacketsMaid.SummonPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.SummonPacket::encode, SchedulePacketsMaid.SummonPacket::decode), SchedulePacketsMaid.SummonPacket::handle);
        r.playToServer(SchedulePacketsMaid.RenameMaidPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.RenameMaidPacket::encode, SchedulePacketsMaid.RenameMaidPacket::decode), SchedulePacketsMaid.RenameMaidPacket::handle);
        r.playToClient(SchedulePacketsPlan.MaidTaskResyncPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.MaidTaskResyncPacket::encode, SchedulePacketsPlan.MaidTaskResyncPacket::decode), SchedulePacketsPlan.MaidTaskResyncPacket::handle);
        r.playToServer(SchedulePacketsMaid.MaidSummonPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MaidSummonPacket::encode, SchedulePacketsMaid.MaidSummonPacket::decode), SchedulePacketsMaid.MaidSummonPacket::handle);
        r.playToClient(SchedulePacketsPlan.MaidStateSyncPacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.MaidStateSyncPacket::encode, SchedulePacketsPlan.MaidStateSyncPacket::decode), SchedulePacketsPlan.MaidStateSyncPacket::handle);
        r.playToServer(SchedulePacketsPlan.HomeTogglePacket.TYPE, StreamCodec.ofMember(SchedulePacketsPlan.HomeTogglePacket::encode, SchedulePacketsPlan.HomeTogglePacket::decode), SchedulePacketsPlan.HomeTogglePacket::handle);
        // v1.2.2 实测六百二十一：BatchHomePacket（列表页「全员在家」批量包）已整条删除——
        // 按钮与包一起移除（玩家裁定），单只女仆的在家开关走上面那条 HomeTogglePacket。
        r.playToClient(SchedulePacketsMaid.MaidRenameSyncPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MaidRenameSyncPacket::encode, SchedulePacketsMaid.MaidRenameSyncPacket::decode), SchedulePacketsMaid.MaidRenameSyncPacket::handle);
        // v1.2.0：快捷设置页显示女仆当前坐标 + 「去她身边」——坐标由服务端回（客户端
        // 只拿到打开排班表那一刻的快照，女仆走动后就不准了，所以每秒问一次）
        r.playToServer(SchedulePacketsMaid.MaidCoordRequestPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MaidCoordRequestPacket::encode, SchedulePacketsMaid.MaidCoordRequestPacket::decode), SchedulePacketsMaid.MaidCoordRequestPacket::handle);
        r.playToClient(SchedulePacketsMaid.MaidCoordPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MaidCoordPacket::encode, SchedulePacketsMaid.MaidCoordPacket::decode), SchedulePacketsMaid.MaidCoordPacket::handle);
        r.playToServer(SchedulePacketsMaid.MaidTeleportToPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MaidTeleportToPacket::encode, SchedulePacketsMaid.MaidTeleportToPacket::decode), SchedulePacketsMaid.MaidTeleportToPacket::handle);
        // 实测五百六十二：潜行+中键 工位标记（把身边 home 女仆的工作区域锚点标过去）
        r.playToServer(SchedulePacketsMaid.MarkWorkPosPacket.TYPE, StreamCodec.ofMember(SchedulePacketsMaid.MarkWorkPosPacket::encode, SchedulePacketsMaid.MarkWorkPosPacket::decode), SchedulePacketsMaid.MarkWorkPosPacket::handle);
    }

    /* ==================== 实测五百六十二：潜行+中键 工位标记 ==================== */

    /* ==================== 排班生效 → GUI 状态同步 ==================== */

    /** 服务端：把女仆当前真实状态（任务/模式/排班开关）推给其主人——排班段应用
     *  成功后调用，打开着排班书的玩家 GUI 立即更新为排班状态（快捷设置页同步+锁定） */
    public static void sendMaidStateSync(net.minecraft.server.level.ServerPlayer player, EntityMaid maid) {
        try {
            String taskUid = maid.getTask() == null ? "touhou_little_maid:idle"
                    : maid.getTask().getUid().toString();
            int sched = maid.getSchedule() == null ? 2 : maid.getSchedule().ordinal();
            PacketDistributor.sendToPlayer(player, new SchedulePacketsPlan.MaidStateSyncPacket(maid.getUUID().toString(), taskUid, sched,
                            ScheduleData.isOn(maid)));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 排班表内单独调整在家模式（实测三百四十二） ==================== */

    /* ==================== 排班守卫拒绝 → 客户端重同步 ==================== */

    /** 服务端：守卫拒绝后把女仆当前真实任务/作息同步给其的主人（客户端实体扳回） */
    public static void sendResync(net.minecraft.server.level.ServerPlayer player, EntityMaid maid) {
        try {
            String taskUid = maid.getTask() == null ? "touhou_little_maid:idle"
                    : maid.getTask().getUid().toString();
            int sched = maid.getSchedule() == null ? -1 : maid.getSchedule().ordinal();
            PacketDistributor.sendToPlayer(player, new SchedulePacketsPlan.MaidTaskResyncPacket(maid.getId(), taskUid, sched));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 打开 UI ==================== */

    /** 服务端：收集女仆列表 + 任务清单，给玩家发打开包（排班表物品右键调用）
     *  v1.1.0 实测六十：扫描【全部维度】（旧版只扫玩家所在维度——下界/末地的
     *  女仆直接消失在列表里）；条目扩到 8 字段：+血量百分比 + 维度标签
     *  （空串 = 与玩家同维度不显示），借鉴 Maid_Roster 的状态显示（在场/其他维度）*/
    public static void openFor(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 女仆列表：{uuid, 名字, 任务UID, 工作模式(0早/1晚/2全), 排班开"1"/"0", 段数, 血量%, 维度标签, 在家模式"1"/"0"}
        List<String[]> maids = new ArrayList<>();
        List<String> taskUids = new ArrayList<>();
        for (ServerLevel lvl : player.level().getServer().getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : lvl.getAllEntities()) {
                if (!(e instanceof EntityMaid m) || !m.isAlive()) {
                    continue;
                }
                if (!m.isOwnedBy(player)) {
                    continue; // 只列自己的女仆
                }
                String taskUid = m.getTask() == null ? "touhou_little_maid:idle"
                        : m.getTask().getUid().toString();
                int mode = m.getSchedule() == null ? 2 : m.getSchedule().ordinal();
                // v1.2.0【血量百分比公式修正】：旧版写成 getMaxHealth / getHealth——取反了。
                // 满血时恰好 100% 所以一直没被发现；一旦掉血，数字会往上涨（15/20 显示
                // 133%、10/20 显示 200%、1/20 显示 2000%）。改为与其它 8 处一致的
                // 【当前/最大】：掉血就显示 75%、50%、5%。
                int hp = (int) Math.round(m.getHealth() / Math.max(1.0f, m.getMaxHealth()) * 100.0f);
                String dimTag = "";
                if (lvl != level) {
                    dimTag = switch (lvl.dimension().location().getPath()) {
                        case "overworld" -> "主世界";
                        case "the_nether" -> "下界";
                        case "the_end" -> "末地";
                        default -> lvl.dimension().location().getPath();
                    };
                }
                maids.add(new String[]{m.getUUID().toString(), m.getDisplayName().getString(),
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
        PacketDistributor.sendToPlayer(player, new SchedulePacketsPlan.OpenSchedulePacket(maids, taskUids));
    }

    /* ==================== 快捷设置 ==================== */

    /* ==================== 日程加载/保存 ==================== */

    /* ==================== 批量应用 / 一键集合 / 改名（实测六十，借鉴 Maid_Roster） ==================== */

    /* ==================== 坐标显示 / 去她身边（v1.2.0） ==================== */

    /** 维度显示名（主世界/下界/末地，其余用注册路径） */
    static String dimName(net.minecraft.world.level.Level level) {
        try {
            return switch (level.dimension().location().getPath()) {
                case "overworld" -> "主世界";
                case "the_nether" -> "下界";
                case "the_end" -> "末地";
                default -> level.dimension().location().getPath();
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

    /** 更新排班去抖键为当前段（手动切任务后排班不立即覆盖） */
    static void touchAppliedKey(EntityMaid maid, ServerLevel level) {
        var seg = ScheduleData.segmentAt(ScheduleData.load(maid), ScheduleData.currentMinute(level));
        if (seg != null) {
            ((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().putString(ScheduleData.APPLIED_TAG,
                    ScheduleData.dayIndex(level) + "|" + seg.startMin());
        }
    }
}
