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
        dispatcher.register(net.minecraft.commands.Commands.literal("maid_smart") // literal
                .requires(src -> src.hasPermission(2)) // 仅 OP（hasPermission）
                .then(net.minecraft.commands.Commands.literal("summon_builders")
                        .then(net.minecraft.commands.Commands.argument("count", // argument
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    int count = com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(ctx, "count");
                                    return summon(ctx.getSource(), count);
                                })))
                // v1.5.87：AI 记忆 per-maid 开关（调试面板门控无法打开时的命令兜底）
                .then(net.minecraft.commands.Commands.literal("memory")
                        .then(net.minecraft.commands.Commands.literal("on")
                                .executes(ctx -> memory(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("off")
                                .executes(ctx -> memory(ctx.getSource(), false)))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> memoryStatus(ctx.getSource()))))
                // v1.2.0 实测五百四十五：投喂回血的**可验证入口**。
                // 【为什么必须有】"女仆喂女仆"发生在两个实体之间、不产生任何原版事件，
                // 而互助链要求"被喂的那只有主人且主人在线"——专用服务器上没有玩家，
                // 这条链**结构上无法端到端触发**（前两轮回归都卡在这里）。所以留一条
                // 控制台可用的调试命令：对最近的女仆执行"一份食物 → 原版进食 → TLM
                // 餐食口径回血"（与 MaidMealBridge 完全同一条路径），把回血量打进日志。
                .then(net.minecraft.commands.Commands.literal("feedtest")
                        .then(net.minecraft.commands.Commands.argument("item", // argument
                                        // 【必须是 greedyString()】Brigadier 三种字符串参数：
                                        // `word()` 只接受 [A-Za-z0-9_]（**不含冒号**，
                                        //   `minecraft:carrot` 会断在冒号上报 trailing data）；
                                        // `string()` 是 QUOTED 类型（要求输入带引号）；
                                        // 只有 `greedyString()` 能吃下裸的 `minecraft:carrot`。
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> feedTest(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType
                                                .getString(ctx, "item")))))
                // v1.2.2 实测六百〇八【飞行跟随的可验证入口】。
                // 【为什么必须有】这条链的 target 是**在线主人实体**（{@code maid.getOwner()}
                // 走 PlayerList，专用服务器上没有玩家就是 null），与"女仆喂女仆"（实测五百四十五）
                // 完全同一类困境：结构上无法端到端触发。照那条的先例留一条控制台可用的命令——
                // 给指定女仆挂一个"替代主人"，走的仍是 MaidFlightFollowBehavior **同一套**判定与
                // 飞行链路，只替换目标来源（就为了能在专用服务器上验证"她真的会起飞追人"）。
                // 【前提】flightFollow.enabled 开关必须是开的（默认关），否则行为压根不启动。
                .then(net.minecraft.commands.Commands.literal("flyfollow")
                        .then(net.minecraft.commands.Commands.literal("clear")
                                .executes(ctx -> flightFollowClear(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.argument("target", // argument
                                        net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(ctx -> flightFollow(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument
                                                .getEntity(ctx, "target"), null))
                                // 【第三个参数是可选的指定女仆】不给就取"离执行点最近的一只"——
                                // 但测试/多女仆场景下"最近"可能不是你心里那只（本批实测踩过：
                                // 测试世界里堆着前几批留下的女仆，命令挂到了别人身上，现象是
                                // "她怎么不飞"），所以留一个显式指定的口子。
                                .then(net.minecraft.commands.Commands.argument("maid", // argument
                                                net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> flightFollow(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .getEntity(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .getEntity(ctx, "maid"))))))
                // v1.2.2 实测六百一十四【飞行跟随的坐标档】——取自粉丝 Roderick32 的「鞘翅赶路」分支。
                // 【两个用途】① 实用：想让她"飞去那边的高台/浮空岛"时，不必先跑一趟再等她飞丢；
                // ② 排查：专用服务器上没有在线主人（{@code getOwner()} 恒为 null），这条链结构上
                // 无法自动触发（与"女仆喂女仆"同一类困境）——给一个坐标就能把整条起飞链路跑起来。
                // 【本仓没有照搬他那套的哪一半】他的命令只是"挂个请求"，由他自己那套平行飞行状态机
                // 消费；本仓的飞行跟随已经把这套链路做全了（实测六百〇八～六百一十三），所以这里
                // 只做"设目标"这一件事（与上面 flyfollow 同形），判定/起飞/补推/收手全部复用。
                .then(net.minecraft.commands.Commands.literal("elytra_goto")
                        .then(net.minecraft.commands.Commands.argument("x", // argument
                                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                .then(net.minecraft.commands.Commands.argument("y", // argument
                                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                        .then(net.minecraft.commands.Commands.argument("z", // argument
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                                .executes(ctx -> elytraGoto(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                                                        null))
                                                // 【第 4 个参数是可选的指定女仆】不给就取"离执行点最近的一只"。
                                                // 取实体选择器而不是他自己那版的"名字字符串"：能补全、能写
                                                // @e[type=...]，多女仆时点名同样准（他给的理由——"否则很容易
                                                // 把命令下给旁边一只站桩的旧女仆"——本仓实测六百〇八 也踩到过）。
                                                .then(net.minecraft.commands.Commands.argument("maid", // argument
                                                                net.minecraft.commands.arguments.EntityArgument.entity())
                                                        .executes(ctx -> elytraGoto(ctx.getSource(),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "y"),
                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                                                                net.minecraft.commands.arguments.EntityArgument
                                                                        .getEntity(ctx, "maid"))))))))
                // v1.2.2 实测六百一十九【战斗感知的诊断入口】——只读（摆出来的场景用完复原）。
                // 【为什么要有】本批两条改的都是**判据**（"被方块挡住的怪算不算威胁"、
                // "接战时工作圈多大"），而这两条都要求**在线主人在场**（专用服务器上没有玩家、
                // 结构上触发不了）。照 实测五百四十五/六百〇八 的先例：把场景摆出来、调真方法、
                // 把判据的取值打日志（细则见 CombatSenseCheck）。
                .then(net.minecraft.commands.Commands.literal("combat")
                        .then(net.minecraft.commands.Commands.literal("check")
                                .executes(ctx -> combatCheck(ctx.getSource(), null))
                                .then(net.minecraft.commands.Commands.argument("maid", // argument
                                                net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> combatCheck(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .getEntity(ctx, "maid"))))))
                // v1.2.2 实测六百一十六【压缩盒的诊断入口】——只读，不改任何东西。
                // 【为什么要有】"她到底看没看见盒子里那十万个石头"是这一档最容易让人困惑的地方：
                // 盒子在她背包里、开关也开着，但她一次只拿 64（大堆不许漏进她的存档，理由见
                // CompressionBoxData），光看背包界面看不出"她的代码能看见什么"。这条命令把
                // **她那一侧的真实视图**打出来：视图多少格、每格她看得见多少、盒子里实际存了多少。
                .then(net.minecraft.commands.Commands.literal("box")
                        // v1.2.2 实测六百一十七【自检】——把"盒子能不能装盒子"这条路真的走一遍
                        // （用假玩家调界面点击的那个方法）：改的是点击路径，而自动化回归里没有
                        // 客户端、点不了界面，所以这一步只能放在游戏里跑。细则见 CompressionBoxCheck。
                        .then(net.minecraft.commands.Commands.literal("check")
                                .executes(ctx -> boxCheck(ctx.getSource(), null))
                                .then(net.minecraft.commands.Commands.argument("maid", // argument
                                                net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> boxCheck(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .getEntity(ctx, "maid")))))
                        .then(net.minecraft.commands.Commands.argument("maid", // argument
                                        net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(ctx -> boxReport(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument
                                                .getEntity(ctx, "maid"))))));
    }

    /** v1.2.2 实测六百一十九：{@code /maid_smart combat check [女仆]} —— 战斗感知自检 */
    private static int combatCheck(net.minecraft.commands.CommandSourceStack source,
                                  net.minecraft.world.entity.Entity entity) {
        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid =
                entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                        ? m : null;
        java.util.List<Component> lines =
                com.maidsmart.combat.CombatSenseCheck.run(source.getLevel(), maid);
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
            com.maidsmart.tool.PromaidLog.log(
                    com.maidsmart.combat.CombatSenseCheck.CAT, line.getString());
        }
        return lines.size();
    }

    /** v1.2.2 实测六百一十七：{@code /maid_smart box check [女仆]} —— 压缩盒自检 */
    private static int boxCheck(net.minecraft.commands.CommandSourceStack source,
                                net.minecraft.world.entity.Entity entity) {
        com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid =
                entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid m
                        ? m : null;
        java.util.List<Component> lines =
                com.maidsmart.box.CompressionBoxCheck.run(source.getLevel(), maid);
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
            com.maidsmart.tool.PromaidLog.log("\u538b\u7f29\u76d2\u81ea\u68c0", line.getString());
        }
        return lines.size();
    }

    /** v1.2.2 实测六百一十六：{@code /maid_smart box <女仆>} —— 把她的背包视图（含压缩盒那几格）打进日志 */
    private static int boxReport(net.minecraft.commands.CommandSourceStack source,
                                 net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            source.sendFailure(Component.literal("\u00a7c那不是女仆。"));
            return 0;
        }
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        java.util.List<Component> lines =
                inv instanceof com.maidsmart.box.CompressionBoxMaidInv ext
                        ? ext.describe()
                        : java.util.List.of(Component.literal("\u00a77"
                        + maid.getName().getString() + " 的背包视图是原版的 " + inv.getSlots()
                        + " 格——没有压缩盒（盒子不在背包里，或 compressionBox.maidExtension 关着）"));
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
            com.maidsmart.tool.PromaidLog.log("\u538b\u7f29\u76d2", line.getString());
        }
        return lines.size();
    }

    /** v1.2.2 实测六百〇八：{@code /maid_smart flyfollow clear} —— 摘掉全部"替代主人"（调试目标表很小） */
    private static int flightFollowClear(net.minecraft.commands.CommandSourceStack source) {
        com.maidsmart.combat.MaidFlightFollowBehavior.clearDebugTargets();
        source.sendFailure(Component.literal("\u00a77已摘掉全部飞行跟随调试目标。"));
        return 1;
    }

    /**
     * v1.2.2 实测六百〇八：{@code /maid_smart flyfollow <目标实体> [女仆]} —— 把"替代主人"挂上。
     *
     * 不给女仆参数时就取**离执行点最近**的一只（按 3D 距离排序——本批实测踩过"取列表第一只"
     * 导致挂到别人身上的坑）；给了就必须是女仆。
     *
     * 【只用于验证】它不改任何玩法配置，只把 MaidFlightFollowBehavior 的目标来源换成命令指定的
     * 实体；因此它同时是"这条链在实机上到底会不会起飞"的唯一可核验入口。
     * 权限同 /maid_smart（OP），与控制台兼容（无执行者时退回主世界出生点定位女仆）。
     */
    private static int flightFollow(net.minecraft.commands.CommandSourceStack source,
                                    net.minecraft.world.entity.Entity target,
                                    net.minecraft.world.entity.Entity maidArg) {
        try {
            net.minecraft.world.entity.Entity executor = source.getEntity();
            net.minecraft.server.level.ServerLevel level;
            net.minecraft.core.BlockPos around;
            if (executor != null && executor.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                level = sl;
                around = net.minecraft.core.BlockPos.containing(
                        executor.getX(), executor.getY(), executor.getZ());
            } else {
                level = source.getServer().overworld();
                around = level.getSharedSpawnPos();
            }
            if (level == null) {
                source.sendFailure(Component.literal("\u00a7c需要在一个世界里执行。"));
                return 0;
            }
            EntityMaid maid = pickMaid(source, level, around, maidArg);
            if (maid == null) {
                return 0; // 原因已经报给执行者了（见 pickMaid）
            }
            // 【必须是"实际上的最终变量"】下面 sendSuccess 的 lambda 要捕获它，而 maid 本身被赋值两次
            final EntityMaid chosen = maid;
            if (target == null) {
                com.maidsmart.combat.MaidFlightFollowBehavior.setDebugTarget(chosen, null);
                source.sendSuccess(() -> Component.literal("\u00a77已摘掉 "
                        + chosen.getDisplayName().getString() + " 的飞行跟随调试目标。"), false);
                return 1;
            }
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                source.sendFailure(Component.literal("\u00a7c目标必须是活体实体（她要追的是活物）。"));
                return 0;
            }
            com.maidsmart.combat.MaidFlightFollowBehavior.setDebugTarget(chosen, living);
            String msg = "\u00a7a" + chosen.getDisplayName().getString() + " 的飞行跟随目标已设为 "
                    + living.getDisplayName().getString()
                    + "\uff08\u9700 flightFollow.enabled=true\uff1b"
                    + "\u7f3a\u9798\u7fc5/\u70df\u82b1\u3001\u8ddd\u79bb\u4e0d\u591f\u3001"
                    + "\u4e2d\u95f4\u6709\u65b9\u5757\u906e\u6321\u90fd\u4e0d\u4f1a\u8d77\u98de\uff09";
            source.sendSuccess(() -> Component.literal(msg), false);
            com.maidsmart.tool.PromaidLog.log("飞行跟随", "flyfollow " + msg);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("\u00a7cflyfollow 失败：" + t));
            return 0;
        }
    }

    /**
     * v1.2.2 实测六百一十四：从命令上下文里挑一只女仆——显式给了就用它，否则取**离执行点最近**的一只
     * （64 格内，按到执行点的 3D 距离排序）。
     *
     * 【为什么抽出来】{@code flyfollow} 与 {@code elytra_goto} 是同一件事的两个目标来源（实体 / 坐标），
     * "怎么挑这只女仆"必须同口径——本批实测踩过"取列表第一只 → 挂到旁边站桩的旧女仆身上"，
     * 那种坑各写一遍就会各踩一遍。失败原因由本方法直接报给执行者，返回 null 即"已报过、别再往下走"。
     */
    private static EntityMaid pickMaid(net.minecraft.commands.CommandSourceStack source,
                                       net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.core.BlockPos around,
                                       net.minecraft.world.entity.Entity maidArg) {
        if (maidArg != null) {
            if (!(maidArg instanceof EntityMaid m)) {
                source.sendFailure(Component.literal("\u00a7c指定的实体不是女仆。"));
                return null;
            }
            return m;
        }
        java.util.List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                new net.minecraft.world.phys.AABB(around).inflate(64.0));
        maids.removeIf(m -> !m.isAlive());
        if (maids.isEmpty()) {
            source.sendFailure(Component.literal("\u00a7c附近 64 格内没有存活的女仆。"));
            return null;
        }
        // 按到执行点的 3D 距离排序取最近的（不是"列表第一只"）
        final double cx = around.getX() + 0.5;
        final double cy = around.getY() + 0.5;
        final double cz = around.getZ() + 0.5;
        maids.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(cx, cy, cz)));
        return maids.get(0);
    }

    /**
     * v1.2.2 实测六百一十四【取自粉丝 Roderick32 的「鞘翅赶路」分支】：
     * {@code /maid_smart elytra_goto <x> <y> <z> [女仆]} —— 让女仆穿鞘翅飞向指定坐标。
     *
     * 【这条命令做什么、不做什么】它只做一件事：给 {@code MaidFlightFollowBehavior} 挂一个
     * **坐标目标**（{@code setGotoTarget}）。判定（开关/鞘翅/燃料/视线/威胁/距离）、起飞、补推、
     * 收手全部复用那条已有链路——本仓**没有**为它新开状态机（他那份实现自带一整套平行的飞行
     * 状态机与 8 个配置项，与飞行跟随撞车，见 CHANGELOG 的取舍说明）。
     *
     * 【一次性】到点 / 超时（60 秒）/ 出现威胁 / 燃料与鞘翅掉了 / 她换维度 → 链收手并**自动摘掉**
     * 这个坐标（见 {@code MaidFlightFollowBehavior.stop}），之后她恢复正常跟随。
     *
     * 【这里为什么先把几条硬条件判一遍】不然命令会回一句"开始赶路"，而实际什么都没发生
     * （行为那边只在日志里写「飞行跟随跳过：…」）。判据全部取自**同一份定义**
     * （{@code MaidFlightKit} / 那个开关本身），不另写一套——否则就是第二个口径。
     */
    private static int elytraGoto(net.minecraft.commands.CommandSourceStack source,
                                  double x, double y, double z,
                                  net.minecraft.world.entity.Entity maidArg) {
        try {
            net.minecraft.world.entity.Entity executor = source.getEntity();
            net.minecraft.server.level.ServerLevel level;
            net.minecraft.core.BlockPos around;
            if (executor != null && executor.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                level = sl;
                around = net.minecraft.core.BlockPos.containing(
                        executor.getX(), executor.getY(), executor.getZ());
            } else {
                level = source.getServer().overworld();
                around = level.getSharedSpawnPos();
            }
            if (level == null) {
                source.sendFailure(Component.literal("\u00a7c需要在一个世界里执行。"));
                return 0;
            }
            EntityMaid maid = pickMaid(source, level, around, maidArg);
            if (maid == null) {
                return 0;
            }
            if (!com.maidsmart.config.MaidSmartConfig.FLIGHT_FOLLOW_ENABLED.get()) {
                source.sendFailure(Component.literal("\u00a7c" + maid.getDisplayName().getString()
                        + " 的「飞行跟随」开关关着（flightFollow.enabled，默认关）——"
                        + "这个坐标档跑的就是那条链路，开关关着时她不会起飞。"));
                return 0;
            }
            net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(x, y, z);
            double dist = Math.sqrt(maid.distanceToSqr(x, y, z));
            double need = com.maidsmart.config.MaidSmartConfig.FLIGHT_FOLLOW_DIST.get();
            if (dist <= need) {
                source.sendFailure(Component.literal(String.format(
                        "\u00a7c目标点离她只有 %.1f 格（≤ 起手距离 %.1f，flightFollow.dist）——"
                                + "这么近她会走路/搭路过去，不会起飞。", dist, need)));
                return 0;
            }
            if (!com.maidsmart.combat.MaidFlightKit.hasElytra(maid)) {
                source.sendFailure(Component.literal("\u00a7c" + maid.getDisplayName().getString()
                        + " 没有可用鞘翅（穿在身上/拿在手里/放在背包里都算，起飞时自动穿上）。"));
                return 0;
            }
            if (!com.maidsmart.combat.MaidFlightKit.hasFlightPropellant(maid)) {
                source.sendFailure(Component.literal("\u00a7c" + maid.getDisplayName().getString()
                        + " 没有「可以飞行的道具」（烟花火箭 / 孔雀羽扇 / 能上天的位移法术，三选一）。"));
                return 0;
            }
            com.maidsmart.combat.MaidFlightFollowBehavior.setGotoTarget(maid, pos, level.dimension());
            String msg = "\u00a7a" + maid.getDisplayName().getString() + " 飞向 " + (int) x + " " + (int) y
                    + " " + (int) z + "（距 " + String.format("%.1f", dist) + " 格；到点/超时/遇敌即收手，"
                    + "之后恢复正常跟随）。";
            source.sendSuccess(() -> Component.literal(msg), true);
            com.maidsmart.tool.PromaidLog.log("飞行跟随", "elytra_goto " + msg);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("\u00a7celytra_goto 失败：" + t));
            return 0;
        }
    }

    /**
     * v1.2.0 实测五百四十五：`/maid_smart feedtest &lt;itemId&gt;` —— 对最近的女仆结算一次
     * "投喂"（原版进食 + TLM 餐食回血），并把血量变化写进 promaid.log。
     *
     * 【只用于验证】走的是与 {@code MaidAidOwnerBehavior.feedSisterFood} 完全相同的调用序列
     * （eat 快照 → eat → MaidMealBridge），所以它能证明"这条路径在实机上确实回血"；
     * 但它**不消耗任何物品**（用的是新造的物品栈），因此不是一个玩法入口。权限同 /maid_smart。
     */
    private static int feedTest(net.minecraft.commands.CommandSourceStack source, String itemId) {
        try {
            // 【控制台也要能用】本命令是验证入口，跑在专用服务器上时没有玩家/执行者实体
            // （`getEntity()` 为 null），此时退回"服务端主世界 + 世界出生点"定位女仆。
            net.minecraft.world.entity.Entity executor = source.getEntity();
            ServerLevel level;
            net.minecraft.core.BlockPos around;
            if (executor != null && executor.level() instanceof ServerLevel sl) {
                level = sl;
                around = net.minecraft.core.BlockPos.containing(
                        executor.getX(), executor.getY(), executor.getZ());
            } else {
                level = source.getServer().overworld();
                around = level.getSharedSpawnPos();
            }
            if (level == null) {
                source.sendFailure(Component.literal("\u00a7c需要在一个世界里执行。"));
                return 0;
            }
            java.util.List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                    new net.minecraft.world.phys.AABB(around).inflate(64.0));
            if (maids.isEmpty()) {
                source.sendFailure(Component.literal("\u00a7c附近 64 格内没有女仆。"));
                return 0;
            }
            // 【优先选血量不满的那只】本命令的用途就是验证"投喂回血"，而 heal() 在满血时
            // 被原版上限吃掉、血量一动不动——照"就近选一只"会在测试世界里撞上满血的残留
            // 女仆，得到 20.00 → 20.00 的假失败（neo 首轮实测踩过）。
            // 【必须滤掉非存活】实测第二轮按血量升序又选中了 0 血的**残留尸体**（0.00 → 0.00）。
            // 两个条件一起：先只要存活的，再在其中取最低血。
            maids.removeIf(m -> !m.isAlive());
            if (maids.isEmpty()) {
                source.sendFailure(Component.literal("\u00a7c附近 64 格内没有存活的女仆。"));
                return 0;
            }
            maids.sort(java.util.Comparator.comparingDouble(EntityMaid::getHealth));
            EntityMaid maid = maids.get(0);
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(ResourceLocation.parse(itemId));
            if (item == net.minecraft.world.item.Items.AIR) {
                source.sendFailure(Component.literal("\u00a7c找不到物品：" + itemId));
                return 0;
            }
            net.minecraft.world.item.ItemStack food = new net.minecraft.world.item.ItemStack(item);
            float before = maid.getHealth();
            // 与 feedSisterFood 同一序列：先把"喂进去的那一份"存快照（eat 会 shrink 掉原栈）
            net.minecraft.world.item.ItemStack fed = food.copy();
            // 实测五百七十九：与喂食链同一入口（普通食物等价于旧版 eat()，
            // "吃完不消失"的遗物类不会被手搓消耗毁掉）
            com.maidsmart.combat.MaidMealBridge.eatByItemLogic(maid, food);
            boolean meal = com.maidsmart.combat.MaidMealBridge.applySelfEatingEffect(maid, fed);
            float after = maid.getHealth();
            String msg = String.format("\u00a7a%s：血量 %.2f → %.2f（TLM 餐食回血=%s）",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), before, after, meal);
            source.sendSuccess(() -> Component.literal(msg), false);
            com.maidsmart.tool.PromaidLog.log("投喂回血", "feedtest " + itemId + " " + msg);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("\u00a7cfeedtest 失败：" + t));
            return 0;
        }
    }

    /** v1.5.87：/maid_smart memory on|off —— 切换最近一只女仆的 AI 记忆开关 */
    private static int memory(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSystemMessage(Component.literal("\u00a7c\u8be5\u6307\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\u3002"));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        EntityMaid maid = nearestMaid(player);
        if (maid == null) {
            player.sendSystemMessage(Component.literal("\u00a7c\u5468\u56f432\u683c\u5185\u6ca1\u6709\u5973\u4ec6\u3002"));
            return 0;
        }
        com.maidsmart.memory.AiMemoryManager.setEnabled(maid, enabled);
        player.sendSystemMessage(Component.literal(
                (enabled ? "\u00a7a" : "\u00a77") + "\u3010AI \u8bb0\u5fc6\u3011" + maid.getDisplayName().getString()
                        + "\u5df2" + (enabled ? "\u542f\u7528\uff08\u5bf9\u8bdd\u79ef\u7d2f\u540e\u81ea\u52a8\u63d0\u53d6\uff09"
                        : "\u5173\u95ed\uff08\u4e0d\u63d0\u53d6\u4e0d\u6ce8\u5165\uff09")));
        return 1;
    }

    /** v1.5.87：/maid_smart memory status —— 列出附近女仆的记忆开关状态 */
    private static int memoryStatus(net.minecraft.commands.CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(32.0);
        StringBuilder sb = new StringBuilder("\u00a7e\u5468\u56f432\u683c\u5185\u5973\u4ec6\u7684 AI \u8bb0\u5fc6\u72b6\u6001\uff1a");
        int n = 0;
        for (EntityMaid m : level.getEntitiesOfClass(EntityMaid.class, box)) {
            boolean on = com.maidsmart.memory.AiMemoryManager.isEnabled(m);
            sb.append("\n").append(m.getDisplayName().getString()).append(": ")
                    .append(on ? "\u00a7a\u5f00" : "\u00a77\u5173");
            n++;
        }
        if (n == 0) {
            sb.append("\n\u00a77\uff08\u65e0\uff09");
        }
        player.sendSystemMessage(Component.literal(sb.toString()));
        return 1;
    }

    /** v1.5.87：玩家 32 格内最近的女仆 */
    private static EntityMaid nearestMaid(ServerPlayer player) {
        EntityMaid best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntityMaid m : player.level().getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(32.0))) {
            double d = m.position().distanceTo(player.position());
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private static int summon(net.minecraft.commands.CommandSourceStack source, int count) {
        if (!(source.getEntity() instanceof ServerPlayer player)) { // getEntity
            source.sendSystemMessage(Component.literal("\u00a7c\u8be5\u6307\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\u3002")); // sendFailure
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        java.util.Optional<com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask> optTask =
                TaskManager.findTask(ResourceLocation.parse(TASK_UID));
        if (optTask.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "\u00a7c\u5efa\u7b51\u4efb\u52a1\u672a\u6ce8\u518c\uff0c\u65e0\u6cd5\u53ec\u5524\u3002"));
            return 0;
        }
        com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = optTask.get();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double r = 1.2 + i * 0.28; // 圆环展开，避免重叠
            EntityMaid maid = EntityMaid.TYPE.create(level); // create(Level)
            if (maid == null) {
                continue;
            }
            maid.setPos(player.getX() + Math.cos(angle) * r,
                    player.getY(),
                    player.getZ() + Math.sin(angle) * r);
            // 驯服：绑定执行者为主人（tame = setOwner）
            maid.tame(player);
            // 直接切"建筑"任务——出生即是建造状态，等手册下达蓝图
            maid.setTask(task);
            level.addFreshEntity(maid); // addFreshEntity
            spawned++;
        }
        // 同步 TLM 女仆数量上限（绕过 canAdd 逐只累加，保持"已驯服数"与实际一致，
        // 否则之后右击驯服新女仆会因计数不符被拒）
        final int total = spawned;
        com.github.tartaricacid.touhoulittlemaid.data.MaidNumAttachment cap =
                ((net.neoforged.neoforge.attachment.IAttachmentHolder) player)
                .getData(com.github.tartaricacid.touhoulittlemaid.init.InitDataAttachment.MAID_NUM.get());
        for (int i = 0; i < total; i++) {
            try {
                cap.add();
            } catch (Exception ignored) {
            }
        }
        player.sendSystemMessage(Component.literal(
                "\u00a7a\u5df2\u53ec\u5524 " + spawned + " \u53ea\u5efa\u9020\u5973\u4ec6\uff08\u5df2\u9a6f\u670d\uff0c\u4efb\u52a1\uff1a\u5efa\u7b51\uff09\u3002"
                        + "\u7528\u624b\u518c\u70b9\u51fb\u56fe\u7eb8\u5373\u5f00\u59cb\u5efa\u9020\u3002"));
        return 1;
    }
}
