package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 枪械兼容工具（v1.1.0）——TACZ（永恒枪械工坊：零）与 卓越前线（Superb Warfare）双枪械模组。
 *
 * 判定优先走 TLM 内置兼容层（GunCommonUtil.isGun——TLM 用反射同时兼容两家，
 * 未装枪械 mod 时安全返回 false），注册名兜底（TLM 兼容层异常/未初始化时仍可判定）：
 * - TACZ：枪 = tacz:modern_kinetic_gun（NBT GunId 区分枪型）；弹药 = tacz:ammo（NBT AmmoId）
 * - 卓越前线：每枪一个注册物品（superbwarfare 命名空间）；弹药 = 5 类 *_ammo
 *
 * 理想射程基准读 TLM 枪械三段距离配置（MAID_GUN_MEDIUM_DISTANCE，中距离）——
 * 与 TLM 自家 gun_attack 任务的走位配置同源。
 */
public final class GunCompat {
    private GunCompat() {
    }

    /** TACZ 枪物品注册名（全 mod 唯一枪物品，枪型在 NBT GunId） */
    private static final String TACZ_GUN_ID = "tacz:modern_kinetic_gun";
    /** TACZ 弹药物品注册名（类型在 NBT AmmoId） */
    private static final String TACZ_AMMO_ID = "tacz:ammo";
    /** 卓越前线命名空间 */
    private static final String SBW_NS = "superbwarfare";
    /** 卓越前线 5 类弹药（path 名） */
    private static final java.util.Set<String> SBW_AMMO_PATHS = java.util.Set.of(
            "handgun_ammo", "rifle_ammo", "sniper_ammo", "shotgun_ammo", "heavy_ammo");
    /** 卓越前线能量武器（二次灾变等——充能即用，不吃上面 5 类常规弹药；javap 实证
     *  SecondaryCataclysmItem 的射击走 ForgeEnergy IEnergyStorage，TLM 的 doGunReload
     *  对它只查 shouldStartReloading/startBolt（能量充能），弹药判定对它们恒空） */
    private static final java.util.Set<String> SBW_ENERGY_GUN_PATHS = java.util.Set.of(
            "secondary_cataclysm", "super_star_shooter");

    /** 是否装了任一枪械模组（主动切战斗的枪械优先分支用——没装就别浪费背包扫描） */
    public static boolean anyGunModLoaded() {
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("tacz")) {
                return true;
            }
            return net.minecraftforge.fml.ModList.get().isLoaded("superbwarfare");
        } catch (Exception e) {
            return false;
        }
    }

    /** 该物品栈是否为枪（TACZ/卓越前线） */
    public static boolean isGun(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        // 首选：TLM 兼容层（反射双枪械 mod，未装安全返回 false）
        try {
            if (com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil.isGun(stack)) {
                return true;
            }
        } catch (Throwable ignored) {
            // TLM 兼容层异常（版本变化/未初始化）→ 走注册名兜底
        }
        return isGunById(stack);
    }

    /** 注册名兜底判定：TACZ 唯一枪物品（SBW 每枪一个物品且命名无规律，只走 TLM 兼容层） */
    private static boolean isGunById(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && key.toString().equals(TACZ_GUN_ID);
    }

    /** 该物品栈是否为弹药（TACZ:ammo 带 AmmoId；SBW:5 类） */
    public static boolean isAmmo(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        if (key == null) {
            return false;
        }
        String id = key.toString();
        if (id.equals(TACZ_AMMO_ID)) {
            return true; // TACZ 弹药类型在 NBT，物品唯一
        }
        return SBW_NS.equals(key.m_135827_()) && SBW_AMMO_PATHS.contains(key.m_135815_());
    }

    /**
     * 女仆是否"有枪可用"——背包或主手有枪且背包有任意弹药（枪械优先切战斗的判定）。
     * 换弹由 TLM gun_attack 任务自动处理（TacInnerCompat 自动搜背包同型弹药装填），
     * 这里只确认"有枪 + 有子弹"。
     * v1.1.0 终审二：卓越前线能量武器（二次灾变/超级星星炮）不消耗常规弹药
     * （内部 ForgeEnergy 充能）——持有它们时跳过弹药检查直接算可用；此前要求
     * "枪+弹药"导致女仆拿着二次灾变却判定"没子弹"不切枪械模式。
     */
    public static boolean hasGunAndAmmo(EntityMaid maid) {
        if (!anyGunModLoaded()) {
            return false;
        }
        boolean hasGun = false;
        boolean hasAmmo = false;
        boolean hasEnergyGun = false;
        try {
            ItemStack main = maid.m_21205_();
            if (isGun(main)) {
                hasGun = true;
                hasEnergyGun |= isEnergyGun(main);
            }
            net.minecraftforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots() && !(hasGun && (hasAmmo || hasEnergyGun)); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_()) {
                    continue;
                }
                if (!hasGun && isGun(s)) {
                    hasGun = true;
                    hasEnergyGun |= isEnergyGun(s);
                }
                if (!hasAmmo && isAmmo(s)) {
                    hasAmmo = true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return hasGun && (hasAmmo || hasEnergyGun);
    }

    /** 卓越前线能量武器（不吃常规弹药——内部充能）：按注册名判定 */
    public static boolean isEnergyGun(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && SBW_NS.equals(key.m_135827_())
                && SBW_ENERGY_GUN_PATHS.contains(key.m_135815_());
    }

    /**
     * 枪械理想射程基准（格）——读 TLM 枪械中距离配置（与 TLM gun_attack 任务同源），
     * 读取失败回退 12（TLM 默认中距离量级）。
     */
    public static double gunMaxRange() {
        try {
            return com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig
                    .MAID_GUN_MEDIUM_DISTANCE.get();
        } catch (Throwable ignored) {
            return 12.0;
        }
    }
}
