package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 主人/友方免伤总闸——女仆绝不伤害主人与同主女仆。
 *
 * 根因（粉丝反馈：带女仆到雪地，空闲模式打雪仗后冲主人跳劈，脱甲约 4 心，主手武器越强越疼）：
 * TLM 原版空闲模式在雪地有打雪仗行为（MaidStartSnowballAttacking），该行为会把【主人】
 * 写进女仆 brain 的 ATTACK_TARGET（雪球目标）。promaid 的单兵战术行为
 * （MaidCombatTacticsBehavior，v1.5.202 起不再限定战斗任务）读到 ATTACK_TARGET 就接管
 * 走位 + 跳劈，对主人打出真实近战暴击伤害。旧版只拦了"雪球 0 伤害触发参战"这一层
 * （AutoCombatSwitch），拦不住 TLM 自己写攻击目标这条路径。
 *
 * 双层防护：
 *  ① 本类事件总闸（最终保险）：女仆造成的伤害，受害者为主人/同主友方 → 直接取消；
 *  ② 各注入点过滤：战术行为 isActive/跳劈/近战反击、中立威胁反击、自保近战反击、
 *     LLM 攻击工具——目标为主人/友方时一律不执行（连动画都不出）。
 */
@Mod.EventBusSubscriber(modid = "promaid")
public final class FriendlyFireGuard {

    private FriendlyFireGuard() {
    }

    /** 最终保险①：受伤链最上游（hurt() 开头）——女仆 → 主人/友方 直接取消 */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity src = event.getSource() == null ? null : event.getSource().m_7639_();
        if (src instanceof EntityMaid maid && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** 最终保险②：结算层兜底（部分模组自定义管线只走到这层） */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        Entity src = event.getSource() == null ? null : event.getSource().m_7639_();
        if (src instanceof EntityMaid maid && isFriendly(maid, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** 女仆是否不该伤害该目标：自身 / 主人 / 同主人女仆 / 友军（同队等） */
    public static boolean isFriendly(EntityMaid maid, Entity target) {
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
