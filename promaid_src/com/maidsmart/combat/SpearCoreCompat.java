package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「长矛核心」（spearcore）软兼容（1.20.1 镜像）。
 *
 * <p>spearcore 不在编译类路径上，本类只用原版 API + 注册名/标签；未安装时判据恒 false。
 * 反编译实证：{@code SpearItem.onUseTick(Level, LivingEntity, ...)} 对任意 LivingEntity
 * 生效，自己按速度条件扫实体、结算伤害/击退/音效，所以女仆只需
 * {@code startUsingItem(InteractionHand.MAIN_HAND)}，伤害公式一个字都不用抄。
 */
public final class SpearCoreCompat {

    private static final String SPEAR_NS = "spearcore";
    private static final String SPEAR_SUFFIX = "_spear";
    private static final TagKey<Item> SPEAR_TAG =
            ItemTags.create(new ResourceLocation(SPEAR_NS, "spear"));
    /** {@code computeDamageUseDuration} 的反射缓存（按物品类；只服务端线程调用） */
    private static Class<?> damageEndClass;
    private static java.lang.reflect.Method damageEndMethod;
    /** {@code getDamageMultiplier} 的反射缓存（按物品类；只服务端线程调用） */
    private static Class<?> multiplierClass;
    private static float multiplierValue;
    /** {@code LivingEntity.useItemRemaining}（SRG f_20936_）——按住满蓄力用 */
    private static java.lang.reflect.Field useItemRemaining;

    private SpearCoreCompat() {
    }

