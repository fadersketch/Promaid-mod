package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * v1.1.0（1.21.1 专属）：女仆重锤猛击——参考 vanilla_mob_remake 的 ZombieMaceAttackGoal。
 *
 * 机制（与原 mod 僵尸同款）：
 * - 女仆【主手持有重锤】且有存活敌对目标；
 * - 目标进入【起跳距离】（默认 3 格）→ 朝目标方向起跳（水平 0.15 + 垂直 1.2，
 *   原 mod 数值），带 GUST / GUST_EMITTER_SMALL 粒子与 WIND_CHARGE_BURST 音效；
 * - 下落途中贴地（或已落地）时若目标仍在 4 格内 → 出手猛砸，按原版
 *   MaceItem 的 fallDistance 加成结算（4f / 12+2(f-3) / 22+(f-8) 三段），
 *   然后清零坠落距离（同原版 postHurtEnemy：猛击免摔伤）。
 *
 * 【为什么必须自己算加成】1.21.1 原版只有 Player.attack 会调
 * Item.getAttackDamageBonus（字节码实证：全 jar 仅 Player 引用），
 * Mob.doHurtTarget 走的是 ATTACK_DAMAGE 属性——所以生物拿重锤挥砍【没有】
 * 下落加成。这里显式复刻 Player.attack 的那一步：
 * EnchantmentHelper.modifyDamage → Item.getAttackDamageBonus → hurt。
 *
 * 【与落地水的平衡】猛击跃起会坠落 8 格以上，若落地水/雪在落点放桶会清零
 * fallDistance（猛击加成全丢）。实测四百四十二改为【重锤专属落地水】：
 * - 跃起全过程（LEAPING）通用落地水/雪【完全让位】——不再有"目标还在范围内就
 *   抑制、跑出去就放开"的半吊子状态（旧版 suppressFallClutch 会让水在冲刺段
 *   提前清零 fallDistance，或该放的时候来不及放）；
 * - 落地帧（本行为结算完猛击之后）置位 FORCED_CLUTCH，由 WaterClutchBehavior 在
 *   同一 tick 于落点【强制】放一格水（有水桶）/细雪（只有细雪桶）——落地摔不着，
 *   而猛击加成已经在放水之前算完，两者不再互相干扰。
 *   （顺序依赖 brain 优先级：本行为 235 先 tick，落地水 240 后 tick。）
 *
 * 【空中禁传送】跃起期间（isAirborne）禁止自动传送——跟随过远瞬移（TLM
 * teleportToOwner）、同维度远距拉回（含 Y 轴"搭太高"拉回）、跨维度跟随、
 * 自保归位传送全部让位；否则她刚飞到高空就被拽回主人身边，猛击白跳、战位全乱。
 * 玩家手动"一键集合/救援"与主人死亡传送不在此列（那是玩家/紧急意图）。
 * 跃起最长 MAX_AIR_TICKS=100 tick（5 秒）自动收尾，不会长期禁传。
 *
 * 【重锤 + 风弹，缺一不可】用户要求（1.21.1）：只有同时持有重锤与风弹、并消耗
 * 1 枚风弹时才起跳猛击；没有风弹就交还原版（TLM 正常持锤平砍）——旧版"不用
 * 风弹也能直接起飞"太超标，已移除。起跳垂直初速 1.7。
 * （开关 combat.maceWindCharge 关掉 = 恢复旧的不消耗风弹自由起跳，不推荐。）
 *
 * 【附魔与玩家挥锤一致】伤害走 EnchantmentHelper.modifyDamage（锋利/亡灵杀手/
 * 节肢杀手）+ Item.getAttackDamageBonus（重锤下落三段 + 密度），火焰附加等走
 * doPostAttackEffects，耐久走 hurtAndBreak（含耐久附魔），【击退附魔】另行补算
 * modifyKnockback——原版这一步在 Mob.doHurtTarget 里，我们直接 hurt 会绕过它。
 */
public class MaidMaceSmashBehavior extends Behavior<EntityMaid> {

