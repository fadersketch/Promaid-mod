package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 宠物免疫总闸（v1.1.0 实测一百九十三，用户："如果都被打上了玩家宠物的字样，
 * 那么女仆便不会再对他造成伤害（包括 aoe，防误伤）并去除仇恨"）。
 *
 * 三层防线：
 * ① 目标选取拦截（PetImmunityTargetMixin → IAttackTask.canAttack HEAD）：宠物字样
 *    目标不进入攻击选取——仇恨不进；TLM TaskAttack 的 StopAttackingIfTargetInvalid
 *    同谓词自动清掉已持有的目标（去除仇恨）；
 * ② 本类 LivingHurtEvent 伤害总闸：凡伤害【造成者】(m_7640_)或【直接实体】
 *    (m_7639_)是女仆，受害者命中宠物标记 → 整次伤害取消——近战/横扫/箭矢/弹幕/
 *    枪械/爆炸溅射全覆盖（扫场溅到宠物也零伤害，防误伤）；
 * ③ 仇恨残留兜底：目标选取层已拦，被扫清除（②只拦伤害不改目标——万一 TLM 其他
 *    入口把宠物塞进攻击记忆，②保零伤害、①保下一 tick 清掉）。
 *
 * 宠物标记判定（isPetMarked）：
 *  a. 显示名包含「玩家宠物」「主人的宠物」（字样全覆盖——铁砧/命名牌取名即标记）
 *  b. 以「MaidNoAttack」开头（对齐 TLM 内置不攻击名，javap 实证其 canAttack 逻辑）
 *  c. 可驯服生物且持有者 = 攻击女仆的主人（主人的猫/狗/鹦鹉等——同主宠物）
 */
@Mod.EventBusSubscriber(modid = "promaid")
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
     * 清掉（去仇恨；TLM StopAttackingIfTargetInvalid 等行为自然接管后续）；
     * 伤害层由下方 LivingHurtEvent 总闸兜底（锁定瞬间也零伤害、零溅射）。
     * 与"锁定优先级"的语义差别：女仆可能先锁定一眼才被清掉（≤2 秒），
     * 但全程无伤害；可接受。
     */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (++scanCounter < 40) {
            return;
        }
        scanCounter = 0;
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            long now = server.m_129785_().iterator().next().m_46467_();
            net.minecraft.world.phys.AABB whole = new net.minecraft.world.phys.AABB(
                    -131072.0, -4096.0, -131072.0, 131072.0, 4096.0, 131072.0);
            for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
                for (EntityMaid maid : lvl.m_45976_(EntityMaid.class, whole)) {
                    if (!maid.m_6084_()) {
                        continue;
                    }
                    try {
                        var atk = maid.m_6274_().m_21952_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
                        if (atk.isEmpty()) {
                            continue;
                        }
                        LivingEntity target = atk.get();
                        if (target == null || !isPetMarked(maid, target)) {
                            continue;
                        }
                        maid.m_6274_().m_21936_(net.minecraft.world.entity.ai.memory.MemoryModuleType.f_26372_);
                        Long last = HATE_CLEAR_LOG.get(maid.m_20148_());
                        if (last == null || now - last > 1200L) {
                            HATE_CLEAR_LOG.put(maid.m_20148_(), now);
                            org.slf4j.LoggerFactory.getLogger("promaid").info(
                                    "maid pet-hate-clear: maid={} target={}（宠物标记，仇恨已清、伤害免疫）",
                                    com.maidsmart.tool.PromaidLog.nameOf(maid),
                                    target.m_5446_() != null ? target.m_5446_().getString() : target.m_20148_());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
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
            Entity cause = source.m_7640_();   // 造成者（射手本体/爆炸主）
            Entity direct = source.m_7639_();  // 直接实体（箭矢/弹射物）
            EntityMaid maid = null;
            if (cause instanceof EntityMaid m) {
                maid = m;
            } else if (direct instanceof EntityMaid m2) {
                maid = m2;
            }
            if (maid == null) {
                return; // 不是女仆造成的伤害——不拦（玩家/怪打宠物照常）
            }
            if (isPetMarked(maid, victim)) {
                event.setCanceled(true); // 女仆链上的任何伤害打到宠物 → 免疫
                return;
            }
            // v1.1.0 实测二百四十五（用户："哪怕给一个铁傀儡命名为玩家宠物，女仆仍然
            // 可以伤到铁傀儡"）：判定未命中诊断——受害者名字含"宠物"字样但 isPetMarked
            // 返回 false，记录名字原文（m_5446_ 显示名 / m_7770_ 自定义名 / m_7755_ 名）
            // 到 promaid.log，60 秒限频/女仆，定位"名字判定为什么不命中"。
            try {
                var vn = victim.m_5446_();
                if (vn != null && vn.getString().contains("宠物")) {
                    long now = victim.m_9236_().m_46467_();
                    Long last = PET_MISS_LOG.get(maid.m_20148_());
                    if (last == null || now - last > 1200L) {
                        PET_MISS_LOG.put(maid.m_20148_(), now);
                        String custom = victim.m_7770_() != null ? victim.m_7770_().getString() : "null";
                        String plain = victim.m_7755_() != null ? victim.m_7755_().getString() : "null";
                        org.slf4j.LoggerFactory.getLogger("promaid").info(
                                "maid pet-miss: maid={} victim={} display=[{}] custom=[{}] name=[{}]",
                                com.maidsmart.tool.PromaidLog.nameOf(maid),
                                victim.m_20148_(), vn.getString(), custom, plain);
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 目标/受害者是否命中宠物标记（a/b 只看目标本身；c 需攻击女仆的主人比对）
     *  v1.1.0 实测二百四十五：名字判定双保险——m_5446_（getDisplayName，含自定义名）
     *  之外再直接查 m_7770_（getCustomName）与 m_7755_（getName），任一命中即标记；
     *  并放宽为"包含"匹配（旧版只查显示名，若显示名被队伍/其他模组改写会漏判）。 */
    public static boolean isPetMarked(EntityMaid maid, LivingEntity target) {
        if (target == null) {
            return false;
        }
        try {
            var name = target.m_5446_();
            if (name != null) {
                String s = name.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            var custom = target.m_7770_();
            if (custom != null) {
                String s = custom.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            var plain = target.m_7755_();
            if (plain != null) {
                String s = plain.getString();
                if (s.contains("玩家宠物") || s.contains("主人的宠物")
                        || s.startsWith("MaidNoAttack")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (target instanceof TamableAnimal t) {
                var ownerId = t.m_21805_();
                if (ownerId != null && maid != null && maid.m_269323_() != null
                        && ownerId.equals(maid.m_269323_().m_20148_())) {
                    return true; // 可驯服且持有者 = 女仆的主人（主人的宠物）
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
