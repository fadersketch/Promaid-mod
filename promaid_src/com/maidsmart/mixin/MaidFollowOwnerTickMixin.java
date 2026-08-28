package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFollowOwnerTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.1.0 实测一百五十一（参考改版 TLM jar "touhoulittlemaid-1.5.3-modified-all.jar"）：
 * 跟随每 tick 重断言——平常跟随在 4 格以内、不再乱跑。
 *
 * 【实证】改版 jar 与官方 1.5.3 的 MaidFollowOwnerTask 距离逻辑（start 里的
 * teleport/walk 分支）字节码完全一致；唯一区别是改版把该逻辑提取成 tryFollowOwner
 * 并加了 tick 覆写【每 tick 驱动】。官方版只在行为【启动时】设一次寻路目标：
 * 被其他行为覆盖、或 MoveToTargetSink 的 150~250 tick 刹车（实测一百二十九修过：
 * 走路满 7.5~12.5 秒清 WALK_TARGET + 停导航）清掉后，女仆失去跟随目标，
 * 走走停停/漫无目的乱跑。
 *
 * 【注入点（实测一百五十九修正）】初版注入 m_6725_（tick）导致游戏加载崩溃——
 * tick 是继承自父类 Behavior 的方法，Mixin 的 @Inject 只匹配目标类【自己声明】的
 * 方法，继承方法匹配不到（InvalidInjectionException: could not find any targets
 * matching 'm_6725_' in MaidFollowOwnerTask）。MaidFollowOwnerTask 自己声明的方法
 * 只有 m_6114_（canUse）与 m_6735_（start）。本任务未覆写 canStillUse（父类默认
 * false），大脑每 tick：STILL 状态 → tryStart → canUse（m_6114_ 每 tick 调用）；
 * RUNNING 只持续 1 tick 就 doStop 回到 STILL——注入 m_6114_ 入口即等价每 tick 驱动。
 *
 * 【处理器签名（实测一百六十修正）】m_6114_ 返回 boolean——Mixin 要求带返回值的
 * 方法用 CallbackInfoReturnable<Boolean>（用 CallbackInfo 会报 Invalid descriptor:
 * CallbackInfoReturnable is required）。本注入不 setReturnValue，原 canUse 判定不受影响。
 *
 * 每 tick 重断言：4 格内不动（跟随已达成）；超过 4 格重新 setWalkAndLookTarget
 * 指向主人（speedModifier/stopDistance 与官方任务字段一致）；守家/脑冻结/无主/
 * 跨维度/干活中不拉（干活判定防跟随目标覆盖工作寻路）。总开关 misc.followTighten。
 */
@Mixin(MaidFollowOwnerTask.class)
public abstract class MaidFollowOwnerTickMixin {
    @Shadow
    @Final
    private float speedModifier;

    @Shadow
    @Final
    private int stopDistance;

    @Inject(method = "m_6114_", at = @At("HEAD"))
    private void maidsmart$perTickFollow(ServerLevel level, LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        try {
            if (!com.maidsmart.config.MaidSmartConfig.MISC_FOLLOW_TIGHTEN.get()) {
                return;
            }
            // 守家/脑冻结不拉（与官方 maidStateConditions 同口径）
            if (maid.isHomeModeEnable() || !maid.canBrainMoving()) {
                return;
            }
            // 干活中不拉——跟随目标不覆盖工作寻路（工作行为自己控制移动）
            if (maid.getTask() != null && maid.getTask().getUid() != null
                    && !"idle".equals(maid.getTask().getUid().m_135815_())) {
                return;
            }
            LivingEntity owner = maid.m_269323_();
            if (owner == null || !owner.m_6084_() || owner.m_21224_()
                    || maid.m_9236_() != owner.m_9236_()) {
                return; // 无主/主人死亡/跨维度不拉
            }
            if (maid.m_20238_(owner.m_20182_()) <= 16.0) {
                return; // 4 格内：跟随已达成，不打扰
            }
            // 超过 4 格：重断言跟随目标（参考改版 jar 的每 tick 驱动）
            BehaviorUtils.m_22590_(maid, owner, this.speedModifier, this.stopDistance);
        } catch (Throwable ignored) {
        }
    }
}
