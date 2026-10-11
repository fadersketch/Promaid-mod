package com.maidsmart.accessory;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 饰品「大运」——卡车头造型的飞行开路挂件：鞘翅飞行中击毁自身周边 5×5 范围内除硬度 ≥ 黑曜石
 * 以及硬度 &lt; 0 的一切方块（配置可调）。
 *
 * 【两种佩戴】① 女仆：放进她的 TLM 饰品栏（注册为 {@code IMaidBauble}，见 {@link DaYunBauble}），
 * 只在**空袭模式**下生效；② 玩家：放进 Curios 饰品栏（全反射识别，见
 * {@link com.maidsmart.combat.CuriosElytraCompat#hasCurio}），只要自己在鞘翅滑翔就生效；没装
 * Curios 时玩家侧静默不生效，不影响物品本身与女仆侧。
 * 【物品不带状态】不写 NBT、不记冷却——"谁戴着"由饰品栏回答，反复取放/换人佩戴都不残留。
 * 【文案必须 translatable，不能 literal】用 literal 传语言键会**原样显示键名**而非译文；本物品
 * 四行说明（含合成配方）都在 lang 里，必须走 translatable。
 */
public class DaYunItem extends Item {

    public DaYunItem(Properties properties) {
        super(properties);
    }

    @Override
    public void m_7373_(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.m_237115_("item.maid_smart.da_yun.desc1"));
        tooltip.add(Component.m_237115_("item.maid_smart.da_yun.desc2"));
        tooltip.add(Component.m_237115_("item.maid_smart.da_yun.desc3"));
        tooltip.add(Component.m_237115_("item.maid_smart.da_yun.desc4"));
        // 实测八百五十六：佩戴者免疫鞘翅冲撞（新增效果，说明行跟着加）
        tooltip.add(Component.m_237115_("item.maid_smart.da_yun.desc5"));
    }
}
