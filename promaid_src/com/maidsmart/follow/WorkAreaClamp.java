package com.maidsmart.follow;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;

/**
 * 实测五百六十二：工作圈钳制——home（不跟随/排班）模式下"工作区域"的统一硬实现。
 *
 * TLM 原版口径（1.5.3 反编译实证）：EntityMaid 把 {@code hasRestriction()} 重写为
 * {@code isHomeModeEnable()}——非 home 模式（跟随/战斗）恒无圈、isWithinRestriction
 * 恒 true；home 模式的圈心由 SchedulePos.restrictTo 按当前活动刷到工位/休闲/睡眠
 * 锚点、半径取 TLM/promaid 配置（ScheduleRangeMixin）。TLM 原生任务
 * （MaidMoveToBlockTask 族）选点逐个过 isWithinRestriction 过滤——这就是原版的
 * "工作区域"。本模组自己的任务（挖矿/伐木/宰杀等）此前用直连导航绕过了这套圈，
 * 女仆会顺着目标越走越远（干活期间拉回又被 SchedulePosTickMixin 免掉）——
 * 本类把所有自定义行为的选点统一拉回同一口径。
 */
public final class WorkAreaClamp {
    private WorkAreaClamp() {
    }

    /** home 模式下目标必须在限制圈内；非 home（跟随/战斗）恒放行 */
    public static boolean allows(EntityMaid maid, BlockPos pos) {
        try {
            if (maid == null || pos == null) {
                return true;
            }
            if (!maid.m_21536_()) { // hasRestriction——TLM 重写为 isHomeModeEnable
                return true;
            }
            return maid.m_21444_(pos); // isWithinRestriction(BlockPos)
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 限制圈心（home 模式且圈心有效时返回；否则 null）。
     * 圈心来自实体数据、永不为 null，未 restrictTo 过时是 BlockPos.ZERO——
     * 直接拿去当锚点会把女仆拴到世界原点，这里一并挡掉。
     */
    public static BlockPos circleCenter(EntityMaid maid) {
        try {
            if (maid == null || !maid.m_21536_()) { // hasRestriction
                return null;
            }
            BlockPos c = maid.m_21534_(); // getRestrictCenter
            if (c.equals(BlockPos.f_121853_)) { // BlockPos.ZERO = 从未 restrictTo 过
                return null;
            }
            return c;
        } catch (Throwable t) {
            return null;
        }
    }
}
