package com.maidsmart.dialogue;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * perception_query（v1.5.196，移植 PatchouliAI 查询工具集）。
 *
 * 单工具聚合 6 种世界查询，让 LLM "先查后做"：
 * - look_around [r]        ASCII 空间感知网格（避坑/选址/规划路径）
 * - terrain [r]            地形平坦度报告（评估地基需求）
 * - build_site [w] [d]     建造场地分析（障碍物/可建性/清理建议）
 * - inspect <x> <y> <z>    单方块详情（ID/硬度/掉落/状态）
 * - scanblock <id> [r]     按方块 ID 扫描（坐标+距离）
 * - scanentity [type] [r]  按类型扫描实体（hostile/passive/player/all + 血量/距离）
 *
 * 用途：玩家说"帮我在这里建个小屋"时，LLM 先调本工具拿到场地报告与地形，
 * 再调用 smart_build/smart_design —— 生成蓝图的坐标/材料更贴合实际，减少
 * 超时重试（蓝图太复杂/材料对不上 → 反复工具轮次 → 超 16 次工具上限）。
 */
public class PerceptionQueryTool implements ITool<PerceptionQueryTool.Result> {
    public static final String TOOL_ID = "perception_query";

    private static final String TOOL_DESC = "Use this BEFORE building or designing to inspect the "
            + "environment (like a scout). ONE call with the 'query' parameter:\n"
            + "- look_around [radius=8]: 2.5D ASCII terrain grid around you — route, walls, water, lava, drops.\n"
            + "- terrain [radius=8]: flatness report (avg/max/min height, buildable %).\n"
            + "- build_site [width=7] [depth=7]: build-site analysis — obstacles (TREE/WALL/LAVA/WATER), "
            + "ground height, cleanup needed, suitability.\n"
            + "- inspect <x> <y> <z>: block details (id, hardness, tool requirement, state).\n"
            + "- scanblock <block_id> [radius=64]: find nearest blocks by id.\n"
            + "- scanentity [hostile|passive|player|all] [radius=32]: list nearby entities with hp and distance.\n"
            + "Examples: \"build_site\", \"look_around 10\", \"scanentity hostile 24\", \"inspect 100 64 200\".\n"
            + "Call this BEFORE smart_build/smart_design so the blueprint matches the real terrain "
            + "(fewer retries, no wasted tool turns).";

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
                .setDescription("查询命令：look_around [r] / terrain [r] / build_site [w] [d] / inspect <x> <y> <z> / scanblock <id> [r] / scanentity [type] [r]"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_PERCEPTION.get()) {
            return callback.addToolResult("perception_query 工具已被禁用（配置面板 AI 工具页可开启）。", toolId);
        }
        if (result.query() == null || result.query().isBlank()) {
            return callback.addToolResult("查询命令不能为空。可用：look_around [r] / terrain [r] / build_site [w] [d] / "
                    + "inspect <x> <y> <z> / scanblock <id> [r] / scanentity [type] [r]", toolId);
        }
        String q = result.query().trim();
        String[] parts = q.split("\\s+");
        String cmd = parts[0].toLowerCase();
        EntityMaid maid = callback.getMaid();
        try {
            switch (cmd) {
                case "look_around":
                case "lookaround": {
                    int r = parts.length > 1 ? parseInt(parts[1], 8) : 8;
                    return callback.addToolResult(WorldProbe.lookAround(maid, r), toolId);
                }
                case "terrain":
                case "analyzeterrain": {
                    int r = parts.length > 1 ? parseInt(parts[1], 8) : 8;
                    return callback.addToolResult(WorldProbe.analyzeTerrain(maid, r), toolId);
                }
                case "build_site":
                case "analyzebuildsite": {
                    int w = parts.length > 1 ? parseInt(parts[1], 7) : 7;
                    int d = parts.length > 2 ? parseInt(parts[2], 7) : 7;
                    return callback.addToolResult(WorldProbe.formatSiteReport(
                            WorldProbe.analyzeBuildSite(maid, w, d)), toolId);
                }
                case "inspect": {
                    if (parts.length < 4) {
                        return callback.addToolResult("用法: inspect <x> <y> <z>", toolId);
                    }
                    return callback.addToolResult(WorldProbe.inspectBlock(maid,
                            parseInt(parts[1], 0), parseInt(parts[2], 0), parseInt(parts[3], 0)), toolId);
                }
                case "scanblock": {
                    if (parts.length < 2) {
                        return callback.addToolResult("用法: scanblock <block_id> [radius]", toolId);
                    }
                    int r = parts.length > 2 ? parseInt(parts[2], 64) : 64;
                    return callback.addToolResult(WorldProbe.scanBlock(maid, parts[1], r), toolId);
                }
                case "scanentity": {
                    String type = parts.length > 1 ? parts[1] : "all";
                    int r = parts.length > 2 ? parseInt(parts[2], 32) : 32;
                    return callback.addToolResult(WorldProbe.scanEntities(maid, type, r), toolId);
                }
                default:
                    return callback.addToolResult("未知查询命令: " + cmd + "。可用：look_around / terrain / build_site / "
                            + "inspect <x> <y> <z> / scanblock <id> [r] / scanentity [type] [r]", toolId);
            }
        } catch (Exception e) {
            return callback.addToolResult("查询失败: " + e.getMessage(), toolId);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public record Result(String query) {
    }
}
