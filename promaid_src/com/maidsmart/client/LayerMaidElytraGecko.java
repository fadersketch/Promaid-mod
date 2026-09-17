package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.GeckoEntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.ILocationBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.GeoLayerRenderer;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.maidsmart.combat.MaidFlightKit;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.2.0（1.20.1）：飞行作战——鞘翅图层（**GeckoLib 模型** 女仆走这条）。
 *
 * 【为什么必须单独做一层】EntityMaidRenderer.m_7392_ 在 `mainInfo.isGeckoModel()` 为真时
 * 把渲染交给 GeckoEntityMaidRenderer 并直接 return，Bedrock 的 RenderLayer 链根本不执行。
 *
 * 【用的就是玩家那套模型】`ElytraModel` + `textures/entity/elytra.png`，与原版
 * `ElytraLayer.m_6494_` 同源。真正要解决的不是"换个模型"，而是**挂到哪、缩多少**。
 *
 * 【v1.2.0 实测四百八十：修「除 winefox 外都渲染不出鞘翅」】
 * 实测全部 28 个 Gecko 女仆模型的骨骼（模型 JSON 扫描）：
 * ```
 *   Elytra            17/28   ← 全部是 winefox 家族
 *   ElytraLocator     17/28   ← 同上
 *   UpperBody         26/28
 *   Root              28/28
 * ```
 * 旧版只找 `ElytraLocator` / `Elytra`，于是**没有这两根骨骼的 7 个模型**
 * （foxmaid / hailuo_new_year / sta / winefox_hanfu / winefox_mini / zhiban_hanfu /
 * zhiban_new_year）全部掉进"硬编码兜底"，而那个兜底是从**原版** ElytraLayer 抄来的
 * `(-1,-1,1) + translate(0,-1.501,0)`——那是**原版模型空间**的补偿；Gecko 图层栈
 * 根本不在原版模型空间（单位=格、Y 朝上、原点在脚下），于是兜底把翅膀顶到脚下/身体里
 * → 看不见。这就是"只有 winefox 能显示"的根。
 *
 * 【现在的锚点策略：永不掉进兜底】按优先级找骨骼，找不到专用挂点就锚到**一定有**的
 * 躯干/根骨骼，再补一段**从真实模型统计出来的平均偏移**（n=17 样本，单位：格）：
 * ```
 *   ElytraLocator / Elytra  → 偏移 (0, 0, 0)              （17/28）
 *   UpperBody               → 偏移 (0, +0.4310, +0.2011)  （26/28）
 *   Root                    → 偏移 (0, +1.8596, +0.2022)  （28/28，兜底必中）
 * ```
 * 偏移在**骨骼空间**（Y 朝上）里施加，所以是 +Y。
 *
 * 【v1.2.0 实测四百九十一：尺寸与位置（用户要求）】
 * ① 尺寸 = 与玩家**等大**：`k = 1.0 × 0.9375 / modelScale`
 *    （0.9375 是原版 `PlayerRenderer.m_7546_`，即"与玩家等大"的系数；四百八十的 0.85 倍已取消）；
 * ② 整体**上移 0.7 格**（四百八十曾是 1.0 格）：在翻转后的空间里 `+Y` 就是世界里向上，
 *    故 `translate(0, +0.7, 0)`（放在 `scale(k)` **之前**，所以是实打实的世界格）。
 *    挂点 `ElytraLocator` 换算后本就落在肩线上 → 顶端位置只由本上移量决定。
 */
@OnlyIn(Dist.CLIENT)
public class LayerMaidElytraGecko extends GeoLayerRenderer<Mob, GeckoEntityMaidRenderer<Mob>> {

    private static final ResourceLocation WINGS = new ResourceLocation("textures/entity/elytra.png");

    /** 原版 `PlayerRenderer.m_7546_` 的值：抵消模型缩放后乘它 = 与玩家鞘翅等大 */
    private static final float VANILLA_PLAYER_SCALE = 0.9375f;
    /** 实测四百九十一：尺寸 = 与玩家等大（1.0 倍；四百八十曾是 0.85 倍，用户要求改回） */
    private static final float SIZE_FACTOR = 1.0f;
    /**
     * 实测四百九十一：整体上移 0.7 格（用户要求；四百八十曾是 1.0 格）。
     *
     * 挂点骨骼 `ElytraLocator` 的枢轴（winefox：y=30.9644px）换算成格后本就落在
     * 肩线上（`LeftArm` 枢轴 29.1、手臂方块顶 30.436px），所以"鞘翅顶端对准肩膀"
     * 完全由本上移量决定——把四百八十的 1.0 格减去用户目视偏高的 0.3 格即得。
     */
    private static final float LIFT_BLOCKS = 0.7f;

