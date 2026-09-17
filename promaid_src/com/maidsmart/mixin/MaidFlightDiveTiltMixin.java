package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoReplacedEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0 实测五百零六【飞行作战"跟随俯角"前倾】——**Gecko 模型**（酒狐家族等）走这条。
 *
 * 【为什么注入这里】酒狐等 Gecko 女仆由 `GeckoEntityMaidRenderer` 渲染，它继承
 * `GeoReplacedEntityRenderer`；该类的 `setupRotations` 先调 `LivingEntityRenderer.setupRotations`
 * 摆好朝向，此时矩阵仍在世界式空间（Y 朝上）——原版所有飞行/俯卧姿态都在这一层施加。
 * 在 RETURN 处叠一层额外前倾，就能作用在"模型动画之上"，与 swim 动画叠加而非覆盖。
 *
 * 【为什么不走 TLM 官方扩展点】TLM 的 `ICustomAnimation.setupGeckoRotations` 确实存在，
 * 但 TLM 自己的 `SwimAnimation` **只覆写了 `setupRotations`（Bedrock 路径）、没有覆写
 * `setupGeckoRotations`**——即 Gecko 路径的姿态根本不走扩展点分发（Gecko 的俯卧来自模型
 * 自带的 `swim` 动画，谓词是 `isVisuallySwimming()`）。用扩展点够不到，必须 mixin 这个
 * 渲染器方法本身。
 *
 * 【只影响飞行作战】具体的判定与角度计算都在 {@link com.maidsmart.client.FlightDiveTilt}
 * 里（含"为什么是 -getXRot()"的完整推导）。非飞行任务、非女仆、已落地一律原样返回。
 *
 * 【描述符说明】1.20.1 的 SRG 名是 `m_7523_`、1.21.1 是 `setupRotations`，本项目的两树
 * 源码本就分开维护（各自 mixin json + 各自文件名），故两树各写一份、互不影响。
 */
@Mixin(GeoReplacedEntityRenderer.class)
public abstract class MaidFlightDiveTiltMixin {

    @Inject(method = "m_7523_", at = @At("RETURN"), require = 0)
    private void promaid$diveTilt(LivingEntity entity, PoseStack poseStack, float ageInTicks,
                                 float rotationYaw, float partialTicks, CallbackInfo ci) {
        com.maidsmart.client.FlightDiveTilt.apply(entity, poseStack);
    }
}
