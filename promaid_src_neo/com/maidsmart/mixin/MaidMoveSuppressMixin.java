package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.SelfPreservationBehavior;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动抑制（v1.5.24）：从源头解决"站桩时灵时不灵"。
 *
 * 背景：1.20.1 Brain 同一 activity 内多个行为并发启动（无 runningPriority 互斥）。
 * 工作行为（建筑/烹饪/酿造/整理）每 tick 清 WALK_TARGET + 停导航，但 MoveToTargetSink
 * 若先执行，会读到其他行为/上 tick 遗留的移动目标重新寻路 → 女仆站着站着挪一下。
 * 与其事后清 memory，不如在 MoveToTargetSink.doTick（SRG tick）入口直接取消。
 *
 * 三种标记，三种强度：
 * - WORK_STILL_TAG（工作站桩）：彻底静止——清 WALK_TARGET + 停导航 + 取消本 tick。
 * - SNIPING（垫高状态，v1.1.0 实测三百九十三）：同工作站桩档【彻底静止】——
 *   反馈"搭好高塔仍自己乱走走下塔摔下去"；TLM 远程 strafe 行为
 *   （MaidRangedWalkToTarget / SetWalkTargetFromAttackTargetIfTargetOutOfReach）
 *   每 tick 写 WALK_TARGET 被源头取消，塔顶零移动意图；仅影响移动，
 *   弓的瞄准/射击不走此 sink 不受影响（与建造状态同款静止方案）。
 * - PRESERVE_TAG（自保逃跑）：只清 WALK_TARGET + 取消本 tick，【不】停导航——
 *   逃跑路径是直连导航（moveTo moveTo）不是 WALK_TARGET，停了就跑不动了；
 *   拦掉 MoveToTargetSink 是为了防止 TLM 跟随/其他行为塞 WALK_TARGET 把逃跑
 *   路径盖掉（旧版"逃跑时往主人身边跑"的竞态来源之一）。
 * - 战术接管（v1.5.134 MaidCombatTacticsBehavior.isActive）：同自保——只清
 *   WALK_TARGET + 取消本 tick，战术行为的直连导航独占移动（TLM 战斗走位行为
 *   SetWalkTargetFromAttackTargetIfTargetOutOfReach / MaidRangedWalkToTarget
 *   写的 WALK_TARGET 一律不执行，防绕圈/拉扯时被拽回直线追脸）。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.MoveToTargetSink.class)
public abstract class MaidMoveSuppressMixin {
    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)V", at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressMoveWhenStill(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包对野生女仆的规则一律不动）
        }
        if (MaidWorkTags.isStill(maid) || MaidWorkTags.isBuildSitting(maid)) {
            // 工作站桩 / 建造强制坐下（v1.1.0）：完全静止——玩家"解除坐下"后
            // MaidMoveControl 直施速度等通道一并被源头锁住，坐着绝不走动
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.getNavigation().recomputePath();
            ci.cancel();
        } else if (((net.neoforged.neoforge.common.extensions.IEntityExtension) maid).getPersistentData().getBoolean(SelfPreservationBehavior.PRESERVE_TAG)) {
            // 自保逃跑：防止 WALK_TARGET 覆盖逃跑路径（不碰导航本身）
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            ci.cancel();
        } else if (com.maidsmart.combat.MaidCombatTacticsBehavior.isActive(maid)) {
            // v1.5.134：单兵战术接管移动——清 WALK_TARGET + 取消本 tick，
            // 战术行为直连导航独占（TLM 战斗走位写的 WALK_TARGET 不执行）
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            ci.cancel();
        } else if (com.maidsmart.flight.MaidFreeFlightController.isControlling(maid)) {
            // 实测六百八十【创造飞行接管移动】：用户反馈"她创造飞行跟上玩家时依然会搭路，
            // 而移动是搭路的逻辑，于是影响移动速度"。根子比搭路更深一层——
            // **我们的飞行速度是在 MaidTickEvent 里写的，而它跑在大脑之前**：大脑随后写下的
            // WALK_TARGET 会被 MoveToTargetSink 变成寻路，再由 MaidMoveControl 在"我们写速度之后"
            // 执行并覆盖掉 ✗。所以这里从源头掐：清 WALK_TARGET + 停导航 + 取消本 tick。
            // （搭路那侧另有一道让位：BridgeUpBehavior 的 checkExtraStartConditions/canStillUse。）
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.getNavigationManager().resetNavigation();
            ci.cancel();
        } else if (com.maidsmart.task.MaidWorkTags.isSpawnerTorchRun(maid)) {
            // v1.3.0(beta) 实测六百六十四【刷怪笼插火把的"优先"】：走去插火把期间她独占移动
            // ——清 WALK_TARGET + 取消本 tick（TLM 原生任务/跟随/远程走位写的走位目标一律不
            // 执行）；直连寻路那一侧由 SpawnerTorchNavGuardMixin 掐。
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            ci.cancel();
        }
    }
}
