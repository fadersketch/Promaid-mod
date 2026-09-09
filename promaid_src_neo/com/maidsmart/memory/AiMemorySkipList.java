package com.maidsmart.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 跳表时间索引（移植自 Sphantosis cognitive/composite/skiplist_index.py）。
 *
 * 自研跳表——以游戏 tick（事件时间）为 key、段落 hash 为 value：
 * - O(log n) 插入 / 范围查询
 * - 范围查询供多级索引生成（按时间跨度收集事件）与按时间检索记忆使用，
 *   取代对全部段落的线性扫描
 *
 * 纯内存结构：数据源是 paragraphs.jsonl，load() 后重建，无需单独持久化。
 * 只在服务端线程使用（记忆调度/归档均在服务端线程或回调切回后执行）。
 */
public final class AiMemorySkipList {

    private static final class Node {
        final long key;
        final String value;
        Node[] forward;

        Node(long key, String value, int level) {
            this.key = key;
            this.value = value;
            this.forward = new Node[level];
        }
    }

    /** 重建用的 (tick, hash) 对 */
    public record Entry(long tick, String hash) {
    }

    private static final int MAX_LEVEL = 16;
    private static final double P = 0.5;

    private int level = 1;
    private final Node head = new Node(Long.MIN_VALUE, null, MAX_LEVEL);
    private int nodeCount = 0;
    private final Random random = new Random();

    /** 随机生成节点层数（概率晋升，与 Sphantosis 实现一致） */
    private int randomLevel() {
        int lv = 1;
        while (random.nextDouble() < P && lv < MAX_LEVEL) {
            lv++;
        }
        return lv;
    }

    /** 插入（时间戳 → 段落 hash；同 key 允许重复，稳定追加在其后） */
    public void insert(long key, String value) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].key < key) {
                current = current.forward[i];
            }
            update[i] = current;
        }
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }
        Node node = new Node(key, value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            node.forward[i] = update[i].forward[i];
            update[i].forward[i] = node;
        }
        nodeCount++;
    }

    /** 范围查询：返回 [start, end] 内的段落 hash（按时间升序；同 tick 保持插入序） */
    public List<String> queryRange(long start, long end) {
        List<String> result = new ArrayList<>();
        Node current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].key < start) {
                current = current.forward[i];
            }
        }
        current = current.forward[0];
        while (current != null && current.key <= end) {
            result.add(current.value);
            current = current.forward[0];
        }
        return result;
    }

    /** 查询指定时间之前的最近 limit 条（升序返回） */
    public List<String> queryBefore(long timestamp, int limit) {
        List<String> result = new ArrayList<>();
        Node current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].key < timestamp) {
                current = current.forward[i];
            }
        }
        current = current.forward[0];
        while (current != null && result.size() < limit) {
            result.add(current.value);
            current = current.forward[0];
        }
        return result;
    }

    /** 从有序对重建（load 后批量建索引；Sphantosis from_list 的对应实现） */
    public void rebuild(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(Entry::tick));
        for (Entry e : sorted) {
            insert(e.tick, e.hash());
        }
    }

    /** 节点数 */
    public int size() {
        return nodeCount;
    }
}
