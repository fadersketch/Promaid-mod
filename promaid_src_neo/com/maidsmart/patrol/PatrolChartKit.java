package com.maidsmart.patrol;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * v1.3.9【巡逻航图 · 小工具】物品判定 + 会话清理。
 *
 * <p>── 为什么这一版把"右键女仆绑定/解绑"删了 ──
 * v1.3.8 的做法是"拿着航图右键女仆 = 绑定、潜行右键 = 解绑"。玩家实测反馈是**记不住、也发现不了**
 * （「绑定完的女仆如何解绑？」），而且服务端那条交互链有两个入口（{@code Player.interactOn} 与
 * {@code Entity.interactAt}），只挂一个会在"准星落在实体表面"时收不到。
 *
 * <p>玩家最终定的设计是把这件事整个搬进界面（「跟建造模式的女仆管理同款」）：
 * <b>绑定/解绑都在航图的轨道管理页点按钮</b>。于是这里不再拦任何右击——拿着航图右键女仆就是
 * 原版的"开女仆界面"，不会静默地绑走。本类只剩两件纯工具：物品判定 + 玩家下线时清标记态。
 *
 * <p>（命名照 {@code MaidBroomKit} 那套"kit = 判定/工具"惯例。）
 */
public final class PatrolChartKit {

    /** 事件总线注册用（{@code ProMaidMod} 里 {@code new} 一个实例挂上去） */
    public PatrolChartKit() {
    }

    /** 判定：是不是巡逻航图（按类型，与物品注册同源） */
    public static boolean isChart(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof PatrolChartItem;
    }

    /**
     * 玩家下线 → 清掉他的标记态。
     *
     * <p>{@link PatrolMarking} 是按玩家 UUID 记的静态表，没人清就会随在线时长慢慢长起来；
     * 下线是它唯一自然消失的时机（界面关闭/连接都会在服务端主动清，见 {@code openFor}）。
     */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer sp) {
                PatrolMarking.clear(sp);
            }
        } catch (Throwable ignored) {
        }
    }
}
