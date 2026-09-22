package com.maidsmart.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.2.3-dbg【临时探针·搭路刷物品 / 压人 / 临时方块计时】——查清问题后整类删掉即可。
 *
 * 为什么要有它：搭路刷物品靠读码只能出"嫌疑"，不能定案。这里改成**账本式**取证——
 * 每次取料 / 放置 / 回收各记一行，并且【把"取料方声称扣到了料"与"真实库存是否真的少了"对照】。
 * 两者不一致就是定案：
 *
 * - {@code PROBE[pay]} 里 took!=null 而 realDeduct=false → 声称扣了、真实库存没动（这一次没付账）
 * - 账本 {@code PROBE[ledger]} 里 NET(回收-取料) > 0            → 回收件数超过取料次数（净产出）
 * - {@code PROBE[pay]} 紧邻 poseShowing=true                     → 就是"副手展示件被当材料扣了"
 * - {@code PROBE[time]} 里 edge=YES                              → 主人明明压在这块上却判定不到（计时不刷新）
 * - {@code PROBE[crush]}                                         → 该格与女仆/主人碰撞箱重叠仍被放置、或她仍被埋
 *
 * 每 20 个事件打一行 {@code PROBE[ledger]}，一行看完趋势，不用自己数行。
 *
 * 形状上刻意不碰任何会被重映射的 API（只有 LogUtils + 纯 Java），所以两棵源码树里本文件
 * **逐字节相同**，维护时改一处即可。开关：JVM 参数 {@code -Dpromaid.probe=false}。
 */
public final class MaidProbe {

    /** 总开关（-Dpromaid.probe=false 关闭；默认开——这是诊断构建） */
    private static final boolean ON =
            !"false".equalsIgnoreCase(System.getProperty("promaid.probe", "true"));
    private static final long LEDGER_EVERY = 20L;

    /** maid → {takeTry, takeOk, takeNoPay, takePhantom, place, reclaim, reclaimDropStack, edge, crush} */
    private static final Map<String, long[]> STAT = new LinkedHashMap<>();
    private static final Map<String, String> LAST_COUNTS = new LinkedHashMap<>();
    private static long EVENTS = 0L;

    private MaidProbe() {
    }

    private static long[] stat(String maid) {
        long[] s = STAT.get(maid);
        if (s == null) {
            s = new long[9];
            STAT.put(maid, s);
        }
        return s;
    }

