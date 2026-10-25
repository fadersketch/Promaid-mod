package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 宠物 / 友方单位免疫总闸（实测一百九十三）：女仆绝不对玩家宠物、模组召唤物、其他玩家的
 * 女仆造成伤害（含 AoE），并清掉这类仇恨。
 * 三层防线：① 目标选取层拦截——宠物标记目标不进攻击记忆；
 * ② 本类伤害总闸：受害者命中宠物标记 → 整次伤害取消（近战/横扫/箭矢/弹幕/枪械/爆炸）；
 * ③ 仇恨兜底：每 2 秒扫一遍攻击记忆，标记目标一律清掉。
 * 宠物标记判定（isPetMarked）：a. 名字含「玩家宠物」「主人的宠物」或以「MaidNoAttack」开头；
 * b. 有玩家主人的 OwnableEntity（可驯服宠物 / 模组召唤物 / 其他玩家的女仆）——
 *    自己或同队队友名下的一律免伤，PvP 关闭时任何玩家名下的一律免伤，
 *    PvP 开启且不同队按原版可交战。
 */
@EventBusSubscriber(modid = "promaid")
public final class PetImmunityGuard {
    /** 仇恨清理扫描节流（tick，40 = 2 秒）——全事件实现，不碰 TLM 混入 */
    private static int scanCounter = 0;
    /** 宠物仇恨清除日志限频（60 秒/女仆） */
    private static final java.util.Map<java.util.UUID, Long> HATE_CLEAR_LOG = new java.util.HashMap<>();
    /** v1.1.0 实测二百四十五：宠物判定未命中诊断日志限频（60 秒/女仆）——
     *  女仆伤害了名字含"宠物"字样的目标但 isPetMarked 返回 false 时记录名字原文 */
    private static final java.util.Map<java.util.UUID, Long> PET_MISS_LOG = new java.util.HashMap<>();

    private PetImmunityGuard() {
    }

