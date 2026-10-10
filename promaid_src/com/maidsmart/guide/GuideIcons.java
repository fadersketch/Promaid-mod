package com.maidsmart.guide;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.6.0 实测八百一十三：Promaid 详细介绍（手册）里**画物品图标**用的注册表查询。
 *
 * <p>——本模组的手册
 * 一直是**纯文字**渲染器（{@code GuideScreen} 只画字符串），所以这里先补上"把一个
 * 物品 id 变成能画的 {@link ItemStack}"这一层，再在 {@code GuideScreen} 里接上
 * 行内小图标（{@code [[i:物品id|说明文字]]}）与整块的合成表（{@code [[#...]]}）。
 *
 * <p>【为什么用物品而不是 png 图片】本模组没有 GUI 贴图资源，也不方便让使用者自己塞图；
 * 而**物品图标是原版现成的**——任何模组物品都能按 id 画出来（原版 {@code getRenderItem}
 * 会调物品自己的 model/json），所以「配图」= 画物品图标，零资源文件。
 *
 * <p>【1.20.1 差异】物品注册表用 {@code ForgeRegistries.ITEMS}（本树一律避开
 * {@code BuiltInRegistries.ITEM}，见 {@code MaidBdCompat} 的同类注释）；
 * 且 1.20.1 **没有** {@code ResourceLocation.tryParse}，按 {@code MaidBdCompat.tryParseRl}
 * 的做法手拆命名空间。
 *
 * <p>认不出 id 时返回 {@link ItemStack#EMPTY}（不抛异常），调用方跳过绘制即可——
 * 手册绝不能因为写错一个 id 就崩界面。结果带缓存（每帧都要画）。
 */
public final class GuideIcons {

    /** id → 物品栈（空栈 = 认不出，也缓存，避免每次重查）。 */
    private static final Map<String, ItemStack> CACHE = new HashMap<>();

    private GuideIcons() {
    }

    /**
     * 物品 id（{@code ns:path}，省略命名空间按 {@code minecraft:}）→ 物品栈。
     * 认不出/解析失败 → {@link ItemStack#m_41619_() 空栈}。
     */
    public static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.f_41583_;
        }
        ItemStack hit = CACHE.get(id);
        if (hit != null) {
            return hit;
        }
        ItemStack out = ItemStack.f_41583_;
        try {
            ResourceLocation rl = tryParseRl(id);
            if (rl != null && net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(rl)) {
                net.minecraft.world.item.Item it =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
                out = it == null ? ItemStack.f_41583_ : new ItemStack(it);
            }
        } catch (Throwable ignored) {
            // 认不出就是空栈，绝不外溢
        }
        CACHE.put(id, out);
        return out;
    }

    /** 1.20.1 手拆命名空间（本树没有 {@code ResourceLocation.tryParse}）。 */
    private static ResourceLocation tryParseRl(String s) {
        try {
            String full = s.indexOf(':') < 0 ? "minecraft:" + s : s;
            int c = full.indexOf(':');
            String ns = c <= 0 ? "" : full.substring(0, c);
            String path = c < 0 ? full : full.substring(c + 1);
            if (ns.isEmpty() || path.isEmpty()) {
                return null;
            }
            return new ResourceLocation(ns, path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 显示名（认不出时回退 id 本身的尾段）。给合成表旁边的文字标签用。 */
    public static String name(String id) {
        ItemStack s = stack(id);
        if (s.m_41619_()) {
            int c = id == null ? -1 : id.indexOf(':');
            return c >= 0 && c + 1 < id.length() ? id.substring(c + 1) : String.valueOf(id);
        }
        try {
            return s.m_41786_().getString();
        } catch (Throwable ignored) {
            return id;
        }
    }

    /** 可放进 {@code Component.m_237113_} 的显示名组件（保留物品名自带的颜色）。 */
    public static Component nameComponent(String id) {
        return Component.m_237113_(name(id));
    }
}
