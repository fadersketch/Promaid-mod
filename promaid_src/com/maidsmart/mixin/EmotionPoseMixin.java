package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.maidsmart.emotion.EmotionPoseClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * 情绪姿势动画层（v1.1.0 实测二百八十六）——机制移植自 HeartPact
 * （TouhouLittleMaid-HeartPact，开源实现）：
 * mixin 到 TLM HardcodedAnimationManger 的 playMaidAnimation（Bedrock 模型）/
 * playGeckoMaidAnimation（Gecko 模型）RETURN，在最终骨骼覆盖层写入
 * 摸头/抱抱姿势角度——TLM 1.5.3 本体没有这两个动作动画。
 *
 * - 摸摸头：头部左右轻摆（sin 波）+ 身体微微前倾，2 秒淡入淡出
 * - 抱抱：双臂张开环抱（肩/大臂/前臂三段弧线）+ 身体前倾，4 秒淡入淡出；
 *   抱抱激活时摸头退化为"头部轻摆"叠加（HeartPact 同款降级）
 * - 骨骼名称多套兼容（TLM 模型包命名不统一，Gecko 路径模糊查找 head）
 */
@Mixin(value = com.github.tartaricacid.touhoulittlemaid.client.animation.HardcodedAnimationManger.class, remap = false)
public abstract class EmotionPoseMixin {

    @Inject(method = "playMaidAnimation", at = @At("RETURN"))
    private static void maidsmart$bedrockPose(IMaid maid, HashMap<String, ModelRendererWrapper> models,
                                              float limbSwing, float limbSwingAmount, float ageInTicks,
                                              float netHeadYaw, float headPitch, CallbackInfo ci) {
        EntityMaid entityMaid = maid.asStrictMaid();
        if (entityMaid == null) {
            return;
        }
        float hug = EmotionPoseClient.hugProgress(entityMaid.m_20148_());
        float pat = EmotionPoseClient.petProgress(entityMaid.m_20148_());
        if (hug > 0.0f) {
            applyBedrockHug(models, hug);
            // 抱抱中摸头：只叠加头部轻摆
            if (pat > 0.0f) {
                applyBedrockHeadSway(models.get("head"), ageInTicks, pat, 0.6f);
            }
            return;
        }
        if (pat > 0.0f) {
            applyBedrockStandalonePat(models, pat, ageInTicks);
        }
    }

    @Inject(method = "playGeckoMaidAnimation", at = @At("RETURN"))
    private static void maidsmart$geckoPose(IMaid maid, AnimatedGeoModel model,
                                            float limbSwing, float limbSwingAmount, float ageInTicks,
                                            float netHeadYaw, float headPitch, CallbackInfo ci) {
        EntityMaid entityMaid = maid.asStrictMaid();
        if (entityMaid == null) {
            return;
        }
        float hug = EmotionPoseClient.hugProgress(entityMaid.m_20148_());
        float pat = EmotionPoseClient.petProgress(entityMaid.m_20148_());
        if (hug > 0.0f) {
            applyGeckoHug(model, hug);
            if (pat > 0.0f) {
                applyGeckoHeadSway(findGeckoHead(model), ageInTicks, pat, 0.6f);
            }
            return;
        }
        if (pat > 0.0f) {
            applyGeckoStandalonePat(model, pat, ageInTicks);
        }
    }

    /* ---------- Bedrock（ModelRendererWrapper）路径 ---------- */

    /** 站立摸头：身体微前倾 + 头部轻摆顺从 */
    private static void applyBedrockStandalonePat(HashMap<String, ModelRendererWrapper> models,
                                                  float progress, float ageInTicks) {
        ModelRendererWrapper body = models.get("body");
        ModelRendererWrapper upperBody = models.get("upperBody");
        if (body != null) {
            body.setRotateAngleX(0.04f * progress);
        }
        if (upperBody != null) {
            upperBody.setRotateAngleX(0.06f * progress);
        }
        applyBedrockHeadSway(models.get("head"), ageInTicks, progress, 1.0f);
    }