    /**
     * 宠物仇恨清理扫描（v1.1.0 实测一百九十七修订）：
     * 宠物免疫的目标选取拦截原计划混入 TLM 的 IAttackTask.canAttack——Mixin 0.8.5
     * 对接口 target 两种形式都不支持（class mixin：SubType 校验 PREPARE 失败；
     * interface mixin：@Inject 不被接受，APPLY 失败，玩家两次启动崩溃日志实证）。
     * 改为全事件实现：本扫描每 2 秒全维度检查女仆攻击记忆，目标是宠物标记 →
     * 清掉（去仇恨；TLM StopAttackingIfTargetInvalid 等行为自然接管后续）； 伤害层由下方 LivingIncomingDamageEvent 总闸兜底（锁定瞬间也零伤害、零溅射）。 与"锁定优先级"的语义差别：女仆可能先锁定一眼才被清掉（≤2 秒）， 但全程无伤害；可接受。
     */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
if (++scanCounter < 40) {
            return;
        }
        scanCounter = 0;
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            long now = server.getAllLevels().iterator().next().getGameTime();
            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                // v1.1.0 实测三百三十：EntityMaid.class 全图扫描改用 Entity.class 全量 +
                // instanceof 过滤——类查询漏扫（同 FarmTillDriver，真因见 MaidRoster）
                // v1.2.0（2026-09-18）【Sable 兼容】：全世界 AABB → getAllEntities()（超大 AABB 被 Sable 拒查并且每次刷一份堆栈日志）
                for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.MaidRoster.inAsEntities(lvl)) {
                    if (!(e instanceof EntityMaid maid) || !maid.isAlive()) {
                        continue;
                    }
                    try {
                        var atk = maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
                        if (atk.isEmpty()) {
                            continue;
                        }
                        LivingEntity target = atk.get();
                        if (target == null || !isPetMarked(maid, target)) {
                            continue;
                        }
                        maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
                        Long last = HATE_CLEAR_LOG.get(maid.getUUID());
                        if (last == null || now - last > 1200L) {
                            HATE_CLEAR_LOG.put(maid.getUUID(), now);
                            org.slf4j.LoggerFactory.getLogger("promaid").info(
                                    "maid pet-hate-clear: maid={} target={}（宠物标记，仇恨已清、伤害免疫）",
                                    com.maidsmart.tool.PromaidLog.nameOf(maid),
                                    target.getDisplayName() != null ? target.getDisplayName().getString() : target.getUUID());
                        }
                    } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        try {
            if (event.getAmount() <= 0.0f) {
                return;
            }
            LivingEntity victim = event.getEntity();
            if (victim == null) {
                return;
            }
            var source = event.getSource();
            if (source == null) {
                return;
            }
            // 实测一千〇一十五：归因统一走 FriendlyFireGuard（含"弹丸 owner 是女仆"）——
            // 枪械子弹常只把弹丸写进伤害源，只查 cause/direct 认不出是谁开的枪。
            EntityMaid maid = FriendlyFireGuard.maidOfDamage(source);
            if (maid == null) {
                return; // 不是女仆造成的伤害——不拦（玩家/怪打宠物照常）
            }
            if (isPetMarked(maid, victim)) {
                event.setCanceled(true); // 女仆链上的任何伤害打到宠物 → 免疫
                return;
            }
            // v1.1.0 实测二百四十五（反馈："哪怕给一个铁傀儡命名为玩家宠物，女仆仍然
            // 可以伤到铁傀儡"）：判定未命中诊断——受害者名字含"宠物"字样但 isPetMarked
            // 返回 false，记录名字原文（getDisplayName 显示名 / getCustomName 自定义名 / getName 名）
            // 到 promaid.log，60 秒限频/女仆，定位"名字判定为什么不命中"。
            try {
                var vn = victim.getDisplayName();
                if (vn != null && vn.getString().contains("宠物")) {
                    long now = victim.level().getGameTime();
                    Long last = PET_MISS_LOG.get(maid.getUUID());
                    if (last == null || now - last > 1200L) {
                        PET_MISS_LOG.put(maid.getUUID(), now);
                        String custom = victim.getCustomName() != null ? victim.getCustomName().getString() : "null";
                        String plain = victim.getName() != null ? victim.getName().getString() : "null";
                        org.slf4j.LoggerFactory.getLogger("promaid").info(
                                "maid pet-miss: maid={} victim={} display=[{}] custom=[{}] name=[{}]",
                                com.maidsmart.tool.PromaidLog.nameOf(maid),
                                victim.getUUID(), vn.getString(), custom, plain);
                    }
                }
            } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
    }

    /** 目标/受害者是否命中宠物标记（a/b 只看目标本身；c 需攻击女仆的主人比对）
     *  v1.1.0 实测二百四十五：名字判定双保险——getDisplayName（getDisplayName，含自定义名）
     *  之外再直接查 getCustomName（getCustomName）与 getName（getName），任一命中即标记；
     *  并放宽为"包含"匹配（旧版只查显示名，若显示名被队伍/其他模组改写会漏判）。 */
    public static boolean isPetMarked(EntityMaid maid, LivingEntity target) {
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_PET_IMMUNITY.get()) {
            return false; // v1.3.2 实测八百五十九：关 = 回原版（她照常攻击宠物）
        }
        if (target == null) {
            return false;
        }
        try {
            var name = target.getDisplayName();
            if (name != null) {
                String s = name.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
        try {
            var custom = target.getCustomName();
            if (custom != null) {
                String s = custom.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
        try {
            var plain = target.getName();
            if (plain != null) {
                String s = plain.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
        try {
            // 实测一千〇一十五：其他玩家名下的宠物 / 召唤物 / 女仆（OwnableEntity）——
            // 按"主人玩家"同一口径：自己或同队队友的单位一律免伤；PvP 关闭时任何玩家
            // 名下的单位都免伤；PvP 开启且不同队 → 保持原版可交战（否则 PvP 会变成
            // "只能挨打不能还手"）。UUID 非空即视为"有玩家主人"（主人离线也认）。
            if (target instanceof net.minecraft.world.entity.OwnableEntity own && maid != null) {
                if (own.getOwnerUUID() != null) {
                    LivingEntity petOwner = own.getOwner();
                    LivingEntity myOwner = maid.getOwner();
                    if (petOwner instanceof net.minecraft.world.entity.player.Player petPlayer
                            && myOwner instanceof net.minecraft.world.entity.player.Player op
                            && (op == petPlayer || op.isAlliedTo(petPlayer))) {
                        return true; // 自己 / 同队队友名下的单位
                    }
                    if (!FriendlyFireGuard.pvpAllowed(maid)) {
                        return true; // PvP 关：任何玩家名下的宠物/单位一律免伤
                    }
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("PetImmunityGuard", ignored);
        }
        return false;
    }
}
