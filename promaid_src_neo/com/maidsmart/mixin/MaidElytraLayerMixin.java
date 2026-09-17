package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.2.0（1.21.1，客户端）：给女仆渲染器挂上"飞行作战鞘翅"图层。
 *
 * 【为什么不走 ILittleMaid.addAdditionMaidLayer】TLM 虽然提供这个官方扩展点，
 * 但它的方法签名里带客户端类型 EntityMaidRenderer / EntityRendererProvider.Context。
 * 在 ProMaidExtension（服务端也会加载 @Mod 扩展类）里覆写它，FML 注册事件总线时会
 * 反射该类的全部方法描述符 → NeoForge 的 RuntimeDistCleaner 立刻抛
 * "Attempted to load class ... EntityMaidRenderer for invalid dist DEDICATED_SERVER"，
 * 整个 mod 加载失败、服务端启动中止（实测崩溃实证）。
 * 所以改为【客户端 mixin】：直接注入 EntityMaidRenderer 构造末尾 addLayer——
 * 效果与官方扩展点等价（同一时机、同一入口），但客户端类只出现在 client 段 mixin 中，
 * 服务端根本不会加载本类。
 *
 * 本类已在 mixins.promaid.json 的 client 列表注册。
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
