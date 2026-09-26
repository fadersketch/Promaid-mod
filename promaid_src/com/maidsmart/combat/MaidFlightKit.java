package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * v1.2.0（1.20.1）：飞行作战模式的装备判定 / 穿戴 / 滑翔状态（工具类）。
 *
 * 【模式激活三要素】鞘翅 + **近战武器（1.20.1 无重锤，故为任意近战武器）** + 烟花火箭
 * ——缺任意一个，本模式【不激活】：行为退回"和普通攻击模式一致"的地面近战。
 * 判定覆盖背包 / 主手 / 副手 / 身上护甲位。
 * v1.2.0 实测四百六十八：武器位再放开"枪械"——**飞行远战**认枪械（TACZ / 卓越前线，
 * 判据 `GunCompat.isGun`），开火走 TLM 自己的枪械通道（换弹 + 自动瞄准 + 开火，见
 * `MaidFlightCombatBehavior#tickGunFire`，射程用 `GunCompat.gunMaxRange`）；飞行近战
 * 仍只认近战武器（枪械挥砍没有意义）。
 *
 * 【滑翔的实现原理（反编译实证）】原版滑翔物理不在 Player 里，而在
 * `LivingEntity.travel(Vec3)`（SRG `m_7023_`）：唯一闸门是
 * `isFallFlying()` = `m_21255_()` = `getSharedFlag(7)`，全程无 instanceof Player。
 * 所以把第 7 位共享标志位置 true 即可让女仆滑翔。setSharedFlag/getSharedFlag 是
 * protected → 经 {@link com.maidsmart.mixin.EntityFlagInvoker} mixin 暴露。
 *
 * 【滑翔必须持续满足的条件（否则同 tick 就被清掉）】
 * 1. 胸甲位是可用鞘翅——`LivingEntity.updateFallFlying`（SRG `m_21323_`，aiStep 内、
 *    travel 之前）每 tick 检查胸甲位，不满足就清第 7 位 → equip() 必须真正穿到 CHEST 槽；
 * 2. 不能落地——`updateFallFlying` 里 `!onGround` 是硬条件，落地即清位
 *    （所以地面起飞必须先垫一个上跳，见行为里的"起跳滑翔"）。
 * 另：滑翔中每 20 tick 扣 1 点鞘翅耐久（同方法内实证），耐久见底后
 * `isFlyEnabled` 为假 → 模式自然失效。
 */
public final class MaidFlightKit {

    /** 飞行作战任务 UID（与 MaidFlightCombatTask.UID 同值；字符串复制避免循环依赖） */
    public static final ResourceLocation UID = new ResourceLocation("maid_smart", "flight_combat");
    /** v1.2.0：飞行远战任务 UID（空中盘旋 + 远程开火）；近战那个改名为"飞行近战" */
    public static final ResourceLocation UID_RANGED = new ResourceLocation("maid_smart", "flight_ranged");

    /** 共享标志位：原版滑翔（= Player.startFallFlying 写的那一位） */
    private static final int FLAG_FALL_FLYING = 7;

    private MaidFlightKit() {
    }

