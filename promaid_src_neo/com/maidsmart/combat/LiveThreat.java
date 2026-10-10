package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 【实测七百五十七】"这一拍它还算是敌人吗"——**带时效**的交战判据（自动索敌 / 载具开火共用）。
 *
 * <h2>为什么必须有它（玩家实机 + 日志实证）</h2>
 *
 * 日志（2026-10-02 同一局）里是同一件事反复出现：
 * <pre>
 * [模组坐骑·炮位] 卓越前线载具(WHEEL) 炮塔目标=horse
 * [模组坐骑·开火] 卓越前线载具(WHEEL) 直连开火 → horse
 * </pre>
 * 车上的女仆把**场上的马**当成敌人、对着它开炮。
 *
 * <h2>根因（TLM 的口径 + 原版字段语义）</h2>
 * 合法性的类型闸走的是 TLM 的 {@code DefaultMonsterType.canAttack}：
 * <ul>
 *   <li>{@code Enemy} → 能打；</li>
 *   <li>{@code TamableAnimal} / {@code Npc} → 友方，不打；</li>
 *   <li><b>其余一律 NEUTRAL</b>，而中立能不能打只看"它是不是
 *       {@code getLastHurtByMob()} / {@code getLastHurtMob()} 里的那一个"。</li>
 * </ul>
 * 关键在**马不是 {@code TamableAnimal}**（{@code AbstractHorse} 继承 {@code Animal}），
 * 所以马落在 NEUTRAL；而原版那两个"最近打过谁"的字段**一旦写上就不再清空**——主人很久
 * 以前随手打过一下那匹马，它就**永远**过 canAttack。平时没人拿 canAttack 大范围扫（TLM
 * 自己的索敌半径很小），但**本模组的 50 格自动索敌**（{@link FlightTargeting}：扫帚 /
 * 飞行载具 / 地面载具绕圈那一整套）把它当唯一类型闸，于是"很久以前被打过一次的马"成了
 * 永远合法的猎物。
 *
 * <h2>本模组其实早有这条口径</h2>
 * {@code NeutralThreatDriver.ownerEnemy} 早就把同一组字段**加了 100 tick（5 秒）时间窗**
 * （见那里类内注释 ①：「他们读主人的 getLastHurtByMob/getLastHurtMob 不看时间……我们一并
 * 查时间戳」）。本类是那条口径的**公共件**：自动索敌与载具开火共用一份，不许各写一份。
 */
@EventBusSubscriber(modid = "promaid")
public final class LiveThreat {
    private LiveThreat() {
    }

    /** "最近交手"的窗口（tick）：与 {@code NeutralThreatDriver.OWNER_COMBAT_TICKS} 同口径（5 秒）。 */
    public static final int WINDOW = 100;

