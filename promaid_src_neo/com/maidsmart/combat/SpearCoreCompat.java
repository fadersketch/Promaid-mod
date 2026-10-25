package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「长矛核心」（spearcore）软兼容：近战空袭主手长矛时改走它自己的蓄力/冲锋结算。
 *
 * <p>spearcore 不在编译类路径上，本类只用原版 API + 注册名/标签；未安装时判据恒 false。
 * 反编译实证：{@code SpearItem.onUseTick(Level, LivingEntity, ...)} 对任意 LivingEntity
 * 生效，自己按速度条件扫实体、结算伤害/击退/音效，所以女仆只需
 * {@link net.minecraft.world.entity.LivingEntity#startUsingItem(InteractionHand)}，
 * 伤害公式一个字都不用抄。
 */
public final class SpearCoreCompat {

    private static final String SPEAR_NS = "spearcore";
    private static final String SPEAR_SUFFIX = "_spear";
    private static final TagKey<Item> SPEAR_TAG =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(SPEAR_NS, "spear"));
    /** {@code computeDamageUseDuration} 的反射缓存（按物品类；只服务端线程调用） */
    private static Class<?> damageEndClass;
    private static java.lang.reflect.Method damageEndMethod;
    /** {@code getDamageMultiplier} 的反射缓存（按物品类；只服务端线程调用） */
    private static Class<?> multiplierClass;
    private static float multiplierValue;

    private SpearCoreCompat() {
    }

    /** 注册名 {@code spearcore:*_spear} → 标签 {@code spearcore:spear} → 类名兜底。 */
    public static boolean isSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && SPEAR_NS.equals(id.getNamespace())
                    && (id.getPath().endsWith(SPEAR_SUFFIX) || "spear".equals(id.getPath()))) {
                return true;
            }
            if (stack.is(SPEAR_TAG)) {
                return true;
            }
            String cls = stack.getItem().getClass().getName();
            return cls.contains("spearcore.item.") && cls.contains("Spear");
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 女仆此刻是否正在主手蓄力长矛（含长矛模组自己开始的蓄力）。 */
    public static boolean isCharging(EntityMaid maid) {
        try {
            return maid != null && maid.isUsingItem()
                    && maid.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && isSpear(maid.getUseItem());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 开始/维持主手长矛蓄力，并补播一次它自己的使用音效；正在吃喝时不抢。 */
    public static boolean startCharge(EntityMaid maid) {
        if (maid == null || !isSpear(maid.getMainHandItem())) {
            return false;
        }
        try {
            if (maid.isUsingItem()) {
                return isCharging(maid);
            }
            maid.startUsingItem(InteractionHand.MAIN_HAND);
            boolean ok = isCharging(maid);
            if (ok) {
                playUseSound(maid, maid.getMainHandItem());
            }
            return ok;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /**
     * 战斗链续矛：主手是矛且当前没有别的使用动作时才举矛。
     *
     * <p>【实测一千〇二十五】冲锋是并行链路，不抢占吃喝：她正在吃东西/喝药时这里直接返回
     * false，让原版使用动作正常走完；结束后的下一 tick 调用方会再次进来，自动续上满蓄力。
     * 绝不 {@code stopUsingItem()} 把饭碗抢掉。
     */
    public static boolean claimCharge(EntityMaid maid) {
        if (maid == null || !isSpear(maid.getMainHandItem())) {
            return false;
        }
        try {
            if (maid.isUsingItem() && !isCharging(maid)) {
                return false;
            }
            return isCharging(maid) || startCharge(maid);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /**
     * 动画闸用：客户端不保证 {@code useItem} 栈已同步，所以主手是矛且正在使用物品
     * 也算蓄力。只影响挥臂动画，不参与伤害/状态判定。
     */
    public static boolean isChargingForAnimation(EntityMaid maid) {
        try {
            if (maid == null || !maid.isUsingItem()) {
                return false;
            }
            return isSpear(maid.getUseItem()) || isSpear(maid.getMainHandItem());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /**
     * 动画闸：当前是否正好处在 spearcore 的伤害结算拍。
     * 只给 {@code swing(hand, true)}（spearcore 在命中循环里自己调的那一记）放行，
     * TLM 的一参平A挥臂仍然全程吞掉——玩家要的就是"只有打中那一下有动作"。
     */
    public static boolean isDamageSwingFrame(EntityMaid maid) {
        try {
            if (maid == null || !isCharging(maid)) {
                return false;
            }
            Item item = maid.getUseItem().getItem();
            java.lang.reflect.Method method = damageEndMethod(item);
            if (method == null) {
                return false;
            }
            int end = (Integer) method.invoke(item);
            int used = maid.getTicksUsingItem();
            return end > 0 && used >= end - 2 && used <= end;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /**
     * 【实测一千〇二十】把"正在使用的那份引用"与主手槽对齐：原版 {@code startUsingItem}
     * 把 {@code useItem} 直接指向槽里的 ItemStack 对象（javap 实证，没有 copy）；玩家把矛
     * 拿走/换掉后槽已变、{@code useItem} 仍指着旧对象（视觉上"取不下来"、耐久还扣玩家
     * 背包那份）。每 tick 对一次引用：手里已不是它就立刻收矛。
     */
    public static void guardStaleUse(EntityMaid maid) {
        try {
            if (maid != null && maid.isUsingItem()
                    && isSpear(maid.getUseItem())
                    && maid.getUseItem() != maid.getMainHandItem()) {
                maid.stopUsingItem();
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    /**
     * 把正在蓄力的长矛钉在伤害窗口最后一 tick：spearcore 的 {@code getSpearChargeProgress()}
     * 按 {@code useTicks / damageEndTick} 计算，停在末尾即恒为 1.0；也不会触发它在
     * {@code damageEndTick} 自己调用的 {@code stopUsingItem()}。不是长矛蓄力时什么都不做。
     */
    public static void holdMaxCharge(EntityMaid maid) {
        try {
            if (maid == null || !isCharging(maid)) {
                return;
            }
            ItemStack stack = maid.getUseItem();
            Item item = stack.getItem();
            java.lang.reflect.Method method = damageEndMethod(item);
            if (method == null) {
                return;
            }
            int end = (Integer) method.invoke(item);
            int duration = stack.getUseDuration(maid);
            int useTicks = end - 1;
            if (duration <= 0 || useTicks <= 0) {
                return;
            }
            int remaining = duration - useTicks;
            if (remaining <= 0 || remaining > duration) {
                return;
            }
            ((com.maidsmart.mixin.LivingEntityUseItemAccessor) (Object) maid)
                    .promaid$setUseItemRemaining(remaining);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    private static java.lang.reflect.Method damageEndMethod(Item item) {
        try {
            Class<?> type = item.getClass();
            if (type == damageEndClass) {
                return damageEndMethod;
            }
            java.lang.reflect.Method method = type.getMethod("computeDamageUseDuration");
            damageEndClass = type;
            damageEndMethod = method;
            return method;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            damageEndClass = item.getClass();
            damageEndMethod = null;
            return null;
        }
    }

    /**
     * 【R1042】长矛"移动越快伤害越高"的增伤：与 spearcore {@code onUseTick} 同一口径。
     * 它自身的公式是 {@code 基础 + floor(沿视线相对速度 × getDamageMultiplier())}；本模组的
     * 路径/落地强制命中走 {@code hitOne}，绕过了它，通常链路吃不到这一份，这里补回来。
     * 相对速度单位与它一致：{@code 视线 · (速度 × 20)}；落地的一方不计竖直分量。
     * 反射失败（未装 / 版本不符）返回 0，不影响这一记的其余伤害。
     */
    public static float speedDamageBonus(EntityMaid maid, net.minecraft.world.entity.LivingEntity victim,
                                         ItemStack stack) {
        try {
            if (maid == null || victim == null || !isSpear(stack)) {
                return 0.0f;
            }
            net.minecraft.world.phys.Vec3 look = maid.getLookAngle();
            net.minecraft.world.phys.Vec3 mine = maid.getDeltaMovement().scale(20.0);
            if (maid.onGround()) {
                mine = new net.minecraft.world.phys.Vec3(mine.x, 0.0, mine.z);
            }
            net.minecraft.world.phys.Vec3 theirs = victim.getDeltaMovement().scale(20.0);
            if (victim.onGround()) {
                theirs = new net.minecraft.world.phys.Vec3(theirs.x, 0.0, theirs.z);
            }
            double relative = Math.max(0.0, look.dot(mine) - look.dot(theirs));
            if (relative <= 0.0) {
                return 0.0f;
            }
            float multiplier = damageMultiplier(stack.getItem());
            return (float) Math.floor(relative * multiplier);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return 0.0f;
        }
    }

    private static float damageMultiplier(Item item) {
        try {
            if (item.getClass() == multiplierClass) {
                return multiplierValue;
            }
            java.lang.reflect.Method method = item.getClass().getMethod("getDamageMultiplier");
            float value = (Float) method.invoke(item);
            multiplierClass = item.getClass();
            multiplierValue = value;
            return value;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            multiplierClass = item.getClass();
            multiplierValue = 0.0f;
            return 0.0f;
        }
    }

    /** 只结束「主手长矛蓄力」，不碰吃东西/喝药等其它使用状态。 */
    public static void stopCharge(EntityMaid maid) {
        try {
            if (isCharging(maid)) {
                maid.stopUsingItem();
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    /** 长矛模组在服务端注册的使用音效（木矛有单独一套，其余用通用音效）。 */
    private static void playUseSound(EntityMaid maid, ItemStack stack) {
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String path = id != null && id.getPath().startsWith("wooden")
                    ? "item.spear_wood.use" : "item.spear.use";
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(
                    ResourceLocation.fromNamespaceAndPath(SPEAR_NS, path));
            if (sound != null) {
                maid.level().playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                        sound, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }
}
