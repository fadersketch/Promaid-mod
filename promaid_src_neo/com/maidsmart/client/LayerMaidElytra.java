package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.maidsmart.combat.MaidFlightKit;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * v1.2.0（1.21.1）：飞行作战——女仆背后的鞘翅渲染层（**Bedrock 模型**走这条）。
 *
 * 【为什么需要自己写】TLM 本体零鞘翅支持（jar 内 0 个 elytra 引用），胸甲穿了鞘翅
 * 不会自动显示翅膀。
 *
 * 【变换照抄原版 ElytraLayer（字节码实证）】原版对任意 LivingEntityRenderer 的模型
 * 只做 `pushPose + translate(0,0,0.125) + setupAnim + renderToBuffer`——翅膀位置由
 * ElytraModel 自身的坐标决定，不依赖模型骨架。所以【不要】去锚定 body 骨骼
 * （BedrockPart.translateAndRotate 在 Y 翻转空间里会把翅膀移到身体下方，反而看不见）。
 *
 * 【显示条件 = 模式已激活】任务为飞行作战 且 胸甲槽真穿着可用鞘翅（equip 会穿上）。
 * 与玩家穿戴鞘翅一致：穿上即显示；滑翔时 ElytraModel.setupAnim 读 isFallFlying()
 * 自动展开，落地自然收拢。刻意不依赖滑翔标志位（起降/被击落会反复开关，导致闪烁）。
 *
 * 注：Gecko 模型的女仆走 GeckoEntityMaidRenderer，不经过本层——见 LayerMaidElytraGecko。
 */
public class LayerMaidElytra extends RenderLayer<Mob, BedrockModel<Mob>> {

    private static final ResourceLocation WINGS =
            ResourceLocation.withDefaultNamespace("textures/entity/elytra.png");

    /** 原版 `PlayerRenderer.scale`：抵消模型缩放后乘它 = 与玩家鞘翅等大 */
    private static final float VANILLA_PLAYER_SCALE = 0.9375f;
    /** 实测四百九十一：尺寸 = 与玩家等大（1.0 倍；四百八十曾是 0.85 倍，用户要求改回） */
    private static final float SIZE_FACTOR = 1.0f;
    /**
     * 实测四百九十一：整体上移 0.7 格（用户要求；四百八十曾是 1.0 格）。
     *
     * 为什么是 0.7：winefox 的 `ElytraLocator` 枢轴在模型 y=30.9644px，而肩线
     * （`LeftArm` 枢轴 29.1、手臂方块顶 30.436px）换算成格后落在同一高度——**挂点
     * 本身就在肩线上**。所以"鞘翅顶端对准肩膀"由本上移量单独决定，把四百八十的
     * 1.0 格减掉用户目视偏高的 0.3 格即得。
     *
     * 本层 Y 朝下，故施加时为 -Y。
     */
    private static final float LIFT_BLOCKS = 0.7f;

    private final ElytraModel<Mob> elytraModel;
    /** v1.2.0 实测四百六十九：读模型缩放（render_entity_scale）用于抵消 */
    private final EntityMaidRenderer modelRenderer;

