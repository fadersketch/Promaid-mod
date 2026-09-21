package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.BedrockModel;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * v1.2.0（1.20.1）：飞行作战——鞘翅图层（**Bedrock 模型** 女仆走这条）。
 *
 * 【显示条件】任务为飞行作战 **或 正在滑翔**（六百一十一 放宽，见下）且胸甲槽穿着可用鞘翅
 * （三件齐备时 MaidFlightKit.equip 会穿上）= "激活即显示"，与玩家穿戴鞘翅一致。
 *
 * ── v1.2.2 实测六百一十一【判据加上"正在滑翔"】──
 * 用户反馈："飞行跟随……动作没有换成空袭飞行的动作。"飞行跟随的女仆**不是飞行任务**
 * （她在跟主人，任务可能是空闲/搭路），旧判据只看 `isFlightTask` → 她滑翔时背上一片空白。
 * 现在改成 `MaidFlightKit.isFlightVisual(maid)`（= `isFlightTask || isGliding`）：空袭那边一字不变（它本来就在滑翔），
 * 跟着飞的她与"玩家给她鞘翅、她自己滑起来"的情况也一并认——滑翔位是**同步过的共享标志位 7**，
 * 所以多人下客户端也认得出（不依赖服务端那些状态表）。
 *
 * 【照抄原版 ElytraLayer 的渲染配方】1.20.1 的 `ElytraLayer.m_6494_` 就是
 * `pushPose + translate(0,0,0.125) + setupAnim + getArmorFoilBuffer + renderToBuffer`；
 * 本层所处的 poseStack 与 vanilha 图层完全一致（EntityMaidRenderer 走
 * `super.m_7392_`，即 `scale(-1,-1,1)` → `scale(entity)` → `translate(0,-1.501,0)` 之后），
 * 所以不需要任何额外空间换算。翅膀张合由 `ElytraModel.setupAnim` 依据
 * `isFallFlying()`（共享标志位 7）决定：滑翔=张开、落地/收翅=收拢。
 *
 * 注意：1.20.1 的 `RenderLayer` 抽象方法在 SRG 环境里叫 `m_6494_`（javap 实证），
 * 参数是 `Entity` 而不是 `Mob`，必须按这个名字覆写。
 */
@OnlyIn(Dist.CLIENT)
public class LayerMaidElytra extends RenderLayer<Mob, BedrockModel<Mob>> {

    private static final ResourceLocation WINGS = new ResourceLocation("textures/entity/elytra.png");

    /** 原版 `PlayerRenderer.m_7546_`：抵消模型缩放后乘它 = 与玩家鞘翅等大 */
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
        this.elytraModel = new ElytraModel<>(context.m_174023_(ModelLayers.f_171141_));
    }

    @Override
    public void m_6494_(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                        float limbSwing, float limbSwingAmount, float partialTick,
                        float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (maid.m_20145_() || !MaidFlightKit.isFlightVisual(maid)) {
            return;
        }
        ItemStack chest = maid.m_6844_(EquipmentSlot.CHEST);
        if (!MaidFlightKit.isUsableElytra(chest)) {
            return;
        }
        // v1.2.0 实测五百零四：绑定 TLM 的「显示背部物品」（maid 配置页那一项，
        // `gui.touhou_little_maid.maid_config.show_back_item`）——鞘翅画在女仆背上，
        // 属于"背部物品"，玩家关掉这一项时就不该再看到翅膀。
        // 取值：EntityMaid.getConfigManager().isShowBackItem()（同步实体数据
        // BACK_ITEM_SHOW，默认 true → 与旧行为一致）。javap 实证两树 API 同名同签名。
        if (!maid.getConfigManager().isShowBackItem()) {
            return;
        }
        poseStack.m_85836_();
        try {
            poseStack.m_252880_(0.0f, 0.0f, 0.125f);
            // v1.2.0 实测四百六十九：抵消 EntityMaidRenderer.m_7546_ 的实体缩放（酒狐等模型常
            // <1，不抵消鞘翅就比玩家小一圈），再对齐玩家缩放 → 与玩家等大。
            float modelScale = 1.0f;
            try {
                modelScale = this.modelRenderer.getMainInfo().getRenderEntityScale();
            } catch (Throwable ignored) {
            }
            if (modelScale <= 0.0f) {
                modelScale = 1.0f;
            }
            // v1.2.0 实测四百九十一【尺寸 + 位置，用户要求】：
            // ① 尺寸回到"与玩家等大"（0.9375 = 原版 PlayerRenderer.m_7546_；四百八十的 0.85 倍已取消）；
            // ② 整体上移 0.7 格——本层已在原版模型空间（m_252880_(0,-1.501,0) 之后，Y 朝下），
            //    所以"向上"是 -Y。放在 scale(k) 之前 → 不受鞘翅自身尺寸影响，是实打实的世界格。
            //    ElytraModel 的翅根枢轴就在 y=0，顶端即挂点位置，与尺寸缩放无关 → 两个常量互不干扰。
            // v1.2.0 实测四百八十三：与 1.21.1 对齐——除以 modelScale。
            // 本层矩阵已被实体缩放（render_entity_scale）乘过，translate 在最内层施加
            // 会被该缩放放大，除以它才是实打实的 1 个世界格（旧版漏除，非 1.0 缩放模型上移量失真）。
            poseStack.m_252880_(0.0f, -LIFT_BLOCKS / modelScale, 0.0f);
            float k = SIZE_FACTOR * VANILLA_PLAYER_SCALE / modelScale;
            poseStack.m_85841_(k, k, k);
            this.elytraModel.m_6973_(mob, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // v1.2.0：飞行任务且离地就强制展翅（原版按下落速度收拢，同步间隙也会露出折叠态）
            com.maidsmart.client.ElytraSpread.forceSpread(this.elytraModel,
                    MaidFlightKit.isFlightVisual(maid) && !maid.m_20096_());
            // v1.2.0 实测五百零七【改回按附魔判定】：`m_115184_` 的第 4 参就是原版的
            // "hasFoil"（字节码实证：为 true 时复合一层 armor_entity_glint 光泽层）。
            // 实测五百零四曾固定传 true（常亮），当时是按"本模组手册/排班表常亮"的口径
            // 顺手统一，但反馈指出这不对："现在对于鞘翅附魔的渲染不分对象，不管附没附魔
            // 都渲染出了附魔的样子。" → 现在传 `chest.m_41790_()`（= ItemStack.hasFoil），
            // 与原版 ElytraLayer 完全同款：**只有真附魔过的鞘翅才有光泽**。
            // 已确认 equip() 只把鞘翅原样搬进 CHEST 槽（m_8061_/setItemSlot），不改写 NBT，
            // 所以这个判定不会被本模组污染。
            var vc = ItemRenderer.m_115184_(buffer,
                    RenderType.m_110431_(WINGS), false, chest.m_41790_());
            this.elytraModel.m_7695_(poseStack, vc, light, OverlayTexture.f_118083_,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        } finally {
            poseStack.m_85849_();
        }
    }
}
