package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.maidsmart.combat.MaidAutoResurrect;
import com.maidsmart.config.MaidSmartConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实测四百三十八：右键墓碑【不复活】，只取消这次自动复活登记（1.20.1 Forge 版）。
 *
 * 用户：「在女仆死亡期间右击墓碑会直接复活……使得我设的冷却毫无意义。应当改为：
 * 自动复活开始以后右击墓碑仍然跟原版一致，同时取消复活事件。」
 *
 * 本 mixin 只在服务端、主手、且自动复活开启时，把该墓碑对应的待复活登记移除；
 * 【不取消事件】——右键继续走 TLM 原版（归还物品等）。未登记 / 非主人完全不介入。
 */
@Mixin(EntityTombstone.class)
public abstract class MaidTombstoneClickMixin {

    @Inject(method = "m_6096_", at = @At("HEAD"))
    private void promaid$cancelPendingOnClick(Player player, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND || !MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        EntityTombstone self = (EntityTombstone) (Object) this;
        if (self.m_9236_().f_46443_ || !(player instanceof ServerPlayer sp)) {
            return;
        }
        MinecraftServer server = self.m_9236_().m_7654_();
        if (server == null) {
            return;
        }
        MaidAutoResurrect.cancelPendingOnTombstoneClick(server, sp, self.m_20148_());
    }
}
