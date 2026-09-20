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
 * v1.2.2 实测五百九十八【粉色火焰：边点边换】。
 *
 * 原版点火只有一条路径：任何一处"放一格火"最终都落到
 * {@code BaseFireBlock.getState(level, pos)}（javap 实证：爆炸的
 * {@code Explosion.finalizeExplosion}、火自己蔓延的 {@code FireBlock.getState}、打火石/火焰弹的
 * {@code getStateForPlacement} 全部经过它）。所以这一个点就能接管"世界上出现的每一格火"——
 * 而本模组要的**只是她自己那一炸点着的那些**，于是用窗口把范围框死。
 *
 * ── 什么时候换 ──
 * {@link MaidBombing} 在 {@code level.explode(...)} 前后开/关窗口（爆炸是同步调用链，窗口不跨
 * tick），窗口内把返回值翻译成粉色火：那一炸点着的每一格直接就是粉色火，与距离无关
 * （实测日志：`粉色火焰：这一炸点着的 119 格火直接生成为粉色火`），也没有"先橙后粉"的中间态。
 *
 * ── 什么时候不换（范围边界，实测六百〇一收回来的一步）──
 * 实测六百 曾经把这里改成"开关开着就换**所有**新火"（为了兜住窗口之外那些烧到人的火）。
 * 反馈原话：<b>"你这样等于直接开挂了呀。你应该只影响女仆造成爆炸所产生的火焰，并且将它替换。
 * 而不是将所有的火焰全都开了。"</b>——粉火不蔓延、几秒自灭，"全都换"等于顺手把全世界的火蔓延
 * 关掉（含玩家自己点的火）。现在恢复成**只在窗口内换**：打火石 / 闪电 / 火焰弹 / 岩浆 /
 * 别的模组 / 世界里早就存在的原版火一概不碰。
 *
 * 【静态注入的写法】目标是 vanilla 的静态方法，处理函数必须是 static；SRG 名写死在
 * 注解里（与 LeavesBlockMixin 等一致：手工编译没有 refmap，Forge 运行期就是 SRG）。
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockPinkMixin {

    @Inject(method = "m_49245_", at = @At("RETURN"), cancellable = true)
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
        } catch (Throwable ignored) {
            // 翻译失败就保持原版火——宁可橙一点，也不能让爆炸链断在这里
        }
    }
}
