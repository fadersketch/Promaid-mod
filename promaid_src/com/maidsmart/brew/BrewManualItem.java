package com.maidsmart.brew;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 女仆药剂手册（v1.1.0 实测二百七十七）——水瓶+书本无序合成，创造栏「工具与
 * 实用品」页。手持右击自己的女仆 → 打开该女仆的酿造配置 GUI（批量/定向两种模式）。
 * 图标与排班表同款做法：m_5812_ 恒 true 出附魔光效。
 */
public class BrewManualItem extends Item {
    public BrewManualItem(Properties props) {
        super(props);
    }

    @Override
    public boolean m_5812_(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        // 右键空气/方块：无动作（手册只对女仆生效）
        return new InteractionResultHolder<>(
                level.m_5776_() ? InteractionResult.SUCCESS : InteractionResult.CONSUME,
                player.m_21120_(hand));
    }

    /** 服务端：玩家手持手册右键自己的女仆 → 打开配置 GUI（由交互事件处理器调用） */
    public static void openFor(ServerPlayer player, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid) {
        BrewManualNetworking.openFor(player, maid);
    }
}
