package com.maidsmart.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.combat.MaidFlightKit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.LivingEntity;

/**
 * 飞行作战"跟随俯角"前倾（纯客户端渲染）。
 *
 * 【为什么俯冲像游泳】{@code MaidSwimGlideMixin} 让滑翔时 {@code isVisuallySwimming()} 返回 true，
 * 而 TLM 的 Gecko 动画表里 swim 动画的触发谓词正是它 → 播放自带 swim 动画（身体水平俯卧、中轴
 * 直指目标）。本类不改动画，只在整体矩阵上叠一层随俯角变化的额外前倾。
 * 【玩家鞘翅配方】{@code PlayerRenderer.setupRotations}：
 * {@code ramp=clamp(t*t/100,0,1); mulPose(Axis.XP.rotationDegrees(ramp*(-90-getXRot())))}。基础 -90°
 * 由模型动画负责，本类只补 **{@code -getXRot()}**（俯冲角本身，非固定角度）；平飞（≈0）无变化。
 * 【施加位置】{@code GeoReplacedEntityRenderer.setupRotations} 的 RETURN：矩阵仍在"Y 朝上、原点
 * 在脚下"的世界式空间，原版飞行/俯卧姿态都在此用 {@code Axis.XP} 施加，轴向与枢轴因此与游戏一致。
 * 【触发】{@code MaidFlightKit.isFlightVisual(maid) && !onGround()}（飞行任务 **或** 滑翔），与鞘翅图层
 * 同款；**刻意不认 {@code isFallFlying()}**——收翅猛击会 {@code setGliding(false)}，只认滑翔位最需要
 * 前倾那段恰好失效。【可调】{@link #TILT_FACTOR}（1.0=玩家同款）；纯客户端，不动飞行机制。
 */
public final class FlightDiveTilt {

    /** 前倾倍率：1.0 = 与玩家鞘翅同款（额外旋转 = -getXRot() × 本值） */
    private static final float TILT_FACTOR = 1.0f;

    private FlightDiveTilt() {
    }

    /**
     * 给飞行作战中的女仆叠一层"跟随俯角"的前倾。
     *
     * @param entity    当前渲染的实体（非女仆 / 未在飞行作战任务 / 已落地时原样返回）
     * @param poseStack 渲染矩阵（调用点在世界式空间，Y 朝上）
     */
    public static void apply(LivingEntity entity, PoseStack poseStack) {
        try {
            if (!(entity instanceof EntityMaid maid)) {
                return;
            }
            if (!MaidFlightKit.isFlightVisual(maid) || maid.onGround()) {
                return;
            }
            float pitch = maid.getXRot();
            if (pitch == 0.0f) {
                return;
            }
            // 原版低头为正角 → 取负与基础 -90° 同向，即"继续往前翻"
            poseStack.mulPose(Axis.XP.rotationDegrees(-pitch * TILT_FACTOR));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("FlightDiveTilt", ignored);
        }
    }
}
