package com.maidsmart.tool;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 实测六百九十：实体表快照——遍历"全世界实体"时一律走这里，不要直接 for-each 原版实体表。
 *
 * <p>为什么要这份快照（javap 实证，1.20.1 与 1.21.1 两版实现一致）：
 * {@code ServerLevel.m_8583_()()} 返回的不是拷贝，而是原版实体表的【活视图】——
 * {@code ServerLevel.m_8583_()} → {@code LevelEntityGetterAdapter.getAll()}（1.21.1 同名）→
 * {@code EntityLookup.getAllEntities()} = {@code Iterables.unmodifiableIterable(byId.values())}，
 * 那个 {@code byId} 就是 {@code it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap}。
 * fastutil 的链式表迭代器靠【槽位号】在 {@code long[] link} 上走——反编译可见
 * {@code MapIterator.nextEntry()} 的第一句就是 {@code next = (int) link[curr]}——
 * 所以只要遍历期间实体表被结构性改动（加实体/删实体/换维度），迭代器手里那个槽位号
 * 就落到别的（或更短的）数组上，下一句 {@code link[curr]} 直接越界。
 *
 * <p>玩家实测崩溃那一局（2026-09-26 23:00:29，已发布的 1.2.3）就是这么崩的：
 * 本模组每 5 秒的跨维度跟随扫描一边 {@code for (Entity en : lvl.m_8583_())}，
 * 一边在循环里对女仆做跨维度 {@code teleportTo} —— 传送把她从本维度的 {@code byId}
 * 里删掉（并触发 EntityLeaveLevel 事件链），同一个迭代器接着读 {@code link[877]}，
 * 而那张表当时只有 513 个槽 → {@code ArrayIndexOutOfBoundsException: Index 877 out of
 * bounds for length 513} → 「Exception in server tick loop」，整合服务端整个崩回桌面
 * （同一局还先有一条第三方模组在同名事件里读活视图的 544/513）。
 *
 * <p>语义与代价：快照 = "这一趟看到的是这一趟开始时就在场的那批实体"，这正是本模组
 * 所有全量扫描循环原本的假设（下一趟扫描会看到新出现的实体）；代价是一次 O(实体数)
 * 的引用复制（微秒级，而这些循环本来就是 O(实体数) 的全量扫描，且大多几十 tick 才跑一次）。
 *
 * <p>注意：不能靠"我这条循环自己不增删实体"来判断安全——循环体里调用的任何代码都可能
 * 顺手改动实体表：我们自己（跨维传送 / 一键集合召回 / 掉物品清理 / 自保搭方块），
 * 车万女仆的任务与 AI，以及其他模组挂在事件上的回调。所以本模组统一口径：
 * 遍历实体表 = 先取快照。
 */
public final class EntitySnapshot {
    private EntitySnapshot() {
    }

    /** 该维度全部实体的快照（拿到手之后随便增删实体，再遍历不会出事）。 */
    public static List<Entity> of(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.m_8583_()) {
            out.add(e);
        }
        return out;
    }
}
