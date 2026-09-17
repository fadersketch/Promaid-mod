package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * v1.2.0 实测五百：拔刀剑（SlashBlade: Resharped）兼容层——**全反射，可选模组**。
 *
 * 反馈原文：「空袭技能目前不适配拔刀剑。」
 *
 * 【根因·两处，都用字节码实证】
 *
 * ① **TLM 的拔刀斩触发被硬编码门控在它自己的攻击任务上，我们的空袭任务永远进不去。**
 * 1.20.1 的 TLM 把拔刀剑支持放在 `EntityMaid.m_6674_(swing)` 开头调用的
 * `SlashBladeCompat.swingSlashBlade(maid, 主手)` 里，而那个方法体（反编译）第一步就是：
 * ```
 * if (isSlashBladeItem(stack)
 *         && maid.getTask().getUid().equals(TaskAttack.UID)) {   // ← 只认 touhou_little_maid:attack
 *     ... AttackManager.doSlash(...)
 * }
 * ```
 * 我们的飞行任务 UID 是 `maid_smart:flight_combat` / `flight_ranged`，不是
 * `touhou_little_maid:attack` → 这个 `equals` 恒 false → **女仆在空袭里挥刀，拔刀斩一次都不会触发**
 * （不是"触发得少"，是根本不进那段代码）。地面攻击任务下同一个女仆却能触发，这正是
 * 玩家看到的"空袭不适配拔刀剑"。
 *
 * 【1.21.1 侧额外实情，必须如实告知】1.21.1 的 TLM 1.5.3 **完全没有拔刀剑兼容代码**
 * （jar 内常量池扫 `slashblade` 零命中，1.20.1 那份 `SlashBladeCompat` / `SlashBladeRender`
 * 在 neo 版里不存在）——也就是说 1.21.1 上"女仆持拔刀剑"本来就没有任何官方联动，
 * 连地面攻击任务都不会触发拔刀斩。本兼容层在 1.21.1 上反而**同时补上了地面与空袭**。
 * （另：当前 1.21.1 实例里拔刀剑本体是 `.disabled` 状态，见交付说明。）
 *
 * ② **我们自己的命中结算只吃原版 `ATTACK_DAMAGE`，没有拔刀剑的伤害倍率。**
 * `MaidFlightCombatBehavior#hitOne` 的伤害 = `ATTACK_DAMAGE 属性 × 暴击 1.5 + 附魔`；
 * 而拔刀剑自己的口径（`AttackHelper.calculateTotalDamage` 反编译）是：
 * ```
 * 伤害 = (ATTACK_DAMAGE + 横扫加成 + 段位加成 + 附魔加成)
 *        × comboRatio × getSlashBladeDamageScale(攻击者) × SLASHBLADE_DAMAGE_MULTIPLIER
 * ```
 * 其中 `getSlashBladeDamageScale` 就是拔刀剑自己注册的 `SLASHBLADE_DAMAGE` 属性
 * （`ModAttributes.getSlashBladeDamage`，默认 1.0，随练度/段位成长）。我们那条通道
 * **整段没有这个乘数**，所以同一把刀在空袭里打出的数字明显低于她自己地面攻击时打出的。
 *
 * 【改法：只做一件事——把我们空袭的"挥刀"补上 TLM 给地面攻击补的那一下】
 * 既然 TLM 那处门控改不了（那是它的类，而且 slashblade 不在我们的编译 classpath 里），
 * 就在我们**自己**的挥刀点复刻 TLM 的同一段调用（逐点一致，不自己发挥）：
 * ```
 * roll = random.nextInt(60) - 30
 * AttackManager.doSlash(maid, roll, Vec3.ZERO, mute=false, critical=false,
 *                       comboRatio=1.0, KnockBacks.smash)
 * 主手拔刀剑的 BLADESTATE.setLastActionTime(level.getGameTime())
 * ```
 * `doSlash` 生成的 `EntitySlashEffect` 会**自己按拔刀剑的完整公式结算伤害**
 * （上面那 ① ② 两段公式都在它内部：`EntitySlashEffect.tick` → `AttackManager.areaAttack`
 * → `AttackHelper.attack`），所以 ② 那条缺口是被这同一个调用一并补上的——
 * 不需要（也不应该）再往 `hitOne` 里乘一次倍率，否则会变成"我们的原版伤害 + 拔刀斩伤害"叠乘，
 * 比玩家和 TLM 的女仆都强。**加了这一下之后，空袭持刀的表现 = 她自己地面攻击持刀的表现**
 * （TLM 地面攻击也是"原版 doHurtTarget 一记 + 拔刀斩一记"两件都做，`MaidMeleeAttack`
 * 字节码实证：`m_6674_` 与 `m_7327_` 相邻两条调用）。
 *
 * 【为什么必须是反射】`compile_promaid.txt` / `compile_neo.txt` 的 classpath 里
 * **没有** slashblade（已核对），模组也不保证装 —— 直接 import 会编译不过、装了就崩。
 * 全部符号按需 `Class.forName` + 缓存，缺模组时 `isLoaded()` 一票否决，零开销。
 *
 * 【1.21.1 的状态读取口径与 1.20.1 不同】1.21.1 的拔刀剑 2.0.7 已经没有
 * `CapabilitySlashBlade`（该类在 2.0.7 里不存在，capability 包换成了
 * `BladeStateAccess` / `SlashBladeDataComponents`），所以状态走
 * `BladeStateAccess.of(ItemStack)` 这个静态入口拿 `Optional<ISlashBladeState>`。
 *
 * 【主人免伤】`doSlash` 的伤害来源是 `damageSources().mobAttack(女仆)`（非玩家分支，
 * `AttackHelper.attack` 反编译实证）→ 加害者就是女仆 → 已有的 {@link FriendlyFireGuard}
 * （1.21.1 走 `LivingIncomingDamageEvent`）会拦掉主人/友方，无需新增免伤代码。
 * 另外拔刀剑自身默认 `pvp_enable=false`、`friendly_enable=false`，它的目标筛选器
 * （`TargetSelector$AttackablePredicate`）本来就只打敌对生物，这是第二层保险。
 */
