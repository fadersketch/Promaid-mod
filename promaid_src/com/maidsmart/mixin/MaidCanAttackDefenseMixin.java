package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【护主/自卫的类型闸补丁】正在打我们这一边的生物，不管它是什么类型类，都算敌人。
 * <h2>
 * 「ALEX 的生物（Alex's Mobs）攻击主人时，不会触发女仆的护主与攻击行为。」
 * <h2>根因（javap / CFR 实证，两树同构）</h2>
 * TLM 的索敌链一共两道类型闸，最终都汇到 {@code EntityMaid.canAttack}：
 * <ul>
 *   <li>选目标：{@code IAttackTask.findFirstValidAttackTarget} →
 *       {@code NEAREST_VISIBLE_LIVING_ENTITIES.findClosest(e -> maid.canAttack(e) && ...)}；</li>
 *   <li>挥砍：{@code MaidMeleeAttack} 要求 {@code nearestVisibleLivingEntities.contains(target)}，
 *       而这张"看得见的活物"表由 {@code Sensor.isEntityAttackable} →
 *       {@code TargetingConditions.forCombat().test()} → {@code mob.canAttack(target)}
 *       逐条过滤。</li>
 * </ul> 也就是说：<b>只要 {@code canAttack} 为 false，女仆既选不到它、也砍不到它。</b> <p>而 {@code canAttack} 在她是攻击任务时走 {@code IAttackTask.canAttack} → {@code DefaultMonsterType}，后者只认三档： <ul> <li>{@code Enemy} → HOSTILE（能打）；</li> <li>{@code TamableAnimal} / {@code Npc} → FRIENDLY（<b>无条件 false</b>）；</li> <li>其余 → NEUTRAL（只有它出现在"最近打过谁"的旧账字段里才放行）。</li> </ul> ALEX 的生物<b>没有一个是 {@code Enemy}</b>（javap 实证 alexsmobs-1.22.9）： 虎 / 犀牛 extends {@code Animal}（落 NEUTRAL）、灰熊 / 鳄鱼 / 大猩猩 / 大象 extends {@code TamableAnimal}（落 FRIENDLY，直接 false）。它们扑上来咬主人时，女仆的索敌闸一律判 "不是敌人"——切了战斗任务也选不到目标，正是玩家看到的"一点反应都没"。这与此前 {@code NeutralThreatDriver} 修"发狂的狼"是同一类结构性盲区（狼落 NEUTRAL，ALEX 生物还多一层 FRIENDLY）。 <h2>修法：给类型闸补一条【行为化】例外</h2> 在 {@code canAttack} 的返回处挂一道：原值判 false 时，若 {@link com.maidsmart.combat.LiveThreat#attackingUs} 说"它正在打我们这一边" （它此刻锁着她/主人，或它刚打过她/主人），就改判 true。 <p><b>不看类型</b>是这条例外的全部意义（正是给"类型判不出威胁"的模组生物用的）； 安全性由两道既有闸兜底：① {@code FriendlyFireGuard.isFriendly}（主人 / 同主女仆 / 友军 一律不碰）；② 只在"它真的在攻击我们"的 5 秒窗口内成立——平静的生物照样打不着。 <p><b>为什么补在 {@code canAttack} 这一处</b>：它是上述两条链路的<b>唯一汇合点</b>—— 一处出口改完，选目标、挥砍、横扫（{@code doSweepHurt} 对每个受害者同样过它）全线一致， 不必在四五处各打一个补丁。 <p><b>为什么不会无限递归</b>：{@code attackingUs} 只用行为信号 （{@code getTarget} / {@code getLastHurtByMob}），<b>刻意不回调 {@code canAttack}</b>。
 */
@Mixin(EntityMaid.class)
public abstract class MaidCanAttackDefenseMixin {

    @Inject(method = "m_6779_", at = @At("RETURN"), cancellable = true)
    private void maidsmart$attackThoseAttackingUs(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return; // 类型闸本来就放行 → 不动
        }
        if (!com.maidsmart.config.MaidSmartConfig.COMBAT_ATTACK_THOSE_ATTACKING_US.get()) {
            // 实测八百四十：本处原来没有配置项、只能硬问 isDiy；现在是真开关（默认开）。
            // 关掉 = TLM 原版类型闸（类型判不出威胁的生物照样打不着）；
            // DIY 模式由预设把这条开关置 false，所以 DIY 下同样不介入。
            return;
        }
        if (com.maidsmart.combat.LiveThreat.attackingUs((EntityMaid) (Object) this, target)) {
            cir.setReturnValue(true); // 正在打我们这一边 → 类型闸让位
        }
    }
}
