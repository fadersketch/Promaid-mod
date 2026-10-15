package com.maidsmart.mixin;

import com.maidsmart.build.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 建造区流体冻结（"搭建中的水/岩浆静止"——水不漫流、岩浆不外溢）。
 * 背景：流体块一落位就自己排上流体 tick 开始流动（{@code LiquidBlock.onPlace} 无条件被调，
 * 与 setBlock 的 flag 无关——同族结论见 {@link GravityFreezeMixin}）。墙体还没砌完水先漫出去：
 * 冲散后放的红石粉/火把/铁轨、漫过没建好的槽，女仆再回头补——来回好几轮。
 * 做法：拦截 {@code FlowingFluid.tick}（水与岩浆共用的流动/衰减唯一入口：先 scheduleTick 再
 * spread）。位置在**建造中的蓝图区域**（{@code BuildPlan.isBuildingRegion}，按区块包围盒 O(1)）
 * → **重排 + cancel**。
 * 【为什么必须"重排"】流体 tick 是自续的（调度 → 流动 → 再调度）。只 cancel 会让这一格从此
 * **永远**没有下一次 tick——完工解冻后水/岩浆也不再流（刷石机不结石头）。所以冻结期每
 * {@link #DEFER_TICKS} 拍回来问一次：区域一解冻（计划被清）立刻按原版物理继续。
 * 【映射】本树 SRG {@code m_6292_} = {@code FlowingFluid.tick}；{@code m_186469_} =
 * {@code scheduleTick(BlockPos, Fluid, int)}；{@code m_76152_} = {@code FluidState.getType()}。
 */
@Mixin(FlowingFluid.class)
public abstract class FluidFreezeMixin {

    /** 冻结期的"回来问一次"间隔（tick）：只影响解冻后第一次流动的早晚（几拍），不是流速 */
    private static final int DEFER_TICKS = 5;

    @Inject(method = "m_6292_", at = @At("HEAD"), cancellable = true)
    private void promaid$freezeFluid(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (!com.maidsmart.config.MaidSmartConfig.BUILD_AREA_PROTECTION.get()) {
            return; // 关 = 回原版（水照流、岩浆照溢）
        }
        if (!BuildPlan.isBuildingRegion(level, pos)) {
            return;
        }
        level.m_186469_(pos, state.m_76152_(), DEFER_TICKS); // 暂缓：冻结期每 5 拍再来看一眼
        ci.cancel();
    }
}
