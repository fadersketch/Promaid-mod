package com.maidsmart.schedule;

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
}
