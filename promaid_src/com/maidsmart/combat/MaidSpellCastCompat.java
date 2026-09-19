package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 《车万女仆：魔法》（Touhou Little Maid: Spell，modid = touhou_little_maid_spell，
 * 作者 yimeng261）的**软兼容层**——让女仆在空袭过程中顺带释放法术。
 *
 * 【为什么走反射，不编译期依赖】本模组的既定口径是"只保留通用武器联动、不做第三方专属
 * 硬联动"（见 CHANGELOG 里删软联动那两条），已有先例 {@link GunCompat}（枪械）、
 * {@link SlashBladeCompat}（拔刀剑）都是"能调就调、调不到就当没有"。法术模组同理：
 * 没装 / 换版本 / 内部改名都不该让 Promaid 起不来。
 *
 * 【法术模组的施法链路（社区 1.8.4-neoforge 源码级实证）】
 * <pre>
 *   {任务行为}.tick → SpellBookManager#castSpell(EntityMaid)          ← 我们接的就是这一层
 *        → ISpellBookProvider#castSpell(maid)
 *        → IronsSpellbooksProvider.initiateCasting → actualCasting
 *              forceLookAtTarget → setupSpellTargetData → checkPreCastConditions
 *              → magicData.initiateCast(...) → onServerPreCast → setCasting(true)
 * </pre>
 * 我们反射调用的六个方法全部是**公开 API**：
 * {@code SpellBookManager#getOrCreateManager(EntityMaid)} / {@code #getProviders()} /
 * {@code #stopAllCasting()}；{@code ISpellBookProvider#setTarget(EntityMaid, LivingEntity)} /
 * {@code #castSpell(EntityMaid)} / {@code #isCasting(EntityMaid)}。
 * {@code castSpell} 的内部口径：**先滤掉冷却中与黑名单里的法术，再从剩下的里随机挑一个**
 * ——没有距离判定，所以"施法距离"这一道闸必须由调用方自己把关。
 * 续施法（吟唱推进 / 连续施法 / 冷却恢复）**不依赖任务**：法术模组自己挂在 TLM 的
 * {@code MaidTickEvent} 上每 tick 调 {@code SpellBookManager#tick()}，
 * 所以"她的任务是不是法术任务"对施法链路没有影响——我们在自己的空袭任务里调
 * {@code castSpell} 是完整合法用法。
 *
 * 【已实证的三条关键事实】
 * <ol>
 *   <li>**空中无门槛**：整个施法链路没有任何 isFallFlying / onGround / isPassenger
 *       判定——鞘翅滑翔中照样能放；</li>
 *   <li>**不看主手**：ISS 路径只要求女仆身上（背包 / curios 饰品栏 / 主手任一）有
 *       {@code ISpellContainer.isSpellContainer(item)} 为真的物品，法术书放在**饰品栏**
 *       完全没问题（这也是需求方的口径：法术书不是武器、不进武器位）；</li>
 *   <li>**没有法力**：女仆走的是自有 MagicData(false)，ISS 的查蓝与扣蓝都不在这条
 *       链路上；她只剩"法术冷却 + 黑名单"两道闸。</li>
 * </ol>
 *
 * 【我们负责什么、不负责什么】
 * - 不负责：选哪个法术（法术模组从她所有法术书里随机挑一个不在冷却、不在黑名单的）、
 *   吟唱推进、冷却计时、目标数据——全交给它；
 * - 负责：**什么时候发起一次施法**（空袭的相位 + 间隔 + 距离），以及把当前空袭目标
 *   同步给法术模组（{@code setTarget}，它的朝向钉定与目标数据都读这份数据）。
 *
 * 【一个必须知道的副作用】法术模组在吟唱期间**每 tick** 把女仆的朝向拧向目标。
 * 原版鞘翅滑翔的转向力来自视线方向，而 TLM 的 MaidTickEvent 早于 brain tick 发出，
 * 所以**吟唱期间我们的盘旋/爬升朝向会被它覆盖**。空袭这边因此只在"本来就该面向目标"
 * 的相位发起施法（见 {@link MaidFlightCombatBehavior#tryCastSpell}），把这份副作用压到最小。
 */
public final class MaidSpellCastCompat {

    private MaidSpellCastCompat() {
    }

    /** 法术模组 modid（mods.toml 实证） */
    public static final String MOD_ID = "touhou_little_maid_spell";

    private static final String MANAGER_CLASS =
            "com.github.yimeng261.maidspell.spell.manager.SpellBookManager";
    private static final String PROVIDER_CLASS =
            "com.github.yimeng261.maidspell.api.ISpellBookProvider";

    /** 探针结果缓存：null = 还没探过（探针只在第一次调用时跑一次） */
    private static Boolean available;
    private static Method mGetOrCreateManager;
    private static Method mGetProviders;
    private static Method mStopAllCasting;
    private static Method mSetTarget;
    private static Method mCastSpell;
    private static Method mIsCasting;

    /**
     * 法术模组是否可用（装了 + 反射探针拿齐了我们要用的那几个公开方法）。
     * 探针失败一次就永久返回 false，避免每 tick 抛异常刷屏。
     */
    public static boolean available() {
        if (available != null) {
            return available;
        }
        try {
            if (!ModList.get().isLoaded(MOD_ID)) {
                available = Boolean.FALSE;
                return false;
            }
            Class<?> manager = Class.forName(MANAGER_CLASS);
            Class<?> provider = Class.forName(PROVIDER_CLASS);
            // 参数类型都用编译期就有的 TLM/MC 类，反射拿到的 Method 可直接 invoke 任意子类实例
            mGetOrCreateManager = manager.getMethod("getOrCreateManager", EntityMaid.class);
            mGetProviders = manager.getMethod("getProviders");
            mStopAllCasting = manager.getMethod("stopAllCasting");
            mSetTarget = provider.getMethod("setTarget", EntityMaid.class, LivingEntity.class);
            mCastSpell = provider.getMethod("castSpell", EntityMaid.class);
            mIsCasting = provider.getMethod("isCasting", EntityMaid.class);
            available = Boolean.TRUE;
            com.maidsmart.tool.PromaidLog.log("法术兼容",
                    "Touhou Little Maid: Spell 已就绪（空袭可在飞行中顺带施法）");
        } catch (Throwable t) {
            available = Boolean.FALSE;
            com.maidsmart.tool.PromaidLog.log("法术兼容",
                    "Touhou Little Maid: Spell 探针失败，空袭法术层关闭：" + t);
        }
        return available;
    }

    /**
     * 发起一次施法（目标同步 + 交给法术模组自己挑法术）。
     *
     * 【为什么不用它的 SimplifiedSpellCaster.melee_tick/far_tick】源码实证：那两个方法在
     * 放法术之外**还会替女仆挥一记 doHurtTarget**，而空袭的伤害结算有自己的口径
     * （收翅俯冲那一记 + 无敌帧清零）——多出来的这一挥会打乱那套命中判定。
     * 需求也是"用默认武器的同时施法"，所以这里只触发**法术**这一半。
     *
     * @return true = 已经向她下达了施法指令（不代表法术一定成立：法术模组会按
     *         "有没有法术书 / 是不是在吟唱 / 冷却 / 黑名单"自己决定收不收）
     */
    public static boolean castSpell(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !available()) {
            return false;
        }
        try {
            Object manager = mGetOrCreateManager.invoke(null, maid);
            if (manager == null) {
                return false;
            }
            Object providers = mGetProviders.invoke(manager);
            if (providers instanceof List<?> list) {
                for (Object provider : list) {
                    if (provider == null) {
                        continue;
                    }
                    // 目标必须先同步：它的 forceLookAtTarget / setupSpellTargetData 读的都是这份
                    mSetTarget.invoke(provider, maid, target);
                    mCastSpell.invoke(provider, maid);
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 她是不是正在吟唱/施法（空袭据此决定"这轮先别俯冲，让她把法术放完"） */
    public static boolean isCasting(EntityMaid maid) {
        if (maid == null || !available()) {
            return false;
        }
        try {
            Object manager = mGetOrCreateManager.invoke(null, maid);
            Object providers = manager == null ? null : mGetProviders.invoke(manager);
            if (providers instanceof List<?> list) {
                for (Object provider : list) {
                    if (provider != null && Boolean.TRUE.equals(mIsCasting.invoke(provider, maid))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 中止当前施法（法术模组自己的 stopAllCasting：会给她正在吟唱的那个法术
     * 记上冷却，但**不会**结算伤害——即"打断成功、法术作废"）。
     *
     * 空袭不用它做常规让位（吟唱都发生在面向目标的相位），保留给"她必须立刻收翅俯冲"
     * 这类需要抢飞行权的场合（见 MaidFlightCombatBehavior 的用法说明）。
     */
    public static void stopCasting(EntityMaid maid) {
        if (maid == null || !available()) {
            return;
        }
        try {
            Object manager = mGetOrCreateManager.invoke(null, maid);
            if (manager != null) {
                mStopAllCasting.invoke(manager);
            }
        } catch (Throwable ignored) {
        }
    }
}
