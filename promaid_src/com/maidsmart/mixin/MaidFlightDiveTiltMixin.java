package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoReplacedEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【飞行作战"跟随俯角"前倾】——**Gecko 模型**（酒狐家族等）走这条。
 * 【为什么注入这里】Gecko 女仆由 {@code GeckoEntityMaidRenderer} 渲染，它继承
 * {@code GeoReplacedEntityRenderer}；该类的 {@code setupRotations} 先调
 * {@code LivingEntityRenderer.setupRotations} 摆好朝向，此时矩阵仍在世界式空间（Y 朝上）——原版所有
 * 飞行/俯卧姿态都在这一层施加。在 RETURN 处叠前倾 = 作用在"模型动画之上"，与 swim 动画叠加而非覆盖。
 * 【为什么不走 TLM 扩展点】TLM 的 {@code ICustomAnimation.setupGeckoRotations} 存在，但 TLM 自己的
 * {@code SwimAnimation} **只覆写了 {@code setupRotations}（Bedrock 路径）、没覆写
 * {@code setupGeckoRotations}**——Gecko 姿态根本不走扩展点分发（俯卧来自模型自带的 {@code swim} 动画，
 * 谓词 {@code isVisuallySwimming()}）。用扩展点够不到，必须 mixin 这个渲染器方法。
 * 【只影响飞行中女仆】判定与角度计算都在 {@link com.maidsmart.client.FlightDiveTilt}（含"为什么是
 * {@code -getXRot()}"的完整推导）；非女仆、已落地原样返回，判据 = "飞行任务**或**正在滑翔"（飞行跟随的
 * 女仆不是飞行任务，见 {@code MaidFlightKit.isFlightVisual}）。本树 SRG {@code m_7523_} / neo 树官方名
 * {@code setupRotations}；两树各写一份、互不影响。
 */
@Mixin(GeoReplacedEntityRenderer.class)
public abstract class MaidFlightDiveTiltMixin {

    @Inject(method = "m_7523_", at = @At("RETURN"), require = 0)
    private void promaid$diveTilt(LivingEntity entity, PoseStack poseStack, float ageInTicks,
                                 float rotationYaw, float partialTicks, CallbackInfo ci) {
        com.maidsmart.client.FlightDiveTilt.apply(entity, poseStack);
    }
}
