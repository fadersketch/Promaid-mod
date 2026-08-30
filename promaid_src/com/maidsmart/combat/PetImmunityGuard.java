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
    private PetImmunityGuard() {
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
            }
        } catch (Throwable ignored) {
        }
    }

    /** 目标/受害者是否命中宠物标记（a/b 只看目标本身；c 需攻击女仆的主人比对） */
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