    /**
     * 这一拍它算不算**活敌人**：仍然过 TLM 的类型/友军口径，且满足下面任意一条——
     * <ol>
     *   <li>真敌对（{@code Enemy}）；</li>
     *   <li>刚刚真的交过手（5 秒内：她打过它 / 它打过她 / 主人打过它 / 它打过主人）；</li>
     *   <li>它此刻正锁着我们这边（她或主人）——模组 boss 不一定实现 {@code Enemy}，
     *       这一条保证"正在打的仗"不会被误判成"陈年旧账"。</li>
     * </ol>
     * 三条都不满足 = 只剩"很久以前那点旧账" → 不当敌人。
     */
    public static boolean live(EntityMaid maid, LivingEntity e) {
        try {
            if (maid == null || e == null || !e.isAlive()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, e)) {
                return false;
            }
            if (!maid.canAttack(e)) {
                return false; // 类型口径（玩家 / 盔甲架 / 宠物 / 村民…）一个都不放宽
            }
            if (e instanceof Enemy) {
                return true;
            }
            return recent(maid, e) || targetsUs(maid, e);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 5 秒内真的交过手（四个方向都查，时间戳全部取**各实体自己的**）。 */
    public static boolean recent(EntityMaid maid, LivingEntity e) {
        try {
            if (hurtFresh(e, maid)) {
                return true; // 她打过它
            }
            if (hurtFresh(maid, e)) {
                return true; // 它打过她
            }
            LivingEntity owner = maid.getOwner();
            if (owner != null) {
                if (hurtFresh(e, owner)) {
                    return true; // 主人打过它
                }
                if (hurtFresh(owner, e)) {
                    return true; // 它打过主人
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code victim} 的"最近被谁打"是不是 {@code attacker}，且在窗口内，且这一记**真的见了血**。 */
    private static boolean hurtFresh(LivingEntity victim, LivingEntity attacker) {
        try {
            if (victim.getLastHurtByMob() != attacker) {
                return false;
            }
            if (victim.tickCount - victim.getLastHurtByMobTimestamp() >= WINDOW) {
                return false;
            }
            // 实测八百一十九（）：
            // 原版 hurt() 里 setLastHurtByMob **写在伤害值判断之前**——0 伤害的命中（雪球 /
            // 鸡蛋这类娱乐弹射物）照样把他写进"最近打我的人"。只看旧账字段的话，
            // "A 朝我们扔了个雪球" 会被读成 "A 在打我们"。这里再要一条正伤害台账记录兜住。
            return positiveHitFresh(victim, attacker);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================== 「伤害 > 0」台账（实测八百一十九） ====================

    /** 台账容量上限（超出即清过期项；纯防御性，正常一局远达不到）。 */
    private static final int LEDGER_MAX = 8192;

    /**
     * 正伤害台账：受害者 UUID → (攻击者 UUID, 记录刻)。**只写 {@code amount > 0} 的命中。**
     *
     * <h3>为什么必须有它（
     * 「判断依据里面还要再加一个伤害大于 0，防止误伤。」
     *
     * <h3>根因（原版 {@code hurt()} 实证，两树同构）</h3>
     * {@code LivingEntity.hurt()} 里 {@code setLastHurtByMob(source.getEntity())} 写在
     * **任何伤害值判断之前**：0 伤害的命中（雪球 / 鸡蛋 / 娱乐性弹射物）照样把"最近打我的人"
     * 写成投掷者。于是只看那两个旧账字段的 {@link #hurtFresh} 会把"A 朝我们扔了个雪球"
     * 读成"A 在打我们"→ 女仆拔刀还击。本模组早就吃过这口亏：{@code AutoCombatSwitch}
     * 三处监听器都有 {@code event.getAmount() <= 0.0f → return}（实测三百二十，
     * 雪地打雪仗后女仆冲主人跳劈）。这里是同一条口径挪到索敌侧。
     *
     * <h3>怎么补（Neo 的 {@code LivingIncomingDamageEvent} 一事件即覆盖整条链）</h3>
     * Neo 把 {@code LivingAttackEvent} / {@code LivingHurtEvent}（Forge 的）统一成
     * {@code LivingIncomingDamageEvent}，一个监听器就能拿到"是否被这次伤害命中"和伤害值；
     * {@code amount > 0} 才记一条。0 伤害的雪球 / 鸡蛋因此一条都进不来。
     *
     * <p>【范围】**只收紧"它打我们/我们打它"这类伤害往来判据**（{@link #hurtFresh}），
     * 也就是 {@link #attackingUs} 与 {@link #recent} 共用的那个"真的交过手"。这不是放宽、
     * 是收紧：0 伤害的挑逗不再算交手。{@link #targetsUs}（它此刻锁着谁）不属"伤害"，
     * 不受影响。
     */
    private static final java.util.Map<java.util.UUID, long[]> POSITIVE_HITS = new java.util.HashMap<>();

    /** 记一条正伤害（{@code victim} 被 {@code aggressor} 打掉血）。 */
    private static void recordPositiveHit(LivingEntity victim, LivingEntity aggressor) {
        try {
            if (victim == null || aggressor == null || victim == aggressor) {
                return;
            }
            java.util.UUID vid = victim.getUUID();
            java.util.UUID aid = aggressor.getUUID();
            if (vid == null || aid == null) {
                return;
            }
            // 与 hurtFresh 读的旧账时间戳同源（tickCount）——同一台钟，免得两个时间基准
            // 在跨维度/重登时对不上。
            long now = victim.tickCount;
            if (POSITIVE_HITS.size() > LEDGER_MAX) {
                POSITIVE_HITS.values().removeIf(v -> now - v[2] >= WINDOW);
            }
            POSITIVE_HITS.put(vid, new long[]{aid.getMostSignificantBits(), aid.getLeastSignificantBits(), now});
        } catch (Throwable ignored) {
        }
    }

    /** 台账里是否存在"{@code attacker} 在窗口内对 {@code victim} 造成过正伤害"的记录。 */
    private static boolean positiveHitFresh(LivingEntity victim, LivingEntity attacker) {
        try {
            long[] rec = POSITIVE_HITS.get(victim.getUUID());
            if (rec == null) {
                return false;
            }
            java.util.UUID aid = attacker.getUUID();
            if (aid == null || rec[0] != aid.getMostSignificantBits() || rec[1] != aid.getLeastSignificantBits()) {
                return false;
            }
            return victim.tickCount - rec[2] < WINDOW;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Neo：受伤链的"来伤害了"统一事件（{@code amount > 0} 才记）。 */
    @SubscribeEvent
    public static void onPositiveHit(LivingIncomingDamageEvent event) {
        if (event != null && event.getAmount() > 0.0f) {
            recordPositiveHit(event.getEntity(), causeOf(event.getSource()));
        }
    }

    /** 这一记伤害**算谁头上**（{@code getEntity} 造成者；没有则退回直接实体，如箭矢）。 */
    private static LivingEntity causeOf(DamageSource source) {
        try {
            if (source == null) {
                return null;
            }
            Entity e = source.getEntity();
            if (e instanceof LivingEntity le) {
                return le;
            }
            Entity d = source.getDirectEntity();
            return d instanceof LivingEntity le ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 【护主/自卫扩宽】这只生物此刻是不是**正在打我们这一边**——不看类型，只看行为。
     *
     * <h2>为什么必须有它（
     * 「ALEX 的生物（Alex's Mobs）攻击主人时，不会触发女仆的护主与攻击行为」。
     *
     * <h2>根因（javap 实证，两树同构）</h2>
     * TLM 的索敌链有两道类型闸，全部汇到 {@code EntityMaid.canAttack}：
     * {@code IAttackTask.findFirstValidAttackTarget}→{@code maid.canAttack}→
     * {@code IAttackTask.canAttack}→{@code DefaultMonsterType}。后者只认三档：
     * {@code Enemy}→HOSTILE、{@code TamableAnimal}/{@code Npc}→FRIENDLY（**直接 false**）、
     * 其余→NEUTRAL（只有它出现在"最近打过谁"的旧账字段里才放行）。ALEX 的生物**没有一个是
     * {@code Enemy}**：虎/犀牛 extends {@code Animal}（NEUTRAL）、灰熊/鳄鱼/大猩猩/大象 extends
     * {@code TamableAnimal}（FRIENDLY）——于是它们扑上来咬主人时，女仆的索敌闸一律判"不是敌人"，
     * 切了战斗任务也选不到目标（"一点反应都没"）。这与
     * {@link com.maidsmart.combat.NeutralThreatDriver} 当初修"发狂的狼"是同一类结构性盲区。
     *
     * <h2>与 {@link #live} 的关系（绝不可混用）</h2>
     * {@code live()} <b>先过类型闸</b>（{@code maid.canAttack}），本方法<b>刻意不查类型</b>——
     * 正是给"类型闸判不出威胁"的模组生物用的。所以本方法<b>绝不能回调 {@code canAttack}</b>
     * （它自己被 {@link com.maidsmart.mixin.MaidCanAttackDefenseMixin} 挂在 {@code canAttack}
     * 的返回处，回调即无限递归）；只用行为信号：它锁着我们、或它刚打过我们。
     *
     * <p>判据（任一成立即"正在打我们"）：
     * <ol>
     *   <li>{@link #targetsUs}：它此刻的 {@code getTarget()} 正是她或主人；</li>
     *   <li>{@code hurtFresh(e, maid)}：它刚打过她（5 秒窗口，**且造成了正伤害**）；</li>
     *   <li>{@code hurtFresh(e, owner)}：它刚打过主人——"护主"最核心的那一条（同为正伤害口径）。</li>
     * </ol>
     * <b>刻意不含</b>"我们打过它"两个方向：那会顺带把"主人随手打了一下的牛羊"也放进来
     * ——本方法只负责"它打我们"这个防御方向。
     *
     * <p>【实测八百一十九·伤害 &gt; 0】上面第 ②③ 条走的是 {@code getLastHurtByMob} 旧账字段，
     * 而原版 {@code hurt()} 把这个字段**写在伤害值判断之前**——朝我们扔个雪球/鸡蛋（伤害恒 0）
     * 也会把他写成"最近打我的人"。所以两条 hurt 判据都再要一条**正伤害台账**记录兜底
     * （见 {@link #hurtFresh} / {@code POSITIVE_HITS}）：0 伤害的挑逗不再算"在打我们"。
     * 第 ① 条是"锁定"不是"伤害"，不受此限。
     */
    public static boolean attackingUs(EntityMaid maid, LivingEntity e) {
        try {
            if (maid == null || e == null || !e.isAlive()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, e)) {
                return false; // 主人 / 同主女仆 / 友军：无论它在干什么都不打（防误伤总闸）
            }
            if (!(e instanceof Mob)) {
                return false; // 本扩宽只针对 Mob（能 getTarget 的生物）
            }
            if (targetsUs(maid, e)) {
                return true; // 它此刻锁着她或主人
            }
            if (hurtFresh(e, maid)) {
                return true; // 它刚打过她
            }
            LivingEntity owner = maid.getOwner();
            return owner != null && hurtFresh(e, owner); // 它刚打过主人（护主）
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 它此刻正锁着她或主人。 */
    public static boolean targetsUs(EntityMaid maid, LivingEntity e) {
        try {
            if (!(e instanceof Mob mob)) {
                return false;
            }
            LivingEntity t = mob.getTarget();
            return t == maid || (maid.getOwner() != null && t == maid.getOwner());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
