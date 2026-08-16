package com.maidsmart.memory;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * query_memory_index（移植自 Sphantosis recaller/workflows.query_memory_index）：
 * 多级记忆索引（日记式摘要）查询工具——LLM 对话中按需调用。
 *
 * 记忆索引按游戏日组织为四个压缩级别："日"（当天详细日记）、"3日"（最近3天）、
 * "周"（当周）、"月"（当月仅最重要事件记录）。跨度越大信息压缩程度越高，
 * 所有级别的索引均被永久归档，可随时查询。
 *
 * 使用方式（前缀和式范围确定）：
 * 1. 先不带 start_day/end_day 调用 → 返回该级别所有可用索引跨度列表
 * 2. 从中选跨度带范围二次调用 → 返回日记式摘要 + 关键事件原文
 *    （粗粒度定位 → 细粒度精确，多次调用逐步缩小范围）
 */
public class QueryMemoryIndexTool implements ITool<QueryMemoryIndexTool.Result> {
    public static final String TOOL_ID = "query_memory_index";

    private static final String TOOL_DESC = "Use this to recall memories over a time span via "
            + "compressed diary-style memory indexes (day / 3-day / week / month levels). "
            + "First call WITHOUT start_day/end_day to list available time spans for the level, "
            + "then pick one span and call again with start_day/end_day to get the diary summary "
            + "and key original events. Day numbers are in-game days counting from world creation.";

    /** -1 = 未指定（该端不设限） */
    private static final int UNSPECIFIED = -1;

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("level").forGetter(Result::level),
            Codec.INT.optionalFieldOf("start_day", UNSPECIFIED).forGetter(Result::startDay),
            Codec.INT.optionalFieldOf("end_day", UNSPECIFIED).forGetter(Result::endDay)
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
        root.addProperties("level", StringParameter.create()
                .setDescription("索引级别，只能是：日 / 3日 / 周 / 月"));
        root.addProperties("start_day", IntegerParameter.create()
                .setDescription("跨度开始（游戏日序号，可省略；省略时列出该级别可用跨度列表）"));
        root.addProperties("end_day", IntegerParameter.create()
                .setDescription("跨度结束（游戏日序号，可省略）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.MEMORY_INDEX_ENABLE.get()) {
            return callback.addToolResult("记忆索引功能未开启（config: memory.indexEnable）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!AiMemoryManager.isEnabled(maid)) {
            return callback.addToolResult("记忆功能已关闭（女仆配置界面可开启）。", toolId);
        }
        if (!(maid.m_9236_() instanceof net.minecraft.server.level.ServerLevel level)) {
            return callback.addToolResult("记忆检索需要在服务端进行", toolId);
        }
        String lv = result.level() == null ? "" : result.level().trim();
        if (!lv.equals(AiMemoryIndexStore.LEVEL_DAY) && !lv.equals(AiMemoryIndexStore.LEVEL_3DAY)
                && !lv.equals(AiMemoryIndexStore.LEVEL_WEEK) && !lv.equals(AiMemoryIndexStore.LEVEL_MONTH)) {
            return callback.addToolResult("无效的索引级别：" + lv + "。可选级别：日/3日/周/月。", toolId);
        }
        AiMemoryStore store = com.maidsmart.soul.SoulBindingService.storeFor(maid, level);
        if (result.startDay() == UNSPECIFIED && result.endDay() == UNSPECIFIED) {
            // 第一步：列出该级别所有可用跨度
            List<AiMemoryIndexStore.IndexRecord> records = store.index().byLevel(lv);
            if (records.isEmpty()) {
                return callback.addToolResult("暂无" + lv + "级记忆索引。", toolId);
            }
            StringBuilder sb = new StringBuilder("【" + lv + "级索引可用跨度列表（共" + records.size() + "条）】\n");
            int currentDay = (int) (level.m_46467_() / 24000);
            sb.append("（当前是第").append(currentDay).append("天）\n");
            for (AiMemoryIndexStore.IndexRecord r : records) {
                sb.append("- 第").append(r.startDay()).append("~").append(r.endDay())
                        .append("天（关键事件 ").append(r.eventIds().size()).append(" 个）\n");
            }
            sb.append("请从中选择跨度后，以 start_day/end_day 二次调用本工具。");
            return callback.addToolResult(sb.toString(), toolId);
        }
        // 第二步：返回跨度内日记 + 关键事件原文
        List<AiMemoryIndexStore.IndexRecord> records = store.index().findRange(lv,
                result.startDay() == UNSPECIFIED ? null : result.startDay(),
                result.endDay() == UNSPECIFIED ? null : result.endDay());
        if (records.isEmpty()) {
            return callback.addToolResult("在指定范围内未找到" + lv + "级记忆索引。"
                    + "可先不带 start_day/end_day 调用本工具获取可用跨度列表。", toolId);
        }
        List<String> parts = new ArrayList<>();
        for (AiMemoryIndexStore.IndexRecord r : records) {
            StringBuilder part = new StringBuilder("【" + r.level() + "索引 第" + r.startDay()
                    + "~" + r.endDay() + "天】\n" + r.content());
            List<String> events = new ArrayList<>();
            for (String hash : r.eventIds()) {
                if (events.size() >= 10) {
                    break;
                }
                AiMemoryModels.Paragraph p = store.paragraphByHash(hash);
                if (p != null && !p.deleted()) {
                    events.add("（重要度" + p.salience() + "）" + AiMemoryModels.clip(p.content(), 80));
                }
            }
            if (!events.isEmpty()) {
                part.append("\n关键事件：").append(String.join("；", events));
            }
            parts.add(part.toString());
        }
        return callback.addToolResult(String.join("\n\n", parts), toolId);
    }

    /** 工具参数 */
    public record Result(String level, int startDay, int endDay) {
    }
}
