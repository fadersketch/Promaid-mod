package com.maidsmart.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 武装拴绳（v1.3.7 实测六百六十七）——粉丝点单的「武装直升机二号位」道具（1.21.1 版）。
 *
 * 需求原文："引入一个新道具，应该类似于一个不会扯断，而且长度只有一格的拴绳。可以将玩家和
 * 女仆锁在一起。使用方法跟拴绳一样，右击女仆就行。解除也是同样的方式。"
 *
 * 本类只负责"是个什么东西"：右击交互在 {@link GunnerTetherManager} 的 EntityInteract
 * 事件里（与指标石右击女仆同款骨架——事件比 TLM 的 mobInteract 先到，cancel 掉就不会
 * 误开女仆 GUI）。状态全在 GunnerTetherManager + 女仆 persistentData，物品本身不带状态：
 * 同一条拴绳可以反复用（不会扯断也不会耗尽）。
 *
 * 合成：拴绳 + 铁锭×2（无序——"不会扯断"是拿铁锭把绳头加固了）。
 */
public class CombatLeashItem extends Item {

    public CombatLeashItem(Properties properties) {
        super(properties);
    }

    /** 用法提示（悬停物品时显示，两行） */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.maid_smart.combat_leash.desc1"));
        tooltip.add(Component.translatable("item.maid_smart.combat_leash.desc2"));
    }
}
