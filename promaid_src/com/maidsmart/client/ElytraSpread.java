package com.maidsmart.client;

import net.minecraft.client.model.geom.ModelPart;

/**
 * v1.2.0（1.20.1，客户端）：滑翔中强制鞘翅**完全展开**。
 *
 * 【为什么需要】原版 `ElytraModel.m_6973_`（setupAnim）是"按下落速度决定展开程度"的
 * （反编译实证）：`f = 1 - pow(-vy_normalized, 1.5)`，翅膀角度在折叠态
 * （xRot 0.2617994 / zRot -0.2617994）与完全展开态（xRot 0.34906584 / zRot -1.5707964）
 * 之间按 f 插值。女仆扑向目标时基本都在下降，f 被拉低 → 看上去"没张开"。
 * 这里在滑翔中直接把角度钉到 f=1 的那一组值（与玩家平飞时的展翅完全一致）；
 * 收翅/落地时不干预（恢复原版行为，俯冲收翅膀正好对）。
 *
 * ModelPart 的 SRG 字段：f_104203_=xRot、f_104204_=yRot、f_104205_=zRot
 * （反编译实证：ElytraModel 里就是 `this.f_102533_.f_104203_ = <xRot>` 这样赋值的）。
 */
public final class ElytraSpread {

    /** 原版 f=1（完全展开）时左翼的 xRot / zRot */
    private static final float SPREAD_X = 0.34906584f;
    private static final float SPREAD_Z = -1.5707964f;

    private ElytraSpread() {
    }

    /**
     * @param elytraModel 必须是真的 ElytraModel 实例（走 {@link com.maidsmart.mixin.ElytraModelAccessor}）
     * @param gliding     是否正在滑翔（MaidFlightKit.isGliding）
     */
    public static void forceSpread(Object elytraModel, boolean gliding) {
        if (!gliding) {
            return;
        }
        try {
            com.maidsmart.mixin.ElytraModelAccessor acc =
                    (com.maidsmart.mixin.ElytraModelAccessor) elytraModel;
            ModelPart left = acc.promaid$leftWing();
            ModelPart right = acc.promaid$rightWing();
            left.f_104203_ = SPREAD_X;
            left.f_104204_ = 0.0f;
            left.f_104205_ = SPREAD_Z;
            // 右翼是左翼的镜像（原版 setupAnim 末尾：yRot/zRot 取反、y/xRot 相同）
            right.f_104203_ = SPREAD_X;
            right.f_104204_ = 0.0f;
            right.f_104205_ = -SPREAD_Z;
        } catch (Throwable ignored) {
        }
    }
}
