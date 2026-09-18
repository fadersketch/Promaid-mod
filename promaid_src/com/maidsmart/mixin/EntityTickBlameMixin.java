package com.maidsmart.mixin;

import com.maidsmart.combat.FriendlyWindGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.2 实测五百六十：**外力归因**——记录"这一 tick 里最近一个开始 tick 的实体"。
 *
 * 用途见 {@link FriendlyWindGuard}：法术/风弹/火球推人时，调用栈必定围着"效果实体"自己的
 * tick（铁魔法在自己的 tick 里射线命中后推、火球在自己的 tick 里爆炸、瞬发范围法术在
 * 女仆本人的 tick 里结算、GustCollider 由法书在施法瞬间手动 tick 一次也在这条链上），
 * 所以"谁在 tick"就等价于"这一下是谁推的"。
 *
 * 【为什么只在 HEAD 写、不在 RETURN 还原】javap 实证：投射物的命中判定/爆炸发生在
 * 自己的 tick 里 **`super.tick()` 返回之后**（铁魔法 `AbstractMagicProjectile`、
 * 原版 `Projectile.m_8119_` 都是这个结构）。若在 super 返回时还原，真正推人的那一刻
 * 归因就丢了。所以这里"粘住"，由服务器 tick 开始时（{@link FriendlyWindGuard#resetTick()}）
 * 统一清空 —— 陈旧值最多在一个 tick 内被误用，且只有"上个 tick 最后 tick 的实体正好是
 * 女仆方"这一种组合才可能，代价是漏一次豁免，方向安全。
 *
 * SRG 名（手工编译无 refmap）：m_8119_ = Entity.tick。
 */
@Mixin(Entity.class)
public abstract class EntityTickBlameMixin {

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void promaid$tickBlame(CallbackInfo ci) {
        FriendlyWindGuard.beginTick((Entity) (Object) this);
    }
}
