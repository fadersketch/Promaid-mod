package com.maidsmart.mixin;

import com.maidsmart.combat.FriendlyWindGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【外力归因】——记录"这一 tick 里最近一个开始 tick 的实体"。
 *
 * 用途见 {@link FriendlyWindGuard}：法术/风弹/火球推人时，调用栈必定围着"效果实体"自己的 tick（铁魔法
 * 在自己的 tick 里射线命中后推、火球在自己的 tick 里爆炸、瞬发范围法术在女仆本人的 tick 里结算、
 * {@code GustCollider} 由法书在施法瞬间手动 tick 一次也在这条链上），所以"谁在 tick"≈"这一下是谁推的"。
 *
 * 【为什么只在 HEAD 写、不在 RETURN 还原】javap 实证：投射物的命中判定/爆炸发生在自己的 tick 里
 * {@code super.tick()} 返回**之后**（铁魔法 {@code AbstractMagicProjectile}、原版 {@code Projectile}
 * 都是这个结构）——在 super 返回时还原的话，真正推人的那一刻归因就丢了。所以这里"粘住"，由服务器 tick
 * 开始时（{@link FriendlyWindGuard#resetTick()}）统一清空；陈旧值最多在一个 tick 内被误用、代价是漏一次
 * 豁免，方向安全。
 *
 * 【映射】本树 SRG {@code m_8119_} = {@code Entity.tick}；neo 树写官方名 {@code tick}。
 */
@Mixin(Entity.class)
public abstract class EntityTickBlameMixin {

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void promaid$tickBlame(CallbackInfo ci) {
        FriendlyWindGuard.beginTick((Entity) (Object) this);
    }
}
