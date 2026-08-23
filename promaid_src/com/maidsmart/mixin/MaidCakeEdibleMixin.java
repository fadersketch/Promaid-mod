package com.maidsmart.mixin;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.5.142：蛋糕变成可吃物品——女仆（及一切食物判定）认蛋糕为食物。
 *
 * 背景：设定上女仆爱吃蛋糕，但原版蛋糕物品没注册 foodProperties（1.20.1 的蛋糕
 * 物品就是普通 BlockItem，无 CakeItem 类）：
 * - Item.m_41472_（isEdible）只查字段 f_41380_（foodProperties）→ 恒 false
 *   → 女仆的进食判定（TLM DefaultMaidWorkMeal.isWorkMeal → ItemStack.m_41614_
 *   → Item.m_41472_）永远把蛋糕当"不可吃"
 * - Item.m_41473_（getFoodProperties）返回字段 → null → 进食流程直接跳过
 *
 * 修复：对蛋糕物品（注册名 minecraft:cake）两个方法返回食物属性（营养 14、
 * 饱和度 0.6——整块蛋糕=7切片×2，一次吃完吃掉整块）。getUseAnimation（m_6164_）
 * = isEdible ? EAT : NONE、使用时长（m_8105_）也由 isEdible 派生 → 自动获得正常
 * 进食动作/音效/粒子，无需额外处理。玩家右键蛋糕仍是放方块（BlockItem.use 未改），
 * 不受影响。
 */
@Mixin(Item.class)
public abstract class MaidCakeEdibleMixin {
    /** 蛋糕食物属性（营养 14=7切片×2、饱和度 0.6——整块蛋糕一次吃完的量）
     *  v1.5.292 修复：旧值营养 2 只等于一口切片，但女仆吃蛋糕会消耗整块，
     *  回复量与消耗不匹配。整块蛋糕=7切片，每片营养 2，合计 14。 */
    private static final FoodProperties CAKE_FOOD =
            new FoodProperties.Builder().m_38760_(14).m_38758_(0.6f).m_38767_();

    /** 是否为蛋糕物品（1.20.1 无 CakeItem 类，蛋糕就是注册名 minecraft:cake 的 BlockItem） */
    private static boolean isCake(Item item) {
        if (!(item instanceof net.minecraft.world.item.BlockItem)) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return key != null && "minecraft:cake".equals(key.toString());
    }

    @Inject(method = "m_41472_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$cakeIsEdible(CallbackInfoReturnable<Boolean> cir) {
        if (isCake((Item) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "m_41473_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$cakeFoodProperties(CallbackInfoReturnable<FoodProperties> cir) {
        if (isCake((Item) (Object) this)) {
            cir.setReturnValue(CAKE_FOOD);
        }
    }
}
