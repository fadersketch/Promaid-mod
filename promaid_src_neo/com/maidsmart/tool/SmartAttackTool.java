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
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;

import java.util.List;
import java.util.Optional;

/**
 * v1.5.136：smart_attack —— AI 对话指挥战斗（"去打那只僵尸 / 攻击最近的怪 / 帮我打它"）。
 *
 * 三种目标模式（target 参数）：
 * - nearest：最近的可攻击敌对生物（12 格 Monster 扫描，TLM canAttack 完整过滤防误伤）；
 * - owner_target：主人当前攻击目标（护主集火——主人打谁女仆打谁）；
 * - attacker：最近攻击过女仆的生物（5 秒窗口，LivingIncomingDamageEvent 记录——
 *   覆盖其他 mod 的非 Monster 类敌对生物）。
 *
 * 执行：切到 TLM 原生 attack 任务（如需要）→ 写 ATTACK_TARGET + setTarget 双重锁定
 * → 播报确认。锁定后 TLM 的 StartAttacking 不会覆盖已有目标（只选空目标），
 * 单兵战术行为（core 230）自动接管走位，自保（250）兜底保命——指挥链路闭环。
 */
public class SmartAttackTool implements ITool<SmartAttackTool.Result> {
    public static final String TOOL_ID = "smart_attack";
    private static final String TARGET_PARAM_ID = "target";
    private static final String TOOL_DESC = "Use this when the user orders the maid to attack a hostile creature, "
            + "fight enemies, or help fight what the user is fighting.\n"
            + "Set target=nearest to attack the closest hostile creature, "
            + "target=owner_target to attack whatever the user is currently fighting, "
            + "target=attacker to attack the creature that recently attacked the maid.\n"
            + "The maid will switch to the attack task if needed and engage the target.";
    private static final Codec<Result> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(TARGET_PARAM_ID).forGetter(Result::target)
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
        StringParameter target = StringParameter.create()
                .addEnumValues("nearest", "owner_target", "attacker");
        root.addProperties(TARGET_PARAM_ID, target);
        return root;
    }

    @Override
    public Codec<Result> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolId, Result result, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid.level().isClientSide()) {
            return callback.addToolResult("Cannot attack on client side", toolId);
        }
        // v1.1.0 实测一百三十六：排班中的女仆任务由日程表管理，禁止外部指派攻击
        if (com.maidsmart.schedule.ScheduleData.isOn(maid)) {
            return callback.addToolResult("该女仆正在排班中，任务由日程表管理——请先关闭她的排班再下攻击指令", toolId);
        }
        ServerLevel level = (ServerLevel) maid.level();
        LivingEntity target = null;
        String mode = result.target();
        if ("owner_target".equals(mode)) {
            LivingEntity owner = maid.getOwner();
            if (owner != null) {
                // getLastHurtMob = getLastHurtMob（主人最近攻击的目标；Player 非 Mob 无 getTarget，
                // TLM DefaultMonsterType 同款用法实证）
                target = owner.getLastHurtMob();
            }
        } else if ("attacker".equals(mode)) {
            target = com.maidsmart.combat.SelfPreservationBehavior.recentAttacker(maid);
        } else {
            target = findNearestHostile(maid, level);
        }
        // 防御：TLM 原生 canAttack 完整过滤（排除玩家/装甲架/有主宠物/怪物类型/忽略列表）
        if (target == null || !target.isAlive() || !maid.canAttack(target)) {
            return callback.addToolResult("No valid attack target found nearby", toolId);
        }
        // 切到攻击任务（如不在攻击类任务）
        Optional<IMaidTask> attackTask = TaskManager.findTask(
                ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "attack"));
        if (attackTask.isPresent() && maid.getTask() != attackTask.get()) {
            maid.setTask(attackTask.get());
        }
        // 双重锁定：ATTACK_TARGET 记忆（Brain 战斗行为读它）+ Mob setTarget（实体层）
        // TLM StartAttacking 只选空目标，不会覆盖；战术行为/自保自动接管后续
        if (com.maidsmart.combat.FriendlyFireGuard.isFriendly(maid, target)) {
            return callback.addToolResult("Refused: target is owner/ally", toolId);
        }
        maid.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        maid.setTarget(target);
        maid.getChatBubbleManager().addTextChatBubble("明白！我去解决它！");
        return callback.addToolResult("Attacking " + target.getName().getString(), toolId);
    }

    /** 最近的可攻击敌对生物（12 格；canAttack = TLM canAttack 过滤） */
    private static LivingEntity findNearestHostile(EntityMaid maid, ServerLevel level) {
        List<Monster> monsters = level.getEntitiesOfClass(Monster.class,
                maid.getBoundingBox().inflate(12.0), m -> m.isAlive() && maid.canAttack(m));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster monster : monsters) {
            double d = maid.distanceTo(monster);
            if (d < bestDist) {
                bestDist = d;
                best = monster;
            }
        }
        return best;
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
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) maid.level();
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

    public record Result(String target) {
    }
}
