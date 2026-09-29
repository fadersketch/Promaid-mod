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
     * 把她摆到龙的"玩家鞍位"上【降级方案的内核】。
     *
     * <p>位置与玩家骑龙时逐字相同：{@code getRiderPosition()} + {@code getBbHeight()}，再过
     * {@link #freeSeatY}。她**不是乘客**（{@code startRiding} 那一步已取消），所以位置不由
     * 原版 {@code positionRider} 那条链条管——由我们每 tick 写一次。她是**普通实体**，
     * 位置照常由原版位置包同步给客户端（这正是降级方案比"当乘客"省事的地方：乘客位置要靠
     * 乘客自己每拍算，普通实体由服务端说了算）。
     *
     * <p>速度一并清零：不让她把上一拍的惯性带进"椅子"，也不让重力在座位下方累积。
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
            double y = freeSeatY(maid, seat.x,
                    seat.y + (double) maid.getBbHeight(), seat.z);
            maid.setPos(seat.x, y, seat.z);
            maid.setDeltaMovement(Vec3.ZERO);
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
        if (horiz > 1.5 && (headSteer || !turning || Math.abs(err) < 40.0f)) {
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
                    + (headSteer ? " 头朝向=" + Math.round(desiredYaw) : "")
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

    /**
     * 头朝向档每拍最多把车头转多少度（{@link #driveVehicle}）。12 ≈ 240°/秒，
     * 比引擎自己那 10°/秒 快得多（这就是"能转弯"与"顶住不动"的差别），又不至于瞬转画龙。
     */
    private static final double HEAD_STEER_MAX_DEG_PER_TICK = 12.0;

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
            LivingEntity target = targetOf(maid);
            if (k == Kind.VEHICLE) {
                // 把目标交给"她"（载具内置的 Mob-乘客开火链路读的是乘客自己的 getTarget）
                if (target != null) {
                    try {
                        maid.setTarget(target);
                    } catch (Throwable ignored) {
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
