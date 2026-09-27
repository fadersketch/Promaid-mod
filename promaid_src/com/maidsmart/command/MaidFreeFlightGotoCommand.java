package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.flight.MaidFreeFlightController;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 实测七百〇二【1.20.1 精简版】仿创造飞行 · 手动触发：
 * {@code /maid_smart freeflight_goto <x> <y> <z> [女仆选择器]}——让有资格的女仆飞过去（悬停 + 自由升降）。
 *
 * 【为什么要有它】一是排查（无头测试服没有真玩家、也就没有"主人"可跟，自动触发的那一路测不到），
 * 二是实用（想让她"飘到那边的高台/浮空岛"时不必先跑一趟）。
 *
 * 【1.20.1 落法】与 1.21.1 树逐字对应，只把名字换成 SRG（{@code m_243053_} = sendSuccess、
 * {@code m_81352_} = sendFailure、{@code m_81372_} = getLevel、{@code m_81371_} = getPosition、
 * {@code m_6761_} = hasPermission、{@code m_45976_} = getEntitiesOfClass）。挂载点由
 * {@code ProMaidExtension.onRegisterCommands} 调 {@link #register}。
 */
public final class MaidFreeFlightGotoCommand {

    private MaidFreeFlightGotoCommand() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // 权限门：与另两条命令（MaidArmyCommand / MaidResyncCommand）同口径——没有它，
        // **任何玩家**都能把 64 格内任意一只女仆开到任意坐标。
        dispatcher.register(Commands.m_82127_("maid_smart")
                .requires(src -> src.m_6761_(2)) // 仅 OP
                .then(Commands.m_82127_("freeflight_goto")
                        .then(Commands.m_82129_("x", DoubleArgumentType.doubleArg())
                                .then(Commands.m_82129_("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.m_82129_("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> go(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                        DoubleArgumentType.getDouble(ctx, "z"),
                                                        null))
                                                .then(Commands.m_82129_("maid", net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                                        .executes(ctx -> go(ctx.getSource(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"),
                                                                net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "maid"))))))))
                // 【专用服务器验收入口】替代主人（照上游 /maid_smart flyfollow 的先例）
                .then(Commands.m_82127_("freeflight_enemy")
                        .then(Commands.m_82127_("clear")
                                .executes(ctx -> enemyClear(ctx.getSource())))
                        .then(Commands.m_82129_("target", net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                .executes(ctx -> enemy(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "target"), null))
                                .then(Commands.m_82129_("maid", net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                        .executes(ctx -> enemy(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "maid"))))))
                .then(Commands.m_82127_("freeflight_follow")
                        .then(Commands.m_82127_("clear")
                                .executes(ctx -> followClear(ctx.getSource())))
                        .then(Commands.m_82129_("target", net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                .executes(ctx -> follow(ctx.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "target"), null))
                                .then(Commands.m_82129_("maid", net.minecraft.commands.arguments.EntityArgument.m_91449_())
                                        .executes(ctx -> follow(ctx.getSource(),
                                                net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "target"),
                                                net.minecraft.commands.arguments.EntityArgument.m_91452_(ctx, "maid")))))));
    }

    /** /maid_smart freeflight_follow <目标实体> [女仆]：给女仆挂"替代主人"（无头/专用服验收入口） */
    private static int follow(CommandSourceStack src, net.minecraft.world.entity.Entity target,
                              net.minecraft.world.entity.Entity picked) {
        try {
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                src.m_81352_(Component.m_237113_("§c替代主人必须是活体实体。"));
                return 0;
            }
            EntityMaid maid = pickMaid(src, picked);
            if (maid == null) {
                return 0;
            }
            MaidFreeFlightController.setSubstituteOwner(maid, living);
            src.m_288197_(() -> Component.m_237113_("§a已给 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 挂上替代主人：" + living.m_7755_().getString()
                    + "（判定/起飞/落地待命全走同一套链路）"), true);
            return 1;
        } catch (Throwable t) {
            src.m_81352_(Component.m_237113_("§cfreeflight_follow 失败：" + t));
            return 0;
        }
    }

    /** /maid_smart freeflight_enemy <目标实体> [女仆]：挂"替代敌人"（验收战斗档飞行） */
    private static int enemy(CommandSourceStack src, net.minecraft.world.entity.Entity target,
                             net.minecraft.world.entity.Entity picked) {
        try {
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
                src.m_81352_(Component.m_237113_("§c目标必须是活体实体。"));
                return 0;
            }
            EntityMaid maid = pickMaid(src, picked);
            if (maid == null) {
                return 0;
            }
            MaidFreeFlightController.setSubstituteEnemy(maid, living);
            src.m_288197_(() -> Component.m_237113_("§a已给 " + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 挂上替代敌人：" + living.m_7755_().getString() + "（走的就是战斗档飞行链路）"), true);
            return 1;
        } catch (Throwable t) {
            src.m_81352_(Component.m_237113_("§cfreeflight_enemy 失败：" + t));
            return 0;
        }
    }

    private static int enemyClear(CommandSourceStack src) {
        try {
            Vec3 pos = src.m_81371_();
            var maids = src.m_81372_().m_45976_(EntityMaid.class, new AABB(pos, pos).m_82400_(64.0));
            for (EntityMaid m : maids) {
                MaidFreeFlightController.clearSubstituteEnemy(m);
            }
            src.m_288197_(() -> Component.m_237113_("§a已清除 " + maids.size() + " 只女仆的替代敌人。"), true);
            return 1;
        } catch (Throwable t) {
            src.m_81352_(Component.m_237113_("§cfreeflight_enemy clear 失败：" + t));
            return 0;
        }
    }

    /** 取女仆：显式指定优先，否则离执行点最近的 64 格内一只 */
    private static EntityMaid pickMaid(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        if (picked instanceof EntityMaid m) {
            return m;
        }
        Vec3 pos = src.m_81371_();
        EntityMaid maid = src.m_81372_().m_45976_(EntityMaid.class,
                        new AABB(pos, pos).m_82400_(64.0)).stream()
                .filter(EntityMaid::m_6084_)
                .min((a, b) -> Double.compare(a.m_20275_(pos.f_82479_, pos.f_82480_, pos.f_82481_),
                        b.m_20275_(pos.f_82479_, pos.f_82480_, pos.f_82481_)))
                .orElse(null);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("§c64 格内没有女仆（请显式指定一只）。"));
        }
        return maid;
    }

    private static int followClear(CommandSourceStack src) {
        try {
            Vec3 pos = src.m_81371_();
            var maids = src.m_81372_().m_45976_(EntityMaid.class, new AABB(pos, pos).m_82400_(64.0));
            for (EntityMaid m : maids) {
                MaidFreeFlightController.clearSubstituteOwner(m);
            }
            src.m_288197_(() -> Component.m_237113_("§a已清除 " + maids.size() + " 只女仆的替代主人。"), true);
            return 1;
        } catch (Throwable t) {
            src.m_81352_(Component.m_237113_("§cfreeflight_follow clear 失败：" + t));
            return 0;
        }
    }

    private static int go(CommandSourceStack src, double x, double y, double z, net.minecraft.world.entity.Entity picked) {
        try {
            ServerLevel level = src.m_81372_();
            Vec3 pos = new Vec3(x, y, z);
            EntityMaid maid;
            if (picked instanceof EntityMaid m) {
                maid = m;
            } else {
                if (picked != null) {
                    src.m_81352_(Component.m_237113_("§c那不是女仆。"));
                    return 0;
                }
                AABB box = new AABB(pos, pos).m_82400_(64.0);
                List<EntityMaid> list = level.m_45976_(EntityMaid.class, box).stream()
                        .filter(m -> m.m_6084_() && !m.isMaidInSittingPose())
                        .toList();
                maid = list.stream().min((a, b) -> Double.compare(a.m_20275_(x, y, z), b.m_20275_(x, y, z)))
                        .orElse(null);
                if (maid == null) {
                    src.m_81352_(Component.m_237113_("§c坐标 64 格内没有可用女仆（或指定一只）。"));
                    return 0;
                }
            }
            if (!MaidFreeFlightController.canFly(maid)) {
                src.m_81352_(Component.m_237113_("§c" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                        + " 现在飞不了（需要：总开关打开 + 资格物品/效果其一；"
                        + "且不在骑乘/睡觉/守家/空袭/扫帚状态）。当前：" + MaidFreeFlightController.status(maid)));
                return 0;
            }
            MaidFreeFlightController.setDebugTarget(maid, pos);
            src.m_288197_(() -> Component.m_237113_("§a" + com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 开始仿创造飞行 → " + (int) x + " " + (int) y + " " + (int) z), true);
            return 1;
        } catch (Throwable t) {
            src.m_81352_(Component.m_237113_("§cfreeflight_goto 失败：" + t));
            return 0;
        }
    }
}
