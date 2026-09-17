package com.maidsmart.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v1.2.0（1.20.1）：暴露 Entity 的 protected 共享标志位读写。
 *
 * 滑翔的唯一闸门是 `isFallFlying()` = `LivingEntity.m_21255_()` = `getSharedFlag(7)`
 * （反编译实证，全程无 instanceof Player）；写位是 `setSharedFlag` = `m_20115_(int,boolean)`。
 * 两者都是 protected，经本 mixin 暴露给 {@link com.maidsmart.combat.MaidFlightKit}。
 * 服务端置位后经 SynchedEntityData 自动同步客户端，不需要自定义网络包。
 */
@Mixin(Entity.class)
public interface EntityFlagInvoker {

    @Invoker("m_20115_")
    void promaid$setSharedFlag(int flag, boolean set);

    @Invoker("m_20291_")
    boolean promaid$getSharedFlag(int flag);
}