    /** 起跳垂直初速（参考 mod 的 jumpVector.y） */
    private static final double JUMP_UP = 1.2;
    /** 有风弹时的自我起跳垂直初速（更高的一跳） */
    private static final double JUMP_UP_WIND = 1.7;
    /** 朝目标水平初速（参考 mod 的 0.15） */
    private static final double HORIZONTAL_BOOST = 0.15;
    /** 落地命中判定距离平方（4 格 = 参考 mod 的 distanceToSqr <= 16） */
    private static final double HIT_DIST_SQR = 16.0;
    /** 起跳后至少这么多 tick 才允许判定落地（起跳当帧 onGround 可能仍为 true） */
    private static final int MIN_AIR_TICKS = 3;
    /** 空中超时兜底（tick）：卡住/被击退也要收尾，避免状态残留 */
    private static final int MAX_AIR_TICKS = 100;

    /** 每只女仆的冷却到期 gameTime；行为实例可能共享，统一按 UUID 存 */
    private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();
    /** 跃起中的女仆 → 已滞空 tick 数 */
    private static final Map<UUID, Integer> AIR_TICKS = new HashMap<>();
    /** 正在猛击跃起的女仆 */
    private static final Set<UUID> LEAPING = new HashSet<>();
    /** 实测四百四十二：落地帧"强制放一格缓冲"请求——由 WaterClutchBehavior 消费 */
    private static final Set<UUID> FORCED_CLUTCH = new HashSet<>();
    /** 实测四百三十七：门禁诊断节流（每只女仆 5 秒最多一条，pro maid.log 搜「重锤门」） */
    private static final Map<UUID, Long> LAST_GATE_LOG = new HashMap<>();

    public MaidMaceSmashBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** 是否处于猛击跃起中（单兵战术让位用） */
    public static boolean isLeaping(EntityMaid maid) {
        return maid != null && LEAPING.contains(maid.getUUID());
    }

    /**
     * 实测四百四十二：是否处于"重锤空中状态"——跃起期间禁止自动传送 + 禁止通用
     * 落地缓冲（保住 fallDistance 给猛击）。与 isLeaping 同义，语义名更明确。
     */
    public static boolean isAirborne(EntityMaid maid) {
        return isLeaping(maid);
    }

    /**
     * 实测四百四十二：消费一次"落地帧强制缓冲"请求（WaterClutchBehavior 调用）。
     * 只在【本行为已结算完猛击、即将触地】的那一 tick 为 true——落地水据此在落点
     * 强制放一格水/雪，不看清零阈值、不看 suppress 条件。只成功一次。
     */
    public static boolean consumeForcedClutch(EntityMaid maid) {
        return maid != null && FORCED_CLUTCH.remove(maid.getUUID());
    }

    /**
     * 实测四百三十七：当前攻击目标——优先读脑内 ATTACK_TARGET，读不到就退回
     * `maid.getTarget()`（Mob 原生目标）。用户反馈"自主战斗能触发、TLM 原生攻击
     * 不能触发"，两种战斗模式写入目标的位置可能不同，两条都认，避免漏触发。
     */
    private static LivingEntity currentTarget(EntityMaid maid) {
        Optional<LivingEntity> ot = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
        if (ot.isPresent() && ot.get().isAlive()) {
            return ot.get();
        }
        LivingEntity t = maid.getTarget();
        return t != null && t.isAlive() ? t : null;
    }

