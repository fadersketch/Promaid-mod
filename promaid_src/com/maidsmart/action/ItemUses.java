package com.maidsmart.action;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * v1.2.2 实测五百八十【"剩余次数"变体键】：把"同一个物品、剩余次数不同"当成两个条目。
 *
 * ── 需求原话 ──
 * "喂水也有这样的风险，尤其是那种可以喂好几次的食物和水。如果可以细分的话最好在勾选面板
 *  里面都给它做出来。也就是说能够喝 4/4 次的和喝 3/4 次的在面板里面属于两种不同的物品。
 *  这样子可以减少很多麻烦。"
 *
 * ── 为什么需要 ──
 * 像水壶 / 多次使用的容器这类物品，剩余次数不同、用途完全不同：给主人喂水当然优先喂满的，
 * 而"只剩 1 次的破壶"最好别再占喂食优先级。旧版勾选面板与白/黑名单**只按物品 id 记账**，
 * "4/4 次"和"3/4 次"共用同一个开关，玩家没法只允许其中一种。
 *
 * ── 键格式 ──
 * <ul>
 *   <li>非耐久物品：{@code 命名空间:id}（与旧配置完全一致）；</li>
 *   <li>耐久物品且最大耐久 ≤ {@link #MAX_VARIANTS}：{@code 命名空间:id#剩余次数}——
 *       "剩余次数" = 最大耐久 − 已损耗（`getMaxDamage − getDamageValue`），
 *       所以 4 次的水壶用掉一次就是 {@code ...:canteen#3}；</li>
 *   <li>最大耐久 &gt; {@link #MAX_VARIANTS} 的物品不细分（否则网格里一个物品就是几百行），
 *       仍旧只有裸 id 一行。</li>
 * </ul>
 *
 * ── 向后兼容 ──
 * 裸 id 条目仍按"**任意剩余次数**"匹配（老配置、以及手动输入框里敲的 id 都照旧生效）；
 * 细分只是多给玩家一个"只认某几档"的选择，不改变原有条目的语义。
 */
public final class ItemUses {

    /** 最多细分成几档（超过就不细分，防网格爆炸） */
    public static final int MAX_VARIANTS = 16;

    /** 变体分隔符（配置项里 {@code id#剩余次数}） */
    public static final char SEP = '#';

    private ItemUses() {
    }

    /** 剩余次数（耐久物品且在细分级距内）；不适用返回 -1 */
    public static int remaining(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_() || !stack.m_41763_()) {
                return -1;
            }
            int max = stack.m_41776_();
            if (max <= 0 || max > MAX_VARIANTS) {
                return -1;
            }
            return Math.max(0, max - stack.m_41773_());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 总次数（与 {@link #remaining} 同口径）；不适用返回 -1 */
    public static int total(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_() || !stack.m_41763_()) {
                return -1;
            }
            int max = stack.m_41776_();
            return (max <= 0 || max > MAX_VARIANTS) ? -1 : max;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 是否细分（有剩余次数概念） */
    public static boolean variant(ItemStack stack) {
        return remaining(stack) >= 0;
    }

    /** 物品的裸 id（不带 #剩余次数） */
    public static String baseIdOf(ItemStack stack) {
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return rl == null ? "" : rl.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 去掉 {@code #剩余次数} 后缀（对没有后缀的键原样返回） */
    public static String baseId(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf(SEP);
        return i < 0 ? key : key.substring(0, i);
    }

    /** 从键里取剩余次数；无后缀 / 非法返回 -1 */
    public static int usesOfKey(String key) {
        if (key == null) {
            return -1;
        }
        int i = key.indexOf(SEP);
        if (i < 0 || i >= key.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(i + 1).trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 变体键（配置项里存的就是它） */
    public static String key(ItemStack stack) {
        String base = baseIdOf(stack);
        int rem = remaining(stack);
        return rem >= 0 ? base + SEP + rem : base;
    }

    /**
     * 造一个"剩余 remaining 次"的栈（只用于面板显示：耐久条会跟着变化，
     * 这样"4/4 次"和"3/4 次"两行一眼能看出差别）。
     */
    public static ItemStack stackFor(Item item, int remaining) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        try {
            if (stack.m_41763_()) {
                int max = stack.m_41776_();
                if (max > 0) {
                    stack.m_41721_(Math.max(0, max - remaining));
                }
            }
        } catch (Throwable ignored) {
        }
        return stack;
    }

    /**
     * 配置项键是否匹配该栈：id 相同，且（键无 {@code #} 后缀 = 任意剩余次数，或剩余次数一致）。
     */
    public static boolean matches(String entry, ItemStack stack) {
        if (entry == null || stack == null || stack.m_41619_()) {
            return false;
        }
        String e = entry.trim();
        if (e.isEmpty()) {
            return false;
        }
        if (!baseIdOf(stack).equals(baseId(e))) {
            return false; // 物品都不同 → 直接不匹配
        }
        int want = usesOfKey(e);
        return want < 0 || remaining(stack) == want;
    }

    /** "剩余 3/4 次"；不适用返回空串（给悬停信息/列表行用） */
    public static String label(ItemStack stack) {
        int rem = remaining(stack);
        if (rem < 0) {
            return "";
        }
        return "剩余 " + rem + "/" + total(stack) + " 次";
    }

    /** 同上，但输入是配置项键（面板底部列表用；无后缀返回空串） */
    public static String labelOfKey(String key) {
        int rem = usesOfKey(key);
        if (rem < 0) {
            return "";
        }
        String base = baseId(key);
        try {
            net.minecraft.world.item.Item it = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation(base));
            if (it != null) {
                int max = new net.minecraft.world.item.ItemStack(it).m_41776_();
                if (max > 0) {
                    return "剩余 " + rem + "/" + max + " 次";
                }
            }
        } catch (Throwable ignored) {
        }
        return "剩余 " + rem + " 次";
    }
}
