package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;

/**
 * v1.2.0 实测五百四十二【激流旋转时把模型抬起来一点，别像嵌进地里】（1.21.1，纯客户端渲染）。
 *
 * ── 需求原文 ──
 * "旋转建模上面有点位置上偏低了。导致你不在平地上进行旋转的时候像嵌进地里了一样。"
 *
 * ── 为什么会偏低（几何上算得出来）──
 * 原版激流姿态是**绕实体原点（脚底）**转的：`LivingEntityRenderer.setupRotations` 里
 * 就是两次 `mulPose`（`XP(-90 - 俯仰)` + `YP(-75 × tick)`），**没有任何平移**。
 * 女仆的模型是从脚下往上建模的（身体占 y ∈ [0, ~1.8]），平躺之后这一段的**高度方向**变成
 * 了身体的"前后厚度"（约 ±0.3）：于是整个身体变成一块**以脚底高度为中心的薄片**——下半
 * 沉进地面、上半露在外。平地还能勉强看，斜坡/台阶上一转就"嵌进地里"。
 * 玩家不会有这个观感，因为玩家的激流只在水里/雨里、身体本来就不贴着地面。
 *
 * ── 修法 ──
 * 在**旋转之前**（也就是还没被 `XP/YP` 转过去、矩阵仍是"Y 朝上、原点在脚下"的时候）
 * 先把模型整体抬起 {@link #SPIN_LIFT} 格：抬的是**枢轴**，于是平躺的身体改成以"离地
 * 0.6 格"为中心，不再有一半埋进方块里。只作用于**正在旋转突进的女仆**，其余实体、
 * 其余状态一律原样返回。
 *
 * ── 为什么注在 `LivingEntityRenderer`（而不是 TLM 那个渲染器）──
 * 两个模型体系（Gecko 与 Bedrock）最终都会走到原版的 `setupRotations` 去施加激流姿态，
 * 注在**这一层**一处就同时覆盖，不必分别处理 TLM 的两个渲染器。
 *
 * ── 可调 ──
 * {@link #SPIN_LIFT} 是唯一调节量：嫌"像在飘"就调小（0.3 左右），嫌"还是压地"就调大。
 * 纯客户端渲染，不动任何机制、不加网络包、服务端无感。
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
        } catch (Throwable ignored) {
        }
    }
}
