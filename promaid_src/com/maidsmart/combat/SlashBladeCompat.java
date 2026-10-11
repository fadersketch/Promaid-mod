package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 拔刀剑（SlashBlade: Resharped）兼容层——**全反射，可选模组**。
 *
 * 【根因】① TLM 的拔刀斩触发被硬编码门控在它自己的攻击任务上（{@code swingSlashBlade} 里
 * {@code maid.getTask().getUid().equals(TaskAttack.UID)}）；我们的飞行任务 UID 不是
 * {@code touhou_little_maid:attack} → 恒 false → **空袭里挥刀，拔刀斩一次都不触发**（1.21.1 的 TLM
 * 1.5.3 完全没有拔刀剑兼容代码，本层在那边反而同时补上地面与空袭）。② 我们的命中结算只吃原版
 * {@code ATTACK_DAMAGE}，缺拔刀剑的 {@code SLASHBLADE_DAMAGE} 倍率。
 * 【改法】在**我们自己**的挥刀点复刻 TLM 给地面攻击补的那一下——{@code AttackManager.doSlash(maid,
 * roll, ZERO, false, false, comboRatio=1.0, KnockBacks.smash)} + 写 BLADESTATE 动作时间。
 * {@code doSlash} 生成的 {@code EntitySlashEffect} 自己按完整公式结算伤害，② 的缺口被同一调用补上——
 * **不要**再往 {@code hitOne} 里乘倍率（否则叠乘超模）。
 * 【必须反射】classpath 无 slashblade，直接 import 编译不过；{@code Class.forName}+缓存，缺模组时
 * {@code isLoaded()} 一票否决。【主人免伤】伤害源是女仆，{@link FriendlyFireGuard} 已拦掉主人/友方。
 */
public final class SlashBladeCompat {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final String CLS_ITEM_BLADE =
            "mods.flammpfeil.slashblade.item.ItemSlashBlade";
    private static final String CLS_ATTACK_MANAGER =
            "mods.flammpfeil.slashblade.util.AttackManager";
    private static final String CLS_KNOCKBACKS =
            "mods.flammpfeil.slashblade.util.KnockBacks";
    private static final String CLS_CAPABILITY_BLADE =
            "mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade";

    /** 解析只做一次：null = 还没试；NO_VALUE = 试过且不可用 */
    private static volatile Object initState = null;
    private static final Object NO_VALUE = new Object();

    private static Class<?> itemBladeClass;
    private static Method doSlashMethod;
    private static Object smashKnockback;
    private static Method itemGetCapability;
    private static Method lazyResolve;
    private static Method setLastActionTime;
    /** {@code CapabilitySlashBlade.BLADESTATE} 那个 capability 对象：解析一次存下，别每次挥刀再 Class.forName（TD-15）。 */
    private static Object bladeStateCapability;

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
        if (stack == null || stack.m_41619_() || !isLoaded()) {
            return false;
        }
        try {
            return itemBladeClass.isInstance(stack.m_41720_());
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SlashBladeCompat", ignored);
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
            ItemStack blade = maid.m_21205_();
            if (!isSlashBlade(blade)) {
                return false;
            }
            int roll = maid.m_217043_().m_188503_(60) - 30;
            doSlashMethod.invoke(null, maid, (float) roll, Vec3.f_82478_,
                    Boolean.FALSE, Boolean.FALSE, 1.0d, smashKnockback);
            // TLM 同款：记一次 lastActionTime，让拔刀剑自己的 onEntitySwing 不再插手这一刀
            bladeState(blade).ifPresent(state -> {
                try {
                    setLastActionTime.invoke(state, maid.m_9236_().m_46467_());
                } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SlashBladeCompat", ignored);
                }
            });
            return true;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SlashBladeCompat", ignored);
            return false;
        }
    }

    /* ---------------- 内部：反射解析 ---------------- */

    /** 1.20.1 取拔刀剑状态：ItemStack.getCapability(BLADESTATE) → LazyOptional.resolve() → Optional */
    private static Optional<?> bladeState(ItemStack stack) {
        if (bladeStateCapability == null) {
            return Optional.empty();
        }
        try {
            Object lazy = itemGetCapability.invoke(stack, bladeStateCapability);
            if (lazy == null) {
                return Optional.empty();
            }
            Object resolved = lazyResolve.invoke(lazy);
            return resolved instanceof Optional<?> opt ? opt : Optional.empty();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SlashBladeCompat", ignored);
            return Optional.empty();
        }
    }

    private static boolean resolve() {
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("slashblade")) {
                return false;
            }
            itemBladeClass = Class.forName(CLS_ITEM_BLADE);
            Class<?> knockBacks = Class.forName(CLS_KNOCKBACKS);
            Field smash = knockBacks.getField("smash");
            smashKnockback = smash.get(null);
            doSlashMethod = findDoSlash(Class.forName(CLS_ATTACK_MANAGER), knockBacks);
            Class<?> capability = Class.forName(
                    "net.minecraftforge.common.capabilities.Capability");
            itemGetCapability = ItemStack.class.getMethod("getCapability", capability);
            lazyResolve = Class.forName("net.minecraftforge.common.util.LazyOptional")
                    .getMethod("resolve");
            Class<?> bladeState = Class.forName(
                    "mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState");
            setLastActionTime = bladeState.getMethod("setLastActionTime", long.class);
            bladeStateCapability = Class.forName(CLS_CAPABILITY_BLADE).getField("BLADESTATE").get(null);
            boolean ok = doSlashMethod != null && smashKnockback != null
                    && itemGetCapability != null && lazyResolve != null && setLastActionTime != null
                    && bladeStateCapability != null;
            if (ok) {
                LOGGER.info("promaid: 拔刀剑兼容已启用（空袭挥刀将触发拔刀斩）");
            }
            return ok;
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SlashBladeCompat", ignored);
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
