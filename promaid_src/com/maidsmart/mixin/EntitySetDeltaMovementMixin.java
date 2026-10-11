package com.maidsmart.mixin;

import com.maidsmart.combat.FriendlyWindGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【友军风免】的总闸——所有外力位移的唯一出口。
 *
 * javap 实证（{@code Entity}）：{@code setDeltaMovement(Vec3)} 就是一行字段赋值，另两个重载最终都汇到
 * 它：{@code setDeltaMovement(double,double,double)} 自己 new 一个 Vec3 再调它、{@code addDeltaMovement
 * (Vec3)} 也是"取旧值 → 相加 → 调它"。所以在这里拦一次 = 拦住**所有**"凭空改速度"的路径：
 * - 铁魔法呼啸之风 {@code GustCollider}（{@code setDeltaMovement(getDeltaMovement().add(推力))}）
 * - 新生魔艺击退构件 / 爆炸（{@code ANExplosion} 自己覆写 explode，走不到原版那条）
 * - 原版 {@code Explosion} 的爆炸击退（另有 {@code ExplosionWindGuardMixin} 双保险 + 吞掉发给主人
 *   的"击退向量"）
 *
 * 拦不拦由 {@link FriendlyWindGuard#shouldBlockTick} 决定（归因 + 三条判据），它自身对任何异常都返回
 * false——最坏情况是"这次没免"，绝不会误伤正常移动。
 * 【映射】本树 SRG {@code m_20256_} = {@code Entity.setDeltaMovement}；neo 树写官方名。
 */
@Mixin(Entity.class)
public abstract class EntitySetDeltaMovementMixin {

    @Inject(method = "m_20256_", at = @At("HEAD"), cancellable = true)
    private void promaid$friendlyWindImmune(Vec3 vec, CallbackInfo ci) {
        try {
            if (FriendlyWindGuard.shouldBlockTick((Entity) (Object) this, vec)) {
                ci.cancel(); // 保持原速度：等于这一下没推
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EntitySetDeltaMovementMixin", ignored);
        }
    }
}
