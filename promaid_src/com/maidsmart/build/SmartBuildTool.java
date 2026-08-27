package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * smart_build：让女仆建造建筑。
 *
 * blueprint 参数两种格式：
 * 1. 内置蓝图 id：maid_smart:hut（小木屋）/ maid_smart:gazebo（凉亭）/ maid_smart:fountain（喷泉）——离线可用
 * 2. JSON 蓝图（LLM 现场生成，联网知识）：
 *    {"name":"小屋","blocks":[{"x":0,"y":0,"z":0,"block":"minecraft:oak_planks"}, ...]}
 *    注意：仅允许建筑白名单方块；块数 1~200；平面坐标 ±12；高度 0~8
 *
 * mode 参数（材料不足时的处理策略）：
 * - full（默认）：材料不足时【不开始建造】，返回缺失清单，
 *   由 LLM 询问玩家选择"先建一部分"（再用 mode=partial 调用）还是"放弃建造"
 * - partial：只把"当前材料够用"的前缀步骤加入计划（顺序截断，
 *   不会出现"墙中间缺一块"的悬空建筑），缺的部分等玩家补料后由建造行为继续提示
 *
 * 流程：解析校验 → 材料预检 → 按 mode 决策 → 记录建造计划 → 自动切建筑任务。
 */
