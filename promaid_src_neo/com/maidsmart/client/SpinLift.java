package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;

/**
 * 激流旋转时把模型抬起来一点，别像嵌进地里（纯客户端渲染）。
 *
 * 【为什么会偏低（几何上算得出来）】原版激流姿态是**绕实体原点（脚底）**转的
 * （{@code setupRotations} 里两次 {@code mulPose}：{@code XP(-90-俯仰)} + {@code YP(-75×tick)}，
 * **没有任何平移**）。女仆模型从脚下往上建模（身体占 y ∈ [0,~1.8]），平躺后这段高度方向变成身体的
 * "前后厚度"（约 ±0.3）→ 整个身体成了**以脚底高度为中心的薄片**：下半沉进地面、上半露在外。平地勉强
 * 看，斜坡/台阶一转就"嵌进地里"。玩家没这观感，因为玩家的激流只在水里/雨里、身体不贴地。
 * 【修法】在**旋转之前**（矩阵仍是"Y 朝上、原点在脚下"）先把模型整体抬起 {@link #SPIN_LIFT} 格——
 * 抬的是**枢轴**，平躺的身体改以"离地 0.6 格"为中心，不再半埋。只作用于**正在旋转突进的女仆**。
 * 【为什么注在 {@code LivingEntityRenderer}】Gecko 与 Bedrock 两套模型最终都走原版
 * {@code setupRotations} 施加激流姿态，注这一层一处即同时覆盖。
 * 【可调】{@link #SPIN_LIFT} 是唯一调节量（0.3 左右更"轻"、调大更"离地"）；纯客户端，不动机制。
 */
public final class SpinLift {

    /**
     * 平躺旋转时把模型整体抬起这么多格。
     *
     * <p>0.6 的依据：平躺后身体的竖直厚度约 ±0.3（就是原本的前后厚度），抬起 0.6 之后
     * 身体最低点离地约 0.3 格——看着像悬在地面上方一点，而不是插进方块里。
     */
    private static final float SPIN_LIFT = 0.6f;

    private SpinLift() {
    }

    /**
     * 给正在激流旋转的女仆把模型抬起来一点。
     *
     * @param entity    当前渲染的实体（非女仆 / 未在旋转时原样返回）
     * @param poseStack 渲染矩阵（调用点在 `setupRotations` 的 HEAD，仍是"Y 朝上、原点在脚下"）
     */
    public static void apply(LivingEntity entity, PoseStack poseStack) {
        try {
            if (!(entity instanceof EntityMaid)) {
                return;
            }
            // 与渲染器里那一段的姿态判据保持一致：死亡翻转期间不要插手
            if (entity.deathTime > 0 || !entity.isAutoSpinAttack()) {
                return;
            }
            poseStack.translate(0.0f, SPIN_LIFT, 0.0f);
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("SpinLift", ignored);
        }
    }
}
