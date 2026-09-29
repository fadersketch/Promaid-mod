package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * v1.3.0(beta)【骑乘指挥棒·模组坐骑通解通法】——让女仆能骑并驾驶**第三方模组的载具/坐骑**。
 *
 * <h2>玩家原话</h2>
 * 「骑乘开始考虑兼容卓越前线的和冰火传说的龙，这两类载具都具有攻击能力以及飞行能力，
 * 不能用通用的兼容。看看能不能给出一个通用解。还有一件事，冰火传说有普通版和社区版。
 * 看看关于骑乘方面能不能给一个通用的兼容。」
 *
 * <h2>为什么"不能用通用兼容"，而我们仍然给得出通用解</h2>
 * 现有的 {@link MaidRideKit} 走的是**一个**通用判据——原版 {@code Saddleable && isSaddled}，
 * 然后把目的地喂给载具自己的 {@code PathNavigation}。这条路对马/猪/骆驼成立，是因为原版把它们
 * 做成了"玩家骑就走玩家驾驶、女仆骑就走普通 travel + 寻路"。
 * 而这两类模组载具**根本不走原版那一套**（javap / 反编译实证，jar 路径见各驱动注释）：
 * <ul>
 *   <li><b>卓越前线（Superb Warfare）</b>：座驾不是 {@code Saddleable}，也没有
 *       {@code PathNavigation}——它自己实现了一整套 {@code processInput(short)} 位掩码 +
 *       引擎类（Wheel/Track/Ship/Helicopter/Aircraft/AirShip 各自 {@code work()}）。</li>
 *   <li><b>冰火传说（Ice and Fire）的龙</b>：不是 {@code Saddleable}、**没有鞍**，
 *       骑乘由"驯服 + 主人 + 阶段 &gt; 2"决定；飞行由 {@code IafDragonFlightManager} 驱动；
 *       而 {@code getControllingPassenger()} 在原版/社区版 1.20.1 上**只认 Player**，
 *       女仆永远拿不到操纵权。</li>
 * </ul>
 * 所以"通用"不能是"一条判据包打天下"，而应该是——**把"怎么开"抽象成可插拔的驱动（driver）**：
 * 探测到是哪一类载具 → 派给对应的驱动；每个驱动只负责把同一个"意图"（去某点 / 停下 /
 * 攻击某目标）翻译成那个模组自己的 API。见 {@link #drive} / {@link #stop} / {@link #tickAttack}。
 *
 * <h2>通解的三条驱动（本类就是这张派发表）</h2>
 * <ol>
 *   <li><b>通用兽（BEAST）</b>——{@code Saddleable && isSaddled}：原路不在此类，保留在
 *       {@link MaidRideKit}（喂 {@code PathNavigation} / {@code MoveControl}）。</li>
 *   <li><b>卓越前线载具（VEHICLE）</b>——{@code instanceof VehicleEntity}（反射，编译期不依赖
 *       该模组）：驾驶走 {@code processInput} 位掩码 + 朝向/俯仰直接写；开火由模组自己那套
 *       "Mob 乘客有目标就自动瞄准开火"接管（反编译实证，见 {@link #driveVehicle}）。</li>
 *   <li><b>冰火传说龙（DRAGON）</b>——类名 {@code EntityDragonBase}/{@code DragonBaseEntity}
 *       （反射）：<b>【实测七百二十三】降级方案下本类**不驱动它、不触发攻击**</b>——女仆只是
 *       挂在 {@code getRiderPosition()} 那个玩家鞍位上（不是乘客），龙的飞行/跟随交给它自己
 *       那套（我们只把它置到 {@code command=2} 跟随档）。见本类"悬空鞍位"那一节。</li>
 * </ol>
 *
 * <h2>普通版 vs 社区版（同一个类，两套包名）</h2>
 * 反编译三个 jar 对照：<b>结构相同、只有包名与个别内部名不同</b>——
 * 普通版 {@code com.github.alexthe666.iceandfire.entity.EntityDragonBase}、社区版
 * {@code com.iafenvoy.iceandfire.entity.EntityDragonBase}（1.21.1 上类名多了后缀：
 * {@code DragonBaseEntity}）。本类用到的成员名（{@code getRiderPosition}、
 * {@code getDragonStage}、{@code getCommand}、{@code setCommand}）**四份 jar 逐字相同**。
 * 所以这里**按类名探测、按同一套方法名调用**——普通版与社区版共用一条代码路径，无需分叉。
 *
 * <h2>铁律（全类通用）</h2>
 * <ul>
 *   <li>**全程反射**：没装 / 换版本 / 改包名 → {@code available()==false}，整条链路不激活，
 *       一个字节都不碰原版（与 {@code GunCompat}/{@code MaidGoetyCompat} 同范式）。</li>
 *   <li>**只在"棍子绑了"时介入**：真正决定要不要调用本类的是 {@link MaidRideKit#isRideRider}，
 *       它要求她身上带 {@code maid_smart_ride_mount} 痕迹（{@link RideBindManager} 写的）。
 *       原版/别的模组让她坐上去的场合，本类一次都不会被调到。</li>
 *   <li>**异常吞掉、返回安全默认**：绝不因为对第三方模组的探测失败把她卡住或让游戏崩。</li>
 * </ul>
 */
public final class MaidMountCompat {

    /* ==================== 模组 id ==================== */

    /** 卓越前线（Superb Warfare）。 */
    public static final String MOD_SWB = "superbwarfare";
    /** 冰火传说（含社区版，两者 modid 都是 iceandfire）。 */
    public static final String MOD_IAF = "iceandfire";

    /** 载具/坐骑种类。 */
    public enum Kind {
        /** 普通原版兽（Saddleable）——不走本类，由 {@link MaidRideKit} 处理。 */
        BEAST,
        /** 卓越前线载具。 */
        VEHICLE,
        /** 冰火传说的龙。 */
        DRAGON
    }

    private MaidMountCompat() {
    }

    /* ==================== 反射缓存：卓越前线 ==================== */

    private static boolean swbInited;
    private static boolean swbOk;
    private static Class<?> cVehicle;
    private static Method mProcessInput;      // VehicleEntity.processInput(short)
    private static Method mSetForward;        // setForwardInputDown(boolean)
    private static Method mSetBack;           // setBackInputDown(boolean)
    private static Method mSetLeft;           // setLeftInputDown(boolean)
    private static Method mSetRight;          // setRightInputDown(boolean)
    private static Method mSetUp;             // setUpInputDown(boolean)
    private static Method mSetDown;           // setDownInputDown(boolean)
    private static Method mMouseInput;        // mouseInput(double,double)
    private static Method mComputed;          // computed() -> DefaultVehicleData
    private static Method mGetEngineType;     // DefaultVehicleData.getEngineType()
    private static Method mCanShoot;          // canShoot(LivingEntity)
    private static Method mVehicleShoot;      // vehicleShoot(LivingEntity,String,UUID,Vec3)
    private static Method mGetGunName;        // getGunName(int)
    private static Method mGetSeatIndex;      // getSeatIndex(Entity)
    private static Method mGetMaxPassengers;  // getMaxPassengers()
    private static Method mIsWreck;           // isWreck()
    /* 【实测七百二十四】真刹车三件套（javap 实证 1.20.1 hotfix 与 1.21.1 同名同描述符）：
     * processInput(0) 只是"松油门"——引擎里 power 只按 0.96 衰减、地面摩擦系数才 0.54~0.79，
     * 车会继续滑。要真停住必须把这三个量一起写零。 */
    private static Method mSetPower;          // setPower(float)
    private static Method mSetDeltaRot;       // setDeltaRot(float)
    private static Method mSetTargetSpeed;    // setTargetSpeed(double)
    /* 【实测七百二十四】炮塔/武器位的 AI 目标（车自己那套瞄准开火读的就是这两个 UUID）。 */
    private static Method mSetAiTurretUUID;   // setAiTurretTargetUUID(String)
    private static Method mSetAiWeaponUUID;   // setAiPassengerWeaponTargetUUID(String)
    private static Method mHasTurret;         // hasTurret()
    private static Method mHasWeaponStation;  // hasPassengerWeaponStation()
    private static Method mGetTurretCtrlIdx;  // getTurretControllerIndex()
    private static Method mGetWeaponCtrlIdx;  // getPassengerWeaponStationControllerIndex()
    private static Method mGetNthEntity;      // getNthEntity(int) —— 取某个座位上的乘客
    /* 【实测七百二十五】直升机的转向/俯仰走的是**鼠标通道**（javap/反编译实证：
     * helicopterEngine 的 yaw/pitch/roll 三项都读 getMouseMoveSpeedX/Y，而不是左右位）。
     * 所以驱动层必须能写这两个量 + 悬停开关。 */
    private static Method mGetMouseSpeedX;    // getMouseMoveSpeedX()
    private static Method mGetMouseSpeedY;    // getMouseMoveSpeedY()
    private static Method mSetMouseSpeedX;    // setMouseMoveSpeedX(float)
    private static Method mSetMouseSpeedY;    // setMouseMoveSpeedY(float)
    private static Method mSetHoverMode;      // setHoverMode(boolean)
    private static Method mGetHoverMode;      // getHoverMode()
    /* 【实测七百二十六·点6】女仆自己往载具里装弹：车的枪弹从**车自己的容器**取（反编译
     * ModCapabilities 实证：Capabilities.ItemHandler.ENTITY → getInventory()），她背包里的
     * 子弹车看不见。这三组反射用来"读出这车要哪种子弹 + 把子弹从她背包搬进车容器"。 */
    private static Method mGetInventory;       // getInventory() -> VehicleContainerHandler(IItemHandler)
    private static Method mGetGunDataMap;      // getGunDataMap() -> Map<String, GunData>
    private static Method mGunUseBackpackAmmo; // GunData.useBackpackAmmo()
    private static Method mGunSelectedAmmo;    // GunData.selectedAmmoConsumer() -> AmmoConsumer
    /* 【实测七百二十七·点3】七百二十六 那一版装弹一条都没搬（实机日志零行「模组坐骑·装弹」）。
     * 根因（反编译 ItemAmmoStrategy / PlayerAmmoStrategy 实证）：车的武器分两种吃弹方式——
     *   ① `AmmoType: "superbwarfare:small_shell_he"`（普通物品 id）→ **ItemAmmoStrategy**，
     *      它的 `getPlayerAmmoType()` **恒为 null**，子弹就是那个**物品**本身；
     *   ② `AmmoType: "@rifle"` 之类 → PlayerAmmoStrategy，才走 `Ammo` 枚举。
     * 旧版只读 ①的 null → 直接 return 0；而且旧版还用 `AmmoSupplierItem` 判 ②的物品，对 ①
     * （普通 `Item`，见 ModItems.registerAmmo）**永远不匹配**。两道都错，所以一颗都搬不动。
     * 正解：用 SWB 自己的判据 `AmmoConsumer.isAmmoItem(stack)`（= MinecraftUtil.isSameItemStack
     * 比 `consumer.stack()`）——它对 ① ② **两种都成立**（PlayerAmmoStrategy.init 也会
     * `setStack(ammoType.getItemStack())`，反编译实证）。 */
    private static Method mConsumerIsAmmoItem; // AmmoConsumer.isAmmoItem(ItemStack) -> boolean
    private static Method mConsumerStack;      // AmmoConsumer.stack() -> ItemStack（取代表物品）

    /* ==================== 反射缓存：冰火传说 ==================== */

    private static boolean iafInited;
    private static boolean iafOk;
    private static Class<?> cDragon;
    private static Method mGetDragonStage;    // getDragonStage()
    /** 【实测七百二十】{@code getRiderPosition()}——龙给**玩家**算的背上鞍位；723 起是悬空椅面。 */
    private static Method mGetRiderPosition;  // getRiderPosition()
    private static Method mStrike;            // strike(boolean)
    private static Method mRiderShootFire;    // riderShootFire(Entity)
    /** 【实测七百二十三】跟随档：{@code getCommand()}/{@code setCommand(int)}（0=站 1=坐 2=跟随）。 */
    private static Method mGetCommand;
    private static Method mSetCommand;

    /* ==================== 初始化（各一次，失败即"没有这个模组"） ==================== */

    private static synchronized void initSwb() {
        if (swbInited) {
            return;
        }
        swbInited = true;
        try {
            cVehicle = Class.forName("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
            mProcessInput = cVehicle.getMethod("processInput", short.class);
            mSetForward = cVehicle.getMethod("setForwardInputDown", boolean.class);
            mSetBack = cVehicle.getMethod("setBackInputDown", boolean.class);
            mSetLeft = cVehicle.getMethod("setLeftInputDown", boolean.class);
            mSetRight = cVehicle.getMethod("setRightInputDown", boolean.class);
            mSetUp = cVehicle.getMethod("setUpInputDown", boolean.class);
            mSetDown = cVehicle.getMethod("setDownInputDown", boolean.class);
            mMouseInput = cVehicle.getMethod("mouseInput", double.class, double.class);
            mComputed = cVehicle.getMethod("computed");
            // 引擎类型：computed() 的返回类型上取 getEngineType()
            Class<?> dataCls = mComputed.getReturnType();
            mGetEngineType = dataCls.getMethod("getEngineType");
            // 以下都是"可选"：某版本没有也不该让驾驶失效，各自单独 try
            try {
                mIsWreck = cVehicle.getMethod("isWreck");
            } catch (Throwable ignored) {
                mIsWreck = null;
            }
            try {
                mCanShoot = cVehicle.getMethod("canShoot", LivingEntity.class);
                mVehicleShoot = cVehicle.getMethod("vehicleShoot", LivingEntity.class,
                        String.class, java.util.UUID.class, Vec3.class);
                mGetGunName = cVehicle.getMethod("getGunName", int.class);
            } catch (Throwable ignored) {
                mCanShoot = null;
                mVehicleShoot = null;
                mGetGunName = null;
            }
            try {
                mGetSeatIndex = cVehicle.getMethod("getSeatIndex", Entity.class);
                mGetMaxPassengers = cVehicle.getMethod("getMaxPassengers");
            } catch (Throwable ignored) {
                mGetSeatIndex = null;
                mGetMaxPassengers = null;
            }
            // 【实测七百二十四】真刹车三件套 + 炮塔/武器位 AI 目标：各自单独 try，
            // 某版本缺一个也不该让驾驶/开火整条失效。
            try {
                mSetPower = cVehicle.getMethod("setPower", float.class);
                mSetDeltaRot = cVehicle.getMethod("setDeltaRot", float.class);
                mSetTargetSpeed = cVehicle.getMethod("setTargetSpeed", double.class);
            } catch (Throwable ignored) {
                mSetPower = null;
                mSetDeltaRot = null;
                mSetTargetSpeed = null;
            }
            try {
                mSetAiTurretUUID = cVehicle.getMethod("setAiTurretTargetUUID", String.class);
                mSetAiWeaponUUID = cVehicle.getMethod("setAiPassengerWeaponTargetUUID", String.class);
                mHasTurret = cVehicle.getMethod("hasTurret");
                mHasWeaponStation = cVehicle.getMethod("hasPassengerWeaponStation");
                mGetTurretCtrlIdx = cVehicle.getMethod("getTurretControllerIndex");
                mGetWeaponCtrlIdx = cVehicle.getMethod("getPassengerWeaponStationControllerIndex");
                mGetNthEntity = cVehicle.getMethod("getNthEntity", int.class);
            } catch (Throwable ignored) {
                mSetAiTurretUUID = null;
                mSetAiWeaponUUID = null;
                mHasTurret = null;
                mHasWeaponStation = null;
                mGetTurretCtrlIdx = null;
                mGetWeaponCtrlIdx = null;
                mGetNthEntity = null;
            }
            // 【实测七百二十五】直升机的鼠标通道 + 悬停开关：各自单独 try，缺一个也不该让其余失效。
            try {
                mGetMouseSpeedX = cVehicle.getMethod("getMouseMoveSpeedX");
                mGetMouseSpeedY = cVehicle.getMethod("getMouseMoveSpeedY");
                mSetMouseSpeedX = cVehicle.getMethod("setMouseMoveSpeedX", float.class);
                mSetMouseSpeedY = cVehicle.getMethod("setMouseMoveSpeedY", float.class);
                mSetHoverMode = cVehicle.getMethod("setHoverMode", boolean.class);
                mGetHoverMode = cVehicle.getMethod("getHoverMode");
            } catch (Throwable ignored) {
                mGetMouseSpeedX = null;
                mGetMouseSpeedY = null;
                mSetMouseSpeedX = null;
                mSetMouseSpeedY = null;
                mSetHoverMode = null;
                mGetHoverMode = null;
            }
            // 【实测七百二十六·点6】装弹反射链：车的容器 + 这车这门枪要哪种子弹 + 子弹物品的类型。
            // 每一环各自 try（缺一环只是"装弹"这一档不生效，不影响驾驶/开火）。
            try {
                mGetInventory = cVehicle.getMethod("getInventory");
                mGetGunDataMap = cVehicle.getMethod("getGunDataMap");
            } catch (Throwable ignored) {
                mGetInventory = null;
                mGetGunDataMap = null;
            }
            try {
                Class<?> gdCls = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                mGunUseBackpackAmmo = gdCls.getMethod("useBackpackAmmo");
                mGunSelectedAmmo = gdCls.getMethod("selectedAmmoConsumer");
            } catch (Throwable ignored) {
                mGunUseBackpackAmmo = null;
                mGunSelectedAmmo = null;
            }
            try {
                Class<?> acCls = Class.forName("com.atsuishio.superbwarfare.data.gun.AmmoConsumer");
                // 【实测七百二十七·点3】SWB 自己的"这一格是不是这门枪要的弹"判据——对
                // 物品型（ItemAmmoStrategy）与枚举型（PlayerAmmoStrategy）**两种都成立**，是正解。
                mConsumerIsAmmoItem = acCls.getMethod("isAmmoItem",
                        net.minecraft.world.item.ItemStack.class);
            } catch (Throwable ignored) {
                mConsumerIsAmmoItem = null;
            }
            // 取代表物品的方法（{@code stack()}）：与上面分开 try——它缺了只是"没实体弹的武器判不出来"，
            // 不该把整条装弹链路一起关掉（各自独立是这一节所有反射的既定口径）。
            try {
                Class<?> acCls2 = Class.forName("com.atsuishio.superbwarfare.data.gun.AmmoConsumer");
                mConsumerStack = acCls2.getMethod("stack");
            } catch (Throwable ignored) {
                mConsumerStack = null;
            }
            swbOk = true;
        } catch (Throwable ignored) {
            swbOk = false;
        }
    }

    private static synchronized void initIaf() {
        if (iafInited) {
            return;
        }
        iafInited = true;
        // 普通版与社区版两个包名都试；1.21.1 社区版类名带 Entity 后缀
        String[] candidates = {
                "com.github.alexthe666.iceandfire.entity.EntityDragonBase", // 普通版（1.20.1）
                "com.iafenvoy.iceandfire.entity.EntityDragonBase",          // 社区版（1.20.1）
                "com.iafenvoy.iceandfire.entity.DragonBaseEntity",          // 社区版（1.21.1）
        };
        for (String cn : candidates) {
            try {
                Class<?> d = Class.forName(cn);
                Method stage = d.getMethod("getDragonStage");
                // 【实测七百二十】"玩家鞍位"也是公开方法，四份 jar 签名一致（返回 Vec3）
                Method riderPos = d.getMethod("getRiderPosition");
                // 【实测七百二十三】跟随档（0=站 1=坐 2=跟随）——四份 jar 同名
                Method getCmd = d.getMethod("getCommand");
                Method setCmd = d.getMethod("setCommand", int.class);
                // 【实测七百二十三】攻击两件（吐息位 + 以某实体为控制者喷一口）——保留原样
                Method strike = d.getMethod("strike", boolean.class);
                Method shoot = d.getMethod("riderShootFire", Entity.class);
                cDragon = d;
                mGetDragonStage = stage;
                mGetRiderPosition = riderPos;
                mGetCommand = getCmd;
                mSetCommand = setCmd;
                mStrike = strike;
                mRiderShootFire = shoot;
                iafOk = true;
                return;
            } catch (Throwable ignored) {
                // 换下一个类名
            }
        }
        iafOk = false;
    }

    /** 卓越前线的载具反射链是否就绪（没装 / 换版本 → false）。 */
    public static boolean swbAvailable() {
        initSwb();
        return swbOk;
    }

    /** 冰火传说的龙反射链是否就绪（普通版与社区版任一命中即 true）。 */
    public static boolean iafAvailable() {
        initIaf();
        return iafOk;
    }

    /** 模组坐骑兼容总开关（配置 combat.ride.modMounts，默认开）。关掉 = 整类不介入。 */
    public static boolean modMountsEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_MOD_MOUNTS.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 骑模组载具时要不要顺手开火（配置 combat.ride.modMountFire，默认开）。 */
    public static boolean modMountFireEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_MOD_MOUNT_FIRE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ==================== 种类探测 ==================== */

    /**
     * 这只实体是哪一类载具？不可驾 → {@code null}。
     *
     * <p>顺序有意义：先问两家模组（它们不是 Saddleable，落不进通用档），再交给
     * {@link MaidRideKit} 的通用档。
     */
    public static Kind kindOf(Entity e) {
        if (e == null) {
            return null;
        }
        if (!modMountsEnabled()) {
            return null;
        }
        if (isVehicle(e)) {
            return Kind.VEHICLE;
        }
        if (isDragon(e)) {
            return Kind.DRAGON;
        }
        return null;
    }

    /** 是不是卓越前线的载具（类型判据，反射）。 */
    public static boolean isVehicle(Entity e) {
        try {
            initSwb();
            return swbOk && cVehicle != null && cVehicle.isInstance(e);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是不是冰火传说的龙（普通版 / 社区版共用，类名判据）。 */
    public static boolean isDragon(Entity e) {
        try {
            initIaf();
            return iafOk && cDragon != null && cDragon.isInstance(e);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这只模组坐骑此刻能不能被女仆绑：载具要看是不是报废的（{@code isWreck}），
     * 龙要看阶段（{@code getDragonStage() &gt;= 2}——原版里 1 阶段的小龙是"被玩家抱着"，
     * 不能骑）。能驾 → {@code null}；否则给一句给玩家看的理由。
     */
    public static String denyReason(Entity e) {
        try {
            if (isVehicle(e)) {
                if (mIsWreck != null && Boolean.TRUE.equals(mIsWreck.invoke(e))) {
                    return "这辆载具已经报废了……";
                }
                int seats = maxPassengers(e);
                if (seats <= 0) {
                    return "这辆载具没有能坐的位子～";
                }
                return null;
            }
            if (isDragon(e)) {
                if (!isAlive(e)) {
                    return "它已经不在了……";
                }
                int stage = dragonStage(e);
                if (stage > 0 && stage < 2) {
                    return "它还太小，等它长大些再骑吧～";
                }
                return null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 载具的座位数（拿不到 → 0）。 */
    public static int maxPassengers(Entity e) {
        try {
            if (mGetMaxPassengers != null && isVehicle(e)) {
                Object v = mGetMaxPassengers.invoke(e);
                if (v instanceof Integer i) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 龙此刻的成长阶段（拿不到 → -1）。 */
    public static int dragonStage(Entity e) {
        try {
            if (mGetDragonStage != null && isDragon(e)) {
                Object v = mGetDragonStage.invoke(e);
                if (v instanceof Integer i) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean isAlive(Entity e) {
        try {
            return e.isAlive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 实测七百二十三：冰火传说龙 —— 悬空鞍位（降级方案） ==================== */

    /**
     * v1.3.0(beta) 实测七百二十三【冰火传说的龙：不再"真骑"，改成"挂在玩家骑乘位上"】。
     *
     * <h2>玩家原话（722 之后）</h2>
     * 「关于龙方面的问题，还是没能解决。我采用降级方案，骑龙的话，那就仅仅是把女仆挂在
     * 玩家的骑乘位上，但并没有真正的骑在龙上。随后龙的行动逻辑自动转变为跟随玩家。玩家
     * 手动使用日程表进行传送那也仅仅是传送女仆不传送龙。也就是说移动的逻辑仍然是龙在进行
     * 移动。女仆相当于仅仅是坐在一把悬空的同位置的椅子上而已。这个处于是开后门的无奈之举。
     * 先将原有的代码删除掉，然后替换成这个就行。此时玩家对龙进行右击会显示已经占用了。
     * 阻止一下右击骑龙的行为。」
     *
     * <h2>为什么"真骑"这条路走不通（722 的结论，留档）</h2>
     * 龙**覆写了**两参 {@code positionRider}，覆写体是「先 {@code super}、**返回之后**再判一次
     * 乘客身份」，判据是 {@code getControllingPassenger()}——它只认"主人"（1.21.1
     * {@code DragonBaseEntity.getControllingPassenger()}）或"玩家"（1.20.1
     * {@code EntityDragonBase.m_6688_}）。女仆永远不满足 → 龙把她当**嘴里的猎物**：
     * <pre>
     *   DragonBaseEntity.positionRider(passenger, callback):
     *       super.positionRider(passenger, callback);
     *       if (getControllingPassenger() == null || 不是这一位) {
     *           updatePreyInMouth(passenger);   // 摆到嘴边 + ANIMATION_SHAKEPREY（模型被带偏）
     *                                           // animationTick > 55 → 伤害×2 + stopRiding()
     *       }
     * </pre>
     * {@code ci.cancel()} 只取消**基类那一份**，覆写体在 {@code super} 返回之后照样写；722 试着
     * 在乘客自己每 tick 的末尾再抢回鞍位（{@code m_6083_} / {@code rideTick} 的 TAIL），但龙的
     * 状态机每拍都在跟她抢，结果是"横跳 / 模型只剩一块 / 被咬死"轮着来。玩家因此拍板走
     * **降级方案**——这一档就是那个方案。
     *
     * <h2>降级方案的口径（逐条对应玩家的话）</h2>
     * <ol>
     *   <li><b>不真骑</b>：{@code maid.startRiding(dragon)} 这一步**取消**。她不是乘客，
     *       龙那条猎物分支**永远不会被走到**（那是 {@code positionRider} 里的代码，只有乘客
     *       才进）——"被咬死 / 模型被带偏 / 横跳"三个症状从根上没有了。</li>
     *   <li><b>挂在骑乘位上</b>：每 tick 由 {@link #seatOnDragon} 把她 {@code setPos} 到
     *       {@link #riderSeat}（龙给**玩家**算的那个鞍位，含俯仰/飞行补偿）+ 她的身高，
     *       再过一道 {@link #freeSeatY}。玩家原话"坐在一把悬空的同位置的椅子上"——位置与
     *       玩家骑龙时**逐字相同**。</li>
     *   <li><b>不会掉下来</b>：绑上时 {@code setNoGravity(true)}，解绑时还原。她不是乘客，
     *       原版没有任何东西托着她，只能由我们负责——不设无重力就会被自己那一拍的重力拽下去。</li>
     *   <li><b>移动仍然由龙进行</b>：我们**不驱动龙**（旧的 {@code driveDragon} 已删）。龙自己
     *       那一套飞行物理照常跑。</li>
     *   <li><b>龙的行动自动转为跟随玩家</b>：绑上时把龙置 {@code setCommand(2)}（原版语义：
     *       {@code DragonAIEscortGoal.canUse()} 要求 {@code getCommand() == 2} 才跟着主人走）。
     *       解绑时还原成绑定前的值。</li>
     *   <li><b>传送只传女仆</b>：传送链路的"连人带坐骑一起搬"对龙**不再走**（她不是乘客，
     *       原版 {@code teleportTo} 也不会 unRide 掉什么）。见 {@code RideBindManager} 里
     *       {@code isDragonChairRider} 的分叉。</li>
     *   <li><b>右击龙提示"已被占用"、并且骑不上</b>：指挥棒右击时由
     *       {@code RideBindManager} 认出"这条龙正被棍子链路占着"并回一句明确提示；原版登龙
     *       那一下由 {@code denyMountForBatonHolder} 堵死。</li>
     * </ol>
     *
     * <p><b>边界（一个字节都不碰的场合）</b>：只有 <b>有主女仆 + 冰火传说的龙 + 这一对在
     * 链路表里</b>三者同时成立才介入。玩家本人骑龙、别的模组的生物当乘客、无主女仆——本类
     * 一次都不会被调到（调用方 {@code RideBindManager} 就是按链路表调度的）。
     */

    /**
     * 龙背上的**玩家鞍位**（{@code getRiderPosition()}，四份 jar 成员名逐字一致）；拿不到 → null。
     *
     * <p>这是龙自己给玩家算的那个点（{@code getRiderPosition} 里含俯仰补偿、飞行/行走抬高），
     * 我们原样借用——所以她的位置与玩家骑龙时**逐字相同**（玩家那条路也是这个点 +
     * {@code getBbHeight()} 的竖直补偿，见 {@code DragonBaseEntity.positionRider} 的鞍位分支）。
     */
    public static Vec3 riderSeat(Entity dragon) {
        try {
            initIaf();
            if (!iafOk || mGetRiderPosition == null) {
                return null;
            }
            Object v = mGetRiderPosition.invoke(dragon);
            return v instanceof Vec3 vec ? vec : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * v1.3.0(beta) 实测七百二十一【鞍位在方块里时往上抬到第一格空气】（降级方案里同样要过）。
     *
     * <h2>为什么需要它（实机日志实证）</h2>
     * 「会在背上反复横跳，**甚至直接陷到地里面窒息**」。日志里那一串
     * {@code [maidhurt] … 类型=inWall 位置=(…,-32,…)} 就是它：龙在**密闭空间**里（洞里 / 天花板
     * 很低的大厅里）时，{@code getRiderPosition()} 算出来的那个点落在方块里，而女仆
     * **没有把方块挤开的体格**（原版只有玩家/大体积生物会被"推出去"）——她就每 tick 吃
     * {@code in_wall} 窒息伤害（每秒 1 点）。降级方案里她不再是乘客、不受那条猎物分支管，
     * 但**落点仍然必须过这一道**，否则还是会被摆进墙里。
     *
     * <p>修法：把候选 Y 抬到**第一格"她放得下"的位置**：从候选点起逐格向上探（最多
     * {@link #SEAT_LIFT_MAX} 格），第一格"该格+上一格都没有碰撞方块/流体"就用它。
     * 一格都不合格就用原候选（**绝不因此不落座**——宁可抬不上去也不能"卡在原点"）。
     *
     * @return 落座用的 Y（拿不到世界 / 异常 → 原候选，调用方照常落座）
     */
    public static double freeSeatY(Entity passenger, double x, double y, double z) {
        try {
            if (passenger == null) {
                return y;
            }
            net.minecraft.world.level.Level level = passenger.level();
            if (level == null) {
                return y;
            }
            float h = Math.max(0.9f, passenger.getBbHeight());
            for (int dy = 0; dy <= SEAT_LIFT_MAX; dy++) {
                double cy = y + dy;
                if (freeCell(level, x, cy, z) && freeCell(level, x, cy + Math.min(h, 1.0), z)) {
                    return cy;
                }
            }
        } catch (Throwable ignored) {
        }
        return y;
    }

    /** 鞍位最多往上抬这么多格去找空气（再高就不是"鞍位"了）。 */
    private static final int SEAT_LIFT_MAX = 6;

    /**
     * 这一格（脚位 / 头位）"她放得下"吗——判据与项目里其余落点判定**同源**
     * （{@code MaidChunkLoadManager.standableCell}）：该格的**碰撞形状为空**即算放得下
     * （草丛/火把/雪这类无碰撞方块不挡人），不额外要求"必须是最纯的空气"。
     */
    private static boolean freeCell(net.minecraft.world.level.Level level, double x, double y, double z) {
        try {
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(x, y, z);
            return level.getBlockState(p).getCollisionShape(level, p,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把她摆到龙的"玩家鞍位"上【降级方案的内核】，**并锁定她的渲染插值**。
     *
     * <p>位置与玩家骑龙时逐字相同：{@code getRiderPosition()} + {@code getBbHeight()}，再过
     * {@link #freeSeatY}。她**不是乘客**（{@code startRiding} 那一步已取消），所以位置不由
     * 原版 {@code positionRider} 那条链条管——由我们每 tick 写一次。
     *
     * <h2>【实测七百二十九·点3】为什么"每拍 setPos"仍然会漂——原版渲染插值走的是另一个字段</h2>
     * 玩家原话：「女仆的位置还是在发生漂移。能不能强行绑定女仆坐在龙上龙的某块建模，然后每 tick
     * 锁定那个位置呢？这样子也比在空中乱飘强得多。」
     *
     * <p>反编译实证（javap {@code ClientLevel.tickEntities} / {@code EntityRenderer.render}）：
     * 实体的**渲染位置不是 {@code getX()}**，而是 {@code getPosition(partialTick)}
     * = {@code Mth.lerp(partialTick, xo, getX())}——其中 {@code xo/yo/zo} 是"上一拍的位置"，
     * 在**每只实体自己 tick 的开头**由 {@code setOldPosAndRot()} 从"当时的当前位置"拍下来
     * （bytecode：{@code getX → putfield xo}）。
     *
     * <p>于是两条曲线不同源：
     * <ul>
     *   <li><b>龙</b>：{@code xo}=龙上一拍位置，{@code getX}=龙这一拍位置 → 龙模型平滑滑动。</li>
     *   <li><b>她</b>：{@code xo} 是"上一拍 <b>{@code setOldPosAndRot} 那一刻</b>她在哪"——
     *       服务端上这恰好等于上一拍的鞍位（没问题），可<b>客户端</b>上她在那一拍之前刚被
     *       {@code lerpTo} 的位置包**限流平滑**（原版位置包每 2 tick 才发一次，客户端再 lerp
     *       三拍），所以 {@code xo} 拿到的是"平滑到一半"的位置，而 {@code getX} 又被我们
     *       硬写成这一拍的鞍位 → <b>两个端点一个来自插值中间态、一个来自最终态</b>，
     *       她的曲线于是每拍都在"往回缩一下"，观感就是**相对龙体持续漂移**。</li>
     * </ul>
     *
     * <p>修法（就是玩家说的"每 tick 锁定那个位置"）：**她的两个插值端点直接由龙的这两个端点
     * 推出来**——鞍位相对龙体的偏移量（{@code 鞍位 - 龙位置}）在一拍之内几乎不变，所以
     * <pre>
     *   她的这一拍端点 = 龙的这一拍位置 + 偏移
     *   她的上一拍端点 = 龙的上一拍位置 + 偏移
     * </pre>
     * 两条曲线的**两端点逐拍同源**，插值中间态自然重合成同一条线，漂移消失。这个算法是
     * **无状态的**（偏移当场算、不缓存任何"上一拍"），因此它与本方法在一拍里被调几次无关——
     * 她自己的 tick 之后调一次、龙 tick 之后又调一次（谁后 tick 谁说了算，见
     * {@code RideBindManager.onEntityTickPost}），两次结果完全一样，不会"越推越远"。
     *
     * <p>同时把她的**朝向**也镜像成龙这一拍的朝向（含 {@code yRotO}/{@code yBodyRot}）：
     * 龙转身时她的模型跟着转，且转的插值曲线与龙同源（旧版压根不设她的朝向，龙一转
     * 她就是"坐在原地不动"，看起来又像错位）。
     *
     * @return true = 摆好了
     */
    public static boolean seatOnDragon(Entity dragon, Entity maid) {
        try {
            if (dragon == null || maid == null) {
                return false;
            }
            Vec3 seat = riderSeat(dragon);
            if (seat == null) {
                return false;
            }
            double bb = maid.getBbHeight();
            double y = freeSeatY(maid, seat.x, seat.y + bb, seat.z);
            Vec3 now = new Vec3(seat.x, y, seat.z);
            // 【只在客户端写插值字段】{@code xo/yo/zo} 唯一的消费者是**渲染**
            // （{@code getPosition(partialTick)} → 客户端渲染线程），服务端写它没有任何收益；
            // 而 {@code xOld/yOld/zOld} 还兼作服务端碰撞扫掠的"上一拍位置"，在服务端乱写反而
            // 有风险。所以这一档严格限定在客户端那一侧（{@link RideBindManager} 的客户端分支调到这里）。
            if (maid.level() != null && maid.level().isClientSide()) {
                Vec3 origin = dragon.position();
                // 鞍位相对龙体的偏移（含俯仰/飞行补偿与"抬出方块"的 y 修正）
                double offX = now.x - origin.x;
                double offY = now.y - origin.y;
                double offZ = now.z - origin.z;
                // 龙的"上一拍端点"（原版每只实体 tick 开头由 setOldPosAndRot 拍下，客户端亦然）
                maid.xo = dragon.xo + offX;
                maid.yo = dragon.yo + offY;
                maid.zo = dragon.zo + offZ;
                maid.xOld = maid.xo;
                maid.yOld = maid.yo;
                maid.zOld = maid.zo;
            }
            maid.setPos(now.x, now.y, now.z);
            maid.setDeltaMovement(Vec3.ZERO);
            // 朝向镜像（含"上一拍朝向"，让她的转身插值与龙同源）。朝向字段两侧都写：
            // 服务端写是为了下一次同步包里带的朝向就是龙这一拍朝向（客户端 lerpTo 也读它）。
            float dyaw = dragon.getYRot();
            float dyawO = dragon.yRotO;
            maid.yRotO = dyawO;
            maid.setYRot(dyaw);
            // yBodyRot/yBodyRotO 声明在 LivingEntity 上（javap 实证）——必须转成 LivingEntity
            // 才访问得到；她本来就是 EntityMaid（LivingEntity 的子类），这个 instanceof 恒真。
            if (maid instanceof LivingEntity le) {
                le.yBodyRotO = dyawO;
                le.yBodyRot = dyaw;
            }
            maid.setYHeadRot(dyaw);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 置/撤"无重力"（她不是乘客，原版没有东西托着她——绑上时必须无重力）。 */
    public static void setGravity(Entity e, boolean gravity) {
        try {
            if (e != null) {
                e.setNoGravity(!gravity); // setNoGravity(!gravity)
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十四】把她"钉"在鞍位上：停掉她自己那套导航与速度。
     *
     * <p>她是**普通实体**（不是乘客），大脑/寻路仍然活跃——每拍都可能往外走/被别的东西推开，
     * 于是鞍位上"时不时抖一下、位置错"。723 只每 2 tick {@code setPos} 一次纠正，跟不上。
     * 724 起每 tick 摆位 + 这一档停导航（{@code getNavigation().stop()}）+ 清速度 +
     * 清 {@code WALK_TARGET}，她就老老实实待在鞍位上，位置与玩家骑龙时一致。
     *
     * <p>【实测七百二十五·点1 补一条】顺手**每拍复述坐姿**：TLM 的坐姿标志会被别的任务/
     * 日程清掉（她不是真乘客时尤其容易），一旦掉了就变回站姿模型、看起来又"跟龙分离"。
     * 这里由 {@link #setSitting} 每拍压一次，与摆位同频。
     */
    public static void freezeOnSeat(Entity maid) {
        try {
            if (maid == null) {
                return;
            }
            if (maid instanceof EntityMaid em) {
                setSitting(em, true);
            }
            if (maid instanceof net.minecraft.world.entity.Mob mob) {
                try {
                    mob.getNavigation().stop();
                } catch (Throwable ignored) {
                }
            }
            maid.setDeltaMovement(Vec3.ZERO);
            if (maid instanceof net.minecraft.world.entity.LivingEntity le) {
                le.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 坐姿（实测七百二十五：骑乘时她应当是坐姿） ==================== */

    /**
     * v1.3.0(beta) 实测七百二十五【骑乘时她应该是坐姿】——把 TLM 的"坐姿"标志开/关。
     *
     * <h2>玩家原话</h2>
     * ①「女仆骑乘的时候应该是坐姿。」
     * ②「现在女仆跟龙之间还是有较大的分离关系。看着很不自然。为什么玩家坐在龙上面就不会出现
     * 这种情况呢？」
     *
     * <h2>为什么她一直站着（反编译实证）</h2>
     * TLM 的**可见坐姿**只有一个来源——实体自己的坐姿标志
     * {@code EntityMaid.isMaidInSittingPose()}（= {@code TamableAnimal.isInSittingPose()}，
     * 读 {@code DATA_FLAGS_ID} 第 0 位）。两条渲染路径都问它：
     * <ul>
     *   <li>Gecko 模型：{@code TLMBinding} 把 molang 变量 {@code tlm.is_sitting} 绑到
     *       {@code ((EntityMaid)ctx.entity()).isMaidInSittingPose()}（反编译实证）。</li>
     *   <li>Bedrock(JS) 模型：{@code EntityMaidWrapper.isSitting()} 返回同一个
     *       {@code isMaidInSittingPose()}（反编译实证）。</li>
     * </ul>
     * 而**骑乘**这条路上她**从来不是坐姿**：原版只有 {@code AbstractHorse} 那一系
     * （马/骆驼/驴）在玩家骑上去时置坐姿；卓越前线的载具与冰火传说的龙**都不是**
     * {@code TamableAnimal} 的坐骑语义，压根没人替她置这个位。所以她骑在车上/龙上时
     * 一直用**站立模型**——玩家看到的"站着骑"与"跟龙分离"（她站在鞍位上方一整格 = 身高）
     * 都是这一个原因：站姿模型 + 按身高抬起的落点，看起来就是"悬空站着、没贴着鞍"。
     *
     * <h2>这一档做什么</h2>
     * 绑上（载具/龙/原版兽皆可）→ {@code setInSittingPose(true)}；解绑 → 还原成绑前的值
     * （原版"坐下"指令、玩家手动让她坐下等，一个字节不丢）。判据全走 TLM 自己的公开方法
     * {@code isMaidInSittingPose()/setInSittingPose(boolean)}——TLM 改名会编译期报错，
     * 不会静默失效。
     *
     * <p><b>为什么这条同时治了"跟龙分离"（玩家原话②）</b>：龙的鞍位公式
     * {@code positionRider} 摆的是「{@code getRiderPosition()} + {@code 乘客身高}」——**玩家**
     * 坐在那个点上用的是**坐姿模型**，所以贴鞍；而女仆旧版用**站姿模型**落同一个点，
     * 看起来就是"站在鞍位上方一整格、跟龙有段空档"。摆位公式与玩家逐字相同、只把模型换成
     * 坐姿，观感即与玩家一致——所以本档**只动坐姿标志，不动落点公式**。
     */
    public static void setSitting(EntityMaid maid, boolean sitting) {
        try {
            if (maid == null) {
                return;
            }
            maid.setInSittingPose(sitting);
        } catch (Throwable ignored) {
        }
    }

    /** 她此刻是不是坐姿（= TLM 渲染坐姿模型的那个标志）；拿不到 → false。 */
    public static boolean isSitting(EntityMaid maid) {
        try {
            return maid != null && maid.isMaidInSittingPose();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 龙当前的行动档（{@code getCommand()}：0=站 1=坐 2=跟随）；拿不到 → -1。 */
    public static int dragonCommand(Entity dragon) {
        try {
            if (mGetCommand != null && isDragon(dragon)) {
                Object v = mGetCommand.invoke(dragon);
                if (v instanceof Integer i) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 把龙置成"跟随主人"（{@code setCommand(2)}）——玩家原话"龙的行动逻辑自动转变为跟随玩家"。
     * 原版语义见 {@code DragonAIEscortGoal.canUse()}：{@code getCommand() == 2} 时它跟着主人走。
     */
    public static void setDragonFollow(Entity dragon) {
        try {
            if (mSetCommand != null && isDragon(dragon)) {
                mSetCommand.invoke(dragon, 2);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把龙的行动档还原成绑上之前的值（解绑时用；原值 &lt; 0 = 没读到过，不动它）。 */
    public static void restoreDragonCommand(Entity dragon, int prev) {
        try {
            if (mSetCommand != null && isDragon(dragon) && prev >= 0) {
                mSetCommand.invoke(dragon, prev);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 多部件实体的"本体"解析（实测七百二十·点2） ==================== */

    /**
     * 【实测七百二十·点2】把冰火传说龙的**部位实体**还原成**龙本体**（翅膀/尾巴/头各是一个实体，
     * 准星常打中它们，而它们会把右击转发给本体）。完整口径见 1.20.1 树同名方法。
     */
    public static Entity resolveMount(Entity target) {
        try {
            if (target == null || !isMultipartPart(target)) {
                return target;
            }
            initPart();
            if (mPartGetParent == null) {
                return target;
            }
            Object p = mPartGetParent.invoke(target);
            return p instanceof Entity parent ? parent : target;
        } catch (Throwable ignored) {
            return target;
        }
    }

    /** 它是不是"多部件实体的一块部位"（冰火传说的龙部件）。判据走父类链上的类名。 */
    public static boolean isMultipartPart(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                String n = c.getName();
                if (n.endsWith("EntityMultipartPart") || n.endsWith("MultipartPartEntity")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean partInited;
    private static Method mPartGetParent;   // MultipartPartEntity.getParent() / EntityMultipartPart.getParent()

    private static synchronized void initPart() {
        if (partInited) {
            return;
        }
        partInited = true;
        String[] candidates = {
                "com.iafenvoy.iceandfire.entity.MultipartPartEntity",        // 社区版 1.21.1
                "com.iafenvoy.iceandfire.entity.EntityMultipartPart",        // 社区版 1.20.1
                "com.github.alexthe666.iceandfire.entity.EntityMultipartPart", // 普通版
        };
        for (String cn : candidates) {
            try {
                mPartGetParent = Class.forName(cn).getMethod("getParent");
                return;
            } catch (Throwable ignored) {
                // 换下一个类名
            }
        }
        mPartGetParent = null;
    }

    /* ==================== 引擎/载具细分 ==================== */

    /** 载具的引擎类型名（大写，如 AIRCRAFT / HELICOPTER / AIRSHIP / WHEEL / TRACK / SHIP）；拿不到 → ""。 */
    public static String engineType(Entity e) {
        try {
            if (mComputed == null || mGetEngineType == null || !isVehicle(e)) {
                return "";
            }
            Object data = mComputed.invoke(e);
            if (data == null) {
                return "";
            }
            Object et = mGetEngineType.invoke(data);
            if (et instanceof Enum<?> en) {
                return en.name();
            }
            return et == null ? "" : String.valueOf(et);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /* ==================== 驱动：把"去某点"翻译成各自的 API ==================== */

    /**
     * 驱动一只模组坐骑朝 {@code target} 走。返回 true = 本类接管了驱动
     * （{@link MaidRideKit#feedNavigation} 据此跳过通用档）。
     *
     * <p>【实测七百二十三】新增第四参 {@code maid}：卓越前线的**轮椅（WHEELCHAIR）**引擎的
     * 转向只读 {@code getFirstPassenger().getYHeadRot()}（反编译 {@code wheelChairEngine} 实证），
     * 不读左右位——所以驱动层必须拿到"她"才能把目标方位写成她的头朝向。
     *
     * @param mount    坐骑（本类只处理 VEHICLE）
     * @param target   目的地点
     * @param modifier 速度倍率（载具档用它缩放油门）
     * @param maid     骑在上面的女仆（写头朝向用；可为 null）
     */
    public static boolean drive(Entity mount, Vec3 target, double modifier, Entity maid) {
        try {
            Kind k = kindOf(mount);
            if (k == Kind.VEHICLE) {
                return driveVehicle(mount, target, modifier, maid);
            }
            // 【实测七百二十三】DRAGON 不再由本类驱动（降级方案：她只是挂在鞍位上）。
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 停下（{@link MaidRideKit#stopNavigation} 的模组分叉）。 */
    public static void stop(Entity mount) {
        try {
            Kind k = kindOf(mount);
            if (k == Kind.VEHICLE) {
                stopVehicle(mount);
            }
            // 【实测七百二十三】DRAGON 无需"收手"：我们从没驱动过它。
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 卓越前线：processInput 位掩码 ---------- */

    /**
     * 卓越前线的驾驶面**只有一个入口**：{@code processInput(short)} 位掩码
     * （javap 实证 1.20.1 与 1.21.1 方法名/描述符逐字相同）：
     * <pre>
     *   0x001 左   0x002 右   0x004 前   0x008 后
     *   0x010 上   0x020 下   0x080 开火  0x100 冲刺
     * </pre>
     * 各引擎（Wheel/Track/Ship/Helicopter/Aircraft/AirShip，见
     * {@code VehicleEngineUtils}）在 {@code travel()} 里读这些标志，驱动方式**逐字照抄玩家**：
     * <pre>
     *   wheelEngine  (反编译实证): getFirstPassenger() == null → 全清输入 + power=0
     *                              前/后 累加 power（油门），左/右 累积 holdTick → setDeltaRot（转向）
     *   与 airShipEngine 同构；直升机/固定翼同理，只是 前=总距/推力、上=悬停或起落架
     * </pre>
     *
     * <p><b>两条硬边界（都来自上面这段实证）</b>：
     * <ol>
     *   <li>引擎的驾驶闸是 <b>{@code getFirstPassenger()}</b>（座位 0），**不是**
     *       {@code getControllingPassenger()}——后者在 SWB 里压根没被覆写、对谁都返回 null。
     *       所以女仆只要坐进<b>座位 0</b>（{@code startRiding(force)} 的默认落点）就能开，
     *       无需她是 Player。这正是"能通用"的支点。</li>
     *   <li>**绝不能自己写 yaw**：引擎每 tick 用 {@code setDeltaRot} 改偏航，我们若同时直接
     *       {@code setYRot} 就是两股力打架（画龙、抖）。所以朝向也走它自己的左右位，
     *       与玩家按键完全同一条路径——本类只决定"按哪些位"。</li>
     * </ol>
     *
     * <p>位语义按引擎不同（实证）：地面/船 前=油门、左右=转向；直升机 前=加总距（爬升）、
     * 后=减总距、左右=偏航、上=悬停开关；固定翼 前=推力、上=起落架（俯仰由它自己按速度算）；
     * 飞艇 上/下=升降（{@code setLiftSpeed}）。所以只有**飞艇**用上下位表达"想更高/更低"，
     * 其余引擎把高度交给它自己的物理（地面载具本来就不该飞）。
     *
     * <h2>【实测七百二十三】轮椅（WHEELCHAIR）：左右位**不被读**，转向只听乘客的头</h2>
     * 玩家原话：「可以把女仆绑在轮椅上了，但是女仆移动的路径完全就跟女仆应有的路径不符。
     * 大部分情况是坐上轮椅之后，朝轮椅面朝的方向移动个几步，然后就停在那边了。为什么骑马
     * 就不会出现这种情况呢？」——实机日志就是证据（19:15:14 起每 5 秒一行）：
     * <pre>
     *   位掩码=6 引擎=WHEELCHAIR 距目标=8格    6 = 0x002|0x004（右转 + 前进）
     *   位掩码=1 引擎=WHEELCHAIR 距目标=15格   1 = 0x001（左转）—— 距目标反而涨了
     *   位掩码=2 引擎=WHEELCHAIR 距目标=23格   2 = 0x002（右转）—— 一路涨到 23 格
     * </pre>
     * 我们一直在送左右位，但它**原地打转**，因为 {@code VehicleEngineUtils.wheelChairEngine}
     * 里跟方向有关的只有这一句（反编译实证）：
     * <pre>
     *   diffY = clamp(-90, 90, wrapDegrees(passenger0.getYHeadRot() - this.getYRot()));
     *   this.setYRot(this.getYRot() + clamp(0.4f * diffY, -5*steeringSpeed, 5*steeringSpeed));
     * </pre>
     * 也就是说：**它每 tick 把车头拉向"第一位乘客的头朝向"**，而左右位只在
     * {@code wheelEngine}（普通轮式车）里被读——轮椅这台引擎**根本没有那段**。女仆是 Mob，
     * 头朝向由她自己那套决定；她一坐上车就不再走路（乘客位移被 rideTick 吃掉），头常常
     * 停在原地不动 → 车头永远对着同一个方向 → 走几步就顶住。**骑马不会出现**正是因为它走的是
     * {@code GroundPathNavigation}（{@link MaidRideKit#feedNavigation} 的渠道一）。
     *
     * <p>所以这一档**不再指望左右位**，改成两件事一起做：① 把目标方位角写进乘客的
     * {@code setYHeadRot}；② **同时按有限速率把车头 yaw 拽向目标**——引擎那一项被
     * {@code 5*steeringSpeed} 卡住（轮椅默认 0.1 → 只有 0.5°/拍 ≈ 10°/秒，太慢就是"走几步
     * 就顶住"），我们按 {@link #HEAD_STEER_MAX_DEG_PER_TICK} 补上。两条同向叠加。
     *
     * <p>判据：只有引擎名叫 {@code WHEELCHAIR} 走这一档。<b>为什么不含 TOM6</b>：它的转向/油门
     * 整段写在 {@code passenger instanceof Player} 分支里（反编译实证），Mob 乘客压根进不去。
     * 引擎名拿不到（反射失败）→ 照旧左右位，一个字节不变。
     */
    private static boolean driveVehicle(Entity mount, Vec3 target, double modifier, Entity maid) {
        if (mProcessInput == null) {
            return false;
        }
        String eng = engineType(mount);
        double dx = target.x - mount.getX();
        double dz = target.z - mount.getZ();
        double dy = target.y - mount.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float err = wrapDegrees(desiredYaw - mount.getYRot());

        // 【实测七百二十五】飞行载具（直升机/固定翼/飞艇）**换一整套**：它们的航向/俯仰在
        // 鼠标通道里，地面那套左右位表达不了。见 driveFlight 的注释。
        if (isFlyingEngine(eng)) {
            return driveFlight(mount, modifier, eng, dy, horiz, err, maid);
        }

        // 【实测七百二十三】轮椅：引擎只认乘客头朝向，左右位不被读。
        boolean headSteer = "WHEELCHAIR".equals(eng);
        if (headSteer) {
            if (maid != null) {
                try {
                    maid.setYHeadRot(desiredYaw); // 引擎唯一读的那个
                    maid.setYRot(desiredYaw);     // 模型/身体跟上，免得扭着走
                } catch (Throwable ignored) {
                }
            }
            // 车头自己按有限速率朝目标转（补上引擎那一项被 steeringSpeed 卡住的速率）
            try {
                float step = (float) Math.max(-HEAD_STEER_MAX_DEG_PER_TICK,
                        Math.min(HEAD_STEER_MAX_DEG_PER_TICK, err));
                mount.setYRot(mount.getYRot() + step);
            } catch (Throwable ignored) {
            }
        }

        short bits = 0;
        boolean turning = Math.abs(err) > 8.0f;
        if (turning && !headSteer) {
            // 与玩家按键同一条路径：左右位 → 引擎自己累积 holdTick → setDeltaRot
            bits |= (err > 0) ? 0x002 : 0x001;
        }
        // 前进：够远就踩油门。头朝向档**不等转向**（引擎每拍都在把车头拉过来，
        // 站着不冲就是玩家看到的"走几步就停"）
        if (horiz > stopBand(mount) && (headSteer || !turning || Math.abs(err) < 40.0f)) {
            bits |= 0x004;
        }
        // 升降：只有飞艇有真正的竖直轴；其余引擎的高度归它自己的物理
        if ("AIRSHIP".equals(eng)) {
            if (dy > 1.0) {
                bits |= 0x010;
            } else if (dy < -1.0) {
                bits |= 0x020;
            }
        }
        // 倍率 > 1 → 冲刺位（各引擎里 sprint 抬高速度上限）
        if (modifier > 1.05) {
            bits |= 0x100;
        }
        try {
            mProcessInput.invoke(mount, bits);
            // 【实测七百二十四】进了停车带就**真刹车**（旧版只清前进位，车靠摩擦慢慢滑）。
            if (horiz <= stopBand(mount)) {
                brakeVehicle(mount);
            }
            logDrive(mount, "位掩码=" + bits + " 引擎=" + (eng.isEmpty() ? "?" : eng)
                    + (headSteer ? " 头朝向=" + Math.round(desiredYaw) : "")
                    + " 距目标=" + (long) horiz + "格");
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 【实测七百二十四】停车带（格）：载具在离目标多远就松油门并开始刹车。
     *
     * <p>玩家原话：「像坦克这种超大型载具……会直接把主人撞倒。应与主人拉开距离才对。」
     * 旧版全世界共用 {@code STOP_SLACK = 1.5} 格——对半宽 2 格上下的坦克等于"顶到人身上才停"。
     * 这里按**车体半宽**放大：{@link #stopSlackFor} + 车速前瞻（{@code |Δ|×10}），
     * 越快的车越早松油/刹住，大车也再不会被主人塞进车头。
     */
    private static double stopBand(Entity mount) {
        double base = stopSlackFor(mount);
        try {
            return base + mount.getDeltaMovement().length() * 10.0;
        } catch (Throwable ignored) {
            return base;
        }
    }

    /**
     * 【实测七百二十四】跟随停下的基线距离（格）＝{@code max(1.5, 车体半宽 + 1)}。
     * 小载具（轮椅/摩托，半宽 &lt; 0.5）仍是 1.5 格；坦克（半宽 ≈ 2）自动拉到 ~3 格。
     * 给 {@code RideBindManager.drive} 的"够近就站住"判据共用，两处口径一致。
     */
    public static double stopSlackFor(Entity mount) {
        try {
            if (mount != null) {
                return Math.max(1.5, (double) mount.getBbWidth() + 1.0);
            }
        } catch (Throwable ignored) {
        }
        return 1.5;
    }

    /**
     * 【实测七百二十四】真刹车：清输入 + **油门/转向/目标速度一起归零** + 按拍阻尼当前动量。
     *
     * <p>为什么"清输入"不算刹车（反编译 {@code VehicleEngineUtils} 实证）：引擎里没有前进/后退
     * 位时只做 {@code setPower(power * 0.96)}，而地面每拍的摩擦系数只有 {@code 0.54~0.79}
     * （{@code wheelEngine:250-257}、{@code wheelChairEngine:996-999}）——power 从 1 衰减到
     * 0.1 要 ~55 拍，deltaMovement 也一路拖着走。这正是玩家说的"没有刹车机制、不断漂移"。
     * 把 {@code power/deltaRot/targetSpeed} 直接写零 + 每拍乘 0.45 阻尼，车就当场停住。
     *
     * <p>阻尼安全：SWB 的 {@code setDeltaMovement} 覆写体只在**加速**（{@code |新| > |旧|} 且
     * 加速度 &gt; 8）时限幅，减速是直通的（反编译 {@code VehicleEntity:5474-5487}）。
     */
    static void brakeVehicle(Entity mount) {
        if (mount == null) {
            return;
        }
        try {
            if (mSetPower != null) {
                mSetPower.invoke(mount, 0.0f);
            }
            if (mSetDeltaRot != null) {
                mSetDeltaRot.invoke(mount, 0.0f);
            }
            if (mSetTargetSpeed != null) {
                mSetTargetSpeed.invoke(mount, 0.0d);
            }
            mount.setDeltaMovement(mount.getDeltaMovement().scale(0.45));
        } catch (Throwable ignored) {
        }
    }

    private static void stopVehicle(Entity mount) {
        try {
            // 【实测七百二十五】飞行载具（直升机/固定翼）**没有"刹车"**：空中把 power 归零就是
            // 掉高度（引擎里直升机 power 靠总距维持升力，反编译实证）。所以飞行档只清输入位、
            // **不**调 brakeVehicle，并把鼠标通道也松手（偏航/俯仰回中，让它自己稳住姿态）。
            String eng = engineType(mount);
            if (isFlyingEngine(eng)) {
                if (mProcessInput != null) {
                    mProcessInput.invoke(mount, (short) 0);
                }
                mSetMouseX(mount, 0.0f);
                mSetMouseY(mount, 0.0f);
                if (mSetHoverMode != null) {
                    try {
                        mSetHoverMode.invoke(mount, true); // 站着不动 → 悬停
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (mProcessInput != null) {
                mProcessInput.invoke(mount, (short) 0);
            }
            // 【实测七百二十四】地面停车 = 真刹车（旧版只清输入，车会继续滑）。
            brakeVehicle(mount);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 实测七百二十五：飞行载具（直升机/固定翼/飞艇）的驾驶 ---------- */

    /**
     * 这个引擎是不是"航向/俯仰走鼠标通道"的飞行载具。
     *
     * <p><b>只含直升机与固定翼</b>：反编译比对三个引擎（{@code helicopterEngine} /
     * {@code aircraftEngine} / {@code airShipEngine}）——只有前两者的偏航/俯仰写在
     * {@code getMouseMoveSpeedX/Y} 上；<b>飞艇的偏航走左右位、升降走上下位</b>（它自己有
     * {@code setLiftSpeed} 竖直轴），走地面那套位掩码就够，所以**不进本档**。
     */
    private static boolean isFlyingEngine(String eng) {
        return "HELICOPTER".equals(eng) || "AIRCRAFT".equals(eng);
    }

    /**
     * 【实测七百二十六·点3】这只坐骑是不是"飞行载具"（卓越前线的直升机 / 固定翼）——
     * 空战那一档（{@link MaidAirCombat}）据此决定要不要接管"去哪"。
     *
     * <p>判据与 {@link #isFlyingEngine} 同源（同一个引擎名集合），只是这一条对外公开、
     * 供 {@code RideBindManager} 问。飞艇**不算**：它的偏航/升降走位掩码，接敌盘旋那套
     * "爬升 + 绕圈"的坐标语义对它没有意义（而且它本来也不快）。
     */
    public static boolean isFlyingVehicle(Entity e) {
        try {
            return isVehicle(e) && isFlyingEngine(engineType(e));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.3.0(beta) 实测七百二十五【女仆驾驶直升机：左右与升降都不动】。
     *
     * <h2>玩家原话</h2>
     * 「女仆驾驶直升机的时候堪称灾难，完全不会左右移动和直上直下。」
     *
     * <h2>根因（反编译 {@code VehicleEngineUtils.helicopterEngine} 实证）</h2>
     * 直升机的**航向/俯仰/滚转**三项全都写在**鼠标通道**上，位掩码里根本没有对应位：
     * <pre>
     *   setXRot(xRot + (onGround ? 0 : 1.5) * pitchSpeed * getMouseMoveSpeedY() * propeller)   // 俯仰
     *   setYRot(yRot + yawSpeed * clamp(… * getMouseMoveSpeedX() * propeller, -10, 10))        // 偏航
     *   setZRot(roll - rollSpeed * (deltaRot + (onGround ? 0 : 0.25) * getMouseMoveSpeedX()…)) // 滚转
     * </pre>
     * 左右位（0x001/0x002）在直升机里**只**影响 {@code deltaRot}（滚转），**不改偏航**——
     * 所以旧版"送左右位"= 机头不转 = 原地打转/不动。升降同理：引擎没有竖直轴，高度全靠
     * <b>总距</b>（前位 0x004 加、后位/下位减，反编译 {@code up && EngineStartOver → power += …}）。
     * 旧版只在 {@code horiz > 停车带} 时踩前进位、近了就 {@code brakeVehicle} 把 power 归零 →
     * 空中直接掉高度、反复"飞出去再被拉回来"。实机日志正是这一串：
     * {@code 位掩码=5 引擎=HELICOPTER 距目标=11格 → 38格 → 42格}，然后每几秒一条
     * "距离超 48 格，已连人带坐骑传送回主人身边"。
     *
     * <h2>玩家骑直升机时是怎么飞的</h2>
     * ① 鼠标左右 → X 通道 → 偏航+滚转（转向）；② 鼠标上下 → Y 通道 → 俯仰（负值抬头 = 爬升，
     * 正值低头 = 前飞）；③ 前进键 → 总距上升、后/下键 → 总距下降；④ 上键 → 切悬停。
     * <b>飞行载具没有"刹车"</b>——松手是缓慢衰减，我们绝不能在空中把总距归零。所以本档：
     * <ul>
     *   <li><b>偏航/俯仰走鼠标通道</b>：直接写 X/Y（引擎唯一读的输入），与玩家推鼠标逐字同路径。</li>
     *   <li><b>升降走总距</b>：目标在头顶 → 前进位抬总距 + 抬头；在脚下 → 后位压总距 + 低头；
     *       高度差进死区就松手（保持当前总距），<b>绝不调 brakeVehicle</b>。</li>
     *   <li><b>平飞</b>：够远就低头前倾＋抬总距，让它朝目标压过去。</li>
     *   <li><b>悬停</b>：贴到目标且高度对齐 → 开悬停（引擎自己摆平姿态、衰减速度）。</li>
     * </ul>
     * 固定翼同款（{@code aircraftEngine} 的偏航/俯仰也在鼠标通道；它的前/后位是推力、
     * 高度靠俯仰+速度，所以这里只按高度差给俯仰、按远近给推力，不给竖直位）。
     *
     * <p>写不出鼠标通道（反射失败）→ 退回地面那套位掩码（一个字节不变）。
     */
    private static boolean driveFlight(Entity mount, double modifier, String eng,
                                       double dy, double horiz, float err, Entity maid) {
        if (mSetMouseSpeedX == null && mMouseInput == null) {
            return false; // 探测失败 → 交回地面档
        }
        try {
            boolean heli = "HELICOPTER".equals(eng);
            boolean airCombat = MaidAirCombat.enabled();
            double dead = airCombat ? FLIGHT_HOVER_DEADZONE : FLIGHT_ALT_DEADZONE;
            // 【实测七百二十八】只用于日志区分两档（高度本身已由目标点 Y 表达）。
            boolean fighting = airCombat && maid instanceof EntityMaid em && MaidAirCombat.inCombat(em);

            // 【实测七百二十九·点1·关键返修——"悬停"与"能飞"是互斥的，旧版把它们焊死了】
            //
            // 727 那一版把悬停档设成 `heli && 开关开着` = **常驻**，于是引擎每拍都在做这三件
            // （反编译 {@code helicopterEngine:590-599} 实证）：
            //   ① pitchSpeed *= 0.2（俯仰权限只剩两成）；
            //   ② setXRot(xRot * 0.97) 且 xRot -= 0.5*Δ·viewVec（**主动把机头压回水平**）；
            //   ③ setDeltaMovement(Δ.multiply(0.95, 1, 0.95))（水平速度每拍打 0.95 折）。
            // 而直升机**唯一**的水平推进就是"低头 → 升力分出水平分量"
            // （{@code add(getUpVec().scale(propeller*lift*0.66))}，:686）——① 让它几乎压不下去、
            // ② 每拍又把它拉回来、③ 把刚推出来的速度按指数衰减（半衰期 ~13 拍）。
            // 三条合起来 = 水平方向被彻底锁死 → 正是玩家说的「彻底失去了前后左右移动能力，
            // 只会上下飞行」。
            //
            // 所以悬停档改成**只在"水平已经停在目标点上"时开**：那时不需要侧移，悬停档给的两件
            // 东西（姿态自稳 + 竖直速度反调总距、系数 0.01 比非悬停档的 0.002 大 5 倍）正是
            // "稳稳悬停"要的；一旦要挪窝（盘旋/接敌/跟随主人）立刻退出，把俯仰权限和水平速度
            // 还给引擎。这才是引擎设计悬停档的用法。
            //
            // 判据用「够近 **且** 水平速度真的停了」而不是只看距离：盘旋时她的目标点是一直在动的
            // 「追逐胡萝卜」，距离会在死区边缘反复穿越；只看距离会让悬停档每几拍开关一次（抖）。
            // 加上"速度已停"这一条，跟随站定的主人 → 悬停；绕圈/赶路 → 退出，全程没有抖动。
            double hspeed = horizontalSpeed(mount);
            boolean parked = heli && airCombat && horiz <= FLIGHT_ARRIVE && hspeed < FLIGHT_PARK_SPEED;

            // ① 偏航：写 X 通道（引擎里那一项被 clamp(…, -10, 10) 卡住，取值与玩家推鼠标同档）
            float yawCmd = clamp(err, -FLIGHT_YAW_MAX, FLIGHT_YAW_MAX);
            mSetMouseX(mount, yawCmd);

            // ② 俯仰：写 Y 通道。约定（反编译实证）：mouseY 正值 → xRot += → 低头 → 前飞；
            //    负值 → 抬头 → 爬升（直升机）/ 拉起来（固定翼）。
            double altErr = dy;                        // >0 = 目标更高
            double flatDist = Math.max(horiz - FLIGHT_ARRIVE, 0.0);
            float pitchCmd = 0.0f;
            boolean turning = Math.abs(err) >= 60.0f;
            if (!turning) {
                if (Math.abs(altErr) > dead) {
                    // 先对齐高度：目标更高 → 抬头（负），更低 → 低头（正）
                    pitchCmd = (float) clamp(-altErr * FLIGHT_PITCH_PER_BLOCK,
                            -FLIGHT_PITCH_MAX, FLIGHT_PITCH_MAX);
                } else if (flatDist > 0.0) {
                    // 高度已对齐 → 低头前飞。**这是直升机唯一的水平推进手段**（见上面那段：
                    // 升力沿机体上方向量，机头一低就分出水平分量）；悬停档此时已退出，
                    // 俯仰权限是满的，所以这一条真的推得动她。
                    pitchCmd = (float) clamp(flatDist * FLIGHT_PITCH_PER_BLOCK,
                            0.08, FLIGHT_PITCH_MAX);
                }
            }
            mSetMouseY(mount, pitchCmd);

            // ③ 总距：**PD 控制**（按预测落点决定升降），不再是"差多少就顶多少"的 bang-bang。
            //
            // 【实测七百二十九·点1·为什么旧版会"先往上飞三格，然后再慢慢掉下来"】
            // 旧版是纯死区开关：`altErr > 0.75 → 一直按抬总距`、`altErr < -0.75 → 一直按压总距`，
            // 中间没有阻尼。总距一按就一路加到 0.12 上限（{@code :638-641}），升力随之过剩 → 冲过头
            // → 反过来一路压下去 → 又冲过头，形成**极限环**。实机日志正是这一串：
            // {@code 高差=3 → -4 → -1 → 2 → 0 → -4 → -3 → 0}（每 5 秒一条，永远在 ±4 格之间荡）。
            // 玩家看到的就是"先往上飞三格、然后慢慢掉下来"，且**永远稳不住**（=做不到悬停）。
            //
            // 修法：用**实测竖直速度**把目标点提前。`预测落点 = 高差 - 竖直速度 × 提前量`——
            // 已经在上升就把"还差多少"算少（提前松油、不会冲过），已经在掉就把"还差多少"算多
            // （提前加油、接住下坠）。这就是 PD 里的 D 项，专门治极限环。
            short bits = 0;
            if (heli) {
                double vy = verticalSpeed(mount);
                double lead = altErr - vy * FLIGHT_VY_LEAD;
                if (lead > dead) {
                    bits |= 0x004;                     // 抬总距（爬升）
                } else if (lead < -dead) {
                    bits |= 0x008;                     // 压总距（下降）
                }
                // 【为什么"两个位都不按"才是对的】反编译实证：没按任何总距位时，引擎会按**竖直
                // 速度**反调总距（{@code :657-660}，悬停档系数 0.01）——它自己就是一台高度保持
                // 控制器。旧版只要"水平还远"就补一个 0x004 当"前进推力"，可 0x004 就是
                // forwardInputDown = **抬总距**（{@code :628-641}）→ 一边说高度够了、一边继续爬。
                // 所以**这条分支整个删掉**：水平移动只走上面 ② 的俯仰，高度只走这里的总距。
                if (mSetHoverMode != null) {
                    try {
                        mSetHoverMode.invoke(mount, parked);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                // 固定翼：前/后位是推力；高度只由俯仰决定
                if (horiz > FLIGHT_ARRIVE) {
                    bits |= 0x004;
                } else if (horiz < FLIGHT_ARRIVE * 0.5) {
                    bits |= 0x008;                     // 太近 → 减速
                }
            }
            if (modifier > 1.05) {
                bits |= 0x100;                         // 冲刺位（引擎里抬高速度上限）
            }
            mProcessInput.invoke(mount, bits);
            // 【关键】飞行档**绝不 brakeVehicle**：空中把 power 归零就是"掉高度 + 被反复拉回"。
            logDrive(mount, "飞行档 引擎=" + eng + (parked ? " 悬停档(停住)" : "")
                    + (airCombat ? (fighting ? " 接敌档(敌上" + (long) MaidAirCombat.fightAltCfg() + "格)"
                            : " 跟随档(离地" + (long) MaidAirCombat.followAltCfg() + "格)") : "")
                    + " 鼠标X=" + Math.round(yawCmd)
                    + " 鼠标Y=" + Math.round(pitchCmd * 100) + "% 位掩码=" + bits
                    + " 竖直速度=" + fmt2(verticalSpeed(mount))
                    + " 距目标=" + (long) horiz + "格 高差=" + (long) dy);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百二十九·点1】实测竖直速度（格/拍）——PD 高度控制的 D 项。
     *
     * <p>直升机的竖直速度**是**引擎每拍用升力积分出来的 {@code deltaMovement.y}（反编译
     * {@code helicopterEngine:686}：{@code deltaMovement.add(getUpVec().scale(propeller*lift*0.66))}），
     * 所以读它就是把"当前正在往上还是往下、多快"拿到手——这正是旧版死区开关缺的那一项。
     * 拿不到 → 0（退化成纯 P，仍比旧版好）。
     */
    private static double verticalSpeed(Entity mount) {
        try {
            if (mount == null) {
                return 0.0;
            }
            return mount.getDeltaMovement().y;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /**
     * 【实测七百二十九·点1】实测水平速度（格/拍）——用来判断"她是不是真的停住了"，
     * 见 {@code parked} 那一段。只取 xz 分量（竖直速度归 {@link #verticalSpeed}）。
     */
    private static double horizontalSpeed(Entity mount) {
        try {
            if (mount == null) {
                return 0.0;
            }
            Vec3 dm = mount.getDeltaMovement();
            return Math.sqrt(dm.x * dm.x + dm.z * dm.z);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /**
     * 【实测七百二十九·点1】"停住了"的水平速度阈值（格/拍）——低于它才允许进悬停档。
     * 取 0.05 ≈ 每秒 1 格（直升机巡航速度是每秒 10 格上下的量级），所以这一条只拦住"真在原地"，
     * 不会误判缓慢修正。
     */
    private static final double FLIGHT_PARK_SPEED = 0.05;

    /** 小工具：保留两位（日志用）。 */
    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * 【实测七百二十九·点1】PD 高度控制的"提前量"（拍）：把目标点按**当前竖直速度**折算成
     * "再飞这么多拍会到哪"，用那个预测落点决定此刻该不该给总距。
     *
     * <p>取值依据：引擎的总距从按下到升力见效要经过
     * {@code power → propellerRot}（{@code lerp(0.18f, …, power)}，{@code :680}）这一层一阶滞后，
     * 时间常数约 1/0.18 ≈ 5.5 拍；再叠加 {@code deltaMovement} 自己一拍的积分——所以提前量取
     * {@code 10} 拍（约半秒）刚好覆盖"给油 → 见效"这条链，既压得住冲过头、又不至于抖。
     */
    private static final double FLIGHT_VY_LEAD = 10.0;

    /**
     * 飞行档的偏航上限（写进鼠标 X 通道的值）。引擎里那一项被 {@code clamp(…, -10, 10)}
     * 卡住，所以这里的量级与玩家推鼠标同一档：取 6 ≈ 中速转向，不至于瞬转画龙。
     * 俯仰同理见 {@link #FLIGHT_PITCH_MAX}。
     */
    private static final float FLIGHT_YAW_MAX = 6.0f;
    /** 飞行档前倾/抬头角上限（相对引擎那一项的钳制区间，取保守值）。 */
    private static final float FLIGHT_PITCH_MAX = 0.6f;
    /** 每 1 格高度差给多少俯仰输入（收敛用）。 */
    private static final float FLIGHT_PITCH_PER_BLOCK = 0.04f;
    /**
     * 到达判据（格）：水平距离进这个带就不再前倾，改去对齐高度/悬停。
     * 【实测七百二十七·点1】从 6.0 收到 3.0——盘旋半径本身就可能是 3~6 格，旧值等于
     * "还没到盘旋圈就判定到达、不再给前飞输入"，圈会松。
     */
    private static final double FLIGHT_ARRIVE = 3.0;
    /** 高度死区（格，非悬停档）：竖直差进这个带就不动总距。 */
    private static final double FLIGHT_ALT_DEADZONE = 2.0;
    /**
     * 【实测七百二十七·点1 / 七百二十九·点1】高度死区（格）：{@code 0.75}。
     *
     * <p>0.75 是**目标值**——玩家要"稳稳悬停/同高度盘旋"，死区大了就会在带里晃。
     * <p>【七百二十九 的关键区别】旧版把它当**死区开关**用（差超过 0.75 就一路顶总距到上限），
     * 于是形成极限环（实机日志 {@code 高差=3 → -4 → -1 → 2 → 0 → -4}，永远荡）。本版起这个数
     * 只当**PD 控制器的容差**——`lead = 高差 - 竖直速度 × 提前量` 进这个带才松手，靠 D 项
     * 提前量把过冲吃掉。见 {@code driveFlight} 里那一段。
     */
    private static final double FLIGHT_HOVER_DEADZONE = 0.75;

    /** 写鼠标 X 通道（引擎的偏航/滚转输入）；拿不到方法 → 不动。 */
    private static void mSetMouseX(Entity mount, float v) {
        try {
            if (mSetMouseSpeedX != null) {
                mSetMouseSpeedX.invoke(mount, v);
            } else if (mMouseInput != null) {
                mMouseInput.invoke(mount, (double) v, 0.0d);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 写鼠标 Y 通道（引擎的俯仰输入）；拿不到方法 → 不动。 */
    private static void mSetMouseY(Entity mount, float v) {
        try {
            if (mSetMouseSpeedY != null) {
                mSetMouseSpeedY.invoke(mount, v);
            } else if (mMouseInput != null) {
                mMouseInput.invoke(mount, 0.0d, (double) v);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十四】把她的攻击目标写进**炮塔/武器位的 AI 目标**——车自己那套瞄准开火
     * 读的就是这两个 UUID（反编译 {@code VehicleEntity.baseTick:3751-3768} →
     * {@code turretAutoAimFromUuid} / {@code passengerWeaponAutoAimFormUuid}）。
     *
     * <p>玩家原话：「骑坦克的时候攻击欲望太低了，也不会使用坦克上的炮弹。逻辑应该是和骑马远程
     * 攻击的运动逻辑一样。」——旧版只写 {@code maid.setTarget}，而车那两条链路**从不读乘客的
     * {@code getTarget}**：它读的是 {@code getAiTurretTargetUUID()}（炮塔控制位是 Mob 时走
     * {@code turretAutoAimFromUuid}）与 {@code getAiPassengerWeaponTargetUUID()}（武器位同理）。
     * 两个 UUID 一直是空 → 炮塔恒无目标 → 从不算弹道、从不开炮。这里把目标 UUID 补上。
     *
     * <p>有炮塔/武器位且控制位确实坐着 Mob 时才写；没人坐的那一路写 {@code ""} 清掉，
     * 免得留在车上的旧 UUID 让空位炮塔自己乱瞄。
     */
    public static void applyVehicleAiTargets(Entity mount, LivingEntity target) {
        try {
            if (!isVehicle(mount)) {
                return;
            }
            String uuid = (target == null) ? "" : target.getStringUUID();
            if (mSetAiTurretUUID != null && isControllerMob(mount, mHasTurret, mGetTurretCtrlIdx)) {
                mSetAiTurretUUID.invoke(mount, uuid);
            }
            if (mSetAiWeaponUUID != null && isControllerMob(mount, mHasWeaponStation, mGetWeaponCtrlIdx)) {
                mSetAiWeaponUUID.invoke(mount, uuid);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 这辆车有没有这个位 / 该位上此刻坐的是不是 Mob（反编译实证：只有 Mob 控制位才走 AI 瞄准）。 */
    private static boolean isControllerMob(Entity mount, Method hasSlot, Method ctrlIdx) {
        try {
            if (hasSlot == null || ctrlIdx == null) {
                return false;
            }
            Object has = hasSlot.invoke(mount);
            if (!Boolean.TRUE.equals(has)) {
                return false;
            }
            Object idxObj = ctrlIdx.invoke(mount);
            int idx = idxObj instanceof Integer i ? i : -1;
            if (idx < 0) {
                return false;
            }
            Object nth = mGetNthEntity == null ? null : mGetNthEntity.invoke(mount, idx);
            return nth instanceof net.minecraft.world.entity.Mob;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 头朝向档每拍最多把车头转多少度（{@link #driveVehicle}）。12 ≈ 240°/秒，
     * 比引擎自己那 10°/秒 快得多（这就是"能转弯"与"顶住不动"的差别），又不至于瞬转画龙。
     */
    private static final double HEAD_STEER_MAX_DEG_PER_TICK = 12.0;

    /* ==================== 实测七百二十六·点6：女仆自己把子弹搬进载具弹药容器 ==================== */

    /** 装弹开关（配置 combat.ride.ammoFeed，默认开）。 */
    public static boolean ammoFeedEnabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_RIDE_AMMO_FEED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * v1.3.0(beta) 实测七百二十六·点6【女仆自己往载具装弹】。
     *
     * <h2>玩家原话</h2>
     * 「卓越前线，如果女仆身上有这个载具对应的子弹。能不能让女仆自己把弹扔进装弹区里面呢？」
     *
     * <h2>根因（反编译实证）</h2>
     * 车的枪弹从 {@code GunData.countBackupAmmo(getAmmoSupplier())} / {@code withdrawAmmo} 取，
     * 而 {@code VehicleEntity.getAmmoSupplier()} 返回的是**车自己**；车又注册了
     * {@code Capabilities.ItemHandler.ENTITY} → {@code getInventory()}（{@code VehicleContainerHandler}，
     * 反编译 {@code ModCapabilities$registerCapabilities$9} 实证）——也就是说车的"备用弹药"
     * 只在**车自己的容器**里找。她背包里的子弹在**她**身上，车根本看不见（
     * {@code InventoryTool.countAmmoItem(VehicleEntity)} 走的是车那个 capability）。
     * 所以旧版她身上带再多对得上的子弹，也一枪都打不出来。
     *
     * <h2>本方法做什么</h2>
     * 找出**这辆车当前武器实际吃的那种子弹类型**（从车里那门枪的 {@code selectedAmmoConsumer()} 的
     * {@code getPlayerAmmoType()} 读出来，读不到就用 {@code getAmmo()} 名字反查），
     * 然后把她背包里**属于这一种**的弹药搬进车的容器（{@code insertItem}）。
     * 对不上的子弹一件都不动。
     *
     * <p>节流：调用方（{@code RideBindManager}）每 0.5 秒调一次就够——弹药消耗远慢于此。
     *
     * @return 这一次搬进去的**件数**（0 = 没搬 / 没有对得上的 / 车装满了）
     */
    /**
     * v1.3.0(beta) 实测七百二十六·点6【女仆自己往载具装弹】＋ 实测七百二十七·点3【返修：一条都没搬】。
     *
     * <h2>玩家原话</h2>
     * 「卓越前线，如果女仆身上有这个载具对应的子弹。能不能让女仆自己把弹扔进装弹区里面呢？」
     * （七百二十七 复报：「扫描女仆的背包换弹机制似乎没能实现。」）
     *
     * <h2>为什么"备用弹药"必须搬进车容器（七百二十六 已定位）</h2>
     * 车的枪弹从 {@code GunData.countBackupAmmo(getAmmoSupplier())} / {@code withdrawAmmo} 取，
     * 而 {@code VehicleEntity.getAmmoSupplier()} 返回的是**车自己**；车又注册了
     * {@code Capabilities.ItemHandler.ENTITY} → {@code getInventory()}（{@code VehicleContainerHandler}，
     * 反编译实证）——车的"备用弹药"只在**车自己的容器**里找。她背包里的子弹在**她**身上，
     * 车根本看不见。所以必须把子弹搬进车的容器。
     *
     * <h2>为什么七百二十六 一颗都没搬（这一版修的根因，反编译双实证）</h2>
     * 七百二十六 是**按 {@code Ammo} 枚举**去匹配她的子弹的，而 SWB 的车武器分两条完全不同的路
     * （{@code AmmoConsumeStrategy} 实证）：
     * <ol>
     *   <li><b>物品型</b>（{@code ItemAmmoStrategy}）——配置写 {@code "AmmoType": "superbwarfare:small_shell_he"}
     *       这种**普通物品 id**（AH-6 的机炮/火箭弹都是这一类）。它的
     *       {@code getPlayerAmmoType()} <b>恒为 null</b>，弹就是那个物品本身。旧版读 null → 直接
     *       {@code return 0}，一颗都不搬。</li>
     *   <li><b>枚举型</b>（{@code PlayerAmmoStrategy}）——配置写 {@code "@rifle"} 这种。它才有
     *       {@code getPlayerAmmoType()}。而旧版判物品用的是 {@code AmmoSupplierItem}
     *       （只有步枪弹那种"弹药补给物品"才是这个类）——对物品型的武器**永远不匹配**。</li>
     * </ol>
     * 两道都错，所以实机日志里一条「模组坐骑·装弹」都没有。
     *
     * <h2>正解（本版）</h2>
     * 不问"这是什么类型"，直接问 SWB 自己：{@code AmmoConsumer.isAmmoItem(stack)}
     * （= {@code MinecraftUtil.isSameItemStack(stack, consumer.stack())}，反编译实证）。
     * 它对物品型（stack 就是那个物品）与枚举型（{@code PlayerAmmoStrategy.init} 也会
     * {@code setStack(ammoType.getItemStack())}）**两种都成立**，而且顺带把 NBT/组件差异也判掉——
     * 这是"这门枪吃不吃这一格"的唯一权威判据。把车上**每一门吃背包弹的武器**的 consumer 都收进来，
     * 她背包里**命中任意一门**的子弹就搬进去。
     *
     * <p>节流：调用方（{@code RideBindManager}）每 0.5 秒调一次就够——弹药消耗远慢于此。
     *
     * @return 这一次搬进去的**件数**（0 = 没搬 / 没有对得上的 / 车装满了）
     */
    public static int feedVehicleAmmo(Entity mount, EntityMaid maid) {
        try {
            if (!ammoFeedEnabled() || mount == null || maid == null) {
                return 0;
            }
            if (!isVehicle(mount) || mGetInventory == null || mGetGunDataMap == null) {
                return 0;
            }
            java.util.List<Object> consumers = wantedConsumers(mount);
            if (consumers.isEmpty()) {
                return 0; // 这车没有任何"吃背包弹"的武器 → 无事可做
            }
            Object container = mGetInventory.invoke(mount);
            if (!(container instanceof net.neoforged.neoforge.items.IItemHandler inv)) {
                return 0;
            }
            // 从她身上取对得上的子弹：主手 → 副手 → 背包（含精妙背包/旅行者背包，走 TLM 的
            // getAvailableBackpackInv）。抽出来立刻塞进车容器；塞不下的原样还回她背包。
            int moved = 0;
            moved += moveFrom(maid.getHandsInvWrapper(), consumers, inv);
            moved += moveFrom(availableInv(maid), consumers, inv);
            if (moved > 0) {
                logVehicleAmmo(mount, moved);
            }
            return moved;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 收起这辆车上**每一门吃背包弹的武器**的弹药判据（{@code AmmoConsumer} 实例）。
     *
     * <p>过滤条件与 SWB 自己一致：{@code GunData.useBackpackAmmo()} 为 true（{@code MAGAZINE <= 0}，
     * 即"不带自带弹匣、吃外部弹药"的那一类），且它的 {@code selectedAmmoConsumer()} 拿得到、
     * 且 {@code stack()} 非空（EMPTY/INVALID 型没有可搬运的实体弹，跳过）。
     */
    private static java.util.List<Object> wantedConsumers(Entity mount) {
        java.util.List<Object> out = new java.util.ArrayList<>(2);
        try {
            Object mapObj = mGetGunDataMap.invoke(mount);
            if (!(mapObj instanceof java.util.Map<?, ?> map) || map.isEmpty()) {
                return out;
            }
            for (Object gd : map.values()) {
                if (gd == null || mGunUseBackpackAmmo == null || mGunSelectedAmmo == null) {
                    continue;
                }
                Object useBackpack = mGunUseBackpackAmmo.invoke(gd);
                if (!Boolean.TRUE.equals(useBackpack)) {
                    continue; // 这把枪自带弹匣 → 不吃外部弹
                }
                Object consumer = mGunSelectedAmmo.invoke(gd);
                if (consumer == null || mConsumerIsAmmoItem == null) {
                    continue;
                }
                // 没实体弹的判据（EMPTY/INVALID/ENERGY）直接跳过——它们的 stack() 是空的，
                // 搬进去也没意义。（isAmmoItem 对空 stack 的语义各家实现不一，这里显式挡一道。）
                Object st = consumerStack(consumer);
                if (st instanceof net.minecraft.world.item.ItemStack iss && !iss.isEmpty()) {
                    out.add(consumer);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 取某个 {@code AmmoConsumer} 的代表物品（{@code stack()}）；拿不到 → null。 */
    private static Object consumerStack(Object consumer) {
        try {
            if (mConsumerStack == null) {
                return null;
            }
            return mConsumerStack.invoke(consumer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 她可用于装弹的背包（含额外容器拉取）；拿不到 → null。 */
    private static net.neoforged.neoforge.items.IItemHandler availableInv(EntityMaid maid) {
        try {
            return maid.getAvailableBackpackInv();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 从一个物品栏里把"这车上任意一门武器吃得下的子弹"搬进车容器。命中一件就抽 1 件、立刻塞；
     * 塞不下（返回的剩余非空）就把它原样还回同一个格子。
     *
     * <p>为什么抽 1 件而不是整摞：{@code ItemHandlerHelper.insertItem} 会自己按上限合并，
     * 抽 1 件最省事、也最难出错（一次调用只动一格）。循环上限 = 该栏格数，绝不死锁。
     */
    private static int moveFrom(net.neoforged.neoforge.items.IItemHandler from,
                                java.util.List<Object> consumers,
                                net.neoforged.neoforge.items.IItemHandler into) {
        if (from == null || into == null || consumers.isEmpty()) {
            return 0;
        }
        int moved = 0;
        try {
            int slots = from.getSlots();
            for (int i = 0; i < slots; i++) {
                net.minecraft.world.item.ItemStack stack = from.getStackInSlot(i);
                if (!stackIsWantedAmmo(stack, consumers)) {
                    continue;
                }
                net.minecraft.world.item.ItemStack one = from.extractItem(i, 1, false);
                if (one.isEmpty()) {
                    continue;
                }
                // ItemHandlerHelper.insertItem 会自己找空位/合并；返回的剩余非空 = 塞不下
                net.minecraft.world.item.ItemStack rest =
                        net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(into, one, false);
                if (!rest.isEmpty()) {
                    from.insertItem(i, rest, false); // 车装满了 → 原样退回，别吞她的东西
                    break;
                }
                moved++;
            }
        } catch (Throwable ignored) {
        }
        return moved;
    }

    /**
     * 这一格物品是不是"这车上某门武器吃得下的弹"——判据走 SWB 自己的
     * {@code AmmoConsumer.isAmmoItem(stack)}（对物品型与枚举型**两种都成立**，见
     * {@link #feedVehicleAmmo} 的说明）。
     */
    private static boolean stackIsWantedAmmo(net.minecraft.world.item.ItemStack stack,
                                             java.util.List<Object> consumers) {
        try {
            if (stack == null || stack.isEmpty() || consumers.isEmpty() || mConsumerIsAmmoItem == null) {
                return false;
            }
            for (Object consumer : consumers) {
                Object ok = mConsumerIsAmmoItem.invoke(consumer, stack);
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 装弹留痕（节流 5 秒/车）：日志搜「模组坐骑·装弹」。 */
    private static void logVehicleAmmo(Entity mount, int moved) {
        try {
            long now = System.currentTimeMillis();
            Long last = AFEED_AT.get(mount.getUUID());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            AFEED_AT.put(mount.getUUID(), now);
            if (AFEED_AT.size() > 512) {
                AFEED_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·装弹", describeKind(mount)
                    + " 女仆把对得上的子弹搬进载具弹药容器：+" + moved + " 发");
        } catch (Throwable ignored) {
        }
    }

    /** 装弹日志的独立频限表（与其它几条日志互不顶掉）。 */
    private static final java.util.Map<java.util.UUID, Long> AFEED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /* ==================== 攻击：让载具/龙去打她的目标 ==================== */

    /**
     * 每 tick（与驱动同频）问一次："她要打谁，让坐骑去打。"
     *
     * <ul>
     *   <li><b>卓越前线载具</b>：反编译实证，{@code VehicleEntity.baseTick} 里已经内置了
     *       "**Mob 乘客** + {@code canShoot(它)} + 它自己有 {@code getTarget()} → 自动瞄准并
     *       {@code vehicleShoot}"那条链路（每 tick 对齐炮口、按 RPM 开火）。所以女仆只要
     *       **坐进武器位、自己的 brain 里有目标**，载具就会替她打——本类只需把她的目标
     *       传给它（{@code setTarget} 是 Mob 的公开方法，走 Entity 类型即可，无需反射）。</li>
     *   <li><b>冰火传说的龙</b>：<b>【实测七百二十三】不再由本类触发吐息</b>。降级方案里女仆
     *       不是乘客（只是挂在鞍位上），龙有自己的 AI 目标与 {@code riderShootFire} 的
     *       "控制者"语义——她不再是控制者，硬喷只会在她旁边凭空吐火。交还原版。</li>
     * </ul>
     */
    public static void tickAttack(Entity mount, EntityMaid maid) {
        try {
            if (!modMountFireEnabled() || maid == null) {
                return;
            }
            Kind k = kindOf(mount);
            // 【实测七百二十七·点5】"什么时候她才会让载具开火"——玩家的口径是
            // 「只要女仆进入了 I attack 状态下，那么就会让他的载具一起攻击了」。
            // 所以这里把判据收敛成**一条**：她此刻有没有活的攻击目标（= 她在攻击状态）。
            // 有 → 把目标同时写到①她自己的 getTarget（SWB 内置的"Mob 乘客自动开火"读的就是它，
            // 见反编译 VehicleEntity:4014）与②车上的炮塔/武器位 AI 目标 UUID（坦克那种走它）；
            // **没有 → 两处都显式清空**（旧版只在有目标时才写，于是她脱战后车还在对着
            // 上一个目标开火——"到底什么时候才会让载具攻击"就不确定了）。
            LivingEntity target = targetOf(maid);
            if (k == Kind.VEHICLE) {
                try {
                    maid.setTarget(target); // 有则写、无则清（null 也要写，否则她脱战了车还开火）
                } catch (Throwable ignored) {
                }
                // 【实测七百二十四】再把目标写进炮塔/武器位的 **AI 目标 UUID**——车自己那套
                // 弹道求解+开火读的是它（座位制武器如直升机机炮则读上面那个 getTarget）。
                applyVehicleAiTargets(mount, target);
                logVehicleFire(mount, target);
                return;
            }
            // 【实测七百二十三】DRAGON：本类不再触发（她不是乘客/控制者）——见上面那段。
            // 【实测七百一十九·点4 通用档：任何"用正常优先级判定"的可骑乘生物】
            // 玩家原话：「理论上如果乘坐的生物是用正常的优先级来进行判定的话，应该是无条件服从
            // 女仆的 target 的。攻击方面应该是可以采用坐骑自己的攻击方式的……尽量做一个通用兼容，
            // 实在兼容不了再专门做适配。」
            //
            // 所以这一档不做任何逐模组适配：只要它是 {@link net.minecraft.world.entity.Mob}，
            // 就把她的攻击目标**原样无条件**写到它的 target 上，它自己那套（原版）优先级里
            // 的攻击/目标 AI 会接着完成"瞄准、接近、开火、用坐骑自己的攻击方式"。null 也照写
            // （她没目标 = 坐骑也没目标），这样"服从"是双向且即时的。
            // 原版马/猪/骆驼这些没有攻击目标 AI → 写了也没有任何副作用。
            if (mount instanceof net.minecraft.world.entity.Mob mob) {
                try {
                    mob.setTarget(target);
                    logAttackGeneric(mount, target);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 女仆当前的攻击目标（brain 的 ATTACK_TARGET 优先，退回实体层 target）。 */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            java.util.Optional<LivingEntity> mem = maid.getBrain().getMemory(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
            if (mem != null && mem.isPresent()) {
                LivingEntity le = mem.get();
                if (le != null && le.isAlive()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.getTarget();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 日志/诊断 ==================== */

    /** 给日志与拒绝理由用的短描述（"卓越前线载具(AIRCRAFT)" / "冰火传说龙(阶段4)"）。 */
    public static String describeKind(Entity e) {
        try {
            Kind k = kindOf(e);
            if (k == Kind.VEHICLE) {
                String eng = engineType(e);
                return "卓越前线载具" + (eng.isEmpty() ? "" : "(" + eng + ")");
            }
            if (k == Kind.DRAGON) {
                int st = dragonStage(e);
                return "冰火传说龙" + (st > 0 ? "(阶段" + st + ")" : "");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 诊断用：两家反射链的状态（写进只读核对/日志）。 */
    public static String diag() {
        return "swb=" + swbAvailable() + " iaf=" + iafAvailable()
                + " enabled=" + modMountsEnabled() + " fire=" + modMountFireEnabled();
    }

    /** 日志节流：同一只坐骑 5 秒最多一行（与全工程惯例一致）。 */
    private static final java.util.Map<java.util.UUID, Long> LOG_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_MS = 5000L;

    /**
     * 一行诊断日志（节流 5 秒/只）：被接管的模组坐骑、类型、引擎/阶段、位掩码。
     * 日志搜「模组坐骑」就能确认兼容层有没有真的在驾驶。
     */
    static void logDrive(Entity mount, String detail) {
        try {
            long now = System.currentTimeMillis();
            Long last = LOG_AT.get(mount.getUUID());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            LOG_AT.put(mount.getUUID(), now);
            if (LOG_AT.size() > 512) {
                LOG_AT.clear(); // 兜底：表不会无限涨
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑", describeKind(mount) + " " + detail);
        } catch (Throwable ignored) {
        }
    }

    /** 通用攻击档的日志节流表（与 {@link #LOG_AT} 分开，免得两条日志互相顶掉对方的限频）。 */
    private static final java.util.Map<java.util.UUID, Long> ATK_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 载具炮塔目标写入的日志节流表（第三条独立频限，避免互相顶掉）。 */
    private static final java.util.Map<java.util.UUID, Long> VFIRE_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百二十四】载具炮塔/武器位目标写入的一行留痕（节流 5 秒/只）。
     * 日志搜「模组坐骑·炮位」就能确认坦克的炮塔有没有拿到目标（= 会不会用自己的炮弹开火）。
     */
    private static void logVehicleFire(Entity mount, LivingEntity target) {
        try {
            long now = System.currentTimeMillis();
            Long last = VFIRE_AT.get(mount.getUUID());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            VFIRE_AT.put(mount.getUUID(), now);
            if (VFIRE_AT.size() > 512) {
                VFIRE_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·炮位", describeKind(mount)
                    + " 炮塔目标=" + (target == null ? "无"
                            : String.valueOf(target.getType()).replace("entity.minecraft.", "")));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 通用档写目标时的一行留痕（节流 5 秒/只）。
     * 日志搜「模组坐骑·目标」就能确认通用兜底有没有真的把她的 target 传下去。
     */
    private static void logAttackGeneric(Entity mount, LivingEntity target) {
        try {
            long now = System.currentTimeMillis();
            Long last = ATK_AT.get(mount.getUUID());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            ATK_AT.put(mount.getUUID(), now);
            if (ATK_AT.size() > 512) {
                ATK_AT.clear(); // 兜底：表不会无限涨
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·目标",
                    String.valueOf(mount.getType()).replace("entity.minecraft.", "") + " 目标="
                            + (target == null ? "无"
                                    : String.valueOf(target.getType()).replace("entity.minecraft.", "")));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 小工具 ==================== */

    /** 角度归一到 [-180,180)。 */
    private static float wrapDegrees(float deg) {
        deg = deg % 360.0f;
        if (deg >= 180.0f) {
            deg -= 360.0f;
        }
        if (deg < -180.0f) {
            deg += 360.0f;
        }
        return deg;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 保留：给未来驱动用的座位查询（当前未接线，留作诊断）。 */
    static int seatIndexOf(Entity mount, Entity passenger) {
        try {
            if (mGetSeatIndex != null && isVehicle(mount)) {
                Object v = mGetSeatIndex.invoke(mount, passenger);
                if (v instanceof Integer i) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 绑定前的一道保险：卓越前线的引擎只认<b>座位 0</b>（{@code getFirstPassenger()}）——
     * 女仆若落在别的座位（比如座舱里已被玩家占了驾驶位），她能开火但**开不动**。
     * 所以绑定时若她被排到座位 0 以外，就把她挪到座位 0（用模组自己的 {@code getSeatIndex}
     * 判断；挪动走原版 {@code startRiding} 的自然规则——先下再上，落点必是空出来的座位 0）。
     *
     * @return true = 她已经在座位 0（或不需要处理）
     */
    public static boolean ensureDriverSeat(Entity mount, Entity maid) {
        try {
            if (!isVehicle(mount) || mGetSeatIndex == null) {
                return true;
            }
            Object v = mGetSeatIndex.invoke(mount, maid);
            int idx = v instanceof Integer i ? i : -1;
            if (idx <= 0) {
                return true; // 座位 0 或问不出来 → 不折腾
            }
            // 她被排在别的座位 → 先下再上，原版会把她放进空出来的座位 0
            try {
                if (maid.getVehicle() == mount) {
                    maid.stopRiding();
                }
            } catch (Throwable ignored) {
            }
            return maid.startRiding(mount, true);
        } catch (Throwable ignored) {
            return true; // 探测失败不拦路（她照常坐上去，只是可能开不动）
        }
    }

    /** 未使用，避免 import 警告。 */
    @SuppressWarnings("unused")
    private static List<Entity> unused() {
        return null;
    }
}
