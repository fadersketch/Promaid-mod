package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * <p>【为什么是这里】javap 实证：TLM 的 {@code MaidMoveControl.tick()} 在 MOVE_TO / STRAFE
 * 分支里算出 {@code speedModifier × MOVEMENT_SPEED} 后调的正是 {@code Mob.setSpeed}，而
 * {@code Mob.setSpeed} 只有两行——写 {@code LivingEntity} 的 speed 字段 + 写 zza；紧接着
 * {@code travel} 就用这两个值算这一 tick 的位移。<b>所以"靠腿走"的速度无一例外经过这里</b>：
 * 导航走路 / 直连 moveTo / 闲逛 WALK_TARGET / 农活 / 跟随 / 挖矿赶路全在里面。
 * （直接 {@code setDeltaMovement} 施加速度矢量的那几处——搭路推一步、自保逃跑、飞行/扫帚——
 * 不走这个口子，本项也没打算管：那些不是"走路"，由 {@link com.maidsmart.task.MaidNonCombatSpeedCap}
 * 的让位判据另行排除。）
 * <p>用 {@code @ModifyVariable(at = HEAD)} 改<b>入参本身</b>：改的是"送进去的那个数"，
 * 于是后续写字段 / 写 zza / travel 全部自动一致，不会出现"字段压了、zza 没压"的半吊子状态。
 * <p>【目标类为什么不写 EntityMaid】{@code setSpeed} 是原版 {@code Mob} 声明的方法，
 * EntityMaid 及其祖先链（TamableAnimal/Animal/AgeableMob/PathfinderMob）<b>都没有覆写</b>
 * （javap 逐个查过），运行时真正执行的就是 {@code Mob.setSpeed}——挂 {@code Mob} 才能拦住 全部调用，挂 EntityMaid 会注入失败（方法不在该类里）。 <p>【本树是 neo / 1.21.1】方法名写官方名 {@code setSpeed}（Mojmap 环境直接可用）； forge 树同名类写 SRG {@code m_7910_}。逻辑一字不差，全在 {@link com.maidsmart.task.MaidNonCombatSpeedCap#clamp} 里。
 */
@Mixin(Mob.class)
public abstract class MaidWalkSpeedCapMixin {

    @ModifyVariable(method = "setSpeed(F)V", at = @At("HEAD"), argsOnly = true)
    private float maidsmart$capWalkSpeed(float speed) {
        if (!((Object) this instanceof EntityMaid maid)) {
            return speed; // 别的生物一律不动
        }
        return com.maidsmart.task.MaidNonCombatSpeedCap.clamp(maid, speed);
    }
}
