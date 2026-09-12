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
 * 实测四百三十八：右键墓碑【不复活】，只取消这次自动复活登记（1.21.1 NeoForge 版）。
 * 详见 1.20.1 版注释；本版方法名为 Mojmap {@code interact}。
 */
@Mixin(EntityTombstone.class)
public abstract class MaidTombstoneClickMixin {

    @Inject(method = "interact", at = @At("HEAD"))
    private void promaid$cancelPendingOnClick(Player player, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND || !MaidSmartConfig.AUTO_RESURRECT_ENABLE.get()) {
            return;
        }
        EntityTombstone self = (EntityTombstone) (Object) this;
        if (self.level().isClientSide || !(player instanceof ServerPlayer sp)) {
            return;
        }
        MinecraftServer server = self.getServer();
        if (server == null) {
            return;
        }
        MaidAutoResurrect.cancelPendingOnTombstoneClick(server, sp, self.getUUID());
    }
}
