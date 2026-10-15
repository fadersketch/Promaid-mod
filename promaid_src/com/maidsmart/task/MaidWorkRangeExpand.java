package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;

/**
 * 干活时临时扩圈（挖矿 / 伐木，实测九百一十一 / 九百一十二）：**跟随模式**下**正在干活**期间把「工作范围」
 * 圈的半径读数顶到罩得住「她到主人 / 她自己的检索范围」——TLM 里"太远就回去"的判据（跟随：
 * {@code MaidFollowOwnerTask} 太远就传送、否则自己走回来）与圈内判定、寻路范围全读
 * {@code getRestrictRadius} 这一个读数，出口在 {@link com.maidsmart.mixin.WorkRangeExpandMixin}
 * （根因见 {@link com.maidsmart.combat.CombatWorkRange}）：一处改完全线一致，停手当拍回落常态，不写存储。
 * <p>【为什么必须罩住"到主人的距离"】跟随中的女仆没有圈：她这份半径读的是 TLM 的 {@code MaidNonHomeRange}
 * （默认 8，不是 home 的 {@code MaidWorkRange} 64），于是跟随判据只有 6~10 格——6 格外它就把主人写回她的
 * 寻路目标、与干活目标拔河（在目标与主人之间反复走动）；10 格外走传送分支，传送被 {@code misc.workTeleportHold}
 * 拦下，可它紧接着 {@code resetNavigation}（停寻路 + 导航模式重置回 GROUND），每 tick 清掉她刚起步的寻路 =
 * 原地走一步停一步。顶过"她到主人"这段距离，两个分支连判据都过不去。
 * <p>【home 模式不扩（实测九百一十四）】home（不跟随 / 排班）下那道圈**就是她的最大活动范围**：出圈被传送回工位是**预期**。
 * <p>【开关与客户端】跟随 {@code misc.workTeleportHold}（关 = 回 TLM 原版）；客户端恒返回常态值（拿不到状态就别硬猜）。
 */
public final class MaidWorkRangeExpand {

    private MaidWorkRangeExpand() {
    }

    /** 余量（格）：比要罩住的距离再宽这么多。TLM 的"太远"判据从"半径 − 2"起算，8 格够她绕树走位 /
     *  垫方块靠近目标，也够主人再走两步，而不会触发任何一条拉回。 */
    private static final int MARGIN = 8;

    /**
     * 圈半径的最终读数（mixin 的出口值）：跟随模式 + 干活中 + 开关开 → 取大者，否则原样。
     * 任何异常 → 原样返回（这个 getter 被 TLM 各处高频调用，绝不能抛）。
     */
    public static float radius(EntityMaid maid, float normal) {
        try {
            if (maid == null || !com.maidsmart.config.MaidSmartConfig.MISC_WORK_TELEPORT_HOLD.get()) {
                return normal;
            }
            if (maid.m_9236_() == null || maid.m_9236_().m_5776_()) {
                return normal; // 客户端：画出来的圈保持玩家设置（见类文档）
            }
            if (maid.m_21536_()) {
                return normal; // home 模式：那道圈就是她的最大活动范围（实测九百一十四）——出了圈被传送回工位是预期
            }
            int work = workRadius(maid);
            if (work <= 0) {
                return normal; // 没在干活：常态半径
            }
            return Math.max(normal, Math.max(work + MARGIN, holdRange(maid) + MARGIN));
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkRangeExpand", ignored);
            return normal;
        }
    }

    /**
     * 她"正在干活"的那个活儿的检索半径（格）；没在干活 → 0（= 不扩）。
     * 两个都命中时取大者（正常流程下她只可能干一个活）。
     */
    private static int workRadius(EntityMaid maid) {
        int r = 0;
        try {
            if (MaidMineBehavior.isMining(maid)) {
                r = Math.max(r, com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get());
            }
            if (MaidWoodBehavior.isWooding(maid)) {
                r = Math.max(r, com.maidsmart.config.MaidSmartConfig.WOOD_SEARCH_RADIUS.get());
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkRangeExpand", ignored);
            return 0; // 读不到就当没在干活：宁可少扩一次圈，也不要凭异常把圈放大
        }
        return r;
    }

    /**
     * 跟随模式下"还得罩住"的那段距离（格）：跟随行为量的是**她到主人的距离**（{@code MaidFollowOwnerTask}
     * 的两个分支都以它为判据）。无主 / 主人已死 / 跨维度 → 0（那几种情况下跟随本来就不动她）。
     */
    private static float holdRange(EntityMaid maid) {
        float d = 0f;
        try {
            LivingEntity owner = maid.m_269323_();
            if (owner != null && owner.m_6084_() && maid.m_9236_() == owner.m_9236_()) {
                d = (float) Math.sqrt(maid.m_20280_(owner));
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("MaidWorkRangeExpand", ignored);
        }
        return d;
    }
}
