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
 * v1.3.0(beta)【骑乘链路·坐骑别自己乱逛】：把原版"玩家在骑就别闲逛"的规则，原样扩展到
 * "本模组的女仆在骑也别闲逛"。
 *
 * ── 为什么必须有这一条（这是骑乘链路唯一必须动原版的地方）──
 * {@code RandomStrollGoal.canUse()} 的第一句是
 * {@code if (this.mob.hasControllingPassenger()) return false;}（javap 实证，1.20.1 与
 * 1.21.1 两版逐字相同）——原版靠它保证"玩家骑着马时马不会自己溜达"。
 *
 * <p>{@code hasControllingPassenger()} = {@code getControllingPassenger() != null}，
 * 而 {@code AbstractHorse.getControllingPassenger()} 的字节码是「{@code isSaddled()} 且
 * 第一乘客 {@code instanceof Player} 才返回他，否则走 {@code super}（一路回 {@code Entity}
 * 恒返回 {@code null}）」——**女仆当乘客时它恒为 null**。这正是整条骑乘链路的关键事实
 * （也是为什么女仆骑上去后载具仍走普通 {@code travel()}、导航照常推着它走）：我们**要**
 * 它走普通 travel，但**不要**它同时跑自己的随机闲逛、跟我们喂的导航抢方向。
 *
 * <p>判据 = {@link com.maidsmart.combat.MaidRideKit#isDriven}（乘客里有本模组有主的、
 * 且不是扫帚模式的女仆），但**模组仆从坐骑（无鞍可骑仆从）要排除**：实测七百七十起，那一类走
 * "单独一个区间"——行动逻辑全归它自己的 AI（含它自己的闲逛 goal {@code Summoned.WanderGoal}，
 * 它继承自 {@code RandomStrollGoal}），我们只赋速度。若这里仍按 {@code isDriven} 拦，就会把它
 * 的闲逛一并掐掉、与"全换成仆从自己的"相悖。不满足 → 原版行为一个字节不动：原版玩家的马
 * （它本来 controllingPassenger 非空、canUse 早就返回 false）、别的模组的坐骑、无主女仆骑的生物
 * 全部照旧。作用面很窄：只掐掉"随机溜达"这一个 goal；坐骑的恐慌/繁殖/跟随亲代/看玩家
 * 一律照旧，我们喂的移动目标走它自己的 {@code PathNavigation}，与这个 goal 无关。
 */
@Mixin(RandomStrollGoal.class)
public abstract class RandomStrollGoalRiddenMixin {

    /** RandomStrollGoal 持有的那只怪（1.20.1 SRG 名；字段名两版不同，本树见另一份同名类） */
    @Shadow
    @Final
    protected PathfinderMob f_25725_;

    @Inject(method = "m_8036_", at = @At("HEAD"), cancellable = true)
    private void promaid$noStrollWhileMaidDriven(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (com.maidsmart.combat.MaidRideKit.isNoSaddleRideable(this.f_25725_)) {
                return; // 实测七百七十：模组仆从坐骑的闲逛归它自己，不拦
            }
            if (com.maidsmart.combat.MaidRideKit.isDriven(this.f_25725_)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}
