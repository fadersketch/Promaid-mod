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
 * （潜行右键女仆）也走同一个入口**。我们在返回值上打一个只读观察点：写成功
 * （{@code InteractionResult.consumesAction()}）就落 {@link PlayerCircle#markPlayerCircle}，
 * 清除模式则用"结果成功后 configured 还在不在"判定 → 落 {@link PlayerCircle#clearPlayerCircle}，
 * 不复制它内部任何分支逻辑。
 *
 * <p>【不改行为】只读观察 + 打标记，原方法的返回值一字不动。
 */
@Mixin(com.github.tartaricacid.touhoulittlemaid.item.ItemKappaCompass.class)
public abstract class KappaCompassPlayerCircleMixin {
    @Inject(method = "m_6880_", at = @At("RETURN"))
    private void maidsmart$rememberPlayerCircle(ItemStack stack, Player player, LivingEntity target,
                                                InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        try {
            InteractionResult result = cir.getReturnValue();
            if (result == null || !result.m_19077_()) {
                return; // 没写成功（无数据 / 维度不符 / 客户端）→ 不打标记
            }
            if (!(target instanceof EntityMaid maid)) {
                return;
            }
            var sp = maid.getSchedulePos();
            // 清除模式：clear() 会把 configured 置 false。用"configured 还在不在"分流：
            // 还在 = 刚写入锚点；不在了 = 刚被清掉。
            if (sp != null && sp.isConfigured()) {
                PlayerCircle.markPlayerCircle(maid);
            } else {
                PlayerCircle.clearPlayerCircle(maid);
            }
        } catch (Throwable ignored) { com.maidsmart.diag.Diag.swallow("KappaCompassPlayerCircleMixin", ignored);
        }
    }
}
