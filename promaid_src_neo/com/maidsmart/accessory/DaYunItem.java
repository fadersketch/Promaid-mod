package com.maidsmart.accessory;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * v1.5.393 实测八百零四：饰品「大运」——卡车头造型的飞行开路挂件（1.21.1 版）。
 *
 * <p>【需求原文】"加入一个新饰品，可以由玩家和女仆进行佩戴。佩戴的效果是使用鞘翅飞行的过程中
 * 可以直接击毁自身周边 5×5 范围内除硬度大于等于黑曜石以及硬度&lt;0 的一切方块。配置选项里面
 * 也可以写。这个效果在女仆空袭的时候生效。"（外观与名称参照玩家提供的红色卡车图。）
 *
 * <p>【两种佩戴方式】
 * <ul>
 *   <li><b>女仆</b>：放进她的 TLM 饰品栏（本模组通过 {@code bindMaidBauble} 把这件注册成一个
 *       {@code IMaidBauble}，见 {@link DaYunBauble}）——只在她的**空袭模式**下生效。</li>
 *   <li><b>玩家</b>：放进玩家的 Curios 饰品栏（全反射识别，见
 *       {@link com.maidsmart.combat.CuriosElytraCompat#hasCurio}）——只要自己在鞘翅滑翔就生效。
 *       没装 Curios 时玩家侧静默不生效，不影响物品本身与女仆侧。</li>
 * </ul>
 *
 * <p>【物品本身不带状态】不写 NBT、不记冷却——"谁戴着"由饰品栏回答。所以同一件物品反复取放、
 * 换人佩戴都不会残留。合成的是一件普通物品，佩戴位置决定效果归谁。
 *
 * <p>【提示文案必须是 translatable，不能是 literal】四行说明（含合成配方）都在 lang 里，
 * 用 literal 传语言键会**原样显示键名**而不是译文——与 {@code RideBatonItem} /
 * {@code CombatLeashItem} 同款写法（它们也是 translatable）。
 */
public class DaYunItem extends Item {

    public DaYunItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.maid_smart.da_yun.desc1"));
        tooltip.add(Component.translatable("item.maid_smart.da_yun.desc2"));
        tooltip.add(Component.translatable("item.maid_smart.da_yun.desc3"));
        tooltip.add(Component.translatable("item.maid_smart.da_yun.desc4"));
        // 实测八百五十六：佩戴者免疫鞘翅冲撞（新增效果，说明行跟着加）
        tooltip.add(Component.translatable("item.maid_smart.da_yun.desc5"));
    }
}
