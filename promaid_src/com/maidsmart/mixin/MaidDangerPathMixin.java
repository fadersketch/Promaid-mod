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
 * 女仆寻路【危险方块避让】——让她自己避开危险方块。原理：注入原版 {@code WalkNodeEvaluator} 的逐节点路
 * 径类型评估（SRG {@code m_7209_} = {@code getBlockPathType(BlockGetter, x, y, z, Mob)}，带被寻路生物上
 * 下文）。当被寻路的是女仆、且目标格命中危险表（岩浆/火/灵魂火/岩浆块/仙人掌/甜浆果丛/凋零玫瑰/细雪/石
 * 笋，面板可增删）时，把结果强制改判为 BLOCKED——该节点从寻路图剔除，她会绕开岩浆湖岸、火焰地面，宁可停
 * 下等 TLM 过远传送兜底也不往里走。
 *
 * 三个细节：① **起点格不拦**（已身处险境必须允许"逃出来"的路径，否则原地冻死）；② **三格判定**（站立格 /
 * 脚下方块 / 头顶一格灼烧型，任一命中即拦——逻辑在 DangerBlocks，与险境脱离处理器共享）；③ **仅女仆生效**
 * （mob 参数 instanceof 过滤，其余生物寻路零影响）。
 * 【多类目标】1.20.1 的 {@code FlyNodeEvaluator} 自己覆写 {@code m_7209_}（不走父类），飞行女仆完全不经过
 * {@code WalkNodeEvaluator} 的注入 → 飞行中直接越过岩浆/火面；故两类同时挂同一个 handler。
 */
    @Mixin({WalkNodeEvaluator.class, net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class})
    public abstract class MaidDangerPathMixin {

    @Inject(method = "m_7209_(Lnet/minecraft/world/level/BlockGetter;IIILnet/minecraft/world/entity/Mob;)"
            + "Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at = @At("RETURN"), cancellable = true)
    private void maidsmart$avoidDangerBlocks(BlockGetter level, int x, int y, int z, Mob mob,
                                             CallbackInfoReturnable<BlockPathTypes> cir) {
        if (!(mob instanceof EntityMaid)) {
            return; // 仅女仆生效
        }
        if (!com.maidsmart.tool.MaidScope.owned(mob)) {
            return; // v1.2.2 实测六百：无主女仆不干预（整合包的野生女仆 AI 一律不动）
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
             com.maidsmart.diag.Diag.swallow("MaidDangerPathMixin", e); // v1.1.0 实测一百零二：不再静默吞异常——若 DangerBlocks 或寻路判定抛异常，
            // 危险回避系统会静默失效导致女仆走入岩浆。改为记日志便于排查。
            com.mojang.logging.LogUtils.getLogger().warn("maidsmart: danger path mixin error", e);
        }
    }
}
