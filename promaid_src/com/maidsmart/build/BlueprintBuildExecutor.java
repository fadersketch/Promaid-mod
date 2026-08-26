package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 建造执行器（v1.5.16）：蓝图卷轴 / Promaid 手册 / 外部图纸共用的建造入口。
 *
 * v1.5.180：创建区块【不再依赖女仆在场】——execute 直接以玩家脚下为原点创建
 * 区块计划；唯一硬性要求 = 不与已有区块重叠（findOverlap 拒绝 + 警告）；
 * 区域内障碍物只【警告】不阻止（用户要求）。开始工作仅指挥绑定该区块的女仆
 * （女仆管理页绑定/解绑）。
 *
 * 流程：解析蓝图 → 重叠检查（拒绝）→ 障碍物警告（不阻止）→ 已建感知 →
 * 材料预检（需求 − 已建 − 绑定女仆/主人背包）→ 创建/续建区块计划。
 */
public final class BlueprintBuildExecutor {

    public static final int TYPE_OK = 0;          // 已创建/续建区块
    public static final int TYPE_SHORTFALL = 1;   // 材料不足（未创建）
    public static final int TYPE_OBSTACLE = 2;    // 区域有障碍物（拒绝，v1.5.180 起仅警告）
    public static final int TYPE_UNKNOWN = 3;     // 蓝图不存在/解析失败
    /** v1.5.180：TYPE_NOT_BUILDING(4) 已废弃——创建区块不再需要女仆在场 */
    public static final int TYPE_NOT_BUILDING = 4;
    public static final int TYPE_BUSY = 5;        // 兼容保留（不再使用）
    public static final int TYPE_OVERLAP = 6;     // v1.5.180：与已有区块重叠（拒绝）

    public record Outcome(int type, String message) {
    }

    private BlueprintBuildExecutor() {
    }

    /** 女仆是否已处于建筑任务（绑定判定/行为评估用） */
    public static boolean isBuildingTask(EntityMaid maid) {
        return maid.getTask() != null
                && ResourceLocation.parse("maid_smart:build").equals(maid.getTask().getUid());
    }

    /**
     * v1.5.180：创建/续建区块计划（不依赖女仆）。
     *
     * @param origin              区块原点（玩家脚下；centerSteps 以它为中心）
     * @param partialOnShortfall  true：材料不足时直接创建（缺料步骤延后自动续建）；
     *                            false：材料不足不创建，返回缺口清单
     */
    public static Outcome execute(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                  String blueprintId, boolean partialOnShortfall,
                                  net.minecraft.world.entity.player.Player player) {
        return execute(level, origin, blueprintId, partialOnShortfall, player, 0);
    }

