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
        CHANNEL.registerMessage(0, SchedulePacketsPlan.OpenSchedulePacket.class,
                SchedulePacketsPlan.OpenSchedulePacket::encode, SchedulePacketsPlan.OpenSchedulePacket::decode, SchedulePacketsPlan.OpenSchedulePacket::handle);
        CHANNEL.registerMessage(1, SchedulePacketsPlan.QuickApplyPacket.class,
                SchedulePacketsPlan.QuickApplyPacket::encode, SchedulePacketsPlan.QuickApplyPacket::decode, SchedulePacketsPlan.QuickApplyPacket::handle);
        CHANNEL.registerMessage(2, SchedulePacketsPlan.SchedLoadRequestPacket.class,
                SchedulePacketsPlan.SchedLoadRequestPacket::encode, SchedulePacketsPlan.SchedLoadRequestPacket::decode, SchedulePacketsPlan.SchedLoadRequestPacket::handle);
        CHANNEL.registerMessage(3, SchedulePacketsPlan.SchedDataPacket.class,
                SchedulePacketsPlan.SchedDataPacket::encode, SchedulePacketsPlan.SchedDataPacket::decode, SchedulePacketsPlan.SchedDataPacket::handle);
        CHANNEL.registerMessage(4, SchedulePacketsPlan.SchedSavePacket.class,
                SchedulePacketsPlan.SchedSavePacket::encode, SchedulePacketsPlan.SchedSavePacket::decode, SchedulePacketsPlan.SchedSavePacket::handle);
        // v1.1.0 实测六十（借鉴 Maid_Roster 军队管理）：批量应用 / 一键集合 / 改名
        CHANNEL.registerMessage(6, SchedulePacketsPlan.BatchApplyPacket.class,
                SchedulePacketsPlan.BatchApplyPacket::encode, SchedulePacketsPlan.BatchApplyPacket::decode, SchedulePacketsPlan.BatchApplyPacket::handle);
        CHANNEL.registerMessage(7, SchedulePacketsMaid.SummonPacket.class,
                SchedulePacketsMaid.SummonPacket::encode, SchedulePacketsMaid.SummonPacket::decode, SchedulePacketsMaid.SummonPacket::handle);
        CHANNEL.registerMessage(8, SchedulePacketsMaid.RenameMaidPacket.class,
                SchedulePacketsMaid.RenameMaidPacket::encode, SchedulePacketsMaid.RenameMaidPacket::decode, SchedulePacketsMaid.RenameMaidPacket::handle);
        // v1.1.0 实测一百九十一：排班守卫拒绝后的客户端重同步（TLM 面板本地 setTask
        // 改的是【客户端实体】——服务端拦截生效但客户端实体脱钩，面板永远显示"改
        // 成功"，观感=排班锁失效；守卫拒绝时主动发本包把客户端实体扳回服务端口径）
        CHANNEL.registerMessage(9, SchedulePacketsPlan.MaidTaskResyncPacket.class,
                SchedulePacketsPlan.MaidTaskResyncPacket::encode, SchedulePacketsPlan.MaidTaskResyncPacket::decode, SchedulePacketsPlan.MaidTaskResyncPacket::handle);
        CHANNEL.registerMessage(10, SchedulePacketsMaid.MaidSummonPacket.class,
                SchedulePacketsMaid.MaidSummonPacket::encode, SchedulePacketsMaid.MaidSummonPacket::decode, SchedulePacketsMaid.MaidSummonPacket::handle);
        // v1.1.0 实测二百六十八：排班生效 → 同步给打开排班书的主人（快捷设置页立即
        // 显示排班规定的模式/任务并锁定，不再停留在打开时的旧状态）
        CHANNEL.registerMessage(11, SchedulePacketsPlan.MaidStateSyncPacket.class,
                SchedulePacketsPlan.MaidStateSyncPacket::encode, SchedulePacketsPlan.MaidStateSyncPacket::decode, SchedulePacketsPlan.MaidStateSyncPacket::handle);
        // v1.1.0 实测三百四十二：排班表内单独调整女仆在家模式（不依赖排班开关——
        // 不开排班也能让女仆守家；排班开着时 home 由排班管理，按钮锁定）
        CHANNEL.registerMessage(12, SchedulePacketsPlan.HomeTogglePacket.class,
                SchedulePacketsPlan.HomeTogglePacket::encode, SchedulePacketsPlan.HomeTogglePacket::decode, SchedulePacketsPlan.HomeTogglePacket::handle);
        // v1.2.2 实测六百二十一：「全员在家」批量包（旧 index 13）已删除——列表页那个
        // 按钮整条移除（玩家裁定），单只女仆的在家开关走 index 12 的 HomeTogglePacket。
        // 索引留空不补位：注册号是双方约定的固定槽位，重排只会平白引入不一致。
        // v1.1.0 实测三百四十九（反馈："在排班表内对女仆进行改名，但是在排班表内
        // 并没有显示出来，还是原来的名字"）：改名成功 → S2C 回发新名字，GUI 同步
        // 列表行与详情页标题（旧版只改服务端，客户端列表是打开排班表那一刻的快照）
        CHANNEL.registerMessage(14, SchedulePacketsMaid.MaidRenameSyncPacket.class,
                SchedulePacketsMaid.MaidRenameSyncPacket::encode, SchedulePacketsMaid.MaidRenameSyncPacket::decode, SchedulePacketsMaid.MaidRenameSyncPacket::handle);
        // v1.2.0：快捷设置页显示女仆当前坐标 + 「去她身边」——坐标由服务端回（客户端
        // 只拿到打开排班表那一刻的快照，女仆走动后就不准了，所以每秒问一次）
        CHANNEL.registerMessage(15, SchedulePacketsMaid.MaidCoordRequestPacket.class,
                SchedulePacketsMaid.MaidCoordRequestPacket::encode, SchedulePacketsMaid.MaidCoordRequestPacket::decode, SchedulePacketsMaid.MaidCoordRequestPacket::handle);
        CHANNEL.registerMessage(16, SchedulePacketsMaid.MaidCoordPacket.class,
                SchedulePacketsMaid.MaidCoordPacket::encode, SchedulePacketsMaid.MaidCoordPacket::decode, SchedulePacketsMaid.MaidCoordPacket::handle);
        CHANNEL.registerMessage(17, SchedulePacketsMaid.MaidTeleportToPacket.class,
                SchedulePacketsMaid.MaidTeleportToPacket::encode, SchedulePacketsMaid.MaidTeleportToPacket::decode, SchedulePacketsMaid.MaidTeleportToPacket::handle);
        // 实测五百六十二：潜行+中键 工位标记（把身边 home 女仆的工作区域锚点标过去）
        CHANNEL.registerMessage(18, SchedulePacketsMaid.MarkWorkPosPacket.class,
                SchedulePacketsMaid.MarkWorkPosPacket::encode, SchedulePacketsMaid.MarkWorkPosPacket::decode, SchedulePacketsMaid.MarkWorkPosPacket::handle);
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
                    new SchedulePacketsPlan.MaidStateSyncPacket(maid.m_20148_().toString(), taskUid, sched,
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
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new SchedulePacketsPlan.MaidTaskResyncPacket(maid.m_19879_(), taskUid, sched));
        } catch (Throwable ignored) {
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
                new SchedulePacketsPlan.OpenSchedulePacket(maids, taskUids));
    }

    /* ==================== 快捷设置 ==================== */

    /* ==================== 日程加载/保存 ==================== */

    /* ==================== 批量应用 / 一键集合 / 改名（实测六十，借鉴 Maid_Roster） ==================== */

    /* ==================== 坐标显示 / 去她身边（v1.2.0） ==================== */

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
}
