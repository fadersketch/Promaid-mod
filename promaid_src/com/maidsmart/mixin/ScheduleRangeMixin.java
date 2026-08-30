package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.1.0 实测一百八十三（用户："在排班状态下，增大活动的范围"）：
 * TLM home 模式（排班必进）的 SchedulePos.restrictTo 用 TLM 自带配置
 * MAID_WORK_RANGE / MAID_IDLE_RANGE / MAID_SLEEP_RANGE（默认只有 8~16 格）
 * 收紧女仆活动半径——原版"限制区"机制下女仆出圈会被拉回，排班状态稍微离远
 * 一点就不行。本 mixin 把这三处半径取值改为 max(promaid「排班活动半径」,
 * TLM 设置)——promaid 值是下限（默认 32），TLM 调更大也尊重。
 */
@Mixin(SchedulePos.class)
public abstract class ScheduleRangeMixin {
    @Redirect(method = "restrictTo(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/common/ForgeConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private Object maidsmart$scheduleActivityRange(ForgeConfigSpec.IntValue tlmRange) {
        return Math.max(com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get(),
                tlmRange.get());
    }
}
