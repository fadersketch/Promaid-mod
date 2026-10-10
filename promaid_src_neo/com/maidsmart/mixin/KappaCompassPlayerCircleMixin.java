package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidsmart.schedule.PlayerCircle;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【实测八百一十五·罗盘优先】河童的罗盘 → 女仆：记下"这个圈是玩家亲手画的"。
 *
 * <p>【为什么用 mixin 而不是自己写交互】罗盘的右键写锚点全在 {@code ItemKappaCompass} 内部
 * （{@code setWorkPos/setIdlePos/setSleepPos + setConfigured + restrictTo}），且**清除模式
 * （潜行右键女仆）也走同一个入口**（内部按 {@code player.isShiftKeyDown()} 分流）。我们在
 * 返回值上打一个只读观察点：写成功（{@code InteractionResult.SUCCESS} / 非 PASS）就落
 * {@link PlayerCircle#markPlayerCircle}，清除模式（结果也是 SUCCESS，但锚点被清了）则落
 * {@link PlayerCircle#clearPlayerCircle}——用"结果成功后锚点还在不在"判定是写还是清，
 * 不复制它内部任何分支逻辑。
 *
 * <p>【不改行为】只读观察 + 打标记，原方法的返回值一字不动（{@code ci.setReturnValue} 不调用）。
 */
@Mixin(com.github.tartaricacid.touhoulittlemaid.item.ItemKappaCompass.class)
public abstract class KappaCompassPlayerCircleMixin {
    @Inject(method = "interactLivingEntity", at = @At("RETURN"))
    private void maidsmart$rememberPlayerCircle(ItemStack stack, Player player, LivingEntity target,
                                                InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        try {
            InteractionResult result = cir.getReturnValue();
            if (result == null || !result.consumesAction()) {
                return; // 没写成功（无数据 / 维度不符 / 客户端）→ 不打标记
            }
            if (!(target instanceof EntityMaid maid)) {
                return;
            }
            var sp = maid.getSchedulePos();
            // 清除模式：clear() 会把 workPos 置回原点并把 configured 置 false。
            // 用"configured 还在不在"分流：还在 = 刚写入锚点；不在了 = 刚被清掉。
            if (sp != null && sp.isConfigured()) {
                PlayerCircle.markPlayerCircle(maid);
            } else {
                PlayerCircle.clearPlayerCircle(maid);
            }
        } catch (Throwable ignored) {
        }
    }
}
