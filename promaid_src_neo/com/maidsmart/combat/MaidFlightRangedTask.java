package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.2.0（1.21.1）：飞行**远战**任务——图标 = 弓，**不响应自主切换**。
 *
 * 【激活口径】鞘翅 + **任意远程武器（ProjectileWeaponItem：弓/弩/御币/模组弹射武器；
 * 以及枪械：TACZ / 卓越前线）** + 烟花火箭，三件齐备才激活；缺任意一件 → 行为与普通攻击模式一致。
 *
 * 【与飞行近战的区别（用户指定）】
 * - 起飞方式完全相同（起跳滑翔 + 放烟花给"背离敌人 + 向上"的初速）；
 * - 起飞之后**不再扑击**，而是持续在天上盘旋（类似幻翼），按需补烟花维持高度；
 * - 攻击改为**用手持远程武器在锁敌范围内开火**——弓弩走 TLM 自己的通道：
 *   `EntityMaid.performRangedAttack` → 因为本任务实现了 {@link IRangedAttackTask}，
 *   所以会回调到下面的 {@link #performRangedAttack}。箭矢/弹药消耗都在这里处理。
 *   **枪械不在本方法里开火**：枪械走 TLM 的 `GunCommonUtil` 通道，由
 *   `MaidFlightCombatBehavior#tickGunFire` 直接调用（含换弹与瞄准），本方法对枪械直接返回。
 *   （不注册 TLM 的 MaidShootTargetTask：它每 tick 会把视线拽向目标，和盘旋转向打架。）
 * - 击败敌人后行为自然结束 → 清滑翔落地（与近战一致）。
 */
public class MaidFlightRangedTask implements IRangedAttackTask {

    public static final ResourceLocation UID = MaidFlightKit.UID_RANGED;

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 图标 = 鞘翅（v1.2.0 实测四百六十九：远战也从弓改成鞘翅，两种飞行作战统一） */
    @Override
    public ItemStack getIcon() {
        return new ItemStack(Items.ELYTRA);
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return buildBrain();
    }

    private static List<Pair<Integer, BehaviorControl<? super EntityMaid>>> buildBrain() {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StartAttacking.create(
                IRangedAttackTask::findFirstValidAttackTarget)));
        list.add(Pair.of(5, net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid.create()));
        // 飞行远战：飞行/盘旋由本行为独占（开火由 performRangedAttack 通道负责）
        list.add(Pair.of(4, new MaidFlightCombatBehavior(true)));
        return list;
    }

    /** 武器位 = 任意远程武器（与 MaidFlightKit 同一判据） */
    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return MaidFlightKit.isRangedWeapon(stack);
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return false;
    }

    /**
     * v1.2.0 实测五百一十三【空袭索敌改为"以自身为圆心、半径 50 的立方盒"】——与
     * {@link MaidFlightCombatTask#searchDimension} 完全同款（远程空袭同属空中作战模式，
     * 索敌口径必须一致）。
     *
     * 【演进】五百零八先把**垂直**半径从 TLM 默认的 4 格提到 50（水平仍 `searchRadius`，
     * 默认 16），修掉"凋灵飞高就锁不上"；但实测水平 16 格仍太小（"索敌范围还是太小"）——
     * 空袭是立体作战，敌人斜上方 20~40 格时水平距离早就出界。
     *
     * 【现在】三轴统一 50 格（`inflate(50,50,50)` = 以自身为半径 50 的立方盒），水平/垂直
     * 一视同仁。
     *
     * 【隔墙不出手由 TLM 原版保证】本方法只管"扫多大范围"；"能不能打"走 TLM 的
     * `IAttackTask.findFirstValidAttackTarget` → `NearestVisibleLivingEntities.findClosest`，
     * **该类自带视线过滤**（可见性谓词 = `Sensor.isEntityAttackable` → `TargetingConditions.test`，
     * `ignoreLineOfSight` 默认 false → `Sensing.hasLineOfSight`，字节码实证）。
     * 所以放大范围不会导致隔墙出手——候选进得来、可见性那关会滤掉。我们没动这条链路。
     */
    private static final double FLIGHT_SEARCH_RADIUS = 50.0;

    @Override
    public net.minecraft.world.phys.AABB searchDimension(EntityMaid maid) {
        if (maid.hasRestriction()) {
            return new net.minecraft.world.phys.AABB(maid.getRestrictCenter())
                    .inflate(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
        }
        return maid.getBoundingBox().inflate(FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS, FLIGHT_SEARCH_RADIUS);
    }

    @Override
    public String getMaidActionSummary() {
        return "远程空袭";
    }

    /**
     * 开火（由 `EntityMaid.performRangedAttack` 回调）。
     *
     * v1.2.0 实测四百七十六【手持武器没参与开火】——旧版这里**无条件造箭**：
     * 拿御币/三叉戟/模组弹射武器也照样射出一支箭（反馈原话：「换了武器（如御币）
     * 也没用，依旧发射弓箭」）。根因是本方法只判了"是不是 ProjectileWeaponItem"，
     * 之后就自己 `createArrow`，武器本身的发射机制从未被调用。
     *
     * 现在按武器分流（与玩家手持该武器时的表现对齐）：
     * - **枪械**（TACZ / 卓越前线）：直接返回，由 `MaidFlightCombatBehavior#tickGunFire`
     *   走 TLM 枪械通道（换弹 + 瞄准 + 开火）；
     * - **三叉戟**：走 {@link #throwTrident}——**它不是 `ProjectileWeaponItem`**
     *   （1.21.1 是 `TridentItem extends Item implements ProjectileItem`，与
     *   `ProjectileWeaponItem` 是两个不同接口），旧版会在这里被判掉、一枪都放不出来；
     * - **御币**（TLM 的 `ItemHakureiGohei`）：走 TLM 的弹幕 API `DanmakuShoot`——
     *   这是 TLM 自己的 `TaskDanmakuAttack` 用的同一套（反编译实证：御币的
     *   `shootProjectile` 是**空实现**、`getAllSupportedProjectiles` 返回 alwaysTrue，
     *   说明它压根不走原版箭矢通道，硬造箭必然变成"射箭"）；
     * - **其它弹射武器**（弓/弩/模组弹射物）：造箭。优先用武器自己的
     *   `getAllSupportedProjectiles` 判据找弹药（弓弩=箭、模组武器=自己的弹药类型），
     *   拿不到才退回普通箭。
     */
    @Override
    public void performRangedAttack(EntityMaid shooter, LivingEntity target, float power) {
        try {
            ItemStack weapon = shooter.getMainHandItem();
            // 枪械不走箭矢通道（由 MaidFlightCombatBehavior#tickGunFire 调 TLM 枪械接口开火）
            if (GunCompat.isGun(weapon)) {
                return;
            }
            // 三叉戟：不是 ProjectileWeaponItem，必须单独一路走 ThrownTrident（见 throwTrident）
            if (isTrident(weapon)) {
                throwTrident(shooter, target);
                return;
            }
            if (!(weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem pwi)) {
                return;
            }
            // 御币 → TLM 弹幕通道（与 TLM 自己的弹幕任务同款）
            if (isGohei(weapon)) {
                shootDanmaku(shooter, target, power);
                return;
            }
            // 弩 + 攻击性烟花火箭：走"弩射烟花"通道（见 shootCrossbowFirework）。
            // 必须排在 shootArrow 之前——烟花不是 ArrowItem，落到下面那一路必然被糟蹋掉。
            if (weapon.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
                if (shootCrossbowFirework(shooter, target, weapon)) {
                    return;
                }
                // v1.2.0 实测五百三十一【弩的附魔不生效】：弩必须走"弩的装配口径"，
                // 不能当弓处理——见 shootCrossbowArrows 的注释
                shootCrossbowArrows(shooter, target, weapon, pwi);
                return;
            }
            shootArrow(shooter, target, weapon, pwi);
        } catch (Throwable ignored) {
        }
    }

    /** 是否 TLM 御币（两种御币：博丽/早苗——都实现 ProjectileWeaponItem 且有 isGohei 判据） */
    private static boolean isGohei(ItemStack stack) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei.isGohei(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否三叉戟（判据与 TLM `TaskTridentAttack.isWeapon` 一致：`instanceof TridentItem`） */
    private static boolean isTrident(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.TridentItem;
    }

    /**
     * 三叉戟投掷——照搬 TLM `TaskTridentAttack.performRangedAttack`（字节码实证）。
     *
     * 为什么不能走原版那条路：`TridentItem.releaseUsing` 第一件事就是
     * `if (!(entityLiving instanceof Player)) return;`（1.20.1/1.21.1 同款，字节码 `instanceof Player; ifne`），
     * 女仆不是 Player → 调用它等于空转。所以必须自己建 `ThrownTrident`，这也正是
     * TLM 给女仆做三叉戟任务的做法。
     *
     * 与 TLM 逐点一致（不是自己发挥，避免出现"比玩家强/有刷取漏洞"的差异）：
     * ① 投的是**副本**——主手那把只掉 1 点耐久，不会被真的扔出去；
     * ② **剥掉忠诚附魔**——留着它，`ThrownTrident.isAcceptibleReturnOwner()` 对活着的
     *    女仆返回 true，这把副本会飞回来、还可能被玩家捡走，等于凭空多出一把三叉戟；
     * ③ `setNoGravity(true)` 直线飞行（配 TLM 的 `ThrownTridentMixin`：命中实体后才恢复重力）；
     * ④ `pickup = CREATIVE_ONLY`——副本不落地可捡，到期由 `tickDespawn` 自行回收；
     * ⑤ 速度/散布照 TLM：`clamp(距离/10, 1.6, 3.2)` 与 `1 - clamp(距离/100, 0, 0.9)`。
     */
    private static void throwTrident(EntityMaid shooter, LivingEntity target) {
        try {
            // v1.2.0 实测五百三十二【激流三叉戟投不出去】：原版 `TridentItem.releaseUsing` 里
            // "造 ThrownTrident"那一支**只在无激流时走**，激流 > 0 走的是
            // `startAutoSpinAttack` 旋转突进分支。TLM 的 TaskTridentAttack 不看激流（照投），
            // 本模组原本也照搬——这里补上原版闸门：带激流就不投。
            // 正常路径上她根本不会拿着激流三叉戟进远程空袭（MaidFlightKit.isRangedWeapon
            // 已把它排除、三件套不齐），这条是防任务切换竞态的兜底。
            if (MaidFlightKit.isRiptide(shooter.getMainHandItem())) {
                return;
            }
            ItemStack stack = shooter.getMainHandItem().copy();
            // 剥忠诚：1.21.1 的附魔是数据驱动的 Holder，只能经 ItemEnchantments 改。
            try {
                net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> loyalty =
                        shooter.level().registryAccess()
                                .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY);
                if (net.minecraft.world.item.enchantment.EnchantmentHelper
                        .getItemEnchantmentLevel(loyalty, stack) > 0) {
                    net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(
                            stack, m -> m.set(loyalty, 0));
                }
            } catch (Throwable ignored) {
            }
            net.minecraft.world.entity.projectile.ThrownTrident trident =
                    new net.minecraft.world.entity.projectile.ThrownTrident(shooter.level(), shooter, stack);
            double dx = target.getX() - shooter.getX();
            double dy = target.getEyeY() - shooter.getEyeY();
            double dz = target.getZ() - shooter.getZ();
            float dist = shooter.distanceTo(target);
            float velocity = net.minecraft.util.Mth.clamp(dist / 10.0f, 1.6f, 3.2f);
            float inaccuracy = 1.0f - net.minecraft.util.Mth.clamp(dist / 100.0f, 0.0f, 0.9f);
            trident.setNoGravity(true);
            trident.shoot(dx, dy, dz, velocity, inaccuracy);
            trident.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.CREATIVE_ONLY;
            shooter.getMainHandItem().hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
            shooter.level().addFreshEntity(trident);
            shooter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            shooter.playSound(SoundEvents.TRIDENT_THROW.value(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * TLM 弹幕开火——**照搬 TLM `TaskDanmakuAttack.performRangedAttack` 的完整规则**。
     *
     * v1.2.0 实测四百九十【御币扇形丢失修复】。反馈："御币进行的攻击是扇形范围攻击，
     * 但目前在飞行远战期间御币失去了这个功能。"
     *
     * 【根因】旧版这里是无条件单发：
     * ```
     * DanmakuShoot.create()....setVelocity(1.6f).setInaccuracy(1.0f).aimedShot();
     * ```
     * 而 TLM 的御币扇形来自 `fanShapedShot()`——`DanmakuShoot` 里 `aimedShot` 只造
     * **1** 发、`fanShapedShot` 才按 `setFanNum(n)` + `setYawTotal(弧度)` 围绕目标方向
     * 张开一个扇形（CFR 反编译实证：以目标方向为轴，`yaw` 从 `-yawTotal/2` 起按
     * `yawTotal/(fanNum-1)` 递进，逐发 `Vec3.yRot(yaw)`）。
     *
     * 【TLM 的原始规则——不是"永远扇形"】扇叶数按【周围同种敌人数量】分档：
     * ```
     *   同种敌人 ≤1 且 无多重射击 → aimedShot()      单发瞄准
     *   同种敌人 ≤1 且 有多重射击 → fanShapedShot()  3 发 / 0.2618 rad(15°)
     *   同种敌人 ≤5              → fanShapedShot()  8 发 / 1.0472 rad(60°)
     *   同种敌人 >5              → fanShapedShot() 32 发 / 2.0944 rad(120°)
     * ```
     * 所以「打了 3 只以上才明显扇形」是有意设计（单挑不该撒一片）。旧版一律单发，
     * 等于把这套规则整段丢了。
     *
     * 【为什么照抄而不是自己发挥】伤害/速度/散布都与 TLM 原式对齐（`attackValue`
     * 取攻击力属性、`speed = 0.3*(df+1)*(急速+1) + clamp(dist/40-0.4, 0, 2.4)`、
     * `inaccuracy` 单发时 `1-clamp(dist/100,0,0.8)`、扇形时再 /5），避免出现
     * "比 TLM 原版强/弱"的差异。
     *
     * 【本模组特有的两处调整（仅这两处）】
     * ① 速度基线：TLM 用 `0.3f`，飞行远战是空中交战、目标常更远，沿用会导致弹幕
     *    追不上（这也是旧版把 setVelocity 提到 1.6 的原因）——这里保留本模组原有的
     *    "更快"取向，即把 TLM 的 `0.3f` 基线整体抬到 `1.6f`；
     * ② 附魔：TLM 独有的「阻碍/神速/末影杀手」三个自定义附魔在飞行远战里不做特殊
     *    处理（保持与旧版一致：不吃这三个附魔），只保留**多重射击**这一条影响形态的。
     *
     * 数敌人用的记忆：TLM 在 1.21.1 读的是 `NEAREST_LIVING_ENTITIES`（列表，非
     * VISIBLE 版）、视线判据用 `hasLineOfSight`——都照 TLM 原文，保证口径一致。
     *
     * @param distanceFactor 由 TLM 的开火通道传入（蓄力系数），与 TLM 同款用于伤害/速度缩放
     */
    private static void shootDanmaku(EntityMaid shooter, LivingEntity target, float distanceFactor) {
        try {
            float dmg = 4.0f;
            try {
                dmg = (float) shooter.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            } catch (Throwable ignored) {
            }
            dmg = Math.max(1.0f, dmg);

            // 周围同种敌人数量（TLM 同款判据：可攻击 + 同 EntityType + 有视线）
            long sameKind = 0L;
            try {
                var mem = shooter.getBrain().getMemory(
                        net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_LIVING_ENTITIES);
                if (mem.isPresent()) {
                    sameKind = mem.get().stream()
                            .filter(e -> enemyEntityTest(shooter, target, e))
                            .count();
                }
            } catch (Throwable ignored) {
            }

            // 多重射击附魔（TLM 口径：EnchantmentKeys.getEnchantmentLevel(registryAccess, MULTISHOT, stack)）
            int multiShot = 0;
            try {
                multiShot = com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                        .getEnchantmentLevel(shooter.level().registryAccess(),
                                net.minecraft.world.item.enchantment.Enchantments.MULTISHOT,
                                shooter.getMainHandItem());
            } catch (Throwable ignored) {
            }
            // v1.2.0 实测四百九十二【御币三个专属附魔补齐】——TLM 的阻碍/神速/末影杀手注册在
            // GOHEI 类别上，自身没有行为，作用全靠 TaskDanmakuAttack 读等级后传给弹幕 API。
            // TLM 原文三处：speed 再乘 (speedyLevel + 1)；setImpedingLevel(impedingLevel)；
            // setHurtEnderman(endersEnderLevel > 0)。旧版整段跳过，本次按用户"攻击能不能
            // 触发附魔效果"的要求补齐，算式与 TLM 逐字一致。
            int impedingLevel = 0;
            int speedyLevel = 0;
            boolean endersEnder = false;
            try {
                impedingLevel = com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                        .getEnchantmentLevel(shooter.level().registryAccess(),
                                com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys.IMPEDING,
                                shooter.getMainHandItem());
                speedyLevel = com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                        .getEnchantmentLevel(shooter.level().registryAccess(),
                                com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys.SPEEDY,
                                shooter.getMainHandItem());
                endersEnder = com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                        .getEnchantmentLevel(shooter.level().registryAccess(),
                                com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys.ENDERS_ENDER,
                                shooter.getMainHandItem()) > 0;
            } catch (Throwable ignored) {
            }

            float distance = shooter.distanceTo(target);
            // TLM 原式：speed = 0.3*(df+1)*(急速+1) + clamp(dist/40 - 0.4, 0, 2.4)
            // 本模组把 0.3 基线抬到 1.6（空中交战距离更远，见上文【调整①】）；
            // (speedyLevel + 1) 与 TLM 同款，是"神速"附魔的实际作用点。
            float speed = 1.6f * (distanceFactor + 1.0f) * (float) (speedyLevel + 1)
                    + net.minecraft.util.Mth.clamp(distance / 40.0f - 0.4f, 0.0f, 2.4f);
            // TLM 原式：inaccuracy = 1 - clamp(dist/100, 0, 0.8)
            float inaccuracy = 1.0f - net.minecraft.util.Mth.clamp(distance / 100.0f, 0.0f, 0.8f);

            com.github.tartaricacid.touhoulittlemaid.entity.projectile.DanmakuShoot shot =
                    com.github.tartaricacid.touhoulittlemaid.entity.projectile.DanmakuShoot.create()
                            .setWorld(shooter.level())
                            .setThrower(shooter)
                            .setTarget(target)
                            .setRandomColor()
                            .setRandomType()
                            .setGravity(0.0f)
                            .setImpedingLevel(impedingLevel)
                            .setVelocity(speed);

            if (sameKind <= 1L) {
                if (multiShot > 0) {
                    // 单目标 + 多重射击：3 发小扇形（TLM 同款角度）
                    shot.setDamage(dmg * (distanceFactor + 1.2f))
                            .setHurtEnderman(endersEnder)
                            .setInaccuracy(inaccuracy)
                            .setFanNum(3)
                            .setYawTotal(0.2617993877991494)
                            .fanShapedShot();
                } else {
                    // 单目标无多重射击：单发瞄准（TLM 原语义——单挑不撒扇形）
                    shot.setDamage(dmg * (distanceFactor + 1.0f))
                            .setHurtEnderman(endersEnder)
                            .setInaccuracy(inaccuracy / 5.0f)
                            .aimedShot();
                }
            } else if (sameKind <= 5L) {
                shot.setDamage(dmg * (distanceFactor + 1.2f))
                        .setHurtEnderman(endersEnder)
                        .setInaccuracy(inaccuracy / 5.0f)
                        .setFanNum(8)
                        .setYawTotal(1.0471975511965976)
                        .fanShapedShot();
            } else {
                shot.setDamage(dmg * (distanceFactor + 1.5f))
                        .setHurtEnderman(endersEnder)
                        .setInaccuracy(inaccuracy / 5.0f)
                        .setFanNum(32)
                        .setYawTotal(2.0943951023931953)
                        .fanShapedShot();
            }

            shooter.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            shooter.getMainHandItem().hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
        } catch (Throwable ignored) {
        }
    }

    /** 同种敌人判据（照 TLM `TaskDanmakuAttack.enemyEntityTest`：可攻击 + 同类型 + 有视线） */
    private static boolean enemyEntityTest(EntityMaid shooter, LivingEntity target, LivingEntity test) {
        try {
            return shooter.canAttack(test)
                    && target.getType().equals(test.getType())
                    && shooter.hasLineOfSight(test);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 弩：照原版 `CrossbowItem.createProjectile` + `performShooting` 的装配与发射。
     *
     * v1.2.0 实测五百三十一。反馈："飞行没有办法触发手持武器的附魔。比如弓和弩，还有三叉戟。"
     *
     * 【根因·反编译实证（1.21.1 客户端 jar）】原版弩的附魔**不在弓那条路里**：
     * <ul>
     *   <li>`CrossbowItem.createProjectile` 只额外做一件事：把命中音改成
     *       `SoundEvents.CROSSBOW_HIT`（其余交给 `ProjectileWeaponItem.createProjectile`）；</li>
     *   <li>**穿透与弩标记不用手动设**：`AbstractArrow` 带武器的构造器里已经
     *       `EnchantmentHelper.getPiercingCount(level, firedFromWeapon, pickup)` → `setPierceLevel`，
     *       而 `setPierceLevel`/`setFlag` 在 1.21.1 都是 **private**——也就是说"给箭矢传武器"
     *       这一件事本身就够了（本模组一直在传，所以这边的穿透/附魔本来就是通的）；</li>
     *   <li>`CrossbowItem.shoot` 按 `Enchantments.MULTISHOT` 决定发数：**1 发 / 3 发**，
     *       三发的偏角是 **0° / -10° / +10°**（`getProjectileShotVector` = 视线绕上轴旋转该角度）
     *       ——**这一块旧版的模组完全没有**，因为旧版把弩当弓、自己造一支箭就完事；</li>
     *   <li>`CrossbowItem.getChargeDuration` = `EnchantmentHelper.modifyCrossbowChargingTime(...)`
     *       × 20（基础 1.25s = 25 tick）——快速装填作用在"蓄力时长"上；飞行时没有蓄力动作，
     *       所以由 `MaidFlightCombatBehavior#rangedShotCooldown` 按同一相对幅度缩短开火间隔。</li>
     * </ul>
     *
     * 旧版把弩当弓处理（一律走 {@link #shootArrow}），于是**多重射击一次都没有**；
     * 而弩根本附不上 Power/Punch/Flame，"弩的附魔一个都不触发"就是这么来的。
     *
     * 【弹药只吃 1 份】原版多重射击的第 2、3 发用的是副本，只有第 1 发真的从背包扣——
     * "1 支箭变 3 支"正是这个附魔的价值，这里照抄。
     * 【耐久每次射击扣 1】原版 `onCrossbowShot` 是每次射击扣 1 点，不是每支箭扣 1 点。
     */
    private static void shootCrossbowArrows(EntityMaid shooter, LivingEntity target, ItemStack crossbow,
                                            net.minecraft.world.item.ProjectileWeaponItem pwi) {
        ItemStack ammo = findAmmo(shooter, pwi);
        if (ammo.isEmpty()) {
            return;
        }
        ArrowItem arrowItem = ammo.getItem() instanceof ArrowItem a ? a : (ArrowItem) Items.ARROW;
        // 穿透不在这里读：见上方注释——1.21.1 由 `AbstractArrow` 构造期按武器自动设好
        int multishot = enchLevel(shooter, net.minecraft.world.item.enchantment.Enchantments.MULTISHOT, crossbow);
        int shots = multishot > 0 ? 3 : 1;
        float[] angles = {0.0f, -10.0f, 10.0f};
        boolean infinity = enchLevel(shooter, net.minecraft.world.item.enchantment.Enchantments.INFINITY, crossbow) > 0;

        double dx = target.getX() - shooter.getX();
        double dy = target.getEyeY() - shooter.getEyeY();
        double dz = target.getZ() - shooter.getZ();

        for (int i = 0; i < shots; i++) {
            // 【穿透不用自己设】1.21.1 的箭矢在**构造期**就按武器读好了：
            // `AbstractArrow` 带武器的那个构造器里有
            // `EnchantmentHelper.getPiercingCount(level, firedFromWeapon, pickup)` → `setPierceLevel`；
            // 而 `setPierceLevel` / `setFlag` 在 1.21.1 都是 **private**（弩标记同理由构造期处理），
            // 所以这边既不需要、也设不了。本树要补的只有原版 `createProjectile` 的另一半：命中音。
            AbstractArrow arrow = arrowItem.createArrow(shooter.level(), ammo, shooter, crossbow);
            arrow.setSoundEvent(SoundEvents.CROSSBOW_HIT);
            // 偏角：把瞄向目标的水平分量绕 Y 轴转 angles[i] 度（与原版 getProjectileShotVector 同口径）
            double rad = Math.toRadians(angles[i]);
            double rx = dx * Math.cos(rad) + dz * Math.sin(rad);
            double rz = dz * Math.cos(rad) - dx * Math.sin(rad);
            arrow.setNoGravity(true);
            arrow.shoot(rx, dy, rz, 2.4f, 1.0f);
            shooter.level().addFreshEntity(arrow);
            // 原版 shootProjectile 是**每发**都放一次弩声（音高带 ±0.4 随机）
            shooter.level().playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                    SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS,
                    1.0f, 1.0f / (shooter.getRandom().nextFloat() * 0.4f + 0.8f));
        }
        crossbow.hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
        if (!infinity) {
            ammo.shrink(1);   // Multishot 只吃 1 份弹药
        }
    }

    /** 读手持武器上的附魔等级（1.21.1：TLM 的 EnchantmentKeys，与 shootArrow 里的无限判据同款） */
    private static int enchLevel(EntityMaid maid,
                                 net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key,
                                 ItemStack stack) {
        try {
            return com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                    .getEnchantmentLevel(maid.level().registryAccess(), key, stack);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 弓/弩/模组弹射物：造箭并射出（弹药优先按武器自己的判据找）。
     *
     * v1.2.0 实测四百九十二【附魔核查结论：1.21.1 本就生效，无需补】。反馈："看一下飞行时
     * 攻击能不能触发附魔效果。"
     *
     * 【为什么这棵树不用补】1.21.1 的 `ArrowItem.createArrow(level, stack, shooter, weapon)`
     * **多一个武器参数**（反编译：`new Arrow(level, shooter, stack.copyWithCount(1), weapon)`），
     * 武器被存进箭矢的 `firedFromWeapon`，命中时由 `EnchantmentHelper.modifyDamage` /
     * `modifyKnockback` 读它结算附魔（`onHitEntity` 第 374 行、`doKnockback` 第 457 行）——
     * 且这两个方法是**数据驱动、无 `instanceof Player` 门控**（实测确认，与 1.20.1 的
     * `m_44823_` 不同）。我们这里已经把 `weapon` 传进了 createArrow，所以 Power/Punch/Flame
     * 在 1.21.1 上一直是生效的。
     *
     * 【唯一要自己补的是无限附魔】因为我们绕过原版弹射通道、自己扣箭，所以"无限"得自己判
     * （原版是 `processAmmoUse` 里处理的）；判据与原版同款。
     */
    private static void shootArrow(EntityMaid shooter, LivingEntity target, ItemStack weapon,
                                   net.minecraft.world.item.ProjectileWeaponItem pwi) {
        ItemStack arrowStack = findAmmo(shooter, pwi);
        if (arrowStack.isEmpty()) {
            return;
        }
        ArrowItem arrowItem = arrowStack.getItem() instanceof ArrowItem a ? a : (ArrowItem) Items.ARROW;
        AbstractArrow arrow = arrowItem.createArrow(shooter.level(), arrowStack, shooter, weapon);

        double dx = target.getX() - shooter.getX();
        double dy = target.getEyeY() - shooter.getEyeY();
        double dz = target.getZ() - shooter.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + dist * 0.05, dz, 2.4f, 1.0f);
        arrow.setCritArrow(true);

        weapon.hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
        // 无限附魔：有就不消耗箭（原版 processAmmoUse 同款语义，我们自己扣箭故自行判定）
        boolean infinity = false;
        try {
            infinity = com.github.tartaricacid.touhoulittlemaid.datagen.EnchantmentKeys
                    .getEnchantmentLevel(shooter.level().registryAccess(),
                            net.minecraft.world.item.enchantment.Enchantments.INFINITY,
                            weapon) > 0;
        } catch (Throwable ignored) {
        }
        if (!infinity) {
            arrowStack.shrink(1);
        }
        shooter.level().playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f);
        shooter.level().addFreshEntity(arrow);
    }

    /**
     * 找弹药：先走原版手部/副手，再按【武器自己的 supportedProjectiles 判据】找
     * （弓弩认箭、模组弹射武器认自己的弹药），最后退回女仆背包里的箭。
     */
    private static ItemStack findAmmo(EntityMaid maid, net.minecraft.world.item.ProjectileWeaponItem pwi) {
        try {
            ItemStack held = maid.getProjectile(maid.getMainHandItem());
            if (!held.isEmpty()) {
                return held;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.function.Predicate<ItemStack> supported = pwi.getAllSupportedProjectiles();
            net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && supported.test(s) && s.getItem() instanceof ArrowItem) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() instanceof ArrowItem) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    /**
     * 弩 + 烟花火箭：照原版 `CrossbowItem.createProjectile` 的烟花分支打出一枚真烟花。
     *
     * v1.2.0 实测四百九十九。反馈原文：「在使用弩的时候没有办法发射攻击性烟花火箭。」
     *
     * v1.2.0 实测五百三十三【任意烟花都算弹药 + 优先威力大的】：旧版只认带 `Explosions`
     * 的烟花，玩家拿一叠普通烟花配弩会被判成不认（反馈："女仆不认烟花火箭是弩的弹药"）。
     * 现在口径改为**任意烟花火箭**（与原版 `CrossbowItem` 的弹药谓词一致），
     * 且取用时由 `MaidFlightKit#takeBestCrossbowFirework` **按威力降序挑**
     * （威力 = 爆炸条目数 = 合成用的烟火之星个数，原版伤害 `5 + 2×条目数`）——
     * 好烟花先用，普通烟花只作兜底。
     *
     * 【根因】本方法上面的分流里，弩没有任何特殊待遇——弩是 `ProjectileWeaponItem`，
     * 于是直接落到 {@link #shootArrow}，而那里**只认 `ArrowItem`**：1.21.1 的
     * {@link #findAmmo} 第一站是 `maid.getProjectile(主手)`，TLM 的 `EntityMaid.getProjectile`
     * 在副手是烟花时**就把副手那叠烟花交出来**（TLM 给女仆做弩的官方口径，字节码实证），
     * 拿回来却被 `instanceof ArrowItem` 判掉 → 退化成一支普通箭，
     * 最后 `arrowStack.shrink(1)` 还把这枚烟花**消耗掉了**。
     * 表现就是"烟花莫名少了一枚、飞出去的却是一支箭"。
     *
     * 【烟花在本任务里的两种身份】烟花火箭在本任务里有两种身份：
     * ① **飞行燃料**——喂鞘翅推进用，发射路径自己造弹体
     *    （`MaidFlightCombatBehavior#launchFirework`），**不需要手持**；
     * ② **弩弹药**——原版口径（`EntityMaid.getProjectile` 副手是烟花就交出来当弹药）。
     * v1.2.0 实测五百一十：① 已不再占用副手（`MaidFlightKit.equip` 让出副手供盾牌/食物用），
     * 所以这两者不再抢同一个槽位。
     *
     * 【实测五百三十三：不再把"普通烟花"挡在门外】旧版这里只认带爆炸组件的烟花，
     * 理由是"不带爆炸打出去 0 伤害"。但那条理由**只说明它弱，不说明它不能用**——
     * 原版玩家拿弩装普通烟花照样能射（`CrossbowItem` 的弹药谓词就不看爆炸组件），
     * 而本模组却因此报"缺弹药"、一发不开。现在改为"任意烟花都能当弹药"，
     * 并用**威力优先**（{@link MaidFlightKit#takeBestCrossbowFirework}）把"弱"这件事
     * 变成排序而不是门禁：有带烟火之星的先打带烟火之星的，没有才用普通烟花兜底。
     * 弹药耗尽后仍会回落到射箭。
     *
     * 【逐点对齐原版 + TLM，不自己发挥】
     * - 弹体 = `new FireworkRocketEntity(level, 弹药, 女仆, x, 眼高 - 0.15, z, true)`，
     *   与原版 `CrossbowItem.createProjectile` 的烟花分支逐字一致；末位 `true` = `shotAtAngle`，
     *   即**命中即爆、不给射手推力**（不是给滑翔加速的那种挂载烟花）；
     * - 瞄准逐点照抄 TLM 自己的女仆弩口径 `MixinCrossbowItem#shootCrossbowProjectile`
     *   （1.21.1 官方实现，字节码实证）：方向取双方**眼高**之差，`setNoGravity(true)` 直线飞行，
     *   速度 = `clamp(距离/10, 1.6, 3.2)`、散布 = `1 - clamp(距离/100, 0, 0.9)`，
     *   音效 `CROSSBOW_SHOOT`（音高带 ±0.4 随机，与 TLM 同款）；
     * - 耐久按原版 `getDurabilityUse` 的烟花分支扣 **3**（射箭是 1）；
     * - **顺序**：弹药由 {@link MaidFlightKit#takeExplosiveFirework} 先取走（副手优先、
     *   其次主手、最后背包只抽 1 枚，与 {@link MaidFlightKit#takeFirework} 同一套换装语义），
     *   紧接着直接建弹体入世界——
     *   这两步之间只有算术、没有任何可能失败的分支（不查方块、不查目标、不做条件判断），
     *   所以不存在"烟花扣了却打不出去"的窗口；耐久在弹体入世界之后才扣。
     *
     * 【主人免伤】烟花是爆炸伤害，`DamageSources.fireworks(烟花, 女仆)` 的造成者就是女仆，
     * 会被 {@link FriendlyFireGuard}（`LivingIncomingDamageEvent`）拦下——
     * 女仆绝不会用烟花炸到主人。
     *
     * @return true = 确实打出了烟花（调用方不要再走射箭）
     */
    private static boolean shootCrossbowFirework(EntityMaid shooter, LivingEntity target, ItemStack weapon) {
        try {
            // 实测五百三十三：按威力挑（威力大的先用），且**任意烟花**都算弹药
            ItemStack ammo = MaidFlightKit.takeBestCrossbowFirework(shooter);
            if (ammo.isEmpty()) {
                return false;
            }
            double dx = target.getX() - shooter.getX();
            double dy = target.getEyeY() - shooter.getEyeY();
            double dz = target.getZ() - shooter.getZ();
            float dist = shooter.distanceTo(target);
            float velocity = net.minecraft.util.Mth.clamp(dist / 10.0f, 1.6f, 3.2f);
            float inaccuracy = 1.0f - net.minecraft.util.Mth.clamp(dist / 100.0f, 0.0f, 0.9f);

            net.minecraft.world.entity.projectile.FireworkRocketEntity rocket =
                    new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                            shooter.level(), ammo, shooter,
                            shooter.getX(), shooter.getEyeY() - 0.15, shooter.getZ(), true);
            rocket.setNoGravity(true);
            // 注意：这里传的是 dy（眼高差）——与 TLM 女仆弩的口径一致，不是原版弓的抛物线抬升
            rocket.shoot(dx, dy, dz, velocity, inaccuracy);

            shooter.level().addFreshEntity(rocket);
            weapon.hurtAndBreak(3, shooter, EquipmentSlot.MAINHAND);
            shooter.level().playSound(null, shooter.blockPosition(), SoundEvents.CROSSBOW_SHOOT,
                    SoundSource.PLAYERS, 1.0f,
                    1.0f / (shooter.getRandom().nextFloat() * 0.4f + 0.8f));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
