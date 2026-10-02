package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诡厄巫法（Goety，modid = goety，作者 Polarice3）的**软兼容层**——让女仆能用它的
 * 风系位移聚晶当推进器。范式与 {@code GunCompat}（枪械）、{@code SlashBladeCompat}（拔刀剑）、
 * {@code MaidSpellCompat}（万法皆通）一致：**全程反射，能调就调、调不到就当没有**，
 * 没装 / 换版本 / 内部改名都不该让 Promaid 起不来。
 *
 * <h2>为什么调 {@code mobSpellResult} 而不是走玩家的施法链（源码 + 字节码实证）</h2>
 * Goety 的玩家施法入口 {@code DarkWand#use(Level, Player, InteractionHand)} 本身是原版
 * **Player 专属**回调，中间还有一道硬门 {@code if (spell != null && caster instanceof Player)}，
 * 不满足就走 failParticles + 灭火音效，**根本到不了 SpellResult**。灵魂（SE）那套同理：
 * capability 只在 Player 上注册。但 Goety **自己给怪物留了正门**：
 * {@code Spell#mobSpellResult(LivingEntity, ItemStack)} 是 public，直接进 SpellResult，
 * **不查灵魂、不扣灵魂、不进冷却**——Goety 自家的骷髅领主、唤风仆从全走这条路。
 * 所以女仆作为 Mob 是"有门可进的"，代价是**灵魂与冷却得由我们（外层）自己管**。
 *
 * <h2>数值（1.20.1 的 goety-2.5.58.4 字节码实测；与 1.21.1 的 3.1.5.1 同公式）</h2>
 * <pre>
 *   两者都是「直接把速度**覆盖**成 视线方向 × d0」（不是累加）：
 *     d0 = power + potency / 2      // 注意 potency/2 是**整数除法**
 *     飞行聚晶 FlyingSpell：power = 0.5；若手里是 wind_staff（rightStaff）则 power = 1.0
 *     发射聚晶 LaunchSpell：power = 1.5；若手里是 wind_staff 则 power = 2.5
 *   节奏：FlyingSpell 的 Cooldown() 硬编码 0 ⇒ 持杖者**每 tick** 覆盖一次（真·恒速飞行）；
 *        LaunchSpell 走普通冷却（配置 launchCoolDown = 20 tick）。
 * </pre>
 * 也就是说：**飞行聚晶 = 20 格/秒（风杖）或 10 格/秒**，比我们仿创造飞行的上限高一个量级
 * ——它更像"喷气"而不是"直升机"。
 *
 * <h2>已知坑（本工程踩过同名的一个）</h2>
 * FlyingSpell 靠"每 tick 覆盖 deltaMovement"，而**女仆是 Mob**：她的 {@code MoveControl}/travel
 * 会在 aiStep 阶段改速度。Goety 侧的玩家版不用管这个，**我们这一侧必须管**——与仿创造飞行同一个
 * 坑，解法也一样（源头抑制走路目标 + 晚写速度）。
 *
 * <p>【1.20.1 差异】本树手工编译、生产环境跑的是 SRG 类，所以：物品注册名走
 * {@link ForgeRegistries#ITEMS}（1.21.1 是 {@code BuiltInRegistries}）、物品栏 capability 走
 * {@code ForgeCapabilities.ITEM_HANDLER} + {@code LazyOptional}（1.21.1 是 directly-returned
 * {@code Capabilities.ItemHandler.ITEM}）、{@code getMaidBauble()} 直接就是
 * {@link IItemHandler}（1.21.1 要 instanceof 一次）。反射的类名/方法名两版**完全同名**
 * （javap 实证：{@code com.Polarice3.Goety} 根、{@code IWand.getFocus}、
 * {@code Spell.mobSpellResult}、{@code WandUtil.getStats}、{@code SpellStat.getPotency}）。
 */
public final class MaidGoetyCompat {

    /** Goety 的 modid（{@code ModList.get().isLoaded} 用）。 */
    public static final String MOD_ID = "goety";

    /** 已知的两套包根：官方 Forge 版在前，万法皆通用的那套在后。 */
    private static final String[] ROOTS = {"com.Polarice3.Goety", "za.co.infernos.goety"};

    /** 风之魔杖：{@code rightStaff()} 命中它时威力翻倍（发射 1.5→2.5、飞行 0.5→1.0）。 */
    public static final String WIND_STAFF = "goety:wind_staff";

    /** 发射聚晶：给施法者一个【视线方向】的一次性冲量。 */
    public static final String FOCUS_LAUNCH = "goety:launch_focus";

    /** 飞行聚晶：每 tick 把速度覆盖成【视线方向 × 恒定速率】= 持续飞行。 */
    public static final String FOCUS_FLYING = "goety:flying_focus";

    /** 聚晶包（存货，不是施法器）。 */
    public static final String FOCUS_BAG = "goety:focus_bag";

    // ---------------- 反射缓存（一次性解析，失败即"没有这个模组"） ----------------

    private static boolean inited;
    private static boolean ok;
    private static Method mGetFocus;        // IWand.getFocus(ItemStack) : static
    private static Method mRightStaff;      // Spell.rightStaff(ItemStack) : 威力是否翻倍
    private static Method mGetStats;        // WandUtil.getStats(LivingEntity, ISpell) : static
    private static Method mGetPotency;      // SpellStat.getPotency()
    private static Object flyingProto;      // 一个复用的 FlyingSpell 实例（只用来查询，不施放）
    private static Method mMobSpellResult;  // Spell.mobSpellResult(LivingEntity, ItemStack)
    private static Class<?> cFlyingSpell;
    private static Class<?> cLaunchSpell;
    /** 命中的包根（init 时确定）——{@code thrustPower} 里的 WandUtil 也用它拼接。 */
    private static String rootHit;

    private MaidGoetyCompat() {
    }

    private static synchronized void init() {
        if (inited) {
            return;
        }
        inited = true;
        // 【两套包名都要试】官方 Forge 版是 com.Polarice3.Goety；《万法皆通》那套是
        // za.co.infernos.goety。同一份 Promaid 要能同时吃这两种发行，所以按顺序试。
        for (String root : ROOTS) {
            try {
                Class<?> iwand = Class.forName(root + ".api.items.magic.IWand");
                Class<?> spell = Class.forName(root + ".common.magic.Spell");
                mGetFocus = iwand.getMethod("getFocus", ItemStack.class);
                mMobSpellResult = spell.getMethod("mobSpellResult", LivingEntity.class, ItemStack.class);
                cFlyingSpell = Class.forName(root + ".common.magic.spells.wind.FlyingSpell");
                cLaunchSpell = Class.forName(root + ".common.magic.spells.wind.LaunchSpell");
                rootHit = root; // 记下命中包根：thrustPower 的 WandUtil 也按它解析
                ok = true;
                return;
            } catch (Throwable ignored) {
                // 换下一个包名
            }
        }
        ok = false;
    }

    /** Goety 在不在、且反射路径对不对。 */
    public static boolean available() {
        init();
        return ok;
    }

    /** 物品注册名（{@code goety:flying_focus} 这种）；解析不出来返回空串。 */
    public static String itemId(ItemStack stack) {
        try {
            if (stack == null || stack.m_41619_()) {
                return "";
            }
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            return rl == null ? "" : rl.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 读一件物品的"当前聚晶"（法杖单槽）。**任何物品都能问**——
     * 不是法杖的物品会返回空栈而不是抛异常（Goety 侧走的是 item capability，取不到就是空）。
     */
    public static ItemStack focusOf(ItemStack staff) {
        init();
        if (!ok || staff == null || staff.m_41619_()) {
            return ItemStack.f_41583_;
        }
        try {
            Object o = mGetFocus.invoke(null, staff);
            return o instanceof ItemStack s ? s : ItemStack.f_41583_;
        } catch (Throwable t) {
            return ItemStack.f_41583_;
        }
    }

    /** 这件物品是不是"装着聚晶的法杖"。 */
    public static boolean isStaff(ItemStack stack) {
        return !focusOf(stack).m_41619_();
    }

    /**
     * 一只女仆身上"可能装着东西"的全部位置：双手 + 背包（{@code getAvailableBackpackInv}）
     * + 女仆饰品栏（{@code getMaidBauble}）+ Curios 饰品栏（反射）。位置描述 → 物品栈。
     */
    public static Map<String, ItemStack> containers(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (maid == null) {
            return out;
        }
        try {
            out.put("主手", maid.m_21205_());
            out.put("副手", maid.m_21206_());
        } catch (Throwable ignored) {
        }
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            if (inv != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    out.put("背包槽" + i, inv.getStackInSlot(i));
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 【1.20.1】TLM 的 getMaidBauble() 直接返回 IItemHandler（1.21.1 才要 instanceof 一次）
            IItemHandler h = maid.getMaidBauble();
            if (h != null) {
                for (int i = 0; i < h.getSlots(); i++) {
                    out.put("女仆饰品" + i, h.getStackInSlot(i));
                }
            }
        } catch (Throwable ignored) {
        }
        out.putAll(curios(maid));
        return out;
    }

    /** 一只女仆身上（双手/背包/两类饰品栏）所有"装着聚晶的法杖"：位置描述 → 法杖栈。 */
    public static Map<String, ItemStack> staffs(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> e : containers(maid).entrySet()) {
            addIfStaff(out, e.getKey(), e.getValue());
        }
        return out;
    }

    private static void addIfStaff(Map<String, ItemStack> out, String where, ItemStack stack) {
        if (stack != null && !stack.m_41619_() && isStaff(stack)) {
            out.put(where, stack);
        }
    }

    /** 女仆饰品栏（Curios）里的全部物品：槽位名 → 栈（自包含反射，不依赖别的兼容层）。 */
    public static Map<String, ItemStack> curios(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (maid == null) {
            return out;
        }
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object inv0 = api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, maid);
            if (inv0 == null) {
                return out;
            }
            // 【两版壳】1.20.1 curios-5.x 是 LazyOptional、1.21.1 是 Optional；两者都有
            // orElse(T)，所以统一反射调 orElse(null) 拆壳（不认具体类型）。
            Object handler;
            try {
                handler = inv0.getClass().getMethod("orElse", Object.class).invoke(inv0, (Object) null);
            } catch (Throwable t) {
                handler = inv0;
            }
            if (handler == null) {
                handler = inv0;
            }
            Object mapObj = handler.getClass().getMethod("getCurios").invoke(handler);
            if (!(mapObj instanceof Map<?, ?> map)) {
                return out;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object stacksHandler = e.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                if (stacks == null) {
                    continue;
                }
                int n = (Integer) stacks.getClass().getMethod("getSlots").invoke(stacks);
                for (int i = 0; i < n; i++) {
                    Object o = stacks.getClass().getMethod("getStackInSlot", int.class).invoke(stacks, i);
                    if (o instanceof ItemStack s && !s.m_41619_()) {
                        out.put("饰品[" + e.getKey() + "]" + i, s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 她身上**有没有这个聚晶**（保真门禁）：法杖当前槽 / 聚晶包里 / 任意容器里的物品。
     *
     * <p>【为什么要门禁】飞行聚晶是**道具**驱动的能力——不查她有没有，就等于"凭空给她装了个
     * 推进器"，与我们做仿创造飞行时定的口径（不绕过物品/燃料）冲突。所以这条判据是
     * 「她真的有这件聚晶」而不是「法术调得通」。
     */
    public static boolean hasFocus(EntityMaid maid, String focusId) {
        if (maid == null || focusId == null || focusId.isEmpty()) {
            return false;
        }
        for (ItemStack staff : staffs(maid).values()) {
            if (focusId.equals(itemId(focusOf(staff)))) {
                return true;
            }
        }
        for (ItemStack s : bagStacks(maid)) {
            if (focusId.equals(itemId(s))) {
                return true;
            }
        }
        for (ItemStack s : containers(maid).values()) {
            if (focusId.equals(itemId(s))) {
                return true;
            }
        }
        return false;
    }

    /** 这只女仆身上所有可用的聚晶 id（法杖当前槽 + 聚晶包里的存货）。 */
    public static List<String> focusIds(EntityMaid maid) {
        List<String> out = new ArrayList<>();
        for (ItemStack staff : staffs(maid).values()) {
            String id = itemId(focusOf(staff));
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        for (ItemStack bag : bagStacks(maid)) {
            String id = itemId(bag);
            if (id.startsWith("goety:") && id.endsWith("_focus") && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 背包/饰品栏里的每个聚晶包，把"包里面"的东西取出来（走能力拿它的物品栏）。 */
    private static List<ItemStack> bagStacks(EntityMaid maid) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : containers(maid).values()) {
            if (s != null && FOCUS_BAG.equals(itemId(s))) {
                out.addAll(inventoryOf(s));
            }
        }
        return out;
    }

    /**
     * 读一件"带物品栏的物品"（如聚晶包）里的内容：走 Forge 的 ITEM_HANDLER capability。
     *
     * <p>【1.20.1 形态】{@code ItemStack.getCapability(Capability, Direction)} 返回
     * {@code LazyOptional}，要 {@code .orElse(null)} 拆壳（1.21.1 是一参直接返回
     * {@code IItemHandler}）。方向传 {@code null} = 与朝向无关。
     */
    private static List<ItemStack> inventoryOf(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        try {
            Object lazy = stack.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
            if (lazy == null) {
                return out;
            }
            Object h = ((net.minecraftforge.common.util.LazyOptional<?>) lazy).orElse(null);
            if (h instanceof IItemHandler handler) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack s = handler.getStackInSlot(i);
                    if (s != null && !s.m_41619_()) {
                        out.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 这一发飞行聚晶的**基准速率**（格/tick）：Goety 的公式是
     * {@code d0 = power + potency / 2}，其中 {@code power = rightStaff ? 1.0 : 0.5}
     * （{@code rightStaff} = 手里的杖与法术同系，例如风系聚晶 + 风之魔杖）。
     * 我们不改威力，只是把它读出来——"速度由聚晶给"这条语义保持不变。
     */
    public static double thrustPower(EntityMaid maid, ItemStack staff) {
        init();
        if (!ok) {
            return 0.5;
        }
        try {
            if (flyingProto == null) {
                flyingProto = cFlyingSpell.getDeclaredConstructor().newInstance();
            }
            if (mRightStaff == null) {
                mRightStaff = cFlyingSpell.getMethod("rightStaff", ItemStack.class);
            }
            ItemStack use = staff == null ? ItemStack.f_41583_ : staff;
            double power = Boolean.TRUE.equals(mRightStaff.invoke(flyingProto, use)) ? 1.0 : 0.5;
            double potency = 0.0;
            try {
                if (mGetStats == null) {
                    // 【必须用命中的那个包根】写死 com.Polarice3.Goety 会在
                    // za.co.infernos.goety 那套发行里 ClassNotFound → 威力加成永远当 0，
                    // 且失败被吞掉、现场看不出原因。
                    String root = rootHit == null ? ROOTS[0] : rootHit;
                    mGetStats = Class.forName(root + ".utils.WandUtil")
                            .getMethod("getStats", LivingEntity.class, Class.forName(root + ".api.magic.ISpell"));
                }
                Object stat = mGetStats.invoke(null, maid, flyingProto);
                if (stat != null) {
                    if (mGetPotency == null) {
                        mGetPotency = stat.getClass().getMethod("getPotency");
                    }
                    potency = ((Number) mGetPotency.invoke(stat)).doubleValue() / 2.0;   // 与 Goety 一致：整数除法后转 double
                }
            } catch (Throwable ignored) {
                // 拿不到加成就算 0（基准速度），不影响方向
            }
            return power + potency;
        } catch (Throwable t) {
            return 0.5;
        }
    }

    /**
     * 【实测七百六十五·点2】一只诡厄仆从的"真正主人"（{@code IOwned.getTrueOwner()}）。
     *
     * <p>反编译实证：{@code RedstoneMonstrosity.mobInteract} 只在
     * {@code getTrueOwner() != null && pPlayer == getTrueOwner()} 时才允许骑乘。所以"主人直接
     * 接管"这条判据必须问**它自己的**主人是谁——否则一个骑不上它的人右击一下，我们白白把女仆
     * 请下来、而 Goety 又不会让他上。拿不到（非诡厄仆从）→ 返回 null。
     */
    public static LivingEntity trueOwner(Entity e) {
        try {
            if (e == null) {
                return null;
            }
            Class<?> c = e.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod("getTrueOwner");
                    m.setAccessible(true);
                    Object r = m.invoke(e);
                    return r instanceof LivingEntity le ? le : null;
                } catch (NoSuchMethodException ignored) {
                    // 往上找
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 【实测七百六十六·后门】把诡厄仆从的"目标优先闸"打开（{@code IServant.setPriorityTime(100)}）。
     *
     * <h2>现场（玩家 2026-10-02 19:42/19:43 那两局的日志 + 反编译实证）</h2>
     * 女仆骑上下界合金巨兽仆从、目标也写进去了（日志 {@code [模组坐骑·目标] ... 目标=husk}），
     * 但它**一招不出、只会自己游荡**。根因在 {@code Summoned.setTarget(LivingEntity)}：
     * <pre>
     *   if (isGuardingArea() &amp;&amp; !isPrioritizing()) {
     *       if (target != null) {
     *           if (target.distanceToSqr(vec3BoundPos()) &lt;= Mth.square(guardingRange()))
     *               overrideSetTarget(target);   // 区内才收
     *           // 区外：**静默丢掉**，连日志都没有
     *       } else overrideSetTarget(null);
     *   } else overrideSetTarget(target);
     * </pre>
     * 而 {@code IServant.servantTick()} 对守区仆从还有第二道：目标离守区中心超过
     * {@code guardingRange()*2} 就 {@code owned.setTarget(null)} 直接清掉。两条叠加的结果是
     * **它的 getTarget() 恒为 null** ⇒ {@code InternalSummonMoveGoal} / 五个
     * {@code InternalSummonAttackGoal}（砸地/岩浆弹/火焰弹/冲锋/震地）全部 {@code canUse()=false}
     * ——只剩 {@code Summoned.WanderGoal}（priority 5，不看目标）在走 ⇒ 玩家原话「只会移动，
     * 但仍然不会进行攻击」。它自己的技能冷却、装填、视线判据全是好的，**只是没人给它目标**。
     *
     * <h2>这道闸为什么能开</h2>
     * 上面两条的判据里都有 {@code !isPrioritizing()} / {@code isPrioritizing()}——
     * 而 {@code isPrioritizing() == getPriorityTime() > 0}，{@code setPriorityTime(int)} 是
     * {@code IServant} 的 public 接口方法、{@code Summoned} 原样实现。把闸打开之后再走
     * {@code setTarget(...)}，它自己就会落到 {@code else} 分支直接收下，且 {@code servantTick}
     * 的"清目标"那一段整块被跳过。**没有新数值、没有绕过它自己的任何判据**——用的是模组
     * 自己给"优先目标"留的那条正门。
     *
     * <p>本方法只对**诡厄仆从**生效（{@code setPriorityTime} 在别的实体上根本不存在 → 返回 false，
     * 调用方照旧 {@code setTarget}，一个字节都不变）。110 拍后自动衰减，不留痕。
     */
    public static boolean openPriorityGate(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            Object cached = GATE_METHODS.computeIfAbsent(e.getClass(), MaidGoetyCompat::findPriorityTime);
            if (cached instanceof java.lang.reflect.Method m) {
                m.invoke(e, 100);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** {@code setPriorityTime} 的按类缓存（哨兵 {@link #GATE_NONE} = 这类没有这道闸）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> GATE_METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object GATE_NONE = new Object();

    private static Object findPriorityTime(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Method m = k.getDeclaredMethod("setPriorityTime", int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 往上找（Summoned / IServant 默认实现都可能有）
            } catch (Throwable ignored) {
                return GATE_NONE;
            }
        }
        return GATE_NONE;
    }

    /**
     * 让女仆放一发聚晶（走 Goety 给怪物留的正门 {@code mobSpellResult}）。
     *
     * @param key   {@code "flying"} / {@code "launch"}（其余一律返回 false）
     * @param staff 法杖栈——**它决定威力倍率**（风之魔杖 ×2）；没有就传空栈（基准威力）
     */
    public static boolean cast(EntityMaid maid, String key, ItemStack staff) {
        return castDiag(maid, key, staff) == null;
    }

    /**
     * 与 {@link #cast} 同义，但把失败原因**吐出来**（成功返回 null）。
     * 排查用：飞行过程中"法术好像没生效"时，先看这里是不是在抛异常——旧版
     * {@code cast} 把 Throwable 一口吞掉，导致"速度只剩重力"这种现场看不出原因。
     */
    public static String castDiag(EntityMaid maid, String key, ItemStack staff) {
        init();
        if (!ok) {
            return "Goety 反射未解析";
        }
        if (maid == null || maid.m_9236_().m_5776_()) {
            return "空或客户端";
        }
        try {
            Class<?> cls = "launch".equals(key) ? cLaunchSpell : cFlyingSpell;
            Object spell = cls.getDeclaredConstructor().newInstance();
            ItemStack use = staff == null ? ItemStack.f_41583_ : staff;
            mMobSpellResult.invoke(spell, maid, use);
            return null;
        } catch (Throwable t) {
            // 【只回一行】调用方（MaidGoetyFlight）在失败时**每 tick** 会把它写进日志——
            // 一场飞行就是几万行堆栈。这里压缩成"异常类: 首行消息"，够定位就够了。
            String msg = t.getClass().getName();
            String m = t.getMessage();
            if (m != null && !m.isEmpty()) {
                msg = msg + ": " + m.split("\n", 2)[0];
            }
            if (msg.length() > 200) {
                msg = msg.substring(0, 200) + "…";
            }
            return msg;
        }
    }
}
