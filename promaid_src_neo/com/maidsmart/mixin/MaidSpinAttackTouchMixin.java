package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0 实测五百三十四【激流旋转突进碰到实体时的伤害】。
 *
 * 【为什么必须有这一条】原版旋转突进的伤害链路是：
 * <pre>
 *   LivingEntity.tick →（autoSpinAttackTicks > 0 时）checkAutoSpinAttack
 *     → 扫过包围盒里的 LivingEntity → doAutoAttackOnTouch
 * </pre>
 * 而 `doAutoAttackOnTouch` 在 **LivingEntity 上是空实现**——只有 **Player** 把它覆写成
 * `attack()`（1.21.1 字节码实证：`Player.doAutoAttackOnTouch` → `this.attack(target)`）。
 * 也就是说女仆置位之后，原版的整套突进（tick 递减、扫掠、撞墙收招）都会正常跑，
 * **唯独"打中"这一下没有任何结算**。本 mixin 补上这一记。
 *
 * 【为什么用注入而不是让女仆覆写】`EntityMaid` 是 TLM 的类，本模组不改它源码；
 * 而且它继承链上并没有覆写 `doAutoAttackOnTouch`（字节码实证只覆写了 `doHurtTarget`），
 * 所以注入基类即可覆盖所有女仆。
 *
 * 【只在"正在突进"时结算】`doAutoAttackOnTouch` 只会被 `checkAutoSpinAttack` 调用，
 * 而后者只在 `autoSpinAttackTicks > 0`（即突进中）才执行——天然具备条件，无需再判。
 */
@Mixin(LivingEntity.class)
public abstract class MaidSpinAttackTouchMixin {

    @Inject(method = "doAutoAttackOnTouch", at = @At("HEAD"))
    private void promaid$spinTouch(LivingEntity target, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof EntityMaid maid) {
            com.maidsmart.combat.MaidTridentSpinBehavior.onSpinTouch(maid, target);
        }
    }
}