    /**
     * 挂点骨骼优先级 + 相对该骨骼的补偿偏移（**格**，骨骼空间 Y 朝上）。
     *
     * 偏移不是拍的：取全部 17 个"同时有 Elytra 与目标骨骼"的模型，
     * 算 `Elytra.pivot - bone.pivot` 的平均值再 /16（px → 格）。
     * 数据见 changelog 实测四百八十。
     */
    private static final Anchor[] ANCHORS = {
            new Anchor("ElytraLocator", 0.0f, 0.0f),        // 专用挂点（模型作者约定）
            new Anchor("Elytra", 0.0f, 0.0f),               // 专用挂点（父骨骼）
            new Anchor("UpperBody", 0.4310f, 0.2011f),      // 躯干（26/28 有）
            new Anchor("Root", 1.8596f, 0.2022f),           // 根（28/28 必有，最后兜底）
    };

    /** 锚定失败只报一次，避免刷日志（也便于实测时确认是否真的没找到骨骼） */
    private static final AtomicBoolean ANCHOR_WARNED = new AtomicBoolean(false);

    private record Anchor(String bone, float dy, float dz) {
    }

    private final ElytraModel<Mob> elytraModel;

    @SuppressWarnings("unchecked")
    public LayerMaidElytraGecko(GeckoEntityMaidRenderer<?> renderer, EntityRendererProvider.Context context) {
        super((GeckoEntityMaidRenderer<Mob>) renderer);
        this.elytraModel = new ElytraModel<>(context.m_174023_(ModelLayers.f_171141_));
    }

    private LayerMaidElytraGecko(GeckoEntityMaidRenderer<Mob> renderer, ElytraModel<Mob> model) {
        super(renderer);
        this.elytraModel = model;
    }

    @Override
    public GeoLayerRenderer<Mob, GeckoEntityMaidRenderer<Mob>> copy(GeckoEntityMaidRenderer<Mob> renderer) {
        return new LayerMaidElytraGecko(renderer, this.elytraModel);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Mob mob,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(mob instanceof EntityMaid maid)) {
            return;
        }
        if (maid.m_20145_() || !MaidFlightKit.isFlightTask(maid)) {
            return;
        }
        ItemStack chest = maid.m_6844_(EquipmentSlot.CHEST);
        if (!MaidFlightKit.isUsableElytra(chest)) {
            return;
        }
        // v1.2.0 实测五百零四：绑定 TLM 的「显示背部物品」（女仆配置页那一项，
        // `gui.touhou_little_maid.maid_config.show_back_item`）——鞘翅画在女仆背上，
        // 属于"背部物品"，玩家关掉这一项时就不该再看到翅膀。TLM 自己的
        // LayerMaidBackItem / GeckoLayerMaidBackItem 就是在这个条件下绘制的。
        // 取值：EntityMaid.getConfigManager().isShowBackItem()（同步实体数据
        // BACK_ITEM_SHOW，默认 true → 与旧行为一致）。javap 实证两树 API 同名同签名。
        if (!maid.getConfigManager().isShowBackItem()) {
            return;
        }

        float modelScale = 1.0f;
        try {
            modelScale = getGeoEntity(mob).getMaidInfo().getRenderEntityScale();
        } catch (Throwable ignored) {
        }
        if (modelScale <= 0.0f) {
            modelScale = 1.0f;
        }

