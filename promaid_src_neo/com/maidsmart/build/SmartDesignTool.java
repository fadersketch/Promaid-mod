package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * smart_design —— AI 画蓝图（v1.5.94，子 Agent 设计器）。
 *
 * 玩家正常对话说"帮我设计一栋中式庭院"，主 LLM 调用本工具 → 工具返回一个
 * 带【建筑设计师专属提示词】的子 Agent callback（TLM 原生子 Agent 分发：
 * LLMCallback 的 subagents 构造器 + 官方 "dispatched to a dedicated sub-agent"）→
 * 子 LLM 以"建筑师"角色用专门的 JSON 蓝图格式设计 → 回复 JSON → 本工具
 * 解析校验 → saveJsonBlueprint 落盘（自动注册进Promaid 手册）→ 走【正常建造流程】
 * （BlueprintBuildExecutor.execute，非强制，材料不足 partial）→ 建造结果
 * （能建/缺料/障碍）由子 LLM 在最终回复里向玩家转述。
 *
 * 与 smart_build 的区别：smart_build 是"主 LLM 自己生成 JSON"（受主对话上下文
 * 干扰、质量不稳）；smart_design 是"主 LLM 把设计任务外包给子 Agent"——子 LLM
 * 只干一件事（设计），提示词更聚焦，蓝图质量更高；且子 Agent 的回复不污染主对话。
 *
 * 尺寸上限：配置面板 build.designMaxBlocks（AI 设计蓝图上限，默认 500）。
 */
public class SmartDesignTool implements ITool<SmartDesignTool.Result> {
    public static final String TOOL_ID = "smart_design";
    private static final String DESC_PARAM_ID = "description";
    private static final String NAME_PARAM_ID = "name";