    /**
     * 取料一笔账。
     *
     * @param took         取料方拿到的物品 id（null = 没取到）
     * @param countsBefore 取料前 inv+hands 里全部【方块物品】的 "id x 总数" 统计串
     * @param countsAfter  取料后的同一统计串
     * @param poseShowing  此刻"动作表现"（BombPose/FlightFireworkPose）是否正借走副手
     * @param poseHeld     被借走的那一件（savedOffhand）
     */
    public static void take(String maid, String site, String took, boolean poseShowing, String poseHeld,
                            String countsBefore, String countsAfter) {
        if (!ON) {
            return;
        }
        try {
            long[] s = stat(maid);
            s[0]++;
            boolean real = countsBefore != null && !countsBefore.equals(countsAfter);
            if (took != null) {
                s[1]++;
                if (!real) {
                    s[2]++; // ★ 声称扣到、真实库存没变
                }
            } else if (real) {
                s[3]++; // 没取到却少了东西
            }
            EVENTS++;
            com.mojang.logging.LogUtils.getLogger().info(
                    "PROBE[pay] maid={} site={} took={} realDeduct={} poseShowing={} poseHeld={} counts[{} -> {}]",
                    maid, site, took, real, poseShowing, poseHeld, countsBefore, countsAfter);
            LAST_COUNTS.put(maid, String.valueOf(countsAfter));
            ledger(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 放置一笔账（打进追踪表时调用；搭路四条腿的汇聚点）。 */
    public static void place(String maid, String site, String pos, String blockId, String counts,
                             boolean ownerCellBlocked) {
        if (!ON) {
            return;
        }
        try {
            stat(maid)[4]++;
            EVENTS++;
            com.mojang.logging.LogUtils.getLogger().info(
                    "PROBE[place] maid={} site={} pos={} block={} ownerCellBlocked={} counts[{}]",
                    maid, site, pos, blockId, ownerCellBlocked, counts);
            LAST_COUNTS.put(maid, String.valueOf(counts));
            ledger(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 回收一笔账：handed=true 表示掉落物真的塞进了她背包；dropStacks = 掉落表条目数。 */
    public static void reclaim(String maid, String pos, String blockId, boolean handed, int dropStacks,
                               String countsBefore, String countsAfter) {
        if (!ON) {
            return;
        }
        try {
            long[] s = stat(maid);
            s[5]++;
            s[6] += Math.max(1, dropStacks);
            EVENTS++;
            com.mojang.logging.LogUtils.getLogger().info(
                    "PROBE[reclaim] maid={} pos={} block={} handed={} dropStacks={} counts[{} -> {}]",
                    maid, pos, blockId, handed, dropStacks, countsBefore, countsAfter);
            LAST_COUNTS.put(maid, String.valueOf(countsAfter));
            ledger(maid);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 临时方块到期时的计时账：主人（绑定女仆的主人）与这一块的相对位置。
     * edge=YES 即"他压在这块上、脚心却既不在本格也不在上一格"——计时刷新判定漏掉的那种情形。
     * 只在他确实就在这块附近时才打日志（正常到期不刷屏）。
     */
    public static void expireUnderMaster(String maid, String pos, String blockId,
                                         double dx, double dy, double dz,
                                         boolean feetMatch, boolean refreshOnOwnerStand, String masterKey) {
        if (!ON) {
            return;
        }
        try {
            boolean near = Math.abs(dx) <= 1.0 && Math.abs(dz) <= 1.0 && dy >= -0.5 && dy <= 2.5;
            if (!near) {
                return;
            }
            boolean edge = !feetMatch;
            if (edge) {
                stat(maid)[7]++;
            }
            EVENTS++;
            com.mojang.logging.LogUtils.getLogger().info(
                    "PROBE[time] expired pos={} block={} master={} feetMatch={} edge={} d=({},{},{}) refreshOnOwnerStand={}",
                    pos, blockId, masterKey, feetMatch, edge,
                    String.format("%.2f", dx), String.format("%.2f", dy), String.format("%.2f", dz),
                    refreshOnOwnerStand);
            ledger(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 压人账：该格与女仆/主人碰撞箱重叠（含"她已经被自己垫的方块埋住"）。 */
    public static void crush(String maid, String what, String pos, String detail) {
        if (!ON) {
            return;
        }
        try {
            stat(maid)[8]++;
            EVENTS++;
            com.mojang.logging.LogUtils.getLogger().warn(
                    "PROBE[crush] maid={} what={} pos={} detail={}", maid, what, pos, detail);
            ledger(maid);
        } catch (Throwable ignored) {
        }
    }

    /** 每 20 个事件一行总账——一行看完"取料 / 真实扣料 / 回收"三者对不对得上。 */
    private static void ledger(String maid) {
        if (EVENTS % LEDGER_EVERY != 0L) {
            return;
        }
        try {
            long[] s = STAT.get(maid);
            if (s == null) {
                return;
            }
            long net = s[5] - s[1];
            com.mojang.logging.LogUtils.getLogger().info(
                    "PROBE[ledger] maid={} takeTry={} takeOk={} TAKE_NO_PAY={} takePhantom={} place={} reclaim={} reclaimDropStacks={} edge={} crush={} NET(reclaim-takeOk)={} counts[{}]",
                    maid, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], net, LAST_COUNTS.get(maid));
        } catch (Throwable ignored) {
        }
    }
}
