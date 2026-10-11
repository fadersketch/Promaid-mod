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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 把官方那圈"激流旋转特效"原样复刻给女仆。
 *
 * 【它不是粒子、也不是模型的一部分】它是原版挂在 {@code PlayerRenderer} 上的独立渲染图层
 * {@code SpinAttackEffectLayer}：门槛只有 {@code LivingEntity.isAutoSpinAttack()}，然后贴
 * {@code textures/entity/trident_riptide.png}（白色斜纹、透明底），用三个 0.75×i 缩放、三种角速度
 * （45/50/55 度每 tick）反向自转的大方块叠出来（上下错开 0.6 格；i=0 时 s=0 退化不可见，实际两层）。
 * 所以"白色斜纹拼成的旋转方框"= 一个贴白色斜纹贴图的大方块反向自转，**完全可以照抄给女仆**。
 * 【复刻】逐行照抄角度/缩放/位移，只把"画在哪"换成女仆：① {@link LayerMaidSpinAttack}（Bedrock 模型）
 * ——图层在 {@code EntityMaidRenderer} 的图层链里，矩阵与玩家那边**同一空间**（原版模型空间），原样
 * 照抄即可；② {@link LayerMaidSpinAttackGecko}（Gecko 模型）——Gecko 图层跑在"格、Y 朝上、原点在
 * 脚下"的空间（在 {@code setupRotations} 之后调用），先 {@code scale(-1,-1,1)} 翻进原版模型空间再照抄。
 * 显示条件 = **女仆正在旋转突进**（{@link MaidTridentSpinBehavior} 突进状态）且原版旋转标志位已置起
 * ——与"她什么时候会转"完全一致，不会"没转却冒特效"。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidSpinAttackEffect {

    /** 原版贴图（两版同名同路径，`assets/minecraft/textures/entity/trident_riptide.png`） */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation("textures/entity/trident_riptide.png");

    private static final String BOX = "box";

    /** 烘好的方块部件（静态持有，构造一次；几何与贴图参数照抄原版） */
    private static final ModelPart BOX_PART = buildBox();

    private MaidSpinAttackEffect() {
    }

    private static ModelPart buildBox() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.m_171576_();
        root.m_171599_(BOX, CubeListBuilder.m_171558_().m_171514_(0, 0)
                        .m_171481_(-8.0f, -16.0f, -8.0f, 16.0f, 32.0f, 16.0f),
                PartPose.f_171404_);
        return LayerDefinition.m_171565_(mesh, 64, 64).m_171564_().m_171324_(BOX);
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
        return mob instanceof EntityMaid maid && maid.m_21209_() && maid.f_20919_ <= 0;
    }

    /**
     * 照抄原版那三层方块。调用方负责把 poseStack 摆到**原版模型空间**
     * （Y 朝下、X 镜像；Bedrock 图层本来就在，Gecko 图层需要先 `scale(-1,-1,1)`）。
     *
     * @param ageInTicks 原版传入的 `p_117533_`（自转相位就是它，别换成 tickCount）
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffer, int light, float ageInTicks) {
        VertexConsumer vc = buffer.m_6299_(RenderType.m_110458_(TEXTURE));
        for (int i = 0; i < 3; ++i) {
            poseStack.m_85836_();
            poseStack.m_252781_(Axis.f_252436_.m_252977_(ageInTicks * (float) (-(45 + i * 5))));
            float s = 0.75f * (float) i;
            poseStack.m_85841_(s, s, s);
            poseStack.m_252880_(0.0f, -0.2f + 0.6f * (float) i, 0.0f);
            BOX_PART.m_104301_(poseStack, vc, light, OverlayTexture.f_118083_);
            poseStack.m_85849_();
        }
    }
}
