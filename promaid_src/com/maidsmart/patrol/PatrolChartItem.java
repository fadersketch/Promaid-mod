package com.maidsmart.patrol;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * v1.3.9【巡逻航图】物品（1.21.1）——玩家原话定的操作流程（逐字见 {@link PatrolChartScreen}）：
 *
 * <ol>
 *   <li><b>右键</b>：打开界面。第一次进去只有「＋ 创建一个轨道」；建好之后每条轨道有
 *       「管理 / 改名 / 删除」。再右键一次永远是"回到这个界面"（标记期间也一样）。</li>
 *   <li><b>轨道管理页</b>：把女仆绑到这条轨道（跟建造模式的女仆管理同款）、点「开始标记」、
 *       点「连接」把首尾接成闭环。</li>
 *   <li><b>「开始标记」之后退出界面</b>：手持航图，按<b>鼠标中键</b>记一个标记
 *       （不用潜行；空中地面都行，落点就是你此刻的位置）。没点过「开始标记」时中键**没有任何用处**。</li>
 *   <li><b>标记管理页</b>（v1.3.9.2）：列出这条轨道上的每一个标记，可逐条删 / 删最后一个 / 清空。</li>
 *   <li><b>再次右键</b>回到界面 → 点「连接」：**没有门槛**，点了就闭环；原来的点数/坡度/自交/
 *       半径/挡路方块判据都只回一句 ⚠ 提醒（v1.3.9.3，玩家原话「连接方面就不要再加入门禁了，
 *       强制连接，后果由玩家自己负责。原来那些门禁可以作为一个提醒」）。飞行时的兜底不变：
 *       {@link PatrolAdapt} 抬一抬，她自己也会绕开（见 {@code MaidBroomDrive.steerTo}）。</li>
 * </ol>
 *
 * <p>标记只在**手持航图**时于世界里可见（菱形核 + 地面光环 + 竖针，首点青绿、末点品红；
 * v1.3.9.2 起换掉了原来的立方体轮廓）——见 {@code PatrolPreviewClient}。
 * 右键女仆不再有任何巡逻含义（绑定/解绑都在界面里点）。
 *
 * <p>光效沿用管理道具惯例（{@code isFoil} 恒 true）。
 */
public class PatrolChartItem extends Item {

    public PatrolChartItem(Properties props) {
        super(props);
    }

    /** 常驻附魔光效——与蓝图手册/排班表同款 */
    @Override
    public boolean m_5812_(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.m_21120_(hand);
        if (!level.m_5776_() && player instanceof ServerPlayer sp) {
            // 服务端发 S2C 开屏（照 ScheduleBookItem → ScheduleNetworking.openFor 的口径）。
            // 这一下同时"结束标记"（玩家原话："再次通过右击可以回到刚才那个界面，点击连接"）。
            PatrolNetworking.openFor(sp, hand);
        }
        return new InteractionResultHolder<>(
                level.m_5776_() ? InteractionResult.SUCCESS : InteractionResult.CONSUME,
                stack);
    }
}
