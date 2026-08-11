package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * query_memory（v1.5.86）：长期记忆检索工具——LLM 对话中按需调用。
 * 对齐 maidsoulcore 的 query_memory：prompt 只注入短投影，详细记忆由
 * LLM 主动检索（参数 = 查询词，返回 = 三路 RRF 融合的相关记忆）。
 */
public class QueryMemoryTool implements ITool<QueryMemoryTool.Result> {
    public static final String TOOL_ID = "query_memory";

    private static final String TOOL_DESC = "Use this when you need to recall detailed long-term memories "
            + "about the owner, past conversations, preferences, boundaries, relationships or events. "
            + "Pass a short query keyword or phrase describing what you want to recall. "
            + "Returns relevant memories with their importance levels.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("query").forGetter(Result::query)
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
        root.addProperties("query", StringParameter.create()
                .setDescription("检索词/短语，描述你想回忆的内容（如：主人喜欢什么 / 我们上次聊了什么）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (result.query() == null || result.query().isBlank()) {
            return callback.addToolResult("查询词不能为空，请用简短的词或短语描述想回忆的内容", toolId);
        }
        EntityMaid maid = callback.getMaid();
        // v1.5.190：与 remember 工具一致——记忆功能关闭时拒绝检索（旧版关了仍能读旧记忆）
        if (!AiMemoryManager.isEnabled(maid)) {
            return callback.addToolResult("记忆功能已关闭（女仆配置界面可开启）。", toolId);
        }
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
            return callback.addToolResult("记忆检索需要在服务端进行", toolId);
        }
        MinecraftServer server = level.m_7654_();
        AiMemoryStore store = maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl
                ? com.maidsmart.soul.SoulBindingService.storeFor(maid, sl)
                : AiMemoryStore.of(maid.m_20148_(), AiMemoryExtractor.memoryRoot(server));
        List<AiMemorySearch.Hit> hits = AiMemorySearch.search(store, result.query(), 6);
        if (hits.isEmpty()) {
            return callback.addToolResult("未找到相关记忆。", toolId);
        }
        // 命中计数（最近访问刷新）——v1.5.190：按内容去重后只 touch 一次
        // （旧版嵌套循环对同一段落反复 touch → 同 hash 合并时 accessCount 求和翻倍）
        long now = System.currentTimeMillis();
        java.util.Set<String> touched = new java.util.HashSet<>();
        for (AiMemoryModels.Paragraph p : store.paragraphs()) {
            if (touched.contains(p.hash())) {
                continue;
            }
            for (AiMemorySearch.Hit h : hits) {
                if (h.content().endsWith(p.content())) {
                    store.addParagraph(p.touch(now));
                    touched.add(p.hash());
                    break;
                }
            }
        }
        String text = AiMemorySearch.render(hits, 6);
        return callback.addToolResult("检索到相关记忆：" + text, toolId);
    }

    /** 工具参数 */
    public record Result(String query) {
    }
}
