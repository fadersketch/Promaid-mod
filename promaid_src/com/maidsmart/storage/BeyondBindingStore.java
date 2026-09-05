package com.maidsmart.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.0 实测三百零九：女仆 × 超越维度网络接口（beyonddimensions:net_interface）
 * 绑定表——与精妙储存绑定表（StorageBindingStore）同构但独立存储
 * （maid_smart_beyond_binding），两套绑定互不干扰（各自的解绑卡只解各自的）。
 *
 * 取物能力：NetInterfaceBlockEntity 实现 Forge capability 代理
 * （getCapability(ITEM_HANDLER, dir) 透传网络对面容器的物品能力）——
 * 通过 IItemHandler 接口引用即可从中抽物品，零编译期依赖。
 */
public final class BeyondBindingStore extends SavedData {
    private static final String DATA_NAME = "maid_smart_beyond_binding";
    private static final String KEY_MAIDS = "maids";
    private static final String KEY_MAID_ID = "id";
    private static final String KEY_INTERFACES = "interfaces";

    /** 女仆 UUID → 网络接口坐标列表 */
    private final java.util.Map<String, List<long[]>> bindings = new java.util.HashMap<>();

    private BeyondBindingStore() {
    }

    private BeyondBindingStore(CompoundTag tag) {
        net.minecraft.nbt.ListTag maids = tag.m_128425_(KEY_MAIDS, 9)
                ? tag.m_128437_(KEY_MAIDS, 9) : new net.minecraft.nbt.ListTag();
        for (int i = 0; i < maids.size(); i++) {
            net.minecraft.nbt.CompoundTag e = maids.m_128728_(i);
            String id = e.m_128461_(KEY_MAID_ID);
            net.minecraft.nbt.ListTag terms = e.m_128425_(KEY_INTERFACES, 9)
                    ? e.m_128437_(KEY_INTERFACES, 9) : new net.minecraft.nbt.ListTag();
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
    public static BeyondBindingStore get(ServerLevel level) {
        return level.m_8895_().m_164861_(BeyondBindingStore::new, BeyondBindingStore::new, DATA_NAME);
    }

    /** 绑定：女仆 → 网络接口（替换该女仆的旧绑定） */
    public void bind(String maidUuid, long[] interfacePos) {
        List<long[]> list = this.bindings.computeIfAbsent(maidUuid, k -> new ArrayList<>());
        list.clear();
        list.add(interfacePos);
        this.m_77762_();
    }

    /** 解绑：清空该女仆的全部绑定 */
    public void unbind(String maidUuid) {
        if (this.bindings.remove(maidUuid) != null) {
            this.m_77762_();
        }
    }

    /** 女仆绑定的网络接口坐标列表（无则空列表） */
    public List<long[]> interfacesOf(String maidUuid) {
        List<long[]> list = this.bindings.get(maidUuid);
        return list == null ? java.util.Collections.emptyList() : list;
    }

    public static long[] posToArr(BlockPos pos) {
        return new long[]{pos.m_123341_(), pos.m_123342_(), pos.m_123343_()};
    }

    public static BlockPos arrToPos(long[] arr) {
        return new BlockPos((int) arr[0], (int) arr[1], (int) arr[2]);
    }

    public static String maidKey(java.util.UUID id) {
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
            entry.m_128365_(KEY_INTERFACES, terms);
            maids.add(entry);
        }
        tag.m_128365_(KEY_MAIDS, maids);
        return tag;
    }
}
