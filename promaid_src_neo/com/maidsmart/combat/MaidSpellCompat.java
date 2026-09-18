package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.List;

/**
 * v1.2.0 实测五百五十九：【车万女仆：万法皆通】法术附属的软兼容层。
 *
 * 【为什么需要它】该附属给女仆加了两个法术战斗任务
 * （`maidspell:spell_combat_melee` 近战法术 / `maidspell:spell_combat_far` 远程法术），
 * 而它们的 `isWeapon(maid, stack)` **恒返回 true**（javap 反汇编实证：方法体只有
 * `iconst_1; ireturn`）。我们所有"武器"判定都走"任务自带判据"（{@link CombatTaskCompat}），
 * 于是对这两个任务等于**万物皆武器**：
 *
 * - 自动换武器：主手空/快坏时会把背包里**最高 DPS 的冷兵器**（剑/斧）塞进法术模式的主手；
 * - 自主参战：法术任务只凭"背包里任意一件非原版物品"就进候选池（实测三百七十九的兜底）——
 *   等于带个模组食物也算"会用魔法"。
 *
 * 用户要求收紧：**必须保证女仆真的会用法术（随身带着法术装备）才开放切换，否则不进列表。**
 *
 * 【软依赖】全部走反射 + 一次性缓存：附属没装 / 版本不符 → 所有判定恒 false，
 * 调用方按"没有法术任务"处理，行为与旧版完全一致（与 {@link GunCompat} 同一套路，
 * 不给本模组增加任何前置或硬链接）。
 *
 * 【判据口径】
 * - "法术装备" = 附属自己认的法术书/法器（`ISpellBookProvider.isSpellBook`：铁魔法的法术书、
 *   新生魔艺的法器…）——法术本体就存在这些物品里（铁魔法一本法术书 = 一个法术），
 *   所以"随身有法术装备"就是"学过法术、用得出法术"。
 * - "会不会用法术" = 主手/副手/背包/饰品栏里有法术装备，或者附属自己的数据里已经有她的
 *   法术书（`MaidIronsSpellData/MaidArsNouveauSpellData.get(uuid).getSpellBooks()` 非空，
 *   覆盖附属收进"末影口袋"等特殊位置的情况）。
 */
public final class MaidSpellCompat {
    /** 附属 modId（其 mods.toml 实证：touhou_little_maid_spell） */
    private static final String MOD_ID = "touhou_little_maid_spell";
    /** 两个法术任务的 UID——注意附属的 namespace 用的是 maidspell，不是它的 modId */
    private static final ResourceLocation UID_MELEE = ResourceLocation.parse("maidspell:spell_combat_melee");
    private static final ResourceLocation UID_FAR = ResourceLocation.parse("maidspell:spell_combat_far");
    /** 类名兜底：附属换 UID / 以后新增法术任务时仍能命中（按包名前缀） */
    private static final String TASK_CLASS_PREFIX = "com.github.yimeng261.maidspell.task.SpellCombat";

    private static final String CLS_MANAGER = "com.github.yimeng261.maidspell.spell.manager.SpellBookManager";
    private static final String CLS_PROVIDER = "com.github.yimeng261.maidspell.api.ISpellBookProvider";
    private static final String CLS_DATA = "com.github.yimeng261.maidspell.api.IMaidSpellData";
    /** 附属自己的盟友判定（v1.2.2 实测五百六十：友军风免复用它，保证与附属对伤害的口径一致） */
    private static final String CLS_ALLY = "com.github.yimeng261.maidspell.compat.MaidSpellAllyResolver";
    private static final String[] DATA_CLASSES = {
            "com.github.yimeng261.maidspell.spell.data.MaidIronsSpellData",
            "com.github.yimeng261.maidspell.spell.data.MaidArsNouveauSpellData",
    };

    private static boolean resolved;
    private static boolean present;
    private static Method mLoadedMods;    // static List<String> SpellBookManager.getLoadedMods()
    private static Method mGetProvider;   // static ISpellBookProvider SpellBookManager.getProvider(String)
    private static Method mIsSpellBook;   // boolean ISpellBookProvider.isSpellBook(ItemStack)
    private static Method mGetSpellBooks; // List<ItemStack> IMaidSpellData.getSpellBooks()
    private static Method[] mDataGet = new Method[0]; // static <Data> get(UUID)
    private static Method mAreFriendly;   // static boolean MaidSpellAllyResolver.areFriendly(Entity, Entity)

    private MaidSpellCompat() {
    }

