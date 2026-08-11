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
 * 与其事后清 memory，不如在 MoveToTargetSink.doTick（SRG m_6725_）入口直接取消。
 *
 * 两种标记，两种强度：
 * - WORK_STILL_TAG（工作站桩）：彻底静止——清 WALK_TARGET + 停导航 + 取消本 tick。
 * - PRESERVE_TAG（自保逃跑）：只清 WALK_TARGET + 取消本 tick，【不】停导航——
 *   逃跑路径是直连导航（m_26519_ moveTo）不是 WALK_TARGET，停了就跑不动了；
 *   拦掉 MoveToTargetSink 是为了防止 TLM 跟随/其他行为塞 WALK_TARGET 把逃跑
 *   路径盖掉（旧版"逃跑时往主人身边跑"的竞态来源之一）。
 * - 战术接管（v1.5.134 MaidCombatTacticsBehavior.isActive）：同自保——只清
 *   WALK_TARGET + 取消本 tick，战术行为的直连导航独占移动（TLM 战斗走位行为
 *   SetWalkTargetFromAttackTargetIfTargetOutOfReach / MaidRangedWalkToTarget
 *   写的 WALK_TARGET 一律不执行，防绕圈/拉扯时被拽回直线追脸）。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.MoveToTargetSink.class)
public abstract class MaidMoveSuppressMixin {
    @Inject(method = "m_6725_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressMoveWhenStill(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (MaidWorkTags.isStill(maid)) {
            // 工作站桩：完全静止
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            maid.m_21573_().m_26569_();
            ci.cancel();
        } else if (maid.getPersistentData().m_128471_(SelfPreservationBehavior.PRESERVE_TAG)) {
            // 自保逃跑：防止 WALK_TARGET 覆盖逃跑路径（不碰导航本身）
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            ci.cancel();
        } else if (com.maidsmart.combat.MaidCombatTacticsBehavior.isActive(maid)) {
            // v1.5.134：单兵战术接管移动——清 WALK_TARGET + 取消本 tick，
            // 战术行为直连导航独占（TLM 战斗走位写的 WALK_TARGET 不执行）
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            ci.cancel();
        }
    }
}
