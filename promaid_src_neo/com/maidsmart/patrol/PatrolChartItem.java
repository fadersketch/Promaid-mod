package com.maidsmart.patrol;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * <ol>
 *   <li><b>右键</b>：打开界面。第一次进去只有「＋ 创建一个轨道」；建好之后每条轨道有
 *       「管理 / 改名 / 删除」。再右键一次永远是"回到这个界面"（标记期间也一样）。</li>
 *   <li><b>轨道管理页</b>：把女仆绑到这条轨道（跟建造模式的女仆管理同款）、点「开始标记」、
 *       点「连接」把首尾接成闭环。</li>
 *   <li><b>「开始标记」之后退出界面</b>：手持航图，按<b>鼠标中键</b>记一个标记
 *       （不用潜行；空中地面都行，落点就是你此刻的位置）。没点过「开始标记」时中键**没有任何用处**。</li>
 *   <li><b>标记管理页</b>（v1.3.9.2）：列出这条轨道上的每一个标记，可逐条删 / 删最后一个 / 清空。</li>
 *   <li><b>再次右键</b>回到界面 → 点「连接」：**没有门槛**，点了就闭环；原来的点数/坡度/自交/
 *       半径/挡路方块判据都只回一句 ⚠ 提醒（v1.3.9.3，
 *       {@link PatrolAdapt} 抬一抬，她自己也会绕开（见 {@code MaidBroomDrive.steerTo}）。</li>
 * </ol>
 * <p>标记只在**手持航图**时于世界里可见（菱形核 + 地面光环 + 竖针，首点青绿、末点品红； 右键女仆不再有任何巡逻含义（绑定/解绑都在界面里点）。 <p>光效只在**真正打过有效标记**之后出现（R1129）：没用过的航图是普通物品，用过之后永远带附魔光、金色名字与署名，且只认原作者。
 */
public class PatrolChartItem extends Item {

    public PatrolChartItem(Properties props) {
        super(props);
    }

    /** 【R1129】只有真正打过标记的航图才有附魔光（旧版是常驻 true）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return PatrolChartData.marked(stack);
    }

    /** 用过的航图名字变金色，并带上署名。 */
    @Override
    public Component getName(ItemStack stack) {
        if (!PatrolChartData.marked(stack)) {
            return super.getName(stack);
        }
        String owner = PatrolChartData.ownerName(stack);
        if (owner.isEmpty()) {
            return Component.translatable("item.maid_smart.patrol_chart")
                    .withStyle(ChatFormatting.GOLD);
        }
        return Component.translatable("item.maid_smart.patrol_chart.signed", owner)
                .withStyle(ChatFormatting.GOLD);
    }

    /** 用过的航图多一行金色署名描述（没用过的保持原样，一个字都不加）。 */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        if (!PatrolChartData.marked(stack)) {
            return;
        }
        String owner = PatrolChartData.ownerName(stack);
        tooltip.add(owner.isEmpty()
                ? Component.translatable("item.maid_smart.patrol_chart.marked.desc")
                        .withStyle(ChatFormatting.GOLD)
                : Component.translatable("item.maid_smart.patrol_chart.signed.desc", owner)
                        .withStyle(ChatFormatting.GOLD));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            // 【R1129】署了名的航图只认原作者；别人捡起来右键只收到一句"无法使用"。
            if (!PatrolChartData.canUse(stack, player)) {
                sp.sendSystemMessage(Component.translatable("item.maid_smart.patrol_chart.locked"));
                return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
            }
            // 服务端发 S2C 开屏（照 ScheduleBookItem → ScheduleNetworking.openFor 的口径）。
            // 这一下同时"结束标记"（）。
            PatrolNetworking.openFor(sp, hand);
        }
        return new InteractionResultHolder<>(
                level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME,
                stack);
    }
}
