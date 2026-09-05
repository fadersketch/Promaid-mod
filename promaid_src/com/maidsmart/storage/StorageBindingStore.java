package com.maidsmart.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v1.1.0 实测三百零八：女仆 × 精妙储存终端 绑定表（SavedData，每维度一份。
 * 主世界/下界/末地各自记录：键 = 女仆 UUID → 终端坐标列表。
 *
 * 绑定关系设计（多女仆一终端）：
 * - 终端坐标（维度|坐标）→ 可被多只女仆同时绑定（列表存储）
 * - 一只女仆只能绑定 1 个终端（新绑定替换旧绑定，防混乱）
 * - 终端被拆除：女仆绑定自动失效（取物时按方块存在性判定，无需解绑事件）
 * - 解绑卡右击该女仆 = 清空她的绑定
 */
public final class StorageBindingStore extends SavedData {
    private static final String DATA_NAME = "maid_smart_storage_binding";
    private static final String KEY_MAIDS = "maids";
    private static final String KEY_MAID_ID = "id";
    private static final String KEY_TERMINALS = "terminals";

    /** 女仆 UUID → 终端坐标列表（每维度一份表） */
    private final java.util.Map<String, List<long[]>> bindings = new java.util.HashMap<>();

    private StorageBindingStore() {
    }

    private StorageBindingStore(CompoundTag tag) {
        net.minecraft.nbt.ListTag maids = tag.m_128425_(KEY_MAIDS, 9)
                ? tag.m_128437_(KEY_MAIDS, 9) : new net.minecraft.nbt.ListTag();
        for (int i = 0; i < maids.size(); i++) {
            net.minecraft.nbt.CompoundTag e = maids.m_128728_(i);
            String id = e.m_128461_(KEY_MAID_ID);
            net.minecraft.nbt.ListTag terms = e.m_128425_(KEY_TERMINALS, 9)
                    ? e.m_128437_(KEY_TERMINALS, 9) : new net.minecraft.nbt.ListTag();
            List<long[]> list = new ArrayList<>();
            for (int j = 0; j < terms.size(); j++) {
                String s = terms.m_128778_(j);
                String[] parts = s.split(",");
                if (parts.length == 3) {
                    try {
                        list.add(new long[]{Long.parseLong(parts[0]),
                                Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            this.bindings.put(id, list);
        }
    }

    /** 取当前维度的绑定表（懒创建） */
    public static StorageBindingStore get(ServerLevel level) {
        return level.m_8895_().m_164861_(StorageBindingStore::new, StorageBindingStore::new, DATA_NAME);
    }

    /** 绑定：女仆 → 终端（替换该女仆的旧绑定；终端坐标去重） */
    public void bind(String maidUuid, long[] terminalPos) {
        List<long[]> list = this.bindings.computeIfAbsent(maidUuid, k -> new ArrayList<>());
        list.clear();
        list.add(terminalPos);
        this.m_77762_();
    }

    /** 解绑：清空该女仆的全部绑定 */
    public void unbind(String maidUuid) {
        if (this.bindings.remove(maidUuid) != null) {
            this.m_77762_();
        }
    }

    /** 女仆绑定的终端坐标列表（无则空列表；坐标 = BlockPos 三分量） */
    public List<long[]> terminalsOf(String maidUuid) {
        List<long[]> list = this.bindings.get(maidUuid);
        return list == null ? java.util.Collections.emptyList() : list;
    }

    public static long[] posToArr(BlockPos pos) {
        return new long[]{pos.m_123341_(), pos.m_123342_(), pos.m_123343_()};
    }

    public static BlockPos arrToPos(long[] arr) {
        return new BlockPos((int) arr[0], (int) arr[1], (int) arr[2]);
    }

    public static String maidKey(UUID id) {
        return id.toString();
    }

    @Override
    public CompoundTag m_7176_(CompoundTag tag) {
        net.minecraft.nbt.ListTag maids = new net.minecraft.nbt.ListTag();
        for (java.util.Map.Entry<String, List<long[]>> e : this.bindings.entrySet()) {
            net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
            entry.m_128359_(KEY_MAID_ID, e.getKey());
            net.minecraft.nbt.ListTag terms = new net.minecraft.nbt.ListTag();
            for (long[] arr : e.getValue()) {
                terms.add(net.minecraft.nbt.StringTag.m_129297_(
                        arr[0] + "," + arr[1] + "," + arr[2]));
            }
            entry.m_128365_(KEY_TERMINALS, terms);
            maids.add(entry);
        }
        tag.m_128365_(KEY_MAIDS, maids);
        return tag;
    }
}