    /**
     * v1.1.0 实测九十七：带朝向的创建/续建（quarters = 0~3 × 90° 顺时针）——
     * 步骤经 rotateSteps 整体旋转（坐标矩阵 + W/D 互换 + 方块状态转向）后再居中，
     * 与投影点云同一变换，橙影与实建逐块重合。
     */
    public static Outcome execute(net.minecraft.server.level.ServerLevel level, BlockPos origin,
                                  String blueprintId, boolean partialOnShortfall,
                                  net.minecraft.world.entity.player.Player player, int quarters) {
        List<String> steps = BlueprintLib.getBlueprintRotated(blueprintId, Math.floorMod(quarters, 4),
                level.m_246945_(net.minecraft.core.registries.Registries.f_256747_));
        if (steps == null || steps.isEmpty()) {
            return new Outcome(TYPE_UNKNOWN, "蓝图打不开：" + blueprintId);
        }
        String name = BlueprintLib.getBlueprintName(blueprintId);
        List<String> centered = BlueprintLib.centerSteps(steps);
        // v1.5.316：红石机器改革——机器走专属搭建顺序（红石拓扑分层：结构→机构→
        // 活动件→传感→动力源→TNT，动力源最后落位），取代常规"结构→功能→装饰红石"
        // 排序；配合活放置（flag 3）机器建好即自然运行，不再需要静默+完工唤醒的禁锢。
        // 开关 BUILD_MACHINE_SMART 可一键回退旧行为。
        String machineFam = BlueprintLib.machineFamily(blueprintId);
        if (machineFam != null && com.maidsmart.config.MaidSmartConfig.BUILD_MACHINE_SMART.get()) {
            centered = BlueprintLib.sortMachinePlan(centered, machineFam);
        }
        int[] sz = BlueprintLib.blueprintSize(centered);
        // v1.5.180：续建识别——同蓝图同原点 = 续建（保留进度继续建，不重叠检查）
        // v1.1.0 实测九十七复查：朝向必须一致才算续建——同点位换朝向重建时旧计划
        // 会被取消后按新建走（已建方块由 filterBuilt 已建感知自然跳过，进度不白费）；
        // 否则续建分支沿用旧步骤，玩家按 P 选的旋转完全不起作用
        boolean resuming = false;
        String planId = null;
        BuildPlan.PlanState staleSameSpot = null;
        for (BuildPlan.PlanState ex : BuildPlan.getPlans(level)) {
            if (ex.blueprintId.equals(blueprintId) && ex.origin.equals(origin)) {
                if (ex.quarters == Math.floorMod(quarters, 4)) {
                    planId = ex.planId;
                    resuming = true;
                } else {
                    staleSameSpot = ex;
                }
                break;
            }
        }
        if (staleSameSpot != null) {
            BuildPlan.cancel(level, staleSameSpot.planId, player);
        }
        if (!resuming) {
            // v1.5.180：重叠检查——创建区块唯一硬性要求：不能与其他区块重叠
            BuildPlan.PlanState overlap = BuildPlan.findOverlap(level, origin, sz[0], sz[1], sz[2], null);
            if (overlap != null) {
                return new Outcome(TYPE_OVERLAP,
                        "这个位置与已有区块「" + overlap.name + "」（"
                                + overlap.origin.m_123341_() + "," + overlap.origin.m_123342_()
                                + "," + overlap.origin.m_123343_() + "）重叠了。请换个位置再创建——"
                                + "区块之间不能互相重叠。");
            }
        }
        // v2.0：统一已建感知——区块内与蓝图匹配的方块 = 已建（不拆、只补缺）
        List<String> pending = BlueprintLib.filterBuilt(level, origin, centered);
        if (pending.isEmpty()) {
            return new Outcome(TYPE_OK, "这个蓝图已经全部建好啦，不用重复建哦。");
        }
        // v1.5.180：障碍物警告（不阻止——唯一硬性限制是重叠；提醒玩家注意周边）
        String obstacleWarn = "";
        String obs = BlueprintLib.findObstacles(level, origin, centered);
        if (obs != null) {
            obstacleWarn = "（" + obs + "——创建后女仆建造时会拆掉这些阻挡）";
        }
        // v1.5.179：材料缺口 = 需求 − 已建 − 背包（绑定女仆 + 主人）
        Map<String, Integer> shortfall = combinedShortfall(player, level, pending);
        List<String> buildable = pending;
        if (shortfall != null && partialOnShortfall) {
            // v1.5.144：缺料步骤保留在计划里，由建造行为延后（deferred）+ 补料轮播
            boolean anyMaterial = false;
            for (String step : pending) {
                String[] pp = BlueprintLib.parseStep(step);
                if (pp != null && BlueprintLib.combinedHaveAll(level, player, pp[3]) > 0) {
                    anyMaterial = true;
                    break;
                }
            }
            if (!anyMaterial) {
                return new Outcome(TYPE_SHORTFALL, "背包里没有任何建造材料，无法创建。缺少："
                        + formatShortfall(shortfall) + "。" + fluidNote(pending));
            }
        } else if (shortfall != null) {
            return new Outcome(TYPE_SHORTFALL, "材料不足，缺少：" + formatShortfall(shortfall) + "。"
                    + fluidNote(pending));
        }
        // v1.5.180：创建/续建区块计划（续建 = 解除暂停继续）
        if (resuming) {
            BuildPlan.PlanState ex = BuildPlan.getPlanById(planId);
            if (ex != null && ex.paused) {
                BuildPlan.togglePause(level, ex);
            }
        } else {
            planId = BuildPlan.setPlan(level, origin, buildable, name, blueprintId,
                    Math.floorMod(quarters, 4));
        }
        String needText = needBubbleText(buildable);
        if (resuming) {
            return new Outcome(TYPE_OK, "继续建造「" + name + "」！（已建部分自动跳过，还剩 "
                    + buildable.size() + " 块）" + needText);
        }
        return new Outcome(TYPE_OK, "好！区块「" + name + "」已创建（共 " + buildable.size() + " 块）。"
                + obstacleWarn + " 在手册女仆管理里绑定女仆后，她们就会开始建造。"
                + (shortfall != null ? "材料不足：" + formatShortfall(shortfall)
                + "——把材料放进你的背包或女仆背包即可。" : needText));
    }

    /** v1.5.43：剩余材料需求气泡文案（前 5 种 + 合计） */
    private static String needBubbleText(List<String> buildable) {
        Map<String, Integer> needs = BlueprintLib.countNeeds(buildable);
        if (needs.isEmpty()) {
            return "";
        }
        int total = 0;
        for (int v : needs.values()) {
            total += v;
        }
        StringBuilder sb = new StringBuilder(" 还需 ");
        int shown = 0;
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            if (shown >= 5) {
                break;
            }
            sb.append(BlueprintLib.cnName(e.getKey())).append("×").append(e.getValue()).append(" ");
            shown++;
        }
        sb.append(needs.size() > 5 ? "…" : "").append("（共").append(total).append("块）。");
        sb.append("材料放进主人背包，女仆会自己拿～");
        // v1.5.318：液体工具/材料需求（水桶/岩浆桶）单独提示——水/岩浆不在普通
        // 材料统计（FORBIDDEN 排除），但玩家需要知道要备桶
        String fluid = BlueprintLib.fluidNeedText(buildable);
        if (!fluid.isEmpty()) {
            sb.append(" ").append(fluid).append("。");
        }
        return sb.toString();
    }

    private static String formatShortfall(Map<String, Integer> shortfall) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : shortfall.entrySet()) {
            parts.add(BlueprintLib.cnName(entry.getKey()) + " x" + entry.getValue());
        }
        return String.join("、", parts);
    }

    /** v1.5.318：缺料消息附加液体需求提示（水桶/岩浆桶）；无液体需求返回空串 */
    private static String fluidNote(List<String> steps) {
        String fluid = BlueprintLib.fluidNeedText(steps);
        return fluid.isEmpty() ? "" : " " + fluid + "。";
    }

    /** v1.5.24：组合材料预检（v1.5.179：主人背包 + 该维度所有绑定女仆背包总量），
     *  返回缺失清单；充足返回 null */
    private static Map<String, Integer> combinedShortfall(
            net.minecraft.world.entity.player.Player owner, net.minecraft.server.level.ServerLevel level,
            List<String> steps) {
        Map<String, Integer> needed = BlueprintLib.countNeeds(steps);
        Map<String, Integer> shortfall = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            int have = BlueprintLib.combinedHaveAll(level, owner, entry.getKey());
            if (have < entry.getValue()) {
                shortfall.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return shortfall.isEmpty() ? null : shortfall;
    }
}
