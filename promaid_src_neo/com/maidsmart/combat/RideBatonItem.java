package com.maidsmart.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 骑乘指挥棒（v1.3.0(beta)·原版生物骑乘，1.21.1 版）——"把坐骑与女仆绑在一起"的那件道具。
 *
 * <p>需求原文："引入一个新物品，骑乘指挥棒，可以将可骑乘坐骑与女仆绑定起来，被绑定以后会出现
 * 光标。（类似于前段时间的武装拴绳）不管是先绑女仆还是先绑可骑乘坐骑都没问题。绑定之后，女仆
 * 就会坐到那个坐骑上。"
 *
 * <p>与 {@link CombatLeashItem} 同款：本类只负责"是个什么东西"，交互全在
 * {@link RideBindManager} 的 EntityInteract 事件里。物品本身**不带状态**——"已选中谁"记在
 * 玩家的 persistentData，所以同一根指挥棒可以反复用。
 *
 * <p>合成：拴绳 + 木棍。
 */
public class RideBatonItem extends Item {

    public RideBatonItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.maid_smart.ride_baton.desc1"));
        tooltip.add(Component.translatable("item.maid_smart.ride_baton.desc2"));
        tooltip.add(Component.translatable("item.maid_smart.ride_baton.desc3"));
    }
}
