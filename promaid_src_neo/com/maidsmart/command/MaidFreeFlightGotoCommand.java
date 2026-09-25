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
                                                                net.minecraft.commands.arguments.EntityArgument.getEntity(ctx, "maid")))))))));
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
