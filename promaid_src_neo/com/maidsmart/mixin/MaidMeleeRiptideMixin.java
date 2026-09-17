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
 * v1.2.0 实测五百三十八 起【持激流三叉戟时，她那一记普通挥砍换成旋转冲击】。
 *
 * ── 需求原文 ──
 * "拿到激流三叉戟的时候需要单独写一条链路：攻击为朝向敌人发动一次旋转冲击然后结算伤害。
 * （在攻击/近战空袭中生效，**替换原本的攻击环节**），其他的如索敌等不变。"
 *
 * ── 为什么注这里（javap 实证 1.21.1）──
 * TLM 的攻击模式近战 = `MaidMeleeAttack.create(cooldown)` 返回的 OneShot，其任务体是
 * `lambda$create$0`（javap -p 实证，两版同名同签名），里面的出手条件与动作是：
 * <pre>
 *   if (!isHoldingUsableProjectileWeapon(maid)      // 拿着远程武器就不近战
 *       &amp;&amp; maid.isWithinMeleeAttackRange(target)    // 近战可达
 *       &amp;&amp; nearestVisibleLivingEntities.contains(target)) {
 *       lookTarget.set(...);
 *       maid.swing(MAIN_HAND);          // 挥砍动作
 *       maid.doHurtTarget(target);      // ← 这一记"结算伤害"
 *       attackCoolingDown.setWithExpiry(true, cooldown / 攻速);
 *   }
 * </pre>
 * 直接把"结算伤害"这一条**换成起手旋转冲击**：起手成功就返回 true（＝这一记算打出去了，
 * TLM 照常吃攻击冷却、照常维持它的节奏）；起不了手（不在攻击模式 / 主手不是激流三叉戟 /
 * 在空中 / 硬直中 / 目标太远）就**原样调回** `doHurtTarget`，与没装本模组完全一样。
 *
 * ── 实测五百四十一 为什么从"掐判据"改成"换这一记" ──
 * 早先版本是掐 `isWithinMeleeAttackRange`（近战可达）让整段不执行，加上本模组自己按距离自主
 * 起手 —— 实机代价：她会在空袭的起跳/俯冲途中横插一次突进（"突然不会起飞了"），而且玩家完全
 * 看不懂她什么时候会触发（"判定很奇怪，容易被别的行为覆盖，可能是条件过于苛刻"）。
 * 现在起手时机 = **她的攻击时机**，且**绝不吞掉任何一记**：替换失败就落回普通挥砍。
 *
 * ── 必须是 static（实测五百四十 的血债）──
 * 注入目标 `lambda$create$0` 是 **private static** 方法，而 Mixin 的规则是"**静态目标方法只
 * 接受静态回调**"：写成实例方法会在 `MaidMeleeAttack` **首次被加载**（＝女仆生成、构建大脑）
 * 时抛 `InvalidInjectionException: non-static callback method … targets a static method which
 * is not supported`，整个 `MaidMeleeAttack` 变形失败 —— 表现就是**女仆 AI 直接死掉／一动不动，
 * 收回魂符再放下来会消失**。注意 `require = 0` 救不了这种情况：它只容忍"没匹配到"，
 * 不容忍"回调签名非法"。静态回调仍然照常收下 receiver 作为第一个参数。
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
