package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoLayerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Mob;

/**
 * v1.2.0 实测五百四十三（1.21.1）：把官方那圈**激流旋转特效**画给 **Gecko 模型** 女仆。
 *
 * 几何/贴图/三层方块的角度与位移都在 {@link MaidSpinAttackEffect}（逐行照抄原版
 * `SpinAttackEffectLayer`，含"它其实是独立图层"的完整取证）。本类只解决一件事：
 * **Gecko 图层所处的坐标系与原版模型空间不同**。
 *
 * 【反编译实证】`GeoReplacedEntityRenderer.render` 里，图层是在
 * `this.setupRotations(entity, poseStack, …)`（旋转在这一步施加）**之后**、
 * "格、Y 朝上、原点在脚下"的空间里逐个 `layerRenderer.render(...)` 调用；而原版
 * `SpinAttackEffectLayer` 拿到的 poseStack 在 `scale(-1,-1,1) + translate(0,-1.501,0)`
 * 之后（Y 朝下、X 镜像、单位=模型像素/16）。
 * 所以这里先 `scale(-1,-1,1)` 把坐标翻进原版空间，之后原版那两步（`translate(0, -0.2+0.6i, 0)`
 * 与 `scale(0.75i)`）就是**原样照抄**、不再做任何换算——与鞘翅图层同样的处理。
 *
 * 【判据】{@link MaidSpinAttackEffect#shouldRender}（＝原版那个已同步的旋转标志位）。
 */
public class LayerMaidSpinAttackGecko extends GeoLayerRenderer<Mob, GeckoEntityMaidRenderer<Mob>> {

    @SuppressWarnings("unchecked")
    public LayerMaidSpinAttackGecko(GeckoEntityMaidRenderer<?> renderer) {
        super((GeckoEntityMaidRenderer<Mob>) renderer);
    }

    @Override
    public GeoLayerRenderer<Mob, GeckoEntityMaidRenderer<Mob>> copy(GeckoEntityMaidRenderer<Mob> renderer) {
        return new LayerMaidSpinAttackGecko(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!MaidSpinAttackEffect.shouldRender(mob)) {
            return;
        }
        poseStack.pushPose();
        try {
            // 骨骼空间（格、Y 朝上、X 已镜像）→ 原版模型空间（Y 朝下、X 镜像）
            poseStack.scale(-1.0f, -1.0f, 1.0f);
            MaidSpinAttackEffect.render(poseStack, buffer, light, ageInTicks);
        } catch (Throwable ignored) {
        } finally {
            poseStack.popPose();
        }
    }
}
