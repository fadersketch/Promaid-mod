package com.maidsmart.mixin;

import com.maidsmart.flight.MaidFreeFlightAnimState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.5 实测六百五十七【仿创造飞行 · 注册 fly 状态】——让模型包自己的 `fly` 动画能在女仆身上播放。
 *
 * 注入 TLM 客户端初始化时的 `registerAnimationState()` 尾部，把我们自己的状态挂上去：
 * <ul>
 *   <li>名字用 {@code fly}——与 YSM 玩家侧（以及模型包里的动画名）**同名**，所以模型包不用改一行；</li>
 *   <li>priority 取 **1**：TLM 从 0 往下取第一个命中，0 是死亡/睡觉/游泳（那些优先），
 *       1 排在 `jump`(2) 之前——她悬停时正是"离地且不在水里"，不抢优先级就会被 jump 顶掉；</li>
 *   <li>条件只用**客户端可见的同步数据**（在场物品 / 身上效果 / 重力属性 + 不在落地/水里/骑乘），
 *       不去读服务端那份"是否在飞"的状态——避免为它加一套同步通道。</li>
 * </ul>
 * 冲突面很小：priority 1 同档还有 boat/chair/sit（那几档她都是乘客或坐姿，被我们的条件排除）、
 * 以及 YSM 的 ride/sit；真正的取舍只有 `attacked`(2) ——她挨打时优先播受击动画而不是飞行，
 * 这条也写在条件里（hurtTime == 0）。
 */
@Mixin(com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.AnimationRegister.class)
public abstract class MaidFreeFlightAnimMixin {

    @Inject(method = "registerAnimationState", at = @At("TAIL"))
    private static void promaid$registerFreeFlightState(CallbackInfo ci) {
        try {
            // 先注册 elytra_fly：滑翔档（配置打开时）——与 fly 同为 priority 1，
            // 同优先级内取先注册者，所以它排在前面（滑翔与自由飞行两种状态互斥，不会打架）
            MaidFreeFlightAnimInvoker.promaid$registerState("elytra_fly", 1,
                    (maid, event) -> MaidFreeFlightAnimState.shouldPlayElytraFly(maid));
            MaidFreeFlightAnimInvoker.promaid$registerState("fly", 1,
                    (maid, event) -> MaidFreeFlightAnimState.shouldPlayFly(maid));
        } catch (Throwable ignored) {
        }
    }
}