    /** 头部轻摆（顺从地微微偏头），scale=1 独立摸头 / 0.6 抱抱内轻摸 */
    private static void applyBedrockHeadSway(ModelRendererWrapper head, float ageInTicks,
                                             float progress, float scale) {
        if (head == null) {
            return;
        }
        float phase = ageInTicks * 0.42f;
        head.setRotateAngleX(-0.08f * progress + ((float) Math.sin(phase)) * 0.03f * progress * scale);
        head.setRotateAngleY(((float) Math.sin(phase * 0.8f)) * 0.22f * progress * scale);
        head.setRotateAngleZ(((float) Math.sin(phase * 0.8f)) * 0.05f * progress);
    }

    /** 拥抱：肩/大臂/前臂三段弧线环抱 + 身体前倾（数值取自 HeartPact 调优结果） */
    private static void applyBedrockHug(HashMap<String, ModelRendererWrapper> models, float progress) {
        ModelRendererWrapper body = models.get("body");
        ModelRendererWrapper upperBody = models.get("upperBody");
        ModelRendererWrapper head = models.get("head");
        ModelRendererWrapper neck = models.get("neck");
        ModelRendererWrapper shoulderLeft = models.get("shoulderLeft");
        ModelRendererWrapper shoulderRight = models.get("shoulderRight");
        ModelRendererWrapper armLeft = models.get("armLeft");
        ModelRendererWrapper armRight = models.get("armRight");
        ModelRendererWrapper armLeft2 = models.get("armLeft2");
        ModelRendererWrapper armRight2 = models.get("armRight2");
        ModelRendererWrapper skirt = models.get("skirt");

        if (body != null) {
            body.setRotateAngleX(0.10f * progress);
        }
        if (upperBody != null) {
            upperBody.setRotateAngleX(0.18f * progress);
        }
        if (head != null) {
            head.setRotateAngleX(-0.06f * progress);
            head.setRotateAngleY(0.14f * progress);
            head.setRotateAngleZ(0.04f * progress);
        }
        if (neck != null) {
            neck.setRotateAngleX(0.05f * progress);
        }
        if (shoulderLeft != null) {
            shoulderLeft.setRotateAngleX(0.16f * progress);
            shoulderLeft.setRotateAngleY(-0.20f * progress);
            shoulderLeft.setRotateAngleZ(-0.15f * progress);
        }
        if (shoulderRight != null) {
            shoulderRight.setRotateAngleX(0.16f * progress);
            shoulderRight.setRotateAngleY(0.20f * progress);
            shoulderRight.setRotateAngleZ(0.15f * progress);
        }
        if (armLeft != null) {
            armLeft.setRotateAngleX(-1.28f * progress);
            armLeft.setRotateAngleY(-0.62f * progress);
            armLeft.setRotateAngleZ(-0.56f * progress);
        }
        if (armRight != null) {
            armRight.setRotateAngleX(-1.28f * progress);
            armRight.setRotateAngleY(0.62f * progress);
            armRight.setRotateAngleZ(0.56f * progress);
        }
        if (armLeft2 != null) {
            armLeft2.setRotateAngleX(-0.52f * progress);
            armLeft2.setRotateAngleY(0.30f * progress);
            armLeft2.setRotateAngleZ(0.24f * progress);
        }
        if (armRight2 != null) {
            armRight2.setRotateAngleX(-0.52f * progress);
            armRight2.setRotateAngleY(-0.30f * progress);
            armRight2.setRotateAngleZ(-0.24f * progress);
        }
        if (skirt != null) {
            skirt.setRotateAngleX(-0.05f * progress);
        }
    }

    /* ---------- Gecko（AnimatedGeoBone）路径 ---------- */

    private static void applyGeckoStandalonePat(AnimatedGeoModel model, float progress, float ageInTicks) {
        AnimatedGeoBone body = findBone(model, "Body", "body");
        AnimatedGeoBone upperBody = findBone(model, "UpperBody", "upperBody");
        if (body != null) {
            body.setRotationX(0.04f * progress);
        }
        if (upperBody != null) {
            upperBody.setRotationX(0.06f * progress);
        }
        applyGeckoHeadSway(findGeckoHead(model), ageInTicks, progress, 1.0f);
    }

