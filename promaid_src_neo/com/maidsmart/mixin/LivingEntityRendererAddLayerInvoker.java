package com.maidsmart.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v1.2.0（1.21.1，客户端）：把 LivingEntityRenderer.addLayer 暴露出来。
 *
 * 用途：TLM 的扩展点 ILittleMaid.addAdditionMaidLayer(EntityMaidRenderer, Context)
 * 把渲染器实例交给第三方，但 addLayer 是 LivingEntityRenderer 的 protected final
 * 方法（字节码实证），外部包无法直接调用——TLM 自己的 Gecko 图层走
 * IGeoEntityRenderer.addGeoLayerRenderer（public），Bedrock 的 RenderLayer 链却
 * 没有公开入口。所以用 @Invoker 把这唯一的入口暴露出来，供飞行作战的鞘翅图层注册。
 *
 * 仅客户端加载（在 mixins.promaid.json 的 client 段注册）。
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAddLayerInvoker {
    @Invoker("addLayer")
    boolean promaid$addLayer(RenderLayer<?, ?> layer);
}
