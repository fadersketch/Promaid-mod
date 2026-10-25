package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
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
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 重锤猛击（1.20.1 移植版，适配「试炼密室」回移植的 {@code trials:mace} / {@code trials:wind_charge}）。
 * <p>
 * 行为口径与 1.21.1 版 {@code MaidMaceSmashBehavior} 一致：
 * 主手重锤 + 目标在起跳距离内 → 朝目标起跳（水平 0.15、垂直 1.2；带风弹 1.7 并消耗 1 枚），
 * 下落贴地时若目标仍在 4 格内 → 按下落距离结算猛击并清零坠落距离。
 * <p>
 * 1.20.1 没有原版 MaceItem，也没有 {@code Item#getAttackDamageBonus}：伤害在这里显式补，
 * 公式照 1.21.1 原版（4 / 12+2×(f-3) / 22+(f-8)），附魔与耐久走 1.20.1 的
 * {@link EnchantmentHelper} 那一套。物品/音效全部按注册名查，不硬依赖 Trials 的类，
 * 没装 Trials 时本行为永远不会启动。
 */
public class MaidMaceSmashBehavior extends Behavior<EntityMaid> {

    /** 起跳垂直初速（与 1.21.1 版一致） */
    private static final double JUMP_UP = 1.2;
    /** 有风弹时的自我起跳垂直初速 */
    private static final double JUMP_UP_WIND = 1.7;
    /** 朝目标水平初速 */
    private static final double HORIZONTAL_BOOST = 0.15;
    /** 落地命中判定距离平方（4 格） */
    private static final double HIT_DIST_SQR = 16.0;
    /**
     * 【实测一千零五十九】猛击只贴身用：2 格外她手够得着，一律重锤平A。
     * 旧版默认 3 格，配合战斗走位停在 2.6 格 → 每 3 秒就起跳一次，玩家看到的是
     * “不用平A、只会风弹起跳下落”。配置上限也压到 2，这里再兜一道，老 toml 写 3 也按 2。
     */
    private static final double MACE_LEAP_MAX_RANGE = 2.0;
    /** 起跳后至少这么多 tick 才允许判定落地 */
    private static final int MIN_AIR_TICKS = 3;
    /** 空中超时兜底（tick） */
    private static final int MAX_AIR_TICKS = 100;

    /** 每只女仆的冷却到期 gameTime */
    private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();
    /** 跃起中的女仆 → 已滞空 tick 数 */
    private static final Map<UUID, Integer> AIR_TICKS = new HashMap<>();
    /** 正在猛击跃起的女仆 */
    private static final Set<UUID> LEAPING = new HashSet<>();
    /**
     * 【实测一千〇六十】自上一次起跳以来，这一记猛击**真的打中了**。
     * 轰炸链路（TNT / 末地水晶 / 重生锚 / 床）在重锤+风弹档必须等这一步：
     * 起跳时清掉，落地 {@link #smashHit} 命中后写上——否则炸弹会在重锤落下之前
     * 把目标掀飞，砸个空。非重锤档 / 空袭档不受此限（见 {@link #bombingAllowed}）。
     */
    private static final Set<UUID> SMASH_HIT = new HashSet<>();
    /** 门禁诊断节流 */
    private static final Map<UUID, Long> LAST_GATE_LOG = new HashMap<>();
    /** 落地帧"强制放一格缓冲"请求——由 WaterClutchBehavior 消费 */
    private static final Set<UUID> FORCED_CLUTCH = new HashSet<>();

    public MaidMaceSmashBehavior() {
        super(Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** 是否处于猛击跃起中（其他链路让位用） */
    public static boolean isLeaping(EntityMaid maid) {
        return maid != null && LEAPING.contains(maid.m_20148_());
    }

    /** 跃起或空袭飞行中：两条链路都靠 fallDistance 吃加成，自动传送/搭方块一律让位 */
    public static boolean isAirborne(EntityMaid maid) {
        return isLeaping(maid) || MaidFlightKit.isFlightAirborne(maid);
    }

    /** 落地帧请求一次缓冲（由 WaterClutchBehavior 在同一 tick 消费） */
    public static void requestForcedClutch(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        if (FORCED_CLUTCH.size() > 256) {
            FORCED_CLUTCH.clear();
        }
        FORCED_CLUTCH.add(maid.m_20148_());
    }

    /** 消费一次缓冲请求：只成功一次 */
    public static boolean consumeForcedClutch(EntityMaid maid) {
        return maid != null && FORCED_CLUTCH.remove(maid.m_20148_());
    }

    /** 女仆移除时清掉残留请求 */
    public static void forget(UUID maidId) {
        if (maidId == null) {
            return;
        }
        LEAPING.remove(maidId);
        AIR_TICKS.remove(maidId);
        NEXT_ALLOWED.remove(maidId);
        LAST_GATE_LOG.remove(maidId);
        FORCED_CLUTCH.remove(maidId);
        SMASH_HIT.remove(maidId);
    }

    /** 服务端停止时清空全部静态表 */
    public static void clearAll() {
        LEAPING.clear();
        AIR_TICKS.clear();
        NEXT_ALLOWED.clear();
        LAST_GATE_LOG.clear();
        FORCED_CLUTCH.clear();
        SMASH_HIT.clear();
    }

    static boolean isTrialsItem(ItemStack stack, String path) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        String key = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.m_41720_()));
        return key.startsWith("trials:") && key.substring(7).equals(path);
    }

    public static boolean isMace(ItemStack stack) {
        return isTrialsItem(stack, "mace");
    }

    /**
     * 【实测一千〇六十】这一拍是不是"重锤 + 风弹起跳猛击"档：主手重锤、猛击开着，
     * 且风弹条件满足（要求消耗风弹时身上至少有一枚）。
     * 单兵战术据此把绕圈半径压进猛击距离；轰炸链路据此等命中。
     */
    public static boolean maceLeapMode(EntityMaid maid) {
        if (maid == null || !com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_SMASH.get()) {
            return false;
        }
        if (!isMace(maid.m_21205_())) {
            return false;
        }
        return !com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_WIND_CHARGE.get()
                || hasWindCharge(maid);
    }

    /**
     * 重锤档是否需要"先命中再放炸弹"。空袭任务（本行为让位给俯冲猛击）、骑乘、
     * 鞘翅、睡觉这些状态下猛击不会起跳，不能把轰炸链路一起锁死。
     */
    public static boolean waitsForMaceHit(EntityMaid maid) {
        if (!maceLeapMode(maid)) {
            return false;
        }
        if (maid.m_5803_() || maid.m_20159_() || maid.m_21255_()) {
            return false;
        }
        return !(MaidFlightKit.isFlightTask(maid) && MaidFlightKit.isModeActive(maid));
    }

    /**
     * 轰炸链路（TNT / 末地水晶 / 重生锚 / 床）现在能不能起手？
     * 非重锤档恒 true；重锤档只在上一次起跳**落地真的打中**之后放行——
     * 起跳那一 tick 清标记，{@link #smashHit} 命中后重新写上。
     */
    public static boolean bombingAllowed(EntityMaid maid) {
        if (!waitsForMaceHit(maid)) {
            return true;
        }
        return SMASH_HIT.contains(maid.m_20148_());
    }

    private static boolean isWindCharge(ItemStack stack) {
        return isTrialsItem(stack, "wind_charge");
    }

    /** Trials 的音效按注册名取；没装该模组时回退原版暴击音，绝不因缺音效崩 */
    static SoundEvent trialSound(String path, SoundEvent fallback) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("trials", path));
        return sound != null ? sound : fallback;
    }

    /** 当前攻击目标：优先 brain 的 ATTACK_TARGET，读不到再退回 Mob 原生目标 */
    private static LivingEntity currentTarget(EntityMaid maid) {
        Optional<LivingEntity> memory = maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
        if (memory.isPresent() && memory.get().m_6084_()) {
            return memory.get();
        }
        LivingEntity target = maid.m_5448_();
        return target != null && target.m_6084_() ? target : null;
    }

    private static boolean hasMaceSomewhere(EntityMaid maid) {
        if (isMace(maid.m_21205_()) || isMace(maid.m_21206_())) {
            return true;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isMace(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    private static int countWindCharges(EntityMaid maid) {
        int n = 0;
        if (isWindCharge(maid.m_21205_())) {
            n++;
        }
        if (isWindCharge(maid.m_21206_())) {
            n++;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (isWindCharge(stack)) {
                n += stack.m_41613_();
            }
        }
        return n;
    }

    private static boolean hasWindCharge(EntityMaid maid) {
        if (isWindCharge(maid.m_21206_())) {
            return true;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isWindCharge(inv.getStackInSlot(i))) {
                return true;
            }
        }
        // 主手留给重锤，风弹只认副手/背包；额外容器（精妙背包/旅行者背包）也算。
        return com.maidsmart.tool.MaidExtraContainer.contains(maid,
                MaidMaceSmashBehavior::isWindCharge);
    }

    private static boolean consumeWindCharge(EntityMaid maid) {
        // 真取物：先从额外容器搬一件到背包，下面的背包/副手扫描才拿得到。
        com.maidsmart.tool.MaidExtraContainer.pull(maid,
                MaidMaceSmashBehavior::isWindCharge, 1);
        IItemHandler inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (isWindCharge(inv.getStackInSlot(i))) {
                inv.extractItem(i, 1, false);
                return true;
            }
        }
        ItemStack off = maid.m_21206_();
        if (isWindCharge(off)) {
            off.m_41774_(1);
            return true;
        }
        return false;
    }

    private static void noteGate(EntityMaid maid, String reason, long gameTime) {
        try {
            if (!hasMaceSomewhere(maid)) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = LAST_GATE_LOG.get(maid.m_20148_());
            if (last != null && now - last < 5000L) {
                return;
            }
            LAST_GATE_LOG.put(maid.m_20148_(), now);
            LivingEntity target = currentTarget(maid);
            Long next = NEXT_ALLOWED.get(maid.m_20148_());
            com.maidsmart.tool.PromaidLog.log("重锤门", com.maidsmart.tool.PromaidLog.nameOf(maid)
                    + " 不触发: " + reason
                    + " | 主手=" + maid.m_21205_().m_41720_()
                    + " 风弹=" + countWindCharges(maid)
                    + " 目标=" + (target == null ? "无" : Math.round(maid.m_20270_(target)) + "格")
                    + " 地面=" + maid.m_20096_()
                    + " 冷却剩=" + (next == null ? 0 : Math.max(0, next - gameTime)) + "t");
        } catch (Throwable ignored) {
            com.maidsmart.diag.Diag.swallow("MaidMaceSmashBehavior", ignored);
        }
    }

    @Override
    protected boolean m_6114_(ServerLevel level, EntityMaid maid) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_SMASH.get()) {
            return false;
        }
        if (LEAPING.contains(maid.m_20148_())) {
            return false;
        }
        if (!isMace(maid.m_21205_())) {
            noteGate(maid, "主手不是重锤", level.m_46467_());
            return false;
        }
        if (com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_WIND_CHARGE.get()
                && !hasWindCharge(maid)) {
            noteGate(maid, "没有风弹（重锤+风弹缺一不可）", level.m_46467_());
            return false;
        }
        if (maid.m_5803_() || maid.m_20159_() || maid.m_21255_()) {
            noteGate(maid, "睡觉/骑乘/鞘翅中", level.m_46467_());
            return false;
        }
        if (MaidFlightKit.isFlightTask(maid) && MaidFlightKit.isModeActive(maid)) {
            noteGate(maid, "空袭任务进行中（让位给空袭起飞/俯冲猛击）", level.m_46467_());
            return false;
        }
        if (!maid.m_20096_() || maid.m_20069_()) {
            noteGate(maid, "不在实地（空中/水里）", level.m_46467_());
            return false;
        }
        LivingEntity target = currentTarget(maid);
        if (target == null) {
            noteGate(maid, "没有攻击目标（脑内记忆与 Mob 目标都为空）", level.m_46467_());
            return false;
        }
        if (target instanceof Player p && (p.m_5833_() || p.m_150110_().f_35937_)) {
            return false;
        }
        if (FriendlyFireGuard.isFriendly(maid, target)) {
            return false;
        }
        if (maid.m_20280_(target) > 256.0) {
            return false;
        }
        double range = Math.min(com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_TRIGGER_RANGE.get(),
                MACE_LEAP_MAX_RANGE);
        if (maid.m_20270_(target) > range) {
            noteGate(maid, "超出贴身距离（>2格，改用重锤平A）", level.m_46467_());
            return false;
        }
        Long next = NEXT_ALLOWED.get(maid.m_20148_());
        if (next != null && level.m_46467_() < next) {
            noteGate(maid, "冷却中", level.m_46467_());
            return false;
        }
        // 【实测一千〇六十】她名下还有炸弹没炸完：这一跳先等它——否则炸弹把目标掀飞，
        // 这记重锤又砸空。没有炸弹时这条恒 false，不影响起跳频率。
        if (MaidBombing.hasLiveBomb(maid)) {
            noteGate(maid, "炸弹还没炸完（等它落地再起跳）", level.m_46467_());
            return false;
        }
        return true;
    }

    @Override
    protected void m_6735_(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity target = currentTarget(maid);
        if (target == null) {
            return;
        }
        boolean needWind = com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_WIND_CHARGE.get();
        boolean wind = needWind && consumeWindCharge(maid);
        if (needWind && !wind) {
            return;
        }
        double up = wind ? JUMP_UP_WIND : JUMP_UP;
        Vec3 mp = maid.m_20182_();
        Vec3 tp = target.m_20182_();
        double dx = tp.f_82479_ - mp.f_82479_;
        double dz = tp.f_82481_ - mp.f_82481_;
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd > 1.0E-4) {
            dx /= hd;
            dz /= hd;
        } else {
            dx = 0.0;
            dz = 0.0;
        }
        maid.m_20256_(new Vec3(dx * HORIZONTAL_BOOST, up, dz * HORIZONTAL_BOOST));
        maid.f_19864_ = true;
        maid.m_21563_().m_24960_(target, 30.0f, 30.0f);
        BlockPos pos = maid.m_20183_();
        level.m_8767_(ParticleTypes.f_123796_, mp.f_82479_, mp.f_82480_ + 1.0, mp.f_82481_,
                8, 1.5, 0.15, 1.5, 0.0);
        level.m_8767_(ParticleTypes.f_123813_, mp.f_82479_, mp.f_82480_ + 1.0, mp.f_82481_,
                4, 1.8, 0.15, 1.8, 0.0);
        level.m_5594_(null, pos, trialSound("wind_charge", SoundEvents.f_12313_),
                SoundSource.NEUTRAL, wind ? 1.0f : 0.7f, 1.0f);
        UUID id = maid.m_20148_();
        LEAPING.add(id);
        AIR_TICKS.put(id, 0);
        // 新一跳开始：上一次的"已命中"到此作废。轰炸链路在重锤档必须等这一跳落地砸中，
        // 免得炸弹先落地把目标掀飞（反馈："重锤还没打到怪，怪先被炸飞了"）。
        SMASH_HIT.remove(id);
        NEXT_ALLOWED.put(id, gameTime + Math.max(1,
                com.maidsmart.config.MaidSmartConfig.COMBAT_MACE_COOLDOWN.get()));
        if (NEXT_ALLOWED.size() > 512) {
            NEXT_ALLOWED.values().removeIf(t -> t < gameTime);
        }
        com.maidsmart.tool.PromaidLog.log("重锤", com.maidsmart.tool.PromaidLog.nameOf(maid)
                + (wind ? " 借风弹起跳猛击 " : " 起跳猛击 ")
                + (target.m_5446_() != null ? target.m_5446_().getString() : target.m_20148_().toString()));
    }

    @Override
    protected boolean m_6737_(ServerLevel level, EntityMaid maid, long gameTime) {
        return LEAPING.contains(maid.m_20148_());
    }

    @Override
    protected void m_6725_(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID id = maid.m_20148_();
        if (!LEAPING.contains(id)) {
            return;
        }
        LivingEntity target = currentTarget(maid);
        if (target != null) {
            maid.m_21563_().m_24960_(target, 30.0f, 30.0f);
        }
        int air = AIR_TICKS.getOrDefault(id, 0) + 1;
        AIR_TICKS.put(id, air);
        if (air < MIN_AIR_TICKS) {
            return;
        }
        boolean aboutToLand = maid.m_20184_().f_82480_ < 0.0 && isNearGround(level, maid);
        if (!maid.m_20096_() && !aboutToLand && air < MAX_AIR_TICKS) {
            return;
        }
        LEAPING.remove(id);
        AIR_TICKS.remove(id);
        if (target != null && maid.m_20280_(target) <= HIT_DIST_SQR) {
            smashHit(level, maid, target);
        }
        // 落地帧：先按此刻仍非零的坠落距离结算猛击，再请求落地缓冲，最后清坠落距离。
        // 1.20.1 没有 Neo 的强制通道兜底，这里无条件清一次——目标半空被打死/跑开时
        // 她不会按自起跳高度摔伤；有水桶时 WaterClutchBehavior 仍会在落点放一格水。
        requestForcedClutch(maid);
        maid.m_183634_();
    }

    @Override
    protected void m_6732_(ServerLevel level, EntityMaid maid, long gameTime) {
        LEAPING.remove(maid.m_20148_());
        AIR_TICKS.remove(maid.m_20148_());
    }

    /**
     * 基础攻击力 → 附魔修正 → 重锤下落加成 → 命中 → 附魔后效 → 耐久 + 清坠落。
     * 1.20.1 的生物路径没有“重锤加成”这一步，所以显式复刻。
     */
    private static void smashHit(ServerLevel level, EntityMaid maid, LivingEntity target) {
        maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack weapon = maid.m_21205_();
        if (!isMace(weapon)) {
            maid.m_7327_(target);
            return;
        }
        float fall = maid.f_19789_;
        float bonus = maceFallBonus(fall);
        float damage = (float) maid.m_21133_(Attributes.f_22281_);
        DamageSource source = maid.m_269291_().m_269333_(maid);
        damage += EnchantmentHelper.m_44833_(weapon, target.m_6336_());
        boolean hit = target.m_6469_(source, damage + bonus);
        if (!hit && target.f_19802_ > 0) {
            // 平A 刚打中时目标还在无敌帧里，猛击会被原版直接吞掉（hurt 返回 false）。
            // 与飞行作战 hitOne 同一口径：清掉无敌帧再结算一次——否则这一跳看起来砸中了，
            // 实际 0 伤害，也不会给轰炸链路放行。
            target.f_19802_ = 0;
            hit = target.m_6469_(source, damage + bonus);
        }
        if (hit) {
            if (SMASH_HIT.size() > 512) {
                SMASH_HIT.clear();
            }
            SMASH_HIT.add(maid.m_20148_());
            int fireAspect = EnchantmentHelper.m_44914_(maid);
            if (fireAspect > 0 && !target.m_6060_()) {
                target.m_20254_(fireAspect * 4);
            }
            int knockback = EnchantmentHelper.m_44894_(maid);
            if (knockback > 0) {
                double kx = net.minecraft.util.Mth.m_14031_(maid.m_146908_() * ((float) Math.PI / 180.0F));
                double kz = -net.minecraft.util.Mth.m_14089_(maid.m_146908_() * ((float) Math.PI / 180.0F));
                target.m_147240_(knockback * 0.5, kx, kz);
                maid.m_20256_(maid.m_20184_().m_82542_(0.6, 1.0, 0.6));
            }
            EnchantmentHelper.m_44823_(target, maid);
            BlockPos pos = target.m_20183_();
            level.m_8767_(ParticleTypes.f_123796_, pos.m_123341_() + 0.5, pos.m_123342_() + 0.2,
                    pos.m_123343_() + 0.5, 8, 1.5, 0.15, 1.5, 0.0);
            level.m_8767_(ParticleTypes.f_123813_, pos.m_123341_() + 0.5, pos.m_123342_() + 0.2,
                    pos.m_123343_() + 0.5, 4, 1.8, 0.15, 1.8, 0.0);
            level.m_5594_(null, pos, smashSound(maid, fall), SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        weapon.m_41622_(1, maid, e -> e.m_21166_(EquipmentSlot.MAINHAND));
        maid.m_183634_();
    }

    /** 猛击音效：贴地重砸 / 贴地砸 / 空中砸（音效按注册名查，缺 Trials 时回退原版） */
    static SoundEvent smashSound(EntityMaid maid, float fall) {
        if (maid.m_20096_()) {
            return fall > 5.0F
                    ? trialSound("mace_smash_ground_heavy", SoundEvents.f_12316_)
                    : trialSound("mace_smash_ground", SoundEvents.f_12316_);
        }
        return trialSound("mace_smash_air", SoundEvents.f_12313_);
    }

    /** 1.21.1 原版重锤下落加成（三段）。低于 1.5 格不触发猛击加伤。 */
    static float maceFallBonus(float fall) {
        if (fall > 8.0F) {
            return 22.0F + (fall - 8.0F);
        }
        if (fall > 3.0F) {
            return 12.0F + 2.0F * (fall - 3.0F);
        }
        if (fall > 1.5F) {
            return 4.0F;
        }
        return 0.0F;
    }

    /** 脚下 1~2 格内有非空气方块 = 贴近地面 */
    private static boolean isNearGround(ServerLevel level, EntityMaid maid) {
        BlockPos p = maid.m_20183_();
        for (int i = 0; i <= 1; i++) {
            if (!level.m_8055_(p.m_6625_(i)).m_60795_()) {
                return true;
            }
        }
        return false;
    }
}
