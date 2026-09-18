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
 * v1.2.0 实测五百五十五【客户端重同步】：修"服务端还活着、客户端连实体都没有"的女仆。
 *
 * 【现象】玩家报"女仆打 BOSS 之后凭空消失"：F3+B 看不到碰撞箱（= 客户端实体列表里
 * 根本没有她），但服务端她一直活着、照常干活，`/tp` 也找得到；TLM 的备份恢复能
 * 让她短暂出现，之后又消失。
 *
 * 【已排除】不是隐身效果（`/effect clear` 无效）、不是距离/维度召回被挡
 * （本模组三条自动召回链都留了日志，现场一条都没有）——**她就在主人附近，只是
 * 客户端那边没有这个实体**。也就是"服务端追踪表与客户端实体表失配"。
 *
 * 【为什么会失配（Sable 侧证据）】Sable 2.0.5 改了原版实体追踪：
 * `mixin/entity/entity_tracking/TrackedEntityMixin#sable$trackSubLevelEntities`
 * 给玩家算追踪位置时，先 `SABLE_HELPER.getContaining(level, entity.position())`
 * 判断实体是否"落在某个物理 sub-level 里"，是则返回
 * `subLevel.logicalPose().transformPosition(pos)`。这条链一旦给出离谱坐标
 * （sub-level 被拆/姿态失效/实体恰好被包进体积），服务端就会判定"超出追踪距离"
 * → 给客户端发删除包，之后**再也不会重发**；而服务端实体本身毫发无损。
 *
 * 【本命令做什么】既当**诊断**又当**救急**：
 * <ol>
 *   <li>报告每只女仆的维度/坐标、主人是否同维度；</li>
 *   <li>用反射问 Sable"她是否被判在 sub-level 里、变换后的坐标是多少"（据此可判定
 *       是不是 Sable 追踪误判）；</li>
 *   <li>对**同维度的主人客户端**强制重同步：删实体 → 重新生成 → 补同步数据与装备。
 *       她立刻就能重新出现（这一步不改任何服务端状态，纯补包）。</li>
 * </ol>
 *
 * 用法：`/maid_smart resync`（自己名下的女仆）· `/maid_smart resync all`（全服）·
 * `/maid_smart resync <女仆UUID>`（跨维度点名）。权限：OP。
 */
public final class MaidResyncCommand {

