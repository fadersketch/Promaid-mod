package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * 【实测七百五十七】"这一拍它还算是敌人吗"——**带时效**的交战判据（自动索敌 / 载具开火共用）。
 *
 * <h2>为什么必须有它（玩家实机 + 日志实证）</h2>
 * 玩家原话：「我发现女仆在车上索敌似乎出现了问题……似乎什么都被他们当成了敌人。」
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
            if (maid == null || e == null || !e.m_6084_()) {
                return false;
            }
            if (FriendlyFireGuard.isFriendly(maid, e)) {
                return false;
            }
            if (!maid.m_6779_(e)) {
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
            LivingEntity owner = maid.m_269323_();
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

    /** {@code victim} 的"最近被谁打"是不是 {@code attacker}，且在窗口内。 */
    private static boolean hurtFresh(LivingEntity victim, LivingEntity attacker) {
        try {
            return victim.m_21188_() == attacker
                    && victim.f_19797_ - victim.m_21213_() < WINDOW;
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
            LivingEntity t = mob.m_5448_();
            return t == maid || (maid.m_269323_() != null && t == maid.m_269323_());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
