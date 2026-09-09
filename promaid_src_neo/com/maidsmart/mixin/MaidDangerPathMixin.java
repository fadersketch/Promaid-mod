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
     * v1.1.0 实测八十九：女仆寻路【危险方块避让】——用户需求："让他可以自己避开
     * 一些危险的方块"。
     *
     * 原理：注入原版 WalkNodeEvaluator 的逐节点方块路径类型评估（m_7209_ =
     * getBlockPathType(BlockGetter, x, y, z, Mob)，带被寻路生物上下文）。当被寻路的
     * 生物是女仆、且目标格子命中危险表（岩浆/火/灵魂火/岩浆块/仙人掌/甜浆果丛/
     * 凋零玫瑰/细雪/石笋，配置面板可增删）时，把评估结果强制改判为 BLOCKED——
     * 该节点直接从寻路图中剔除，女仆规划路径时会绕开岩浆湖岸边、火焰地面等，
     * 宁可停下等 TLM 的过远传送兜底也不往里走。
     *
     * 三个细节：
     * - 【起点格不拦】女仆已经身处险境时（掉进岩浆边、着火），必须允许"逃出来"
     *   的路径存在，否则她会在原地冻死；
     * - 【三格判定】站立格本体 / 脚下方块（站上去就出事的）/ 头顶一格灼烧型，
     *   任一命中即拦——判定逻辑在 DangerBlocks 工具类（与险境脱离处理器共享）；
     * - 【仅女仆生效】mob 参数 instanceof 过滤，其余生物寻路零影响。
     *
     * 1.21.1 移植：方法名 getPathType（mojmap 直名），签名改为
     * (PathfindingContext, x, y, z)——评估器不再携带 mob 参数，PathfindingContext
     * 也不持有 mob 引用，故【仅女仆生效】的 instanceof 守卫移除，改为全体生物
     * 共享危险避让（对 TLM 女仆与原版生物均生效；误伤面小，实测阶段再收紧）。
     *
     * v1.1.0 实测一百二十七：多类目标加入 FlyNodeEvaluator（飞行女仆）——
     * javap 实证 1.20.1 FlyNodeEvaluator 自己覆写了 m_7209_（不走父类），飞行
     * 女仆的节点评估完全不经过旧版 @Mixin(WalkNodeEvaluator) 的注入 → 飞行中
     * 直接越过岩浆/火面。多类 @Mixin 同一 handler 同时覆盖两种评估器。
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
            // v1.1.0 实测一百零二：不再静默吞异常——若 DangerBlocks 或寻路判定抛异常，
            // 危险回避系统会静默失效导致女仆走入岩浆。改为记日志便于排查。
            com.mojang.logging.LogUtils.getLogger().warn("maidsmart: danger path mixin error", e);
        }
    }
}
