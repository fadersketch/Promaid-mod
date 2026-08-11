package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.task.MaidWorkTags;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 站桩锁位（v1.5.93）：从实体移动层直接掐断站桩女仆的水平位移。
 *
 * 背景（v1.5.24 的 MoveToTargetSink 抑制只堵住了一层移动源）：
 * TLM 女仆用自定义 MaidMoveControl——当 getSwimManager().wantToSwim()（想游泳）时，
 * 它用 setDeltaMovement（m_20256_）【直接施加速度矢量】，绕过 WALK_TARGET 和
 * MoveToTargetSink；且多行为直接调 PathNavigation.moveTo（m_26519_）也会绕过
 * WALK_TARGET 驱动移动。这些源都能让"坐着的/站桩的女仆"诡异地移动。
 *
 * v1.5.98c 修复（女仆站桩时持续溺水）：旧版直接 ci.cancel() 整个 m_7023_（travel），
 * 把【垂直运动也掐死】——站桩女仆在水里（河边整理/水边工作）无法上浮呼吸 →
 * 持续溺水伤害（"女仆一直在受伤"）。现在改为【只清水平速度、保留垂直】：
 * setDeltaMovement(0, y, 0) 且不 cancel——水平不动（站桩），垂直照常
 * （水上浮/下落/落地），杜绝溺水与"卡半空"。
 */
@Mixin(EntityMaid.class)
public abstract class MaidStationaryMixin {
    @Inject(method = "m_7023_", at = @At("HEAD"))
    private void maidsmart$lockWhenStationary(Vec3 travelVector, CallbackInfo ci) {
        EntityMaid maid = (EntityMaid) (Object) this;
        if (MaidWorkTags.isStill(maid)) {
            // v1.5.98c：只清水平速度（x/z 归零），保留垂直（y）——防溺水/保下落
            Vec3 v = maid.m_20184_();
            if (v.f_82479_ != 0.0 || v.f_82481_ != 0.0) {
                maid.m_20256_(new Vec3(0.0, v.f_82480_, 0.0));
            }
        }
    }
}
