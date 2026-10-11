package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【武装拴绳三期·半透明】：绑定的玩家在第一人称下看挂着自己那只女仆 = 半透明。
 *
 * 【为什么拦 {@code EntityMaidRenderer.render(Mob, …)} 这一个方法】TLM 三条渲染分支（YSM / Gecko /
 * Bedrock）全部从它进——javap 实证：{@code isGeckoModel()} 为真时把渲染整个交给
 * {@code geckoEntityMaidRenderer.render(...)} 并 return，否则落到 {@code MobRenderer.render(...)}
 * （Bedrock）；YSM 走 {@code ysmMaidRenderer.geoRender(...)}。只有拦在这里，"模型本体 + 各图层全变半透明"
 * 才对三种模型一视同仁（按渲染类型逐个改只能覆盖其中一种）。
 * 【为什么是"取消 + 用包好的 buffer 重进一次"】不能用 {@code @Redirect} 改实参（{@code MultiBufferSource}
 * 在这里没有可安全注入的单一点），也不能"改完类型再让原方法跑"——渲染类型是方法体内部算出来的。写法是：先
 * {@code ci.cancel()} 掉**这一遍**，再用"包了一层的 MultiBufferSource"**重进同一个方法**；重进那遍由
 * {@code MaidGhostRender} 内部的 active 标记挡住拦截，于是**原方法体只执行一次**（RenderMaidEvent 只发一次、
 * 模型信息只算一次），只是它拿到的 buffer 变成了幽灵版。
 * 【两侧无关】判定全在客户端（第一人称 + 本地玩家 + S2C 挂载表），服务端一行不改；本 mixin 挂在 client 列表。
 */
@Mixin(EntityMaidRenderer.class)
public abstract class MaidGhostTetherMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/Mob;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void promaid$ghostWhenTethered(Mob entity, float entityYaw, float partialTicks,
                                           PoseStack poseStack, MultiBufferSource bufferIn, int packedLightIn,
                                           CallbackInfo ci) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        if (!com.maidsmart.client.MaidGhostRender.begin(maid)) {
            return;
        }
        // 先取消**这一遍**，再重进一次（active 标记会让重进的那一遍不再被拦）——
        // 顺序不能颠倒：先重进再取消的话，万一渲染中途抛异常就会把她的几何提交两遍。
        // 【实测八百三十四】护栏：重进的那一遍跑的是原版方法体，而原版是 push 后直接 pop、
        // 没有 finally；一旦中途抛异常，它的 push 就留在栈上，几十毫秒后由原版 checkPoseStack
        // 抛出 "Pose stack not empty" 把游戏崩掉（issue #37 的扫帚右击闪退就是这个形状）。
        int guardDepth = com.maidsmart.client.MaidRenderGuard.depthOf(poseStack);
        ci.cancel();
        try {
            EntityMaidRenderer self = (EntityMaidRenderer) (Object) this;
            self.render(entity, entityYaw, partialTicks, poseStack,
                    com.maidsmart.client.MaidGhostRender.wrap(bufferIn, self, maid), packedLightIn);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("MaidGhostTetherMixin", t); // 幽灵化这一遍失败：这一帧就当她没画（下一帧照常试）；把姿态栈补回、并留一行痕
            com.maidsmart.client.MaidRenderGuard.noteSwallowed("武装拴绳半透明重绘（女仆）", t);
        } finally {
            com.maidsmart.client.MaidGhostRender.end();
            com.maidsmart.client.MaidRenderGuard.restore(poseStack, guardDepth);
        }
    }
}
