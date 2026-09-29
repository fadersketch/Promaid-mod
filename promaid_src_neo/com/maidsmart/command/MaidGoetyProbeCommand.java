package com.maidsmart.command;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyCompat;
import com.maidsmart.tool.PromaidLog;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 诡厄巫法（Goety）兼容的**诊断探针**——只读扫描 + 手动放一发，用来在没有 UI、没有真玩家的
 * 无头测试服里回答一个关键未知：
 *
 * <p><b>飞行聚晶靠「每 tick 覆盖 deltaMovement」，可女仆是 Mob——她的
 * MoveControl / travel 会不会把我们写进去的速度吃掉？</b>
 * Goety 玩家版不用管这个（玩家 travel 走输入速度 + hasImpulse 同步），
 * 女仆这一侧必须实测。这正是本工程在仿创造飞行上踩过的同一个坑。
 *
 * <pre>
 *   /maid_smart goety_probe                    —— 扫女仆：Goety 在不在、她身上有哪些法杖/聚晶、
 *                                                 饰品栏里有什么 goety 物品、当前位置与速度
 *   /maid_smart goety_probe cast flying 5      —— 连续 5 秒每 tick 放一发飞行聚晶（真·恒速飞行）
 *   /maid_smart goety_probe cast launch 3      —— 连续 3 秒每 20 tick 放一发发射聚晶（它的冷却）
 *   （两条都可再跟一个实体选择器）
 * </pre>
 *
 * 日志走 PromaidLog（分类「Goety探针」），每 10 tick 一行位置+速度——速度是不是「写进去就留住」，
 * 一眼看得出来。
 */
public final class MaidGoetyProbeCommand {

    /** 进行中的任务：key、法杖（决定威力倍率）、结束 tick、已跑 tick。 */
    private record Task(String key, ItemStack staff, long endTick, int tick) {
    }

    private static final Map<UUID, Task> TASKS = new LinkedHashMap<>();
    private static boolean hooked;

    private MaidGoetyProbeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        hook();
        dispatcher.register(Commands.literal("maid_smart")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("goety_probe")
                        .executes(ctx -> scan(ctx.getSource(), null))
                        .then(Commands.literal("cast")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                                .executes(ctx -> cast(ctx.getSource(), null,
                                                        StringArgumentType.getString(ctx, "key"),
                                                        IntegerArgumentType.getInteger(ctx, "seconds")))
                                                .then(Commands.argument("maid", EntityArgument.entities())
                                                        .executes(ctx -> cast(ctx.getSource(),
                                                                EntityArgument.getEntities(ctx, "maid").iterator().next(),
                                                                StringArgumentType.getString(ctx, "key"),
                                                                IntegerArgumentType.getInteger(ctx, "seconds")))))))
                        .then(Commands.argument("maid", EntityArgument.entities())
                                .executes(ctx -> scan(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "maid").iterator().next())))));
    }

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.register(new MaidGoetyProbeCommand());
    }

    private static int scan(CommandSourceStack src, net.minecraft.world.entity.Entity picked) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆（写个选择器，或站到她附近）"));
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Goety 反射路径：" + (MaidGoetyCompat.available() ? "正常" : "没解析到"));
        Map<String, ItemStack> staffs = MaidGoetyCompat.staffs(maid);
        if (staffs.isEmpty()) {
            lines.add("身上没有【装着聚晶的法杖】——她放不了 Goety 法术（法杖才是施法器，聚晶包只是存货）");
        } else {
            for (Map.Entry<String, ItemStack> e : staffs.entrySet()) {
                ItemStack focus = MaidGoetyCompat.focusOf(e.getValue());
                lines.add(e.getKey() + "：" + MaidGoetyCompat.itemId(e.getValue())
                        + "，当前聚晶 = " + MaidGoetyCompat.itemId(focus));
            }
        }
        lines.add("可用聚晶 id：" + MaidGoetyCompat.focusIds(maid));
        List<String> curiosGoety = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : MaidGoetyCompat.curios(maid).entrySet()) {
            String id = MaidGoetyCompat.itemId(e.getValue());
            if (id.startsWith("goety:")) {
                curiosGoety.add(e.getKey() + "=" + id);
            }
        }
        lines.add("饰品栏里的 Goety 物品：" + curiosGoety);
        lines.add("位置 " + fmt(maid.position()) + " 速度 " + fmt(maid.getDeltaMovement())
                + " NoGravity=" + maid.isNoGravity() + " 着地=" + maid.onGround());
        for (String s : lines) {
            src.sendSuccess(() -> Component.literal(s), false);
            PromaidLog.log("Goety探针", maid.getName().getString() + " " + s);
        }
        return 1;
    }

    private static int cast(CommandSourceStack src, net.minecraft.world.entity.Entity picked, String key, int seconds) {
        EntityMaid maid = asMaid(src, picked);
        if (maid == null) {
            src.sendFailure(Component.literal("没找到女仆"));
            return 0;
        }
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (!"flying".equals(k) && !"launch".equals(k)) {
            src.sendFailure(Component.literal("key 只能是 flying（飞行聚晶）或 launch（发射聚晶）"));
            return 0;
        }
        if (!MaidGoetyCompat.available()) {
            src.sendFailure(Component.literal("这台服务器没装 Goety（或版本反射路径不符），放不了"));
            return 0;
        }
        ItemStack staff = ItemStack.EMPTY;
        for (ItemStack s : MaidGoetyCompat.staffs(maid).values()) {
            staff = s;
            break;
        }
        TASKS.put(maid.getUUID(), new Task(k, staff, maid.level().getGameTime() + seconds * 20L, 0));
        String msg = "开始放 " + k + "（" + seconds + " 秒；法杖="
                + (staff.isEmpty() ? "无（基准威力）" : MaidGoetyCompat.itemId(staff)) + "）";
        src.sendSuccess(() -> Component.literal(msg), true);
        PromaidLog.log("Goety探针", maid.getName().getString() + " " + msg);
        return 1;
    }

    /** 每 tick 推一次：flying 每 tick 覆盖（它的冷却就是 0）、launch 每 20 tick 一发（它的冷却）。 */
    @SubscribeEvent
    public void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        Task task = TASKS.get(maid.getUUID());
        if (task == null) {
            return;
        }
        long now = maid.level().getGameTime();
        if (now > task.endTick()) {
            TASKS.remove(maid.getUUID());
            PromaidLog.log("Goety探针", maid.getName().getString() + " 结束（" + task.key()
                    + "）；末位置 " + fmt(maid.position()) + " 末速度 " + fmt(maid.getDeltaMovement()));
            return;
        }
        boolean fire = "flying".equals(task.key()) || task.tick() % 20 == 0;
        if (fire) {
            MaidGoetyCompat.cast(maid, task.key(), task.staff());
        }
        if (task.tick() % 10 == 0) {
            PromaidLog.log("Goety探针", maid.getName().getString()
                    + " t=" + task.tick() + " 位置 " + fmt(maid.position())
                    + " 速度 " + fmt(maid.getDeltaMovement())
                    + " NoGravity=" + maid.isNoGravity() + " 着地=" + maid.onGround()
                    + " 视线y=" + String.format(Locale.ROOT, "%.3f", maid.getLookAngle().y));
        }
        TASKS.put(maid.getUUID(), new Task(task.key(), task.staff(), task.endTick(), task.tick() + 1));
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

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
