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
 * v1.2.2 实测五百九十八【粉色火焰：边点边换】。
 *
 * 原版爆炸点火只有一条路径：{@code Explosion.finalizeExplosion} 里
 * {@code level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos))}——
 * 也就是说"哪几格着火"由原版自己决定（射线扫出来的，能跑到二十格开外），
 * 我们事后再按盒子扫一遍永远会漏（实测反馈"又粉又橙"）。
 *
 * 所以这里挂在**点火那一刻**：{@code MaidBombing} 爆炸前后开/关
 * {@link PinkFireBlock#beginWindow()} 那个窗口，窗口内把 getState 的返回值翻译成粉色火——
 * 原版点出来的每一格直接就是粉色火，与距离无关，也不会有"先橙后粉"的中间态。
 *
 * 【为什么不让窗口常开】{@code getState} 也是打火石 / 火焰弹 / 闪电 / 发射器点火走的
 * 同一个入口；常开 = 把**所有**火都变粉色（含主人自己点的营火/火把引燃）。窗口只在我们
 * 自己那一炸的同步调用链里开着（爆炸是同步的，窗口不会跨 tick —— 与
 * {@code SelfPreservationBehavior} 那套"自爆风免窗口"同一手法）。
 *
 * 【静态注入的写法】目标是 vanilla 的静态方法，处理函数必须是 static；SRG 名写死在
 * 注解里（与 LeavesBlockMixin 等一致：手工编译没有 refmap，Forge 运行期就是 SRG）。
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockPinkMixin {

    @Inject(method = "m_49245_", at = @At("RETURN"), cancellable = true)
    private static void maidsmart$pinkFire(BlockGetter level, BlockPos pos,
                                           CallbackInfoReturnable<BlockState> cir) {
        if (!PinkFireBlock.inWindow()) {
            return;
        }
        try {
            BlockState pink = PinkFireBlock.fromVanillaFire(cir.getReturnValue());
            if (pink != null) {
                PinkFireBlock.countConverted();
                cir.setReturnValue(pink);
            }
        } catch (Throwable ignored) {
            // 翻译失败就保持原版火——宁可橙一点，也不能让爆炸链断在这里
        }
    }
}
