package com.maidsmart.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 指标石右键女仆交互（v1.2.0）——手持指标石右击自己的女仆：
 * 已锁方块 → 绑定（开始临时建造）；已绑定同一只 → 解绑；绑另一只 → 换绑。
 * 未锁方块 → 拒绝并提示（契约：必须先绑方块再绑女仆）。
 *
 * 骨架同 BrewManualInteractHandler（服务端判定 + setCanceled + 挥手），
 * 归属判定用 maid.m_21830_(player)（isOwnedBy）。
 */
public class IndexStoneInteractHandler {

    /** 是否为指标石（按注册名判定，与注册命名空间 maid_smart 一致） */
    public static boolean isIndexStone(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return false;
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.m_41720_());
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
        ItemStack stack = player.m_21120_(hand);
        if (!isIndexStone(stack)) {
            return;
        }
        event.setCanceled(true);
        player.m_6674_(hand); // 挥手动画
        IndexStoneService.bindOrUnbind(player, maid);
        IndexStoneNetworking.syncTo(player);
    }
}