    /** 注册名 {@code spearcore:*_spear} → 标签 {@code spearcore:spear} → 类名兜底。 */
    public static boolean isSpear(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            if (id != null && SPEAR_NS.equals(id.m_135827_())
                    && (id.m_135815_().endsWith(SPEAR_SUFFIX) || "spear".equals(id.m_135815_()))) {
                return true;
            }
            if (stack.m_204117_(SPEAR_TAG)) {
                return true;
            }
            String cls = stack.m_41720_().getClass().getName();
            return cls.contains("spearcore.item.") && cls.contains("Spear");
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 女仆此刻是否正在主手蓄力长矛（含长矛模组自己开始的蓄力）。 */
    public static boolean isCharging(EntityMaid maid) {
        try {
            return maid != null && maid.m_6117_()
                    && maid.m_7655_() == InteractionHand.MAIN_HAND
                    && isSpear(maid.m_21211_());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 开始/维持主手长矛蓄力，并补播一次它自己的使用音效；正在吃喝时不抢。 */
    public static boolean startCharge(EntityMaid maid) {
        if (maid == null || !isSpear(maid.m_21205_())) {
            return false;
        }
        try {
            if (maid.m_6117_()) {
                return isCharging(maid);
            }
            // 1.20.1 SRG：m_6672_ = startUsingItem（m_6674_ 是 swing，只挥手臂不举矛）
            maid.m_6672_(InteractionHand.MAIN_HAND);
            boolean ok = isCharging(maid);
            if (ok) {
                playUseSound(maid, maid.m_21205_());
            }
            return ok;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 战斗链续矛：主手是矛且当前没有别的使用动作时才举矛；吃喝时不抢。 */
    public static boolean claimCharge(EntityMaid maid) {
        if (maid == null || !isSpear(maid.m_21205_())) {
            return false;
        }
        try {
            if (maid.m_6117_() && !isCharging(maid)) {
                return false;
            }
            return isCharging(maid) || startCharge(maid);
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 动画闸用：主手是矛且正在使用物品也算蓄力。 */
    public static boolean isChargingForAnimation(EntityMaid maid) {
        try {
            if (maid == null || !maid.m_6117_()) {
                return false;
            }
            return isSpear(maid.m_21211_()) || isSpear(maid.m_21205_());
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 动画闸：当前是否正好处在 spearcore 的伤害结算拍。 */
    public static boolean isDamageSwingFrame(EntityMaid maid) {
        try {
            if (maid == null || !isCharging(maid)) {
                return false;
            }
            Item item = maid.m_21211_().m_41720_();
            java.lang.reflect.Method method = damageEndMethod(item);
            if (method == null) {
                return false;
            }
            int end = (Integer) method.invoke(item);
            int used = maid.m_21252_();
            return end > 0 && used >= end - 2 && used <= end;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return false;
        }
    }

    /** 把"正在使用的那份引用"与主手槽对齐：手里已不是它就立刻收矛。 */
    public static void guardStaleUse(EntityMaid maid) {
        try {
            if (maid != null && maid.m_6117_()
                    && isSpear(maid.m_21211_())
                    && maid.m_21211_() != maid.m_21205_()) {
                maid.m_5810_();
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    /**
     * 把正在蓄力的长矛钉在伤害窗口最后一 tick：spearcore 的
     * {@code getSpearChargeProgress()} 按 {@code useTicks / damageEndTick} 计算，停在末尾即恒为 1.0。
     */
    public static void holdMaxCharge(EntityMaid maid) {
        try {
            if (maid == null || !isCharging(maid)) {
                return;
            }
            ItemStack stack = maid.m_21211_();
            Item item = stack.m_41720_();
            java.lang.reflect.Method method = damageEndMethod(item);
            if (method == null) {
                return;
            }
            int end = (Integer) method.invoke(item);
            int duration = stack.m_41779_();
            int useTicks = end - 1;
            if (duration <= 0 || useTicks <= 0) {
                return;
            }
            int remaining = duration - useTicks;
            if (remaining <= 0 || remaining > duration) {
                return;
            }
            java.lang.reflect.Field f = useItemRemainingField();
            if (f != null) {
                f.setInt(maid, remaining);
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    private static java.lang.reflect.Field useItemRemainingField() {
        try {
            if (useItemRemaining == null) {
                java.lang.reflect.Field f = net.minecraft.world.entity.LivingEntity.class
                        .getDeclaredField("f_20936_");
                f.setAccessible(true);
                useItemRemaining = f;
            }
            return useItemRemaining;
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
            return null;
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

    /** 长矛"移动越快伤害越高"的增伤：与 spearcore {@code onUseTick} 同一口径。 */
    public static float speedDamageBonus(EntityMaid maid, net.minecraft.world.entity.LivingEntity victim,
                                         ItemStack stack) {
        try {
            if (maid == null || victim == null || !isSpear(stack)) {
                return 0.0f;
            }
            net.minecraft.world.phys.Vec3 look = maid.m_20154_();
            net.minecraft.world.phys.Vec3 mine = maid.m_20184_().m_82490_(20.0);
            if (maid.m_20096_()) {
                mine = new net.minecraft.world.phys.Vec3(mine.f_82479_, 0.0, mine.f_82481_);
            }
            net.minecraft.world.phys.Vec3 theirs = victim.m_20184_().m_82490_(20.0);
            if (victim.m_20096_()) {
                theirs = new net.minecraft.world.phys.Vec3(theirs.f_82479_, 0.0, theirs.f_82481_);
            }
            double relative = Math.max(0.0, look.m_82526_(mine) - look.m_82526_(theirs));
            if (relative <= 0.0) {
                return 0.0f;
            }
            float multiplier = damageMultiplier(stack.m_41720_());
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
                maid.m_5810_();
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }

    /** 长矛模组在服务端注册的使用音效（木矛有单独一套，其余用通用音效）。 */
    private static void playUseSound(EntityMaid maid, ItemStack stack) {
        try {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
            String path = id != null && id.m_135815_().startsWith("wooden")
                    ? "item.spear_wood.use" : "item.spear.use";
            SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation(SPEAR_NS, path));
            if (sound != null) {
                maid.m_9236_().m_5594_(null, maid.m_20183_(),
                        sound, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("SpearCoreCompat", ignored);
        }
    }
}
