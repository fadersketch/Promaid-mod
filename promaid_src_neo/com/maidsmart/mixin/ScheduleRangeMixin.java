package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.1.0 实测一百八十三（用户："在排班状态下，增大活动的范围"）：
 * TLM home 模式（排班必进）的 SchedulePos 用 TLM 自带配置
 * MAID_WORK_RANGE / MAID_IDLE_RANGE / MAID_SLEEP_RANGE（默认只有 8~16 格）
 * 收紧女仆活动半径——原版"限制区"机制下女仆出圈会被拉回，排班状态稍微离远
 * 一点就不行。本 mixin 把这三处半径取值改为 max(promaid「排班活动半径」,
 * TLM 设置)——promaid 值是下限（默认 32），TLM 调更大也尊重。
 *
 * 【1.21.1 移植修正】三处配置读取仍在 SchedulePos.restrictTo（javap 实证：读
 * MaidConfig.MAID_WORK/IDLE/SLEEP_RANGE.get() → Integer.intValue → EntityMaid.restrictTo(BlockPos,int)）；
 * 目标包名换成 NeoForge 的 net.neoforged.neoforge（Forge 的 net.minecraftforge 在
 * NeoForge 不存在，旧目标扫不到任何指令 → Redirector 必注入失败 → 启动崩溃）。
 */
@Mixin(SchedulePos.class)
public abstract class ScheduleRangeMixin {
    @Redirect(method = "restrictTo",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private Object maidsmart$scheduleActivityRange(ModConfigSpec.IntValue tlmRange) {
        return Math.max(com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get(),
                tlmRange.get());
    }
}
