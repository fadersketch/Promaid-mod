package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidPanicTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.129：干活不被打断③——小伤不再恐慌逃跑。
 *
 * 根因：MaidPanicTask（CORE 优先级 1）只要"被攻击过/附近有敌对"就切 PANIC 活动
 * 逃跑，农活/挖矿/建造女仆被打一下就丢下工作满场跑。
 *
 * 修复：非战斗任务进行中、血量 ≥ 30% 时不恐慌（继续干活）；血量 < 30% 仍正常
 * 恐慌，且我们的自保行为（SelfPreservationBehavior，core 250）会在低血+威胁时
 * 接管逃跑/回血——不会致死。总开关：misc.workUninterrupted。
 */
@Mixin(MaidPanicTask.class)
public abstract class PanicGatingMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noPanicWhileWorking(ServerLevel level, EntityMaid maid, long gameTime,
                                               CallbackInfo ci) {
        // v1.1.0 实测一百九十六【有保命道具→不惊慌逃跑】（用户："让女仆保证在有保命
        // 道具的时候不触发自保逃跑"）：TLM 原生 MaidPanicTask 是 CORE 优先级 1——
        // 只被前面的"干活+血量≥30%"门控管着，血量 <30% 的女仆即使【带着绀珠之药/
        // 不死图腾】也会被 TLM 原版恐慌满场跑，绕过了自保行为的 canFlee 判定
        // （自保 250 的逃跑分支 100/1091/1109 都查了保命物品，唯独原生恐慌没查）。
        // 修复：与自保同口径——有保命物品且 COMBAT_FLEE_WITH_SAVE_ITEM 关闭
        // → 取消恐慌（她死不了，治疗/战斗由自保行为接管）。canFlee 已内含开关判定。
        if (!com.maidsmart.combat.SelfPreservationBehavior.canFlee(maid)) {
            ci.cancel();
            return;
        }
        if (com.maidsmart.config.MaidSmartConfig.MISC_WORK_UNINTERRUPTED.get()
                && MaidWorkTags.isNonCombatWork(maid)
                && maid.m_21223_() >= maid.m_21233_() * 0.3f) {
            ci.cancel();
        }
    }
}
