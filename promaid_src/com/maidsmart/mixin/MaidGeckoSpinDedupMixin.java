package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoReplacedEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 【Gecko 模型女仆的激流旋转为什么转不起来】——TLM 自己那对旋转把原版的那对**正好抵消**了。
 * 原版早在 {@code LivingEntityRenderer.setupRotations} 里实现了激流姿态（不是 Player 专属；本树字节码在本
 * 方法 197-199 行）：按 {@code isAutoSpinAttack()} 施加 {@code XP(-90-xRot)} + {@code YP(-75·(tick+partial))}。
 * 而 TLM 1.5.3 的 {@code GeoReplacedEntityRenderer.setupRotations} 在 {@code super} 之后**又施加了同轴反
 * 号的一对**（javap 实证，常量 75.0f）。{@code mulPose} 是**右乘**，合成
 * {@code M = M0·XP(a)·YP(b)·YP(-b)·XP(-a) = M0}——同轴相邻对直接消成单位矩阵，**净旋转恒等于什么都不做**。
 * 所以走这个渲染器的 Gecko/YSM 模型（酒狐家族等）突进时永远不转也不俯卧；Bedrock 模型不走这里、不受影响。
 * 【修法】只掐掉 TLM 那一对重复的：把 TLM 那次标志位读取改成 {@code false}，它的 {@code if} 块整段不执行 →
 * 只剩原版那份，与玩家**完全同款**（该块里除那两次 {@code mulPose} 没有别的语句）。
 * 【require = 0】这条注入是"**撤销别人的重复**"，不是"提供我们自己的行为"：万一 TLM 以后自己删掉那一对
 * （即修好了 bug），重定向不再命中、我们自动不再干预——写 {@code require = 1} 会把"TLM 修好了"变成启动崩溃。
 * 【覆盖范围】只覆盖走 {@code GeoReplacedEntityRenderer} 的模型（Gecko）；YSM 走 YSM 自己的渲染器、管不到。
 * 与 {@link MaidFlightDiveTiltMixin} 并存、互不干扰（那个做的是飞行前倾）。本树 SRG {@code m_7523_}；neo 树官方名。
 */
@Mixin(GeoReplacedEntityRenderer.class)
public abstract class MaidGeckoSpinDedupMixin {

    /**
     * 拦截 TLM 那次 {@code isAutoSpinAttack()} 读取，恒返回 false —— 让它那一对与
     * 原版反号的重复旋转整段跳过。
     */
    @Redirect(method = "m_7523_",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;m_21209_()Z"),
            require = 0)
    private boolean promaid$dropDuplicateSpin(LivingEntity entity) {
        return false;
    }
}
