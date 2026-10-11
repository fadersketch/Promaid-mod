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
 * 【半透明二期·扫帚本体】：**扫帚**也一起半透明——只对"骑着它的那位玩家的第一人称"。
 *
 * 【为什么拦这个类、这个方法】TLM 的扫帚渲染器 {@code EntityBroomRender extends
 * LivingEntityRenderer<EntityBroom, BroomModel>} **没有覆写 render**（javap 实证：只有构造器、
 * shouldShowName、getTextureLocation），所以它的渲染入口就是 {@code LivingEntityRenderer} 自己声明的
 * 那一个——注入到**基类**，再用 {@code instanceof EntityBroom} 把世上别的生物放行（同类前例：
 * {@code EntityGunnerHangMixin} 注入 {@code Entity} 的 positionRider）。
 * 【描述符必须是 {@code LivingEntity}}javap -s 实证：该方法声明的描述符是 {@code (…LivingEntity;FF…)V}
 * （泛型 T 的 erasure），{@code (…Entity;…)V} 是编译器生成的桥接方法。**不能**写 Mob——那个描述符在这个类
 * 里不存在，注入点会解析不到（启动期直接报错）。
 * 【不误伤女仆】{@code EntityMaidRenderer} **自己声明**了 {@code render(Mob, …)}，调度先落到它自己那层、
 * 不走基类这里——女仆那条链路（{@code MaidGhostTetherMixin} 拦它）与这里互不干扰。判定全在客户端，本 mixin
 * 挂在 client 列表，专用服务器不加载。
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
        // 【实测八百三十四】护栏：重进跑的是原版方法体（push 后直接 pop、无 finally），中途抛异常
        // 会把它的 push 留在姿态栈上 → 原版 checkPoseStack 抛 "Pose stack not empty" 崩游戏。
        int guardDepth = com.maidsmart.client.MaidRenderGuard.depthOf(poseStack);
        ci.cancel();
        try {
            ((LivingEntityRenderer<EntityBroom, ?>) (Object) this).render(broom, entityYaw, partialTicks,
                    poseStack, com.maidsmart.client.MaidGhostRender.wrapBroom(bufferIn), packedLightIn);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostBroomMixin", t); // 幽灵化这一遍失败：这一帧就当它没画（下一帧照常试）；把姿态栈补回、并留一行痕
            com.maidsmart.client.MaidRenderGuard.noteSwallowed("武装拴绳半透明重绘（扫帚）", t);
        } finally {
            com.maidsmart.client.MaidGhostRender.end();
            com.maidsmart.client.MaidRenderGuard.restore(poseStack, guardDepth);
        }
    }
}
