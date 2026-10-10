package com.maidsmart.tool;

import java.util.ArrayList;
import java.util.List;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 实测八百三十九：已加载女仆名册——"现在有哪些女仆"全模组只来这里问一次，按
 * {@link #REBUILD_TICKS} 节流重建；零女仆时所有女仆相关服务都近乎零成本。
 *
 * <p>查询代价（javap 实证）：不带 AABB 的 {@code Level.getEntities(EntityTypeTest,…)} 走
 * {@code EntityLookup.m_260822_}，字节码是"遍历全部实体逐个 tryCast"，不查
 * {@code ClassInstanceMultiMap} 的类桶 → 不能每刻重建，必须按周期复用。
 *
 * <p>只读快照：{@link EntitySnapshot} 类注释里那颗雷（活视图遍历中被改 → fastutil
 * 越界崩服）同样适用；名册先攒进 ArrayList 再交出去。滞后语义：成员表最多滞后
 * {@link #REBUILD_TICKS} tick（滞后的是"算不算在场"，不是女仆对象本身），各使用点
 * 本就按 5~100 tick 节流。
 */
public final class MaidRoster {
    // 实测八百三十九·起因（开发者给的 spark 采样报告，46 秒 / 921 tick 的**超平坦、零女仆**
    // 存档）：本模组十几个每 tick（或每 5/10/20/100 tick）跑的女仆相关服务，过去各自都用
    // EntitySnapshot.of() 把整张实体表拷一遍再 instanceof 过滤——零女仆时全是白烧：
    // MaidChunkLoadManager.tick 12 ms、FishingChairService.sweep 8 ms、HomeWorkMovementDriver
    // 8 ms……合计 76 ms（服务器线程执行时间的 5.6%），换来的信息量是"一只女仆都没有"。
    // 名册把"有哪些女仆"收敛成一处、按周期重建，零女仆世界从各服务合计约 14 次全量扫描/秒
    // 降到 20 / REBUILD_TICKS 次/秒。

    // 顺带一条实证（免得下一个人再走一遍）：ClassInstanceMultiMap 的 getAllOfClass 字节码是
    // byClass.computeIfAbsent(clazz, c -> allInstances.stream().filter(c::isInstance)…) ——
    // 类桶是【按需补建】的，不存在"没预建 EntityMaid 桶的 section 被整段跳过"这回事
    // （AutoCombatSwitch 里那条同名注释归因有误，真因是同段里写明的无限 AABB 溢出；
    // 本名册走的是上面那条全量 tryCast，与那条注释无关）。

    /** 名册重建周期（tick）。取 20：比各使用点最短节流周期（5 tick）宽松得多，不会让谁看到更旧的世界。 */
    public static final int REBUILD_TICKS = 20;

    private static MinecraftServer cachedServer; // 建表时所属的服务器实例（换存档即作废）
    private static int cachedTick = Integer.MIN_VALUE;
    private static List<EntityMaid> cached = List.of();

    private MaidRoster() {
    }

    /** 全部维度、存活的女仆（只读快照；按 {@link #REBUILD_TICKS} 节流重建）。 */
    public static List<EntityMaid> all(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        int tick = server.m_129921_();
        if (server == cachedServer && tick - cachedTick < REBUILD_TICKS) {
            return cached;
        }
        List<EntityMaid> out = new ArrayList<>();
        for (ServerLevel level : server.m_129785_()) {
            for (EntityMaid maid : level.m_143280_(
                    net.minecraft.world.level.entity.EntityTypeTest.m_156916_(EntityMaid.class), m -> true)) {
                if (maid.m_6084_()) {
                    out.add(maid);
                }
            }
        }
        cachedServer = server;
        cachedTick = tick;
        cached = out;
        return out;
    }

    /** 名册里属于这一维度的那批（只读快照）。 */
    public static List<EntityMaid> in(MinecraftServer server, ServerLevel level) {
        List<EntityMaid> all = all(server);
        if (level == null || all.isEmpty()) {
            return List.of();
        }
        List<EntityMaid> out = new ArrayList<>();
        for (EntityMaid maid : all) {
            if (maid.m_9236_() == level) {
                out.add(maid);
            }
        }
        return out;
    }

    /** 零女仆快闸：一只女仆都没有时女仆相关服务可直接返回（循环体整段只处理女仆的方法才能
     *  用它整体跳过；像 AiMemoryManager 那样兼做与女仆无关的事的不能跳）。 */
    public static boolean any(MinecraftServer server) {
        return !all(server).isEmpty();
    }
}
