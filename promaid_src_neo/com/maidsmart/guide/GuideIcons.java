package com.maidsmart.guide;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Promaid 详细介绍（手册）里**画物品图标**用的注册表查询。手册一直是纯文字渲染器
 * （{@code GuideScreen} 只画字符串），这里先补上"把一个物品 id 变成能画的 {@link ItemStack}"这一层，
 * 再接上行内小图标与整块合成表。
 *
 * 【为什么用物品而不是 png】本模组没有 GUI 贴图资源、也不便让使用者自己塞图；而**物品图标是原版现成
 * 的**（任何模组物品都能按 id 画出来），所以"配图"= 画物品图标，零资源文件。
 * 【注册表与解析】物品注册表走 {@code ForgeRegistries.ITEMS} / NeoForge 对应入口（本树一律避开
 * {@code BuiltInRegistries.ITEM}）；1.20.1 没有 {@code ResourceLocation.tryParse}，按
 * {@code MaidBdCompat.tryParseRl} 的做法手拆命名空间。
 * 【健壮】认不出 id 时返回 {@link ItemStack#EMPTY}（不抛异常），调用方跳过绘制；结果带缓存。
 */
public final class GuideIcons {

    /** id → 物品栈（空栈 = 认不出，也缓存，避免每次重查）。 */
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private GuideIcons() {
    }

    /**
     * 物品 id（{@code ns:path}，省略命名空间按 {@code minecraft:}）→ 物品栈。
     * 认不出/解析失败 → {@link ItemStack#EMPTY}。
     */
    public static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack hit = CACHE.get(id);
        if (hit != null) {
            return hit;
        }
        ItemStack out = ItemStack.EMPTY;
        try {
            String full = id.indexOf(':') < 0 ? "minecraft:" + id : id;
            ResourceLocation rl = ResourceLocation.tryParse(full);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                out = new ItemStack(BuiltInRegistries.ITEM.get(rl));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GuideIcons", ignored);
            // 认不出就是空栈，绝不外溢
        }
        CACHE.put(id, out);
        return out;
    }

    /** 显示名（认不出时回退 id 本身的尾段）。给合成表旁边的文字标签用。 */
    public static String name(String id) {
        ItemStack s = stack(id);
        if (s.isEmpty()) {
            int c = id == null ? -1 : id.indexOf(':');
            return c >= 0 && c + 1 < id.length() ? id.substring(c + 1) : String.valueOf(id);
        }
        try {
            return s.getHoverName().getString();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("GuideIcons", ignored);
            return id;
        }
    }

    /** 可放进 {@code Component.literal} 的显示名组件（保留物品名自带的颜色）。 */
    public static Component nameComponent(String id) {
        return Component.literal(name(id));
    }
}
