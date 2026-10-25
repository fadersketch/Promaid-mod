package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.SpearAirRaidBehavior;
import com.maidsmart.combat.SpearAttackBehavior;
import com.maidsmart.combat.SpearCoreCompat;
import com.maidsmart.tool.PromaidLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 长矛冲锋期间不播平A挥臂：spearcore 在 {@code onUseTick} 的命中循环里调
 * {@code swing(hand, true)}，TLM 的平A走一参 {@code swing(hand)}；两个重载分开处理。
 *
 * <p>一参全程吞；二参只在 spearcore 的伤害窗口放行——玩家口径是"只有造成伤害的那一下
 * 有动作"。两个分支都留一条带调用来源的诊断日志（每 20 tick 最多一条），
 * 用来定位还有没有别的链路在摆臂。
 */
@Mixin(LivingEntity.class)
public abstract class MaidSpearSwingMixin {

    @Unique
    private long promaid$lastSwingLog = -1000L;

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$noSwingWhileSpearCharging(InteractionHand hand, CallbackInfo ci) {
        if ((Object) this instanceof EntityMaid maid && promaid$suppress(maid)) {
            promaid$log(maid, "swing(1) 拦截");
            ci.cancel();
        }
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$noSwingWhileSpearCharging(InteractionHand hand, boolean offHand,
                                                   CallbackInfo ci) {
        if (!((Object) this instanceof EntityMaid maid) || !promaid$suppress(maid)) {
            return;
        }
        boolean allow = SpearCoreCompat.isDamageSwingFrame(maid);
        promaid$log(maid, allow ? "swing(2) 放行（伤害拍）" : "swing(2) 拦截");
        if (!allow) {
            ci.cancel();
        }
    }

    private static boolean promaid$suppress(EntityMaid maid) {
        return SpearCoreCompat.isChargingForAnimation(maid)
                || SpearAttackBehavior.suppressesMelee(maid)
                || SpearAirRaidBehavior.isActive(maid);
    }

    /** 每 20 tick 最多一条：记录调用来源与当前状态，定位其余手臂动作。 */
    @Unique
    private void promaid$log(EntityMaid maid, String what) {
        try {
            long now = maid.level().getGameTime();
            if (now - this.promaid$lastSwingLog < 20L) {
                return;
            }
            this.promaid$lastSwingLog = now;
            PromaidLog.log("挥臂诊断", PromaidLog.nameOf(maid) + " " + what
                    + "｜using=" + maid.isUsingItem()
                    + " armRise=" + maid.isSwingingArms()
                    + " attackAnim=" + String.format(java.util.Locale.ROOT, "%.2f", maid.attackAnim)
                    + "｜来源=" + promaid$caller());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidSpearSwingMixin", ignored);
        }
    }

    @Unique
    private static String promaid$caller() {
        try {
            return StackWalker.getInstance().walk(frames -> frames
                    .filter(f -> !f.getClassName().startsWith("com.maidsmart.mixin."))
                    .filter(f -> !f.getClassName().startsWith("java.lang.StackWalker"))
                    .findFirst()
                    .map(f -> f.getClassName() + "#" + f.getMethodName())
                    .orElse("?"));
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidSpearSwingMixin", ignored);
            return "?";
        }
    }
}
