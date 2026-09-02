package com.maidsmart.brew;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 女仆药剂手册配置（v1.1.0 实测二百七十七）——每女仆持久化在 persistentData
 * NBT 键 maid_smart_brew_config 下，由「女仆药剂手册」物品右键女仆打开的 GUI 编辑，
 * MaidBrewBehavior 按此配置执行酿造。
 *
 * 两种模式：
 * - 批量酿造（mode=0）：通用模板——背包里有什么正向材料就酿什么，按配置的
 *   强化路线（红石/萤石）与成品形态（饮用/喷溅/滞留）统一处理。
 * - 定向酿造（mode=1）：只按目标药水的配方链精确下料（BrewRecipeResolver 反推），
 *   缺料停止下料等待（半成品留在酿造台，补料后自动继续），不换材料凑合。
 *
 * 默认配置 = 批量 + 无强化 + 饮用，等价旧版 brewAuto=true 的"有什么酿什么"行为；
 * 配置总闸仍是 MaidSmartConfig.MISC_BREW_AUTO（false = 完全禁用自动下料，只维持）。
 */
public final class BrewConfig {
    public static final String TAG = "maid_smart_brew_config";

    /** 批量酿造 */
    public static final int MODE_BATCH = 0;
    /** 定向酿造 */
    public static final int MODE_TARGETED = 1;

    /** 无强化 */
    public static final int ENHANCE_NONE = 0;
    /** 红石延长 */
    public static final int ENHANCE_REDSTONE = 1;
    /** 萤石强化 */
    public static final int ENHANCE_GLOWSTONE = 2;

    /** 饮用 */
    public static final int FORM_DRINK = 0;
    /** 喷溅 */
    public static final int FORM_SPLASH = 1;
    /** 滞留 */
    public static final int FORM_LINGERING = 2;

    public int mode = MODE_BATCH;
    public int enhance = ENHANCE_NONE;
    public int form = FORM_DRINK;
    /** 定向模式目标药水（注册表 key，如 minecraft:healing）；空 = 未选 */
    public String targetPotion = "";

    public static BrewConfig load(EntityMaid maid) {
        BrewConfig cfg = new BrewConfig();
        try {
            CompoundTag tag = maid.getPersistentData().m_128469_(TAG);
            if (tag == null) {
                return cfg;
            }
            cfg.mode = clamp(tag.m_128451_("mode"), MODE_BATCH, MODE_TARGETED, MODE_BATCH);
            cfg.enhance = clamp(tag.m_128451_("enhance"), ENHANCE_NONE, ENHANCE_GLOWSTONE, ENHANCE_NONE);
            cfg.form = clamp(tag.m_128451_("form"), FORM_DRINK, FORM_LINGERING, FORM_DRINK);
            cfg.targetPotion = tag.m_128461_("targetPotion");
            if (cfg.targetPotion == null) {
                cfg.targetPotion = "";
            }
        } catch (Throwable ignored) {
            // 配置损坏回退默认——绝不影响女仆
        }
        return cfg;
    }

    public static void save(EntityMaid maid, BrewConfig cfg) {
        try {
            CompoundTag tag = new CompoundTag();
            tag.m_128405_("mode", cfg.mode);
            tag.m_128405_("enhance", cfg.enhance);
            tag.m_128405_("form", cfg.form);
            tag.m_128359_("targetPotion", cfg.targetPotion == null ? "" : cfg.targetPotion);
            maid.getPersistentData().m_128365_(TAG, tag);
        } catch (Throwable ignored) {
        }
    }

    /** 目标药水是否有效（注册表里存在） */
    public boolean hasValidTarget() {
        if (targetPotion == null || targetPotion.isEmpty()) {
            return false;
        }
        try {
            return net.minecraftforge.registries.ForgeRegistries.POTIONS
                    .getValue(ResourceLocation.parse(targetPotion)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int clamp(int v, int min, int max, int def) {
        return v >= min && v <= max ? v : def;
    }
}
