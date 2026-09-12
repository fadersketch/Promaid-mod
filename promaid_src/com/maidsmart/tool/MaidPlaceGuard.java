package com.maidsmart.tool;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 实测四百四十三【悬空禁搭方块】——用户："女仆在处于悬空状态下的时候应该禁止搭建
 * 方块（这个机制在挖矿和伐木的时候也通用，有时候就是因为下落悬空的时候搭方块又
 * 放不了落地水，导致自己被摔死了）。"
 *
 * 判定（下面全部满足才算"悬空应禁搭"）：
 * - 开关 misc.noPlaceInAir（默认开）；
 * - 未落地（onGround=false）——站在地上搭方块是各模块的常态，一律放行；
 * - 不在水/岩浆里（水里搭方块是正常操作）；
 * - 没骑乘、没鞘翅滑翔；
 * - 且满足坠落下限：坠落距离 ≥ 落地水触发高度（combat.waterFallDistance）——
 *   这个高度落地水本来就会接管；此时搭方块既救不了她，又会把落点的水/地面结构
 *   改掉、挡住落地水（用户实测的摔死根因）。
 *   （1.20.1 无重锤跃起——重锤是 1.21.1 专属）
 *
 * 【统一闸口】四个搭方块模块（自保搭高·搭路·挖矿垫脚·伐木垫脚）都在各自的
 * takeBuildBlock 取料前问一次本判定：被禁 → 返回 null → 各模块自然放弃本次
 * 放置（不消耗方块、不动世界）。"背包里没有搭方块的材料"这类取料失败气泡也
 * 同样静默，避免悬空时误报。
 *
 * 【不拦】落地水/细雪本身（WaterClutchBehavior 直接放水，不走 here）、照明
 * 光块（MaidHeldLight，非"搭建"语义且不消耗物品）、农耕播种——这些要么是保命
 * 机制、要么与坠落无关。
 */
public final class MaidPlaceGuard {
    private MaidPlaceGuard() {
    }

    /** 悬空/坠落中 → true = 本次禁止搭方块 */
    public static boolean blocked(EntityMaid maid) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_NO_PLACE_IN_AIR.get()) {
                return false;
            }
            // SRG：m_20096_=onGround m_20071_=isInWater m_20069_=isInLava
            //      m_20159_=isPassenger m_20161_=isFallFlying f_19789_=fallDistance
            if (maid.m_20096_() || maid.m_20071_() || maid.m_20069_()
                    || maid.m_20159_() || maid.m_20161_()) {
                return false;
            }
            return maid.f_19789_ >= (float) (double)
                    com.maidsmart.config.MaidSmartConfig.COMBAT_WATER_FALL_DISTANCE.get();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
