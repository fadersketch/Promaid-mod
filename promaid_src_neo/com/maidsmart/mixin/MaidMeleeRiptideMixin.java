package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidMeleeAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 【持激流三叉戟时，她那一记普通挥砍换成旋转冲击】（在攻击/近战空袭中生效，**替换原本的攻击环节**）。
 * 【为什么注这里（javap 实证）】TLM 攻击模式近战 = {@code MaidMeleeAttack.create(cooldown)} 返回的 OneShot，
 * 任务体是 {@code lambda$create$0}（javap -p 实证，两版同名同签名）：出手条件是"不拿远程武器 && 近战可达 &&
 * 目标可见"，动作是摆挥砍姿势后调 {@code doHurtTarget}（= 这一记"结算伤害"）。直接把这一条**换成起手旋转
 * 冲击**：起手成功就返回 true（这一记算打出去了，TLM 照常吃攻击冷却、维持节奏）；起不了手（不在攻击模式 / 主手不是激流三叉戟 / 在空中 / 硬直中 / 目标太远）就**原样调回** {@code doHurtTarget}。
 * 【为什么从"掐判据"改成"换这一记"】早先是掐"近战可达"让整段不执行、本模组自己按距离自主起手——代价是她会在
 * 空袭的起跳/俯冲途中横插一次突进（"突然不会起飞了"），玩家也看不懂触发时机。现在起手时机 = **她的攻击时机**，
 * 且**绝不吞掉任何一记**。
 * 【必须是 static】注入目标 {@code lambda$create$0} 是 **private static**，而 Mixin 规则是"**静态目标只接受
 * 静态回调**"：写成实例方法会在 {@code MaidMeleeAttack} **首次被加载**时抛
 * {@code InvalidInjectionException}，整个类变形失败——表现是**女仆 AI 直接死掉／一动不动**。注意
 * {@code require = 0} 救不了这种情况（它只容忍"没匹配到"，不容忍"回调签名非法"）。
 * 【映射】本树官方名 {@code isWithinMeleeAttackRange}/{@code swing}/{@code doHurtTarget}。
 */
@Mixin(MaidMeleeAttack.class)
public abstract class MaidMeleeRiptideMixin {

    /**
     * 拦截那一记"结算伤害"：持激流三叉戟且能起手时换成旋转冲击，否则原样调回。
     *
     * <p>{@code target} 的类型按字节码是 `Entity`（`doHurtTarget(Entity)Z`），只在是
     * {@code LivingEntity} 时才谈得上突进。
     */
    @Redirect(method = "lambda$create$0",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 0)
    private static boolean promaid$riptideInsteadOfSwing(EntityMaid maid, Entity target) {
        if (target instanceof LivingEntity living
                && com.maidsmart.combat.MaidTridentSpinBehavior.replacesMelee(maid)
                && maid.level() instanceof ServerLevel level
                && com.maidsmart.combat.MaidTridentSpinBehavior.tryStartDash(level, maid, living)) {
            return true; // 这一记换成旋转冲击了：算出手成功，冷却照 TLM 原有逻辑走
        }
        return maid.doHurtTarget(target);
    }
}
