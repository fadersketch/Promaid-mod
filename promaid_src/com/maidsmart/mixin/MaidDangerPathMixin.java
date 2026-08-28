package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.config.MaidSmartConfig;
import com.maidsmart.tool.DangerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.entity.Mob;
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
     * 方法名用 SRG（m_7209_）：本项目对原版类编译于 SRG 映射（HomingPotionMixin
     * 的 m_8119_ 同款先例），运行期即为正确名字。
     *
     * v1.1.0 实测一百二十七：多类目标加入 FlyNodeEvaluator（飞行女仆）——
     * javap 实证 1.20.1 FlyNodeEvaluator 自己覆写了 m_7209_（不走父类），飞行
     * 女仆的节点评估完全不经过旧版 @Mixin(WalkNodeEvaluator) 的注入 → 飞行中
     * 直接越过岩浆/火面。多类 @Mixin 同一 handler 同时覆盖两种评估器。
     */
    @Mixin({WalkNodeEvaluator.class, net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class})
    public abstract class MaidDangerPathMixin {

    @Inject(method = "m_7209_", at = @At("RETURN"), cancellable = true)
    private void maidsmart$avoidDangerBlocks(BlockGetter level, int x, int y, int z, Mob mob,
                                             CallbackInfoReturnable<BlockPathTypes> cir) {
        if (!(mob instanceof EntityMaid)) {
            return; // 仅女仆生效
        }
        try {
            if (!MaidSmartConfig.MISC_DANGER_AVOID.get()) {
                return; // 开关关闭：整体旁路
            }
            BlockPos mobPos = mob.m_20183_();
            // 起点格不拦——身处险境时必须保留"逃出来"的路径
            if (mobPos.m_123341_() == x && mobPos.m_123342_() == y && mobPos.m_123343_() == z) {
                return;
            }
            BlockPathTypes cur = cir.getReturnValue();
            if (cur == BlockPathTypes.BLOCKED) {
                return; // 本来就不可通行
            }
            if (DangerBlocks.cellDangerous(level, x, y, z)) {
                cir.setReturnValue(BlockPathTypes.BLOCKED);
            }
        } catch (Exception e) {
            // v1.1.0 实测一百零二：不再静默吞异常——若 DangerBlocks 或寻路判定抛异常，
            // 危险回避系统会静默失效导致女仆走入岩浆。改为记日志便于排查。
            com.mojang.logging.LogUtils.getLogger().warn("maidsmart: danger path mixin error", e);
        }
    }
}
