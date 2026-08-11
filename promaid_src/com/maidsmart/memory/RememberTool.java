package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * remember（v1.5.95，借鉴 maidsoulcore PlannerAgent 的 memory_event 工具）：
 * LLM 在对话中【当场决定】记什么——与被动提取（攒 12 条后台批量）互补。
 *
 * 玩家明确说"记住/以后别忘/我的偏好是…"或对话中出现对长期互动重要的信息时，
 * LLM 主动调用本工具写入记忆（走写入策略分层：关系/承诺/偏好自动抬高重要度）。
 * 参数：content（内容，必填）、type（preference/boundary/relation/promise/emotion/event）、
 * importance（1-10，可选，默认 6）、tags（英文逗号分隔，可选）。
 */
public class RememberTool implements ITool<RememberTool.Result> {
    public static final String TOOL_ID = "remember";

    private static final String TOOL_DESC = "Use this when the user asks you to REMEMBER something long-term "
            + "(\"记住我喜欢红茶\", \"以后别忘了我讨厌蜘蛛\", \"记住了，这是承诺\") or when important information "
            + "about the owner's preferences, boundaries, relationships, promises or emotional events "
            + "comes up in conversation.\n"
            + "This writes a permanent memory that will be recalled in future conversations.\n"
            + "Do NOT use it for trivial chit-chat or temporary topics.\n"
            + "type: preference (偏好/喜欢/讨厌), boundary (边界/禁忌), relation (关系), "
            + "promise (承诺), emotion (情绪事件), event (一般事件).\n"
            + "importance 1-10 (default 6). tags: comma-separated English tags (optional).";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("content").forGetter(Result::content),
            Codec.STRING.optionalFieldOf("type", "preference").forGetter(Result::type),
            Codec.INT.optionalFieldOf("importance", 6).forGetter(Result::importance),
            Codec.STRING.optionalFieldOf("tags", "").forGetter(Result::tags)
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
        root.addProperties("content", StringParameter.create()
                .setDescription("要记住的内容（中文，可直接进入记忆）"));
        root.addProperties("type", StringParameter.create()
                .addEnumValues("preference", "boundary", "relation", "promise", "emotion", "event")
                .setDescription("记忆类型"));
        root.addProperties("importance", com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter.create()
                .setMinimum(1).setMaximum(10)
                .setDescription("重要度 1-10（默认 6）"));
        root.addProperties("tags", StringParameter.create()
                .setDescription("英文标签，逗号分隔（可选）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        // v1.5.96：工具开关（配置面板 aitools.remember——接更强 agent 时可关闭让位）
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_REMEMBER.get()) {
            return callback.addToolResult("remember 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        String content = result.content() == null ? "" : result.content().trim();
        if (content.isEmpty()) {
            return callback.addToolResult("内容不能为空。请把要记住的话写在 content 里。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
            return callback.addToolResult("记忆写入需要在服务端进行", toolId);
        }
        if (!AiMemoryManager.isEnabled(maid)) {
            return callback.addToolResult("记忆功能已关闭（女仆配置界面可开启）。", toolId);
        }
        // 类型 → 标签 + 写入策略
        String type = result.type() == null ? "preference" : result.type().trim().toLowerCase(java.util.Locale.ROOT);
        int importance = Math.max(1, Math.min(10, result.importance()));
        List<String> tags = new ArrayList<>();
        if (result.tags() != null) {
            for (String t : result.tags().split(",")) {
                if (!t.isBlank()) {
                    tags.add(t.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        tags.add(type);
        // 关系/承诺类 → 同时写关系三元组
        AiMemoryStore store = maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl
                ? com.maidsmart.soul.SoulBindingService.storeFor(maid, sl)
                : AiMemoryStore.of(maid.m_20148_(),
                AiMemoryExtractor.memoryRoot(level.m_7654_()));
        long now = System.currentTimeMillis();
        long gameTime = level.m_46467_();
        AiMemoryType memType = switch (type) {
            case "boundary" -> AiMemoryType.PREFERENCE;
            case "relation" -> AiMemoryType.RELATION;
            case "promise" -> AiMemoryType.PROMISE;
            case "emotion" -> AiMemoryType.EMOTION;
            case "event" -> AiMemoryType.EVENT; // v1.5.190：event 不再落 PREFERENCE
            default -> AiMemoryType.PREFERENCE; // preference
        };
        AiMemoryWriteStrategy.Plan plan = AiMemoryWriteStrategy.plan(memType, importance, tags);
        AiMemoryModels.Paragraph p = AiMemoryModels.Paragraph.create(plan.layer(), "maid",
                content, String.join(",", plan.tags()), plan.salience(), plan.permanent(), now, gameTime);
        store.addParagraph(p);
        // v1.5.190：event 类同时建事件片段（与提取器 EVENT 行同构）
        if (memType == AiMemoryType.EVENT) {
            store.upsertEpisode(AiMemoryModels.Episode.create("remember", content,
                    "", gameTime - 100, gameTime, importance / 10.0, p.hash(), now));
        }
        // relation/promise → 关系三元组（subject=主人，predicate 由内容语义简化）
        if (memType == AiMemoryType.RELATION || memType == AiMemoryType.PROMISE) {
            store.upsertRelation(AiMemoryModels.Relation.create("主人", type, content,
                    importance / 10.0, p.hash(), plan.permanent(), now));
        }
        // v1.5.190：画像聚合——只对刻画主人的类型（偏好/边界/特质/关系/承诺）聚合；
        // event/emotion 是单次事件，不该污染"主人画像"（旧版全部塞进画像）
        if (memType == AiMemoryType.PREFERENCE || memType == AiMemoryType.RELATION
                || memType == AiMemoryType.PROMISE) {
            store.upsertProfile(AiMemoryModels.Profile.create("owner", "（" + type + "）" + content, p.hash(), now));
        }
        store.prune(now, com.maidsmart.config.MaidSmartConfig.MEMORY_MAX_ENTRIES.get());
        return callback.addToolResult("已记住：「" + content + "」（类型：" + type
                + "，重要度：" + plan.salience() + "）。之后对话我会记得。", toolId);
    }

    public record Result(String content, String type, int importance, String tags) {
    }
}