    /** 附属是否在场（不在场 → 本类所有判定恒 false） */
    public static boolean isLoaded() {
        try {
            return net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该任务是不是附属的"近战法术 / 远程法术"任务 */
    public static boolean isSpellTask(IMaidTask task) {
        if (task == null) {
            return false;
        }
        try {
            ResourceLocation uid = task.getUid();
            if (UID_MELEE.equals(uid) || UID_FAR.equals(uid)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return task.getClass().getName().startsWith(TASK_CLASS_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 女仆当前任务是不是法术任务 */
    public static boolean isSpellTask(EntityMaid maid) {
        try {
            return maid != null && isSpellTask(maid.getTask());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这件物品是不是"法术装备"（附属各 provider 认的法术书/法器）。
     * 附属没装 / 反射失败一律 false——调用方据此把它排除在切换列表外。
     */
    public static boolean isSpellWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !resolve()) {
            return false;
        }
        try {
            Object mods = mLoadedMods.invoke(null);
            if (!(mods instanceof List<?> list)) {
                return false;
            }
            for (Object id : list) {
                if (!(id instanceof String s)) {
                    continue;
                }
                Object provider = mGetProvider.invoke(null, s);
                if (provider == null) {
                    continue;
                }
                Object r = mIsSpellBook.invoke(provider, stack);
                if (r instanceof Boolean b && b) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 女仆身上（主手/副手/背包/饰品栏）有没有法术装备 */
    public static boolean hasSpellGear(EntityMaid maid) {
        if (maid == null || !isLoaded()) {
            return false;
        }
        try {
            if (isSpellWeapon(maid.getMainHandItem()) || isSpellWeapon(maid.getOffhandItem())) {
                return true;
            }
            var inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isSpellWeapon(inv.getStackInSlot(i))) {
                    return true;
                }
            }
            var bauble = maid.getMaidBauble();
            for (int i = 0; i < bauble.getSlots(); i++) {
                if (isSpellWeapon(bauble.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 女仆"用得出法术"吗——自动换武器/自主参战对法术任务的收紧闸门。
     * ① 随身有法术装备（主手/副手/背包/饰品栏）→ 会；② 或附属数据里已有她的法术书 → 会。
     */
    public static boolean maidHasSpells(EntityMaid maid) {
        if (maid == null || !isLoaded()) {
            return false;
        }
        if (hasSpellGear(maid)) {
            return true;
        }
        return addonDataHasSpellBook(maid);
    }

    /**
     * 附属自己的盟友判定（v1.2.2 实测五百六十）：友军风免用它来判断"这个人该不该被免"。
     *
     * 为什么不用我们自己的口径而是借它的：附属对"女仆→友军"的伤害就是在
     * {@code MaidSpellAllyEvents} 里按 {@code MaidSpellAllyResolver.areFriendly} 取消的
     * （javap 实证：它依次看魔法召唤物的召唤者、同队、同主人/盟友）。
     * 既然"伤害免了"，"震开"就该按**完全同一份名单**免 —— 否则会出现
     * "这个人它认为不该受伤、却被震飞"的错位，正是用户报的现象。
     *
     * @return 附属在场时返回它的判定结果；附属不在场 / 老版本没有这个类 → 返回 null，
     *         调用方回退到本模组自己的口径（{@link FriendlyFireGuard#isFriendly}）。
     */
    public static Boolean addonAreFriendly(net.minecraft.world.entity.Entity a,
                                           net.minecraft.world.entity.Entity b) {
        if (a == null || b == null || mAreFriendly == null || !isLoaded()) {
            return null;
        }
        try {
            Object r = mAreFriendly.invoke(null, a, b);
            return r instanceof Boolean v ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 附属自己的数据里有没有她的法术书（覆盖法术书被收进末影口袋等特殊位置的情况） */
    private static boolean addonDataHasSpellBook(EntityMaid maid) {
        if (!resolve() || mDataGet.length == 0) {
            return false;
        }
        try {
            java.util.UUID id = maid.getUUID();
            for (Method get : mDataGet) {
                if (get == null) {
                    continue;
                }
                Object data = get.invoke(null, id);
                if (data == null) {
                    continue;
                }
                Object books = mGetSpellBooks.invoke(data);
                if (books instanceof List<?> l && !l.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 反射句柄一次性解析（含缓存）。任何一环失败 → present=false，之后每次直接短路 false，
     * 不再重复尝试（附属中途被卸载/热重载的极端情况也只会退化成"不认法术任务"）。
     */
    private static synchronized boolean resolve() {
        if (resolved) {
            return present;
        }
        resolved = true;
        try {
            ClassLoader cl = MaidSpellCompat.class.getClassLoader();
            Class<?> manager = Class.forName(CLS_MANAGER, false, cl);
            Class<?> provider = Class.forName(CLS_PROVIDER, false, cl);
            Class<?> data = Class.forName(CLS_DATA, false, cl);
            mLoadedMods = manager.getMethod("getLoadedMods");
            mGetProvider = manager.getMethod("getProvider", String.class);
            mIsSpellBook = provider.getMethod("isSpellBook", ItemStack.class);
            mGetSpellBooks = data.getMethod("getSpellBooks");
            mDataGet = new Method[DATA_CLASSES.length];
            for (int i = 0; i < DATA_CLASSES.length; i++) {
                try {
                    mDataGet[i] = Class.forName(DATA_CLASSES[i], false, cl)
                            .getMethod("get", java.util.UUID.class);
                } catch (Throwable ignored) {
                    mDataGet[i] = null; // 该前置魔法模组没装（附属只启用其中一部分）
                }
            }
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        // 盟友判定是**可选**的（老版本附属可能没有这个类）：单独解析，失败只是让
        // addonAreFriendly 返回 null（调用方回退自己的口径），绝不能连带把 present 打成 false。
        try {
            Class<?> ally = Class.forName(CLS_ALLY, false, MaidSpellCompat.class.getClassLoader());
            mAreFriendly = ally.getMethod("areFriendly",
                    net.minecraft.world.entity.Entity.class, net.minecraft.world.entity.Entity.class);
        } catch (Throwable ignored) {
            mAreFriendly = null;
        }
        return present;
    }
}
