package com.maidsmart.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v1.2.0（1.20.1，客户端）：暴露 `LivingEntityRenderer.addLayer`（SRG `m_115326_`，
 * protected final boolean）——用于给 EntityMaidRenderer 追加飞行作战的鞘翅图层。
 *
 * 为什么不在 ILittleMaid.addAdditionMaidLayer 里加：那个扩展点的签名带客户端类型
 * （EntityMaidRenderer / Context），在"服务端也会加载"的扩展类里覆写会被
 * RuntimeDistCleaner 拦下（1.21.1 那边实测过同款崩溃）。改成客户端 mixin 就没有这个问题。
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAddLayerInvoker {

    @Invoker("m_115326_")
    boolean promaid$addLayer(RenderLayer<?, ?> layer);
}
