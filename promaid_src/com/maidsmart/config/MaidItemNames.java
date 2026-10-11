/*
 * Promaid —— 物品名（配置面板悬停用）。
 *
 * <p>。
 *
 * <p>面板里原本的物品名行来自 {@link com.maidsmart.build.BlueprintLib#cnName}——那是**手写词表**，
 * 只覆盖原版/常见模组物品。模组食物（如 kaleidoscope_cookery:stargazy_pie）译不出，
 * 于是那一行显示的是英文的**物品路径**（stargazy_pie），中文玩家看到的整页都是英文。
 *
 * <p>这里改用**原版语言表**取名字，拿到的就是玩家在背包里看到的那个名字：
 *
 * <ul>
 *   <li>{@link #name}——玩家当前语言下的显示名：中文客户端给中文名（「仰望星空派」「金苹果」），
 *       英文客户端给英文名。悬停框第一行用它。</li>
 *   <li>{@link #id}——物品注册名（如 minecraft:golden_apple）。悬停框第二行的「英文字段」用它；
 *       它同时也是可直接手填进白名单输入框的准确标识，一举两得。</li>
 * </ul>
 *
 * <p>都不含 § 颜色码，调用方自行加前缀色。任何取不到名字的异常一律回退（名字→id→空串），
 * 绝不让 GUI 崩。
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