public final class SlashBladeCompat {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final String CLS_ITEM_BLADE =
            "mods.flammpfeil.slashblade.item.ItemSlashBlade";
    private static final String CLS_ATTACK_MANAGER =
            "mods.flammpfeil.slashblade.util.AttackManager";
    private static final String CLS_KNOCKBACKS =
            "mods.flammpfeil.slashblade.util.KnockBacks";
    private static final String CLS_BLADE_STATE_ACCESS =
            "mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess";

    /** 解析只做一次：null = 还没试；NO_VALUE = 试过且不可用 */
    private static volatile Object initState = null;
    private static final Object NO_VALUE = new Object();

    private static Class<?> itemBladeClass;
    private static Method doSlashMethod;
    private static Object smashKnockback;
    private static Method bladeStateOf;
    private static Method setLastActionTime;

    private SlashBladeCompat() {
    }

    /** 兼容层是否可用（装了拔刀剑 + 反射目标齐全）。解析失败会静默降级为 false。 */
    public static boolean isLoaded() {
        Object st = initState;
        if (st == null) {
            synchronized (SlashBladeCompat.class) {
                st = initState;
                if (st == null) {
                    st = resolve() ? Boolean.TRUE : NO_VALUE;
                    initState = st;
                }
            }
        }
        return st == Boolean.TRUE;
    }

    /** 该物品是不是拔刀剑（与 TLM `SlashBladeCompat.isSlashBladeItem` 同判据：instanceof ItemSlashBlade） */
    public static boolean isSlashBlade(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isLoaded()) {
            return false;
        }
        try {
            return itemBladeClass.isInstance(stack.getItem());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 空袭挥刀时的拔刀斩——照搬 TLM 给女仆地面攻击做的那一段（`SlashBladeCompat#swingSlashBlade`
     * 反编译逐点一致），只是不再要求任务 UID 是 `touhou_little_maid:attack`。
     *
     * @return true = 确实触发了拔刀斩（调用方可用于日志/调试）
     */
    public static boolean swingSlash(EntityMaid maid) {
        if (maid == null || !isLoaded()) {
            return false;
        }
        try {
            ItemStack blade = maid.getMainHandItem();
            if (!isSlashBlade(blade)) {
                return false;
            }
            int roll = maid.getRandom().nextInt(60) - 30;
            doSlashMethod.invoke(null, maid, (float) roll, Vec3.ZERO,
                    Boolean.FALSE, Boolean.FALSE, 1.0d, smashKnockback);
            // TLM 同款：记一次 lastActionTime，让拔刀剑自己的 onEntitySwing 不再插手这一刀
            bladeState(blade).ifPresent(state -> {
                try {
                    setLastActionTime.invoke(state, maid.level().getGameTime());
                } catch (Throwable ignored) {
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 内部：反射解析 ---------------- */

    /** 1.21.1 取拔刀剑状态：`BladeStateAccess.of(ItemStack)` → `Optional<ISlashBladeState>`（静态入口） */
    private static Optional<?> bladeState(ItemStack stack) {
        try {
            Object resolved = bladeStateOf.invoke(null, stack);
            return resolved instanceof Optional<?> opt ? opt : Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static boolean resolve() {
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("slashblade")) {
                return false;
            }
            itemBladeClass = Class.forName(CLS_ITEM_BLADE);
            Class<?> knockBacks = Class.forName(CLS_KNOCKBACKS);
            Field smash = knockBacks.getField("smash");
            smashKnockback = smash.get(null);
            doSlashMethod = findDoSlash(Class.forName(CLS_ATTACK_MANAGER), knockBacks);
            bladeStateOf = Class.forName(CLS_BLADE_STATE_ACCESS)
                    .getMethod("of", ItemStack.class);
            Class<?> bladeState = Class.forName(
                    "mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState");
            setLastActionTime = bladeState.getMethod("setLastActionTime", long.class);
            boolean ok = doSlashMethod != null && smashKnockback != null
                    && bladeStateOf != null && setLastActionTime != null;
            if (ok) {
                LOGGER.info("promaid: 拔刀剑兼容已启用（空袭挥刀将触发拔刀斩）");
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 精确定位 7 参那条：`doSlash(LivingEntity, float, Vec3, boolean, boolean, double, KnockBacks)`。
     * 不按参数个数盲选——`AttackManager` 里 doSlash 有 7 个重载，选错会抛
     * `IllegalArgumentException`（静默失败 = 又变成"不适配"）。
     */
    private static Method findDoSlash(Class<?> attackManager, Class<?> knockBacks) {
        for (Method m : attackManager.getMethods()) {
            if (!"doSlash".equals(m.getName()) || m.getParameterCount() != 7) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p[0].isAssignableFrom(EntityMaid.class)
                    && p[2] == Vec3.class
                    && p[6].isAssignableFrom(knockBacks)) {
                return m;
            }
        }
        return null;
    }
}
