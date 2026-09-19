package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v1.2.0 实测五百六十五（PR #10 社区贡献，Forge 树移植）：客户端实体重同步。
 * 修"服务端还活着、客户端连实体都没有"的女仆。
 *
 * 【现象】玩家报"女仆打 BOSS 之后凭空消失"：F3+B 看不到碰撞箱（= 客户端实体列表里
 * 根本没有她），但服务端她一直活着、照常干活。也就是"服务端追踪表与客户端实体表失配"。
 *
 * 【为什么会失配（Sable 侧证据，社区取证）】Sable 2.0.5 改了原版实体追踪：
 * TrackedEntityMixin#sable$trackSubLevelEntities 给玩家算追踪位置时，先经
 * SABLE_HELPER.getContaining(level, entity.position()) 判断实体是否落在某个物理
 * sub-level 里。这条链一旦给出离谱坐标，服务端会判定"超出追踪距离"→ 给客户端发
 * 删除包，之后不再重发；而服务端实体本身毫发无损。叠加法术模组对"没带锚核"女仆
 * 离场时"只删不补"客户端实体的行为，"客户端没有她"就固化了。
 *
 * 【本命令做什么】既当诊断又当救急：
 * <ol>
 *   <li>报告每只女仆的维度/坐标、主人是否同维度；</li>
 *   <li>用反射问 Sable"她是否被判在 sub-level 里、变换后的坐标是多少"；</li>
 *   <li>对同维度的主人客户端强制重同步：删实体 → 重新生成 → 补同步数据与装备。
 *       不改任何服务端状态，纯补包。</li>
 * </ol>
 *
 * 用法：/maid_smart resync（自己名下的女仆）· /maid_smart resync all（全服）·
 * /maid_smart resync &lt;女仆UUID&gt;（跨维度点名）。权限：OP。
 */
public final class MaidResyncCommand {

