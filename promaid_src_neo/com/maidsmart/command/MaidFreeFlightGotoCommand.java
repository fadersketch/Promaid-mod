package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.flight.MaidFreeFlightController;
import com.maidsmart.flight.MaidFreeFlightKit;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 实测六百七十三【仿创造飞行 · 手动触发】：
 * {@code /maid_smart freeflight_goto <x> <y> <z> [女仆选择器]}——让有资格的女仆飞过去（悬停 + 自由升降）。
 *
 * 【为什么要有它】一是排查（无头测试服没有真玩家、也就没有"主人"可跟，自动触发的那一路测不到），
 * 二是实用（想让她"飘到那边的高台/浮空岛"时不必先跑一趟）。
 * 不写选择器 = 取离坐标最近的 64 格内一只；写了就用标准实体选择器（如
 * {@code @e[type=touhou_little_maid:maid,name="X",limit=1]}）。
 */
public final class MaidFreeFlightGotoCommand {

    private MaidFreeFlightGotoCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maid_smart")
                .then(Commands.literal("freeflight_goto")
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
                // 【专用服务器验收入口】替代主人：照上游 /maid_smart flyfollow 的先例——
                // 这条链的目标是在线主人实体（getOwner 走 PlayerList，专用服务器恒 null），
                // 挂一个实体当跟随目标就能把"静止→落地待命→再起飞"整条链跑起来
                .then(Commands.literal("freeflight_enemy")
                        .then(Commands.literal("clear")
                                .executes(ctx -> enemyClear(ctx.getSource())))
                        .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(ctx -> enemy(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "target"), null))
                                .then(Commands.argument("maid", net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> enemy(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid"))))))
                .then(Commands.literal("freeflight_follow")
                        .then(Commands.literal("clear")
                                .executes(ctx -> followClear(ctx.getSource())))
                        .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(ctx -> follow(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "target"), null))
                                .then(Commands.argument("maid", net.minecraft.commands.arguments.EntityArgument.entity())
                                        .executes(ctx -> follow(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid")))))));
    }

    /** /maid_smart freeflight_follow <目标实体> [女仆]：给女仆挂"替代主人"（无头/专用服验收入口） */
    private static int follow(CommandSourceStack src, net.minecraft.world.entity.Entity target,
                              net.minecraft.world.entity.Entity picked) {
        try {
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                src.sendFailure(Component.literal("§c替代主人必须是活体实体。"));
                return 0;
            }
            EntityMaid maid;
            if (picked instanceof EntityMaid m) {
                maid = m;
            } else {
                Vec3 pos = src.getPosition();
                maid = src.getLevel().getEntitiesOfClass(EntityMaid.class,
                                new AABB(pos, pos).inflate(64.0), EntityMaid::isAlive).stream()
                        .min((a, b) -> Double.compare(a.distanceToSqr(pos), b.distanceToSqr(pos)))
                        .orElse(null);
                if (maid == null) {
                    src.sendFailure(Component.literal("§c64 格内没有女仆（请显式指定一只）。"));
                    return 0;
                }
            }
            MaidFreeFlightController.setSubstituteOwner(maid, living);
            src.sendSuccess(() -> Component.literal("§a已给 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 挂上替代主人：" + living.getName().getString()
                    + "（判定/起飞/落地待命全走同一套链路）"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfreeflight_follow 失败：" + t));
            return 0;
        }
    }

    /** /maid_smart freeflight_enemy <目标实体> [女仆]：挂"替代敌人"（验收战斗档飞行） */
    private static int enemy(CommandSourceStack src, net.minecraft.world.entity.Entity target,
                             net.minecraft.world.entity.Entity picked) {
        try {
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                src.sendFailure(Component.literal("§c目标必须是活体实体。"));
                return 0;
            }
            EntityMaid maid = pickMaid(src, picked);
            if (maid == null) {
                return 0;
            }
            MaidFreeFlightController.setSubstituteEnemy(maid, living);
            src.sendSuccess(() -> Component.literal("§a已给 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 挂上替代敌人：" + living.getName().getString() + "（走的就是战斗档飞行链路）"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfreeflight_enemy 失败：" + t));
            return 0;
        }
    }

    private static int enemyClear(CommandSourceStack src) {
        try {
            Vec3 pos = src.getPosition();
            var maids = src.getLevel().getEntitiesOfClass(EntityMaid.class, new AABB(pos, pos).inflate(64.0));
            for (EntityMaid m : maids) {
                MaidFreeFlightController.clearSubstituteEnemy(m);
            }
            src.sendSuccess(() -> Component.literal("§a已清除 " + maids.size() + " 只女仆的替代敌人。"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfreeflight_enemy clear 失败：" + t));
            return 0;
        }
    }

    /** 取女仆：显式指定优先，否则离执行点最近的 64 格内一只 */
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

    private static int followClear(CommandSourceStack src) {
        try {
            Vec3 pos = src.getPosition();
            var maids = src.getLevel().getEntitiesOfClass(EntityMaid.class, new AABB(pos, pos).inflate(64.0));
            for (EntityMaid m : maids) {
                MaidFreeFlightController.clearSubstituteOwner(m);
            }
            src.sendSuccess(() -> Component.literal("§a已清除 " + maids.size() + " 只女仆的替代主人。"), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfreeflight_follow clear 失败：" + t));
            return 0;
        }
    }

    private static int go(CommandSourceStack src, double x, double y, double z, net.minecraft.world.entity.Entity picked) {
        try {
            ServerLevel level = src.getLevel();
            Vec3 pos = new Vec3(x, y, z);
            EntityMaid maid;
            if (picked instanceof EntityMaid m) {
                maid = m;
            } else {
                if (picked != null) {
                    src.sendFailure(Component.literal("§c那不是女仆。"));
                    return 0;
                }
                AABB box = new AABB(pos, pos).inflate(64.0);
                List<EntityMaid> list = level.getEntitiesOfClass(EntityMaid.class, box,
                        m -> m.isAlive() && !m.isMaidInSittingPose());
                maid = list.stream().min((a, b) -> Double.compare(a.distanceToSqr(pos), b.distanceToSqr(pos)))
                        .orElse(null);
                if (maid == null) {
                    src.sendFailure(Component.literal("§c坐标 64 格内没有可用女仆（或指定一只）。"));
                    return 0;
                }
            }
            if (!MaidFreeFlightController.canFly(maid)) {
                src.sendFailure(Component.literal("§c" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 现在飞不了（需要：总开关打开 + 资格物品/效果/重力归零其一；"
                        + "且不在骑乘/睡觉/守家/空袭/扫帚状态）。当前：" + MaidFreeFlightController.status(maid)));
                return 0;
            }
            MaidFreeFlightController.setDebugTarget(maid, pos);
            src.sendSuccess(() -> Component.literal("§a" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 开始仿创造飞行 → " + (int) x + " " + (int) y + " " + (int) z), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§cfreeflight_goto 失败：" + t));
            return 0;
        }
    }
}
