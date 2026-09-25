package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityBroom;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.0 实测六百七十四【半透明二期·扫帚本体】：**扫帚**也一起半透明——只对"骑着它的那位玩家的
 * 第一人称"。玩家原话："半透明程度不够，默认应该为0.1，而且扫帚也应该是半透明的。"
 *
 * ── 为什么拦这个类、这个方法 ──
 * TLM 的扫帚渲染器 {@code EntityBroomRender extends LivingEntityRenderer<EntityBroom, BroomModel>}
 * **没有覆写 render**（javap 实证：那个类里只有构造器、shouldShowName、getTextureLocation），
 * 所以它的渲染入口就是 {@code LivingEntityRenderer} 自己声明的那一个 —— 注入到**基类**，
 * 再用 {@code instanceof EntityBroom} 把世上所有别的生物放行（同类前例：{@code EntityGunnerHangMixin}
 * 注入 {@code Entity} 的 positionRider）。
 *
 * <p>【描述符为什么是 LivingEntity】javap -s 实证：{@code LivingEntityRenderer} 里这个方法声明的
 * 描述符是 {@code (Lnet/minecraft/world/entity/LivingEntity;FF...)V}（泛型 T 的 erasure），
 * 另外那个 {@code (Lnet/minecraft/world/entity/Entity;...)V} 是编译器生成的桥接方法。
 * **不能**写 Mob —— 那个描述符在这个类里不存在，注入点会解析不到（启动期直接报错）。
 *
 * <p>【为什么不会误伤女仆】TLM 的 {@code EntityMaidRenderer} **自己声明**了 {@code render(Mob, …)}
 * （javap 实证），调度器的调用会先落到它自己的那一层、不会走到基类这里 —— 女仆那条链路
 * （由 {@code MaidGhostTetherMixin} 拦 {@code EntityMaidRenderer.render}）与这里互不干扰。
 *
 * <p>【两侧无关】判定全在客户端（第一人称 + 本地玩家 + 是不是骑着这把扫帚），服务端一行不改；
 * 本 mixin 挂在 mixins 配置的 client 列表里，专用服务器上不会被加载。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MaidGhostBroomMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$ghostBroomFirstPerson(LivingEntity entity, float entityYaw, float partialTicks,
                                               PoseStack poseStack, MultiBufferSource bufferIn, int packedLightIn,
                                               CallbackInfo ci) {
        if (!(entity instanceof EntityBroom broom)) {
            return;
        }
        if (!com.maidsmart.client.MaidGhostRender.beginBroom(broom)) {
            return;
        }
        // 先取消**这一遍**，再用包好的 buffer 重进一次（顺序不能颠倒：反过来一旦中途抛异常，
        // 扫帚的几何会被提交两遍）。重进的那一遍由 MaidGhostRender 内部标记挡住，原方法体只跑一次。
        ci.cancel();
        try {
            ((LivingEntityRenderer<EntityBroom, ?>) (Object) this).render(broom, entityYaw, partialTicks,
                    poseStack, com.maidsmart.client.MaidGhostRender.wrapBroom(bufferIn), packedLightIn);
        } catch (Throwable ignored) {
            // 幽灵化这一遍失败：这一帧就当它没画（下一帧照常试）。绝不把异常抛进渲染线程。
        } finally {
            com.maidsmart.client.MaidGhostRender.end();
        }
    }
}
