package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyAuto;
import com.maidsmart.goety.MaidGoetyCompat;
import com.maidsmart.goety.MaidGoetyFlight;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实测七百〇四【1.20.1 · 第三种飞行 · 手动触发】：
 * <pre>
 *   /maid_smart goety_fly &lt;x&gt; &lt;y&gt; &lt;z&gt; [女仆]   —— 用飞行聚晶飞到坐标（巡航→下降→收手）
 *   /maid_smart goety_follow &lt;实体&gt; [女仆]      —— 用飞行聚晶跟着实体飞（近了绕圈伴飞）
 *   /maid_smart goety_combat &lt;目标&gt; [女仆]      —— 【G-3】绕着目标打盘旋：几何全用上游那套
 *                                                接敌机动（CombatOrbit + CombatManeuver 的五种打法）
 *   /maid_smart goety_boost [女仆] [实体]         —— 【G-3】放一发发射聚晶（一次性冲量）：给了实体
 *                                                就朝**背离它**的方向并略抬 20°（脱离用），否则用当前视线
 *   /maid_smart goety_auto &lt;on&gt; [女仆]           —— 【G-4】开/关"她自己判断要不要飞"
 *   /maid_smart goety_stop [女仆]                —— 收手（她开始自由下落，别在高空用）
 *   /maid_smart goety_status [女仆]              —— 看她现在有没有在推进、有没有法杖
 * </pre>
 * 无头测试服没有真玩家（也就没有"主人"可跟），所以这一档的验收入口就是这几条命令——
 * 与 {@code freeflight_goto} 那套同口径。
 *
 * <p>【非 OP 入口】这一批命令仍 {@code requires(hasPermission(2))}（OP 专属，是给测试/服主用的）。
 * 普通生存玩家走的是**另外两条**：女仆配置界面里的「飞行聚晶」一行 + 快捷键（见
 * {@code MaidConfigGoetyMixin} / {@code MaidGoetyKeysClient} / {@code MaidGoetyNetworking}）。
 *
 * <p>【1.20.1 落法】与 1.21.1 树逐字对应，只把名字换成 SRG（{@code m_82127_} = Commands.literal、
 * {@code m_82129_} = Commands.argument、{@code m_6761_} = hasPermission、
 * {@code m_288197_} = sendSuccess、{@code m_81352_} = sendFailure、{@code m_81372_} = getLevel、
 * {@code m_81371_} = getPosition、{@code m_91449_} = EntityArgument.entity、
 * {@code m_91460_} = EntityArgument.entities、{@code m_91452_} = EntityArgument.getEntity）。
 * 挂载点由 {@code ProMaidExtension.onRegisterCommands} 调 {@link #register}。
 */
public final class MaidGoetyFlyCommand {

    private MaidGoetyFlyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        MaidGoetyFlight.ensureHooked();
        MaidGoetyAuto.ensureHooked();
        dispatcher.register(Commands.m_82127_("maid_smart")
                .requires(src -> src.m_6761_(2))
                .then(Commands.m_82127_("goety_fly")
                        .then(Commands.m_82129_("x", DoubleArgumentType.doubleArg())
                                .then(Commands.m_82129_("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.m_82129_("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> fly(ctx.getSource(), null,
                                                        DoubleArgumentType.getDouble(ctx, "x"),
                                                        DoubleArgumentType.getDouble(ctx, "y"),
                                                        DoubleArgumentType.getDouble(ctx, "z")))
                                                .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                                        .executes(ctx -> fly(ctx.getSource(),
                                                                EntityArgument.m_91461_(ctx, "maid").iterator().next(),
                                                                DoubleArgumentType.getDouble(ctx, "x"),
                                                                DoubleArgumentType.getDouble(ctx, "y"),
                                                                DoubleArgumentType.getDouble(ctx, "z"))))))))
                .then(Commands.m_82127_("goety_follow")
                        .then(Commands.m_82129_("target", EntityArgument.m_91449_())
                                .executes(ctx -> follow(ctx.getSource(), null,
                                        EntityArgument.m_91452_(ctx, "target")))
                                .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                        .executes(ctx -> follow(ctx.getSource(),
                                                EntityArgument.m_91461_(ctx, "maid").iterator().next(),
                                                EntityArgument.m_91452_(ctx, "target"))))))
                .then(Commands.m_82127_("goety_combat")
                        .then(Commands.m_82129_("target", EntityArgument.m_91449_())
                                .executes(ctx -> combat(ctx.getSource(), null,
                                        EntityArgument.m_91452_(ctx, "target")))
                                .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                        .executes(ctx -> combat(ctx.getSource(),
                                                EntityArgument.m_91461_(ctx, "maid").iterator().next(),
                                                EntityArgument.m_91452_(ctx, "target"))))))
                .then(Commands.m_82127_("goety_boost")
                        .executes(ctx -> boost(ctx.getSource(), null, null))
                        .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                .executes(ctx -> boost(ctx.getSource(),
                                        EntityArgument.m_91461_(ctx, "maid").iterator().next(), null))
                                .then(Commands.m_82129_("away", EntityArgument.m_91449_())
                                        .executes(ctx -> boost(ctx.getSource(),
                                                EntityArgument.m_91461_(ctx, "maid").iterator().next(),
                                                EntityArgument.m_91452_(ctx, "away"))))))
                .then(Commands.m_82127_("goety_auto")
                        .then(Commands.m_82129_("on", BoolArgumentType.bool())
                                .executes(ctx -> auto(ctx.getSource(), null,
                                        BoolArgumentType.getBool(ctx, "on")))
                                .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                        .executes(ctx -> auto(ctx.getSource(),
                                                EntityArgument.m_91461_(ctx, "maid").iterator().next(),
                                                BoolArgumentType.getBool(ctx, "on"))))))
                .then(Commands.m_82127_("goety_stop")
                        .executes(ctx -> stop(ctx.getSource(), null))
                        .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                .executes(ctx -> stop(ctx.getSource(),
                                        EntityArgument.m_91461_(ctx, "maid").iterator().next()))))
                .then(Commands.m_82127_("goety_status")
                        .executes(ctx -> status(ctx.getSource(), null))
                        .then(Commands.m_82129_("maid", EntityArgument.m_91460_())
                                .executes(ctx -> status(ctx.getSource(),
                                        EntityArgument.m_91461_(ctx, "maid").iterator().next())))));
    }

    private static int fly(CommandSourceStack src, Entity picked, double x, double y, double z) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("没找到女仆"));
            return 0;
        }
        String bad = MaidGoetyFlight.flyTo(maid, new Vec3(x, y, z));
        if (bad != null) {
            src.m_81352_(Component.m_237113_(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶飞往 " + fmt(new Vec3(x, y, z));
        src.m_288197_(() -> Component.m_237113_(msg), true);
        PromaidLog.log("Goety推进", maid.m_7755_().getString() + " " + msg);
        return 1;
    }

    private static int follow(CommandSourceStack src, Entity picked, Entity target) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null || target == null) {
            src.m_81352_(Component.m_237113_("没找到女仆或目标"));
            return 0;
        }
        String bad = MaidGoetyFlight.follow(maid, target);
        if (bad != null) {
            src.m_81352_(Component.m_237113_(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶跟随 " + target.m_7755_().getString();
        src.m_288197_(() -> Component.m_237113_(msg), true);
        PromaidLog.log("Goety推进", maid.m_7755_().getString() + " " + msg);
        return 1;
    }

    /** 【G-3 战斗档】绕着目标打盘旋（几何全用上游的接敌机动那套）。 */
    private static int combat(CommandSourceStack src, Entity picked, Entity target) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null || !(target instanceof net.minecraft.world.entity.LivingEntity living)) {
            src.m_81352_(Component.m_237113_("没找到女仆，或目标不是生物"));
            return 0;
        }
        String bad = MaidGoetyFlight.combat(maid, living);
        if (bad != null) {
            src.m_81352_(Component.m_237113_(bad));
            return 0;
        }
        String msg = "开始用飞行聚晶盘旋 " + target.m_7755_().getString();
        src.m_288197_(() -> Component.m_237113_(msg), true);
        PromaidLog.log("Goety推进", maid.m_7755_().getString() + " " + msg);
        return 1;
    }

    /** 【G-3 加力档】放一发发射聚晶（一次性冲量；给了实体就朝背离它的方向）。 */
    private static int boost(CommandSourceStack src, Entity picked, Entity away) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("没找到女仆"));
            return 0;
        }
        String bad = MaidGoetyFlight.boost(maid, away);
        if (bad != null) {
            src.m_81352_(Component.m_237113_(bad));
            return 0;
        }
        src.m_288197_(() -> Component.m_237113_("已加力（发射聚晶）；当前速度 "
                + fmt(maid.m_20184_())), true);
        return 1;
    }

    /** 【G-4 自动档】开/关"她自己判断要不要飞"（默认关；存 persistentData）。 */
    private static int auto(CommandSourceStack src, Entity picked, boolean on) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("没找到女仆"));
            return 0;
        }
        MaidGoetyAuto.setAuto(maid, on);
        src.m_288197_(() -> Component.m_237113_("自动推进已" + (on ? "开启" : "关闭")
                + "（她会在主人拉开 " + (int) 16 + " 格以上时用飞行聚晶追，追到 6 格内落地交还跟随）"), true);
        return 1;
    }

    private static int stop(CommandSourceStack src, Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("没找到女仆"));
            return 0;
        }
        MaidGoetyFlight.stop(maid, "命令");
        src.m_288197_(() -> Component.m_237113_("已收手"), true);
        return 1;
    }

    private static int status(CommandSourceStack src, Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.m_81352_(Component.m_237113_("没找到女仆"));
            return 0;
        }
        String line = "推进中=" + MaidGoetyFlight.isActive(maid) + " 档位=" + MaidGoetyFlight.describe(maid)
                + " 自动=" + MaidGoetyAuto.isAuto(maid)
                + " Goety=" + MaidGoetyCompat.available()
                + " 法杖=" + MaidGoetyCompat.staffs(maid)
                + " 位置=" + fmt(maid.m_20182_())
                + " 速度=" + fmt(maid.m_20184_())
                + " 视线pitch=" + String.format(java.util.Locale.ROOT, "%.1f", maid.m_146909_());
        src.m_288197_(() -> Component.m_237113_(line), false);
        PromaidLog.log("Goety推进", maid.m_7755_().getString() + " " + line);
        return 1;
    }

    private static EntityMaid asMaid(CommandSourceStack src, Entity picked) {
        if (picked instanceof EntityMaid m) {
            return m;
        }
        try {
            Vec3 pos = src.m_81371_();
            AABB box = new AABB(pos, pos).m_82400_(64.0);
            return src.m_81372_().m_45976_(EntityMaid.class, box).stream().findFirst().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, %.2f)", v.f_82479_, v.f_82480_, v.f_82481_);
    }
}
