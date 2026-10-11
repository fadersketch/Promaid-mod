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
 * 移动抑制：从源头解决"站桩时灵时不灵"。
 * 背景：Brain 同一 activity 内多个行为并发启动（无 runningPriority 互斥）。工作行为每 tick 清
 * WALK_TARGET + 停导航，但 {@code MoveToTargetSink} 若先执行，会读到其他行为/上 tick 遗留的移动目标重新寻路
 * → 女仆站着站着挪一下。与其事后清 memory，不如在 {@code MoveToTargetSink} 入口直接取消。
 * 四种标记、三种强度：
 * - WORK_STILL_TAG（工作站桩）/ SNIPING（垫高）/ 战术接管（{@code MaidCombatTacticsBehavior.isActive}）/
 *   仿创造飞行（{@code MaidFreeFlightController.isControlling}）：【彻底静止】——清 WALK_TARGET + 停导航 +
 *   取消本 tick（TLM 远程 strafe、走位行为每 tick 写的 WALK_TARGET 被源头取消；飞行速度写在 brain 之前，
 *   sink 的寻路会在 brain 阶段把她拽住、速度发飘。落地待命不在 isControlling 里，TLM 跟随照常工作）。
 * - PRESERVE_TAG（自保逃跑）：只清 WALK_TARGET + 取消本 tick，【不】停导航——逃跑路径是直连导航
 *   （{@code moveTo}）不是 WALK_TARGET，停了就跑不动了；拦 sink 是防 TLM 跟随/其他行为塞 WALK_TARGET 把逃跑
 *   路径盖掉。
 * 【映射】本树 SRG {@code m_6725_}；neo 树官方名 {@code tick}。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.MoveToTargetSink.class)
public abstract class MaidMoveSuppressMixin {
    @Inject(method = "m_6725_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressMoveWhenStill(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包对野生女仆的规则一律不动）
        }
        if (com.maidsmart.config.MaidSmartConfig.MISC_STATIONARY_LOCK.get()
                && (MaidWorkTags.isStill(maid) || MaidWorkTags.isBuildSitting(maid))) {
            // 工作站桩 / 建造强制坐下（v1.1.0）：完全静止——玩家"解除坐下"后
            // MaidMoveControl 直施速度等通道一并被源头锁住，坐着绝不走动
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
        } else if (com.maidsmart.task.MaidWorkTags.isSpawnerTorchRun(maid)) {
            // v1.3.0(beta) 实测六百六十四【刷怪笼插火把的"优先"】：走去插火把期间她独占移动
            // ——清 WALK_TARGET + 取消本 tick（TLM 原生任务/跟随/远程走位写的走位目标一律不
            // 执行）；直连寻路那一侧由 SpawnerTorchNavGuardMixin 掐。
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            ci.cancel();
        } else if (com.maidsmart.flight.MaidFreeFlightController.isControlling(maid)) {
            // 【仿创造飞行接管移动】：清 WALK_TARGET + 停导航 + 取消本 tick。飞行速度写在
            // MaidTickEvent（brain 之前），而 brain 随后写下的 WALK_TARGET 会被 MoveToTargetSink 变成
            // 寻路（{@code start()} 走 {@code moveTo(Path,double)}、路径由 {@code createPath} 造，
            // 都不在 {@code FreeFlightWalkGuardMixin} 的射程里）；{@code MaidMoveControl} 又在"我们写
            // 速度之后"执行 ⇒ 跟随时她被自己的寻路拽着、速度发飘——控制器清记忆跑在 brain 之前，拦不住
            // 同 tick 的重写，必须在 brain 阶段源头取消。落地待命（ST_STANDBY）不在 isControlling 里。
            maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
            maid.m_21573_().m_26573_();
            ci.cancel();
        }
    }
}
