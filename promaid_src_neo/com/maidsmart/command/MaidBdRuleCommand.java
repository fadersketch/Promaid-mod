package com.maidsmart.command;

import com.maidsmart.bd.MaidBdRules;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 实测 G-9【超越维度·规则名单的玩家入口】——改 {@code config/promaid_bd_rules.json}，改完即存盘。
 *
 * <pre>
 *   /maid_smart bd_rule list                       看当前规则
 *   /maid_smart bd_rule move   &lt;条目&gt;              一定搬（条目 = minecraft:coal 或 tag:c:ores）
 *   /maid_smart bd_rule keep   &lt;条目&gt;              一定不搬（例如 minecraft:golden_carrot）
 *   /maid_smart bd_rule keepN  &lt;个数&gt; &lt;条目&gt;       她背包里最多留几个，多的搬走
 *   /maid_smart bd_rule remove &lt;条目&gt;              删掉某条规则
 * </pre>
 *
 * <p>条目的两种写法：<b>裸物品 id</b>（等于 {@code item:}）与 <b>{@code tag:命名空间:路径}</b>。
 * 用了 {@code greedyString}，所以带斜杠的标签写法也能直接敲，不用加引号。
 */
public final class MaidBdRuleCommand {

    private MaidBdRuleCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("bd_rule")
                        .executes(ctx -> show(ctx.getSource()))
                        .then(Commands.literal("list").executes(ctx -> show(ctx.getSource())))
                        .then(Commands.literal("move")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addMove(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.literal("keep")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addKeep(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.literal("keepN")
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 1000000))
                                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        MaidBdRules.setKeepN(StringArgumentType.getString(ctx, "entry"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("keepAtLeast")
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 1000000))
                                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        MaidBdRules.addKeepAtLeast(
                                                                StringArgumentType.getString(ctx, "entry"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("test")
                                .then(Commands.argument("item",
                                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> test(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "entry"),
                                                        net.minecraft.commands.arguments.ResourceLocationArgument
                                                                .getId(ctx, "item").toString())))))
                        .then(Commands.literal("builtin")
                                .executes(ctx -> builtin(ctx.getSource())))
                        .then(Commands.literal("tags")
                                .then(Commands.argument("item",
                                                net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .executes(ctx -> tags(ctx.getSource(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument
                                                        .getId(ctx, "item").toString()))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.remove(StringArgumentType.getString(ctx, "entry"))))))));
    }

    /**
     * 规则写法自测：{@code /maid_smart bd_rule test <物品> <规则写法>}，例如
     * {@code /maid_smart bd_rule test minecraft:oak_log tag:minecraft:logs} ——回一句"匹配/不匹配"。
     *
     * <p>物品在前、规则在后：物品用 {@code ResourceLocationArgument}（专为 {@code minecraft:oak_log}
     * 这种写法设计），规则那一段用 greedyString，所以带斜杠的标签也能直接敲、不用加引号。
     * 标签最容易写错（公共命名空间 {@code c:*} 与原版 {@code minecraft:*} 覆盖面不一样），
     * 有这条就不用靠猜。
     */
    private static int test(CommandSourceStack src, String entry, String itemId) {
        net.minecraft.world.item.ItemStack st = com.maidsmart.bd.MaidBdCompat.stackOf(itemId);
        if (st.isEmpty()) {
            src.sendFailure(Component.literal("不认识的物品 id：" + itemId));
            return 0;
        }
        boolean hit = MaidBdRules.matches(st, entry);
        String msg = "规则「" + entry + "」" + (hit ? "匹配" : "不匹配") + " " + itemId;
        src.sendSuccess(() -> Component.literal(msg), false);
        PromaidLog.log("超越维度规则", msg);
        return 1;
    }

    /**
     * 列出一个物品**属于哪些物品标签**：{@code /maid_smart bd_rule tags minecraft:oak_log}。
     *
     * <p>为什么需要它：{@code tag:} 写法要猜标签名，而公共命名空间（{@code c:*}）与原版
     * （{@code minecraft:*}）覆盖面不一样——需求方想到的两个用途（"不同种类的石头搭路"、
     * "不同 mod 的火把照明"）都依赖标签，所以先把"这个物品有哪些标签"摊开给他看，
     * 比让他猜要省事得多。
     */
    private static int tags(CommandSourceStack src, String itemId) {
        net.minecraft.world.item.ItemStack st = com.maidsmart.bd.MaidBdCompat.stackOf(itemId);
        if (st.isEmpty()) {
            src.sendFailure(Component.literal("不认识的物品 id：" + itemId));
            return 0;
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            st.getTags().forEach(t -> list.add(t.location().toString()));
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(list);
        src.sendSuccess(() -> Component.literal(itemId + " 属于 " + list.size() + " 个物品标签："), false);
        for (String s : list) {
            src.sendSuccess(() -> Component.literal("  tag:" + s), false);
        }
        PromaidLog.log("超越维度规则", itemId + " 的标签：" + String.join("，", list));
        return 1;
    }

    /**
     * 列**内置**名单：需求方实测里最困惑的一点是"我没写规则，钻石怎么还是被搬走了"——
     * 因为内置白名单本来就覆盖挖矿/伐木/收成的原产物（钻石在 {@code c:gems} 里）。
     * 把内置名单摊开给他看，比让他猜要省事。
     */
    private static int builtin(CommandSourceStack src) {
        for (String s : com.maidsmart.bd.MaidBdDeposit.describeBuiltin()) {
            src.sendSuccess(() -> Component.literal(s), false);
        }
        return 1;
    }

    private static int show(CommandSourceStack src) {
        List<String> lines = MaidBdRules.describe();
        for (String s : lines) {
            src.sendSuccess(() -> Component.literal(s), false);
            PromaidLog.log("超越维度规则", s);
        }
        return 1;
    }

    private static int say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("超越维度规则", msg);
        return 1;
    }
}
