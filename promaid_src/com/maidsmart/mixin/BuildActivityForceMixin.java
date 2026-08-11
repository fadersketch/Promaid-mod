package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUpdateActivityFromSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.173：建造任务强制 WORK activity（修复"下达后不建造"根因）。
 *
 * 根因（日志实证 + TLM/原版字节码）：
 * - TLM 的 MaidUpdateActivityFromSchedule 每 tick 按时间表（Schedule.m_38019_）
 *   切换女仆 activity——游戏内休息/睡觉时段 → REST/HOME/IDLE。
 * - 任务行为（MaidBuildBehavior 等）注册在 WORK activity，只有 activity 激活
 *   才被 Brain 评估——activity 不是 WORK 时行为 canUse 根本不执行 →
 *   "下达成功（setPlan+setTask 都通过）但女仆不建造"。
 * - 症状特征：core 行为（自保/战术，任何 activity 都评估）日志正常，
 *   WORK 任务行为日志一条没有（10:42 会话实证：jump attack 有日志、
 *   build canUse 零条）。
 *
 * 做法：注入 updateActivityFromSchedule（private static 4 参，两个 public 重载
 * 的汇聚点）——女仆当前任务是 maid_smart:build 时强制切 WORK 并跳过 schedule
 * 计算。下达建造 = 玩家明确意图，休息时段也必须建；任务切走后恢复正常作息。
 *
 * v1.5.176：method 指定完整 descriptor——SMART 版 TLM 存在 3 个同名重载
 * （public (EntityMaid,Brain) / public (EntityMaid) / private (ServerLevel,EntityMaid,Brain,long)），
 * 仅按名字匹配会命中第一个 public 重载 → InvalidInjectionException →
 * MixinTransformerError 致命崩溃（2026-08-09 11:32 会话实证）。带 descriptor
 * 精确命中 private 4 参汇聚点（javap 实证 SMART jar 中该方法存在）。
 */
@Mixin(MaidUpdateActivityFromSchedule.class)
public abstract class BuildActivityForceMixin {
    @Inject(method = "updateActivityFromSchedule(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Lnet/minecraft/world/entity/ai/Brain;J)V",
            at = @At("HEAD"), cancellable = true)
    private static void maidSmartForceWorkActivity(
            ServerLevel level, EntityMaid maid, Brain<?> brain, long gameTime,
            CallbackInfo ci) {
        if (maid.getTask() != null && maid.getTask().getUid() != null
                && "maid_smart".equals(maid.getTask().getUid().m_135827_())
                && "build".equals(maid.getTask().getUid().m_135815_())) {
            // v1.5.177：暂停 = 解除绑定——暂停中【不】强制 WORK，女仆恢复正常作息
            //（可自由走动/切任务去干别的事）；恢复建造后重新强制。
            // canUse 也已挡掉行为评估（MaidBuildBehavior.m_6114_），双保险。
            // v1.5.180：暂停按女仆绑定区块判定（多区块共存）
            if (com.maidsmart.build.BuildPlan.isBoundPlanPaused(maid)
                    || com.maidsmart.build.BuildPlan.isMaidPaused(maid)) {
                return;
            }
            try {
                // Brain.m_21889_ = setActiveActivity（SRG 实证）；
                // Activity.f_37980_ = WORK（SRG 实证：static{} 里 "work" 赋给 f_37980_）
                brain.m_21889_(Activity.f_37980_);
                ci.cancel(); // 跳过时间表切换——建造期间 activity 钉在 WORK
            } catch (Exception ignored) {
            }
        }
    }
}
