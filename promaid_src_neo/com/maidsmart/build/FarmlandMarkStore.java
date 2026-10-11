package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * 耕地标记表：玩家/女仆用锄头锄地时给"曾经是耕地"的地块自动打标（SavedData 持久化），女仆只锄
 * "有标记但当前不是耕地"的地块；从未被锄过的泥土/草方块没有标记 → 不锄。
 *
 * 根因：旧版 isTillable 用"3×3 内有耕地"的启发式判定——锄掉一块泥土后周围 3×3 就有耕地了，连锁
 * 扩散导致超平坦地形 5×5 全被锄成耕地。改用标记制即消除扩散。
 * 存储：每维度一份 SavedData（{@code data/farmland_marks.dat}），{@code long[]} 存
 * {@code BlockPos.asLong} 压缩坐标（每格 8 字节，1 万格 ≈ 80KB，可接受）。
 */
public final class FarmlandMarkStore extends SavedData {
    private static final String DATA_NAME = "maid_smart_farmland_marks";
    private static final String KEY_MARKS = "marks";

    private final Set<Long> marks = new HashSet<>();

    private FarmlandMarkStore() {
    }

    private FarmlandMarkStore(CompoundTag tag) {
        long[] arr = tag.getLongArray(KEY_MARKS);
        if (arr != null) {
            for (long l : arr) {
                this.marks.add(l);
            }
        }
    }

    /** 取当前维度的标记表（懒创建） */
    public static FarmlandMarkStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<FarmlandMarkStore>(FarmlandMarkStore::new,
                        (t, registries) -> new FarmlandMarkStore(t),
                        net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES), DATA_NAME);
    }

    /** 打标记（幂等） */
    public void mark(BlockPos pos) {
        if (this.marks.add(pos.asLong())) {
            this.setDirty();
        }
    }

    /** 批量打标记（区块扫描用，只 setDirty 一次） */
    public void markAll(java.util.Collection<BlockPos> positions) {
        boolean changed = false;
        for (BlockPos pos : positions) {
            if (this.marks.add(pos.asLong())) {
                changed = true;
            }
        }
        if (changed) {
            this.setDirty();
        }
    }

    /** 是否有标记（"曾经是耕地"） */
    public boolean isMarked(BlockPos pos) {
        return this.marks.contains(pos.asLong());
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        long[] arr = new long[this.marks.size()];
        int i = 0;
        for (Long l : this.marks) {
            arr[i++] = l;
        }
        tag.putLongArray(KEY_MARKS, arr);
        return tag;
    }
}
