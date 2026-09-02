package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidFarmMoveTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.1.0 实测二百七十八：农场锄地触发链路修复。
 *
 * 根因：MaidFarmMoveTask.shouldMoveTo 只认"可收割（成熟作物）/可种植（空耕地+
 * 背包有种子）"两种目标。踩坏的泥土（dirt）不在其中 → searchForDestination 找不到
 * 目标 → TARGET_POS 永不设置 → MaidFarmPlantTask.canUse（要求 TARGET_POS 存在）
 * 永远 false → start 永不触发 → 挂在 start TAIL 的锄地逻辑（FarmSweepMixin.
 * tillAround）永不运行。用户实测：女仆不换锄头、无任何动作。
 *
 * 修复：shouldMoveTo 注入第三个目标判定——"需要锄的泥土"（dirt + 上方空气 +
 * 3×3 内有耕地 + 背包/主手有锄头，判定在 FarmSweepCache.isTillable）也算目标。
 * 女仆走过去后 MaidFarmPlantTask.start 触发，tillAround 锄地。
 * 与原逻辑同机制（真实消耗锄头耐久），无作弊。
 * 总开关：misc.produceTaskEnhance（与锄地逻辑同一开关）。
 */
@Mixin(MaidFarmMoveTask.class)
public abstract class FarmMoveTillMixin {
    @Inject(method = "shouldMoveTo", at = @At("TAIL"), cancellable = true)
    private void maidsmart$tillTarget(ServerLevel world, EntityMaid maid, BlockPos pos,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return; // 原逻辑已判定为目标（可收割/可种植），不重复
        }
        if (com.maidsmart.build.FarmSweepCache.isTillable(world, maid, pos)) {
            cir.setReturnValue(true);
        }
    }
}
