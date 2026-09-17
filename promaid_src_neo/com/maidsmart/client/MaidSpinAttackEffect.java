package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * v1.2.0 实测五百四十三【把官方那圈"激流旋转特效"原样复刻给女仆】。
 *
 * ── 需求原文（含用户的猜测）──
 * "激流的特效我有一个猜测。激流产生的视觉效果并不是特效，而是嵌入玩家的建模……"
 *
 * ── 猜测对了一半，而且是个好消息 ──
 * 它**不是**粒子（我早先查过旋转附近没有任何 `addParticle`），但也**不是**玩家模型/动作的一部分 ——
 * 它是原版挂在 `PlayerRenderer` 上的一个**独立渲染图层** `SpinAttackEffectLayer`，源码（两版同构）：
 * <pre>
 *   if (!entity.isAutoSpinAttack()) return;                       // 唯一门槛，LivingEntity 的方法
 *   VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(
 *           ResourceLocation.withDefaultNamespace("textures/entity/trident_riptide.png")));
 *   for (int i = 0; i &lt; 3; ++i) {
 *       poseStack.pushPose();
 *       poseStack.mulPose(Axis.YP.rotationDegrees(ageInTicks * -(45 + i * 5))); // 45/50/55 度每 tick，三个反向自转
 *       float s = 0.75f * i;                                    // i=0 时 s=0（退化不可见），实际两层
 *       poseStack.scale(s, s, s);
 *       poseStack.translate(0.0f, -0.2f + 0.6f * i, 0.0f);      // 上下错开 0.6 格
 *       box.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
 *       poseStack.popPose();
 *   }
 *   static LayerDefinition createLayer() {
 *       // 一个 16×32×16 的大方块，贴的就是上面那张"白色斜纹、透明底"的贴图
 *       root.addOrReplaceChild("box", CubeListBuilder.create().texOffs(0, 0)
 *               .addBox(-8.0f, -16.0f, -8.0f, 16.0f, 32.0f, 16.0f), PartPose.ZERO);
 *       return LayerDefinition.create(mesh, 64, 64);
 *   }
 * </pre>
 * 也就是说：截图里那圈"白色斜纹拼成的旋转方框"＝**一个贴着白色斜纹贴图的大方块、以三种速度反向自转**。
 * 门槛只是 `LivingEntity.isAutoSpinAttack()`，所以**完全可以照抄给女仆**，不需要改动玩家那边的任何东西。
 *
 * ── 复刻说明 ──
 * 逐行照抄上面那段（角度、缩放、位移一模一样），只把"画在哪"换成女仆：
 * <ul>
 *   <li>**Bedrock 模型**（{@link LayerMaidSpinAttack}，挂在 `EntityMaidRenderer` 上）：
 *       图层所处的矩阵与玩家那边**完全同一个空间**（原版模型空间），原样照抄即可。</li>
 *   <li>**Gecko 模型**（{@link LayerMaidSpinAttackGecko}）：Gecko 图层跑在"格、Y 朝上、
 *       原点在脚下"的空间（在 `setupRotations` 之后调用，见反编译），所以先
 *       `scale(-1,-1,1)` 翻进原版模型空间再照抄原版那两步。</li>
 * </ul>
 */
public final class MaidSpinAttackEffect {

    /** 原版贴图（两版同名同路径，`assets/minecraft/textures/entity/trident_riptide.png`） */
    public static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/trident_riptide.png");

    private static final String BOX = "box";

    /** 烘好的方块部件（静态持有，构造一次；几何与贴图参数照抄原版） */
    private static final ModelPart BOX_PART = buildBox();

    private MaidSpinAttackEffect() {
    }

    private static ModelPart buildBox() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(BOX, CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-8.0f, -16.0f, -8.0f, 16.0f, 32.0f, 16.0f),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64).bakeRoot().getChild(BOX);
    }

    /**
     * 是否该画这圈特效。
     *
     * 【判据用原版的标志位，不用本模组的状态表】本模组那份"是否正在突进"的 Map 在**服务端**，
     * 客户端读不到；而原版 `isAutoSpinAttack()` 读的是实体数据里已同步的标志位（服务端置位后
     * 客户端就有），所以这条既准确又跨端可用。突进结束时我们也会显式清它（见
     * {@code MaidTridentSpinBehavior.finish}），不会留下"没转却一直冒特效"。
     */
    public static boolean shouldRender(Mob mob) {
        return mob instanceof EntityMaid maid && maid.isAutoSpinAttack() && maid.deathTime <= 0;
    }

    /**
     * 照抄原版那三层方块。调用方负责把 poseStack 摆到**原版模型空间**
     * （Y 朝下、X 镜像；Bedrock 图层本来就在，Gecko 图层需要先 `scale(-1,-1,1)`）。
     *
     * @param ageInTicks 原版传入的 `p_117533_`（自转相位就是它，别换成 tickCount）
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffer, int light, float ageInTicks) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (int i = 0; i < 3; ++i) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(ageInTicks * (float) (-(45 + i * 5))));
            float s = 0.75f * (float) i;
            poseStack.scale(s, s, s);
            poseStack.translate(0.0f, -0.2f + 0.6f * (float) i, 0.0f);
            BOX_PART.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
    }
}
