package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;

/**
 * smart_report：信息型工具，让女仆汇报自身状态。
 * 供 LLM 在需要"女仆现在状态"时调用（比 query_game_context 更聚焦于关系与健康）。
 */
public class SmartReportTool implements ITool<SmartReportTool.Result> {
    public static final String TOOL_ID = "smart_report";
    private static final String TOOL_DESC = "Use this when the user asks about your current state: health, favorability, mood, task or schedule.\n"
            + "Returns a short summary of your condition.";
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
        EntityMaid maid = callback.getMaid();
        int favorability = maid.getFavorability();
        int level = favorability < 64 ? 0 : (favorability < 192 ? 1 : (favorability < 384 ? 2 : 3));
        // v1.5.190：getTask() 可能为 null（刚召出/数据未就绪）——旧版直接
        // getTask().getUid() 会 NPE 崩服务端线程（LLM 随时可能调 smart_report）
        String taskUid = "（空闲）";
        try {
            if (maid.getTask() != null && maid.getTask().getUid() != null) {
                taskUid = maid.getTask().getUid().toString();
            }
        } catch (Exception ignored) {
        }
        String report = String.format("Health: %.1f/%.1f, Favorability: level %d (%d/384), Task: %s, Schedule: %s, Pickup: %s",
                maid.m_21223_(), maid.m_21233_(), level, favorability,
                taskUid, maid.getSchedule(), maid.isPickup() ? "on" : "off");
        return callback.addToolResult(report, toolId);
    }

    public record Result() {
    }
}
