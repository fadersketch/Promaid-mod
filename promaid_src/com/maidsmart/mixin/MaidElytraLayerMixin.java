package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0（1.20.1，客户端）：给 **Bedrock 模型** 女仆挂上两个图层。
 * 注入 EntityMaidRenderer 构造末尾。Gecko 模型走另一个 mixin（见 MaidElytraLayerGeckoMixin）。
 *
 * <ul>
 *   <li>鞘翅图层（飞行作战）——实测四百八十 起；</li>
 *   <li>官方激流旋转特效图层（实测五百四十三）——与鞘翅同一入口挂载，时机完全一致。</li>
 * </ul>
 */
@Mixin(EntityMaidRenderer.class)
public abstract class MaidElytraLayerMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void promaid$addElytraLayer(EntityRendererProvider.Context context, CallbackInfo ci) {
        EntityMaidRenderer self = (EntityMaidRenderer) (Object) this;
        LivingEntityRendererAddLayerInvoker invoker = (LivingEntityRendererAddLayerInvoker) self;
        invoker.promaid$addLayer(new com.maidsmart.client.LayerMaidElytra(self, context));
        // 实测五百四十三：激流旋转特效（复刻原版 SpinAttackEffectLayer）
        invoker.promaid$addLayer(new com.maidsmart.client.LayerMaidSpinAttack(self));
    }
}