        poseStack.m_85836_();
        try {
            // ① 锚到骨骼（含通用兜底骨骼 + 统计补偿偏移），坐标系 = 格、Y 朝上
            anchorToBackBone(poseStack, mob);
            // ② 骨骼空间（Y 朝上、X 已镜像）→ 原版 ElytraModel 期望的"Y 朝下、X 镜像"
            poseStack.m_85841_(-1.0f, -1.0f, 1.0f);
            // ③ 用户要求：整体上移 0.7 格（四百八十曾是 1.0 格）。
            //    【为什么是 -Y】此刻已在 ElytraModel 的局部空间里，该空间 Y 朝下
            //    （原版模型约定：-Y 才向上），所以"向上" = -Y。
            //    【为什么除以 modelScale】本层所处的矩阵已被实体缩放（render_entity_scale）
            //    乘过，translate 在最内层施加会被该缩放放大，除以它才是实打实的世界格。
            //    放在 scale(k) 之前 → 不受鞘翅自身尺寸影响。
            //    v1.2.0 实测四百八十三：与 1.21.1 对齐（旧版此处误写 +Y 且未除缩放 →
            //    方向相反、位移量还随模型缩放漂移）。
            poseStack.m_252880_(0.0f, -LIFT_BLOCKS / modelScale, 0.0f);
            // ④ 尺寸 = 与玩家等大（抵消模型缩放，再对齐玩家缩放）
            float k = SIZE_FACTOR * VANILLA_PLAYER_SCALE / modelScale;
            poseStack.m_85841_(k, k, k);
            // ⑤ 原版 ElytraLayer 的背后微调
            poseStack.m_252880_(0.0f, 0.0f, 0.125f);

            this.elytraModel.m_6973_(mob, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // 展翅：飞行任务且离地就张开（不只认滑翔，避免同步间隙露出折叠态）
            ElytraSpread.forceSpread(this.elytraModel,
                    MaidFlightKit.isFlightTask(maid) && !maid.m_20096_());
            // v1.2.0 实测五百零七【改回按附魔判定】：`m_115184_` 的第 4 参就是原版的
            // "hasFoil"（字节码实证：为 true 时复合一层 armor_entity_glint 光泽层）。
            // 实测五百零四曾固定传 true（常亮），反馈指出那不对（"不管附没附魔都渲染出了
            // 附魔的样子"）→ 现在传 `chest.m_41790_()`（= ItemStack.hasFoil），
            // 与原版 ElytraLayer 完全同款：只有真附魔过的鞘翅才有光泽。
            // equip() 只把鞘翅原样搬进 CHEST 槽，不改写 NBT，判定不会被污染。
            var vc = ItemRenderer.m_115184_(buffer,
                    RenderType.m_110431_(WINGS), false, chest.m_41790_());
            this.elytraModel.m_7695_(poseStack, vc, light, OverlayTexture.f_118083_,
                    1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        } finally {
            poseStack.m_85849_();
        }
    }

    /**
     * 把 poseStack 锚到"背部挂点"：按 {@link #ANCHORS} 优先级找骨骼，用
     * `prepMatrixForLocator` 沿祖先链定位（含躯干动画），再补该骨骼到 Elytra 挂点的
     * 平均偏移（骨骼空间，Y 朝上）。
     *
     * 与 TLM `AnimatedGeoModel.getLocatorHierarchy` 内部同款（该方法 private，
     * 这里按 `geoBone().parent()` 自己回溯，得到"根 → 目标"的顺序）。
     *
     * @return true = 找到并已应用
     */
    private boolean anchorToBackBone(PoseStack poseStack, Mob mob) {
        try {
            var loc = getLocationModel(mob);
            if (!(loc instanceof AnimatedGeoModel model)) {
                return false;
            }
            var bones = model.bones();
            for (Anchor anchor : ANCHORS) {
                AnimatedGeoBone target = bones.get(anchor.bone());
                if (target == null) {
                    continue;
                }
                List<ILocationBone> chain = new ArrayList<>();
                AnimatedGeoBone cur = target;
                for (int guard = 0; cur != null && guard < 64; guard++) {
                    chain.add(cur);
                    GeoBone parent = cur.geoBone() == null ? null : cur.geoBone().parent();
                    if (parent == null) {
                        break;
                    }
                    cur = bones.get(parent.name());
                }
                java.util.Collections.reverse(chain);
                RenderUtils.prepMatrixForLocator(poseStack, chain);
                // 补偿偏移（骨骼空间 Y 朝上：+dy = 向上）
                if (anchor.dy() != 0.0f || anchor.dz() != 0.0f) {
                    poseStack.m_252880_(0.0f, anchor.dy(), anchor.dz());
                }
                return true;
            }
            if (ANCHOR_WARNED.compareAndSet(false, true)) {
                com.maidsmart.tool.PromaidLog.log("鞘翅挂点",
                        "模型没有 ElytraLocator/Elytra/UpperBody/Root 任一骨骼，鞘翅位置退回原点");
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
