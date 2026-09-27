package com.maidsmart.goety;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诡厄巫法（Goety，modid = goety，作者 Polarice3）的**软兼容层**——让女仆能用它的
 * 风系位移聚晶当推进器。范式与 {@link com.maidsmart.combat.GunCompat}（枪械）、
 * {@link com.maidsmart.combat.SlashBladeCompat}（拔刀剑）、{@code MaidSpellCastCompat}
 * （万法皆通）一致：**全程反射，能调就调、调不到就当没有**，没装 / 换版本 / 内部改名
 * 都不该让 Promaid 起不来。
 *
 * <h2>为什么调 {@code mobSpellResult} 而不是走玩家的施法链（源码 + 字节码实证）</h2>
 * Goety 的玩家施法入口是 {@code DarkWand#use(Level, Player, InteractionHand)}——
 * 它本身就是原版的 **Player 专属**回调，而且中间有一道硬门
 * {@code if (spell != null && caster instanceof Player)}，不满足就走
 * {@code failParticles} + 灭火音效，**根本到不了 SpellResult**。
 * 灵魂（SE）那套同理：capability 只在 Player 上注册、{@code SEHelper.*} 的形参也全是 Player。
 *
 * <p>但 Goety **自己给怪物留了正门**：{@code Spell#mobSpellResult(LivingEntity, ItemStack)}
 * 是 public，直接进 {@code SpellResult}，**不查灵魂、不扣灵魂、不进冷却**——
 * Goety 自家的骷髅领主、唤风仆从全走这条路。所以女仆作为 Mob 是"有门可进的"，
 * 代价是**灵魂与冷却得由我们（外层）自己管**。
 *
 * <h2>数值（1.21.1 的 goety-3.1.5.1 字节码实测 + 配置回读）</h2>
 * <pre>
 *   两者都是「直接把速度**覆盖**成 视线方向 × d0」（不是累加）：
 *     d0 = power + potency / 2      // 注意 potency/2 是**整数除法**
 *     飞行聚晶 FlyingSpell：power = 0.5；若手里是 wind_staff（rightStaff）则 power = 1.0
 *     发射聚晶 LaunchSpell：power = 1.5；若手里是 wind_staff 则 power = 2.5
 *   节奏：FlyingSpell 的 Cooldown() 硬编码 0 ⇒ 持杖者**每 tick** 覆盖一次（真·恒速飞行）；
 *        LaunchSpell 走普通冷却（配置 launchCoolDown = 20 tick）。
 *   配置默认（config/goety/goety-spells.toml）：发射 灵魂4/冷却20；飞行 灵魂4/每 20 tick 扣一次。
 * </pre>
 * 也就是说：**飞行聚晶 = 20 格/秒（风杖）或 10 格/秒**，比我们仿创造飞行的上限（0.35 格/tick）
 * 高一个量级——它更像"喷气"而不是"直升机"。
 *
 * <h2>已知坑（本工程踩过同名的一个）</h2>
 * FlyingSpell 靠"每 tick 覆盖 deltaMovement"，而**女仆是 Mob**：她的
 * {@code MoveControl}/`travel` 会在 aiStep 阶段改速度。Goety 侧的玩家版不用管这个
 * （玩家的 travel 走输入速度 + hasImpulse 同步），**我们这一侧必须管**——
 * 与仿创造飞行同一个坑，解法也一样（源头抑制走路目标 + 晚写速度）。
 */
public final class MaidGoetyCompat {

    /** Goety 的 modid（{@code ModList.get().isLoaded} 用）。 */
    public static final String MOD_ID = "goety";

    /** 已知的两套包根：官方 NeoForge 版在前，万法皆通 1.21 分支用的那套在后。 */
    private static final String[] ROOTS = {"com.Polarice3.Goety", "za.co.infernos.goety"};

    /** 风之魔杖：`rightStaff()` 命中它时威力翻倍（发射 1.5→2.5、飞行 0.5→1.0）。 */
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
    private static Method mMobSpellResult;  // Spell.mobSpellResult(LivingEntity, ItemStack)
    private static Class<?> cFlyingSpell;
    private static Class<?> cLaunchSpell;

    private MaidGoetyCompat() {
    }

    private static synchronized void init() {
        if (inited) {
            return;
        }
        inited = true;
        // 【两套包名都要试】官网 NeoForge 版（Vivideru/Goety-3，1.21.1 的 3.1.5.1）是
        // com.Polarice3.Goety；而《万法皆通》1.21 分支里对接的却是 za.co.infernos.goety。
        // 同一份 Promaid 要能同时吃这两种发行，所以按顺序试，命中哪个用哪个。
        for (String root : ROOTS) {
            try {
                Class<?> iwand = Class.forName(root + ".api.items.magic.IWand");
                Class<?> spell = Class.forName(root + ".common.magic.Spell");
                mGetFocus = iwand.getMethod("getFocus", ItemStack.class);
                mMobSpellResult = spell.getMethod("mobSpellResult", LivingEntity.class, ItemStack.class);
                cFlyingSpell = Class.forName(root + ".common.magic.spells.wind.FlyingSpell");
                cLaunchSpell = Class.forName(root + ".common.magic.spells.wind.LaunchSpell");
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

    /** 物品注册名（`goety:flying_focus` 这种）；解析不出来返回空串。 */
    public static String itemId(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return "";
            }
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
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
        if (!ok || staff == null || staff.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            Object o = mGetFocus.invoke(null, staff);
            return o instanceof ItemStack s ? s : ItemStack.EMPTY;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 这件物品是不是"装着聚晶的法杖"。 */
    public static boolean isStaff(ItemStack stack) {
        return !focusOf(stack).isEmpty();
    }

    /**
     * 一只女仆身上"可能装着东西"的全部位置：双手 + 背包（{@code getAvailableBackpackInv}，
     * TLM 自己的 {@code CombinedInvWrapper}）+ 女仆饰品栏（{@code getMaidBauble}）
     * + Curios 饰品栏（反射）。位置描述 → 物品栈。
     */
    public static Map<String, ItemStack> containers(EntityMaid maid) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (maid == null) {
            return out;
        }
        try {
            out.put("主手", maid.getMainHandItem());
            out.put("副手", maid.getOffhandItem());
        } catch (Throwable ignored) {
        }
        try {
            var inv = maid.getAvailableBackpackInv();
            if (inv != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    out.put("背包槽" + i, inv.getStackInSlot(i));
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (maid.getMaidBauble() instanceof net.neoforged.neoforge.items.IItemHandler h) {
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
        if (stack != null && !stack.isEmpty() && isStaff(stack)) {
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
            Object handler = inv0.getClass().getMethod("resolve").invoke(inv0);
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
                    if (o instanceof ItemStack s && !s.isEmpty()) {
                        out.put("饰品[" + e.getKey() + "]" + i, s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
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

    /** 读一件"带物品栏的物品"（如聚晶包）里的内容：走 NeoForge 的 IItemHandler capability。 */
    private static List<ItemStack> inventoryOf(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        try {
            Object cap = stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ITEM);
            if (cap instanceof net.neoforged.neoforge.items.IItemHandler h) {
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack s = h.getStackInSlot(i);
                    if (s != null && !s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
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
     * 排查用：飞行过程中"法术好像没生效"时，先看这里是不是在抛异常——
     * 之前 {@code cast} 把 Throwable 一口吞掉，导致"速度只剩重力"这种现场看不出原因。
     */
    public static String castDiag(EntityMaid maid, String key, ItemStack staff) {
        init();
        if (!ok) {
            return "Goety 反射未解析";
        }
        if (maid == null || maid.level().isClientSide()) {
            return "空或客户端";
        }
        try {
            Class<?> cls = "launch".equals(key) ? cLaunchSpell : cFlyingSpell;
            Object spell = cls.getDeclaredConstructor().newInstance();
            ItemStack use = staff == null ? ItemStack.EMPTY : staff;
            mMobSpellResult.invoke(spell, maid, use);
            return null;
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString().replace("\n", " | ").replace("\t", " ");
        }
    }
}
