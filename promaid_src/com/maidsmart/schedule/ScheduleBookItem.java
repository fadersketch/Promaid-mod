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
 *
 * v1.1.0 实测五十：图标 = 原版「旗帜图案」物品（flower_banner_pattern）贴图原样
 * 复用（用户指定："原版有的一个物品替换成那个的贴图"）。
 *
 * v1.1.0 实测五十五【附魔光效改手册同款】（用户："打了经验修补零级没有用，不显示
 * 附魔特效；逻辑应该跟 promaid 手册一样"）：重写 m_5812_（isFoil）恒 true——与
 * BlueprintBookItem 完全同源（v1.5.285 起长期稳定运行，光效可见）。
 * 【纠正实测十三的错误结论】旧注释称"isFoil 恒 true 会被 ServerPlayer.m_9240_
 * 强转 ComplexItem 崩服"——该推断不成立：手册就是普通 Item + m_5812_=true，
 * 长期实战无任何崩溃；当年实测十三的崩溃另有原因，0 级经验修补方案属于误诊
 * 下的绕路（且实测五十五用户证实它根本不显示光效——0 级附魔在原版渲染层
 * isEnchanted 判定/法线贴图流光路径上不生效）。withFoil/mending 方案整体移除。
 */
public class ScheduleBookItem extends Item {
    public ScheduleBookItem(Properties props) {
        super(props);
    }

    /** v1.1.0 实测五十五：常驻附魔光效——手册（BlueprintBookItem.m_5812_）同款 */
    @Override
    public boolean m_5812_(ItemStack stack) {
        return true;
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

    @Override
    public ItemStack m_7968_() { // getDefaultInstance
        return new ItemStack(this);
    }
}
