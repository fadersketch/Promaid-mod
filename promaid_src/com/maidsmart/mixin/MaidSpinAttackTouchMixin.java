package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【激流旋转突进碰到实体时的伤害】。原版旋转突进的伤害链路是：
 * {@code LivingEntity.tick} →（{@code f_20938_ > 0} 时）{@code m_21071_}(checkAutoSpinAttack) → 扫过包围盒
 * 里的 {@code LivingEntity} → {@code m_6727_}(doAutoAttackOnTouch)。
 * 而 {@code m_6727_} 在 **LivingEntity 上是空实现**（1.20.1 字节码就一条 {@code return}）——只有 **Player**
 * 把它覆写成 {@code attack()}。也就是说女仆置位之后，原版的整套突进（tick 递减、扫掠、撞墙收招）都会正常
 * 跑，**唯独"打中"这一下没有任何结算**。本 mixin 补上这一记。
 * 【为什么用注入而不是让女仆覆写】{@code EntityMaid} 是 TLM 的类，本模组不改它源码；而且它继承链上并没有覆
 * 写 {@code m_6727_}（字节码实证只覆写了 {@code m_7327_}），所以注入基类即可覆盖所有女仆。
 * 【只在"正在突进"时结算】{@code m_6727_} 只会被 {@code m_21071_} 调用，而后者只在 {@code f_20938_ > 0}
 * （即突进中）才执行——天然具备条件，无需再判。
 * 【映射】本树 SRG {@code m_21071_}/{@code m_6727_}/{@code m_7327_}；neo 树官方名
 * {@code checkAutoSpinAttack}/{@code doAutoAttackOnTouch}/{@code doHurtTarget}。
 */
@Mixin(LivingEntity.class)
public abstract class MaidSpinAttackTouchMixin {

    @Inject(method = "m_6727_", at = @At("HEAD"))
    private void promaid$spinTouch(LivingEntity target, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof EntityMaid maid) {
            com.maidsmart.combat.MaidTridentSpinBehavior.onSpinTouch(maid, target);
        }
    }
}
