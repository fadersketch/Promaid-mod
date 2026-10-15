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
            if (!maid.hasRestriction()) { // TLM 重写为 isHomeModeEnable
                return true;
            }
            return maid.isWithinRestriction(pos);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return true;
        }
    }

    /**
     * 干活选点闸（挖矿/伐木/宰杀/农活在扫描里选点用）——口径就是"目标能不能到"（{@link #allows}）。
     *
     * <p>【实测九百二十二改定】home 模式走 TLM 自己的限制圈；非 home（跟随/战斗）一律放行。此前在
     * 非 home 时也拿"玩家用罗盘画的圈"钳选点——罗盘只写锚点、不开 home，圈留在老家后人被带到几十格
     * 外，圈内圈外每一格都被否掉 → 扫描永远空 → 她站着报"附近没有值得挖的矿石"、旁边就是矿也不挖。
     * 圈是"守家"的口径（home 与跟随必须分开）；闲逛那种"走两步"另走 {@link #allowsWander}。
     */
    public static boolean allowsWork(EntityMaid maid, BlockPos pos) {
        return allows(maid, pos);
    }

    /**
     * 玩家画的圈心（**非 home 也读**）——{@code SchedulePos} 的最近锚点。
     *
     * <p>与 {@link #circleCenter} 的区别：那条要求 home 模式（因为它读的是 TLM 的限制圈心）；
     * 这条专供"玩家画过圈但没开 home"时使用——圈心就是玩家用罗盘写进
     * {@code SchedulePos} 的那三个锚点里离她最近的一个。没配置锚点 → null。
     */
    public static BlockPos playerCircleCenter(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            var sp = maid.getSchedulePos();
            if (sp == null || !sp.isConfigured()) {
                return null;
            }
            BlockPos p = sp.getNearestPos(maid);
            return p != null && !p.equals(BlockPos.ZERO) ? p : null;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return null;
        }
    }

    /** 玩家画的圈的作业半径——取「排班活动半径」（罗盘只画点、不画半径；半径一直来自配置）。 */
    public static int playerCircleRadius(EntityMaid maid) {
        try {
            if (maid != null && maid.hasRestriction()) {
                int r = (int) maid.getRestrictRadius();
                if (r > 0) {
                    return r;
                }
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("WorkAreaClamp", ignored);
        }
        return Math.max(2, com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get());
    }

    /**
     * 非 home（跟随）模式改用「她自己此刻的位置」当圆心、半径 = 排班活动半径。
     * <p>【为什么要有它】
     * 宰杀在「没活可干」时的闲逛分支（每 2 秒换一个周围 8 格的随机点）。它选点过的是
     * {@link #allows}，而这条在**跟随模式（非 home）下恒放行**——圈形同不存在，于是每 2 秒换一个
     * 方向、越走越远。{@code WorkAreaClamp#allows} 保持原口径不动（它是"目标能不能到"的通用闸，
     * 战斗追击等也依赖它放行跟随模式）；闲逛这种"没事找事走两步"才用本方法，跟随模式下也给它一个 临时圈，让找活的范围不再无限扩张。 <p>【圆心与半径】home → {@link #circleCenter}（玩家画的圈心，罗盘/工位写的锚点）+ 半径 {@code maid.getRestrictRadius()}；跟随 → **主人身边**（跟随语义就是待在他附近）+ 半径 {@code config 排班活动半径}。锚点取不到 → 退回"她自己此刻位置"，绝不放行到无限。 开关 {@code misc.workStayInCircle}；关掉 = 与 {@link #allows} 完全一致。
     */
    public static boolean allowsWander(EntityMaid maid, BlockPos pos) {
        try {
            if (maid == null || pos == null) {
                return true;
            }
            if (!com.maidsmart.config.MaidSmartConfig.MISC_WORK_STAY_IN_CIRCLE.get()) {
                return allows(maid, pos);
            }
            if (maid.hasRestriction()) {
                return allows(maid, pos); // home：玩家画的圈说了算（与旧口径一字不差）
            }
            // 跟随：以【主人身边】为锚的临时圈（锚定不动的点，她才会在这一带转悠而不是一路漂走）
            BlockPos anchor = wanderAnchor(maid);
            if (anchor == null) {
                return true;
            }
            int r = wanderRadius(maid);
            return anchor.distSqr(pos) <= (double) r * r;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return true;
        }
    }

    /**
     * 【实测八百一十五】干活闲逛的**锚点**——闲逛选点必须围绕它取，不能围绕她自己。
     * <p>【为什么不能用"她自己"】每 2 秒在"她此刻位置周围 8 格"取随机点，等于一场**没有回复力的
     * 随机游走**：每步都可能朝同一方向多走一点，几十步下来就走出去几十格。这正是玩家看到的
     * "不停地在多个地方以极高的速度移动、似乎是在找任务目标"。把取点锚定在一个**固定点**上，
     * 她才是在这一点附近来回转，而不是一路漂走。
     * <p>home → 玩家画的圈心（罗盘/工位锚点，{@link #circleCenter}）；跟随 → **主人**身边 （跟随语义就是待在他附近），没主人时退回她自己。
     */
    public static BlockPos wanderAnchor(EntityMaid maid) {
        try {
            if (maid == null) {
                return null;
            }
            if (maid.hasRestriction()) {
                BlockPos c = circleCenter(maid);
                return c != null ? c : maid.blockPosition();
            }
            // 实测八百一十五【罗盘优先】：跟随中但玩家画过圈 → 以玩家画的圈心为锚（干活不出圈）
            if (com.maidsmart.schedule.PlayerCircle.isPlayerCircle(maid)) {
                BlockPos c = playerCircleCenter(maid);
                if (c != null) {
                    return c;
                }
            }
            net.minecraft.world.entity.LivingEntity owner = maid.getOwner();
            if (owner != null && owner.isAlive() && owner.level() == maid.level()) {
                return owner.blockPosition();
            }
            return maid.blockPosition();
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return null;
        }
    }

    /** 闲逛锚点对应的半径：home = 玩家画的圈半径；跟随/取不到 = 「排班活动半径」。 */
    public static int wanderRadius(EntityMaid maid) {
        try {
            if (maid != null && maid.hasRestriction()) {
                int r = (int) maid.getRestrictRadius();
                if (r > 0) {
                    return r;
                }
            }
            // 实测八百一十五：跟随中但玩家画过圈 → 用同一条半径口径
            if (com.maidsmart.schedule.PlayerCircle.isPlayerCircle(maid)) {
                return playerCircleRadius(maid);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("WorkAreaClamp", ignored);
        }
        return Math.max(2, com.maidsmart.config.MaidSmartConfig.SCHEDULE_ACTIVITY_RANGE.get());
    }

    /**
     * 取一个"闲逛点"：围绕 {@link #wanderAnchor} 的半径 {@code radius} 内随机偏移，
     * **y 用她当前所在层**（只约束水平范围，不把她送上屋顶/地下）。
     *
     * <p>返回 null = 本轮不逛（锚点取不到）。调用方仍应把落点过一次 {@link #allowsWander}
     * 兜底（锚点半径与限制圈半径口径不同时也不越界）。
     */
    public static BlockPos pickWanderSpot(EntityMaid maid, int radius,
                                          net.minecraft.util.RandomSource rng) {
        BlockPos anchor = wanderAnchor(maid);
        if (anchor == null) {
            return null;
        }
        net.minecraft.util.RandomSource r = rng != null ? rng
                : net.minecraft.util.RandomSource.create();
        int dx = r.nextInt(radius * 2 + 1) - radius;
        int dz = r.nextInt(radius * 2 + 1) - radius;
        return new BlockPos(anchor.getX() + dx, maid.blockPosition().getY(), anchor.getZ() + dz);
    }

    /**
     * 限制圈心（home 模式且圈心有效时返回；否则 null）。
     * 圈心来自实体数据、永不为 null，未 restrictTo 过时是 BlockPos.ZERO——
     * 直接拿去当锚点会把女仆拴到世界原点，这里一并挡掉。
     */
    public static BlockPos circleCenter(EntityMaid maid) {
        try {
            if (maid == null || !maid.hasRestriction()) {
                return null;
            }
            BlockPos c = maid.getRestrictCenter(); // getRestrictCenter
            if (c != null && !c.equals(BlockPos.ZERO)) { // BlockPos.ZERO = 从未 restrictTo 过
                return c;
            }
            // 【v1.3.0(beta) 实测六百六十四：排班锚点兜底】圈心还是零 → 退回"她排班锚点里最近的
            //  那一个"（只在玩家配过锚点时返回）。
            //
            //  而锚点就是河童的罗盘/排班表写进去的那三个点，读它等于直取"玩家画的那个圈"。
            return nearestAnchor(maid);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return null;
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
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return null;
        }
    }

    /**
     * 【实测六百九十二】这只女仆的「家」= 守家锚点（home 模式且圈心有效时返回圈心，否则 null）。
     * <p>口径就是 {@link #circleCenter}（连"排班锚点兜底"那一层一起），只是把"她此刻是不是在守家"
     * 也一起判掉——两条牵引绳（空袭 {@code MaidFlightRecall} / 扫帚 {@code MaidBroomRecall}）
     * 用它回答"该把她拉回哪儿"。
     * <p>【为什么必须有这一条】
     * **工作区圈心**，而不是主人：主人跑多远都不该把守家女仆拽走；而"她飞太远回不来"这个原始 问题照旧解决——拉回来的是家。口径只留一处，别在两条绳里各写一遍。
     */
    public static BlockPos homeAnchor(EntityMaid maid) {
        try {
            if (maid == null || !maid.hasRestriction()) { // hasRestriction——TLM 重写为 isHomeModeEnable
                return null;
            }
            return circleCenter(maid);
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return null;
        }
    }

    /**
     * 一行话说清"她现在的圈是怎么回事"（诊断用，日志里搜「扫帚守家」）。
     *
     * <p>【为什么要有它】，而"圈在哪儿"取决于
     * 三件只在服务端才看得见的东西：**home 开关**、**当前活动档**（圈心按活动档从工位/休闲/睡眠
     * 三个锚点里取）、**半径**（TLM 配置，本模组只抬高下限到「排班活动半径」）。这一行把它们
     * 连同三个锚点一起打出来，一条日志就能判断"她绕的到底是不是你画的那个圈"。
     */
    public static String describe(EntityMaid maid) {
        try {
            if (maid == null) {
                return "无女仆";
            }
            boolean home = maid.hasRestriction();
            BlockPos raw = home ? maid.getRestrictCenter() : null;
            boolean rawValid = raw != null && !raw.equals(BlockPos.ZERO);
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
                    + " 半径=" + (home ? String.valueOf(maid.getRestrictRadius()) : "—")
                    + " 锚点已配置=" + (sp != null && sp.isConfigured())
                    + " 锚点=" + anchors;
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return "读取异常：" + t;
        }
    }

    /** 当前活动档的中文名（工作/休闲/睡眠/其它）——圈心按它从三个锚点里选，排查时最关键的一格 */
    private static String activityName(EntityMaid maid) {
        try {
            net.minecraft.world.entity.schedule.Activity a = maid.getScheduleDetail();
            if (a == null) {
                return "?";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.WORK)) {
                return "工作";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.IDLE)) {
                return "休闲";
            }
            if (a.equals(net.minecraft.world.entity.schedule.Activity.REST)) {
                return "睡眠"; // TLM 的 sleepPos 用的就是原版 Activity.REST
            }
            return String.valueOf(a.getName());
        } catch (Throwable t) {
             com.maidsmart.diag.Diag.swallow("WorkAreaClamp", t); return "?";
        }
    }

    private static String pos(BlockPos p) {
        return p == null ? "（未设）" : "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
