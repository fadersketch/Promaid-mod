package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyCompat;
import com.maidsmart.goety.MaidGoetyFlight;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实测七百〇四【第三种飞行 · 手动触发】：
 * <pre>
 *   /maid_smart goety_fly &lt;x&gt; &lt;y&gt; &lt;z&gt; [女仆]   —— 用飞行聚晶飞到坐标（巡航→下降→收手）
 *   /maid_smart goety_follow &lt;实体&gt; [女仆]      —— 用飞行聚晶跟着实体飞（近了绕圈伴飞）
 *   /maid_smart goety_combat &lt;目标&gt; [女仆]      —— 【G-3】绕着目标打盘旋：几何全用上游那套
 *                                                接敌机动（CombatOrbit + CombatManeuver 的五种打法），
 *                                                我们只负责"朝那个盘旋点推"
 *   /maid_smart goety_boost [女仆] [实体]         —— 【G-3】放一发发射聚晶（一次性冲量）：给了实体
 *                                                就朝**背离它**的方向并略抬 20°（脱离用），否则用当前视线
 *   /maid_smart goety_stop [女仆]                —— 收手（她开始自由下落，别在高空用）
 *   /maid_smart goety_status [女仆]              —— 看她现在有没有在推进、有没有法杖
 * </pre>
 * 无头测试服没有真玩家（也就没有"主人"可跟），所以这一档的验收入口就是这三条命令——
 * 与 {@code freeflight_goto} 那套同口径。
 */
public final class MaidGoetyFlyCommand {

    private MaidGoetyFlyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        MaidGoetyFlight.ensureHooked();
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("goety_fly")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> fly(ctx.getSource(), null,
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                        DoubleArgumentType.getDouble(ctx, "z")))
                                                .then(Commands.argument("maid", EntityArgument.entities())
                                                        .executes(ctx -> fly(ctx.getSource(),
                                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"))))))))
                .then(Commands.literal("goety_follow")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> follow(ctx.getSource(), null,
                                        EntityArgument.getEntity(ctx, "target")))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(ctx -> follow(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                EntityArgument.getEntity(ctx, "target"))))))
                .then(Commands.literal("goety_combat")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> combat(ctx.getSource(), null,
                                        EntityArgument.getEntity(ctx, "target")))
                                .then(Commands.argument("maid", EntityArgument.entities())
                                        .executes(ctx -> combat(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                EntityArgument.getEntity(ctx, "target"))))))
                .then(Commands.literal("goety_boost")
                        .executes(ctx -> boost(ctx.getSource(), null, null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> boost(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next(), null))
                                .then(Commands.argument("away", EntityArgument.entity())
                                        .executes(ctx -> boost(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                EntityArgument.getEntity(ctx, "away"))))))
                .then(Commands.literal("goety_stop")
                        .executes(ctx -> stop(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> stop(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next()))))
                .then(Commands.literal("goety_status")
                        .executes(ctx -> status(ctx.getSource(), null))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> status(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next())))));
    }

    private static int fly(CommandSourceStack src, Entity picked, double x, double y, double z) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String bad = MaidGoetyFlight.flyTo(maid, new Vec3(x, y, z));
        if (bad != null) {
            src.sendFailure(Component.literal(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶飞往 " + fmt(new Vec3(x, y, z));
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("Goety推进", maid.getName().getString() + " " + msg);
        return 1;
    }

    private static int follow(CommandSourceStack src, Entity picked, Entity target) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null || target == null) {
            src.sendFailure(Component.literal("没找到女仆或目标"));
            return 0;
        }
        String bad = MaidGoetyFlight.follow(maid, target);
        if (bad != null) {
            src.sendFailure(Component.literal(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶跟随 " + target.getName().getString();
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("Goety推进", maid.getName().getString() + " " + msg);
        return 1;
    }

    /** 【G-3 战斗档】绕着目标打盘旋（几何全用上游的接敌机动那套）。 */
    private static int combat(CommandSourceStack src, Entity picked, Entity target) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null || !(target instanceof net.minecraft.world.entity.LivingEntity living)) {
            src.sendFailure(Component.literal("没找到女仆，或目标不是生物"));
            return 0;
        }
        String bad = MaidGoetyFlight.combat(maid, living);
        if (bad != null) {
            src.sendFailure(Component.literal(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶盘旋 " + target.getName().getString();
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("Goety推进", maid.getName().getString() + " " + msg);
        return 1;
    }

    /** 【G-3 加力档】放一发发射聚晶（一次性冲量；给了实体就朝背离它的方向）。 */
    private static int boost(CommandSourceStack src, Entity picked, Entity away) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String bad = MaidGoetyFlight.boost(maid, away);
        if (bad != null) {
            src.sendFailure(Component.literal(bad));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("已加力（发射聚晶）；当前速度 "
                + fmt(maid.getDeltaMovement())), true);
        return 1;
    }

    private static int stop(CommandSourceStack src, Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        MaidGoetyFlight.stop(maid, "命令");
        src.sendSuccess(() -> Component.literal("已收手"), true);
        return 1;
    }

    private static int status(CommandSourceStack src, Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String line = "推进中=" + MaidGoetyFlight.isActive(maid) + " 档位=" + MaidGoetyFlight.describe(maid)
                + " Goety=" + MaidGoetyCompat.available()
                + " 法杖=" + MaidGoetyCompat.staffs(maid)
                + " 位置=" + fmt(maid.position())
                + " 速度=" + fmt(maid.getDeltaMovement())
                + " 视线pitch=" + String.format(java.util.Locale.ROOT, "%.1f", maid.getXRot());
        src.sendSuccess(() -> Component.literal(line), false);
        PromaidLog.log("Goety推进", maid.getName().getString() + " " + line);
        return 1;
    }

    private static EntityMaid asMaid(CommandSourceStack src, Entity picked) {
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

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}
