package com.maidsmart.tool;

import java.util.Set;

/**
 * 实测六百八十七【服务端：状态表的寿命】——给"只增不减"的静态表上一道护栏。
 *
 * <p>【现象】本模组有几十张 {@code static final Map<UUID, …>}（日志限频、启发式计时、短暂宽限）。
 * 它们的问题不是"某一次写错了"，而是**只在 tick 到那只女仆/那个玩家时才会被清**：她死亡、被魂符
 * 收走、区块卸载、玩家退服之后，键就永远留在表里。单机看不出问题；服务器跑几天、来来回回几百只
 * 女仆之后，这些表就是几千个永不回收的键（每键一个 UUID + 一个对象）。这是"服务端隐患"里最典型的
 * 一类——不是崩溃，是长跑之后慢慢变胖。
 *
 * <p>【为什么用"清空"而不是"按 UUID 存活性逐个删"】逐个删要拿每个键去 {@code level.getEntity(uuid)}
 * 查一遍（跨维度还要遍历所有 level），而**这些表里存的全是无害值**：限频时间戳（清掉 = 下次多打
 * 一条日志）、退避计时（清掉 = 重新起算）、几秒的宽限（清掉 = 少一次豁免）。没有一张表存"行为必须
 * 保持一致"的状态，所以整表清空只花一次内存分配，不改变任何可见行为。真正必须精确的表（武装拴绳
 * 的 {@code LINKS}）没走这条路——它有自己的 detach / onMaidJoin 链路，本来就是准的。
 *
 * <p>【上限怎么选】2048 条远高于任何正常存档（那是 2048 只不同的女仆/玩家）；真撞到就说明这张表
 * 确实在无界增长，清空是当时唯一安全的动作。清空时记一条日志——真发生了你会在 promai.log 里看到，
 * 而不是靠猜。
 */
public final class StateTables {

    private StateTables() {
    }

    /** 每张表最多留这么多条 */
    public static final int CAP = 2048;

    /** 已经报过警的表名（本身有界：表名个数） */
    private static final Set<String> WARNED = new java.util.HashSet<>();

    /**
     * 给一张表上护栏：超过 {@link #CAP} 就整表清空并记一条日志（同一张表只报一次）。
     *
     * @param name  表名（日志用）
     * @param table 目标表
     */
    public static void cap(String name, java.util.Map<?, ?> table) {
        try {
            if (table == null || table.size() <= CAP) {
                return;
            }
            int was = table.size();
            table.clear();
            if (WARNED.add(name)) {
                PromaidLog.log("服务端", "状态表 " + name + " 已到 " + was + " 条（上限 " + CAP
                        + "）→ 整表清空。这张表里都是日志限频/启发式计时/短暂宽限，清空只是让它们重算一次；"
                        + "若这条日志频繁出现，说明有地方在无界地记东西，请报给作者");
            }
        } catch (Throwable ignored) {
        }
    }
}
