package com.maidsmart.mixin;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.0(beta)（1.21.1）【骑乘链路·坐骑别自己乱逛】：把原版"玩家在骑就别闲逛"的规则，
 * 原样扩展到"本模组的女仆在骑也别闲逛"。
 *
 * <p>{@code RandomStrollGoal.canUse()} 的第一句是
 * {@code if (this.mob.hasControllingPassenger()) return false;}（javap 实证，两版逐字相同）
 * ——原版靠它保证"玩家骑着马时马不自己溜达"。而 {@code hasControllingPassenger()} =
 * {@code getControllingPassenger() != null}，{@code AbstractHorse/Pig/Strider} 的实现都要求
 * **第一乘客是 Player**（猪/炽足兽还要钓竿）→ **女仆当乘客时它恒为 null**，于是载具自己的
 * 随机闲逛仍会跑、跟我们喂的导航抢方向。这一条补上那一格。
 *
 * <p>判据 = {@link com.maidsmart.combat.MaidRideKit#isDriven}，但**模组仆从坐骑（无鞍可骑仆从）
 * 要排除**：实测七百七十起，那一类走的是"单独一个区间"——行动逻辑全归它自己的 AI（含它自己的
 * 闲逛 goal {@code Summoned.WanderGoal}，它继承自 {@code RandomStrollGoal}），我们只赋速度。
 * 若这里仍按 {@code isDriven} 拦，就会把它的闲逛一并掐掉、与"全换成仆从自己的"相悖。
 * 不满足判据 → 原版行为一个字节不动。
 * 完整口径见 1.20.1 树同名类的类注释（两份文件只有"字段名/方法名"两处不同）。
 */
@Mixin(RandomStrollGoal.class)
public abstract class RandomStrollGoalRiddenMixin {

    /** RandomStrollGoal 持有的那只怪（1.21.1 官方名） */
    @Shadow
    @Final
    protected PathfinderMob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void promaid$noStrollWhileMaidDriven(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (com.maidsmart.combat.MaidRideKit.isNoSaddleRideable(this.mob)) {
                return; // 实测七百七十：模组仆从坐骑的闲逛归它自己，不拦
            }
            if (com.maidsmart.combat.MaidRideKit.isDriven(this.mob)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
