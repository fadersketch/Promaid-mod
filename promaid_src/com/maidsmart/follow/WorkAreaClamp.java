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
            if (c != null && !c.equals(BlockPos.f_121853_)) { // BlockPos.ZERO = 从未 restrictTo 过
                return c;
            }
            // 【v1.3.0(beta) 实测六百六十四：排班锚点兜底】圈心还是零 → 退回"她排班锚点里最近的
            //  那一个"（只在玩家配过锚点时返回）。玩家反馈：「我刚刚用河童的罗盘画了一个圈，
            //  但女仆却不照着那个飞」——圈心为零时夹取与盘旋双双失效，扫帚模式只剩跟主人；
            //  而锚点就是河童的罗盘/排班表写进去的那三个点，读它等于直取"玩家画的那个圈"。
            return nearestAnchor(maid);
        } catch (Throwable t) {
            return null;
        }
    }
    /**
     * 排班锚点里离她最近的那个（**只在玩家配过锚点时**返回，否则 null）。
     *
     * <p>圈心（实体数据）与锚点（{@code SchedulePos} 的工位/休闲/睡眠）是两处存储：正常情况
     * {@code restrictTo} 每 2 秒拿当前活动档的锚点去刷圈心，两者一致；但 home 模式刚开、
     * 或当前活动档不在 work/idle/rest 三档里时，{@code restrictTo} 整条不执行，圈心就一直
     * 停在零——**玩家明明用河童的罗盘配过点，圈却是无效的**。这一条只读兜底，不改任何存储。
     */
    private static BlockPos nearestAnchor(EntityMaid maid) {
        try {
            var sp = maid.getSchedulePos();
            if (sp == null || !sp.isConfigured()) {
                return null;
            }
            return sp.getNearestPos(maid);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 一行话说清"她现在的圈是怎么回事"（诊断用，日志里搜「扫帚守家」）。
     *
     * <p>【为什么要有它】玩家反馈「女仆绕着一个我根本就不知道的范围在飞行」，而"圈在哪儿"取决于
     * 三件只在服务端才看得见的东西：**home 开关**、**当前活动档**（圈心按活动档从工位/休闲/睡眠
     * 三个锚点里取）、**半径**（TLM 配置，本模组只抬高下限到「排班活动半径」）。这一行把它们
     * 连同三个锚点一起打出来，一条日志就能判断"她绕的到底是不是你画的那个圈"。
     */
    public static String describe(EntityMaid maid) {
        try {
            if (maid == null) {
                return "无女仆";
            }
            boolean home = maid.m_21536_();
            BlockPos raw = home ? maid.m_21534_() : null;
            boolean rawValid = raw != null && !raw.equals(BlockPos.f_121853_);
            BlockPos c = circleCenter(maid);
            var sp = maid.getSchedulePos();
            String anchors = sp == null
                    ? "无排班数据"
                    : ("工位" + pos(sp.getWorkPos()) + " 休闲" + pos(sp.getIdlePos())
                       + " 睡眠" + pos(sp.getSleepPos()));
            return "home=" + home
                    + " 活动=" + activityName(maid)
                    + " 圈心=" + (c == null ? "无" : pos(c))
                    + " 圈心来源=" + (rawValid ? "实体数据" : (c == null ? "无" : "排班锚点兜底"))
                    + " 半径=" + (home ? String.valueOf(maid.m_21535_()) : "—")
                    + " 锚点已配置=" + (sp != null && sp.isConfigured())
                    + " 锚点=" + anchors;
        } catch (Throwable t) {
            return "读取异常：" + t;
        }
    }

    /** 当前活动档的中文名（工作/休闲/睡眠/其它）——圈心按它从三个锚点里选，排查时最关键的一格 */
    private static String activityName(EntityMaid maid) {
        try {
            net.minecraft.world.entity.schedule.Activity a = maid.getScheduleDetail();
            if (a == null) {
                return "?";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.f_37980_)) {
                return "工作";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.f_37979_)) {
                return "休闲";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.f_37982_)) {
                return "睡眠"; // TLM 的 sleepPos 用的就是原版 Activity.REST
            }
            return String.valueOf(a.m_37998_());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String pos(BlockPos p) {
        return p == null ? "（未设）" : "(" + p.m_123341_() + "," + p.m_123342_() + "," + p.m_123343_() + ")";
    }
}
