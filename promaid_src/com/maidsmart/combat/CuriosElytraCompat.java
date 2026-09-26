package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Predicate;

/**
 * v1.3.0(beta) 实测六百八十【女仆饰品栏（Curios）里的鞘翅】——软兼容层，**全反射、零硬依赖**。
 *
 * <p>玩家原话：「我现在了解到翘翅是可以放在饰品栏的，那么在饰品栏里面的鞘翅应该也可以作为启动
 * 我们的空袭的激活条件之一，并且等效消耗耐久。」
 *
 * <p>【为什么必须走反射】Curios 不在本模组的编译期 classpath 上（本工程一贯原则：不给玩家加任何
 * 前置、不硬链接第三方——{@link GunCompat}、{@link MaidSpellCompat}、{@link SlashBladeCompat} 同款）。
 * 而女仆的饰品栏**不是** TLM 的 {@code getMaidBauble()}（那个 {@code BaubleItemHandler} 的
 * {@code isItemValid} 只收 TLM 自己注册的饰品，鞘翅根本放不进去，字节码实证），它是 Curios 挂在
 * 女仆实体上的 {@code ICuriosItemHandler}。所以只能反射问 Curios 要。
 *
 * <p>【两版 API 形态（javap 实证，本类只用两版**同名**的那几个方法）】
 * <pre>
 *   CuriosApi.getCuriosInventory(LivingEntity)
 *        1.20.1 curios-5.x : LazyOptional&lt;ICuriosItemHandler&gt;
 *        1.21.1 curios-9.x : Optional&lt;ICuriosItemHandler&gt;
 *        → 两者都有 orElse(T)，本类统一用反射调 orElse(null) 拆壳（不认具体类型）
 *   ICuriosItemHandler.getCurios()      : Map&lt;String, ICurioStacksHandler&gt;（槽位名 → 槽组）
 *   ICurioStacksHandler.getStacks()     : IDynamicStackHandler
 *   IDynamicStackHandler.getSlots() / getStackInSlot(int) / setStackInSlot(int, ItemStack)
 * </pre>
 *
 * <p>【"等效消耗耐久"怎么落地的】本类只负责"把她饰品栏里那件鞘翅**取出来**"；取出后由
 * {@code MaidFlightKit.equip} 穿到她的**胸甲槽**——原版滑翔的闸门
 * （{@code LivingEntity.updateFallFlying}）只认胸甲槽的鞘翅，穿上去之后耐久就由原版那套
 * 「滑翔中每 20 tick 扣 1 点」照常扣，与她自己从背包里翻出一件鞘翅穿上是**同一条路**，
 * 所以"等效"是结构上成立的，不是另写一套扣耐久。
 *
 * <p>【失败一律安全】Curios 没装 / 版本不符 / 反射被拒 / 她根本没有饰品栏槽位 → 所有方法返回
 * null / "未装"，调用方按"没有饰品栏鞘翅"处理，行为与旧版一字不差。
 *
 * <p>【两树同一份】本类不碰任何加载器专有类型（不引用 Forge/NeoForge 注册表、不用 SRG 名字），
 * 也不调 {@code ItemStack.isEmpty()/copy()} 这类两树名字不同的方法，所以 SRG 树与 Mojmap 树里
 * **逐字相同**（要清空槽位时，标记用的空栈由调用方传进来）。
 */
public final class CuriosElytraCompat {

    private CuriosElytraCompat() {
    }

    private static final String API_CLASS = "top.theillusivec4.curios.api.CuriosApi";
    private static final Object LOCK = new Object();

    /** null = 还没问过；true/false = Curios 在不在（只问一次） */
    private static volatile Boolean loaded = null;
    /** 反射句柄解析失败过就不再反复试（版本不符时别每 tick 抛异常） */
    private static volatile boolean gaveUp = false;

    private static Method mGetInventory;   // CuriosApi.getCuriosInventory(LivingEntity)
    private static Method mGetCurios;      // ICuriosItemHandler.getCurios()
    private static Method mGetStacks;      // ICurioStacksHandler.getStacks()
    private static Method mGetSlots;       // IDynamicStackHandler.getSlots()
    private static Method mGetStackInSlot; // IDynamicStackHandler.getStackInSlot(int)
    private static Method mSetStackInSlot; // IDynamicStackHandler.setStackInSlot(int, ItemStack)

