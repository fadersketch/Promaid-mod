package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.AbstractEntityFromItem;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.252q：自动生成的钓鱼坐垫被摧毁时【不掉落坐垫物品】。
 *
 * TLM 的 AbstractEntityFromItem.killEntity()（玩家潜行拳打 / 创造击碎时调用）会
 * discard 实体 + 按 DO_MOB_LOOT 规则掉落 getKilledStack()（坐垫物品）。promaid
 * 自动生成的标记坐垫是"虚拟"的——被破坏不应留下物品（免费坐垫 = 变相刷物品）。
 * 标记坐垫走 killEntity → 只移除实体，不执行掉落。
 */
@Mixin(AbstractEntityFromItem.class)
public abstract class ChairNoDropMixin {
    @Inject(method = "killEntity", at = @At("HEAD"), cancellable = true)
    private void maidsmart$noDropOnKill(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (com.maidsmart.fishing.FishingChairService.isAutoChair(self)) {
            self.m_146870_(); // 直接移除实体，无掉落
            ci.cancel();
        }
    }
}
