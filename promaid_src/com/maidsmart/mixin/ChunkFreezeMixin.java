package com.maidsmart.mixin;

import com.maidsmart.build.ChunkFreeze;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.70：建造区块随机刻度冻结——1.20.1 的随机刻不在 LevelChunk，
 * 而在 ServerLevel.tickChunk（m_8714_）方法体内联循环（thunder/iceandsnow/tickBlocks）。
 * 冻结区块时直接取消整个 tickChunk：树叶不衰减消失、草不传播、作物不生长（时间静止），
 * 建造完成后解冻一切恢复。
 * 注意：注解目标必须写 SRG 名（手工编译无 refmap，Forge 不自动重映射 mojmap 名）。
 */
@Mixin(ServerLevel.class)
public abstract class ChunkFreezeMixin {
    @Inject(method = "m_8714_", at = @At("HEAD"), cancellable = true)
    private void maidSmartFreezeChunkTick(LevelChunk chunk, int randomTickCount, CallbackInfo ci) {
        ChunkPos cp = chunk.m_7697_();
        // 区块最小方块坐标 >> 4 = 区块号；按 (维度, cx, cz) 判定是否冻结
        if (ChunkFreeze.isFrozen(chunk.m_62953_().m_46472_(),
                cp.m_45604_() >> 4, cp.m_45605_() >> 4)) {
            ci.cancel();
        }
    }
}