    /** Curios 是否在场（Class.forName 一次，之后走缓存）。 */
    public static boolean available() {
        Boolean l = loaded;
        if (l != null) {
            return l;
        }
        boolean ok;
        try {
            Class.forName(API_CLASS);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        loaded = ok;
        return ok;
    }

    private static boolean handlesForApi() {
        if (!available() || gaveUp) {
            return false;
        }
        if (mGetInventory != null) {
            return true;
        }
        synchronized (LOCK) {
            if (mGetInventory != null) {
                return true;
            }
            try {
                mGetInventory = Class.forName(API_CLASS)
                        .getMethod("getCuriosInventory", LivingEntity.class);
                return true;
            } catch (Throwable t) {
                gaveUp = true;
                return false;
            }
        }
    }

    private static boolean handlesForHandler(Object handler) {
        if (mGetCurios != null) {
            return true;
        }
        synchronized (LOCK) {
            if (mGetCurios != null) {
                return true;
            }
            try {
                mGetCurios = handler.getClass().getMethod("getCurios");
                return true;
            } catch (Throwable t) {
                gaveUp = true;
                return false;
            }
        }
    }

    private static boolean handlesForStacksHandler(Object stacksHandler) {
        if (mGetStacks != null) {
            return true;
        }
        synchronized (LOCK) {
            if (mGetStacks != null) {
                return true;
            }
            try {
                mGetStacks = stacksHandler.getClass().getMethod("getStacks");
                return true;
            } catch (Throwable t) {
                gaveUp = true;
                return false;
            }
        }
    }

    private static boolean handlesForStacks(Object stacks) {
        if (mGetSlots != null) {
            return true;
        }
        synchronized (LOCK) {
            if (mGetSlots != null) {
                return true;
            }
            try {
                Class<?> c = stacks.getClass();
                mGetSlots = c.getMethod("getSlots");
                mGetStackInSlot = c.getMethod("getStackInSlot", int.class);
                mSetStackInSlot = c.getMethod("setStackInSlot", int.class, ItemStack.class);
                return true;
            } catch (Throwable t) {
                gaveUp = true;
                return false;
            }
        }
    }

    /** Optional / LazyOptional 都拆成里面的那个对象（都没有 → null）。 */
    private static Object unwrap(Object optionalLike) {
        if (optionalLike == null) {
            return null;
        }
        try {
            Method orElse = optionalLike.getClass().getMethod("orElse", Object.class);
            return orElse.invoke(optionalLike, (Object) null);
        } catch (Throwable ignored) {
        }
        try {
            Method resolve = optionalLike.getClass().getMethod("resolve");
            return resolve.invoke(optionalLike);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 饰品栏里第一件满足 {@code filter} 的物品（**只读**，不取走）；没有 → {@code null}。
     *
     * <p>查找次序：先 {@code back} 槽（原版鞘翅在 Curios 里的常规归宿），再其余槽位——
     * 玩家的鞘翅可能挂在任意一个接受它的槽里（模组/整合包自定义槽位），不能只看 back。
     *
     * <p>{@code filter} 必须自己对"空栈"返回 false（本模组调用方传的都是
     * {@link MaidFlightKit#isElytraLike}，它认空栈为 false）——本类**不调** {@code ItemStack.isEmpty()}
     * 就是为了保持两树同源（那两棵树里这个名字不一样）。
     */
    public static ItemStack findElytra(EntityMaid maid, Predicate<ItemStack> filter) {
        return scan(maid, filter, null);
    }

    /**
     * 饰品栏里第一件满足 {@code filter} 的物品**取走**（从槽里真实移出，返回那一件）；没有 → null。
     *
     * @param emptyMarker 清空槽位用的"空栈"——**由调用方传入**：两棵树里空栈常量的名字不同
     *                    （一版 {@code ItemStack.EMPTY}、一版 {@code ItemStack.f_41583_}），
     *                    传进来本类才能保持两树逐字相同。传 null 直接放弃（返回 null），
     *                    **绝不写一个 null 进槽位**。
     */
    public static ItemStack takeElytra(EntityMaid maid, Predicate<ItemStack> filter, ItemStack emptyMarker) {
        if (emptyMarker == null) {
            return null;
        }
        return scan(maid, filter, emptyMarker);
    }

    /** 一行诊断：`饰品栏=有/空`；Curios 不在场时明说。 */
    public static String describe(EntityMaid maid, Predicate<ItemStack> filter) {
        if (!available()) {
            return "未装 Curios";
        }
        ItemStack s;
        try {
            s = findElytra(maid, filter);
        } catch (Throwable t) {
            s = null;
        }
        return s == null ? "空" : "有 1 件";
    }

    /**
     * 扫她饰品栏的所有槽位；{@code emptyMarker != null} 时命中即取走。
     *
     * @return 命中的那一件（取走时就是刚被移出的那件本身）；没有 → null
     */
    private static ItemStack scan(EntityMaid maid, Predicate<ItemStack> filter, ItemStack emptyMarker) {
        if (maid == null || filter == null || !handlesForApi()) {
            return null;
        }
        try {
            Object handler = unwrap(mGetInventory.invoke(null, maid));
            if (handler == null || !handlesForHandler(handler)) {
                return null;
            }
            Object mapObj = mGetCurios.invoke(handler);
            if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) {
                return null;
            }
            ItemStack hit = scanOne(map.get("back"), filter, emptyMarker);
            if (hit != null) {
                return hit;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("back".equals(e.getKey())) {
                    continue;
                }
                hit = scanOne(e.getValue(), filter, emptyMarker);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ItemStack scanOne(Object stacksHandler, Predicate<ItemStack> filter, ItemStack emptyMarker) {
        if (stacksHandler == null || !handlesForStacksHandler(stacksHandler)) {
            return null;
        }
        try {
            Object stacks = mGetStacks.invoke(stacksHandler);
            if (stacks == null || !handlesForStacks(stacks)) {
                return null;
            }
            int n = (Integer) mGetSlots.invoke(stacks);
            for (int i = 0; i < n; i++) {
                Object o = mGetStackInSlot.invoke(stacks, i);
                if (!(o instanceof ItemStack s) || s == null || !filter.test(s)) {
                    continue;
                }
                if (emptyMarker == null) {
                    return s; // 只读
                }
                // 取走：直接把槽位写成空栈（IDynamicStackHandler.setStackInSlot → Curios 自己的
                // onContentsChanged 会照常同步；**不**依赖"返回的栈是不是副本"——
                // 本工程吃过"对 getStackInSlot 返回的栈改 count 结果扣不掉"的亏）
                mSetStackInSlot.invoke(stacks, i, emptyMarker);
                return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
