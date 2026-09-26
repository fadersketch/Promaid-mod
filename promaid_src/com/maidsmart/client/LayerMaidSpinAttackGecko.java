package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * v1.2.0 实测五百四十三（1.20.1）：把官方那圈**激流旋转特效**画给 **Gecko 模型** 女仆。
 *
 * 几何/贴图/三层方块的角度与位移都在 {@link MaidSpinAttackEffect}（逐行照抄原版
 * `SpinAttackEffectLayer`，含"它其实是独立图层"的完整取证）。本类只解决一件事：
 * **Gecko 图层所处的坐标系与原版模型空间不同**。
 *
 * 【反编译实证】`GeoReplacedEntityRenderer.render` 里，图层是在
 * `this.m_7523_(entity, poseStack, …)`（＝`setupRotations`，旋转在这一步施加）**之后**、
 * “格、Y 朝上、原点在脚下”的空间里逐个 `layerRenderer.render(...)` 调用；而原版
 * `SpinAttackEffectLayer` 拿到的 poseStack 在 `scale(-1,-1,1) + translate(0,-1.501,0)`
 * 之后（Y 朝下、X 镜像、单位=模型像素/16）。
 * 所以这里先 `scale(-1,-1,1)` 把坐标翻进原版空间，之后原版那两步（`translate(0, -0.2+0.6i, 0)`
 * 与 `scale(0.75i)`）就是**原样照抄**、不再做任何换算——与鞘翅图层同样的处理。
 *
 * 【判据】{@link MaidSpinAttackEffect#shouldRender}（＝原版那个已同步的旋转标志位）。
 *
 * 【YSM 模型女仆也画】实测六百八十三：本层不再限定 Gecko 渲染器，YSM 渲染器下同样绘制
 * （理由见 {@code render} 里的注释；一句话：本层不依赖任何骨骼，而 YSM 调用图层的位置与
 * TLM 的 `GeoReplacedEntityRenderer` 同构）。
 */
@OnlyIn(Dist.CLIENT)
public class LayerMaidSpinAttackGecko extends com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoLayerRenderer<Mob, IGeoEntityRenderer<Mob>> {

    @SuppressWarnings("unchecked")
    public LayerMaidSpinAttackGecko(IGeoEntityRenderer<?> renderer) {
        super((IGeoEntityRenderer<Mob>) renderer);
    }

    @Override
    public com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoLayerRenderer<Mob, IGeoEntityRenderer<Mob>> copy(
            IGeoEntityRenderer<Mob> renderer) {
        return new LayerMaidSpinAttackGecko(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        // v1.2.0 实测五百四十八【YSM 兼容】：R 从 GeckoEntityMaidRenderer 放宽为
        // IGeoEntityRenderer，避免泛型擦除后的桥接方法在 TLM 把图层复制到 YSM 渲染器时抛
        // ClassCastException（那会让实体渲染器整体建不起来 → 黑屏进不去游戏）。
        //
        // 实测六百八十三【YSM 下也照画】——五百四十八 当时顺手写了一句「本层只在 Gecko
        // 渲染器下绘制」，理由是"挂点依赖 Gecko 骨骼表"。那条理由属于**鞘翅图层**
        // （{@link LayerMaidElytraGecko} 要 ElytraLocator / Elytra / UpperBody / Root），
        // 本层一根骨骼都不用：几何全在 {@link MaidSpinAttackEffect} 里，只在图层入口那个
        // poseStack 上按固定偏移画三层方块。而 YSM 的女仆渲染器（`com.elfmcys.*`，实现 TLM
        // 的 `IGeoEntityRenderer`）调用图层的位置与 TLM 的 `GeoReplacedEntityRenderer`
        // **同构**——反编译实证：push → setupRotations → translate(0, 0.01, 0) → 画模型 →
        // 图层循环 → pop，同一段代码形状、同一套矩阵。所以这里拿到的仍是"格、Y 朝上、
        // 原点在脚下"那一套（在 setupRotations 之后），下面 `scale(-1,-1,1)` 的换算照样成立，
        // 守卫就此取消：YSM 模型的女仆重新有那圈激流特效。
        if (!MaidSpinAttackEffect.shouldRender(mob)) {
            return;
        }
        poseStack.m_85836_();
        try {
            // 骨骼空间（格、Y 朝上、X 已镜像）→ 原版模型空间（Y 朝下、X 镜像）
            poseStack.m_85841_(-1.0f, -1.0f, 1.0f);
            MaidSpinAttackEffect.render(poseStack, buffer, light, ageInTicks);
        } catch (Throwable ignored) {
        } finally {
            poseStack.m_85849_();
        }
    }
}
