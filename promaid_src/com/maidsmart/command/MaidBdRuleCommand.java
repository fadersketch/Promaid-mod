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
 *
 * <p>【1.20.1 差异】命令树走 SRG（{@code m_82127_}=literal、{@code m_82129_}=argument、
 * {@code m_6761_}=hasPermission、{@code m_288197_}=sendSuccess、{@code m_81352_}=sendFailure）；
 * {@code test} / {@code tags} 的物品参数不用 {@code ResourceLocationArgument}，改 greedyString +
 * try/catch 解析（与 {@code bd_query} 同口径）。
 */
public final class MaidBdRuleCommand {

    private MaidBdRuleCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.m_82127_("maid_smart")
                .requires(src -> src.m_6761_(2))
                .then(Commands.m_82127_("bd_rule")
                        .executes(ctx -> show(ctx.getSource()))
                        .then(Commands.m_82127_("list").executes(ctx -> show(ctx.getSource())))
                        .then(Commands.m_82127_("move")
                                .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addMove(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.m_82127_("keep")
                                .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.addKeep(StringArgumentType.getString(ctx, "entry"))))))
                        .then(Commands.m_82127_("keepN")
                                .then(Commands.m_82129_("count", IntegerArgumentType.integer(0, 1000000))
                                        .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        MaidBdRules.setKeepN(StringArgumentType.getString(ctx, "entry"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.m_82127_("keepAtLeast")
                                .then(Commands.m_82129_("count", IntegerArgumentType.integer(0, 1000000))
                                        .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> say(ctx.getSource(),
                                                        MaidBdRules.addKeepAtLeast(
                                                                StringArgumentType.getString(ctx, "entry"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.m_82127_("test")
                                .then(Commands.m_82129_("item", ItemIdArgument.item())
                                        .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                                .executes(ctx -> test(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "entry"),
                                                        ItemIdArgument.get(ctx, "item"))))))
                        .then(Commands.m_82127_("builtin")
                                .executes(ctx -> builtin(ctx.getSource())))
                        .then(Commands.m_82127_("tags")
                                .then(Commands.m_82129_("item", StringArgumentType.greedyString())
                                        .executes(ctx -> tags(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "item")))))
                        .then(Commands.m_82127_("remove")
                                .then(Commands.m_82129_("entry", StringArgumentType.greedyString())
                                        .executes(ctx -> say(ctx.getSource(),
                                                MaidBdRules.remove(StringArgumentType.getString(ctx, "entry"))))))));
    }

    /**
     * 规则写法自测：{@code /maid_smart bd_rule test <物品> <规则写法>}，例如
     * {@code /maid_smart bd_rule test minecraft:oak_log tag:minecraft:logs} ——回一句"匹配/不匹配"。
     *
     * <p>【1.20.1 改写】物品在前、规则在后；物品与规则都用字符串 token，物品做 try/catch 解析，
     * 规则原样传给 {@link MaidBdRules#matches}（标签最容易写错，有这条就不用靠猜）。
     */
    private static int test(CommandSourceStack src, String entry, String itemId) {
        net.minecraft.world.item.ItemStack st = com.maidsmart.bd.MaidBdCompat.stackOf(itemId);
        if (st.m_41619_()) {
            src.m_81352_(Component.m_237113_("不认识的物品 id：" + itemId));
            return 0;
        }
        boolean hit = MaidBdRules.matches(st, entry);
        String msg = "规则「" + entry + "」" + (hit ? "匹配" : "不匹配") + " " + itemId;
        src.m_288197_(() -> Component.m_237113_(msg), false);
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
        if (st.m_41619_()) {
            src.m_81352_(Component.m_237113_("不认识的物品 id：" + itemId));
            return 0;
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            st.m_204131_().forEach(t -> list.add(t.f_203868_().toString()));
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(list);
        src.m_288197_(() -> Component.m_237113_(itemId + " 属于 " + list.size() + " 个物品标签："), false);
        for (String s : list) {
            src.m_288197_(() -> Component.m_237113_("  tag:" + s), false);
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
            src.m_288197_(() -> Component.m_237113_(s), false);
        }
        return 1;
    }

    private static int show(CommandSourceStack src) {
        List<String> lines = MaidBdRules.describe();
        for (String s : lines) {
            src.m_288197_(() -> Component.m_237113_(s), false);
            PromaidLog.log("超越维度规则", s);
        }
        return 1;
    }

    private static int say(CommandSourceStack src, String msg) {
        src.m_288197_(() -> Component.m_237113_(msg), true);
        PromaidLog.log("超越维度规则", msg);
        return 1;
    }

    /**
     * 物品 id 参数：读一个不含空格的 token（{@code :}、{@code _} 都允许），
     * 这样 {@code minecraft:oak_log} 能整体吃进来。本树不用 {@code ResourceLocationArgument}，
     * 也不方便用 greedyString（那会吞掉后面的规则写法），所以自带一个小参数类型。
     */
    private static final class ItemIdArgument
            implements com.mojang.brigadier.arguments.ArgumentType<String> {
        private static final ItemIdArgument INSTANCE = new ItemIdArgument();

        private ItemIdArgument() {
        }

        static ItemIdArgument item() {
            return INSTANCE;
        }

        static String get(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, String name) {
            return ctx.getArgument(name, String.class);
        }

        @Override
        public String parse(com.mojang.brigadier.StringReader reader)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            int start = reader.getCursor();
            while (reader.canRead() && reader.peek() != ' ') {
                reader.skip();
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public java.util.Collection<String> getExamples() {
            return java.util.List.of("minecraft:oak_log", "minecraft:coal");
        }
    }
}
