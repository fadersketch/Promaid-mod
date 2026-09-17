package com.maidsmart.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0 实测五百四十二：把激流旋转的**枢轴抬高**（纯客户端渲染修复）。
 *
 * <p>原版施加激流姿态是绕**实体原点（脚底）**转的，平躺之后身体有一半在地面以下 ——
 * 平地勉强能看，斜坡/台阶上就像嵌进方块里。这里在旋转**之前**（本方法 HEAD，矩阵仍是
 * "Y 朝上、原点在脚下"）先把模型整体抬起一点，身体就以离地一段高度为中心了。
 *
 * <p>注在**原版**这一层而不是 TLM 的渲染器：Gecko 与 Bedrock 两套模型最终都走这里，
 * 一处覆盖两边（TLM 的 `GeoReplacedEntityRenderer.setupRotations` 与
 * `EntityMaidRenderer.setupRotations` 都是先调 super 再叠自己的东西）。
 *
 * <p>1.21.1 的签名比 1.20.1 多一个 `pScale` 参数（javap 实证：
 * `setupRotations(T, PoseStack, float, float, float, float)`）。
 *
 * <p>判据与逻辑都在 {@link com.maidsmart.client.SpinLift} 里（含"为什么是 0.6 格"的推导、
 * 以及"只动渲染、不动机制"的说明）。非女仆 / 未在旋转时原样返回。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MaidSpinLiftMixin {

    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void promaid$liftWhileSpinning(LivingEntity entity, PoseStack poseStack,
                                           float ageInTicks, float rotationYaw, float partialTicks,
                                           float scale, CallbackInfo ci) {
        com.maidsmart.client.SpinLift.apply(entity, poseStack);
    }
}
