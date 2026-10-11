package com.maidsmart.mixin;

import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.DangerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 女仆寻路【危险方块避让】——让她自己避开危险方块。
 *
 * 原理：注入原版 {@code WalkNodeEvaluator} 的逐节点路径类型评估（1.21.1 官方名 {@code getPathType}，签名
 * {@code (PathfindingContext, x, y, z)}）。当目标格命中危险表（岩浆/火/灵魂火/岩浆块/仙人掌/甜浆果丛/凋零
 * 玫瑰/细雪/石笋，面板可增删）时，把结果强制改判为 BLOCKED——该节点从寻路图剔除，她会绕开岩浆湖岸、火焰
 * 地面，宁可停下等 TLM 过远传送兜底也不往里走。
 *
 * 三个细节：① **起点格不拦**（已身处险境必须允许"逃出来"的路径，否则原地冻死）；② **三格判定**（站立格 /
 * 脚下方块 / 头顶一格灼烧型，任一命中即拦——逻辑在 DangerBlocks，与险境脱离处理器共享）；③ **1.21.1 无
 * mob 上下文**：评估器不再携带 mob，{@code PathfindingContext} 也不持有它，故"仅女仆生效"的 instanceof
 * 守卫移除，改为全体生物共享危险避让（误伤面小，实测阶段再收紧）。
 * 【多类目标】{@code FlyNodeEvaluator} 同 1.20.1 一样自己覆写该方法（不走父类），故两类同挂一个 handler。
 */
    @Mixin({WalkNodeEvaluator.class, net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class})
    public abstract class MaidDangerPathMixin {

    @Inject(method = "getPathType", at = @At("RETURN"), cancellable = true)
    private void maidsmart$avoidDangerBlocks(net.minecraft.world.level.pathfinder.PathfindingContext ctx, int x, int y, int z,
                                             CallbackInfoReturnable<PathType> cir) {
        // 1.21.1 重构：评估器不再接收 mob 参数，PathfindingContext 也只留 mobPosition——
        // 【仅女仆生效】改为行为层守卫（女仆寻路时把 MISC_DANGER_AVOID 临时置真，
        // 其他生物路径评估零影响）；【起点格不拦】用 mobPosition 判断。
        BlockPos mobPos = ctx.mobPosition();
        BlockGetter level = ctx.level();
        try {
            if (!MaidSmartConfig.MISC_DANGER_AVOID.get()) {
                return; // 开关关闭：整体旁路
            }
            // 起点格不拦——身处险境时必须保留"逃出来"的路径
            if (mobPos.getX() == x && mobPos.getY() == y && mobPos.getZ() == z) {
                return;
            }
            PathType cur = cir.getReturnValue();
            if (cur == PathType.BLOCKED) {
                return; // 本来就不可通行
            }
            if (DangerBlocks.cellDangerous(ctx.level(), x, y, z)) {
                cir.setReturnValue(PathType.BLOCKED);
            }
        } catch (Exception e) {
             com.maidsmart.diag.Diag.swallow("MaidDangerPathMixin", e); // v1.1.0 实测一百零二：不再静默吞异常——若 DangerBlocks 或寻路判定抛异常，
            // 危险回避系统会静默失效导致女仆走入岩浆。改为记日志便于排查。
            com.mojang.logging.LogUtils.getLogger().warn("maidsmart: danger path mixin error", e);
        }
    }
}
