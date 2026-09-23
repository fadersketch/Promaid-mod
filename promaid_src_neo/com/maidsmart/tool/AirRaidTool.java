package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * v1.2.2 实测五百七十八：smart_air_raid —— 「起飞打它 / 别飞了」。
 *
 * ── 为什么单独立一个工具（而不是并进 smart_switch_task）──
 * 空袭是本模组最重的一套功能（三件套判定 + 地面支点 + 爬升 + 盘旋 + 收翅猛击 + 位移法术当
 * 引擎），模型只知道"把任务切成 flight_combat"是不够的：它还需要知道**她到底能不能飞**
 * （缺件会静默退回地面战斗）、**这次飞要打谁**、以及**怎么收工**。把这些包成一个动作，
 * 模型说人话就能用："起飞打那只僵尸" / "别飞了，回来打"。
 *
 * ── 起飞前的"能不能飞"口径（复用她自己那套，不另起一套判定）──
 * {@code MaidFlightKit.missingParts(maid)} 直接给出缺什么（鞘翅 / 武器 / 「可以飞行的道具」
 * = 烟花·孔雀羽扇·位移类法术任一）；缺件时**照切任务但如实回报**——她会在缺件时退回地面
 * 战斗（这是既有设计），而玩家看到回报才知道该给她配什么。
 *
 * ── 停止（action=stop）──
 * 清掉空袭本轮的运行状态（{@code MaidFlightCombatBehavior.forget}，它同时清空袭专用索敌
 * 的锁定）+ 清 ATTACK_TARGET/setTarget + 切回 TLM「攻击」任务（落地继续近战）。
 * 刻意**不收翅**（不清滑翔位）：让她自己滑翔落地，避免半空突然自由落体。
 */
public class AirRaidTool implements ITool<AirRaidTool.Result> {
    public static final String TOOL_ID = "smart_air_raid";

    private static final String ACTION_PARAM = "action";
    private static final String MODE_PARAM = "mode";
    private static final String TARGET_PARAM = "target";

