package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.207：玩家对女仆伤害策略（面板 combat.playerDamageMode）——
 * TLM 原版 `EntityMaid.m_6469_`（hurt）：玩家（且是该女仆主人）攻击时
 * `amount = Mth.m_14036_(amount / 5.0f, 0.0f, 2.0f)`——伤害 ÷5 封顶 2 点：
 * 原版剑 7 点 ÷5 ≈ 1.4 再被护甲减伤 ≈ 0（"看起来打不到"）；高伤武器（如
 * 更好的战斗模组剑 8~12 点）÷5 后仍能打满 2 点 + 击退反馈（"能打到"）。
 *
 * 模式（playerDamageMode）：
 * 0 = 跟随 TLM 原版（÷5 封顶 2）；
 * 1 = 玩家伤害完全免疫（任何玩家，含陌生人/弓弩投掷物——HEAD 直接 return false）；
 * 2 = 玩家伤害无限制（解除 ÷5 压制，像打普通生物一样）；
 * 3 = 玩家伤害有上限（单次伤害 = 女仆最大生命 × playerDamageMaidCap，默认 10%）；
 * 4 = 仅受到一点伤害（v1.5.217：单次伤害上限 1 点——被打有反馈但不疼）。
 *
 * 实现：HEAD 注入处理"完全免疫"；@Redirect 拦截 TLM 的 Mth.m_14036_ 压制调用
 * （m_6469_ 内唯一一处）——mode 2/3/4 时把已 ÷5 的值还原再封顶。
 */
@Mixin(EntityMaid.class)
public abstract class MaidPlayerDamageMixin {

    @Inject(method = "m_6469_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$playerDamageHead(DamageSource source, float amount,
                                            CallbackInfoReturnable<Boolean> cir) {
        Entity attacker = source.m_7639_();
        if (!(attacker instanceof Player)) {
            return; // 非玩家攻击（怪物/环境/女仆互殴等）不管
        }
        if (MaidSmartConfig.PLAYER_DAMAGE_MODE.get() == 1) {
            cir.setReturnValue(false); // 完全免疫：不掉血、不受伤动画、不击退
        }
    }

    /** 拦截 TLM 的伤害压制 `Mth.m_14036_(amount/5, 0, 2)`——value 已是 ÷5 后的值 */
    @Redirect(method = "m_6469_",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;m_14036_(FFF)F"))
    private float maidsmart$unclampPlayerDamage(float value, float min, float max) {
        int mode = MaidSmartConfig.PLAYER_DAMAGE_MODE.get();
        if (mode == 0) {
            return Mth.m_14036_(value, min, max); // 原版压制
        }
        float original = value * 5.0f; // 还原 ÷5 前的原始伤害
        if (mode == 2) {
            return original; // 无限制
        }
        if (mode == 4) {
            return Math.min(original, 1.0f); // v1.5.217：仅受到一点伤害（单次上限 1 点）
        }
        // mode 3：单次伤害上限 = 最大生命 × 比例
        float cap = (float) (((EntityMaid) (Object) this).m_21233_()
                * MaidSmartConfig.PLAYER_DAMAGE_MAID_CAP.get());
        return Math.min(original, cap);
    }
}
