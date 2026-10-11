/*
 * Promaid —— 物品名（配置面板悬停用）。面板里物品名行原来自 {@link com.maidsmart.build.BlueprintLib#cnName}
 * ——那是**手写词表**，只覆盖原版/常见模组物品；模组食物（如 kaleidoscope_cookery:stargazy_pie）译不出，
 * 显示成英文物品路径，中文玩家看到整页英文。这里改用**原版语言表**取名字（就是背包里看到的名字）：
 * {@link #name} = 当前语言下的显示名（悬停第一行）；{@link #id} = 注册名（悬停第二行的「英文字段」，
 * 也可直接填进白名单）。都不含 § 颜色码（调用方自行加前缀色）；任何取不到名字的异常一律回退（名字→id→
 * 空串），绝不让 GUI 崩。
 */
package com.maidsmart.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public final class MaidItemNames {

    private MaidItemNames() {
    }

    /**
     * 物品显示名（**跟随客户端语言**）：中文客户端=中文名，英文客户端=英文名。
     * 取不到名字回退物品 id，id 也取不到才回退空串。
     */
    public static String name(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return "";
        }
        try {
            String n = stack.m_41786_().getString();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidItemNames", ignored);
        }
        return id(stack);
    }

    /** 物品注册名（minecraft:golden_apple）；取不到回退空串 */
    public static String id(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return "";
        }
        try {
            ResourceLocation k = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return k == null ? "" : k.toString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidItemNames", ignored);
            return "";
        }
    }
}