    private static final String TOOL_DESC =
            "Use this when the user wants the maid to fly / start an air raid on an enemy, "
            + "or to stop an air raid and come back down.\n"
            + "action=start (default) switches her to the air-raid task (mode=melee for elytra+melee weapons, "
            + "mode=ranged for elytra+bow/gun) and locks a target; action=stop cancels the air raid, "
            + "clears the target and puts her back on ground melee.\n"
            + "target works like smart_attack: nearest / owner_target / attacker.\n"
            + "Returns whether she is actually flight-ready (elytra + weapon + firework/peacock fan/flight spell) "
            + "and what is missing if not.";

    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf(ACTION_PARAM, "start").forGetter(Result::action),
            Codec.STRING.optionalFieldOf(MODE_PARAM, "melee").forGetter(Result::mode),
            Codec.STRING.optionalFieldOf(TARGET_PARAM, "nearest").forGetter(Result::target)
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
        root.addProperties(ACTION_PARAM, StringParameter.create()
                .addEnumValues("start", "stop").setDefaultValue("start")
                .setDescription("start=起飞空袭（默认）/ stop=停止空袭并落地"));
        root.addProperties(MODE_PARAM, StringParameter.create()
                .addEnumValues("melee", "ranged").setDefaultValue("melee")
                .setDescription("空袭类型：melee=近战空袭（鞘翅+近战武器俯冲）/ "
                        + "ranged=远程空袭（鞘翅+弓或枪械盘旋射击）"));
        root.addProperties(TARGET_PARAM, StringParameter.create()
                .addEnumValues("nearest", "owner_target", "attacker").setDefaultValue("nearest")
                .setDescription("打谁：nearest=最近的敌对生物 / owner_target=主人正在打的 / "
                        + "attacker=刚打过她的（仅 action=start 时用）"));
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        if (!com.maidsmart.config.MaidSmartConfig.TOOL_AIR_RAID.get()) {
            return callback.addToolResult("空袭指挥工具已被禁用（设置里可开启）。", toolId);
        }
        EntityMaid maid = callback.getMaid();
        if (!(maid.level() instanceof ServerLevel level)) {
            return callback.addToolResult("空袭指挥需要在服务端进行", toolId);
        }
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return callback.addToolResult("该女仆正在排班中，任务由日程表管理——请先关掉她的排班", toolId);
        }
        String action = result.action() == null ? "start" : result.action().trim().toLowerCase(java.util.Locale.ROOT);
        if ("stop".equals(action)) {
            return stop(maid, toolId, callback);
        }
        return start(maid, level, result, toolId, callback);
    }

    // ==================== 起飞 ====================

    private LLMCallback start(EntityMaid maid, ServerLevel level, Result result,
                              String toolId, LLMCallback callback) {
        String mode = result.mode() == null ? "melee" : result.mode().trim().toLowerCase(java.util.Locale.ROOT);
        boolean ranged = "ranged".equals(mode);
        ResourceLocation uid = ranged
                ? com.maidsmart.combat.MaidFlightKit.UID_RANGED
                : com.maidsmart.combat.MaidFlightKit.UID;
        IMaidTask task = TaskManager.findTask(uid).orElse(null);
        if (task == null) {
            return callback.addToolResult("空袭任务不可用（" + uid + "）——任务表里没有它，"
                    + "可能是本模组未正确加载", toolId);
        }
        StringBuilder sb = new StringBuilder();
        try {
            if (maid.getTask() != task) {
                maid.setTask(task);
            }
            sb.append("已切到").append(ranged ? "远程空袭" : "近战空袭")
                    .append("（").append(uid).append("）");
        } catch (Throwable t) {
            return callback.addToolResult("切换空袭任务失败：" + t, toolId);
        }
        // 能不能飞 = 复用她自己那套缺件口径（不另起一套判定，避免"两套口径说不一致"）
        try {
            String missing = com.maidsmart.combat.MaidFlightKit.missingParts(maid);
            if (missing == null || missing.isEmpty()) {
                sb.append("；三件套齐了，她会自己起跳并保持滞空");
            } else {
                sb.append("；⚠ 她缺「").append(missing).append("」——缺件期间空袭会退回地面战斗"
                        + "（鞘翅 / 武器 / 「可以飞行的道具」= 烟花·孔雀羽扇·位移类法术任一），"
                        + "请先补给她或让玩家知道");
            }
            if (ranged && !com.maidsmart.combat.MaidFlightKit.rangedAmmoOk(maid)) {
                sb.append("；另外没有远程弹药（箭/枪弹/副手烟花）");
            }
        } catch (Throwable ignored) {
        }
        LivingEntity target = ToolKit.pick(maid, level, result.target());
        if (ToolKit.lock(maid, target)) {
            sb.append("；目标 ").append(ToolKit.name(target));
            ToolKit.bubble(maid, "起飞！");
        } else {
            sb.append("；这一拍没扫到合法目标，她会自己索敌");
            ToolKit.bubble(maid, "我去空中待命");
        }
        return callback.addToolResult(sb.toString(), toolId);
    }

    // ==================== 收工 ====================

    private LLMCallback stop(EntityMaid maid, String toolId, LLMCallback callback) {
        StringBuilder sb = new StringBuilder();
        try {
            // 硬清本轮空袭状态（含空袭专用索敌的锁定）——forget 的既有用途就是"换任务"
            com.maidsmart.combat.MaidFlightCombatBehavior.forget(maid.getUUID());
            sb.append("已停止空袭");
        } catch (Throwable t) {
            sb.append("停止空袭时出错：").append(t);
        }
        try {
            maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            maid.setTarget(null);
            sb.append("，已清掉目标");
        } catch (Throwable ignored) {
        }
        IMaidTask attack = TaskManager.findTask(
                ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack")).orElse(null);
        if (attack != null) {
            if (maid.getTask() != attack) {
                maid.setTask(attack);
            }
            sb.append("；她对地任务切回「攻击」（落地继续近战）。注意：刻意不收翅，"
                    + "让她自己滑翔落地，别在半空自由落体");
        } else {
            sb.append("；但没找到「攻击」任务，她保持当前任务");
        }
        ToolKit.bubble(maid, "好，我落下来");
        return callback.addToolResult(sb.toString(), toolId);
    }

    @Override
    public java.util.concurrent.CompletableFuture<LLMCallback> onCallAsync(
            String toolCallId, Result result, LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client) {
        EntityMaid maid = callback.getMaid();
        if (maid.level().isClientSide()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    callback.addToolResult("Cannot run on client side", toolCallId));
        }
        ServerLevel level = (ServerLevel) maid.level();
        java.util.concurrent.CompletableFuture<LLMCallback> future = new java.util.concurrent.CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete(onCall(toolCallId, result, callback));
            } catch (Throwable t) {
                future.complete(callback.addToolResult("Tool execution failed: " + t, toolCallId));
            }
        });
        return future;
    }

    public record Result(String action, String mode, String target) {
    }
}
