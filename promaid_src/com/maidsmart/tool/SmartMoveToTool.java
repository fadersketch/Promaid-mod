package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;

/**
 * smart_move_to：让女仆走到主人身边。
 * 与 switch_follow_state 的分工：该工具只做一次"走过去"，不改变跟随模式。
 */
public class SmartMoveToTool implements ITool<SmartMoveToTool.Result> {
    public static final String TOOL_ID = "smart_move_to";
    private static final String TARGET_PARAM_ID = "target";
    private static final String TOOL_DESC = "Use this when the user asks you to come closer, walk over to them, or stand by their side.\n"
            + "Set target=owner to walk to the user's side.\n"
            + "Do not use this to toggle follow mode (use switch_follow_state for that).";
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(TARGET_PARAM_ID).forGetter(Result::target)
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
        StringParameter target = StringParameter.create().addEnumValues("owner");
        root.addProperties(TARGET_PARAM_ID, target);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        LivingEntity owner = maid.m_269323_();
        if (!(owner instanceof ServerPlayer)) {
            return callback.addToolResult("No owner found", toolId);
        }
        BlockPos pos = owner.m_20183_();
        maid.m_6274_().m_21879_(MemoryModuleType.f_26370_,
                new WalkTarget(new BlockPosTracker(pos), 1.0f, 2));
        return callback.addToolResult("Walking to the owner's side", toolId);
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
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.m_9236_();
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

    public record Result(String target) {
    }
}
