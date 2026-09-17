package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0（1.20.1，客户端）：给 **Bedrock 模型** 女仆挂上飞行作战的鞘翅图层。
 * 注入 EntityMaidRenderer 构造末尾。Gecko 模型走另一个 mixin（见 MaidElytraLayerGeckoMixin）。
 */
@Mixin(EntityMaidRenderer.class)
public abstract class MaidElytraLayerMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void promaid$addElytraLayer(EntityRendererProvider.Context context, CallbackInfo ci) {
        EntityMaidRenderer self = (EntityMaidRenderer) (Object) this;
        ((LivingEntityRendererAddLayerInvoker) self)
                .promaid$addLayer(new com.maidsmart.client.LayerMaidElytra(self, context));
    }
}
