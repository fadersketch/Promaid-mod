package com.maidsmart.mixin;

import com.maidsmart.combat.FriendlyWindGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.2 实测五百六十：**友军风免**的总闸——所有外力位移的唯一出口。
 *
 * javap 实证（1.21.1 `Entity`）：`setDeltaMovement(Vec3)` 就是 `this.deltaMovement = vec`
 * 一行赋值，另外两个重载最终都汇到它：`setDeltaMovement(double,double,double)` 自己 new 一个
 * Vec3 再调它；`addDeltaMovement(Vec3)` 也是"取旧值 → 相加 → 调它"。
 * 所以在这里拦一次，等于拦住了**所有**"凭空改速度"的路径：
 *
 * - 铁魔法呼啸之风 `GustCollider.onHitEntity`：`target.setDeltaMovement(target.getDeltaMovement().add(推力))`
 * - 新生魔艺击退构件 / 爆炸（`ANExplosion` 是自己覆写的 explode，走不到原版那条）
 * - 原版 `Explosion` 的爆炸击退（那一路另有 {@code ExplosionWindGuardMixin} 做双保险 +
 *   吞掉发给主人的"netty 击退向量"）
 *
 * 拦不拦由 {@link FriendlyWindGuard#shouldBlockTick} 决定（归因 + 三条判据），
 * 它自身对任何异常都返回 false —— 最坏情况是"这次没免"，绝不会误伤正常移动。
 *
 * 1.21.1 官方名：setDeltaMovement = Entity.setDeltaMovement。
 */
@Mixin(Entity.class)
public abstract class EntitySetDeltaMovementMixin {

    @Inject(method = "setDeltaMovement", at = @At("HEAD"), cancellable = true)
    private void promaid$friendlyWindImmune(Vec3 vec, CallbackInfo ci) {
        try {
            if (FriendlyWindGuard.shouldBlockTick((Entity) (Object) this, vec)) {
                ci.cancel(); // 保持原速度：等于这一下没推
            }
        } catch (Throwable ignored) {
        }
    }
}
