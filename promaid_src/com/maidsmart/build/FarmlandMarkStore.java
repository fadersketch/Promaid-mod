package com.maidsmart.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * v1.1.0 实测三百零二：耕地标记表（用户："对曾经已经是耕地的地块打上一个标记
 * （不管是自然生成的还是玩家搞的）女仆可以识别这些标记，并且在 5×5 范围内检索到
 * 以后发现不是耕地就动用锄头将其锄成耕地"）。
 *
 * 根因：旧版 isTillable 用"3×3 内有耕地"的启发式判定——锄掉一块泥土后，周围 3×3
 * 内就有耕地了，连锁扩散导致超平坦地形 5×5 全被锄成耕地（用户："女仆在发现一个
 * 地块不是耕地的时候，就直接锄上去了，导致在超平坦地形直接 5×5 的地块全都变成了
 * 耕地"）。
 *
 * 修复：改为【标记制】——"曾经是耕地"的地块打上标记（玩家/女仆用锄头锄地时
 * 自动打标，SavedData 持久化），女仆只锄"有标记但当前不是耕地"的地块。从未被
 * 锄过的泥土/草方块没有标记 → 不锄。
 *
 * 存储：每维度一份 SavedData（data/farmland_marks.dat），long[] 存 BlockPos.asLong
 * 压缩坐标（每格 8 字节，1 万格 ≈ 80KB，可接受）。
 */
public final class FarmlandMarkStore extends SavedData {
    private static final String DATA_NAME = "maid_smart_farmland_marks";
    private static final String KEY_MARKS = "marks";

    private final Set<Long> marks = new HashSet<>();

    private FarmlandMarkStore() {
    }

    private FarmlandMarkStore(CompoundTag tag) {
        long[] arr = tag.m_128467_(KEY_MARKS);
        if (arr != null) {
            for (long l : arr) {
                this.marks.add(l);
            }
        }
    }

    /** 取当前维度的标记表（懒创建） */
    public static FarmlandMarkStore get(ServerLevel level) {
        return level.m_8895_().m_164861_(FarmlandMarkStore::new, FarmlandMarkStore::new, DATA_NAME);
    }

    /** 打标记（幂等） */
    public void mark(BlockPos pos) {
        if (this.marks.add(pos.m_121878_())) {
            this.m_77762_();
        }
    }

    /** 批量打标记（区块扫描用，只 setDirty 一次） */
    public void markAll(java.util.Collection<BlockPos> positions) {
        boolean changed = false;
        for (BlockPos pos : positions) {
            if (this.marks.add(pos.m_121878_())) {
                changed = true;
            }
        }
        if (changed) {
            this.m_77762_();
        }
    }

    /** 是否有标记（"曾经是耕地"） */
    public boolean isMarked(BlockPos pos) {
        return this.marks.contains(pos.m_121878_());
    }

    @Override
    public CompoundTag m_7176_(CompoundTag tag) {
        long[] arr = new long[this.marks.size()];
        int i = 0;
        for (Long l : this.marks) {
            arr[i++] = l;
        }
        tag.m_128388_(KEY_MARKS, arr);
        return tag;
    }
}
