package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 枪械兼容工具（v1.1.0）——TACZ（永恒枪械工坊：零）与 卓越前线（Superb Warfare）双枪械模组。
 *
 * 判定优先走 TLM 内置兼容层（GunCommonUtil.isGun——TLM 用反射同时兼容两家，
 * 未装枪械 mod 时安全返回 false），注册名兜底（TLM 兼容层异常/未初始化时仍可判定）：
 * - TACZ：枪 = tacz:modern_kinetic_gun（NBT GunId 区分枪型）；弹药 = tacz:ammo（NBT AmmoId）
 *   与 tacz:ammo_box 弹药箱（NBT AmmoId/AmmoCount，见 taczBoxApi 注释）
 * - 卓越前线：每枪一个注册物品（superbwarfare 命名空间）；弹药 = 5 类 *_ammo
 *
 * 理想射程基准读 TLM 枪械三段距离配置（MAID_GUN_MEDIUM_DISTANCE，中距离）——
 * 与 TLM 自家 gun_attack 任务的走位配置同源。
 *
 * ============ v1.3.0 实测六百六十六【"有没有弹"这件事交还给枪械 mod 自己判】 ============
 *
 * 【粉丝反馈原文】「远程鞘翅模式下对蓄力枪械和重型武器的判定有点问题，通常打个几发就会
 * "哑火"，女仆会一直绕圈但不射击，如果在地面则会挨揍。」
 *
 * 【旧版错在哪】旧版散装弹药那一条是"任意 `tacz:ammo` 都算"（卓越前线那边是"5 类弹药任意
 * 一类都算"）——而两家的开火门根本不是这么判的（javap 实证，TACZ 1.1.8-hotfix / SBW 0.8.9.1）：
 *
 * <pre>
 *   TACZ  LivingEntityShoot.shoot 里那道 NO_AMMO 门：
 *         useInventoryAmmo(gun) ? IGun.hasInventoryAmmo(女仆, gun, needCheckAmmo())
 *                               : getCurrentAmmoCount(gun) + (弹膛里有弹 ? 1 : 0) &gt;= 1
 *         其中 hasInventoryAmmo 扫她整个 ITEM_HANDLER（女仆 = 护甲 + 双手 + 背包 + 饰品四合一的
 *         capability，EntityMaid.getCapability 反编译实证），逐个槽位问的是
 *             IAmmo.isAmmoOfGun(gunItem, ammoStack)        ← **口径必须对上这把枪**
 *         或 IAmmoBox.isAmmoBoxOfGun(gunItem, boxStack)
 *         （needCheckAmmo() 对非玩家恒 true：LivingEntityAmmoCheck 字节码里只有"创造玩家"才是 false）
 *
 *   SBW   GunItem.canShoot → GunData.hasEnoughAmmoToShoot(entity)
 *         = AMMO_COST_PER_SHOOT &lt;= currentAvailableAmmo(entity)
 *         = 弹匣里（用背包弹的枪则是背包里**这一把枪认得的**那类弹）够不够打一发
 * </pre>
 *
 * 于是「她背着步枪弹、手里却是重机枪 / 火箭筒 / 蓄力枪」这一类**口径对不上**的局面下：
 * 本模组判"有弹药"→ 飞行远战 / 扫帚模式照旧激活、气泡不报缺件、**她一直绕圈**；
 * 而枪械 mod 那边每一发 shoot 都是 NO_AMMO（TLM 收到只会去调 reload，喂不上就永远在重装）
 * 或 canShoot=false（SBW 那一支直接返回 50，只扔一颗手雷）。**枪里预装的那几发**打完，
 * 这把枪就再也不会响——正是"打个几发就会哑火"；人一直挂在圈上，落地那几秒还只有开火
 * 一条支路（飞行远战的地面分支不近战），所以"在地面则会挨揍"。
 *
 * 【现在的口径：问枪械 mod 自己】{@link #canFeed} = 那把枪此刻"喂得上弹 / 打得响"吗：
 *   TACZ  与上面那道 NO_AMMO 门逐句同口径（useInventoryAmmo / hasInventoryAmmo(…, true) /
 *         getCurrentAmmoCount + 弹膛）；
 *   SBW   与 canShoot 用的是**同一个方法** hasEnoughAmmoToShoot(女仆)——充能武器的能量、
 *         用背包弹的枪的背包弹，都由它自己算，本模组不再猜。
 * 反射不可用（没装 / 版本改名）时退回旧版宽松判据（{@link #looseAmmoScan}），行为与旧版
 * 一字不差——绝不因为兼容层异常就判她"没弹"、把模式踢掉。
 *
 * ============ v1.3.0 实测六百九十四【模式门禁的「换得上弹」那一半也交给 TACZ 自己】 ============
 *
 * 【玩家原话】「现在关于远程空袭模式和扫帚模式枪械的判定还是 tacz 原版能够开火的判定吗？我想的
 * 是现在对于允许激活的判定，不应该是单纯的远程武器加弹药（很简单，因为像冲锋枪跟狙击枪并不是
 * 只要有远程武器和弹药就行的），最好是走 TACZ 的 API，发现手持武器对应的 TACZ 端允许开火，那么
 * 就判定为武器和弹药方面为可通过的。」
 *
 * 【玩家说的没错：缺口正好在「换得上弹」那一半】实测六百六十六 只把「此刻扣扳机打得响吗」
 * （{@link #canFeed}）交给了 TACZ；实测六百九十二 为了让打空弹匣的女仆不从扫帚上掉下来，又给
 * 模式门禁配了一个「现在打不响但换得上吗」的宽松档（当时的 {@link #canReload}）——那一档用的是
 * {@link #looseAmmoScan}，**任意 {@code tacz:ammo} 都算，不看口径**。于是「她背着一包步枪弹、
 * 手里却是狙击枪 / 重机枪 / 蓄力枪」这种**口径对不上**的局面照样判「备弹通过」：模式激活、
 * 气泡不报缺件、她就一直绕圈不开火。这正是玩家说的「并不是只要有远程武器和弹药就行」。
 *
 * 【现在的口径：原样调用 TACZ 的 {@code AbstractGunItem.canReload}】它就是 TACZ 自己那句
 * 「这把枪换得上弹吗」（javap 实证：1.20.1 与 1.21.1 两版 1.1.8 签名一致、字节码逐句相同）：
 * <pre>
 *   canReload(LivingEntity shooter, ItemStack gun):
 *     ① getCommonGunIndex(getGunId(gun)) 为空          → false  ← 枪包数据缺失（坏枪），直接否
 *     ② getCurrentAmmoCount(gun) &gt;= 弹匣上限（含配件） → false  ← 弹匣是满的（那一拍 canFeed 已为真）
 *     ③ useInventoryAmmo(gun)                          → false  ← 这类枪不吃弹匣，归 canFeed 的
 *                                                                  hasInventoryAmmo 那一支管
 *     ④ getReloadData().isInfinite()                   → true   ← 无限弹的枪永远换得上
 *     ⑤ useDummyAmmo(gun)                              → getDummyAmmoAmount(gun) &gt; 0
 *     ⑥ 否则扫 shooter 的 ITEM_HANDLER（女仆 = 护甲 + 双手 + 背包 + 饰品四合一，
 *        EntityMaid.getCapability 反编译实证）：
 *            IAmmo.isAmmoOfGun(gun, stack)          ← **口径必须对上这把枪**
 *        或  IAmmoBox.isAmmoBoxOfGun(gun, stack)   ← 弹药箱也算（与原版换弹同一条）
 * </pre>
 * 第 ⑥ 步的扫法（{@code lambda$canReload$1}，javap 实证）与 {@code hasInventoryAmmo} 的扫法**逐句
 * 相同**，是纯只读的 {@code getStackInSlot} 循环——不 extract、不改 NBT，所以每 tick 问它没有副作用。
 *
 * 【为什么这不回退 实测六百九十二（弹匣空了别把她摔下扫帚）】第 ⑥ 步**同时认弹药箱**，而玩家后勤的
 * 主流形态就是 {@code tacz:ammo_box}（实测五百六十八 起的老口径）——「只带弹药箱」的女仆照旧通过；
 * 而被挡掉的只有「带的是对不上口径的弹药」这一种，那本来就不该让她起飞（起了也打不响）。
 * 弹匣满时 canReload 为 false，但那一拍 {@link #canFeed} 为真，「打得响 **或** 换得上」的或门照旧过。
 *
 * 反射不可用（没装 TACZ / 版本改名）时退回 {@link #looseAmmoScan}，行为与旧版一字不差。
 * 卓越前线（SBW）没有对应的公开查询方法（本机未装、无法 javap 实证，且实测六百六十六 已用它的
 * {@code hasEnoughAmmoToShoot} 管「打得响」那一半）→ 它的「换得上」仍走 looseAmmoScan。
 */
public final class GunCompat {
    private GunCompat() {
    }

    /** TACZ 枪物品注册名（全 mod 唯一枪物品，枪型在 NBT GunId） */
    private static final String TACZ_GUN_ID = "tacz:modern_kinetic_gun";
    /** TACZ 弹药物品注册名（类型在 NBT AmmoId） */
    private static final String TACZ_AMMO_ID = "tacz:ammo";
    /** 卓越前线命名空间 */
    private static final String SBW_NS = "superbwarfare";
    /** 卓越前线 5 类弹药（path 名） */
    private static final java.util.Set<String> SBW_AMMO_PATHS = java.util.Set.of(
            "handgun_ammo", "rifle_ammo", "sniper_ammo", "shotgun_ammo", "heavy_ammo");
    /** 卓越前线能量武器（二次灾变等——充能即用，不吃上面 5 类常规弹药；javap 实证
     *  SecondaryCataclysmItem 的射击走 ForgeEnergy IEnergyStorage，TLM 的 doGunReload
     *  对它只查 shouldStartReloading/startBolt（能量充能），弹药判定对它们恒空） */
    private static final java.util.Set<String> SBW_ENERGY_GUN_PATHS = java.util.Set.of(
            "secondary_cataclysm", "super_star_shooter");

    /* ---------------- TACZ 弹药箱（实测五百六十八） ----------------
     *
     * 【为什么必须认弹药箱】TACZ 玩家后勤的主流形态不是散装弹药而是弹药箱
     * （tacz:ammo_box，一箱 1-2 组的 AmmoId/AmmoCount）。反编译实证（tacz 1.1.8-hotfix，
     * 1.20.1/1.21.1 两版 API 逐签名对上）：TACZ 自己的换弹链路**原生支持弹药箱**——
     * AbstractGunItem.canReload / findAndExtractInventoryAmmo 扫
     * shooter 的 ITEM_HANDLER capability（女仆 = 护甲+双手+背包+饰品
     * 四合一，EntityMaid.getCapability 反编译实证），按 IAmmoBox.isAmmoBoxOfGun 认箱、
     * 扣 AmmoCount、打空清 AmmoId。TLM 侧 TacInnerCompat.performGunAttack 收到
     * ShootResult.NO_AMMO 时只管调 gunOperator.reload()，喂弹全交给 TACZ。
     * 所以**消耗侧零工作量**——坏的只有本类 hasGunAndAmmo 这道判定门：旧版只数散装
     * tacz:ammo，箱装弹药被当"没子弹"→ 空袭弹门禁不过（气泡报缺弹药、退普通模式）+
     * AutoCombatSwitch 枪械弹药闸不过（gun_attack 不进池、普通模式也不开枪），
     * 正是粉丝反馈的整条链。
     *
     * 【判据与 TACZ canReload 同口径】箱里有弹 = isAmmoBoxOfGun(gun, box)
     * （全类型创造箱恒 true；AmmoId 为空 false；弹药型号必须对上枪的 ammoId）
     * 且 getAmmoCount(box) > 0（创造箱/全类型创造箱读作 Integer.MAX_VALUE，天然通过；
     * 普通箱打空 0 → 不算）。**实测六百六十六 起这条只作为反射不可用时的兜底**
     * （见 looseAmmoScan）——正路是把整道判定交给 TACZ 自己的 hasInventoryAmmo。
     *
     * 【为什么走反射】TACZ 是可选兼容 mod，不在编译类路径上（TLM 自家 GunCommonUtil、
     * 以及本 mod 对 Sable/暮色森林/法术层的既有软兼容全是同一做法）。IAmmoBox 全是
     * 公开接口方法，1.1.8 两版签名一致，Method 惰性解析一次后复用。
     */
    /** 惰性反射句柄：[0]=IAmmoBox 类，[1]=isAmmoBoxOfGun(ItemStack,ItemStack)，[2]=getAmmoCount(ItemStack)；
     *  空数组 = TACZ 不在场/解析失败（永远判 false，行为与旧版一致） */
    private static volatile Object[] TACZ_BOX_API;
    private static final Object TACZ_BOX_LOCK = new Object();

    private static Object[] taczBoxApi() {
        Object[] api = TACZ_BOX_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_BOX_LOCK) {
            if (TACZ_BOX_API == null) {
                try {
                    Class<?> cls = Class.forName("com.tacz.guns.api.item.IAmmoBox");
                    TACZ_BOX_API = new Object[]{
                            cls,
                            cls.getMethod("isAmmoBoxOfGun", ItemStack.class, ItemStack.class),
                            cls.getMethod("getAmmoCount", ItemStack.class)};
                } catch (Throwable ignored) {
                    TACZ_BOX_API = new Object[0];
                }
            }
            return TACZ_BOX_API;
        }
    }

    /* ---------------- v1.3.0 实测六百六十六：枪械 mod 自己的"喂得上弹吗" ----------------
     *
     * 两句判定分别抄两家的原始调用点（见类注释的字节码摘录）：
     *   TACZ  useInventoryAmmo / hasInventoryAmmo(…, true) / getCurrentAmmoCount / hasBulletInBarrel
     *   SBW   GunData.from(gun).hasEnoughAmmoToShoot(maid)
     * 都是公开 API，TACZ 不在编译类路径上、SBW 也不在（可选兼容），所以照旧走反射。
     */
    /** TACZ 反射句柄：[0]=IGun 类，[1]=useInventoryAmmo(IS)，[2]=hasInventoryAmmo(LE,IS,Z)，
     *  [3]=getCurrentAmmoCount(IS)，[4]=hasBulletInBarrel(IS)；空数组 = 不可用 */
    private static volatile Object[] TACZ_FEED_API;
    private static final Object TACZ_FEED_LOCK = new Object();

    private static Object[] taczFeedApi() {
        Object[] api = TACZ_FEED_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_FEED_LOCK) {
            if (TACZ_FEED_API == null) {
                try {
                    Class<?> cls = Class.forName("com.tacz.guns.api.item.IGun");
                    TACZ_FEED_API = new Object[]{
                            cls,
                            cls.getMethod("useInventoryAmmo", ItemStack.class),
                            cls.getMethod("hasInventoryAmmo",
                                    net.minecraft.world.entity.LivingEntity.class,
                                    ItemStack.class, boolean.class),
                            cls.getMethod("getCurrentAmmoCount", ItemStack.class),
                            cls.getMethod("hasBulletInBarrel", ItemStack.class)};
                } catch (Throwable ignored) {
                    TACZ_FEED_API = new Object[0];
                }
            }
            return TACZ_FEED_API;
        }
    }

    /* ---------------- v1.3.0 实测六百九十四：TACZ 自己的「这把枪换得上弹吗」 ----------------
     *
     * {@code AbstractGunItem.canReload(LivingEntity, ItemStack)} ——见类注释那一节的字节码摘录。
     * 公开实例方法（挂在枪物品上），两版签名一致，照旧走反射（TACZ 不在编译类路径上）。
     */
    /** TACZ 反射句柄：[0]=AbstractGunItem 类，[1]=canReload(LivingEntity, ItemStack)；空数组 = 不可用 */
    private static volatile Object[] TACZ_RELOAD_API;
    private static final Object TACZ_RELOAD_LOCK = new Object();

    private static Object[] taczReloadApi() {
        Object[] api = TACZ_RELOAD_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_RELOAD_LOCK) {
            if (TACZ_RELOAD_API == null) {
                try {
                    Class<?> cls = Class.forName("com.tacz.guns.api.item.gun.AbstractGunItem");
                    TACZ_RELOAD_API = new Object[]{
                            cls,
                            cls.getMethod("canReload",
                                    net.minecraft.world.entity.LivingEntity.class,
                                    ItemStack.class)};
                } catch (Throwable ignored) {
                    TACZ_RELOAD_API = new Object[0];
                }
            }
            return TACZ_RELOAD_API;
        }
    }

    /**
     * v1.3.0 实测六百九十四：**这把枪**此刻换得上弹吗（null = 反射不可用，交回旧判据）。
     * 原样调用 TACZ 的 {@code AbstractGunItem.canReload}——口径比对、弹药箱识别、无限弹/假弹
     * 全由它自己算，本模组不再猜。它是纯只读查询（见类注释的 lambda 实证），可每 tick 调用。
     */
    private static Boolean taczCanReload(EntityMaid maid, ItemStack gun) {
        Object[] api = taczReloadApi();
        if (api.length == 0) {
            return null;
        }
        try {
            Object item = ((Class<?>) api[0]).cast(gun.m_41720_());
            return (Boolean) ((java.lang.reflect.Method) api[1]).invoke(item, maid, gun);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 卓越前线反射句柄：[0]=GunData 类，[1]=from(IS)（静态），[2]=hasEnoughAmmoToShoot(Entity)；
     *  空数组 = 不可用 */
    private static volatile Object[] SBW_FEED_API;
    private static final Object SBW_FEED_LOCK = new Object();

    private static Object[] sbwFeedApi() {
        Object[] api = SBW_FEED_API;
        if (api != null) {
            return api;
        }
        synchronized (SBW_FEED_LOCK) {
            if (SBW_FEED_API == null) {
                try {
                    Class<?> cls = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                    SBW_FEED_API = new Object[]{
                            cls,
                            cls.getMethod("from", ItemStack.class),
                            cls.getMethod("hasEnoughAmmoToShoot",
                                    net.minecraft.world.entity.Entity.class)};
                } catch (Throwable ignored) {
                    SBW_FEED_API = new Object[0];
                }
            }
            return SBW_FEED_API;
        }
    }

    /**
     * v1.3.0 实测六百六十六：**这把枪**此刻喂得上弹 / 打得响吗（null = 反射不可用，交回旧判据）。
     * 与 TACZ `LivingEntityShoot.shoot` 的 NO_AMMO 门逐句同口径。
     */
    private static Boolean taczCanFeed(EntityMaid maid, ItemStack gun) {
        Object[] api = taczFeedApi();
        if (api.length == 0) {
            return null;
        }
        try {
            Object item = ((Class<?>) api[0]).cast(gun.m_41720_());
            java.lang.reflect.Method useInv = (java.lang.reflect.Method) api[1];
            if (!(Boolean) useInv.invoke(item, gun)) {
                // 不吃背包弹的枪（自带弹 / 内部充能）：只看枪里的数 + 弹膛那一发
                int cur = (Integer) ((java.lang.reflect.Method) api[3]).invoke(item, gun);
                if (cur > 0) {
                    return Boolean.TRUE;
                }
                return (Boolean) ((java.lang.reflect.Method) api[4]).invoke(item, gun);
            }
            return (Boolean) ((java.lang.reflect.Method) api[2])
                    .invoke(item, maid, gun, Boolean.TRUE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v1.3.0 实测六百六十六：卓越前线版（与 GunItem.canShoot 用的**同一个方法**） */
    private static Boolean sbwCanFeed(EntityMaid maid, ItemStack gun) {
        Object[] api = sbwFeedApi();
        if (api.length == 0) {
            return null;
        }
        try {
            Object data = ((java.lang.reflect.Method) api[1]).invoke(null, gun);
            if (data == null) {
                return Boolean.FALSE; // GunData 读不出来 = 这把枪没有枪包数据（同 canShoot 的 100）
            }
            return (Boolean) ((java.lang.reflect.Method) api[2]).invoke(data, maid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该物品是否 TACZ 弹药箱（实现 IAmmoBox 接口；TACZ 不在场恒 false） */
    public static boolean isAmmoBox(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        Object[] api = taczBoxApi();
        if (api.length == 0) {
            return false;
        }
        try {
            return ((Class<?>) api[0]).isInstance(stack.m_41720_());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 弹药箱里是否有"这把枪打得响"的弹药（isAmmoBoxOfGun + getAmmoCount > 0，同上口径） */
    public static boolean boxHasAmmoFor(ItemStack gun, ItemStack box) {
        Object[] api = taczBoxApi();
        if (api.length == 0 || gun == null || gun.m_41619_() || box == null || box.m_41619_()) {
            return false;
        }
        try {
            Object boxItem = ((Class<?>) api[0]).cast(box.m_41720_());
            if (!(Boolean) ((java.lang.reflect.Method) api[1]).invoke(boxItem, gun, box)) {
                return false;
            }
            return (Integer) ((java.lang.reflect.Method) api[2]).invoke(boxItem, box) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否装了任一枪械模组（主动切战斗的枪械优先分支用——没装就别浪费背包扫描） */
    public static boolean anyGunModLoaded() {
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("tacz")) {
                return true;
            }
            return net.minecraftforge.fml.ModList.get().isLoaded("superbwarfare");
        } catch (Exception e) {
            return false;
        }
    }

    /** 该物品栈是否为枪（TACZ/卓越前线） */
    public static boolean isGun(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        // 首选：TLM 兼容层（反射双枪械 mod，未装安全返回 false）
        try {
            if (com.github.tartaricacid.touhoulittlemaid.compat.gun.common.GunCommonUtil.isGun(stack)) {
                return true;
            }
        } catch (Throwable ignored) {
            // TLM 兼容层异常（版本变化/未初始化）→ 走注册名兜底
        }
        return isGunById(stack);
    }

    /** 注册名兜底判定：TACZ 唯一枪物品（SBW 每枪一个物品且命名无规律，只走 TLM 兼容层） */
    private static boolean isGunById(ItemStack stack) {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && key.toString().equals(TACZ_GUN_ID);
    }

    /** 是不是卓越前线的枪（决定"喂得上弹吗"问哪一家；命名空间兜底 TLM 兼容层异常） */
    public static boolean isSbwGun(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            if (com.github.tartaricacid.touhoulittlemaid.compat.gun.swarfare.SWarfareCompat.isGun(stack)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 交回命名空间兜底
        }
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && SBW_NS.equals(key.m_135827_());
    }

    /** 该物品栈是否为弹药（TACZ:ammo 带 AmmoId；SBW:5 类） */
    public static boolean isAmmo(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        if (key == null) {
            return false;
        }
        String id = key.toString();
        if (id.equals(TACZ_AMMO_ID)) {
            return true; // TACZ 弹药类型在 NBT，物品唯一
        }
        return SBW_NS.equals(key.m_135827_()) && SBW_AMMO_PATHS.contains(key.m_135815_());
    }

    /**
     * v1.3.0 实测六百六十六：**这一把枪**喂得上弹 / 打得响吗（模式门禁与日志都用这一条）。
     *
     * 正路是问枪械 mod 自己（{@link #taczCanFeed} / {@link #sbwCanFeed}）；反射不可用
     * （没装该 mod / 版本改名）时退回旧版宽松扫描 {@link #looseAmmoScan}——**与旧版行为一致**，
     * 不会因为兼容层异常就判她"没弹"。
     *
     * 【为什么必须是"这把枪"而不是"她身上任意一把枪"】开火路径用的就是主手那把
     * （`MaidFlightCombatBehavior#fireRanged` 读 `maid.getMainHandItem()`）。旧版口径
     * "身上任意一把枪有弹就算"会让"手里是喂不上弹的重武器、背包里另有一把能用的手枪"
     * 这种局面照样激活模式——她就一直绕圈不开火（粉丝反馈的"绕圈"）。
     */
    public static boolean canFeed(EntityMaid maid, ItemStack gun) {
        if (maid == null || gun == null || gun.m_41619_() || !isGun(gun)) {
            return false;
        }
        Boolean r;
        try {
            r = isSbwGun(gun) ? sbwCanFeed(maid, gun) : taczCanFeed(maid, gun);
        } catch (Throwable ignored) {
            r = null;
        }
        return r != null ? r : looseAmmoScan(maid, gun);
    }

    /**
     * 【实测六百九十二】"现在这一拍打不响，但**换得上弹**吗"——背包里有这把枪认的弹药（或对得上的弹药箱）。
     *
     * <p>【实测六百九十四 起这条由 TACZ 自己回答】旧版名义上这么写、实际用的是口径不敏感的宽松扫描
     * （任意 tacz:ammo 都算），见下面【口径】那一段。
     *
     * <p>【为什么要跟 {@link #canFeed} 分开】两者回答的是两个不同的问题：
     * <ul>
     *   <li>{@link #canFeed} = **此刻扣扳机能打响吗**（弹匣里 / 弹膛里有弹）→ 开火链路的诊断日志用它；</li>
     *   <li>本方法 = **喂得进这把枪吗**（打空了也没关系，换弹就能继续）→ **模式门禁**用它。</li>
     * </ul>
     * 玩家原话：「扫帚模式如果女仆持有的是冲锋枪或者狙击枪，这两类枪械很容易飞着飞着就突然从
     * 扫帚上掉下来。」根因就是模式门禁拿 {@code canFeed} 判弹药：TACZ 弹匣枪的
     * {@code taczCanFeed} 只看"弹匣里这一拍还有没有弹"（{@code useInventoryAmmo=false} →
     * {@code getCurrentAmmoCount>0 || hasBulletInBarrel}），于是**弹匣打空的那一拍 = 判定缺弹药**，
     * 她就被从扫帚上请下来（实测日志 01:38:09「弹=无」紧跟着「下扫帚（理由=缺远程武器/弹药）」）。
     * 而她的背包里明明有 {@code tacz:ammo_box}——TLM 自己的开火链路（{@code performGunAttack}）
     * 下一发就会拉栓/换弹。
     *
     * <p>【口径】v1.3.0 实测六百九十四 之前是 实测五百六十八 那条老判据 {@link #looseAmmoScan}
     * （主手/背包里有**任意** tacz:ammo 就算，不校验口径）——玩家反馈「像冲锋枪跟狙击枪并不是只要
     * 有远程武器和弹药就行」，指的就是这里。**现在改成原样调用 TACZ 自己的
     * {@code AbstractGunItem.canReload}**（{@link #taczCanReload}，见类注释那一节）：它按口径认
     * 散装弹、也认对得上的弹药箱，还顺带否掉弹匣已满 / 枪包数据缺失（坏枪）这几种情形。
     * 反射不可用（没装枪械 mod 等）时才回到 {@link #looseAmmoScan}——仍然是「宽进」的老口径，
     * 绝不在空中误判把她摔下去。
     */
    public static boolean canReload(EntityMaid maid, ItemStack gun) {
        if (maid == null || gun == null || gun.m_41619_() || !isGun(gun)) {
            return false;
        }
        // v1.3.0 实测六百九十四：TACZ 枪改问 TACZ 自己（口径比对 + 认弹药箱）。
        // 只有"反射不可用"（返回 null）才回退宽松扫描；TACZ 明确回答 false（口径对不上）时
        // **必须**照它说的算 false，绝不能回退——那正是玩家要挡掉的那种局面。
        if (!isSbwGun(gun)) {
            Boolean r;
            try {
                r = taczCanReload(maid, gun);
            } catch (Throwable ignored) {
                r = null;
            }
            if (r != null) {
                return r;
            }
        }
        return looseAmmoScan(maid, gun);
    }

    /**
     * 旧版宽松判据（实测五百六十八 的口径）——**现在只在反射不可用时兜底**：
     * 主手 / 背包里有任意 tacz:ammo（或 SBW 5 类弹药之一）、或对得上这把枪的 TACZ 弹药箱；
     * 卓越前线能量武器直接放行（v1.1.0 终审二的老口径）。
     */
    private static boolean looseAmmoScan(EntityMaid maid, ItemStack gun) {
        try {
            if (isEnergyGun(gun)) {
                return true;
            }
            if (isAmmo(maid.m_21205_()) || boxHasAmmoFor(gun, maid.m_21205_())) {
                return true;
            }
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.m_41619_()) {
                    continue;
                }
                if (isAmmo(s) || boxHasAmmoFor(gun, s)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 她身上（主手 + 背包）所有的枪——顺序：主手优先，其余按背包槽位 */
    public static java.util.List<ItemStack> gunsOf(EntityMaid maid) {
        java.util.List<ItemStack> guns = new java.util.ArrayList<>();
        if (maid == null) {
            return guns;
        }
        try {
            ItemStack main = maid.m_21205_();
            if (isGun(main)) {
                guns.add(main);
            }
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && isGun(s)) {
                    guns.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return guns;
    }

    /**
     * 女仆是否"有枪可用"——**身上至少有一把喂得上弹的枪**（自动切换枪械战斗池的闸）。
     *
     * v1.1.0 终审二：卓越前线能量武器（二次灾变/超级星星炮）不消耗常规弹药
     * （内部 ForgeEnergy 充能）——旧版持有它们时跳过弹药检查直接算可用。
     * v1.2.0 实测五百六十八：TACZ **弹药箱**计入弹药（箱对得上枪 + 箱里有数）。
     * v1.3.0 实测六百六十六：整道判定换成 {@link #canFeed}（问枪械 mod 自己，per-gun）；
     * 能量武器的"免检"由 SBW 自己的 hasEnoughAmmoToShoot 回答（它算的就是充能），
     * 反射不可用时才回到 looseAmmoScan 的老口径——**这一条只管"要不要进枪械池"**，
     * 飞行远战 / 扫帚的模式门禁请用 {@link #canFeed}（按主手那把枪判）。
     */
    public static boolean hasGunAndAmmo(EntityMaid maid) {
        if (maid == null || !anyGunModLoaded()) {
            return false;
        }
        try {
            for (ItemStack gun : gunsOf(maid)) {
                if (canFeed(maid, gun)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 卓越前线能量武器（不吃常规弹药——内部充能）：按注册名判定 */
    public static boolean isEnergyGun(ItemStack stack) {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key != null && SBW_NS.equals(key.m_135827_())
                && SBW_ENERGY_GUN_PATHS.contains(key.m_135815_());
    }

    /**
     * 枪械理想射程基准（格）——读 TLM 枪械中距离配置（与 TLM gun_attack 任务同源），
     * 读取失败回退 12（TLM 默认中距离量级）。
     */
    public static double gunMaxRange() {
        try {
            return com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig
                    .MAID_GUN_MEDIUM_DISTANCE.get();
        } catch (Throwable ignored) {
            return 12.0;
        }
    }
}
