package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 指标石右键女仆交互（v1.2.0，1.21.1 版）——手持指标石右击自己的女仆：
 * 已锁方块 → 绑定；已绑定同一只 → 解绑；绑另一只 → 换绑。
 * 未锁方块 → 拒绝并提示。骨架同 BrewManualInteractHandler。
 */
public class IndexStoneInteractHandler {

    /** 是否为指标石（按注册名判定，命名空间 maid_smart） */
    public static boolean isIndexStone(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && "maid_smart:index_stone".equals(key.toString());
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!IndexStoneService.isEnabled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        InteractionHand hand = event.getHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!isIndexStone(stack)) {
            return;
        }
        event.setCanceled(true);
        player.swing(hand); // 挥手动画
        IndexStoneService.bindOrUnbind(player, maid);
        IndexStoneNetworking.syncTo(player);
    }
}