    private MaidResyncCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // 挂在既有的 /maid_smart 根下面（MaidArmyCommand 已建该根，这里再挂一个子命令）
        dispatcher.register(Commands.m_82127_("maid_smart")
                .requires(src -> src.m_6761_(2))
                .then(Commands.m_82127_("resync")
                        .executes(ctx -> run(ctx.getSource(), null))
                        .then(Commands.m_82127_("all")
                                .executes(ctx -> run(ctx.getSource(), "all")))
                        .then(Commands.m_82129_("maid", StringArgumentType.string())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "maid"))))));
    }

    private static int run(CommandSourceStack src, String arg) {
        net.minecraft.server.MinecraftServer server = src.m_81377_();
        ServerPlayer self = src.m_81373_() instanceof ServerPlayer sp ? sp : null;
        List<EntityMaid> targets = new ArrayList<>();
        String label;

        if (arg != null && !arg.equals("all")) {
            UUID id;
            try {
                id = UUID.fromString(arg);
            } catch (Throwable t) {
                src.m_243053_(net.minecraft.network.chat.Component.m_237113_("UUID 格式不对：" + arg));
                return 0;
            }
            for (ServerLevel lvl : server.m_129785_()) {
                net.minecraft.world.entity.Entity e = lvl.m_8791_(id);
                if (e instanceof EntityMaid m) {
                    targets.add(m);
                    break;
                }
            }
            label = "UUID " + arg;
        } else {
            for (ServerLevel lvl : server.m_129785_()) {
                for (net.minecraft.world.entity.Entity e : lvl.m_8583_()) {
                    if (!(e instanceof EntityMaid m) || !m.m_6084_()) {
                        continue;
                    }
                    if (arg == null && (self == null || !m.m_21830_(self))) {
                        continue; // 不带参数 = 只管自己名下的
                    }
                    targets.add(m);
                }
            }
            label = arg == null ? "你自己的女仆" : "全服女仆";
        }

        if (targets.isEmpty()) {
            src.m_243053_(net.minecraft.network.chat.Component.m_237113_("没找到女仆（" + label + "）"));
            return 0;
        }

        int synced = 0;
        StringBuilder report = new StringBuilder();
        for (EntityMaid maid : targets) {
            String name = PromaidLog.nameOf(maid);
            LivingEntity owner = maid.m_269323_();
            StringBuilder line = new StringBuilder("· " + name + " @ "
                    + maid.m_9236_().m_46472_().m_135782_()
                    + " " + maid.m_20183_().m_123341_() + "," + maid.m_20183_().m_123342_()
                    + "," + maid.m_20183_().m_123343_());
            // ② Sable 侧诊断：她是否被判在 sub-level 里、变换后坐标多少
            String sable = sableContainmentReport(maid);
            if (sable != null) {
                line.append(" | Sable: ").append(sable);
            }
            // ③ 强制重同步
            if (owner instanceof ServerPlayer sp) {
                if (sp.m_9236_() != maid.m_9236_()) {
                    line.append(" | 主人不在同维度（跳过重同步，跨维度本就该看不见）");
                } else {
                    resyncTo(sp, maid);
                    synced++;
                    line.append(" | 已重同步");
                }
            } else {
                line.append(" | 主人不在线（无客户端可同步）");
            }
            report.append(line).append("\n");
            PromaidLog.log("重同步", line.toString());
        }
        String head = "客户端重同步：" + label + " 共 " + targets.size() + " 只，实际重同步 "
                + synced + " 只\n";
        String finalReport = head + report;
        src.m_288197_(() -> net.minecraft.network.chat.Component.m_237113_(finalReport), false);
        return synced;
    }

    // ================= 实测五百六十六：入世界自动补包 =================

    /** 待补包队列（女仆 UUID → 剩余 tick）。女仆重新入世界后延迟补一次，避开同一 tick 的生成包 */
    private static final java.util.Map<UUID, Integer> PENDING_AUTO =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 自动补包：女仆重新入世界时登记一次延迟补包。
     *
     * 【为什么需要】实测现场：女仆在战斗中"客户端消失、服务端照打"，重启游戏就回来。
     * 是"两个模组各做一半"的链：①法术模组在女仆离场（区块卸载/维度切换等）时对
     * 没带锚核的女仆发"让客户端删掉实体"的通知，且自己的恢复只覆盖带锚核的；
     * ②她随后被重新加回同一维度时，原版追踪表（尤其被 Sable 改过追踪位置计算后）
     * 未必再发一次生成包 → 没有任何一方把她补回来。
     * 入世界/离场两个窗口都登记一次，补齐"删+生成+数据+装备"四个包。
     */
    public static final int AUTO_RESYNC_DELAY_TICKS = 20;

    public static void scheduleAutoResync(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        PENDING_AUTO.put(maid.m_20148_(), AUTO_RESYNC_DELAY_TICKS);
    }

    /** 由 ProMaidExtension 的 ServerTick 每 tick 调一次（空队列零开销） */
    public static void tickAutoResync(net.minecraft.server.MinecraftServer server) {
        if (PENDING_AUTO.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<UUID, Integer>> it = PENDING_AUTO.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            it.remove();
            UUID id = e.getKey();
            try {
                for (ServerLevel lvl : server.m_129785_()) {
                    net.minecraft.world.entity.Entity ent = lvl.m_8791_(id);
                    if (ent instanceof EntityMaid maid && maid.m_6084_()
                            && maid.m_269323_() instanceof ServerPlayer owner
                            && owner.m_9236_() == maid.m_9236_()) {
                        resyncTo(owner, maid);
                        PromaidLog.log("重同步", PromaidLog.nameOf(maid)
                                + " 重新入世界 → 已给主人补一次实体包（防客户端实体丢失）");
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 服务端侧"强制重同步"：删 → 生成 → 数据 → 装备。
     * 不改服务端任何状态（不动追踪表、不动实体），纯粹把客户端缺的那几包补齐；
     * 如果客户端本来就有她，删+加也只是一次可见的瞬时重建，无害。
     */
    private static void resyncTo(ServerPlayer viewer, EntityMaid maid) {
        try {
            viewer.f_8906_.m_9829_(new ClientboundRemoveEntitiesPacket(maid.m_19879_()));
            viewer.f_8906_.m_9829_(new ClientboundAddEntityPacket(maid, 0, maid.m_20183_()));
            // 【实测五百七十三：这里必须用 getNonDefaultValues（m_252804_）而不是 packDirty！】
            // AddEntity 在客户端新建的是一只"全新实体"（同步数据全是默认值），所以必须把
            // **全部非默认值**补过去——这正是原版生成实体时发的那个包
            // （ClientboundSetEntityDataPacket(id, getEntityData().getNonDefaultValues())）。
            // 旧版误用了 m_135378_（= packDirty：只返回"自上次同步以来变化过"的值，且会清脏标记），
            // 于是重同步时通常一个值都不发 → 客户端那只新实体保持默认数据 → **模型回落成默认
            // （灵梦）**，服务端数据完全正常，所以"重进游戏就好了"。这正是反馈的
            // "1.20.1 魂符放出后模型变了"的根因（1.21.1 树本来就是 getNonDefaultValues，
            // 是移植到 1.20.1 时的 SRG 映射错误）。
            List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> vals =
                    maid.m_20088_().m_252804_(); // getNonDefaultValues：不动脏标记
            if (vals != null) {
                viewer.f_8906_.m_9829_(new ClientboundSetEntityDataPacket(maid.m_19879_(), vals));
            }
            List<com.mojang.datafixers.util.Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> eq =
                    new ArrayList<>();
            eq.add(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, maid.m_21205_()));
            eq.add(com.mojang.datafixers.util.Pair.of(EquipmentSlot.OFFHAND, maid.m_21206_()));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.m_20743_() == EquipmentSlot.Type.ARMOR) {
                    eq.add(com.mojang.datafixers.util.Pair.of(slot, maid.m_6844_(slot)));
                }
            }
            viewer.f_8906_.m_9829_(new ClientboundSetEquipmentPacket(maid.m_19879_(), eq));
        } catch (Throwable t) {
            PromaidLog.log("重同步", "补包失败：" + t);
        }
    }

    /**
     * 反射问 Sable："她是否被判在某个 sub-level 里？变换后的坐标是多少？"
     * 只在装了 Sable 时有意义；任何异常都返回 null（软兼容，绝不影响重同步本身）。
     */
    private static String sableContainmentReport(EntityMaid maid) {
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("sable")) {
                return null;
            }
            Class<?> sableCls = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableCls.getField("HELPER").get(null);
            if (helper == null) {
                return null;
            }
            Method getContaining = helper.getClass().getMethod("getContaining",
                    net.minecraft.world.level.Level.class, net.minecraft.core.Position.class);
            Object sub = getContaining.invoke(helper, maid.m_9236_(), maid.m_20182_());
            if (sub == null) {
                return "不在 sub-level 内（追踪位置=原坐标）";
            }
            Object pose = sub.getClass().getMethod("logicalPose").invoke(sub);
            Method transform = pose.getClass().getMethod("transformPosition",
                    net.minecraft.world.phys.Vec3.class);
            Object out = transform.invoke(pose, maid.m_20182_());
            if (out instanceof net.minecraft.world.phys.Vec3 v) {
                double d = v.m_82554_(maid.m_20182_());
                return "⚠ 被判在 sub-level 内：" + sub.getClass().getSimpleName()
                        + "，追踪位置=" + String.format(java.util.Locale.ROOT, "(%.1f, %.1f, %.1f)",
                                v.f_82479_, v.f_82480_, v.f_82481_)
                        + "，与本体的距离=" + String.format(java.util.Locale.ROOT, "%.1f", d) + " 格"
                        + (d > 64.0 ? " ← 追踪位置离谱，这就是「客户端没实体」的原因" : "");
            }
            return "在 sub-level 内（无法解析变换坐标）";
        } catch (Throwable t) {
            return "Sable 诊断不可用：" + t.getClass().getSimpleName();
        }
    }
}
