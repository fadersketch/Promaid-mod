package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0（1.21.1，客户端）：给 GeckoLib 女仆渲染器挂上飞行作战的鞘翅图层。
 *
 * 【为什么是 mixin】TLM 的官方扩展点 addAdditionGeckoMaidLayer 的方法签名带客户端类型
 * （GeckoEntityMaidRenderer/Context），在服务端也会加载的扩展类里覆写会被
 * RuntimeDistCleaner 拦截（同 addAdditionMaidLayer 的坑）。改为客户端 mixin：
 * 注入 GeckoEntityMaidRenderer 构造末尾，调用其 public 的 addGeoLayerRenderer——
 * 与官方扩展点同一时机、同一入口，但只在 client 段 mixin 加载。
 */
@Mixin(GeckoEntityMaidRenderer.class)
public abstract class MaidElytraLayerGeckoMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void promaid$addElytraLayer(EntityRendererProvider.Context context, CallbackInfo ci) {
        GeckoEntityMaidRenderer<?> self = (GeckoEntityMaidRenderer<?>) (Object) this;
        self.addGeoLayerRenderer(new com.maidsmart.client.LayerMaidElytraGecko(self, context));
        // 实测五百四十三：激流旋转特效（复刻原版 SpinAttackEffectLayer）
        self.addGeoLayerRenderer(new com.maidsmart.client.LayerMaidSpinAttackGecko(self));
    }
}
