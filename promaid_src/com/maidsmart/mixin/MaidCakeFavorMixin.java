package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.meal.DefaultMaidHomeMeal;
import com.github.tartaricacid.touhoulittlemaid.entity.task.meal.DefaultMaidWorkMeal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实测一百二十五：女仆吃完蛋糕好感 +10（工作餐/家餐都算）。
 *
 * 背景：TLM 原版 DefaultMaidWorkMeal/HomeMeal.onMaidEat 只按食物营养随机给
 * 0~1 点好感，蛋糕（经 MaidCakeEdibleMixin 变为可吃物品）没有额外价值。
 * 这里在 onMaidEat 返回后检测到是蛋糕（注册名 minecraft:cake）就补 +10——
 * 与玩家投喂（MaidCakeEatHandler）同口径：每吃一整块蛋糕 = +10。
 *
 * 时机说明：TLM 的好感在进食动画开始瞬间 apply（onMaidEat 内部），本 mixin
 * 在同一时刻补 +10，与 TLM 原版语义一致；静默不弹气泡（投喂才弹）。
 */
@Mixin({DefaultMaidWorkMeal.class, DefaultMaidHomeMeal.class})
public abstract class MaidCakeFavorMixin {
    @Inject(method = "onMaidEat", at = @At("RETURN"))
    private void maidsmart$cakeFavorOnEat(EntityMaid maid, ItemStack stack, InteractionHand hand, CallbackInfo ci) {
        if (com.maidsmart.task.MaidCakeEatHandler.isCake(stack)) {
            com.maidsmart.task.MaidCakeEatHandler.onCakeEaten(maid, false);
        }
    }
}
