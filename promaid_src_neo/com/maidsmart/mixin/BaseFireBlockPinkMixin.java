package com.maidsmart.mixin;

import com.maidsmart.combat.PinkFireBlock;
import com.maidsmart.combat.PinkFireSweep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【粉色火焰：边点边换】——世界上出现的每一格火都由 {@code BaseFireBlock.getState(level, pos)} 创建（javap
 * 实证：爆炸的 {@code Explosion.finalizeExplosion}、火蔓延的 {@code FireBlock.getState}、打火石/火焰弹的
 * {@code getStateForPlacement} 全部经过它），所以这**一个点**就能接管所有新火；本模组只要"她自己那一炸
 * 点着的那些"，故用窗口把范围框死：
 * ── 什么时候换 ──
 * {@link MaidBombing} 在 {@code level.explode(...)} 前后开/关窗口（爆炸是同步调用链，窗口不跨 tick），
 * 窗口内把返回值翻译成粉色火：那一炸点着的每一格直接就是粉色火、与距离无关，也没有"先橙后粉"的中间态。
 * ── 什么时候不换（范围边界）──
 * **只在窗口内换**：打火石 / 闪电 / 火焰弹 / 岩浆 / 别的模组 / 世界里早就存在的原版火一概不碰（曾试过
 * "开关开着就换所有新火"，会把玩家自己点的火也染粉，已收回）。
 * 【静态注入】目标是 vanilla 的静态方法，处理函数必须是 static；方法名写死在注解里（手工编译无 refmap，
 * Forge 运行期是 SRG 名、Neo 树是 Mojmap 名）。
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockPinkMixin {

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true)
    private static void maidsmart$pinkFire(BlockGetter level, BlockPos pos,
                                           CallbackInfoReturnable<BlockState> cir) {
        if (!PinkFireBlock.inWindow() || !PinkFireBlock.pinkEnabled()) {
            return; // 不是"她这一炸"点着的火（或开关关着）= 原样返回原版火
        }
        try {
            BlockState pink = PinkFireBlock.fromVanillaFire(cir.getReturnValue());
            if (pink != null) {
                PinkFireBlock.countConverted(); // 只用于"这一炸点着了 N 格"那行日志
                // v1.2.2 实测六百〇二：顺手登记一条"兜底熄灭"（不靠方块 tick 链，见 PinkFireSweep）
                PinkFireSweep.track(level, pos);
                cir.setReturnValue(pink);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("BaseFireBlockPinkMixin", ignored);
            // 翻译失败就保持原版火——宁可橙一点，也不能让爆炸链断在这里
        }
    }
}
