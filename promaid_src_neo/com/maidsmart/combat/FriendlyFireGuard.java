package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 主人/友方免伤总闸——女仆绝不伤害主人与同主女仆（三层防护）。
 *
 * 根因（粉丝反馈：带女仆到雪地，空闲模式打雪仗后冲主人跳劈，脱甲约 4 心，主手武器越强越疼）：
 * TLM 原版空闲模式在雪地有打雪仗行为（MaidStartSnowballAttacking），该行为会把【主人】
 * 写进女仆 brain 的 ATTACK_TARGET（雪球目标）。promaid 的单兵战术行为
 * （MaidCombatTacticsBehavior，v1.5.202 起不再限定战斗任务）读到 ATTACK_TARGET 就接管
 * 走位 + 跳劈，对主人打出真实近战暴击伤害。
 *
 * 三层防护：
 *  ① 事件总闸（最终保险）：女仆造成的主人/友方伤害，在攻击事件与结算事件两处直接取消；
 *  ② 注入点过滤：战术 isActive/跳劈/近战横扫、中立威胁反击、自保反击（含弹幕/射箭）、
 *     LLM 攻击工具——目标为主人/友方时一律不执行；
 *  ③ 定期清除仇恨：每 2 秒把主人/友方从女仆的 ATTACK_TARGET、实体目标、复仇目标里清掉
 *     （TLM 打雪仗等娱乐行为写入的目标不会残留成"敌对状态"）。
 */
@EventBusSubscriber(modid = "promaid")
public final class FriendlyFireGuard {
    /** 仇恨清除扫描节流（tick；40 = 2 秒） */
    private static int scanCounter = 0;
    /** 仇恨清除日志限频（10 秒/女仆） */
    private static final java.util.Map<java.util.UUID, Long> HATE_LOG = new java.util.HashMap<>();
    /** 全维度范围（与 PetImmunityGuard 同款） */
    private static final AABB WHOLE = new AABB(-131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);

    private FriendlyFireGuard() {
    }

    /** ① 最终保险（攻击事件，受伤链最上游）：女仆 → 主人/友方 直接取消 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity src = event.getSource() == null ? null : event.getSource().getEntity();
        if (src instanceof EntityMaid maid && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ③ 清除仇恨：定期把主人/友方从攻击目标、实体目标、复仇目标里清掉 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++scanCounter < 40) {
            return;
        }
        scanCounter = 0;
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            for (ServerLevel lvl : server.getAllLevels()) {
                for (Entity e : lvl.getEntitiesOfClass(Entity.class, WHOLE)) {
                    if (!(e instanceof EntityMaid maid) || !maid.isAlive()) {
                        continue;
                    }
                    LivingEntity at = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
                    boolean cleared = false;
                    if (at != null && isFriendly(maid, at)) {
                        maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                        cleared = true;
                    }
                    if (isFriendly(maid, maid.getTarget())) {
                        maid.setTarget(null);
                        cleared = true;
                    }
                    if (isFriendly(maid, maid.getLastHurtByMob())) {
                        maid.setLastHurtByMob(null);
                    }
                    if (cleared) {
                        long now = System.currentTimeMillis();
                        Long last = HATE_LOG.get(maid.getUUID());
                        if (last == null || now - last > 10000L) {
                            HATE_LOG.put(maid.getUUID(), now);
                            com.mojang.logging.LogUtils.getLogger().info(
                                    "friendly-fire hate-clear: maid={}", com.maidsmart.tool.PromaidLog.nameOf(maid));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 女仆是否不该伤害该目标：自身 / 主人 / 同主人女仆 / 友军（同队等） */
    public static boolean isFriendly(EntityMaid maid, Entity target) {
        if (target == null || target == maid) {
            return true;
        }
        LivingEntity owner = maid.getOwner();
        if (owner != null && (target == owner || owner.getUUID().equals(target.getUUID()))) {
            return true;
        }
        if (target instanceof EntityMaid other) {
            LivingEntity otherOwner = other.getOwner();
            if (owner != null && otherOwner != null
                    && owner.getUUID().equals(otherOwner.getUUID())) {
                return true;
            }
        }
        try {
            return maid.isAlliedTo(target);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
