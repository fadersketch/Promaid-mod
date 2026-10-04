package com.maidsmart.patrol;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidBroomDrive;
import com.maidsmart.combat.MaidBroomKit;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * v1.3.8【巡逻航迹 · 指令】{@code /maid_smart broom_goto <x> <y> <z> [女仆]}
 * + {@code /maid_smart broom_patrol status|off [女仆]}。
 *
 * <p>── 玩家原话（为什么这条留给指令）──
 * 「如果说要让女仆飞到哪个地方，那可以参考一下房创造飞行里面的指令操作，我们将这个留给指令就行了。」
 * ⇒ 所以"飞到某处"**不做进编辑器**，照 {@code MaidFreeFlightGotoCommand} 的
 * {@code freeflight_goto} 口径来：坐标参数 + 可选实体选择器 + 只做一件事（给一个目标点）。
 *
 * <p>── {@code broom_goto} 怎么落地 ──
 * 不新开状态机。扫帚的位移原语只有一个：{@link MaidBroomDrive#steerTo}。所以这里把目标点写进
 * 一张**外部目标表**（{@link #GOTO}），由 {@code MaidBroomBehavior} 的平时档优先消费一次、
 * 到达或超时就摘掉——"一次性"这条与 {@code elytra_goto} 完全一致。
 *
 * <p>权限：{@code hasPermission(2)}，与 {@code MaidArmyCommand} / {@code freeflight_goto} 同口径
 * （少了它任何玩家都能把别人的女仆开走）。
 */
public final class PatrolCommand {

    /** 一次性外部目标：女仆 UUID → {x, y, z, 到期 gameTime} */
    private static final java.util.Map<java.util.UUID, double[]> GOTO = new java.util.HashMap<>();
    /** 一条 goto 最多活这么久（tick）= 60 秒，与 elytra_goto 同口径 */
    private static final long GOTO_TIMEOUT = 1200L;
    /** 到点判定（格） */
    private static final double ARRIVE = 1.5;

    /** 新建航迹的默认净空半径（配置；读不到时用 PatrolRoute 的默认常量） */
    public static double defaultClearance() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_PATROL_CLEARANCE.get();
        } catch (Throwable ignored) {
            return PatrolRoute.DEFAULT_CLEARANCE;
        }
    }

    /** 航迹水平半径是否在允许范围内（0 = 不限制） */
    public static boolean radiusAllowed(double radius) {
        try {
            int max = com.maidsmart.config.MaidSmartConfig.COMBAT_PATROL_MAX_RADIUS.get();
            return max <= 0 || radius <= (double) max;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private PatrolCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("broom_goto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> go(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                        DoubleArgumentType.getDouble(ctx, "z"),
                                                        null))
                                                .then(Commands.argument("maid", net.minecraft.commands.arguments.EntityArgument.entity())
                                                        .executes(ctx -> go(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"),
                                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid"))))))))
                .then(Commands.literal("broom_patrol")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource(), null))
                                .then(Commands.argument("maid", net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> status(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid")))))
                        .then(Commands.literal("off")
                                .executes(ctx -> off(ctx.getSource(), null))
                                .then(Commands.argument("maid", net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> off(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid")))))));
    }

    /* ==================== 外部目标表（供 MaidBroomBehavior 消费） ==================== */

    /**
     * 取一次待飞目标：到时/到点/超时都会摘掉（一次性）。
     * 返回 null = 没有（调用方继续走航迹/守家）。
     */
    public static Vec3 poll(EntityMaid maid, Vec3 broomPos) {
        try {
            if (maid == null) {
                return null;
            }
            java.util.UUID id = maid.getUUID();
            double[] g = GOTO.get(id);
            if (g == null) {
                return null;
            }
            long now = maid.level().getGameTime();
            Vec3 t = new Vec3(g[0], g[1], g[2]);
            if (now > (long) g[3] || (broomPos != null && broomPos.distanceTo(t) <= ARRIVE)) {
                GOTO.remove(id);
                return null; // 这一拍摘掉，下一拍回航迹
            }
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clear(EntityMaid maid) {
        try {
            if (maid != null) {
                GOTO.remove(maid.getUUID());
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 命令体 ==================== */

    private static int go(CommandSourceStack src, double x, double y, double z,
                          net.minecraft.world.entity.Entity picked) {
        try {
            ServerLevel level = src.getLevel();
            Vec3 pos = new Vec3(x, y, z);
            EntityMaid maid;
            if (picked instanceof EntityMaid m) {
                maid = m;
            } else {
                AABB box = new AABB(pos, pos).inflate(64.0);
                List<EntityMaid> list = level.getEntitiesOfClass(EntityMaid.class, box,
                        e -> e.isAlive() && !e.isMaidInSittingPose());
                maid = list.stream().min((a, b) -> Double.compare(a.distanceToSqr(pos), b.distanceToSqr(pos)))
                        .orElse(null);
                if (maid == null) {
                    src.sendFailure(Component.literal("§c坐标 64 格内没有可用女仆（或显式指定一只）。"));
                    return 0;
                }
            }
            if (!MaidBroomKit.isBroomTask(maid)) {
                src.sendFailure(Component.literal("§c" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 现在不是「扫帚模式」——这条命令跑的是扫帚的飞行链路，先把她的任务切过去。"));
                return 0;
            }
            if (!MaidBroomKit.isRidingBroom(maid) && !MaidBroomKit.hasBroomItem(maid)) {
                src.sendFailure(Component.literal("§c" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 没有扫帚（背包里没有、也没骑着一把）。"));
                return 0;
            }
            GOTO.put(maid.getUUID(), new double[]{x, y, z,
                    level.getGameTime() + GOTO_TIMEOUT});
            src.sendSuccess(() -> Component.literal("§a" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 开始飞向 " + (int) x + " " + (int) y + " " + (int) z
                    + "（到点/60 秒后自动回到航迹或守家；有敌人时仍是先接敌）"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cbroom_goto 失败：" + t));
            return 0;
        }
    }

    private static int status(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        try {
            EntityMaid maid = pickMaid(src, picked);
            if (maid == null) {
                return 0;
            }
            src.sendSuccess(() -> Component.literal("§b" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " §f巡逻状态：" + PatrolFlight.status(maid)), false);
            PatrolRoute r = PatrolFlight.boundRoute(maid);
            if (r != null) {
                PatrolGeometry.Report rep = PatrolValidation.validate(maid.level(), r);
                for (String line : PatrolValidation.lines(rep)) {
                    src.sendSuccess(() -> Component.literal("  " + line), false);
                }
            }
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cbroom_patrol status 失败：" + t));
            return 0;
        }
    }

    private static int off(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        try {
            EntityMaid maid = pickMaid(src, picked);
            if (maid == null) {
                return 0;
            }
            PatrolChartData.unbind(maid);
            PatrolFlight.forget(maid);
            clear(maid);
            src.sendSuccess(() -> Component.literal("§a已解除 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 的巡逻航迹（她回到守家盘旋/跟随/悬停）"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cbroom_patrol off 失败：" + t));
            return 0;
        }
    }

    /** 取女仆：显式指定优先，否则离执行点最近的 64 格内一只（同 freeflight_goto 口径） */
    private static EntityMaid pickMaid(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        if (picked instanceof EntityMaid m) {
            return m;
        }
        Vec3 pos = src.getPosition();
        EntityMaid maid = src.getLevel().getEntitiesOfClass(EntityMaid.class,
                        new AABB(pos, pos).inflate(64.0), EntityMaid::isAlive).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(pos), b.distanceToSqr(pos)))
                .orElse(null);
        if (maid == null) {
            src.sendFailure(Component.literal("§c64 格内没有女仆（请显式指定一只）。"));
        }
        return maid;
    }
}