    public LayerMaidElytra(EntityMaidRenderer renderer, EntityRendererProvider.Context context) {
        super(renderer);
        this.modelRenderer = renderer;
        this.elytraModel = new ElytraModel<>(context.bakeLayer(ModelLayers.ELYTRA));
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!(mob instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
            return;
        }
        if (maid.isInvisible() || !MaidFlightKit.isFlightTask(maid)) {
            return;
        }
        if (!MaidFlightKit.isUsableElytra(maid.getItemBySlot(EquipmentSlot.CHEST))) {
            return;
        }
        // v1.2.0 实测五百零七：取出胸甲槽的鞘翅栈——既用于下方的光泽判定
        // （hasFoil），也让"显示背部物品"判定与 1.20.1 侧写法保持一致。
        ItemStack chest = maid.getItemBySlot(EquipmentSlot.CHEST);
        // v1.2.0 实测五百零四：绑定 TLM 的「显示背部物品」（女仆配置页那一项，
        // `gui.touhou_little_maid.maid_config.show_back_item`）——鞘翅画在女仆背上，
        // 属于"背部物品"，玩家关掉这一项时就不该再看到翅膀。
        // 取值：EntityMaid.getConfigManager().isShowBackItem()（同步实体数据
        // BACK_ITEM_SHOW，默认 true → 与旧行为一致）。javap 实证两树 API 同名同签名。
        if (!maid.getConfigManager().isShowBackItem()) {
            return;
        }

        poseStack.pushPose();
        // v1.2.0 实测四百八十三：与 1.20.1 对齐——pushPose 后必须 try/finally 兜 popPose，
        // 中途抛异常（模型/渲染类型异常）否则会漏 pop、污染后续渲染矩阵。
        try {
            poseStack.translate(0.0f, 0.0f, 0.125f); // 与原版 ElytraLayer 完全一致
            // v1.2.0 实测四百六十九：抵消 EntityMaidRenderer.scale 的实体缩放（酒狐等模型常 <1，
            // 不抵消鞘翅就比玩家小一圈），再对齐玩家缩放 → 与玩家等大。
            float modelScale = 1.0f;
            try {
                modelScale = this.modelRenderer.getMainInfo().getRenderEntityScale();
            } catch (Throwable ignored) {
            }
            if (modelScale <= 0.0f) {
                modelScale = 1.0f;
            }
            // v1.2.0 实测四百九十一【尺寸 + 位置，用户要求】：
            // ① 尺寸回到"与玩家等大"（0.9375 = 原版 PlayerRenderer.scale；四百八十的 0.85 倍已取消）；
            // ② 整体上移 0.7 格——本层已在原版模型空间（translate(0,-1.501,0) 之后，Y 朝下），
            //    所以"向上"是 -Y；本层矩阵已被实体缩放（render_entity_scale）乘过，
            //    故除以 modelScale 才是实打实的世界格（scale=1 的模型即为 1）。
            //    放在 scale(k) 之前 → 不受鞘翅自身尺寸缩放影响。
            //    ElytraModel 的翅根枢轴就在 y=0，顶端即挂点位置，与尺寸缩放无关 → 两个常量互不干扰。
            poseStack.translate(0.0f, -LIFT_BLOCKS / modelScale, 0.0f);
            float k = SIZE_FACTOR * VANILLA_PLAYER_SCALE / modelScale;
            poseStack.scale(k, k, k);
            this.elytraModel.setupAnim(mob, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // 展翅：飞行任务且离地就强制展翅（原版按下落速度收拢翅膀，同步间隙会露出折叠态）
            ElytraSpread.forceSpread(this.elytraModel,
                    MaidFlightKit.isFlightTask(maid) && !maid.onGround());
            // v1.2.0 实测五百零七【改回按附魔判定】：原版 `getArmorFoilBuffer` 的第 3 参
            // 就是 "hasFoil"（字节码实证：为 true 时复合一层 `armorEntityGlint` 光泽层）。
            // 实测五百零四曾固定传 true（常亮），反馈指出那不对（"不管附没附魔都渲染出了
            // 附魔的样子"）→ 现在传 `chest.hasFoil()`，与原版 ElytraLayer 完全同款：
            // 只有真附魔过的鞘翅才有光泽。
            // equip() 只把鞘翅原样搬进 CHEST 槽（setItemSlot），不改写 NBT，判定不会被污染。
            var vc = ItemRenderer.getArmorFoilBuffer(buffer,
                    RenderType.armorCutoutNoCull(WINGS), chest.hasFoil());
            this.elytraModel.renderToBuffer(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
        } finally {
            poseStack.popPose();
        }
    }
}
