package com.maidsmart.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 神秘遗物（Enigmatic Legacy）进阶药水软联动。
 * <p>它的药水不是原版 {@code PotionItem}：进阶药水把药水 id 存在 NBT
 * {@code EnigmaticPotion}（String），效果表由 {@code PotionHelper.getEffects} 按 id 从
 * {@code EnigmaticPotions.ULTIMATE_POTIONS / COMMON_POTIONS} 里取。所以本模组原有的
 * 「instanceof PotionItem + PotionUtils.getPotion」两路口径都认不出它们。
 * <p>全部调用点都先过 {@link #isLoaded()}（{@code ModList} 在场判断），没装神秘遗物时
 * 就是恒假判断，对原版药水与不装它的存档零影响；反射只做一次方法/字段探针。
 */
public final class EnigmaticLegacyCompat {

    private static final String MOD_ID = "enigmaticlegacy";
    private static final String NBT_KEY = "EnigmaticPotion";
    private static final List<String> ULTIMATE_IDS = new ArrayList<>();
    private static final List<String> COMMON_IDS = new ArrayList<>();

    private static boolean initTried;
    private static boolean initOk;
    private static java.lang.reflect.Method mGetEffects;
    private static java.lang.reflect.Method mGetId;

    private EnigmaticLegacyCompat() {
    }

    private static synchronized void ensureInit() {
        if (initTried) {
            return;
        }
        initTried = true;
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID)) {
                return;
            }
            Class<?> helper = Class.forName("com.aizistral.enigmaticlegacy.helpers.PotionHelper");
            mGetEffects = helper.getMethod("getEffects", ItemStack.class);
            Class<?> potions = Class.forName("com.aizistral.enigmaticlegacy.registries.EnigmaticPotions");
            readIds(potions.getField("ULTIMATE_POTIONS").get(null), ULTIMATE_IDS);
            readIds(potions.getField("COMMON_POTIONS").get(null), COMMON_IDS);
            initOk = mGetEffects != null && !ULTIMATE_IDS.isEmpty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
        }
    }

    /** 静态列表里的 {@code AdvancedPotion} → 药水 id（{@code getId()} 反射一次取方法） */
    private static void readIds(Object listObj, List<String> out) throws Exception {
        if (!(listObj instanceof List<?>)) {
            return;
        }
        for (Object o : (List<?>) listObj) {
            if (o == null) {
                continue;
            }
            if (mGetId == null) {
                mGetId = o.getClass().getMethod("getId");
            }
            Object id = mGetId.invoke(o);
            if (id instanceof String && !out.contains(id)) {
                out.add((String) id);
            }
        }
    }

    /** 神秘遗物在场且反射探针成功 */
    public static boolean isLoaded() {
        ensureInit();
        return initOk;
    }

    /** 该栈是否是进阶药水（带 {@code EnigmaticPotion} 字符串 NBT） */
    public static boolean isAdvancedPotion(ItemStack s) {
        ensureInit();
        if (!initOk || s == null || s.m_41619_()) {
            return false;
        }
        try {
            CompoundTag tag = s.m_41783_();
            return tag != null && tag.m_128425_(NBT_KEY, 8); // 8 = TAG_STRING
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
            return false;
        }
    }

    /** 进阶药水 id（{@code ultimate_healing} 这类）；不是进阶药水返回 null */
    public static String advancedId(ItemStack s) {
        if (!isAdvancedPotion(s)) {
            return null;
        }
        try {
            return s.m_41783_().m_128461_(NBT_KEY);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
            return null;
        }
    }

    /** 该栈的进阶药水效果（注册表里的效果表，按 id 解析）；不是进阶药水返回空表 */
    public static List<MobEffectInstance> effects(ItemStack s) {
        List<MobEffectInstance> out = new ArrayList<>();
        if (!isAdvancedPotion(s) || mGetEffects == null) {
            return out;
        }
        try {
            Object r = mGetEffects.invoke(null, s);
            if (r instanceof List<?>) {
                for (Object o : (List<?>) r) {
                    if (o instanceof MobEffectInstance) {
                        out.add((MobEffectInstance) o);
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
        }
        return out;
    }

    /** 把进阶药水 id 写进该栈的 NBT（面板重建候选栈用） */
    public static void setAdvanced(ItemStack s, String id) {
        try {
            s.m_41784_().m_128359_(NBT_KEY, id);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
        }
    }

    /** 按 id 还原：id 在注册表里才写，返回是否成功（面板解析记账键用） */
    public static boolean setAdvancedFromId(ItemStack s, String id) {
        ensureInit();
        if (!initOk || id == null || s == null || s.m_41619_()) {
            return false;
        }
        if (!ULTIMATE_IDS.contains(id) && !COMMON_IDS.contains(id)) {
            return false;
        }
        setAdvanced(s, id);
        return true;
    }

    /** 饮用形态（{@code common_potion} / {@code ultimate_potion}） */
    public static boolean isDrinkForm(ItemStack s) {
        return isForm(s, "UltimatePotionBase");
    }

    /** 喷溅形态 */
    public static boolean isSplashForm(ItemStack s) {
        return isForm(s, "UltimatePotionSplash");
    }

    /** 滞留形态 */
    public static boolean isLingeringForm(ItemStack s) {
        return isForm(s, "UltimatePotionLingering");
    }

    private static boolean isForm(ItemStack s, String simpleName) {
        if (!isAdvancedPotion(s)) {
            return false;
        }
        try {
            Class<?> c = s.m_41720_().getClass();
            return c.getName().startsWith("com.aizistral.enigmaticlegacy.")
                    && simpleName.equals(c.getSimpleName());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("EnigmaticLegacyCompat", ignored);
            return false;
        }
    }

    /** 终极药水 id 表（面板枚举用，只读） */
    public static List<String> ultimateIds() {
        ensureInit();
        return Collections.unmodifiableList(ULTIMATE_IDS);
    }

    /** 普通进阶药水 id 表（面板枚举用，只读） */
    public static List<String> commonIds() {
        ensureInit();
        return Collections.unmodifiableList(COMMON_IDS);
    }
}