    /** 诊断：记录一次"拿着重锤却没触发"的原因（每只女仆 5 秒最多一条） */
    private static void noteGate(EntityMaid maid, String reason, long gameTime) {
        try {
            if (!hasMaceSomewhere(maid)) {
                return; // 压根没重锤的女仆不记（避免刷屏）
            }
            long now = System.currentTimeMillis();
            Long last = LAST_GATE_LOG.get(maid.getUUID());
            if (last != null && now - last < 5000L) {
                return;
            }
            LAST_GATE_LOG.put(maid.getUUID(), now);
            LivingEntity t = currentTarget(maid);
            Long next = NEXT_ALLOWED.get(maid.getUUID());
            com.maidsmart.tool.PromaidLog.log("重锤门", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 不触发: " + reason
                    + " | 主手=" + maid.getMainHandItem().getItem()
                    + " 风弹=" + countWindCharges(maid)
                    + " 目标=" + (t == null ? "无" : Math.round(maid.distanceTo(t)) + "格")
                    + " 地面=" + maid.onGround()
                    + " 冷却剩=" + (next == null ? 0 : Math.max(0, next - gameTime)) + "t");
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasMaceSomewhere(EntityMaid maid) {
        if (maid.getMainHandItem().is(Items.MACE) || maid.getOffhandItem().is(Items.MACE)) {
            return true;
        }
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.MACE)) {
                return true;
            }
        }
        return false;
    }

    private static int countWindCharges(EntityMaid maid) {
        int n = 0;
        if (maid.getMainHandItem().is(Items.WIND_CHARGE)) {
            n++;
        }
        if (maid.getOffhandItem().is(Items.WIND_CHARGE)) {
            n++;
        }
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.WIND_CHARGE)) {
                n += inv.getStackInSlot(i).getCount();
            }
        }
        return n;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_SMASH.get()) {
            return false;
        }
        if (LEAPING.contains(maid.getUUID())) {
            return false;
        }
        // 只认主手重锤（装备由 MaidToolAutoEquip 按任务选武器；重锤有攻击力属性，
        // 会被当作近战武器选中——只有它时才走本行为）
        if (!maid.getMainHandItem().is(Items.MACE)) {
            noteGate(maid, "主手不是重锤", level.getGameTime());
            return false;
        }
        // 用户要求（1.21.1）：**必须同时持有重锤与风弹**才允许起跳猛击——没有风弹就
        // 交还原版（TLM 正常持锤平砍），不再出现"不用风弹也能直接起飞"的超标行为。
        if (com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_WIND_CHARGE.get()
                && !hasWindCharge(maid)) {
            noteGate(maid, "没有风弹（重锤+风弹缺一不可）", level.getGameTime());
            return false;
        }
        if (maid.isSleeping() || maid.isPassenger() || maid.isFallFlying()) {
            noteGate(maid, "睡觉/骑乘/鞘翅中", level.getGameTime());
            return false;
        }
        if (!maid.onGround() || maid.isInWater()) {
            noteGate(maid, "不在实地（空中/水里）", level.getGameTime());
            return false; // 必须站在实地才能起跳
        }
        LivingEntity target = currentTarget(maid);
        if (target == null) {
            noteGate(maid, "没有攻击目标（脑内记忆与 Mob 目标都为空）", level.getGameTime());
            return false;
        }
        if (target instanceof Player p && (p.isSpectator() || p.getAbilities().instabuild)) {
            return false;
        }
        if (FriendlyFireGuard.isFriendly(maid, target)) {
            return false;
        }
        if (maid.distanceToSqr(target) > 256.0) {
            return false; // 参考 mod：16 格内
        }
        double range = com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_TRIGGER_RANGE.get();
        if (maid.distanceTo(target) > range) {
            noteGate(maid, "超出起跳距离", level.getGameTime());
            return false;
        }
        Long next = NEXT_ALLOWED.get(maid.getUUID());
        if (next != null && level.getGameTime() < next) {
            noteGate(maid, "冷却中", level.getGameTime());
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity target = currentTarget(maid);
        if (target == null) {
            return;
        }
        boolean needWind = com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_WIND_CHARGE.get();
        boolean wind = needWind && consumeWindCharge(maid);
        if (needWind && !wind) {
            return; // 保险：checkExtraStartConditions 已保证有风弹
        }
        double up = wind ? JUMP_UP_WIND : JUMP_UP;
        Vec3 mp = maid.position();
        Vec3 tp = target.position();
        double dx = tp.x - mp.x;
        double dz = tp.z - mp.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd > 1.0E-4) {
            dx /= hd;
            dz /= hd;
        } else {
            dx = 0.0;
            dz = 0.0;
        }
        maid.setDeltaMovement(dx * HORIZONTAL_BOOST, up, dz * HORIZONTAL_BOOST);
        maid.hurtMarked = true; // 把速度同步给客户端（否则本地看不到起跳）
        maid.getLookControl().setLookAt(target, 30.0f, 30.0f);
        // 起跳特效（参考 mod：GUST + GUST_EMITTER_SMALL + WIND_CHARGE_BURST）
        level.sendParticles(ParticleTypes.GUST, mp.x, mp.y + 1.0, mp.z, 5, 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, mp.x, mp.y + 1.0, mp.z, 4, 0.4, 0.4, 0.4, 0.08);
        level.playSound(null, mp.x, mp.y, mp.z, SoundEvents.WIND_CHARGE_BURST.value(),
                SoundSource.NEUTRAL, wind ? 1.0f : 0.7f, 1.0f);
        UUID id = maid.getUUID();
        LEAPING.add(id);
        AIR_TICKS.put(id, 0);
        // 兜底清理：落地水开关全关时 FORCED_CLUTCH 没人消费，防止长期残留
        if (FORCED_CLUTCH.size() > 256) {
            FORCED_CLUTCH.removeIf(u -> !LEAPING.contains(u));
        }
        NEXT_ALLOWED.put(id, gameTime + Math.max(1, com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_COOLDOWN.get()));
        if (NEXT_ALLOWED.size() > 512) {
            NEXT_ALLOWED.values().removeIf(t -> t < gameTime);
        }
        com.maidsmart.tool.PromaidLog.log("重锤", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + (wind ? " 借风弹起跳猛击 " : " 起跳猛击 ")
                + (target.getDisplayName() != null ? target.getDisplayName().getString() : target.getUUID().toString()));
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return LEAPING.contains(maid.getUUID());
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.getUUID();
        if (!LEAPING.contains(id)) {
            return;
        }
        LivingEntity target = currentTarget(maid);
        if (target != null) {
            maid.getLookControl().setLookAt(target, 30.0f, 30.0f);
        }
        int air = AIR_TICKS.getOrDefault(id, 0) + 1;
        AIR_TICKS.put(id, air);
        if (air < MIN_AIR_TICKS) {
            return;
        }
        // 收尾时机（参考 mod）：下落中贴近地面（提前一 tick 落地——这样清零
        // fallDistance 能赶在原版落地摔伤判定之前）或已落地，或超时兜底
        boolean aboutToLand = maid.getDeltaMovement().y < 0.0 && isNearGround(level, maid);
        if (!maid.onGround() && !aboutToLand && air < MAX_AIR_TICKS) {
            return;
        }
        LEAPING.remove(id);
        AIR_TICKS.remove(id);
        // 实测四百四十二：顺序不能反——先用【此刻仍非零的 fallDistance】结算猛击，
        // 再置位"落地帧强制缓冲"请求，让 WaterClutchBehavior（优先级 240，在本行为
        // 235 之后 tick）在同一 tick 于落点强放一格水/雪。反过来的话水会先清零
        // fallDistance，重锤下落加成全部丢失。
        if (target != null && maid.distanceToSqr(target) <= HIT_DIST_SQR) {
            smashHit(level, maid, target);
        }
        FORCED_CLUTCH.add(id);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        LEAPING.remove(maid.getUUID());
        AIR_TICKS.remove(maid.getUUID());
    }

    /**
     * 复刻 Player.attack 对重锤的处理（原版生物路径不含这段，必须自己做）：
     * 基础攻击力 → 附魔修正 → 重锤下落加成 → 命中 → 附魔后效 → 耐久 + 清坠落。
     */
    private static void smashHit(ServerLevel level, EntityMaid maid, LivingEntity target) {
        ItemStack weapon = maid.getMainHandItem();
        if (!weapon.is(Items.MACE)) {
            maid.doHurtTarget(target); // 途中换了武器 → 退回普通近战
            return;
        }
        float damage = (float) maid.getAttributeValue(Attributes.ATTACK_DAMAGE);
        DamageSource source = maid.damageSources().mobAttack(maid);
        damage = EnchantmentHelper.modifyDamage(level, weapon, target, source, damage);
        float bonus = weapon.getItem().getAttackDamageBonus(target, damage, source);
        boolean hit = target.hurt(source, damage + bonus);
        if (hit) {
            // 附魔后效（火焰附加等 POST_ATTACK 组件）
            EnchantmentHelper.doPostAttackEffects(level, target, source);
            // 附魔【击退】：原版由 Mob.doHurtTarget 计算并施加，我们直接 hurt 会绕过它
            // ——不补这一步，女仆重锤吃了击退附魔也不会击退（用户："附魔对女仆操作重锤有用吗"）
            float knockback = EnchantmentHelper.modifyKnockback(level, weapon, target, source, 0.0f);
            if (knockback > 0.0f) {
                double kx = net.minecraft.util.Mth.sin(maid.getYRot() * ((float) Math.PI / 180.0F));
                double kz = -net.minecraft.util.Mth.cos(maid.getYRot() * ((float) Math.PI / 180.0F));
                target.knockback(knockback * 0.5, kx, kz);
                maid.setDeltaMovement(maid.getDeltaMovement().multiply(0.6, 1.0, 0.6));
            }
            // 原版猛砸【冲击表现】（即使无附魔也有）：在目标脚下喷砸击烟尘环
            // （LevelEvent.PARTICLES_SMASH_ATTACK = 2013，data 750——照 MaceItem.knockback 的原版数值）
            level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_SMASH_ATTACK,
                    target.getOnPos(), 750);
            // 音效按原版选择：贴地砸（落距 > 5 = 重砸音）/ 空中砸
            net.minecraft.sounds.SoundEvent smashSound = maid.onGround()
                    ? (maid.fallDistance > 5.0F
                            ? SoundEvents.MACE_SMASH_GROUND_HEAVY : SoundEvents.MACE_SMASH_GROUND)
                    : SoundEvents.MACE_SMASH_AIR;
            level.playSound(null, maid.getX(), maid.getY(), maid.getZ(), smashSound,
                    maid.getSoundSource(), 1.0f, 1.0f);
        }
        weapon.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
        // 同原版 MaceItem.postHurtEnemy：猛击后清零坠落距离（免摔伤，
        // 也顺带避免落地水在她命中后多此一举）
        maid.resetFallDistance();
    }

    /** 脚下 1~2 格内有非空气方块 = 贴近地面（参考 mod 的 isNearGround） */
    private static boolean isNearGround(ServerLevel level, EntityMaid maid) {
        BlockPos p = maid.blockPosition();
        for (int i = 0; i <= 1; i++) {
            if (!level.getBlockState(p.below(i)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** 身上是否有风弹（起跳前置条件：重锤 + 风弹缺一不可）——背包 + 副手都认 */
    public static boolean hasWindCharge(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        if (maid.getOffhandItem().is(Items.WIND_CHARGE)) {
            return true;
        }
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.WIND_CHARGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取 1 枚风弹（找不到返回 false，不消耗）。
     * 实测四百三十七：背包 + 副手都认——旧版只扫背包，玩家把风弹放副手时
     * hasWindCharge=false（根本不触发）或消耗失败（白起跳），都是误判。
     */
    private static boolean consumeWindCharge(EntityMaid maid) {
        net.neoforged.neoforge.items.IItemHandler inv = maid.getMaidInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.WIND_CHARGE)) {
                inv.extractItem(i, 1, false);
                return true;
            }
        }
        net.minecraft.world.item.ItemStack off = maid.getOffhandItem();
        if (off.is(Items.WIND_CHARGE)) {
            off.shrink(1);
            return true;
        }
        return false;
    }
}
