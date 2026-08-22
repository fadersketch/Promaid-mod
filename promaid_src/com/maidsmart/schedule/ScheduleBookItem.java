package com.maidsmart.schedule;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 排班表物品（v1.1.0）——纸+墨囊合成。右键打开排班界面：
 * 女仆列表 → 点选女仆 → 快捷设置（工作模式/任务/排班开关）或 日程设置
 * （00:00～24:00 分段编排一天干什么，按游戏内时间自动切换）。
 * UI 结构仿 Promaid 手册（BlueprintBookScreen）。
 *
 * v1.1.0 实测十一（用户："排班表要有附魔书那种特效"）——附魔光效。
 * 【实现教训】不能 override isFoil(m_7807_) 恒 true：原版 ServerPlayer.m_9240_
 * （每 tick 检查主手物品）对 isFoil()==true 的物品【无条件强转 ComplexItem】
 * （原版只有地图等 ComplexItem 会返回 true）——普通物品返回 true 直接
 * ClassCastException 崩服（实测十三：进世界 1 秒崩）。正确做法：初始化时给
 * 物品实例挂一个【真实但无害的附魔】（等级 0 的经验修补——不掉落、不参与
 * 任何计算、附魔台也不会再上它），原版渲染层的 isFoil 判定走
 * ItemStack.isEnchanted() → 光效与附魔书完全同源，零崩溃风险。
 */
public class ScheduleBookItem extends Item {
    public ScheduleBookItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        if (!level.m_5776_() && player instanceof ServerPlayer sp) {
            ScheduleNetworking.openFor(sp);
        }
        return new InteractionResultHolder<>(
                level.m_5776_() ? InteractionResult.SUCCESS : InteractionResult.CONSUME,
                player.m_21120_(hand));
    }

    /**
     * v1.1.0 实测十三：常驻附魔光效——createItemStack 后立即挂 0 级附魔。
     * 普通物品的附魔 NBT 键是 "Enchantments"（"StoredEnchantments" 是附魔书
     * 专用结构——挂在普通物品上不会被 EnchantmentHelper 识别）。等级 0 的
     * 附魔"存在但无效"：不改行为、不可被附魔台处理，只让 isEnchanted() 为
     * true（该判定对非耐久物品恒真路径：!isDamageable()——附魔书同款原理）
     * → 渲染层走附魔流光。零崩溃风险（不碰 isFoil/ComplexItem 链路）。
     */
    private static ItemStack withFoil(ItemStack stack) {
        try {
            CompoundTag tag = stack.m_41783_(); // getOrCreateTag
            ListTag list = new ListTag();
            CompoundTag e = new CompoundTag();
            e.m_128359_("id", "minecraft:mending");
            e.m_128359_("lvl", "0"); // short 型 NBT——存字符串由原版解析（附魔表同款写法）
            list.add(e);
            tag.m_128365_("Enchantments", list);
        } catch (Exception ignored) {
        }
        return stack;
    }

    @Override
    public ItemStack m_7968_() { // getDefaultInstance
        return withFoil(new ItemStack(this));
    }
}
