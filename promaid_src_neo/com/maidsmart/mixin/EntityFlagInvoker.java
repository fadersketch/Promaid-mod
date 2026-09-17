package com.maidsmart.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v1.2.0（1.21.1）：把 Entity.setSharedFlag / getSharedFlag 暴露出来。
 *
 * 用途：原版「滑翔」只对玩家开放——tryToStartFallFlying / startFallFlying 只定义在
 * Player 上（Entity/LivingEntity 都没有，字节码实证）。但滑翔【物理】并不在 Player
 * 里，而在 LivingEntity.travel(Vec3)：它唯一的闸门是 isFallFlying()，而
 * isFallFlying() 就是 getSharedFlag(7)（字节码实证），全程无 instanceof Player。
 * 所以非玩家实体只要把第 7 位共享标志位置 true，就能获得原版滑翔物理。
 *
 * 而 Entity.setSharedFlag(int, boolean) 是 protected，所以用 @Invoker 暴露。
 * （第 7 位 = 原版 Player.startFallFlying 写的那一位；LivingEntity.updateFallFlying
 * 也用 bipush 7 读取。）
 *
 * 注意：共享标志位本身是 SynchedEntityData：服务端置位会自动同步到客户端，
 * 无需自定义网络包。
 */
@Mixin(Entity.class)
public interface EntityFlagInvoker {
    @Invoker("setSharedFlag")
    void promaid$setSharedFlag(int flag, boolean value);

    @Invoker("getSharedFlag")
    boolean promaid$getSharedFlag(int flag);
}