    private MaidResyncCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // 挂在既有的 /maid_smart 根下面（MaidArmyCommand 已建该根，这里再挂一个子命令）
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("resync")
                        .executes(ctx -> run(ctx.getSource(), null))
                        .then(Commands.literal("all")
                                .executes(ctx -> run(ctx.getSource(), "all")))
                        .then(Commands.argument("maid", StringArgumentType.string())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "maid"))))));
    }

    private static int run(CommandSourceStack src, String arg) {
        net.minecraft.server.MinecraftServer server = src.getServer();
        ServerPlayer self = src.getPlayer();
        List<EntityMaid> targets = new ArrayList<>();
        String label;

        if (arg != null && !arg.equals("all")) {
            UUID id;
            try {
                id = UUID.fromString(arg);
            } catch (Throwable t) {
                src.sendFailure(net.minecraft.network.chat.Component.literal("UUID 格式不对：" + arg));
                return 0;
            }
            for (ServerLevel lvl : server.getAllLevels()) {
                net.minecraft.world.entity.Entity e = lvl.getEntity(id);
                if (e instanceof EntityMaid m) {
                    targets.add(m);
                    break;
                }
            }
            label = "UUID " + arg;
        } else {
            for (ServerLevel lvl : server.getAllLevels()) {
                for (net.minecraft.world.entity.Entity e : lvl.getAllEntities()) {
                    if (!(e instanceof EntityMaid m) || !m.isAlive()) {
                        continue;
                    }
                    if (arg == null && (self == null || !m.isOwnedBy(self))) {
                        continue; // 不带参数 = 只管自己名下的
                    }
                    targets.add(m);
                }
            }
            label = arg == null ? "你自己的女仆" : "全服女仆";
        }

        if (targets.isEmpty()) {
            src.sendFailure(net.minecraft.network.chat.Component.literal("没找到女仆（" + label + "）"));
            return 0;
        }

        int synced = 0;
        StringBuilder report = new StringBuilder();
        for (EntityMaid maid : targets) {
            String name = PromaidLog.nameOf(maid);
            LivingEntity owner = maid.getOwner();
            StringBuilder line = new StringBuilder("· " + name + " @ " + maid.level().dimension().location()
                    + " " + maid.blockPosition().toShortString());
            // ② Sable 侧诊断：她是否被判在 sub-level 里、变换后坐标多少
            String sable = sableContainmentReport(maid);
            if (sable != null) {
                line.append(" | Sable: ").append(sable);
            }
            // ③ 强制重同步
            if (owner instanceof ServerPlayer sp) {
                if (sp.level() != maid.level()) {
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
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(head + report), false);
        return synced;
    }


    // ================= v1.2.0 实测五百五十六：入世界自动补包 =================

    /** 待补包队列（女仆 UUID → 剩余 tick）。女仆重新入世界后延迟补一次，避开同一 tick 的生成包 */
    private static final java.util.Map<UUID, Integer> PENDING_AUTO =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * v1.2.0 实测五百五十六【自动补包】：女仆重新入世界时登记一次延迟补包。
     *
     * 【为什么需要】实测现场：女仆在战斗中"客户端消失、服务端照打"，**重启游戏就回来**。
     * 顺着代码追下去是一条"两个模组各做一半"的链：
     * <ol>
     *   <li>法术模组 `MaidSpellEventHandler.onEntityLeaveLevel`：女仆一旦**离开世界**
     *       （区块卸载 / 维度切换 / 被搬进 Sable 的 sub-level 等等），只要移除原因属于
     *       "该释放区块加载"那类，它就发一个包**让客户端把她的实体删掉**
     *       （`MaidHardRemovalProtection.allowClientRemoval`）；</li>
     *   <li>它自己的"客户端实体恢复"（`MaidEntityRestoreMessage`）**只对带锚核的女仆生效**
     *       （`handleMaidLeaveLevel` 里 `isProtectedMaid` 不成立就直接放行删除）——没带
     *       锚核的女仆被删掉后，**没有任何一方会把她补回来**；</li>
     *   <li>而她随后又被重新加回同一个 level（Sable 的 sub-level 挂载/卸载，或别的链路），
     *       原版追踪表未必会再发一次生成包（Sable 还改了追踪位置的计算：见
     *       `TrackedEntityMixin#sable$trackSubLevelEntities`）→ 结果就是"服务端好好的、
     *       客户端永远没有她"，直到玩家重登（重登会重新下发生成包）。</li>
     * </ol>
     *
     * 【本方法】入世界后延迟 {@link #AUTO_RESYNC_DELAY_TICKS} 给主人补一次"删+生成+数据+装备"。
     * 纯补包、不改服务端状态；同一 tick 的生成包不会被撞掉（延迟 20 tick）。
     */
    public static final int AUTO_RESYNC_DELAY_TICKS = 20;

    public static void scheduleAutoResync(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        PENDING_AUTO.put(maid.getUUID(), AUTO_RESYNC_DELAY_TICKS);
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
                for (ServerLevel lvl : server.getAllLevels()) {
                    net.minecraft.world.entity.Entity ent = lvl.getEntity(id);
                    if (ent instanceof EntityMaid maid && maid.isAlive()
                            && maid.getOwner() instanceof ServerPlayer owner
                            && owner.level() == maid.level()) {
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
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(maid.getId()));
            viewer.connection.send(new ClientboundAddEntityPacket(maid, 0, maid.blockPosition()));
            viewer.connection.send(new ClientboundSetEntityDataPacket(maid.getId(),
                    maid.getEntityData().getNonDefaultValues()));
            List<com.mojang.datafixers.util.Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> eq =
                    new ArrayList<>();
            eq.add(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, maid.getMainHandItem()));
            eq.add(com.mojang.datafixers.util.Pair.of(EquipmentSlot.OFFHAND, maid.getOffhandItem()));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                    eq.add(com.mojang.datafixers.util.Pair.of(slot, maid.getItemBySlot(slot)));
                }
            }
            viewer.connection.send(new ClientboundSetEquipmentPacket(maid.getId(), eq));
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
            if (!net.neoforged.fml.ModList.get().isLoaded("sable")) {
                return null;
            }
            Class<?> sableCls = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableCls.getField("HELPER").get(null);
            if (helper == null) {
                return null;
            }
            Method getContaining = helper.getClass().getMethod("getContaining",
                    net.minecraft.world.level.Level.class, net.minecraft.core.Position.class);
            Object sub = getContaining.invoke(helper, maid.level(), maid.position());
            if (sub == null) {
                return "不在 sub-level 内（追踪位置=原坐标）";
            }
            Object pose = sub.getClass().getMethod("logicalPose").invoke(sub);
            Method transform = pose.getClass().getMethod("transformPosition",
                    net.minecraft.world.phys.Vec3.class);
            Object out = transform.invoke(pose, maid.position());
            if (out instanceof net.minecraft.world.phys.Vec3 v) {
                double d = v.distanceTo(maid.position());
                return "⚠ 被判在 sub-level 内：" + sub.getClass().getSimpleName()
                        + "，追踪位置=" + String.format(java.util.Locale.ROOT, "(%.1f, %.1f, %.1f)",
                                v.x, v.y, v.z)
                        + "，与本体的距离=" + String.format(java.util.Locale.ROOT, "%.1f", d) + " 格"
                        + (d > 64.0 ? " ← 追踪位置离谱，这就是「客户端没实体」的原因" : "");
            }
            return "在 sub-level 内（无法解析变换坐标）";
        } catch (Throwable t) {
            return "Sable 诊断不可用：" + t.getClass().getSimpleName();
        }
    }
}
