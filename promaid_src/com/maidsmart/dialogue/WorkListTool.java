package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.maidsmart.build.BlueprintLib;
import com.maidsmart.build.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * work_list（v1.5.196，移植 PatchouliAI TodoList 到 TLM 工具链）。
 *
 * 跨轮任务计划 + 建造材料缺口查询：
 * - set [json]：写入/更新当前任务计划（[{content,status,priority}]，只允许一个 in_progress）
 * - get：读取当前任务计划
 * - clear：清空任务计划
 * - query_todo：查询当前任务计划 + 当前建造进度（build_need 的 LLM 提示版）
 * - build_need：查询当前绑定的建造计划的材料缺口（剩余需要 vs 背包持有），
 *   补料后让玩家直接补，女仆自动续建 —— 不必重新下达/重新生成蓝图
 *
 * 用途：杜绝"先生成蓝图 → 再生成材料清单 → 再开始建造"这类消耗多个工具轮次的
 * 链式调用（超过 TLM 16 次工具轮次上限 → 超时）。build_need 一次调用即给出
 * 缺口，玩家补料后女仆自动续建。
 */
public class WorkListTool implements ITool<WorkListTool.Result> {
    public static final String TOOL_ID = "work_list";

    private static final String TOOL_DESC = "Use this to manage the CURRENT multi-turn task plan and query "
            + "build progress / missing materials.\n"
            + "- set <json array>: replace the task plan, e.g. {\"action\":\"set\",\"json\":\"[{\\\"content\\\":\\\"建地基\\\","
            + "\\\"status\\\":\\\"in_progress\\\"},{\\\"content\\\":\\\"砌墙\\\",\\\"status\\\":\\\"pending\\\"}]\"}\n"
            + "- get: read the current task plan.\n"
            + "- clear: clear the task plan.\n"
            + "- query_todo: current task plan + build progress (how many blocks done).\n"
            + "- build_need: materials still missing for the current bound build plan (remaining need vs "
            + "backpack). After the player restocks, the maid auto-continues — do NOT re-issue or "
            + "re-generate the blueprint.\n"
            + "IMPORTANT: for a big build, prefer setting a plan with set, then call build_need to tell the "
            + "player exactly what to add. Never try to generate blueprint + material list + start in one "
            + "reply (that wastes tool turns and times out).";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("action", "get").forGetter(Result::action),
            Codec.STRING.optionalFieldOf("json", "").forGetter(Result::json)
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
        root.addProperties("action", StringParameter.create()
                .addEnumValues("set", "get", "clear", "query_todo", "build_need")
                .setDescription("set=写入任务计划(json必填), get=读取, clear=清空, query_todo=任务计划+建造进度, build_need=建造材料缺口"));
        root.addProperties("json", StringParameter.create()
                .setDescription("任务计划 JSON 数组（action=set 时必填）：[{\"content\":\"任务\",\"status\":\"pending\",\"priority\":\"medium\"}]"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_WORK_LIST.get()) {
            return callback.addToolResult("work_list 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        String action = result.action() == null ? "get" : result.action().trim();
        try {
            switch (action) {
                case "set": {
                    String json = result.json() == null ? "" : result.json().trim();
                    List<MaidWorkList.Item> items = MaidWorkList.parseJson(json);
                    if (items == null || items.isEmpty()) {
                        return callback.addToolResult("任务计划 JSON 格式错误。格式：[{\"content\":\"任务\",\"status\":\"pending\",\"priority\":\"medium\"}]（content 必填，status 取 pending/in_progress/completed/cancelled）", toolId);
                    }
                    MaidWorkList.set(maid, items);
                    StringBuilder sb = new StringBuilder("任务计划已更新：\n");
                    sb.append(MaidWorkList.toPromptString(maid));
                    return callback.addToolResult(sb.toString(), toolId);
                }
                case "clear": {
                    MaidWorkList.clear(maid);
                    return callback.addToolResult("已清空任务计划。", toolId);
                }
                case "query_todo": {
                    StringBuilder sb = new StringBuilder();
                    String plan = MaidWorkList.toPromptString(maid);
                    if (plan.isEmpty()) {
                        sb.append("当前没有任务计划。\n");
                    } else {
                        sb.append(plan).append("\n");
                    }
                    sb.append(buildProgressText(maid));
                    return callback.addToolResult(sb.toString(), toolId);
                }
                case "build_need": {
                    return callback.addToolResult(buildNeedText(maid), toolId);
                }
                default: { // get
                    String plan = MaidWorkList.toPromptString(maid);
                    return callback.addToolResult(plan.isEmpty() ? "当前没有任务计划。" : plan, toolId);
                }
            }
        } catch (Exception e) {
            return callback.addToolResult("work_list 操作失败: " + e.getMessage(), toolId);
        }
    }

    /** 建造进度（有多少块已建/总块数 + 当前背包还缺什么） */
    private static String buildProgressText(EntityMaid maid) {
        List<String> plan = BuildPlan.getBoundPlan(maid);
        if (plan.isEmpty()) {
            return "当前没有绑定的建造计划。要开始建造请调用 smart_build 指定蓝图。";
        }
        String name = BuildPlan.planName(plan);
        int total = plan.size() - 1;
        int done = 0;
        Map<String, Integer> needed = new LinkedHashMap<>();
        BlockPos origin = BuildPlan.getOrigin(plan);
        if (origin == null) {
            return "建造计划数据异常（缺少原点信息），无法统计进度。";
        }
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(parts[3]));
                BlockPos target = origin.m_7918_(x, y, z);
                net.minecraft.world.level.block.state.BlockState state = maid.m_9236_().m_8055_(target);
                if (block != null && (state.m_60734_() == block || BlueprintLib.isBuiltEquivalent(parts[3], state.m_60734_()))) {
                    done++;
                } else if (block != null) {
                    needed.merge(parts[3], 1, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Player owner = null;
        if (maid.m_269323_() instanceof Player p) {
            owner = p;
        }
        boolean creative = BlueprintLib.isCreative(owner);
        StringBuilder sb = new StringBuilder();
        sb.append("建造进度：「").append(name).append("」已建 ").append(done).append('/').append(total)
                .append(" 块（").append(total == 0 ? 0 : done * 100 / total).append("%）。");
        Map<String, Integer> shortOf = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            // v1.5.218：背包口径 = 主人背包 + 女仆背包（combinedHave），旧版只算
            // 女仆背包导致缺口虚高
            int have = creative ? Integer.MAX_VALUE : BlueprintLib.combinedHave(owner, maid, e.getKey());
            if (have < e.getValue()) {
                shortOf.put(e.getKey(), e.getValue() - have);
            }
        }
        if (shortOf.isEmpty()) {
            sb.append("当前材料足够完成剩余部分，她正在继续建造。");
        } else {
            sb.append("还缺材料：");
            boolean first = true;
            for (Map.Entry<String, Integer> e : shortOf.entrySet()) {
                if (!first) {
                    sb.append("、");
                }
                first = false;
                sb.append(BlueprintLib.cnName(e.getKey())).append(" x").append(e.getValue());
            }
            sb.append("。请告知主人补充这些材料到女仆背包，补上后她会自动继续（已建部分自动跳过）。");
        }
        return sb.toString();
    }

    /** 材料缺口（build_need）
     *  v1.5.218：按用户要求 = 蓝图总需求 − 已累计搭建（世界状态扫描）− 主人背包
     *  − 女仆背包（旧版全量需求 − 仅女仆背包，缺口严重虚高） */
    private static String buildNeedText(EntityMaid maid) {
        List<String> plan = BuildPlan.getBoundPlan(maid);
        if (plan.isEmpty()) {
            return "当前没有绑定的建造计划。请先调用 smart_build 指定蓝图开始建造。";
        }
        String name = BuildPlan.planName(plan);
        BlockPos origin = BuildPlan.getOrigin(plan);
        if (origin == null) {
            return "建造计划数据异常（缺少原点信息），无法统计材料缺口。";
        }
        Map<String, Integer> needed = new LinkedHashMap<>();
        int total = plan.size() - 1;
        for (int i = 1; i < plan.size(); i++) {
            String[] parts = BlueprintLib.parseStep(plan.get(i));
            if (parts == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getValue(net.minecraft.resources.ResourceLocation.parse(parts[3]));
                BlockPos target = origin.m_7918_(x, y, z);
                net.minecraft.world.level.block.state.BlockState state = maid.m_9236_().m_8055_(target);
                if (block != null && (state.m_60734_() == block || BlueprintLib.isBuiltEquivalent(parts[3], state.m_60734_()))) {
                    continue; // 已累计搭建 → 不计入需求
                }
                if (block != null) {
                    needed.merge(parts[3], 1, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Player owner = null;
        if (maid.m_269323_() instanceof Player p) {
            owner = p;
        }
        boolean creative = BlueprintLib.isCreative(owner);
        StringBuilder sb = new StringBuilder();
        sb.append("蓝图「").append(name).append("」共 ").append(total).append(" 块，已建 ")
                .append(total - needed.values().stream().mapToInt(Integer::intValue).sum())
                .append(" 块，剩余材料缺口：\n");
        boolean anyMissing = false;
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            int have = creative ? Integer.MAX_VALUE : BlueprintLib.combinedHave(owner, maid, e.getKey());
            int shortOf = Math.max(0, e.getValue() - have);
            if (shortOf > 0) {
                anyMissing = true;
                sb.append("- ").append(BlueprintLib.cnName(e.getKey())).append(": 需 ").append(e.getValue())
                        .append("，缺 ").append(shortOf).append("\n");
            }
        }
        if (!anyMissing) {
            sb.append("  无缺口，材料已足够。");
        } else {
            sb.append("请告知主人把这些材料放到主人或女仆背包，补上后她会自动继续建造（已建部分自动跳过）。");
        }
        return sb.toString();
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

    public record Result(String action, String json) {
    }
}
