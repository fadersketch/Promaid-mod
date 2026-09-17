package com.maidsmart.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v1.2.0 实测五百三十四：暴露 LivingEntity 的**旋转突进**状态（给激流三叉戟用）。
 *
 * 【为什么要新开一个，而不复用 {@link EntityFlagInvoker}】
 * 反编译实证：`Entity.setSharedFlag` 写的是 **`Entity.DATA_SHARED_FLAGS_ID`**
 * （滑翔位就走它）；而 `isAutoSpinAttack` 读的是
 * **`LivingEntity.DATA_LIVING_ENTITY_FLAGS`**——**两个不同的 SynchedEntityData**。
 * 所以滑翔那套 invoker 够不到旋转突进，必须按 LivingEntity 单独暴露。
 *
 * 【tick 数同源】`autoSpinAttackTicks` 也在 LivingEntity 上，
 * 由 `LivingEntity.tick` 每 tick 递减并调 `checkAutoSpinAttack` 做扫掠——
 * 女仆置位后即自动获得原版的整套突进行为。
 */
@Mixin(LivingEntity.class)
public interface LivingEntitySpinAccessor {

    /** 读旋转突进剩余 tick（LivingEntity.autoSpinAttackTicks） */
    @Accessor("autoSpinAttackTicks")
    int promaid$getSpinTicks();

    /** 写旋转突进剩余 tick——原版 `startAutoSpinAttack` 做的第一件事 */
    @Accessor("autoSpinAttackTicks")
    void promaid$setSpinTicks(int ticks);

    /** 置/清 LivingEntity 的标志位（1 = 使用中、2 = 副手、4 = 旋转突进） */
    @Invoker("setLivingEntityFlag")
    void promaid$setLivingFlag(int flag, boolean set);
}
