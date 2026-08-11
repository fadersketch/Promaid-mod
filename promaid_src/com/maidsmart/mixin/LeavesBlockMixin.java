package com.maidsmart.mixin;

import com.maidsmart.build.ChunkFreeze;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.70：蓝图树叶永不衰减——女仆逐块"打印"的装饰树不算自然树，
 * 树叶 distance 超限会在 randomTick 时掉落消失（LeavesBlock.m_213898_，
 * 经 javap 实证：m_221385_ 判定 → dropResources + removeBlock）；
 * 蓝图内树叶位置永久豁免（建造结束后也不消失）。
 * 注意：注解目标必须写 SRG 名（手工编译无 refmap，Forge 不自动重映射 mojmap 名）。
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
    @Inject(method = "m_213898_", at = @At("HEAD"), cancellable = true)
    private void maidSmartProtectLeaves(BlockState state, ServerLevel level, BlockPos pos,
                                        RandomSource random, CallbackInfo ci) {
        if (ChunkFreeze.isProtectedLeaf(level.m_46472_(), pos)) {
            ci.cancel(); // 蓝图树叶：永不随机刻度衰减
        }
    }
}
