package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.bd.MaidBdCompat;
import com.maidsmart.bd.MaidBdDeposit;
import com.maidsmart.bd.MaidBdOverflow;
import com.maidsmart.bd.MaidBdFlush;
import com.maidsmart.bd.MaidBdRestock;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 超越维度（BeyondDimensions）兼容的**诊断探针**——只读，先回答"通不通"，再谈"存取"。
 *
 * <pre>
 *   /maid_smart bd_probe [女仆]                —— 反射是否正常、她主人有没有网络、主网络里都有什么
 *   /maid_smart bd_query &lt;物品id&gt; [女仆]      —— 主人的网络里这个物品有多少（O(1)，模拟取一次）
 * </pre>
 *
 * <p>为什么先做只读：维度的网络是**按玩家**的（入口全是 {@code Player}，方块侧才有 capability），
 * 所以女仆能用的是**她主人的**网络；"她主人到底有没有网络、网络里有什么格式的 key"这件事
 * 得先在实机里看一眼，才能决定存取那一层的形状。
 */
public final class MaidBdProbeCommand {

    private MaidBdProbeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        MaidBdDeposit.ensureHooked();
        MaidBdOverflow.ensureHooked();
        MaidBdRestock.ensureHooked();
        MaidBdFlush.ensureHooked();
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("bd_probe")
                        .executes(ctx -> probe(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> probe(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_deposit")
                        .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                .executes(ctx -> depositOn(ctx.getSource(), null,
                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on")))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(ctx -> depositOn(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "on"))))))
                .then(Commands.literal("bd_deposit_dry")
                        .executes(ctx -> depositDry(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> depositDry(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_deposit_now")
                        .executes(ctx -> depositNow(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> depositNow(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_restock_dry")
                        .executes(ctx -> restockDry(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> restockDry(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_restock_now")
                        .executes(ctx -> restockNow(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> restockNow(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_flush_dry")
                        .executes(ctx -> flushDry(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> flushDry(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_flush_now")
                        .executes(ctx -> flushNow(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> flushNow(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("bd_query")
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .executes(ctx -> query(ctx.getSource(), null,
                                        net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item")))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(ctx -> query(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item")))))));
    }

    private static int probe(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("超越维度反射：" + (MaidBdCompat.available() ? "正常" : "没解析到（没装或版本类名不符）"));
        lines.add("功能总开关 misc.bdStorage = " + MaidBdCompat.enabled()
                + "（默认关；自动回收/补货/冲刷都受它管，改 config/promaid-common.toml 或游戏内面板）");
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null) {
            lines.add("她没有主人 —— 网络是按玩家挂的，所以这条路只能在单机/带主人的服务器上用");
        } else {
            lines.add("主人：" + owner.getName().getString()
                    + " 网络数=" + MaidBdCompat.netCount(owner)
                    + " 有网络=" + MaidBdCompat.hasAnyNet(owner));
            Object net = MaidBdCompat.primaryNet(owner);
            lines.add("主网络：" + MaidBdCompat.netDescribe(net)
                    + " 内容种类=" + MaidBdCompat.contentKinds(net));
            for (MaidBdCompat.Line l : MaidBdCompat.contents(net, 12)) {
                lines.add("  · " + l.id() + " × " + l.amount());
            }
            lines.add("产出回收开关 = " + MaidBdDeposit.isOn(maid)
                    + "（bd_deposit true 打开；bd_deposit_dry 先看名单）");
            lines.add("溢出容量：" + MaidBdOverflow.capacityReport(maid)
                    + "（她背包与额外容器都满时，产物会直接入库）");
        }
        for (String s : lines) {
            src.sendSuccess(() -> Component.literal(s), false);
            PromaidLog.log("超越维度探针", maid.getName().getString() + " " + s);
        }
        return 1;
    }

    private static int query(CommandSourceStack src, net.minecraft.world.entity.Entity picked, ResourceLocation rl) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String itemId = rl.toString();
        if (!BuiltInRegistries.ITEM.containsKey(rl)) {
            src.sendFailure(Component.literal("不认识的物品 id：" + itemId));
            return 0;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null) {
            src.sendFailure(Component.literal("她没有主人，没有可用的网络"));
            return 0;
        }
        Object net = MaidBdCompat.primaryNet(owner);
        long n = MaidBdCompat.countOf(net, new ItemStack(BuiltInRegistries.ITEM.get(rl)));
        String line = "查询 " + itemId + " → " + (n < 0 ? "查询失败" : n + " 个");
        src.sendSuccess(() -> Component.literal(line), false);
        PromaidLog.log("超越维度探针", maid.getName().getString() + " " + line);
        return 1;
    }

    /** 只看：她精妙背包（额外容器）里有哪些产物会被冲刷进库。 */
    private static int flushDry(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        List<String> list = MaidBdFlush.preview(maid);
        if (list.isEmpty()) {
            src.sendSuccess(() -> Component.literal("她的额外容器里没有可冲刷的产物"
                    + "（也可能她根本没戴精妙背包：TLM 只认插在饰品栏里的）"), false);
            return 1;
        }
        for (String s : list) {
            src.sendSuccess(() -> Component.literal("  " + s), false);
            PromaidLog.log("超越维度冲刷", maid.getName().getString() + " dry " + s);
        }
        return 1;
    }

    /** 立刻冲刷一次（把精妙背包里的产物推进库）。 */
    private static int flushNow(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        if (!MaidBdCompat.available()) {
            src.sendFailure(Component.literal("超越维度反射没解析到"));
            return 0;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.hasAnyNet(owner)) {
            src.sendFailure(Component.literal("她没有主人，或她主人没有网络"));
            return 0;
        }
        List<String> res = MaidBdFlush.flush(maid, MaidBdCompat.primaryNet(owner), false);
        src.sendSuccess(() -> Component.literal(res.isEmpty() ? "没有可冲刷的产物（或她没有额外容器）"
                : "本轮：" + String.join("；", res)), true);
        return 1;
    }

    /** 只看不补：报告每条补货规则"她有 / 目标 / 需要补多少"。 */
    private static int restockDry(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        List<String> list = MaidBdRestock.preview(maid);
        for (String s : list) {
            src.sendSuccess(() -> Component.literal("  " + s), false);
            PromaidLog.log("超越维度补货", maid.getName().getString() + " dry " + s);
        }
        return 1;
    }

    /** 立刻补一次（验收用，不用等自动节奏）。 */
    private static int restockNow(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        if (!MaidBdCompat.available()) {
            src.sendFailure(Component.literal("超越维度反射没解析到"));
            return 0;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.hasAnyNet(owner)) {
            src.sendFailure(Component.literal("她没有主人，或她主人没有网络"));
            return 0;
        }
        List<String> res = MaidBdRestock.restock(maid, MaidBdCompat.primaryNet(owner), false);
        src.sendSuccess(() -> Component.literal(res.isEmpty() ? "没有需要补的" : "本轮：" + String.join("；", res)), true);
        return 1;
    }

    /** 开/关"产物回收"（默认关；存 persistentData）。 */
    private static int depositOn(CommandSourceStack src, net.minecraft.world.entity.Entity picked, boolean on) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        MaidBdDeposit.setOn(maid, on);
        src.sendSuccess(() -> Component.literal("产出回收已" + (on ? "开启" : "关闭")
                + "（先把产物收进她背包，再自动存进你主网络；bd_deposit_dry 可先看名单）"), true);
        return 1;
    }

    /** 只看不搬：列出她背包里"会被回收"的东西。 */
    private static int depositDry(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        List<String> list = MaidBdDeposit.preview(maid);
        if (list.isEmpty()) {
            src.sendSuccess(() -> Component.literal("她背包（可用槽位）里没有东西"), false);
            return 1;
        }
        // 【实测 G-6 修】整份名单拼成一条会被聊天长度截断（无头实测：只显示到第 6 条就没了）。
        // 所以逐条发；日志照旧一条一条写，方便对账。
        int moved = 0;
        for (String s : list) {
            if (s.contains("→ 会搬")) {
                moved++;
            }
        }
        final int movedFinal = moved;
        src.sendSuccess(() -> Component.literal("背包可用槽位 " + list.size() + " 格，其中会被回收 "
                + movedFinal + " 格（每格判定如下）"), false);
        for (String s : list) {
            src.sendSuccess(() -> Component.literal("  " + s), false);
            PromaidLog.log("超越维度存入", maid.getName().getString() + " dry " + s);
        }
        return 1;
    }

    /** 立刻搬一次（用于验收，不用等自动扫描）。 */
    private static int depositNow(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        if (!MaidBdCompat.available()) {
            src.sendFailure(Component.literal("超越维度反射没解析到"));
            return 0;
        }
        Player owner = MaidBdCompat.ownerOf(maid);
        if (owner == null || !MaidBdCompat.hasAnyNet(owner)) {
            src.sendFailure(Component.literal("她没有主人，或她主人没有网络"));
            return 0;
        }
        List<String> res = MaidBdDeposit.sweep(maid, MaidBdCompat.primaryNet(owner), false);
        src.sendSuccess(() -> Component.literal(res.isEmpty() ? "没有可回收的产物"
                : "本次回收：" + String.join("；", res)), true);
        return 1;
    }

    private static EntityMaid asMaid(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        if (picked instanceof EntityMaid m) {
            return m;
        }
        try {
            AABB box = src.getEntity() != null
                    ? src.getEntity().getBoundingBox().inflate(64.0D)
                    : new AABB(src.getPosition().x - 64, src.getPosition().y - 64, src.getPosition().z - 64,
                            src.getPosition().x + 64, src.getPosition().y + 64, src.getPosition().z + 64);
            return src.getLevel().getEntitiesOfClass(EntityMaid.class, box).stream().findFirst().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
