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
 *       （反射）：飞行走 {@code flightManager.setFlightTarget}；升降走 {@code up/down}；
 *       攻击走 {@code strike} + {@code riderShootFire}。</li>
 * </ol>
 *
 * <h2>普通版 vs 社区版（同一个类，两套包名）</h2>
 * 反编译三个 jar 对照（{@code iceandfire-2.1.13}、{@code IceAndFireCE-1.2.7}、
 * {@code iceandfire-2.1-beta.1}）：<b>结构相同、只有包名与个别内部名不同</b>——
 * 普通版 {@code com.github.alexthe666.iceandfire.entity.EntityDragonBase}、社区版
 * {@code com.iafenvoy.iceandfire.entity.EntityDragonBase}（1.21.1 上类名多了后缀：
 * {@code DragonBaseEntity}）。承载骑乘/飞行/攻击的成员名（{@code flightManager}、
 * {@code isFlying}、{@code setFlying}、{@code up}、{@code down}、{@code strike}、
 * {@code riderShootFire}）**四份 jar 逐字相同**。所以这里**按类名探测、按同一套方法名驱动**
 * ——普通版与社区版共用一条代码路径，无需分叉。
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

    /* ==================== 反射缓存：冰火传说 ==================== */

    private static boolean iafInited;
    private static boolean iafOk;
    private static Class<?> cDragon;
    private static Field fFlightManager;      // EntityDragonBase.flightManager (public)
    private static Method mSetFlightTarget;   // IafDragonFlightManager.setFlightTarget(Vec3)
    private static Method mGetFlightTarget;   // IafDragonFlightManager.getFlightTarget()
    private static Method mIsFlying;          // EntityDragonBase.isFlying()
    private static Method mSetFlying;         // EntityDragonBase.setFlying(boolean)
    private static Method mUp;                // EntityDragonBase.up(boolean)
    private static Method mDown;              // EntityDragonBase.down(boolean)
    private static Method mStrike;            // EntityDragonBase.strike(boolean)
    private static Method mRiderShootFire;    // EntityDragonBase.riderShootFire(Entity)
    private static Method mGetDragonStage;    // EntityDragonBase.getDragonStage()

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
                Field fm = d.getField("flightManager");
                Class<?> fmCls = fm.getType();
                Method setTarget = fmCls.getMethod("setFlightTarget", Vec3.class);
                Method getTarget = fmCls.getMethod("getFlightTarget");
                Method isFly = d.getMethod("isFlying");
                Method setFly = d.getMethod("setFlying", boolean.class);
                Method up = d.getMethod("up", boolean.class);
                Method down = d.getMethod("down", boolean.class);
                Method strike = d.getMethod("strike", boolean.class);
                Method shoot = d.getMethod("riderShootFire", Entity.class);
                Method stage = d.getMethod("getDragonStage");
                cDragon = d;
                fFlightManager = fm;
                mSetFlightTarget = setTarget;
                mGetFlightTarget = getTarget;
                mIsFlying = isFly;
                mSetFlying = setFly;
                mUp = up;
                mDown = down;
                mStrike = strike;
                mRiderShootFire = shoot;
                mGetDragonStage = stage;
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
            return e.m_6084_();
        } catch (Throwable ignored) {
            return false;
        }
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
     * @param mount    坐骑（本类只处理 VEHICLE / DRAGON）
     * @param target   目的地点
     * @param modifier 速度倍率（载具档用它缩放油门）
     */
    public static boolean drive(Entity mount, Vec3 target, double modifier) {
        try {
            Kind k = kindOf(mount);
            if (k == Kind.VEHICLE) {
                return driveVehicle(mount, target, modifier);
            }
            if (k == Kind.DRAGON) {
                return driveDragon(mount, target);
            }
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
            } else if (k == Kind.DRAGON) {
                stopDragon(mount);
            }
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
     */
    private static boolean driveVehicle(Entity mount, Vec3 target, double modifier) {
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

        short bits = 0;
        boolean turning = Math.abs(err) > 8.0f;
        if (turning) {
            // 与玩家按键同一条路径：左右位 → 引擎自己累积 holdTick → setDeltaRot
            bits |= (err > 0) ? 0x002 : 0x001;
        }
        // 前进：够远就踩油门；朝向差太多时先转不冲（免得画龙）
        if (horiz > 1.5 && (!turning || Math.abs(err) < 40.0f)) {
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
            logDrive(mount, "位掩码=" + bits + " 引擎=" + (eng.isEmpty() ? "?" : eng)
                    + " 距目标=" + (long) horiz + "格");
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void stopVehicle(Entity mount) {
        try {
            if (mProcessInput != null) {
                mProcessInput.invoke(mount, (short) 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 冰火传说：flightManager.setFlightTarget ---------- */

    /**
     * 龙的飞行由 {@code IafDragonFlightManager} 驱动（四份 jar 成员名逐字相同）。
     *
     * <p><b>为什么"喂目标点"就够</b>——反编译实证（{@code IceAndFireCE-1.2.7}）：
     * <pre>
     *   EntityDragonBase.tick():  if (useFlyingPathFinder() && !level.isClientSide) flightManager.update();
     *   useFlyingPathFinder()  =  isFlying() && getControllingPassenger() == null;
     * </pre>
     * 而 {@code getControllingPassenger()} 在普通版 / 社区版 1.20.1 上**只认 Player**
     * （{@code updateRider()} 整段都写着 {@code controllingPassenger instanceof Player}），
     * 女仆当乘客时恒为 null ⇒ {@code useFlyingPathFinder()} 为真 ⇒ 每 tick 跑
     * {@code flightManager.update()}，而它就是把龙朝 {@code getFlightTarget()} 带的那个
     * MoveControl。
     *
     * <p><b>所以有两件事必须由我们做</b>：① 把飞行目标点写进去；② **把 {@code flying} 置真**
     * ——否则 {@code useFlyingPathFinder()} 为假、{@code flightManager.update()} 根本不会跑
     * （女仆骑上去时龙还站在地上）。升/降不单独表达：{@code up/down} 那两个控制位只在
     * {@code updateRider()} 的 **Player 分支**里被读，女仆这一档读了也白读——高度全部走
     * "目标点自己的 Y"，由 {@code flightManager} 的俯仰逻辑实现（与玩家骑乘时同一套飞行物理）。
     */
    private static boolean driveDragon(Entity mount, Vec3 target) {
        try {
            Object fm = fFlightManager.get(mount);
            if (fm == null || mSetFlightTarget == null) {
                return false;
            }
            // 必须先起飞：useFlyingPathFinder() 要 isFlying 为真，flightManager.update() 才会跑
            if (mIsFlying != null && mSetFlying != null
                    && !Boolean.TRUE.equals(mIsFlying.invoke(mount))) {
                mSetFlying.invoke(mount, true);
            }
            mSetFlightTarget.invoke(fm, target);
            logDrive(mount, "飞行目标已写（阶段=" + dragonStage(mount) + "）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void stopDragon(Entity mount) {
        try {
            if (mStrike != null) {
                mStrike.invoke(mount, false);
            }
            // 飞行目标设成它自己脚下 → 失去推力、原地悬停（与载具档"收手"同口径）
            Object fm = fFlightManager.get(mount);
            if (fm != null && mSetFlightTarget != null) {
                mSetFlightTarget.invoke(fm, mount.m_20182_());
            }
        } catch (Throwable ignored) {
        }
    }

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
     *   <li><b>冰火传说的龙</b>：龙没有"自动开火"，得显式触发——{@code strike(true)} 置
     *       吐息位、{@code riderShootFire(女仆)} 以女仆为控制者喷一口（javap 实证该方法形参是
     *       {@code Entity}，**不要求 Player**）。</li>
     * </ul>
     */
    public static void tickAttack(Entity mount, EntityMaid maid) {
        try {
            if (!modMountFireEnabled() || maid == null) {
                return;
            }
            Kind k = kindOf(mount);
            LivingEntity target = targetOf(maid);
            if (k == Kind.VEHICLE) {
                // 把目标交给"她"（载具内置的 Mob-乘客开火链路读的是乘客自己的 getTarget）
                if (target != null) {
                    try {
                        maid.m_6710_(target);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (k == Kind.DRAGON) {
                if (target == null) {
                    if (mStrike != null) {
                        mStrike.invoke(mount, false);
                    }
                    return;
                }
                if (mStrike != null) {
                    mStrike.invoke(mount, true);
                }
                if (mRiderShootFire != null) {
                    mRiderShootFire.invoke(mount, maid);
                }
                return;
            }
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

    /**
     * 通用档写目标时的一行留痕（节流 5 秒/只，且只在"目标变了"时更值得看）。
     * 日志搜「模组坐骑·目标」就能确认通用兜底有没有真的把她的 target 传下去。
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
            com.maidsmart.tool.PromaidLog.log("模组坐骑·目标", who + " 目标="
                    + (target == null ? "无" : String.valueOf(target.m_6095_())
                            .replace("entity.minecraft.", "")));
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
}
