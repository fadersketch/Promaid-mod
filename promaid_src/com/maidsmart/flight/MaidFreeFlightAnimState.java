package com.maidsmart.flight;

import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;

/**
 * 实测七百〇二【仿创造飞行 · 1.20.1 精简版 · 动画条件】——"现在该不该播模型的 fly 动画"。
 *
 * 【1.20.1 移植说明（精简版口径）】本类与 1.21.1 树逐字对应：只用**客户端可见的同步数据**
 * 判定（她身上有没有资格物品/效果）。1.21.1 那版的第三路"重力属性 ≈ 0"在 1.20.1 **被砍掉**
 * （没有 {@code Attributes.GRAVITY} 这个属性，javap 实证），所以这里连它的调用都不出现。
 *
 * 【只用客户端可见的数据】女仆的 noGravity 不在同步数据里（原版 Entity 的 noGravity 是纯服务端
 * 字段），所以这里不去读"是否正在由我们托着"，而是复用资格探测（在场物品 / 身上效果——这两样
 * 客户端都知道）+ "她此刻离地、不在水里、不是乘客、没在挨打"。
 *
 * 这条判定的产物只有一个：让模型包自己做的 {@code fly} 动画被播放（模型包没做这条动画时毫无影响）。
 */
public final class MaidFreeFlightAnimState {

    private MaidFreeFlightAnimState() {
    }

    /**
     * 滑翔档：该不该播模型自己的 {@code elytra_fly} 动画？
     *
     * 只看两件事：配置打开了「滑翔时用鞘翅动画」+ 她正在滑翔（同步过的滑翔位，多人下客户端也认得出）。
     * 默认关时这一路恒 false，游泳动作照旧由 MaidSwimGlideMixin 顶。
     */
    public static boolean shouldPlayElytraFly(IMaid maid) {
        try {
            if (maid == null || !MaidSmartConfig.MISC_GLIDE_ELYTRA_ANIM.get()) {
                return false;
            }
            return maid.asEntity() instanceof EntityMaid m && m.m_21255_();
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
            if (!m.m_6084_() || m.m_20096_() || m.m_20069_() || m.m_20159_()
                    || m.isMaidInSittingPose() || m.m_5803_()) {
                return false;
            }
            if (m.f_20916_ > 0) {
                return false;   // 挨打时优先播受击动画（attacked 在 priority 2，我们不想把它盖掉）
            }
            return MaidFreeFlightKit.isModeActive(m);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
