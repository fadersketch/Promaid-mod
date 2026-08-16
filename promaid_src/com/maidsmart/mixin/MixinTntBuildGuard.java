package com.maidsmart.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.328/331：TNT 建造期防自燃——女仆建造 TNT（BuildTntGuard 护栏内：线程标记
 * 或保护时间窗内），抑制 TntBlock 的两个点火入口：
 * - m_6807_（onPlace）：放置/活塞推动重放时，检测相邻带电 → 点燃 + 移除；
 * - m_6861_（neighborChanged）：邻居方块变化（红石线通电/活塞伸缩）时，检测相邻
 *   带电 → 点燃 + 移除。
 *
 * 背景（字节码实证 forge-1.20.1-47.4.21-client）：
 * - LevelChunk.m_6978_（setBlockState）服务端【无条件】调用 BlockState.m_60753_
 *   （onPlace），flag 2 静默放置【不】阻止 onPlace（v1.5.317 的"flag 2 防点燃"无效）；
 * - TntBlock.m_6807_/m_6861_ 均为 `if (level.m_276867_(pos)) { onCaughtFire; removeBlock; }`。
 *
 * v1.5.331【时间窗】：线程标记只覆盖 doPlace 放置瞬间；天机屠龙炮等机器靠
 * 【观察者→活塞→推 TNT】点火（TNT 六邻无红石，活塞推动发生在 doPlace 之外）——
 * 时间窗覆盖建造期+完工激活期+宽限期，窗口内任何入口都压制；完工
 * BlueprintLib.settleTntIgnition 只点燃邻接带电的 TNT（轰炸机当场启动，天机屠龙炮
 * 静止惰性），宽限期满后机器按正常红石逻辑点火。
 */
@Mixin(TntBlock.class)
public abstract class MixinTntBuildGuard {

    @Inject(method = "m_6807_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$guardBuildTntIgnite(BlockState state, Level level, BlockPos pos,
                                               BlockState oldState, boolean isMoving, CallbackInfo ci) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && com.maidsmart.build.BuildTntGuard.suppressing(sl)) {
            ci.cancel();
        }
    }

    @Inject(method = "m_6861_", at = @At("HEAD"), cancellable = true)
    private void maidsmart$guardBuildTntNeighbor(BlockState state, Level level, BlockPos pos,
                                                 Block block, BlockPos fromPos, boolean isMoving,
                                                 CallbackInfo ci) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && com.maidsmart.build.BuildTntGuard.suppressing(sl)) {
            ci.cancel();
        }
    }
}
