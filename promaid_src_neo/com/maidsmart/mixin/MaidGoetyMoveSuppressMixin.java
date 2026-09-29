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
 * 实测 G-2【Goety 推进期间不让"走路"抢方向盘】——照上游 {@link MaidMoveSuppressMixin} 的范式再加一档。
 *
 * <h2>实机现场（需求方在单机里复现）</h2>
 * <pre>
 *   /maid_smart goety_fly &lt;30 格外的坐标&gt; → 她不去，而是**绕着一个点画不规则但重复的圈**；
 *   日志里 err=无（法术每次都放成功）、俯仰也对（pitch=-68 → 速度 y=+0.46），
 *   但水平方向一直在变 —— 最后"收手（超时）"。
 * </pre>
 * 根因：飞行聚晶的推力方向取的是 {@code caster.getLookAngle()}，而**女仆有主人时**，
 * TLM 的跟随/闲逛会持续给她下行走目标 ⇒ 原版 {@code MoveControl.tick()} 每 tick 把她的
 * **yaw 掰向那个行走目标**（每秒最多 90°）⇒ 推力方向指向主人 ⇒ 绕圈。
 * 俯仰不归 MoveControl 管，所以只有水平方向在乱 —— 这正是日志里"俯仰对、方向错"的来源。
 *
 * <p>上游那套抑制是按它自己的几档（工作站桩/自保/战术接管/插火把/创造飞行）判定的，
 * **不认识我们这一档**，所以这里独立再加一个 mixin：只要这只女仆正在被 Goety 推进，
 * 就把 {@code WALK_TARGET} 清掉、停掉导航、取消本 tick —— 从源头让"走路"没有方向盘可抢。
 *
 * <p>不加 {@code MaidScope.owned} 门禁：本档是**玩家显式下命令**才进的（不是自动行为），
 * 无主女仆在测试服里也要能飞。
 */
@Mixin(net.minecraft.world.entity.ai.behavior.MoveToTargetSink.class)
public abstract class MaidGoetyMoveSuppressMixin {

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;J)V",
            at = @At("HEAD"), cancellable = true)
    private void maidsmart$suppressMoveUnderGoetyPush(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (!MaidGoetyFlight.isActive(maid)) {
            return;
        }
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        try {
            maid.getNavigation().stop();
        } catch (Throwable ignored) {
        }
        ci.cancel();
    }
}
