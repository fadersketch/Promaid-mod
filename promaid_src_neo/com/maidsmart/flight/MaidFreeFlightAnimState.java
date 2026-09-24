package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;

/**
 * v1.2.5 实测六百五十七【仿创造飞行 · 动画条件】——"现在该不该播模型的 fly 动画"。
 *
 * 【只用客户端可见的数据】女仆的 `noGravity` 不在同步数据里（原版 `Entity` 的 noGravity 是纯服务端字段），
 * 所以这里**不去读"是否正在由我们托着"**，而是复用资格探测（在场物品 / 身上效果 / 重力属性——这三样
 * 客户端都知道）+ "她此刻离地、不在水里、不是乘客、没在挨打"。
 *
 * 这条判定的产物只有一个：让模型包自己做的 `fly` 动画被播放（模型包没做这条动画时毫无影响）。
 */
public final class MaidFreeFlightAnimState {

    private MaidFreeFlightAnimState() {
    }

    /**
     * v1.2.5 实测六百五十八【滑翔档】：该不该播模型自己的 {@code elytra_fly} 动画？
     *
     * 只看两件事：配置打开了「滑翔时用鞘翅动画」+ 她正在滑翔（共享标志位第 7 位 = 已同步，
     * 多人下客户端也认得出）。默认关时这一路恒 false，游泳动作照旧由 MaidSwimGlideMixin 顶。
     */
    public static boolean shouldPlayElytraFly(IMaid maid) {
        try {
            if (maid == null || !MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()) {
                return false;
            }
            return maid.asEntity() instanceof EntityMaid m && m.isFallFlying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** TLM 每 tick 问一次：她该播 fly 吗 */
    public static boolean shouldPlayFly(IMaid maid) {
        try {
            if (maid == null || !MaidSmartConfig.MISC_FREE_FLIGHT.get()) {
                return false;
            }
            if (!(maid.asEntity() instanceof EntityMaid m)) {
                return false;
            }
            if (!m.isAlive() || m.onGround() || m.isInWater() || m.isPassenger()
                    || m.isMaidInSittingPose() || m.isSleeping()) {
                return false;
            }
            if (m.hurtTime > 0) {
                return false;   // 挨打时优先播受击动画（attacked 在 priority 2，我们不想把它盖掉）
            }
            return MaidFreeFlightKit.isModeActive(m);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
