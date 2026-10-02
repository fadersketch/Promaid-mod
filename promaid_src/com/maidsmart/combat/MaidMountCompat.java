package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
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
 * 反编译三个 jar 对照（{@code iceandfire-2.1.13}、{@code IceAndFireCE-1.2.7}、
 * {@code iceandfire-2.1-beta.1}）：<b>结构相同、只有包名与个别内部名不同</b>——
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
    /* 【实测七百三十七·双人座】把**主人**放进副驾驶要用 SWB 自己的逐座 API（反编译实证：
     * changeSeat(Entity,int) 公开；要求"目标座为空 + 该实体已是乘客"→ 先 startRiding 再 changeSeat）。 */
    private static Method mChangeSeat;        // changeSeat(Entity,int)
    /* 【实测七百二十五】直升机的转向/俯仰走的是**鼠标通道**（javap/反编译实证：
     * helicopterEngine 的 yaw/pitch/roll 三项都读 getMouseMoveSpeedX/Y，而不是左右位）。
     * 所以驱动层必须能写这两个量 + 悬停开关。 */
    private static Method mGetMouseSpeedX;    // getMouseMoveSpeedX()
    private static Method mGetMouseSpeedY;    // getMouseMoveSpeedY()
    private static Method mSetMouseSpeedX;    // setMouseMoveSpeedX(float)
    private static Method mSetMouseSpeedY;    // setMouseMoveSpeedY(float)
    private static Method mSetHoverMode;      // setHoverMode(boolean)
    private static Method mGetHoverMode;      // getHoverMode()
    /* 【实测七百四十三】补三组：
     *   · setLoiterParams/setLoiterActive——固定翼（AC-130H）**唯一**的"停住并绕圈"通路。
     *     反编译 VehicleEntity.baseTick 实证：只有 EngineType.AIRCRAFT && getLoiterActive()
     *     才会调 VehicleEngineUtils.aircraftLoiter，而那个方法自己写 mouseX/mouseY/power 绕一个
     *     圆心+半径的圈——正是玩家要的"会盘旋、能停下"。
     *   · setLiftSpeed——飞艇（极恶乐魂）的竖直轴。反编译 airShipEngine 实证：有乘客时升力
     *     只来自上下位与 liftSpeed，且每拍 *0.8 衰减，所以想稳高度必须直接写它（PD）。
     *   · getPower——固定翼"点火"要看当前推力（loiter 的前置条件是 engineStartOver）。 */
    private static Method mSetLoiterParams;   // setLoiterParams(org.joml.Quaternionf)
    private static Method mSetLoiterActive;   // setLoiterActive(boolean)
    private static Method mSetLiftSpeed;      // setLiftSpeed(float)
    private static Method mGetPower;          // getPower()
    /* 【实测七百八十】固定翼"强制滚转"的两个口（见 aircraftBank 的说明）：
     * {@code getRoll()} 读当前滚转角，{@code setZRot(float)} 写它——反编译实证这两个方法在
     * VehicleEntity 上都有，且 {@code setZRot(rot)} 的实体就是 {@code setRoll(rot)}（1.20.1 / 1.21.1
     * 两个版本的 SWB 都一样）。滚转角直接进引擎的舵量上限
     * {@code rotSpeed = 0.3 + 3.2*|roll|/90}，所以"转弯半径"这件事只有它能定。 */
    private static Method mGetRoll;           // getRoll() -> float
    private static Method mSetRoll;           // setZRot(float)（= setRoll 的别名）
    /* 【实测七百六十】电量闸：反编译 VehicleEngineUtils.helicopterEngine:1005-1058 实证——
     * 载具引擎**自己**有一条"没电就熄火"的闸（{@code energy <= energyCostRate*|power|}
     * → setPower(power*0.995) + setForwardInputDown(false) + setEngineStart(false)
     * + setEngineStartOver(false) + goto 返回）。但我们的飞行驱动每拍**直接写 power**
     * （直升机高度 PD 的 setPower(0.045~0.12)、固定翼点火/loiter），下一拍就把它救活——
     * 这条闸被绕过了，于是"没电也能一直飞"。这两个反射用来把同一条闸在**写入之前**问一遍。 */
    private static Method mGetEnergy;         // getEnergy() -> int
    private static Method mGetMaxEnergy;      // getMaxEnergy() -> int
    /* 【实测七百七十六·点2】固定翼"补能"的 setter（javap 实证 VehicleEntity 上有 setEnergy(int)）。
     * SWB 固定翼的 loiter 闸里有一条 {@code getEnergy() > 1024}（baseTick 实证），而车的能量只能
     * 靠往车里塞能量物品充（baseTick:4097 每 20 拍从车里物品抽）。女仆不会充电——她要飞而电量见底时
     * 我们直接把能量仓填满（见 {@link #topUpAircraftEnergy}；只补这一档、只在见底时补）。 */
    private static Method mSetEnergy;         // setEnergy(int)
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

    /* 【实测七百三十一·暴力后门】玩家原话「女仆也不会使用直升机上的炮弹」「完全就停不下来了」
     * 「多走一些后门，来强制稳定他的位移」。以下四组反射**不依赖引擎自己的判定**，直接写状态：
     *   ① 位移：直接改 {@code deltaMovement}（水平清零/限幅）——引擎的 setDeltaMovement 只在
     *      **加速**时限幅，减速直通，所以写小值是安全的（反编译 VehicleEntity:5474-5487）。
     *   ② 炮弹：直接把炮塔方向**写死到目标**（{@code setTurretYRot} / {@code setTurretXRot}，
     *      反编译 VehicleEntity:3994 的 AI 瞄准最终就落在它俩上）+ 必要时给弹匣上弹
     *      （{@code GunData.reloadAmmo}）。
     *   ③ 开火：直接调 {@code vehicleShoot(LivingEntity, UUID, Vec3)}——绕开引擎那道
     *      "炮口必须在目标 4° 以内才开火"的闸（反编译 VehicleEntity:4013-4024）。 */
    private static Method mGetTurretYRot;      // getTurretYRot() -> float
    private static Method mSetTurretYRot;      // setTurretYRot(float)
    private static Method mGetTurretXRot;      // getTurretXRot() -> float
    private static Method mSetTurretXRot;      // setTurretXRot(float)
    private static Method mSetTurretYRotLock;  // setTurretYRotLock(float)
    private static Method mSetGunYRot;         // setGunYRot(float)  —— 武器位（直升机机炮多在驾驶位）
    private static Method mSetGunXRot;         // setGunXRot(float)
    private static Method mSetServerYaw;       // setServerYaw(float) —— 直写机头时要一起写（否则被 lerp 拽回）
    private static Method mVehicleShootAt;     // vehicleShoot(LivingEntity, UUID, Vec3)
    private static Method mGetGunDataSeat;     // getGunData(int seatIndex) -> GunData
    private static Method mGunReloadAmmo;      // GunData.reloadAmmo(Entity, boolean)
    private static Method mGunReloading;       // GunData.reloading() -> boolean
    private static Method mGunCurrentAmmo;     // GunData.currentAvailableAmmo(Entity) -> int
    private static Method mGunCanShoot;        // GunData.canShoot(Entity) -> boolean
    /* 【实测七百四十五·点4】"当前模式没弹 → 换一个有弹的模式"。玩家原话（原话即规格）：
     * 「女仆在操纵载具进行攻击的时候，它只会检查当前模式下是否存在对应的弹药……应该在没有发现
     * 链路之后，再检查一下其他模式有没有对应的弹药，然后考虑切换到那个模式。」
     *
     * 反编译实证（SWB 的两级"模式"）：
     *   ① 逐座武器表：一个座可有**多门炮**，getSelectedWeapon() 记录每座选中哪门；
     *      getWeaponIndex(int) 读、setWeaponIndex(int,int) 写、getGunName(int,int) 取第 int 门。
     *      ——这正是玩家说的"模式 A 发 A 弹、模式 B 发 B 弹"。
     *   ② 同一门炮内的弹药类型：GunData.selectedAmmoType 指向 GunProp.AMMO_CONSUMER 表里第几项；
     *      changeAmmoConsumer(int,Entity) 切它。有些车的"模式"是这一级。
     * 我们两级都试：先在同一门炮里换弹种，不行再换到另一门炮。 */
    private static Method mGetWeaponIndex;         // getWeaponIndex(int seat) -> int
    private static Method mSetWeaponIndex;         // setWeaponIndex(int seat, int weaponIndex)
    private static Method mGetGunNameAtSeatWeapon; // getGunName(int seat, int weaponIndex) -> String
    private static java.lang.reflect.Field fGunPropAmmoConsumer; // GunProp.AMMO_CONSUMER
    private static Method mGunGetProp;             // GunData.get(GunProp) -> T（通用取值）
    private static Method mChangeAmmoConsumer;     // GunData.changeAmmoConsumer(int index, Entity supplier)
    private static Method mConsumerCount;          // AmmoConsumer.count(GunData, Entity) -> int
    /* 【实测七百七十七·点1】引擎自己的"无限弹"判据（见 vehicleHasInfiniteAmmo 的说明）：
     * InventoryTool.hasCreativeAmmoBox(Entity) -> boolean（反编译 InventoryTool:296）。 */
    private static Method mHasCreativeAmmoBoxEntity;
    /* 【实测七百七十七·点1】"直接把弹匣写满"所需的反射（见 forceMagazineFull 的说明）：
     * GunData.ammo（public IntValue）+ IntValue.get()/set(int) + GunProp.MAGAZINE。 */
    private static java.lang.reflect.Field fGunAmmo;
    private static Method mIntValueGet;
    private static Method mIntValueSet;
    private static java.lang.reflect.Field fGunPropMagazine;

    /* 【实测七百七十九·点1】"这门炮自己设计的射速"所需的反射：
     * GunProp.EMPTY_RELOAD_TIME（打完一匣要多少拍）。配合上面那个 MAGAZINE 一起算
     * "一匣打完 + 装满一匣"的**固有循环**——见 fireIntervalTicks 的说明。 */
    private static java.lang.reflect.Field fGunPropEmptyReload;

    /* 【实测七百七十八·点1】"检测到创造盒就强制允许开炮"这条后门所需的反射。
     *
     * 根因（反编译实证）：载具武器的 {@code canShoot} 走的是 **VehicleGunItem 的覆写**，
     * 它最后一关不是 {@code hasEnoughAmmoToShoot}，而是
     * {@code VehicleEntity.getAmmo(data) = useBackpackAmmo ? backupAmmoCount.get() : ammo.get()}
     * （VehicleEntity:5707）——无弹匣武器（机枪）读的是 **backupAmmoCount 这个"显示值"**，
     * 有弹匣的（主炮）读 ammo。另外它前面还有五道闸：{@code PROJECTILE_AMOUNT>0}、
     * {@code !overHeat}、{@code heat<=100}、{@code !reloading()}、{@code !charging()}、
     * {@code !bolt.needed}——**任意一道不过就是"一点动静都没有"**。
     * 所以这条后门要把"弹"和"状态"一起按到可开火：
     *   ① ammo := MAGAZINE（与 SWB 对创造玩家同一手，GunData:631）
     *   ② backupAmmoCount := 大值（无弹匣武器 getAmmo 读它）
     *   ③ GunData.resetStatus()（清装填/拉栓/蓄力，GunData:641）
     *   ④ overHeat := false、heat := 0.0（机枪连射过热那道闸）
     */
    private static java.lang.reflect.Field fGunBackupAmmoCount; // GunData.backupAmmoCount (IntValue)
    private static Method mGunResetStatus;                       // GunData.resetStatus()
    private static java.lang.reflect.Field fGunOverHeat;         // GunData.overHeat (BooleanValue)
    private static java.lang.reflect.Field fGunHeat;             // GunData.heat (DoubleValue)
    private static Method mBoolValueGet;                         // BooleanValue.get()
    private static Method mBoolValueSet;                         // BooleanValue.set(boolean)
    private static Method mDoubleValueGet;                       // DoubleValue.get()
    private static Method mDoubleValueSet;                       // DoubleValue.set(double)
    private static java.lang.reflect.Field fGunBoltSub;          // GunData.bolt (subdata)
    private static java.lang.reflect.Field fBoltNeeded;          // Bolt.needed (BooleanValue)
    private static Method mGunCharging;                          // GunData.charging() -> boolean
    private static Method mVehicleGetAmmo;                       // VehicleEntity.getAmmo(GunData) -> int
    /* 后门写入 backupAmmoCount 的目标值：与 countBackupAmmo 的"无限"同一个量级（MAX_VALUE 会在
     * 界面上显示成天文数字，这里取一个"够大又不会溢出/显示异常"的值）。 */
    private static final int FORCE_BACKUP_AMMO = 9999;

    /* 【实测七百四十】弹道瞄准：把 731 那套"自己算角度"换成**引擎自己的弹道解算器**。
     * 反编译实证（VehicleWeaponUtils.turretAutoAimFromUuid / RangeTool.calculateFiringSolution）：
     * 炮塔角是**相对车身**的、且要解重力下坠与目标提前量，自己画直线必然打偏。 */
    private static Method mTurretAutoAimFromVector;          // turretAutoAimFromVector(Vec3)
    private static Method mPassengerWeaponAutoAimFromVector; // passengerWeaponAutoAimFormVector(Vec3)
    private static Method mGetShootVecEntity;   // getShootVec(Entity, float) —— 炮管当前朝向
    private static Method mGetShootPosEntity;   // getShootPos(Entity, float) —— 炮口位置
    private static Method mGetProjectileVelocity; // getProjectileVelocity(Entity) -> float
    private static Method mGetProjectileGravity;  // getProjectileGravity(Entity) -> float
    private static Method mRangeFiringSolution;   // RangeTool.calculateFiringSolution(Vec3,Vec3,Vec3,double,double)

    /* 【实测七百四十一·点2/点3】按**炮名**取的四个重载（与上面按 Entity 那组一一对应）。
     *
     * 为什么必须补这一组（反编译实证，是本批"直升机打不出炮弹 / 炮艇不装弹"的根因之一）：
     * 上面那组 {@code getShootPos(Entity,float)} / {@code getShootVec(Entity,float)} 内部走的是
     * {@code getGunData(getSeatIndex(entity))}——**按"她坐哪个座"解析武器**。而卓越前线的多座载具
     * 里，主炮常常**不在她坐的那一座**：
     *   · Mi-28：炮塔控制器 = 座 1（30mm 机炮），驾驶座 0 的武器是火箭弹 → 瞄准算的是机炮、
     *     扣扳机打的却是座 0 的火箭弹（"炮弹准度约等于 0"）。
     *   · AC-130H：驾驶座 0 **一个武器都没有**，三门炮在座 1/2/3。
     * 所以"瞄哪门炮"与"打哪门炮"必须用**同一个炮名**统一解析——这一组就是那个入口。 */
    private static Method mGetShootPosName;         // getShootPos(String, float)
    private static Method mGetShootVecName;         // getShootVec(String, float)
    private static Method mGetProjectileVelocityName; // getProjectileVelocity(String) -> float
    private static Method mGetProjectileGravityName;  // getProjectileGravity(String) -> float
    private static Method mVehicleShootByName;      // vehicleShoot(LivingEntity, String, UUID, Vec3)
    private static Method mGetGunNameAtSeat;        // getGunName(int) -> String（该座**当前选中**那门炮名）
    private static Method mHasWeaponSeat;           // hasWeapon(int) -> boolean（该座有没有武器）
    private static Method mGetGunDataName;          // getGunData(String) -> GunData（按炮名取枪）

    /* 【实测七百七十三·点3】"直瞄开火"（= 女仆枪械模式式瞄准）所需的反射：
     * 不再让炮弹等"炮塔/机头转到位"，而是**开火那一刻**把解算方向直接交给枪自己的 shoot——
     * 反编译 {@code GunItem.shootBullet} 实证：弹体逐字按传入的 {@code shootDirection} 生成，
     * 中间没有任何机械环节。玩家原话：「子弹好像从车底打出来的……都集中在脚下……能不能改成
     * 跟女仆枪械模式一样的瞄准机制呢？」
     *   · {@code ShootParameters(ammoSupplier, shooter, level, shootPos, shootDir, data, spread, zoom, uuid, targetPos)}
     *   · {@code GunData.shoot(ShootParameters)}——弹药/冷却/音效/过热/后坐全由枪自己那条链处理。
     * 各自单独 try：缺一个就整条退回引擎原路（{@code vehicleShoot}），绝不让它拖垮开火。 */
    private static Constructor<?> ctorShootParams;   // ShootParameters(...) 10 参构造
    private static Method mGunDataShootParams;       // GunData.shoot(ShootParameters)
    private static java.lang.reflect.Field fGunPropSpread; // GunProp.SPREAD（这门炮自己的散布）
    private static Constructor<?> ctorVehicleShootMsg; // VehicleShootClientMessage(UUID,UUID,int,String)
    private static Method mSendPacketToAll;          // MinecraftUtil.sendPacketToAll(payload)

    /* 【实测七百七十四·点1】"她只认现成的弹药、不认弹药盒"所需的反射（见 {@link #stackIsWantedAmmo}）。
     *
     * 玩家原话：「我发现现在女仆给载具装填没有办法识别弹药盒，她只认现成的弹药。」
     * 反编译 {@code InventoryTool.countAmmoItem/consumeAmmoItem} 实证：车的枪在容器里认**三种**形态——
     *   ① 散装弹（{@code AmmoSupplierItem}，如 {@code rifle_ammo}）→ 旧判据 isAmmoItem 已覆盖；
     *   ② 盒装弹（{@code RifleAmmoBoxItem} 等，**继承 AmmoSupplierItem**、{@code type} 相同、
     *      每件 {@code ammoToAdd=30/12} 发）→ 物品 id 与散装弹**不同**，旧判据匹配不到；
     *   ③ 通用「弹药盒」（{@code AmmoBoxItem}，弹药存在物品自身的数据组件里，
     *      用 {@code Ammo.get(stack)} 读、{@code Ammo.set(stack,n)} 写）→ 旧判据也匹配不到。
     * 所以判据要按 SWB 自己的"这盒弹是不是这门枪吃的型号"来问：
     *   · {@code AmmoConsumer.getPlayerAmmoType()}（枚举型武器才有，物品型恒 null）；
     *   · {@code AmmoSupplierItem.getType()}（盒装/散装弹自带型号）；
     *   · {@code Ammo.get(ItemStack)}（弹药盒里存了多少发）。
     * 三者缺一就退回旧判据（至少散装弹照旧能搬）。 */
    private static Class<?> cAmmoBoxItem;            // ...item.ammo.AmmoBoxItem（通用「弹药盒」）
    private static Class<?> cAmmoSupplier;           // ...item.ammo.AmmoSupplierItem（散装弹/盒装弹）
    private static Method mConsumerPlayerAmmoType;   // AmmoConsumer.getPlayerAmmoType() -> Ammo
    private static Method mSupplierGetType;          // AmmoSupplierItem.getType() -> Ammo
    private static Method mAmmoGetStack;             // Ammo.get(ItemStack) -> int

    /* 【实测七百七十四·点2/点3】"全车炮管一起开火 + 按各自 RPM 的射速"所需的反射：
     * {@code vehicleWeaponRpm(String)}——引擎自己算射速用的就是它（RPM / 60 = 每秒发数）。 */
    private static Method mVehicleWeaponRpmName;     // vehicleWeaponRpm(String) -> int

    /* 【实测七百七十五·点1/点2】多弹种装填 + 投弹安全高度=炸弹威力半径。
     *   · {@code GunProp.AMMO_CONSUMER}（745 已取到）——本批用它把每门炮的**全部弹种**都收进
     *     识弹判据：旧版只看"当前选中那一种"，于是 M1A2 主炮选着 AP、她带着 HE 时，那摞 HE
     *     永远搬不进车（实机日志：主炮打两发就再无 Cannon）。
     *   · {@code GunProp.EXPLOSION_RADIUS}——投弹安全高度按它算（玩家原话：「应该把上升高度
     *     调整为那个炸弹所能波及的半径的大小。保证全身而退。」）。 */
    private static java.lang.reflect.Field fGunPropExplosionRadius; // GunProp.EXPLOSION_RADIUS

    /* ==================== 反射缓存：冰火传说 ==================== */

    private static boolean iafInited;
    private static boolean iafOk;
    private static Class<?> cDragon;
    private static Method mGetDragonStage;    // getDragonStage()
    /**
     * 【实测七百二十】{@code getRiderPosition()}——龙给**玩家**算的那个背上鞍位（公开方法）。
     * 【实测七百二十三】降级方案里它成了女仆的"悬空椅子"坐标。
     */
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
            // 【实测七百三十七·双人座】逐座移动：单独 try——缺了它只是"主人坐不进副驾"，
            // 不该把上面那组炮塔/武器位反射一起关掉。
            try {
                mChangeSeat = cVehicle.getMethod("changeSeat", Entity.class, int.class);
            } catch (Throwable ignored) {
                mChangeSeat = null;
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
            // 【实测七百四十三】固定翼盘旋（loiter）/ 飞艇竖直轴（liftSpeed）/ 固定翼点火（power）。
            // 各自单独 try——某一版本缺一个只影响对应那一档，不该拖垮驾驶/开火/装弹。
            try {
                Class<?> quat = Class.forName("org.joml.Quaternionf");
                mSetLoiterParams = cVehicle.getMethod("setLoiterParams", quat);
                mSetLoiterActive = cVehicle.getMethod("setLoiterActive", boolean.class);
            } catch (Throwable ignored) {
                mSetLoiterParams = null;
                mSetLoiterActive = null;
            }
            try {
                mSetLiftSpeed = cVehicle.getMethod("setLiftSpeed", float.class);
            } catch (Throwable ignored) {
                mSetLiftSpeed = null;
            }
            try {
                mGetPower = cVehicle.getMethod("getPower");
            } catch (Throwable ignored) {
                mGetPower = null;
            }
            // 【实测七百八十】固定翼强制滚转：单独 try——拿不到就退回"借左右位"那套慢滚转
            // （只是转弯半径大些），绝不能连累上面任何一档。
            try {
                mGetRoll = cVehicle.getMethod("getRoll");
                mSetRoll = cVehicle.getMethod("setZRot", float.class);
            } catch (Throwable ignored) {
                mGetRoll = null;
                mSetRoll = null;
            }
            // 【实测七百六十】电量闸的两个读数（javap 实证 VehicleEntity 上都有；
            // 各自单独 try——拿不到 = 不启用这道闸，绝不让它影响驾驶本身）。
            try {
                mGetEnergy = cVehicle.getMethod("getEnergy");
                mGetMaxEnergy = cVehicle.getMethod("getMaxEnergy");
            } catch (Throwable ignored) {
                mGetEnergy = null;
                mGetMaxEnergy = null;
            }
            // 【实测七百七十六·点2】固定翼补能的写口（缺了只是"不补能"，不影响其余）。
            try {
                mSetEnergy = cVehicle.getMethod("setEnergy", int.class);
            } catch (Throwable ignored) {
                mSetEnergy = null;
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
            // 【实测七百三十一·暴力后门】炮塔朝向直写 + 直接开火 + 弹匣补弹。各自 try。
            try {
                mGetTurretYRot = cVehicle.getMethod("getTurretYRot");
                mSetTurretYRot = cVehicle.getMethod("setTurretYRot", float.class);
                mGetTurretXRot = cVehicle.getMethod("getTurretXRot");
                mSetTurretXRot = cVehicle.getMethod("setTurretXRot", float.class);
                mSetTurretYRotLock = cVehicle.getMethod("setTurretYRotLock", float.class);
                // 武器位（直升机机炮常在驾驶位，走 gunYRot/gunXRot，与炮塔那对分开）。
                mSetGunYRot = cVehicle.getMethod("setGunYRot", float.class);
                mSetGunXRot = cVehicle.getMethod("setGunXRot", float.class);
                // 【实测七百三十三】直写机头要连 serverYaw 一起写（handleClientSync 会用
                // serverYaw 把 yRot 往回 lerp）。单独 try——缺了它只是"直写会被慢慢拽回"。
                try {
                    mSetServerYaw = cVehicle.getMethod("setServerYaw", float.class);
                } catch (Throwable ignored) {
                    mSetServerYaw = null;
                }
            } catch (Throwable ignored) {
                mGetTurretYRot = null;
                mSetTurretYRot = null;
                mGetTurretXRot = null;
                mSetTurretXRot = null;
                mSetTurretYRotLock = null;
                mSetGunYRot = null;
                mSetGunXRot = null;
            }
            try {
                mVehicleShootAt = cVehicle.getMethod("vehicleShoot", LivingEntity.class,
                        java.util.UUID.class, Vec3.class);
            } catch (Throwable ignored) {
                mVehicleShootAt = null;
            }
            try {
                mGetGunDataSeat = cVehicle.getMethod("getGunData", int.class);
                mAmmoSupplier = cVehicle.getMethod("getAmmoSupplier");
            } catch (Throwable ignored) {
                mGetGunDataSeat = null;
                mAmmoSupplier = null;
            }
            try {
                Class<?> gdCls2 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                mGunReloadAmmo = gdCls2.getMethod("reloadAmmo", Entity.class, boolean.class);
                mGunReloading = gdCls2.getMethod("reloading");
                mGunCurrentAmmo = gdCls2.getMethod("currentAvailableAmmo", Entity.class);
                mGunCanShoot = gdCls2.getMethod("canShoot", Entity.class);
            } catch (Throwable ignored) {
                mGunReloadAmmo = null;
                mGunReloading = null;
                mGunCurrentAmmo = null;
                mGunCanShoot = null;
            }
            // 【实测七百四十五·点4】"这个模式没弹就换一个有弹的模式"所需的反射：逐座武器表 + 切换 +
            // 同一门炮内换弹药类型。各自单独 try（缺了只是"换模式"这一档不生效，不影响"有弹就打"）。
            try {
                mGetWeaponIndex = cVehicle.getMethod("getWeaponIndex", int.class);
                mSetWeaponIndex = cVehicle.getMethod("setWeaponIndex", int.class, int.class);
                mGetGunNameAtSeatWeapon = cVehicle.getMethod("getGunName", int.class, int.class);
            } catch (Throwable ignored) {
                mGetWeaponIndex = null;
                mSetWeaponIndex = null;
                mGetGunNameAtSeatWeapon = null;
            }
            try {
                Class<?> gdCls3 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                Class<?> gpCls = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp");
                Class<?> acCls3 = Class.forName("com.atsuishio.superbwarfare.data.gun.AmmoConsumer");
                fGunPropAmmoConsumer = gpCls.getField("AMMO_CONSUMER");
                mGunGetProp = gdCls3.getMethod("get", gpCls);
                mChangeAmmoConsumer = gdCls3.getMethod("changeAmmoConsumer", int.class, Entity.class);
                mConsumerCount = acCls3.getMethod("count", gdCls3, Entity.class);
            } catch (Throwable ignored) {
                fGunPropAmmoConsumer = null;
                mGunGetProp = null;
                mChangeAmmoConsumer = null;
                mConsumerCount = null;
            }
            // 【实测七百四十】弹道瞄准所需的反射：自动瞄准入口 + 炮口/炮向 + 弹道参数 + 解算器。
            // 各自单独 try（缺某一个只是"瞄准这一档不生效"，不该拖垮驾驶/开火/装弹）。
            try {
                mTurretAutoAimFromVector = cVehicle.getMethod("turretAutoAimFromVector", Vec3.class);
                mPassengerWeaponAutoAimFromVector =
                        cVehicle.getMethod("passengerWeaponAutoAimFormVector", Vec3.class);
                mGetShootVecEntity = cVehicle.getMethod("getShootVec", Entity.class, float.class);
                mGetShootPosEntity = cVehicle.getMethod("getShootPos", Entity.class, float.class);
                mGetProjectileVelocity = cVehicle.getMethod("getProjectileVelocity", Entity.class);
                mGetProjectileGravity = cVehicle.getMethod("getProjectileGravity", Entity.class);
            } catch (Throwable ignored) {
                mTurretAutoAimFromVector = null;
                mPassengerWeaponAutoAimFromVector = null;
                mGetShootVecEntity = null;
                mGetShootPosEntity = null;
                mGetProjectileVelocity = null;
                mGetProjectileGravity = null;
            }
            try {
                Class<?> rt = Class.forName("com.atsuishio.superbwarfare.tools.RangeTool");
                mRangeFiringSolution = rt.getMethod("calculateFiringSolution",
                        Vec3.class, Vec3.class, Vec3.class, double.class, double.class);
            } catch (Throwable ignored) {
                mRangeFiringSolution = null;
            }
            // 【实测七百四十一·点2/点3】按**炮名**取的那一组（与上面按 Entity 那组一一对应）。
            // 各自单独 try——缺了它只是"多座载具的炮打不准/炮艇不装弹"，不该拖垮驾驶/开火。
            try {
                mGetShootPosName = cVehicle.getMethod("getShootPos", String.class, float.class);
                mGetShootVecName = cVehicle.getMethod("getShootVec", String.class, float.class);
                mGetProjectileVelocityName = cVehicle.getMethod("getProjectileVelocity", String.class);
                mGetProjectileGravityName = cVehicle.getMethod("getProjectileGravity", String.class);
                mVehicleShootByName = cVehicle.getMethod("vehicleShoot", LivingEntity.class,
                        String.class, java.util.UUID.class, Vec3.class);
                mGetGunNameAtSeat = cVehicle.getMethod("getGunName", int.class);
                mHasWeaponSeat = cVehicle.getMethod("hasWeapon", int.class);
                mGetGunDataName = cVehicle.getMethod("getGunData", String.class);
            } catch (Throwable ignored) {
                mGetShootPosName = null;
                mGetShootVecName = null;
                mGetProjectileVelocityName = null;
                mGetProjectileGravityName = null;
                mVehicleShootByName = null;
                mGetGunNameAtSeat = null;
                mHasWeaponSeat = null;
                mGetGunDataName = null;
            }
            // 【实测七百七十三·点3】直瞄开火反射（见字段注释）。三段各自单独 try。
            try {
                Class<?> spCls = Class.forName("com.atsuishio.superbwarfare.data.gun.ShootParameters");
                Class<?> gdCls4 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                ctorShootParams = spCls.getConstructor(Entity.class, Entity.class,
                        net.minecraft.server.level.ServerLevel.class, Vec3.class, Vec3.class,
                        gdCls4, double.class, boolean.class, java.util.UUID.class, Vec3.class);
                mGunDataShootParams = gdCls4.getMethod("shoot", spCls);
            } catch (Throwable ignored) {
                ctorShootParams = null;
                mGunDataShootParams = null;
            }
            try {
                Class<?> gpCls2 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp");
                fGunPropSpread = gpCls2.getField("SPREAD");
            } catch (Throwable ignored) {
                fGunPropSpread = null;
            }
            // 枪口火光/音效的客户端同步包（引擎那条 vehicleShoot 里会发；我们直瞄绕过了它，
            // 这里自己补一发，免得"开炮没火光"）。**按名字+1 参找**：1.20.1 的形参是 Object、
            // 1.21.1 是 CustomPacketPayload，写死类型会在另一版上找不到。拿不到就不发——不影响命中。
            try {
                Class<?> msgCls = Class.forName(
                        "com.atsuishio.superbwarfare.network.message.receive.VehicleShootClientMessage");
                ctorVehicleShootMsg = msgCls.getConstructor(java.util.UUID.class, java.util.UUID.class,
                        int.class, String.class);
                Class<?> muCls = Class.forName("com.atsuishio.superbwarfare.tools.MinecraftUtil");
                for (Method m : muCls.getMethods()) {
                    if ("sendPacketToAll".equals(m.getName()) && m.getParameterCount() == 1) {
                        mSendPacketToAll = m;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                ctorVehicleShootMsg = null;
                mSendPacketToAll = null;
            }
            // 【实测七百七十四·点1/点2/点3】弹药盒识别 + 全车炮管枚举 + 逐炮 RPM 射速所需的反射。
            // 一整段 try：缺任何一个都只是"那一档退回旧行为"（散装弹照搬、只打主炮、固定 5 拍节奏）。
            try {
                Class<?> ac774 = Class.forName("com.atsuishio.superbwarfare.data.gun.AmmoConsumer");
                mConsumerPlayerAmmoType = ac774.getMethod("getPlayerAmmoType");
                Class<?> ammoCls774 = Class.forName("com.atsuishio.superbwarfare.data.gun.Ammo");
                mAmmoGetStack = ammoCls774.getMethod("get", net.minecraft.world.item.ItemStack.class);
                cAmmoBoxItem = Class.forName("com.atsuishio.superbwarfare.item.ammo.AmmoBoxItem");
                Class<?> supCls774 = Class.forName(
                        "com.atsuishio.superbwarfare.item.ammo.AmmoSupplierItem");
                mSupplierGetType = supCls774.getMethod("getType");
                cAmmoSupplier = supCls774;
            } catch (Throwable ignored) {
                cAmmoBoxItem = null;
                cAmmoSupplier = null;
                mConsumerPlayerAmmoType = null;
                mSupplierGetType = null;
                mAmmoGetStack = null;
            }
            try {
                mVehicleWeaponRpmName = cVehicle.getMethod("vehicleWeaponRpm", String.class);
            } catch (Throwable ignored) {
                mVehicleWeaponRpmName = null;
            }
            // 【实测七百七十五·点2】投弹安全高度 = 这门炸弹自己的爆炸半径（GunProp.EXPLOSION_RADIUS）。
            try {
                Class<?> gpCls775 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp");
                fGunPropExplosionRadius = gpCls775.getField("EXPLOSION_RADIUS");
            } catch (Throwable ignored) {
                fGunPropExplosionRadius = null;
            }
            // 【实测七百七十七·点1】引擎自己的"无限弹"判据（静态方法，见 vehicleHasInfiniteAmmo）。
            try {
                Class<?> itCls777 = Class.forName("com.atsuishio.superbwarfare.tools.InventoryTool");
                mHasCreativeAmmoBoxEntity = itCls777.getMethod("hasCreativeAmmoBox", Entity.class);
            } catch (Throwable ignored) {
                mHasCreativeAmmoBoxEntity = null;
            }
            // 【实测七百七十七·点1】"直接把弹匣写满"的反射（见 forceMagazineFull）。
            try {
                Class<?> gdCls777 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                Class<?> ivCls777 = Class.forName("com.atsuishio.superbwarfare.data.gun.value.IntValue");
                Class<?> gpCls777 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunProp");
                fGunAmmo = gdCls777.getField("ammo");
                mIntValueGet = ivCls777.getMethod("get");
                mIntValueSet = ivCls777.getMethod("set", int.class);
                fGunPropMagazine = gpCls777.getField("MAGAZINE");
                fGunPropEmptyReload = gpCls777.getField("EMPTY_RELOAD_TIME");
            } catch (Throwable ignored) {
                fGunAmmo = null;
                mIntValueGet = null;
                mIntValueSet = null;
                fGunPropMagazine = null;
                fGunPropEmptyReload = null;
            }
            // 【实测七百七十八·点1】"强制允许开炮"那条后门所需的反射（见 forceAllowShoot）。
            try {
                Class<?> gdCls778 = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
                Class<?> bvCls778 = Class.forName("com.atsuishio.superbwarfare.data.gun.value.BooleanValue");
                Class<?> dvCls778 = Class.forName("com.atsuishio.superbwarfare.data.gun.value.DoubleValue");
                Class<?> boltCls778 = Class.forName("com.atsuishio.superbwarfare.data.gun.subdata.Bolt");
                fGunBackupAmmoCount = gdCls778.getField("backupAmmoCount");
                mGunResetStatus = gdCls778.getMethod("resetStatus");
                mGunCharging = gdCls778.getMethod("charging");
                fGunOverHeat = gdCls778.getField("overHeat");
                fGunHeat = gdCls778.getField("heat");
                fGunBoltSub = gdCls778.getField("bolt");
                fBoltNeeded = boltCls778.getField("needed");
                mBoolValueGet = bvCls778.getMethod("get");
                mBoolValueSet = bvCls778.getMethod("set", boolean.class);
                mDoubleValueGet = dvCls778.getMethod("get");
                mDoubleValueSet = dvCls778.getMethod("set", double.class);
            } catch (Throwable ignored) {
                fGunBackupAmmoCount = null;
                mGunResetStatus = null;
                mGunCharging = null;
                fGunOverHeat = null;
                fGunHeat = null;
                fGunBoltSub = null;
                fBoltNeeded = null;
                mBoolValueGet = null;
                mBoolValueSet = null;
                mDoubleValueGet = null;
                mDoubleValueSet = null;
            }
            // VehicleEntity.getAmmo(GunData)（VehicleGunItem.canShoot 的最后一关；只用于诊断留痕）。
            try {
                mVehicleGetAmmo = cVehicle.getMethod("getAmmo",
                        Class.forName("com.atsuishio.superbwarfare.data.gun.GunData"));
            } catch (Throwable ignored) {
                mVehicleGetAmmo = null;
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

    /* ==================== v1.3.0(beta) 实测七百四十七：不可驾名单 ==================== */

    /**
     * v1.3.0(beta) 实测七百四十七【骑乘指挥棒·不可驾名单】。
     *
     * <h2>玩家原话</h2>
     * 「骑乘棒没有办法绑定 Ju-87 斯图卡轰炸机、A-10 雷电二攻击机、AC-130H 空中炮艇、
     *  汤姆 F6F、KV-16 幽灵战斗机、迷你快艇。在手册里面就写这些载具要么对于操作的要求太高，
     *  要么在代码上直接认准的人。让女仆来指会搞得一团糟，所以没有办法骑。」
     *
     * <h2>为什么这六台单独点名，而不是"凡是飞机都不许"</h2>
     * 它们分两类，两类都**不是"女仆开得不好"，而是"这车根本不接受非玩家驾驶"**：
     * <ol>
     *   <li><b>操作要求高</b>（Ju-87 / A-10 / AC-130H / KV-16）：全是固定翼。固定翼没有
     *       竖直输入轴，高度只由"速度 + 俯仰"积分出来（`aircraftEngine` 反编译实证），
     *       起飞要助跑、盘旋要维持速度——把它交给"去某个点"这一层意图，结果必然是
     *       要么一头栽地、要么一路飞走。玩家自己飞都得练，女仆没有"练"这件事。</li>
     *   <li><b>代码上直接认准的人</b>（汤姆 F6F / 迷你快艇）：引擎里油门与姿态整段写在
     *       {@code passenger instanceof Player} 那一支里（`tomEngine` 反编译实证），
     *       女仆是 Mob、压根进不去那条分支 —— 我们前面为了 TOM6 硬写过一套"直写推力+俯仰"
     *       的补丁，但它的**武器**（西瓜炸弹）与**座位**也都只按玩家语义设计；
     *       迷你快艇则连座位都只有一个、且 {@code Type=Boat} 走的是水面物理。
     *       与其继续打补丁，不如照玩家的话——**认人**，不给她开。</li>
     * </ol>
     * 名单判据 = **实体类型注册名**（{@code modid:entity}），与家具黑名单同一套写法：
     * 不写死类引用，1.20.1 / 1.21.1 两树共用（两树的 SWB 实体 id 逐字相同，jar 实证）。
     * 其余载具（坦克 / 装甲车 / 直升机 / 飞艇等）**一律不受影响**，照旧能骑能打。
     *
     * <p>【实测七百七十五·点3】原名单六台，其中**四架固定翼已解禁**（Ju-87 / A-10 / AC-130H /
     * KV-16）：玩家原话「我觉得那些原本不能绑定的那些坐骑可以尝试给他们解禁了。比如AC 130H，
     * 但它的飞行轨迹必须要专门定制。」它们的"门槛"不再是黑名单，而是
     * {@code driveFlight} 里那条**固定翼定制起飞链路**——先验平地（落差不能太大，不行就报
     * "环境不允许起飞"）、向平地加速、够速抬机头、之后交给引擎 loiter（以主人/敌人为圆心
     * 盘旋）并照常开火。剩下两台（汤姆 F6F / 迷你快艇）**代码上只认玩家**（引擎里油门与姿态
     * 整段写在 {@code passenger instanceof Player} 分支里，反编译实证），女仆连输入通道都进不去，
     * 继续拦。
     */
    private static final java.util.List<String> UNRIDABLE = java.util.List.of(
            MOD_SWB + ":tom_6",            // 汤姆 F6F（油门/姿态只写给玩家）
            MOD_SWB + ":tiny_speedboat");  // 迷你快艇（单座、只按玩家语义）

    /**
     * 不可驾名单里那几台的**中文名**（给玩家看的气泡用）。
     *
     * <p>为什么不从游戏里取本地化名：SWB 这几台的名字散在 {@code entity.superbwarfare.*} 与
     * {@code superbwarfare.entry.vehicle.*} 两套键里，还要先判当前语言——而这份名单是固定几台，
     * 名字就是玩家自己说出来的那几个，直接写死最稳，也不受 SWB 换翻译影响。
     *
     * <p>【实测七百七十五·点3】四架固定翼（Ju-87 / A-10 / AC-130H / KV-16）已从名单移除——
     * 玩家原话「我觉得那些原本不能绑定的那些坐骑可以尝试给他们解禁了。比如AC 130H，但它的
     * 飞行轨迹必须要专门定制。」它们的"操作门槛"由本类的**固定翼定制起飞链路**接管
     * （平地判据 → 向平地加速 → 抬机头 → 引擎 loiter 以主人/敌人为圆心盘旋 + 武器照打）；
     * 真正的"只认玩家"两台（汤姆 F6F / 迷你快艇，引擎里油门姿态整段写在
     * {@code passenger instanceof Player} 分支里）继续拦。
     */
    private static final java.util.Map<String, String> UNRIDABLE_NAME = java.util.Map.of(
            MOD_SWB + ":tom_6", "汤姆 F6F",
            MOD_SWB + ":tiny_speedboat", "迷你快艇");

    /**
     * 这只实体是不是在**不可驾名单**里（{@link #UNRIDABLE}）。
     *
     * <p>拿不到注册名（别的模组换了 id / 探测失败）→ {@code false}，即**照旧可驾**：
     * 这份名单是"已知要排除的少数"，不是白名单，探测失败不该把玩家正常的车也一起禁掉。
     */
    public static boolean isUnridable(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
            if (key == null) {
                return false;
            }
            return UNRIDABLE.contains(key.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 不可驾名单里那台的中文名（拿不到 → 注册名）。 */
    private static String unridableName(Entity e) {
        try {
            net.minecraft.resources.ResourceLocation key =
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
            if (key != null) {
                String n = UNRIDABLE_NAME.get(key.toString());
                if (n != null) {
                    return n;
                }
                return key.toString();
            }
        } catch (Throwable ignored) {
        }
        return "载具";
    }

    /**
     * 这只模组坐骑此刻能不能被女仆绑：载具要看是不是报废的（{@code isWreck}），
     * 龙要看阶段（{@code getDragonStage() &gt;= 2}——原版里 1 阶段的小龙是"被玩家抱着"，
     * 不能骑）。能驾 → {@code null}；否则给一句给玩家看的理由。
     *
     * <p>【实测七百四十七】最前面加一道**不可驾名单**闸（见 {@link #isUnridable}）——
     * 它必须在"报废/没座位"这些技术判据之前：玩家要看到的理由是"这架认人，不给她开"，
     * 而不是"这辆载具没有能坐的位子"。
     */
    public static String denyReason(Entity e) {
        try {
            if (isUnridable(e)) {
                return "这架" + unridableName(e) + "操作门槛太高（或只认玩家来开），我驾驭不了～";
            }
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
            return e.m_6084_();
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
            net.minecraft.world.level.Level level = passenger.m_9236_();
            if (level == null) {
                return y;
            }
            float h = Math.max(0.9f, passenger.m_20206_());
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
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.m_274561_(x, y, z);
            return level.m_8055_(p).m_60742_(level, p,
                    net.minecraft.world.phys.shapes.CollisionContext.m_82749_()).m_83281_();
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
     * = {@code Mth.m_14139_(partialTick, xo, getX())}——其中 {@code xo/yo/zo} 是"上一拍的位置"，
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
            double bb = maid.m_20206_();
            double y = freeSeatY(maid, seat.f_82479_, seat.f_82480_ + bb, seat.f_82481_);
            Vec3 now = new Vec3(seat.f_82479_, y, seat.f_82481_);
            // 【只在客户端写插值字段】{@code xo/yo/zo} 唯一的消费者是**渲染**
            // （{@code getPosition(partialTick)} → 客户端渲染线程），服务端写它没有任何收益；
            // 而 {@code xOld/yOld/zOld} 还兼作服务端碰撞扫掠的"上一拍位置"，在服务端乱写反而
            // 有风险。所以这一档严格限定在客户端那一侧（{@link RideBindManager} 的客户端分支调到这里）。
            if (maid.m_9236_() != null && maid.m_9236_().m_5776_()) {
                Vec3 origin = dragon.m_20182_();
                // 鞍位相对龙体的偏移（含俯仰/飞行补偿与"抬出方块"的 y 修正）
                double offX = now.f_82479_ - origin.f_82479_;
                double offY = now.f_82480_ - origin.f_82480_;
                double offZ = now.f_82481_ - origin.f_82481_;
                // 龙的"上一拍端点"（原版每只实体 tick 开头由 setOldPosAndRot 拍下，客户端亦然）
                maid.f_19854_ = dragon.f_19854_ + offX;
                maid.f_19855_ = dragon.f_19855_ + offY;
                maid.f_19856_ = dragon.f_19856_ + offZ;
                maid.f_19790_ = maid.f_19854_;
                maid.f_19791_ = maid.f_19855_;
                maid.f_19792_ = maid.f_19856_;
            }
            maid.m_6034_(now.f_82479_, now.f_82480_, now.f_82481_);
            maid.m_20256_(Vec3.f_82478_);
            // 朝向镜像（含"上一拍朝向"，让她的转身插值与龙同源）。朝向字段两侧都写：
            // 服务端写是为了下一次同步包里带的朝向就是龙这一拍朝向（客户端 lerpTo 也读它）。
            float dyaw = dragon.m_146908_();
            float dyawO = dragon.f_19859_;
            maid.f_19859_ = dyawO;
            maid.m_146922_(dyaw);
            // yBodyRot/yBodyRotO 声明在 LivingEntity 上（javap 实证）——必须转成 LivingEntity
            // 才访问得到；她本来就是 EntityMaid（LivingEntity 的子类），这个 instanceof 恒真。
            if (maid instanceof LivingEntity le) {
                le.f_20884_ = dyawO;
                le.f_20883_ = dyaw;
            }
            maid.m_5616_(dyaw);
            // 【实测七百五十九·点3】俯仰也镜像——这是"真骑手"有、我们原来漏掉的一半。
            //
            // 依据（javap 实证）：冰火传说给**控制乘客**摆位时，除偏航外连 xRot 一起同步
            // （1.20.1 {@code EntityDragonBase.m_19956_} 里 {@code setXRot(passenger.getXRot())}；
            // 1.21.1 {@code positionRider} 同款），也就是说"骑在龙上的那个人"的**俯仰是跟着龙走的**。
            // 我们只镜像了 yaw / yBodyRot / yHeadRot，于是龙一俯冲、一抬头，她**直挺挺地杵着**——
            // 位置对、姿态却是脱开的，"人在龙背上飘"的观感有很大一部分来自这里（玩家原话
            // 「一旦龙开始飞行和跟随主人的时候，女仆的位置就会发生严重的改变和错乱」）。
            //
            // xRotO（上一拍）一起写，是为了让她的**抬头插值**也与龙同源，与上面 yaw 的处理逐字同口径。
            maid.f_19860_ = dragon.f_19860_;
            maid.m_146926_(dragon.m_146909_());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 置/撤"无重力"（她不是乘客，原版没有东西托着她——绑上时必须无重力）。 */
    public static void setGravity(Entity e, boolean gravity) {
        try {
            if (e != null) {
                e.m_20242_(!gravity); // setNoGravity(!gravity)
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
                    mob.m_21573_().m_26573_();
                } catch (Throwable ignored) {
                }
            }
            maid.m_20256_(Vec3.f_82478_);
            if (maid instanceof net.minecraft.world.entity.LivingEntity le) {
                le.m_6274_().m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26370_);
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
     * {@code EntityMaid.isMaidInSittingPose()}（= {@code TamableAnimal.m_21825_()}，
     * 读 {@code DATA_FLAGS_ID} 第 0 位）。两条渲染路径都问它：
     * <ul>
     *   <li>Gecko 模型：{@code TLMBinding} 把 molang 变量 {@code tlm.is_sitting} 绑到
     *       {@code ((EntityMaid)ctx.m_91449_()).isMaidInSittingPose()}（反编译实证）。</li>
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
            maid.m_21837_(sitting);
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
     * v1.3.0(beta) 实测七百二十·点2【骑乘指挥棒吞掉原版右击：先把"部位"还原成"本体"】。
     *
     * <p>玩家原话：「加一个新设定，骑乘指挥棒在使用的时候不会触发原本的右击效果。只会触发
     * 骑士棒自己的右击效果，也就是说你拿骑乘棒是骑不上龙或者车子的。」
     *
     * <p>为什么必须解析：冰火传说的龙是**多部件实体**——准星打中的不是龙本体，而是它身上
     * 若干个 {@code EntityMultipartPart}/{@code MultipartPartEntity}（翅膀/尾巴/头各一个实体），
     * 而那些部位会把这一下右击**转发给本体**（{@code EntityMultipartPart.m_6096_} 里
     * {@code getParent().m_6096_(player, hand)}，反编译实证）。所以指挥棒的处理器若只认"目标
     * 本身是不是坐骑"，看到的是**部位**（既不是女仆、也不是 Mob）→ 直接放行 → 转发到龙 →
     * 玩家就骑上去了。这正是"拿着棍子还是能骑上龙"的来源。
     *
     * <p>判据用**类名**（{@code MultipartPart} 前缀，两家包名与两个版本共四种写法都在列），
     * 找不到就原样返回——**绝不猜**：只有确认它是"部位"才去问它的本体。
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

    /** 它是不是"多部件实体的一块部位"（冰火传说的龙部件；别的模组同构类名同样认得）。 */
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
    private static Method mPartGetParent;   // EntityMultipartPart.getParent() / MultipartPartEntity.getParent()

    private static synchronized void initPart() {
        if (partInited) {
            return;
        }
        partInited = true;
        String[] candidates = {
                "com.iafenvoy.iceandfire.entity.EntityMultipartPart",        // 社区版 1.20.1
                "com.iafenvoy.iceandfire.entity.MultipartPartEntity",        // 社区版 1.21.1
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
     * <p>【实测七百二十三】新增第三参 {@code maid}：**轮椅（WHEELCHAIR）/ TOM6 这两台引擎
     * 不用左右位**（反编译 {@code VehicleEngineUtils.wheelChairEngine:1007} 实证：它读的是
     * {@code getFirstPassenger().getYHeadRot() - 车头 yaw}），必须由我们**写乘客的头朝向**
     * 才能转向。详见 {@link #driveVehicle}。
     *
     * @param mount    坐骑（本类只处理 VEHICLE / DRAGON）
     * @param target   目的地点
     * @param modifier 速度倍率（载具档用它缩放油门）
     * @param maid     骑在上面的女仆（写头朝向用；可为 null）
     */
    public static boolean drive(Entity mount, Vec3 target, double modifier, Entity maid) {
        try {
            Kind k = kindOf(mount);
            if (k == Kind.VEHICLE) {
                return driveVehicle(mount, target, modifier, maid, false);
            }
            // 【实测七百二十三】DRAGON 不再由本类驱动：降级方案里女仆只是"挂在龙的骑乘位
            // 上"（不是乘客），龙自己那套飞行物理照常跑。我们**不碰**它的 flightManager。
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百三十九·点3】接敌绕圈的驱动档：与 {@link #drive} **同一套输入**，只关掉两处
     * "把它当成到达点"的收敛——停车带刹车、以及"没对准就不给油"。
     *
     * <h2>玩家原话</h2>
     * 「女仆骑车对敌人的绕圈堪称灾难。基本上就是往敌人身上一撞就完事了，根本绕不起来。
     * 也发挥不出车辆的速度」
     *
     * <h2>根因（就是"到达点收敛"用错了地方）</h2>
     * {@link #driveVehicle} 里 {@code horiz <= stopBand(mount)} 就 {@code brakeVehicle}，而
     * {@code stopBand} 含**车速前瞻**（{@code 车体半宽+1 + |Δ|×10}）：车速 1 格/拍时停车带高达
     * **13 格**，而绕圈用的"胡萝卜"只挂在前方 4~6 格 → **每拍都判"到了"、每拍都真刹车**。
     * 车于是"冲一下、刹一下"，永远起不来速，最后就是玩家看到的"一撞就完事"。
     * 同理那句 {@code |err| < 40} 才给油也会在转弯时把油门掐掉。
     *
     * <p>绕圈时目标点**本来就不是一个要停下来的点**，所以这一档：不刹车、只要没偏得太离谱就一直
     * 给油（转弯靠转向位与机头兜底，不靠松油门）。停车/跟随那条路一个字节不变。
     */
    public static boolean driveOrbit(Entity mount, Vec3 target, double modifier, Entity maid) {
        try {
            if (kindOf(mount) == Kind.VEHICLE) {
                return driveVehicle(mount, target, modifier, maid, true);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百三十九·点3】地面接敌绕圈的半径（格）——**按车速自适应**。
     *
     * <p>为什么不能用固定半径：车的转弯能力是有限的。机头兜底每拍最多转
     * {@code CAR_HEAD_MAX_STEP} 度，所以"能绕住的半径"下限 = {@code 车速 / 该角速度}：
     * 车速 1 格/拍时至少要 {@code 1 / 0.1745 ≈ 5.7} 格，再留一倍余量就是 ~11 格。半径给小了，
     * 车根本拐不过来 → 直直撞进敌人怀里（正是玩家说的"一撞就完事"）；半径给足，它才能
     * **真的把速度跑起来**沿着圆走（"发挥不出车辆的速度"也随之解决）。
     *
     * <p>下限还叠**车体尺寸**：坦克半宽 2 格，绕 5 格等于原地蹭。
     *
     * @param configRadius 配置里的基础半径（{@code combat.ride.orbitRadius}）
     */
    public static double groundOrbitRadius(Entity mount, double configRadius) {
        double r = Math.max(GROUND_ORBIT_FLOOR, configRadius);
        try {
            double v = horizontalSpeed(mount);
            r = Math.max(r, v * GROUND_ORBIT_SPEED_GAIN);          // 开得越快，圈越大
            r = Math.max(r, mount.m_20205_() * GROUND_ORBIT_WIDTH_GAIN); // 车越大，圈越大
        } catch (Throwable ignored) {
        }
        return clamp(r, GROUND_ORBIT_FLOOR, GROUND_ORBIT_MAX);
    }

    /** 绕圈半径下限（格）：任何地面载具都不该绕得比这更紧（转弯能力所限）。 */
    private static final double GROUND_ORBIT_FLOOR = 6.0;
    /** 绕圈半径上限（格）：再大就打不着了（武器射程/视野内）。 */
    private static final double GROUND_ORBIT_MAX = 28.0;
    /** 车速 → 半径系数：{@code 1 格/拍} 至少要 {@code 5.7} 格才能绕住，这里取 2 倍余量。 */
    private static final double GROUND_ORBIT_SPEED_GAIN = 11.0;
    /** 车体半宽 → 半径系数：坦克（半宽 2）自动拉到 6 格以上。 */
    private static final double GROUND_ORBIT_WIDTH_GAIN = 3.0;

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
     * <h2>【实测七百二十三】轮椅（WHEELCHAIR）/ TOM6：左右位**不被读**，转向只听乘客的头</h2>
     * 玩家原话：「可以把女仆绑在轮椅上了，但是女仆移动的路径完全就跟女仆应有的路径不符。
     * 大部分情况是坐上轮椅之后，朝轮椅面朝的方向移动个几步，然后就停在那边了。为什么骑马
     * 就不会出现这种情况呢？」——实机日志就是证据（19:15:14 起每 5 秒一行）：
     * <pre>
     *   位掩码=6 引擎=WHEELCHAIR 距目标=8格    6 = 0x002|0x004（右转 + 前进）
     *   位掩码=1 引擎=WHEELCHAIR 距目标=15格   1 = 0x001（左转）—— 距目标反而涨了
     *   位掩码=2 引擎=WHEELCHAIR 距目标=23格   2 = 0x002（右转）—— 一路涨到 23 格
     * </pre>
     * 我们一直在送左右位，但它**原地打转**（距目标越来越大），因为
     * {@code VehicleEngineUtils.wheelChairEngine} 里跟方向有关的只有这一句（反编译实证）：
     * <pre>
     *   diffY = clamp(-90, 90, wrapDegrees(passenger0.getYHeadRot() - this.getYRot()));
     *   this.setYRot(this.getYRot() + clamp(0.4f * diffY, -5*steeringSpeed, 5*steeringSpeed));
     * </pre>
     * 也就是说：**它每 tick 把车头拉向"第一位乘客的头朝向"**，而左右位只在
     * {@code wheelEngine}（普通轮式车）里被读（{@code getHoldTick()} → {@code setDeltaRot}）——
     * 轮椅/TOM6 这两台引擎**根本没有那段**。女仆是 Mob，头朝向由她自己的 LookControl 看着
     * 她的走位目标决定；她一坐上车就不再走路（乘客的位移被 rideTick 吃掉），LookControl 常常
     * 停在原地不动 → 车头永远对着同一个方向 → 走几步就顶住。**骑马不会出现**正是因为它走的是
     * {@code GroundPathNavigation}（{@link MaidRideKit#feedNavigation} 的渠道一），完全不经过
     * 这套引擎。
     *
     * <p>所以这一档**不再指望左右位**，改成两件事一起做（都是"喂给它唯一的输入源"，不是
     * 另写一股力）：① 把目标方位角写进乘客的 {@code setYHeadRot} —— 引擎自己那
     * {@code 0.4*diffY} 那一项就朝对的方向加；② **同时按有限速率把车头 yaw 拽向目标**
     * ——因为引擎那一项的速率被 {@code 5*steeringSpeed} 卡住（轮椅默认 0.1 → 只有 0.5°/拍
     * ≈ 10°/秒，太慢就是玩家看到的"走几步就顶住"），我们按 {@code HEAD_STEER_MAX_DEG_PER_TICK}
     * 补上，快而不瞬转。两条同向叠加，不会再出现"头被钉住 → diffY=0 → 永远不转"。
     *
     * <p>判据：只有引擎名叫 {@code WHEELCHAIR} 走这一档（其余引擎照旧左右位）。
     * <b>为什么不含 TOM6</b>：它的转向/油门整段写在 {@code passenger instanceof Player}
     * 分支里（反编译实证），Mob 乘客压根进不去，写什么都不会动。引擎名拿不到（反射失败）
     * → 照旧左右位，一个字节不变。
     */
    private static boolean driveVehicle(Entity mount, Vec3 target, double modifier, Entity maid,
                                        boolean orbit) {
        if (mProcessInput == null) {
            return false;
        }
        String eng = engineType(mount);
        double dx = target.f_82479_ - mount.m_20185_();
        double dz = target.f_82481_ - mount.m_20189_();
        double dy = target.f_82480_ - mount.m_20186_();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float err = wrapDegrees(desiredYaw - mount.m_146908_());

        // 【实测七百二十五】飞行载具（直升机/固定翼/飞艇）**换一整套**：它们的航向/俯仰在
        // 鼠标通道里，地面那套左右位表达不了。见 driveFlight 的注释。
        if (isFlyingEngine(eng)) {
            return driveFlight(mount, target, modifier, eng, dy, horiz, err, maid);
        }

        // 【实测七百二十三】轮椅：引擎只认乘客头朝向，左右位不被读。
        boolean headSteer = "WHEELCHAIR".equals(eng);
        // 【实测七百三十七·转向】车/坦克（轮式/履带）另有"直写机头"的兜底档（见 forceCarHeading）。
        boolean carSteer = "WHEEL".equals(eng) || "TRACK".equals(eng);
        if (headSteer) {
            if (maid != null) {
                try {
                    maid.m_5618_(desiredYaw);   // setYHeadRot：引擎唯一读的那个
                    maid.m_146922_(desiredYaw); // setYRot：模型/身体跟上，免得扭着走
                } catch (Throwable ignored) {
                }
            }
            // 车头自己按有限速率朝目标转（补上引擎那一项被 steeringSpeed 卡住的速率）
            //
            // 【实测七百五十三·点1】这一档原来**漏写 setServerYaw**——而本类别处（forceCarHeading /
            // forceHeadingOnto / 飞行档）直写机头 yaw 时都成对写它，因为 SWB 的客户端同步
            // （handleClientSync）会按 serverYaw 把车身 yaw 往回 lerp：只写 setYRot 的那一下
            // 下一拍就被拽回去，而轮椅引擎又**只认**"乘客头朝向 − 车身 yaw"这个差值——车身 yaw
            // 被拽回 = 差值归零 = 引擎不再转向，车就走两步顶住、看着就是玩家说的"坐上就动弹不得"。
            // 补上之后与上面三处逐字同口径；同时按 forceCarHeading 的写法过一遍 wrapDegrees，
            // 免得 yaw 一直无界累加。
            try {
                float step = (float) Math.max(-HEAD_STEER_MAX_DEG_PER_TICK,
                        Math.min(HEAD_STEER_MAX_DEG_PER_TICK, err));
                float yaw = wrapDegrees(mount.m_146908_() + step);
                mount.m_146922_(yaw);
                if (mSetServerYaw != null) {
                    try {
                        mSetServerYaw.invoke(mount, yaw);
                    } catch (Throwable ignored) {
                    }
                }
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
        // 站着不冲就是玩家看到的"走几步就停"）。
        // 【实测七百三十九·点3】绕圈档**永远给油**（只有偏得太离谱、快掉头时才收）：
        // 目标点是"前方的胡萝卜"不是"要停下的点"，松油就等于绕不起来。
        boolean gasGate = orbit ? Math.abs(err) < 100.0f
                                : (headSteer || !turning || Math.abs(err) < 40.0f);
        if ((orbit || horiz > stopBand(mount)) && gasGate) {
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
            // 【实测七百三十七·转向】地面机头兜底：引擎自己那条链（holdTick→deltaRot→rudderRot）
            // 慢速转不动、快速追不上（见 forceCarHeading 注释）。**在 processInput 之后**写，
            // 让引擎这一拍自己的 yaw 积分先落地，我们只纠残余；只写 yaw、不碰 deltaRot，
            // 输入位照旧发（两股力同向叠加，不打架）。判据与诊断一致：只接**轮式/履带**这两台
            // 引擎（引擎名拿不到/别的引擎一字不变）——轮椅有自己的头朝向档、飞艇有独立升降轴、
            // TOM6 的转向整段写在"乘客是玩家"分支里，都不该被这条兜底影响。
            if (carSteer) {
                forceCarHeading(mount, err);
            }
            // 【实测七百二十四】进了停车带就**真刹车**（旧版只清前进位，车靠摩擦慢慢滑）。
            // 【实测七百三十九·点3】绕圈档**不刹车**：目标点是前方的胡萝卜，停车带（含车速前瞻
            // 可达十几格）每拍都会命中它 → 每拍刹车 = 车永远起不来速、撞上去就完事。
            if (!orbit && horiz <= stopBand(mount)) {
                brakeVehicle(mount);
            }
            // 【实测七百四十一·点3】地面载具同样支持"炮随车体"那档的机头瞄准（最后写的赢）。
            // 判据天然最窄：只有 aimBallistic 登记了请求（这车有炮、有敌人、且没走炮塔/武器站）
            // 才会生效；其余一切情况 NOSE_AIM 里没有这一辆 → 本句是空操作。
            applyNoseAim(mount);
            logDrive(mount, (orbit ? "绕圈档 " : "") + "位掩码=" + bits + " 引擎=" + (eng.isEmpty() ? "?" : eng)
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
            return base + mount.m_20184_().m_82553_() * 10.0;
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
                return Math.max(1.5, (double) mount.m_20205_() + 1.0);
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
            mount.m_20256_(mount.m_20184_().m_82490_(0.45));
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
                // 【实测七百四十三·点2】固定翼收手时必须**关掉 loiter**，否则她会一直在原地绕圈
                // （loiter 是引擎自己跑的，与我们给不给目标点无关）。
                if (isAircraftEngine(eng) && mSetLoiterActive != null) {
                    try {
                        mSetLoiterActive.invoke(mount, false);
                    } catch (Throwable ignored) {
                    }
                }
                // 【实测七百四十三·点3】飞艇收手：liftSpeed 归零（引擎会自己按重力收敛回浮高）。
                if (isAirshipEngine(eng) && mSetLiftSpeed != null) {
                    try {
                        mSetLiftSpeed.invoke(mount, 0.0f);
                    } catch (Throwable ignored) {
                    }
                }
                if (mSetHoverMode != null && !isAirshipEngine(eng) && !isTomEngine(eng)) {
                    try {
                        mSetHoverMode.invoke(mount, true); // 站着不动 → 悬停（飞艇/汤姆6 没这个开关）
                    } catch (Throwable ignored) {
                    }
                }
                // 【实测七百四十五·点2】收手时也要抹掉残余**水平**速度（竖直分量保留，空中不能失去升力）：
                // 旧版只清输入，飞艇/固定翼松油后按 0.9~0.96 衰减、还会滑出去很远（"漂移严重"）。
                killHorizontal(mount, FLIGHT_STOP_RETAIN);
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
        // 【实测七百四十三·点3/点4】AIRSHIP（飞艇：极恶乐魂/基洛夫/空中绵羊）与 TOM6（汤姆6）
        // 一并进本档，各走自己的专属分支：
        //   · AIRSHIP 的竖直轴是 setLiftSpeed（反编译实证：位掩码上下位只是它的积分器，
        //     且每拍 *0.8 衰减），用位掩码"冲一下掉一下"——必须直接写 liftSpeed 做 PD。
        //   · TOM6 的油门/姿态**整段**写在"乘客是 Player"分支里（反编译 tomEngine 实证），
        //     位掩码与鼠标通道对 Mob 乘客**一个都不生效**，只能直写 power/XRot/YRot。
        return "HELICOPTER".equals(eng) || "AIRCRAFT".equals(eng)
                || "AIRSHIP".equals(eng) || "TOM6".equals(eng);
    }

    /** 【实测七百四十三·点3】这只是不是"飞艇"（AIRSHIP：极恶乐魂 / 基洛夫 / 空中绵羊）。 */
    private static boolean isAirshipEngine(String eng) {
        return "AIRSHIP".equals(eng);
    }

    /** 【实测七百四十三·点2】这只是不是"固定翼"（AIRCRAFT：AC-130H / A-10 / J-16 / Ju-87）。 */
    private static boolean isAircraftEngine(String eng) {
        return "AIRCRAFT".equals(eng);
    }

    /**
     * 【实测七百七十九·点2】这只是不是固定翼（对外公开的口径，给 {@code RideBindManager} 用）。
     *
     * <p>固定翼有两处与直升机/飞艇**必须分开**：① 接敌时的胡萝卜是"敌正上方"而不是"绕圈的圈上点"；
     * ② 它气动上转不过来小圈（最小转弯半径 ≈55~65 格，推导见 {@code driveFlight} 里
     * "固定翼巡航"那段注释），所以"主人离太远就收手"那道闸对它要放宽。
     */
    public static boolean isAircraft(Entity e) {
        return isAircraftEngine(engineType(e));
    }

    /**
     * 【实测七百七十九·点2】固定翼接敌时"主人可以离多远"（格）。
     *
     * <p>{@code RideBindManager} 有一条"主人超出接敌半径就放弃敌人、转去追主人"的闸，旧值复用
     * 索敌器的**发现半径** 50 格。那对直升机/扫帚合适，对固定翼**必然误伤**：它一次通场
     * （冲过去 + 拐大弯掉头）就要飞出 100~200 格，于是每一趟都在半路被判"主人不在场"、
     * 敌人被丢掉 —— 玩家看到的"飞机不支援战斗"有一半是这条闸造成的。
     * 这里给它一个与航程相称的上限：{@code 200} 格（≈ 一次通场 + 掉头的跨度），
     * 直升机/扫帚/飞艇一个字节不变。
     */
    public static final double AIRCRAFT_ENGAGE_LEASH = 200.0;

    /** 【实测七百四十三·点4】这只是不是汤姆6（TOM6——油门/姿态整段写在"乘客是玩家"分支里）。 */
    private static boolean isTomEngine(String eng) {
        return "TOM6".equals(eng);
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
    /**
     * 【实测七百六十·电量闸】这架载具是不是"没电了"——判据**逐字照抄引擎自己那一条**
     * （反编译 {@code VehicleEngineUtils.helicopterEngine:1005-1058} 实证）：
     *
     * <pre>
     *   if (getEnergy() &lt;= energyCostRate * |getPower()|) {
     *       setPower(getPower() * 0.995f);
     *       setForwardInputDown(false); setBackInputDown(false);
     *       setEngineStart(false); setEngineStartOver(false);   // 熄火
     *   }
     * </pre>
     *
     * <p><b>为什么需要我们这一道</b>：引擎那条闸是"每拍自己检查"，可我们的飞行驱动
     * （{@link #driveFlight} 的直升机高度 PD {@code setPower(0.045~0.12)}、固定翼点火
     * {@code setPower(0.35)} 与 loiter）**每拍直接写 power**——引擎刚把它熄掉，下一拍我们就
     * 又写回去，等于把这条闸整个绕过。玩家看到的就是「哪怕没有电，女仆仍然可以启动直升机」。
     *
     * <p><b>判据</b>：{@code getEnergy() <= 0} = 电量见底，不驱动。引擎那一条还乘了每辆车的
     * {@code energyCostRate}（在 {@code EngineInfo} 数据里、实体上拿不到），这里取**最保守的
     * 等价形式**「电量到底才拦」——绝不误伤还有电的车，也绝不会出现"满电被当成没电"。
     *
     * <p>{@code hasEnergyStorage} 那条（{@code getMaxEnergy() <= 0} = 这车根本没有能量仓）
     * 也照抄：没有能量仓的车（船/马车之类）**永不**被这道闸拦，与引擎的
     * {@code consumeEnergy} 里那句"hasEnergyStorage 为假就只打日志、照常跑"同口径。
     *
     * @return true = 没电（不该驱动飞行）；拿不到读数 / 无能量仓 → false（不拦）
     */
    private static boolean outOfFuel(Entity mount) {
        if (mGetEnergy == null || mGetMaxEnergy == null || mount == null) {
            return false; // 反射拿不到 → 不启用这道闸（绝不能因为探测失败就锁死驾驶）
        }
        try {
            int energy = ((Number) mGetEnergy.invoke(mount)).intValue();
            int max = ((Number) mGetMaxEnergy.invoke(mount)).intValue();
            if (max <= 0) {
                return false; // hasEnergyStorage() == false：这车没有能量仓 → 引擎那套闸本来也不生效
            }
            return energy <= 0; // 电量到底 → 与引擎"没电熄火"同口径
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 没电时这一拍的安全动作：清输入位 + 把总距/推力收到底（引擎自己还会按 0.995 继续衰减）。 */
    private static void brakeVehicleSafely(Entity mount) {
        try {
            if (mProcessInput != null) {
                mProcessInput.invoke(mount, (short) 0); // 清输入位
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mSetPower != null) {
                mSetPower.invoke(mount, 0.0f); // 不写 power 引擎就一直"点火中"，写 0 才是松手
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mSetMouseSpeedX != null) {
                mSetMouseSpeedX.invoke(mount, 0.0f);
            }
            if (mSetMouseSpeedY != null) {
                mSetMouseSpeedY.invoke(mount, 0.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 电量闸拦下时的留痕（5 秒/车节流），日志搜「载具电量」。 */
    private static void logFuelBlocked(Entity mount, String eng) {
        try {
            long now = System.currentTimeMillis();
            String key = mount.m_20148_().toString();
            Long last = FUEL_LOG_AT.get(key);
            if (last != null && now - last < 5000L) {
                return;
            }
            if (FUEL_LOG_AT.size() > 256) {
                FUEL_LOG_AT.clear();
            }
            FUEL_LOG_AT.put(key, now);
            com.maidsmart.tool.PromaidLog.log("载具电量", describeKind(mount) + " 引擎=" + eng
                    + " 电量不足 → 拒绝驱动飞行（与引擎自身那条\u201c没电熄火\u201d闸同口径；"
                    + "日志搜「载具电量」）");
        } catch (Throwable ignored) {
        }
    }

    /** 电量闸留痕节流表（每车 5 秒一条）。 */
    private static final java.util.Map<String, Long> FUEL_LOG_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean driveFlight(Entity mount, Vec3 target, double modifier, String eng,
                                       double dy, double horiz, float err, Entity maid) {
        if (mSetMouseSpeedX == null && mMouseInput == null) {
            return false; // 探测失败 → 交回地面档
        }
        // 【实测七百六十·电量闸】没电就别驱动飞行——见 {@link #outOfFuel} 的说明（同一条引擎闸）。
        // 放在最前：写 mouse/power 之前先问，否则我们那一写就把引擎自己那条"没电熄火"救活了。
        if (outOfFuel(mount)) {
            brakeVehicleSafely(mount);
            logFuelBlocked(mount, eng);
            return true; // 这一拍由我们接管（拒绝驱动），地面档也不要再来抢
        }
        try {
            boolean heli = "HELICOPTER".equals(eng);
            boolean airCombat = MaidAirCombat.enabled();
            double dead = airCombat ? FLIGHT_HOVER_DEADZONE : FLIGHT_ALT_DEADZONE;
            // 【实测七百二十八】只用于日志区分两档（高度本身已由目标点 Y 表达）。
            boolean fighting = airCombat && maid instanceof EntityMaid em && MaidAirCombat.inCombat(em);

            double hspeed = horizontalSpeed(mount);

            // 【实测七百七十五·点4】载具环境避险——照搬"移动电路"里已经做过的那三条
            // （不贴地 / 不撞墙 / 不钻一格）。玩家原话：「目前这些载具没有对于环境的相关避险机制。
            // 这个是我们原来移动电路里面已经做了的，看看能不能照搬上来。」
            // 做法：目标高度先过一道"避险抬升"——脚下离地太近就抬到净空线；前方 8 格内
            // （机身三层都算）有方块就再抬一截越过它。抬升只加在**期望高度**上，姿态/推力
            // 仍由下面各档自己的控制器写，所以对四种引擎是同一套、零副作用。
            double avoidLift = vehicleAvoidLift(mount, hspeed);
            if (avoidLift > 0.0) {
                dy += avoidLift;
                target = target.m_82520_(0.0, avoidLift, 0.0);
                logAvoid(mount, avoidLift);
            }

            // 【实测七百三十九·点1】悬停档：**机头锁住不动**（玩家原话「在空中悬停的时候，总是在
            // 原地进行不停的旋转……能不能让它的头在悬停期间朝向不要发生变化呢？」）。
            // 根因：悬停时跟随目标点就压在她自己脚下（水平误差≈0），算出来的 `err` 是一个**退化方位**
            // （atan2(0,0) 或她的座位与目标点的微小抖动）——每拍都不同，机头就被这个抖动的方位
            // 一直拽着转。悬停本来就该是"停住"，航向自然也该停住。所以这一档**记下进档那一刻的
            // 机头并每拍写回**（连 serverYaw 一起写，否则 handleClientSync 会慢慢拽走），
            // 鼠标 X 通道也归零（它是引擎唯一能让机头自己转的输入）。退出悬停档立刻解冻。
            boolean holdYaw = heli && airCombat && horiz <= FLIGHT_ARRIVE && hspeed < FLIGHT_PARK_SPEED;
            if (holdYaw) {
                float held = hoverYawHold(mount);
                try {
                    mount.m_146922_(held);
                } catch (Throwable ignored) {
                }
                if (mSetServerYaw != null) {
                    try {
                        mSetServerYaw.invoke(mount, held);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                releaseHoverYaw(mount); // 不再悬停 → 解冻（下次进档重新采样）
            }

            // ① 偏航：写 X 通道（引擎里那一项被 clamp(…, -10, 10) 卡住，取值与玩家推鼠标同档）。
            // 【实测七百三十三】**只有近距/小误差时才走这条**——远距离改走下面的"直写机头"
            // 后门。原因见 forceHeadingOnto 的注释：这条通道的权限还不到玩家的一成。
            float yawCmd = holdYaw ? 0.0f : clamp(err, -FLIGHT_YAW_MAX, FLIGHT_YAW_MAX);
            mSetMouseX(mount, yawCmd);

            short bits = 0;
            float pitchCmd;

            if (heli) {
                // 【先把输入位清干净】本档**不再用位掩码**（俯仰走鼠标通道、总距走 power）。
                // 不清的话上一次残留的"抬总距位"会让引擎在 :638-641 又往 power 上加一笔、
                // 与我们下面的 PD 写值打架。清掉之后引擎走 :657-660 那条"按竖直速度自稳"的
                // 弱控制器（悬停档系数 0.01），正好当我们的慢速外环。
                try {
                    mProcessInput.invoke(mount, (short) 0);
                } catch (Throwable ignored) {
                }
                // 【实测七百三十·点1·根因】直升机的**俯仰输入要乘一个很小的 propeller**：
                //   反编译 helicopterEngine:611 → xRot += 1.5 * pitchSpeed(0.75) * mouseY * propeller，
                //   而 propeller 由 power 决定、悬停时 power≈0.1（:638-641 上限也只有 0.12）→
                //   **即使 mouseY 拉满，每拍也只转 0.1° 出头**。727/729 两版都在这个通道里写 0.6~0.75
                //   的小量，等于没写，机头永远压不下去 → 水平推进（唯一来源是"低头 → 升力分出水平分量"）
                //   恒为零 → 玩家看到的「彻底失去前后左右移动能力，只会上下飞行」。
                //
                //   本版改成**串级速度环**：外环按剩余距离给期望速度（上限照搬扫帚的 0.75 格/拍），
                //   再换算成**目标前倾角**；内环把"当前俯仰角 vs 目标角"的误差写进鼠标 Y 通道，
                //   量级提到玩家档（±8）。**与扫帚同一套速度包络、同一处口径。**
                double desired = clamp(horiz * FLIGHT_SPEED_PER_BLOCK, 0.0, FLIGHT_HMAX);
                // 【实测七百三十三·根因】旧版这里有一句 `if (|err| >= 60) desired = 0`（"转向优先，
                // 没对准就不给速度"）。实机日志揭穿了它：`鼠标X=-6 期望=0.00 水平速度=0.00` 反复出现
                // —— 偏航通道权限太小、机头总是转不过来，于是**长期卡在"不给速度"分支里原地打转、
                // 一点不前进**，这正是玩家说的「长距离很容易飞偏 / 中途失速」。
                // 本版**删掉这个分支**：航向改由 forceHeadingOnto 直接写机头（见下），转得动，
                // 也就不需要靠"停住等转向"。给速度与转向从此解耦。
                //
                // 【实测七百三十一·后门】到位就**不再给任何前倾**——期望速度归零，让内环把机头
                // 摆平（顺带把发动机那点残余水平推力卸掉），再由下面的硬刹车抹掉动量。两者配合
                // 才能真正停住；只刹不摆平的话，机头还压着，下一拍水平速度又长回来。
                boolean hardBrake = shouldHardBrake(horiz, hspeed, fighting);
                if (hardBrake) {
                    desired = 0.0;
                }
                // 外环：速度误差 → 目标俯仰角（度）。机头**下压为正**（引擎里 mouseY>0 → xRot+ → 低头）。
                float targetTilt = clamp((float) ((desired - hspeed) * FLIGHT_TILT_PER_SPEED),
                        -FLIGHT_TILT_MAX, FLIGHT_TILT_MAX);
                // 内环：姿态误差 → 鼠标 Y 通道。写满上限也只 8，与玩家推鼠标同档。
                float pitchCmdNow = clamp((targetTilt - mount.m_146909_()) * FLIGHT_TILT_GAIN,
                        -FLIGHT_HELI_PITCH_MAX, FLIGHT_HELI_PITCH_MAX);
                pitchCmd = pitchCmdNow;
                mSetMouseY(mount, pitchCmdNow);

                // ② 总距：**直接用 power 定高**（PD 控制器），不再按位掩码。
                //
                // 【为什么不能再用位掩码】反编译实证：抬总距那条路是
                //   {@code power += 7e-4 * powerAdd * min(holdPowerTick,10)}（:638-641），
                //   而 power 每次松手都按竖直速度反调（:657-660）。它是一台**很慢的**积分器
                //   （从 0 到悬停所需 power≈0.058 要约 54 拍 ≈ 2.7 秒），逐拍开关只会形成极限环
                //   ——实机日志 {@code 高差=3 → -4 → -1 → 2 → 0 → -4} 永远荡。本版直接把 power
                //   写成"按**当前竖直速度**预测落点算出来的值"，D 项把过冲吃掉。
                double vy = verticalSpeed(mount);
                double lead = dy - vy * FLIGHT_VY_LEAD;      // 预测落点（拍 × 格/拍）
                float powerCmd = -1.0f;                      // <0 = 反射拿不到，没写成功
                if (mSetPower != null) {
                    // 【基准怎么算出来的】反编译三处联立（mi_28：lift=1、gravity=0.06）：
                    //   ① 引擎每拍 {@code deltaMovement.f_82480_ *= 0.95} 再 {@code += propeller*lift*0.66}（:558/:686）；
                    //   ② baseTick 末尾 {@code deltaMovement.f_82480_ -= 0.06}（:4068）；
                    //   ③ {@code propeller} 稳态 ≈ {@code power}（:680 lerp）。
                    //   悬停（竖直速度=0）⇒ {@code 0.05·vy = 0.66·power − 0.06} ⇒ **power ≈ 0.091**。
                    // 所以基准取 0.091；按预测落点线性给量，钳制在[0.045, 0.12]——**下限不能低于
                    // 0.04**：引擎里 {@code power < 0.04} 会把发动机关掉（:687-693），那就变成"熄火坠机"。
                    float base = 0.091f;
                    double k = lead > 0.0 ? 0.010 : 0.014;   // 上升慢一点、接住下坠快一点
                    powerCmd = (float) clamp(base + lead * k, 0.045, 0.12);
                    try {
                        mSetPower.invoke(mount, powerCmd);
                    } catch (Throwable ignored) {
                        powerCmd = -1.0f;
                    }
                }
                // 悬停档：只在"真的停在目标点上"时开（727 的常开把它焊死了，见 CHANGELOG 729）。
                boolean parked = airCombat && horiz <= FLIGHT_ARRIVE && hspeed < FLIGHT_PARK_SPEED;
                if (mSetHoverMode != null) {
                    try {
                        mSetHoverMode.invoke(mount, parked);
                    } catch (Throwable ignored) {
                    }
                }
                // 【实测七百四十五·抽公共】"急停三件套"（硬刹 + 每 0.5 秒周期脉冲 + 水平速度硬上限）
                // 原本只写在直升机这一档里，现在提成 {@link #flightBrake} 由三种飞行档共用
                // （玩家原话：「极乐恶魂以及其他的飞行载具都没有像直升机那样的同款急停。导致漂移
                // 非常严重。」）。逻辑与七百三十一/七百三十二/七百四十三 一字未改，只是搬了位置。
                int brake = flightBrake(mount, hardBrake, fighting);
                boolean pulse = (brake & BRAKE_FLAG_PULSE) != 0;
                // 【实测七百三十三·直写机头】长距离不飞偏的正解：把机身航向直接写过去，
                // 只留一点点 mouseX 微调（见 forceHeadingOnto 的注释——那条通道权限不足两成，
                // 且与滚转耦合）。拿不到反射就退回纯鼠标通道（yawCmd 已在上面写过）。
                boolean headLock = forceHeadingOnto(mount, err);
                if (headLock) {
                    mSetMouseX(mount, clamp(err, -FLIGHT_YAW_TRIM, FLIGHT_YAW_TRIM));
                }
                // 【实测七百三十九·点1】悬停档里**机头锁死**：上面那两处（鼠标通道 + 直写机头）
                // 都会按抖动的 err 改朝向，正好是玩家看到的"原地不停旋转"。这里放在最后、
                // 把它们全部覆盖回进档那一刻的机头（顺序即优先级：最后写的赢）。
                if (holdYaw) {
                    float held = hoverYawHold(mount);
                    try {
                        mount.m_146922_(held);
                    } catch (Throwable ignored) {
                    }
                    if (mSetServerYaw != null) {
                        try {
                            mSetServerYaw.invoke(mount, held);
                        } catch (Throwable ignored) {
                        }
                    }
                    mSetMouseX(mount, 0.0f);
                }
                // 【实测七百四十三·点1 / 七百四十五·抽公共】速度硬上限已并入 flightBrake。
                logDrive(mount, "飞行档 引擎=" + eng + (parked ? " 悬停档(停住)" : "")
                        + (holdYaw ? " 机头锁死" : "")
                        + (hardBrake ? " 硬刹(水平清零)" : "")
                        + (pulse ? " 周期急停(0.5s)" : "")
                        + (headLock ? " 直写机头" : "")
                        + (airCombat ? (fighting ? " 接敌档(敌上" + (long) MaidAirCombat.fightAltCfg() + "格)"
                                : " 跟随档(比主人高" + (long) MaidAirCombat.followAltCfg() + "格)") : "")
                        + " 鼠标X=" + Math.round(yawCmd)
                        + " 鼠标Y=" + fmt2(pitchCmd)
                        + " 水平速度=" + fmt2(hspeed) + " 期望=" + fmt2(desired)
                        + " 竖直速度=" + fmt2(vy) + " power=" + fmt2(powerCmd)
                        + " 距目标=" + (long) horiz + "格 高差=" + (long) dy);
                return true;
            }

            // ---------- 飞艇（AIRSHIP：极恶乐魂 / 基洛夫 / 空中绵羊）：走"直升机那一套电路" ----------
            //
            // 【实测七百四十三·点3·玩家原话】「女仆在乘坐极恶乐魂这个飞行载具的时候应该直接套用
            // 直升机的那一套电路，现在女仆乘坐它不会在遇到敌人之后自己升高。」
            //
            // 根因：飞艇此前**不进本档**（isFlyingVehicle 只含直升机/固定翼），所以它走的是
            // driveVehicle 那条地面通路——接敌机动整个不生效，高度永远贴着敌人的 Y（玩家看到的
            // "不会自己升高"）。而它其实**有**一个真正的竖直轴：反编译 airShipEngine 实证
            // deltaMovement.y += maxUpSpeedRate * 0.06 * liftSpeed——所以本档直接写 liftSpeed
            // 做高度 PD（同直升机的 power PD 一个道理），比位掩码准。
            if (isAirshipEngine(eng)) {
                mSetMouseX(mount, 0.0f);   // 飞艇不读鼠标通道；清了免得残留
                mSetMouseY(mount, 0.0f);
                // 【实测七百四十五·点2】飞艇也要同款急停（玩家原话「极乐恶魂……没有像直升机那样的
                // 同款急停，导致漂移非常严重」）。旧版只按 horiz>FLIGHT_ARRIVE 决定给不给前进位，
                // 到点后引擎按 power*0.96 衰减（airShipEngine:1178）→ 还在滑。现在与直升机同一套。
                boolean aHard = shouldHardBrake(horiz, horizontalSpeed(mount), fighting);
                short abits = 0;
                if (Math.abs(err) > 8.0f) {
                    abits |= (err > 0) ? 0x002 : 0x001;   // 左右位 → deltaRot → rudderRot
                }
                if (!aHard && horiz > FLIGHT_ARRIVE) {
                    abits |= 0x004;                       // 前位 → power（到点松油）
                }
                if (modifier > 1.05) {
                    abits |= 0x100;                       // sprintMultiply
                }
                mProcessInput.invoke(mount, abits);
                double avy = verticalSpeed(mount);
                double alead = dy - avy * FLIGHT_VY_LEAD;
                float liftCmd = Float.NaN;
                if (mSetLiftSpeed != null) {
                    liftCmd = (float) clamp(alead * AIRSHIP_LIFT_PER_BLOCK, -0.25, 0.25);
                    try {
                        mSetLiftSpeed.invoke(mount, liftCmd);
                    } catch (Throwable ignored) {
                        liftCmd = Float.NaN;
                    }
                }
                int aBrake = flightBrake(mount, aHard, fighting);
                logDrive(mount, "飞艇档 引擎=" + eng + " 位掩码=" + abits
                        + (airCombat ? (fighting ? " 接敌档(敌上" + (long) MaidAirCombat.fightAltCfg() + "格)"
                                : " 跟随档(比主人高" + (long) MaidAirCombat.followAltCfg() + "格)") : "")
                        + ((aBrake & BRAKE_FLAG_HARD) != 0 ? " 硬刹(水平清零)" : "")
                        + ((aBrake & BRAKE_FLAG_PULSE) != 0 ? " 周期急停(0.5s)" : "")
                        + " 竖直速度=" + fmt2(avy)
                        + " liftSpeed=" + (Float.isNaN(liftCmd) ? "n/a" : fmt2(liftCmd))
                        + " 水平速度=" + fmt2(horizontalSpeed(mount))
                        + " 距目标=" + (long) horiz + "格 高差=" + (long) dy);
                return true;
            }

            // ---------- 汤姆6（TOM6）：直写 power/姿态（位掩码与鼠标通道对它全不生效） ----------
            //
            // 【实测七百四十三·点4·玩家原话】「骑乘汤姆6F6的时候女仆不会移动。」
            //
            // 根因（反编译 tomEngine 实证）：它的油门/俯仰/偏航/滚转**整段**写在
            // "else if (passenger instanceof Player)" 分支里——女仆是 Mob，压根进不去那条分支，
            // 于是 power 恒为 0（另一条 passenger == null 分支还会 setPower(0)、清所有输入位），
            // **一动不动**。它的水平推力在 viewVector 上（tomEngine：deltaMovement +=
            // viewVector * 0.061 * speedRate * power），升力在 upVec 上。
            if (isTomEngine(eng)) {
                mSetMouseX(mount, 0.0f);
                mSetMouseY(mount, 0.0f);
                try {
                    mProcessInput.invoke(mount, (short) 0); // 清输入位（引擎那两条 setPower(0) 分支别打架）
                } catch (Throwable ignored) {
                }
                forceHeadingOnto(mount, err);            // 机头对准目标（与直升机同源）
                // 俯仰：按高度差给目标角（负 = 抬头爬升 / 正 = 低头前飞），再直写 XRot。
                // 【为什么不是 "dy * k" 的纯 P】她多半是**从地面起飞**：起飞时 dy 只有几格，纯 P
                // 给的抬头角很小 → 升力不足 → 永远起不来。所以**起飞段**（贴地）给固定抬头角，
                // 离地后才交回 P。反编译 tomEngine 实证升力项是
                // upVec × (dm·viewVector) × 0.022 × lift，机头不抬就没有升力分量；
                // 而 MC 约定 xRot 为负 = 机头朝上（viewVector 抬头）。
                //
                // 【判据必须是 onGround，不能用 dy】用 dy（"高差小就抬头"）会在巡航段形成
                // 每拍 +15° 的棘轮（高度差一小就抬头 → 升高 → 差又变小 → 再抬头），
                // 与七百三十四 修掉的那个悬停棘轮是同一类错误。贴地才是"还没起来"的充要条件。
                boolean grounded;
                try {
                    grounded = mount.m_20096_(); // onGround
                } catch (Throwable ignored) {
                    grounded = false;
                }
                float tomPitch;
                if (grounded) {
                    tomPitch = -TOM_TAKEOFF_PITCH; // 还没离地 → 强制抬头，保证能起来
                } else {
                    tomPitch = (float) clamp(-dy * TOM_PITCH_PER_BLOCK,
                            -TOM_PITCH_MAX, TOM_PITCH_MAX);
                }
                try {
                    mount.m_146926_(tomPitch);
                } catch (Throwable ignored) {
                }
                float tomPower = 0.0f;
                if (horiz > FLIGHT_ARRIVE) {
                    tomPower = (float) clamp(horiz * TOM_POWER_PER_BLOCK, 0.0, TOM_POWER_MAX);
                    if (modifier > 1.05) {
                        tomPower = TOM_POWER_MAX;
                    }
                }
                if (mSetPower != null) {
                    try {
                        mSetPower.invoke(mount, tomPower);
                    } catch (Throwable ignored) {
                    }
                }
                int tBrake = flightBrake(mount, shouldHardBrake(horiz, horizontalSpeed(mount), fighting), fighting);
                logDrive(mount, "汤姆6档 引擎=" + eng + " 推力=" + fmt2(tomPower)
                        + " 俯仰=" + Math.round(tomPitch)
                        + ((tBrake & BRAKE_FLAG_HARD) != 0 ? " 硬刹(水平清零)" : "")
                        + ((tBrake & BRAKE_FLAG_PULSE) != 0 ? " 周期急停(0.5s)" : "")
                        + (airCombat ? (fighting ? " 接敌档(敌上" + (long) MaidAirCombat.fightAltCfg() + "格)"
                                : " 跟随档(比主人高" + (long) MaidAirCombat.followAltCfg() + "格)") : "")
                        + " 距目标=" + (long) horiz + "格 高差=" + (long) dy);
                return true;
            }

            // ---------- 固定翼（AC-130H / A-10 / J-16 等）：起飞助跑离地 + 引擎自带 loiter 盘旋 ----------
            //
            // 【实测七百四十三·点2·玩家原话】「ACH13空中炮艇，我发现女仆根本就不会骑这个东西。
            // 全程乱窜并且没有停止和飞行能力。」
            //
            // 根因（反编译实证，两条）：① 固定翼**没有竖直输入轴**——aircraftEngine 的高度只由
            // 俯仰+速度决定，所以"到点悬停"它做不到，旧版给目标点它只会**一直朝前飞**（实机日志：
            // 距目标 58 → 170 → 214 格，一路飞走）；② 它的"停住并绕圈"是**引擎自带**的一档——
            // VehicleEntity.baseTick 实证：EngineType.AIRCRAFT && getLoiterActive() 时每拍调
            // VehicleEngineUtils.aircraftLoiter(this)，而那个方法自己写 mouseX/mouseY/power 绕
            // getLoiterParams() 给的圆心+半径转圈（还带地形回避）。正解不是我们继续喂目标点，
            // 而是把圆心/半径写进 loiter 参数并打开它，剩下的全交给引擎（它比我们的鼠标通道权限大）。
            // 写不出 loiter（反射失败）→ 退回旧口径，一个字节不变。
            //
            // ---------------------------------------------------------------
            // 【实测七百四十五·点3·玩家原话】「空中炮艇现在可以大致跟随主人的轨迹了，
            // 但它仍然飞不起来。」——**根因（反编译 aircraftEngine 实证，三条叠加）**：
            //   ① 俯仰只在**空中**才写：:801 if (!onGround) setXRot(...) ⇒ 地面上写 mouseY 是空操作；
            //   ② 升力 ∝ **当前速度**：:877 upVec * ... * speed * ... ⇒ 静止时 speed=0 → 升力恒为 0；
            //   ③ 地面推力 ∝ dotViewVector（当前速度在视线上的投影，:702），静止时也恒为 0；
            //      且地面摩擦 f = 0.497 + 0.45*|dotView|（:701）远大于推力的 0.047*power*speedRate。
            //   三条合起来 = **它是一架需要"跑道助跑"的真飞机**：没有初速就永远起不来，
            //   而 loiter（唯一能让她停住盘旋的东西）又要求 !onGround（baseTick:3845）——死锁。
            //   所以"写 loiter 参数"这条我们一直做对了，但它**读不到**。
            //
            //   正解：把这架飞机**从地面抬起来**这一步由我们在外部硬做出来（与本档其它"暴力后门"
            //   同源——直升机的直写 power、TOM6 的起飞抬头角都是同一个道理）：贴地时每拍写一个
            //   抬头角 + 直接补一点竖直速度，把 onGround 顶掉；一旦离地就交回引擎自己的俯仰/loiter。
            //   这也解释了为什么"能大致跟随轨迹"——引擎的地面推力虽小，但目标点仍在逐拍喂，
            //   她在地面滑行时方向是对的。
            {
                boolean grounded;
                try {
                    grounded = mount.m_20096_();
                } catch (Throwable ignored) {
                    grounded = false;
                }
                // 只有"确实要去某处"才起飞助跑：目标就在脚下同高度时不该硬把炮艇从地面拔起来。
                boolean wantAir = horiz > FLIGHT_ARRIVE || Math.abs(dy) > FLIGHT_ALT_DEADZONE;
                // 【实测七百七十六·点2】爬升窗口：起飞那一拍开、之后每拍续，爬到 2 格以内或 15 秒
                // 超时为止。窗口内由我们接管竖直速度（见 aircraftClimbAssist 的说明）。
                long vnow = 0L;
                try {
                    vnow = mount.m_9236_() == null ? 0L : mount.m_9236_().m_46467_();
                } catch (Throwable ignored) {
                }
                Long climbUntil = AIRCRAFT_CLIMB.get(mount.m_20148_());
                boolean climbing = climbUntil != null && vnow < climbUntil;
                if (grounded && wantAir) {
                    // 【实测七百七十五·点3】固定翼定制起飞三步（玩家原话：「先确保周围相当一部分
                    // 是平地（允许接受凹凸，但是落差不能太大），不是的话就报环境不允许起飞。有允许的
                    // 环境之后，就向那个平地的方向先进行加速，加速到一定程度之后，开始将机头往上抬，
                    // 执行飞行链路，随后以主人为圆心，进行环绕盘旋。」）：
                    //   ① 验平地：八向扫"助跑带"（28 格长 × 9 格宽）的地面落差，取最平的一条；
                    //      一条都不合格 → 报"环境不允许起飞"并钉在原地（不硬拔、不滑跑）；
                    //   ② 滑跑：机头锁在助跑方向、满推力，直到水平速度够；
                    //   ③ 抬头+补竖直（原来的物理后门），随后交给引擎 loiter 盘旋 + 照常开火。
                    double[] rw = aircraftRunway(mount);
                    if (rw == null) {
                        try {
                            mProcessInput.invoke(mount, (short) 0); // 清输入位（不滑跑）
                        } catch (Throwable ignored) {
                        }
                        if (mSetPower != null) {
                            try {
                                mSetPower.invoke(mount, 0.0f);
                            } catch (Throwable ignored) {
                            }
                        }
                        refuseTakeoff(mount, maid);
                        logDrive(mount, "固定翼起飞被拦：周围没有落差足够小的助跑地（环境不允许起飞）");
                        return true;
                    }
                    forceHeadingOnto(mount, wrapDegrees((float) rw[0] - mount.m_146908_()));
                    mSetMouseY(mount, 0.0f);
                    // 【实测七百七十八·点2】助跑全程挂着冲刺位（引擎里 sprintInputDown 会把
                    // powerAdd×1.6、maxPower 放宽到 3），并**直接把推力顶到地面最大 3.0**——
                    // 旧版让引擎自己从 0 慢慢爬到 3（+0.006×powerAdd/拍）要等十几二十秒，
                    // 而且没等爬到就抬了机头（0.30 就起飞），升力根本不够。
                    short rbits = (short) (0x004 | 0x100);   // 满推力 + 冲刺
                    mProcessInput.invoke(mount, rbits);
                    aircraftPowerAtLeast(mount, AIRCRAFT_GROUND_MAX_POWER);
                    double rollSpeed = horizontalSpeed(mount);
                    if (rollSpeed < AIRCRAFT_TAKEOFF_SPEED) {
                        logDrive(mount, "固定翼滑跑 引擎=" + eng
                                + " 助跑方向=" + Math.round(rw[0]) + "° 助跑带落差=" + fmt2(rw[1]) + "格"
                                + " 水平速度=" + fmt2(rollSpeed) + "/" + fmt2(AIRCRAFT_TAKEOFF_SPEED)
                                + " 距目标=" + (long) horiz + "格 高差=" + (long) dy);
                        return true;
                    }
                    // ② 抬头（负 = 机头朝上）：让引擎的 viewVector 带上竖直分量。
                    try {
                        mount.m_146926_(-AIRCRAFT_TAKEOFF_PITCH);
                    } catch (Throwable ignored) {
                    }
                    // ② 直接补竖直速度，顶掉"必须助跑"的物理死锁。
                    //    地面摩擦 f≈0.497、重力 0.06 ⇒ add=0.25 时稳态 v≈0.128 格/拍（≈2.5 格/秒）。
                    //    竖直分量取 max(当前,0)+量，所以不会把已有的下坠叠成上冲。
                    try {
                        Vec3 tdm = mount.m_20184_();
                        mount.m_20256_(new net.minecraft.world.phys.Vec3(tdm.f_82479_,
                                Math.max(tdm.f_82480_, 0.0) + AIRCRAFT_TAKEOFF_LIFT, tdm.f_82481_));
                    } catch (Throwable ignored) {
                    }
                    // ③ 推力拉满：先把 engineStart 点着（power > 0.2 → engineStartOver，:884，
                    //    那是 loiter 的另一个前置条件 baseTick:3845）。
                    //    【实测七百七十六·点2】先补能——loiter 闸里还有一条 getEnergy() > 1024，
                    //    而车的能量只能靠往车里塞能量物品充（baseTick:4097）；女仆不会充电。
                    topUpAircraftEnergy(mount);
                    if (mSetPower != null) {
                        try {
                            mSetPower.invoke(mount, 1.0f);
                        } catch (Throwable ignored) {
                        }
                    }
                    // ④ 【实测七百七十九·点2】把引擎自己的 loiter 明确关掉：779 起固定翼的航向/
                    //    高度全由我们自己的巡航环接管（见下面"固定翼巡航"那段的推导），留着 loiter
                    //    会变成两套控制器打架。
                    aircraftLoiterOff(mount);
                    // 【实测七百七十六·点2】开"爬升窗口"：离地之后由我们继续接管竖直速度，直到真的
                    // 爬到目标高度（2 格以内）或 15 秒超时。旧版只在贴地这一拍补一下竖直速度就撒手，
                    // 实机日志里她的炮艇随后一直贴地 0.5 格/拍地滑行盘旋、高差十几格不降
                    //（loiter 被它自己的闸挡着没跑，见 aircraftClimbAssist 的反编译说明）。
                    AIRCRAFT_CLIMB.put(mount.m_20148_(), vnow + AIRCRAFT_CLIMB_WINDOW_TICKS);
                    logDrive(mount, "固定翼起飞 引擎=" + eng
                            + " 抬头=" + AIRCRAFT_TAKEOFF_PITCH + "°"
                            + " 补竖直=" + fmt2(AIRCRAFT_TAKEOFF_LIFT)
                            + " 竖直速度=" + fmt2(verticalSpeed(mount))
                            + " 距目标=" + (long) horiz + "格 高差=" + (long) dy
                            + " 电量=" + energyText(mount));
                    return true;
                }
                // 【实测七百七十六·点2】离地后的"爬升窗口"：目标还在上方 → 每拍抬机头 + 垂直自驾
                // （见 aircraftClimbAssist 的说明）。爬到 2 格以内/窗口超时就撒手，交还引擎 loiter。
                if (!grounded && wantAir && dy > AIRCRAFT_CLIMB_STOP && climbing) {
                    AIRCRAFT_CLIMB.put(mount.m_20148_(), vnow + AIRCRAFT_CLIMB_WINDOW_TICKS);
                    topUpAircraftEnergy(mount);
                    aircraftClimbAssist(mount, dy);
                    try {
                        mount.m_146926_(-AIRCRAFT_TAKEOFF_PITCH);
                    } catch (Throwable ignored) {
                    }
                    // 【实测七百七十八·点2】爬升要的是"升力 ≥ 重力"，而升力 ∝ 速度 → 推力拉满
                    // （旧版只顶到 1.0：AC-130H 在 power=1 的空中稳态速度只有 ~0.7，
                    // 升力 ≈0.02/拍 < 重力 0.06/拍，所以"爬升窗口"里也爬不动）。
                    aircraftPowerAtLeast(mount, AIRCRAFT_GROUND_MAX_POWER);
                    try {
                        mProcessInput.invoke(mount, (short) (0x004 | 0x100));
                    } catch (Throwable ignored) {
                    }
                    aircraftLoiterOff(mount);
                    // 【实测七百八十】爬升档也留一道硬上限（{@link #AIRCRAFT_HMAX}）：这一段推力是
                    // 满的 3.0，放开了她能冲到 ~3.7 格/拍（74 格/秒）——万一半路擦到山，
                    // 撞地伤害 {@code 18×(v−0.2)²} 就是 220 点。巡航速度环负责 0.80，这里只兜住上限。
                    aircraftCapOnly(mount);
                    logDrive(mount, "固定翼爬升 引擎=" + eng + " 抬头=" + AIRCRAFT_TAKEOFF_PITCH + "°"
                            + " 竖直速度=" + fmt2(verticalSpeed(mount)) + " 高差=" + (long) dy
                            + " 距目标=" + (long) horiz + "格 电量=" + energyText(mount));
                    return true;
                }
                if (!grounded && wantAir && dy <= AIRCRAFT_CLIMB_STOP) {
                    AIRCRAFT_CLIMB.remove(mount.m_20148_());   // 到位了 → 收窗，交还引擎自己的 PD
                }
            }
            // ---------- 【实测七百七十九·点2】固定翼巡航：**我们自己的航向 / 速度 / 高度环** ----------
            //
            // 玩家原话（即规格）：「平时没有发现敌人就正常绕着主人在空中盘旋，发现了敌人之后就开始
            // 在敌人头上进行来回穿梭轰炸。」
            //
            // <h2>为什么不再交给引擎自己的 aircraftLoiter（反编译 + 实机日志双实证）</h2>
            // <ul>
            //   <li>它的舵量上限（{@code aircraftEngine:793-800}）：
            //       {@code rotSpeed = 0.3 + 3.2*|calculateY(roll)|}，而 {@code calculateY(x) = x/90}
            //       （VectorTool 实证，**不是** cos），{@code ClampRoll=45} ⇒ 满舵 1.9；
            //       {@code addY = clamp(0.24*speed*mouseX, ±rotSpeed)}，{@code yaw += yawSpeed*addY}。
            //       AC-130H 的 {@code YawSpeed=0.9} ⇒ 最大角速度 1.71°/拍 = 0.0298 弧度/拍，
            //       即**转弯半径 r ≈ v/0.0298**（778/779 那两版按速度 2.2~2.6 飞 ⇒ 74~87 格）。
            //       此处旧注释写的 "≤2.56 / 2.3°/拍 / 55~65 格" 是按 cos 反推的，780 已按
            //       {@code x/90} 更正为 1.9 / 1.71°/拍 / r ≈ v/0.0298。</li>
            //   <li>而 loiter 的半径被我们钳在 8~60 格，**小于它实际转得过来的半径** ⇒ 它的径向
            //       误差项每拍都把她往外推，永远收不拢、只会越飞越远。实机日志正是这个形状：跟随档
            //       {@code 距目标 54→93→140→188→288 格、高差 -39→-141} ——
            //       玩家说的"只会在天上飞"。</li>
            // </ul>
            //
            // <h2>换成什么</h2>
            // 玩家那句话的后半段本身就是正解：固定翼的本行不是画小圈，而是"一遍遍从目标头顶冲过去、
            // 转个大弯再冲回来"。所以这里换成**盯住胡萝卜的纯航向环**：
            // 接敌时胡萝卜在**敌正上方**（{@code RideBindManager} 给的），跟随在**主人上方**；
            // 机头对着它飞，冲过头以后方位角自然翻到 ~180°，她就拐大弯掉头再来——
            // **不需要任何状态机**，"来回穿梭轰炸"是几何的自然结果（炸弹由 {@code tickAttack} 的
            // 投弹链路在每次通场时照常丢）。
            //
            // <h2>四层各管一件事（与直升机/飞艇/汤姆6 三档同一套"分工"口径）</h2>
            //   · 航向 —— 鼠标 X 通道（引擎用它算偏航，权限与玩家推鼠标同档；写满
            //     {@link #AIRCRAFT_YAW_CMD}=12 才拿得到满舵量）；偏航误差大时**叠加左右位**。
            //   · 滚转 —— 【实测七百八十】直接写滚转角（{@link #aircraftBank}）：舵量上限
            //     {@code rotSpeed = 0.3 + 3.2*|roll|/90} 只由它决定，靠左右位让引擎自己滚
            //     要几十拍，而转弯半径 {@code r ≈ v/0.0298} 是每拍都在算的。
            //   · 速度 —— 推力下限 + 冲刺位（保速）+ **巡航速度环**（{@link #aircraftGovernSpeed}，
            //     压速）：`r ≈ v/0.0298`，779 的 2.60 给了 87 格半径、撞地伤害还有 103 点/次。
            //   · 高度 —— **直接写 {@code deltaMovement.y}**（776 那条爬升自驾的对称版）：
            //     固定翼的高度是"速度 × 俯仰"积分出来的，用俯仰环去追一个高度点必然过冲
            //     （778 实机：{@code 高差 -39→-141} 来回荡），直接拉竖直速度才收敛；而且目标高度
            //     先过一道**地形净空线**（{@link #aircraftSafeTargetY}），不然山一抬头就撞。
            {
                boolean grounded;
                try {
                    grounded = mount.m_20096_();
                } catch (Throwable ignored) {
                    grounded = false;
                }
                if (grounded) {
                    // 贴地又不需要去某处（目标就在脚下同高度）→ 别把飞机从地上拔起来
                    // （起飞助跑那一档只对"确实要去某处"开，见上面 wantAir）。
                    try {
                        mProcessInput.invoke(mount, (short) 0);
                    } catch (Throwable ignored) {
                    }
                    if (mSetPower != null) {
                        try {
                            mSetPower.invoke(mount, 0.0f);
                        } catch (Throwable ignored) {
                        }
                    }
                    logDrive(mount, "固定翼待命（贴地、目标就在脚下，不强行起飞）");
                    return true;
                }
                // 【实测七百八十】目标高度先过"地形净空线"（脚下 + 前瞻路径上最高的地 + 14 格）
                // 与胡萝卜高度取大者——三次「坠机了」都是"胡萝卜在山谷、机身在山头"撞出来的。
                double safeY = aircraftSafeTargetY(mount, target.f_82480_, err);
                double dySafe = safeY - mount.m_20186_();
                boolean terrain = safeY > target.f_82480_ + 0.5;
                aircraftCruise(mount, eng, dySafe, err, terrain);
                logDrive(mount, "固定翼巡航档 引擎=" + eng
                        + (airCombat ? (fighting ? " 接敌档(敌上" + (long) MaidAirCombat.fightAltCfg() + "格)"
                                : " 跟随档(比主人高" + (long) MaidAirCombat.followAltCfg() + "格)") : "")
                        + (terrain ? " 地形净空(抬到地面上" + (long) AIRCRAFT_TERRAIN_CLEAR + "格)"
                                : "")
                        + " 鼠标X=" + Math.round(clamp(err, -AIRCRAFT_YAW_CMD, AIRCRAFT_YAW_CMD))
                        + " 滚转=" + rollText(mount)
                        + " 推力=" + powerText(mount)
                        + " 水平速度=" + fmt2(horizontalSpeed(mount))
                        + " 竖直速度=" + fmt2(verticalSpeed(mount))
                        + " 距目标=" + (long) horiz + "格 高差=" + (long) dy
                        + (terrain ? "→" + (long) dySafe : ""));
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百二十九·点1】实测竖直速度（格/拍）——PD 高度控制的 D 项。
     *
     * <p>直升机的竖直速度**是**引擎每拍用升力积分出来的 {@code deltaMovement.f_82480_}（反编译
     * {@code helicopterEngine:686}：{@code deltaMovement.add(getUpVec().scale(propeller*lift*0.66))}），
     * 所以读它就是把"当前正在往上还是往下、多快"拿到手——这正是旧版死区开关缺的那一项。
     * 拿不到 → 0（退化成纯 P，仍比旧版好）。
     */
    private static double verticalSpeed(Entity mount) {
        try {
            if (mount == null) {
                return 0.0;
            }
            return mount.m_20184_().f_82480_;
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
            Vec3 dm = mount.m_20184_();
            return Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /* ---------- 【实测七百七十五·点4】载具环境避险（照搬"移动电路"：不贴地/不撞墙/不钻一格） ---------- */

    /** 载具的最小离地净空（格）——"不贴地"。低于它就把期望高度抬上来。 */
    private static final double VEH_MIN_CLEAR = 3.0;
    /** 前方有方块时一次抬升的格数——"不撞墙 / 不钻一格"（机身三层任一被挡就抬）。 */
    private static final double VEH_AVOID_LIFT = 6.0;
    /** 前方探测距离（格）：机身高度起连续三层都算。 */
    private static final int VEH_PROBE_LEN = 8;

    /**
     * 这一拍该给期望高度加多少"避险抬升"（格）；不需要 → 0。
     *
     * <p>两条判据（与扫帚/滑翔那一套"移动电路"同口径）：
     * <ol>
     *   <li><b>不贴地</b>：从机身往下找地面，净空 &lt; {@link #VEH_MIN_CLEAR} 就抬到净空线
     *       （避免擦地/蹭树冠，也给"投弹安全高度/接敌高度"之外再加一道最低保险）；</li>
     *   <li><b>不撞墙 / 不钻一格</b>：沿**当前水平行进方向**（慢到看不出方向时用机头）探
     *       {@link #VEH_PROBE_LEN} 格，机身高度起三层只要有一格非空气 → 抬 {@link #VEH_AVOID_LIFT}
     *       格从顶上过（不横向绕——载具不像扫帚那样灵活，往上让开是唯一稳的走法）。</li>
     * </ol>
     * 只有"真的在动"（水平速度 &gt; 0.03）才算前方障碍：原地悬停在墙边不该被永久抬升。
     */
    private static double vehicleAvoidLift(Entity mount, double hspeed) {
        try {
            if (mount == null || !(mount.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl)) {
                return 0.0;
            }
            double lift = 0.0;
            // ① 不贴地
            int gy = surfaceY(sl, net.minecraft.util.Mth.m_14107_(mount.m_20185_()),
                    net.minecraft.util.Mth.m_14107_(mount.m_20186_()) - 1,
                    net.minecraft.util.Mth.m_14107_(mount.m_20189_()));
            if (gy != Integer.MIN_VALUE) {
                double clear = mount.m_20186_() - (gy + 1.0);
                if (clear < VEH_MIN_CLEAR) {
                    lift = Math.max(lift, Math.min(VEH_MIN_CLEAR - clear, VEH_AVOID_LIFT));
                }
            }
            // ② 不撞墙 / 不钻一格（只在真在动时判）
            if (hspeed > 0.03) {
                Vec3 dm = mount.m_20184_();
                double len = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
                double hx, hz;
                if (len > 1.0E-4) {
                    hx = dm.f_82479_ / len;
                    hz = dm.f_82481_ / len;
                } else {
                    float yaw = mount.m_146908_();
                    hx = -Math.sin(Math.toRadians(yaw));
                    hz = Math.cos(Math.toRadians(yaw));
                }
                int by = net.minecraft.util.Mth.m_14107_(mount.m_20186_());
                for (int d = 1; d <= VEH_PROBE_LEN; d++) {
                    int bx = net.minecraft.util.Mth.m_14107_(mount.m_20185_() + hx * d);
                    int bz = net.minecraft.util.Mth.m_14107_(mount.m_20189_() + hz * d);
                    boolean blocked = false;
                    for (int up = 0; up <= 2; up++) {
                        if (!sl.m_8055_(new net.minecraft.core.BlockPos(bx, by + up, bz)).m_60795_()) {
                            blocked = true;
                            break;
                        }
                    }
                    if (blocked) {
                        lift = Math.max(lift, VEH_AVOID_LIFT);
                        break;
                    }
                }
            }
            return lift;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 避险抬升的留痕（5 秒/车，日志搜「载具避险」）。 */
    private static void logAvoid(Entity mount, double lift) {
        try {
            long now = System.currentTimeMillis();
            java.util.UUID id = mount.m_20148_();
            Long last = AVOID_AT.get(id);
            if (last != null && now - last < 5000L) {
                return;
            }
            if (AVOID_AT.size() > 256) {
                AVOID_AT.clear();
            }
            AVOID_AT.put(id, now);
            com.maidsmart.tool.PromaidLog.log("载具避险", describeKind(mount)
                    + " 贴地/前方有方块 → 期望高度抬升 " + fmt2(lift) + " 格越过");
        } catch (Throwable ignored) {
        }
    }

    /** 载具避险留痕节流表（车 → 上次毫秒）。 */
    private static final java.util.Map<java.util.UUID, Long> AVOID_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /* ---------- 实测七百四十三：飞艇（AIRSHIP）/ 汤姆6（TOM6）的驱动常量 ---------- */

    /** 飞艇高度 PD：每 1 格预测落点给多少 liftSpeed（引擎 clamp 域 ±0.25）。 */
    private static final double AIRSHIP_LIFT_PER_BLOCK = 0.04;
    /** 汤姆6 推力：每 1 格距离给多少 power（引擎里 power 上限 1，sprint 时 2.2）。 */
    private static final float TOM_POWER_PER_BLOCK = 0.10f;
    /** 汤姆6 推力上限（引擎里非冲刺档 maxPower=1）。 */
    private static final float TOM_POWER_MAX = 1.0f;
    /** 汤姆6 俯仰：每 1 格高差给多少度（引擎 clamp 是 ±120 空中）。 */
    private static final float TOM_PITCH_PER_BLOCK = 3.0f;
    /** 汤姆6 俯仰上限（度）：别一上来就垂直扎。 */
    private static final float TOM_PITCH_MAX = 30.0f;
    /** 汤姆6 起飞抬头角（度，负 = 抬头）。贴地时强制给这个角，保证能起来。 */
    private static final float TOM_TAKEOFF_PITCH = 15.0f;

    /** 固定翼日志用的推力读数（拿不到 → "?"）。 */
    private static String powerText(Entity mount) {
        try {
            if (mGetPower != null) {
                Object p = mGetPower.invoke(mount);
                if (p instanceof Number n) {
                    return fmt2(n.doubleValue());
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 【实测七百八十】固定翼日志用的滚转读数（度；拿不到 → "?"）——转弯半径就看它。 */
    private static String rollText(Entity mount) {
        try {
            if (mGetRoll != null) {
                Object r = mGetRoll.invoke(mount);
                if (r instanceof Number n) {
                    return String.valueOf(Math.round(n.doubleValue()));
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * 【实测七百四十五·点3】固定翼起飞助跑的抬头角（度，负 = 机头朝上）。
     *
     * <p>地面推力是 {@code viewVector × 0.047 × power × speedRate}（aircraftEngine:879），不抬头就
     * 没有爬升分量；而引擎自己的俯仰**只在空中才写**（:801），地面上写 mouseY 是空操作，所以贴地
     * 这一段由我们直接写 XRot。
     *
     * <p>【实测七百七十六·点2】20° → 25°（玩家原话：「是不是往上抬的角度太低了呢？」）。
     * 实机日志复核：光有角度不够——旧版只在贴地那一拍写一次角度/补一次竖直速度，离地就撒手，
     * 于是她"跳一下又贴回去"。这一版配套加了爬升窗口（见 {@link #aircraftClimbAssist}），
     * 角度仍留在引擎自己的 {@code ClampPitch}（AC-130H 为 40°）以内，25° 不会触发它的限位。
     */
    private static final float AIRCRAFT_TAKEOFF_PITCH = 25.0f;

    /**
     * 【实测七百四十五·点3】固定翼起飞助跑每拍补的竖直速度（格/拍）。
     *
     * <p>地面摩擦 f≈0.497、重力 0.06 ⇒ 稳态 {@code v ≈ 0.128 格/拍}（≈2.5 格/秒），足以顶掉
     * {@code onGround()}、又不至于一飞冲天。它只在**贴地**时补，离地后由
     * {@link #aircraftClimbAssist}（起飞那一口气）与 {@link #aircraftHoldAltitude}（巡航高度）接力。
     */
    private static final double AIRCRAFT_TAKEOFF_LIFT = 0.25;

    /* ---------- 【实测七百七十六·点2】固定翼"真的飞起来"：爬升窗口 + 垂直自驾 + 补能 ---------- */

    /** 爬升窗口（UUID → 到期 gameTime）：窗口内我们接管竖直速度，爬到/超时交还引擎。 */
    private static final java.util.Map<java.util.UUID, Long> AIRCRAFT_CLIMB =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 爬升窗口的滑动超时（拍）：15 秒还爬不到位就不再硬接管（避免与引擎无限对拉）。 */
    private static final long AIRCRAFT_CLIMB_WINDOW_TICKS = 300L;
    /** 目标高差缩到这么多格以内就算"爬到了" → 收窗，交还引擎自己的 loiter/PD。 */
    private static final double AIRCRAFT_CLIMB_STOP = 2.0;
    /** 爬升窗口内每拍的最大爬升率（格/拍 ≈ 4.4 格/秒）。 */
    private static final double AIRCRAFT_VY_MAX = 0.22;
    /** 竖直速度每拍最多拉多少（格/拍）：必须 ≥ 重力量级（0.06），否则顶不住。 */
    private static final double AIRCRAFT_VY_STEP = 0.06;
    /** 补能阈值：SWB 固定翼 loiter 闸 {@code getEnergy() > 1024}（baseTick 实证）里的那个 1024。 */
    private static final int AIRCRAFT_ENERGY_FLOOR = 1024;

    /* ---------- 【实测七百七十五·点3】固定翼定制起飞：验平地 → 加速 → 抬机头 ---------- */

    /** 助跑带长度（格，从机头正前方扫出去）。 */
    private static final int AIRCRAFT_RUNWAY_LEN = 28;
    /** 助跑带半宽（格，左右各扫这么多列）。 */
    private static final int AIRCRAFT_RUNWAY_HALF = 4;
    /** 助跑带允许的最大落差（格）——玩家原话「允许接受凹凸，但是落差不能太大」。 */
    private static final double AIRCRAFT_RUNWAY_MAX_RELIEF = 6.0;
    /**
     * 起飞离地所需的最小水平速度（格/拍）——"加速到一定程度"的"一定程度"。
     *
     * <p>【实测七百七十八·点2】原值 0.30 是**远远不够**的（玩家原话：「我怀疑它起飞不了，
     * 还有一个原因就是它的速度不够」）。反编译实证（{@code aircraftEngine:877}）升力项为
     * {@code upVec × (1-|n·up|) × speed × (0.008+liftOffset) × LiftSpeed × 襟翼项(≈4)}，
     * 即**升力 ∝ 当前速度**；而每拍重力约 0.06（{@code baseTick} 末尾）。取 AC-130H
     * （LiftSpeed=1.0）代入：速度 0.30 时升力只有 ≈0.01/拍、0.70 时 ≈0.02/拍，
     * **都远小于 0.06**——所以旧版"一到 0.30 就抬机头"等于刚离地就往下掉，
     * 实机日志「固定翼滑跑 水平速度=0.00/0.30」之后立刻「盘旋档 高差=1」正是这个形状。
     * 要升力 ≈ 重力需要 speed ≈ 0.06/(0.032×LiftSpeed) ≈ 1.9（LiftSpeed=1.0）；
     * 这里取 1.20 作为"可以抬机头"的门槛，剩下的差额由
     * {@link #AIRCRAFT_GROUND_MAX_POWER}（地面满推力 3.0）+ 爬升窗口的竖直自驾一起顶住。
     */
    private static final double AIRCRAFT_TAKEOFF_SPEED = 1.20;

    /**
     * 【实测七百七十八·点2】固定翼的水平速度上限（格/拍）。
     *
     * <p>旧版这一档用的是 {@code FLIGHT_HMAX × FLIGHT_SPEED_CAP_SLACK} = 0.75×1.25 = 0.9375
     * ——那是**扫帚/直升机**那套包络。对固定翼是致命的：升力 ∝ 速度，
     * 0.9375 的升力（≈0.03/拍）仍然 < 重力 0.06/拍，等于**上限本身就把飞机锁在"飞不起来"的区间**。
     * 引擎自己的空气阻力（{@code f = 0.96 - 0.0017×R×v²}）会把速度自然收敛，
     * 这里只留一个"防超速"的粗上限（2.6 格/拍 ≈ 52 格/秒）。
     */
    private static final double AIRCRAFT_HMAX = 2.60;

    /**
     * 【实测七百七十八·点2】固定翼在地面的最大推力。
     *
     * <p>反编译实证 {@code aircraftEngine:753}：{@code sprintInputDown() || onGround()} 时
     * {@code maxPower = 3}（空中不按冲刺则封顶 1）。助跑阶段直接给它顶到 3，
     * 省掉引擎那条 {@code +0.006×powerAdd}/拍 的缓慢爬升（原地等 15~22 秒才到 3）。
     */
    private static final float AIRCRAFT_GROUND_MAX_POWER = 3.0f;

    /**
     * 【实测七百七十八·点2 / 七百七十九·点2 / 七百八十】固定翼巡航时的最小推力。
     *
     * <p>为什么必须有下限（778 实证）：引擎自己的盘旋会把 {@code power} 收敛到 {@code 0.5~0.9}
     * （反编译 {@code aircraftLoiter:1337}），对应的速度只有 ~0.6 格/拍 → 升力 ≈0.02/拍
     * < 重力 0.06/拍——**盘旋就等于"慢慢往下沉"**。所以每拍给一个推力下限
     * （引擎空中每拍只把多余推力衰减 0.012，压不过我们）。
     *
     * <p>【780】值从 2.40 收回 1.20：779 的 2.40 是按"用推力把速度顶到 2.60"设计的，
     * 而 780 已经把巡航速度**改由速度环直接压到 0.80**（{@link #aircraftGovernSpeed}），
     * 推力再顶到 2.60 只会被速度环每拍削掉——那是白烧油，也让"急刹感"来自两处对抗。
     * 1.20 的稳态速度在各家阻力系数下都仍是 1.2 格/拍以上（{@code v³ ≈ 16.6×P/R}），
     * 依然足够让速度环"只压不拉"，同时把多余推力的量级降到 1/4。
     *
     * <p>空中要让 {@code power} 停在 1 以上，必须**带冲刺位**（引擎
     * {@code maxPower = sprint||onGround ? 3 : (power>1 ? power−0.012 : 1)}，反编译实证），
     * 所以巡航档的输入位里始终带着 {@code 0x100}。
     */
    private static final float AIRCRAFT_MIN_CRUISE_POWER = 1.20f;
    /** 助跑带扫描的缓存拍数（2 秒；地面不会两秒一变，且扫描本身是几十次方块查询）。 */
    private static final long AIRCRAFT_RUNWAY_CACHE_TICKS = 40L;

    /** 每台固定翼的助跑带缓存：UUID → {方向yaw, 落差, 到期gameTime, 合格1/不合格0}。 */
    private static final java.util.Map<java.util.UUID, double[]> RUNWAY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百七十五·点3】给这台固定翼找一条"落差足够小"的助跑带。
     *
     * <p>八向各扫一条 {@link #AIRCRAFT_RUNWAY_LEN} 格长 × {@link #AIRCRAFT_RUNWAY_HALF}
     * 格半宽的带子，取每条带内地面高度的 max−min 作为落差；取落差最小的一条。
     * 最小落差超过 {@link #AIRCRAFT_RUNWAY_MAX_RELIEF}（或有整列悬空）→ 返回 {@code null}
     * （调用方报"环境不允许起飞"）。
     *
     * @return {@code {方向yaw, 落差}}；一条都不合格 → null
     */
    private static double[] aircraftRunway(Entity mount) {
        try {
            if (mount == null || !(mount.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl)) {
                return null;
            }
            java.util.UUID id = mount.m_20148_();
            long now = sl.m_46467_();
            double[] c = RUNWAY_CACHE.get(id);
            if (c != null && now <= c[2]) {
                return c[3] > 0.5 ? new double[]{c[0], c[1]} : null;
            }
            if (RUNWAY_CACHE.size() > 256) {
                RUNWAY_CACHE.clear();
            }
            int my = net.minecraft.util.Mth.m_14107_(mount.m_20186_());
            double bestRelief = Double.MAX_VALUE;
            double bestYaw = 0.0;
            for (int i = 0; i < 8; i++) {
                double yaw = i * 45.0;
                double hx = -Math.sin(Math.toRadians(yaw));
                double hz = Math.cos(Math.toRadians(yaw));
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                int voids = 0;
                for (int d = 2; d <= AIRCRAFT_RUNWAY_LEN; d += 2) {
                    for (int w = -AIRCRAFT_RUNWAY_HALF; w <= AIRCRAFT_RUNWAY_HALF; w++) {
                        int bx = net.minecraft.util.Mth.m_14107_(mount.m_20185_() + hx * d - hz * w);
                        int bz = net.minecraft.util.Mth.m_14107_(mount.m_20189_() + hz * d + hx * w);
                        int gy = surfaceY(sl, bx, my, bz);
                        if (gy == Integer.MIN_VALUE) {
                            voids++;
                            continue;
                        }
                        min = Math.min(min, gy);
                        max = Math.max(max, gy);
                    }
                }
                if (min == Double.MAX_VALUE) {
                    continue; // 这条带子全悬空
                }
                double relief = (max - min) + voids * 0.5; // 少量悬空列折算成落差惩罚
                if (relief < bestRelief) {
                    bestRelief = relief;
                    bestYaw = yaw;
                }
            }
            boolean ok = bestRelief != Double.MAX_VALUE && bestRelief <= AIRCRAFT_RUNWAY_MAX_RELIEF;
            if (!ok) {
                bestRelief = bestRelief == Double.MAX_VALUE ? 99.0 : bestRelief;
            }
            RUNWAY_CACHE.put(id, new double[]{bestYaw, bestRelief, now + AIRCRAFT_RUNWAY_CACHE_TICKS,
                    ok ? 1.0 : 0.0});
            return ok ? new double[]{bestYaw, bestRelief} : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 某一列的地面高度（从 {@code fromY+4} 往下最多 40 格找第一格非空气）；找不到（悬空/太高）→ {@code Integer.MIN_VALUE}。 */
    private static int surfaceY(net.minecraft.server.level.ServerLevel sl, int x, int fromY, int z) {
        try {
            int floor = Math.max(sl.m_141937_() + 1, fromY + 4 - 40); // 有界向下扫，别把整列世界翻一遍
            for (int y = fromY + 4; y > floor; y--) {
                if (!sl.m_8055_(new net.minecraft.core.BlockPos(x, y, z)).m_60795_()) {
                    return y;
                }
            }
        } catch (Throwable ignored) {
        }
        return Integer.MIN_VALUE;
    }

    /** 起飞被环境拦下时的留痕 + 给主人的一句话（5 秒/车，日志搜「环境不允许起飞」）。 */
    private static void refuseTakeoff(Entity mount, Entity maid) {
        try {
            long now = System.currentTimeMillis();
            String key = mount.m_20148_().toString();
            Long last = RUNWAY_REFUSE_AT.get(key);
            if (last != null && now - last < 5000L) {
                return;
            }
            if (RUNWAY_REFUSE_AT.size() > 256) {
                RUNWAY_REFUSE_AT.clear();
            }
            RUNWAY_REFUSE_AT.put(key, now);
            com.maidsmart.tool.PromaidLog.log("环境不允许起飞", describeKind(mount)
                    + " 周围没有落差足够小的助跑地（需要一条 28×9 格、落差 ≤ "
                    + (long) AIRCRAFT_RUNWAY_MAX_RELIEF + " 格的平地）→ 拒绝起飞");
            try {
                if (maid instanceof EntityMaid em
                        && em.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
                    p.m_213846_(net.minecraft.network.chat.Component.m_237113_(
                            "[女仆助手] 环境不允许起飞：周围没有足够平整的助跑道（落差要 ≤ "
                                    + (long) AIRCRAFT_RUNWAY_MAX_RELIEF + " 格）"));
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 「环境不允许起飞」留痕节流表（车 → 上次毫秒）。 */
    private static final java.util.Map<String, Long> RUNWAY_REFUSE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /* ---------- 【实测七百七十九·点2】固定翼巡航：我们自己的航向 / 速度 / 高度环 ----------
     *
     * 旧版（743~778）这里是一个 {@code tryAircraftLoiter}——把"去某点"翻译成引擎自己的
     * {@code aircraftLoiter}（圆心 + 半径 + 高度）。779 起**整段删掉**：它要的半径只有 8~60 格，
     * 比这架飞机气动上转得过来的半径还小，于是它的径向误差项每拍把她往外推、永远收不拢——
     * 实机日志就是"越飞越远、只会在天上飞"。改由下面这几个方法自己管。 */

    /* ---------- 【实测七百八十】转弯半径 / 巡航速度 / 地形净空（三个数字定死一架固定翼） ----------
     *
     * 玩家原话（即规格）：「目前问题就是女仆飞行的半径太大，然后高度又太低。导致经常坠机。」
     *
     * <h2>半径：r = v / ω，两个量都在引擎手里，所以必须一起动手</h2>
     * 反编译 {@code aircraftEngine:793-800} 是**唯一**的转向公式：
     * <pre>
     *   rotSpeed = 0.3 + 3.2 * |calculateY(roll)|       // calculateY(x) = x/90（VectorTool 实证）
     *   addY     = clamp(0.24 * speed * mouseX, ±rotSpeed)
     *   yRot    += yawSpeed * addY
     * </pre>
     * AC-130H 的 {@code ClampRoll=45}、{@code YawSpeed=0.9} ⇒ 满滚转时
     * {@code rotSpeed = 0.3+3.2*0.5 = 1.9}，即角速度 1.71°/拍 = 0.0298 弧度/拍。
     * 于是**巡航半径 r ≈ v / 0.0298**：
     * <ul>
     *   <li>779 那版巡航速度 2.60 ⇒ r ≈ 87 格（实机日志 {@code 距目标 50→288} 一次通场就是它）；</li>
     *   <li>本版 0.80 ⇒ r ≈ 27 格。</li>
     * </ul>
     * 注意"r 与速度成正比"只在舵量被 rotSpeed 卡住时才成立；速度再往下掉时
     * {@code addY ∝ speed} 会一起变小，半径反而收不动——所以**必须同时**把滚转顶到 45°
     * （{@link #aircraftBank}）并把速度压到 {@link #AIRCRAFT_CRUISE_SPEED}
     * （{@link #aircraftGovernSpeed}），缺一个都白搭。
     *
     * <h2>高度：抬目标点解决不了，得"不许低于地形"</h2>
     * 实机日志（05:12~05:15）里三次「坠机了」的共同形状是：{@code 竖直速度=-0.18~-0.27}、
     * {@code 高差=1~-16}——她跟的胡萝卜是**主人/敌人高度 + 3~15 格**，与脚下的山丘毫无关系，
     * 山一抬头她就一头撞进去。撞地伤害（反编译 {@code VehicleEntity.move:6121}）是
     * {@code 18×(speed−0.2)²}：2.60 时 103 点/次、0.80 时 6.5 点/次——**"经常坠机"就是它**。
     * 本版给固定翼加一条**地形净空线**（脚下的地 + 前瞻路径上最高的地，各加
     * {@link #AIRCRAFT_TERRAIN_CLEAR} 格）与胡萝卜高度取大者；速度降下来后再擦一下也只剩零头伤害。
     */

    /** 固定翼航向通道写多少：引擎里 {@code addY = clamp(0.24*speed*mouseX, ±rotSpeed)}——
     *  要拿满舵量就得 {@code 0.24*v*mouseX ≥ rotSpeed}；0.80 巡航 + 满滚转时
     *  {@code 0.24*0.8*12 = 2.30 > 1.9} 还有余量，所以取 12（779 的 8 只有 1.54，拿不满）。 */
    private static final float AIRCRAFT_YAW_CMD = 12.0f;

    /** 转弯时"强制滚转"的目标角（度）——舵量上限只由它决定，45° 即满舵 1.9。 */
    private static final double AIRCRAFT_BANK_MAX = 45.0;

    /** 强制滚转的每拍变化上限（度）——别一拍横过来；从 0 滚到 45 约 1.2 秒。 */
    private static final double AIRCRAFT_BANK_RATE = 2.0;

    /** 巡航水平速度（格/拍 ≈ 16 格/秒）——转弯半径 r ≈ v/0.0298 ⇒ 0.80 → 27 格。 */
    private static final double AIRCRAFT_CRUISE_SPEED = 0.80;

    /** 巡航减速的每拍上限（格/拍）：从爬升/起飞档的 2.6 拉回 0.8 要 ~45 拍，别一脚急刹
     *  （急刹那一拍升力跟着掉，高度环还得额外补）。 */
    private static final double AIRCRAFT_SPEED_DECEL = 0.04;

    /** 固定翼最小离地净空（格）——机身底面到地面至少留这么多。 */
    private static final double AIRCRAFT_TERRAIN_CLEAR = 14.0;

    /** 前瞻多少拍的路（按当前水平速度换算成格）：0.80 格/拍 × 100 拍 = 80 格。 */
    private static final int AIRCRAFT_LOOKAHEAD_TICKS = 100;
    /** 前瞻距离的上下限（格）——太快/太慢时都不至于扫得太离谱。 */
    private static final double AIRCRAFT_LOOKAHEAD_MIN = 24.0;
    private static final double AIRCRAFT_LOOKAHEAD_MAX = 84.0;
    /** 前瞻扫描时"往上多扫"的格数：用来发现**比机身还高**的山坡（见 {@link #columnFloor}）。 */
    private static final int AIRCRAFT_SCAN_UP = 20;

    /** 偏航误差超过这个角才滚转（{@code aircraftEngine} 里左右位会累积 {@code deltaRot} → 滚转
     *  → {@code rotSpeed = 0.3 + 3.2*|roll|/90} 变大 → 转得更快）。 */
    private static final float AIRCRAFT_BANK_ERR = 25.0f;

    /** 高度环：每 1 格高差给多少目标竖直速度（格/拍）。 */
    private static final double AIRCRAFT_VY_PER_BLOCK = 0.08;

    /** 高度环的目标竖直速度上限（格/拍）——巡航不要大起大落。 */
    private static final double AIRCRAFT_VY_CRUISE_MAX = 0.18;

    /** 地形避险时的爬升率上限（格/拍）：比巡航大一截，山抬头时来得及抬。
     *  0.30 格/拍 @ 0.80 前进 ⇒ 约 20° 爬升角。 */
    private static final double AIRCRAFT_VY_AVOID_MAX = 0.30;

    /** 高度环死区（格）：巡航速度下升力只有重力的四成左右（{@code 0.8×0.008×3.8 ≈ 0.024 < 0.06}），
     *  所以竖直通道**每拍都得轻轻托着**——死区从 779 的 1.5 收到 0.4，不然她会在死区里慢慢沉。 */
    private static final double AIRCRAFT_ALT_DEADZONE = 0.4;

    /**
     * 【实测七百七十九·点2】把引擎自己的固定翼 loiter 关掉。
     *
     * <p>779 起固定翼的航向/速度/高度由 {@link #aircraftCruise} 自己管；loiter 若还开着，
     * 它每拍会往 {@code mouseMoveSpeedX/Y} 与 {@code power} 上写它自己那一套，变成两套控制器
     * 打架（而且它要的盘旋半径这架飞机气动上转不过来）。反射拿不到 → 什么都不做
     * （那种情况下 loiter 本来也不会自己开，一个字节不变）。
     */
    private static void aircraftLoiterOff(Entity mount) {
        if (mSetLoiterActive == null) {
            return;
        }
        try {
            mSetLoiterActive.invoke(mount, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十九·点2 / 七百八十】固定翼巡航：四层各管一件事（详见调用点
     * {@code driveFlight} 的注释）。
     *
     * @param dy      高差（目标高度 − 机身 y，正 = 目标更高）。780 起这里传的是
     *                {@link #aircraftSafeTargetY} 之后的**安全高度**——地形净空线与胡萝卜高度取大者。
     * @param err     到 carrot 的偏航误差（{@code wrapDegrees(目标方位 − 机头)}，正 = 该右转）
     * @param terrain 这一拍的高度是不是**被地形净空线顶起来的**（是 → 允许更陡的爬升率）
     */
    private static void aircraftCruise(Entity mount, String eng, double dy, float err, boolean terrain) {
        // 引擎自己的 loiter 每拍会写 mouse/power，与下面四层打架（而且它要的半径它转不过来）
        aircraftLoiterOff(mount);
        // ① 航向：鼠标 X 通道 + 左右位（左右位仍留着：它累积 deltaRot，是引擎自己的滚转来源，
        //    也是 aircraftBank 拿不到反射时的退路）
        mSetMouseX(mount, clamp(err, -AIRCRAFT_YAW_CMD, AIRCRAFT_YAW_CMD));
        // 俯仰通道归零 → 交给引擎自己的"机头自动整平"（{@code aircraftEngine:818}：
        // {@code |xRot|<20 && |mouseY|<0.001} 时按竖直速度把机头慢慢摆平）。高度不靠俯仰环，见③。
        mSetMouseY(mount, 0.0f);
        short bits = (short) (0x004 | 0x100);   // 前进 + 冲刺（空中 power>1 必须靠冲刺位）
        if (Math.abs(err) > AIRCRAFT_BANK_ERR) {
            bits |= (err > 0) ? (short) 0x002 : (short) 0x001;   // 借左右位滚转
        }
        try {
            mProcessInput.invoke(mount, bits);
        } catch (Throwable ignored) {
        }
        aircraftPowerAtLeast(mount, AIRCRAFT_MIN_CRUISE_POWER);
        // ② 滚转：直接写滚转角（舵量上限的唯一来源，见 780 那段的推导）
        aircraftBank(mount, err);
        // ③ 高度：竖直自驾（直接写 deltaMovement.y）——地形顶起来时允许更陡
        aircraftHoldAltitude(mount, dy, terrain ? AIRCRAFT_VY_AVOID_MAX : AIRCRAFT_VY_CRUISE_MAX);
        // ④ 水平速度：巡航速度环（转弯半径 r ≈ v/0.0298 里的那个 v）
        aircraftGovernSpeed(mount, AIRCRAFT_CRUISE_SPEED);
    }

    /**
     * 【实测七百八十】固定翼"强制滚转"：把滚转角按 {@link #AIRCRAFT_BANK_MAX} 顶住。
     *
     * <h2>为什么非做不可</h2>
     * 转向能力**完全**由滚转角决定（反编译 {@code aircraftEngine:793}）：
     * {@code rotSpeed = 0.3 + 3.2*|roll|/90}。不滚的时候 {@code rotSpeed} 只有 0.3 —— 角速度
     * 0.27°/拍，半径是**几百格**（实机日志里"越飞越远"就是这个形状）；滚满 45° 才有 1.9。
     * 光靠左右位让引擎自己滚要几十拍才到位（实机日志反推滚转角长期只在 20~30°），
     * 而转弯半径按 {@code r = v/(0.9×rotSpeed×π/180)} 算，25° 时是 48 格、45° 时 27 格。
     *
     * <h2>方向怎么定（不猜）</h2>
     * 引擎自己的约定是"右位 → {@code deltaRot} 变负 → 滚转角变正"，与它自己的偏航同源。
     * 所以这里**保留引擎当前滚转角的正负号**（{@code |roll|>5} 时），只在它已经选定的方向上
     * 把幅度补到 45°；只有在引擎还没滚起来（≈0）时才用偏航误差的符号开局。
     * 这样即使我们的符号约定与引擎相反，也不会把升力的水平分量推到圈外。
     *
     * <p>反射拿不到 → 什么都不做（退路就是上面那条"借左右位"，只是半径大些）。
     */
    private static void aircraftBank(Entity mount, float err) {
        if (mSetRoll == null || mGetRoll == null) {
            return;
        }
        try {
            float cur = ((Number) mGetRoll.invoke(mount)).floatValue();
            float want;
            if (Math.abs(err) > AIRCRAFT_BANK_ERR) {
                float sign = Math.abs(cur) > 5.0f ? Math.signum(cur)
                        : (err > 0.0f ? 1.0f : -1.0f);
                want = sign * (float) AIRCRAFT_BANK_MAX;
            } else {
                want = 0.0f;   // 不用转弯了 → 回正（引擎自己也在回正，同向不打架）
            }
            float step = (float) clamp(want - cur, -AIRCRAFT_BANK_RATE, AIRCRAFT_BANK_RATE);
            if (Math.abs(step) > 1.0E-4f) {
                mSetRoll.invoke(mount, cur + step);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十九·点2 / 七百八十】固定翼竖直自驾：把竖直速度拉向"按高差算的目标值"。
     *
     * <p>与 {@link #aircraftClimbAssist}（776 起飞爬升窗口用）同一套手法，两处差别只有两条：
     * 这里是**双向**的（下降也要接管，{@code want} 允许为负），且带一个
     * {@link #AIRCRAFT_ALT_DEADZONE} 死区。
     *
     * <p>【780】死区为什么从 1.5 收到 0.4、上限为什么变成参数：巡航速度降到 0.80 之后，
     * 升力（≈0.024/拍）只有重力（0.06/拍）的四成——**她一直在缓慢下沉**，全靠这个竖直环每拍托着。
     * 死区留在 1.5 的话，她会在死区里以 0.036 格/拍下沉、反复进出，看着就是"忽忽悠悠往下掉"；
     * 收到 0.4 才能把稳态误差压到 0.4 格以内。{@code maxVy} 由调用方给：巡航 0.18、
     * 被地形净空线顶起来时 0.30（见 {@link #AIRCRAFT_VY_AVOID_MAX}）。
     */
    private static void aircraftHoldAltitude(Entity mount, double dy, double maxVy) {
        try {
            if (Math.abs(dy) <= AIRCRAFT_ALT_DEADZONE) {
                return;
            }
            double want = clamp(dy * AIRCRAFT_VY_PER_BLOCK, -maxVy, maxVy);
            Vec3 dm = mount.m_20184_();
            double step = clamp(want - dm.f_82480_, -AIRCRAFT_VY_STEP, AIRCRAFT_VY_STEP);
            mount.m_20256_(new Vec3(dm.f_82479_, clamp(dm.f_82480_ + step, -0.30, 0.30), dm.f_82481_));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百八十】固定翼"安全目标高度" = {@code max(胡萝卜高度, 地形净空线)}。
     *
     * <h2>为什么必须这么做（三次「坠机了」的根因）</h2>
     * 胡萝卜的高度来自**实体**（跟随 = 主人 Y + {@code followAlt}、接敌 = 敌人 Y +
     * {@code requiredAbove}），与地形毫无关系。她巡航在 80 格外的山头上方时，胡萝卜可能
     * 指着一个山谷底（高差 -16），竖直环就会一路把她往山里压——实机日志
     * {@code 竖直速度=-0.18~-0.27 高差=-16 → 坠机了} 正是这个形状。
     * 所以目标高度必须先跟"脚下的地 + 前瞻路径上最高的地"取大者。
     *
     * <p>净空 {@link #AIRCRAFT_TERRAIN_CLEAR} 只作用在**高度目标**上（姿态/推力照旧），
     * 所以这是一条与四种引擎共用的"避险抬升"同源、但专门给固定翼的**前瞻版**：
     * {@code vehicleAvoidLift} 只看正前方 8 格（那是给每秒几格的直升机用的），
     * 对 0.80 格/拍（16 格/秒）的固定翼不够，这里按**速度换算的前瞻距离**扫。
     *
     * @param carrotY 胡萝卜（主人/敌人 + 各自高度）的高度
     * @param err     这一拍朝目标的偏航误差（度）——前瞻时"目标那一侧"也要扫，原因见下
     * @return 安全高度（格）；这一带地面远在下方时原样返回 {@code carrotY}
     */
    private static double aircraftSafeTargetY(Entity mount, double carrotY, float err) {
        try {
            double floor = aircraftTerrainFloor(mount, err);
            return floor == Double.NEGATIVE_INFINITY ? carrotY : Math.max(carrotY, floor);
        } catch (Throwable ignored) {
            return carrotY;
        }
    }

    /** 地形净空线（格）：脚下 + 前瞻路径上所有采样列里"最高的地 + CLEAR"；无约束 → 负无穷。 */
    private static double aircraftTerrainFloor(Entity mount, float err) {
        if (!(mount.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return Double.NEGATIVE_INFINITY;
        }
        int from = net.minecraft.util.Mth.m_14107_(mount.m_20186_()) + AIRCRAFT_SCAN_UP;
        // ① 脚下那一列（她现在就在这上面）
        double best = columnFloor(sl, mount.m_20185_(), mount.m_20189_(), from);
        // ② 前瞻：沿"当前行进方向"与"目标方位"各扫 3 列（0.33L / 0.67L / L）
        double len = clamp(horizontalSpeed(mount) * AIRCRAFT_LOOKAHEAD_TICKS,
                AIRCRAFT_LOOKAHEAD_MIN, AIRCRAFT_LOOKAHEAD_MAX);
        double vx = 0.0;
        double vz = 0.0;
        Vec3 dm = mount.m_20184_();
        double h = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
        if (h > 1.0E-3) {
            vx = dm.f_82479_ / h;
            vz = dm.f_82481_ / h;
        } else {
            float yaw = mount.m_146908_();
            vx = -Math.sin(Math.toRadians(yaw));
            vz = Math.cos(Math.toRadians(yaw));
        }
        for (int i = 1; i <= 3; i++) {
            double d = len * i / 3.0;
            best = Math.max(best, columnFloor(sl, mount.m_20185_() + vx * d, mount.m_20189_() + vz * d, from));
            // 目标那一侧也要扫：她正在转弯时，去路是"速度方向"与"目标方向"之间那一片，
            // 只扫速度方向的话，转弯内侧的山头会漏掉。方位 = 行进方向再转 err
            // （MC 约定 yaw 0 = +Z、yaw 增大朝 -X，与这里用的 2D 旋转矩阵同式）。
            double rad = Math.toRadians(err);
            double tx = vx * Math.cos(rad) - vz * Math.sin(rad);
            double tz = vx * Math.sin(rad) + vz * Math.cos(rad);
            best = Math.max(best, columnFloor(sl, mount.m_20185_() + tx * d, mount.m_20189_() + tz * d, from));
        }
        return best;
    }

    /**
     * 某一列的地面净空线：{@code surfaceY} 找到这一列在扫描窗口内的最高非空气方块，返回它 + 1 + CLEAR。
     *
     * <p>扫描窗口是 {@code [from-40+4, from+4]} = {@code [机身+SCAN_UP-36, 机身+SCAN_UP+4]}
     * （见 {@link #surfaceY}）——{@code SCAN_UP}=20 时即 {@code [-16, +24]}：
     * <ul>
     *   <li>窗口内有方块 → 返回它 + 净空（这就是"要抬到多高"）；</li>
     *   <li>窗口内全空气 → 说明这一带的地在 16 格以下，对她没有约束 → 负无穷（不参与 max）。</li>
     * </ul>
     * 比机身高出 20 格以上的山坡会被截断成窗口顶（少报几格）——但那一列**一定是实体**，
     * 于是她仍会拿到一个很大的抬升量、继续爬；等她爬上去窗口也跟着上移，读数自然补齐。
     */
    private static double columnFloor(net.minecraft.server.level.ServerLevel sl,
                                     double x, double z, int from) {
        int g = surfaceY(sl, net.minecraft.util.Mth.m_14107_(x), from, net.minecraft.util.Mth.m_14107_(z));
        return g == Integer.MIN_VALUE ? Double.NEGATIVE_INFINITY : g + 1.0 + AIRCRAFT_TERRAIN_CLEAR;
    }

    /**
     * 【实测七百七十六·点2】固定翼"垂直自驾"：把竖直速度往「按高差算的目标爬升率」上拉。
     *
     * <p><b>为什么需要</b>（反编译 + 实机日志双实证）：引擎自己的 loiter 要**同时**满足
     * {@code !onGround && getEngineStartOver() && getEnergy() > 1024 && !isWreck()}
     * 且车上有乘客、车型是 AIRCRAFT、{@code getLoiterActive()}（baseTick 实证），任何一道没过
     * 它就完全不写俯仰/推力。玩家日志（实测七百七十六）里她的 AC-130H 能"跳"起来（起飞那一拍
     * 补的竖直速度），但随后一直贴地 0.5 格/拍地滑行盘旋、目标高差十几格从不下降——就是 loiter
     * 没跑，而旧版只在贴地那一拍给过一次补偿。这里每拍直接拉 {@code deltaMovement.y}：
     * 目标爬升率 = {@code clamp(高差 × 0.06, 0.06, 0.22)}，每拍最多拉 0.06（≥ 重力量级，才顶得住）。
     *
     * <p><b>为什么不是"把角度抬更高"</b>（玩家原话：「是不是往上抬的角度太低了呢？」）：
     * 抬头角只决定推力/升力分出多少竖直分量，而玩家看到的"飞不起来"的直接量是**爬升率**——
     * 25° 的抬头角 + 每拍 0.2 格的竖直速度才是真的在爬。窗口一到（爬到位/15 秒超时）就撒手，
     * 交还引擎自己的 loiter（它接管后会写俯仰与推力，与我们同向，不打架）。
     */
    private static void aircraftClimbAssist(Entity mount, double dy) {
        try {
            double want = clamp(dy * 0.06, 0.06, AIRCRAFT_VY_MAX);
            net.minecraft.world.phys.Vec3 dm = mount.m_20184_();
            double step = clamp(want - dm.f_82480_, -AIRCRAFT_VY_STEP, AIRCRAFT_VY_STEP);
            mount.m_20256_(new net.minecraft.world.phys.Vec3(dm.f_82479_,
                    clamp(dm.f_82480_ + step, -0.30, 0.30), dm.f_82481_));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十六·点2】固定翼"补能"：见 {@link #mSetEnergy} 的说明。
     *
     * <p>只在电量 ≤ {@link #AIRCRAFT_ENERGY_FLOOR}（= 引擎 loiter 闸那个 1024）且拿得到读写口时
     * 把能量仓填满；其余情况一个字节都不动（有电的车不受影响，没能量仓的车自然跳过）。
     */
    private static void topUpAircraftEnergy(Entity mount) {
        try {
            if (mGetEnergy == null || mSetEnergy == null || mGetMaxEnergy == null) {
                return;
            }
            int e = ((Number) mGetEnergy.invoke(mount)).intValue();
            int max = ((Number) mGetMaxEnergy.invoke(mount)).intValue();
            if (max <= 0 || e > AIRCRAFT_ENERGY_FLOOR) {
                return;
            }
            mSetEnergy.invoke(mount, max);
            logDrive(mount, "固定翼补能：电量 " + e + " → " + max
                    + "（反编译实证：loiter 闸要求 getEnergy() > " + AIRCRAFT_ENERGY_FLOOR
                    + "，而车的能量只能靠车里塞能量物品充、女仆不会充电）");
        } catch (Throwable ignored) {
        }
    }

    /** 固定翼日志用的电量读数（拿不到 → "?"）。 */
    private static String energyText(Entity mount) {
        try {
            if (mGetEnergy == null) {
                return "?";
            }
            return String.valueOf(((Number) mGetEnergy.invoke(mount)).intValue());
        } catch (Throwable ignored) {
            return "?";
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
    /** **固定翼**的前倾/抬头角上限（相对引擎那一项的钳制区间，取保守值）。 */
    private static final float FLIGHT_PITCH_MAX = 0.6f;
    /** 每 1 格高度差给多少俯仰输入（固定翼收敛用）。 */
    private static final float FLIGHT_PITCH_PER_BLOCK = 0.04f;

    /* ---------- 【实测七百三十】直升机：速度环 + 玩家量级俯仰 ---------- */

    /**
     * 【实测七百三十·点1】直升机俯仰命令上限（写进鼠标 Y 通道的值）。
     *
     * <p>反编译 {@code helicopterEngine:611}：{@code xRot += (onGround?0:1.5) * pitchSpeed *
     * getMouseMoveSpeedY() * propeller}。mi_28 的 {@code pitchSpeed=0.75}、{@code propeller≈power}
     * 悬停时只有 {@code ≈0.1} → 每拍俯仰变化 = {@code 1.125 × mouseY × 0.1}。旧版上限 0.6
     * 只做出 {@code 0.067°/拍 ≈ 1.3°/秒}——**机头根本压不下去**。
     *
     * <p>而玩家推鼠标时这个通道是**个位数**量级（客户端 {@code ClientMouseHandler}：{@code speedY =
     * mouseSensitivity(0.35) × 鼠标位移}，再 lerp 进 {@code lerpSpeedY}），所以取 8 与玩家同档。
     */
    private static final float FLIGHT_HELI_PITCH_MAX = 8.0f;
    /**
     * 【实测七百三十·点1】期望水平速度上限（格/拍）——**照搬扫帚那套包络 {@code 0.75}**
     * （{@code MaidBroomDrive.MAX_H_SPEED}，来源是 TLM 给玩家驾驶写的 {@code PlayerBroomControl}），
     * 一分不加。玩家点名"扫帚的移动速度只能照搬原版"。
     */
    private static final double FLIGHT_HMAX = 0.75;
    /** 每 1 格剩余水平距离给多少期望速度（格/拍）：7.5 格差就吃满 {@link #FLIGHT_HMAX}。 */
    private static final double FLIGHT_SPEED_PER_BLOCK = 0.10;
    /**
     * 【实测七百三十·点1】**串级**控制的外环→内环换算：速度误差（格/拍）→ 目标俯仰角（度）。
     *
     * <p>为什么必须串级（数值仿真实证）：先前那版把俯仰写成 {@code k*(期望速度−实际速度) − xRot*回中}，
     * 稳态时 {@code mouseY→0} 意味着 {@code xRot = k*(误差)/回中系数}——**机头角度被误差项与回中项
     * 卡死在 ~13°**，水平速度只能到 0.21 格/拍（不到扫帚的三分之一）。改成"先算目标姿态角、
     * 再用内环把姿态打过去"就没有这个稳态下垂：角度由外环直接指定、内环只负责追上它。
     */
    private static final float FLIGHT_TILT_PER_SPEED = 50.0f;
    /**
     * 巡航最大前倾角（度）。反编译实证水平推力 = {@code 升力 × sin(俯仰角)}
     * （{@code helicopterEngine:686} 的 {@code getUpVec().scale(prop*lift*0.66)}，机头一低就分出
     * 水平分量），而竖直阻尼 {@code f≈0.935} → 稳态 {@code 0.065·v ≈ 0.060·sinθ} → 35° 约对应
     * {@code 0.5 格/拍}（10 格/秒，与扫帚同量级）。
     */
    private static final float FLIGHT_TILT_MAX = 35.0f;
    /** 内环：姿态角误差（度）→ 写进鼠标 Y 通道的量。1° 误差给 0.5，钳到 {@link #FLIGHT_HELI_PITCH_MAX}。 */
    private static final float FLIGHT_TILT_GAIN = 0.5f;
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

    /* ==================== 实测七百三十一·暴力后门 ==================== */

    /**
     * 【实测七百三十一】强制刹车：**直接把水平速度抹掉**，不看引擎脸色。
     *
     * <h2>为什么必须走这个后门</h2>
     * 玩家原话：「现在女仆完全就停不下来了。1 点刹车的能力都没有……比如悬停完之后就强制给它
     * 水平方向的速度停下来。」实机日志（{@code promaid.log}）实证：到点后 {@code 期望=0.00}
     * 而 {@code 水平速度=0.83}，下一拍又 {@code 1.82}——发动机的惯性远大于我们那点俯仰权限，
     * 光靠"松油门 + 悬停开关"收不住。
     *
     * <h2>为什么直接写 {@code deltaMovement} 是安全的</h2>
     * 反编译 {@code VehicleEntity.m_20256_}（:5474-5487）只在**加速**（新速度比旧速度大
     * 且加速度 &gt; 8）时限幅，**减速是直通**的——所以把水平分量按比例缩小、或直接置零，
     * 引擎不会"又给你乘回去"。竖直分量**保留**（不然空中就是自由落体）。
     *
     * @param retain 保留系数（0 = 当场停死；0.2 = 一拍掉八成，用来做软刹）
     */
    static void killHorizontal(Entity mount, double retain) {
        try {
            if (mount == null) {
                return;
            }
            Vec3 dm = mount.m_20184_();
            mount.m_20256_(new net.minecraft.world.phys.Vec3(dm.f_82479_ * retain, dm.f_82480_, dm.f_82481_ * retain));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百四十三·点1】把**水平速度**钳到上限（格/拍）：超了就整体缩回上限，竖直分量不动。
     * 与 killHorizontal 同源（都是直写 deltaMovement 的"减速"方向，SWB 的 setDeltaMovement 覆写体
     * 对减速是直通的），区别只在于这里是"封顶"：没超上限就一个字都不改，所以巡航/绕圈不受影响。
     *
     * <p>玩家原话（七百四十三·点1）：「现在它容易离敌人范围太远……明显就出现了刹不住车的情况。
     * 要求就是女仆在驾驶直升机的时候要进行停顿，不能滑出太远，或者获得太大的速度。」
     */
    static void capHorizontal(Entity mount, double max) {
        try {
            if (mount == null || max <= 0.0) {
                return;
            }
            Vec3 dm = mount.m_20184_();
            double hs = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
            if (hs <= max || hs < 1.0E-6) {
                return;
            }
            double k = max / hs;
            mount.m_20256_(new net.minecraft.world.phys.Vec3(dm.f_82479_ * k, dm.f_82480_, dm.f_82481_ * k));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一拍她是不是"该停住"（已到目标点）——停住档的判据。
     *
     * <p>【实测七百三十一·为什么不再看速度】旧判据还要求 {@code hspeed < 0.6}，可实机日志里
     * 到点时 {@code 水平速度} 常在 0.7~1.8（发动机惯性），于是**闸门根本不打开**、她冲过目标点
     * 再慢慢荡回来。玩家要的是"强制刹车"，所以这里**只看距离**：进到点就刹，多快都刹。
     *
     * @param fighting 她此刻在接敌盘旋——**盘旋中绝不刹死**，否则每绕到胡萝卜附近就顿一下，
     *                 圈直接顿成碎步。盘旋结束（丢目标/回到跟随）后自然会刹。
     */
    static boolean shouldHardBrake(double horiz, double hspeed, boolean fighting) {
        return !fighting && horiz <= FLIGHT_ARRIVE;
    }

    /* ---------- 实测七百四十五：三种飞行档共用的"急停三件套" ---------- */

    /** {@link #flightBrake} 返回值的位：本拍打了"到位硬刹"。 */
    static final int BRAKE_FLAG_HARD = 1;
    /** {@link #flightBrake} 返回值的位：本拍打了"周期急停脉冲"。 */
    static final int BRAKE_FLAG_PULSE = 2;
    /** {@link #flightBrake} 返回值的位：本拍把水平速度钳到了上限。 */
    static final int BRAKE_FLAG_CAP = 4;

    /**
     * 【实测七百四十五·点2】收手（{@link #stopVehicle}）时抹水平速度的保留系数：0.15 = 一拍掉八成半。
     * 比到位硬刹的 0.25 更狠一点——"收手"是彻底不管了，让她当场止住，别滑出视线。
     */
    private static final double FLIGHT_STOP_RETAIN = 0.15;

    /**
     * 【实测七百四十五·抽公共】飞行档的"急停三件套"：**硬刹（到位）+ 每 0.5 秒周期脉冲 + 水平速度硬上限**。
     *
     * <h2>为什么抽出来</h2>
     * 玩家原话：「直升机好了。但是极乐恶魂以及其他的飞行载具都没有像直升机那样的同款急停。
     * 导致漂移非常严重。」——七百三十一/七百三十二/七百四十三 那三记刹车**只写在直升机分支里**
     * （{@code driveFlight} 的 {@code if (heli)} 块），于是飞艇（极乐恶魂/基洛夫）与固定翼/汤姆6
     * 全程没有急停：它们松油后引擎每拍按 0.9~0.96 衰减（airShipEngine:1117、:1178），
     * 到点后还会滑出很远，正是玩家看到的"漂移严重"。
     *
     * <h2>为什么对所有飞行档都安全</h2>
     * 三件套只动**水平分量** {@code deltaMovement}，竖直分量一个字不碰（空中失去升力 = 掉高度）；
     * 且减速方向对 SWB 的 {@code setDeltaMovement} 覆写体是**直通**的（只有加速且加速度 &gt; 8 才限幅，
     * 反编译 {@code VehicleEntity:5474-5487}）——所以写小值安全（见 {@link #killHorizontal}）。
     *
     * @param hardBrake 这一拍是不是"到位了该刹死"（{@link #shouldHardBrake}）
     * @param fighting  她此刻在接敌盘旋——盘旋中绝不用 0.25 那种狠的（会把圈顿成碎步）
     * @return 三个 {@code BRAKE_FLAG_*} 的位或（调用方据此打日志）
     */
    static int flightBrake(Entity mount, boolean hardBrake, boolean fighting) {
        int flags = 0;
        try {
            if (hardBrake) {
                killHorizontal(mount, HARD_BRAKE_RETAIN);
                flags |= BRAKE_FLAG_HARD;
            }
            if (!hardBrake && brakePulseDue(mount)) {
                killHorizontal(mount, fighting ? BRAKE_PULSE_RETAIN_FIGHT : BRAKE_PULSE_RETAIN);
                flags |= BRAKE_FLAG_PULSE;
            }
            capHorizontal(mount, FLIGHT_HMAX * FLIGHT_SPEED_CAP_SLACK);
            flags |= BRAKE_FLAG_CAP;
        } catch (Throwable ignored) {
        }
        return flags;
    }

    /**
     * 【实测七百七十七·点2】固定翼专用：**只保速度上限，不保急停**（见盘旋档调用点的说明）。
     *
     * <p>为什么要单独抽一个：{@link #flightBrake} 的三件套是给**悬停型**飞行器（直升机/飞艇）
     * 定的——它们悬停时靠"急停"防漂移；而固定翼的升力 ∝ 速度，任何减速都是掉高度。
     * 玩家的原话已经把结论给死了（「对飞机而言这是致命的，需要放开」）。
     *
     * <p>【实测七百七十八·点2】上限也从扫帚那套 0.9375 换成固定翼自己的
     * {@link #AIRCRAFT_HMAX}（2.60）——0.9375 的升力仍小于重力，等于上限本身就不让飞机飞起来。
     */
    static int aircraftCapOnly(Entity mount) {
        try {
            capHorizontal(mount, AIRCRAFT_HMAX);
            return BRAKE_FLAG_CAP;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 【实测七百八十】固定翼**巡航速度环**：把水平速度平缓地压到 {@code target}（格/拍）。
     *
     * <h2>为什么不是"把推力调小"</h2>
     * 推力→速度的稳态关系要除以引擎的阻力系数 {@code R}（{@code f = 0.96 − 0.0017×R×v²}），
     * 而 {@code R} 没写在车辆 json 里（AC-130H 的 EngineInfo 只有 Increment/SpeedRate 那几项，
     * 反编译实证），所以"给多少推力能落在 0.80"这件事**算不准**。直接写 {@code deltaMovement}
     * 则与 {@code R} 无关：超了就按比例缩回来（SWB 的 {@code setDeltaMovement} 覆写体对**减速**
     * 是直通的——见 {@link #killHorizontal} 的注释），每拍都是我们要的那个数。
     *
     * <h2>为什么带 {@link #AIRCRAFT_SPEED_DECEL} 的缓降</h2>
     * 起飞/爬升档为了升力把速度顶到 {@link #AIRCRAFT_HMAX}（2.60）。切进巡航那一拍如果直接
     * 缩到 0.80，等于一瞬间损失 70% 的速度、升力跟着掉（虽然竖直环会补，但看着像急刹）。
     * 每拍最多掉 0.04 ⇒ 2.60→0.80 约 45 拍（2.3 秒）的平缓减速。
     *
     * <p>注意这里**只压不拉**：低于目标时一个字不改（推力下限才是保速的那一端）。
     */
    static int aircraftGovernSpeed(Entity mount, double target) {
        try {
            Vec3 dm = mount.m_20184_();
            double hs = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
            if (hs < 1.0E-6) {
                return BRAKE_FLAG_CAP;
            }
            double cap = Math.max(target, hs - AIRCRAFT_SPEED_DECEL);
            if (hs <= cap) {
                return BRAKE_FLAG_CAP;
            }
            double k = cap / hs;
            mount.m_20256_(new Vec3(dm.f_82479_ * k, dm.f_82480_, dm.f_82481_ * k));
        } catch (Throwable ignored) {
        }
        return BRAKE_FLAG_CAP;
    }

    /**
     * 【实测七百七十八·点2】把这一架的推力**抬到至少 floor**（不动更高的值）。
     *
     * <p>固定翼这一整条链上，有两处会主动把 {@code power} 往下拉：
     * 引擎自己的盘旋（{@code aircraftLoiter:1337}，收敛到 0.5~0.9）与空中每拍 0.012 的衰减
     * （{@code aircraftEngine:753}）。而 {@code power} 直接决定推力
     * （{@code :878 force = 0.047×power×speedRate}），进而决定速度、进而决定升力
     * （{@code :877}）。所以"保住速度"这件事落到代码上就是**每拍给一个推力下限**。
     * 取"至少"的写法：引擎/规避逻辑想推更高时我们不压它。
     */
    private static void aircraftPowerAtLeast(Entity mount, float floor) {
        if (mSetPower == null) {
            return;
        }
        try {
            double now = 0.0;
            if (mGetPower != null) {
                Object p = mGetPower.invoke(mount);
                if (p instanceof Number n) {
                    now = n.doubleValue();
                }
            }
            if (now < floor) {
                mSetPower.invoke(mount, floor);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 悬停/停住档每拍把水平速度乘掉的系数（0 = 一次停死）。0.25 → 两拍内基本停住。 */
    private static final double HARD_BRAKE_RETAIN = 0.25;

    /**
     * 【实测七百四十三·点1】速度硬上限相对速度环包络的余量：1.25 倍。
     * 巡航/绕圈都在包络内，一点不受影响；只有发动机惯性把她冲过包络两成半以上才动手
     * ——那正是玩家说的"获得太大的速度"。
     */
    private static final double FLIGHT_SPEED_CAP_SLACK = 1.25;

    /* ==================== 实测七百三十三：直写机头朝向（长距离不飞偏） ==================== */

    /**
     * 【实测七百三十三】把机身航向**直接写过去**，不再靠那条只有约一成权限的鼠标偏航通道。
     *
     * <h2>玩家原话</h2>
     * 「女仆控制不好方向感的方向，短距离还行，但是长距离如果要往主人的方向飞很容易飞偏。」
     *
     * <h2>根因（反编译实证）</h2>
     * 引擎 {@code helicopterEngine:613} 的偏航：
     * <pre>
     * setYRot(yRot + yawSpeed * clamp(2.0 * mouseX * propeller, -10, 10))
     * </pre>
     * 而 {@code propeller} 稳态 ≈ {@code power} ≈ 0.09（悬停所需值）——也就是说这个通道的
     * 实际权限只有 {@code 2.0 × 0.09 = 0.18} 倍，**不到玩家推鼠标的两成**。更糟的是同一条
     * {@code mouseX} 在 :612 还**驱动滚转**：她为了纠航向持续压 mouseX（实机日志 {@code 鼠标X=-6}
     * 长期打满），机体一路滚转，升力矢量（{@code getUpVec}）随之歪向侧方——水平推力被掰成
     * 横向分量，既飞偏、又抵消前推（这正是「中途失速」）。
     *
     * <h2>本方法</h2>
     * 直接把 {@code setYRot} 写到目标方位（并按每拍上限平滑，免得瞬转画龙），同时把
     * {@code serverYaw} 一起写上——反编译 {@code VehicleEntity.handleClientSync:5443-5445}
     * 服务端每拍会用 {@code serverYaw} 把 {@code yRot} 往回 lerp，不写它就会被拽回去。
     * 通道里只留**很小一点** mouseX（{@link #FLIGHT_YAW_TRIM}）做微调，滚转耦合随之可忽略。
     *
     * @param yawErr 目标方位 - 当前机头（已 wrap 到 [-180,180)）
     * @return true = 真的写了（反射拿不到 → false，调用方退回鼠标通道）
     */
    static boolean forceHeadingOnto(Entity mount, float yawErr) {
        try {
            if (mount == null) {
                return false;
            }
            float step = clamp(yawErr, -FLIGHT_HEAD_MAX_DEG, FLIGHT_HEAD_MAX_DEG);
            float yaw = wrapDegrees(mount.m_146908_() + step);
            mount.m_146922_(yaw);
            if (mSetServerYaw != null) {
                try {
                    mSetServerYaw.invoke(mount, yaw);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 直写机头的每拍最大转角（度）。10 ≈ 200°/秒——比引擎那条通道（约 1.6°/拍 上限，
     * 且被 propeller 缩到不足两成）**快十倍以上**，又远低于瞬转，长距离能一路咬住方位。
     */
    private static final float FLIGHT_HEAD_MAX_DEG = 10.0f;

    /* ==================== 实测七百三十九·点1：悬停机头锁死（不原地打转） ==================== */

    /**
     * 【实测七百三十九·点1】悬停期间机头保持不动的"锚定角"。
     *
     * <h2>玩家原话</h2>
     * 「女仆驾驶直升机在空中悬停的时候，总是在原地进行不停的旋转。没有什么实际影响，就是观感不好。
     * 能不能让它的头在悬停期间朝向不要发生变化呢？」
     *
     * <h2>根因</h2>
     * 悬停档（跟随、且已贴到目标点）时，跟随目标点就压在她**自己脚下**——水平分量≈0。于是
     * {@code driveVehicle} 算出来的 {@code err} 是一个**退化方位**：{@code atan2(Δx, Δz)} 的两个
     * 参数都是座位与目标点之间的浮点抖动，每拍符号/量级都在变 → 鼠标 X 通道与直写机头都跟着它
     * 改朝向 → 机头原地慢慢转。这不是"故障"，是"把噪声当指令"。
     *
     * <h2>修法</h2>
     * 进入悬停档的那一刻**采样一次机头**存起来，此后每拍写回这个值（{@code setYRot} +
     * {@code setServerYaw}，后者不写会被 {@code handleClientSync} 的 lerp 慢慢拽走），
     * 并把鼠标 X 通道归零。退出悬停档（有目标要绕圈 / 要移动 / 下鞍）立刻解冻，
     * 下一次进档重新采样——**接敌盘旋完全不受影响**（那一档永远不满足悬停判据）。
     *
     * @return 要锁定的机头角（度）；首次调用采样当前机头
     */
    static float hoverYawHold(Entity mount) {
        try {
            if (mount == null) {
                return 0.0f;
            }
            java.util.UUID id = mount.m_20148_();
            Double held = HOVER_YAW.get(id);
            if (held == null) {
                float cur = mount.m_146908_();
                if (HOVER_YAW.size() > 512) {
                    HOVER_YAW.clear(); // 兜底：表不会无限涨（与其它几张表同口径）
                }
                HOVER_YAW.put(id, (double) cur);
                return cur;
            }
            return held.floatValue();
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    /** 退出悬停档 → 丢掉锚定角（下次进档重新采样当前机头）。 */
    static void releaseHoverYaw(Entity mount) {
        try {
            if (mount != null) {
                HOVER_YAW.remove(mount.m_20148_());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 载具 UUID → 悬停期间锁定的机头角（度）。见 {@link #hoverYawHold}。 */
    private static final java.util.Map<java.util.UUID, Double> HOVER_YAW =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百三十七·转向】地面载具（车/坦克，引擎 {@code WHEEL}/{@code TRACK}）的机头兜底。
     *
     * <p>玩家原话：「女仆在开车的时候，那个车头的转向方面没有那么智能，能不能也加上像直升机
     * 这样子的转向兜底呢？」——地面那一档此前**只写左右输入位**（0x001/0x002），把转向整个交给
     * SWB 引擎自己的 {@code holdTick → deltaRot → rudderRot} 那条链。反编译实证那条链有两个
     * 死结：① 转向量按 {@code holdTick} 慢慢累积（每拍 {@code steeringSpeed*0.12*min(holdTick,10)}），
     * ② 转率正比于**车速**（{@code 12*horizSpeed}）且 {@code rudderRot} 被夹在 ±0.8。于是"起步/慢速
     * 时几乎转不动、快起来又追不上"，就是玩家说的"没那么智能"。
     *
     * <p>本方法与直升机那条 {@link #forceHeadingOnto} 同源（直写 {@code setYRot} + 一起写
     * {@code setServerYaw}，否则客户端 {@code handleClientSync} 的 10%/拍 lerp 会把机头拽回去），
     * 但**有三处地面专属的收敛**，都是"两股力别打架"的必然取舍：
     * <ul>
     *   <li><b>死区更大</b>（{@link #CAR_HEAD_DEADBAND} = 25°）：引擎每拍自己也在改 yaw，
     *       小误差时让引擎自己收（避免画龙）；只有"确实偏了"才插手；</li>
     *   <li><b>每拍上限随车速缩放</b>（{@code clamp(err, ±k*|速度|, ±floor)}）：引擎自己的 yaw 积分
     *       正比于车速，固定步长在慢速时会过冲、快速时又杯水车薪。缩放后快慢都咬得住；</li>
     *   <li><b>只写 yaw，绝不碰 deltaRot</b>：{@code rudderRot} 是从 {@code deltaRot} 积分出来的，
     *       在这里清它等于把引擎自己的转向冻死（轮子永远不转）。输入位照旧发，本方法只当**偏置**。</li>
     * </ul>
     *
     * @param mount   地面载具
     * @param yawErr  目标方位 - 当前机头（已 wrap 到 [-180,180)）
     * @return true = 真的写了（反射/类型不满足 → false，调用方退回纯输入位）
     */
    static boolean forceCarHeading(Entity mount, float yawErr) {
        try {
            if (mount == null) {
                return false;
            }
            float abs = Math.abs(yawErr);
            if (abs <= CAR_HEAD_DEADBAND) {
                return false; // 死区内：交给引擎自己的转向链，别跟它抢
            }
            // 每拍上限随车速缩放：慢速给一个下限（不然永远转不动），快速按速度放开（不然追不上）。
            double speed = 0.0;
            try {
                Vec3 dm = mount.m_20184_();
                speed = Math.sqrt(dm.f_82479_ * dm.f_82479_ + dm.f_82481_ * dm.f_82481_);
            } catch (Throwable ignored) {
            }
            float cap = (float) Math.max(CAR_HEAD_MIN_STEP, CAR_HEAD_SPEED_GAIN * speed);
            cap = Math.min(cap, CAR_HEAD_MAX_STEP);
            // 去掉死区再夹（死区内不动，出死区按"超出部分"给步长，靠近时自然收敛不抖）
            float eff = Math.copySign(abs - CAR_HEAD_DEADBAND, yawErr);
            float step = clamp(eff, -cap, cap);
            float yaw = wrapDegrees(mount.m_146908_() + step);
            mount.m_146922_(yaw);
            if (mSetServerYaw != null) {
                try {
                    mSetServerYaw.invoke(mount, yaw);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 地面机头兜底的死区（度）：偏得比这少就不插手（见 {@link #forceCarHeading}）。 */
    private static final float CAR_HEAD_DEADBAND = 25.0f;
    /** 地面机头兜底每拍步长随车速的增益（度/格每拍）：引擎自身转率也是正比车速，同源缩放。 */
    private static final double CAR_HEAD_SPEED_GAIN = 6.0;
    /** 地面机头兜底每拍步长下限（度）：停车/起步时引擎几乎转不动，给个地板保证能转起来。 */
    private static final float CAR_HEAD_MIN_STEP = 4.0f;
    /** 地面机头兜底每拍步长上限（度）：仍远低于瞬转，留出客户端 lerp 的跟随余量。 */
    private static final float CAR_HEAD_MAX_STEP = 10.0f;

    /**
     * 直写航向时留在鼠标 X 通道里的**微调量**：只补一点转角，顺带把滚转耦合压到可忽略
     * （那条通道同时驱动滚转，见 {@link #forceHeadingOnto}）。
     */
    private static final float FLIGHT_YAW_TRIM = 1.5f;


    /**
     * 【实测七百三十二】急停脉冲的间隔（拍）：玩家原话「最好是每 0.5 秒就急停下来一次。
     * 这样子可以显著增加飞机的稳定性」——20 拍 = 1 秒，所以 **10 拍 = 0.5 秒**。
     */
    private static final int BRAKE_PULSE_TICKS = 10;

    /**
     * 脉冲急停的**保留系数**（跟随/接近档）：每 {@link #BRAKE_PULSE_TICKS} 拍把水平分量打到
     * 这个比例。玩家原话就是「每 0.5 秒就急停下来一次」——所以这里照做，把到位时那记"急停"
     * 变成周期性的。
     *
     * <p>【为什么不是"封顶"】先试过只在超速时砍回上限（更平滑），但那不叫"急停"、对
     * "用力过猛"的削峰也不够狠。按实机数据估算：她 1 秒能从 0 冲到 1.83，所以每 0.5 秒
     * 砍到 0.25 会形成 **0.3~1.1** 的有界锯齿、均值 ≈ 0.7（正好在速度环 0.75 的包络内）——
     * 既压住了过冲，又没把她拖慢。竖直分量不动（空中不能失去升力）。
     */
    private static final double BRAKE_PULSE_RETAIN = 0.25;

    /**
     * 接敌盘旋档的保留系数——**比跟随档温和**。绕圈要靠水平速度维持，用 0.25 会把圈顿成
     * 一步一停；0.55 只压速度峰值、圈照绕。
     */
    private static final double BRAKE_PULSE_RETAIN_FIGHT = 0.55;

    /** 每只车的脉冲计数（每拍 +1，到 {@link #BRAKE_PULSE_TICKS} 归零并触发一次）。 */
    private static final java.util.Map<java.util.UUID, Integer> BRAKE_PULSE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 这一拍该不该打一次"急停脉冲"（每 {@link #BRAKE_PULSE_TICKS} 拍一次）。
     *
     * @return true = 本拍触发脉冲（调用方据此调 {@link #killHorizontal}）
     */
    static boolean brakePulseDue(Entity mount) {
        try {
            if (mount == null) {
                return false;
            }
            Integer n = BRAKE_PULSE.get(mount.m_20148_());
            int v = (n == null ? 0 : n) + 1;
            if (BRAKE_PULSE.size() > 512) {
                BRAKE_PULSE.clear(); // 兜底：表不会无限涨（与其它几张频限表同口径）
            }
            if (v >= BRAKE_PULSE_TICKS) {
                BRAKE_PULSE.put(mount.m_20148_(), 0);
                return true;
            }
            BRAKE_PULSE.put(mount.m_20148_(), v);
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百三十一】弹匣保底：弹匣空 + 车容器里有对得上的弹 → 自己装。
     *
     * <p>反编译 {@code GunData}：弹匣 {@code ammo} 为空且 {@code useBackpackAmmo()} 为假时，
     * {@code canShoot} 直接返回 false——而"从容器补弹"（{@code shouldStartReloading}→
     * {@code reloadAmmo}）是**玩家开火键**那条链在调，女仆没有开火键。所以每拍检查一次。
     *
     * <p>【实测七百四十】原来这个方法还顺带"自己算角度写炮塔"，那条已删（角度写法根本是错的，
     * 见 {@link #aimBallistic}）；这里只留"保证有弹"这一件事。
     */
    static void ensureAmmo(Entity mount, EntityMaid maid) {
        try {
            int seat = seatIndexOf(mount, maid);
            if (seat >= 0) {
                ensureMagazineLoaded(mount, seat);
            }
            // 【实测七百四十一·点2b/点3】她自己那一座没武器（AC-130H 的座 0）时，上面那句
            // 什么也问不出来——补一发"把**这一场要用的那门炮**的弹匣装满"。这就是炮艇
            // "会填充炮弹"的那一半（另一半是 feedVehicleAmmo 把弹搬进车容器）。
            String gun = gunNameFor(mount, maid);
            if (gun != null) {
                int gs = seatOfGun(mount, gun);
                if (gs >= 0 && gs != seat) {
                    ensureMagazineLoaded(mount, gs);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百四十五·点4】"当前模式没弹 → 换一个有弹的模式"。
     *
     * <h2>玩家原话（即规格）</h2>
     * 「假设模式 A 可以发射 A 弹药，模式 B 可以发射 B 弹药，玩家初始给直升机的状态为模式 A，
     * 但是只有 B 的子弹。那么女仆就不会操纵直升机发射弹药，必须要玩家手动调整到模式 B……应该
     * 在没有发现链路之后，再检查一下其他模式有没有对应的弹药，然后考虑切换到那个模式。」
     *
     * <h2>SWB 的"模式"是两级（反编译实证）</h2>
     * <ol>
     *   <li><b>逐座武器</b>：一个座可挂多门炮，getSelectedWeapon() 记录每座选中的是哪门
     *       （getWeaponIndex/setWeaponIndex/getGunName(int,int)）；</li>
     *   <li><b>同一门炮的弹种</b>：selectedAmmoType 指向 GunProp.AMMO_CONSUMER 表里第几项
     *       （changeAmmoConsumer）。</li>
     * </ol>
     * 两级都试：先换弹种（同一门炮）、再换炮。判据用 {@code AmmoConsumer.count(gunData, supplier)}
     * 与 {@code currentAvailableAmmo}——与引擎 canShoot 内部同一套账。
     *
     * @return true = 真的切了；false = 当前模式就有弹 / 切不动
     */
    static boolean ensureUsableAmmoMode(Entity mount, EntityMaid maid) {
        try {
            if (mount == null || mChangeAmmoConsumer == null || mGunGetProp == null
                    || fGunPropAmmoConsumer == null) {
                return false;
            }
            Entity supplier = mount;
            if (mAmmoSupplier != null) {
                try {
                    Object s = mAmmoSupplier.invoke(mount);
                    if (s instanceof Entity e) {
                        supplier = e;
                    }
                } catch (Throwable ignored) {
                }
            }
            final Entity sup = supplier;
            // ---- ① 当前这一门炮：先看它自己有没有弹，没有就在它的弹种表里找一个有弹的 ----
            String cur = gunNameFor(mount, maid);
            if (cur == null) {
                return false;
            }
            Object gd = gunDataOf(mount, cur);
            if (gd == null) {
                return false;
            }
            if (ammoAvailable(gd, sup)) {
                return false; // 当前模式就有弹 → 一个字不改
            }
            Object consumers = mGunGetProp.invoke(gd, fGunPropAmmoConsumer.get(null));
            int n = sizeOf(consumers);
            for (int i = 0; i < n; i++) {
                try {
                    mChangeAmmoConsumer.invoke(gd, i, sup);
                } catch (Throwable ignored) {
                    continue;
                }
                if (ammoAvailable(gd, sup)) {
                    ensureAmmo(mount, maid); // 换完顺手把弹匣装满
                    com.maidsmart.tool.PromaidLog.log("模组坐骑·弹种",
                            "「" + cur + "」当前弹种无弹 → 切到弹种 #" + i);
                    return true;
                }
            }
            // ---- ② 同一座里换一门炮（玩家说的"模式 A/B"多半是这一级）----
            int seat = -1;
            if (maid != null) {
                seat = seatIndexOf(mount, maid);
            }
            if (seat < 0) {
                seat = seatOfGun(mount, cur);
            }
            if (seat < 0 || mGetWeaponIndex == null || mSetWeaponIndex == null
                    || mGetGunNameAtSeatWeapon == null) {
                return false;
            }
            int curIdx = asInt(mGetWeaponIndex.invoke(mount, seat), -1);
            int count = seatWeaponCount(mount, seat);
            for (int w = 0; w < count; w++) {
                if (w == curIdx) {
                    continue;
                }
                String other = asString(mGetGunNameAtSeatWeapon.invoke(mount, seat, w));
                if (other == null) {
                    continue;
                }
                Object ogd = gunDataOf(mount, other);
                if (ogd == null || !ammoAvailable(ogd, sup)) {
                    continue;
                }
                mSetWeaponIndex.invoke(mount, seat, w);
                ensureAmmo(mount, maid);
                com.maidsmart.tool.PromaidLog.log("模组坐骑·换炮", "座 " + seat + " 当前武器「" + cur
                        + "」无弹 → 切到「" + other + "」（第 " + w + " 门）");
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这门炮**当前模式**下有没有可用弹药（与引擎 canShoot 同一套账）。
     *
     * <p>【为什么必须用 {@code selectedAmmoConsumer().count()} 而不是 {@code currentAvailableAmmo}】
     * 反编译 GunData:809 实证：currentAvailableAmmo 在"不背包取弹"时返回的是**弹匣里现有几发**
     * （ammo.get()），不是"这个模式配得上的弹药有多少"。而我们要判的是**"这个模式有没有对得上的
     * 弹药来源"**——弹匣空了但容器里有对得上的弹，那是"该装填"而不是"该换模式"（装填由
     * ensureMagazineLoaded 负责）。所以这里读 selectedAmmoConsumer() 这个**当模式所用的 consumer**
     * 再问它 count()——与引擎 hasEnoughAmmoToShoot → countBackupAmmo → countBackupAmmoItem 完全同源。
     * 弹匣现成有弹也算，两者取或。
     */
    private static boolean ammoAvailable(Object gd, Entity supplier) {
        try {
            if (gd == null) {
                return false;
            }
            // 【实测七百七十七·点1】车容器里有创造盒 → SWB 自己按无限后备弹算（countBackupAmmo
            // 返回 MAX_VALUE、consume 不扣，反编译实证）。这里必须**提前认它**，否则有弹匣的炮
            // （Cannon/MachineGun）弹匣一空就被我们判"没弹"、换弹种/换炮白折腾 + 打「缺弹」日志。
            if (vehicleHasInfiniteAmmo(supplier)) {
                return true;
            }
            if (mGunCurrentAmmo != null) {
                Object cur = mGunCurrentAmmo.invoke(gd, supplier);
                if (cur instanceof Integer i && i > 0) {
                    return true; // 弹匣/背包里现成有
                }
            }
            if (mGunSelectedAmmo != null && mConsumerCount != null) {
                Object consumer = mGunSelectedAmmo.invoke(gd);
                if (consumer != null) {
                    Object c = mConsumerCount.invoke(consumer, gd, supplier);
                    if (c instanceof Integer i && i > 0) {
                        return true; // 这个模式配得上的弹药存在（可装填）
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 某一座上挂了几门炮（computed().seats().get(seat).weapons().size()）。 */
    private static int seatWeaponCount(Entity mount, int seat) {
        try {
            if (mComputed == null || seat < 0) {
                return 0;
            }
            Object seats = seatsOf(mComputed.invoke(mount));
            if (seats instanceof java.util.List<?> list && seat < list.size()) {
                Object weapons = weaponsOf(list.get(seat));
                if (weapons instanceof java.util.List<?> wl) {
                    return wl.size();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object seatsOf(Object computed) {
        try {
            java.lang.reflect.Method m = computed.getClass().getMethod("seats");
            return m.invoke(computed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object weaponsOf(Object seatInfo) {
        try {
            if (seatInfo == null) {
                return null;
            }
            java.lang.reflect.Method m = seatInfo.getClass().getMethod("weapons");
            return m.invoke(seatInfo);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int sizeOf(Object list) {
        return list instanceof java.util.Collection<?> c ? c.size() : 0;
    }

    /** 取列表第 i 项（{@code List} 专用；拿不到 → null）。逐弹种枚举用（775·点1）。 */
    private static Object listGet(Object list, int i) {
        try {
            return list instanceof java.util.List<?> l && i >= 0 && i < l.size() ? l.get(i) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int asInt(Object v, int dflt) {
        return v instanceof Integer i ? i : dflt;
    }

    private static String asString(Object v) {
        return v instanceof String s && !s.isEmpty() ? s : null;
    }

    /** 这门炮在车容器里吃的那种弹（{@code GunData}）；拿不到 → null。喂给换弹种/装弹链路用。 */
    private static Object gunDataOf(Entity mount, String gunName) {
        try {
            if (mGetGunDataName == null || gunName == null) {
                return null;
            }
            return mGetGunDataName.invoke(mount, gunName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这门炮挂在哪个座上（反查）；找不到 → -1。 */
    private static int seatOfGun(Entity mount, String gunName) {
        try {
            if (gunName == null) {
                return -1;
            }
            int n = maxPassengers(mount);
            for (int i = 0; i < n; i++) {
                if (gunName.equals(gunNameAt(mount, i))) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 【实测七百四十一·点2b/点3】她该操作**哪一门炮**——全车唯一的那次"选炮"。
     *
     * <h2>为什么必须有这个方法（本批"直升机打不出炮弹 / 炮艇完全不装弹"的共同根因）</h2>
     * 反编译实证：{@code getShootPos/getShootVec/getProjectileVelocity/getProjectileGravity}
     * 的 **{@code Entity} 重载**内部一律走 {@code getGunData(getSeatIndex(entity))}——**按"她坐哪座"
     * 解析武器**。可卓越前线的多座载具里主炮常常**不在她坐的那一座**：
     * <ul>
     *   <li><b>Mi-28</b>：{@code TurretControllerIndex = 1}，30mm 机炮在**座 1**；她（驾驶员）
     *       在座 0，座 0 的武器是火箭弹。于是 740 那一版"瞄准算的是机炮、扣扳机打的却是火箭弹"
     *       → 玩家原话「发射的炮弹准度约等于 0」。</li>
     *   <li><b>AC-130H</b>：座 0（驾驶位）**一门武器都没有**，三门炮在座 1/2/3。
     *       {@code getGunData(0)} 恒为 null → 连"该搬什么弹"都问不出来 → 一颗都不装、
     *       引擎那条"Mob 乘客自动开火"也因 {@code getGunData(mob) == null} 直接跳过
     *       → 玩家原话「女仆不会填充和发射炮弹」。</li>
     * </ul>
     * 所以"瞄哪门炮"和"打哪门炮"必须由**同一个炮名**统一解析。优先级：
     * <ol>
     *   <li><b>车上的炮塔</b>（{@code hasTurret}，且炮塔位上没有玩家在手动瞄）→ 用炮塔那门炮。
     *       <b>这一条排最前</b>，因为它才是"能被瞄准的炮"：Mi-28 的 30mm 机炮（座 1）是玩家说的
     *       "那个炮弹"，炮塔能逐拍转到位（740 那一版瞄的就是它，却把扳机扣在了座 0 的火箭弹上）。</li>
     *   <li>否则<b>她自己座位上的武器</b>（Mi-28 座 0 的火箭弹、prism_tank 座 0 的激光——单座/
     *       炮塔归驾驶位的车全部走这一条，与旧行为完全一致）；</li>
     *   <li>否则<b>武器站</b>（{@code hasPassengerWeaponStation}，且该位没有玩家）；</li>
     *   <li>否则<b>车上第一门有武器的座</b>（AC-130H：座 0 空 → 落到座 1 的 M61）——炮艇靠这一条。</li>
     * </ol>
     * 全都没有 → {@code null}（这车确实没炮，调用方照旧不做任何事）。
     *
     * @return 炮名（{@code getGunName(int)} 的返回值，可直接喂给按名取的各个重载）；没有 → null
     */
    static String gunNameFor(Entity mount, EntityMaid maid) {
        try {
            if (mount == null || !isVehicle(mount) || mGetGunNameAtSeat == null) {
                return null;
            }
            // ① 炮塔（真正的"可瞄准的炮"）——玩家没占着这个位才归我们
            int turretSeat = controllerIndex(mount, mHasTurret, mGetTurretCtrlIdx);
            if (turretSeat >= 0 && !seatHasPlayer(mount, turretSeat)) {
                String g = gunNameAt(mount, turretSeat);
                if (g != null) {
                    return g;
                }
            }
            // ② 她自己那一座（旧行为，保住已经能用的单座车）
            if (maid != null) {
                String own = gunNameAt(mount, seatIndexOf(mount, maid));
                if (own != null) {
                    return own;
                }
            }
            // ③ 武器站
            int stationSeat = controllerIndex(mount, mHasWeaponStation, mGetWeaponCtrlIdx);
            if (stationSeat >= 0 && !seatHasPlayer(mount, stationSeat)) {
                String g = gunNameAt(mount, stationSeat);
                if (g != null) {
                    return g;
                }
            }
            // ④ 兜底：从 0 号座起找第一门有武器的座（AC-130H：座 0 空 → 落到座 1 的 M61）
            int n = maxPassengers(mount);
            for (int i = 0; i < n; i++) {
                String g = gunNameAt(mount, i);
                if (g != null) {
                    return g;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 【实测七百七十四·点2】车上**每一门能用的炮**（逐座逐位枚举，去重）。
     *
     * <h2>玩家原话（即规格）</h2>
     * 「像坦克这种，它拥有多种攻击方式，而这个时候又有多种炮管。如果多种炮管都满足，
     * 应该一起开火，而不是单选择一种。」
     *
     * <h2>旧口径为什么只打一门</h2>
     * {@link #gunNameFor} 取的是**每座当前选中那门**（{@code getGunName(座)}），于是 M1A2 的
     * 座 0 挂着 {@code Cannon + MachineGun} 两门炮、永远只打选中的那一门（实机日志里
     * {@code 炮=Cannon} 与 {@code 炮=MachineGun} 交替出现，正是"玩家手动切换模式"的结果）。
     *
     * <h2>本方法</h2>
     * 按 {@code getGunName(座, 位)} 把**每个座位的每一门炮**都收进来（反编译实证：
     * {@code SeatInfo.weapons()} 就是逐座武器表）。玩家坐着的那一座**整座跳过**——那一座的
     * 武器归玩家手动操作，我们不抢。
     *
     * @return 炮名列表（顺序稳定：座 0 的炮在前）；这车没炮 → 空表
     */
    static java.util.List<String> gunsFor(Entity mount, EntityMaid maid) {
        java.util.List<String> out = new java.util.ArrayList<>(4);
        try {
            if (mount == null || !isVehicle(mount) || mGetGunNameAtSeatWeapon == null) {
                return out;
            }
            int n = maxPassengers(mount);
            for (int seat = 0; seat < n; seat++) {
                if (seatHasPlayer(mount, seat)) {
                    continue; // 玩家占着这一座 → 他的武器归他
                }
                for (int w = 0; w < 8; w++) {
                    String g = asString(mGetGunNameAtSeatWeapon.invoke(mount, seat, w));
                    if (g == null) {
                        break; // 这个位没炮了（武器表已到尾）
                    }
                    if (!out.contains(g)) {
                        out.add(g);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 炮名里带 Bomb 的 = 往下丢的航空炸弹（基洛夫/斯图卡那几门），走引擎投弹链路与最小间隔。 */
    private static boolean isBombGun(String gun) {
        return gun != null && gun.toLowerCase(java.util.Locale.ROOT).contains("bomb");
    }

    /**
     * 【实测七百七十五·点2】车上所有炸弹武器的**最大爆炸半径**（格）——投弹安全高度的基准。
     *
     * <h2>玩家原话（即规格）</h2>
     * 「我发现向上20格似乎不行。还是很容易被炸到。应该把上升高度调整为那个炸弹所能波及的
     *  半径的大小。保证全身而退。」
     *
     * <h2>为什么 20 格不够（算术）</h2>
     * 基洛夫那颗航空炸弹 {@code ExplosionRadius=42}（vehicle json 实证），而旧版安全高度是
     * 配置里的固定 20 格、还是从**目标**算起的：炸弹落点就在目标脚下，机身却在爆心正上方
     * 20 格 → 距离 20 &lt; 42，等于**自己站在爆心顶部**（飞船还吃
     * {@code "@#superbwarfare:aerial_bomb * 3"} 三倍航空炸弹伤害）。
     *
     * <p>读法：逐门炸弹读 {@code GunProp.EXPLOSION_RADIUS}，取最大；读不到 → 0
     * （调用方退回配置值，一个字节不变）。
     */
    static double bombBlastRadius(Entity mount, EntityMaid maid) {
        double best = 0.0;
        try {
            if (mount == null || fGunPropExplosionRadius == null || mGunGetProp == null) {
                return 0.0;
            }
            java.util.List<String> guns = gunsFor(mount, maid);
            if (guns.isEmpty()) {
                String one = gunNameFor(mount, maid);
                if (one != null) {
                    guns = java.util.Collections.singletonList(one);
                }
            }
            for (String g : guns) {
                if (!isBombGun(g)) {
                    continue;
                }
                Object gd = gunDataOf(mount, g);
                if (gd == null) {
                    continue;
                }
                Object v = mGunGetProp.invoke(gd, fGunPropExplosionRadius.get(null));
                if (v instanceof Number n) {
                    best = Math.max(best, n.doubleValue());
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** 这个位上坐的是不是玩家（玩家在瞄 → 我们让开）。 */
    private static boolean seatHasPlayer(Entity mount, int seat) {
        try {
            if (seat < 0 || mGetNthEntity == null) {
                return false;
            }
            return mGetNthEntity.invoke(mount, seat) instanceof net.minecraft.world.entity.player.Player;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 某一座上**当前选中**那门炮的名字；该座没武器 / 问不出来 → null。 */
    private static String gunNameAt(Entity mount, int seat) {
        try {
            if (seat < 0 || mGetGunNameAtSeat == null) {
                return null;
            }
            Object v = mGetGunNameAtSeat.invoke(mount, seat);
            return v instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 某个"控制位"（炮塔/武器站）的座号；这车没这个位 → -1。 */
    private static int controllerIndex(Entity mount, Method hasSlot, Method ctrlIdx) {
        try {
            if (hasSlot == null || ctrlIdx == null) {
                return -1;
            }
            if (!Boolean.TRUE.equals(hasSlot.invoke(mount))) {
                return -1;
            }
            Object idx = ctrlIdx.invoke(mount);
            return idx instanceof Integer i ? i : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 【实测七百四十一起未使用】按座位的炮口位置入口——已被按炮名的 {@link #shootPosOfGun} 取代。 */
    private static Method mGetShootPosSeat;
    @SuppressWarnings("unused")
    private static Vec3 shootPosOf(Entity mount, int seat) {
        try {
            if (mGetShootPosSeat == null) {
                mGetShootPosSeat = cVehicle.getMethod("getShootPos", int.class, float.class);
            }
            Object v = mGetShootPosSeat.invoke(mount, seat, 1.0f);
            return v instanceof Vec3 vec ? vec : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 【实测七百三十一】弹匣补弹：弹匣空 + 车容器里有对得上的弹 → 自己装。
     * 反编译 {@code GunData.reloadAmmo(Entity,boolean)}：从 {@code countBackupAmmo} 取、
     * 扣容器、写 {@code ammo}。{@code Entity} 参传**车自己**（{@code getAmmoSupplier()}）。
     */
    private static void ensureMagazineLoaded(Entity mount, int seat) {
        try {
            if (mGetGunDataSeat == null) {
                return;
            }
            ensureGunLoaded(mount, mGetGunDataSeat.invoke(mount, seat));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十四·点2】按**炮名**补弹匣——全车炮管一起开火时，每一门都要能自己装填
     * （旧版只补"她坐那一座 + 主炮"两门，别的炮打空弹匣后永远哑火）。
     */
    private static void ensureGunLoaded(Entity mount, String gun) {
        try {
            if (gun == null) {
                return;
            }
            ensureGunLoaded(mount, gunDataOf(mount, gun));
        } catch (Throwable ignored) {
        }
    }

    /** 补弹匣的共用实现（GunData 已取到）：空匣 + 车容器里有对得上的弹 → reloadAmmo。 */
    private static void ensureGunLoaded(Entity mount, Object gd) {
        try {
            if (gd == null || mGunReloadAmmo == null) {
                return;
            }
            if (mGunReloading != null) {
                Object rel = mGunReloading.invoke(gd);
                if (Boolean.TRUE.equals(rel)) {
                    return; // 正在装填 → 别打断
                }
            }
            Entity supplier = mount;
            if (mAmmoSupplier != null) {
                try {
                    Object s = mAmmoSupplier.invoke(mount);
                    if (s instanceof Entity e) {
                        supplier = e;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (mGunCurrentAmmo != null) {
                Object cur = mGunCurrentAmmo.invoke(gd, supplier);
                if (cur instanceof Integer i && i > 0) {
                    return; // 还有弹 → 不用补
                }
            }
            mGunReloadAmmo.invoke(gd, supplier, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十七·点1】把这一门炮的弹匣**直接写满**（绕开引擎的装填账）。
     *
     * <h2>为什么需要"直接写"</h2>
     * 实机日志（777 那一局）实证：创造盒在 TRACK 车容器里，
     * {@code PassengerMachineGun}（无弹匣型）每 5 秒稳定开火——说明引擎的
     * {@code countBackupAmmo} **认**这个盒（返回 MAX_VALUE）；而 {@code Cannon}/{@code MachineGun}
     * （Magazine&gt;0 的弹匣型）全程「弹匣空、全部弹种都换不出余弹」——因为它们的
     * {@code hasEnoughAmmoToShoot} 只读 {@code ammo.get()}，**弹匣得有人装**：
     *   · 引擎自己的 autoReload 依赖 {@code GunProp.AUTO_RELOAD}（载具武器 json 没写，
     *     默认为假/空 ⇒ {@code autoReload} 直接 return，车上没有任何人按键装填）；
     *   · 我们调 {@code reloadAmmo} 时，它内部先查 {@code useBackpackAmmo()}（弹匣型为假，
     *     可继续）再取 {@code countBackupAmmo}——理论上能装，但实机表现为没装上
     *     （装填状态/时序与节点判断纠缠，见 775/776 两轮的反复）。
     * 所以这里用最直白、与「创造盒=无限弹」语义完全一致的做法：**直接把弹匣值写成满**
     * （{@code GunData.ammo} 是 public 的 {@code IntValue}，SWB 自己在创造模式玩家身上
     * 也是这么干的——反编译 {@code GunData.changeAmmoConsumer:630-632}：
     * {@code if (ammoSupplier instanceof Player && isCreative) ammo.set(MAGAZINE)}）。
     *
     * <p>弹药消耗照旧走引擎（弹匣型在 {@code afterShoot} 里 {@code ammo -= cost}）——我们只在
     * 每次开火前把它补回满值，**不产生任何物品**，也不碰背包/容器。
     */
    private static void forceMagazineFull(Object gd) {
        try {
            if (gd == null || fGunAmmo == null || mIntValueGet == null || mIntValueSet == null
                    || mGunGetProp == null || fGunPropMagazine == null) {
                return;
            }
            Object magObj = mGunGetProp.invoke(gd, fGunPropMagazine.get(null));
            if (!(magObj instanceof Number magNum)) {
                return;
            }
            int mag = magNum.intValue();
            if (mag <= 0) {
                return; // 无弹匣武器（背包弹型）：它的无限弹由 forceBackupAmmoFull / countBackupAmmo 负责
            }
            Object ammoVal = fGunAmmo.get(gd);
            if (ammoVal == null) {
                return;
            }
            Object curObj = mIntValueGet.invoke(ammoVal);
            int cur = curObj instanceof Number n ? n.intValue() : 0;
            if (cur < mag) {
                mIntValueSet.invoke(ammoVal, mag);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十八·点1】把 {@code backupAmmoCount}（无弹匣武器的"可用弹"显示值）写足。
     *
     * <p>为什么必须写它：{@code VehicleGunItem.canShoot} 的最后一关是
     * {@code VehicleEntity.getAmmo(data) >= AMMO_COST_PER_SHOOT}，而
     * {@code getAmmo = useBackpackAmmo() ? backupAmmoCount.get() : ammo.get()}
     * （{@code VehicleEntity:5707}，反编译实证）——**机枪/乘客机枪这类无弹匣武器，判的是
     * backupAmmoCount 这个值**，不是 {@code ammo}、也不是实时的 {@code countBackupAmmo}。
     */
    private static void forceBackupAmmoFull(Object gd) {
        try {
            if (gd == null || fGunBackupAmmoCount == null || mIntValueGet == null || mIntValueSet == null) {
                return;
            }
            Object bv = fGunBackupAmmoCount.get(gd);
            if (bv == null) {
                return;
            }
            Object curObj = mIntValueGet.invoke(bv);
            int cur = curObj instanceof Number n ? n.intValue() : 0;
            if (cur < FORCE_BACKUP_AMMO) {
                mIntValueSet.invoke(bv, FORCE_BACKUP_AMMO);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十八·点1】把"不许开火"的那几个状态位清干净。
     *
     * <p>反编译实证：{@code VehicleGunItem.canShoot} 除弹量外还有五道闸——
     * {@code !overHeat}、{@code heat <= 100}、{@code !reloading()}、{@code !charging()}、
     * {@code !bolt.needed}。任意一道为真，主炮就是"一点动静都没有"。
     * <ul>
     *   <li>①② 装填中 / 拉栓中 / 蓄力中：直接调 SWB 自己的 {@code GunData.resetStatus()}
     *       （{@code GunData:641}，它一手清掉 reload / bolt / charge / fireIndex）；</li>
     *   <li>③ 过热 / 热度：把 {@code overHeat} 置 false、{@code heat} 归零（机枪连射的闸）。</li>
     * </ul>
     * 只在"车容器里有创造盒"（{@link #vehicleHasInfiniteAmmo}）时调用——也就是玩家要的
     * "检测到创造弹药盒就强制允许开炮"，不碰正常实弹玩法。
     */
    private static void forceReadyState(Object gd) {
        try {
            if (gd == null) {
                return;
            }
            if (mGunResetStatus != null) {
                mGunResetStatus.invoke(gd);
            }
            if (fGunOverHeat != null && mBoolValueGet != null && mBoolValueSet != null) {
                Object ov = fGunOverHeat.get(gd);
                if (ov != null && Boolean.TRUE.equals(mBoolValueGet.invoke(ov))) {
                    mBoolValueSet.invoke(ov, false);
                }
            }
            if (fGunHeat != null && mDoubleValueGet != null && mDoubleValueSet != null) {
                Object hv = fGunHeat.get(gd);
                if (hv != null) {
                    Object h = mDoubleValueGet.invoke(hv);
                    if (h instanceof Number n && n.doubleValue() > 0.0) {
                        mDoubleValueSet.invoke(hv, 0.0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百七十八·点1】这条后门的唯一入口：检测到创造盒 → 把这一门炮按到"可以开火"。
     *
     * <p>玩家定的口径：「只需要直接走个后门，一检测到创造弹药盒让他强制允许开炮就可以了，
     * 完全不需要做任何的审查」。所以这里不做任何"弹种/换炮"判断，只把
     * {@code VehicleGunItem.canShoot} 那六道闸逐一按到通过：弹匣值、后备弹显示值、状态位、过热度。
     */
    private static void forceAllowShoot(Object gd) {
        forceMagazineFull(gd);
        forceBackupAmmoFull(gd);
        forceReadyState(gd);
    }

    /** 这门炮此刻过不了 {@code canShoot} 时，把每一道闸的值打出来（只给"无限弹"那条后门用）。 */
    private static String shootGateText(Entity mount, Object gd, Entity supplier) {
        StringBuilder sb = new StringBuilder();
        try {
            if (mVehicleGetAmmo != null) {
                sb.append("车侧弹量=").append(mVehicleGetAmmo.invoke(mount, gd)).append(' ');
            }
        } catch (Throwable ignored) {
        }
        sb.append("ammo=").append(intValueText(fGunAmmo, gd, mIntValueGet));
        sb.append(" 后备=").append(intValueText(fGunBackupAmmoCount, gd, mIntValueGet));
        try {
            if (mGunReloading != null) {
                sb.append(" 装填中=").append(Boolean.TRUE.equals(mGunReloading.invoke(gd)));
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mGunCharging != null) {
                sb.append(" 蓄力中=").append(Boolean.TRUE.equals(mGunCharging.invoke(gd)));
            }
        } catch (Throwable ignored) {
        }
        sb.append(" 拉栓=").append(boolValueText(fGunBoltSub, fBoltNeeded, gd, mBoolValueGet));
        sb.append(" 过热=").append(boolValueText(fGunOverHeat, null, gd, mBoolValueGet));
        sb.append(" 热度=").append(doubleValueText(fGunHeat, gd, mDoubleValueGet));
        try {
            if (mGunCanShoot != null) {
                sb.append(" canShoot=").append(Boolean.TRUE.equals(mGunCanShoot.invoke(gd, supplier)));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String intValueText(java.lang.reflect.Field holder, Object gd, Method get) {
        try {
            if (holder == null || get == null) {
                return "?";
            }
            Object v = holder.get(gd);
            if (v == null) {
                return "?";
            }
            Object n = get.invoke(v);
            return n instanceof Number num ? String.valueOf(num.intValue()) : "?";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String boolValueText(java.lang.reflect.Field holder, java.lang.reflect.Field inner,
                                        Object gd, Method get) {
        try {
            if (holder == null || get == null) {
                return "?";
            }
            Object v = holder.get(gd);
            if (inner != null) {
                v = v == null ? null : inner.get(v);
            }
            if (v == null) {
                return "?";
            }
            return String.valueOf(get.invoke(v));
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String doubleValueText(java.lang.reflect.Field holder, Object gd, Method get) {
        try {
            if (holder == null || get == null) {
                return "?";
            }
            Object v = holder.get(gd);
            if (v == null) {
                return "?";
            }
            Object n = get.invoke(v);
            return n instanceof Number num ? fmt2(num.doubleValue()) : "?";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * 【实测七百七十五·点1】开火前"这门炮的备弹三步"——治「坦克主炮始终不使用」。
     *
     * <h2>实机日志（774 那一版，01:51:52 / 01:51:57）</h2>
     * 坦克主炮 {@code Cannon} 只打了两发（弹匣 1 + 装填 100 拍 = 5 秒，正好两发），此后
     * 78 秒里每一轮都只有 {@code MachineGun} / {@code PassengerMachineGun}——
     * 主炮"打空车里那两发就再也没响过"。
     *
     * <h2>为什么（与点1的"弹药盒"是同一个病根的另一半）</h2>
     * M1A2 主炮的 {@code AmmoType} 是**一张表**（{@code large_shell_ap} / {@code large_shell_he}
     * / {@code large_shell_gs}，vehicle json 实证），而 {@code GunData} 同时只有**一个**
     * {@code selectedAmmoConsumer}。旧版装填判据只看当前选中那一种、也从不换弹种：
     * 她带着 HE、炮上选着 AP → HE 搬不进车（识弹失败）、炮也没人会去切到 HE →
     * 这门炮就永远"没有对得上的弹"。
     *
     * <h2>本方法</h2>
     * ① {@link #ensureGunLoaded(Entity, String)}——弹匣空了先从车容器补；
     * ② 仍不能射（{@code canShoot=false}）→ 在这门炮**自己的弹种表**里换一个真有余弹的弹种
     *    （与 745 的 {@code ensureUsableAmmoMode} 同一套账：{@code count(gd, supplier) > 0}）；
     * ③ 换完再补一次弹匣。
     * 三步之后仍不能射 → 记一条「模组坐骑·缺弹」（5 秒/车/炮）：下次日志里直接能看出"是没弹
     * 还是别的原因"，不用再猜。
     */
    private static void prepareGun(Entity mount, String gun) {
        try {
            if (mount == null || gun == null) {
                return;
            }
            if (vehicleHasInfiniteAmmo(mount)) {
                // 【实测七百七十八·点1】后门（玩家口径：「一检测到创造弹药盒让他强制允许开炮就可以了，
                // 完全不需要做任何的审查」）：这里**不再**走 ensureGunLoaded / 换弹种 / 换炮 / 缺弹留痕，
                // 只把这一门炮按到可开火就返回。玩家原话：「除了创造弹药盒以外其他的弹药盒都是可以
                // 正常使用的……除了主炮以外的所有功能都可以正常使用」——所以后门只补"主炮打不响"这一处。
                Object infGd = gunDataOf(mount, gun);
                if (infGd != null) {
                    forceAllowShoot(infGd);
                    Entity sup = ammoSupplierOf(mount);
                    if (mGunCanShoot == null || Boolean.TRUE.equals(mGunCanShoot.invoke(infGd, sup))) {
                        logInfiniteReload(mount);
                    } else {
                        // 走到这里说明还有一道我们没料到的闸（把每一道的值打出来，便于下次一眼定位）。
                        logInfiniteBlocked(mount, gun, infGd, sup);
                    }
                    return;
                }
            }
            ensureGunLoaded(mount, gun);
            Object gd = gunDataOf(mount, gun);
            if (gd == null || mGunCanShoot == null) {
                return;
            }
            Entity supplier = ammoSupplierOf(mount);
            if (Boolean.TRUE.equals(mGunCanShoot.invoke(gd, supplier))) {
                return;
            }
            if (switchGunAmmoType(gd, supplier, gun)) {
                ensureGunLoaded(mount, gd);
                if (Boolean.TRUE.equals(mGunCanShoot.invoke(gd, supplier))) {
                    return;
                }
            }
            logGunDry(mount, gun);
        } catch (Throwable ignored) {
        }
    }

    /** 补弹/开火共用的弹药来源实体：{@code mount.getAmmoSupplier()}（反编译返回车自己）。 */
    private static Entity ammoSupplierOf(Entity mount) {
        try {
            if (mAmmoSupplier != null) {
                Object s = mAmmoSupplier.invoke(mount);
                if (s instanceof Entity e) {
                    return e;
                }
            }
        } catch (Throwable ignored) {
        }
        return mount;
    }

    /**
     * 在这门炮自己的弹种表里换一个"真有余弹"的弹种（775·点1）。
     *
     * <p>判据与引擎 {@code canShoot} 同源：{@link #ammoAvailable}（现成弹匣或
     * {@code selectedAmmoConsumer().count(gd, supplier) > 0}）。一个都换不出来 → 把弹种
     * **还原成原来那个**（不替玩家改选择）并返回 false。
     */
    private static boolean switchGunAmmoType(Object gd, Entity supplier, String gun) {
        try {
            if (gd == null || mChangeAmmoConsumer == null || mGunGetProp == null
                    || fGunPropAmmoConsumer == null) {
                return false;
            }
            Object list = mGunGetProp.invoke(gd, fGunPropAmmoConsumer.get(null));
            int n = sizeOf(list);
            if (n <= 0) {
                return false;
            }
            // 当前选中的是第几项（用于"换不出来就还原"）
            int orig = -1;
            if (mGunSelectedAmmo != null) {
                Object sel = mGunSelectedAmmo.invoke(gd);
                for (int i = 0; i < n; i++) {
                    if (listGet(list, i) == sel) {
                        orig = i;
                        break;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                try {
                    mChangeAmmoConsumer.invoke(gd, i, supplier);
                } catch (Throwable ignored) {
                    continue;
                }
                if (ammoAvailable(gd, supplier)) {
                    com.maidsmart.tool.PromaidLog.log("模组坐骑·弹种",
                            "「" + gun + "」当前弹种无弹 → 切到弹种 #" + i);
                    return true;
                }
            }
            if (orig >= 0) {
                try {
                    mChangeAmmoConsumer.invoke(gd, orig, supplier); // 还原：不替玩家改选中的弹种
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 这一门炮"彻底没弹"时的留痕（5 秒/车/炮，日志搜「模组坐骑·缺弹」）。 */
    private static void logGunDry(Entity mount, String gun) {
        try {
            long now = System.currentTimeMillis();
            String key = mount.m_20148_() + "|" + gun;
            Long last = ADRY_AT.get(key);
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            if (ADRY_AT.size() > 512) {
                ADRY_AT.clear();
            }
            ADRY_AT.put(key, now);
            com.maidsmart.tool.PromaidLog.log("模组坐骑·缺弹", describeKind(mount)
                    + " 炮「" + gun + "」弹匣空、全部弹种都换不出余弹（车容器与她背包都没有对得上的弹）");
        } catch (Throwable ignored) {
        }
    }

    /** 「缺弹」留痕节流表（车+炮 → 上次毫秒）。 */
    private static final java.util.Map<String, Long> ADRY_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code getAmmoSupplier()}（反编译：返回车自己）——补弹时作为弹药来源实体。 */
    private static Method mAmmoSupplier;

    /**
     * 【实测七百四十】弹道瞄准：用 SWB **自己的**弹道解算器算出炮口该朝哪，喂给它的自动瞄准。
     *
     * <h2>玩家原话</h2>
     * 「女仆驾驶直升机在空中的时候，发射的炮弹准度约等于 0。因为前几个版本打的还挺准的，
     * 能不能走后门让那个炮弹强制射向敌方？」
     *
     * <h2>根因（反编译实证，见 {@code VehicleEntity} 与 {@code VehicleWeaponUtils}）</h2>
     * 七百三十一 那一版是**自己动手算角度**写进 {@code setTurretYRot/setTurretXRot}：
     * {@code yaw = atan2(dz,dx)-90}、{@code pitch = -atan2(dy,flat)}。两处根本错误：
     * <ol>
     *   <li><b>炮塔角是"相对车身"的</b>——引擎的 {@code getBarrelVector} 是
     *       {@code 车身变换 × turretYRot/turretXRot}（{@code VehicleVecUtils.getTurretTransform}）。
     *       我们写进去的却是**世界绝对方位**，等于把相对角当绝对角用 → 车一转，炮口就乱指。</li>
     *   <li><b>完全没算弹道</b>——引擎那条链（{@code VehicleWeaponUtils.turretAutoAimFromUuid}）
     *       用 {@code RangeTool.calculateFiringSolution} 解**重力下坠 + 目标提前量**，
     *       我们只对着目标画直线。炮弹是纯抛物线的（{@code CannonShellEntity}，无制导、
     *       无追踪），画直线就是打偏。</li>
     * </ol>
     * <p>而且 {@code vehicleShoot} 的 {@code targetPos} 参数对炮弹**毫无作用**——反编译
     * {@code GunItem.shoot} 实证：只有 {@code MissileProjectile}（制导导弹）读它，
     * 炮弹只按 {@code getShootVec()}（炮管当前朝向）飞。所以"强行让炮弹射向敌方"这件事
     * **只能靠发射前把炮管摆对**，没有第二条路。
     *
     * <h2>本方法</h2>
     * 逐字照搬引擎自己那条链（口径只有一处）：① 按引擎公式取炮位；②
     * {@code calculateFiringSolution}（重力 + 提前量）；③ 交给引擎的
     * {@code turretAutoAimFromVector} / {@code passengerWeaponAutoAimFormVector} 转炮塔——
     * 它们负责"相对角换算 + 转速限幅 + 俯仰/偏航限位"这些我们不该自己重写的东西。
     * 炮塔是**逐拍转过**去的（M1A2 3.3°/拍、Mi-28 15°/拍），所以返回"这一拍是否已经对准"，
     * 调用方**只在对准时才开火**。
     *
     * @return true = 炮口已经对准解算方向（可以开火）
     */
    static boolean aimBallistic(Entity mount, EntityMaid maid, LivingEntity target) {
        try {
            if (mount == null || maid == null || target == null || !target.m_6084_()) {
                return false;
            }
            if (mRangeFiringSolution == null) {
                return false;
            }
            ensureAmmo(mount, maid); // 弹匣空就自己上弹（不然 canShoot 为假、永远打不响）
            // 【实测七百四十五·点4】当前模式（炮/弹种）没弹时，自动换到**有弹的那个模式**——
            // 玩家原话「应该在没有发现链路之后，再检查一下其他模式有没有对应的弹药，然后考虑切换
            // 到那个模式」。换完之后 gunNameFor 会取到新的那一门，所以下面的"瞄"与"打"自动跟上。
            ensureUsableAmmoMode(mount, maid);
            // 【实测七百四十一·点2b/点3】全车只在这里选一次炮，后面"瞄"与"打"共用同一个名字。
            String gun = gunNameFor(mount, maid);
            if (gun == null) {
                return false; // 这车真的没有炮（或反射拿不到）→ 交回旧行为
            }
            Vec3 desired = firingSolution(mount, gun, maid, target);
            if (desired == null) {
                return false;
            }
            boolean aimed = false;
            // 【谁在瞄】炮塔 / 武器站两条链各自只在该位"不由玩家手动瞄"时才动手。
            // 【实测七百四十一】引擎自己那道闸是"控制位坐着 Mob"；多座载具上炮塔位常常空着
            // （Mi-28 炮塔在座 1、她在座 0），照那道闸炮塔永远没人瞄 —— 所以放宽到
            // {@link #isControllerEntity}（位存在、索引有效、且没有玩家占着手动瞄）。
            boolean turretMob = isControllerEntity(mount, mHasTurret, mGetTurretCtrlIdx);
            boolean stationMob = isControllerEntity(mount, mHasWeaponStation, mGetWeaponCtrlIdx);
            if (mTurretAutoAimFromVector != null && turretMob) {
                try {
                    mTurretAutoAimFromVector.invoke(mount, desired);
                    aimed = true;
                } catch (Throwable ignored) {
                }
            }
            if (mPassengerWeaponAutoAimFromVector != null && stationMob) {
                try {
                    mPassengerWeaponAutoAimFromVector.invoke(mount, desired);
                    aimed = true;
                } catch (Throwable ignored) {
                }
            }
            if (!aimed) {
                // 没有可用的自动瞄准链（炮艇：AC-130H 既无炮塔也无武器站）→ 自己把机头摆过去。
                // 这条不是"绕过引擎"，而是**引擎自己也没有别的路**：它的自动瞄准入口只有
                // turret/passengerWeapon 两个，而 AC-130H 那两个位都不存在。见 pointBarrelAt。
                aimed = pointBarrelAt(mount, gun, desired);
            }
            if (!aimed) {
                return false; // 确实没有可瞄的炮 → 交回旧行为
            }
            return alignedWith(mount, gun, desired);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 炮口方向与解算方向的夹角小于这个值就算"对准了"（度）。引擎自己那道闸是 4°。 */
    private static final double AIM_ALIGN_DEG = 3.0;

    /**
     * 【实测七百四十二·点2】"对不准也必须打"的宽限拍数。
     *
     * <p>为什么需要它：{@link #aimBallistic} 只在炮口真的掰到 {@link #AIM_ALIGN_DEG} 以内时
     * 才返回 true，而"炮管方向写在车体坐标里、不能独立转"的车（AC-130H 三门炮、AH-6 机炮、
     * 飞艇炸弹）**只有机头正对敌人**才能达标——机头是被飞行档逐拍控制的，未必掰得到。
     * 玩家要的是"弹药能发射"（原话「大部分载具上的弹药没有办法发射」），不是"必须完美对正"。
     * 所以：连续这么多拍没对准就直接开火（打出去的那发由 {@code MaidShellHoming} 纠向目标，
     * 所以"歪着打"也不会白打）。数值取 40 拍 = 2 秒——够机头/炮塔转一个来回。
     */
    private static final int AIM_FORCE_TICKS = 40;

    /** 连续"没对准"的拍数（按车记；一对准立刻清零）。见 {@link #AIM_FORCE_TICKS}。 */
    private static final java.util.Map<java.util.UUID, Integer> AIM_STALL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百四十二·点2】这辆车的炮**能不能自己转**（有炮塔或武器站）。
     *
     * <p>为什么要问它：{@link #AIM_FORCE_TICKS} 那条"对不准也硬打"的保底**只该给不能自己转的炮**
     * ——AC-130H / AH-6 / 飞艇这些炮管方向写死在车体坐标里的固定炮，不硬打就一辈子不开火。
     * 而能转的炮（M1A2/Mi-28 的炮塔、有武器站的车）只是**转得慢**，多等几拍就能对正；
     * 它们**不该**被硬打（硬打就是把炮弹打进地里，玩家反馈"光棱坦克倒是可以"的那一档
     * 就是"等它转到位再打"的结果）。所以判据是：**没有可转的炮架**才允许硬打。
     */
    private static boolean hasSlewableMount(Entity mount) {
        try {
            if (mount == null) {
                return false;
            }
            return Boolean.TRUE.equals(mHasTurret == null ? null : mHasTurret.invoke(mount))
                    || Boolean.TRUE.equals(mHasWeaponStation == null ? null
                            : mHasWeaponStation.invoke(mount));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 瞄准诊断日志的独立频限表（与其它几条日志互不顶掉）。 */
    private static final java.util.Map<java.util.UUID, Long> AIMLOG_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 记/取"连续没对准"的拍数。
     *
     * @param reset true = 这一拍对准了 → 清零；false = 没对准 → +1
     * @return 累加后的拍数（{@code reset=true} 时为 0）
     */
    private static int aimStalledTicks(Entity mount, boolean reset) {
        try {
            if (mount == null) {
                return 0;
            }
            java.util.UUID id = mount.m_20148_();
            if (reset) {
                AIM_STALL.remove(id);
                return 0;
            }
            Integer n = AIM_STALL.get(id);
            int v = (n == null ? 0 : n) + 1;
            if (AIM_STALL.size() > 512) {
                AIM_STALL.clear(); // 兜底：表不会无限涨（与其它几张表同口径）
            }
            AIM_STALL.put(id, v);
            return v;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 当前炮口方向与期望方向的夹角（度）；拿不到 → -1。 */
    private static double aimErrorDeg(Entity mount, String gun, Vec3 desired) {
        try {
            Vec3 cur = shootVecOfGun(mount, gun);
            if (cur == null || desired == null
                    || cur.m_82556_() < 1.0E-8 || desired.m_82556_() < 1.0E-8) {
                return -1.0;
            }
            double dot = clamp(cur.m_82541_().m_82554_(desired.m_82541_()), -1.0, 1.0);
            return Math.toDegrees(Math.acos(dot));
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    /**
     * 瞄准诊断留痕（节流 5 秒/车）：日志搜「模组坐骑·瞄准」看"到底有没有在瞄、差多少度"。
     *
     * <p>只读——不动 {@link #AIM_STALL}（那个计数只由 {@code tickAttack} 推进，见
     * {@link #aimStalledTicks}），免得诊断日志把"连续没对准"的拍数刷成假的。
     */
    private static void logAim(Entity mount, String gun, boolean aimed, double deg) {
        try {
            long now = System.currentTimeMillis();
            Long last = AIMLOG_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            AIMLOG_AT.put(mount.m_20148_(), now);
            if (AIMLOG_AT.size() > 512) {
                AIMLOG_AT.clear();
            }
            Integer stall = AIM_STALL.get(mount.m_20148_());
            com.maidsmart.tool.PromaidLog.log("模组坐骑·瞄准", describeKind(mount)
                    + " 炮=" + gun + (aimed ? " 已对准" : " 未对准")
                    + " 夹角=" + (long) deg + "°"
                    + " 累计未对准=" + (stall == null ? 0 : stall) + "拍");
        } catch (Throwable ignored) {
        }
    }

    /** 当前炮口方向与期望方向是否已对准（{@link #AIM_ALIGN_DEG}）。 */
    private static boolean alignedWith(Entity mount, String gun, Vec3 desired) {
        try {
            Vec3 cur = shootVecOfGun(mount, gun);
            if (cur == null || cur.m_82556_() < 1.0E-8) {
                return false;
            }
            Vec3 a = cur.m_82541_();
            Vec3 b = desired.m_82541_();
            double dot = clamp(a.m_82554_(b), -1.0, 1.0);
            double deg = Math.toDegrees(Math.acos(dot));
            return deg <= AIM_ALIGN_DEG;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 解算"炮口要朝哪打"——**逐字照搬** {@code VehicleWeaponUtils.turretAutoAimFromUuid}：
     * <pre>
     *   vec3 = getShootPos(她) − getShootVec(她) × |getShootPos(她) − 她的位置|
     *   targetVel = 目标速度 + (0, 目标重力, 0)          // 提前量；玩家再 ×(2,1,2)
     *   return calculateFiringSolution(vec3, 目标包围盒中心, targetVel, 初速, 重力)
     * </pre>
     * 那个 {@code vec3} 不是笔误：引擎把炮口沿炮管**反投影回车身**当作发射点（消除"炮口已经
     * 领先目标一点"的偏差）。我们照抄，才能和引擎自己开火时算得一模一样的解。
     *
     * <p>【实测七百四十一·点2b/点3】四个量全部改走**按炮名**的重载（{@code getShootPos(String,float)}
     * 等）——740 那一版走的是 {@code Entity} 重载，内部按"她坐哪座"解析武器，于是多座载具
     * （Mi-28 炮在座 1、AC-130H 炮在座 1/2/3）里"瞄的炮"与"打的炮"不是同一门。
     * 反投影的距离基准改用**炮口到载具原点**（引擎用的是"炮口到她"；她是乘客时两者只差座位偏移，
     * 但用载具原点更稳——她若是乘客，{@code position()} 就是座位，与载具原点差那一截座位偏移）。
     */
    private static Vec3 firingSolution(Entity mount, String gun, EntityMaid maid, LivingEntity target) {
        try {
            if (gun == null) {
                return null;
            }
            Vec3 muzzle = shootPosOfGun(mount, gun);
            Vec3 dir = shootVecOfGun(mount, gun);
            if (muzzle == null || dir == null) {
                return null;
            }
            double back = muzzle.m_82557_(mount.m_20182_());
            Vec3 launch = muzzle.m_82549_(dir.m_82490_(back));
            Vec3 targetPos = target.m_20191_().m_82399_();
            Vec3 targetVel = target.m_20184_();
            try {
                double g = target.m_21051_(
                        net.minecraft.world.entity.ai.attributes.Attributes.f_22276_).m_22135_();
                targetVel = targetVel.m_82492_(0.0, g, 0.0);
            } catch (Throwable ignored) {
            }
            if (target instanceof net.minecraft.world.entity.player.Player) {
                targetVel = targetVel.m_82520_(2.0, 1.0, 2.0);
            }
            double velocity = projectileVelocityOfGun(mount, gun);
            double gravity = projectileGravityOfGun(mount, gun);
            Object sol = mRangeFiringSolution.invoke(null, launch, targetPos, targetVel, velocity, gravity);
            Vec3 out = sol instanceof Vec3 v ? v : null;
            if (out != null) {
                // 【实测七百四十二】把"这一拍算出来该朝哪"留在按车的表里——诊断日志要用它报
                // "炮口离期望方向还差几度"（{@link #lastDesired}）。纯记录，不影响任何决策。
                LAST_DESIRED.put(mount.m_20148_(), out);
                if (LAST_DESIRED.size() > 512) {
                    LAST_DESIRED.clear();
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 载具 UUID → 最近一次 {@link #firingSolution} 算出的期望炮口方向。只给诊断日志用。 */
    private static final java.util.Map<java.util.UUID, Vec3> LAST_DESIRED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 最近一次算出的期望炮口方向；没有 → null。 */
    private static Vec3 lastDesired(Entity mount) {
        try {
            return mount == null ? null : LAST_DESIRED.get(mount.m_20148_());
        } catch (Throwable ignored) {
            return null;
        }
    }


    /* ---------- 按**炮名**取炮位/炮向/弹道参数（多座载具正解；见 gunNameFor 的注释） ---------- */

    /** {@code getShootPos(String, float)}——**这门炮**的炮口世界位置。 */
    private static Vec3 shootPosOfGun(Entity mount, String gun) {
        try {
            if (mGetShootPosName == null) {
                return null;
            }
            Object v = mGetShootPosName.invoke(mount, gun, 1.0f);
            return v instanceof Vec3 vec ? vec : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code getShootVec(String, float)}——**这门炮**炮管的当前朝向。 */
    private static Vec3 shootVecOfGun(Entity mount, String gun) {
        try {
            if (mGetShootVecName == null) {
                return null;
            }
            Object v = mGetShootVecName.invoke(mount, gun, 1.0f);
            return v instanceof Vec3 vec ? vec : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code getProjectileVelocity(String)}（这门炮的初速；拿不到退回引擎默认 20）。 */
    private static double projectileVelocityOfGun(Entity mount, String gun) {
        try {
            Object v = mGetProjectileVelocityName.invoke(mount, gun);
            if (v instanceof Number n && n.doubleValue() > 0.0) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    /** {@code getProjectileGravity(String)}（这门炮的重力；拿不到退回引擎默认 0.05）。 */
    private static double projectileGravityOfGun(Entity mount, String gun) {
        try {
            Object v = mGetProjectileGravityName.invoke(mount, gun);
            if (v instanceof Number n && n.doubleValue() > 0.0) {
                return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return 0.05;
    }

    /* ---------- 按炮名那一组是唯一路径；下面这些"按乘客/按座位"的旧入口已在 741 删除 ---------- */

    /**
     * 【实测七百四十】开火：{@code vehicleShoot(LivingEntity, UUID, Vec3)}。
     *
     * <p>【为什么 targetPos 传 null】反编译实证：{@code GunItem.shoot} 里那个位置参数**只有
     * {@code MissileProjectile}（制导导弹）读**——它被翻成 {@code setTargetVec} 当航路点；
     * 炮/炮弹一律不读，只按 {@code getShootVec()}（炮管朝向）飞。所以"打中"这件事完全由
     * {@link #aimBallistic} 把炮管摆对来负责，这里不需要再传位置（传了也是白传）。
     *
     * <p>UUID 仍传目标：导弹会因此进入制导，炮弹忽略它。
     *
     * @return true = 这一拍真的把开火调用发出去了
     */
    static boolean fireAt(Entity mount, EntityMaid maid, LivingEntity target) {
        return fireAt(mount, maid, target, null);
    }

    /**
     * 【实测七百七十四·点2】按**指定炮名**开火（{@code gun==null} 时退回"本车主炮"）。
     *
     * <p>全车炮管一起开火时，每一门都走这个入口：先直瞄（{@link #directFireAt}），直瞄不可用
     * 或这门炮是投弹时退回引擎那条 {@code vehicleShoot}。
     */
    static boolean fireAt(Entity mount, EntityMaid maid, LivingEntity target, String gun) {
        try {
            if (mount == null || maid == null || target == null || !target.m_6084_()) {
                return false;
            }
            // 【实测七百七十三·点3】优先走**直瞄**（女仆枪械模式式瞄准）——见 {@link #directFireAt}。
            // 反射不可用 / 这门炮取不到时返回 false，下面原样走引擎那条路。
            if (directFireAt(mount, maid, target, gun)) {
                return true;
            }
            // 【实测七百四十一·点2b/点3】按**炮名**开火（{@code vehicleShoot(LivingEntity,String,UUID,Vec3)}）。
            //
            // 为什么不能用按乘客的那个重载：它走 {@code getSeatIndex(maid)} → {@code getGunData(座号)}
            // → 打的是**她那座**的武器。Mi-28 上她在座 0、炮在座 1，于是"瞄炮塔、打火箭弹"；
            // AC-130H 上座 0 压根没武器，那个重载会在 Kotlin 的 {@code checkNotNull(gunData)} 上
            // **抛 IllegalStateException**（反编译实证）。按炮名则两头都对齐，且它对没有炮塔的车
            // 同样有效（AC-130H 的三门炮就是靠这一条打出去的）。
            //
            // 【为什么 targetPos 仍传 null】反编译 {@code GunItem.shoot} 实证：那个位置参数**只有
            // {@code MissileProjectile}（制导导弹）读**，炮弹一律按炮管当前朝向飞。UUID 照传
            // （导弹因此制导；炮弹忽略）。
            String use = gun != null ? gun : gunNameFor(mount, maid);
            if (use == null || mVehicleShootByName == null) {
                return false;
            }
            mVehicleShootByName.invoke(mount, maid, use, target.m_20148_(), null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 实测七百七十三·点3：直瞄开火（女仆枪械模式式瞄准） ==================== */

    /**
     * 【实测七百七十三·点3】"直瞄开火"这条后门此刻可用吗（反射齐）。
     *
     * <p>供 {@code tickAttack} 的开火闸用：可用时**不再等炮塔转到 {@code AIM_ALIGN_DEG} 以内**——
     * 方向由 {@link #directFireAt} 自己给，打出去的弹不依赖炮塔转到哪。
     */
    static boolean directFireReady() {
        return ctorShootParams != null && mGunDataShootParams != null;
    }

    /**
     * 【实测七百七十三·点3】直瞄开火：**开火那一刻**按我们算出的方向，直接调枪自己的 shoot。
     *
     * <h2>玩家原话（即规格）</h2>
     * 「让酒狐单独开载具，子弹好像从车底打出来的。然后子弹让方块吃了。空中载具好像也一样，
     *  子弹散布很乱，并且都集中在脚下。……女仆使用载具上的武器进行攻击时命中率堪忧。
     *  哪怕走强制的后门也都没有用。能不能改成跟女仆枪械模式一样的瞄准机制呢？」
     *
     * <h2>女仆枪械模式为什么准（反编译实证）</h2>
     * {@code GunItem.shoot(ShootParameters)} → {@code shootBullet}：弹体用传入的
     * {@code shootPosition} 生成、逐字沿传入的 {@code shootDirection} 飞——"往哪打"就是开火
     * 那一刻算出来的方向，中间**没有**"炮塔要慢慢转过去 / 机头要掰过来"的机械环节。
     * 载具那条 {@code vehicleShoot} 用的却是 {@code getShootVec(炮名)} 的**当前**朝向，
     * 于是"没转到位就打 / 转到位了车却又动了"都会让炮弹飞偏——这就是玩家看到的
     * "从车底打出来、打在脚下"。
     *
     * <h2>本方法</h2>
     * 逐字照做：炮口取 {@code getShootPos(炮名)}（与引擎开火同一处），方向取
     * {@link #firingSolution}（引擎自己的 {@code calculateFiringSolution}：重力下坠 + 提前量），
     * 用 SWB 自己的 {@code ShootParameters} 构造后调 {@code GunData.shoot(...)}——弹药扣除、
     * 冷却、过热、音效、后坐全部仍由枪自己那条链处理（与 {@code vehicleShoot} 后半段同源）。
     * 打出去的这一发**一定**沿解算方向飞；炮塔照旧由 {@link #aimBallistic} 慢慢转（纯观感）。
     *
     * <p>投弹（炮名含 Bomb）不走这条：它的物理是"往下丢"，引擎那条 {@code Directions=["Bomb"]}
     * 的口径 + 投弹安全高度那一套更合适，保持原路。
     *
     * @return true = 已按直瞄路径把开火调用发出去了；false = 反射不可用/这门炮取不到/没弹
     *         → 交回引擎原路（{@link #mVehicleShootByName}）
     */
    static boolean directFireAt(Entity mount, EntityMaid maid, LivingEntity target) {
        return directFireAt(mount, maid, target, null);
    }

    /**
     * 【实测七百七十四·点2】按**指定炮名**直瞄开火（{@code gun==null} → 退回"本车主炮"）。
     *
     * <p>多炮齐射时每一门都单独走一遍：炮口/弹道参数/散布全按这一门自己的数据取，
     * 所以"哪一门打哪一门的"不会串。
     */
    static boolean directFireAt(Entity mount, EntityMaid maid, LivingEntity target, String gun) {
        try {
            if (mount == null || maid == null || target == null || !target.m_6084_()
                    || !directFireReady()) {
                return false;
            }
            String use = gun != null ? gun : gunNameFor(mount, maid);
            if (use == null) {
                return false;
            }
            if (isBombGun(use)) {
                return false; // 投弹保持引擎原路（见方法注释）
            }
            Object gd = gunDataOf(mount, use);
            if (gd == null) {
                return false;
            }
            // 【实测七百七十八·点1】创造盒在车里 → 先把这门炮按到可开火（不依赖 prepareGun 先跑）：
            // 弹匣/后备弹写足 + 清装填·拉栓·蓄力 + 过热度归零，见 forceAllowShoot。
            if (vehicleHasInfiniteAmmo(mount)) {
                forceAllowShoot(gd);
            }
            Entity supplier = mount;
            if (mAmmoSupplier != null) {
                try {
                    Object s = mAmmoSupplier.invoke(mount);
                    if (s instanceof Entity e) {
                        supplier = e;
                    }
                } catch (Throwable ignored) {
                }
            }
            // 没弹/没拉栓 → 交回原路：它会去装弹 / 换一个"有弹的模式"（ensureAmmo/ensureUsableAmmoMode）。
            if (mGunCanShoot != null) {
                Object ok = mGunCanShoot.invoke(gd, supplier);
                if (!Boolean.TRUE.equals(ok)) {
                    return false;
                }
            }
            Vec3 muzzle = shootPosOfGun(mount, use);
            Vec3 dir = firingSolution(mount, use, maid, target);
            if (muzzle == null || dir == null || dir.m_82556_() < 1.0E-8
                    || !(mount.m_9236_() instanceof net.minecraft.server.level.ServerLevel sl)) {
                return false;
            }
            Object params = ctorShootParams.newInstance(supplier, maid, sl, muzzle, dir.m_82541_(),
                    gd, gunSpreadOf(gd), true, target.m_20148_(), null);
            mGunDataShootParams.invoke(gd, params);
            sendVehicleShootFx(mount, maid, use);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读这门炮自己的 {@code GunProp.SPREAD}（保持武器手感）；拿不到 → 0（只影响散布，不影响方向）。 */
    private static double gunSpreadOf(Object gd) {
        try {
            if (fGunPropSpread != null && mGunGetProp != null) {
                Object v = mGunGetProp.invoke(gd, fGunPropSpread.get(null));
                if (v instanceof Number n) {
                    return n.doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    /**
     * 补一发"某座开火了"的客户端同步包（枪口火光/动画用的那一条）——引擎的
     * {@code vehicleShoot} 里会发，我们直瞄绕过了它。**尽力而为**：反射拿不到就不发（不影响命中）。
     *
     * <p>炮位索引固定传 0（多炮管武器只是火光位置不轮流；命中与弹药账走的是枪自己那条链）。
     */
    private static void sendVehicleShootFx(Entity mount, EntityMaid maid, String gun) {
        try {
            if (ctorVehicleShootMsg == null || mSendPacketToAll == null) {
                return;
            }
            Object msg = ctorVehicleShootMsg.newInstance(maid.m_20148_(), mount.m_20148_(), 0, gun);
            mSendPacketToAll.invoke(null, msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【实测七百二十四】把她的攻击目标写进**炮塔/武器位的 AI 目标**——车自己那套瞄准开火
     * 读的就是这两个 UUID（反编译 {@code VehicleEntity.baseTick:3751-3768} →
     * {@code turretAutoAimFromUuid} / {@code passengerWeaponAutoAimFormUuid}）。
     *
     * <p>玩家原话：「骑坦克的时候攻击欲望太低了，也不会使用坦克上的炮弹。逻辑应该是和骑马远程
     * 攻击的运动逻辑一样。」——旧版只写 {@code maid.m_6710_}，而车那两条链路**从不读乘客的
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
            String uuid = (target == null) ? "" : target.m_20149_();
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
     * 【实测七百四十一·点2b/点3】这个位能不能由**我们**接管瞄准——比 {@link #isControllerMob}
     * 宽一档：位存在、索引有效、且**没有玩家坐在那里手动瞄**。
     *
     * <p>为什么要放宽：引擎自己只在"控制位坐着 Mob"时才自动瞄准（{@code VehicleEntity.baseTick}
     * 那条 {@code instanceof Mob}）。可卓越前线的多座载具里，炮塔控制位常常**空着**——
     * Mi-28 的炮塔控制器是座 1 而她（驾驶员）在座 0，座 1 没人。照引擎那道闸，炮塔永远没人瞄，
     * 正是"炮弹准度约等于 0"的另一半。我们算得出弹道解，所以这一档由我们接管；唯一必须让开的是
     * **玩家正坐在那个位上手动瞄**（那种情况我们插手就是跟玩家抢炮塔）。
     */
    private static boolean isControllerEntity(Entity mount, Method hasSlot, Method ctrlIdx) {
        try {
            if (hasSlot == null || ctrlIdx == null) {
                return false;
            }
            if (!Boolean.TRUE.equals(hasSlot.invoke(mount))) {
                return false;
            }
            Object idxObj = ctrlIdx.invoke(mount);
            int idx = idxObj instanceof Integer i ? i : -1;
            if (idx < 0) {
                return false;
            }
            Object nth = mGetNthEntity == null ? null : mGetNthEntity.invoke(mount, idx);
            if (nth instanceof net.minecraft.world.entity.player.Player) {
                return false; // 玩家在这个位上手动瞄 → 让开
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百四十一·点3】没有炮塔/武器站时的"机头瞄准"。
     *
     * <h2>为什么炮艇只能这样瞄（反编译实证）</h2>
     * AC-130H 既没有 {@code TurretPos} 也没有 {@code PassengerWeaponStationPos}，所以引擎那两条
     * 自动瞄准链**都不存在**。更要紧的是它的三门炮（M61/Bofors/M102）的
     * {@code ShootPos.Directions = ["Passenger"]}，而按炮名取的 {@code getShootVec(String,float)}
     * 走的是 **2 参** {@code getVectorFromString(String,float)}——查的是 {@code vectorTransform}
     * 那张表，表里**没有 "Passenger"**，于是落到 {@code "Default"} = {@code getViewVector()} =
     * **载具自己的机头方向**（反编译 {@code registerTransforms} 与 {@code getVectorFromString} 实证）。
     * 也就是说：**炮艇的炮弹沿机头飞**——想打中，只能把机头对准敌人。同一条判据也覆盖 AH-6 /
     * A-10 这类"机炮挂在车体、方向是 {@code Vehicle} 变换"的固定炮。
     *
     * <h2>【为什么只"登记"、不在本方法里写】</h2>
     * 调用顺序是 {@code RideBindManager.drive()}：先 {@code tickAttack}（本方法在这里被调），
     * **之后**才 {@code feedNavigation → driveVehicle/driveFlight}——后者每拍都会自己写
     * {@code setYRot}（直升机那档的直写机头、固定翼那档的鼠标通道）。在这里直接写会被下一句
     * 覆盖掉。所以这里只把"该朝哪"登记下来（带几拍时效），由飞行档在**它自己写完之后的最后**
     * 调 {@link #applyNoseAim} 落地（顺序即优先级：最后写的赢）。
     *
     * @return true = 已经登记了瞄准请求
     */
    static boolean pointBarrelAt(Entity mount, String gun, Vec3 desired) {
        try {
            if (mount == null || desired == null || desired.m_82556_() < 1.0E-8) {
                return false;
            }
            Vec3 n = desired.m_82541_();
            // 方向向量 -> MC 的 yaw/pitch：yaw = atan2(-x, z)、pitch = -asin(y)
            // 【SRG 提醒】Vec3 的 x/y/z 在 1.20.1 是 f_82479_/f_82480_/f_82481_（1.21 才是 x/y/z）。
            float wantYaw = (float) Math.toDegrees(Math.atan2(-n.f_82479_, n.f_82481_));
            float wantPitch = (float) Math.toDegrees(-Math.asin(clamp(n.f_82480_, -1.0, 1.0)));
            if (NOSE_AIM.size() > 512) {
                NOSE_AIM.clear(); // 兜底：表不会无限涨（与其它几张表同口径）
            }
            NOSE_AIM.put(mount.m_20148_(), new double[]{wantYaw, wantPitch,
                    (double) (NOW_TICK + NOSE_AIM_TTL_TICKS)});
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 飞行/驾驶档在**自己写完机头之后**调：若这一拍有活的瞄准请求，就把机头按限速摆过去
     * （并连 {@code serverYaw} 一起写，否则被 {@code handleClientSync} 的 lerp 拽回），
     * 同时把鼠标 X/Y 通道归零——否则引擎那一拍还会按旧通道值再转一点，与我们对冲。
     *
     * <p>时效很短（{@link #NOSE_AIM_TTL_TICKS} 拍）：目标一没，{@code aimBallistic} 不再登记，
     * 请求自动过期，飞行档**立刻回到原来的行为**（跟随/盘旋一个字节都不变）。
     */
    static void applyNoseAim(Entity mount) {
        try {
            if (mount == null) {
                return;
            }
            double[] req = NOSE_AIM.get(mount.m_20148_());
            if (req == null) {
                return;
            }
            if ((double) NOW_TICK > req[2]) {
                NOSE_AIM.remove(mount.m_20148_()); // 过期 → 交回飞行档自己控
                return;
            }
            float wantYaw = (float) req[0];
            float wantPitch = (float) req[1];
            float yaw = wrapDegrees(mount.m_146908_()
                    + clamp(wrapDegrees(wantYaw - mount.m_146908_()), -NOSE_AIM_MAX_DEG, NOSE_AIM_MAX_DEG));
            float pitch = clamp(mount.m_146909_()
                    + clamp(wantPitch - mount.m_146909_(), -NOSE_AIM_MAX_DEG, NOSE_AIM_MAX_DEG),
                    -NOSE_AIM_PITCH_LIMIT, NOSE_AIM_PITCH_LIMIT);
            mount.m_146922_(yaw);
            mount.m_146926_(pitch);
            if (mSetServerYaw != null) {
                try {
                    mSetServerYaw.invoke(mount, yaw);
                } catch (Throwable ignored) {
                }
            }
            mSetMouseX(mount, 0.0f); // 清掉飞行档刚写的鼠标通道，免得它这一拍再转一点与我们对冲
            mSetMouseY(mount, 0.0f);
        } catch (Throwable ignored) {
        }
    }

    /** 载具 UUID → [期望 yaw, 期望 pitch, 过期 tick]。见 {@link #pointBarrelAt}。 */
    private static final java.util.Map<java.util.UUID, double[]> NOSE_AIM =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 机头瞄准请求的时效（拍）：够盖住"登记→飞行档落地"这一拍，又不足以在脱战后继续抢机头。 */
    private static final int NOSE_AIM_TTL_TICKS = 3;

    /**
     * "当前刻"——由 {@code RideBindManager.drive} 每拍刷新（见 {@link #markTick}）。用它而不是各自
     * 读世界时间，是为了在"客户端/服务端都加载本类"的前提下不依赖任何一侧的 level 状态。
     */
    private static volatile long NOW_TICK = 0L;

    /** 刷新"当前刻"（{@code RideBindManager.drive} 每拍调一次）。 */
    public static void markTick(long tick) {
        NOW_TICK = tick;
    }

    /** 机头瞄准每拍最大转角（度）：8 ≈ 160°/秒，够追上目标又不瞬转。 */
    private static final float NOSE_AIM_MAX_DEG = 8.0f;
    /** 机头瞄准的俯仰限幅（度）：别让"追一个脚下的目标"把飞机压成俯冲。 */
    private static final float NOSE_AIM_PITCH_LIMIT = 45.0f;

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
            if (!(container instanceof net.minecraftforge.items.IItemHandler inv)) {
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
            // 【实测七百七十六·点1】创造模式弹药盒留痕：它一进车容器，SWB 自己就把这辆车当
            // "有无限弹"（见 stackIsWantedAmmo 的说明），不需要我们再搬任何实弹。
            if (containerHasCreativeAmmoBox(inv)) {
                logCreativeAmmo(mount);
            }
            return moved;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 收起这辆车上**每一门武器的弹药判据**（{@code AmmoConsumer} 实例）。
     *
     * <p>【实测七百四十一·点3】过滤条件从"只看 {@code useBackpackAmmo()} 的枪"放宽到**所有枪**。
     *
     * <h2>为什么（玩家原话：「还有个空中炮艇，但是女仆不会填充和发射炮弹」）</h2>
     * 反编译 {@code GunData.countBackupAmmo(Entity)} / {@code reloadAmmo(Entity,boolean)} 实证：
     * **两类枪的弹都来自"车的容器"**——
     * <ul>
     *   <li><b>背包弹型</b>（{@code MAGAZINE <= 0}，{@code useBackpackAmmo() == true}）：
     *       每发都直接 {@code countBackupAmmo} 从容器数（AC-130H 的 M61、Mi-28 的 Cannon）；</li>
     *   <li><b>弹匣型</b>（{@code MAGAZINE > 0}）：打完由 {@code reloadAmmo} 从
     *       {@code countBackupAmmo}（**同一个容器**）扣物品填进 {@code ammo}——
     *       AC-130H 的 Bofors（弹匣 12）、M102（弹匣 1）都是这一类。</li>
     * </ul>
     * 旧版把弹匣型整个跳过，于是她的 {@code medium_shell_he} / {@code large_shell_he} 永远进不了车，
     * 那两门炮**永远装不进弹**。现在两类都收：只要 {@code selectedAmmoConsumer().stack()} 是
     * 一件**真实的物品**（能量/空判据自然是空 stack，会被挡掉）就收进来。
     */
    private static java.util.List<Object> wantedConsumers(Entity mount) {
        java.util.List<Object> out = new java.util.ArrayList<>(4);
        try {
            Object mapObj = mGetGunDataMap.invoke(mount);
            if (!(mapObj instanceof java.util.Map<?, ?> map) || map.isEmpty()) {
                return out;
            }
            for (Object gd : map.values()) {
                if (gd == null || mGunSelectedAmmo == null) {
                    continue;
                }
                Object consumer = mGunSelectedAmmo.invoke(gd);
                if (consumer == null || mConsumerIsAmmoItem == null) {
                    continue;
                }
                // 没实体弹的判据（EMPTY/INVALID/ENERGY）直接跳过——它们的 stack() 是空的，
                // 搬进去也没意义。（isAmmoItem 对空 stack 的语义各家实现不一，这里显式挡一道。）
                Object st = consumerStack(consumer);
                if (st instanceof net.minecraft.world.item.ItemStack iss && !iss.m_41619_()) {
                    out.add(consumer);
                }
                // 【实测七百七十五·点1】把这一门炮的**全部弹种**也收进来（不只是当前选中的那个）。
                // 旧版只收 selectedAmmoConsumer：M1A2 主炮选着 AP、她带着 HE 时，那摞 HE 永远
                // 搬不进车（实机日志：主炮打两发就再无 Cannon——车里原有的打光了，她的 HE 进不来）。
                if (mGunGetProp != null && fGunPropAmmoConsumer != null) {
                    Object list = mGunGetProp.invoke(gd, fGunPropAmmoConsumer.get(null));
                    int n = sizeOf(list);
                    for (int i = 0; i < n; i++) {
                        Object c = listGet(list, i);
                        if (c == null || out.contains(c)) {
                            continue;
                        }
                        Object st2 = consumerStack(c);
                        if (st2 instanceof net.minecraft.world.item.ItemStack is2 && !is2.m_41619_()) {
                            out.add(c);
                        }
                    }
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
    private static net.minecraftforge.items.IItemHandler availableInv(EntityMaid maid) {
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
    private static int moveFrom(net.minecraftforge.items.IItemHandler from,
                                java.util.List<Object> consumers,
                                net.minecraftforge.items.IItemHandler into) {
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
                if (one.m_41619_()) {
                    continue;
                }
                // ItemHandlerHelper.insertItem 会自己找空位/合并；返回的剩余非空 = 塞不下
                net.minecraft.world.item.ItemStack rest =
                        net.minecraftforge.items.ItemHandlerHelper.insertItem(into, one, false);
                if (!rest.m_41619_()) {
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
     *
     * <p>【实测七百七十四·点1】在旧判据之上补**盒装弹**与**弹药盒**（玩家原话：「女仆给载具装填
     * 没有办法识别弹药盒，她只认现成的弹药」）：
     * <ul>
     *   <li><b>盒装弹</b>（{@code RifleAmmoBoxItem} 等，继承 {@code AmmoSupplierItem}、自带型号）：
     *       问 {@code getType()} 是不是某个 consumer 的 {@code getPlayerAmmoType()}；</li>
     *   <li><b>通用弹药盒</b>（{@code AmmoBoxItem}，弹药存在物品自身数据里）：问
     *       {@code Ammo.get(stack) > 0} 且型号对得上。</li>
     * </ul>
     * 两类搬进车容器后都由 SWB 自己的 {@code InventoryTool.countAmmoItem/consumeAmmoItem}
     * 计数与消耗（反编译实证），所以"搬进去"这一步就够了，不需要我们替它拆盒。
     *
     * <p>【实测七百七十六·点1】再加一类：**创造模式弹药盒**（玩家原话：「我给女仆的是创造模式
     * 弹药盒。但是他还是不会使用主炮。」）。它不需要型号——SWB 自己就是这么判的（反编译
     * {@code InventoryTool.hasCreativeAmmoBox}：{@code hasItem(车, 创造弹药盒)} 为真时，
     * {@code GunData.countBackupAmmo} 直接返回 {@code Integer.MAX_VALUE}、
     * {@code consumeBackupAmmo} 直接不扣）。所以"正解"与实弹完全一样：**把它搬进车容器**
     * ——搬进去那一刻起，这辆车所有武器就是无限弹。
     */
    private static boolean stackIsWantedAmmo(net.minecraft.world.item.ItemStack stack,
                                             java.util.List<Object> consumers) {
        try {
            if (stack == null || stack.m_41619_() || consumers.isEmpty()) {
                return false;
            }
            // ⓪ 创造模式弹药盒（不受型号限制，见方法注释）
            if (isCreativeAmmoBox(stack)) {
                return true;
            }
            // ① 散装弹 / 枚举型武器的代表弹（旧判据，原样保留）
            if (mConsumerIsAmmoItem != null) {
                for (Object consumer : consumers) {
                    Object ok = mConsumerIsAmmoItem.invoke(consumer, stack);
                    if (Boolean.TRUE.equals(ok)) {
                        return true;
                    }
                }
            }
            // ② 盒装弹 / ③ 弹药盒：按"型号 + 里面有没有弹"判（见方法注释）
            net.minecraft.world.item.Item item = stack.m_41720_();
            boolean boxed = cAmmoSupplier != null && cAmmoSupplier.isInstance(item);
            boolean box = cAmmoBoxItem != null && cAmmoBoxItem.isInstance(item);
            if (!boxed && !box) {
                return false;
            }
            for (Object consumer : consumers) {
                Object want = playerAmmoTypeOf(consumer);
                if (want == null) {
                    continue; // 物品型武器（炮弹类）：没有 Ammo 型号，盒装弹对它无意义
                }
                if (boxed && mSupplierGetType != null) {
                    Object type = mSupplierGetType.invoke(item);
                    if (want.equals(type)) {
                        return true;
                    }
                }
                if (box && mAmmoGetStack != null) {
                    Object n = mAmmoGetStack.invoke(want, stack);
                    if (n instanceof Number num && num.intValue() > 0) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百七十六·点1】是不是卓越前线的「创造模式弹药盒」。
     *
     * <p>判据用**物品类名**而不是 id/注册表：{@code CreativeAmmoBoxItem} 是 SWB 自己的类名，
     * 两个版本（1.21.1 / 1.20.1）的 SWB 0.8.9.1 都是这个名字，跨加载器也稳
     * （id 反而可能被整合包改命名空间）。
     */
    private static boolean isCreativeAmmoBox(net.minecraft.world.item.ItemStack stack) {
        try {
            return stack != null && !stack.m_41619_()
                    && stack.m_41720_().getClass().getName().contains("CreativeAmmoBoxItem");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 车容器里有没有创造模式弹药盒（有 = SWB 按"无限弹"算，见 {@link #stackIsWantedAmmo}）。 */
    private static boolean containerHasCreativeAmmoBox(net.minecraftforge.items.IItemHandler inv) {
        try {
            if (inv == null) {
                return false;
            }
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isCreativeAmmoBox(inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百七十七·点1】这辆车此刻是不是"无限弹"（车容器里有创造模式弹药盒）。
     *
     * <p>判据直接用**引擎自己的** {@code InventoryTool.hasCreativeAmmoBox(车)}（反编译
     * {@code InventoryTool:296}，内部读乘客手持 + 车容器）；反射拿不到就退回扫车容器
     * （{@link #containerHasCreativeAmmoBox}）。为真时 {@link #ammoAvailable} 恒真、
     * {@link #forceMagazineFull} 每轮开火前把弹匣写满——两者都走引擎的原账，没有虚拟弹药。
     */
    private static boolean vehicleHasInfiniteAmmo(Entity mount) {
        try {
            if (mount == null) {
                return false;
            }
            if (mHasCreativeAmmoBoxEntity != null) {
                Object ok = mHasCreativeAmmoBoxEntity.invoke(null, mount);
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
                return false; // 引擎的判据就是权威（含"乘客手持盒 + 车型开关"那一支）
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mGetInventory != null) {
                Object inv = mGetInventory.invoke(mount);
                if (inv instanceof net.minecraftforge.items.IItemHandler handler) {
                    return containerHasCreativeAmmoBox(handler);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 创造弹药盒进车留痕（节流 5 秒/车）：日志搜「创造弹药盒」。 */
    private static void logCreativeAmmo(Entity mount) {
        try {
            long now = System.currentTimeMillis();
            Long last = ACRE_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            ACRE_AT.put(mount.m_20148_(), now);
            if (ACRE_AT.size() > 256) {
                ACRE_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("创造弹药盒", describeKind(mount)
                    + " 车容器里有创造模式弹药盒 → 全车武器按\u201c无限弹\u201d算"
                    + "（SWB 自己的判据 InventoryTool.hasCreativeAmmoBox：不消耗、也不用再搬实弹）");
        } catch (Throwable ignored) {
        }
    }

    /** 创造弹药盒日志的独立频限表（与其它几条日志互不顶掉）。 */
    private static final java.util.Map<java.util.UUID, Long> ACRE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百七十七·点1 / 七百七十八·点1】后门生效留痕（节流 5 秒/车）：日志搜「无限弹开炮」。
     *
     * <p>它回答"后门到底有没有把炮按响"：看到它 + 主炮有火光/音效 = 修好；
     * 若只看到「无限弹受阻」，那一行会把每道闸的值都带出来（见 {@link #shootGateText}）。
     */
    private static void logInfiniteReload(Entity mount) {
        try {
            long now = System.currentTimeMillis();
            Long last = AINF_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            AINF_AT.put(mount.m_20148_(), now);
            if (AINF_AT.size() > 256) {
                AINF_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("无限弹开炮", describeKind(mount)
                    + " 检测到创造模式弹药盒 → 强制允许开炮"
                    + "（弹匣/后备弹写足 + 清装填·拉栓·蓄力 + 过热度归零，不走任何缺弹审查）");
        } catch (Throwable ignored) {
        }
    }

    /** 「无限弹开炮」日志的独立频限表。 */
    private static final java.util.Map<java.util.UUID, Long> AINF_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【实测七百七十八·点1】后门按不动时的留痕（节流 5 秒/车+炮）：日志搜「无限弹受阻」。
     *
     * <p>它只在"我们已经把弹量和状态位都按平了、{@code canShoot} 仍然是 false"时出现，
     * 并把 {@link #shootGateText} 的逐闸读数贴在后面——下次日志里一眼就能看出还剩哪一道闸。
     */
    private static void logInfiniteBlocked(Entity mount, String gun, Object gd, Entity supplier) {
        try {
            long now = System.currentTimeMillis();
            String key = mount.m_20148_() + "|" + gun;
            Long last = ABLK_AT.get(key);
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            ABLK_AT.put(key, now);
            if (ABLK_AT.size() > 256) {
                ABLK_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("无限弹受阻", describeKind(mount)
                    + " 炮「" + gun + "」已按平弹量/状态位，canShoot 仍为假：" + shootGateText(mount, gd, supplier));
        } catch (Throwable ignored) {
        }
    }

    /** 「无限弹受阻」日志的独立频限表（车+炮）。 */
    private static final java.util.Map<String, Long> ABLK_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code AmmoConsumer.getPlayerAmmoType()}（枚举型武器才有；物品型恒 null）→ Ammo 或 null。 */
    private static Object playerAmmoTypeOf(Object consumer) {
        try {
            return mConsumerPlayerAmmoType == null ? null : mConsumerPlayerAmmoType.invoke(consumer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 装弹留痕（节流 5 秒/车）：日志搜「模组坐骑·装弹」。 */
    private static void logVehicleAmmo(Entity mount, int moved) {
        try {
            long now = System.currentTimeMillis();
            Long last = AFEED_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            AFEED_AT.put(mount.m_20148_(), now);
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
            // 【实测七百五十七】开火闸：只打"活敌人"（见 {@link LiveThreat}）——TLM 的 canAttack
            // 对中立生物是**永久记仇**（原版"最近打过谁"的字段一旦写上就不清空），于是主人很久
            // 以前打过一下的马永远合法。玩家实测：车上的女仆对着场上的马开炮
            // （日志 `模组坐骑·炮位 炮塔目标=horse` / `模组坐骑·开火 → horse`）。
            if (target != null && !LiveThreat.live(maid, target)) {
                target = null;
            }
            if (k == Kind.VEHICLE) {
                // 【实测七百四十一·点2b/点3】这一拍由谁开火——**判据只有一条**：
                // 该用的那门炮（gunNameFor）是不是**就在她坐的那一座**上。
                //
                //   · 是（prism_tank 座 0 的激光、M1A2 座 0 的 120mm、AH-6 座 0 的机炮）→
                //     SWB 内置那条"Mob 乘客自动开火"打的正是这门炮，**它与我们瞄的是同一门**，
                //     一个字都不用改（玩家反馈"地上的光棱坦克倒是可以"就是这一档），
                //     所以我们**不抢**、也不重复开火。
                //   · 不是（Mi-28 的炮塔在座 1、她在座 0；AC-130H 三门炮在座 1/2/3、座 0 没武器）
                //     → 引擎要么打**别的一门**（Mi-28：瞄机炮打火箭弹）、要么**压根打不出**
                //     （AC-130H：座 0 没 gun → 引擎那条循环直接跳过）。这一档由我们接管：
                //     先把她的 `getTarget` **清掉**（那是引擎自动开火的唯一开关，不清就是两门炮
                //     各打各的），再按炮名统一"瞄 + 打"。
                // 【实测七百四十二·点2】不再"按炮在谁座上分两档"——**一律由我们接管**。
                //
                // 741 那一版把"开火权"按"这门炮是不是就在她坐的那一座"劈成两半：是 → 交给引擎
                // 内置的"Mob 乘客自动开火"；不是 → 我们接管。实机日志证明这一劈是错的：
                // **741 那份 jar（2026-09-30 17:2x 那一局）里「模组坐骑·开火」一行都没有**，
                // 而同一辆车在 740 那份 jar 下（同一天 06:18~13:08）每 5 秒都在打。
                //
                // 根因在引擎那条链自己的一道闸：反编译 {@code VehicleEntity.baseTick:3765-3775}，
                // 它要求 {@code angleTo(炮管当前朝向, 炮口→目标) < 4.0} 才开火；而**机炮挂车体、
                // 没有炮塔**的车（AH-6 的 Cannon、飞艇的 Bomb、AC-130H 的三门炮）炮管方向是
                // 写死的车体坐标（{@code Directions} 不是 {@code "Barrel"} 而是具体向量/字符串），
                // 它**不能独立转**——除非机头恰好正对敌人，那道 4° 闸永远不过。
                // 也就是说"炮正好在她座上"这一档其实**一枪不发**（玩家原话「大部分载具上的
                // 弹药没有办法发射」正是它）。而 740 那版不分档、一律接管，所以它能打。
                //
                // 现在恢复并固化 740 的口径：**不分档**。一律把她的 {@code getTarget} 清掉
                // （原意是掐掉引擎自动开火的开关；【实测七百七十四·更正】这招对 TLM 其实无效，
                // 见下面 setTarget 那几行——引擎那条链一直开着，这里只是"能清就清"），
                // 瞄准与开火都走我们这条路。
                String gun = gunNameFor(mount, maid);
                // 【实测七百七十四·更正】这行 setTarget(null) 对 TLM **不起作用**：javap 实证
                // {@code EntityMaid.getTarget()} 被覆写成"读 brain 的 ATTACK_TARGET 记忆"，
                // 不读实体自身那个 target 字段——所以引擎那条"Mob 乘客自动开火"其实一直开着
                // （它按 RPM 打她那一座**当前选中**的炮，且要求炮口与目标夹角 < 4°）。
                // 保留这行只为兼容"别的实现读实体 target"的场合，清不掉引擎那条链；
                // 多炮齐射（本类）与它是并行关系，主炮偶尔会两侧各打一发（RPM 节流后不失控）。
                try {
                    maid.m_6710_(null);
                } catch (Throwable ignored) {
                }
                // 【实测七百二十四】再把目标写进炮塔/武器位的 **AI 目标 UUID**——车自己那套
                // **瞄准**读的是它（它只瞄、不开火，与我们算的是同一个解，互补不冲突）。
                applyVehicleAiTargets(mount, target);
                logVehicleFire(mount, target);
                // 【实测七百三十一·暴力后门 → 七百四十·真弹道 → 七百四十一·按炮名统一
                //  → 七百四十二·不再分档 + 末制导兜底】
                // 玩家原话：「女仆驾驶直升机在空中的时候，发射的炮弹准度约等于 0。……能不能走后门
                // 让那个炮弹强制射向敌方？」
                //
                // 731 那条后门是"自己算方位角写死炮塔 + 不看角度直接开火"——反编译实证它有两处
                // 根本错误：① 炮塔角是**相对车身**的，我们写的却是世界绝对方位；② 完全没解
                // 重力下坠与提前量（炮弹是纯抛物线，无制导，`targetPos` 参数对它毫无作用）。
                // 740 换成**引擎自己的弹道解算器**。本版沿用"瞄与打统一到同一个炮名"，
                // 并加两条保证"一定能打出去、且一定能命中"：
                //   ① **对不准也打**——机头/炮塔迟迟掰不到 3° 以内（炮艇、AH-6 那类）时，
                //      连续 {@link #AIM_FORCE_TICKS} 拍没对准就直接开火，不再"等到对准为止"；
                //   ② 打出去的每一发都由 {@code MaidShellHoming} 记下目标并逐拍纠向
                //      （玩家点名要的那条"强力后门"：炮弹直接指向敌人）。
                if (target != null && gun != null) {
                    // 【实测七百七十四·点2】"多种炮管都满足就一起开火"：把全车每一门炮都列出来，
                    // 一门一条扳机（玩家占着的座整座跳过）。取不到（反射缺）就退回只有主炮那一门。
                    java.util.List<String> guns = gunsFor(mount, maid);
                    if (guns.isEmpty()) {
                        guns = java.util.Collections.singletonList(gun);
                    }
                    // 瞄准（观感 + 引擎原路的对准闸）只对**主炮**做一次：直瞄那一档每一门炮
                    // 都有自己的解算方向（directFireAt 内部按炮名算），不需要等炮塔转到位。
                    boolean aimed = aimBallistic(mount, maid, target);
                    Vec3 want = lastDesired(mount);
                    logAim(mount, gun, aimed, aimErrorDeg(mount, gun, want));
                    if (aimed) {
                        aimStalledTicks(mount, true);
                    }
                    // 【实测七百四十二·点2】"对不准也必须打"的保底：只服务**引擎原路**
                    //（投弹 / 直瞄反射不可用）。有可转炮架的车继续等它对正。
                    boolean force = !aimed && !hasSlewableMount(mount)
                            && aimStalledTicks(mount, false) >= AIM_FORCE_TICKS;
                    StringBuilder fired = new StringBuilder();
                    for (String g : guns) {
                        boolean bomb = isBombGun(g);
                        // 【实测七百四十七】投弹安全高度闸：没爬到位就**只按住炸弹这一门**，
                        // 车上别的炮（机炮/机枪）照打；非飞行载具这门闸恒不拦。
                        if (bomb && com.maidsmart.combat.MaidAirCombat.holdDropForStandoff(maid, target)) {
                            continue;
                        }
                        // 【实测七百七十四·点3】逐炮按自己的 RPM 定节奏（fireTickDue 内部算间隔）；
                        // 投弹那一门在 RPM 之上还加 2 秒最小间隔（见 BOMB_MIN_INTERVAL_TICKS）。
                        if (!fireTickDue(mount, g)) {
                            continue;
                        }
                        boolean ok;
                        if (!bomb && directFireReady()) {
                            // 直瞄：不等炮塔转到位，方向由 directFireAt 自己给（773 那条）。
                            prepareGun(mount, g); // 弹匣空/弹种错就自己上弹（不然这门永远打不响）
                            ok = directFireAt(mount, maid, target, g);
                        } else {
                            // 引擎原路：投弹 / 直瞄反射不可用。照旧等"对准或保底硬打"。
                            if (!aimed && !force) {
                                continue;
                            }
                            prepareGun(mount, g);
                            ok = fireAt(mount, maid, target, g);
                        }
                        if (ok) {
                            if (fired.length() > 0) {
                                fired.append('+');
                            }
                            fired.append(g);
                        }
                    }
                    if (fired.length() > 0) {
                        logForceFire(mount, fired.toString(), target);
                        // 【实测七百四十二·点3】记下"这一发该打谁"，由 MaidShellHoming
                        // 逐拍把她的炮弹纠向目标（末制导后门）。
                        com.maidsmart.combat.MaidShellHoming.note(maid, mount, target);
                    }
                }
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
            //
            // 【实测七百七十】模组仆从坐骑（无鞍可骑仆从）**排除在本档之外**：那一类走"单独一个
            // 区间"——行动逻辑全归它自己的 AI（含它自己的锁敌 SummonTargetGoal），本模组只赋速度，
            // **不再替它写目标**（写了反而会覆盖它自己选的敌人）。见 MaidRideKit.servantAutoEnabled。
            if (mount instanceof net.minecraft.world.entity.Mob mob) {
                try {
                    if (MaidRideKit.servantAutoEnabled() && MaidRideKit.isNoSaddleRideable(mob)) {
                        return;
                    }
                    // 【实测七百六十六·后门】先把诡厄仆从的"目标优先闸"打开（只在诡厄仆从上有：
                    // 见 MaidGoetyCompat.openPriorityGate 的完整口径）——不开的话，守区仆从的
                    // setTarget 会把区域外的目标**静默吞掉**，它自己的战斗 AI 从此一片死寂。
                    com.maidsmart.goety.MaidGoetyCompat.openPriorityGate(mob);
                    mob.m_6710_(target);
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
            java.util.Optional<LivingEntity> mem = maid.m_6274_().m_21952_(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
            if (mem != null && mem.isPresent()) {
                LivingEntity le = mem.get();
                if (le != null && le.m_6084_()) {
                    return le;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return maid.m_5448_();
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
            Long last = LOG_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            LOG_AT.put(mount.m_20148_(), now);
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
            Long last = VFIRE_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            VFIRE_AT.put(mount.m_20148_(), now);
            if (VFIRE_AT.size() > 512) {
                VFIRE_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·炮位", describeKind(mount)
                    + " 炮塔目标=" + (target == null ? "无"
                            : String.valueOf(target.m_6095_()).replace("entity.minecraft.", "")));
        } catch (Throwable ignored) {
        }
    }

    /* 【实测七百七十四·点3】开火节流：**按每一门炮自己的 RPM** 算间隔（不再是固定 5 拍）。
     *
     * 玩家原话：「只有女仆在枪械模式下发射炮弹的频率是最高的，如果选择正常的攻击模式以及其他的
     * 模式，那频率远远不如枪械高，我不知道这是为什么。」——根因就是旧版这行固定节流：
     * 5 拍 = 4 发/秒，与武器无关；而引擎自己那条（Mob 乘客自动开火）用的是
     * {@code tickCount % ceil(20 / (rpm/60))}（反编译 {@code VehicleEntity:3903}）。于是
     * RPM=600 的车载机枪被压到 4/s（M1A2 的 MachineGun 真实 10/s、AC-130H 的 M61 真实 20/s），
     * 而"枪械模式"下她自己的手持枪不受这条节流 → 看起来"只有枪械模式最快"。
     *
     * <p>现在逐字照搬引擎公式：{@code interval = ceil(20 / (rpm / 60)) = ceil(1200 / rpm)} 拍，
     * rpm 取 {@code vehicleWeaponRpm(炮名)}（缺 RPM 的炮按引擎 Entity 重载同口径退 60）。
     * 投弹（炮名含 Bomb）另加一道最小间隔（见 {@link #BOMB_MIN_INTERVAL_TICKS}）。 */
    private static final java.util.Map<String, Integer> GFIRE_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 缺 RPM 时的退路：与反编译 {@code vehicleWeaponRpm(Entity)} 的 {@code return 60} 同口径。 */
    private static final int FIRE_RPM_FALLBACK = 60;

    /** 【实测七百七十四·点4】投弹最小间隔（拍）= 40 拍 = 2 秒。
     *
     * <p>玩家原话：「女仆在使用洛基夫空艇的时候应该要有一个最小投弹间隔的，现在不停的连续投
     * 直接把飞船自己给炸了。」基洛夫的 {@code Bomb}：RPM 60、弹匣 7、爆炸半径 42，而飞船自己的
     * {@code DamageModifiers} 里对航空炸弹是 {@code *3}（反编译数据实证）——旧版 5 拍一发，
     * 1.75 秒把 7 发全丢光，飞船还在原地就被自己炸死。现在两发之间至少隔 2 秒（飞机会飞离上一发
     * 的落点），弹匣打空后照旧走引擎自己的 5 秒装填。 */
    private static final int BOMB_MIN_INTERVAL_TICKS = 40;

    /** 这门炮两发之间至少要等几拍（RPM → 拍；投弹再加最小间隔）。
     *
     * <p>【实测七百七十九·点1】再加一道**这门炮自己设计的循环**：玩家原话「我们给的后门似乎有点
     * 太过了，导致坦克发射一点间隔都没有。需要有一个内定的间隔的。根据坦克自身的属性来定。」
     * ——完全正确。创造盒那条后门每拍都会 {@code resetStatus()}（清装填）把弹匣写满，于是
     * 「打一发 → 装填 100 拍」这段**引擎自己的战车炮循环被抹掉了**，只剩 RPM 那一道：
     * 而 M1A2 / T-90A / ZTZ-99A 的 {@code Cannon} **vehicle json 里压根没有 RPM 字段**
     * （只有 {@code "Magazine": 1, "EmptyReloadTime": 100}，m_1a_2.json 实证），缺 RPM 时
     * 退到引擎自己的 60 ⇒ 20 拍 = **1 秒一发**——主炮看起来就是"一点间隔都没有"。
     *
     * <p>所以这里把**炮自己的属性**并进来：一匣 {@code Magazine} 发、打完要
     * {@code EmptyReloadTime} 拍 ⇒ 它自己设计的持续射速上限是
     * {@code EmptyReloadTime / Magazine} 拍一发（主炮 = 100/1 = 100 拍 = 5 秒，与它
     * "打一发装 5 秒"的设计完全一致）。与 RPM 那道**取大**：Bofors（Magazine 12 /
     * EmptyReloadTime 40 / RPM 180）仍由 RPM 的 7 拍说了算，MachineGun（无 Magazine）
     * 仍是 RPM 600 → 2 拍。两值都拿不到（默认 0）→ 一个字节不变。
     *
     * <p>注意：玩家给的**实弹**玩法里引擎自己的装填闸还在，本项只会是"更慢的那一个"，
     * 不会凭空加速——它补的是后门把装填抹掉之后**丢掉的那半条循环**。 */
    private static int fireIntervalTicks(Entity mount, String gun) {
        int rpm = FIRE_RPM_FALLBACK;
        try {
            if (mVehicleWeaponRpmName != null && gun != null) {
                Object v = mVehicleWeaponRpmName.invoke(mount, gun);
                if (v instanceof Integer i && i > 0) {
                    rpm = i;
                }
            }
        } catch (Throwable ignored) {
        }
        int t = (int) Math.ceil(1200.0 / Math.max(1, rpm));
        t = Math.max(1, Math.min(t, 1200));
        // 【实测七百七十九·点1】这门炮自己的"一匣 + 装填"循环（见上）。
        int cycle = gunMagazineCycleTicks(mount, gun);
        if (cycle > t) {
            t = cycle;
        }
        if (isBombGun(gun)) {
            t = Math.max(t, BOMB_MIN_INTERVAL_TICKS);
        }
        return t;
    }

    /**
     * 【实测七百七十九·点1】这门炮"一匣打完 + 装满一匣"的固有拍数
     * （{@code ceil(EmptyReloadTime / Magazine)}）；这门炮没有弹匣/装填属性 → 0。
     *
     * <p>读的是 {@code GunData.get(GunProp.MAGAZINE / EMPTY_RELOAD_TIME)}——与
     * {@link #forceMagazineFull} 读 MAGAZINE 是同一个口径（那两处必须一致：后门写满的是
     * 同一个 Magazine 值）。结果按 {@code uuid|炮名} 缓存：这两个值由 vehicle json 决定、
     * 开车过程中不会变，而 fireIntervalTicks 是**每拍每炮**都要问一次的。
     */
    private static int gunMagazineCycleTicks(Entity mount, String gun) {
        if (mount == null || gun == null) {
            return 0;
        }
        String key = mount.m_20148_() + "|" + gun;
        Integer cached = GFIRE_CYCLE.get(key);
        if (cached != null) {
            return cached;
        }
        int cycle = 0;
        try {
            Object gd = gunDataOf(mount, gun);
            if (gd != null && mGunGetProp != null && fGunPropMagazine != null
                    && fGunPropEmptyReload != null) {
                Object magObj = mGunGetProp.invoke(gd, fGunPropMagazine.get(null));
                Object loadObj = mGunGetProp.invoke(gd, fGunPropEmptyReload.get(null));
                int mag = magObj instanceof Number m ? m.intValue() : 0;
                int load = loadObj instanceof Number l ? l.intValue() : 0;
                if (mag > 0 && load > 0) {
                    cycle = (int) Math.ceil(load / (double) mag);
                }
            }
        } catch (Throwable ignored) {
        }
        if (GFIRE_CYCLE.size() > 512) {
            GFIRE_CYCLE.clear(); // 兜底：表不会无限涨（与其它几张表同口径）
        }
        GFIRE_CYCLE.put(key, cycle);
        return cycle;
    }

    /** 【实测七百七十九·点1】"炮自己的循环拍数"缓存（uuid|炮名 → 拍）。 */
    private static final java.util.Map<String, Integer> GFIRE_CYCLE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean fireTickDue(Entity mount, String gun) {
        try {
            String key = mount.m_20148_() + "|" + gun;
            Integer n = GFIRE_TICK.get(key);
            int interval = fireIntervalTicks(mount, gun);
            int v = (n == null ? 0 : n) + 1;
            if (v >= interval) {
                GFIRE_TICK.put(key, 0);
                return true;
            }
            if (GFIRE_TICK.size() > 512) {
                GFIRE_TICK.clear(); // 兜底：表不会无限涨（与其它几张表同口径）
            }
            GFIRE_TICK.put(key, v);
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 【实测七百三十一·后门】直接开火的一条留痕（节流 5 秒/只）——日志搜「模组坐骑·开火」。 */
    private static final java.util.Map<java.util.UUID, Long> FLOG_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void logForceFire(Entity mount, String guns, LivingEntity target) {
        try {
            long now = System.currentTimeMillis();
            Long last = FLOG_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            FLOG_AT.put(mount.m_20148_(), now);
            if (FLOG_AT.size() > 512) {
                FLOG_AT.clear();
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·开火", describeKind(mount)
                    + " 开火[" + guns + "] → "
                    + String.valueOf(target.m_6095_()).replace("entity.minecraft.", ""));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 通用档写目标时的一行留痕（节流 5 秒/只，且只在"目标变了"时更值得看）。
     * 日志搜「模组坐骑·目标」就能确认通用兜底有没有真的把她的 target 传下去。
     *
     * <p>【实测七百六十六】补上"**它自己**的目标"：这一行原先打的只是**女仆的目标**，
     * 于是"目标=husk"看着一切正常、而它一招不出。反编译实证诡厄 {@code Summoned.setTarget}
     * 对守区仆从会静默丢弃区域外目标 → 它的 {@code getTarget()} 其实是 null。
     * 两栏并排（女仆的 / 它自己的）以后，这类"写丢了"一眼可见。
     */
    private static void logAttackGeneric(Entity mount, LivingEntity target) {
        try {
            long now = System.currentTimeMillis();
            Long last = ATK_AT.get(mount.m_20148_());
            if (last != null && now - last < LOG_INTERVAL_MS) {
                return;
            }
            ATK_AT.put(mount.m_20148_(), now);
            if (ATK_AT.size() > 512) {
                ATK_AT.clear(); // 兜底：表不会无限涨
            }
            String who = describeKind(mount);
            if (who.isEmpty()) {
                who = String.valueOf(mount.m_6095_()).replace("entity.minecraft.", "");
            }
            String mine = "-";
            if (mount instanceof net.minecraft.world.entity.Mob m2) {
                LivingEntity own = m2.m_5448_();
                mine = own == null ? "无" : String.valueOf(own.m_6095_()).replace("entity.minecraft.", "");
            }
            com.maidsmart.tool.PromaidLog.log("模组坐骑·目标", who + " 女仆目标="
                    + (target == null ? "无" : String.valueOf(target.m_6095_())
                            .replace("entity.minecraft.", ""))
                    + " 它自己=" + mine);
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
                if (maid.m_20202_() == mount) {
                    maid.m_8127_();
                }
            } catch (Throwable ignored) {
            }
            return maid.m_7998_(mount, true);
        } catch (Throwable ignored) {
            return true; // 探测失败不拦路（她照常坐上去，只是可能开不动）
        }
    }

    /** 未使用，避免 import 警告。 */
    @SuppressWarnings("unused")
    private static List<Entity> unused() {
        return null;
    }

    /* ==================== 实测七百四十一·点1：指挥棒左击换座（主驾 ↔ 副驾） ==================== */

    /**
     * 【实测七百四十一·点1】把**主人**在"驾驶位（座位 0）"与"副驾"之间来回换。
     *
     * <h2>玩家原话</h2>
     * 「如果玩家处于副座，可以通过手持骑乘指挥棒进行左击，从而把自己交换到主座位。
     *  再左击一下再换回去。」
     *
     * <h2>为什么是"主人与女仆互换"而不是"主人随便挪个空座"</h2>
     * 719 那条口径（反编译实证）是：**卓越前线的引擎只认座位 0**（{@code getFirstPassenger()}）——
     * 座位 0 是谁，谁在开这辆车。所以"主人坐主驾"这件事，本质就是"把女仆从 0 号座换下来、
     * 把主人放上去"；再按一次就反过来。
     *
     * <h2>为什么走"主人先下 → 女仆挪 → 主人上"</h2>
     * {@code changeSeat(Entity,int)} 要求"目标座为空 + 该实体已是乘客"（反编译实证）——
     * 两人互换座位时，任何一方想去对方那一座都会撞到"目标座有人"。多座载具有第三方空座可周转，
     * 两座车（Mi-28）没有；所以统一走"主人先下车让位"这条路，两座/多座**同一条路**，
     * 且不存在"两人抢同一座"的中间态。
     *
     * @return 给玩家看的一行提示（动作栏）；不满足条件 → null（调用方不提示）
     */
    public static String swapOwnerSeat(Entity mount, net.minecraft.world.entity.player.Player player,
                                       Entity maid) {
        try {
            if (mount == null || player == null || maid == null || mChangeSeat == null) {
                return null;
            }
            int ownerSeat = seatIndexOf(mount, player);
            int maidSeat = seatIndexOf(mount, maid);
            if (ownerSeat < 0 || maidSeat < 0) {
                return null; // 两人必须都在这辆车上
            }
            if (ownerSeat == maidSeat) {
                return null;
            }
            // 目标：主人去女仆那一座，女仆来主人这一座（**就是互换**——不管谁在 0 号座）
            int ownerWants = maidSeat;
            int maidWants = ownerSeat;
            // ① 主人先下车（把他的座位让出来）
            player.m_8127_();
            // ② 女仆挪进主人刚才那一座（changeSeat 要求"目标座为空 + 她已是乘客"）
            try {
                mChangeSeat.invoke(mount, maid, maidWants);
            } catch (Throwable ignored) {
            }
            boolean maidMoved = seatIndexOf(mount, maid) == maidWants;
            // ③ 主人重新上车。**这里必须先开一张"重上车放行条"**——
            //    玩家手里正拿着指挥棒（左击是靠它触发的），而我们自己那两道登乘闸
            //    （{@code EntityBatonMountGateMixin} 拦 startRiding + {@code onMount} 拦
            //    EntityMountEvent）会把"拿指挥棒的人"一律拦下。741 那一版没开这张条，
            //    于是 {@code startRiding} 恒返回 false → 每次都走"换座失败：坐不回去了"。
            //    这正是玩家原话「从副驾驶换到主驾驶座这个操作一直没能实现。老是提示失败」的根因。
            RideBindManager.grantRemount(player, mount);
            boolean remounted;
            try {
                remounted = player.m_7998_(mount, true);
            } finally {
                RideBindManager.revokeRemount(player);
            }
            // 原版 {@code startRiding} 会把他放进**第一个空座**，而那里往往就是他想要的那座
            // （她刚挪走）——此时 {@code changeSeat} 会因"目标座已被自己占着"返回 false，
            // 但那不是失败。所以**判成功一律看最终座号**，不看返回值。
            if (!remounted || seatIndexOf(mount, player) < 0) {
                return "\u00a7c换座失败：坐不回去了……";
            }
            if (seatIndexOf(mount, player) != ownerWants) {
                try {
                    mChangeSeat.invoke(mount, player, ownerWants);
                } catch (Throwable ignored) {
                }
            }
            boolean ownerMoved = seatIndexOf(mount, player) == ownerWants;
            if (ownerMoved && maidMoved) {
                return ownerWants == 0
                        ? "\u00a7a换到驾驶位了\u00a7f（她现在坐副驾）"
                        : "\u00a7a换回副驾了\u00a7f（她回去开车）";
            }
            if (ownerMoved) {
                return "\u00a7a你换过来了\u00a7f（她那座没动，再按一次左击试试）";
            }
            return "\u00a7e换座只成功了一半\u00a7f（再按一次左击试试）";
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== 实测七百三十七·双人座：主人坐副驾 ==================== */

    /**
     * 这辆载具**除了某位乘客之外**的第一个空座（座位号；没有则 -1）。
     *
     * <p>口径：座位数组是"有序 + 可为 null"的（SWB 的 {@code orderedPassengers}，反编译实证
     * {@code getSeatIndex(Entity) = indexOf}、{@code getNthEntity(int)} 取某座乘客）。
     * 这里从 0 号座起逐个问"这座有人吗"，跳过 {@code exclude}（女仆自己）所占的那座，
     * 返回第一个空座——正是主人该坐的副驾。
     */
    public static int firstFreeSeatExcept(Entity mount, Entity exclude) {
        try {
            if (!isVehicle(mount) || mGetNthEntity == null) {
                return -1;
            }
            int excludeSeat = seatIndexOf(mount, exclude);
            int n = maxPassengers(mount);
            if (n <= 0) {
                return -1;
            }
            for (int i = 0; i < n; i++) {
                if (i == excludeSeat) {
                    continue; // 女仆自己那座，跳过
                }
                Object nth = mGetNthEntity.invoke(mount, i);
                if (nth == null) {
                    return i; // 空座
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 【实测七百三十七·双人座】把主人放进这辆载具的**副驾驶座**（女仆留在驾驶位，0 号座）。
     *
     * <p>玩家原话：「我考虑到卓越前线有一些载具是分为双人座的，能否考虑在女仆乘坐后主人右击的
     * 时候登上副驾驶座呢？」——SWB 自己的 {@code VehicleEntity.interact} 只有"座位 0 是第一乘客"
     * 那一支；当第一乘客**不是玩家**（我们的女仆）时它走"把第一乘客踢下来、主人自己坐座位 0"
     * 的那一支（反编译实证），正是"女仆被顶下车"的根因。所以我们不走它那条路，改成：
     * <ol>
     *   <li>先 {@code player.startRiding(mount, true)}（原版规则：落进第一个空座——女仆在 0 号，
     *       所以主人落 1 号）；</li>
     *   <li>再用 SWB 自己的 {@code changeSeat(player, 目标座)} 把主人**钉到副驾**——这样即使
     *       原版把它排在别处也能挪过来（{@code changeSeat} 要求"目标座为空 + 该实体已是乘客"，
     *       上面那一步正好满足）。</li>
     * </ol>
     * 全程不碰女仆的座位（她稳在 0 号 = 引擎唯一认的驾驶位），于是"她开、主人坐副驾"。
     *
     * @return true = 主人已在这辆载具上（坐上副驾，或本来就在车上）
     */
    public static boolean boardOwnerAsPassenger(Entity mount, net.minecraft.world.entity.player.Player player,
                                                Entity maid) {
        try {
            if (mount == null || player == null) {
                return false;
            }
            if (player.m_20202_() == mount) {
                return true; // 已经在车上（可能正被我们挪座），不重复动
            }
            int seat = firstFreeSeatExcept(mount, maid);
            if (seat < 0) {
                return false; // 没有空座（单座车 / 已满）——调用方据此拒绝
            }
            if (!player.m_7998_(mount, true)) {
                return false;
            }
            if (mChangeSeat != null) {
                try {
                    mChangeSeat.invoke(mount, player, seat);
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 实测七百三十八：地面载具接敌 + 玩家在机上 ==================== */

    /**
     * 【实测七百七十三·点2】这台**卓越前线载具**的**驾驶位**上坐的是不是玩家。
     *
     * <h2>玩家原话</h2>
     * 「玩家坐副驾驶时如果换到其它位置（如3、4号位），再左键与女仆换位，玩家虽坐在主驾驶
     *  却不能控制载具。」
     *
     * <h2>根因（反编译实证）</h2>
     * 卓越前线的引擎只认**座位 0** 是驾驶位：{@code wheelEngine} 那一路第一句就是
     * {@code getFirstPassenger() == null → 全清输入 + power = 0}——"谁在开"看的是
     * {@code getFirstPassenger()}（0 号座），**不是** {@code getControllingPassenger()}
     * （SWB 压根没覆写它、对谁都返回 null）。左击换座把主人换进 0 号座之后，**玩家与我们的
     * 驱动每拍都在写同一组输入状态**（{@code processInput} 位掩码 / 鼠标通道 / 机头 yaw），
     * 玩家按什么都没用——这就是"坐在主驾却不能控制载具"。
     *
     * <h2>与 {@link #hasPlayerAboard} 的区别（别合并）</h2>
     * {@code hasPlayerAboard} = "机上有人"（副驾也算）——那一档我们**照旧要开**（女仆开、
     * 主人坐副驾，738 起的高度档就是这么设计的）。本判据只认**驾驶位**：副驾坐着玩家时它
     * 返回 false，方向盘还在女仆手里。两者用途不同，不能互相替换。
     *
     * <p>只对卓越前线载具成立（{@code isVehicle}）：原版马/猪只有一个鞍位，玩家骑上去时
     * 本来就该由玩家开，那种情况下我们根本没在驱动。
     */
    public static boolean playerHoldsDriverSeat(Entity mount) {
        try {
            if (mount == null || !isVehicle(mount)) {
                return false;
            }
            Entity first = mount.m_146895_();
            return first instanceof net.minecraft.world.entity.player.Player;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 【实测七百三十八】这台载具上**除了女仆之外**是不是坐着玩家（主人坐进副驾了）。
     *
     * <p>玩家原话：「如果女仆乘坐的是直升机且玩家坐副驾驶……导致女仆必须要一直往上飞。
     * 建议改为玩家乘坐以后就悬停。」——判据只看"有没有玩家乘客"，不问是哪位玩家：
     * 直升机是这台机器，机上有玩家就该稳在原地（见 {@code MaidAirCombat.holdHere}）。
     *
     * <p>为什么用 {@code instanceof Player} 而不是比 UUID：这一档的语义是"这架飞机上有人"，
     * 与"是不是主人"无关（别人坐进来也一样该悬停，否则同样会顶着玩家往上飞）。
     */
    public static boolean hasPlayerAboard(Entity mount) {
        try {
            if (mount == null) {
                return false;
            }
            for (Entity p : mount.m_20197_()) {
                if (p instanceof net.minecraft.world.entity.player.Player) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 【实测七百三十八】地面载具接敌绕圈的**撞墙反向**符号（{@code ±1}）。
     *
     * <p>玩家原话：「撞墙以后自动反方向。」——引擎自己不会掉头：它只会照着我们的输入位死顶，
     * 于是车卡在墙边原地磨。这里按"位置几乎没动"来判卡住：连续 {@link #GROUND_STALL_TICKS} 拍
     * 位移小于 {@link #GROUND_STALL_EPS} 格就翻一次号，绕圈方向随之调头，她就从墙边绕出去。
     *
     * <p>为什么用"位移"而不是引擎的碰撞标志：SWB 载具的 {@code horizontalCollision} 在它自己的
     * {@code travel} 里被改写、且被我们每拍的 {@code setDeltaMovement} 干扰，判不稳；位移是最终
     * 事实，且对"顶墙 / 顶在别的实体上 / 被地形卡住"三种情形一视同仁。
     *
     * <p>表按 UUID 记，与其它频限表同口径（超限整表清）。
     */
    public static double groundReverse(Entity mount) {
        try {
            if (mount == null) {
                return 1.0;
            }
            java.util.UUID id = mount.m_20148_();
            double x = mount.m_20185_();
            double z = mount.m_20189_();
            double[] prev = GROUND_LAST.get(id);
            int stall = GROUND_STALL.getOrDefault(id, 0);
            double sign = GROUND_SIGN.getOrDefault(id, 1.0);
            if (prev != null) {
                double mv = Math.sqrt((x - prev[0]) * (x - prev[0]) + (z - prev[1]) * (z - prev[1]));
                if (mv < GROUND_STALL_EPS) {
                    stall++;
                } else {
                    stall = 0;
                }
                if (stall >= GROUND_STALL_TICKS) {
                    stall = 0;
                    sign = -sign;
                    logDrive(mount, "地面接敌：连续 " + GROUND_STALL_TICKS
                            + " 拍几乎没动（撞墙/卡住）→ 绕圈方向调头 " + (sign > 0 ? "逆时针" : "顺时针"));
                }
            }
            if (GROUND_LAST.size() > 512) {
                GROUND_LAST.clear();
                GROUND_STALL.clear();
                GROUND_SIGN.clear();
            }
            GROUND_LAST.put(id, new double[]{x, z});
            GROUND_STALL.put(id, stall);
            GROUND_SIGN.put(id, sign);
            return sign;
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /** 撞墙判据：连续这么多拍位移小于 {@link #GROUND_STALL_EPS} 格 → 调头。0.5 秒。 */
    private static final int GROUND_STALL_TICKS = 10;
    /** "几乎没动"的每拍位移阈值（格）：0.02 ≈ 每秒 0.4 格，只有真卡住才会低于它。 */
    private static final double GROUND_STALL_EPS = 0.02;

    private static final java.util.Map<java.util.UUID, double[]> GROUND_LAST =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> GROUND_STALL =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Double> GROUND_SIGN =
            new java.util.concurrent.ConcurrentHashMap<>();
}