    /** 当前任务是否飞行作战 */
    public static boolean isFlightTask(EntityMaid maid) {
        try {
            return maid != null && maid.getTask() != null && isFlightUid(maid.getTask().getUid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * v1.2.2 实测六百一十一【外观口径：她该不该"看起来在飞"】——飞行任务 **或** 正在滑翔。
     *
     * 三个纯渲染的调用点共用这一条：鞘翅翅膀图层（Bedrock / Gecko 两套）、
     * 跟随俯角的前倾、以及"离地强制展翅"。它们原来都写着 {@link #isFlightTask}，
     * 而**飞行跟随（六百〇八 起跟着主人飞）的女仆并不是飞行任务**——她在滑翔，
     * 背上却没有翅膀、模型还是走路姿态（用户反馈："动作没有换成空袭飞行的动作"）。
     *
     * 【为什么把滑翔位算进来就够】判据用的是**同步过的共享标志位 7**（{@link #isGliding}），
     * 服务端/客户端一致，所以多人下客户端不需要任何本模组的服务端状态表也认得出；
     * 空袭那边一字不变（它本来就满足 isFlightTask）；玩家给女仆穿鞘翅、她自己也滑起来的
     * 情况同样认——这正是原版"谁在滑翔谁就有翅膀"的语义。
     */
    public static boolean isFlightVisual(EntityMaid maid) {
        return isFlightTask(maid) || isGliding(maid);
    }

    /* ---------------- 滑翔状态 ---------------- */

    /** 是否处于滑翔（读共享标志位——服务端/客户端一致） */
    public static boolean isGliding(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        try {
            return ((com.maidsmart.mixin.EntityFlagInvoker) (Object) maid)
                    .promaid$getSharedFlag(FLAG_FALL_FLYING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 置位/清位滑翔（服务端调用；自动同步客户端） */
    public static void setGliding(EntityMaid maid, boolean gliding) {
        if (maid == null) {
            return;
        }
        try {
            ((com.maidsmart.mixin.EntityFlagInvoker) (Object) maid)
                    .promaid$setSharedFlag(FLAG_FALL_FLYING, gliding);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否处于"飞行作战的空中状态"——供"空中禁传送"抑制链使用。
     *
     * v1.2.0【实测四百八十七】只认滑翔位是不够的：收翅猛击时 `tickSmash` 会**主动**
     * 清掉滑翔位（收翅才吃得到猛击判定），于是"飞向敌人、贴近地面"那一刻恰好不满足
     * `isGliding` → 主人一远，自动传送就把她拽走，这一轮扑击白费（用户反馈：
     * "飞向敌人离地面较近的时候…会触发自动传送。导致本次攻击被卡掉"）。
     *
     * 现在并入 {@code MaidFlightCombatBehavior.isEngaged}：本轮攻击的任一阶段
     * （起跳/爬升/收翅猛击/等待再放烟花/远程俯冲助推）都算"空中状态"。
     * 该状态由行为收尾 `forget()` 整清，豁免有界——一轮打完立刻恢复正常传送。
     */
    public static boolean isFlightAirborne(EntityMaid maid) {
        if (isGliding(maid)) {
            return true;
        }
        try {
            return com.maidsmart.combat.MaidFlightCombatBehavior.isEngaged(maid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ---------------- 装备检测 ---------------- */

    /**
     * v1.2.2 实测六百〇九：**本模组自己借走的那件副手物品**（没借走就空栈）。
     *
     * 两个"手上亮一下"的链路（{@link BombPose} / {@link FlightFireworkPose}）只补动作表现：
     * 把"她这一下正在用的那件"放进副手举十几 tick，原物留在它们自己的快照里。也就是说
     * 那十几 tick 里，玩家给她的那件东西（羽扇 / 烟花 / 盾牌…）**不在任何槽位里**——
     * 就绪判定若只看槽位，就会在这十几 tick 里得到"没有"。
     *
     * 实测现场：粉丝把孔雀羽扇挂在她副手，空袭每十几秒起手一次轰炸 → 就绪判定报
     * 「可以飞行的道具」→ 主人聊天框一条系统消息；借还之后判定又齐、把播报冷却清零，
     * 下一轮再报一条，**每 10 秒一条永远不停**。修法：就绪判定把"借走的那件"算作
     * **仍在她身上**（{@link #hasInHandsOrBorrowed}）。取用（{@code takeFirework} /
     * {@code TwilightFanKit.findFan}）**不变**：真要动手挥扇 / 烧烟花时它必须在槽位里。
     */
    public static ItemStack borrowedOffhand(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        ItemStack a = BombPose.savedOffhand(maid);
        if (!a.m_41619_()) {
            return a;
        }
        return FlightFireworkPose.savedOffhand(maid);
    }

    /** 主手 / 副手 / 被我们自己借走的那件副手物品 —— 三处任一满足即算"在她身上" */
    private static boolean hasInHandsOrBorrowed(EntityMaid maid, StackFilter filter) {
        return filter.test(maid.m_21205_()) || filter.test(maid.m_21206_())
                || filter.test(borrowedOffhand(maid));
    }

    /** 身上（主手/副手/护甲/背包）是否有可用鞘翅
     *  （v1.2.0 实测五百五十五：判据放宽为 {@link #isElytraLike}——模组"内置鞘翅的装备"也算） */
    public static boolean hasElytra(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (isElytraLike(maid.m_6844_(EquipmentSlot.CHEST), maid)) {
            return true;
        }
        if (hasInHandsOrBorrowed(maid, s -> isElytraLike(s, maid))) {
            return true;
        }
        // v1.3.0(beta) 实测六百八十【饰品栏（Curios）里的鞘翅也算】玩家原话："在饰品栏里面的鞘翅
        // 应该也可以作为启动我们的空袭的激活条件之一"——Curios 不在场/她没饰品栏槽位时这条恒 false
        //（全反射软兼容，见 CuriosElytraCompat），行为与旧版一字不差。
        if (CuriosElytraCompat.findElytra(maid, s -> isElytraLike(s, maid)) != null) {
            return true;
        }
        return hasInBackpack(maid, s -> isElytraLike(s, maid));
    }

    /** 三件套里的"武器位"——按任务分流：飞行近战=近战武器，飞行远战=远程武器 */
    public static boolean hasWeapon(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (hasInHandsOrBorrowed(maid, s -> isWeaponForTask(maid, s))) {
            return true;
        }
        return hasInBackpack(maid, s -> isWeaponForTask(maid, s));
    }

    /** 该任务 UID 是否飞行作战类（飞行近战 / 飞行远战） */
    public static boolean isFlightUid(ResourceLocation uid) {
        return UID.equals(uid) || UID_RANGED.equals(uid);
    }

    /**
     * v1.3.0：「**只能由玩家手动指定**、不参与自主切换」的作战模式 UID = 两种空袭 + 扫帚模式。
     *
     * 【为什么收成一个判据】自主战斗系统对这类模式有**三处**接入（入池候选 / 换战术 /
     * 还原链），空袭当初就是三处各写一遍 {@code isFlightUid}。再加一个手动模式（扫帚）
     * 若也照抄三遍，下一次加模式就要改六处——本模组已经反复吃过"同一个口径散落多处"的亏。
     * 现在三处统一问这一个方法，加模式只需改这一行。
     */
    public static boolean isManualOnlyCombatUid(ResourceLocation uid) {
        return isFlightUid(uid) || com.maidsmart.combat.MaidBroomKit.isBroomUid(uid);
    }

    /** 同上，实体版（给她自己判"我在不在一个手动专属的作战模式里"） */
    public static boolean isManualOnlyCombatTask(EntityMaid maid) {
        try {
            return maid != null && maid.getTask() != null
                    && isManualOnlyCombatUid(maid.getTask().getUid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前任务是否飞行**远战**（武器位 = 远程武器） */
    public static boolean isRangedTask(EntityMaid maid) {
        try {
            return maid != null && maid.getTask() != null && UID_RANGED.equals(maid.getTask().getUid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 远程武器：弓/弩/御币/模组弹射武器（ProjectileWeaponItem）+ **三叉戟** + 枪械（TACZ / 卓越前线）。
     *
     * v1.2.0 实测四百七十八【三叉戟】：它**不是** `ProjectileWeaponItem`
     * （1.20.1 是 `TridentItem extends Item implements Vanishable`），所以旧版这个判据
     * 会把它当"不是武器" → 三件套永远不齐 → 飞行远战激活不了，而且
     * `hasWeapon`/自动装备也认不出它。TLM 自己的 `TaskTridentAttack.isWeapon`
     * 用的就是 `instanceof TridentItem`，这里对齐。
     */
    public static boolean isRangedWeapon(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        // v1.2.0 实测五百三十二【激流三叉戟算近战】：带激流附魔的三叉戟**根本投不出去**
        // （原版语义见下），把它当远程武器会让女仆做玩家做不到的事。详见 isRiptide。
        if (isRiptide(stack)) {
            return false;
        }
        return stack.m_41720_() instanceof net.minecraft.world.item.ProjectileWeaponItem
                || stack.m_41720_() instanceof net.minecraft.world.item.TridentItem
                || GunCompat.isGun(stack);
    }

    /**
     * 是否**激流**三叉戟（`Enchantments.f_44957_` = RIPTIDE，1.20.1 字节码实证：
     * `EnchantmentHelper.m_44932_` 的方法体就是读这个字段）。
     *
     * v1.2.0 实测五百三十二。用户提问："TLM是怎么处理三叉戟的激流附魔的？
     * 我觉得我们的空袭也需要注意一下这个，理论上激流三叉戟应被判定为近战武器。"
     *
     * 【原版语义（`TridentItem` 两版字节码逐条对上）——激流把三叉戟变成"近战突进"】：
     * <ul>
     *   <li>`use`（1.20.1 `m_7203_`）：激流 > 0 且**不在水中/雨中** → 直接 return，
     *       连"举起来蓄力"都不允许；</li>
     *   <li>`releaseUsing`（1.20.1 `m_5551_`）：激流 > 0 且不在水中/雨中 → 直接 return；
     *       而**激流 = 0 才会 `new ThrownTrident`**（字节码里投掷那一支有 `iload 7; ifne` 跳过）；
     *       激流 > 0 的分支走的是 `player.startAutoSpinAttack(...)` + `push(...)` 旋转突进。</li>
     * </ul>
     * 也就是说：**带激流的三叉戟永远不是投掷武器**，它是靠旋转突进贴脸的近战武器。
     *
     * 【TLM 怎么处理的】**完全没处理**——两个版本的 jar 里搜不到任何激流标识
     * （`m_44932_` / `getTridentSpinAttackStrength` / `startAutoSpinAttack` 全部 0 命中，
     * 只剩 geckolib 那个客户端动画绑定读 `isAutoSpinAttack`）。TLM 的 `TaskTridentAttack`
     * 只做了"剥忠诚"一件事，投掷时**不看激流**——所以在 TLM 里激流三叉戟照样被投出去，
     * 等于一个有玩家在手上绝对投不出去的附魔，在女仆手上变成了普通三叉戟。
     * 本模组的 `throwTrident` 原本照搬 TLM，因此继承了这个不一致。
     *
     * 【本模组的口径】既然原版里它投不出去，就按**近战武器**归类：
     * 远程空袭里它不算"远程武器"（三件套不齐 → 不起飞并提示缺远程武器），
     * 近战空袭里它照旧算近战武器（镐/弓/弩/御币之外带攻击力属性的都算），
     * 由俯冲猛击那一记去结算——与"玩家拿它在水里突进"是同一档的近战用法。
     */
    public static boolean isRiptide(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            return net.minecraft.world.item.enchantment.EnchantmentHelper.m_44932_(stack) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 本任务口径的"武器位"判据（远战认远程武器，近战认近战武器）。
     *
     * 【刻意**不**在这里放行激流三叉戟（v1.2.4 实测六百四十一）】这个判据还喂给
     * {@link #equip}（换主手武器）与 {@code resolveRangedWeapon}（找一个远程武器去数弹药）——
     * 在这里放行会同时把"远程空袭的主手换成三叉戟"和"三叉戟算远程武器"两件事一起带进来，
     * 那是玩家做不到的事（三叉戟投不出去，见 {@link #isRangedWeapon} 的 五百三十二 那段）。
     * "远程空袭也认这把武器能起飞"这件事单独由 {@link #hasWeaponForFlightTask} 承担。
     */
    public static boolean isWeaponForTask(EntityMaid maid, ItemStack stack) {
        return isRangedTask(maid) ? isRangedWeapon(stack) : isMeleeWeapon(stack);
    }

    /**
     * v1.2.4 实测六百四十一【三件套里的"武器位"：远程空袭也认激流三叉戟】。
     *
     * 反馈原文："远程空袭不认这把武器能够起飞。"
     *
     * 【为什么原本不认】实测五百三十二 把激流三叉戟归成"近战武器"（当时是用户自己的口径：
     * "理论上激流三叉戟应被判定为近战武器"），于是远程任务里 {@link #isWeaponForTask} 直接把它
     * 排除 → {@link #hasWeapon} 为假 → {@link #isModeActive} 直接不成立 → 她**连模式都进不去**，
     * 更别提起飞（缺件提示还会报"远程武器"）。
     *
     * 【现在的口径】远程任务下**优先远程武器**（背包里真有弓/弩/枪械时一切照旧：自动装备换的
     * 还是那把远程武器，主手不会莫名变成三叉戟）；她**没有任何远程武器**时，那把激流三叉戟顶替
     * 武器位——放行的只是"能起飞"（它不能开火：远程空袭的开火那一支 {@code hasUsableRangedWeapon}
     * 判据一字未动，她拿不出远程武器时就只是不开火，不做玩家做不到的事）。
     */
    public static boolean hasWeaponForFlightTask(EntityMaid maid) {
        if (hasWeapon(maid)) {
            return true;
        }
        return isRangedTask(maid) && hasRiptide(maid);
    }

    /** 近战武器判据：与自动装备同一口径（镐/弓/弩/御币排除，其余带攻击力属性的都算） */
    public static boolean isMeleeWeapon(ItemStack stack) {
        return com.maidsmart.task.MaidToolAutoEquip.isMeleeWeapon(stack);
    }

    /**
     * v1.2.0 实测四百九十五：【远程空袭】的弹药门禁——没弹药就不必起飞。
     *
     * 需求原文："远程空袭激活时还需要检测一下有没有对应的弹药，否则没必要起飞。"
     *
     * 【为什么不能一刀切】远程武器里**只有一部分消耗弹药**，照 TLM 自己的口径分三类：
     * ```
     *   弓 / 弩          → 需要箭（弩还额外认烟花火箭当弹药）  ← TLM TaskBowAttack/TaskCrossBowAttack
     *   枪械（TACZ/SBW）  → 需要子弹（能量武器内部充能、免检）    ← GunCompat.hasGunAndAmmo 已处理
     *   御币 / 三叉戟     → **不消耗弹药**（弹幕用武器本体、三叉戟投的是自己）
     * ```
     * 后两类在 TLM 里也没有弹药门禁（`TaskDanmakuAttack` / `TaskTridentAttack` 都没有
     * hasArrow 之类的判据，反编译实证），所以这里对它们一律**放行**——否则"持御币/三叉戟
     * 的女仆永远判定缺弹药、永远不起飞"，那才是真正的新 bug。
     *
     * v1.2.0 实测五百零一【这三类必须排在最前面判】：御币虽然"不需要弹药"，但它是
     * `ProjectileWeaponItem` 的**子类**（`ItemHakureiGohei extends ProjectileWeaponItem`，
     * 两版 TLM 字节码实证），所以一旦把它放在下面那条 `ProjectileWeaponItem` 分支之后，
     * 它就会被"数箭"那一路吃掉 —— 这正是"检测御币时显示没有弹药"的根因。
     * 判据顺序：枪械 → **御币** → **三叉戟** → 弩 → 其它弹射武器。
     *
     * 【箭的判据照抄 TLM】用**武器自己的** `supportedProjectiles` 谓词在背包里找
     * （`TaskBowAttack.findArrow` 就是 `ItemsUtil.findStackSlot(inv, bow.m_6437_())`）——
     * 这样模组弹射武器也能用它们自己的弹药，不是只认原版箭。
     *
     * @return true = 该武器不需要弹药，或者需要且背包里确实有
     */
    public static boolean hasAmmoForRanged(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        return hasAmmoForWeapon(maid, resolveRangedWeapon(maid));
    }

    /**
     * v1.3.0「扫帚模式」共用的弹药口径——**唯一实现**：上面那个入口与扫帚模式只是"找哪一把武器"
     * 不同（一个按任务分流、一个按任务无关的远程判据），弹药本身的判据共用这一份，
     * 不再抄第二份（本模组反复强调的红线：同一个口径只能有一处实现）。
     */
    public static boolean hasAmmoForWeapon(EntityMaid maid, ItemStack weapon) {
        if (maid == null) {
            return false;
        }
        try {
            if (weapon == null || weapon.m_41619_()) {
                return false;
            }
            // 枪械：走 GunCompat（口径由枪械 mod 自己回答，见 GunCompat.canFeed 的类注释）
            // v1.3.0 实测六百六十六：改成**按这把枪**问——旧版是"她身上任意一把枪有弹就算"，
            // 于是"手里是喂不上弹的重武器/蓄力枪、背包里另有一把能用的手枪"时模式照样激活，
            // 她就一直绕圈不开火（粉丝反馈"一直绕圈但不射击"的成因；开火用的就是这把枪）
            if (GunCompat.isGun(weapon)) {
                return GunCompat.canFeed(maid, weapon);
            }
            // v1.2.0 实测五百零一【御币被误判缺弹药】：御币 **不消耗弹药**（弹幕用武器本体），
            // 但它是 `ProjectileWeaponItem` 的子类（`ItemHakureiGohei extends ProjectileWeaponItem`，
            // 两版 TLM 字节码实证），所以**必须在下面那条 ProjectileWeaponItem 分支之前判掉**——
            // 否则会落到"数箭"那一路，手上没箭就判成缺弹药、远程空袭永不激活
            // （反馈原文："检测远程武器御币的时候会显示没有弹药。明明这个武器不需要弹药来驱动的。"）。
            // 判据用 TLM 自带的 `isGohei`，与开火路径 `MaidFlightRangedTask#isGohei` 同一口径。
            if (isGohei(weapon)) {
                return true;
            }
            // 三叉戟：**不消耗弹药**（投的是自己）。它本来就不是 ProjectileWeaponItem，
            // 显式写出来是为了让门禁与开火路径的三条"无需弹药"通道逐条对齐、不漏。
            if (weapon.m_41720_() instanceof net.minecraft.world.item.TridentItem) {
                return true;
            }
            // 弩：烟花火箭 **或** 箭。
            //
            // v1.2.0 实测五百三十三【不再要求"带爆炸"】：旧版只认带 `Explosions` 的烟花，
            // 玩家拿一叠普通烟花配弩会被判成"缺弹药"→ 一发不开
            // （反馈原文："女仆不认烟花火箭是弩的弹药"）。**原版 `CrossbowItem` 的弹药谓词
            // 认的就是任意烟花火箭**，不看有没有爆炸组件；这里对齐原版，
            // 并与开火路径 {@link #takeBestCrossbowFirework} 严格同一口径。
            //
            // 顺序不变（烟花优先、其次箭；TLM `TaskCrossBowAttack.hasAmmunition` 同款），
            // 但**具体用哪一枚由 {@link #takeBestCrossbowFirework} 按威力挑**：
            // 威力 = 爆炸条目数（= 合成时用的烟火之星个数），原版伤害 = `5 + 2×条目数`
            // （字节码实证）——所以"好烟花"先用，不会一上来就烧普通燃料烟花。
            //
            // v1.2.0 实测五百三十五："普通烟花算不算弹药"由配置
            // `combat.crossbowPlainFirework` 决定（见 {@link #hasAmmoFirework}）。
            if (weapon.m_41720_() instanceof net.minecraft.world.item.CrossbowItem) {
                if (hasAmmoFirework(maid)) {
                    return true;
                }
                return hasArrowFor(maid, weapon);
            }
            // 弓 / 其它弹射武器（含模组弹射物）：判据与开火时的 findArrow 完全对齐
            if (weapon.m_41720_() instanceof net.minecraft.world.item.ProjectileWeaponItem) {
                return hasArrowFor(maid, weapon);
            }
            // 其它：不消耗弹药 → 放行
            return true;
        } catch (Throwable ignored) {
            return true; // 判据异常时放行，宁可起飞也不要卡死（旧行为）
        }
    }

    /**
     * 是否 TLM 御币（博丽/早苗两种）。判据照抄 TLM 自带的 `ItemHakureiGohei.isGohei`，
     * 与开火路径 `MaidFlightRangedTask#isGohei` 同一口径——门禁与开火必须一致，
     * 否则会出现"判定有弹药却没按弹幕打"或"能打弹幕却不起飞"。
     */
    public static boolean isGohei(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            return com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 解析"实际会被使用的那把远程武器"——**必须与 {@link #equip} 的选武器口径一致**。
     *
     * 为什么不能直接看主手：`isModeActive` 是在 `equip` **之前**被调用的，那一刻主手
     * 可能还是空的、而弓和箭都在背包里。若按主手判弹药就会误判"缺弹药"→ 永远不激活
     * （把本来能用的配置锁死，属于比原问题更糟的回归）。
     * `equip` 的规则是：主手已是本任务合法武器就不换，否则从背包取第一把合法的。
     * 这里照抄同一条规则。
     */
    private static ItemStack resolveRangedWeapon(EntityMaid maid) {
        return resolveWeapon(maid, s -> isWeaponForTask(maid, s));
    }

    /**
     * v1.3.0「扫帚模式」：按**任务无关**的口径解析"她实际会用的那把远程武器"——与上面那个共用
     * 同一份实现（{@link #resolveWeapon}），只是判据换成 {@link #isRangedWeapon}。扫帚模式不是
     * 飞行任务，{@code isWeaponForTask} 会按"近战"分流，用它必然解析不出远程武器。
     */
    public static ItemStack resolveRangedWeaponAny(EntityMaid maid) {
        return resolveWeapon(maid, MaidFlightKit::isRangedWeapon);
    }

    /** 找武器的**唯一实现**（主手优先，其次背包按槽序）——上面两个入口共用。 */
    private static ItemStack resolveWeapon(EntityMaid maid, StackFilter filter) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            if (filter.test(maid.m_21205_())) {
                return maid.m_21205_();
            }
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && filter.test(s)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /**
     * 背包（含副手）里是否有可用的箭。**与开火路径 `MaidFlightRangedTask#findArrow`
     * 的三层判据一一对齐**——激活检测与实际能不能打出去必须是同一口径，否则会出现
     * "判定有弹药但打不出去"或"能打却不起飞"。
     * ```
     *   ① 副手是箭
     *   ② 背包里有武器 supportedProjectiles 认可的弹药（弓弩认箭、模组弹射物认自己的）
     *   ③ 背包里有任意箭（与 findArrow 的最后兜底一致）
     * ```
     */
    private static boolean hasArrowFor(EntityMaid maid, ItemStack weapon) {
        try {
            if (maid.m_21206_().m_41720_() instanceof net.minecraft.world.item.ArrowItem) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.function.Predicate<ItemStack> supported = null;
            if (weapon.m_41720_() instanceof net.minecraft.world.item.ProjectileWeaponItem pwi) {
                supported = pwi.m_6437_();
            }
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            if (supported != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (!s.m_41619_() && s.m_41720_() instanceof net.minecraft.world.item.ArrowItem
                            && supported.test(s)) {
                        return true;
                    }
                }
            }
            // 兜底：任意箭（与 findArrow 第三层一致）
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.m_41619_() && s.m_41720_() instanceof net.minecraft.world.item.ArrowItem) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 身上是否有烟花火箭 */
    public static boolean hasFirework(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        // 实测六百〇九：含"被我们自己的动作表现借走的那件副手物品"——见 borrowedOffhand
        if (hasInHandsOrBorrowed(maid, MaidFlightKit::isFirework)) {
            return true;
        }
        return hasInBackpack(maid, MaidFlightKit::isFirework);
    }

    /** 实测五百六十三：身上是否有暮色森林孔雀羽扇（软兼容——暮色森林不在场恒 false） */
    public static boolean hasFan(EntityMaid maid) {
        return TwilightFanKit.hasFan(maid);
    }

    /**
     * v1.2.4 实测六百三十三【激流三叉戟也能当飞行推进剂】：找她身上的激流三叉戟（没有则 EMPTY）。
     *
     * 【找哪三处】**主手 → 副手 → 背包**（与 六百三十三 的原始口径一致）。实测六百三十八
     * 曾把这里收成"只认背包"（当时的理由是"主手那件是玩家的武器，不该被当成燃料"），
     * **实测六百四十 按玩家要求改回来了**——玩家原话：「从"主手 → 副手 → 背包"改成只扫背包，
     * 改回去」。手里举着激流三叉戟时她照样要能用它起飞/推进，否则"只带一把戟、正好拿在手上"
     * 的存档会被判成没有推进剂；两侧（判定与取用）现在是同一口径。
     *
     * 【耐久扣在谁身上】找到哪一件就用哪一件（{@code hurtAndBreak} 就在那一件 stack 上，
     * 见 {@code MaidTridentSpinBehavior#boostForFlight} 与 {@code MaidRiptideBoost#ignite}）——
     * 也就是说"拿在手上用的就是手上那把、装包里用的就是包里那把"，不会有歧义。
     *
     * 【近战突进仍只看主手】{@link MaidTridentSpinBehavior#spinWeapon} 要求的"主手举着激流三叉戟"
     * 一条没动：那是攻击起手（与原版一致），不走本方法。
     *
     * 【额外容器】这三处都没有时，用 {@link #fetchRiptide} 再问一次精妙背包 / 旅行者背包
     * （TLM 的额外容器体系，见 {@link com.maidsmart.tool.MaidExtraContainer}）。
     */
    public static ItemStack findRiptide(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            if (isRiptide(maid.m_21205_())) {
                return maid.m_21205_(); // 主手
            }
            if (isRiptide(maid.m_21206_())) {
                return maid.m_21206_(); // 副手
            }
        } catch (Throwable ignored) {
        }
        try {
            net.minecraftforge.items.IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isRiptide(inv.getStackInSlot(i))) {
                    return inv.getStackInSlot(i);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /**
     * v1.2.4 实测六百三十九【先判背包，再判精妙背包】：**真要动手推一口时**调这个。
     * 主手/副手/背包里有 → 直接用；都没有 → 请 TLM 的额外容器系统（精妙背包 **或旅行者背包**）
     * 把激流三叉戟搬进她的背包，然后再找一次（{@link com.maidsmart.tool.MaidExtraContainer#pull}）。
     *
     * 【与 findRiptide 的分工】findRiptide 是纯读取（判定/气泡/日志都用它，不许有副作用）；
     * 本方法会真的搬东西，所以只在"已经决定要用"的那几处（空袭抬升/俯冲、起飞与飞行跟随）
     * 调用。搬进来的三叉戟落在她背包里，扣的还是它自己的耐久。
     *
     * 【额外容器是"两个 mod 一套代码"】TLM 的额外容器体系里，精妙背包（{@code SBackpackSlotRef}）
     * 与旅行者背包（{@code TBackpackSlotRef}）是**同一个接口下的两个实现**（javap 实证：
     * 两者都 extends {@code CuriosSlotRef}、都按 {@code ForgeCapabilities.ITEM_HANDLER} 操作
     * 饰品栏里那件背包），而本方法走的是 TLM 自己的容器列表——所以**旅行者背包与精妙背包
     * 从第一天起就是同一条路**，不需要各写一份。
     */
    public static ItemStack fetchRiptide(EntityMaid maid) {
        ItemStack own = findRiptide(maid);
        if (!own.m_41619_()) {
            return own;
        }
        if (com.maidsmart.tool.MaidExtraContainer.pull(maid, MaidFlightKit::isRiptide, 1)) {
            return findRiptide(maid);
        }
        return ItemStack.f_41583_;
    }

    /** 身上是否有激流三叉戟。
     *  v1.2.4 实测六百三十三：**"在身上"包含被我们自己的动作表现借走的那件副手物品**
     *  ——与 {@link #hasFan} 同款（见 {@link #borrowedOffhand}）。
     *  v1.2.4 实测六百四十：**主手/副手也算**（findRiptide 恢复三处都找，见那里的注释），
     *  于是"能不能起飞/有没有燃料"的判定与取用两侧完全同口径。
     *  开关（`combat.riptideDash`）判在**这里**、与位移法术那一支（{@link #hasClimbSpell} 里判
     *  `combat.flightDashClimb`）同一口径：关掉它，这一条腿既不算"能飞的道具"、也不会真去推
     *  ——否则会出现"判定说她能飞、她却不飞"的分裂（气泡说齐了、人不动，正是本模组吃过亏的那类）。 */
    public static boolean hasRiptide(EntityMaid maid) {
        if (maid == null || !com.maidsmart.config.MaidSmartConfig.RIPTIDE_DASH_ENABLE.get()) {
            return false;
        }
        if (!findRiptide(maid).m_41619_()) {
            return true;
        }
        // v1.2.4 实测六百三十九：精妙背包/旅行者背包里的也算"她身上有"（只读探针，不搬动）
        if (com.maidsmart.tool.MaidExtraContainer.contains(maid, MaidFlightKit::isRiptide)) {
            return true;
        }
        return isRiptide(borrowedOffhand(maid));
    }

    /**
     * 实测五百六十三：飞行燃料 = 烟花火箭 <b>或</b> 暮色森林孔雀羽扇。
     * 三件套的"燃料件"从单一烟花扩为二选一——有扇先用扇（推进照搬扇子自己的
     * 公式、每挥一次按扇子自己的口径扣耐久），没扇才烧烟花。
     *
     * （v1.2.2 实测六百一十三：要判"她到底能不能飞起来"请用 {@link #hasFlightPropellant}——
     * 那是三选一（含不需要物资的位移法术）；本方法只是其中"可燃的那两件"。）
     */
    public static boolean hasFlightFuel(EntityMaid maid) {
        return hasFirework(maid) || hasFan(maid);
    }

    /**
     * v1.2.2 实测六百一十三【飞行跟随的启动并入位移法术】：她"能飞起来"的**道具件**口径——
     * 烟花火箭 / 孔雀羽扇（{@link #hasFlightFuel}）**或**能上天的位移法术（{@link #hasClimbSpell}）。
     *
     * 【为什么收敛成一个方法】这个"三选一"原先在空袭激活（{@link #isModeActive}）与缺件气泡
     * （{@link #missingParts}）里各写一遍，六百一十三 又要给飞行跟随的启动门禁加同一件事——
     * 再加一遍就是第四份。口径必须**只有一处定义**：这一类的账本模组里已经吃过好几次亏
     * （"气泡说齐了、她却不飞"就是两处判据写歪出来的）。现在空袭与飞行跟随共用这一份。
     *
     * 【开关归属】位移法术那一支由空袭的「位移法术·起飞/补高」开关
     * （{@code combat.flightDashClimb}，默认开）管：关掉它 → 空袭与飞行跟随**同时**退回
     * 只认烟花/羽扇，两处口径依然一致（不需要再开一个新开关）。
     *
     * 【与 {@link #hasFlightFuel} 的区别】那个是"有没有可燃的道具"（烟花/羽扇，补推要扣它）；
     * 这个是"能不能飞起来"（含不消耗物资的法术与只扣三叉戟耐久的激流）。要判断"能不能起飞"
     * 一律用这一个。
     *
     * v1.2.4 实测六百三十三【第四条腿 = 激流三叉戟】：推进剂再添一件——**带激流附魔的三叉戟**
     * 也能把她推起来（沿视线推原版那一口 `3.0 × (1 + 等级) / 4`，见
     * {@code MaidTridentSpinBehavior#boostForFlight}），于是"只带一把激流三叉戟、没有烟花/
     * 羽扇/法术"的女仆也能起飞、也能启动飞行跟随。开关沿用 `combat.riptideDash`
     * （激流三叉戟旋转突进，默认开）——**不新增配置项**，关掉它这一条腿也一起退回。
     */
    public static boolean hasFlightPropellant(EntityMaid maid) {
        return hasFlightFuel(maid) || hasClimbSpell(maid) || hasRiptide(maid);
    }

    /* v1.2.0 实测五百三十三：原 `hasExplosiveFirework`（只认带爆炸的烟花）已删除——
     * 弩的弹药门禁改为直接复用 {@link #hasFirework}（任意烟花火箭都算），
     * 见 `hasAmmoForRanged` 的弩分支。 */

    /**
     * v1.2.0 实测五百七十二【位移法术可顶替烟花】：她有没有"能把自己顶上天"的位移法术。
     *
     * 需求（法术模组作者转达的玩家反馈）：位移类法术（如「升腾」）也要能用于**平地起飞**。
     * 而空袭原来把"烟花火箭"写死在激活条件里——没有烟花模式压根不激活，那条路永远走不到。
     * 所以这里给烟花开一个替代件：带得起飞法术 = 推进剂不缺。
     *
     * 结果按 20 tick 缓存（isModeActive 每 tick 会被问好几次，而扫她的书单是反射调用）。
     */
    private static final java.util.Map<java.util.UUID, Boolean> DASH_KIT_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Long> DASH_KIT_CACHE_TICK = new java.util.HashMap<>();
    private static final long DASH_KIT_CACHE_INTERVAL = 20L;

    /** 实测五百七十六：女仆入世界（魂符放出/区块重载/跨维）时清掉她的法术书缓存——见负缓存注释 */
    public static void forgetClimbSpellCache(java.util.UUID maidId) {
        if (maidId == null) {
            return;
        }
        DASH_KIT_CACHE.remove(maidId);
        DASH_KIT_CACHE_TICK.remove(maidId);
    }

    public static boolean hasClimbSpell(EntityMaid maid) {
        if (maid == null
                || !com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB.get()) {
            return false;
        }
        try {
            long now = maid.m_9236_().m_46467_();
            Long cached = DASH_KIT_CACHE_TICK.get(maid.m_20148_());
            if (cached != null && now - cached < DASH_KIT_CACHE_INTERVAL) {
                return Boolean.TRUE.equals(DASH_KIT_CACHE.get(maid.m_20148_()));
            }
            // 【必须用不看冷却的那个】否则她每冲刺一次（写回 2 秒冷却）模式就掉回未激活
            String[] ids;
            try {
                ids = com.maidsmart.config.MaidSmartConfig.COMBAT_FLIGHT_DASH_CLIMB_SPELLS.get()
                        .toArray(new String[0]);
            } catch (Throwable ignored) {
                ids = com.maidsmart.combat.MaidSpellCastCompat.DEFAULT_CLIMB_SPELLS;
            }
            boolean has = com.maidsmart.combat.MaidSpellCastCompat.hasDashSpell(maid, ids);
            DASH_KIT_CACHE.put(maid.m_20148_(), has);
            // 实测五百七十六：**负结果只缓存 5 tick**——刚放出来时法术数据要一拍才就绪，
            // 若把那一拍的 false 缓存满 20 tick，缺件提示会在这段时间里一直误报（同 NOTIFY_GRACE_TICKS）。
            DASH_KIT_CACHE_TICK.put(maid.m_20148_(), has ? now : now - (DASH_KIT_CACHE_INTERVAL - 5L));
            return has;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 三要素齐备 = 模式激活（缺一即未激活，行为退回普通攻击模式）。
     *
     * v1.2.0 实测四百九十五：**远程空袭**额外要求弹药（见 {@link #hasAmmoForRanged}）——
     * 没箭/没子弹就没必要起飞（需求："否则没必要起飞"）。近战空袭不受影响。
     *
     * v1.2.0 实测五百七十二：推进剂那一件改成 **烟花火箭 或 能上天的位移法术**——
     * 这也是"平地起飞"能成立的前提（没有烟花时模式必须照样激活，否则那条分支永远走不到）。
     */
    public static boolean isModeActive(EntityMaid maid) {
        // v1.2.0 实测五百七十二：推进剂 = 烟花 / 羽扇（hasFlightFuel）**或**能上天的位移法术——
        // 后者是"平地起飞"能成立的前提（没有烟花时模式必须照样激活，否则那条分支永远走不到）
        // v1.2.4 实测六百三十三：改成直接调 hasFlightPropellant——原来这里把"两选一"又抄了一遍，
        // 添第四条腿（激流三叉戟）时正是"抄第二遍"最容易漏的地方（本就该只有一份口径）。
        if (!(hasElytra(maid) && hasWeaponForFlightTask(maid) && hasFlightPropellant(maid))) {
            return false;
        }
        return rangedAmmoOk(maid);
    }

    /**
     * 【实测六百七十七】**任务无关**的"空袭三件套齐"——给"扫帚模式的女仆也能报出这一句"用。
     *
     * <p>玩家原话："当空袭模式处于激活状态（集齐三件套或者其他的，已达成激活的条件），
     * 扫帚模式条件满足（自己已经坐到了扫帚上，并且手中拿有远程武器）的时候，会说一句，
     * 自己已经准备好了。"
     *
     * <p>【为什么不能直接用 {@link #isModeActive}】那一条里的"武器位"是**按当前任务分流**的
     * （{@link #hasWeaponForFlightTask} → {@link #isWeaponForTask}：远程任务要远程武器、
     * 其余任务要近战武器）。而这一句的触发场合是**扫帚模式**——她的任务是扫帚、不是飞行任务，
     * 于是同一个背包会被 {@code isModeActive} 判成"缺武器"（它转头去数近战武器了）。
     * 所以她能不能空袭这件事必须按**任务无关**的口径问：鞘翅 / 远程武器 / 推进剂 / 弹药。
     * 四件**全部复用既有判据**（{@link #hasElytra}、{@link #hasRangedWeaponOnly}、
     * {@link #hasFlightPropellant}、{@link #hasAmmoForWeapon}），不新写第二份"什么算鞘翅"——
     * 弹药那两行与 {@link MaidBroomKit#ammoOk} 是同一份口径（本模组"同一个口径只有一处"）。
     */
    public static boolean airAssaultReady(EntityMaid maid) {
        try {
            if (!(hasElytra(maid) && hasRangedWeaponOnly(maid) && hasFlightPropellant(maid))) {
                return false;
            }
            return hasAmmoForWeapon(maid, resolveRangedWeaponAny(maid));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.2.4 实测六百四十一【远程空袭的弹药门禁：只有"真拿得出远程武器"时才要求弹药】。
     *
     * 反馈原文："远程空袭不认这把武器能够起飞。"旧版这里是直白的
     * {@code !isRangedTask(maid) || hasAmmoForRanged(maid)}，而 {@code hasAmmoForRanged} 在
     * "背包里没有远程武器"时恒为假——于是"只带一把激流三叉戟"的远程空袭**模式都激活不了**
     * （{@link #hasWeaponForFlightTask} 那一头 六百四十一 已经放行三叉戟顶武器位，这一头必须
     * 跟着放行，否则两头口径打架、她的表现仍是"不认这把武器"）。
     *
     * 口径：有远程武器 → 按老规矩要弹药（缺箭/缺子弹照旧不放行，提示照报）；**没有远程武器、
     * 武器位由激流三叉戟顶着** → 不吃弹药（它本来就不耗弹药，见 {@link #hasAmmoForRanged} 的
     * 三类划分）。两者都没有 → 仍然不放行。
     */
    public static boolean rangedAmmoOk(EntityMaid maid) {
        if (!isRangedTask(maid)) {
            return true;
        }
        if (hasAmmoForRanged(maid)) {
            return true;
        }
        // 兜底那一支：她拿不出远程武器（所以"没有弹药"这件事无从谈起），而武器位由三叉戟顶着
        return !hasRangedWeaponOnly(maid) && hasRiptide(maid);
    }

    /** "她到底有没有远程武器"——只看 {@link #isRangedWeapon}，不看 六百四十一 的顶替口径 */
    // v1.3.0：改成 public——「扫帚模式」的"有没有远程武器"判据直接复用这一条（任务无关），
    // 不再另写一份（见 MaidBroomKit.hasRangedWeapon）。
    public static boolean hasRangedWeaponOnly(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (hasInHandsOrBorrowed(maid, MaidFlightKit::isRangedWeapon)) {
            return true;
        }
        return hasInBackpack(maid, MaidFlightKit::isRangedWeapon);
    }

    /**
     * v1.2.0 实测四百七十四：未激活时缺哪一件的**可读原因**（气泡/系统消息用）。
     *
     * 需求：两种空战没进入激活状态时，给玩家一条气泡 + 系统消息说明。只说
     * "未激活"没用——玩家不知道缺什么；所以按缺件逐条报，缺多件就一起报。
     * 顺序固定（鞘翅 → 武器 → 可以飞行的道具 → 弹药），与 isModeActive 的判定口径完全一致。
     *
     * @return null = 三件齐备（不该调用）；否则形如「鞘翅、可以飞行的道具」
     */
    public static String missingParts(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!hasElytra(maid)) {
            sb.append("鞘翅");
        }
        if (!hasWeaponForFlightTask(maid)) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(isRangedTask(maid) ? "远程武器" : "近战武器");
        }
        // 实测五百六十三：燃料件 = 烟花或羽扇；实测五百七十二：位移法术也能顶上；
        // 实测五百七十四【文案口径统一】：这里只报「可以飞行的道具」——不再点名烟花/羽扇。
        // 反馈："既然法术可以飞，那无法启动空袭的消息应该改成'没有可以飞行的道具'，
        // 而不仅仅是烟花/羽扇。" 三种手段（烟花火箭 / 孔雀羽扇 / 能上天的位移类法术）任一即可，
        // 具体是哪三种由气泡尾注与手册展开说明。
        // 六百一十三：判据改调 hasFlightPropellant——空袭与飞行跟随共用同一份"能飞的道具"口径
        // （文案一字不变，仍然只报「可以飞行的道具」）。
        if (!hasFlightPropellant(maid)) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("可以飞行的道具");
        }
        // v1.2.0 实测四百九十五：远程空袭还要报"缺弹药"（否则玩家只看到"三件齐了却没起飞"，
        // 完全不知道为什么——这正是本次需求要修的可观测性问题）。
        // v1.2.4 实测六百四十一：判据换成 rangedAmmoOk——"武器位由激流三叉戟顶着、身上没有远程
        // 武器"这一档不该报缺弹药（那本来就不吃弹药，见 {@link #rangedAmmoOk}）。
        if (isRangedTask(maid) && hasElytra(maid) && hasWeaponForFlightTask(maid)
                && hasFlightPropellant(maid) && !rangedAmmoOk(maid)) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("弹药");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /* ---------------- 穿戴 ---------------- */

    /**
     * 穿戴装备：胸甲槽穿鞘翅、主手换武器。
     *
     * v1.2.0 实测五百一十：**不再占用副手**（旧版把烟花常驻副手，导致盾牌/食物装不进去、
     * 空战无法进食回血）。烟花改为按需从背包取用——发射路径自己造弹体，不需要手持。
     *
     * @return 是否已全部就位（true = 可起飞）
     */
    public static boolean equip(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        boolean ok = true;
        // 胸甲槽：鞘翅（updateFallFlying 只认 CHEST 槽的鞘翅，这一步是滑翔能否持续的关键）
        // v1.2.0 实测五百五十五：判据放宽到 isElytraLike——玩家给女仆穿的是"鞘翅胸甲"
        // 这类模组装备时**原地不动**（旧的严格判据会把它当成"没穿鞘翅"，转手把它换下来
        // 塞回背包，等于把玩家的护甲扒了）。
        if (!isElytraLike(maid.m_6844_(EquipmentSlot.CHEST), maid)) {
            // 实测五百五十五补充【取用优先级】：背包里同时有普通鞘翅和"鞘翅胸甲"这类装备时
            // **优先穿带护甲的那件**（既滑翔又当护甲，对玩家更划算）；没有护甲型才退回普通鞘翅。
            // 两趟扫描而不是一次谓词：takeFromBackpack 取的是第一个匹配，想要"优先"必须分两趟问。
            ItemStack ely = takeFromBackpack(maid, s -> isElytraLike(s, maid) && isArmorElytra(s));
            if (ely.m_41619_()) {
                ely = takeFromBackpack(maid, s -> isElytraLike(s, maid));
            }
            if (ely.m_41619_()) {
                // 背包没有就从手上来（hasElytra 认主/副手，可穿戴口径必须一致）
                IItemHandlerModifiable h = (IItemHandlerModifiable) maid.getHandsInvWrapper();
                for (int slot = 0; slot <= 1 && ely.m_41619_(); slot++) {
                    if (isElytraLike(h.getStackInSlot(slot), maid) && isArmorElytra(h.getStackInSlot(slot))) {
                        ely = h.extractItem(slot, 1, false);
                    }
                }
                for (int slot = 0; slot <= 1 && ely.m_41619_(); slot++) {
                    if (isElytraLike(h.getStackInSlot(slot), maid)) {
                        ely = h.extractItem(slot, 1, false);
                    }
                }
                // v1.3.0(beta) 实测六百八十【第三来源：饰品栏（Curios）】：玩家原话"在饰品栏里面的
                // 鞘翅应该也可以作为启动我们的空袭的激活条件之一，并且等效消耗耐久"——把她饰品栏里
                // 那件鞘翅**取出来穿到胸甲槽**：原版滑翔闸门（updateFallFlying）只认胸甲槽的鞘翅，
                // 穿上之后耐久就由原版"滑翔每 20 tick 扣 1 点"照常扣——与从背包翻出一件穿上
                // **同一条路**，所以说"等效"。取用优先级排在背包/双手之后（不强抢她手上正拿的）。
                if (ely.m_41619_()) {
                    ItemStack fromCurios = CuriosElytraCompat.takeElytra(maid,
                            s -> isElytraLike(s, maid) && isArmorElytra(s), ItemStack.f_41583_);
                    if (fromCurios == null) {
                        fromCurios = CuriosElytraCompat.takeElytra(maid,
                                s -> isElytraLike(s, maid), ItemStack.f_41583_);
                    }
                    if (fromCurios != null) {
                        ely = fromCurios;
                    }
                }
            }
            if (!ely.m_41619_()) {
                ItemStack old = maid.m_6844_(EquipmentSlot.CHEST);
                maid.m_8061_(EquipmentSlot.CHEST, ely);
                if (!old.m_41619_()) {
                    giveBack(maid, old);
                }
                // 实测五百五十五补充【飞坏了会自己换】的诊断：鞘翅耐久见底会消失/失效，
                // 这一行落盘即证明"她换了下一件"；包里没得换就不会有这行（她会落地）。
                logElytraSwap(maid, ely, old);
            } else {
                ok = false;
            }
        }
        // 主手：任意近战武器；主手已有近战武器时【不换】——尊重玩家/模组的搭配
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        if (!isWeaponForTask(maid, maid.m_21205_())) {
            // 近战才有「重锤优先」这种偏好；远战直接找远程武器（弓/枪）
            ItemStack weapon = takeFromBackpack(maid, s -> isWeaponForTask(maid, s));
            if (!weapon.m_41619_()) {
                ItemStack old = hands.getStackInSlot(0);
                hands.setStackInSlot(0, weapon);
                if (!old.m_41619_()) {
                    giveBack(maid, old);
                }
            } else {
                ok = false;
            }
        }
        // 副手：v1.2.0 实测五百一十【让出副手，不再常驻烟花】。
        //
        // 旧版这里把烟花**常驻副手**（`hands.setStackInSlot(1, fw)`），且把"副手是烟花"
        // 写进了下面的就绪判定。后果（用户反馈："空袭状态下锁定了主副手物品，女仆无法在
        // 副手装盾牌和食物，我认为这是原因"）：空袭期间副手被烟花锁死，盾牌/食物装不进去；
        // 而回血吃（TLM `MaidHealSelfTask`）扫的是"主手/副手/背包"且换手时要把旧物腾出来，
        // 副手被占 → 无法进食回血（"女仆没办法在空战状态下吃东西回血"）。
        //
        // 关键事实：烟花**根本不需要拿在手上**——发射走的是
        // `MaidFlightCombatBehavior.launchFirework`，它自己 `new ItemStack(Items.FIREWORK_ROCKET)`
        // 造一枚全新火箭弹体并直接入世界，从不读女仆手里的物品。所以"常驻副手"纯属多余占用。
        // 现在改为：**副手完全让出**（供盾牌/食物使用），烟花只从背包按需取用
        // （`hasFirework` / `takeFirework` 都已是"主手→副手→背包"三处覆盖，不依赖副手）。
        return ok && isElytraLike(maid.m_6844_(EquipmentSlot.CHEST), maid)
                && isWeaponForTask(maid, maid.m_21205_()) && hasFirework(maid);
    }

    /* ---------------- 烟花消耗 ---------------- */

    /**
     * 取 1 枚烟花火箭（副手优先，其次主手，最后背包只抽 1 枚）。
     * 绝不能 extractItem(i, count) 抽走整叠——发射一枚挂载烟花就毁掉一整叠。
     */
    public static ItemStack takeFirework(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        ItemStack off = hands.getStackInSlot(1);
        if (isFirework(off)) {
            ItemStack one = off.m_41777_();
            one.m_41764_(1);
            off.m_41774_(1);
            return one;
        }
        ItemStack main = hands.getStackInSlot(0);
        if (isFirework(main)) {
            ItemStack one = main.m_41777_();
            one.m_41764_(1);
            main.m_41774_(1);
            return one;
        }
        return takeOneFromBackpack(maid, MaidFlightKit::isFirework);
    }

    /**
     * 取 1 枚**威力最大**的烟花火箭当弩弹药（同威力时优先手上，其次背包靠前的格子）。
     * 找不到返回空。
     *
     * v1.2.0 实测五百三十三【认烟花 + 优先威力大的】。需求原文："女仆不认烟花火箭是弩的
     * 弹药，如果要认的话，优先使用威力大（合成用烟火之星多）的烟花。"
     *
     * 【口径】候选 = **任意烟花火箭**（`isFirework`），与 `hasAmmoForRanged` 的弩分支、
     * 原版 `CrossbowItem` 的弹药谓词三处一致。
     * v1.2.0 实测五百三十五：是否包含"普通烟花"（无爆炸组件）由配置
     * `combat.crossbowPlainFirework` 决定。**实测五百三十七起默认【关】**：普通烟花在原版
     * 恒为 0 伤害（见 {@link #isExplosiveFirework}），默认只当飞行燃料；打开才认它们。
     *
     * 【为什么按威力挑】原版烟花的伤害只取决于爆炸条目数：
     * `FireworkRocketEntity.m_37087_` 字节码 = `5.0f + 2 * Explosions.size()`，
     * 不带爆炸则整段早退（0 伤害）。而"取哪一枚"旧版是"按格子顺序取第一个"——
     * 背包里普通燃料烟花排在前面时，先被烧掉的会是它们。现在按威力降序挑：
     * **威力大的先用光**，普通烟花只在没有更好的时候才当弹药。
     *
     * 【同威力时的稳定次序】副手 → 主手 → 背包（原版/TLM 口径：副手是烟花就直接当弹药）；
     * 判据用**严格大于**，所以同威力时先被看到的那一枚胜出，不会因为排序抖动而乱拿。
     *
     * 【只抽 1 枚】绝不整叠拿走——与 {@link #takeFirework} 同一套消耗语义。
     */
    public static ItemStack takeBestCrossbowFirework(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        // v1.2.0 实测五百三十五：是否允许用"普通烟花"（无爆炸组件）当弹药。
        // 关闭时只认攻击性烟花——普通烟花留着当飞行燃料，绝不被弩烧掉。
        boolean allowPlain = com.maidsmart.config.MaidSmartConfig
                .COMBAT_CROSSBOW_PLAIN_FIREWORK.get();
        IItemHandlerModifiable hands = (IItemHandlerModifiable) maid.getHandsInvWrapper();
        int bestHandSlot = -1;
        int bestPower = -1;
        for (int slot : new int[]{1, 0}) {
            ItemStack s = hands.getStackInSlot(slot);
            if (!isFirework(s)) {
                continue;
            }
            int p = fireworkPower(s);
            if (!allowPlain && p <= 0) {
                continue; // 开关关掉（默认）：普通烟花不当弹药
            }
            if (p > bestPower) {
                bestPower = p;
                bestHandSlot = slot;
            }
        }
        int bestBagSlot = -1;
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!isFirework(s)) {
                    continue;
                }
                int p = fireworkPower(s);
                if (!allowPlain && p <= 0) {
                    continue; // 开关关掉（默认）：普通烟花不当弹药
                }
                if (p > bestPower) {
                    bestPower = p;
                    bestBagSlot = i;
                }
            }
        } catch (Throwable ignored) {
        }
        if (bestBagSlot >= 0) {
            try {
                return maid.getAvailableBackpackInv().extractItem(bestBagSlot, 1, false);
            } catch (Throwable ignored) {
                return ItemStack.f_41583_;
            }
        }
        if (bestHandSlot >= 0) {
            ItemStack st = hands.getStackInSlot(bestHandSlot);
            ItemStack one = st.m_41777_();
            one.m_41764_(1);
            st.m_41774_(1);
            return one;
        }
        return ItemStack.f_41583_;
    }

    /* ---------------- 内部工具 ---------------- */

    /**
     * 可用鞘翅（**严格口径**）：原版鞘翅物品且未耗尽（`ElytraItem.isFlyEnabled` 同判据）。
     *
     * 【渲染层专用】——{@link com.maidsmart.client.LayerMaidElytra} 用它决定"要不要给女仆
     * 画我们的鞘翅翅膀"。这里是严格口径而不是宽松口径，理由是**模组的鞘翅装备自带模型**
     * （鞘翅胸甲本身就是一件胸甲，有它自己的外观），再叠一层我们的翅膀只会穿模；原版自己的
     * `ElytraLayer` 同样只认 `Items.ELYTRA`（反编译实证）。识别/穿脱请用 {@link #isElytraLike}。
     */
    public static boolean isUsableElytra(ItemStack stack) {
        return !stack.m_41619_()
                && stack.m_41720_() instanceof ElytraItem
                && ElytraItem.m_41140_(stack);
    }

    /**
     * v1.2.0 实测五百五十五【模组"内置鞘翅的装备"兼容】：宽松口径——**原版鞘翅** 或者
     * **任何自称能滑翔的物品**（鞘翅胸甲这类：不是 `ElytraItem`，是护甲 + 重写滑翔钩子）。
     *
     * 判据取 `ItemStack.canElytraFly(LivingEntity)`（Forge/NeoForge 扩展方法，javap 实证
     * `ItemStack implements IForgeItemStack` / `IItemStackExtension`）。选它的唯一理由是
     * **口径同源**：原版滑翔的闸门 `LivingEntity.updateFallFlying`（SRG `m_21323_`，aiStep 内、
     * travel 之前）在 Forge/NeoForge 里读的就是这个方法——字节码里依次调用
     * `ItemStack.canElytraFly` 与 `ItemStack.elytraFlightTick`，而原版 `ElytraItem` 的实现
     * 恰好就是 `ElytraItem.isFlyEnabled`（等于旧判据）。所以"她能不能滑翔"与"我们认不认这件
     * 装备"从此是同一个判据：不会出现"我们认了但原版每 tick 把滑翔位清掉"，也不会反过来。
     *
     * 【旧版的病】只认 `instanceof ElytraItem` → 鞘翅胸甲被当成"没有鞘翅" → 三件套永远不齐
     * → 空袭模式不激活、并且会不停播报"缺鞘翅"。
     *
     * @param entity 判据要的实体（模组实现可能按穿着者判定）；null 时退化为只认原版鞘翅
     */
    public static boolean isElytraLike(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
        if (isUsableElytra(stack)) {
            return true;
        }
        if (entity == null || stack == null || stack.m_41619_()) {
            return false;
        }
        try {
            return stack.canElytraFly(entity);
        } catch (Throwable ignored) {
            return false; // 模组实现抛异常不能把她的空袭整段带崩
        }
    }

    /** 实测五百五十五补充：既是鞘翅、又是护甲的装备（鞘翅胸甲这类）——背包取用时优先它。
     *  `ElytraItem` **不是** ArmorItem（原版鞘翅 = `Item implements Equipable`），所以这条
     *  只会命中"护甲 + 滑翔钩子"的模组装备，不会把原版鞘翅也带进来。 */
    public static boolean isArmorElytra(ItemStack stack) {
        return !stack.m_41619_() && stack.m_41720_() instanceof net.minecraft.world.item.ArmorItem;
    }

    /** 实测五百五十五补充：胸甲槽换装写一行运行日志（40 tick 节流，防模组拒绝穿戴时刷屏）。
     *  用来回答"鞘翅飞坏了会不会自己换下一件"——换了就有这行，包里没得换就不会有。 */
    private static final java.util.Map<EntityMaid, Long> ELYTRA_SWAP_LOG =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void logElytraSwap(EntityMaid maid, ItemStack now, ItemStack old) {
        try {
            long t = maid.m_9236_().m_46467_();
            Long last = ELYTRA_SWAP_LOG.get(maid);
            if (last != null && t - last < 40L) {
                return;
            }
            ELYTRA_SWAP_LOG.put(maid, t);
            String nowId;
            try {
                nowId = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(now.m_41720_()));
            } catch (Throwable ignored) {
                nowId = now.m_41720_().toString();
            }
            String oldId = "空";
            if (!old.m_41619_()) {
                try {
                    oldId = String.valueOf(
                            net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(old.m_41720_()));
                } catch (Throwable ignored) {
                    oldId = old.m_41720_().toString();
                }
            }
            com.maidsmart.tool.PromaidLog.log("空袭装备", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 胸甲槽换装：" + nowId + "（原 " + oldId + "）");
        } catch (Throwable ignored) {
        }
    }

    /**
     * v1.2.0 实测五百五十五【排查用诊断】：一行说清"鞘翅判定为什么不过"——
     * 胸甲/主手/副手各是什么物品、`canElytraFly` 钩子对女仆返回什么。
     *
     * 有了它，"模组鞘翅装备不认"这种问题一眼定位：如果日志里 `canElytraFly=false`，
     * 那就是**那个物品没实现滑翔钩子**（原版滑翔闸门认的就是它），我们认了也没用——
     * 那种装备对玩家同样滑不起来，该找的是那个模组。由 `notifyNotReady` 在播报
     * "缺鞘翅"的同一节流里落盘（最多 15 秒一行）。
     */
    public static String elytraDiagnostic(EntityMaid maid) {
        if (maid == null) {
            return "鞘翅判定: 女仆为空";
        }
        // 主人（在线时非空）——用来区分"这个物品只对玩家开放滑翔"（模组的 canElytraFly 里
        // 写死 instanceof Player 的情况：那种装备对玩家能飞、对女仆永远不行，我们认了也没用）
        net.minecraft.world.entity.player.Player owner = null;
        try {
            if (maid.m_269323_() instanceof net.minecraft.world.entity.player.Player p) {
                owner = p;
            }
        } catch (Throwable ignored) {
        }
        return "鞘翅判定: 胸甲=" + describeElytraCandidate(maid.m_6844_(EquipmentSlot.CHEST), maid, owner)
                + " 主手=" + describeElytraCandidate(maid.m_21205_(), maid, owner)
                + " 副手=" + describeElytraCandidate(maid.m_21206_(), maid, owner)
                // v1.3.0(beta) 实测六百八十：饰品栏（Curios）那一路——"我把鞘翅放她饰品栏了，
                // 她怎么还说缺鞘翅"这类现场，日志里这一行就能直接对上（没装 Curios 会写"未装 Curios"）
                + " 饰品栏=" + CuriosElytraCompat.describe(maid, s -> isElytraLike(s, maid));
    }

    private static String describeElytraCandidate(ItemStack stack, net.minecraft.world.entity.LivingEntity entity,
                                                 net.minecraft.world.entity.player.Player owner) {
        if (stack == null || stack.m_41619_()) {
            return "空";
        }
        String id;
        try {
            id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_()));
        } catch (Throwable ignored) {
            id = stack.m_41720_().toString();
        }
        boolean vanilla = isUsableElytra(stack);
        boolean hook;
        try {
            hook = stack.canElytraFly(entity);
        } catch (Throwable ignored) {
            hook = false;
        }
        String text = id + "(原版鞘翅=" + vanilla + "，canElytraFly=" + hook + ")";
        if (!vanilla && !hook && owner != null) {
            try {
                if (stack.canElytraFly(owner)) {
                    text += "← 它对玩家返回 true、对女仆 false：这个物品的滑翔是写死给玩家的，女仆用不了";
                }
            } catch (Throwable ignored) {
            }
        }
        return text;
    }

    /**
     * v1.2.2 实测六百〇九【排查用诊断】：一行说清"飞行燃料判定为什么不过"——
     * 主手 / 副手各是什么、背包里有没有烟花 / 羽扇 / 激流三叉戟、她会不会"能上天的位移法术"。
     *
     * 与 {@link #elytraDiagnostic} 同一用途、同一节流（由 {@code notifyNotReady} 落盘，
     * 最多 15 秒一行）。
     *
     * 【为什么补这一行】旧版只给"缺鞘翅"留了诊断，缺"可以飞行的道具"时运行日志里一片安静，
     * 而气泡 / 主人系统消息照发——玩家报"她明明拿着孔雀羽扇却说缺飞行道具"时，日志里
     * 查无此事，只能靠猜。本批真正的根因（本模组自己的"手上亮一下"把副手借走十几 tick）
     * 就是靠这一行读出来的：现场那一行里 `副手=` 显示的是**我们的展示件**而不是玩家的羽扇。
     */
    public static String fuelDiagnostic(EntityMaid maid) {
        if (maid == null) {
            return "燃料判定: 女仆为空";
        }
        // 实测六百四十：`身上激流三叉戟`（主手/副手/背包）与 `额外容器激流三叉戟`
        // （精妙背包 / 旅行者背包）分开报——"那两个背包里的到底认没认出来"从此一眼可见
        //（额外容器那一项走只读探针，取出 1 个立刻放回，见 MaidExtraContainer.contains）。
        boolean extra = false;
        try {
            extra = com.maidsmart.tool.MaidExtraContainer.contains(maid, MaidFlightKit::isRiptide);
        } catch (Throwable ignored) {
        }
        return "燃料判定: 主手=" + describeStack(maid.m_21205_())
                + " 副手=" + describeStack(maid.m_21206_())
                + " 背包烟花=" + hasInBackpack(maid, MaidFlightKit::isFirework)
                + " 背包羽扇=" + hasInBackpack(maid, TwilightFanKit::isFan)
                + " 身上激流三叉戟=" + !findRiptide(maid).m_41619_()
                + " 额外容器激流三叉戟=" + extra
                + " 位移法术=" + hasClimbSpell(maid);
    }

    /** 一行物品描述：`1xminecraft:elytra` / `空`（诊断日志专用） */
    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return "空";
        }
        String id;
        try {
            id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_()));
        } catch (Throwable ignored) {
            id = stack.m_41720_().toString();
        }
        return stack.m_41613_() + "x" + id;
    }

    public static boolean isFirework(ItemStack stack) {
        return !stack.m_41619_() && stack.m_150930_(Items.f_42688_);
    }

    /**
     * 身上是否有**可当弩弹药**的烟花——由配置 {@code combat.crossbowPlainFirework} 决定
     * 是否把"普通烟花"（无爆炸组件）也算进来。
     *
     * v1.2.0 实测五百三十五。v1.2.0 实测五百三十七起默认 **false** = 只认**攻击性烟花**
     * （带烟火之星的那种）——普通烟花留着当飞行燃料，绝不被弩烧掉（它打出去本来也是
     * 0 伤害，见 {@link #isExplosiveFirework}）。打开才退回"任意烟花都能当弹药"
     * （原版 `CrossbowItem` 的弹药谓词就不看爆炸组件，玩家拿普通烟花照样能射）。
     *
     * 【与取用口径必须一致】本判据与 {@link #takeBestCrossbowFirework} 读同一个开关，
     * 否则会出现"判定有弹药、起飞后一发打不出来"的死角（本文件反复强调的红线）。
     */
    public static boolean hasAmmoFirework(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (com.maidsmart.config.MaidSmartConfig.COMBAT_CROSSBOW_PLAIN_FIREWORK.get()) {
            return hasFirework(maid);
        }
        return hasInBackpack(maid, MaidFlightKit::isExplosiveFirework)
                || hasInHandsOrBorrowed(maid, MaidFlightKit::isExplosiveFirework);
    }

    /**
     * 是否**攻击性**烟花火箭——即带爆炸组件（`Fireworks.Explosions` 非空）的那种。
     * 等价于 {@link #fireworkPower} > 0。
     *
     * v1.2.0 实测四百九十九：为什么必须把"攻击性"和"飞行燃料"分开。
     *
     * 烟花面板里 `Explosions` 是**伤害的唯一来源**：`FireworkRocketEntity.dealExplosionDamage`
     * 开头就是 `if (!this.hasExplosion()) return;`（= `!getExplosions().isEmpty()`），
     * 也就是说**不带爆炸的烟花打出去是 0 伤害**（只有一条直线飞行轨迹）。
     *
     * v1.2.0 实测五百三十三：一度改成"任意烟花都算"；实测五百三十七起弩默认又回到本判据
     * ——`combat.crossbowPlainFirework` 默认关闭，弩只认带爆炸的（开关打开才退回"任意烟花
     * 都算"，原版 `CrossbowItem` 的弹药谓词就不看爆炸组件），见
     * {@link #takeBestCrossbowFirework}。
     * 保留它是因为"这枚打出去有没有伤害"仍是个有意义的判据。
     */
    public static boolean isExplosiveFirework(ItemStack stack) {
        return fireworkPower(stack) > 0;
    }

    /**
     * 烟花火箭的**威力** = 爆炸条目数 = 合成时用掉的**烟火之星个数**（普通烟花 = 0）。
     *
     * v1.2.0 实测五百三十三：弩挑弹药就按这个值降序——需求原文"优先使用威力大
     * （合成用烟火之星多）的烟花"。
     *
     * 【这不是拍脑袋的排序依据】原版伤害就是这一个数在决定：
     * `FireworkRocketEntity.m_37087_` 字节码 = `5.0f + 2 * Explosions.size()`
     * （每多一枚烟火之星 +2 伤害），不带爆炸则整段早退 = 0 伤害。
     * 所以"烟火之星多"与"打出去更疼"是同一件事。
     */
    public static int fireworkPower(ItemStack stack) {
        if (!isFirework(stack)) {
            return 0;
        }
        try {
            net.minecraft.nbt.CompoundTag tag = stack.m_41783_();
            if (tag == null) {
                return 0;
            }
            // 与 `FireworkRocketEntity` 自己的读法一致：Fireworks.Explosions
            net.minecraft.nbt.ListTag explosions =
                    tag.m_128469_("Fireworks").m_128437_("Explosions", 10);
            return explosions.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * v1.2.2 实测六百〇八【飞行跟随】：从背包/手上取一件可用鞘翅（**只取，不穿**）。
     *
     * 取用优先级与 {@link #equip} 完全一致（带护甲的"鞘翅胸甲"优先，其次普通鞘翅，最后手上），
     * 只是把"穿上"那一步留给调用方——飞行跟随要记下**换下来的原胸甲**并在收手时还回去，
     * 那件事只有调用方知道。
     *
     * @return 取到的鞘翅（空 = 没有）
     */
    public static ItemStack takeElytra(EntityMaid maid) {
        if (maid == null) {
            return ItemStack.f_41583_;
        }
        try {
            ItemStack ely = takeFromBackpack(maid, s -> isElytraLike(s, maid) && isArmorElytra(s));
            if (ely.m_41619_()) {
                ely = takeFromBackpack(maid, s -> isElytraLike(s, maid));
            }
            if (!ely.m_41619_()) {
                return ely;
            }
            IItemHandlerModifiable h = (IItemHandlerModifiable) maid.getHandsInvWrapper();
            for (int slot = 0; slot <= 1; slot++) {
                if (isElytraLike(h.getStackInSlot(slot), maid) && isArmorElytra(h.getStackInSlot(slot))) {
                    return h.extractItem(slot, 1, false);
                }
            }
            for (int slot = 0; slot <= 1; slot++) {
                if (isElytraLike(h.getStackInSlot(slot), maid)) {
                    return h.extractItem(slot, 1, false);
                }
            }
            // v1.3.0(beta) 实测六百八十：最后再问一次她的饰品栏（Curios）——与 equip 同一取舍次序
            //（先带护甲的"鞘翅胸甲"，再普通鞘翅）。飞行跟随会记下换下来的原胸甲并在收手时还回去。
            ItemStack fromCurios = CuriosElytraCompat.takeElytra(maid,
                    s -> isElytraLike(s, maid) && isArmorElytra(s), ItemStack.f_41583_);
            if (fromCurios == null) {
                fromCurios = CuriosElytraCompat.takeElytra(maid,
                        s -> isElytraLike(s, maid), ItemStack.f_41583_);
            }
            if (fromCurios != null) {
                return fromCurios;
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /**
     * v1.2.2 实测六百〇八【飞行跟随】：放一枚"只有推力、没有爆炸"的挂载型烟花。
     *
     * 与 {@link MaidFlightCombatBehavior} 里那一枚同一口径：**只写飞行时长、不写爆炸组件**，
     * 所以到期 `explode()` 时伤害为 0，不会按 5+2n 炸到骑乘者自己（女仆只有 20 血）。
     * 单独提出来是为了让"飞行跟随"不必复用空袭行为里的私有发射方法。
     *
     * 【实测六百一十二：返回值从 boolean 改成那枚火箭本身】调用方需要它来**解除推进矢量**
     * （见 {@code MaidFlightFollowBehavior.releaseThrust}）。烟花对骑手的推力**不是一次性的**，
     * 而是它活着的那十几 tick 里**每 tick 都把骑手速度往"1.7×视线"上拉**——1.20.1
     * `FireworkRocketEntity.tick`（SRG `m_8119_`）字节码实证：
     * <pre>
     *   if (this.isAttachedToEntity()) {                       // m_37088_
     *       if (rider.isFallFlying()) {                        // m_21255_（正是我们置的滑翔位）
     *           Vec3 look = rider.getLookAngle();              // m_20154_
     *           rider.setDeltaMovement(vel.add(                // m_20256_
     *               look.x * 0.1 + (look.x * 1.5 - vel.x) * 0.5, …y…, …z…));
     * </pre>
     * 即 `v = v * 0.5 + look * 0.85`（不动点 = 1.7 倍视线方向）。所以"进到主人身边就解除"
     * 必须把**还挂在背上的那枚**收掉（`Entity.remove(RemovalReason.DISCARDED)`；**别写
     * `m_6075_`**——javap 实证那是 `baseTick()`，调了等于没收），否则下一 tick 它又把速度
     * 补回来，清零等于白清。
     *
     * @return 放出去的那枚火箭（要给调用方 discard 用）；null = 没放成
     */
    public static net.minecraft.world.entity.projectile.FireworkRocketEntity launchBoostRocket(
            net.minecraft.server.level.ServerLevel level, EntityMaid maid) {
        if (level == null || maid == null) {
            return null;
        }
        try {
            ItemStack rocketStack = new ItemStack(Items.f_42688_);
            CompoundTag fireworks = new CompoundTag();
            fireworks.m_128405_("Flight", 1);
            rocketStack.m_41784_().m_128365_("Fireworks", fireworks);
            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                    new net.minecraft.world.entity.projectile.FireworkRocketEntity(level, rocketStack, maid);
            level.m_7967_(rocket);
            net.minecraft.sounds.SoundEvent launch = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                    .getValue(new ResourceLocation("minecraft", "entity.firework_rocket.launch"));
            if (launch != null) {
                level.m_5594_(null, maid.m_20183_(), launch, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
            return rocket;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface StackFilter {
        boolean test(ItemStack stack);
    }

    private static boolean hasInBackpack(EntityMaid maid, StackFilter filter) {
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (filter.test(inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // v1.2.4 实测六百三十九：她自己的背包没有 → 再看精妙背包/旅行者背包（只读探针）
        return com.maidsmart.tool.MaidExtraContainer.contains(maid, filter::test);
    }

    /** 从背包整叠取出（换装语义） */
    private static ItemStack takeFromBackpack(EntityMaid maid, StackFilter filter) {
        try {
            // v1.2.4 实测六百三十九：先判背包，再判精妙背包——自己背包里没有就先让 TLM
            // 的额外容器系统把匹配物搬进来（整组），下面照原样扫一遍就能拿到
            com.maidsmart.tool.MaidExtraContainer.pull(maid, filter::test, -1);
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (filter.test(s)) {
                    return inv.extractItem(i, s.m_41613_(), false);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /** 从背包只抽走 1 个（烟花消耗专用） */
    private static ItemStack takeOneFromBackpack(EntityMaid maid, StackFilter filter) {
        try {
            // v1.2.4 实测六百三十九：同上（烟花火箭——她背包里没有就翻精妙背包要一个）
            com.maidsmart.tool.MaidExtraContainer.pull(maid, filter::test, 1);
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (filter.test(s)) {
                    return inv.extractItem(i, 1, false);
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.f_41583_;
    }

    /**
     * 把换下来的物品塞回背包；塞不下的部分丢在脚下。
     *
     * v1.2.2 实测五百九十五【不再吞装备】：旧版与 MaidBombing 那处同一个形状
     * （"第一个空格 insertItem 后不看返回值"）——空袭换鞘翅 / 换武器时换下来的
     * 胸甲、武器就都是从这条路径"消失"的：`insertItem` 把整栈退回来、返回值被丢掉。
     * 现在统一走 {@link com.maidsmart.tool.MaidGiveBack}。
     */
    private static void giveBack(EntityMaid maid, ItemStack stack) {
        com.maidsmart.tool.MaidGiveBack.give(maid, stack, "空袭换装换下来的装备");
    }
}
