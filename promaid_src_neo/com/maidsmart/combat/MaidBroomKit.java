package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * v1.3.0「扫帚模式」的判定套件 + 活动范围夹取（两树镜像）。
 *
 * ── 这是什么 ──
 * 一个 TLM 任务模式：女仆取出扫帚、在脚下放出 {@code EntityBroom} 并骑上去飞起来，
 * 用**远程武器**打（开火链路整条复用飞行远战，见 {@link MaidFlightRangedTask#fireRangedWeapon}），
 * 平时悬停跟着主人。**不做编队**（玩家明确排除：成本太高）。
 *
 * ── 为什么需要单独的 Kit（而不是直接用 MaidFlightKit）──
 * 空袭的"能不能激活"是三件套：**鞘翅 + 武器 + 推进剂（烟花/羽扇/位移法术/激流三叉戟）**。
 * 扫帚模式要的是两件套：**扫帚 + 远程武器**（骑扫帚本身就是推进方式，不需要鞘翅，
 * 也不需要烟花）。所以判定要另写一份，但**远程武器与弹药那两条口径一字不改地复用**
 * {@link MaidFlightKit}——本模组反复强调的红线：同一个口径只能有一处实现。
 *
 * ── 与空袭的关系（用户原话："检查判定稍微改一改就行"）──
 * <pre>
 *   空袭：  hasElytra && hasWeaponForFlightTask && hasFlightPropellant && rangedAmmoOk
 *   扫帚：  hasBroom  && hasRangedWeapon                            && rangedAmmoOk
 * </pre>
 * "弹药"这一条照搬：远程空袭本来就是"没弹药不必起飞"，扫帚同理（弓/弩要箭、枪械要子弹；
 * 御币/三叉戟/能量枪不消耗弹药，那一档 {@code hasAmmoForWeapon} 自己放行）。
 */
public final class MaidBroomKit {

    /** 扫帚模式的任务 UID（与 {@link MaidBroomTask#UID} 同值；字符串复制避免循环依赖，照 MaidFlightKit 的写法） */
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:broom");

    private MaidBroomKit() {
    }

    /* ==================== 任务 / 开关 ==================== */

    public static boolean isBroomUid(ResourceLocation uid) {
        return UID.equals(uid);
    }

    public static boolean isBroomTask(EntityMaid maid) {
        try {
            return maid != null && maid.getTask() != null && isBroomUid(maid.getTask().getUid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 总开关（配置 combat.broom，默认开）。关掉时行为整段不激活、退回地面战斗 */
    public static boolean enabled() {
        try {
            return com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_ENABLE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ==================== 扫帚 ==================== */

    /**
     * 是不是 TLM 的扫帚**物品**。
     *
     * 【判据用类型而不是注册名】`touhou_little_maid:broom` 这个 id 在两版 TLM 里都是它，
     * 但写死字符串等于把"TLM 改 id"变成我们的静默失效点；`instanceof ItemBroom` 由编译器
     * 盯着，类型被删/改名时**编译期**就报错。MaidFlightKit 认鞘翅是同一种思路。
     */
    public static boolean isBroomItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return stack.getItem() instanceof com.github.tartaricacid.touhoulittlemaid.item.ItemBroom;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 她身上（主手 / 副手 / 背包 / 精妙背包-旅行者背包这类额外容器）有没有一把扫帚物品。
     * 覆盖顺序与 {@link MaidFlightKit} 的"找鞘翅/找武器"同款。
     */
    public static boolean hasBroomItem(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        try {
            if (isBroomItem(maid.getMainHandItem()) || isBroomItem(maid.getOffhandItem())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            net.neoforged.neoforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isBroomItem(inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return com.maidsmart.tool.MaidExtraContainer.contains(maid, MaidBroomKit::isBroomItem);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 她正骑着的那把扫帚（没骑返回 null） */
    public static EntityBroom ridingBroom(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            return maid.getVehicle() instanceof EntityBroom b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isRidingBroom(EntityMaid maid) {
        return ridingBroom(maid) != null;
    }

    /* ==================== 远程武器（复用 MaidFlightKit 口径） ==================== */

    /** 有没有远程武器——**任务无关**判据，直接复用 MaidFlightKit（不另写一份） */
    public static boolean hasRangedWeapon(EntityMaid maid) {
        try {
            return MaidFlightKit.hasRangedWeaponOnly(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 弹药够不够——**与远程空袭同一份判据**（{@link MaidFlightKit#hasAmmoForWeapon}） */
    public static boolean ammoOk(EntityMaid maid) {
        try {
            if (maid == null) {
                return false;
            }
            return MaidFlightKit.hasAmmoForWeapon(maid, MaidFlightKit.resolveRangedWeaponAny(maid));
        } catch (Throwable ignored) {
            return true; // 判据异常时放行：宁可起飞也不要卡死（与 hasAmmoForRanged 的兜底同款）
        }
    }

    /* ==================== 激活 / 缺件 ==================== */

    /**
     * 模式激活 = 扫帚（身上有物品 **或** 已经骑着一把）+ 远程武器 + 弹药。
     *
     * 【为什么"已经骑着"也算有扫帚】她骑上去的那一刻物品已经消耗掉了（与玩家放置扫帚
     * 同款，见 {@code ItemBroom.useOn}），这时背包里当然没有扫帚物品——若只认物品，
     * 她会在骑上去的下一 tick 判定"缺扫帚"、把刚放出来的扫帚又收回去，来回抽搐。
     */
    public static boolean isModeActive(EntityMaid maid) {
        if (maid == null || !enabled()) {
            return false;
        }
        if (!(hasBroomItem(maid) || isRidingBroom(maid))) {
            return false;
        }
        if (!hasRangedWeapon(maid)) {
            return false;
        }
        return ammoOk(maid);
    }

    /**
     * 未激活时缺哪一件的**可读原因**（气泡用）。{@code null} = 齐备（不该调用）。
     * 顺序固定（扫帚 → 远程武器 → 弹药），与 {@link #isModeActive} 的判定口径完全一致
     * ——与 {@link MaidFlightKit#missingParts} 同一个写法、同一个理由（"只说未激活没用"）。
     */
    public static String missingParts(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!(hasBroomItem(maid) || isRidingBroom(maid))) {
            sb.append("扫帚");
        }
        if (!hasRangedWeapon(maid)) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("远程武器");
        }
        // 弹药只在"其它都齐"时才报——与 MaidFlightKit.missingParts 的条件同款：
        // 缺武器时"没有弹药"是废话，报出来只会让玩家困惑该补哪一件。
        if (sb.length() == 0 && !ammoOk(maid)) {
            sb.append("弹药");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /* ==================== 活动范围（home / 工作区） ==================== */

    /**
     * 把一个飞行目标点夹进她的**活动范围圈**（home 模式）——扫帚模式的"与 home 模式的关系"
     * 这条最难的账，全部收在这一个方法里。
     *
     * 【为什么必须我们自己夹】她一旦骑上扫帚就是**乘客**：TLM {@code canBrainMoving()} 在
     * {@code isPassenger()} 时为 false（字节码实证），于是 TLM 自身那套"范围约束"（寻路节点
     * 评估、任务选点过滤）**整条失效**——她自己的移动全停，往哪飞只听我们的。
     * 好消息是 TLM 的硬约束 {@code SchedulePos.tick} 里紧跟着 `if (!canBrainMoving()) return;`，
     * 所以**不会被传送拉回**（不会出现"飞出去→被拽回来→再飞出去"的循环，也不会被拽下扫帚）。
     * 坏消息是"守家"这件事**没人管了**，必须由这里补上。
     *
     * 【只夹水平，不夹垂直】活动范围圈在 TLM 里就是个水平圆（{@code getRestrictCenter()} 是
     * BlockPos、{@code getRestrictRadius()} 是 float，没有纵向半径这一说）；而扫帚模式本来
     * 就只在目标/地面之上 1~3 格悬停，纵向夹取没有意义、还会把她按进地里。
     *
     * 【半径读的是"放大后"的值】战斗任务下 {@code CombatWorkRangeMixin} 会把
     * {@code getRestrictRadius()} 的读数临时放大到 {@code combat.workRange}（默认 15，见
     * {@link CombatWorkRange}）——我们**直接读 {@code maid.getRestrictRadius()}**，所以吃得到
     * 同一次放大：与近战/空袭"接战时可以追远一点"的口径完全一致，不需要另写一套。
     *
     * 【圈心没配好就不夹】{@code WorkAreaClamp.circleCenter} 已经把"从未 restrictTo 过"
     * （圈心是 {@code BlockPos.ZERO}）挡成 null——那种情况下照夹会把她拴到世界原点。
     *
     * @return 夹取后的点（任何异常 / 未开 home / 关掉本项 → 原样返回）
     */
    public static Vec3 clampToHome(EntityMaid maid, Vec3 desired) {
        if (desired == null) {
            return null;
        }
        try {
            if (!com.maidsmart.config.MaidSmartConfig.COMBAT_BROOM_CLAMP_HOME.get()) {
                return desired;
            }
            if (maid == null || !maid.hasRestriction()) {
                return desired; // 非 home 模式（跟随/战斗）：无圈，自由飞
            }
            BlockPos c = com.maidsmart.follow.WorkAreaClamp.circleCenter(maid);
            if (c == null) {
                return desired;
            }
            double r = Math.max(1.0, maid.getRestrictRadius());
            double cx = c.getX() + 0.5;
            double cz = c.getZ() + 0.5;
            double dx = desired.x - cx;
            double dz = desired.z - cz;
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h <= r || h <= 1.0E-4) {
                return desired;
            }
            double k = r / h;
            return new Vec3(cx + dx * k, desired.y, cz + dz * k);
        } catch (Throwable ignored) {
            return desired;
        }
    }

    /* ==================== 供全模组"她在飞"的那一族判据 ==================== */

    /**
     * 她此刻正**骑在扫帚上飞**（扫帚模式 + 是本模组所有的女仆）。
     *
     * 【为什么单独一个判据，而不是把扫帚 UID 塞进 {@code MaidFlightKit.isFlightUid}】
     * `isFlightUid` 是"鞘翅空袭"的判据，它一路喂给**鞘翅渲染图层、滑翔姿态前倾、防摔落、
     * 落地水**那些"她背上有翅膀、在滑翔"的语义。扫帚模式**既不背鞘翅也不滑翔**，把它并进去
     * 会让扫帚女仆长出翅膀、被按滑翔物理对待。所以这里**并列**开一条，只在真正需要
     * "别打断她、别丢下她"的那几处接入（区块加载 / 传送豁免 / 就绪汇报）。
     */
    public static boolean isBroomAirborne(EntityMaid maid) {
        try {
            return isBroomTask(maid) && isRidingBroom(maid) && !maid.level().isClientSide();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
