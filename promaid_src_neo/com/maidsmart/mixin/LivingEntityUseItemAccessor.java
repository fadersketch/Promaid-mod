package com.maidsmart.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露原版 {@code useItemRemaining}（Mojmap 字段名）：长矛核心在 {@code damageEndTick}
 * 会自己 {@code stopUsingItem()}，冲锋进度从满值掉回 0，再举矛又要重新爬满。
 * 长矛战斗链路每 tick 把剩余使用时长钉在「伤害窗口最后一 tick」，进度恒为 1。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityUseItemAccessor {
    @Accessor("useItemRemaining")
    void promaid$setUseItemRemaining(int ticks);
}
