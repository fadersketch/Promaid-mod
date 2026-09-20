package com.maidsmart.mixin;

import com.maidsmart.combat.PinkFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.2.2 实测五百九十八【粉色火焰：边点边换】 → 实测六百【改成全局替换】。
 *
 * 原版点火只有一条路径：任何一处"放一格火"最终都落到 {@code BaseFireBlock.getState(level, pos)}
 * （javap 实证：爆炸的 {@code Explosion.finalizeExplosion}、火自己蔓延的 {@code FireBlock.getState}、
 * 打火石/火焰弹的 {@code getStateForPlacement} 全部经过它）。所以只要挂在这一个点上，
 * 就等于接管了**世界上出现的每一格火**。
 *
 * ── 五百九十八做了什么、为什么还不够 ──
 * 旧版是"爆炸前后开一个窗口，只在窗口内换"——理由是"常开会把主人自己点的火也变粉"。
 * 但实测日志（latest.log）显示：那一炸点着的 119 格火**确实全部**直接生成成了粉色火
 * （搜「粉色火焰：这一炸点着的」），而反馈里"还有橙火焰、玩家还是会被烧到"来自
 * <b>窗口之外</b>的火——世界里早先留下的、岩浆点的、别的模组点的。窗口再精确也罩不住它们。
 *
 * ── 现在（实测六百，按需求原话）──
 * "在此模式开启的时候，所有被生成的黄色火焰都会被替换成粉色火焰"：
 * {@link PinkFireBlock#pinkEnabled()}（= 面板「爆炸火焰改粉色」）开着 → **每一格新火都换**；
 * 关着 → 一个都不换（保持原版橙色火，等同旧版行为）。
 *
 * 窗口（{@link PinkFireBlock#inWindow()}）现在只用来<b>记日志</b>——"这一炸点着的 N 格火"
 * 那一行照旧，方便实测对账（爆炸是同步调用链，窗口内点着的火都算这一炸的）。
 *
 * 【静态注入的写法】目标是 vanilla 的静态方法，处理函数必须是 static；用 mojmap 名
 * （NeoForge 1.21.1 运行期就是官方名，与 GravityFreezeMixin 等一致）。
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockPinkMixin {

    @Inject(method = "getState", at = @At("RETURN"), cancellable = true)
    private static void maidsmart$pinkFire(BlockGetter level, BlockPos pos,
                                           CallbackInfoReturnable<BlockState> cir) {
        if (!PinkFireBlock.pinkEnabled()) {
            return; // 开关关着 = 完全不碰（原版橙色火）
        }
        try {
            BlockState pink = PinkFireBlock.fromVanillaFire(cir.getReturnValue());
            if (pink != null) {
                if (PinkFireBlock.inWindow()) {
                    PinkFireBlock.countConverted(); // 只用于"这一炸点着了 N 格"那行日志
                }
                cir.setReturnValue(pink);
            }
        } catch (Throwable ignored) {
            // 翻译失败就保持原版火——宁可橙一点，也不能让世界生成链断在这里
        }
    }
}
