package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 枪械兼容工具（v1.1.0）——TACZ（永恒枪械工坊：零）与 卓越前线（Superb Warfare）双枪械模组。
 *
 * 判定优先走 TLM 内置兼容层（GunCommonUtil.isGun——TLM 用反射同时兼容两家，
 * 未装枪械 mod 时安全返回 false），注册名兜底（TLM 兼容层异常/未初始化时仍可判定）：
 * - TACZ：枪 = tacz:modern_kinetic_gun（NBT GunId 区分枪型）；弹药 = tacz:ammo（NBT AmmoId）
 *   与 tacz:ammo_box 弹药箱（NBT AmmoId/AmmoCount，见 taczBoxApi 注释）
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

    /* ---------------- TACZ 弹药箱（实测五百六十八） ----------------
     *
     * 【为什么必须认弹药箱】TACZ 玩家后勤的主流形态不是散装弹药而是弹药箱
     * （tacz:ammo_box，一箱 1-2 组的 AmmoId/AmmoCount）。反编译实证（tacz 1.1.8-hotfix，
     * 1.20.1/1.21.1 两版 API 逐签名对上）：TACZ 自己的换弹链路**原生支持弹药箱**——
     * AbstractGunItem.canReload / findAndExtractInventoryAmmo 扫
     * shooter 的 ITEM_HANDLER capability（女仆 = 护甲+双手+背包+饰品
     * 四合一，EntityMaid.getCapability 反编译实证），按 IAmmoBox.isAmmoBoxOfGun 认箱、
     * 扣 AmmoCount、打空清 AmmoId。TLM 侧 TacInnerCompat.performGunAttack 收到
     * ShootResult.NO_AMMO 时只管调 gunOperator.reload()，喂弹全交给 TACZ。
     * 所以**消耗侧零工作量**——坏的只有本类 hasGunAndAmmo 这道判定门：旧版只数散装
     * tacz:ammo，箱装弹药被当"没子弹"→ 空袭弹门禁不过（气泡报缺弹药、退普通模式）+
     * AutoCombatSwitch 枪械弹药闸不过（gun_attack 不进池、普通模式也不开枪），
     * 正是粉丝反馈的整条链。
     *
     * 【判据与 TACZ canReload 同口径】箱里有弹 = isAmmoBoxOfGun(gun, box)
     * （全类型创造箱恒 true；AmmoId 为空 false；弹药型号必须对上枪的 ammoId）
     * 且 getAmmoCount(box) > 0（创造箱/全类型创造箱读作 Integer.MAX_VALUE，天然通过；
     * 普通箱打空 0 → 不算）。比散装弹药那条"任意 tacz:ammo 都算"的宽松判据更严：
     * 箱对不上枪就不计数，绝不出现"判定有弹却永远装填不上"的死角。
     *
     * 【为什么走反射】TACZ 是可选兼容 mod，不在编译类路径上（TLM 自家 GunCommonUtil、
     * 以及本 mod 对 Sable/暮色森林/法术层的既有软兼容全是同一做法）。IAmmoBox 全是
     * 公开接口方法，1.1.8 两版签名一致，Method 惰性解析一次后复用。
     */
    /** 惰性反射句柄：[0]=IAmmoBox 类，[1]=isAmmoBoxOfGun(ItemStack,ItemStack)，[2]=getAmmoCount(ItemStack)；
     *  空数组 = TACZ 不在场/解析失败（永远判 false，行为与旧版一致） */
    private static volatile Object[] TACZ_BOX_API;
    private static final Object TACZ_BOX_LOCK = new Object();

    private static Object[] taczBoxApi() {
        Object[] api = TACZ_BOX_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_BOX_LOCK) {
            if (TACZ_BOX_API == null) {
                try {
                    Class<?> cls = Class.forName("com.tacz.guns.api.item.IAmmoBox");
                    TACZ_BOX_API = new Object[]{
                            cls,
                            cls.getMethod("isAmmoBoxOfGun", ItemStack.class, ItemStack.class),
                            cls.getMethod("getAmmoCount", ItemStack.class)};
                } catch (Throwable ignored) {
                    TACZ_BOX_API = new Object[0];
                }
            }
            return TACZ_BOX_API;
        }
    }

    /** 该物品是否 TACZ 弹药箱（实现 IAmmoBox 接口；TACZ 不在场恒 false） */
    public static boolean isAmmoBox(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Object[] api = taczBoxApi();
        if (api.length == 0) {
            return false;
        }
        try {
            return ((Class<?>) api[0]).isInstance(stack.getItem());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 弹药箱里是否有"这把枪打得响"的弹药（isAmmoBoxOfGun + getAmmoCount > 0，同上口径） */
    public static boolean boxHasAmmoFor(ItemStack gun, ItemStack box) {
        Object[] api = taczBoxApi();
        if (api.length == 0 || gun == null || gun.isEmpty() || box == null || box.isEmpty()) {
            return false;
        }
        try {
            Object boxItem = ((Class<?>) api[0]).cast(box.getItem());
            if (!(Boolean) ((java.lang.reflect.Method) api[1]).invoke(boxItem, gun, box)) {
                return false;
            }
            return (Integer) ((java.lang.reflect.Method) api[2]).invoke(boxItem, box) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否装了任一枪械模组（主动切战斗的枪械优先分支用——没装就别浪费背包扫描） */
    public static boolean anyGunModLoaded() {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("tacz")) {
                return true;
            }
            return net.neoforged.fml.ModList.get().isLoaded("superbwarfare");
        } catch (Exception e) {
            return false;
        }
    }

    /** 该物品栈是否为枪（TACZ/卓越前线） */
    public static boolean isGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
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
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && key.toString().equals(TACZ_GUN_ID);
    }

    /** 该物品栈是否为弹药（TACZ:ammo 带 AmmoId；SBW:5 类） */
    public static boolean isAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String id = key.toString();
        if (id.equals(TACZ_AMMO_ID)) {
            return true; // TACZ 弹药类型在 NBT，物品唯一
        }
        return SBW_NS.equals(key.getNamespace()) && SBW_AMMO_PATHS.contains(key.getPath());
    }

    /**
     * 女仆是否"有枪可用"——背包或主手有枪且弹药可用（枪械优先切战斗的判定）。
     * 换弹由 TLM gun_attack 任务自动处理（TacInnerCompat 收到 NO_AMMO 调 gunOperator.reload()，
     * 喂弹走 TACZ 自己的 findAndExtractInventoryAmmo），这里只确认"有枪 + 有子弹"。
     * v1.1.0 终审二：卓越前线能量武器（二次灾变/超级星星炮）不消耗常规弹药
     * （内部 ForgeEnergy 充能）——持有它们时跳过弹药检查直接算可用；此前要求
     * "枪+弹药"导致女仆拿着二次灾变却判定"没子弹"不切枪械模式。
     * v1.2.0 实测五百六十八：TACZ **弹药箱**计入弹药——但必须箱内弹药对得上她身上
     * 某把枪（isAmmoBoxOfGun）且箱里有数（创造箱恒有），见 taczBoxApi 注释。
     */
    public static boolean hasGunAndAmmo(EntityMaid maid) {
        if (!anyGunModLoaded()) {
            return false;
        }
        boolean hasGun = false;
        boolean hasAmmo = false;
        boolean hasEnergyGun = false;
        java.util.List<ItemStack> guns = new java.util.ArrayList<>();
        java.util.List<ItemStack> boxes = new java.util.ArrayList<>();
        try {
            ItemStack main = maid.getMainHandItem();
            if (isGun(main)) {
                hasGun = true;
                hasEnergyGun |= isEnergyGun(main);
                guns.add(main);
            }
            // 主手拿的就是弹药/弹药箱（箱子占了武器位时枪在背包——照 TACZ canReload
            // 扫全背包的口径，手上的弹药同样有效）
            if (isAmmo(main)) {
                hasAmmo = true;
            } else if (isAmmoBox(main)) {
                boxes.add(main);
            }
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty()) {
                    continue;
                }
                if (!hasGun && isGun(s)) {
                    hasGun = true;
                    hasEnergyGun |= isEnergyGun(s);
                }
                if (isGun(s)) {
                    guns.add(s);
                }
                if (!hasAmmo && isAmmo(s)) {
                    hasAmmo = true;
                }
                // 有散装弹药就不用再收箱（箱只在散装缺位时才参与判定）
                if (!hasAmmo && isAmmoBox(s)) {
                    boxes.add(s);
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        if (!hasGun) {
            return false;
        }
        if (hasAmmo || hasEnergyGun) {
            return true;
        }
        // 实测五百六十八：散装弹药缺位 → 弹药箱兜底（型号对上枪 + 箱里有数）
        for (ItemStack box : boxes) {
            for (ItemStack gun : guns) {
                if (boxHasAmmoFor(gun, box)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 卓越前线能量武器（不吃常规弹药——内部充能）：按注册名判定 */
    public static boolean isEnergyGun(ItemStack stack) {
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && SBW_NS.equals(key.getNamespace())
                && SBW_ENERGY_GUN_PATHS.contains(key.getPath());
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
