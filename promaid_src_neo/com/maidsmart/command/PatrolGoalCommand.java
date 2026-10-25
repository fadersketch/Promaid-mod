package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * R1114【/set goal &lt;x&gt; &lt;y&gt; &lt;z&gt; [女仆]】——玩家自己用指令指定一个目标点。
 *
 * <p>它做两件互相独立、都"能做就做"的事：
 * <ol>
 *   <li>手上拿着巡逻航图 → 把这个坐标追加成红色目标点（与中键标记落进同一本书，自动命名「目标N」）；</li>
 *   <li>当前处于"女仆载着你"的四种状态之一（或显式点了一只自己的女仆）→ 直接让她飞过去，
 *       走的就是航图「飞过去」那条 {@code MaidTravelTarget} 链路。</li>
 * </ol>
 *
 * <p>【为什么不挂 /maid_smart 下面】那条根命令是 OP 专属（{@code requires hasPermission(2)}）——
 * 而这一条是给普通玩家用的。它只校验"女仆是你自己的 / 她在你身边"，没有 OP 要求。
 */
public final class PatrolGoalCommand {

    private PatrolGoalCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("set").requires(src -> src.getEntity() instanceof ServerPlayer);
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goal =
                Commands.literal("goal");
        var zNode = Commands.argument("z",
                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg());
        zNode.executes(ctx -> setGoal(ctx.getSource(),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y"),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                null));
        var maidNode = Commands.argument("maid",
                net.minecraft.commands.arguments.EntityArgument.entity());
        maidNode.executes(ctx -> setGoal(ctx.getSource(),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y"),
                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid")));
        zNode.then(maidNode);
        var yNode = Commands.argument("y",
                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg()).then(zNode);
        var xNode = Commands.argument("x",
                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg()).then(yNode);
        goal.then(xNode);
        root.then(goal);
        dispatcher.register(root);
    }

    private static int setGoal(CommandSourceStack source, double x, double y, double z,
                               net.minecraft.world.entity.Entity explicit) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Throwable t) {
            source.sendSuccess(() -> Component.literal("§c这条命令只能由玩家执行"), false);
            return 0;
        }
        Vec3 pos = new Vec3(x, y, z);
        String dim = dimensionOf(player);
        // ① 手上有航图 → 记成一个目标点（与中键标记同一本书、同一套 NBT）
        boolean saved = false;
        Boolean off = chartOffHand(player);
        if (off != null) {
            ItemStack stack = heldChart(player, off);
            com.maidsmart.patrol.PatrolBook book = com.maidsmart.patrol.PatrolChartData.readBook(stack);
            com.maidsmart.patrol.PatrolTarget t = book.addTarget(pos, dim);
            if (t == null) {
                player.sendSystemMessage(Component.literal(
                        "§e目标点已满（上限 " + com.maidsmart.patrol.PatrolBook.MAX_TARGETS
                                + " 个），这次只让她飞过去，没有记进航图"));
            } else {
                com.maidsmart.patrol.PatrolChartData.writeBook(stack, book);
                com.maidsmart.patrol.PatrolNetworking.sendBook(player, stack, off, false);
                saved = true;
                player.sendSystemMessage(Component.literal(String.format(
                        "§c◆ 已记为目标点「%s」(%.1f, %.1f, %.1f)", t.displayName(), x, y, z)));
            }
        }
        // ② 有可执行的女仆 → 直接起飞
        EntityMaid maid = null;
        if (explicit != null) {
            if (!(explicit instanceof EntityMaid m)) {
                player.sendSystemMessage(Component.literal("§c目标不是女仆"));
                return 0;
            }
            maid = m;
            if (maid.getOwner() != player) {
                player.sendSystemMessage(Component.literal("§c这只女仆不是你的"));
                return 0;
            }
            if (!maid.isAlive() || maid.level() != player.level()) {
                player.sendSystemMessage(Component.literal("§c她不在你身边（或已经不在这个世界）"));
                return 0;
            }
        } else {
            maid = com.maidsmart.patrol.PatrolNetworking.findEligibleMaid(player);
        }
        if (maid == null) {
            if (saved) {
                player.sendSystemMessage(Component.literal(
                        "§7目标点已存进航图；要让她飞，需和她同乘扫帚/直升机（她驾驶），"
                                + "或用武装拴绳把自己拴在能飞的女仆下面"));
            } else {
                player.sendSystemMessage(Component.literal(
                        "§c现在没有可执行的女仆——拿上巡逻航图，或和她同乘扫帚/直升机/被拴绳带着"));
            }
            return saved ? 1 : 0;
        }
        com.maidsmart.patrol.MaidTravelTarget.set(maid, pos);
        player.sendSystemMessage(Component.literal(String.format(
                "§a✈ %s 开始飞往 (%.1f, %.1f, %.1f)",
                com.maidsmart.tool.PromaidLog.nameOf(maid), x, y, z)));
        return 1;
    }

    private static ItemStack heldChart(ServerPlayer player, boolean offHand) {
        try {
            ItemStack s = offHand ? player.getOffhandItem() : player.getMainHandItem();
            return com.maidsmart.patrol.PatrolChartKit.isChart(s) ? s : null;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolGoalCommand", ignored);
            return null;
        }
    }

    private static Boolean chartOffHand(ServerPlayer player) {
        if (heldChart(player, false) != null) {
            return Boolean.FALSE;
        }
        if (heldChart(player, true) != null) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static String dimensionOf(ServerPlayer player) {
        try {
            return player.level().dimension().location().toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PatrolGoalCommand", ignored);
            return "";
        }
    }
}
