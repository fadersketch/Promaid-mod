package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0（1.20.1，客户端）：给 **GeckoLib 模型** 女仆挂上飞行作战的鞘翅图层。
 *
 * 为什么必须单独做一层：EntityMaidRenderer.m_7392_ 在 `mainInfo.isGeckoModel()` 为真时
 * 把渲染整个交给 geckoEntityMaidRenderer.render(...) 并直接 return——**不会调用
 * super.m_7392_**，所以挂在 EntityMaidRenderer 上的普通 RenderLayer 对 Gecko 女仆
 * 根本不执行。Gecko 渲染器自己那份 layerRenderers 才有效。
 * 注入其构造末尾，调用 public 的 addGeoLayerRenderer（与官方扩展点同一入口）。
 */
@Mixin(GeckoEntityMaidRenderer.class)
public abstract class MaidElytraLayerGeckoMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void promaid$addElytraLayer(EntityRendererProvider.Context context, CallbackInfo ci) {
        GeckoEntityMaidRenderer<?> self = (GeckoEntityMaidRenderer<?>) (Object) this;
        self.addGeoLayerRenderer(new com.maidsmart.client.LayerMaidElytraGecko(self, context));
    }
}
