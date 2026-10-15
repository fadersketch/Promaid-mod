package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.goety.MaidGoetyFlight;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【Goety 推进期间不让"走路"抢方向盘】——照上游 {@code MaidMoveSuppressMixin} 的范式再加一档。
 *
 * 【实机现场】{@code /maid_smart goety_fly <30 格外的坐标>} → 她不去，而是**绕着一个点画不规则但重复的
 * 圈**；日志 err=无（法术每次都放成功）、俯仰也对（pitch=-68 → 速度 y=+0.46），但水平方向一直在变——最后
 * "收手（超时）"。
 * 【根因】飞行聚晶的推力方向取 {@code caster.getLookAngle()}，而**女仆有主人时** TLM 的跟随/闲逛会持续给她下行走目标 ⇒
 * 原版 {@code MoveControl.tick()} 每 tick 把她的 **yaw 掰向行走目标** ⇒ 推力指向主人 ⇒ 绕圈（俯仰不归 MoveControl 管）。
 * 【做法】上游那套抑制按它自己的几档（工作站桩/自保/战术接管/插火把/创造飞行）判定，**不认识我们这一档**，
 * 所以这里独立再加一个 mixin：只要这只女仆正在被 Goety 推进，就清 {@code WALK_TARGET} + 停导航 + 取消本
 * tick——从源头让"走路"没有方向盘可抢。
 * 【不加 {@code MaidScope.owned} 门禁】本档是**玩家显式下命令**才进的（不是自动行为），无主女仆在测试服里
 * 也要能飞。
 * 【1.20.1 差异】本树 {@code MoveToTargetSink.tick} 只有 SRG 名 {@code m_6725_}；注入点写全签名——该类上同名方法有两份（自带的参数是 {@code Mob}、编译器留的协变桥接是 {@code LivingEntity}），裸名字会命中桥接、签名对不上就崩。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.MoveToTargetSink.class)
public abstract class MaidGoetyMoveSuppressMixin {

    @Inject(method = "m_6725_(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressMoveUnderGoetyPush(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (!MaidGoetyFlight.isActive(maid)) {
            return;
        }
        maid.m_6274_().m_21936_(MemoryModuleType.f_26370_);
        try {
            maid.m_21573_().m_26573_();
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidGoetyMoveSuppressMixin", ignored);
        }
        ci.cancel();
    }
}
