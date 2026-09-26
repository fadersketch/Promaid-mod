package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 主人/友方免伤总闸——女仆绝不伤害主人与同主女仆（三层防护）。
 *
 * 根因（反馈：带女仆到雪地，空闲模式打雪仗后冲主人跳劈，脱甲约 4 心，主手武器越强越疼）：
 * TLM 原版空闲模式在雪地有打雪仗行为（MaidStartSnowballAttacking），该行为会把【主人】
 * 写进女仆 brain 的 ATTACK_TARGET（雪球目标）。promaid 的单兵战术行为
 * （MaidCombatTacticsBehavior，v1.5.202 起不再限定战斗任务）读到 ATTACK_TARGET 就接管
 * 走位 + 跳劈，对主人打出真实近战暴击伤害。
 *
 * 三层防护：
 *  ① 事件总闸（最终保险）：女仆造成的主人/友方伤害，在攻击事件/受伤事件/结算事件三处直接取消；
 *  ② 注入点过滤：战术 isActive/跳劈/近战横扫、中立威胁反击、自保反击（含弹幕/射箭）、
 *     LLM 攻击工具——目标为主人/友方时一律不执行；
 *  ③ 定期清除仇恨：每 2 秒把主人/友方从女仆的 ATTACK_TARGET、实体目标、复仇目标里清掉。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class FriendlyFireGuard {
    /** 仇恨清除扫描节流（tick；40 = 2 秒） */
    private static int scanCounter = 0;
    /** 仇恨清除日志限频（10 秒/女仆） */
    private static final java.util.Map<java.util.UUID, Long> HATE_LOG = new java.util.HashMap<>();

    private FriendlyFireGuard() {
    }

    /** ① 最终保险（攻击事件，受伤链最上游，任何模组取消之前必经） */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        EntityMaid maid = maidOfDamage(event.getSource());
        if (maid != null && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ①b 受伤事件 */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        EntityMaid maid = maidOfDamage(event.getSource());
        if (maid != null && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ①c 结算层兜底（部分模组自定义管线只走到这层） */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        EntityMaid maid = maidOfDamage(event.getSource());
        if (maid != null && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /**
     * v1.2.2 实测六百一十：这一记伤害**算谁头上**——主人/友军免伤的唯一入口。
     *
     * 三级归因（越靠前越可靠）：
     * <ol>
     *   <li>**造成者**是女仆（{@code getEntity}）：近战 / 横扫 / 法术 / 我们自己那几类炸弹都走这条；</li>
     *   <li>**直接实体**是女仆：弹射物的直接实体是她本人时的那一档（既有行为）；</li>
     *   <li>**直接实体是她放的 TNT**（{@link MaidTntBlastGuard#maidOfTnt}：引信 TNT 记的点火者 →
     *       我们亲手登记过的那一枚）。</li>
     * </ol>
     *
     * ── 为什么补第 ③ 条 ──
     * 六百〇七 收走模组 TNT 的"破不破方块"时留了一句"那一炸的伤害源是那枚 TNT 实体、不是女仆，
     * 所以主人/友军免伤覆盖不到它"。实测六百一十 把这句话验了：对**等价交换的爆破新星**而言，
     * 伤害源的**造成者其实是她**（引信 TNT 记了点火者 {@code PrimedTnt.getOwner()}，原版
     * {@code DamageSources.explosion} 就是拿它当造成者——javap 实证），所以主人/友军本来就被
     * 这条出口护住了（实机日志逐条对上：同队女仆在她连炸十几发新星期间一格血没掉，没入队的
     * 对照女仆被炸到 69 血）。
     * 但那句话指出的**边界是真的**：模组的爆炸未必把点火者写进伤害源（六百〇七 给地形那半边
     * 留的"我们亲手登记过的那一枚"兜底就是为这种模组准备的）。那种情况下造成者是空的、
     * 谁也认不出这一炸，第 ③ 条按登记表把账算回她头上——这是"她扔的模组 TNT 不伤主人/友军"
     * 这个承诺在地形之外的另一半。
     *
     * 【让位】「轰炸伤到主人/友军」开着时（{@code hurtFriendly=true}）第 ③ 条整段让位：那是
     * 玩家明确要的原版口径（主人/友军照掉血照被炸飞），我们不替他兜。
     */
    public static EntityMaid maidOfDamage(net.minecraft.world.damagesource.DamageSource source) {
        try {
            if (source == null) {
                return null;
            }
            Entity cause = source.m_7639_();   // getEntity（造成者）
            if (cause instanceof EntityMaid m) {
                return m;
            }
            Entity direct = source.m_7640_();  // getDirectEntity（直接实体：箭矢 / 那一枚 TNT）
            if (direct instanceof EntityMaid m) {
                return m;
            }
            if (com.maidsmart.config.MaidSmartConfig.COMBAT_BOMBING_HURT_FRIENDLY.get()) {
                return null; // 玩家要原版口径 → 这一层让位
            }
            return MaidTntBlastGuard.maidOfTnt(direct != null ? direct : cause);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ==================== ③ 清除仇恨 ==================== */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (++scanCounter < 40) {
            return;
        }
        scanCounter = 0;
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }
            for (ServerLevel lvl : server.m_129785_()) {
                // 实测五百六十四（PR #9 移植）：全世界 AABB → getAllEntities()——
                // Sable 会拒查超大 AABB 并静默返回空
                for (Entity e : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
                    if (!(e instanceof EntityMaid maid) || !maid.m_6084_()) {
                        continue;
                    }
                    boolean cleared = false;
                    LivingEntity at = maid.m_6274_()
                            .m_21952_(MemoryModuleType.f_26372_).orElse(null);
                    if (at != null && isFriendly(maid, at)) {
                        maid.m_6274_().m_21936_(MemoryModuleType.f_26372_);
                        cleared = true;
                    }
                    if (isFriendly(maid, maid.m_5448_())) {
                        maid.m_6710_(null);
                        cleared = true;
                    }
                    if (isFriendly(maid, maid.m_21188_())) {
                        maid.m_6703_(null);
                    }
                    if (cleared) {
                        long now = System.currentTimeMillis();
                        Long last = HATE_LOG.get(maid.m_20148_());
                        if (last == null || now - last > 10000L) {
                            HATE_LOG.put(maid.m_20148_(), now);
                            org.slf4j.LoggerFactory.getLogger("promaid").info(
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
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return false; // v1.2.2 实测六百：无主女仆的伤害关系一律按原版走，本模组不介入
        }
        if (target == null || target == maid) {
            return true;
        }
        LivingEntity owner = maid.m_269323_();
        if (owner != null && (target == owner || owner.m_20148_().equals(target.m_20148_()))) {
            return true;
        }
        if (target instanceof EntityMaid other) {
            LivingEntity otherOwner = other.m_269323_();
            if (owner != null && otherOwner != null
                    && owner.m_20148_().equals(otherOwner.m_20148_())) {
                return true;
            }
        }
        try {
            return maid.m_7307_(target);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
