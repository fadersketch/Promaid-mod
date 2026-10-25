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
 * 时机举盾——拦截 TLM 原版 {@code MaidUseShieldTask}，改由 {@code MaidCombatTacticsBehavior} 的判定接管。
 *
 * 【口径（两次修正后定稿）】近战**回到原版口径**——8 格内就举盾，连"攻击冷却中"也不再要求，且举盾不影响移
 * 速；远程敌人放宽到 15 格 + 视线；**枪械任务重新允许举盾**（原版 gun_attack 本就注册了举盾，是早期误挡）。
 * 唯一例外是弓 / 弩 / 三叉戟 / 御币蓄力中：它们与盾抢同一个"使用物品"槽位（原版 {@code startUsingItem}
 * 互斥），物理上不能同时成立。
 *
 * 【原版行为的问题（反编译实证）】{@code checkExtraStartConditions} = {@code canUseShield} && 目标 8 格内
 * → {@code start} 举盾（OFF_HAND），{@code canStillUse} 同样判定 → 女仆全程举着盾站桩挨打，像盾兵不像战士。
 *
 * 【注入点】{@code checkExtraStartConditions}（TLM 源码名，reobf 双方法模式下 mixin 挂源码方法；
 * {@code canStillUse} 直接 return {@code checkExtraStartConditions}，注入一处两头生效）。战术关闭 → 不
 * setReturnValue、走原版逻辑；战术开启 → 完全替换判定。
 */
@Mixin(MaidUseShieldTask.class)
public abstract class MaidShieldTimingMixin {

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z", at = @At("HEAD"), cancellable = true)
    private void maidsmart$shieldTiming(ServerLevel level, EntityMaid maid,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包对野生女仆的规则一律不动）
        }
        if (com.maidsmart.combat.SpearAttackBehavior.takesOverMovement(maid)) {
            // 实测一千〇一十八：长矛冲锋占着同一个使用物品槽位，战术关着也不能举盾顶掉它
            cir.setReturnValue(false);
            return;
        }
        if (!MaidCombatTacticsBehavior.isTacticsEnabled(maid)) {
            return; // 战术关闭：原版"一直举盾"逻辑
        }
        cir.setReturnValue(MaidCombatTacticsBehavior.shouldUseShield(maid));
    }
}
