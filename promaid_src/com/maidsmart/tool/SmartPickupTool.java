package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * smart_set_pickup：切换女仆的自动拾取模式（全部/关闭）。
 * 对应主模组 GUI 里的"拾物模式"开关。
 */
public class SmartPickupTool implements ITool<SmartPickupTool.Result> {
    public static final String TOOL_ID = "smart_set_pickup";
    private static final String ENABLE_PARAM_ID = "enable";
    private static final String TOOL_DESC = "Use this when the user asks you to start or stop picking up items on the ground.\n"
            + "Set enable=true to pick up items, enable=false to stop picking up.";
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf(ENABLE_PARAM_ID).forGetter(Result::enable)
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
        BoolParameter enable = BoolParameter.create();
        root.addProperties(ENABLE_PARAM_ID, enable);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid.isPickup() == result.enable()) {
            return callback.addToolResult(result.enable() ? "Already picking up items" : "Already not picking up items", toolId);
        }
        maid.setPickup(result.enable());
        return callback.addToolResult(result.enable() ? "Pickup mode enabled" : "Pickup mode disabled", toolId);
    }

    public record Result(boolean enable) {
    }
}
