package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Mob;

/**
 * v1.2.0 实测五百四十三（1.21.1）：把官方那圈**激流旋转特效**画给 **Bedrock 模型** 女仆。
 *
 * 几何、贴图、三层方块的角度/缩放/位移**逐行照抄**原版 `SpinAttackEffectLayer`，
 * 全部收在 {@link MaidSpinAttackEffect}（含"它其实是个独立图层、不是粒子也不是玩家模型"
 * 的完整取证）。本类只负责"挂在哪"。
 *
 * 【为什么这里不用换算】本层在 `EntityMaidRenderer` 的图层链里，它所处的矩阵与玩家
 * `PlayerRenderer` 那边**完全同一个空间**（原版模型空间：Y 朝下、X 镜像），原版怎么画就怎么画。
 *
 * 【判据】{@link MaidSpinAttackEffect#shouldRender}（＝原版那个已同步的旋转标志位）。
 */
public class LayerMaidSpinAttack extends RenderLayer<Mob, BedrockModel<Mob>> {

    public LayerMaidSpinAttack(EntityMaidRenderer renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!MaidSpinAttackEffect.shouldRender(mob)) {
            return;
        }
        try {
            MaidSpinAttackEffect.render(poseStack, buffer, light, ageInTicks);
        } catch (Throwable ignored) {
        }
    }
}
