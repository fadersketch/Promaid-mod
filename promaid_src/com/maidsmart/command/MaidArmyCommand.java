package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * v1.5.49：批量召唤建造女仆指令 —— /maid_smart summon_builders &lt;数量&gt;
 * 新存档开局一键：N 只【已驯服（绑定执行者）+ 已切"建筑"任务】的女仆围成一圈生成，
 * 直接进入建造模式等待下达蓝图（配合Promaid 手册使用）。
 * 权限：OP（requires hasPermission(2)），防止服务器上任意玩家刷女仆。
 */
public final class MaidArmyCommand {

    private static final String TASK_UID = "maid_smart:build";

    private MaidArmyCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.m_82127_("maid_smart") // literal
                .requires(src -> src.m_6761_(2)) // 仅 OP（hasPermission）
                .then(net.minecraft.commands.Commands.m_82127_("summon_builders")
                        .then(net.minecraft.commands.Commands.m_82129_("count", // argument
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    int count = com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(ctx, "count");
                                    return summon(ctx.getSource(), count);
                                })))
                // v1.5.87：AI 记忆 per-maid 开关（调试面板门控无法打开时的命令兜底）
                .then(net.minecraft.commands.Commands.m_82127_("memory")
                        .then(net.minecraft.commands.Commands.m_82127_("on")
                                .executes(ctx -> memory(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.m_82127_("off")
                                .executes(ctx -> memory(ctx.getSource(), false)))
                        .then(net.minecraft.commands.Commands.m_82127_("status")
                                .executes(ctx -> memoryStatus(ctx.getSource())))));
    }

    /** v1.5.87：/maid_smart memory on|off —— 切换最近一只女仆的 AI 记忆开关 */
    private static int memory(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        if (!(source.m_81373_() instanceof ServerPlayer player)) {
            source.m_243053_(Component.m_237113_("\u00a7c\u8be5\u6307\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\u3002"));
            return 0;
        }
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return 0;
        }
        EntityMaid maid = nearestMaid(player);
        if (maid == null) {
            player.m_213846_(Component.m_237113_("\u00a7c\u5468\u56f432\u683c\u5185\u6ca1\u6709\u5973\u4ec6\u3002"));
            return 0;
        }
        com.maidsmart.memory.AiMemoryManager.setEnabled(maid, enabled);
        player.m_213846_(Component.m_237113_(
                (enabled ? "\u00a7a" : "\u00a77") + "\u3010AI \u8bb0\u5fc6\u3011" + maid.m_5446_().getString()
                        + "\u5df2" + (enabled ? "\u542f\u7528\uff08\u5bf9\u8bdd\u79ef\u7d2f\u540e\u81ea\u52a8\u63d0\u53d6\uff09"
                        : "\u5173\u95ed\uff08\u4e0d\u63d0\u53d6\u4e0d\u6ce8\u5165\uff09")));
        return 1;
    }

    /** v1.5.87：/maid_smart memory status —— 列出附近女仆的记忆开关状态 */
    private static int memoryStatus(net.minecraft.commands.CommandSourceStack source) {
        if (!(source.m_81373_() instanceof ServerPlayer player)) {
            return 0;
        }
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return 0;
        }
        net.minecraft.world.phys.AABB box = player.m_20191_().m_82400_(32.0);
        StringBuilder sb = new StringBuilder("\u00a7e\u5468\u56f432\u683c\u5185\u5973\u4ec6\u7684 AI \u8bb0\u5fc6\u72b6\u6001\uff1a");
        int n = 0;
        for (EntityMaid m : level.m_45976_(EntityMaid.class, box)) {
            boolean on = com.maidsmart.memory.AiMemoryManager.isEnabled(m);
            sb.append("\n").append(m.m_5446_().getString()).append(": ")
                    .append(on ? "\u00a7a\u5f00" : "\u00a77\u5173");
            n++;
        }
        if (n == 0) {
            sb.append("\n\u00a77\uff08\u65e0\uff09");
        }
        player.m_213846_(Component.m_237113_(sb.toString()));
        return 1;
    }

    /** v1.5.87：玩家 32 格内最近的女仆 */
    private static EntityMaid nearestMaid(ServerPlayer player) {
        EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityMaid m : player.m_9236_().m_45976_(EntityMaid.class,
                player.m_20191_().m_82400_(32.0))) {
            double d = m.m_20238_(player.m_20182_());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private static int summon(net.minecraft.commands.CommandSourceStack source, int count) {
        if (!(source.m_81373_() instanceof ServerPlayer player)) { // getEntity
            source.m_243053_(Component.m_237113_("\u00a7c\u8be5\u6307\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\u3002")); // sendFailure
            return 0;
        }
        if (!(player.m_9236_() instanceof ServerLevel level)) {
            return 0;
        }
        java.util.Optional<com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask> optTask =
                TaskManager.findTask(ResourceLocation.parse(TASK_UID));
        if (optTask.isEmpty()) {
            player.m_213846_(Component.m_237113_(
                    "\u00a7c\u5efa\u7b51\u4efb\u52a1\u672a\u6ce8\u518c\uff0c\u65e0\u6cd5\u53ec\u5524\u3002"));
            return 0;
        }
        com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = optTask.get();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double r = 1.2 + i * 0.28; // 圆环展开，避免重叠
            EntityMaid maid = EntityMaid.TYPE.m_20615_(level); // create(Level)
            if (maid == null) {
                continue;
            }
            maid.m_6034_(player.m_20185_() + Math.cos(angle) * r,
                    player.m_20186_(),
                    player.m_20189_() + Math.sin(angle) * r);
            // 驯服：绑定执行者为主人（m_21828_ = setOwner）
            maid.m_21828_(player);
            // 直接切"建筑"任务——出生即是建造状态，等手册下达蓝图
            maid.setTask(task);
            level.m_7967_(maid); // addFreshEntity
            spawned++;
        }
        // 同步 TLM 女仆数量上限（绕过 canAdd 逐只累加，保持"已驯服数"与实际一致，
        // 否则之后右击驯服新女仆会因计数不符被拒）
        final int total = spawned;
        player.getCapability(
                        com.github.tartaricacid.touhoulittlemaid.capability.MaidNumCapabilityProvider.MAID_NUM_CAP)
                .ifPresent(cap -> {
                    for (int i = 0; i < total; i++) {
                        try {
                            cap.add();
                        } catch (Exception ignored) {
                        }
                    }
                });
        player.m_213846_(Component.m_237113_(
                "\u00a7a\u5df2\u53ec\u5524 " + spawned + " \u53ea\u5efa\u9020\u5973\u4ec6\uff08\u5df2\u9a6f\u670d\uff0c\u4efb\u52a1\uff1a\u5efa\u7b51\uff09\u3002"
                        + "\u7528\u624b\u518c\u70b9\u51fb\u56fe\u7eb8\u5373\u5f00\u59cb\u5efa\u9020\u3002"));
        return 1;
    }
}