public class SmartBuildTool implements ITool<SmartBuildTool.Result> {
    public static final String TOOL_ID = "smart_build";
    private static final String BLUEPRINT_PARAM_ID = "blueprint";
    private static final String MODE_PARAM_ID = "mode";
    private static final String ROTATION_PARAM_ID = "rotation";
    private static final String ACTION_PARAM_ID = "action";
    private static final String TOOL_DESC = "Use this when the user asks you to build a structure "
            + "(\"建房子\", \"搭个小木屋\", \"造个塔\"...).\n"
            + "If the user asks what you CAN build, call smart_build_list FIRST and show the catalog.\n"
            + "blueprint can be a built-in id (these are the available buildings):\n"
            + "- maid_smart:hut — 小木屋 (5x5 log cabin, oak_planks + oak_log)\n"
            + "- maid_smart:gazebo — 凉亭 (4x4 stone pavilion, stone_bricks)\n"
            + "- maid_smart:fountain — 喷泉 (3x3 stone fountain, stone_bricks + sea_lantern)\n"
            + "- maid_smart:tower — 瞭望塔 (3x3 stone watchtower, stone_bricks + lantern)\n"
            + "- maid_smart:well — 水井 (3x3 cobble well, cobblestone + lantern)\n"
            + "or a custom JSON blueprint like {\"blocks\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:oak_planks\"}]}.\n"
            + "Each JSON block may carry optional \"state\" (SNBT like {facing:\"north\"}) and \"nbt\" (block entity data).\n"
            + "Redstone components are allowed now (redstone_wire/redstone_torch/repeater/comparator/piston/lever/observer/rail/hopper...).\n"
            + "or an external blueprint id (maid_smart_ext:xxx) listed by smart_build_list — these come\n"
            + "from structure files (.nbt structure-block export / .snbt text / .litematic Litematica\n"
            + "export / .schem WorldEdit export) placed in config/maid_smart/blueprints/ or the world's\n"
            + "schematics/ folder; anything the player already has there is available, including\n"
            + "elaborate builds downloaded online. For external structure blueprints you may pass\n"
            + "rotation 0/90/180/270 (clockwise) — the same file can be built rotated; omit for default 0.\n"
            + "Rules for JSON blueprints: building blocks only (whitelist), 1-200 blocks, x/z +-12, y 0-8.\n"
            + "Material style suggestions (pick ONE consistent style per build):\n"
            + "- rustic cabin: oak_log + oak_planks\n"
            + "- castle: stone_bricks + cobblestone + dark_oak_planks accents\n"
            + "- modern: smooth_stone + glass + white_wool\n"
            + "- garden: stone_bricks path + flowers (poppy/dandelion) + oak_planks\n"
            + "- coastal: spruce_planks + dark_oak_log + sea_lantern\n"
            + "Set mode to 'full' (default) or 'partial'. When materials are missing:\n"
            + "- full: DO NOT start building. Report the missing materials and ASK THE USER whether to\n"
            + "  build a partial structure (then call again with mode=partial) or cancel.\n"
            + "- partial: build only the prefix of steps that current materials can cover.\n"
            + "If building runs out of materials MIDWAY, she pauses and reports what is needed; "
            + "that is normal, not a failure — after restocking, the user can ask again to continue, "
            + "finished blocks are skipped automatically. Tell the player this instead of promising "
            + "one single trip for big buildings.";
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(BLUEPRINT_PARAM_ID).forGetter(Result::blueprint),
            Codec.STRING.optionalFieldOf(MODE_PARAM_ID, "full").forGetter(Result::mode),
            Codec.STRING.optionalFieldOf(ROTATION_PARAM_ID, "0").forGetter(Result::rotation),
            Codec.STRING.optionalFieldOf(ACTION_PARAM_ID, "build").forGetter(Result::action)
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
        StringParameter blueprint = StringParameter.create()
                .setDescription("Built-in id (maid_smart:hut/gazebo/fountain/tower/well), external id (maid_smart_ext:xxx), or JSON blueprint");
        StringParameter mode = StringParameter.create().addEnumValues("full", "partial");
        StringParameter rotation = StringParameter.create().addEnumValues("0", "90", "180", "270")
                .setDescription("Optional clockwise rotation for external structure blueprints (default 0)");
        StringParameter action = StringParameter.create().addEnumValues("build", "status")
                .setDescription("build = start building (default); status = report current build progress / missing materials");
        root.addProperties(BLUEPRINT_PARAM_ID, blueprint);
        root.addProperties(MODE_PARAM_ID, mode);
        root.addProperties(ROTATION_PARAM_ID, rotation);
        root.addProperties(ACTION_PARAM_ID, action);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        String blueprint = result.blueprint().trim();
        boolean partial = "partial".equals(result.mode());
        if ("status".equals(result.action())) {
            // v1.5.16：查询当前建造进度/缺料（不建造）
            return this.queryStatus(maid, toolId, callback);
        }
        int quarters = 0;
        try {
            quarters = (int) (Math.floorMod((long) Double.parseDouble(result.rotation().trim()), 360L) / 90L);
        } catch (Exception ignored) {
        }
        List<String> steps = BlueprintLib.getBlueprintRotated(blueprint, quarters,
                maid.m_9236_().m_246945_(net.minecraft.core.registries.Registries.f_256747_));
        boolean fromJson = false;
        String name;
        if (steps == null) {
            // 不是内置/外部蓝图 → 尝试 JSON 蓝图
            steps = BlueprintLib.parseJson(blueprint);
            if (steps == null) {
                return callback.addToolResult("未知蓝图：" + blueprint + "。可用蓝图：\n" + BlueprintLib.buildCatalog(), toolId);
            }
            fromJson = true;
            name = BlueprintLib.parseJsonName(blueprint);
            if (name == null) {
                name = "建筑";
            }
        } else {
            name = BlueprintLib.getBlueprintName(blueprint);
        }
        if (fromJson) {
            // LLM JSON 蓝图受白名单/范围限制；内置/外部（含结构文件）蓝图不受此限
            String error = BlueprintLib.validate(steps);
            if (error != null) {
                return callback.addToolResult(error, toolId);
            }
            // v1.5.28：LLM 现场生成的蓝图【落盘】到 config/maid_smart/blueprints/
            // ——之前生成完就丢，高科技别墅/海景房等从未进过手册；
            // 现在生成即保存，下次扫描自动注册，主人可以直接从手册复用
            BlueprintLib.saveJsonBlueprint(name, blueprint);
        }
        String desc = BlueprintLib.describe(blueprint, steps);
        // v1.5.162：有进行中/暂停中的建造计划时拒绝重复下达——建造用暂停/继续控制，
        // 取消（删除区块标记）后才能重新开始
        // v1.5.180：女仆绑定区块 = 唯一约束（多区块共存；LLM 下达自动绑定该女仆）
        String boundId = BuildPlan.getBoundPlanId(maid);
        if (boundId != null) {
            String where = "";
            try {
                BuildPlan.PlanState exPs = BuildPlan.getPlanById(boundId);
                if (exPs != null) {
                    where = "（已绑定「" + exPs.name + "」在 " + exPs.origin.m_123341_() + ","
                            + exPs.origin.m_123342_() + "," + exPs.origin.m_123343_()
                            + "——走到该区块内即可看到手册控制按钮）";
                }
            } catch (Exception ignored) {
            }
            return callback.addToolResult("女仆已经绑定了建造区块。" + where
                    + "请先在手册女仆管理里解绑，或取消该区块后再开始新的。", toolId);
        }
        // v1.5.25：统一先居中；v1.5.162：不再沿用旧计划 origin / 自动识别半成品——
        // 取消建造后旧建筑按障碍物处理（默认强制模式下被拆成掉落物重建）
        List<String> centered = BlueprintLib.centerSteps(steps);
        BlockPos origin = this.calcOrigin(maid);
        // v1.5.25：过滤已建格 → 材料/障碍/截断全部基于未建部分，续建不重复计算。
        List<String> pending = BlueprintLib.filterBuilt(maid.m_9236_(), origin, centered);
        if (pending.isEmpty()) {
            return callback.addToolResult("蓝图「" + name + "」已经全部建好啦，不需要重复建造。", toolId);
        }
        // v1.5.162：默认强制执行——不再做障碍物预检（原"强制建造"开关已删除，
        // 渲染已给出建造范围 = 玩家自己的选择）：挡路方块由建造行为运行时拆成掉落物
        // v1.5.24：材料以主人背包为准——确认后自动从主人背包交付给女仆
        net.minecraft.world.entity.player.Player owner = null;
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
            owner = p;
        }
        Map<String, Integer> shortfall = owner != null
                ? BlueprintLib.calcPlayerShortfall(owner, pending)
                : BlueprintLib.calcShortfall(maid, pending);
        if (shortfall == null) {
            // 材料充足：自动交付 + 全量建造（未建部分）
            if (owner != null) {
                BlueprintLib.deliverToMaid(owner, maid, BlueprintLib.countNeeds(pending));
            }
            return this.startBuild(maid, pending, name,
                    owner != null ? "（所需材料已自动从你的背包交给女仆）" : null,
                    desc, toolId, callback, blueprint);
        }
        if (!partial) {
            // full 模式缺料：不建造，反馈缺失并让 LLM 询问玩家
            return callback.addToolResult("蓝图「" + name + "」（" + desc + "）材料不足，无法开始建造。缺少："
                    + formatShortfall(shortfall)
                    + "。请不要直接开始建造，先询问主人："
                    + "1) 先建造有材料的部分（回答后请以 mode=partial 重新调用本工具）；"
                    + "2) 放弃建造（不调用工具，直接回复主人）。"
                    + "如果主人坚持全量建造，请告知主人需要补充哪些材料（放入自己的背包），并不要重复调用本工具。", toolId);
        }
        // v1.5.144：partial 缺料【不再顺序截断】——全量下发，缺料步骤由建造行为
        // 延后（deferred）自动续建。旧版 break 截断把缺料步骤及其后所有步骤从计划
        // 永久丢弃 → 补料后重新下达又因原点漂移/已建判定错位重复建造，"生存续建"
        // 永远失败（瞭望塔日志实证：缺灯笼 → 5 秒"建造完成"）。只保留
        // "完全没材料"拒绝（避免女仆空建干等）。
        boolean anyMaterial = false;
        for (String step : pending) {
            String[] pp = BlueprintLib.parseStep(step);
            if (pp == null) {
                continue;
            }
            String block = pp[3];
            int have = owner != null
                    ? BlueprintLib.countPlayerMaterial(owner, block)
                    : BlueprintLib.countMaterial(maid, block);
            if (have > 0) {
                anyMaterial = true;
                break;
            }
        }
        if (!anyMaterial) {
            return callback.addToolResult("背包里没有任何建造材料，无法开始。请告知主人需要：" + formatShortfall(shortfall), toolId);
        }
        // 自动交付可建部分所需材料给女仆（有材料的全交付，缺料的等补料自动拿）
        if (owner != null) {
            BlueprintLib.deliverToMaid(owner, maid, BlueprintLib.countNeeds(pending));
        }
        String notice = "材料不足，我先建材料够的部分，缺料的方块会自动延后，补料后自动续建（不用重复下达）。缺少："
                + formatShortfall(shortfall)
                + "。所需材料已自动从主人背包交给女仆。";
            return this.startBuild(maid, pending, name, notice, desc, toolId, callback, blueprint);
    }

    /** action=status：查询当前建造计划进度与缺料（LLM 回答"建到哪了/还缺什么"） */
    private LLMCallback queryStatus(EntityMaid maid, String toolId, LLMCallback callback) {
        List<String> plan = BuildPlan.getBoundPlan(maid);
        if (plan.isEmpty()) {
            return callback.addToolResult("当前没有进行中的建造计划。要开始建造请调用本工具（action=build）并指定 blueprint。可用蓝图：\n"
                    + BlueprintLib.buildCatalog(), toolId);
        }
        String name = BuildPlan.planName(plan);
        int total = plan.size() - 1;
        int done = 0;
        java.util.Map<String, Integer> needed = new HashMap<>();
        BlockPos origin = BuildPlan.getOrigin(plan);
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
        // 缺料：剩余需要 vs 背包持有（v1.5.24：主人创造模式视为材料充足）
        net.minecraft.world.entity.player.Player qOwner = null;
        if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
            qOwner = p;
        }
        boolean creative = BlueprintLib.isCreative(qOwner);
        java.util.Map<String, Integer> shortOf = new HashMap<>();
        for (java.util.Map.Entry<String, Integer> e : needed.entrySet()) {
            int have = creative ? Integer.MAX_VALUE : BlueprintLib.countMaterial(maid, e.getKey());
            if (have < e.getValue()) {
                shortOf.put(e.getKey(), e.getValue() - have);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("建造进度：「").append(name).append("」已建 ").append(done).append('/').append(total)
                .append(" 块（").append(total == 0 ? 0 : done * 100 / total).append("%）。");
        if (shortOf.isEmpty()) {
            sb.append("当前背包材料足够完成剩余部分，她正在继续建造。");
        } else {
            sb.append("还缺材料：");
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> e : shortOf.entrySet()) {
                if (!first) {
                    sb.append("、");
                }
                first = false;
                sb.append(BlueprintLib.cnName(e.getKey())).append(" x").append(e.getValue());
            }
            sb.append("。请告知主人补充这些材料到女仆背包，补上后她会自动继续（已建部分自动跳过）。");
        }
        return callback.addToolResult(sb.toString(), toolId);
    }

    /** 创建区块并绑定女仆（v1.5.180：LLM 下达 = 明确意图 → 自动绑定该女仆开工）；
     *  notice 非空时在工具结果前附带 */
    private LLMCallback startBuild(EntityMaid maid, List<String> steps, String name, String notice,
                                   String desc, String toolId, LLMCallback callback, String blueprintId) {
        net.minecraft.server.level.ServerLevel level = maid.m_9236_()
                instanceof net.minecraft.server.level.ServerLevel sl ? sl : null;
        if (level == null) {
            return callback.addToolResult("内部错误：无法确定建造维度。", toolId);
        }
        // v1.1.0 实测一百三十六：排班中的女仆任务由日程表管理，禁止外部指派建造
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return callback.addToolResult("该女仆正在排班中，任务由日程表管理——请先关闭她的排班再下达建造", toolId);
        }
        // v1.5.180：已绑定区块 → 拒绝（女仆一次只绑一个区块；先解绑/取消）
        if (BuildPlan.getBoundPlanId(maid) != null) {
            return callback.addToolResult("女仆已绑定区块。请先在手册女仆管理里解绑，或取消该区块后再下达。", toolId);
        }
        // v1.5.13：以女仆自身为中心（步骤居中 + 原点 = 女仆脚下），隔空建造
        BlockPos origin = this.calcOrigin(maid);
        // v1.5.180：重叠检查——创建区块唯一硬性要求：不能与其他区块重叠
        int[] sz = BlueprintLib.blueprintSize(steps);
        BuildPlan.PlanState overlap = BuildPlan.findOverlap(level, origin, sz[0], sz[1], sz[2], null);
        if (overlap != null) {
            return callback.addToolResult("这个位置与已有区块「" + overlap.name + "」（"
                    + overlap.origin.m_123341_() + "," + overlap.origin.m_123342_()
                    + "," + overlap.origin.m_123343_() + "）重叠了。请换个位置再试。", toolId);
        }
        String planId = BuildPlan.setPlan(level, origin, steps, name, blueprintId);
        if (planId == null) {
            return callback.addToolResult("内部错误：建造计划未初始化，请稍后重试", toolId);
        }
        // v1.5.180：自动绑定该女仆 + 切建筑任务 + 强制 WORK
        BuildPlan.bindMaid(maid, planId);
        if (!BlueprintBuildExecutor.isBuildingTask(maid)) {
            com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager
                    .findTask(net.minecraft.resources.ResourceLocation.parse("maid_smart:build"))
                    .ifPresent(maid::setTask);
        }
        try {
            maid.m_6274_().m_21889_(net.minecraft.world.entity.schedule.Activity.f_37980_);
        } catch (Exception ignored) {
        }
        String result = "区块「" + name + "」已创建并绑定给女仆（" + desc + "，共 " + steps.size() + " 块）。她开始建造了。";
        if (notice != null) {
            result = notice + " " + result;
        }
        return callback.addToolResult(result, toolId);
    }

    private static String formatShortfall(Map<String, Integer> shortfall) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : shortfall.entrySet()) {
            parts.add(BlueprintLib.cnName(entry.getKey()) + " x" + entry.getValue());
        }
        return String.join(", ", parts);
    }

    /** 计算建造原点（v1.5.84）：女仆选择搭建的地点是【主人（玩家）所在方块】——
     *  后续障碍判定/提示围绕玩家所在那一格描述；主人不在场时退回女仆脚下。
     *  v1.5.88：可在配置面板切换（build.originPlayer：true=玩家脚下，false=女仆脚下） */
    public static BlockPos calcOrigin(EntityMaid maid) {
        if (com.maidsmart.config.MaidSmartConfig.BUILD_ORIGIN_PLAYER.get()) {
            net.minecraft.world.entity.LivingEntity owner = maid.m_269323_();
            if (owner instanceof net.minecraft.server.level.ServerPlayer) {
                return owner.m_20183_();
            }
        }
        return maid.m_20183_();
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

    public record Result(String blueprint, String mode, String rotation, String action) {
    }
}
