package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 《车万女仆：万法皆通》（Touhou Little Maid: Spell，modid = touhou_little_maid_spell，
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
            // 实测五百七十五【版本差异兜底，必须放在 try 里单独吞】：插件包 1.21.1-1.8.4 是
            // `stopAllCasting()`（无参），1.20.1-1.8.2 是 `stopAllCasting(EntityMaid)`。
            // 旧版探针写死无参 → 1.20.1 上直接 NoSuchMethodException → **整层（顺带施法 +
            // 位移法术）被一起关掉**（用户实测现场："饰品栏装着烈焰冲锋的书也不起飞"，
            // 日志里就一行「探针失败，空袭法术层关闭」）。现在两种签名都认；都没有时
            // 只把"中止施法"降级为不可用（那是个可选能力），绝不拖垮整层。
            mStopAllCasting = null;
            try {
                mStopAllCasting = manager.getMethod("stopAllCasting");
            } catch (NoSuchMethodException e1) {
                try {
                    mStopAllCasting = manager.getMethod("stopAllCasting", EntityMaid.class);
                } catch (NoSuchMethodException ignored) {
                    // 两版都没有 → 中止施法不可用（其余能力照常）
                }
            }
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

    // ================= v1.2.0 实测五百七十二：位移类法术（冲刺加速 / 平地起飞） =================
    //
    // 【需求】位移类法术（如铁魔法的 `irons_spellbooks:burning_dash`「烈焰冲锋」）也用起来：
    // ① **飞行途中加速**；② **平地起飞**（没有烟花也能上天）。
    //
    // 【机制（ISS 源码实证，两条都是 CastType.INSTANT，直接改施法者速度）】
    // - `BurningDashSpell#onCast`：`entity.setDeltaMovement(旧速度 + 前向脉冲)`——**沿视线方向**冲刺；
    // - `AscensionSpell#onCast`：`motion = 视线水平分量 + (0,5,0)`（再 ×0.125）加进速度——**给向上初速**。
    // 也就是说：法术自己会推她，我们只负责"什么时候放、放之前把朝向摆对"。
    //
    // 【为什么不用 castSpell（随机法术）】那条路径从她书里**随机**挑一个不在冷却的法术，
    // 拿来当"冲刺"用不成立（可能放出一个火球）。所以这里走**指定法术**的施法路径——
    // 与法术模组自己的 `/maidspell iron_cast <spell> [level]` 命令同一套序列（见
    // `MaidSpellCommand#castSpellOnMaid`），只是我们全程走反射保持软依赖。
    //
    // 【一个必须知道的坑】法术模组的 `actualCasting` 会先 `forceLookAtTarget`（把朝向拧向
    // 它的 data.target）再施法。**起飞那一枪会因此被掰平**（`AscensionSpell` 取的是
    // `getLookAngle()`，朝向一平就变成了"向前扑"而不是"窜上天"）。所以起飞前我们
    // **把它的目标清空**（`setTarget(maid, null)` → `forceLookAtTarget` 里 target==null 直接跳过），
    // 自己把俯仰角摆到抬头；冲刺那一枪则照常让它对着目标（方向一致，不冲突）。

    /** v1.2.0 实测五百七十二：「提供高度」的位移法术默认表——用于**起飞**与**补高**。
     *  ascension（升腾）自带 80 tick 悬浮效果，是"平地起飞并爬上去"的主力；
     *  burning_dash（烈焰冲锋）沿视线冲刺且垂直分量保留、站地上时还会先抬高 1.5 格，
     *  所以**抬头瞄着放也能顶一下**（但只跳得动一下，没有持续升力）——放进来是为了
     *  "只带烈焰冲锋的女仆也能起飞"（默认表顺序 = 优先级，见 findDashSpell）。 */
    public static final String[] DEFAULT_CLIMB_SPELLS = {
            "irons_spellbooks:ascension",
            "irons_spellbooks:burning_dash",
    };
    /** v1.2.0 实测五百七十二：「提供速度」的位移法术默认表——用于**飞行加速**（沿视线冲刺） */
    public static final String[] DEFAULT_BOOST_SPELLS = {
            "irons_spellbooks:burning_dash",
    };
    /**
     * 位移法术的**兜底等级**——只在读不到书里铭刻等级时使用（正常路径见
     * {@link #spellLevelInBooks}）。
     *
     * 【纠错：等级确实影响冲量】本常量旁边原来写着"1 级足够、等级只影响伤害"——**那是错的**：
     * 烈焰冲锋的冲量系数就是 `(15 + 法术强度) / 12`（1 级 1.33、10 级 2.08），
     * 升腾的强度也进它自己的公式。所以正常路径一律按书里铭刻的等级放，这个常量只是兜底。
     */
    public static final int DASH_SPELL_FALLBACK_LEVEL = 1;

    private static Class<?> cMaidData;
    private static Method mMaidDataGetOrCreate;
    private static Method mGetSpellBooks;
    private static Method mDataSetTarget;
    private static Method mDataSetCasting;
    private static Method mDataSetSpellCooldown;
    private static Method mDataIsSpellOnCooldown;
    private static Method mDataSetCurrentCastingSpell;
    private static Method mDataSetCachedCastSource;
    private static Method mDataResetCastingState;
    private static Method mDataGetMagicData;
    private static Method mSpellRegistryGetSpell;
    private static Method mContainerGet;
    private static Method mContainerGetActiveSpells;
    private static Method mSlotGetSpell;
    private static Method mSlotGetLevel;
    private static Method mSpellGetId;
    private static Method mSpellCheckPreCast;
    private static Method mSpellEffectiveCastTime;
    private static Method mSpellOnServerPreCast;
    private static Method mSpellOnCast;
    private static Method mSpellOnServerCastComplete;
    private static Method mMagicInitiateCast;
    private static java.lang.reflect.Constructor<?> cSpellData;
    private static java.lang.reflect.Constructor<?> cSpellSlot;
    private static Object oCastSourceCommand;

    /** 位移法术所需的额外反射句柄（与主探针分开，缺了只关掉冲刺、不影响普通施法） */
    private static Boolean dashReady;

    private static boolean dashAvailable() {
        if (dashReady != null) {
            return dashReady;
        }
        try {
            if (!available()) {
                dashReady = Boolean.FALSE;
                return false;
            }
            cMaidData = Class.forName("com.github.yimeng261.maidspell.spell.data.MaidIronsSpellData");
            Class<?> cData = Class.forName("com.github.yimeng261.maidspell.api.IMaidSpellData");
            Class<?> cContainer = Class.forName("io.redspace.ironsspellbooks.api.spells.ISpellContainer");
            Class<?> cSlot = Class.forName("io.redspace.ironsspellbooks.api.spells.SpellSlot");
            Class<?> cSpell = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            Class<?> cMagic = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
            Class<?> cRegistry = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Class<?> cCastSource = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
            Class<?> spellDataCls = Class.forName("io.redspace.ironsspellbooks.api.spells.SpellData");

            mMaidDataGetOrCreate = cMaidData.getMethod("getOrCreate", EntityMaid.class);
            mGetSpellBooks = cData.getMethod("getSpellBooks");
            mDataSetTarget = cData.getMethod("setTarget", LivingEntity.class);
            mDataSetCasting = cData.getMethod("setCasting", boolean.class);
            mDataSetSpellCooldown = cData.getMethod("setSpellCooldown", String.class, int.class, EntityMaid.class);
            mDataIsSpellOnCooldown = cData.getMethod("isSpellOnCooldown", String.class);
            mDataGetMagicData = cMaidData.getMethod("getMagicData");
            // 实测五百七十五【同款版本差异】：1.21.1-1.8.4 是
            // `setCurrentCastingSpell(SpellSlot)`，1.20.1-1.8.2 是
            // `setCurrentCastingSpell(SpellData)`——两种都认（调用侧按形参类型传对对象，
            // 见 castSpecific；SpellSlot 在 1.20.1 依旧存在，读她书单槽位还要用）。
            try {
                mDataSetCurrentCastingSpell = cMaidData.getMethod("setCurrentCastingSpell", cSlot);
            } catch (NoSuchMethodException e1) {
                mDataSetCurrentCastingSpell = cMaidData.getMethod("setCurrentCastingSpell", spellDataCls);
            }
            mDataSetCachedCastSource = cMaidData.getMethod("setCachedCastSource", cCastSource);
            mDataResetCastingState = cMaidData.getMethod("resetCastingState");
            mSpellRegistryGetSpell = cRegistry.getMethod("getSpell", net.minecraft.resources.ResourceLocation.class);
            mContainerGet = cContainer.getMethod("get", net.minecraft.world.item.ItemStack.class);
            mContainerGetActiveSpells = cContainer.getMethod("getActiveSpells");
            mSlotGetSpell = cSlot.getMethod("getSpell");
            mSlotGetLevel = cSlot.getMethod("getLevel");
            mSpellGetId = cSpell.getMethod("getSpellId");
            mSpellCheckPreCast = cSpell.getMethod("checkPreCastConditions", net.minecraft.world.level.Level.class,
                    int.class, LivingEntity.class, cMagic);
            mSpellEffectiveCastTime = cSpell.getMethod("getEffectiveCastTime", int.class, LivingEntity.class);
            mSpellOnServerPreCast = cSpell.getMethod("onServerPreCast", net.minecraft.world.level.Level.class,
                    int.class, LivingEntity.class, cMagic);
            mSpellOnCast = cSpell.getMethod("onCast", net.minecraft.world.level.Level.class, int.class,
                    LivingEntity.class, cCastSource, cMagic);
            mSpellOnServerCastComplete = cSpell.getMethod("onServerCastComplete",
                    net.minecraft.world.level.Level.class, int.class, LivingEntity.class, cMagic, boolean.class);
            mMagicInitiateCast = cMagic.getMethod("initiateCast", cSpell, int.class, int.class, cCastSource,
                    String.class);
            cSpellData = spellDataCls.getConstructor(cSpell, int.class);
            cSpellSlot = cSlot.getConstructor(spellDataCls, int.class);
            oCastSourceCommand = Enum.valueOf((Class) cCastSource, "COMMAND");
            dashReady = Boolean.TRUE;
            com.maidsmart.tool.PromaidLog.log("法术兼容", "位移法术（冲刺/起飞）反射链就绪");
        } catch (Throwable t) {
            dashReady = Boolean.FALSE;
            com.maidsmart.tool.PromaidLog.log("法术兼容", "位移法术反射链不可用：" + t);
        }
        return dashReady;
    }


    // ── v1.2.0 实测五百七十二：法术自身冷却 + 书里铭刻的等级 ──

    private static Method mSpellGetCooldown;

    /**
     * 某个法术**自身**的冷却（tick）——读 ISS 的 `AbstractSpell#getSpellCooldown()`。
     *
     * 【为什么要它】"提供速度/提供高度"这两类法术在原版都是有冷却的输出手段（烈焰冲锋 10 秒、
     * 升腾 15 秒）；写回冷却时若只按我们的间隔（默认 2 秒）写，等于让她比玩家频繁好几倍。
     * 默认口径 `max(空袭位移间隔, 法术自身冷却)`；想让她窜得更勤可关掉"尊重法术自身冷却"。
     *
     * v1.2.2 实测五百九十七（issues #16）【单处换算修正，无行为分支变化】：旧版这里
     * 把它的返回值当成"秒"、又乘了一次 20（`Math.max(0, i) * 20`）——而 ISS 的
     * `AbstractSpell#getSpellCooldown()` **返回的本来就是 tick**：
     * `(int) (SpellConfigManager.getSpellConfigValue(this, COOLDOWN_IN_SECONDS) * 20)`。
     * 于是烈焰冲锋（真实 10 秒 = 200 tick）被写成 **4000 tick = 200 秒**，"提供速度"
     * 第一次放过之后两百秒再也不放（面板与日志都看不出来，像是"法术推进偶尔不工作"）；
     * 这个错误值还会被 `castSpecific` 写回 ISS 冷却表，并让 `findAvailableDashSpell(..., ready)`
     * 长时间挑不到它。报告者实测：改回原样返回后同一条日志从"每 4000 tick 一记"变成
     * "每 200 tick 一记"。现在原样返回。
     *
     * @return 冷却 tick；读不到返回 0（调用方按自己的间隔处理）
     */
    public static int spellCooldownTicks(String spellId) {
        if (spellId == null || !dashAvailable()) {
            return 0;
        }
        try {
            if (mSpellGetCooldown == null) {
                mSpellGetCooldown = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell")
                        .getMethod("getSpellCooldown");
            }
            Object spell = mSpellRegistryGetSpell.invoke(null,
                    new net.minecraft.resources.ResourceLocation(spellId));
            if (spell == null) {
                return 0;
            }
            Object ticks = mSpellGetCooldown.invoke(spell); // 已经是 tick（见方法头 实测五百九十七）
            if (ticks instanceof Integer i) {
                return Math.max(0, i);
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 她书里那个法术**铭刻的等级**（找不到返回 0）。
     *
     * 【为什么要按铭刻等级放】ISS 位移法术的推力随等级走（烈焰冲锋的冲量系数 =
     * `(15 + 法术强度) / 12`：1 级 1.33、10 级 2.08），原先一律按 1 级施法等于把玩家
     * 升级过的法术书降级用。现在按书里实际等级放，与玩家自己施法一致。
     */
    public static int spellLevelInBooks(EntityMaid maid, String spellId) {
        if (maid == null || spellId == null || !dashAvailable()) {
            return 0;
        }
        try {
            Object data = mMaidDataGetOrCreate.invoke(null, maid);
            Object books = data == null ? null : mGetSpellBooks.invoke(data);
            if (!(books instanceof java.util.List<?> list)) {
                return 0;
            }
            for (Object book : list) {
                if (!(book instanceof net.minecraft.world.item.ItemStack stack) || stack.m_41619_()) {
                    continue;
                }
                Object container = mContainerGet.invoke(null, stack);
                Object slots = container == null ? null : mContainerGetActiveSpells.invoke(container);
                if (!(slots instanceof java.util.List<?> slotList)) {
                    continue;
                }
                for (Object slot : slotList) {
                    if (slot == null) {
                        continue;
                    }
                    Object spell = mSlotGetSpell.invoke(slot);
                    if (spell == null || !spellId.equals(mSpellGetId.invoke(spell))) {
                        continue;
                    }
                    Object lvl = mSlotGetLevel.invoke(slot);
                    if (lvl instanceof Integer i && i > 0) {
                        return i;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 位移法术（冲刺/起飞）是否可用 */
    public static boolean dashUsable() {
        return dashAvailable();
    }

    /**
     * 在她书里找一个"可用的"位移法术（在候选列表里、且不在冷却）——找不到返回 null。
     * 注意这里读的是**法术模组维护的书单**（`IMaidSpellData#getSpellBooks`），
     * 与它自己施法时看的是同一份，所以"她带了这本书"与"它认这本书"永远一致。
     */
    public static String findAvailableDashSpell(EntityMaid maid, String[] candidates) {
        return findDashSpell(maid, candidates, true);
    }

    /**
     * v1.2.0 实测五百七十二【起飞/补高专用】：找"提供高度"里能用的法术，**不看它的冷却**。
     *
     * 依据：本模组对位移手段一向让女仆比玩家宽松——激流三叉戟那一套就是忽略原版
     * "必须在水中/雨中"的限制；而"平地起飞"要求她没烟花也能持续飞（若卡 15 秒冷却，
     * 升腾一记只抬约 6 格后缓降，需求等于没满足）。空中**冲刺加速**那一类仍然尊重冷却。
     */
    public static String findClimbSpellIgnoringCooldown(EntityMaid maid, String[] candidates) {
        return findDashSpell(maid, candidates, false);
    }

    /**
     * v1.2.0 实测五百七十二【套件判定专用】：她书里**有没有**起飞法术——**不看冷却**。
     *
     * 【为什么必须分开】"有没有这件装备"与"现在能不能放"是两件事：
     * 套件判定若把冷却中的法术算作"缺件"，那么每次冲刺（写回 2 秒冷却）之后模式都会
     * 掉回未激活——她会当场停掉空袭、顺惯性飘走（本地实测就是这个现象）。
     */
    public static boolean hasDashSpell(EntityMaid maid, String[] candidates) {
        return findDashSpell(maid, candidates, false) != null;
    }

    private static String findDashSpell(EntityMaid maid, String[] candidates, boolean requireReady) {
        if (maid == null || candidates == null || candidates.length == 0 || !dashAvailable()) {
            return null;
        }
        try {
            Object data = mMaidDataGetOrCreate.invoke(null, maid);
            if (data == null) {
                return null;
            }
            Object books = mGetSpellBooks.invoke(data);
            if (!(books instanceof java.util.List<?> list)) {
                return null;
            }
            for (Object book : list) {
                if (!(book instanceof net.minecraft.world.item.ItemStack stack) || stack.m_41619_()) {
                    continue;
                }
                Object container = mContainerGet.invoke(null, stack);
                if (container == null) {
                    continue;
                }
                Object slots = mContainerGetActiveSpells.invoke(container);
                if (!(slots instanceof java.util.List<?> slotList)) {
                    continue;
                }
                // 【顺序口径】外层走**候选表**、内层走她的书单槽位 —— "配置表里排前面的优先"。
                // 旧版按槽位顺序返回，结果"提供高度"表里排第一的升腾永远被书里排在前面的
                // 烈焰冲锋抢掉（实测症状：她只跳 1 格、爬不上去）。
                for (String want : candidates) {
                    if (want == null) {
                        continue;
                    }
                    for (Object slot : slotList) {
                        if (slot == null) {
                            continue;
                        }
                        Object spell = mSlotGetSpell.invoke(slot);
                        if (spell == null) {
                            continue;
                        }
                        Object idObj = mSpellGetId.invoke(spell);
                        if (!(idObj instanceof String id) || !want.equals(id)) {
                            continue;
                        }
                        if (requireReady && Boolean.TRUE.equals(mDataIsSpellOnCooldown.invoke(data, id))) {
                            continue; // 只在"真要放"时看冷却（套件判定不看，见 hasDashSpell 注释）
                        }
                        return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 放一个**指定**法术（瞬发路径）——与法术模组的 `/maidspell iron_cast` 同一序列。
     *
     * 不在施法前设目标（`IMaidSpellData#setTarget` 由调用方决定）：起飞那一枪必须让她的
     * 视线朝上，一旦有目标，它施法前会把朝向拧平（见类注释里那个坑）。
     *
     * @param cooldownTicks 写回它自己的冷却表（避免与我们这边节流打架；失败忽略）
     * @return true = 已施放（不代表法术一定产生位移：由所放法术自己决定）
     */
    public static boolean castSpecific(EntityMaid maid, String spellId, int level, int cooldownTicks) {
        if (maid == null || spellId == null || !dashAvailable()) {
            return false;
        }
        try {
            Object data = mMaidDataGetOrCreate.invoke(null, maid);
            if (data == null) {
                return false;
            }
            Object magic = mDataGetMagicData.invoke(data);
            Object spell = mSpellRegistryGetSpell.invoke(null, new net.minecraft.resources.ResourceLocation(spellId));
            if (spell == null) {
                return false;
            }
            if (!Boolean.TRUE.equals(mSpellCheckPreCast.invoke(spell, maid.m_9236_(), level, maid, magic))) {
                return false; // 前置条件不过（本组法术恒 true，失败说明环境不允许）
            }
            int castTime = (Integer) mSpellEffectiveCastTime.invoke(spell, level, maid);
            mMagicInitiateCast.invoke(magic, spell, level, castTime, oCastSourceCommand, "offhand");
            mSpellOnServerPreCast.invoke(spell, maid.m_9236_(), level, maid, magic);
            Object spellData = cSpellData.newInstance(spell, level);
            // 实测五百七十五：按目标方法的形参类型决定传什么——1.21.1 要 SpellSlot(spellData, 0)，
            // 1.20.1 要裸 SpellData（它的 setter 就收这个）。
            Object slotArg = mDataSetCurrentCastingSpell.getParameterTypes()[0].isInstance(spellData)
                    ? spellData : cSpellSlot.newInstance(spellData, 0);
            mDataSetCurrentCastingSpell.invoke(data, slotArg);
            mDataSetCachedCastSource.invoke(data, oCastSourceCommand);
            mDataSetCasting.invoke(data, true);
            // 位移法术都是 INSTANT：立刻结算，不留吟唱
            mSpellOnCast.invoke(spell, maid.m_9236_(), level, maid, oCastSourceCommand, magic);
            mSpellOnServerCastComplete.invoke(spell, maid.m_9236_(), level, maid, magic, false);
            mDataResetCastingState.invoke(data);
            try {
                mDataSetSpellCooldown.invoke(data, spellId, cooldownTicks, maid);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清掉法术模组那份"施法目标"（起飞前用：让它的 forceLookAtTarget 不再掰朝向） */
    public static boolean clearCastTarget(EntityMaid maid) {
        if (maid == null || !available()) {
            return false;
        }
        try {
            Object manager = mGetOrCreateManager.invoke(null, maid);
            Object providers = manager == null ? null : mGetProviders.invoke(manager);
            if (providers instanceof java.util.List<?> list) {
                for (Object provider : list) {
                    if (provider != null) {
                        mSetTarget.invoke(provider, maid, null);
                    }
                }
                return true;
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
        if (maid == null || !available() || mStopAllCasting == null) {
            return; // 两版签名都没探到 → 该能力不可用（静默降级）
        }
        try {
            Object manager = mGetOrCreateManager.invoke(null, maid);
            if (manager != null) {
                if (mStopAllCasting.getParameterCount() == 0) {
                    mStopAllCasting.invoke(manager);            // 1.21.1：stopAllCasting()
                } else {
                    mStopAllCasting.invoke(manager, maid);      // 1.20.1：stopAllCasting(maid)
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
