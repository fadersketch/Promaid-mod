package com.maidsmart.brew;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * 1.21.1 药水数据组件适配层（v1.21.1 移植）——PotionUtils 在 1.21 已删除，
 * 药水信息改为 DataComponents.POTION_CONTENTS 数据组件承载。本类提供与旧
 * PotionUtils 等价的读取/写入入口，调用点无需关心 Holder 包装。
 */
public final class PotionCompat {
    private PotionCompat() {
    }

    /** 等价旧 PotionUtils.getPotion(ItemStack)：返回药水本体，无药水返回 null。 */
    public static Potion of(ItemStack stack) {
        Holder<Potion> h = holderOf(stack);
        return h == null ? null : h.value();
    }

    /** 药水 Holder（组件读写用），无药水返回 null。 */
    public static Holder<Potion> holderOf(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion().orElse(null);
    }

    /** 等价旧 PotionUtils.setPotion(ItemStack, Potion)。 */
    public static void setPotion(ItemStack stack, Potion potion) {
        stack.set(DataComponents.POTION_CONTENTS,
                new PotionContents(net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder(potion)));
    }

    /** 等价旧 PotionUtils.getMobEffects(ItemStack)：药水自带效果 + 自定义效果。 */
    public static java.util.List<MobEffectInstance> allEffects(ItemStack stack) {
        java.util.List<MobEffectInstance> out = new java.util.ArrayList<>();
        for (MobEffectInstance e : stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .getAllEffects()) {
            out.add(e);
        }
        return out;
    }

    /** 等价旧 PotionUtils.getCustomEffects(ItemStack)。 */
    public static java.util.List<MobEffectInstance> customEffects(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).customEffects();
    }
}