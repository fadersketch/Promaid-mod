package com.maidsmart.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0(beta)（1.21.1）实测七百六十八【被女仆骑着的仆从·它自己的 AI 不许被骑手关掉】。
 *
 * <h2>玩家原话</h2>
 * 「如果女仆采用的是远程攻击的方式，下界合金巨兽的锁敌范围明显跟不上女仆的锁敌。导致拉开了
 * 很长一段距离之后下界合金巨兽直接不会攻击了。」
 *
 * <h2>根因（javap 实证）</h2>
 * {@code Mob.tick()} 里每隔 5 拍调用一次 {@code updateControlFlags()}（字节码：{@code tickCount % 5
 * == 0} 且服务端），而它的第一句就是
 * {@code flag = !(getControllingPassenger() instanceof Mob)}，随后把 {@code goalSelector} 的
 * {@code MOVE}/{@code JUMP}/{@code LOOK} 设成这个值——「**驾驶者是个生物** → 坐骑自己的 goal 全停」。
 * {@code Mob.getControllingPassenger()} 认的正是"第一乘客是 Mob"——**女仆就是个 Mob**。
 * {@code GoalSelector.tick()} 于是把所有带这三个控制位的 goal 直接 {@code stop()}；诡厄灾变
 * 下界合金巨兽仆从的接近（{@code InternalSummonMoveGoal}）与全部技能（{@code InternalSummonAttackGoal}
 * 家族）恰好全带这三个控制位 → 它自己一步都走不了、一招都放不出。
 *
 * <h2>修法（调用点注入，天然兼容诡厄的重写）</h2>
 * 注入点选在 {@code Mob.tick()} 里那一句 {@code updateControlFlags()} **调用之后**（而不是
 * {@code Mob.updateControlFlags} 的 TAIL——诡厄 {@code Summoned} 重写了那个方法，TAIL 注入会被
 * 虚分派跳过）。回调 {@link com.maidsmart.combat.MaidRideKit#reopenRiddenCombatGoals}：只对
 * "我们棍子绑的女仆正在骑的无鞍可骑仆从 + 它此刻有攻击目标"把 MOVE/LOOK/JUMP 开回来。
 * 判据不满足 → 一个控制位都不动，原版/诡厄行为逐字节不变（完整口径见那个方法）。
 */
@Mixin(Mob.class)
public abstract class MobRiddenControlFlagsMixin {

    @Inject(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;updateControlFlags()V",
                    shift = At.Shift.AFTER))
    private void promaid$reopenRiddenCombatGoals(CallbackInfo ci) {
        try {
            if ((Object) this instanceof Mob self) {
                com.maidsmart.combat.MaidRideKit.reopenRiddenCombatGoals(self);
            }
        } catch (Throwable ignored) {
        }
    }
}
