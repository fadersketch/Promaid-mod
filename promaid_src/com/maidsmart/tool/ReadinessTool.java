package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * v1.2.2 实测五百七十八：smart_readiness —— 「她现在到底能不能干这件事」的自检工具。
 *
 * ── 为什么需要 ──
 * 既有 smart_report 只报"血/好感/任务/日程/拾取"，而本模组这几年长出来的东西全都不在里面：
 * 空袭三件套（鞘翅 + 武器 + 燃料）、位移类法术能不能顶替燃料、远程有没有弹药、驻守/排班
 * 状态、工作锚点在哪。结果是模型只能"猜"——玩家问"她能飞吗""她怎么不去干活"，模型答不出
 * 所以然。本工具把这些**全部只读**地摊开，模型才有依据决定要不要调 switch_task / air_raid /
 * work_area。
 *
 * ── 只读保证 ──
 * 不写任何状态、不锁目标、不切任务（连缓存都不碰：{@code hasClimbSpell} 是只读缓存查询）。
 * 判定口径全部**复用她自己那套**（{@code MaidFlightKit.missingParts} /
 * {@code elytraDiagnostic}），避免出现"工具说能飞、她实际飞不起来"这种口径分裂。
 */
public class ReadinessTool implements ITool<ReadinessTool.Result> {
    public static final String TOOL_ID = "smart_readiness";

    private static final String TOOL_DESC =
            "Use this when you need to know whether the maid can actually perform something right now: "
            + "can she fly / start an air raid, does she have ammo, is she on duty (home mode) or on a "
            + "schedule, where is her work area, how is her health.\n"
            + "Read-only: it changes nothing. Returns the flight kit check (elytra + weapon + "
            + "firework/peacock fan/flight spell), ammo, home-mode/schedule state, work anchors and health.";

    private static final Codec<Result> CODEC = Codec.unit(new Result());

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
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_READINESS.get()) {
            return callback.addToolResult("状态自检工具已被禁用（设置里可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        StringBuilder sb = new StringBuilder();

        // ---- 身份与任务 ----
        String taskName = "（未知）";
        String taskUid = "";
        boolean flight = false;
        boolean ranged = false;
        try {
            if (maid.getTask() != null) {
                taskUid = String.valueOf(maid.getTask().getUid());
                taskName = SwitchTaskTool.taskName(maid.getTask());
                flight = com.maidsmart.combat.MaidFlightKit.isFlightTask(maid);
                ranged = com.maidsmart.combat.MaidFlightKit.isRangedTask(maid);
            }
        } catch (Throwable ignored) {
        }
        sb.append("任务：").append(taskName);
        if (!taskUid.isEmpty()) {
            sb.append("（").append(taskUid).append("）");
        }
        if (flight) {
            sb.append("［空袭·").append(ranged ? "远程" : "近战").append("］");
        }
        sb.append("；血量：").append(fmt(maid.m_21223_())).append("/").append(fmt(maid.m_21233_()));

        // ---- 空袭三件套（口径复用她自己的判定）----
        try {
            boolean elytra = com.maidsmart.combat.MaidFlightKit.hasElytra(maid);
            boolean weapon = com.maidsmart.combat.MaidFlightKit.hasWeapon(maid);
            boolean firework = com.maidsmart.combat.MaidFlightKit.hasFirework(maid);
            boolean fan = com.maidsmart.combat.MaidFlightKit.hasFan(maid);
            boolean spell = com.maidsmart.combat.MaidFlightKit.hasClimbSpell(maid);
            sb.append("\n飞行准备：鞘翅=").append(yn(elytra))
                    .append("、武器=").append(yn(weapon))
                    .append("、燃料（烟花=").append(yn(firework))
                    .append(" / 孔雀羽扇=").append(yn(fan))
                    .append(" / 位移类法术=").append(yn(spell)).append("）");
            String missing = com.maidsmart.combat.MaidFlightKit.missingParts(maid);
            if (missing == null || missing.isEmpty()) {
                sb.append(" → 齐了，可以空袭");
            } else {
                sb.append(" → ⚠ 缺「").append(missing).append("」，缺件时空袭会退回地面战斗");
            }
            if (ranged) {
                sb.append("\n远程弹药：").append(yn(com.maidsmart.combat.MaidFlightKit.hasAmmoForRanged(maid)));
            }
            if (!elytra) {
                String diag = com.maidsmart.combat.MaidFlightKit.elytraDiagnostic(maid);
                if (diag != null && !diag.isEmpty()) {
                    sb.append("\n鞘翅诊断：").append(diag);
                }
            }
            if (flight) {
                sb.append("\n飞行状态：滑翔中=").append(yn(com.maidsmart.combat.MaidFlightKit.isGliding(maid)))
                        .append("、滞空=").append(yn(com.maidsmart.combat.MaidFlightKit.isFlightAirborne(maid)));
            }
        } catch (Throwable ignored) {
        }

        // ---- 驻守 / 排班 / 工作锚点 ----
        try {
            sb.append("\n驻守（home 模式）=").append(maid.isHomeModeEnable() ? "开" : "关（跟随中）");
            sb.append("；排班中=").append(com.maidsmart.schedule.ScheduleData.isOn(maid) ? "是" : "否");
            SchedulePos sp = maid.getSchedulePos();
            if (sp != null) {
                sb.append("\n工作锚点=").append(pos(sp.getWorkPos()))
                        .append("、休闲=").append(pos(sp.getIdlePos()))
                        .append("、睡眠=").append(pos(sp.getSleepPos()))
                        .append("（已配置=").append(yn(sp.isConfigured())).append("）");
            }
        } catch (Throwable ignored) {
        }

        // ---- 主人侧：饥饿（喂食/喂水相关决策常要用）----
        try {
            LivingEntity owner = maid.m_269323_();
            if (owner instanceof Player player) {
                sb.append("\n主人：饥饿值=").append(player.m_36324_().m_38702_())
                        .append("/20");
            } else {
                sb.append("\n主人：不在线/未认领");
            }
        } catch (Throwable ignored) {
        }

        return callback.addToolResult(sb.toString(), toolId);
    }

    private static String yn(boolean b) {
        return b ? "有" : "无";
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String pos(BlockPos p) {
        return p == null ? "（未设）" : "(" + p.m_123341_() + "," + p.m_123342_() + "," + p.m_123343_() + ")";
    }

    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.m_9236_().m_5776_()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        net.minecraft.server.level.ServerLevel level =
                (net.minecraft.server.level.ServerLevel) maid.m_9236_();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.m_7654_().execute(() -> {
            try {
                future.complete(onCall(toolCallId, result, callback));
            } catch (Throwable t) {
                future.complete(callback.addToolResult("Tool execution failed: " + t, toolCallId));
            }
        });
        return future;
    }

    public record Result() {
    }
}