    private static final String TOOL_DESC = "Use this when the user asks you to DESIGN a custom building "
            + "(\"帮我设计一栋别墅\", \"设计个中式庭院\", \"画一张小木屋的图纸\"...) — a NEW blueprint "
            + "created from imagination, not an existing one.\n"
            + "You hand the design task to a dedicated architect sub-agent which will generate a complete "
            + "blueprint, save it into the blueprint book, and try to start building it.\n"
            + "IMPORTANT (v1.5.196): first consider whether an EXISTING blueprint already fits — "
            + "if the request matches a built-in or external blueprint (e.g. 水井/小木屋/凉亭/喷泉/瞭望塔 "
            + "or anything in the blueprint book), do NOT use this tool — use smart_build with that id instead.\n"
            + "Only use this tool for genuinely NEW custom buildings.\n"
            + "Optionally call perception_query (build_site/look_around) BEFORE this tool so the architect "
            + "can match the real terrain and avoid timeouts.\n"
            + "Description of the building is REQUIRED (style, size, materials, features). "
            + "Keep it in the player's language.\n"
            + "Do NOT use this tool when the user just wants to build an EXISTING blueprint "
            + "(use smart_build with the blueprint id instead).";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(DESC_PARAM_ID).forGetter(Result::description),
            Codec.STRING.optionalFieldOf(NAME_PARAM_ID, "").forGetter(Result::name)
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
        StringParameter description = StringParameter.create()
                .setDescription("What the player wants to build: style, size, materials, features (REQUIRED)");
        StringParameter name = StringParameter.create()
                .setDescription("Optional blueprint name; if empty the architect decides one");
        root.addProperties(DESC_PARAM_ID, description);
        root.addProperties(NAME_PARAM_ID, name);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Result result, LLMCallback callback) {
        // 同步路径兜底：没有 client 无法触发子 agent，直接返回原 callback 不分发。
        // 实际流程走 onCallAsync（覆写），子 agent 分发在那边实现。
        return callback;
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolCallId, Result result,
                                                      LLMCallback callback, LLMClient client) {
        // v1.5.196：零 LLM 快速路径（借鉴 PatchouliAI BuildCommandInterceptor）——
        // 描述命中内置/外部蓝图关键词 → 直接提示主 LLM 用 smart_build，不走子 Agent。
        // 常见建筑（水井/小木屋/凉亭/喷泉/瞭望塔/已有图纸）完全不消耗子 LLM token，
        // 也不会因子 Agent 生成复杂 JSON 而超时。
        String hit = matchExistingBlueprint(callback.getMaid(), result.description());
        if (hit != null) {
            return CompletableFuture.completedFuture(callback.addToolResult(
                    "这个建筑已有现成蓝图「" + hit + "」。不要设计新蓝图，"
                            + "请直接调用 smart_build 工具（blueprint=" + hit + "）开始建造。", toolCallId));
        }
        // 子 Agent 分发：返回一个不同的 callback（带专属 messages），TLM 主流程
        // 自动 client.chat(side) 发起子调用（官方 sub-agent 机制，见 LLMCallback）。
        LLMCallback side = new DesignCallback(callback.getChatManager(), result);
        return CompletableFuture.completedFuture(side);
    }

    /**
     * v1.5.196：描述 → 已有蓝图 id 匹配（内置 5 个关键词 + 外部蓝图文件名）。
     * 命中返回蓝图 id（maid_smart:xxx / maid_smart_ext:xxx），未命中返回 null。
     */
    private static String matchExistingBlueprint(EntityMaid maid, String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String d = description.toLowerCase();
        // 内置蓝图关键词（按精确度排序：更长/更明确的关键词在前，避免"塔"误伤"水塔"等）
        String[][] builtins = {
                {"maid_smart:well", "水井", "挖口井", "打口井"},
                {"maid_smart:hut", "小木屋", "木屋", "小房子", "小木房"},
                {"maid_smart:gazebo", "凉亭", "亭子", "凉棚"},
                {"maid_smart:fountain", "喷泉", "喷水池"},
                {"maid_smart:tower", "瞭望塔", "哨塔", "望塔", "观塔"},
        };
        for (String[] b : builtins) {
            for (int i = 1; i < b.length; i++) {
                if (d.contains(b[i])) {
                    return b[0];
                }
            }
        }
        // 外部蓝图：文件名/中文名包含匹配（玩家自己导入的图纸优先复用）
        try {
            for (String[] e : BlueprintLib.buildCatalogEntries()) {
                if (e == null || e.length < 1 || e[0] == null) {
                    continue;
                }
                String id = e[0];
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1).toLowerCase() : id.toLowerCase();
                if (path.length() >= 2 && d.contains(path)) {
                    return id;
                }
                // 中文名匹配（e[1] = 蓝图名）
                if (e.length > 1 && e[1] != null && !e[1].isBlank() && description.contains(e[1])) {
                    return id;
                }
            }
        } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("SmartDesignTool", ignored);
        }
        return null;
    }

    /**
     * 子 Agent 回调：messages = [建筑师 system 提示词, 玩家描述]。
     * onSuccess 覆盖：拿到子 LLM 的 JSON 回复 → 解析 → 落盘 → 正常建造 →
     * 用结果拼出给玩家的最终回复（气泡 + 聊天）。
     */
    private static final class DesignCallback extends LLMCallback {
        private final Result result;

        DesignCallback(MaidAIChatManager chatManager, Result result) {
            super(chatManager, buildMessages(chatManager.getMaid(), result), true);
            this.result = result;
        }

        private static List<LLMMessage> buildMessages(EntityMaid maid, Result result) {
            int maxBlocks = com.maidsmart.config.MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS.get();
            String system = "You are a Minecraft building ARCHITECT. The player's maid asked you to design a "
                    + "building blueprint from imagination. Your ONLY task is to produce a valid JSON blueprint.\n"
                    + "## Output format (STRICT — output ONLY the JSON, nothing else, no markdown fences)\n"
                    + "{\"name\": \"蓝图名\", \"blocks\": [{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:oak_planks\"}, ...]}\n"
                    + "## Rules\n"
                    + "- Coordinate system: x/z horizontal (0,0 = front-center), y vertical from 0 (ground).\n"
                    + "- Use any NORMAL building block. Preferred: oak/spruce/birch/jungle/acacia/dark_oak/"
                    + "mangrove/cherry planks & logs, their leaves (oak/spruce/birch/jungle/acacia/dark_oak/"
                    + "mangrove/cherry_leaves, azalea_leaves), stone, stone_bricks, cracked/mossy_stone_bricks, "
                    + "cobblestone, mossy_cobblestone, bricks, smooth_stone, polished_andesite/granite/diorite, "
                    + "glass, glass_pane, white_stained_glass, planks/iron doors, stairs, slabs, fences, "
                    + "torch, lantern, sea_lantern, glowstone, wool, carpet, chest, crafting_table, "
                    + "furnace, bookshelf, grass_block, dirt, gravel, sand, flower_pot, poppy, dandelion, "
                    + "azure_bluet, signs. Do NOT use water/lava/command_block/barrier/portal blocks.\n"
                    + "- Design REALISTIC buildings: walls with corners, a roof, an entrance (door), windows "
                    + "with glass, interior lighting. Support every block (nothing floating). Trees: a trunk "
                    + "of logs plus a leaf canopy; a 20-block-tall tree is fine.\n"
                    // v1.5.197：设计大小无限制——不引导"简单/缩小"，块数上限仅由配置面板
                    // build.designMaxBlocks 兜底，防单次输出截断。建造材料由玩家确认后准备，
                    // 不因材料限制设计。
                    // 实测八百三十一：**尺寸也彻底放开**——旧版这里还写着 x/z ≤ ±maxRange、y ≤ maxHeight
                    // （默认 12/8），20 格高的树直接被拒；而真正卡死它的是 parseJson 里同一套上限，
                    // 现已移除（parseJson 只留物理兜底，见 BlueprintCatalog.parseJson）。
                    + "- Size is NOT limited by materials, width or height: design as large and detailed as "
                    + "the player asks (the only hard cap is at most " + maxBlocks + " blocks total). "
                    + "y grows upward from 0 with no height ceiling; x/z have no small square limit.\n"
                    + "- 'name' should be a short Chinese name (e.g. 中式庭院).\n"
                    // v1.5.196：给建筑师提供"本地预置模板"优先选型——AI 现场生成 300-800 块 JSON
                    // 极容易超过 TLM 16 次工具轮次/响应长度上限而超时。本地模板离线即得、材料简单、
                    // 一次调用即开工，是最可靠的兜底路径；仅当玩家描述明显超出模板能力时才自由发挥。
                    + "- TRY BUILT-IN TEMPLATES FIRST (offline, instant, no timeout): ask the maid's "
                    + "smart_build_list tool to list available blueprint ids (maid_smart:hut / gazebo / "
                    + "fountain / tower / well / and any maid_smart_ext:xxx the player already has). "
                    + "If one fits the request, reply with a short message telling the player "
                    + "\"已有现成图纸「xxx」，直接用 smart_build 建造（不需要新设计）\" and DO NOT output a blueprint.\n"
                    + "- Only when the player explicitly asks for a NEW custom building (or none of the "
                    + "templates fit) do you design a new blueprint — size is up to the player's request.\n"
                    + "Building request from the player: " + result.description() + "\n"
                    + "If the player asked for a name use it, otherwise choose one. "
                    + "Respond with ONLY the JSON (or the template message above).";
            List<LLMMessage> messages = new ArrayList<>();
            messages.add(LLMMessage.systemChat(maid, system));
            messages.add(LLMMessage.userChat(maid, "请先判断：这个建筑能用现成蓝图模板吗？能的话用模板；不能才输出新的 JSON 蓝图。"));
            return messages;
        }

        @Override
        public void onSuccess(ResponseChat responseChat) {
            String json = responseChat.getChatText();
            if (json == null || json.isBlank()) {
                this.finish("设计失败了——建筑师没有返回蓝图。请再试一次，或换个描述。");
                return;
            }
            // 去掉可能的 markdown 代码块围栏
            json = json.trim();
            if (json.startsWith("```")) {
                int firstNl = json.indexOf('\n');
                if (firstNl >= 0) {
                    json = json.substring(firstNl + 1);
                }
                int lastFence = json.lastIndexOf("```");
                if (lastFence >= 0) {
                    json = json.substring(0, lastFence);
                }
                json = json.trim();
            }
            List<String> steps = BlueprintLib.parseJson(json);
            if (steps == null || steps.isEmpty()) {
                // v1.5.196：子 Agent 可能按提示词回复"已有现成图纸"（模板建议，非 JSON）——
                // 此时转述给玩家并提示用 smart_build，而不是误报"格式不对"
                String lower = json.toLowerCase();
                if (lower.contains("smart_build") || lower.contains("maid_smart:")
                        || lower.contains("现成图纸") || lower.contains("现成的") || lower.contains("模板")) {
                    this.finish(json);
                    return;
                }
                // 实测八百三十一：把 parseJson 记下的**真实原因**一并报出来（旧的
                // "格式不对" 让玩家和模型都不知道改哪儿）
                String why = BlueprintLib.lastJsonError();
                this.finish("设计失败——建筑师返回的蓝图没法保存成蓝图"
                        + (why == null || why.isEmpty() ? "（不是合法 JSON）" : "：" + why)
                        + "。请换个描述再试一次。");
                return;
            }
            String name = BlueprintLib.parseJsonName(json);
            if (name == null || name.isEmpty()) {
                name = "AI 设计";
            }
            // v1.5.197：宽松校验——不再用 BlueprintLib.validate（它按 maxBlocks=200 拒绝大蓝图）。
            // 自绘蓝图大小无限制：只校验 designMaxBlocks（默认 500，配置可调 100~20000）
            // 与坐标范围（与建筑师提示词一致），白名单方块由提示词约束、落盘后走 external 路径
            // 建造（scanExternalBlueprints 对大蓝图放行，大小限制仅用于手册目录显示）。
            String looseError = looseValidate(steps);
            if (looseError != null) {
                this.finish("设计失败：" + looseError);
                return;
            }
            // 落盘 → 增量扫描自动注册进手册（id = maid_smart_ext:文件名）
            BlueprintLib.saveJsonBlueprint(name, json);
            BlueprintLib.scanExternalBlueprints();
            String extId = "maid_smart_ext:" + name.replaceAll("[\\\\/:*?\"<>|]", "_");
            // v1.5.197：自绘蓝图【只入册、不自动动工】——建造是强制拆挡路方块的大动作，
            // 玩家还没选位置、也没确认，直接开工可能把周围建筑/地形全拆了。
            // 由玩家确认后再走 smart_build（可以指定位置/旋转）。
            this.finish("设计好了！蓝图「" + name + "」已存入手册（id：" + extId + "）。"
                    + "你想让我在哪里建造？确认后对女仆说\"用这张图纸建造\"或\"开始建「" + name + "」\"，"
                    + "她会在你选好的位置动工——在那之前她不会自己开工，也不会拆任何东西。");
        }

        /** 给玩家最终回复：气泡 + 主人聊天栏（服务端线程） */
        private void finish(String bubble) {
            try {
                this.runOnServerThread(() -> {
                    try {
                        this.getMaid().getChatBubbleManager().addTextChatBubble(bubble);
                        if (this.getMaid().getOwner() instanceof net.minecraft.server.level.ServerPlayer owner) {
                            owner.sendSystemMessage(Component.literal(
                                    "\u00a77[" + (this.getMaid().getDisplayName() != null
                                            ? this.getMaid().getDisplayName().getString() : "女仆") + "] \u00a7f" + bubble));
                        }
                    } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("SmartDesignTool", ignored);
                    }
                });
            } catch (Exception ignored) { com.maidsmart.diag.Diag.swallow("SmartDesignTool", ignored);
            }
        }

        /**
         * v1.5.197 / 实测八百三十一：自绘蓝图宽松校验——**只查块数上限**（designMaxBlocks，配置可调）
         * 与"有没有方块"。不做白名单强制（提示词已约束），也不查 ±maxRange / y≤maxHeight
         * ——那三条是 smart_build 现场 JSON 的上限；自绘蓝图尺寸无限制（20 格高的树、±12 外的
         * 庭院都应放行）。物理兜底（坐标绝对值 ≤ 512、黑名单方块）在 {@code parseJson} 里，
         * 所以这里不再重复坐标检查，只把失败原因报清楚。
         */
        private static String looseValidate(List<String> steps) {
            int maxBlocks = com.maidsmart.config.MaidSmartConfig.BUILD_DESIGN_MAX_BLOCKS.get();
            if (steps == null || steps.isEmpty()) {
                return "蓝图无效：没有可放置的方块";
            }
            if (steps.size() > maxBlocks) {
                return "蓝图无效：块数 " + steps.size() + " 超过上限 " + maxBlocks
                        + "（可在配置面板 build.designMaxBlocks 调大）";
            }
            return null;
        }
    }

    public record Result(String description, String name) {
    }
}
