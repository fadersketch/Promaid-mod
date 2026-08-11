package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUseShieldTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidCombatTacticsBehavior;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.134：时机举盾——把 TLM 原版 MaidUseShieldTask 的"8 格内有目标就一直举盾"
 * 改成 PVP 式攻防交替：攻击冷却中 + 目标贴身才举盾格挡，冷却满放盾攻击。
 *
 * 原版行为的问题（反编译实证）：checkExtraStartConditions = canUseShield &&
 * 目标 8 格内 → start 举盾（m_6672_ OFF_HAND），canStillUse 同样判定 →
 * 女仆全程举着盾站桩挨打，像盾兵不像战士。
 *
 * 注入点：checkExtraStartConditions（TLM 源码名，reobf 双方法模式下 mixin 挂
 * 源码方法；canStillUse 直接 return checkExtraStartConditions，注入一处两头生效）。
 * 战术关闭 → 不 setReturnValue，走原版逻辑；战术开启 → 完全替换判定。
 */
@Mixin(MaidUseShieldTask.class)
public abstract class MaidShieldTimingMixin {

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void maidsmart$shieldTiming(ServerLevel level, EntityMaid maid,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!MaidCombatTacticsBehavior.isTacticsEnabled(maid)) {
            return; // 战术关闭：原版"一直举盾"逻辑
        }
        cir.setReturnValue(MaidCombatTacticsBehavior.shouldUseShield(maid));
    }
}