    private static void applyGeckoHeadSway(AnimatedGeoBone head, float ageInTicks,
                                           float progress, float scale) {
        if (head == null) {
            return;
        }
        float phase = ageInTicks * 0.42f;
        head.setRotationX(-0.08f * progress + ((float) Math.sin(phase)) * 0.03f * progress * scale);
        head.setRotationY(-((float) Math.sin(phase * 0.8f)) * 0.22f * progress * scale); // Gecko 头部 yaw 轴反向
        head.setRotationZ(((float) Math.sin(phase * 0.8f)) * 0.05f * progress);
    }

    private static void applyGeckoHug(AnimatedGeoModel model, float progress) {
        AnimatedGeoBone allBody = findBone(model, "AllBody", "allBody", "Body", "body");
        AnimatedGeoBone upperBody = findBone(model, "UpperBody", "upperBody");
        AnimatedGeoBone head = findGeckoHead(model);
        AnimatedGeoBone neck = findBone(model, "Neck", "neck");
        AnimatedGeoBone leftShoulder = findBone(model, "LeftShoulder", "leftShoulder", "shoulderLeft");
        AnimatedGeoBone rightShoulder = findBone(model, "RightShoulder", "rightShoulder", "shoulderRight");
        AnimatedGeoBone leftArm = findBone(model, "LeftArm", "leftArm", "armLeft");
        AnimatedGeoBone leftForeArm = findBone(model, "LeftForeArm", "leftForeArm", "foreArmLeft");
        AnimatedGeoBone rightArm = findBone(model, "RightArm", "rightArm", "armRight");
        AnimatedGeoBone rightForeArm = findBone(model, "RightForeArm", "rightForeArm", "foreArmRight");
        AnimatedGeoBone skirt = findBone(model, "Skirt", "skirt");

        if (allBody != null) {
            allBody.setRotationX(0.10f * progress);
        }
        if (upperBody != null) {
            upperBody.setRotationX(0.18f * progress);
        }
        if (head != null) {
            head.setRotationX(-0.06f * progress);
            head.setRotationY(0.14f * progress);
            head.setRotationZ(0.04f * progress);
        }
        if (neck != null) {
            neck.setRotationX(0.05f * progress);
        }
        // Gecko 骨架开合轴与 Bedrock 反向（HeartPact 实证），数值随其调整
        if (leftShoulder != null) {
            leftShoulder.setRotationX(0.06f * progress);
            leftShoulder.setRotationY(0.18f * progress);
            leftShoulder.setRotationZ(-0.15f * progress);
        }
        if (rightShoulder != null) {
            rightShoulder.setRotationX(0.06f * progress);
            rightShoulder.setRotationY(-0.18f * progress);
            rightShoulder.setRotationZ(0.15f * progress);
        }
        if (leftArm != null) {
            leftArm.setRotationX(1.18f * progress);
            leftArm.setRotationY(0.48f * progress);
            leftArm.setRotationZ(-0.54f * progress);
        }
        if (rightArm != null) {
            rightArm.setRotationX(1.18f * progress);
            rightArm.setRotationY(-0.48f * progress);
            rightArm.setRotationZ(0.54f * progress);
        }
        if (leftForeArm != null) {
            leftForeArm.setRotationX(0.32f * progress);
            leftForeArm.setRotationY(-0.22f * progress);
            leftForeArm.setRotationZ(0.16f * progress);
        }
        if (rightForeArm != null) {
            rightForeArm.setRotationX(0.32f * progress);
            rightForeArm.setRotationY(0.22f * progress);
            rightForeArm.setRotationZ(-0.16f * progress);
        }
        if (skirt != null) {
            skirt.setRotationX(-0.05f * progress);
        }
    }

    /** Gecko 骨骼名兼容查找（模型包命名不统一） */
    private static AnimatedGeoBone findBone(AnimatedGeoModel model, String... names) {
        for (String name : names) {
            AnimatedGeoBone bone = model.bones().get(name);
            if (bone != null) {
                return bone;
            }
        }
        return null;
    }

    /** Gecko 头骨模糊查找（HeartPact 同款：精确名失败后按含 "head" 兜底） */
    private static AnimatedGeoBone findGeckoHead(AnimatedGeoModel model) {
        AnimatedGeoBone exact = findBone(model, "Head", "head",
                "HeadMain", "headMain", "Bip001 Head", "bip001_head");
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, AnimatedGeoBone> entry : model.bones().entrySet()) {
            String normalized = entry.getKey().replace("_", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("head")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
