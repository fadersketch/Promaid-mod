package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * v1.2.2 实测五百七十八：smart_work_area —— 「这块地方归你管 / 回来跟着我」。
 *
 * ── 用的是哪套数据（不另起一套定点系统）──
 * TLM 的 {@code SchedulePos}：工作锚点 + 休闲锚点 + 睡眠锚点 + "home 模式（不跟随）"开关。
 * 这也正是**潜行+中键工位标记**（实测五百六十二）与 **河童的罗盘**写的那一份数据——三者
 * 谁后写谁生效。本工具补的是"对话里说人话"这条路：
 * <ul>
 *   <li>{@code action=set}：把工作/休闲锚点设到指定位置并**开启 home 模式**——她随后会守在
 *       这片区域干活（范围 = 配置里的「排班活动半径」，见排班表）；</li>
 *   <li>{@code action=follow}：关掉 home 模式——她恢复跟随主人（"别干活了跟着我"）；</li>
 *   <li>{@code action=info}：只读回报三个锚点与 home 状态（不修改任何东西）。</li>
 * </ul>
 *
 * ── 位置从哪来 ──
 * {@code where=owner}（默认）取**主人脚下**，{@code where=self} 取女仆自己脚下。
 * 刻意不做"主人看向的方块"的射线：那要自己拼 ClipContext，两树名字差异大、收益小
 * ——玩家站在目标位置说话就够了（与中键标记"标到那个方块"互为补充）。
 *
 * ── 与排班的关系 ──
 * 排班（日程表）会按时间段切任务；本工具只管**锚点**，所以正在排班的女仆也允许设置锚点
 * ——排班本身的开关仍归排班表（工具结果里会提一句，避免模型以为设了锚点就等于排好了班）。
 */
public class WorkAreaTool implements ITool<WorkAreaTool.Result> {
    public static final String TOOL_ID = "smart_work_area";

    private static final String ACTION_PARAM = "action";
    private static final String WHERE_PARAM = "where";

    private static final String TOOL_DESC =
            "Use this when the user assigns a place to the maid, e.g. 'stay and work around here', "
            + "'guard this spot', or 'stop working and follow me'.\n"
            + "action=set (default) sets her work/idle anchor to the given place and enables home mode "
            + "(she stays and works around it, radius = the schedule activity radius); "
            + "action=follow disables home mode so she follows the user again; "
            + "action=info only reports the current anchors and home-mode state.\n"
            + "where=owner (default) = at the user's feet, where=self = where the maid stands now.\n"
            + "This writes the same data as TLM's home positions / the middle-click work marker.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf(ACTION_PARAM, "set").forGetter(Result::action),
            Codec.STRING.optionalFieldOf(WHERE_PARAM, "owner").forGetter(Result::where)
    ).apply(instance, Result::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return TOOL_DESC;
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties(ACTION_PARAM, StringParameter.create()
                .addEnumValues("set", "follow", "info").setDefaultValue("set")
                .setDescription("set=把工作区设到该处并进入驻守（默认）/ follow=解除驻守恢复跟随 / "
                        + "info=只查看当前锚点"));
        root.addProperties(WHERE_PARAM, StringParameter.create()
                .addEnumValues("owner", "self").setDefaultValue("owner")
                .setDescription("位置：owner=主人脚下（默认，玩家站哪儿就设哪儿）/ self=女仆当前所在处"
                        + "（仅 action=set 时用）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_WORK_AREA.get()) {
            return callback.addToolResult("工作区工具已被禁用（设置里可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.level() instanceof ServerLevel level)) {
            return callback.addToolResult("设置工作区需要在服务端进行", toolId);
        }
        SchedulePos sp = maid.getSchedulePos();
        if (sp == null) {
            return callback.addToolResult("取不到她的排班锚点数据（SchedulePos 为空）", toolId);
        }
        String action = result.action() == null
                ? "set" : result.action().trim().toLowerCase(java.util.Locale.ROOT);
        if ("info".equals(action)) {
            return callback.addToolResult(info(maid, sp), toolId);
        }
        if ("follow".equals(action)) {
            try {
                maid.setHomeModeEnable(false);
            } catch (Throwable t) {
                return callback.addToolResult("解除驻守失败：" + t, toolId);
            }
            ToolKit.bubble(maid, "好，我跟着你");
            return callback.addToolResult("已解除驻守（home 模式关闭）——她现在恢复跟随主人；"
                    + "工作锚点仍保留在 " + pos(sp.getWorkPos()) + "，下次开启驻守就能直接用", toolId);
        }
        // action = set
        String where = result.where() == null
                ? "owner" : result.where().trim().toLowerCase(java.util.Locale.ROOT);
        BlockPos pos;
        if ("self".equals(where)) {
            pos = maid.blockPosition();
        } else {
            LivingEntity owner = maid.getOwner();
            if (!(owner instanceof Player)) {
                return callback.addToolResult("主人不在线，无法取「主人脚下」的位置；"
                        + "可以改用 where=self 把工作区设在她自己站的地方", toolId);
            }
            pos = owner.blockPosition();
        }
        try {
            // 与排班表开启排班时同一套写法（ScheduleManager：setHomeModeEnable + setConfigured），
            // 也与 TLM GUI 的 home 按钮同一条路径——不自己拼 SchedulePos 字段
            sp.setHomeModeEnable(maid, pos);
            sp.setConfigured(true);
            sp.restrictTo(maid);
        } catch (Throwable t) {
            return callback.addToolResult("设置工作区失败：" + t, toolId);
        }
        ToolKit.bubble(maid, "这儿交给我");
        StringBuilder sb = new StringBuilder();
        sb.append("已把她的工作/休闲锚点设到 ").append(pos(pos))
                .append("，并开启驻守（home 模式）——她会在这片区域干活（范围 = 配置里的「排班活动半径」）");
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            sb.append("。注意：她**正在排班中**，任务仍由日程表按时间段切换，本工具只改了锚点位置");
        }
        return callback.addToolResult(sb.toString(), toolId);
    }

    /** 只读回报（info / 结果里附注用） */
    private static String info(EntityMaid maid, SchedulePos sp) {
        StringBuilder sb = new StringBuilder("她的锚点：");
        sb.append("工作 ").append(pos(sp.getWorkPos()));
        sb.append("、休闲 ").append(pos(sp.getIdlePos()));
        sb.append("、睡眠 ").append(pos(sp.getSleepPos()));
        sb.append("；驻守（home）=").append(maid.isHomeModeEnable() ? "开" : "关（跟随中）");
        sb.append("；锚点已配置=").append(sp.isConfigured() ? "是" : "否");
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            sb.append("；排班中=是");
        }
        return sb.toString();
    }

    private static String pos(BlockPos p) {
        return p == null ? "（未设）" : "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.level().isClientSide()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        ServerLevel level = (ServerLevel) maid.level();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete(onCall(toolCallId, result, callback));
            } catch (Throwable t) {
                future.complete(callback.addToolResult("Tool execution failed: " + t, toolCallId));
            }
        });
        return future;
    }

    public record Result(String action, String where) {
    }
}
