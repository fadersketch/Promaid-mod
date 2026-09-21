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
                                .executes(ctx -> memoryStatus(ctx.getSource()))))
                // v1.2.0 实测五百四十五：投喂回血的**可验证入口**。
                // 【为什么必须有】"女仆喂女仆"发生在两个实体之间、不产生任何原版事件，
                // 而且互助链要求"被喂的那只有主人且主人在线"——专用服务器上没有玩家，
                // 这条链**结构上无法端到端触发**（前两轮回归都卡在这里）。所以留一条
                // 控制台可用的调试命令：对指定女仆执行"一份食物 → 原版进食 → TLM
                // 餐食口径回血"（与 MaidMealBridge 完全同一条路径），把回血量打进日志。
                .then(net.minecraft.commands.Commands.m_82127_("feedtest")
                        .then(net.minecraft.commands.Commands.m_82129_("item", // argument
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
                // 【前提】bridge.flightFollow 开关必须是开的（默认关），否则行为压根不启动。
                .then(net.minecraft.commands.Commands.m_82127_("flyfollow")
                        .then(net.minecraft.commands.Commands.m_82127_("clear")
                                .executes(ctx -> flightFollowClear(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.m_82129_("target", // argument
                                        net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                .executes(ctx -> flightFollow(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument
                                                .m_91452_(ctx, "target"), null))
                                // 【第三个参数是可选的指定女仆】不给就取"离执行点最近的一只"——
                                // 但测试/多女仆场景下"最近"可能不是你心里那只（本批实测踩过：
                                // 测试世界里堆着前几批留下的女仆，命令挂到了别人身上，现象是
                                // "她怎么不飞"），所以留一个显式指定的口子。
                                .then(net.minecraft.commands.Commands.m_82129_("maid", // argument
                                                net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                        .executes(ctx -> flightFollow(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .m_91452_(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument
                                                        .m_91452_(ctx, "maid")))))));
    }

    /** v1.2.2 实测六百〇八：{@code /maid_smart flyfollow clear} —— 摘掉全部"替代主人"（调试目标表很小） */
    private static int flightFollowClear(net.minecraft.commands.CommandSourceStack source) {
        com.maidsmart.combat.MaidFlightFollowBehavior.clearDebugTargets();
        source.m_243053_(Component.m_237113_("\u00a77已摘掉全部飞行跟随调试目标。"));
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
            net.minecraft.world.entity.Entity executor = source.m_81373_();
            net.minecraft.server.level.ServerLevel level;
            net.minecraft.core.BlockPos around;
            if (executor != null && executor.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
                level = sl;
                around = net.minecraft.core.BlockPos.m_274561_(
                        executor.m_20185_(), executor.m_20186_(), executor.m_20189_());
            } else {
                level = source.m_81377_().m_129783_(); // getServer().overworld()
                around = level.m_220360_();            // getSharedSpawnPos()
            }
            if (level == null) {
                source.m_243053_(Component.m_237113_("\u00a7c需要在一个世界里执行。"));
                return 0;
            }
            EntityMaid maid = null;
            if (maidArg != null) {
                if (!(maidArg instanceof EntityMaid m)) {
                    source.m_243053_(Component.m_237113_("\u00a7c指定的实体不是女仆。"));
                    return 0;
                }
                maid = m;
            } else {
                java.util.List<EntityMaid> maids = level.m_45976_(EntityMaid.class,
                        new net.minecraft.world.phys.AABB(around).m_82400_(64.0));
                maids.removeIf(m -> !m.m_6084_());
                if (maids.isEmpty()) {
                    source.m_243053_(Component.m_237113_("\u00a7c附近 64 格内没有存活的女仆。"));
                    return 0;
                }
                // 按到执行点的 3D 距离排序取最近的（不是"列表第一只"）
                final double cx = around.m_123341_() + 0.5;
                final double cy = around.m_123342_() + 0.5;
                final double cz = around.m_123343_() + 0.5;
                maids.sort(java.util.Comparator.comparingDouble(m -> m.m_20275_(cx, cy, cz)));
                maid = maids.get(0);
            }
            if (target == null) {
                com.maidsmart.combat.MaidFlightFollowBehavior.setDebugTarget(maid, null);
                source.m_243053_(Component.m_237113_("\u00a77已摘掉 "
                        + maid.m_5446_().getString() + " 的飞行跟随调试目标。"));
                return 1;
            }
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                source.m_243053_(Component.m_237113_("\u00a7c目标必须是活体实体（她要追的是活物）。"));
                return 0;
            }
            com.maidsmart.combat.MaidFlightFollowBehavior.setDebugTarget(maid, living);
            String msg = "\u00a7a" + maid.m_5446_().getString() + " 的飞行跟随目标已设为 "
                    + living.m_5446_().getString()
                    + "\uff08\u9700 bridge.flightFollow=true\uff1b"
                    + "\u7f3a\u9798\u7fc5/\u70df\u82b1\u3001\u8ddd\u79bb\u4e0d\u591f\u3001"
                    + "\u4e2d\u95f4\u6709\u65b9\u5757\u906e\u6321\u90fd\u4e0d\u4f1a\u8d77\u98de\uff09";
            source.m_243053_(Component.m_237113_(msg));
            com.maidsmart.tool.PromaidLog.log("飞行跟随", "flyfollow " + msg);
            return 1;
        } catch (Throwable t) {
            source.m_243053_(Component.m_237113_("\u00a7cflyfollow 失败：" + t));
            return 0;
        }
    }

    /**
     * v1.2.0 实测五百四十五：`/maid_smart feedtest &lt;itemId&gt;` —— 对最近的女仆结算一次
     * "投喂"（原版进食 + TLM 餐食回血），并把血量变化写进 promaid.log。
     *
     * 【只用于验证】走的是与 {@code MaidAidOwnerBehavior.feedSisterFood} 完全相同的调用序列
     * （eat 快照 → eat → MaidMealBridge），所以它能证明"这条路径在实机上确实回血"；
     * 但它**不消耗任何物品**（用的是新造的物品栈），因此不是一个玩法入口。
     * 权限同 /maid_smart（OP）。
     */
    private static int feedTest(net.minecraft.commands.CommandSourceStack source, String itemId) {
        try {
            // 【控制台也要能用】本命令是验证入口，跑在专用服务器上时没有玩家/执行者实体
            // （`m_81373_` 为 null），此时退回"服务端主世界 + 世界出生点"定位女仆。
            net.minecraft.world.entity.Entity executor = source.m_81373_();
            net.minecraft.server.level.ServerLevel level;
            net.minecraft.core.BlockPos around;
            if (executor != null && executor.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl) {
                level = sl;
                around = net.minecraft.core.BlockPos.m_274561_(
                        executor.m_20185_(), executor.m_20186_(), executor.m_20189_());
            } else {
                level = source.m_81377_().m_129783_(); // getServer().overworld()
                around = level.m_220360_();            // getSharedSpawnPos()
            }
            if (level == null) {
                source.m_243053_(Component.m_237113_("\u00a7c需要在一个世界里执行。"));
                return 0;
            }
            java.util.List<EntityMaid> maids = level.m_45976_(EntityMaid.class,
                    new net.minecraft.world.phys.AABB(around).m_82400_(64.0));
            if (maids.isEmpty()) {
                source.m_243053_(Component.m_237113_("\u00a7c附近 64 格内没有女仆。"));
                return 0;
            }
            // 【优先选血量不满的那只】本命令的用途就是验证"投喂回血"，而 heal() 在满血时
            // 被原版上限吃掉、血量一动不动——照"就近选一只"会在测试世界里撞上满血的残留
            // 女仆，得到 20.00 → 20.00 的假失败（neo 首轮实测踩过）。
            // 【必须滤掉非存活】实测第二轮按血量升序又选中了 0 血的**残留尸体**（0.00 → 0.00）。
            // 两个条件一起：先只要存活的，再在其中取最低血。
            maids.removeIf(m -> !m.m_6084_());
            if (maids.isEmpty()) {
                source.m_243053_(Component.m_237113_("\u00a7c附近 64 格内没有存活的女仆。"));
                return 0;
            }
            maids.sort(java.util.Comparator.comparingDouble(EntityMaid::m_21223_));
            EntityMaid maid = maids.get(0);
            // 1.20.1：物品注册表走 ForgeRegistries.ITEMS（BuiltInRegistries.ITEM 在 SRG 环境
            // 里是 f_122959_，直接写字面名编译不过）；`Items.f_41583_`（AIR）在 1.20.1 也不存在，
            // 所以只判 null。
            net.minecraft.world.item.Item item =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                            net.minecraft.resources.ResourceLocation.parse(itemId));
            if (item == null) {
                source.m_243053_(Component.m_237113_("\u00a7c找不到物品：" + itemId));
                return 0;
            }
            net.minecraft.world.item.ItemStack food = new net.minecraft.world.item.ItemStack(item);
            float before = maid.m_21223_();
            // 与 feedSisterFood 同一序列：先把"喂进去的那一份"存快照（进食会把原栈吃空）
            // v1.2.2 实测五百七十九：改走**物品自己的 finishUsingItem**（与喂食链同一入口，
            // 普通食物等价于旧版 eat()，"吃完不消失"的遗物类不会被手搓消耗毁掉）
            net.minecraft.world.item.ItemStack fed = food.m_41777_();
            com.maidsmart.combat.MaidMealBridge.eatByItemLogic(maid, food);
            boolean meal = com.maidsmart.combat.MaidMealBridge.applySelfEatingEffect(maid, fed);
            float after = maid.m_21223_();
            String msg = String.format("\u00a7a%s：血量 %.2f → %.2f（TLM 餐食回血=%s）",
                    com.maidsmart.tool.PromaidLog.nameOf(maid), before, after, meal);
            source.m_243053_(Component.m_237113_(msg));
            com.maidsmart.tool.PromaidLog.log("投喂回血", "feedtest " + itemId + " " + msg);
            return 1;
        } catch (Throwable t) {
            source.m_243053_(Component.m_237113_("\u00a7cfeedtest 失败：" + t));
            return 0;
        }
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
