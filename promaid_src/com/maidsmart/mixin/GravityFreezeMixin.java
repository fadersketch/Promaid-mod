package com.maidsmart.mixin;

import com.maidsmart.build.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.5.79：建造区重力冻结（"搭建中的物品静止"的完整实现）。
 *
 * 背景：FallingBlock.onPlace（m_6807_）无视 flag 静默、总会 scheduleTick →
 * 沙子/沙砾/混凝土粉末/铁砧放置后约 2 tick 就检查下方，悬空即下落——
 * 即使静默放置（flag 2）也无法阻止。挖空版蓝图挖掉支撑后，上方沙子
 * 建造中就会崩落。
 *
 * 做法：拦截 FallingBlock.tick（m_213897_，下落检查唯一入口——onPlace/
 * updateShape/neighborChanged 全部调度它）。位置在【建造中的蓝图区域】
 * （BuildPlan.isBuildingRegion，按区块包围盒 O(1)）→ cancel：悬空沙块
 * 保持原位不落。建造完成（GLOBAL_PLAN 清空）→ 自动解冻恢复物理。
 *
 * 说明：完成时若有悬空重力方块（图纸的悬浮设计），会在解冻后的下一次
 * 方块更新时落下——由完成气泡提示玩家（BlueprintLib.countSuspendedGravity）。
 * SRG 名（手工编译无 refmap）：m_213897_ = FallingBlock.tick。
 */
@Mixin(FallingBlock.class)
public abstract class GravityFreezeMixin {

    @Inject(method = "m_213897_", at = @At("HEAD"), cancellable = true)
    private void promaid$freezeGravity(BlockState state, ServerLevel level, BlockPos pos,
                                       RandomSource random, CallbackInfo ci) {
        if (BuildPlan.isBuildingRegion(level, pos)) {
            ci.cancel();
        }
    }
}
