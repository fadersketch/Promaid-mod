package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.mixin.LivingEntitySpinAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * v1.2.0 实测五百三十四【激流三叉戟：把玩家的"旋转突进"套到女仆身上】。
 *
 * ── 需求原文 ──
 * "能不能想办法把玩家一的代码套到女仆身上呢？当处于攻击模式/近战空袭且手中的武器为
 * 激流三叉戟时调用。这样子就也可以用激流三叉戟了，就是动画这一块不知道该怎么做。"
 *
 * ── 为什么需要它（上一轮的遗留）──
 * 实测五百三十二 已经把激流三叉戟从"远程武器"里摘出来按近战归类（原版里它**投不出去**：
 * `TridentItem.releaseUsing` 造 `ThrownTrident` 那一支只在激流 = 0 时走）。但"按近战算"
 * 只解决了归类，她的近战是普通挥砍——激流附魔本身**没有任何作用**。本类把原版的
 * 旋转突进真正做出来。
 *
 * ── 原版玩家是怎么做的（1.20.1 `TridentItem.m_5551_` / 1.21.1 `releaseUsing` 字节码实证）──
 * <pre>
 *   ① 闸门：激流 > 0 且 !isInWaterOrRain → 直接 return（水里/雨里才允许）
 *   ② 方向 = 视线方向（yaw/pitch 换算）
 *   ③ 力度 = 3.0 × (1 + 激流等级) / 4        // I 级 1.5、III 级 3.0 格/tick
 *   ④ player.push(方向 × 力度)               // 加在速度上（不是直接赋值）
 *   ⑤ startAutoSpinAttack(20 tick[, 伤害, 武器]) → 置【标志位 4】+ 记 tick 数
 *   ⑥ 之后由 LivingEntity.tick 每 tick 递减，并 checkAutoSpinAttack：
 *      扫过本次位移的包围盒，碰到 LivingEntity 就 doAutoAttackOnTouch(伤害) 并立刻收招；
 *      撞到横向方块（horizontalCollision）也收招
 *   ⑦ 音效按等级：TRIDENT_RIPTIDE_1/2/3；耐久扣 1（与投掷共用那一条）
 * </pre>
 *
 * ── 女仆这边怎么落地的（关键：**标志位是通用的**）──
 * 反编译实证：`f_20938_`/`autoSpinAttackTicks` 与"标志位 4"都在 **LivingEntity** 上，
 * `LivingEntity.tick` 里就会递减 + 扫掠（`m_21071_` / `checkAutoSpinAttack`），
 * **不是 Player 专属**。所以女仆只要置位就一定会有原版的整套行为，我们额外做的只有两件：
 * <ul>
 *   <li>本类负责"触发"：置 tick 数 + 置标志位 + 给冲量（原版 ①②③④⑤）；</li>
 *   <li>{@code MaidSpinAttackTouchMixin} 负责"伤害"：原版 `doAutoAttackOnTouch` 在
 *       LivingEntity 上是**空实现**（只有 Player 覆写成 `attack()`），女仆得自己结算。</li>
 * </ul>
 *
 * ── 动画：**不用自己写**（问题原文"动画这一块不知道该怎么做"的答案）──
 * TLM 自己的渲染器早就把旋转突进画好了，它读的就是这个标志位：
 * <pre>
 *   GeoReplacedEntityRenderer.setupRotations （两版都有，字节码实证）：
 *       if (deathTime <= 0 && isAutoSpinAttack()) {
 *           poseStack.mulPose(Axis.YP.rotationDegrees((tickCount + partialTicks) * 75.0f));
 *           poseStack.mulPose(Axis.XP.rotationDegrees(90.0f + getXRot()));
 *       }
 *   YSMBinding 还把它暴露成 molang 变量 `is_riptide`
 *       （1.20.1 读 `m_21209_()`、1.21.1 读 `isAutoSpinAttack()`——同一个标志位）
 * </pre>
 * 也就是说：**标志位一置，Gecko 模型女仆就自动一边高速自转一边平躺突进**，模型作者还能用
 * `is_riptide` 写专属动画。Bedrock 模型那条路 TLM 自己没做（`EntityMaidRenderer` 无此分支），
 * 与 TLM 原版表现一致——本类不额外补，避免自作主张改变模型观感。
 *
 * ── 与玩家版本的**刻意差异**（三处，都有理由）──
 * <ol>
 *   <li><b>不要求在水里/雨中</b>：原版那道闸门是为了"游泳突进"设计的，而女仆在空袭/陆战时
 *       根本不在水里——照搬闸门等于这功能永远触发不了。水外使用是本次需求的核心。</li>
 *   <li><b>方向取"她→目标"而不是"她的视线"</b>：玩家自己瞄，女仆得自动瞄准；并且竖直分量
 *       夹在 [-0.15, 0.35]，避免目标在脚下时一头扎进地面（原版可以扎地，因为玩家会自己抬头）。</li>
 *   <li><b>伤害 = 攻击力 + 附魔加成</b>：1.20.1 的玩家激流走的是普通 `attack()`（伤害随
 *       攻击冷却缩放），1.21.1 是固定 8.0 + 附魔；女仆统一用"攻击力 + 附魔"（与
 *       {@code MaidFlightCombatBehavior.hitOne} 同口径，不吃暴击倍率），两树一致。</li>
 * </ol>
 *
 * ── 触发口径 ──
 * 攻击模式（`touhou_little_maid:attack`）或**近战空袭**（{@link MaidFlightKit#UID}）+
 * 主手是**激流**三叉戟 + 有存活敌对目标 + 目标在 {@link #TRIGGER_RANGE} 内 + 冷却好。
 * 冷却 {@link #COOLDOWN}（2 秒），与"攻击间隔"同量级、避免原地连转。
 * 总开关：`combat.riptideDash`（默认开）。
 */
public class MaidTridentSpinBehavior extends Behavior<EntityMaid> {

    /** 一次突进的持续 tick（照原版 `startAutoSpinAttack(20, …)`） */
    private static final int SPIN_TICKS = 20;
    /** 两次突进的间隔（tick）——原版没有冷却（靠武器冷却），女仆需要一条防连转 */
    private static final int COOLDOWN = 40;
    /** 距离目标多近才发起突进（格）：太远突进会撞墙/冲过头，先走近再说 */
    private static final double TRIGGER_RANGE = 5.0;
    /** 冲量基数：照原版 `3.0f * (1 + 激流等级) / 4.0f` */
    private static final double POWER_BASE = 3.0;
    /** 竖直分量上下限（刻意偏离原版，见类文档差异②） */
    private static final double MIN_UP = -0.15;
    private static final double MAX_UP = 0.35;

    /** 冷却到期 gameTime */
    private static final Map<UUID, Long> READY = new HashMap<>();
    /** 正在突进中（本行为的 canStillUse 依据） */
    private static final Set<UUID> ACTIVE = new HashSet<>();

    public MaidTridentSpinBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** 该任务是否允许激流突进：攻击模式 + 近战空袭（需求原文点名的两种） */
    private static boolean taskAllows(EntityMaid maid) {
        try {
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
            if (task == null) {
                return false;
            }
            ResourceLocation uid = task.getUid();
            if (MaidFlightKit.UID.equals(uid)) {
                return true; // 近战空袭
            }
            return "touhou_little_maid".equals(uid.m_135827_()) && "attack".equals(uid.m_135815_());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前锁定目标（与项目其它战斗代码同一口径：ATTACK_TARGET 记忆） */
    private static LivingEntity targetOf(EntityMaid maid) {
        try {
            Optional<LivingEntity> t = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
            if (t.isEmpty()) {
                return null;
            }
            LivingEntity e = t.get();
            return e.m_6084_() ? e : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 主手是不是"能用的激流三叉戟"：三叉戟 + 激流附魔（等级由 {@link MaidFlightKit#isRiptide} 判） */
    private static ItemStack spinWeapon(EntityMaid maid) {
        ItemStack main = maid.m_21205_();
        if (!(main.m_41720_() instanceof net.minecraft.world.item.TridentItem)) {
            return ItemStack.f_41583_;
        }
        return MaidFlightKit.isRiptide(main) ? main : ItemStack.f_41583_;
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.RIPTIDE_DASH_ENABLE.get()) {
            return false;
        }
        UUID id = maid.m_20148_();
        if (ACTIVE.contains(id)) {
            return false; // 正在转，等它转完
        }
        if (level.m_46467_() < READY.getOrDefault(id, 0L)) {
            return false;
        }
        if (!taskAllows(maid) || spinWeapon(maid).m_41619_()) {
            return false;
        }
        LivingEntity target = targetOf(maid);
        if (target == null || FriendlyFireGuard.isFriendly(maid, target)) {
            return false;
        }
        return maid.m_20270_(target) <= TRIGGER_RANGE;
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        // 1.20.1 的 Behavior.canStillUse 默认返回 false！不覆写的话 tick 永远不执行
        return ACTIVE.contains(maid.m_20148_());
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity target = targetOf(maid);
        if (target == null) {
            return;
        }
        ItemStack weapon = spinWeapon(maid);
        if (weapon.m_41619_()) {
            return;
        }
        UUID id = maid.m_20148_();
        int level_ = riptideLevel(weapon);

        // ② 方向 = 她 → 目标（原版是视线方向；竖直分量夹一下，见类文档差异②）
        double dx = target.m_20185_() - maid.m_20185_();
        double dy = target.m_20188_() - maid.m_20188_();
        double dz = target.m_20189_() - maid.m_20189_();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            len = 1.0;
        }
        double uy = Math.max(MIN_UP, Math.min(MAX_UP, dy / len));
        double uxh = dx / len;
        double uzh = dz / len;
        double hlen = Math.sqrt(uxh * uxh + uzh * uzh);
        if (hlen < 1.0E-4) {
            uxh = 0.0;
            uzh = 1.0;
            hlen = 1.0;
        }
        uxh /= hlen;
        uzh /= hlen;

        // ③ 力度照原版：3.0 × (1 + 等级) / 4
        double power = POWER_BASE * (1.0 + level_) / 4.0;
        // ④ push = 加在速度上（不是赋值）——与原版一致
        maid.m_5997_(uxh * power, uy * power, uzh * power);

        // ⑤ 置 tick 数 + 标志位 4：之后 LivingEntity.tick 自己会递减与扫掠
        LivingEntitySpinAccessor spin = (LivingEntitySpinAccessor) (Object) maid;
        spin.promaid$setSpinTicks(SPIN_TICKS);
        spin.promaid$setLivingFlag(4, true);

        // 面向目标（模型自转由 TLM 渲染器负责，这里只是让她的 yRot 与突进同向）
        try {
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            maid.m_146922_(yaw);
            maid.f_19859_ = yaw;
        } catch (Throwable ignored) {
        }
        // 突进期间别再让寻路把她拽回去
        try {
            maid.m_21573_().m_26573_();
        } catch (Throwable ignored) {
        }

        // ⑦ 音效按等级（原版三选一）+ 耐久扣 1（原版与投掷共用那一条）
        try {
            net.minecraft.sounds.SoundEvent snd = level_ >= 3
                    ? net.minecraft.sounds.SoundEvents.f_12519_
                    : (level_ == 2 ? net.minecraft.sounds.SoundEvents.f_12518_
                                   : net.minecraft.sounds.SoundEvents.f_12517_);
            level.m_5594_(null, maid.m_20183_(), snd, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        weapon.m_41622_(1, maid, m -> m.m_21166_(EquipmentSlot.MAINHAND));

        ACTIVE.add(id);
        READY.put(id, gameTime + COOLDOWN + SPIN_TICKS);
        com.maidsmart.tool.PromaidLog.log("激流突进", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + " 旋转突进（激流 " + level_ + " 级，力度 "
                + String.format("%.2f", power) + "，目标 "
                + (target.m_5446_() != null ? target.m_5446_().getString() : target.m_6095_().toString()) + "）");
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
        // 标志位由 LivingEntity 自己在 tick 里递减/清除；这里只做状态收尾与兜底
        int left = ((LivingEntitySpinAccessor) (Object) maid).promaid$getSpinTicks();
        if (left <= 0 && !maid.m_21209_()) {
            ACTIVE.remove(id);
            return;
        }
        // 兜底：标志位被别的路径清掉（撞墙/受击异常）时同步收尾，避免 ACTIVE 永久占用
        if (left > 0 && !maid.m_21209_()) {
            ((LivingEntitySpinAccessor) (Object) maid).promaid$setSpinTicks(0);
            ACTIVE.remove(id);
        }
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        ACTIVE.remove(maid.m_20148_());
    }

    /** 读激流等级（1.20.1：`EnchantmentHelper.m_44932_` 的方法体就是读 RIPTIDE 字段） */
    private static int riptideLevel(ItemStack stack) {
        try {
            return net.minecraft.world.item.enchantment.EnchantmentHelper.m_44932_(stack);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /* ================= 伤害结算（由 MaidSpinAttackTouchMixin 调用） ================= */

    /**
     * 原版 `checkAutoSpinAttack` 碰到实体时回调 `doAutoAttackOnTouch`——那个方法在
     * `LivingEntity` 上是**空实现**（只有 Player 覆写成 `attack()`），所以女仆这一记由本方法结算。
     *
     * 口径与 {@code MaidFlightCombatBehavior.hitOne} 一致：攻击力 + 附魔加成（激流突进不吃
     * 暴击倍率）× 命中 → 击退 → 火焰附加 → 荆棘 → 命中音/粒子。
     * 主人/友方与非法目标一律跳过（绝不误伤）。
     */
    public static void onSpinTouch(EntityMaid maid, LivingEntity target) {
        if (maid == null || target == null || !target.m_6084_()) {
            return;
        }
        try {
            if (FriendlyFireGuard.isFriendly(maid, target)) {
                return;
            }
            com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask task = maid.getTask();
            if (task instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask atk
                    && !atk.canAttack(maid, target)) {
                return;
            }
            ItemStack weapon = maid.m_21205_();
            float base = (float) maid.m_21133_(
                    net.minecraft.world.entity.ai.attributes.Attributes.f_22281_);
            float ench = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44833_(
                    weapon, target.m_6336_());
            float dmg = base + ench;
            if (target.m_6469_(maid.m_269291_().m_269333_(maid), dmg)) {
                double dx = target.m_20185_() - maid.m_20185_();
                double dz = target.m_20189_() - maid.m_20189_();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01) {
                    target.m_147240_(0.5, dx / len, dz / len);
                }
                try {
                    maid.m_9236_().m_5594_(null, target.m_20183_(),
                            net.minecraft.sounds.SoundEvents.f_12520_,
                            net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
                } catch (Throwable ignored) {
                }
                // 火焰附加（女仆非玩家，按 Player.attack 的 m_44914_ 逻辑手动点燃）
                int fireAspect = net.minecraft.world.item.enchantment.EnchantmentHelper.m_44914_(maid);
                if (fireAspect > 0 && !target.m_6060_()) {
                    target.m_20254_(fireAspect * 4);
                }
                // 荆棘（目标护甲反击）
                net.minecraft.world.item.enchantment.EnchantmentHelper.m_44823_(target, maid);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清场（女仆移除 / 服务器停止） */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        READY.remove(maidId);
        ACTIVE.remove(maidId);
    }

    public static void clearAll() {
        READY.clear();
        ACTIVE.clear();
    }
}
