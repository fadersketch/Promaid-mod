package com.maidsmart.brew;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 女仆药剂手册右键女仆交互（v1.1.0 实测二百七十七）——手持手册右键自己的女仆
 * → 取消原交互 → 打开该女仆的酿造配置 GUI。骨架同 MaidCakeEatHandler（纯服务端
 * 判定 + setCanceled + 发 S2C 打开包）。
 */
public class BrewManualInteractHandler {
    /** 是否为女仆药剂手册（按注册名判定，与物品注册命名空间 maid_smart 一致） */
    public static boolean isBrewManual(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:brew_manual".equals(key.toString());
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof EntityMaid maid)
                || !maid.isOwnedBy(player)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!isBrewManual(stack)) {
            return;
        }
        event.setCanceled(true);
        player.swing(hand); // 挥手动画
        BrewManualNetworking.openFor(player, maid);
    }
}
