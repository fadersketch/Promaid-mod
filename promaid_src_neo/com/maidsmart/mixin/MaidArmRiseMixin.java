package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.SpearAirRaidBehavior;
import com.maidsmart.combat.SpearAttackBehavior;
import com.maidsmart.combat.SpearCoreCompat;
import com.maidsmart.tool.PromaidLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 长矛蓄力/冲锋期间压掉 TLM 的举臂标志（{@code DATA_ARM_RISE}）。
 *
 * <p>TLM 的 {@code MaidAttackStrafingTask} 在 {@code start()} 里调
 * {@code setSwingingArms(true)}，模型据此在整个任务期间播放举臂/挥臂姿态；
 * 这条链路和 {@code LivingEntity.swing} 无关，之前的 swing 闸拦不到。
 * 这里在写入源头直接拒绝 true，并留一条带调用来源的诊断日志。
 */
@Mixin(EntityMaid.class)
public abstract class MaidArmRiseMixin {

    @Unique
    private long promaid$lastArmRiseLog = -1000L;

    @Inject(method = "setSwingingArms", at = @At("HEAD"), cancellable = true)
    private void promaid$blockArmRise(boolean value, CallbackInfo ci) {
        if (!value) {
            return;
        }
        EntityMaid maid = (EntityMaid) (Object) this;
        boolean spearLink;
        try {
            spearLink = SpearCoreCompat.isChargingForAnimation(maid)
                    || SpearAttackBehavior.takesOverMovement(maid)
                    || SpearAirRaidBehavior.isActive(maid);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidArmRiseMixin", ignored);
            return;
        }
        if (!spearLink) {
            return;
        }
        try {
            long now = maid.level().getGameTime();
            if (now - this.promaid$lastArmRiseLog >= 40L) {
                this.promaid$lastArmRiseLog = now;
                PromaidLog.log("挥臂诊断", PromaidLog.nameOf(maid)
                        + " setSwingingArms(true) 已拦截，来源=" + promaid$caller());
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidArmRiseMixin", ignored);
        }
        ci.cancel();
    }

    /** 取第一个非 mixin 栈帧，定位到底是谁在置举臂。 */
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
            com.maidsmart.diag.Diag.swallow("MaidArmRiseMixin", ignored);
            return "?";
        }
    }
}
