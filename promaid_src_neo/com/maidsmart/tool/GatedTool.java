package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.mojang.serialization.Codec;

import java.util.concurrent.CompletableFuture;

/**
 * 只在「Promaid AI 总开关」与「AI 工具总开关」都打开时放行到真正工具的执行闸。
 * 注册发生在模组初始化，所以开关必须在这里按次读——关掉后下一次工具调用即停。
 */
public final class GatedTool<T> implements ITool<T> {
    private final ITool<T> delegate;
    /** 子开关/记忆开关；工具关闭时连 function schema 都不下发，避免模型白调一次再被拒。 */
    private final java.util.function.Predicate<EntityMaid> maidGate;

    public GatedTool(ITool<T> delegate) {
        this(delegate, maid -> true);
    }

    public GatedTool(ITool<T> delegate, java.util.function.Predicate<EntityMaid> maidGate) {
        this.delegate = delegate;
        this.maidGate = maidGate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String summary(EntityMaid maid) {
        return delegate.summary(maid);
    }

    @Override
    public Parameter parameters(ObjectParameter parent, EntityMaid maid) {
        return delegate.parameters(parent, maid);
    }

    @Override
    public Codec<T> codec() {
        return delegate.codec();
    }

    @Override
    public LLMCallback onCall(String toolId, T arg, LLMCallback callback) {
        if (!enabled(callback == null ? null : callback.getMaid())) {
            return callback; // 总开关关闭：静默放回，不写工具结果、不在聊天栏提示
        }
        return delegate.onCall(toolId, arg, callback);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolId, T arg, LLMCallback callback, LLMClient client) {
        if (!enabled(callback == null ? null : callback.getMaid())) {
            return CompletableFuture.completedFuture(callback);
        }
        return delegate.onCallAsync(toolId, arg, callback, client);
    }

    private boolean enabled(EntityMaid maid) {
        if (!MaidSmartConfig.AI_ENABLE.get() || !MaidSmartConfig.AITOOLS_ENABLE.get()) {
            return false;
        }
        return maid == null || this.maidGate.test(maid);
    }

    @Override
    public boolean trigger(EntityMaid maid, ChatCompletion request) {
        return enabled(maid) && delegate.trigger(maid, request);
    }
}
