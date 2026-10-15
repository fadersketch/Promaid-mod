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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 跟随每 tick 重断言——平常跟随后维持在 4 格以内、不再乱跑（参考改版 TLM jar "…-modified-all.jar"）。
 * 【实证】改版 jar 与官方 1.5.3 的 {@code MaidFollowOwnerTask} 距离逻辑（start 里的 teleport/walk 分支）
 * 字节码完全一致；唯一区别是改版把它提取成 {@code tryFollowOwner} 并加了 tick 覆写【每 tick 驱动】。官方版
 * 只在行为**启动时**设一次寻路目标：被其他行为覆盖、或 {@code MoveToTargetSink} 的 150~250 tick 刹车清掉
 * 后，女仆失去跟随目标，走走停停/漫无目的乱跑。
 * 【注入点】该类自己声明的只有 {@code checkExtraStartConditions} 与 {@code start}；它未覆写 canStillUse，
 * 大脑每 tick 走 STILL → tryStart → canUse，RUNNING 只持续 1 tick 就 doStop 回 STILL，故**注入
 * {@code checkExtraStartConditions} 入口即等价每 tick 驱动**。注意 1.21.1 的该方法第二参数是具体类型
 * {@code EntityMaid}（javap 实证）——用父类 {@code LivingEntity} 会报 Invalid descriptor，客户端放置女仆
 * 时崩溃（服务端不加载该类，故服务器测试没暴露）。
 * 【签名】{@code checkExtraStartConditions} 返回 boolean——必须用 {@code CallbackInfoReturnable<Boolean>}（用 {@code CallbackInfo} 会报 Invalid descriptor）；本注入不 setReturnValue，原判定不受影响。
 * 口径：4 格内不动；超过 4 格重新 {@code setWalkAndLookTarget} 指向主人；守家/脑冻结/无主/跨维度/干活中不拉
 * （防覆盖工作寻路）。上面这条的总开关 {@code misc.followTighten}；本类另有一发"链路独占"取消（见下方方法）。
 */
@Mixin(MaidFollowOwnerTask.class)
public abstract class MaidFollowOwnerTickMixin {
    @Shadow
    @Final
    private float speedModifier;

    @Shadow
    @Final
    private int stopDistance;

    @Inject(method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)Z",
            at = @At("HEAD"))
    private void maidsmart$perTickFollow(ServerLevel level, EntityMaid maid, CallbackInfoReturnable<Boolean> cir) {
        if (!com.maidsmart.tool.MaidScope.owned(maid)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包对野生女仆的规则一律不动）
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
                    && !"idle".equals(maid.getTask().getUid().getPath())) {
                return;
            }
            LivingEntity owner = maid.getOwner();
            if (owner == null || !owner.isAlive() || owner.isDeadOrDying()
                    || maid.level() != owner.level()) {
                return; // 无主/主人死亡/跨维度不拉
            }
            if (maid.position().distanceTo(owner.position()) <= 16.0) {
                return; // 4 格内：跟随已达成，不打扰
            }
            // 超过 4 格：重断言跟随目标（参考改版 jar 的每 tick 驱动）
            BehaviorUtils.setWalkAndLookTargetMemories(maid, owner, this.speedModifier, this.stopDistance);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFollowOwnerTickMixin", ignored);
        }
    }

    /**
     * 链路走位独占期间【整段取消跟随行为的 start】（实测九百一十三）。
     * 跟随行为"太远就把主人写回她的寻路目标"与"更远就传送"两个分支都落在 start 里，而**传送被拦下后
     * 它紧接着无条件 {@code resetNavigation()}**（停寻路 + 导航模式重置回 GROUND）——她刚起步的寻路
     * 每 tick 被清一次 = 原地走一步停一步，只在传送点拦截拦不住这一下。
     */
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$yieldWhileChainDriving(ServerLevel level, LivingEntity entity, long gameTime,
                                                  CallbackInfo ci) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        try {
            if (com.maidsmart.task.MaidWorkTags.isChainDrive(maid)) {
                com.maidsmart.combat.CombatStallProbe.note(maid, "follow-cut"); // 滞留探针（诊断）
                ci.cancel(); // 干活/接战中：本行为的走位与传送整段不生效
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidFollowOwnerTickMixin", ignored);
        }
    }
}
